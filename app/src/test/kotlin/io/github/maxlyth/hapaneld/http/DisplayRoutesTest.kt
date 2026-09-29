package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.DensityController
import io.github.maxlyth.hapaneld.control.DisplaySizingObservation
import io.github.maxlyth.hapaneld.control.FakeDaemon
import io.github.maxlyth.hapaneld.control.FakeRootShell
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayRoutesTest {
    @Test fun `full mount preserves display admission results and known sizing cache`() {
        val root = FakeRootShell(outputs = mapOf("wm density" to "Physical density: 320\nOverride density: 240", "font_scale" to "1.0"))
        val density = DensityController(true, root, FakeDaemon())
        PaneldServerHttpFixture(density = density).use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val unavailable = client.get("/api/v1/display")
                assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
                assertEquals("""{"error":"display-unavailable"}""", unavailable.bodyAsText())
                val foreign = client.post("/api/v1/display/density") { header(HttpHeaders.Origin, "http://elsewhere.example") }
                assertEquals(HttpStatusCode.Forbidden, foreign.status)
                assertTrue(root.ran.isEmpty())
                val applied = client.post("/api/v1/display/density") {
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    header(HttpHeaders.Accept, "application/json")
                    setBody("density=280&font=1.2")
                }
                assertEquals(HttpStatusCode.OK, applied.status)
                assertEquals("applied", JSONObject(applied.bodyAsText()).getString("status"))
                assertEquals(listOf("wm density 280", "settings put system font_scale 1.2"), root.ran)
                assertEquals(DisplaySizingObservation(280, 320, 1.2f), fixture.sizingCache.peek())
                val failed = client.post("/api/v1/display/density") {
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    header(HttpHeaders.Accept, "application/json")
                    setBody("density=1")
                }
                assertEquals(HttpStatusCode.InternalServerError, failed.status)
                assertEquals("apply-failed", JSONObject(failed.bodyAsText()).getString("status"))
                assertEquals(DisplaySizingObservation(280, 320, 1.2f), fixture.sizingCache.peek())
                val reset = client.post("/api/v1/display/density") {
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    setBody("action=reset")
                }
                assertEquals(HttpStatusCode.OK, reset.status)
                assertTrue(reset.bodyAsText().contains("url=install#cfg-display"))
                assertEquals(DisplaySizingObservation(320, 320, 1.0f), fixture.sizingCache.peek())
            }
        }
    }
}
