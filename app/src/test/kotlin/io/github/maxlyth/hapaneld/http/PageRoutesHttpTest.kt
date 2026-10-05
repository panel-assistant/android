package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRoutesHttpTest {

    @Test fun `missing browser pages show Pickles beneath production admission and retain machine errors`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            testApplication {
                application { fixture.mount(this) }
                val page = client.get("/missing-page?lang=de") { header(HttpHeaders.Accept, "text/html") }
                assertEquals(HttpStatusCode.NotFound, page.status)
                assertEquals("text/html; charset=UTF-8", page.headers[HttpHeaders.ContentType])
                val html = page.bodyAsText()
                assertTrue(html.contains("Pickles ist ausgebüxt"))
                assertTrue(html.contains("<code>/missing-page</code>"))
                assertTrue(html.contains("href=\"./?lang=de\""))
                assertEquals("DENY", page.headers["X-Frame-Options"])
                assertEquals("de", page.headers[HttpHeaders.ContentLanguage])
                val embedded = client.get("/missing-page") {
                    header(HttpHeaders.Accept, "text/html")
                    header(EmbedMode.HEADER, "v=1;theme=light;lang=fr")
                }.bodyAsText()
                assertTrue(embedded.contains("<base href=\"/\">"))
                assertTrue(embedded.contains("<html lang=\"fr\" data-theme=\"light\""))
                assertTrue(embedded.contains("href=\"info.css\""))
                assertTrue(embedded.contains("src=\"assets/pickles.svg\""))
                assertTrue(embedded.contains("href=\"./?lang=fr\""))
                for (path in listOf("/api/v1/missing", "/assets/missing.js", "/missing.css")) {
                    val machine = client.get(path) { header(HttpHeaders.Accept, "text/html") }
                    assertEquals(path, HttpStatusCode.NotFound, machine.status)
                    assertFalse(path, machine.bodyAsText().contains("Pickles"))
                }
                for (accept in listOf("application/json", "*/*", "text/html;q=0")) {
                    val machine = client.get("/missing-page") { header(HttpHeaders.Accept, accept) }
                    assertEquals(HttpStatusCode.NotFound, machine.status)
                    assertFalse(machine.bodyAsText().contains("Pickles"))
                }
                val write = client.post("/missing-page") { header(HttpHeaders.Accept, "text/html") }
                assertEquals(HttpStatusCode.NotFound, write.status)
                assertFalse(write.bodyAsText().contains("Pickles"))
                val refused = client.get("/missing-page") {
                    header(HttpHeaders.Accept, "text/html")
                    header(HttpHeaders.Host, "elsewhere.example")
                }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertEquals("host not allowed\n", refused.bodyAsText())
                val crossSite = client.post("/missing-page") {
                    header(HttpHeaders.Accept, "text/html")
                    header(HttpHeaders.Origin, "http://elsewhere.example")
                }
                assertEquals(HttpStatusCode.Forbidden, crossSite.status)
                assertEquals("cross-origin refused\n", crossSite.bodyAsText())
                val svg = client.get("/assets/pickles.svg")
                assertEquals(HttpStatusCode.OK, svg.status)
                assertEquals("image/svg+xml", svg.headers[HttpHeaders.ContentType]?.substringBefore(';'))
            }
        }
    }

    @Test fun `retired test page redirects old bookmarks under the real host guard`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            testApplication {
                application { fixture.mount(this) }
                val direct = createClient { followRedirects = false }
                val response = direct.get("/test?lang=de") { header(HttpHeaders.Cookie, "wiz_escape=1") }
                assertEquals(HttpStatusCode.Found, response.status)
                assertEquals("/", response.headers[HttpHeaders.Location])
                assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                val refused = direct.get("/test") { header(HttpHeaders.Host, "elsewhere.example") }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertEquals("host not allowed\n", refused.bodyAsText())
            }
        }
    }

    @Test fun `API specification is JSON at the canonical route and retains root admission`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.mount(this) }
                val response = client.get("/api/v1/openapi.json")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("application/json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                val spec = org.json.JSONObject(response.bodyAsText())
                assertEquals("3.0.3", spec.getString("openapi"))
                assertEquals("ha-paneld", spec.getJSONObject("info").getString("title"))
                assertTrue(spec.getJSONObject("paths").has("/api/v1/info"))
                val refused = client.get("/api/v1/openapi.json") { header(HttpHeaders.Host, "elsewhere.example") }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertEquals("host not allowed\n", refused.bodyAsText())
            }
        }
    }

    @Test fun `dashboard hydration retains localized JSON and root response guards`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.enableWarmDashboard()
            testApplication {
                application { fixture.mount(this) }
                val response = client.get("/api/v1/info?lang=zh-Hans")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("application/json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                assertTrue(response.headers.getAll(HttpHeaders.Vary).orEmpty().joinToString().contains("Accept-Language"))
                assertTrue(response.headers[HttpHeaders.ContentLanguage].orEmpty().contains("zh-Hans"))
                assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                val payload = org.json.JSONObject(response.bodyAsText())
                assertEquals(io.panelassistant.android.BuildConfig.VERSION_CODE, payload.getInt("versionCode"))
                assertTrue(payload.getJSONObject("cards").getString("infotbl").contains("Warm &lt;panel&gt;"))
                assertTrue(payload.getJSONObject("cards").getString("livetbl").contains("50% (128)"))
                val refused = client.get("/api/v1/info") { header(HttpHeaders.Host, "elsewhere.example") }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertEquals("host not allowed\n", refused.bodyAsText())
            }
        }
    }

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
                    val scripts = Regex("""<script src="assets/(configure[^"]*\.js)"></script>""").findAll(html)
                        .map { it.groupValues[1] }.toList()
                    assertEquals(listOf(
                        "configure-state.js", "configure-view.js", "configure-help.js", "configure-controls.js",
                        "configure-brightness.js", "configure-auto-sleep.js", "configure-cards.js", "configure-render.js", "configure.js",
                    ), scripts)
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
                    "logs" to "id=\"lg-out\"",
                    "fleet" to "http://&lt;its-ip&gt;:",
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
                val api = client.get("/api?lang=zh-Hans") {
                    header(HttpHeaders.Cookie, "wiz_escape=1")
                }
                assertEquals(HttpStatusCode.OK, api.status)
                assertTrue(api.bodyAsText().contains("Contract &lt;panel&gt;"))
                assertFalse(api.bodyAsText().contains("__API_I18N_PAYLOAD__"))
                val refusedApi = client.get("/api") {
                    header(HttpHeaders.Cookie, "wiz_escape=1")
                    header(HttpHeaders.Host, "elsewhere.example")
                }
                assertEquals(HttpStatusCode.Forbidden, refusedApi.status)
            }
        }
    }
}
