package io.panelassistant.android.http

import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.config.Validation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.json.JSONObject
import io.panelassistant.android.HaAuthOwner
import io.panelassistant.android.control.AmbientThemeReport

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
                call.request.queryParameters["minimum_percent"], call.request.queryParameters["maximum_percent"],
            )
        }.getOrElse {
            return@get call.respondText("${it.message ?: "invalid history query"}\n", status = HttpStatusCode.BadRequest)
        }
        val json = try {
            api.historyJson(request.hours, request.sensitivity, request.minimumPercent, request.maximumPercent)
        } catch (invalid: IllegalArgumentException) {
            return@get call.respondText("${invalid.message}\n", status = HttpStatusCode.BadRequest)
        }
        call.respondText(json, ContentType.Application.Json)
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

/** Small HTTP boundary over the service-owned adaptive-brightness runtime. The default is deliberately
 * read-safe and mutation-closed so the UI/API can land before the model, history and HA transport are
 * wired into the service. JSON is produced by the owner to avoid copying its snapshots here. */
internal interface AutoBrightnessHttpApi {
    fun statusJson(): String
    fun historyJson(hours: Int = 168, sensitivity: Int? = null, minimumPercent: Int? = null, maximumPercent: Int? = null): String
    fun haSourcesJson(query: String, limit: Int): String
    suspend fun validateHaSource(entityId: String): AutoBrightnessHttpValidation
    suspend fun selectHaSource(entityId: String?): AutoBrightnessHttpAction
    fun resetHistory(): AutoBrightnessHttpAction
    fun resumeFullAuto(): AutoBrightnessHttpAction

    /** Why the Ambient dashboard theme resolves as it does, or null when the runtime cannot say. */
    fun ambientTheme(): AmbientThemeReport? = null

    companion object {
        val UNAVAILABLE: AutoBrightnessHttpApi = object : AutoBrightnessHttpApi {
            override fun statusJson(): String =
                """{"available":false,"state":"unavailable","sourceRevision":null,"detail":"Adaptive brightness runtime is not connected."}"""

            override fun historyJson(hours: Int, sensitivity: Int?, minimumPercent: Int?, maximumPercent: Int?): String =
                """{"available":false,"hours":$hours,"bucket_minutes":0,"sourceRevision":null,"latestEpochMinute":null,"points":[]}"""

            override fun haSourcesJson(query: String, limit: Int): String =
                """{"available":false,"items":[]}"""

            override suspend fun validateHaSource(entityId: String): AutoBrightnessHttpValidation =
                AutoBrightnessHttpValidation(AutoBrightnessHttpAction.unavailable())

            override suspend fun selectHaSource(entityId: String?): AutoBrightnessHttpAction =
                AutoBrightnessHttpAction.unavailable()

            override fun resetHistory(): AutoBrightnessHttpAction = AutoBrightnessHttpAction.unavailable()
            override fun resumeFullAuto(): AutoBrightnessHttpAction = AutoBrightnessHttpAction.unavailable()
        }
    }
}

internal data class AutoBrightnessHttpValidation(
    val action: AutoBrightnessHttpAction,
    val authOwner: io.panelassistant.android.HaAuthOwner? = null,
)

internal data class AutoBrightnessHttpAction(val statusCode: Int, val json: String) {
    init { require(statusCode in 200..599); require(json.isNotBlank()) }

    companion object {
        fun ok(json: String = """{"ok":true}""") = AutoBrightnessHttpAction(200, json)
        fun unavailable() = AutoBrightnessHttpAction(
            503,
            """{"ok":false,"error":"Adaptive brightness runtime is not connected."}""",
        )
    }
}

internal data class AutoBrightnessHistoryParameters(
    val hours: Int,
    val sensitivity: Int?,
    val minimumPercent: Int?,
    val maximumPercent: Int?,
)

internal fun autoBrightnessHistoryParameters(
    hours: String?,
    sensitivity: String?,
    minimumPercent: String? = null,
    maximumPercent: String? = null,
): AutoBrightnessHistoryParameters {
    val boundedHours = if (hours == null) 168 else hours.toIntOrNull()
        ?: throw IllegalArgumentException("hours must be between 1 and 168")
    require(boundedHours in 1..168) { "hours must be between 1 and 168" }
    val boundedSensitivity = sensitivity?.let {
        it.toIntOrNull()?.takeIf { value -> value in 0..100 }
            ?: throw IllegalArgumentException("sensitivity must be between 0 and 100")
    }
    val minimumRange = SettingsRegistry.MINIMUM_AUTOMATIC_PERCENT..SettingsRegistry.MAX_AUTOMATIC_MINIMUM_PERCENT
    val boundedMinimum = minimumPercent?.let {
        it.toIntOrNull()?.takeIf { value -> value in minimumRange }
            ?: throw IllegalArgumentException(
                "minimum_percent must be between ${minimumRange.first} and ${minimumRange.last}",
            )
    }
    val boundedMaximum = maximumPercent?.let {
        it.toIntOrNull()?.takeIf { value -> value in (SettingsRegistry.MINIMUM_AUTOMATIC_PERCENT + 1)..100 }
            ?: throw IllegalArgumentException("maximum_percent must be between ${SettingsRegistry.MINIMUM_AUTOMATIC_PERCENT + 1} and 100")
    }
    require(boundedMaximum == null || boundedMinimum == null || boundedMaximum > boundedMinimum) {
        "maximum_percent must be above minimum_percent"
    }
    return AutoBrightnessHistoryParameters(boundedHours, boundedSensitivity, boundedMinimum, boundedMaximum)
}
