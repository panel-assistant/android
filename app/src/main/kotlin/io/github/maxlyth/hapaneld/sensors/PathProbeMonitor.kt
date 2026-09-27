package io.github.maxlyth.hapaneld.sensors

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The layer-3 probe's one owner: what to probe, when, and what the bursts have shown.
 *
 * It deliberately runs no loop of its own. The shared socket already ticks on a fixed cadence to send
 * its WebSocket ping, and that tick is a perfectly good clock to ask "is a burst due?" against — so
 * this adds no thread, no lifecycle and nothing to leak. The caller does the dispatching, because a
 * burst blocks for as long as its echoes take and must never sit on the socket's own probe path.
 *
 * Serialised on one lock in the [HaNetworkPathMonitor] style. Retains counts and round trips only;
 * the target address is held to send to and is never exposed on any surface.
 */
internal class PathProbeMonitor(
    private val source: PathEchoSource,
    /**
     * Publishes each new layer-3 verdict to the one owner of the prominent path warning. A layer-3
     * measurement outranks anything derivable from the socket, so this is what makes the echo probe
     * the path verdict rather than a second opinion beside it.
     */
    private val onVerdict: (HaNetworkPathSeverity?, PathProbeCause) -> Unit = { _, _ -> },
    private val schedule: PathProbeSchedule = PathProbeSchedule(),
    private val history: PathProbeHistory = PathProbeHistory(),
    private val echoesPerBurst: Int = PathProbeHistory.ECHOES_PER_BURST,
    private val perEchoTimeoutMs: Long = PER_ECHO_TIMEOUT_MS,
) {
    private val lock = Any()
    private val bursting = AtomicBoolean(false)

    @Volatile private var target: InetAddress? = null
    private var otherResolved: Boolean? = null
    private var measuring = false

    /**
     * Which authenticated session the current evidence belongs to.
     *
     * A burst outlives the moment it was claimed in: it blocks for as long as its echoes take, and
     * the socket can be torn down and re-established underneath it. Without an identity to check on
     * the way back, a burst measured against a dead route lands in a successor session's history.
     */
    private var generation = 0L

    /**
     * The address the shared socket actually connected on, as reported by the transport.
     *
     * Taken from the live connection rather than resolved here, so the probe always measures the path
     * the dashboard is using even when the two families disagree about which one works.
     */
    fun onRouteConnected(address: InetAddress, resolvedOtherFamily: Boolean? = null) = synchronized(lock) {
        // A replacement connection owns a new route generation even when DNS selected the same
        // address. Let an old silence burst finish, but never attribute its result to the new socket.
        if (target != null) generation++
        target = address
        otherResolved = resolvedOtherFamily
    }

    /** Measurement follows the authenticated socket, exactly as the WebSocket monitor's does. */
    fun onSocketState(state: HaSocketState) = synchronized(lock) {
        when (state) {
            HaSocketState.LIVE -> measuring = true
            HaSocketState.CONNECTING -> Unit
            HaSocketState.STOPPED -> {
                measuring = false
                schedule.reset()
                history.reset()
                target = null
                otherResolved = null
                // Any burst still in flight belongs to the session that just ended; advancing the
                // generation is what makes its result discardable when it returns.
                generation++
                publishVerdict()
            }
        }
    }

    /**
     * A silent pong timeout on the shared socket: bring the next burst forward.
     *
     * This is the two probes cooperating. A pong that never arrived is precisely the observation the
     * layer-3 probe exists to attribute — a path that lost the packet, or a server that never sent
     * one — so it is the one moment where an immediate answer is worth the echoes.
     */
    fun onSocketSilence(nowMs: Long) = synchronized(lock) {
        schedule.escalate(nowMs)
    }

    /**
     * Claim the right to run a burst now, or null when one is not due.
     *
     * Returns the target so the caller can run [runBurst] off the calling thread. A burst already in
     * flight always wins: bursts must never overlap, or their echoes race for the same sequence
     * numbers and the loss figure becomes fiction.
     */
    fun claimBurst(nowMs: Long): Claim? = synchronized(lock) {
        val address = target ?: return null
        if (!measuring) return null
        if (!schedule.due(nowMs)) return null
        if (!bursting.compareAndSet(false, true)) return null
        schedule.started(nowMs)
        Claim(address, generation)
    }

    /** One claimed burst, carrying the session it belongs to. */
    data class Claim(val target: InetAddress, val generation: Long)

    /**
     * Run the claimed burst. Blocking; the caller must already be off the socket's probe path.
     *
     * Fail-soft by construction: nothing thrown here may escape into the dispatching coroutine, and
     * a result whose session has ended is discarded rather than recorded.
     */
    fun runBurst(claim: Claim, nowMs: () -> Long) {
        try {
            val burst = source.burst(claim.target, echoesPerBurst, perEchoTimeoutMs, nowMs)
            synchronized(lock) {
                // The socket this was measured for is gone: the evidence describes a route the panel
                // is no longer using, so it is dropped rather than attributed to its successor.
                if (claim.generation != generation) return@synchronized
                if (burst == null) {
                    // The platform withholds the capability. Nothing here will ever be measured, and
                    // the panel keeps the verdict its WebSocket can support.
                    history.markUnsupported()
                    publishVerdict()
                } else {
                    history.record(burst)
                    schedule.completed(nowMs(), clean = burst.clean)
                }
                publishVerdict()
            }
        } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // A diagnostic must never take the process with it.
        } finally {
            bursting.set(false)
        }
    }

    /** Hand the current layer-3 verdict to the prominent owner. Called under the lock. */
    private fun publishVerdict() {
        val verdict = history.verdict()
        onVerdict(verdict?.severity, verdict?.cause ?: PathProbeCause.NONE)
    }

    /** One atomic reading for the surfaces, or null when there is nothing to describe. */
    fun snapshot(nowMs: Long): Snapshot? = synchronized(lock) {
        if (!measuring && history.state != PathProbeAvailability.UNSUPPORTED) return null
        val aggregate = history.aggregate()
        Snapshot(
            family = target?.let { if (it is java.net.Inet6Address) "ipv6" else "ipv4" },
            otherResolved = otherResolved,
            availability = aggregate.availability,
            severity = history.severity(),
            bursts = aggregate.bursts,
            sent = aggregate.sent,
            received = aggregate.received,
            lossPercent = aggregate.lossPercent,
            p50Ms = aggregate.p50Ms,
            p95Ms = aggregate.p95Ms,
            maxMs = aggregate.maxMs,
            jitterMs = aggregate.jitterMs,
            consecutiveDeadBursts = aggregate.consecutiveDeadBursts,
            intervalMs = schedule.currentIntervalMs,
            lastBurstAgeMs = schedule.lastBurstAgeMs(nowMs),
        )
    }

    /**
     * The one tuple a surface may render.
     *
     * [severity] is null whenever the probe is not entitled to a verdict — unsupported, or not yet
     * proven — and a surface must render that as "not measured", never as health.
     */
    data class Snapshot(
        /** The family the socket actually connected on, or null when no route is held. */
        val family: String?,
        /** Whether the other family appeared in this connection's DNS result; null if unknown. */
        val otherResolved: Boolean?,
        val availability: PathProbeAvailability,
        val severity: HaNetworkPathSeverity?,
        val bursts: Int,
        val sent: Int,
        val received: Int,
        val lossPercent: Double,
        val p50Ms: Long,
        val p95Ms: Long,
        val maxMs: Long,
        val jitterMs: Long,
        val consecutiveDeadBursts: Int,
        val intervalMs: Long,
        val lastBurstAgeMs: Long,
    )

    companion object {
        /** Per echo. A LAN answers in single-digit milliseconds; a second is already a lost packet. */
        const val PER_ECHO_TIMEOUT_MS = 1_000L
    }
}
