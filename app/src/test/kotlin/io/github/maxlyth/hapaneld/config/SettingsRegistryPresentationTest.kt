package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRegistryPresentationTest {
    @Test fun interfaceLanguageIsBasicDeviceLocalAndDoesNotApplyLive() {
        val spec = requireNotNull(SettingsRegistry.spec("ui_language"))

        assertEquals("System", spec.group)
        assertEquals("Interface language", spec.label)
        assertEquals(SettingsRegistry.DEFAULT_UI_LANGUAGE, spec.default)
        assertEquals(SettingsRegistry.UI_LANGUAGES, spec.options)
        assertEquals(Tier.BASIC, spec.tier)
        assertEquals(Scope.DEVICE, spec.scope)
        assertFalse(spec.liveApply)
        SettingsRegistry.UI_LANGUAGES.forEach { language ->
            assertEquals(language, (SettingValue.validate(spec, language) as Validation.Ok).normalized)
        }
        assertTrue(SettingValue.validate(spec, "pt-BR") is Validation.Bad)
        assertEquals(
            "Language used by ha-paneld's own interface. Automatic uses an explicit page override first. " +
                "Configure setting labels and help can then follow the connected Home Assistant user's language; " +
                "browser, device and English are the remaining fallbacks.",
            spec.help,
        )
    }

    @Test fun keepPanelResponsiveExplainsScreenOffBehaviorAndDefaultsOn() {
        val spec = SettingsRegistry.spec("keep_awake")!!
        assertEquals("Keep panel responsive", spec.label)
        assertEquals("true", spec.default)
        assertEquals(
            "Keep the network and background services running while the screen is off.",
            spec.help,
        )
    }

    @Test fun networkAdbIsLastSystemSettingAndRetainsSafeDefaults() {
        val spec = SettingsRegistry.spec("network_adb")!!
        assertEquals("network_adb", SettingsRegistry.SPECS.last { it.group == "System" }.key)
        assertEquals("false", spec.default)
        assertEquals(Tier.ADVANCED, spec.tier)
        assertFalse(spec.haExposedByDefault)
        assertEquals(
            "Security risk: keeps classic ADB listening on TCP port 5555 across boots and reconnects. " +
                "Enable only during active maintenance on a trusted network. If ADB was enabled outside " +
                "ha-paneld, it must also be disabled there.",
            spec.help,
        )
    }

    @Test fun launcherHelpExplainsAutomaticAndEnforcedHomeModes() {
        val help = SettingsRegistry.spec("launcher_package")!!.help
        assertTrue(help.contains("Blank auto-picks"))
        assertTrue(help.contains("Panel admin (ha-paneld)"))
        assertTrue(help.contains("Android Home app"))
    }

    @Test fun logShippingHelpNamesTheOnlySourceThatLeavesThePanel() {
        assertEquals(
            "Forward ha-paneld's own process log — not the full system log — to a central sink " +
                "(LAN-only, tokens and passwords redacted).",
            SettingsRegistry.spec("log_ship_enabled")!!.help,
        )
    }

    @Test fun navbarModeIsAHomeAssistantSelectWithAConfigureSyncControl() {
        val spec = SettingsRegistry.spec("navbar_mode")!!
        assertFalse(spec.haExposedByDefault)
        assertEquals("select", spec.ha!!.component)
        assertEquals("navbar", spec.ha!!.objectSuffix)
        // The body carries the placeholder, not a literal list: the choices are capability-filtered per
        // panel. What actually reaches Home Assistant is pinned byte-for-byte in DiscoveryParityTest.
        assertTrue(spec.ha!!.body.contains("\"options\":{options}"))
        assertEquals(listOf("Off", "Always on", "Swipe reveal"), spec.optionsFor(Capabilities()))
        assertEquals(
            listOf("Off", "Always on", "Swipe reveal", "Native"),
            spec.optionsFor(Capabilities(hasNativeNavbar = true)),
        )
    }

    @Test fun lastBootTimeRenamesPresentationWithoutChangingIdentity() {
        val spec = SettingsRegistry.spec("diag_boot")!!
        assertEquals("Last boot time", spec.label)
        assertEquals("sensor", spec.ha!!.component)
        assertEquals("diag_boot", spec.ha!!.objectSuffix)
        assertEquals("Last boot time", spec.ha!!.name)
        assertTrue(spec.ha!!.body.contains("\"device_class\":\"timestamp\""))
        assertTrue(spec.ha!!.readOnly)
    }

    @Test fun sensorsCardImmediatelyPrecedesDiagnosticsAndContainsOnlySensorReadings() {
        val groups = SettingsRegistry.SPECS.map { it.group }.distinct()
        assertTrue(groups.indexOf("Sensors") < groups.indexOf("Diagnostics"))
        assertEquals(
            listOf("screen", "volume", "illuminance", "proximity", "proximity_level", "auto_sleep_activity", "temperature", "humidity", "room_temp", "room_humidity", "room_temp_offset"),
            SettingsRegistry.SPECS.filter { it.group == "Sensors" }.map { it.key },
        )
        val screen = SettingsRegistry.spec("screen")!!
        val volume = SettingsRegistry.spec("volume")!!
        assertTrue(screen.readOnly)
        assertTrue(volume.readOnly)
        assertTrue(screen.haExposedByDefault)
        assertTrue(volume.haExposedByDefault)
        assertEquals("light", screen.ha!!.component)
        assertEquals("screen", screen.ha!!.objectSuffix)
        assertEquals("number", volume.ha!!.component)
        assertEquals("volume", volume.ha!!.objectSuffix)
        assertTrue(SettingsRegistry.spec("illuminance")!!.availableWhen(Capabilities(hasLight = true)))
        assertFalse(SettingsRegistry.spec("illuminance")!!.availableWhen(Capabilities()))
        assertTrue(SettingsRegistry.spec("proximity")!!.availableWhen(Capabilities(hasLearnedProximity = true)))
        assertFalse(SettingsRegistry.spec("proximity")!!.availableWhen(Capabilities(hasProximity = true)))
        listOf(
            "volume", "illuminance", "proximity", "proximity_level", "temperature", "humidity",
            "room_temp", "room_humidity",
        ).forEach {
            assertTrue(SettingsRegistry.spec(it)!!.haExposedByDefault)
            assertTrue(SettingsRegistry.spec(it)!!.ha!!.readOnly)
        }
        assertFalse(SettingsRegistry.spec("auto_sleep_activity")!!.haExposedByDefault)
        assertTrue(SettingsRegistry.spec("auto_sleep_activity")!!.ha!!.readOnly)
    }

    @Test fun onlySensorsCardEntitiesSyncToHomeAssistantByDefault() {
        SettingsRegistry.SPECS.filter { it.ha != null }.forEach { spec ->
            assertEquals(
                "unexpected HA sync default for ${spec.key}",
                spec.group == "Sensors" && spec.key !in setOf("auto_sleep_activity"),
                spec.haExposedByDefault,
            )
        }
    }

    @Test fun proximitySetupAndTelemetryFollowSeparateSourceAndLearningCapabilities() {
        val wake = SettingsRegistry.spec("wake_on_wave")!!
        val proximity = SettingsRegistry.spec("proximity")!!
        val level = SettingsRegistry.spec("proximity_level")!!

        val absent = Capabilities()
        assertFalse(wake.availableWhen(absent))
        assertFalse(proximity.availableWhen(absent))
        assertFalse(level.availableWhen(absent))

        val sourcePresentButUnlearned = Capabilities(hasProximity = true)
        assertTrue(wake.availableWhen(sourcePresentButUnlearned))
        assertFalse(proximity.availableWhen(sourcePresentButUnlearned))
        assertFalse(level.availableWhen(sourcePresentButUnlearned))

        val learned = Capabilities(hasProximity = true, hasLearnedProximity = true)
        assertTrue(wake.availableWhen(learned))
        assertTrue(proximity.availableWhen(learned))
        assertTrue(level.availableWhen(learned))

        val learnedLossWithSourceRemaining = Capabilities(hasProximity = true, hasLearnedProximity = false)
        assertTrue(wake.availableWhen(learnedLossWithSourceRemaining))
        assertFalse(proximity.availableWhen(learnedLossWithSourceRemaining))
        assertFalse(level.availableWhen(learnedLossWithSourceRemaining))
    }

    @Test fun wifiReadingsRemainOptInDiagnosticsAndUseNativeSignalUnits() {
        val ssid = SettingsRegistry.spec("diag_wifi_ssid")!!
        val rssi = SettingsRegistry.spec("diag_wifi_rssi")!!
        assertEquals("Diagnostics", ssid.group)
        assertEquals("Diagnostics", rssi.group)
        assertFalse(ssid.haExposedByDefault)
        assertFalse(rssi.haExposedByDefault)
        assertFalse(ssid.availableWhen(Capabilities()))
        assertFalse(ssid.availableWhen(Capabilities(hasWifi = true)))
        assertTrue(ssid.availableWhen(Capabilities(hasWifi = true, hasWifiSsid = true)))
        assertTrue(rssi.availableWhen(Capabilities(hasWifi = true)))
        assertTrue(rssi.ha!!.body.contains("\"device_class\":\"signal_strength\""))
        assertTrue(rssi.ha!!.body.contains("\"unit_of_measurement\":\"dBm\""))
        assertTrue(rssi.ha!!.body.contains("\"entity_category\":\"diagnostic\""))
    }

    /** Outage counters follow the Wi-Fi diagnostics shape: opt-in, capability-gated, diagnostic. */
    @Test fun wifiOutageCountersRemainOptInDiagnosticsGatedOnWifi() {
        for (key in listOf("diag_wifi_outages_24h")) {
            val spec = SettingsRegistry.spec(key)!!
            assertEquals("Diagnostics", spec.group)
            assertFalse(spec.haExposedByDefault)
            assertFalse(spec.availableWhen(Capabilities()))
            assertTrue(spec.availableWhen(Capabilities(hasWifi = true)))
            assertTrue(spec.ha!!.readOnly)
            assertTrue(spec.ha!!.body.contains("\"entity_category\":\"diagnostic\""))
        }
    }
}
