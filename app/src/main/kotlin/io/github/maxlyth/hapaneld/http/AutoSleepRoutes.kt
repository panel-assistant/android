package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.sensors.HaPresenceSourceUpdate
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.json.JSONObject

internal fun autoSleepHistoryHours(hours: String?): Int {
    val parsed = hours?.toIntOrNull() ?: if (hours == null) 6 else null
    require(parsed != null && parsed in 1..48) { "hours must be between 1 and 48" }
    return parsed
}

private val OPAQUE_AUTO_SLEEP_KEY = Regex("^[a-f0-9]{64}$")

internal fun Route.autoSleepRoutes(
    api: AutoSleepHttpApi,
    admit: suspend (ApplicationCall) -> Boolean,
    receiveJson: suspend (ApplicationCall, Boolean) -> JSONObject?,
) {
    get("/auto-sleep") { call.respondText(api.statusJson(), ContentType.Application.Json) }
    get("/auto-sleep/prerequisite") {
        if (!admit(call)) return@get
        val result = api.prerequisite()
        call.respondText(
            JSONObject().put("eligible", result.eligible).put("phase", result.phase.name.lowercase())
                .put("area_name", result.areaName).put("detail", result.detail.take(240)).toString(),
            ContentType.Application.Json,
        )
    }
    get("/auto-sleep/history") {
        if (!admit(call)) return@get
        val hours = runCatching { autoSleepHistoryHours(call.request.queryParameters["hours"]) }.getOrElse {
            return@get call.respondText("${it.message ?: "invalid history query"}\n", status = HttpStatusCode.BadRequest)
        }
        call.respondText(api.historyJson(hours), ContentType.Application.Json)
    }
    post("/auto-sleep/source") {
        val obj = receiveJson(call, false) ?: return@post
        val areaKey = obj.optString("area_key").trim()
        val sourceKey = obj.optString("source_key").trim()
        val includedValue = obj.opt("included")
        if (!OPAQUE_AUTO_SLEEP_KEY.matches(areaKey) ||
            !OPAQUE_AUTO_SLEEP_KEY.matches(sourceKey) || includedValue !is Boolean
        ) {
            return@post call.respondText(
                "area_key, source_key and included are required\n",
                status = HttpStatusCode.BadRequest,
            )
        }
        when (api.setSourceIncluded(areaKey, sourceKey, includedValue)) {
            HaPresenceSourceUpdate.UPDATED -> call.respondText(
                """{"ok":true,"included":$includedValue}""", ContentType.Application.Json,
            )
            HaPresenceSourceUpdate.STALE -> call.respondText(
                "activity sources changed; reload and try again\n", status = HttpStatusCode.Conflict,
            )
            HaPresenceSourceUpdate.COMMIT_FAILED -> call.respondText(
                "configuration commit failed\n", status = HttpStatusCode.InternalServerError,
            )
            HaPresenceSourceUpdate.UNAVAILABLE -> call.respondText(
                "activity sources are unavailable\n", status = HttpStatusCode.Conflict,
            )
        }
    }
}
