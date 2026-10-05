package io.panelassistant.android.http

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.panelassistant.android.CoreInstrumentation
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp as ClientOkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.server.cio.CIO as ServerCio
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Proves the production CIO server and OkHttp client can exchange bytes at the supported API floor. */
@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class KtorCioRuntimeTest {
    @Test fun cioServerAndOkHttpClientRunOnAndroid() = runBlocking {
        val server = embeddedServer(ServerCio, host = "::", port = 0) {
            routing {
                get("/health") { call.respondText("ok") }
            }
        }
        val client = HttpClient(ClientOkHttp) {
            install(WebSockets)
        }
        try {
            server.start(wait = false)
            val port = withTimeout(10_000L) { server.engine.resolvedConnectors().single().port }
            val body = withTimeout(10_000L) {
                client.get("http://[::1]:$port/health").body<String>()
            }
            assertEquals("ok", body)
        } finally {
            client.close()
            server.stop(gracePeriodMillis = 1_000L, timeoutMillis = 5_000L)
        }
    }
}
