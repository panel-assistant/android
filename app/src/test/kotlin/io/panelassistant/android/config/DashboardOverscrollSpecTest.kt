package io.panelassistant.android.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the dashboard-overscroll setting's contract: a hidden (API-only), off-by-default BOOL on the
 *  Dashboard card that never surfaces in the Configure form or as an HA entity, but stays a first-class
 *  registry citizen so it persists, exports in bundles, and reads back via GET /config. */
class DashboardOverscrollSpecTest {
    private val spec = SettingsRegistry.spec("dashboard_overscroll")

    @Test fun registered() =
        assertNotNull("dashboard_overscroll must stay in the registry (persist/export/import)", spec)

    @Test fun offByDefaultBoolOnDashboardCard() {
        assertEquals(SettingType.BOOL, spec!!.type)
        assertEquals("overscroll bounce is off out of the box", "false", spec.default)
        assertEquals("Dashboard", spec.group)
    }

    @Test fun apiOnlyNeverAnHaEntity() {
        assertTrue("hidden from the generated Configure form (API-only)", spec!!.hidden)
        assertNull("never an HA entity", spec.ha)
    }

    @Test fun fleetPushable() {
        assertEquals("a display preference safe to push fleet-wide", Scope.PORTABLE, spec!!.scope)
        assertFalse("must persist (not transient)", spec.transient)
    }
}
