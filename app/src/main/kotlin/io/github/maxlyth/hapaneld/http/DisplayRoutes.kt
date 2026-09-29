package io.github.maxlyth.hapaneld.http

import android.content.Context
import io.github.maxlyth.hapaneld.control.DensityController
import io.github.maxlyth.hapaneld.control.DisplaySizingObservation
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.i18n.Strings
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.github.maxlyth.hapaneld.util.Cached
import io.github.maxlyth.hapaneld.util.Json
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun Route.displayRoutes(
    appContext: Context,
    profile: DeviceProfile,
    density: DensityController,
    densityCache: Cached<DisplaySizingObservation>,
    recommendedDensity: Int?,
    recommendedFontScale: Float?,
    requestStrings: (ApplicationCall) -> Strings,
    snapInvalidate: () -> Unit,
    authorizeSensitive: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
    escapeHtml: (String) -> String,
    localizedHref: (String, Strings) -> String,
) {
    get("/display") {
        // The factory base is the `wm density` reset reference the sizing control restores;
        // the framework's stable density stands in only where that read is unavailable.
        val observation = withContext(Dispatchers.IO) {
            DisplayGeometryReport.observe(appContext)?.let { framework ->
                framework.copy(factoryBaseDpi = densityCache.get().base ?: framework.factoryBaseDpi)
            }
        }
        if (observation == null) {
            call.respondText("""{"error":"display-unavailable"}""", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
            return@get
        }
        val profiled = profile.displayGeometry(observation.physicalWidthPx, observation.physicalHeightPx)
        call.respondText(
            DisplayGeometryReport.json(observation, profiled, recommendedDensity).toString(),
            ContentType.Application.Json,
        )
    }
    post("/display/density") {
        val strings = requestStrings(call)
        val p = receiveBoundedFormParameters(call) ?: return@post
        val action = p["action"]                          // "reset" | "rec" (buttons)
        val d = p["density"]?.trim()?.toIntOrNull()       // custom density (Apply)
        val f = p["font"]?.trim()?.toFloatOrNull()        // custom font scale (Apply)
        if (!authorizeSensitive(
                call,
                SensitiveOperation.DISPLAY_CONFIGURATION,
                exactHttpApprovalPayload(call, p.canonicalDigest()),
                strings.get("install.display.approval"),
            )
        ) return@post
        val ok = when (action) {
            "reset" -> DensityController.allApplied(density.reset(), density.resetFontScale())
            "rec" -> DensityController.allApplied(
                recommendedDensity?.let { density.set(it) },
                recommendedFontScale?.let { density.setFontScale(it) },
            )
            else -> {  // Apply: set whichever fields were provided
                DensityController.allApplied(
                    d?.let { density.set(it) },
                    f?.let { density.setFontScale(it) },
                )
            }
        }
        // Prime the density cache with the KNOWN result so the redirected Install card shows it
        // at once — reading `wm density` back immediately after a change can still return the
        // pre-write override for a second or two (the change is async), which flashed a stale
        // value on the page until a manual reload. Only the just-changed field could race, so
        // we take the value we set (d / recommendedDensity / base) and only re-read the
        // unchanged fields (which are stable).
        val observedSizing = density.observeSizing()
        val base = observedSizing.base
        val postDpi = when (action) {
            "reset" -> base
            "rec" -> recommendedDensity ?: observedSizing.current
            else -> d ?: observedSizing.current
        }
        val postFont = when (action) {
            "reset" -> 1.0f
            "rec" -> recommendedFontScale ?: observedSizing.fontScale
            else -> f ?: observedSizing.fontScale
        }
        snapInvalidate()
        if (ok) densityCache.set(DisplaySizingObservation(postDpi, base, postFont))
        val message = if (ok) {
            strings.get("install.display.result.applied")
        } else {
            strings.get("install.display.result.failed")
        }
        val returnTo = localizedHref("install#cfg-display", strings)
        val responseStatus = if (ok) HttpStatusCode.OK else HttpStatusCode.InternalServerError
        if (call.request.headers["Accept"]?.contains("application/json") == true) {
            call.respondText(
                "{" +
                    "\"ok\":$ok,\"status\":\"${if (ok) "applied" else "apply-failed"}\"," +
                    "\"message\":${Json.str(message)},\"return_to\":${Json.str(returnTo)}}",
                ContentType.Application.Json,
                responseStatus,
            )
        } else {
            call.respondText(
                "<!doctype html><base href=\"/\"><meta charset=utf-8>" +
                    (if (ok) "<meta http-equiv=refresh content='1;url=${escapeHtml(returnTo)}'>" else "") +
                    "<body style='font-family:system-ui;background:#111;color:#eee;padding:20px'>" +
                    escapeHtml(message) + (if (ok) "…" else " <a href='${escapeHtml(returnTo)}' style='color:#9cf'>${escapeHtml(strings.get("install.display.return"))}</a>") + "</body>",
                ContentType.Text.Html,
                responseStatus,
            )
        }
    }
}
