package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.DashboardEntityBackupState
import io.github.maxlyth.hapaneld.backup.CompanionRestore
import io.github.maxlyth.hapaneld.device.profile.ProfileAdmin
import io.github.maxlyth.hapaneld.device.profile.ProfileBackupRestoreOutcome
import io.github.maxlyth.hapaneld.device.profile.ProfileBackupRestorePlan
import io.github.maxlyth.hapaneld.device.profile.ProfileBackupRestoreResult
import io.github.maxlyth.hapaneld.persistence.AppState
import io.github.maxlyth.hapaneld.persistence.ConfigVault
import io.github.maxlyth.hapaneld.migration.RestoreAttempt
import io.github.maxlyth.hapaneld.util.InstallPresentation
import io.github.maxlyth.hapaneld.util.InstallProgress

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
) {
    suspend fun execute(
        configPlan: RestoreConfigPlan,
        entityState: DashboardEntityBackupState?,
        profilePayload: io.github.maxlyth.hapaneld.device.profile.ProfileBackupDecodeResult?,
        profilePlan: ProfileBackupRestorePlan?,
        companionPlan: CompanionRestore.Plan?,
        rawPreferences: Map<String, Map<String, String>>,
        restorableState: List<ConfigVault.StateRow>,
        stateUnavailable: Boolean,
        migrationRestore: Boolean,
        restoreWakeWords: List<io.github.maxlyth.hapaneld.assist.wakeword.WakeWordCatalog.ImportedFiles>,
        wakeWordCatalog: io.github.maxlyth.hapaneld.assist.wakeword.WakeWordCatalog?,
        progress: InstallProgress.Ticket,
        restoreAttempt: RestoreAttempt,
    ) {
        val before = currentValues()
        val beforeEntityState = config.dashboardEntityBackupState()
        val beforeRevisionHash = io.github.maxlyth.hapaneld.config.ConfigHash.of(
            revisionValues(before, beforeEntityState),
        )
        var configCommitted = false
        var configItems = 0
        var restoredStateRows = 0
        var companionResult: CompanionApplyResult? = null
        var profileResult: ProfileBackupRestoreResult? = null
        var appliedRevisionHash: String? = null
        // Before the configuration, so a restored `voice_wake_words` selecting an imported id finds
        // its model when the listener rearms. Additive and never fatal: a model the engine refuses is
        // reported in the result, and a later configuration rollback leaves these imports in place,
        // exactly as a user's own import would stay.
        val wakeWordOutcome = if (restoreWakeWords.isEmpty()) null else runCatching {
            io.github.maxlyth.hapaneld.backup.WakeWordBackup.restore(requireNotNull(wakeWordCatalog), restoreWakeWords)
        }.getOrElse { failure ->
            io.github.maxlyth.hapaneld.backup.WakeWordBackup.Outcome(
                emptyList(),
                restoreWakeWords.map { it.id to (failure.message ?: "could not be saved") },
            )
        }
        if (wakeWordOutcome?.restored?.isNotEmpty() == true) runCatching { onWakeWordsChanged() }
        val wakeWordComponent = wakeWordOutcome?.let(::wakeWordRestoreComponent)
        val operation = runCatching {
            configItems = commitConfig.apply(
                configPlan.values,
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
                    val rawPreferencesApplied = rawPreferences.all { (store, values) ->
                        runCatching {
                            val editor = appContext
                                .getSharedPreferences(store, android.content.Context.MODE_PRIVATE).edit()
                            values.forEach { (key, value) -> editor.putString(key, value) }
                            editor.commit()
                        }.getOrDefault(false)
                    }
                    if (restorableState.isNotEmpty()) {
                        restoredStateRows = runCatching {
                            AppState.applyRestoredRows(appContext, restorableState)
                        }.getOrDefault(0)
                        if (restoredStateRows > 0) {
                            // Never fatal, exactly like the write above: the restore is
                            // already durable, and a live re-read failing must not undo it.
                            runCatching { onDurableStateRestored() }
                        }
                    }
                    // An ordinary restore forgives a state write that failed, because its archive
                    // survives it. A migration's receipt is about to become the only copy of a
                    // package that is then removed, so there every carried value must have landed.
                    if (migrationRestore &&
                        !migrationRestoreComplete(rawPreferencesApplied, restorableState.size, restoredStateRows)
                    ) {
                        throw IllegalStateException("migration restore did not apply every carried value")
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
                    restoredStateRows > 0 ->
                        "Restore completed, including $restoredStateRows panel state values"
                    stateUnavailable -> "Restore completed; this backup carried no panel state"
                    else -> "Restore completed"
                } + wakeWordRestoreNote(wakeWordOutcome),
                structured = InstallProgress.OperationResult(
                    status = restoreOverallStatus(wakeWordOutcome),
                    config = succeededComponent(configItems),
                    profiles = profileComponent(profileResult),
                    companion = companionResult?.component
                        ?: skippedComponent("not present"),
                    wakeWords = wakeWordComponent,
                ),
                presentation = when {
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
                    rollbackConfig(before, beforeEntityState, expected, progress)
                }
                    .getOrDefault(false)
                if (restored) InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLED_BACK)
                else InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLBACK_FAILED)
            } else null
            val partial = companionResult?.ok == true ||
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
                ),
                presentation = InstallPresentation(if (partial) "restore-partial" else "restore-failed"),
            )
        }
        Log.i(TAG, "restore: ${operation.message}")
        // Only a whole success advances the migration; a partial or failed restore leaves the
        // step open, and the successor restores the same receipt again. It is told either way,
        // so a failure is retried at once rather than after waiting out a timeout.
        restoreAttempt.finished(operation.structured.status == InstallProgress.Outcome.SUCCEEDED)
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
