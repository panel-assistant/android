package io.panelassistant.android.http

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.InteractiveController
import io.panelassistant.android.control.SystemController
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What [SocketHttpParityMatrixTest] cannot drive on the read server: the Controls card's actions, which need the
 * remote control owner, and a failed route's status beside its outcome.
 */
class SocketDoorParityTest {
    @Test fun theControlsCardActionsAreServedOverTheSocket() {
        PaneldServerHttpFixture().use { fixture ->
            val owner = RemoteControlRoutes(
                fixture.config, InteractiveController(canSu = false),
                SystemController(FakeSystemEnv(), FakeRootShell(), FakeDaemon(), isDeviceSecure = { false }),
                stepVolume = {}, stopping = { false }, cacheScreenshot = { null },
            )
            field(fixture.server, "remoteControlRoutes", owner)
            try {
                testApplication {
                    application { fixture.mount(this) }
                    val (back, dashboard) = socket {
                        request("action", JSONObject().put("a", "back")) to request("action.dashboard", JSONObject())
                    }
                    assertEquals(back.toString(), "applied", back.getString("outcome"))
                    assertEquals(dashboard.toString(), "applied", dashboard.getString("outcome"))
                }
            } finally {
                owner.closeAndJoin(1_000)
            }
        }
    }

    @Test fun anOAuthStartOverTheSocketBindsThePanelOrigin() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val (oauth, failed) = socket {
                    request("ha.oauth.start", JSONObject().put("ha_url", "http://ha.local:8123")) to
                        request("ha.oauth.start", JSONObject().put("ha_url", ""))
                }
                assertEquals(oauth.toString(), "applied", oauth.getString("outcome"))
                val authorization = Url(oauth.getJSONObject("result").getString("authorization_url"))
                assertEquals("http://localhost:8888/", authorization.parameters["client_id"])
                assertEquals("http://localhost:8888/api/v1/ha/oauth/callback", authorization.parameters["redirect_uri"])
                assertEquals("invalid-ha-url", failed.getJSONObject("result").getString("error"))
                assertEquals("the route's status travels with a failed answer", 400, failed.getInt("status"))
            }
        }
    }

    private suspend fun <T> ApplicationTestBuilder.socket(language: String? = null, block: suspend DefaultClientWebSocketSession.() -> T): T {
        var result: Result<T>? = null
        // A browser's upgrade request always names the Host it opened; the test engine's names none unless asked.
        createClient { install(WebSockets) }.webSocket("/ui", request = {
            header(HttpHeaders.Host, "localhost")
            language?.let { header(HttpHeaders.AcceptLanguage, it) }
        }) {
            result = runCatching { block() }
        }
        return result!!.getOrThrow()
    }

    private suspend fun DefaultClientWebSocketSession.request(op: String, args: JSONObject): JSONObject {
        val id = "r" + System.nanoTime()
        send(Frame.Text(JSONObject().put("kind", "manage").put("command_id", id).put("op", op).put("args", args).toString()))
        return withTimeout(10_000) {
            while (true) {
                val frame = JSONObject((incoming.receive() as Frame.Text).readText())
                if (frame.optString("command_id") == id && !frame.optBoolean("interim")) return@withTimeout frame
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    private fun field(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
