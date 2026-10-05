package io.panelassistant.android

import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.interruptAndJoin
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * One bounded, ordered authority for live MQTT/HTTP commands.
 *
 * Stateful commands conflate by key: replacing a pending value moves the new value to the tail, so
 * the values which survive a burst still execute in their accepted arrival order. Actions never
 * conflate and have a smaller FIFO allowance. Closing rejects new work, cancels everything pending,
 * and waits for the one active handler to finish before returning.
 */
internal class MqttCommandDispatcher(
    private val maxPending: Int = DEFAULT_MAX_PENDING,
    private val maxPendingActions: Int = DEFAULT_MAX_PENDING_ACTIONS,
    private val threadName: String = "mqtt-command-dispatch",
    private val resultWaitMs: Long = DEFAULT_RESULT_WAIT_MS,
    private val nowNanos: () -> Long = System::nanoTime,
    private val onQueueWait: (waitNanos: Long) -> Unit = {},
) {
    enum class Admission { ACCEPTED, COALESCED, REJECTED, CLOSED }
    enum class Execution { SUCCEEDED, FAILED, PENDING, SUPERSEDED, NOT_ADMITTED }
    data class RunResult(
        val admission: Admission,
        val execution: Execution,
        private val completion: CompletableFuture<Execution>? = null,
    ) {
        val executed: Boolean get() = execution == Execution.SUCCEEDED

        /** Observe the terminal result after a bounded caller stopped waiting. Registration is race-safe:
         * CompletableFuture invokes the observer immediately when completion won before registration. */
        internal fun observeLateCompletion(observer: (Execution) -> Unit): Boolean {
            val pendingCompletion = completion.takeIf { execution == Execution.PENDING } ?: return false
            pendingCompletion.whenComplete { terminal, error ->
                observer(if (error == null) terminal else Execution.FAILED)
            }
            return true
        }
    }
    data class DrainResult(val cancelled: Int, val drained: Boolean)

    private data class Work(
        val latestKey: String?,
        val action: () -> Boolean,
        val completion: CompletableFuture<Execution>?,
        val submittedAtNanos: Long,
    )

    private val lock = Object()
    private val pending = ArrayDeque<Work>()
    private var accepting = true
    private var worker: Thread? = null
    private var pendingActions = 0

    init {
        require(maxPending > 0)
        require(maxPendingActions in 1..maxPending)
        require(resultWaitMs > 0)
    }

    fun submitLatest(key: String, command: () -> Unit): Admission {
        require(key.isNotBlank())
        return submit(Work(key, action = { command(); true }, completion = null, submittedAtNanos = nowNanos()))
    }

    /**
     * [submitLatest] for a caller that must answer every command: [onSkipped] runs once, on the thread that
     * decided it, when the command will never run because a newer one replaced it ([Execution.SUPERSEDED])
     * or it was not admitted ([Execution.NOT_ADMITTED]). A command that runs reports its own result.
     */
    fun submitLatest(key: String, onSkipped: (Execution) -> Unit, command: () -> Unit): Admission {
        require(key.isNotBlank())
        val completion = CompletableFuture<Execution>()
        completion.thenAccept { execution ->
            if (execution == Execution.SUPERSEDED || execution == Execution.NOT_ADMITTED) onSkipped(execution)
        }
        return submit(Work(key, action = { command(); true }, completion, submittedAtNanos = nowNanos()))
    }

    fun submitAction(command: () -> Unit): Admission =
        submit(Work(latestKey = null, action = { command(); true }, completion = null, submittedAtNanos = nowNanos()))

    /** Execute a state-setting command on the same ordered authority and report whether it ran. */
    fun runLatest(key: String, command: () -> Unit): Boolean = runLatestResult(key, command = command).executed

    fun runLatestResult(
        key: String,
        onAdmission: (Admission) -> Unit = {},
        command: () -> Unit,
    ): RunResult {
        require(key.isNotBlank())
        val completion = CompletableFuture<Execution>()
        val admission = submit(Work(key, action = { command(); true }, completion, submittedAtNanos = nowNanos()))
        onAdmission(admission)
        val execution = if (admission == Admission.REJECTED || admission == Admission.CLOSED) {
            Execution.NOT_ADMITTED
        } else {
            try {
                completion.get(resultWaitMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                Execution.PENDING
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                Execution.PENDING
            }
        }
        return RunResult(admission, execution, completion.takeIf { execution == Execution.PENDING })
    }

    private fun submit(work: Work): Admission {
        var replaced: Work? = null
        val admission = synchronized(lock) {
            if (!accepting) return@synchronized Admission.CLOSED

            if (work.latestKey != null) {
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (candidate.latestKey == work.latestKey) {
                        iterator.remove()
                        replaced = candidate
                        break
                    }
                }
            }

            if (replaced == null &&
                (pending.size >= maxPending || work.latestKey == null && pendingActions >= maxPendingActions)
            ) return@synchronized Admission.REJECTED

            pending.addLast(work)
            if (work.latestKey == null) pendingActions++
            if (!ensureWorkerLocked()) {
                pending.removeLast()
                if (work.latestKey == null) pendingActions--
                return@synchronized Admission.REJECTED
            }
            lock.notifyAll()
            if (replaced == null) Admission.ACCEPTED else Admission.COALESCED
        }

        when (admission) {
            Admission.COALESCED -> replaced?.completion?.complete(Execution.SUPERSEDED)
            Admission.REJECTED -> {
                replaced?.completion?.complete(Execution.SUPERSEDED)
                work.completion?.complete(Execution.NOT_ADMITTED)
            }
            Admission.CLOSED -> work.completion?.complete(Execution.NOT_ADMITTED)
            Admission.ACCEPTED -> Unit
        }
        return admission
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    /** Close admission, cancel queued commands and interrupt the active command without waiting. */
    fun close(): Int {
        val discarded = mutableListOf<Work>()
        val running = synchronized(lock) {
            if (accepting) {
                accepting = false
                while (pending.isNotEmpty()) discarded += pending.removeFirst()
                pendingActions = 0
                lock.notifyAll()
            }
            worker
        }
        discarded.forEach { it.completion?.complete(Execution.NOT_ADMITTED) }
        running?.interrupt()
        return discarded.size
    }

    fun awaitDrained(deadline: MonotonicDeadline): Boolean =
        synchronized(lock) { worker }.interruptAndJoin(deadline)

    /** Close/cancel first, then spend only the caller's remaining retirement budget draining work. */
    fun closeAndDrain(deadline: MonotonicDeadline): DrainResult =
        DrainResult(close(), awaitDrained(deadline))

    private fun ensureWorkerLocked(): Boolean {
        if (worker != null) return true
        val candidate = Thread({ runLoop() }, threadName).apply {
            isDaemon = true
        }
        worker = candidate
        return try {
            candidate.start()
            true
        } catch (_: Throwable) {
            worker = null
            false
        }
    }

    private fun runLoop() {
        try {
            while (true) {
                val work = synchronized(lock) {
                    while (accepting && pending.isEmpty()) lock.waitUninterruptibly()
                    if (!accepting) return
                    pending.removeFirst().also {
                        if (it.latestKey == null) pendingActions--
                    }
                }
                // Receipt-to-execution wait, reported only for work that actually runs: a conflated
                // entry's wait is meaningless (its replacement restarts the clock at its own submit).
                // The sink is observability, not control flow — a throwing callback must not kill the
                // one worker every live command depends on, so its failure is deliberately discarded.
                runCatching { onQueueWait((nowNanos() - work.submittedAtNanos).coerceAtLeast(0L)) }
                val execution = if (runCatching(work.action).getOrDefault(false)) {
                    Execution.SUCCEEDED
                } else {
                    Execution.FAILED
                }
                work.completion?.complete(execution)
            }
        } finally {
            synchronized(lock) {
                if (worker === Thread.currentThread()) worker = null
                lock.notifyAll()
            }
        }
    }

    private fun Object.waitUninterruptibly() {
        var interrupted = false
        while (true) {
            try {
                wait()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        internal const val DEFAULT_MAX_PENDING = 32
        internal const val DEFAULT_MAX_PENDING_ACTIONS = 8
        internal const val DEFAULT_RESULT_WAIT_MS = 1_000L
    }
}
