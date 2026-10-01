package io.github.maxlyth.hapaneld.dashboard

import android.content.Context
import android.os.CancellationSignal
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.dashboardEntityScopePath
import io.github.maxlyth.hapaneld.DashboardEntityDefaultResolverMigration
import io.github.maxlyth.hapaneld.HaConnectionRoute
import io.github.maxlyth.hapaneld.HaConnectionRoutes
import io.github.maxlyth.hapaneld.HaCredentialManager
import io.github.maxlyth.hapaneld.HaAuthOwner
import io.github.maxlyth.hapaneld.HaAuthSnapshot
import io.github.maxlyth.hapaneld.stableOwner
import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCostRegistry
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.storage.StorageHealthObservation
import io.github.maxlyth.hapaneld.storage.VacuumOutcome
import io.github.maxlyth.hapaneld.storage.WalCheckpointOutcome
import io.github.maxlyth.hapaneld.util.BoundedStreams
import io.github.maxlyth.hapaneld.util.ByteLimitExceeded
import io.github.maxlyth.hapaneld.util.HaWebSocketClients
import io.github.maxlyth.hapaneld.mqtt.MqttAddressFamilyPolicy
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.io.Reader
import java.util.concurrent.atomic.AtomicLong

internal data class HomeDashboardCatalog(
    /** True only when the authenticated dashboard-list request completed; empty items can then mean zero. */
    val queried: Boolean = false,
    val items: List<EntityLearningProtocol.HomeDashboardChoice> = emptyList(),
    val userDefault: String? = null,
    val systemDefault: String? = null,
    val default: EntityLearningProtocol.HomeDashboardDefault = EntityLearningProtocol.HomeDashboardDefault(),
)

/** One process-local answer shared by the renderer and entity scanner for the same HA authority. */
internal class HomeDashboardResolutionAuthority {
    internal data class Key(
        val baseUrl: String,
        val authOwner: HaAuthOwner,
        val configuredPath: String,
    )

    private data class Cached(
        val key: Key,
        val resolution: EntityLearningProtocol.HomeDashboardResolution,
    )

    private val mutex = Mutex()
    private var cached: Cached? = null

    /**
     * [forceLive] bypasses the memo for this call and replaces it with what the account answers now.
     *
     * The key is endpoint, credential owner and CONFIGURED path — and under `Auto` none of those change
     * when the Home Assistant account's default dashboard changes. A process that had already resolved
     * once would therefore return the superseded answer for its whole lifetime, so the scan that
     * promises to reconcile the scope could never observe the disagreement it exists to correct. Any
     * caller that promises a live answer, rather than a consistent one within a single pass, must ask
     * for one.
     */
    suspend fun resolve(
        key: Key,
        stillCurrent: () -> Boolean,
        /** Skip the process-local answer and read Home Assistant again, then republish the result.
         *  The launch cache refreshes behind an already rendering provisional page and PROMISES a
         *  live answer: reusing this cache there would silently miss an account-default change, a
         *  removed dashboard or a revoked access across a warm activity relaunch. */
        forceLive: Boolean = false,
        read: suspend () -> EntityLearningProtocol.HomeDashboardResolution?,
    ): EntityLearningProtocol.HomeDashboardResolution? = mutex.withLock {
        cached?.takeIf { !forceLive && it.key == key }?.resolution?.takeIf { stillCurrent() } ?: run {
            if (!stillCurrent()) return@withLock null
            // A null READ is a transient failure (socket, auth, cancellation) and changes nothing:
            // the previous positive answer stays, so a flaky moment cannot erase a working target.
            val resolution = read() ?: return@withLock null
            if (!stillCurrent()) return@withLock null
            // A completed read reporting NO legal dashboard is authoritative for the account, so it
            // must also RETIRE any positive answer this process is still holding. Merely declining
            // to cache the zero left the stale positive in place, where an ordinary later resolve
            // replayed it and the launch cache persisted and opened a path the account cannot reach.
            // The zero itself is still not cached: the recovery screen tells the user to create or
            // grant a dashboard and retry, and that retry must reach Home Assistant again.
            if (resolution.path != null) {
                cached = Cached(key, resolution)
            } else {
                cached = cached?.takeIf { it.key != key }
            }
            resolution
        }
    }
}

internal data class OwnedAuthenticatedHomeDashboardAuthority(
    val key: HomeDashboardResolutionAuthority.Key,
    val credentialFingerprint: String,
)

/** Bind a returned access token to the exact stable credential owner that authorized the request. */
internal fun ownedAuthenticatedHomeDashboardAuthority(
    baseUrl: String,
    configuredPath: String,
    ownerBeforeAuthentication: HaAuthOwner,
    snapshotAfterAuthentication: HaAuthSnapshot,
    returnedAccessToken: String,
): OwnedAuthenticatedHomeDashboardAuthority? {
    val ownerAfter = snapshotAfterAuthentication.stableOwner()
    if (ownerAfter != ownerBeforeAuthentication || snapshotAfterAuthentication.accessToken != returnedAccessToken) {
        return null
    }
    return OwnedAuthenticatedHomeDashboardAuthority(
        HomeDashboardResolutionAuthority.Key(baseUrl, ownerAfter, configuredPath),
        EntityLearningProtocol.hash(
            listOf(
                snapshotAfterAuthentication.url,
                snapshotAfterAuthentication.accessToken,
                snapshotAfterAuthentication.refreshToken,
                snapshotAfterAuthentication.tokenExpiry.toString(),
                snapshotAfterAuthentication.clientId,
            ).joinToString("\u0000"),
        ),
    )
}

private data class AuthenticatedHomeDashboardChoices(
    val items: List<EntityLearningProtocol.HomeDashboardChoice>,
)

/** The resolved dashboard's configuration JSON, as the scan and the change probe both analyse it. */
internal suspend fun readDashboardConfig(
    request: suspend (JSONObject) -> JSONObject,
    resolved: String,
): String? {
    val urlPath = EntityLearningProtocol.dashboardUrlPath(resolved)
    EntityLearningProtocol.panelDashboardConfig(urlPath)?.let { return it.toString() }
    val command = JSONObject().put("type", "lovelace/config")
    if (urlPath.isNotBlank()) command.put("url_path", urlPath)
    return (request(command).opt("result") as? JSONObject)?.toString()
}

/** Read only the authenticated legal set shared by catalog display and renderer resolution. */
private suspend fun readAuthenticatedHomeDashboardChoices(
    request: suspend (JSONObject) -> JSONObject,
): AuthenticatedHomeDashboardChoices {
    val user = request(JSONObject().put("type", "auth/current_user")).optJSONObject("result")
        ?: error("Home Assistant current-user response missing result")
    val isAdmin = user.optBoolean("is_admin") || user.optBoolean("is_owner")
    // Render admission needs the complete authenticated set. Treat a rejected/failed panel list as an
    // unavailable catalog rather than misclassifying built-in defaults as out-of-list or reporting a
    // false zero-dashboard result.
    val panels = request(JSONObject().put("type", "get_panels")).optJSONObject("result")
        ?: error("Home Assistant panel list missing result")
    val dashboards = request(JSONObject().put("type", "lovelace/dashboards/list"))
        .optJSONArray("result") ?: error("Home Assistant dashboard list missing result")
    val choices = EntityLearningProtocol.panelDashboardChoices(panels, isAdmin) +
        EntityLearningProtocol.homeDashboardChoices(dashboards, isAdmin)
    return AuthenticatedHomeDashboardChoices(choices)
}

private suspend fun readHomeDashboardDefault(
    request: suspend (JSONObject) -> JSONObject,
    command: String,
    label: String,
): String? {
    val result = request(JSONObject().put("type", command).put("key", "core"))
        .optJSONObject("result") ?: error("Home Assistant $label response missing result")
    return result.optJSONObject("value")?.optString("default_panel")
}

/** Parse one complete catalog for configuration UI without touching entity-learning state or scan APIs. */
internal suspend fun readHomeDashboardCatalog(
    request: suspend (JSONObject) -> JSONObject,
): HomeDashboardCatalog {
    val choices = readAuthenticatedHomeDashboardChoices(request).items
    val userDefault = readHomeDashboardDefault(request, "frontend/get_user_data", "user-default")
    val systemDefault = readHomeDashboardDefault(request, "frontend/get_system_data", "system-default")
    return HomeDashboardCatalog(
        queried = true,
        items = choices,
        userDefault = userDefault,
        systemDefault = systemDefault,
        default = EntityLearningProtocol.homeDashboardDefault(userDefault, systemDefault, choices),
    )
}

/** Resolve in authority order; lower-priority commands cannot block an already legal answer. */
internal suspend fun readHomeDashboardResolution(
    request: suspend (JSONObject) -> JSONObject,
    homeDashboard: String,
): EntityLearningProtocol.HomeDashboardResolution {
    val choices = readAuthenticatedHomeDashboardChoices(request).items
    val explicit = EntityLearningProtocol.resolveHomeDashboard(
        homeDashboard, null, null, choices, allowFirstLegalFallback = false,
    )
    if (explicit.source == EntityLearningProtocol.HomeDashboardSource.EXPLICIT) return explicit

    val userDefault = readHomeDashboardDefault(request, "frontend/get_user_data", "user-default")
    val user = EntityLearningProtocol.resolveHomeDashboard(
        homeDashboard, userDefault, null, choices, allowFirstLegalFallback = false,
    )
    if (user.source == EntityLearningProtocol.HomeDashboardSource.USER_DEFAULT) return user

    val systemDefault = readHomeDashboardDefault(request, "frontend/get_system_data", "system-default")
    return EntityLearningProtocol.resolveHomeDashboard(homeDashboard, userDefault, systemDefault, choices)
}

/** Owns catalog synchronization, automatic set promotion and the HTTP/UI query surface. */
class EntityLearningManager(
    context: Context,
    private val config: Config,
    private val scope: CoroutineScope,
    private val resolveInstanceUuid: suspend (candidateUrls: Collection<String>) -> String? = { null },
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
    // Entity access/metric telemetry and dashboard performance history each own a single-owner drain
    // worker via the shared EntityTelemetryPipeline. Each keeps its own accumulator, cadence and
    // flush body; the pipeline owns the accumulator plus the worker-lifecycle contract they used to
    // hand-roll separately (launch-if-idle, respawn-while-pending, close cancels).
    private val telemetryPipeline = EntityTelemetryPipeline(
        scope = scope,
        accumulator = EntityTelemetryAccumulator(MAX_TELEMETRY_IDS),
        hasPending = { it.size() > 0 },
        drain = ::drainTelemetry,
    )
    private val telemetryPending: EntityTelemetryAccumulator get() = telemetryPipeline.accumulator
    private val performanceHistorySink = DashboardPerformanceHistorySink(::admitDashboardPerformance)
    private val performancePipeline = EntityTelemetryPipeline(
        scope = scope,
        accumulator = DashboardPerformanceAccumulator(MAX_PERFORMANCE_PENDING),
        hasPending = { it.isNotEmpty() },
        drain = ::drainDashboardPerformance,
    )
    private val performancePending: DashboardPerformanceAccumulator get() = performancePipeline.accumulator
    @Volatile private var performanceSummaryAt = 0L
    @Volatile private var performanceSummary = "[]"
    @Volatile private var performanceSummaryTarget = ""
    @Volatile private var initialized = false
    @Volatile private var closed = false

    // Live catalog-scan progress, for the setup wizard's entity-filter question. Deliberately not persisted
    // and not part of the catalog snapshot: it is a fact about a scan in flight, worthless a second later.
    @Volatile private var scanCounting = false
    @Volatile private var scanCountedRows = 0

    /** Entities counted so far by a scan in flight, or null when no scan is running. */
    fun scanProgress(): Int? = if (scanCounting) scanCountedRows else null

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
        DashboardTelemetry.setHistorySink(performanceHistorySink)
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
        DashboardTelemetry.setHistorySink(null)
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
        val pendingPerformance = performancePipeline.close { it.drain() }
        if (pendingPerformance.isNotEmpty()) {
            telemetryStoreGate.withStore { store.recordDashboardPerformance(pendingPerformance) }
        }
        // Cancellation cannot interrupt a synchronous SQLite transaction. The generation check keeps
        // a not-yet-started flush out, while this gate waits for an already-started bounded telemetry
        // or performance-history write. Performance history remains active when learning is disabled,
        // so the shared store must always be closed.
        telemetryStoreGate.withStore { store.close() }
    }

    private fun admitDashboardPerformance(batch: EntityFilterProtocol.TrafficBatch) {
        val now = System.currentTimeMillis()
        val filter = EntityFilterTelemetry.dashboardFilterState()
        val sample = DashboardPerformanceSample(
            instance = instance(),
            path = dashboardPath(),
            minute = now / 60_000L,
            filterActive = filter.first,
            entityCount = filter.second,
            batch = batch,
        )
        performancePipeline.admit(
            onOpen = { pending ->
                if (pending.add(sample) != null) {
                    Log.w(TAG, "dashboard performance queue full; dropped oldest minute aggregate")
                }
            },
            onClosed = {},
        )
    }

    private suspend fun drainDashboardPerformance() {
        delay(PERFORMANCE_FLUSH_MS)
        flushDashboardPerformance()
    }

    private fun flushDashboardPerformance() {
        val pending = performancePipeline.withLock { it.drain() }
        if (pending.isEmpty()) return
        runCatching {
            telemetryStoreGate.withStore { store.recordDashboardPerformance(pending) }
        }.onFailure { Log.w(TAG, "dashboard performance history write failed: ${it.message}") }
    }

    fun performanceHistoryJson(hours: Int): String {
        flushDashboardPerformance()
        val boundedHours = hours.coerceIn(1, EntityCatalogStore.PERFORMANCE_RETENTION_DAYS * 24)
        val sinceMinute = System.currentTimeMillis() / 60_000L - boundedHours * 60L
        return dashboardPerformanceHistoryJson(
            telemetryStoreGate.withStore {
                store.dashboardPerformanceHistory(instance(), dashboardPath(), sinceMinute)
            },
            EntityCatalogStore.PERFORMANCE_RETENTION_DAYS,
        )
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
                        // A superseded request must not stamp its failure onto the newly-selected target.
                        if (shouldPersistEntityLearningFailure(
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
        withHaSocket(snapshot.baseUrl, snapshot.authToken, checkNotNull(snapshot.route)) { request ->
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
            if (resolvedScopeRequiresRebind(snapshot.dashboardPath, resolved)) {
                outcome = DashboardProbeOutcome.CHANGED
                return@withHaSocket
            }
            val configJson = readDashboardConfig(request, resolved) ?: error("dashboard configuration unavailable")
            outcome = dashboardProbeOutcome(
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
        val scanCost = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_STATIC_SCAN)
        val scan = try {
            EntityLearningProtocol.scanDashboard(ws.configJson).also {
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
                if (promotionsInWindow >= MAX_PROMOTIONS) {
                    store.markStatus(
                        snapshot.instanceKey, snapshot.dashboardPath,
                        "degraded", "runtime dependencies queued; synchronize manually",
                    )
                    return@launch
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
                    if (active != snapshot.filterIds && active.isNotEmpty()) {
                        check(config.commitDashboardEntitySubscription(true, active, applied = true)) {
                            "failed to commit runtime entity promotion"
                        }
                        promotionsInWindow++
                        onFilterChanged()
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
            val held = shouldHoldRendererForEntityBootstrap(learningEnabled, filtered)
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
        val held = shouldHoldRendererForEntityBootstrap(config.dashboardEntityLearningEnabled, filtered)
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
        val held = shouldHoldRendererForEntityBootstrap(
            config.dashboardEntityLearningEnabled,
            config.dashboardEntityFilterEnabled,
        )
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
            json.put("stream_mode", when { held -> "held"; filtered -> "filtered"; else -> "unfiltered" })
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
        return config.dashboardEntityInstanceKey.ifBlank { EntityLearningProtocol.hash(normalizedOrigin(base)) }
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
        if (!EntityLearningProtocol.usesFrontendDefaultPanel(configured)) return dashboardEntityScopePath(configured)
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
        if (!resolvedScopeRequiresRebind(snapshot.dashboardPath, resolved)) return false
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
                currentOrigin = normalizedOrigin(config.haUrl.trim().trimEnd('/')),
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
        val origin = normalizedOrigin(base)
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

    /** Fetch dashboards visible to the signed-in HA user without starting an entity scan. */
    internal suspend fun homeDashboardChoices(): List<EntityLearningProtocol.HomeDashboardChoice> =
        homeDashboardCatalog().items

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
            withHaSocket(base, token, route) { request ->
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
            withHaSocket(base, token, route) { request ->
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

    /** The area registry, this panel's device row, and what the signed-in account may do about it. */
    data class HaAreaCatalog(
        /** Credential/endpoint owner actually used for this read; callers reject stale catalogs. */
        val ownerKey: String = "",
        val queried: Boolean = false,
        val admin: Boolean = false,
        val areas: List<io.github.maxlyth.hapaneld.http.HaAreaProtocol.HaArea> = emptyList(),
        val device: io.github.maxlyth.hapaneld.http.HaAreaProtocol.PanelDeviceArea =
            io.github.maxlyth.hapaneld.http.HaAreaProtocol.PanelDeviceArea(),
        /** The signed-in account's LOGIN name (admin sessions only: `config/auth/list` is the sole API
         *  that exposes it, and it is admin-gated). Blank when unavailable. The wizard prefills the MQTT
         *  username with it — the Mosquitto add-on authenticates against HA users. */
        val haUsername: String = "",
    )

    /**
     * Read the area registry and this panel's device row in one authenticated session — registry LISTS
     * are readable by any user; only writes need an admin. `queried=false` means "could not ask Home
     * Assistant", which callers must keep distinct from "device has no area".
     */
    suspend fun haAreaCatalog(deviceUid: String, panelId: String): HaAreaCatalog = withContext(Dispatchers.IO) {
        val ownerKey = credentialFingerprint()
        val configuredBase = config.haUrl.trim().trimEnd('/')
        if (configuredBase.isBlank()) return@withContext HaAreaCatalog(ownerKey = ownerKey)
        val auth = HaCredentialManager.resolve(config)
        val token = auth.session?.accessToken ?: return@withContext HaAreaCatalog(ownerKey = ownerKey)
        val route = auth.route ?: return@withContext HaAreaCatalog(ownerKey = ownerKey)
        val base = route.url
        runCatching {
            var catalog = HaAreaCatalog()
            withHaSocket(base, token, route) { request ->
                val user = request(JSONObject().put("type", "auth/current_user")).optJSONObject("result")
                val isAdmin = user?.optBoolean("is_admin") == true || user?.optBoolean("is_owner") == true
                val areas = io.github.maxlyth.hapaneld.http.HaAreaProtocol.areas(
                    request(JSONObject().put("type", "config/area_registry/list")),
                )
                val deviceRegistry = request(JSONObject().put("type", "config/device_registry/list"))
                val device = io.github.maxlyth.hapaneld.http.HaAreaProtocol.panelDeviceArea(
                    deviceRegistry,
                    areas,
                    deviceUid,
                    panelId,
                    panelAssistantEntryIds(request, deviceRegistry),
                )
                // Login shortname, best-effort: only config/auth/list carries it and only admins may
                // call it (verified against core source — auth/current_user has display name only).
                val haUsername = if (isAdmin) runCatching {
                    val userId = user?.optString("id").orEmpty()
                    val rows = request(JSONObject().put("type", "config/auth/list")).optJSONArray("result")
                    (0 until (rows?.length() ?: 0)).asSequence()
                        .mapNotNull { rows?.optJSONObject(it) }
                        .firstOrNull { it.optString("id") == userId }
                        ?.optString("username")?.takeUnless { it.isNullOrBlank() || it == "null" }
                        .orEmpty()
                }.getOrDefault("") else ""
                catalog = HaAreaCatalog(
                    ownerKey = ownerKey, queried = true, admin = isAdmin, areas = areas,
                    device = device, haUsername = haUsername,
                )
            }
            check(HaConnectionRoutes.isCurrent(config, route)) { "Home Assistant route changed during catalog read" }
            catalog
        }.getOrDefault(HaAreaCatalog(ownerKey = ownerKey))
    }

    /** The Panel Assistant entries proven to be this panel, read over the same registry socket. */
    private suspend fun panelAssistantEntryIds(
        request: suspend (JSONObject) -> JSONObject,
        deviceRegistry: JSONObject,
    ): Set<String> = io.github.maxlyth.hapaneld.sensors.HaPanelDeviceMatcher.panelAssistantEntryIds(
        io.github.maxlyth.hapaneld.sensors.HaPanelDeviceMatcher.readProbe(request, deviceRegistry),
        io.github.maxlyth.hapaneld.panelAssistantDiscoveryId(config.deviceUid),
    )

    /** Non-secret digest identifying the HA endpoint and credential generation used by area operations. */
    fun haAreaOwnerKey(): String = credentialFingerprint()

    /** Admin write-back of an area request; blank repairs only a current literal-null assignment. */
    suspend fun applyRequestedArea(
        deviceUid: String,
        panelId: String,
        areaName: String,
        expectedOwnerKey: String? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val requested = io.github.maxlyth.hapaneld.config.normalizedHaAreaName(areaName)
            if (expectedOwnerKey != null && credentialFingerprint() != expectedOwnerKey) return@withContext false
            val configuredBase = config.haUrl.trim().trimEnd('/')
            if (configuredBase.isBlank()) return@withContext false
            val auth = HaCredentialManager.resolve(config)
            val token = auth.session?.accessToken ?: return@withContext false
            val route = auth.route ?: return@withContext false
            val base = route.url
            runCatching {
                var moved = false
                withHaSocket(base, token, route) { request ->
                    val user = request(JSONObject().put("type", "auth/current_user")).optJSONObject("result")
                    val isAdmin = user?.optBoolean("is_admin") == true || user?.optBoolean("is_owner") == true
                    if (!isAdmin) return@withHaSocket
                    val areas = io.github.maxlyth.hapaneld.http.HaAreaProtocol.areas(
                        request(JSONObject().put("type", "config/area_registry/list")),
                    )
                    val deviceRegistry = request(JSONObject().put("type", "config/device_registry/list"))
                    val device = io.github.maxlyth.hapaneld.http.HaAreaProtocol.panelDeviceArea(
                        deviceRegistry,
                        areas,
                        deviceUid,
                        panelId,
                        panelAssistantEntryIds(request, deviceRegistry),
                    )
                    if (!device.found || device.deviceId.isBlank()) return@withHaSocket
                    if (requested.isBlank()) {
                        if (device.areaId.isBlank()) { moved = true; return@withHaSocket }
                        if (!io.github.maxlyth.hapaneld.http.HaAreaProtocol.hasLiteralNullAssignment(device, areas)) {
                            return@withHaSocket
                        }
                    }
                    if (requested.isNotBlank() && device.areaName.equals(requested, ignoreCase = true)) { moved = true; return@withHaSocket }
                    if (expectedOwnerKey != null && credentialFingerprint() != expectedOwnerKey) return@withHaSocket
                    val areaId: Any = if (requested.isBlank()) JSONObject.NULL else {
                        io.github.maxlyth.hapaneld.http.HaAreaProtocol.resolveAreaId(areas, requested)
                            ?: request(
                                JSONObject().put("type", "config/area_registry/create").put("name", requested),
                            ).optJSONObject("result")?.optString("area_id")?.trim().takeUnless { it.isNullOrBlank() }
                            ?: return@withHaSocket
                    }
                    if (expectedOwnerKey != null && credentialFingerprint() != expectedOwnerKey) return@withHaSocket
                    val updated = request(
                        JSONObject().put("type", "config/device_registry/update")
                            .put("device_id", device.deviceId)
                            .put("area_id", areaId),
                    )
                    moved = updated.optJSONObject("result") != null
                }
                check(HaConnectionRoutes.isCurrent(config, route)) { "Home Assistant route changed during area update" }
                moved
            }.getOrDefault(false)
        }

    private fun credentialFingerprint(): String {
        val snapshot = config.haAuthSnapshot()
        return ownedAuthenticatedHomeDashboardAuthority(
            snapshot.url.trim().trimEnd('/'),
            config.homeDashboard.trim(),
            snapshot.stableOwner(),
            snapshot,
            snapshot.accessToken,
        )?.credentialFingerprint ?: error("credential snapshot changed while reading")
    }

    private fun currentEffectState(): EntityLearningEffectState = EntityLearningEffectState(
        origin = runCatching { normalizedOrigin(config.haUrl) }.getOrDefault(""),
        routeEpoch = HaConnectionRoutes.current(config).epoch,
        instanceKey = instance(),
        targetKey = config.dashboardEntityTargetKey,
        dashboardPath = dashboardPath(),
        credentialFingerprint = credentialFingerprint(),
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
        val origin = normalizedOrigin(base)
        val configuredHomeDashboard = config.homeDashboard.trim()
        val path = dashboardPath()
        val legacyKey = EntityLearningProtocol.hash(origin)
        val selected = config.prepareDashboardEntityInstance(origin, path, legacyKey)
            ?: error("failed to prepare entity-learning target")
        val targetKey = config.dashboardEntityTargetKey
        val ownerBeforeAuthentication = config.haAuthSnapshot().stableOwner()
        val stillCurrent = {
            generation == effectGeneration.get() &&
                runCatching { normalizedOrigin(config.haUrl) }.getOrNull() == origin &&
                config.haAuthSnapshot().stableOwner() == ownerBeforeAuthentication &&
                config.homeDashboard.trim() == configuredHomeDashboard && dashboardPath() == path &&
                config.dashboardEntityInstanceKey == selected &&
                config.dashboardEntityTargetKey == targetKey
        }
        val auth = HaCredentialManager.resolve(config, stillCurrent = stillCurrent)
        val route = auth.route ?: error("Home Assistant route unavailable")
        val token = auth.session?.accessToken
            ?: error(if (auth.rejected) "Home Assistant credential rejected" else "Home Assistant token unavailable")
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
        check(HaConnectionRoutes.isCurrent(config, target.route)) { "Home Assistant route changed" }
        check(target.generation == effectGeneration.get()) { "entity-learning target changed during discovery" }
        check(normalizedOrigin(config.haUrl) == target.origin && dashboardPath() == target.dashboardPath) {
            "entity-learning target changed during discovery"
        }
        check(credentialFingerprint() == target.credentialFingerprint) { "Home Assistant credential changed during discovery" }
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
        check(HaConnectionRoutes.isCurrent(config, target.route)) { "Home Assistant route changed" }
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
            requireCurrentHaRoute(base, token, route)
            val cost = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_STATES_FETCH_PARSE)
            val c = (URL(base.trimEnd('/') + "/api/states").openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = HTTP_TIMEOUT_MS; readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("Accept", "application/json")
            }
            try {
                if (c.responseCode !in 200..299) error("states request failed: HTTP ${c.responseCode}")
                // /api/states includes every attribute payload. Stream over it without hydrating the
                // attributes, but reject a response that exceeds any resource or wall-clock bound.
                val limits = HaStatesReadLimits()
                if (c.contentLengthLong > limits.maxBytes) throw ByteLimitExceeded(limits.maxBytes)
                val counted = CountingInputStream(c.inputStream)
                scanCountedRows = 0
                scanCounting = true
                readHaStates(counted, limits, onProgress = { scanCountedRows = it }).also {
                    cost.work(units = it.size.toLong(), bytes = counted.bytesRead)
                }
            } catch (failure: Throwable) {
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
        requireCurrentHaRoute(base, token, route)
        val c = (URL(base.trimEnd('/') + "/api/config").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = HA_CONFIG_TIMEOUT_MS
            readTimeout = HA_CONFIG_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode !in 200..299) error("config request failed: HTTP ${c.responseCode}")
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
        withHaSocket(base, token, checkNotNull(snapshot.route)) { request ->
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
        withHaSocket(base, token, route) { request ->
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
                    if (id.matches(ENTITY_ID_PATTERN)) out += id
                    require(out.size <= EntityFilterProtocol.MAX_ENTITY_IDS) {
                        "expanded target set exceeds ${EntityFilterProtocol.MAX_ENTITY_IDS} IDs"
                    }
                }
            }
        }
        return out
    }

    private fun requireCurrentHaRoute(base: String, token: String, route: HaConnectionRoute) {
        check(route.url == base && HaConnectionRoutes.isCurrent(config, route) &&
            config.haAuthSnapshot().accessToken == token) { "Home Assistant route or credential changed" }
    }

    private suspend fun withHaSocket(
        base: String,
        token: String,
        route: HaConnectionRoute,
        block: suspend (suspend (JSONObject) -> JSONObject) -> Unit,
    ) {
        requireCurrentHaRoute(base, token, route)
        check(HaConnectionRoutes.verify(config, route)) { "Home Assistant instance unavailable" }
        val ws = EntityFilterProtocol.upstreamWebSocketUrl(base)
        val policy = MqttAddressFamilyPolicy.fromConfig(config.mqttAddressFamily)
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
                        requireCurrentHaRoute(base, token, route)
                        send(Frame.Text(JSONObject().put("type", "auth").put("access_token", token).toString()))
                        val auth = (incoming.receive() as? Frame.Text)?.readText().orEmpty()
                        require(auth.contains("\"type\":\"auth_ok\"")) { "Home Assistant WebSocket authentication failed" }
                    }
                    var id = 0
                    suspend fun request(command: JSONObject): JSONObject = withEntityLearningDeadline(WS_REQUEST_TIMEOUT_MS) {
                        requireCurrentHaRoute(base, token, route)
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

    companion object {
        private const val TAG = "EntityLearning"
        private const val HTTP_TIMEOUT_MS = 30_000
        private const val HA_CONFIG_TIMEOUT_MS = 10_000
        private const val MAX_HA_CONFIG_BYTES = 256L * 1024
        private const val MAX_PERFORMANCE_PENDING = 24
        private const val PERFORMANCE_FLUSH_MS = 60_000L
        private const val SYNC_TIMEOUT_MS = 120_000L
        private const val PROBE_TIMEOUT_MS = 60_000L
        private const val MAX_WS_FRAME = 32L * 1024 * 1024
        private const val WS_CONNECT_TIMEOUT_MS = 15_000L
        private const val WS_AUTH_TIMEOUT_MS = 15_000L
        private const val WS_REQUEST_TIMEOUT_MS = 20_000L
        private const val WS_SOCKET_TIMEOUT_MS = 90_000L
        private const val MAX_RESPONSE_FRAMES = 32
        private const val MAX_TARGETS = 500
        private const val HOURLY_CHECK_MS = 60L * 60_000
        private const val DAILY_SYNC_MS = 24L * HOURLY_CHECK_MS
        // Long enough for the first filtered load's own telemetry to finish arriving (it is what
        // triggers promotion in the first place); with trailing-edge coalescing in queuePromotion this
        // trades two visible dashboard rebuilds for one fuller one. Promotions are never urgent.
        private const val PROMOTION_DEBOUNCE_MS = 20_000L
        private const val PROMOTION_WINDOW_MS = 10L * 60_000
        private const val MAX_PROMOTIONS = 2
        private const val MAX_DYNAMIC_EXPRESSIONS = 128
        private const val MAX_TELEMETRY_IDS = EntityFilterProtocol.MAX_ENTITY_IDS
        private const val PERFORMANCE_SUMMARY_CACHE_MS = 10_000L
        private val ENTITY_ID_PATTERN = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")
        private const val UNCONFIGURED_INSTANCE = "unconfigured"

        private fun normalizedOrigin(raw: String): String = EntityFilterProtocol.origin(raw)
    }
}

internal fun visibleDashboardIssuesJson(
    storedIssuesJson: String,
    showAdvisories: Boolean,
    dynamicExpressionsJson: String = "[]",
): String {
    val visible = visibleDashboardIssues(storedIssuesJson, showAdvisories)
    val visibleJson = visible.toString()
    val counts = EntityCatalogIssuePersistence.counts(visibleJson)
    return JSONObject()
        .put("items", visible)
        .put("dashboard_issue_count", counts.first)
        .put("blocking_issue_count", counts.second)
        .put("ignored_issue_count", EntityCatalogIssuePersistence.ignoredCount(visibleJson))
        .put("dynamic_expressions", JSONArray(dynamicExpressionsJson))
        .toString()
}

internal fun entityStreamMode(held: Boolean, filtered: Boolean): String =
    when { held -> "held"; filtered -> "filtered"; else -> "unfiltered" }

/**
 * An allowed dashboard-strategy finding on a filtered stream (issue #133 follow-up). Home Assistant
 * builds a strategy's cards from the entities it receives, so with the check allowed an entity outside
 * the subscription gets no card and never reaches runtime learning; nothing the panel observes can
 * list it, so the configure pages say so instead. Visibility only: the filter is never changed here.
 */
internal fun strategySelectorAllowed(streamMode: String, visibleIssues: JSONArray): Boolean =
    streamMode == "filtered" && (0 until visibleIssues.length()).any { index ->
        val issue = visibleIssues.optJSONObject(index) ?: return@any false
        issue.optBoolean("ignored") && issue.optString("presentation_code") ==
            DashboardConfigurationLint.PresentationCode.DASHBOARD_STRATEGY.wireName
    }

internal fun visibleDashboardIssues(storedIssuesJson: String, showAdvisories: Boolean): JSONArray {
    val stored = JSONArray(EntityCatalogIssuePersistence.boundExistingJson(storedIssuesJson))
    return JSONArray().apply {
        for (index in 0 until stored.length()) {
            val issue = stored.optJSONObject(index) ?: continue
            val safetyFinding = issue.optBoolean("would_block", issue.optBoolean("blocking", false))
            if (showAdvisories || safetyFinding) put(issue)
        }
    }
}

internal data class DashboardRegistryProjection(
    val metadata: Map<String, String>,
    val areaRegistryEntities: Map<String, Set<String>>,
    val complete: Boolean,
)

/** Validate and enrich the registry snapshot used by registry-backed dashboard selectors. */
internal fun projectDashboardRegistries(
    entityResponse: JSONObject?,
    areaResponse: JSONObject?,
    deviceResponse: JSONObject?,
    floorResponse: JSONObject?,
    labelResponse: JSONObject?,
    requirements: DashboardConfigurationLint.RegistryRequirements =
        DashboardConfigurationLint.RegistryRequirements(
            entities = true,
            areas = true,
            devices = true,
            floors = true,
            labels = true,
        ),
): DashboardRegistryProjection {
    val metadata = mutableMapOf<String, String>()
    var complete = true
    val entries = if (requirements.entities) {
        entityResponse?.optJSONObject("result")?.optJSONArray("entities")
            ?: entityResponse?.optJSONArray("result")
    } else null
    if (requirements.entities && entries == null) complete = false
    val entityRows = entries ?: JSONArray()
    for (i in 0 until entityRows.length()) {
        val row = entityRows.optJSONObject(i)
        val id = row?.let { it.optString("ei").ifBlank { it.optString("entity_id") } }?.lowercase()
        if (row == null || id == null || !id.matches(PROJECTED_ENTITY_ID)) complete = false
        else if (metadata.put(id, row.toString()) != null) complete = false
    }

    val areas = if (requirements.areas) areaResponse?.optJSONArray("result") else null
    val devices = if (requirements.devices) deviceResponse?.optJSONArray("result") else null
    val floors = if (requirements.floors) floorResponse?.optJSONArray("result") else null
    val labels = if (requirements.labels) labelResponse?.optJSONArray("result") else null

    fun rows(
        required: Boolean,
        array: JSONArray?,
        requireName: Boolean,
        vararg idKeys: String,
    ): Map<String, JSONObject> {
        if (!required) return emptyMap()
        if (array == null) {
            complete = false
            return emptyMap()
        }
        val out = mutableMapOf<String, JSONObject>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i)
            val id = row?.let { objectValue ->
                idKeys.firstNotNullOfOrNull { key -> objectValue.optString(key).takeIf(String::isNotBlank) }
            }?.lowercase()
            val name = row?.opt("name") as? String
            if (row == null || id == null || !id.matches(PROJECTED_REGISTRY_ID) || requireName &&
                (name.isNullOrBlank() || name.length > PROJECTED_REGISTRY_NAME_MAX || name.any(Char::isISOControl))
            ) {
                complete = false
            } else if (out.put(id, row) != null) complete = false
        }
        return out
    }

    val areaRows = rows(requirements.areas, areas, true, "area_id", "id")
    val deviceRows = rows(requirements.devices, devices, false, "id", "device_id")
    val floorRows = rows(requirements.floors, floors, true, "floor_id", "id")
    val labelRows = rows(requirements.labels, labels, true, "label_id", "id")

    fun labelIds(row: JSONObject?, vararg keys: String): Set<String> = buildSet {
        for (key in keys) {
            if (row == null || !row.has(key)) continue
            val value = row.opt(key)
            if (value is JSONArray) {
                for (index in 0 until value.length()) {
                    val id = (value.opt(index) as? String)?.lowercase()
                    if (id == null || !id.matches(PROJECTED_REGISTRY_ID)) complete = false else add(id)
                }
            } else if (value is String) {
                val id = value.lowercase()
                if (!id.matches(PROJECTED_REGISTRY_ID)) complete = false else add(id)
            } else {
                complete = false
            }
        }
    }

    metadata.replaceAll { _, raw ->
        val entity = JSONObject(raw)
        val deviceId = entity.optString("di").ifBlank { entity.optString("device_id") }.lowercase()
        val device = if (requirements.devices) deviceRows[deviceId] else null
        if (requirements.devices && deviceId.isNotBlank() && device == null) complete = false
        val area = if (requirements.areas) {
            val areaId = entity.optString("ai").ifBlank { entity.optString("area_id") }
                .ifBlank { device?.optString("area_id").orEmpty() }.lowercase()
            val row = areaRows[areaId]
            if (areaId.isNotBlank() && row == null) complete = false
            if (areaId.isNotBlank()) entity.put("ai", areaId)
            row?.optString("name")?.takeIf(String::isNotBlank)?.let { entity.put("an", it) }
            row
        } else null
        if (requirements.floors) {
            val floorId = area?.optString("floor_id").orEmpty().lowercase()
            val floor = floorRows[floorId]
            if (floorId.isNotBlank() && floor == null) complete = false
            if (floorId.isNotBlank()) entity.put("fi", floorId)
            floor?.optString("name")?.takeIf(String::isNotBlank)?.let { entity.put("fn", it) }
        }
        if (requirements.labels) {
            val inheritedLabels = labelIds(entity, "lb", "labels", "label_ids") +
                labelIds(device, "labels", "label_ids")
            if (inheritedLabels.isNotEmpty()) entity.put("lb", JSONArray(inheritedLabels.sorted()))
            val labelNames = inheritedLabels.mapNotNullTo(sortedSetOf()) { labelId ->
                val label = labelRows[labelId]
                if (label == null) complete = false
                label?.optString("name")?.takeIf(String::isNotBlank)
            }
            if (labelNames.isNotEmpty()) entity.put("ln", JSONArray(labelNames))
        }
        entity.toString()
    }
    val areaRegistryEntities = if (requirements.areas) areaRows.mapValues { (_, area) ->
        setOf("temperature_entity_id", "humidity_entity_id").mapNotNullTo(sortedSetOf()) { key ->
            val raw = area.optString(key).lowercase()
            if (raw.isNotBlank() && !raw.matches(PROJECTED_ENTITY_ID)) complete = false
            raw.takeIf { it.matches(PROJECTED_ENTITY_ID) }
        }
    } else emptyMap()
    return DashboardRegistryProjection(metadata, areaRegistryEntities, complete)
}

private val PROJECTED_ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")
private val PROJECTED_REGISTRY_ID = Regex("^[a-z0-9_-]+$")
private const val PROJECTED_REGISTRY_NAME_MAX = 1_024

/**
 * Rows between progress reports while streaming `/api/states`. Coarse on purpose: the wizard polls every two
 * seconds and eases between the samples it receives, so a finer stride would cost work without the user
 * seeing anything the tween does not already give them.
 */
internal const val HA_STATES_PROGRESS_STRIDE = 25

internal data class HaStatesReadLimits(
    val maxBytes: Long = 64L * 1024 * 1024,
    val maxRows: Int = 100_000,
    val maxEntityIdChars: Int = 255,
    val maxStateChars: Int = 255,
    val maxFriendlyNameChars: Int = 1_024,
    val deadlineMs: Long = 45_000,
) {
    init {
        require(
            maxBytes > 0 && maxRows > 0 && maxEntityIdChars > 0 && maxStateChars > 0 &&
                maxFriendlyNameChars > 0 && deadlineMs > 0,
        )
    }
}

/** Counts bytes consumed by the streaming parser without retaining a second copy. */
internal class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
}

private fun Throwable.featureCostOutcome(): FeatureCostOutcome =
    if (this is CancellationException) FeatureCostOutcome.CANCELLED else FeatureCostOutcome.FAILURE

/** Parse only the state projection needed by the learner while retaining hard transport-independent
 * bounds. The monotonic clock seam keeps elapsed-time enforcement deterministic in unit tests. */
/**
 * @param onProgress called with the running row count as the response streams, every
 *   [HA_STATES_PROGRESS_STRIDE] rows, so the setup wizard can show the count climbing while the user reads
 *   the entity-filter question instead of watching an unexplained pause. Reported counts only ever rise and
 *   never exceed what has actually been parsed, so a partial figure is honest rather than an estimate.
 */
internal fun readHaStates(
    input: InputStream,
    limits: HaStatesReadLimits = HaStatesReadLimits(),
    nanoTime: () -> Long = System::nanoTime,
    onProgress: (Int) -> Unit = {},
): List<EntityCatalogStore.StateRow> {
    val bounded = DeadlineBoundedInputStream(input, limits.maxBytes, limits.deadlineMs, nanoTime)
    return StreamingJsonReader(InputStreamReader(bounded, Charsets.UTF_8)).use { reader ->
        buildList {
            var rows = 0
            reader.beginArray()
            while (reader.hasNext()) {
                check(rows < limits.maxRows) { "states response exceeds ${limits.maxRows} rows" }
                rows++
                var entityId = ""
                var state = ""
                var friendlyName = ""
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "entity_id" -> entityId = reader.readBoundedScalar(limits.maxEntityIdChars, "entity_id")
                    "state" -> state = reader.readBoundedScalar(limits.maxStateChars, "state")
                    "attributes" -> friendlyName = reader.readFriendlyName(limits.maxFriendlyNameChars)
                    else -> reader.skipValue()
                }
                reader.endObject()
                validateHaStateRow(entityId, state, rows, limits, friendlyName)?.let(::add)
                if (rows % HA_STATES_PROGRESS_STRIDE == 0) onProgress(rows)
            }
            reader.endArray()
            onProgress(rows)
            reader.requireEndDocument()
        }
    }
}

internal fun validateHaStateRow(
    entityId: String,
    state: String,
    rowNumber: Int,
    limits: HaStatesReadLimits,
    friendlyName: String = "",
): EntityCatalogStore.StateRow? {
    check(rowNumber <= limits.maxRows) { "states response exceeds ${limits.maxRows} rows" }
    check(entityId.length <= limits.maxEntityIdChars) {
        "states entity_id exceeds ${limits.maxEntityIdChars} characters"
    }
    check(state.length <= limits.maxStateChars) { "states state exceeds ${limits.maxStateChars} characters" }
    check(friendlyName.length <= limits.maxFriendlyNameChars) {
        "states friendly_name exceeds ${limits.maxFriendlyNameChars} characters"
    }
    return entityId.takeIf(String::isNotBlank)?.let { EntityCatalogStore.StateRow(it, state, friendlyName) }
}

/** Selectively project the one state attribute used by static dashboard selectors. All other
 * attributes remain streaming skips and are neither hydrated nor persisted. */
private fun StreamingJsonReader.readFriendlyName(maxChars: Int): String {
    if (peek() != StreamJsonToken.BEGIN_OBJECT) {
        skipValue()
        return ""
    }
    var friendlyName = ""
    beginObject()
    while (hasNext()) when (nextName()) {
        "friendly_name" -> friendlyName = readBoundedScalar(maxChars, "friendly_name")
        else -> skipValue()
    }
    endObject()
    return friendlyName
}

private fun StreamingJsonReader.readBoundedScalar(maxChars: Int, field: String): String {
    val value = when (peek()) {
        StreamJsonToken.NULL -> { nextNull(); "" }
        StreamJsonToken.STRING, StreamJsonToken.NUMBER -> nextString(maxChars, field)
        StreamJsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); "" }
    }
    check(value.length <= maxChars) { "states $field exceeds $maxChars characters" }
    return value
}

internal enum class StreamJsonToken { BEGIN_OBJECT, BEGIN_ARRAY, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }

/** Small dependency-free streaming JSON reader for the HA state projection. Android's JsonReader is
 * unavailable as real code in local JVM tests, while a tree parser would reintroduce the payload-copy
 * regression this path exists to avoid. */
internal class StreamingJsonReader(private val input: Reader) : AutoCloseable {
    private data class Scope(val closing: Char, var first: Boolean = true)
    private val scopes = ArrayDeque<Scope>()
    private var lookahead = UNSET

    fun beginArray() { expect('['); pushScope(']') }
    fun endArray() { expect(']'); popScope(']') }
    fun beginObject() { expect('{'); pushScope('}') }
    fun endObject() { expect('}'); popScope('}') }

    fun hasNext(): Boolean {
        val scope = scopes.lastOrNull() ?: error("JSON scope is empty")
        skipWhitespace()
        if (peekChar() == scope.closing.code) return false
        if (scope.first) {
            scope.first = false
        } else {
            expect(',')
            skipWhitespace()
            check(peekChar() != scope.closing.code) { "trailing comma in JSON" }
        }
        return true
    }

    fun nextName(): String {
        val name = readString(MAX_NAME_CHARS, "field name")
        skipWhitespace(); expect(':')
        return name
    }

    fun peek(): StreamJsonToken {
        skipWhitespace()
        return when (val char = peekChar()) {
            '{'.code -> StreamJsonToken.BEGIN_OBJECT
            '['.code -> StreamJsonToken.BEGIN_ARRAY
            '"'.code -> StreamJsonToken.STRING
            't'.code, 'f'.code -> StreamJsonToken.BOOLEAN
            'n'.code -> StreamJsonToken.NULL
            -1 -> StreamJsonToken.END_DOCUMENT
            else -> if (char == '-'.code || char in '0'.code..'9'.code) StreamJsonToken.NUMBER
                else error("invalid JSON value")
        }
    }

    fun nextNull() { expectLiteral("null") }
    fun nextBoolean(): Boolean = when (peekChar()) {
        't'.code -> { expectLiteral("true"); true }
        'f'.code -> { expectLiteral("false"); false }
        else -> error("expected JSON boolean")
    }

    fun nextString(maxChars: Int, field: String): String = when (peek()) {
        StreamJsonToken.STRING -> readString(maxChars, field)
        StreamJsonToken.NUMBER -> readNumber(maxChars, field)
        else -> error("expected JSON scalar")
    }

    fun skipValue(depth: Int = 0) {
        check(depth < MAX_DEPTH) { "JSON nesting exceeds $MAX_DEPTH" }
        when (peek()) {
            StreamJsonToken.BEGIN_OBJECT -> {
                beginObject()
                while (hasNext()) { nextName(); skipValue(depth + 1) }
                endObject()
            }
            StreamJsonToken.BEGIN_ARRAY -> {
                beginArray()
                while (hasNext()) skipValue(depth + 1)
                endArray()
            }
            StreamJsonToken.STRING -> { readString(null, "skipped value") }
            StreamJsonToken.NUMBER -> { readNumber(MAX_SKIPPED_NUMBER_CHARS, "skipped number") }
            StreamJsonToken.BOOLEAN -> { nextBoolean() }
            StreamJsonToken.NULL -> nextNull()
            StreamJsonToken.END_DOCUMENT -> error("unexpected end of JSON")
        }
    }

    fun requireEndDocument() {
        check(scopes.isEmpty()) { "JSON scope is still open" }
        skipWhitespace()
        check(peekChar() == -1) { "trailing data after JSON document" }
    }

    override fun close() = input.close()

    private fun readString(maxChars: Int?, field: String): String {
        skipWhitespace(); expect('"')
        val value = if (maxChars == null) null else StringBuilder(minOf(maxChars, 64))
        var count = 0
        while (true) {
            val char = takeChar()
            check(char >= 0) { "unterminated JSON string" }
            if (char == '"'.code) break
            val decoded = if (char == '\\'.code) readEscape() else {
                check(char >= 0x20) { "control character in JSON string" }
                char.toChar()
            }
            count++
            if (maxChars != null) check(count <= maxChars) { "states $field exceeds $maxChars characters" }
            value?.append(decoded)
        }
        return value?.toString().orEmpty()
    }

    private fun readEscape(): Char = when (val escaped = takeChar().toChar()) {
        '"', '\\', '/' -> escaped
        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
        'u' -> {
            var value = 0
            repeat(4) {
                val digit = takeChar()
                val hex = Character.digit(digit.toChar(), 16)
                check(hex >= 0) { "invalid JSON unicode escape" }
                value = value * 16 + hex
            }
            value.toChar()
        }
        else -> error("invalid JSON escape")
    }

    private fun readNumber(maxChars: Int, field: String): String {
        skipWhitespace()
        val value = StringBuilder()
        while (true) {
            val char = peekChar()
            if (char < 0 || char.toChar().isWhitespace() || char == ','.code || char == ']'.code || char == '}'.code) break
            value.append(takeChar().toChar())
            check(value.length <= maxChars) { "states $field exceeds $maxChars characters" }
        }
        return value.toString().also { check(JSON_NUMBER.matches(it)) { "invalid JSON number" } }
    }

    private fun expectLiteral(literal: String) = literal.forEach { expected ->
        check(takeChar() == expected.code) { "invalid JSON literal" }
    }

    private fun expect(expected: Char) {
        skipWhitespace()
        check(takeChar() == expected.code) { "expected '$expected' in JSON" }
    }

    private fun pushScope(closing: Char) {
        check(scopes.size < MAX_DEPTH) { "JSON nesting exceeds $MAX_DEPTH" }
        scopes.addLast(Scope(closing))
    }

    private fun popScope(closing: Char) {
        check(scopes.removeLastOrNull()?.closing == closing) { "mismatched JSON scope" }
    }

    private fun skipWhitespace() { while (peekChar() >= 0 && peekChar().toChar().isWhitespace()) takeChar() }
    private fun peekChar(): Int {
        if (lookahead == UNSET) lookahead = input.read()
        return lookahead
    }
    private fun takeChar(): Int = peekChar().also { lookahead = UNSET }

    private companion object {
        const val UNSET = -2
        const val MAX_DEPTH = 64
        const val MAX_NAME_CHARS = 1_024
        const val MAX_SKIPPED_NUMBER_CHARS = 1_024
        val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    }
}

/** Bounds bytes before the streaming reader can allocate field values. Cancellation interrupts the blocking read through
 * runInterruptible; this stream also notices interruption and elapsed time between transport reads. */
internal class DeadlineBoundedInputStream(
    input: InputStream,
    private val maxBytes: Long,
    deadlineMs: Long,
    private val nanoTime: () -> Long,
) : FilterInputStream(input) {
    private val startedAt = nanoTime()
    private val deadlineNanos = deadlineMs * 1_000_000L
    private var total = 0L

    override fun read(): Int {
        checkAbort()
        val value = super.read()
        checkAbort()
        if (value < 0) return value
        if (++total > maxBytes) throw ByteLimitExceeded(maxBytes)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        checkAbort()
        val wanted = minOf(length.toLong(), maxBytes - total + 1L).toInt()
        val count = super.read(buffer, offset, wanted)
        checkAbort()
        if (count < 0) return count
        total += count
        if (total > maxBytes) throw ByteLimitExceeded(maxBytes)
        return count
    }

    private fun checkAbort() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("states response read cancelled")
        if (nanoTime() - startedAt >= deadlineNanos) throw SocketTimeoutException("states response read deadline exceeded")
    }
}

/** Serialize compound entity-learning preference mutations on the manager's intrinsic monitor. */
internal inline fun <T> withEntityLearningMutationLock(owner: Any, mutation: () -> T): T =
    synchronized(owner, mutation)

/** Publish one scan's SQLite evidence and stored overrides under the same manager generation check.
 * A cancelled coroutine can remain on-CPU after [Job.isActive] becomes false; keeping these writes
 * under the mutation monitor prevents a reset from clearing the catalog between the two writes. */
internal inline fun commitEntityLearningSyncEvidence(
    owner: Any,
    ensureCurrent: () -> Unit,
    commitSync: () -> Unit,
    applyStoredOverrides: () -> Unit,
    publishDiagnostics: () -> Unit,
) = synchronized(owner) {
    ensureCurrent()
    commitSync()
    applyStoredOverrides()
    publishDiagnostics()
}

internal fun shouldInitializeEntityLearningOnStart(enabled: Boolean): Boolean = enabled

/**
 * Retire an old effect generation before lazy initialization can start its owned bootstrap sync.
 *
 * Reversing this order cancels the fresh sync immediately and leaves a first-time opt-in held until
 * the hourly maintenance loop. Keep the ordering behind a small pure seam so the lifecycle contract
 * remains deterministic without opening the SQLite-backed manager in a unit test.
 */
internal inline fun initializeEntityLearningAfterEffectInvalidation(
    invalidate: () -> Unit,
    initialize: () -> Boolean,
): Boolean {
    invalidate()
    return initialize()
}

internal data class EntityLearningEffectState(
    val origin: String,
    val instanceKey: String,
    val targetKey: String,
    val dashboardPath: String,
    val credentialFingerprint: String,
    val learningEnabled: Boolean,
    val applied: Boolean,
    val filterEnabled: Boolean,
    val filterIds: List<String>,
    val autoStatic: Boolean,
    val autoRuntime: Boolean,
    val overrides: Map<String, String>,
    val forceBootstrap: Boolean,
    val initialActivationPending: Boolean = false,
    /** The default-resolver migration disabled a running filter and owes this target a re-bootstrap. */
    val upgradeRebootstrapPending: Boolean = false,
    val routeEpoch: Long = 0L,
)

/**
 * Whether an authenticated resolution names a different dashboard than a scan is scoped for.
 *
 * Under Auto the scope is a standing binding until a resolution answers; comparing by DASHBOARD rather
 * than by route is what lets a view of the same dashboard keep its learned list while a genuinely
 * different dashboard gives it up.
 */
internal fun resolvedScopeRequiresRebind(currentScope: String, resolved: String): Boolean =
    dashboardEntityScopePath(currentScope) != dashboardEntityScopePath(resolved)

internal data class EntityLearningSyncSnapshot(
    val generation: Long,
    val baseUrl: String,
    val authToken: String,
    val configuredHomeDashboard: String,
    val homeDashboardAuthorityKey: HomeDashboardResolutionAuthority.Key,
    val state: EntityLearningEffectState,
    val route: HaConnectionRoute? = null,
) {
    val instanceKey get() = state.instanceKey
    val dashboardPath get() = state.dashboardPath
    val learningEnabled get() = state.learningEnabled
    val applied get() = state.applied
    val filterEnabled get() = state.filterEnabled
    val filterIds get() = state.filterIds
    val autoStatic get() = state.autoStatic
    val autoRuntime get() = state.autoRuntime
    val overrides get() = state.overrides
    val forceBootstrap get() = state.forceBootstrap
    val initialActivationPending get() = state.initialActivationPending
    val upgradeRebootstrapPending get() = state.upgradeRebootstrapPending

    fun matchesCurrent(currentGeneration: Long, currentState: EntityLearningEffectState): Boolean =
        generation == currentGeneration && state == currentState
}

internal data class EntityLearningPromotionSnapshot(
    val generation: Long,
    val state: EntityLearningEffectState,
) {
    val instanceKey get() = state.instanceKey
    val dashboardPath get() = state.dashboardPath
    val filterIds get() = state.filterIds
    val autoStatic get() = state.autoStatic
    val autoRuntime get() = state.autoRuntime

    fun isEligible(
        currentGeneration: Long,
        currentState: EntityLearningEffectState,
        blockingIssues: Int,
    ): Boolean = generation == currentGeneration && state == currentState &&
        state.learningEnabled && state.applied && state.autoRuntime && blockingIssues == 0
}

internal suspend fun <T> withEntityLearningDeadline(
    timeoutMs: Long,
    block: suspend CoroutineScope.() -> T,
): T = kotlinx.coroutines.withTimeout(timeoutMs, block)

/** Effect-changing operations own a replacement generation; the prior scan must release the mutex
 * before a replacement can run and must no longer occupy the admission slot. */
internal fun supersedeEntityLearningSync(current: Job?, clearQueuedRerun: () -> Unit = {}): Job? {
    clearQueuedRerun()
    current?.cancel()
    return null
}

/** Persist failures only for the generation and target that admitted the scan. */
internal fun shouldPersistEntityLearningFailure(
    requestGeneration: Long,
    currentGeneration: Long,
    requestInstance: String,
    requestPath: String,
    currentInstance: String,
    currentPath: String,
): Boolean = requestGeneration == currentGeneration &&
    requestInstance == currentInstance && requestPath == currentPath

/** Serializes the final generation check with evidence reset. A batch that entered first is cleared
 * by reset; a batch that reaches the barrier after reset observes the replacement generation. */
internal class EntityTelemetryWriteBarrier(
    private val currentGeneration: () -> Long,
) {
    private val lock = Any()

    fun writeIfCurrent(admittedGeneration: Long, write: () -> Unit): Boolean = synchronized(lock) {
        if (admittedGeneration != currentGeneration()) return@synchronized false
        write()
        true
    }

    fun invalidateAndWrite(invalidate: () -> Unit, write: () -> Unit) = synchronized(lock) {
        invalidate()
        write()
    }
}

/** Run follow-up work only after releasing the telemetry barrier. Follow-ups may enter the manager
 * monitor; reset takes that monitor before this barrier, so nesting them would invert the lock order. */
internal fun writeEntityTelemetryThen(
    barrier: EntityTelemetryWriteBarrier,
    admittedGeneration: Long,
    write: () -> Unit,
    afterWrite: () -> Unit,
): Boolean {
    val admitted = barrier.writeIfCurrent(admittedGeneration, write)
    if (admitted) afterWrite()
    return admitted
}

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

internal fun canActivateLearnedEntitySet(defaultResolverRebootstrapPending: Boolean): Boolean =
    !defaultResolverRebootstrapPending

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

internal data class EntityTelemetryTarget(val generation: Long, val instance: String, val path: String)

internal data class EntityTelemetryBatch(
    val target: EntityTelemetryTarget,
    val accessed: Map<String, Long>,
    val missing: Set<String>,
    val metrics: Map<String, Pair<Long, Long>>,
    val now: Long = System.currentTimeMillis(),
) {
    val uniqueIds: Int get() = (accessed.keys + missing + metrics.keys).size
}

internal data class EntityTelemetryAdmission(val backlog: Int, val coalesced: Long, val dropped: Long)

/** Serializes synchronous SQLite telemetry with close; cancellation alone cannot stop a transaction. */
internal class EntityTelemetryStoreGate {
    private val lock = Any()
    fun <T> withStore(block: () -> T): T = synchronized(lock) { block() }
}

/** Fixed-size accumulator used by the renderer bridge. It merges repeated batches without queuing jobs. */
internal class EntityTelemetryAccumulator(private val maxIds: Int) {
    init { require(maxIds > 0) }

    private var target: EntityTelemetryTarget? = null
    private val ids = linkedSetOf<String>()
    private val accessed = linkedMapOf<String, Long>()
    private val missing = linkedSetOf<String>()
    private val metrics = linkedMapOf<String, Pair<Long, Long>>()

    @Synchronized
    fun admit(
        admittedTarget: EntityTelemetryTarget,
        newAccessed: Map<String, Long>,
        newMissing: Set<String>,
        newMetrics: Map<String, Pair<Long, Long>>,
    ): EntityTelemetryAdmission {
        var dropped = 0L
        if (target != null && target != admittedTarget) {
            dropped += ids.size
            clear()
        }
        target = admittedTarget
        val coalesced = if (ids.isNotEmpty() &&
            (newAccessed.isNotEmpty() || newMissing.isNotEmpty() || newMetrics.isNotEmpty())) 1L else 0L

        fun admitId(id: String): Boolean {
            if (id in ids) return true
            if (ids.size >= maxIds) {
                dropped++
                return false
            }
            ids += id
            return true
        }
        newAccessed.forEach { (id, count) ->
            if (admitId(id)) accessed[id] = saturatingAdd(accessed[id] ?: 0L, count.coerceAtLeast(0L))
        }
        newMissing.forEach { id -> if (admitId(id)) missing += id }
        newMetrics.forEach { (id, value) ->
            if (admitId(id)) {
                val previous = metrics[id] ?: (0L to 0L)
                metrics[id] = saturatingAdd(previous.first, value.first.coerceAtLeast(0L)) to
                    saturatingAdd(previous.second, value.second.coerceAtLeast(0L))
            }
        }
        return EntityTelemetryAdmission(ids.size, coalesced, dropped)
    }

    @Synchronized fun drain(): EntityTelemetryBatch? {
        val admittedTarget = target ?: return null
        if (ids.isEmpty()) {
            target = null
            return null
        }
        return EntityTelemetryBatch(
            admittedTarget,
            LinkedHashMap(accessed),
            LinkedHashSet(missing),
            LinkedHashMap(metrics),
        ).also { clear() }
    }

    @Synchronized fun size(): Int = ids.size

    @Synchronized fun discard(): Int = ids.size.also { clear() }

    private fun clear() {
        target = null
        ids.clear()
        accessed.clear()
        missing.clear()
        metrics.clear()
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}

/** A resolver-policy upgrade must rescan even when the old, potentially wrong catalog has synced. */
/**
 * [autoScopeUnverifiedThisProcess] is what makes `Auto` safe across a restart.
 *
 * Every other reason here is a property of stored state, and under `Auto` stored state cannot answer
 * the only question that matters: the account default can move while the panel is stopped, and nothing
 * on disk records that. A nonzero `lastSyncAt` therefore certified a filter for the dashboard the panel
 * used to show — so the renderer opened the new dashboard while the previous dashboard's allow-list
 * stayed active until a manual or daily sync happened to run. Forcing a live resolution inside the scan
 * cannot fix that on its own, because this decision is what determines whether a scan runs at all.
 *
 * The cost is one resolution per process under `Auto`, which is the price of the scope being a fact
 * about the account rather than about this panel.
 */
internal fun shouldSyncEntityLearningOnStartup(
    learningEnabled: Boolean,
    lastSyncAt: Long,
    resolverMigration: DashboardEntityDefaultResolverMigration,
    forceBootstrap: Boolean = false,
    analyzerPolicyStale: Boolean = false,
    autoScopeUnverifiedThisProcess: Boolean = false,
): Boolean = learningEnabled && resolverMigration != DashboardEntityDefaultResolverMigration.PERSIST_FAILED &&
    (forceBootstrap || analyzerPolicyStale || autoScopeUnverifiedThisProcess ||
        resolverMigration == DashboardEntityDefaultResolverMigration.REBOOTSTRAP || lastSyncAt == 0L)

/** An empty installation starts narrow; any stored manual list is preserved for explicit review. */
internal fun shouldBootstrapEntityLearning(
    learningEnabled: Boolean,
    applied: Boolean,
    configuredIds: Collection<String>,
): Boolean = learningEnabled && !applied && configuredIds.isEmpty()

/** First-run compatibility defaults are durable choices, not a blanket weakening of later scans. */
internal fun defaultIgnoredIssueFingerprints(
    initialActivationPending: Boolean,
    bootstrap: Boolean,
    issues: Collection<JSONObject>,
): Set<String> {
    if (!bootstrap || !initialActivationPending) return emptySet()
    return ignorableIssueFingerprints(issues)
}

/** The blocking findings a default may take: never a fence, and never an advisory that blocks nothing. */
internal fun ignorableIssueFingerprints(issues: Collection<JSONObject>): Set<String> = issues.asSequence()
    .filter(EntityCatalogIssuePersistence::canIgnore)
    .mapNotNull { it.optString("fingerprint").takeIf(String::isNotBlank) }
    .toSet()

/**
 * Whether an upgrade-forced re-bootstrap is eligible for recovery at all.
 *
 * The migration latch is the only trigger: a person's own "Reset learned data" also forces a bootstrap,
 * but that person is looking at the Entities page where the rules are shown, and deciding for them
 * there would be a surprise. A retained allow-list is the evidence that filtering on this dashboard was
 * accepted before; without one there is nothing to restore and the decision is genuinely load-bearing.
 */
internal fun upgradeRecoveryAdmissible(
    upgradeRebootstrapPending: Boolean,
    retainedFilterIds: Collection<String>,
): Boolean = upgradeRebootstrapPending && retainedFilterIds.isNotEmpty()

/**
 * Whether the set now restorable carries the COMPLETE filter the panel was running.
 *
 * A subset is not a partial success, it is silent canonical loss. The apply overwrites the stored
 * subscription with what it installs and clears the one-shot migration latch, so ids missing here are
 * missing for good: no later pass has any record that they were ever in the filter, and the only
 * symptom is cards that quietly stop updating. The caller must therefore evaluate this against the
 * exact post-commit candidate — `commitSync` advances `missing_streak` for entities absent from the
 * scan and `desiredIds` drops rows that reach three, so a pre-commit measurement can overstate what
 * survives. Anything short of complete leaves the decision hold standing, which a person can resolve.
 */
internal fun upgradeRecoveryPreservesFilter(
    retainedFilterIds: Collection<String>,
    recoveredIds: Collection<String>,
): Boolean {
    // Nothing retained means nothing to preserve, and `containsAll` would vacuously agree. An empty
    // candidate needs no clause of its own: it cannot contain a non-empty retained list, so the
    // superset check below is the single place this policy lives. A separate emptiness guard was tried
    // and removed — the mutation battery proved no test could tell it from its absence.
    if (retainedFilterIds.isEmpty()) return false
    return recoveredIds.toSet().containsAll(retainedFilterIds.toSet())
}

internal enum class DashboardProbeOutcome { UNCHANGED, CHANGED }

/**
 * Whether a held dashboard changed in a way that gives a new scan something to say.
 *
 * A different resolved dashboard is a change even when both revisions match, because the scope the
 * catalogue is bound to no longer describes what the panel would open. A blank stored revision means no
 * scan ever committed; a live revision is always a hash, so the comparison owes a scan there too.
 */
internal fun dashboardProbeOutcome(
    resolvedPath: String,
    boundPath: String,
    liveRevision: String,
    storedRevision: String,
): DashboardProbeOutcome = when {
    resolvedScopeRequiresRebind(boundPath, resolvedPath) -> DashboardProbeOutcome.CHANGED
    liveRevision != storedRevision -> DashboardProbeOutcome.CHANGED
    else -> DashboardProbeOutcome.UNCHANGED
}

/** A probe failure is transient unless it is the one fault a person has to repair. */
internal fun probeFailureSurfacesProblem(message: String?): Boolean =
    classifyEntityBootstrapProblem("degraded", message.orEmpty()) == EntityBootstrapProblem.AUTHENTICATION

internal enum class PeriodicMaintenanceAction { NONE, SYNC, PROBE }

/**
 * What the daily maintenance tick owes a catalogue. A catalogue synced within the day owes nothing. One
 * held only on a person's decision is settled: resyncing it cannot change the answer, so the tick asks
 * whether the dashboard changed instead and lets the answer decide whether a scan follows.
 */
internal fun periodicMaintenanceAction(
    sinceLastSyncMs: Long,
    dailyMs: Long,
    heldOnDecision: Boolean,
): PeriodicMaintenanceAction = when {
    sinceLastSyncMs < dailyMs -> PeriodicMaintenanceAction.NONE
    heldOnDecision -> PeriodicMaintenanceAction.PROBE
    else -> PeriodicMaintenanceAction.SYNC
}

/** Why the renderer is held, for the status surface and the person reading it off-panel. */
enum class EntityBootstrapHoldReason(val wireName: String) {
    /** The scan answered and a flagged rule awaits a person's decision; catalogue resync is suspended. */
    DECISION("decision"),
    /** Home Assistant rejected the panel's credential; the sign-in needs repair. */
    AUTHENTICATION("authentication"),
    /** The scan did not finish; the watchdog keeps retrying it. */
    SYNCHRONIZATION("synchronization"),
    /** The first scan has not answered yet. */
    SYNCHRONIZING("synchronizing"),
}

internal fun entityBootstrapHoldReason(
    held: Boolean,
    blockingIssues: Int,
    problem: EntityBootstrapProblem?,
): EntityBootstrapHoldReason? = when {
    !held -> null
    problem == EntityBootstrapProblem.AUTHENTICATION -> EntityBootstrapHoldReason.AUTHENTICATION
    problem == EntityBootstrapProblem.SYNCHRONIZATION -> EntityBootstrapHoldReason.SYNCHRONIZATION
    blockingIssues > 0 -> EntityBootstrapHoldReason.DECISION
    else -> EntityBootstrapHoldReason.SYNCHRONIZING
}

/** Ignoring a saturated selector means omitting its complete linter projection, never a partial set. */
internal fun effectiveLintEntityIds(
    lint: DashboardConfigurationLint.Result,
    ignoredFingerprints: Set<String>,
): Set<String> = if (lint.issues.any {
        it.type == DashboardConfigurationLint.IssueType.SELECTOR_BUDGET && it.fingerprint in ignoredFingerprints
    }) emptySet() else lint.safeEntityIds

/** Merge only independent bounded evidence after the linter projection has applied its safety policy. */
internal fun deriveStaticEntityIds(
    scannedEntityIds: Collection<String>,
    catalogIds: Set<String>,
    expandedTargets: Collection<String>,
    lintEntityIds: Collection<String>,
): java.util.SortedSet<String> = sortedSetOf<String>().apply {
    addAll(scannedEntityIds.filter(catalogIds::contains))
    addAll(expandedTargets)
    addAll(lintEntityIds)
}

internal enum class AutomaticSyncDecision { BLOCKED, OBSERVE, APPLY, BOOTSTRAP }

/** Pure safety policy: a blocking scan is atomic rejection, and disabled learning is scan-only. */
internal fun automaticSyncDecision(
    learningEnabled: Boolean,
    applied: Boolean,
    configuredIds: Collection<String>,
    blockingIssues: Boolean,
    forceBootstrap: Boolean = false,
): AutomaticSyncDecision = when {
    blockingIssues -> AutomaticSyncDecision.BLOCKED
    !learningEnabled -> AutomaticSyncDecision.OBSERVE
    applied -> AutomaticSyncDecision.APPLY
    forceBootstrap || configuredIds.isEmpty() -> AutomaticSyncDecision.BOOTSTRAP
    else -> AutomaticSyncDecision.OBSERVE
}

internal data class EntitySubscriptionPreview(
    val currentCount: Int,
    val additions: Int,
    val removals: Int,
    val streamChange: Boolean,
)

/** Pure current→policy preview used by both status and the confirmed apply result. */
internal fun previewEntitySubscription(
    filtered: Boolean,
    currentIds: Collection<String>,
    catalogCount: Int,
    desiredIds: Collection<String>,
): EntitySubscriptionPreview {
    val desired = desiredIds.toSet()
    if (!filtered) return EntitySubscriptionPreview(
        currentCount = catalogCount,
        additions = 0,
        removals = (catalogCount - desired.size).coerceAtLeast(0),
        // Enabling an empty allow-list is still a real transport change: the existing unfiltered
        // WebView must reload so subscribe_entities is rewritten to entity_ids=[].
        streamChange = true,
    )
    val current = currentIds.toSet()
    return EntitySubscriptionPreview(
        currentCount = current.size,
        additions = (desired - current).size,
        removals = (current - desired).size,
        streamChange = current != desired,
    )
}

/** Process-local rendezvous between the service-owned learner and DashboardActivity's JS bridge. */
object EntityLearningRuntime {
    internal val homeDashboardAuthority = HomeDashboardResolutionAuthority()
    @Volatile private var current: EntityLearningManager? = null
    @Volatile private var attachment = CompletableDeferred<EntityLearningManager>()

    @Synchronized fun attach(manager: EntityLearningManager) {
        current = manager
        attachment.complete(manager)
    }

    @Synchronized fun detach(manager: EntityLearningManager) {
        if (current === manager) {
            current = null
            attachment = CompletableDeferred()
        }
    }
    fun recordAccessBatch(text: String) { current?.recordAccessBatch(text) }
    fun recordMetricBatch(text: String) { current?.recordMetricBatch(text) }
    fun performanceSummaryJson(): String = current?.performanceSummaryJson() ?: "[]"
    fun blockingIssueCount(): Int = current?.blockingIssueCount() ?: 0
    fun bootstrapProblem(): EntityBootstrapProblem? = current?.bootstrapProblem()
    /** Explicit user retry only. syncNow rejects the request while an existing scan is active. */
    fun retryBootstrap(): Boolean = current?.syncNow("dashboard-retry") ?: false
    fun syncRunning(): Boolean = current?.syncRunning() ?: false
    /** The held renderer asking whether the dashboard changed; never a catalogue resync. */
    fun probeDashboardChange(): Boolean = current?.probeDashboardChange("dashboard-hold") ?: false

    /** Live bring-up milestones for the panel's hold screen — real signals, never a bare spinner. */
    data class BootstrapMilestone(val stage: Int, val count: Int?)

    fun bootstrapMilestoneState(): BootstrapMilestone? {
        val manager = current ?: return null
        val scanned = manager.scanProgress()
        if (scanned != null) return BootstrapMilestone(1, scanned)
        val catalog = manager.catalogCount() ?: return BootstrapMilestone(1, null)
        return BootstrapMilestone(2, catalog)
    }

    fun bootstrapMilestone(): String {
        val milestone = bootstrapMilestoneState() ?: return ""
        return when (milestone.stage) {
            1 -> milestone.count?.let { "Step 1 of 3 · Reading your entities — $it so far" }
                ?: "Step 1 of 3 · Reading your entities…"
            else -> "Step 2 of 3 · Building the filtered set from ${milestone.count} entities"
        }
    }
    /** Await service attachment, then perform a scan-independent authenticated dashboard resolution. */
    suspend fun resolveHomeDashboard(
        homeDashboard: String,
        stillCurrent: () -> Boolean = { true },
        forceLive: Boolean = false,
    ): EntityLearningProtocol.HomeDashboardResolution? {
        // Service ownership can legitimately wait behind a predecessor's bounded teardown. The activity
        // scope cancels this await on destruction; an arbitrary short timeout would strand a healthy
        // same-process restart on a manual Retry screen before its new owner has had a chance to attach.
        val manager = current ?: withTimeoutOrNull(45_000L) { attachment.await() } ?: return null
        val resolved = manager.resolveHomeDashboard(homeDashboard, stillCurrent, forceLive)
        return resolved.takeIf { current === manager && stillCurrent() }
    }
    fun canIgnoreBlockingIssues(): Boolean = current?.canIgnoreAllBlockingIssues() ?: false
    fun ignoreBlockingIssues(): Boolean = current?.ignoreAllBlockingIssues() ?: false
    fun disableAutomaticFilter(): Boolean = current?.setEnabled(false) ?: false
}

internal data class BlockingIssueSelection(val fingerprints: List<String>, val allIgnorable: Boolean)

/** One atomic preflight for every surface that offers the bulk-ignore action. */
internal fun blockingIssueSelection(rawIssues: String): BlockingIssueSelection = runCatching {
    val issues = JSONArray(rawIssues)
    val fingerprints = mutableListOf<String>()
    var sawBlocking = false
    var allIgnorable = true
    for (index in 0 until issues.length()) {
        val issue = issues.optJSONObject(index)?.takeIf { it.optBoolean("blocking", false) } ?: continue
        sawBlocking = true
        val fingerprint = issue.optString("fingerprint").takeIf(String::isNotBlank)
        if (!EntityCatalogIssuePersistence.canIgnore(issue) || fingerprint == null) {
            allIgnorable = false
        } else {
            fingerprints += fingerprint
        }
    }
    BlockingIssueSelection(fingerprints, sawBlocking && allIgnorable)
}.getOrDefault(BlockingIssueSelection(emptyList(), false))

enum class EntityBootstrapProblem { AUTHENTICATION, SYNCHRONIZATION }

internal fun classifyEntityBootstrapProblem(state: String, error: String): EntityBootstrapProblem? {
    if (state != "degraded" || error.isBlank()) return null
    val normalized = error.lowercase()
    return if (
        "http 401" in normalized || "http 403" in normalized ||
        "credential rejected" in normalized || "token unavailable" in normalized
    ) EntityBootstrapProblem.AUTHENTICATION else EntityBootstrapProblem.SYNCHRONIZATION
}

/**
 * Which entity ids an Entities-tab query may return, or null for "no id restriction".
 *
 * Extracted as a pure function because the restriction is composed here, upstream of the store's SQL
 * predicate, and a row the query never selects cannot be rescued by any predicate. Reviewing that
 * predicate's text proves nothing about it.
 *
 * `review` deliberately reaches beyond the active ids. A pinned entity dropped from the applied set
 * after repeated misses is exactly the row Review exists to show, and restricting to the active ids
 * made it unreachable — invisible to the operator and impossible to unpin. `subscribed` keeps the
 * active ids exactly, because it reports the LIVE subscription and folding pins in would overstate
 * what the panel is watching.
 */
internal fun entityQueryIncludeIds(
    subscribed: Boolean,
    review: Boolean,
    held: Boolean,
    filtered: Boolean,
    activeIds: Set<String>,
    pinnedIds: Set<String>,
): Set<String>? = when {
    (subscribed || review) && held -> emptySet()
    review && filtered -> activeIds + pinnedIds
    subscribed && filtered -> activeIds
    else -> null
}

/**
 * Whether a scan must obtain a live home-dashboard resolution rather than reuse the process memo.
 *
 * The memo is keyed by endpoint, credential owner and CONFIGURED path. Under `Auto` the configured
 * path is a constant, so the account's default dashboard can move without changing the key — and a
 * process that resolved once would answer with the superseded dashboard for its whole lifetime. The
 * scan is the thing that promises to notice that move, so under `Auto` it must ask for a live answer.
 */
internal fun homeDashboardResolutionMustBeLive(configuredHomeDashboard: String): Boolean =
    EntityLearningProtocol.usesFrontendDefaultPanel(configuredHomeDashboard)

/**
 * Whether a resolved-scope rebind may still be written durably.
 *
 * The currency checks around the read guard the READ. This decision happens later and mutates durable
 * target state, and between the two a cooperatively cancelled pass can be overtaken: the endpoint or
 * the configured dashboard changes, and rebinding on the older snapshot overwrites the newer selection.
 * Every component of the snapshot the decision was made from has to still describe the panel.
 */
internal fun resolvedScopeRebindIsStillCurrent(
    snapshotConfigured: String,
    currentConfigured: String,
    snapshotOrigin: String,
    currentOrigin: String,
    generationMatches: Boolean,
): Boolean = snapshotConfigured == currentConfigured &&
    snapshotOrigin == currentOrigin &&
    generationMatches
