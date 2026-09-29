package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.logship.LogCapture
import io.github.maxlyth.hapaneld.logship.LogShipStatusProjection
import io.github.maxlyth.hapaneld.util.HelperClient
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal fun Route.loggingRoutes(
    logApp: LogCapture?,
    logSystem: LogCapture?,
    logWebView: LogCapture?,
    webViewConsoleEnabled: () -> Boolean,
    logShipStatus: () -> LogShipStatusProjection,
    admitActiveRead: suspend (ApplicationCall) -> Boolean,
) {
    get("/logship/status") {
        // Passive read of what the shipper is actually doing, including Dashboard state.
        // Distinct from probe-log-sink, which transmits: this one only reports, so it
        // is safe to poll while a page is open.
        call.respondText(logShipStatusJson(logShipStatus()), ContentType.Application.Json)
    }
    // Live log tail as Server-Sent Events (?source=app|system|webview). Feeds the Logs tab;
    // also curl-able (`curl -N .../api/v1/logs/stream`). Lines are pre-redacted.
    get("/logs/stream") {
        if (admitActiveRead(call)) handleLogStream(call, logApp, logSystem, logWebView, webViewConsoleEnabled)
    }
}

// ---- live log stream (SSE) ----

/** Tail a [LogCapture] to the client as Server-Sent Events. Backlog first (ring snapshot while
 *  capture is already running for the shipper / another viewer, else a one-shot `logcat -d`
 *  dump), then live lines. A per-connection drop-oldest channel means a stalled browser can
 *  never back-pressure the capture; a 15s `: ping` comment detects dead peers so the
 *  subscription (and with it the logcat subprocess) is released. */
private suspend fun handleLogStream(
    call: ApplicationCall,
    logApp: LogCapture?,
    logSystem: LogCapture?,
    logWebView: LogCapture?,
    webViewConsoleEnabled: () -> Boolean,
) {
    val cap = when (val src = call.request.queryParameters["source"] ?: "app") {
        "app" -> logApp
        "system" -> if (withContext(Dispatchers.IO) {
                Su.availableCachedIsolated() || HelperClient.send("LOGCATCAPS") == "LOGCATCAPS 1"
            }) logSystem else {
            call.respondText("system log needs root or a LOGCAT helper\n", status = HttpStatusCode.ServiceUnavailable)
            return
        }
        "webview" -> if (webViewConsoleEnabled()) logWebView else {
            call.respondText(
                "webview console needs log shipping configured\n",
                status = HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        else -> {
            call.respondText("unknown source '$src' (app|system|webview)\n", status = HttpStatusCode.BadRequest)
            return
        }
    }
    if (cap == null) {
        call.respondText("log viewer unavailable\n", status = HttpStatusCode.NotFound)
        return
    }
    val viewer = when (val admission = cap.admitViewer()) {
        is LogCapture.ViewerAdmission.Accepted -> admission.lease
        LogCapture.ViewerAdmission.CapacityExceeded -> {
            call.response.headers.append("Retry-After", "5")
            call.respondText("too many live log viewers\n", status = HttpStatusCode.TooManyRequests)
            return
        }
        LogCapture.ViewerAdmission.Unavailable -> {
            call.respondText("log viewer unavailable\n", status = HttpStatusCode.ServiceUnavailable)
            return
        }
    }
    try {
        // Backlog BEFORE subscribing: a few ms of lines can fall in the gap, which beats the visible
        // duplicates the opposite order produces (the dump overlaps the live stream's first lines).
        val backlog = withContext(Dispatchers.IO) { cap.initialBacklog() }
        val chan = Channel<String>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val sub = cap.subscribe { chan.trySend(it) }
        try {
            call.response.headers.append("Cache-Control", "no-cache")
            call.respondTextWriter(ContentType.Text.EventStream) {
                for (line in backlog) write(logSseEvent(line))
                flush()
                while (true) {
                    val line = withTimeoutOrNull(15_000) { chan.receive() }
                    write(if (line == null) ": ping\n\n" else logSseEvent(line))
                    flush()
                }
            }
        } finally {
            runCatching { sub.close() }
            chan.close()
        }
    } finally {
        runCatching { viewer.close() }
    }
}
