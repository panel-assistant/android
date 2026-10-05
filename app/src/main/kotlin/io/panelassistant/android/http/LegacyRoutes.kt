package io.panelassistant.android.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** 308 for a legacy flat path — preserves method, body and query so pre-0.8.5 tooling keeps working. */
private suspend fun legacy(call: ApplicationCall, new: String) {
    val q = call.request.uri.substringAfter('?', "")
    val loc = if (q.isEmpty()) new else "$new?$q"
    call.response.headers.append("Location", loc)
    call.respondText("moved-permanently: $loc\n", status = HttpStatusCode.PermanentRedirect)
}

/** Flat machine endpoints redirect with 308, preserving GET/POST methods and bodies. */
internal fun Route.legacyRedirects() {
    val map = mapOf(
        "/perf" to "/api/v1/perf",
        "/action" to "/api/v1/action",
        "/diag" to "/api/v1/diag",
        "/sensortrace" to "/api/v1/sensortrace",
        "/screenshot.png" to "/api/v1/screenshot.png",
        "/openapi.json" to "/api/v1/openapi.json",
        "/proximity" to "/api/v1/proximity",
        "/proximity/capture" to "/api/v1/proximity/capture",
        "/proximity/threshold" to "/api/v1/proximity/threshold",
        "/proximity/sensitivity" to "/api/v1/proximity/sensitivity",
        "/proximity/reset" to "/api/v1/proximity/reset",
        "/proximity/teach" to "/api/v1/proximity/teach",
        "/proximity/test" to "/api/v1/proximity/test",
        "/proximity/relearn" to "/api/v1/proximity/relearn",
        "/config" to "/api/v1/config",
        "/tame" to "/api/v1/tame",
        "/tame/suggest" to "/api/v1/tame/suggest",
        "/density" to "/api/v1/display/density",
        "/inspect" to "/api/v1/inspect",
        "/inspect/start" to "/api/v1/inspect/start",
        "/inspect/stop" to "/api/v1/inspect/stop",
    )
    for ((old, new) in map) {
        get(old) { legacy(call, new) }
        post(old) { legacy(call, new) }
    }
}
