package io.panelassistant.android.sensors

import io.panelassistant.android.metrics.RoomClimate

internal const val ROOM_CLIMATE_REFRESH_MS = 5_000L
internal const val ROOM_CLIMATE_STALE_MS = 15_000L

internal fun interface RoomClimateRefreshCancellation {
    fun cancel()
}

/**
 * Lifecycle-owned asynchronous room-climate cache. The source read may block in helper/Shizuku I/O,
 * so it runs only inside the supplied single-worker scheduler. HTTP callers only read [current] and
 * never wait for or initiate I/O. A refresh schedules its successor only after completion, preventing
 * overlap even when one authority attempt is slower than the refresh interval.
 */
internal class AsyncRoomClimateSnapshot(
    private val read: () -> RoomClimate?,
    private val elapsedRealtime: () -> Long,
    private val schedule: (Long, () -> Unit) -> RoomClimateRefreshCancellation,
    private val refreshMs: Long = ROOM_CLIMATE_REFRESH_MS,
    private val staleMs: Long = ROOM_CLIMATE_STALE_MS,
) {
    private data class Sample(val value: RoomClimate?, val sampledAt: Long)

    @Volatile
    private var sample: Sample? = null

    private var running = false
    private var generation = 0L
    private var scheduled: RoomClimateRefreshCancellation? = null

    /** Returns immediately. Unavailable, never-sampled, rolled-back-clock and stale states are null. */
    fun current(): RoomClimate? {
        val captured = sample ?: return null
        val age = elapsedRealtime() - captured.sampledAt
        return captured.value.takeIf { age >= 0L && age <= staleMs }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        generation++
        scheduleRefresh(generation, 0L)
    }

    @Synchronized
    fun stop() {
        running = false
        generation++
        scheduled?.cancel()
        scheduled = null
        sample = null
    }

    @Synchronized
    private fun scheduleRefresh(expectedGeneration: Long, delayMs: Long) {
        if (!running || generation != expectedGeneration) return
        scheduled = schedule(delayMs) {
            val value = runCatching(read).getOrNull()
            synchronized(this) {
                if (!running || generation != expectedGeneration) return@synchronized
                sample = Sample(value, elapsedRealtime())
                scheduled = null
                scheduleRefresh(expectedGeneration, refreshMs)
            }
        }
    }
}
