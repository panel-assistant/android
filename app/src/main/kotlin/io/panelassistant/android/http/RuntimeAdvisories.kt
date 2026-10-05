package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.PanelStatus
import io.panelassistant.android.control.CompanionDb
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.control.ZigbeeHealthState
import io.panelassistant.android.i18n.AppLocale
import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.i18n.Strings as AppStrings

/** Render-blocking warnings not modelled by HealthAudit: a crash-looping dashboard app, and Companion
 *  server inspection/blank-internal-URL findings — the latter only when Companion is the active renderer
 *  ([CompanionDb.warningApplies]). Shown on BOTH the dashboard
 *  banner and the Install tab as high-severity (`crit`). [inlineRepair] adds the one-tap repair button
 *  (Install tab, where install.js is loaded); the dashboard links to the Install tab for the action. */
internal fun adHocWarnings(
    config: Config,
    catalogueLoader: CatalogueLoader,
    directSuReady: Boolean,
    densityBase: Int?,
    radioStatus: () -> ZigbeeHealthSnapshot?,
    dashboardRecoveryState: () -> PanelStatus.DashboardRecoveryState,
    companion: CompanionDb.ServerObservation?,
    inlineRepair: Boolean,
    strings: AppStrings = catalogueLoader.strings(AppLocale.ENGLISH),
): String = buildString {
    radioStatus()?.let { z ->
        zigbeeWarningText(z, config.zigbeeRouterConfigured && config.zigbeeRouterEnabled)?.let { warning ->
            append(
                """<div class="setup${if (z.state in setOf(ZigbeeHealthState.RUNAWAY, ZigbeeHealthState.CONTAINMENT_FAILED)) " crit" else ""}">""",
            )
            append(localizedZigbeeWarning(z, config.zigbeeRouterConfigured && config.zigbeeRouterEnabled, warning, strings))
            append("</div>")
        }
    }
    if (io.panelassistant.android.control.BuiltinDashboard.authLatched) append(
        """<div class="setup crit">⛔ <b>${esc(strings.get("dashboard.banner.auth_rejected.title"))}</b> — """ +
            """${esc(strings.get("dashboard.banner.auth_rejected.explanation"))} """ +
            """<a href="${localizedHref("configure#cfg-ha-oauth", strings)}">${esc(strings.get("dashboard.banner.auth_rejected.action"))}</a>; """ +
            """${esc(strings.get("dashboard.banner.auth_rejected.reload_suffix"))}</div>""",
    )
    val recoveryState = dashboardRecoveryState()
    dashboardRecoveryWarning(recoveryState)?.let { warning ->
        append("""<div class="setup crit">${localizedRecoveryWarning(recoveryState, warning, strings)}</div>""")
    }
    // Shared companion internal-URL decision (CompanionDb.warning); this surface renders it as a banner
    // with the one-tap repair button ([inlineRepair], Install tab) or an Install-tab link (dashboard).
    when (val w = CompanionDb.warning(config.dashboardPackage, companion, directSuReady)) {
        is CompanionDb.Warning.NeedsRepair -> {
            val action = if (inlineRepair)
                """<div style="margin-top:10px"><button class="pbtn"${hardenedApprovalAttrs(strings = catalogueLoader.strings(AppLocale.ENGLISH))} onclick="repairCompUrl(this)">⚙ ${esc(strings.get("dashboard.banner.companion_url.repair"))}</button> <span id="cu-fix" class="muted"></span></div>"""
            else """ <a href="${localizedHref("install", strings)}">${esc(strings.get("dashboard.banner.companion_url.install_action"))}</a>"""
            val summaryKey = if (w.affected == 1) {
                "dashboard.banner.companion_url.summary_one"
            } else {
                "dashboard.banner.companion_url.summary_many"
            }
            append(
                """<div class="setup crit">⚠ <b>${esc(strings.get("dashboard.banner.companion_url.title"))}</b> """ +
                    """${esc(formattedString(strings, summaryKey, "count" to w.affected.toString()))} """ +
                    """<i>"Missing 'Host' header"</i>. ${esc(strings.get("dashboard.banner.companion_url.explanation"))}$action</div>""",
            )
        }
        CompanionDb.Warning.ProbeFailed -> append(
            """<div class="setup">⚠ <b>${esc(strings.get("dashboard.banner.companion_probe_failed.title"))}</b> — """ +
                """${esc(strings.get("dashboard.banner.companion_probe_failed.explanation"))}</div>""",
        )
        null -> {}
    }
    // Built-in renderer zoomed off 100% (usually carried over from the Companion's "Page zoom"). App
    // zoom is a compatibility lever; the cleaner way to size the dashboard is the panel display density
    // — so we only nudge when that's actually available (rooted / helper daemon). No root = app zoom is
    // the only sizing tool, so stay quiet. densityBase comes from the shared snapshot (no su round-trip).
    val zoom = config.dashboardZoom
    if ((config.dashboardPackage.isBlank() || config.dashboardPackage == SystemController.BUILTIN_DASHBOARD) && zoom != 100 && densityBase != null) {
        // Reset is a plain form POST (no JS), so it works on the dashboard banner too — not just the
        // Install tab. The message already links to the Display-sizing card.
        append(
            """<div class="setup">⚠ <b>${esc(formattedString(strings, "dashboard.banner.zoom.title", "zoom" to zoom.toString()))}</b> """ +
                """${esc(strings.get("dashboard.banner.zoom.explanation"))} """ +
                """<a href="${localizedHref("install#cfg-display", strings)}">${esc(strings.get("dashboard.banner.zoom.display_density"))}</a>, """ +
                """${esc(strings.get("dashboard.banner.zoom.action_suffix"))}""" +
                """ <form method="post" action="api/v1/config" style="display:inline">""" +
                """<input type="hidden" name="dashboard_zoom" value="100">""" +
                """<button class="pbtn" type="submit">${esc(strings.get("dashboard.banner.zoom.reset"))}</button></form></div>""",
        )
    }
}
