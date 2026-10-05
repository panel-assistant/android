package io.panelassistant.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the `ha_expose_` naming convention that decides which per-setting expose-to-HA pips exist and
 * how they persist. The prefix is a stored-format contract (SharedPreferences keys + config bundles),
 * so the string must stay byte-identical to the historical `"ha_expose_$key"` literal; the parse/
 * construct helpers own the convention that was previously duplicated across eight call sites.
 */
class SettingsRegistryExposureTest {

    private val haSpec = SettingsRegistry.SPECS.first { it.ha != null }
    private val nonHaSpec = SettingsRegistry.SPECS.first { it.ha == null }

    @Test fun prefixIsStableStoredFormat() {
        // Existing panels persist these keys; a change silently drops their saved expose choices.
        assertEquals("ha_expose_", SettingsRegistry.HA_EXPOSE_PREFIX)
    }

    @Test fun exposureKeyIsPrefixPlusSpecKey() {
        assertEquals("ha_expose_${haSpec.key}", SettingsRegistry.exposureKey(haSpec))
    }

    @Test fun roundTripResolvesBackToTheSameSpec() {
        assertSame(haSpec, SettingsRegistry.parseExposure(SettingsRegistry.exposureKey(haSpec)))
    }

    @Test fun knownExposureKeyResolvesToItsSpec() {
        val spec = SettingsRegistry.spec("diag_boot")!!
        assertSame(spec, SettingsRegistry.parseExposure("ha_expose_diag_boot"))
    }

    @Test fun nonExposureNameIsNull() {
        // A bare setting name (no prefix) is not an exposure toggle.
        assertNull(SettingsRegistry.parseExposure(haSpec.key))
        assertNull(SettingsRegistry.parseExposure("panel_id"))
    }

    @Test fun unknownTargetIsNull() {
        assertNull(SettingsRegistry.parseExposure("ha_expose_definitely_not_a_registered_key"))
    }

    @Test fun specWithoutHaEntityIsNull() {
        // Only specs that carry an HA entity (ha != null) may be exposed; others get no pip.
        assertNull(nonHaSpec.ha)
        assertNull(SettingsRegistry.parseExposure(SettingsRegistry.exposureKey(nonHaSpec)))
    }

    @Test fun dashboardNetworkWarningIsVisibleOnlyOnThePanel() {
        val spec = SettingsRegistry.spec("dashboard_network_warning")!!
        assertEquals("Display", spec.group)
        assertEquals("true", spec.default)
        assertEquals(Scope.DEVICE, spec.scope)
        assertEquals(spec, SettingsRegistry.schemaVisibleSpecs().first { it.key == spec.key })
        assertNull(SettingsRegistry.parseExposure("ha_expose_dashboard_network_warning"))
    }
}
