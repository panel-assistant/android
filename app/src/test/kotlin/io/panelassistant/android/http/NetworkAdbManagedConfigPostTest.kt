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
import io.panelassistant.android.LiveSettingRequestOutcome
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
 * While the owner's Keep ADB off lease holds ADB, the owner's Configure ON still works at once: it is the live
 * command Home Assistant's switch sends, never a journalled setting, and one that could not apply is rejected
 * rather than queued to open port 5555 later. Keep ADB off OFF gives the lease back at its door. The Configure
 * projection says what holds ADB, so the rows can say so.
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
        override fun <T> turn(refuseIfLate: Boolean, waiting: T, press: () -> T): T = press()
    }

    /** The page's Network ADB ON as the server hands it on: what it was answered, and from whom. */
    private class PageOn(private val answer: LiveSettingRequestOutcome) : (io.panelassistant.android.CommandOrigin) -> LiveSettingRequestOutcome {
        val origins = mutableListOf<io.panelassistant.android.CommandOrigin>()
        override fun invoke(origin: io.panelassistant.android.CommandOrigin): LiveSettingRequestOutcome { origins += origin; return answer }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postConfig(body: Map<String, Any>) =
        client.post("/api/v1/config") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(JSONObject(body).toString())
        }

    @Test fun `a Configure ON is the live command Home Assistant's switch sends, never a queued setting`() =
        withFullReadServer { config, fixture ->
            capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
            val queued = mutableListOf<String>()
            setField(fixture.server, "applySetting", { key: String, value: String -> queued += "$key=$value"; LiveSettingRequestOutcome.APPLIED })
            setField(fixture.server, "onReconfigure", { _: Set<String> -> })
            val pageOn = PageOn(LiveSettingRequestOutcome.APPLIED)
            setField(fixture.server, "pressNetworkAdbOn", pageOn)
            assertTrue(config.setAdbKeepOff(true))
            ManagedNetworkAdb.managed = true
            testApplication {
                application { fixture.mount(this) }
                val text = postConfig(mapOf("network_adb" to true)).bodyAsText()
                assertTrue(JSONObject(text).getBoolean("ok"), text)
                assertEquals(1, pageOn.origins.size, "one live press, whose handler gives the lease back and applies it")
                assertTrue(queued.isEmpty(), "the ON is never a journalled setting: $queued")
            }
        }

    @Test fun `a Configure ON the press could not apply is rejected and leaves nothing pending`() =
        withFullReadServer { config, fixture ->
            capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
            val authority = LiveSettingAuthority(setOf("network_adb"), bootIdentity = { "test-boot" })
            setField(fixture.server, "applySetting", { key: String, value: String ->
                authority.applyOrQueueOutcome(key, value, null) { _, _, _ -> LiveSettingApplyResult.FAILED }
            })
            setField(fixture.server, "onReconfigure", { _: Set<String> -> })
            setField(fixture.server, "pressNetworkAdbOn", PageOn(LiveSettingRequestOutcome.REJECTED))
            ManagedNetworkAdb.managed = true
            testApplication {
                application { fixture.mount(this) }
                val text = postConfig(mapOf("network_adb" to true)).bodyAsText()
                assertFalse(JSONObject(text).getBoolean("ok"), text)
                val rejected = JSONObject(text).getJSONArray("rejected")
                assertEquals(listOf("network_adb"), (0 until rejected.length()).map(rejected::getString), text)
                assertTrue(authority.pendingSnapshot().isEmpty(), "nothing is left to open ADB later")
                assertFalse(config.networkAdbEnabled)
            }
        }

    @Test fun `an ON saved earlier reads off while the lease holds and the owner's next press is the live command`() =
        withFullReadServer { config, fixture ->
            capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
            setField(fixture.server, "applySetting", { _: String, _: String -> LiveSettingRequestOutcome.APPLIED })
            setField(fixture.server, "onReconfigure", { _: Set<String> -> })
            val pageOn = PageOn(LiveSettingRequestOutcome.APPLIED)
            setField(fixture.server, "pressNetworkAdbOn", pageOn)
            liveAsTheServiceReadsThem(fixture, config)
            // An ON saved before the owner chose Keep ADB off on an older version: the lease closed ADB, but
            // the saved intent is still ON.
            assertTrue(config.setNetworkAdbEnabled(true))
            ManagedNetworkAdb.managed = true
            testApplication {
                application { fixture.mount(this) }
                val values = JSONObject(client.get("/api/v1/config/values").bodyAsText())
                assertFalse(values.getJSONObject("settings").getBoolean("network_adb"), "the switch reads off: ADB is closed")
                val text = postConfig(mapOf("network_adb" to true)).bodyAsText()
                assertTrue(JSONObject(text).getBoolean("ok"), text)
                assertEquals(1, pageOn.origins.size, "the press is made although the saved value is ON")
            }
        }

    @Test fun `a Configure ON while Hardened mode holds ADB never asks for the lease`() =
        withFullReadServer { config, fixture ->
            capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
            val applied = mutableListOf<String>()
            setField(fixture.server, "applySetting", { _: String, value: String -> applied += value; false })
            setField(fixture.server, "onReconfigure", { _: Set<String> -> })
            val pageOn = PageOn(LiveSettingRequestOutcome.APPLIED)
            setField(fixture.server, "pressNetworkAdbOn", pageOn)
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
                assertTrue(pageOn.origins.isEmpty(), "Hardened mode refuses the ON before it is pressed")
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

    /** The su-free live values as PaneldService.currentConfigLiveValues reads them, from a real controller. */
    private fun liveAsTheServiceReadsThem(fixture: PaneldServerHttpFixture, config: io.panelassistant.android.Config) {
        val adb = AdbController(config, NetworkAdbDisableTransitionMarker(kotlin.io.path.createTempFile("adb-off").toFile()))
        setField(fixture.server, "configLiveValues", { mapOf("network_adb" to adb.isPersisted().toString()) })
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
