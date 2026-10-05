package io.panelassistant.android.http

import io.panelassistant.android.util.Json
import io.ktor.http.HttpStatusCode

/** Maps an [io.panelassistant.android.assist.AssistPipelineDirectory.Result] to the exact
 *  `GET /api/v1/voice/pipelines` response, pure so every branch is unit-testable without a routed
 *  request. */
internal fun voicePipelinesResponse(
    result: io.panelassistant.android.assist.AssistPipelineDirectory.Result,
): Pair<HttpStatusCode, String> = when (result) {
    is io.panelassistant.android.assist.AssistPipelineDirectory.Result.Available -> {
        val pipelines = result.pipelines.joinToString(",") {
            "{\"id\":${Json.str(it.id)},\"name\":${Json.str(it.name)}}"
        }
        HttpStatusCode.OK to "{\"pipelines\":[$pipelines],\"preferred\":${Json.str(result.preferred)}}"
    }
    is io.panelassistant.android.assist.AssistPipelineDirectory.Result.NotConfigured ->
        HttpStatusCode.ServiceUnavailable to "{\"error\":\"not-configured\",\"reason\":${Json.str(result.reason)}}"
    is io.panelassistant.android.assist.AssistPipelineDirectory.Result.Unavailable ->
        HttpStatusCode.ServiceUnavailable to "{\"error\":\"unavailable\",\"reason\":${Json.str(result.reason)}}"
}

/** `GET /api/v1/voice/microphone`: `{presence, check, detail}`, detail null unless the capture gave one. */
internal fun voiceMicrophoneJson(status: io.panelassistant.android.audio.MicrophoneStatus): String =
    "{\"presence\":${Json.str(status.presence.wireValue)},\"check\":${Json.str(status.check.wireValue)}," +
        "\"detail\":${status.detail?.let(Json::str) ?: "null"}}"

/** Refuses `POST /api/v1/voice/test` before the trigger is ever called — returns the 409 reason, or
 *  null to proceed. Checked ahead of [io.panelassistant.android.assist.VoiceTestTrigger] so a disabled
 *  or capability-less panel never depends on whether the coordinator lane happens to be wired up. */
internal fun voiceTestRefusal(hasMicrophone: Boolean, voiceEnabled: Boolean): String? = when {
    !hasMicrophone -> "this panel has no microphone capability"
    !voiceEnabled -> "voice assistant is disabled"
    else -> null
}

/** Refuses `GET /api/v1/voice/pipelines` before [io.panelassistant.android.assist.AssistPipelineDirectory]
 *  is ever called — returns the reason to report as the existing `{"error":"unavailable",reason}` 503
 *  shape ([voicePipelinesResponse]'s `Unavailable` branch), or null to proceed to the directory. The
 *  route's docs and OpenAPI both promise this endpoint "requires a microphone-capable panel" — the
 *  directory itself has no live capability signal, so the capability-less case must be checked here,
 *  exactly like [voiceTestRefusal] checks it ahead of the trigger. */
internal fun voicePipelinesRefusal(hasMicrophone: Boolean): String? =
    if (!hasMicrophone) "this panel has no microphone capability" else null

/** Maps a [io.panelassistant.android.assist.VoiceTestTrigger.Result] to the exact
 *  `POST /api/v1/voice/test` response, pure so every branch is unit-testable without a routed request. */
internal fun voiceTestTriggerResponse(
    result: io.panelassistant.android.assist.VoiceTestTrigger.Result,
): Pair<HttpStatusCode, String> = when (result) {
    is io.panelassistant.android.assist.VoiceTestTrigger.Result.Accepted ->
        HttpStatusCode.Accepted to "{\"accepted\":true}"
    is io.panelassistant.android.assist.VoiceTestTrigger.Result.Refused ->
        HttpStatusCode.Conflict to "{\"reason\":${Json.str(result.reason)}}"
    is io.panelassistant.android.assist.VoiceTestTrigger.Result.Unavailable ->
        HttpStatusCode.ServiceUnavailable to "{\"reason\":${Json.str(result.reason)}}"
}
