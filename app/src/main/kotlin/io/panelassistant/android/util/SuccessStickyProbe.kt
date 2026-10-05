package io.panelassistant.android.util

/**
 * Caches the first successful non-null probe result for the lifetime of its owner. Unavailable and
 * failed probes remain retryable, with monotonic exponential backoff so request-path callers cannot
 * turn a temporarily missing capability into a tight privileged-probe loop.
 */
internal class SuccessStickyProbe<T : Any>(
    private val probe: () -> T?,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val initialBackoffMs: Long = 1_000L,
    private val maxBackoffMs: Long = 60_000L,
) {
    init {
        require(initialBackoffMs > 0L)
        require(maxBackoffMs >= initialBackoffMs)
    }

    private var cached: T? = null
    private var nextProbeAtMs = Long.MIN_VALUE
    private var backoffMs = initialBackoffMs
    private var previousNowMs = Long.MIN_VALUE

    @Synchronized
    fun get(): T? {
        cached?.let { return it }
        val now = nowMs()
        // A monotonic source should not move backwards, but injected/test clocks can. Expire the failed
        // probe rather than extending its suppression window under the new epoch.
        if (previousNowMs != Long.MIN_VALUE && now < previousNowMs) nextProbeAtMs = Long.MIN_VALUE
        previousNowMs = now
        if (nextProbeAtMs != Long.MIN_VALUE && now < nextProbeAtMs) return null

        val value = runCatching(probe).getOrNull()
        if (value != null) {
            cached = value
            return value
        }

        nextProbeAtMs = if (now > Long.MAX_VALUE - backoffMs) Long.MAX_VALUE else now + backoffMs
        val doublingThreshold = maxBackoffMs / 2 + maxBackoffMs % 2
        backoffMs = if (backoffMs >= doublingThreshold) maxBackoffMs else backoffMs * 2
        return null
    }
}
