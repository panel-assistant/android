package io.panelassistant.android.http

import io.panelassistant.android.Config
import io.panelassistant.android.i18n.Strings as AppStrings

/** Logs tab — live log tail over SSE. App source always; system source needs root (gated live). */
internal fun logsBody(strings: AppStrings, httpPort: Int): String {
    // Deliberately NOT inside a `.cards` masonry container — the log card wants the full page width.
    return """
<div class="card"><h2>${esc(strings.get("logs.title"))} <small id="lg-state" class="muted">· ${esc(strings.get("logs.state.connecting"))}</small></h2>
<div class="log-toolbar">
 <span class="log-source"><button id="lg-src-app" class="pbtn on" onclick="lgSource('app')">${esc(strings.get("logs.source.app"))}</button><button id="lg-src-system" class="pbtn" onclick="lgSource('system')" title="${esc(strings.get("logs.source.system_root_check"))}">${esc(strings.get("logs.source.system"))}</button><button id="lg-src-webview" class="pbtn" onclick="lgSource('webview')" title="${esc(strings.get("logs.source.webview_hint"))}">${esc(strings.get("logs.source.webview"))}</button></span>
 <select id="lg-level" onchange="lgRender()" title="${esc(strings.get("logs.level.minimum"))}">
  <option value="V" selected>${esc(strings.get("logs.level.verbose"))}</option><option value="D">${esc(strings.get("logs.level.debug"))}</option><option value="I">${esc(strings.get("logs.level.info"))}</option>
  <option value="W">${esc(strings.get("logs.level.warning"))}</option><option value="E">${esc(strings.get("logs.level.error"))}</option>
 </select>
 <input id="lg-filter" class="log-filter" placeholder="${esc(strings.get("logs.filter.placeholder"))}" oninput="lgRender()">
 <span class="log-actions">
  <label class="log-follow muted"><input type="checkbox" id="lg-follow" checked> ${esc(strings.get("logs.action.follow"))}</label>
  <button id="lg-pause" class="pbtn" onclick="lgPause()">⏸ ${esc(strings.get("logs.action.pause"))}</button>
  <button class="pbtn" onclick="lgClear()">${esc(strings.get("logs.action.clear"))}</button>
 </span>
</div>
<div id="lg-out" class="logview" onscroll="lgScrolled()"></div>
<p class="note">${esc(strings.get("logs.note.sources"))}
${esc(strings.get("logs.note.privacy"))}
${esc(strings.get("logs.note.raw_stream"))} <code>curl -N http://&lt;panel&gt;:${esc(httpPort.toString())}/api/v1/logs/stream</code></p></div>
<script src="assets/logs.js"></script>"""
}
