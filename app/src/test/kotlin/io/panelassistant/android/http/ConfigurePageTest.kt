package io.panelassistant.android.http

import io.panelassistant.android.i18n.CatalogueLoader
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurePageTest {
    // Source-text reason: loads the shipped i18n catalogues as input data.
    private val strings = CatalogueLoader { path -> File("src/main/assets", path).readText() }.strings("en")

    @Test fun cameraHelpReceivesThePanelEndpointAsEscapedPageContext() {
        for ((endpoint, attribute) in listOf(
            "http://192.0.2.10:8888/" to "http://192.0.2.10:8888/",
            "http://[fd00::1234]:8888/" to "http://[fd00::1234]:8888/",
            "http://panel.test/?a=1&b=\"two\"" to "http://panel.test/?a=1&amp;b=&quot;two&quot;",
            null to "",
        )) {
            val html = configureBody(strings, false, "", endpoint)
            assertTrue(html, html.contains("id=\"cfg-help\" class=\"cfg-help\" data-panel-url=\"$attribute\""))
        }
    }
}
