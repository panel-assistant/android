package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings

internal const val WEBVIEW_DOC = "https://panel-assistant.io/go/docs?page=hardware/readme"

/** One top-of-tab warning for a render-blocking finding (WebView old / no dashboard app). WebView gets
 *  the inline "Update WebView now" heal button when [canHeal]; a missing renderer gets a one-tap
 *  "Install HA Companion" button when [canInstallCompanion]. */
internal fun installWarning(
    f: HealthAudit.Finding,
    canHeal: Boolean,
    canInstallCompanion: Boolean,
    strings: AppStrings,
): String = when (f.kind) {
    HealthAudit.Kind.WEBVIEW_OLD ->
        """<div class="setup crit">⚠ <b>${esc(strings.get("install.warning.webview_old.title"))}</b> (${esc(f.detail)}) — ${esc(strings.get("install.warning.webview_old.body"))} <a href="$WEBVIEW_DOC" target="_blank" rel="noopener">${esc(strings.get("install.warning.webview_old.help"))}</a> (${esc(formattedString(strings, "install.warning.webview_old.target", "version" to PanelHealth.MIN_CHROMIUM.toString()))}).""" +
            (if (canHeal) """<div style="margin-top:10px"><button class="pbtn"${hardenedApprovalAttrs(strings = strings)} onclick="healWebView(this)">⬇ ${esc(strings.get("install.warning.webview_old.update"))}</button> <span id="wv-heal" class="muted"></span></div>""" else "") +
            """</div>"""
    HealthAudit.Kind.NO_RENDERER ->
        """<div class="setup">ℹ <b>${esc(strings.get("install.warning.no_renderer.title"))}</b> ${esc(strings.get("install.warning.no_renderer.prefix"))} <a href="${localizedHref("configure", strings)}">${esc(strings.get("shell.nav.configure"))}</a>${esc(strings.get("install.warning.no_renderer.suffix"))}""" +
            (if (canInstallCompanion) """<div style="margin-top:10px"><button class="pbtn"${hardenedApprovalAttrs(strings = strings)} onclick="installComp('companion','update',this)">⬇ ${esc(strings.get("install.warning.no_renderer.install_companion"))}</button> <span class="muted">${esc(strings.get("install.warning.no_renderer.progress"))}</span></div>""" else "") +
            """</div>"""
    HealthAudit.Kind.UPDATE -> "" // shown in the Managed-components card, not as a top warning
    HealthAudit.Kind.SCHEMA_ROLLED_BACK ->
        """<div class="setup crit">⚠ <b>${esc(strings.get("install.warning.schema_rollback.title"))}</b> — ${esc(strings.get("install.warning.schema_rollback.prefix"))} <a href="${localizedHref("configure", strings)}">${esc(strings.get("shell.nav.configure"))}</a>${esc(strings.get("install.warning.schema_rollback.suffix"))}</div>"""
}

internal fun rootLockBanner(unlocks: String, strings: AppStrings): String =
    """<div class="setup rootlock">🔒 ${esc(formattedString(strings, "install.lock.root_required", "detail" to unlocks))}</div>"""

internal fun privilegedLockBanner(unlocks: String, strings: AppStrings): String =
    """<div class="setup rootlock">🔒 ${esc(formattedString(strings, "install.lock.privileged_required", "detail" to unlocks))}</div>"""
