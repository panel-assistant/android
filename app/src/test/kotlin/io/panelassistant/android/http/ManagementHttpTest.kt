package io.panelassistant.android.http

import io.panelassistant.android.platform.PanelPermissionRepair
import io.panelassistant.android.platform.PanelPermissionRepair.Grant
import io.panelassistant.android.platform.PanelPermissionRepair.State
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagementHttpTest {
    @Test fun statusReportsFreshPermissionReadbackWhileFeaturesRemainDisabled() {
        var sdk = 32
        var microphone = false
        var camera: Boolean? = false
        val held = mutableSetOf(Grant.WRITESETTINGS, Grant.ACCESSIBILITY)
        var unreadable: Grant? = null
        PaneldServerHttpFixture(permissionStatus = {
            PanelPermissionRepair.observe(sdk, microphone, camera) { grant ->
                if (grant == unreadable) throw SecurityException("read unavailable")
                grant in held
            }
        }).use { fixture ->
            fixture.useManagementStatus { error("Passive permission read must not refresh storage") }
            testApplication {
                application { fixture.mount(this) }
                val first = client.get("/api/v1/status")
                assertEquals(HttpStatusCode.OK, first.status)
                val states = org.json.JSONObject(first.bodyAsText()).getJSONObject("permissions")
                assertEquals(setOf("notifications", "write_settings", "overlay", "accessibility", "microphone", "camera"), states.keys().asSequence().toSet())
                assertEquals("not_required", states.getString("notifications"))
                assertEquals("held", states.getString("write_settings"))
                assertEquals("missing", states.getString("overlay"))
                assertEquals("held", states.getString("accessibility"))
                assertEquals("not_required", states.getString("microphone"))
                assertEquals("not_required", states.getString("camera"))

                sdk = 34
                microphone = true
                camera = true
                held.clear()
                unreadable = Grant.OVERLAY
                val changed = client.get("/api/v1/status")
                assertEquals(HttpStatusCode.OK, changed.status)
                val changedStates = org.json.JSONObject(changed.bodyAsText()).getJSONObject("permissions")
                for (key in listOf("notifications", "write_settings", "accessibility", "microphone", "camera")) {
                    assertEquals(key, "missing", changedStates.getString(key))
                }
                assertEquals("unreadable", changedStates.getString("overlay"))

                held += Grant.entries
                unreadable = null
                val restored = org.json.JSONObject(client.get("/api/v1/status").bodyAsText()).getJSONObject("permissions")
                assertTrue(restored.keys().asSequence().all { restored.getString(it) == "held" })
                assertFalse(fixture.config.cameraEnabled)
                assertFalse(fixture.config.voiceEnabled)
            }
        }
    }

    @Test fun statusKeepsAllPermissionFieldsUnreadableWhenObservationIsUnavailableOrIncomplete() {
        var unavailable = true
        PaneldServerHttpFixture(permissionStatus = {
            if (unavailable) throw SecurityException("observation unavailable")
            mapOf(Grant.ACCESSIBILITY to State.HELD)
        }).use { fixture ->
            fixture.useManagementStatus {}
            testApplication {
                application { fixture.mount(this) }
                for (missingProvider in listOf(true, false)) {
                    unavailable = missingProvider
                    val response = client.get("/api/v1/status")
                    assertEquals(HttpStatusCode.OK, response.status)
                    val states = org.json.JSONObject(response.bodyAsText()).getJSONObject("permissions")
                    assertEquals(6, states.length())
                    for (key in listOf("notifications", "write_settings", "overlay", "microphone", "camera")) {
                        assertEquals(key, "unreadable", states.getString(key))
                    }
                    assertEquals(if (missingProvider) "unreadable" else "held", states.getString("accessibility"))
                }
            }
        }
    }

    @Test fun `full mount returns the real controller HOME proof only when requested`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.useUnresolvedHome()
            fixture.useManagementStatus { error("A HOME proof must not refresh storage") }
            testApplication {
                application { fixture.mount(this) }
                val passive = client.get("/api/v1/status")
                assertEquals(HttpStatusCode.OK, passive.status)
                org.junit.Assert.assertFalse(org.json.JSONObject(passive.bodyAsText()).has("home_ui"))
                val requested = client.get("/api/v1/status?home_proof=1")
                assertEquals(HttpStatusCode.OK, requested.status)
                val proof = org.json.JSONObject(requested.bodyAsText()).getJSONObject("home_ui")
                assertEquals("unknown", proof.getString("state"))
                assertEquals("home_unresolved", proof.getString("reason"))
                assertEquals("home_resolve", proof.getString("evidence"))
            }
        }
    }

    @Test fun `status selects Companion warning before sampling mDNS`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setDashboardPackage("builtin")
            fixture.useManagementStatus(
                companion = io.panelassistant.android.control.CompanionDb.ServerObservation.EMPTY.copy(
                    status = io.panelassistant.android.control.CompanionDb.UrlStatus(true, 1),
                ),
                mdns = {
                    fixture.config.setDashboardPackage("io.homeassistant.companion.android")
                    "mDNS observed" to null
                },
                onRefresh = {},
            )
            testApplication {
                application { fixture.mount(this) }
                val response = client.get("/api/v1/status")
                assertEquals(HttpStatusCode.OK, response.status)
                val warnings = org.json.JSONObject(response.bodyAsText()).getJSONArray("warnings").toString()
                org.junit.Assert.assertTrue(warnings.contains("mDNS observed"))
                org.junit.Assert.assertFalse(warnings.contains("Companion has no internal URL"))
            }
        }
    }

    @Test fun `status emits unconditional observations and binds database proof to a fresh read`() {
        PaneldServerHttpFixture().use { fixture ->
            var refreshes = 0
            fixture.useManagementStatus { refreshes++ }
            testApplication {
                application { fixture.mount(this) }
                val passive = client.get("/api/v1/status")
                assertEquals(HttpStatusCode.OK, passive.status)
                val body = org.json.JSONObject(passive.bodyAsText())
                for (key in listOf("renderer", "camera", "permissions", "ha_network", "ha_path_probe", "storage_health", "power_safety")) {
                    org.junit.Assert.assertTrue(key, body.has(key))
                }
                org.junit.Assert.assertFalse(body.has("database_observation_nonce"))
                assertEquals(0, refreshes)
                val nonce = "0123456789abcdef0123456789abcdef"
                val refreshed = client.get("/api/v1/status?database_observation_nonce=$nonce")
                assertEquals(HttpStatusCode.OK, refreshed.status)
                assertEquals(nonce, org.json.JSONObject(refreshed.bodyAsText()).getString("database_observation_nonce"))
                assertEquals(1, refreshes)
            }
        }
    }

    @Test fun `diagnostics retain the complete last report after shutdown closes refresh admission`() {
        PaneldServerHttpFixture().use { fixture ->
            val report = "Panel diagnostics\nroot: unavailable\ndensity: unknown\n"
            fixture.useWarmDiagnostics(report, stopping = true)
            testApplication {
                application { fixture.mount(this) }
                repeat(2) {
                    val response = client.get("/api/v1/diag")
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals("text/plain", response.headers["Content-Type"]?.substringBefore(';'))
                    assertEquals(report, response.bodyAsText())
                }
            }
        }
    }

    @Test fun `status refresh and database proof require active read admission`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                for (query in listOf("refresh=1", "database_observation_nonce=proof")) {
                    val response = client.get("/api/v1/status?$query") {
                        header("Sec-Fetch-Site", "cross-site")
                    }
                    assertEquals(HttpStatusCode.Forbidden, response.status)
                    assertEquals("refused: this panel does not serve active reads to another site.\n", response.bodyAsText())
                }
            }
        }
    }
}
