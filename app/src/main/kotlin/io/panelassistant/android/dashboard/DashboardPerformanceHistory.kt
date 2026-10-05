package io.panelassistant.android.dashboard

import org.json.JSONArray
import org.json.JSONObject

internal fun interface DashboardPerformanceHistorySink {
    fun record(batch: EntityFilterProtocol.TrafficBatch)
}

/** Saturating Long addition shared by every dashboard traffic counter; clamps at [Long.MAX_VALUE]. */
internal fun saturatedAdd(left: Long, right: Long): Long =
    if (right >= Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

/** Per-second and millisecond projections shared by the live window and the minute rollup. */
internal fun trafficRatePerSec(value: Long, sampleMs: Long): Double = value * 1000.0 / sampleMs.coerceAtLeast(1L)
internal fun trafficMicrosPerSecond(value: Long, sampleMs: Long): Double = value.toDouble() / sampleMs.coerceAtLeast(1L)
internal fun trafficMicrosToMs(value: Long): Double = value / 1000.0

internal data class DashboardPerformanceSample(
    val instance: String,
    val path: String,
    val minute: Long,
    val filterActive: Boolean,
    val entityCount: Int,
    val batch: EntityFilterProtocol.TrafficBatch,
)

internal data class DashboardPerformanceKey(
    val instance: String,
    val path: String,
    val minute: Long,
)

/**
 * Canonical accumulated traffic totals shared by the in-memory minute aggregate and the persisted
 * minute readout. Sums are plain (non-saturating), preserving the historical rollup arithmetic; the
 * worst single interaction is retained together with its component input/processing/presentation
 * timings.
 */
internal data class TrafficTotals(
    val sampleMs: Long = 0,
    val frames: Long = 0,
    val payloadBytes: Long = 0,
    val updates: Long = 0,
    val hydrationUpdates: Long = 0,
    val observerMicros: Long = 0,
    val droppedFrames: Long = 0,
    val stateTaskMicros: Long = 0,
    val stateTaskMaxMicros: Long = 0,
    val interactionCount: Long = 0,
    val interactionMaxMicros: Long = 0,
    val inputDelayMicros: Long = 0,
    val interactionProcessingMicros: Long = 0,
    val presentationMicros: Long = 0,
    val loafCount: Long = 0,
    val blockingMicros: Long = 0,
    val loafMaxMicros: Long = 0,
    val scriptMicros: Long = 0,
    val renderMicros: Long = 0,
    val longTaskCount: Long = 0,
) {
    fun accumulate(batch: EntityFilterProtocol.TrafficBatch): TrafficTotals {
        val worse = batch.interactionMaxMicros > interactionMaxMicros
        return copy(
            sampleMs = sampleMs + batch.sampleMs,
            frames = frames + batch.frames,
            payloadBytes = payloadBytes + batch.payloadBytes,
            updates = updates + batch.entityUpdates,
            hydrationUpdates = hydrationUpdates + batch.hydrationUpdates,
            observerMicros = observerMicros + batch.observerMicros,
            droppedFrames = droppedFrames + batch.droppedFrames,
            stateTaskMicros = stateTaskMicros + batch.stateTaskMicros,
            stateTaskMaxMicros = maxOf(stateTaskMaxMicros, batch.stateTaskMaxMicros),
            interactionCount = interactionCount + batch.interactionBins.sum(),
            interactionMaxMicros = if (worse) batch.interactionMaxMicros else interactionMaxMicros,
            inputDelayMicros = if (worse) batch.inputDelayMicros else inputDelayMicros,
            interactionProcessingMicros = if (worse) batch.interactionProcessingMicros else interactionProcessingMicros,
            presentationMicros = if (worse) batch.presentationMicros else presentationMicros,
            loafCount = loafCount + batch.loafCount,
            blockingMicros = blockingMicros + batch.blockingMicros,
            loafMaxMicros = maxOf(loafMaxMicros, batch.loafMaxMicros),
            scriptMicros = scriptMicros + batch.scriptMicros,
            renderMicros = renderMicros + batch.renderMicros,
            longTaskCount = longTaskCount + batch.longTaskCount,
        )
    }

    fun rate(value: Long): Double = trafficRatePerSec(value, sampleMs)
    fun microsPerSecond(value: Long): Double = trafficMicrosPerSecond(value, sampleMs)
}

/** One in-memory minute rollup. Five-second browser batches merge here before SQLite is touched. */
internal data class DashboardPerformanceAggregate(
    val key: DashboardPerformanceKey,
    var filterActive: Boolean,
    var entityCount: Int,
    var totals: TrafficTotals = TrafficTotals(),
) {
    fun merge(sample: DashboardPerformanceSample) {
        require(key == DashboardPerformanceKey(sample.instance, sample.path, sample.minute))
        filterActive = sample.filterActive
        entityCount = sample.entityCount
        totals = totals.accumulate(sample.batch)
    }

    companion object {
        fun from(sample: DashboardPerformanceSample): DashboardPerformanceAggregate =
            DashboardPerformanceAggregate(
                key = DashboardPerformanceKey(sample.instance, sample.path, sample.minute),
                filterActive = sample.filterActive,
                entityCount = sample.entityCount,
            ).also { it.merge(sample) }
    }
}

/** Bounded insertion-ordered minute accumulator; overflow retains the newest target-minutes. */
internal class DashboardPerformanceAccumulator(private val maxEntries: Int) {
    private val entries = LinkedHashMap<DashboardPerformanceKey, DashboardPerformanceAggregate>()

    init {
        require(maxEntries > 0)
    }

    /** Returns the evicted aggregate when a new distinct target-minute exceeds the bound. */
    fun add(sample: DashboardPerformanceSample): DashboardPerformanceAggregate? {
        val key = DashboardPerformanceKey(sample.instance, sample.path, sample.minute)
        entries[key]?.merge(sample) ?: run { entries[key] = DashboardPerformanceAggregate.from(sample) }
        if (entries.size <= maxEntries) return null
        val oldest = entries.entries.first()
        entries.remove(oldest.key)
        return oldest.value
    }

    fun drain(): List<DashboardPerformanceAggregate> =
        entries.values.toList().also { entries.clear() }

    fun isNotEmpty(): Boolean = entries.isNotEmpty()
    fun size(): Int = entries.size
}

internal data class DashboardPerformanceMinute(
    val minute: Long,
    val filterActive: Boolean,
    val entityCount: Int,
    val totals: TrafficTotals,
) {
    fun json(): JSONObject = JSONObject()
        .put("minute", minute)
        .put("filterActive", filterActive)
        .put("entityCount", entityCount)
        .put("sampleMs", totals.sampleMs)
        .put("updatesPerSec", totals.rate(totals.updates))
        .put("payloadBytesPerSec", totals.rate(totals.payloadBytes))
        .put("mainThreadMsPerSec", totals.microsPerSecond(totals.stateTaskMicros))
        .put("blockedMsPerSec", totals.microsPerSecond(totals.blockingMicros))
        .put("interactionCount", totals.interactionCount)
        .put("worstInteractionMs", trafficMicrosToMs(totals.interactionMaxMicros))
        .put("longestStateTaskMs", trafficMicrosToMs(totals.stateTaskMaxMicros))
        .put("longestFrameMs", trafficMicrosToMs(totals.loafMaxMicros))
        .put("frames", totals.frames)
        .put("hydrationUpdates", totals.hydrationUpdates)
        .put("observerMicros", totals.observerMicros)
        .put("droppedFrames", totals.droppedFrames)
        .put("loafCount", totals.loafCount)
        .put("longTaskCount", totals.longTaskCount)
        .put("scriptMsPerSec", totals.microsPerSecond(totals.scriptMicros))
        .put("renderMsPerSec", totals.microsPerSecond(totals.renderMicros))
        .put("slowestInteraction", JSONObject()
            .put("inputDelayMs", trafficMicrosToMs(totals.inputDelayMicros))
            .put("processingMs", trafficMicrosToMs(totals.interactionProcessingMicros))
            .put("presentationMs", trafficMicrosToMs(totals.presentationMicros)))
}

internal fun dashboardPerformanceHistoryJson(
    rows: List<DashboardPerformanceMinute>,
    retentionDays: Int,
): String = JSONObject()
    .put("schema", 1)
    .put("retentionDays", retentionDays)
    .put("resolution", "minute")
    .put("samples", JSONArray(rows.map(DashboardPerformanceMinute::json)))
    .toString()
