package io.panelassistant.android.assist

import org.json.JSONObject

/** One inbound WebSocket message, as far as an Assist run cares. */
internal sealed interface AssistMessage {
    data class Event(val id: Int, val event: AssistEvent) : AssistMessage
    data class Result(
        val id: Int,
        val success: Boolean,
        val code: String?,
        val message: String?,
        val result: JSONObject?,
    ) : AssistMessage

    /** Authentication frames, pongs, and anything else this run does not act on. */
    data object Other : AssistMessage
}

/**
 * The Assist pipeline wire format, in one place.
 *
 * Home Assistant reports a stage that a pipeline skipped by simply never sending its event, and it
 * moved the reply url between two events across core versions, so every field here is read
 * defensively: an absent or null member yields null rather than a default that would read as real
 * data. The parse never throws on a well-formed frame it does not recognise — an unmodelled event
 * becomes [AssistEvent.Other] and leaves the run untouched.
 */
internal object AssistPipelineJson {
    const val LIST_TYPE = "assist_pipeline/pipeline/list"
    const val RUN_TYPE = "assist_pipeline/run"

    /** Lists the pipelines the panel's non-admin token may run. */
    fun listMessage(id: Int): String = JSONObject()
        .put("id", id)
        .put("type", LIST_TYPE)
        .toString()

    fun runMessage(id: Int, request: AssistRunRequest): String {
        val input = JSONObject()
        if (AssistRunRequest.stageNeedsAudio(request.startStage)) input.put("sample_rate", request.sampleRate)
        if (
            request.startStage == AssistRunRequest.STAGE_INTENT ||
            request.startStage == AssistRunRequest.STAGE_TTS
        ) {
            request.inputText?.takeIf { it.isNotBlank() }?.let { input.put("text", it) }
        }
        request.wakeWordPhrase?.takeIf { it.isNotBlank() }?.let { input.put("wake_word_phrase", it) }
        val message = JSONObject()
            .put("id", id)
            .put("type", RUN_TYPE)
            .put("start_stage", request.startStage)
            .put("end_stage", request.endStage)
            .put("input", input)
        request.pipelineId?.takeIf { it.isNotBlank() }?.let { message.put("pipeline", it) }
        request.conversationId?.takeIf { it.isNotBlank() }?.let { message.put("conversation_id", it) }
        request.deviceId?.takeIf { it.isNotBlank() }?.let { message.put("device_id", it) }
        request.timeoutSeconds?.let { message.put("timeout", it) }
        return message.toString()
    }

    fun parseCatalog(result: JSONObject): AssistPipelineCatalog {
        val array = result.optJSONArray("pipelines")
        val pipelines = ArrayList<AssistPipeline>(array?.length() ?: 0)
        for (index in 0 until (array?.length() ?: 0)) {
            val entry = array?.optJSONObject(index) ?: continue
            val id = entry.stringOrNull("id") ?: continue
            pipelines += AssistPipeline(
                id = id,
                name = entry.stringOrNull("name") ?: id,
                language = entry.stringOrNull("language"),
                ttsLanguage = entry.stringOrNull("tts_language"),
                ttsVoice = entry.stringOrNull("tts_voice"),
            )
        }
        return AssistPipelineCatalog(pipelines, result.stringOrNull("preferred_pipeline"))
    }

    fun parseMessage(raw: String): AssistMessage {
        val message = JSONObject(raw)
        return when (message.stringOrNull("type")) {
            "event" -> {
                val event = message.optJSONObject("event") ?: return AssistMessage.Other
                AssistMessage.Event(message.optInt("id"), parseEvent(event))
            }
            "result" -> {
                val error = message.optJSONObject("error")
                AssistMessage.Result(
                    id = message.optInt("id"),
                    success = message.optBoolean("success"),
                    code = error?.stringOrNull("code"),
                    message = error?.stringOrNull("message"),
                    result = message.optJSONObject("result"),
                )
            }
            else -> AssistMessage.Other
        }
    }

    fun parseEvent(event: JSONObject): AssistEvent {
        val name = event.stringOrNull("type") ?: return AssistEvent.Other("")
        val data = event.optJSONObject("data") ?: JSONObject()
        return when (name) {
            "run-start" -> {
                val runner = data.optJSONObject("runner_data")
                val tts = data.optJSONObject("tts_output")
                AssistEvent.RunStart(
                    handlerId = runner?.intOrNull("stt_binary_handler_id"),
                    ttsUrl = tts?.stringOrNull("url"),
                    streamResponse = tts?.optBoolean("stream_response") ?: false,
                    timeoutSeconds = runner?.intOrNull("timeout"),
                )
            }
            "stt-start" -> AssistEvent.SttStart
            "stt-vad-start" -> AssistEvent.SttVadStart
            "stt-vad-end" -> AssistEvent.SttVadEnd
            "stt-end" -> AssistEvent.SttEnd(data.optJSONObject("stt_output")?.stringOrNull("text"))
            "intent-start" -> AssistEvent.IntentStart
            "intent-progress" -> AssistEvent.IntentProgress
            "intent-end" -> parseIntentEnd(data)
            "tts-start" -> AssistEvent.TtsStart
            "tts-end" -> AssistEvent.TtsEnd(data.optJSONObject("tts_output")?.stringOrNull("url"))
            "run-end" -> AssistEvent.RunEnd
            "error" -> AssistEvent.Failure(
                data.stringOrNull("code") ?: "error",
                data.stringOrNull("message") ?: "Home Assistant reported a pipeline error",
            )
            else -> AssistEvent.Other(name)
        }
    }

    private fun parseIntentEnd(data: JSONObject): AssistEvent.IntentEnd {
        val intent = data.optJSONObject("intent_output")
        val speech = intent
            ?.optJSONObject("response")
            ?.optJSONObject("speech")
            ?.optJSONObject("plain")
            ?.stringOrNull("speech")
        // Core has carried the conversation identity inside intent_output and beside it in different
        // versions; a run that reads only one of the two silently loses every follow-up exchange.
        val conversationId = intent?.stringOrNull("conversation_id") ?: data.stringOrNull("conversation_id")
        val continueConversation = (intent?.optBoolean("continue_conversation") ?: false) ||
            data.optBoolean("continue_conversation")
        return AssistEvent.IntentEnd(speech, conversationId, continueConversation)
    }

    /**
     * Home Assistant returns reply media as a site-relative path. Resolving it against the session's
     * own base url keeps playback on the endpoint the panel is authenticated against.
     */
    fun resolveMediaUrl(baseUrl: String, url: String): String {
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) return url
        val base = baseUrl.trim().trimEnd('/')
        return if (url.startsWith("/")) base + url else "$base/$url"
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.intOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)
}
