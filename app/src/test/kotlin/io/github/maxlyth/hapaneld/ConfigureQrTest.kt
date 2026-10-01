package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.isScannableHost
import io.github.maxlyth.hapaneld.util.scannableHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigureQrTest {
    @Test fun `only the two credential screens send anybody to their phone`() {
        assertEquals(11, AdmissionOutcome.entries.size)
        val sent = AdmissionOutcome.entries.filter { configureQrPath(it) != null }
        assertEquals(
            listOf(AdmissionOutcome.CREDENTIAL_REFUSED, AdmissionOutcome.SIGN_IN_REQUIRED).sorted(),
            sent.sorted(),
        )
        assertNull(configureQrPath(AdmissionOutcome.BRIDGE_UNAVAILABLE))
        assertNull(configureQrPath(null))
    }

    @Test fun `the code opens the sign-in control itself, not the top of a crowded page`() {
        // The anchor is the whole justification for the code. Landing on /configure would leave somebody
        // scrolling a long page on a phone to find the one row they came for, which is the work the code
        // was supposed to remove.
        assertEquals("/configure#cfg-ha-oauth", configureQrPath(AdmissionOutcome.SIGN_IN_REQUIRED))
        assertEquals("/configure#cfg-ha-oauth", configureQrPath(AdmissionOutcome.CREDENTIAL_REFUSED))
    }

    // --- and whether the address behind it is worth printing ---

    @Test fun `an address nobody could reach is not offered as one`() {
        // THE TRAP THIS EXISTS FOR. LocalAdminEndpoint.externalUrl falls back to 127.0.0.1 when it is
        // handed nothing, so a panel with no network produces a scannable, plausible and completely
        // useless code that opens the scanning phone's own loopback.
        assertNull(scannableHost(null, null))
        assertNull(scannableHost("127.0.0.1", null))
        assertNull(scannableHost("", ""))
        // A failed DHCP lease is the realistic case, and it looks like a LAN address to a naive check.
        assertNull(scannableHost("169.254.7.9", null))
        assertNull(scannableHost("0.0.0.0", null))
    }

    @Test fun `an ordinary home network address is offered`() {
        assertEquals("192.168.1.40", scannableHost("192.168.1.40", null))
        assertEquals("172.16.4.9", scannableHost("172.16.4.9", "fd00::1"))
        // IPv4 first, exactly as the URL builder prefers it, so the code and the printed address agree.
        assertEquals("192.168.1.40", scannableHost("192.168.1.40", "fd00::1"))
        assertEquals("fd00::1", scannableHost(null, "fd00::1"))
        assertEquals("fd00::1", scannableHost("127.0.0.1", "fd00::1"))
    }

    @Test fun `the reachability test is the opposite question from the request-source one`() {
        // isRoutable exists to reject a source that is not globally routable, so it rejects every address
        // a home panel actually has. Using it here would have hidden the code on every working panel and
        // shown it on none. These are the three families that genuinely fail.
        assertTrue(isScannableHost("192.168.1.40"))
        assertTrue(isScannableHost("172.16.4.9"))
        assertTrue(isScannableHost("fd00::1"))
        assertFalse(isScannableHost("127.0.0.1"))
        assertFalse(isScannableHost("169.254.7.9"))
        assertFalse(isScannableHost("0.0.0.0"))
        assertFalse("an unparseable value is not an address", isScannableHost("—"))
    }
}
