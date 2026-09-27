// Logs: mirror of PaneldServer.logsBody() inside page(). The stream fixture answers the SSE endpoint with
// threadtime lines of every level and several tags, then closes; the page is paused before measuring so
// the header shows a translated state and the action shows its Resume label.
import { tabbedPage } from '../harness.mjs';

const LEVELS = ['V', 'D', 'I', 'W', 'E', 'I', 'D', 'W'];
const TAGS = ['PaneldServer', 'MqttBridge', 'DashboardWebView', 'AutoBrightness', 'Provisioner', 'chromium', 'HaSession', 'Watchdog'];

function streamBody() {
  const lines = [];
  for (let index = 0; index < 60; index += 1) {
    const level = LEVELS[index % LEVELS.length]; const tag = TAGS[index % TAGS.length];
    const second = String(index % 60).padStart(2, '0');
    const message = index % 5 === 0
      ? `request GET http://192.0.2.10:8888/api/v1/status completed in ${index + 3} ms with a deliberately long line that must scroll inside the log view rather than widen the page`
      : `event ${index} for light.living_room state=on brightness=${index * 3}`;
    lines.push(`data: 09-27 10:15:${second}.${String(index * 7).padStart(3, '0')}  4321  ${4321 + (index % 4)} ${level} ${tag}: ${message}\n\n`);
  }
  return `retry: 3600000\n\n${lines.join('')}`;
}

export default {
  name: 'logs',
  path: '/logs',
  html(context) {
    const { s } = context;
    const body = `
<div class="card"><h2>${s.t('logs.title')} <small id="lg-state" class="muted">· ${s.t('logs.state.connecting')}</small></h2>
<div class="log-toolbar">
 <span class="log-source"><button id="lg-src-app" class="pbtn on" onclick="lgSource('app')">${s.t('logs.source.app')}</button><button id="lg-src-system" class="pbtn" onclick="lgSource('system')" title="${s.t('logs.source.system_root_check')}">${s.t('logs.source.system')}</button><button id="lg-src-webview" class="pbtn" onclick="lgSource('webview')" title="${s.t('logs.source.webview_hint')}">${s.t('logs.source.webview')}</button></span>
 <select id="lg-level" onchange="lgRender()" title="${s.t('logs.level.minimum')}">
  <option value="V" selected>${s.t('logs.level.verbose')}</option><option value="D">${s.t('logs.level.debug')}</option><option value="I">${s.t('logs.level.info')}</option>
  <option value="W">${s.t('logs.level.warning')}</option><option value="E">${s.t('logs.level.error')}</option>
 </select>
 <input id="lg-filter" class="log-filter" placeholder="${s.t('logs.filter.placeholder')}" oninput="lgRender()">
 <span class="log-actions">
  <label class="log-follow muted"><input type="checkbox" id="lg-follow" checked> ${s.t('logs.action.follow')}</label>
  <button id="lg-pause" class="pbtn" onclick="lgPause()">⏸ ${s.t('logs.action.pause')}</button>
  <button class="pbtn" onclick="lgClear()">${s.t('logs.action.clear')}</button>
 </span>
</div>
<div id="lg-out" class="logview" onscroll="lgScrolled()"></div>
<p class="note">${s.t('logs.note.sources')}
${s.t('logs.note.privacy')}
${s.t('logs.note.raw_stream')} <code>curl -N http://&lt;panel&gt;:8888/api/v1/logs/stream</code></p></div>
<script src="assets/logs.js"></script>`;
    return tabbedPage({ ...context, active: 'logs', sectionTitle: s.text('shell.nav.logs'), body, prefixes: ['shell.', 'logs.', 'runtime.'] });
  },
  api(url) {
    if (url.pathname === '/api/v1/logs/stream') return { __raw: true, type: 'text/event-stream; charset=utf-8', body: streamBody() };
    return {};
  },
  async ready(frame) {
    // The fixture stream ends after its lines and EventSource reports the drop once; let that land
    // before pausing so the paused state is the one measured.
    await frame.waitForFunction(() => document.querySelectorAll('#lg-out .lg-line').length >= 60);
    await frame.waitForTimeout(300);
  },
  async exercise(frame) {
    await frame.evaluate(() => window.lgPause());
    await frame.waitForFunction(() => /▶/.test(document.getElementById('lg-pause').textContent));
  },
};
