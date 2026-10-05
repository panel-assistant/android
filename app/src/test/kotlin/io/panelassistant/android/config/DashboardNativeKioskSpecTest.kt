package io.panelassistant.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardNativeKioskSpecTest {
    private val spec = requireNotNull(SettingsRegistry.spec("dashboard_native_kiosk"))

    @Test fun `native kiosk defaults on as portable renderer configuration`() {
        assertEquals(SettingType.BOOL, spec.type)
        assertEquals("true", spec.default)
        assertEquals("Dashboard", spec.group)
        assertEquals(Scope.PORTABLE, spec.scope)
        assertFalse(spec.hidden)
        assertFalse(spec.transient)
        assertEquals("Hide Home Assistant navigation (native)", spec.label)
    }

    @Test fun `native kiosk is not advertised as an MQTT entity or CSS emulation`() {
        assertNull(spec.ha)
        assertTrue(spec.help.contains("After Home Assistant 2026.4.2+ connects"))
        assertTrue(spec.help.contains("does not lock Android"))
        assertTrue(spec.help.contains("does not") && spec.help.contains("inject CSS"))
        assertFalse(spec.help.contains("HACS"))
        assertTrue(spec.help.contains("leaves the dashboard unchanged"))
    }

    @Test fun `three panel chrome controls name distinct layers and effects`() {
        val androidLock = requireNotNull(SettingsRegistry.spec("kiosk_lock"))
        val systemBars = requireNotNull(SettingsRegistry.spec("dashboard_fullscreen"))

        assertEquals("Lock Android to dashboard (experimental)", androidLock.label)
        assertTrue(androidLock.help.contains("Root-only casual-use lock"))
        assertTrue(androidLock.help.contains("within about 3 seconds"))
        assertTrue(androidLock.help.contains("60-second unlocked window after reboot"))
        assertTrue(androidLock.help.contains("does not hide Home Assistant navigation"))
        assertEquals("Android dashboard lock", androidLock.ha?.name)

        assertEquals("Hide Android system bars", systemBars.label)
        assertTrue(systemBars.help.contains("while the dashboard is in front"))
        assertTrue(systemBars.help.contains("does not lock the panel"))
        assertTrue(systemBars.help.contains("does not") && systemBars.help.contains("Home Assistant navigation"))

        assertFalse(spec.help.contains("Root-only"))
        assertFalse(systemBars.help.contains("Root-only"))
    }
}
