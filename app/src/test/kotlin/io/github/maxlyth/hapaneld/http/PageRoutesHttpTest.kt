package io.github.maxlyth.hapaneld.http

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

class PageRoutesHttpTest {

    @Test fun `configure keeps proximity mounts conditional and root guards active`() {
        for (proximity in listOf(false, true)) {
            PaneldServerHttpFixture().use { fixture ->
                fixture.enablePages()
                fixture.enableConfigurePage(proximity)
                testApplication {
                    application { fixture.server.mount(this) }
                    val response = client.get("/configure?lang=en") {
                        header(HttpHeaders.Cookie, "wiz_escape=1")
                    }
                    assertEquals(HttpStatusCode.OK, response.status)
                    val html = response.bodyAsText()
                    assertTrue(html.contains("id=\"cfg-groups\""))
                    assertTrue(html.contains("id=\"savebar\""))
                    assertTrue(html.contains("src=\"assets/configure.js\""))
                    assertEquals(proximity, html.contains("id=\"proximity-learning-mount\""))
                    assertEquals(proximity, html.contains("src=\"assets/proximity-learning.js\""))
                    val refused = client.get("/configure") {
                        header(HttpHeaders.Cookie, "wiz_escape=1")
                        header(HttpHeaders.Host, "elsewhere.example")
                    }
                    assertEquals(HttpStatusCode.Forbidden, refused.status)
                }
            }
        }
    }

    @Test fun `entity learning renders all three tables only for enabled builtin dashboard`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            testApplication {
                application { fixture.server.mount(this) }
                suspend fun entities() = client.get("/entities?lang=en") {
                    header(HttpHeaders.Cookie, "wiz_escape=1")
                }
                val disabled = entities()
                assertEquals(HttpStatusCode.OK, disabled.status)
                assertFalse(disabled.bodyAsText().contains("id=\"entity-status\""))
                fixture.enableEntityPage()
                val enabled = entities()
                assertEquals(HttpStatusCode.OK, enabled.status)
                val html = enabled.bodyAsText()
                assertTrue(html.contains("id=\"entity-status\""))
                for (table in listOf("current", "suggested", "review")) {
                    assertTrue(html.contains("data-table=\"$table\""))
                }
                assertTrue(html.contains("src=\"assets/entities.js\""))
            }
        }
    }

    @Test fun `simple pages render localized shell and bodies through guarded production mount`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            testApplication {
                application { fixture.server.mount(this) }
                for ((path, marker) in listOf(
                    "setup" to "id=\"wiz-step\"",
                    "profiles" to "id=\"profile-editor\"",
                    "entities" to "entities.disabled.title",
                )) {
                    val response = client.get("/$path?lang=zh-Hans") {
                        header(HttpHeaders.Cookie, "wiz_escape=1")
                    }
                    assertEquals(path, HttpStatusCode.OK, response.status)
                    assertEquals("text/html; charset=UTF-8", response.headers[HttpHeaders.ContentType])
                    val html = response.bodyAsText()
                    assertTrue(path, html.contains("<html lang=\"zh-Hans\""))
                    assertTrue(path, html.contains("Contract &lt;panel&gt;"))
                    assertTrue(path, html.contains("href=\"configure?lang=zh-Hans\""))
                    assertTrue(path, html.contains(marker))
                    assertEquals("DENY", response.headers["X-Frame-Options"])
                    assertTrue(response.headers.getAll(HttpHeaders.Vary).orEmpty().joinToString().contains("Accept-Language"))
                    val refused = client.get("/$path") {
                        header(HttpHeaders.Cookie, "wiz_escape=1")
                        header(HttpHeaders.Host, "elsewhere.example")
                    }
                    assertEquals(HttpStatusCode.Forbidden, refused.status)
                    assertEquals("host not allowed\n", refused.bodyAsText())
                }
                val setup = client.get("/setup?lang=en").bodyAsText()
                assertTrue(setup.contains("href=\"configure?lang=en\""))
                assertTrue(setup.contains("wiz_escape=1;path=/;max-age=3600"))
                assertFalse(setup.contains("data-cfg="))
            }
        }
    }
}
