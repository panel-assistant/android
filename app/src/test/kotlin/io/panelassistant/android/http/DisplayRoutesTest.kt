package io.panelassistant.android.http

import io.panelassistant.android.control.DensityController
import io.panelassistant.android.control.DisplaySizingObservation
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.control.FakeRootShell
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
        val root = FakeRootShell()
        // Sizing reads come from the helper (never su); the writes below still take the profile's su route.
        val helper = FakeDaemon(replies = mapOf("DENSITY" to "PHYS=320 OVER=240", "FONTSCALE" to "SCALE=1.0"))
        val density = DensityController(true, root, helper)
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

    @Test fun `a panel with neither root nor the helper applies the profile sizing through the app's own grants`() {
        val app = io.panelassistant.android.control.FakeAppSizing(base = 240)
        val density = DensityController(
            false, FakeRootShell(available = false, runResult = false), FakeDaemon(available = false), app,
        )
        PaneldServerHttpFixture(density = density).use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                suspend fun post(body: String) = client.post("/api/v1/display/density") {
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    header(HttpHeaders.Accept, "application/json")
                    setBody(body)
                }
                val applied = post("density=216&font=1.1")
                assertEquals(HttpStatusCode.OK, applied.status)
                assertEquals("applied", JSONObject(applied.bodyAsText()).getString("status"))
                assertEquals(216, app.override)
                assertEquals(1.1f, app.font)
                assertEquals(DisplaySizingObservation(216, 240, 1.1f), fixture.sizingCache.peek())
                val reset = post("action=reset")
                assertEquals(HttpStatusCode.OK, reset.status)
                assertEquals(null, app.override)
                assertEquals(1.0f, app.font)
            }
        }
    }

    @Test fun `without the app's grants a rootless panel reports the sizing as not applied`() {
        val app = io.panelassistant.android.control.FakeAppSizing(granted = false)
        val density = DensityController(
            false, FakeRootShell(available = false, runResult = false), FakeDaemon(available = false), app,
        )
        PaneldServerHttpFixture(density = density).use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val refused = client.post("/api/v1/display/density") {
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    header(HttpHeaders.Accept, "application/json")
                    setBody("density=216")
                }
                assertEquals(HttpStatusCode.InternalServerError, refused.status)
                assertEquals("apply-failed", JSONObject(refused.bodyAsText()).getString("status"))
                assertEquals(null, app.override)
            }
        }
    }
}
