package io.panelassistant.android

import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.ManagedNetworkAdb
import io.panelassistant.android.control.adbOpenedByHelper
import io.panelassistant.android.control.liveOwnerDoor
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
    @Test fun anOwnersOffDuringTheMigrationReplyStaysOffAndClosesAdb() = panel(session = false) { rig, helper, runtime ->
        helper.holdLeftByEarlierVersion()
        // The owner turns Network ADB off from Home Assistant after the helper reopened ADB, before its reply lands.
        helper.afterRecoverSubmit = {
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "off"))
            assertFalse("OFF completed with saved intent off", rig.config.networkAdbEnabled)
        }
        runtime.session("migrate", true)
        runCurrent()
        assertFalse("the migration reply keeps the owner's later OFF", rig.config.networkAdbEnabled)
        assertFalse("the switch reads off", rig.adb.isPersisted())
        assertFalse("the owner's later OFF leaves the listener closed", helper.listening)
    }

    @Test fun noMigrationRunsAfterTheOwnerHasMadeAnAdbChoice() = panel(session = false) { rig, helper, runtime ->
        helper.holdLeftByEarlierVersion()
        assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "off"))
        runtime.session("first", true)
        runCurrent()
        assertEquals("the owner has taken control; nothing reopens ADB behind that", 0, helper.recovers)
        assertFalse(helper.listening)
    }

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
        assertTrue(rig.adb.isPersisted())
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
        assertTrue(rig.adb.isPersisted())
    }

    @Test fun switchingKeepAdbOffOffGivesAdbBackNowAndNothingClosesItAgain() = keptOff { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "false"))
        assertTrue(helper.listening)
        assertEquals(1, helper.recovers)
        assertFalse(ManagedNetworkAdb.managed)
        assertTrue("ADB was open before, so it is back open and the switch says so", rig.adb.isPersisted())
        advanceTimeBy(5 * 60_000L)
        assertTrue(helper.listening)
        assertEquals(1, helper.arms)
        // Applying the same choice again changes nothing.
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "false"))
        assertEquals(1, helper.recovers)
    }

    @Test fun keepAdbOffEndsNetworkAdbTheAppKeptOpenAndThenArms() = panel { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertTrue(rig.adb.isPersisted())
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        assertFalse("the two choices never stand together", rig.adb.isPersisted())
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
        assertFalse(rig.adb.isPersisted())
    }

    @Test fun aGiveBackTheHelperRefusesLeavesTheOnUnappliedAndNothingReopensAdbLater() = keptOff { rig, helper, runtime ->
        helper.refuseRecovery = true
        assertNotEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertFalse(helper.listening)
        assertFalse(rig.adb.isPersisted())
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
        assertTrue(rig.adb.isPersisted())
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
            assertTrue("the switch reads what the panel now does", rig.adb.isPersisted())
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "off"))
            assertFalse("the owner's OFF closes what this app's helper opened", helper.listening)
            assertFalse(rig.adb.isPersisted())
        }

    @Test fun switchingKeepAdbOffOffOpensAdbWithTheSwitchReadingOnAndItsOffClosesIt() = panel(firmwareOpen = false) { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "false"))
        assertTrue("ending the lease reopens port 5555", helper.listening)
        assertTrue("and the switch says so", rig.adb.isPersisted())
        advanceTimeBy(5 * 60_000L)
        assertTrue("nothing closes it behind the owner's back", helper.listening)
        assertTrue(setting(rig, "network_adb", "false") in setOf(LiveSettingRequestOutcome.APPLIED, LiveSettingRequestOutcome.DEFERRED))
        awaitReal("the owner's OFF closes it") { !helper.listening && !rig.adb.isPersisted() }
    }

    @Test fun theOwnersNetworkAdbOnOnAPanelWhoseAdbWasClosedStaysOpen() = panel(firmwareOpen = false) { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertTrue(helper.listening)
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertTrue("the owner's ON stands through every later renewal", helper.listening)
        assertTrue(rig.adb.isPersisted())
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
        assertFalse(rig.adb.isPersisted())
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
        assertFalse("and the switch off", rig.adb.isPersisted())
    }
    @Test fun switchingKeepAdbOffOffGivesAdbBackOpenWhereItWasOpen() = panel(firmwareOpen = true) { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "false"))
        assertTrue(helper.listening)
        assertTrue("the switch says so, and its OFF would close it", rig.adb.isPersisted())
    }

    @Test fun keepAdbOffWithoutASessionStillEndsNetworkAdbTheAppKept() = panel(firmwareOpen = false, session = false) { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertTrue(helper.listening)
        assertTrue(setting(rig, "adb_keep_off", "true") in setOf(LiveSettingRequestOutcome.APPLIED, LiveSettingRequestOutcome.DEFERRED))
        awaitReal("no session to arm a lease, so the controller closed it") { !helper.listening && !rig.adb.isPersisted() }
        assertFalse("the two choices never stand together", rig.adb.isPersisted())
        assertTrue(rig.config.adbKeepOff)
        assertFalse(ManagedNetworkAdb.managed)
    }

    @Test fun aKeepAdbOffHandlerTheOwnersNetworkAdbOnOvertookLeavesThatOnStanding() = panel { rig, helper, _ ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertFalse(rig.config.adbKeepOff)
        // A Keep ADB off ON queued before that press runs after it: the stored choice decides, not the queued value.
        rig.commands.handleAdbKeepOff(true)
        assertTrue(rig.adb.isPersisted())
        assertTrue(helper.listening)
        assertFalse(ManagedNetworkAdb.managed)
    }

    @Test fun aRecoveryHoldEndsWithTheSessionThatAskedForIt() = panel(firmwareOpen = false) { rig, helper, runtime ->
        assertEquals("applied", runtime.request("adb.recover", JSONObject(), { true }).outcome)
        assertTrue(helper.listening)
        assertFalse("Panel Assistant's own recovery changes no owner setting", rig.config.networkAdbEnabled)
        assertTrue(ManagedNetworkAdb.ownsRuntime())
        // Panel Assistant goes away without a removal: the owner's switch works again.
        runtime.sessionEnded("panel-assistant")
        assertFalse(ManagedNetworkAdb.ownsRuntime())
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertTrue(setting(rig, "network_adb", "false") in setOf(LiveSettingRequestOutcome.APPLIED, LiveSettingRequestOutcome.DEFERRED))
        awaitReal("the owner can close it") { !helper.listening }
    }

    // The invariant every composition hold broke: once the owner has made a choice, nothing that runs later
    // without an owner at a door ends the lease (reopening port 5555) or changes either ADB setting.
    private enum class LastChoice { KEEP_OFF, KEEP_OFF_THEN_NETWORK_ON, REFUSED_NETWORK_ON, REFUSED_KEEP_OFF_OFF, REFUSED_OFF_THEN_NETWORK_OFF, QUEUED_ON_WHILE_HELD, RESTORED_ON_STILL_QUEUED }
    private enum class Trigger { RENEWALS, GRANTED_SESSION, UNGRANTED_SESSION, SESSION_ENDED, JOURNAL_REPLAY, RESTART_REPLAY, RESTORE_NETWORK_ON, RESTORE_KEEP_OFF_OFF }

    private suspend fun TestScope.choose(choice: LastChoice, rig: Rig, helper: AdbLeftAsThePanelHasItTest.Helper) {
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        when (choice) {
            LastChoice.KEEP_OFF -> Unit
            LastChoice.KEEP_OFF_THEN_NETWORK_ON -> assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
            LastChoice.REFUSED_NETWORK_ON -> { helper.refuseRecovery = true; setting(rig, "network_adb", "true") }
            LastChoice.REFUSED_KEEP_OFF_OFF -> { helper.refuseRecovery = true; setting(rig, "adb_keep_off", "false") }
            LastChoice.REFUSED_OFF_THEN_NETWORK_OFF -> {
                helper.refuseRecovery = true
                setting(rig, "adb_keep_off", "false")
                assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "off"))
            }
            // An ON that reached the journal while the lease held (a restore, say), then the owner's Keep ADB off.
            LastChoice.QUEUED_ON_WHILE_HELD -> {
                unattended(rig, "network_adb", "true")
                assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
            }
            // A restored ON that is still queued: the owner's Keep ADB off is the last choice made at a door.
            LastChoice.RESTORED_ON_STILL_QUEUED -> {
                unattended(rig, "network_adb", "true")
                assertEquals(mapOf("network_adb" to "true"), authority.pendingSnapshot())
            }
        }
        helper.refuseRecovery = false
        runCurrent()
    }

    private suspend fun TestScope.fire(trigger: Trigger, rig: Rig, runtime: LocalAdbRuntime) {
        when (trigger) {
            Trigger.RENEWALS -> advanceTimeBy(5 * 60_000L)
            Trigger.GRANTED_SESSION -> { runtime.session("again", true); runCurrent(); advanceTimeBy(35_000) }
            Trigger.UNGRANTED_SESSION -> { runtime.session("older", false); runCurrent(); advanceTimeBy(35_000) }
            Trigger.SESSION_ENDED -> { runtime.sessionEnded("panel-assistant"); advanceTimeBy(35_000) }
            Trigger.JOURNAL_REPLAY -> replay(rig)
            // A process restart replays the journal before any session has published the helper's state.
            Trigger.RESTART_REPLAY -> { ManagedNetworkAdb.managed = false; ManagedNetworkAdb.recoveryHold = false; replay(rig) }
            Trigger.RESTORE_NETWORK_ON -> unattended(rig, "network_adb", "true")
            Trigger.RESTORE_KEEP_OFF_OFF -> unattended(rig, "adb_keep_off", "false")
        }
        runCurrent()
    }

    @Test fun nothingThatRunsWithoutTheOwnerEndsTheLeaseOrChangesAnAdbSetting() {
        val broken = mutableListOf<String>()
        for (choice in LastChoice.entries) for (trigger in Trigger.entries) {
            panel(firmwareOpen = false) { rig, helper, runtime ->
                choose(choice, rig, helper)
                val before = listOf(helper.recovers, helper.listening, rig.config.adbKeepOff, rig.config.networkAdbEnabled, rig.adb.isPersisted())
                fire(trigger, rig, runtime)
                val after = listOf(helper.recovers, helper.listening, rig.config.adbKeepOff, rig.config.networkAdbEnabled, rig.adb.isPersisted())
                if (before != after) broken += "$choice then $trigger: [recovers, listening, keepOff, networkAdb, switch] $before -> $after"
            }
        }
        assertEquals(emptyList<String>(), broken)
    }

    @Test fun keepAdbOffOnRetiresANetworkAdbOnQueuedWhileTheLeaseHeld() = keptOff { rig, helper, _ ->
        unattended(rig, "network_adb", "true")
        assertEquals("a restored ON waits while the lease holds", mapOf("network_adb" to "true"), authority.pendingSnapshot())
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        assertTrue("the owner's later Keep ADB off retires it", authority.pendingSnapshot().isEmpty())
        assertFalse(helper.listening)
    }

    @Test fun aDeclinedOnAfterTheDoorGaveAdbBackLosesToALaterKeepAdbOffAndToEveryReplay() = keptOff { rig, helper, _ ->
        lastRoot.runResult = false
        assertNotEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        lastRoot.runResult = true
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        assertFalse(helper.listening)
        replay(rig)
        ManagedNetworkAdb.managed = false
        replay(rig)
        assertTrue(rig.config.adbKeepOff)
        assertFalse(rig.adb.isPersisted())
        assertFalse(helper.listening)
    }

    @Test fun aHomeAssistantOnQueuedBeforeALaterKeepAdbOffLosesToIt() = panel(firmwareOpen = false) { rig, helper, _ ->
        val gate = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        var result: PanelAssistantCommandResult? = null
        rig.commands.submit(
            io.panelassistant.android.panelassistant.PanelAssistantCommand("network_adb", nativeValue("network_adb", "on"), admit = { gate.await(20, java.util.concurrent.TimeUnit.SECONDS); null }),
            io.panelassistant.android.CommandOrigin.panelAssistant(),
        ) { result = it; done.countDown() }
        // The owner turns Keep ADB off on while Home Assistant's ON waits its turn.
        val keeping = Thread { setting(rig, "adb_keep_off", "true") }.also { it.start() }
        awaitReal("Keep ADB off is stored ahead of its handler") { rig.config.adbKeepOff }
        gate.countDown()
        assertTrue(done.await(20, java.util.concurrent.TimeUnit.SECONDS))
        keeping.join(20_000)
        runCurrent()
        assertNotEquals(PanelAssistantCommandResult.Applied, result)
        assertTrue("the owner's later Keep ADB off stands", rig.config.adbKeepOff)
        assertFalse(rig.adb.isPersisted())
        assertFalse(helper.listening)
    }

    @Test fun aHomeAssistantOnAfterKeepAdbOffStillOpensAdb() = keptOff { rig, helper, _ ->
        assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "on"))
        assertTrue(helper.listening)
        assertFalse(rig.config.adbKeepOff)
    }

    @Test fun switchingBackToRelaxedEndsALeaseOnlyHardenedModeWanted() = panel(hardened = true, firmwareOpen = false) { rig, helper, _ ->
        assertTrue(ManagedNetworkAdb.managed)
        assertTrue(rig.config.setSecurityMode(Config.SecurityMode.RELAXED))
        assertTrue(io.panelassistant.android.control.endLeaseOnRelaxed(rig.config))
        assertFalse(ManagedNetworkAdb.managed)
        assertTrue(helper.listening)
        assertTrue("the switch says so, and its OFF closes it", rig.adb.isPersisted())
        advanceTimeBy(5 * 60_000L)
        assertTrue(helper.listening)
    }

    @Test fun switchingBackToRelaxedKeepsALeaseKeepAdbOffWants() = panel(hardened = true, firmwareOpen = false) { rig, helper, _ ->
        assertTrue(rig.config.setAdbKeepOff(true))
        assertTrue(rig.config.setSecurityMode(Config.SecurityMode.RELAXED))
        assertTrue(io.panelassistant.android.control.endLeaseOnRelaxed(rig.config))
        assertTrue(ManagedNetworkAdb.managed)
        assertFalse(helper.listening)
    }

    @Test fun anImportCarryingKeepAdbOffOffNeverTurnsItOnOverALeaseNobodyChose() = panel(firmwareOpen = false, before = {
        it.holdLeftByEarlierVersion(); it.refuseRecovery = true
    }) { rig, _, _ ->
        assertTrue("the migration give-back was refused, so a lease nobody chose still holds", ManagedNetworkAdb.managed)
        assertFalse(rig.config.adbKeepOff)
        unattended(rig, "adb_keep_off", "false")
        assertFalse("an import saying off never turns Keep ADB off on", rig.config.adbKeepOff)
    }

    @Test fun aRefusalsRevertIsNotALaterOwnerChoice() = keptOff { rig, helper, _ ->
        val before = Config.adbKeepOffOnEpoch.get()
        helper.refuseRecovery = true
        setting(rig, "adb_keep_off", "false")
        setting(rig, "network_adb", "true")
        assertEquals("reverts after refusals never count as the owner choosing Keep ADB off", before, Config.adbKeepOffOnEpoch.get())
        assertTrue(rig.config.adbKeepOff)
    }

    @Test fun aKeepAdbOffQueuedBeforeALaterNetworkAdbOnNeverUndoesItOnReplay() = panel(firmwareOpen = true) { rig, helper, _ ->
        // The owner's Keep ADB off ON reaches a draining dispatcher: stored, then deferred and journalled.
        authority.applyOrQueueOutcome("adb_keep_off", "true", "false") { _, value, _ ->
            assertTrue(commitHttpLiveSetting(rig.config, io.panelassistant.android.config.SettingsRegistry.spec("adb_keep_off")!!, value) {})
            LiveSettingApplyResult.DEFERRED
        }
        assertEquals(mapOf("adb_keep_off" to "true"), authority.pendingSnapshot())
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "network_adb", "true"))
        assertTrue(authority.pendingSnapshot().isEmpty())
        replay(rig)
        assertTrue("the owner's later Network ADB ON stands after a replay", helper.listening)
        assertFalse(rig.config.adbKeepOff)
        assertTrue(rig.adb.isPersisted())
    }

    @Test fun keepAdbOffOnWithoutALeaseToArmClosesAPortTheHelperReopened() = keptOff { rig, helper, runtime ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "false"))
        assertTrue(helper.listening)
        assertFalse("the give-back writes no owner setting", rig.config.networkAdbEnabled)
        assertTrue("the switch reads on: the port is this app's own", rig.adb.isPersisted())
        // A session that cannot arm a lease, then the owner's Keep ADB off again.
        runtime.session("older-panel-assistant", false)
        runCurrent()
        assertTrue(setting(rig, "adb_keep_off", "true") in setOf(LiveSettingRequestOutcome.APPLIED, LiveSettingRequestOutcome.DEFERRED))
        awaitReal("the controller closes the port the helper reopened, and the switch reads off") { !helper.listening && !rig.adb.isPersisted() }
    }

    /** Waits in real time: the controller's settle proof sleeps on the command worker, outside test time. */
    private fun awaitReal(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!condition()) {
            assertTrue(what, System.nanoTime() < deadline)
            Thread.sleep(50)
        }
    }

    /** The first session after install consumes the one migration, as Config.takeAdbLeaseMigration does. */
    private var migrationDue = true

    /** The panel's live-setting journal, shared by every request in one test, as the service keeps one. */
    private lateinit var authority: LiveSettingAuthority
    private lateinit var lastRoot: FakeRootShell

    /** The owner's press on the settings page: its door (the production [liveOwnerDoor]), then the request. */
    private fun setting(rig: Rig, key: String, value: String): LiveSettingRequestOutcome =
        if (!liveOwnerDoor(rig.config, key, value)) LiveSettingRequestOutcome.REJECTED else unattended(rig, key, value)

    /** The same request with no owner at a door: a journal replay, a restore or an import. */
    private fun unattended(rig: Rig, key: String, value: String): LiveSettingRequestOutcome =
        requestLiveSetting(authority, rig.config, { rig.commands }, key, value, unlockKiosk = { true }, refreshAutoSleep = {})

    /** Replays the journal as the service does at start and after a reconfigure. */
    private fun replay(rig: Rig) = authority.replay { key, value, previous ->
        applyLiveSetting(rig.config, rig.commands, key, value, previous) {}.initial
    }

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
        migrationDue = true
        val choicesAtStart = Config.adbOwnerChoiceEpoch.get()
        val helper = AdbLeftAsThePanelHasItTest.Helper(firmwareOpen)
        before(helper)
        lateinit var root: FakeRootShell
        root = FakeRootShell(available = true, runResult = true, onRun = { command ->
            // A root manager that declines runs nothing.
            if (root.runResult && command.contains("setprop persist.adb.tcp.port 5555")) helper.listening = true
            if (root.runResult && command.contains("setprop persist.adb.tcp.port \"\"")) helper.listening = false
        })
        lastRoot = root
        authority = LiveSettingAuthority(setOf("network_adb", "adb_keep_off"), bootIdentity = { "test-boot" })
        // As the service wires it: a command that supersedes a setting retires its queued HTTP value.
        val rig = rig(adbRoot = root, adbListening = { helper.listening }, onExternalSettingApplied = { authority.discard(it) },
            configure = { assertTrue(it.setSecurityMode(if (hardened) Config.SecurityMode.HARDENED else Config.SecurityMode.RELAXED)) })
        val runtime = LocalAdbRuntime(
            backgroundScope, helper, { rig.config.hardenedSecurityEnabled }, { JSONObject() }, { false },
            StandardTestDispatcher(testScheduler), keepOff = { rig.config.adbKeepOff },
            helperOpened = { open -> adbOpenedByHelper(rig.config, open) },
            ownerChoices = { Config.adbOwnerChoiceEpoch.get() - choicesAtStart },
            migrationDue = { migrationDue.also { migrationDue = false } },
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
