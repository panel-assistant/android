package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigProbeRoutesHttpTest {
    @Test fun `network probe validation and body limits run behind production root guards`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.server.mount(this) }

                val broker = client.get("/api/v1/config/probe-broker?url=http://not-an-mqtt-broker.example:1883")
                assertEquals(HttpStatusCode.OK, broker.status)
                assertEquals("invalid-url", JSONObject(broker.bodyAsText()).getString("error"))
                assertEquals("nosniff", broker.headers["X-Content-Type-Options"])

                val badPort = client.submitForm(
                    "/api/v1/config/probe-log-sink",
                    Parameters.build { append("port", "70000") },
                )
                assertEquals(HttpStatusCode.OK, badPort.status)
                assertEquals("invalid-port", JSONObject(badPort.bodyAsText()).getString("error"))

                val blankHost = client.submitForm(
                    "/api/v1/config/probe-log-sink",
                    Parameters.build { append("host", ""); append("port", "514") },
                )
                assertEquals(HttpStatusCode.OK, blankHost.status)
                assertEquals("no-host", JSONObject(blankHost.bodyAsText()).getString("error"))

                val tooLarge = client.submitForm(
                    "/api/v1/config/probe-log-sink",
                    Parameters.build { append("host", "x".repeat(16 * 1024 + 1)) },
                )
                assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
                assertEquals("request too large\n", tooLarge.bodyAsText())

                val crossOrigin = client.submitForm(
                    "/api/v1/config/probe-log-sink", Parameters.Empty,
                ) { header(HttpHeaders.Origin, "http://elsewhere.example") }
                assertEquals(HttpStatusCode.Forbidden, crossOrigin.status)
                assertEquals("cross-origin refused\n", crossOrigin.bodyAsText())
            }
        }
    }
}
