package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.PanelStatus
import io.github.maxlyth.hapaneld.RendererAdmissionPresentation
import io.github.maxlyth.hapaneld.RendererMode
import io.github.maxlyth.hapaneld.camera.CameraPresentation
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.control.*
import io.github.maxlyth.hapaneld.shizuku.ShizukuBridge
import io.github.maxlyth.hapaneld.shizuku.ShizukuState
import io.github.maxlyth.hapaneld.storage.StorageHealthSnapshot
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManagementHomeProofTest {
    @Test fun `home proof is opt in and preserves refresh admission and observation order`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.useManagementStatus {}
            val events = mutableListOf<String>()
            val snapshot = ManagementSnapshot(
                emptyMap(), emptyMap(), Capabilities(), emptyList(),
                PrivilegedRouteObservation(false, false, ShizukuBridge.Snapshot(ShizukuState.DISABLED, false)),
                null, null, 1f, false,
            )
            val power = PowerSafetyAdvisory(
                PowerSafetyAssessment(PowerRiskLevel.SAFE,
                    PowerSafetyObservation(true, true, false, false, true, 60_000, true, 1, 1, false, true, "none"),
                    emptyList(), "safe", "none"),
                PowerRepairCapability.APP_ONLY, PowerSafetyAdvisoryAction.NONE, null, false,
            )
            testApplication {
                application {
                    paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                        route("/api/v1") {
                            managementRoutes(
                                diagnostics = { error("Not requested") },
                                onUpdateOwner = {},
                                admitActiveRead = { admitActiveRead(it) },
                                refreshUpdates = { events += "updates" },
                                refreshStorage = { events += "storage"; StorageHealthSnapshot.UNCHECKED },
                                cachedStorage = { events += "cached"; StorageHealthSnapshot.UNCHECKED },
                                status = { storage, nonce, requested ->
                                    managementStatusJson(
                                        fixture.config, snapshot, CompanionDb.ServerObservation.EMPTY, power,
                                        null, HealthAudit.storage(storage),
                                        StatusHealth(emptyList(), emptyList(),
                                            { events += "recovery"; PanelStatus.DashboardRecoveryState.NONE },
                                            { events += "mdns"; null to null },
                                            { events += "rollback"; null }),
                                        renderer = {
                                            events += "renderer"
                                            RendererAdmissionPresentation.of(RendererMode.NONE, "", "auto", null, 0, 0, null, 0)
                                        },
                                        camera = { events += "camera"; CameraPresentation.absent() },
                                        databaseObservationNonce = nonce,
                                        homeProof = if (requested) ({
                                            events += "home"
                                            homeUiProofJson("ready", "dashboard_foreground", "builtin_lifecycle")
                                        }) else null,
                                    )
                                },
                            )
                        }
                    }
                }
                val passive = listOf("cached", "recovery", "mdns", "rollback", "renderer", "camera")
                for (query in listOf("", "?home_proof=0", "?home_proof=true", "?home_proof=01", "?home_proof=")) {
                    events.clear()
                    val response = client.get("/api/v1/status$query")
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertFalse(JSONObject(response.bodyAsText()).has("home_ui"))
                    assertEquals(passive, events)
                }
                events.clear()
                val requested = client.get("/api/v1/status?home_proof=1") { header("Sec-Fetch-Site", "cross-site") }
                assertEquals(HttpStatusCode.OK, requested.status)
                val requestedBody = requested.bodyAsText()
                assertTrue(requestedBody.contains("\"home_ui\":{\"state\":\"ready\",\"reason\":\"dashboard_foreground\",\"evidence\":\"builtin_lifecycle\"},"))
                assertEquals(3, JSONObject(requestedBody).getJSONObject("home_ui").length())
                assertEquals(listOf("cached", "recovery", "mdns", "rollback", "home", "renderer", "camera"), events)
                val nonce = "0123456789abcdef0123456789abcdef"
                for (query in listOf("refresh=1", "database_observation_nonce=$nonce")) {
                    events.clear()
                    val rejected = client.get("/api/v1/status?$query&home_proof=1") { header("Sec-Fetch-Site", "cross-site") }
                    assertEquals(HttpStatusCode.Forbidden, rejected.status)
                    assertEquals("refused: this panel does not serve active reads to another site.\n", rejected.bodyAsText())
                    assertTrue(events.isEmpty())
                    events.clear()
                    val accepted = client.get("/api/v1/status?$query&home_proof=1")
                    assertEquals(HttpStatusCode.OK, accepted.status)
                    val body = accepted.bodyAsText()
                    assertTrue(JSONObject(body).has("home_ui"))
                    if (query.startsWith("database")) {
                        assertEquals(nonce, JSONObject(body).getString("database_observation_nonce"))
                        assertTrue(body.indexOf("database_observation_nonce") < body.indexOf("home_ui"))
                    }
                    assertEquals((if (query.startsWith("refresh")) listOf("updates") else emptyList()) +
                        listOf("storage", "recovery", "mdns", "rollback", "home", "renderer", "camera"), events)
                }
            }
        }
    }

    @Test fun `home proof fields use the existing JSON string escaping`() {
        val json = JSONObject(homeUiProofJson("unknown", "reason\"\n", "path\\value"))
        assertEquals("unknown", json.getString("state"))
        assertEquals("reason\"\n", json.getString("reason"))
        assertEquals("path\\value", json.getString("evidence"))
    }
}
