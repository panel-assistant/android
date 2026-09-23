package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardIdleReturnPolicyTest {
    @Test fun `same home path without a fragment stays put`() {
        assertNull(DashboardIdleReturnPolicy.target("/lovelace/0", null, "/lovelace/0"))
        assertNull(DashboardIdleReturnPolicy.target("/lovelace/0/", "", "lovelace/0/"))
    }

    @Test fun `same home path with a popup fragment returns to fragment-free home`() {
        assertEquals(
            "lovelace/0",
            DashboardIdleReturnPolicy.target("/lovelace/0", "beta", "/lovelace/0"),
        )
    }

    @Test fun `configured query is preserved while its deep-link fragment is cleared`() {
        assertEquals(
            "lovelace/0?theme=dark&return=/",
            DashboardIdleReturnPolicy.target(
                currentPath = "/lovelace/0",
                currentFragment = "beta",
                homeDashboard = "/lovelace/0?theme=dark&return=/#beta",
            ),
        )
    }

    @Test fun `different path returns to configured home target`() {
        assertEquals(
            "lovelace/0?theme=dark",
            DashboardIdleReturnPolicy.target("/other/view", null, "/lovelace/0?theme=dark"),
        )
    }

    @Test fun `fragment only home resolves to root`() {
        assertEquals("/", DashboardIdleReturnPolicy.target("/lovelace/0", "beta", "#home"))
    }

    @Test fun `matching external auth query does not trigger a return`() {
        assertNull(DashboardIdleReturnPolicy.target("/lovelace/0", null, "/lovelace/0", "external_auth=1"))
    }

    @Test fun `configured query is restored when current route differs only by query`() {
        assertEquals("lovelace/0?theme=dark", DashboardIdleReturnPolicy.target("/lovelace/0", null, "/lovelace/0?theme=dark"))
    }

    @Test fun `encoded query values compare without idle churn`() {
        assertNull(
            DashboardIdleReturnPolicy.target(
                currentPath = "/lovelace/0",
                currentFragment = null,
                homeDashboard = "/lovelace/0?theme=Bubble%20Dark",
                currentQuery = "theme=Bubble%20Dark",
            ),
        )
    }

    @Test fun `blank home target remains disabled`() {
        assertNull(DashboardIdleReturnPolicy.target("/lovelace/0", "beta", "  "))
    }
}
