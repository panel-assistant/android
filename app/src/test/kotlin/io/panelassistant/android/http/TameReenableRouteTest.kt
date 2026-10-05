package io.panelassistant.android.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TameReenableRouteTest {
    @Test fun `localized form errors require an explicit HTML accept type`() {
        assertFalse(installFormWantsHtml(null))
        assertFalse(installFormWantsHtml("*/*"))
        assertFalse(installFormWantsHtml("application/json"))
        assertTrue(installFormWantsHtml("text/html,application/xhtml+xml"))
        assertTrue(installFormWantsHtml("TEXT/HTML"))
    }
}
