package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route rules the renderer and the `home_dashboard` validator now share. These are the cases the
 * Custom path input can produce, asserted on the admission decision itself rather than on whether some
 * caller happened to survive them.
 */
class DashboardPathTest {

    @Test fun aViewBelowADashboardRootKeepsItsRouteQueryAndFragment() {
        assertEquals("/dashboard-test/alpha", DashboardPath.canonical("/dashboard-test/alpha", true))
        assertEquals("/alpha/view?kiosk=1#main", DashboardPath.canonical("/alpha/view?kiosk=1#main", true))
        assertEquals("/alpha/Upper%20Floor", DashboardPath.canonical("/alpha/Upper%20Floor", true))
        // The issue's own example, entered without a leading slash and with stray whitespace.
        assertEquals("/dashboard-test/laundry", DashboardPath.canonical("  dashboard-test/laundry  ", true))
    }

    @Test fun membershipIsTestedByRootSoAViewBelongsToItsDashboard() {
        assertEquals("/alpha", DashboardPath.root("/alpha/view?kiosk=1#main"))
        assertEquals("/alpha", DashboardPath.root("/alpha"))
        assertEquals("/dashboard-test", DashboardPath.root("dashboard-test/alpha/deeper"))
        assertNull(DashboardPath.root("https://elsewhere.example/alpha"))
    }

    @Test fun nothingThatLeavesThisHomeAssistantIsEverCanonical() {
        val escapes = listOf(
            "https://ha.example/wall-panel",
            "HTTPS://ha.example/wall-panel",
            "javascript:alert(1)",
            "//ha.example/wall-panel",
            // A protocol-relative form whose host happens to LOOK like a legal dashboard root. The
            // root-segment check alone would admit this one, so it is what makes the `//` guard real.
            "//alpha/view",
            "//alpha",
            "../wall-panel",
            "wall%2fpanel",
            "wall\\panel",
            "null",
            "/Alpha",              // dashboard url_paths are lower-case
            "/_alpha",             // a root may not start with an underscore
            "/alpha\u0007bell",    // an embedded control character
            "",
            "   ",
        )
        for (candidate in escapes) {
            assertNull("preserveRoute admitted $candidate", DashboardPath.canonical(candidate, true))
            assertNull("root admitted $candidate", DashboardPath.root(candidate))
        }
    }

    @Test fun aTraversalBelowALegalRootIsRefusedAsARouteButStillNamesThatRoot() {
        // Traversal is a SUFFIX concern: the dashboard is genuinely /alpha, and it is the view part
        // that must never escape it. Reducing to the root deliberately ignores the suffix, which is
        // why list membership can be tested with it while the route itself is still refused.
        for (traversal in listOf(
            "/alpha/../../etc",
            "/alpha/%2e%2e/evil",
            "/alpha/%2Foutside",
            "/alpha/%2e%2E/evil",
        )) {
            assertNull("route admitted $traversal", DashboardPath.canonical(traversal, true))
            assertEquals("$traversal still belongs to /alpha", "/alpha", DashboardPath.root(traversal))
        }
    }

    @Test fun aTruncatedPercentEscapeCannotSlipThroughTheDecoder() {
        assertNull(DashboardPath.canonical("/alpha/%2", true))
        assertNull(DashboardPath.canonical("/alpha/%zz", true))
    }

    @Test fun onlyRoutesThatNameNoDashboardFollowTheAccountDefault() {
        for (auto in listOf("", "   ", "/", "//", " /?kiosk ", "/#view")) {
            assertTrue("$auto should mean Auto", DashboardPath.followsAccountDefault(auto))
        }
        for (explicit in listOf("/lovelace", "/alpha/view", "alpha", "/dashboard-test/alpha?x=1")) {
            assertFalse("$explicit should be explicit", DashboardPath.followsAccountDefault(explicit))
        }
    }

    @Test fun rootSegmentsFollowHomeAssistantsOwnUrlPathShape() {
        for (ok in listOf("alpha", "dashboard-test", "lovelace", "0", "a_b-c9")) {
            assertTrue("$ok should be a legal url_path", DashboardPath.isRootSegment(ok))
        }
        for (bad in listOf("", "-alpha", "_alpha", "Alpha", "al/pha", "al pha", "al.pha")) {
            assertFalse("$bad should be rejected", DashboardPath.isRootSegment(bad))
        }
    }
}
