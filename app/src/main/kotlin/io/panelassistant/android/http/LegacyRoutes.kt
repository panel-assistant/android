package io.panelassistant.android.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** 308 for a legacy flat path — preserves method, body and query. */
private suspend fun legacy(call: ApplicationCall, new: String) {
    val q = call.request.uri.substringAfter('?', "")
    val loc = if (q.isEmpty()) new else "$new?$q"
    call.response.headers.append("Location", loc)
    call.respondText("moved-permanently: $loc\n", status = HttpStatusCode.PermanentRedirect)
}

/** `/diag` stays a redirect to `/api/v1/diag`: the README and bug-report replies send people to it. The
 *  other pre-0.8.5 flat machine paths were retired in 0.9.11 and answer 404. */
internal fun Route.legacyDiagRedirect() {
    get("/diag") { legacy(call, "/api/v1/diag") }
}
