package io.panelassistant.android.metrics

/**
 * Retention seam for metric history. A consumer hands samples to a sink; the sink decides how much to
 * keep and where. The default [RamRingSink] keeps a bounded, drop-oldest FIFO per key in RAM — exactly
 * the behaviour of PerfReader's old `cpuHist`/`ramHist`/`gpuHist` ArrayDeques it replaces. A durable store
 * (SQLite, a JSON ring on `filesDir`) implements the same interface and swaps in later **without touching
 * the reader or its source-selection logic** — that's the whole point of putting retention behind a seam.
 */
interface MetricSink {
    /** Append one sample. */
    fun record(sample: MetricSample)

    /** Samples recorded for [key], oldest-first. Empty when none. */
    fun history(key: String): List<MetricSample>
}

/**
 * Bounded per-key FIFO: keeps the last [capacity] samples for each key, dropping the oldest once full —
 * the drop-in replacement for PerfReader's fixed-size ArrayDeque history. Thread-safe (the reader is
 * driven from a coroutine and the info-page server thread reads history concurrently).
 */
class RamRingSink(private val capacity: Int) : MetricSink {
    private val lock = Any()
    private val rings = HashMap<String, ArrayDeque<MetricSample>>()

    override fun record(sample: MetricSample) {
        synchronized(lock) {
            val q = rings.getOrPut(sample.key) { ArrayDeque() }
            q.addLast(sample)
            while (q.size > capacity) q.removeFirst()
        }
    }

    override fun history(key: String): List<MetricSample> = synchronized(lock) {
        rings[key]?.toList() ?: emptyList()
    }

    /** Drop every retained series when the owning sampler reaches a terminal lifecycle boundary. */
    fun clear() = synchronized(lock) {
        rings.clear()
    }
}
