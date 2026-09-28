package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.WebViewSpec
import io.github.maxlyth.hapaneld.http.PanelHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * Auto-heals the panel's **System WebView** when it's too old to render the Home Assistant dashboard.
 *
 * These panels have no Play Store, so a stock WebView (often Chromium ~83–107) leaves the HA frontend
 * **blank** and there's no built-in way to update it — the single most common first-run failure. When
 * the profile declares a known-good build ([DeviceProfile.recommendedWebView]), ha-paneld downloads it
 * from the `webview-mirror` release and installs it over root via the pinned-signer [AppInstaller]
 * (the same path the Companion updater uses). The build always uses the `com.android.webview` package,
 * so the framework auto-selects it as the provider — no allowlist edit, no extra app.
 *
 * The install-or-not decision keys on the **real** Chromium engine version (from the WebView UA), NOT
 * the package versionName — a Cromite/LineageOS build stamps the OEM stock version to clear a
 * signature-locked provider gate, so the package version lies. Network + root: call OFF the main thread.
 */
object WebViewInstaller {
    const val WEBVIEW_PKG = "com.android.webview"
    private const val TAG = "ha-paneld/webview"

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

    /** Refuse an auto-swap unless an exact, signed prior APK is safely retained on the panel. */
    @Synchronized fun prepareRollback(context: Context, pinVersion: String, targetSha256: String): String? {
        if (rollbackRecord(context).exists()) return "another WebView swap is awaiting health verification"
        if (alreadyRolledBackPin(context, pinVersion)) return "this WebView pin already required rollback"
        if (!targetSha256.matches(Regex("[0-9a-f]{64}"))) return "new WebView APK has no exact checksum"
        val packageInfo = runCatching { context.packageManager.getPackageInfo(WEBVIEW_PKG, 0) }.getOrNull()
            ?: return "previous WebView package is unreadable"
        val applicationInfo = packageInfo.applicationInfo ?: return "previous WebView package has no APK"
        if (!applicationInfo.splitSourceDirs.isNullOrEmpty()) return "previous WebView has split APKs"
        val source = File(applicationInfo.sourceDir ?: return "previous WebView has no APK path")
        if (!source.isFile || source.length() <= 0L || source.length() > 512L * 1024 * 1024) {
            return "previous WebView APK is missing or oversized"
        }
        if (context.filesDir.usableSpace < source.length() * 2L) return "insufficient space to retain previous WebView"
        val staged = File(context.filesDir, "$ROLLBACK_APK.tmp")
        return try {
            source.copyTo(staged, overwrite = true)
            val info = AppInstaller.inspect(context, staged.absolutePath)
            val signer = info?.signerSha256
            if (info?.pkg != WEBVIEW_PKG || signer.isNullOrBlank()) {
                return "previous WebView APK identity cannot be verified"
            }
            val sha = AppInstaller.sha256(staged)
            val previous = rollbackApk(context)
            if (!staged.renameTo(previous)) return "previous WebView APK cannot be retained"
            val pending = PendingRollback(
                pinVersion, targetSha256, sha, signer, 0L,
                originProcess = processToken, installMayHaveStarted = false,
            )
            if (!writeRollbackRecord(context, pending)) {
                previous.delete()
                return "previous WebView receipt cannot be committed"
            }
            null
        } catch (error: Exception) {
            "previous WebView snapshot failed (${error.javaClass.simpleName})"
        } finally {
            staged.delete()
        }
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

    /** Persist the uncertainty fence immediately before the verified APK reaches PackageManager. */
    @Synchronized fun markInstallMayHaveStarted(context: Context, pinVersion: String): Boolean {
        val pending = pendingRollback(context) ?: return false
        if (pending.pinVersion != pinVersion || !rollbackApk(context).isFile) return false
        return pending.installMayHaveStarted || writeRollbackRecord(
            context, pending.copy(installMayHaveStarted = true),
        )
    }

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

    @Synchronized fun abandonRollback(context: Context) {
        rollbackRecord(context).delete()
        rollbackApk(context).delete()
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

    /** Keep download failure and the last pre-mutation gate on the same production install path. */
    internal suspend fun installAutoUpdateWithReceipt(
        context: Context,
        pinVersion: String,
        stillBuiltin: () -> Boolean,
        install: suspend ((() -> Boolean)?) -> InstallOutcome,
    ): InstallOutcome = try {
        install({ stillBuiltin() && markInstallMayHaveStarted(context, pinVersion) })
    } finally {
        // A thrown download or cancellation has no typed failure result. Once the gate ran,
        // an interrupted privileged reply remains uncertain and retains the only backup.
        if (pendingRollback(context)?.installMayHaveStarted == false) abandonRollback(context)
    }

    /**
     * The result of a [heal] attempt. [status] is the exact human-readable message shown in the UI /
     * InstallProgress; callers switch on the variant rather than re-parsing it.
     */
    sealed interface HealResult {
        val status: String
        val presentation: InstallPresentation?
        /** A no-op decision (no recommendation, engine already fine, or the pin is not newer). */
        data class NoAction(
            override val status: String,
            override val presentation: InstallPresentation? = null,
        ) : HealResult
        /** The recommended WebView was installed and the dashboard should be reactivated. */
        data class Installed(
            override val status: String,
            override val presentation: InstallPresentation? = null,
        ) : HealResult
        /** The helper lost the install reply; a process boundary must inspect the installed provider. */
        data class Uncertain(
            override val status: String,
            override val presentation: InstallPresentation? = null,
        ) : HealResult
        /** The install was attempted but failed. [terminal] = a durable rejection (retrying the same pin
         *  cannot help), as opposed to a transient failure that a later tick may clear. */
        data class Failed(
            override val status: String,
            val terminal: Boolean,
            override val presentation: InstallPresentation? = null,
        ) : HealResult
    }

    /** What [heal] should do — a pure decision so the gating logic is unit-testable without a device. */
    sealed class Decision {
        /** An external dashboard cannot prove a scheduled provider swap rendered successfully. */
        object AutoBlockedForeign : Decision()
        /** No known-good build for this panel → leave the WebView alone. */
        object NoRecommendation : Decision()
        /** The engine already renders HA (≥ threshold) or is unknown → don't touch it. */
        data class UpToDate(val engineMajor: Int?) : Decision()
        /** The recommended build is no newer than what's already running → nothing to gain. */
        data class NotNewer(val version: String) : Decision()
        /** Install [spec]. */
        data class Install(val spec: WebViewSpec) : Decision()
    }

    /**
     * Decide whether to install. [engineVersion] is the real four-part Chromium version from the
     * WebView UA (null = unknown). [force] allows reinstalling an equal build from the manual
     * "Update WebView" button. [autoUpdate] is the
     * scheduled auto-update intent: it drops the "engine already renders HA → leave it" short-circuit so
     * a WORKING engine still advances to a newer pinned build — the heal path (autoUpdate=false) instead
     * only touches an engine genuinely below [minChromium]. Either way an unknown engine is never
     * disturbed and a pin that isn't newer is a no-op, so there's no reinstall loop from [decide] alone.
     */
    fun decide(
        rec: WebViewSpec?, engineVersion: String?, minChromium: Int, force: Boolean,
        autoUpdate: Boolean = false, builtinRenderer: Boolean = true,
    ): Decision {
        if (autoUpdate && !builtinRenderer) return Decision.AutoBlockedForeign
        if (rec == null) return Decision.NoRecommendation
        val engine = versionParts(engineVersion) ?: return Decision.UpToDate(null)
        val recommended = versionParts(rec.version) ?: return Decision.NotNewer(rec.version)
        if (!force && !autoUpdate && engine[0] >= minChromium) return Decision.UpToDate(engine[0])
        return if (isOlder(engine, recommended) || (force && engine == recommended)) {
            Decision.Install(rec)
        } else Decision.NotNewer(rec.version)
    }

    private fun versionParts(version: String?): List<Int>? {
        val parts = version?.split('.') ?: return null
        if (parts.size != 4) return null
        return parts.map {
            if (it.isEmpty() || !it.all(Char::isDigit)) return null
            it.toIntOrNull() ?: return null
        }
    }

    private fun isOlder(engine: List<Int>, recommended: List<Int>): Boolean =
        engine.zip(recommended).firstOrNull { (current, pin) -> current != pin }
            ?.let { (current, pin) -> current < pin } == true

    /**
     * Loop guard for the scheduled auto-update ([io.github.maxlyth.hapaneld.PaneldService] `autoUpdateWebView`):
     * skip re-attempting the same pinned [recVersion] once a prior tick recorded it AND the running
     * [engineVersion] still hasn't reached [recVersion] — i.e. the provider isn't actually switching
     * (variant / signature-locked hardware where `pm install` can't change the WebView signer), so
     * re-downloading ~90 MB (and, on the built-in renderer, restarting the process) every 24 h tick would be
     * pointless. A pin bump ([recVersion] differs from the recorded one) clears it and re-attempts; an
     * unknown engine ([engineVersion] == null) counts as "still not switched" so a records-then-can't-verify
     * panel also stops retrying. Kept pure + unit-tested because this predicate is the only thing standing
     * between an opt-in panel and a daily re-download/restart loop, and a regression here stays green.
     */
    fun shouldSkipAutoUpdate(lastVersion: String, recVersion: String, engineVersion: String?): Boolean {
        if (lastVersion != recVersion) return false
        val engine = versionParts(engineVersion) ?: return true
        val recommended = versionParts(recVersion) ?: return true
        return isOlder(engine, recommended)
    }

    /** Whether an auto-update result is durable evidence that retrying the same pin cannot help. Network,
     *  staging, storage, and temporarily unavailable privilege failures remain retryable on the next tick;
     *  successful/no-op decisions and package-manager rejection of a provider swap are terminal until the
     *  profile pin changes or the user explicitly retries. An unknown engine is not durable evidence of
     *  being current: the UA read may have timed out, so the next tick must be allowed to try again.
     *  The terminal-vs-retryable classification is the
     *  producer's ([InstallOutcome]), carried on [HealResult.Failed.terminal]. */
    internal fun shouldRecordAutoAttempt(result: HealResult, engineVersion: String?): Boolean =
        versionParts(engineVersion) != null && when (result) {
            is HealResult.Failed -> result.terminal
            is HealResult.Uncertain -> false
            is HealResult.NoAction, is HealResult.Installed -> true
        }

    /** Heal the WebView per [decide], returning a typed [HealResult] whose [HealResult.status] is a short
     *  human status ("OK: …" on a successful install). [autoUpdate] = the scheduled update-to-pin path
     *  (advance a working engine to a newer pin). */
    suspend fun heal(
        context: Context,
        profile: DeviceProfile,
        engineVersion: String?,
        force: Boolean = false,
        autoUpdate: Boolean = false,
        builtinRenderer: Boolean = true,
        stillBuiltin: (() -> Boolean)? = null,
    ): HealResult {
        return when (val d = decide(profile.recommendedWebView, engineVersion, PanelHealth.MIN_CHROMIUM, force, autoUpdate, builtinRenderer)) {
            Decision.AutoBlockedForeign -> HealResult.Failed(
                "skipped: automatic WebView swap requires the built-in dashboard", terminal = false,
            )
            is Decision.NoRecommendation -> HealResult.NoAction(
                "no known-good WebView for this panel",
                presentation("managed-no-recommendation"),
            )
            is Decision.UpToDate -> HealResult.NoAction(
                "up to date (Chromium ${d.engineMajor ?: "?"})",
                presentation("managed-up-to-date", "current" to "Chromium ${d.engineMajor ?: "?"}"),
            )
            is Decision.NotNewer -> HealResult.NoAction(
                "already current (${d.version})",
                presentation("managed-no-newer", "current" to d.version),
            )
            is Decision.Install -> {
                if (autoUpdate) {
                    if (stillBuiltin == null) return HealResult.Failed(
                        "WebView update deferred: dashboard health guard unavailable", terminal = false,
                    )
                    val reason = withContext(Dispatchers.IO) {
                        prepareRollback(context, d.spec.version, d.spec.apkSha256)
                    }
                    if (reason != null) return HealResult.Failed("WebView update deferred: $reason", terminal = false)
                }
                Log.i(TAG, "healing WebView → ${d.spec.version} (engine was $engineVersion)")
                val installPinned: suspend ((() -> Boolean)?) -> InstallOutcome = { beforeInstall ->
                    AppInstaller.install(
                        context,
                        d.spec.url,
                        AppInstaller.Pin(WEBVIEW_PKG, d.spec.certSha256, d.spec.apkSha256),
                        allowShizuku = false,
                        beforeInstall = beforeInstall,
                    )
                }
                val outcome = if (autoUpdate) {
                    installAutoUpdateWithReceipt(context, d.spec.version, stillBuiltin!!, installPinned)
                } else installPinned(null)
                val result = when (outcome) {
                    InstallOutcome.Succeeded ->
                        HealResult.Installed(
                            "OK: installed WebView ${d.spec.version} — reloading the dashboard",
                            presentation(
                                "managed-install-committed",
                                "version" to d.spec.version,
                            ),
                        )
                    is InstallOutcome.Rejected -> HealResult.Failed(
                        outcome.message,
                        terminal = true,
                        presentation = outcome.presentation,
                    )
                    is InstallOutcome.Retryable -> if (autoUpdate && outcome.mayHaveCommitted) {
                        HealResult.Uncertain(outcome.message, outcome.presentation)
                    } else HealResult.Failed(outcome.message, terminal = false, presentation = outcome.presentation)
                }
                if (result !is HealResult.Installed && result !is HealResult.Uncertain && autoUpdate) abandonRollback(context)
                result
            }
        }
    }

    private fun presentation(code: String, vararg params: Pair<String, String>): InstallPresentation? =
        InstallPresentation.create(code, mapOf("component" to "webview", *params))
}
