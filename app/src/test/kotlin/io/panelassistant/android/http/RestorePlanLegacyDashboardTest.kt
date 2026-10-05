package io.panelassistant.android.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A restore is all or nothing, so one value an older release was allowed to store can cost the owner
 * every other setting in the archive — at the moment they are restoring, which is the worst moment for
 * it. `home_dashboard` had no validator then, so an archive can carry a whole address.
 */
class RestorePlanLegacyDashboardTest {

    private val origin = "https://ha.example:8123"

    @Test fun `an archive holding this panel's own address restores, and restores to a path`() {
        val decision = planRestoreSettings(
            mapOf("home_dashboard" to "https://ha.example:8123/lovelace/beta", "panel_id" to "alpha"),
            origin,
        )
        assertTrue("archive must restore, errors were ${decision.errors}", decision.errors.isEmpty())
        assertEquals("/lovelace/beta", decision.accepted["home_dashboard"])
        assertEquals("alpha", decision.accepted["panel_id"])
    }

    @Test fun `an address on someone else's server is refused rather than silently retargeted`() {
        val decision = planRestoreSettings(
            mapOf("home_dashboard" to "https://other.example:8123/lovelace/beta"),
            origin,
        )
        assertTrue("a foreign origin must be refused", decision.errors.any { it.startsWith("home_dashboard:") })
        assertTrue(decision.accepted.isEmpty())
    }

    @Test fun `a plain path still restores unchanged`() {
        val decision = planRestoreSettings(mapOf("home_dashboard" to "/lovelace/beta"), origin)
        assertTrue(decision.errors.isEmpty())
        assertEquals("/lovelace/beta", decision.accepted["home_dashboard"])
    }

    @Test fun `a bare origin restores as follow-the-account-default`() {
        val decision = planRestoreSettings(mapOf("home_dashboard" to "https://ha.example:8123"), origin)
        assertTrue("errors were ${decision.errors}", decision.errors.isEmpty())
        assertEquals("", decision.accepted["home_dashboard"])
    }

    @Test fun `with no configured Home Assistant an absolute value is still refused, not guessed`() {
        val decision = planRestoreSettings(
            mapOf("home_dashboard" to "https://ha.example:8123/lovelace/beta"),
            null,
        )
        assertTrue(decision.errors.any { it.startsWith("home_dashboard:") })
    }
}
