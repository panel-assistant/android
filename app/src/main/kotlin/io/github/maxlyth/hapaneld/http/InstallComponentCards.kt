package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.util.CompanionInstaller

/** Managed-components card. ha-paneld + HA Companion get a channel + version picker (default channel
 *  from Configure; up to 10 recent versions hydrated by install.js) with a release-notes link and an
 *  Install-selected-version button. The System WebView is a single known-good build (heal/up-to-date).
 *  All actions POST /api/v1/install/component and poll /api/v1/install/status. */
internal fun componentsCardHtml(
    wv: PanelInfo.WebViewStatus,
    root: Boolean,
    installer: Boolean,
    strings: AppStrings,
    config: Config,
    compPkg: String?,
    compCur: String?,
    hasRecommendedWebView: Boolean,
): String {
    val paneldCur = Config.VERSION
    val compFull = compPkg == CompanionInstaller.FULL_PKG

    val paneldRow = pickerRow("paneld", "ha-paneld", paneldCur, config.updateChannel, installer, strings)
    // A Play-managed FULL Companion must never be touched by ha-paneld — show it read-only.
    val compRow = if (compFull)
        simpleRow("HA Companion", compCur, """<span class="muted">${esc(strings.get("install.components.play_managed"))}</span>""", strings)
    else pickerRow("companion", "HA Companion", compCur, config.companionUpdateChannel, installer, strings)
    val wvAction = when {
        wv.playManaged -> """<span class="muted">${esc(strings.get("install.components.google_play_managed"))}</span>"""
        wv.tooOld && hasRecommendedWebView && root -> """<button class="pbtn"${hardenedApprovalA11yAttrs(strings = strings)} onclick="installComp('webview','update',this)">⬇ ${esc(strings.get("install.components.update_webview"))}</button>"""
        wv.tooOld && hasRecommendedWebView -> """<span class="muted">${esc(strings.get("install.components.root_update_required"))}</span>"""
        wv.tooOld -> """<span class="muted">${esc(strings.get("install.components.no_known_build"))}</span>"""
        else -> """<span class="muted">${esc(strings.get("install.components.up_to_date"))}</span>"""
    }
    val installNote = if (installer) "" else """<p class="note">⚠ ${esc(strings.get("install.components.privileged_unavailable"))}</p>"""
    val title = if (installer || (wv.tooOld && hasRecommendedWebView && root)) {
        hardenedApprovalCardTitle(esc(strings.get("install.components.title")), conditional = true, strings = strings)
    } else {
        "<h2>${esc(strings.get("install.components.title"))}</h2>"
    }
    return """<div class="card" data-layout-key="managed-components">$title
$paneldRow
$compRow
${simpleRow("System WebView", wv.display, wvAction, strings)}
$installNote
<p class="note">${esc(strings.get("install.components.channel_prefix"))} <a href="${localizedHref("configure", strings)}">${esc(strings.get("shell.nav.configure"))}</a>${esc(strings.get("install.components.channel_suffix"))}</p>
<p class="note" id="comp-msg"></p></div>"""
}

/** A component row with a channel + version picker (versions hydrated by install.js), a release-notes
 *  link, and an Install button — for the GitHub-hosted components (ha-paneld, HA Companion). The
 *  channel select defaults to [defaultChannel] (the Configure-tab setting). */
private fun pickerRow(
    name: String,
    label: String,
    installed: String?,
    defaultChannel: String,
    installer: Boolean,
    strings: AppStrings,
): String {
    fun sel(v: String) = if (defaultChannel == v) " selected" else ""
    return """<div class="comprow" data-name="${esc(name)}">
<div class="compname"><b>${esc(label)}</b> <span class="muted">${if (installed != null) """${esc(strings.get("install.shared.installed"))} <span class="cver">${esc(installed)}</span>""" else """<span class="cver">${esc(strings.get("install.shared.not_installed"))}</span>"""}</span></div>
<div class="comppick">
<label class="muted">${esc(strings.get("install.components.channel"))} <select class="cchan" onchange="loadVersions('$name')"><option value="stable"${sel("stable")}>${esc(strings.get("install.components.stable"))}</option><option value="prerelease"${sel("prerelease")}>${esc(strings.get("install.components.prerelease"))}</option></select></label>
<label class="muted">${esc(strings.get("install.shared.version"))} <select class="cvsel" onchange="verChanged('$name')"><option>${esc(strings.get("install.shared.loading"))}</option></select></label>
<a class="gh gh-inline cnotes" target="_blank" rel="noopener" title="${esc(strings.get("install.components.release_notes"))}" aria-label="${esc(strings.get("install.components.release_notes"))}" style="visibility:hidden"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="$GH_ICON"/></svg></a>
${if (installer) """<button class="pbtn cinstall"${hardenedApprovalA11yAttrs(strings = strings)} onclick="installSel('$name',this)" data-root="1" disabled>${esc(strings.get("install.components.install"))}</button>"""
        else """<a class="pbtn cdl" style="display:none" target="_blank" rel="noopener" title="${esc(strings.get("install.components.download_apk_help"))}">⬇ ${esc(strings.get("install.components.download_apk"))}</a>"""}
</div></div>"""
}

/** A component row with no picker — installed version + a single action/state (System WebView, or a
 *  Play-managed Companion). */
private fun simpleRow(label: String, installed: String?, action: String, strings: AppStrings): String =
    """<div class="comprow">
<div class="compname"><b>${esc(label)}</b> <span class="muted">${if (installed != null) """${esc(strings.get("install.shared.installed"))} <span class="cver">${esc(installed)}</span>""" else """<span class="cver">${esc(strings.get("install.shared.not_installed"))}</span>"""}</span></div>
<div class="comppick">$action</div></div>"""
