package io.github.maxlyth.hapaneld.control

internal enum class AutoSleepSourceState { ON, OFF, UNAVAILABLE }
internal enum class AutoSleepOutput { HOLD_AWAKE, ALLOW_SLEEP, INHIBITED }
internal enum class AutoSleepReason {
    NO_SOURCES_CONFIGURED,
    ALL_SOURCES_UNAVAILABLE,
    SOURCE_ACTIVE,
    PARTIAL_SOURCE_LOSS,
    SOURCE_ACTIVITY_LEASE,
    TOUCH_ACTIVITY,
    /** The controller woke its own automatic sleep because its sources stopped being live. */
    SOURCE_LOSS_WAKE,
    PROXIMITY_ACTIVITY,
    LEASE_EXPIRED,
}
internal data class AutoSleepPolicyConfig(
    val learnedLeaseMs: Long = MIN_AUTO_SLEEP_LEASE_MS,
    val qualifiedProximityExtensionMs: Long = DEFAULT_PROXIMITY_EXTENSION_MS,
    val fixedLeaseMs: Long? = null,
)
internal data class AutoSleepFeedPosition(val generation: Long, val revision: Long)
internal data class AutoSleepActivityMarker(val sequence: Long, val atMs: Long)
/** A hydrated aggregate may include an activity edge no longer visible in its final source states. */
internal sealed class AutoSleepEvent(open val atMs: Long) {
    data class SourcesHydrated(
        override val atMs: Long,
        val states: Map<String, AutoSleepSourceState>,
        val feed: AutoSleepFeedPosition,
        val activityMarker: AutoSleepActivityMarker? = null,
    ) : AutoSleepEvent(atMs)
    data class Touch(override val atMs: Long, val wokeOwnedAutomaticSleep: Boolean = false) : AutoSleepEvent(atMs)
    data class ScreenWoken(
        override val atMs: Long,
        val reason: AutoSleepReason = AutoSleepReason.TOUCH_ACTIVITY,
    ) : AutoSleepEvent(atMs)
    data class QualifiedProximity(override val atMs: Long) : AutoSleepEvent(atMs)
    data class AutomaticSleepRecorded(override val atMs: Long) : AutoSleepEvent(atMs)
    data class LearnedLeaseChanged(override val atMs: Long, val learnedLeaseMs: Long) : AutoSleepEvent(atMs)
    data class TimeAdvanced(override val atMs: Long) : AutoSleepEvent(atMs)
}
internal data class AutoSleepDecision(
    val atMs: Long,
    val output: AutoSleepOutput,
    val reason: AutoSleepReason,
    val nextDeadlineMs: Long?,
    val effectiveLeaseMs: Long,
    val healthySourceCount: Int,
    val unavailableSourceCount: Int,
)
internal data class AutoSleepPolicyState(
    val sources: Map<String, AutoSleepSourceState>,
    val learnedLeaseMs: Long,
    val proximityExtensionMs: Long,
    val fixedLeaseMs: Long? = null,
    val correctionFloorMs: Long = MIN_AUTO_SLEEP_LEASE_MS,
    val awakeUntilMs: Long? = null,
    val leaseReason: AutoSleepReason = AutoSleepReason.SOURCE_ACTIVITY_LEASE,
    val lastAutomaticSleepMs: Long? = null,
    val feed: AutoSleepFeedPosition? = null,
    val activityMarker: AutoSleepActivityMarker? = null,
    val lastEventAtMs: Long? = null,
)
internal data class AutoSleepTransition(val state: AutoSleepPolicyState, val decision: AutoSleepDecision)
/** Pure policy authority: the returned state is the complete memory of the next reduction. */
internal object AutoSleepPolicyReducer {
    fun initial(sourceIds: Set<String>, config: AutoSleepPolicyConfig = AutoSleepPolicyConfig()): AutoSleepPolicyState {
        require(sourceIds.none(String::isBlank)) { "Auto-sleep source IDs must not be blank" }
        return AutoSleepPolicyState(
            sources = sourceIds.associateWith { AutoSleepSourceState.UNAVAILABLE },
            learnedLeaseMs = config.learnedLeaseMs.coerceIn(MIN_AUTO_SLEEP_LEASE_MS, MAX_AUTO_SLEEP_LEASE_MS),
            proximityExtensionMs = config.qualifiedProximityExtensionMs.coerceIn(0L, MAX_PROXIMITY_EXTENSION_MS),
            fixedLeaseMs = config.fixedLeaseMs?.coerceIn(5_000L, 86_400_000L),
        )
    }
    fun reduce(previous: AutoSleepPolicyState, event: AutoSleepEvent): AutoSleepTransition {
        require(event.atMs >= 0L) { "Auto-sleep event time must be non-negative" }
        val current = previous.copy(lastEventAtMs = maxOf(event.atMs, previous.lastEventAtMs ?: 0L))
        val next = when (event) {
            is AutoSleepEvent.SourcesHydrated -> sources(current, event.states, event.feed, event.activityMarker)
            is AutoSleepEvent.Touch -> touch(current, event.wokeOwnedAutomaticSleep)
            is AutoSleepEvent.ScreenWoken -> extend(current, current.effectiveLeaseMs(), event.reason)
            is AutoSleepEvent.QualifiedProximity -> if (current.proximityExtensionMs == 0L) current else {
                extend(current, current.proximityExtensionMs, AutoSleepReason.PROXIMITY_ACTIVITY)
            }
            is AutoSleepEvent.AutomaticSleepRecorded ->
                if (decision(current).output == AutoSleepOutput.ALLOW_SLEEP) {
                    current.copy(lastAutomaticSleepMs = current.lastEventAtMs)
                } else current
            is AutoSleepEvent.LearnedLeaseChanged -> current.copy(
                learnedLeaseMs = event.learnedLeaseMs.coerceIn(MIN_AUTO_SLEEP_LEASE_MS, MAX_AUTO_SLEEP_LEASE_MS),
            )
            is AutoSleepEvent.TimeAdvanced -> current
        }
        return AutoSleepTransition(next, decision(next))
    }
    private fun sources(
        current: AutoSleepPolicyState,
        states: Map<String, AutoSleepSourceState>,
        feed: AutoSleepFeedPosition,
        suppliedMarker: AutoSleepActivityMarker?,
    ): AutoSleepPolicyState {
        require(states.keys == current.sources.keys) { "Hydration must cover the configured auto-sleep sources exactly" }
        require(feed.generation >= 0L && feed.revision >= 0L) { "Auto-sleep feed position must be non-negative" }
        require(current.feed == null || feed.generation >= current.feed.generation) { "Stale auto-sleep feed generation" }
        val newGeneration = current.feed == null || feed.generation > current.feed.generation
        require(newGeneration || feed.revision >= current.feed.revision) { "Stale auto-sleep feed revision" }
        val marker = suppliedMarker ?: if (newGeneration) null else current.activityMarker
        require(marker == null || marker.sequence >= 0L && marker.atMs in 0L..current.lastEventAtMs!!) {
            "Auto-sleep activity marker must share the event's monotonic clock"
        }
        val priorMarker = current.activityMarker.takeUnless { newGeneration }
        require(marker == null || priorMarker == null || marker == priorMarker ||
            marker.sequence > priorMarker.sequence && marker.atMs >= priorMarker.atMs
        ) { "Stale auto-sleep activity marker" }
        if (!newGeneration && feed.revision == current.feed.revision) {
            require(states == current.sources && marker == priorMarker) { "Conflicting auto-sleep feed revision" }
        }
        val changed = states != current.sources
        val activityChanged = marker != null && marker != priorMarker
        var next = current.copy(
            sources = states.toMap(),
            feed = feed,
            activityMarker = marker,
        )
        val healthy = states.values.count { it != AutoSleepSourceState.UNAVAILABLE }
        if (healthy == 0 || states.values.any { it == AutoSleepSourceState.ON }) {
            return next.copy(awakeUntilMs = null)
        }
        val anchor = when {
            newGeneration || changed -> current.lastEventAtMs
            activityChanged -> marker.atMs
            else -> null
        } ?: return next
        val partial = healthy < states.size
        next = extend(
            next,
            if (partial) MAX_AUTO_SLEEP_LEASE_MS else next.effectiveLeaseMs(),
            if (partial) AutoSleepReason.PARTIAL_SOURCE_LOSS else AutoSleepReason.SOURCE_ACTIVITY_LEASE,
            anchor,
        )
        return next
    }
    private fun touch(current: AutoSleepPolicyState, wokeOwnedAutomaticSleep: Boolean): AutoSleepPolicyState {
        val sleptAt = current.lastAutomaticSleepMs
        val prompt = wokeOwnedAutomaticSleep && sleptAt != null &&
            current.lastEventAtMs!! - sleptAt in 0L..PREMATURE_TOUCH_WINDOW_MS
        val corrected = if (prompt && current.fixedLeaseMs == null) {
            current.copy(
                correctionFloorMs = safeAdd(current.effectiveLeaseMs(), PREMATURE_TOUCH_CORRECTION_MS)
                    .coerceAtMost(MAX_AUTO_SLEEP_LEASE_MS),
                lastAutomaticSleepMs = null,
            )
        } else current.copy(lastAutomaticSleepMs = null)
        return extend(corrected, corrected.effectiveLeaseMs(), AutoSleepReason.TOUCH_ACTIVITY)
    }
    private fun extend(
        current: AutoSleepPolicyState,
        durationMs: Long,
        reason: AutoSleepReason,
        anchorMs: Long = current.lastEventAtMs!!,
    ): AutoSleepPolicyState {
        val deadline = safeAdd(anchorMs, durationMs)
        return if (current.awakeUntilMs == null || deadline > current.awakeUntilMs) {
            current.copy(awakeUntilMs = deadline, leaseReason = reason)
        } else {
            current
        }
    }
    private fun decision(state: AutoSleepPolicyState): AutoSleepDecision {
        val healthy = state.sources.values.count { it != AutoSleepSourceState.UNAVAILABLE }
        val unavailable = state.sources.size - healthy
        val atMs = state.lastEventAtMs ?: 0L
        val (output, reason, deadline) = when {
            state.sources.isEmpty() -> Triple(AutoSleepOutput.INHIBITED, AutoSleepReason.NO_SOURCES_CONFIGURED, null)
            state.feed == null || healthy == 0 ->
                Triple(AutoSleepOutput.INHIBITED, AutoSleepReason.ALL_SOURCES_UNAVAILABLE, null)
            state.sources.values.any { it == AutoSleepSourceState.ON } ->
                Triple(AutoSleepOutput.HOLD_AWAKE, AutoSleepReason.SOURCE_ACTIVE, null)
            state.awakeUntilMs != null && atMs < state.awakeUntilMs -> Triple(
                AutoSleepOutput.HOLD_AWAKE,
                if (unavailable > 0) AutoSleepReason.PARTIAL_SOURCE_LOSS else state.leaseReason,
                state.awakeUntilMs,
            )
            else -> Triple(AutoSleepOutput.ALLOW_SLEEP, AutoSleepReason.LEASE_EXPIRED, null)
        }
        return AutoSleepDecision(
            atMs, output, reason, deadline,
            if (unavailable in 1 until state.sources.size) MAX_AUTO_SLEEP_LEASE_MS else state.effectiveLeaseMs(),
            healthy, unavailable,
        )
    }
    private fun AutoSleepPolicyState.effectiveLeaseMs() = fixedLeaseMs ?: maxOf(learnedLeaseMs, correctionFloorMs)
    private fun safeAdd(value: Long, increment: Long) =
        if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment
}
internal const val MIN_AUTO_SLEEP_LEASE_MS = 10L * 60_000L
internal const val MAX_AUTO_SLEEP_LEASE_MS = 60L * 60_000L
internal const val DEFAULT_PROXIMITY_EXTENSION_MS = 5L * 60_000L
internal const val MAX_PROXIMITY_EXTENSION_MS = 30L * 60_000L
internal const val PREMATURE_TOUCH_WINDOW_MS = 2L * 60_000L
internal const val PREMATURE_TOUCH_CORRECTION_MS = 5L * 60_000L
