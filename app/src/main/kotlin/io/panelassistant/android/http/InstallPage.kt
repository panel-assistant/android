package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings

/** Install layout; observations and guarded actions remain with their production owners. */
internal fun installPageBody(
    strings: AppStrings,
    warnings: String,
    allGood: String,
    components: String,
    apk: String,
    uninstall: String,
    vendor: String,
    display: String,
    backup: String,
): String = """$warnings
<div class="cards" id="install-cards" data-card-size-page="install" data-card-size-epoch="1" data-card-size-restore="1">
$components
$apk
$uninstall
<div class="card" id="radiocard" data-layout-key="radio-firmware" style="display:none"><h2>${esc(strings.get("install.radio.title"))}</h2>
<table><tr><th>${esc(strings.get("install.radio.efr32"))}</th><td id="radio-status">…</td></tr>
<tr><th>${esc(strings.get("install.radio.gateway_health"))}</th><td id="radio-health">…</td></tr></table>
<p class="note">${esc(strings.get("install.radio.note_prefix"))} <a href="${localizedHref("configure#cfg-zigbee_join", strings)}">${esc(strings.get("install.radio.configure_join"))}</a>. <span class="muted">${esc(strings.get("install.radio.thread_planned"))}</span></p></div>
<div class="card" data-layout-key="health-audit"><h2>${esc(strings.get("install.audit.title"))}</h2>
<p class="note">${esc(strings.get("install.audit.description"))}</p>
<button class="pbtn" onclick="healthAudit(this)">${esc(strings.get("install.audit.run"))}</button>
<div id="audit-out" style="margin-top:10px"></div>
<p class="note"><a href="api/v1/diag" target="_blank" style="color:#9cf">⭳ ${esc(strings.get("install.audit.diagnostics"))}</a> — ${esc(strings.get("install.audit.diagnostics_help"))}</p></div>
$vendor
$display
$backup
$allGood</div>
<script src="assets/card-size-memory.js"></script>
<script src="assets/card-column-alignment.js"></script>
<script src="assets/install.js"></script>"""
