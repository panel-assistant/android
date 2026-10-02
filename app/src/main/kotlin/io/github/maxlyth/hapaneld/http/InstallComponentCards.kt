package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.util.CompanionInstaller

/** Managed components: PA chooses the panel channel; Companion retains its request-local picker. */
internal fun componentsCardHtml(
    installer: Boolean,
    strings: AppStrings,
    compPkg: String?,
    compCur: String?,
): String {
    val paneldCur = Config.VERSION
    val compFull = compPkg == CompanionInstaller.FULL_PKG

    val paneldRow = pickerRow("paneld", "ha-paneld", paneldCur, installer, strings)
    // A Play-managed FULL Companion must never be touched by ha-paneld — show it read-only.
    val compRow = if (compFull)
        simpleRow("HA Companion", compCur, """<span class="muted">${esc(strings.get("install.components.play_managed"))}</span>""", strings)
    else pickerRow("companion", "HA Companion", compCur, installer, strings)
    val installNote = if (installer) "" else """<p class="note">⚠ ${esc(strings.get("install.components.privileged_unavailable"))}</p>"""
    val title = if (installer) {
        hardenedApprovalCardTitle(esc(strings.get("install.components.title")), conditional = true, strings = strings)
    } else {
        "<h2>${esc(strings.get("install.components.title"))}</h2>"
    }
    return """<div class="card" data-layout-key="managed-components">$title
$paneldRow
$compRow
$installNote
<p class="note" id="comp-msg"></p></div>"""
}

/** A version picker hydrated by install.js, with PA's effective panel channel or Companion's channel
 *  selector, release notes, and the install/download control. */
private fun pickerRow(
    name: String,
    label: String,
    installed: String?,
    installer: Boolean,
    strings: AppStrings,
): String {
    val channelControl = if (name == "paneld") """<span class="cpa-channel">—</span>""" else
        """<select class="cchan" onchange="loadVersions('$name')"><option value="stable" selected>${esc(strings.get("install.components.stable"))}</option><option value="prerelease">${esc(strings.get("install.components.prerelease"))}</option></select>"""
    return """<div class="comprow" data-name="${esc(name)}">
<div class="compname"><b>${esc(label)}</b> <span class="muted">${if (installed != null) """${esc(strings.get("install.shared.installed"))} <span class="cver">${esc(installed)}</span>""" else """<span class="cver">${esc(strings.get("install.shared.not_installed"))}</span>"""}</span></div>
<div class="comppick">
<label class="muted">${esc(strings.get("install.components.channel"))} $channelControl</label>
<label class="muted">${esc(strings.get("install.shared.version"))} <select class="cvsel" onchange="verChanged('$name')"><option>${esc(strings.get("install.shared.loading"))}</option></select></label>
<a class="gh gh-inline cnotes" target="_blank" rel="noopener" title="${esc(strings.get("install.components.release_notes"))}" aria-label="${esc(strings.get("install.components.release_notes"))}" style="visibility:hidden"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="$GH_ICON"/></svg></a>
${if (installer) """<button class="pbtn cinstall"${hardenedApprovalA11yAttrs(strings = strings)} onclick="installSel('$name',this)" data-root="1" disabled>${esc(strings.get("install.components.install"))}</button>"""
        else """<a class="pbtn cdl" style="display:none" target="_blank" rel="noopener" title="${esc(strings.get("install.components.download_apk_help"))}">⬇ ${esc(strings.get("install.components.download_apk"))}</a>"""}
</div></div>"""
}

/** Installed version and state for a Play-managed Companion. */
private fun simpleRow(label: String, installed: String?, action: String, strings: AppStrings): String =
    """<div class="comprow">
<div class="compname"><b>${esc(label)}</b> <span class="muted">${if (installed != null) """${esc(strings.get("install.shared.installed"))} <span class="cver">${esc(installed)}</span>""" else """<span class="cver">${esc(strings.get("install.shared.not_installed"))}</span>"""}</span></div>
<div class="comppick">$action</div></div>"""
