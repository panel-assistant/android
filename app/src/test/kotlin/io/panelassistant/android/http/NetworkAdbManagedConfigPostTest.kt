package io.panelassistant.android.http

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.panelassistant.android.LiveSettingApplyResult
import io.panelassistant.android.LiveSettingAuthority
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.control.ManagedNetworkAdb
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * While Panel Assistant manages network ADB the app cannot turn it on, so Configure's ON is refused at the
 * panel's config door instead of being journalled as a value that waits for good (and could open port 5555
 * once management ends). The Configure projection says ADB is managed, so the row can say so.
 */
class NetworkAdbManagedConfigPostTest {
    @After fun releaseManagement() {
        ManagedNetworkAdb.managed = false
        ManagedNetworkAdb.recoveryHold = false
    }

    @Test fun `a Configure ON while Panel Assistant manages ADB is refused and leaves nothing pending`() =
        withFullReadServer { config, fixture ->
            capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
            val authority = LiveSettingAuthority(setOf("network_adb"), bootIdentity = { "test-boot" })
            val applied = mutableListOf<String>()
            // AdbController.set(true) answers FAILED while Panel Assistant owns the runtime.
            setField(fixture.server, "applySetting", { key: String, value: String ->
                authority.applyOrQueueOutcome(key, value, null) { _, appliedValue, _ ->
                    applied += appliedValue
                    LiveSettingApplyResult.FAILED
                }
            })
            setField(fixture.server, "onReconfigure", { _: Set<String> -> })
            ManagedNetworkAdb.managed = true
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/config") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(JSONObject(mapOf("network_adb" to true)).toString())
                }
                val text = response.bodyAsText()
                assertEquals(HttpStatusCode.Conflict, response.status, text)
                assertEquals("network-adb-managed-by-panel-assistant", JSONObject(text).getString("error"))
                assertTrue(authority.pendingSnapshot().isEmpty(), "nothing is left to apply once management ends")
                assertTrue(applied.isEmpty(), "a refused ON never reaches the controller: $applied")
                assertFalse(config.networkAdbEnabled)

                // The OFF the switch can still ask for is not refused.
                val off = client.post("/api/v1/config") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(JSONObject(mapOf("network_adb" to false)).toString())
                }
                assertFalse(off.status == HttpStatusCode.Conflict, off.bodyAsText())
            }
        }

    @Test fun `without management a Configure ON is still accepted`() =
        withFullReadServer { _, fixture ->
            capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
            val authority = LiveSettingAuthority(setOf("network_adb"), bootIdentity = { "test-boot" })
            setField(fixture.server, "applySetting", { key: String, value: String ->
                authority.applyOrQueueOutcome(key, value, null) { _, _, _ -> LiveSettingApplyResult.FAILED }
            })
            setField(fixture.server, "onReconfigure", { _: Set<String> -> })
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/config") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(JSONObject(mapOf("network_adb" to true)).toString())
                }
                assertFalse(response.status == HttpStatusCode.Conflict, response.bodyAsText())
                assertEquals(mapOf("network_adb" to "true"), authority.pendingSnapshot())
            }
        }

    @Test fun `the Configure projection says ADB is managed and hides an older queued ON`() =
        withFullReadServer { config, _ ->
            // Identity fields come from Android build properties this JVM does not have.
            val live = mapOf("panel_id" to "test_panel", "friendly_name" to "Test panel")
            // A desired ON an older version journalled while Panel Assistant already managed ADB.
            val queued = mapOf("network_adb" to "true", "touch_sound" to "false")
            fun values(managed: Boolean) = JSONObject(ConfigValueProjection(
                config, { live }, { live }, { queued }, { emptySet() }, { "{}" }, { "{}" }, { null },
                networkAdbManaged = { managed },
            ).valuesJson(null))

            val managed = values(true)
            assertTrue(managed.getBoolean("network_adb_managed"))
            assertFalse(managed.getJSONObject("apply_pending").has("network_adb"))
            assertEquals("false", managed.getJSONObject("apply_pending").getString("touch_sound"))

            val unmanaged = values(false)
            assertFalse(unmanaged.getBoolean("network_adb_managed"))
            assertEquals("true", unmanaged.getJSONObject("apply_pending").getString("network_adb"))
        }

    private fun capabilities(fixture: PaneldServerHttpFixture, caps: Capabilities) {
        val observations = fixture.server.javaClass.getDeclaredField("managementObservations")
            .apply { isAccessible = true }.get(fixture.server) as ManagementObservations
        val previous = requireNotNull(observations.snapCache.peek())
        observations.snapCache.set(ManagementSnapshot(
            previous.facts, previous.live, caps, previous.capabilityRows, previous.privilege,
            previous.densityCur, previous.densityBase, previous.fontScale, previous.wifiChronic,
        ))
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
