package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.backup.CompanionRestore
import io.panelassistant.android.control.CompanionDataLease
import io.panelassistant.android.control.CompanionDataOperationGate
import io.panelassistant.android.control.CompanionDataOperationState
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.util.AndroidInput
import io.panelassistant.android.util.CompanionInstaller
import io.panelassistant.android.util.CompanionHelperProtocol
import io.panelassistant.android.util.HelperClient
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
) {
    /** Capture descriptor-opened raw files, then checkpoint only the private-cache SQLite copy. */
    fun capture(): CapturedCompanion {
        val pkg = installedCompanionPackage()
            ?: throw CompanionBackupUnavailable("HA Companion is not installed")
        if (pkg !in CompanionInstaller.SUPPORTED_PACKAGES || !AndroidInput.isPackage(pkg)) {
            throw CompanionBackupUnavailable("HA Companion package is not supported")
        }
        if (!ensureCompanionHelper()) {
            throw CompanionBackupUnavailable("HA Companion backup needs the current ha-paneld helper")
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
        var helperCapture: CompanionHelperProtocol.Capture? = null
        var needsCompanionRecovery = false
        try {
            val result = HelperClient.backupCompanion(pkg, cacheDir)
            val capture = when (result) {
                is CompanionHelperProtocol.BackupResult.Success -> result.capture.also {
                    needsCompanionRecovery = !it.relaunched
                }
                CompanionHelperProtocol.BackupResult.Busy -> {
                    lease.settle(possiblyInFlight = true) {}
                    throw CompanionBackupUnavailable("Companion helper is busy")
                }
                CompanionHelperProtocol.BackupResult.NotSubmitted ->
                    throw CompanionBackupUnavailable("Companion helper is unavailable")
                is CompanionHelperProtocol.BackupResult.Failed -> {
                    needsCompanionRecovery = result.relaunchFailed
                    throw CompanionBackupUnavailable(
                        if (result.relaunchFailed) "Companion capture failed and relaunch was not confirmed"
                        else "Companion capture failed",
                    )
                }
                CompanionHelperProtocol.BackupResult.Indeterminate -> {
                    lease.settle(possiblyInFlight = true) {
                        system.launchHome(pkg)
                        if (system.resolveDashboard(config.dashboardPackage) != pkg) {
                            system.launchHome(config.dashboardPackage)
                        }
                    }
                    throw CompanionBackupUnavailable("Companion capture result was indeterminate")
                }
            }
            helperCapture = capture
            val database = capture.files[CompanionRestore.DATABASE_FILE]
                ?: throw CompanionBackupUnavailable("Companion login database was not captured")
            if (!io.panelassistant.android.backup.CompanionDatabasePreparation.checkpointCapturedDatabase(
                    database,
                    capture.files[CompanionHelperProtocol.DATABASE_WAL_FILE],
                    capture.files[CompanionHelperProtocol.DATABASE_SHM_FILE],
                )
            ) throw CompanionBackupUnavailable("Companion login database could not be checkpointed safely")

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
            lease.settle(possiblyInFlight = false) {
                // The helper always launches Companion to clear Android's stopped state. Restore the
                // configured dashboard after releasing suppression when Companion is not it.
                if (needsCompanionRecovery) system.launchHome(pkg)
                if (system.resolveDashboard(config.dashboardPackage) != pkg) {
                    system.launchHome(config.dashboardPackage)
                }
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
        val preparation = io.panelassistant.android.backup.CompanionDatabasePreparation.prepare(plan, cacheDir)
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
            var result = CompanionHelperProtocol.RestoreResult.INDETERMINATE
            try {
                result = HelperClient.restoreCompanion(
                    plan.packageName,
                    prepared.files.associate { it.relativePath to it.file },
                )
                if (result == CompanionHelperProtocol.RestoreResult.INDETERMINATE ||
                    result == CompanionHelperProtocol.RestoreResult.BUSY
                ) {
                    lease.settle(possiblyInFlight = true) {
                        if (system.resolveDashboard(config.dashboardPackage) != plan.packageName) {
                            system.launchHome(config.dashboardPackage)
                        }
                    }
                }
            } finally {
                lease.settle(possiblyInFlight = false) {
                    if (result in setOf(
                        CompanionHelperProtocol.RestoreResult.COMMITTED_RELAUNCH_FAILED,
                        CompanionHelperProtocol.RestoreResult.ROLLED_BACK_RELAUNCH_FAILED,
                    )
                    ) system.launchHome(plan.packageName)
                    if (system.resolveDashboard(config.dashboardPackage) != plan.packageName) {
                        system.launchHome(config.dashboardPackage)
                    }
                }
            }
            val repaired = prepared.repairedInternalUrls
            return when (result) {
                CompanionHelperProtocol.RestoreResult.COMMITTED -> CompanionApplyResult(
                    true,
                    InstallProgress.ComponentResult(
                        InstallProgress.Outcome.SUCCEEDED,
                        plan.files.size,
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
                        plan.files.size,
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
        }
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
                operationStatus = HelperClient::companionOperationStatus,
                pollMs = COMPANION_STATUS_POLL_MS,
            )
        }
    }


    private companion object {
        const val COMPANION_STATUS_POLL_MS = 1_000L
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
