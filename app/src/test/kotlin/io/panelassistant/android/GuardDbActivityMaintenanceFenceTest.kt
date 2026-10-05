package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbActivityMaintenanceFenceTest {
    @Test fun `maintenance redirect is sticky for every later lifecycle callback`() {
        val fence = GuardDbActivityMaintenanceFence()
        var redirects = 0
        var ordinaryOpeners = 0

        listOf("onCreate", "onStart", "onResume", "onWindowFocusChanged", "onNewIntent").forEach {
            if (!fence.stop(maintenanceRequired = true) { redirects++ }) ordinaryOpeners++
        }

        assertEquals("one Activity instance redirects only once", 1, redirects)
        assertEquals("no callback after maintenance admission may construct ordinary state", 0, ordinaryOpeners)
    }

    @Test fun `ordinary startup remains open until maintenance is first observed then stays closed`() {
        val fence = GuardDbActivityMaintenanceFence()
        var redirects = 0

        assertFalse(fence.stop(maintenanceRequired = false) { redirects++ })
        assertTrue(fence.stop(maintenanceRequired = true) { redirects++ })
        assertTrue(fence.stop(maintenanceRequired = false) { redirects++ })
        assertEquals(1, redirects)
    }
}
