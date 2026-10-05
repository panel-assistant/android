package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.dashboard.EntityFilterProtocol
import io.panelassistant.android.dashboard.EntityFilterTelemetry
import io.panelassistant.android.dashboard.EntityLearningManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class EntityRoutes(
    private val config: Config,
    private val entityLearning: EntityLearningManager,
    private val onFilterChanged: () -> Unit,
) {
    fun mount(route: Route) {
        with(route) {
            // Experimental built-in-renderer entity filter. The exact ids are accepted at runtime
            // but never echoed, logged, or included in config exports; status is count+hash.
            get("/dashboard/entity-filter") {
                call.respondText(entityFilterStatusJson(), ContentType.Application.Json)
            }
            post("/dashboard/entity-filter") { handleEntityFilterPost(call) }
            get("/dashboard/entities") {
                call.respondText(
                    entityLearning.entitiesJson(
                        call.request.queryParameters["q"].orEmpty(),
                        call.request.queryParameters["filter"] ?: "active",
                        call.request.queryParameters["limit"]?.toIntOrNull() ?: 100,
                        call.request.queryParameters["offset"]?.toIntOrNull() ?: 0,
                        call.request.queryParameters["sort"] ?: "entity_id",
                        call.request.queryParameters["dir"] ?: "asc",
                    ),
                    ContentType.Application.Json,
                )
            }
            get("/dashboard/entities/sync") { call.respondText(entityLearning.statusJson(), ContentType.Application.Json) }
            get("/dashboard/entities/issues") {
                call.respondText(entityLearning.issuesJson(), ContentType.Application.Json)
            }
            post("/dashboard/entities/issues") {
                val obj = receiveEntityAdminJson(call) ?: return@post
                if (!obj.has("fingerprint") || !obj.has("ignored")) {
                    return@post call.respondText(
                        "fingerprint and ignored are required\n", status = HttpStatusCode.BadRequest,
                    )
                }
                val response = runCatching {
                    entityLearning.setIssueIgnored(obj.optString("fingerprint"), obj.optBoolean("ignored"))
                }.getOrElse {
                    return@post call.respondText("invalid issue override: ${it.message}\n", status = HttpStatusCode.BadRequest)
                }
                call.respondText(response, ContentType.Application.Json)
            }
            post("/dashboard/entities/sync") {
                if (entityLearning.syncNow("manual")) {
                    call.respondText(entityLearning.statusJson(), ContentType.Application.Json, HttpStatusCode.Accepted)
                } else call.respondText("synchronization already running\n", status = HttpStatusCode.Conflict)
            }
            post("/dashboard/entities/activate") {
                val obj = receiveEntityAdminJson(call, allowBlank = true) ?: return@post
                val response = runCatching { entityLearning.activate(obj.optBoolean("confirm", false)) }.getOrElse {
                    return@post call.respondText("activation failed: ${it.message}\n", status = HttpStatusCode.BadRequest)
                }
                val status = if (JSONObject(response).optBoolean("confirmation_required")) HttpStatusCode.Conflict else HttpStatusCode.OK
                call.respondText(response, ContentType.Application.Json, status)
            }
            post("/dashboard/entities/policy") {
                val obj = receiveEntityAdminJson(call) ?: return@post
                if (!obj.has("auto_static") || !obj.has("auto_runtime")) {
                    return@post call.respondText("auto_static and auto_runtime are required\n", status = HttpStatusCode.BadRequest)
                }
                val response = runCatching {
                    entityLearning.setPromotionPolicy(obj.optBoolean("auto_static"), obj.optBoolean("auto_runtime"))
                }.getOrElse {
                    return@post call.respondText("invalid policy: ${it.message}\n", status = HttpStatusCode.BadRequest)
                }
                call.respondText(response, ContentType.Application.Json)
            }
            post("/dashboard/entities/override") {
                val obj = receiveEntityAdminJson(call) ?: return@post
                val response = runCatching {
                    entityLearning.setOverride(
                        obj.optString("entity_id"), obj.optString("override"), obj.optBoolean("force", false),
                    )
                }.getOrElse {
                    return@post call.respondText("invalid override: ${it.message}\n", status = HttpStatusCode.BadRequest)
                }
                val status = if (JSONObject(response).optBoolean("confirmation_required")) HttpStatusCode.Conflict else HttpStatusCode.OK
                call.respondText(response, ContentType.Application.Json, status)
            }
            post("/dashboard/entities/overrides") {
                val obj = receiveEntityAdminJson(call) ?: return@post
                val ids = obj.optJSONArray("entity_ids")?.let { array ->
                    (0 until array.length()).map { array.optString(it) }
                }.orEmpty()
                val response = runCatching {
                    entityLearning.setOverrides(
                        ids, obj.optBoolean("all_candidates", false), obj.optString("override"), obj.optBoolean("force", false),
                    )
                }.getOrElse {
                    return@post call.respondText("invalid bulk override: ${it.message}\n", status = HttpStatusCode.BadRequest)
                }
                val status = if (JSONObject(response).optBoolean("confirmation_required")) HttpStatusCode.Conflict else HttpStatusCode.OK
                call.respondText(response, ContentType.Application.Json, status)
            }
            post("/dashboard/entities/reset") {
                val obj = receiveEntityAdminJson(call, allowBlank = true) ?: return@post
                val response = runCatching {
                    entityLearning.resetEvidence(
                        confirm = obj.optBoolean("confirm", false),
                        clearFilter = obj.optBoolean("clear_filter", false),
                    )
                }.getOrElse {
                    return@post call.respondText("reset failed: ${it.message}\n", status = HttpStatusCode.Conflict)
                }
                val status = if (JSONObject(response).optBoolean("confirmation_required")) HttpStatusCode.Conflict else HttpStatusCode.OK
                call.respondText(response, ContentType.Application.Json, status)
            }
            get("/dashboard/entities/export") {
                call.response.headers.append("Content-Disposition", "attachment; filename=ha-paneld-entities.json")
                call.respondTextWriter(ContentType.Application.Json) {
                    entityLearning.writeExportJson(this)
                }
            }
        }
    }

    private fun entityFilterStatusJson(): String {
        val ids = runCatching { EntityFilterProtocol.normalize(config.dashboardEntityFilterIds) }
            .getOrDefault(emptyList())
        val hash = EntityFilterProtocol.hash(ids)
        return "{" +
            "\"enabled\":${config.dashboardEntityFilterEnabled}," +
            "\"entity_count\":${ids.size},\"filter_hash\":\"$hash\"," +
            "\"runtime\":${EntityFilterTelemetry.json()},\"learning\":${entityLearning.statusJson()}}"
    }

    /** Replace/toggle the complete experimental allow-list. Existing state supplies omitted fields,
     *  making `{\"enabled\":false}` a cheap A/B switch while the list remains stored on the panel. */
    private suspend fun handleEntityFilterPost(call: ApplicationCall) {
        val body = when (val receipt = receiveBoundedBody(call, EntityFilterProtocol.MAX_API_BODY_BYTES.toLong())) {
            is BoundedBodyReceipt.Received -> String(receipt.bytes, Charsets.UTF_8)
            BoundedBodyReceipt.TooLarge -> {
                call.respondText("request too large\n", status = HttpStatusCode.PayloadTooLarge)
                return
            }
            BoundedBodyReceipt.TimedOut -> {
                call.respondText("request timeout\n", status = HttpStatusCode.RequestTimeout)
                return
            }
        }
        val update = runCatching { EntityFilterProtocol.parseUpdate(body) }
            .getOrElse {
                call.respondText("invalid entity filter: ${it.message}\n", status = HttpStatusCode.BadRequest)
                return
            }
        if (update.mode == "automatic") {
            val requested = update.enabled ?: true
            if (!entityLearning.setEnabled(requested)) {
                call.respondText("configuration commit failed\n", status = HttpStatusCode.InternalServerError)
                return
            }
            call.respondText(entityFilterStatusJson(), ContentType.Application.Json)
            return
        }
        val ids = update.entityIds ?: config.dashboardEntityFilterIds
        val enabled = update.enabled ?: config.dashboardEntityFilterEnabled
        if (update.entityIds != null && ids.isEmpty()) {
            call.respondText("entity_ids must contain at least one valid entity\n", status = HttpStatusCode.BadRequest)
            return
        }
        if (enabled && ids.isEmpty()) {
            call.respondText("entity_ids required when enabled\n", status = HttpStatusCode.BadRequest)
            return
        }
        val committed = withContext(Dispatchers.IO) {
            if (update.mode == "manual" || update.entityIds != null) {
                config.commitDashboardManualEntityFilter(enabled, ids)
            } else {
                config.setDashboardEntityFilter(enabled, ids)
            }
        }
        if (!committed) {
            call.respondText("configuration commit failed\n", status = HttpStatusCode.InternalServerError)
            return
        }
        call.respondText(entityFilterStatusJson(), ContentType.Application.Json)
        onFilterChanged()
    }
}
