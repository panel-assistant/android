package io.panelassistant.android.sensors

/** One finished burst of layer-3 echoes. Counts only; no address, no per-echo sample, ever. */
internal data class PathBurst(
    val atMs: Long,
    val sent: Int,
    val received: Int,
    /** Round trips in milliseconds, ascending order not required. Empty when nothing came back. */
    val rttsMs: List<Long>,
) {
    val lost: Int get() = (sent - received).coerceAtLeast(0)
    val lossPercent: Double get() = if (sent == 0) 0.0 else lost * 100.0 / sent
    val clean: Boolean get() = sent > 0 && lost == 0
}

/**
 * Whether the layer-3 probe can be believed at all.
 *
 * This exists because "no echo came back" has two completely different causes and only one of them
 * is a fault. A Home Assistant reached through a reverse proxy, a tunnel, or simply a host that drops
 * ICMP will never answer an echo however perfect the network is, and a panel that reported that as
 * total packet loss would raise a permanent severe warning on a link carrying a live WebSocket. So
 * loss is only counted once the probe has been SEEN to work at least once.
 *
 * The same rule covers the platform gap: if the socket cannot be opened at all — an OEM policy that
 * denies `untrusted_app` an ICMP socket, which the kernel's `ping_group_range` does not settle — the
 * probe reports [UNSUPPORTED] and the panel keeps the WebSocket-derived verdict it shipped with.
 */
/**
 * What raised a layer-3 verdict.
 *
 * The wording a user sees has to match the evidence: "packets are going missing" and "the path is
 * slow" call for different checks at the other end, and reporting one as the other is how a person
 * is sent looking in the wrong place.
 */
internal enum class PathProbeCause { NONE, LOSS, LATENCY }

internal enum class PathProbeAvailability {
    /** No socket could be opened on this platform. Nothing here will ever be measured. */
    UNSUPPORTED,

    /** A socket opened, but nothing has ever answered. Silence is not yet evidence of loss. */
    UNPROVEN,

    /** At least one echo has been answered, so silence now means something. */
    PROVEN,
}

/**
 * Rolling classification over the last [maxBursts] probe bursts.
 *
 * Judged per BURST rather than over a wall-clock window, because the burst interval is adaptive: at
 * a ten-minute cadence a five-minute window would be empty almost always and the verdict would
 * flicker between "degraded" and "no data" while nothing changed. A fixed number of recent bursts
 * ages out cleanly no matter how fast they arrive — recovery is clean bursts pushing bad ones out,
 * which is the same "recovery is not an event" rule the WebSocket monitor uses.
 */
internal class PathProbeHistory(
    private val maxBursts: Int = MAX_BURSTS,
    private val warnLossPercent: Double = WARN_LOSS_PERCENT,
    private val severeLossPercent: Double = SEVERE_LOSS_PERCENT,
    private val severeConsecutiveDead: Int = SEVERE_CONSECUTIVE_DEAD,
    private val warnLatencyMs: Long = WARN_LATENCY_MS,
    private val severeLatencyMs: Long = SEVERE_LATENCY_MS,
) {
    private val bursts = ArrayDeque<PathBurst>()
    private var availability = PathProbeAvailability.UNPROVEN
    private var consecutiveDead = 0

    val state: PathProbeAvailability get() = availability

    /** Mark the platform as unable to probe; nothing further is recorded. */
    fun markUnsupported() {
        availability = PathProbeAvailability.UNSUPPORTED
        bursts.clear()
        consecutiveDead = 0
    }

    fun record(burst: PathBurst) {
        if (availability == PathProbeAvailability.UNSUPPORTED) return
        if (burst.received > 0) availability = PathProbeAvailability.PROVEN
        // A burst where nothing at all came back is only a "dead" burst once the probe has been shown
        // to work; before that it is the ICMP-filtered case and carries no information.
        consecutiveDead = when {
            availability != PathProbeAvailability.PROVEN -> 0
            burst.sent > 0 && burst.received == 0 -> consecutiveDead + 1
            else -> 0
        }
        bursts.addLast(burst)
        while (bursts.size > maxBursts) bursts.removeFirst()
    }

    fun reset() {
        bursts.clear()
        consecutiveDead = 0
        if (availability != PathProbeAvailability.UNSUPPORTED) availability = PathProbeAvailability.UNPROVEN
    }

    /** The verdict, or null when this probe has nothing it is entitled to say. */
    fun severity(): HaNetworkPathSeverity? = verdict()?.severity

    /** What the verdict is and what raised it, or null when the probe may not speak. */
    fun verdict(): Verdict? {
        if (availability != PathProbeAvailability.PROVEN) return null
        if (bursts.isEmpty()) return null
        val sent = bursts.sumOf { it.sent }
        val lost = bursts.sumOf { it.lost }
        if (sent == 0) return null
        val lossPercent = lost * 100.0 / sent
        val rtts = bursts.flatMap { it.rttsMs }
        // Latency is genuine PATH evidence here in a way the WebSocket round trip never was: an echo
        // is answered by the target's kernel, so nothing of the server's own response time or this
        // panel's thread scheduling is in the figure. The same two-event floor as everywhere else
        // keeps a single spike diagnostic rather than alarming.
        val overSevere = rtts.count { it > severeLatencyMs }
        val overWarn = rtts.count { it > warnLatencyMs }
        return when {
            consecutiveDead >= severeConsecutiveDead -> Verdict(HaNetworkPathSeverity.SEVERE, PathProbeCause.LOSS)
            lossPercent > severeLossPercent -> Verdict(HaNetworkPathSeverity.SEVERE, PathProbeCause.LOSS)
            HaNetworkPath.exceedsShare(overSevere, rtts.size) ->
                Verdict(HaNetworkPathSeverity.SEVERE, PathProbeCause.LATENCY)
            lossPercent > warnLossPercent -> Verdict(HaNetworkPathSeverity.WARNING, PathProbeCause.LOSS)
            HaNetworkPath.exceedsShare(overWarn, rtts.size) ->
                Verdict(HaNetworkPathSeverity.WARNING, PathProbeCause.LATENCY)
            else -> Verdict(HaNetworkPathSeverity.HEALTHY, PathProbeCause.NONE)
        }
    }

    /** Aggregates for the machine surfaces. Never an address and never an individual echo. */
    fun aggregate(): Aggregate {
        val sent = bursts.sumOf { it.sent }
        val received = bursts.sumOf { it.received }
        val rtts = bursts.flatMap { it.rttsMs }.sorted()
        return Aggregate(
            availability = availability,
            bursts = bursts.size,
            sent = sent,
            received = received,
            lossPercent = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent,
            p50Ms = HaNetworkPath.nearestRank(rtts, 0.50),
            p95Ms = HaNetworkPath.nearestRank(rtts, 0.95),
            maxMs = rtts.lastOrNull() ?: -1L,
            jitterMs = HaNetworkPath.jitter(bursts.flatMap { it.rttsMs }),
            consecutiveDeadBursts = consecutiveDead,
        )
    }

    /** A layer-3 verdict and the evidence class that raised it. */
    data class Verdict(val severity: HaNetworkPathSeverity, val cause: PathProbeCause)

    data class Aggregate(
        val availability: PathProbeAvailability,
        val bursts: Int,
        val sent: Int,
        val received: Int,
        val lossPercent: Double,
        val p50Ms: Long,
        val p95Ms: Long,
        val maxMs: Long,
        val jitterMs: Long,
        val consecutiveDeadBursts: Int,
    )

    companion object {
        /** How many recent bursts a verdict is judged over. */
        const val MAX_BURSTS = 6

        /** Echoes per burst. Enough for loss and jitter to mean something, few enough to be cheap. */
        const val ECHOES_PER_BURST = 5

        /**
         * Loss thresholds. Deliberately above the level a healthy Wi-Fi link produces: interference
         * shows as sustained loss across bursts, while a single dropped echo in thirty is ordinary.
         */
        const val WARN_LOSS_PERCENT = 5.0
        const val SEVERE_LOSS_PERCENT = 20.0

        /**
         * Layer-3 round-trip lines.
         *
         * These are round trips to the Home Assistant host with no server and no application in
         * them: an echo is answered by the target's kernel. A healthy wired path on a local network
         * answers in a fraction of a millisecond, ordinary Wi-Fi in single-digit milliseconds, a
         * weak or busy wireless link in the low tens, and even a tunnelled remote site typically in
         * the low tens. A hundred milliseconds on such a path is therefore genuinely wrong rather
         * than merely unlucky, and four hundred is far beyond anything a working link produces.
         *
         * A panel on powerline, a congested mesh or a long-haul tunnel may sit above the warning
         * line legitimately. Telling that user their path is slow is the point of the feature rather
         * than a defect in it: every server-backed action on the panel waits for exactly this.
         */
        const val WARN_LATENCY_MS = 100L
        const val SEVERE_LATENCY_MS = 400L

        /** Bursts where NOTHING came back, in a row, before the path is called failing. */
        const val SEVERE_CONSECUTIVE_DEAD = 2
    }
}
