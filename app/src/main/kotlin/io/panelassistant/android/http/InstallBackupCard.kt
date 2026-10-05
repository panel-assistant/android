package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings

/** Backup & restore card: an ENCRYPTED device-state bundle (ha-paneld config + optionally the HA
 *  Companion login) with a passphrase; restore shows a decrypt preview before the destructive apply.
 *  Also links the plain config-only bundle (for cloning settings between panels). */
internal fun backupCardHtml(
    companionHelper: Boolean,
    companionInstalled: Boolean,
    strings: AppStrings,
): String {
    val companion = backupCompanionCopy(installed = companionInstalled, helper = companionHelper)
    val compRow = when {
        companion.showLoginChoice -> """<label style="display:flex;flex-direction:row;gap:8px;align-items:center;font-size:.85rem"><input type="checkbox" id="bk-comp" checked> ${esc(strings.get("install.backup.companion.include_login"))}</label>"""
        companion.explainHelperRequirement -> """<p class="note">${esc(strings.get("install.backup.companion.helper_required"))}</p>"""
        else -> ""
    }
    val descriptionKey = if (companion.showLoginChoice) "install.backup.description.with_companion" else "install.backup.description.config_only"
    val restoreKey = if (companion.showLoginChoice) "install.backup.restore.description.with_companion" else "install.backup.restore.description.config_only"
    return """<div class="card" data-layout-key="backup-restore">${hardenedApprovalCardTitle(esc(strings.get("install.backup.title")), conditional = true, strings = strings)}
<p class="note">${esc(strings.get(descriptionKey))}</p>
<div style="display:flex;flex-direction:column;gap:8px;max-width:440px">
$compRow
<input type="password" id="bk-pw" placeholder="${esc(strings.get("install.backup.passphrase.placeholder"))}">
<label style="display:flex;flex-direction:row;gap:8px;align-items:flex-start;font-size:.85rem;color:#c88"><input type="checkbox" id="bk-plain"> ${esc(strings.get("install.backup.plaintext_zip"))}</label>
<button class="pbtn"${hardenedApprovalA11yAttrs(strings = strings)} onclick="doBackup(this)">⭳ ${esc(strings.get("install.backup.download"))}</button>
</div>
<hr style="border:0;border-top:1px solid #2a2a2a;margin:14px 0">
<p class="note"><b>${esc(strings.get("install.backup.restore.title"))}</b> ${esc(strings.get(restoreKey))}</p>
<div style="display:flex;flex-direction:column;gap:8px;max-width:440px">
<input type="password" id="rs-pw" placeholder="${esc(strings.get("install.backup.restore.passphrase_placeholder"))}">
<label class="pbtn" style="cursor:pointer">⭱ ${esc(strings.get("install.backup.restore.choose"))}<input type="file" id="rs-file" accept=".hpb,.zip,application/octet-stream,application/zip" style="display:none" onchange="restorePick(this)"></label>
<div id="rs-preview"></div>
</div>
<p class="note" id="bk-msg"></p>
<hr style="border:0;border-top:1px solid #2a2a2a;margin:14px 0">
<p class="note"><b>${esc(strings.get("install.backup.config_bundle.title"))}</b> ${esc(strings.get("install.backup.config_bundle.description"))}</p>
<div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
 <a class="pbtn" href="api/v1/config/export">⭳ ${esc(strings.get("install.backup.config_bundle.export"))}</a>
 <button class="pbtn" type="button"${hardenedApprovalA11yAttrs(strings = strings)} onclick="configExport(true,this)">⭳ ${esc(strings.get("install.backup.config_bundle.export_secrets"))}</button>
 <label class="pbtn"${hardenedApprovalA11yAttrs(strings = strings)} style="cursor:pointer">⭱ ${esc(strings.get("install.backup.config_bundle.import"))}<input type="file" id="cfg-import-file" accept="application/json" style="display:none" onchange="configImport(this)"></label>
</div>
<p id="cfg-export-result" class="note" role="status" aria-live="polite"></p>
<pre id="cfg-import-result" class="muted" style="white-space:pre-wrap;margin-top:10px"></pre></div>"""
}
