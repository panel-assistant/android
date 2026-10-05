package io.panelassistant.android.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class ProximityRoutesTest {
    @Test fun `proximity calibration keeps UI origin form and source guards and retired responses`() = testApplication {
        var present = false
        var accepted = false
        var received = ""
        application {
            paneldRoot(
                allowedHosts = { emptySet() },
                setupNeedsUser = { false },
                setupRedirectLocation = { "/setup" },
            ) {
                route("/api/v1") {
                    proximityRoutes(
                        hasProximity = { present },
                        proximityJson = { "{\"present\":$present}" },
                        calibrate = { action, id -> received = "$action/$id"; accepted },
                    )
                }
            }
        }

        assertEquals("{\"present\":false}", client.get("/api/v1/proximity").bodyAsText())
        val unmarked = client.post("/api/v1/proximity/calibration")
        assertEquals(HttpStatusCode.Forbidden, unmarked.status)
        assertEquals("{\"error\":\"Start proximity setup from this panel's HTML UI.\"}", unmarked.bodyAsText())

        suspend fun submit(action: String) = client.submitForm(
            "/api/v1/proximity/calibration",
            Parameters.build { append("action", action); append("sessionId", "session-1") },
        ) {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
            header("X-Proximity-UI", "1")
        }

        val invalid = submit("unknown")
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals("{\"error\":\"Unsupported calibration action.\"}", invalid.bodyAsText())
        val absent = submit("start")
        assertEquals(HttpStatusCode.Conflict, absent.status)
        assertEquals("{\"error\":\"proximity_source_required\"}", absent.bodyAsText())
        assertEquals("", received)

        present = true
        val rejected = submit("heartbeat")
        assertEquals(HttpStatusCode.Conflict, rejected.status)
        assertEquals("heartbeat/session-1", received)
        assertEquals("no-store", rejected.headers[HttpHeaders.CacheControl])
        assertEquals("{\"present\":true}", rejected.bodyAsText())
        accepted = true
        val started = submit("start")
        assertEquals(HttpStatusCode.Accepted, started.status)
        assertEquals("start/session-1", received)

        // Exactly the headers the Panel Assistant sidebar proxy forwards: no Origin, Referer or marker.
        received = ""
        val embedded = client.submitForm(
            "/api/v1/proximity/calibration",
            Parameters.build { append("action", "start"); append("sessionId", "session-2") },
        ) {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Accept, "application/json")
            header("Sec-Fetch-Site", "same-origin")
            header(EmbedMode.HEADER, "v=1;lang=en")
        }
        assertEquals(HttpStatusCode.Accepted, embedded.status)
        assertEquals("start/session-2", received)

        for (path in listOf("teach", "test", "relearn", "capture", "threshold", "sensitivity", "reset")) {
            val retired = client.post("/api/v1/proximity/$path")
            assertEquals("$path status", HttpStatusCode.Gone, retired.status)
            val expected = when (path) {
                "teach", "test" -> "Use on-panel proximity setup from the HTML UI.\n"
                "relearn" -> "Use Reset to profile from the HTML UI.\n"
                else -> "{\"error\":\"automatic proximity learning replaced this operation\"}"
            }
            assertEquals("$path body", expected, retired.bodyAsText())
        }
    }
}
