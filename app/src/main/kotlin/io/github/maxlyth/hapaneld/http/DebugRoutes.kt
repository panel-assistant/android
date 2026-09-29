package io.github.maxlyth.hapaneld.http

import android.content.Context
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.control.AdbController
import io.github.maxlyth.hapaneld.control.CdpRelay
import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

internal fun Route.sensorTraceRoute() {
    // Debug-only sensor trace (RAM ring buffer, on by default) for fit-testing the
    // auto-brightness + proximity filters. CSV by default (drop into a plot); ?format=json
    // for programmatic use / a future on-panel chart. Not an HA/MQTT surface.
    get("/sensortrace") {
        if (call.request.queryParameters["format"] == "json") {
            call.respondText(io.github.maxlyth.hapaneld.sensors.SensorTrace.toJson(), ContentType.Application.Json)
        } else {
            call.respondText(io.github.maxlyth.hapaneld.sensors.SensorTrace.toCsv(), ContentType("text", "csv"))
        }
    }

}

internal fun Route.debugInspectionRoutes(
    appContext: Context,
    config: Config,
    inspectLock: Any,
    isStopping: () -> Boolean,
    authorizeSensitive: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
) {
    // 1-click WebView DevTools: expose the dashboard's CDP socket to the LAN (root relay)
    // so the user can chrome://inspect with no adb. See CdpRelay.
    get("/inspect") {
        val status = when {
            CdpRelay.running -> "started"
            config.hardenedSecurityEnabled -> "hardened-disabled"
            else -> "off"
        }
        call.respondText(inspectJson(config, status), ContentType.Application.Json)
    }
    post("/inspect/start") {
        if (rejectHardenedDevToolsRelay(call, config)) return@post
        if (!authorizeSensitive(
                call,
                SensitiveOperation.DEVTOOLS_ENABLE,
                exactHttpApprovalPayload(call, sha256Hex(ByteArray(0))),
                "Expose this panel's WebView developer tools to the LAN",
            )
        ) return@post
        val status = synchronized(inspectLock) {
            if (isStopping()) "off" else CdpRelay.start(appContext)
        }
        call.respondText(inspectJson(config, status), ContentType.Application.Json)
    }
    post("/inspect/stop") {
        synchronized(inspectLock) {
            if (CdpRelay.running) CdpRelay.stop()
            if (config.hardenedSecurityEnabled) AdbController(appContext, config).reassert()
        }
        call.respondText(inspectJson(config, "off"), ContentType.Application.Json)
    }
}

private suspend fun rejectHardenedDevToolsRelay(call: ApplicationCall, config: Config): Boolean {
    if (!config.hardenedSecurityEnabled) return false
    call.respondText(
        """{"ok":false,"error":"devtools-incompatible-with-hardened-mode","message":"Switch to Relaxed mode before exposing WebView developer tools to the LAN."}""",
        ContentType.Application.Json,
        HttpStatusCode.Conflict,
    )
    return true
}


private fun inspectJson(config: Config, status: String): String =
    """{"running":${CdpRelay.running},"port":${CdpRelay.PORT},"status":"$status","start_allowed":${!config.hardenedSecurityEnabled}}"""
