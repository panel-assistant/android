package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings
import io.panelassistant.android.PanelStatus
import io.panelassistant.android.control.PowerSafetyAdvisory
import io.panelassistant.android.control.PowerSafetyAdvisoryAction
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.control.zigbeeHealthPresentation
import io.panelassistant.android.i18n.AppLocale

internal fun haSignInBanner(strings: AppStrings): String =
    """<div class="setup">🏠 <b>${esc(strings.get("dashboard.banner.ha_sign_in.title"))}</b> """ +
        """${esc(strings.get("dashboard.banner.ha_sign_in.explanation"))} """ +
        """<a href="${localizedHref("configure#cfg-ha-oauth", strings)}">${esc(strings.get("dashboard.banner.ha_sign_in.action"))}</a>.</div>"""

/** Use only a catalogue record actually resolved in the requested locale; otherwise retain exact HTML. */
private fun translatedText(strings: AppStrings, key: String): String? = runCatching { strings.resolve(key) }
    .getOrNull()
    ?.takeIf { it.language != AppLocale.ENGLISH }
    ?.text

internal fun localizedRecoveryWarning(
    state: PanelStatus.DashboardRecoveryState,
    fallbackHtml: String,
    strings: AppStrings,
): String {
    val key = when (state) {
        PanelStatus.DashboardRecoveryState.NONE -> return fallbackHtml
        PanelStatus.DashboardRecoveryState.BUILTIN_RENDERER -> "runtime.renderer_recovery.builtin"
        PanelStatus.DashboardRecoveryState.EXTERNAL_RENDERER -> "runtime.renderer_recovery.external"
    }
    return translatedText(strings, key)?.let { "⛔ ${esc(it)}" } ?: fallbackHtml
}

internal fun localizedZigbeeWarning(
    snapshot: ZigbeeHealthSnapshot,
    configuredOn: Boolean,
    fallbackHtml: String,
    strings: AppStrings,
): String {
    val presentation = zigbeeHealthPresentation(
        snapshot,
        configuredOn,
    ) ?: return fallbackHtml
    val key = when (presentation.code) {
        "status-zigbee-contained" -> "runtime.zigbee.warning.contained"
        "status-zigbee-containment-incomplete" -> "runtime.zigbee.warning.containment_failed"
        "status-zigbee-runaway" -> "runtime.zigbee.warning.runaway"
        "status-zigbee-high-cpu" -> "runtime.zigbee.warning.degraded_high_cpu"
        "status-zigbee-not-joined" -> "runtime.zigbee.warning.degraded_unjoined"
        "status-zigbee-legacy-watchdog" -> "runtime.zigbee.warning.legacy_watchdog"
        else -> return fallbackHtml
    }
    val translated = translatedText(strings, key) ?: return fallbackHtml
    val action = if (presentation.code == "status-zigbee-not-joined") {
        val label = translatedText(strings, "runtime.zigbee.warning.resolve")
            ?: strings.get("shell.nav.configure")
        " <a href=\"${localizedHref("configure#cfg-zigbee_join", strings)}\">${esc(label)}</a>"
    } else ""
    return "${if (presentation.code in setOf("status-zigbee-contained", "status-zigbee-containment-incomplete", "status-zigbee-runaway")) "⛔" else "⚠"} ${esc(translated)}$action"
}

internal fun localizedStorageBanner(
    storage: HealthAudit.StoragePresentation,
    strings: AppStrings,
): String {
    val fallback = storage.bannerHtml()
    val presentation = storage.warningPresentation ?: return fallback
    val key = when (presentation.code) {
        "status-storage-warning" -> "install.presentation.status_storage_warning"
        "status-storage-critical" -> "install.presentation.status_storage_critical"
        "status-storage-database-failure" -> "install.presentation.status_storage_database_failure"
        else -> return fallback
    }
    val translated = translatedText(strings, key) ?: return fallback
    val rendered = presentation.params.entries.fold(translated) { text, (name, value) ->
        text.replace("{$name}", value)
    }
    val critical = presentation.code != "status-storage-warning"
    return "<div class=\"setup${if (critical) " crit" else ""}\">${esc(rendered)}</div>"
}

internal fun localizedPowerSafetyBanner(
    advisory: PowerSafetyAdvisory,
    inlineRepair: Boolean,
    strings: AppStrings,
): String {
    val fallback = PowerSafetyPresentation.bannerHtml(advisory, inlineRepair)
    if (fallback.isEmpty()) return fallback
    val presentation = PowerSafetyPresentation.warningPresentation(advisory) ?: return fallback
    val levelKey = when (presentation.code) {
        "status-power-at-risk" -> "runtime.power_safety.level.at_risk"
        "status-power-caution" -> "runtime.power_safety.level.caution"
        "status-power-unknown" -> "runtime.power_safety.level.unknown"
        else -> return fallback
    }
    val level = translatedText(strings, levelKey) ?: return fallback
    val summaryKey = when (presentation.code) {
        "status-power-at-risk" -> "runtime.power_safety.summary.at_risk"
        "status-power-caution" -> "runtime.power_safety.summary.caution"
        "status-power-unknown" -> "runtime.power_safety.summary.unknown"
        else -> return fallback
    }
    val summary = translatedText(strings, summaryKey) ?: return fallback
    val actionKey = when (advisory.action) {
        PowerSafetyAdvisoryAction.NONE -> "runtime.power_safety.action.review"
        PowerSafetyAdvisoryAction.REPAIR -> when (advisory.repairCapability.wireValue) {
            "direct_root" -> "runtime.power_safety.action.repair_direct"
            "degraded" -> "runtime.power_safety.action.repair_degraded"
            else -> "runtime.power_safety.action.repair_limited"
        }
        PowerSafetyAdvisoryAction.ACKNOWLEDGE -> if (advisory.acknowledged) {
            "runtime.power_safety.action.acknowledged"
        } else {
            "runtime.power_safety.action.acknowledgeable"
        }
        PowerSafetyAdvisoryAction.MANUAL_ONLY -> "runtime.power_safety.action.manual"
    }
    val actionText = translatedText(strings, actionKey) ?: return fallback
    val control = when {
        !inlineRepair -> " <a href=\"${localizedHref("configure#cfg-keep_awake", strings)}\">${esc(strings.get("shell.nav.configure"))} →</a>"
        advisory.action == PowerSafetyAdvisoryAction.REPAIR -> {
            val label = translatedText(strings, "runtime.power_safety.button.repair") ?: "Repair power safety"
            val title = translatedText(strings, "runtime.power_safety.button.repair_title")
                ?: "Repair is explicit, read-back verified, and never reboots the panel"
            """ <form method="post" action="api/v1/power-safety/repair" data-power-safety-repair style="display:inline"><button class="pbtn" type="submit" data-hardened-approval title="${esc(title)}">${esc(label)}</button> <span class="power-safety-repair-result" role="status" aria-live="polite"></span></form>"""
        }
        advisory.action == PowerSafetyAdvisoryAction.ACKNOWLEDGE -> {
            val fingerprint = requireNotNull(advisory.acknowledgementFingerprint)
            val label = translatedText(strings, "runtime.power_safety.button.hide") ?: "Hide this caution"
            val title = translatedText(strings, "runtime.power_safety.button.hide_title")
                ?: "Hide this unchanged caution in panel web pages; Hardened mode requires physical approval"
            """ <form method="post" action="api/v1/power-safety/acknowledge" data-power-safety-acknowledge style="display:inline"><input type="hidden" name="fingerprint" value="${esc(fingerprint)}"><button class="pbtn" type="submit" data-hardened-approval title="${esc(title)}">${esc(label)}</button> <span class="power-safety-acknowledge-result" role="status" aria-live="polite"></span></form>"""
        }
        else -> ""
    }
    val critical = presentation.code == "status-power-at-risk"
    return "<div class=\"setup${if (critical) " crit" else ""}\" data-power-safety-banner>" +
        "${if (critical) "⛔" else "⚠"} <b>${esc(level)}</b> — ${esc(summary)} ${esc(actionText)}$control</div>"
}

/** One dashboard banner for a health finding. Update findings link to the Install tab (where the user
 *  manages versions) and carry an "Ignore this version" button — a per-version dismissal that stays
 *  hidden until a newer release ships (see Config.ignoreUpdate / UpdateChecker.visible). */
internal fun bannerFor(f: HealthAudit.Finding, strings: AppStrings): String = when (f.kind) {
    HealthAudit.Kind.WEBVIEW_OLD ->
        """<div class="setup crit">⚠ <b>${esc(strings.get("dashboard.banner.webview_old.title"))}</b> (${esc(f.detail)}) — """ +
            """${esc(strings.get("dashboard.banner.webview_old.explanation"))} <a href="$WEBVIEW_DOC" target="_blank" rel="noopener">""" +
            """${esc(strings.get("dashboard.banner.webview_old.update_action"))}</a> """ +
            """${esc(formattedString(strings, "dashboard.banner.webview_old.target", "version" to PanelHealth.MIN_CHROMIUM.toString()))}. """ +
            """<small>${esc(strings.get("dashboard.banner.webview_old.engine_note"))}</small> """ +
            """<a href="${localizedHref("install", strings)}">${esc(strings.get("dashboard.banner.manage_install"))}</a></div>"""
    HealthAudit.Kind.NO_RENDERER ->
        """<div class="setup">ℹ <b>${esc(strings.get("dashboard.banner.no_renderer.title"))}</b> """ +
            """${esc(strings.get("dashboard.banner.no_renderer.configure_prefix"))} <a href="${localizedHref("configure", strings)}">${esc(strings.get("shell.nav.configure"))}</a> """ +
            """${esc(strings.get("dashboard.banner.no_renderer.explanation"))} <small>${esc(strings.get("dashboard.banner.no_renderer.note"))}</small></div>"""
    HealthAudit.Kind.UPDATE -> {
        val u = f.update!!
        """<div class="setup info" data-update="${esc(u.label)}" data-version="${esc(u.latestVersion)}">""" +
            """⬆ <b>${esc(u.label)}</b> ${esc(formattedString(strings, "dashboard.banner.update.available", "latest" to u.latestVersion, "current" to u.displayedCurrentVersion))} — """ +
            """<a href="${localizedHref("install", strings)}">${esc(strings.get("dashboard.banner.manage_install"))}</a> """ +
            """<button class="pbtn" onclick="ignoreUpdate(this)">${esc(strings.get("dashboard.banner.update.ignore"))}</button></div>"""
    }
    HealthAudit.Kind.SCHEMA_ROLLED_BACK ->
        """<div class="setup crit">⚠ <b>${esc(strings.get("dashboard.banner.schema_rollback.title"))}</b> (${esc(f.detail)}) — """ +
            """${esc(strings.get("dashboard.banner.schema_rollback.explanation"))} """ +
            """<a href="${localizedHref("configure", strings)}">${esc(strings.get("dashboard.banner.schema_rollback.configure_action"))}</a> """ +
            """${esc(strings.get("dashboard.banner.schema_rollback.or_restore"))} <a href="${localizedHref("install", strings)}">${esc(strings.get("shell.nav.install"))}</a>.</div>"""
}
