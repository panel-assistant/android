package io.github.maxlyth.hapaneld.config

import io.github.maxlyth.hapaneld.control.CpuController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden tests: the registry-driven discovery builder must preserve legacy payloads except for
 * descriptor-level policy fields that are asserted explicitly below.
 */
class DiscoveryParityTest {
    @Test fun silenceBootChimeDefaultHasOneAuthority() {
        assertTrue(SettingsRegistry.DEFAULT_SILENCE_BOOT_CHIME)
        assertEquals(
            SettingsRegistry.DEFAULT_SILENCE_BOOT_CHIME.toString(),
            SettingsRegistry.spec("silence_boot_chime")?.default,
        )
        // Source-text reason: the published OpenAPI default is the settings wire contract.
        val openApi = sequenceOf(
            java.io.File("src/main/assets/openapi.json"),
            java.io.File("app/src/main/assets/openapi.json"),
        ).first { it.isFile }.readText()
        assertTrue(openApi.contains(
            "\"silence_boot_chime\": { \"type\": \"boolean\", \"default\": true",
        ))
    }

    @Test fun cpuGovernorIsExplicitlyLiveOnly() {
        assertTrue(SettingsRegistry.spec("cpu_governor")?.transient == true)
    }

    @Test fun cpuGovernorDiscoveryOptionsStayAlignedWithRuntimeTiers() {
        assertEquals(CpuController.TIERS, SettingsRegistry.spec("cpu_governor")?.options)
    }

    private val panel = "test"
    private val avail =
        """"availability_topic":"ha-paneld/test/availability","payload_available":"online","payload_not_available":"offline""""
    private val device =
        """"device":{"identifiers":["ha-paneld-test"],"name":"Test Panel","manufacturer":"Sonoff","model":"NSPanel Pro","sw_version":"9.9.9","hw_version":"hw","serial_number":"abc"}"""

    private fun build(key: String): String =
        SettingsRegistry.spec(key)!!.ha!!.buildDiscoveryJson(panel, avail, device)

    /** Publishes exactly as [MqttBridge] does, via the spec's own options authority. */
    private fun buildFor(key: String, caps: Capabilities): String =
        SettingsRegistry.spec(key)!!.let {
            it.ha!!.buildDiscoveryJson(panel, avail, device, optionsJson = it.discoveryOptionsJson(caps))
        }

    @Test fun wakeOnWaveMatchesLegacy() {
        assertEquals(
            """{"name":"Wake on wave","object_id":"test_wake_on_wave","unique_id":"test_wake_on_wave","command_topic":"ha-paneld/test/wake_on_wave/set","state_topic":"ha-paneld/test/wake_on_wave/state","icon":"mdi:gesture-tap","entity_category":"config",$avail,$device}""",
            build("wake_on_wave"),
        )
    }

    @Test fun autoBrightnessMatchesLegacy() {
        assertEquals(
            """{"name":"Auto-brightness","object_id":"test_auto_brightness","unique_id":"test_auto_brightness","command_topic":"ha-paneld/test/auto_brightness/set","state_topic":"ha-paneld/test/auto_brightness/state","icon":"mdi:brightness-auto","entity_category":"config",$avail,$device}""",
            build("auto_brightness"),
        )
    }

    /** A panel with no native bar publishes exactly the legacy three-choice payload, byte for byte. */
    @Test fun navbarModeMatchesLegacySelect() {
        assertEquals(
            """{"name":"Navbar","object_id":"test_navbar","unique_id":"test_navbar","command_topic":"ha-paneld/test/navbar/set","state_topic":"ha-paneld/test/navbar/state","options":["Off","Always on","Swipe reveal"],"icon":"mdi:gesture-tap-button","entity_category":"config",$avail,$device}""",
            buildFor("navbar_mode", Capabilities()),
        )
    }

    @Test fun navbarModeOffersNativeOnlyWhereTheProfileDeclaresANativeBar() {
        assertEquals(
            """{"name":"Navbar","object_id":"test_navbar","unique_id":"test_navbar","command_topic":"ha-paneld/test/navbar/set","state_topic":"ha-paneld/test/navbar/state","options":["Off","Always on","Swipe reveal","Native"],"icon":"mdi:gesture-tap-button","entity_category":"config",$avail,$device}""",
            buildFor("navbar_mode", Capabilities(hasNativeNavbar = true)),
        )
    }

    /** A missing snapshot must still emit a well-formed array, never `"options":,`. */
    @Test fun navbarModeDiscoveryNeverEmitsAnEmptyOptionsFragment() {
        val payload = buildFor("navbar_mode", Capabilities())
        assertFalse(payload.contains("\"options\":,"))
        assertTrue(payload.contains("\"options\":[\"Off\","))
    }

    @Test fun ambientLightSensorMatchesLegacyDiscovery() {
        assertEquals(
            """{"name":"Illuminance","object_id":"test_illuminance","unique_id":"test_illuminance","state_topic":"ha-paneld/test/illuminance/state","device_class":"illuminance","unit_of_measurement":"lx","state_class":"measurement","force_update":true,$avail,$device}""",
            build("illuminance"),
        )
    }

    @Test fun forceUpdateIsLimitedToTheBoundedMeasurementRefreshSet() {
        val expected = setOf(
            "illuminance", "proximity_level", "temperature", "humidity",
            "diag_cpu", "diag_memory", "diag_soc_temp", "diag_wifi_rssi",
            "diag_wifi_outages_24h", "room_temp", "room_humidity",
        )
        val periodic = SettingsRegistry.haCapable().filter { it.ha!!.periodicRefresh }

        assertEquals(expected, periodic.mapTo(linkedSetOf()) { it.key })
        periodic.forEach { spec ->
            assertTrue(build(spec.key).contains("\"force_update\":true"))
        }
        SettingsRegistry.haCapable().filterNot { it.ha!!.periodicRefresh }.forEach { spec ->
            assertFalse("${spec.key} must stay change-only", build(spec.key).contains("\"force_update\""))
        }
    }

    @Test fun physicalSensorDiscoveryComesFromRegistryDescriptors() {
        val proximityAvail =
            """"availability":[{"topic":"ha-paneld/test/availability","payload_available":"online","payload_not_available":"offline"},{"topic":"ha-paneld/test/proximity/availability","payload_available":"online","payload_not_available":"offline"}],"availability_mode":"all""""
        val expected = mapOf(
            "proximity" to
                """{"name":"Proximity","object_id":"test_proximity","unique_id":"test_proximity","state_topic":"ha-paneld/test/proximity/state","device_class":"occupancy","payload_on":"ON","payload_off":"OFF",$proximityAvail,$device}""",
            "proximity_level" to
                """{"name":"Proximity level","object_id":"test_proximity_level","unique_id":"test_proximity_level","state_topic":"ha-paneld/test/proximity_level/state","unit_of_measurement":"%","state_class":"measurement","icon":"mdi:hand-wave","force_update":true,$proximityAvail,$device}""",
            "temperature" to
                """{"name":"Temperature","object_id":"test_temperature","unique_id":"test_temperature","state_topic":"ha-paneld/test/temperature/state","device_class":"temperature","unit_of_measurement":"°C","state_class":"measurement","force_update":true,$avail,$device}""",
            "humidity" to
                """{"name":"Humidity","object_id":"test_humidity","unique_id":"test_humidity","state_topic":"ha-paneld/test/humidity/state","device_class":"humidity","unit_of_measurement":"%","state_class":"measurement","force_update":true,$avail,$device}""",
        )

        expected.forEach { (key, payload) ->
            val availability = if (key.startsWith("proximity")) proximityAvail else avail
            assertEquals(
                payload,
                SettingsRegistry.spec(key)!!.ha!!.buildDiscoveryJson(panel, availability, device),
            )
        }
    }

    @Test fun screenAndVolumeKeepTheirLegacyEntitiesAndNativeRanges() {
        assertEquals(
            """{"name":"Screen","object_id":"test_screen","unique_id":"test_screen","schema":"json","brightness":true,"supported_color_modes":["brightness"],"command_topic":"ha-paneld/test/screen/set","state_topic":"ha-paneld/test/screen/state",$avail,$device}""",
            build("screen"),
        )
        assertEquals(
            """{"name":"Volume","object_id":"test_volume","unique_id":"test_volume","command_topic":"ha-paneld/test/volume/set","state_topic":"ha-paneld/test/volume/state","min":0,"max":100,"step":1,"mode":"slider","unit_of_measurement":"%","icon":"mdi:volume-high",$avail,$device}""",
            build("volume"),
        )
    }

    @Test fun configEntitiesMigratedToRegistryMatchLegacyLiterals() {
        // Golden = the exact hand-written literals these entities used before the discovery loop was
        // switched to the registry. Byte-parity here is what lets the migration be behaviour-preserving
        // (existing HA installs see no entity churn). cpu_governor's options mirror CpuController.TIERS.
        val expected = mapOf(
            "touch_sound" to
                """{"name":"Touch sound","object_id":"test_touch_sound","unique_id":"test_touch_sound","command_topic":"ha-paneld/test/touch_sound/set","state_topic":"ha-paneld/test/touch_sound/state","icon":"mdi:volume-high","entity_category":"config",$avail,$device}""",
            "companion_auto_update" to
                """{"name":"Companion auto-update","object_id":"test_companion_auto_update","unique_id":"test_companion_auto_update","command_topic":"ha-paneld/test/companion_auto_update/set","state_topic":"ha-paneld/test/companion_auto_update/state","icon":"mdi:cellphone-arrow-down","entity_category":"config",$avail,$device}""",
            "companion_update_channel" to
                """{"name":"Companion auto-update channel","object_id":"test_companion_update_channel","unique_id":"test_companion_update_channel","command_topic":"ha-paneld/test/companion_update_channel/set","state_topic":"ha-paneld/test/companion_update_channel/state","options":["Stable","Pre-release"],"icon":"mdi:source-branch","entity_category":"config",$avail,$device}""",
            "cpu_governor" to
                """{"name":"CPU profile","object_id":"test_cpu_governor","unique_id":"test_cpu_governor","command_topic":"ha-paneld/test/cpu_governor/set","state_topic":"ha-paneld/test/cpu_governor/state","options":["Performance","Efficiency","Auto"],"icon":"mdi:speedometer","entity_category":"config",$avail,$device}""",
            "network_adb" to
                """{"name":"Network ADB","object_id":"test_network_adb","unique_id":"test_network_adb","command_topic":"ha-paneld/test/network_adb/set","state_topic":"ha-paneld/test/network_adb/state","icon":"mdi:adb","entity_category":"config",$avail,$device}""",
        )
        expected.forEach { (key, payload) -> assertEquals(payload, build(key)) }
    }

    @Test fun wifiSignalUsesHomeAssistantDiagnosticSignalSchema() {
        assertEquals(
            """{"name":"Wi-Fi signal strength","object_id":"test_diag_wifi_rssi","unique_id":"test_diag_wifi_rssi","state_topic":"ha-paneld/test/diag_wifi_rssi/state","device_class":"signal_strength","unit_of_measurement":"dBm","state_class":"measurement","icon":"mdi:wifi","entity_category":"diagnostic","force_update":true,$avail,$device}""",
            build("diag_wifi_rssi"),
        )
    }

    @Test fun wifiOutageCountersAreDiagnosticMeasurementSensors() {
        assertEquals(
            """{"name":"Wi-Fi outages (24 h)","object_id":"test_diag_wifi_outages_24h","unique_id":"test_diag_wifi_outages_24h","state_topic":"ha-paneld/test/diag_wifi_outages_24h/state","json_attributes_topic":"ha-paneld/test/diag_wifi_outages_24h/attributes","state_class":"measurement","icon":"mdi:wifi-alert","entity_category":"diagnostic","force_update":true,$avail,$device}""",
            build("diag_wifi_outages_24h"),
        )
    }

    @Test fun adaptiveBrightnessTuningRemainsPanelLocal() {
        assertNull(SettingsRegistry.spec("auto_brightness_minimum_percent")!!.ha)
        assertNull(SettingsRegistry.spec("auto_brightness_response_percent")!!.ha)
        assertNull(SettingsRegistry.spec("auto_brightness_ha_entity")!!.ha)
        assertNull(SettingsRegistry.spec("brightness_bias"))
        assertNull(SettingsRegistry.spec("ambient_lux"))
    }

    @Test fun operationalPanelSettingsRemainLocalOnly() {
        listOf(
            "silence_boot_chime",
            "prevent_idle_dim",
            "watchdog_enabled",
            "zigbee_router",
            "self_update",
            "update_channel",
        ).forEach { key ->
            assertNull("$key must not expose a Configure HA sync pip", SettingsRegistry.spec(key)!!.ha)
        }
    }

    @Test fun removedOperationalEntitiesRemainInTheDiscoveryTombstoneSuperset() {
        val known = io.github.maxlyth.hapaneld.mqttKnownConfigTopics(panel)
        listOf(
            "homeassistant/switch/test_silence_boot_chime/config",
            "homeassistant/switch/test_prevent_idle_dim/config",
            "homeassistant/switch/test_watchdog/config",
            "homeassistant/switch/test_zigbee_router/config",
            "homeassistant/switch/test_self_update/config",
            "homeassistant/select/test_update_channel/config",
            "homeassistant/button/test_admin_launcher/config",
            "homeassistant/button/test_back/config",
            "homeassistant/button/test_home/config",
            "homeassistant/button/test_launcher/config",
            "homeassistant/button/test_recents/config",
        ).forEach { topic ->
            assertTrue("missing tombstone topic $topic", known.contains(topic))
        }
    }

    @Test fun softHideAppendsEnabledByDefaultFalse() {
        val out = SettingsRegistry.spec("touch_sound")!!.ha!!
            .buildDiscoveryJson(panel, avail, device, enabledByDefault = false)
        assertTrue(out.contains(""","entity_category":"config","enabled_by_default":false,"availability_topic""""))
    }

    @Test fun stateTopicDerives() {
        assertEquals(
            "ha-paneld/test/wake_on_wave/state",
            SettingsRegistry.spec("wake_on_wave")!!.ha!!.stateTopic("test"),
        )
    }

    @Test fun proximityGatesEntityAvailability() {
        val s = SettingsRegistry.spec("wake_on_wave")!!
        assertTrue(s.availableWhen(Capabilities(hasProximity = true, hasLearnedProximity = true)))
        assertTrue(s.availableWhen(Capabilities(hasProximity = true, hasLearnedProximity = false)))
        assertTrue(!s.availableWhen(Capabilities(hasProximity = false, hasLearnedProximity = false)))
    }
}
