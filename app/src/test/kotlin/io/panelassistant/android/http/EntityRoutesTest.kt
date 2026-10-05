package io.panelassistant.android.http

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class EntityRoutesTest {
    @Test fun `full mount rejects oversized malformed and incomplete entity mutations`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                for (route in listOf("activate", "policy", "override", "overrides", "issues", "reset")) {
                    val path = "/api/v1/dashboard/entities/$route"
                    val invalid = client.post(path) { setBody("not-json") }
                    assertEquals(route, HttpStatusCode.BadRequest, invalid.status)
                    assertEquals("invalid JSON\n", invalid.bodyAsText())
                    val oversized = client.post(path) { setBody("x".repeat(256 * 1024 + 1)) }
                    assertEquals(route, HttpStatusCode.PayloadTooLarge, oversized.status)
                    assertEquals("request too large\n", oversized.bodyAsText())
                    val foreign = client.post(path) { header(HttpHeaders.Origin, "http://elsewhere.example") }
                    assertEquals(route, HttpStatusCode.Forbidden, foreign.status)
                    assertEquals("cross-origin refused\n", foreign.bodyAsText())
                }
                for ((route, message) in listOf(
                    "policy" to "auto_static and auto_runtime are required\n",
                    "issues" to "fingerprint and ignored are required\n",
                )) {
                    val response = client.post("/api/v1/dashboard/entities/$route") { setBody("{}") }
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertEquals(message, response.bodyAsText())
                }
                val empty = client.post("/api/v1/dashboard/entity-filter") {
                    setBody("""{"mode":"manual","entity_ids":[]}""")
                }
                assertEquals(HttpStatusCode.BadRequest, empty.status)
                assertEquals("entity_ids must contain at least one valid entity\n", empty.bodyAsText())
                val enable = client.post("/api/v1/dashboard/entity-filter") { setBody("""{"enabled":true}""") }
                assertEquals(HttpStatusCode.BadRequest, enable.status)
                assertEquals("entity_ids required when enabled\n", enable.bodyAsText())
                assertEquals(false, fixture.config.dashboardEntityFilterEnabled)
            }
        }
    }
}
