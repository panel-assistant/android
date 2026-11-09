package io.panelassistant.android.http

import io.panelassistant.android.util.ByteLimitExceeded
import io.panelassistant.android.util.Json
import io.panelassistant.android.security.SensitiveOperation
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
private val SESSION_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

internal fun Route.voiceRoutes(
    hasMicrophone: () -> Boolean,
    microphone: () -> io.panelassistant.android.audio.MicrophoneStatus,
    voiceEnabled: () -> Boolean,
    assistPipelines: io.panelassistant.android.assist.AssistPipelineDirectory,
    voiceTest: io.panelassistant.android.assist.VoiceTestTrigger,
    wakeWords: io.panelassistant.android.assist.wakeword.WakeWordCatalog?,
    onWakeWordsChanged: () -> Unit,
    authorize: suspend (OpCall, SensitiveOperation, String, String) -> Boolean,
    voiceDiagnostics: io.panelassistant.android.assist.VoiceDiagnosticsControl =
        io.panelassistant.android.assist.VoiceDiagnosticsControl.NONE,
) {
    // Home Assistant Assist pipelines for the Configure voice_pipelines picker, through the
    // injected directory rather than talking to Home Assistant here.
    ops {
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
    // What the panel has and what its own capture check found, so the Voice card can say why it is
    // quiet: no microphone, or one that recorded silence or delivered nothing when checked.
    get("/voice/microphone") {
        call.respondText(voiceMicrophoneJson(microphone()), ContentType.Application.Json)
    }
    // The owner's diagnostics for the Voice card: the level meter and wake-word test a tuning session runs,
    // and the last command the panel heard, which is held in memory only.
    get("/voice/diagnostics") {
        if (!hasMicrophone()) {
            call.respondText("{\"error\":\"unavailable\"}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            return@get
        }
        call.response.headers.append("Cache-Control", "no-store")
        call.respondText(voiceDiagnosticsJson(voiceDiagnostics.diagnostics(), microphone()), ContentType.Application.Json)
    }
    // Starts, keeps or stops a tuning session. It leases the microphone, so it is the panel's own UI only,
    // as proximity setup is: its socket and the sidebar relay, or a same-origin page that marks the request.
    post("/voice/diagnostics", name = "voice.diagnostics.set") {
        val fromDoorPage = call.http == null && (call.origin.peerAddress != null || call.origin.isUser)
        if (!fromDoorPage && !proximityUiRequestAllowed(
                call.request.headers["Origin"], call.request.headers["Referer"],
                call.request.headers["Host"], call.request.headers["Sec-Fetch-Site"],
                call.request.headers["X-Voice-UI"],
            )) {
            call.respondText("{\"error\":${Json.str("Tune the microphone from this panel's HTML UI.")}}",
                ContentType.Application.Json, HttpStatusCode.Forbidden)
            return@post
        }
        val parameters = receiveBoundedFormParameters(call) ?: return@post
        val action = parameters["action"].orEmpty()
        val sessionId = parameters["sessionId"].orEmpty()
        if (action !in io.panelassistant.android.assist.VoiceDiagnosticsControl.ACTIONS || !SESSION_ID.matches(sessionId)) {
            call.respondText("{\"error\":${Json.str("Unsupported diagnostics action.")}}",
                ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        val refusal = voiceTestRefusal(hasMicrophone = hasMicrophone(), voiceEnabled = voiceEnabled())
            ?.takeIf { action != io.panelassistant.android.assist.VoiceDiagnosticsControl.ACTION_STOP }
            ?: withContext(Dispatchers.IO) { voiceDiagnostics.diagnosticsAction(action, sessionId) }
        call.response.headers.append("Cache-Control", "no-store")
        if (refusal != null) {
            call.respondText("{\"error\":${Json.str(refusal)}}", ContentType.Application.Json, HttpStatusCode.Conflict)
            return@post
        }
        call.respondText(voiceDiagnosticsJson(voiceDiagnostics.diagnostics(), microphone()),
            ContentType.Application.Json, HttpStatusCode.Accepted)
    }
    // One-shot voice-assistant test run. Refused with 409 before ever reaching the trigger
    // when the panel has no microphone capability or voice_enabled is off, so a disabled
    // panel never depends on whether the coordinator lane happens to be wired up. The
    // refusal check and the trigger-result mapping are both pure (voiceTestRefusal(),
    // voiceTestTriggerResponse()) so every branch is unit-testable without a routed request.
    // A named operation, so the sidebar relay and the socket door can run tap-to-talk too.
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
    post("/voice/wake-words", name = "voice.wake_words.set") {
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
        val result = withContext(Dispatchers.IO) {
            wakeWordImportResponse(catalog, received.toByteArray(), onWakeWordsChanged) { payload, summary ->
                authorize(call, SensitiveOperation.WAKE_WORD_IMPORT, payload, summary)
            }
        } ?: return@post
        call.respondText(result.second, ContentType.Application.Json, result.first)
    }
    }
}
