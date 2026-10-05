package io.panelassistant.android.http

import android.content.Context
import io.panelassistant.android.Config
import io.panelassistant.android.RendererAdmissionPresentation
import io.panelassistant.android.RendererAdmissionRuntime
import io.panelassistant.android.RendererMode
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.util.DashboardTheme

/**
 * The renderer/Home Assistant admission projection, built LIVE on every read rather than through
 * the management cache.
 *
 * Two reasons, both learned the hard way. The state changes during an outage, so a
 * stale-while-revalidate copy would answer a "is the dashboard up?" question with a value from
 * before it went down — the same defect that made the lifecycle row live. And a deployment check
 * judges staleness from `observed_age_ms`, so an age measured against a cached capture would be
 * an age of the cache, not of the observation.
 */
internal fun rendererAdmission(
    appContext: Context,
    config: Config,
    autoBrightnessHttpApi: AutoBrightnessHttpApi,
): RendererAdmissionPresentation {
    val pkg = config.dashboardPackage
    // Only an Ambient panel pays for the runtime read; every other policy reports nothing from it.
    val ambient = if (config.dashboardTheme == DashboardTheme.AMBIENT) autoBrightnessHttpApi.ambientTheme() else null
    val mode = when {
        SystemController.isBuiltinSelection(pkg, appContext.packageName) -> RendererMode.BUILTIN
        pkg.isBlank() -> RendererMode.NONE
        else -> RendererMode.EXTERNAL
    }
    return RendererAdmissionPresentation.of(
        mode = mode,
        haUrl = config.haUrl,
        addressFamilyPolicy = config.mqttAddressFamily,
        live = RendererAdmissionRuntime.current(),
        nowElapsedMs = android.os.SystemClock.elapsedRealtime(),
        processStartElapsedMs = android.os.Process.getStartElapsedRealtime(),
        packageUpdatedAtMs = packageUpdatedAtMs(appContext),
        nowWallMs = System.currentTimeMillis(),
        themePolicy = config.dashboardTheme,
        themeEffectivePolicy = config.dashboardThemeEffective,
        ambientReason = ambient?.reason,
        ambientLevel = ambient?.level,
    )
}

/**
 * When this app package was last installed or replaced, or null when the package manager would
 * not say. Same source as the build token, read as a number rather than an opaque token because a
 * deployment check has to do arithmetic with it.
 *
 * The failure is deliberately not distinguished from an unset value, and deliberately does not
 * fail the status request: this is one figure on a health surface whose whole purpose is to keep
 * answering while things are wrong. Swallowing it is safe because it is reported as null and
 * every consumer treats null as "cannot prove it" — a deployment check refuses a panel that
 * cannot name its own install rather than passing it — so the quiet path is the strict one, not
 * a way through.
 */
private fun packageUpdatedAtMs(appContext: Context): Long? =
    runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).lastUpdateTime }
        .getOrNull()
