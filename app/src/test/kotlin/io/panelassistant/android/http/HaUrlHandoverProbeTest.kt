package io.panelassistant.android.http

import com.sun.net.httpserver.HttpServer
import io.ktor.http.Parameters
import io.panelassistant.android.Config
import io.panelassistant.android.fakePreferences
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handover check run for real, through the config POST's own onboarding step, against servers on
 * loopback that play Home Assistant and the things that are not Home Assistant.
 *
 * Home Assistant turns every 401 into a "Login attempt failed" notification and a ban strike against
 * the sender, so the check must tell Home Assistant apart without ever being answered 401.
 */
class HaUrlHandoverProbeTest {

    private data class Seen(val method: String, val path: String, val status: Int)

    private class FakeServer(private val answer: (String) -> Triple<Int, String, String>) {
        val seen = CopyOnWriteArrayList<Seen>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val (status, type, body) = answer(path)
                seen += Seen(exchange.requestMethod, path, status)
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", type)
                exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        val url: String get() = "http://127.0.0.1:${server.address.port}"
        fun close() = server.stop(0)
    }

    private val servers = mutableListOf<FakeServer>()

    @After fun close() = servers.forEach { it.close() }

    private fun server(answer: (String) -> Triple<Int, String, String>) = FakeServer(answer).also { servers += it }

    /** Answers the way a current Home Assistant does to a request carrying no credentials. */
    private fun homeAssistant() = server { path ->
        when (path) {
            "/api/" -> Triple(401, "text/plain; charset=utf-8", "401: Unauthorized")
            "/auth/providers" -> Triple(
                200,
                "application/json",
                """{"providers":[{"name":"Home Assistant Local","id":null,"type":"homeassistant"}],"preselect_remember_me":true}""",
            )
            else -> Triple(404, "text/plain; charset=utf-8", "404: Not Found")
        }
    }

    private fun handover(url: String): HandoverConfigPost = runBlocking {
        DirectConfigOnboarding(Config(fakePreferences().instance)).augmentPostWithVerifiedHandover(
            Parameters.build {
                append("ha_setup_handover", "true")
                append("ha_url_handover", url)
            },
        )
    }

    @Test fun `Home Assistant is verified without ever being answered 401`() {
        val ha = homeAssistant()
        val result = handover(ha.url)
        assertTrue("the handover must reach Home Assistant: ${ha.seen}", ha.seen.isNotEmpty())
        assertEquals(
            "every request answered 401 raises Login attempt failed in Home Assistant",
            emptyList<Seen>(),
            ha.seen.filter { it.status == 401 },
        )
        assertEquals(HaUrlHandover.Outcome.VERIFIED, result.outcome)
        assertEquals(ha.url, result.parameters["ha_url"])
        assertEquals(listOf(Seen("GET", "/auth/providers", 200)), ha.seen.toList())
    }

    @Test fun `a stranger answering 200 to everything is refused`() {
        // A captive portal or an unrelated web server on a reused address.
        val stranger = server { Triple(200, "text/html", "<html><body>Welcome</body></html>") }
        val result = handover(stranger.url)
        assertEquals(HaUrlHandover.Outcome.NOT_HOME_ASSISTANT, result.outcome)
        assertNull(result.parameters["ha_url"])
        assertEquals("not_home_assistant", result.parameters["ha_url_handover_reason"])
    }

    @Test fun `a JSON server that is not Home Assistant is refused`() {
        val api = server { Triple(200, "application/json", """{"status":"ok"}""") }
        assertEquals(HaUrlHandover.Outcome.NOT_HOME_ASSISTANT, handover(api.url).outcome)
    }

    @Test fun `a proxy that demands its own sign-in is refused rather than taken for Home Assistant`() {
        val proxy = server { Triple(401, "text/html", "<html>Sign in</html>") }
        assertEquals(HaUrlHandover.Outcome.NOT_HOME_ASSISTANT, handover(proxy.url).outcome)
    }

    @Test fun `Home Assistant that has not finished onboarding is not verified`() {
        val fresh = server { Triple(400, "application/json", """{"message":"Onboarding not finished","code":"onboarding_required"}""") }
        assertEquals(HaUrlHandover.Outcome.NOT_HOME_ASSISTANT, handover(fresh.url).outcome)
    }
}
