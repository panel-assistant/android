package io.panelassistant.android.http

import android.content.Context
import android.util.Log
import io.panelassistant.android.Config
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.util.GenerationSingleFlight
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun Route.rendererMaintenanceRoutes(
    appContext: Context,
    config: Config,
    system: SystemController,
    scope: CoroutineScope,
    clearStorageGate: GenerationSingleFlight,
    isStopping: () -> Boolean,
    onRepairCompanionUrl: () -> Boolean,
    invalidateCompanionObservation: () -> Unit,
    authorizeSensitive: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
) {
    // Clear the built-in renderer's browsing data (localStorage/IndexedDB/caches/cookies)
    // — the remote heal for a corrupted-storage dashboard that survives plain reloads.
    // Sign-in is NOT stored there (the external-auth bridge holds the token in Config),
    // so this never logs the panel out. Relaunches the built-in renderer when it's the
    // active dashboard so it comes back on a clean slate. WebView APIs are UI-thread-only.
    post("/dashboard/clear-storage") {
        if (!authorizeSensitive(
                call,
                SensitiveOperation.DASHBOARD_STORAGE_CLEAR,
                exactHttpApprovalPayload(call, sha256Hex(ByteArray(0))),
                "Clear the built-in dashboard's browsing data",
            )
        ) return@post
        val token = clearStorageGate.claim()
        if (token == null) {
            call.respondText(
                """{"status":"busy"}""",
                ContentType.Application.Json,
                if (isStopping()) HttpStatusCode.ServiceUnavailable else HttpStatusCode.Conflict,
            )
            return@post
        }
        val posted = android.os.Handler(android.os.Looper.getMainLooper()).post storage@{
            val cost = FeatureCosts.registry.span(FeatureCostOperation.DASHBOARD_STORAGE_CLEAR)
            try {
                if (!clearStorageGate.isCurrent(token) || isStopping()) {
                    cost.outcome(FeatureCostOutcome.CANCELLED)
                    return@storage
                }
                android.webkit.WebStorage.getInstance().deleteAllData()
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                // The HTTP resource cache is per-application but only reachable through a
                // WebView instance — a throwaway one clears it for every WebView we host
                // (a corrupted cached asset is exactly what this heal exists for).
                runCatching {
                    android.webkit.WebView(appContext).apply {
                        settings.allowContentAccess = false
                        settings.allowFileAccess = false
                        clearCache(true)
                        destroy()
                    }
                }
                if (config.dashboardPackage == "builtin" && !isStopping()) {
                    // Privileged-first relaunch (BAL rules block a plain startActivity
                    // from a service context) — off the main thread, it may shell out.
                    scope.launch {
                        runCatching {
                            system.reloadDashboard(
                                SystemController.BUILTIN_DASHBOARD,
                                reason = "clearing the dashboard’s stored data",
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                cost.outcome(FeatureCostOutcome.FAILURE)
                Log.w("ha-paneld/http", "dashboard storage clear failed", error)
            } finally {
                clearStorageGate.finish(token)
                cost.close()
            }
        }
        if (!posted) {
            clearStorageGate.finish(token)
            FeatureCosts.registry.recordDropped(FeatureCostOperation.DASHBOARD_STORAGE_CLEAR)
            call.respondText(
                """{"status":"stopping"}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return@post
        }
        call.respondText(
            """{"status":"started"}""",
            ContentType.Application.Json,
            HttpStatusCode.Accepted,
        )
    }
    // Repair a Companion server row with an empty internal_url (HA 2026.7 "Missing Host
    // header" incident). Fire-and-forget: the repair force-stops + relaunches the Companion
    // off-thread; invalidate the health cache so the warning clears on the next poll.
    post("/companion/repair-url") {
        if (!authorizeSensitive(
                call,
                SensitiveOperation.COMPANION_REPAIR,
                exactHttpApprovalPayload(call, sha256Hex(ByteArray(0))),
                "Repair and relaunch the Home Assistant Companion",
            )
        ) return@post
        val started = onRepairCompanionUrl()
        if (started) invalidateCompanionObservation()
        call.respondText("""{"status":"${if (started) "started" else "busy"}"}""", ContentType.Application.Json)
    }
}
