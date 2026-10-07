package io.panelassistant.android.assist

/** One Assist pipeline as Home Assistant lists it to a non-admin client. */
internal data class AssistPipeline(
    val id: String,
    val name: String,
    val ttsLanguage: String? = null,
)

/** The pipelines this instance offers, plus the one it prefers when the caller names none. */
internal data class AssistPipelineCatalog(
    val pipelines: List<AssistPipeline>,
    val preferredId: String?,
)

/** Why a run ended badly. Carried in the outcome; never thrown. */
internal data class AssistError(val code: String, val message: String) {
    /**
     * Home Assistant reports the same wake word twice when two satellites hear one phrase. It is an
     * ordinary outcome of a room with more than one microphone, so it never reaches the user.
     */
    val silent: Boolean get() = code == DUPLICATE_WAKE_UP

    internal companion object {
        const val DUPLICATE_WAKE_UP = "duplicate_wake_up_detected"
    }
}

/** Everything one run produced. */
internal data class AssistOutcome(
    val continueConversation: Boolean = false,
    val ttsUrl: String? = null,
    val error: AssistError? = null,
) {
    val failed: Boolean get() = error != null
}

/** A pipeline event of a text-to-speech run, already parsed off the wire. */
internal sealed interface AssistEvent {
    data class TtsEnd(val url: String?) : AssistEvent
    data object RunEnd : AssistEvent
    data class Failure(val code: String, val message: String) : AssistEvent

    /** A name this build does not model. Kept so an unknown event is inert, never fatal. */
    data class Other(val name: String) : AssistEvent
}
