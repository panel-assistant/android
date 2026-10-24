package io.panelassistant.android.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.panelassistant.android.AppIdentity
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.panelassistant.PanelAssistantUpdatePolicy
import io.panelassistant.android.dashboard.DatabaseCompatibility
import io.panelassistant.android.dashboard.DatabaseCompatibilityBoundary
import io.panelassistant.android.dashboard.DatabaseCompatibilityDecision
import io.panelassistant.android.dashboard.DatabaseOwnerState
import io.panelassistant.android.control.Su
import io.panelassistant.android.persistence.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Shared privileged APK installer with a **pinned signer + package allowlist**. This is NOT a generic
 * installer: an install proceeds only if the downloaded APK declares the pinned package AND is signed
 * by the pinned certificate — a package/signer mismatch is refused, so a MITM / DNS-spoof / compromised
 * asset can't be installed even on a fresh (no-incumbent) install. Used by both the HA Companion app
 * updater and ha-paneld's own self-update.
 *
 * Install path is selected once from currently available authorities: `su` first, then the
 * peer-uid-locked helper `INSTALL` verb. An attempted install is never replayed through a second
 * authority because a timed-out package-manager transaction can still have committed. Panel-app
 * candidates require live Panel Assistant policy and cannot downgrade. Other approved packages retain
 * their existing downgrade behavior.
 * Network + package installation — always call OFF the main / command thread.
 */
object AppInstaller {
    data class Pin(val pkg: String, val certSha256: String, val apkSha256: String? = null)
    // Reads the current transport state rather than remembering a policy from a disconnected session.
    @Volatile internal var livePanelUpdatePolicy: (() -> PanelAssistantUpdatePolicy?)? = null
    internal fun panelUpdatePolicy(): PanelAssistantUpdatePolicy? =
        runCatching { livePanelUpdatePolicy?.invoke() }.getOrNull()
    internal const val PROTOCOL_METADATA_NAME = "io.panelassistant.android.PANEL_ASSISTANT_PROTOCOL"
    internal enum class InstallRoute { SU, DAEMON, CONFIRMED, NONE }

    // Pinned signers (public certificate fingerprints — NOT secrets).
    private const val RELEASE_SIGNER = "ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339"
    /** This app's own pin: what a self-update candidate must match. */
    val HA_PANELD = Pin(AppIdentity.OWN, RELEASE_SIGNER)
    val COMPANION_MINIMAL = Pin("io.homeassistant.companion.android.minimal", "11194ba809b42ddf0e1a7dec6842a59c7ff1119c5482e95febffd5c6014daa5a")

    /** Android System WebView, which the uninstall route refuses and the removable-app list leaves out. */
    const val WEBVIEW_PKG = "com.android.webview"

    /** Files a build before 0.9.11 kept to roll back an in-app WebView update; nothing reads them now. */
    internal fun deleteRetiredWebViewRollback(filesDir: File) = listOf(
        "webview-previous.apk", "webview-previous.json", "webview-rollback-attempted.json", "webview-rollback-diagnostic.txt",
    ).forEach { File(filesDir, it).delete() }

    private const val TAG = "ha-paneld/install"
    private const val MAX_APK_DOWNLOAD_BYTES = 512L * 1024L * 1024L
    private const val SELF_REPLACE_STATE_FLUSH_MS = 10_000L

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
        beforeInstall: (() -> Boolean)? = null,
    ): InstallOutcome = withContext(Dispatchers.IO) {
        val component = componentForPin(pin)
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) {
            return@withContext guardDbInstallBlocked(component)
        }
        if (selectInstallRoute(Su.available(), HelperClient.available()) == InstallRoute.NONE)
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
            installLocalApkAdmitted(context, apk, component = component, beforeInstall = beforeInstall)
        }
    }

    /** The final dashboard choice is read after the download, immediately before the package mutation. */
    internal fun mayCommitPinnedInstall(beforeInstall: (() -> Boolean)?): Boolean =
        beforeInstall?.let { runCatching { it() }.getOrDefault(false) } ?: true

    /** Metadata read from an exact APK file (no install), including its signed database boundary. */
    internal data class ApkInfo(
        val pkg: String,
        val version: String,
        val signerSha256: String?,
        val databaseCompatibility: DatabaseCompatibilityApkContract.Parsed,
        internal val signerSha256s: Set<String> = setOfNotNull(signerSha256),
        val versionCode: Long = 0L,
        val nativeProtocolRange: IntRange? = null,
    )

    /** Parse an APK's package name, versionName and signer SHA-256 without installing it. Null if the file
     *  isn't a readable APK. Used to show the user WHAT they're about to install before they confirm. */
    @Suppress("DEPRECATION") // GET_SIGNATURES / PackageInfo.signatures for API < 33 archives
    internal fun inspect(context: Context, apkPath: String): ApkInfo? {
        val pm = context.packageManager
        // Android 9 to 12 leave `signingInfo` empty for an APK file that is not installed, so those
        // releases also ask for the legacy signatures, which they do fill in (a rootless Android 10
        // panel read every candidate as unsigned and refused its own update).
        val flags = PackageManager.GET_META_DATA or archiveSignatureFlags(Build.VERSION.SDK_INT)
        val info = pm.getPackageArchiveInfo(apkPath, flags) ?: return null
        val sigs = archiveSigners(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else null,
            info.signatures,
        )
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong(),
            parseNativeProtocolMetadata(metadata?.get(PROTOCOL_METADATA_NAME)),
        )
    }

    /** The flags that make [PackageManager.getPackageArchiveInfo] report an APK file's signers on [sdk]. */
    @Suppress("DEPRECATION")
    internal fun archiveSignatureFlags(sdk: Int): Int = when {
        sdk >= Build.VERSION_CODES.TIRAMISU -> PackageManager.GET_SIGNING_CERTIFICATES
        sdk >= Build.VERSION_CODES.P -> PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        else -> PackageManager.GET_SIGNATURES
    }

    /** The modern signer list when Android filled it in, else the legacy one. */
    internal fun <T> archiveSigners(signingInfo: Array<T>?, legacy: Array<T>?): Array<T>? =
        signingInfo?.takeIf { it.isNotEmpty() } ?: legacy

    internal fun parseNativeProtocolMetadata(raw: Any?): IntRange? {
        val value = raw as? String ?: return null
        val match = Regex("^hapaneld-native:v1:([1-9][0-9]{0,9}):([1-9][0-9]{0,9})$")
            .matchEntire(value) ?: return null
        val low = match.groupValues[1].toIntOrNull() ?: return null
        val high = match.groupValues[2].toIntOrNull() ?: return null
        return (low..high).takeIf { low <= high }
    }

    /** The same predicate admits signed APKs and authenticated catalogue metadata. */
    @Suppress("UNUSED_PARAMETER")
    internal fun panelUpdateRefusal(
        policy: PanelAssistantUpdatePolicy?,
        candidateRange: IntRange?,
        candidateVersion: String,
        currentVersion: String,
        candidateCode: Long,
        currentCode: Long,
    ): String? {
        if (policy == null) return "Panel Assistant must be online with update policy"
        val range = candidateRange ?: return "candidate Panel Assistant protocol metadata is missing or malformed"
        if (range.first > policy.protocolMax || range.last < policy.protocolMin) {
            return "candidate cannot speak to the running Panel Assistant"
        }
        if (!policy.prerelease && candidateVersion.contains('-')) {
            return "Panel Assistant update policy permits stable builds only"
        }
        // The build number is the only order; a version name is presentation and may be renamed
        // (1.0.0-rc1 became 1.0.0-preview1 at a higher build).
        if (candidateCode < currentCode) return "panel-app downgrades are not permitted"
        return null
    }

    /** Mirror PA's finite SemVer grammar and rc10-after-rc9 identifier ordering. */
    internal fun comparePanelVersions(candidate: String, installed: String): Int? {
        fun parts(value: String): Pair<List<java.math.BigInteger>, List<String>>? {
            if (value.length > 64 || !Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?$").matches(value)) return null
            val suffix = value.substringAfter('-', "")
            val identifiers = if (suffix.isEmpty()) emptyList() else suffix.split('.')
            if (identifiers.any { it.isEmpty() || (it.all(Char::isDigit) && it.length > 1 && it.startsWith('0')) }) return null
            return value.substringBefore('-').split('.').map { it.toBigInteger() } to identifiers
        }
        val left = parts(candidate) ?: return null
        val right = parts(installed) ?: return null
        for (i in 0..2) left.first[i].compareTo(right.first[i]).takeIf { it != 0 }?.let { return it }
        if (left.second.isEmpty() || right.second.isEmpty()) {
            return left.second.isEmpty().compareTo(right.second.isEmpty())
        }
        fun identifier(value: String): Triple<Int, String, java.math.BigInteger> {
            if (value.all(Char::isDigit)) return Triple(0, "", value.toBigInteger())
            val stem = value.trimEnd { it in '0'..'9' }
            val digits = value.removePrefix(stem)
            return Triple(1, stem, if (digits.isEmpty()) (-1).toBigInteger() else digits.toBigInteger())
        }
        for (i in 0 until minOf(left.second.size, right.second.size)) {
            val a = identifier(left.second[i]); val b = identifier(right.second[i])
            val comparison = a.first.compareTo(b.first).takeIf { it != 0 }
                ?: a.second.compareTo(b.second).takeIf { it != 0 } ?: a.third.compareTo(b.third)
            if (comparison != 0) return comparison
        }
        return left.second.size.compareTo(right.second.size)
    }

    @Suppress("DEPRECATION")
    private fun currentPanelUpdateRefusal(context: Context, candidate: ApkInfo): String? {
        panelUpdateRefusal(panelUpdatePolicy(), candidate.nativeProtocolRange, candidate.version,
            BuildConfig.VERSION_NAME, candidate.versionCode, BuildConfig.VERSION_CODE.toLong())?.let { return it }
        // pm may have committed while an old process is finishing. Observe the actual package being
        // replaced as well.
        val installed = try {
            context.packageManager.getPackageInfo(candidate.pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Exception) {
            return "installed panel-app version could not be proven"
        }
        val version = installed?.versionName ?: BuildConfig.VERSION_NAME
        val code = installed?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong()
        } ?: BuildConfig.VERSION_CODE.toLong()
        return panelUpdateRefusal(panelUpdatePolicy(), candidate.nativeProtocolRange, candidate.version,
            version, candidate.versionCode, code)
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

    /** Authenticate a local candidate and read current policy/database admission before mutation. */
    internal fun localInstallCandidateRefusal(
        info: ApkInfo?,
        runningPackage: String,
        updatePolicy: PanelAssistantUpdatePolicy? = null,
        currentVersion: String = BuildConfig.VERSION_NAME,
        currentCode: Long = BuildConfig.VERSION_CODE.toLong(),
        decideCurrentCompatibility: (DatabaseCompatibilityApkContract.Boundary) -> String?,
    ): String? {
        if (info == null) return "unreadable candidate APK"
        if (info.pkg == AppIdentity.OWN) {
            if (info.signerSha256s.size != 1 ||
                !info.signerSha256s.single().equals(HA_PANELD.certSha256, ignoreCase = true)) {
                return "candidate must have exactly the pinned panel-app signer"
            }
            panelUpdateRefusal(updatePolicy, info.nativeProtocolRange, info.version, currentVersion,
                info.versionCode, currentCode)?.let { return it }
        }
        if (info.pkg != runningPackage) return null
        return selfReplacementRefusal(info, decideCurrentCompatibility)
    }

    private fun compatibilityRefusal(
        context: Context,
        boundary: DatabaseCompatibilityApkContract.Boundary,
    ): String? = compatibilityDecisionRefusal(compatibilityDecision(context, boundary))

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
    ): String? =
        when (decision) {
            is DatabaseCompatibilityDecision.Direct -> null
            is DatabaseCompatibilityDecision.Recover -> null
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
     * Returns a typed [InstallOutcome].
     */
    suspend fun installLocalApk(
        context: Context,
        apk: File,
    ): InstallOutcome = if (GuardDbProcessAdmission.ordinaryMutationsAllowed()) {
        installLocalApkAdmitted(context, apk, component = "apk")
    } else {
        guardDbInstallBlocked("apk")
    }

    private suspend fun installLocalApkAdmitted(
        context: Context,
        apk: File,
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
            updatePolicy = panelUpdatePolicy(),
        ) { exactBoundary ->
            // Database/recovery state can change while an APK is staged. Re-observe
            // before the first self-replacement mutation; matching APK bytes are not current DB proof.
            compatibilityRefusal(context, exactBoundary)
        }
        if (refusal != null) {
            apk.delete()
            Log.w(TAG, "refused local install: $refusal")
            return@withContext InstallOutcome.Rejected("refused ($refusal)")
        }
        val replacingSelf = requireNotNull(info).pkg == context.packageName
        val hasSu = Su.available()
        val hasDaemon = HelperClient.available()
        val route = selectInstallRoute(hasSu, hasDaemon, replacingSelf && ConfirmedSelfInstaller.available(context))
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
        // Re-read live authority after installer probing, immediately before our first mutation.
        if (info.pkg == AppIdentity.OWN) {
            currentPanelUpdateRefusal(context, info)?.let { why ->
                apk.delete()
                return@withContext policyFailure(why)
            }
        }
        val submissionRefusal: (() -> InstallOutcome.Failure?)? = if (info.pkg == AppIdentity.OWN) {
            { currentPanelUpdateRefusal(context, info)?.let(::policyFailure) }
        } else null
        // DB_COMPAT_MUTATION_ANCHOR: IN_APP_FIRST_MUTATION
        installWithStateQuiescence(
            route = route,
            prepare = if (replacingSelf) {
                {
                    prepareSelfReplace(
                        snapshot = { ConfigUpgradeBackup.snapshot(context) },
                        quiesce = { AppState.quiesceForSelfReplace(context, SELF_REPLACE_STATE_FLUSH_MS) },
                        warn = { Log.w(TAG, it) },
                    )
                }
            } else null,
            onDeferred = {
                apk.delete()
                retryable(
                    "install deferred: application state is still being saved",
                    "install-deferred-saving-state",
                    component,
                )
            },
            beforeSubmit = { submissionRefusal?.invoke()?.also { apk.delete() } },
        ) { submit ->
            if (route == InstallRoute.SU) {
                val out = try {
                    // Stream the APK straight into `pm install -S <size>` — no intermediate /data/local/tmp copy
                    // (halves peak disk use). Long-timeout: staging a large stream far exceeds the 5s su bound.
                    Su.runWithStdinLong(
                        "pm install -S ${apk.length()} -r ${if (info.pkg == AppIdentity.OWN) "" else "-d "}2>&1",
                        apk,
                        HelperInstallTransaction.INSTALL_TIMEOUT_MS,
                    )?.trim()
                } finally {
                    apk.delete()
                }
                val result = classifyPmInstallReply(out)
                if (result == InstallOutcome.Succeeded) {
                    return@installWithStateQuiescence InstallOutcome.Succeeded
                }
                Log.w(TAG, "install failed: $out")
                return@installWithStateQuiescence (result as InstallOutcome.Failure).withFailurePresentation(component)
            }

            val outcome: InstallOutcome = if (route == InstallRoute.CONFIRMED) {
                try {
                    ConfirmedSelfInstaller.install(context, apk, info.versionCode, submit) {
                        InstallProgress.presentRunning(
                            "Tap Install on the panel to finish the update",
                            InstallPresentation("install-awaiting-confirmation", mapOf("component" to component)),
                        )
                    }
                } finally {
                    apk.delete()
                }
            } else if (route == InstallRoute.DAEMON) {
                HelperInstallTransaction(HelperClient).install(
                    apk,
                    File(context.filesDir, HelperInstallTransaction.STAGING_DIR),
                    beforeSubmit = submissionRefusal,
                )
            } else {
                apk.delete()
                retryable("skipped: no permitted installer", "install-no-permitted-route", component)
            }
            val presentedOutcome = outcome.withFailurePresentation(component)
            if (presentedOutcome is InstallOutcome.Failure) Log.w(TAG, presentedOutcome.message)
            presentedOutcome
        }
    }

    private fun policyFailure(reason: String): InstallOutcome.Failure =
        if (reason == "Panel Assistant must be online with update policy") InstallOutcome.Retryable(reason)
        else InstallOutcome.Rejected("refused ($reason)")

    private fun guardDbInstallBlocked(component: String): InstallOutcome.Retryable = retryable(
        "blocked: Guard DB maintenance owns package mutations",
        "install-guard-db-owned",
        component,
    )

    private fun componentForPin(pin: Pin): String = when (pin.pkg) {
        HA_PANELD.pkg -> "paneld"
        COMPANION_MINIMAL.pkg -> "companion"
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
        quiesce: () -> io.panelassistant.android.persistence.StateQuiescence?,
        warn: (String) -> Unit,
    ): io.panelassistant.android.persistence.StateQuiescence? {
        if (!snapshot()) warn(SNAPSHOT_UNWRITABLE_WARNING)
        return quiesce()
    }

    /** Privileged replacement holds state through success/process death. Android's confirmed route
     * commits its session before asking for a tap, so only that submission holds the state lease. */
    internal suspend fun installWithStateQuiescence(
        route: InstallRoute,
        prepare: (() -> io.panelassistant.android.persistence.StateQuiescence?)?,
        onDeferred: () -> InstallOutcome.Failure,
        beforeSubmit: () -> InstallOutcome.Failure? = { null },
        install: suspend ((() -> Unit) -> InstallOutcome.Failure?) -> InstallOutcome,
    ): InstallOutcome {
        if (route == InstallRoute.CONFIRMED) {
            return install { commit ->
                val lease = if (prepare == null) null else prepare() ?: return@install onDeferred()
                try {
                    beforeSubmit() ?: run {
                        commit()
                        null
                    }
                } finally {
                    lease?.close()
                }
            }
        }
        val lease = if (prepare == null) null else prepare() ?: return onDeferred()
        var installSucceeded = false
        try {
            // Snapshot/flush can outlive the hello that admitted them: re-read live authority.
            beforeSubmit()?.let { return it }
            return install { commit -> commit(); null }.also {
                installSucceeded = it is InstallOutcome.Succeeded
            }
        } finally {
            finishSelfReplaceQuiescence(lease, installSucceeded)
        }
    }

    internal const val SNAPSHOT_UNWRITABLE_WARNING =
        "pre-upgrade configuration revision could not be written; continuing with the upgrade"

    internal fun finishSelfReplaceQuiescence(
        quiescence: io.panelassistant.android.persistence.StateQuiescence?,
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
     * release signer under another package id never satisfies this app's pin.
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

    /** [confirmedSelf] is true only for the running app's own update on a panel that may ask Android to
     *  install it with a tap ([ConfirmedSelfInstaller]); every other package stays root- or helper-only. */
    internal fun selectInstallRoute(
        hasSu: Boolean,
        hasDaemon: Boolean,
        confirmedSelf: Boolean = false,
    ): InstallRoute = when {
        hasSu -> InstallRoute.SU
        hasDaemon -> InstallRoute.DAEMON
        confirmedSelf -> InstallRoute.CONFIRMED
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
