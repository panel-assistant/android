package io.panelassistant.android.http

import android.util.Log
import android.content.Context
import io.panelassistant.android.Config
import io.panelassistant.android.DashboardEntityBackupState
import io.panelassistant.android.HaAuthOwner
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.NativeLocale
import io.panelassistant.android.dashboardEntityLearningEnabled
import io.panelassistant.android.haAuthSnapshot
import io.panelassistant.android.isHaOAuthAttemptCurrent
import io.panelassistant.android.normalizeDashboardEntityPath
import io.panelassistant.android.stableOwner
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.ConfigBundle
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.backup.CompanionRestore
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.device.profile.ProfileAdmin
import io.panelassistant.android.dashboard.EntityLearningManager
import io.panelassistant.android.security.SensitiveOperation
import io.panelassistant.android.util.InstallProgress
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface ApplyAcceptedResult {
    data class Applied(val appliedKeys: List<String>, val pendingKeys: List<String>) : ApplyAcceptedResult
    data object Stale : ApplyAcceptedResult
    data object CommitFailed : ApplyAcceptedResult
    data class CompatibilityRefused(val message: String) : ApplyAcceptedResult
}


/** The ordinary phase is durable; only these live values acknowledged completion before refusal. */
internal class ConfigLiveApplyRefused(
    val failedKey: String,
    val appliedKeys: List<String>,
    val pendingKeys: List<String> = emptyList(),
) : IllegalStateException("$failedKey live apply refused")

/** Ordered accepted-config commit and renderer effects shared by import, OAuth and restore. */
internal class AcceptedConfigTransaction(
    private val config: Config,
    private val revisions: RevisionStore,
    private val rendererPreparation: RendererPreparationCoordinator,
    private val system: SystemController,
    private val values: ConfigValueProjection,
    private val applySetting: (String, String) -> LiveSettingRequestOutcome,
    private val onEntityTargetChanged: () -> Unit,
    private val setEntityLearningEnabled: (Boolean) -> Boolean,
    private val effectiveDashboardIsBuiltin: () -> Boolean,
    private val requestTameReconcileAfterCommit: () -> Boolean,
    private val snapInvalidate: () -> Unit,
    private val onReconfigure: (Set<String>) -> Unit,
) {
    /**
     * Construct direct admission with this transaction's existing commit/effect collaborators.
     * The direct handler retains its own admission sequence and the shared direct-mutation lock;
     * it does not delegate its persistence transaction to [applyAccepted].
     */
    fun directPost(
        directConfigMutationLock: Any,
        autoSleepHttpApi: AutoSleepHttpApi,
        autoBrightnessHttpApi: AutoBrightnessHttpApi,
        onboarding: DirectConfigOnboarding,
        capabilities: () -> Capabilities,
        authorizeSensitive: suspend (OpCall, SensitiveOperation, String, String) -> Boolean,
        rejectHardenedNetworkAdb: suspend (OpCall, String?) -> Boolean,
        onHaAreaCommitted: () -> Unit,
    ): DirectConfigPost = DirectConfigPost(
        config = config,
        revisions = revisions,
        directConfigMutationLock = directConfigMutationLock,
        rendererPreparation = rendererPreparation,
        autoSleepHttpApi = autoSleepHttpApi,
        autoBrightnessHttpApi = autoBrightnessHttpApi,
        onboarding = onboarding,
        capabilities = capabilities,
        directMutationValues = { values.directMutationValues() },
        revisionValues = { values.revisionValues() },
        authorizeSensitive = authorizeSensitive,
        rejectHardenedNetworkAdb = rejectHardenedNetworkAdb,
        applySetting = applySetting,
        applyRendererEffects = ::applyRendererEffects,
        onEntityTargetChanged = onEntityTargetChanged,
        setEntityLearningEnabled = setEntityLearningEnabled,
        requestTameReconcileAfterCommit = requestTameReconcileAfterCommit,
        onHaAreaCommitted = onHaAreaCommitted,
        snapInvalidate = snapInvalidate,
        onReconfigure = onReconfigure,
        configJson = { status, applied, pending, rejected, message ->
            values.configJson(status, applied, pending, rejected, message)
        },
    )

    /** Apply a validated value set in two ordered phases: snapshot current → atomically commit ordinary
     *  preference fields → run live controller/hardware persistence and side-effects → reconfigure.
     *  External state cannot be rolled back and only starts after a successful preference commit.
     *  Returns false without starting side-effects when the preference commit fails. */

    suspend fun applyAccepted(
        accepted: Map<String, String>,
        expectedConfig: String? = null,
        expectedRevision: String? = null,
        expectedHaAuthOwner: HaAuthOwner? = null,
        expectedHaOAuthEpoch: Long? = null,
        entityState: DashboardEntityBackupState? = null,
        existingOperationTicket: InstallProgress.Ticket? = null,
        onDurableRevision: (String) -> Unit = {},
        afterCommitBeforeRenderer: (RendererConfigEffects, String) -> Unit = { _, _ -> },
        afterApply: () -> Unit = {},
        /** Checked once every lock is held, immediately before the commit: false makes the whole call [ApplyAcceptedResult.Stale]. */
        admit: () -> Boolean = { true },
    ): ApplyAcceptedResult = withContext(Dispatchers.IO) {
        if (existingOperationTicket != null && !InstallProgress.owns(existingOperationTicket)) {
            return@withContext ApplyAcceptedResult.CompatibilityRefused(
                "the owning panel operation is no longer active",
            )
        }
        val configMutationTicket = if (existingOperationTicket == null) {
            InstallProgress.startConfigMutation()
                ?: return@withContext ApplyAcceptedResult.CompatibilityRefused(
                    "another panel operation owns configuration admission",
                )
        } else null
        try {
        val result = rendererPreparation.transaction {
            var earlyResult: ApplyAcceptedResult? = null
            var committed: AcceptedCommit? = null
            config.synchronizedTransaction {
                if (!admit()) {
                    earlyResult = ApplyAcceptedResult.Stale
                    return@synchronizedTransaction
                }
                if (expectedConfig != null &&
                    io.panelassistant.android.config.ConfigHash.of(configConcurrencyValues(values.currentValues())) != expectedConfig
                ) {
                    earlyResult = ApplyAcceptedResult.Stale
                    return@synchronizedTransaction
                }
                if (expectedRevision != null &&
                    io.panelassistant.android.config.ConfigHash.of(values.revisionValues()) != expectedRevision
                ) {
                    earlyResult = ApplyAcceptedResult.Stale
                    return@synchronizedTransaction
                }
                if (expectedHaAuthOwner != null && config.haAuthSnapshot().stableOwner() != expectedHaAuthOwner) {
                    earlyResult = ApplyAcceptedResult.Stale
                    return@synchronizedTransaction
                }
                if (expectedHaOAuthEpoch != null && !config.isHaOAuthAttemptCurrent(expectedHaOAuthEpoch)) {
                    earlyResult = ApplyAcceptedResult.Stale
                    return@synchronizedTransaction
                }
                SettingsRegistry.automaticBrightnessBoundsError(
                    accepted, config.autoBrightnessMinimumPercent, config.autoBrightnessMaximumPercent,
                )?.let { reason ->
                    earlyResult = ApplyAcceptedResult.CompatibilityRefused(reason)
                    return@synchronizedTransaction
                }
                val previous = ConfigBundle.fromValues(
                    values.revisionValues(), kind = ConfigBundle.KIND_REVISION,
                    exportedAt = System.currentTimeMillis().toString(), exportedBy = config.panelId,
                )
                val editor = config.editor()
                val live = ArrayList<Pair<String, String>>()
                for ((key, value) in accepted) {
                    when {
                        key == "panel_id" -> config.stagePanelId(editor, value)
                        SettingsRegistry.isPersistedExposure(key) -> editor.putBoolean(key, SettingValue.parseBool(value) == true)
                        // EntityLearningManager owns enable/disable transition semantics and commits this
                        // preference after the ordinary bundle transaction succeeds.
                        key == "dashboard_entity_learning" -> Unit
                        key in liveKeys -> {
                            if (key == "auto_brightness_minimum_percent" || key == "auto_brightness_maximum_percent") {
                                config.stage(editor, requireNotNull(SettingsRegistry.spec(key)), value)
                            }
                            live.add(key to value)
                        }
                        else -> SettingsRegistry.spec(key)?.let { spec ->
                            config.stage(editor, spec, value)
                        }
                    }
                }
                config.stageImportDependencies(editor, accepted)
                entityState?.let { config.stageDashboardEntityBackupState(editor, it) }
                if (!config.commit(
                        editor,
                        afterCommit = {
                            if ("tame_vendor_packages" in accepted) requestTameReconcileAfterCommit()
                            // A changed interface language reaches the panel's own screens at once, as a Configure save does.
                            accepted["ui_language"]?.takeIf { it != previous.values["ui_language"] }?.let(NativeLocale::apply)
                        },
                    )
                ) {
                    earlyResult = ApplyAcceptedResult.CommitFailed
                    return@synchronizedTransaction
                }
                committed = AcceptedCommit(
                    previous = previous,
                    live = live,
                    effects = RendererConfigEffects.between(previous.values, accepted),
                )
            }
            earlyResult?.let { return@transaction it }
            val phase = requireNotNull(committed)
            revisions.snapshot(phase.previous)
            var rendererFailure: Throwable? = null
            val appliedKeys = accepted.keys.filterTo(ArrayList()) {
                it !in liveKeys && it != "dashboard_entity_learning"
            }
            val pendingKeys = ArrayList<String>()
            runCatching {
                // The base transaction deliberately excludes live keys. Publish every actually durable
                // generation to rollback ownership, then converge all live values before any external
                // Companion/profile work can fail.
                onDurableRevision(io.panelassistant.android.config.ConfigHash.of(values.revisionValues()))
                val previousHome = phase.previous.values["home_dashboard"].orEmpty()
                phase.live.firstOrNull { it.first == "home_dashboard" }?.let { (_, value) ->
                    val applied = applySetting("home_dashboard", value)
                    onDurableRevision(io.panelassistant.android.config.ConfigHash.of(values.revisionValues()))
                    if (!applied.legacyAcknowledged) throw ConfigLiveApplyRefused("home_dashboard", appliedKeys.toList(), pendingKeys.toList())
                    if (applied == LiveSettingRequestOutcome.APPLIED) appliedKeys += "home_dashboard"
                    else pendingKeys += "home_dashboard"
                }
                for ((k, v) in phase.live) if (k != "home_dashboard") {
                    val applied = applySetting(k, v)
                    onDurableRevision(io.panelassistant.android.config.ConfigHash.of(values.revisionValues()))
                    if (!applied.legacyAcknowledged) throw ConfigLiveApplyRefused(k, appliedKeys.toList(), pendingKeys.toList())
                    if (applied == LiveSettingRequestOutcome.APPLIED) appliedKeys += k
                    else pendingKeys += k
                }
                val homeChanged = normalizeDashboardEntityPath(config.homeDashboard) !=
                    normalizeDashboardEntityPath(previousHome)
                val credentialsChanged = RendererConfigEffects.credentialsChanged(phase.previous.values, accepted)
                if (homeChanged || credentialsChanged) onEntityTargetChanged()
                accepted["dashboard_entity_learning"]?.let { raw ->
                    val enabled = SettingValue.parseBool(raw)
                        ?: error("validated automatic entity-filter value became invalid")
                    if (enabled != config.dashboardEntityLearningEnabled && !setEntityLearningEnabled(enabled)) {
                        error("entity-learning transition failed")
                    }
                    onDurableRevision(io.panelassistant.android.config.ConfigHash.of(values.revisionValues()))
                    appliedKeys += "dashboard_entity_learning"
                }
                // Ordinary values and acknowledged live work are now durable; queued live work
                // remains pending in the receipt. This same fence remains exact if either the
                // Companion/renderer callback or the later profile callback fails.
                afterCommitBeforeRenderer(
                    phase.effects,
                    io.panelassistant.android.config.ConfigHash.of(values.revisionValues()),
                )
                applyRendererEffects(phase.effects)
            }.onFailure { rendererFailure = it }
            snapInvalidate()
            onReconfigure(accepted.keys)
            rendererFailure?.let { throw it }
            afterApply()
            ApplyAcceptedResult.Applied(appliedKeys.toList(), pendingKeys.toList())
        }
        result
        } finally {
            configMutationTicket?.let(InstallProgress::finishConfigMutation)
        }
    }

    private data class AcceptedCommit(
        val previous: ConfigBundle,
        val live: List<Pair<String, String>>,
        val effects: RendererConfigEffects,
    )

    /** Apply renderer changes only after their preferences commit. A dashboard switch dominates all
     *  reloads; otherwise a reload dominates a foreground relaunch, so one request schedules at most
     *  one renderer operation. */
    fun applyRendererEffects(effects: RendererConfigEffects) {
        effects.darkMode?.let { dark ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    if (dark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
                )
            }
        }
        when {
            effects.dashboardChanged -> {
                val result = rendererPreparation.launchConfigured(
                    ensureHome = { pkg, ready ->
                        system.applyLauncherHomePolicy(config.launcherPackage, pkg, ready)
                    },
                    launchHome = { pkg -> system.launchHome(pkg) },
                )
                requireRendererResult(result)
                Log.i(TAG, "renderer switch completed (preparation=$result)")
            }
            effects.reloadBuiltin && effectiveDashboardIsBuiltin() -> {
                val result = rendererPreparation.prepareIfNeeded()
                requireRendererResult(result)
                system.reloadDashboard(
                    SystemController.BUILTIN_DASHBOARD,
                    reason = "applying your settings",
                )
            }
            effects.relaunchBuiltin && effectiveDashboardIsBuiltin() -> {
                val result = rendererPreparation.prepareIfNeeded()
                requireRendererResult(result)
                // A settings change is a deliberate act, so it lifts a crash latch as an explicit reload does.
                BuiltinDashboard.clearRendererLatch()
                system.launchHome(SystemController.BUILTIN_DASHBOARD)
            }
        }
    }


    /** A Companion-only restore can make an interrupted built-in switch repairable without changing a
     * renderer setting. When an ordinary renderer effect exists, that effect performs preparation. */
    private fun reconcileAfterCompanionRestore(effects: RendererConfigEffects?) {
        if (effects != null && (effects.dashboardChanged || effects.reloadBuiltin || effects.relaunchBuiltin)) return
        val result = rendererPreparation.reconcileStartup(
            ensureHome = { pkg, ready ->
                system.applyLauncherHomePolicy(config.launcherPackage, pkg, ready)
            },
            launchHome = { pkg -> system.launchHome(pkg) },
        )
        requireRendererResult(result)
    }

    companion object {
        private const val TAG = "ha-paneld/http"
        private val liveKeys = SettingsRegistry.liveApplyKeys()

        /** Restore uses the admitted ticket and latest durable revision for both commit and rollback.
         * Resolve the transaction only when executing, never while admitting or previewing a request. */
        fun restoreExecutor(
            appContext: Context,
            config: Config,
            transaction: () -> AcceptedConfigTransaction,
            capabilities: () -> Capabilities,
            restoreCompanion: (CompanionRestore.Plan) -> CompanionApplyResult,
            profileAdmin: ProfileAdmin?,
            onProfileRestart: () -> Boolean,
            onProfileRestartAbort: (String) -> Boolean,
            onDurableStateRestored: () -> Unit,
            onWakeWordsChanged: () -> Unit,
            calibrations: io.panelassistant.android.persistence.ProximityCalibrationCustody =
                io.panelassistant.android.persistence.CatalogProximityCalibrationCustody(appContext),
            dashboardIssues: io.panelassistant.android.persistence.DashboardIssueCustody =
                io.panelassistant.android.persistence.CatalogDashboardIssueCustody(appContext),
        ): RestoreExecutor = RestoreExecutor(
            appContext = appContext,
            config = config,
            currentValues = { transaction().values.currentValues() },
            capabilities = capabilities,
            revisionValues = { values, state -> transaction().values.revisionValues(values, state) },
            commitConfig = RestoreConfigCommit { accepted, entityState, expectedRevision,
                existingOperationTicket, onDurableRevision, afterCommitBeforeRenderer, afterApply ->
                val result = transaction().applyAccepted(
                    accepted,
                    expectedRevision = expectedRevision,
                    entityState = entityState,
                    existingOperationTicket = existingOperationTicket,
                    onDurableRevision = onDurableRevision,
                    afterCommitBeforeRenderer = { effects, appliedHash ->
                        afterCommitBeforeRenderer(effects, accepted.size, appliedHash)
                    },
                    afterApply = afterApply,
                )
                check(result is ApplyAcceptedResult.Applied) {
                    if (result is ApplyAcceptedResult.CompatibilityRefused) {
                        "configuration refused: ${result.message}"
                    } else "configuration commit failed"
                }
                accepted.size
            },
            rollbackConfig = { before, entityState, expectedRevision, ticket ->
                transaction().applyAccepted(
                    before,
                    expectedRevision = expectedRevision,
                    entityState = entityState,
                    existingOperationTicket = ticket,
                ) is ApplyAcceptedResult.Applied
            },
            restoreCompanion = restoreCompanion,
            reconcileAfterCompanionRestore = { transaction().reconcileAfterCompanionRestore(it) },
            profileAdmin = profileAdmin,
            onProfileRestart = onProfileRestart,
            onProfileRestartAbort = onProfileRestartAbort,
            onDurableStateRestored = onDurableStateRestored,
            onWakeWordsChanged = onWakeWordsChanged,
            calibrations = calibrations,
            dashboardIssues = dashboardIssues,
        )
    }
}

internal fun requireRendererResult(result: RendererPreparationCoordinator.Result) {
    check(result != RendererPreparationCoordinator.Result.PERSIST_FAILED) {
        "built-in renderer preparation did not commit"
    }
    check(result != RendererPreparationCoordinator.Result.CLOSED) {
        "renderer lifecycle is stopping"
    }
}
