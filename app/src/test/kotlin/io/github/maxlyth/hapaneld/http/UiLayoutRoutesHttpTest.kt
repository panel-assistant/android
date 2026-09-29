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

class UiLayoutRoutesHttpTest {
    @Test fun `layout round trips opaque data and refusals leave the stored layout intact`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val layout = "[{\"id\":\"sensor\\\"<&\",\"x\":2}]"
                val saved = client.submitForm("/api/v1/ui/layout", Parameters.build { append("layout", layout) })
                assertEquals(HttpStatusCode.OK, saved.status)
                assertEquals("{\"ok\":true}", saved.bodyAsText())
                assertEquals(layout, fixture.config.uiDashboardLayout)
                val read = client.get("/api/v1/ui/layout")
                assertEquals(HttpStatusCode.OK, read.status)
                assertEquals("application/json", read.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                assertEquals(layout, JSONObject(read.bodyAsText()).getString("layout"))
                assertEquals("nosniff", read.headers["X-Content-Type-Options"])
                val refused = client.submitForm("/api/v1/ui/layout", Parameters.build { append("layout", "replaced") }) {
                    header(HttpHeaders.Origin, "http://elsewhere.example")
                }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertEquals(layout, fixture.config.uiDashboardLayout)
                val oversized = client.submitForm("/api/v1/ui/layout", Parameters.build {
                    append("layout", "x".repeat(256 * 1024 + 1))
                })
                assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
                assertEquals("request too large\n", oversized.bodyAsText())
                assertEquals(layout, fixture.config.uiDashboardLayout)
                val cleared = client.submitForm("/api/v1/ui/layout", Parameters.Empty)
                assertEquals(HttpStatusCode.OK, cleared.status)
                assertEquals("", fixture.config.uiDashboardLayout)
            }
        }
    }
}
