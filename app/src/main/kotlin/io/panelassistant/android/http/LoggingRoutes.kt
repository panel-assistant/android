package io.panelassistant.android.http

import io.panelassistant.android.logship.LogCapture
import io.panelassistant.android.logship.LogShipStatusProjection
import io.panelassistant.android.util.HelperClient
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
    systemLogAvailable: () -> Boolean,
    admitActiveRead: suspend (ApplicationCall) -> Boolean,
) {
    val captures = LogCaptures(logApp, logSystem, logWebView, webViewConsoleEnabled, systemLogAvailable)
    // The `logs` subscription family follows the capture the stream below would serve (protocol 20.4).
    manageTable()?.logSources = captures
    // Live log tail as Server-Sent Events (?source=app|system|webview). Feeds the Logs tab;
    // also curl-able (`curl -N .../api/v1/logs/stream`). Lines are pre-redacted.
    get("/logs/stream") {
        if (admitActiveRead(call)) handleLogStream(call, captures)
    }
}

/** What a log viewer's `source` selects: its capture, or the stream's refusal of it. */
private sealed interface LogSelection {
    class Selected(val capture: LogCapture) : LogSelection
    class Refused(val status: HttpStatusCode, val text: String) : LogSelection
}

/** The three captures and the one definition of which a viewer may follow, for the stream and the `logs` family. */
private class LogCaptures(
    private val app: LogCapture?,
    private val system: LogCapture?,
    private val webView: LogCapture?,
    private val webViewConsoleEnabled: () -> Boolean,
    private val systemLogAvailable: () -> Boolean,
) : io.panelassistant.android.ManageLogSources {
    suspend fun selection(source: String): LogSelection {
        val capture = when (source) {
            "app" -> app
            "system" -> if (withContext(Dispatchers.IO) { systemLogAvailable() }) system else {
                return LogSelection.Refused(HttpStatusCode.ServiceUnavailable, "system log needs a LOGCAT helper\n")
            }
            "webview" -> if (webViewConsoleEnabled()) webView else {
                return LogSelection.Refused(HttpStatusCode.ServiceUnavailable, "webview console needs log shipping configured\n")
            }
            else -> return LogSelection.Refused(HttpStatusCode.BadRequest, "unknown source '$source' (app|system|webview)\n")
        } ?: return LogSelection.Refused(HttpStatusCode.NotFound, "log viewer unavailable\n")
        return LogSelection.Selected(capture)
    }

    override suspend fun select(source: String): io.panelassistant.android.ManageLogSource? =
        (selection(source) as? LogSelection.Selected)?.capture?.let(::LogCaptureSource)
}

/** System logs require an authenticated helper that advertises LOGCAT. */
internal fun systemLogReadable(): Boolean = systemLogReadable(HelperClient::send)

internal fun systemLogReadable(send: (String) -> String?): Boolean =
    send("LOGCATCAPS") == "LOGCATCAPS 1"

// ---- live log stream (SSE) ----

/** Tail a [LogCapture] to the client as Server-Sent Events. Backlog first (ring snapshot while
 *  capture is already running for the shipper / another viewer, else a one-shot `logcat -d`
 *  dump), then live lines. A per-connection drop-oldest channel means a stalled browser can
 *  never back-pressure the capture; a 15s `: ping` comment detects dead peers so the
 *  subscription (and with it the logcat subprocess) is released. */
private suspend fun handleLogStream(call: ApplicationCall, captures: LogCaptures) {
    val cap = when (val selection = captures.selection(call.request.queryParameters["source"] ?: "app")) {
        is LogSelection.Selected -> selection.capture
        is LogSelection.Refused -> {
            call.respondText(selection.text, status = selection.status)
            return
        }
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

/** The app log capture as the `logs` family's source: the stream's backlog, then its live lines. */
private class LogCaptureSource(private val capture: LogCapture) : io.panelassistant.android.ManageLogSource {
    override fun backlog(): List<String> = capture.initialBacklog()

    /** Holds a viewer lease like a stream does, so subscribers count against the same viewer limit. */
    override fun follow(onLine: (String) -> Unit): AutoCloseable? {
        val lease = (capture.admitViewer() as? LogCapture.ViewerAdmission.Accepted)?.lease ?: return null
        val subscription = capture.subscribe(onLine)
        return AutoCloseable {
            runCatching { subscription.close() }
            runCatching { lease.close() }
        }
    }
}
