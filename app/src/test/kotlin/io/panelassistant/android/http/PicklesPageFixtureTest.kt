package io.panelassistant.android.http

import java.io.File
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

/** Real guarded page documents consumed by the cross-engine initial-load failure browser test. */
class PicklesPageFixtureTest {
    @Test fun `export localized production pages for browser failure journeys`() {
        val output = File("build/test-fixtures/pickles").apply { mkdirs() }
        val pages = listOf(
            "dashboard" to "/", "dashboard-warm" to "/", "dashboard-stale" to "/", "configure" to "/configure",
            "api" to "/api", "profiles" to "/profiles", "404" to "/missing-page",
        )
        for ((name, path) in pages) PaneldServerHttpFixture().use { fixture ->
            fixture.enablePages()
            when (name) {
                "dashboard" -> fixture.enableColdDashboard()
                "dashboard-warm", "dashboard-stale" -> {
                    fixture.enableWarmDashboard()
                    if (name == "dashboard-stale") {
                        val observations = PaneldServer::class.java.getDeclaredField("managementObservations").apply { isAccessible = true }
                            .get(fixture.server) as ManagementObservations
                        observations.snapCache.invalidate()
                    }
                }
                "configure" -> fixture.enableConfigurePage(false)
            }
            testApplication {
                application { fixture.mount(this) }
                for (locale in listOf("en", "de", "fr", "it", "es", "zh-Hans", "nl", "pl", "uk")) {
                    val response = client.get("$path?lang=$locale") {
                        header(HttpHeaders.Cookie, "wiz_escape=1")
                        header(HttpHeaders.Accept, "text/html")
                    }
                    assertEquals("$name/$locale", if (name == "404") HttpStatusCode.NotFound else HttpStatusCode.OK, response.status)
                    val html = response.bodyAsText()
                    if (name == "dashboard-stale") assertEquals(true, html.contains("data-hydrate=\"1\""))
                    File(output, "$name-$locale.html").writeText(html)
                }
            }
        }
    }
}
