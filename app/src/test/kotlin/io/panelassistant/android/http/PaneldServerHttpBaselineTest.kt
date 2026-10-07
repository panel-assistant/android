package io.panelassistant.android.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class PaneldServerHttpBaselineTest {
    @Test fun `full production mount keeps the diag redirect and retires the other flat paths`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val direct = createClient { followRedirects = false }
                val diag = direct.get("/diag?redact=1")
                assertEquals(HttpStatusCode.PermanentRedirect, diag.status)
                assertEquals("/api/v1/diag?redact=1", diag.headers[HttpHeaders.Location])
                assertEquals("moved-permanently: /api/v1/diag?redact=1\n", diag.bodyAsText())
                assertEquals("nosniff", diag.headers["X-Content-Type-Options"])
                assertEquals("DENY", diag.headers["X-Frame-Options"])

                val retired = listOf(
                    direct.get("/proximity/threshold?value=12"),
                    direct.post("/config") {
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        setBody("friendly_name=x")
                    },
                    direct.get("/screenshot.png"),
                    direct.get("/fleet"),
                )
                for (response in retired) assertEquals(HttpStatusCode.NotFound, response.status)
            }
        }
    }

    @Test fun `full production mount enforces Host Origin and active read admission`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val host = client.get("/api/v1/radio") { header(HttpHeaders.Host, "elsewhere.example") }
                assertEquals(HttpStatusCode.Forbidden, host.status)
                assertEquals("host not allowed\n", host.bodyAsText())
                val origin = client.post("/api/v1/radio/join") {
                    header(HttpHeaders.Origin, "http://elsewhere.example")
                }
                assertEquals(HttpStatusCode.Forbidden, origin.status)
                assertEquals("cross-origin refused\n", origin.bodyAsText())
                val active = client.get("/api/v1/perf") { header("Sec-Fetch-Site", "cross-site") }
                assertEquals(HttpStatusCode.Forbidden, active.status)
                assertEquals("refused: this panel does not serve active reads to another site.\n", active.bodyAsText())
            }
        }
    }

    @Test fun `full production mount validates probes and reports absent radio`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val broker = client.get("/api/v1/config/probe-broker?url=")
                assertEquals(HttpStatusCode.OK, broker.status)
                assertEquals("{\"ok\":false,\"error\":\"invalid-url\"}", broker.bodyAsText())
                val sink = client.post("/api/v1/config/probe-log-sink") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody("port=invalid")
                }
                assertEquals(HttpStatusCode.OK, sink.status)
                assertEquals("{\"ok\":false,\"error\":\"invalid-port\"}", sink.bodyAsText())
                val radio = client.get("/api/v1/radio")
                assertEquals(HttpStatusCode.OK, radio.status)
                assertEquals("{\"present\":false,\"status\":\"none\"}", radio.bodyAsText())
                assertEquals("application/json", radio.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                val join = client.post("/api/v1/radio/join")
                assertEquals(HttpStatusCode.NotFound, join.status)
                assertEquals("{\"status\":\"unavailable\"}", join.bodyAsText())
            }
        }
    }
}
