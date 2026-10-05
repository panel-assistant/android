package io.panelassistant.android.storage

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Publishes every database-failure event as one non-blocking recovery demand. Unlike snapshot
 * publication, equal failures are never collapsed: each one invalidates the hidden clean-observation
 * epoch and must be visible to the recovery owner. The internal subscriber may only enqueue and
 * return; delivery is intentionally inline so a failed verification cannot outrun its own demand and
 * accidentally escape the owner's finite retry budget. All SQLite work remains off the writer thread.
 */
internal class StorageHealthFailureEventDispatch {
    private class Registration(
        val listener: () -> Unit,
        val active: AtomicBoolean = AtomicBoolean(true),
    )

    private val lock = Any()
    private val registrations = LinkedHashSet<Registration>()

    fun subscribe(replayCurrentFailure: () -> Boolean, listener: () -> Unit): AutoCloseable {
        val registration = Registration(listener)
        synchronized(lock) { registrations += registration }
        // Register before reading the latch. A failure before registration is replayed from state; a
        // failure after registration is published, and a race between the two merely coalesces twice.
        if (replayCurrentFailure()) dispatch(registration)
        return AutoCloseable {
            if (registration.active.compareAndSet(true, false)) {
                synchronized(lock) { registrations -= registration }
            }
        }
    }

    fun publish() {
        val targets = synchronized(lock) { registrations.toList() }
        targets.forEach(::dispatch)
    }

    private fun dispatch(registration: Registration) {
        if (registration.active.get()) runCatching(registration.listener)
    }
}

/** Couples the failure latch and its every-event recovery signal without exposing either globally. */
internal class StorageHealthFailureHub(
    private val authority: StorageHealthAuthority,
    private val events: StorageHealthFailureEventDispatch = StorageHealthFailureEventDispatch(),
) {
    fun recordDatabaseFailure(operation: String, throwable: Throwable): StorageHealthSnapshot {
        val snapshot = authority.recordDatabaseFailure(operation, throwable)
        events.publish()
        return snapshot
    }

    fun subscribe(listener: () -> Unit): AutoCloseable = events.subscribe(
        replayCurrentFailure = {
            authority.snapshot().severity == StorageHealthSeverity.DATABASE_FAILURE
        },
        listener = listener,
    )
}

/** Serializes daily and prompt observations and lets lifecycle close cancel the concrete active read. */
internal class StorageHealthObservationQueue<T : Any>(
    private val create: () -> T,
    private val cancel: (T) -> Unit,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val mutex = Mutex()
    private var closed = false
    private var active: T? = null

    suspend fun <R : Any> run(block: suspend (T) -> R): R? = mutex.withLock {
        val resource = synchronized(lifecycleLock) {
            if (closed) return@withLock null
            create().also { active = it }
        }
        try {
            block(resource)
        } finally {
            synchronized(lifecycleLock) {
                if (active === resource) active = null
            }
        }
    }

    override fun close() {
        val current = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            active
        }
        current?.let(cancel)
    }
}

/**
 * One service-lifecycle owner for short post-failure verification. Requests are conflated while the
 * owner waits, but a failure arriving during an observation remains queued and forces a fresh
 * observation whose token begins after that newer failure. A finite delay vector bounds each run;
 * a later failure may start another bounded run.
 */
internal class StorageHealthRecoveryOwner(
    scope: CoroutineScope,
    delaysMs: LongArray,
    private val onError: (Throwable) -> Unit = {},
    private val verify: suspend () -> Boolean,
) : AutoCloseable {
    private val delaysMs = delaysMs.copyOf()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val worker: Job

    init {
        require(delaysMs.isNotEmpty() && delaysMs.all { it > 0L })
        require((1 until delaysMs.size).all { index -> delaysMs[index] >= delaysMs[index - 1] })
        worker = scope.launch {
            for (ignored in requests) {
                var attempt = 0
                while (isActive && attempt < delaysMs.size) {
                    delay(delaysMs[attempt])
                    drainRequests()
                    val complete = try {
                        verify()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        onError(failure)
                        false
                    }
                    // A completed observation invalidated by a newer external failure starts a fresh
                    // bounded run so even a final-attempt race gets post-failure evidence. An incomplete
                    // observation spends an attempt so its own database failure cannot rearm forever.
                    if (drainRequests()) {
                        attempt = if (complete) 0 else attempt + 1
                        continue
                    }
                    if (complete) break
                    attempt++
                }
            }
        }
    }

    fun request(): Boolean = requests.trySend(Unit).isSuccess

    override fun close() {
        requests.close()
        worker.cancel()
    }

    private fun drainRequests(): Boolean {
        var drained = false
        while (requests.tryReceive().isSuccess) drained = true
        return drained
    }
}

/** Wires automatic failure/replay demand to exactly one owner for one service lifecycle. */
internal class StorageHealthRecoveryLifecycle(
    scope: CoroutineScope,
    delaysMs: LongArray,
    subscribeFailures: ((() -> Unit) -> AutoCloseable),
    onError: (Throwable) -> Unit = {},
    verify: suspend () -> Boolean,
) : AutoCloseable {
    private val owner = StorageHealthRecoveryOwner(scope, delaysMs, onError, verify)
    private val failureSubscription = subscribeFailures { owner.request() }

    override fun close() {
        failureSubscription.close()
        owner.close()
    }
}
