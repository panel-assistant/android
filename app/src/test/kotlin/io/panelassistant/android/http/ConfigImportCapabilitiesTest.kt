package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.kioskSetting
import io.panelassistant.android.LiveSettingAuthority
import io.panelassistant.android.LiveSettingApplyResult
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.ConfigBundle
import io.panelassistant.android.config.SettingsRegistry
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Complete HTTP import, production kiosk admission and durable Config readback. */
internal class ConfigImportCapabilitiesTest : io.panelassistant.android.testsupport.PanelRuntimeRig() {
    @Test fun aLaterRefusalKeepsEarlierPendingWorkOutOfTheAppliedReceipt() = withRealConfigFixture { config, fixture ->
        capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
        val called = mutableListOf<String>()
        field(fixture.server, "applySetting", { key: String, _: String ->
            called += key
            if (key == "home_dashboard") LiveSettingRequestOutcome.DEFERRED else LiveSettingRequestOutcome.REJECTED
        })
        testApplication {
            application { fixture.mount(this) }
            val response = client.post("/api/v1/config/import") {
                setBody(ConfigBundle.fromValues(mapOf(
                    "friendly_name" to "Imported", "home_dashboard" to "lovelace/pending", "kiosk_lock" to "true",
                )).serialize())
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val receipt = JSONObject(response.bodyAsText())
            assertEquals(listOf("home_dashboard", "kiosk_lock"), called)
            assertEquals(listOf("friendly_name"), strings(receipt, "applied"))
            assertEquals(listOf("home_dashboard"), strings(receipt, "pending"))
            assertEquals("partial", receipt.getString("status"))
            assertTrue(strings(receipt, "errors").single().contains("kiosk_lock"))
            assertEquals("Imported", config.friendlyName)
        }
    }

    @Test fun importAndRevisionReportQueuedKioskWorkBeforeItsSuccessfulOrRefusedCompletion() {
        for (revision in listOf(false, true)) for (success in listOf(false, true)) {
            withRealConfigFixture { config, fixture ->
                assertTrue(config.commitKioskLock(false))
                var actuations = 0
                val runtime = rig(sharedConfig = config, kioskApply = { actuations++; success })
                runtime.sysfs.blockNextWrite("$RELAY_BASE/relay1")
                val worker = kotlin.concurrent.thread { submitNative(runtime, "relay1", "ON") }
                try {
                    assertTrue(runtime.sysfs.blockEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    field(fixture.server, "applySetting", { key: String, value: String ->
                        check(key == "kiosk_lock")
                        io.panelassistant.android.requestKioskSetting(config, { runtime.commands }, value) { false }
                    })
                    capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
                    val bundle = ConfigBundle.fromValues(mapOf("friendly_name" to "Imported", "kiosk_lock" to "true"))
                    val path = if (revision) {
                        val revisions = readField(fixture.server, "revisions") as RevisionStore
                        "/api/v1/config/revisions/${revisions.snapshot(bundle)}/restore"
                    } else "/api/v1/config/import"
                    testApplication {
                        application { fixture.mount(this) }
                        val response = client.post(path) { setBody(bundle.serialize()) }
                        assertEquals(HttpStatusCode.OK, response.status)
                        val receipt = JSONObject(response.bodyAsText())
                        assertFalse(config.kioskLock, "The real worker has not applied the queued lock")
                        assertEquals(0, actuations)
                        assertEquals("Imported", config.friendlyName)
                        assertEquals(listOf("friendly_name"), strings(receipt, "applied"))
                        assertEquals(listOf("kiosk_lock"), strings(receipt, "pending"))
                        assertEquals("partial", receipt.getString("status"))
                    }
                } finally {
                    runtime.sysfs.blockRelease.countDown()
                    worker.join(20_000)
                    barrier(runtime, "kiosk_lock")
                    runtime.close()
                }
                assertEquals(1, actuations)
                assertEquals(success, config.kioskLock)
            }
        }
    }

    @Test fun previewAndImportSkipUnsupportedKioskOnButRetainSupportedSettings() {
        for ((eligible, directSu) in listOf(false to false, true to false, false to true, true to true)) {
            withRealConfigFixture { config, fixture ->
                config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Before")
                assertTrue(config.commitKioskLock(false))
                val available = eligible && directSu
                var actuated = false
                val coordinator = kioskSetting(
                    canEnable = { available }, actuate = { actuated = true; true }, persist = config::commitKioskLock,
                )
                field(fixture.server, "applySetting", { key: String, value: String ->
                    check(key == "kiosk_lock")
                    if (coordinator(value == "true")) LiveSettingRequestOutcome.APPLIED
                    else LiveSettingRequestOutcome.REJECTED
                })
                capabilities(fixture, Capabilities(appCanSu = eligible, networkAdb = directSu, canControlBootChime = true))
                val bundle = ConfigBundle.fromValues(mapOf("friendly_name" to "Imported", "kiosk_lock" to "true")).serialize()
                testApplication {
                    application { fixture.mount(this) }
                    val preview = client.post("/api/v1/config/import?dry_run=1") { setBody(bundle) }
                    assertEquals(HttpStatusCode.OK, preview.status)
                    val json = JSONObject(preview.bodyAsText())
                    val changes = json.getJSONArray("changes")
                    assertEquals(available, (0 until changes.length()).any { changes.getJSONObject(it).getString("key") == "kiosk_lock" })
                    assertEquals(if (available) emptyList() else listOf("kiosk_lock"), strings(json, "skipped"))
                    assertEquals("Before", config.friendlyName)
                    assertFalse(actuated)
                    val applied = client.post("/api/v1/config/import?expected_cfg=" + json.getString("expected_cfg")) { setBody(bundle) }
                    assertEquals(HttpStatusCode.OK, applied.status)
                    val receipt = JSONObject(applied.bodyAsText())
                    assertEquals(if (available) "applied" else "partial", receipt.getString("status"))
                    assertEquals(if (available) emptyList() else listOf("kiosk_lock"), strings(receipt, "skipped"))
                    assertEquals("Imported", config.friendlyName)
                    assertEquals(available, config.kioskLock)
                    assertEquals(available, actuated)
                }
            }
        }
    }

    @Test fun rootlessImportAdmitsKioskOffCleanup() = withRealConfigFixture { config, fixture ->
        assertTrue(config.commitKioskLock(true))
        var cleanup = false
        val coordinator = kioskSetting(
            canEnable = { false }, actuate = { cleanup = true; false }, persist = config::commitKioskLock,
        )
        field(fixture.server, "applySetting", { _: String, value: String ->
            if (coordinator(value == "true")) LiveSettingRequestOutcome.APPLIED else LiveSettingRequestOutcome.REJECTED
        })
        capabilities(fixture, Capabilities())
        val bundle = ConfigBundle.fromValues(mapOf("kiosk_lock" to "false")).serialize()
        testApplication {
            application { fixture.mount(this) }
            val response = client.post("/api/v1/config/import") { setBody(bundle) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("applied", JSONObject(response.bodyAsText()).getString("status"))
            assertFalse(config.kioskLock)
            assertTrue(cleanup)
        }
    }

    @Test fun lateLiveRefusalReturnsJsonAndNamesAlreadyCommittedOrdinarySettings() {
        for (deferred in listOf(false, true)) {
            withRealConfigFixture { config, fixture ->
                config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Before")
                config.setRaw(requireNotNull(SettingsRegistry.spec("ha_area")), "Hall")
                assertTrue(config.commitKioskLock(false))
                capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
                val authority = LiveSettingAuthority(setOf("ha_area"))
                val coordinator = kioskSetting(
                    canEnable = { false }, actuate = { error("root disappeared before actuation") }, persist = config::commitKioskLock,
                )
                field(fixture.server, "applySetting", { key: String, value: String ->
                    if (key == "ha_area") {
                        authority.applyOrQueueOutcome(key, value, null) { appliedKey, appliedValue, _ ->
                            if (deferred) LiveSettingApplyResult.DEFERRED else {
                                config.setRaw(requireNotNull(SettingsRegistry.spec(appliedKey)), appliedValue)
                                LiveSettingApplyResult.APPLIED
                            }
                        }
                    } else {
                        check(key == "kiosk_lock")
                        if (coordinator(value == "true")) LiveSettingRequestOutcome.APPLIED else LiveSettingRequestOutcome.REJECTED
                    }
                })
                testApplication {
                    application { fixture.mount(this) }
                    val bundle = ConfigBundle.fromValues(linkedMapOf(
                        "friendly_name" to "Imported", "ha_area" to "Kitchen", "kiosk_lock" to "true",
                    )).serialize()
                    val response = client.post("/api/v1/config/import") { setBody(bundle) }
                    val text = response.bodyAsText()
                    assertTrue(response.headers["Content-Type"].orEmpty().startsWith("application/json"), text)
                    assertTrue(text.trim().startsWith("{"), text)
                    val receipt = JSONObject(text)
                    assertTrue(receipt.has("applied"), text)
                    assertEquals("partial", receipt.getString("status"))
                    assertEquals(if (deferred) listOf("friendly_name") else listOf("friendly_name", "ha_area"), strings(receipt, "applied"))
                    assertEquals(if (deferred) "Hall" else "Kitchen", config.haArea)
                    assertEquals(if (deferred) mapOf("ha_area" to "Kitchen") else emptyMap(), authority.pendingSnapshot())
                    assertTrue(receipt.getJSONArray("errors").toString().contains("kiosk_lock"))
                    assertEquals("Imported", config.friendlyName)
                    assertFalse(config.kioskLock)
                }
            }
        }
    }

    @Test fun hardenedImportPreviewAndApplyRefuseOriginalAdbIntentBeforeCapabilitySkipping() = withRealConfigFixture { config, fixture ->
        assertTrue(config.setSecurityMode(Config.SecurityMode.HARDENED))
        capabilities(fixture, Capabilities())
        testApplication {
            application { fixture.mount(this) }
            val bundle = ConfigBundle.fromValues(mapOf("friendly_name" to "Must not apply", "network_adb" to "true")).serialize()
            for (suffix in listOf("?dry_run=1", "")) {
                val response = client.post("/api/v1/config/import$suffix") { setBody(bundle) }
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertEquals("network-adb-incompatible-with-hardened-mode", JSONObject(response.bodyAsText()).getString("error"))
                assertFalse(config.friendlyName == "Must not apply")
            }
        }
    }

    @Test fun savedRevisionSkipsLostKioskCapabilityAndReportsDurableState() = withRealConfigFixture { config, fixture ->
        val observations = readField(fixture.server, "managementObservations") as ManagementObservations
        val initial = requireNotNull(observations.snapCache.peek())
        fun resetCapabilities(caps: Capabilities) {
            observations.snapCache.set(initial)
            capabilities(fixture, caps)
        }
        resetCapabilities(Capabilities(appCanSu = true, networkAdb = true, canControlBootChime = true))
        var canEnable = true
        val kiosk = kioskSetting(canEnable = { canEnable }, actuate = { true }, persist = config::commitKioskLock)
        field(fixture.server, "applySetting", { key: String, value: String ->
            if (key == "kiosk_lock") {
                if (kiosk(value == "true")) LiveSettingRequestOutcome.APPLIED else LiveSettingRequestOutcome.REJECTED
            } else LiveSettingRequestOutcome.APPLIED
        })
        testApplication {
            application { fixture.mount(this) }
            suspend fun save(values: Map<String, String>) = client.post("/api/v1/config/import") {
                setBody(ConfigBundle.fromValues(values).serialize())
            }
            assertEquals(HttpStatusCode.OK, save(mapOf("friendly_name" to "Historical", "kiosk_lock" to "true")).status)
            assertTrue(config.kioskLock)
            resetCapabilities(Capabilities(appCanSu = true, networkAdb = true, canControlBootChime = true))
            assertEquals(HttpStatusCode.OK, save(mapOf("friendly_name" to "Current", "kiosk_lock" to "false")).status)
            val revisions = readField(fixture.server, "revisions") as RevisionStore
            val historical = revisions.list().first { it.second.values["friendly_name"] == "Historical" }
            assertEquals("true", revisions.get(historical.first)?.values?.get("kiosk_lock"))
            val current = revisions.snapshot(ConfigBundle.fromValues(
                mapOf("friendly_name" to "Current", "kiosk_lock" to "false"),
                kind = ConfigBundle.KIND_REVISION,
            ))
            resetCapabilities(Capabilities(appCanSu = true, networkAdb = true, canControlBootChime = true))
            assertEquals(HttpStatusCode.OK, client.post("/api/v1/config/revisions/${historical.first}/restore").status)
            assertTrue(config.kioskLock)
            canEnable = false
            resetCapabilities(Capabilities())
            val cleanup = client.post("/api/v1/config/revisions/$current/restore")
            assertEquals(HttpStatusCode.OK, cleanup.status)
            assertFalse(strings(JSONObject(cleanup.bodyAsText()), "skipped").contains("kiosk_lock"))
            assertFalse(config.kioskLock)
            resetCapabilities(Capabilities())
            val response = client.post("/api/v1/config/revisions/${historical.first}/restore")
            assertEquals(HttpStatusCode.OK, response.status)
            val receipt = JSONObject(response.bodyAsText())
            assertEquals("partial", receipt.getString("status"))
            assertTrue(strings(receipt, "skipped").contains("kiosk_lock"))
            assertEquals("Historical", config.friendlyName)
            assertFalse(config.kioskLock)
        }
    }

    @Test fun lateRevisionRefusalNamesAlreadyCommittedKeys() = withRealConfigFixture { config, fixture ->
        capabilities(fixture, Capabilities(appCanSu = true, networkAdb = true))
        val kiosk = kioskSetting(canEnable = { false }, actuate = { error("must refuse") }, persist = config::commitKioskLock)
        field(fixture.server, "applySetting", { key: String, value: String ->
            if (key == "kiosk_lock" && !kiosk(value == "true")) LiveSettingRequestOutcome.REJECTED
            else LiveSettingRequestOutcome.APPLIED
        })
        val revisions = readField(fixture.server, "revisions") as RevisionStore
        val id = revisions.snapshot(ConfigBundle.fromValues(linkedMapOf("friendly_name" to "Historical", "kiosk_lock" to "true"), kind = ConfigBundle.KIND_REVISION))
        testApplication {
            application { fixture.mount(this) }
            val response = client.post("/api/v1/config/revisions/$id/restore")
            assertEquals(HttpStatusCode.OK, response.status)
            val receipt = JSONObject(response.bodyAsText())
            assertEquals("partial", receipt.getString("status"))
            assertEquals(listOf("friendly_name"), strings(receipt, "applied"))
            assertTrue(strings(receipt, "errors").any { it.contains("kiosk_lock") })
            assertEquals("Historical", config.friendlyName)
            assertFalse(config.kioskLock)
        }
    }

    private fun capabilities(fixture: PaneldServerHttpFixture, caps: Capabilities) {
        val observations = readField(fixture.server, "managementObservations") as ManagementObservations
        val previous = requireNotNull(observations.snapCache.peek())
        observations.snapCache.set(ManagementSnapshot(
            previous.facts, previous.live, caps, previous.capabilityRows, previous.privilege,
            previous.densityCur, previous.densityBase, previous.fontScale, previous.wifiChronic,
        ))
    }

    private fun strings(json: JSONObject, name: String): List<String> = json.getJSONArray(name).let { array ->
        (0 until array.length()).map(array::getString)
    }

    private fun withRealConfigFixture(block: (Config, PaneldServerHttpFixture) -> Unit) = withFullReadServer(block)
    private fun field(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
    private fun readField(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
