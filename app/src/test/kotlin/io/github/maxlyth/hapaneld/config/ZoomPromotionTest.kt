package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renderer zoom is the unprivileged substitute for display sizing. Where the panel can set its display
 * (root or Shizuku) the Configure form is exactly as it was; where it cannot, zoom takes density's place.
 */
class ZoomPromotionTest {
    private val privileged = Capabilities(canSetDisplay = true)
    private val unprivileged = Capabilities(canSetDisplay = false)
    private val zoom = SettingsRegistry.SPECS.single { it.key == "dashboard_zoom" }

    private fun dashboardKeys(caps: Capabilities) =
        SettingsRegistry.schemaVisibleSpecs(caps).filter { it.group == "Dashboard" }.map { it.key }

    @Test fun `with display sizing the form is exactly as declared`() {
        assertEquals(SettingsRegistry.schemaVisibleSpecs(), SettingsRegistry.schemaVisibleSpecs(privileged))
        assertEquals("dashboard_zoom", dashboardKeys(privileged).last())
        assertEquals(Tier.ADVANCED, zoom.tierFor(privileged))
        assertEquals("settings.dashboard_zoom.help", zoom.helpKeyFor(privileged))
    }

    @Test fun `without display sizing zoom leads its group on the basic view with sizing help`() {
        assertEquals("dashboard_zoom", dashboardKeys(unprivileged).first())
        assertEquals(Tier.BASIC, zoom.tierFor(unprivileged))
        assertEquals("settings.dashboard_zoom.promoted_help", zoom.helpKeyFor(unprivileged))
        assertTrue(zoom.promotedHelp.contains("size the dashboard"))
    }

    @Test fun `promotion reorders only the promoted spec`() {
        val before = SettingsRegistry.schemaVisibleSpecs()
        val after = SettingsRegistry.schemaVisibleSpecs(unprivileged)
        assertEquals(before.toSet(), after.toSet())
        assertEquals(before.filterNot { it === zoom }, after.filterNot { it === zoom })
        val firstDashboard = before.indexOfFirst { it.group == "Dashboard" }
        assertSame(zoom, after[firstDashboard])
    }

    @Test fun `promotion never touches storage semantics`() {
        assertEquals("100", zoom.default)
        assertEquals(50.0, zoom.min)
        assertEquals(300.0, zoom.max)
        assertEquals(Scope.DEVICE, zoom.scope)
        assertEquals("100", zoom.defaultFor(unprivileged))
        assertNull(zoom.derivedDefault)
    }

    @Test fun `no other setting is promoted on either capability state`() {
        listOf(privileged, unprivileged).forEach { caps ->
            val promoted = SettingsRegistry.SPECS.filter { it.promoteWhen(caps) }.map { it.key }
            assertEquals(if (caps.canSetDisplay) emptyList() else listOf("dashboard_zoom"), promoted)
        }
        assertFalse(SettingsRegistry.SPECS.any { it.promotedHelp.isNotEmpty() && it.key != "dashboard_zoom" })
    }
}
