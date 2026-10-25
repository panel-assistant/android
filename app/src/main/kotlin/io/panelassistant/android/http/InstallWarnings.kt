package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings

internal const val WEBVIEW_DOC = "https://panel-assistant.io/go/docs?page=hardware/readme"

/** The WebView update steps for a panel model, keyed by profile id. The TPA10 (signature-locked, needs the
 *  root swap) and the original Shelly Wall Display (Shelly's own update ZIP) have their own sections; every
 *  other panel, Play-managed ones included, is a row of the update guide's table above its sideload steps. */
internal fun webViewUpdateSteps(profileId: String): String = when (profileId) {
    "tpa10" -> "https://panel-assistant.io/go/docs?page=hardware/tpa10#webview-update-this-first"
    "shelly-wall-display" -> "https://panel-assistant.io/go/docs?page=hardware/shelly-wall-display#webview"
    else -> "https://panel-assistant.io/go/update-webview"
}

/** A render-blocking warning; a missing renderer can offer an explicit Companion install. */
internal fun installWarning(
    f: HealthAudit.Finding,
    canInstallCompanion: Boolean,
    strings: AppStrings,
): String = when (f.kind) {
    HealthAudit.Kind.WEBVIEW_OLD -> webviewOldNotice(f.detail, strings, manageInstall = false)
    HealthAudit.Kind.NO_RENDERER -> noRendererNotice(
        strings,
        action = if (canInstallCompanion) """<button class="pbtn"${hardenedApprovalAttrs(strings = strings)} onclick="installComp('companion','update',this)">${esc(strings.get("install.warning.no_renderer.install_companion"))}</button> <span class="muted">${esc(strings.get("install.warning.no_renderer.progress"))}</span>""" else "",
    )
    HealthAudit.Kind.UPDATE -> "" // shown in the Managed-components card, not as a top warning
    HealthAudit.Kind.SCHEMA_ROLLED_BACK -> schemaRollbackNotice(f.detail, strings)
}

/** Visible "this needs root" notice for a root-gated card/control group — shown (never hidden) so a
 *  no-root user sees the feature and what root would unlock, next to controls rendered disabled. */
internal fun rootLockBanner(unlocks: String, strings: AppStrings, attrs: String = ""): String =
    titled(NoticeSeverity.UNAVAILABLE, formattedString(strings, "install.lock.root_required", "detail" to unlocks), attrs = attrs).html(strings)

internal fun privilegedLockBanner(unlocks: String, strings: AppStrings): String =
    titled(NoticeSeverity.UNAVAILABLE, formattedString(strings, "install.lock.privileged_required", "detail" to unlocks)).html(strings)
