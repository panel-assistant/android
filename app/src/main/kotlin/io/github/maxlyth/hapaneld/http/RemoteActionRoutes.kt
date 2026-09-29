package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.security.SensitiveOperation
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

internal data class RemoteActionRouteDependencies(
    val authorizeSensitive: suspend (ApplicationCall, SensitiveOperation, String, String) -> Boolean,
    val admit: suspend (ApplicationCall, String) -> Unit,
)

/** HTTP handler for the software-navbar actions. Dashboard foregrounding remains routine; Reload
 * deliberately restarts a renderer and therefore shares the exact-request physical-approval policy
 * used by the other sensitive process and power operations. */
internal suspend fun handleRemoteAction(call: ApplicationCall, dependencies: RemoteActionRouteDependencies) {
    val parameters = receiveBoundedFormParameters(call) ?: return
    val action = parameters["a"]
    if (action !in REMOTE_ACTIONS) {
        call.respondText("bad-action\n", status = HttpStatusCode.BadRequest)
        return
    }
    val sensitive = when (action) {
        "reload" -> SensitiveOperation.DASHBOARD_RELOAD to "Reload the dashboard renderer"
        "reboot" -> SensitiveOperation.DEVICE_REBOOT to "Reboot this panel"
        else -> null
    }
    if (sensitive != null && !dependencies.authorizeSensitive(
            call,
            sensitive.first,
            exactHttpApprovalPayload(call, parameters.canonicalDigest()),
            sensitive.second,
        )
    ) return
    dependencies.admit(call, action!!)
}

/** One renderer-sensitive execution seam shared by the live queue and endpoint behavior tests. */
internal fun executeRemoteDashboardAction(
    action: String,
    dashboardPackage: String,
    launch: (String) -> Unit,
    reload: (String) -> Unit,
): Boolean = when (action) {
    "dashboard" -> { launch(dashboardPackage); true }
    "reload" -> { reload(dashboardPackage); true }
    else -> false
}

internal val REMOTE_ACTIONS = setOf(
    "back", "recents", "launcher", "admin_launcher", "dashboard", "reload", "reboot", "volup", "voldn",
)
