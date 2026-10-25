package io.panelassistant.android.http

import io.ktor.client.request.get
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.mutableOriginConnectionPoint
import io.ktor.server.testing.testApplication
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetRoutesHttpTest {
    // Source-text reason: shipped assets are the expected HTTP payload, not Kotlin implementation pins.
    @Test fun `production assets preserve content media cache and root guards`() {
        PaneldServerHttpFixture().use { fixture ->
            testApplication {
                application { fixture.server.mount(this) }
                val cases = listOf(
                    Triple("info.js", "application/javascript", "no-cache"),
                    Triple("info.css", "text/css", "no-cache"),
                    Triple("icon.svg", "image/svg+xml", null),
                    Triple("favicon.svg", "image/svg+xml", null),
                )
                for ((name, media, cache) in cases) {
                    val response = client.get("/$name")
                    assertEquals(name, HttpStatusCode.OK, response.status)
                    val file = if (name == "favicon.svg") "icon.svg" else name
                    assertArrayEquals(name, File("src/main/assets", file).readBytes(), response.body<ByteArray>())
                    assertEquals(name, wireType(media), response.headers[HttpHeaders.ContentType])
                    assertEquals(name, cache, response.headers[HttpHeaders.CacheControl])
                    assertEquals("nosniff", response.headers["X-Content-Type-Options"])
                    val refused = client.get("/$name") { header(HttpHeaders.Host, "elsewhere.example") }
                    assertEquals(HttpStatusCode.Forbidden, refused.status)
                    assertEquals("host not allowed\n", refused.bodyAsText())
                }
                for ((name, media) in listOf(
                    "vendor/profile-editor/codemirror.js" to "application/javascript",
                    "info.css" to "text/css",
                    "icon.svg" to "image/svg+xml",
                    "i18n/en.json" to "application/json",
                    "vendor/profile-editor/LICENSE.txt" to "text/plain",
                )) {
                    val response = client.get("/assets/$name")
                    assertEquals(name, HttpStatusCode.OK, response.status)
                    assertArrayEquals(name, File("src/main/assets", name).readBytes(), response.body<ByteArray>())
                    assertEquals(name, wireType(media), response.headers[HttpHeaders.ContentType])
                    assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
                    val refused = client.get("/assets/$name") { header(HttpHeaders.Host, "elsewhere.example") }
                    assertEquals(HttpStatusCode.Forbidden, refused.status)
                }
                for (path in listOf("/assets/", "/assets/absent-file.js", "/assets/a..b", "/assets/%2e%2e/info.js")) {
                    val response = client.get(path)
                    assertEquals(path, HttpStatusCode.NotFound, response.status)
                    assertEquals(path, "not found\n", response.bodyAsText())
                    assertEquals(null, response.headers[HttpHeaders.CacheControl])
                }
            }
        }
    }

    @Test fun `only the panel's own pages take the bundled font, and only where the system font replaces Roboto`() {
        val stylesheet = File("src/main/assets/info.css").readText()
        for (replaced in listOf(true, false)) PaneldServerHttpFixture(panelFont = replaced).use { fixture ->
            var peer = "127.0.0.1"
            testApplication {
                application {
                    intercept(io.ktor.server.application.ApplicationCallPipeline.Setup) {
                        context.mutableOriginConnectionPoint.remoteAddress = peer
                    }
                    fixture.mount(this)
                }
                val own = client.get("/info.css").bodyAsText()
                if (replaced) {
                    assertTrue(own.startsWith(stylesheet))
                    val face = Regex("""@font-face\{font-family:"([^"]+)";src:url\(([^)]+)\)""").find(own)?.groupValues ?: error("the panel's own stylesheet names no bundled face")
                    assertTrue(Regex("""body\{font-family:"${face[1]}",""").containsMatchIn(own.removePrefix(stylesheet)))
                    // The url resolves against the stylesheet at the root, as the pages' base puts it.
                    val font = client.get("/${face[2]}")
                    assertEquals(HttpStatusCode.OK, font.status)
                    assertEquals("font/ttf", font.headers[HttpHeaders.ContentType])
                    assertArrayEquals(File("src/main/assets/fonts/Roboto.ttf").readBytes(), font.body<ByteArray>())
                } else {
                    assertEquals("a panel that draws Roboto keeps the stylesheet as shipped", stylesheet, own)
                }
                peer = "192.168.50.20"
                assertEquals("a browser on the network keeps the stylesheet as shipped", stylesheet, client.get("/info.css").bodyAsText())
            }
        }
    }

    private fun wireType(media: String) = if (media.startsWith("text/")) "$media; charset=UTF-8" else media
}
