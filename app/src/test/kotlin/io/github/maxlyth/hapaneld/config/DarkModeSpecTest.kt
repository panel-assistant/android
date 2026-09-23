package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the dark-mode setting's contract: dark by default (wall panels live in dark rooms), BOOL, on
 *  the Display card, and shown ONLY on panels without a native system dark/light setting (Android 9-) —
 *  panels that have the OS control follow it and hide the toggle. */
class DarkModeSpecTest {
    private val spec = SettingsRegistry.spec("dark_mode")

    @Test fun registered() = assertNotNull("dark_mode must stay in the registry (persist/export/import)", spec)

    @Test fun defaultsDarkBoolOnDisplayCard() {
        assertEquals(SettingType.BOOL, spec!!.type)
        assertEquals("dark stays the out-of-box panel look", "true", spec.default)
        assertEquals("Display", spec.group)
    }

    @Test fun hiddenWherePanelHasSystemDarkMode() {
        assertTrue("no OS control (Android 9-) -> our toggle fills the gap", spec!!.availableWhen(Capabilities()))
        assertFalse("OS control exists (Android 10+) -> follow the system, hide ours", spec.availableWhen(Capabilities(hasSystemDarkMode = true)))
    }

    @Test fun firstVisibleDisplaySettingWhereSupported() {
        val display = SettingsRegistry.SPECS.filter { it.group == "Display" }
        assertEquals("dark_mode", display.first().key)
        assertEquals("dark_mode", display.first { it.availableWhen(Capabilities()) }.key)
        assertEquals(
            "auto_brightness_ha_entity",
            display.first { it.availableWhen(Capabilities(hasSystemDarkMode = true)) }.key,
        )
    }
}
