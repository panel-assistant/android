package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertEquals
import org.junit.Test

class FleetScopeSpecTest {

    @Test
    fun `new settings fail closed as device local`() {
        val spec = SettingSpec(
            key = "new_setting",
            type = SettingType.BOOL,
            group = "Test",
            label = "New setting",
            default = "false",
            tier = Tier.ADVANCED,
            summary = "New setting.",
        )

        assertEquals(Scope.DEVICE, spec.scope)
    }

    @Test
    fun `fleet portable settings are an explicit reviewed allowlist`() {
        val portable = SettingsRegistry.SPECS
            .filter { it.scope == Scope.PORTABLE }
            .map { it.key }
            .toSet()

        assertEquals(
            setOf(
                "mqtt_broker",
                "wake_on_wave",
                "prevent_idle_dim",
                "watchdog_enabled",
                "auto_brightness",
                "touch_sound",
                "keep_awake",
                "dashboard_entity_learning",
                "dashboard_theme",
                "dashboard_fullscreen",
                "dashboard_native_kiosk",
                "dashboard_overscroll",
                "dashboard_idle_return_min",
                "ha_url",
                "dark_mode",
                "dashboard_entity_auto_static",
                "dashboard_entity_auto_runtime",
            ),
            portable,
        )
    }

    @Test
    fun `panel credentials calibration and renderer state stay device local`() {
        val deviceLocal = listOf(
            "mqtt_user",
            "mqtt_password",
            "mqtt_address_family",
            "auto_sleep",
            "auto_brightness_minimum_percent",
            "auto_brightness_maximum_percent",
            "auto_brightness_response_percent",
            "auto_brightness_ha_entity",
            "cpu_governor",
            "dashboard_package",
            "home_dashboard",
            "ha_token",
            "ha_refresh_token",
            "ha_token_expiry",
            "ha_client_id",
            "dashboard_entity_overrides",
            "dashboard_entity_learning_applied",
            "dashboard_zoom",
            "launcher_package",
            "tame_vendor_packages",
            "room_temp_offset",
        )

        deviceLocal.forEach { key ->
            assertEquals("$key must not be applied by a fleet import", Scope.DEVICE, SettingsRegistry.spec(key)?.scope)
        }
    }
}
