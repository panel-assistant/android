package io.panelassistant.android.control

internal data class AutoSleepActuationGate(
    val allowAutomaticWake: Boolean,
    val allowAutomaticSleep: Boolean,
) {
    companion object {
        val AUTOMATIC = AutoSleepActuationGate(allowAutomaticWake = true, allowAutomaticSleep = true)
        val MANUAL_OVERRIDE = AutoSleepActuationGate(allowAutomaticWake = false, allowAutomaticSleep = false)
    }
}

internal enum class AutoSleepAutomaticAction { NONE, WAKE, SLEEP }

internal fun AutoSleepDecision.actionFor(
    screenIsAwake: Boolean,
    gate: AutoSleepActuationGate,
): AutoSleepAutomaticAction = when {
    screenIsAwake && output == AutoSleepOutput.ALLOW_SLEEP && gate.allowAutomaticSleep ->
        AutoSleepAutomaticAction.SLEEP
    !screenIsAwake && output != AutoSleepOutput.ALLOW_SLEEP && gate.allowAutomaticWake ->
        AutoSleepAutomaticAction.WAKE
    else -> AutoSleepAutomaticAction.NONE
}

/** Test-only convenience around the immutable production reducer. */
internal class TestAutoSleepPolicy(
    sourceIds: Set<String>,
    config: AutoSleepPolicyConfig = AutoSleepPolicyConfig(),
) {
    private var state = AutoSleepPolicyReducer.initial(sourceIds, config)

    fun transition(event: AutoSleepEvent): AutoSleepDecision {
        val transition = AutoSleepPolicyReducer.reduce(state, event)
        state = transition.state
        return transition.decision
    }

    fun advanceTo(atMs: Long): AutoSleepDecision = transition(AutoSleepEvent.TimeAdvanced(atMs))
}
