package io.panelassistant.android.http

import io.panelassistant.android.camera.CameraRefusal
import io.panelassistant.android.camera.CameraResolution
import io.panelassistant.android.camera.CameraSurface
import io.panelassistant.android.camera.CameraState
import io.panelassistant.android.camera.SnapshotResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun Route.cameraRoutes(
    camera: CameraSurface,
    admitActiveRead: suspend (ApplicationCall, Boolean) -> Boolean,
) {
    // Camera trial. The camera opens only for the duration of this request and closes
    // when no other subscriber
    // remains, so a caller must expect the open cost on every snapshot. No detail beyond
    // the refusal token in the body — the finer classification lives in /api/v1/status.
    get("/camera/snapshot.jpg") {
        if (!admitActiveRead(call, true)) return@get
        val requestedRaw = call.request.queryParameters["res"]
        val requested = when {
            requestedRaw == null -> null
            else -> CameraResolution.parse(requestedRaw) ?: run {
                call.respondText(
                    "unknown res '$requestedRaw' (480p|720p|1080p)\n",
                    status = HttpStatusCode.BadRequest,
                )
                return@get
            }
        }
        call.response.headers.append("Cache-Control", "no-store")
        val captured = withContext(Dispatchers.IO) { camera.snapshot(requested) }
        val result = if (captured is SnapshotResult.Jpeg && camera.presentation().state == CameraState.DISABLED) {
            SnapshotResult.Refused(CameraRefusal.DISABLED)
        } else captured
        when (result) {
            is SnapshotResult.Jpeg -> call.respondBytes(result.bytes, ContentType.Image.JPEG)
            is SnapshotResult.Refused -> call.respondText(
                "${result.reason.token}\n",
                status = HttpStatusCode.fromValue(
                    CameraRefusal.snapshotStatusCode(result.reason),
                ),
            )
        }
    }
    // Exactly the object `/api/v1/status` carries under `camera`, served alone so the
    // Dashboard's camera card can poll it every couple of seconds without rebuilding
    // the whole status document. One projection, one renderer: the bytes are produced
    // by the same `statusJson()`, so the card and the status object cannot drift.
    //
    // Unadmitted for the same reason `/sensors` is: there is no work here to gate. The
    // call reads the session's own state under its lock and never opens the camera, so
    // an idle panel stays at zero cost, and the identical bytes are already readable
    // from `/api/v1/status` — this adds no exposure, only a cheaper way to ask.
    get("/camera/status") {
        call.response.headers.append("Cache-Control", "no-store")
        call.respondText(camera.presentation().statusJson(), ContentType.Application.Json)
    }
}
