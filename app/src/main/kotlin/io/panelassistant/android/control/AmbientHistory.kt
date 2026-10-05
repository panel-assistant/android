package io.panelassistant.android.control

import kotlin.math.ln1p

internal const val AMBIENT_MINUTE_MS = 60_000L
internal const val AMBIENT_RETENTION_MINUTES = 7L * 24L * 60L

internal data class AmbientHistoryKey(
    val contextId: String,
    val sourceId: String,
    val minute: Long,
)

/** Time-weighted evidence for one source/context minute. Integrals use milliseconds as their weight. */
internal data class AmbientMinuteAggregate(
    val key: AmbientHistoryKey,
    var luxIntegral: Double = 0.0,
    var coverageMs: Long = 0,
    var minLux: Double = Double.POSITIVE_INFINITY,
    var maxLux: Double = Double.NEGATIVE_INFINITY,
    var lastLux: Double = 0.0,
    var sampleCount: Long = 0,
    var baselineLogIntegral: Double = 0.0,
    var baselineCoverageMs: Long = 0,
) {
    val meanLux: Double get() = if (coverageMs > 0) luxIntegral / coverageMs else lastLux
    val baselineMeanLogLux: Double?
        get() = baselineCoverageMs.takeIf { it > 0 }?.let { baselineLogIntegral / it }

    fun add(lux: Double, durationMs: Long, baselineEligible: Boolean) {
        require(lux.isFinite() && lux >= 0.0)
        require(durationMs in 1..AMBIENT_MINUTE_MS)
        luxIntegral += lux * durationMs
        coverageMs = (coverageMs + durationMs).coerceAtMost(AMBIENT_MINUTE_MS)
        minLux = minOf(minLux, lux)
        maxLux = maxOf(maxLux, lux)
        lastLux = lux
        sampleCount++
        if (baselineEligible) {
            baselineLogIntegral += ln1p(lux) * durationMs
            baselineCoverageMs = (baselineCoverageMs + durationMs).coerceAtMost(AMBIENT_MINUTE_MS)
        }
    }
}

/**
 * Bounded callback-side accumulator. A duration crossing a minute boundary is split so coverage and
 * integrals remain independent of sensor callback rate. Completed rows are drained by the IO owner.
 */
internal class AmbientHistoryAccumulator(private val maxEntries: Int = 16) {
    private val rows = LinkedHashMap<AmbientHistoryKey, AmbientMinuteAggregate>()

    init { require(maxEntries > 1) }

    @Synchronized
    fun add(
        contextId: String,
        sourceId: String,
        epochMs: Long,
        lux: Double,
        durationMs: Long,
        baselineEligible: Boolean,
    ): List<AmbientMinuteAggregate> {
        if (contextId.isBlank() || sourceId.isBlank() || epochMs < 0L ||
            !lux.isFinite() || lux < 0.0 || durationMs <= 0L) return emptyList()
        var evicted: MutableList<AmbientMinuteAggregate>? = null
        var cursor = epochMs
        var remaining = durationMs.coerceAtMost(AMBIENT_MINUTE_MS * 2)
        while (remaining > 0L) {
            val minute = cursor / AMBIENT_MINUTE_MS
            val untilBoundary = ((minute + 1) * AMBIENT_MINUTE_MS - cursor).coerceAtLeast(1L)
            val slice = minOf(remaining, untilBoundary)
            val key = AmbientHistoryKey(contextId, sourceId, minute)
            rows.getOrPut(key) { AmbientMinuteAggregate(key) }.add(lux, slice, baselineEligible)
            cursor += slice
            remaining -= slice
        }
        val newestMinute = (cursor - 1L) / AMBIENT_MINUTE_MS
        val iterator = rows.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.minute < newestMinute) {
                (evicted ?: mutableListOf<AmbientMinuteAggregate>().also { evicted = it }).add(entry.value)
                iterator.remove()
            }
        }
        while (rows.size > maxEntries) {
            val oldest = rows.entries.first()
            rows.remove(oldest.key)
            (evicted ?: mutableListOf<AmbientMinuteAggregate>().also { evicted = it }).add(oldest.value)
        }
        return evicted ?: emptyList()
    }

    @Synchronized fun drain(): List<AmbientMinuteAggregate> = rows.values.toList().also { rows.clear() }
    @Synchronized fun size(): Int = rows.size
}

internal data class AmbientHistoryMinute(
    val key: AmbientHistoryKey,
    val luxIntegral: Double,
    val coverageMs: Long,
    val minLux: Double,
    val maxLux: Double,
    val lastLux: Double,
    val sampleCount: Long,
    val baselineLogIntegral: Double,
    val baselineCoverageMs: Long,
) {
    val meanLux: Double get() = if (coverageMs > 0) luxIntegral / coverageMs else lastLux
    val baselineMeanLogLux: Double?
        get() = baselineCoverageMs.takeIf { it > 0 }?.let { baselineLogIntegral / it }
}
