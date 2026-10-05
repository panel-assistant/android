package io.panelassistant.android.http

import io.panelassistant.android.util.ByteLimitExceeded
import io.panelassistant.android.util.Json
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_WAKE_WORD_IMPORT_BYTES = 4L * 1024L * 1024L
private const val WAKE_WORD_IMPORT_DEADLINE_MS = 30_000L

internal fun Route.voiceRoutes(
    hasMicrophone: () -> Boolean,
    voiceEnabled: () -> Boolean,
    assistPipelines: io.panelassistant.android.assist.AssistPipelineDirectory,
    voiceTest: io.panelassistant.android.assist.VoiceTestTrigger,
    wakeWords: io.panelassistant.android.assist.wakeword.WakeWordCatalog?,
    onWakeWordsChanged: () -> Unit,
) {
    // Home Assistant Assist pipelines for the Configure voice_pipelines picker. Delegates to
    // an injectable directory (the voice-coordinator lane's real HA-backed implementation;
    // the stub default reports 503 not-configured) rather than talking to Home Assistant here.
    // The response is decided by the pure voicePipelinesResponse() so it is unit-testable
    // without a routed request.
    get("/voice/pipelines") {
        val refusal = voicePipelinesRefusal(hasMicrophone = hasMicrophone())
        if (refusal != null) {
            call.respondText(
                "{\"error\":\"unavailable\",\"reason\":${Json.str(refusal)}}",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }
        val (status, body) = voicePipelinesResponse(assistPipelines.list())
        call.respondText(body, ContentType.Application.Json, status)
    }
    // One-shot voice-assistant test run. Refused with 409 before ever reaching the trigger
    // when the panel has no microphone capability or voice_enabled is off, so a disabled
    // panel never depends on whether the coordinator lane happens to be wired up. The
    // refusal check and the trigger-result mapping are both pure (voiceTestRefusal(),
    // voiceTestTriggerResponse()) so every branch is unit-testable without a routed request.
    post("/voice/test") {
        val refusal = voiceTestRefusal(hasMicrophone = hasMicrophone(), voiceEnabled = voiceEnabled())
        if (refusal != null) {
            call.respondText(
                "{\"reason\":${Json.str(refusal)}}",
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
            return@post
        }
        val (status, body) = voiceTestTriggerResponse(voiceTest.trigger())
        call.respondText(body, ContentType.Application.Json, status)
    }
    // The wake words this panel can listen for, and the import of one a user trained: a
    // microWakeWord manifest and model, sent as JSON with the model base64-encoded.
    get("/voice/wake-words") {
        val catalog = wakeWords
        if (catalog == null || !hasMicrophone()) {
            call.respondText("{\"error\":\"unavailable\"}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            return@get
        }
        val (status, body) = withContext(Dispatchers.IO) { wakeWordsResponse(catalog) }
        call.respondText(body, ContentType.Application.Json, status)
    }
    post("/voice/wake-words") {
        val catalog = wakeWords
        if (catalog == null || !hasMicrophone()) {
            call.respondText("{\"error\":\"unavailable\"}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            return@post
        }
        val received = java.io.ByteArrayOutputStream()
        try {
            withContext(Dispatchers.IO) {
                call.receiveStream().use { input ->
                    DeadlineBoundedBody.copy(input, received, MAX_WAKE_WORD_IMPORT_BYTES, WAKE_WORD_IMPORT_DEADLINE_MS)
                }
            }
        } catch (_: ByteLimitExceeded) {
            call.respondText("{\"error\":\"too-large\"}", ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
            return@post
        } catch (_: BodyReceiptTimeout) {
            call.respondText("{\"error\":\"timeout\"}", ContentType.Application.Json, HttpStatusCode.RequestTimeout)
            return@post
        }
        val (status, body) = withContext(Dispatchers.IO) {
            wakeWordImportResponse(catalog, received.toByteArray(), onWakeWordsChanged)
        }
        call.respondText(body, ContentType.Application.Json, status)
    }
}
