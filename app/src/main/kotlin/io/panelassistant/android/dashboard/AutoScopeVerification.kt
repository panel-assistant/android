package io.panelassistant.android.dashboard

/**
 * Whether this process has established what `Auto` currently resolves to.
 *
 * Scheduling a startup scan fixes eventual convergence, not the window before it answers. `Auto` names
 * no dashboard, so a filter retained for the dashboard the panel used to show is not evidence about the
 * one it is opening now — and document-start interception happens on activity, watchdog and HOME
 * relaunches, any of which can land before an asynchronous resolution returns. Until a live read says
 * otherwise, the retained allow-list must be treated as belonging to an unknown dashboard.
 *
 * Deliberately process-scoped and never persisted: the fact being tracked is "has THIS process asked",
 * and a stored answer would reintroduce exactly the stale-across-restart trust this closes.
 */
internal object AutoScopeVerification {

    @Volatile
    private var verifiedFor: String? = null

    /** Record that a live resolution answered for [configuredHomeDashboard]. */
    fun markVerified(configuredHomeDashboard: String) {
        verifiedFor = configuredHomeDashboard.trim()
    }

    /** Drop the verification when the question changes — a new endpoint or setting re-opens it. */
    fun invalidate() {
        verifiedFor = null
    }

    /**
     * True while [configuredHomeDashboard] is `Auto` and no live resolution has answered for it yet.
     *
     * An explicitly configured dashboard is never unverified: its scope is configured rather than
     * discovered, so there is nothing a live read could add.
     */
    fun unverified(configuredHomeDashboard: String): Boolean {
        val configured = configuredHomeDashboard.trim()
        return homeDashboardResolutionMustBeLive(configured) && verifiedFor != configured
    }
}
