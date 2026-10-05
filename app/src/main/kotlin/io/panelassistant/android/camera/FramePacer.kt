package io.panelassistant.android.camera

/**
 * Admits frames at no more than [fps] per second over any run longer than one interval, from a source
 * ticking faster than that at its own granularity (the sensor's 33 ms). The k-th admission is due at
 * `origin + k * 1000 / fps` milliseconds, computed from the cumulative count rather than by adding a
 * rounded interval, so the long-run rate is exactly the cap with no drift (1000 / 15 is 66.67 ms, and
 * adding 66 every frame would run 1 % fast). A small slop lets a frame that lands just before its due
 * time count rather than waiting a whole sensor frame; a stalled source earns no credit — after a gap
 * the cadence restarts from the next frame instead of bursting to catch up.
 */
class FramePacer(private val fps: Int, private val slopMs: Long = minOf(1_000L / fps / 4, DEFAULT_SLOP_MS)) {
    init {
        require(fps > 0) { "fps must be positive" }
    }

    private var originMs = Long.MIN_VALUE
    private var admitted = 0L

    fun admit(nowMs: Long): Boolean {
        if (originMs == Long.MIN_VALUE) return restart(nowMs)
        val due = originMs + admitted * 1_000L / fps
        if (nowMs < due - slopMs) return false
        if (nowMs - due > 1_000L / fps) return restart(nowMs)
        admitted++
        return true
    }

    private fun restart(nowMs: Long): Boolean {
        originMs = nowMs
        admitted = 1
        return true
    }

    companion object {
        /** Half a sensor frame at 30 fps: enough to absorb the HAL's jitter, never enough to double a frame. */
        const val DEFAULT_SLOP_MS = 16L
    }
}
