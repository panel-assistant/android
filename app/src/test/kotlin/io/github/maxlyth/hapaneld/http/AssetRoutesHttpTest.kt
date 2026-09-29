package io.github.maxlyth.hapaneld.http

import io.ktor.client.request.get
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
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
                    assertArrayEquals(name, File("src/main/assets", name).readBytes(), response.body<ByteArray>())
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

    private fun wireType(media: String) = if (media.startsWith("text/")) "$media; charset=UTF-8" else media
}
