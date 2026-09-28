package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PaneldRootRoutesTest {
    @Test fun `production root admits local reads and keeps shared write and host guards`() = testApplication {
        application {
            paneldRoot(
                allowedHosts = { emptySet() },
                setupNeedsUser = { false },
                setupRedirectLocation = { "/setup" },
            ) {
                get("/api/v1/probe") { call.respondText("read-ok") }
                post("/api/v1/probe") { call.respondText("write-ok") }
            }
        }

        val read = client.get("/api/v1/probe")
        assertEquals(HttpStatusCode.OK, read.status)
        assertEquals("read-ok", read.bodyAsText())
        assertEquals("nosniff", read.headers["X-Content-Type-Options"])
        assertEquals("DENY", read.headers["X-Frame-Options"])

        val automationPost = client.post("/api/v1/probe")
        assertEquals(HttpStatusCode.OK, automationPost.status)
        assertEquals("write-ok", automationPost.bodyAsText())

        val crossOrigin = client.post("/api/v1/probe") {
            header(HttpHeaders.Origin, "http://elsewhere.example")
        }
        assertEquals(HttpStatusCode.Forbidden, crossOrigin.status)
        assertEquals("cross-origin refused\n", crossOrigin.bodyAsText())

        val reboundHost = client.get("/api/v1/probe") {
            header(HttpHeaders.Host, "elsewhere.example")
        }
        assertEquals(HttpStatusCode.Forbidden, reboundHost.status)
        assertEquals("host not allowed\n", reboundHost.bodyAsText())

        val oauthRejection = client.get(HA_OAUTH_CALLBACK_PATH) {
            header(HttpHeaders.Host, "elsewhere.example")
        }
        assertEquals(HttpStatusCode.Forbidden, oauthRejection.status)
        assertNotNull(oauthRejection.headers[HttpHeaders.CacheControl])
    }
}
