package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.github.maxlyth.hapaneld.AppIdentity
import io.github.maxlyth.hapaneld.BuildConfig
import io.github.maxlyth.hapaneld.dashboard.DatabaseCompatibility
import io.github.maxlyth.hapaneld.dashboard.DatabaseCompatibilityBoundary
import io.github.maxlyth.hapaneld.dashboard.DatabaseCompatibilityDecision
import io.github.maxlyth.hapaneld.dashboard.DatabaseOwnerState
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.persistence.AppState
import io.github.maxlyth.hapaneld.shizuku.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared privileged APK installer with a **pinned signer + package allowlist**. This is NOT a generic
 * installer: an install proceeds only if the downloaded APK declares the pinned package AND is signed
 * by the pinned certificate — a package/signer mismatch is refused, so a MITM / DNS-spoof / compromised
 * asset can't be installed even on a fresh (no-incumbent) install. Used by both the HA Companion app
 * updater and ha-paneld's own self-update.
 *
 * Install path is selected once from currently available authorities: `su` first, then the
 * peer-uid-locked helper `INSTALL` verb, then (only for explicitly allowed curated packages) the typed
 * Shizuku shell-UID service. An attempted install is never replayed through a second authority because
 * a timed-out package-manager transaction can still have committed. Arbitrary uploads and the System
 * WebView never allow Shizuku. `pm install -r -d` — the
 * `-d` (allow downgrade) is deliberate, so a stable<->pre-release channel switch can move either way.
 * Network + package installation — always call OFF the main / MQTT thread.
 */
object AppInstaller {
    data class Pin(val pkg: String, val certSha256: String, val apkSha256: String? = null)
    internal enum class InstallRoute { SU, DAEMON, SHIZUKU, NONE }
    internal enum class SelfInstallDatabaseDisposition { DIRECT, RECOVER }

    // Pinned signers (public certificate fingerprints — NOT secrets).
    private const val RELEASE_SIGNER = "ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339"
    /** The two panel-app identities share one release signer; only the package half of the pin differs. */
    val LEGACY = Pin(AppIdentity.LEGACY, RELEASE_SIGNER)
    val SUCCESSOR = Pin(AppIdentity.SUCCESSOR, RELEASE_SIGNER)
    /** This build's own pin: what a self-update candidate must match. */
    val HA_PANELD = ownPin(AppIdentity.OWN)

    /**
     * The signer the identity migration trusts for the other panel-app package: the release
     * certificate, except in a secretless debug build, whose two packages are both signed by the
     * committed debug keystore. Self-update, Guard and every other pin stay on the release signer.
     */
    internal val MIGRATION_SIGNER: String = migrationSigner(BuildConfig.MIGRATION_SIGNER_OVERRIDE)

    internal fun migrationSigner(override: String): String =
        if (override.matches(Regex("[0-9a-f]{64}"))) override else RELEASE_SIGNER

    /** The pin under which [applicationId], one of the two panel-app ids, is installed or trusted. */
    internal fun migrationPin(applicationId: String): Pin = Pin(ownPin(applicationId).pkg, MIGRATION_SIGNER)

    /** Signer SHA-256 set of an installed package, or null when it is absent or unreadable. */
    @Suppress("DEPRECATION") // GET_SIGNATURES / PackageInfo.signatures for API < 28
    internal fun installedSigners(context: Context, pkg: String): Set<String>? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageInfo(pkg, flags)
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.signingInfo?.apkContentsSigners else info.signatures
        sigs.orEmpty().mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }.getOrNull()
    val COMPANION_MINIMAL = Pin("io.homeassistant.companion.android.minimal", "11194ba809b42ddf0e1a7dec6842a59c7ff1119c5482e95febffd5c6014daa5a")

    private const val TAG = "ha-paneld/install"
    private const val MAX_APK_DOWNLOAD_BYTES = 512L * 1024L * 1024L
    private const val SELF_REPLACE_STATE_FLUSH_MS = 10_000L

    /** Exact, authenticated and database-admitted self APK. Owns [apk] until consumed or closed. */
    internal class PreparedSelfInstall internal constructor(
        private val apk: File,
        private val expectedSha256: String,
        internal val version: String,
        internal val boundary: DatabaseCompatibilityApkContract.Boundary,
        internal val databaseDisposition: SelfInstallDatabaseDisposition,
        internal val allowShizuku: Boolean,
    ) : AutoCloseable {
        private val consumed = AtomicBoolean(false)
        private val requireDirectAtConsumption = AtomicBoolean(false)

        internal fun restrictToDirectConsumption() {
            check(!consumed.get()) { "prepared install already consumed" }
            requireDirectAtConsumption.set(true)
        }

        internal fun requiresDirectAtConsumption(): Boolean = requireDirectAtConsumption.get()

        internal fun isAvailable(): Boolean = !consumed.get()

        internal fun apkPath(): String = apk.absolutePath

        internal fun consume(): File? = if (consumed.compareAndSet(false, true)) apk else null

        internal fun bytesUnchanged(): Boolean = apk.isFile && sha256(apk) == expectedSha256

        override fun close() {
            if (consumed.compareAndSet(false, true)) apk.delete()
        }
    }

    internal sealed interface SelfInstallPreparation {
        data class Ready(val prepared: PreparedSelfInstall) : SelfInstallPreparation
        data class Failed(val outcome: InstallOutcome.Failure) : SelfInstallPreparation
    }
    fun installedVersion(context: Context, pkg: String): String =
        runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName ?: "" }.getOrElse { "" }

    /**
     * Download [url], refuse unless the APK declares [pin].pkg AND is signed by [pin].certSha256, then
     * install over an available privileged route. Returns a typed [InstallOutcome] — [InstallOutcome.Succeeded]
     * or a [InstallOutcome.Failure] carrying the exact status message. The caller owns the
     * version/should-update decision.
     */
    suspend fun install(
        context: Context,
        url: String,
        pin: Pin,
        allowShizuku: Boolean = false,
        beforeInstall: (() -> Boolean)? = null,
    ): InstallOutcome = withContext(Dispatchers.IO) {
        val component = componentForPin(pin)
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) {
            return@withContext guardDbInstallBlocked(component)
        }
        val hasSu = Su.available()
        val hasDaemon = HelperClient.available()
        val hasShizuku = ShizukuBridge.available()
        if (selectInstallRoute(hasSu, hasDaemon, hasShizuku, allowShizuku) == InstallRoute.NONE)
            return@withContext retryable("skipped: no permitted installer", "install-no-permitted-route", component)

        // Preflight free space BEFORE downloading, so a large APK (a WebView build is ~250 MB) can't
        // fill /data or fail half-written on a low-storage panel. We need room for the download only —
        // the su install streams straight from it (no second /data/local/tmp copy).
        val size = contentLength(url)
        if (size > MAX_APK_DOWNLOAD_BYTES) {
            Log.w(TAG, "refusing oversized APK download: $size bytes")
            return@withContext retryable(
                "download too large (${size / 1048576} MB)",
                "install-download-too-large",
                component,
            )
        }
        val free = context.cacheDir.usableSpace
        val downloadLimit = downloadCeiling(size, free)
        if (downloadLimit <= 0L) {
            val why = if (size > 0L)
                "insufficient storage (need ${size / 1048576} MB, ${free / 1048576} MB free)"
            else "insufficient storage (no free space)"
            Log.w(TAG, why)
            return@withContext retryable(why, "install-insufficient-storage", component)
        }
        val apk = runCatching { File.createTempFile("hapaneld-dl-", ".apk", context.cacheDir) }
            .getOrElse {
                return@withContext retryable("download staging failed", "install-staging-failed", component)
            }
        withStagedFiles { staged ->
            staged.stage(apk)
            if (download(url, apk, downloadLimit) != DownloadResult.Succeeded)
                return@withStagedFiles retryable("download failed", "install-download-failed", component)
            val why = verifyApk(context, apk.absolutePath, pin)
            if (why != null) {
                Log.w(TAG, "refused install: $why")
                return@withStagedFiles rejected("refused ($why)", component)
            }
            installLocalApkAdmitted(context, apk, allowShizuku, component = component, beforeInstall = beforeInstall)
        }
    }

    /** The final dashboard choice is read after the download, immediately before the package mutation. */
    internal fun mayCommitPinnedInstall(beforeInstall: (() -> Boolean)?): Boolean =
        beforeInstall?.let { runCatching { it() }.getOrDefault(false) } ?: true

    /**
     * Stage one exact self-update candidate and prove its package, signer, database contract and live
     * database compatibility without mutating configuration, helpers or packages. The returned owned
     * capability is bound to the candidate digest and may be consumed exactly once by [installPrepared].
     */
    internal suspend fun prepareSelfInstall(
        context: Context,
        url: String,
        allowShizuku: Boolean = true,
    ): SelfInstallPreparation = withContext(Dispatchers.IO) {
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) {
            return@withContext SelfInstallPreparation.Failed(guardDbInstallBlocked("paneld"))
        }
        if (selectInstallRoute(Su.available(), HelperClient.available(), ShizukuBridge.available(), allowShizuku) ==
            InstallRoute.NONE
        ) {
            return@withContext SelfInstallPreparation.Failed(
                retryable("skipped: no permitted installer", "install-no-permitted-route", "paneld"),
            )
        }
        val size = contentLength(url)
        if (size > MAX_APK_DOWNLOAD_BYTES) {
            return@withContext SelfInstallPreparation.Failed(
                retryable("download too large", "install-download-too-large", "paneld"),
            )
        }
        val limit = downloadCeiling(size, context.cacheDir.usableSpace)
        if (limit <= 0L) {
            return@withContext SelfInstallPreparation.Failed(
                retryable("insufficient storage", "install-insufficient-storage", "paneld"),
            )
        }
        val apk = runCatching { File.createTempFile("hapaneld-prepared-", ".apk", context.cacheDir) }
            .getOrElse {
                return@withContext SelfInstallPreparation.Failed(
                    retryable("download staging failed", "install-staging-failed", "paneld"),
                )
            }
        withStagedFiles { staged ->
            staged.stage(apk)
            if (download(url, apk, limit) != DownloadResult.Succeeded) {
                return@withStagedFiles SelfInstallPreparation.Failed(
                    retryable("download failed", "install-download-failed", "paneld"),
                )
            }
            verifyApk(context, apk.absolutePath, HA_PANELD)?.let { why ->
                return@withStagedFiles SelfInstallPreparation.Failed(
                    rejected("refused ($why)", "paneld"),
                )
            }
            val info = inspect(context, apk.absolutePath)
            var admittedBoundary: DatabaseCompatibilityApkContract.Boundary? = null
            var admittedDisposition: SelfInstallDatabaseDisposition? = null
            val refusal = selfReplacementRefusal(info) { boundary ->
                val decision = compatibilityDecision(context, boundary)
                compatibilityDecisionRefusal(decision).also { why ->
                    if (why == null) {
                        admittedBoundary = boundary
                        admittedDisposition = when (decision) {
                            is DatabaseCompatibilityDecision.Direct -> SelfInstallDatabaseDisposition.DIRECT
                            is DatabaseCompatibilityDecision.Recover -> SelfInstallDatabaseDisposition.RECOVER
                            else -> null
                        }
                    }
                }
            }
            if (refusal != null || admittedBoundary == null || admittedDisposition == null) {
                return@withStagedFiles SelfInstallPreparation.Failed(
                    rejected(
                        "refused (${refusal ?: "database compatibility could not be proven"})",
                        "paneld",
                    ),
                )
            }
            val prepared = PreparedSelfInstall(
                apk = apk,
                expectedSha256 = sha256(apk),
                version = requireNotNull(info).version,
                boundary = requireNotNull(admittedBoundary),
                databaseDisposition = requireNotNull(admittedDisposition),
                allowShizuku = allowShizuku,
            )
            staged.commit()
            SelfInstallPreparation.Ready(prepared)
        }
    }

    /** Consume the exact staged capability without resolving, downloading or observing a second APK. */
    internal suspend fun installPrepared(
        context: Context,
        prepared: PreparedSelfInstall,
    ): InstallOutcome = withContext(Dispatchers.IO) {
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) {
            return@withContext guardDbInstallBlocked("paneld")
        }
        val apk = prepared.consume()
            ?: return@withContext rejected("refused (prepared install already consumed)", "paneld")
        try {
            if (!prepared.bytesUnchanged()) {
                return@withContext rejected("refused (prepared APK changed after admission)", "paneld")
            }
            installLocalApkAdmitted(
                context = context,
                apk = apk,
                allowShizuku = prepared.allowShizuku,
                admittedBoundary = prepared.boundary,
                requireDirectDatabase = prepared.requiresDirectAtConsumption(),
                component = "paneld",
            )
        } finally {
            // The capability is already consumed, so close() cannot own exceptional cleanup now.
            apk.delete()
        }
    }

    /** Metadata read from an exact APK file (no install), including its signed database boundary. */
    internal data class ApkInfo(
        val pkg: String,
        val version: String,
        val signerSha256: String?,
        val databaseCompatibility: DatabaseCompatibilityApkContract.Parsed,
        internal val signerSha256s: Set<String> = setOfNotNull(signerSha256),
        val versionCode: Long = 0L,
    )

    /** Parse an APK's package name, versionName and signer SHA-256 without installing it. Null if the file
     *  isn't a readable APK. Used to show the user WHAT they're about to install before they confirm. */
    @Suppress("DEPRECATION") // GET_SIGNATURES / PackageInfo.signatures for API < 28
    internal fun inspect(context: Context, apkPath: String): ApkInfo? {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apkPath, flags) ?: return null
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.signingInfo?.apkContentsSigners else info.signatures
        val signerHashes = sigs.orEmpty().mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
        val metadata = info.applicationInfo?.metaData
        val databaseCompatibility = if (metadata?.containsKey(DatabaseCompatibilityApkContract.METADATA_NAME) == true) {
            when (val raw = metadata.get(DatabaseCompatibilityApkContract.METADATA_NAME)) {
                is String -> DatabaseCompatibilityApkContract.parse(raw)
                else -> DatabaseCompatibilityApkContract.Parsed.Malformed(
                    "database compatibility metadata is not a string",
                )
            }
        } else {
            DatabaseCompatibilityApkContract.Parsed.Missing
        }
        return ApkInfo(
            info.packageName,
            info.versionName ?: "?",
            signerHashes.firstOrNull(),
            databaseCompatibility,
            signerHashes,
            info.versionCode.toLong(),
        )
    }

    /**
     * Authenticate and parse an exact self-replacement candidate before consulting database state.
     * The caller supplies the final compatibility decision so this pure ordering seam is testable
     * without an Android filesystem. Null means admitted; a non-null value is the refusal reason.
     */
    internal fun selfReplacementRefusal(
        info: ApkInfo?,
        decideCompatibility: (DatabaseCompatibilityApkContract.Boundary) -> String?,
    ): String? {
        if (info == null) return "unreadable candidate APK"
        if (info.pkg != HA_PANELD.pkg) return "candidate is not the running package"
        if (info.signerSha256s.size != 1 ||
            !info.signerSha256s.single().equals(HA_PANELD.certSha256, ignoreCase = true)
        ) {
            return "candidate must have exactly the pinned running-package signer"
        }
        val boundary = when (val parsed = info.databaseCompatibility) {
            DatabaseCompatibilityApkContract.Parsed.Missing -> return "candidate database compatibility metadata is missing"
            is DatabaseCompatibilityApkContract.Parsed.Malformed -> return parsed.reason
            is DatabaseCompatibilityApkContract.Parsed.Valid -> parsed.boundary
        }
        return decideCompatibility(boundary)
    }

    /** Re-authenticate the exact candidate and re-run current database observation at consumption. */
    internal fun preparedSelfReplacementRefusal(
        info: ApkInfo?,
        admittedBoundary: DatabaseCompatibilityApkContract.Boundary,
        decideCurrentCompatibility: (DatabaseCompatibilityApkContract.Boundary) -> String?,
    ): String? = selfReplacementRefusal(info) { exactBoundary ->
        if (exactBoundary != admittedBoundary) {
            "prepared APK database boundary changed after admission"
        } else {
            decideCurrentCompatibility(exactBoundary)
        }
    }

    /**
     * Decide whether a local candidate may reach installer-route selection. An admitted boundary is an
     * assertion that this exact file is a prepared self replacement, so unreadable or wrong-package
     * metadata can never silently demote it to the unrestricted non-self upload path. Unprepared files
     * must still be readable APKs before any privileged installer is selected.
     */
    internal fun localInstallCandidateRefusal(
        info: ApkInfo?,
        runningPackage: String,
        admittedBoundary: DatabaseCompatibilityApkContract.Boundary?,
        decideCurrentCompatibility: (DatabaseCompatibilityApkContract.Boundary) -> String?,
    ): String? {
        if (info == null) return "unreadable candidate APK"
        if (admittedBoundary != null) {
            if (info.pkg != runningPackage) return "prepared candidate is not the running package"
            return preparedSelfReplacementRefusal(
                info,
                admittedBoundary,
                decideCurrentCompatibility,
            )
        }
        if (info.pkg != runningPackage) return null
        return selfReplacementRefusal(info, decideCurrentCompatibility)
    }

    /** Pure fail-closed seam for the final config-commit admission of a prepared candidate. */
    internal fun preparedDirectConfigCommitRefusal(
        available: Boolean,
        bytesUnchanged: Boolean,
        info: ApkInfo?,
        admittedBoundary: DatabaseCompatibilityApkContract.Boundary,
        decideCurrentCompatibility: (DatabaseCompatibilityApkContract.Boundary) -> String?,
    ): String? {
        if (!available) return "prepared install already consumed"
        if (!bytesUnchanged) return "prepared APK changed after admission"
        return preparedSelfReplacementRefusal(
            info,
            admittedBoundary,
            decideCurrentCompatibility,
        )
    }

    /**
     * Re-hash and re-authenticate the exact staged APK, then observe the current database and require a
     * DIRECT decision. This is deliberately synchronous so a config transaction can call it immediately
     * before its atomic commit. The install-consumption gate repeats the same proof later.
     */
    internal fun revalidatePreparedDirectForConfigCommit(
        context: Context,
        prepared: PreparedSelfInstall,
    ): String? = try {
        if (!prepared.requiresDirectAtConsumption()) {
            "prepared install is not restricted to direct database consumption"
        } else {
            val available = prepared.isAvailable()
            val bytesUnchanged = available && prepared.bytesUnchanged()
            val info = if (bytesUnchanged) inspect(context, prepared.apkPath()) else null
            // DB_COMPAT_MUTATION_ANCHOR: CONFIG_COMMIT_REVALIDATE
            preparedDirectConfigCommitRefusal(
                available = available,
                bytesUnchanged = bytesUnchanged,
                info = info,
                admittedBoundary = prepared.boundary,
            ) { exactBoundary ->
                compatibilityRefusal(context, exactBoundary, requireDirect = true)
            }
        }
    } catch (_: Exception) {
        "prepared APK and database compatibility could not be proven"
    }

    private fun compatibilityRefusal(
        context: Context,
        boundary: DatabaseCompatibilityApkContract.Boundary,
        requireDirect: Boolean = false,
    ): String? = compatibilityDecisionRefusal(
        compatibilityDecision(context, boundary),
        requireDirect = requireDirect,
    )

    private fun compatibilityDecision(
        context: Context,
        boundary: DatabaseCompatibilityApkContract.Boundary,
    ): DatabaseCompatibilityDecision =
        DatabaseCompatibility.observeAndDecide(
            context,
            DatabaseCompatibilityBoundary(
                boundary.formatVersion,
                boundary.databaseName,
                boundary.minimumSchema,
                boundary.maximumSchema,
            ),
            DatabaseOwnerState.PACKAGE_PRESENT,
        )

    internal fun compatibilityDecisionRefusal(
        decision: DatabaseCompatibilityDecision,
        requireDirect: Boolean = false,
    ): String? =
        when (decision) {
            is DatabaseCompatibilityDecision.Direct -> null
            is DatabaseCompatibilityDecision.Recover -> if (requireDirect) {
                "database compatibility changed from direct to recovery after configuration admission"
            } else null
            DatabaseCompatibilityDecision.Fresh -> "installed package database is not proven present"
            is DatabaseCompatibilityDecision.Refuse ->
                "database compatibility ${decision.reason.name.lowercase().replace('_', ' ')}"
        }

    /**
     * Install an APK already on local disk through one permitted route, then delete it. Shared install tail used by both the
     * pinned-download path ([install]) and the Install-tab APK upload. [install] applies its selected pin
     * before calling this. An uploaded candidate for the running package is independently
     * signer-authenticated and database-admitted here before any mutation; non-self uploads retain their
     * explicit user-confirmation policy. Streams straight into `pm install -S` or over
     * the peer-uid-locked daemon socket; an older daemon falls back to its path-based `INSTALL` verb.
     * Shizuku is considered only when [allowShizuku] is explicitly true; arbitrary upload callers keep
     * the default false.
     * Returns a typed [InstallOutcome].
     */
    suspend fun installLocalApk(
        context: Context,
        apk: File,
        allowShizuku: Boolean = false,
    ): InstallOutcome = if (GuardDbProcessAdmission.ordinaryMutationsAllowed()) {
        installLocalApkAdmitted(context, apk, allowShizuku, admittedBoundary = null, component = "apk")
    } else {
        guardDbInstallBlocked("apk")
    }

    /** Restore an APK saved before a WebView swap through the same package, signer, hash and privileged
     *  install admission as a downloaded pin. Consumes [apk] only after the pin is verified. */
    suspend fun restorePinnedWebView(
        context: Context, apk: File, signerSha256: String, apkSha256: String,
        beforeInstall: (() -> Boolean)? = null,
    ): InstallOutcome = withContext(Dispatchers.IO) {
        val pin = Pin(WebViewInstaller.WEBVIEW_PKG, signerSha256, apkSha256)
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) return@withContext guardDbInstallBlocked("webview")
        val why = verifyApk(context, apk.absolutePath, pin)
        if (why != null) return@withContext rejected("refused ($why)", "webview")
        installLocalApkAdmitted(context, apk, allowShizuku = false, component = "webview", beforeInstall = beforeInstall)
    }

    private suspend fun installLocalApkAdmitted(
        context: Context,
        apk: File,
        allowShizuku: Boolean,
        admittedBoundary: DatabaseCompatibilityApkContract.Boundary? = null,
        requireDirectDatabase: Boolean = false,
        component: String,
        beforeInstall: (() -> Boolean)? = null,
    ): InstallOutcome = withContext(Dispatchers.IO) {
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) {
            return@withContext guardDbInstallBlocked(component)
        }
        val info = inspect(context, apk.absolutePath)
        // DB_COMPAT_MUTATION_ANCHOR: IN_APP_GATE
        val refusal = localInstallCandidateRefusal(
            info = info,
            runningPackage = context.packageName,
            admittedBoundary = admittedBoundary,
        ) { exactBoundary ->
            // Preparation protects configuration commit ordering, but database/recovery state
            // can change while the staged capability is held. Re-observe immediately before
            // the first self-replacement mutation; matching APK bytes are not current DB proof.
            compatibilityRefusal(
                context,
                exactBoundary,
                requireDirect = admittedBoundary != null && requireDirectDatabase,
            )
        }
        if (refusal != null) {
            apk.delete()
            Log.w(TAG, "refused local install: $refusal")
            return@withContext rejected("refused ($refusal)", component)
        }
        val replacingSelf = requireNotNull(info).pkg == context.packageName
        val hasSu = Su.available()
        val hasDaemon = HelperClient.available()
        val hasShizuku = ShizukuBridge.available()
        val route = selectInstallRoute(hasSu, hasDaemon, hasShizuku, allowShizuku)
        if (route == InstallRoute.NONE) {
            apk.delete()
            return@withContext retryable("skipped: no permitted installer", "install-no-permitted-route", component)
        }
        if (!mayCommitPinnedInstall(beforeInstall)) {
            apk.delete()
            return@withContext retryable(
                "skipped: dashboard changed before WebView install", "install-precondition-changed", component,
            )
        }
        // DB_COMPAT_MUTATION_ANCHOR: IN_APP_FIRST_MUTATION
        val stateQuiescence = if (replacingSelf) {
            prepareSelfReplace(
                snapshot = { ConfigUpgradeBackup.snapshot(context) },
                quiesce = { AppState.quiesceForSelfReplace(context, SELF_REPLACE_STATE_FLUSH_MS) },
                warn = { Log.w(TAG, it) },
            )
        } else null
        if (replacingSelf && stateQuiescence == null) {
            apk.delete()
            return@withContext retryable(
                "install deferred: application state is still being saved",
                "install-deferred-saving-state",
                component,
            )
        }
        var installSucceeded = false
        try {
            if (route == InstallRoute.SU) {
                val out = try {
                    // Stream the APK straight into `pm install -S <size>` — no intermediate /data/local/tmp copy
                    // (halves peak disk use). Long-timeout: staging a large stream far exceeds the 5s su bound.
                    Su.runWithStdinLong(
                        "pm install -S ${apk.length()} -r -d 2>&1",
                        apk,
                        HelperInstallTransaction.INSTALL_TIMEOUT_MS,
                    )?.trim()
                } finally {
                    apk.delete()
                }
                val result = classifyPmInstallReply(out)
                if (result == InstallOutcome.Succeeded) {
                    installSucceeded = true
                    return@withContext InstallOutcome.Succeeded
                }
                Log.w(TAG, "install failed: $out")
                return@withContext (result as InstallOutcome.Failure).withFailurePresentation(component)
            }

            val outcome: InstallOutcome = if (route == InstallRoute.DAEMON) {
                HelperInstallTransaction(HelperClient).install(
                    apk,
                    File(context.filesDir, HelperInstallTransaction.STAGING_DIR),
                )
            } else if (route == InstallRoute.SHIZUKU) {
                val out = try {
                    ShizukuBridge.installApk(apk, allowDowngrade = true, HelperInstallTransaction.INSTALL_TIMEOUT_MS)
                        ?.trim().orEmpty()
                } finally {
                    apk.delete()
                }
                if (out.contains("Success", ignoreCase = true)) InstallOutcome.Succeeded
                else installFailure(out.ifBlank { "Shizuku installer unavailable" })
            } else {
                apk.delete()
                retryable("skipped: no permitted installer", "install-no-permitted-route", component)
            }
            val presentedOutcome = outcome.withFailurePresentation(component)
            if (presentedOutcome is InstallOutcome.Failure) Log.w(TAG, presentedOutcome.message)
            installSucceeded = presentedOutcome is InstallOutcome.Succeeded
            presentedOutcome
        } finally {
            // Package-manager success may return before process replacement. Keep state mutation
            // admission quiesced across that lag; only a failed install reopens writes.
            finishSelfReplaceQuiescence(stateQuiescence, installSucceeded)
        }
    }

    private fun guardDbInstallBlocked(component: String): InstallOutcome.Retryable = retryable(
        "blocked: Guard DB maintenance owns package mutations",
        "install-guard-db-owned",
        component,
    )

    internal fun ownPin(applicationId: String): Pin = when (applicationId) {
        LEGACY.pkg -> LEGACY
        SUCCESSOR.pkg -> SUCCESSOR
        else -> error("$applicationId has no pinned panel-app identity")
    }

    private fun componentForPin(pin: Pin): String = when (pin.pkg) {
        LEGACY.pkg, SUCCESSOR.pkg -> "paneld"
        COMPANION_MINIMAL.pkg -> "companion"
        WebViewInstaller.WEBVIEW_PKG -> "webview"
        else -> "apk"
    }

    private fun presentation(code: String, component: String): InstallPresentation =
        InstallPresentation(code, mapOf("component" to component))

    private fun retryable(message: String, code: String, component: String) =
        InstallOutcome.Retryable(message, presentation(code, component))

    private fun rejected(message: String, component: String) =
        InstallOutcome.Rejected(message, presentation("install-durable-rejection", component))

    private fun InstallOutcome.withFailurePresentation(component: String): InstallOutcome = when (this) {
        InstallOutcome.Succeeded -> this
        is InstallOutcome.Rejected -> if (presentation != null) this else copy(
            presentation = AppInstaller.presentation("install-durable-rejection", component),
        )
        is InstallOutcome.Retryable -> if (presentation != null) this else copy(
            presentation = AppInstaller.presentation("install-retryable-failure", component),
        )
    }

    /**
     * Classify a `pm install` failure [output] line into a typed [InstallOutcome.Failure]. A
     * package-manager `Failure [...]` rejection is durable — retrying the same APK cannot help — so it
     * is [InstallOutcome.Rejected]; anything else (empty output, an installer-unavailable note, other
     * text) is [InstallOutcome.Retryable]. The message preserves the historical
     * `"install failed: <trimmed output>"` text exactly.
     */
    internal fun installFailure(output: String): InstallOutcome {
        val message = "install failed: ${output.take(120)}"
        return if (output.startsWith("Failure [")) InstallOutcome.Rejected(message)
        else InstallOutcome.Retryable(message)
    }

    /** A missing `su`/`pm` reply is not proof that the package mutation never happened. */
    internal fun classifyPmInstallReply(output: String?): InstallOutcome = when {
        output?.contains("Success", ignoreCase = true) == true -> InstallOutcome.Succeeded
        output.isNullOrBlank() -> InstallOutcome.Retryable(
            "install outcome unknown: pm reply lost", mayHaveCommitted = true,
        )
        else -> installFailure(output)
    }

    /**
     * Bytes the staged download may occupy, or 0 to refuse. Admission is the download's **actual**
     * requirement: a declared [size] must fit in [free] and nothing beyond it is reserved, because a
     * fixed surplus only ever refused upgrades that would have installed. A server that declares no
     * length ([size] < 0) is still bounded — by whichever of [free] and [MAX_APK_DOWNLOAD_BYTES] is
     * smaller — and only a completely full filesystem is refused outright. Space exhausted while
     * writing is reported by the write itself rather than pre-judged here.
     */
    internal fun downloadCeiling(size: Long, free: Long): Long {
        val usable = free.coerceAtLeast(0L)
        if (size > usable) return 0L
        return minOf(MAX_APK_DOWNLOAD_BYTES, usable)
    }

    /**
     * Prepare to replace the running package: take the private configuration revision, then quiesce
     * state writes. The revision is defense in depth, not a precondition — an unwritable one warns
     * through [warn] and the upgrade continues, because stranding the panel on the old build is the
     * worse outcome and nothing in the restore path requires this optional copy. [quiesce] is still
     * the gate; a null return means state is not yet safe to replace under.
     */
    internal fun prepareSelfReplace(
        snapshot: () -> Boolean,
        quiesce: () -> io.github.maxlyth.hapaneld.persistence.StateQuiescence?,
        warn: (String) -> Unit,
    ): io.github.maxlyth.hapaneld.persistence.StateQuiescence? {
        if (!snapshot()) warn(SNAPSHOT_UNWRITABLE_WARNING)
        return quiesce()
    }

    internal const val SNAPSHOT_UNWRITABLE_WARNING =
        "pre-upgrade configuration revision could not be written; continuing with the upgrade"

    internal fun finishSelfReplaceQuiescence(
        quiescence: io.github.maxlyth.hapaneld.persistence.StateQuiescence?,
        installSucceeded: Boolean,
    ) {
        if (!installSucceeded) quiescence?.close()
    }

    /** Null = APK declares [pin].pkg AND is signed by the pinned cert; else a short reason. */
    @Suppress("DEPRECATION") // GET_SIGNATURES / PackageInfo.signatures for API < 28
    private fun verifyApk(context: Context, apkPath: String, pin: Pin): String? {
        pin.apkSha256?.let { expected ->
            val actual = sha256(File(apkPath))
            if (!actual.equals(expected, ignoreCase = true)) return "APK checksum mismatch"
        }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apkPath, flags) ?: return "unreadable APK"
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.signingInfo?.apkContentsSigners else info.signatures
        val md = MessageDigest.getInstance("SHA-256")
        return pinRefusal(
            info.packageName,
            sigs.orEmpty().map { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } },
            pin,
        )
    }

    /**
     * Pure half of [verifyApk]: null when an APK declaring [declaredPackage] and signed by
     * [signerSha256s] satisfies [pin]. The package is checked first and exactly, so an APK carrying the
     * shared release signer under the other panel-app id never satisfies this id's pin.
     */
    internal fun pinRefusal(declaredPackage: String, signerSha256s: Collection<String>, pin: Pin): String? {
        if (declaredPackage != pin.pkg) return "package $declaredPackage not allowlisted"
        if (signerSha256s.isEmpty()) return "no signature"
        return if (signerSha256s.any { it.equals(pin.certSha256, true) }) null else "signer mismatch"
    }

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    internal fun selectInstallRoute(
        hasSu: Boolean,
        hasDaemon: Boolean,
        hasShizuku: Boolean,
        allowShizuku: Boolean,
    ): InstallRoute = when {
        hasSu -> InstallRoute.SU
        hasDaemon -> InstallRoute.DAEMON
        allowShizuku && hasShizuku -> InstallRoute.SHIZUKU
        else -> InstallRoute.NONE
    }

    /** The download's size in bytes from a HEAD (following HTTPS redirects), or -1 if unknown. Used to
     *  preflight free space before committing to a large download. */
    private fun contentLength(url: String): Long = runCatching {
        var current = URL(url).takeIf { it.protocol.equals("https", true) } ?: return -1L
        repeat(5) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"; instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 15_000
            }
            try {
                when (conn.responseCode) {
                    in 300..399 -> {
                        val loc = conn.getHeaderField("Location") ?: return -1L
                        current = httpsRedirect(current, loc) ?: return -1L
                    }
                    200 -> return conn.contentLengthLong
                    else -> return -1L
                }
            } finally {
                conn.disconnect()
            }
        }
        -1L
    }.getOrDefault(-1L)

    /**
     * Why a [download] did not produce bytes. The pinned [install] path collapses every non-[Succeeded]
     * value into one retryable failure, exactly as it did when this returned a Boolean; the Install-page
     * review flow reports them separately, because an administrator fetching an APK from somewhere else
     * on the network needs to know whether the panel was refused, timed out, or hit the size ceiling.
     */
    internal enum class DownloadResult { Succeeded, TooLarge, TimedOut, Aborted, Failed }

    /**
     * Download [url] to [dest], following redirects (GitHub release → CDN).
     *
     * HTTPS-only for every hop: the initial URL and each redirect target must be `https`, else the
     * fetch is refused. The APK is signer-pinned after download ([verifyApk]) on the [install] path, so
     * a substituted blob still can't install — but refusing plaintext hops closes the residual downgrade
     * (an `https→http` redirect would otherwise fetch the update over cleartext, leaking the request and
     * letting a network attacker waste the download before the pin rejects it). Real callers use GitHub
     * `https` release URLs that redirect to `https` CDNs, so this rejects nothing legitimate; the
     * Install-page URL source deliberately inherits the same rule rather than widening it.
     *
     * Redirects are followed the way any HTTP client follows them, because APK distribution depends on
     * it — GitHub releases, APKMirror and APKPure all bounce through CDN hops. See the note on the
     * Install page's URL source about why no destination filtering sits on top of that here.
     *
     * Bounded three ways, none of which trusts the peer: at most five redirect hops, a whole-operation
     * [DOWNLOAD_TOTAL_TIMEOUT_MS] deadline that a slow drip cannot outlast, and [maxBytes] enforced by
     * [copyBeforeDeadline] on bytes actually read — a lying or absent `Content-Length` cannot widen it.
     */
    internal fun download(
        url: String,
        dest: File,
        maxBytes: Long,
        abort: DownloadAbort? = null,
        openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    ): DownloadResult {
        val deadline = MonotonicDeadline(DOWNLOAD_TOTAL_TIMEOUT_MS)
        // Held as a non-null local: `current` is reassigned from inside the redirect loop, so a nullable
        // captured var would never smart-cast.
        val origin = runCatching { URL(url) }.getOrNull()?.takeIf { it.protocol.equals("https", true) }
            ?: run {
                Log.w(TAG, "refusing non-HTTPS or unparseable URL")
                return DownloadResult.Failed
            }
        var current: URL = origin
        return try {
            repeat(5) {
                val remainingMs = deadline.remainingMs()
                if (remainingMs <= 0L) return DownloadResult.TimedOut
                val conn = openConnection(current)
                conn.instanceFollowRedirects = false
                conn.connectTimeout = minOf(15_000L, remainingMs).coerceAtLeast(1L).toInt()
                conn.readTimeout = minOf(60_000L, remainingMs).coerceAtLeast(1L).toInt()
                // The single place a cancel is observed between hops. Registering before the first read
                // is what makes it effective at all: the copy loop is synchronous, so only closing the
                // connection can free a thread already blocked in read(). Attaching also reports an
                // abort that arrived earlier, so no separate pre-check is needed — and a separate one
                // would make this line impossible to prove, by answering before it is ever consulted.
                if (abort != null && !abort.attach(conn)) return DownloadResult.Aborted
                try {
                    when (conn.responseCode) {
                        in 300..399 -> {
                            conn.closeBody()
                            val loc = conn.getHeaderField("Location") ?: return DownloadResult.Failed
                            val next = httpsRedirect(current, loc)
                                ?: run { Log.w(TAG, "refusing non-HTTPS redirect"); return DownloadResult.Failed }
                            current = next
                        }
                        200 -> {
                            val declared = conn.contentLengthLong
                            if (declared > maxBytes) {
                                Log.w(TAG, "refusing oversized APK response: $declared bytes")
                                conn.closeBody()
                                return DownloadResult.TooLarge
                            }
                            conn.inputStream.use { input ->
                                dest.outputStream().use { output ->
                                    copyBeforeDeadline(input, output, maxBytes, deadline::remainingMs)
                                }
                            }
                            return if (dest.length() > 0) DownloadResult.Succeeded else DownloadResult.Failed
                        }
                        else -> {
                            conn.closeBody()
                            return DownloadResult.Failed
                        }
                    }
                } finally {
                    abort?.detach()
                    conn.disconnect()
                }
            }
            DownloadResult.Failed
        } catch (error: Exception) {
            // A closed connection surfaces as an ordinary IO failure; the owner's intent decides what it
            // actually was, so a cancel is never reported to the operator as a broken link.
            if (abort?.isAborted == true) DownloadResult.Aborted else downloadFailure(error)
        }
    }

    /**
     * Classify a failed download. Kept separate from [download] because the distinction is only
     * reachable through a live transfer otherwise, and it is the distinction the Install page shows an
     * operator: a size breach must not be reported as an unreachable host, nor a stall as a refusal.
     */
    internal fun downloadFailure(error: Exception): DownloadResult = when (error) {
        is ByteLimitExceeded -> {
            Log.w(TAG, "refusing APK response beyond the byte ceiling")
            DownloadResult.TooLarge
        }
        is java.net.SocketTimeoutException -> {
            Log.w(TAG, "APK download stalled or exceeded its deadline")
            DownloadResult.TimedOut
        }
        else -> {
            Log.w(TAG, "download error", error)
            DownloadResult.Failed
        }
    }

    /**
     * Resolve a redirect [location] (absolute or relative) against [base] and return it **only** if the
     * result is HTTPS; null means "refuse" (non-HTTPS target, or unparseable). Resolving against [base]
     * also handles relative `Location` headers, which the previous `URL(location)` mishandled.
     */
    internal fun httpsRedirect(base: URL, location: String): URL? =
        runCatching { URL(base, location) }.getOrNull()?.takeIf { it.protocol.equals("https", true) }

    internal fun copyBeforeDeadline(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        maxBytes: Long,
        remainingMs: () -> Long,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            if (remainingMs() <= 0L) throw java.net.SocketTimeoutException("APK download deadline exceeded")
            val read = input.read(buffer)
            if (read < 0) return copied
            if (read == 0) continue
            copied += read
            if (copied > maxBytes) throw ByteLimitExceeded(maxBytes)
            if (remainingMs() <= 0L) throw java.net.SocketTimeoutException("APK download deadline exceeded")
            output.write(buffer, 0, read)
        }
    }

    private const val DOWNLOAD_TOTAL_TIMEOUT_MS = 10L * 60_000L
}
