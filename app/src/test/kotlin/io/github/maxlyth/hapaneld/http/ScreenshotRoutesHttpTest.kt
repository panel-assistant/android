package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.InteractiveController
import io.github.maxlyth.hapaneld.control.FakeDaemon
import io.github.maxlyth.hapaneld.control.FakeRootShell
import io.github.maxlyth.hapaneld.platform.AccessibilityActions
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.platform.ShellPrivilege
import io.github.maxlyth.hapaneld.shizuku.ShizukuPolicy
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotRoutesHttpTest {
    @Test fun `live capture is cached by content and remains available after capture failure`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val captures = AtomicInteger()
        var available = true
        val root = object : RootShell by FakeRootShell(available = false, runResult = false) {
            override fun runBytesBounded(cmd: String, maxBytes: Long): ByteArray? {
                assertEquals("screencap -p", cmd)
                assertEquals(ShizukuPolicy.MAX_SCREENSHOT_BYTES.toLong(), maxBytes)
                captures.incrementAndGet()
                return if (available) png else null
            }
        }
        PaneldServerHttpFixture().use { fixture ->
            fixture.useInteractive(InteractiveController(
                canSu = true,
                root = root,
                daemon = FakeDaemon(available = false),
                accessibility = object : AccessibilityActions {
                    override fun back() = false
                    override fun recents() = false
                    override fun tap(x: Int, y: Int) = false
                },
                shell = object : ShellPrivilege {
                    override fun available() = false
                    override fun uid(): Int? = null
                    override fun screenshot(): ByteArray? = null
                    override fun inputKey(keyCode: Int) = false
                    override fun tap(x: Int, y: Int) = false
                    override fun density(): String? = null
                    override fun setDensity(dpi: Int) = false
                    override fun resetDensity() = false
                    override fun fontScale(): String? = null
                    override fun setFontScale(scale: Float) = false
                    override fun resetFontScale() = false
                    override fun installApk(apk: java.io.File, allowDowngrade: Boolean, timeoutMs: Long): String? = null
                },
            ))
            testApplication {
                application { fixture.mount(this) }
                val live = client.get("/api/v1/screenshot.png")
                assertEquals(HttpStatusCode.OK, live.status)
                assertEquals("image/png", live.headers[HttpHeaders.ContentType])
                assertEquals("no-store", live.headers[HttpHeaders.CacheControl])
                assertArrayEquals(png, live.bodyAsBytes())
                val id = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
                assertEquals(id, live.headers["X-ha-paneld-Screenshot-Id"])
                assertEquals(1, captures.get())

                available = false
                val failed = client.get("/api/v1/screenshot.png")
                assertEquals(HttpStatusCode.ServiceUnavailable, failed.status)
                assertEquals("screenshot-unavailable\n", failed.bodyAsText())
                assertEquals("no-store", failed.headers[HttpHeaders.CacheControl])
                assertEquals(2, captures.get())

                val cached = client.get("/api/v1/screenshot.png?cached=$id")
                assertEquals(HttpStatusCode.OK, cached.status)
                assertArrayEquals(png, cached.bodyAsBytes())
                assertEquals("private, max-age=31536000, immutable", cached.headers[HttpHeaders.CacheControl])
                assertEquals(2, captures.get())
                val missing = client.get("/api/v1/screenshot.png?cached=invalid")
                assertEquals(HttpStatusCode.ServiceUnavailable, missing.status)
                assertEquals("screenshot-unavailable\n", missing.bodyAsText())
                assertEquals(2, captures.get())

                val refused = client.get("/api/v1/screenshot.png?cached=$id") {
                    header("Sec-Fetch-Site", "cross-site")
                }
                assertEquals(HttpStatusCode.Forbidden, refused.status)
                assertEquals(2, captures.get())
                assertEquals("nosniff", cached.headers["X-Content-Type-Options"])
            }
        }
    }
}
