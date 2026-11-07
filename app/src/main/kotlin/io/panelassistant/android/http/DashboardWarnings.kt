package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings
import io.panelassistant.android.PanelStatus
import io.panelassistant.android.control.PowerRiskLevel
import io.panelassistant.android.control.PowerSafetyAdvisory
import io.panelassistant.android.control.PowerSafetyAdvisoryAction
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.control.zigbeeHealthPresentation
import io.panelassistant.android.i18n.AppLocale
import io.panelassistant.android.i18n.formatNamedTemplate

/** One advisory decided once: the page draws it with [html]; the status `warnings[]` carries [wire] and
 *  `warning_severities[]` carries the severity. Title, detail and action are pre-escaped HTML. */
internal data class NoticeSpec(
    val severity: NoticeSeverity,
    val title: String,
    val detail: String = "",
    val action: String = "",
    val attrs: String = "",
) {
    fun html(strings: AppStrings): String = notice(severity, strings, title, detail, action, attrs)
    fun wire(): String = if (detail.isEmpty()) title else "$title — $detail"
}

private val OLD_GLYPH = Regex("^[⛔⚠ℹ⬆⟳🔒🏠👋]️?\\s*")

/** Drops a leading glyph from the retired banner set; catalogue text may still carry one. */
internal fun stripNoticeGlyph(text: String): String = text.replace(OLD_GLYPH, "")

/** Catalogue sentences of the form "Title — detail" split at the first dash; no dash means title only. */
internal fun titled(severity: NoticeSeverity, text: String, action: String = "", attrs: String = ""): NoticeSpec {
    val plain = stripNoticeGlyph(text).trim()
    val at = plain.indexOf(" — ")
    return if (at > 0) NoticeSpec(severity, esc(plain.substring(0, at)), esc(plain.substring(at + 3)), action, attrs)
    else NoticeSpec(severity, esc(plain), "", action, attrs)
}

internal fun haSignInBanner(strings: AppStrings): String = notice(
    NoticeSeverity.WARNING, strings,
    title = esc(strings.get("dashboard.banner.ha_sign_in.title")),
    detail = """${esc(strings.get("dashboard.banner.ha_sign_in.explanation"))} """ +
        """<a href="${localizedHref("configure#cfg-ha-oauth", strings)}">${esc(strings.get("dashboard.banner.ha_sign_in.action"))}</a>.""",
)

internal fun recoveryNotice(state: PanelStatus.DashboardRecoveryState, strings: AppStrings): NoticeSpec? {
    val key = when (state) {
        PanelStatus.DashboardRecoveryState.NONE -> return null
        PanelStatus.DashboardRecoveryState.BUILTIN_RENDERER -> "runtime.renderer_recovery.builtin"
        PanelStatus.DashboardRecoveryState.EXTERNAL_RENDERER -> "runtime.renderer_recovery.external"
    }
    return titled(NoticeSeverity.ACTION_NEEDED, strings.get(key))
}

internal fun zigbeeNotice(snapshot: ZigbeeHealthSnapshot, configuredOn: Boolean, strings: AppStrings): NoticeSpec? {
    val presentation = zigbeeHealthPresentation(snapshot, configuredOn) ?: return null
    val (key, severity) = when (presentation.code) {
        "status-zigbee-contained" -> "runtime.zigbee.warning.contained" to NoticeSeverity.WARNING
        "status-zigbee-containment-incomplete" -> "runtime.zigbee.warning.containment_failed" to NoticeSeverity.ACTION_NEEDED
        "status-zigbee-runaway" -> "runtime.zigbee.warning.runaway" to NoticeSeverity.ACTION_NEEDED
        "status-zigbee-high-cpu" -> "runtime.zigbee.warning.degraded_high_cpu" to NoticeSeverity.WARNING
        "status-zigbee-not-joined" -> "runtime.zigbee.warning.degraded_unjoined" to NoticeSeverity.WARNING
        "status-zigbee-legacy-watchdog" -> "runtime.zigbee.warning.legacy_watchdog" to NoticeSeverity.WARNING
        else -> return null
    }
    val action = if (presentation.code == "status-zigbee-not-joined") {
        """<a href="${localizedHref("configure#cfg-zigbee_join", strings)}">${esc(strings.get("runtime.zigbee.warning.resolve"))}</a>"""
    } else ""
    return titled(severity, strings.get(key), action)
}

internal fun storageNotice(storage: HealthAudit.StoragePresentation, strings: AppStrings): NoticeSpec? {
    val presentation = storage.warningPresentation ?: return null
    val (key, severity) = when (presentation.code) {
        "status-storage-warning" -> "install.presentation.status_storage_warning" to NoticeSeverity.WARNING
        "status-storage-critical" -> "install.presentation.status_storage_critical" to NoticeSeverity.ACTION_NEEDED
        "status-storage-database-failure" -> "install.presentation.status_storage_database_failure" to NoticeSeverity.ACTION_NEEDED
        else -> return null
    }
    val rendered = formatNamedTemplate(strings.get(key), presentation.params)
    val spec = titled(severity, rendered)
    // The measured summary and action are English runtime text: appended to the catalogue detail
    // wherever the page itself is English, so the figures and the escalation step are never lost.
    val english = runCatching { strings.resolve(key).language == AppLocale.ENGLISH }.getOrDefault(true)
    return if (english) spec.copy(detail = "${spec.detail} ${esc(storage.summary)} ${esc(storage.action)}".trim()) else spec
}

/** Power safety as one notice whenever the assessment warns. [inlineRepair] picks the repair or
 *  acknowledge form as the action; otherwise the action links to Configure. */
internal fun powerSafetyNotice(
    advisory: PowerSafetyAdvisory,
    inlineRepair: Boolean,
    strings: AppStrings,
): NoticeSpec? {
    val assessment = advisory.assessment
    if (!assessment.warning) return null
    val (severity, levelKey, summaryKey) = when (assessment.level) {
        PowerRiskLevel.AT_RISK -> Triple(NoticeSeverity.ACTION_NEEDED, "runtime.power_safety.level.at_risk", "runtime.power_safety.summary.at_risk")
        PowerRiskLevel.CAUTION -> Triple(NoticeSeverity.WARNING, "runtime.power_safety.level.caution", "runtime.power_safety.summary.caution")
        PowerRiskLevel.UNKNOWN -> Triple(NoticeSeverity.WARNING, "runtime.power_safety.level.unknown", "runtime.power_safety.summary.unknown")
        PowerRiskLevel.SAFE -> return null
    }
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
    val control = when {
        !inlineRepair -> """<a href="${localizedHref("configure#cfg-keep_awake", strings)}">${esc(strings.get("shell.nav.configure"))} →</a>"""
        advisory.action == PowerSafetyAdvisoryAction.REPAIR ->
            """<form method="post" action="api/v1/power-safety/repair" data-power-safety-repair style="display:inline"><button class="pbtn" type="submit" data-hardened-approval title="${esc(strings.get("runtime.power_safety.button.repair_title"))}">${esc(strings.get("runtime.power_safety.button.repair"))}</button> <span class="power-safety-repair-result" role="status" aria-live="polite"></span></form>"""
        advisory.action == PowerSafetyAdvisoryAction.ACKNOWLEDGE -> {
            val fingerprint = requireNotNull(advisory.acknowledgementFingerprint)
            """<form method="post" action="api/v1/power-safety/acknowledge" data-power-safety-acknowledge style="display:inline"><input type="hidden" name="fingerprint" value="${esc(fingerprint)}"><button class="pbtn" type="submit" data-hardened-approval title="${esc(strings.get("runtime.power_safety.button.hide_title"))}">${esc(strings.get("runtime.power_safety.button.hide"))}</button> <span class="power-safety-acknowledge-result" role="status" aria-live="polite"></span></form>"""
        }
        else -> ""
    }
    return NoticeSpec(
        severity,
        title = esc(strings.get(levelKey)),
        detail = "${esc(strings.get(summaryKey))} ${esc(strings.get(actionKey))}",
        action = control,
        attrs = " data-power-safety-banner",
    )
}

/** The page banner for power safety: hidden once the user acknowledged an unchanged caution. */
internal fun powerSafetyBanner(advisory: PowerSafetyAdvisory, inlineRepair: Boolean, strings: AppStrings): String =
    if (!advisory.bannerVisible) "" else powerSafetyNotice(advisory, inlineRepair, strings)?.html(strings).orEmpty()

internal fun storageBanner(storage: HealthAudit.StoragePresentation, strings: AppStrings): String =
    storageNotice(storage, strings)?.html(strings).orEmpty()

/** The WebView-too-old notice; its action opens this panel model's update steps ([webViewUpdateSteps]). */
internal fun webviewOldNotice(detail: String, strings: AppStrings, manageInstall: Boolean, profileId: String): String = notice(
    NoticeSeverity.ACTION_NEEDED, strings,
    title = esc(strings.get("dashboard.banner.webview_old.title")),
    detail = """(${esc(detail)}) ${esc(strings.get("dashboard.banner.webview_old.explanation"))} """ +
        """${esc(formattedString(strings, "dashboard.banner.webview_old.target", "version" to PanelHealth.MIN_CHROMIUM.toString()))}.""" +
        (if (manageInstall) """ <a href="${localizedHref("install", strings)}">${esc(strings.get("dashboard.banner.manage_install"))}</a>""" else ""),
    action = """<a href="${webViewUpdateSteps(profileId)}" target="_blank" rel="noopener">${esc(strings.get("dashboard.banner.webview_old.update_action"))}</a>""",
)

internal fun noRendererNotice(strings: AppStrings, action: String = ""): String = notice(
    NoticeSeverity.NOTE, strings,
    title = esc(strings.get("dashboard.banner.no_renderer.title")),
    detail = """${esc(strings.get("dashboard.banner.no_renderer.configure_prefix"))} <a href="${localizedHref("configure", strings)}">${esc(strings.get("shell.nav.configure"))}</a> """ +
        """${esc(strings.get("dashboard.banner.no_renderer.explanation"))} <small>${esc(strings.get("dashboard.banner.no_renderer.note"))}</small>""",
    action = action,
)

internal fun schemaRollbackNotice(detail: String, strings: AppStrings): String = notice(
    NoticeSeverity.ACTION_NEEDED, strings,
    title = esc(strings.get("dashboard.banner.schema_rollback.title")),
    detail = """(${esc(detail)}) ${esc(strings.get("dashboard.banner.schema_rollback.explanation"))} """ +
        """${esc(strings.get("dashboard.banner.schema_rollback.or_restore"))} <a href="${localizedHref("install", strings)}">${esc(strings.get("shell.nav.install"))}</a>.""",
    action = """<a href="${localizedHref("configure", strings)}">${esc(strings.get("dashboard.banner.schema_rollback.configure_action"))}</a>""",
)

/** One dashboard banner for a health finding. Update findings link to the Install tab (where the user
 *  manages versions) and carry an "Ignore this version" button — a per-version dismissal that stays
 *  hidden until a newer release ships (see Config.ignoreUpdate / UpdateChecker.visible). */
internal fun bannerFor(f: HealthAudit.Finding, strings: AppStrings, profileId: () -> String): String = when (f.kind) {
    HealthAudit.Kind.WEBVIEW_OLD -> webviewOldNotice(f.detail, strings, manageInstall = true, profileId = profileId())
    HealthAudit.Kind.NO_RENDERER -> noRendererNotice(strings)
    HealthAudit.Kind.UPDATE -> {
        val u = f.update!!
        notice(
            NoticeSeverity.UPDATE, strings,
            title = esc(u.label),
            detail = """${esc(formattedString(strings, "dashboard.banner.update.available", "latest" to u.latestVersion, "current" to u.currentVersion))} — """ +
                """<a href="${localizedHref("install", strings)}">${esc(strings.get("dashboard.banner.manage_install"))}</a>""",
            action = """<button class="pbtn" onclick="ignoreUpdate(this)">${esc(strings.get("dashboard.banner.update.ignore"))}</button>""",
            attrs = """ data-update="${esc(u.label)}" data-version="${esc(u.latestVersion)}"""",
        )
    }
    HealthAudit.Kind.SCHEMA_ROLLED_BACK -> schemaRollbackNotice(f.detail, strings)
}
