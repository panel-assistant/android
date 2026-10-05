package io.panelassistant.android.control

import android.content.Context
import android.util.Log
import io.panelassistant.android.dashboard.EntityCatalogStore
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Single IO owner for the adaptive controller's bounded ambient evidence. */
internal class AmbientHistoryRuntime(
    context: Context,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    store: EntityCatalogStore? = null,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ha-paneld-ambient-history").apply { isDaemon = true }
    },
) : AutoCloseable {
    private val storeOwner = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        store ?: EntityCatalogStore(context.applicationContext)
    }
    private val store: EntityCatalogStore get() = storeOwner.value
    private val accumulator = AmbientHistoryAccumulator(maxEntries = 24)
    private val pendingLock = Any()
    private val pending = mutableListOf<AmbientMinuteAggregate>()
    private var flushFuture: ScheduledFuture<*>? = null
    private val generation = AtomicLong()
    @Volatile private var contextId = "unlocated"
    @Volatile private var sourceId = "local"
    @Volatile private var cached = emptyList<AmbientHistoryMinute>()
    @Volatile private var closed = false

    fun configure(nextContextId: String, nextSourceId: String) {
        val normalizedContext = nextContextId.take(128).ifBlank { "unlocated" }
        val normalizedSource = nextSourceId.take(255).ifBlank { "local" }
        if (normalizedContext == contextId && normalizedSource == sourceId) return
        synchronized(pendingLock) {
            pending += accumulator.drain()
            contextId = normalizedContext
            sourceId = normalizedSource
            cached = emptyList()
            generation.incrementAndGet()
        }
        flushAsync(reloadAfter = true)
    }

    fun record(epochMs: Long, lux: Double, durationMs: Long, baselineEligible: Boolean) {
        if (closed) return
        val completed = accumulator.add(
            contextId = contextId,
            sourceId = sourceId,
            epochMs = epochMs,
            lux = lux,
            durationMs = durationMs,
            baselineEligible = baselineEligible,
        )
        if (completed.isNotEmpty()) synchronized(pendingLock) {
            pending += completed
            while (pending.size > MAX_PENDING) pending.removeAt(0)
            scheduleFlushLocked()
        }
    }

    fun history(): List<AmbientHistoryMinute> = cached
    fun currentContextId(): String = contextId
    fun currentSourceId(): String = sourceId

    fun reset() {
        val target = synchronized(pendingLock) {
            flushFuture?.cancel(false)
            flushFuture = null
            pending.clear()
            accumulator.drain()
            cached = emptyList()
            generation.incrementAndGet()
            contextId to sourceId
        }
        if (!closed) executor.execute {
            runCatching { store.resetAmbientHistory(target.first, target.second) }
                .onFailure { Log.w(TAG, "ambient history reset failed", it) }
        }
    }

    /** Flush live evidence first, then fill only missing minutes from an external bootstrap. */
    fun seed(
        samples: List<AmbientMinuteAggregate>,
        expectedContextId: String,
        expectedSourceId: String,
        onComplete: () -> Unit = {},
    ) {
        if (closed || samples.isEmpty()) return
        val run = synchronized(pendingLock) {
            if (contextId != expectedContextId || sourceId != expectedSourceId ||
                samples.any { it.key.contextId != expectedContextId || it.key.sourceId != expectedSourceId }
            ) return
            pending += accumulator.drain()
            flushFuture?.cancel(false)
            flushFuture = null
            generation.get()
        }
        executor.execute {
            if (closed || run != generation.get() || contextId != expectedContextId || sourceId != expectedSourceId) return@execute
            if (!flush(reloadAfter = false)) return@execute
            runCatching {
                synchronized(pendingLock) {
                    if (closed || run != generation.get() || contextId != expectedContextId || sourceId != expectedSourceId) {
                        return@execute
                    }
                    // Source changes are rare and seed/prune is a bounded one-shot operation. Keep
                    // the identity lock through it so configure() cannot make another partition
                    // current while this transaction still applies active-first retention.
                    store.seedAmbientHistory(samples, wallClockMs())
                    val since = wallClockMs() / AMBIENT_MINUTE_MS - AMBIENT_RETENTION_MINUTES
                    store.ambientHistory(expectedContextId, expectedSourceId, since)
                }
            }.onSuccess {
                if (run == generation.get() && contextId == expectedContextId && sourceId == expectedSourceId) {
                    cached = it
                    runCatching(onComplete)
                }
            }.onFailure { Log.w(TAG, "ambient history seed failed", it) }
        }
    }

    fun reload() {
        if (closed) return
        executor.execute { reloadCacheFromStore("ambient history load failed") }
    }

    /**
     * Refresh the in-memory cache from the store, discarding the result if the generation or the
     * context/source identity captured when this refresh started has since moved on. Runs on the
     * caller's thread; callers own the executor dispatch and the retention/read semantics.
     */
    private fun reloadCacheFromStore(failMessage: String) {
        val run = generation.get()
        val contextSnapshot = contextId
        val sourceSnapshot = sourceId
        val since = wallClockMs() / AMBIENT_MINUTE_MS - AMBIENT_RETENTION_MINUTES
        runCatching { store.ambientHistory(contextSnapshot, sourceSnapshot, since) }
            .onSuccess {
                if (run == generation.get() && contextSnapshot == contextId && sourceSnapshot == sourceId) cached = it
            }
            .onFailure { Log.w(TAG, failMessage, it) }
    }

    private fun flushAsync(reloadAfter: Boolean = false) {
        if (closed) return
        synchronized(pendingLock) {
            flushFuture?.cancel(false)
            flushFuture = null
        }
        executor.execute { flush(reloadAfter) }
    }

    private fun flushSafely() {
        synchronized(pendingLock) { flushFuture = null }
        runCatching { flush(reloadAfter = true) }
            .onFailure { Log.w(TAG, "ambient history flush failed", it) }
    }

    private fun scheduleFlushLocked() {
        if (closed || flushFuture?.isDone == false) return
        flushFuture = executor.schedule(::flushSafely, FLUSH_MS, TimeUnit.MILLISECONDS)
    }

    private fun flush(reloadAfter: Boolean): Boolean {
        val persisted = synchronized(pendingLock) {
            val batch = if (pending.isEmpty()) emptyList() else pending.toList().also { pending.clear() }
            if (batch.isEmpty()) return@synchronized true
            runCatching {
                // Keep configure() behind the small five-minute write/prune transaction. Otherwise
                // a source switch can overtake this snapshot and make active-first retention prune
                // the partition that has just become current.
                store.recordAmbientHistory(batch, wallClockMs(), contextId, sourceId)
                true
            }.getOrElse {
                Log.w(TAG, "ambient history write failed", it)
                pending.addAll(0, batch.takeLast(MAX_PENDING))
                while (pending.size > MAX_PENDING) pending.removeAt(0)
                scheduleFlushLocked()
                false
            }
        }
        if (reloadAfter) reloadCacheFromStore("ambient history refresh failed")
        return persisted
    }

    fun closeAndJoin(timeoutMs: Long = CLOSE_TIMEOUT_MS): Boolean {
        if (closed) return executor.isTerminated
        synchronized(pendingLock) {
            flushFuture?.cancel(false)
            flushFuture = null
            pending += accumulator.drain()
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        val flushed = runCatching {
            executor.submit { flush(reloadAfter = false) }
                .get(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            true
        }.onFailure { Log.w(TAG, "ambient history close flush failed", it) }.getOrDefault(false)
        closed = true
        executor.shutdownNow()
        val remainingNanos = (deadline - System.nanoTime()).coerceAtLeast(0L)
        val drained = runCatching { executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS) }
            .getOrDefault(false)
        val storeClosed = if (!storeOwner.isInitialized()) true else if (!drained) false else {
            runCatching { store.close(); true }
                .onFailure { Log.w(TAG, "ambient history store close failed", it) }
                .getOrDefault(false)
        }
        return flushed && drained && storeClosed
    }

    override fun close() { closeAndJoin() }

    companion object {
        private const val TAG = "ha-paneld/ambient-history"
        private const val FLUSH_MS = 5L * 60_000L
        private const val CLOSE_TIMEOUT_MS = 3_000L
        private const val MAX_PENDING = 24
    }
}
