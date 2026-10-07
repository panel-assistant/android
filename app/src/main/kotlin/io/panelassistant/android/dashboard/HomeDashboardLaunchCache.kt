package io.panelassistant.android.dashboard

import io.panelassistant.android.HaAuthOwner
import io.panelassistant.android.util.DashboardPath

/**
 * Pure policy for the persisted last-successfully-resolved home-dashboard path — the launch
 * accelerator that lets a relaunch navigate immediately instead of holding first paint on the
 * authenticated dashboard-list round trip.
 *
 * The cache is not a second configuration authority: it can only replay a path that a live,
 * list-validated resolution produced earlier for the same owner, and a completed live resolution
 * always supersedes it — the same path confirms the page, a different path corrects it, and a
 * confirmed no-legal-dashboards answer clears the cache. A transient read failure changes nothing.
 */
internal object HomeDashboardLaunchCache {

    /**
     * Persisted owner identity, mirroring the in-memory [HomeDashboardResolutionAuthority.Key]
     * exactly: the key's `baseUrl` is always the stable owner's URL at both construction sites, so
     * hashing the [HaAuthOwner] fields plus the configured path covers every key component. Any HA
     * instance, account or explicit-path change therefore changes the fingerprint and the prior
     * identity's cache becomes structurally unreadable, rather than depending on cleanup code
     * running. One-way hashed so no credential material is ever persisted.
     */
    fun ownerFingerprint(authOwner: HaAuthOwner, configuredPath: String): String =
        EntityLearningProtocol.hash(
            listOf(
                authOwner.url,
                authOwner.refreshToken,
                authOwner.clientId,
                authOwner.staticAccessToken,
                configuredPath.trim(),
            ).joinToString("\u0000"),
        )

    /**
     * Fail-closed guard for a value read back from persistence. Legality against the live dashboard
     * LIST was proven when the value was written; this re-admits the stored ROUTE by exactly the
     * semantics the live resolver uses, so a corrupt, hand-edited or restored row cannot reach URL
     * construction through a weaker local rule. The leading-slash / no-`//` requirement is the origin
     * guard: appended to the configured HA base URL such a value can only name a path on that origin.
     */
    fun sanitizedStoredPath(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.length > MAX_STORED_PATH_CHARS) return null
        // Admit by the SAME semantics the live resolution used to produce this route: a local
        // first-segment check was weaker than the resolver, so a corrupt or restored row such as
        // `/alpha/../auth` — or its percent-encoded equivalent, which Chromium canonicalises back
        // into separators — passed the guard and could be launched provisionally. Separate
        // leading-slash and `//` checks used to sit here; a battery survivor proved them dead, since
        // canonicalisation rejects protocol-relative and scheme forms and always yields a leading
        // slash, which the exact-equality requirement below then enforces.
        val canonical = EntityLearningProtocol.canonicalDashboardRoute(value) ?: return null
        // The stored form must BE what the resolver would admit, not merely reduce to it: anything
        // else is a value we never wrote, so it is corruption and fails closed.
        return value.takeIf { it == canonical }
    }

    enum class RefreshOutcome { CONFIRMED, CORRECTED, NO_LEGAL_DASHBOARDS }

    /** True when both routes name the same dashboard, ignoring the view within it. */
    fun sameDashboardRoute(observed: String?, claimed: String?): Boolean =
        observed?.trim()?.substringBefore('?')?.substringBefore('#')?.trimEnd('/') ==
            claimed?.trim()?.substringBefore('?')?.substringBefore('#')?.trimEnd('/')

    /**
     * Whether the page's reported location proves a correction landed: membership is by dashboard
     * root, the same rule the resolver uses (`/alpha/view` belongs to `/alpha`), because the
     * frontend may open the dashboard's default view rather than the literal corrected route. A null
     * or blank location proves nothing and reads as not converged.
     */
    fun correctionConverged(currentPath: String?, correctedPath: String): Boolean {
        val current = DashboardPath.firstSegment(currentPath) ?: return false
        return current == DashboardPath.firstSegment(correctedPath)
    }

    /** What a completed live resolution means for a provisionally shown cached path. */
    fun refreshOutcome(
        shownPath: String,
        live: EntityLearningProtocol.HomeDashboardResolution,
    ): RefreshOutcome = when (live.path) {
        null -> RefreshOutcome.NO_LEGAL_DASHBOARDS
        shownPath -> RefreshOutcome.CONFIRMED
        else -> RefreshOutcome.CORRECTED
    }

    /** Matches the `home_dashboard` SettingSpec bound so a stored value can never outgrow it. */
    internal const val MAX_STORED_PATH_CHARS = 2048
}
