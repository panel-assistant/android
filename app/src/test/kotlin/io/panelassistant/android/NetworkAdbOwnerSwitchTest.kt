package io.panelassistant.android

import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.ManagedNetworkAdb
import io.panelassistant.android.panelassistant.LocalAdbRuntime
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
 * Keep ADB off is the owner's opt-in: while it is on the helper's lease holds ADB off, and the owner's Network
 * ADB switch still always works, at once and with no time limit, from every door. Only Hardened mode, also the
 * owner's own choice, keeps ADB off against the switch. Driven through the panel's own setting doors with the
 * production runtime, dispatcher and controller; only the helper socket is a stand-in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class NetworkAdbOwnerSwitchTest : PanelRuntimeRig() {
    @Test fun keepAdbOffArmsAtOnceAndKeepsAdbOffAcrossRenewals() = panel { rig, helper, _ ->
        assertTrue(helper.listening)
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        assertTrue(rig.config.adbKeepOff)
        assertEquals("armed straight away, not at the next renewal", 1, helper.arms)
        assertFalse(helper.listening)
        assertTrue(ManagedNetworkAdb.managed)
        advanceTimeBy(65_000)
        assertEquals("and renewed", 2, helper.beats)
        assertFalse(helper.listening)
    }

    @Test fun theOwnersConfigureSwitchOpensAdbAtOnceAndSwitchesKeepAdbOffOff() = keptOff { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertTrue(helper.listening)
        assertFalse("one press switched the option off", rig.config.adbKeepOff)
        assertTrue(rig.config.networkAdbEnabled)
        assertFalse(ManagedNetworkAdb.managed)
        assertFalse("the hold the give-back leaves is not Panel Assistant's", ManagedNetworkAdb.ownsRuntime())
        val (arms, beats) = helper.arms to helper.beats
        advanceTimeBy(10 * 60_000L)
        assertEquals("nothing closes it again, with the session up all along", arms to beats, helper.arms to helper.beats)
        assertTrue(helper.listening)
    }

    @Test fun homeAssistantsNetworkAdbSwitchWorksTheSameWay() = keptOff { rig, helper, _ ->
        assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "on"))
        assertTrue(helper.listening)
        assertFalse(rig.config.adbKeepOff)
        assertTrue(rig.config.networkAdbEnabled)
    }

    @Test fun switchingKeepAdbOffOffGivesAdbBackNowAndNothingClosesItAgain() = keptOff { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "false"))
        assertTrue(helper.listening)
        assertEquals(1, helper.recovers)
        assertFalse(ManagedNetworkAdb.managed)
        assertFalse(rig.config.networkAdbEnabled)
        advanceTimeBy(5 * 60_000L)
        assertTrue(helper.listening)
        assertEquals(1, helper.arms)
        // Applying the same choice again changes nothing.
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "false"))
        assertEquals(1, helper.recovers)
    }

    @Test fun keepAdbOffEndsNetworkAdbTheAppKeptOpenAndThenArms() = panel { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertTrue(rig.config.networkAdbEnabled)
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        assertFalse("the two choices never stand together", rig.config.networkAdbEnabled)
        assertTrue(rig.config.adbKeepOff)
        assertTrue(ManagedNetworkAdb.managed)
        assertFalse(helper.listening)
    }

    @Test fun keepAdbOffCanBeChosenAgainInTheSameSessionAfterTheOwnerOpenedAdb() = keptOff { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertTrue(helper.listening)
        // The give-back's hold carries a nonce of its own, so the session's next arm is not refused as held.
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        assertTrue(ManagedNetworkAdb.managed)
        assertFalse(helper.listening)
        assertFalse(rig.config.networkAdbEnabled)
    }

    @Test fun aGiveBackTheHelperRefusesLeavesTheOnUnappliedAndTheNextRenewalTriesAgain() = keptOff { rig, helper, _ ->
        helper.refuseRecovery = true
        assertNotEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertFalse(helper.listening)
        assertFalse(rig.config.networkAdbEnabled)
        assertFalse("the owner asked for ADB, so the option stays off", rig.config.adbKeepOff)
        helper.refuseRecovery = false
        advanceTimeBy(31_000)
        assertTrue("the next renewal gives the lease back instead of renewing it", helper.listening)
        assertFalse(ManagedNetworkAdb.managed)
    }

    @Test fun aRecoveryPanelAssistantAsksForKeepsAdbOpenAgainstKeepAdbOff() = keptOff { rig, helper, runtime ->
        assertEquals("applied", runtime.request("adb.recover", JSONObject(), { true }).outcome)
        assertTrue(helper.listening)
        assertTrue(ManagedNetworkAdb.ownsRuntime())
        advanceTimeBy(65_000)
        assertTrue("the helper refuses to arm over this session's own hold", helper.listening)
        // The owner's OFF clears the saved intent without closing the route a removal is using.
        assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "off"))
        assertTrue(helper.listening)
    }

    @Test fun aRecoveryHoldFromAnEarlierSessionNeverBlocksTheOwnersSwitch() = panel { rig, helper, runtime ->
        assertEquals("applied", runtime.request("adb.recover", JSONObject(), { true }).outcome)
        assertTrue(ManagedNetworkAdb.ownsRuntime())
        // Panel Assistant reconnects; the removal it asked for did not happen.
        runtime.session("reconnected", true)
        runCurrent()
        assertFalse(ManagedNetworkAdb.ownsRuntime())
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertTrue(rig.config.networkAdbEnabled)
    }

    @Test fun hardenedModeKeepsAdbOffAndTheSwitchIsRefused() = panel(hardened = true) { rig, helper, runtime ->
        runtime.session("hardened", true)
        runCurrent()
        assertTrue("Hardened mode holds ADB off without Keep ADB off", ManagedNetworkAdb.managed)
        assertFalse(rig.config.adbKeepOff)
        assertFalse(ManagedNetworkAdb.ownerSwitchWorks())
        assertFalse(ManagedNetworkAdb.releaseForOwner())
        assertNotEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "on"))
        assertEquals(0, helper.recovers)
        assertFalse(helper.listening)
        assertEquals("{\"managed\":true,\"owner_switch_wait_s\":null}", ManagedNetworkAdb.statusJson())
    }

    @Test fun theStatusDocumentSaysTheOwnersSwitchWorksNowWhileKeepAdbOffHolds() = keptOff { _, _, _ ->
        assertEquals("{\"managed\":true,\"owner_switch_wait_s\":0}", ManagedNetworkAdb.statusJson())
    }

    private fun setting(rig: Rig, key: String, value: String): LiveSettingRequestOutcome =
        requestLiveSetting(
            LiveSettingAuthority(setOf(key), bootIdentity = { "test-boot" }), rig.config, { rig.commands }, key, value,
            unlockKiosk = { true }, refreshAutoSleep = {},
        )

    private fun keptOff(body: suspend TestScope.(Rig, AdbLeftAsThePanelHasItTest.Helper, LocalAdbRuntime) -> Unit) = panel { rig, helper, runtime ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        assertTrue(ManagedNetworkAdb.managed)
        assertFalse(helper.listening)
        body(rig, helper, runtime)
    }

    /** A panel whose firmware opens ADB, with a granted Panel Assistant session up throughout. */
    private fun panel(hardened: Boolean = false, body: suspend TestScope.(Rig, AdbLeftAsThePanelHasItTest.Helper, LocalAdbRuntime) -> Unit) = runTest {
        val helper = AdbLeftAsThePanelHasItTest.Helper(firmwareOpen = true)
        val root = FakeRootShell(available = true, runResult = true, onRun = { command ->
            if (command.contains("setprop persist.adb.tcp.port 5555")) helper.listening = true
        })
        val rig = rig(adbRoot = root, configure = { assertTrue(it.setSecurityMode(Config.SecurityMode.RELAXED)) })
        val runtime = LocalAdbRuntime(
            backgroundScope, helper, { hardened }, { JSONObject() }, { false },
            StandardTestDispatcher(testScheduler), keepOff = { rig.config.adbKeepOff },
        )
        // Production installs the runtime as the lease owner where it is created.
        ManagedNetworkAdb.owner = runtime
        try {
            runtime.session("panel-assistant", true)
            runCurrent()
            body(rig, helper, runtime)
        } finally {
            rig.close()
            ManagedNetworkAdb.managed = false
            ManagedNetworkAdb.recoveryHold = false
            ManagedNetworkAdb.panelAssistantHold = false
            ManagedNetworkAdb.owner = null
        }
    }
}
