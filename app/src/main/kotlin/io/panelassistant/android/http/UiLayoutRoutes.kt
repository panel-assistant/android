package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.util.Json
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

internal fun Route.uiLayoutRoutes(config: Config) {
    // Per-panel Canvas dashboard layout (opaque Gridstack JSON, stored in Config).
    get("/ui/layout") {
        call.respondText("""{"layout":${Json.str(config.uiDashboardLayout)}}""", ContentType.Application.Json)
    }
    post("/ui/layout") {
        config.uiDashboardLayout = (receiveBoundedFormParameters(
            call,
            PaneldServer.MAX_CONFIG_POST_BODY_BYTES,
        ) ?: return@post)["layout"].orEmpty()
        call.respondText("""{"ok":true}""", ContentType.Application.Json)
    }
}
