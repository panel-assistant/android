package io.github.maxlyth.hapaneld.panelassistant

import io.github.maxlyth.hapaneld.HaAuthOwner
import io.github.maxlyth.hapaneld.sensors.HaApiSession
import io.github.maxlyth.hapaneld.sensors.HaApiSessionProvider
import io.github.maxlyth.hapaneld.sensors.HaAuthenticationException
import io.github.maxlyth.hapaneld.sensors.HaLifecycle
import io.github.maxlyth.hapaneld.sensors.HaLifecycleCoordinator
import io.github.maxlyth.hapaneld.sensors.HaLifecycleRuntime
import io.github.maxlyth.hapaneld.sensors.HaLifecycleNotice
import io.github.maxlyth.hapaneld.sensors.HaLifecyclePhase
import io.github.maxlyth.hapaneld.sensors.HaLifecycleReason
import io.github.maxlyth.hapaneld.sensors.HaLifecycleState
import io.github.maxlyth.hapaneld.util.ServiceRestartBarrier
import io.github.maxlyth.hapaneld.util.ServiceRuntimeOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PanelAssistantTransportOwnerTest {

    @Test fun `native notices replay hello then live phases and fence retired generation teardown`() = runTest {
        fun notice(phase: String) = JSONObject().put("phase", phase).put("reason", "restart")
            .put("elapsed_ms", 0).put("expected_ms", JSONObject.NULL)
        val first = FakeConnection(Ha.accepting(lifecycle = notice("starting")))
        val second = FakeConnection(Ha.accepting(lifecycle = notice("ready")))
        val observations = mutableListOf<String>()
        val harness = harness(first, second,
            onLifecycleNotice = { observations += it.phase.name },
            onLifecycleDisconnected = { observations += "disconnected" },
            onLifecycleRetired = { observations += "retired" })
        try {
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            assertEquals(listOf("retired", "STARTING"), observations)
            first.inbound.send(JSONObject().put("type", "event").put("id", 1)
                .put("event", notice("shutting_down").put("kind", "lifecycle")).toString())
            runCurrent()
            assertEquals("SHUTTING_DOWN", observations.last())
            harness.owner.replaceDemand(DEMAND.copy(routeEpoch = 1))
            runCurrent()
            assertEquals(listOf("retired", "STARTING", "SHUTTING_DOWN", "retired", "READY"), observations)
            second.inbound.close(IOException("lost"))
            runCurrent()
            assertEquals("disconnected", observations.last())
            harness.owner.close()
            runCurrent()
            assertEquals("retired", observations.last())
        } finally { harness.owner.close() }
    }

    @Test fun `PA session alone drives shutdown startup and readiness on the lifecycle runtime`() = runTest {
        fun notice(phase: String) = JSONObject().put("phase", phase).put("reason", "restart")
            .put("elapsed_ms", 2_000).put("expected_ms", 60_000)
        val first = FakeConnection(Ha.accepting(lifecycle = notice("shutting_down")))
        val second = FakeConnection(Ha.accepting(lifecycle = notice("starting")))
        val coordinator = HaLifecycleCoordinator(nowMs = { testScheduler.currentTime })
        HaLifecycleRuntime.install(coordinator)
        val harness = harness(first, second,
            onLifecycleNotice = coordinator::onNativeNotice,
            onLifecycleAuthenticated = coordinator::onNativeAuthenticated,
            onLifecycleDisconnected = coordinator::onNativeDisconnected,
            onLifecycleRetired = coordinator::onNativeRetired)
        try {
            harness.owner.replaceDemand(DEMAND)
            HaLifecycleRuntime.setNativeWatching(coordinator, true)
            runCurrent()
            assertEquals(HaLifecycleState.SHUTTING_DOWN, HaLifecycleRuntime.snapshot()?.state)
            assertEquals(60_000L, HaLifecycleRuntime.snapshot()?.expectedMs)
            first.inbound.close(IOException("Core stopped"))
            runCurrent()
            assertEquals(HaLifecycleState.SHUTTING_DOWN, HaLifecycleRuntime.snapshot()?.state)
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(HaLifecycleState.STARTING, HaLifecycleRuntime.snapshot()?.state)
            coordinator.onNativeAuthenticated()
            assertEquals("authentication cannot clear reported startup", HaLifecycleState.STARTING,
                HaLifecycleRuntime.snapshot()?.state)
            second.inbound.send(JSONObject().put("type", "event").put("id", 1)
                .put("event", notice("ready").put("kind", "lifecycle")).toString())
            runCurrent()
            assertEquals(HaLifecycleState.BACK_ONLINE, HaLifecycleRuntime.snapshot()?.state)
            advanceTimeBy(8_000L)
            runCurrent()
            assertEquals(HaLifecycleState.NORMAL, HaLifecycleRuntime.snapshot()?.state)
        } finally {
            harness.owner.close()
            HaLifecycleRuntime.uninstall(coordinator)
        }
    }

    @Test fun `legacy hello authentication clears connection loss without synthetic ready notice`() = runTest {
        val observations = mutableListOf<String>()
        val connection = FakeConnection(Ha.accepting())
        val harness = harness(IOException("offline"), connection,
            onLifecycleAuthenticated = { observations += "authenticated" },
            onLifecycleNotice = { observations += "notice" },
            onLifecycleDisconnected = { observations += "disconnected" })
        try {
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            assertEquals(listOf("disconnected"), observations)
            advanceTimeBy(1000)
            runCurrent()
            assertEquals(listOf("disconnected", "authenticated"), observations)
        } finally { harness.owner.close() }
    }

    @Test fun `native cold connection failure reports loss without inventing lifecycle evidence`() = runTest {
        var lost = 0
        val notices = mutableListOf<io.github.maxlyth.hapaneld.sensors.HaLifecycleNotice>()
        val harness = harness(IOException("offline"), onLifecycleNotice = { notices += it },
            onLifecycleDisconnected = { lost++ })
        try {
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            assertEquals(1, lost)
            assertTrue(notices.isEmpty())
        } finally { harness.owner.close() }
    }

    @Test fun `enrollment refusals prove authentication without claiming Home Assistant is offline`() = runTest {
        for (code in listOf("unknown_panel", "panel_user_mismatch")) {
            val tracker = HaLifecycle()
            tracker.onDisconnected(testScheduler.currentTime)
            val harness = harness(repeating = { FakeConnection(Ha.refusing(code)) },
                onLifecycleAuthenticated = { tracker.onAuthenticatedRunning(testScheduler.currentTime) },
                onLifecycleDisconnected = { tracker.onDisconnected(testScheduler.currentTime) })
            try {
                harness.owner.replaceDemand(DEMAND)
                runCurrent()
                advanceTimeBy(15_000L)
                runCurrent()
                assertEquals(code, harness.owner.status.refusal)
                assertEquals(HaLifecycleState.NORMAL, tracker.snapshot(testScheduler.currentTime).state)
            } finally { harness.owner.close(); runCurrent() }
        }
    }

    @Test fun `enrollment refusal cannot retire a native reported startup`() = runTest {
        val tracker = HaLifecycle()
        tracker.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.STARTING, HaLifecycleReason.RESTART, 0L, null), 0L)
        val harness = harness(FakeConnection(Ha.refusing("unknown_panel")),
            onLifecycleAuthenticated = { tracker.onAuthenticatedRunning(testScheduler.currentTime) },
            onLifecycleDisconnected = { tracker.onDisconnected(testScheduler.currentTime) })
        try {
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            assertEquals(HaLifecycleState.STARTING, tracker.snapshot(testScheduler.currentTime).state)
        } finally { harness.owner.close() }
    }


    @Test fun `route epoch change reopens native connection without replacing credential`() = runTest {
        val first = FakeConnection(Ha.accepting())
        val second = FakeConnection(Ha.accepting())
        val harness = harness(first, second)
        try {
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
            harness.owner.replaceDemand(DEMAND.copy(routeEpoch = 1))
            runCurrent()
            assertTrue(first.closed)
            assertFalse(second.closed)
            assertEquals(listOf("https://ha.example" to "token", "https://ha.example" to "token"), harness.connector.connects)
            assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        } finally { harness.owner.close() }
    }

    @Test fun replacingDemandImmediatelyRevokesPolicyWhileOldSocketTeardownBlocksItsSuccessor() = runTest {
        val oldPolicy = PanelAssistantUpdatePolicy(1, 3, false)
        val newPolicy = oldPolicy.copy(prerelease = true)
        val release = CompletableDeferred<Unit>()
        val first = FakeConnection(Ha.accepting(updatePolicy = oldPolicy)).apply { holdReadAfterCancel = release }
        val second = FakeConnection(Ha.accepting(updatePolicy = newPolicy))
        val harness = harness(first, second)
        try {
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            assertEquals(oldPolicy, harness.owner.status.liveUpdatePolicy())

            harness.owner.replaceDemand(DEMAND.copy(routeEpoch = 1))
            assertNull("replacement revokes the retired hello before coroutine scheduling", harness.owner.status.liveUpdatePolicy())
            assertNull(harness.owner.status.session)
            runCurrent()
            assertFalse(first.closed)
            assertEquals(1, harness.connector.connects.size)
            assertNull("retired socket teardown is not live update authority", harness.owner.status.liveUpdatePolicy())

            release.complete(Unit)
            runCurrent()
            assertTrue(first.closed)
            assertEquals(2, harness.connector.connects.size)
            assertEquals(newPolicy, harness.owner.status.liveUpdatePolicy())
        } finally {
            release.complete(Unit)
            harness.owner.close()
            runCurrent()
        }
    }

    @Test fun socketFailureRevokesUpdatePolicyBeforeBlockedSocketCleanupAndRetry() = runTest {
        val policy = PanelAssistantUpdatePolicy(1, 3, true)
        val release = CompletableDeferred<Unit>()
        val first = FakeConnection(Ha.accepting(updatePolicy = policy)).apply { holdClose = release }
        val second = FakeConnection(Ha.accepting(updatePolicy = policy))
        val harness = harness(first, second)
        try {
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            assertEquals(policy, harness.owner.status.liveUpdatePolicy())

            first.inbound.close(IOException("socket lost"))
            runCurrent()
            assertTrue(first.closeStarted)
            assertFalse(first.closed)
            assertEquals(1, harness.connector.connects.size)
            assertNull("known socket failure revokes policy before cleanup returns", harness.owner.status.liveUpdatePolicy())
            assertNull(harness.owner.status.session)

            release.complete(Unit)
            runCurrent()
            assertTrue(first.closed)
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(2, harness.connector.connects.size)
            assertEquals(policy, harness.owner.status.liveUpdatePolicy())
        } finally {
            release.complete(Unit)
            harness.owner.close()
            runCurrent()
        }
    }

    @Test fun `hello losing connection ownership cannot publish connected or authority`() = runTest {
        val connection = FakeConnection(Ha.accepting(authority = "native"))
        var connected = 0
        val authorities = mutableListOf<String>()
        val harness = harness(connection, onConnection = { _, _ -> false },
            onConnected = { connected++ }, onAuthority = { authorities += it })
        try {
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            assertTrue(connection.closed)
            assertEquals(0, connected)
            assertTrue(authorities.isEmpty())
            assertFalse(harness.owner.status.phase == PanelAssistantTransportPhase.CONNECTED)
        } finally { harness.owner.close() }
    }

    @Test fun `preferred route only retires a working session after check succeeds at ping cadence`() = runTest {
        val connection = FakeConnection(Ha.accepting())
        var preferredReady = false
        var checks = 0
        val harness = harness(connection, checkPreferred = { checks++; preferredReady })
        try {
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(0, checks)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, checks)
            assertFalse(connection.closed)
            assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
            assertEquals(1, connection.sent.map(::JSONObject).count { it.optString("type") == "ping" })
            preferredReady = true
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(2, checks)
            assertTrue(connection.closed)
            assertEquals(1, connection.sent.map(::JSONObject).count { it.optString("type") == "ping" })
        } finally { harness.owner.close() }
    }

    @Test fun `native activation waits for predecessor teardown and successful core startup`() = runTest {
        val harness = harness(FakeConnection(Ha.accepting()), FakeConnection(Ha.accepting()))
        val barrier = ServiceRestartBarrier()
        val predecessor = barrier.enter()
        val successor = barrier.enter()
        val waiting = CountDownLatch(1)
        val coreStarts = AtomicInteger()
        val runtime = ServiceRuntimeOwner(Unit, "native-startup-fence-test")
        try {
            val started = runtime.start(
                block = {
                    waiting.countDown()
                    successor.awaitPredecessor()
                    coreStarts.incrementAndGet()
                },
                complete = { harness.owner.replaceDemand(DEMAND) },
            )
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            runCurrent()
            assertEquals(0, coreStarts.get())
            assertEquals(emptyList<Pair<String, String>>(), harness.connector.connects)

            predecessor.completeTeardown()
            assertTrue(started.get(2, TimeUnit.SECONDS))
            runCurrent()
            assertEquals(1, coreStarts.get())
            assertEquals(listOf("https://ha.example" to "token"), harness.connector.connects)

            assertTrue(runtime.runIfRunning { harness.owner.replaceDemand(null) })
            runCurrent()
            assertEquals(PanelAssistantTransportPhase.STOPPED, harness.owner.status.phase)
            assertFalse(runtime.start(
                block = { coreStarts.incrementAndGet() },
                complete = { harness.owner.replaceDemand(DEMAND) },
            ).get(2, TimeUnit.SECONDS))
            runCurrent()
            assertEquals(1, coreStarts.get())
            assertEquals(1, harness.connector.connects.size)
            assertTrue(runtime.runIfRunning { harness.owner.replaceDemand(DEMAND) })
            runCurrent()
            assertEquals(2, harness.connector.connects.size)
            assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        } finally {
            predecessor.completeTeardown()
            runtime.shutdown(2_000L) { harness.owner.close() }
            successor.completeTeardown()
        }
    }

    @Test fun `stopping a successor behind its predecessor prevents native activation and refresh`() = runTest {
        val harness = harness(FakeConnection(Ha.accepting()))
        val barrier = ServiceRestartBarrier()
        val predecessor = barrier.enter()
        val successor = barrier.enter()
        val waiting = CountDownLatch(1)
        val coreStarts = AtomicInteger()
        val runtime = ServiceRuntimeOwner(Unit, "native-stopped-startup-test")
        try {
            val started = runtime.start(
                block = {
                    waiting.countDown()
                    successor.awaitPredecessor()
                    coreStarts.incrementAndGet()
                },
                complete = { harness.owner.replaceDemand(DEMAND) },
            )
            assertTrue(waiting.await(2, TimeUnit.SECONDS))
            runtime.closeAdmission()
            predecessor.completeTeardown()
            assertTrue(started.get(2, TimeUnit.SECONDS))
            assertEquals(1, coreStarts.get())
            assertFalse(runtime.runIfRunning { harness.owner.replaceDemand(DEMAND) })
            runCurrent()
            assertEquals(emptyList<Pair<String, String>>(), harness.connector.connects)
        } finally {
            predecessor.completeTeardown()
            runtime.shutdown(2_000L) { harness.owner.close() }
            successor.completeTeardown()
        }
    }

    @Test fun `failed core startup cannot activate or refresh native transport`() = runTest {
        val harness = harness(FakeConnection(Ha.accepting()))
        val coreStarts = AtomicInteger()
        val runtime = ServiceRuntimeOwner(Unit, "native-failed-startup-test")
        try {
            assertFalse(runtime.start(
                block = {
                    coreStarts.incrementAndGet()
                    error("core listener failed")
                },
                complete = { harness.owner.replaceDemand(DEMAND) },
            ).get(2, TimeUnit.SECONDS))
            assertEquals(1, coreStarts.get())
            assertFalse(runtime.runIfRunning { harness.owner.replaceDemand(DEMAND) })
            runCurrent()
            assertEquals(emptyList<Pair<String, String>>(), harness.connector.connects)
        } finally {
            runtime.shutdown(2_000L) { harness.owner.close() }
        }
    }

    @Test fun `an accepted hello carries the panel identity and learns the integration version`() = runTest {
        val connection = FakeConnection(Ha.accepting())
        val harness = harness(connection)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()

        val status = harness.owner.status
        assertEquals(PanelAssistantTransportPhase.CONNECTED, status.phase)
        assertEquals("0.3.0", status.session?.integrationVersion)
        assertEquals("mqtt", status.session?.authority)
        assertEquals(listOf("https://ha.example" to "token"), harness.connector.connects)

        val hello = JSONObject(connection.sent.single())
        assertEquals("panel_assistant/hello", hello.getString("type"))
        assertEquals(1, hello.getInt("id"))
        assertEquals(IDENTITY.did, hello.getString("did"))
        assertEquals("0.9.8-rc1", hello.getJSONObject("app").getString("version"))
        assertEquals(790, hello.getJSONObject("app").getInt("version_code"))
        assertEquals(listOf("mqtt_withdraw"), hello.getJSONArray("capabilities").let { (0 until it.length()).map(it::getString) })
        assertEquals(0, hello.getJSONArray("channels").length())
        harness.owner.close()
    }

    @Test fun `reconnect reports current interface addresses without replacing demand`() = runTest {
        val first = FakeConnection(Ha.accepting())
        val second = FakeConnection(Ha.accepting())
        var current = listOf("192.0.2.10", "2001:db8::10", "198.51.100.10")
        val harness = harness(first, second, addresses = { current })
        try {
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            assertEquals(current, JSONObject(first.sent.single()).getJSONArray("addresses").let {
                (0 until it.length()).map(it::getString)
            })
            current = listOf("192.0.2.11", "2001:db8::11")
            first.inbound.trySend(Ha.sessionClosed("entry_unloaded"))
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()
            assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
            assertEquals(current, JSONObject(second.sent.single()).getJSONArray("addresses").let {
                (0 until it.length()).map(it::getString)
            })
        } finally {
            harness.owner.close()
        }
    }

    @Test fun `interface collection failure does not prevent hello acceptance`() = runTest {
        val connection = FakeConnection(Ha.accepting())
        val harness = harness(connection, addresses = { throw IOException("interfaces unavailable") })
        try {
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
            assertFalse(JSONObject(connection.sent.single()).has("addresses"))
        } finally {
            harness.owner.close()
        }
    }

    @Test fun `a deliberate restart reaches a schema 3 peer and health expires if the panel stays down`() = runTest {
        val connection = FakeConnection(Ha.accepting(protocol = 3))
        var now = 0L
        val harness = harness(connection, clock = { now })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        val delivered = harness.owner.announceRestart("app", "settings", 30_000L)
        now = 1_000L // Delivery delay must not buy another second of Restarting.
        runCurrent()
        assertTrue(delivered.await())
        val notice = connection.sent.map(::JSONObject).single { it.optString("type") == "panel_assistant/restart_notice" }
        assertEquals("opaque-session", notice.getString("session"))
        assertEquals("app", notice.getString("scope"))
        assertEquals("settings", notice.getString("reason"))
        assertEquals(29_000L, notice.getLong("expected_back_ms"))
        assertEquals(" pa_restarting=app,settings,29000", harness.owner.restartHealthToken())
        now = 30_001L
        assertEquals("", harness.owner.restartHealthToken())
        harness.owner.close()
    }

    @Test fun `an older schema peer gets no new request while health still explains the restart`() = runTest {
        val connection = FakeConnection(Ha.accepting(protocol = 1))
        val harness = harness(connection)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        val delivered = harness.owner.announceRestart("panel", "reboot", 120_000L)
        runCurrent()
        assertFalse(delivered.await())
        assertEquals(" pa_restarting=panel,reboot,120000", harness.owner.restartHealthToken())
        assertFalse(connection.sent.any { JSONObject(it).optString("type") == "panel_assistant/restart_notice" })
        harness.owner.close()
    }

    @Test fun `without a live session the HTTP health fact still carries the bounded notice`() = runTest {
        val harness = harness()
        assertFalse(harness.owner.announceRestart("app", "recovery", 30_000L).await())
        assertEquals(" pa_restarting=app,recovery,30000", harness.owner.restartHealthToken())
        assertEquals(emptyList<Pair<String, String>>(), harness.connector.connects)
        advanceTimeBy(30_001L)
        assertEquals("", harness.owner.restartHealthToken())
        harness.owner.close()
    }

    @Test fun `a closed session reconnects on the first backoff step because acceptance reset the counter`() = runTest {
        val first = FakeConnection(Ha.accepting())
        val harness = harness(IOException("down"), IOException("down"), IOException("down"), first, FakeConnection(Ha.accepting()))
        harness.owner.replaceDemand(DEMAND)
        advanceTimeBy(1_000L + 2_000L + 4_000L + 1L)
        runCurrent()
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)

        val closedAt = testScheduler.currentTime
        first.inbound.trySend(Ha.sessionClosed("entry_unloaded"))
        runCurrent()
        assertEquals(PanelAssistantTransportPhase.WAITING, harness.owner.status.phase)
        assertEquals(1, harness.owner.status.attempt)
        assertTrue(first.closed)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(closedAt + 1_000L, harness.connector.times.last())
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        harness.owner.close()
    }

    @Test fun `transport failures back off exponentially to the cap and never park`() = runTest {
        val harness = harness()
        harness.owner.replaceDemand(DEMAND)
        advanceTimeBy(40L * 60_000L)
        runCurrent()

        val gaps = harness.connector.times.zipWithNext { a, b -> b - a }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), gaps.take(8))
        assertTrue("still retrying after ${gaps.size} gaps", gaps.size > 40)
        assertTrue(gaps.all { it <= 60_000L })
        assertFalse(harness.owner.status.slowRetry)
        harness.owner.close()
    }

    @Test fun `unknown_command stays on backoff inside the warm-up window and then slows`() = runTest {
        val harness = harness(repeating = { FakeConnection(Ha.refusing("unknown_command")) })
        harness.owner.replaceDemand(DEMAND)
        advanceTimeBy(9L * 60_000L)
        runCurrent()
        assertFalse(harness.owner.status.slowRetry)

        advanceTimeBy(30L * 60_000L)
        runCurrent()
        val status = harness.owner.status
        assertTrue(status.slowRetry)
        assertEquals("unknown_command", status.refusal)
        val gaps = harness.connector.times.zipWithNext { a, b -> b - a }
        assertEquals(15L * 60_000L, gaps.last())
        harness.owner.close()
    }

    @Test fun `losing a session reopens the warm-up window so an entry reload does not slow the panel`() = runTest {
        val accepted = FakeConnection(Ha.accepting())
        val harness = harness(accepted, repeating = { FakeConnection(Ha.refusing("unknown_command")) })
        harness.owner.replaceDemand(DEMAND)
        // Well outside the window opened when demand started.
        advanceTimeBy(20L * 60_000L)
        runCurrent()
        accepted.inbound.trySend(Ha.sessionClosed("entry_unloaded"))
        runCurrent()

        advanceTimeBy(5L * 60_000L)
        runCurrent()
        assertEquals("unknown_command", harness.owner.status.refusal)
        assertFalse(harness.owner.status.slowRetry)
        harness.owner.close()
    }

    @Test fun `a Core restart that just drops the socket also reopens the warm-up window`() = runTest {
        val accepted = FakeConnection(Ha.accepting())
        val harness = harness(accepted, repeating = { FakeConnection(Ha.refusing("unknown_command")) })
        harness.owner.replaceDemand(DEMAND)
        advanceTimeBy(20L * 60_000L)
        runCurrent()
        // No session_closed event: Core closes the socket and the next receive fails.
        accepted.inbound.close()
        runCurrent()

        advanceTimeBy(5L * 60_000L)
        runCurrent()
        assertEquals("unknown_command", harness.owner.status.refusal)
        assertFalse(harness.owner.status.slowRetry)
        harness.owner.close()
    }

    @Test fun `a superseded session waits on the slow schedule instead of taking the session back`() = runTest {
        val first = FakeConnection(Ha.accepting())
        val harness = harness(first, FakeConnection(Ha.accepting()))
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        first.inbound.trySend(Ha.sessionClosed("superseded"))
        runCurrent()

        assertTrue(harness.owner.status.slowRetry)
        assertEquals(PanelAssistantTransportOwner.REFUSAL_SESSION_SUPERSEDED, harness.owner.status.refusal)
        advanceTimeBy(10L * 60_000L)
        runCurrent()
        assertEquals(1, harness.connector.times.size)
        harness.owner.close()
    }

    @Test fun `a CancellationException from a closing socket is a lost socket, not the end of the owner`() = runTest {
        val closing = FakeConnection { _, _ -> throw java.util.concurrent.CancellationException("socket closed") }
        val harness = harness(closing, FakeConnection(Ha.accepting()))
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertEquals(PanelAssistantTransportPhase.WAITING, harness.owner.status.phase)
        assertEquals(PanelAssistantTransportOwner.REFUSAL_TRANSPORT, harness.owner.status.refusal)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, harness.connector.times.size)
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        harness.owner.close()
    }

    @Test fun `a rejected credential waits slowly and a credential that moved retries on backoff`() = runTest {
        val rejected = harness()
        rejected.session = { HaApiSession("https://ha.example", null, rejected = true) }
        rejected.owner.replaceDemand(DEMAND)
        runCurrent()
        assertTrue(rejected.owner.status.slowRetry)
        assertEquals(PanelAssistantTransportOwner.REFUSAL_CREDENTIAL_REJECTED, rejected.owner.status.refusal)
        assertEquals(0, rejected.connector.times.size)
        rejected.owner.close()

        val moved = harness()
        moved.session = { HaApiSession("https://ha.example", "token", owner = OWNER.copy(refreshToken = "newer")) }
        moved.owner.replaceDemand(DEMAND)
        runCurrent()
        assertFalse(moved.owner.status.slowRetry)
        assertEquals(PanelAssistantTransportOwner.REFUSAL_CREDENTIAL_UNAVAILABLE, moved.owner.status.refusal)
        assertEquals(0, moved.connector.times.size)
        moved.owner.close()
    }

    @Test fun `a panel connects within seconds after prolonged identity or account confirmation`() = runTest {
        for (code in listOf("unknown_panel", "panel_user_mismatch")) {
            var confirmed = false
            val startedAt = testScheduler.currentTime
            val harness = harness(repeating = {
                FakeConnection(if (confirmed) Ha.accepting() else Ha.refusing(code))
            })
            harness.owner.replaceDemand(DEMAND)
            advanceTimeBy(20L * 60_000L)
            runCurrent()
            assertEquals(code, harness.owner.status.refusal)
            assertFalse(code, harness.owner.status.slowRetry)
            assertEquals((0L..240L).map { startedAt + it * 5_000L }, harness.connector.times)
            confirmed = true
            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(startedAt + 20L * 60_000L + 5_000L, harness.connector.times.last())
            assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
            harness.owner.close()
        }
    }

    @Test fun `a rejected token gets one forced refresh and then the slow schedule`() = runTest {
        val harness = harness(repeatingFailure = { HaAuthenticationException("rejected") })
        harness.owner.replaceDemand(DEMAND)
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(listOf(false, true), harness.forces)
        assertTrue(harness.owner.status.slowRetry)
        assertEquals(PanelAssistantTransportOwner.REFUSAL_AUTH_INVALID, harness.owner.status.refusal)
        advanceTimeBy(15L * 60_000L)
        runCurrent()
        assertEquals(listOf(false, true, false), harness.forces)
        harness.owner.close()
    }

    @Test fun `a ping with no inbound frame ends the session and reconnects`() = runTest {
        val silent = FakeConnection(Ha.accepting(answerPings = false))
        val harness = harness(silent, FakeConnection(Ha.accepting()))
        harness.owner.replaceDemand(DEMAND)
        advanceTimeBy(30_000L + 14_999L)
        runCurrent()
        assertFalse(silent.closed)
        assertEquals("ping", JSONObject(silent.sent.last()).getString("type"))

        advanceTimeBy(1L)
        runCurrent()
        assertTrue(silent.closed)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, harness.connector.times.size)
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        harness.owner.close()
    }

    @Test fun `answered pings keep one session open with strictly increasing message ids`() = runTest {
        val connection = FakeConnection(Ha.accepting())
        val harness = harness(connection)
        harness.owner.replaceDemand(DEMAND)
        advanceTimeBy(10L * 60_000L)
        runCurrent()

        assertEquals(1, harness.connector.times.size)
        assertFalse(connection.closed)
        val ids = connection.sent.map { JSONObject(it).getLong("id") }
        assertEquals((1L..ids.size.toLong()).toList(), ids)
        assertTrue(ids.size >= 20)
        harness.owner.close()
    }

    @Test fun `an equal demand leaves a live session alone and a new credential replaces it`() = runTest {
        val first = FakeConnection(Ha.accepting())
        val harness = harness(first, FakeConnection(Ha.accepting()))
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        harness.owner.replaceDemand(DEMAND.copy())
        runCurrent()
        assertEquals(1, harness.connector.times.size)
        assertFalse(first.closed)

        harness.credential = OWNER.copy(refreshToken = "other-refresh")
        harness.owner.replaceDemand(DEMAND.copy(credential = harness.credential))
        runCurrent()
        assertTrue(first.closed)
        assertEquals(2, harness.connector.times.size)
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        harness.owner.close()
    }

    @Test fun `removing demand closes the socket and stops`() = runTest {
        val connection = FakeConnection(Ha.accepting())
        val harness = harness(connection)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        harness.owner.replaceDemand(null)
        runCurrent()
        assertTrue(connection.closed)
        assertEquals(PanelAssistantTransportPhase.STOPPED, harness.owner.status.phase)
        advanceTimeBy(60L * 60_000L)
        runCurrent()
        assertEquals(1, harness.connector.times.size)
        harness.owner.close()
    }

    @Test fun `demand needs an address, a credential and a panel identity`() {
        assertEquals(DEMAND, panelAssistantTransportDemand(OWNER, accessTokenPresent = false, IDENTITY))
        assertNull(panelAssistantTransportDemand(OWNER.copy(url = ""), accessTokenPresent = true, IDENTITY))
        assertNull(panelAssistantTransportDemand(OWNER.copy(refreshToken = ""), accessTokenPresent = false, IDENTITY))
        assertEquals(
            OWNER.copy(refreshToken = ""),
            panelAssistantTransportDemand(OWNER.copy(refreshToken = ""), accessTokenPresent = true, IDENTITY)?.credential,
        )
        assertNull(panelAssistantTransportDemand(OWNER, accessTokenPresent = true, IDENTITY.copy(did = null)))
    }

    @Test fun aShadowSessionReportsAFullSyncThenDeltasWithIdsIncreasingAcrossPings() = runTest {
        val shadow = Shadow(listOf("relay1", "screen"))
        shadow.sink("relay1", "ON")
        val connection = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state")))
        val harness = harness(connection, shadow = shadow.reporter)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()

        val hello = JSONObject(connection.sent.first())
        assertEquals(listOf("state", "mqtt_withdraw", "media"), hello.getJSONArray("capabilities").let { (0 until it.length()).map(it::getString) })
        assertEquals(listOf("relay1", "screen"), hello.getJSONArray("channels").let { (0 until it.length()).map { i -> it.getJSONObject(i).getString("channel") } })
        assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end"), connection.sent.map(::kind))
        assertEquals("opaque-session", JSONObject(connection.sent[1]).getString("session"))

        advanceTimeBy(45_000L)
        runCurrent()
        shadow.sink("screen", """{"state":"OFF"}""")
        runCurrent()
        advanceTimeBy(40_000L)
        runCurrent()
        shadow.sink("relay1", "OFF")
        runCurrent()

        assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end", "ping", "delta", "ping", "delta"), connection.sent.map(::kind))
        val ids = connection.sent.map { JSONObject(it).getLong("id") }
        assertEquals((1L..ids.size.toLong()).toList(), ids)
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        harness.owner.close()
    }

    @Test fun acceptedHelloSamplesStateBeforeFullEnd() = runTest {
        val shadow = Shadow(listOf("relay1", "screen"))
        val connection = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("state")))
        val harness = harness(connection, shadow = shadow.reporter, observeForHello = {
            shadow.sink("relay1", "ON")
            shadow.sink("screen", """{"state":"ON"}""")
            true
        })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()

        val states = connection.sent.filter { kind(it) == "full_begin" }
            .flatMap { frame ->
                val observations = JSONObject(frame).getJSONArray("observations")
                (0 until observations.length()).map { observations.getJSONObject(it).getString("channel") }
            }
        assertEquals(setOf("relay1", "screen"), states.toSet())
        assertTrue("full sync completed after the sample", connection.sent.any { kind(it) == "full_end" })
        harness.owner.close()
    }

    @Test fun retiredStateOwnerCannotCompleteFullSync() = runTest {
        val shadow = Shadow(listOf("screen"))
        val connection = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("state")))
        val harness = harness(connection, shadow = shadow.reporter, observeForHello = { false })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()

        assertEquals(listOf("panel_assistant/hello"), connection.sent.map(::kind))
        harness.owner.close()
    }

    @Test fun theHelloStatesTheChannelsTheBridgeCannotFillAndAChannelThatBecomesUnsupportedHelloesAgain() = runTest {
        val shadow = Shadow(listOf("relay1", "temperature"))
        shadow.unsupported += "humidity"
        val first = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state")))
        val second = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state")))
        val harness = harness(first, second, shadow = shadow.reporter)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        val hello = JSONObject(first.sent.first())
        assertEquals(listOf("relay1", "temperature"), hello.getJSONArray("channels").let { (0 until it.length()).map { i -> it.getJSONObject(i).getString("channel") } })
        assertEquals(listOf("humidity"), hello.optJSONArray("unsupported")?.let { (0 until it.length()).map(it::getString) })

        shadow.keys -= "temperature"
        shadow.unsupported += "temperature"
        shadow.sink("relay1", "ON")
        runCurrent()
        assertTrue(first.closed)
        advanceTimeBy(1_000L)
        runCurrent()
        val again = JSONObject(second.sent.first())
        assertEquals(listOf("relay1"), again.getJSONArray("channels").let { (0 until it.length()).map { i -> it.getJSONObject(i).getString("channel") } })
        assertEquals(listOf("humidity", "temperature"), again.optJSONArray("unsupported")?.let { (0 until it.length()).map(it::getString) })
        harness.owner.close()
    }

    @Test fun mediaIsDescribedOnlyToAnIntegrationThatGrantsItAndAnOlderOneNeverSeesIt() = runTest {
        fun channels(message: String) = JSONObject(message).getJSONArray("channels")
            .let { (0 until it.length()).map { i -> it.getJSONObject(i).getString("channel") } }
        fun offered(message: String) = JSONObject(message).getJSONArray("capabilities")
            .let { (0 until it.length()).map(it::getString) }

        // An integration predating the media_player platform refuses a whole hello describing it, so a
        // panel on one keeps a working session without the channel.
        val older = Shadow(listOf("relay1", "media"))
        val old = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state")))
        val oldHarness = harness(old, shadow = older.reporter)
        oldHarness.owner.replaceDemand(DEMAND)
        runCurrent()
        older.sink("relay1", "ON")
        advanceTimeBy(31_000L)
        runCurrent()
        assertEquals(listOf("relay1"), channels(old.sent.first()))
        assertTrue("media" in offered(old.sent.first()))
        assertFalse("an ungranted media stays out without ending the session", old.closed)
        oldHarness.owner.close()

        // An integration that grants it gets the channel on the next hello.
        val shadow = Shadow(listOf("relay1", "media"))
        val first = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state", "media")))
        val second = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state", "media")))
        val harness = harness(first, second, shadow = shadow.reporter)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertEquals(listOf("relay1"), channels(first.sent.first()))
        shadow.sink("relay1", "ON")
        runCurrent()
        assertTrue(first.closed)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf("media", "relay1"), channels(second.sent.first()))
        harness.owner.close()
    }

    @Test fun aPanelMovedToAnIntegrationThatRefusesMediaWithholdsItAndHelloesAgainAtOnce() = runTest {
        fun channels(message: String) = JSONObject(message).getJSONArray("channels")
            .let { (0 until it.length()).map { i -> it.getJSONObject(i).getString("channel") } }
        val shadow = Shadow(listOf("relay1", "media"))
        val granting = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state", "media")))
        val describing = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state", "media")))
        val older = FakeConnection(Ha.refusing("invalid_format"))
        val after = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state")))
        val harness = harness(granting, describing, older, after, shadow = shadow.reporter)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        shadow.sink("relay1", "ON")
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf("media", "relay1"), channels(describing.sent.first()))
        // No session_closed event: Core closes the socket and the next receive fails.
        describing.inbound.close()
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf("media", "relay1"), channels(older.sent.first()))
        advanceTimeBy(5_000L)
        runCurrent()
        assertTrue("the next hello comes on backoff, not the slow schedule", after.sent.isNotEmpty())
        assertEquals(listOf("relay1"), channels(after.sent.first()))
        harness.owner.close()
    }

    @Test fun anMqttAuthorityOrAnUngrantedStateCapabilityReportsNothing() = runTest {
        for ((authority, capabilities) in listOf("mqtt" to listOf("state"), "shadow" to emptyList())) {
            val shadow = Shadow(listOf("relay1"))
            shadow.sink("relay1", "ON")
            val connection = FakeConnection(Ha.accepting(authority = authority, capabilities = capabilities))
            val harness = harness(connection, shadow = shadow.reporter)
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            shadow.sink("relay1", "OFF")
            advanceTimeBy(31_000L)
            runCurrent()
            assertEquals("$authority $capabilities", listOf("panel_assistant/hello", "ping"), connection.sent.map(::kind))
            harness.owner.close()
        }
    }

    @Test fun aNewChannelDuringAShadowSessionEndsItAndHelloesAgainPromptly() = runTest {
        val shadow = Shadow(listOf("relay1"))
        val first = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state")))
        val second = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state")))
        val harness = harness(first, second, shadow = shadow.reporter)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        shadow.keys += "relay2"
        shadow.sink("relay2", "ON")
        runCurrent()
        assertTrue(first.closed)
        assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end"), first.sent.map(::kind))

        advanceTimeBy(1_000L)
        runCurrent()
        val hello = JSONObject(second.sent.first())
        assertEquals(2, hello.getJSONArray("channels").length())
        assertEquals(listOf("relay2"), JSONObject(second.sent[1]).getJSONArray("observations").let { (0 until it.length()).map { i -> it.getJSONObject(i).getString("channel") } })
        harness.owner.close()
    }

    @Test fun sessionUnknownOnAReportEndsTheSession() = runTest {
        val shadow = Shadow(listOf("relay1"))
        shadow.sink("relay1", "ON")
        val connection = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state"), reportError = "session_unknown"))
        val harness = harness(connection, FakeConnection(Ha.accepting()), shadow = shadow.reporter)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertTrue(connection.closed)
        assertEquals(PanelAssistantTransportOwner.REFUSAL_SESSION_CLOSED, harness.owner.status.refusal)
        harness.owner.close()
    }

    @Test fun aReplacedSessionFinishingLateCannotStopTheNewSessionsReporting() = runTest {
        val shadow = Shadow(listOf("relay1"))
        shadow.sink("relay1", "ON")
        val release = CompletableDeferred<Unit>()
        val first = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state"))).apply { holdReadAfterCancel = release }
        val second = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state")))
        val harness = harness(first, second, shadow = shadow.reporter)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end"), first.sent.map(::kind))

        // The retired session's socket read outlives its cancellation, so its teardown ends only when released.
        harness.owner.replaceDemand(DEMAND.copy(identity = IDENTITY.copy(appVersionCode = 791)))
        runCurrent()
        assertFalse(first.closed)
        release.complete(Unit)
        runCurrent()
        shadow.sink("relay1", "OFF")
        runCurrent()

        assertTrue(first.closed)
        assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end", "delta"), second.sent.map(::kind))
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        harness.owner.close()
    }

    @Test fun aNativeSessionReportsStateAndAnswersEachCommandOnTheSameSocket() = runTest {
        val shadow = Shadow(listOf("relay1"))
        shadow.sink("relay1", "OFF")
        val sink = ImmediateSink()
        val authorities = mutableListOf<String>()
        val connection = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("state", "commands", "approval")))
        val harness = harness(connection, shadow = shadow.reporter, commands = sink, onAuthority = { authorities += it })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()

        val hello = JSONObject(connection.sent.first())
        assertEquals(listOf("state", "commands", "approval", "mqtt_withdraw", "media"), hello.getJSONArray("capabilities").let { (0 until it.length()).map(it::getString) })
        assertEquals(listOf("native"), authorities)
        assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end"), connection.sent.map(::kind))

        connection.inbound.trySend(Ha.command("c1", "relay1", true))
        runCurrent()

        assertEquals(listOf("relay1" to "ON"), sink.ran)
        val answer = JSONObject(connection.sent.last())
        assertEquals(listOf("panel_assistant/command_result", "opaque-session", "c1", "applied"), listOf("type", "session", "command_id", "outcome").map(answer::getString))
        val ids = connection.sent.map { JSONObject(it).getLong("id") }
        assertEquals((1L..ids.size.toLong()).toList(), ids)
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        harness.owner.close()
    }

    @Test fun aShadowSessionRefusesCommandsWithoutRunningThem() = runTest {
        val shadow = Shadow(listOf("relay1"))
        val sink = ImmediateSink()
        val authorities = mutableListOf<String>()
        val connection = FakeConnection(Ha.accepting(authority = "shadow", capabilities = listOf("state")))
        val harness = harness(connection, shadow = shadow.reporter, commands = sink, onAuthority = { authorities += it })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        connection.inbound.trySend(Ha.command("c1", "relay1", true))
        runCurrent()

        assertEquals(listOf("shadow"), authorities)
        assertTrue(sink.ran.isEmpty())
        val answer = JSONObject(connection.sent.last())
        assertEquals(listOf("refused", "authority_mismatch"), listOf("outcome", "code").map(answer::getString))
        harness.owner.close()
    }

    @Test fun anUnknownAuthorityIsNeverPersisted() = runTest {
        val authorities = mutableListOf<String>()
        val connection = FakeConnection(Ha.accepting(authority = "future_mode"))
        val harness = harness(connection, commands = ImmediateSink(), onAuthority = { authorities += it })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        assertTrue(authorities.isEmpty())
        harness.owner.close()
    }

    @Test fun sessionUnknownAnsweringACommandResultEndsTheSession() = runTest {
        val connection = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("state", "commands"), commandResultError = "session_unknown"))
        val harness = harness(connection, shadow = Shadow(listOf("relay1")).reporter, commands = ImmediateSink())
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        connection.inbound.trySend(Ha.command("c1", "relay1", true))
        runCurrent()

        assertTrue(connection.closed)
        assertEquals("session_closed", harness.owner.status.refusal)
        harness.owner.close()
    }

    @Test fun sessionUnknownAnsweringACommandResultEndsASessionWithoutStateReporting() = runTest {
        val connection = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("commands"), commandResultError = "session_unknown"))
        val sink = ImmediateSink()
        val harness = harness(connection, shadow = Shadow(listOf("relay1")).reporter, commands = sink)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        connection.inbound.trySend(Ha.command("c1", "relay1", true))
        runCurrent()

        assertEquals(1, sink.ran.size)
        assertTrue(connection.sent.none { JSONObject(it).getString("type") == "panel_assistant/report_state" })
        assertTrue(connection.closed)
        assertEquals("session_closed", harness.owner.status.refusal)
        harness.owner.close()
    }

    @Test fun aHeldApprovalIsWithdrawnWhenTheSessionEnds() = runTest {
        val sink = ImmediateSink(result = PanelAssistantCommandResult.ApprovalPending("approval-1"))
        val connection = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("state", "commands", "approval")))
        val harness = harness(connection, shadow = Shadow(listOf("camera_enabled")).reporter, commands = sink)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        connection.inbound.trySend(Ha.command("c1", "camera_enabled", true))
        runCurrent()
        assertEquals("pending_approval", JSONObject(connection.sent.last()).getString("outcome"))

        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(1, sink.ran.size)
        connection.inbound.trySend(Ha.sessionClosed("authority_changed"))
        runCurrent()

        assertEquals(listOf("approval-1"), sink.withdrawn)
        harness.owner.close()
    }

    @Test fun aFreshWithdrawalReachesTheBridgeOnlyAfterTheFullSyncIsAcknowledged() = runTest {
        val shadow = Shadow(listOf("relay1"))
        shadow.sink("relay1", "ON")
        val discoveries = mutableListOf<String>()
        val connection = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("state", "mqtt_withdraw"), mqttDiscovery = "withdraw", holdFullEnd = true))
        val harness = harness(connection, shadow = shadow.reporter, onMqttDiscovery = { discoveries += it })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end"), connection.sent.map(::kind))
        assertEquals(emptyList<String>(), discoveries)

        connection.inbound.trySend(Ha.reportAcknowledged(3L))
        runCurrent()
        assertEquals(listOf("withdraw"), discoveries)

        // Later acknowledged reports do not hand the withdrawal over again.
        shadow.sink("relay1", "OFF")
        runCurrent()
        assertEquals("delta", kind(connection.sent.last()))
        assertEquals(listOf("withdraw"), discoveries)
        harness.owner.close()
    }

    @Test fun aSessionEndingBeforeItsFullSyncHandsNothingOverAndTheNextResolvesTheClaimAgain() = runTest {
        val shadow = Shadow(listOf("relay1"))
        shadow.sink("relay1", "ON")
        val discoveries = mutableListOf<String>()
        val first = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("state", "mqtt_withdraw"), mqttDiscovery = "withdraw", holdFullEnd = true))
        val second = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("state", "mqtt_withdraw"), mqttDiscovery = "withdraw"))
        val harness = harness(first, second, shadow = shadow.reporter, onMqttDiscovery = { discoveries += it })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        first.inbound.trySend(Ha.sessionClosed("entry_unloaded"))
        runCurrent()
        assertTrue(first.closed)
        assertEquals(emptyList<String>(), discoveries)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end"), second.sent.map(::kind))
        assertEquals(listOf("withdraw"), discoveries)
        harness.owner.close()
    }

    @Test fun aWithdrawalOnASessionWithoutStateReportingReachesTheBridgeAtOnce() = runTest {
        val discoveries = mutableListOf<String>()
        val connection = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("commands", "mqtt_withdraw"), mqttDiscovery = "withdraw"))
        val harness = harness(connection, shadow = Shadow(listOf("relay1")).reporter, commands = ImmediateSink(), onMqttDiscovery = { discoveries += it })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertEquals(listOf("panel_assistant/hello"), connection.sent.map(::kind))
        assertEquals(listOf("withdraw"), discoveries)
        harness.owner.close()
    }

    @Test fun aReleaseReachesTheBridgeAtOnceWhateverTheReplyClaims() = runTest {
        for ((authority, claim) in listOf("shadow" to "withdraw", "mqtt" to null, "future_mode" to "withdraw")) {
            val shadow = Shadow(listOf("relay1"))
            shadow.sink("relay1", "ON")
            val discoveries = mutableListOf<String>()
            val granted = if (claim != null) listOf("state", "mqtt_withdraw") else listOf("state")
            val connection = FakeConnection(Ha.accepting(authority = authority, capabilities = granted, mqttDiscovery = claim, holdFullEnd = true))
            val harness = harness(connection, shadow = shadow.reporter, mqttDiscovery = { "withdraw" }, onMqttDiscovery = { discoveries += it })
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
            assertEquals("$authority $claim", listOf("announce"), discoveries)
            harness.owner.close()
        }
    }

    @Test fun anOlderIntegrationWithoutAClaimKeepsAWithdrawnPanelWithdrawnAndAnnouncesOtherwise() = runTest {
        for ((persisted, expected) in listOf("withdraw" to emptyList(), "announce" to emptyList(), "" to listOf("announce"))) {
            val shadow = Shadow(listOf("relay1"))
            shadow.sink("relay1", "ON")
            val discoveries = mutableListOf<String>()
            val connection = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("state")))
            val harness = harness(connection, shadow = shadow.reporter, mqttDiscovery = { persisted }, onMqttDiscovery = { discoveries += it })
            harness.owner.replaceDemand(DEMAND)
            advanceTimeBy(31_000L)
            runCurrent()
            assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end", "ping"), connection.sent.map(::kind))
            assertEquals("persisted=$persisted", expected, discoveries)
            harness.owner.close()
        }
    }

    @Test fun aClaimEqualToThePersistedValueIsNotHandedOverAgain() = runTest {
        val shadow = Shadow(listOf("relay1"))
        shadow.sink("relay1", "ON")
        val discoveries = mutableListOf<String>()
        val connection = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("state", "mqtt_withdraw"), mqttDiscovery = "withdraw"))
        val harness = harness(connection, shadow = shadow.reporter, mqttDiscovery = { "withdraw" }, onMqttDiscovery = { discoveries += it })
        harness.owner.replaceDemand(DEMAND)
        advanceTimeBy(31_000L)
        runCurrent()
        assertEquals(listOf("panel_assistant/hello", "full_begin", "full_end", "ping"), connection.sent.map(::kind))
        assertEquals(emptyList<String>(), discoveries)
        harness.owner.close()
    }

    @Test fun everyHelloOffersMqttWithdrawWhateverElseIsWired() = runTest {
        for ((shadow, commands, expected) in listOf(
            Triple(null, null, listOf("mqtt_withdraw")),
            Triple(Shadow(listOf("relay1")).reporter, null, listOf("state", "mqtt_withdraw", "media")),
            Triple(null, ImmediateSink(), listOf("commands", "approval", "mqtt_withdraw")),
        )) {
            val connection = FakeConnection(Ha.accepting())
            val harness = harness(connection, shadow = shadow, commands = commands)
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            val offered = JSONObject(connection.sent.first()).getJSONArray("capabilities")
            assertEquals(expected, (0 until offered.length()).map(offered::getString))
            harness.owner.close()
        }
    }

    @Test fun embedProofIsOfferedExactlyWhenAKeyringIsWired() = runTest {
        for ((keys, expected) in listOf(
            null to listOf("mqtt_withdraw"),
            io.github.maxlyth.hapaneld.http.EmbedProofKeyring() to listOf("mqtt_withdraw", "embed_proof"),
        )) {
            val connection = FakeConnection(Ha.accepting())
            val harness = harness(connection, embedKeys = keys)
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            val offered = JSONObject(connection.sent.first()).getJSONArray("capabilities")
            assertEquals(expected, (0 until offered.length()).map(offered::getString))
            harness.owner.close()
        }
    }

    @Test fun aGrantedEmbedKeyIsHeldForItsSessionOnlyAndNeverLogged() = runTest {
        val keys = io.github.maxlyth.hapaneld.http.EmbedProofKeyring()
        val logs = mutableListOf<String>()
        val first = FakeConnection(Ha.accepting(capabilities = listOf("embed_proof"), embed = EMBED))
        val second = FakeConnection(Ha.accepting(capabilities = listOf("embed_proof"), embed = JSONObject(EMBED.toString()).put("key_id", "fedcba9876543210")))
        val harness = harness(first, second, embedKeys = keys, log = { logs += it })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertEquals("0123456789abcdef", keys.liveKeyId())
        assertEquals(IDENTITY.did, keys.key("0123456789abcdef")?.did)
        assertFalse(harness.owner.status.describe().contains(EMBED.getString("key")))
        assertFalse(harness.owner.status.session.toString().contains(EMBED.getString("key")))

        first.inbound.trySend(Ha.sessionClosed("entry_unloaded"))
        runCurrent()
        assertNull(keys.liveKeyId())
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals("fedcba9876543210", keys.liveKeyId())
        harness.owner.close()
        runCurrent()
        assertNull(keys.liveKeyId())
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { it.contains(EMBED.getString("key")) })
    }

    @Test fun anEmbedGrantWithoutAUsableKeyIsAProtocolFailureAndHoldsNoKey() = runTest {
        val keys = io.github.maxlyth.hapaneld.http.EmbedProofKeyring()
        val broken = FakeConnection(Ha.accepting(capabilities = listOf("embed_proof")))
        val good = FakeConnection(Ha.accepting(capabilities = listOf("embed_proof"), embed = EMBED))
        val harness = harness(broken, good, embedKeys = keys)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertNull(keys.liveKeyId())
        assertTrue(broken.closed)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals("0123456789abcdef", keys.liveKeyId())
        harness.owner.close()
    }

    @Test fun aGrantWithoutAClaimIsAProtocolFailureThatRetriesOnBackoff() = runTest {
        val authorities = mutableListOf<String>()
        val discoveries = mutableListOf<String>()
        val unclaimed = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("mqtt_withdraw")))
        val claimed = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("mqtt_withdraw"), mqttDiscovery = "withdraw"))
        val harness = harness(unclaimed, claimed, onAuthority = { authorities += it }, onMqttDiscovery = { discoveries += it })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertTrue(unclaimed.closed)
        assertEquals(PanelAssistantTransportPhase.WAITING, harness.owner.status.phase)
        assertEquals(PanelAssistantTransportOwner.REFUSAL_TRANSPORT, harness.owner.status.refusal)
        assertFalse(harness.owner.status.slowRetry)
        assertEquals(emptyList<String>(), authorities)
        assertEquals(emptyList<String>(), discoveries)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        assertEquals(listOf("native"), authorities)
        assertEquals(listOf("withdraw"), discoveries)
        harness.owner.close()
    }

    @Test fun anUngrantedClaimKeepsAWithdrawnPanelWithdrawn() = runTest {
        val discoveries = mutableListOf<String>()
        val connection = FakeConnection(Ha.accepting(authority = "native", capabilities = listOf("commands"), mqttDiscovery = "announce"))
        val harness = harness(connection, commands = ImmediateSink(), mqttDiscovery = { "withdraw" }, onMqttDiscovery = { discoveries += it })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        assertEquals(emptyList<String>(), discoveries)
        harness.owner.close()
    }

    @Test fun anEntryRemovedRefusalReturnsThePanelToMqttOnceAndRetriesSlowly() = runTest {
        val persisted = Persisted("native", "withdraw")
        val logs = mutableListOf<String>()
        val harness = harness(repeating = { FakeConnection(Ha.refusing("entry_removed")) }, persisted = persisted, log = { logs += it })
        harness.owner.replaceDemand(DEMAND)
        runCurrent()

        assertEquals(listOf("authority:mqtt", "discovery:announce"), persisted.events)
        assertEquals("mqtt" to "announce", persisted.authority to persisted.discovery)
        assertTrue(harness.owner.status.slowRetry)
        assertEquals("entry_removed", harness.owner.status.refusal)
        assertEquals(
            PanelAssistantTransportFacts("mqtt", "announce", PanelAssistantTransportPhase.WAITING, "entry_removed"),
            harness.owner.facts(),
        )

        advanceTimeBy(10L * 60_000L)
        runCurrent()
        assertEquals(1, harness.connector.times.size)
        advanceTimeBy(5L * 60_000L)
        runCurrent()
        assertEquals(2, harness.connector.times.size)
        // Released already: a repeated refusal neither re-announces nor logs again.
        assertEquals(listOf("authority:mqtt", "discovery:announce"), persisted.events)
        assertEquals(1, logs.count { it.contains("entry removed") })
        harness.owner.close()
    }

    @Test fun anEntryRemovedRefusalOnAnAnnouncingPanelOnlyMovesTheAuthority() = runTest {
        val persisted = Persisted("native", "announce")
        val harness = harness(FakeConnection(Ha.refusing("entry_removed")), persisted = persisted)
        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertEquals(listOf("authority:mqtt"), persisted.events)
        assertTrue(harness.owner.status.slowRetry)
        harness.owner.close()
    }

    @Test fun otherRefusalsLeaveTheAuthorityAndDiscoveryAlone() = runTest {
        for (code in listOf("unknown_panel", "unknown_command", "panel_user_mismatch", "protocol_unsupported", "invalid_format")) {
            val persisted = Persisted("native", "withdraw")
            val harness = harness(FakeConnection(Ha.refusing(code)), persisted = persisted)
            harness.owner.replaceDemand(DEMAND)
            runCurrent()
            assertEquals(code, harness.owner.status.refusal)
            assertEquals(code, emptyList<String>(), persisted.events)
            harness.owner.close()
        }
    }

    @Test fun theLocalReleasePersistsMqttAndAnnounceOnlyWhenEitherDiffers() = runTest {
        val withdrawn = Persisted("native", "withdraw")
        val first = harness(persisted = withdrawn)
        assertTrue(first.owner.releaseToMqtt())
        assertEquals(listOf("authority:mqtt", "discovery:announce"), withdrawn.events)
        assertFalse(first.owner.releaseToMqtt())
        assertEquals(listOf("authority:mqtt", "discovery:announce"), withdrawn.events)
        first.owner.close()

        val shadowAnnouncing = Persisted("shadow", "announce")
        val second = harness(persisted = shadowAnnouncing)
        assertTrue(second.owner.releaseToMqtt())
        assertEquals(listOf("authority:mqtt"), shadowAnnouncing.events)
        second.owner.close()

        val mqttWithdrawn = Persisted("mqtt", "withdraw")
        val third = harness(persisted = mqttWithdrawn)
        assertTrue(third.owner.releaseToMqtt())
        assertEquals(listOf("discovery:announce"), mqttWithdrawn.events)
        assertEquals(
            PanelAssistantTransportFacts("mqtt", "announce", PanelAssistantTransportPhase.STOPPED, null),
            third.owner.facts(),
        )
        third.owner.close()
    }

    @Test fun `MQTT fallback is not a connection but an accepted MQTT hello is`() = runTest {
        val connected = AtomicInteger()
        val persisted = Persisted("native", "withdraw")
        val harness = harness(
            FakeConnection(Ha.accepting()),
            persisted = persisted,
            onConnected = { connected.incrementAndGet() },
        )

        assertTrue(harness.owner.releaseToMqtt())
        assertEquals("mqtt", persisted.authority)
        assertEquals("announce", persisted.discovery)
        assertEquals(0, connected.get())

        harness.owner.replaceDemand(DEMAND)
        runCurrent()
        assertEquals(PanelAssistantTransportPhase.CONNECTED, harness.owner.status.phase)
        assertEquals("mqtt", harness.owner.status.session?.authority)
        assertEquals(1, connected.get())
        harness.owner.close()
    }

    // ---- harness ---------------------------------------------------------------------------------

    /** Runs every command at once with [result]; approvals stay pending. */
    private class ImmediateSink(private val result: PanelAssistantCommandResult = PanelAssistantCommandResult.Applied) :
        PanelAssistantCommandSink {
        val ran = mutableListOf<Pair<String, String>>()
        val withdrawn = mutableListOf<String>()

        override fun submit(command: PanelAssistantCommand, done: (PanelAssistantCommandResult) -> Unit) {
            ran += command.channel to command.payload
            done(command.admit() ?: result)
        }

        override fun approvalState(approvalId: String) = PanelAssistantApprovalState.PENDING

        override fun withdrawApproval(approvalId: String) {
            withdrawn += approvalId
        }
    }

    private class Shadow(initial: List<String>) {
        val keys = initial.toMutableList()
        val unsupported = mutableListOf<String>()
        val reporter = PanelAssistantShadowReporter(log = {})
        private val bound = reporter.bindShape { PanelAssistantChannelShape(keys.toList(), unsupported.toList()) }

        fun sink(channel: String, payload: String) = bound(channel, io.github.maxlyth.hapaneld.mqtt.StateConverger.Observation.Known(payload)) {}
    }

    /** The panel's persisted authority and discovery value, written through the owner's callbacks. */
    private class Persisted(var authority: String, var discovery: String) {
        val events = mutableListOf<String>()
    }

    private class Harness(val owner: PanelAssistantTransportOwner, val connector: FakeConnector, val forces: List<Boolean>) {
        var credential: HaAuthOwner = OWNER
        var session: (() -> HaApiSession)? = null
    }

    private fun TestScope.harness(
        vararg script: Any,
        repeating: (() -> FakeConnection)? = null,
        repeatingFailure: (() -> Exception)? = null,
        shadow: PanelAssistantShadowReporter? = null,
        observeForHello: suspend () -> Boolean = { true },
        commands: PanelAssistantCommandSink? = null,
        onAuthority: (String) -> Unit = {},
        onConnected: () -> Unit = {},
        onLifecycleNotice: (io.github.maxlyth.hapaneld.sensors.HaLifecycleNotice) -> Unit = {},
        onLifecycleAuthenticated: () -> Unit = {},
        onLifecycleDisconnected: () -> Unit = {},
        onLifecycleRetired: () -> Unit = {},
        mqttDiscovery: () -> String = { "" },
        onMqttDiscovery: (String) -> Unit = {},
        persisted: Persisted? = null,
        log: (String) -> Unit = {},
        embedKeys: io.github.maxlyth.hapaneld.http.EmbedProofKeyring? = null,
        clock: (() -> Long)? = null,
        addresses: () -> List<String> = { emptyList() },
        onConnection: (HaApiSession, PanelAssistantSession) -> Boolean = { _, _ -> true },
        checkPreferred: suspend () -> Boolean = { false },
    ): Harness {
        val connector = FakeConnector(this, script.toMutableList(), repeating, repeatingFailure)
        val forces = mutableListOf<Boolean>()
        lateinit var harness: Harness
        val owner = PanelAssistantTransportOwner(
            scope = backgroundScope,
            auth = HaApiSessionProvider { force ->
                forces += force
                harness.session?.invoke() ?: HaApiSession("https://ha.example", "token", owner = harness.credential)
            },
            connector = connector,
            workerDispatcher = StandardTestDispatcher(testScheduler),
            monotonicMillis = clock ?: { testScheduler.currentTime },
            jitter = { bound -> bound },
            log = log,
            shadow = shadow,
            observeForHello = observeForHello,
            commands = commands,
            embedKeys = embedKeys,
            addresses = addresses,
            onConnection = onConnection,
            checkPreferred = checkPreferred,
            onAuthority = persisted?.let { store -> { value: String -> store.events += "authority:$value"; store.authority = value } } ?: onAuthority,
            onConnected = onConnected,
            onLifecycleNotice = onLifecycleNotice,
            onLifecycleAuthenticated = onLifecycleAuthenticated,
            onLifecycleDisconnected = onLifecycleDisconnected,
            onLifecycleRetired = onLifecycleRetired,
            authority = persisted?.let { store -> { store.authority } } ?: { "" },
            mqttDiscovery = persisted?.let { store -> { store.discovery } } ?: mqttDiscovery,
            onMqttDiscovery = persisted?.let { store -> { value: String -> store.events += "discovery:$value"; store.discovery = value } } ?: onMqttDiscovery,
        )
        harness = Harness(owner, connector, forces)
        return harness
    }

    private class FakeConnector(
        private val scope: TestScope,
        private val script: MutableList<Any>,
        private val repeating: (() -> FakeConnection)?,
        private val repeatingFailure: (() -> Exception)?,
    ) : PanelAssistantTransportConnector {
        val connects = mutableListOf<Pair<String, String>>()
        val times = mutableListOf<Long>()

        override suspend fun connect(baseUrl: String, accessToken: String): PanelAssistantTransportConnection {
            connects += baseUrl to accessToken
            times += scope.testScheduler.currentTime
            val next: Any = script.removeFirstOrNull()
                ?: repeating?.invoke()
                ?: repeatingFailure?.invoke()
                ?: IOException("unreachable")
            if (next is Exception) throw next
            return next as FakeConnection
        }
    }

    /** Home Assistant's side of one socket: answers each frame the panel sends. */
    private class FakeConnection(private val respond: (JSONObject, FakeConnection) -> Unit) :
        PanelAssistantTransportConnection {
        val inbound = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        var closed = false
        var closeStarted = false
        var holdClose: CompletableDeferred<Unit>? = null

        /** When set, a read cancelled mid-wait returns only once this completes, as a slow socket might. */
        var holdReadAfterCancel: CompletableDeferred<Unit>? = null

        override suspend fun send(text: String) {
            check(!closed) { "send on a closed connection" }
            sent += text
            respond(JSONObject(text), this)
        }

        override suspend fun receive(timeoutMs: Long): String? = try {
            select {
                inbound.onReceive { it }
                onTimeout(timeoutMs) { null }
            }
        } catch (cancelled: CancellationException) {
            holdReadAfterCancel?.let { withContext(NonCancellable) { it.await() } }
            throw cancelled
        }

        override suspend fun close() {
            closeStarted = true
            holdClose?.await()
            closed = true
            inbound.close()
        }
    }

    private object Ha {
        fun accepting(
            protocol: Int = 3,
            answerPings: Boolean = true,
            authority: String = "mqtt",
            capabilities: List<String> = emptyList(),
            reportError: String? = null,
            commandResultError: String? = null,
            mqttDiscovery: String? = null,
            embed: JSONObject? = null,
            lifecycle: JSONObject? = null,
            updatePolicy: PanelAssistantUpdatePolicy? = null,
            /** Leave the `full_end` request unanswered; the test injects [reportAcknowledged] itself. */
            holdFullEnd: Boolean = false,
        ): (JSONObject, FakeConnection) -> Unit = { frame, connection ->
            when (frame.getString("type")) {
                "panel_assistant/hello" -> connection.inbound.trySend(
                    JSONObject()
                        .put("id", frame.getLong("id"))
                        .put("type", "result")
                        .put("success", true)
                        .put(
                            "result",
                            JSONObject()
                                .put("protocol", protocol)
                                .put("session", "opaque-session")
                                .put("authority", authority)
                                .put("capabilities", JSONArray(capabilities))
                                .put("integration", JSONObject().put("version", "0.3.0"))
                                .put("channels", JSONObject().put("accepted", 0).put("unknown", JSONArray()))
                                .apply { if (mqttDiscovery != null) put("mqtt_discovery", mqttDiscovery) }
                                .apply { if (embed != null) put("embed", embed) }
                                .apply { if (lifecycle != null) put("lifecycle", lifecycle) }
                                .apply {
                                    if (updatePolicy != null) put("update_policy", JSONObject()
                                        .put("protocolMin", updatePolicy.protocolMin)
                                        .put("protocolMax", updatePolicy.protocolMax)
                                        .put("prerelease", updatePolicy.prerelease))
                                },
                        )
                        .toString(),
                )
                "panel_assistant/report_state" -> if (!holdFullEnd || frame.getString("sync") != "full_end") connection.inbound.trySend(
                    if (reportError != null) {
                        JSONObject().put("id", frame.getLong("id")).put("type", "result").put("success", false)
                            .put("error", JSONObject().put("code", reportError).put("message", "x")).toString()
                    } else {
                        JSONObject().put("id", frame.getLong("id")).put("type", "result").put("success", true)
                            .put("result", JSONObject().put("rejected", JSONArray())).toString()
                    },
                )
                "panel_assistant/command_result" -> connection.inbound.trySend(
                    if (commandResultError != null) {
                        JSONObject().put("id", frame.getLong("id")).put("type", "result").put("success", false)
                            .put("error", JSONObject().put("code", commandResultError).put("message", "x")).toString()
                    } else {
                        JSONObject().put("id", frame.getLong("id")).put("type", "result").put("success", true)
                            .put("result", JSONObject()).toString()
                    },
                )
                "panel_assistant/restart_notice" -> connection.inbound.trySend(
                    JSONObject().put("id", frame.getLong("id")).put("type", "result").put("success", true)
                        .put("result", JSONObject()).toString(),
                )
                "ping" -> if (answerPings) {
                    connection.inbound.trySend(JSONObject().put("id", frame.getLong("id")).put("type", "pong").toString())
                }
            }
        }

        fun refusing(code: String): (JSONObject, FakeConnection) -> Unit = { frame, connection ->
            connection.inbound.trySend(
                JSONObject()
                    .put("id", frame.getLong("id"))
                    .put("type", "result")
                    .put("success", false)
                    .put("error", JSONObject().put("code", code).put("message", "refused"))
                    .toString(),
            )
        }

        /** The acknowledgement of the `report_state` sent as message [id]. */
        fun reportAcknowledged(id: Long): String = JSONObject().put("id", id).put("type", "result").put("success", true)
            .put("result", JSONObject().put("rejected", JSONArray())).toString()

        fun command(commandId: String, channel: String, value: Any?): String = JSONObject()
            .put("id", 1)
            .put("type", "event")
            .put(
                "event",
                JSONObject().put("kind", "command").put("command_id", commandId).put("session", "opaque-session")
                    .put("channel", channel).put("value", value ?: JSONObject.NULL).put("deadline_ms", 10_000),
            )
            .toString()

        fun sessionClosed(reason: String): String = JSONObject()
            .put("id", 1)
            .put("type", "event")
            .put("event", JSONObject().put("kind", "session_closed").put("reason", reason))
            .toString()
    }

    private companion object {
        val EMBED: JSONObject = JSONObject()
            .put("key_id", "0123456789abcdef")
            .put("key", java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it * 7).toByte() }))

        /** A sent frame's command type, or its `sync` for a `report_state`. */
        fun kind(text: String): String = JSONObject(text).let { it.optString("sync").ifEmpty { it.getString("type") } }

        val OWNER = HaAuthOwner(
            url = "https://ha.example",
            refreshToken = "refresh",
            clientId = "",
            staticAccessToken = "",
        )
        val IDENTITY = PanelAssistantHelloIdentity(
            did = "0".repeat(64),
            appVersion = "0.9.8-rc1",
            appVersionCode = 790,
        )
        val DEMAND = PanelAssistantTransportDemand(OWNER, IDENTITY)
    }
}
