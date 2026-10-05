package io.panelassistant.android.dashboard

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Single-owner drain worker shared by the two dashboard telemetry lanes: entity access/metric
 * telemetry and dashboard performance history. Each lane keeps its own accumulator, cadence and
 * flush body; this primitive owns the accumulator reference plus the one worker-lifecycle contract
 * the two lanes previously duplicated byte-for-byte:
 *
 *  - admit under a private monitor, launching a worker only when none is active (launch-if-idle);
 *  - when a worker body returns or throws, clear ownership and re-spawn exactly one replacement iff
 *    the pipeline is still open and the accumulator still holds pending work;
 *  - [close] latches closed, cancels any in-flight worker and runs the caller's accumulator cleanup
 *    under the same monitor.
 *
 * Worker cardinality is therefore unchanged from the two hand-rolled implementations: at most one
 * coroutine per pipeline instance is ever active, and a fresh coroutine is launched only from an
 * idle-and-pending or open-and-pending transition.
 */
internal class EntityTelemetryPipeline<A>(
    private val scope: CoroutineScope,
    val accumulator: A,
    private val hasPending: (A) -> Boolean,
    private val drain: suspend () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private var active = false
    @Volatile private var closed = false
    @Volatile private var job: Job? = null

    /** Run [block] holding the worker monitor, e.g. to drain the accumulator atomically with the
     * worker-ownership decisions taken in [admit] and the re-spawn check. */
    fun <T> withLock(block: (A) -> T): T = synchronized(lock) { block(accumulator) }

    /**
     * Admit a producer batch under the worker monitor. When the pipeline is open, [onOpen]
     * accumulates and a worker is (re)started if idle; once the pipeline is closed, [onClosed] runs
     * instead and no worker is started.
     */
    fun admit(onOpen: (A) -> Unit, onClosed: (A) -> Unit) = synchronized(lock) {
        if (closed) {
            onClosed(accumulator)
        } else {
            onOpen(accumulator)
            ensureRunningLocked()
        }
    }

    /** Must hold [lock]. Starts a single worker when the pipeline is open and none is active. */
    private fun ensureRunningLocked() {
        if (closed || active) return
        active = true
        job = scope.launch(dispatcher) { run() }
    }

    private suspend fun run() {
        try {
            drain()
        } finally {
            synchronized(lock) {
                active = false
                job = null
                if (!closed && hasPending(accumulator)) {
                    active = true
                    job = scope.launch(dispatcher) { run() }
                }
            }
        }
    }

    /** Latch closed, cancel any in-flight worker and run [cleanup] under the monitor, returning its
     * result. A closed pipeline never re-spawns, so an already-started worker's finally observes the
     * closed latch and stops. */
    fun <T> close(cleanup: (A) -> T): T = synchronized(lock) {
        closed = true
        active = false
        job?.cancel()
        job = null
        cleanup(accumulator)
    }
}
