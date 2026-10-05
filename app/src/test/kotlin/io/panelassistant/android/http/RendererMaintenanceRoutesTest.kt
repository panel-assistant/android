package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.control.CompanionDb
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererMaintenanceRoutesTest {
    @Test fun `full mount preserves approval single flight repair callbacks and invalidation`() {
        var repairs = 0
        var repairAdmitted = true
        val broker = LocalApprovalBroker.instance
        val approvals = mutableListOf<String>()
        try {
            PaneldServerHttpFixture(repairCompanionUrl = { repairs++; repairAdmitted }).use { fixture ->
                assertNotNull(fixture.clearStorageGate.claim())
                assertTrue(fixture.config.setSecurityMode(Config.SecurityMode.HARDENED))
                testApplication {
                    application {
                        intercept(ApplicationCallPipeline.Setup) {
                            context.mutableOriginConnectionPoint.remoteAddress = "192.168.1.25"
                        }
                        fixture.mount(this)
                    }
                    for ((path, operation) in listOf(
                        "/api/v1/dashboard/clear-storage" to SensitiveOperation.DASHBOARD_STORAGE_CLEAR,
                        "/api/v1/companion/repair-url" to SensitiveOperation.COMPANION_REPAIR,
                    )) {
                        val foreign = client.post(path) { header(HttpHeaders.Origin, "http://elsewhere.example") }
                        assertEquals(HttpStatusCode.Forbidden, foreign.status)
                        val challenge = client.post(path)
                        assertEquals(HttpStatusCode.Accepted, challenge.status)
                        val body = JSONObject(challenge.bodyAsText())
                        assertEquals("approval-required", body.getString("error"))
                        assertEquals(
                            "Approve this request physically on the panel, then retry it; it cannot be approved remotely.",
                            body.getString("message"),
                        )
                        val id = body.getString("approval_id")
                        approvals += id
                        assertEquals(operation, broker.pending().single { it.id == id }.operation)
                        assertEquals(0, repairs)
                        assertTrue(fixture.companionCache.ageMs() < Long.MAX_VALUE)
                        assertTrue(broker.approve(id))
                        val approved = client.post(path)
                        if (operation == SensitiveOperation.DASHBOARD_STORAGE_CLEAR) {
                            assertEquals(HttpStatusCode.Conflict, approved.status)
                            assertEquals("""{"status":"busy"}""", approved.bodyAsText())
                        } else {
                            assertEquals(HttpStatusCode.OK, approved.status)
                            assertEquals("""{"status":"started"}""", approved.bodyAsText())
                            assertEquals(1, repairs)
                            assertEquals(Long.MAX_VALUE, fixture.companionCache.ageMs())
                        }
                        val replay = client.post(path)
                        assertEquals(HttpStatusCode.Accepted, replay.status)
                        val replayBody = JSONObject(replay.bodyAsText())
                        assertEquals("approval-required", replayBody.getString("error"))
                        approvals += replayBody.getString("approval_id")
                    }
                    assertTrue(fixture.config.setSecurityMode(Config.SecurityMode.RELAXED))
                    fixture.companionCache.set(CompanionDb.ServerObservation.EMPTY)
                    repairAdmitted = false
                    val busy = client.post("/api/v1/companion/repair-url")
                    assertEquals(HttpStatusCode.OK, busy.status)
                    assertEquals("""{"status":"busy"}""", busy.bodyAsText())
                    assertEquals(2, repairs)
                    assertTrue(fixture.companionCache.ageMs() < Long.MAX_VALUE)
                }
            }
            PaneldServerHttpFixture(stopping = true).use { fixture ->
                fixture.clearStorageGate.close()
                testApplication {
                    application { fixture.mount(this) }
                    val stopped = client.post("/api/v1/dashboard/clear-storage")
                    assertEquals(HttpStatusCode.ServiceUnavailable, stopped.status)
                    assertEquals("""{"status":"busy"}""", stopped.bodyAsText())
                }
            }
        } finally {
            approvals.forEach(broker::deny)
        }
    }
}
