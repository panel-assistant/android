package io.github.maxlyth.hapaneld.http

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
    @Test fun `full production mount preserves legacy GET and POST redirects`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val direct = createClient { followRedirects = false }
                val target = "/api/v1/proximity/threshold?value=12&note=a%20b"
                val responses = listOf(
                    direct.get("/proximity/threshold?value=12&note=a%20b"),
                    direct.post("/proximity/threshold?value=12&note=a%20b") {
                        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                        setBody("threshold=12")
                    },
                )
                for (response in responses) {
                    assertEquals(HttpStatusCode.PermanentRedirect, response.status)
                    assertEquals(target, response.headers[HttpHeaders.Location])
                    assertEquals("moved-permanently: $target\n", response.bodyAsText())
                    assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                    assertEquals("DENY", response.headers["X-Frame-Options"])
                }
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
