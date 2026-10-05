package io.panelassistant.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardDebuggingPolicyTest {
    @Test fun `WebView debugging requires network ADB and Relaxed mode`() {
        assertTrue(shouldEnableWebViewDebugging(networkAdbEnabled = true, hardenedSecurityEnabled = false))
        assertFalse(shouldEnableWebViewDebugging(networkAdbEnabled = false, hardenedSecurityEnabled = false))
        assertFalse(shouldEnableWebViewDebugging(networkAdbEnabled = true, hardenedSecurityEnabled = true))
        assertFalse(shouldEnableWebViewDebugging(networkAdbEnabled = false, hardenedSecurityEnabled = true))
    }
}
