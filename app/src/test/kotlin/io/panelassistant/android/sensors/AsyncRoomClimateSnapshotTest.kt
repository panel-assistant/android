package io.panelassistant.android.sensors

import io.panelassistant.android.metrics.RoomClimate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncRoomClimateSnapshotTest {
    private data class Pending(
        val delayMs: Long,
        val task: () -> Unit,
        var cancelled: Boolean = false,
    )

    private class FakeScheduler {
        val pending = mutableListOf<Pending>()

        fun schedule(delayMs: Long, task: () -> Unit): RoomClimateRefreshCancellation {
            val entry = Pending(delayMs, task)
            pending += entry
            return RoomClimateRefreshCancellation { entry.cancelled = true }
        }

        fun take(): Pending = pending.removeAt(0)
    }

    @Test fun slowHelperNeverBlocksEndpointSnapshotReadsOrStartsOverlappingRefreshes() {
        val scheduler = FakeScheduler()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = AsyncRoomClimateSnapshot(
            read = {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                RoomClimate(23.84, 58.95)
            },
            elapsedRealtime = { 100L },
            schedule = scheduler::schedule,
        )
        cache.start()
        val first = scheduler.take()
        val worker = Executors.newSingleThreadExecutor()
        val endpoint = Executors.newSingleThreadExecutor()
        try {
            val refresh = worker.submit(first.task)
            assertTrue("slow source entered", entered.await(1, TimeUnit.SECONDS))
            assertEquals("no successor exists while source is blocked", 0, scheduler.pending.size)

            val endpointRead = endpoint.submit<RoomClimate?> { cache.current() }
            assertNull("endpoint returns immediately while helper is blocked", endpointRead.get(250, TimeUnit.MILLISECONDS))

            release.countDown()
            refresh.get(1, TimeUnit.SECONDS)
            assertEquals(RoomClimate(23.84, 58.95), cache.current())
            assertEquals(ROOM_CLIMATE_REFRESH_MS, scheduler.pending.single().delayMs)
        } finally {
            release.countDown()
            cache.stop()
            worker.shutdownNow()
            endpoint.shutdownNow()
        }
    }

    @Test fun unavailableRefreshRemainsTruthfulNullAndSchedulesOneSuccessor() {
        val scheduler = FakeScheduler()
        val cache = AsyncRoomClimateSnapshot(
            read = { null },
            elapsedRealtime = { 10L },
            schedule = scheduler::schedule,
        )

        cache.start()
        scheduler.take().task()

        assertNull(cache.current())
        assertEquals(1, scheduler.pending.size)
        assertEquals(ROOM_CLIMATE_REFRESH_MS, scheduler.pending.single().delayMs)
    }

    @Test fun staleOrClockRolledBackSnapshotReturnsNullUntilARefreshCompletes() {
        val scheduler = FakeScheduler()
        var now = 1_000L
        var reading = RoomClimate(23.84, 58.95)
        val cache = AsyncRoomClimateSnapshot(
            read = { reading },
            elapsedRealtime = { now },
            schedule = scheduler::schedule,
            refreshMs = 5_000L,
            staleMs = 15_000L,
        )
        cache.start()
        scheduler.take().task()
        assertEquals(reading, cache.current())

        now = 16_001L
        assertNull("expired helper data is never served", cache.current())
        now = 999L
        assertNull("clock rollback expires the snapshot", cache.current())

        now = 20_000L
        reading = RoomClimate(24.01, 57.5)
        scheduler.take().task()
        assertEquals(reading, cache.current())
    }

    @Test fun stopCancelsRefreshClearsSnapshotAndRejectsLateCompletion() {
        val scheduler = FakeScheduler()
        var now = 1L
        val cache = AsyncRoomClimateSnapshot(
            read = { RoomClimate(23.84, 58.95) },
            elapsedRealtime = { now },
            schedule = scheduler::schedule,
        )
        cache.start()
        scheduler.take().task()
        assertEquals(RoomClimate(23.84, 58.95), cache.current())
        val pending = scheduler.take()

        cache.stop()
        assertTrue(pending.cancelled)
        assertNull(cache.current())
        now++
        pending.task()
        assertNull("stopped generation cannot publish a late sample", cache.current())
    }
}
