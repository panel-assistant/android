package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.ZigbeeHealthSnapshot
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** EFR32 status and explicit Request join from the Install tab. */
internal fun Route.radioRoutes(
    status: () -> ZigbeeHealthSnapshot?,
    configured: () -> Boolean,
    enabled: () -> Boolean,
    join: () -> Boolean,
) {
    get("/radio") {
        val st = withContext(Dispatchers.IO) { status() }
        val body = if (st == null) """{"present":false,"status":"none"}""" else JSONObject()
            .put("present", true)
            .put("router_configured", configured())
            .put("router_enabled", configured() && enabled())
            .put("status", st.publicSummary())
            .put("state", st.state.wireValue)
            .put("attributes", JSONObject(st.mqttAttributes()))
            .toString()
        call.respondText(body, ContentType.Application.Json)
    }
    post("/radio/join") {
        val st = status()
        when {
            st == null -> call.respondText("""{"status":"unavailable"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            !configured() || !enabled() ->
                call.respondText("""{"status":"disabled"}""", ContentType.Application.Json, HttpStatusCode.Conflict)
            join() -> call.respondText("""{"status":"started"}""", ContentType.Application.Json)
            else -> call.respondText("""{"status":"busy"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
        }
    }
}
