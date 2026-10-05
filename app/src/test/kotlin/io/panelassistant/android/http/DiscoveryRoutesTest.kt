package io.panelassistant.android.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryRoutesTest {
    @Test fun `panel and app chooser routes keep JSON and root request guards`() = testApplication {
        application {
            paneldRoot({ emptySet() }, { false }, { "/setup" }) {
                route("/api/v1") {
                    discoveryRoutes(
                        peersJson = { """{"peers":[{"name":"Kitchen"}]}""" },
                        appsJson = { """{"apps":[{"label":"Dashboard"}]}""" },
                    )
                }
            }
        }
        val peers = client.get("/api/v1/peers")
        assertEquals(HttpStatusCode.OK, peers.status)
        assertEquals("application/json", peers.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        assertEquals("""{"peers":[{"name":"Kitchen"}]}""", peers.bodyAsText())
        val apps = client.get("/api/v1/apps")
        assertEquals(HttpStatusCode.OK, apps.status)
        assertEquals("application/json", apps.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        assertEquals("""{"apps":[{"label":"Dashboard"}]}""", apps.bodyAsText())
        val foreignHost = client.get("/api/v1/apps") { header(HttpHeaders.Host, "foreign.invalid") }
        assertEquals(HttpStatusCode.Forbidden, foreignHost.status)
    }
}
