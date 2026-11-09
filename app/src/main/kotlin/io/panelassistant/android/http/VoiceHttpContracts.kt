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

/** `GET /api/v1/voice/microphone`: `{presence, check, detail, muted, route, aec}`, detail null unless the capture
 *  gave one; route as [voiceRouteJson] renders it, and aec the open capture's echo canceller, null while closed. */
internal fun voiceMicrophoneJson(status: io.panelassistant.android.audio.MicrophoneStatus): String =
    "{\"presence\":${Json.str(status.presence.wireValue)},\"check\":${Json.str(status.check.wireValue)}," +
        "\"detail\":${status.detail?.let(Json::str) ?: "null"},\"muted\":${status.muted}," +
        "\"route\":${voiceRouteJson(status)},\"aec\":${status.route?.active?.aec?.wireValue?.let(Json::str) ?: "null"}}"

/**
 * The capture route the panel opened, as a JSON object shared by `voice/microphone` and `voice/diagnostics`:
 * what was requested, what is open now (null while the microphone is closed, its echo canceller read from the
 * platform for this report) and how many times capture has rebuilt itself. `null` when no source has been built.
 */
internal fun voiceRouteJson(status: io.panelassistant.android.audio.MicrophoneStatus): String {
    val report = status.route ?: return "null"
    val requested = report.requested.let {
        "{\"source\":${Json.str(io.panelassistant.android.audio.CaptureRoute.sourceName(it.source))}," +
            "\"rate_hz\":${it.sampleRateHz},\"channels\":${it.channels},\"downmix\":${Json.str(it.downmix.wireValue)}," +
            "\"preferred_device\":${Json.str(it.preferredDevice.wireValue)},\"warmup_reads\":${it.warmupReads}," +
            "\"platform_aec\":${Json.str(it.platformAec.wireValue)}}"
    }
    val active = report.active?.let {
        "{\"source\":${Json.str(io.panelassistant.android.audio.CaptureRoute.sourceName(it.source))}," +
            "\"rate_hz\":${it.sampleRateHz},\"channels\":${it.channels}," +
            "\"downmix\":${it.downmix?.wireValue?.let(Json::str) ?: "null"}," +
            "\"routed_device\":${it.routedDevice?.let(Json::str) ?: "null"},\"aec\":${Json.str(it.aec.wireValue)}," +
            "\"warmup_reads\":${it.warmupReads}}"
    } ?: "null"
    return "{\"requested\":$requested,\"active\":$active,\"rebuilds\":${report.rebuilds}}"
}

/** The `/diag` words for the capture route: `source/rate/channels[/downmix]` of the open capture, else of the
 *  requested one, its echo canceller (`-` while closed) and the rebuild count; `-` before any source is built. */
private fun routeWords(status: io.panelassistant.android.audio.MicrophoneStatus): String {
    val report = status.route ?: return "route=- aec=- rebuilds=-"
    val active = report.active
    val route = if (active != null) {
        "${io.panelassistant.android.audio.CaptureRoute.sourceName(active.source)}/${active.sampleRateHz}/${active.channels}" +
            (active.downmix?.let { "/${it.wireValue}" } ?: "")
    } else {
        report.requested.let {
            "${io.panelassistant.android.audio.CaptureRoute.sourceName(it.source)}/${it.sampleRateHz}/${it.channels}" +
                (if (it.channels > 1) "/${it.downmix.wireValue}" else "") + "(closed)"
        }
    }
    return "route=$route aec=${active?.aec?.wireValue ?: "-"} rebuilds=${report.rebuilds}"
}

private fun decimal(value: Float?, places: Int): String =
    value?.takeIf { it.isFinite() }?.let { String.format(java.util.Locale.ROOT, "%.${places}f", it) } ?: "null"

/**
 * `GET /api/v1/voice/diagnostics`: the owner's live view. `level` while a tuning session meters the
 * microphone, the wake-word test against its cutoff, the last command (in memory only; never in `/diag`),
 * and the capture route.
 */
internal fun voiceDiagnosticsJson(
    snapshot: io.panelassistant.android.assist.VoiceDiagnosticsSnapshot,
    microphone: io.panelassistant.android.audio.MicrophoneStatus,
): String {
    val level = snapshot.level?.let {
        "{\"rms_dbfs\":${decimal(it.rmsDbfs, 1)},\"peak_dbfs\":${decimal(it.peakDbfs, 1)}," +
            "\"floor_dbfs\":${decimal(it.floorDbfs, 1)},\"clipped\":${it.clipped}}"
    } ?: "null"
    val test = snapshot.wakeTest.let {
        "{\"state\":${Json.str(it.state)},\"model\":${it.model?.let(Json::str) ?: "null"}," +
            "\"peak\":${decimal(it.peak, 2)},\"cutoff\":${decimal(it.cutoff, 2)}," +
            "\"fired\":${it.fired},\"remaining_ms\":${it.remainingMs}}"
    }
    val last = snapshot.lastCommand?.let {
        "{\"at\":${it.at},\"wake_word\":${it.wakeWord?.let(Json::str) ?: "null"}," +
            "\"wake_score\":${decimal(it.wakeScore, 2)},\"heard\":${it.heard?.let(Json::str) ?: "null"}," +
            "\"reply\":${it.reply?.let(Json::str) ?: "null"},\"outcome\":${Json.str(it.outcome)}," +
            "\"code\":${it.code?.let(Json::str) ?: "null"},\"listen_ms\":${it.listenMs ?: "null"}}"
    } ?: "null"
    return "{\"session\":${snapshot.session?.let(Json::str) ?: "null"},\"level\":$level,\"wake_test\":$test," +
        "\"last_command\":$last,\"route\":${voiceRouteJson(microphone)}}"
}

/** The `/diag` voice line: what a report needs to say why a panel does not hear or answer. Settings and
 *  the microphone check only. No audio is ever kept; the last command the panel heard is held in memory for
 *  the owner's Voice card alone and is never put here. */
internal fun voiceDiagnosticLine(
    enabled: Boolean,
    microphone: io.panelassistant.android.audio.MicrophoneStatus,
    wakeWords: String,
    pipelines: String,
    audioSource: String,
    sensitivity: String,
    micGainDb: Int,
    stopWord: Boolean,
): String =
    "[voice] enabled=$enabled microphone=${microphone.presence.wireValue} check=${microphone.check.wireValue} " +
        "muted=${microphone.muted} detail=${microphone.detail?.let(Json::str) ?: "-"} " +
        "wake_words=${wakeWords.ifBlank { "-" }} pipelines=${pipelines.ifBlank { "-" }} " +
        "source=${audioSource.ifBlank { "-" }} sensitivity=${sensitivity.ifBlank { "-" }} " +
        "gain_db=$micGainDb stop_word=$stopWord ${routeWords(microphone)}"

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
