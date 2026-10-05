package io.panelassistant.android

/** Pure idle-return decision shared by the Android lifecycle path and deterministic JVM tests. */
internal object DashboardIdleReturnPolicy {
    sealed interface Tick {
        data object Off : Tick
        data object NotIdle : Tick
        data object Unresolved : Tick
        data object AtHome : Tick
        data class Return(val target: String) : Tick
    }

    /** One idle-return tick; [targetFor] maps the normalised home dashboard to [target]'s answer.
     *  The switched-off and not-idle-yet guards come first, so [homeDashboard] is read only by a tick
     *  that would act on it; a null home dashboard is [Tick.Unresolved], checked again next tick. */
    fun tick(
        minutes: Int,
        idleMs: Long,
        homeDashboard: () -> String?,
        targetFor: (home: String) -> String?,
    ): Tick {
        if (minutes <= 0) return Tick.Off
        if (idleMs < minutes * 60_000L) return Tick.NotIdle
        val home = homeDashboard()?.trim()?.trim('/') ?: return Tick.Unresolved
        return targetFor(home)?.let(Tick::Return) ?: Tick.AtHome
    }

    /** Reports an unresolved home dashboard once per episode: the first [Tick.Unresolved] is logged,
     *  repeats are not, and a tick that read a resolved dashboard starts a new episode. */
    class UnresolvedNotice {
        private var reported = false

        fun shouldLog(tick: Tick): Boolean = when (tick) {
            Tick.Unresolved -> !reported.also { reported = true }
            Tick.AtHome, is Tick.Return -> false.also { reported = false }
            Tick.Off, Tick.NotIdle -> false
        }
    }

    /** Return the fragment-free home target when idle navigation is needed; null means already home. */
    fun target(
        currentPath: String,
        currentFragment: String?,
        homeDashboard: String,
        currentQuery: String? = null,
    ): String? {
        if (homeDashboard.trim().isEmpty()) return null
        val home = normalizeDashboardTarget(homeDashboard.substringBefore('#'))
        val homeRoute = home.substringBefore('?').ifEmpty { "/" }
        val homeQuery = home.substringAfter('?', missingDelimiterValue = "").takeIf { '?' in home }
        val samePath = normalizeDashboardEntityPath(currentPath) == normalizeDashboardEntityPath(homeRoute)
        val sameQuery = comparableDashboardQuery(currentQuery) == comparableDashboardQuery(homeQuery)
        val target = if (home == "") "/" else home
        return target.takeUnless { samePath && sameQuery && currentFragment.isNullOrEmpty() }
    }
}

private fun comparableDashboardQuery(query: String?): String = query.orEmpty().split('&')
    .filter { it.substringBefore('=') != "external_auth" }
    .joinToString("&")
