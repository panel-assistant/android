package io.github.maxlyth.hapaneld.http

import android.content.Context
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import io.github.maxlyth.hapaneld.dashboard.SchemaReconcileAction
import io.github.maxlyth.hapaneld.util.UpdateChecker

/** Shared health observations and findings for page, setup and status projections. */
internal class PageHealth(
    private val appContext: Context,
    private val config: Config,
) {
    /** Request-scoped snapshot of the two health inputs several render surfaces consult — the real WebView
     *  engine status and whether any dashboard renderer is present. Captured ONCE per render so the banner,
     *  facts card and diagnostics rows on one page can't disagree about the WebView. Benign normalization of
     *  a within-render race (the probes are cached + stable across a render-millisecond; making the reads
     *  consistent can never surface a warning that a fresh read wouldn't have). */
    class Inputs(
        val webView: PanelInfo.WebViewStatus,
        val hasRenderer: Boolean,
        val brokerConfigured: Boolean,
    )

    /**
     * Whether the system WebView is too old, resolved once per process.
     *
     * `GET /api/v1/setup` is polled every two seconds during setup, and reading the true engine version can
     * load the WebView provider to get its user agent — far too expensive to repeat on a poll. Caching is
     * exactly right rather than merely cheap: a WebView swap restarts this process (see `autoUpdateWebView`),
     * so the value cannot change underneath the cache, and the answer after a successful update is read by
     * the new process. Routed through [healthInputs] so the probe keeps its single call site, which is the
     * discipline HealthWarningAuthoritySourceTest exists to hold — surfaces that probe independently drift.
     */
    val webViewTooOldOnce: Boolean by lazy { healthInputs().webView.tooOld }

    fun healthInputs(): Inputs = Inputs(
        PanelInfo.webViewStatus(appContext),
        PanelInfo.dashboardRenderers(appContext, config.dashboardPackage, config.haUrl).isNotEmpty(),
        config.mqttBroker.isNotBlank(),
    )

    /** The schema-version detail to warn about when a downgrade reset config to defaults (the last
     *  reconcile was PRESERVED_FRESH), else null. Stable after boot — the reconcile runs once at store
     *  construction — so the warning clears only on the next start at the current schema. */
    fun schemaRollbackVersions(): Pair<Int, Int>? {
        // Suppressed when the config vault refilled the fresh store: this warning exists to tell an owner
        // their settings may have reset and to check them, and once they have been recovered that is both
        // untrue and actionless. A warning demanding no action teaches people to ignore warnings. The
        // event itself remains visible in diagnostics.
        if (EntityCatalogStore.lastConfigRestore != null) return null
        return EntityCatalogStore.lastSchemaReconcile
            ?.takeIf { it.action == SchemaReconcileAction.PRESERVED_FRESH }
            ?.let { it.fromVersion to it.toVersion }
    }

    private fun schemaRollbackDetail(): String? = schemaRollbackVersions()
        ?.let { (from, to) -> "schema $from → $to" }

    /** HealthAudit findings for a render surface. The shared (WebView-too-old, no-renderer) inputs come from
     *  the request snapshot; [webViewDisplay] (the version string to show) and [updates] stay per-surface —
     *  the Install tab passes no updates, GET /api/v1/status the unfiltered list, and the dashboard banner
     *  the ignore-filtered list. */
    fun healthFindings(
        h: Inputs,
        webViewDisplay: String,
        updates: List<UpdateChecker.UpdateInfo>,
    ): List<HealthAudit.Finding> = HealthAudit.evaluate(
        webViewTooOld = h.webView.tooOld,
        webViewDisplay = webViewDisplay,
        hasRenderer = h.hasRenderer,
        brokerConfigured = h.brokerConfigured,
        updates = updates,
        schemaRolledBack = schemaRollbackDetail() != null,
        schemaRollbackDetail = schemaRollbackDetail() ?: "",
    )
}
