package io.github.maxlyth.hapaneld.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the dashboard-zoom setting's contract: a visible, tunable INT % on the Dashboard card that
 *  defaults to 100 (HA Companion parity, so a switched-over panel keeps its sizing) and is never an HA
 *  entity. */
class DashboardZoomSpecTest {
    private val spec = SettingsRegistry.spec("dashboard_zoom")

    @Test fun registered() =
        assertNotNull("dashboard_zoom must stay in the registry (persist/export/import)", spec)

    @Test fun hundredPercentDefaultIntOnDashboardCard() {
        assertEquals(SettingType.INT, spec!!.type)
        assertEquals("100 = HA Companion default sizing", "100", spec.default)
        assertEquals("Dashboard", spec.group)
    }

    @Test fun boundedRange() {
        assertEquals(50.0, spec!!.min)
        assertEquals(300.0, spec.max)
    }

    @Test fun notAnHaEntity() = assertNull("local display setting, never an HA entity", spec!!.ha)
}
