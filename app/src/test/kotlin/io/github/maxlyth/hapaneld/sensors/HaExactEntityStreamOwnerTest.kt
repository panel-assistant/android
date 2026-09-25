package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.HaAuthOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HaExactEntityStreamOwnerTest {
    @Test fun `registry-only demand owns a socket and delivers registry changes outside demand mutation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val transport = FakeTransport(connection)
        var changes = 0
        val owner = HaExactEntityStreamOwner(
            scope = this,
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "token", owner = OWNER) },
            transport = transport,
            workerDispatcher = dispatcher,
        )
        owner.bindRegistryChanges { changes++ }

        owner.replacePresenceDemand(emptySet(), watchRegistry = true)
        runCurrent()
        assertEquals(listOf(true), transport.registryWatches)
        assertEquals(0, changes)

        connection.messages.send(HaExactSocketMessage.RegistryChanged)
        runCurrent()
        assertEquals(1, changes)

        owner.replacePresenceDemand(emptySet(), watchRegistry = false)
        runCurrent()
        assertEquals(1, connection.closeCount)
        owner.close()
    }

    @Test fun `registry watcher requests convergence after reconnect`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = FakeConnection()
        val second = FakeConnection()
        val transport = FakeTransport(first, second)
        var changes = 0
        val owner = HaExactEntityStreamOwner(
            scope = this,
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "token", owner = OWNER) },
            transport = transport,
            workerDispatcher = dispatcher,
            reconnectBaseMs = 10L,
            reconnectMaxMs = 10L,
        )
        owner.bindRegistryChanges { changes++ }
        owner.replacePresenceDemand(emptySet(), watchRegistry = true)
        runCurrent()

        first.messages.close()
        runCurrent()
        advanceTimeBy(10L)
        runCurrent()

        assertEquals(2, transport.subscribeCount)
        assertEquals(1, changes)
        owner.close()
    }

    @Test fun `empty ambient request owns no socket job or timer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport()
        val observer = RecordingObserver()
        val owner = owner(dispatcher, transport, observer)

        owner.replaceAmbientSource(null)
        runCurrent()
        advanceTimeBy(24L * 60L * 60_000L)
        runCurrent()

        assertEquals(0, transport.subscribeCount)
        assertEquals(HaExactEntityStreamPhase.DISABLED, observer.statuses.last().phase)
        // Demanding nothing still owns nothing after the lifecycle demand was added beside the others.
        assertTrue(transport.lifecycleWatches.isEmpty())
        owner.close()
    }

    @Test fun `lifecycle demand alone owns a socket and subscribes for lifecycle events`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val transport = FakeTransport(connection)
        val observer = RecordingObserver()
        val owner = owner(dispatcher, transport, observer)

        owner.replaceLifecycleWatch(true)
        runCurrent()

        assertEquals(1, transport.subscribeCount)
        assertEquals(listOf(true), transport.lifecycleWatches)
        assertEquals("no entity demand accompanies it", listOf(emptySet<String>()), transport.subscriptions)
        owner.close()
    }

    @Test fun `disabling the lifecycle watch releases the socket it was holding open`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val transport = FakeTransport(connection)
        val observer = RecordingObserver()
        val owner = owner(dispatcher, transport, observer)

        owner.replaceLifecycleWatch(true)
        runCurrent()
        owner.replaceLifecycleWatch(false)
        runCurrent()
        advanceTimeBy(24L * 60L * 60_000L)
        runCurrent()

        assertEquals("no reconnect may follow a withdrawn demand", 1, transport.subscribeCount)
        owner.close()
    }

    @Test fun `a rejected lifecycle subscription does not tear down the shared stream`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val transport = FakeTransport(connection)
        transport.lifecycleOutcome = HaExactSocketMessage.LifecycleStartedRejected
        val observer = RecordingObserver()
        val signals = mutableListOf<HaLifecycleSignal>()
        val owner = owner(dispatcher, transport, observer)
        owner.bindLifecycle { signals += it }

        owner.replaceAmbientSource(ENTITY_A)
        owner.replaceLifecycleWatch(true)
        runCurrent()

        // Home Assistant refuses each lifecycle subscription, as it does for a non-admin user. Were this
        // still an HaProtocolException the stream would resubscribe three times and then park for good,
        // taking ambient light and automatic sleep down with it.
        repeat(4) { connection.messages.trySend(HaExactSocketMessage.LifecycleRejected) }
        runCurrent()
        advanceTimeBy(10L * 60_000L)
        runCurrent()

        assertEquals("the stream must not reconnect", 1, transport.subscribeCount)
        assertEquals(HaExactEntityStreamPhase.LIVE, observer.statuses.last().phase)
        assertTrue("the consumer is told it will learn nothing", signals.contains(HaLifecycleSignal.Rejected))
        owner.close()
    }

    @Test fun `lifecycle frames are delivered while entity hydration is still pending`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val transport = FakeTransport(connection)
        transport.lifecycleOutcome = null
        // Hydration will BLOCK: the REST state is a deferred we deliberately leave incomplete.
        val pendingHydration = CompletableDeferred<JSONObject?>()
        transport.states[ENTITY_A] = pendingHydration
        val observer = RecordingObserver()
        val signals = mutableListOf<HaLifecycleSignal>()
        val owner = owner(dispatcher, transport, observer)
        owner.bindLifecycle { signals += it }

        owner.replaceAmbientSource(ENTITY_A)
        owner.replaceLifecycleWatch(true)
        runCurrent()

        // A non-admin refusal arrives IMMEDIATELY after subscribing, and a restart can land at any
        // moment. Both used to be consumed and discarded by the hydration loop, a deaf window of up
        // to the 20-second hydration timeout.
        connection.messages.trySend(HaExactSocketMessage.LifecycleStartedRejected)
        connection.messages.trySend(HaExactSocketMessage.Lifecycle(HaLifecycleEvent.STOP))
        runCurrent()

        assertTrue("hydration must still be pending for this test to prove anything", pendingHydration.isActive)
        assertTrue("the refusal must not wait for hydration", signals.contains(HaLifecycleSignal.Rejected))
        assertTrue(
            "nor must a restart event",
            signals.contains(HaLifecycleSignal.Event(HaLifecycleEvent.STOP)),
        )

        pendingHydration.complete(state(ENTITY_A, "1"))
        runCurrent()
        owner.close()
    }

    @Test fun `lifecycle events reach the observer in arrival order and are never coalesced`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val transport = FakeTransport(connection)
        val observer = RecordingObserver()
        val events = mutableListOf<HaLifecycleEvent>()
        val owner = owner(dispatcher, transport, observer)
        owner.bindLifecycle { signal ->
            if (signal is HaLifecycleSignal.Event) events += signal.event
        }

        owner.replaceAmbientSource(ENTITY_A)
        owner.replaceLifecycleWatch(true)
        runCurrent()

        listOf(
            HaLifecycleEvent.STOP,
            HaLifecycleEvent.FINAL_WRITE,
            HaLifecycleEvent.CLOSE,
            HaLifecycleEvent.START,
            HaLifecycleEvent.STARTED,
        ).forEach { connection.messages.trySend(HaExactSocketMessage.Lifecycle(it)) }
        runCurrent()

        assertEquals(
            listOf(
                HaLifecycleEvent.STOP,
                HaLifecycleEvent.FINAL_WRITE,
                HaLifecycleEvent.CLOSE,
                HaLifecycleEvent.START,
                HaLifecycleEvent.STARTED,
            ),
            events,
        )
        owner.close()
    }

    @Test fun `LIVE waits for the started subscription outcome even when hydration is empty`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val transport = FakeTransport(connection).apply { lifecycleOutcome = null }
        val observer = RecordingObserver()
        val phases = mutableListOf<HaExactEntityStreamPhase>()
        val owner = owner(dispatcher, transport, observer)
        owner.bindLifecycle { signal ->
            if (signal is HaLifecycleSignal.Transport) phases += signal.phase
        }

        owner.replaceLifecycleWatch(true)
        runCurrent()
        assertFalse("an unanswered subscription is not LIVE", phases.contains(HaExactEntityStreamPhase.LIVE))

        connection.messages.trySend(HaExactSocketMessage.LifecycleEstablished)
        runCurrent()
        assertTrue("the exact STARTED acceptance releases LIVE", phases.contains(HaExactEntityStreamPhase.LIVE))
        owner.close()
    }

    @Test fun `the lifecycle observer is told when the socket proves live and when it drops`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = FakeConnection()
        val second = FakeConnection()
        val transport = FakeTransport(first, second)
        val observer = RecordingObserver()
        val phases = mutableListOf<HaExactEntityStreamPhase>()
        val owner = owner(dispatcher, transport, observer)
        owner.bindLifecycle { signal ->
            if (signal is HaLifecycleSignal.Transport) phases += signal.phase
        }

        owner.replaceLifecycleWatch(true)
        runCurrent()
        assertTrue("a completed connection reports LIVE", phases.contains(HaExactEntityStreamPhase.LIVE))

        first.messages.close()
        runCurrent()
        advanceTimeBy(10L)
        runCurrent()

        assertTrue(
            "a lost socket reports RECONNECTING",
            phases.contains(HaExactEntityStreamPhase.RECONNECTING),
        )
        owner.close()
    }

    @Test fun `presence snapshot retains an ON marker after final state returns OFF`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val transport = FakeTransport(connection).apply {
            states[PRESENCE] = CompletableDeferred(presenceState("off", 1_000L))
        }
        val snapshots = mutableListOf<HaPresenceFeedSnapshot>()
        val owner = HaExactEntityStreamOwner(
            scope = this,
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "token", owner = OWNER) },
            transport = transport,
            workerDispatcher = dispatcher,
        )
        owner.bindPresence { snapshots += it }

        owner.replacePresenceSources(setOf(PRESENCE))
        runCurrent()
        connection.messages.send(HaExactSocketMessage.State(PRESENCE, presenceState("on", 2_000L)))
        connection.messages.send(HaExactSocketMessage.State(PRESENCE, presenceState("off", 3_000L)))
        runCurrent()

        val final = snapshots.last()
        assertEquals(HaPresenceValue.OFF, final.states[PRESENCE])
        assertEquals(PRESENCE, final.lastOnActivity?.entityId)
        assertTrue((final.lastOnActivity?.sequence ?: 0L) > 0L)
        owner.close()
    }

    @Test fun `stale activity from one source cannot erase valid buffered activity from another`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val hydrationA = CompletableDeferred<JSONObject?>()
        val hydrationB = CompletableDeferred<JSONObject?>()
        var monotonic = 0L
        val transport = FakeTransport(connection).apply {
            states[PRESENCE] = hydrationA
            states[PRESENCE_B] = hydrationB
        }
        val snapshots = mutableListOf<HaPresenceFeedSnapshot>()
        val owner = HaExactEntityStreamOwner(
            scope = this,
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "token", owner = OWNER) },
            transport = transport,
            workerDispatcher = dispatcher,
            monotonicMillis = { ++monotonic },
        )
        owner.bindPresence { snapshots += it }

        owner.replacePresenceSources(setOf(PRESENCE, PRESENCE_B))
        repeat(2) { runCurrent() }
        connection.messages.send(HaExactSocketMessage.State(PRESENCE, presenceState("on", 2_000L)))
        connection.messages.send(HaExactSocketMessage.State(PRESENCE, presenceState("off", 3_000L)))
        connection.messages.send(HaExactSocketMessage.State(PRESENCE_B, presenceState("on", 500L, PRESENCE_B)))
        connection.messages.send(HaExactSocketMessage.State(PRESENCE_B, presenceState("off", 600L, PRESENCE_B)))
        runCurrent()
        hydrationA.complete(presenceState("off", 1_000L))
        hydrationB.complete(presenceState("off", 1_000L, PRESENCE_B))
        runCurrent()

        assertEquals(PRESENCE, snapshots.last().lastOnActivity?.entityId)
        assertEquals(1L, snapshots.last().lastOnActivity?.receivedAtMonotonicMs)
        assertTrue((snapshots.last().lastOnActivity?.sequence ?: 0L) > 0L)
        owner.close()
    }

    @Test fun `stale buffered final state cannot overwrite a newer state before hydration`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val hydration = CompletableDeferred<JSONObject?>()
        val transport = FakeTransport(connection).apply { states[PRESENCE] = hydration }
        val snapshots = mutableListOf<HaPresenceFeedSnapshot>()
        var monotonic = 0L
        val owner = HaExactEntityStreamOwner(
            scope = this,
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "token", owner = OWNER) },
            transport = transport,
            workerDispatcher = dispatcher,
            monotonicMillis = { ++monotonic },
        )
        owner.bindPresence { snapshots += it }

        owner.replacePresenceSources(setOf(PRESENCE))
        repeat(2) { runCurrent() }
        connection.messages.send(HaExactSocketMessage.State(PRESENCE, presenceState("on", 3_000L)))
        connection.messages.send(HaExactSocketMessage.State(PRESENCE, presenceState("off", 2_000L)))
        connection.messages.send(HaExactSocketMessage.State(PRESENCE, presenceState("on", 4_000L)))
        connection.messages.send(HaExactSocketMessage.State(PRESENCE, presenceState("off", 5_000L)))
        runCurrent()
        hydration.complete(presenceState("off", 1_000L))
        runCurrent()

        assertEquals(HaPresenceValue.OFF, snapshots.last().states[PRESENCE])
        assertEquals(PRESENCE, snapshots.last().lastOnActivity?.entityId)
        assertEquals(1L, snapshots.last().lastOnActivity?.sequence)
        assertEquals(1L, snapshots.last().lastOnActivity?.receivedAtMonotonicMs)
        owner.close()
    }

    @Test fun `compressed union projection returns every entity changed in one event`() {
        val projection = HaCompressedEntityProjection(setOf(ENTITY_A, PRESENCE))
        val event = JSONObject().put("a", JSONObject()
            .put(ENTITY_A, JSONObject().put("s", "10"))
            .put(PRESENCE, JSONObject().put("s", "on")))

        val updates = projection.applyAll(event)

        assertEquals(setOf(ENTITY_A, PRESENCE), updates.filterIsInstance<HaExactSocketMessage.State>()
            .mapTo(linkedSetOf(), HaExactSocketMessage.State::entityId))
    }

    @Test fun `presence union admits more than sixteen sources`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport(FakeConnection())
        val owner = HaExactEntityStreamOwner(
            scope = this,
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "token", owner = OWNER) },
            transport = transport,
            workerDispatcher = dispatcher,
        )

        val sources = (1..32).mapTo(linkedSetOf()) { "binary_sensor.motion_$it" }
        owner.replacePresenceSources(sources)
        runCurrent()

        assertEquals(listOf(sources), transport.subscriptions)
        owner.close()
    }

    @Test fun `subscribe first buffers socket state until REST hydration`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val hydration = CompletableDeferred<JSONObject?>()
        val transport = FakeTransport(connection).apply { states[ENTITY_A] = hydration }
        val observer = RecordingObserver()
        val owner = owner(dispatcher, transport, observer)

        owner.replaceAmbientSource(ENTITY_A)
        repeat(2) { runCurrent() }
        connection.messages.send(HaExactSocketMessage.State(state(ENTITY_A, "50")))
        runCurrent()
        assertTrue(observer.updates.isEmpty())

        hydration.complete(state(ENTITY_A, "10"))
        runCurrent()

        assertEquals(listOf(true, false), observer.updates.map(HaExactEntityUpdate::initial))
        assertEquals(listOf("10", "50"), observer.updates.map { (it as HaExactEntityUpdate.State).json.getString("state") })
        assertEquals(HaExactEntityStreamPhase.LIVE, observer.statuses.last().phase)
        assertEquals(1, observer.statuses.count { it.phase == HaExactEntityStreamPhase.LIVE })
        owner.close()
    }

    @Test fun `replacement generation rejects late hydration and closes the old socket`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = FakeConnection()
        val second = FakeConnection()
        val staleHydration = CompletableDeferred<JSONObject?>()
        val transport = FakeTransport(first, second).apply {
            states[ENTITY_A] = staleHydration
            states[ENTITY_B] = CompletableDeferred(state(ENTITY_B, "20"))
        }
        val observer = RecordingObserver()
        val owner = owner(dispatcher, transport, observer)

        owner.replaceAmbientSource(ENTITY_A)
        repeat(2) { runCurrent() }
        owner.replaceAmbientSource(ENTITY_B)
        repeat(2) { runCurrent() }
        staleHydration.complete(state(ENTITY_A, "99"))
        runCurrent()

        assertEquals(listOf(ENTITY_B), observer.updates.map(HaExactEntityUpdate::entityId).distinct())
        assertEquals(1, first.closeCount)
        assertEquals(ENTITY_B, observer.statuses.last().entityId)
        owner.close()
    }

    @Test fun `authentication refresh liveness reconnect and close stay with one owner`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = FakeConnection(autoPong = false)
        val second = FakeConnection(autoPong = true)
        val transport = FakeTransport(first, second).apply {
            rejectSubscriptions = 1
            states[ENTITY_A] = CompletableDeferred(state(ENTITY_A, "10"))
        }
        val forces = mutableListOf<Boolean>()
        val auth = HaApiSessionProvider { force ->
            forces += force
            HaApiSession("https://ha.example", "token-${forces.size}", owner = OWNER)
        }
        val observer = RecordingObserver()
        val owner = owner(
            dispatcher,
            transport,
            observer,
            auth,
            probeIntervalMs = 100,
            pongTimeoutMs = 50,
            reconnectBaseMs = 10,
            reconnectMaxMs = 10,
        )

        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        assertEquals(listOf(false), forces)
        assertEquals(HaExactEntityStreamPhase.AUTH_FAILED, observer.statuses.last().phase)
        advanceTimeBy(10)
        runCurrent()
        assertEquals(listOf(false, true), forces)

        advanceTimeBy(151)
        runCurrent()
        assertEquals(HaExactEntityStreamPhase.RECONNECTING, observer.statuses.last().phase)
        advanceTimeBy(10)
        runCurrent()
        assertEquals(3, transport.subscribeCount)
        assertEquals(HaExactEntityStreamPhase.LIVE, observer.statuses.last().phase)

        owner.close()
        runCurrent()
        val callbackCountAfterClose = observer.statuses.size + observer.updates.size
        advanceTimeBy(24L * 60L * 60_000L)
        runCurrent()
        assertEquals(HaExactEntityStreamPhase.STOPPED, observer.statuses.last().phase)
        assertEquals(1, second.closeCount)
        assertEquals(callbackCountAfterClose, observer.statuses.size + observer.updates.size)
    }

    @Test fun `subscribe timeout reconnects while parent cancellation remains available`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport(FakeConnection()).apply { subscribeTimeouts = 1 }
        val observer = RecordingObserver()
        val owner = owner(
            dispatcher,
            transport,
            observer,
            subscribeTimeoutMs = 50,
            reconnectBaseMs = 10,
            reconnectMaxMs = 10,
        )

        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertEquals(HaExactEntityStreamPhase.RECONNECTING, observer.statuses.last().phase)
        assertEquals(1, observer.statuses.last().reconnectAttempt)

        advanceTimeBy(10)
        runCurrent()
        assertEquals(2, transport.subscribeCount)
        assertEquals(HaExactEntityStreamPhase.LIVE, observer.statuses.last().phase)
        owner.close()
    }

    @Test fun `hydration timeout closes the socket and reconnects`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = FakeConnection()
        val second = FakeConnection()
        val transport = FakeTransport(first, second).apply { stateTimeouts = 1 }
        val observer = RecordingObserver()
        val owner = owner(
            dispatcher,
            transport,
            observer,
            hydrationTimeoutMs = 50,
            reconnectBaseMs = 10,
            reconnectMaxMs = 10,
        )

        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertEquals(HaExactEntityStreamPhase.RECONNECTING, observer.statuses.last().phase)
        assertEquals(1, first.closeCount)

        advanceTimeBy(10)
        runCurrent()
        assertEquals(2, transport.subscribeCount)
        assertEquals(HaExactEntityStreamPhase.LIVE, observer.statuses.last().phase)
        owner.close()
    }

    @Test fun `close during hydration cancels work and rejects late state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeConnection()
        val hydration = CompletableDeferred<JSONObject?>()
        val transport = FakeTransport(connection).apply { states[ENTITY_A] = hydration }
        val observer = RecordingObserver()
        val owner = owner(dispatcher, transport, observer)

        owner.replaceAmbientSource(ENTITY_A)
        repeat(2) { runCurrent() }
        assertEquals(HaExactEntityStreamPhase.SYNCHRONIZING, observer.statuses.last().phase)

        owner.close()
        hydration.complete(state(ENTITY_A, "99"))
        runCurrent()

        assertEquals(HaExactEntityStreamPhase.STOPPED, observer.statuses.last().phase)
        assertTrue(observer.updates.isEmpty())
        assertEquals(1, connection.closeCount)
    }

    @Test fun `authentication exhausts one forced refresh and same source explicitly recovers`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport(FakeConnection())
        val observer = RecordingObserver()
        val forces = mutableListOf<Boolean>()
        var reject = true
        val auth = HaApiSessionProvider { force ->
            forces += force
            if (reject) HaApiSession("https://ha.example", null, rejected = true)
            else HaApiSession("https://ha.example", "token", owner = OWNER)
        }
        val owner = owner(
            dispatcher,
            transport,
            observer,
            auth,
            reconnectBaseMs = 10,
            reconnectMaxMs = 10,
        )

        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        assertEquals(listOf(false, true), forces)
        assertEquals(HaExactEntityStreamPhase.AUTH_FAILED, observer.statuses.last().phase)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, forces.size)

        reject = false
        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        assertEquals(HaExactEntityStreamPhase.LIVE, observer.statuses.last().phase)
        owner.close()
    }

    @Test fun `missing stable credential owner parks setup without retry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport(FakeConnection())
        val observer = RecordingObserver()
        var resolves = 0
        val owner = owner(
            dispatcher,
            transport,
            observer,
            auth = HaApiSessionProvider {
                resolves++
                HaApiSession("https://ha.example", "token", owner = null)
            },
        )

        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        assertEquals(HaExactEntityStreamPhase.AUTH_FAILED, observer.statuses.last().phase)
        assertTrue(observer.statuses.last().detail.contains("credentials changed"))

        advanceTimeBy(24L * 60L * 60_000L)
        runCurrent()
        assertEquals(1, resolves)
        assertEquals(0, transport.subscribeCount)
        owner.close()
    }

    @Test fun `credential owner change during forced refresh parks the generation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport(FakeConnection()).apply { rejectSubscriptions = 1 }
        val observer = RecordingObserver()
        val forces = mutableListOf<Boolean>()
        val changedOwner = OWNER.copy(clientId = "replacement-client")
        val owner = owner(
            dispatcher,
            transport,
            observer,
            auth = HaApiSessionProvider { force ->
                forces += force
                HaApiSession("https://ha.example", "token", owner = if (force) changedOwner else OWNER)
            },
            reconnectBaseMs = 10,
            reconnectMaxMs = 10,
        )

        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        advanceTimeBy(10)
        runCurrent()

        assertEquals(listOf(false, true), forces)
        assertEquals(HaExactEntityStreamPhase.AUTH_FAILED, observer.statuses.last().phase)
        assertTrue(observer.statuses.last().detail.contains("credentials changed"))
        assertEquals(1, transport.subscribeCount)
        owner.close()
    }

    @Test fun `deterministic protocol failure parks after three attempts`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport().apply { protocolSubscriptionFailures = Int.MAX_VALUE }
        val observer = RecordingObserver()
        val owner = owner(
            dispatcher,
            transport,
            observer,
            reconnectBaseMs = 10,
            reconnectMaxMs = 10,
        )

        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        repeat(2) {
            advanceTimeBy(10)
            runCurrent()
        }
        assertEquals(3, transport.subscribeCount)
        assertEquals(HaExactEntityStreamPhase.RECONNECTING, observer.statuses.last().phase)

        advanceTimeBy(24L * 60L * 60_000L)
        runCurrent()
        assertEquals(3, transport.subscribeCount)
        owner.close()
    }

    @Test fun `normal remote close reconnects and healthy live resets retry counters`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = FakeConnection()
        val second = FakeConnection()
        val third = FakeConnection()
        val transport = FakeTransport(first, second, third)
        val observer = RecordingObserver()
        val owner = owner(
            dispatcher,
            transport,
            observer,
            reconnectBaseMs = 10,
            reconnectMaxMs = 10,
        )

        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        first.messages.close()
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        second.messages.close()
        runCurrent()

        assertEquals(listOf(1, 1), observer.statuses
            .filter { it.phase == HaExactEntityStreamPhase.RECONNECTING }
            .map(HaExactEntityStreamStatus::reconnectAttempt))
        advanceTimeBy(10)
        runCurrent()
        assertEquals(3, transport.subscribeCount)
        assertEquals(HaExactEntityStreamPhase.LIVE, observer.statuses.last().phase)
        owner.close()
    }

    @Test fun `stalled connection close is bounded before reconnect`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = FakeConnection(closeNeverReturns = true)
        val second = FakeConnection()
        val transport = FakeTransport(first, second)
        val observer = RecordingObserver()
        val owner = owner(
            dispatcher,
            transport,
            observer,
            reconnectBaseMs = 10,
            reconnectMaxMs = 10,
            closeTimeoutMs = 50,
        )

        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()
        first.messages.close()
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        advanceTimeBy(10)
        runCurrent()

        assertEquals(2, transport.subscribeCount)
        assertEquals(HaExactEntityStreamPhase.LIVE, observer.statuses.last().phase)
        owner.close()
    }

    @Test fun `observer may replace its source reentrantly without stale delivery`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = FakeTransport(FakeConnection())
        lateinit var owner: HaExactEntityStreamOwner
        val observer = object : HaExactEntityStreamObserver {
            val statuses = mutableListOf<HaExactEntityStreamStatus>()
            val updates = mutableListOf<HaExactEntityUpdate>()
            override fun onStatus(status: HaExactEntityStreamStatus) {
                statuses += status
                if (status.phase == HaExactEntityStreamPhase.SYNCHRONIZING) owner.replaceAmbientSource(null)
            }
            override fun onUpdate(update: HaExactEntityUpdate) {
                updates += update
            }
        }
        owner = HaExactEntityStreamOwner(
            scope = this,
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "token", owner = OWNER) },
            transport = transport,
            workerDispatcher = dispatcher,
        )
        owner.bindAmbient(observer)

        owner.replaceAmbientSource(ENTITY_A)
        runCurrent()

        assertEquals(HaExactEntityStreamPhase.DISABLED, observer.statuses.last().phase)
        assertTrue(observer.updates.isEmpty())
        owner.close()
    }

    private fun kotlinx.coroutines.test.TestScope.owner(
        dispatcher: CoroutineDispatcher,
        transport: FakeTransport,
        observer: RecordingObserver,
        auth: HaApiSessionProvider = HaApiSessionProvider {
            HaApiSession("https://ha.example", "token", owner = OWNER)
        },
        probeIntervalMs: Long = 45_000L,
        pongTimeoutMs: Long = 15_000L,
        reconnectBaseMs: Long = 1_000L,
        reconnectMaxMs: Long = 60_000L,
        subscribeTimeoutMs: Long = 35_000L,
        hydrationTimeoutMs: Long = 20_000L,
        closeTimeoutMs: Long = 5_000L,
    ) = HaExactEntityStreamOwner(
        scope = this,
        auth = auth,
        transport = transport,
        workerDispatcher = dispatcher,
        probeIntervalMs = probeIntervalMs,
        pongTimeoutMs = pongTimeoutMs,
        // The probe deadline is computed on this clock while the waits run on the scheduler's; the
        // two must agree or a slow host makes the deadline fall due early and probes over-fire.
        monotonicMillis = { testScheduler.currentTime },
        reconnectBaseMs = reconnectBaseMs,
        reconnectMaxMs = reconnectMaxMs,
        subscribeTimeoutMs = subscribeTimeoutMs,
        hydrationTimeoutMs = hydrationTimeoutMs,
        closeTimeoutMs = closeTimeoutMs,
    ).also { it.bindAmbient(observer) }

    private class RecordingObserver : HaExactEntityStreamObserver {
        val statuses = mutableListOf<HaExactEntityStreamStatus>()
        val updates = mutableListOf<HaExactEntityUpdate>()
        override fun onStatus(status: HaExactEntityStreamStatus) {
            statuses += status
        }
        override fun onUpdate(update: HaExactEntityUpdate) {
            updates += update
        }
    }

    private class FakeConnection(
        private val autoPong: Boolean = true,
        private val closeNeverReturns: Boolean = false,
    ) : HaExactEntityConnection {
        val messages = Channel<HaExactSocketMessage>(Channel.UNLIMITED)
        var closeCount = 0
        override suspend fun receive(): HaExactSocketMessage = messages.receive()
        override suspend fun ping(id: Int) {
            if (autoPong) messages.send(HaExactSocketMessage.Pong(id))
        }
        override suspend fun close() {
            closeCount++
            if (closeNeverReturns) awaitCancellation()
            messages.close()
        }
    }

    private class FakeTransport(vararg connections: FakeConnection) : HaExactEntityStreamTransport {
        private val connections = ArrayDeque(connections.toList())
        val states = linkedMapOf<String, CompletableDeferred<JSONObject?>>()
        var rejectSubscriptions = 0
        var subscribeTimeouts = 0
        var protocolSubscriptionFailures = 0
        var stateTimeouts = 0
        var subscribeCount = 0
        var lifecycleOutcome: HaExactSocketMessage? = HaExactSocketMessage.LifecycleEstablished
        val subscriptions = mutableListOf<Set<String>>()
        val registryWatches = mutableListOf<Boolean>()
        val lifecycleWatches = mutableListOf<Boolean>()

        override suspend fun subscribe(
            baseUrl: String,
            accessToken: String,
            entityIds: Set<String>,
        ): HaExactEntityConnection {
            return subscribe(baseUrl, accessToken, entityIds, false)
        }

        override suspend fun subscribe(
            baseUrl: String,
            accessToken: String,
            entityIds: Set<String>,
            watchRegistry: Boolean,
        ): HaExactEntityConnection =
            subscribe(baseUrl, accessToken, entityIds, watchRegistry, watchLifecycle = false)

        override suspend fun subscribe(
            baseUrl: String,
            accessToken: String,
            entityIds: Set<String>,
            watchRegistry: Boolean,
            watchLifecycle: Boolean,
        ): HaExactEntityConnection {
            subscribeCount++
            subscriptions += entityIds
            registryWatches += watchRegistry
            lifecycleWatches += watchLifecycle
            if (rejectSubscriptions-- > 0) throw HaAuthenticationException("rejected")
            if (subscribeTimeouts-- > 0) awaitCancellation()
            if (protocolSubscriptionFailures-- > 0) throw HaProtocolException("invalid handshake")
            return connections.removeFirst().also { connection ->
                if (watchLifecycle) lifecycleOutcome?.let(connection.messages::trySend)
            }
        }

        override suspend fun state(baseUrl: String, accessToken: String, entityId: String): JSONObject? {
            if (stateTimeouts-- > 0) awaitCancellation()
            return states.getOrPut(entityId) { CompletableDeferred(state(entityId, "1")) }.await()
        }
    }

    private companion object {
        const val ENTITY_A = "sensor.room_illuminance"
        const val ENTITY_B = "sensor.hall_illuminance"
        const val PRESENCE = "binary_sensor.room_motion"
        const val PRESENCE_B = "binary_sensor.hall_motion"
        val OWNER = HaAuthOwner("https://ha.example", "refresh", "client", "")
        fun state(entityId: String, value: String) = JSONObject()
            .put("entity_id", entityId)
            .put("state", value)
            .put("last_updated", "2026-07-17T10:00:00Z")
            .put("attributes", JSONObject().put("device_class", "illuminance").put("unit_of_measurement", "lx"))

        fun presenceState(value: String, observedAt: Long, entityId: String = PRESENCE) = JSONObject()
            .put("entity_id", entityId)
            .put("state", value)
            .put("last_updated", java.time.Instant.ofEpochMilli(observedAt).toString())
            .put("attributes", JSONObject().put("device_class", "motion"))
    }
}
