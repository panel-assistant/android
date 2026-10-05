package io.panelassistant.android.http

import io.panelassistant.android.control.PrivilegeRoute
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText

internal sealed class TapCaptureResult {
    data class Success(
        val inputRoute: PrivilegeRoute,
        val screenshotRoute: PrivilegeRoute,
        val png: ByteArray,
    ) : TapCaptureResult()
    data class TapFailed(val inputRoute: PrivilegeRoute? = null) : TapCaptureResult()
    data class ScreenshotFailed(val inputRoute: PrivilegeRoute) : TapCaptureResult()
    data class CompletionUnknown(val inputRoute: PrivilegeRoute? = null) : TapCaptureResult()
    data object HardenedRefusal : TapCaptureResult()
    data object Expired : TapCaptureResult()
    data object NotExecuted : TapCaptureResult()
}

internal suspend fun respondTapCaptureResult(
    call: ApplicationCall,
    requestId: Long,
    result: TapCaptureResult,
    stopping: Boolean,
    cacheScreenshot: suspend (ByteArray) -> String?,
) {
    call.response.headers.append("X-ha-paneld-Input-Id", requestId.toString())
    fun inputRoute(route: PrivilegeRoute?) {
        route?.let { call.response.headers.append("X-ha-paneld-Input-Route", it.name.lowercase()) }
    }
    suspend fun error(code: HttpStatusCode, name: String, route: PrivilegeRoute? = null) {
        inputRoute(route)
        call.respondText(
            """{"ok":false,"error":"$name"}""",
            ContentType.Application.Json,
            code,
        )
    }
    when (result) {
        is TapCaptureResult.Success -> {
            inputRoute(result.inputRoute)
            call.response.headers.append(
                "X-ha-paneld-Screenshot-Route",
                result.screenshotRoute.name.lowercase(),
            )
            call.response.headers.append("Cache-Control", "no-store")
            cacheScreenshot(result.png)?.let { id ->
                call.response.headers.append("X-ha-paneld-Screenshot-Id", id)
            }
            call.respondBytes(result.png, ContentType.Image.PNG, HttpStatusCode.OK)
        }
        is TapCaptureResult.TapFailed ->
            error(HttpStatusCode.BadGateway, "tap-failed", result.inputRoute)
        is TapCaptureResult.ScreenshotFailed ->
            error(HttpStatusCode.ServiceUnavailable, "screenshot-unavailable", result.inputRoute)
        is TapCaptureResult.CompletionUnknown ->
            error(HttpStatusCode.GatewayTimeout, "completion-unknown", result.inputRoute)
        TapCaptureResult.HardenedRefusal ->
            error(HttpStatusCode.Forbidden, "remote-input-disabled")
        TapCaptureResult.Expired ->
            error(HttpStatusCode.GatewayTimeout, "tap-expired")
        TapCaptureResult.NotExecuted -> error(
            if (stopping) HttpStatusCode.ServiceUnavailable else HttpStatusCode.Conflict,
            if (stopping) "control-plane-stopping" else "tap-superseded",
        )
    }
}
