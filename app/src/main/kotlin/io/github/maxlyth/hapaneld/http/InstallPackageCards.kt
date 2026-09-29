package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.i18n.Strings as AppStrings
import io.github.maxlyth.hapaneld.Config

/** "Install an APK" card (Install tab). ⚠ Root-installs an arbitrary user-supplied APK over the
 *  unauthenticated LAN-trust :8888 — carries a prominent in-card security warning, an enable toggle
 *  (config.apkUploadAllowed), and a parse-then-confirm flow (see install.js). Root/helper-gated.
 *
 *  Two sources feed one review: a local file, or a link the panel fetches itself. The link exists
 *  because a phone browser may refuse to offer a downloaded APK to the file picker at all, which
 *  leaves upload-only administrators with no route. Both end at the same inspected staged file and
 *  the same confirm-before-install button. */
internal fun apkCardHtml(root: Boolean, strings: AppStrings, config: Config): String {
    val body = if (!root) {
        """<p class="note">⚠ ${esc(strings.get("install.apk.root_unavailable"))}</p>"""
    } else {
        val allowed = config.apkUploadAllowed
        """<div class="setup">⚠ <b>${esc(strings.get("install.apk.security_title"))}</b> ${esc(strings.get("install.apk.security_warning"))} """ +
            """<small>(${esc(strings.get("install.apk.security_future_auth"))})</small></div>
<label style="display:flex;flex-direction:row;gap:8px;align-items:center;margin:10px 0"><input type="checkbox" id="apk-allow" ${if (allowed) "checked" else ""} onchange="apkAllow(this)"> ${esc(strings.get("install.apk.enable"))}</label>
<div id="apk-ui"${if (allowed) "" else " style=\"display:none\""}>
<label class="pbtn" style="cursor:pointer">⭱ ${esc(strings.get("install.apk.choose"))}<input type="file" id="apk-file" accept=".apk,application/vnd.android.package-archive" style="display:none" onchange="apkPick(this)"></label>
<label style="margin-top:10px">${esc(strings.get("install.apk.fetch_label"))}<input type="url" id="apk-url" inputmode="url" autocomplete="off" spellcheck="false" placeholder="https://example.com/app.apk"></label>
<button class="pbtn" style="margin-top:8px" onclick="apkFetchUrl()">⇩ ${esc(strings.get("install.apk.fetch"))}</button>
<div id="apk-preview" style="margin-top:10px"></div>
</div>"""
    }
    // Both actions in this card are approval-gated in Hardened mode — fetching, because it aims the
    // panel at a destination someone chose remotely, and installing — so the card title carries the
    // shield rather than each control repeating it.
    val title = if (root) hardenedApprovalCardTitle(esc(strings.get("install.apk.title")), strings = strings) else "<h2>${esc(strings.get("install.apk.title"))}</h2>"
    return """<div class="card" data-layout-key="apk-install">$title
<p class="note">${esc(strings.get("install.apk.description"))}</p>
$body
<p class="note" id="apk-msg"></p></div>"""
}

/** "Uninstall an app" card. Lists only removable apps (see packagesJson) so the picker can't strand the
 *  panel; the endpoint additionally refuses ha-paneld itself. Root-gated. */
internal fun uninstallCardHtml(root: Boolean, strings: AppStrings): String {
    val body = if (!root) """<p class="note">⚠ ${esc(strings.get("install.uninstall.root_unavailable"))}</p>"""
    else """<p class="note">${esc(strings.get("install.uninstall.description_prefix"))} <a href="${localizedHref("install#cfg-tame", strings)}">${esc(strings.get("install.uninstall.tame_link"))}</a>${esc(strings.get("install.uninstall.description_suffix"))}</p>
<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
<select id="uninst-pkg" style="min-width:220px;background:#1c1c1c;color:#eee;border:1px solid #444;border-radius:7px;padding:5px 8px"><option>${esc(strings.get("install.shared.loading"))}</option></select>
<button class="pbtn"${hardenedApprovalA11yAttrs(strings = strings)} onclick="doUninstall(this)">${esc(strings.get("install.uninstall.action"))}</button>
</div>
<p class="note" id="uninst-msg"></p>"""
    val title = if (root) hardenedApprovalCardTitle(esc(strings.get("install.uninstall.title")), strings = strings) else "<h2>${esc(strings.get("install.uninstall.title"))}</h2>"
    return """<div class="card" data-layout-key="uninstall-app">$title
$body</div>"""
}
