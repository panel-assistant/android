package io.github.maxlyth.hapaneld.logship

import android.util.Log
import io.github.maxlyth.hapaneld.control.CdpRelay
import io.github.maxlyth.hapaneld.util.HaWebSocketClients
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** One console message from the dashboard WebView, before formatting. [timestampMs] is CDP's epoch ms. */
internal data class ConsoleEvent(val level: Char, val text: String, val timestampMs: Double)

/**
 * Pure mapping from CDP event frames to [ConsoleEvent]s and from events to logcat-threadtime-shaped
 * lines, so the viewer's level filter and the shipper's syslog severity read a console line exactly as
 * they read a logcat line.
 */
internal object CdpConsoleMapper {
    const val TAG = "webview/console"
    private const val MAX_TEXT_CHARS = 4_096
    private val STYLE_DIRECTIVE = Regex("%c")

    /** Map one CDP frame, or null when it is not a console event this source reports. */
    fun map(frame: String): ConsoleEvent? {
        val message = runCatching { JSONObject(frame) }.getOrNull() ?: return null
        val params = message.optJSONObject("params") ?: return null
        return when (message.optString("method")) {
            "Runtime.consoleAPICalled" -> consoleApi(params)
            "Log.entryAdded" -> logEntry(params.optJSONObject("entry") ?: return null)
            else -> null
        }
    }

    private fun consoleApi(params: JSONObject): ConsoleEvent? {
        val type = params.optString("type")
        val level = when (type) {
            "error", "assert" -> 'E'
            "warning" -> 'W'
            "debug" -> 'D'
            // No message text: a clear or a group end is structure, not a log line.
            "clear", "endGroup" -> return null
            else -> 'I'
        }
        val text = joinArgs(params.optJSONArray("args") ?: JSONArray())
        val location = params.optJSONObject("stackTrace")
            ?.optJSONArray("callFrames")
            ?.optJSONObject(0)
            ?.let { frame -> location(frame.optString("url"), frame.optInt("lineNumber", -1)) }
        return ConsoleEvent(level, withLocation(text, location), params.optDouble("timestamp", 0.0))
    }

    private fun logEntry(entry: JSONObject): ConsoleEvent {
        val level = when (entry.optString("level")) {
            "error" -> 'E'
            "warning" -> 'W'
            "verbose" -> 'V'
            else -> 'I'
        }
        val location = location(entry.optString("url"), entry.optInt("lineNumber", -1))
        return ConsoleEvent(
            level,
            withLocation(entry.optString("text"), location),
            entry.optDouble("timestamp", 0.0),
        )
    }

    /** Render console arguments the way DevTools prints them: strings verbatim, `%c` styling dropped
     * together with the CSS argument each directive consumes. */
    private fun joinArgs(args: JSONArray): String {
        val parts = ArrayList<String>(args.length())
        var skipStyles = 0
        for (index in 0 until args.length()) {
            val arg = args.optJSONObject(index) ?: continue
            val rendered = render(arg)
            if (index == 0 && arg.optString("type") == "string") {
                skipStyles = STYLE_DIRECTIVE.findAll(rendered).count()
                parts.add(rendered.replace(STYLE_DIRECTIVE, "").trim())
                continue
            }
            if (skipStyles > 0 && arg.optString("type") == "string") {
                skipStyles--
                continue
            }
            parts.add(rendered)
        }
        return parts.joinToString(" ")
    }

    private fun render(arg: JSONObject): String = when {
        arg.optString("type") == "undefined" -> "undefined"
        arg.has("value") -> if (arg.isNull("value")) "null" else arg.get("value").toString()
        arg.has("unserializableValue") -> arg.optString("unserializableValue")
        arg.has("description") -> arg.optString("description")
        else -> arg.optString("type")
    }

    private fun location(url: String, zeroBasedLine: Int): String? =
        url.takeIf(String::isNotBlank)?.let { if (zeroBasedLine >= 0) "$it:${zeroBasedLine + 1}" else it }

    private fun withLocation(text: String, location: String?): String =
        if (location == null) text else "$text ($location)"

    /** One line in logcat threadtime shape. Newlines are folded so a stack trace cannot split an SSE
     * event or a syslog frame, and the text is bounded before redaction and fan-out. */
    fun format(level: Char, text: String, timestampMs: Double, zone: TimeZone = TimeZone.getDefault()): String {
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            .apply { timeZone = zone }
            .format(Date(timestampMs.toLong()))
        val flat = text.replace(Regex("\r\n|\r|\n"), " ⏎ ").let {
            if (it.length > MAX_TEXT_CHARS) it.take(MAX_TEXT_CHARS) + "…" else it
        }
        return "$stamp     0     0 $level $TAG: $flat"
    }
}

/**
 * Collapses a storm of identical console messages. The first occurrence passes at once; repeats within
 * [windowMs] of the previous one are counted, and one summary line reports the count when a different
 * message arrives, when the storm goes quiet for [windowMs], or every [maxHoldMs] during a sustained
 * storm. Identity is the level plus the REDACTED text, so two lines differing only in a secret merge and
 * the key never holds a secret. Nothing here delays a non-identical line.
 */
internal class ConsoleCoalescer(
    private val windowMs: Long = WINDOW_MS,
    private val maxHoldMs: Long = MAX_HOLD_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: TimeZone = TimeZone.getDefault(),
) {
    private var lastKey: String? = null
    private var lastLevel = 'I'
    private var lastSeenMs = 0L
    private var heldSinceMs = 0L
    private var suppressed = 0

    /** Lines to emit for [event], in order. */
    fun offer(event: ConsoleEvent): List<String> {
        val at = now()
        val key = event.level + LogCapture.redact(event.text)
        if (key == lastKey && at - lastSeenMs <= windowMs) {
            if (suppressed == 0) heldSinceMs = at
            suppressed++
            lastSeenMs = at
            return if (at - heldSinceMs >= maxHoldMs) listOfNotNull(summary(at)) else emptyList()
        }
        val out = ArrayList<String>(2)
        summary(at)?.let(out::add)
        lastKey = key
        lastLevel = event.level
        lastSeenMs = at
        out.add(CdpConsoleMapper.format(event.level, event.text, event.timestampMs, zone))
        return out
    }

    /** Periodic tick: report a held count once the storm has gone quiet, or when [force]d at detach. */
    fun flush(force: Boolean = false): List<String> {
        val at = now()
        if (suppressed == 0) return emptyList()
        if (!force && at - lastSeenMs <= windowMs && at - heldSinceMs < maxHoldMs) return emptyList()
        return listOfNotNull(summary(at))
    }

    private fun summary(at: Long): String? {
        if (suppressed == 0) return null
        val count = suppressed
        suppressed = 0
        heldSinceMs = at
        return CdpConsoleMapper.format(
            lastLevel,
            "previous message repeated $count more time${if (count == 1) "" else "s"}",
            at.toDouble(),
            zone,
        )
    }

    companion object {
        const val WINDOW_MS = 2_000L
        const val MAX_HOLD_MS = 10_000L
    }
}

/**
 * The producer behind [LogCapture.webView]. It never starts the CDP relay: it attaches only while the
 * relay the user started from `/inspect` is listening on loopback, and backs off cheaply otherwise.
 * It stays idle unless [enabled] (log shipping configured) holds. It reads events and never evaluates
 * anything in the page; every emit is non-blocking, so a slow sink cannot back-pressure the dashboard.
 */
internal class WebViewConsoleStream(
    private val enabled: () -> Boolean,
    private val port: Int = CdpRelay.PORT,
) {
    // High-water mark across reattachments: Runtime.enable replays the page's buffered console, which
    // is useful on first attach and a duplicate after the relay's idle timeout forces a reconnect.
    private var targetId: String? = null
    private var lastTimestampMs = 0.0

    suspend fun run(emit: (String) -> Unit) {
        if (!enabled()) {
            delay(DISABLED_POLL_MS)
            return
        }
        val target = withContext(Dispatchers.IO) { pageTarget() }
        if (target == null) {
            delay(ABSENT_POLL_MS)
            return
        }
        if (target.first != targetId) {
            targetId = target.first
            lastTimestampMs = 0.0
        }
        val client = HttpClient(OkHttp) {
            install(WebSockets)
            engine {
                config {
                    connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    // Keeps a quiet console inside the relay's idle timeout.
                    pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
                }
            }
        }
        val coalescer = ConsoleCoalescer()
        try {
            val session = HaWebSocketClients.open(client, target.second, MAX_FRAME_BYTES)
            try {
                session.send("""{"id":1,"method":"Runtime.enable"}""")
                session.send("""{"id":2,"method":"Log.enable"}""")
                Log.i(TAG, "attached to dashboard console")
                while (enabled()) {
                    val received = withTimeoutOrNull(TICK_MS) { session.incoming.receiveCatching() }
                    if (received == null) {
                        coalescer.flush().forEach(emit)
                        continue
                    }
                    val frame = received.getOrNull() ?: break
                    if (frame !is Frame.Text) continue
                    val event = CdpConsoleMapper.map(frame.readText()) ?: continue
                    if (event.timestampMs > 0.0 && event.timestampMs <= lastTimestampMs) continue
                    if (event.timestampMs > lastTimestampMs) lastTimestampMs = event.timestampMs
                    coalescer.offer(event).forEach(emit)
                }
            } finally {
                coalescer.flush(force = true).forEach(emit)
                runCatching { session.close() }
            }
        } finally {
            client.close()
        }
    }

    /** `(id, webSocketDebuggerUrl)` of the one page target, or null when the relay is not listening. */
    private fun pageTarget(): Pair<String, String>? {
        val body = try {
            val connection = URL("http://127.0.0.1:$port/json/list").openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT_MS.toInt()
            connection.readTimeout = READ_TIMEOUT_MS
            try {
                if (connection.responseCode != 200) return null
                connection.inputStream.use { input ->
                    val bytes = input.readNBytesCompat(MAX_LIST_BYTES)
                    String(bytes, Charsets.UTF_8)
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            return null
        }
        return selectPageTarget(body)
    }

    companion object {
        private const val TAG = "ha-paneld/wvconsole"
        private const val DISABLED_POLL_MS = 10_000L
        private const val ABSENT_POLL_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 500L
        private const val READ_TIMEOUT_MS = 2_000
        private const val PING_INTERVAL_MS = 60_000L
        private const val TICK_MS = 1_000L
        private const val MAX_FRAME_BYTES = 1L shl 20
        private const val MAX_LIST_BYTES = 256 * 1024

        /** The single `page` target; several pages are ambiguous, so none is chosen. */
        internal fun selectPageTarget(json: String): Pair<String, String>? {
            val list = runCatching { JSONArray(json) }.getOrNull() ?: return null
            val pages = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                .filter { it.optString("type") == "page" && it.optString("webSocketDebuggerUrl").isNotBlank() }
            val page = pages.singleOrNull() ?: return null
            return page.optString("id") to page.optString("webSocketDebuggerUrl")
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < max) {
        val read = read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
