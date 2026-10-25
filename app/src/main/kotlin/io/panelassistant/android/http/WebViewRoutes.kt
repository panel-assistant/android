package io.panelassistant.android.http

import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.util.WebViewUpdate
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owner-started provider operation; the native session calls the same owner with its own admission. */
internal fun Route.webViewRoutes(
    owner: WebViewUpdate,
    authorize: suspend (OpCall, SensitiveOperation, String, String) -> Boolean,
) = ops {
    get("/webview", name = "webview.observe") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondText(withContext(Dispatchers.IO) { owner.observe().toString() }, ContentType.Application.Json)
    }
    post("/webview", name = "webview.install") {
        val parameters = receiveBoundedConfigParameters(call, 1024L) ?: return@post
        if (!authorize(call, SensitiveOperation.APK_INSTALL, exactHttpApprovalPayload(call, parameters.canonicalDigest()), "Update System WebView")) return@post
        val answer = withContext(Dispatchers.IO) { owner.install(parameters["request_id"], parameters["artifact_sha256"]) }
        call.respondText(answer.body.toString(), ContentType.Application.Json, HttpStatusCode.fromValue(answer.status))
    }
}
