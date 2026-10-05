package io.panelassistant.android.control

import io.panelassistant.android.sensors.HaPresenceSelectedHistory
import io.panelassistant.android.sensors.HaPresenceValue

internal enum class AutoSleepTraceCause { EVENT, DEADLINE, END }

internal data class AutoSleepTracePoint(
    val atMs: Long,
    val cause: AutoSleepTraceCause,
    val decision: AutoSleepDecision,
)

internal data class AutoSleepReplaySegment(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val output: AutoSleepOutput,
    val reason: AutoSleepReason,
)

internal data class AutoSleepSourceReplaySegment(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val value: HaPresenceValue,
)

/**
 * Deterministic replay of the production policy, including its internally scheduled lease deadline.
 * Events sharing an instant retain their supplied order; callers which combine independent feeds
 * should coalesce them into one complete [AutoSleepEvent.SourcesHydrated] event first.
 */
internal object AutoSleepReplay {
    /**
     * Crops each selected source to the visible window. History is read with a warm-up interval, so
     * the last value before [startEpochMs] becomes the lane's initial value. Equal adjacent values
     * are coalesced; source count is not capped here because transport-wide row/byte limits already
     * bound the input independently of how well provisioned an Area is.
     */
    fun sourceSegments(
        history: HaPresenceSelectedHistory,
        startEpochMs: Long,
        endEpochMs: Long,
    ): Map<String, List<AutoSleepSourceReplaySegment>> {
        require(startEpochMs < endEpochMs)
        return history.discoveredSourceIds.toSortedSet().associateWithTo(linkedMapOf()) { sourceId ->
            val transitions = history.transitions[sourceId].orEmpty().sortedBy { it.atEpochMs }
            var value = HaPresenceValue.UNAVAILABLE
            var cursor = startEpochMs
            val out = mutableListOf<AutoSleepSourceReplaySegment>()
            transitions.forEach { transition ->
                if (transition.atEpochMs <= startEpochMs) {
                    value = transition.value
                    return@forEach
                }
                if (transition.atEpochMs >= endEpochMs) return@forEach
                if (transition.value == value) return@forEach
                if (transition.atEpochMs > cursor) {
                    out += AutoSleepSourceReplaySegment(cursor, transition.atEpochMs, value)
                }
                cursor = transition.atEpochMs
                value = transition.value
            }
            if (cursor < endEpochMs) out += AutoSleepSourceReplaySegment(cursor, endEpochMs, value)
            out
        }
    }

    /** Bounded all-source projection. Any ON overlap keeps a short motion pulse visible. */
    fun bucketSourceSegments(
        history: HaPresenceSelectedHistory,
        startEpochMs: Long,
        endEpochMs: Long,
        bucketMs: Long,
    ): Map<String, List<AutoSleepSourceReplaySegment>> {
        require(bucketMs > 0L)
        return sourceSegments(history, startEpochMs, endEpochMs).mapValuesTo(linkedMapOf()) { (_, exact) ->
            val out = mutableListOf<AutoSleepSourceReplaySegment>()
            var bucketStart = startEpochMs
            var exactIndex = 0
            while (bucketStart < endEpochMs) {
                val bucketEnd = minOf(endEpochMs, bucketStart + bucketMs)
                while (exactIndex < exact.size && exact[exactIndex].endEpochMs <= bucketStart) exactIndex++
                var sawOn = false
                var sawOff = false
                var sawUnavailable = false
                var scan = exactIndex
                while (scan < exact.size && exact[scan].startEpochMs < bucketEnd) {
                    sawOn = sawOn || exact[scan].value == HaPresenceValue.ON
                    sawOff = sawOff || exact[scan].value == HaPresenceValue.OFF
                    sawUnavailable = sawUnavailable || exact[scan].value == HaPresenceValue.UNAVAILABLE
                    scan++
                }
                val value = when {
                    sawOn -> HaPresenceValue.ON
                    sawUnavailable -> HaPresenceValue.UNAVAILABLE
                    else -> HaPresenceValue.OFF
                }
                val previous = out.lastOrNull()
                if (previous != null && previous.value == value) {
                    out[out.lastIndex] = previous.copy(endEpochMs = bucketEnd)
                } else {
                    out += AutoSleepSourceReplaySegment(bucketStart, bucketEnd, value)
                }
                bucketStart = bucketEnd
            }
            out
        }
    }

    fun run(
        sourceIds: Set<String>,
        events: List<AutoSleepEvent>,
        untilMs: Long,
        config: AutoSleepPolicyConfig = AutoSleepPolicyConfig(),
        fromMs: Long = 0L,
    ): List<AutoSleepTracePoint> {
        require(fromMs in 0L..untilMs) { "Replay range must be non-negative and ordered" }
        require(events.zipWithNext().all { (first, second) -> second.atMs >= first.atMs }) {
            "Replay events must be ordered by non-decreasing time"
        }
        require(events.all { it.atMs in fromMs..untilMs }) { "Replay event is outside the replay range" }

        var state = AutoSleepPolicyReducer.initial(sourceIds, config)
        val trace = mutableListOf<AutoSleepTracePoint>()
        fun reduce(event: AutoSleepEvent, cause: AutoSleepTraceCause) {
            val transition = AutoSleepPolicyReducer.reduce(state, event)
            state = transition.state
            trace += AutoSleepTracePoint(event.atMs, cause, transition.decision)
        }

        reduce(AutoSleepEvent.TimeAdvanced(fromMs), AutoSleepTraceCause.EVENT)
        events.forEach { event ->
            val deadline = trace.last().decision.nextDeadlineMs
            if (deadline != null && deadline < event.atMs) {
                reduce(AutoSleepEvent.TimeAdvanced(deadline), AutoSleepTraceCause.DEADLINE)
            }
            reduce(event, AutoSleepTraceCause.EVENT)
        }

        val deadline = trace.last().decision.nextDeadlineMs
        if (deadline != null && deadline <= untilMs) {
            reduce(AutoSleepEvent.TimeAdvanced(deadline), AutoSleepTraceCause.DEADLINE)
        }
        if (trace.last().atMs != untilMs) {
            reduce(AutoSleepEvent.TimeAdvanced(untilMs), AutoSleepTraceCause.END)
        }
        return trace
    }

    /** Crops the warmed-up trace and joins adjacent intervals with the same categorical result. */
    fun segments(
        trace: List<AutoSleepTracePoint>,
        startEpochMs: Long,
        endEpochMs: Long,
    ): List<AutoSleepReplaySegment> {
        require(startEpochMs < endEpochMs)
        val coalesced = trace.fold(mutableListOf<AutoSleepTracePoint>()) { out, point ->
            if (out.lastOrNull()?.atMs == point.atMs) out[out.lastIndex] = point else out += point
            out
        }
        val intervals = coalesced.mapIndexedNotNull { index, point ->
            val start = maxOf(startEpochMs, point.atMs)
            val end = minOf(endEpochMs, coalesced.getOrNull(index + 1)?.atMs ?: endEpochMs)
            if (start >= end) null else AutoSleepReplaySegment(
                start, end, point.decision.output, point.decision.reason,
            )
        }
        return intervals.fold(mutableListOf()) { out, segment ->
            val previous = out.lastOrNull()
            if (previous != null && previous.endEpochMs == segment.startEpochMs &&
                previous.output == segment.output && previous.reason == segment.reason
            ) {
                out[out.lastIndex] = previous.copy(endEpochMs = segment.endEpochMs)
            } else out += segment
            out
        }
    }

    /**
     * Samples the exact reducer trace at each fixed bucket start. A decision transition inside a
     * bucket therefore becomes visible at the next bucket boundary; a transition exactly on the
     * boundary belongs to the new bucket. Adjacent equal samples are joined for compact JSON.
     */
    fun minuteSegments(
        trace: List<AutoSleepTracePoint>,
        startEpochMs: Long,
        endEpochMs: Long,
        bucketMs: Long = REPLAY_BUCKET_MS,
    ): List<AutoSleepReplaySegment> {
        require(startEpochMs < endEpochMs && bucketMs > 0L)
        val coalesced = trace.fold(mutableListOf<AutoSleepTracePoint>()) { out, point ->
            if (out.lastOrNull()?.atMs == point.atMs) out[out.lastIndex] = point else out += point
            out
        }
        require(coalesced.isNotEmpty() && coalesced.first().atMs <= startEpochMs) {
            "Replay trace must cover the sampled window"
        }
        var traceIndex = 0
        var decision = coalesced.first().decision
        while (traceIndex + 1 < coalesced.size && coalesced[traceIndex + 1].atMs <= startEpochMs) {
            decision = coalesced[++traceIndex].decision
        }
        val out = mutableListOf<AutoSleepReplaySegment>()
        var bucketStart = startEpochMs
        while (bucketStart < endEpochMs) {
            while (traceIndex + 1 < coalesced.size && coalesced[traceIndex + 1].atMs <= bucketStart) {
                decision = coalesced[++traceIndex].decision
            }
            val bucketEnd = minOf(endEpochMs, bucketStart + bucketMs)
            val segment = AutoSleepReplaySegment(bucketStart, bucketEnd, decision.output, decision.reason)
            val previous = out.lastOrNull()
            if (previous != null && previous.endEpochMs == segment.startEpochMs &&
                previous.output == segment.output && previous.reason == segment.reason
            ) out[out.lastIndex] = previous.copy(endEpochMs = segment.endEpochMs)
            else out += segment
            bucketStart = bucketEnd
        }
        return out
    }
}

internal const val REPLAY_BUCKET_MS = 60_000L

/** Converts all source transitions at one timestamp into one complete aggregate policy event. */
internal fun autoSleepHistoryEvents(history: HaPresenceSelectedHistory): List<AutoSleepEvent> {
    val states = history.sourceIds.associateWithTo(linkedMapOf()) { AutoSleepSourceState.UNAVAILABLE }
    var revision = 0L
    var markerSequence = 0L
    var marker: AutoSleepActivityMarker? = null
    return history.transitions.asSequence().filter { it.key in history.sourceIds }.flatMap { it.value.asSequence() }.toList()
        .groupBy { it.atEpochMs }
        .toSortedMap()
        .map { (atMs, simultaneous) ->
            simultaneous.sortedBy { it.entityId }.forEach { transition ->
                val next = when (transition.value) {
                    HaPresenceValue.ON -> AutoSleepSourceState.ON
                    HaPresenceValue.OFF -> AutoSleepSourceState.OFF
                    HaPresenceValue.UNAVAILABLE -> AutoSleepSourceState.UNAVAILABLE
                }
                if (states.getValue(transition.entityId) != AutoSleepSourceState.ON &&
                    next == AutoSleepSourceState.ON
                ) marker = AutoSleepActivityMarker(++markerSequence, atMs)
                states[transition.entityId] = next
            }
            AutoSleepEvent.SourcesHydrated(
                atMs = atMs,
                states = states.toMap(),
                feed = AutoSleepFeedPosition(0L, revision++),
                activityMarker = marker,
            )
        }
}
