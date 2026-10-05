package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.isHandled
import io.ktor.server.request.acceptItems
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText

/** Runs only after production routing and its root guards have admitted an unhandled browser read. */
internal fun Application.browserNotFoundPage(requestStrings: (ApplicationCall) -> AppStrings) {
    intercept(ApplicationCallPipeline.Call) {
        val path = call.request.path()
        // Human pages have extensionless paths; API namespaces and missing packaged files retain
        // Ktor's machine-facing 404, even when a caller asks for HTML.
        if (call.isHandled || call.request.httpMethod != HttpMethod.Get ||
            path == "/api" || path.startsWith("/api/") ||
            path == "/assets" || path.startsWith("/assets/") || '.' in path.substringAfterLast('/') ||
            call.request.acceptItems().none { it.value == "text/html" && it.quality > 0.0 }
        ) return@intercept
        val strings = requestStrings(call)
        call.response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptLanguage)
        call.response.headers.append(HttpHeaders.ContentLanguage, strings.languages(setOf("shell.pickles.")).joinToString(", "))
        call.respondText(browserNotFoundHtml(strings, path, call.embedMode()), ContentType.Text.Html, HttpStatusCode.NotFound)
    }
}

internal fun browserNotFoundHtml(strings: AppStrings, path: String, embed: EmbedMode? = null): String = """<!doctype html>
<html lang="${esc(strings.requestedLocale)}"${embed?.theme?.let { " data-theme=\"$it\"" }.orEmpty()}><head><base href="/"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(strings.get("shell.pickles.not_found"))}</title>
<link rel="icon" href="favicon.svg"><link rel="stylesheet" href="info.css">
<link rel="stylesheet" href="assets/pickles.css">
<script>(function(){var m=location.search.match(/[?&]theme=(dark|light)\b/);if(m)document.documentElement.setAttribute('data-theme',m[1])})();</script>
</head><body${if (embed == null) "" else " data-embedded"}><main class="pickles">
<img src="assets/pickles.svg" alt="">
<h1>${esc(strings.get("shell.pickles.title"))}</h1>
<p>${esc(strings.get("shell.pickles.story"))}</p>
<p>${esc(strings.get("shell.pickles.not_found"))}<code>${esc(path)}</code></p>
<a class="pbtn" href="${esc(localizedHref("./", strings))}">${esc(strings.get("shell.pickles.back"))}</a>
</main></body></html>"""
