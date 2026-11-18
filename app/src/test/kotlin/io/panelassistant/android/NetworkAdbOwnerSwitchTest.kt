package io.panelassistant.android

import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.ManagedNetworkAdb
import io.panelassistant.android.control.adbOpenedByHelper
import io.panelassistant.android.control.liveOwnerDoor
import io.panelassistant.android.panelassistant.LocalAdbRuntime
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.testsupport.PanelRuntimeRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.client.statement.bodyAsText
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
    // Each of these failed on the queue's first version, and each must pass.
    private fun blockWorker(rig: Rig): java.util.concurrent.CountDownLatch {
        val gate = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        rig.commands.submit(
            io.panelassistant.android.panelassistant.PanelAssistantCommand("navigate", io.panelassistant.android.panelassistant.CommandValue.Text(""),
                admit = { entered.countDown(); gate.await(20, java.util.concurrent.TimeUnit.SECONDS); PanelAssistantCommandResult.Refused("blocker") }),
            io.panelassistant.android.CommandOrigin.panelAssistant(),
        ) {}
        assertTrue(entered.await(20, java.util.concurrent.TimeUnit.SECONDS))
        return gate
    }

    @Test fun advA_aConflatedPageOnLeavesNoStampThatSilencesALaterHomeAssistantOn() = panel(firmwareOpen = false) { rig, helper, _ ->
        val gate = blockWorker(rig)
        // The owner's page ON passes its door; its request waits behind the busy worker.
        val page = Thread { setting(rig, "network_adb", "true") }.also { it.start() }
        Thread.sleep(500)
        // Home Assistant's OFF, on the same conflation key, replaces the queued page ON.
        val off = java.util.concurrent.CompletableFuture<PanelAssistantCommandResult>()
        rig.commands.submit(io.panelassistant.android.panelassistant.PanelAssistantCommand("network_adb", nativeValue("network_adb", "off"), admit = { null }),
            io.panelassistant.android.CommandOrigin.panelAssistant()) { off.complete(it) }
        gate.countDown()
        assertEquals(PanelAssistantCommandResult.Applied, off.get(20, java.util.concurrent.TimeUnit.SECONDS))
        page.join(20_000)
        assertFalse(helper.listening)
        // Much later, the owner turns Network ADB on from Home Assistant.
        val on = submitNative(rig, "network_adb", "on")
        assertEquals(PanelAssistantCommandResult.Applied, on)
        assertTrue("an Applied ON opens the port", helper.listening)
        assertTrue("an Applied ON reads on", rig.adb.isPersisted())
    }

    /** advA2, for the page's ON as one live command: replaced in the queue by a later Home Assistant OFF, it is
     *  answered rejected and opens nothing; the OFF stands. */
    @Test fun advA2_aPageOnReplacedByALaterHomeAssistantOffIsRejectedAndTheOffStands() = panel(firmwareOpen = false) { rig, helper, _ ->
        assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "on"))
        assertTrue(helper.listening)
        val gate = blockWorker(rig)
        val page = java.util.concurrent.CompletableFuture<LiveSettingRequestOutcome>()
        Thread { page.complete(setting(rig, "network_adb", "true")) }.start()
        Thread.sleep(500)
        val off = java.util.concurrent.CompletableFuture<PanelAssistantCommandResult>()
        rig.commands.submit(io.panelassistant.android.panelassistant.PanelAssistantCommand("network_adb", nativeValue("network_adb", "off"), admit = { null }),
            io.panelassistant.android.CommandOrigin.panelAssistant()) { off.complete(it) }
        gate.countDown()
        assertEquals(PanelAssistantCommandResult.Applied, off.get(20, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(LiveSettingRequestOutcome.REJECTED, page.get(20, java.util.concurrent.TimeUnit.SECONDS))
        assertFalse("the Home Assistant OFF made after the page ON is the later choice", helper.listening)
        assertFalse(rig.adb.isPersisted())
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    @Test fun aPageOnTheWorkerCannotStartInTimeIsWithdrawnAndOpensNothing() = keptOff { rig, helper, _ ->
        val gate = blockWorker(rig)
        val outcome = pressNetworkAdbOn(rig.commands, CommandOrigin.peer("192.0.2.10"), startWaitMs = 300)
        gate.countDown()
        assertEquals(LiveSettingRequestOutcome.REJECTED, outcome)
        barrier(rig)
        runCurrent()
        assertFalse("withdrawn before it ran", helper.listening)
        assertTrue(rig.config.adbKeepOff)
        assertTrue(ManagedNetworkAdb.managed)
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    /** Waits until the command worker has run everything queued before this. */
    private fun barrier(rig: Rig) {
        val done = java.util.concurrent.CountDownLatch(1)
        rig.commands.submit(io.panelassistant.android.panelassistant.PanelAssistantCommand("navigate", io.panelassistant.android.panelassistant.CommandValue.Text(""),
            admit = { PanelAssistantCommandResult.Refused("barrier") }), io.panelassistant.android.CommandOrigin.panelAssistant()) { done.countDown() }
        assertTrue(done.await(20, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test fun advB_aHomeAssistantOnWhoseTurnTimedOutNeverAnswersAppliedForAnOnThatFailed() = keptOff { rig, helper, _ ->
        val answer = java.util.concurrent.CompletableFuture<PanelAssistantCommandResult>()
        var armed = true
        helper.duringRequest = { name ->
            if (armed && name == "beat") {
                armed = false
                rig.commands.submit(io.panelassistant.android.panelassistant.PanelAssistantCommand("network_adb", nativeValue("network_adb", "on"), admit = { null }),
                    io.panelassistant.android.CommandOrigin.panelAssistant()) { answer.complete(it) }
                // The helper is slow to answer this renewal; then refuses the give-back.
                answer.get(20, java.util.concurrent.TimeUnit.SECONDS)
                helper.refuseRecovery = true
            }
        }
        advanceTimeBy(30_000); runCurrent()
        helper.duringRequest = {}
        val result = answer.get(1, java.util.concurrent.TimeUnit.SECONDS)
        runCurrent()
        if (result == PanelAssistantCommandResult.Applied) {
            assertTrue("an Applied ON opened the port", helper.listening)
            assertFalse("an Applied ON switched Keep ADB off off", rig.config.adbKeepOff)
        }
    }

    /** advB2: an ON answered Applied has taken effect when it is answered; one that could not have its turn is
     *  answered as not applied and has changed nothing. */
    @Test fun advB2_aHomeAssistantOnAnsweredAppliedHasTakenEffect() = keptOff { rig, helper, _ ->
        val answer = java.util.concurrent.CompletableFuture<PanelAssistantCommandResult>()
        var atAnswer = ""
        var armed = true
        helper.duringRequest = { name ->
            if (armed && name == "beat") {
                armed = false
                rig.commands.submit(io.panelassistant.android.panelassistant.PanelAssistantCommand("network_adb", nativeValue("network_adb", "on"), admit = { null }),
                    io.panelassistant.android.CommandOrigin.panelAssistant()) {
                    atAnswer = "listening=${helper.listening} keepOff=${rig.config.adbKeepOff} networkAdb=${rig.config.networkAdbEnabled}"
                    answer.complete(it)
                }
                answer.get(20, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        advanceTimeBy(30_000); runCurrent()
        helper.duringRequest = {}
        val result = answer.get(1, java.util.concurrent.TimeUnit.SECONDS)
        if (result == PanelAssistantCommandResult.Applied) {
            assertEquals("at the Applied answer", "listening=true keepOff=false networkAdb=true", atAnswer)
        } else {
            assertEquals("a refused ON changed nothing", "listening=false keepOff=true networkAdb=false", atAnswer)
            runCurrent()
            assertFalse(helper.listening)
            assertTrue(rig.config.adbKeepOff)
        }
    }

    @Test fun advH_aPageOnDuringTheSessionsHealthCheckIsAnsweredForWhatItDoes() =
        panel(firmwareOpen = true, session = false, healthy = { kotlinx.coroutines.delay(1_000); true }) { rig, helper, runtime ->
            assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
            runtime.session("panel-assistant", true)
            runCurrent()
            // The session's start holds the lease's queue across its suspending health check.
            val outcome = setting(rig, "network_adb", "true")
            advanceTimeBy(1_500); runCurrent()
            assertEquals("answer=$outcome listening=${helper.listening} keepOff=${rig.config.adbKeepOff} queued=${authority.pendingSnapshot()}",
                outcome == LiveSettingRequestOutcome.APPLIED, helper.listening)
        }

    @Test fun advC_aPageOnWhoseDoorTimedOutNeverReportsFailureForAnOnThatThenOpensAdb() = keptOff { rig, helper, _ ->
        val outcome = java.util.concurrent.CompletableFuture<LiveSettingRequestOutcome>()
        var armed = true
        helper.duringRequest = { name ->
            if (armed && name == "beat") {
                armed = false
                Thread { outcome.complete(setting(rig, "network_adb", "true")) }.start()
                outcome.get(30, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        advanceTimeBy(30_000); runCurrent()
        helper.duringRequest = {}
        val result = outcome.get(1, java.util.concurrent.TimeUnit.SECONDS)
        runCurrent()
        val opened = helper.listening
        assertEquals("the page's answer matches what happened: $result, listening=$opened, keepOff=${rig.config.adbKeepOff}, queued=${authority.pendingSnapshot()}",
            result == LiveSettingRequestOutcome.APPLIED, opened)
    }

    @Test fun advE1_aRefusedPanelAssistantRecoveryKeepsOwnershipOfAPortTheHelperReopened() = keptOff { rig, helper, runtime ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "false"))
        assertTrue(helper.listening)
        assertTrue(rig.adb.isPersisted())
        helper.refuseRecovery = true
        assertEquals("failed", runtime.request("adb.recover", JSONObject(), { true }).outcome)
        helper.refuseRecovery = false
        assertTrue(helper.listening)
        assertTrue("the port is still open and still this app's", rig.adb.isPersisted())
    }

    @Test fun advE2_anOwnersOffAfterARefusedPanelAssistantRecoveryClosesThePort() = keptOff { rig, helper, runtime ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "false"))
        assertTrue(helper.listening)
        helper.refuseRecovery = true
        assertEquals("failed", runtime.request("adb.recover", JSONObject(), { true }).outcome)
        helper.refuseRecovery = false
        assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "off"))
        assertFalse("an Applied OFF closes the port this app's helper reopened", helper.listening)
    }

    // A slow or unreadable helper, inside its 20 s exchange budget.
    private fun haCommand(rig: Rig, key: String, value: String, answer: java.util.concurrent.CompletableFuture<PanelAssistantCommandResult>) =
        rig.commands.submit(io.panelassistant.android.panelassistant.PanelAssistantCommand(key, nativeValue(key, value), admit = { null }),
            io.panelassistant.android.CommandOrigin.panelAssistant()) { answer.complete(it) }

    private fun awaitWaiting(runtime: LocalAdbRuntime, n: Int) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (runtime.pressesWaiting < n && System.nanoTime() < deadline) Thread.sleep(2)
        assertEquals("presses waiting on the lease queue", n, runtime.pressesWaiting)
    }

    @Test fun r2a_relaxedChosenDuringASlowRenewalEndsTheLeaseOnlyHardenedModeWanted() = panel(hardened = true, firmwareOpen = false) { rig, helper, _ ->
        assertTrue(ManagedNetworkAdb.managed)
        val relaxed = java.util.concurrent.CompletableFuture<Boolean>()
        var armed = true
        helper.duringRequest = { name ->
            if (armed && name == "beat") {
                armed = false
                Thread {
                    rig.config.setSecurityMode(Config.SecurityMode.RELAXED)
                    relaxed.complete(io.panelassistant.android.control.endLeaseOnRelaxed(rig.config))
                }.start()
                Thread.sleep(6_000)
            }
        }
        advanceTimeBy(30_000); runCurrent()
        helper.duringRequest = {}
        val ended = relaxed.get(20, java.util.concurrent.TimeUnit.SECONDS)
        advanceTimeBy(5 * 60_000L); runCurrent()
        assertFalse(rig.config.hardenedSecurityEnabled)
        assertEquals("Relaxed chosen during a renewal: endLeaseOnRelaxed=$ended, [managed, listening]",
            listOf(false, true), listOf(ManagedNetworkAdb.managed, helper.listening))
    }

    /** r2c with each give-back exchange slower than the turn's wait (19 s, past a 60 s cap that
     *  is gone): an ON already running is answered for what it did. */
    @Test fun r2c_anOnThatRunsPastTheTurnsWaitIsAnsweredForWhatItDid() = panel(timeoutS = 120) { rig, helper, runtime ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        val answer = java.util.concurrent.CompletableFuture<PanelAssistantCommandResult>()
        var armed = true
        var slow = false
        helper.duringRequest = { name ->
            if (armed && name == "beat") {
                armed = false
                haCommand(rig, "network_adb", "on", answer)
                awaitWaiting(runtime, 1)
                slow = true
            }
            if (slow && name == "recover") Thread.sleep(3_000)
        }
        stateHook = { if (slow && !rig.config.adbKeepOff) Thread.sleep(3_000) }
        advanceTimeBy(30_000); runCurrent()
        slow = false
        helper.duringRequest = {}
        stateHook = {}
        val result = answer.get(120, java.util.concurrent.TimeUnit.SECONDS)
        runCurrent()
        assertEquals("answer=$result listening=${helper.listening} keepOff=${rig.config.adbKeepOff} switch=${rig.adb.isPersisted()}",
            result == PanelAssistantCommandResult.Applied, helper.listening)
    }

    @Test fun r2d_aRefusedRecoveryFollowedByAnUnreadableHelperKeepsThePortThisAppsToClose() = keptOff { rig, helper, runtime ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "false"))
        assertTrue(helper.listening)
        assertTrue(rig.adb.isPersisted())
        helper.refuseRecovery = true
        helper.duringRequest = { if (it == "recover") stateUnreadable = 1 }
        assertEquals("failed", runtime.request("adb.recover", JSONObject(), { true }).outcome)
        helper.refuseRecovery = false
        helper.duringRequest = {}
        assertTrue(helper.listening)
        val before = "before the OFF: switch=${rig.adb.isPersisted()} openedByHelper=${rig.config.adbOpenedByHelper}"
        val off = submitNative(rig, "network_adb", "off")
        assertEquals("$before; OFF answer=$off listening=${helper.listening} switch=${rig.adb.isPersisted()}",
            off == PanelAssistantCommandResult.Applied, !helper.listening)
    }

    @Test fun r2e_aRestoredKeepAdbOffOffDuringASlowRenewalLeavesKeepAdbOffAsItIs() = keptOff { rig, helper, _ ->
        val restored = java.util.concurrent.CompletableFuture<LiveSettingRequestOutcome>()
        var armed = true
        helper.duringRequest = { name ->
            if (armed && name == "beat") {
                armed = false
                Thread { restored.complete(unattended(rig, "adb_keep_off", "false")) }.start()
                Thread.sleep(6_000)
            }
        }
        advanceTimeBy(30_000); runCurrent()
        helper.duringRequest = {}
        val outcome = restored.get(20, java.util.concurrent.TimeUnit.SECONDS)
        runCurrent()
        advanceTimeBy(5 * 60_000L); runCurrent()
        assertEquals("outcome=$outcome queued=${authority.pendingSnapshot()}",
            listOf(true, true, false), listOf(rig.config.adbKeepOff, ManagedNetworkAdb.managed, helper.listening))
    }

    @Test fun r2e2_afterARestoreDuringASlowRenewalANewSessionKeepsTheLease() = keptOff { rig, helper, runtime ->
        var armed = true
        val restored = java.util.concurrent.CompletableFuture<LiveSettingRequestOutcome>()
        helper.duringRequest = { name ->
            if (armed && name == "beat") {
                armed = false
                Thread { restored.complete(unattended(rig, "adb_keep_off", "false")) }.start()
                Thread.sleep(6_000)
            }
        }
        advanceTimeBy(30_000); runCurrent()
        helper.duringRequest = {}
        restored.get(20, java.util.concurrent.TimeUnit.SECONDS)
        runtime.session("again", true); runCurrent()
        val renewed = beatsRenewed
        advanceTimeBy(5 * 60_000L); runCurrent()
        val lapsed = beatsRenewed == renewed
        if (lapsed) helper.lapse()
        advanceTimeBy(60_000L); runCurrent()
        assertEquals("accepted BEATs in the five minutes after the new session: ${beatsRenewed - renewed} (lapsed=$lapsed); keepOff=${rig.config.adbKeepOff}",
            listOf(true, false), listOf(ManagedNetworkAdb.managed, helper.listening))
    }

    /** r2f: the page's ON starts after waiting on the worker, then runs slowly and is refused by the helper: it is
     *  answered for what it did, never pending with nothing queued. */
    @Test fun r2f_aSlowRefusedPageOnIsAnsweredForWhatItDid() = panel(timeoutS = 120) { rig, helper, runtime ->
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
        val gate = blockWorker(rig)
        val page = java.util.concurrent.CompletableFuture<LiveSettingRequestOutcome>()
        Thread { page.complete(setting(rig, "network_adb", "true")) }.start()
        var armed = true
        var slow = false
        helper.duringRequest = { name ->
            if (armed && name == "beat") {
                armed = false
                Thread.sleep(1_000)
                gate.countDown()
                awaitWaiting(runtime, 1)
                slow = true
                helper.refuseRecovery = true
            }
            if (slow && name == "recover") Thread.sleep(3_000)
        }
        stateHook = { if (slow && !rig.config.adbKeepOff) Thread.sleep(3_000) }
        advanceTimeBy(30_000); runCurrent()
        slow = false
        helper.duringRequest = {}
        stateHook = {}
        helper.refuseRecovery = false
        val outcome = page.get(120, java.util.concurrent.TimeUnit.SECONDS)
        barrier(rig)
        runCurrent()
        assertEquals("page=$outcome queued=${authority.pendingSnapshot()} listening=${helper.listening} keepOff=${rig.config.adbKeepOff}",
            outcome == LiveSettingRequestOutcome.APPLIED, helper.listening)
        assertTrue(authority.pendingSnapshot().isEmpty())
    }

    /** The settings page's Network ADB ON through its real route: POST /api/v1/config reaches the production
     *  press (pressNetworkAdbOn) and the real dispatcher, and opens ADB while Keep ADB off held it. */
    @Test fun aSettingsPagePostOfNetworkAdbOnOpensAdbWhileKeepAdbOffHeldIt() = keptOff { rig, helper, _ ->
        // The config route reads the panel's identity, which a running service has always settled.
        rig.config.ensurePanelId { "door_panel" }
        rig.config.setRaw(requireNotNull(io.panelassistant.android.config.SettingsRegistry.spec("friendly_name")), "Door panel")
        val server = io.panelassistant.android.http.routeServer(rig.config) { key, value -> unattended(rig, key, value) }
        server.javaClass.getDeclaredField("pressNetworkAdbOn").apply { isAccessible = true }
            .set(server, { origin: CommandOrigin -> pressNetworkAdbOn(rig.commands, origin) })
        var status = 0
        var body = ""
        val post = Thread {
            io.ktor.server.testing.testApplication {
                application {
                    routing {
                        route("/api/v1") {
                            with(server) { installDirectConfigPostRoute { io.panelassistant.android.config.Capabilities(appCanSu = true, networkAdb = true) } }
                        }
                    }
                }
                val response = client.post("/api/v1/config") {
                    header(io.ktor.http.HttpHeaders.ContentType, io.ktor.http.ContentType.Application.Json.toString())
                    setBody(JSONObject(mapOf("network_adb" to true)).toString())
                }
                status = response.status.value
                body = response.bodyAsText()
            }
        }.also { it.start() }
        post.join(30_000)
        assertEquals(body, 200, status)
        assertTrue(body, JSONObject(body).getBoolean("ok"))
        assertTrue("the lease gave ADB back and the ON opened it", helper.listening)
        assertFalse(rig.config.adbKeepOff)
        assertTrue(rig.adb.isPersisted())
        assertTrue("never a journalled setting", authority.pendingSnapshot().isEmpty())
    }

    @Test fun anOwnersOffDuringALiveKeepOffReleaseReplyClosesAdb() = keptOff { rig, helper, _ ->
        helper.afterRecoverSubmit = {
            assertEquals(PanelAssistantCommandResult.Applied, submitNative(rig, "network_adb", "off"))
            assertFalse(rig.config.networkAdbEnabled)
        }
        assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "false"))
        assertFalse("the later Network ADB OFF leaves the listener closed", helper.listening)
        assertFalse("the later Network ADB OFF leaves the switch off", rig.adb.isPersisted())
    }

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

    // An owner's press made while the root helper is answering an earlier lease request ends exactly as the same
    // press made straight after that request: every lease request the app makes, crossed with every owner press,
    // with the helper acting on the request as it is submitted and as it replies.
    private enum class Request(val helperRequest: String, val onCommandWorker: Boolean = false) {
        MIGRATION_GIVE_BACK("recover"),
        SESSION_ARM("arm"),
        RENEWAL_ARM("arm"),
        RENEWAL("beat"),
        KEEP_ADB_OFF_OFF_GIVE_BACK("recover"),
        PAGE_ON_GIVE_BACK("recover", onCommandWorker = true),
        HOME_ASSISTANT_ON_GIVE_BACK("recover", onCommandWorker = true),
        KEEP_ADB_OFF_ON_ARM("arm", onCommandWorker = true),
        PANEL_ASSISTANT_RECOVERY("recover"),
    }

    /** [firstTurnOnCommandWorker]: the press reaches the lease's queue first from the command worker (a Home
     *  Assistant command, or a setting's handler), not from the settings page's own door. */
    private enum class Press(val key: String, val value: String, val homeAssistant: Boolean, val firstTurnOnCommandWorker: Boolean) {
        HOME_ASSISTANT_ON("network_adb", "true", true, true),
        HOME_ASSISTANT_OFF("network_adb", "false", true, true),
        PAGE_ON("network_adb", "true", false, true),
        PAGE_OFF("network_adb", "false", false, true),
        KEEP_ADB_OFF_ON("adb_keep_off", "true", false, true),
        KEEP_ADB_OFF_OFF("adb_keep_off", "false", false, false),
    }

    private data class Ending(val answer: String, val listening: Boolean, val leaseHolds: Boolean, val switchReadsOn: Boolean, val networkAdb: Boolean, val keepAdbOff: Boolean, val queued: Map<String, String>)

    @Test fun aPressDuringAnyHelperRequestEndsAsTheSamePressStraightAfterIt() {
        val differ = mutableListOf<String>()
        val notDuring = mutableListOf<String>()
        for (request in Request.entries) for (press in Press.entries) {
            val after = ending(request, press, atReply = false, during = false)
            for (atReply in listOf(false, true)) {
                var waited = false
                val during = ending(request, press, atReply, during = true) { waited = it }
                val case = "$request x $press, the helper acting at ${if (atReply) "its reply" else "submission"}"
                if (during != after) differ += "$case: straight after $after; during $during"
                if (waitsOnTheLeaseQueue(request, press) && !waited) notDuring += case
            }
        }
        assertEquals(emptyList<String>(), differ)
        assertEquals("each press reached the lease's queue while the request was in flight", emptyList<String>(), notDuring)
    }

    /** The command worker runs one command at a time, so a press that first reaches the lease's queue from it,
     *  during a request that runs on it, waits in the command queue instead: also straight after the request. */
    private fun waitsOnTheLeaseQueue(request: Request, press: Press) = !(request.onCommandWorker && press.firstTurnOnCommandWorker)

    private fun ending(request: Request, press: Press, atReply: Boolean, during: Boolean, waited: (Boolean) -> Unit = {}): Ending {
        lateinit var ending: Ending
        panel(
            session = request != Request.MIGRATION_GIVE_BACK && request != Request.SESSION_ARM,
            before = { if (request == Request.MIGRATION_GIVE_BACK) it.holdLeftByEarlierVersion() },
            settlesInstantly = true,
        ) { rig, helper, runtime ->
            when (request) {
                Request.MIGRATION_GIVE_BACK, Request.KEEP_ADB_OFF_ON_ARM -> Unit
                // Keep ADB off chosen before Panel Assistant's session: the session arms the lease.
                Request.SESSION_ARM -> assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
                else -> {
                    assertEquals(LiveSettingRequestOutcome.APPLIED, setting(rig, "adb_keep_off", "true"))
                    assertTrue(ManagedNetworkAdb.managed)
                }
            }
            if (request == Request.RENEWAL_ARM) helper.lapse()
            helper.actsAtReply = atReply
            var pressed: java.util.concurrent.CompletableFuture<String>? = null
            if (during) {
                var armed = true
                helper.duringRequest = { name ->
                    if (armed && name == request.helperRequest) {
                        armed = false
                        val before = runtime.pressesWaiting
                        val answer = press(rig, press).also { pressed = it }
                        if (waitsOnTheLeaseQueue(request, press)) {
                            val deadline = System.nanoTime() + 10_000_000_000L
                            while (runtime.pressesWaiting == before && !answer.isDone && System.nanoTime() < deadline) Thread.sleep(2)
                            waited(runtime.pressesWaiting > before)
                        }
                    }
                }
            }
            when (request) {
                Request.MIGRATION_GIVE_BACK -> { runtime.session("migrate", true); runCurrent() }
                Request.SESSION_ARM -> { runtime.session("arm", true); runCurrent() }
                Request.RENEWAL_ARM, Request.RENEWAL -> { advanceTimeBy(30_000); runCurrent() }
                Request.KEEP_ADB_OFF_OFF_GIVE_BACK -> setting(rig, "adb_keep_off", "false")
                Request.PAGE_ON_GIVE_BACK -> setting(rig, "network_adb", "true")
                Request.HOME_ASSISTANT_ON_GIVE_BACK -> submitNative(rig, "network_adb", "on")
                Request.KEEP_ADB_OFF_ON_ARM -> setting(rig, "adb_keep_off", "true")
                Request.PANEL_ASSISTANT_RECOVERY -> {
                    val reply = async { runtime.request("adb.recover", JSONObject(), { true }) }
                    runCurrent()
                    assertEquals("applied", reply.await().outcome)
                }
            }
            helper.duringRequest = {}
            val answer = if (during) requireNotNull(pressed) { "$request reached the helper" }.get(20, java.util.concurrent.TimeUnit.SECONDS)
                else press(rig, press).get(20, java.util.concurrent.TimeUnit.SECONDS)
            runCurrent()
            advanceTimeBy(65_000)
            runCurrent()
            ending = Ending(answer, helper.listening, ManagedNetworkAdb.managed, rig.adb.isPersisted(), rig.config.networkAdbEnabled, rig.config.adbKeepOff, authority.pendingSnapshot())
        }
        return ending
    }

    /** The owner's press from Home Assistant (a native command) or the settings page (on a thread of its own). */
    private fun press(rig: Rig, press: Press): java.util.concurrent.CompletableFuture<String> {
        val answer = java.util.concurrent.CompletableFuture<String>()
        if (press.homeAssistant) {
            rig.commands.submit(
                io.panelassistant.android.panelassistant.PanelAssistantCommand(press.key, nativeValue(press.key, if (press.value == "true") "on" else "off"), admit = { null }),
                io.panelassistant.android.CommandOrigin.panelAssistant(),
            ) { answer.complete(it.toString()) }
        } else {
            Thread { answer.complete(runCatching { setting(rig, press.key, press.value).name }.getOrElse { "threw ${it.javaClass.simpleName}" }) }.start()
        }
        return answer
    }

    /** Waits in real time: the controller's settle proof sleeps on the command worker, outside test time. */
    private fun awaitReal(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!condition()) {
            assertTrue(what, System.nanoTime() < deadline)
            Thread.sleep(50)
        }
    }

    /** Runs at every STATUS exchange the runtime makes. */
    @Volatile private var stateHook: () -> Unit = {}
    /** The next n STATUS exchanges fail (the helper cannot be read). */
    @Volatile private var stateUnreadable = 0
    /** BEATs the helper accepted (renewed the lease). */
    @Volatile private var beatsRenewed = 0

    /** The first session after install consumes the one migration, as Config.takeAdbLeaseMigration does. */
    private var migrationDue = true

    /** The panel's live-setting journal, shared by every request in one test, as the service keeps one. */
    private lateinit var authority: LiveSettingAuthority
    private lateinit var lastRoot: FakeRootShell

    /** The owner's press on the settings page, as DirectConfigPost makes it: a Network ADB ON is the live command
     *  ([pressNetworkAdbOn]); anything else passes its door ([liveOwnerDoor]), then the request. */
    private fun setting(rig: Rig, key: String, value: String): LiveSettingRequestOutcome = when {
        key == "network_adb" && value == "true" -> pressNetworkAdbOn(rig.commands, CommandOrigin.peer("192.0.2.10"))
        !liveOwnerDoor(rig.config, key, value) -> LiveSettingRequestOutcome.REJECTED
        else -> unattended(rig, key, value)
    }

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
        settlesInstantly: Boolean = false,
        healthy: suspend () -> Boolean = { true },
        timeoutS: Long = 60,
        body: suspend TestScope.(Rig, AdbLeftAsThePanelHasItTest.Helper, LocalAdbRuntime) -> Unit,
    ) = runTest(timeout = kotlin.time.Duration.parse("${timeoutS}s")) {
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
        val rig = rig(adbRoot = root, adbListening = { helper.listening }, adbSettlesInstantly = settlesInstantly, onExternalSettingApplied = { authority.discard(it) },
            configure = { assertTrue(it.setSecurityMode(if (hardened) Config.SecurityMode.HARDENED else Config.SecurityMode.RELAXED)) })
        stateHook = {}
        stateUnreadable = 0
        beatsRenewed = 0
        // The helper as the runtime sees it: a STATUS exchange a test can slow down or fail, renewals counted.
        val endpoint = object : io.panelassistant.android.panelassistant.LocalAdbEndpoint by helper {
            override fun beat(nonce: String, submit: (() -> Unit) -> Boolean): Boolean =
                helper.beat(nonce, submit).also { if (it) beatsRenewed++ }
            override fun state(): io.panelassistant.android.panelassistant.LocalAdbState? {
                stateHook()
                if (stateUnreadable > 0) { stateUnreadable--; return null }
                return helper.state()
            }
        }
        val runtime = LocalAdbRuntime(
            backgroundScope, endpoint, { rig.config.hardenedSecurityEnabled }, { JSONObject() }, { false },
            StandardTestDispatcher(testScheduler), keepOff = { rig.config.adbKeepOff },
            helperOpened = { open -> adbOpenedByHelper(rig.config, open) },
            ownerChoices = { Config.adbOwnerChoiceEpoch.get() - choicesAtStart },
            migrationDue = { migrationDue.also { migrationDue = false } },
            healthy = healthy,
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
