package io.panelassistant.android.http

import io.panelassistant.android.sensors.HaPresenceSourceUpdate
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import org.json.JSONArray
import org.json.JSONObject
import io.panelassistant.android.MAX_PRESENCE_HA_SOURCES
import io.panelassistant.android.isPresenceSourceEntityId
import io.panelassistant.android.sensors.HaPresenceSourceCatalog
import io.panelassistant.android.sensors.HaPanelAreaPrerequisite
import io.panelassistant.android.sensors.HaPanelAreaPrerequisitePhase

internal fun autoSleepHistoryHours(hours: String?): Int {
    val parsed = hours?.toIntOrNull() ?: if (hours == null) 6 else null
    require(parsed != null && parsed in 1..48) { "hours must be between 1 and 48" }
    return parsed
}

private val OPAQUE_AUTO_SLEEP_KEY = Regex("^[a-f0-9]{64}$")

internal fun Route.autoSleepRoutes(
    api: AutoSleepHttpApi,
    admit: suspend (OpCall) -> Boolean,
    receiveJson: suspend (OpCall, Boolean) -> JSONObject?,
) = ops {
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
    get("/auto-sleep/presence-sources") {
        if (!admit(call)) return@get
        val catalog = runCatching { api.presenceSources() }.getOrNull()
            ?: return@get call.respondText("activity sources are unavailable\n", status = HttpStatusCode.Conflict)
        call.respondText(presenceSourcesJson(catalog), ContentType.Application.Json)
    }
    post("/auto-sleep/presence-sources/choose") {
        val obj = receiveJson(call, false) ?: return@post
        val raw = obj.opt("entity_ids") as? JSONArray
        val ids = raw?.let { array -> (0 until array.length()).map { array.opt(it) as? String ?: "" } }
        if (ids == null || ids.size > MAX_PRESENCE_HA_SOURCES || !ids.all(::isPresenceSourceEntityId)) {
            return@post call.respondText(
                "entity_ids must list at most $MAX_PRESENCE_HA_SOURCES binary_sensor entity ids\n",
                status = HttpStatusCode.BadRequest,
            )
        }
        when (api.setPresenceSources(ids)) {
            HaPresenceSourceUpdate.UPDATED -> call.respondText(
                """{"ok":true,"mode":"${if (ids.isEmpty()) "automatic" else "chosen"}"}""",
                ContentType.Application.Json,
            )
            HaPresenceSourceUpdate.COMMIT_FAILED -> call.respondText(
                "configuration commit failed\n", status = HttpStatusCode.InternalServerError,
            )
            HaPresenceSourceUpdate.STALE, HaPresenceSourceUpdate.UNAVAILABLE -> call.respondText(
                "activity sources are unavailable\n", status = HttpStatusCode.Conflict,
            )
        }
    }
}

/** Picker rows grouped client-side by area_name; blank area_name means the sensor has no Area. */
internal fun presenceSourcesJson(catalog: HaPresenceSourceCatalog): String {
    val byId = catalog.candidates.associateBy { it.entityId }
    return JSONObject()
        .put("mode", if (catalog.chosen.isEmpty()) "automatic" else "chosen")
        .put("chosen", JSONArray().apply {
            catalog.chosen.forEach { id ->
                val row = byId[id]
                put(JSONObject().put("entity_id", id).put("name", row?.friendlyName ?: id)
                    .put("area_name", row?.areaName.orEmpty()).put("missing", row == null))
            }
        })
        .put("missing", JSONArray(catalog.missing))
        .put("candidates", JSONArray().apply {
            catalog.candidates.forEach {
                put(JSONObject().put("entity_id", it.entityId).put("name", it.friendlyName)
                    .put("area_name", it.areaName).put("device_class", it.deviceClass).put("platform", it.platform))
            }
        })
        .toString()
}

/** Compact read-only boundary over the service-owned auto-sleep runtime. Configuration continues to
 * use the ordinary schema/config transaction; the runtime owns the coherent bounded status JSON. */
internal interface AutoSleepHttpApi {
    fun statusJson(): String
    suspend fun historyJson(hours: Int = 6): String
    suspend fun prerequisite(): HaPanelAreaPrerequisite
    fun setSourceIncluded(areaKey: String, sourceKey: String, included: Boolean): HaPresenceSourceUpdate

    /** The panel's area changed; the runtime must re-read its configuration. Kept abstract so a service
     * implementation cannot silently compile with a no-op while the running discovery keeps stale room. */
    fun noteAreaChanged()

    /** Every presence-class binary_sensor in HA plus the owner's choice; null when unavailable. */
    suspend fun presenceSources(): HaPresenceSourceCatalog? = null

    /** Replaces the owner's chosen sources; an empty list restores automatic Area discovery. */
    fun setPresenceSources(entityIds: List<String>): HaPresenceSourceUpdate = HaPresenceSourceUpdate.UNAVAILABLE

    companion object {
        val UNAVAILABLE: AutoSleepHttpApi = object : AutoSleepHttpApi {
            override fun statusJson(): String =
                """{"available":false,"enabled":false,"phase":"unavailable","reason":"runtime_unavailable","learned_lease_ms":null,"source_count":0,"manual_suppression":false,"detail":""}"""

            override suspend fun historyJson(hours: Int): String =
                """{"available":false,"hours":$hours,"bucket_ms":60000,"window_start_epoch_ms":null,"window_end_epoch_ms":null,"warmup_ms":3600000,"learned_lease_ms":null,"source_scope":"selected_area_sources","area_sources_only":true,"source_count":0,"exclusions":["past_touch","panel_proximity","manual_override_or_suppression","screen_wake","historical_learning_changes"],"segments":[],"detail":"runtime_unavailable"}"""

            override suspend fun prerequisite() = HaPanelAreaPrerequisite(
                HaPanelAreaPrerequisitePhase.UNAVAILABLE,
                detail = "Auto-sleep Area discovery is unavailable",
            )

            override fun noteAreaChanged() {} // no runtime exists to refresh

            override fun setSourceIncluded(areaKey: String, sourceKey: String, included: Boolean) =
                HaPresenceSourceUpdate.UNAVAILABLE
        }
    }
}

internal fun autoSleepRequiresHaAdmission(
    currentEnabled: Boolean,
    currentSource: String,
    requestedEnabled: Boolean,
    requestedSource: String,
): Boolean = requestedEnabled && requestedSource == "home_assistant" &&
    (!currentEnabled || currentSource != "home_assistant")

internal fun autoSleepConfigErrorJson(error: String, message: String): String = JSONObject()
    .put("ok", false)
    .put("error", error)
    .put("message", message)
    .toString()
