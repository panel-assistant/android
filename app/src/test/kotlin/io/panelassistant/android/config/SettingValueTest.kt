package io.panelassistant.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingValueTest {

    private fun spec(
        type: SettingType,
        min: Double? = null,
        max: Double? = null,
        maxChars: Int = 16 * 1024,
        options: List<String> = emptyList(),
        validate: (String) -> Validation = { Validation.Ok(it) },
    ) = SettingSpec(
        key = "k", type = type, group = "g", label = "l", default = "", tier = Tier.ADVANCED, summary = "s",
        min = min, max = max, maxChars = maxChars, options = options, validate = validate,
    )

    @Test fun mqttBrokerRejectsUnsupportedOrMalformedSchemesBeforePersistence() {
        val mqtt = SettingsRegistry.spec("mqtt_broker")!!

        assertTrue(SettingValue.validate(mqtt, "tcp://broker.example:1883") is Validation.Ok)
        assertEquals(
            "tcp://broker.example:1883",
            ok(SettingValue.validate(mqtt, "tcp://broker.example:1883/")),
        )
        assertTrue(SettingValue.validate(mqtt, "mqtts://broker.example") is Validation.Ok)
        assertTrue(SettingValue.validate(mqtt, "") is Validation.Ok)
        assertTrue(SettingValue.validate(mqtt, "wss://broker.example:1883") is Validation.Bad)
        assertTrue(SettingValue.validate(mqtt, "tcp://") is Validation.Bad)
        assertTrue(SettingValue.validate(mqtt, "tcp://broker.example:0") is Validation.Bad)
        assertTrue(SettingValue.validate(mqtt, "tcp://broker.example:1883/path") is Validation.Bad)
    }

    private fun ok(v: Validation): String = (v as Validation.Ok).normalized

    @Test fun boolAcceptsCommonSpellings() {
        listOf("true", "1", "on", "ON", "yes").forEach {
            assertEquals("true", ok(SettingValue.validate(spec(SettingType.BOOL), it)))
        }
        listOf("false", "0", "off", "no").forEach {
            assertEquals("false", ok(SettingValue.validate(spec(SettingType.BOOL), it)))
        }
        assertTrue(SettingValue.validate(spec(SettingType.BOOL), "maybe") is Validation.Bad)
    }

    @Test fun intRangeChecked() {
        val s = spec(SettingType.INT, min = -100.0, max = 100.0)
        assertEquals("5", ok(SettingValue.validate(s, "5")))
        assertEquals("-100", ok(SettingValue.validate(s, "-100")))
        assertTrue(SettingValue.validate(s, "101") is Validation.Bad)
        assertTrue(SettingValue.validate(s, "x") is Validation.Bad)
    }

    @Test fun floatNormalizesIntegral() {
        val s = spec(SettingType.FLOAT)
        assertEquals("5", ok(SettingValue.validate(s, "5.0")))
        assertEquals("5.5", ok(SettingValue.validate(s, "5.5")))
    }

    @Test fun floatRejectsNonFiniteValuesBeforeRangeOrPersistence() {
        val s = spec(SettingType.FLOAT, min = -20.0, max = 20.0)
        listOf("NaN", "Infinity", "+Infinity", "-Infinity").forEach {
            assertTrue(it, SettingValue.validate(s, it) is Validation.Bad)
        }
    }

    @Test fun enumIsCaseInsensitiveAndNormalizes() {
        val s = spec(SettingType.ENUM, options = listOf("Off", "Always on", "Swipe reveal"))
        assertEquals("Always on", ok(SettingValue.validate(s, "always on")))
        assertTrue(SettingValue.validate(s, "nope") is Validation.Bad)
    }

    @Test fun panelIdSlugValidatorFromRegistry() {
        val s = SettingsRegistry.spec("panel_id")!!
        assertEquals("alpha_device", ok(SettingValue.validate(s, "Alpha-Device")))
        assertTrue(SettingValue.validate(s, "  -- ") is Validation.Bad)
    }

    @Test fun panelIdHonoursDnsSafeLengthBoundary() {
        val s = SettingsRegistry.spec("panel_id")!!
        assertEquals("a".repeat(63), ok(SettingValue.validate(s, "a".repeat(63))))
        val rejected = SettingValue.validate(s, "a".repeat(64))
        assertTrue(rejected is Validation.Bad)
        assertTrue((rejected as Validation.Bad).reason.contains("63"))
    }

    @Test fun dashboardPackageRejectsShellInput() {
        val s = SettingsRegistry.spec("dashboard_package")!!
        listOf("", "builtin", "io.homeassistant.companion.android.minimal").forEach {
            assertEquals(it, ok(SettingValue.validate(s, it)))
        }
        listOf("com.example;reboot", "com.example dashboard", "com/example").forEach {
            assertTrue(SettingValue.validate(s, it) is Validation.Bad)
        }
    }

    @Test fun stringAndPasswordLengthsAreBoundedBeforeCustomValidation() {
        val string = spec(SettingType.STRING, maxChars = 4)
        assertEquals("four", ok(SettingValue.validate(string, " four ")))
        val rejected = SettingValue.validate(string, "12345")
        assertTrue(rejected is Validation.Bad)
        assertTrue((rejected as Validation.Bad).reason.contains("4"))

        val password = spec(SettingType.PASSWORD, maxChars = 3)
        assertTrue(SettingValue.validate(password, "1234") is Validation.Bad)
    }

    @Test fun passwordsPreserveLeadingAndTrailingSpacesExactly() {
        val password = spec(SettingType.PASSWORD, maxChars = 32)
        assertEquals("  mqtt secret  ", ok(SettingValue.validate(password, "  mqtt secret  ")))
    }

    @Test fun longValuesRetainTheFullPreferenceRange() {
        val long = spec(SettingType.LONG, min = 0.0)
        assertEquals(
            Long.MAX_VALUE.toString(),
            ok(SettingValue.validate(long, Long.MAX_VALUE.toString())),
        )
        assertTrue(SettingValue.validate(long, "-1") is Validation.Bad)
    }
}
