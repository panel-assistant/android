package io.panelassistant.android.util

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TTL + single-flight memo for expensive probes (su round-trips, `wm density`, process checks).
 * [get] returns the cached value while it's fresh and otherwise rebuilds — concurrent callers wait
 * for the one in-flight build instead of stampeding the probe. [peek] never blocks: it returns the
 * last-known value (possibly stale) or null if never built — the "render instantly, hydrate later"
 * read. [invalidate] forces the next [get] to rebuild (call after a write that changes the probed
 * state, so the UI doesn't show pre-write values for a TTL).
 */
class Cached<T : Any>(
    private val ttlMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val supplier: () -> T,
) {
    @Volatile private var value: T? = null
    @Volatile private var builtAt = Long.MIN_VALUE
    private val lock = Any()
    private val publicationLock = Any()
    private val refreshInFlight = AtomicBoolean(false)
    private val generation = AtomicLong(0L)

    init { require(ttlMs >= 0L) }

    fun peek(): T? = value

    /** Age of the cached value; MAX_VALUE when never built. Lets callers do stale-while-revalidate. */
    fun ageMs(): Long = ageAt(nowMs())

    fun get(): T = getWithSupplier(supplier)

    /** Build an expired value with a request-scoped [supplierOverride]. A fresh value still wins, and
     * concurrent callers still share one build; this lets a caller pass immutable routing evidence
     * without adding mutable request context to the cache owner. */
    fun getWithSupplier(supplierOverride: () -> T): T {
        value?.let { if (ageAt(nowMs()) < ttlMs) return it }
        synchronized(lock) {
            value?.let { if (ageAt(nowMs()) < ttlMs) return it }
            val startedGeneration = generation.get()
            val built = supplierOverride()
            synchronized(publicationLock) {
                value = built
                // Preserve the result as last-known, but never let a concurrent invalidation disappear.
                builtAt = if (generation.get() == startedGeneration) nowMs() else Long.MIN_VALUE
            }
            return built
        }
    }

    /** Return the last-known value immediately and admit at most one asynchronous refresh when stale.
     * The first read still builds synchronously because no truthful last-known value exists yet. */
    fun staleWhileRevalidate(launch: ((() -> Unit), () -> Unit) -> Boolean): T {
        val current = peek() ?: return get()
        if (ageMs() < ttlMs || !refreshInFlight.compareAndSet(false, true)) return current
        val admissionReleased = AtomicBoolean(false)
        val releaseAdmission = {
            if (admissionReleased.compareAndSet(false, true)) refreshInFlight.set(false)
            Unit
        }
        val refresh = {
            try {
                get()
            } finally {
                releaseAdmission()
            }
            Unit
        }
        try {
            if (!launch(refresh, releaseAdmission)) releaseAdmission()
        } catch (error: Throwable) {
            releaseAdmission()
            throw error
        }
        return current
    }

    fun invalidate() {
        synchronized(publicationLock) {
            generation.incrementAndGet()
            builtAt = Long.MIN_VALUE
        }
    }

    /** Prime the cache with a known value (fresh). Use after a write whose result is known, so the UI
     *  shows it immediately instead of racing a re-probe that may still read the pre-write state. */
    fun set(v: T) {
        synchronized(lock) {
            synchronized(publicationLock) {
                value = v
                builtAt = nowMs()
            }
        }
    }

    private fun ageAt(now: Long): Long {
        val built = builtAt
        if (value == null || built == Long.MIN_VALUE || now < built) return Long.MAX_VALUE
        return now - built
    }
}
