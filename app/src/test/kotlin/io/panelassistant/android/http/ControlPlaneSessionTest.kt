package io.panelassistant.android.http

import com.sun.net.httpserver.HttpServer
import io.panelassistant.android.backup.PanelBackup
import io.panelassistant.android.panelassistant.PanelAssistantBlobs
import io.panelassistant.android.panelassistant.PanelAssistantManageReply
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.sensors.HaApiSession
import io.panelassistant.android.storage.DisposableDataClass
import io.panelassistant.android.storage.DisposableFileSweeper
import io.panelassistant.android.storage.appOwnedDisposableFileRules
import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.InstallProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest

/**
 * The update and backup routes Panel Assistant asks for over its session (Epic E slice 5), driven through
 * the session entry the transport calls, against a stand-in for Panel Assistant's blob view on a real
 * loopback socket.
 */
class ControlPlaneSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var homeAssistant: HttpServer
    private val served = mutableMapOf<String, ByteArray>()
    private val received = mutableMapOf<String, ByteArray>()
    private val requests = mutableListOf<String>()
    private var receiptOverride: String? = null

    @Before fun startHomeAssistant() {
        homeAssistant = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext(PanelAssistantBlobs.PATH) { exchange ->
                val token = exchange.requestURI.path.removePrefix(PanelAssistantBlobs.PATH)
                synchronized(requests) { requests += "${exchange.requestMethod} $token ${exchange.requestHeaders.getFirst("Authorization")}" }
                if (exchange.requestHeaders.getFirst("Authorization") != "Bearer panel-token") {
                    exchange.sendResponseHeaders(401, -1)
                    exchange.close()
                    return@createContext
                }
                when (exchange.requestMethod) {
                    "GET" -> {
                        val bytes = served.remove(token)
                        if (bytes == null) exchange.sendResponseHeaders(404, -1)
                        else {
                            exchange.sendResponseHeaders(200, bytes.size.toLong())
                            exchange.responseBody.write(bytes)
                        }
                    }
                    "PUT" -> {
                        val bytes = exchange.requestBody.readBytes()
                        received[token] = bytes
                        val receipt = (receiptOverride ?: """{"size":${bytes.size},"sha256":"${sha256(bytes)}"}""").toByteArray()
                        exchange.sendResponseHeaders(200, receipt.size.toLong())
                        exchange.responseBody.write(receipt)
                    }
                    else -> exchange.sendResponseHeaders(405, -1)
                }
                exchange.close()
            }
            start()
        }
    }

    @After fun stopHomeAssistant() = homeAssistant.stop(0)

    private val blobs by lazy {
        PanelAssistantBlobs({ HaApiSession("http://127.0.0.1:${homeAssistant.address.port}", "panel-token") })
    }

    private val installed = mutableListOf<ByteArray>()
    private val pending = PendingUploadStore { "upload-token" }.also { it.open() }
    private val apkCache by lazy { temporary.newFolder("apk-cache") }
    private var beforeApkInspection: (File) -> Unit = {}
    private var beforeApkInstall: () -> Unit = {}
    private var built: PanelBackup.Artifact? = null
    private val backupRequests = mutableListOf<Pair<CompanionBackupRequest, String>>()

    private val wakeWordDir by lazy { temporary.newFolder("wakeword") }
    private val wakeWordCatalog by lazy {
        io.panelassistant.android.assist.wakeword.WakeWordCatalog(
            bundled = object : io.panelassistant.android.assist.wakeword.WakeWordCatalog.BundledModels {
                override fun ids() = listOf("okay_nabu")
                override fun manifest(id: String) = wakeWordManifest("Okay Nabu", "okay_nabu.tflite")
                override fun model(file: String) = byteArrayOf(1)
            },
            importDir = wakeWordDir,
            accepts = { buffer, _ -> buffer.get(0) != REJECTED_BY_ENGINE },
        )
    }
    private var microphone = true
    private var wakeWordsChanged = 0

    private fun routes() = ControlPlaneRouteDependencies(
        playAudio = { true },
        installComponent = { _, _, _ -> true },
        installedComponentVersion = { null },
        buildBackup = { request, passphrase ->
            backupRequests += request to passphrase
            PanelBackup.Artifact(temporary.newFile("panel-backup.zip").apply { writeBytes(BACKUP) }, "zip").also { built = it }
        },
        backupFileStem = { "test-panel" },
        apkUpload = ApkUploadRouteDependencies(
            enabled = { true },
            rootAvailable = { false },
            confirmedSelfUpdate = { true },
            ownPackage = "own.panel",
            pending = pending,
            createStagingFile = { ApkUploadStaging.create(apkCache) },
            inspect = { staged ->
                beforeApkInspection(staged)
                UploadedApkIdentity("own.panel", String(staged.readBytes()), null)
            },
            startInstall = { claimed, progress ->
                try {
                    beforeApkInstall()
                    installed += claimed.file.readBytes()
                } finally {
                    InstallProgress.finish(progress, "installed")
                }
            },
            maxBytes = 64,
            receiveBody = { input, output, maxBytes -> BoundedStreams.copy(input, output, maxBytes) },
        ),
        wakeWords = { wakeWordCatalog.takeIf { microphone } },
        wakeWordsChanged = { wakeWordsChanged++ },
    )

    /** Ask for [model] as Panel Assistant does; the blob view serves [served] (the announced bytes by default). */
    private fun shareWakeWord(
        model: ByteArray,
        token: String,
        replaces: List<String>? = null,
        served: ByteArray = model,
    ): PanelAssistantManageReply? {
        this.served[token] = served
        return request(
            ControlPlaneSessionOps.WAKE_WORD_IMPORT,
            JSONObject().put("name", "hey_pickle.json").put("manifest", wakeWordManifest("Hey Pickle", "hey_pickle.tflite"))
                .put("blob", JSONObject().put("url", PanelAssistantBlobs.PATH + token).put("size", model.size).put("sha256", sha256(model)).put("type", "application/octet-stream"))
                .apply { replaces?.let { put("replaces", org.json.JSONArray(it)) } },
        )
    }

    private fun request(
        op: String,
        args: JSONObject,
        authorize: suspend (SensitiveOperation, String, String) -> String? = { _, _, _ -> null },
    ): PanelAssistantManageReply? = runBlocking { ControlPlaneSessionOps.request(op, args, routes(), blobs, authorize) }

    private fun offer(bytes: ByteArray, token: String = "download-token-0001", sha: String = sha256(bytes), size: Long = bytes.size.toLong()) =
        JSONObject().put(
            "blob",
            JSONObject().put("url", PanelAssistantBlobs.PATH + token).put("size", size).put("sha256", sha)
                .put("type", "application/vnd.android.package-archive"),
        ).also { served[token] = bytes }

    @Test fun anUpdatePulledFromHomeAssistantStagesAndCommitsLikeAnUpload() {
        val apk = "1.0.1".toByteArray()
        val staged = request("install.apk", offer(apk))!!
        assertEquals("applied", staged.outcome)
        // The body the HTTP upload route answers, so Panel Assistant checks the same package, version and signer.
        assertEquals(
            JSONObject("""{"ok":true,"token":"upload-token","package":"own.panel","version":"1.0.1","signer":"unsigned"}""").fields(),
            staged.body!!.fields(),
        )
        assertEquals(listOf("GET download-token-0001 Bearer panel-token"), requests)

        val committed = request("install.apk.commit", JSONObject().put("token", "upload-token"))!!
        assertEquals("applied" to mapOf<String, Any?>("status" to "started"), committed.outcome to committed.body!!.fields())
        assertArrayEquals(apk, installed.single())
    }

    @Test fun sameArtifactRetrySurvivesAClockStepDuringReceiveStageAndInstall() {
        val apk = "1.0.1".toByteArray()
        val installedApk = temporary.newFile("base.apk").apply { writeBytes(apk) }
        val previousUpload = File(apkCache, "apk-upload-0123456789abcdef0123456789abcdef-123.apk").apply {
            writeBytes(apk)
            assertTrue(setLastModified(996_000L))
        }
        val processBoundary = 1_000_000L
        var wallTime = processBoundary
        val rule = appOwnedDisposableFileRules(apkCache, temporary.root, installedApk = installedApk) { true }
            .single { it.dataClass == DisposableDataClass.DOWNLOADS }
        val sweep = DisposableFileSweeper(processBoundary, nowMillis = { wallTime })
        lateinit var activeUpload: File
        beforeApkInspection = { received ->
            activeUpload = received
            // The sweep sampled its boundary before the upload finished receiving after a clock step.
            wallTime = 997_000L
            assertTrue(received.setLastModified(wallTime))
            sweep.sweep(rule)
            assertFalse(previousUpload.exists())
            assertTrue("The current upload must survive receive", received.isFile)
        }
        beforeApkInstall = {
            wallTime += 7_200_000L
            sweep.sweep(rule)
            assertTrue("The claimed upload must survive the sweep", activeUpload.isFile)
        }

        val staged = request("install.apk", offer(apk))!!
        assertEquals("applied", staged.outcome)
        sweep.sweep(rule)
        assertTrue("The pending upload must survive the sweep", activeUpload.isFile)
        val committed = request("install.apk.commit", JSONObject().put("token", staged.body!!.getString("token")))!!
        assertEquals("applied" to mapOf<String, Any?>("status" to "started"), committed.outcome to committed.body!!.fields())
        assertArrayEquals(apk, installed.single())
    }

    @Test fun bytesThatAreNotTheAnnouncedOnesAreRefusedAndStageNothing() {
        val apk = "1.0.1".toByteArray()
        val tampered = request("install.apk", offer(apk, sha = sha256("other".toByteArray())))!!
        assertEquals(listOf("refused", "invalid_value", "blob-mismatch"), listOf(tampered.outcome, tampered.code, tampered.body?.optString("error")))
        val longer = request("install.apk", offer(apk, token = "download-token-0002", size = 3))!!
        assertEquals("blob-mismatch", longer.body?.optString("error"))
        assertNull(pending.pendingSummary())

        // The single staging slot was released: an honest pull still stages.
        assertEquals("applied", request("install.apk", offer(apk, token = "download-token-0003"))!!.outcome)
    }

    @Test fun aReferenceOutsideThePanelsOwnBlobViewIsRefusedBeforeAnyConnection() {
        for (url in listOf("/api/other/download-token-0001", "http://elsewhere.example/api/panel_assistant/blob/download-token-0001", PanelAssistantBlobs.PATH + "short")) {
            val reply = request(
                "install.apk",
                JSONObject().put("blob", JSONObject().put("url", url).put("size", 5).put("sha256", sha256("1.0.1".toByteArray()))),
            )!!
            assertEquals(listOf("refused", "invalid-request"), listOf(reply.outcome, reply.body?.optString("error")))
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun aBackupIsBuiltAsTheHttpOneAndUploadedWithItsReference() {
        val reply = request(
            "backup",
            JSONObject().put("allow_plaintext", "1").put("include_companion", "false").put("blob_token", "upload-token-00001"),
        )!!
        assertEquals("applied", reply.outcome)
        val blob = reply.body!!.getJSONObject("blob")
        assertEquals(
            listOf(PanelAssistantBlobs.PATH + "upload-token-00001", BACKUP.size.toLong(), sha256(BACKUP)),
            listOf(blob.getString("url"), blob.getLong("size"), blob.getString("sha256")),
        )
        assertArrayEquals(BACKUP, received.getValue("upload-token-00001"))
        assertEquals(listOf(CompanionBackupRequest.EXCLUDED to ""), backupRequests)
        // The archive was released once uploaded, and the operation lane is free again.
        assertFalse(built!!.file.exists())
        InstallProgress.start("probe")!!.also { InstallProgress.finish(it, "released") }
    }

    @Test fun aBackupHomeAssistantDidNotReceiveIntactIsNotReportedAsLanded() {
        receiptOverride = """{"size":1,"sha256":"${sha256(byteArrayOf(9))}"}"""
        val reply = request(
            "backup",
            JSONObject().put("allow_plaintext", "1").put("include_companion", "false").put("blob_token", "upload-token-00001"),
        )!!
        assertEquals(listOf("refused", "blob-unavailable"), listOf(reply.outcome, reply.body?.optString("error")))
        assertFalse(built!!.file.exists())
    }

    @Test fun aBackupKeepsTheHttpRequestRulesAndItsApproval() {
        val unacknowledged = request("backup", JSONObject().put("include_companion", "false").put("blob_token", "upload-token-00001"))!!
        assertEquals("passphrase-required", unacknowledged.body?.optString("error"))
        val asked = mutableListOf<SensitiveOperation>()
        val denied = request(
            "backup",
            JSONObject().put("allow_plaintext", "1").put("include_companion", "false").put("blob_token", "upload-token-00001"),
        ) { operation, _, _ -> asked += operation; "approval_denied" }!!
        assertEquals(listOf("refused", "approval_denied"), listOf(denied.outcome, denied.code))
        assertEquals(listOf(SensitiveOperation.BACKUP_EXPORT), asked)
        assertTrue(backupRequests.isEmpty())
        assertTrue(received.isEmpty())
    }

    @Test fun aBackupApprovalCoversOnlyTheExportAndDestinationItWasGivenFor() = runTest {
        val broker = ApprovalBroker(monotonicMs = { testScheduler.currentTime })
        val hardened: suspend (SensitiveOperation, String, String) -> String? = { operation, payload, summary ->
            authorizeSessionRequest(true, operation, payload, summary, broker, onPending = {})
        }
        fun backup(companion: String, token: String = "upload-token-00001") = async {
            ControlPlaneSessionOps.request(
                "backup",
                JSONObject().put("allow_plaintext", "1").put("include_companion", companion).put("blob_token", token),
                routes(), blobs, hardened,
            )
        }
        val first = backup("false")
        runCurrent()
        broker.approve(broker.pending().single().id)
        // The same token with a different export, and the same export to another destination, are new requests:
        // the approval given for the first admits neither.
        val changed = backup("true")
        val elsewhere = backup("false", token = "upload-token-00002")
        runCurrent()
        assertEquals(2, broker.pending().size)
        changed.cancel()
        elsewhere.cancel()
        runCurrent()
        assertTrue(backupRequests.isEmpty())
        // The approved request itself still runs.
        advanceTimeBy(1_500)
        assertEquals("applied", first.await()!!.outcome)
        assertEquals(listOf(CompanionBackupRequest.EXCLUDED to ""), backupRequests)
    }

    @Test fun aCommitAsksForTheSameApprovalAsTheHttpCommit() {
        request("install.apk", offer("1.0.1".toByteArray()))
        val asked = mutableListOf<Pair<SensitiveOperation, String>>()
        val refused = request("install.apk.commit", JSONObject().put("token", "upload-token")) { operation, _, summary ->
            asked += operation to summary
            "approval_denied"
        }!!
        assertEquals("approval_denied", refused.code)
        assertEquals(SensitiveOperation.APK_INSTALL, asked.single().first)
        assertTrue(installed.isEmpty())
    }

    @Test fun aSharedWakeWordIsFetchedCheckedAgainstItsDigestAndBecomesSelectable() {
        val model = byteArrayOf(7, 7, 7)
        // Panel Assistant asks again after a lost answer; the second request converges on the same model.
        repeat(2) { attempt ->
            val reply = shareWakeWord(model, "wake-word-token-0$attempt")!!
            assertEquals("applied", reply.outcome)
            assertEquals("hey_pickle", reply.body!!.getString("id"))
        }
        assertEquals(listOf("okay_nabu", "hey_pickle"), wakeWordCatalog.available().map { it.id })
        assertEquals(sha256(model), wakeWordCatalog.importedSha256("hey_pickle"))
        assertEquals(2, wakeWordsChanged)
        assertEquals(listOf("GET wake-word-token-00 Bearer panel-token", "GET wake-word-token-01 Bearer panel-token"), requests)
    }

    @Test fun aSharedWakeWordWhoseBytesAreNotTheAnnouncedOnesInstallsNothing() {
        val reply = shareWakeWord(byteArrayOf(7, 7, 7), "wake-word-token-01", served = byteArrayOf(9, 9, 9))!!
        assertEquals("refused", reply.outcome)
        assertEquals("invalid_value", reply.code)
        assertEquals(listOf("okay_nabu"), wakeWordCatalog.available().map { it.id })
        assertEquals(0, wakeWordsChanged)
    }

    @Test fun anOwnersOwnModelUnderTheSameNameIsKeptAndOnlyAnEarlierSharedCopyIsReplaced() {
        val owners = byteArrayOf(3, 3, 3)
        wakeWordCatalog.import("hey_pickle", wakeWordManifest("Hey Pickle", "hey_pickle.tflite").toByteArray(), owners)

        val refused = shareWakeWord(byteArrayOf(7, 7, 7), "wake-word-token-01")!!
        assertEquals("refused", refused.outcome)
        assertEquals("invalid_value", refused.code)
        assertEquals(sha256(owners), wakeWordCatalog.importedSha256("hey_pickle"))

        // Panel Assistant names the copy it sent before, so its newer model replaces that one.
        val replaced = shareWakeWord(byteArrayOf(8, 8, 8), "wake-word-token-02", replaces = listOf(sha256(owners)))!!
        assertEquals("applied", replaced.outcome)
        assertEquals(sha256(byteArrayOf(8, 8, 8)), wakeWordCatalog.importedSha256("hey_pickle"))
    }

    @Test fun aSharedWakeWordTheEngineRefusesKeepsTheModelAlreadyHeld() {
        assertEquals("applied", shareWakeWord(byteArrayOf(7, 7, 7), "wake-word-token-01")!!.outcome)
        val refused = shareWakeWord(byteArrayOf(REJECTED_BY_ENGINE, 1), "wake-word-token-02", replaces = listOf(sha256(byteArrayOf(7, 7, 7))))!!
        assertEquals("refused", refused.outcome)
        assertEquals("invalid_value", refused.code)
        assertEquals(sha256(byteArrayOf(7, 7, 7)), wakeWordCatalog.importedSha256("hey_pickle"))
    }

    @Test fun aPanelWithoutAMicrophoneTakesNoSharedWakeWordAndFetchesNothing() {
        microphone = false
        val reply = shareWakeWord(byteArrayOf(7, 7, 7), "wake-word-token-01")!!
        assertEquals("refused", reply.outcome)
        assertEquals("hardware_unavailable", reply.code)
        assertTrue(requests.isEmpty())
    }

    @Test fun aNameThisBuildDoesNotServeIsLeftToTheUnknownCommandAnswer() {
        assertNull(request("install.component", JSONObject()))
    }

    @Test fun hardenedSessionRequestsWaitForTheirPhysicalApproval() = runTest {
        val broker = ApprovalBroker(monotonicMs = { testScheduler.currentTime })
        val pendingAnswers = mutableListOf<Unit>()
        // Relaxed mode admits a session request as it admits a LAN one: nothing waits on the panel.
        assertNull(authorizeSessionRequest(false, SensitiveOperation.APK_INSTALL, "p", "s", broker, onPending = { pendingAnswers += Unit }))
        assertEquals(0, pendingAnswers.size)
        assertTrue(broker.pending().isEmpty())

        val approved = async { authorizeSessionRequest(true, SensitiveOperation.APK_INSTALL, "p", "s", broker, onPending = { pendingAnswers += Unit }) }
        runCurrent()
        assertEquals(1, pendingAnswers.size)
        val record = broker.pending().single()
        assertEquals(PANEL_ASSISTANT_APPROVAL_PEER, record.peer)
        broker.approve(record.id)
        advanceTimeBy(1_500)
        assertNull(approved.await())
        assertTrue(broker.pending().isEmpty())

        val denied = async { authorizeSessionRequest(true, SensitiveOperation.BACKUP_EXPORT, "q", "s", broker, onPending = {}) }
        runCurrent()
        broker.deny(broker.pending().single().id)
        advanceTimeBy(1_500)
        assertEquals("approval_denied", denied.await())

        // A wait the session abandons leaves nothing to approve on the panel.
        val abandoned = async { authorizeSessionRequest(true, SensitiveOperation.BACKUP_EXPORT, "r", "s", broker, onPending = {}) }
        runCurrent()
        abandoned.cancel()
        runCurrent()
        assertTrue(broker.pending().isEmpty())
    }

    private companion object {
        val BACKUP = "PK-backup-archive".toByteArray()
        const val REJECTED_BY_ENGINE: Byte = 0x55

        fun wakeWordManifest(phrase: String, model: String) =
            """{"type":"micro","wake_word":"$phrase","author":"me","model":"$model","trained_languages":["en"],"version":2,""" +
                """"micro":{"probability_cutoff":0.97,"feature_step_size":10,"sliding_window_size":5,"tensor_arena_size":26080}}"""

        fun JSONObject.fields(): Map<String, Any?> = keys().asSequence().associateWith { opt(it) }

        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** These operations answer JSON objects. */
private val io.panelassistant.android.panelassistant.PanelAssistantManageReply.body: org.json.JSONObject?
    get() = result as org.json.JSONObject?
