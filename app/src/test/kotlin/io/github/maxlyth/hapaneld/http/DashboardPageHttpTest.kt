package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardPageHttpTest {
    @Test fun `cold dashboard renders hydration shell without management probes`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.enableColdDashboard()
            testApplication {
                application { fixture.server.mount(this) }
                val response = client.get("/?lang=en") {
                    header(HttpHeaders.Cookie, "wiz_escape=1")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val html = response.bodyAsText()
                assertTrue(html.contains("data-hydrate=\"1\""))
                assertTrue(html.contains("data-capture-ok=\"0\""))
                assertTrue(html.contains("id=\"infotbl\""))
                assertTrue(html.contains("id=\"contexttbl\""))
                assertTrue(html.contains("Contract &lt;panel&gt;"))
                val refused = client.get("/") {
                    header(HttpHeaders.Cookie, "wiz_escape=1")
                    header(HttpHeaders.Host, "elsewhere.example")
                }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
            }
        }
    }
}
