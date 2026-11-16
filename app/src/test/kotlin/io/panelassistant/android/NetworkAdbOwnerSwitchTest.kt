package io.panelassistant.android

import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.ManagedNetworkAdb
import io.panelassistant.android.control.adbReopenedByHelper
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
        assertTrue("ADB was open before, so it is back open and the switch says so", rig.config.networkAdbEnabled)
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

    @Test fun aGiveBackTheHelperRefusesLeavesTheOnUnappliedAndNothingReopensAdbLater() = keptOff { rig, helper, runtime ->
        helper.refuseRecovery = true
        assertNotEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertFalse(helper.listening)
        assertFalse(rig.config.networkAdbEnabled)
        assertTrue("the lease still holds, so Keep ADB off still says so", rig.config.adbKeepOff)
        helper.refuseRecovery = false
        // A new session ends no lease the owner still wants.
        runtime.session("reconnected", true)
        runCurrent()
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertFalse("renewals only renew: nothing gives ADB back behind the owner", helper.listening)
        assertTrue(ManagedNetworkAdb.managed)
        assertEquals("the owner's next press works", LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertTrue(helper.listening)
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

    @Test fun aPanelAnEarlierVersionClosedShowsNetworkAdbOnOnceItIsBackAndItsOffClosesIt() =
        panel(before = { it.holdLeftByEarlierVersion() }) { rig, helper, _ ->
            assertTrue("the give-back reopened ADB", helper.listening)
            assertTrue("the switch reads what the panel now does", rig.config.networkAdbEnabled)
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "off"))
            assertFalse("the owner's OFF closes what this app's helper opened", helper.listening)
            assertFalse(rig.config.networkAdbEnabled)
        }

    @Test fun switchingKeepAdbOffOffOpensAdbWithTheSwitchReadingOnAndItsOffClosesIt() = panel(firmwareOpen = false) { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "false"))
        assertTrue("ending the lease reopens port 5555", helper.listening)
        assertTrue("and the switch says so", rig.config.networkAdbEnabled)
        advanceTimeBy(5 * 60_000L)
        assertTrue("nothing closes it behind the owner's back", helper.listening)
        assertTrue(setting(rig, "network_adb", "false") in setOf(LiveSettingRequestOutcome.APPLIED, LiveSettingRequestOutcome.DEFERRED))
        awaitReal("the owner's OFF closes it") { !helper.listening && !rig.config.networkAdbEnabled }
    }

    @Test fun theOwnersNetworkAdbOnOnAPanelWhoseAdbWasClosedStaysOpen() = panel(firmwareOpen = false) { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertTrue(helper.listening)
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertTrue("the owner's ON stands through every later renewal", helper.listening)
        assertTrue(rig.config.networkAdbEnabled)
        assertFalse(rig.config.adbKeepOff)
    }

    @Test fun aKeepAdbOffOffTheHelperRefusesLeavesTheOptionOnAndIsNeverRetried() = panel(firmwareOpen = false) { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        helper.refuseRecovery = true
        setting(rig, "adb_keep_off", "false")
        assertTrue("the lease still holds: the helper refused", ManagedNetworkAdb.managed)
        assertTrue("so the setting still says what the panel does", rig.config.adbKeepOff)
        assertFalse(helper.listening)
        helper.refuseRecovery = false
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertFalse("no later renewal or replay reopens it", helper.listening)
        assertTrue(rig.config.adbKeepOff)
        assertFalse(rig.config.networkAdbEnabled)
    }

    @Test fun anExplicitNetworkAdbOffAfterARefusedGiveBackKeepsAdbClosed() = keptOff { rig, helper, _ ->
        helper.refuseRecovery = true
        setting(rig, "adb_keep_off", "false")
        assertTrue(ManagedNetworkAdb.managed)
        assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "off"))
        assertFalse(helper.listening)
        helper.refuseRecovery = false
        advanceTimeBy(31_000)
        runCurrent()
        assertFalse("an explicit later OFF keeps the port closed", helper.listening)
        assertFalse("and the switch off", rig.config.networkAdbEnabled)
    }
    @Test fun switchingKeepAdbOffOffGivesAdbBackOpenWhereItWasOpen() = panel(firmwareOpen = true) { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "false"))
        assertTrue(helper.listening)
        assertTrue("the switch says so, and its OFF would close it", rig.config.networkAdbEnabled)
    }

    @Test fun keepAdbOffWithoutASessionStillEndsNetworkAdbTheAppKept() = panel(firmwareOpen = false, session = false) { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertTrue(helper.listening)
        assertTrue(setting(rig, "adb_keep_off", "true") in setOf(LiveSettingRequestOutcome.APPLIED, LiveSettingRequestOutcome.DEFERRED))
        awaitReal("no session to arm a lease, so the controller closed it") { !helper.listening && !rig.config.networkAdbEnabled }
        assertFalse("the two choices never stand together", rig.config.networkAdbEnabled)
        assertTrue(rig.config.adbKeepOff)
        assertFalse(ManagedNetworkAdb.managed)
    }

    @Test fun aKeepAdbOffHandlerTheOwnersNetworkAdbOnOvertookLeavesThatOnStanding() = panel { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertFalse(rig.config.adbKeepOff)
        // A Keep ADB off ON queued before that press runs after it: the stored choice decides, not the queued value.
        rig.commands.handleAdbKeepOff(true)
        assertTrue(rig.config.networkAdbEnabled)
        assertTrue(helper.listening)
        assertFalse(ManagedNetworkAdb.managed)
    }

    @Test fun aRecoveryHoldEndsWithTheSessionThatAskedForIt() = panel(firmwareOpen = false) { rig, helper, runtime ->
        assertEquals("applied", runtime.request("adb.recover", JSONObject(), { true }).outcome)
        assertTrue(helper.listening)
        assertTrue("the switch reads on while the recovery keeps ADB open", rig.config.networkAdbEnabled)
        assertTrue(ManagedNetworkAdb.ownsRuntime())
        // Panel Assistant goes away without a removal.
        runtime.sessionEnded("panel-assistant")
        assertFalse(ManagedNetworkAdb.ownsRuntime())
        assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "off"))
        assertFalse("the owner can close it", helper.listening)
    }

    /** Waits in real time: the controller's settle proof sleeps on the command worker, outside test time. */
    private fun awaitReal(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!condition()) {
            assertTrue(what, System.nanoTime() < deadline)
            Thread.sleep(50)
        }
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

    /** A panel (whose firmware opens ADB unless [firmwareOpen] is false) with a granted Panel Assistant session
     *  up throughout, unless [session] is false. [before] sets the helper up before the session. */
    private fun panel(
        hardened: Boolean = false,
        firmwareOpen: Boolean = true,
        session: Boolean = true,
        before: (AdbLeftAsThePanelHasItTest.Helper) -> Unit = {},
        body: suspend TestScope.(Rig, AdbLeftAsThePanelHasItTest.Helper, LocalAdbRuntime) -> Unit,
    ) = runTest {
        val helper = AdbLeftAsThePanelHasItTest.Helper(firmwareOpen)
        before(helper)
        val root = FakeRootShell(available = true, runResult = true, onRun = { command ->
            if (command.contains("setprop persist.adb.tcp.port 5555")) helper.listening = true
            if (command.contains("setprop persist.adb.tcp.port \"\"")) helper.listening = false
        })
        val rig = rig(adbRoot = root, adbListening = { helper.listening }, configure = { assertTrue(it.setSecurityMode(Config.SecurityMode.RELAXED)) })
        val runtime = LocalAdbRuntime(
            backgroundScope, helper, { hardened }, { JSONObject() }, { false },
            StandardTestDispatcher(testScheduler), keepOff = { rig.config.adbKeepOff },
            onReopened = { adbReopenedByHelper(rig.config) },
        )
        // Production installs the runtime as the lease owner where it is created, with the same reopen rule.
        ManagedNetworkAdb.owner = runtime
        try {
            if (session) {
                runtime.session("panel-assistant", true)
                runCurrent()
            }
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
