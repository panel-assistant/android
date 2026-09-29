package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagementHttpTest {
    @Test fun `diagnostics retain the complete last report after shutdown closes refresh admission`() {
        PaneldServerHttpFixture().use { fixture ->
            val report = "Panel diagnostics\nroot: unavailable\ndensity: unknown\n"
            fixture.useWarmDiagnostics(report, stopping = true)
            testApplication {
                application { fixture.mount(this) }
                repeat(2) {
                    val response = client.get("/api/v1/diag")
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals("text/plain", response.headers["Content-Type"]?.substringBefore(';'))
                    assertEquals(report, response.bodyAsText())
                }
            }
        }
    }

    @Test fun `status refresh and database proof require active read admission`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                for (query in listOf("refresh=1", "database_observation_nonce=proof")) {
                    val response = client.get("/api/v1/status?$query") {
                        header("Sec-Fetch-Site", "cross-site")
                    }
                    assertEquals(HttpStatusCode.Forbidden, response.status)
                    assertEquals("refused: this panel does not serve active reads to another site.\n", response.bodyAsText())
                }
            }
        }
    }
}
