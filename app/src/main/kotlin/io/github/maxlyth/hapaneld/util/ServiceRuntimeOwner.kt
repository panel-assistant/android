package io.github.maxlyth.hapaneld.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One optional bounded-liveness policy for a latest-value operation. */
internal class LatestOperationTimeoutPolicy(
    val budgetMs: Long,
    val schedule: (Runnable, delayMs: Long) -> Boolean,
    val cancel: (Runnable) -> Unit,
    val onTimeout: () -> Unit,
    val nanoTime: () -> Long = System::nanoTime,
) {
    init { require(budgetMs > 0L) { "latest operation budget must be positive" } }
}

/** The fixed operation and timeout policy share one ticket from lane admission through completion. */
internal class LatestOperationPolicy<T : Any>(
    val name: String = "latest",
    val timeout: LatestOperationTimeoutPolicy? = null,
    val operation: ServiceRuntimeOwner<T>.LatestMutation.() -> Unit,
)

/**
 * Single authority for a service runtime's concrete resource, generation and lifecycle transitions.
 *
 * Start and replacement run on one dedicated lane, while accepted reconnects use a bounded recovery
 * pool so one wedged dependency cannot block later lifecycle admission. Background producers receive
 * an [Observation] containing both the generation and exact resource; replacement therefore cannot make
 * a generation/resource split-read look current. Shutdown closes admission synchronously, discards
 * transitions that were queued but not started, and leaves final teardown queued when the caller's wait
 * expires.
 */
internal class ServiceRuntimeOwner<T : Any>(
    initial: T,
    threadName: String,
    private val onError: (operation: String, error: Throwable) -> Unit = { _, _ -> },
    private val onRecoverySaturated: () -> Unit = {},
    private val latestOperation: LatestOperationPolicy<T>? = null,
) {
    data class Observation<T : Any>(val generation: Long, val value: T)

    enum class LatestAdmission { ACCEPTED, COALESCED, CLOSED }

    private enum class State { NEW, STARTING, RUNNING, RECONFIGURING, FAILED, STOPPING, STOPPED }
    private data class View<T : Any>(val state: State, val generation: Long, val value: T)

    private val lock = Any()
    private val lane: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, threadName).apply { isDaemon = true }
    }
    private val recoverySequence = AtomicInteger()
    private val recovery: ExecutorService = ThreadPoolExecutor(
        MAX_RECOVERY_WORKERS,
        MAX_RECOVERY_WORKERS,
        0L,
        TimeUnit.MILLISECONDS,
        SynchronousQueue(),
        { task ->
            Thread(task, "$threadName-reconnect-${recoverySequence.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Volatile private var view = View(State.NEW, 0L, initial)
    private var shutdownFuture: Future<Boolean>? = null
    private var latestPending = false
    private var latestTicket: LatestTicket? = null
    private val latestMutationIdentity = Any()

    private inner class LatestTicket(private val timeout: LatestOperationTimeoutPolicy?) {
        val deadline = timeout?.let { MonotonicDeadline(it.budgetMs, it.nanoTime) }
        private val settled = AtomicBoolean(false)
        private val timeoutTask = Runnable {
            if (markTimedOut()) this@ServiceRuntimeOwner.notifyLatestTimeout(this)
        }
        var started = false

        fun arm(): Throwable? {
            val policy = timeout ?: return null
            val delayMs = deadline!!.remainingMs()
            if (delayMs <= 0L) return TimeoutException("latest operation deadline expired before admission")
            return try {
                if (policy.schedule(timeoutTask, delayMs)) null
                else RejectedExecutionException("latest operation watchdog was rejected")
            } catch (error: Throwable) {
                error
            }
        }

        fun isOpen(): Boolean = !settled.get()

        fun isExpired(): Boolean = settled.get() || deadline?.remainingMs() == 0L

        fun settle(): Boolean = settled.compareAndSet(false, true)

        fun markTimedOut(): Boolean = settle()

        fun cancelWatchdog() {
            timeout?.cancel?.invoke(timeoutTask)
        }

        fun notifyTimeout() {
            timeout?.onTimeout?.invoke()
        }
    }

    /** Scoped access to the owner lane. Instances are valid only while their latest callback runs. */
    inner class LatestMutation internal constructor(
        ticket: Any,
        identity: Any,
    ) {
        private val ticket = ticket as? ServiceRuntimeOwner<T>.LatestTicket
            ?: error("latest mutation ticket was not issued by its owner")
        private val ownerThread = Thread.currentThread()
        private var active = true

        init { check(identity === latestMutationIdentity) { "latest mutation authority was not issued by its owner" } }

        val deadline: MonotonicDeadline get() {
            checkOwnerThread(requireAdmission = false)
            return checkNotNull(ticket.deadline) { "latest operation has no deadline policy" }
        }

        val current: T get() {
            checkOwnerThread()
            return view.value
        }

        val isRunning: Boolean get() {
            checkOwnerThread()
            return view.state == State.RUNNING
        }

        fun replace(
            retire: (T) -> Unit,
            build: (previous: T) -> T,
            start: (T) -> Unit,
            complete: (T) -> Unit = {},
        ): Boolean {
            checkOwnerThread()
            return replaceCurrent(retire, build, start, complete, ::isAdmitted)
        }

        private fun isAdmitted(): Boolean =
            ticket.isOpen() && ticket.deadline?.remainingMs()?.let { it > 0L } != false

        private fun checkOwnerThread(requireAdmission: Boolean = true) {
            check(active && Thread.currentThread() === ownerThread) { "latest mutation escaped its owner callback" }
            if (requireAdmission) check(isAdmitted()) { "latest mutation is no longer admitted" }
        }

        internal fun invalidate() { active = false }
    }

    fun current(): T = view.value

    fun isRunning(): Boolean = view.state == State.RUNNING

    fun isStopped(): Boolean = view.state == State.STOPPED

    /** A finished shutdown that could not prove teardown must select the process boundary. */
    fun hasFailedShutdown(): Boolean = synchronized(lock) {
        shutdownFuture?.isDone == true && view.state != State.STOPPED
    }

    /** Close every transition admission immediately while letting already admitted lane work drain. */
    fun closeAdmission() {
        val ticket = synchronized(lock) { closeAdmissionLocked() }
        cancelLatestWatchdog(ticket)
    }

    fun observe(): Observation<T>? {
        val observed = view
        if (observed.state != State.RUNNING) return null
        return Observation(observed.generation, observed.value)
    }

    fun isCurrent(observation: Observation<T>): Boolean = view.let { current ->
        current.state == State.RUNNING &&
            current.generation == observation.generation &&
            current.value === observation.value
    }

    /** Queue the one initial start. Duplicate starts are rejected when they reach the lane. */
    fun start(block: (T) -> Unit): Future<Boolean> = start(block, complete = {})

    /** Activate dependants only after successful startup, while shutdown admission is still open. */
    fun start(block: (T) -> Unit, complete: (T) -> Unit): Future<Boolean> = submit("start") {
        val started = transition(State.STARTING, setOf(State.NEW)) { block(view.value) }
        if (started) runIfRunning(complete)
        started
    }

    /** A short, non-blocking activation is atomic with closing admission; never wait for work here. */
    fun runIfRunning(block: (T) -> Unit): Boolean = synchronized(lock) {
        if (view.state != State.RUNNING) return@synchronized false
        block(view.value)
        true
    }

    /** Queue a full retire/build/start replacement. [retire] must close recovery admission for the old
     * resource and wait for any recovery mutation that already entered before it returns. */
    fun reconfigure(
        retire: (T) -> Unit,
        build: (previous: T) -> T,
        start: (T) -> Unit,
        complete: (T) -> Unit = {},
    ): Future<Boolean> = submit("reconfigure") {
        replaceCurrent(retire, build, start, complete)
    }

    /**
     * Retain at most one not-yet-running mutation and execute it on the existing owner lane. Each lane
     * task consumes one mutation, then queues a pending successor at the tail so reconnect and shutdown
     * work already admitted to the lane keeps its ordering.
     */
    fun requestLatest(): LatestAdmission {
        var failure: Pair<LatestTicket, Throwable>? = null
        val admission = synchronized(lock) {
            check(latestOperation != null) { "no latest operation configured" }
            if (view.state == State.STOPPING || view.state == State.STOPPED) {
                return@synchronized LatestAdmission.CLOSED
            }
            if (latestTicket == null) {
                failure = enqueueLatestLocked()
                if (failure == null) LatestAdmission.ACCEPTED else LatestAdmission.CLOSED
            } else {
                val ticket = checkNotNull(latestTicket)
                if (!ticket.started && !ticket.isExpired()) {
                    // The queued callback has not sampled desired state yet, so this request is already
                    // represented by that ticket and must not occupy its one post-start successor slot.
                    LatestAdmission.COALESCED
                } else {
                    val result = if (latestPending) LatestAdmission.COALESCED else LatestAdmission.ACCEPTED
                    latestPending = true
                    result
                }
            }
        }
        failure?.let { (ticket, error) -> failLatestTicket(ticket, error) }
        return admission
    }

    fun pendingLatestCount(): Int = synchronized(lock) {
        val queued = latestTicket?.let { !it.started && it.isOpen() } == true
        if (view.state != State.STOPPING && view.state != State.STOPPED && (queued || latestPending)) 1 else 0
    }

    /** Admit reconnect work only for the still-current observed resource generation. */
    fun reconnect(observation: Observation<T>, block: (T) -> Unit): Future<Boolean> {
        if (view.value !== observation.value) return CompletableFuture.completedFuture(false)
        val result = CompletableFuture<Boolean>()
        synchronized(lock) {
            if (view.state == State.STOPPING || view.state == State.STOPPED) {
                return CompletableFuture.completedFuture(false)
            }
            lane.execute {
                val accepted = synchronized(lock) {
                    val current = view
                    current.state == State.RUNNING &&
                        current.generation == observation.generation &&
                        current.value === observation.value
                }
                if (!accepted) {
                    result.complete(false)
                } else {
                    try {
                        recovery.execute {
                            try {
                                block(observation.value)
                                result.complete(true)
                            } catch (error: Throwable) {
                                onError("reconnect", error)
                                result.complete(false)
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        onRecoverySaturated()
                        result.complete(false)
                    }
                }
            }
        }
        return result
    }

    /**
     * Close admission and run final teardown after the active transition. A timeout does not cancel the
     * queued teardown because doing so could strand resources acquired by that transition.
     */
    fun shutdown(timeoutMs: Long, block: (T) -> Unit): Boolean {
        var cancelledTicket: LatestTicket? = null
        val future = synchronized(lock) {
            shutdownFuture?.let { return@synchronized it }
            cancelledTicket = closeAdmissionLocked()
            lane.submit<Boolean> {
                var success = true
                var interrupted = false
                try {
                    while (!recovery.isTerminated) {
                        try {
                            recovery.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
                        } catch (_: InterruptedException) {
                            interrupted = true
                        }
                    }
                    block(view.value)
                } catch (error: Throwable) {
                    success = false
                    onError("shutdown", error)
                } finally {
                    synchronized(lock) {
                        if (success) view = view.copy(state = State.STOPPED)
                    }
                    if (interrupted) Thread.currentThread().interrupt()
                }
                success
            }.also {
                shutdownFuture = it
                lane.shutdown()
            }
        }
        cancelLatestWatchdog(cancelledTicket)
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (error: Throwable) {
            onError("shutdown-wait", error)
            false
        }
    }

    private fun submit(operation: String, block: () -> Boolean): Future<Boolean> = synchronized(lock) {
        if (view.state == State.STOPPING || view.state == State.STOPPED) {
            return@synchronized CompletableFuture.completedFuture(false)
        }
        lane.submit<Boolean> {
            try {
                block()
            } catch (error: Throwable) {
                onError(operation, error)
                false
            }
        }
    }

    private fun runLatest(ticket: LatestTicket) {
        var notifyExpired = false
        val shouldRun = synchronized(lock) {
            if (latestTicket !== ticket) {
                false
            } else {
                ticket.started = true
                when {
                    view.state == State.STOPPING || view.state == State.STOPPED -> {
                        latestPending = false
                        ticket.settle()
                        false
                    }
                    ticket.isExpired() -> {
                        notifyExpired = ticket.markTimedOut()
                        false
                    }
                    !ticket.isOpen() -> false
                    else -> {
                        // The callback samples current desired state. Only requests arriving after this
                        // point need the one successor slot retained in [latestPending].
                        latestPending = false
                        true
                    }
                }
            }
        }
        if (notifyExpired) notifyLatestTimeout(ticket)
        var mutation: LatestMutation? = null
        try {
            if (shouldRun) {
                mutation = LatestMutation(ticket, latestMutationIdentity)
                latestOperation!!.operation.invoke(mutation)
            }
        } catch (error: Throwable) {
            onError(latestOperation!!.name, error)
        } finally {
            mutation?.invalidate()
            var notifyOverdue = false
            var successorFailure: Pair<LatestTicket, Throwable>? = null
            synchronized(lock) {
                if (latestTicket === ticket) {
                    if (ticket.isExpired()) {
                        notifyOverdue = ticket.markTimedOut()
                    } else {
                        ticket.settle()
                    }
                    if (latestPending && view.state != State.STOPPING && view.state != State.STOPPED) {
                        latestPending = false
                        successorFailure = enqueueLatestLocked()
                    } else {
                        latestPending = false
                        latestTicket = null
                    }
                }
            }
            cancelLatestWatchdog(ticket)
            if (notifyOverdue) notifyLatestTimeout(ticket)
            successorFailure?.let { (failedTicket, error) -> failLatestTicket(failedTicket, error) }
        }
    }

    /** Install one exact ticket before enqueueing its runner. Caller holds [lock]. */
    private fun enqueueLatestLocked(): Pair<LatestTicket, Throwable>? {
        val operation = checkNotNull(latestOperation)
        val ticket = LatestTicket(operation.timeout)
        latestTicket = ticket
        ticket.arm()?.let { error ->
            latestTicket = null
            latestPending = false
            return ticket to error
        }
        return try {
            lane.execute { runLatest(ticket) }
            null
        } catch (error: RejectedExecutionException) {
            latestTicket = null
            latestPending = false
            ticket to error
        }
    }

    private fun failLatestTicket(ticket: LatestTicket, error: Throwable) {
        val notify = ticket.markTimedOut()
        cancelLatestWatchdog(ticket)
        onError("${latestOperation?.name ?: "latest"}-admission", error)
        if (notify) notifyLatestTimeout(ticket)
    }

    private fun notifyLatestTimeout(ticket: LatestTicket) {
        runCatching(ticket::notifyTimeout).onFailure { error ->
            onError("${latestOperation?.name ?: "latest"}-timeout", error)
        }
    }

    private fun cancelLatestWatchdog(ticket: LatestTicket?) {
        if (ticket == null) return
        runCatching(ticket::cancelWatchdog).onFailure { error ->
            onError("${latestOperation?.name ?: "latest"}-watchdog-cancel", error)
        }
    }

    /** Replace the concrete resource inline on the owner lane. Each blocking phase has a lock-linearized
     * admission point, so shutdown can prevent a not-yet-admitted successor phase without waiting for
     * the currently admitted phase. A replacement returned after shutdown remains published solely as
     * the exact resource terminal cleanup must own. */
    private fun replaceCurrent(
        retire: (T) -> Unit,
        build: (previous: T) -> T,
        start: (T) -> Unit,
        complete: (T) -> Unit,
        stillAdmitted: () -> Boolean = { true },
    ): Boolean {
        synchronized(lock) {
            if (view.state !in setOf(State.RUNNING, State.FAILED) || !stillAdmitted()) return false
            view = view.copy(state = State.RECONFIGURING, generation = view.generation + 1L)
        }
        var success = false
        try {
            val previous = view.value
            retire(previous)
            if (!admitReconfigurePhase(stillAdmitted)) return false
            val replacement = build(previous)
            val startAdmitted = synchronized(lock) {
                view = view.copy(value = replacement)
                view.state == State.RECONFIGURING && stillAdmitted()
            }
            if (!startAdmitted) return false
            start(replacement)
            if (!admitReconfigurePhase(stillAdmitted)) return false
            complete(replacement)
            success = stillAdmitted()
        } catch (error: Throwable) {
            onError("reconfiguring", error)
        } finally {
            synchronized(lock) {
                if (view.state == State.RECONFIGURING) {
                    view = view.copy(state = if (success) State.RUNNING else State.FAILED)
                }
            }
        }
        return success
    }

    private fun admitReconfigurePhase(stillAdmitted: () -> Boolean): Boolean = synchronized(lock) {
        view.state == State.RECONFIGURING && stillAdmitted()
    }

    private fun closeAdmissionLocked(): LatestTicket? {
        if (view.state != State.STOPPED) view = view.copy(state = State.STOPPING)
        latestPending = false
        latestTicket?.settle()
        recovery.shutdownNow()
        return latestTicket
    }

    private fun transition(target: State, allowed: Set<State>, block: () -> Unit): Boolean {
        synchronized(lock) {
            if (view.state !in allowed) return false
            view = view.copy(state = target, generation = view.generation + 1L)
        }
        var success = true
        try {
            block()
        } catch (error: Throwable) {
            success = false
            onError(target.name.lowercase(), error)
        } finally {
            synchronized(lock) {
                if (view.state == target) {
                    view = view.copy(state = if (success) State.RUNNING else State.FAILED)
                }
            }
        }
        return success
    }

    companion object {
        internal const val MAX_RECOVERY_WORKERS = 2
    }
}
