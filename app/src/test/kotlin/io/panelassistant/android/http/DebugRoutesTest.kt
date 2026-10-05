package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.sensors.SensorTrace
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugRoutesTest {
    @Test fun `full mount preserves trace formats and rejects devtools before privileged effects`() {
        val wasEnabled = SensorTrace.enabled
        SensorTrace.enabled = true
        SensorTrace.clear()
        try {
            SensorTrace.recordLux(12.5f, 10f, 42, 40)
            SensorTrace.recordProx(1f, true)
            PaneldServerHttpFixture().use { fixture ->
                testApplication {
                    application { fixture.mount(this) }
                    val csv = client.get("/api/v1/sensortrace")
                    assertEquals(HttpStatusCode.OK, csv.status)
                    assertEquals("text/csv; charset=UTF-8", csv.headers[HttpHeaders.ContentType])
                    assertTrue(csv.bodyAsText().startsWith("t_ms,kind,raw,smoothed,target,applied,near\n"))
                    assertTrue(csv.bodyAsText().contains(",L,12.5,10.0,42,40,\n"))
                    assertTrue(csv.bodyAsText().contains(",P,1.0,,,,1\n"))
                    val json = client.get("/api/v1/sensortrace?format=json")
                    assertEquals(HttpStatusCode.OK, json.status)
                    assertEquals("application/json", json.headers[HttpHeaders.ContentType])
                    val trace = JSONObject(json.bodyAsText())
                    assertEquals(2, trace.getInt("count"))
                    assertEquals(42, trace.getJSONArray("rows").getJSONObject(0).getInt("target"))
                    assertTrue(trace.getJSONArray("rows").getJSONObject(1).getBoolean("near"))
                    assertTrue(fixture.config.setSecurityMode(Config.SecurityMode.HARDENED))
                    val start = client.post("/api/v1/inspect/start")
                    assertEquals(HttpStatusCode.Conflict, start.status)
                    assertEquals(
                        """{"ok":false,"error":"devtools-incompatible-with-hardened-mode","message":"Switch to Relaxed mode before exposing WebView developer tools to the LAN."}""",
                        start.bodyAsText(),
                    )
                    for (path in listOf("/api/v1/inspect/start", "/api/v1/inspect/stop")) {
                        val foreign = client.post(path) { header(HttpHeaders.Origin, "http://elsewhere.example") }
                        assertEquals(HttpStatusCode.Forbidden, foreign.status)
                    }
                }
            }
        } finally {
            SensorTrace.clear()
            SensorTrace.enabled = wasEnabled
        }
    }
}
