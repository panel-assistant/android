package io.panelassistant.android.dashboard

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Locks the single-owner drain-worker contract that the entity-telemetry and dashboard-performance
 * lanes previously each hand-rolled: launch-if-idle, at-most-one active worker, re-spawn only while
 * open and pending, and close cancels without re-spawn. This is the union of the guarantees both
 * bespoke `admit`/`drain` finally blocks provided before consolidation.
 */
class EntityTelemetryPipelineTest {

    @Test fun admitLaunchesAtMostOneWorkerAndDoesNotRespawnWhenNotPending() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val entries = AtomicInteger(0)
        val opened = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val pipeline = EntityTelemetryPipeline(
            scope = this,
            accumulator = Unit,
            hasPending = { false },
            dispatcher = dispatcher,
            drain = {
                entries.incrementAndGet()
                gate.await()
            },
        )

        // Three producers arrive before the scheduler runs the worker body.
        repeat(3) { pipeline.admit(onOpen = { opened.incrementAndGet() }, onClosed = {}) }
        runCurrent()
        assertEquals("all three producers admitted", 3, opened.get())
        assertEquals("exactly one worker despite three admits", 1, entries.get())

        // A further producer while the worker is parked must not start a second worker.
        pipeline.admit(onOpen = { opened.incrementAndGet() }, onClosed = {})
        runCurrent()
        assertEquals(4, opened.get())
        assertEquals("no second worker while one is active", 1, entries.get())

        // Release the worker; hasPending is false, so no replacement is spawned.
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("no re-spawn when the accumulator is empty", 1, entries.get())
    }

    @Test fun workerRespawnsExactlyWhileTheAccumulatorHoldsPendingWork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val entries = AtomicInteger(0)
        val queue = ArrayDeque<Int>()
        val pipeline = EntityTelemetryPipeline(
            scope = this,
            accumulator = queue,
            hasPending = { synchronized(it) { it.isNotEmpty() } },
            dispatcher = dispatcher,
            drain = {
                entries.incrementAndGet()
                // Consume a single item per pass, mirroring a lane that leaves residual backlog.
                synchronized(queue) { if (queue.isNotEmpty()) queue.removeFirst() }
            },
        )

        pipeline.admit(onOpen = { synchronized(it) { it.add(1) } }, onClosed = {})
        pipeline.admit(onOpen = { synchronized(it) { it.add(2) } }, onClosed = {})
        advanceUntilIdle()

        // First worker drains one item; the finally re-spawn drains the residual second item, then
        // stops because the accumulator is empty. Two worker generations, no more.
        assertEquals("re-spawned once to clear the backlog", 2, entries.get())
        assertTrue("accumulator fully drained", synchronized(queue) { queue.isEmpty() })
    }

    @Test fun closeCancelsTheWorkerRunsCleanupAndBlocksFurtherWork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val entries = AtomicInteger(0)
        val opened = AtomicInteger(0)
        val closedRouted = AtomicInteger(0)
        val stillPending = AtomicBoolean(true)
        val gate = CompletableDeferred<Unit>()
        val pipeline = EntityTelemetryPipeline(
            scope = this,
            accumulator = "acc",
            hasPending = { stillPending.get() },
            dispatcher = dispatcher,
            drain = {
                entries.incrementAndGet()
                gate.await()
            },
        )

        pipeline.admit(onOpen = { opened.incrementAndGet() }, onClosed = { closedRouted.incrementAndGet() })
        runCurrent()
        assertEquals(1, entries.get())

        // close runs the caller cleanup under the monitor and returns its result.
        val cleanupResult = pipeline.close { "$it-closed" }
        assertEquals("acc-closed", cleanupResult)

        // The in-flight worker is cancelled; despite hasPending==true it must never re-spawn.
        advanceUntilIdle()
        assertEquals("closed pipeline never re-spawns", 1, entries.get())

        // Admissions after close route to onClosed and start no worker.
        pipeline.admit(onOpen = { opened.incrementAndGet() }, onClosed = { closedRouted.incrementAndGet() })
        runCurrent()
        advanceUntilIdle()
        assertEquals("no further onOpen after close", 1, opened.get())
        assertEquals("admission routed to onClosed", 1, closedRouted.get())
        assertEquals("no worker after close", 1, entries.get())
        assertFalse(gate.isCompleted)
    }
}
