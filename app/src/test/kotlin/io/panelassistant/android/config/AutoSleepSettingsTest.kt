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
    }

    @Test fun `first slice has no manual source contract and retains policy activity history`() {
        assertEquals(null, SettingsRegistry.spec("auto_sleep_source_mode"))
        assertEquals(null, SettingsRegistry.spec("auto_sleep_ha_entities"))
        assertTrue(SettingsRegistry.spec("auto_sleep_activity")?.ha?.readOnly == true)
        assertFalse(SettingsRegistry.spec("auto_sleep_activity")?.haExposedByDefault == true)
    }

    @Test fun `presence is a config select offering only the modes this panel's sensor supports`() {
        val presence = requireNotNull(SettingsRegistry.spec("presence_mode"))
        assertEquals(listOf("automatic", "home_assistant", "manual", "hybrid", "off"), presence.options)
        assertEquals(Scope.DEVICE, presence.scope)
        assertTrue(presence.liveApply)
        assertEquals("select", presence.ha?.component)
        assertEquals("presence", presence.ha?.objectSuffix)
        assertEquals("config", presence.ha?.facts?.entityCategory)
        assertEquals(presence.options, presence.ha?.facts?.options?.map { it.code })

        val ranged = Capabilities(hasProximity = true, hasRangedProximity = true)
        val binary = Capabilities(hasProximity = true)
        val none = Capabilities()
        assertEquals(presence.options, presence.optionsFor(ranged))
        assertEquals(listOf("home_assistant", "manual", "off"), presence.optionsFor(binary))
        assertEquals(listOf("home_assistant", "off"), presence.optionsFor(none))
        assertEquals("automatic", presence.defaultFor(ranged))
        assertEquals("manual", presence.defaultFor(binary))
        assertEquals("home_assistant", presence.defaultFor(none))
        assertFalse(presence.canApplyValue("automatic", binary))
        assertFalse(SettingsRegistry.isRetired("presence_mode"))
        assertTrue(SettingsRegistry.isRetired("auto_sleep_source"))
    }
}
