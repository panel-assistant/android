package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class LoggingRoutesTest {
    @Test fun `full mount preserves logging refusals and passive status`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val cases = listOf(
                    "app" to (HttpStatusCode.NotFound to "log viewer unavailable\n"),
                    "webview" to (HttpStatusCode.ServiceUnavailable to "webview console needs log shipping configured\n"),
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
                val passive = client.get("/api/v1/logship/status") { header("Sec-Fetch-Site", "cross-site") }
                assertEquals(HttpStatusCode.OK, passive.status)
                assertEquals("{\"enabled\":false,\"configured\":false,\"text\":\"disabled\"}", passive.bodyAsText())
            }
        }
    }
}
