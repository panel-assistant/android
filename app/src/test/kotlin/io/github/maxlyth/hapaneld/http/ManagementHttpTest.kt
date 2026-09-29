package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagementHttpTest {
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
                companion = io.github.maxlyth.hapaneld.control.CompanionDb.ServerObservation.EMPTY.copy(
                    status = io.github.maxlyth.hapaneld.control.CompanionDb.UrlStatus(true, 1),
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
                for (key in listOf("renderer", "camera", "ha_network", "ha_path_probe", "storage_health", "power_safety")) {
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
