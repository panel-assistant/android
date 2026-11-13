package io.panelassistant.android.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardPageHttpTest {
    @Test fun `warm dashboard renders escaped facts private values and live diagnostic rows`() {
        // A density route that can set density, so the off-100 zoom banner is due.
        val density = io.panelassistant.android.control.DensityController(
            false,
            io.panelassistant.android.control.FakeRootShell(available = false, runResult = false),
            io.panelassistant.android.control.FakeDaemon(available = false),
            io.panelassistant.android.control.FakeAppSizing(),
        )
        PaneldServerHttpFixture(density = density).use { fixture ->
            fixture.enablePages()
            fixture.enableWarmDashboard()
            fixture.config.setDashboardZoom(125)
            testApplication {
                application { fixture.server.mount(this) }
                val response = client.get("/?lang=en") {
                    header(HttpHeaders.Cookie, "wiz_escape=1")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val html = response.bodyAsText()
                assertTrue(html.contains("data-hydrate=\"0\""))
                // In the sidebar the Diagnostics links work only through the page transport (data-op); on the LAN the href serves.
                assertEquals(Regex("href=\"api/v1/diag\"").findAll(html).count(), Regex("href=\"api/v1/diag\" data-op=\"diag\" data-relay-only data-file=\"diag.txt\"").findAll(html).count())
                assertTrue(html.contains("data-op=\"diag\""))
                assertTrue(html.contains("Warm &lt;panel&gt;"))
                assertTrue(html.contains("<span class=\"secret\">secret-value</span>"))
                assertTrue(html.contains("id=\"halifecell\""))
                assertTrue(html.contains("id=\"hanetcell\""))
                assertTrue(html.contains("50% (128)"))
                assertTrue(html.contains("name=\"dashboard_zoom\" value=\"100\""))
                assertTrue(html.contains("action=\"api/v1/config\""))
                val hydration = client.get("/api/v1/info?lang=en")
                assertEquals(HttpStatusCode.OK, hydration.status)
                val payload = org.json.JSONObject(hydration.bodyAsText())
                val cards = payload.getJSONObject("cards")
                assertTrue(cards.getString("infotbl").contains("Warm &lt;panel&gt;"))
                assertTrue(cards.getString("infotbl").contains("<span class=\"secret\">secret-value</span>"))
                assertTrue(cards.getString("contexttbl").contains("id=\"halifecell\""))
                assertTrue(cards.getString("livetbl").contains("50% (128)"))
                assertTrue(payload.getString("banners").contains("name=\"dashboard_zoom\" value=\"100\""))
            }
        }
    }

    @Test fun `cold dashboard renders hydration shell without management probes`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.enableColdDashboard()
            testApplication {
                application { fixture.server.mount(this) }
                val response = client.get("/?lang=en") {
                    header(HttpHeaders.Cookie, "wiz_escape=1")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                val html = response.bodyAsText()
                assertTrue(html.contains("data-hydrate=\"cold\""))
                assertTrue(html.contains("data-capture-ok=\"0\""))
                assertTrue(html.contains("id=\"infotbl\""))
                assertTrue(html.contains("id=\"contexttbl\""))
                assertTrue(html.contains("Contract &lt;panel&gt;"))
                val refused = client.get("/") {
                    header(HttpHeaders.Cookie, "wiz_escape=1")
                    header(HttpHeaders.Host, "elsewhere.example")
                }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
            }
        }
    }

    // The sidebar cannot load the last capture's URL itself: the page asks the transport for it by the id.
    /** One page as the sidebar's embed link gets it: a `page.<name>` manage over the panel's session. */
    private fun relayedPage(fixture: PaneldServerHttpFixture, name: String): String {
        val answer = java.util.concurrent.LinkedBlockingQueue<io.panelassistant.android.ManageReply>()
        fixture.door.manage(
            java.util.UUID.randomUUID().toString(), "page.$name", org.json.JSONObject().put("embed", "v=1;lang=en"),
            io.panelassistant.android.CommandOrigin.user("0123456789abcdef0123456789abcdef").withConnection("relay-1"),
        ) { answer.put(it) }
        return requireNotNull(answer.poll(20, java.util.concurrent.TimeUnit.SECONDS)).result as String
    }

    @Test fun `in the sidebar the screenshot card names the last capture instead of loading it`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.enableWarmDashboard()
            fixture.useInteractive(io.panelassistant.android.control.InteractiveController(
                canSu = false,
                root = io.panelassistant.android.control.FakeRootShell(available = false, runResult = false),
                daemon = io.panelassistant.android.control.FakeDaemon(available = false),
                accessibility = object : io.panelassistant.android.platform.AccessibilityActions {
                    override fun back() = false
                    override fun recents() = false
                    override fun tap(x: Int, y: Int) = false
                    override fun canScreenshot() = true
                },
            ))
            val id = requireNotNull(ScreenshotCache(fixture.directory).store(byteArrayOf(1, 2, 3)))
            val url = "api/v1/screenshot.png?cached=$id"
            testApplication {
                application { fixture.mount(this) }
                startApplication()
                val hydration = org.json.JSONObject(client.get("/api/v1/info?lang=en").bodyAsText())
                val lan = client.get("/?lang=en") { header(HttpHeaders.Cookie, "wiz_escape=1") }.bodyAsText()
                val relayed = relayedPage(fixture, "info")
                val card = { html: String -> requireNotNull(Regex("<div class=\"card\" id=\"shotcard\"[^>]*>.*?</a></div>").find(html)) { html }.value }
                assertTrue(card(lan).contains("src=\"$url\""))
                // The sidebar's page is the cold shell; hydration over the relay names the last capture.
                assertEquals(url, hydration.getString("shotCached"))
                assertTrue("the sidebar's browser never asks for an address it cannot reach", !card(relayed).contains("<img src="))
            }
        }
    }

    @Test fun `a page rendered for the sidebar's embed link carries no panel facts or address`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.enableWarmDashboard()
            testApplication {
                application { fixture.mount(this) }
                startApplication()
                client.get("/api/v1/info?lang=en")
                val lan = client.get("/?lang=en") { header(HttpHeaders.Cookie, "wiz_escape=1") }.bodyAsText()
                assertTrue("the LAN page is warm, so the check below can fail", lan.contains("Warm &lt;panel&gt;"))
                assertTrue(lan.contains("<span class=\"secret\">secret-value</span>"))
                // Anyone holding the embed link gets this document, so the facts must come only by hydration
                // over the administrator's own connection.
                val relayed = relayedPage(fixture, "info")
                assertTrue(relayed.contains("data-hydrate=\"cold\""))
                assertTrue(relayed.contains("id=\"infotbl\""))
                assertTrue(!relayed.contains("Warm &lt;panel&gt;"))
                assertTrue(!relayed.contains("secret-value"))
                assertTrue(!relayed.contains("halifecell"))
                val panelUrl = { html: String -> requireNotNull(Regex("id=\"cfg-help\" class=\"cfg-help\" data-panel-url=\"([^\"]*)\"").find(html)) { html }.groupValues[1] }
                val lanConfigure = client.get("/configure?lang=en") { header(HttpHeaders.Cookie, "wiz_escape=1") }.bodyAsText()
                assertTrue("the LAN Configure page names the panel's address", panelUrl(lanConfigure).isNotEmpty())
                assertEquals("", panelUrl(relayedPage(fixture, "configure")))
            }
        }
    }

    @Test fun `a panel without root offers the screenshot only where the accessibility capture serves`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            fixture.enableWarmDashboard()
            testApplication {
                application { fixture.server.mount(this) }
                suspend fun shot() = org.json.JSONObject(client.get("/api/v1/info?lang=en").bodyAsText()).getBoolean("shot")
                assertEquals(false, shot())
                val without = client.get("/?lang=en") { header(HttpHeaders.Cookie, "wiz_escape=1") }.bodyAsText()
                assertTrue(without.contains("id=\"infotbl\""))
                assertTrue(!without.contains("id=\"shotcard\""))
                fixture.useInteractive(io.panelassistant.android.control.InteractiveController(
                    canSu = false,
                    root = io.panelassistant.android.control.FakeRootShell(available = false, runResult = false),
                    daemon = io.panelassistant.android.control.FakeDaemon(available = false),
                    accessibility = object : io.panelassistant.android.platform.AccessibilityActions {
                        override fun back() = false
                        override fun recents() = false
                        override fun tap(x: Int, y: Int) = false
                        override fun canScreenshot() = true
                    },
                ))
                assertEquals(true, shot())
                val html = client.get("/?lang=en") { header(HttpHeaders.Cookie, "wiz_escape=1") }.bodyAsText()
                assertTrue(html.contains("id=\"shotcard\" data-layout-key=\"screenshot\" data-capture-ok=\"1\""))
            }
        }
    }
}
