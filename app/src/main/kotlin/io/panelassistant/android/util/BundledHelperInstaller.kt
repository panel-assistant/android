package io.panelassistant.android.util

import android.content.Context
import android.os.Build
import android.util.Log
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.control.Su
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * First-start migration backstop for the supported in-app self-update path.
 *
 * Provisioning remains the durable helper installer. A previously released direct-su panel can,
 * however, install a newer APK before the external provisioner runs. The new APK therefore carries
 * its exact helper and launches a root-owned `/data/local` copy when the installed daemon lacks the
 * Guard replacement protocol. Helper-only panels cannot safely replace their own old daemon and remain
 * explicitly reprovision-gated instead of accepting a generic privileged upgrade verb.
 *
 * That protection comes from OBSERVING root, not from the profile declaring it. A panel is helper-only
 * precisely when the app has no su of its own, which is exactly when [Su.available] is false, so the
 * observation already excludes the unsafe case on its own. The profile's `app_can_su` is an
 * attempt-order hint — the same thing Issue #21 established for metric routes — and it used to veto
 * this path before the probe could speak. That cost real capability rather than buying safety: a panel
 * whose owner has flashed a firmware with root still carries a profile written against the stock image,
 * so it reported `app_can_su: false` and never installed the helper it could plainly have installed.
 *
 * A direct-su panel with no helper at all (a fresh install that no provisioner touched, which is every
 * panel Panel Assistant installs) gets the same root-owned `/data/local` copy, started again on each
 * service start because nothing registers it for boot.
 */
internal object BundledHelperInstaller {
    enum class Result {
        ALREADY_CURRENT,
        INSTALLED,
        SKIPPED,
        REPROVISION_REQUIRED,
        BLOCKED_ACTIVE,
        FAILED,
    }

    /** Resume only an exact current-build ephemeral candidate; never stages or starts a replacement. */
    @Synchronized
    fun resumeRetainedCurrent(context: Context): Boolean = resumeRetainedCurrentOutcome(context) == true

    /** Why the last [ensureCurrent] refused or failed, for the caller's log line; null when it had no reason. */
    @Volatile
    var lastReason: String? = null
        private set

    @Synchronized
    fun ensureCurrent(context: Context): Result {
        lastReason = null
        return ensureCurrentAttempt(context, retryStalled = true)
    }

    private fun refused(result: Result, reason: String): Result {
        lastReason = reason
        Log.w(TAG, "bundled helper $result: $reason")
        return result
    }

    private fun ensureCurrentAttempt(context: Context, retryStalled: Boolean): Result {
        when (resumeRetainedCurrentOutcome(context)) {
            true -> return Result.INSTALLED
            false -> return Result.FAILED
            null -> Unit
        }
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) return Result.BLOCKED_ACTIVE
        val companionSupported = HelperClient.supportsCompanionData()
        val bundledBuildMatches = HelperClient.matchesBundledHelper()
        val guardSupported = companionSupported && bundledBuildMatches && GuardDbMaintenance.client.supported()
        val admission = bundledHelperAdmission(
            bundledBuildMatches = bundledBuildMatches,
            companionSupported = companionSupported,
            guardSupported = guardSupported,
            rootObserved = { Su.available() },
        )
        if (admission == Result.REPROVISION_REQUIRED) lastReason = "equal_build_not_canonical"
        bundledHelperStartGate(
            admission,
            currentLegacyCustody = {
                if (Su.available()) {
                    currentBundledLegacyTakeoverRecordResult(BuildConfig.HELPER_BUILD_ID)?.also { result ->
                        Log.w(TAG, "current helper has legacy takeover custody requiring $result")
                    }
                } else null
            },
            systemCustody = { systemCustodyMigrationOutcome(resumeOnly = false) },
        )?.let { return it }
        val stagedBuildId = BuildConfig.HELPER_BUILD_ID
            .takeIf(GuardDbMaintenanceProtocol::validSha256) ?: return Result.FAILED
        val asset = helperAssetName(Build.SUPPORTED_ABIS.asIterable()) ?: return Result.SKIPPED
        // A journal the deleted installers left before this APK arrived can no longer stop its helper.
        val appInstalledAt = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime / 1000
        }.getOrDefault(0L).coerceAtLeast(0L)
        val staged = runCatching { File.createTempFile("hapaneld-helper-", ".bin", context.cacheDir) }
            .getOrElse { return Result.FAILED }
        return try {
            context.assets.open(asset).use { input -> staged.outputStream().use(input::copyTo) }
            val expectedSha256 = AppInstaller.sha256(staged)
            // Reacquire authority after native reconciliation. Staging still independently proves
            // the replacement namespace empty before it can touch any candidate bytes.
            val statusProbe = bundledHelperReplacementStatusAfterRecovery(staged, expectedSha256)
                ?: return Result.FAILED
            val replacementProbe = bundledHelperReplacementProbe(statusProbe) {
                probeBundledLegacyHelper(staged, expectedSha256, stagedBuildId)
            } ?: return Result.FAILED
            val probedMode = bundledHelperReplacementMode(replacementProbe.status) ?: return Result.BLOCKED_ACTIVE
            val incumbentBuildId = if (probedMode == BundledHelperReplacementMode.FRESH_INSTALL) {
                null
            } else {
                (replacementProbe.incumbentBuildId ?: HelperClient.installedBuildId())
                    ?.takeIf { it != stagedBuildId } ?: return Result.FAILED
            }
            val incumbentIdentity = if (probedMode == BundledHelperReplacementMode.GUARDED_RETIRE) {
                GuardDbMaintenance.client.selfIdentity()
            } else null
            // A Guard helper that cannot name itself is reprovision-gated unless it is exactly layout F.
            val replacementMode = bundledHelperCustodyMigrationMode(probedMode, incumbentIdentity) {
                probeSystemCustodyLayout().also { layout ->
                    layout.inventory.forEach { line -> Log.i(TAG, "system custody probe: $line") }
                    if (layout is SystemCustodyLayout.Refused) lastReason = layout.reason
                }
            }
            if (replacementMode == BundledHelperReplacementMode.GUARDED_RETIRE && incumbentIdentity != null &&
                incumbentIdentity.buildId != incumbentBuildId
            ) lastReason = "incumbent_identity_mismatch"
            val priorRecordReply = Su.runOutputLong(bundledLegacyTakeoverRecordReadCommand(), INSTALL_TIMEOUT_MS)
            val priorRecord = priorRecordReply?.let {
                parseBundledLegacyTakeoverRecord(it) ?: return Result.FAILED
            }
            val result = installBundledHelperReplacement(
                replacementMode, stagedBuildId, incumbentBuildId, incumbentIdentity, priorRecord,
                stage = {
                    bundledHelperStaged(Su.runWithStdinLongChecked(
                        bundledHelperStageCommand(
                            expectedSha256,
                            appInstalledAtEpochSeconds = appInstalledAt,
                            retainedRecord = priorRecord,
                        ),
                        staged,
                        INSTALL_TIMEOUT_MS,
                    ))
                },
                guarded = {
                    executeBundledHelperReplacement(
                        retire = {
                            GuardDbMaintenance.client.retireApp(
                                freshBundledHelperReplacementNonce(),
                                expectedSha256,
                                stagedBuildId,
                            )
                        },
                        probe = { HelperClient.replacementProbe(stagedBuildId, requireNotNull(incumbentBuildId)) },
                        pause = { Thread.sleep(SETTLEMENT_POLL_MS) },
                        polls = SETTLEMENT_POLLS,
                    )
                },
                legacy = {
                    Su.runSingleAttempt(
                        bundledLegacyHelperTakeoverCommand(
                            expectedSha256,
                            stagedBuildId,
                            requireNotNull(incumbentBuildId),
                        ),
                        LEGACY_TAKEOVER_TIMEOUT_MS,
                    )
                },
                fresh = {
                    Su.runSingleAttempt(
                        bundledFreshHelperInstallCommand(
                            expectedSha256,
                            stagedBuildId,
                            appInstalledAtEpochSeconds = appInstalledAt,
                        ),
                        LEGACY_TAKEOVER_TIMEOUT_MS,
                    )
                },
                cleanup = { cleanupAnyBundledLegacyTakeoverRecord() },
                migrate = {
                    Su.runSingleAttempt(
                        bundledLegacyHelperTakeoverCommand(
                            expectedSha256,
                            stagedBuildId,
                            requireNotNull(incumbentBuildId),
                            systemCustodyMigration = true,
                        ),
                        LEGACY_TAKEOVER_TIMEOUT_MS,
                    ).also { if (!it) lastReason = "custody_migration_refused" }
                },
            )
            if (result == Result.REPROVISION_REQUIRED && lastReason == null) lastReason = "legacy_record_other_build"
            retryBundledHelperAfterStalledReplacement(
                result, retryStalled,
                statusProbe = GuardDbMaintenance.client::statusProbe,
                reconcile = { reconcileBundledHelperReplacement(staged, expectedSha256) },
                retry = { ensureCurrentAttempt(context, retryStalled = false) },
            )
        } catch (failure: Exception) {
            Log.w(TAG, "bundled helper migration failed", failure)
            Result.FAILED
        } finally {
            staged.delete()
        }
    }

    /**
     * A layout F migration record outlives the app process and the reboot that proves it. Each start drives it
     * forward through the same takeover shell (finishing any cut, or relaunching the bundled copy the app lease
     * way once nothing registers a helper), then retires it once the bundled build has served across a reboot.
     * Null when there is no record, or when it was retired or kept and the ordinary path should continue.
     */
    private fun systemCustodyMigrationOutcome(resumeOnly: Boolean): Result? {
        val reply = Su.runOutputLong(systemCustodyMigrationRecordReadCommand(), INSTALL_TIMEOUT_MS) ?: return null
        val record = parseSystemCustodyMigrationRecord(reply) ?: return refused(Result.FAILED, "custody_record_malformed")
        if (resumeOnly && !systemCustodyResumeOnlyAllowed(record, BuildConfig.HELPER_BUILD_ID)) return null
        if (record.stagedBuildId != BuildConfig.HELPER_BUILD_ID) {
            return refused(Result.REPROVISION_REQUIRED, "custody_record_other_build")
        }
        if (!resumeOnly && !GuardDbProcessAdmission.ordinaryMutationsAllowed()) return Result.BLOCKED_ACTIVE
        val serving = when (val status = GuardDbMaintenance.client.statusProbe()) {
            is GuardDbMaintenanceClient.StatusProbe.Valid -> {
                if (status.status.ownsMaintenance) return Result.BLOCKED_ACTIVE
                GuardDbMaintenance.client.selfIdentity()
            }
            GuardDbMaintenanceClient.StatusProbe.Unreachable -> null
            else -> return refused(Result.FAILED, "custody_status_unusable")
        }
        if (serving != null && serving != record.candidateIdentity) return refused(Result.FAILED, "custody_foreign_helper")
        val resumed = !(record.phase == SYSTEM_CUSTODY_PHASE_REMOVED && serving != null)
        if (resumed) {
            val driven = Su.runSingleAttempt(
                bundledLegacyHelperTakeoverCommand(
                    record.candidateSha256, record.stagedBuildId, record.incumbentBuildId,
                    systemCustodyMigration = true,
                ),
                LEGACY_TAKEOVER_TIMEOUT_MS,
            )
            if (!driven) return refused(Result.FAILED, "custody_migration_incomplete")
            // A record written before this run's removal settles only after the next reboot.
            if (record.phase != SYSTEM_CUSTODY_PHASE_REMOVED) return Result.INSTALLED
            if (GuardDbMaintenance.client.selfIdentity() != record.candidateIdentity) {
                return refused(Result.FAILED, "custody_candidate_unproved")
            }
        }
        val settlement = Su.runOutputLong(systemCustodyMigrationSettleCommand(record), INSTALL_TIMEOUT_MS)
            ?: return refused(Result.FAILED, "custody_settle_refused")
        settlement.lineSequence().filter { it.isNotBlank() }.forEach { Log.i(TAG, "system custody settle: $it") }
        return if (resumed) Result.INSTALLED else null
    }

    private fun resumeRetainedCurrentOutcome(context: Context): Boolean? {
        val stagedBuildId = BuildConfig.HELPER_BUILD_ID
            .takeIf(GuardDbMaintenanceProtocol::validSha256) ?: return false
        val statusProbe = GuardDbMaintenance.client.statusProbe()
        val retainedLegacy = if (statusProbe == GuardDbMaintenanceClient.StatusProbe.Unreachable ||
            statusProbe == GuardDbMaintenanceClient.StatusProbe.Unsupported
        ) {
            Su.runOutputLong(bundledLegacyTakeoverRecordReadCommand(), INSTALL_TIMEOUT_MS)
                ?.let(::parseBundledLegacyTakeoverRecord)
        } else {
            null
        }
        // Writer-free startup can run before ensureCurrent; after the reboot that follows a removal nothing
        // registers a helper, so the exact recorded candidate is relaunched and the record settled here.
        if (statusProbe == GuardDbMaintenanceClient.StatusProbe.Unreachable && retainedLegacy == null) {
            when (systemCustodyMigrationOutcome(resumeOnly = true)) {
                null -> Unit
                Result.INSTALLED -> return true
                else -> return false
            }
        }
        return resumeBundledLegacyHelper(
            statusProbe, retainedLegacy, stagedBuildId,
            bundledIdentity = {
                val asset = helperAssetName(Build.SUPPORTED_ABIS.asIterable()) ?: return@resumeBundledLegacyHelper null
                runCatching {
                    val bytes = context.assets.open(asset).use { it.readBytes() }
                    GuardDbMaintenanceProtocol.SelfIdentity(
                        bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it) }, stagedBuildId,
                    )
                }.getOrNull()
            },
            rebind = ::settleBundledLegacyTakeoverRecord,
            resume = { record ->
                Su.runSingleAttempt(
                    bundledLegacyHelperResumeCommand(
                        record.candidateSha256, record.stagedBuildId, record.incumbentBuildId,
                    ),
                    LEGACY_TAKEOVER_TIMEOUT_MS,
                ) && GuardDbMaintenance.client.statusProbe() is GuardDbMaintenanceClient.StatusProbe.Valid
            },
        )
    }

    /** A different app build may not discard the record that makes the live helper reboot-recoverable. */
    private fun currentBundledLegacyTakeoverRecordResult(currentBuildId: String): Result? {
        val reply = Su.runOutputLong(bundledLegacyTakeoverRecordReadCommand(), INSTALL_TIMEOUT_MS)
            ?: return if (verifyBundledLegacyTakeoverRecordAbsent()) null else Result.FAILED
        val record = parseBundledLegacyTakeoverRecord(reply) ?: return Result.FAILED
        val identity = GuardDbMaintenance.client.selfIdentity()
            ?.takeIf { it.buildId == currentBuildId } ?: return Result.REPROVISION_REQUIRED
        return if (settleBundledLegacyTakeoverRecord(record, identity)) null else Result.FAILED
    }

    private fun cleanupAnyBundledLegacyTakeoverRecord(): Boolean {
        val reply = Su.runOutputLong(bundledLegacyTakeoverRecordReadCommand(), INSTALL_TIMEOUT_MS)
            ?: return verifyBundledLegacyTakeoverRecordAbsent()
        val record = parseBundledLegacyTakeoverRecord(reply) ?: return false
        val identity = GuardDbMaintenance.client.selfIdentity()
            ?.takeIf { it.buildId == BuildConfig.HELPER_BUILD_ID } ?: return false
        return settleBundledLegacyTakeoverRecord(record, identity)
    }

    private fun settleBundledLegacyTakeoverRecord(
        record: BundledLegacyTakeoverRecord,
        identity: GuardDbMaintenanceProtocol.SelfIdentity,
    ): Boolean =
        Su.runLong(
            bundledLegacyTakeoverRecordCleanupCommand(
                record.recordSha256, record.recordBytes,
                preserveIfOldAuthorityExact = record.copy(
                    stagedBuildId = identity.buildId,
                    candidateSha256 = identity.sha256,
                    candidateBytes = identity.bytes,
                ),
            ),
            INSTALL_TIMEOUT_MS,
        )

    private fun verifyBundledLegacyTakeoverRecordAbsent(): Boolean = Su.runLong(
        bundledLegacyTakeoverRecordCleanupCommand(NO_RECORD_SHA256, 0),
        INSTALL_TIMEOUT_MS,
    )

    private const val TAG = "ha-paneld/helper-migrate"
    private const val INSTALL_TIMEOUT_MS = 30_000L
    private const val SETTLEMENT_POLLS = 120
    private const val SETTLEMENT_POLL_MS = 250L
}

/**
 * Whether this migration may proceed, and why not when it may not.
 *
 * Deliberately takes no device-profile argument. The profile's `app_can_su` is an attempt-order hint,
 * and a hint standing in for an observation is what Issue #21 corrected elsewhere; here it used to veto
 * the migration outright, which denied the helper to any panel whose owner had flashed a rooted
 * firmware while its profile still described the stock image. Root readiness is read live only when
 * a different helper build is eligible for replacement. An equal-build daemon must also expose the
 * exact Companion and autonomous/supervised/terminal Guard surfaces; an equal but noncanonical daemon
 * is left untouched for explicit reprovisioning. `rootObserved` is a lambda so neither terminal
 * equal-build result probes root.
 *
 * Returns the terminal result, or null when the caller should continue with the install.
 */
internal fun bundledHelperAdmission(
    bundledBuildMatches: Boolean,
    companionSupported: Boolean,
    guardSupported: Boolean,
    rootObserved: () -> Boolean,
): BundledHelperInstaller.Result? = when {
    bundledHelperIsCanonical(bundledBuildMatches, companionSupported, guardSupported) ->
        BundledHelperInstaller.Result.ALREADY_CURRENT
    bundledBuildMatches -> BundledHelperInstaller.Result.REPROVISION_REQUIRED
    !rootObserved() -> BundledHelperInstaller.Result.SKIPPED
    else -> null
}

internal fun bundledHelperIsCanonical(
    bundledBuildMatches: Boolean,
    companionSupported: Boolean,
    guardSupported: Boolean,
): Boolean = bundledBuildMatches && companionSupported && guardSupported

/** The stage command's reply ends with a newline, so its marker is the last non-blank line. */
internal fun bundledHelperStaged(output: String?): Boolean =
    output?.lineSequence()?.lastOrNull { it.isNotBlank() }?.trim() == "STAGED_OK"

internal fun helperAssetName(abis: Iterable<String>): String? = when {
    abis.any { it == "arm64-v8a" } -> "hapaneld-helper-arm64"
    abis.any { it == "armeabi-v7a" } -> "hapaneld-helper-arm"
    else -> null
}

internal data class BundledLegacyTakeoverRecord(
    val topology: String,
    val oldSha256: String,
    val oldBytes: Long,
    val registrationSha256: String,
    val registrationBytes: Long,
    val registrationMode: Int,
    val incumbentBuildId: String,
    val stagedBuildId: String,
    val candidateSha256: String,
    val candidateBytes: Long,
    val recordSha256: String,
    val recordBytes: Long,
)

internal fun bundledLegacyRecoveryAllowed(
    status: GuardDbMaintenanceClient.StatusProbe,
    record: BundledLegacyTakeoverRecord?,
): Boolean = status in setOf(
    GuardDbMaintenanceClient.StatusProbe.Unreachable,
    GuardDbMaintenanceClient.StatusProbe.Unsupported,
) && record != null

/** Recover finalized replacement even if its app died before rebinding the legacy boot record. */
internal fun resumeBundledLegacyHelper(
    status: GuardDbMaintenanceClient.StatusProbe,
    record: BundledLegacyTakeoverRecord?,
    currentBuildId: String,
    bundledIdentity: () -> GuardDbMaintenanceProtocol.SelfIdentity?,
    rebind: (BundledLegacyTakeoverRecord, GuardDbMaintenanceProtocol.SelfIdentity) -> Boolean,
    resume: (BundledLegacyTakeoverRecord) -> Boolean,
): Boolean? {
    if (!bundledLegacyRecoveryAllowed(status, record)) return null
    var recovery = requireNotNull(record)
    if (recovery.stagedBuildId != currentBuildId) {
        val identity = bundledIdentity()?.takeIf { it.buildId == currentBuildId } ?: return false
        if (!rebind(recovery, identity)) return false
        recovery = recovery.copy(
            stagedBuildId = identity.buildId,
            candidateSha256 = identity.sha256,
            candidateBytes = identity.bytes,
        )
    }
    return resume(recovery)
}

internal fun parseBundledLegacyTakeoverRecord(reply: String): BundledLegacyTakeoverRecord? {
    if ('\r' in reply || reply.toByteArray(Charsets.UTF_8).size !in 1..1024) return null
    val line = if (reply.endsWith('\n')) reply.dropLast(1) else reply
    if ('\n' in line) return null
    val fields = line.split(' ')
    if (fields.size != 13 || fields[0] != "OK" || fields[1] != "LEGACYTAKEOVER" || fields[2] != "1") {
        return null
    }
    if (fields[3] !in setOf("system", "systemless", "hybrid") ||
        fields[5].toLongOrNull()?.let { it in 1..MAX_STAGED_HELPER_BYTES } != true ||
        fields[7].toLongOrNull()?.let { it in 1..MAX_STAGED_HELPER_BYTES } != true ||
        fields[8] !in setOf("644", "755") ||
        fields[12].toLongOrNull()?.let { it in 1..MAX_STAGED_HELPER_BYTES } != true ||
        listOf(fields[4], fields[6], fields[9], fields[10], fields[11]).any {
            !GuardDbMaintenanceProtocol.validSha256(it)
        }
    ) {
        return null
    }
    val recordBytes = reply.toByteArray(Charsets.UTF_8)
    val recordSha256 = MessageDigest.getInstance("SHA-256").digest(recordBytes)
        .joinToString("") { byte -> "%02x".format(byte) }
    return BundledLegacyTakeoverRecord(
        topology = fields[3],
        oldSha256 = fields[4],
        oldBytes = fields[5].toLong(),
        registrationSha256 = fields[6],
        registrationBytes = fields[7].toLong(),
        registrationMode = fields[8].toInt(),
        incumbentBuildId = fields[9],
        stagedBuildId = fields[10],
        candidateSha256 = fields[11],
        candidateBytes = fields[12].toLong(),
        recordSha256 = recordSha256,
        recordBytes = recordBytes.size.toLong(),
    )
}

internal fun bundledLegacyTakeoverRecordReadCommand(filesystemRoot: String = ""): String {
    require(filesystemRoot.isEmpty() ||
        (filesystemRoot.startsWith("/") && !filesystemRoot.endsWith("/") &&
            SAFE_ABSOLUTE_PATH.matches(filesystemRoot) &&
            filesystemRoot.split('/').none { it == ".." }))
    return """
    record=$filesystemRoot/data/local/.hapaneld-helper.legacy-takeover
    [ -f "${'$'}record" ] && [ ! -L "${'$'}record" ] &&
    meta=${'$'}(stat -c '%u:%g:%a:%h:%s' "${'$'}record" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${'$'}record" 2>/dev/null) &&
    case "${'$'}meta" in 0:0:600:1:*) ;; *) false ;; esac &&
    bytes=${'$'}{meta##*:} && [ "${'$'}bytes" -ge 1 ] && [ "${'$'}bytes" -le 1024 ] &&
    dd if="${'$'}record" bs=1025 count=1 2>/dev/null
""".trimIndent()
}

internal fun bundledLegacyTakeoverOldAuthorityExactCommand(
    record: BundledLegacyTakeoverRecord,
    filesystemRoot: String = "",
): String {
    require(filesystemRoot.isEmpty() ||
        (filesystemRoot.startsWith("/") && !filesystemRoot.endsWith("/") &&
            SAFE_ABSOLUTE_PATH.matches(filesystemRoot) &&
            filesystemRoot.split('/').none { it == ".." }))
    val (binary, registration, foreign) = when (record.topology) {
        "system" -> Triple(
            "system/bin/hapaneld-helper",
            "system/etc/init/hapaneld-helper.rc",
            listOf(
                "data/adb/hapaneld/hapaneld-helper",
                "data/adb/service.d/hapaneld-helper.sh",
                "vendor/etc/init/hapaneld-helper.rc",
            ),
        )
        "systemless" -> Triple(
            "data/adb/hapaneld/hapaneld-helper",
            "data/adb/service.d/hapaneld-helper.sh",
            listOf(
                "system/bin/hapaneld-helper",
                "system/etc/init/hapaneld-helper.rc",
                "vendor/etc/init/hapaneld-helper.rc",
            ),
        )
        "hybrid" -> Triple(
            "data/adb/hapaneld/hapaneld-helper",
            "vendor/etc/init/hapaneld-helper.rc",
            listOf(
                "system/bin/hapaneld-helper",
                "system/etc/init/hapaneld-helper.rc",
                "data/adb/service.d/hapaneld-helper.sh",
            ),
        )
        else -> error("invalid parsed takeover topology")
    }
    val foreignChecks = foreign.joinToString(" &&\n  ") {
        "[ ! -e \"\$root/$it\" ] && [ ! -L \"\$root/$it\" ]"
    }
    return """
        root=$filesystemRoot
        binary=${'$'}root/$binary
        registration=${'$'}root/$registration
        [ -f "${'$'}binary" ] && [ ! -L "${'$'}binary" ] &&
        [ -f "${'$'}registration" ] && [ ! -L "${'$'}registration" ] &&
        binary_meta=${'$'}(stat -c '%u:%g:%a:%h:%s' "${'$'}binary" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${'$'}binary" 2>/dev/null) &&
        [ "${'$'}binary_meta" = "0:0:755:1:${record.oldBytes}" ] &&
        binary_hash=${'$'}(sha256sum "${'$'}binary" 2>/dev/null || toybox sha256sum "${'$'}binary" 2>/dev/null) &&
        [ "${'$'}{binary_hash%% *}" = "${record.oldSha256}" ] &&
        registration_meta=${'$'}(stat -c '%u:%g:%a:%h:%s' "${'$'}registration" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${'$'}registration" 2>/dev/null) &&
        [ "${'$'}registration_meta" = "0:0:${record.registrationMode}:1:${record.registrationBytes}" ] &&
        registration_hash=${'$'}(sha256sum "${'$'}registration" 2>/dev/null || toybox sha256sum "${'$'}registration" 2>/dev/null) &&
        [ "${'$'}{registration_hash%% *}" = "${record.registrationSha256}" ] &&
        $foreignChecks &&
        [ ! -e "${'$'}root/system/bin/hapaneld-ledd" ] && [ ! -L "${'$'}root/system/bin/hapaneld-ledd" ] &&
        [ ! -e "${'$'}root/system/etc/init/hapaneld-ledd.rc" ] && [ ! -L "${'$'}root/system/etc/init/hapaneld-ledd.rc" ]
    """.trimIndent()
}

internal fun bundledLegacyTakeoverRecordCleanupCommand(
    expectedRecordSha256: String,
    expectedRecordBytes: Long,
    filesystemRoot: String = "",
    preserveIfOldAuthorityExact: BundledLegacyTakeoverRecord? = null,
): String {
    require(GuardDbMaintenanceProtocol.validSha256(expectedRecordSha256))
    require(expectedRecordBytes in 0..1024)
    require(filesystemRoot.isEmpty() ||
        (filesystemRoot.startsWith("/") && !filesystemRoot.endsWith("/") &&
            SAFE_ABSOLUTE_PATH.matches(filesystemRoot) &&
            filesystemRoot.split('/').none { it == ".." }))
    val preserveExact = preserveIfOldAuthorityExact?.let { record ->
        require(GuardDbMaintenanceProtocol.validSha256(record.stagedBuildId))
        require(GuardDbMaintenanceProtocol.validSha256(record.candidateSha256))
        require(record.candidateBytes in 1..MAX_STAGED_HELPER_BYTES)
        val line = "OK LEGACYTAKEOVER 1 ${record.topology} ${record.oldSha256} ${record.oldBytes} " +
            "${record.registrationSha256} ${record.registrationBytes} ${record.registrationMode} " +
            "${record.incumbentBuildId} ${record.stagedBuildId} ${record.candidateSha256} ${record.candidateBytes}"
        """
        (
        ${bundledLegacyTakeoverOldAuthorityExactCommand(record, filesystemRoot).prependIndent("  ")}
        ) || exit 1
        live=${'$'}root/data/local/hapaneld-helper
        [ -f "${'$'}live" ] && [ ! -L "${'$'}live" ] || exit 1
        live_meta=${'$'}(stat -c '%u:%g:%a:%h:%s' "${'$'}live" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${'$'}live" 2>/dev/null) || exit 1
        [ "${'$'}live_meta" = '0:0:700:1:${record.candidateBytes}' ] || exit 1
        live_hash=${'$'}(sha256sum "${'$'}live" 2>/dev/null || toybox sha256sum "${'$'}live" 2>/dev/null) || exit 1
        [ "${'$'}{live_hash%% *}" = '${record.candidateSha256}' ] || exit 1
        # Native replacement owns in-flight journals and previous bytes. Rebind only settled custody.
        for authority in $BUNDLED_HELPER_FOREIGN_JOURNALS \
          "${'$'}root/data/local/.hapaneld-helper.previous" \
          "${'$'}root/data/local/.hapaneld-helper.previous.tmp"; do
          [ ! -e "${'$'}authority" ] && [ ! -L "${'$'}authority" ] || exit 75
        done
        temporary=${'$'}record.tmp
        if [ -e "${'$'}temporary" ] || [ -L "${'$'}temporary" ]; then
          [ -f "${'$'}temporary" ] && [ ! -L "${'$'}temporary" ] || exit 1
          temporary_meta=${'$'}(stat -c '%u:%g:%a:%h:%s' "${'$'}temporary" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${'$'}temporary" 2>/dev/null) || exit 1
          case "${'$'}temporary_meta" in 0:0:600:1:*) ;; *) exit 1 ;; esac
          [ "${'$'}{temporary_meta##*:}" -le 1024 ] || exit 1
        fi
        if [ "${'$'}(cat "${'$'}record")" = '$line' ]; then
          rm -f "${'$'}temporary" && sync || exit 1
          exit 0
        fi
        umask 077
        printf '%s\n' '$line' > "${'$'}temporary" || exit 1
        chown 0:0 "${'$'}temporary" && chmod 600 "${'$'}temporary" || exit 1
        sync && record_exact || exit 1
        mv -f "${'$'}temporary" "${'$'}record" && sync || exit 1
        exit 0
        """.trimIndent()
    } ?: "exit 1"
    return """
        root=$filesystemRoot
        lock=${'$'}root/dev/.hapaneld-helper-transaction.lock
        if ! mkdir "${'$'}lock" 2>/dev/null; then
          holder=${'$'}(cat "${'$'}lock/pid" 2>/dev/null || true)
          case "${'$'}holder" in ''|*[!0-9]*) exit 75 ;; *) [ ! -d "/proc/${'$'}holder" ] || exit 75 ;; esac
          rm -rf "${'$'}lock" 2>/dev/null || exit 75
          mkdir "${'$'}lock" 2>/dev/null || exit 75
        fi
        echo ${'$'}${'$'} > "${'$'}lock/pid" || { rm -rf "${'$'}lock"; exit 75; }
        cleanup_record_lock() { rm -rf "${'$'}lock"; }
        trap cleanup_record_lock 0
        trap 'cleanup_record_lock; trap - 0; exit 74' 1 2 3 15
        record=${'$'}root/data/local/.hapaneld-helper.legacy-takeover
        if [ ! -e "${'$'}record" ] && [ ! -L "${'$'}record" ]; then
          [ $expectedRecordBytes -eq 0 ] || exit 1
          exit 0
        fi
        [ $expectedRecordBytes -ge 1 ] || exit 1
        record_exact() {
          [ -f "${'$'}record" ] && [ ! -L "${'$'}record" ] || return 1
          meta=${'$'}(stat -c '%u:%g:%a:%h:%s' "${'$'}record" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${'$'}record" 2>/dev/null) || return 1
          [ "${'$'}meta" = "0:0:600:1:$expectedRecordBytes" ] || return 1
          record_hash=${'$'}(sha256sum "${'$'}record" 2>/dev/null || toybox sha256sum "${'$'}record" 2>/dev/null) || return 1
          [ "${'$'}{record_hash%% *}" = '$expectedRecordSha256' ]
        }
        record_exact || exit 1
        $preserveExact
    """.trimIndent()
}

internal enum class BundledHelperReplacementMode {
    GUARDED_RETIRE,
    RELEASED_LEGACY_TAKEOVER,
    FRESH_INSTALL,
    SYSTEM_CUSTODY_MIGRATION,
}

internal enum class BundledLegacyPriorRecordDisposition { KEEP, REPROVISION }

/** The installer's mutation boundary: admit custody, stage once, then use exactly one authority. */
internal fun installBundledHelperReplacement(
    mode: BundledHelperReplacementMode,
    stagedBuildId: String,
    incumbentBuildId: String?,
    incumbentIdentity: GuardDbMaintenanceProtocol.SelfIdentity?,
    priorRecord: BundledLegacyTakeoverRecord?,
    stage: () -> Boolean,
    guarded: () -> BundledHelperReplacementSettlement,
    legacy: () -> Boolean,
    fresh: () -> Boolean,
    cleanup: () -> Boolean,
    migrate: () -> Boolean = { false },
): BundledHelperInstaller.Result {
    if (mode == BundledHelperReplacementMode.GUARDED_RETIRE &&
        (incumbentIdentity == null || incumbentIdentity.buildId != incumbentBuildId)
    ) return BundledHelperInstaller.Result.REPROVISION_REQUIRED
    if (priorRecord != null && bundledLegacyPriorRecordDisposition(
            stagedBuildId, priorRecord, incumbentIdentity,
        ) == BundledLegacyPriorRecordDisposition.REPROVISION
    ) return BundledHelperInstaller.Result.REPROVISION_REQUIRED
    if (!stage()) return BundledHelperInstaller.Result.FAILED
    val settlement = when (mode) {
        BundledHelperReplacementMode.GUARDED_RETIRE -> guarded()
        BundledHelperReplacementMode.RELEASED_LEGACY_TAKEOVER -> if (legacy()) {
            BundledHelperReplacementSettlement.INSTALLED
        } else BundledHelperReplacementSettlement.HOLD
        BundledHelperReplacementMode.FRESH_INSTALL -> if (fresh()) {
            BundledHelperReplacementSettlement.INSTALLED
        } else BundledHelperReplacementSettlement.HOLD
        BundledHelperReplacementMode.SYSTEM_CUSTODY_MIGRATION -> if (migrate()) {
            BundledHelperReplacementSettlement.INSTALLED
        } else BundledHelperReplacementSettlement.HOLD
    }
    return when (settlement) {
        BundledHelperReplacementSettlement.INSTALLED ->
            if (mode != BundledHelperReplacementMode.GUARDED_RETIRE || cleanup()) {
                BundledHelperInstaller.Result.INSTALLED
            } else BundledHelperInstaller.Result.FAILED
        BundledHelperReplacementSettlement.BLOCKED_ACTIVE -> BundledHelperInstaller.Result.BLOCKED_ACTIVE
        BundledHelperReplacementSettlement.OLD_SAFE,
        BundledHelperReplacementSettlement.NOT_SUBMITTED,
        BundledHelperReplacementSettlement.HOLD -> BundledHelperInstaller.Result.FAILED
    }
}

internal fun bundledLegacyPriorRecordDisposition(
    currentBuildId: String,
    record: BundledLegacyTakeoverRecord,
    incumbentIdentity: GuardDbMaintenanceProtocol.SelfIdentity? = null,
): BundledLegacyPriorRecordDisposition = if (record.stagedBuildId == currentBuildId ||
    incumbentIdentity == GuardDbMaintenanceProtocol.SelfIdentity(
        bytes = record.candidateBytes,
        sha256 = record.candidateSha256,
        buildId = record.stagedBuildId,
    )
) {
    BundledLegacyPriorRecordDisposition.KEEP
} else {
    BundledLegacyPriorRecordDisposition.REPROVISION
}

/**
 * A bare ERR is the released pre-Guard helper's exact response to an unknown verb. It cannot create
 * the Guard replacement journal, so asking it to retire and then waiting for Guard capabilities can
 * only hold forever. A direct-su app instead lets the staged, authenticated candidate prove the Guard
 * namespace empty and perform one exact in-place takeover with an authenticated incumbent rollback.
 * Any ambiguous reply remains fail-closed.
 *
 * No socket at all is a candidate fresh install, not maintenance: a helper that owns recovery answers
 * GUARDSTATUS. The fresh-install shell proves no helper binary, boot registration or recovery journal
 * exists before it starts anything, so an installed helper that is merely not running is refused there.
 */
internal fun bundledHelperReplacementMode(
    status: GuardDbMaintenanceClient.StatusProbe,
): BundledHelperReplacementMode? = when (status) {
    GuardDbMaintenanceClient.StatusProbe.Unsupported ->
        BundledHelperReplacementMode.RELEASED_LEGACY_TAKEOVER
    is GuardDbMaintenanceClient.StatusProbe.Valid -> if (!status.status.ownsMaintenance) {
        BundledHelperReplacementMode.GUARDED_RETIRE
    } else {
        null
    }
    GuardDbMaintenanceClient.StatusProbe.Unreachable -> BundledHelperReplacementMode.FRESH_INSTALL
    GuardDbMaintenanceClient.StatusProbe.Malformed -> null
}

// Foreign recovery authorities fence takeover, even after their writer exits. Staging and a fresh
// install retire a stale deleted-installer journal instead ([installerJournalsRetiredShell]).
// App-owned takeover records and previous-byte custody have separate admission/resume rules.
private val BUNDLED_HELPER_FOREIGN_JOURNALS = (GUARD_REPLACEMENT_JOURNALS + DELETED_INSTALLER_JOURNALS).underRoot()

/**
 * Root stages one fixed candidate; replacement happens only through the selected exact authority.
 * [appInstalledAtEpochSeconds] is when this APK was installed; the default 0 retires no journal.
 */
internal fun bundledHelperStageCommand(
    expectedSha256: String,
    filesystemRoot: String = "",
    appInstalledAtEpochSeconds: Long = 0,
    retainedRecord: BundledLegacyTakeoverRecord? = null,
): String {
    require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
    require(filesystemRoot.isEmpty() ||
        (filesystemRoot.startsWith("/") && !filesystemRoot.endsWith("/") &&
            SAFE_ABSOLUTE_PATH.matches(filesystemRoot) &&
            filesystemRoot.split('/').none { it == ".." }))
    val dollar = '$'
    val installerJournalsRetired = installerJournalsRetiredShell(appInstalledAtEpochSeconds)
    val guardJournals = GUARD_REPLACEMENT_JOURNALS.underRoot()
    val recordAdmission = retainedRecord?.let { record ->
        require(GuardDbMaintenanceProtocol.validSha256(record.recordSha256))
        require(record.recordBytes in 1..1024)
        """
          [ -f "${dollar}record" ] && [ ! -L "${dollar}record" ] || return 1
          record_meta=${dollar}(stat -c '%u:%g:%a:%h:%s' "${dollar}record" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${dollar}record" 2>/dev/null) || return 1
          [ "${dollar}record_meta" = "0:0:600:1:${record.recordBytes}" ] || return 1
          record_hash=${dollar}(sha256sum "${dollar}record" 2>/dev/null || toybox sha256sum "${dollar}record" 2>/dev/null) || return 1
          [ "${dollar}{record_hash%% *}" = '${record.recordSha256}' ] || return 1
        """.trimIndent()
    } ?: "[ ! -e \"${dollar}record\" ] && [ ! -L \"${dollar}record\" ] || return 1"
    return """
        root=$filesystemRoot
        data_local=${dollar}root/data/local
        lock=${dollar}root/dev/.hapaneld-helper-transaction.lock
        stage=${dollar}data_local/.hapaneld-helper.new
        record=${dollar}data_local/.hapaneld-helper.legacy-takeover
        upload=${dollar}data_local/.hapaneld-helper.app-stage-$expectedSha256
        if ! mkdir "${dollar}lock" 2>/dev/null; then
          holder=${dollar}(cat "${dollar}lock/pid" 2>/dev/null || true)
          case "${dollar}holder" in ''|*[!0-9]*) exit 75 ;; *) [ ! -d "/proc/${dollar}holder" ] || exit 75 ;; esac
          rm -rf "${dollar}lock" 2>/dev/null || exit 75
          mkdir "${dollar}lock" 2>/dev/null || exit 75
        fi
        echo ${dollar}${dollar} > "${dollar}lock/pid" || { rm -rf "${dollar}lock"; exit 75; }
        cleanup_stage_lock() { rm -f "${dollar}upload"; rm -rf "${dollar}lock"; }
        trap cleanup_stage_lock 0
        trap 'cleanup_stage_lock; trap - 0; exit 74' 1 2 3 15
        [ -d "${dollar}data_local" ] && [ ! -L "${dollar}data_local" ] || exit 1
        $installerJournalsRetired
        other_authority_absent() {
          for authority in \
            $guardJournals \
            "${dollar}root/data/local/.hapaneld-helper.previous" \
            "${dollar}root/data/local/.hapaneld-helper.previous.tmp" \
            "${dollar}root/data/local/.hapaneld-helper.custody-migration" \
            "${dollar}root/data/local/.hapaneld-helper.custody-migration.tmp"; do
            [ ! -e "${dollar}authority" ] && [ ! -L "${dollar}authority" ] || return 1
          done
          $recordAdmission
          installer_journals_retired
        }
        record_tmp=${dollar}data_local/.hapaneld-helper.legacy-takeover.tmp
        other_authority_absent || exit 75
        if [ -e "${dollar}record_tmp" ] || [ -L "${dollar}record_tmp" ]; then
          [ -f "${dollar}record_tmp" ] && [ ! -L "${dollar}record_tmp" ] || exit 75
          record_tmp_meta=${dollar}(stat -c '%u:%g:%a:%h:%s' "${dollar}record_tmp" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${dollar}record_tmp" 2>/dev/null) || exit 75
          case "${dollar}record_tmp_meta" in 0:0:600:1:*) ;; *) exit 75 ;; esac
          record_tmp_bytes=${dollar}{record_tmp_meta##*:}
          [ "${dollar}record_tmp_bytes" -le 1024 ] || exit 75
          rm -f "${dollar}record_tmp" || exit 75
          sync || exit 75
        fi
        authority_absent() { other_authority_absent && [ ! -e "${dollar}record_tmp" ] && [ ! -L "${dollar}record_tmp" ]; }
        authority_absent || exit 75
        if [ -e "${dollar}upload" ] || [ -L "${dollar}upload" ]; then
          [ -f "${dollar}upload" ] && [ ! -L "${dollar}upload" ] || exit 1
          upload_meta=${dollar}(stat -c '%u:%g:%h:%s' "${dollar}upload" 2>/dev/null || toybox stat -c '%u:%g:%h:%s' "${dollar}upload" 2>/dev/null) || exit 1
          case "${dollar}upload_meta" in 0:0:1:*) ;; *) exit 1 ;; esac
          rm -f "${dollar}upload" || exit 1
        fi
        umask 077
        cat > "${dollar}upload" || exit 1
        [ -f "${dollar}upload" ] && [ ! -L "${dollar}upload" ] || exit 1
        actual=${dollar}(sha256sum "${dollar}upload" 2>/dev/null || toybox sha256sum "${dollar}upload" 2>/dev/null) || exit 1
        [ "${dollar}{actual%% *}" = "$expectedSha256" ] || exit 1
        chown 0:0 "${dollar}upload" && chmod 700 "${dollar}upload" || exit 1
        meta=${dollar}(stat -c '%u:%g:%a:%h:%s' "${dollar}upload" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${dollar}upload" 2>/dev/null) || exit 1
        case "${dollar}meta" in 0:0:700:1:*) ;; *) exit 1 ;; esac
        bytes=${dollar}{meta##*:}
        [ "${dollar}bytes" -ge 1 ] && [ "${dollar}bytes" -le $MAX_STAGED_HELPER_BYTES ] || exit 1
        # Rebind the authority namespace at the last cut before replacing an unowned fixed-stage
        # orphan. Every supported writer shares this lock, and the rebind also fails closed on
        # unsupported or manually published authority.
        authority_absent || exit 75
        if [ -e "${dollar}stage" ] || [ -L "${dollar}stage" ]; then
          [ -f "${dollar}stage" ] && [ ! -L "${dollar}stage" ] || exit 1
        fi
        mv -f "${dollar}upload" "${dollar}stage" || exit 1
        sync || exit 1
        meta=${dollar}(stat -c '%u:%g:%a:%h:%s' "${dollar}stage" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${dollar}stage" 2>/dev/null) || exit 1
        [ "${dollar}meta" = "0:0:700:1:${dollar}bytes" ] || exit 1
        actual=${dollar}(sha256sum "${dollar}stage" 2>/dev/null || toybox sha256sum "${dollar}stage" 2>/dev/null) || exit 1
        [ "${dollar}{actual%% *}" = "$expectedSha256" ] || exit 1
        echo STAGED_OK
    """.trimIndent()
}

/**
 * Released helpers predate GUARDRETIRE, so a direct-su app may perform one same-session takeover.
 * The released binary and its exact boot registration remain byte-for-byte untouched: provisioning is
 * still the durable installer and a reboot therefore restores the released helper. Under the shared
 * helper lock, the candidate is published only at /data/local, supervises its own worker, and retires
 * only a different executable inode. Failure or interruption terminates that exact candidate lineage,
 * returns its authenticated bytes to staging, and relaunches/verifies the untouched incumbent. A
 * bounded root-owned authority record remains for the lifetime of this ephemeral takeover so a later
 * app process can recover a dead supervisor. A different bundled build ignores the record; durable
 * provisioning supersedes its recorded topology and leaves the now-inert record unable to authorize a
 * mutation because the shell rebinds every recorded byte/hash/path before publication.
 */
internal fun bundledLegacyHelperTakeoverCommand(
    expectedSha256: String,
    stagedBuildId: String,
    incumbentBuildId: String,
    filesystemRoot: String = "",
    polls: Int = LEGACY_TAKEOVER_POLLS,
    retainedResumeOnly: Boolean = false,
    systemCustodyMigration: Boolean = false,
): String {
    require(GuardDbMaintenanceProtocol.validSha256(expectedSha256))
    require(GuardDbMaintenanceProtocol.validSha256(stagedBuildId))
    require(GuardDbMaintenanceProtocol.validSha256(incumbentBuildId))
    require(stagedBuildId != incumbentBuildId)
    require(filesystemRoot.isEmpty() ||
        (filesystemRoot.startsWith("/") && !filesystemRoot.endsWith("/") &&
            SAFE_ABSOLUTE_PATH.matches(filesystemRoot) &&
            filesystemRoot.split('/').none { it == ".." }))
    require(polls in 1..LEGACY_TAKEOVER_POLLS)
    require(!(systemCustodyMigration && retainedResumeOnly))
    val dollar = '$'
    val expectedCapabilities =
        "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE"
    val expectedStatus = "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0"
    val migration = systemCustodyMigration
    val resumeAdmission = if (retainedResumeOnly) "exit 1" else ""
    val initialReplacementProof = if (retainedResumeOnly) {
        "exit 1"
    } else {
        """
          exact_file "${dollar}stage" "$expectedSha256" 700 "${dollar}candidate_bytes" &&
            [ "${dollar}("${dollar}stage" --replacement-safe 2>/dev/null)" = REPLACE_SAFE ] &&
            [ "${dollar}("${dollar}old_bin" --request BUILDID 2>/dev/null)" = "BUILDID $incumbentBuildId" ] || exit 1
        """.trimIndent()
    }
    val acceptedStatus = if (migration) {
        // The incumbent shared the Guard DB, so its counters may differ; only an EMPTY phase is accepted.
        "custody_status_empty \"${dollar}status\""
    } else if (retainedResumeOnly) {
        "[ -n \"${dollar}status\" ]"
    } else {
        "[ \"${dollar}status\" = \"$expectedStatus\" ]"
    }
    val legacyAdmission = """
        system_match=0; systemless_match=0; hybrid_match=0
        if [ -f "${dollar}system_bin" ] && [ ! -L "${dollar}system_bin" ] &&
           [ -f "${dollar}system_registration" ] && [ ! -L "${dollar}system_registration" ] &&
           absent "${dollar}data_bin" && absent "${dollar}vendor_registration" &&
           absent "${dollar}service_registration"; then system_match=1; fi
        if [ -f "${dollar}data_bin" ] && [ ! -L "${dollar}data_bin" ] &&
           [ -f "${dollar}service_registration" ] && [ ! -L "${dollar}service_registration" ] &&
           absent "${dollar}system_bin" && absent "${dollar}system_registration" &&
           absent "${dollar}vendor_registration"; then systemless_match=1; fi
        if [ -f "${dollar}data_bin" ] && [ ! -L "${dollar}data_bin" ] &&
           [ -f "${dollar}vendor_registration" ] && [ ! -L "${dollar}vendor_registration" ] &&
           absent "${dollar}system_bin" && absent "${dollar}system_registration" &&
           absent "${dollar}service_registration"; then hybrid_match=1; fi
        [ ${dollar}((system_match + systemless_match + hybrid_match)) -eq 1 ] &&
          absent "${dollar}legacy_bin" && absent "${dollar}legacy_registration" || exit 1
        if [ "${dollar}system_match" = 1 ]; then topology=system; old_bin=${dollar}system_bin; registration=${dollar}system_registration; registration_mode=644
        elif [ "${dollar}systemless_match" = 1 ]; then topology=systemless; old_bin=${dollar}data_bin; registration=${dollar}service_registration; registration_mode=755
        else topology=hybrid; old_bin=${dollar}data_bin; registration=${dollar}vendor_registration; registration_mode=644; fi

        old_meta=${dollar}(file_meta "${dollar}old_bin") || exit 1
        case "${dollar}old_meta" in 0:0:755:1:*) ;; *) exit 1 ;; esac
        old_bytes=${dollar}{old_meta##*:}
        [ "${dollar}old_bytes" -ge 1 ] && [ "${dollar}old_bytes" -le $MAX_STAGED_HELPER_BYTES ] || exit 1
        old_sha=${dollar}(file_hash "${dollar}old_bin") || exit 1
        [ "${dollar}old_sha" != "$expectedSha256" ] || exit 1
        registration_meta=${dollar}(file_meta "${dollar}registration") || exit 1
        case "${dollar}registration_meta" in 0:0:${dollar}registration_mode:1:*) ;; *) exit 1 ;; esac
        registration_bytes=${dollar}{registration_meta##*:}
        registration_sha=${dollar}(file_hash "${dollar}registration") || exit 1
        case "${dollar}topology:${dollar}registration_sha" in
          system:9b430712c493df177a19e5e893df445f6c2e951fc30ea140dcdbcdb7987de659|system:1ec2c7baef1b3961f3d8a4c20222fe63c358896238022f4d87bbb5b8b51bdf8e|system:b42a66ff435a830390c7f04e66ffa252e3bf4027e68c72a29002df4886f8d4f4) ;;
          systemless:60ff22aa9b38483cbffd95a653d804d0d9abf682e1b952e8b4519d5c0f3f9493|systemless:cc3eb30416693865345eb241493efaf846c803b9c7370883d0e7eed8101d1411) ;;
          hybrid:cf146dd5320fcb017514def6295fdb0c473e150a478d5c2219af2e3f03826ed1|hybrid:0bdc270e81edee3af5150dd6fe599cb5f3dd0571a7df5214be13ccbbbca33eba) ;;
          *) exit 1 ;;
        esac

        candidate_source=
        if [ -f "${dollar}live" ] && [ ! -L "${dollar}live" ]; then candidate_source=${dollar}live
        elif ! absent "${dollar}live"; then exit 1
        elif [ -f "${dollar}stage" ] && [ ! -L "${dollar}stage" ]; then candidate_source=${dollar}stage
        else exit 1; fi
        candidate_meta=${dollar}(file_meta "${dollar}candidate_source") || exit 1
        case "${dollar}candidate_meta" in 0:0:700:1:*) ;; *) exit 1 ;; esac
        candidate_bytes=${dollar}{candidate_meta##*:}
        [ "${dollar}candidate_bytes" -ge 1 ] && [ "${dollar}candidate_bytes" -le $MAX_STAGED_HELPER_BYTES ] &&
          [ "${dollar}(file_hash "${dollar}candidate_source")" = "$expectedSha256" ] || exit 1
        if [ -f "${dollar}stage" ] && [ ! -L "${dollar}stage" ]; then
          exact_file "${dollar}stage" "$expectedSha256" 700 "${dollar}candidate_bytes" || exit 1
        elif ! absent "${dollar}stage"; then exit 1; fi

        expected_record="OK LEGACYTAKEOVER 1 ${dollar}topology ${dollar}old_sha ${dollar}old_bytes ${dollar}registration_sha ${dollar}registration_bytes ${dollar}registration_mode $incumbentBuildId $stagedBuildId $expectedSha256 ${dollar}candidate_bytes"
        discard_record_tmp() {
          absent "${dollar}record_tmp" && return 0
          [ -f "${dollar}record_tmp" ] && [ ! -L "${dollar}record_tmp" ] || return 1
          record_tmp_meta=${dollar}(file_meta "${dollar}record_tmp") || return 1
          case "${dollar}record_tmp_meta" in 0:0:600:1:*) ;; *) return 1 ;; esac
          [ "${dollar}{record_tmp_meta##*:}" -le 1024 ] || return 1
          rm -f "${dollar}record_tmp" && sync
        }
        retained_record=0
        if [ -f "${dollar}record" ] && [ ! -L "${dollar}record" ]; then
          record_meta=${dollar}(file_meta "${dollar}record") || exit 1
          case "${dollar}record_meta" in 0:0:600:1:*) ;; *) exit 1 ;; esac
          [ "${dollar}(cat "${dollar}record")" = "${dollar}expected_record" ] || exit 1
          retained_record=1
          discard_record_tmp || exit 1
        else
          absent "${dollar}record" || exit 1
          $resumeAdmission
          if [ -f "${dollar}record_tmp" ] && [ ! -L "${dollar}record_tmp" ]; then
            [ "${dollar}candidate_source" = "${dollar}stage" ] && absent "${dollar}live" || exit 1
            discard_record_tmp || exit 1
          elif ! absent "${dollar}record_tmp"; then exit 1; fi
          $initialReplacementProof
          umask 077
          printf '%s\n' "${dollar}expected_record" > "${dollar}record_tmp" || exit 1
          chown 0:0 "${dollar}record_tmp" && chmod 600 "${dollar}record_tmp" || exit 1
          sync || exit 1
          mv -f "${dollar}record_tmp" "${dollar}record" || exit 1
          sync || exit 1
          retained_record=0
        fi

        authority_written=1
""".trimEnd()
    val legacySwap = """
        # A retained record is durable authority for this exact candidate, not for another
        # replacement. Stop only its inode-bound lineage, then resume that same supervisor directly;
        # Guard state remains in its durable namespace and the released boot files stay untouched.
        if [ "${dollar}retained_record" = 1 ]; then
          if [ "${dollar}candidate_source" = "${dollar}live" ]; then stop_candidate || exit 1; fi
          incumbent_reply=${dollar}("${dollar}old_bin" --request BUILDID 2>/dev/null) || incumbent_reply=
          case "${dollar}incumbent_reply" in ''|"BUILDID $incumbentBuildId") ;; *) exit 1 ;; esac
        fi
        if [ "${dollar}retained_record" != 1 ] && [ "${dollar}candidate_source" = "${dollar}live" ]; then
          stop_candidate || exit 1
        fi
        if [ "${dollar}topology" = system ] || [ "${dollar}topology" = hybrid ]; then
          "${dollar}root/system/bin/stop" hapaneld_helper >/dev/null 2>&1 || exit 1
          boot_service_stopped=1
        fi
        stop_path_processes "${dollar}old_bin" || exit 1
        if [ "${dollar}candidate_source" = "${dollar}stage" ]; then
          mv -f "${dollar}stage" "${dollar}live" || exit 1
          candidate_source=${dollar}live
        fi
        sync || exit 1
""".trimEnd()
    val legacyAcceptance = """
            exact_file "${dollar}live" "$expectedSha256" 700 "${dollar}candidate_bytes" || { rollback_candidate; exit 1; }
            exact_file "${dollar}old_bin" "${dollar}old_sha" 755 "${dollar}old_bytes" || { rollback_candidate; exit 1; }
            exact_file "${dollar}registration" "${dollar}registration_sha" "${dollar}registration_mode" "${dollar}registration_bytes" || { rollback_candidate; exit 1; }
            if [ -f "${dollar}stage" ]; then exact_file "${dollar}stage" "$expectedSha256" 700 "${dollar}candidate_bytes" || exit 1; rm -f "${dollar}stage" || exit 1; fi
            sync || exit 1
            exit 0
""".trimEnd()
    // Layout F custody migration: archive the /system helper and its rc, swap the bundled build in, then
    // remove both from /system under one record. See BundledHelperSystemCustody.kt for the record.
    val migrationAdmission = """
        custody_record=${dollar}data_local/.hapaneld-helper.custody-migration
        custody_tmp=${dollar}custody_record.tmp
        archive=${dollar}data_local/.hapaneld-custody-archive
        init_root=${dollar}root/proc/1/root
        system_bin_tmp=${dollar}root/system/bin/.hapaneld-helper.restore
        registration_tmp=${dollar}root/system/etc/.hapaneld-helper.rc.restore
        topology=system; old_bin=${dollar}system_bin; registration=${dollar}system_registration; registration_mode=644
        retained_record=0
        removed_now=0
        system_mount=
        custody_reason() {
          printf 'CUSTODY_REASON %s\n' "${dollar}1"
          log -t ha-paneld/helper-migrate "system custody migration: ${dollar}1" >/dev/null 2>&1 || true
        }
        custody_status_empty() {
          set -- ${dollar}1
          [ ${dollar}# -eq 15 ] && [ "${dollar}1" = OK ] && [ "${dollar}2" = GUARDSTATUS ] && [ "${dollar}4" = EMPTY ] || return 1
          case "${dollar}3" in ''|*[!0-9]*) return 1 ;; esac
        }
        absent "${dollar}data_bin" && absent "${dollar}vendor_registration" && absent "${dollar}service_registration" &&
          absent "${dollar}legacy_bin" && absent "${dollar}legacy_registration" &&
          absent "${dollar}record" && absent "${dollar}record_tmp" || exit 1
        for sibling in "${dollar}system_registration"?* "${dollar}vendor_registration"?*; do absent "${dollar}sibling" || exit 1; done
        [ ! -L "${dollar}old_bin" ] && [ ! -L "${dollar}registration" ] && [ ! -L "${dollar}archive" ] || exit 1
        discard_custody_tmp() {
          absent "${dollar}custody_tmp" && return 0
          [ -f "${dollar}custody_tmp" ] && [ ! -L "${dollar}custody_tmp" ] || return 1
          case "${dollar}(file_meta "${dollar}custody_tmp")" in 0:0:600:1:*) ;; *) return 1 ;; esac
          rm -f "${dollar}custody_tmp" && sync
        }
        write_custody() {
          custody_line="OK CUSTODYMIGRATION 1 ${dollar}1 ${dollar}old_sha ${dollar}old_bytes ${dollar}registration_sha ${dollar}registration_bytes ${dollar}stale_sha ${dollar}stale_bytes $incumbentBuildId $stagedBuildId $expectedSha256 ${dollar}candidate_bytes ${dollar}2"
          umask 077
          printf '%s\n' "${dollar}custody_line" > "${dollar}custody_tmp" || return 1
          chown 0:0 "${dollar}custody_tmp" && chmod 600 "${dollar}custody_tmp" && sync &&
            mv -f "${dollar}custody_tmp" "${dollar}custody_record" && sync || return 1
          phase=${dollar}1
        }

        phase=NONE
        if ! absent "${dollar}custody_record"; then
          [ -f "${dollar}custody_record" ] && [ ! -L "${dollar}custody_record" ] || exit 1
          custody_meta=${dollar}(file_meta "${dollar}custody_record") || exit 1
          case "${dollar}custody_meta" in 0:0:600:1:*) ;; *) exit 1 ;; esac
          [ "${dollar}{custody_meta##*:}" -le 1024 ] || exit 1
          custody_line=${dollar}(cat "${dollar}custody_record") || exit 1
          set -- ${dollar}custody_line
          [ ${dollar}# -eq 15 ] && [ "${dollar}1 ${dollar}2 ${dollar}3" = "OK CUSTODYMIGRATION 1" ] || exit 1
          phase=${dollar}4; old_sha=${dollar}5; old_bytes=${dollar}6; registration_sha=${dollar}7; registration_bytes=${dollar}8
          stale_sha=${dollar}9; stale_bytes=${dollar}{10}; candidate_bytes=${dollar}{14}
          [ "${dollar}{11} ${dollar}{12} ${dollar}{13}" = "$incumbentBuildId $stagedBuildId $expectedSha256" ] || exit 1
          case "${dollar}phase" in $SYSTEM_CUSTODY_PHASE_ARCHIVED|$SYSTEM_CUSTODY_PHASE_SWAPPED|$SYSTEM_CUSTODY_PHASE_REMOVED) ;; *) exit 1 ;; esac
          [ "${dollar}registration_sha" = "$SYSTEM_CUSTODY_REGISTRATION_SHA256" ] || exit 1
          for number in "${dollar}old_bytes" "${dollar}registration_bytes" "${dollar}stale_bytes" "${dollar}candidate_bytes"; do
            case "${dollar}number" in ''|*[!0-9]*) exit 1 ;; esac
          done
          discard_custody_tmp || exit 1
        else
          discard_custody_tmp || exit 1
          [ -f "${dollar}old_bin" ] && [ -f "${dollar}registration" ] || exit 1
          old_meta=${dollar}(file_meta "${dollar}old_bin") || exit 1
          case "${dollar}old_meta" in 0:0:755:1:*) ;; *) exit 1 ;; esac
          old_bytes=${dollar}{old_meta##*:}
          [ "${dollar}old_bytes" -ge 1 ] && [ "${dollar}old_bytes" -le $MAX_STAGED_HELPER_BYTES ] || exit 1
          old_sha=${dollar}(file_hash "${dollar}old_bin") || exit 1
          [ "${dollar}old_sha" != "$expectedSha256" ] || exit 1
          registration_meta=${dollar}(file_meta "${dollar}registration") || exit 1
          case "${dollar}registration_meta" in 0:0:644:1:*) ;; *) exit 1 ;; esac
          registration_bytes=${dollar}{registration_meta##*:}
          registration_sha=${dollar}(file_hash "${dollar}registration") || exit 1
          # Only the pre-canonical rc this layout was found with; the takeover's other rc's stay there.
          [ "${dollar}registration_sha" = "$SYSTEM_CUSTODY_REGISTRATION_SHA256" ] || exit 1
          guard=${dollar}data_local/.hapaneld-guard-db
          if ! absent "${dollar}guard"; then
            [ -d "${dollar}guard" ] && [ ! -L "${dollar}guard" ] || exit 1
            for entry in ${dollar}(ls -A "${dollar}guard"); do [ "${dollar}entry" = .owner.lock ] || exit 1; done
          fi
          stale_sha=NONE; stale_bytes=0; candidate_bytes=
        fi

        # Removal deletes the rc first, so any mix of present and absent files is a SWAPPED cut.
        bin_present=0; registration_present=0
        if ! absent "${dollar}old_bin"; then exact_file "${dollar}old_bin" "${dollar}old_sha" 755 "${dollar}old_bytes" || exit 1; bin_present=1; fi
        if ! absent "${dollar}registration"; then
          exact_file "${dollar}registration" "${dollar}registration_sha" 644 "${dollar}registration_bytes" || exit 1; registration_present=1
        fi
        case "${dollar}phase:${dollar}bin_present${dollar}registration_present" in
          NONE:11|$SYSTEM_CUSTODY_PHASE_ARCHIVED:11|$SYSTEM_CUSTODY_PHASE_SWAPPED:*|$SYSTEM_CUSTODY_PHASE_REMOVED:00) ;;
          *) exit 1 ;;
        esac
        archived_bin=${dollar}archive/hapaneld-helper.system.${dollar}old_sha
        archived_registration=${dollar}archive/hapaneld-helper.rc.${dollar}registration_sha
        # A restored file must be exactly what init found: owner, mode and SELinux label are archived beside
        # each copy as `0:0:<mode> <label>`, and a restore that cannot apply all three fails closed.
        file_label() {
          label_line=${dollar}("${dollar}root/system/bin/ls" -Zd "${dollar}1" 2>/dev/null) || return 1
          set -- ${dollar}label_line
          case "${dollar}1" in u:object_r:*:s0) ;; *) return 1 ;; esac
          case "${dollar}1" in *[!a-z0-9_:]*) return 1 ;; esac
          printf '%s\n' "${dollar}1"
        }
        attr_read() {
          [ -f "${dollar}1" ] && [ ! -L "${dollar}1" ] || return 1
          set -- ${dollar}(cat "${dollar}1")
          [ ${dollar}# -eq 2 ] || return 1
          case "${dollar}1" in 0:0:644|0:0:755) ;; *) return 1 ;; esac
          case "${dollar}2" in u:object_r:*:s0) ;; *) return 1 ;; esac
          case "${dollar}2" in *[!a-z0-9_:]*) return 1 ;; esac
          attr_mode=${dollar}{1##*:}; attr_label=${dollar}2
        }
        attr_capture() {
          captured=${dollar}(file_label "${dollar}1") || return 1
          if ! absent "${dollar}2"; then
            attr_read "${dollar}2" && [ "${dollar}attr_mode" = "${dollar}3" ] && [ "${dollar}attr_label" = "${dollar}captured" ]
            return
          fi
          umask 077
          printf '0:0:%s %s\n' "${dollar}3" "${dollar}captured" > "${dollar}2.tmp" && chown 0:0 "${dollar}2.tmp" && chmod 600 "${dollar}2.tmp" &&
            sync && mv -f "${dollar}2.tmp" "${dollar}2" && sync
        }
        archive_exact() {
          exact_file "${dollar}archived_bin" "${dollar}old_sha" 600 "${dollar}old_bytes" &&
            exact_file "${dollar}archived_registration" "${dollar}registration_sha" 600 "${dollar}registration_bytes" &&
            attr_read "${dollar}archived_bin.attr" && [ "${dollar}attr_mode" = 755 ] &&
            attr_read "${dollar}archived_registration.attr" && [ "${dollar}attr_mode" = 644 ]
        }
        archive_copy() {
          exact_file "${dollar}2" "${dollar}3" 600 "${dollar}4" && return 0
          if ! absent "${dollar}2"; then [ -f "${dollar}2" ] && [ ! -L "${dollar}2" ] && rm -f "${dollar}2" || return 1; fi
          umask 077
          cat "${dollar}1" > "${dollar}2.tmp" && chown 0:0 "${dollar}2.tmp" && chmod 600 "${dollar}2.tmp" && sync &&
            mv -f "${dollar}2.tmp" "${dollar}2" && sync && exact_file "${dollar}2" "${dollar}3" 600 "${dollar}4"
        }
        restore_file() {
          attr_read "${dollar}1.attr" && [ "${dollar}attr_mode" = "${dollar}5" ] || return 1
          if absent "${dollar}2"; then
            umask 022
            cat "${dollar}1" > "${dollar}6" && chown 0:0 "${dollar}6" && chmod "${dollar}attr_mode" "${dollar}6" && sync &&
              mv -f "${dollar}6" "${dollar}2" && sync || return 1
          fi
          exact_file "${dollar}2" "${dollar}3" "${dollar}5" "${dollar}4" || return 1
          if [ "${dollar}(file_label "${dollar}2")" != "${dollar}attr_label" ]; then
            "${dollar}root/system/bin/chcon" "${dollar}attr_label" "${dollar}2" || return 1
          fi
          [ "${dollar}(file_label "${dollar}2")" = "${dollar}attr_label" ] && exact_file "${dollar}2" "${dollar}3" "${dollar}5" "${dollar}4" && sync
        }
        # Only a removal this run made is undone: init still holds the old service until the next reboot.
        restore_removed_system() {
          [ "${dollar}removed_now" = 1 ] || return 0
          archive_exact || return 1
          "${dollar}root/system/bin/mount" -o rw,remount "${dollar}system_mount" >/dev/null 2>&1 || true
          restore_file "${dollar}archived_bin" "${dollar}old_bin" "${dollar}old_sha" "${dollar}old_bytes" 755 "${dollar}system_bin_tmp" &&
            restore_file "${dollar}archived_registration" "${dollar}registration" "${dollar}registration_sha" "${dollar}registration_bytes" 644 "${dollar}registration_tmp" || return 1
          "${dollar}root/system/bin/mount" -o ro,remount "${dollar}system_mount" >/dev/null 2>&1 || true
          removed_now=0
        }

        live_is_candidate=0
        if ! absent "${dollar}live"; then
          [ -f "${dollar}live" ] && [ ! -L "${dollar}live" ] || exit 1
          live_meta=${dollar}(file_meta "${dollar}live") || exit 1
          live_sha=${dollar}(file_hash "${dollar}live") || exit 1
          if [ "${dollar}live_sha" = "$expectedSha256" ]; then
            case "${dollar}live_meta" in 0:0:700:1:*) ;; *) exit 1 ;; esac
            live_is_candidate=1
          else
            # An unrelated /data/local copy is archived, never run, and never replaced while it runs.
            case "${dollar}live_meta" in 0:0:*:1:*) ;; *) exit 1 ;; esac
            live_runners=${dollar}(path_processes "${dollar}live") || exit 1
            [ -z "${dollar}live_runners" ] || exit 1
            if [ "${dollar}phase" = NONE ]; then stale_sha=${dollar}live_sha; stale_bytes=${dollar}{live_meta##*:}
            else [ "${dollar}live_sha" = "${dollar}stale_sha" ] && [ "${dollar}{live_meta##*:}" = "${dollar}stale_bytes" ] || exit 1; fi
          fi
        fi
        if [ "${dollar}live_is_candidate" = 1 ]; then candidate_source=${dollar}live
        elif [ -f "${dollar}stage" ] && [ ! -L "${dollar}stage" ]; then candidate_source=${dollar}stage
        else exit 1; fi
        candidate_meta=${dollar}(file_meta "${dollar}candidate_source") || exit 1
        case "${dollar}candidate_meta" in 0:0:700:1:*) ;; *) exit 1 ;; esac
        [ -z "${dollar}candidate_bytes" ] || [ "${dollar}candidate_bytes" = "${dollar}{candidate_meta##*:}" ] || exit 1
        candidate_bytes=${dollar}{candidate_meta##*:}
        [ "${dollar}candidate_bytes" -ge 1 ] && [ "${dollar}candidate_bytes" -le $MAX_STAGED_HELPER_BYTES ] &&
          [ "${dollar}(file_hash "${dollar}candidate_source")" = "$expectedSha256" ] || exit 1
        if [ -f "${dollar}stage" ] && [ ! -L "${dollar}stage" ]; then
          exact_file "${dollar}stage" "$expectedSha256" 700 "${dollar}candidate_bytes" || exit 1
        elif ! absent "${dollar}stage"; then exit 1; fi
        stale_archive=${dollar}archive/hapaneld-helper.stale.${dollar}stale_sha

        if [ "${dollar}phase" = NONE ]; then
          $initialReplacementProof
          write_custody $SYSTEM_CUSTODY_PHASE_ARCHIVED NONE || exit 1
        fi
        authority_written=1
        if [ "${dollar}phase" != $SYSTEM_CUSTODY_PHASE_REMOVED ]; then
          if absent "${dollar}archive"; then mkdir "${dollar}archive" || { rollback_candidate; exit 1; }; fi
          [ -d "${dollar}archive" ] && [ ! -L "${dollar}archive" ] && chown 0:0 "${dollar}archive" && chmod 700 "${dollar}archive" ||
            { rollback_candidate; exit 1; }
          # The unrelated copy leaves first, so every later rollback finds /data/local as it restores it.
          if [ "${dollar}stale_sha" != NONE ] && [ "${dollar}live_is_candidate" = 0 ] && [ -f "${dollar}live" ]; then
            mv -f "${dollar}live" "${dollar}stale_archive" && sync || { rollback_candidate; exit 1; }
          fi
          if [ "${dollar}bin_present" = 1 ]; then
            archive_copy "${dollar}old_bin" "${dollar}archived_bin" "${dollar}old_sha" "${dollar}old_bytes" &&
              attr_capture "${dollar}old_bin" "${dollar}archived_bin.attr" 755 || { rollback_candidate; exit 1; }
          fi
          if [ "${dollar}registration_present" = 1 ]; then
            archive_copy "${dollar}registration" "${dollar}archived_registration" "${dollar}registration_sha" "${dollar}registration_bytes" &&
              attr_capture "${dollar}registration" "${dollar}archived_registration.attr" 644 || { rollback_candidate; exit 1; }
          fi
        fi
        archive_exact || { rollback_candidate; exit 1; }
        if [ "${dollar}stale_sha" != NONE ]; then
          [ -f "${dollar}stale_archive" ] && [ ! -L "${dollar}stale_archive" ] &&
            [ "${dollar}(file_hash "${dollar}stale_archive")" = "${dollar}stale_sha" ] &&
            [ "${dollar}(stat -c %s "${dollar}stale_archive" 2>/dev/null || toybox stat -c %s "${dollar}stale_archive")" = "${dollar}stale_bytes" ] ||
            { rollback_candidate; exit 1; }
        fi
""".trimEnd()

    val migrationSwap = """
        if [ "${dollar}candidate_source" = "${dollar}live" ]; then stop_candidate || { rollback_candidate; exit 1; }; fi
        # Once the rc is gone after a reboot init has no such service, so stop may then fail harmlessly.
        if ! "${dollar}root/system/bin/stop" hapaneld_helper >/dev/null 2>&1 && [ "${dollar}registration_present" = 1 ]; then
          rollback_candidate; exit 1
        fi
        boot_service_stopped=1
        if [ "${dollar}bin_present" = 1 ]; then stop_path_processes "${dollar}old_bin" || { rollback_candidate; exit 1; }; fi
        if [ "${dollar}candidate_source" = "${dollar}stage" ]; then
          mv -f "${dollar}stage" "${dollar}live" || { rollback_candidate; exit 1; }
          candidate_source=${dollar}live
        fi
        sync || { rollback_candidate; exit 1; }
""".trimEnd()

    val migrationAcceptance = """
            exact_file "${dollar}live" "$expectedSha256" 700 "${dollar}candidate_bytes" || { rollback_candidate; exit 1; }
            [ "${dollar}bin_present" = 0 ] || exact_file "${dollar}old_bin" "${dollar}old_sha" 755 "${dollar}old_bytes" || { rollback_candidate; exit 1; }
            [ "${dollar}registration_present" = 0 ] ||
              exact_file "${dollar}registration" "${dollar}registration_sha" 644 "${dollar}registration_bytes" || { rollback_candidate; exit 1; }
            if [ -f "${dollar}stage" ]; then exact_file "${dollar}stage" "$expectedSha256" 700 "${dollar}candidate_bytes" || exit 1; rm -f "${dollar}stage" || exit 1; fi
            sync || exit 1
            if [ "${dollar}phase" = $SYSTEM_CUSTODY_PHASE_ARCHIVED ]; then
              write_custody $SYSTEM_CUSTODY_PHASE_SWAPPED NONE || { rollback_candidate; exit 1; }
            fi
            if [ "${dollar}phase" = $SYSTEM_CUSTODY_PHASE_SWAPPED ]; then
              if [ "${dollar}bin_present${dollar}registration_present" != 00 ]; then
                # The archive is proved again at the last cut before anything leaves /system.
                archive_exact || { rollback_candidate; exit 1; }
                system_type=
                while IFS= read -r mount_line; do
                  set -- ${dollar}mount_line
                  case "${dollar}5" in
                    /system) system_mount=/system; system_type=${dollar}{mount_line#* - }; system_type=${dollar}{system_type%% *} ;;
                    /) if [ "${dollar}system_mount" != /system ]; then
                         system_mount=/; system_type=${dollar}{mount_line#* - }; system_type=${dollar}{system_type%% *}
                       fi ;;
                  esac
                done < "${dollar}root/proc/1/mountinfo"
                if [ -z "${dollar}system_mount" ]; then custody_reason system_mount_unknown; rollback_candidate; exit 1; fi
                if [ "${dollar}system_type" != overlay ] && [ "${dollar}(getprop ro.boot.veritymode 2>/dev/null)" = enforcing ]; then
                  custody_reason system_verity_enforcing; rollback_candidate; exit 1
                fi
                if ! "${dollar}root/system/bin/mount" -o rw,remount "${dollar}system_mount"; then
                  custody_reason system_removal_refused; rollback_candidate; exit 1
                fi
                [ "${dollar}bin_present${dollar}registration_present" != 11 ] || removed_now=1
                if ! { rm -f "${dollar}registration" && sync && rm -f "${dollar}old_bin" && sync; }; then
                  custody_reason system_removal_refused; rollback_candidate; exit 1
                fi
                # The su shell may have its own mount namespace: only init's view proves the removal.
                if ! { absent "${dollar}registration" && absent "${dollar}old_bin" &&
                       absent "${dollar}init_root/system/etc/init/hapaneld-helper.rc" &&
                       absent "${dollar}init_root/system/bin/hapaneld-helper"; }; then
                  custody_reason system_removal_refused; rollback_candidate; exit 1
                fi
                "${dollar}root/system/bin/mount" -o ro,remount "${dollar}system_mount" >/dev/null 2>&1 ||
                  custody_reason system_read_only_restore_failed
              fi
              boot_id=${dollar}(cat "${dollar}root/proc/sys/kernel/random/boot_id" 2>/dev/null) || exit 1
              case "${dollar}boot_id" in
                [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]-*-*-*-*) ;;
                *) exit 1 ;;
              esac
              write_custody $SYSTEM_CUSTODY_PHASE_REMOVED "${dollar}boot_id" || exit 1
            fi
            exit 0
""".trimEnd()
    return """
        root=$filesystemRoot
        data_local=${dollar}root/data/local
        lock=${dollar}root/dev/.hapaneld-helper-transaction.lock
        if ! mkdir "${dollar}lock" 2>/dev/null; then
          holder=${dollar}(cat "${dollar}lock/pid" 2>/dev/null || true)
          case "${dollar}holder" in
            ''|*[!0-9]*) exit 75 ;;
            *) [ ! -d "/proc/${dollar}holder" ] || exit 75 ;;
          esac
          rm -rf "${dollar}lock" 2>/dev/null || exit 75
          mkdir "${dollar}lock" 2>/dev/null || exit 75
        fi
        echo ${dollar}${dollar} > "${dollar}lock/pid" || { rm -rf "${dollar}lock"; exit 75; }
        cleanup_helper_lock() { rm -rf "${dollar}lock"; }
        boot_service_stopped=0
        authority_written=0
        on_takeover_signal() {
          trap - 1 2 3 15
          if [ "${dollar}authority_written" = 1 ]; then rollback_candidate >/dev/null 2>&1 || true; fi
          cleanup_helper_lock
          trap - 0
          exit 74
        }
        trap cleanup_helper_lock 0
        trap on_takeover_signal 1 2 3 15

        [ -d "${dollar}data_local" ] && [ ! -L "${dollar}data_local" ] || exit 1
        stage=${dollar}data_local/.hapaneld-helper.new
        live=${dollar}data_local/hapaneld-helper
        record=${dollar}data_local/.hapaneld-helper.legacy-takeover
        record_tmp=${dollar}data_local/.hapaneld-helper.legacy-takeover.tmp
        system_bin=${dollar}root/system/bin/hapaneld-helper
        data_bin=${dollar}root/data/adb/hapaneld/hapaneld-helper
        system_registration=${dollar}root/system/etc/init/hapaneld-helper.rc
        vendor_registration=${dollar}root/vendor/etc/init/hapaneld-helper.rc
        service_registration=${dollar}root/data/adb/service.d/hapaneld-helper.sh
        legacy_bin=${dollar}root/system/bin/hapaneld-ledd
        legacy_registration=${dollar}root/system/etc/init/hapaneld-ledd.rc

        file_hash() {
          hash=${dollar}(sha256sum "${dollar}1" 2>/dev/null || toybox sha256sum "${dollar}1" 2>/dev/null) || return 1
          printf '%s\n' "${dollar}{hash%% *}"
        }
        file_meta() {
          stat -c '%u:%g:%a:%h:%s' "${dollar}1" 2>/dev/null ||
            toybox stat -c '%u:%g:%a:%h:%s' "${dollar}1" 2>/dev/null
        }
        absent() { [ ! -e "${dollar}1" ] && [ ! -L "${dollar}1" ]; }
        exact_file() {
          [ -f "${dollar}1" ] && [ ! -L "${dollar}1" ] &&
            [ "${dollar}(file_hash "${dollar}1")" = "${dollar}2" ] &&
            [ "${dollar}(file_meta "${dollar}1")" = "0:0:${dollar}3:1:${dollar}4" ]
        }

        for foreign_journal in $BUNDLED_HELPER_FOREIGN_JOURNALS; do
          absent "${dollar}foreign_journal" || exit 75
        done
        absent "${dollar}data_local/.hapaneld-helper.previous" &&
          absent "${dollar}data_local/.hapaneld-helper.previous.tmp" || exit 75

        path_processes() {
          target_inode=${dollar}(stat -c '%d:%i' "${dollar}1" 2>/dev/null || toybox stat -c '%d:%i' "${dollar}1" 2>/dev/null) || return 1
          found=
          for executable in /proc/[0-9]*/exe; do
            inode=${dollar}(stat -Lc '%d:%i' "${dollar}executable" 2>/dev/null || toybox stat -L -c '%d:%i' "${dollar}executable" 2>/dev/null) || continue
            [ "${dollar}inode" != "${dollar}target_inode" ] || found="${dollar}found ${dollar}{executable#/proc/}"
          done
          printf '%s\n' "${dollar}found"
        }
        stop_path_processes() {
          candidates=${dollar}(path_processes "${dollar}1") || return 1
          for candidate in ${dollar}candidates; do candidate=${dollar}{candidate%/exe}; kill "${dollar}candidate" 2>/dev/null || true; done
          attempt=0
          while [ "${dollar}attempt" -lt 3 ]; do
            candidates=${dollar}(path_processes "${dollar}1") || return 1
            [ -n "${dollar}candidates" ] || return 0
            sleep 1; attempt=${dollar}((attempt + 1))
          done
          for candidate in ${dollar}candidates; do candidate=${dollar}{candidate%/exe}; kill -9 "${dollar}candidate" 2>/dev/null || true; done
          sleep 1
          [ -z "${dollar}(path_processes "${dollar}1")" ]
        }
        stop_candidate() { stop_path_processes "${dollar}live"; }

        rollback_candidate() {
          ${if (migration) "restore_removed_system || return 1" else ":"}
          exact_file "${dollar}old_bin" "${dollar}old_sha" 755 "${dollar}old_bytes" || return 1
          exact_file "${dollar}registration" "${dollar}registration_sha" "${dollar}registration_mode" "${dollar}registration_bytes" || return 1
          if [ -f "${dollar}live" ] && [ ! -L "${dollar}live" ]; then
            exact_file "${dollar}live" "$expectedSha256" 700 "${dollar}candidate_bytes" || return 1
            stop_candidate || return 1
            if absent "${dollar}stage"; then mv -f "${dollar}live" "${dollar}stage" || return 1
            else exact_file "${dollar}stage" "$expectedSha256" 700 "${dollar}candidate_bytes" && rm -f "${dollar}live" || return 1; fi
          elif ! absent "${dollar}live"; then return 1; fi
          sync || return 1
          exact_file "${dollar}old_bin" "${dollar}old_sha" 755 "${dollar}old_bytes" &&
            exact_file "${dollar}registration" "${dollar}registration_sha" "${dollar}registration_mode" "${dollar}registration_bytes" &&
            exact_file "${dollar}stage" "$expectedSha256" 700 "${dollar}candidate_bytes" && absent "${dollar}live" || return 1
          if [ "${dollar}topology" = system ] || [ "${dollar}topology" = hybrid ]; then
            "${dollar}root/system/bin/start" hapaneld_helper >/dev/null 2>&1 || return 1
            boot_service_stopped=0
          else
            "${dollar}old_bin" >/dev/null 2>&1 &
          fi
          attempt=0
          while [ "${dollar}attempt" -lt $polls ]; do
            [ "${dollar}("${dollar}old_bin" --request BUILDID 2>/dev/null)" != "BUILDID $incumbentBuildId" ] || break
            attempt=${dollar}((attempt + 1)); [ "${dollar}attempt" -ge $polls ] || sleep 1
          done
          [ "${dollar}("${dollar}old_bin" --request BUILDID 2>/dev/null)" = "BUILDID $incumbentBuildId" ] || return 1
          if [ "${dollar}retained_record" != 1 ]; then rm -f "${dollar}record" || return 1; fi
          ${if (migration) "rm -f \"${dollar}custody_record\" \"${dollar}custody_tmp\" || return 1" else ":"}
          sync || return 1
          return 0
        }

${if (migration) migrationAdmission else legacyAdmission}
${if (migration) migrationSwap else legacySwap}

        "${dollar}live" --supervise >/dev/null 2>&1 &
        attempt=0
        candidate_ready=0
        while [ "${dollar}attempt" -lt $polls ]; do
          self=${dollar}("${dollar}live" --request GUARDSELF 2>/dev/null) || self=
          if [ "${dollar}self" = "OK GUARDSELF 1 ${dollar}candidate_bytes $expectedSha256 $stagedBuildId" ]; then
            candidate_ready=1
            break
          fi
          attempt=${dollar}((attempt + 1))
          [ "${dollar}attempt" -ge $polls ] || sleep 1
        done
        if [ "${dollar}candidate_ready" = 1 ]; then
          caps=${dollar}("${dollar}live" --request GUARDCAPS 2>/dev/null) || caps=
          status=${dollar}("${dollar}live" --request GUARDSTATUS 2>/dev/null) || status=
          if [ "${dollar}caps" = "$expectedCapabilities" ] && $acceptedStatus; then
${if (migration) migrationAcceptance else legacyAcceptance}
          fi
        fi
        rollback_candidate || exit 1
        exit 1
    """.trimIndent()
}

internal fun bundledLegacyHelperResumeCommand(
    expectedSha256: String,
    stagedBuildId: String,
    incumbentBuildId: String,
    filesystemRoot: String = "",
    polls: Int = LEGACY_TAKEOVER_POLLS,
): String = bundledLegacyHelperTakeoverCommand(
    expectedSha256,
    stagedBuildId,
    incumbentBuildId,
    filesystemRoot,
    polls,
    retainedResumeOnly = true,
)

private const val MAX_STAGED_HELPER_BYTES = 16 * 1024 * 1024
internal const val LEGACY_TAKEOVER_POLLS = 8
internal const val LEGACY_TAKEOVER_TIMEOUT_MS = 120_000L
private const val NO_RECORD_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
internal val SAFE_ABSOLUTE_PATH = Regex("/[A-Za-z0-9._/-]+")
private val BUNDLED_HELPER_REPLACEMENT_RANDOM = SecureRandom()

internal fun freshBundledHelperReplacementNonce(): String = ByteArray(32)
    .also(BUNDLED_HELPER_REPLACEMENT_RANDOM::nextBytes)
    .joinToString("") { "%02x".format(it) }

internal enum class BundledHelperReplacementSettlement {
    INSTALLED,
    OLD_SAFE,
    BLOCKED_ACTIVE,
    NOT_SUBMITTED,
    HOLD,
}

/** Submit RETIRE exactly once; after possible submission the helper alone owns every filesystem/process mutation. */
internal fun executeBundledHelperReplacement(
    retire: () -> GuardDbMaintenanceProtocol.AppRetireResult,
    probe: () -> HelperReplacementProbe,
    pause: () -> Unit,
    polls: Int,
): BundledHelperReplacementSettlement {
    require(polls > 0)
    when (val result = retire()) {
        GuardDbMaintenanceProtocol.AppRetireResult.Requested,
        GuardDbMaintenanceProtocol.AppRetireResult.Indeterminate -> Unit
        GuardDbMaintenanceProtocol.AppRetireResult.NotSubmitted ->
            return BundledHelperReplacementSettlement.NOT_SUBMITTED
        is GuardDbMaintenanceProtocol.AppRetireResult.Rejected -> return if (
            result.code in setOf("ARMED", "HOLD") && result.token == "replacement"
        ) BundledHelperReplacementSettlement.BLOCKED_ACTIVE else BundledHelperReplacementSettlement.NOT_SUBMITTED
    }
    var consecutiveOld = 0
    repeat(polls) { attempt ->
        when (val observed = probe()) {
            is HelperReplacementProbe.Settled -> when (observed.build) {
                HelperReplacementBuild.NEW -> return BundledHelperReplacementSettlement.INSTALLED
                HelperReplacementBuild.OLD -> {
                    consecutiveOld++
                    if (consecutiveOld == 2) return BundledHelperReplacementSettlement.OLD_SAFE
                }
            }
            HelperReplacementProbe.Hold -> consecutiveOld = 0
        }
        if (attempt + 1 < polls) pause()
    }
    return BundledHelperReplacementSettlement.HOLD
}
