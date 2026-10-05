package io.panelassistant.android.http

import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.control.ZigbeeHealthState
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RadioRoutesTest {
    @Test fun `radio reads and join preserve absent disabled busy started and origin refusal`() = testApplication {
        var snapshot: ZigbeeHealthSnapshot? = null
        var configured = false
        var enabled = false
        var joinAccepted = false
        var joinCalls = 0
        application {
            paneldRoot(
                allowedHosts = { emptySet() },
                setupNeedsUser = { false },
                setupRedirectLocation = { "/setup" },
            ) {
                route("/api/v1") {
                    radioRoutes(
                        status = { snapshot },
                        configured = { configured },
                        enabled = { enabled },
                        join = { joinCalls++; joinAccepted },
                    )
                }
            }
        }

        val absent = client.get("/api/v1/radio")
        assertEquals(HttpStatusCode.OK, absent.status)
        assertEquals("""{"present":false,"status":"none"}""", absent.bodyAsText())
        val unavailable = client.post("/api/v1/radio/join")
        assertEquals(HttpStatusCode.NotFound, unavailable.status)
        assertEquals("""{"status":"unavailable"}""", unavailable.bodyAsText())
        assertEquals(0, joinCalls)

        snapshot = ZigbeeHealthSnapshot(state = ZigbeeHealthState.UNKNOWN, firmware = "7.4.4")
        val present = JSONObject(client.get("/api/v1/radio").bodyAsText())
        assertEquals(true, present.getBoolean("present"))
        assertEquals(false, present.getBoolean("router_configured"))
        assertEquals(false, present.getBoolean("router_enabled"))
        assertEquals("unknown", present.getString("state"))
        assertEquals("7.4.4", present.getJSONObject("attributes").getString("firmware"))

        assertEquals(HttpStatusCode.Conflict, client.post("/api/v1/radio/join").status)
        configured = true
        assertEquals(HttpStatusCode.Conflict, client.post("/api/v1/radio/join").status)
        enabled = true
        val busy = client.post("/api/v1/radio/join")
        assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
        assertEquals("""{"status":"busy"}""", busy.bodyAsText())
        assertEquals(1, joinCalls)

        val crossOrigin = client.post("/api/v1/radio/join") {
            header(HttpHeaders.Origin, "http://elsewhere.example")
        }
        assertEquals(HttpStatusCode.Forbidden, crossOrigin.status)
        assertEquals("cross-origin refused\n", crossOrigin.bodyAsText())
        assertEquals(1, joinCalls)

        joinAccepted = true
        val started = client.post("/api/v1/radio/join")
        assertEquals(HttpStatusCode.OK, started.status)
        assertEquals("""{"status":"started"}""", started.bodyAsText())
        assertEquals(2, joinCalls)
    }
}
