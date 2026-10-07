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
    private const val STAGE_TTS = "tts"

    /** Lists the pipelines the panel's non-admin token may run. */
    fun listMessage(id: Int): String = JSONObject()
        .put("id", id)
        .put("type", LIST_TYPE)
        .toString()

    /** A text-to-speech-only run: [pipelineId] speaks [text]. */
    fun runMessage(id: Int, pipelineId: String, text: String): String {
        val input = JSONObject()
        text.takeIf { it.isNotBlank() }?.let { input.put("text", it) }
        val message = JSONObject()
            .put("id", id)
            .put("type", RUN_TYPE)
            .put("start_stage", STAGE_TTS)
            .put("end_stage", STAGE_TTS)
            .put("input", input)
        pipelineId.takeIf { it.isNotBlank() }?.let { message.put("pipeline", it) }
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
                ttsLanguage = entry.stringOrNull("tts_language"),
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
            "tts-end" -> AssistEvent.TtsEnd(data.optJSONObject("tts_output")?.stringOrNull("url"))
            "run-end" -> AssistEvent.RunEnd
            "error" -> AssistEvent.Failure(
                data.stringOrNull("code") ?: "error",
                data.stringOrNull("message") ?: "Home Assistant reported a pipeline error",
            )
            else -> AssistEvent.Other(name)
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
