package io.panelassistant.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSleepSettingsTest {
    @Test fun `auto sleep and its HA sync default off and remain device scoped`() {
        val enabled = requireNotNull(SettingsRegistry.spec("auto_sleep"))

        assertEquals("false", enabled.default)
        assertEquals(Scope.DEVICE, enabled.scope)
        assertFalse(enabled.haExposedByDefault)
        assertEquals(null, SettingsRegistry.spec("auto_sleep_source_mode"))
        assertEquals(null, SettingsRegistry.spec("auto_sleep_ha_entities"))
    }

    @Test fun `first slice has no manual source contract and retains policy activity history`() {
        assertEquals(null, SettingsRegistry.spec("auto_sleep_source_mode"))
        assertEquals(null, SettingsRegistry.spec("auto_sleep_ha_entities"))
        assertTrue(SettingsRegistry.spec("auto_sleep_activity")?.ha?.readOnly == true)
        assertFalse(SettingsRegistry.spec("auto_sleep_activity")?.haExposedByDefault == true)
    }
}
