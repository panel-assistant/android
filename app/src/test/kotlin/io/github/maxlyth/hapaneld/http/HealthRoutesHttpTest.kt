package io.github.maxlyth.hapaneld.http

import io.panelassistant.android.BuildConfig
import io.github.maxlyth.hapaneld.Config
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
                assertTrue(line, line.contains(" pkg=io.github.maxlyth.hapaneld vc=${BuildConfig.VERSION_CODE}"))
                assertTrue(line, line.endsWith(" restart=contract pa_notice=1\n"))
                val refused = direct.get("/health") { header(HttpHeaders.Host, "elsewhere.example") }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertEquals("host not allowed\n", refused.bodyAsText())
            }
        }
    }

    @Test fun `migration dismissal is origin guarded durable and reflected on both health routes`() {
        PaneldServerHttpFixture().use { fixture ->
            initializeHealth(fixture)
            testApplication {
                application { fixture.mount(this) }
                val refused = client.post("/api/v1/migration-notice/dismiss") {
                    header(HttpHeaders.Origin, "http://elsewhere.example")
                }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertTrue(fixture.config.migrationNoticeVisible())
                repeat(2) {
                    val dismissed = client.post("/api/v1/migration-notice/dismiss")
                    assertEquals(HttpStatusCode.OK, dismissed.status)
                    assertEquals("application/json", dismissed.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                    assertEquals("{\"ok\":true}", dismissed.bodyAsText())
                    assertFalse(fixture.config.migrationNoticeVisible())
                }
                for (path in listOf("/health", "/api/v1/health")) {
                    assertTrue(client.get(path).bodyAsText().endsWith(" pa_notice=0\n"))
                }
            }
        }
    }

    private fun initializeHealth(fixture: PaneldServerHttpFixture) {
        fixture.config.setFriendlyName("Contract panel")
        // Fill the existing fixture's runtime collaborators, not an alternate route or admission path.
        PaneldServer::class.java.getDeclaredField("configLiveValues").apply {
            isAccessible = true
        }.set(fixture.server, { emptyMap<String, String>() })
        PaneldServer::class.java.getDeclaredField("panelAssistantRestartHealth").apply {
            isAccessible = true
        }.set(fixture.server, { " restart=contract" })
    }
}
