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
 * While the owner's Keep ADB off lease holds ADB, the owner's Configure ON still works at once: the door has the
 * lease give ADB back as its last step before applying, and an ON that cannot have it is rejected rather than
 * journalled to open port 5555 later. The Configure projection says what holds ADB, so the rows can say so.
 */
class NetworkAdbManagedConfigPostTest {
    @After fun releaseManagement() {
        ManagedNetworkAdb.managed = false
        ManagedNetworkAdb.recoveryHold = false
        ManagedNetworkAdb.owner = null
    }

    /** The lease owner as the door sees it; [releases] counts how often the door asked for the lease back. */
    private class Owner(private val gives: Boolean, private val hardened: Boolean = false, private val onRelease: () -> Unit = {}) : ManagedNetworkAdb.Owner {
        var releases = 0
        override fun hardened() = hardened
        override fun release(): Boolean {
            releases++
            if (gives) { ManagedNetworkAdb.managed = false; onRelease() }
            return gives
        }
        override fun reconcile() {}
        override fun <T> turn(waiting: T, press: () -> T): T = press()
    }

    @Test fun `a Configure ON while Keep ADB off holds ADB opens it at once with the session up`() =
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
            assertTrue(config.setAdbKeepOff(true))
            ManagedNetworkAdb.managed = true
            val owner = Owner(gives = true)
            ManagedNetworkAdb.owner = owner
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/config") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(JSONObject(mapOf("network_adb" to true)).toString())
                }
                val text = response.bodyAsText()
                assertTrue(JSONObject(text).getBoolean("ok"), text)
                assertEquals(1, owner.releases, "the owner's press has the lease give ADB back exactly once")
                assertFalse(config.adbKeepOff, "the press switches Keep ADB off off")
                assertEquals(listOf("true"), applied)
            }
        }

    @Test fun `a Configure ON while Hardened mode holds ADB never asks for the lease`() =
        withFullReadServer { config, fixture ->
            capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
            val applied = mutableListOf<String>()
            setField(fixture.server, "applySetting", { _: String, value: String -> applied += value; false })
            setField(fixture.server, "onReconfigure", { _: Set<String> -> })
            assertTrue(config.setSecurityMode(io.panelassistant.android.Config.SecurityMode.HARDENED))
            ManagedNetworkAdb.managed = true
            val owner = Owner(gives = true, hardened = true)
            ManagedNetworkAdb.owner = owner
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/config") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(JSONObject(mapOf("network_adb" to true)).toString())
                }
                val text = response.bodyAsText()
                assertEquals(HttpStatusCode.Conflict, response.status, text)
                assertEquals("network-adb-incompatible-with-hardened-mode", JSONObject(text).getString("error"))
                assertEquals(0, owner.releases)
                assertTrue(applied.isEmpty())
            }
        }

    @Test fun `a Keep ADB off OFF that cannot have the lease back is rejected, leaves the option on and nothing pending`() =
        withFullReadServer { config, fixture ->
            capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
            val authority = LiveSettingAuthority(setOf("adb_keep_off"), bootIdentity = { "test-boot" })
            val applied = mutableListOf<String>()
            setField(fixture.server, "applySetting", { key: String, value: String ->
                authority.applyOrQueueOutcome(key, value, null) { _, appliedValue, _ ->
                    applied += appliedValue
                    LiveSettingApplyResult.APPLIED
                }
            })
            setField(fixture.server, "onReconfigure", { _: Set<String> -> })
            assertTrue(config.setAdbKeepOff(true))
            ManagedNetworkAdb.managed = true
            val owner = Owner(gives = false)
            ManagedNetworkAdb.owner = owner
            testApplication {
                application { fixture.mount(this) }
                val response = client.post("/api/v1/config") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(JSONObject(mapOf("adb_keep_off" to false)).toString())
                }
                val text = response.bodyAsText()
                val rejected = JSONObject(text).getJSONArray("rejected")
                assertEquals(listOf("adb_keep_off"), (0 until rejected.length()).map(rejected::getString), text)
                assertEquals(1, owner.releases)
                assertTrue(config.adbKeepOff, "the option still says what the panel does")
                assertTrue(applied.isEmpty(), "a refused OFF never reaches the handler: $applied")
                assertTrue(authority.pendingSnapshot().isEmpty(), "nothing is left to reopen ADB later")
            }
        }

    @Test fun `an ON that cannot have the lease back is rejected and leaves nothing pending`() =
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
            // The helper did not answer.
            val owner = Owner(gives = false)
            ManagedNetworkAdb.owner = owner
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

    /** The su-free live values as PaneldService.currentConfigLiveValues reads them, from a real controller. */
    private fun liveAsTheServiceReadsThem(fixture: PaneldServerHttpFixture, config: io.panelassistant.android.Config) {
        val adb = AdbController(config, NetworkAdbDisableTransitionMarker(kotlin.io.path.createTempFile("adb-off").toFile()))
        setField(fixture.server, "configLiveValues", { mapOf("network_adb" to adb.isPersisted().toString()) })
    }

    @Test fun `an ON saved earlier reads off while the lease holds and the owner's next press takes the lease back`() =
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
            // An ON saved before the owner chose Keep ADB off on an older version: the lease closed ADB, but
            // the saved intent is still ON.
            assertTrue(config.setNetworkAdbEnabled(true))
            ManagedNetworkAdb.managed = true
            val owner = Owner(gives = true)
            ManagedNetworkAdb.owner = owner
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
            val owner = Owner(gives = true, onRelease = { leaseHeld = false })
            ManagedNetworkAdb.owner = owner
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

    @Test fun `the Configure projection says what holds ADB and hides an older queued ON`() =
        withFullReadServer { config, _ ->
            // Identity fields come from Android build properties this JVM does not have.
            val live = mapOf("panel_id" to "test_panel", "friendly_name" to "Test panel")
            // A desired ON an older version journalled while its lease already held ADB.
            val queued = mapOf("network_adb" to "true", "touch_sound" to "false")
            fun values(managed: Boolean, works: Boolean = true, external: Boolean = false) = JSONObject(ConfigValueProjection(
                config, { live }, { live }, { queued }, { emptySet() }, { "{}" }, { "{}" }, { null },
                networkAdbManaged = { managed }, networkAdbOwnerSwitchWorks = { works }, networkAdbExternal = { external },
            ).valuesJson(null))

            val keptOff = values(true)
            assertTrue(keptOff.getBoolean("network_adb_managed"))
            assertFalse(keptOff.getJSONObject("apply_pending").has("network_adb"))
            assertEquals("false", keptOff.getJSONObject("apply_pending").getString("touch_sound"))
            assertEquals("available", keptOff.getString("network_adb_owner_switch"), "one press opens ADB")
            assertFalse(keptOff.getBoolean("hardened_mode"))

            assertTrue(values(true, works = false).isNull("network_adb_owner_switch"), "Hardened mode holds it")

            val firmwareOpen = values(false, external = true)
            assertFalse(firmwareOpen.getBoolean("network_adb_managed"))
            assertTrue(firmwareOpen.isNull("network_adb_owner_switch"))
            assertTrue(firmwareOpen.getBoolean("network_adb_external"))
            assertEquals("true", firmwareOpen.getJSONObject("apply_pending").getString("network_adb"))
            assertFalse(values(true, external = true).getBoolean("network_adb_external"), "a held port is not open")
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
