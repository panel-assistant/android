package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.backup.CompanionDatabasePreparation
import io.panelassistant.android.backup.CompanionRestore
import io.panelassistant.android.control.CompanionDataLease
import io.panelassistant.android.control.CompanionDataOperationGate
import io.panelassistant.android.control.CompanionDataOperationState
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.util.AndroidInput
import io.panelassistant.android.util.CompanionInstaller
import io.panelassistant.android.util.CompanionHelperProtocol
import io.panelassistant.android.util.CompanionOperationStatus
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.util.InstallOperationResult
import io.panelassistant.android.util.InstallPresentation
import io.panelassistant.android.util.InstallProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

internal class CompanionBackupOperations(
    private val installedCompanionPackage: () -> String?,
    private val cacheDir: File,
    private val ensureCompanionHelper: () -> Boolean,
    private val companionDataOperationState: CompanionDataOperationState,
    private val scope: CoroutineScope,
    private val config: Config,
    private val system: SystemController,
    private val helper: CompanionDataHelper = CompanionDataHelper.Root,
    private val database: CompanionDatabaseWork = CompanionDatabaseWork.Platform,
    private val statusPollMs: Long = COMPANION_STATUS_POLL_MS,
) {
    /** Capture descriptor-opened raw files, then checkpoint only the private-cache SQLite copy. */
    fun capture(): CapturedCompanion {
        val pkg = installedCompanionPackage()
            ?: throw CompanionBackupUnavailable("HA Companion is not installed")
        if (pkg !in CompanionInstaller.SUPPORTED_PACKAGES || !AndroidInput.isPackage(pkg)) {
            throw CompanionBackupUnavailable("HA Companion package is not supported")
        }
        if (!ensureCompanionHelper()) {
            throw CompanionBackupUnavailable("HA Companion backup needs the current Panel Assistant helper")
        }
        val lease = when (
            val acquisition = CompanionDataLease.acquireArmed(
                pkg,
                companionDataOperationState,
                ::retainCompanionLeaseUntilHelperIdle,
            )
        ) {
            is CompanionDataLease.Acquisition.Acquired -> acquisition.lease
            CompanionDataLease.Acquisition.GateBusy ->
                throw CompanionBackupUnavailable("Another Companion data operation is running")
            CompanionDataLease.Acquisition.MarkerFailed ->
                throw CompanionBackupUnavailable("Companion operation safety marker could not be persisted")
        }
        // Until the helper replies, assume it may still hold Companion data.
        var backup: CompanionHelperProtocol.BackupResult = CompanionHelperProtocol.BackupResult.Indeterminate
        var helperCapture: CompanionHelperProtocol.Capture? = null
        var needsCompanionRecovery = false
        try {
            backup = helper.backup(pkg, cacheDir)
            val capture = when (val result = backup) {
                is CompanionHelperProtocol.BackupResult.Success -> result.capture.also {
                    needsCompanionRecovery = !it.relaunched
                }
                CompanionHelperProtocol.BackupResult.Busy ->
                    throw CompanionBackupUnavailable("Companion helper is busy")
                CompanionHelperProtocol.BackupResult.NotSubmitted ->
                    throw CompanionBackupUnavailable("Companion helper is unavailable")
                is CompanionHelperProtocol.BackupResult.Failed -> {
                    needsCompanionRecovery = result.relaunchFailed
                    throw CompanionBackupUnavailable(
                        if (result.relaunchFailed) "Companion capture failed and relaunch was not confirmed"
                        else "Companion capture failed",
                    )
                }
                CompanionHelperProtocol.BackupResult.Indeterminate ->
                    throw CompanionBackupUnavailable("Companion capture result was indeterminate")
            }
            helperCapture = capture
            val database = capture.files[CompanionRestore.DATABASE_FILE]
                ?: throw CompanionBackupUnavailable("Companion login database was not captured")
            if (!checkpoint(capture, database)) {
                throw CompanionBackupUnavailable("Companion login database could not be checkpointed safely")
            }

            val captured = CompanionRestore.ALLOWED_FILES.mapNotNull { relative ->
                capture.files[relative]?.let { CapturedCompanionFile(relative, it) }
            }
            val total = captured.sumOf { it.file.length() }
            if (captured.none { it.relativePath == CompanionRestore.DATABASE_FILE } ||
                captured.any { it.file.length() !in 1..CompanionRestore.maxBytes(it.relativePath) } ||
                total > MAX_COMPANION_BACKUP_BYTES
            ) throw CompanionBackupUnavailable("Companion capture exceeded portable backup bounds")
            if (!capture.relaunched) {
                throw CompanionBackupUnavailable("Companion was captured but helper relaunch failed")
            }
            helperCapture = null
            return CapturedCompanion(pkg, captured, capture)
        } finally {
            lease.settle(possiblyInFlight = backup.mayStillHoldData()) {
                if (backup == CompanionHelperProtocol.BackupResult.Busy) return@settle
                // The helper always launches Companion to clear Android's stopped state. Restore the
                // configured dashboard after releasing suppression when Companion is not it.
                if (needsCompanionRecovery || backup == CompanionHelperProtocol.BackupResult.Indeterminate) {
                    system.launchHome(pkg)
                }
                returnToDashboard(pkg)
            }
            helperCapture?.close()
        }
    }
    /** Execute a prevalidated Companion restore through the descriptor-confined helper transaction. */
    fun restore(plan: CompanionRestore.Plan): CompanionApplyResult {
        if (plan.packageName !in CompanionInstaller.SUPPORTED_PACKAGES || !AndroidInput.isPackage(plan.packageName)) {
            return CompanionApplyResult(
                false,
                InstallProgress.ComponentResult(
                    InstallProgress.Outcome.FAILED,
                    0,
                    "unsupported Companion package",
                    InstallPresentation("companion-unsupported-package"),
                ),
            )
        }
        val preparation = database.prepare(plan, cacheDir)
            ?: return CompanionApplyResult(
                false,
                InstallProgress.ComponentResult(
                    InstallProgress.Outcome.FAILED,
                    0,
                    "Companion payload validation failed",
                    InstallPresentation("companion-payload-invalid"),
                ),
            )
        preparation.use { prepared ->
            val lease = when (
                val acquisition = CompanionDataLease.acquireArmed(
                    plan.packageName,
                    companionDataOperationState,
                    ::retainCompanionLeaseUntilHelperIdle,
                )
            ) {
                is CompanionDataLease.Acquisition.Acquired -> acquisition.lease
                CompanionDataLease.Acquisition.GateBusy -> return CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.FAILED,
                        0,
                        "Companion helper is busy",
                        InstallPresentation("companion-helper-busy"),
                    ),
                )
                CompanionDataLease.Acquisition.MarkerFailed -> return CompanionApplyResult(
                    false,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.FAILED,
                        0,
                        "Companion operation safety marker could not be persisted",
                        InstallPresentation("companion-marker-failed"),
                    ),
                )
            }
            // Until the helper replies, assume its transaction may still be running.
            var result = CompanionHelperProtocol.RestoreResult.INDETERMINATE
            try {
                result = helper.restore(plan.packageName, prepared.files.associate { it.relativePath to it.file })
            } finally {
                lease.settle(possiblyInFlight = result.mayStillHoldData()) {
                    if (result in RELAUNCH_FAILED) system.launchHome(plan.packageName)
                    returnToDashboard(plan.packageName)
                }
            }
            return restoreOutcome(result, plan.files.size, prepared.repairedInternalUrls)
        }
    }

    /**
     * Copy each blank Companion `internal_url` from its `external_url` (the HA 2026.7 "Missing 'Host'
     * header" repair) without editing the live database in place. One data lease suppresses every
     * ha-paneld Companion launch from before the helper stops the Companion until the repair is terminal,
     * and the helper keeps the captured Companion stopped (HOLD) rather than relaunching it: the private
     * copy is checkpointed and repaired with the checks a backup restore uses, and the repaired copy goes
     * back through the helper's rollback-safe restore transaction only while the live database and WAL
     * are still the bytes captured (EXPECT). Anything written since, by a launch from outside ha-paneld,
     * refuses the restore. Every refusal leaves the live files as they were.
     */
    fun repairInternalUrls(): InstallOperationResult {
        val pkg = installedCompanionPackage() ?: return InstallOperationResult("no HA Companion app installed")
        if (pkg !in CompanionInstaller.SUPPORTED_PACKAGES || !AndroidInput.isPackage(pkg)) {
            return InstallOperationResult("HA Companion package is not supported")
        }
        if (!ensureCompanionHelper()) {
            return InstallOperationResult("Companion URL repair needs the current Panel Assistant helper")
        }
        val lease = when (
            val acquisition = CompanionDataLease.acquireArmed(
                pkg,
                companionDataOperationState,
                ::retainCompanionLeaseUntilHelperIdle,
            )
        ) {
            is CompanionDataLease.Acquisition.Acquired -> acquisition.lease
            CompanionDataLease.Acquisition.GateBusy ->
                return InstallOperationResult("another Companion data operation is running; nothing was changed")
            CompanionDataLease.Acquisition.MarkerFailed ->
                return InstallOperationResult("Companion operation safety marker could not be persisted; nothing was changed")
        }
        // Until the helper replies to a call, assume it may still hold Companion data.
        var backup: CompanionHelperProtocol.BackupResult = CompanionHelperProtocol.BackupResult.Indeterminate
        var restored: CompanionHelperProtocol.RestoreResult? = null
        var capture: CompanionHelperProtocol.Capture? = null
        try {
            backup = helper.backup(pkg, cacheDir, hold = true)
            val captured = (backup as? CompanionHelperProtocol.BackupResult.Success)?.capture
                ?: return InstallOperationResult(captureRefusal(backup))
            capture = captured
            val file = captured.files[CompanionRestore.DATABASE_FILE]
                ?: return InstallOperationResult("Companion database was not captured; nothing was changed")
            // The live bytes as captured, read before the checkpoint below rewrites this copy.
            val live = CompanionHelperProtocol.LiveDigest.of(captured)
                ?: return InstallOperationResult("Companion database was not captured; nothing was changed")
            if (!checkpoint(captured, file)) {
                return InstallOperationResult("Companion database could not be checkpointed safely; nothing was changed")
            }
            val plan = when (
                val planned = CompanionRestore.planFiles(
                    pkg,
                    listOf(CompanionRestore.FilePayload(CompanionRestore.DATABASE_FILE, file, deleteOnClose = false)),
                    setOf(pkg),
                )
            ) {
                is CompanionRestore.PlanResult.Valid -> planned.plan
                is CompanionRestore.PlanResult.Invalid ->
                    return InstallOperationResult("Companion database is outside repair bounds; nothing was changed")
            }
            val preparation = database.prepare(plan, cacheDir)
                ?: return InstallOperationResult("Companion database could not be repaired safely; nothing was changed")
            preparation.use { prepared ->
                if (prepared.repairedInternalUrls == 0) {
                    return InstallOperationResult("nothing to repair — every server already has an internal URL")
                }
                restored = CompanionHelperProtocol.RestoreResult.INDETERMINATE
                val result = helper.restore(pkg, prepared.files.associate { it.relativePath to it.file }, live)
                restored = result
                val outcome = restoreOutcome(result, prepared.files.size, prepared.repairedInternalUrls).component
                return InstallOperationResult(outcome.detail, outcome.presentation)
            }
        } finally {
            val captureResult = backup
            val restoreResult = restored
            lease.settle(possiblyInFlight = captureResult.mayStillHoldData() || restoreResult?.mayStillHoldData() == true) {
                // The helper relaunches the Companion after a completed restore and leaves a held capture
                // stopped; launch it here where neither happened and no partial file set is held back.
                val companionStopped = when {
                    captureResult == CompanionHelperProtocol.BackupResult.Indeterminate -> true
                    captureResult is CompanionHelperProtocol.BackupResult.Failed -> captureResult.relaunchFailed
                    restoreResult != null -> restoreResult in RELAUNCH_FAILED ||
                        capture?.relaunched == false && restoreResult in setOf(
                            CompanionHelperProtocol.RestoreResult.FAILED,
                            CompanionHelperProtocol.RestoreResult.NOT_SUBMITTED,
                        )
                    else -> capture?.relaunched == false
                }
                if (companionStopped) system.launchHome(pkg)
                returnToDashboard(pkg)
            }
            capture?.close()
        }
    }

    private fun captureRefusal(backup: CompanionHelperProtocol.BackupResult): String = when (backup) {
        CompanionHelperProtocol.BackupResult.Busy -> "Companion helper is busy; nothing was changed"
        CompanionHelperProtocol.BackupResult.NotSubmitted -> "Companion helper is unavailable; nothing was changed"
        is CompanionHelperProtocol.BackupResult.Failed ->
            if (backup.relaunchFailed) {
                "Companion could not be captured and its relaunch was not confirmed; nothing was changed"
            } else {
                "Companion could not be captured; nothing was changed"
            }
        CompanionHelperProtocol.BackupResult.Indeterminate ->
            "Companion capture result was indeterminate; nothing was changed"
        is CompanionHelperProtocol.BackupResult.Success -> error("a successful capture is not a refusal")
    }

    private fun checkpoint(capture: CompanionHelperProtocol.Capture, file: File): Boolean = database.checkpoint(
        file,
        capture.files[CompanionHelperProtocol.DATABASE_WAL_FILE],
        capture.files[CompanionHelperProtocol.DATABASE_SHM_FILE],
    )

    private fun returnToDashboard(companionPackage: String) {
        if (system.resolveDashboard(config.dashboardPackage) != companionPackage) {
            system.launchHome(config.dashboardPackage)
        }
    }

    private fun restoreOutcome(
        result: CompanionHelperProtocol.RestoreResult,
        fileCount: Int,
        repaired: Int,
    ): CompanionApplyResult = when (result) {
        CompanionHelperProtocol.RestoreResult.COMMITTED -> CompanionApplyResult(
            true,
            InstallProgress.ComponentResult(
                InstallProgress.Outcome.SUCCEEDED,
                fileCount,
                if (repaired > 0) "$repaired blank internal URL(s) repaired" else "owner/context restored",
                if (repaired > 0) {
                    InstallPresentation(
                        "companion-urls-repaired",
                        mapOf("count" to repaired.toString()),
                    )
                } else {
                    InstallPresentation("companion-owner-restored")
                },
            ),
        )
        CompanionHelperProtocol.RestoreResult.COMMITTED_RELAUNCH_FAILED -> CompanionApplyResult(
            false,
            InstallProgress.ComponentResult(
                InstallProgress.Outcome.PARTIAL,
                fileCount,
                "files restored but Companion relaunch was not confirmed",
                InstallPresentation("companion-relaunch-unconfirmed"),
            ),
        )
        CompanionHelperProtocol.RestoreResult.ROLLED_BACK,
        CompanionHelperProtocol.RestoreResult.ROLLED_BACK_RELAUNCH_FAILED -> CompanionApplyResult(
            false,
            InstallProgress.ComponentResult(
                InstallProgress.Outcome.ROLLED_BACK,
                0,
                "restore failed; prior Companion files retained",
                InstallPresentation("companion-prior-files-retained"),
            ),
        )
        CompanionHelperProtocol.RestoreResult.CHANGED,
        CompanionHelperProtocol.RestoreResult.CHANGED_RELAUNCH_FAILED -> CompanionApplyResult(
            false,
            InstallProgress.ComponentResult(
                InstallProgress.Outcome.FAILED,
                0,
                "the Companion's database changed during the repair; nothing was replaced",
            ),
        )
        CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED,
        CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED_RELAUNCH_FAILED,
        CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED_RELAUNCH_SUPPRESSED -> CompanionApplyResult(
            false,
            InstallProgress.ComponentResult(
                InstallProgress.Outcome.ROLLBACK_FAILED,
                null,
                "restore and rollback failed; Companion state may be partial",
                InstallPresentation("companion-rollback-failed"),
            ),
        )
        CompanionHelperProtocol.RestoreResult.BUSY -> CompanionApplyResult(
            false,
            InstallProgress.ComponentResult(
                InstallProgress.Outcome.FAILED,
                0,
                "Companion helper is busy",
                InstallPresentation("companion-helper-busy"),
            ),
        )
        CompanionHelperProtocol.RestoreResult.NOT_SUBMITTED -> CompanionApplyResult(
            false,
            InstallProgress.ComponentResult(
                InstallProgress.Outcome.FAILED,
                0,
                "Companion helper is unavailable",
                InstallPresentation("companion-helper-unavailable"),
            ),
        )
        CompanionHelperProtocol.RestoreResult.FAILED -> CompanionApplyResult(
            false,
            InstallProgress.ComponentResult(
                InstallProgress.Outcome.FAILED,
                0,
                "restore rejected before commit",
                InstallPresentation("companion-rejected-before-commit"),
            ),
        )
        CompanionHelperProtocol.RestoreResult.INDETERMINATE -> CompanionApplyResult(
            false,
            InstallProgress.ComponentResult(
                InstallProgress.Outcome.PARTIAL,
                null,
                "restore terminal status was indeterminate",
                InstallPresentation("companion-indeterminate"),
            ),
        )
    }

    /** A timed-out socket does not cancel the helper worker. Keep every automatic launch path blocked
     * until a reachable helper affirmatively reports that the transaction can no longer be active. */
    private fun retainCompanionLeaseUntilHelperIdle(
        lease: CompanionDataOperationGate.Lease,
        afterRelease: () -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            retainCompanionLeaseUntilHelperIdle(
                lease = lease,
                operationState = companionDataOperationState,
                afterRelease = afterRelease,
                operationStatus = helper::status,
                pollMs = statusPollMs,
            )
        }
    }

    private companion object {
        const val COMPANION_STATUS_POLL_MS = 1_000L
        val RELAUNCH_FAILED = setOf(
            CompanionHelperProtocol.RestoreResult.COMMITTED_RELAUNCH_FAILED,
            CompanionHelperProtocol.RestoreResult.ROLLED_BACK_RELAUNCH_FAILED,
            CompanionHelperProtocol.RestoreResult.CHANGED_RELAUNCH_FAILED,
        )
    }
}

/**
 * Whether the helper may still hold or be changing Companion data after this capture reply, so launch
 * suppression must outlive the call until the helper reports IDLE. A failed capture whose relaunch was
 * not confirmed covers a failed stop and a pending journal the helper could not recover.
 */
internal fun CompanionHelperProtocol.BackupResult.mayStillHoldData(): Boolean =
    this == CompanionHelperProtocol.BackupResult.Busy ||
        this == CompanionHelperProtocol.BackupResult.Indeterminate ||
        this is CompanionHelperProtocol.BackupResult.Failed && relaunchFailed

/** The restore counterpart of [CompanionHelperProtocol.BackupResult.mayStillHoldData]: an unknown or
 *  busy reply, or a rollback the helper must still recover, keeps launch suppression. */
internal fun CompanionHelperProtocol.RestoreResult.mayStillHoldData(): Boolean = this in setOf(
    CompanionHelperProtocol.RestoreResult.INDETERMINATE,
    CompanionHelperProtocol.RestoreResult.BUSY,
    CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED,
    CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED_RELAUNCH_FAILED,
    CompanionHelperProtocol.RestoreResult.ROLLBACK_FAILED_RELAUNCH_SUPPRESSED,
)

/** The root helper's Companion data verbs, an interface so a JVM test can stand in for the device. */
internal interface CompanionDataHelper {
    fun backup(packageName: String, cacheDir: File, hold: Boolean = false): CompanionHelperProtocol.BackupResult
    fun restore(
        packageName: String,
        files: Map<String, File>,
        expected: CompanionHelperProtocol.LiveDigest? = null,
    ): CompanionHelperProtocol.RestoreResult
    fun status(): CompanionOperationStatus

    object Root : CompanionDataHelper {
        override fun backup(packageName: String, cacheDir: File, hold: Boolean) =
            HelperClient.backupCompanion(packageName, cacheDir, hold)
        override fun restore(
            packageName: String,
            files: Map<String, File>,
            expected: CompanionHelperProtocol.LiveDigest?,
        ) = HelperClient.restoreCompanion(packageName, files, expected)
        override fun status() = HelperClient.companionOperationStatus()
    }
}

/** Private-copy SQLite work on Android's engine, an interface because the JVM suite has no such engine. */
internal interface CompanionDatabaseWork {
    fun checkpoint(database: File, wal: File?, shm: File?): Boolean
    fun prepare(plan: CompanionRestore.Plan, cacheDir: File): CompanionRestore.Preparation?

    object Platform : CompanionDatabaseWork {
        override fun checkpoint(database: File, wal: File?, shm: File?) =
            CompanionDatabasePreparation.checkpointCapturedDatabase(database, wal, shm)
        override fun prepare(plan: CompanionRestore.Plan, cacheDir: File) =
            CompanionDatabasePreparation.prepare(plan, cacheDir)
    }
}

internal data class CapturedCompanionFile(val relativePath: String, val file: File)
internal data class CapturedCompanion(
    val packageName: String,
    val files: List<CapturedCompanionFile>,
    val owner: java.io.Closeable,
) : java.io.Closeable {
    override fun close() = owner.close()
}

internal data class CompanionApplyResult(
    val ok: Boolean,
    val component: InstallProgress.ComponentResult,
)
