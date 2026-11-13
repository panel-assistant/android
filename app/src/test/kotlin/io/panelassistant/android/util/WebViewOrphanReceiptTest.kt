package io.panelassistant.android.util

import io.panelassistant.android.device.WebViewSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebViewOrphanReceiptTest {
    @get:Rule val temporary = TemporaryFolder()
    private val oldSha = "1".repeat(64)
    private inner class Fixture {
        val directory = temporary.newFolder()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val source = File(directory, "source").apply { writeBytes(bytes) }
        val spec = WebViewSpec("https://example.invalid/pin.apk", "138.0.7204.63", "a".repeat(64),
            AppInstaller.sha256(source), 720406301, 4, setOf("arm64-v8a"), 26, 28)
        @Volatile var selected: Pair<String, WebViewSpec>? = "current" to spec
        val receiptFile = File(directory, "receipt.json")
        var downloads = 0
        var submissions = 0
        var readbacks = 0
        var rebinds = 0
        var installed = false
        var beforeDownload: () -> Unit = {}
        var beforeInstall: () -> Unit = {}
        val io = object : WebViewUpdateIo {
            override val directory get() = this@Fixture.directory
            override fun observe() = WebViewObservation("nspanel-pro", 27, setOf("arm64-v8a"), "new-process", 10, 100,
                "com.android.webview", "107.0.5304.105", 530410501, setOf(spec.certSha256), null, false,
                "107.0.5304.105", "su")
            override fun inspect(file: File) = WebViewApk("com.android.webview", spec.version, spec.versionCode,
                setOf(spec.certSha256), spec.minSdk, spec.abis)
            override fun readReceipt() = if (receiptFile.exists()) JSONObject(receiptFile.readText()) else null
            override fun writeReceipt(receipt: JSONObject) { receiptFile.writeText(receipt.toString()) }
            override fun deleteReceipt() { receiptFile.delete() }
            override fun download(spec: WebViewSpec, destination: File): String? {
                downloads++; beforeDownload(); destination.writeBytes(bytes); return null
            }
            override fun installedMatches(spec: WebViewSpec): Boolean { readbacks++; return installed }
            override suspend fun install(file: File, beforeSubmit: () -> InstallOutcome.Failure?): InstallOutcome {
                beforeInstall()
                beforeSubmit()?.let { return it }
                submissions++; installed = true
                return InstallOutcome.Succeeded
            }
        }
        fun seed(state: String, sha: String = oldSha) {
            io.writeReceipt(JSONObject().put("schema", 1).put("request_id", "old-request")
                .put("artifact_sha256", sha).put("process_id", "old-process").put("state", state))
        }
        fun owner(scope: CoroutineScope) = WebViewUpdate(scope, { selected }, io) { rebinds++; true }
        fun durable() = requireNotNull(io.readReceipt())
        fun assertIdentity(transaction: JSONObject) {
            assertEquals("old-request", transaction.getString("request_id"))
            assertEquals(oldSha, transaction.getString("artifact_sha256"))
            assertEquals("old-process", transaction.getString("process_id"))
            assertEquals(1, transaction.getInt("schema"))
        }
        fun assertNoSubmission() { assertEquals(0, submissions); assertEquals(0, rebinds) }
    }
    private suspend fun CoroutineScope.finishWork() = coroutineContext[Job]!!.children.toList().joinAll()

    @Test fun `orphan downloading defers and a fresh request installs once`() = runBlocking { orphanInstall("downloading") }
    @Test fun `orphan verified defers and a fresh request installs once`() = runBlocking { orphanInstall("verified") }
    private suspend fun CoroutineScope.orphanInstall(state: String) {
        val fixture = Fixture(); fixture.seed(state)
        val owner = fixture.owner(this)
        val transaction = owner.observe().getJSONObject("transaction")
        assertEquals("deferred", transaction.getString("state"))
        fixture.assertIdentity(transaction)
        fixture.assertIdentity(fixture.durable())
        assertEquals("interrupted", fixture.durable().getString("reason"))
        val saved = fixture.receiptFile.readText()
        repeat(3) { assertEquals("deferred", owner.observe().getJSONObject("transaction").getString("state")) }
        assertEquals(saved, fixture.receiptFile.readText())
        assertEquals(0, fixture.downloads); fixture.assertNoSubmission()
        assertEquals(202, owner.install("new-request", fixture.spec.apkSha256).status)
        finishWork()
        assertEquals(1, fixture.downloads); assertEquals(1, fixture.submissions)
        assertEquals("installed", fixture.durable().getString("state"))
        assertEquals("new-request", fixture.durable().getString("request_id"))
        assertEquals(fixture.spec.apkSha256, fixture.durable().getString("artifact_sha256"))
        val repeated = owner.install("new-request", fixture.spec.apkSha256)
        assertEquals(200, repeated.status); assertTrue(repeated.body.getBoolean("accepted"))
        assertEquals(1, fixture.submissions)
    }

    @Test fun `orphan downloading defers without a candidate`() = runBlocking { orphanWithoutCandidate("downloading") }
    @Test fun `orphan verified defers without a candidate`() = runBlocking { orphanWithoutCandidate("verified") }
    private fun CoroutineScope.orphanWithoutCandidate(state: String) {
        val fixture = Fixture(); fixture.seed(state); fixture.selected = null
        val transaction = fixture.owner(this).observe().getJSONObject("transaction")
        assertEquals("deferred", transaction.getString("state"))
        fixture.assertIdentity(transaction); fixture.assertIdentity(fixture.durable())
        assertEquals("interrupted", fixture.durable().getString("reason"))
        assertEquals(0, fixture.downloads); fixture.assertNoSubmission()
    }

    @Test fun `submitted uncertainty requires the same candidate and terminal receipts are unchanged`() = runBlocking {
        for (state in listOf("submitted_unknown", "installed", "deferred", "rejected")) {
            val fixture = Fixture(); fixture.seed(state); fixture.installed = true
            val oldStage = File(fixture.directory, "$oldSha.apk").apply { writeText("old") }
            val currentStage = File(fixture.directory, "${fixture.spec.apkSha256}.apk").apply { writeText("current") }
            val saved = fixture.receiptFile.readText()
            assertEquals(state, fixture.owner(this).observe().getJSONObject("transaction").getString("state"))
            assertEquals(saved, fixture.receiptFile.readText())
            assertEquals("old", oldStage.readText()); assertEquals("current", currentStage.readText())
            assertEquals(0, fixture.readbacks); assertEquals(0, fixture.downloads); fixture.assertNoSubmission()
        }
        val matching = Fixture(); matching.seed("submitted_unknown", matching.spec.apkSha256); matching.installed = true
        val stage = File(matching.directory, "${matching.spec.apkSha256}.apk").apply { writeText("stage") }
        assertEquals("installed", matching.owner(this).observe().getJSONObject("transaction").getString("state"))
        assertFalse(stage.exists()); assertEquals(1, matching.readbacks); matching.assertNoSubmission()
    }

    @Test fun `live original and replacement owners retain custody across a candidate change`() = runBlocking {
        for (state in listOf("downloading", "verified")) {
            val fixture = Fixture()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val hold = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            if (state == "downloading") fixture.beforeDownload = hold else fixture.beforeInstall = hold
            val owner = fixture.owner(this)
            assertEquals(202, owner.install("live-request", fixture.spec.apkSha256).status)
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                fixture.selected = "replacement" to fixture.spec.copy(apkSha256 = "c".repeat(64))
                val saved = fixture.receiptFile.readText()
                for (observer in listOf(owner, fixture.owner(this))) {
                    val transaction = observer.observe().getJSONObject("transaction")
                    assertEquals(state, transaction.getString("state"))
                    assertEquals(fixture.spec.apkSha256, transaction.getString("artifact_sha256"))
                    assertEquals("live-request", transaction.getString("request_id"))
                }
                assertEquals(saved, fixture.receiptFile.readText()); fixture.assertNoSubmission()
            } finally { release.countDown(); finishWork() }
            assertFalse(InstallProgress.running)
        }
    }
}
