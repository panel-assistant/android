package io.github.maxlyth.hapaneld.control

/**
 * Detects a crash-*looping* dashboard so the watchdog stops hammering a relaunch that isn't helping.
 *
 * The watchdog relaunches the dashboard app when it dies. If the app crashes on launch (e.g. an
 * incompatible Companion update — 2026.6.5 on PX30/8.1), naive relaunching just restarts the crash
 * forever, flashing the screen and burning CPU. This tracks relaunch times in a sliding window: once
 * [maxRelaunches] happen within [windowMs], it enters a [backoffMs] cool-off during which it refuses
 * further relaunches (so the panel settles on whatever it can show — the admin launcher / a health
 * warning — instead of a relaunch storm). Pure + time-injected, so it's unit-tested without a device.
 */
class CrashLoopTracker(
    private val maxRelaunches: Int = MAX_RELAUNCHES,
    private val windowMs: Long = WINDOW_MS,
    private val backoffMs: Long = BACKOFF_MS,
) {
    private val relaunches = ArrayDeque<Long>()
    private var backoffUntil = 0L

    /** Call when the watchdog wants to relaunch the dead dashboard. Returns true if it should proceed;
     *  false = suppressed because we're crash-looping (in back-off). Records the relaunch when allowed. */
    fun onRelaunchAttempt(now: Long): Boolean {
        if (now < backoffUntil) return false
        while (relaunches.isNotEmpty() && now - relaunches.first() > windowMs) relaunches.removeFirst()
        if (relaunches.size >= maxRelaunches) {           // too many in the window → crash-loop
            backoffUntil = now + backoffMs
            relaunches.clear()
            return false
        }
        relaunches.addLast(now)
        return true
    }

    /** The dashboard came back healthy (foreground) — forget the history so a later, unrelated crash
     *  gets its full retry budget again. */
    fun reset() {
        relaunches.clear()
        backoffUntil = 0L
    }

    companion object {
        const val MAX_RELAUNCHES = 4          // relaunches allowed within the window before backing off
        const val WINDOW_MS = 180_000L        // 3 min sliding window
        const val BACKOFF_MS = 1_800_000L     // 30 min cool-off once a crash-loop is detected
    }
}
