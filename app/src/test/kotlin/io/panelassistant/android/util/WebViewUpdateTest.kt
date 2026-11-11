package io.panelassistant.android.util

import io.panelassistant.android.device.WebViewSpec
import io.panelassistant.android.webViewRebindRestartCoordinator
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
        override fun deleteReceipt() { durable = null }
        override fun download(spec: WebViewSpec, destination: File): String? {
            downloads++
            beforeDownload()
            destination.writeBytes(bytes)
            return null
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
        assertEquals("installed", owner.observe().optJSONObject("transaction")?.optString("state"))
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

    @Test fun `too little free storage for the stage session copy and installed provider refuses before download`() = runBlocking {
        // A 4-byte pin on a 1000-byte filesystem: reserve 100, stage 4, session copy plus installed APK and libraries 12.
        fun reason(available: Long?, total: Long? = 1000L, stage: Int? = null, change: (WebViewObservation) -> WebViewObservation = { it }): Any? {
            val fixture = Fixture()
            fixture.observed = change(fixture.observed.copy(dataAvailableBytes = available, dataTotalBytes = total))
            stage?.let { File(fixture.directory, fixture.spec.apkSha256 + ".apk").writeBytes(ByteArray(it)) }
            val owner = fixture.owner(this)
            val answer = owner.install("owner", "b".repeat(64)).body
            assertEquals(0, fixture.downloads)
            // Observation reports the same admission; an admitted panel only fails the mismatched hash here.
            val admitted = answer.get("reason") == "candidate_changed"
            assertEquals(if (admitted) JSONObject.NULL else answer.get("reason"), owner.observe().get("reason"))
            return answer.get("reason")
        }
        assertEquals("insufficient_storage", reason(115))
        assertEquals("candidate_changed", reason(116))
        assertEquals("insufficient_storage", reason(111, stage = 4))
        assertEquals("candidate_changed", reason(112, stage = 4))
        assertEquals("A stage of another size still has to be written", "insufficient_storage", reason(112, stage = 3))
        val cap = 500L * 1024 * 1024
        assertEquals("insufficient_storage", reason(cap + 15, total = 100L * cap))
        assertEquals("candidate_changed", reason(cap + 16, total = 100L * cap))
        assertEquals("candidate_changed", reason(null))
        assertEquals("candidate_changed", reason(0L, total = null))
        assertEquals("current", reason(0L) { it.copy(versionCode = 900000000) })
        assertEquals("no_install_route", reason(116) { it.copy(route = "none") })
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
            assertEquals("installed", replacement.observe().optJSONObject("transaction")?.optString("state"))
            assertEquals(1, fixture.submissions)
        } finally { release.countDown() }
    }

    @Test fun `durably installed provider requests the existing restart only after releasing package ownership`() = runBlocking {
        val fixture = Fixture()
        fixture.observed = fixture.observed.copy(boundPackage = "com.android.webview", boundVersionCode = 530410501)
        fixture.afterInstall = {
            fixture.observed = fixture.observed.copy(versionName = fixture.spec.version, versionCode = fixture.spec.versionCode)
        }
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

    @Test fun `live submission projects installing across server owners while disk retains unknown`() = runBlocking {
        val fixture = Fixture()
        fixture.reply = InstallOutcome.Retryable("reply lost", mayHaveCommitted = true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.afterInstall = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val owner = fixture.owner(this)
        owner.install("owner", fixture.spec.apkSha256)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals("submitted_unknown", fixture.durable?.optString("state"))
            assertEquals("installing", owner.observe().getJSONObject("transaction").optString("state"))
            val replacement = fixture.owner(this)
            assertEquals("installing", replacement.observe().getJSONObject("transaction").optString("state"))
            assertEquals("submitted_unknown", fixture.durable?.optString("state"))
            release.countDown()
            finishWork()
            assertEquals("submitted_unknown", owner.observe().getJSONObject("transaction").optString("state"))
            assertEquals("submitted_unknown", replacement.observe().getJSONObject("transaction").optString("state"))
            replacement.observe()
            assertEquals(1, fixture.submissions)
        } finally { release.countDown() }
    }

    @Test fun `installed package stays installing through deferred rebind until the new process binds it`() = runBlocking {
        val fixture = Fixture()
        fixture.observed = fixture.observed.copy(boundPackage = "com.android.webview", boundVersionCode = 530410501)
        fixture.afterInstall = {
            fixture.observed = fixture.observed.copy(versionName = fixture.spec.version, versionCode = fixture.spec.versionCode)
        }
        val scheduled = ArrayDeque<Pair<Long, () -> Unit>>()
        var setupWalked = true
        var restarts = 0
        val coordinator = webViewRebindRestartCoordinator(
            schedule = { delay, action -> scheduled.addLast(delay to action); true },
            restartProcess = { restarts++ },
            destructiveOperationRunning = { InstallProgress.running },
            guidedSetupBeingWalked = { setupWalked },
            serviceStopping = { false },
        )
        val owner = fixture.owner(this, coordinator::request)
        owner.install("owner", fixture.spec.apkSha256)
        finishWork()
        assertFalse(InstallProgress.running)
        assertEquals("installed", fixture.durable?.optString("state"))
        assertEquals("installing", owner.observe().getJSONObject("transaction").optString("state"))
        val grace = scheduled.removeFirst()
        assertEquals(350L, grace.first)
        grace.second()
        assertEquals(0, restarts)
        assertEquals("installing", owner.observe().getJSONObject("transaction").optString("state"))
        assertEquals(1, scheduled.size)
        setupWalked = false
        scheduled.removeFirst().second()
        assertEquals(1, restarts)
        assertEquals("installing", owner.observe().getJSONObject("transaction").optString("state"))
        fixture.observed = fixture.observed.copy(processId = "new-process", engineVersion = fixture.spec.version,
            boundVersionName = fixture.spec.version, boundVersionCode = fixture.spec.versionCode)
        val fresh = fixture.owner(this, coordinator::request)
        val complete = fresh.observe()
        assertEquals("installed", complete.getJSONObject("transaction").optString("state"))
        assertEquals("current", complete.optString("reason"))
        fresh.install("owner", fixture.spec.apkSha256)
        assertEquals(1, fixture.submissions)
        assertEquals(1, restarts)
        assertTrue(scheduled.isEmpty())
    }

    private fun Fixture.installedBy(process: String) {
        durable = JSONObject().put("schema", 1).put("request_id", "pa-update-old").put("artifact_sha256", spec.apkSha256)
            .put("state", "installed").put("reason", JSONObject.NULL).put("process_id", process)
    }

    @Test fun `a WebView rolled back after its install clears the receipt so the panel offers it again and never restarts`() = runBlocking {
        val fixture = Fixture()
        fixture.installedBy("old-process")
        // pm uninstall com.android.webview: package and this process's engine are the system 107 again.
        fixture.observed = fixture.observed.copy(processId = "new-process", boundPackage = "com.android.webview",
            boundVersionName = "107.0.5304.105", boundVersionCode = 530410501)
        File(fixture.directory, fixture.spec.apkSha256 + ".apk").writeBytes(fixture.bytes)
        var rebinds = 0
        val owner = fixture.owner(this) { rebinds++; true }
        repeat(2) {
            val snapshot = owner.observe()
            assertEquals(JSONObject.NULL, snapshot.get("transaction"))
            assertTrue(snapshot.getBoolean("eligible"))
            assertEquals(JSONObject.NULL, snapshot.get("reason"))
        }
        assertEquals(0, rebinds)
        assertNull(fixture.durable)
        assertFalse(File(fixture.directory, fixture.spec.apkSha256 + ".apk").exists())
        // Every later process reads no receipt, so none of them restarts either.
        assertEquals(JSONObject.NULL, fixture.owner(this) { rebinds++; true }.observe().get("transaction"))
        assertEquals(0, rebinds)
        // The owner's next request installs it again.
        fixture.afterInstall = {
            fixture.observed = fixture.observed.copy(versionName = fixture.spec.version, versionCode = fixture.spec.versionCode)
        }
        assertEquals(202, owner.install("pa-update-new", fixture.spec.apkSha256).status)
        finishWork()
        assertEquals(1, fixture.submissions)
        assertEquals("pa-update-new", fixture.durable?.getString("request_id"))
        assertEquals(1, rebinds)
    }

    @Test fun `an unread provider version never clears an installed receipt`() = runBlocking {
        for (change in listOf<(WebViewObservation) -> WebViewObservation>(
            { it.copy(versionCode = null) }, { it.copy(providerPackage = null) },
        )) {
            val fixture = Fixture()
            fixture.installedBy("old-process")
            fixture.observed = change(fixture.observed.copy(processId = "new-process"))
            val owner = fixture.owner(this)
            assertEquals("installed", owner.observe().optJSONObject("transaction")?.optString("state"))
            assertEquals("installed", fixture.durable?.optString("state"))
        }
    }

    @Test fun `a later process still on the old engine never requests another restart and reports the install`() = runBlocking {
        val fixture = Fixture()
        fixture.installedBy("old-process")
        // The package is the candidate, yet this new process started on the old engine: a restart cannot help.
        fixture.observed = fixture.observed.copy(processId = "new-process", versionName = fixture.spec.version,
            versionCode = fixture.spec.versionCode, boundPackage = "com.android.webview",
            boundVersionName = "107.0.5304.105", boundVersionCode = 530410501)
        var rebinds = 0
        repeat(3) { process ->
            fixture.observed = fixture.observed.copy(processId = "new-process-$process")
            val snapshot = fixture.owner(this) { rebinds++; true }.observe()
            assertEquals("installed", snapshot.getJSONObject("transaction").getString("state"))
        }
        assertEquals(0, rebinds)
        assertEquals("installed", fixture.durable?.getString("state"))
    }
}
