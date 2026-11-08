package io.panelassistant.android.http

import io.panelassistant.android.PanelCommandDispatcher
import io.panelassistant.android.config.SettingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneldServerConfigWiringTest {
    @Test fun httpRoutesEveryApplicableLiveSettingThroughTheSharedDispatcher() {
        val registryKeys = SettingsRegistry.liveApplyKeys()
        val expectedKeys = setOf(
            "auto_brightness", "auto_brightness_ha_entity", "auto_brightness_minimum_percent", "auto_brightness_maximum_percent",
            "auto_brightness_response_percent", "auto_sleep",
            "cpu_governor", "dashboard_idle_return_min", "dashboard_theme", "ha_area", "home_dashboard", "kiosk_lock",
            "navbar_mode", "network_adb", "presence_mode", "prevent_idle_dim", "silence_boot_chime",
            "touch_sound", "voice_enabled", "wake_on_wave", "watchdog_enabled",
            "zigbee_router",
        )

        assertEquals(expectedKeys, registryKeys.toSet())
        assertEquals(registryKeys, PaneldServer.HTTP_LIVE_KEYS)
        assertEquals(expectedKeys, PanelCommandDispatcher.APPLY_SETTING_KEYS)
        assertTrue(registryKeys.all { SettingsRegistry.spec(it)?.readOnly == false })
    }
}
