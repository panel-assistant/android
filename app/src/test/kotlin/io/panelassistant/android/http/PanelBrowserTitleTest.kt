package io.panelassistant.android.http

import kotlin.test.assertEquals
import org.junit.Test

class PanelBrowserTitleTest {
    @Test
    fun everyBrowserTitleShowsVersionThenBuildAfterThePanelAndSection() {
        for (version in listOf("0.9.9", "1.0.0-rc1")) {
            assertEquals("Example Panel · $version (1136)", panelBrowserTitle("Example Panel", versionName = version, versionCode = 1136))
            assertEquals("Example Panel · Configure · $version (1136)", panelBrowserTitle("Example Panel", "Configure", version, 1136))
            assertEquals("Example Panel · REST API · $version (1136)", panelBrowserTitle("Example Panel", "REST API", version, 1136))
            assertEquals("ha-paneld · $version (1136)", panelBrowserTitle("   ", versionName = version, versionCode = 1136))
        }
    }
}
