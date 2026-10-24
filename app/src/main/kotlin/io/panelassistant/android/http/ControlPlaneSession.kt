package io.panelassistant.android.http

import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.panelassistant.android.panelassistant.PanelAssistantBlobs
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantManageReply
import io.panelassistant.android.panelassistant.PanelAssistantTransportProtocol
import io.panelassistant.android.security.SensitiveOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * The update and backup routes Panel Assistant asks for over its session (protocol section 20.3, Epic E
 * slice 5), named by the REST path they mirror. Each runs the same code its HTTP route runs; only the
 * bytes move differently, through Panel Assistant's blob view ([PanelAssistantBlobs]), and approval comes
 * from [authorize], which answers null to go ahead or the refusal code.
 */
internal object ControlPlaneSessionOps {
    const val BACKUP = "backup"
    const val INSTALL_APK = "install.apk"
    const val INSTALL_APK_COMMIT = "install.apk.commit"
    const val INSTALL_APK_DISCARD = "install.apk.discard"
    /** A wake-word model Panel Assistant installs on every panel: the manifest inline, the model as a blob. */
    const val WAKE_WORD_IMPORT = "voice.wake_words.import"

    /** Null when [op] is not one of these names. */
    suspend fun request(
        op: String,
        args: JSONObject,
        routes: ControlPlaneRouteDependencies,
        blobs: PanelAssistantBlobs,
        authorize: suspend (SensitiveOperation, String, String) -> String?,
    ): PanelAssistantManageReply? = when (op) {
        INSTALL_APK -> reply(stageFromBlob(routes.apkUpload, args, blobs))
        INSTALL_APK_DISCARD -> reply(discardApk(routes.apkUpload, args.optString("token")))
        INSTALL_APK_COMMIT -> when (val commit = prepareApkCommit(routes, args.optString("token"))) {
            is ApkCommit.Answered -> reply(commit.answer)
            is ApkCommit.Ready -> authorize(SensitiveOperation.APK_INSTALL, "$op\u0000${commit.token}", commit.summary)
                ?.let(::refused) ?: reply(startApkCommit(routes, commit))
        }
        BACKUP -> backup(args, routes, blobs, authorize)
        WAKE_WORD_IMPORT -> reply(importWakeWord(routes, args, blobs))
        else -> null
    }

    /**
     * Fetch the model the blob names, which must match its size and SHA-256, and install it as the
     * Configure import does, except that a model the owner imported here under the same id is kept
     * ([io.panelassistant.android.assist.wakeword.WakeWordCatalog.importShared]). Models are untrusted
     * native input; this authenticated Home Assistant distribution path keeps its existing exemption.
     */
    private suspend fun importWakeWord(routes: ControlPlaneRouteDependencies, args: JSONObject, blobs: PanelAssistantBlobs): ControlPlaneAnswer {
        val catalog = routes.wakeWords()
            ?: return ControlPlaneAnswer(HttpStatusCode.ServiceUnavailable, """{"error":"unavailable"}""")
        val name = args.opt("name") as? String
        val manifest = args.opt("manifest") as? String
        val reference = PanelAssistantBlobs.reference(
            args.optJSONObject("blob"),
            io.panelassistant.android.assist.wakeword.WakeWordCatalog.MAX_MODEL_BYTES.toLong(),
        )
        val replaces = args.optJSONArray("replaces")?.let { list -> (0 until list.length()).mapNotNull { list.opt(it) as? String } }
        if (name == null || manifest == null || reference == null) {
            return ControlPlaneAnswer(HttpStatusCode.BadRequest, """{"error":"invalid-request"}""")
        }
        val model = withContext(Dispatchers.IO) { File.createTempFile("wake-word-", ".tflite") }
        try {
            when (blobs.download(reference, model)) {
                PanelAssistantBlobs.Fetched.WRITTEN -> Unit
                PanelAssistantBlobs.Fetched.MISMATCH ->
                    return ControlPlaneAnswer(HttpStatusCode.UnprocessableEntity, """{"error":"the model does not match its SHA-256"}""")
                PanelAssistantBlobs.Fetched.UNAVAILABLE ->
                    return ControlPlaneAnswer(HttpStatusCode.BadGateway, """{"error":"blob-unavailable"}""")
            }
            val (status, body) = withContext(Dispatchers.IO) {
                wakeWordInstalled(
                    catalog.importShared(name, manifest.toByteArray(Charsets.UTF_8), model.readBytes(), replaces.orEmpty().toSet()),
                    routes.wakeWordsChanged,
                )
            }
            return ControlPlaneAnswer(status, body)
        } finally {
            model.delete()
        }
    }

    private suspend fun stageFromBlob(
        dependencies: ApkUploadRouteDependencies,
        args: JSONObject,
        blobs: PanelAssistantBlobs,
    ): ControlPlaneAnswer {
        apkUploadRefusal(dependencies)?.let { return it }
        val reference = PanelAssistantBlobs.reference(args.optJSONObject("blob"), dependencies.maxBytes)
            ?: return ControlPlaneAnswer(HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-request"}""")
        val lease = when (val admission = dependencies.pending.begin(null)) {
            is PendingUploadStore.BeginResult.Granted -> admission.lease
            PendingUploadStore.BeginResult.Busy ->
                return ControlPlaneAnswer(HttpStatusCode.Conflict, """{"ok":false,"error":"upload-busy"}""")
            PendingUploadStore.BeginResult.Closed ->
                return ControlPlaneAnswer(HttpStatusCode.ServiceUnavailable, """{"ok":false,"error":"stopping"}""")
        }
        // A token that never reaches Panel Assistant expires from the store like any unclaimed upload.
        return stageInspect(dependencies, lease, reference.size) { staged, _ ->
            when (blobs.download(reference, staged)) {
                PanelAssistantBlobs.Fetched.WRITTEN -> StagedBytes.Written
                PanelAssistantBlobs.Fetched.MISMATCH -> StagedBytes.Refused(HttpStatusCode.UnprocessableEntity, "blob-mismatch")
                PanelAssistantBlobs.Fetched.UNAVAILABLE -> StagedBytes.Refused(HttpStatusCode.BadGateway, "blob-unavailable")
            }
        }.answer
    }

    private suspend fun backup(
        args: JSONObject,
        routes: ControlPlaneRouteDependencies,
        blobs: PanelAssistantBlobs,
        authorize: suspend (SensitiveOperation, String, String) -> String?,
    ): PanelAssistantManageReply {
        val token = PanelAssistantBlobs.validToken(args.opt("blob_token"))
            ?: return reply(ControlPlaneAnswer(HttpStatusCode.BadRequest, """{"ok":false,"error":"invalid-request"}"""))
        // The HTTP route's form fields, so the same request rules decide what the archive contains.
        val parameters = Parameters.build {
            for (name in listOf("passphrase", "allow_plaintext", "include_companion")) {
                (args.opt(name) as? String)?.let { append(name, it) }
            }
        }
        val request = when (val parsed = backupRequest(parameters)) {
            is BackupRequest.Refused -> return reply(parsed.answer)
            is BackupRequest.Accepted -> parsed
        }
        // The approval covers exactly what the archive contains and where it goes, as the HTTP route's does.
        authorize(SensitiveOperation.BACKUP_EXPORT, "$BACKUP\u0000${parameters.canonicalDigest()}\u0000$token", BACKUP_APPROVAL_SUMMARY)
            ?.let { return refused(it) }
        var uploaded: PanelAssistantBlobs.Reference? = null
        buildAndDeliverBackup(routes, request) { built, release ->
            try {
                uploaded = blobs.upload(token, built.file, BACKUP_TYPE)
            } finally {
                release()
            }
        }?.let { return reply(it) }
        val reference = uploaded
            ?: return reply(ControlPlaneAnswer(HttpStatusCode.BadGateway, """{"ok":false,"error":"blob-unavailable"}"""))
        return PanelAssistantManageReply(
            PanelAssistantTransportProtocol.OUTCOME_APPLIED,
            null,
            JSONObject().put("blob", reference.json()),
        )
    }

    /** A route's answer as a session answer: its body is the result either way, and a refusal carries a code. */
    private fun reply(answer: ControlPlaneAnswer): PanelAssistantManageReply {
        val body = JSONObject(answer.body)
        if (answer.status.value in 200..299) {
            return PanelAssistantManageReply(PanelAssistantTransportProtocol.OUTCOME_APPLIED, null, body)
        }
        val code = when (answer.status.value) {
            400, 413, 422 -> PanelAssistantCommandProcessor.CODE_INVALID_VALUE
            403 -> PanelAssistantCommandProcessor.CODE_NOT_COMMANDABLE
            503 -> PanelAssistantCommandProcessor.CODE_HARDWARE_UNAVAILABLE
            else -> PanelAssistantCommandProcessor.CODE_FAILED
        }
        return PanelAssistantManageReply(PanelAssistantTransportProtocol.OUTCOME_REFUSED, code, body)
    }

    private fun refused(code: String) = PanelAssistantManageReply(PanelAssistantTransportProtocol.OUTCOME_REFUSED, code, null)

    private const val BACKUP_TYPE = "application/octet-stream"
}
