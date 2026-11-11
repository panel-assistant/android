package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.sensors.HaApiSession
import io.panelassistant.android.sensors.HaApiSessionProvider
import io.panelassistant.android.util.AppInstaller
import io.panelassistant.android.util.InstallOutcome
import io.panelassistant.android.util.WebViewApk
import io.panelassistant.android.util.WebViewObservation
import io.panelassistant.android.util.WebViewUpdate
import io.panelassistant.android.util.WebViewUpdateIo
import com.sun.net.httpserver.HttpServer
import io.panelassistant.android.device.WebViewSpec
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaneldServerWebViewApprovalTest {
    @Test fun nativeProviderInstallWaitsForExactApprovalBeforeEnteringTheOwner() = runTest {
        val broker = LocalApprovalBroker.instance
        broker.clear()
        try {
            PaneldServerHttpFixture().use { fixture ->
                fixture.config.setSecurityMode(Config.SecurityMode.HARDENED)
                var observations = 0
                val io = object : WebViewUpdateIo {
                    override val directory = fixture.directory
                    override fun observe(): WebViewObservation {
                        observations++
                        return WebViewObservation("generic", 27, emptySet(), "process", 1, 0,
                            null, null, null, emptySet(), null, null, null, "none")
                    }
                    override fun readReceipt(): JSONObject? = null
                    override fun writeReceipt(receipt: JSONObject): Unit = error("Unadmitted install wrote a receipt")
                    override fun deleteReceipt(): Unit = error("Unadmitted install deleted a receipt")
                    override fun inspect(file: File): WebViewApk? = error("Unadmitted install inspected an APK")
                    override fun download(spec: io.panelassistant.android.device.WebViewSpec, destination: File): String? =
                        error("Unadmitted install downloaded an APK")
                    override fun installedMatches(spec: io.panelassistant.android.device.WebViewSpec): Boolean = false
                    override suspend fun install(file: File, beforeSubmit: () -> InstallOutcome.Failure?): InstallOutcome =
                        error("Unadmitted install submitted a package")
                }
                // Reuse the complete server fixture; only its platform owner IO is replaced.
                PaneldServer::class.java.getDeclaredField("webViewUpdate\$delegate").apply {
                    isAccessible = true
                    set(fixture.server, lazyOf(WebViewUpdate(this@runTest, { null }, io)))
                }
                val auth = HaApiSessionProvider { error("Provider admission does not fetch session blobs") }
                val request = JSONObject().put("request_id", "owner-one").put("artifact_sha256", "a".repeat(64))
                var pending = 0
                val refused = async { fixture.server.managementRequest("webview.install", request, { pending++ }, auth) }
                runCurrent()
                assertFalse("Native request must wait for local approval", refused.isCompleted)
                assertEquals(1, pending)
                assertEquals(0, observations)
                val denied = broker.pending().single()
                assertEquals(SensitiveOperation.APK_INSTALL, denied.operation)
                assertEquals("Update System WebView", denied.summary)
                assertEquals(PANEL_ASSISTANT_APPROVAL_PEER, denied.peer)
                assertTrue(broker.deny(denied.id))
                advanceTimeBy(1_000)
                runCurrent()
                assertEquals(PanelAssistantTransportProtocol.OUTCOME_REFUSED, refused.await()!!.outcome)
                assertEquals("approval_denied", refused.await()!!.code)
                assertNull(refused.await()!!.result)
                assertEquals(0, observations)

                val approved = async { fixture.server.managementRequest("webview.install", request, { pending++ }, auth) }
                runCurrent()
                assertEquals(2, pending)
                val approval = broker.pending().single()
                assertTrue(broker.approve(approval.id))
                advanceTimeBy(1_000)
                runCurrent()
                val answer = approved.await()!!
                assertEquals(PanelAssistantTransportProtocol.OUTCOME_APPLIED, answer.outcome)
                assertNull(answer.code)
                assertEquals("no_candidate", (answer.result as JSONObject).getString("reason"))
                assertEquals("The approved request reached the real provider owner", 1, observations)
                assertEquals(ApprovalBroker.State.ABSENT, broker.state(approval.id))

                // A consumed approval cannot authorize another native dispatch, even with identical bytes.
                val replay = async { fixture.server.managementRequest("webview.install", request, { pending++ }, auth) }
                runCurrent()
                assertFalse(replay.isCompleted)
                assertEquals(3, pending)
                assertEquals(1, observations)
                replay.cancel()
                runCurrent()
                assertTrue(broker.pending().isEmpty())

                // An approved request cannot authorize changed native request bytes before its own poll consumes it.
                for (changedField in listOf("request_id", "artifact_sha256")) {
                    val exact = JSONObject().put("request_id", "binding-$changedField").put("artifact_sha256", "a".repeat(64))
                    val beforeObservations = observations
                    val original = async { fixture.server.managementRequest("webview.install", exact, { pending++ }, auth) }
                    runCurrent()
                    assertTrue(broker.approve(broker.pending().single().id))
                    val different = JSONObject(exact.toString()).put(changedField,
                        if (changedField == "request_id") "different-owner" else "b".repeat(64))
                    val changed = async { fixture.server.managementRequest("webview.install", different, { pending++ }, auth) }
                    runCurrent()
                    assertFalse("$changedField must remain bound to the approved request", changed.isCompleted)
                    assertEquals(beforeObservations, observations)
                    assertEquals("A distinct changed request needs pending approval", 1, broker.pending().size)
                    assertTrue(broker.deny(broker.pending().single().id))
                    advanceTimeBy(1_000)
                    runCurrent()
                    assertEquals(PanelAssistantTransportProtocol.OUTCOME_APPLIED, original.await()!!.outcome)
                    assertEquals("approval_denied", changed.await()!!.code)
                    assertEquals(beforeObservations + 1, observations)
                }
                assertEquals(7, pending)
                fixture.config.setSecurityMode(Config.SecurityMode.RELAXED)
                val relaxed = fixture.server.managementRequest("webview.install", request, { pending++ }, auth)!!
                assertEquals(PanelAssistantTransportProtocol.OUTCOME_APPLIED, relaxed.outcome)
                assertEquals("no_candidate", (relaxed.result as JSONObject).getString("reason"))
                assertEquals(7, pending)
                assertEquals(4, observations)
            }
        } finally {
            broker.clear()
        }
    }

    @Test fun nativeProviderRefusalsAnswerWithTransportContractCodesAndKeepTheReason() = runTest {
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setSecurityMode(Config.SecurityMode.RELAXED)
            val receipt = JSONObject().put("schema", 1).put("request_id", "held").put("artifact_sha256", "a".repeat(64))
                .put("state", "installed").put("reason", JSONObject.NULL).put("process_id", "process")
            val io = object : WebViewUpdateIo {
                override val directory = fixture.directory
                override fun observe() = WebViewObservation("generic", 27, emptySet(), "process", 1, 0,
                    null, null, null, emptySet(), null, null, null, "none")
                override fun readReceipt(): JSONObject? = receipt
                override fun writeReceipt(receipt: JSONObject): Unit = error("A refused request wrote a receipt")
                override fun deleteReceipt(): Unit = error("A refused request deleted a receipt")
                override fun inspect(file: File): WebViewApk? = error("A refused request inspected an APK")
                override fun download(spec: io.panelassistant.android.device.WebViewSpec, destination: File): String? =
                    error("A refused request downloaded an APK")
                override fun installedMatches(spec: io.panelassistant.android.device.WebViewSpec): Boolean = false
                override suspend fun install(file: File, beforeSubmit: () -> InstallOutcome.Failure?): InstallOutcome =
                    error("A refused request submitted a package")
            }
            PaneldServer::class.java.getDeclaredField("webViewUpdate\$delegate").apply {
                isAccessible = true
                set(fixture.server, lazyOf(WebViewUpdate(this@runTest, { null }, io)))
            }
            val auth = HaApiSessionProvider { error("Provider install does not fetch session blobs") }
            // Panel Assistant's schema accepts only contract outcome codes; anything else is dropped and times out.
            val invalid = fixture.server.managementRequest("webview.install", JSONObject(), {}, auth)!!
            assertEquals(PanelAssistantTransportProtocol.OUTCOME_REFUSED, invalid.outcome)
            assertEquals("invalid_value", invalid.code)
            assertEquals("invalid_request", (invalid.result as JSONObject).getString("reason"))

            val conflict = fixture.server.managementRequest(
                "webview.install", JSONObject().put("request_id", "held").put("artifact_sha256", "b".repeat(64)), {}, auth,
            )!!
            assertEquals(PanelAssistantTransportProtocol.OUTCOME_REFUSED, conflict.outcome)
            assertEquals("not_commandable", conflict.code)
            assertEquals("request_conflict", (conflict.result as JSONObject).getString("reason"))
        }
    }

    /** What one session `webview.install` with a blob did: the reply, the transaction after the job, and who fetched. */
    private class BlobRun(val reply: JSONObject?, val transaction: JSONObject?, val acceptsBlob: Boolean, val blobHits: Int, val ownDownloads: Int)

    /**
     * Drives the real session op with a real [io.panelassistant.android.panelassistant.PanelAssistantBlobs] against a
     * local Home Assistant blob view that answers [blobStatus] with [served].
     */
    private fun sessionInstallWithBlob(
        served: ByteArray?, blobStatus: Int = 200, admits: Boolean = true, blob: (WebViewSpec) -> JSONObject,
    ): BlobRun = runBlocking {
        val pinned = byteArrayOf(1, 2, 3, 4)
        val signer = "a".repeat(64)
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/panel_assistant/blob/") { exchange ->
            hits.incrementAndGet()
            val body = served ?: ByteArray(0)
            exchange.sendResponseHeaders(blobStatus, if (blobStatus == 200) body.size.toLong() else -1L)
            exchange.responseBody.use { if (blobStatus == 200) it.write(body) }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob())
        try {
            PaneldServerHttpFixture().use { fixture ->
                fixture.config.setSecurityMode(Config.SecurityMode.RELAXED)
                val source = File(fixture.directory, "pinned-source").apply { writeBytes(pinned) }
                val spec = WebViewSpec("https://panel-assistant.io/downloads/webview/pin.apk", "138.0.7204.63", signer,
                    AppInstaller.sha256(source), 720406301, pinned.size.toLong(), setOf("arm64-v8a"), 26, 28)
                var ownDownloads = 0
                var installed = false
                val io = object : WebViewUpdateIo {
                    override val directory = File(fixture.directory, "webview-install")
                    var receipt: JSONObject? = null
                    override fun observe() = WebViewObservation("nspanel-pro", 27, setOf("arm64-v8a"), "process", 1, 100,
                        "com.android.webview", "107.0.5304.105", 530410501, setOf(signer), null, false, "107.0.5304.105", "su")
                    override fun readReceipt() = receipt
                    override fun writeReceipt(receipt: JSONObject) { this.receipt = JSONObject(receipt.toString()) }
                    override fun deleteReceipt() { receipt = null }
                    // The stage is admitted only when it holds exactly the pinned bytes, as the real inspection would.
                    override fun inspect(file: File): WebViewApk? = if (admits && file.readBytes().contentEquals(pinned))
                        WebViewApk(AppInstaller.WEBVIEW_PKG, spec.version, spec.versionCode, setOf(signer), spec.minSdk, spec.abis) else null
                    override fun download(spec: WebViewSpec, destination: File): String? {
                        ownDownloads++
                        destination.writeBytes(pinned)
                        return null
                    }
                    override fun installedMatches(spec: WebViewSpec) = installed
                    override suspend fun install(file: File, beforeSubmit: () -> InstallOutcome.Failure?): InstallOutcome {
                        beforeSubmit()?.let { return it }
                        installed = true
                        return InstallOutcome.Succeeded
                    }
                }
                PaneldServer::class.java.getDeclaredField("webViewUpdate\$delegate").apply {
                    isAccessible = true
                    set(fixture.server, lazyOf(WebViewUpdate(scope, { "pin" to spec }, io)))
                }
                val auth = HaApiSessionProvider { HaApiSession("http://127.0.0.1:${server.address.port}", "session-token") }
                val request = JSONObject().put("request_id", "owner-blob").put("artifact_sha256", spec.apkSha256).put("blob", blob(spec))
                val reply = fixture.server.managementRequest("webview.install", request, {}, auth)!!
                scope.coroutineContext[Job]!!.children.toList().joinAll()
                val observed = fixture.server.managementRequest("webview.observe", JSONObject(), {}, auth)!!.result as JSONObject
                BlobRun(reply.result as? JSONObject, observed.optJSONObject("transaction"), observed.optBoolean("accepts_blob"), hits.get(), ownDownloads)
            }
        } finally {
            scope.cancel()
            server.stop(0)
        }
    }

    private fun blobFor(spec: WebViewSpec, sha256: String = spec.apkSha256, size: Long = spec.size) = JSONObject()
        .put("url", "/api/panel_assistant/blob/" + "t".repeat(32)).put("size", size).put("sha256", sha256)
        .put("type", "application/vnd.android.package-archive")

    @Test fun sessionInstallUsesHomeAssistantsCopyOfThePinWithoutTheOwnDownload() {
        val run = sessionInstallWithBlob(byteArrayOf(1, 2, 3, 4)) { blobFor(it) }
        assertTrue("The observation tells Panel Assistant a blob is accepted", run.acceptsBlob)
        assertEquals(1, run.blobHits)
        assertEquals("A usable blob replaces the panel's own download", 0, run.ownDownloads)
        assertEquals("installed", run.transaction?.optString("state"))
        assertEquals("fetched", run.transaction?.optString("supplied"))
    }

    @Test fun sessionInstallFallsBackToItsOwnDownloadWhenTheBlobIsUnavailableOrWrong() {
        val unavailable = sessionInstallWithBlob(null, blobStatus = 404) { blobFor(it) }
        assertEquals(1, unavailable.blobHits)
        assertEquals(1, unavailable.ownDownloads)
        assertEquals("installed", unavailable.transaction?.optString("state"))
        assertEquals("unavailable", unavailable.transaction?.optString("supplied"))

        val wrong = sessionInstallWithBlob(byteArrayOf(9, 9, 9, 9)) { blobFor(it) }
        assertEquals(1, wrong.blobHits)
        assertEquals(1, wrong.ownDownloads)
        assertEquals("installed", wrong.transaction?.optString("state"))
        assertEquals("mismatch", wrong.transaction?.optString("supplied"))

        // The pinned hash alone never admits a stage: a blob whose APK fails inspection is not used.
        val inadmissible = sessionInstallWithBlob(byteArrayOf(1, 2, 3, 4), admits = false) { blobFor(it) }
        assertEquals(1, inadmissible.blobHits)
        assertEquals(1, inadmissible.ownDownloads)
        assertEquals("mismatch", inadmissible.transaction?.optString("supplied"))
        assertEquals("artifact_mismatch", inadmissible.transaction?.optString("reason"))

        val resized = sessionInstallWithBlob(byteArrayOf(1, 2, 3, 4)) { blobFor(it, size = it.size + 1) }
        assertEquals("A blob of another size is never fetched", 0, resized.blobHits)
        assertEquals(1, resized.ownDownloads)
        assertEquals("mismatch", resized.transaction?.optString("supplied"))
    }

    @Test fun sessionInstallRefusesABlobForAnotherArtifactBeforeAnyDownload() {
        val run = sessionInstallWithBlob(byteArrayOf(1, 2, 3, 4)) { blobFor(it, sha256 = "b".repeat(64)) }
        assertEquals("invalid_request", run.reply?.optString("reason"))
        assertEquals(0, run.blobHits)
        assertEquals(0, run.ownDownloads)
        assertNull(run.transaction)
    }
}
