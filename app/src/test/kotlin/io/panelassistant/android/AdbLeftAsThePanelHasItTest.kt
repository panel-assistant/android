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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Panel Assistant leaves ADB as the panel's firmware and its owner have it (maintainer, 2026-11-16). A session
 * that grants local ADB no longer closes it; a panel an earlier version closed by itself gets it back at its
 * first session; and the owner's Network ADB switch works with a session up. The session runtime, dispatcher
 * and controller are production; only the root helper's socket is a stand-in. Built as the factory builds
 * the runtime when the owner has not chosen Keep ADB off.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class AdbLeftAsThePanelHasItTest : PanelRuntimeRig() {
    @Test fun aGrantedSessionLeavesFirmwareAdbOpenAndNeverAsksTheHelper() = panel(firmwareOpen = true) { rig, helper, runtime ->
        runtime.session("panel-assistant", true)
        runCurrent()
        advanceTimeBy(95_000)
        assertEquals("nothing arms a lease", 0, helper.arms)
        assertEquals("nothing renews one", 0, helper.beats)
        assertEquals("an unheld helper is never asked to reopen: that opens port 5555", 0, helper.recovers)
        assertTrue("ADB is still open, as the firmware had it", helper.listening)
        assertFalse(ManagedNetworkAdb.managed)
        assertFalse(rig.config.networkAdbEnabled)
    }

    @Test fun aGrantedSessionLeavesAdbClosedWhereTheOwnerHadItClosed() = panel(firmwareOpen = false) { _, helper, runtime ->
        runtime.session("panel-assistant", true)
        runCurrent()
        advanceTimeBy(95_000)
        assertEquals(0, helper.arms)
        assertEquals("never reopened", 0, helper.recovers)
        assertFalse("ADB stays closed: Panel Assistant opens nothing either", helper.listening)
    }

    @Test fun aPanelAnEarlierVersionClosedGetsAdbBackAtItsFirstSessionAndOnlyOnce() = panel(firmwareOpen = true) { _, helper, runtime ->
        // The helper still holds the lease a 1.0.x app armed by itself before this update.
        helper.holdLeftByEarlierVersion()
        assertFalse(helper.listening)
        runtime.session("panel-assistant", true)
        runCurrent()
        assertEquals("the lease is given back at once", 1, helper.recovers)
        assertTrue("ADB is open again", helper.listening)
        assertFalse(ManagedNetworkAdb.managed)
        assertEquals("and nothing closes it again", 0, helper.arms)
        advanceTimeBy(95_000)
        assertEquals(0, helper.beats)
        assertTrue(helper.listening)
        // A reconnect repeats nothing.
        runtime.session("reconnected", true)
        runCurrent()
        advanceTimeBy(35_000)
        assertEquals(1, helper.recovers)
        assertEquals(0, helper.arms)
        assertTrue(helper.listening)
    }

    @Test fun aHeldPanelWhosePanelAssistantDoesNotGrantLocalAdbGetsAdbBackToo() = panel(firmwareOpen = true) { _, helper, runtime ->
        helper.holdLeftByEarlierVersion()
        runtime.session("older-panel-assistant", false)
        runCurrent()
        assertEquals(1, helper.recovers)
        assertTrue(helper.listening)
        assertFalse(ManagedNetworkAdb.managed)
    }

    @Test fun theOwnersNetworkAdbSwitchFromHomeAssistantWorksWhileTheSessionIsUp() = panel(firmwareOpen = false) { rig, helper, runtime ->
        runtime.session("panel-assistant", true)
        runCurrent()
        assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "on"))
        assertTrue(rig.config.networkAdbEnabled)
        assertTrue(helper.listening)
        advanceTimeBy(10 * 60_000L)
        assertTrue("no renewal closes it, however long the session stays up", helper.listening)
        assertEquals(0, helper.arms + helper.beats)
    }

    private fun panel(firmwareOpen: Boolean, body: suspend TestScope.(Rig, Helper, LocalAdbRuntime) -> Unit) = runTest {
        val helper = Helper(firmwareOpen)
        val root = FakeRootShell(available = true, runResult = true, onRun = { command ->
            if (command.contains("setprop persist.adb.tcp.port 5555")) helper.listening = true
        })
        val rig = rig(adbRoot = root, configure = { assertTrue(it.setSecurityMode(Config.SecurityMode.RELAXED)) })
        val runtime = LocalAdbRuntime(
            backgroundScope, helper, { rig.config.hardenedSecurityEnabled }, { JSONObject() }, { false },
            StandardTestDispatcher(testScheduler),
        )
        try {
            body(rig, helper, runtime)
        } finally {
            rig.close()
            ManagedNetworkAdb.managed = false
            ManagedNetworkAdb.recoveryHold = false
        }
    }

    /** The root helper's recovery socket as seen from the app: a lease that stops ADB, renewals, and recovery
     *  that reopens it on port 5555 with a hold this nonce owns. */
    internal class Helper(firmwareOpen: Boolean) : LocalAdbEndpoint {
        var listening = firmwareOpen
        private var managed = false
        private var hold = false
        private var nonce = ""
        var arms = 0
            private set
        var beats = 0
            private set
        var recovers = 0
            private set
        var refuseRecovery = false
        /** Runs after the reopen was submitted and before its reply returns. */
        var afterRecoverSubmit: () -> Unit = {}
        fun holdLeftByEarlierVersion() { managed = true; listening = false; nonce = "earlier-version" }
        override fun state() = LocalAdbState(managed, hold)
        override fun arm(nonce: String): Boolean {
            arms++
            if (nonce != this.nonce) { this.nonce = nonce; hold = false }
            if (hold) return false
            managed = true; listening = false
            return true
        }
        override fun beat(nonce: String): Boolean {
            beats++
            if (!managed || hold || nonce != this.nonce) return false
            listening = false
            return true
        }
        override fun recover(nonce: String, submit: (() -> Unit) -> Boolean): Boolean? {
            if (!submit {
                recovers++
                if (!refuseRecovery) { this.nonce = nonce; hold = true; managed = false; listening = true }
            }) return null
            afterRecoverSubmit()
            return !refuseRecovery
        }
        override fun shell(command: ByteArray, timeoutMs: Int, submit: (() -> Unit) -> Boolean): JSONObject? =
            error("this test runs no maintenance shell")
    }
}
