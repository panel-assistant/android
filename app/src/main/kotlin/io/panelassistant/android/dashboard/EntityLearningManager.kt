package io.panelassistant.android.dashboard

import android.content.Context
import android.os.CancellationSignal
import android.util.Log
import io.panelassistant.android.Config
import io.panelassistant.android.adoptDashboardEntityInstance
import io.panelassistant.android.clearDashboardEntityDefaultResolverRebootstrapPending
import io.panelassistant.android.commitDashboardEntityEvidenceReset
import io.panelassistant.android.commitDashboardEntityLearningMode
import io.panelassistant.android.commitDashboardEntityPromotionPolicy
import io.panelassistant.android.commitDashboardEntitySubscription
import io.panelassistant.android.dashboardEntityAutoRuntime
import io.panelassistant.android.dashboardEntityAutoStatic
import io.panelassistant.android.dashboardEntityDashboardPath
import io.panelassistant.android.dashboardEntityDefaultResolverRebootstrapPending
import io.panelassistant.android.dashboardEntityFilterEnabled
import io.panelassistant.android.dashboardEntityFilterIds
import io.panelassistant.android.dashboardEntityInitialActivationPending
import io.panelassistant.android.dashboardEntityInstanceKey
import io.panelassistant.android.dashboardEntityLearningApplied
import io.panelassistant.android.dashboardEntityLearningEnabled
import io.panelassistant.android.dashboardEntityOverrides
import io.panelassistant.android.dashboardEntityScopeNamespaceMigrationRequired
import io.panelassistant.android.dashboardEntityScopePath
import io.panelassistant.android.DashboardEntityDefaultResolverMigration
import io.panelassistant.android.HaConnectionRoute
import io.panelassistant.android.HaConnectionRoutes
import io.panelassistant.android.HaCredentialManager
import io.panelassistant.android.dashboardEntityTargetKey
import io.panelassistant.android.haAuthSnapshot
import io.panelassistant.android.haUrl
import io.panelassistant.android.markDashboardEntityScopeNamespaceMigrated
import io.panelassistant.android.migrateDashboardEntityDefaultResolver
import io.panelassistant.android.prepareDashboardEntityInstance
import io.panelassistant.android.setDashboardEntityOverrides
import io.panelassistant.android.stableOwner
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCostRegistry
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.storage.StorageHealthObservation
import io.panelassistant.android.storage.VacuumOutcome
import io.panelassistant.android.storage.WalCheckpointOutcome
import io.panelassistant.android.util.BoundedStreams
import io.panelassistant.android.util.ByteLimitExceeded
import io.panelassistant.android.util.HaWebSocketClients
import io.panelassistant.android.util.AddressFamilyPolicy
import io.panelassistant.android.util.closeBody
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/** Owns catalog synchronization, automatic set promotion and the HTTP/UI query surface. */
class EntityLearningManager(
    context: Context,
    private val config: Config,
    private val scope: CoroutineScope,
    private val resolveInstanceUuid: suspend (candidateUrls: Collection<String>) -> String? = { null },
    private val expectedEntityTotal: () -> Int? = { null },
    private val onFilterChanged: () -> Unit,
) {
    private val store = EntityCatalogStore(context.applicationContext)
    private val syncMutex = Mutex()
    // Process-owned rather than service-instance-owned: DashboardActivity can survive a same-process
    // service replacement, so its renderer and the successor manager's scanner must retain one answer.
    private val homeDashboardAuthority = EntityLearningRuntime.homeDashboardAuthority
    private val effectGeneration = AtomicLong(0)
    private val telemetryWriteBarrier = EntityTelemetryWriteBarrier(effectGeneration::get)
    @Volatile private var syncJob: Job? = null
    /** The held renderer's single-flight dashboard change probe; never runs beside a scan. */
    @Volatile private var probeJob: Job? = null

    /** Set when [syncNow] found a run already active; consumed on that run's NORMAL completion. */
    private var syncRerunReason: String? = null
    @Volatile private var promotionJob: Job? = null
    @Volatile private var bootstrapBlockingIssues = 0
    @Volatile private var resetBootstrapPending = false
    @Volatile private var dynamicExpressionsJson = "[]"
    private var promotionWindowStart = 0L
    private var promotionsInWindow = 0
    private val telemetryStoreGate = EntityTelemetryStoreGate()
    // Entity access/metric telemetry has one bounded owner and drain worker.
    private val telemetryPipeline = EntityTelemetryPipeline(
        scope = scope,
        accumulator = EntityTelemetryAccumulator(MAX_TELEMETRY_IDS),
        hasPending = { it.size() > 0 },
        drain = ::drainTelemetry,
    )
    private val telemetryPending: EntityTelemetryAccumulator get() = telemetryPipeline.accumulator
    @Volatile private var performanceSummaryAt = 0L
    @Volatile private var performanceSummary = "[]"
    @Volatile private var performanceSummaryTarget = ""
    @Volatile private var initialized = false
    @Volatile private var closed = false

    // Live catalog-scan progress, for the setup wizard's entity-filter question. Deliberately not persisted
    // and not part of the catalog snapshot: it is a fact about a scan in flight, worthless a second later.
    @Volatile private var scanCounting = false
    @Volatile private var scanCountedRows = -1
    @Volatile private var scanGeneration = 0L

    /** Entities counted so far by a scan in flight, or null when no scan is running. */
    fun scanProgress(): Int? = if (scanCounting && scanGeneration == effectGeneration.get()) scanCountedRows else null

    /** Read the live session each time: a first hello may arrive after this scan started. */
    fun scanExpectedTotal(): Int? = runCatching { expectedEntityTotal() }.getOrNull()?.takeIf { it > 0 }
        ?: catalogCount()

    /** Actual rows from the completed state response, excluding older catalogue entries retained for recovery. */
    fun completedScanCount(): Int? = runCatching {
        scanCountedRows.takeIf { it >= 0 && !scanCounting && scanGeneration == effectGeneration.get() }
    }.getOrNull()

    /**
     * Entities Home Assistant reported in the last completed scan, or null before there has been one.
     * Distinct from [scanProgress]: this is a settled total the setup wizard may recommend against, while a
     * scan in flight is a partial figure it must label as still counting.
     */
    fun catalogCount(): Int? = runCatching {
        store.snapshot(instance(), dashboardPath()).catalogCount.takeIf { it > 0 }
    }.getOrNull()
    private var periodicJob: Job? = null

    fun start() {
        if (!shouldInitializeEntityLearningOnStart(config.dashboardEntityLearningEnabled)) return
        ensureInitialized()
    }

    /** Lazily open and inspect the rebuildable catalog only when learning or an admin surface needs it. */
    private fun ensureInitialized(): Boolean = synchronized(this) {
        check(!closed) { "entity-learning manager is closed" }
        if (initialized) {
            ensurePeriodicSyncLocked()
            return@synchronized false
        }
        // Before anything reads the catalogue under the rooted key, collapse rows an older build wrote
        // under the configured ROUTE. Skipping this would silently abandon that install's runtime
        // observations and ignored issues, which no rescan can reproduce.
        applyScopeNamespaceMigration()
        prepareCurrentTarget()
        val resolverMigration = applyDefaultResolverMigration()
        refreshTargetDiagnostics()
        initialized = true
        ensurePeriodicSyncLocked()
        val snapshot = store.snapshot(instance(), dashboardPath())
        if (shouldSyncEntityLearningOnStartup(
                config.dashboardEntityLearningEnabled,
                snapshot.lastSyncAt,
                resolverMigration,
                resetBootstrapPending,
                snapshot.analyzerPolicyVersion < DashboardConfigurationLint.ANALYZER_POLICY_VERSION,
                autoScopeUnverifiedThisProcess =
                    homeDashboardResolutionMustBeLive(config.homeDashboard.trim()),
            )) {
            val reason = when {
                resolverMigration == DashboardEntityDefaultResolverMigration.REBOOTSTRAP -> "default-resolver-upgrade"
                snapshot.analyzerPolicyVersion < DashboardConfigurationLint.ANALYZER_POLICY_VERSION -> "analyzer-policy-upgrade"
                homeDashboardResolutionMustBeLive(config.homeDashboard.trim()) &&
                    snapshot.lastSyncAt != 0L -> "auto-scope-verification"
                else -> "startup"
            }
            syncNow(reason)
        }
        true
    }

    /** Idempotent, so a failed latch commit costs a repeat rather than a half-migrated catalogue. */
    private fun applyScopeNamespaceMigration() {
        if (!config.dashboardEntityScopeNamespaceMigrationRequired) return
        val collapsed = runCatching { store.migrateRouteKeyedRowsToRoot(::dashboardEntityScopePath) }
            .onFailure { Log.e(TAG, "catalogue scope-namespace migration failed; it will be retried", it) }
            .getOrNull() ?: return
        if (!config.markDashboardEntityScopeNamespaceMigrated()) {
            Log.e(TAG, "catalogue scope-namespace migration ran but its latch did not commit")
            return
        }
        if (collapsed > 0) Log.i(TAG, "collapsed $collapsed route-keyed catalogue target(s) onto their root")
    }

    private fun applyDefaultResolverMigration(): DashboardEntityDefaultResolverMigration {
        val result = config.migrateDashboardEntityDefaultResolver()
        if (result == DashboardEntityDefaultResolverMigration.REBOOTSTRAP ||
            config.dashboardEntityDefaultResolverRebootstrapPending) {
            // Preserve the old ids only as inactive evidence. The next scan must use bootstrap rules,
            // even when the old catalog has a non-zero lastSyncAt or the preserved list is non-empty.
            resetBootstrapPending = true
            onFilterChanged()
        } else if (result == DashboardEntityDefaultResolverMigration.PERSIST_FAILED) {
            Log.e(TAG, "default-dashboard resolver migration did not commit; filter remains fail-closed")
        }
        return result
    }

    private fun refreshTargetDiagnostics() {
        bootstrapBlockingIssues = runCatching {
            store.snapshot(instance(), dashboardPath()).blockingIssueCount
        }.getOrDefault(0)
        dynamicExpressionsJson = runCatching {
            encodeDynamicExpressions(EntityLearningProtocol.scanDashboard(
                store.dashboardConfigJson(instance(), dashboardPath()),
            ).dynamicExpressions)
        }.getOrDefault("[]")
    }

    private fun ensurePeriodicSyncLocked() {
        if (!config.dashboardEntityLearningEnabled || periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (true) {
                delay(HOURLY_CHECK_MS)
                if (!config.dashboardEntityLearningEnabled || BuiltinDashboard.screenAwakeNow) continue
                val s = store.snapshot(instance(), dashboardPath())
                if (!isActive || !config.dashboardEntityLearningEnabled) continue
                when (periodicMaintenanceAction(
                    sinceLastSyncMs = System.currentTimeMillis() - s.lastSyncAt,
                    dailyMs = DAILY_SYNC_MS,
                    heldOnDecision = s.blockingIssueCount > 0 && classifyEntityBootstrapProblem(s.state, s.error) == null,
                )) {
                    PeriodicMaintenanceAction.SYNC -> syncNow("daily")
                    PeriodicMaintenanceAction.PROBE -> probeDashboardChange("daily")
                    PeriodicMaintenanceAction.NONE -> Unit
                }
            }
        }
    }

    private fun cancelPeriodicSyncLocked() {
        periodicJob?.cancel()
        periodicJob = null
    }

    fun close() {
        synchronized(this) {
            closed = true
            cancelPeriodicSyncLocked()
            effectGeneration.incrementAndGet()
            promotionJob?.cancel()
            promotionJob = null
            syncJob = supersedeEntityLearningSync(syncJob) { syncRerunReason = null }
            probeJob?.cancel()
            probeJob = null
        }
        telemetryPipeline.close { pending ->
            val dropped = pending.discard()
            if (dropped > 0) {
                FeatureCosts.registry.recordDropped(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, dropped.toLong())
            }
            FeatureCosts.registry.setBacklog(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, 0)
        }
        // Wait for an admitted bounded entity telemetry write before closing the shared store.
        telemetryStoreGate.withStore { store.close() }
    }

    /** Rebind owner-scoped state before a renderer is relaunched for a new HA URL/dashboard. */
    fun onTargetConfigurationChanged() {
        synchronized(this) {
            if (!initialized && !config.dashboardEntityLearningEnabled) return
            ensureInitialized()
            invalidateEffects(cancelSync = true)
            runCatching { prepareCurrentTarget() }
                .onFailure { Log.w(TAG, "entity-learning target preparation failed: ${it.message}") }
            val resolverMigration = applyDefaultResolverMigration()
            refreshTargetDiagnostics()
            if (config.dashboardEntityLearningEnabled &&
                resolverMigration != DashboardEntityDefaultResolverMigration.PERSIST_FAILED) {
                syncNow(if (resolverMigration == DashboardEntityDefaultResolverMigration.REBOOTSTRAP) {
                    "default-resolver-target-change"
                } else {
                    "target-change"
                })
            }
        }
    }

    fun blockingIssueCount(): Int = bootstrapBlockingIssues

    /** Safe native-screen summary. Never return the stored exception text: it can contain target
     * details, while the renderer hold only needs to distinguish credential repair from retry. */
    fun bootstrapProblem(): EntityBootstrapProblem? = runCatching {
        // Startup initializes on the service scope. The renderer's main thread can observe
        // not-ready here instead of waiting behind a catalogue scan holding this monitor.
        if (!initialized) return@runCatching null
        store.snapshot(instance(), dashboardPath()).let { classifyEntityBootstrapProblem(it.state, it.error) }
    }.getOrNull()

    fun setEnabled(enabled: Boolean): Boolean = withEntityLearningMutationLock(this) {
        val wasEnabled = config.dashboardEntityLearningEnabled
        // A new opt-in clears a stale activation latch. Synchronization may bootstrap an empty
        // installation narrowly, but it never silently replaces a stored manual list. Disable mode
        // and interception together so a failed commit cannot expose a contradictory half-state.
        val committed = config.commitDashboardEntityLearningMode(
            enabled = enabled,
            clearApplied = !enabled || !wasEnabled,
        )
        if (!committed) return false
        val newlyInitialized = if (enabled) {
            initializeEntityLearningAfterEffectInvalidation(
                invalidate = { invalidateEffects() },
                initialize = { ensureInitialized() },
            )
        } else {
            cancelPeriodicSyncLocked()
            invalidateEffects()
            false
        }
        if (enabled) {
            val resolverMigration = if (newlyInitialized) null else applyDefaultResolverMigration()
            if (resolverMigration != DashboardEntityDefaultResolverMigration.PERSIST_FAILED &&
                !newlyInitialized
            ) {
                syncNow(if (resolverMigration == DashboardEntityDefaultResolverMigration.REBOOTSTRAP) {
                    "default-resolver-enable"
                } else {
                    "enable"
                })
            }
            // COMMIT-FROM-CATALOG: with a settled catalog for the current target, apply the subscription
            // NOW, synchronously — no network in the hold path. Every wizard panel arrives here with a
            // fresh catalog (the count step just displayed it), yet the enable-time sync has died
            // silently twice on hardware — worst on the newlyInitialized path above, which calls no sync
            // at all and trusts a startup sync that skips when a persisted catalog exists: full catalog,
            // zero applied ids, eternal "Preparing" hold. A store read and a commit close the class; the
            // scheduled/background sync refreshes afterwards as usual.
            if (!config.dashboardEntityLearningApplied && catalogCount() != null) {
                runCatching {
                    val active = desiredIds(System.currentTimeMillis())
                    if (active.isNotEmpty()) {
                        check(config.commitDashboardEntitySubscription(true, active, applied = true)) {
                            "commit-from-catalog failed to persist"
                        }
                        Log.i(TAG, "entity learning: applied ${active.size} ids from the settled catalog at enable")
                    } else {
                        Log.i(TAG, "entity learning: settled catalog yields no active ids yet; awaiting sync")
                    }
                }.onFailure { Log.w(TAG, "entity learning: commit-from-catalog at enable failed", it) }
            }
            // Always notify the renderer. A populated set rebuilds with its observer; an empty fresh
            // bootstrap tears down any pre-existing unfiltered WebView and waits on the native bootstrap
            // screen until synchronization commits the first narrow set.
            onFilterChanged()
        } else {
            resetBootstrapPending = false
            if (initialized) store.markStatus(instance(), dashboardPath(), "disabled")
            onFilterChanged()
        }
        true
    }

    /** Explicitly promote the policy-selected evidence set, primarily for existing manual filters. */
    @Synchronized fun activate(confirm: Boolean): String {
        ensureInitialized()
        require(config.dashboardEntityLearningEnabled) { "automatic learning is not enabled" }
        require(canActivateLearnedEntitySet(config.dashboardEntityDefaultResolverRebootstrapPending)) {
            "a fresh default-dashboard scan must complete before activation"
        }
        require(store.snapshot(instance(), dashboardPath()).blockingIssueCount == 0) {
            "automatic activation is blocked by dashboard configuration issues"
        }
        if (!confirm) return JSONObject().put("ok", false).put("confirmation_required", true).toString()
        val active = desiredIds(System.currentTimeMillis())
        val preview = subscriptionPreview(active)
        check(config.commitDashboardEntitySubscription(true, active, applied = true)) {
            "failed to commit learned entity set"
        }
        invalidateEffects()
        if (preview.streamChange) onFilterChanged()
        resetBootstrapPending = false
        store.markStatus(instance(), dashboardPath(), "active")
        return JSONObject().put("ok", true).put("entity_count", active.size)
            .put("previous_count", preview.currentCount)
            .put("added_count", preview.additions).put("removed_count", preview.removals)
            .put("stream_changed", preview.streamChange)
            .put("automatic_updates_enabled", true)
            .put("filter_hash", EntityFilterProtocol.hash(active)).toString()
    }

    fun syncNow(reason: String = "manual"): Boolean = synchronized(this) {
        ensureInitialized()
        if (syncJob?.isActive == true) {
            // A rerun latch, never a silent drop. The enable-time bootstrap once no-opped here against a
            // still-running count scan — which, having started with learning OFF, commits no applied
            // subscription — and the panel sat on its "Preparing" hold until the hourly loop: a de facto
            // hang, watched live on hardware. A busy scanner means "go again when this one finishes",
            // because the active run may be operating under superseded intent.
            syncRerunReason = reason
            return@synchronized false
        }
        val requestGeneration = effectGeneration.get()
        // Lifecycle forensics: this sync has died silently twice on hardware (no scan, no commit, no
        // log). Every launch and outcome names itself, at INFO, so the next death is attributable.
        Log.i(TAG, "entity catalog sync launched ($reason)")
        syncJob = scope.launch {
            syncMutex.withLock {
                val fallbackInstance = instance(); val fallbackPath = dashboardPath()
                runCatching { withEntityLearningDeadline(SYNC_TIMEOUT_MS) { synchronize() } }
                    .onSuccess { Log.i(TAG, "entity catalog sync completed ($reason)") }
                    .onFailure {
                        if (it is CancellationException && it !is TimeoutCancellationException) return@onFailure
                        Log.w(TAG, "entity catalog sync failed ($reason): ${it.message}")
                        val rerun = entityLearningRerunAfterFailure(it, reason)
                        if (rerun != null) {
                            // Picked up by the completion latch below, like a request that arrived mid-scan.
                            synchronized(this@EntityLearningManager) { if (syncRerunReason == null) syncRerunReason = rerun }
                        } else if (shouldPersistEntityLearningFailure(
                                // A superseded request must not stamp its failure onto the newly-selected target.
                                requestGeneration,
                                effectGeneration.get(),
                                fallbackInstance,
                                fallbackPath,
                                instance(),
                                dashboardPath(),
                            )) {
                            store.markStatus(fallbackInstance, fallbackPath, "degraded", it.message ?: it.javaClass.simpleName)
                        }
                    }
            }
        }.also { job ->
            job.invokeOnCompletion { cause ->
                // Normal completion only: a CANCELLED sync (shutdown, target swap) must not resurrect
                // itself. Runs after the job is no longer active, so the recursive call proceeds.
                if (cause == null) {
                    val rerun = synchronized(this@EntityLearningManager) {
                        syncRerunReason.also { syncRerunReason = null }
                    }
                    if (rerun != null) syncNow(rerun)
                }
            }
        }
        true
    }

    /** True while a catalogue scan is in flight. The held renderer's watchdog never doubles one. */
    fun syncRunning(): Boolean = syncJob?.isActive == true

    /**
     * Ask Home Assistant whether the held dashboard changed, without resyncing the catalogue.
     *
     * While the renderer is held only on a person's decision about a flagged rule, the catalogue is
     * settled and a full resync cannot change the answer. What CAN change it without anybody at the
     * panel is the dashboard itself: an edit that removes the rule, or the account default moving to a
     * different dashboard. This is one authenticated read of the resolved dashboard's configuration,
     * compared with the revision the last scan committed and the scope it was bound to. A change starts
     * exactly one scan; no change commits nothing to the catalogue. It is single-flight, never runs
     * beside a scan, and honours the same generation checks as a scan, so a target that moves under it
     * abandons the probe rather than acting on a stale answer.
     *
     * Failures are logged, not persisted, with one exception: a credential rejection is a fault the
     * person has to fix, so it is recorded exactly as a failed scan would record it and the renderer's
     * hold screen names the sign-in rather than the decision.
     */
    fun probeDashboardChange(reason: String): Boolean = synchronized(this) {
        ensureInitialized()
        if (syncJob?.isActive == true || probeJob?.isActive == true) return@synchronized false
        val requestGeneration = effectGeneration.get()
        Log.i(TAG, "dashboard change probe launched ($reason)")
        probeJob = scope.launch {
            val fallbackInstance = instance(); val fallbackPath = dashboardPath()
            runCatching { withEntityLearningDeadline(PROBE_TIMEOUT_MS) { probeDashboard() } }
                .onSuccess { outcome ->
                    Log.i(TAG, "dashboard change probe completed ($reason): ${outcome.name.lowercase()}")
                    if (outcome == DashboardProbeOutcome.CHANGED) syncNow("dashboard-changed")
                }
                .onFailure {
                    if (it is CancellationException && it !is TimeoutCancellationException) return@onFailure
                    Log.w(TAG, "dashboard change probe failed ($reason): ${it.message}")
                    if (probeFailureSurfacesProblem(it.message) &&
                        shouldPersistEntityLearningFailure(
                            requestGeneration,
                            effectGeneration.get(),
                            fallbackInstance,
                            fallbackPath,
                            instance(),
                            dashboardPath(),
                        )
                    ) {
                        store.markStatus(fallbackInstance, fallbackPath, "degraded", it.message ?: it.javaClass.simpleName)
                    }
                }
        }
        true
    }

    private suspend fun probeDashboard(): DashboardProbeOutcome = withContext(Dispatchers.IO) {
        val target = captureAuthenticatedTarget()
        val snapshot = captureSyncSnapshot(target)
        var outcome = DashboardProbeOutcome.UNCHANGED
        withHaSocket(config, snapshot.baseUrl, snapshot.authToken, checkNotNull(snapshot.route)) { request ->
            val resolved = homeDashboardAuthority.resolve(
                snapshot.homeDashboardAuthorityKey,
                stillCurrent = {
                    config.homeDashboard.trim() == snapshot.configuredHomeDashboard &&
                        snapshot.matchesCurrent(effectGeneration.get(), currentEffectState())
                },
                forceLive = homeDashboardResolutionMustBeLive(snapshot.configuredHomeDashboard),
            ) {
                readHomeDashboardResolution(request, snapshot.configuredHomeDashboard)
            }?.path ?: error("Home Assistant reported no legal dashboard")
            if (resolvedScopeRequiresRebind(snapshot.configuredHomeDashboard, snapshot.dashboardPath, resolved)) {
                outcome = DashboardProbeOutcome.CHANGED
                return@withHaSocket
            }
            val configJson = readDashboardConfig(request, resolved) ?: error("dashboard configuration unavailable")
            outcome = dashboardProbeOutcome(
                configuredHomeDashboard = snapshot.configuredHomeDashboard,
                resolvedPath = resolved,
                boundPath = snapshot.dashboardPath,
                liveRevision = DashboardConfigurationLint.revision(configJson),
                storedRevision = store.dashboardConfigHash(snapshot.instanceKey, snapshot.dashboardPath),
            )
        }
        check(snapshot.matchesCurrent(effectGeneration.get(), currentEffectState())) {
            "entity-learning target or policy changed during the probe"
        }
        outcome
    }

    /**
     * The bounded upgrade recovery, decided on the exact post-commit candidate.
     *
     * The default-resolver migration disables a filter the panel was already running and forces a
     * re-bootstrap. A dashboard rule the analyzer flags must not then hold that panel behind a decision
     * nobody is present to make, because the panel was working a minute ago and the dashboard did not
     * change. But restoring is only safe when the set now available carries the COMPLETE list the panel
     * had. A subset is silent canonical loss: the subscription would be overwritten with fewer ids and
     * the one-shot latch cleared, so no later pass would ever notice the missing ones — cards simply
     * stop updating. Anything short of complete leaves the decision hold in place, which is recoverable.
     *
     * Returns true only when the block was genuinely cleared and the caller may proceed to apply.
     */
    private fun recoverUpgradeFilter(
        snapshot: EntityLearningSyncSnapshot,
        rawIssues: List<JSONObject>,
        now: Long,
    ): Boolean {
        if (!upgradeRecoveryAdmissible(snapshot.upgradeRebootstrapPending, snapshot.filterIds)) return false
        val recovered = desiredIds(
            now, snapshot.instanceKey, snapshot.dashboardPath,
            includeStatic = snapshot.autoStatic,
            includeRuntime = snapshot.autoRuntime,
        )
        if (!upgradeRecoveryPreservesFilter(snapshot.filterIds, recovered)) {
            Log.w(
                TAG,
                "upgrade re-bootstrap: holding the dashboard — the restorable set (${recovered.size}) does not " +
                    "preserve the complete retained filter (${snapshot.filterIds.size}); the decision stands",
            )
            return false
        }
        val ignorable = ignorableIssueFingerprints(rawIssues)
        if (ignorable.isEmpty()) return false
        if (!ignorable.all { store.setIssueIgnored(snapshot.instanceKey, snapshot.dashboardPath, it, true, now) }) {
            Log.w(TAG, "upgrade re-bootstrap: a dashboard rule could not be recorded as ignored; the decision stands")
            return false
        }
        bootstrapBlockingIssues = store.snapshot(snapshot.instanceKey, snapshot.dashboardPath).blockingIssueCount
        if (bootstrapBlockingIssues > 0) {
            Log.w(TAG, "upgrade re-bootstrap: a non-ignorable fence still blocks this dashboard; the decision stands")
            return false
        }
        Log.i(
            TAG,
            "upgrade re-bootstrap: ${ignorable.size} ignorable dashboard rule(s) recorded as ignored so the " +
                "${snapshot.filterIds.size}-entity filter this panel already ran is restored complete; " +
                "review them under Entities",
        )
        return true
    }

    private suspend fun synchronize() = withContext(Dispatchers.IO) {
        val syncCost = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_SYNC)
        var syncWork = 0L
        try {
        val target = captureAuthenticatedTarget()
        val states = fetchStates(target.baseUrl, target.authToken, target.route)
        syncWork += states.size
        require(states.isNotEmpty()) { "Home Assistant returned no visible states" }

        val identityCandidates = haInstanceCandidateUrls(
            target.baseUrl,
            runCatching { fetchHaConfig(target.baseUrl, target.authToken, target.route) }.getOrNull(),
        )
        val resolvedUuid = resolveInstanceUuid(identityCandidates)
        val adoptedTarget = if (resolvedUuid.isNullOrBlank()) target else adoptStableTarget(target, resolvedUuid)
        val snapshot = captureSyncSnapshot(adoptedTarget)
        require(snapshot.matchesCurrent(effectGeneration.get(), currentEffectState())) { "entity-learning target or policy changed" }

        val dashboardCost = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_DASHBOARD_FETCH_PARSE)
        val ws = try {
            fetchDashboardAndRegistry(snapshot).also {
                dashboardCost.work(
                    units = it.metadata.size.toLong(),
                    // String length is the allocation-free work-size proxy used by the other browser
                    // parsers. Encoding a multi-megabyte admitted dashboard solely for telemetry can
                    // otherwise create a second tens-of-megabytes buffer on memory-constrained panels.
                    bytes = it.configJson.length.toLong(),
                )
            }
        } catch (failure: Throwable) {
            dashboardCost.outcome(failure.featureCostOutcome())
            throw failure
        } finally {
            dashboardCost.close()
        }
        // Best effort: without the file, the entities its templates name are learned at runtime.
        val streamlineTemplates = if (StreamlineTemplates.usedBy(ws.configJson)) runInterruptible(Dispatchers.IO) {
            StreamlineTemplates.fetch(snapshot.baseUrl, HA_CONFIG_TIMEOUT_MS) {
                requireCurrentHaRoute(config, snapshot.baseUrl, snapshot.authToken, checkNotNull(snapshot.route))
            }
        } else null
        val scanCost = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_STATIC_SCAN)
        val scan = try {
            EntityLearningProtocol.scanDashboard(ws.configJson, streamlineTemplates).also {
                scanCost.work(
                    units = (it.entityIds.size + it.targets.size + it.unresolved.size).toLong(),
                    bytes = ws.configJson.length.toLong(),
                )
            }
        } catch (failure: Throwable) {
            scanCost.outcome(failure.featureCostOutcome())
            throw failure
        } finally {
            scanCost.close()
        }
        val expanded = expandTargets(snapshot.baseUrl, snapshot.authToken, scan.targets, checkNotNull(snapshot.route))
        val catalogIds = states.asSequence().map { it.entityId.lowercase() }.toHashSet()
        val friendlyNames = states.asSequence().filter { it.friendlyName.isNotBlank() }
            .associate { it.entityId.lowercase() to it.friendlyName }
        val lint = DashboardConfigurationLint.analyze(
            ws.configJson,
            catalogIds,
            ws.metadata,
            friendlyNames,
            areaRegistryEntities = ws.areaRegistryEntities,
            registryMetadataComplete = ws.registryMetadataComplete,
        )
        val rawIssues = lint.issues.map(DashboardConfigurationLint.Issue::toJson)
        val bootstrap = snapshot.forceBootstrap || shouldBootstrapEntityLearning(
            learningEnabled = snapshot.learningEnabled,
            applied = snapshot.applied,
            configuredIds = snapshot.filterIds,
        )
        // A clean installation must reach its dashboard without a hidden acknowledgement workflow.
        // Persist ordinary, explicitly ignorable compatibility findings as ignored on the first
        // successful bootstrap; they remain visible in Entities and can be re-enabled there. Hard
        // diagnostic/resource fences are never ignorable and therefore still hold the renderer.
        //
        // The upgrade re-bootstrap deliberately takes NO default here. Its recovery is decided after the
        // commit instead, on the exact set the apply would install; see [recoverUpgradeFilter].
        val storedIgnoredFingerprints = store.ignoredIssueFingerprints(snapshot.instanceKey, snapshot.dashboardPath)
        val defaultIgnoredFingerprints = defaultIgnoredIssueFingerprints(
            initialActivationPending = snapshot.initialActivationPending,
            bootstrap = bootstrap,
            issues = rawIssues,
        )
        val ignoredFingerprints = storedIgnoredFingerprints + defaultIgnoredFingerprints
        val lintIds = effectiveLintEntityIds(lint, ignoredFingerprints)
        // Context-free literals still require a live-catalog match to discard entity-shaped prose.
        // Server-expanded targets and registry-backed linter results are authoritative structural
        // evidence and may intentionally name an entity whose state is temporarily absent.
        val derived = deriveStaticEntityIds(scan.entityIds, catalogIds, expanded, lintIds)
        require(derived.size <= EntityFilterProtocol.MAX_ENTITY_IDS) {
            "derived entity set exceeds ${EntityFilterProtocol.MAX_ENTITY_IDS} IDs"
        }
        syncWork += derived.size
        val effectiveIssuesJson = EntityCatalogIssuePersistence.applyIgnores(
            JSONArray(rawIssues), ignoredFingerprints,
        )
        val effectiveIssues = JSONArray(effectiveIssuesJson).let { array ->
            (0 until array.length()).mapNotNull(array::optJSONObject)
        }
        val effectiveBlocking = effectiveIssues.any { it.optBoolean("blocking", false) }
        val now = System.currentTimeMillis()
        val decision = automaticSyncDecision(
            learningEnabled = snapshot.learningEnabled,
            applied = snapshot.applied,
            configuredIds = snapshot.filterIds,
            blockingIssues = effectiveBlocking,
            forceBootstrap = snapshot.forceBootstrap,
        )
        commitEntityLearningSyncEvidence(
            owner = this@EntityLearningManager,
            ensureCurrent = {
                check(snapshot.matchesCurrent(effectGeneration.get(), currentEffectState())) {
                    "entity-learning target or policy changed before commit"
                }
            },
            commitSync = {
                store.commitSync(
                    instance = snapshot.instanceKey,
                    path = snapshot.dashboardPath,
                    states = states,
                    metadata = ws.metadata,
                    configJson = ws.configJson,
                    configHash = lint.dashboardRevision,
                    derived = derived,
                    unresolved = scan.unresolved,
                    status = when (decision) {
                        AutomaticSyncDecision.BLOCKED -> "blocked"
                        AutomaticSyncDecision.APPLY -> "active"
                        AutomaticSyncDecision.BOOTSTRAP -> "learning"
                        AutomaticSyncDecision.OBSERVE -> "observing"
                    },
                    now = now,
                    issues = effectiveIssues,
                    defaultIgnoredFingerprints = defaultIgnoredFingerprints,
                )
            },
            applyStoredOverrides = {
                applyStoredOverrides(snapshot.instanceKey, snapshot.dashboardPath, snapshot.overrides)
            },
            publishDiagnostics = {
                bootstrapBlockingIssues = effectiveIssues.count { it.optBoolean("blocking", false) }
                dynamicExpressionsJson = encodeDynamicExpressions(scan.dynamicExpressions)
            },
        )
        // A blocking dashboard rule invalidates the proposed set as a whole. Keep an existing safe
        // filter byte-for-byte, or keep a fresh renderer on its native diagnostic screen. Never apply
        // the bounded fragments of a partially unsafe dashboard and never fall back to an unfiltered
        // WebSocket while automatic learning is enabled.
        //
        // The one exception is the re-bootstrap an upgrade forced on a panel that was already filtering
        // this dashboard, and it is decided here rather than before the commit for a reason that cost a
        // review round: `commitSync` above advances `missing_streak` for every entity absent from this
        // scan, and `desiredIds` excludes rows that reach three. A candidate measured before the commit
        // can therefore be larger than the one the apply installs, or empty where it looked sufficient.
        if (effectiveBlocking && !recoverUpgradeFilter(snapshot, rawIssues, now)) return@withContext
        val active = desiredIds(
            now, snapshot.instanceKey, snapshot.dashboardPath,
            includeStatic = snapshot.autoStatic,
            includeRuntime = snapshot.autoRuntime,
        )
        synchronized(this@EntityLearningManager) {
            check(snapshot.matchesCurrent(effectGeneration.get(), currentEffectState())) {
                "entity-learning target or policy changed before apply"
            }
            if (snapshot.applied || bootstrap) {
                val changed = active != snapshot.filterIds || !snapshot.filterEnabled
                check(config.commitDashboardEntitySubscription(true, active, applied = true)) {
                    "failed to commit automatic entity set"
                }
                if (snapshot.forceBootstrap) {
                    check(config.clearDashboardEntityDefaultResolverRebootstrapPending()) {
                        "failed to clear default-dashboard replacement latch"
                    }
                }
                if (changed) onFilterChanged()
            }
            if (snapshot.forceBootstrap) resetBootstrapPending = false
        }
        } catch (failure: Throwable) {
            syncCost.outcome(failure.featureCostOutcome())
            throw failure
        } finally {
            syncCost.work(units = syncWork).close()
        }
    }

    fun recordAccessBatch(text: String) {
        if (!config.dashboardEntityLearningEnabled) return
        val span = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_ACCESS_PARSE)
        val parsed = runCatching { EntityLearningProtocol.parseAccessBatch(text) }.getOrElse {
            span.outcome(FeatureCostOutcome.FAILURE).work(bytes = text.length.toLong()).close()
            return
        }
        span.work(units = (parsed.first.size + parsed.second.size).toLong(), bytes = text.length.toLong()).close()
        admitTelemetry(accessed = parsed.first, missing = parsed.second)
    }

    fun recordMetricBatch(text: String) {
        if (!config.dashboardEntityLearningEnabled) return
        val span = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_METRIC_PARSE)
        val envelope = runCatching { EntityLearningProtocol.parseMetricEnvelope(text) }.getOrElse {
            span.outcome(FeatureCostOutcome.FAILURE).work(bytes = text.length.toLong()).close()
            return
        }
        span.work(units = envelope.metrics.size.toLong(), bytes = text.length.toLong()).close()
        envelope.observer?.let { observer ->
            recordBrowserObserverCosts(FeatureCosts.registry, observer)
        }
        if (envelope.metrics.isNotEmpty()) admitTelemetry(metrics = envelope.metrics)
    }

    /** Merge renderer batches into one fixed-size generation-bound accumulator and own one IO worker. */
    private fun admitTelemetry(
        accessed: Map<String, Long> = emptyMap(),
        missing: Set<String> = emptySet(),
        metrics: Map<String, Pair<Long, Long>> = emptyMap(),
    ) {
        val target = EntityTelemetryTarget(effectGeneration.get(), instance(), dashboardPath())
        telemetryPipeline.admit(
            onOpen = { pending ->
                val admission = pending.admit(target, accessed, missing, metrics)
                if (admission.coalesced > 0) {
                    FeatureCosts.registry.recordCoalesced(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, admission.coalesced)
                }
                if (admission.dropped > 0) {
                    FeatureCosts.registry.recordDropped(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, admission.dropped)
                }
                FeatureCosts.registry.setBacklog(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, admission.backlog)
            },
            onClosed = {
                FeatureCosts.registry.recordDropped(
                    FeatureCostOperation.ENTITY_TELEMETRY_FLUSH,
                    (accessed.keys + missing + metrics.keys).size.toLong(),
                )
            },
        )
    }

    private suspend fun drainTelemetry() {
        while (true) {
            val batch = telemetryPending.drain() ?: telemetryPipeline.withLock {
                // Serialize the empty observation with admission. If a producer arrived after drain(),
                // it sees the worker active; re-check before relinquishing ownership.
                it.drain()
            } ?: return
            FeatureCosts.registry.setBacklog(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, telemetryPending.size())
            val flush = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH)
                .work(units = batch.uniqueIds.toLong())
            try {
                flushTelemetry(batch)
            } catch (error: CancellationException) {
                flush.outcome(FeatureCostOutcome.CANCELLED)
                throw error
            } catch (error: Exception) {
                flush.outcome(FeatureCostOutcome.FAILURE)
                Log.w(TAG, "entity telemetry flush failed: ${error.message}")
            } finally {
                flush.close()
            }
        }
    }

    private fun flushTelemetry(batch: EntityTelemetryBatch) = telemetryStoreGate.withStore {
        if (batch.target.generation != effectGeneration.get()) {
            FeatureCosts.registry.recordDropped(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, batch.uniqueIds.toLong())
            return@withStore
        }
        val accessIds = batch.accessed.keys + batch.missing
        val lookup = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_MEMBERSHIP_LOOKUP)
            .work(units = accessIds.size.toLong())
        val known = try {
            store.existingEntityIds(batch.target.instance, accessIds)
        } catch (error: Exception) {
            lookup.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            lookup.close()
        }
        val knownAccessed = batch.accessed.filterKeys(known::contains)
        val knownMissing = batch.missing.filterTo(mutableSetOf(), known::contains)
        val admitted = writeEntityTelemetryThen(
            barrier = telemetryWriteBarrier,
            admittedGeneration = batch.target.generation,
            write = {
                if (knownAccessed.isNotEmpty() || knownMissing.isNotEmpty()) {
                    val access = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_ACCESS_WRITE)
                        .work(units = (knownAccessed.size + knownMissing.size).toLong())
                    try {
                        val counts = LinkedHashMap(knownAccessed)
                        knownMissing.forEach { id -> counts.putIfAbsent(id, 1L) }
                        store.recordAccess(
                            batch.target.instance,
                            batch.target.path,
                            counts,
                            batch.now,
                        )
                    } catch (error: Exception) {
                        access.outcome(FeatureCostOutcome.FAILURE)
                        throw error
                    } finally {
                        access.close()
                    }
                }
                if (batch.metrics.isNotEmpty()) {
                    val metric = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_METRIC_WRITE)
                        .work(
                            units = batch.metrics.values.sumOf { it.first.coerceAtLeast(0L) },
                            bytes = batch.metrics.values.sumOf { it.second.coerceAtLeast(0L) },
                        )
                    try {
                        store.recordMetrics(batch.target.instance, batch.target.path, batch.metrics, batch.now)
                    } catch (error: Exception) {
                        metric.outcome(FeatureCostOutcome.FAILURE)
                        throw error
                    } finally {
                        metric.close()
                    }
                }
            },
            afterWrite = {
                if (knownMissing.isNotEmpty() && config.dashboardEntityLearningApplied &&
                    config.dashboardEntityAutoRuntime
                ) {
                    runCatching { capturePromotionSnapshot(batch.target.instance, batch.target.path) }
                        .getOrNull()?.let(::queuePromotion)
                }
            },
        )
        if (!admitted) {
            FeatureCosts.registry.recordDropped(FeatureCostOperation.ENTITY_TELEMETRY_FLUSH, batch.uniqueIds.toLong())
        }
    }

    private fun queuePromotion(snapshot: EntityLearningPromotionSnapshot) {
        synchronized(this) {
            if (!snapshot.isEligible(effectGeneration.get(), currentEffectState(), bootstrapBlockingIssues)) return
            // Trailing-edge coalescing: a newer snapshot SUPERSEDES a pending one and restarts the wait,
            // so promotions batch until the telemetry quiets down. The old leading-edge admit (drop
            // anything while a job is pending) plus a 2-second debounce meant the very first filtered
            // page load — which itself generates the known-missing telemetry — produced two promotions
            // seventeen seconds apart, each a full WebView teardown the user watched with no explanation
            // (hardware review: "reloaded at least 5 times"). One later, fuller promotion beats two
            // visible resets; promotions are background optimization and are never urgent.
            promotionJob?.cancel()
            promotionJob = scope.launch {
                delay(PROMOTION_DEBOUNCE_MS)
                if (!snapshot.isEligible(effectGeneration.get(), currentEffectState(), bootstrapBlockingIssues)) return@launch
                val now = System.currentTimeMillis()
                if (now - promotionWindowStart > PROMOTION_WINDOW_MS) {
                    promotionWindowStart = now; promotionsInWindow = 0
                }
                val active = withContext(Dispatchers.IO) {
                    desiredIds(
                        now, snapshot.instanceKey, snapshot.dashboardPath,
                        includeStatic = snapshot.autoStatic,
                        includeRuntime = snapshot.autoRuntime,
                    )
                }
                synchronized(this@EntityLearningManager) {
                    if (!snapshot.isEligible(effectGeneration.get(), currentEffectState(), bootstrapBlockingIssues)) return@synchronized
                    when (runtimePromotionDecision(active, snapshot.filterIds, promotionsInWindow, MAX_PROMOTIONS)) {
                        RuntimePromotionDecision.UNCHANGED -> Unit
                        RuntimePromotionDecision.CAPPED -> store.markStatus(
                            snapshot.instanceKey, snapshot.dashboardPath,
                            "degraded", PROMOTION_CAP_ERROR,
                        )
                        RuntimePromotionDecision.PROMOTE -> {
                            check(config.commitDashboardEntitySubscription(true, active, applied = true)) {
                                "failed to commit runtime entity promotion"
                            }
                            promotionsInWindow++
                            // Clear only the cap's own degraded status; a failed sync's error stays.
                            store.recoverStatus(snapshot.instanceKey, snapshot.dashboardPath, PROMOTION_CAP_ERROR)
                            onFilterChanged()
                        }
                    }
                }
            }
        }
    }

    fun setOverride(entityId: String, override: String, force: Boolean): String {
        val id = EntityFilterProtocol.normalize(listOf(entityId)).single()
        if (override == "forced_exclude" && !force) {
            return JSONObject().put("ok", false).put("confirmation_required", true).put("entity_id", id).toString()
        }
        applyOverrides(listOf(id), override, force)
        return JSONObject().put("ok", true).put("entity_id", id).put("override", override).toString()
    }

    fun setOverrides(entityIds: List<String>, allCandidates: Boolean, override: String, force: Boolean): String {
        require(allCandidates || entityIds.isNotEmpty()) { "entity_ids or all_candidates required" }
        val ids = if (allCandidates) {
            val subscribed = config.dashboardEntityFilterIds.toSet()
            store.suggestedIds(instance(), dashboardPath()).filterNot(subscribed::contains)
        } else EntityFilterProtocol.normalize(entityIds)
        require(ids.isNotEmpty()) { "selected entity set is empty" }
        if (override == "forced_exclude" && !force) {
            return JSONObject().put("ok", false).put("confirmation_required", true).put("entity_count", ids.size).toString()
        }
        applyOverrides(ids, override, force)
        return JSONObject().put("ok", true).put("entity_count", ids.size).put("override", override).toString()
    }

    private fun applyOverrides(ids: List<String>, override: String, force: Boolean) = synchronized(this) {
        ensureInitialized()
        require(override in setOf("auto", "pinned", "forced_exclude")) { "invalid override" }
        require(override != "forced_exclude" || force) { "force confirmation required" }
        val previousOverrides = config.dashboardEntityOverrides
        val encoded = previousOverrides.toMutableMap().apply {
            for (id in ids) if (override == "auto") remove(id) else put(id, override)
        }
        invalidateEffects()
        val targetInstance = instance()
        val targetPath = dashboardPath()
        val applied = config.dashboardEntityLearningApplied
        val previousFilter = config.dashboardEntityFilterIds
        var active = previousFilter
        runEntityOverrideTransaction(
            commitOverridePreferences = { config.setDashboardEntityOverrides(encoded) },
            applyStoreOverride = { store.setOverrides(targetInstance, targetPath, ids, override) },
            commitActiveFilter = {
                active = desiredIds(System.currentTimeMillis(), targetInstance, targetPath)
                !applied || config.commitDashboardEntitySubscription(true, active, applied = true)
            },
            restoreStoreOverride = {
                ids.forEach { id -> store.setOverride(targetInstance, targetPath, id, previousOverrides[id] ?: "auto") }
            },
            restoreOverridePreferences = { config.setDashboardEntityOverrides(previousOverrides) },
        )
        if (applied && active != previousFilter) onFilterChanged()
    }

    /** Tester recovery/reset: discard derived dashboard evidence, never credentials or the HA catalog. */
    fun resetEvidence(confirm: Boolean, clearFilter: Boolean = false): String =
        withEntityLearningMutationLock(this) {
            if (!confirm) return JSONObject().put("ok", false).put("confirmation_required", true).toString()
            ensureInitialized()
            check(syncJob?.isActive != true) { "synchronization is running" }
            val preferenceSnapshot = config.dashboardEntityBackupState()
            val previousBootstrapPending = resetBootstrapPending
            runEntityEvidenceResetTransaction(
                commitPreferences = { config.commitDashboardEntityEvidenceReset(clearFilter) },
                resetStore = {
                    telemetryWriteBarrier.invalidateAndWrite(
                        invalidate = { invalidateEffects() },
                    ) {
                        if (config.dashboardEntityLearningEnabled) {
                            // Preserve a known-good live set until the replacement scan succeeds. A blocking or failed
                            // default reset must never turn into an unfiltered renderer or a partial set. The explicit
                            // clean-slate path clears that set deliberately and holds the native bootstrap screen.
                            resetBootstrapPending = true
                        }
                        store.resetEvidence(instance(), dashboardPath())
                    }
                },
                restorePreferences = {
                    resetBootstrapPending = previousBootstrapPending
                    config.restoreDashboardEntityEvidencePreferences(preferenceSnapshot)
                },
                afterSuccess = {
                    bootstrapBlockingIssues = 0
                    dynamicExpressionsJson = "[]"
                },
            )
            if (clearFilter) onFilterChanged()
            val started = if (config.dashboardEntityLearningEnabled) syncNow("reset") else false
            return JSONObject().put("ok", true).put("sync_started", started)
                .put("filter_cleared", clearFilter).toString()
        }

    private fun applyStoredOverrides(instance: String, path: String, overrides: Map<String, String>) {
        overrides.forEach { (id, override) -> store.setOverride(instance, path, id, override) }
    }

    /** Change which evidence sources may promote entities. Collection and review continue regardless. */
    @Synchronized fun setPromotionPolicy(staticRefs: Boolean, runtimeRefs: Boolean): String {
        ensureInitialized()
        val applied = config.dashboardEntityLearningApplied
        val active = desiredIds(
            System.currentTimeMillis(),
            includeStatic = staticRefs,
            includeRuntime = runtimeRefs,
        )
        val changed = applied && (active != config.dashboardEntityFilterIds || !config.dashboardEntityFilterEnabled)
        check(config.commitDashboardEntityPromotionPolicy(
            staticRefs = staticRefs,
            runtimeRefs = runtimeRefs,
            activeEntityIds = active.takeIf { changed },
            applied = applied,
        )) { "failed to commit promotion policy" }
        invalidateEffects()
        if (changed) onFilterChanged()
        return JSONObject().put("ok", true).put("auto_static", staticRefs)
            .put("auto_runtime", runtimeRefs).put("entity_count", active.size).toString()
    }

    fun statusJson(): String {
        ensureInitialized()
        val span = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_STATUS_READ)
        try {
            val currentInstance = instance()
            val currentPath = dashboardPath()
            val s = store.snapshot(currentInstance, currentPath)
            bootstrapBlockingIssues = s.blockingIssueCount
            val suggestions = store.suggestedIds(currentInstance, currentPath)
            val filterIds = config.dashboardEntityFilterIds
            val filterSet = filterIds.toSet()
            val candidates = suggestions.count { it !in filterSet }
            val learningEnabled = config.dashboardEntityLearningEnabled
            val filtered = config.dashboardEntityFilterEnabled
            val applied = config.dashboardEntityLearningApplied
            val autoStatic = config.dashboardEntityAutoStatic
            val autoRuntime = config.dashboardEntityAutoRuntime
            val visibleIssues = visibleDashboardIssues(
                store.issuesJson(currentInstance, currentPath),
                showAdvisories = !autoRuntime,
            )
            val visibleIssueJson = visibleIssues.toString()
            val visibleIssueCounts = EntityCatalogIssuePersistence.counts(visibleIssueJson)
            val held = shouldHoldRendererForEntityBootstrap(config)
            val holdReason = entityBootstrapHoldReason(
                held = held,
                blockingIssues = s.blockingIssueCount,
                problem = classifyEntityBootstrapProblem(s.state, s.error),
            )
            val desired = desiredIds(
                System.currentTimeMillis(), currentInstance, currentPath,
                includeStatic = autoStatic,
                includeRuntime = autoRuntime,
            )
            val preview = previewEntitySubscription(filtered, filterIds, s.catalogCount, desired)
            val streamMode = entityStreamMode(held, filtered)
            span.work(units = (s.catalogCount + suggestions.size).toLong())
            return JSONObject()
                .put("requested_enabled", learningEnabled)
                .put("mode", if (learningEnabled) "automatic" else "manual")
                .put("state", when {
                    !learningEnabled -> "disabled"
                    s.blockingIssueCount > 0 -> "blocked"
                    else -> s.state
                })
                .put("applied", applied)
                .put("auto_static", autoStatic)
                .put("auto_runtime", autoRuntime)
                .put("last_sync_at", s.lastSyncAt).put("catalog_count", s.catalogCount)
                .put("active_count", filterIds.size)
                .put("candidate_count", candidates).put("suggested_count", candidates)
                .put("desired_count", desired.size)
                .put("pending_additions", if (s.blockingIssueCount > 0) 0 else preview.additions)
                .put("pending_removals", if (s.blockingIssueCount > 0) 0 else preview.removals)
                .put("stream_change_required", s.blockingIssueCount == 0 && preview.streamChange)
                .put("activation_required", !applied)
                .put("apply_required", s.blockingIssueCount == 0 && (preview.streamChange || !applied))
                .put("dashboard_issue_count", visibleIssueCounts.first)
                .put("blocking_issue_count", visibleIssueCounts.second)
                .put("ignored_issue_count", EntityCatalogIssuePersistence.ignoredCount(visibleIssueJson))
                .put("automatic_activation_blocked", s.blockingIssueCount > 0)
                .put("stream_mode", streamMode)
                .put("strategy_selector_ignored", strategySelectorAllowed(streamMode, visibleIssues))
                .put("hold_reason", holdReason?.wireName ?: JSONObject.NULL)
                .put("resync_suspended", holdReason == EntityBootstrapHoldReason.DECISION)
                .put("stream_entity_count", when { held -> 0; filtered -> filterIds.size; else -> s.catalogCount })
                .put("stream_filter_hash", if (filtered) EntityFilterProtocol.hash(filterIds) else "")
                .put("unresolved_count", s.unresolvedCount)
                .put("error", s.error).put("db_bytes", s.dbBytes).put("sync_running", syncJob?.isActive == true)
                .toString()
        } catch (error: Exception) {
            span.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            span.close()
        }
    }

    /**
     * Whether a dashboard strategy runs under an allowed entity-discovery check on the live filtered
     * stream, for the Configure page banner. Same inputs as [statusJson], without its catalogue work.
     */
    fun strategySelectorAllowed(): Boolean {
        ensureInitialized()
        val filtered = config.dashboardEntityFilterEnabled
        val held = shouldHoldRendererForEntityBootstrap(config)
        return strategySelectorAllowed(
            entityStreamMode(held, filtered),
            visibleDashboardIssues(store.issuesJson(instance(), dashboardPath()), showAdvisories = false),
        )
    }

    fun issuesJson(): String {
        ensureInitialized()
        return visibleDashboardIssuesJson(
            storedIssuesJson = store.issuesJson(instance(), dashboardPath()),
            showAdvisories = !config.dashboardEntityAutoRuntime,
            dynamicExpressionsJson = dynamicExpressionsJson,
        )
    }

    /** Cheap cached evidence for the shared app database; does not start entity synchronization. */
    fun databaseUsage(): EntityCatalogStore.DatabaseUsage = store.databaseUsage()

    /** Service-owned startup/daily health check; teardown cancellation reaches SQLite quick_check. */
    fun storageHealthObservation(cancellationSignal: CancellationSignal? = null): StorageHealthObservation =
        store.storageHealthObservation(cancellationSignal)

    // Storage remediation steps, owned by the service's serialized storage-health lifecycle.
    fun enforceHistoryRetention(): Boolean = store.enforceRetention()
    fun truncateDatabaseWal(): WalCheckpointOutcome = store.truncateWal()
    fun writeVerifiedConfigurationBackup(): Boolean = store.writeVerifiedConfigurationBackup()
    fun vacuumDatabase(): VacuumOutcome = store.vacuumDatabase()

    @Synchronized fun setIssueIgnored(fingerprint: String, ignored: Boolean): String {
        ensureInitialized()
        val targetInstance = instance()
        val targetPath = dashboardPath()
        invalidateEffects()
        require(store.setIssueIgnored(targetInstance, targetPath, fingerprint, ignored, System.currentTimeMillis())) {
            "dashboard issue is no longer present"
        }
        bootstrapBlockingIssues = store.snapshot(targetInstance, targetPath).blockingIssueCount
        val syncStarted = syncNow(if (ignored) "ignore-issue" else "restore-issue")
        return JSONObject().put("ok", true).put("fingerprint", fingerprint)
            .put("ignored", ignored).put("sync_started", syncStarted).toString()
    }

    @Synchronized fun ignoreAllBlockingIssues(): Boolean {
        ensureInitialized()
        val targetInstance = instance()
        val targetPath = dashboardPath()
        val selection = blockingIssueSelection(store.issuesJson(targetInstance, targetPath))
        if (!selection.allIgnorable) return false
        invalidateEffects()
        val now = System.currentTimeMillis()
        check(selection.fingerprints.all { store.setIssueIgnored(targetInstance, targetPath, it, true, now) }) {
            "dashboard issues changed while applying overrides"
        }
        bootstrapBlockingIssues = store.snapshot(targetInstance, targetPath).blockingIssueCount
        return syncNow("ignore-blocking-issues")
    }

    fun canIgnoreAllBlockingIssues(): Boolean {
        if (!initialized) return false
        return blockingIssueSelection(store.issuesJson(instance(), dashboardPath())).allIgnorable
    }

    private fun encodeDynamicExpressions(expressions: List<EntityLearningProtocol.DynamicExpression>): String =
        JSONArray(expressions.take(MAX_DYNAMIC_EXPRESSIONS).map(EntityLearningProtocol.DynamicExpression::toJson)).toString()

    fun entitiesJson(
        query: String,
        filter: String,
        limit: Int,
        offset: Int,
        sort: String = "entity_id",
        direction: String = "asc",
    ): String {
        ensureInitialized()
        val subscribed = filter == "subscribed"
        val review = filter == "review"
        val filtered = config.dashboardEntityFilterEnabled
        val held = shouldHoldRendererForEntityBootstrap(config)
        val effectiveFilter = when {
            filter == "candidate" && query.isBlank() -> "candidate"
            filter == "candidate" -> "unpinned"
            subscribed -> "all"
            else -> filter
        }
        val currentIds = if (filtered) config.dashboardEntityFilterIds.toSet() else emptySet()
        val json = JSONObject(store.entitiesJson(
            instance(), dashboardPath(), query, effectiveFilter, limit, offset,
            sortKey = sort,
            sortDirection = direction,
            includeIds = entityQueryIncludeIds(
                subscribed = subscribed,
                review = review,
                held = held,
                filtered = filtered,
                activeIds = config.dashboardEntityFilterIds.toSet(),
                pinnedIds = config.dashboardEntityOverrides.filterValues { it == "pinned" }.keys,
            ),
            excludeIds = if (filter == "candidate") currentIds else emptySet(),
        ))
        if (subscribed) {
            val catalogCount = store.snapshot(instance(), dashboardPath()).catalogCount
            json.put("stream_mode", entityStreamMode(held, filtered))
                .put("configured_count", if (filtered) config.dashboardEntityFilterIds.size else 0)
                .put("catalog_present_count", when { held -> 0; filtered -> json.optInt("total"); else -> catalogCount })
        }
        return json.toString()
    }

    fun performanceSummaryJson(): String {
        if (!config.dashboardEntityLearningEnabled) return "[]"
        ensureInitialized()
        val now = System.currentTimeMillis()
        val targetInstance = instance()
        val targetPath = dashboardPath()
        val target = "$targetInstance\n$targetPath"
        if (target == performanceSummaryTarget &&
            now >= performanceSummaryAt && now - performanceSummaryAt < PERFORMANCE_SUMMARY_CACHE_MS
        ) {
            return performanceSummary
        }
        return synchronized(this) {
            val lockedNow = System.currentTimeMillis()
            if (target == performanceSummaryTarget && lockedNow >= performanceSummaryAt &&
                lockedNow - performanceSummaryAt < PERFORMANCE_SUMMARY_CACHE_MS
            ) performanceSummary else runCatching {
                store.performanceSummaryJson(targetInstance, targetPath, lockedNow)
            }.getOrDefault("[]").also {
                performanceSummary = it
                performanceSummaryAt = lockedNow
                performanceSummaryTarget = target
            }
        }
    }

    fun writeExportJson(writer: java.io.Writer) {
        ensureInitialized()
        store.writeExportJson(instance(), dashboardPath(), writer)
    }

    private fun desiredIds(
        now: Long,
        instance: String = instance(),
        path: String = dashboardPath(),
        includeStatic: Boolean = config.dashboardEntityAutoStatic,
        includeRuntime: Boolean = config.dashboardEntityAutoRuntime,
    ): List<String> = store.activeIds(
        instance, path, now,
        includeStatic = includeStatic,
        includeRuntime = includeRuntime,
    )

    private fun subscriptionPreview(
        desired: List<String>,
        catalogCount: Int = store.snapshot(instance(), dashboardPath()).catalogCount,
    ): EntitySubscriptionPreview {
        val filtered = config.dashboardEntityFilterEnabled
        return previewEntitySubscription(
            filtered = filtered,
            currentIds = config.dashboardEntityFilterIds,
            catalogCount = catalogCount,
            desiredIds = desired,
        )
    }

    private fun instance(): String {
        val base = config.haUrl.takeIf(String::isNotBlank) ?: return UNCONFIGURED_INSTANCE
        return config.dashboardEntityInstanceKey.ifBlank { EntityLearningProtocol.hash(EntityFilterProtocol.origin(base)) }
    }
    /**
     * The dashboard this panel's learned state belongs to.
     *
     * `Auto` names no dashboard: it means "whatever this Home Assistant account's default resolves to",
     * a value only an authenticated read can supply. Treating it as its own scope discarded the learned
     * filter every time the setting moved between an explicit dashboard and Auto, including when the
     * default resolved to the dashboard already in use. So Auto keeps the scope a previous resolution
     * bound, and [reconcileResolvedScope] corrects it the moment a resolution disagrees.
     */
    private fun dashboardPath(): String {
        val configured = config.homeDashboard
        // ONE namespace per dashboard, in both modes. Ownership already roots the path, but this value
        // is also the raw catalogue key, and returning the configured ROUTE here gave `/lovelace/kiosk`
        // and an `Auto` that resolves to the same dashboard two different namespaces. Switching between
        // them orphaned catalogue rows and later revived them — including runtime observations and
        // ignored-issue decisions, which a rescan cannot exactly recreate because they record what the
        // panel saw and what a person decided, not what the document says.
        if (!io.panelassistant.android.util.DashboardPath.followsAccountDefault(configured)) return dashboardEntityScopePath(configured)
        return config.dashboardEntityDashboardPath.takeIf { it.isNotBlank() } ?: "/"
    }

    /**
     * Bind the learned scope to the dashboard an authenticated read actually resolved to, and abandon
     * the current scan when that is not the dashboard it was scoped for.
     *
     * The scope has to be decided before the scan can run, but under Auto the answer only arrives part
     * way through it. Rebinding here rather than mid-commit keeps the existing guards honest: the scan's
     * own `stillCurrent` check reads the target key, so once this rebinds nothing from this pass can be
     * committed against the wrong dashboard. [syncNow] latches a rerun rather than starting a nested
     * one, so the corrected scope is scanned as soon as this pass unwinds.
     *
     * Catalogue rows recorded under the superseded scope are left behind deliberately. A rescan
     * reproduces them exactly, so migrating them would be code carrying risk for no gain.
     */
    private fun reconcileResolvedScope(snapshot: EntityLearningSyncSnapshot, resolved: String): Boolean {
        if (!resolvedScopeRequiresRebind(snapshot.configuredHomeDashboard, snapshot.dashboardPath, resolved)) return false
        val origin = snapshot.state.origin
        // The currency checks inside the resolve above guard the READ. This is a durable write, and it
        // happens later: between the two, the endpoint or the configured dashboard can change under a
        // cooperatively cancelled pass, and rebinding on a stale snapshot would overwrite the newer
        // selection with an older one. Re-verify the exact snapshot this decision was made from, and
        // abandon rather than retarget when it no longer describes the panel.
        if (!resolvedScopeRebindIsStillCurrent(
                snapshotConfigured = snapshot.configuredHomeDashboard,
                currentConfigured = config.homeDashboard.trim(),
                snapshotOrigin = origin,
                currentOrigin = EntityFilterProtocol.origin(config.haUrl.trim().trimEnd('/')),
                generationMatches = snapshot.matchesCurrent(effectGeneration.get(), currentEffectState()),
            )
        ) {
            return false
        }
        config.prepareDashboardEntityInstance(origin, resolved, EntityLearningProtocol.hash(origin))
            ?: error("failed to rebind entity-learning scope to the resolved dashboard")
        syncNow("auto-resolved-scope")
        return true
    }

    private fun prepareCurrentTarget(): String? {
        val base = config.haUrl.trim().trimEnd('/')
        if (base.isBlank()) return null
        val origin = EntityFilterProtocol.origin(base)
        val path = dashboardPath()
        val legacyKey = EntityLearningProtocol.hash(origin)
        return config.prepareDashboardEntityInstance(origin, path, legacyKey)
            ?: error("failed to prepare entity-learning target")
    }

    private fun invalidateEffects(cancelSync: Boolean = true) {
        synchronized(this) {
            // Whatever invalidated the effects — endpoint, credentials, the dashboard setting — re-opens
            // the question this verification answered, so it stops vouching for the retained list and the
            // renderer holds again until a fresh read lands.
            AutoScopeVerification.invalidate()
            effectGeneration.incrementAndGet()
            promotionJob?.cancel()
            promotionJob = null
            if (cancelSync) {
                syncJob = supersedeEntityLearningSync(syncJob) { syncRerunReason = null }
                probeJob?.cancel()
                probeJob = null
            }
        }
    }

    /**
     * Fetch, in one authenticated session and without starting an entity scan: the built-in panel
     * dashboards (`get_panels`), the user-created dashboards (`lovelace/dashboards/list`), and the
     * account's server-side default dashboard (`frontend/get_user_data` → `frontend/get_system_data`,
     * where Home Assistant ≥ 2025.12 persists the profile picker's choice). Failures reduce to an empty
     * catalog — the caller must treat that as "could not ask", never as "no dashboards".
     */
    internal suspend fun homeDashboardCatalog(
        stillCurrent: () -> Boolean = { true },
    ): HomeDashboardCatalog = withContext(Dispatchers.IO) {
        if (!stillCurrent()) return@withContext HomeDashboardCatalog()
        val configuredBase = config.haUrl.trim().trimEnd('/')
        if (configuredBase.isBlank()) return@withContext HomeDashboardCatalog()
        val auth = HaCredentialManager.resolve(config, stillCurrent = stillCurrent)
        val token = auth.session?.accessToken ?: return@withContext HomeDashboardCatalog()
        val route = auth.route ?: return@withContext HomeDashboardCatalog()
        val base = route.url
        runCatching {
            var catalog = HomeDashboardCatalog()
            withHaSocket(config, base, token, route) { request ->
                catalog = readHomeDashboardCatalog(request)
            }
            check(stillCurrent() && HaConnectionRoutes.isCurrent(config, route)) { "home-dashboard authority changed during catalog read" }
            catalog
        }.getOrDefault(HomeDashboardCatalog())
    }

    /** Resolve from the authenticated legal list without initializing or reading entity-learning state. */
    internal suspend fun resolveHomeDashboard(
        homeDashboard: String,
        stillCurrent: () -> Boolean = { true },
        forceLive: Boolean = false,
    ): EntityLearningProtocol.HomeDashboardResolution? = homeDashboardAuthority.resolve(
        homeDashboardAuthorityKey(homeDashboard),
        stillCurrent,
        forceLive,
    ) {
        homeDashboardResolution(homeDashboard, stillCurrent)
    }

    private suspend fun homeDashboardResolution(
        homeDashboard: String,
        stillCurrent: () -> Boolean,
    ): EntityLearningProtocol.HomeDashboardResolution? = withContext(Dispatchers.IO) {
        if (!stillCurrent()) return@withContext null
        val configuredBase = config.haUrl.trim().trimEnd('/')
        if (configuredBase.isBlank()) return@withContext null
        val auth = HaCredentialManager.resolve(config, stillCurrent = stillCurrent)
        val token = auth.session?.accessToken ?: return@withContext null
        val route = auth.route ?: return@withContext null
        val base = route.url
        runCatching {
            var resolution: EntityLearningProtocol.HomeDashboardResolution? = null
            withHaSocket(config, base, token, route) { request ->
                resolution = readHomeDashboardResolution(request, homeDashboard)
            }
            check(stillCurrent() && HaConnectionRoutes.isCurrent(config, route)) { "home-dashboard authority changed during resolution" }
            resolution
        }.getOrNull()
    }

    private fun homeDashboardAuthorityKey(homeDashboard: String) =
        HomeDashboardResolutionAuthority.Key(
            baseUrl = config.haUrl.trim().trimEnd('/'),
            authOwner = config.haAuthSnapshot().stableOwner(),
            configuredPath = homeDashboard.trim(),
        )

    private fun currentEffectState(): EntityLearningEffectState = EntityLearningEffectState(
        origin = runCatching { EntityFilterProtocol.origin(config.haUrl) }.getOrDefault(""),
        routeEpoch = HaConnectionRoutes.current(config).epoch,
        instanceKey = instance(),
        targetKey = config.dashboardEntityTargetKey,
        dashboardPath = dashboardPath(),
        credentialFingerprint = credentialFingerprint(config),
        learningEnabled = config.dashboardEntityLearningEnabled,
        applied = config.dashboardEntityLearningApplied,
        filterEnabled = config.dashboardEntityFilterEnabled,
        filterIds = config.dashboardEntityFilterIds,
        autoStatic = config.dashboardEntityAutoStatic,
        autoRuntime = config.dashboardEntityAutoRuntime,
        overrides = config.dashboardEntityOverrides,
        forceBootstrap = resetBootstrapPending,
        initialActivationPending = config.dashboardEntityInitialActivationPending,
        upgradeRebootstrapPending = config.dashboardEntityDefaultResolverRebootstrapPending,
    )

    private suspend fun captureAuthenticatedTarget(): AuthenticatedTarget {
        val generation = effectGeneration.get()
        val base = config.haUrl.trim().trimEnd('/')
        require(base.isNotBlank()) { "Home Assistant URL is not configured" }
        val origin = EntityFilterProtocol.origin(base)
        val configuredHomeDashboard = config.homeDashboard.trim()
        val path = dashboardPath()
        val legacyKey = EntityLearningProtocol.hash(origin)
        val selected = config.prepareDashboardEntityInstance(origin, path, legacyKey)
            ?: error("failed to prepare entity-learning target")
        val targetKey = config.dashboardEntityTargetKey
        val ownerBeforeAuthentication = config.haAuthSnapshot().stableOwner()
        val stillCurrent = {
            generation == effectGeneration.get() &&
                runCatching { EntityFilterProtocol.origin(config.haUrl) }.getOrNull() == origin &&
                config.haAuthSnapshot().stableOwner() == ownerBeforeAuthentication &&
                config.homeDashboard.trim() == configuredHomeDashboard && dashboardPath() == path &&
                config.dashboardEntityInstanceKey == selected &&
                config.dashboardEntityTargetKey == targetKey
        }
        val auth = HaCredentialManager.resolve(config, stillCurrent = stillCurrent)
        val route = auth.route ?: error("Home Assistant route unavailable")
        val token = auth.session?.accessToken
            ?: error(if (auth.rejected) "Home Assistant credential rejected" else TOKEN_UNAVAILABLE)
        check(stillCurrent() && HaConnectionRoutes.isCurrent(config, route)) { "entity-learning target changed during authentication" }
        val ownedAuthSnapshot = config.haAuthSnapshot()
        val authenticatedAuthority = ownedAuthenticatedHomeDashboardAuthority(
            base,
            configuredHomeDashboard,
            ownerBeforeAuthentication,
            ownedAuthSnapshot,
            token,
        ) ?: error("Home Assistant credential changed during authentication")
        check(config.haAuthSnapshot() == ownedAuthSnapshot) {
            "Home Assistant credential changed after authentication"
        }
        return AuthenticatedTarget(
            generation, route.url, origin, configuredHomeDashboard, path, legacyKey, selected, targetKey,
            token, authenticatedAuthority.credentialFingerprint, authenticatedAuthority.key, route,
        )
    }

    private fun adoptStableTarget(target: AuthenticatedTarget, uuid: String): AuthenticatedTarget {
        if (!HaConnectionRoutes.isCurrent(config, target.route)) throw EntityLearningRouteChanged()
        check(target.generation == effectGeneration.get()) { "entity-learning target changed during discovery" }
        check(EntityFilterProtocol.origin(config.haUrl) == target.origin && dashboardPath() == target.dashboardPath) {
            "entity-learning target changed during discovery"
        }
        check(credentialFingerprint(config) == target.credentialFingerprint) { "Home Assistant credential changed during discovery" }
        val stableKey = EntityLearningProtocol.hash("ha-instance:${uuid.lowercase()}")
        check(config.adoptDashboardEntityInstance(
            target.origin, target.dashboardPath, uuid, target.legacyKey, stableKey,
        )) { "Home Assistant identity changed during discovery" }
        return target.copy(
            instanceKey = config.dashboardEntityInstanceKey,
            targetKey = config.dashboardEntityTargetKey,
        )
    }

    private fun captureSyncSnapshot(target: AuthenticatedTarget): EntityLearningSyncSnapshot {
        if (!HaConnectionRoutes.isCurrent(config, target.route)) throw EntityLearningRouteChanged()
        check(target.generation == effectGeneration.get()) { "entity-learning target changed before scan" }
        val state = currentEffectState()
        check(state.origin == target.origin && state.instanceKey == target.instanceKey &&
            state.targetKey == target.targetKey && state.dashboardPath == target.dashboardPath &&
            state.credentialFingerprint == target.credentialFingerprint
        ) { "entity-learning target or credential changed before scan" }
        return EntityLearningSyncSnapshot(
            target.generation,
            target.baseUrl,
            target.authToken,
            target.configuredHomeDashboard,
            target.homeDashboardAuthorityKey,
            state,
            target.route,
        )
    }

    private fun capturePromotionSnapshot(instance: String, path: String): EntityLearningPromotionSnapshot {
        val state = currentEffectState()
        return EntityLearningPromotionSnapshot(effectGeneration.get(), state).also {
            check(state.instanceKey == instance && state.dashboardPath == path) { "entity-learning target changed" }
        }
    }

    private data class AuthenticatedTarget(
        val generation: Long,
        val baseUrl: String,
        val origin: String,
        val configuredHomeDashboard: String,
        val dashboardPath: String,
        val legacyKey: String,
        val instanceKey: String,
        val targetKey: String,
        val authToken: String,
        val credentialFingerprint: String,
        val homeDashboardAuthorityKey: HomeDashboardResolutionAuthority.Key,
        val route: HaConnectionRoute,
    )

    private data class WsSnapshot(
        val configJson: String,
        val metadata: Map<String, String>,
        val areaRegistryEntities: Map<String, Set<String>>,
        val registryMetadataComplete: Boolean,
    )

    private suspend fun fetchStates(base: String, token: String, route: HaConnectionRoute): List<EntityCatalogStore.StateRow> =
        runInterruptible(Dispatchers.IO) {
            val generation = effectGeneration.get()
            requireCurrentHaRoute(config, base, token, route)
            val cost = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_STATES_FETCH_PARSE)
            val c = (URL(base.trimEnd('/') + "/api/states").openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = HTTP_TIMEOUT_MS; readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("Accept", "application/json")
            }
            try {
                if (c.responseCode !in 200..299) {
                    c.closeBody()
                    error("states request failed: HTTP ${c.responseCode}")
                }
                // /api/states includes every attribute payload. Stream over it without hydrating the
                // attributes, but reject a response that exceeds any resource or wall-clock bound.
                val limits = HaStatesReadLimits()
                c.inputStream.use { body ->
                    if (c.contentLengthLong > limits.maxBytes) throw ByteLimitExceeded(limits.maxBytes)
                    val counted = CountingInputStream(body)
                    scanCountedRows = 0
                    scanGeneration = generation
                    scanCounting = true
                    readHaStates(counted, limits, onProgress = { scanCountedRows = it }).also {
                        cost.work(units = it.size.toLong(), bytes = counted.bytesRead)
                    }
                }
            } catch (failure: Throwable) {
                scanCountedRows = -1
                cost.outcome(failure.featureCostOutcome())
                throw failure
            } finally {
                // Clear on every exit, success or failure: a stuck "still counting" would leave the wizard
                // waiting for a number that is never coming, which is worse than showing none.
                scanCounting = false
                cost.close()
                c.disconnect()
            }
        }

    private fun fetchHaConfig(base: String, token: String, route: HaConnectionRoute): String {
        requireCurrentHaRoute(config, base, token, route)
        val c = (URL(base.trimEnd('/') + "/api/config").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = HA_CONFIG_TIMEOUT_MS
            readTimeout = HA_CONFIG_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode !in 200..299) {
                c.closeBody()
                error("config request failed: HTTP ${c.responseCode}")
            }
            return c.inputStream.use {
                String(BoundedStreams.readBytes(it, MAX_HA_CONFIG_BYTES), Charsets.UTF_8)
            }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun fetchDashboardAndRegistry(snapshot: EntityLearningSyncSnapshot): WsSnapshot {
        val base = snapshot.baseUrl
        val token = snapshot.authToken
        val homeDashboard = snapshot.configuredHomeDashboard
        var configJson: String? = null
        val metadata = mutableMapOf<String, String>()
        var areaRegistryEntities: Map<String, Set<String>> = emptyMap()
        var registryMetadataComplete = false
        withHaSocket(config, base, token, checkNotNull(snapshot.route)) { request ->
            // The scanner and renderer must never independently choose different defaults. The first
            // complete authenticated resolution owns this HA/config authority until that authority
            // changes; every scan consumes the exact same legal dashboard path.
            val resolved = homeDashboardAuthority.resolve(
                snapshot.homeDashboardAuthorityKey,
                stillCurrent = {
                    config.homeDashboard.trim() == snapshot.configuredHomeDashboard &&
                        snapshot.matchesCurrent(effectGeneration.get(), currentEffectState())
                },
                // A scan is the thing that promises to notice the account default moving. Reusing a
                // memo whose key cannot express that move would make the promise unkeepable, and a
                // restart would resume filtering the newly-opened dashboard through the previous
                // dashboard's allow-list until something else happened to invalidate the process.
                forceLive = homeDashboardResolutionMustBeLive(snapshot.configuredHomeDashboard),
            ) {
                readHomeDashboardResolution(request, homeDashboard)
            }?.path ?: error("Home Assistant reported no legal dashboard")
            // A live answer exists now, so the retained allow-list is no longer attributable to an
            // unknown dashboard and document-start interception may stop holding. Recorded BEFORE the
            // rebind below, because a rebind abandons this pass and the fact being recorded — that the
            // account default has been read this process — is true either way. A failed or still-running
            // resolution never reaches here, which is what keeps the renderer held.
            AutoScopeVerification.markVerified(snapshot.configuredHomeDashboard)
            // Under Auto the scope was a standing guess until this moment. If the account default
            // resolved to a different dashboard, this pass is scoped to the wrong document: rebind and
            // let the latched rerun scan the right one rather than mixing two dashboards' evidence.
            if (reconcileResolvedScope(snapshot, resolved)) {
                error("entity-learning scope retargeted to the resolved dashboard")
            }
            configJson = readDashboardConfig(request, resolved)
            val dashboard = configJson ?: error("dashboard configuration unavailable")
            val requirements = DashboardConfigurationLint.registryRequirements(dashboard)
            // Fetch only the ancestry the stored dashboard can consume. The dashboard comes first:
            // ordinary HA saves therefore establish referenced registry objects before this later,
            // still-eventual snapshot, and dashboards without registry-backed cards pay no registry cost.
            val registry = if (requirements.entities) runCatching {
                request(JSONObject().put("type", "config/entity_registry/list_for_display"))
            }.getOrNull() else null
            val areaResponse = if (requirements.areas) runCatching {
                request(JSONObject().put("type", "config/area_registry/list"))
            }.getOrNull() else null
            val deviceResponse = if (requirements.devices) runCatching {
                request(JSONObject().put("type", "config/device_registry/list"))
            }.getOrNull() else null
            val floorResponse = if (requirements.floors) runCatching {
                request(JSONObject().put("type", "config/floor_registry/list"))
            }.getOrNull() else null
            val labelResponse = if (requirements.labels) runCatching {
                request(JSONObject().put("type", "config/label_registry/list"))
            }.getOrNull() else null
            val projection = projectDashboardRegistries(
                registry, areaResponse, deviceResponse, floorResponse, labelResponse, requirements,
            )
            metadata.putAll(projection.metadata)
            areaRegistryEntities = projection.areaRegistryEntities
            registryMetadataComplete = projection.complete
        }
        return WsSnapshot(
            configJson ?: error("dashboard configuration unavailable"),
            metadata,
            areaRegistryEntities,
            registryMetadataComplete,
        )
    }

    private suspend fun expandTargets(base: String, token: String, targets: List<String>, route: HaConnectionRoute): Set<String> {
        if (targets.isEmpty()) return emptySet()
        val out = linkedSetOf<String>()
        withHaSocket(config, base, token, route) { request ->
            for (target in targets.take(MAX_TARGETS)) {
                // Dashboard configuration is extensible and third-party cards sometimes put selector
                // syntax in target-shaped fields. A single target rejected by HA must not discard the
                // complete catalogue/static scan; unresolved dependencies can still be learned at runtime.
                val response = runCatching {
                    request(JSONObject().put("type", "extract_from_target").put("target", JSONObject(target)).put("expand_group", true))
                }.getOrNull() ?: continue
                val entities = response.optJSONObject("result")?.optJSONArray("referenced_entities") ?: continue
                for (i in 0 until entities.length()) {
                    val id = entities.optString(i).lowercase()
                    if (EntityFilterProtocol.isEntityId(id)) out += id
                    require(out.size <= EntityFilterProtocol.MAX_ENTITY_IDS) {
                        "expanded target set exceeds ${EntityFilterProtocol.MAX_ENTITY_IDS} IDs"
                    }
                }
            }
        }
        return out
    }

    companion object {
        private const val TAG = "EntityLearning"
        private const val HTTP_TIMEOUT_MS = 30_000
        private const val HA_CONFIG_TIMEOUT_MS = 10_000
        private const val MAX_HA_CONFIG_BYTES = 256L * 1024
        private const val SYNC_TIMEOUT_MS = 120_000L
        private const val PROBE_TIMEOUT_MS = 60_000L
        private const val MAX_TARGETS = 500
        private const val HOURLY_CHECK_MS = 60L * 60_000
        private const val DAILY_SYNC_MS = 24L * HOURLY_CHECK_MS
        // Long enough for the first filtered load's own telemetry to finish arriving (it is what
        // triggers promotion in the first place); with trailing-edge coalescing in queuePromotion this
        // trades two visible dashboard rebuilds for one fuller one. Promotions are never urgent.
        private const val PROMOTION_DEBOUNCE_MS = 20_000L
        private const val PROMOTION_WINDOW_MS = 10L * 60_000
        private const val MAX_PROMOTIONS = 2
        private const val PROMOTION_CAP_ERROR = "runtime dependencies queued; synchronize manually"
        private const val MAX_DYNAMIC_EXPRESSIONS = 128
        private const val MAX_TELEMETRY_IDS = EntityFilterProtocol.MAX_ENTITY_IDS
        private const val PERFORMANCE_SUMMARY_CACHE_MS = 10_000L
        private const val UNCONFIGURED_INSTANCE = "unconfigured"
    }
}

/** Non-secret digest identifying the HA endpoint and credential generation of the current session. */
internal fun credentialFingerprint(config: Config): String {
    val snapshot = config.haAuthSnapshot()
    return ownedAuthenticatedHomeDashboardAuthority(
        snapshot.url.trim().trimEnd('/'),
        config.homeDashboard.trim(),
        snapshot.stableOwner(),
        snapshot,
        snapshot.accessToken,
    )?.credentialFingerprint ?: error("credential snapshot changed while reading")
}

/** Every request a scan makes checks its route here; a moved route or credential reruns the scan once. */
internal fun requireCurrentHaRoute(config: Config, base: String, token: String, route: HaConnectionRoute) {
    if (route.url != base || !HaConnectionRoutes.isCurrent(config, route) ||
        config.haAuthSnapshot().accessToken != token
    ) throw EntityLearningRouteChanged()
}

internal suspend fun withHaSocket(
    config: Config,
    base: String,
    token: String,
    route: HaConnectionRoute,
    block: suspend (suspend (JSONObject) -> JSONObject) -> Unit,
) {
    requireCurrentHaRoute(config, base, token, route)
    check(HaConnectionRoutes.verify(config, route)) { "Home Assistant instance unavailable" }
    val ws = EntityFilterProtocol.upstreamWebSocketUrl(base)
    val policy = AddressFamilyPolicy.fromConfig(config.addressFamily)
    val client = HaWebSocketClients.client(
        preferIpv4 = policy.initialPreferIpv4,
        ipv4Only = policy.ipv4Only,
    )
    var session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null
    try {
        val activeSession = withEntityLearningDeadline(WS_CONNECT_TIMEOUT_MS) { HaWebSocketClients.open(client, ws, MAX_WS_FRAME) }
        session = activeSession
        withEntityLearningDeadline(WS_SOCKET_TIMEOUT_MS) {
            with(activeSession) {
                withEntityLearningDeadline(WS_AUTH_TIMEOUT_MS) {
                    (incoming.receive() as? Frame.Text)?.readText()
                    requireCurrentHaRoute(config, base, token, route)
                    send(Frame.Text(JSONObject().put("type", "auth").put("access_token", token).toString()))
                    val auth = (incoming.receive() as? Frame.Text)?.readText().orEmpty()
                    require(auth.contains("\"type\":\"auth_ok\"")) { "Home Assistant WebSocket authentication failed" }
                }
                var id = 0
                suspend fun request(command: JSONObject): JSONObject = withEntityLearningDeadline(WS_REQUEST_TIMEOUT_MS) {
                    requireCurrentHaRoute(config, base, token, route)
                    val current = ++id; command.put("id", current); send(Frame.Text(command.toString()))
                    repeat(MAX_RESPONSE_FRAMES) {
                        val frame = incoming.receive() as? Frame.Text ?: return@repeat
                        val obj = JSONObject(frame.readText())
                        if (obj.optInt("id") == current && obj.optString("type") == "result") {
                            require(obj.optBoolean("success")) { obj.optJSONObject("error")?.optString("message") ?: "HA command failed" }
                            return@withEntityLearningDeadline obj
                        }
                    }
                    error("Home Assistant command exceeded response frame limit")
                }
                block(::request)
            }
        }
    } finally {
        runCatching { session?.close() }
        client.close()
    }
}

private const val MAX_WS_FRAME = 32L * 1024 * 1024
private const val WS_CONNECT_TIMEOUT_MS = 15_000L
private const val WS_AUTH_TIMEOUT_MS = 15_000L
private const val WS_REQUEST_TIMEOUT_MS = 20_000L
private const val WS_SOCKET_TIMEOUT_MS = 90_000L
private const val MAX_RESPONSE_FRAMES = 32

private fun Throwable.featureCostOutcome(): FeatureCostOutcome =
    if (this is CancellationException) FeatureCostOutcome.CANCELLED else FeatureCostOutcome.FAILURE

internal fun runEntityOverrideTransaction(
    commitOverridePreferences: () -> Boolean,
    applyStoreOverride: () -> Unit,
    commitActiveFilter: () -> Boolean,
    restoreStoreOverride: () -> Unit,
    restoreOverridePreferences: () -> Boolean,
) {
    check(commitOverridePreferences()) { "override preference commit failed" }
    try {
        applyStoreOverride()
        check(commitActiveFilter()) { "active entity filter commit failed" }
    } catch (failure: Throwable) {
        runCatching { restoreStoreOverride() }.exceptionOrNull()?.let(failure::addSuppressed)
        runCatching { check(restoreOverridePreferences()) { "override preference rollback failed" } }
            .exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

/** Preferences and SQLite cannot share one transaction. The SQLite reset is itself transactional;
 * restore the exact preference snapshot if it rolls back so a failed reset publishes no partial
 * durable state. */
internal fun runEntityEvidenceResetTransaction(
    commitPreferences: () -> Boolean,
    resetStore: () -> Unit,
    restorePreferences: () -> Boolean,
    afterSuccess: () -> Unit,
) {
    check(commitPreferences()) { "entity evidence reset preference commit failed" }
    try {
        resetStore()
    } catch (failure: Throwable) {
        runCatching { check(restorePreferences()) { "entity evidence reset preference rollback failed" } }
            .exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
    afterSuccess()
}

/** Candidate aliases are accepted only from the authenticated, bounded `/api/config` response. */
internal fun haInstanceCandidateUrls(configuredBase: String, configJson: String?): List<String> = buildList {
    add(configuredBase)
    val config = configJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@buildList
    for (key in listOf("internal_url", "external_url", "base_url")) {
        val candidate = config.optString(key).trim()
        if (candidate.length in 1..MAX_HA_CANDIDATE_URL_CHARS &&
            runCatching { EntityFilterProtocol.origin(candidate) }.isSuccess
        ) add(candidate)
    }
}.distinct()

private const val MAX_HA_CANDIDATE_URL_CHARS = 2_048

internal fun recordBrowserObserverCosts(
    registry: FeatureCostRegistry,
    observer: EntityLearningProtocol.BrowserObserverCosts,
) {
    registry.recordExternal(
        FeatureCostOperation.ENTITY_BROWSER_OBSERVER,
        externalExecutionNanos = (observer.parseMicros + observer.stringifyMicros) * 1_000L,
        events = observer.frames,
        inputChars = observer.frameChars,
        workUnits = observer.entities,
    )
    registry.recordDropped(FeatureCostOperation.ENTITY_BROWSER_OBSERVER, observer.dropped)
    registry.recordCoalesced(FeatureCostOperation.ENTITY_BROWSER_OBSERVER, observer.coalesced)
}
