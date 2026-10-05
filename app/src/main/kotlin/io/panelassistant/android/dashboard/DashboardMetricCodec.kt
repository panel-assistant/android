package io.panelassistant.android.dashboard

import io.panelassistant.android.metrics.DashboardMetrics

/**
 * Converts between the typed performance projection and the id/value form stored in a bucket payload.
 *
 * This is the only place that knows a metric id corresponds to a named field, so adding a probe touches
 * the metric table and this mapping and nothing else — in particular no schema, which is the point.
 * A metric absent from a bucket reads as zero, exactly as an unwritten fixed column did.
 */
internal object DashboardMetricCodec {
    fun values(filterActive: Boolean, entityCount: Int, totals: TrafficTotals): Map<Int, Long> = buildMap {
        put(DashboardMetrics.FILTER_ACTIVE, if (filterActive) 1L else 0L)
        put(DashboardMetrics.ENTITY_COUNT, entityCount.toLong())
        put(DashboardMetrics.SAMPLE_MS, totals.sampleMs)
        put(DashboardMetrics.FRAMES, totals.frames)
        put(DashboardMetrics.PAYLOAD_BYTES, totals.payloadBytes)
        put(DashboardMetrics.UPDATES, totals.updates)
        put(DashboardMetrics.HYDRATION_UPDATES, totals.hydrationUpdates)
        put(DashboardMetrics.OBSERVER_MICROS, totals.observerMicros)
        put(DashboardMetrics.DROPPED_FRAMES, totals.droppedFrames)
        put(DashboardMetrics.STATE_TASK_MICROS, totals.stateTaskMicros)
        put(DashboardMetrics.STATE_TASK_MAX_MICROS, totals.stateTaskMaxMicros)
        put(DashboardMetrics.INTERACTION_COUNT, totals.interactionCount)
        put(DashboardMetrics.INTERACTION_MAX_MICROS, totals.interactionMaxMicros)
        put(DashboardMetrics.INPUT_DELAY_MICROS, totals.inputDelayMicros)
        put(DashboardMetrics.INTERACTION_PROCESSING_MICROS, totals.interactionProcessingMicros)
        put(DashboardMetrics.PRESENTATION_MICROS, totals.presentationMicros)
        put(DashboardMetrics.LOAF_COUNT, totals.loafCount)
        put(DashboardMetrics.BLOCKING_MICROS, totals.blockingMicros)
        put(DashboardMetrics.LOAF_MAX_MICROS, totals.loafMaxMicros)
        put(DashboardMetrics.SCRIPT_MICROS, totals.scriptMicros)
        put(DashboardMetrics.RENDER_MICROS, totals.renderMicros)
        put(DashboardMetrics.LONG_TASK_COUNT, totals.longTaskCount)
    }

    fun minute(minute: Long, values: Map<Int, Long>): DashboardPerformanceMinute {
        fun at(id: Int): Long = values[id] ?: 0L
        return DashboardPerformanceMinute(
            minute = minute,
            filterActive = at(DashboardMetrics.FILTER_ACTIVE) != 0L,
            entityCount = at(DashboardMetrics.ENTITY_COUNT).toInt(),
            totals = TrafficTotals(
                sampleMs = at(DashboardMetrics.SAMPLE_MS),
                frames = at(DashboardMetrics.FRAMES),
                payloadBytes = at(DashboardMetrics.PAYLOAD_BYTES),
                updates = at(DashboardMetrics.UPDATES),
                hydrationUpdates = at(DashboardMetrics.HYDRATION_UPDATES),
                observerMicros = at(DashboardMetrics.OBSERVER_MICROS),
                droppedFrames = at(DashboardMetrics.DROPPED_FRAMES),
                stateTaskMicros = at(DashboardMetrics.STATE_TASK_MICROS),
                stateTaskMaxMicros = at(DashboardMetrics.STATE_TASK_MAX_MICROS),
                interactionCount = at(DashboardMetrics.INTERACTION_COUNT),
                interactionMaxMicros = at(DashboardMetrics.INTERACTION_MAX_MICROS),
                inputDelayMicros = at(DashboardMetrics.INPUT_DELAY_MICROS),
                interactionProcessingMicros = at(DashboardMetrics.INTERACTION_PROCESSING_MICROS),
                presentationMicros = at(DashboardMetrics.PRESENTATION_MICROS),
                loafCount = at(DashboardMetrics.LOAF_COUNT),
                blockingMicros = at(DashboardMetrics.BLOCKING_MICROS),
                loafMaxMicros = at(DashboardMetrics.LOAF_MAX_MICROS),
                scriptMicros = at(DashboardMetrics.SCRIPT_MICROS),
                renderMicros = at(DashboardMetrics.RENDER_MICROS),
                longTaskCount = at(DashboardMetrics.LONG_TASK_COUNT),
            ),
        )
    }
}
