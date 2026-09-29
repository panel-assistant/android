package io.github.maxlyth.hapaneld.http

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.config.Capabilities
import io.github.maxlyth.hapaneld.control.CompanionDb
import io.github.maxlyth.hapaneld.control.DensityController
import io.github.maxlyth.hapaneld.control.PrivilegedRouteObservation
import io.github.maxlyth.hapaneld.control.observePrivilegedRoutes
import io.github.maxlyth.hapaneld.shizuku.ShizukuBridge
import io.github.maxlyth.hapaneld.util.AccessDenialMemo
import io.github.maxlyth.hapaneld.util.BundledHelperInstaller
import io.github.maxlyth.hapaneld.util.Cached
import io.github.maxlyth.hapaneld.util.CompanionInstaller
import io.github.maxlyth.hapaneld.util.GuardDbMaintenance
import io.github.maxlyth.hapaneld.util.HelperClient
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.util.bundledHelperIsCanonical
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The service's existing management caches; refresh jobs remain children of its HTTP scope. */
internal class ManagementObservations(
    private val appContext: Context,
    private val density: DensityController,
    private val managementProjection: (PrivilegedRouteObservation) -> ManagementProjection,
    private val diagnosticReport: (ManagementSnapshot, () -> TermuxBridgeProbe.State) -> String,
    private val scope: CoroutineScope,
    private val isStopping: () -> Boolean,
) {
    // The density trio is shared with the Configure tab's Display card (the bulk of ITS slow render).
    val densityCache = Cached(DENSITY_TTL_MS) { density.observeSizing() }
    val companionHelperCache = Cached(SU_TTL_MS) {
        val companionSupported = HelperClient.supportsCompanionData()
        val bundledBuildMatches = HelperClient.matchesBundledHelper()
        bundledHelperIsCanonical(
            bundledBuildMatches = bundledBuildMatches,
            companionSupported = companionSupported,
            guardSupported = companionSupported && bundledBuildMatches && GuardDbMaintenance.client.supported(),
        )
    }
    fun ensureCompanionHelper(): Boolean {
        val result = BundledHelperInstaller.ensureCurrent(appContext)
        val ready = result in setOf(
            BundledHelperInstaller.Result.ALREADY_CURRENT,
            BundledHelperInstaller.Result.INSTALLED,
        )
        if (result == BundledHelperInstaller.Result.REPROVISION_REQUIRED) {
            Log.w(TAG, "root helper matches this release but is not canonical; reprovision required")
        }
        if (ready) companionHelperCache.invalidate()
        return ready
    }
    // One servers-table read supplies both the header URL fallback and repair warning. Warm routes use
    // stale-while-revalidate so an expired SQLite observation never blocks rendering.
    val companionServerCache: Cached<CompanionDb.ServerObservation> by lazy {
        Cached(COMPANION_URL_TTL_MS) {
            val observed = if (Su.availableCachedIsolated()) {
                CompanionDb.observeServers(appContext, Su)
            } else if (CompanionInstaller.installedPkg(appContext) == null) {
                CompanionDb.ServerObservation.EMPTY
            } else {
                CompanionDb.ServerObservation.UNKNOWN
            }
            CompanionDb.retainLastKnownServerObservation(companionServerCache.peek(), observed)
        }
    }

    fun privilegeObservation(): PrivilegedRouteObservation = observePrivilegedRoutes(
        directSuProbe = { Su.availableCachedIsolated() },
        helperRootProbe = HelperClient::available,
        shizukuSnapshot = ShizukuBridge::snapshot,
    ).also { AccessDenialMemo.app.onCapabilitySignal(listOf(it.directSuReady, it.helperRootReady, it.shizuku.ready)) }

    val termuxBridgeCache = Cached(SNAP_TTL_MS) {
        TermuxBridgeProbe.collect(
            termuxUid = runCatching<Int?> { appContext.packageManager.getApplicationInfo("com.termux", 0).uid }
                .recoverCatching { if (it is android.content.pm.PackageManager.NameNotFoundException) null else throw it },
            routes = { privilegeObservation().let { it.directSuReady to it.helperRootReady } },
            rootRun = Su::runOutputIsolatedBounded,
            helperRun = { HelperClient.sendBytes(it)?.toString(Charsets.UTF_8) },
        )
    }

    val snapCache = Cached(SNAP_TTL_MS) {
        val privilege = privilegeObservation()
        val management = managementProjection(privilege)
        val d = densityCache.getWithSupplier { density.observeSizing(privilege) }
        ManagementSnapshot(
            facts = management.facts,
            live = management.live,
            caps = management.capabilities,
            capabilityRows = management.capabilityRows,
            privilege = privilege,
            densityCur = d.current,
            densityBase = d.base,
            fontScale = d.fontScale,
            wifiChronic = management.wifiChronic,
        )
    }
    val diagCache = Cached(DIAG_TTL_MS) {
        diagnosticReport(checkNotNull(snapCache.peek()) {
            "diagnostics require the management snapshot to be built first"
        }, { termuxBridgeCache.get() })
    }

    /** Call after any write that changes probed state (config apply/import/restore, density, tame),
     *  so the next render doesn't show pre-write values for a TTL. */
    fun snapInvalidate() {
        snapCache.invalidate()
        diagCache.invalidate()
        densityCache.invalidate()
    }

    /** Empirical proximity mode can change without a config write. Drop the stale capability view so
     *  the next Configure/dashboard request reflects learned reporting eligibility immediately. */
    fun invalidateCapabilitySnapshot() {
        snapCache.invalidate()
        diagCache.invalidate()
    }

    /** Storage is sampled live by status/UI; only the bounded diagnostic dump can retain an old value. */
    fun invalidateStorageHealthDiagnostics() {
        diagCache.invalidate()
    }

    /** Last management-request privilege proof for passive safety work. Never starts a fresh probe. */
    fun lastPrivilegeObservation(): PrivilegedRouteObservation? = snapCache.peek()?.privilege

    /** Last-known snapshot with a background refresh when stale — never blocks once built, so the
     *  Configure endpoints (form values, schema capabilities, Display card) render instantly like
     *  the dashboard. Blocks only before the start-up pre-warm has ever completed. */
    fun snapStaleOk(): ManagementSnapshot {
        return snapCache.staleWhileRevalidate { refresh, releaseAdmission ->
            if (isStopping()) return@staleWhileRevalidate false
            val job = scope.launch(Dispatchers.IO) { runCatching { refresh() } }
            job.invokeOnCompletion { releaseAdmission() }
            !job.isCancelled
        }
    }

    fun companionServersStaleOk(): CompanionDb.ServerObservation =
        companionServerCache.staleWhileRevalidate { refresh, releaseAdmission ->
            if (isStopping()) return@staleWhileRevalidate false
            val job = scope.launch(Dispatchers.IO) { runCatching { refresh() } }
            job.invokeOnCompletion { releaseAdmission() }
            !job.isCancelled
        }

    /** Dashboard rendering never performs the cold root/SQLite read; startup prewarm owns that path. */
    fun companionServersForRender(): CompanionDb.ServerObservation? =
        companionServerCache.peek()?.let { companionServersStaleOk() }

    /** Complete last-known support report. Its own expensive probes run only in the single-flight
     * refresh, never in a warm HTTP response and never by forcing a simultaneous facts refresh. */
    fun diagStaleOk(): String {
        // The documented cold path may block, but builds the facts snapshot first so the first complete
        // report is coherent. Once a report exists, both refreshes happen sequentially in the background.
        if (diagCache.peek() == null) {
            snapCache.get()
            return diagCache.get()
        }
        return diagCache.staleWhileRevalidate { refresh, releaseAdmission ->
            if (isStopping()) return@staleWhileRevalidate false
            val job = scope.launch(Dispatchers.IO) {
                runCatching { snapCache.get() }
                runCatching { refresh() }
            }
            job.invokeOnCompletion { releaseAdmission() }
            !job.isCancelled
        }
    }

    fun prewarm(onCompanionUrl: (String) -> Unit) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var managementSucceeded = false
        var companionSucceeded = false
        runPrewarmPhases(
            isStopping = isStopping,
            management = {
                managementSucceeded = runCatching { snapCache.get() }
                    .onFailure { Log.w(TAG, "management snapshot prewarm failed", it) }
                    .isSuccess
            },
            companion = {
                companionSucceeded = runCatching {
                    val observed = companionServerCache.get()
                    observed.preferredUrl?.let { onCompanionUrl(it) }
                    check(observed.probe != CompanionDb.Probe.FAILED) { "Companion servers table is unreadable" }
                }.onFailure { Log.w(TAG, "Companion server observation prewarm failed", it) }
                    .isSuccess
            },
        )
        if (managementSucceeded && companionSucceeded) {
            Log.i(TAG, "management prewarm completed in " +
                "${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
        }
    }

    companion object {
        private const val TAG = "ha-paneld/http"
        const val SNAP_TTL_MS = 15_000L
        private const val DIAG_TTL_MS = 15_000L
        private const val DENSITY_TTL_MS = 30_000L
        private const val SU_TTL_MS = 60_000L
        private const val COMPANION_URL_TTL_MS = 60_000L
    }
}
