package io.panelassistant.android.http

import io.panelassistant.android.config.SettingType
import io.panelassistant.android.config.SettingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structural guard for a defect that shipped: a value formatter attached to a `BOOL` key, which
 * `settingRowHtml` resolves to `on`/`off` before it ever consults a formatter. The formatter never
 * ran, nothing warned, and the live log-shipping status reached no page at all.
 */
class SettingRowFormatterTest {

    @Test fun aFormatterCannotBeBuiltForABoolSetting() {
        // The exact historical defect: log_ship_enabled is BOOL, so its row can never reach a formatter.
        val failure = runCatching { SettingRowFormatter.of("log_ship_enabled") { "$it (live)" } }
            .exceptionOrNull()
        assertTrue("expected construction to be rejected", failure is IllegalArgumentException)
        assertTrue(failure!!.message!!, failure.message!!.contains("log_ship_enabled"))
        assertTrue(failure.message!!, failure.message!!.contains("BOOL"))
    }

    @Test fun aFormatterCannotBeBuiltForASecretSetting() {
        // A secret resolves to set/— for the same reason, and formatting one would risk rendering it.
        val failure = runCatching { SettingRowFormatter.of("mqtt_password") { it } }.exceptionOrNull()
        assertTrue("expected construction to be rejected", failure is IllegalArgumentException)
        assertTrue(failure!!.message!!, failure.message!!.contains("secret"))
    }

    @Test fun aFormatterCannotBeBuiltForAnUnregisteredKey() {
        val failure = runCatching { SettingRowFormatter.of("no_such_setting") { it } }.exceptionOrNull()
        assertTrue("expected construction to be rejected", failure is IllegalArgumentException)
    }

    @Test fun everyKeyProductionActuallyFormatsIsFormattable() {
        // The call sites in behaviourRowsHtml and displayRowsHtml. If a future row formats a new key,
        // add it here — this list failing is the signal that the key cannot carry a formatter at all.
        listOf("ha_area", "auto_brightness_minimum_percent", "auto_brightness_response_percent").forEach { key ->
            val formatter = SettingRowFormatter.of(key) { raw -> "$raw!" }
            assertEquals(key, formatter.key)
            assertEquals("x!", formatter.formatFor(key, "x"))
        }
    }

    @Test fun formattableMatchesExactlyWhatSettingRowHtmlCanReach() {
        // Pin the classification against the whole registry rather than a sample, so a new SettingType
        // or a spec flipped to secret cannot drift away from the rendering rule this type encodes.
        val specs = SettingsRegistry.SPECS
        assertTrue("registry unexpectedly empty", specs.size > 20)
        specs.forEach { spec ->
            val reachable = !spec.secret && spec.type != SettingType.BOOL
            assertEquals(spec.key, reachable, SettingRowFormatter.formattable(spec))
        }
        assertTrue(specs.any { SettingRowFormatter.formattable(it) })
        assertTrue(specs.any { !SettingRowFormatter.formattable(it) })
    }

    @Test fun aFormatterRefusesToRenderARowItWasNotBuiltFor() {
        // The binding has to be enforced where it is used, not merely recorded on the instance. The
        // realistic mistake is hoisting a formatter out of the per-key loop that builds it, after which
        // every row would silently receive another row's suffix.
        val formatter = SettingRowFormatter.of("ha_area") { raw -> "$raw (local override)" }

        assertEquals("Office (local override)", formatter.formatFor("ha_area", "Office"))

        val failure = runCatching { formatter.formatFor("home_dashboard", "lovelace/0") }.exceptionOrNull()
        assertTrue("a mismatched row must be rejected", failure is IllegalArgumentException)
        assertTrue(failure!!.message!!, failure.message!!.contains("ha_area"))
        assertTrue(failure.message!!, failure.message!!.contains("home_dashboard"))
    }
}
