package io.github.maxlyth.hapaneld.camera

import java.util.concurrent.CompletableFuture

/** Who is holding a lease. Stream and snapshot are subscribers alike; only the encoder cares which. */
enum class LeaseKind { SNAPSHOT, STREAM }

/** What the first stream lease binds the encoder to. Later stream leases join it rather than re-encoding. */
data class StreamBinding(val fps: Int, val kbps: Int)

/**
 * When a captured frame is good enough to answer a snapshot.
 *
 * The camera runs auto-exposure from the moment it opens, and the first frames off a cold sensor are
 * taken before it has done anything. Answering with the first frame that arrives therefore returns a
 * badly under-exposed picture whenever the camera was not already streaming — measured on a WF1589T on
 * 2026-08-31 as a bimodal mean luma of about 21 against about 69 on an unchanging scene, with the dark
 * result in seven of ten back-to-back snapshots. The video stream never showed it because it runs
 * continuously and settles within the first second.
 *
 * So a snapshot waits for the device to report converged exposure — but only for a bounded time, because
 * a device that never reports convergence must still produce a picture rather than a timeout. That
 * fallback is the whole reason this is a budget and not a precondition.
 */
object SnapshotExposure {
    /**
     * How long a snapshot may wait for auto-exposure. Comfortably inside the snapshot request timeout,
     * and at the paced frame rate it is enough frames for a sensor to settle: about 18 at 15 fps.
     */
    const val SETTLE_BUDGET_MS = 1_200L

    /**
     * Whether a frame may satisfy a waiter that has been waiting [waitedMs]. A converged frame always
     * may; an unconverged one only once the budget is spent.
     */
    fun admits(exposureSettled: Boolean, waitedMs: Long, budgetMs: Long = SETTLE_BUDGET_MS): Boolean = when {
        // A settled sensor has nothing left to wait for.
        exposureSettled -> true
        // Otherwise the budget decides, and spending it is what guarantees a picture on a device that
        // never reports its exposure state at all.
        else -> waitedMs >= budgetMs
    }
}

/**
 * How the last answered snapshot got its frame.
 *
 * The gate is a budget, not a precondition, so a device that never reports `CONTROL_AE_STATE` still
 * returns a picture — and from the outside that picture is indistinguishable from a properly converged
 * one, which is exactly what somebody diagnosing a dark still needs told apart. So the session records
 * which path answered, and the projection carries it.
 */
enum class SnapshotExposureOutcome(val wire: String) {
    /** No snapshot has been answered yet, so nothing is claimed either way. */
    NONE("none"),
    /** The sensor reported converged, locked or flash-required exposure before the frame was handed over. */
    CONVERGED("converged"),
    /** The budget was spent without the sensor reporting convergence, and the frame was handed over anyway. */
    BUDGET("budget"),
}

/**
 * The processing knobs these panels actually expose, and how to choose them.
 *
 * Kept pure because the choice is a policy, and because what the hardware offers differs per board:
 * enumerated on both camera panels on 2026-08-31, `availableCapabilities` is a single byte —
 * `BACKWARD_COMPATIBLE` and nothing else — so there is no `MANUAL_SENSOR`, no `BURST_CAPTURE` and no
 * `RAW`, which is why multi-frame HDR is not merely unimplemented but unavailable, and why the vendor
 * extensions CameraX would surface do not exist here either. What is left is worth using.
 */
object CameraProcessing {
    /**
     * Noise reduction and edge enhancement for a session, given what the device offers.
     *
     * There is one repeating request serving both the snapshot and the stream, so this is decided when
     * the session opens rather than per frame. A session opened for a still can afford the expensive
     * pipeline — it produces one frame a person looks at. A session serving a stream cannot: the same
     * cost lands on every frame, fifteen times a second, on a panel already rendering a dashboard.
     *
     * Returns null when the mode is unavailable, so an unsupported device is left at its own default
     * rather than handed a value it never advertised.
     */
    fun qualityMode(forStream: Boolean, available: IntArray?, fast: Int, highQuality: Int): Int? {
        val modes = available?.toSet().orEmpty()
        val wanted = if (forStream) fast else highQuality
        return wanted.takeIf { it in modes }
    }

    /**
     * Exposure bias in device steps, clamped to what the device supports.
     *
     * Both panels report a range of -6..+6 at 1/3 EV, i.e. plus or minus two stops. The setting is in EV
     * so it means the same thing on a board with a different step size, and a device advertising no
     * range at all gets no bias rather than an invented one.
     */
    fun exposureSteps(requestedEv: Double, lower: Int, upper: Int, stepNumerator: Int, stepDenominator: Int): Int {
        // No guard for an empty range: coercing into 0..0 already yields no bias, and a redundant check
        // that cannot change an answer is worse than none — it reads as protection that is not there.
        // The step guard is load-bearing, because a zero denominator would divide by zero.
        if (stepNumerator <= 0 || stepDenominator <= 0) return 0
        val evPerStep = stepNumerator.toDouble() / stepDenominator.toDouble()
        val steps = Math.round(requestedEv / evPerStep).toInt()
        return steps.coerceIn(lower, upper)
    }
}

/** A pending snapshot, with the moment it started waiting so the settle budget can be spent honestly. */
data class SnapshotWaiter(val future: CompletableFuture<ByteArray?>, val addedAtMs: Long)

/**
 * The camera session's ownership state, with no Android in it. `CameraSessionOwner` is the adapter
 * that drives the device; every enable/open/lease/close decision is made here so each interleaving is a
 * unit test rather than something a reviewer has to trace by hand.
 *
 * Two identities, deliberately distinct:
 *
 * - The **session [generation]** is what a lease belongs to. It advances when a session starts from
 *   idle and when it ends for any reason, and a lease from an ended session can never touch a later
 *   one.
 * - The **[attempt]** is what hardware and device callbacks belong to. Every `openCamera` — the first
 *   one and every reopen — is its own attempt, so a callback from a superseded attempt is recognised by
 *   its identity and can only ever release that attempt's own hardware, never a newer attempt's.
 *
 * Phase changes happen here, synchronously, at the moment the decision is made; hardware release trails
 * them asynchronously and is validated against the attempt it belongs to. That is why a disable or a
 * last-lease release leaves nothing joinable behind: the phase is already idle when the next acquire
 * arrives, and it starts a new attempt rather than joining a cancelled one.
 *
 * Callers waiting for an open register a future here, so a disable, a stop or the last lease leaving
 * settles them at once instead of leaving them to time out. Failure memory survives a session: a
 * camera that keeps refusing is backed off between polls and declared degraded at the ceiling, so a
 * poller cannot turn a broken camera into an unbounded retry loop. The failure count is reset only by a
 * delivered frame, never by a successful open on its own, so a fault that recurs after every open still
 * climbs the ladder to the ceiling instead of cycling the device for ever.
 *
 * The encoder is a property of the leases, not of the hardware: it is wanted exactly while a stream
 * lease exists ([encoderWanted]), its parameters are bound by the first stream lease and released by
 * the last ([streamBinding]), and a reopen keeps both because the subscribers are the same. An encoder
 * that refuses or fails holds stream leases off for the policy's backoff without touching snapshots.
 *
 * Not thread-safe: the owner serialises every call under one lock.
 */
class CameraSessionState(private val policy: () -> CameraSessionPolicy) {

    enum class Phase { IDLE, OPENING, LIVE, DEGRADED, STOPPING }

    var phase: Phase = Phase.IDLE
        private set
    var generation: Long = 0L
        private set
    var attempt: Long = 0L
        private set
    /** The attempt whose callbacks are currently valid, or null when no attempt is in flight or live. */
    var currentAttempt: Long? = null
        private set
    var consecutiveFailures: Int = 0
        private set
    var lastFrameAtMs: Long? = null
        private set
    var openedAtMs: Long = 0L
        private set
    /** No new attempt before this instant; the backoff a poller must respect between failed opens. */
    var retryNotBeforeMs: Long = 0L
        private set
    /** Bound by the first stream lease of a session; null while no stream lease is held. */
    var streamBinding: StreamBinding? = null
        private set
    /** Stream leases are refused until this time after the encoder refused or failed; never bounds a snapshot. */
    var encoderHoldUntilMs: Long? = null
        private set

    private val leases = LinkedHashMap<Long, LeaseKind>()
    private var nextLease = 1L
    private val frameWaiters = ArrayList<SnapshotWaiter>()
    private val openWaiters = ArrayList<CompletableFuture<CameraRefusal?>>()
    private var pendingFault: CameraFault? = null

    val clients: Int get() = leases.size
    val streamClients: Int get() = leases.values.count { it == LeaseKind.STREAM }
    /** The encoder runs exactly while a stream lease exists on a live session. */
    val encoderWanted: Boolean get() = streamClients > 0

    sealed interface Admission {
        data class Refused(val reason: CameraRefusal) : Admission
        /**
         * The session is open or opening; the caller holds [lease] and, if opening, waits on [awaitOpen].
         * [startEncoder] is true when this is the first stream lease on an already-live session, so the
         * owner must start the encoder now; a lease joining mid-open starts it with the open.
         */
        data class Join(val lease: Long, val startEncoder: Boolean) : Admission
        /** The caller's lease is the first; the owner must start attempt [attempt]. */
        data class Open(val lease: Long, val attempt: Long) : Admission
    }

    /**
     * [gate] is the classified refusal from the static gates, or null when they all pass. A stream lease
     * carries the [binding] it asks for; only the first stream lease of a session binds the encoder.
     */
    fun acquire(gate: CameraRefusal?, nowMs: Long, kind: LeaseKind = LeaseKind.SNAPSHOT, binding: StreamBinding? = null): Admission {
        if (phase == Phase.STOPPING) return Admission.Refused(CameraRefusal.STOPPING)
        if (gate != null) return Admission.Refused(gate)
        require(kind != LeaseKind.STREAM || binding != null) { "a stream lease must carry a binding" }
        // One encoding of what this session's own failure memory refuses, shared with [retainedRefusal]
        // so a caller asking what would happen and a caller making it happen cannot drift apart.
        blockedBy(nowMs, kind)?.let { return Admission.Refused(it) }
        // Reached only when the hold is absent or spent, so clearing it here is the same store the
        // guarded version made; a snapshot lease still leaves a live hold alone.
        if (kind == LeaseKind.STREAM) encoderHoldUntilMs = null
        val firstStream = kind == LeaseKind.STREAM && streamClients == 0
        return when (phase) {
            Phase.LIVE -> Admission.Join(newLease(kind, firstStream, binding), startEncoder = firstStream)
            Phase.OPENING -> Admission.Join(newLease(kind, firstStream, binding), startEncoder = false)
            Phase.IDLE, Phase.DEGRADED -> {
                // The backoff was already honoured by [blockedBy] above, which is the only place that
                // decides it: a second check here could not change the answer, and a guard that reads
                // as protection it is not is worse than none.
                if (phase == Phase.DEGRADED) consecutiveFailures = 0
                val lease = newLease(kind, firstStream, binding)
                phase = Phase.OPENING
                generation++
                beginAttempt(nowMs)
                Admission.Open(lease, attempt)
            }
            Phase.STOPPING -> error("unreachable")
        }
    }

    /**
     * What this session's retained failure memory refuses a lease of [kind] right now, or null when
     * nothing of its own stands in the way. The static gates are the owner's and are not consulted here.
     *
     * Both blockers outlive the session that earned them, which is the whole point: [encoderHoldUntilMs]
     * keeps stream leases off after the encoder refused, and [retryNotBeforeMs] backs off a poller that
     * would otherwise cycle a broken camera. Neither is cleared by [endSession], so both survive a
     * disable — the reason the master switch may not report a clear camera on the strength of the switch
     * alone.
     */
    private fun blockedBy(nowMs: Long, kind: LeaseKind): CameraRefusal? = when {
        kind == LeaseKind.STREAM && encoderHoldUntilMs?.let { nowMs < it } == true -> CameraRefusal.STREAM_ENCODER
        (phase == Phase.IDLE || phase == Phase.DEGRADED) && nowMs < retryNotBeforeMs -> CameraRefusal.FAILED
        else -> null
    }

    /**
     * The refusal that still stands for a lease of [kind], for a caller deciding what to *say* rather
     * than what to do. [CameraOutcome.onEnable] is that caller: the master switch may clear its own
     * refusal, and this is how it learns whether clearing it would report a camera that is in fact
     * still refusing.
     *
     * It is [blockedBy] plus one deliberate difference: a degraded session is reported as refusing even
     * once its backoff has expired, when a real acquire would admit and try again. That asymmetry is
     * intended. `DEGRADED` is a failure the panel reached after the whole retry ladder and it stays
     * visible until something actually succeeds, so the switch cannot present a camera that gave up as
     * a clear one; `state=degraded outcome=ok` would be a contradiction on its face. The recovery is
     * unaffected, because recovery is an acquire, not a status read.
     */
    fun retainedRefusal(nowMs: Long, kind: LeaseKind): CameraRefusal? = when {
        phase == Phase.STOPPING -> CameraRefusal.STOPPING
        else -> blockedBy(nowMs, kind) ?: if (phase == Phase.DEGRADED) CameraRefusal.FAILED else null
    }

    private fun newLease(kind: LeaseKind, firstStream: Boolean, binding: StreamBinding?): Long {
        val lease = nextLease++
        leases[lease] = kind
        if (firstStream) streamBinding = binding
        return lease
    }

    private fun beginAttempt(nowMs: Long) {
        attempt++
        currentAttempt = attempt
        openedAtMs = nowMs
        lastFrameAtMs = null
        pendingFault = null
    }

    /** A caller that must wait for the in-flight open registers here; settled by every outcome. */
    fun awaitOpen(): CompletableFuture<CameraRefusal?>? {
        if (phase != Phase.OPENING) return null
        return CompletableFuture<CameraRefusal?>().also { openWaiters += it }
    }

    fun isCurrent(attemptId: Long): Boolean = currentAttempt == attemptId

    /**
     * True when [attemptId] is the live attempt and the session is now serving; read [encoderWanted]
     * next. The failure count is NOT reset here — only a delivered frame proves the session works.
     */
    fun openSucceeded(attemptId: Long): Boolean {
        if (!isCurrent(attemptId) || phase != Phase.OPENING) return false
        phase = Phase.LIVE
        retryNotBeforeMs = 0L
        settleOpen(null)
        return true
    }

    /** The encoder refused or failed: hold stream leases off for the policy's backoff. The session itself is untouched. */
    fun encoderFailed(nowMs: Long) {
        encoderHoldUntilMs = nowMs + policy().encoderHoldMs
    }

    sealed interface Failure {
        /** A superseded attempt; the owner releases only that attempt's own hardware. */
        data object Ignored : Failure
        /** Nobody is waiting any more; the session has ended and the owner releases the attempt. */
        data object Close : Failure
        data class Reopen(val afterMs: Long, val attempt: Int) : Failure
        data class Degrade(val attempt: Int) : Failure
    }

    /**
     * Attempt [attemptId] failed with [fault]. Waiting callers learn the refusal now; the ladder decides
     * what the session does next, and failure memory outlives the session so the next acquire is
     * backed off or refused as degraded rather than retrying immediately.
     */
    fun openFailed(attemptId: Long, fault: CameraFault, refusal: CameraRefusal, nowMs: Long): Failure {
        if (!isCurrent(attemptId)) return Failure.Ignored
        consecutiveFailures++
        settleOpen(refusal)
        val decision = policy().onFailure(fault, consecutiveFailures)
        if (leases.isEmpty()) {
            endSession(Phase.IDLE)
            retryNotBeforeMs = when (decision) {
                is CameraSessionPolicy.Decision.Reopen -> nowMs + decision.afterMs
                is CameraSessionPolicy.Decision.Degrade -> nowMs + policy().maxBackoffMs
                else -> 0L
            }
            if (decision is CameraSessionPolicy.Decision.Degrade) phase = Phase.DEGRADED
            return Failure.Close
        }
        return when (decision) {
            is CameraSessionPolicy.Decision.Degrade -> {
                endSession(Phase.DEGRADED)
                retryNotBeforeMs = nowMs + policy().maxBackoffMs
                Failure.Degrade(decision.attempt)
            }
            is CameraSessionPolicy.Decision.Reopen -> {
                // The session and its leases live on; the next attempt gets its own identity when the
                // owner fires it, so this attempt's late callbacks cannot touch it. The backoff is also
                // recorded now: if the waiting caller leaves before the reopen fires, the next poll
                // must still wait it out rather than retry at once.
                currentAttempt = null
                retryNotBeforeMs = nowMs + decision.afterMs
                Failure.Reopen(decision.afterMs, decision.attempt)
            }
            else -> error("onFailure never continues or closes")
        }
    }

    /** The owner is firing a reopen decided earlier: a fresh attempt with a fresh first-frame grace. */
    fun reopenAttempt(nowMs: Long): Long? {
        if (phase != Phase.OPENING) return null
        beginAttempt(nowMs)
        return attempt
    }

    sealed interface Release {
        /** Other subscribers remain, or the lease was already gone; nothing changes for the hardware. */
        data object None : Release
        /** The last stream lease left but a snapshot lease still holds the session: stop only the encoder. */
        data object StopEncoder : Release
        /** The last lease is gone: the session has ended and the caller must release the attempt's hardware. */
        data object Close : Release
    }

    fun release(lease: Long): Release {
        val kind = leases.remove(lease) ?: return Release.None
        if (leases.isEmpty()) {
            streamBinding = null
            if (phase == Phase.IDLE || phase == Phase.STOPPING) return Release.None
            endSession(Phase.IDLE)
            return Release.Close
        }
        if (kind == LeaseKind.STREAM && streamClients == 0) {
            streamBinding = null
            return Release.StopEncoder
        }
        return Release.None
    }

    /** The master switch turned off. True when the owner must release the current attempt's hardware. */
    fun disable(): Boolean = endNow()

    /**
     * End an opening or live session now, to idle, settling everyone. The watchdog uses this for a
     * close it decided itself; it is the same ending as [disable] because the effect is the same, and
     * there is deliberately no way to end a session without going through it.
     */
    fun endNow(): Boolean {
        if (phase != Phase.OPENING && phase != Phase.LIVE) return false
        endSession(Phase.IDLE)
        return true
    }

    /** Service teardown. True when an attempt was holding hardware. */
    fun stopping(): Boolean {
        val held = phase == Phase.OPENING || phase == Phase.LIVE
        endSession(Phase.STOPPING)
        return held
    }

    /** Synchronous end of the session: phase, generation, leases, the stream binding and every waiter, all at once. */
    private fun endSession(next: Phase) {
        phase = next
        generation++
        currentAttempt = null
        leases.clear()
        streamBinding = null
        settleOpen(if (next == Phase.STOPPING) CameraRefusal.STOPPING else CameraRefusal.DISABLED)
        val drained = frameWaiters.toList()
        frameWaiters.clear()
        drained.forEach { it.future.complete(null) }
        pendingFault = null
    }

    private fun settleOpen(refusal: CameraRefusal?) {
        val pending = openWaiters.toList()
        openWaiters.clear()
        pending.forEach { it.complete(refusal) }
    }

    /**
     * A frame arrived for [attemptId]. Null means the frame is stale and must be dropped; otherwise the
     * list is the snapshot waiters to satisfy — empty when nobody is waiting for a JPEG, which is the
     * ordinary case while only the encoder is consuming.
     */
    fun frame(attemptId: Long, nowMs: Long, exposureSettled: Boolean = true): List<SnapshotWaiter>? {
        if (!isCurrent(attemptId) || phase != Phase.LIVE) return null
        lastFrameAtMs = nowMs
        consecutiveFailures = 0
        // Liveness is recorded above for every frame, converged or not: a session producing frames the
        // exposure gate is holding is still alive, and the watchdog must not read it as starved.
        val ready = frameWaiters.filter { SnapshotExposure.admits(exposureSettled, nowMs - it.addedAtMs) }
        frameWaiters.removeAll(ready.toSet())
        if (ready.isNotEmpty()) {
            // Only a frame that actually answered a waiter says anything about a SNAPSHOT's exposure. The
            // frames delivered while nobody is waiting belong to the stream, which settles within its first
            // second, and recording those would report a converged stream as a converged snapshot.
            lastSnapshotExposure =
                if (exposureSettled) SnapshotExposureOutcome.CONVERGED else SnapshotExposureOutcome.BUDGET
            if (!exposureSettled) snapshotExposureFallbacks += ready.size
        }
        return ready
    }

    /** How the last answered snapshot got its frame; it outlives a session, because the sensor does. */
    var lastSnapshotExposure = SnapshotExposureOutcome.NONE
        private set

    /** How many snapshots the settle budget has answered without convergence, over this owner's life. */
    var snapshotExposureFallbacks = 0
        private set

    fun addWaiter(w: CompletableFuture<ByteArray?>, nowMs: Long) {
        frameWaiters += SnapshotWaiter(w, nowMs)
    }

    /** Put a waiter back with its original clock, so a re-queue never buys it a fresh budget. */
    fun requeue(waiter: SnapshotWaiter) {
        frameWaiters += waiter
    }

    fun removeWaiter(w: CompletableFuture<ByteArray?>) {
        frameWaiters.removeAll { it.future === w }
    }

    fun noteDeviceFault(attemptId: Long, fault: CameraFault): Boolean {
        if (!isCurrent(attemptId)) return false
        pendingFault = fault
        return true
    }

    /** The watchdog tick; the policy decides, this only supplies the observed facts. */
    fun tick(attemptId: Long, nowMs: Long, enabled: Boolean, indicated: Boolean): CameraSessionPolicy.Decision? {
        if (!isCurrent(attemptId) || phase != Phase.LIVE) return null
        val fault = pendingFault ?: if (!indicated) CameraFault.INDICATION else null
        pendingFault = null
        return policy().onTick(
            CameraSessionPolicy.Tick(
                nowMs = nowMs, openedAtMs = openedAtMs, lastFrameAtMs = lastFrameAtMs, clients = leases.size,
                enabled = enabled, stopping = false, deviceFault = fault, consecutiveFailures = consecutiveFailures,
            ),
        )
    }

    /**
     * A live session is being torn down for a reopen: the leases and the stream binding stay, the attempt
     * ends, grace restarts on fire. Refused — returning false and changing nothing — unless [attemptId]
     * is still the current attempt of a live session, so a decision computed for a session that a
     * disable, a last-lease release or a stop has since ended can never revive it. The owner computes
     * and applies a tick decision inside one critical section; this guard is the backstop for any path
     * that does not.
     */
    fun reopening(attemptId: Long, attempt: Int): Boolean {
        if (!isCurrent(attemptId) || phase != Phase.LIVE) return false
        phase = Phase.OPENING
        consecutiveFailures = attempt
        currentAttempt = null
        pendingFault = null
        return true
    }

    /** Same guard as [reopening]: only the current attempt of an opening or live session may degrade it. */
    fun degraded(attemptId: Long, attempt: Int, nowMs: Long): Boolean {
        if (!isCurrent(attemptId) || (phase != Phase.LIVE && phase != Phase.OPENING)) return false
        endSession(Phase.DEGRADED)
        consecutiveFailures = attempt
        retryNotBeforeMs = nowMs + policy().maxBackoffMs
        return true
    }
}
