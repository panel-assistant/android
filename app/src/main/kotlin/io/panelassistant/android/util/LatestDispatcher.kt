package io.panelassistant.android.util

import android.util.Log
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/**
 * One daemon consumer draining a bounded, insertion-ordered map of the latest pending value per key.
 *
 * Unifies the three former latest-slot workers — `ConflatedWorker`, `BoundedLatestDispatcher` and
 * `KeyedLatestDispatcher` — behind one implementation. A single fixed key (see [singleSlot])
 * reproduces the single-slot variants exactly: `submit` then only ACCEPTs or COALESCEs, never REJECTs.
 *
 * Semantics a caller relies on:
 *  - A running item is allowed to finish; a burst replaces only the pending value for its key. On a
 *    new key past [maxPendingKeys] the submit is REJECTED and the caller keeps the value.
 *  - [onDiscard] observes every value that will not reach [consume] because it was replaced, drained
 *    by a close, or arrived after close. It is ALWAYS invoked outside the lock, so a hook may complete
 *    futures, re-read state or re-enter without risking the dispatcher's monitor. Hooks must remain
 *    prompt: they are synchronous and cannot themselves be bounded or interrupted by the dispatcher.
 *  - Two teardown flavors, matching the predecessors: polite [close] (stop admitting, drain pending to
 *    [onDiscard], let the running item finish) paired with [awaitTermination]; and interrupting
 *    [closeAndJoin] (interrupt the consumer before any join) for owners that must prove an old
 *    generation drained without risking an indefinite shutdown when I/O ignores interruption.
 *  - [runLoop]'s `finally` re-notifies, so [awaitTermination] / [closeAndJoin] never miss the exit
 *    wakeup (the omission that made the old ConflatedWorker unsafe to await).
 *  - Non-fatal consumer exceptions are contained at the worker boundary and sent to [onFailure],
 *    which must not throw. Fatal JVM errors still escape. Pending latest work then continues.
 */
internal class LatestDispatcher<K : Any, V : Any>(
    threadName: String,
    private val maxPendingKeys: Int,
    private val consume: (K, V) -> Unit,
    private val onDiscard: (K, V) -> Unit = { _, _ -> },
    private val onFailure: (Exception) -> Unit = { failure ->
        Log.e(TAG, "$threadName consumer failed", failure)
    },
) : AutoCloseable {
    enum class Admission { ACCEPTED, COALESCED, REJECTED, CLOSED }

    private val lock = Object()
    private val pending = LinkedHashMap<K, V>()
    private val workerName = threadName
    private var worker: Thread? = null
    private var closed = false

    init { require(maxPendingKeys > 0) { "maxPendingKeys must be positive" } }

    fun submit(key: K, value: V): Admission {
        var discardKey: K? = null
        var discardValue: V? = null
        val admission = synchronized(lock) {
            when {
                closed -> { discardKey = key; discardValue = value; Admission.CLOSED }
                !pending.containsKey(key) && pending.size >= maxPendingKeys -> Admission.REJECTED
                else -> {
                    val existed = pending.containsKey(key)
                    if (existed) { discardKey = key; discardValue = pending.remove(key) } // replaced; move to tail
                    pending[key] = value
                    startWorkerIfNeededLocked()
                    lock.notifyAll()
                    if (existed) Admission.COALESCED else Admission.ACCEPTED
                }
            }
        }
        discardValue?.let { onDiscard(discardKey as K, it) }
        return admission
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    private fun startWorkerIfNeededLocked() {
        if (worker == null) {
            worker = Thread(::runLoop, workerName).apply { isDaemon = true; start() }
        }
    }

    private fun runLoop() {
        try {
            while (true) {
                val next = synchronized(lock) {
                    while (!closed && pending.isEmpty()) {
                        try { lock.wait() } catch (_: InterruptedException) { if (closed) return }
                    }
                    if (closed) return
                    val entry = pending.entries.first()
                    (entry.key to entry.value).also { pending.remove(entry.key) }
                }
                try {
                    consume(next.first, next.second)
                } catch (failure: InterruptedException) {
                    if (synchronized(lock) { closed }) return
                    onFailure(failure)
                } catch (failure: Exception) {
                    onFailure(failure)
                }
            }
        } finally {
            synchronized(lock) {
                if (worker === Thread.currentThread()) worker = null
                lock.notifyAll()
            }
        }
    }

    /** Polite teardown: stop admitting, drain pending to [onDiscard], let the running item finish.
     *  Pair with [awaitTermination]. Safe to call from the consume thread. */
    override fun close() {
        val close = beginClose()
        discardAll(close.drained)
    }

    /** Interrupting teardown: interrupt the consumer before discard hooks and any join, then spend the
     *  remaining [timeoutMs] waiting for it to terminate. Synchronous discard hooks count against that
     *  budget but cannot be pre-empted, so they must remain prompt. Returns true once drained; false on
     *  timeout, caller interruption, or when called from the consumer. */
    fun closeAndJoin(timeoutMs: Long): Boolean {
        val deadline = deadlineAfter(timeoutMs)
        val close = beginClose()
        close.running?.interrupt()
        discardAll(close.drained)
        if (close.running == null || close.running === Thread.currentThread()) return close.running == null
        return awaitTerminationUntil(deadline)
    }

    /** Wait until the worker has finished its running item and terminated after a close. Returns false
     *  promptly and restores the caller's interrupt flag when the wait is interrupted. */
    fun awaitTermination(timeoutMs: Long): Boolean = awaitTerminationUntil(deadlineAfter(timeoutMs))

    private fun awaitTerminationUntil(deadline: Long): Boolean {
        synchronized(lock) {
            while (worker != null) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
                val nanos = (remaining - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
                try {
                    lock.wait(millis, nanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return true
        }
    }

    private data class CloseResult<K, V>(val running: Thread?, val drained: Map<K, V>)

    /** Close once (idempotent) and transfer pending values out of the monitor. */
    private fun beginClose(): CloseResult<K, V> {
        val drained = LinkedHashMap<K, V>()
        val running = synchronized(lock) {
            if (!closed) {
                closed = true
                drained.putAll(pending)
                pending.clear()
                lock.notifyAll()
            }
            worker
        }
        return CloseResult(running, drained)
    }

    /** Complete every transferred value even if one callback fails, then rethrow the first failure. */
    private fun discardAll(drained: Map<K, V>) {
        var firstFailure: Throwable? = null
        drained.forEach { (key, value) ->
            try {
                onDiscard(key, value)
            } catch (failure: Throwable) {
                val recorded = firstFailure
                if (recorded == null) firstFailure = failure else recorded.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }

    private fun deadlineAfter(timeoutMs: Long): Long =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))

    companion object {
        private const val TAG = "LatestDispatcher"

        /** A single latest-value slot (fixed key). Reproduces the former ConflatedWorker /
         *  BoundedLatestDispatcher: [submit] never REJECTs — a replacement coalesces and discards the
         *  prior pending value. Combine with the `submit(value)` extension below. */
        fun <V : Any> singleSlot(
            threadName: String,
            consume: (V) -> Unit,
            onDiscard: (V) -> Unit = {},
            onFailure: (Exception) -> Unit = { failure ->
                Log.e(TAG, "$threadName consumer failed", failure)
            },
        ): LatestDispatcher<Unit, V> =
            LatestDispatcher(threadName, 1, { _, v -> consume(v) }, { _, v -> onDiscard(v) }, onFailure)
    }
}

/** Convenience for a single-slot dispatcher: submit against the fixed key. */
internal fun <V : Any> LatestDispatcher<Unit, V>.submit(value: V): LatestDispatcher.Admission =
    submit(Unit, value)
