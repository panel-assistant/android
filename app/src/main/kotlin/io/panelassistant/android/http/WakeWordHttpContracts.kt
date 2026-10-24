package io.panelassistant.android.http

import io.panelassistant.android.util.Json
import io.ktor.http.HttpStatusCode

/**
 * `GET /api/v1/voice/wake-words`: every wake word the panel holds, bundled first, and the community models
 * it can download, each with the author and licence an owner sees before downloading it.
 */
internal fun wakeWordsResponse(
    catalog: io.panelassistant.android.assist.wakeword.WakeWordCatalog,
): Pair<HttpStatusCode, String> {
    val words = catalog.available().joinToString(",") { config ->
        "{\"id\":${Json.str(config.id)},\"wake_word\":${Json.str(config.wakeWord)}," +
            "\"imported\":${!catalog.isBundled(config.id)}}"
    }
    val downloads = catalog.downloadable().joinToString(",") { entry ->
        "{\"id\":${Json.str(entry.id)},\"wake_word\":${Json.str(entry.wakeWord)}," +
            "\"author\":${Json.str(entry.author)},\"license\":${Json.str(entry.license)}," +
            "\"license_url\":${Json.str(entry.licenseUrl)},\"source\":${Json.str(entry.source)}," +
            "\"bytes\":${entry.modelBytes},\"installed\":${catalog.holds(entry)}}"
    }
    return HttpStatusCode.OK to "{\"wake_words\":[$words],\"downloads\":[$downloads]}"
}

/**
 * `POST /api/v1/voice/wake-words`: import one user-trained microWakeWord model from
 * `{"name":…,"manifest":"<the .json text>","model":"<the .tflite, base64>"}`, or download one the list
 * offers with `{"download":"<id>"}`. [changed] runs after a model is installed, so the listener and Home
 * Assistant see it.
 */
internal suspend fun wakeWordImportResponse(
    catalog: io.panelassistant.android.assist.wakeword.WakeWordCatalog,
    body: ByteArray,
    changed: () -> Unit,
    authorize: suspend (String, String) -> Boolean,
): Pair<HttpStatusCode, String>? {
    val request = runCatching { org.json.JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
    val download = request?.opt("download") as? String
    if (download != null) return wakeWordInstalled(catalog.download(download), changed)
    val name = request?.opt("name") as? String
    val manifest = request?.opt("manifest") as? String
    val model = (request?.opt("model") as? String)?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
    if (name == null || manifest == null || model == null) {
        return HttpStatusCode.BadRequest to "{\"error\":\"expected name, manifest and a base64 model\"}"
    }
    val digest = sha256Hex(model)
    if (!authorize(sha256Hex(body) + ":" + digest, "Import wake word $name (model SHA-256 $digest)")) return null
    return wakeWordInstalled(catalog.import(name, manifest.toByteArray(Charsets.UTF_8), model), changed)
}

internal fun wakeWordInstalled(
    result: io.panelassistant.android.assist.wakeword.WakeWordCatalog.ImportResult,
    changed: () -> Unit,
): Pair<HttpStatusCode, String> {
    return when (result) {
        is io.panelassistant.android.assist.wakeword.WakeWordCatalog.ImportResult.Imported -> {
            changed()
            HttpStatusCode.OK to "{\"id\":${Json.str(result.config.id)},\"wake_word\":${Json.str(result.config.wakeWord)}}"
        }
        is io.panelassistant.android.assist.wakeword.WakeWordCatalog.ImportResult.Refused ->
            (if (result.incomplete) HttpStatusCode.BadGateway else HttpStatusCode.UnprocessableEntity) to
                "{\"error\":${Json.str(result.reason)}}"
    }
}
