package io.panelassistant.android.assist

/** One Assist pipeline as Home Assistant lists it to a non-admin client. */
internal data class AssistPipeline(
    val id: String,
    val name: String,
    val language: String? = null,
    val ttsLanguage: String? = null,
    val ttsVoice: String? = null,
)

/** The pipelines this instance offers, plus the one it prefers when the caller names none. */
internal data class AssistPipelineCatalog(
    val pipelines: List<AssistPipeline>,
    val preferredId: String?,
)

/** What one run asks the pipeline to do. */
internal data class AssistRunRequest(
    val pipelineId: String? = null,
    /** Continues an earlier exchange; supplied by the previous run's outcome. */
    val conversationId: String? = null,
    val wakeWordPhrase: String? = null,
    /** Plain text consumed when the run begins at the intent or text-to-speech stage. */
    val inputText: String? = null,
    val deviceId: String? = null,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val startStage: String = STAGE_STT,
    val endStage: String = STAGE_TTS,
    /** Server-side run deadline in seconds; Home Assistant defaults to 300 when absent. */
    val timeoutSeconds: Int? = null,
) {
    internal companion object {
        const val DEFAULT_SAMPLE_RATE = 16_000
        const val STAGE_WAKE_WORD = "wake_word"
        const val STAGE_STT = "stt"
        const val STAGE_INTENT = "intent"
        const val STAGE_TTS = "tts"

        fun stageNeedsAudio(stage: String): Boolean = stage == STAGE_WAKE_WORD || stage == STAGE_STT
    }
}

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
    val sttText: String? = null,
    val responseText: String? = null,
    val conversationId: String? = null,
    val continueConversation: Boolean = false,
    val ttsUrl: String? = null,
    val error: AssistError? = null,
) {
    val failed: Boolean get() = error != null
}

/** A pipeline event, already parsed off the wire. */
internal sealed interface AssistEvent {
    /**
     * [handlerId] is absent when the run does not start at the speech-to-text stage, in which case
     * no audio is ever wanted. [ttsUrl] appears here only on cores that pre-allocate the reply; it
     * is playable before [TtsEnd] only when [streamResponse] is set.
     */
    data class RunStart(
        val handlerId: Int?,
        val ttsUrl: String? = null,
        val streamResponse: Boolean = false,
        val timeoutSeconds: Int? = null,
    ) : AssistEvent

    data object SttStart : AssistEvent
    data object SttVadStart : AssistEvent
    data object SttVadEnd : AssistEvent
    data class SttEnd(val text: String?) : AssistEvent
    data object IntentStart : AssistEvent
    data object IntentProgress : AssistEvent
    data class IntentEnd(
        val responseText: String?,
        val conversationId: String?,
        val continueConversation: Boolean,
    ) : AssistEvent

    data object TtsStart : AssistEvent
    data class TtsEnd(val url: String?) : AssistEvent
    data object RunEnd : AssistEvent
    data class Failure(val code: String, val message: String) : AssistEvent

    /** A name this build does not model. Kept so an unknown event is inert, never fatal. */
    data class Other(val name: String) : AssistEvent
}
