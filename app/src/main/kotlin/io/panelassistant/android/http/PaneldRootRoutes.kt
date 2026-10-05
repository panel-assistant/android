package io.panelassistant.android.http

import io.panelassistant.android.util.GuardDbProcessAdmission
import io.panelassistant.android.util.isLocalSource
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.uri
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing

/** The production HTTP root, also mounted by JVM route tests. */
internal fun Application.paneldRoot(
    allowedHosts: () -> Set<String>,
    setupNeedsUser: () -> Boolean,
    setupRedirectLocation: (ApplicationCall) -> String,
    routes: Route.() -> Unit,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        // Apply privacy and framing headers before any guard can finish the call.
        if (call.request.uri.substringBefore('?') == HA_OAUTH_CALLBACK_PATH) call.noStoreHaOAuth()
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.response.headers.append("X-Frame-Options", "DENY")
        call.response.headers.append("Content-Security-Policy", "frame-ancestors 'none'")
        if (!isLocalSource(call.request.origin.remoteAddress)) {
            call.respondText("forbidden\n", status = HttpStatusCode.Forbidden)
            return@intercept finish()
        }
        val embed = call.admitEmbedMode()
        if (GuardDbProcessAdmission.maintenanceRequired()) {
            call.respondText("guard database maintenance owns this process\n", status = HttpStatusCode.Locked)
            return@intercept finish()
        }
        if (embed == null && call.request.uri.substringBefore('?') in PaneldServer.WIZARD_REDIRECT_PAGES &&
            call.request.cookies["wiz_escape"] == null && setupNeedsUser()
        ) {
            call.respondRedirect(setupRedirectLocation(call))
            return@intercept finish()
        }
        if (!OriginGuard.allowed(
                call.request.origin.method.value,
                call.request.headers["Origin"],
                call.request.headers["Referer"],
                call.request.headers["Host"],
            )
        ) {
            call.respondText("cross-origin refused\n", status = HttpStatusCode.Forbidden)
            return@intercept finish()
        }
        if (!OriginGuard.hostAllowed(call.request.headers["Host"], allowedHosts())) {
            call.respondText("host not allowed\n", status = HttpStatusCode.Forbidden)
            return@intercept finish()
        }
        if (!call.admitEmbedProof(PanelAssistantEmbedKeys.instance)) return@intercept finish()
    }
    routing(routes)
}
