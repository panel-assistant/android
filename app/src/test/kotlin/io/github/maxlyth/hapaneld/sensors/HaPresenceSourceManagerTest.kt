package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.HaAuthSnapshot
import io.github.maxlyth.hapaneld.stableOwner
import io.github.maxlyth.hapaneld.control.AutoSleepController
import io.github.maxlyth.hapaneld.control.AutoSleepLearnedLease
import io.github.maxlyth.hapaneld.control.AutoSleepLearning
import io.github.maxlyth.hapaneld.control.AutoSleepLocalEvidence
import io.github.maxlyth.hapaneld.control.AutoSleepManagerHandle
import io.github.maxlyth.hapaneld.control.AutoSleepRuntimeConfig
import io.github.maxlyth.hapaneld.control.FakeBacklight
import io.github.maxlyth.hapaneld.control.FakeDaemon
import io.github.maxlyth.hapaneld.control.FakeRootShell
import io.github.maxlyth.hapaneld.control.FakeScreenPower
import io.github.maxlyth.hapaneld.control.FakeWakeTap
import io.github.maxlyth.hapaneld.control.MAX_AUTO_SLEEP_LEASE_MS
import io.github.maxlyth.hapaneld.control.MIN_AUTO_SLEEP_LEASE_MS
import io.github.maxlyth.hapaneld.control.ScreenController
import io.github.maxlyth.hapaneld.device.ScreenOff
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HaPresenceSourceManagerTest {
    @Test fun `prerequisite retries a rejected session provider once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val forces = mutableListOf<Boolean>()
        val provider = HaApiSessionProvider { force ->
            forces += force
            if (!force) HaApiSession("https://ha.example", null, rejected = true)
            else HaApiSession("https://ha.example", "token", owner = OWNER)
        }
        val discovery = FakePresenceTransport()
        val owner = exactOwner(dispatcher, FakeExactTransport(FakeExactConnection()), provider)
        val manager = HaPresenceSourceManager(
            this, provider, discovery, owner, { true }, dispatcher, ::epochMillis,
        )

        val result = manager.prerequisite("device-uid", "panel")

        assertEquals(listOf(false, true), forces)
        assertEquals(HaPanelAreaPrerequisitePhase.ASSIGNED, result.phase)
        manager.close()
        owner.close()
    }

    @Test fun `registry bursts debounce and replace the learned Area`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeExactConnection()
        val discovery = FakePresenceTransport()
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(
            dispatcher, discovery, FakeExactTransport(connection), aggregates,
        )
        manager.configure(request())
        runCurrent()
        assertEquals("Room", aggregates.last { it.phase == HaPresencePhase.LIVE }.areaName)

        discovery.areaId = "hall"
        discovery.areaName = "Hall"
        repeat(3) { connection.messages.send(HaExactSocketMessage.RegistryChanged) }
        runCurrent()
        advanceTimeBy(1_999L)
        runCurrent()
        assertEquals(1, discovery.registryCount)
        advanceTimeBy(1L)
        runCurrent()

        assertEquals(2, discovery.registryCount)
        assertEquals("Hall", aggregates.last { it.phase == HaPresencePhase.LIVE }.areaName)
        manager.close()
        owner.close()
    }

    @Test fun `a panel registered only by Panel Assistant goes live with its own entities excluded`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport().apply {
            panelAssistantOnly = true
            includePanelActivity = true
        }
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(
            dispatcher, discovery, FakeExactTransport(FakeExactConnection()), aggregates,
        )

        manager.configure(request(discoveryId = DID))
        runCurrent()

        assertTrue(
            "never went live; last=${aggregates.lastOrNull()?.phase}/${aggregates.lastOrNull()?.detail}",
            aggregates.any { it.phase == HaPresencePhase.LIVE },
        )
        val live = aggregates.last { it.phase == HaPresencePhase.LIVE }
        assertEquals("Room", live.areaName)
        assertEquals(setOf(ENTITY), live.selectedEntityIds)
        assertEquals("the panel's own proximity never reaches history", setOf(setOf(ENTITY)), discovery.historyEntitySets.toSet())
        assertEquals(
            HaPanelAreaPrerequisitePhase.ASSIGNED,
            manager.prerequisite("device-uid", "panel", discoveryId = DID).phase,
        )
        manager.close()
        owner.close()
    }

    @Test fun `a Panel Assistant device is not this panel without its discovery id`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport().apply { panelAssistantOnly = true }
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(
            dispatcher, discovery, FakeExactTransport(FakeExactConnection()), aggregates,
        )

        manager.configure(request(discoveryId = null))
        runCurrent()

        assertEquals(HaPresencePhase.DISCOVERY_FAILED, aggregates.last().phase)
        assertEquals("registry_projection", aggregates.last().detail)
        assertEquals(
            HaPanelAreaPrerequisitePhase.UNAVAILABLE,
            manager.prerequisite("device-uid", "panel", discoveryId = null).phase,
        )
        manager.close()
        owner.close()
    }

    @Test fun `registry changes after failed discoveries wait out a doubling backoff`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeExactConnection()
        val resubscribed = FakeExactConnection()
        val released = FakeExactConnection()
        val discovery = FakePresenceTransport().apply { registryFailure = true }
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(
            dispatcher, discovery, FakeExactTransport(connection, resubscribed, released), aggregates,
        )
        manager.configure(request())
        runCurrent()
        assertEquals(1, discovery.registryCount)
        assertEquals(HaPresencePhase.DISCOVERY_FAILED, aggregates.last().phase)

        // First failure: one minute, then the ordinary two-second coalesce.
        repeat(3) { connection.messages.send(HaExactSocketMessage.RegistryChanged) }
        runCurrent()
        advanceTimeBy(61_999L)
        runCurrent()
        assertEquals(1, discovery.registryCount)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, discovery.registryCount)

        // Second failure: two minutes. A later event does not push the window back.
        connection.messages.send(HaExactSocketMessage.RegistryChanged)
        runCurrent()
        advanceTimeBy(100_000L)
        connection.messages.send(HaExactSocketMessage.RegistryChanged)
        runCurrent()
        advanceTimeBy(21_999L)
        runCurrent()
        assertEquals(2, discovery.registryCount)
        discovery.registryFailure = false
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(3, discovery.registryCount)
        assertTrue(aggregates.any { it.phase == HaPresencePhase.LIVE })

        // A success clears the backoff. The stream re-subscribed for the selected source.
        resubscribed.messages.send(HaExactSocketMessage.RegistryChanged)
        runCurrent()
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(4, discovery.registryCount)

        // It also resets the count: the next failure opens a one-minute window again, not four.
        discovery.registryFailure = true
        resubscribed.messages.send(HaExactSocketMessage.RegistryChanged)
        runCurrent()
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(5, discovery.registryCount)
        listOf(resubscribed, released).forEach { it.messages.trySend(HaExactSocketMessage.RegistryChanged) }
        runCurrent()
        advanceTimeBy(61_999L)
        runCurrent()
        assertEquals(5, discovery.registryCount)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(6, discovery.registryCount)
        manager.close()
        owner.close()
    }

    @Test fun `an explicit refresh is never held by the registry backoff`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport().apply { registryFailure = true }
        val (manager, owner) = manager(
            dispatcher, discovery, FakeExactTransport(FakeExactConnection()), mutableListOf(),
        )
        manager.configure(request())
        runCurrent()
        assertEquals(1, discovery.registryCount)

        manager.refresh()
        runCurrent()

        assertEquals(2, discovery.registryCount)
        manager.close()
        owner.close()
    }

    @Test fun `prerequisite reads only device and Area registries`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport()
        val exact = FakeExactTransport(FakeExactConnection())
        val (manager, owner) = manager(dispatcher, discovery, exact, mutableListOf())

        val result = manager.prerequisite("device-uid", "panel")

        assertEquals(HaPanelAreaPrerequisitePhase.ASSIGNED, result.phase)
        assertEquals("Room", result.areaName)
        assertEquals(0, discovery.registryCount)
        assertEquals(1, discovery.panelAreaRegistryCount)
        assertTrue(discovery.historyRequests.isEmpty())
        manager.close()
        owner.close()
    }

    @Test fun `prerequisite distinguishes an unassigned panel`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport().apply { panelAreaAssigned = false }
        val exact = FakeExactTransport(FakeExactConnection())
        val (manager, owner) = manager(dispatcher, discovery, exact, mutableListOf())

        val result = manager.prerequisite("device-uid", "panel")

        assertEquals(HaPanelAreaPrerequisitePhase.UNASSIGNED, result.phase)
        assertFalse(result.eligible)
        manager.close()
        owner.close()
    }

    @Test fun `aggregate admission rejects stale epoch generation and revision`() {
        val current = HaPresenceAggregate(
            controllerEpoch = 7L,
            managerGeneration = 11L,
            feedGeneration = 13L,
            feedRevision = 17L,
            selectedEntityIds = setOf(ENTITY),
            hydrated = true,
        )
        fun feed(generation: Long, revision: Long) = HaPresenceFeedSnapshot(
            generation = generation,
            revision = revision,
            sourceIds = setOf(ENTITY),
        )

        assertFalse(current.admits(feed(13L, 18L), epoch = 6L, generation = 11L))
        assertFalse(current.admits(feed(13L, 18L), epoch = 7L, generation = 10L))
        assertFalse(current.admits(feed(12L, 99L), epoch = 7L, generation = 11L))
        assertFalse(current.admits(feed(13L, 17L), epoch = 7L, generation = 11L))
        assertFalse(current.admits(feed(13L, 16L), epoch = 7L, generation = 11L))
        assertTrue(current.admits(feed(13L, 18L), epoch = 7L, generation = 11L))
        assertTrue(current.admits(feed(14L, 0L), epoch = 7L, generation = 11L))
    }

    @Test fun `aggregate collections reject mutation and isolate constructor inputs`() {
        val states = linkedMapOf(ENTITY to HaPresenceValue.OFF)
        val ids = linkedSetOf(ENTITY)
        val aggregate = HaPresenceAggregate(finalStates = states, selectedEntityIds = ids)

        states[ENTITY] = HaPresenceValue.ON
        ids += "binary_sensor.late"
        assertEquals(mapOf(ENTITY to HaPresenceValue.OFF), aggregate.finalStates)
        assertEquals(setOf(ENTITY), aggregate.selectedEntityIds)
        assertTrue(runCatching {
            @Suppress("UNCHECKED_CAST")
            (aggregate.finalStates as MutableMap<String, HaPresenceValue>)[ENTITY] = HaPresenceValue.ON
        }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching {
            @Suppress("UNCHECKED_CAST")
            (aggregate.selectedEntityIds as MutableSet<String>) += "binary_sensor.late"
        }.exceptionOrNull() is UnsupportedOperationException)
    }

    @Test fun `aggregate preserves final OFF with an advanced activity marker`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connection = FakeExactConnection()
        val hydration = CompletableDeferred<JSONObject?>()
        val exact = FakeExactTransport(connection).apply { deferredStates[ENTITY] = hydration }
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(dispatcher, FakePresenceTransport(), exact, aggregates)

        manager.configure(request(controllerEpoch = 23L))
        repeat(2) { runCurrent() }
        connection.messages.send(HaExactSocketMessage.State(ENTITY, state(ENTITY, "on", 2_000L)))
        connection.messages.send(HaExactSocketMessage.State(ENTITY, state(ENTITY, "off", 3_000L)))
        runCurrent()
        hydration.complete(state(ENTITY, "off", 1_000L))
        runCurrent()

        val live = aggregates.last { it.phase == HaPresencePhase.LIVE }
        assertEquals(23L, live.controllerEpoch)
        assertTrue(live.hydrated)
        assertEquals(HaPresenceValue.OFF, live.finalStates.getValue(ENTITY))
        assertEquals(ENTITY, live.activityMarker?.entityId)
        assertTrue(checkNotNull(live.activityMarker).sequence > 0L)
        manager.close()
        owner.close()
    }

    @Test fun `unchanged feed snapshot does not republish an aggregate`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val exact = FakeExactTransport(FakeExactConnection()).apply { states[ENTITY] = state(ENTITY, "off") }
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(dispatcher, FakePresenceTransport(), exact, aggregates)

        manager.configure(request())
        runCurrent()
        val exposed = aggregates.last { it.hydrated }
        val mutation = runCatching {
            @Suppress("UNCHECKED_CAST")
            (exposed.finalStates as MutableMap<String, HaPresenceValue>)[ENTITY] = HaPresenceValue.ON
        }.exceptionOrNull()
        assertTrue(mutation is UnsupportedOperationException)
        assertEquals(HaPresenceValue.OFF, manager.latestAggregate().finalStates.getValue(ENTITY))
        val before = aggregates.size
        owner.replacePresenceSources(setOf(ENTITY))
        runCurrent()

        assertEquals(before, aggregates.size)
        manager.close()
        owner.close()
    }

    @Test fun `refresh and newer controller epoch reject stale feed versions`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val exact = FakeExactTransport(FakeExactConnection()).apply { states[ENTITY] = state(ENTITY, "off") }
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(dispatcher, FakePresenceTransport(), exact, aggregates)

        manager.configure(request(controllerEpoch = 41L))
        runCurrent()
        val first = manager.latestAggregate()
        manager.refresh()
        runCurrent()
        val refreshed = manager.latestAggregate()
        assertEquals(41L, refreshed.controllerEpoch)
        assertEquals(first.managerGeneration, refreshed.managerGeneration)
        assertEquals(first.feedGeneration, refreshed.feedGeneration)
        assertEquals(first.feedRevision, refreshed.feedRevision)

        val afterRefresh = aggregates.size
        manager.acceptFeed(feed(refreshed, revision = refreshed.feedRevision - 1L))
        manager.acceptFeed(feed(refreshed, revision = refreshed.feedRevision))
        assertEquals(afterRefresh, aggregates.size)

        manager.configure(request(controllerEpoch = 42L))
        runCurrent()
        val newerEpoch = manager.latestAggregate()
        assertEquals(42L, newerEpoch.controllerEpoch)
        val afterEpoch = aggregates.size
        manager.acceptFeed(feed(newerEpoch, revision = newerEpoch.feedRevision - 1L))
        assertEquals(afterEpoch, aggregates.size)

        manager.acceptFeed(feed(newerEpoch, generation = newerEpoch.feedGeneration + 1L, revision = 0L))
        assertEquals(newerEpoch.feedGeneration + 1L, manager.latestAggregate().feedGeneration)
        assertEquals(0L, manager.latestAggregate().feedRevision)
        manager.close()
        owner.close()
    }

    @Test fun `activity during a refresh reaches consumers and the unchanged result keeps it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val exact = FakeExactTransport(FakeExactConnection()).apply { states[ENTITY] = state(ENTITY, "off") }
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val discovery = FakePresenceTransport()
        val (manager, owner) = manager(dispatcher, discovery, exact, aggregates)
        manager.configure(request())
        runCurrent()
        val live = manager.latestAggregate()
        assertEquals(HaPresencePhase.LIVE, live.phase)

        val before = aggregates.size
        manager.refresh()
        manager.acceptFeed(feed(live, revision = live.feedRevision + 1L, value = HaPresenceValue.ON))
        assertEquals(HaPresenceValue.ON, manager.latestAggregate().finalStates.getValue(ENTITY))
        runCurrent()

        assertEquals(2, discovery.registryCount)
        val after = manager.latestAggregate()
        assertEquals(HaPresencePhase.LIVE, after.phase)
        assertEquals(HaPresenceValue.ON, after.finalStates.getValue(ENTITY))
        assertEquals(listOf(HaPresencePhase.LIVE), aggregates.drop(before).map { it.phase }.distinct())
        manager.close()
        owner.close()
    }

    @Test fun `close racing feed delivery publishes exactly one empty STOPPED terminal`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val exact = FakeExactTransport(FakeExactConnection()).apply { states[ENTITY] = state(ENTITY, "off") }
        val aggregates = Collections.synchronizedList(mutableListOf<HaPresenceAggregate>())
        val (manager, owner) = manager(dispatcher, FakePresenceTransport(), exact, aggregates)
        manager.configure(request())
        runCurrent()
        val current = manager.latestAggregate()
        val next = feed(current, revision = current.feedRevision + 1L, value = HaPresenceValue.ON)
        val start = CountDownLatch(1)
        val feeder = thread(start = true) { start.await(); manager.acceptFeed(next) }
        val closer = thread(start = true) { start.await(); manager.close() }

        start.countDown()
        feeder.join()
        closer.join()
        manager.acceptFeed(feed(current, generation = current.feedGeneration + 1L, revision = 0L))

        val stopped = synchronized(aggregates) { aggregates.filter { it.phase == HaPresencePhase.STOPPED } }
        assertEquals(1, stopped.size)
        assertTrue(stopped.single().selectedEntityIds.isEmpty())
        assertTrue(stopped.single().finalStates.isEmpty())
        assertFalse(stopped.single().hydrated)
        assertEquals(HaPresencePhase.STOPPED, manager.latestAggregate().phase)
        owner.close()
    }

    @Test fun `nonblocking aggregate offer may reenter without stale delivery`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val exact = FakeExactTransport(FakeExactConnection()).apply { states[ENTITY] = state(ENTITY, "off") }
        val owner = exactOwner(dispatcher, exact)
        val aggregates = mutableListOf<HaPresenceAggregate>()
        lateinit var manager: HaPresenceSourceManager
        manager = HaPresenceSourceManager(
            this, auth(), FakePresenceTransport(), owner,
            offerAggregate = { next ->
                aggregates += next
                if (next.phase == HaPresencePhase.LIVE) manager.configure(request(enabled = false))
                true
            },
            workerDispatcher = dispatcher,
            epochMillis = ::epochMillis,
        )

        manager.configure(request())
        runCurrent()

        assertEquals(HaPresencePhase.DISABLED, manager.latestAggregate().phase)
        assertEquals(HaPresencePhase.DISABLED, aggregates.last().phase)
        manager.close()
        owner.close()
    }

    @Test fun `automatic Area selection admits every history credible source`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport(sourceCount = 300)
        val exact = FakeExactTransport(FakeExactConnection())
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(dispatcher, discovery, exact, aggregates)

        manager.configure(request())
        runCurrent()
        val expected = discovery.entityIds()
        assertEquals(expected, exact.subscriptions.single())
        assertEquals(expected, aggregates.last { it.phase == HaPresencePhase.LIVE }.selectedEntityIds)
        manager.close()
        owner.close()
    }

    @Test fun `panel owned occupancy never enters history or shared stream selection`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport().apply { includePanelActivity = true }
        val exact = FakeExactTransport(FakeExactConnection())
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(dispatcher, discovery, exact, aggregates)

        manager.configure(request())
        runCurrent()

        assertTrue(discovery.historyEntitySets.isNotEmpty())
        assertTrue(discovery.historyEntitySets.all { SELF !in it })
        assertEquals(setOf(ENTITY), exact.subscriptions.single())
        assertEquals(setOf(ENTITY), aggregates.last { it.phase == HaPresencePhase.LIVE }.selectedEntityIds)
        manager.close()
        owner.close()
    }

    @Test fun `on-demand history reads only the current selected sources and exact requested range`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport(sourceCount = 3)
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(
            dispatcher, discovery, FakeExactTransport(FakeExactConnection()), aggregates,
        )
        manager.configure(request())
        runCurrent()
        discovery.historyRequests.clear()

        val pending = async { manager.selectedHistory(10 * 60_000L, 20 * 60_000L) }
        runCurrent()
        val history = pending.await()

        assertEquals(discovery.entityIds(), history.sourceIds)
        assertEquals(discovery.entityIds(), history.transitions.keys)
        assertEquals(discovery.entityIds(), history.sourceLabels.keys)
        assertTrue(history.sourceLabels.values.all { it.startsWith("Motion ") })
        assertEquals(listOf(Triple(discovery.entityIds(), 10 * 60_000L, 20 * 60_000L)),
            discovery.historyRequests)
        manager.close()
        owner.close()
    }

    @Test fun `excluded source stays visible in history but never reaches the live stream`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport(sourceCount = 3)
        val exclusions = FakeExclusions(mutableSetOf("binary_sensor.motion_2"))
        val exact = FakeExactTransport(FakeExactConnection(), FakeExactConnection())
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(dispatcher, discovery, exact, aggregates, exclusions)

        manager.configure(request())
        runCurrent()
        discovery.historyRequests.clear()
        val pending = async { manager.selectedHistory(10 * 60_000L, 20 * 60_000L) }
        runCurrent()
        val history = pending.await()

        assertEquals(discovery.entityIds() - "binary_sensor.motion_2", exact.subscriptions.single())
        assertEquals(discovery.entityIds() - "binary_sensor.motion_2", history.sourceIds)
        assertEquals(discovery.entityIds(), history.discoveredSourceIds)
        assertEquals(setOf("binary_sensor.motion_2"), history.excludedSourceIds)
        assertEquals(discovery.entityIds(), history.transitions.keys)
        assertEquals("Room", history.areaName)
        assertTrue(history.areaKey.matches(Regex("[a-f0-9]{64}")))
        assertTrue(history.sourceKeys.values.all { it.matches(Regex("[a-f0-9]{64}")) })
        assertEquals(HaPresenceSourceUpdate.UPDATED, manager.setSourceIncluded(
            history.areaKey, history.sourceKeys.getValue("binary_sensor.motion_2"), true,
        ))
        assertEquals(HaPresenceSourceUpdate.UPDATED, manager.setSourceIncluded(
            history.areaKey, history.sourceKeys.getValue("binary_sensor.motion_2"), true,
        ))
        runCurrent()
        assertEquals(discovery.entityIds(), exact.subscriptions.last())
        assertTrue(exclusions.excluded("room").isEmpty())
        manager.close()
        owner.close()
    }

    @Test fun `all suppressed sources remain recoverable and survive discovery list changes`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport(sourceCount = 1)
        val exclusions = FakeExclusions(mutableSetOf(ENTITY))
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(
            dispatcher, discovery, FakeExactTransport(FakeExactConnection()), aggregates, exclusions,
        )

        manager.configure(request())
        runCurrent()
        assertEquals(HaPresencePhase.NO_INCLUDED_SOURCES, aggregates.last().phase)
        val first = async { manager.selectedHistory(10 * 60_000L, 20 * 60_000L) }
        runCurrent()
        assertEquals(setOf(ENTITY), first.await().excludedSourceIds)

        discovery.sourceCount = 3
        manager.refresh()
        runCurrent()
        val second = async { manager.selectedHistory(10 * 60_000L, 20 * 60_000L) }
        runCurrent()
        val changed = second.await()
        assertTrue(ENTITY in exclusions.excluded("room"))
        assertTrue(changed.excludedSourceIds.isEmpty())
        assertTrue("binary_sensor.motion_2" in changed.sourceIds)

        discovery.sourceCount = 1
        manager.refresh()
        runCurrent()
        val returned = async { manager.selectedHistory(10 * 60_000L, 20 * 60_000L) }
        runCurrent()
        assertEquals(setOf(ENTITY), returned.await().excludedSourceIds)
        manager.close()
        owner.close()
    }

    @Test fun `derived Area helper has no presence authority and is never read or streamed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport().apply { includeSupportingActivity = true }
        val exact = FakeExactTransport(FakeExactConnection())
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(dispatcher, discovery, exact, aggregates)

        manager.configure(request())
        runCurrent()

        assertTrue(discovery.historyEntitySets.all { SUPPORTING !in it })
        assertEquals(setOf(ENTITY), exact.subscriptions.single())
        assertEquals(setOf(ENTITY), aggregates.last { it.phase == HaPresencePhase.LIVE }.selectedEntityIds)
        discovery.historyRequests.clear()
        val pending = async { manager.selectedHistory(10 * 60_000L, 20 * 60_000L) }
        runCurrent()
        val history = pending.await()
        assertEquals(setOf(ENTITY), history.sourceIds)
        assertEquals(setOf(ENTITY), history.transitions.keys)
        assertEquals(listOf(Triple(setOf(ENTITY), 10 * 60_000L, 20 * 60_000L)),
            discovery.historyRequests)
        manager.close()
        owner.close()
    }

    @Test fun `derived-only Area fails safe before history retrieval`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport(sourceCount = 0).apply { includeSupportingActivity = true }
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(
            dispatcher, discovery, FakeExactTransport(FakeExactConnection()), aggregates,
        )

        manager.configure(request())
        runCurrent()

        val terminal = aggregates.last()
        assertEquals(HaPresencePhase.NO_CREDIBLE_SOURCES, terminal.phase)
        assertEquals("no_device_source", terminal.detail)
        assertEquals("Room", terminal.areaName)
        assertTrue(discovery.historyEntitySets.isEmpty())
        assertTrue(terminal.selectedEntityIds.isEmpty())
        manager.close()
        owner.close()
    }

    @Test fun `Area sources with insufficient history report why they are not ready`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport().apply { emptyHistory = true }
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(
            dispatcher, discovery, FakeExactTransport(FakeExactConnection()), aggregates,
        )

        manager.configure(request())
        runCurrent()

        val terminal = aggregates.last()
        assertEquals(HaPresencePhase.NO_CREDIBLE_SOURCES, terminal.phase)
        assertEquals("insufficient_history", terminal.detail)
        assertEquals("Room", terminal.areaName)
        assertFalse(discovery.historyEntitySets.isEmpty())
        assertTrue(discovery.historyEntitySets.all { it == setOf(ENTITY) })
        assertTrue(terminal.selectedEntityIds.isEmpty())
        manager.close()
        owner.close()
    }

    @Test fun `disabled manager owns no discovery stream or timer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport()
        val exact = FakeExactTransport(FakeExactConnection())
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(dispatcher, discovery, exact, aggregates)

        manager.configure(request(enabled = false, controllerEpoch = 31L))
        runCurrent()
        advanceTimeBy(24L * 60L * 60_000L)
        runCurrent()

        val disabled = aggregates.last()
        assertEquals(HaPresencePhase.DISABLED, disabled.phase)
        assertEquals(31L, disabled.controllerEpoch)
        assertEquals(0, discovery.registryCount)
        assertEquals(0, exact.subscriptions.size)

        manager.close()
        val stopped = aggregates.last()
        assertEquals(HaPresencePhase.STOPPED, stopped.phase)
        assertEquals(31L, stopped.controllerEpoch)
        assertTrue(stopped.managerGeneration > disabled.managerGeneration)
        assertTrue(stopped.finalStates.isEmpty())
        owner.close()
    }

    @Test fun `failed discovery is one shot until explicit refresh`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport().apply { registryFailure = true }
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(
            dispatcher, discovery, FakeExactTransport(FakeExactConnection(), FakeExactConnection()), aggregates,
        )

        manager.configure(request())
        runCurrent()
        assertEquals(HaPresencePhase.DISCOVERY_FAILED, aggregates.last().phase)
        assertEquals("registry_transport", aggregates.last().detail)
        advanceTimeBy(24L * 60L * 60_000L)
        runCurrent()
        assertEquals(1, discovery.registryCount)

        discovery.registryFailure = false
        manager.refresh()
        runCurrent()
        assertEquals(2, discovery.registryCount)
        assertEquals(HaPresencePhase.LIVE, aggregates.last().phase)
        manager.close()
        owner.close()
    }

    @Test fun `registry projection failure retains the independently resolved Area`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport().apply { malformedEntityRegistry = true }
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(
            dispatcher, discovery, FakeExactTransport(FakeExactConnection()), aggregates,
        )

        manager.configure(request())
        runCurrent()

        val terminal = aggregates.last()
        assertEquals(HaPresencePhase.DISCOVERY_FAILED, terminal.phase)
        assertEquals("registry_projection", terminal.detail)
        assertEquals("Room", terminal.areaName)
        assertTrue(terminal.selectedEntityIds.isEmpty())
        manager.close()
        owner.close()
    }

    @Test fun `history transport failure retains the projected Area`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport().apply { historyTransportFailure = true }
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(
            dispatcher, discovery, FakeExactTransport(FakeExactConnection()), aggregates,
        )

        manager.configure(request())
        runCurrent()

        val terminal = aggregates.last()
        assertEquals(HaPresencePhase.DISCOVERY_FAILED, terminal.phase)
        assertEquals("history_transport", terminal.detail)
        assertEquals("Room", terminal.areaName)
        manager.close()
        owner.close()
    }

    @Test fun `history shape and volume failures remain distinct`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport().apply { malformedHistory = true }
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val (manager, owner) = manager(
            dispatcher, discovery, FakeExactTransport(FakeExactConnection()), aggregates,
        )

        manager.configure(request())
        runCurrent()
        assertEquals("history_parse", aggregates.last().detail)

        discovery.malformedHistory = false
        discovery.oversizedHistory = true
        manager.refresh()
        runCurrent()
        assertEquals("history_limit", aggregates.last().detail)
        manager.close()
        owner.close()
    }

    @Test fun `one discovery run performs at most one forced authentication refresh`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val discovery = FakePresenceTransport().apply { registryAuthFailures = Int.MAX_VALUE }
        val forces = mutableListOf<Boolean>()
        val session = HaApiSessionProvider { force ->
            forces += force
            HaApiSession("https://ha.example", "token", owner = OWNER)
        }
        val owner = exactOwner(dispatcher, FakeExactTransport(FakeExactConnection()), session)
        val aggregates = mutableListOf<HaPresenceAggregate>()
        val manager = HaPresenceSourceManager(
            this, session, discovery, owner, aggregates::add, dispatcher, ::epochMillis,
        )

        manager.configure(request())
        runCurrent()

        // Discovery owns exactly one ordinary + one forced resolution. The shared registry watcher
        // independently resolves once because enabled/no-source demand must remain subscribed.
        assertEquals(listOf(false, true), forces.take(2))
        assertEquals(false, forces.getOrNull(2))
        assertEquals(2, discovery.registryCount)
        assertEquals(HaPresencePhase.AUTH_FAILED, aggregates.last().phase)
        manager.close()
        owner.close()
    }

    @Test fun `registry refresh that finds nothing new leaves an automatically slept screen dark`() = runTest {
        val panel = sleepingPanel()

        panel.registryChanged()

        assertEquals(2, panel.discovery.registryCount)
        assertTrue(panel.screen.isIntendedOff())
        assertEquals(listOf(false), panel.screenChanges)
        assertEquals("live", panel.status().getString("phase"))
    }

    @Test fun `registry refresh that changes the selection wakes the slept screen`() = runTest {
        val panel = sleepingPanel()

        panel.discovery.sourceCount = 2
        panel.registryChanged()

        assertFalse(panel.screen.isIntendedOff())
        assertEquals(listOf(false, true), panel.screenChanges)
        assertEquals(2, panel.status().getInt("source_count"))
    }

    @Test fun `failed rediscovery wakes the slept screen and the status names that wake`() = runTest {
        val panel = sleepingPanel()

        panel.discovery.registryFailure = true
        panel.registryChanged()
        assertFalse(panel.screen.isIntendedOff())
        assertEquals("discovery_failed", panel.status().getString("phase"))

        panel.discovery.registryFailure = false
        panel.registryChanged()
        assertEquals("live", panel.status().getString("phase"))
        assertEquals("source_loss_wake", panel.status().getString("reason"))
    }

    private inner class SleepingPanel(
        val discovery: FakePresenceTransport,
        val screen: ScreenController,
        val screenChanges: List<Boolean>,
        private val exact: FakeExactTransport,
        private val connections: List<FakeExactConnection>,
        private val controller: AutoSleepController,
        private val scope: kotlinx.coroutines.test.TestScope,
    ) {
        fun status() = JSONObject(controller.statusJson())

        /** One Home Assistant registry event on the live exact-entity socket, past its coalesce. */
        fun registryChanged() {
            connections[exact.subscriptions.size - 1].messages.trySend(HaExactSocketMessage.RegistryChanged)
            scope.runCurrent()
            scope.advanceTimeBy(2 * 60_000L)
            scope.runCurrent()
        }
    }

    /** The production wiring of manager, stream owner, controller and screen, slept by an expired lease. */
    private fun kotlinx.coroutines.test.TestScope.sleepingPanel(): SleepingPanel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connections = List(4) { FakeExactConnection() }
        val exact = FakeExactTransport(*connections.toTypedArray())
        val discovery = FakePresenceTransport()
        val owner = HaExactEntityStreamOwner(
            scope = backgroundScope, auth = auth(), transport = exact, workerDispatcher = dispatcher,
        )
        val now = java.util.concurrent.atomic.AtomicLong()
        val screenChanges = Collections.synchronizedList(mutableListOf<Boolean>())
        val screen = ScreenController(
            FakeBacklight(), FakeScreenPower(), FakeRootShell(),
            FakeDaemon(mapOf("SCREEN OFF" to "OK", "SCREEN ON" to "OK", "BLPOWER" to "0")),
            FakeWakeTap(), ScreenOff.DAEMON_BLPOWER,
        )
        val controller = AutoSleepController(
            scope = backgroundScope,
            screen = screen,
            configuration = { AutoSleepRuntimeConfig(true, "device-uid", "panel", "https://ha.example") },
            learning = object : AutoSleepLearning {
                override fun learnedLease(partition: String, baseLeaseMs: Long) =
                    AutoSleepLearnedLease(baseLeaseMs, 0, 0, MIN_AUTO_SLEEP_LEASE_MS)
                override fun recordGap(partition: String, evidence: AutoSleepLocalEvidence, gapMs: Long) = Unit
                override fun recordCorrection(partition: String, floorMs: Long) = Unit
                override fun flush() = Unit
            },
            onScreenChanged = { screenChanges += it },
            elapsedRealtime = now::get,
            epochMillis = ::epochMillis,
            workerDispatcher = dispatcher,
            sourceManagerFactory = { offer ->
                val manager = HaPresenceSourceManager(
                    backgroundScope, auth(), discovery, owner, offer, dispatcher, ::epochMillis, FakeExclusions(),
                )
                AutoSleepManagerHandle(manager::configure, manager::close, manager::refresh)
            },
        )
        screen.onWakeCompleted = controller::noteScreenWoken
        assertTrue(controller.start())
        runCurrent()
        assertEquals("live", JSONObject(controller.statusJson()).getString("phase"))
        now.set(MAX_AUTO_SLEEP_LEASE_MS + 60_000L)
        controller.advanceToForTest(now.get())
        runCurrent()
        assertTrue(screen.isIntendedOff())
        return SleepingPanel(discovery, screen, screenChanges, exact, connections, controller, this)
    }

    private fun kotlinx.coroutines.test.TestScope.manager(
        dispatcher: CoroutineDispatcher,
        transport: FakePresenceTransport,
        exactTransport: FakeExactTransport,
        aggregates: MutableList<HaPresenceAggregate>,
        exclusions: HaPresenceExclusions? = null,
    ): Pair<HaPresenceSourceManager, HaExactEntityStreamOwner> {
        val sessionProvider = auth()
        val owner = exactOwner(dispatcher, exactTransport, sessionProvider)
        return HaPresenceSourceManager(
            this, sessionProvider, transport, owner, aggregates::add, dispatcher, ::epochMillis,
            exclusions ?: FakeExclusions(),
        ) to owner
    }

    private class FakeExclusions(
        private val ids: MutableSet<String> = linkedSetOf(),
    ) : HaPresenceExclusions {
        override val scope = "ha-instance"
        override fun excluded(areaId: String): Set<String> = ids.toSet()
        override fun setIncluded(expectedScope: String, areaId: String, entityId: String, included: Boolean): Boolean {
            if (included) ids.remove(entityId) else ids.add(entityId)
            return true
        }
    }

    private fun kotlinx.coroutines.test.TestScope.exactOwner(
        dispatcher: CoroutineDispatcher,
        transport: FakeExactTransport,
        sessionProvider: HaApiSessionProvider = auth(),
    ) = HaExactEntityStreamOwner(
        scope = this,
        auth = sessionProvider,
        transport = transport,
        workerDispatcher = dispatcher,
    )

    private fun auth() = HaApiSessionProvider { HaApiSession("https://ha.example", "token", owner = OWNER) }

    private fun request(
        enabled: Boolean = true,
        controllerEpoch: Long = 1L,
        discoveryId: String? = null,
    ) = HaPresenceRequest(
        enabled = enabled,
        deviceUid = "device-uid",
        panelId = "panel",
        controllerEpoch = controllerEpoch,
        discoveryId = discoveryId,
    )

    private fun feed(
        current: HaPresenceAggregate,
        generation: Long = current.feedGeneration,
        revision: Long,
        value: HaPresenceValue = HaPresenceValue.OFF,
    ) = HaPresenceFeedSnapshot(
        generation = generation,
        revision = revision,
        sourceIds = current.selectedEntityIds,
        states = current.selectedEntityIds.associateWith { value },
        hydrated = true,
        phase = HaExactEntityStreamPhase.LIVE,
    )

    private class FakeExactConnection : HaExactEntityConnection {
        val messages = Channel<HaExactSocketMessage>(Channel.UNLIMITED)
        override suspend fun receive(): HaExactSocketMessage = messages.receive()
        override suspend fun ping(id: Int) { messages.send(HaExactSocketMessage.Pong(id)) }
        override suspend fun close() { messages.close() }
    }

    private class FakeExactTransport(vararg connections: FakeExactConnection) : HaExactEntityStreamTransport {
        private val connections = ArrayDeque(connections.toList())
        val states = linkedMapOf<String, JSONObject?>()
        val deferredStates = linkedMapOf<String, CompletableDeferred<JSONObject?>>()
        val subscriptions = mutableListOf<Set<String>>()

        override suspend fun subscribe(
            baseUrl: String,
            accessToken: String,
            entityIds: Set<String>,
        ): HaExactEntityConnection {
            subscriptions += entityIds.toSet()
            return connections.removeFirst()
        }

        override suspend fun state(baseUrl: String, accessToken: String, entityId: String): JSONObject? =
            deferredStates[entityId]?.await() ?: states[entityId] ?: state(entityId, "off")
    }

    private class FakePresenceTransport(var sourceCount: Int = 1) : HaPresenceTransport {
        var registryCount = 0
        var panelAreaRegistryCount = 0
        var panelAreaAssigned = true
        var areaId = "room"
        var areaName = "Room"
        var registryFailure = false
        var panelAssistantOnly = false
        var registryAuthFailures = 0
        var includePanelActivity = false
        var includeSupportingActivity = false
        var malformedEntityRegistry = false
        var historyTransportFailure = false
        var emptyHistory = false
        var malformedHistory = false
        var oversizedHistory = false
        val historyEntitySets = mutableListOf<Set<String>>()
        val historyRequests = mutableListOf<Triple<Set<String>, Long, Long>>()

        fun entityIds(): Set<String> = if (sourceCount == 1) setOf(ENTITY)
            else (1..sourceCount).mapTo(linkedSetOf()) { "binary_sensor.motion_$it" }

        override suspend fun registry(baseUrl: String, accessToken: String): HaPresenceRegistrySnapshot {
            registryCount++
            if (registryAuthFailures-- > 0) throw HaAuthenticationException("rejected")
            if (registryFailure) error("registry unavailable")
            val devices = JSONArray().put(panelDevice())
            val entities = JSONArray()
            val states = JSONArray()
            if (includePanelActivity) {
                entities.put(JSONObject().put("ei", SELF).put("di", "panel-device").put("pl", "mqtt"))
                states.put(state(SELF, "off").put("attributes", JSONObject()
                    .put("device_class", "occupancy").put("friendly_name", "Panel proximity")))
            }
            if (includeSupportingActivity) {
                entities.put(JSONObject().put("ei", SUPPORTING).put("ai", "room").put("pl", "bayesian"))
                states.put(state(SUPPORTING, "on").put("attributes", JSONObject()
                    .put("device_class", "occupancy").put("friendly_name", "Room is deserted")))
            }
            entityIds().forEachIndexed { index, id ->
                val deviceId = "motion-device-$index"
                devices.put(device(deviceId, "motion-$index"))
                entities.put(JSONObject().put("ei", id).put("di", deviceId).put("pl", "mqtt"))
                states.put(state(id, "off").put("attributes", JSONObject()
                    .put("device_class", "motion").put("friendly_name", "Motion $index")))
            }
            return HaPresenceRegistrySnapshot(
                JSONObject().put("result", devices),
                JSONObject().put("result", JSONArray().put(JSONObject().put("area_id", areaId).put("name", areaName))),
                if (malformedEntityRegistry) JSONObject().put("result", JSONObject())
                else JSONObject().put("result", JSONObject().put("entities", entities)),
                states,
                panelAssistantProbe(),
            )
        }

        override suspend fun panelAreaRegistry(
            baseUrl: String,
            accessToken: String,
        ): HaPanelAreaRegistrySnapshot {
            panelAreaRegistryCount++
            val area = if (panelAreaAssigned) areaId else ""
            return HaPanelAreaRegistrySnapshot(
                JSONObject().put("result", JSONArray().put(panelDevice().put("area_id", area))),
                JSONObject().put("result", JSONArray().put(
                    JSONObject().put("area_id", areaId).put("name", areaName),
                )),
                panelAssistantProbe(),
            )
        }

        override suspend fun history(
            baseUrl: String,
            accessToken: String,
            entityIds: Set<String>,
            startEpochMs: Long,
            endEpochMs: Long,
        ) = JSONArray().apply {
            historyEntitySets += entityIds.toSet()
            historyRequests += Triple(entityIds.toSet(), startEpochMs, endEpochMs)
            if (historyTransportFailure) error("history unavailable")
            if (emptyHistory) return@apply
            if (malformedHistory) {
                put(JSONObject())
                return@apply
            }
            if (oversizedHistory) {
                put(JSONArray().apply { repeat(20_001) { put(JSONObject()) } })
                return@apply
            }
            entityIds.forEach { id ->
                put(JSONArray()
                    .put(history(id, "off", startEpochMs))
                    .put(history("", "on", startEpochMs + 60_000L))
                    .put(history("", "off", minOf(endEpochMs, startEpochMs + 120_000L))))
            }
        }

        /** The panel's own device: MQTT-registered, or (like every migrated panel) Panel Assistant only. */
        private fun panelDevice() = if (panelAssistantOnly) JSONObject()
            .put("id", "panel-device").put("area_id", areaId)
            .put("identifiers", JSONArray().put(JSONArray().put("panel_assistant").put(PA_ENTRY)))
        else device("panel-device", "ha-paneld-uid-device-uid")

        /** Core's `get_entries` answer for the panel's native proximity entity. */
        private fun panelAssistantProbe(): JSONObject? = if (!panelAssistantOnly) null else JSONObject()
            .put("result", JSONObject().put(SELF, JSONObject()
                .put("platform", "panel_assistant")
                .put("unique_id", "${DID}_proximity")
                .put("config_entry_id", PA_ENTRY)))

        private fun device(id: String, identifier: String) = JSONObject()
            .put("id", id).put("area_id", areaId)
            .put("identifiers", JSONArray().put(JSONArray().put("mqtt").put(identifier)))

        private fun history(entity: String, value: String, at: Long) = JSONObject()
            .apply { if (entity.isNotBlank()) put("entity_id", entity) }
            .put("state", value).put("last_changed", java.time.Instant.ofEpochMilli(at).toString())
    }

    private companion object {
        const val ENTITY = "binary_sensor.room_motion"
        const val SELF = "binary_sensor.panel_proximity"
        const val DID = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"
        const val PA_ENTRY = "01J00000000000000000000CCC"
        const val SUPPORTING = "binary_sensor.room_is_deserted"
        val OWNER = HaAuthSnapshot("https://ha.example", "access", "refresh", 1L, "client").stableOwner()

        fun epochMillis() = 7L * 24L * 60L * 60_000L

        fun state(entityId: String, value: String, observedAt: Long = 1_000L) = JSONObject()
            .put("entity_id", entityId)
            .put("state", value)
            .put("last_updated", java.time.Instant.ofEpochMilli(observedAt).toString())
            .put("attributes", JSONObject())
    }
}
