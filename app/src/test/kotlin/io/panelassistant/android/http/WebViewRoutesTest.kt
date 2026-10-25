package io.panelassistant.android.http

import io.panelassistant.android.device.WebViewSpec
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.util.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebViewRoutesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `HTTP observation exposes typed candidate and install accepts JSON without caller URLs`() = testApplication {
        val scope = CoroutineScope(SupervisorJob())
        val io = FakeIo(temporary.newFolder())
        val owner = WebViewUpdate(scope, { "pin" to spec }, io)
        try {
            application { routing { route("/api/v1") { webViewRoutes(owner) { _, _, _, _ -> true } } } }
            val read = client.get("/api/v1/webview")
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals("no-store", read.headers[HttpHeaders.CacheControl])
            val status = JSONObject(read.bodyAsText())
            assertTrue(status.getBoolean("eligible"))
            assertEquals(spec.apkSha256, status.getJSONObject("candidate").getString("sha256"))
            assertEquals("process", status.getJSONObject("process").getString("id"))
            assertEquals("107.0.5304.105", status.getJSONObject("provider").getString("engine_version"))
            // The caller cannot replace the trusted artifact or turn the operation into URL installation.
            val changed = client.post("/api/v1/webview") {
                contentType(ContentType.Application.Json)
                setBody(JSONObject().put("request_id", "owner").put("artifact_sha256", "b".repeat(64))
                    .put("url", "https://example.invalid/other.apk").toString())
            }
            assertEquals(HttpStatusCode.Conflict, changed.status)
            assertEquals("candidate_changed", JSONObject(changed.bodyAsText()).getString("reason"))
            assertNull(io.receipt)
            assertEquals(0, io.installs)
        } finally { scope.cancel() }
    }

    @Test fun `HTTP provider installation uses the existing sensitive operation admission`() = testApplication {
        val scope = CoroutineScope(SupervisorJob())
        val io = FakeIo(temporary.newFolder())
        val owner = WebViewUpdate(scope, { "pin" to spec }, io)
        try {
            application { routing { route("/api/v1") {
                webViewRoutes(owner) { call, operation, _, _ ->
                    assertEquals(SensitiveOperation.APK_INSTALL, operation)
                    call.respondText("denied", status = HttpStatusCode.Forbidden)
                    false
                }
            } } }
            val response = client.post("/api/v1/webview") {
                contentType(ContentType.Application.Json)
                setBody(JSONObject().put("request_id", "owner").put("artifact_sha256", spec.apkSha256).toString())
            }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertNull(io.receipt)
            assertEquals(0, io.installs)
        } finally { scope.cancel() }
    }

    @Test fun `HTTP keeps an active package submission installing without persisting that claim`() = testApplication {
        val scope = CoroutineScope(SupervisorJob())
        val io = FakeIo(temporary.newFolder())
        val source = File(io.directory, "source").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val candidate = spec.copy(apkSha256 = AppInstaller.sha256(source))
        io.acceptedSpec = candidate
        val owner = WebViewUpdate(scope, { "pin" to candidate }, io)
        try {
            application { routing { route("/api/v1") { webViewRoutes(owner) { _, _, _, _ -> true } } } }
            assertEquals(HttpStatusCode.Accepted, client.post("/api/v1/webview") {
                contentType(ContentType.Application.Json)
                setBody(JSONObject().put("request_id", "owner").put("artifact_sha256", candidate.apkSha256).toString())
            }.status)
            assertTrue(io.submitted.await(5, TimeUnit.SECONDS))
            assertEquals("submitted_unknown", io.receipt?.optString("state"))
            val live = JSONObject(client.get("/api/v1/webview").bodyAsText())
            assertEquals("installing", live.getJSONObject("transaction").optString("state"))
            assertEquals("submitted_unknown", io.receipt?.optString("state"))
            io.release.countDown()
            scope.coroutineContext[Job]!!.children.toList().joinAll()
            val ended = JSONObject(client.get("/api/v1/webview").bodyAsText())
            assertEquals("submitted_unknown", ended.getJSONObject("transaction").optString("state"))
            client.get("/api/v1/webview")
            assertEquals(1, io.installs)
        } finally {
            io.release.countDown()
            scope.cancel()
            scope.coroutineContext[Job]!!.children.toList().joinAll()
        }
    }

    private class FakeIo(override val directory: File) : WebViewUpdateIo {
        @Volatile var receipt: JSONObject? = null
        var installs = 0
        var acceptedSpec: WebViewSpec? = null
        val submitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun observe() = WebViewObservation("nspanel-pro", 27, setOf("arm64-v8a"), "process", 1, 100,
            "com.android.webview", "107.0.5304.105", 530410501, setOf("a".repeat(64)), null, false, "107.0.5304.105", "su")
        override fun inspect(file: File): WebViewApk? = acceptedSpec?.let {
            WebViewApk(AppInstaller.WEBVIEW_PKG, it.version, it.versionCode, setOf(it.certSha256), it.minSdk, it.abis)
        }
        override fun readReceipt() = receipt
        override fun writeReceipt(receipt: JSONObject) { this.receipt = receipt }
        override fun download(spec: WebViewSpec, destination: File): Boolean {
            if (acceptedSpec == null) return false
            destination.writeBytes(byteArrayOf(1, 2, 3, 4))
            return true
        }
        override fun installedMatches(spec: WebViewSpec) = false
        override suspend fun install(file: File, beforeSubmit: () -> InstallOutcome.Failure?): InstallOutcome {
            beforeSubmit()?.let { return it }
            installs++
            submitted.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            return InstallOutcome.Retryable("reply lost", mayHaveCommitted = true)
        }
    }

    companion object {
        private val spec = WebViewSpec("https://example.invalid/pin.apk", "138.0.7204.63", "a".repeat(64), "c".repeat(64),
            720406301, 4, setOf("arm64-v8a"), 26, 28)
    }
}
