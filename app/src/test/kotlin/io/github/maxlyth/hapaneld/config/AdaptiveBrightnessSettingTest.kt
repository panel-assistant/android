package io.github.maxlyth.hapaneld.config

import io.github.maxlyth.hapaneld.control.AdaptiveLuxCurve
import io.github.maxlyth.hapaneld.control.BrightnessController
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBrightnessSettingTest {
    @Test fun `ambient source precedes the controls that depend on it`() {
        val displayKeys = SettingsRegistry.settable().filter { it.group == "Display" }.map { it.key }

        assertTrue(
            displayKeys.indexOf("auto_brightness_ha_entity") < displayKeys.indexOf("auto_brightness"),
        )
        assertTrue(
            displayKeys.indexOf("auto_brightness") < displayKeys.indexOf("auto_brightness_minimum_percent"),
        )
        assertTrue(
            displayKeys.indexOf("auto_brightness_minimum_percent") < displayKeys.indexOf("auto_brightness_response_percent"),
        )
    }

    @Test fun `minimum automatic level preserves the existing floor by default`() {
        val spec = SettingsRegistry.spec("auto_brightness_minimum_percent")!!

        assertEquals(SettingType.INT, spec.type)
        assertEquals("Minimum level", spec.label)
        assertEquals("4", spec.default)
        assertEquals(4.0, spec.min)
        assertEquals(99.0, spec.max)
        assertEquals(1.0, spec.step)
        assertEquals(Scope.DEVICE, spec.scope)
        assertNull(spec.ha)
        assertEquals("4", (SettingValue.validate(spec, "4") as Validation.Ok).normalized)
        assertEquals("99", (SettingValue.validate(spec, "99") as Validation.Ok).normalized)
        assertTrue(SettingValue.validate(spec, "3") is Validation.Bad)
        // 100 would put the floor at full brightness, leaving automatic control no range to act in.
        assertTrue(SettingValue.validate(spec, "100") is Validation.Bad)
    }

    @Test fun `maximum automatic level defaults to full brightness and validates usable range`() {
        val spec = requireNotNull(SettingsRegistry.spec("auto_brightness_maximum_percent"))
        assertEquals("100", spec.default)
        assertEquals(SettingType.INT, spec.type)
        assertEquals(5.0, spec.min)
        assertEquals(100.0, spec.max)
        assertEquals(Scope.DEVICE, spec.scope)
        assertNull(spec.ha)
        listOf("5", "60", "100").forEach {
            assertTrue(SettingValue.validate(spec, it) is Validation.Ok)
        }
        listOf("4", "101").forEach {
            assertTrue(SettingValue.validate(spec, it) is Validation.Bad)
        }
    }

    /**
     * The floor is not a taste decision: raw brightness is clamped to the actuator's never-blank
     * minimum, so every percentage below this one drives the same raw level and the control would
     * appear to move while the screen did not. This fails if either number drifts from the other.
     */
    @Test fun `the minimum level floor is the first percent the backlight can distinguish`() {
        val floor = SettingsRegistry.MINIMUM_AUTOMATIC_PERCENT

        assertEquals(ceil(BrightnessController.MIN_VISIBLE * 100.0 / 255.0).toInt(), floor)
        assertEquals(BrightnessController.MIN_VISIBLE, AdaptiveLuxCurve.percentToBrightness(floor))
        assertEquals(BrightnessController.MIN_VISIBLE, AdaptiveLuxCurve.percentToBrightness(floor - 1))
        assertTrue(AdaptiveLuxCurve.percentToBrightness(floor + 1) > BrightnessController.MIN_VISIBLE)
    }

    @Test fun `sensitivity is the percent of measured deviation that reaches the screen`() {
        val spec = SettingsRegistry.spec("auto_brightness_response_percent")!!

        assertEquals(SettingType.INT, spec.type)
        assertEquals("Sensitivity", spec.label)
        assertEquals("50", spec.default)
        assertEquals(0.0, spec.min)
        assertEquals(100.0, spec.max)
        // Schema 5 stepped by 5, which put the whole low-gain band operators actually use into three
        // notches and bottomed out at the first non-zero one.
        assertEquals(1.0, spec.step)
        assertEquals(Scope.DEVICE, spec.scope)
        assertNull(spec.ha)
        assertEquals("0", (SettingValue.validate(spec, "0") as Validation.Ok).normalized)
        assertEquals("100", (SettingValue.validate(spec, "100") as Validation.Ok).normalized)
        assertTrue(SettingValue.validate(spec, "-1") is Validation.Bad)
        assertTrue(SettingValue.validate(spec, "101") is Validation.Bad)
    }

    /**
     * The registry default and the migration are coupled: a live store that never materialized the key
     * presents its default to the migration, which cannot tell that value from a stored one. That is
     * harmless only while the default is numerically the schema-5 default, because both then rescale to
     * the same answer. Changing the default alone silently breaks upgrades, so this pins the coupling.
     */
    @Test fun `the sensitivity default rescales to the same value a stored legacy default would`() {
        val default = SettingsRegistry.spec("auto_brightness_response_percent")!!.default.toInt()

        assertEquals(SettingsRegistry.LEGACY_NEUTRAL_SENSITIVITY, default)
        assertEquals(
            Migrations.rescaleSensitivity(SettingsRegistry.LEGACY_NEUTRAL_SENSITIVITY),
            Migrations.rescaleSensitivity(default),
        )
    }

    @Test fun `HA source accepts only blank or an exact sensor entity id`() {
        val spec = SettingsRegistry.spec("auto_brightness_ha_entity")!!

        assertEquals(Scope.DEVICE, spec.scope)
        assertEquals("ha_illuminance", spec.picker)
        assertNull(spec.ha)
        assertEquals("", (SettingValue.validate(spec, "  ") as Validation.Ok).normalized)
        assertEquals(
            "sensor.living_room_illuminance",
            (SettingValue.validate(spec, " sensor.living_room_illuminance ") as Validation.Ok).normalized,
        )
        listOf(
            "light.living_room",
            "sensor.Living_Room",
            "sensor.room lux",
            "sensor.room/*",
            "{{ states('sensor.room') }}",
        ).forEach { value ->
            assertTrue("must reject $value", SettingValue.validate(spec, value) is Validation.Bad)
        }
    }

}
