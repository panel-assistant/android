package io.github.maxlyth.hapaneld.http

import org.junit.Assert.*
import org.junit.Test

class ProximityUiAdmissionTest {
    @Test fun sameOriginPanelUiCanOpenIntroduction() {
        assertTrue(proximityUiRequestAllowed("http://192.168.1.2:8888", null, "192.168.1.2:8888", "same-origin", "1"))
        assertTrue(proximityUiRequestAllowed(null, "http://192.168.1.2:8888/configure", "192.168.1.2:8888", null, "1"))
    }
    @Test fun unattendedAndCrossOriginCallsAreRefused() {
        assertFalse(proximityUiRequestAllowed(null, null, "192.168.1.2:8888", null, "1"))
        assertFalse(proximityUiRequestAllowed("http://untrusted.test", null, "192.168.1.2:8888", "cross-site", "1"))
        assertFalse(proximityUiRequestAllowed("http://192.168.1.2:8888", null, "192.168.1.2:8888", "same-site", "1"))
        assertFalse(proximityUiRequestAllowed("http://192.168.1.2:8888", null, "192.168.1.2:8888", "same-origin", null))
        assertFalse(proximityUiRequestAllowed("null", null, "192.168.1.2:8888", null, "1"))
    }
    @Test fun sidebarEmbedIsAdmittedOnlyWithTheProxyAssertedMetadata() {
        assertTrue(proximityUiRequestAllowed(null, null, "192.168.1.2:8888", "same-origin", null, "v=1"))
        assertFalse(proximityUiRequestAllowed(null, null, "192.168.1.2:8888", null, null, "v=1"))
        assertFalse(proximityUiRequestAllowed(null, null, "192.168.1.2:8888", "same-origin", null, "bogus"))
        assertFalse(proximityUiRequestAllowed("http://untrusted.test", null, "192.168.1.2:8888", "same-origin", null, "v=1"))
    }
}
