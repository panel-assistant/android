package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.PowerRepairCapability
import io.github.maxlyth.hapaneld.control.PowerRepairStepStatus
import io.github.maxlyth.hapaneld.control.PowerRiskLevel
import io.github.maxlyth.hapaneld.control.PowerSafetyAdvisoryPolicy
import io.github.maxlyth.hapaneld.control.PowerSafetyAssessment
import io.github.maxlyth.hapaneld.control.PowerSafetyObservation
import io.github.maxlyth.hapaneld.control.PowerSafetyRepairResult
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

class PowerSafetyRoutesTest {
    @Test fun `full mount preserves assessment acknowledgement repair and root admission`() {
        val assessment = PowerSafetyAssessment(
            PowerRiskLevel.CAUTION,
            PowerSafetyObservation(true, true, false, false, true, Int.MAX_VALUE, true, 1, 0, false, false, "brightness_zero"),
            listOf("stay_on_disabled"), "Power caution", "Review power settings",
        )
        var repairCalls = 0
        var repairStatus = "repaired"
        PaneldServerHttpFixture(
            powerSafety = { assessment },
            repairPowerSafety = {
                repairCalls++
                PowerSafetyRepairResult(
                    repairStatus, PowerRepairStepStatus.APPLIED, PowerRepairStepStatus.ALREADY,
                    PowerRepairStepStatus.UNAVAILABLE, PowerRepairStepStatus.UNAVAILABLE,
                    "app_only", assessment,
                )
            },
        ).use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val state = client.get("/api/v1/power-safety/state")
                assertEquals(HttpStatusCode.OK, state.status)
                assertEquals("caution\n", state.bodyAsText())
                val passive = client.get("/api/v1/power-safety") { header("Sec-Fetch-Site", "cross-site") }
                assertEquals(HttpStatusCode.OK, passive.status)
                assertEquals("caution", JSONObject(passive.bodyAsText()).getString("state"))

                val invalid = client.post("/api/v1/power-safety/acknowledge") {
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    setBody("fingerprint=invalid")
                }
                assertEquals(HttpStatusCode.BadRequest, invalid.status)
                assertEquals("invalid-fingerprint", JSONObject(invalid.bodyAsText()).getString("error"))
                val stale = client.post("/api/v1/power-safety/acknowledge") {
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    header(HttpHeaders.Accept, "application/json")
                    setBody("fingerprint=" + "0".repeat(64))
                }
                assertEquals(HttpStatusCode.Conflict, stale.status)
                assertEquals("stale-assessment", JSONObject(stale.bodyAsText()).getString("error"))
                val fingerprint = PowerSafetyAdvisoryPolicy.evaluate(assessment, PowerRepairCapability.APP_ONLY, null)
                    .acknowledgementFingerprint!!
                val accepted = client.post("/api/v1/power-safety/acknowledge") {
                    header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
                    header(HttpHeaders.Accept, "application/json")
                    setBody("fingerprint=$fingerprint")
                }
                assertEquals(HttpStatusCode.OK, accepted.status)
                assertTrue(JSONObject(accepted.bodyAsText()).getBoolean("acknowledged"))
                assertEquals(fingerprint, fixture.config.powerSafetyAcknowledgementFingerprint)

                val rejected = client.post("/api/v1/power-safety/repair") { header(HttpHeaders.Origin, "http://elsewhere.example") }
                assertEquals(HttpStatusCode.Forbidden, rejected.status)
                assertEquals(0, repairCalls)
                val repaired = client.post("/api/v1/power-safety/repair") { header(HttpHeaders.Accept, "application/json") }
                assertEquals(HttpStatusCode.OK, repaired.status)
                assertEquals("repaired", JSONObject(repaired.bodyAsText()).getString("status"))
                assertEquals(1, repairCalls)
                repairStatus = "failed"
                val failed = client.post("/api/v1/power-safety/repair")
                assertEquals(HttpStatusCode.ServiceUnavailable, failed.status)
                assertTrue(failed.bodyAsText().contains("url=configure#cfg-keep_awake"))
                assertEquals(2, repairCalls)
            }
        }
    }
}
