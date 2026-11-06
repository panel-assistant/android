package io.panelassistant.android

import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.ManagedNetworkAdb
import io.panelassistant.android.panelassistant.LocalAdbEndpoint
import io.panelassistant.android.panelassistant.LocalAdbRuntime
import io.panelassistant.android.panelassistant.LocalAdbState
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.testsupport.PanelRuntimeRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class NetworkAdbRecoveryHoldTest : PanelRuntimeRig() {
    @Test fun offAfterRecoveryClearsSavedIntentWithoutTakingRecoveryCustody() =
        offWithSession(savedIntent = true, recover = true)

    @Test fun offWhileManagedClearsSavedIntentWithoutTakingSessionCustody() =
        offWithSession(savedIntent = true, recover = false)

    @Test fun offWithDefaultFalseIntentLeavesRecoveryOwnedByTheSession() =
        offWithSession(savedIntent = false, recover = true)

    @Test fun configureOffAfterRecoveryClearsSavedIntentWithoutTakingRecoveryCustody() =
        offWithSession(savedIntent = true, recover = true, configureFirst = true)

    private fun offWithSession(savedIntent: Boolean, recover: Boolean, configureFirst: Boolean = false) = runTest {
        // Any legacy mutation is a failure here. Root is available; refusing the forbidden write
        // keeps a regression from spending time waiting for OS readback on this JVM.
        val endpoint = RecoveryEndpoint()
        val root = FakeRootShell(available = true, runResult = false, onRun = { command ->
            if (command.contains("setprop")) endpoint.legacyTeardown()
        })
        val rig = rig(adbRoot = root, configure = {
            assertTrue(it.setSecurityMode(Config.SecurityMode.RELAXED))
            if (savedIntent) assertTrue(it.setNetworkAdbEnabled(true))
        })
        val runtime = LocalAdbRuntime(
            backgroundScope, endpoint, { rig.config.hardenedSecurityEnabled },
            { JSONObject() }, { false }, StandardTestDispatcher(testScheduler),
        )
        try {
            assertEquals(savedIntent, rig.config.networkAdbEnabled)
            runtime.session("compatible-panel-assistant", true)
            runCurrent()
            assertEquals(LocalAdbState(true, false), endpoint.state())
            assertTrue(ManagedNetworkAdb.managed)
            if (recover) {
                assertEquals("applied", runtime.request("adb.recover", JSONObject(), { true }).outcome)
                assertEquals(LocalAdbState(false, true), endpoint.state())
                assertFalse(ManagedNetworkAdb.managed)
                assertTrue(ManagedNetworkAdb.recoveryHold)
            }

            if (configureFirst) {
                // Configure's live-setting application seam; this is not a full HTTP-server test.
                val authority = LiveSettingAuthority(setOf("network_adb"), bootIdentity = { "test-boot" })
                val outcome = requestLiveSetting(
                    authority, rig.config, { rig.commands }, "network_adb", "false",
                    unlockKiosk = { true }, refreshAutoSleep = {},
                )
                assertTrue("Configure OFF must not mutate session-owned ADB: ${root.ran}", root.ran.isEmpty())
                assertEquals(LiveSettingRequestOutcome.APPLIED, outcome)
                assertFalse(rig.config.networkAdbEnabled)
                assertTrue(authority.pendingSnapshot().isEmpty())
                assertTrue(endpoint.listening)
                assertTrue(endpoint.bootFallback)
            }
            assertOffPreservesCustody(rig, root, endpoint, recovery = recover)

            // An accepted replacement session without local_adb retains recovery in Convenience.
            // This models capability loss, not a transport disconnect.
            runtime.session("", false)
            runCurrent()
            assertEquals(LocalAdbState(false, true), endpoint.state())
            assertTrue(ManagedNetworkAdb.recoveryHold)
            assertOffPreservesCustody(rig, root, endpoint, recovery = true)

            runtime.session("compatible-again", true)
            runCurrent()
            assertEquals(LocalAdbState(true, false), endpoint.state())
            assertFalse("fresh compatible ARM releases recovery", endpoint.listening)
            assertTrue("ARM retains the boot fallback", endpoint.bootFallback)
            assertFalse(ManagedNetworkAdb.recoveryHold)
            assertTrue(ManagedNetworkAdb.managed)
            assertFalse(rig.config.networkAdbEnabled)
        } finally {
            rig.close()
            ManagedNetworkAdb.managed = false
            ManagedNetworkAdb.recoveryHold = false
        }
    }

    private fun assertOffPreservesCustody(rig: Rig, root: FakeRootShell, endpoint: RecoveryEndpoint, recovery: Boolean) {
        val result = submitNative(rig, "network_adb", "off")
        // These are commands at the real root-effect boundary, not controller implementation calls:
        // any legacy property write or adbd restart can close the native recovery listener/fallback.
        assertTrue("OFF must not mutate session-owned ADB through root: ${root.ran}", root.ran.isEmpty())
        assertEquals(PanelAssistantCommandResult.Applied, result)
        assertFalse("OFF commits false and preference readback agrees", rig.config.networkAdbEnabled)
        assertEquals(LocalAdbState(!recovery, recovery), endpoint.state())
        assertEquals(recovery, ManagedNetworkAdb.recoveryHold)
        assertEquals("native listener custody survives OFF", recovery, endpoint.listening)
        assertTrue("native boot fallback survives OFF", endpoint.bootFallback)
    }

    /** Native daemon boundary only; the session runtime, dispatcher and controller are production. */
    private class RecoveryEndpoint : LocalAdbEndpoint {
        private var status = LocalAdbState(false, false)
        var listening = false
            private set
        var bootFallback = false
            private set
        fun legacyTeardown() { listening = false; bootFallback = false }
        override fun state() = status
        override fun arm(nonce: String): Boolean {
            status = LocalAdbState(true, false)
            listening = false
            bootFallback = true
            return true
        }
        override fun beat(nonce: String) = true
        override fun recover(nonce: String, submit: (() -> Unit) -> Boolean): Boolean? {
            if (!submit {
                status = LocalAdbState(false, true)
                listening = true
                bootFallback = true
            }) return null
            return true
        }
        override fun shell(command: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): JSONObject? =
            error("this test does not execute maintenance shell commands")
    }
}
