package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.i18n.CatalogueLoader
import io.github.maxlyth.hapaneld.util.HaLink
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.server.testing.testApplication
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HaOAuthProductionHttpTest {
    @Test fun `unconfigured OAuth status is a passive no-store read`() = withOAuth { fixture, _ ->
        testApplication {
            application { fixture.mount(this) }
            repeat(2) {
                val response = client.get("/api/v1/ha/oauth/status")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("no-store", response.headers["Cache-Control"])
                assertEquals("{\"phase\":\"not_configured\"}", response.bodyAsText())
            }
        }
    }

    @Test fun `cancelled browser sign-in consumes its state without saving credentials`() = withOAuth { fixture, exchanges ->
        testApplication {
            application { fixture.mount(this) }
            val start = client.submitForm(
                "/api/v1/ha/oauth/start",
                Parameters.build {
                    append("ha_url", "http://ha.example:8123")
                    append("return_surface", "setup")
                    append("ui_locale", "en")
                },
            ) { header(HttpHeaders.Host, "127.0.0.1:8888") }
            assertEquals(HttpStatusCode.OK, start.status)
            val authorization = Url(JSONObject(start.bodyAsText()).getString("authorization_url"))
            assertEquals("ha.example", authorization.host)
            val state = requireNotNull(authorization.parameters["state"])
            val callback = "/api/v1/ha/oauth/callback?state=$state&error=access_denied"
            val cancelled = client.get(callback) { header(HttpHeaders.Host, "127.0.0.1:8888") }
            assertEquals(HttpStatusCode.BadRequest, cancelled.status)
            assertTrue(cancelled.bodyAsText().contains("Home Assistant sign-in was cancelled."))
            assertTrue(cancelled.bodyAsText().contains("Back to Setup"))
            val replay = client.get(callback) { header(HttpHeaders.Host, "127.0.0.1:8888") }
            assertTrue(replay.bodyAsText().contains("expired or was already used"))
            assertEquals(0, exchanges.size)
            assertEquals("", fixture.config.haToken)
            assertEquals("", fixture.config.haUrl)
        }
    }

    @Test fun `rejected code reaches the exchange once and never changes the HA owner`() = withOAuth { fixture, exchanges ->
        testApplication {
            application { fixture.mount(this) }
            val start = client.submitForm(
                "/api/v1/ha/oauth/start",
                Parameters.build { append("ha_url", "http://ha.example:8123") },
            ) { header(HttpHeaders.Host, "127.0.0.1:8888") }
            assertEquals(HttpStatusCode.OK, start.status)
            val state = Url(JSONObject(start.bodyAsText()).getString("authorization_url")).parameters["state"]
            val callback = "/api/v1/ha/oauth/callback?state=$state&code=fixture-code"
            val rejected = client.get(callback) { header(HttpHeaders.Host, "127.0.0.1:8888") }
            assertEquals(HttpStatusCode.BadRequest, rejected.status)
            assertTrue(rejected.bodyAsText().contains("Home Assistant did not accept this sign-in."))
            client.get(callback) { header(HttpHeaders.Host, "127.0.0.1:8888") }
            assertEquals(listOf("http://ha.example:8123|fixture-code|http://127.0.0.1:8888/"), exchanges)
            assertEquals("", fixture.config.haToken)
            assertEquals("", fixture.config.haRefreshToken)
        }
    }

    private fun withOAuth(block: (PaneldServerHttpFixture, MutableList<String>) -> Unit) {
        PaneldServerHttpFixture().use { fixture ->
            fun field(name: String, value: Any) {
                PaneldServer::class.java.getDeclaredField(name).apply { isAccessible = true }
                    .set(fixture.server, value)
            }
            val exchanges = mutableListOf<String>()
            val exchange: (String, String, String) -> HaLink.AuthorizationCodeExchange = { url, code, client ->
                exchanges += "$url|$code|$client"
                HaLink.AuthorizationCodeExchange.Rejected
            }
            // Source-text reason: exercise the production callback copy from the packaged catalogue.
            field("haOAuth", HaOAuthRuntime(
                fixture.config,
                { CatalogueLoader { File("src/main/assets", it).readText() } },
                exchange,
                AutoBrightnessHttpApi.UNAVAILABLE,
                { _, _, _ -> error("Rejected exchange must not commit") },
                { error("Rejected exchange must not evaluate setup") },
            ))
            block(fixture, exchanges)
        }
    }
}
