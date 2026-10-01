package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.MqttBridge
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneldServerConfigWiringTest {
    @Test fun httpRoutesEveryApplicableLiveSettingThroughTheSharedDispatcher() {
        val registryKeys = SettingsRegistry.liveApplyKeys()
        val expectedKeys = setOf(
            "auto_brightness", "auto_brightness_ha_entity", "auto_brightness_minimum_percent", "auto_brightness_maximum_percent",
            "auto_brightness_response_percent", "auto_sleep", "auto_sleep_source",
            "cpu_governor", "ha_area", "home_dashboard", "kiosk_lock",
            "navbar_mode", "network_adb", "prevent_idle_dim", "silence_boot_chime",
            "touch_sound", "voice_enabled", "wake_on_wave", "watchdog_enabled",
            "zigbee_router",
        )

        assertEquals(expectedKeys, registryKeys.toSet())
        assertEquals(registryKeys, PaneldServer.HTTP_LIVE_KEYS)
        assertEquals(expectedKeys, MqttBridge.APPLY_SETTING_KEYS)
        assertTrue(registryKeys.all { SettingsRegistry.spec(it)?.readOnly == false })
    }
}
