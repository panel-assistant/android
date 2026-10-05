package io.panelassistant.android.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun Route.screenshotRoutes(
    screenshots: ScreenshotCache,
    capture: () -> ByteArray?,
    admitActiveRead: suspend (ApplicationCall) -> Boolean,
) {
    // Live panel screenshot via root `screencap` (LAN-only like the rest of this surface).
    // Embedded scaled in the info page + linkable full-size; also usable as an HA camera
    // still_image_url. The card asks for ?cached=1 first so it can show the last successful
    // capture immediately, then requests a fresh image in the background. A successful live
    // capture atomically replaces the app-private placeholder; failed captures leave it intact.
    get("/screenshot.png") {
        if (!admitActiveRead(call)) return@get
        val cachedId = call.request.queryParameters["cached"]
        if (cachedId != null) {
            call.response.headers.append("Cache-Control", "private, max-age=31536000, immutable")
            val cached = withContext(Dispatchers.IO) { screenshots.read(cachedId) }
            if (cached != null) call.respondBytes(cached, ContentType.Image.PNG)
            else call.respondText("screenshot-unavailable\n", status = HttpStatusCode.ServiceUnavailable)
            return@get
        }
        call.response.headers.append("Cache-Control", "no-store")
        val png = withContext(Dispatchers.IO) { capture() }
        if (png != null && png.isNotEmpty()) {
            withContext(Dispatchers.IO) { screenshots.store(png) }?.let {
                call.response.headers.append("X-ha-paneld-Screenshot-Id", it)
            }
            call.respondBytes(png, ContentType.Image.PNG)
        } else {
            call.respondText("screenshot-unavailable\n", status = HttpStatusCode.ServiceUnavailable)
        }
    }
}
