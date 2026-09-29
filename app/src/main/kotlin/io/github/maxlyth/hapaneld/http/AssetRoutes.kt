package io.github.maxlyth.hapaneld.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Bundled front-end assets, mounted beneath the shared production root guards. */
internal fun Route.assetRoutes(asset: (String) -> String) {
    // Static front-end assets (externalised from the Kotlin string so CI can lint them).
    get("/info.js") {
        call.response.headers.append("Cache-Control", "no-cache")  // assets iterate; always serve fresh
        call.respondText(asset("info.js"), ContentType.Application.JavaScript)
    }
    get("/info.css") {
        call.response.headers.append("Cache-Control", "no-cache")
        call.respondText(asset("info.css"), ContentType.Text.CSS)
    }
    get("/icon.svg") {
        call.respondText(asset("icon.svg"), ContentType.Image.SVG)
    }
    get("/favicon.svg") {
        call.respondText(asset("favicon.svg"), ContentType.Image.SVG)
    }
    // Generic bundled-asset server for the redesigned UI (page scripts + vendored libs).
    get("/assets/{f...}") {
        val rel = call.parameters.getAll("f")?.joinToString("/").orEmpty()
        val body = if (rel.isEmpty() || rel.contains("..")) null else runCatching { asset(rel) }.getOrNull()
        if (body == null) {
            call.respondText("not found\n", status = HttpStatusCode.NotFound)
        } else {
            val ct = when {
                rel.endsWith(".js") -> ContentType.Application.JavaScript
                rel.endsWith(".css") -> ContentType.Text.CSS
                rel.endsWith(".svg") -> ContentType.Image.SVG
                rel.endsWith(".json") -> ContentType.Application.Json
                else -> ContentType.Text.Plain
            }
            call.response.headers.append("Cache-Control", "no-cache")
            call.respondText(body, ct)
        }
    }
}
