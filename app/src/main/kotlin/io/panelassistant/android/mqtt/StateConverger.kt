package io.panelassistant.android.mqtt

import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCostRegistry
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.RetirableMutationGate

/**
 * One destination for converged observations, addressed by transport-neutral channel id. [done] reports
 * true only when this destination acknowledged the exact observation on the connection that sent it.
 * A sink must return promptly: it runs on the convergence pump.
 */
typealias StateSink = (channel: String, observation: StateConverger.Observation.Reportable, done: (Boolean) -> Unit) -> Unit

/**
 * Registry-driven state convergence. Every state-bearing entity supplies one authoritative observation
 * under a transport-neutral channel id; commands, reconnects, local events and periodic audits all flow
 * through this class. Publication state advances only after [sender] acknowledges the exact generation
 * that was sent. Transport addressing (topics, retain) belongs to the sink, never to the channel.
 */
class StateConverger(
    private val sender: StateSink,
    private val schedule: (() -> Unit) -> Unit = ::dispatch,
    private val featureCosts: FeatureCostRegistry = FeatureCosts.registry,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Bounded, non-blocking observer, independent of this sender's delivery and acknowledgements. */
    private val onObservation: (String, Observation.Reportable) -> Unit = { _, _ -> },
) {
    sealed interface Observation {
        /** What a sink may receive. Unknown publishes nothing, so it is never reported. */
        sealed interface Reportable : Observation
        data class Known(val payload: String) : Reportable
        data object Unknown : Observation
        data object Unavailable : Reportable
    }

    data class Channel(
        /** Transport-neutral channel id matching [CHANNEL_ID]; equal to today's MQTT topic leaf. */
        val key: String,
        val observe: () -> Observation,
        val equivalent: (acknowledged: String, observed: String) -> Boolean = String::equals,
        /** Whether a known payload is meaningful enough to repeat solely for freshness. Semantic
         *  changes still publish normally even when this returns false. */
        val refreshEligible: (String) -> Boolean = { true },
        /** Maximum acknowledged silence for this channel. Null keeps change-only
         *  publication. The deadline never republishes Unknown or Unavailable observations. */
        val maxSilenceMs: Long? = null,
        /** Observed for [onObservation] only; [sender] never receives it (a channel MQTT cannot carry). */
        val nativeOnly: Boolean = false,
    )

    private data class Runtime(
        val channel: Channel,
        var acknowledged: String? = null,
        var sent: String? = null,
        var generation: Long = 0,
        var inFlight: Boolean = false,
        var queuedLatest: Boolean = false,
        var dirty: Boolean = true,
        var unknown: Boolean = false,
        var sentAtMs: Long = 0,
        var acknowledgedAtMs: Long? = null,
        var cost: FeatureCostRegistry.Span? = null,
    )

    private val channels = linkedMapOf<String, Runtime>()
    private var successes = 0L
    private var failures = 0L
    private var closed = false
    private val lifecycle = RetirableMutationGate()

    @Synchronized
    fun register(channel: Channel) {
        check(!closed) { "state converger is closed" }
        require(CHANNEL_ID.matches(channel.key)) { "invalid state channel id ${channel.key}" }
        check(channel.key !in channels) { "duplicate state channel ${channel.key}" }
        require(channel.maxSilenceMs == null || channel.maxSilenceMs > 0L) {
            "maxSilenceMs must be positive"
        }
        channels[channel.key] = Runtime(channel)
    }

    /**
     * [admit] is evaluated under this monitor immediately before an observation is accepted, so a caller
     * can withdraw a reconcile whose observation was overtaken (e.g. a command read-back superseded by
     * a newer command mid-observation). A refused admission changes no channel state: the caller
     * guarantees a later reconcile for the same channel is already ordered behind it.
     */
    fun reconcile(key: String, force: Boolean = false, admit: () -> Boolean = ALWAYS_ADMIT) {
        lifecycle.runIfOpen(Unit) { reconcileAdmitted(key, force, admit) }
    }

    private fun reconcileAdmitted(key: String, force: Boolean, admit: () -> Boolean) {
        val runtime = synchronized(this) { if (closed) null else channels[key] } ?: return
        val observation = runCatching { runtime.channel.observe() }.getOrDefault(Observation.Unknown)
        val generation: Long
        val payload: String
        var admittedCost: FeatureCostRegistry.Span? = null
        synchronized(this) {
            if (closed) return
            // Withdraw superseded command read-backs before either transport receives them. Keep
            // observation delivery ordered under the monitor, ahead of MQTT's ACK/capacity gates.
            if (!admit()) return
            runtime.unknown = observation !is Observation.Known
            val observedPayload = when (observation) {
                is Observation.Known -> observation.payload
                Observation.Unavailable -> ""
                Observation.Unknown -> {
                    // An unreadable sample cannot free an MQTT slot still awaiting acknowledgement.
                    if (!runtime.inFlight) runtime.dirty = false
                    return
                }
            }
            try {
                onObservation(runtime.channel.key, observation as Observation.Reportable)
            } catch (_: Exception) {
                // Native delivery never changes MQTT admission or acknowledgement state.
            }
            if (runtime.channel.nativeOnly) {
                runtime.dirty = false
                return
            }
            val acknowledged = runtime.acknowledged
            val equivalent = acknowledged?.let { runtime.channel.equivalent(it, observedPayload) } == true
            val refreshDue = !runtime.unknown && runtime.channel.refreshEligible(observedPayload) &&
                runtime.channel.maxSilenceMs?.let { maximum ->
                runtime.acknowledgedAtMs?.let { acknowledgedAt ->
                    (monotonicMs() - acknowledgedAt).coerceAtLeast(0L) >= maximum
                }
            } == true
            // A cadence report repeats the acknowledged representative after freshly observing an
            // equivalent value. This proves freshness without allowing sub-deadband drift to move the
            // baseline a little every interval. Semantic/deadband-crossing and forced updates send the
            // current observation as before.
            payload = if (!force && refreshDue && equivalent) requireNotNull(acknowledged) else observedPayload
            if (runtime.inFlight && runtime.sent == payload) return
            if (runtime.inFlight) {
                // Exactly one physical publish per channel. The observer remains the latest-value
                // authority; one dirty bit conflates any alternating flood until the ACK frees its slot.
                runtime.queuedLatest = true
                runtime.dirty = true
                featureCosts.recordCoalesced(FeatureCostOperation.MQTT_STATE_OUTBOX)
                updateBacklog()
                return
            }
            if (!force && !runtime.dirty && equivalent && !refreshDue) return
            if (channels.values.count { it.inFlight } >= MAX_IN_FLIGHT) {
                // A capacity refusal must not lose the observation. A CLEAN channel reconciled here
                // (e.g. just commanded during a burst) would otherwise stay silent until the next
                // periodic audit; dirty keeps it in the ACK pump's reconcileDirty drain.
                runtime.dirty = true
                updateBacklog()
                return
            }
            // Native recording and equivalence checks may overlap a newer command's admission.
            // Retain MQTT's final read-back check immediately before committing its publication.
            if (!admit()) return
            generation = ++runtime.generation
            runtime.sent = payload
            runtime.inFlight = true
            runtime.queuedLatest = false
            runtime.dirty = true
            runtime.sentAtMs = System.currentTimeMillis()
            admittedCost = featureCosts.span(FeatureCostOperation.MQTT_STATE_OUTBOX)
                .work(units = 1, bytes = payload.toByteArray(Charsets.UTF_8).size.toLong())
            runtime.cost = admittedCost
            updateBacklog()
        }

        val cost = requireNotNull(admittedCost)
        val completion: (Boolean) -> Unit = { success ->
            var pump = false
            synchronized(this) {
                if (runtime.cost === cost) runtime.cost = null
                if (closed || generation != runtime.generation) return@synchronized
                runtime.inFlight = false
                if (success) {
                    successes++
                    runtime.acknowledged = payload
                    runtime.acknowledgedAtMs = monotonicMs()
                } else {
                    failures++
                }
                val queued = runtime.queuedLatest
                runtime.queuedLatest = false
                runtime.dirty = queued || !success
                // A success frees capacity for other dirty channels; a queued latest value also pumps
                // after failure, while a lone failed value waits for the ordinary retry/audit cadence.
                pump = success || queued
                updateBacklog()
            }
            cost.outcome(if (success) FeatureCostOutcome.SUCCESS else FeatureCostOutcome.FAILURE).close()
            if (pump) schedule { reconcileDirty() }
        }
        // Unavailable never takes the cadence path (it marks the channel unknown), so an unavailable
        // observation always sends itself; every other admitted payload is a known value.
        val reported = if (observation is Observation.Unavailable) Observation.Unavailable else Observation.Known(payload)
        try {
            sender(runtime.channel.key, reported, completion)
        } catch (_: Exception) {
            completion(false)
        }
    }

    fun reconcileAll(force: Boolean = false) {
        val keys = synchronized(this) { if (closed) emptyList() else channels.keys.toList() }
        keys.forEach { reconcile(it, force) }
    }

    /** Drain only channels already queued/dirty; do not turn ACK completion into a fresh sensor poll. */
    fun reconcileDirty() {
        val keys = synchronized(this) {
            if (closed) emptyList() else channels.filterValues { it.dirty && !it.inFlight }.keys.toList()
        }
        keys.forEach { reconcile(it) }
    }

    /**
     * Invalidate every acknowledgement from the previous broker connection. A QoS acknowledgement is
     * evidence about the connection that produced it, not permission to suppress publication forever:
     * a replacement broker/session may have no retained copy. Incrementing each generation also makes
     * completions from the superseded connection harmless if they arrive after reconnect.
     */
    @Synchronized
    fun markAllDirty() {
        if (closed) return
        channels.values.forEach {
            it.cost?.outcome(FeatureCostOutcome.CANCELLED)?.close()
            it.cost = null
            it.generation++
            it.acknowledged = null
            it.acknowledgedAtMs = null
            it.sent = null
            it.dirty = true
            it.inFlight = false
            it.queuedLatest = false
        }
        updateBacklog()
    }

    /** Terminal owner boundary: reject queued audits and invalidate every late completion. */
    fun close() {
        lifecycle.closeAdmission()
        synchronized(this) { closeLocked() }
    }

    /** Close admission and prove no observer or sender call can cross into owner teardown. */
    internal fun closeAndDrain(deadline: MonotonicDeadline): Boolean {
        close()
        return lifecycle.awaitDrained(deadline)
    }

    /** Must be called under this monitor. */
    private fun closeLocked() {
        if (closed) return
        closed = true
        channels.values.forEach {
            it.cost?.outcome(FeatureCostOutcome.CANCELLED)?.close()
            it.cost = null
            it.generation++
            it.inFlight = false
            it.queuedLatest = false
            it.dirty = false
        }
        featureCosts.setBacklog(FeatureCostOperation.MQTT_STATE_OUTBOX, 0)
    }

    @Synchronized
    fun keys(): Set<String> = channels.keys.toSet()

    data class Status(
        val channels: Int,
        val dirty: Int,
        val inFlight: Int,
        val unknown: Int,
        val successes: Long,
        val failures: Long,
        val pending: List<String>,
    )

    @Synchronized
    fun status(): Status = Status(
        channels = channels.size,
        dirty = channels.values.count { it.dirty },
        inFlight = channels.values.count { it.inFlight },
        unknown = channels.values.count { it.unknown },
        successes = successes,
        failures = failures,
        pending = channels.values.filter { it.inFlight }.map {
            "${it.channel.key}:${((System.currentTimeMillis() - it.sentAtMs).coerceAtLeast(0) / 1000)}s"
        },
    )

    /** Must be called under this monitor. */
    private fun updateBacklog() {
        featureCosts.setBacklog(
            FeatureCostOperation.MQTT_STATE_OUTBOX,
            channels.values.count { it.queuedLatest || it.dirty && !it.inFlight },
        )
    }

    companion object {
        /** Protocol §7 channel identity; a colon can never appear, so `http:<key>` keys cannot collide. */
        val CHANNEL_ID = Regex("^[a-z][a-z0-9_]{0,47}$")
        private const val MAX_IN_FLIGHT = 4
        private val ALWAYS_ADMIT: () -> Boolean = { true }
        private val pumpThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        private val PUMP = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "state-convergence").apply { isDaemon = true; pumpThread.set(this) }
        }

        internal fun onPumpThread(): Boolean = Thread.currentThread() === pumpThread.get()

        /** Serialize local UI/hardware notifications with acknowledgement-driven outbox pumping. */
        internal fun dispatch(task: () -> Unit) = PUMP.execute(task)

        fun numericDeadband(deadband: Double): (String, String) -> Boolean = { acknowledged, observed ->
            val old = acknowledged.toDoubleOrNull()
            val new = observed.toDoubleOrNull()
            if (old != null && old.isFinite() && new != null && new.isFinite()) {
                kotlin.math.abs(new - old) < deadband
            } else {
                acknowledged == observed
            }
        }
    }
}
