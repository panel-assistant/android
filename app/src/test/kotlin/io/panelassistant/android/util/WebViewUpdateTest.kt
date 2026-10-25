package io.panelassistant.android.util

import io.panelassistant.android.device.WebViewSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

class WebViewUpdateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val signer = "a".repeat(64)

    internal inner class Fixture : WebViewUpdateIo {
        override val directory = temporary.newFolder()
        val bytes = byteArrayOf(1, 2, 3, 4)
        val seed = File(directory, "source").apply { writeBytes(bytes) }
        val spec = WebViewSpec("https://example.invalid/pin.apk", "138.0.7204.63", signer, AppInstaller.sha256(seed),
            720406301, 4, setOf("arm64-v8a"), 26, 28)
        var observed = WebViewObservation("nspanel-pro", 27, setOf("arm64-v8a"), "process-1", 10, 100,
            "com.android.webview", "107.0.5304.105", 530410501, setOf(signer), null, false, "107.0.5304.105", "su")
        var identity = WebViewApk("com.android.webview", spec.version, spec.versionCode, setOf(signer), 26, spec.abis)
        @Volatile var durable: JSONObject? = null
        var downloads = 0
        var submissions = 0
        var installed = false
        var reply: InstallOutcome = InstallOutcome.Succeeded
        var beforeDownload: () -> Unit = {}
        var beforeInstall: () -> Unit = {}
        var afterInstall: () -> Unit = {}
        var failReceipt = false
        override fun observe() = observed
        override fun inspect(file: File) = identity
        override fun readReceipt() = durable?.let { JSONObject(it.toString()) }
        override fun writeReceipt(receipt: JSONObject) {
            if (failReceipt) error("disk full")
            durable = JSONObject(receipt.toString())
        }
        override fun download(spec: WebViewSpec, destination: File): Boolean {
            downloads++
            beforeDownload()
            destination.writeBytes(bytes)
            return true
        }
        override fun installedMatches(spec: WebViewSpec) = installed
        override suspend fun install(file: File, beforeSubmit: () -> InstallOutcome.Failure?): InstallOutcome {
            beforeInstall()
            beforeSubmit()?.let { return it }
            // A durable unknown state must already exist when the package manager can receive bytes.
            assertEquals("submitted_unknown", durable!!.getString("state"))
            submissions++
            installed = reply == InstallOutcome.Succeeded
            afterInstall()
            return reply
        }
        fun owner(scope: CoroutineScope, rebind: () -> Boolean = { false }) = WebViewUpdate(scope, { "pin" to spec }, this, rebind)
    }

    private suspend fun CoroutineScope.finishWork() { coroutineContext[Job]!!.children.toList().joinAll() }

    @Test fun `owner request verifies and installs a compatible provider then repeated request is a no-op`() = runBlocking {
        val fixture = Fixture()
        val owner = fixture.owner(this)
        assertTrue(owner.observe().getBoolean("eligible"))
        assertEquals(202, owner.install("owner-1", fixture.spec.apkSha256).status)
        finishWork()
        assertEquals("installed", owner.observe().getJSONObject("transaction").getString("state"))
        assertEquals(1, fixture.submissions)
        assertEquals(200, owner.install("owner-1", fixture.spec.apkSha256).status)
        assertEquals(1, fixture.submissions)
        assertEquals(409, owner.install("owner-1", "b".repeat(64)).status)
        assertFalse(File(fixture.directory, fixture.spec.apkSha256 + ".apk").exists())
    }

    @Test fun `provider and platform admission prevent download or mutation`() = runBlocking {
        val changes: List<Pair<String, (WebViewObservation) -> WebViewObservation>> = listOf(
            "play_managed" to { it.copy(playManaged = true) },
            "installer_unknown" to { it.copy(playManaged = null) },
            "different_provider" to { it.copy(providerPackage = "com.google.android.webview") },
            "signer_mismatch" to { it.copy(signers = setOf("b".repeat(64))) },
            "unsupported_sdk" to { it.copy(sdk = 29) },
            "unsupported_abi" to { it.copy(abis = setOf("armeabi-v7a")) },
            "current" to { it.copy(versionCode = 900000000) },
            "current" to { it.copy(engineVersion = "139.0.0.0") },
            "version_unknown" to { it.copy(engineVersion = null) },
            "no_install_route" to { it.copy(route = "none") },
        )
        for ((reason, change) in changes) {
            val fixture = Fixture()
            fixture.observed = change(fixture.observed)
            val owner = fixture.owner(this)
            assertEquals(reason, owner.install("owner", fixture.spec.apkSha256).body.optString("reason"))
            assertEquals(0, fixture.downloads)
            assertEquals(0, fixture.submissions)
        }
    }

    @Test fun `actual staged APK package signer SDK ABI and version must match the pin`() = runBlocking {
        val changes: List<(WebViewApk) -> WebViewApk> = listOf(
            { it.copy(pkg = "other.package") }, { it.copy(signers = setOf("b".repeat(64))) },
            { it.copy(signers = setOf(signer, "b".repeat(64))) }, { it.copy(minSdk = 29) },
            { it.copy(abis = setOf("armeabi-v7a")) }, { it.copy(versionCode = 1) }, { it.copy(versionName = "139.0.0.0") },
        )
        for (change in changes) {
            val fixture = Fixture()
            fixture.identity = change(fixture.identity)
            val owner = fixture.owner(this)
            owner.install("owner", fixture.spec.apkSha256)
            finishWork()
            assertEquals("artifact_mismatch", owner.observe().getJSONObject("transaction").optString("reason"))
            assertEquals(0, fixture.submissions)
        }
    }

    @Test fun `completed verified stage resumes while partial bytes are discarded`() = runBlocking {
        val fixture = Fixture()
        File(fixture.directory, fixture.spec.apkSha256 + ".apk").writeBytes(fixture.bytes)
        File(fixture.directory, fixture.spec.apkSha256 + ".apk.part").writeText("partial")
        val owner = fixture.owner(this)
        owner.install("owner", fixture.spec.apkSha256)
        finishWork()
        assertEquals(0, fixture.downloads)
        assertEquals(1, fixture.submissions)
        assertFalse(File(fixture.directory, fixture.spec.apkSha256 + ".apk.part").exists())
    }

    @Test fun `completed stage filename does not authenticate its bytes`() = runBlocking {
        val fixture = Fixture()
        File(fixture.directory, fixture.spec.apkSha256 + ".apk").writeBytes(byteArrayOf(4, 3, 2, 1))
        val owner = fixture.owner(this)
        owner.install("owner", fixture.spec.apkSha256)
        finishWork()
        assertEquals(1, fixture.downloads)
        assertEquals(1, fixture.submissions)
    }

    @Test fun `new Play ownership during download prevents submission`() = runBlocking {
        val fixture = Fixture()
        fixture.beforeDownload = { fixture.observed = fixture.observed.copy(playManaged = true) }
        val owner = fixture.owner(this)
        owner.install("owner", fixture.spec.apkSha256)
        finishWork()
        assertEquals("rejected", owner.observe().getJSONObject("transaction").getString("state"))
        assertEquals(0, fixture.submissions)
    }

    @Test fun `receipt failure never submits and releases shared operation ownership`() = runBlocking {
        val fixture = Fixture()
        fixture.failReceipt = true
        assertEquals(503, fixture.owner(this).install("owner", fixture.spec.apkSha256).status)
        assertEquals(0, fixture.submissions)
        assertFalse(InstallProgress.running)
    }

    @Test fun `receipt failure at package boundary prevents submission`() = runBlocking {
        val fixture = Fixture()
        fixture.beforeInstall = { fixture.failReceipt = true }
        fixture.owner(this).install("owner", fixture.spec.apkSha256)
        finishWork()
        assertEquals(0, fixture.submissions)
        assertFalse(InstallProgress.running)
    }

    @Test fun `lost reply survives process loss and observation never replays installation`() = runBlocking {
        val fixture = Fixture()
        fixture.reply = InstallOutcome.Retryable("reply lost", mayHaveCommitted = true)
        val first = fixture.owner(this)
        first.install("owner", fixture.spec.apkSha256)
        finishWork()
        val resumed = fixture.owner(this)
        assertEquals("submitted_unknown", resumed.observe().getJSONObject("transaction").getString("state"))
        resumed.observe()
        resumed.install("owner", fixture.spec.apkSha256)
        assertEquals(1, fixture.submissions)
        fixture.installed = true
        assertEquals("installed", resumed.observe().getJSONObject("transaction").getString("state"))
        assertEquals(1, fixture.submissions)
    }

    @Test fun `interrupted download becomes deferred without installing until a new owner request`() = runBlocking {
        val fixture = Fixture()
        fixture.durable = JSONObject().put("schema", 1).put("request_id", "old").put("artifact_sha256", fixture.spec.apkSha256)
            .put("state", "verified").put("process_id", "old-process")
        val owner = fixture.owner(this)
        assertEquals("deferred", owner.observe().getJSONObject("transaction").getString("state"))
        owner.install("old", fixture.spec.apkSha256)
        assertEquals(0, fixture.submissions)
        owner.install("explicit-retry", fixture.spec.apkSha256)
        finishWork()
        assertEquals(1, fixture.submissions)
    }

    @Test fun `a cancelled scope cannot strand the shared slot before the worker begins`() = runBlocking {
        val fixture = Fixture()
        val parent = SupervisorJob().apply { cancel() }
        val owner = fixture.owner(CoroutineScope(parent))
        val answer = owner.install("owner", fixture.spec.apkSha256)
        parent.join()
        assertEquals(503, answer.status)
        assertFalse(answer.body.getBoolean("accepted"))
        assertEquals("service_stopping", answer.body.optString("reason"))
        assertTrue(owner.observe().isNull("transaction"))
        assertFalse(InstallProgress.running)
        assertEquals(0, fixture.submissions)
    }

    @Test fun `failed final receipt retains submission uncertainty and never resubmits`() = runBlocking {
        val fixture = Fixture()
        fixture.afterInstall = { fixture.failReceipt = true }
        val owner = fixture.owner(this)
        owner.install("owner", fixture.spec.apkSha256)
        finishWork()
        assertEquals("submitted_unknown", owner.observe().getJSONObject("transaction").getString("state"))
        owner.install("owner", fixture.spec.apkSha256)
        assertEquals(1, fixture.submissions)
        fixture.failReceipt = false
        assertEquals("installed", owner.observe().getJSONObject("transaction").getString("state"))
        assertEquals(1, fixture.submissions)
    }

    @Test fun `replacement server observes the running provider owner then its final durable result`() = runBlocking {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.beforeDownload = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val first = fixture.owner(this)
        first.install("owner", fixture.spec.apkSha256)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val replacement = fixture.owner(this)
            assertEquals("downloading", replacement.observe().getJSONObject("transaction").getString("state"))
            assertEquals(409, replacement.install("another-owner", fixture.spec.apkSha256).status)
            release.countDown()
            finishWork()
            assertEquals("installed", replacement.observe().getJSONObject("transaction").getString("state"))
            assertEquals(1, fixture.submissions)
        } finally { release.countDown() }
    }

    @Test fun `durably installed provider requests the existing restart only after releasing package ownership`() = runBlocking {
        val fixture = Fixture()
        fixture.observed = fixture.observed.copy(boundPackage = "com.android.webview", boundVersionCode = 530410501)
        var rebinds = 0
        val owner = fixture.owner(this) {
            assertFalse(InstallProgress.running)
            assertEquals("installed", fixture.durable?.optString("state"))
            rebinds++
            true
        }
        owner.install("owner", fixture.spec.apkSha256)
        finishWork()
        owner.observe()
        owner.observe()
        assertEquals(1, rebinds)
    }

    @Test fun `new process bound to the installed provider needs no second restart`() = runBlocking {
        val fixture = Fixture()
        fixture.durable = JSONObject().put("schema", 1).put("request_id", "owner").put("artifact_sha256", fixture.spec.apkSha256)
            .put("state", "submitted_unknown").put("process_id", "old-process")
        fixture.installed = true
        fixture.observed = fixture.observed.copy(processId = "new-process", versionCode = fixture.spec.versionCode,
            engineVersion = fixture.spec.version, boundPackage = "com.android.webview", boundVersionCode = fixture.spec.versionCode)
        var rebinds = 0
        val owner = fixture.owner(this) { rebinds++; true }
        assertEquals("installed", owner.observe().getJSONObject("transaction").optString("state"))
        assertEquals(0, rebinds)
    }
}
