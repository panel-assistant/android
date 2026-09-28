package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.config.Validation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.json.JSONObject

internal suspend fun respondAutoBrightnessAction(call: ApplicationCall, action: AutoBrightnessHttpAction) {
    call.respondText(action.json, ContentType.Application.Json, HttpStatusCode.fromValue(action.statusCode))
}

internal fun Route.autoBrightnessRoutes(
    api: AutoBrightnessHttpApi,
    admit: suspend (ApplicationCall) -> Boolean,
    receiveJson: suspend (ApplicationCall, Boolean) -> JSONObject?,
) {
    get("/auto-brightness") {
        call.response.headers.append("Cache-Control", "no-store")
        call.respondText(api.statusJson(), ContentType.Application.Json)
    }
    get("/auto-brightness/history") {
        call.response.headers.append("Cache-Control", "no-store")
        if (!admit(call)) return@get
        val request = runCatching {
            autoBrightnessHistoryParameters(
                call.request.queryParameters["hours"], call.request.queryParameters["sensitivity"],
                call.request.queryParameters["minimum_percent"],
            )
        }.getOrElse {
            return@get call.respondText("${it.message ?: "invalid history query"}\n", status = HttpStatusCode.BadRequest)
        }
        call.respondText(api.historyJson(request.hours, request.sensitivity, request.minimumPercent), ContentType.Application.Json)
    }
    get("/auto-brightness/sources") {
        val query = call.request.queryParameters["q"].orEmpty().trim().take(100)
        val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 200)
        call.respondText(api.haSourcesJson(query, limit), ContentType.Application.Json)
    }
    post("/auto-brightness/source") {
        val obj = receiveJson(call, true) ?: return@post
        if (!obj.has("entity_id")) {
            return@post call.respondText(
                "entity_id is required (null selects the panel sensor)\n", status = HttpStatusCode.BadRequest,
            )
        }
        val raw = obj.opt("entity_id")
        val selected = when (raw) {
            JSONObject.NULL -> null
            is String -> {
                val spec = requireNotNull(SettingsRegistry.spec("auto_brightness_ha_entity"))
                when (val accepted = SettingValue.validate(spec, raw)) {
                    is Validation.Ok -> accepted.normalized.ifBlank { null }
                    is Validation.Bad -> return@post call.respondText(
                        "${accepted.reason}\n", status = HttpStatusCode.BadRequest,
                    )
                }
            }
            else -> return@post call.respondText(
                "entity_id must be a string or null\n", status = HttpStatusCode.BadRequest,
            )
        }
        respondAutoBrightnessAction(call, api.selectHaSource(selected))
    }
    post("/auto-brightness/reset") { respondAutoBrightnessAction(call, api.resetHistory()) }
    post("/auto-brightness/resume") { respondAutoBrightnessAction(call, api.resumeFullAuto()) }
}
