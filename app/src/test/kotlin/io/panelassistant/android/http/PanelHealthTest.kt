package io.panelassistant.android.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelHealthTest {
    @Test fun parsesChromiumMajor() {
        assertEquals(107, PanelHealth.chromiumMajor("com.android.webview 107.0.5304.105"))
        assertEquals(138, PanelHealth.chromiumMajor("com.android.webview 138.0.7204.63"))
        assertNull(PanelHealth.chromiumMajor("unknown"))
        assertNull(PanelHealth.chromiumMajor("com.android.webview"))
    }

    @Test fun parsesEngineVersionFromUserAgent() {
        // Cromite SystemWebView on the TPA10: package stamps the OEM 83, UA reports the real 147.
        val ua = "Mozilla/5.0 (Linux; Android 11; TPA10) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/147.0.7727.56 Mobile Safari/537.36"
        assertEquals("147.0.7727.56", PanelHealth.engineVersionFromUa(ua))
        assertNull(PanelHealth.engineVersionFromUa("no chrome token here"))
    }

    @Test fun engineVersionWinsOverStampedPackageVersion() {
        // The exact false-positive being fixed: package says 83 (stamped), engine is really 147.
        assertFalse(
            "Cromite: stamped 83 but real engine 147 → NOT too old",
            PanelHealth.webViewTooOld("com.android.webview 83.0.4103.120", engineMajor = 147),
        )
    }

    @Test fun genuinelyOldEngineStillFlagged() {
        assertTrue(
            "real engine 83 → too old",
            PanelHealth.webViewTooOld("com.android.webview 83.0.4103.120", engineMajor = 83),
        )
    }

    @Test fun fallsBackToPackageVersionWhenEngineUnknown() {
        // UA unreadable (engineMajor null) → fall back to the package version.
        assertTrue("107 package, no UA → flag", PanelHealth.webViewTooOld("com.android.webview 107.0.5304.105", null))
        assertFalse("138 package, no UA → ok", PanelHealth.webViewTooOld("com.android.webview 138.0.7204.63", null))
        assertFalse("threshold boundary 110 → ok", PanelHealth.webViewTooOld("com.android.webview 110.0.0.0", null))
        assertFalse("unparseable + no UA → don't cry wolf", PanelHealth.webViewTooOld("unknown", null))
    }

    @Test fun identifiesOnlyGooglePlayAsAPlayManagedInstallSource() {
        assertTrue(PanelInfo.isPlayStoreInstaller("com.android.vending"))
        assertFalse(PanelInfo.isPlayStoreInstaller("com.google.android.packageinstaller"))
        assertFalse(PanelInfo.isPlayStoreInstaller(null))
    }
}
