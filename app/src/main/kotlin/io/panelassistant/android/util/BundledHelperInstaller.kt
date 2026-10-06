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
    fun resumeRetainedCurrent(): Boolean = resumeRetainedCurrentOutcome() == true

    @Synchronized
    fun ensureCurrent(context: Context): Result {
        when (resumeRetainedCurrentOutcome()) {
            true -> return Result.INSTALLED
            false -> return Result.FAILED
            null -> Unit
        }
        if (!GuardDbProcessAdmission.ordinaryMutationsAllowed()) return Result.BLOCKED_ACTIVE
        val companionSupported = HelperClient.supportsCompanionData()
        val bundledBuildMatches = HelperClient.matchesBundledHelper()
        val guardSupported = companionSupported && bundledBuildMatches && GuardDbMaintenance.client.supported()
        bundledHelperAdmission(
            bundledBuildMatches = bundledBuildMatches,
            companionSupported = companionSupported,
            guardSupported = guardSupported,
            rootObserved = { Su.available() },
        )?.let {
            if (it == Result.ALREADY_CURRENT && Su.available()) {
                currentBundledLegacyTakeoverRecordResult(BuildConfig.HELPER_BUILD_ID)?.let { result ->
                    Log.w(TAG, "current helper has legacy takeover custody requiring $result")
                    return result
                }
            }
            return it
        }
        val stagedBuildId = BuildConfig.HELPER_BUILD_ID
            .takeIf(GuardDbMaintenanceProtocol::validSha256) ?: return Result.FAILED
        val statusProbe = GuardDbMaintenance.client.statusProbe()
        val replacementMode = bundledHelperReplacementMode(statusProbe) ?: return Result.BLOCKED_ACTIVE
        if (replacementMode != BundledHelperReplacementMode.FRESH_INSTALL) {
            // Replacing a running helper stays off: Guard retire records the /data/local copy as the
            // incumbent even when a boot-registered helper is the one serving, and that helper, restarted
            // by init, then holds on its own journal. Until that is fixed, only a panel with no helper
            // gets the bundled one.
            Log.i(TAG, "leaving the running root helper in place; bundled replacement is not enabled")
            return Result.FAILED
        }
        val priorRecordReply = Su.runOutputLong(
            bundledLegacyTakeoverRecordReadCommand(),
            INSTALL_TIMEOUT_MS,
        )
        if (priorRecordReply != null) {
            val priorRecord = parseBundledLegacyTakeoverRecord(priorRecordReply) ?: return Result.FAILED
            when (bundledLegacyPriorRecordDisposition(stagedBuildId, priorRecord)) {
                BundledLegacyPriorRecordDisposition.REPROVISION -> {
                    // The old boot helper plus another app build's retained live bytes need an exact
                    // normalization transaction. External provisioning owns that cross-build case.
                    return Result.REPROVISION_REQUIRED
                }
                BundledLegacyPriorRecordDisposition.KEEP -> Unit
            }
        }
        val incumbentBuildId = if (replacementMode == BundledHelperReplacementMode.FRESH_INSTALL) {
            null
        } else {
            HelperClient.installedBuildId()?.takeIf { it != stagedBuildId } ?: return Result.FAILED
        }
        val asset = helperAssetName(Build.SUPPORTED_ABIS.asIterable()) ?: return Result.SKIPPED
        val staged = runCatching { File.createTempFile("hapaneld-helper-", ".bin", context.cacheDir) }
            .getOrElse { return Result.FAILED }
        return try {
            context.assets.open(asset).use { input -> staged.outputStream().use(input::copyTo) }
            val expectedSha256 = AppInstaller.sha256(staged)
            val output = Su.runWithStdinLongChecked(
                bundledHelperStageCommand(expectedSha256),
                staged,
                INSTALL_TIMEOUT_MS,
            )
            if (!bundledHelperStaged(output)) {
                Log.w(TAG, "bundled helper staging failed: ${output.orEmpty().take(120)}")
                return Result.FAILED
            }
            val settlement = when (replacementMode) {
                BundledHelperReplacementMode.GUARDED_RETIRE -> executeBundledHelperReplacement(
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
                BundledHelperReplacementMode.RELEASED_LEGACY_TAKEOVER -> {
                    val installed = Su.runSingleAttempt(
                        bundledLegacyHelperTakeoverCommand(
                            expectedSha256,
                            stagedBuildId,
                            requireNotNull(incumbentBuildId),
                        ),
                        LEGACY_TAKEOVER_TIMEOUT_MS,
                    )
                    if (installed) BundledHelperReplacementSettlement.INSTALLED
                    else BundledHelperReplacementSettlement.HOLD
                }
                BundledHelperReplacementMode.FRESH_INSTALL ->
                    if (Su.runSingleAttempt(
                            bundledFreshHelperInstallCommand(expectedSha256, stagedBuildId),
                            LEGACY_TAKEOVER_TIMEOUT_MS,
                        )
                    ) BundledHelperReplacementSettlement.INSTALLED
                    else BundledHelperReplacementSettlement.HOLD
            }
            when (settlement) {
                BundledHelperReplacementSettlement.INSTALLED -> {
                    if (replacementMode == BundledHelperReplacementMode.GUARDED_RETIRE &&
                        !cleanupAnyBundledLegacyTakeoverRecord()
                    ) {
                        Log.w(TAG, "guarded helper installed but stale takeover authority cleanup failed")
                        Result.FAILED
                    } else {
                        Result.INSTALLED
                    }
                }
                BundledHelperReplacementSettlement.BLOCKED_ACTIVE -> Result.BLOCKED_ACTIVE
                BundledHelperReplacementSettlement.OLD_SAFE,
                BundledHelperReplacementSettlement.NOT_SUBMITTED,
                BundledHelperReplacementSettlement.HOLD -> Result.FAILED
            }
        } catch (failure: Exception) {
            Log.w(TAG, "bundled helper migration failed", failure)
            Result.FAILED
        } finally {
            staged.delete()
        }
    }

    private fun resumeRetainedCurrentOutcome(): Boolean? {
        val stagedBuildId = BuildConfig.HELPER_BUILD_ID
            .takeIf(GuardDbMaintenanceProtocol::validSha256) ?: return false
        val statusProbe = GuardDbMaintenance.client.statusProbe()
        val retainedLegacy = if (statusProbe == GuardDbMaintenanceClient.StatusProbe.Unreachable ||
            statusProbe == GuardDbMaintenanceClient.StatusProbe.Unsupported
        ) {
            Su.runOutputLong(bundledLegacyTakeoverRecordReadCommand(), INSTALL_TIMEOUT_MS)
                ?.let(::parseBundledLegacyTakeoverRecord)
                ?.takeIf { it.stagedBuildId == stagedBuildId }
        } else {
            null
        }
        if (!bundledLegacyRecoveryAllowed(statusProbe, retainedLegacy)) return null
        val record = requireNotNull(retainedLegacy)
        val resumed = Su.runSingleAttempt(
            bundledLegacyHelperResumeCommand(
                record.candidateSha256,
                record.stagedBuildId,
                record.incumbentBuildId,
            ),
            LEGACY_TAKEOVER_TIMEOUT_MS,
        )
        if (!resumed) return false
        return GuardDbMaintenance.client.statusProbe() is GuardDbMaintenanceClient.StatusProbe.Valid
    }

    /** A different app build may not discard the record that makes the live helper reboot-recoverable. */
    private fun currentBundledLegacyTakeoverRecordResult(currentBuildId: String): Result? {
        val reply = Su.runOutputLong(bundledLegacyTakeoverRecordReadCommand(), INSTALL_TIMEOUT_MS)
            ?: return if (verifyBundledLegacyTakeoverRecordAbsent()) null else Result.FAILED
        val record = parseBundledLegacyTakeoverRecord(reply) ?: return Result.FAILED
        if (record.stagedBuildId != currentBuildId) return Result.REPROVISION_REQUIRED
        return if (Su.runLong(
            bundledLegacyTakeoverRecordCleanupCommand(
                record.recordSha256,
                record.recordBytes,
                preserveIfOldAuthorityExact = record,
            ),
            INSTALL_TIMEOUT_MS,
        )) null else Result.FAILED
    }

    private fun cleanupAnyBundledLegacyTakeoverRecord(): Boolean {
        val reply = Su.runOutputLong(bundledLegacyTakeoverRecordReadCommand(), INSTALL_TIMEOUT_MS)
            ?: return verifyBundledLegacyTakeoverRecordAbsent()
        val record = parseBundledLegacyTakeoverRecord(reply) ?: return false
        return cleanupBundledLegacyTakeoverRecord(record)
    }

    private fun cleanupBundledLegacyTakeoverRecord(record: BundledLegacyTakeoverRecord): Boolean =
        Su.runLong(
            bundledLegacyTakeoverRecordCleanupCommand(record.recordSha256, record.recordBytes),
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
        """
        if (
        ${bundledLegacyTakeoverOldAuthorityExactCommand(record, filesystemRoot).prependIndent("  ")}
        ); then exit 0; fi
        """.trimIndent()
    }.orEmpty()
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
        [ -f "${'$'}record" ] && [ ! -L "${'$'}record" ] || exit 1
        meta=${'$'}(stat -c '%u:%g:%a:%h:%s' "${'$'}record" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${'$'}record" 2>/dev/null) || exit 1
        [ "${'$'}meta" = "0:0:600:1:$expectedRecordBytes" ] || exit 1
        record_hash=${'$'}(sha256sum "${'$'}record" 2>/dev/null || toybox sha256sum "${'$'}record" 2>/dev/null) || exit 1
        [ "${'$'}{record_hash%% *}" = '$expectedRecordSha256' ] || exit 1
        $preserveExact
        # Rebind pathname metadata and bytes immediately before unlink; the shared lock excludes every
        # helper transaction writer, while the exact digest prevents a same-target foreign replacement.
        meta=${'$'}(stat -c '%u:%g:%a:%h:%s' "${'$'}record" 2>/dev/null || toybox stat -c '%u:%g:%a:%h:%s' "${'$'}record" 2>/dev/null) || exit 1
        [ "${'$'}meta" = "0:0:600:1:$expectedRecordBytes" ] || exit 1
        record_hash=${'$'}(sha256sum "${'$'}record" 2>/dev/null || toybox sha256sum "${'$'}record" 2>/dev/null) || exit 1
        [ "${'$'}{record_hash%% *}" = '$expectedRecordSha256' ] || exit 1
        rm -f "${'$'}record" || exit 1
        sync || exit 1
    """.trimIndent()
}

internal enum class BundledHelperReplacementMode {
    GUARDED_RETIRE,
    RELEASED_LEGACY_TAKEOVER,
    FRESH_INSTALL,
}

internal enum class BundledLegacyPriorRecordDisposition { KEEP, REPROVISION }

internal fun bundledLegacyPriorRecordDisposition(
    currentBuildId: String,
    record: BundledLegacyTakeoverRecord,
): BundledLegacyPriorRecordDisposition = if (record.stagedBuildId == currentBuildId) {
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

// Every place a provisioned helper lives or boots from. A fresh install must find none of them.
private val BUNDLED_HELPER_BOOT_AUTHORITIES = listOf(
    "/system/bin/hapaneld-helper",
    "/system/etc/init/hapaneld-helper.rc",
    "/vendor/etc/init/hapaneld-helper.rc",
    "/data/adb/hapaneld",
    "/data/adb/service.d/hapaneld-helper.sh",
    "/system/bin/hapaneld-ledd",
    "/system/etc/init/hapaneld-ledd.rc",
)

// Foreign recovery authorities fence both staging and takeover, even after their writer exits.
// App-owned takeover records and previous-byte custody have separate admission/resume rules.
private val BUNDLED_HELPER_FOREIGN_JOURNALS = listOf(
    "/data/local/.hapaneld-guard-db/replacement.v1",
    "/data/local/.hapaneld-guard-db/.replacement.v1.tmp",
    "/system/bin/.hapaneld-helper-upgrade",
    "/system/bin/.hapaneld-helper-manual-upgrade",
    "/data/adb/hapaneld/.helper-upgrade.marker",
    "/data/adb/hapaneld/.helper-hybrid-upgrade.marker",
    "/data/adb/hapaneld/.helper-manual-upgrade.marker",
    "/data/local/.hapaneld-helper-manual-upgrade",
).joinToString(" ") { "\"\$root$it\"" }

/** Root stages one fixed candidate; replacement happens only through the selected exact authority. */
internal fun bundledHelperStageCommand(
    expectedSha256: String,
    filesystemRoot: String = "",
): String {
    require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
    require(filesystemRoot.isEmpty() ||
        (filesystemRoot.startsWith("/") && !filesystemRoot.endsWith("/") &&
            SAFE_ABSOLUTE_PATH.matches(filesystemRoot) &&
            filesystemRoot.split('/').none { it == ".." }))
    val dollar = '$'
    return """
        root=$filesystemRoot
        data_local=${dollar}root/data/local
        lock=${dollar}root/dev/.hapaneld-helper-transaction.lock
        stage=${dollar}data_local/.hapaneld-helper.new
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
        other_authority_absent() {
          for authority in \
            $BUNDLED_HELPER_FOREIGN_JOURNALS \
            "${dollar}root/data/local/.hapaneld-helper.legacy-takeover" \
            "${dollar}root/data/local/.hapaneld-helper.previous" \
            "${dollar}root/data/local/.hapaneld-helper.previous.tmp"; do
            [ ! -e "${dollar}authority" ] && [ ! -L "${dollar}authority" ] || return 1
          done
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
    val dollar = '$'
    val expectedCapabilities =
        "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE"
    val expectedStatus = "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0"
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
    val acceptedStatus = if (retainedResumeOnly) {
        "[ -n \"${dollar}status\" ]"
    } else {
        "[ \"${dollar}status\" = \"$expectedStatus\" ]"
    }
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
        on_takeover_signal() {
          trap - 1 2 3 15
          rollback_candidate >/dev/null 2>&1 || true
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
        retained_record=0
        if [ -f "${dollar}record" ] && [ ! -L "${dollar}record" ]; then
          record_meta=${dollar}(file_meta "${dollar}record") || exit 1
          case "${dollar}record_meta" in 0:0:600:1:*) ;; *) exit 1 ;; esac
          [ "${dollar}(cat "${dollar}record")" = "${dollar}expected_record" ] || exit 1
          retained_record=1
          absent "${dollar}record_tmp" || exit 1
        else
          absent "${dollar}record" || exit 1
          $resumeAdmission
          if [ -f "${dollar}record_tmp" ] && [ ! -L "${dollar}record_tmp" ]; then
            [ "${dollar}candidate_source" = "${dollar}stage" ] && absent "${dollar}live" || exit 1
            record_tmp_meta=${dollar}(file_meta "${dollar}record_tmp") || exit 1
            case "${dollar}record_tmp_meta" in 0:0:600:1:*) ;; *) exit 1 ;; esac
            record_tmp_bytes=${dollar}{record_tmp_meta##*:}
            [ "${dollar}record_tmp_bytes" -le 1024 ] || exit 1
            rm -f "${dollar}record_tmp" || exit 1
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
          sync || return 1
          return 0
        }

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
            exact_file "${dollar}live" "$expectedSha256" 700 "${dollar}candidate_bytes" || { rollback_candidate; exit 1; }
            exact_file "${dollar}old_bin" "${dollar}old_sha" 755 "${dollar}old_bytes" || { rollback_candidate; exit 1; }
            exact_file "${dollar}registration" "${dollar}registration_sha" "${dollar}registration_mode" "${dollar}registration_bytes" || { rollback_candidate; exit 1; }
            if [ -f "${dollar}stage" ]; then exact_file "${dollar}stage" "$expectedSha256" 700 "${dollar}candidate_bytes" || exit 1; rm -f "${dollar}stage" || exit 1; fi
            sync || exit 1
            exit 0
          fi
        fi
        rollback_candidate || exit 1
        exit 1
    """.trimIndent()
}

/**
 * The only helper is the one this app publishes: no boot binary, boot registration, provisioner
 * custody, recovery journal or takeover record may exist, or a second root authority would appear
 * beside one that a reboot or a running transaction brings back. Under the shared helper lock the
 * staged candidate replaces any earlier `/data/local` copy that is not running (a reboot keeps the file
 * and loses the process), then supervises itself and must prove its exact bytes and Guard capabilities.
 * Refusal leaves no candidate behind; a candidate that cannot prove itself is stopped.
 */
internal fun bundledFreshHelperInstallCommand(
    expectedSha256: String,
    stagedBuildId: String,
    filesystemRoot: String = "",
    polls: Int = LEGACY_TAKEOVER_POLLS,
): String {
    require(GuardDbMaintenanceProtocol.validSha256(expectedSha256))
    require(GuardDbMaintenanceProtocol.validSha256(stagedBuildId))
    require(filesystemRoot.isEmpty() ||
        (filesystemRoot.startsWith("/") && !filesystemRoot.endsWith("/") &&
            SAFE_ABSOLUTE_PATH.matches(filesystemRoot) &&
            filesystemRoot.split('/').none { it == ".." }))
    require(polls in 1..LEGACY_TAKEOVER_POLLS)
    val dollar = '$'
    val expectedCapabilities =
        "${GuardDbMaintenanceProtocol.CAPS_REPLY} AUTONOMOUS SUPERVISED TERMINAL_RETIRE"
    val otherHelpers = BUNDLED_HELPER_BOOT_AUTHORITIES.joinToString(" ") { "\"${dollar}root$it\"" }
    return """
        root=$filesystemRoot
        data_local=${dollar}root/data/local
        lock=${dollar}root/dev/.hapaneld-helper-transaction.lock
        stage=${dollar}data_local/.hapaneld-helper.new
        live=${dollar}data_local/hapaneld-helper
        if ! mkdir "${dollar}lock" 2>/dev/null; then
          holder=${dollar}(cat "${dollar}lock/pid" 2>/dev/null || true)
          case "${dollar}holder" in ''|*[!0-9]*) exit 75 ;; *) [ ! -d "/proc/${dollar}holder" ] || exit 75 ;; esac
          rm -rf "${dollar}lock" 2>/dev/null || exit 75
          mkdir "${dollar}lock" 2>/dev/null || exit 75
        fi
        echo ${dollar}${dollar} > "${dollar}lock/pid" || { rm -rf "${dollar}lock"; exit 75; }
        trap 'rm -rf "${dollar}lock"' 0
        trap 'rm -rf "${dollar}lock"; trap - 0; exit 74' 1 2 3 15
        [ -d "${dollar}data_local" ] && [ ! -L "${dollar}data_local" ] || exit 1

        file_hash() {
          hash=${dollar}(sha256sum "${dollar}1" 2>/dev/null || toybox sha256sum "${dollar}1" 2>/dev/null) || return 1
          printf '%s\n' "${dollar}{hash%% *}"
        }
        file_meta() {
          stat -c '%u:%g:%a:%h:%s' "${dollar}1" 2>/dev/null ||
            toybox stat -c '%u:%g:%a:%h:%s' "${dollar}1" 2>/dev/null
        }
        absent() { [ ! -e "${dollar}1" ] && [ ! -L "${dollar}1" ]; }
        exact_candidate() {
          [ -f "${dollar}1" ] && [ ! -L "${dollar}1" ] &&
            [ "${dollar}(file_hash "${dollar}1")" = "$expectedSha256" ] &&
            case "${dollar}(file_meta "${dollar}1")" in 0:0:700:1:*) true ;; *) false ;; esac
        }
        refuse() {
          if exact_candidate "${dollar}stage"; then rm -f "${dollar}stage"; fi
          exit 1
        }
        for authority in \
          $BUNDLED_HELPER_FOREIGN_JOURNALS \
          $otherHelpers \
          "${dollar}data_local/.hapaneld-helper.legacy-takeover" \
          "${dollar}data_local/.hapaneld-helper.legacy-takeover.tmp" \
          "${dollar}data_local/.hapaneld-helper.previous" \
          "${dollar}data_local/.hapaneld-helper.previous.tmp"; do
          absent "${dollar}authority" || refuse
        done
        if ! absent "${dollar}live"; then
          [ -f "${dollar}live" ] && [ ! -L "${dollar}live" ] || refuse
          case "${dollar}(file_meta "${dollar}live")" in 0:0:700:1:*) ;; *) refuse ;; esac
        fi
        exact_candidate "${dollar}stage" || exact_candidate "${dollar}live" || exit 1

        live_processes() {
          absent "${dollar}live" && return 0
          target_inode=${dollar}(stat -c '%d:%i' "${dollar}live" 2>/dev/null || toybox stat -c '%d:%i' "${dollar}live" 2>/dev/null) || return 1
          found=
          for executable in /proc/[0-9]*/exe; do
            inode=${dollar}(stat -Lc '%d:%i' "${dollar}executable" 2>/dev/null || toybox stat -L -c '%d:%i' "${dollar}executable" 2>/dev/null) || continue
            [ "${dollar}inode" != "${dollar}target_inode" ] || found="${dollar}found ${dollar}{executable#/proc/}"
          done
          printf '%s\n' "${dollar}found"
        }
        stop_live() {
          candidates=${dollar}(live_processes) || return 1
          for candidate in ${dollar}candidates; do kill "${dollar}{candidate%/exe}" 2>/dev/null || true; done
          attempt=0
          while [ "${dollar}attempt" -lt 3 ]; do
            candidates=${dollar}(live_processes) || return 1
            [ -n "${dollar}candidates" ] || return 0
            sleep 1; attempt=${dollar}((attempt + 1))
          done
          for candidate in ${dollar}candidates; do kill -9 "${dollar}{candidate%/exe}" 2>/dev/null || true; done
          sleep 1
          [ -z "${dollar}(live_processes)" ]
        }

        stop_live || exit 1
        if exact_candidate "${dollar}stage"; then mv -f "${dollar}stage" "${dollar}live" || exit 1; fi
        sync || exit 1
        exact_candidate "${dollar}live" && absent "${dollar}stage" || exit 1
        candidate_bytes=${dollar}(file_meta "${dollar}live") || exit 1
        candidate_bytes=${dollar}{candidate_bytes##*:}

        "${dollar}live" --supervise >/dev/null 2>&1 &
        attempt=0
        while [ "${dollar}attempt" -lt $polls ]; do
          self=${dollar}("${dollar}live" --request GUARDSELF 2>/dev/null) || self=
          [ "${dollar}self" != "OK GUARDSELF 1 ${dollar}candidate_bytes $expectedSha256 $stagedBuildId" ] || break
          attempt=${dollar}((attempt + 1))
          [ "${dollar}attempt" -ge $polls ] || sleep 1
        done
        if [ "${dollar}self" = "OK GUARDSELF 1 ${dollar}candidate_bytes $expectedSha256 $stagedBuildId" ] &&
           [ "${dollar}("${dollar}live" --request GUARDCAPS 2>/dev/null)" = "$expectedCapabilities" ]; then
          exit 0
        fi
        stop_live || true
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
private const val LEGACY_TAKEOVER_POLLS = 8
internal const val LEGACY_TAKEOVER_TIMEOUT_MS = 120_000L
private const val NO_RECORD_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
private val SAFE_ABSOLUTE_PATH = Regex("/[A-Za-z0-9._/-]+")
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
