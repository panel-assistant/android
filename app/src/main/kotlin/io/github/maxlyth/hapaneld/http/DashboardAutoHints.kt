package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings

/** What the "auto" (blank) package settings actually resolved to — shown as `auto (label)` in the
 *  dashboard rows and as the Configure-field placeholder, so "auto" is never a mystery. When no
 *  launcher app resolves, the Launcher key falls back to ha-paneld's own admin launcher (see
 *  SystemController.launchLauncher) — say so instead of leaving a "—" that reads like a dead key. */
internal fun dashboardAutoHints(system: SystemController, strings: AppStrings): Map<String, String> = buildMap {
    system.resolveDashboard("").takeIf { it.isNotBlank() }?.let {
        put("dashboard_package", dashboardRendererAutoLabel(it, strings))
    }
    put("launcher_package", system.resolvedLauncher("") ?: "ha-paneld admin launcher")
    // Unset home_dashboard = reload/boot land on whatever HA's frontend picks. On this path,
    // resolve the HA user's profile default (or the system fallback) in-band so the UI shows
    // a concrete target instead of an abstract description.
    put("home_dashboard", strings.get("dashboard.value.ha_default_view"))
}

private fun dashboardRendererAutoLabel(resolved: String, strings: AppStrings): String =
    if (resolved == SystemController.BUILTIN_DASHBOARD) strings.get("dashboard.value.builtin_renderer") else resolved
