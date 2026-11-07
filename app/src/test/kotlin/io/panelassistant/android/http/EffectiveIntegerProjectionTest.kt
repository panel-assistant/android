package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.autoSleepTouchDelaySeconds
import io.panelassistant.android.config.ConfigBundle
import io.panelassistant.android.config.SettingType
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.fakePreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveIntegerProjectionTest {
    private val integers = SettingsRegistry.settable().filter { it.type == SettingType.INT }

    @Test fun malformedStoredIntegersProjectTheRuntimeRangeWithoutWriting() {
        for (spec in integers) for (stored in listOf(Int.MIN_VALUE, Int.MAX_VALUE)) {
            val expected = if (stored < 0) requireNotNull(spec.min).toInt() else requireNotNull(spec.max).toInt()
            checkProjection(spec.key, stored, expected)
        }
    }

    @Test fun validNonDefaultAndMissingIntegersKeepTheirEffectiveValues() {
        for (spec in integers) {
            val minimum = requireNotNull(spec.min).toInt()
            val valid = if (spec.default.toInt() != minimum) minimum else requireNotNull(spec.max).toInt()
            checkProjection(spec.key, valid, valid)
            checkProjection(spec.key, null, spec.default.toInt())
        }
    }

    @Test fun effectiveReadsPreserveRawValuesForLiveStoreMigration() {
        val prefs = fakePreferences(mapOf(
            "config_schema" to 5,
            "panel_id" to "integer_control",
            "friendly_name" to "Contract panel",
            "camera_fps" to 50,
            SettingsRegistry.LEGACY_SENSITIVITY_KEY to 35,
        ))
        val config = Config(prefs.instance)
        val before = prefs.values.toMap()
        assertEquals("30", projection(config).currentValues()["camera_fps"])
        assertEquals("50", config.getRaw(requireNotNull(SettingsRegistry.spec("camera_fps"))))
        assertEquals(before, prefs.values)

        assertTrue(config.migrateLiveStore())
        assertEquals(70, config.autoBrightnessResponsePercent)
        assertEquals(SettingsRegistry.SCHEMA, prefs.values["config_schema"])
        assertEquals(50, prefs.values["camera_fps"])
        assertEquals("30", projection(config).currentValues()["camera_fps"])
    }

    private fun checkProjection(key: String, stored: Int?, expected: Int) {
        val initial = mutableMapOf<String, Any?>("panel_id" to "integer_control", "friendly_name" to "Contract panel", "config_schema" to SettingsRegistry.SCHEMA)
        if (stored != null) initial[key] = stored
        val prefs = fakePreferences(initial)
        val config = Config(prefs.instance)
        val before = prefs.values.toMap()
        val values = projection(config)
        val message = "$key stored=$stored"
        assertEquals(message, expected, runtime(config, key))
        assertEquals(message, expected, JSONObject(values.settingsValuesJson()).getInt(key))
        assertEquals(message, expected.toString(), values.currentValues()[key])
        assertEquals(message, expected.toString(), values.revisionValues()[key])
        val bundle = requireNotNull(ConfigBundle.parse(ConfigBundle.fromValues(values.currentValues()).serialize()))
        val plan = planRestoreSettings(bundle.values, configuredOrigin = null)
        assertEquals(message, emptyList<String>(), plan.errors)
        assertEquals(message, expected.toString(), plan.accepted[key])
        assertEquals(message, (stored ?: expected).toString(), config.getRaw(requireNotNull(SettingsRegistry.spec(key))))
        assertEquals(message, before, prefs.values)
    }

    private fun runtime(config: Config, key: String): Int = when (key) {
        "auto_sleep_touch_delay_seconds" -> config.autoSleepTouchDelaySeconds
        "auto_brightness_minimum_percent" -> config.autoBrightnessMinimumPercent
        "auto_brightness_maximum_percent" -> config.autoBrightnessMaximumPercent
        SettingsRegistry.RESPONSE_PERCENT_KEY -> config.autoBrightnessResponsePercent
        "camera_fps" -> config.cameraFps
        "camera_kbps" -> config.cameraKbps
        "dashboard_idle_return_min" -> config.dashboardIdleReturnMin
        "dashboard_zoom" -> config.dashboardZoom
        "voice_mic_gain_db" -> config.voiceMicGainDb
        "log_ship_port" -> config.logShipPort
        else -> error("Missing typed runtime control for $key")
    }

    private fun projection(config: Config) = ConfigValueProjection(
        config, { emptyMap() }, { emptyMap() }, { emptyMap() }, { emptySet() },
        { "{}" }, { "{}" }, { null }, networkAdbManaged = { false },
    )
}
