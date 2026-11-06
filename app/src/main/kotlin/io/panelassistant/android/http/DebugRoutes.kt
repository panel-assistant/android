package io.panelassistant.android.http

import android.content.Context
import io.panelassistant.android.Config
import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.CdpRelay
import io.panelassistant.android.security.SensitiveOperation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

internal fun Route.debugInspectionRoutes(
    appContext: Context,
    config: Config,
    inspectLock: Any,
    isStopping: () -> Boolean,
    authorizeSensitive: suspend (OpCall, SensitiveOperation, String, String) -> Boolean,
) = ops {
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
        val stopped = synchronized(inspectLock) {
            CdpRelay.stop().also { stopped ->
                if (stopped && config.hardenedSecurityEnabled) AdbController(appContext, config).reassert()
            }
        }
        call.respondText(inspectJson(config, if (stopped) "off" else "failed"), ContentType.Application.Json)
    }
}

private suspend fun rejectHardenedDevToolsRelay(call: OpCall, config: Config): Boolean {
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
