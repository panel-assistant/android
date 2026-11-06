package io.panelassistant.android.config

import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.control.CpuController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/** Registry policy for the settings Home Assistant sees as channels. */
class RegistryChannelPolicyTest {
    @Test fun silenceBootChimeDefaultHasOneAuthority() {
        assertTrue(SettingsRegistry.DEFAULT_SILENCE_BOOT_CHIME)
        assertEquals(
            SettingsRegistry.DEFAULT_SILENCE_BOOT_CHIME.toString(),
            SettingsRegistry.spec("silence_boot_chime")?.default,
        )
        // Source-text reason: the published OpenAPI type and default are the settings wire contract.
        val openApi = JSONObject(TestSources.appFile("src/main/assets/openapi.json").readText())
        val chime = openApi.getJSONObject("paths")
            .getJSONObject("/api/v1/config")
            .getJSONObject("post")
            .getJSONObject("requestBody")
            .getJSONObject("content")
            .getJSONObject("application/x-www-form-urlencoded")
            .getJSONObject("schema")
            .getJSONObject("properties")
            .getJSONObject("silence_boot_chime")
        assertEquals("boolean", chime.getString("type"))
        assertEquals(SettingsRegistry.DEFAULT_SILENCE_BOOT_CHIME, chime.get("default"))
    }

    @Test fun cpuGovernorIsExplicitlyLiveOnly() {
        assertTrue(SettingsRegistry.spec("cpu_governor")?.transient == true)
    }

    @Test fun cpuGovernorOptionsStayAlignedWithRuntimeTiers() {
        assertEquals(CpuController.TIERS, SettingsRegistry.spec("cpu_governor")?.options)
    }

    @Test fun forceUpdateIsLimitedToTheBoundedMeasurementRefreshSet() {
        val expected = setOf(
            "illuminance", "proximity_level", "temperature", "humidity",
            "diag_cpu", "diag_memory", "diag_soc_temp", "diag_wifi_rssi",
            "diag_wifi_outages_24h", "room_temp", "room_humidity",
        )
        val periodic = SettingsRegistry.haCapable().filter { it.ha!!.periodicRefresh }

        assertEquals(expected, periodic.mapTo(linkedSetOf()) { it.key })
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
        ).forEach { key ->
            assertNull("$key must not expose a Configure HA sync pip", SettingsRegistry.spec(key)!!.ha)
        }
    }

}
