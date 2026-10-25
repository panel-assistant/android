package io.panelassistant.android.http

import android.content.Context
import android.util.Log
import io.panelassistant.android.Config
import io.panelassistant.android.OwnerChoicesPlan
import io.panelassistant.android.applyOwnerChoices
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.DashboardEntityBackupState
import io.panelassistant.android.backup.CompanionRestore
import io.panelassistant.android.device.profile.ProfileAdmin
import io.panelassistant.android.device.profile.ProfileBackupRestoreOutcome
import io.panelassistant.android.device.profile.ProfileBackupRestorePlan
import io.panelassistant.android.device.profile.ProfileBackupRestoreResult
import io.panelassistant.android.dashboard.EntityCatalogStore
import io.panelassistant.android.persistence.AppState
import io.panelassistant.android.persistence.CatalogProximityCalibrationCustody
import io.panelassistant.android.persistence.ProximityCalibrationCustody
import io.panelassistant.android.persistence.ConfigVault
import io.panelassistant.android.util.InstallPresentation
import io.panelassistant.android.util.InstallProgress

internal fun interface RestoreConfigCommit {
    suspend fun apply(
        accepted: Map<String, String>,
        entityState: DashboardEntityBackupState?,
        expectedRevision: String,
        existingOperationTicket: InstallProgress.Ticket,
        onDurableRevision: (String) -> Unit,
        afterCommitBeforeRenderer: (RendererConfigEffects, Int, String) -> Unit,
        afterApply: () -> Unit,
    ): Int
}

internal class RestoreExecutor(
    private val appContext: android.content.Context,
    private val config: Config,
    private val currentValues: () -> Map<String, String>,
    private val capabilities: () -> Capabilities,
    private val revisionValues: (Map<String, String>, DashboardEntityBackupState) -> Map<String, String>,
    private val commitConfig: RestoreConfigCommit,
    private val rollbackConfig: suspend (Map<String, String>, DashboardEntityBackupState, String, InstallProgress.Ticket) -> Boolean,
    private val restoreCompanion: (CompanionRestore.Plan) -> CompanionApplyResult,
    private val reconcileAfterCompanionRestore: (RendererConfigEffects?) -> Unit,
    private val profileAdmin: ProfileAdmin?,
    private val onProfileRestart: () -> Boolean,
    private val onProfileRestartAbort: (String) -> Boolean,
    private val onDurableStateRestored: () -> Unit,
    private val onWakeWordsChanged: () -> Unit,
    private val calibrations: ProximityCalibrationCustody = CatalogProximityCalibrationCustody(appContext),
    private val dashboardIssues: io.panelassistant.android.persistence.DashboardIssueCustody =
        io.panelassistant.android.persistence.CatalogDashboardIssueCustody(appContext),
    private val applyState: (List<ConfigVault.StateRow>) -> Int = { AppState.applyRestoredRows(appContext, it) },
) {
    suspend fun execute(
        configPlan: RestoreConfigPlan,
        entityState: DashboardEntityBackupState?,
        profilePayload: io.panelassistant.android.device.profile.ProfileBackupDecodeResult?,
        profilePlan: ProfileBackupRestorePlan?,
        companionPlan: CompanionRestore.Plan?,
        restorableState: List<ConfigVault.StateRow>,
        stateUnavailable: Boolean,
        restoreWakeWords: List<io.panelassistant.android.assist.wakeword.WakeWordCatalog.ImportedFiles>,
        wakeWordCatalog: io.panelassistant.android.assist.wakeword.WakeWordCatalog?,
        progress: InstallProgress.Ticket,
        restorableCalibrations: List<EntityCatalogStore.ProximityModelRow> = emptyList(),
        ownerChoices: OwnerChoicesPlan = OwnerChoicesPlan(emptyMap()),
        restorableIssues: List<io.panelassistant.android.persistence.DashboardIgnoredIssue> = emptyList(),
        stateSkipped: Int = 0,
    ) {
        val before = currentValues()
        // Preview is not admission: sample current capability again for the actual execution.
        val executionCapabilities = capabilities()
        val admittedPlan = configPlan.withAvailableLiveSettings(executionCapabilities)
        // Initially unavailable live values were never touched. Do not turn a genuine rollback
        // into another refusal by trying them; previously supported keys remain in rollback even
        // if their privilege disappears after this snapshot.
        val rollbackValues = before - unavailableRestoreSettings(before, executionCapabilities).toSet()
        val beforeEntityState = config.dashboardEntityBackupState()
        val beforeRevisionHash = io.panelassistant.android.config.ConfigHash.of(
            revisionValues(before, beforeEntityState),
        )
        var configCommitted = false
        var configItems = 0
        var restoredStateRows = 0
        var restoredCalibrations = 0
        val admittedStateCount = restorableState.size + ownerChoices.values.size + restorableIssues.size
        var ownerChoicesRestored = 0
        var issuesRestored = 0
        var stateRefreshFailed = false
        // A row the restore policy withheld was never admitted, so the restore is partial.
        val policySkipIsPartial = stateSkipped > 0
        fun stateComponent(): InstallProgress.ComponentResult? {
            // Calibration restore only fills gaps. Count actual inserts, not existing local rows.
            val admitted = admittedStateCount + restoredCalibrations
            if (admitted == 0 && stateSkipped == 0 && !stateUnavailable) return null
            val restored = restoredStateRows + ownerChoicesRestored + issuesRestored + restoredCalibrations
            val failed = (admitted - restored).coerceAtLeast(0)
            return InstallProgress.ComponentResult(
                status = if (failed > 0 || stateRefreshFailed) InstallProgress.Outcome.PARTIAL
                    else if (stateSkipped > 0 || stateUnavailable) InstallProgress.Outcome.SKIPPED
                    else InstallProgress.Outcome.SUCCEEDED,
                items = restored,
                detail = "admitted=$admitted; skipped=$stateSkipped; failed=$failed" +
                    if (stateRefreshFailed) "; runtime reload failed" else "",
            )
        }
        var companionResult: CompanionApplyResult? = null
        var profileResult: ProfileBackupRestoreResult? = null
        var appliedRevisionHash: String? = null
        // Before the configuration, so a restored `voice_wake_words` selecting an imported id finds
        // its model when the listener rearms. Additive and never fatal: a model the engine refuses is
        // reported in the result, and a later configuration rollback leaves these imports in place,
        // exactly as a user's own import would stay.
        val wakeWordOutcome = if (restoreWakeWords.isEmpty()) null else runCatching {
            io.panelassistant.android.backup.WakeWordBackup.restore(requireNotNull(wakeWordCatalog), restoreWakeWords)
        }.getOrElse { failure ->
            io.panelassistant.android.backup.WakeWordBackup.Outcome(
                emptyList(),
                restoreWakeWords.map { it.id to (failure.message ?: "could not be saved") },
            )
        }
        if (wakeWordOutcome?.restored?.isNotEmpty() == true) runCatching { onWakeWordsChanged() }
        val wakeWordComponent = wakeWordOutcome?.let(::wakeWordRestoreComponent)
        val operation = runCatching {
            configItems = commitConfig.apply(
                admittedPlan.values,
                entityState,
                beforeRevisionHash,
                existingOperationTicket = progress,
                onDurableRevision = { appliedHash ->
                    configCommitted = true
                    appliedRevisionHash = appliedHash
                },
                afterCommitBeforeRenderer = { effects, acceptedCount, appliedHash ->
                    configItems = acceptedCount
                    appliedRevisionHash = appliedHash
                    companionResult = companionPlan?.let(restoreCompanion)
                    if (companionResult?.ok == false) throw CompanionApplyFailed()
                    reconcileAfterCompanionRestore(effects)
                },
                afterApply = afterApply@{
                    // Post-commit, and before the profile early-return below so a backup without
                    // profiles still restores its state. Never fatal: the configuration the owner
                    // came for is already durable, so a failure here must not roll it back.
                    if (restorableState.isNotEmpty()) {
                        restoredStateRows = runCatching {
                            applyState(restorableState)
                        }.getOrDefault(0)
                    }
                    ownerChoicesRestored = runCatching { config.applyOwnerChoices(ownerChoices) }.getOrDefault(0)
                    issuesRestored = if (restorableIssues.isEmpty()) 0
                        else runCatching { dashboardIssues.restore(restorableIssues) }.getOrDefault(0)
                    // A calibration this install already holds under the same key is kept; the
                    // archive's copy only fills a gap. Never fatal, like the state write above.
                    restoredCalibrations = if (restorableCalibrations.isEmpty()) 0
                        else runCatching { calibrations.restore(restorableCalibrations) }.getOrDefault(0)
                    if (restoredStateRows > 0 || restoredCalibrations > 0 || ownerChoicesRestored > 0 || issuesRestored > 0) {
                        // Never fatal, exactly like the writes above: the restore is
                        // already durable, and a live re-read failing must not undo it.
                        stateRefreshFailed = runCatching { onDurableStateRestored() }.isFailure
                    }
                    val payload = profilePayload?.payload ?: return@afterApply
                    profileResult = requireNotNull(profileAdmin).restoreBackup(
                        payload,
                        requireNotNull(profilePlan).expectedCatalogRevision,
                    )
                    if (profileResult?.outcome != ProfileBackupRestoreOutcome.SUCCEEDED) {
                        throw ProfileApplyFailed()
                    }
                    if (profileResult?.restartRequired == true) {
                        rejectFailedProfileRestart(
                            restartAllowed = true,
                            requestRestart = onProfileRestart,
                            abortPendingRestart = onProfileRestartAbort,
                        )?.let { throw ProfileApplyFailed(it) }
                    }
                },
            )
            RestoreOperationResult(
                // An archive that marked itself incomplete restored everything it held, and still
                // must not report the plain success of one that held the panel's state.
                message = when {
                    stateComponent()?.status == InstallProgress.Outcome.PARTIAL || policySkipIsPartial ->
                        "Restore partially completed; some panel state was not restored"
                    admittedPlan.skipped.isNotEmpty() -> "Restore partially completed; unavailable settings: " +
                        admittedPlan.skipped.joinToString(", ") { requireNotNull(SettingsRegistry.spec(it)).label }
                    restoredStateRows > 0 ->
                        "Restore completed, including $restoredStateRows panel state values"
                    stateUnavailable -> "Restore completed; this backup carried no panel state"
                    else -> "Restore completed"
                } + wakeWordRestoreNote(wakeWordOutcome),
                structured = InstallProgress.OperationResult(
                    status = if (admittedPlan.skipped.isNotEmpty() || policySkipIsPartial ||
                        stateComponent()?.status == InstallProgress.Outcome.PARTIAL) InstallProgress.Outcome.PARTIAL
                        else restoreOverallStatus(wakeWordOutcome),
                    config = if (admittedPlan.skipped.isEmpty()) succeededComponent(configItems)
                        else InstallProgress.ComponentResult(
                            InstallProgress.Outcome.PARTIAL,
                            configItems,
                            detail = admittedPlan.warnings.joinToString("; "),
                        ),
                    profiles = profileComponent(profileResult),
                    companion = companionResult?.component
                        ?: skippedComponent("not present"),
                    wakeWords = wakeWordComponent,
                    state = stateComponent(),
                ),
                presentation = when {
                    admittedPlan.skipped.isNotEmpty() ||
                        policySkipIsPartial || stateComponent()?.status == InstallProgress.Outcome.PARTIAL ||
                        restoreOverallStatus(wakeWordOutcome) != InstallProgress.Outcome.SUCCEEDED ->
                        InstallPresentation("restore-partial")
                    restoredStateRows > 0 -> InstallPresentation(
                        "restore-completed-with-state",
                        mapOf("count" to restoredStateRows.toString()),
                    )
                    stateUnavailable -> InstallPresentation("restore-completed-state-unavailable")
                    else -> InstallPresentation("restore-completed")
                },
            )
        }.getOrElse { error ->
            Log.w(TAG, "restore failed", error)
            val profileRestartRejection = (error as? ProfileApplyFailed)?.restartRejection
            val rollback = if (configCommitted) {
                val expected = appliedRevisionHash
                val restored = expected != null && runCatching {
                    rollbackConfig(rollbackValues, beforeEntityState, expected, progress)
                }
                    .getOrDefault(false)
                if (restored) InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLED_BACK)
                else InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLBACK_FAILED)
            } else null
            val partial = companionResult?.ok == true ||
                (wakeWordComponent?.items ?: 0) > 0 ||
                (stateComponent()?.items ?: 0) > 0 ||
                companionResult?.component?.status == InstallProgress.Outcome.PARTIAL ||
                profileResult?.outcome == ProfileBackupRestoreOutcome.SUCCEEDED ||
                profileResult?.outcome == ProfileBackupRestoreOutcome.PARTIAL ||
                rollback?.status == InstallProgress.Outcome.ROLLBACK_FAILED
            RestoreOperationResult(
                message = if (partial) "Restore partially completed" else "Restore failed",
                structured = InstallProgress.OperationResult(
                    status = if (partial) InstallProgress.Outcome.PARTIAL else InstallProgress.Outcome.FAILED,
                    config = when {
                        rollback?.status == InstallProgress.Outcome.ROLLED_BACK ->
                            InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLED_BACK, configItems)
                        rollback?.status == InstallProgress.Outcome.ROLLBACK_FAILED ->
                            InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLBACK_FAILED, configItems)
                        configCommitted -> InstallProgress.ComponentResult(InstallProgress.Outcome.PARTIAL, configItems)
                        else -> InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0)
                    },
                    profiles = profileRestartRejection?.let {
                        profileRestartFailureComponent(it, profileResult?.imported?.size ?: 0)
                    } ?: profileComponent(profileResult, profilePlan != null),
                    companion = companionResult?.component
                        ?: if (companionPlan == null) skippedComponent("not present")
                        else InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0),
                    rollback = rollback,
                    wakeWords = wakeWordComponent,
                    state = stateComponent(),
                ),
                presentation = InstallPresentation(if (partial) "restore-partial" else "restore-failed"),
            )
        }
        Log.i(TAG, "restore: ${operation.message}")
        InstallProgress.finish(
            progress,
            operation.message,
            operation.structured,
            operation.presentation,
        )
    }

    private data class RestoreOperationResult(
        val message: String,
        val structured: InstallProgress.OperationResult,
        val presentation: InstallPresentation,
    )

    private class CompanionApplyFailed : IllegalStateException("Companion restore failed")
    private class ProfileApplyFailed(
        val restartRejection: ProfileRestartRejection? = null,
    ) : IllegalStateException(restartRejection?.message ?: "Profile catalog restore failed")

    private fun succeededComponent(items: Int) = InstallProgress.ComponentResult(
        InstallProgress.Outcome.SUCCEEDED,
        items,
    )

    private fun skippedComponent(detail: String) = InstallProgress.ComponentResult(
        InstallProgress.Outcome.SKIPPED,
        detail = detail,
        presentation = InstallPresentation("component-not-present"),
    )

    private fun profileComponent(
        result: ProfileBackupRestoreResult?,
        present: Boolean = result != null,
    ): InstallProgress.ComponentResult = when {
        result == null && !present -> skippedComponent("not present")
        result == null -> InstallProgress.ComponentResult(InstallProgress.Outcome.FAILED, 0)
        else -> InstallProgress.ComponentResult(
            status = when (result.outcome) {
                ProfileBackupRestoreOutcome.SUCCEEDED -> InstallProgress.Outcome.SUCCEEDED
                ProfileBackupRestoreOutcome.PARTIAL -> InstallProgress.Outcome.PARTIAL
                ProfileBackupRestoreOutcome.REJECTED -> InstallProgress.Outcome.FAILED
            },
            items = result.imported.size,
            detail = result.message,
            presentation = result.presentation?.let {
                InstallPresentation.create(it.code, it.params)
            },
        )
    }

    private fun profileRestartFailureComponent(
        rejection: ProfileRestartRejection,
        imported: Int,
    ) = InstallProgress.ComponentResult(
        status = if (rejection.abortPersisted) {
            InstallProgress.Outcome.ROLLED_BACK
        } else {
            InstallProgress.Outcome.ROLLBACK_FAILED
        },
        items = imported,
        detail = rejection.message,
        presentation = InstallPresentation.create(
            rejection.presentation.code,
            rejection.presentation.params,
        ),
    )

}

private const val TAG = "ha-paneld/http"
