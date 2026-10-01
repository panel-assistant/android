package io.github.maxlyth.hapaneld.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Completes or recovers a WebView swap admitted by an earlier app version. */
object WebViewInstaller {
    const val WEBVIEW_PKG = "com.android.webview"

    enum class SwapHealthDecision { WAIT, KEEP, RESTORE_PREVIOUS, NO_ACTION }

    /** One pending built-in auto-update. The APK is copied before installation because PackageManager
     *  may remove its old /data/app path as soon as the new provider commits. */
    data class PendingRollback(
        val pinVersion: String,
        val targetSha256: String,
        val previousSha256: String,
        val previousSigner: String,
        val deadlineWallMs: Long,
        val originProcess: String = "",
        val installMayHaveStarted: Boolean = true,
    )

    /** A connection is accepted only in the new process, after the receipt is armed and before its
     *  deadline. The consumed receipt is the one-attempt fence even if another callback arrives. */
    fun postSwapDecision(
        pending: PendingRollback?, rendererConnected: Boolean, rendererForeground: Boolean,
        nowWallMs: Long, alreadyRolledBack: Boolean,
    ): SwapHealthDecision = when {
        pending == null || alreadyRolledBack -> SwapHealthDecision.NO_ACTION
        pending.deadlineWallMs == 0L -> SwapHealthDecision.WAIT
        nowWallMs >= pending.deadlineWallMs -> SwapHealthDecision.RESTORE_PREVIOUS
        rendererConnected && rendererForeground -> SwapHealthDecision.KEEP
        else -> SwapHealthDecision.WAIT
    }

    private const val ROLLBACK_APK = "webview-previous.apk"
    private const val ROLLBACK_RECORD = "webview-previous.json"
    private const val ROLLBACK_ATTEMPTED = "webview-rollback-attempted.json"
    private const val ROLLBACK_DIAGNOSTIC = "webview-rollback-diagnostic.txt"
    const val SWAP_HEALTH_DEADLINE_MS = 90_000L
    private val processToken = java.util.UUID.randomUUID().toString()

    private fun rollbackApk(context: Context) = File(context.filesDir, ROLLBACK_APK)
    private fun rollbackRecord(context: Context) = File(context.filesDir, ROLLBACK_RECORD)

    @Synchronized fun alreadyRolledBackPin(context: Context, pinVersion: String): Boolean {
        // Both files can coexist only while a claim is being prepared. The pending receipt still
        // owns recovery until it is removed, so a failed attempted-record write cannot spend it.
        if (pendingRollback(context)?.pinVersion == pinVersion) return false
        val file = File(context.filesDir, ROLLBACK_ATTEMPTED)
        if (!file.exists()) return false
        return readRollbackRecord(file)?.pinVersion?.let { it == pinVersion } ?: true
    }

    private fun readRollbackRecord(file: File): PendingRollback? = runCatching {
        val raw = file.takeIf { it.isFile }?.readText() ?: return null
        val json = JSONObject(raw)
        PendingRollback(
            pinVersion = json.getString("pin"),
            targetSha256 = json.getString("target_sha256"),
            previousSha256 = json.getString("sha256"),
            previousSigner = json.getString("signer"),
            deadlineWallMs = json.getLong("deadline"),
            originProcess = json.optString("origin_process", ""),
            installMayHaveStarted = json.optBoolean("install_may_have_started", true),
        ).takeIf {
            it.targetSha256.matches(Regex("[0-9a-f]{64}")) &&
                it.previousSha256.matches(Regex("[0-9a-f]{64}")) &&
                it.previousSigner.matches(Regex("[0-9a-f]{64}")) &&
                it.pinVersion.isNotBlank()
        }
    }.getOrNull()

    @Synchronized fun pendingRollback(context: Context): PendingRollback? = readRollbackRecord(rollbackRecord(context))

    /** A dead downloader cannot have submitted the install; release only that pre-install receipt. */
    @Synchronized fun discardUnsubmittedRollback(context: Context): Boolean {
        val pending = pendingRollback(context) ?: return false
        if (pending.installMayHaveStarted || madeInThisProcess(pending)) return false
        if (!rollbackRecord(context).delete()) return false
        rollbackApk(context).delete()
        return true
    }

    @Synchronized fun attemptedRollback(context: Context): PendingRollback? =
        readRollbackRecord(File(context.filesDir, ROLLBACK_ATTEMPTED))

    @Synchronized fun armRollback(context: Context, nowWallMs: Long): PendingRollback? {
        val pending = pendingRollback(context) ?: return null
        if (sameProcess(pending, processToken)) return null
        if (pending.deadlineWallMs > 0L) return pending
        val armed = pending.copy(deadlineWallMs = nowWallMs + SWAP_HEALTH_DEADLINE_MS)
        return armed.takeIf { writeRollbackRecord(context, it) }
    }

    /** Atomically claim the receipt as durable no-loop evidence before touching the installed provider. */
    @Synchronized fun claimRollback(context: Context): PendingRollback? {
        val pending = pendingRollback(context) ?: return null
        val attempted = File(context.filesDir, ROLLBACK_ATTEMPTED)
        // Record the process that will invoke PackageManager. A same-process service successor may
        // see the old APK on disk while its Activity still holds the bad WebView provider.
        val claimed = pending.copy(originProcess = processToken)
        if (!writeRollbackRecord(attempted, claimed)) return null
        // The attempted record is durable before the old receipt goes away. If this delete fails,
        // the pending receipt remains authoritative and no package install is admitted.
        return claimed.takeIf { rollbackRecord(context).delete() }
    }

    @Synchronized fun acceptRollbackProbe(context: Context): PendingRollback? {
        val pending = pendingRollback(context) ?: return null
        if (!rollbackRecord(context).delete()) return null
        rollbackApk(context).delete()
        File(context.filesDir, ROLLBACK_DIAGNOSTIC).delete()
        return pending
    }

    fun previousApk(context: Context): File = rollbackApk(context)

    /** A hard link gives the consuming installer its own path without copying or risking the only
     *  recovery APK. Both app files and cache are on the panel's /data filesystem. */
    fun stageRestoreApk(context: Context): File? = runCatching {
        val saved = rollbackApk(context).takeIf { it.isFile } ?: return null
        val alias = File(context.cacheDir, "webview-restore-${java.util.UUID.randomUUID()}.apk")
        Files.createLink(alias.toPath(), saved.toPath())
        alias
    }.getOrNull()

    fun madeInThisProcess(pending: PendingRollback): Boolean = sameProcess(pending, processToken)

    internal fun sameProcess(pending: PendingRollback, currentProcess: String): Boolean =
        pending.originProcess.isNotBlank() && pending.originProcess == currentProcess

    fun discardPreviousApk(context: Context) { rollbackApk(context).delete() }

    suspend fun installedApkMatches(context: Context, expectedSha256: String): Boolean = withContext(Dispatchers.IO) { runCatching {
        val source = context.packageManager.getPackageInfo(WEBVIEW_PKG, 0).applicationInfo?.sourceDir
            ?: return@runCatching false
        AppInstaller.sha256(File(source)).equals(expectedSha256, ignoreCase = true)
    }.getOrDefault(false) }

    fun rollbackDiagnostic(context: Context): String? =
        runCatching { File(context.filesDir, ROLLBACK_DIAGNOSTIC).takeIf { it.isFile }?.readText()?.take(160) }.getOrNull()

    fun recordRollbackDiagnostic(context: Context, reason: String) {
        runCatching { File(context.filesDir, ROLLBACK_DIAGNOSTIC).writeText(reason.take(160)) }
    }

    internal fun writeRollbackRecord(context: Context, pending: PendingRollback): Boolean =
        writeRollbackRecord(rollbackRecord(context), pending)

    private fun writeRollbackRecord(file: File, pending: PendingRollback): Boolean = runCatching {
        val json = JSONObject()
            .put("pin", pending.pinVersion)
            .put("target_sha256", pending.targetSha256)
            .put("sha256", pending.previousSha256)
            .put("signer", pending.previousSigner)
            .put("deadline", pending.deadlineWallMs)
            .put("origin_process", pending.originProcess)
            .put("install_may_have_started", pending.installMayHaveStarted)
        val staged = File(file.parentFile, "${file.name}.tmp")
        staged.writeText(json.toString())
        if (!staged.renameTo(file)) {
            staged.delete()
            return@runCatching false
        }
        true
    }.getOrDefault(false)

}
