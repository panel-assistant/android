package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
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
                    for (card in listOf("managed-components", "apk-install", "uninstall-app", "vendor-packages", "display-sizing", "backup-restore")) {
                        assertTrue(card, html.contains("data-layout-key=\"$card\""))
                    }
                    assertTrue(html.contains("src=\"assets/install.js\""))
                    assertTrue(html.contains("value=\"200\" style=\"width:96px\"${if (root) "" else " disabled"}>"))
                    assertEquals(root, html.contains("id=\"apk-allow\" checked"))
                    assertEquals(root, html.contains("id=\"uninst-pkg\""))
                    assertTrue(html.contains("id=\"hand-back-home-button\""))
                    assertTrue(html.contains("href=\"api/v1/config/export\""))
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
}
