package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.util.Json
import io.ktor.http.HttpStatusCode

/** `GET /api/v1/voice/wake-words`: every wake word the panel holds, bundled first. */
internal fun wakeWordsResponse(
    catalog: io.github.maxlyth.hapaneld.assist.wakeword.WakeWordCatalog,
): Pair<HttpStatusCode, String> {
    val words = catalog.available().joinToString(",") { config ->
        "{\"id\":${Json.str(config.id)},\"wake_word\":${Json.str(config.wakeWord)}," +
            "\"imported\":${!catalog.isBundled(config.id)}}"
    }
    return HttpStatusCode.OK to "{\"wake_words\":[$words]}"
}

/**
 * `POST /api/v1/voice/wake-words`: import one user-trained microWakeWord model from
 * `{"name":…,"manifest":"<the .json text>","model":"<the .tflite, base64>"}`. [changed] runs after a
 * model is installed, so the listener and Home Assistant see it.
 */
internal fun wakeWordImportResponse(
    catalog: io.github.maxlyth.hapaneld.assist.wakeword.WakeWordCatalog,
    body: ByteArray,
    changed: () -> Unit,
): Pair<HttpStatusCode, String> {
    val request = runCatching { org.json.JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
    val name = request?.opt("name") as? String
    val manifest = request?.opt("manifest") as? String
    val model = (request?.opt("model") as? String)?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
    if (name == null || manifest == null || model == null) {
        return HttpStatusCode.BadRequest to "{\"error\":\"expected name, manifest and a base64 model\"}"
    }
    return when (val result = catalog.import(name, manifest.toByteArray(Charsets.UTF_8), model)) {
        is io.github.maxlyth.hapaneld.assist.wakeword.WakeWordCatalog.ImportResult.Imported -> {
            changed()
            HttpStatusCode.OK to "{\"id\":${Json.str(result.config.id)},\"wake_word\":${Json.str(result.config.wakeWord)}}"
        }
        is io.github.maxlyth.hapaneld.assist.wakeword.WakeWordCatalog.ImportResult.Refused ->
            HttpStatusCode.UnprocessableEntity to "{\"error\":${Json.str(result.reason)}}"
    }
}
