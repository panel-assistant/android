package io.panelassistant.android.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val RETIRED_PROXIMITY_OPERATION =
    "{\"error\":\"automatic proximity learning replaced this operation\"}"
private const val PROXIMITY_SOURCE_REQUIRED = "{\"error\":\"proximity_source_required\"}"

/** Refusals are JSON like every other answer here, so the page can show the panel's own reason. */
private fun proximityError(message: String) = "{\"error\":\"$message\"}"

internal fun Route.proximityRoutes(
    hasProximity: () -> Boolean,
    proximityJson: () -> String,
    calibrate: (String, String) -> Boolean,
) {
    get("/proximity") { call.respondText(proximityJson(), ContentType.Application.Json) }
    post("/proximity/calibration") {
        if (!proximityUiRequestAllowed(
                call.request.headers["Origin"], call.request.headers["Referer"],
                call.request.headers["Host"], call.request.headers["Sec-Fetch-Site"],
                call.request.headers["X-Proximity-UI"], call.request.headers[EmbedMode.HEADER],
            )) {
            call.respondText(proximityError("Start proximity setup from this panel's HTML UI."),
                ContentType.Application.Json, HttpStatusCode.Forbidden)
            return@post
        }
        val parameters = receiveBoundedFormParameters(call) ?: return@post
        val action = parameters["action"].orEmpty()
        if (action !in setOf("start", "cancel", "reset", "heartbeat")) {
            call.respondText(proximityError("Unsupported calibration action."),
                ContentType.Application.Json, HttpStatusCode.BadRequest)
            return@post
        }
        if (!hasProximity()) {
            call.respondText(PROXIMITY_SOURCE_REQUIRED, ContentType.Application.Json, HttpStatusCode.Conflict)
            return@post
        }
        val id = parameters["sessionId"].orEmpty()
        val accepted = withContext(Dispatchers.IO) { calibrate(action, id) }
        call.response.headers.append("Cache-Control", "no-store")
        call.respondText(proximityJson(), ContentType.Application.Json,
            if (accepted) HttpStatusCode.Accepted else HttpStatusCode.Conflict)
    }
    post("/proximity/teach") {
        call.respondText("Use on-panel proximity setup from the HTML UI.\n", status = HttpStatusCode.Gone)
    }
    post("/proximity/test") {
        call.respondText("Use on-panel proximity setup from the HTML UI.\n", status = HttpStatusCode.Gone)
    }
    post("/proximity/relearn") {
        call.respondText("Use Reset to profile from the HTML UI.\n", status = HttpStatusCode.Gone)
    }
    for (path in listOf("capture", "threshold", "sensitivity", "reset")) {
        post("/proximity/$path") {
            call.respondText(RETIRED_PROXIMITY_OPERATION, ContentType.Application.Json, HttpStatusCode.Gone)
        }
    }
}
