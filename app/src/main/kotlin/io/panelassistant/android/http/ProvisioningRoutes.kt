package io.panelassistant.android.http

import io.panelassistant.android.provisioning.PROVISIONING_PLAN_SCHEMA
import io.panelassistant.android.provisioning.ProvisioningActivationSnapshot
import io.panelassistant.android.provisioning.ProvisioningPlan
import io.panelassistant.android.provisioning.ProvisioningReadResult
import io.panelassistant.android.provisioning.ProvisioningReader
import io.panelassistant.android.provisioning.ProvisioningTextRenderer
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal data class ProvisioningRouteDependencies(
    val reader: ProvisioningReader,
    /** Capture the registry phase, generation, and exact active immutable ref in one read. */
    val activation: () -> ProvisioningActivationSnapshot,
)

/**
 * Read-only profile-driven guidance. Mount beneath the guarded `/api/v1` route; no profile bytes,
 * commands, action tokens, or network artifact lookups are accepted here.
 */
internal fun Route.provisioningRoutes(dependencies: ProvisioningRouteDependencies) {
    route("/provisioning") {
        get("/plan") {
            val plan = call.readStablePlan(dependencies, json = true)
            if (plan != null) call.respondProvisioningJson(plan)
        }
        get("/plan.txt") {
            val plan = call.readStablePlan(dependencies, json = false)
            if (plan != null) {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respondText(ProvisioningTextRenderer.render(plan), ContentType.Text.Plain)
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.readStablePlan(
    dependencies: ProvisioningRouteDependencies,
    json: Boolean,
): ProvisioningPlan? {
    val snapshot = runCatching(dependencies.activation).getOrNull()
    val initialResult = if (snapshot == null || !snapshot.isStableFor(dependencies.reader.expectedProfileRef)) {
        ProvisioningReadResult.Unavailable("profile_activation_unstable")
    } else {
        try {
            dependencies.reader.plan(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ProvisioningReadResult.Unavailable("provisioning_plan_unavailable")
        }
    }
    // Observation can take seconds on old WebView providers. Recheck immediately before responding so
    // a concurrently staged activation cannot receive one last 200 from an earlier registry snapshot.
    val result = if (initialResult is ProvisioningReadResult.Ready) {
        val finalSnapshot = runCatching(dependencies.activation).getOrNull()
        if (finalSnapshot == snapshot &&
            finalSnapshot?.isStableFor(dependencies.reader.expectedProfileRef) == true
        ) {
            initialResult
        } else {
            ProvisioningReadResult.Unavailable("profile_activation_unstable")
        }
    } else {
        initialResult
    }
    return when (result) {
        is ProvisioningReadResult.Ready -> result.plan
        is ProvisioningReadResult.Unavailable -> {
            response.headers.append(HttpHeaders.CacheControl, "no-store")
            response.headers.append(HttpHeaders.RetryAfter, "1")
            if (json) {
                respondText(
                    JSONObject()
                        .put("ok", false)
                        .put("error", result.reasonCode)
                        .toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            } else {
                respondText(
                    "provisioning plan unavailable\n",
                    ContentType.Text.Plain,
                    HttpStatusCode.ServiceUnavailable,
                )
            }
            null
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondProvisioningJson(plan: ProvisioningPlan) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText(plan.toJson().toString(), ContentType.Application.Json)
}

private fun ProvisioningPlan.toJson(): JSONObject = JSONObject()
    .put("plan_schema", PROVISIONING_PLAN_SCHEMA)
    .put(
        "core",
        JSONObject()
            .put("version", core.version)
            .put("version_code", core.versionCode),
    )
    .put(
        "profile",
        JSONObject()
            .put("id", profile.ref.id)
            .put("display_name", ProvisioningTextRenderer.sanitizeIdentity(profile.displayName))
            .put("revision", profile.ref.revision)
            .put("origin", profile.origin.name.lowercase())
            .put("content_version", profile.contentVersion),
    )
    .put(
        "activation",
        JSONObject()
            .put("state", activation.phase.name.lowercase())
            .put("generation", activation.generation),
    )
    .put("state", state.name.lowercase())
    .put(
        "items",
        JSONArray(items.map { item ->
            JSONObject()
                .put("id", item.id)
                .put("importance", item.importance.name.lowercase())
                .put("status", item.status.name.lowercase())
                .put("executor", item.executor.name.lowercase())
                .put("desired_state", item.desiredState)
                .put("observed_state", item.observedState)
                .put("reason_code", item.reasonCode)
        }),
    )
    .put(
        "issues",
        JSONArray(issues.map { issue -> JSONObject().put("code", issue.code) }),
    )
