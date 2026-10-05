package io.panelassistant.android.http

import io.panelassistant.android.dashboard.EntityCatalogStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PanelInfoDisplayTest {
    @Test fun separatesLogicalDensityFromProfiledPhysicalPpi() {
        val text = PanelInfo.displaySummary(1920, 1200, 212, 226)

        assertEquals("1920×1200 px · logical 212 dpi · physical ≈226 ppi", text)
        assertFalse(text.contains("native"))
    }

    @Test fun omitsPhysicalPpiWhenProfileEvidenceIsUnknown() {
        assertEquals("480×480 px · logical 160 dpi", PanelInfo.displaySummary(480, 480, 160, null))
    }

    @Test fun databaseSizesUseFamiliarAdaptiveUnits() {
        assertEquals("0 B", PanelInfo.formatDisplayBytes(0))
        assertEquals("1023 B", PanelInfo.formatDisplayBytes(1023))
        assertEquals("1.0 KB", PanelInfo.formatDisplayBytes(1024))
        assertEquals("1.5 MB", PanelInfo.formatDisplayBytes(1_572_864))
        assertEquals("2.0 GB", PanelInfo.formatDisplayBytes(2_147_483_648))
    }

    @Test fun databaseSummaryDistinguishesLiveDataFromDiskFootprint() {
        assertEquals(
            "1.5 MB used · 2.0 MB on disk · schema 11",
            PanelInfo.databaseSummary(EntityCatalogStore.DatabaseUsage(1_572_864, 2_097_152, 11)),
        )
    }

    @Test fun effectiveWebViewEngineLeadsWhenPackageRetainsOldCompatibilityStamp() {
        val presentation = PanelInfo.webViewPresentation(
            packageSummary = "com.android.webview 83.0.4103.120",
            packageMajor = 83,
            engineVersion = "150.0.7871.63",
            engineMajor = 150,
        )

        assertEquals("Chromium 150.0.7871.63 rendering engine", presentation.first)
        assertEquals("System reports 83.0.4103.120 · provider compatibility quirk", presentation.second)
    }

    @Test fun ordinaryWebViewPackageDisplayIsUnchanged() {
        val presentation = PanelInfo.webViewPresentation(
            packageSummary = "com.android.webview 150.0.7871.63",
            packageMajor = 150,
            engineVersion = null,
            engineMajor = null,
        )

        assertEquals("com.android.webview 150.0.7871.63", presentation.first)
        assertNull(presentation.second)
    }
}
