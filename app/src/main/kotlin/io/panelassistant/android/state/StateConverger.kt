package io.panelassistant.android.state

import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.RetirableMutationGate
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One destination for converged observations, addressed by transport-neutral channel id.
 * A sink must return promptly: it runs on the convergence pump.
 */
typealias StateSink = (channel: String, observation: StateConverger.Observation.Reportable) -> Unit

/**
 * Registry-driven state observation. Every state-bearing entity supplies one authoritative observation
 * under a transport-neutral channel id; commands, reconnects, local events and periodic audits all flow
 * through this class, and every admitted observation reaches each sink in admission order. Transport
 * addressing and delivery bookkeeping belong to the destination, never to the channel.
 */
class StateConverger {
    sealed interface Observation {
        /** What a sink may receive. Unknown publishes nothing, so it is never reported. */
        sealed interface Reportable : Observation
        /** [attributes] is the JSON object of attributes reported with the value, when the channel has any. */
        data class Known(val payload: String, val attributes: String? = null) : Reportable
        data object Unknown : Observation
        data object Unavailable : Reportable
        /**
         * A sensor the panel serves but has no reading for, and why. Unlike [Unknown] it is reported, so Home
         * Assistant shows the entity as unknown with [reason] instead of keeping a stale value or going unavailable.
         */
        data class NoReading(val reason: NoReadingReason) : Reportable
    }

    /** Why a sensor has no reading; [code] is the protocol's `reason` (section 7). */
    enum class NoReadingReason(val code: String) {
        /** Served, but no reading has arrived yet in this process. */
        WAITING("waiting"),
        /** The owner turned this sensor's sharing off in the panel's settings. */
        NOT_SHARED("not_shared"),
        /** The panel cannot read it: no root helper, a permission Android withholds, or no readable source. */
        NOT_READABLE("not_readable"),
        /** A Wi-Fi measurement on a panel not connected over Wi-Fi. */
        NOT_ON_WIFI("not_on_wifi"),
        /** The feature it measures is switched off. */
        TURNED_OFF("turned_off"),
        /** The feature is on but has not settled enough to decide. */
        NOT_READY("not_ready"),
    }

    data class Channel(
        /** Transport-neutral channel id matching [CHANNEL_ID]. */
        val key: String,
        val observe: () -> Observation,
    )

    private val channels = linkedMapOf<String, Channel>()
    private val sinks = CopyOnWriteArrayList<StateSink>()
    private var closed = false
    private val lifecycle = RetirableMutationGate()

    @Synchronized
    fun register(channel: Channel) {
        check(!closed) { "state converger is closed" }
        require(CHANNEL_ID.matches(channel.key)) { "invalid state channel id ${channel.key}" }
        check(channel.key !in channels) { "duplicate state channel ${channel.key}" }
        channels[channel.key] = channel
    }

    /** Every later admitted observation reaches [sink]. */
    fun addSink(sink: StateSink) {
        sinks += sink
    }

    /**
     * [admit] is evaluated under this monitor immediately before an observation is accepted, so a caller
     * can withdraw a reconcile whose observation was overtaken (e.g. a command read-back superseded by
     * a newer command mid-observation). A refused admission changes no channel state: the caller
     * guarantees a later reconcile for the same channel is already ordered behind it.
     */
    fun reconcile(key: String, admit: () -> Boolean = ALWAYS_ADMIT) {
        lifecycle.runIfOpen(Unit) { reconcileAdmitted(key, admit) }
    }

    private fun reconcileAdmitted(key: String, admit: () -> Boolean) {
        val channel = synchronized(this) { if (closed) null else channels[key] } ?: return
        val observation = runCatching { channel.observe() }.getOrDefault(Observation.Unknown)
        synchronized(this) {
            if (closed) return
            // Withdraw superseded command read-backs before any sink receives them, and keep observation
            // delivery ordered under the monitor.
            if (!admit()) return
            if (observation is Observation.Reportable) {
                for (sink in sinks) {
                    try {
                        sink(key, observation)
                    } catch (_: Exception) {
                        // A sink never changes another sink's admission.
                    }
                }
            }
        }
    }

    fun reconcileAll() {
        val keys = synchronized(this) { if (closed) emptyList() else channels.keys.toList() }
        keys.forEach { reconcile(it) }
    }

    /** Terminal owner boundary: reject queued audits and invalidate every late completion. */
    fun close() {
        lifecycle.closeAdmission()
        synchronized(this) {
            if (closed) return
            closed = true
        }
    }

    /** Close admission and prove no observer or sender call can cross into owner teardown. */
    internal fun closeAndDrain(deadline: MonotonicDeadline): Boolean {
        close()
        return lifecycle.awaitDrained(deadline)
    }

    @Synchronized
    fun keys(): Set<String> = channels.keys.toSet()

    companion object {
        /** Protocol §7 channel identity: a lowercase letter, then up to 47 lowercase letters, digits or underscores. */
        val CHANNEL_ID = Regex("^[a-z][a-z0-9_]{0,47}$")
        private val ALWAYS_ADMIT: () -> Boolean = { true }
        private val PUMP = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "state-convergence").apply { isDaemon = true }
        }

        /** Serialize local UI/hardware notifications on one convergence thread. */
        internal fun dispatch(task: () -> Unit) = PUMP.execute(task)
    }
}
