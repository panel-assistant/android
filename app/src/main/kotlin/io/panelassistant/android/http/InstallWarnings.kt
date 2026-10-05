package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings

internal const val WEBVIEW_DOC = "https://panel-assistant.io/go/docs?page=hardware/readme"

/** A render-blocking warning; a missing renderer can offer an explicit Companion install. */
internal fun installWarning(
    f: HealthAudit.Finding,
    canInstallCompanion: Boolean,
    strings: AppStrings,
): String = when (f.kind) {
    HealthAudit.Kind.WEBVIEW_OLD ->
        """<div class="setup crit">⚠ <b>${esc(strings.get("install.warning.webview_old.title"))}</b> (${esc(f.detail)}) — ${esc(strings.get("install.warning.webview_old.body"))} <a href="$WEBVIEW_DOC" target="_blank" rel="noopener">${esc(strings.get("install.warning.webview_old.help"))}</a> (${esc(formattedString(strings, "install.warning.webview_old.target", "version" to PanelHealth.MIN_CHROMIUM.toString()))}).""" +
            """</div>"""
    HealthAudit.Kind.NO_RENDERER ->
        """<div class="setup">ℹ <b>${esc(strings.get("install.warning.no_renderer.title"))}</b> ${esc(strings.get("install.warning.no_renderer.prefix"))} <a href="${localizedHref("configure", strings)}">${esc(strings.get("shell.nav.configure"))}</a>${esc(strings.get("install.warning.no_renderer.suffix"))}""" +
            (if (canInstallCompanion) """<div style="margin-top:10px"><button class="pbtn"${hardenedApprovalAttrs(strings = strings)} onclick="installComp('companion','update',this)">⬇ ${esc(strings.get("install.warning.no_renderer.install_companion"))}</button> <span class="muted">${esc(strings.get("install.warning.no_renderer.progress"))}</span></div>""" else "") +
            """</div>"""
    HealthAudit.Kind.UPDATE -> "" // shown in the Managed-components card, not as a top warning
    HealthAudit.Kind.SCHEMA_ROLLED_BACK ->
        """<div class="setup crit">⚠ <b>${esc(strings.get("install.warning.schema_rollback.title"))}</b> — ${esc(strings.get("install.warning.schema_rollback.prefix"))} <a href="${localizedHref("configure", strings)}">${esc(strings.get("shell.nav.configure"))}</a>${esc(strings.get("install.warning.schema_rollback.suffix"))}</div>"""
}

/** Visible "this needs root" banner for a root-gated card/control group — shown (never hidden) so a
 *  no-root user sees the feature and what root would unlock, next to controls rendered disabled. */
internal fun rootLockBanner(unlocks: String, strings: AppStrings): String =
    """<div class="setup rootlock">🔒 ${esc(formattedString(strings, "install.lock.root_required", "detail" to unlocks))}</div>"""

internal fun privilegedLockBanner(unlocks: String, strings: AppStrings): String =
    """<div class="setup rootlock">🔒 ${esc(formattedString(strings, "install.lock.privileged_required", "detail" to unlocks))}</div>"""
