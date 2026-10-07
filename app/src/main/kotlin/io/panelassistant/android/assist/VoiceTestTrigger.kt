package io.panelassistant.android.assist

/**
 * Triggers a one-shot voice-assistant test run ("listen once and report"), fired from `POST
 * /api/v1/voice/test` (the Configure page or an HA script). The route itself refuses the request before
 * ever calling this when the panel has no microphone capability or voice_enabled is off; [trigger] only
 * runs once both are true.
 */
fun interface VoiceTestTrigger {
    sealed interface Result {
        /** The test run was started. */
        data object Accepted : Result

        /** Refused for a reason specific to this request (e.g. a run is already in progress). */
        data class Refused(val reason: String) : Result

        /** The trigger itself has nothing to run against yet (not wired, pipeline runtime not started). */
        data class Unavailable(val reason: String) : Result
    }

    fun trigger(): Result
}
