package io.panelassistant.android.http

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.panelassistant.android.logship.LogCapture
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggingRoutesTest {
    @Test fun `system log availability requires LOGCAT capability`() {
        for (reply in listOf(null, "ERR", "OK", "LOGCATCAPS 0", "LOGCATCAPS 1")) {
            assertEquals("capability reply: $reply", reply == "LOGCATCAPS 1", systemLogReadable { command ->
                assertEquals("LOGCATCAPS", command)
                reply
            })
        }
    }

    @Test fun `full mount preserves logging refusals`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val cases = listOf(
                    "app" to (HttpStatusCode.NotFound to "log viewer unavailable\n"),
                    "webview" to (HttpStatusCode.ServiceUnavailable to "webview console needs log shipping configured\n"),
                    "system" to (HttpStatusCode.ServiceUnavailable to "system log needs a LOGCAT helper\n"),
                    "invalid" to (HttpStatusCode.BadRequest to "unknown source 'invalid' (app|system|webview)\n"),
                )
                for ((source, expected) in cases) {
                    val response = client.get("/api/v1/logs/stream?source=$source")
                    assertEquals(expected.first, response.status)
                    assertEquals(expected.second, response.bodyAsText())
                    assertEquals("DENY", response.headers["X-Frame-Options"])
                }
                val refused = client.get("/api/v1/logs/stream") { header("Sec-Fetch-Site", "cross-site") }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertEquals("refused: this panel does not serve active reads to another site.\n", refused.bodyAsText())
            }
        }
    }

    /** The `logs` family over the socket follows the selected source under the stream's checks (protocol 20.4). */
    @Test fun `each log source over the socket shows its own lines and the stream's refusals are unavailable`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun capture(line: String) = LogCapture(
            scope, streamCmd = emptyList(), dumpCmd = { emptyList() },
            lineStream = { emit -> emit(line); awaitCancellation() },
        )
        val systemReadable = AtomicBoolean(true)
        val consoleEnabled = AtomicBoolean(true)
        try {
            PaneldServerHttpFixture(
                logApp = capture("app-source-line"),
                logSystem = capture("system-source-line"),
                logWebView = capture("webview-source-line"),
                webViewConsoleEnabled = { consoleEnabled.get() },
                systemLogAvailable = { systemReadable.get() },
            ).use { fixture ->
                testApplication {
                    application { fixture.mount(this) }
                    createClient { install(WebSockets) }.webSocket("/ui") {
                        var next = 0
                        suspend fun subscribe(source: String): String {
                            val id = "logs${next++}"
                            send(Frame.Text(JSONObject().put("kind", "manage").put("command_id", id).put("op", "subscribe")
                                .put("args", JSONObject().put("family", "logs").put("source", source)).toString()))
                            return id
                        }
                        suspend fun unsubscribe(id: String) = send(Frame.Text(JSONObject().put("kind", "manage")
                            .put("command_id", "u$id").put("op", "unsubscribe").put("args", JSONObject().put("subscription", id)).toString()))
                        /** The observations [id] sends until one satisfies [done], as "state value". */
                        suspend fun observe(id: String, done: (String) -> Boolean): List<String> = withTimeout(10_000) {
                            val seen = mutableListOf<String>()
                            while (seen.none(done)) {
                                val frame = JSONObject((incoming.receive() as Frame.Text).readText())
                                if (frame.optString("subscription") != id) continue
                                val observation = frame.getJSONArray("observations").getJSONObject(0)
                                seen += observation.getString("state") + " " + observation.get("value")
                            }
                            seen
                        }

                        for (source in listOf("app", "system", "webview")) {
                            val id = subscribe(source)
                            val seen = observe(id) { it.contains("-source-line") }
                            assertTrue(seen.toString(), seen.all { it.startsWith("known ") })
                            val lines = seen.joinToString()
                            assertTrue(lines, lines.contains("$source-source-line"))
                            for (other in listOf("app", "system", "webview") - source) {
                                assertFalse("$source shows $other: $lines", lines.contains("$other-source-line"))
                            }
                            unsubscribe(id)
                        }

                        systemReadable.set(false)
                        consoleEnabled.set(false)
                        for (source in listOf("system", "webview", "invalid")) {
                            val id = subscribe(source)
                            assertEquals(listOf("unavailable null"), observe(id) { it.startsWith("unavailable") })
                            unsubscribe(id)
                        }
                    }
                }
            }
        } finally {
            scope.cancel()
        }
    }
}
