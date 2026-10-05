package io.panelassistant.android.sensors

/**
 * When the next layer-3 probe burst is due.
 *
 * The panel does not probe continuously. A path that has demonstrated itself reliable is asked less
 * and less often, because on the overwhelming majority of panels the answer never changes and every
 * echo is a packet somebody's network carried for nothing. Anything suspicious snaps the interval
 * straight back to the floor, so the cost of being wrong about "reliable" is one slow burst rather
 * than a missed outage.
 *
 * Pure and clock-injected in the [HaNetworkPath] style: every decision takes its own monotonic
 * millisecond, so nothing here can be satisfied or defeated by real elapsed time.
 *
 * Escalation is cheap and de-escalation is deliberate: ONE unclean burst returns to [MIN_INTERVAL_MS]
 * immediately, while widening requires [CLEAN_BURSTS_TO_WIDEN] consecutive clean bursts and then only
 * doubles. That asymmetry is the whole policy — it costs a handful of packets to confirm a suspicion
 * and it costs a sustained clean history to earn quiet.
 */
internal class PathProbeSchedule(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val maxIntervalMs: Long = MAX_INTERVAL_MS,
    private val cleanBurstsToWiden: Int = CLEAN_BURSTS_TO_WIDEN,
) {
    private var intervalMs = minIntervalMs
    private var consecutiveClean = 0
    private var nextDueAtMs = Long.MIN_VALUE
    private var lastBurstAtMs = -1L

    /** The interval currently in force, for reporting. Never a promise about the next burst alone. */
    val currentIntervalMs: Long get() = intervalMs

    /** How many clean bursts have accumulated toward the next widening. */
    val cleanRun: Int get() = consecutiveClean

    /**
     * Whether a burst should start now. The FIRST call always says yes: a panel that has just started
     * measuring knows nothing about its path, and waiting a full interval to find out is the wrong
     * trade in the one situation where the user is most likely to be watching.
     */
    fun due(nowMs: Long): Boolean = nowMs >= nextDueAtMs

    /** Record that a burst has started, so [due] stops firing until the next interval elapses. */
    fun started(nowMs: Long) {
        lastBurstAtMs = nowMs
        nextDueAtMs = nowMs + intervalMs
    }

    /**
     * Fold one finished burst into the cadence.
     *
     * [clean] means the burst saw no loss and nothing else worth a second look; the caller decides,
     * because "suspicious" is a classifier judgement rather than a scheduling one.
     */
    fun completed(nowMs: Long, clean: Boolean) {
        if (!clean) {
            // Snap to the floor and forget the clean history: a path that has just misbehaved has to
            // earn quiet again from the beginning.
            intervalMs = minIntervalMs
            consecutiveClean = 0
            nextDueAtMs = nowMs + intervalMs
            return
        }
        consecutiveClean++
        if (consecutiveClean >= cleanBurstsToWiden) {
            consecutiveClean = 0
            intervalMs = (intervalMs * 2).coerceAtMost(maxIntervalMs)
        }
        nextDueAtMs = nowMs + intervalMs
    }

    /**
     * Bring the next burst forward to now because something else saw trouble.
     *
     * The WebSocket probe and this one cooperate: a silent pong timeout on the shared socket is the
     * one moment where an immediate layer-3 answer is worth the packets, because it is exactly the
     * observation this probe exists to attribute — a lost path, or a stalled server. It does NOT
     * change the interval, since nothing about the path has been measured yet.
     */
    fun escalate(nowMs: Long) {
        nextDueAtMs = minOf(nextDueAtMs, nowMs)
    }

    /** Forget everything: no socket, so no path to describe and no cadence to keep. */
    fun reset() {
        intervalMs = minIntervalMs
        consecutiveClean = 0
        nextDueAtMs = Long.MIN_VALUE
        lastBurstAtMs = -1L
    }

    /** How long ago the last burst started, or `-1` when none has. */
    fun lastBurstAgeMs(nowMs: Long): Long =
        if (lastBurstAtMs < 0L) -1L else (nowMs - lastBurstAtMs).coerceAtLeast(0L)

    companion object {
        /**
         * The floor, used while anything is suspicious. Short enough that a real outage is described
         * within a minute or so of starting, long enough that a burst is never a flood.
         */
        const val MIN_INTERVAL_MS = 30_000L

        /**
         * The ceiling for a path that keeps proving itself. Ten minutes means a settled panel spends
         * a handful of packets an hour on this, which is the point of the duty cycle.
         */
        const val MAX_INTERVAL_MS = 10L * 60_000L

        /** Consecutive clean bursts required before the interval doubles. */
        const val CLEAN_BURSTS_TO_WIDEN = 3
    }
}
