package io.panelassistant.android

import io.panelassistant.android.testsupport.scratchDirectory

import android.content.SharedPreferences
import io.panelassistant.android.config.Migrations
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.fakeProfile
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.util.CompanionInstaller
import io.panelassistant.android.http.LocalizedConfigSchema
import io.panelassistant.android.http.configReadRoutes
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.device.profile.BundledProfileFixtures
import io.panelassistant.android.device.profile.DeviceFacts
import io.panelassistant.android.device.profile.EvdevDeviceInspector
import io.panelassistant.android.device.profile.ProfileActivationPhase
import io.panelassistant.android.device.profile.ProfileMutation
import io.panelassistant.android.device.profile.ProfilePreferences
import io.panelassistant.android.device.profile.ProfileSelection
import io.panelassistant.android.device.profile.ProfileYaml
import io.panelassistant.android.device.profile.RuntimeProfileRegistry
import io.panelassistant.android.device.profile.testProfileDocument
import io.panelassistant.android.http.ConfigValueProjection
import io.panelassistant.android.i18n.CatalogueLoader
import java.io.File
import java.nio.file.Files
import org.json.JSONArray

class ConfigTransactionTest {
    @Test fun admittedBrightnessPairsAndPartialDefaultsReachTypedRawAndConfigureReadsWithoutWrites() {
        val minimum = SettingsRegistry.spec("auto_brightness_minimum_percent")!!
        val maximum = SettingsRegistry.spec("auto_brightness_maximum_percent")!!
        for ((defaults, expected) in listOf(
            mapOf(minimum.key to 30, maximum.key to 60) to (30 to 60),
            mapOf(minimum.key to 90) to (90 to maximum.default.toInt()),
            mapOf(maximum.key to 10) to (minimum.default.toInt() to 10),
        )) {
            withProfileRegistry(
                mapOf("generic.yaml" to ProfileYaml.serialize(
                    testProfileDocument(id = "generic", fallback = true).copy(settingDefaults = defaults),
                )),
                DeviceFacts("unmatched", "unmatched", ""),
            ) { registry ->
                val prefs = fakePreferences(initial = mapOf("panel_id" to "brightness_defaults_test", "friendly_name" to "Brightness defaults test"))
                val config = Config(prefs.instance)
                config.attachProfile(registry.resolveForStartup().profile)
                assertBrightnessReads(config, expected.first, expected.second)
                assertBrightnessDefaults(config, expected.first, expected.second)
                for (spec in listOf(minimum, maximum)) {
                    assertFalse(prefs.values.containsKey(spec.key))
                }
            }
        }
    }

    @Test fun conflictingStoredBrightnessBoundDiscardsProfileBoundsWithoutSeedingAbsentChoices() {
        val minimum = SettingsRegistry.spec("auto_brightness_minimum_percent")!!
        val maximum = SettingsRegistry.spec("auto_brightness_maximum_percent")!!
        for (defaults in listOf(
            mapOf(minimum.key to 30, maximum.key to 60),
            mapOf(minimum.key to 30),
            mapOf(maximum.key to 60),
        )) {
            withProfileRegistry(
                mapOf("generic.yaml" to ProfileYaml.serialize(
                    testProfileDocument(id = "generic", fallback = true).copy(settingDefaults = defaults),
                )),
                DeviceFacts("unmatched", "unmatched", ""),
            ) { registry ->
                val declared = registry.resolveForStartup().profile
                val product = BundledProfileFixtures.profile("generic")
                val storedChoices = mutableListOf<Pair<String, Int>>()
                if (minimum.key in defaults) storedChoices += listOf(maximum.key to 20, maximum.key to 30)
                if (maximum.key in defaults) storedChoices += listOf(minimum.key to 70, minimum.key to 60)
                for ((storedKey, storedValue) in storedChoices) {
                    val prefs = fakePreferences(initial = mapOf(
                        "panel_id" to "brightness_defaults_test", "friendly_name" to "Brightness defaults test",
                        storedKey to storedValue,
                    ))
                    repeat(2) {
                        val config = Config(prefs.instance)
                        for (profile in listOf(declared, product, declared)) {
                            config.attachProfile(profile)
                            assertBrightnessReads(
                                config,
                                if (storedKey == minimum.key) storedValue else minimum.default.toInt(),
                                if (storedKey == maximum.key) storedValue else maximum.default.toInt(),
                            )
                        }
                        assertBrightnessDefaults(config, minimum.default.toInt(), maximum.default.toInt())
                        assertEquals(storedValue, prefs.values[storedKey])
                        assertFalse(prefs.values.containsKey(if (storedKey == minimum.key) maximum.key else minimum.key))
                    }
                }
            }
        }
    }

    @Test fun savedBrightnessPairSurvivesProfileChangesAndRestart() = withProfileRegistry(
        mapOf("generic.yaml" to ProfileYaml.serialize(
            testProfileDocument(id = "generic", fallback = true).copy(settingDefaults = mapOf(
                "auto_brightness_minimum_percent" to 30, "auto_brightness_maximum_percent" to 60,
            )),
        )),
        DeviceFacts("unmatched", "unmatched", ""),
    ) { registry ->
        val prefs = fakePreferences(initial = mapOf("panel_id" to "brightness_defaults_test", "friendly_name" to "Brightness defaults test"))
        val declared = registry.resolveForStartup().profile
        val config = Config(prefs.instance)
        config.attachProfile(declared)
        val editor = config.editor()
        stageImport(config, editor, mapOf("auto_brightness_minimum_percent" to "70", "auto_brightness_maximum_percent" to "90"))
        assertTrue(config.commit(editor))
        for (current in listOf(config, Config(prefs.instance))) {
            for (profile in listOf(declared, BundledProfileFixtures.profile("generic"), declared)) {
                current.attachProfile(profile)
                assertBrightnessReads(current, 70, 90)
            }
        }
        assertEquals(70, prefs.values["auto_brightness_minimum_percent"])
        assertEquals(90, prefs.values["auto_brightness_maximum_percent"])
    }

    private fun assertBrightnessReads(config: Config, minimum: Int, maximum: Int) {
        assertEquals(minimum, config.autoBrightnessMinimumPercent)
        assertEquals(maximum, config.autoBrightnessMaximumPercent)
        val projection = profileProjection(config)
        val values = JSONObject(projection.settingsValuesJson())
        val current = projection.currentValues()
        for ((key, expected) in listOf("auto_brightness_minimum_percent" to minimum, "auto_brightness_maximum_percent" to maximum)) {
            assertEquals(expected.toString(), config.getRaw(SettingsRegistry.spec(key)!!))
            assertEquals(expected, values.getInt(key))
            assertEquals(expected.toString(), current[key])
        }
    }

    private fun assertBrightnessDefaults(config: Config, minimum: Int, maximum: Int) {
        val strings = CatalogueLoader { path ->
            File(BundledProfileFixtures.assetsDirectory.parentFile, path).readText()
        }.strings("en")
        val schema = JSONArray(profileProjection(config).schemaJson(strings, Capabilities(), emptyMap(), null, null))
        val settings = (0 until schema.length()).map(schema::getJSONObject).associateBy { it.getString("key") }
        for ((key, expected) in listOf("auto_brightness_minimum_percent" to minimum, "auto_brightness_maximum_percent" to maximum)) {
            assertEquals(expected.toString(), config.effectiveDefault(SettingsRegistry.spec(key)!!))
            assertEquals(expected.toString(), settings.getValue(key).getString("default"))
        }
    }

    @Test fun freshPortalStartupUsesTheDeclaredZoomInTypedRawAndConfigureReads() = withProfileRegistry(
        BundledProfileFixtures.rawByName(),
        DeviceFacts("portal", "omni", ""),
    ) { registry ->
        val prefs = fakePreferences(initial = mapOf("panel_id" to "profile_defaults_test", "friendly_name" to "Profile defaults test"))
        val config = Config(prefs.instance)
        val resolved = registry.resolveForStartup()
        assertEquals("meta-portal-10-gen2", resolved.profile.id)
        config.attachProfile(resolved.profile)
        assertEquals(85, config.dashboardZoom)
        assertEquals("85", config.getRaw(SettingsRegistry.spec("dashboard_zoom")!!))
        val projection = profileProjection(config)
        assertEquals(85, JSONObject(projection.settingsValuesJson()).getInt("dashboard_zoom"))
        assertEquals("85", projection.currentValues()["dashboard_zoom"])
        // Execute Configure schema projection with the shipped English catalogue as input data.
        val strings = CatalogueLoader { path ->
            File(BundledProfileFixtures.assetsDirectory.parentFile, path).readText()
        }.strings("en")
        val schema = JSONArray(projection.schemaJson(
            strings, Capabilities(), emptyMap(), null, null,
        ))
        val zoom = (0 until schema.length()).map(schema::getJSONObject).single { it.getString("key") == "dashboard_zoom" }
        assertEquals("85", zoom.getString("default"))
        assertFalse(prefs.values.containsKey("dashboard_zoom"))
    }

    @Test fun profileStartupPreservesExplicitZoomIncludingTheOldProductDefault() = withProfileRegistry(
        BundledProfileFixtures.rawByName(),
        DeviceFacts("portal", "omni", ""),
    ) { registry ->
        val resolved = registry.resolveForStartup()
        for (stored in listOf(96, 100)) {
            val prefs = fakePreferences(initial = mapOf("dashboard_zoom" to stored))
            repeat(3) {
                val config = Config(prefs.instance)
                config.attachProfile(resolved.profile)
                assertEquals(stored, config.dashboardZoom)
                assertEquals(stored.toString(), config.getRaw(SettingsRegistry.spec("dashboard_zoom")!!))
            }
            assertEquals(stored, prefs.values["dashboard_zoom"])
        }
    }

    @Test fun changingProfilesChangesOnlyAbsentValuesAndNeverSeedsPreferences() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        val portal = BundledProfileFixtures.profile("meta-portal-10-gen2")
        val generic = BundledProfileFixtures.profile("generic")
        repeat(3) {
            config.attachProfile(portal)
            assertEquals(85, config.dashboardZoom)
            config.attachProfile(generic)
            assertEquals(100, config.dashboardZoom)
            assertFalse(prefs.values.containsKey("dashboard_zoom"))
        }
        val editor = config.editor()
        stageImport(config, editor, mapOf("dashboard_zoom" to "100"))
        assertTrue(config.commit(editor))
        for (profile in listOf(portal, generic, portal)) {
            config.attachProfile(profile)
            assertEquals(100, config.dashboardZoom)
        }
    }

    @Test fun acceptedZoomImportsRemainExplicitAcrossProfileChangesAndRestart() {
        for (imported in listOf("85", "96", "100")) {
            val prefs = fakePreferences()
            val config = Config(prefs.instance)
            config.attachProfile(BundledProfileFixtures.profile("meta-portal-10-gen2"))
            val editor = config.editor()
            stageImport(config, editor, mapOf("dashboard_zoom" to imported))
            assertTrue(config.commit(editor))
            val restarted = Config(prefs.instance)
            restarted.attachProfile(BundledProfileFixtures.profile("generic"))
            assertEquals(imported.toInt(), restarted.dashboardZoom)
            assertEquals(imported.toInt(), prefs.values["dashboard_zoom"])
        }
    }

    @Test fun catalogueImportIsInertAndUnhealthyActivationRestoresThePriorDefaultWithoutChangingChoices() = withProfileRegistry(
        mapOf("generic.yaml" to ProfileYaml.serialize(
            testProfileDocument(id = "generic", fallback = true),
        )),
        DeviceFacts("test-panel", "test-device", "fw-1"),
    ) { registry ->
        val absent = Config(fakePreferences().instance)
        val storedPrefs = fakePreferences(initial = mapOf("dashboard_zoom" to 100))
        val explicit = Config(storedPrefs.instance)
        val initial = registry.resolveForStartup()
        absent.attachProfile(initial.profile)
        explicit.attachProfile(initial.profile)
        val raw = ProfileYaml.serialize(
            testProfileDocument().copy(settingDefaults = mapOf("dashboard_zoom" to 85)),
        )
        val preview = registry.preview(raw)
        assertTrue(preview.issues.toString(), preview.compatible)
        assertTrue(registry.importProfile(raw, preview.previewToken!!) is ProfileMutation.Success)
        assertEquals(100, absent.dashboardZoom)
        assertEquals(100, explicit.dashboardZoom)
        assertTrue(registry.select(
            ProfileSelection.Pinned(preview.summary!!.ref),
            registry.status().catalogRevision,
        ) is ProfileMutation.Success)
        val unproven = registry.resolveForStartup()
        absent.attachProfile(unproven.profile)
        explicit.attachProfile(unproven.profile)
        assertEquals(85, absent.dashboardZoom)
        assertEquals(100, explicit.dashboardZoom)
        val recovered = registry.resolveForStartup()
        assertEquals(ProfileActivationPhase.ROLLED_BACK, registry.status().activation.phase)
        absent.attachProfile(recovered.profile)
        explicit.attachProfile(recovered.profile)
        assertEquals(100, absent.dashboardZoom)
        assertEquals(100, explicit.dashboardZoom)
        assertEquals(100, storedPrefs.values["dashboard_zoom"])
    }

    @Test fun admittedGenericDefaultsReachBooleanFloatAndEnumAccessors() = withProfileRegistry(
        mapOf("generic.yaml" to ProfileYaml.serialize(
            testProfileDocument(id = "generic", fallback = true).copy(
                settingDefaults = mapOf(
                    "dark_mode" to false, "room_temp_offset" to 1.25, "camera_resolution" to "1080p",
                    "network_adb" to true, "zigbee_router" to true, "dashboard_theme" to "Dark",
                ),
            ),
        )),
        DeviceFacts("unmatched", "unmatched", ""),
    ) { registry ->
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        val profile = registry.resolveForStartup().profile
        config.attachProfile(profile)
        assertFalse(config.darkMode)
        assertEquals(1.25f, config.roomTempOffsetC, 0f)
        assertEquals("1080p", config.cameraResolution.wire)
        assertTrue(config.networkAdbEnabled)
        assertTrue(config.zigbeeRouterEnabled)
        assertTrue(config.zigbeeRouterConfigured)
        assertFalse(config.setSecurityMode(Config.SecurityMode.HARDENED))
        assertEquals(Config.SecurityMode.RELAXED, config.securityMode)
        for ((key, expected) in mapOf(
            "dark_mode" to "false", "room_temp_offset" to "1.25", "camera_resolution" to "1080p",
            "network_adb" to "true", "zigbee_router" to "true",
        )) {
            assertEquals(expected, config.getRaw(SettingsRegistry.spec(key)!!))
            assertFalse(prefs.values.containsKey(key))
        }
        // One stored key per setting: a registry commit is what the controller reads back.
        for (key in listOf("network_adb", "zigbee_router")) {
            val spec = SettingsRegistry.spec(key)!!
            assertTrue(config.commitRaw(spec, "false"))
            assertEquals("false", config.getRaw(spec))
            assertEquals(false, prefs.values[key])
        }
        assertFalse(config.networkAdbEnabled)
        assertFalse(config.zigbeeRouterEnabled)
        assertTrue(config.zigbeeRouterConfigured)
        assertTrue(config.setNetworkAdbEnabled(true))
        config.setZigbeeRouterEnabled(true)
        assertEquals("true", config.getRaw(SettingsRegistry.spec("network_adb")!!))
        assertEquals("true", config.getRaw(SettingsRegistry.spec("zigbee_router")!!))
        assertEquals(false, listOf("network_adb_enabled", "zigbee_router_enabled").any(prefs.values::containsKey))
        // An explicit OFF an older build stored under the controller keys survives the fold against a
        // default-ON profile, and without the configured flag it still never claims the vendor gateway.
        val legacyPrefs = fakePreferences(initial = mapOf(
            "config_schema" to SettingsRegistry.SCHEMA,
            "network_adb_enabled" to false, "zigbee_router_enabled" to false,
        ))
        val explicit = Config(legacyPrefs.instance)
        explicit.attachProfile(profile)
        assertTrue(explicit.migrateLiveStore())
        assertFalse(explicit.networkAdbEnabled)
        assertFalse(explicit.zigbeeRouterEnabled)
        assertFalse(explicit.zigbeeRouterConfigured)
        assertEquals("false", explicit.getRaw(SettingsRegistry.spec("network_adb")!!))
        assertEquals("false", explicit.getRaw(SettingsRegistry.spec("zigbee_router")!!))
        // A stored value this build does not declare reads as the profile's default, not a fixed literal.
        val undeclared = Config(fakePreferences(initial = mapOf(
            "dashboard_theme" to "Sepia", "camera_resolution" to "4k",
        )).instance)
        undeclared.attachProfile(profile)
        assertEquals("Dark", undeclared.dashboardTheme)
        assertEquals("1080p", undeclared.cameraResolution.wire)
        assertEquals("Dark", undeclared.getRaw(SettingsRegistry.spec("dashboard_theme")!!))
        assertEquals("1080p", undeclared.getRaw(SettingsRegistry.spec("camera_resolution")!!))
        val hardened = Config(fakePreferences(initial = mapOf("device_local_hardened_security" to true)).instance)
        hardened.attachProfile(profile)
        assertFalse(hardened.networkAdbEnabled)
        assertEquals("false", hardened.getRaw(SettingsRegistry.spec("network_adb")!!))
    }

    private fun profileProjection(config: Config) = ConfigValueProjection(
        config, { emptyMap() }, { emptyMap() }, { emptyMap() }, { emptySet() }, { "{}" }, { "{}" }, { null },
    )

    private fun withProfileRegistry(
        bundled: Map<String, String>,
        facts: DeviceFacts,
        test: (RuntimeProfileRegistry) -> Unit,
    ) {
        val directory = scratchDirectory("profile-setting-defaults").toFile()
        val values = mutableMapOf<String, Any>()
        val preferences = object : ProfilePreferences {
            override fun getString(key: String, default: String) = values[key] as? String ?: default
            override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
            override fun put(vararg entries: Pair<String, Any?>): Boolean {
                entries.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
        }
        try {
            test(RuntimeProfileRegistry(
                filesDir = directory, preferences = preferences, bundledLoader = { bundled }, facts = facts,
                coreVersion = "1.0.0", clock = { 1000L },
                evdevInspector = EvdevDeviceInspector { null },
            ))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun legacyKioskAllowanceIsIgnoredWithoutChangingLockOrOtherSettings() {
        for (schema in listOf(13, SettingsRegistry.SCHEMA)) {
            val prefs = fakePreferences(initial = mapOf(
                "config_schema" to schema,
                "kiosk_companion_packages" to "com.example.ava,com.other.app",
                "kiosk_lock" to true,
                "dashboard_package" to CompanionInstaller.MINIMAL_PKG,
                "panel_id" to "existing_panel",
            ))
            val config = Config(prefs.instance)
            // The return-loop decision uses only dashboard and administration state, even before
            // startup cleanup runs or when a failed commit leaves the old record on disk.
            for (adminVisible in listOf(false, true)) {
                for (state in io.panelassistant.android.control.AppState.values()) {
                    assertEquals(
                        state == io.panelassistant.android.control.AppState.BG && !adminVisible,
                        shouldKioskReturnToDashboard(state, adminVisible),
                    )
                }
            }
            assertTrue(config.migrateLiveStore())
            assertTrue(config.kioskLock)
            assertEquals(CompanionInstaller.MINIMAL_PKG, config.dashboardPackage)
            assertEquals("existing_panel", config.panelId)
            assertFalse(prefs.values.containsKey("kiosk_companion_packages"))
            assertNull(SettingsRegistry.spec("kiosk_companion_packages"))
            val committed = prefs.values.toMap()
            assertTrue(config.migrateLiveStore())
            assertEquals(committed, prefs.values)
        }
    }

    @Test fun oldKioskAllowanceCannotReturnThroughBackupOrBundleRestore() {
        for (schema in listOf(13, SettingsRegistry.SCHEMA, SettingsRegistry.SCHEMA + 1)) {
            val (migrated, _) = Migrations.migrate(schema, mapOf(
                "kiosk_companion_packages" to "com.example.ava",
                "kiosk_lock" to "true",
                "panel_id" to "existing_panel",
            ))
            val decision = io.panelassistant.android.http.planRestoreSettings(migrated, null)
            assertEquals(emptyList<String>(), decision.errors)
            assertFalse(decision.accepted.containsKey("kiosk_companion_packages"))
            assertEquals("true", decision.accepted["kiosk_lock"])
            assertEquals("existing_panel", decision.accepted["panel_id"])
        }
    }

    @Test fun firstConfigReadRepairsLegacyAreaBeforeReportingOverride() = testApplication {
        val stale = fakePreferences(initial = mapOf(
            "ha_area" to "null",
            "device_local_ha_area_user_override" to true,
        ))
        val config = Config(stale.instance)
        val areaSpec = requireNotNull(SettingsRegistry.spec("ha_area"))
        application {
            routing {
                route("/api/v1") {
                    configReadRoutes(
                        currentConfigJson = {
                            JSONObject()
                                .put("ha_area_user_override", config.haAreaUserOverride)
                                .put("settings", JSONObject().put("ha_area", config.getRaw(areaSpec)))
                                .toString()
                        },
                        localizedSchema = { LocalizedConfigSchema("{}", listOf("en")) },
                    )
                }
            }
        }

        val first = JSONObject(client.get("/api/v1/config").bodyAsText())
        assertEquals("", first.getJSONObject("settings").getString("ha_area"))
        assertFalse(first.getBoolean("ha_area_user_override"))
        assertEquals("", stale.values["ha_area"])
        assertEquals(false, stale.values["device_local_ha_area_user_override"])

        val rawOnly = fakePreferences(initial = mapOf("ha_area" to "null"))
        assertEquals("", Config(rawOnly.instance).getRaw(areaSpec))
        assertEquals("", rawOnly.values["ha_area"])

        assertTrue(config.commitHaArea("Kitchen", userOverride = true))
        val named = JSONObject(client.get("/api/v1/config").bodyAsText())
        assertEquals("Kitchen", named.getJSONObject("settings").getString("ha_area"))
        assertTrue(named.getBoolean("ha_area_user_override"))
    }

    @Test fun storedLiteralNullAreaIsClearedBeforeThePanelCanReportIt() {
        val stale = fakePreferences(initial = mapOf(
            "ha_area" to "null",
            "device_local_ha_area_user_override" to true,
        ))
        val config = Config(stale.instance)
        assertEquals("", config.haArea)
        assertEquals("", stale.values["ha_area"])
        assertFalse(config.haAreaUserOverride)
        assertEquals("", Config(stale.instance).haArea)

        val named = fakePreferences(initial = mapOf("ha_area" to "Office"))
        assertEquals("Office", Config(named.instance).haArea)
        assertEquals("Office", named.values["ha_area"])

        val areaSpec = requireNotNull(SettingsRegistry.spec("ha_area"))
        assertTrue(Config(named.instance).commitRaw(areaSpec, "null"))
        assertEquals("", named.values["ha_area"])
        assertEquals(false, named.values["device_local_ha_area_user_override"])
        assertTrue(Config(named.instance).commitRaw(areaSpec, "Kitchen"))
        assertEquals("Kitchen", named.values["ha_area"])
    }

    @Test fun legacyAreaCleanupDoesNotEraseAnInterveningUserChoice() {
        val writerStarted = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        var config: Config? = null
        var writer: Thread? = null
        val prefs = fakePreferences(
            initial = mapOf("ha_area" to "null", "device_local_ha_area_user_override" to true),
            onGetString = { key, value ->
                if (key == "ha_area" && value == "null" && writer == null) {
                    writer = Thread {
                        writerStarted.countDown()
                        checkNotNull(config).commitHaArea("Kitchen", userOverride = true)
                        writerFinished.countDown()
                    }.also(Thread::start)
                    assertTrue(writerStarted.await(2, TimeUnit.SECONDS))
                    writerFinished.await(200, TimeUnit.MILLISECONDS)
                }
            },
        )
        config = Config(prefs.instance)

        assertEquals("", config.haArea)
        assertTrue(writerFinished.await(2, TimeUnit.SECONDS))
        writer?.join(2_000)
        assertEquals("Kitchen", config.haArea)
        assertTrue(config.haAreaUserOverride)
    }

    @Test fun liveMigrationRemovesUpdateOwnersAndExposureFlagsWithoutChangingOtherSettings() {
        for (schema in listOf(13, SettingsRegistry.SCHEMA)) {
            val retired = retiredUpdatePreferences()
            val prefs = fakePreferences(initial = retired + mapOf(
                "config_schema" to schema,
                "panel_id" to "existing-panel",
                "mqtt_broker" to "tcp://ha:1883",
                "dashboard_package" to CompanionInstaller.MINIMAL_PKG,
                "ha_expose_wake_on_wave" to true,
            ))
            val config = Config(prefs.instance)

            assertTrue("migration from schema $schema", config.migrateLiveStore())
            retired.keys.forEach { key ->
                assertFalse("$key survived schema $schema migration", prefs.values.containsKey(key))
            }
            assertEquals(SettingsRegistry.SCHEMA, prefs.values["config_schema"])
            assertEquals("existing-panel", config.panelId)
            assertFalse("the broker address goes with the client", prefs.values.containsKey("mqtt_broker"))
            assertEquals(CompanionInstaller.MINIMAL_PKG, config.dashboardPackage)
            assertEquals(true, prefs.values["ha_expose_wake_on_wave"])
            if (schema == SettingsRegistry.SCHEMA) {
                assertFalse("current-schema cleanup must preserve absent defaults", prefs.values.containsKey(SettingsRegistry.RESPONSE_PERCENT_KEY))
                assertEquals(50, config.autoBrightnessResponsePercent)
            }
            val committed = prefs.values.toMap()
            assertTrue(Config(prefs.instance).migrateLiveStore())
            assertEquals("a second startup must be a no-op", committed, prefs.values)
        }
    }

    @Test fun liveMigrationCarriesTheAddressFamilyToItsRenamedKey() {
        val prefs = fakePreferences(initial = mapOf(
            "config_schema" to 14,
            "mqtt_address_family" to "Force IPv4",
            "mqtt_broker" to "tcp://ha:1883",
        ))
        val config = Config(prefs.instance)

        assertTrue(config.migrateLiveStore())
        assertEquals("Force IPv4", config.addressFamily)
        assertFalse(prefs.values.containsKey("mqtt_address_family"))
        assertFalse(prefs.values.containsKey("mqtt_broker"))
        assertEquals(SettingsRegistry.SCHEMA, prefs.values["config_schema"])
        val committed = prefs.values.toMap()
        assertTrue(Config(prefs.instance).migrateLiveStore())
        assertEquals("a second startup must be a no-op", committed, prefs.values)
    }

    @Test fun upgradeDeletesTheBrokerCredentialsAndEveryStateTheRemovedClientKept() {
        for (schema in listOf(14, SettingsRegistry.SCHEMA)) {
            val removed = removedClientPreferences()
            val prefs = fakePreferences(initial = removed + mapOf(
                "config_schema" to schema,
                "panel_id" to "existing-panel",
                "ha_url" to "http://ha.local:8123",
            ))
            val config = Config(prefs.instance)

            assertTrue("migration from schema $schema", config.migrateLiveStore())
            removed.keys.forEach { key ->
                assertFalse("$key survived schema $schema migration", prefs.values.containsKey(key))
            }
            assertEquals("existing-panel", config.panelId)
            assertEquals("http://ha.local:8123", config.haUrl)
            assertTrue("a panel Panel Assistant already manages keeps that evidence", config.panelAssistantConnectionSeen)
            val committed = prefs.values.toMap()
            assertTrue(Config(prefs.instance).migrateLiveStore())
            assertEquals("a second startup must be a no-op", committed, prefs.values)
        }
    }

    @Test fun onlyAnAcceptedSessionOrAClaimedUpdateCarriesConnectionEvidenceAcrossTheUpgrade() {
        for (authority in listOf("", "mqtt")) {
            val prefs = fakePreferences(initial = mapOf(
                "config_schema" to SettingsRegistry.SCHEMA,
                "panel_assistant_authority" to authority,
            ))
            val config = Config(prefs.instance)

            assertTrue(config.migrateLiveStore())
            assertFalse(prefs.values.containsKey("panel_assistant_authority"))
            assertFalse("authority '$authority' is no evidence of Panel Assistant", config.panelAssistantConnectionSeen)
        }
        for (evidence in listOf("panel_assistant_authority" to "shadow", "panel_assistant_update_owner_seen_ms" to 99L)) {
            val prefs = fakePreferences(initial = mapOf("config_schema" to SettingsRegistry.SCHEMA, evidence))
            val config = Config(prefs.instance)

            assertTrue(config.migrateLiveStore())
            assertFalse(prefs.values.containsKey(evidence.first))
            assertTrue("${evidence.first} is carried forward", config.panelAssistantConnectionSeen)
        }
    }

    @Test fun aFailedPurgeLeavesTheBrokerCredentialsForTheNextStartupToRetry() {
        val initial = removedClientPreferences() + mapOf("config_schema" to SettingsRegistry.SCHEMA)
        val prefs = fakePreferences(initial = initial, commitSucceeds = false)

        assertFalse(Config(prefs.instance).migrateLiveStore())
        assertEquals("a failed commit removes nothing", initial, prefs.values)
        prefs.commitsSucceed.set(true)
        assertTrue(Config(prefs.instance).migrateLiveStore())
        assertFalse(prefs.values.containsKey("mqtt_password"))
    }

    /** Everything the removed broker client kept in the store, as an upgrading panel holds it. */
    private fun removedClientPreferences(): Map<String, Any?> = mapOf(
        "mqtt_broker" to "tcp://ha.local:1883",
        "mqtt_user" to "panel",
        "mqtt_password" to "secret",
        "mqtt_update_shape_paneld" to "v1:110",
        "panel_assistant_authority" to "native",
        "panel_assistant_mqtt_discovery" to "withdraw",
        "panel_assistant_update_owner_seen_ms" to 1234L,
        "last_discovery_version" to "0.9.11",
        "device_local_legacy_aid_bridge_pending_v1" to false,
        "device_local_mqtt_family_broker" to "tcp://ha.local:1883",
        "device_local_mqtt_family_ipv4" to true,
        "device_local_mqtt_announcement_boundary_consumed" to "boundary",
        "ha_expose_camera_enabled" to true,
    )

    @Test fun failedUpdateOwnerRemovalLeavesTheCompleteStoreForTheNextStartupToRetry() {
        for (schema in listOf(13, SettingsRegistry.SCHEMA)) {
            val initial = retiredUpdatePreferences() + mapOf(
                "config_schema" to schema,
                "panel_id" to "existing-panel",
                "ha_expose_wake_on_wave" to false,
            )
            val prefs = fakePreferences(initial = initial, commitSucceeds = false)

            assertFalse(Config(prefs.instance).migrateLiveStore())
            assertEquals("failed commit must remove nothing or advance no marker", initial, prefs.values)
            prefs.commitsSucceed.set(true)
            assertTrue(Config(prefs.instance).migrateLiveStore())
            retiredUpdatePreferences().keys.forEach { key ->
                assertFalse("retry left $key", prefs.values.containsKey(key))
            }
            assertEquals(SettingsRegistry.SCHEMA, prefs.values["config_schema"])
            assertEquals("existing-panel", prefs.values["panel_id"])
            assertEquals(false, prefs.values["ha_expose_wake_on_wave"])
        }
    }

    private fun retiredUpdatePreferences(): Map<String, Any?> {
        val keys = listOf(
            "self_update", "update_channel", "companion_auto_update", "companion_update_channel",
            "webview_auto_update",
        )
        return keys.associateWith { key -> if (key.endsWith("channel")) "prerelease" else true } +
            keys.associate { key -> "ha_expose_$key" to true } +
            mapOf("webview_auto_last_version" to "137.0.7151.119")
    }

    @Test fun dashboardNetworkWarningUpgradeMaterializesDefaultAndPreservesOptOut() {
        val untouched = fakePreferences(initial = mapOf("config_schema" to 10))
        assertTrue(Config(untouched.instance).migrateLiveStore())
        assertEquals(true, untouched.values["dashboard_network_warning"])

        val optedOut = fakePreferences(initial = mapOf(
            "config_schema" to 10,
            "dashboard_network_warning" to false,
        ))
        assertTrue(Config(optedOut.instance).migrateLiveStore())
        assertEquals(false, optedOut.values["dashboard_network_warning"])
    }

    @Test fun uiLanguageDefaultsToAutoAndRoundTripsAnExplicitChoice() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertEquals(SettingsRegistry.DEFAULT_UI_LANGUAGE, config.uiLanguage)
        assertFalse(prefs.values.containsKey("ui_language"))

        config.setRaw(requireNotNull(SettingsRegistry.spec("ui_language")), "zh-Hans")

        assertEquals("zh-Hans", prefs.values["ui_language"])
        assertEquals("zh-Hans", Config(prefs.instance).uiLanguage)
    }

    @Test fun cachedHaVersionIsScopedToTheExactCurrentRendererEndpoint() {
        val prefs = fakePreferences(initial = mapOf("ha_url" to "https://ha.example"))
        val config = Config(prefs.instance)

        assertTrue(config.setHaServerVersionIfOwned("https://ha.example/", "2026.4.2"))
        assertEquals("2026.4.2", config.cachedHaServerVersion("https://ha.example"))
        assertNull(config.cachedHaServerVersion("https://other.example"))
        assertFalse(config.setHaServerVersionIfOwned("https://other.example", "2027.1.0"))
        assertEquals("2026.4.2", config.cachedHaServerVersion("https://ha.example"))
    }

    // A resolution abandoned before it read anything judged no credential, so it must say so. The
    // caller turns an empty result with no such marker into an authentication verdict, which is how a
    // panel ends up parked on a credential screen for a credential nothing ever rejected.
    @Test fun anAbandonedResolutionReportsThatNoCredentialWasJudged() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "https://ha.example",
                "ha_token" to "token",
                "ha_refresh_token" to "refresh",
            ),
        )
        val config = Config(prefs.instance)

        val abandoned = HaCredentialManager.resolve(config, nowSec = 1_000_000L, stillCurrent = { false })
        assertNull(abandoned.session)
        assertTrue("an abandoned resolution judged nothing", abandoned.notAttempted)
        assertFalse(abandoned.rejected)

        // Still current, same configuration: the cached token resolves normally, so the marker tracks
        // abandonment rather than being set on every empty-looking result.
        val live = HaCredentialManager.resolve(config, nowSec = 1_000_000L, stillCurrent = { true })
        assertFalse(live.notAttempted)
    }

    @Test fun generatedPanelIdentityIsPersistedOnce() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertEquals("px30_evb_b818", config.ensurePanelId { "px30_evb_b818" })
        assertEquals("px30_evb_b818", prefs.values["panel_id"])
        assertEquals("px30_evb_b818", config.ensurePanelId { "changed_device_name" })
        assertEquals("px30_evb_b818", prefs.values["panel_id"])
    }

    @Test fun explicitPanelIdentityIsNeverReplacedByGeneration() {
        val prefs = fakePreferences(initial = mapOf("panel_id" to "alpha"))
        val config = Config(prefs.instance)

        assertEquals("alpha", config.ensurePanelId { "generated_panel" })
        assertEquals("alpha", prefs.values["panel_id"])
    }

    @Test fun failedGeneratedIdentityCommitStillReturnsTheGeneratedValue() {
        val prefs = fakePreferences(commitSucceeds = false)
        val config = Config(prefs.instance)

        assertEquals("px30_evb_b818", config.ensurePanelId { "px30_evb_b818" })
        assertFalse(prefs.values.containsKey("panel_id"))
    }

    @Test fun nativeKioskStartsOffOnAFreshInstallAndPersistsExplicitOverrides() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertTrue(config.migrateLiveStore())
        assertFalse(config.dashboardNativeKiosk)
        config.setRaw(requireNotNull(SettingsRegistry.spec("dashboard_native_kiosk")), "true")
        assertTrue(config.dashboardNativeKiosk)
        assertEquals(true, prefs.values["dashboard_native_kiosk"])
        config.setRaw(requireNotNull(SettingsRegistry.spec("dashboard_native_kiosk")), "false")
        assertFalse(config.dashboardNativeKiosk)
        assertEquals(false, prefs.values["dashboard_native_kiosk"])
    }

    @Test fun anUpgradedPanelKeepsTheNativeKioskItWasShowing() {
        // Schema 4 shipped the setting on by default; a store from before it never hid the navigation.
        for ((schema, showing) in listOf(3 to false, 4 to true, 15 to true)) {
            val prefs = fakePreferences(initial = mapOf("config_schema" to schema, "panel_id" to "existing-panel"))
            val config = Config(prefs.instance)

            assertTrue("migration from schema $schema", config.migrateLiveStore())
            assertEquals("schema $schema", showing, config.dashboardNativeKiosk)
            val committed = prefs.values.toMap()
            assertTrue(Config(prefs.instance).migrateLiveStore())
            assertEquals("a second startup must be a no-op", committed, prefs.values)
        }
        val chosen = fakePreferences(initial = mapOf("config_schema" to 15, "dashboard_native_kiosk" to false))
        assertTrue(Config(chosen.instance).migrateLiveStore())
        assertFalse("an owner's explicit choice survives", Config(chosen.instance).dashboardNativeKiosk)
    }

    @Test fun autoSleepDefaultsOffAndPersistsBeforeRuntimeRefresh() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertFalse(config.autoSleep)

        assertEquals(AutoSleepWriteResult.COMMITTED, config.setAutoSleep(true))
        val enabledGeneration = config.autoSleepGeneration
        assertTrue(Config(prefs.instance).autoSleep)
        assertEquals(AutoSleepWriteResult.UNCHANGED, config.setAutoSleep(true))
        assertEquals(null, config.setAutoSleepIf(
            expected = false, expectedGeneration = enabledGeneration, on = false,
        ))
        assertTrue(config.autoSleep)
        assertEquals(AutoSleepWriteResult.COMMITTED, config.setAutoSleepIf(
            expected = true, expectedGeneration = enabledGeneration, on = false,
        ))
        assertFalse(config.autoSleep)
        assertTrue(config.autoSleepGeneration > enabledGeneration)
    }

    @Test fun autoSleepSavedOverHttpRefreshesTheControllerOnceBeforeTheBridgeRuns() {
        val config = Config(fakePreferences(initial = mapOf("auto_sleep_source" to "touch")).instance)
        val spec = requireNotNull(SettingsRegistry.spec("auto_sleep"))
        val refreshes = mutableListOf<Boolean>()
        val refresh = { refreshes += config.autoSleep }

        assertTrue(commitHttpLiveSetting(config, spec, "true", refresh))
        // The bridge handler that follows on this lane finds the value already durable.
        applyAutoSleepSetting(true, config::setAutoSleep) { refresh() }
        assertTrue(commitHttpLiveSetting(config, spec, "true", refresh))
        assertEquals(listOf(true), refreshes)

        assertTrue(commitHttpLiveSetting(config, spec, "false", refresh))
        assertEquals(listOf(true, false), refreshes)
    }

    @Test fun localPresenceIsTheFreshDefaultAndSurvivesEnablingAndRestart() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        assertEquals("panel", config.autoSleepSource)
        assertEquals(AutoSleepWriteResult.COMMITTED, config.setAutoSleep(true))
        assertEquals("panel", Config(prefs.instance).autoSleepSource)
    }

    @Test fun legacyEnabledAutoSleepPreservesHaThroughDisableAndRestart() {
        val prefs = fakePreferences(initial = mapOf("auto_sleep" to true))
        val config = Config(prefs.instance)
        assertEquals("home_assistant", config.autoSleepSource)
        assertEquals(AutoSleepWriteResult.COMMITTED, config.setAutoSleep(false))
        assertEquals("home_assistant", Config(prefs.instance).autoSleepSource)
        assertEquals("home_assistant", config.getRaw(SettingsRegistry.spec("auto_sleep_source")!!))
    }

    @Test fun legacyDisabledAutoSleepRetainsHaWhileFreshMigrationChoosesPanel() {
        val old = fakePreferences(initial = mapOf("auto_sleep" to false))
        assertEquals("home_assistant", Config(old.instance).autoSleepSource)
        val fresh = fakePreferences()
        assertEquals("panel", Config(fresh.instance).autoSleepSource)
    }

    @Test fun startupPreparationLeavesAProfileAutoSleepSourceDefaultUnpersisted() {
        withProfileRegistry(
            mapOf("generic.yaml" to ProfileYaml.serialize(
                testProfileDocument(id = "generic", fallback = true)
                    .copy(settingDefaults = mapOf("auto_sleep_source" to "touch")),
            )),
            DeviceFacts("unmatched", "unmatched", ""),
        ) { registry ->
            val profile = registry.resolveForStartup().profile
            val fresh = fakePreferences()
            Config(fresh.instance).apply {
                migrateLiveStore(); migrateLogShipTcpDefault()
            }
            assertFalse(fresh.values.containsKey("auto_sleep_source"))
            assertEquals("touch", Config(fresh.instance).also { it.attachProfile(profile) }.autoSleepSource)

            val legacy = fakePreferences(initial = mapOf("auto_sleep" to false))
            Config(legacy.instance).apply {
                migrateLiveStore(); migrateLogShipTcpDefault()
            }
            assertEquals("home_assistant", Config(legacy.instance).also { it.attachProfile(profile) }.autoSleepSource)
        }
    }

    @Test fun firstLiveAutoSleepWritePinsTheEffectiveSourceRatherThanTheLegacyOne() {
        withProfileRegistry(
            mapOf("generic.yaml" to ProfileYaml.serialize(
                testProfileDocument(id = "generic", fallback = true)
                    .copy(settingDefaults = mapOf("auto_sleep_source" to "touch")),
            )),
            DeviceFacts("unmatched", "unmatched", ""),
        ) { registry ->
            val profile = registry.resolveForStartup().profile
            val spec = SettingsRegistry.spec("auto_sleep")!!
            val profiled = fakePreferences()
            assertTrue(commitHttpLiveSetting(Config(profiled.instance).also { it.attachProfile(profile) }, spec, "true") {})
            assertEquals("touch", Config(profiled.instance).also { it.attachProfile(profile) }.autoSleepSource)

            val bare = fakePreferences()
            assertTrue(commitHttpLiveSetting(Config(bare.instance), spec, "true") {})
            assertEquals("panel", Config(bare.instance).autoSleepSource)
        }
    }

    @Test fun explicitPresenceSourceChangeFencesOutstandingAutoSleepWrites() {
        val prefs = fakePreferences(initial = mapOf("auto_sleep" to true))
        val config = Config(prefs.instance)
        val generation = config.autoSleepGeneration
        assertTrue(config.commitRaw(SettingsRegistry.spec("auto_sleep_source")!!, "panel"))
        assertEquals("panel", Config(prefs.instance).autoSleepSource)
        assertNull(config.setAutoSleepIf(true, generation, false))
    }

    @Test fun touchInactivityChoiceAndDelaySurviveRestart() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        assertTrue(config.commitRaw(SettingsRegistry.spec("auto_sleep_source")!!, "touch"))
        assertTrue(config.commitRaw(SettingsRegistry.spec("auto_sleep_touch_delay_seconds")!!, "45"))
        val restarted = Config(prefs.instance)
        assertEquals("touch", restarted.autoSleepSource)
        assertEquals(45, restarted.autoSleepTouchDelaySeconds)
    }

    @Test fun autoSleepSourceExclusionsPersistByHaInstallationAreaAndEntityId() {
        val prefs = fakePreferences(initial = mapOf("ha_url" to "https://ha.example"))
        val config = Config(prefs.instance)

        assertTrue(config.setAutoSleepSourceIncluded("office", "binary_sensor.office_motion", false))
        assertEquals(setOf("binary_sensor.office_motion"),
            Config(prefs.instance).autoSleepExcludedEntityIds("office"))
        assertTrue(Config(prefs.instance).autoSleepExcludedEntityIds("kitchen").isEmpty())

        assertTrue(config.setAutoSleepSourceIncluded("office", "binary_sensor.office_motion", true))
        assertTrue(Config(prefs.instance).autoSleepExcludedEntityIds("office").isEmpty())
    }

    @Test fun autoSleepSourceExclusionsDoNotCrossHomeAssistantInstallations() {
        val prefs = fakePreferences(initial = mapOf("ha_url" to "https://ha-one.example"))
        val config = Config(prefs.instance)
        assertTrue(config.setAutoSleepSourceIncluded("office", "binary_sensor.office_motion", false))

        config.setHaConnection("https://ha-two.example", null)

        assertTrue(config.autoSleepExcludedEntityIds("office").isEmpty())
        assertEquals(
            null,
            config.setAutoSleepSourceIncludedIfScope(
                "origin:https://ha-one.example",
                "office",
                "binary_sensor.office_motion",
                false,
            ),
        )
        assertTrue(config.autoSleepExcludedEntityIds("office").isEmpty())
    }

    @Test fun autoSleepSourceExclusionsSurviveAsynchronousHaUuidAdoption() {
        val prefs = fakePreferences(initial = mapOf("ha_url" to "https://ha.example"))
        val config = Config(prefs.instance)
        assertTrue(config.setAutoSleepSourceIncluded("office", "binary_sensor.office_motion", false))

        prefs.instance.edit().putString("dashboard_entity_instance_uuid", "core-uuid").commit()

        assertEquals(setOf("binary_sensor.office_motion"), config.autoSleepExcludedEntityIds("office"))
        assertTrue(config.setAutoSleepSourceIncluded("office", "binary_sensor.office_motion", true))
        config.setHaConnection("https://ha-alias.example", null)
        assertTrue(config.autoSleepExcludedEntityIds("office").isEmpty())
    }

    @Test fun failedAutoSleepCommitDoesNotChangeTheStoredValue() {
        val prefs = fakePreferences(commitSucceeds = false)
        val config = Config(prefs.instance)

        assertEquals(AutoSleepWriteResult.FAILED, config.setAutoSleep(true))
        assertFalse(config.autoSleep)
    }

    @Test fun launchScreenVersionAcknowledgementIsExactAndDurable() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertNull(config.lastLaunchScreenVersionCode)
        assertTrue(config.commitLaunchScreenVersionShown(323L))
        assertEquals(323L, Config(prefs.instance).lastLaunchScreenVersionCode)
        assertTrue(config.commitLaunchScreenVersionShown(322L))
        assertEquals(322L, Config(prefs.instance).lastLaunchScreenVersionCode)
    }

    @Test fun failedLaunchScreenAcknowledgementRemainsUnseen() {
        val prefs = fakePreferences(commitSucceeds = false)
        val config = Config(prefs.instance)

        assertFalse(config.commitLaunchScreenVersionShown(323L))
        assertNull(config.lastLaunchScreenVersionCode)
    }

    @Test fun powerSafetyAcknowledgementIsExactReplaceableAndDurable() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        val first = "a".repeat(64)
        val second = "b".repeat(64)

        assertEquals("", config.powerSafetyAcknowledgementFingerprint)
        assertFalse(config.commitPowerSafetyAcknowledgement("not-a-fingerprint"))
        assertTrue(config.commitPowerSafetyAcknowledgement(first))
        assertEquals(first, Config(prefs.instance).powerSafetyAcknowledgementFingerprint)
        assertTrue(config.commitPowerSafetyAcknowledgement(second))
        assertEquals(second, Config(prefs.instance).powerSafetyAcknowledgementFingerprint)
    }

    @Test fun failedPowerSafetyAcknowledgementRemainsUnseen() {
        val prefs = fakePreferences(commitSucceeds = false)
        val config = Config(prefs.instance)

        assertFalse(config.commitPowerSafetyAcknowledgement("a".repeat(64)))
        assertEquals("", config.powerSafetyAcknowledgementFingerprint)
    }

    @Test fun automaticBrightnessMinimumDefaultsAndPersistsWithinPublicBounds() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertEquals(4, config.autoBrightnessMinimumPercent)
        config.setAutoBrightnessMinimumPercent(30)
        assertEquals(30, config.autoBrightnessMinimumPercent)
        assertEquals(30, prefs.values["auto_brightness_minimum_percent"])
        config.setAutoBrightnessMinimumPercent(0)
        assertEquals(4, config.autoBrightnessMinimumPercent)
        config.setAutoBrightnessMinimumPercent(100)
        assertEquals(99, config.autoBrightnessMinimumPercent)
    }

    @Test fun hardenedSecurityAndOwnedNetworkAdbAreDurablyMutuallyExclusive() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertTrue(config.setNetworkAdbEnabled(true))
        assertFalse(config.setSecurityMode(Config.SecurityMode.HARDENED))
        assertEquals(Config.SecurityMode.RELAXED, config.securityMode)

        assertTrue(config.setNetworkAdbEnabled(false))
        assertTrue(config.setSecurityMode(Config.SecurityMode.HARDENED))
        assertFalse(config.setNetworkAdbEnabled(true))
        assertFalse(config.networkAdbEnabled)

        assertTrue(config.setSecurityMode(Config.SecurityMode.RELAXED))
        assertTrue(config.setNetworkAdbEnabled(true))
    }

    @Test fun adaptiveLearnerConsumesCurrentProfileCaptureOnceAndRetiresAllOldCalibration() {
        val prefs = fakePreferences()
        val calibration = fakePreferences(initial = mapOf(
            "profile_calibration.first-panel.revision-a.prox_near_raw" to 2f,
            "profile_calibration.first-panel.revision-a.prox_far_raw" to 10f,
            "profile_calibration.first-panel.revision-a.prox_threshold" to 6f,
            "profile_calibration.first-panel.revision-a.prox_near_below" to true,
            "profile_calibration.first-panel.revision-a.prox_sensitivity" to "LOW",
            "profile_calibration.other.revision.prox_near_raw" to 20f,
            "profile_calibration.other.revision.prox_far_raw" to 40f,
            "proximity_profile_calibration_migrated" to true,
            "unrelated" to 7,
        ))
        val config = Config(prefs.instance, calibration.instance)
        config.attachProfile(profile("first-panel"))

        assertEquals(ProximityLearningSeed(2f, 10f), config.consumeLegacyProximityLearningSeed())
        assertEquals(null, config.consumeLegacyProximityLearningSeed())
        assertEquals(7, calibration.values["unrelated"])
        assertFalse(calibration.values.keys.any(::isLegacyProximityKey))
    }

    @Test fun adaptiveLearnerCanConsumeUnscopedCaptureAndPurgesIncompleteStateFromBothStores() {
        val prefs = fakePreferences(initial = mapOf(
            "prox_near_raw" to 3f,
            "prox_far_raw" to 9f,
            "prox_threshold" to 6f,
            "prox_near_below" to true,
            "prox_sensitivity" to "HIGH",
        ))
        val calibration = fakePreferences(initial = mapOf(
            "profile_calibration.legacy-panel.revision-a.prox_near_raw" to Float.NaN,
            "profile_calibration.legacy-panel.revision-a.prox_threshold" to 12f,
        ))
        val config = Config(prefs.instance, calibration.instance)

        config.attachProfile(profile("legacy-panel"))
        assertEquals(ProximityLearningSeed(3f, 9f), config.consumeLegacyProximityLearningSeed())
        assertFalse(prefs.values.keys.any(::isLegacyProximityKey))
        assertFalse(calibration.values.keys.any(::isLegacyProximityKey))
    }

    @Test fun adaptiveLearnerFindsCaptureFromTheProfilesPreviousRevision() {
        val prefs = fakePreferences()
        val calibration = fakePreferences(initial = mapOf(
            "profile_calibration.panel.previous-revision.prox_near_raw" to 4f,
            "profile_calibration.panel.previous-revision.prox_far_raw" to 14f,
        ))
        val config = Config(prefs.instance, calibration.instance)
        config.attachProfile(profile("panel", "new-revision"))

        assertEquals(ProximityLearningSeed(4f, 14f), config.consumeLegacyProximityLearningSeed())
        assertFalse(calibration.values.keys.any(::isLegacyProximityKey))
    }

    private fun isLegacyProximityKey(key: String): Boolean =
        key == "proximity_profile_calibration_migrated" || listOf(
            "prox_near_raw", "prox_far_raw", "prox_threshold", "prox_near_below", "prox_sensitivity",
        ).any { key == it || key.endsWith(".$it") }

    @Test fun buttonBacklightReplayIsScopedToARevisionThatDeclaresTheCapability() {
        val prefs = fakePreferences(initial = mapOf("last_button_backlight" to 41))
        val calibration = fakePreferences()
        val config = Config(prefs.instance, calibration.instance)
        config.attachProfile(profile("panel", "revision-a", hasButtonBacklight = true))
        assertEquals(41, config.lastButtonBacklight)
        assertFalse(prefs.values.containsKey("last_button_backlight"))
        config.lastButtonBacklight = 73
        assertEquals(73, config.lastButtonBacklight)

        config.attachProfile(profile("panel", "revision-b", hasButtonBacklight = false))
        assertEquals(-1, config.lastButtonBacklight)
        config.lastButtonBacklight = 200

        config.attachProfile(profile("panel", "revision-a", hasButtonBacklight = true))
        assertEquals(73, config.lastButtonBacklight)
    }

    @Test fun defaultResolverUpgradeInvalidatesOldAutomaticFilterButPreservesItsIds() {
        val target = dashboardEntityTargetKey("url-key", "/")
        val prefs = fakePreferences(initial = mapOf(
            "ha_url" to "http://ha.local:8123",
            "home_dashboard" to "",
            "dashboard_entity_instance" to "url-key",
            "dashboard_entity_instance_origin" to "http://ha.local:8123",
            "dashboard_entity_dashboard_path" to "/",
            "dashboard_entity_filter_instance" to target,
            "dashboard_entity_applied_instance" to target,
            "dashboard_entity_learning" to true,
            "dashboard_entity_learning_applied" to true,
            "dashboard_entity_filter_enabled" to true,
            "dashboard_entity_filter_ids" to "light.old\nsensor.old",
        ))
        val config = Config(prefs.instance)

        assertFalse("the suspect stream must fail closed before the migration commits", config.dashboardEntityFilterEnabled)
        assertEquals(DashboardEntityDefaultResolverMigration.REBOOTSTRAP,
            config.migrateDashboardEntityDefaultResolver())

        assertFalse(config.dashboardEntityFilterEnabled)
        assertFalse(config.dashboardEntityLearningApplied)
        assertEquals(listOf("light.old", "sensor.old"), config.dashboardEntityFilterIds)
        assertEquals(1, prefs.values["dashboard_entity_default_resolver_version"])
        assertEquals(target, prefs.values["dashboard_entity_default_resolver_target"])
        assertEquals(target, prefs.values["dashboard_entity_default_resolver_pending"])
        assertEquals(DashboardEntityDefaultResolverMigration.NOT_NEEDED,
            config.migrateDashboardEntityDefaultResolver())

        // A process restart must retain both the native hold and the obligation to replace the set.
        val restarted = Config(prefs.instance)
        assertTrue(restarted.dashboardEntityDefaultResolverRebootstrapPending)
        assertFalse(restarted.dashboardEntityFilterEnabled)
        assertTrue(restarted.commitDashboardEntitySubscription(true, listOf("light.new"), applied = true))
        assertFalse("the replacement cannot activate before its latch clears", restarted.dashboardEntityFilterEnabled)
        assertTrue(restarted.clearDashboardEntityDefaultResolverRebootstrapPending())
        assertTrue(restarted.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.new"), restarted.dashboardEntityFilterIds)
    }

    @Test fun defaultResolverUpgradeDoesNotInvalidateExplicitDashboardFilter() {
        val target = dashboardEntityTargetKey("url-key", "/lovelace/kiosk")
        val prefs = fakePreferences(initial = mapOf(
            "ha_url" to "http://ha.local:8123",
            "home_dashboard" to "/lovelace/kiosk",
            "dashboard_entity_instance" to "url-key",
            "dashboard_entity_instance_origin" to "http://ha.local:8123",
            "dashboard_entity_dashboard_path" to "/lovelace",
            "dashboard_entity_filter_instance" to target,
            "dashboard_entity_applied_instance" to target,
            "dashboard_entity_learning" to true,
            "dashboard_entity_learning_applied" to true,
            "dashboard_entity_filter_enabled" to true,
            "dashboard_entity_filter_ids" to "light.kiosk",
        ))
        val config = Config(prefs.instance)

        assertEquals(DashboardEntityDefaultResolverMigration.NOT_NEEDED,
            config.migrateDashboardEntityDefaultResolver())

        assertTrue(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityLearningApplied)
        assertEquals(listOf("light.kiosk"), config.dashboardEntityFilterIds)
        assertFalse(prefs.values.containsKey("dashboard_entity_default_resolver_version"))
    }

    @Test fun failedDefaultResolverUpgradeKeepsOldAutomaticFilterFailClosed() {
        val target = dashboardEntityTargetKey("url-key", "/")
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/",
                "dashboard_entity_filter_instance" to target,
                "dashboard_entity_applied_instance" to target,
                "dashboard_entity_learning" to true,
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.old",
            ),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertEquals(DashboardEntityDefaultResolverMigration.PERSIST_FAILED,
            config.migrateDashboardEntityDefaultResolver())

        assertFalse(config.dashboardEntityFilterEnabled)
        assertEquals(true, prefs.values["dashboard_entity_filter_enabled"])
        assertEquals(true, prefs.values["dashboard_entity_learning_applied"])
        assertFalse(prefs.values.containsKey("dashboard_entity_default_resolver_version"))
        assertFalse(prefs.values.containsKey("dashboard_entity_default_resolver_pending"))
    }

    @Test fun disablingAndReenablingLearningRestoresThePreservedNarrowStream() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        assertTrue(config.commitDashboardEntitySubscription(true, listOf("light.kitchen"), applied = true))

        assertTrue(config.commitDashboardEntityLearningMode(enabled = false, clearApplied = true))
        assertFalse(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.kitchen"), config.dashboardEntityFilterIds)

        assertTrue(config.commitDashboardEntityLearningMode(enabled = true, clearApplied = true))
        assertTrue(config.dashboardEntityLearningEnabled)
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.kitchen"), config.dashboardEntityFilterIds)
        assertFalse(config.dashboardEntityLearningApplied)
    }

    @Test fun entityPromotionSourcesDefaultOn() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertTrue(config.dashboardEntityAutoStatic)
        assertTrue(config.dashboardEntityAutoRuntime)
    }

    @Test fun livePreferenceSettersRemainStagedUntilTheBatchCommits() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        val committed = config.applyBatch {
            config.setWakeOnWave(true)
            assertFalse(config.wakeOnWave)
        }

        assertTrue(committed)
        assertTrue(config.wakeOnWave)
    }

    @Test fun explicitWakeOnWaveChoicesOverrideTheOptInDefault() {
        assertTrue(Config(fakePreferences(initial = mapOf("wake_on_wave" to true)).instance).wakeOnWave)
        assertFalse(Config(fakePreferences(initial = mapOf("wake_on_wave" to false)).instance).wakeOnWave)
    }

    @Test fun schemaThreeUpgradePreservesImplicitWakeAndHaExposureDefaults() {
        val prefs = fakePreferences(initial = mapOf("config_schema" to 3))
        val config = Config(prefs.instance)

        config.migrateLiveStore()

        assertTrue(config.wakeOnWave)
        SettingsRegistry.LEGACY_DEFAULT_ON_HA_EXPOSURES.forEach { key ->
            assertTrue(config.haExposed(key, false))
        }
        assertEquals(SettingsRegistry.SCHEMA, prefs.values["config_schema"])
    }

    @Test fun freshStoreReceivesNewOptInDefaultsWhileExplicitLegacyChoicesWin() {
        val fresh = fakePreferences()
        val freshConfig = Config(fresh.instance)
        freshConfig.migrateLiveStore()
        assertFalse(freshConfig.wakeOnWave)
        SettingsRegistry.LEGACY_DEFAULT_ON_HA_EXPOSURES.forEach { key ->
            assertFalse(freshConfig.haExposed(key, false))
        }

        val explicit = fakePreferences(initial = mapOf(
            "config_schema" to 3,
            "wake_on_wave" to false,
            "ha_expose_auto_sleep" to false,
        ))
        val explicitConfig = Config(explicit.instance)
        explicitConfig.migrateLiveStore()
        assertFalse(explicitConfig.wakeOnWave)
        assertFalse(explicitConfig.haExposed("auto_sleep", true))
    }

    /**
     * Schema 6 moves the adaptive response to a new key, so every pre-schema-6 store reaches this code
     * without one. What separates "carry the old response forward" from "use the new default" is the
     * schema marker, and that discriminator is backed by release history rather than by inference:
     * v0.9.4 shipped schema 1 with no sensitivity setting at all, while v0.9.5 shipped schema 2 with the
     * setting and ran this migration at startup. A store that has the setting has therefore been stamped
     * at 2 or later; a store still on the schema-1 sentinel never had one.
     */
    @Test fun theResponseMigrationCarriesEveryUpgradeShapeAndLeavesAFreshInstallOnItsDefault() {
        val default = SettingsRegistry.spec(SettingsRegistry.RESPONSE_PERCENT_KEY)!!.default.toInt()
        val legacyImage = Migrations.rescaleSensitivity(SettingsRegistry.LEGACY_NEUTRAL_SENSITIVITY)

        // Fresh install: the schema-1 sentinel, nothing materialized, no previous response to carry.
        val fresh = fakePreferences()
        val freshConfig = Config(fresh.instance)
        assertTrue(freshConfig.migrateLiveStore())
        assertEquals(default, freshConfig.autoBrightnessResponsePercent)
        assertFalse(fresh.values.containsKey(SettingsRegistry.RESPONSE_PERCENT_KEY))

        // An 0.9.5-era panel that never opened the control. This is the shape the review called
        // indistinguishable from fresh state: no materialized sensitivity key. The schema marker is what
        // tells them apart, and a panel with the setting always has one.
        val untouched = fakePreferences(initial = mapOf("config_schema" to 2))
        val untouchedConfig = Config(untouched.instance)
        assertTrue(untouchedConfig.migrateLiveStore())
        assertEquals(legacyImage, untouchedConfig.autoBrightnessResponsePercent)

        // An explicitly tuned panel: rescaled to the identical gain, and the retired key removed so no
        // older build can read the new number under the old scale.
        val tuned = fakePreferences(initial = mapOf(
            "config_schema" to 5,
            SettingsRegistry.LEGACY_SENSITIVITY_KEY to 5,
        ))
        val tunedConfig = Config(tuned.instance)
        assertTrue(tunedConfig.migrateLiveStore())
        assertEquals(10, tunedConfig.autoBrightnessResponsePercent)
        assertFalse(tuned.values.containsKey(SettingsRegistry.LEGACY_SENSITIVITY_KEY))

        // A panel that already ran schema 6 keeps exactly what it has while advancing through later
        // additive schemas.
        val current = fakePreferences(initial = mapOf(
            "config_schema" to 6,
            SettingsRegistry.RESPONSE_PERCENT_KEY to 12,
        ))
        val currentConfig = Config(current.instance)
        assertTrue(currentConfig.migrateLiveStore())
        assertEquals(12, currentConfig.autoBrightnessResponsePercent)
        assertEquals(SettingsRegistry.SCHEMA, current.values["config_schema"])
    }

    /**
     * A semantic migration that reports success without writing would leave the panel running one
     * schema's numbers under another's meaning. The schema marker moves in the same transaction as the
     * values, so a failed commit must leave the store wholly unmigrated AND must be reported.
     */
    @Test fun aFailedLiveStoreMigrationIsReportedAndLeavesTheStoreAtItsOldSchema() {
        val prefs = fakePreferences(
            initial = mapOf("config_schema" to 5, SettingsRegistry.LEGACY_SENSITIVITY_KEY to 5),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertFalse("a migration that did not commit must not report success", config.migrateLiveStore())
        assertEquals(5, prefs.values["config_schema"])
        assertEquals(5, prefs.values[SettingsRegistry.LEGACY_SENSITIVITY_KEY])
        assertFalse(prefs.values.containsKey(SettingsRegistry.RESPONSE_PERCENT_KEY))
    }

    @Test fun everyWakeOnWaveWriteInvalidatesAlreadyAdmittedGestureWork() {
        val config = Config(fakePreferences().instance)
        val initial = config.wakeOnWaveGeneration

        config.setWakeOnWave(false)
        val disabled = config.wakeOnWaveGeneration
        config.setWakeOnWave(true)

        assertTrue(disabled > initial)
        assertTrue(config.wakeOnWaveGeneration > disabled)
    }

    @Test fun failedBatchCommitDoesNotPartiallyPublishStagedSetters() {
        val prefs = fakePreferences(commitSucceeds = false)
        val config = Config(prefs.instance)

        val committed = config.applyBatch {
            config.setPanelId("new_panel")
            config.setHaConnection("http://ha.local:8123", "secret")
        }

        assertFalse(committed)
        assertFalse(prefs.values.containsKey("panel_id"))
        assertFalse(prefs.values.containsKey("ha_url"))
    }

    @Test fun registryStagesOauthExpiryAsLongWithoutTruncation() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        val spec = SettingsRegistry.spec("ha_token_expiry")!!
        val expiry = Int.MAX_VALUE.toLong() + 86_400L
        val editor = config.editor()

        config.stage(editor, spec, expiry.toString())

        assertTrue(editor.commit())
        assertEquals(expiry, config.haTokenExpiry)
        assertEquals(expiry.toString(), config.getRaw(spec))
    }

    @Test fun registryStagesVendorPackageSelectionsInTheExistingConfigOwner() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        val spec = SettingsRegistry.spec("tame_vendor_packages")!!
        val normalized = "com.vendor.one com.vendor.two"
        val editor = config.editor()

        config.stage(editor, spec, normalized)

        assertTrue(editor.commit())
        assertEquals(normalized, config.getRaw(spec))
        assertEquals(normalized, config.tameVendorPackagesRaw)
        assertEquals(listOf("com.vendor.one", "com.vendor.two"), config.tameVendorPackages)
    }

    @Test fun legacyCredentialRestoreClearsUnrelatedTargetExpiry() {
        val prefs = fakePreferences(initial = mapOf(
            "ha_token" to "target-access",
            "ha_refresh_token" to "target-refresh",
            "ha_token_expiry" to 9_999_999_999L,
        ))
        val config = Config(prefs.instance)
        val accepted = mapOf(
            "ha_token" to "restored-access",
            "ha_refresh_token" to "restored-refresh",
        )
        val editor = config.editor()
            .putString("ha_token", accepted.getValue("ha_token"))
            .putString("ha_refresh_token", accepted.getValue("ha_refresh_token"))

        config.stageImportDependencies(editor, accepted)

        assertTrue(editor.commit())
        assertEquals("restored-access", config.haToken)
        assertEquals("restored-refresh", config.haRefreshToken)
        assertEquals(0L, config.haTokenExpiry)
    }

    @Test fun credentialRestorePreservesItsMatchingExplicitExpiryAtomically() {
        val prefs = fakePreferences(initial = mapOf("ha_token_expiry" to 111L))
        val config = Config(prefs.instance)
        val expiry = 2_345_678_901L
        val accepted = mapOf(
            "ha_token" to " restored access ",
            "ha_refresh_token" to " restored refresh ",
            "ha_token_expiry" to expiry.toString(),
        )
        val editor = config.editor()
        accepted.forEach { (key, value) ->
            config.stage(editor, SettingsRegistry.spec(key)!!, value)
        }
        config.stageImportDependencies(editor, accepted)

        assertTrue(editor.commit())
        assertEquals(" restored access ", config.haToken)
        assertEquals(" restored refresh ", config.haRefreshToken)
        assertEquals(expiry, config.haTokenExpiry)
    }

    @Test fun panelIdentityAndDependentLinkInvalidationShareOneCommit() {
        val prefs = fakePreferences(
            initial = mapOf("panel_id" to "old_panel", "ha_device_url" to "http://ha/device/old"),
        )
        val config = Config(prefs.instance)
        val editor = config.editor()

        config.stagePanelId(editor, "new_panel")

        assertEquals("old_panel", prefs.values["panel_id"])
        assertEquals("http://ha/device/old", prefs.values["ha_device_url"])
        assertTrue(editor.commit())
        assertEquals("new_panel", prefs.values["panel_id"])
        assertFalse(prefs.values.containsKey("ha_device_url"))
    }

    @Test fun importClearingCredentialIdentitiesClearsDependentSecretsInTheSameCommit() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha:8123", "ha_token" to "old-access",
                "ha_refresh_token" to "old-refresh", "ha_client_id" to "old-client",
                "ha_token_expiry" to 1234L,
            ),
        )
        val config = Config(prefs.instance)
        val editor = config.editor()
        editor.putString("ha_url", "")

        config.stageImportDependencies(editor, mapOf("ha_url" to ""))

        assertEquals("old-access", prefs.values["ha_token"])
        assertTrue(editor.commit())
        assertEquals("", prefs.values["ha_token"])
        assertEquals("", prefs.values["ha_refresh_token"])
        assertEquals("", prefs.values["ha_client_id"])
        assertEquals(0L, prefs.values["ha_token_expiry"])
    }

    @Test fun importReplacementAccessTokenSupersedesAnUnreplacedRefreshSession() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha:8123", "ha_token" to "old-access",
                "ha_refresh_token" to "old-refresh", "ha_token_expiry" to 1234L,
            ),
        )
        val config = Config(prefs.instance)
        val editor = config.editor().putString("ha_token", "new-access")

        config.stageImportDependencies(editor, mapOf("ha_token" to "new-access"))

        assertTrue(editor.commit())
        assertEquals("new-access", prefs.values["ha_token"])
        assertEquals("", prefs.values["ha_refresh_token"])
        assertEquals(0L, prefs.values["ha_token_expiry"])
    }

    @Test fun importExplicitAccessTokenReassertionStillDropsTheOldRefreshSession() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_token" to "same-access", "ha_refresh_token" to "old-refresh",
                "ha_token_expiry" to 1234L,
            ),
        )
        val config = Config(prefs.instance)
        val editor = config.editor().putString("ha_token", "same-access")

        config.stageImportDependencies(editor, mapOf("ha_token" to "same-access"))

        assertTrue(editor.commit())
        assertEquals("", prefs.values["ha_refresh_token"])
        assertEquals(0L, prefs.values["ha_token_expiry"])
    }

    @Test fun importNewRefreshSessionAndHaUrlKeepTheirCanonicalValues() {
        val prefs = fakePreferences(
            initial = mapOf("ha_token" to "old-access", "ha_refresh_token" to "old-refresh"),
        )
        val config = Config(prefs.instance)
        val editor = config.editor()
            .putString("ha_url", "http://ha:8123/")
            .putString("ha_token", "new-access")
            .putString("ha_refresh_token", "new-refresh")

        config.stageImportDependencies(
            editor,
            mapOf(
                "ha_url" to "http://ha:8123/",
                "ha_token" to "new-access",
                "ha_refresh_token" to "new-refresh",
            ),
        )

        assertTrue(editor.commit())
        assertEquals("http://ha:8123", prefs.values["ha_url"])
        assertEquals("new-access", prefs.values["ha_token"])
        assertEquals("new-refresh", prefs.values["ha_refresh_token"])
    }

    @Test fun borrowedRendererConnectionAndZoomPublishInOneCommit() {
        val prefs = fakePreferences(initial = mapOf("dashboard_zoom" to 100))
        val config = Config(prefs.instance)

        assertTrue(config.setBorrowedRendererSettings(
            url = "http://ha:8123/",
            accessToken = "access",
            refreshToken = "refresh",
            tokenExpiry = 1234L,
            clientId = "client",
            zoom = 125,
        ))

        assertEquals("http://ha:8123", prefs.values["ha_url"])
        assertEquals("access", prefs.values["ha_token"])
        assertEquals("refresh", prefs.values["ha_refresh_token"])
        assertEquals(1234L, prefs.values["ha_token_expiry"])
        assertEquals("client", prefs.values["ha_client_id"])
        assertEquals(125, prefs.values["dashboard_zoom"])
        assertEquals(true, prefs.values["renderer_launch_pending"])
    }

    @Test fun failedBorrowedRendererCommitPublishesNothing() {
        val prefs = fakePreferences(
            initial = mapOf("dashboard_zoom" to 100),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertFalse(config.setBorrowedRendererSettings(
            url = "http://ha:8123",
            accessToken = "access",
            refreshToken = "refresh",
            tokenExpiry = 1234L,
            clientId = "client",
            zoom = 125,
        ))

        assertFalse(prefs.values.containsKey("ha_url"))
        assertFalse(prefs.values.containsKey("ha_token"))
        assertFalse(prefs.values.containsKey("ha_refresh_token"))
        assertEquals(100, prefs.values["dashboard_zoom"])
        assertFalse(prefs.values.containsKey("renderer_launch_pending"))
    }

    @Test fun rendererSwitchAndLaunchHandoffAreDurable() {
        val prefs = fakePreferences(initial = mapOf("dashboard_package" to "foreign.renderer"))
        val config = Config(prefs.instance)

        assertTrue(config.applyBatch {
            config.setDashboardPackage("builtin")
            assertFalse(config.rendererLaunchPending)
        })
        assertEquals("builtin", config.dashboardPackage)
        assertTrue(config.rendererLaunchPending)

        assertTrue(config.completeRendererLaunch())
        assertFalse(config.rendererLaunchPending)
    }

    @Test fun companionToBuiltinRoundTripSurvivesProcessRecreation() {
        val prefs = fakePreferences(initial = mapOf("dashboard_package" to "builtin"))
        val firstProcess = Config(prefs.instance)

        assertTrue(firstProcess.applyBatch {
            firstProcess.setDashboardPackage(CompanionInstaller.MINIMAL_PKG)
        })
        val companionProcess = Config(prefs.instance)
        assertEquals(CompanionInstaller.MINIMAL_PKG, companionProcess.dashboardPackage)
        assertTrue(companionProcess.rendererLaunchPending)
        assertTrue(companionProcess.completeRendererLaunch())

        assertTrue(companionProcess.applyBatch {
            companionProcess.setDashboardPackage("builtin")
        })
        val builtinProcess = Config(prefs.instance)
        assertEquals("builtin", builtinProcess.dashboardPackage)
        assertTrue(builtinProcess.rendererLaunchPending)
    }

    @Test fun importedRendererSwitchStagesLaunchHandoffInTheSameCommit() {
        val prefs = fakePreferences(initial = mapOf("dashboard_package" to "foreign.renderer"))
        val config = Config(prefs.instance)
        val editor = config.editor().putString("dashboard_package", "builtin")

        config.stageImportDependencies(editor, mapOf("dashboard_package" to "builtin"))

        assertTrue(editor.commit())
        assertEquals("builtin", config.dashboardPackage)
        assertTrue(config.rendererLaunchPending)
    }

    @Test fun entityFilterListAndEnableFlagPublishAtomically() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        assertTrue(config.setDashboardEntityFilter(true, listOf("sensor.b", "light.a", "sensor.b")))

        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.a", "sensor.b"), config.dashboardEntityFilterIds)
        assertEquals("light.a\nsensor.b", prefs.values["dashboard_entity_filter_ids"])
    }

    @Test fun failedEntityFilterCommitPublishesNeitherListNorFlag() {
        val prefs = fakePreferences(commitSucceeds = false)
        val config = Config(prefs.instance)

        assertFalse(config.setDashboardEntityFilter(true, listOf("sensor.one")))

        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
    }

    @Test fun anUpgradeReRootsARouteQualifiedOwnerInsteadOfStrandingItsFilter() {
        // An install from before scope-rooting owns its filter with the full route. The rewrite used to
        // run only at first binding — never true here — so nothing repaired it, the filter read as owned
        // by a key that can no longer be constructed, and a startup finding the catalogue already synced
        // skipped the rescan meant to fix it.
        val legacyOwner = "7:url-key/lovelace/kiosk"
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/lovelace/kiosk",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.office",
                "dashboard_entity_filter_instance" to legacyOwner,
                "dashboard_entity_applied_instance" to legacyOwner,
            ),
        )
        val config = Config(prefs.instance)

        assertEquals(
            "url-key",
            config.prepareDashboardEntityInstance("http://ha.local:8123", "/lovelace/kiosk", "url-key"),
        )

        val rooted = dashboardEntityTargetKey("url-key", "/lovelace")
        assertEquals(rooted, prefs.values["dashboard_entity_filter_instance"])
        assertEquals(rooted, prefs.values["dashboard_entity_applied_instance"])
        // Lossless: the list behind the owner is still the right one, so it survives the re-rooting.
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.office"), config.dashboardEntityFilterIds)
    }

    @Test fun anOwnerFromADifferentDashboardIsNeverReRootedIntoTheConfiguredOne() {
        // The other half of the boundary. Migrating this would let a real dashboard change silently
        // inherit another dashboard's learned list, trading the reported bug for a silent one.
        val foreignOwner = "7:url-key/beta"
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/beta",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.kitchen",
                "dashboard_entity_filter_instance" to foreignOwner,
            ),
        )
        val config = Config(prefs.instance)

        assertEquals(
            "url-key",
            config.prepareDashboardEntityInstance("http://ha.local:8123", "/lovelace", "url-key"),
        )

        assertEquals(foreignOwner, prefs.values["dashboard_entity_filter_instance"])
    }

    @Test fun adoptionCarriesOperatorIntentRecordedBeforeADashboardChange() {
        // Pins and exclusions are owned by the INSTANCE. Comparing the whole dashboard-qualified key
        // stranded any intent captured before a dashboard change: adoption skipped it, and it then read
        // as owned by the legacy instance under the stable key — invisible, and overwritten by the next
        // edit.
        val capturedElsewhere = dashboardEntityTargetKey("url-key", "/beta")
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/lovelace",
                "dashboard_entity_overrides" to "+person.lise\n-sensor.noisy",
                "dashboard_entity_override_instance" to capturedElsewhere,
            ),
        )
        val config = Config(prefs.instance)

        assertTrue(
            config.adoptDashboardEntityInstance(
                "http://ha.local:8123", "/lovelace", "uuid-1", "url-key", "stable-key",
            ),
        )

        val owner = prefs.values["dashboard_entity_override_instance"] as String
        assertEquals("stable-key", dashboardEntityInstanceOf(owner))
        // The captured scope is preserved rather than restamped: it records where intent was taken.
        assertEquals("/beta", dashboardEntityPathOf(owner))
        assertEquals(
            mapOf("person.lise" to "pinned", "sensor.noisy" to "forced_exclude"),
            config.dashboardEntityOverrides,
        )
    }

    @Test fun firstIdentityBindingPreservesLegacyStateForTheConfiguredDashboard() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.office\nsensor.temperature",
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_overrides" to "+person.lise",
            ),
        )
        val config = Config(prefs.instance)

        assertEquals(
            "url-key",
            config.prepareDashboardEntityInstance("http://HA.local:8123/", "/lovelace/kiosk", "url-key"),
        )

        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.office", "sensor.temperature"), config.dashboardEntityFilterIds)
        assertTrue(config.dashboardEntityLearningApplied)
        assertEquals(mapOf("person.lise" to "pinned"), config.dashboardEntityOverrides)
        assertEquals(dashboardEntityTargetKey("url-key", "/lovelace/kiosk"), config.dashboardEntityTargetKey)
    }

    @Test fun reassertingTheLegacyDashboardBeforeMigrationStillPreservesItsList() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.office",
            ),
        )
        val config = Config(prefs.instance)

        config.setHomeDashboard("/lovelace/kiosk/")
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))

        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.office"), config.dashboardEntityFilterIds)
    }

    @Test fun endpointChangeBeforeFirstMigrationHidesUnownedLegacyState() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://old-ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.old",
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_overrides" to "+person.old",
            ),
        )
        val config = Config(prefs.instance)

        config.setHaConnection("http://new-ha.local:8123", null)

        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
        assertFalse(config.dashboardEntityLearningApplied)
        // A different Home Assistant is a real boundary: the same entity id there is an unrelated
        // entity, so the operator's overrides do not follow.
        assertTrue(config.dashboardEntityOverrides.isEmpty())
        assertEquals("url-new", config.prepareDashboardEntityInstance(
            "http://new-ha.local:8123", "/lovelace/kiosk", "url-new",
        ))
        assertFalse(config.dashboardEntityFilterEnabled)
    }

    @Test fun verifiedUuidPreservesStateAcrossUrlAliasesButNotBeforeAdoption() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.office",
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_overrides" to "+person.lise",
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-a", config.prepareDashboardEntityInstance("http://ha.local:8123", "/lovelace/kiosk", "url-a"))
        assertTrue(config.adoptDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "00112233445566778899aabbccddeeff", "url-a", "uuid-key",
        ))

        config.setHaConnection("https://ha.example.net", null)
        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
        assertFalse(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityOverrides.isEmpty())

        assertEquals("url-alias", config.prepareDashboardEntityInstance(
            "https://ha.example.net", "/lovelace/kiosk", "url-alias",
        ))
        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.adoptDashboardEntityInstance(
            "https://ha.example.net", "/lovelace/kiosk", "00112233445566778899aabbccddeeff", "url-alias", "uuid-key",
        ))
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.office"), config.dashboardEntityFilterIds)
        assertTrue(config.dashboardEntityLearningApplied)
        assertEquals(mapOf("person.lise" to "pinned"), config.dashboardEntityOverrides)
    }

    @Test fun dashboardChangeImmediatelyHidesStateEvenOnTheSameVerifiedInstance() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/alpha",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.office",
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_overrides" to "+person.lise",
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/alpha", "url-key",
        ))
        assertTrue(config.adoptDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/alpha", "00112233445566778899aabbccddeeff", "url-key", "uuid-key",
        ))

        config.setHomeDashboard("/second-dash")

        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
        assertFalse(config.dashboardEntityLearningApplied)
        // Derived state re-scopes; the operator's pin does not. It cannot be regenerated by a rescan and
        // means the same thing on every dashboard of this same verified Home Assistant.
        assertEquals(mapOf("person.lise" to "pinned"), config.dashboardEntityOverrides)
        assertEquals("uuid-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/second-dash", "url-key",
        ))
        assertFalse(config.dashboardEntityFilterEnabled)
    }

    @Test fun differentHaInstanceNeverInheritsThePreviousTargetState() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha-a.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.office",
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_overrides" to "+person.lise",
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-a", config.prepareDashboardEntityInstance(
            "http://ha-a.local:8123", "/lovelace/kiosk", "url-a",
        ))
        assertTrue(config.adoptDashboardEntityInstance(
            "http://ha-a.local:8123", "/lovelace/kiosk", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "url-a", "uuid-a",
        ))

        config.setHaConnection("http://ha-b.local:8123", null)
        assertEquals("url-b", config.prepareDashboardEntityInstance(
            "http://ha-b.local:8123", "/lovelace/kiosk", "url-b",
        ))
        assertTrue(config.adoptDashboardEntityInstance(
            "http://ha-b.local:8123", "/lovelace/kiosk", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "url-b", "uuid-b",
        ))

        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
        assertFalse(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityOverrides.isEmpty())
    }

    @Test fun failedIdentityPreferenceCommitPublishesNoPartialSelection() {
        val prefs = fakePreferences(
            initial = mapOf("ha_url" to "http://ha.local:8123", "home_dashboard" to "/lovelace/kiosk"),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertEquals(null, config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))
        assertEquals("", config.dashboardEntityInstanceKey)
        assertEquals("", config.dashboardEntityTargetKey)
        assertFalse(prefs.values.containsKey("dashboard_entity_instance_origin"))
        assertFalse(prefs.values.containsKey("dashboard_entity_dashboard_path"))
    }

    @Test fun staleUuidAdoptionCannotCrossAConcurrentDashboardChange() {
        val prefs = fakePreferences(
            initial = mapOf("ha_url" to "http://ha.local:8123", "home_dashboard" to "/lovelace/alpha"),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/alpha", "url-key",
        ))

        config.setHomeDashboard("/second-dash")

        assertFalse(config.adoptDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/alpha", "00112233445566778899aabbccddeeff", "url-key", "uuid-key",
        ))
        assertEquals("url-key", config.dashboardEntityInstanceKey)
        assertEquals("", config.dashboardEntityInstanceUuid)
    }

    @Test fun manualFilterCutoverPublishesModeLatchAndListInOneCommit() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_learning" to true,
                "dashboard_entity_learning_applied" to true,
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))

        assertTrue(config.commitDashboardManualEntityFilter(
            enabled = true, entityIds = listOf("sensor.b", "light.a", "sensor.b"),
        ))

        assertFalse(config.dashboardEntityLearningEnabled)
        assertFalse(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.a", "sensor.b"), config.dashboardEntityFilterIds)
    }

    @Test fun invalidManualFilterCutoverDoesNotDisableLearning() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_learning" to true,
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))

        assertFalse(config.commitDashboardManualEntityFilter(enabled = true, entityIds = listOf(" ")))

        assertTrue(config.dashboardEntityLearningEnabled)
        assertFalse(prefs.values.containsKey("dashboard_entity_filter_enabled"))
    }

    @Test fun disablingLearningAfterTargetSwitchDoesNotClaimPreviousAllowList() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/alpha",
                "dashboard_entity_learning" to true,
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/alpha", "url-key",
        ))
        assertTrue(config.setDashboardEntityFilter(true, listOf("light.office")))

        config.setHomeDashboard("/second-dash")
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/second-dash", "url-key",
        ))
        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())

        assertTrue(config.commitDashboardEntityLearningMode(enabled = false, clearApplied = true))

        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
        assertEquals("light.office", prefs.values["dashboard_entity_filter_ids"])

        config.setHomeDashboard("/lovelace/alpha")
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/alpha", "url-key",
        ))
        assertEquals(listOf("light.office"), config.dashboardEntityFilterIds)
        assertFalse("disabled learning must not reactivate the old target's interceptor", config.dashboardEntityFilterEnabled)
    }

    @Test fun failedManualFilterCutoverPublishesNothing() {
        val target = dashboardEntityTargetKey("url-key", "/lovelace/kiosk")
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/lovelace",
                "dashboard_entity_filter_instance" to target,
                "dashboard_entity_applied_instance" to target,
                "dashboard_entity_learning" to true,
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.old",
            ),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertFalse(config.commitDashboardManualEntityFilter(true, listOf("light.new")))

        assertTrue(config.dashboardEntityLearningEnabled)
        assertTrue(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.old"), config.dashboardEntityFilterIds)
    }

    @Test fun activationPublishesFilterAndAppliedLatchAtomically() {
        val prefs = fakePreferences(
            initial = mapOf("ha_url" to "http://ha.local:8123", "home_dashboard" to "/lovelace/kiosk"),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))

        assertTrue(config.commitDashboardEntitySubscription(true, listOf("sensor.b", "light.a"), applied = true))

        assertTrue(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.a", "sensor.b"), config.dashboardEntityFilterIds)
    }

    @Test fun failedActivationCommitPublishesNeitherFilterNorAppliedLatch() {
        val target = dashboardEntityTargetKey("url-key", "/lovelace/kiosk")
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/lovelace",
                "dashboard_entity_filter_instance" to target,
                "dashboard_entity_applied_instance" to target,
                "dashboard_entity_filter_ids" to "light.old",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_learning_applied" to false,
            ),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertFalse(config.commitDashboardEntitySubscription(true, listOf("light.new"), applied = true))

        assertFalse(config.dashboardEntityLearningApplied)
        assertEquals(listOf("light.old"), config.dashboardEntityFilterIds)
    }

    @Test fun cleanSlateEvidenceResetAtomicallyClearsFilterWithoutDisablingLearning() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_learning" to true,
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))
        assertTrue(config.commitDashboardEntitySubscription(true, listOf("light.old"), applied = true))
        assertTrue(config.setDashboardEntityOverrides(mapOf("light.old" to "pinned")))

        assertTrue(config.commitDashboardEntityEvidenceReset(clearFilter = true))

        assertTrue(config.dashboardEntityLearningEnabled)
        assertFalse(config.dashboardEntityLearningApplied)
        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
        assertTrue(config.dashboardEntityOverrides.isEmpty())
    }

    @Test fun initialEntityActivationLatchSurvivesUntilSuccessButResetCannotRecreateIt() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_learning" to false,
            ),
        )
        val config = Config(prefs.instance)

        assertTrue(config.commitDashboardEntityLearningMode(enabled = true, clearApplied = true))
        assertTrue(config.dashboardEntityInitialActivationPending)
        assertTrue(config.commitDashboardEntityLearningMode(enabled = false, clearApplied = true))
        assertTrue("ordinary disable must preserve unfinished first activation", config.dashboardEntityInitialActivationPending)
        assertTrue(config.commitDashboardEntityLearningMode(enabled = true, clearApplied = true))
        assertTrue(config.dashboardEntityInitialActivationPending)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))

        assertTrue(config.commitDashboardEntityEvidenceReset(clearFilter = true))
        assertFalse(config.dashboardEntityInitialActivationPending)
        assertTrue(config.commitDashboardEntityLearningMode(enabled = false, clearApplied = true))
        assertTrue(config.commitDashboardEntityLearningMode(enabled = true, clearApplied = true))
        assertFalse("reset history must prevent first-run defaults from returning", config.dashboardEntityInitialActivationPending)

        assertTrue(config.commitDashboardEntitySubscription(true, emptyList(), applied = true))
        assertFalse(config.dashboardEntityInitialActivationPending)
    }

    @Test fun defaultEvidenceResetPreservesKnownGoodFilter() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_learning" to true,
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))
        assertTrue(config.commitDashboardEntitySubscription(true, listOf("light.old"), applied = true))

        assertTrue(config.commitDashboardEntityEvidenceReset(clearFilter = false))

        assertTrue(config.dashboardEntityLearningEnabled)
        assertFalse(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.old"), config.dashboardEntityFilterIds)
    }

    @Test fun failedCleanSlateEvidenceResetPublishesNoPartialState() {
        val target = dashboardEntityTargetKey("url-key", "/lovelace/kiosk")
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/lovelace",
                "dashboard_entity_filter_instance" to target,
                "dashboard_entity_applied_instance" to target,
                "dashboard_entity_override_instance" to target,
                "dashboard_entity_learning" to true,
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.old",
                "dashboard_entity_overrides" to "+light.old",
            ),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertFalse(config.commitDashboardEntityEvidenceReset(clearFilter = true))

        assertTrue(config.dashboardEntityLearningEnabled)
        assertTrue(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.old"), config.dashboardEntityFilterIds)
        assertEquals(mapOf("light.old" to "pinned"), config.dashboardEntityOverrides)
    }

    @Test fun failedStoreResetCanRestoreTheExactOwnerScopedEvidencePreferences() {
        val target = dashboardEntityTargetKey("url-key", "/lovelace/kiosk")
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/lovelace",
                "dashboard_entity_filter_instance" to target,
                "dashboard_entity_applied_instance" to target,
                "dashboard_entity_override_instance" to target,
                "dashboard_entity_learning" to true,
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "sensor.b\nlight.a",
                "dashboard_entity_overrides" to "+sensor.b\n-light.a",
            ),
        )
        val config = Config(prefs.instance)
        val original = config.dashboardEntityBackupState()

        assertTrue(config.commitDashboardEntityEvidenceReset(clearFilter = true))
        assertTrue(config.restoreDashboardEntityEvidencePreferences(original))

        assertEquals(original, config.dashboardEntityBackupState())
        assertTrue(config.dashboardEntityLearningApplied)
        assertTrue(config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.a", "sensor.b"), config.dashboardEntityFilterIds)
        assertEquals(
            mapOf("light.a" to "forced_exclude", "sensor.b" to "pinned"),
            config.dashboardEntityOverrides,
        )
    }

    @Test fun disabledCleanSlateResetClearsEvidencePreferencesWithoutEnablingLearning() {
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_learning" to false,
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))
        assertTrue(config.commitDashboardEntitySubscription(true, listOf("light.old"), applied = true))
        assertTrue(config.setDashboardEntityOverrides(mapOf("light.old" to "pinned")))

        assertTrue(config.commitDashboardEntityEvidenceReset(clearFilter = true))

        assertFalse(config.dashboardEntityLearningEnabled)
        assertFalse(config.dashboardEntityLearningApplied)
        assertFalse(config.dashboardEntityFilterEnabled)
        assertTrue(config.dashboardEntityFilterIds.isEmpty())
        assertTrue(config.dashboardEntityOverrides.isEmpty())
    }

    @Test fun promotionPolicyAndResultingSubscriptionPublishInOneCommit() {
        val prefs = fakePreferences(
            initial = mapOf("ha_url" to "http://ha.local:8123", "home_dashboard" to "/lovelace/kiosk"),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/lovelace/kiosk", "url-key",
        ))

        assertTrue(config.commitDashboardEntityPromotionPolicy(
            staticRefs = false,
            runtimeRefs = true,
            activeEntityIds = listOf("sensor.runtime"),
            applied = true,
        ))

        assertFalse(config.dashboardEntityAutoStatic)
        assertTrue(config.dashboardEntityAutoRuntime)
        assertTrue(config.dashboardEntityLearningApplied)
        assertEquals(listOf("sensor.runtime"), config.dashboardEntityFilterIds)
    }

    @Test fun failedPromotionPolicyCommitLeavesPolicyAndSubscriptionUntouched() {
        val target = dashboardEntityTargetKey("url-key", "/lovelace/kiosk")
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/lovelace/kiosk",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/lovelace",
                "dashboard_entity_filter_instance" to target,
                "dashboard_entity_applied_instance" to target,
                "dashboard_entity_auto_static" to true,
                "dashboard_entity_auto_runtime" to false,
                "dashboard_entity_filter_ids" to "light.old",
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_learning_applied" to true,
            ),
            commitSucceeds = false,
        )
        val config = Config(prefs.instance)

        assertFalse(config.commitDashboardEntityPromotionPolicy(
            staticRefs = false,
            runtimeRefs = true,
            activeEntityIds = listOf("light.new"),
            applied = true,
        ))

        assertTrue(config.dashboardEntityAutoStatic)
        assertFalse(config.dashboardEntityAutoRuntime)
        assertTrue(config.dashboardEntityLearningApplied)
        assertEquals(listOf("light.old"), config.dashboardEntityFilterIds)
    }

    @Test fun credentialGroupsAreReadFromOneImmutablePreferenceSnapshot() {
        val prefs = fakePreferences(initial = mapOf(
            "ha_url" to "https://ha.local",
            "ha_token" to "access",
            "ha_refresh_token" to "refresh",
            "ha_token_expiry" to 1234L,
            "ha_client_id" to "client",
        ))
        val config = Config(prefs.instance)

        assertEquals(
            HaAuthSnapshot("https://ha.local", "access", "refresh", 1234L, "client"),
            config.haAuthSnapshot(),
        )
    }

    @Test fun refreshedHaTokenCommitsOnlyWhileTheBorrowedAuthGenerationIsStillOwned() {
        val prefs = fakePreferences(initial = mapOf(
            "ha_url" to "https://ha.local",
            "ha_token" to "old-access",
            "ha_refresh_token" to "refresh",
            "ha_token_expiry" to 1234L,
            "ha_client_id" to "client",
        ))
        val config = Config(prefs.instance)
        val borrowed = config.haAuthSnapshot()

        assertTrue(config.setHaRefreshedTokenIfOwned(borrowed, "new-access", 5678L))
        assertEquals("new-access", prefs.values["ha_token"])
        assertEquals(5678L, prefs.values["ha_token_expiry"])

        config.setHaConnection("https://other.local", "admin-access")
        assertFalse(config.setHaRefreshedTokenIfOwned(borrowed, "late-access", 9999L))
        assertEquals("admin-access", prefs.values["ha_token"])
        assertEquals("https://other.local", prefs.values["ha_url"])
        assertEquals(5678L, prefs.values["ha_token_expiry"])
    }

    @Test fun newestBrowserLoginSupersedesAnAlreadyClaimedAttempt() {
        val config = Config(fakePreferences(initial = mapOf("ha_url" to "https://ha.local")).instance)

        val first = config.beginHaOAuthAttempt()
        assertTrue(config.isHaOAuthAttemptCurrent(first.epoch))
        val second = config.beginHaOAuthAttempt()

        assertFalse(config.isHaOAuthAttemptCurrent(first.epoch))
        assertTrue(config.isHaOAuthAttemptCurrent(second.epoch))
        assertEquals(first.owner, second.owner)
    }

    @Test fun separateConfigWrappersShareOneProcessWideTransactionLock() {
        val prefs = fakePreferences(initial = mapOf("ha_url" to "https://ha.local"))
        val first = Config(prefs.instance)
        val second = Config(prefs.instance)
        val writerStarted = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        lateinit var writer: Thread

        first.synchronizedTransaction {
            writer = Thread {
                writerStarted.countDown()
                second.setHaConnection("https://other.local", "new-access")
                writerFinished.countDown()
            }.also(Thread::start)
            assertTrue(writerStarted.await(2, TimeUnit.SECONDS))
            assertFalse("a second wrapper must not enter during the transaction", writerFinished.await(100, TimeUnit.MILLISECONDS))
            assertEquals("https://ha.local", first.haAuthSnapshot().url)
        }

        assertTrue(writerFinished.await(2, TimeUnit.SECONDS))
        writer.join(2_000)
        assertEquals("https://other.local", first.haAuthSnapshot().url)
    }

    @Test fun ownerScopedEntityBackupStateRoundTripsInOneEditorCommit() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        val state = DashboardEntityBackupState(
            instanceKey = "uuid-key",
            instanceOrigin = "https://ha.local",
            instanceUuid = "core-uuid",
            dashboardPath = "/lovelace/wall",
            filterIds = "light.one\nsensor.two",
            filterEnabled = true,
            filterOwner = "uuid-key|/lovelace/wall",
            learningApplied = true,
            appliedOwner = "uuid-key|/lovelace/wall",
            overrides = "+light.one\n-sensor.two",
            overrideOwner = "uuid-key|/lovelace/wall",
            initialActivationPending = true,
        )
        val editor = config.editor()
        config.stageDashboardEntityBackupState(editor, state)
        assertTrue(editor.commit())

        assertEquals(state, config.dashboardEntityBackupState())
    }

    @Test fun rawRegistryCommitValidatesAndDurablyPersistsOneValue() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        val zoom = requireNotNull(SettingsRegistry.spec("dashboard_zoom"))

        assertTrue(config.commitRaw(zoom, "125"))
        assertEquals(125, prefs.values["dashboard_zoom"])
        assertFalse(config.commitRaw(zoom, "not-a-number"))
        assertEquals(125, prefs.values["dashboard_zoom"])
    }

    @Test fun rawWakeOnWaveCommitInvalidatesAlreadyAdmittedGestureWork() {
        val config = Config(fakePreferences().instance)
        val wakeOnWave = requireNotNull(SettingsRegistry.spec("wake_on_wave"))
        val initial = config.wakeOnWaveGeneration

        assertTrue(config.commitRaw(wakeOnWave, "false"))

        assertFalse(config.wakeOnWave)
        assertTrue(config.wakeOnWaveGeneration > initial)
    }

    @Test fun rawHomeDashboardCommitPreservesItsOwnerScopedPathDependency() {
        val prefs = fakePreferences(
            mutableMapOf(
                "home_dashboard" to "/lovelace/old",
                "dashboard_entity_dashboard_path" to "/lovelace",
            ),
        )
        val config = Config(prefs.instance)
        val home = requireNotNull(SettingsRegistry.spec("home_dashboard"))

        // Another view of the same dashboard: the route moves, the learning scope does not.
        assertTrue(config.commitRaw(home, "/lovelace/new"))

        assertEquals("/lovelace/new", prefs.values["home_dashboard"])
        assertEquals("/lovelace", prefs.values["dashboard_entity_dashboard_path"])

        // A different dashboard still re-points the scope, which is the dependency this pins.
        assertTrue(config.commitRaw(home, "/other-dash/desk"))

        assertEquals("/other-dash/desk", prefs.values["home_dashboard"])
        assertEquals("/other-dash", prefs.values["dashboard_entity_dashboard_path"])
    }

    /** An owned, enabled, learned filter scoped to [path] — the state a re-scope has to invalidate. */
    private fun learnedFilterAt(path: String) = fakePreferences(
        initial = mapOf(
            "ha_url" to "http://ha.local:8123",
            "home_dashboard" to path,
            "dashboard_entity_instance" to "url-key",
            "dashboard_entity_instance_origin" to "http://ha.local:8123",
            "dashboard_entity_dashboard_path" to dashboardEntityScopePath(path),
            "dashboard_entity_filter_instance" to dashboardEntityTargetKey("url-key", path),
            "dashboard_entity_learning" to true,
            "dashboard_entity_learning_applied" to true,
            "dashboard_entity_filter_enabled" to true,
            "dashboard_entity_filter_ids" to "light.hall\nlight.porch",
        ),
    )

    @Test fun aDashboardChangeKeepsPinsAndExclusionsButAnInstanceChangeWithdrawsThem() {
        // Pins and forced exclusions share one preference and one gate. Both are the operator's work, so
        // neither may be withdrawn by moving between dashboards of the same Home Assistant. An exclusion
        // is the quieter of the two to lose: a dropped pin shows up as a card that stops updating, while
        // a dropped exclusion silently re-subscribes to something removed on purpose.
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "/alpha/music",
                "dashboard_entity_overrides" to "+person.lise\n-sensor.chatty",
            ),
        )
        val config = Config(prefs.instance)
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/alpha/music", "url-key",
        ))
        assertTrue(config.setDashboardEntityOverrides(
            mapOf("person.lise" to "pinned", "sensor.chatty" to "forced_exclude"),
        ))

        // Another dashboard entirely, on the same Home Assistant.
        config.setHomeDashboard("/beta-dash")
        assertEquals(
            mapOf("person.lise" to "pinned", "sensor.chatty" to "forced_exclude"),
            config.dashboardEntityOverrides,
        )

        // Following the account default is not a dashboard the operator named at all, and must not
        // withdraw their work either — this is the case that lost real pins on hardware.
        config.setHomeDashboard("")
        assertEquals(
            mapOf("person.lise" to "pinned", "sensor.chatty" to "forced_exclude"),
            config.dashboardEntityOverrides,
        )

        // A different Home Assistant IS a boundary: an entity id only means something inside one
        // instance, so the same string there is an unrelated entity.
        config.setHaConnection("http://other-ha.local:8123", null)
        assertTrue(config.dashboardEntityOverrides.isEmpty())
    }

    @Test fun operatorOverridesAreWithdrawnWhenTheOriginMovesUnderAMatchingInstanceKey() {
        // The instance KEY alone is not proof of the same Home Assistant: an origin change falls back to
        // a URL-derived key, and adoption can carry one key across origins while mDNS decides whether they
        // are aliases. So the origin is checked independently — otherwise a pin could survive onto a
        // different instance where the same entity id means something else entirely.
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://new-ha.local:8123",
                "home_dashboard" to "/alpha",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://old-ha.local:8123",
                "dashboard_entity_override_instance" to dashboardEntityTargetKey("url-key", "/alpha"),
                "dashboard_entity_overrides" to "+person.lise\n-sensor.chatty",
            ),
        )
        val config = Config(prefs.instance)

        assertTrue(
            "a matching instance key must not admit overrides recorded against another origin",
            config.dashboardEntityOverrides.isEmpty(),
        )
    }

    @Test fun followingTheAccountDefaultKeepsTheLearnedFilterItAlreadyOwns() {
        // Reported from a panel: the home dashboard moved from a view of /alpha to "Account default",
        // which resolves to /alpha, and the learned filter was discarded. Auto names no dashboard of
        // its own, so it must not act as a scope change on the way in.
        val prefs = learnedFilterAt("/alpha")
        val config = Config(prefs.instance)
        config.setHomeDashboard("/alpha/music")
        assertTrue("the filter must start owned or this proves nothing", config.dashboardEntityFilterEnabled)

        config.setHomeDashboard("")

        assertEquals("", prefs.values["home_dashboard"])
        assertEquals("/alpha", prefs.values["dashboard_entity_dashboard_path"])
        assertTrue("following the account default must not re-scope learning", config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.hall", "light.porch"), config.dashboardEntityFilterIds)
    }

    @Test fun theAccountDefaultDoesNotInheritAnotherDashboardsLearnedFilter() {
        // The risk that ruled out simply retaining the scope: if the account default resolves to a
        // DIFFERENT dashboard, keeping the old learned list would filter out entities the new dashboard
        // renders and its cards would quietly stop updating. Rebinding to the resolved dashboard — which
        // is what the scan does once an authenticated read answers — must withdraw the old list.
        val prefs = learnedFilterAt("/alpha")
        val config = Config(prefs.instance)
        config.setHomeDashboard("")
        assertTrue(config.dashboardEntityFilterEnabled)

        // Standing in for the scan's reconciliation: the resolution named a different dashboard.
        assertEquals("url-key", config.prepareDashboardEntityInstance(
            "http://ha.local:8123", "/beta-dash", "url-key",
        ))

        assertEquals("/beta-dash", prefs.values["dashboard_entity_dashboard_path"])
        assertFalse("a different resolved dashboard must not inherit the filter", config.dashboardEntityFilterEnabled)
        assertEquals(emptyList<String>(), config.dashboardEntityFilterIds)
    }

    @Test fun anAmbiguouslyScopedBlankDashboardStillRebootstraps() {
        // The case the default-resolver invalidation was written for, and which must keep working: the
        // panel is following the account default with NO established dashboard, so its allow-list may
        // have been derived while a blank value resolved as ordinary Lovelace and could describe a
        // different panel than the frontend renders. That state is still discarded and rescanned.
        val prefs = fakePreferences(
            initial = mapOf(
                "ha_url" to "http://ha.local:8123",
                "home_dashboard" to "",
                "dashboard_entity_instance" to "url-key",
                "dashboard_entity_instance_origin" to "http://ha.local:8123",
                "dashboard_entity_dashboard_path" to "/",
                "dashboard_entity_filter_instance" to dashboardEntityTargetKey("url-key", "/"),
                "dashboard_entity_learning" to true,
                "dashboard_entity_learning_applied" to true,
                "dashboard_entity_filter_enabled" to true,
                "dashboard_entity_filter_ids" to "light.suspect",
            ),
        )
        val config = Config(prefs.instance)

        assertFalse(
            "an allow-list from an ambiguous blank resolution must not stay active",
            config.dashboardEntityFilterEnabled,
        )
        assertEquals(
            DashboardEntityDefaultResolverMigration.REBOOTSTRAP,
            config.migrateDashboardEntityDefaultResolver(),
        )
    }

    @Test fun anEstablishedDashboardIsNotRebootstrappedJustForFollowingTheDefault() {
        // The other side of the same boundary: this panel's list was learned for a named dashboard, so
        // it was never derived under the ambiguous blank resolution and there is nothing to invalidate.
        val prefs = learnedFilterAt("/alpha")
        val config = Config(prefs.instance)
        config.setHomeDashboard("")

        assertTrue("an established scope must not be rebootstrapped", config.dashboardEntityFilterEnabled)
        assertEquals(
            DashboardEntityDefaultResolverMigration.NOT_NEEDED,
            config.migrateDashboardEntityDefaultResolver(),
        )
    }

    @Test fun choosingAViewOfTheSameDashboardKeepsItsLearnedFilter() {
        // Learning fetches `lovelace/config` for the dashboard ROOT and extracts entities from the whole
        // document, so every view of one dashboard yields the same learned set. Re-scoping on a view
        // change would throw away a filter a rescan reproduces byte for byte, and leave the panel
        // unfiltered while it re-learns — the exact cost that choosing a specific view exists to avoid.
        val prefs = learnedFilterAt("/dashboard-test")
        val config = Config(prefs.instance)
        assertTrue("the filter must start owned or this proves nothing", config.dashboardEntityFilterEnabled)

        config.setHomeDashboard("/dashboard-test/alpha")

        assertEquals("/dashboard-test/alpha", prefs.values["home_dashboard"])
        assertEquals("/dashboard-test", prefs.values["dashboard_entity_dashboard_path"])
        assertTrue("a view change must not re-scope learning", config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.hall", "light.porch"), config.dashboardEntityFilterIds)
    }

    @Test fun choosingADifferentDashboardStillRescopesEntityLearning() {
        // The other half of the same rule: a different dashboard really is a different document, so its
        // predecessor's learned list must not be inherited.
        val prefs = learnedFilterAt("/dashboard-test")
        val config = Config(prefs.instance)
        assertTrue(config.dashboardEntityFilterEnabled)

        config.setHomeDashboard("/other-dash/alpha")

        assertEquals("/other-dash", prefs.values["dashboard_entity_dashboard_path"])
        assertFalse("a different dashboard must not inherit the filter", config.dashboardEntityFilterEnabled)
        assertEquals(emptyList<String>(), config.dashboardEntityFilterIds)
    }

    @Test fun retargetingWithinOneViewKeepsTheLearnedFilterItAlreadyOwns() {
        // Query and fragment do not change WHICH cards render, so adding them must not discard a
        // learned list and force a rescan — the entity-path owner deliberately ignores both.
        val prefs = learnedFilterAt("/dashboard-test")
        val config = Config(prefs.instance)

        config.setHomeDashboard("/dashboard-test/alpha?kiosk=1#main")

        assertEquals("/dashboard-test/alpha?kiosk=1#main", prefs.values["home_dashboard"])
        assertEquals("/dashboard-test", prefs.values["dashboard_entity_dashboard_path"])
        assertTrue("a query-only change must not re-scope learning", config.dashboardEntityFilterEnabled)
        assertEquals(listOf("light.hall", "light.porch"), config.dashboardEntityFilterIds)
    }

    @Test fun completedInstallGetsTheVersionedHomeDashboardMigrationAfterV1WasConsumed() {
        val prefs = fakePreferences(
            initial = mapOf(
                "device_local_setup_questions_migrated_v1" to true,
                "device_local_setup_identity_confirmed" to true,
                "device_local_setup_ever_completed" to true,
            ),
        )
        val config = Config(prefs.instance)

        config.migrateSetupQuestionsForExistingInstall()

        assertTrue(config.setupHomeDashboardChosen)
        assertEquals(true, prefs.values["device_local_setup_home_dashboard_migrated_v2"])
    }

    @Test fun freshGuidedSetupKeepsTheHomeDashboardQuestionOpen() {
        val prefs = fakePreferences(
            initial = mapOf(
                "device_local_setup_questions_migrated_v1" to true,
                "device_local_setup_identity_confirmed" to true,
            ),
        )
        val config = Config(prefs.instance)

        config.migrateSetupQuestionsForExistingInstall()

        assertFalse(config.setupHomeDashboardChosen)
        assertEquals(true, prefs.values["device_local_setup_home_dashboard_migrated_v2"])
    }

    @Test fun freshConfigTakesTcpDefaultWithoutMaterialisingAnOldTransport() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)

        config.migrateLogShipTcpDefault()

        assertEquals("syslog-tcp", config.logShipProtocol)
        assertFalse(prefs.values.containsKey("log_ship_protocol"))
        assertEquals(true, prefs.values["device_local_log_ship_tcp_default_migrated_v1"])
    }

    @Test fun upgradedAbsentProtocolKeepsItsPreviousEffectiveUdpTransport() {
        val prefs = fakePreferences(initial = mapOf("panel_id" to "existing-panel"))
        val config = Config(prefs.instance)

        config.migrateLogShipTcpDefault()
        config.migrateLogShipTcpDefault()

        assertEquals("syslog-udp", config.logShipProtocol)
        assertEquals("syslog-udp", prefs.values["log_ship_protocol"])
    }

    @Test fun migrationNeverRewritesExplicitLogShipProtocols() {
        listOf("syslog-udp", "syslog-tcp", "http", "syslog").forEach { protocol ->
            val prefs = fakePreferences(initial = mapOf("panel_id" to "existing", "log_ship_protocol" to protocol))
            val config = Config(prefs.instance)

            config.migrateLogShipTcpDefault()

            assertEquals(protocol, prefs.values["log_ship_protocol"])
            assertEquals(if (protocol == "syslog") "syslog-tcp" else protocol, config.logShipProtocol)
        }
    }

    @Test fun failedDefaultMigrationKeepsUdpInMemoryThenRetriesDurablyAndIdempotently() {
        val prefs = fakePreferences(initial = mapOf("panel_id" to "existing"), commitSucceeds = false)
        val config = Config(prefs.instance)

        config.migrateLogShipTcpDefault()

        assertFalse(prefs.values.containsKey("device_local_log_ship_tcp_default_migrated_v1"))
        assertFalse(prefs.values.containsKey("log_ship_protocol"))
        assertEquals("syslog-udp", config.logShipProtocol)

        prefs.commitsSucceed.set(true)
        config.migrateLogShipTcpDefault()
        assertEquals(true, prefs.values["device_local_log_ship_tcp_default_migrated_v1"])
        assertEquals("syslog-udp", prefs.values["log_ship_protocol"])
        assertEquals("syslog-udp", config.logShipProtocol)

        val afterRetry = prefs.values.toMap()
        config.migrateLogShipTcpDefault()
        assertEquals(afterRetry, prefs.values)
    }

    private fun profile(
        id: String,
        revision: String = "revision-a",
        hasButtonBacklight: Boolean = false,
    ): DeviceProfile = fakeProfile(
        id = id,
        revision = revision,
        displayName = id,
        hasButtonBacklight = hasButtonBacklight,
    )

    // ---- log-sink address consistency (0.9.7) ----------------------------------------------------

    private val desyncPrefs = mapOf(
        "log_ship_host" to "old.lan",
        "log_ship_port" to 514,
        "log_ship_protocol" to "syslog-tcp",
    )

    /** Stage a bundle import exactly as applyAccepted does: per-key first, then the dependency pass. */
    private fun stageImport(config: Config, editor: SharedPreferences.Editor, accepted: Map<String, String>) {
        accepted.forEach { (key, value) ->
            SettingsRegistry.spec(key)?.let { spec -> config.stage(editor, spec, value) }
        }
        config.stageImportDependencies(editor, accepted)
    }

    @Test fun anImportedAutoSleepSourceSurvivesTheAutoSleepWriteStagedAfterIt() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        val editor = config.editor()

        stageImport(config, editor, linkedMapOf("auto_sleep_source" to "home_assistant", "auto_sleep" to "true"))
        assertTrue(config.commit(editor))

        assertEquals("home_assistant", Config(prefs.instance).autoSleepSource)
    }

    @Test fun anImportedEmbeddedSinkAddressRewritesAllThreeFieldsInOneTransaction() {
        // Without the dependency pass the host is stored verbatim and Port/Protocol keep describing
        // somewhere else, so the panel ships to one destination while every surface reports another.
        val prefs = fakePreferences(initial = desyncPrefs)
        val config = Config(prefs.instance)
        val editor = config.editor()

        stageImport(config, editor, mapOf("log_ship_host" to "udp://collector.lan:1514"))
        assertTrue(config.commit(editor))

        assertEquals("collector.lan", prefs.values["log_ship_host"])
        assertEquals(1514, prefs.values["log_ship_port"])
        assertEquals("syslog-udp", prefs.values["log_ship_protocol"])
        assertEquals("collector.lan", config.logShipHost)
        assertEquals(1514, config.logShipPort)
        assertEquals("syslog-udp", config.logShipProtocol)
    }

    @Test fun theStagedSinkTripleDoesNotDependOnAcceptedIterationOrder() {
        // Same three entries in both directions; the committed destination must be identical.
        val entries = listOf(
            "log_ship_host" to "udp://collector.lan:1514",
            "log_ship_port" to "514",
            "log_ship_protocol" to "syslog-tcp",
        )
        val committed = listOf(entries, entries.reversed()).map { ordering ->
            val prefs = fakePreferences(initial = desyncPrefs)
            val config = Config(prefs.instance)
            val editor = config.editor()
            stageImport(config, editor, linkedMapOf(*ordering.toTypedArray()))
            assertTrue(config.commit(editor))
            Triple(
                prefs.values["log_ship_host"], prefs.values["log_ship_port"], prefs.values["log_ship_protocol"],
            )
        }
        assertEquals(committed[0], committed[1])
        assertEquals(Triple("collector.lan", 1514, "syslog-udp"), committed[0])
    }

    @Test fun aFailedCommitLeavesTheSinkTripleAtItsPreviousConsistentDestination() {
        // Partial admission must recover atomically: the three fields are staged into one editor, so a
        // failed commit writes none of them rather than leaving a host that disagrees with its port.
        val prefs = fakePreferences(initial = desyncPrefs, commitSucceeds = false)
        val config = Config(prefs.instance)
        val editor = config.editor()

        stageImport(config, editor, mapOf("log_ship_host" to "udp://collector.lan:1514"))
        assertFalse(config.commit(editor))

        assertEquals("old.lan", prefs.values["log_ship_host"])
        assertEquals(514, prefs.values["log_ship_port"])
        assertEquals("syslog-tcp", prefs.values["log_ship_protocol"])
        assertEquals("old.lan", config.logShipHost)
        assertEquals(514, config.logShipPort)
        assertEquals("syslog-tcp", config.logShipProtocol)
    }
}
