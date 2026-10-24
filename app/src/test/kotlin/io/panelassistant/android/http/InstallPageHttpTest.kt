package io.panelassistant.android.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallPageHttpTest {
    @Test fun `install renders real privilege gated cards and rejects foreign hosts`() {
        for (root in listOf(false, true)) {
            PaneldServerHttpFixture().use { fixture ->
                fixture.enablePages()
                fixture.enableInstallPage(root)
                fixture.config.setApkUploadAllowed(true)
                testApplication {
                    application { fixture.server.mount(this) }
                    val response = client.get("/install?lang=en") {
                        header(HttpHeaders.Cookie, "wiz_escape=1")
                    }
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals("text/html; charset=UTF-8", response.headers[HttpHeaders.ContentType])
                    val html = response.bodyAsText()
                    assertFalse(html.contains("data-name=\"paneld\""))
                    assertTrue(html.contains("data-name=\"companion\""))
                    for (card in listOf("managed-components", "apk-install", "uninstall-app", "vendor-packages", "display-sizing", "backup-restore")) {
                        assertTrue(card, html.contains("data-layout-key=\"$card\""))
                    }
                    assertTrue(html.contains("src=\"assets/install.js\""))
                    assertEquals(1, Regex("class=\"cchan\"").findAll(html).count())
                    assertEquals(1, Regex("value=\"stable\" selected").findAll(html).count())
                    assertTrue(html.contains("value=\"prerelease\""))
                    assertFalse(html.contains("System WebView</b>"))
                    assertFalse(html.contains("healWebView("))
                    assertFalse(html.contains("installComp('webview'"))
                    assertTrue(html.contains("value=\"200\" style=\"width:96px\"${if (root) "" else " disabled"}>"))
                    assertEquals(root, html.contains("id=\"apk-allow\" checked"))
                    assertEquals(root, html.contains("id=\"uninst-pkg\""))
                    assertTrue(html.contains("id=\"hand-back-home-button\""))
                    assertTrue(html.contains("href=\"api/v1/config/export\""))
                    assertTrue(html.contains("href=\"api/v1/diag\" data-op=\"diag\" data-relay-only data-file=\"diag.txt\""))
                    val refused = client.get("/install") {
                        header(HttpHeaders.Cookie, "wiz_escape=1")
                        header(HttpHeaders.Host, "elsewhere.example")
                    }
                    assertEquals(HttpStatusCode.Forbidden, refused.status)
                    assertEquals("host not allowed\n", refused.bodyAsText())
                }
            }
        }
    }

    @Test fun `a rootless panel unlocks display sizing only where the app holds both grants`() {
        for (granted in listOf(false, true)) {
            val density = io.panelassistant.android.control.DensityController(
                false,
                io.panelassistant.android.control.FakeRootShell(available = false, runResult = false),
                io.panelassistant.android.control.FakeDaemon(available = false),
                io.panelassistant.android.control.FakeAppSizing(granted = granted),
            )
            PaneldServerHttpFixture(density = density).use { fixture ->
                fixture.enablePages()
                fixture.enableInstallPage(root = false)
                testApplication {
                    application { fixture.server.mount(this) }
                    val html = client.get("/install?lang=en") { header(HttpHeaders.Cookie, "wiz_escape=1") }.bodyAsText()
                    assertTrue(html.contains("data-layout-key=\"display-sizing\""))
                    assertEquals(!granted, html.contains("value=\"200\" style=\"width:96px\" disabled>"))
                    assertEquals(granted, html.contains("value=\"200\" style=\"width:96px\">"))
                }
            }
        }
    }
}
