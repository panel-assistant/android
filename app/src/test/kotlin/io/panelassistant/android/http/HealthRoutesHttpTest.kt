package io.panelassistant.android.http

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.Config
import io.panelassistant.android.setupEverCompleted
import io.panelassistant.android.preservePreviousHome
import io.panelassistant.android.completeHomeTakeover
import io.panelassistant.android.platform.ActivityRef
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

class HealthRoutesHttpTest {
    @Test fun `health reports first completion live and retains it during repair`() {
        PaneldServerHttpFixture().use { fixture ->
            initializeHealth(fixture)
            testApplication {
                application { fixture.mount(this) }
                assertTrue(client.get("/health").bodyAsText().contains(" setup_complete=0 "))
                val original = ActivityRef("com.vendor.launcher", "com.vendor.launcher.Home")
                assertTrue(fixture.config.preservePreviousHome(original, listOf(original), armTakeover = true))
                fixture.config.setupEverCompleted = true
                for (path in listOf("/health", "/api/v1/health")) {
                    assertTrue(client.get(path).bodyAsText().contains(" setup_complete=1 home_pending=1 "))
                }
                assertTrue(fixture.config.completeHomeTakeover())
                assertTrue(client.get("/health").bodyAsText().contains(" home_pending=0 "))
                // Completion remains durable even with no working sign-in; permission reconciliation
                // must not remove an established panel's screen-lock fallback during a repair.
                assertTrue(fixture.config.setupEverCompleted)
            }
        }
    }

    @Test fun `root and v1 health retain tokens and real root admission`() {
        PaneldServerHttpFixture().use { fixture ->
            initializeHealth(fixture)
            testApplication {
                application { fixture.mount(this) }
                val direct = createClient { followRedirects = false }
                val root = direct.get("/health")
                val api = direct.get("/api/v1/health")
                for (response in listOf(root, api)) {
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals("text/plain", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                    assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                    assertEquals("DENY", response.headers["X-Frame-Options"])
                    assertEquals(null, response.headers[HttpHeaders.Location])
                }
                val line = root.bodyAsText()
                assertEquals(line, api.bodyAsText())
                assertTrue(line, line.startsWith("ha-paneld ${Config.VERSION} panel=contract-panel build=${Config.VERSION} cfg="))
                assertTrue(line, Regex(" cfg=[0-9a-f]{8} ").containsMatchIn(line))
                assertTrue(line, line.contains(" pkg=io.github.maxlyth.hapaneld vc=${BuildConfig.VERSION_CODE} proto=5-5 "))
                assertTrue(line, line.endsWith(" pa_restarting=app,settings,29000\n"))
                val refused = direct.get("/health") { header(HttpHeaders.Host, "elsewhere.example") }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertEquals("host not allowed\n", refused.bodyAsText())
            }
        }
    }

    @Test fun `the panel's own DHCP host name passes the host guard and other names do not`() {
        // A stock NSPanel Pro registers itself in the router's DNS by its own host name; the page and the
        // Home Assistant integration reach it by that name with no operator allowlist configured.
        val own = { OwnHostNames.names(listOf("Sonoff-NSPanelPro", "NSPanel Pro"), listOf("home.example.com.")) }
        PaneldServerHttpFixture(ownHostNames = own).use { fixture ->
            initializeHealth(fixture)
            testApplication {
                application { fixture.mount(this) }
                val direct = createClient { followRedirects = false }
                for (host in listOf(
                    "sonoff-nspanelpro.home.example.com:8888",
                    "SONOFF-NSPANELPRO.Home.Example.Com",
                    "sonoff-nspanelpro:8888",
                    "nspanel-pro.home.example.com:8888",
                )) {
                    val response = direct.get("/api/v1/health") { header(HttpHeaders.Host, host) }
                    assertEquals(host, HttpStatusCode.OK, response.status)
                }
                for (host in listOf(
                    "attacker.example:8888",
                    "sonoff-nspanelpro.attacker.example",
                    "home.example.com",
                    "x.sonoff-nspanelpro.home.example.com",
                )) {
                    val response = direct.get("/api/v1/health") { header(HttpHeaders.Host, host) }
                    assertEquals(host, HttpStatusCode.Forbidden, response.status)
                    assertEquals("host not allowed\n", response.bodyAsText())
                }
            }
        }
    }

    @Test fun `a broker-era upgrade that never met Panel Assistant gets no notice on its pages or health`() {
        PaneldServerHttpFixture().use { fixture ->
            fixture.values.putAll(BROKER_ERA_STORE)
            initializeHealth(fixture)
            fixture.enablePages()
            assertTrue(fixture.config.migrateLiveStore())
            testApplication {
                application { fixture.mount(this) }
                for (path in listOf("/health", "/api/v1/health")) {
                    val line = client.get(path).bodyAsText()
                    assertFalse(line, line.contains("pa_notice"))
                }
                val page = client.get("/configure?lang=en") { header(HttpHeaders.Cookie, "wiz_escape=1") }.bodyAsText()
                assertFalse(page.contains("migrationbar"))
                assertFalse(page.contains("go/migration"))
                assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/migration-notice/dismiss").status)
            }
        }
    }

    private companion object {
        /** What a 0.9.11 panel that ran the broker client leaves in its store. */
        val BROKER_ERA_STORE = mapOf<String, Any?>(
            "config_schema" to 14,
            "mqtt_broker" to "tcp://ha.local:1883",
            "last_discovery_version" to "0.9.11",
        )
    }

    private fun initializeHealth(fixture: PaneldServerHttpFixture) {
        fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Contract panel")
        // Fill the existing fixture's runtime collaborators, not an alternate route or admission path.
        PaneldServer::class.java.getDeclaredField("configLiveValues").apply {
            isAccessible = true
        }.set(fixture.server, { emptyMap<String, String>() })
        PaneldServer::class.java.getDeclaredField("panelAssistantTransport").apply {
            isAccessible = true
        }.set(fixture.server, TestTransportOwners.idle().apply { announceRestart("app", "settings", 29_000L) })
    }
}
