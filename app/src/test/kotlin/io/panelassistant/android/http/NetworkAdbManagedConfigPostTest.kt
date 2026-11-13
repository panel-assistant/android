package io.panelassistant.android.http

import io.ktor.client.request.get
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
import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.ManagedNetworkAdb
import io.panelassistant.android.control.NetworkAdbDisableTransitionMarker
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
        ManagedNetworkAdb.ownerSwitch = null
    }

    /** The lease owner as the door sees it; [releases] counts how often the door asked for the lease back. */
    private class OwnerSwitch(private val waitMs: Long?, private val gives: Boolean) : ManagedNetworkAdb.OwnerSwitch {
        var releases = 0
        override fun waitMs() = waitMs
        override fun release(): Boolean {
            releases++
            if (gives) ManagedNetworkAdb.managed = false
            return gives
        }
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

    @Test fun `a panel that has just lost its session still refuses the ON and says how long the switch waits`() =
        withFullReadServer { config, fixture ->
            capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
            val applied = mutableListOf<String>()
            setField(fixture.server, "applySetting", { _: String, value: String -> applied += value; false })
            setField(fixture.server, "onReconfigure", { _: Set<String> -> })
            ManagedNetworkAdb.managed = true
            // A lease owner that would give the lease back if asked: the door must not ask yet.
            val owner = OwnerSwitch(waitMs = 61_001, gives = true)
            ManagedNetworkAdb.ownerSwitch = owner
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/config") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(JSONObject(mapOf("network_adb" to true)).toString())
                }
                val text = response.bodyAsText()
                assertEquals(HttpStatusCode.Conflict, response.status, text)
                val body = JSONObject(text)
                assertEquals("network-adb-managed-by-panel-assistant", body.getString("error"))
                assertEquals(62, body.getInt("owner_switch_wait_s"))
                assertEquals(0, owner.releases, "the lease is not asked for before the bound")
                assertTrue(applied.isEmpty(), "a refused ON never reaches the controller: $applied")
                assertFalse(config.networkAdbEnabled)
            }
        }

    @Test fun `an ON after the bound that cannot have the lease back is rejected and leaves nothing pending`() =
        withFullReadServer { config, fixture ->
            capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
            val authority = LiveSettingAuthority(setOf("network_adb"), bootIdentity = { "test-boot" })
            val applied = mutableListOf<String>()
            setField(fixture.server, "applySetting", { key: String, value: String ->
                authority.applyOrQueueOutcome(key, value, null) { _, appliedValue, _ ->
                    applied += appliedValue
                    LiveSettingApplyResult.FAILED
                }
            })
            setField(fixture.server, "onReconfigure", { _: Set<String> -> })
            ManagedNetworkAdb.managed = true
            // The session came back in the meantime, say, or the helper did not answer.
            val owner = OwnerSwitch(waitMs = 0, gives = false)
            ManagedNetworkAdb.ownerSwitch = owner
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/config") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(JSONObject(mapOf("network_adb" to true)).toString())
                }
                val text = response.bodyAsText()
                assertFalse(JSONObject(text).getBoolean("ok"), text)
                val rejected = JSONObject(text).getJSONArray("rejected")
                assertEquals(listOf("network_adb"), (0 until rejected.length()).map(rejected::getString), text)
                assertEquals(1, owner.releases)
                assertTrue(applied.isEmpty(), "a rejected ON never reaches the controller: $applied")
                assertTrue(authority.pendingSnapshot().isEmpty(), "nothing is left to open ADB later")
                assertFalse(config.networkAdbEnabled)
            }
        }

    @Test fun `once the panel has been without its session for the bound the ON reaches the controller`() =
        withFullReadServer { _, fixture ->
            capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
            val authority = LiveSettingAuthority(setOf("network_adb"), bootIdentity = { "test-boot" })
            val applied = mutableListOf<String>()
            setField(fixture.server, "applySetting", { key: String, value: String ->
                authority.applyOrQueueOutcome(key, value, null) { _, appliedValue, _ ->
                    applied += appliedValue
                    LiveSettingApplyResult.APPLIED
                }
            })
            setField(fixture.server, "onReconfigure", { _: Set<String> -> })
            ManagedNetworkAdb.managed = true
            val owner = OwnerSwitch(waitMs = 0, gives = true)
            ManagedNetworkAdb.ownerSwitch = owner
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/config") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(JSONObject(mapOf("network_adb" to true)).toString())
                }
                assertFalse(response.status == HttpStatusCode.Conflict, response.bodyAsText())
                assertEquals(1, owner.releases, "the owner's press asks for the lease back exactly once")
                assertEquals(listOf("true"), applied)
            }
        }

    /** The su-free live values as PaneldService.currentConfigLiveValues reads them, from a real controller. */
    private fun liveAsTheServiceReadsThem(fixture: PaneldServerHttpFixture, config: io.panelassistant.android.Config) {
        val adb = AdbController(config, NetworkAdbDisableTransitionMarker(kotlin.io.path.createTempFile("adb-off").toFile()))
        setField(fixture.server, "configLiveValues", { mapOf("network_adb" to adb.isPersisted().toString()) })
    }

    @Test fun `an ON saved by an earlier takeover reads off while the lease holds and the owner's next press takes the lease back`() =
        withFullReadServer { config, fixture ->
            capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
            val authority = LiveSettingAuthority(setOf("network_adb"), bootIdentity = { "test-boot" })
            val applied = mutableListOf<String>()
            setField(fixture.server, "applySetting", { key: String, value: String ->
                authority.applyOrQueueOutcome(key, value, null) { _, appliedValue, _ ->
                    applied += appliedValue
                    LiveSettingApplyResult.APPLIED
                }
            })
            setField(fixture.server, "onReconfigure", { _: Set<String> -> })
            liveAsTheServiceReadsThem(fixture, config)
            // The owner took ADB back once; the panel reconnected and the lease closed ADB again, but the
            // saved intent is still ON.
            assertTrue(config.setNetworkAdbEnabled(true))
            ManagedNetworkAdb.managed = true
            val owner = OwnerSwitch(waitMs = 0, gives = true)
            ManagedNetworkAdb.ownerSwitch = owner
            testApplication {
                application { fixture.mount(this) }
                val values = JSONObject(client.get("/api/v1/config/values").bodyAsText())
                assertFalse(values.getJSONObject("settings").getBoolean("network_adb"), "the switch reads off: ADB is closed")
                val response = client.post("/api/v1/config") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(JSONObject(mapOf("network_adb" to true)).toString())
                }
                val text = response.bodyAsText()
                assertTrue(JSONObject(text).getBoolean("ok"), text)
                assertEquals(1, owner.releases, "the press asks for the lease back although the saved value is ON")
                assertEquals(listOf("true"), applied)
            }
        }

    @Test fun `an ON queued while the lease held does not stop the owner's press taking the lease back`() =
        withFullReadServer { config, fixture ->
            capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
            val authority = LiveSettingAuthority(setOf("network_adb"), bootIdentity = { "test-boot" })
            var leaseHeld = true
            val applied = mutableListOf<String>()
            // AdbController.set(true) answers FAILED while Panel Assistant owns the runtime, so the ON stays queued.
            setField(fixture.server, "applySetting", { key: String, value: String ->
                authority.applyOrQueueOutcome(key, value, null) { _, appliedValue, _ ->
                    applied += appliedValue
                    if (leaseHeld) LiveSettingApplyResult.FAILED else LiveSettingApplyResult.APPLIED
                }
            })
            setField(fixture.server, "pendingLiveSettings", authority::pendingSnapshot)
            setField(fixture.server, "onReconfigure", { _: Set<String> -> })
            liveAsTheServiceReadsThem(fixture, config)
            ManagedNetworkAdb.managed = true
            // A restored or replayed ON reached the controller while the lease held, and waits.
            authority.applyOrQueueOutcome("network_adb", "true", null) { _, _, _ -> LiveSettingApplyResult.FAILED }
            assertEquals(mapOf("network_adb" to "true"), authority.pendingSnapshot())
            val owner = object : ManagedNetworkAdb.OwnerSwitch {
                var releases = 0
                override fun waitMs() = 0L
                override fun release(): Boolean { releases++; leaseHeld = false; ManagedNetworkAdb.managed = false; return true }
            }
            ManagedNetworkAdb.ownerSwitch = owner
            testApplication {
                application { fixture.mount(this) }
                val values = JSONObject(client.get("/api/v1/config/values").bodyAsText())
                assertFalse(values.getJSONObject("settings").getBoolean("network_adb"), "the switch reads off: ADB is closed")
                assertFalse(values.getJSONObject("apply_pending").has("network_adb"), "the queued ON is not shown")
                val response = client.post("/api/v1/config") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(JSONObject(mapOf("network_adb" to true)).toString())
                }
                val text = response.bodyAsText()
                assertTrue(JSONObject(text).getBoolean("ok"), text)
                assertEquals(1, owner.releases, "the press asks for the lease back although an ON is queued")
                assertEquals(listOf("true"), applied)
                assertTrue(authority.pendingSnapshot().isEmpty(), "the applied ON leaves nothing queued")
            }
        }

    @Test fun `an OFF while the lease holds still clears an ON queued then`() =
        withFullReadServer { config, fixture ->
            capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
            val authority = LiveSettingAuthority(setOf("network_adb"), bootIdentity = { "test-boot" })
            val applied = mutableListOf<String>()
            // While Panel Assistant owns the runtime AdbController.set refuses ON and records an OFF.
            setField(fixture.server, "applySetting", { key: String, value: String ->
                authority.applyOrQueueOutcome(key, value, null) { _, appliedValue, _ ->
                    applied += appliedValue
                    if (appliedValue == "true") LiveSettingApplyResult.FAILED else LiveSettingApplyResult.APPLIED
                }
            })
            setField(fixture.server, "pendingLiveSettings", authority::pendingSnapshot)
            setField(fixture.server, "onReconfigure", { _: Set<String> -> })
            liveAsTheServiceReadsThem(fixture, config)
            ManagedNetworkAdb.managed = true
            authority.applyOrQueueOutcome("network_adb", "true", null) { _, _, _ -> LiveSettingApplyResult.FAILED }
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/config") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(JSONObject(mapOf("network_adb" to false)).toString())
                }
                assertTrue(JSONObject(response.bodyAsText()).getBoolean("ok"), response.bodyAsText())
                assertEquals(listOf("false"), applied)
                assertTrue(authority.pendingSnapshot().isEmpty(), "no queued ON is left to open ADB once management ends")
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
            fun values(managed: Boolean, ownerSwitchWaitMs: Long? = null) = JSONObject(ConfigValueProjection(
                config, { live }, { live }, { queued }, { emptySet() }, { "{}" }, { "{}" }, { null },
                networkAdbManaged = { managed }, networkAdbOwnerSwitchWaitMs = { ownerSwitchWaitMs },
            ).valuesJson(null))

            val managed = values(true)
            assertTrue(managed.getBoolean("network_adb_managed"))
            assertFalse(managed.getJSONObject("apply_pending").has("network_adb"))
            assertEquals("false", managed.getJSONObject("apply_pending").getString("touch_sound"))

            assertTrue(managed.isNull("network_adb_owner_switch"), "a live session leaves the switch off the table")
            assertEquals(ManagedNetworkAdb.OWNER_SWITCH_AFTER_MS / 60_000, managed.getLong("network_adb_owner_switch_after_min"))
            // The page follows a state, not a countdown.
            assertEquals("waiting", values(true, ownerSwitchWaitMs = 299_000).getString("network_adb_owner_switch"))
            assertEquals("waiting", values(true, ownerSwitchWaitMs = 1).getString("network_adb_owner_switch"))
            assertEquals("available", values(true, ownerSwitchWaitMs = 0).getString("network_adb_owner_switch"))

            val unmanaged = values(false, ownerSwitchWaitMs = 0)
            assertFalse(unmanaged.getBoolean("network_adb_managed"))
            assertTrue(unmanaged.isNull("network_adb_owner_switch"))
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
