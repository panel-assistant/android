package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `home_dashboard` had no validator before this release, so a stored value can be a whole URL. The path
 * canonicalizer refuses a URL scheme, which is correct for a write and useless for a value already on
 * disk — the reload path then builds a deep link out of the whole URL and lands nowhere.
 */
class LegacyHomeDashboardOriginTest {

    private val origin = "https://ha.example:8123"

    @Test fun `an absolute route on this Home Assistant yields its path`() {
        assertEquals("/lovelace/0", sameOriginDashboardRoute("https://ha.example:8123/lovelace/0", origin))
    }

    @Test fun `the origin comparison ignores case and trailing whitespace`() {
        assertEquals("/lovelace", sameOriginDashboardRoute("  HTTPS://HA.EXAMPLE:8123/lovelace  ", origin))
    }

    @Test fun `an origin with no path at all means the account default`() {
        assertEquals("/", sameOriginDashboardRoute("https://ha.example:8123", origin))
    }

    @Test fun `a route on a different server is refused, never retargeted`() {
        assertNull(sameOriginDashboardRoute("https://other.example:8123/lovelace/0", origin))
        assertNull(sameOriginDashboardRoute("https://ha.example:9999/lovelace/0", origin))
        assertNull(sameOriginDashboardRoute("http://ha.example:8123/lovelace/0", origin))
    }

    @Test fun `a plain path is not this function's business`() {
        assertNull(sameOriginDashboardRoute("/lovelace/0", origin))
    }

    @Test fun `with no configured Home Assistant nothing is same-origin`() {
        assertNull(sameOriginDashboardRoute("https://ha.example:8123/lovelace/0", null))
    }
}
