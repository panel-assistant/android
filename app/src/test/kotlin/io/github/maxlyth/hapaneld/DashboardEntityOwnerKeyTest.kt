package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Owner keys carry an instance and a dashboard scope in one string. Two upgrade defects both came from
 * comparing the whole string when only one component was meant to decide, so the split is asserted
 * directly here and the two call sites are asserted against it.
 */
class DashboardEntityOwnerKeyTest {

    @Test fun `a target key splits into its instance and its captured scope`() {
        val key = dashboardEntityTargetKey("ha-abc", "/lovelace/kiosk")
        // The scope component is the dashboard ROOT: a view and its dashboard yield the same entity set.
        assertEquals("6:ha-abc/lovelace", key)
        assertEquals("ha-abc", dashboardEntityInstanceOf(key))
        assertEquals("/lovelace", dashboardEntityPathOf(key))
    }

    /**
     * The upgrade repair, expressed as the rule it applies: rewrite a stored owner to embed the root of
     * the path it already embeds. A panel that stored a full route under the old scheme reads as unowned
     * under the new one, its filter reports empty, and it subscribes to the whole catalogue. The binding
     * path re-roots owners already, but every route to it runs through entity learning, and committing a
     * MANUAL filter turns learning off — so the repair could never reach the panels that needed it.
     */
    @Test fun `re-rooting a stored owner recovers the key the new scheme computes`() {
        val stored = "6:ha-abc/lovelace/kiosk"
        val rerooted = dashboardEntityTargetKey(dashboardEntityInstanceOf(stored), dashboardEntityPathOf(stored))
        assertEquals(dashboardEntityTargetKey("ha-abc", "/lovelace/kiosk"), rerooted)
        assertEquals("6:ha-abc/lovelace", rerooted)
    }

    @Test fun `re-rooting is idempotent, so it is safe on every start`() {
        val rooted = dashboardEntityTargetKey("ha-abc", "/lovelace")
        val again = dashboardEntityTargetKey(dashboardEntityInstanceOf(rooted), dashboardEntityPathOf(rooted))
        assertEquals(rooted, again)
    }

    @Test fun `re-rooting never moves an owner onto a different dashboard`() {
        // Shortening a route to its own root cannot adopt a neighbour: a genuinely different dashboard
        // still fails the ownership test and re-learns, which is the intended behaviour.
        val stored = "6:ha-abc/lovelace/kiosk"
        val rerooted = dashboardEntityTargetKey(dashboardEntityInstanceOf(stored), dashboardEntityPathOf(stored))
        assertEquals("ha-abc", dashboardEntityInstanceOf(rerooted))
        assertEquals("/lovelace", dashboardEntityPathOf(rerooted))
        assertNotEquals(dashboardEntityTargetKey("ha-abc", "/energy"), rerooted)
    }

    @Test fun `an instance whose own name contains the separator still splits correctly`() {
        // The length prefix exists for this: a naive indexOf on '/' or ':' would tear the key apart.
        val key = dashboardEntityTargetKey("https://ha.example:8123", "/alpha")
        assertEquals("https://ha.example:8123", dashboardEntityInstanceOf(key))
        assertEquals("/alpha", dashboardEntityPathOf(key))
    }

    @Test fun `a route-qualified owner from an older build still names the rooted dashboard`() {
        // The upgrade case behind the stranded-install defect. An owner stored before scope-rooting
        // reads as the same dashboard once its captured path is rooted, which is what makes re-rooting
        // it lossless rather than a re-learn.
        val legacyOwner = "6:ha-abc/lovelace/kiosk"
        assertEquals("ha-abc", dashboardEntityInstanceOf(legacyOwner))
        assertEquals("/lovelace", dashboardEntityScopePath(dashboardEntityPathOf(legacyOwner)))
        // A genuinely different dashboard must NOT root to the same place, or a real dashboard change
        // would silently inherit another dashboard's learned list instead of re-learning.
        assertEquals("/beta", dashboardEntityScopePath(dashboardEntityPathOf("6:ha-abc/beta/tab")))
    }

    @Test fun `an unparseable key yields neither an instance nor a scope`() {
        for (bad in listOf("", "ha-abc", "0:", "99:ha-abc/lovelace", "x:ha-abc/lovelace")) {
            assertEquals("instance of $bad", "", dashboardEntityInstanceOf(bad))
            assertEquals("scope of $bad", "", dashboardEntityPathOf(bad))
        }
    }
}
