// Dashboard: mirror of PaneldServer.infoHtml() as the cold shell (snapshot absent, data-hydrate="1"), which
// info.js then hydrates from /api/v1/info. The hydration fragments mirror infoJson(): factRowsHtml,
// contextRowsHtml, capRowsHtml, liveRowsHtml, behaviourRowsHtml, displayRowsHtml,
// controlsHtml and bannersHtml, with every row label family present. The polled endpoints (perf, sensors,
// camera/status, inspect, screenshot) answer with plausible live data so their tables render too.
import { deflateSync } from 'node:zlib';
import { escapeHtml as esc, shellHtml } from '../harness.mjs';

const GH_ICON = 'M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12';
const REPO = 'https://example.invalid/repository';
const ghLink = (s) => `<a class="gh" href="${REPO}" target="_blank" rel="noopener" title="${s.t('shell.github.title')}" aria-label="GitHub"><svg viewBox="0 0 24 24"><path d="${GH_ICON}"/></svg></a>`;
const href = (s, path) => {
  if (s.locale === 'en') return path;
  const [base, hash] = path.split('#');
  return `${base}?lang=${s.locale}${hash ? `#${hash}` : ''}`;
};

// ---- infoHtml() cold shell ------------------------------------------------------------------------
function body(s) {
  const placeholder = `<tr><td style="color:#888">${s.t('dashboard.status.reading')}</td></tr>`;
  const tcard = (id, titleKey, post = '', pre = '') => `<div class="card" data-layout-key="${id}"><h2>${s.t(titleKey)}</h2>${pre}<table id="${id}">${placeholder}</table>${post}</div>`;
  const profNote = `<p class="note">${s.t('dashboard.profile_note.prefix')} <a href="https://example.invalid/device-profiles" target="_blank" rel="noopener" style="color:#9cf">${s.t('dashboard.profile_note.link')}</a>.<br><span class="profile-reference-links"><a href="https://example.invalid/vendor-manual" target="_blank" rel="noopener noreferrer" referrerpolicy="no-referrer"><bdi class="profile-reference-label" dir="auto">Vendor installation manual</bdi> · <bdi class="profile-reference-host" dir="ltr">example.invalid</bdi></a></span></p>`;
  const capNote = `<p class="note"><a href="api/v1/diag" target="_blank" style="color:#9cf">⭳ ${s.t('dashboard.diagnostics_dump.link')}</a> — ${s.t('dashboard.diagnostics_dump.explanation')}</p>`;
  const shotTitle = `<h2>${s.t('dashboard.card.screenshot')} <small>· ${s.t('dashboard.card.live_panel')}</small><a class="card-title-action" href="#" onclick="refreshScreenshot(this.closest('.card'));return false" title="${s.t('dashboard.screenshot.capture_title')}">↻ ${s.t('dashboard.action.refresh')}</a></h2>`;
  const shotInner = `<a class="shot" href="api/v1/screenshot.png" target="_blank" rel="noopener" title="${s.t('dashboard.screenshot.open_full_size')}" data-error-label="${s.t('dashboard.screenshot.unavailable')}" style="aspect-ratio:480/480"><img  alt="${s.t('dashboard.screenshot.alt')}" onload="this.parentElement.classList.add('loaded')" onerror="this.parentElement.classList.add('failed')"></a>`;
  const shotCard = `<div class="card" id="shotcard" data-layout-key="screenshot" data-capture-ok="0" style="display:none">${shotTitle}${shotInner}</div>`;
  const cameraCard = `<div class="card" data-layout-key="camera-stream"><h2>${s.t('dashboard.camera.title')} <small id="camhdr"></small></h2>
<table id="camtbl"><tr><td style="color:#888">${s.t('dashboard.status.reading')}</td></tr></table>
<p class="note">${s.t('dashboard.camera.note')} ${s.t('dashboard.camera.settings_on')} <a href="${href(s, 'configure')}">${s.t('dashboard.camera.configure_link')}</a>.</p></div>`;
  return `<div id="bannerzone"></div>
<div class="cards" id="dashboard-cards" data-card-size-page="dashboard" data-card-size-epoch="1" data-card-size-restore="1">
<div class="card" data-layout-key="controls"><h2>${s.t('dashboard.card.controls')} <small>· ${s.t('dashboard.card.software_nav_bar')}</small></h2>
<div id="ctlzone">${controlsHtml(s, true)}</div></div>
${tcard('infotbl', 'dashboard.card.panel_information')}
${shotCard}
${tcard('nettbl', 'dashboard.card.networking', `<p class="note">${s.t('dashboard.networking.warning_guidance')}</p>`)}
${tcard('proftbl', 'dashboard.card.profile', profNote)}
${tcard('contexttbl', 'dashboard.card.runtime_diagnostics')}
${tcard('captbl', 'dashboard.card.capabilities', capNote)}
<div class="card" data-layout-key="responsiveness"><h2>${s.t('dashboard.card.responsiveness')} <small id="smhdr"></small></h2>
<canvas id="respchart" width="600" height="150" style="height:150px"></canvas>
<div class="leg"><span style="color:#d04a3b">▬</span> ${s.t('dashboard.chart.interaction_latency')}&nbsp;&nbsp;<span style="color:#4a9eff">▬</span> ${s.t('dashboard.chart.state_updates')}&nbsp;&nbsp;<span style="color:#f5a623">▬</span> ${s.t('dashboard.chart.main_thread_blocking')} · ~4 min</div>
<table id="smtbl"><tr><td style="color:#888">${s.t('dashboard.status.measuring')}</td></tr></table></div>
<div class="card" data-layout-key="ha-state-stream"><h2>${s.t('dashboard.card.ha_state_stream')} <small>· ${s.t('dashboard.card.builtin_renderer')}</small></h2>
<table id="streamtbl"><tr><td style="color:#888">${s.t('dashboard.status.waiting_state_traffic')}</td></tr></table>
<table class="dt" id="noisyentities"><tr><td style="color:#888">${s.t('dashboard.status.waiting_entity_contributors')}</td></tr></table>
<p class="note">${s.t('dashboard.ha_stream.note')} <a href="${href(s, 'entities')}">${s.t('dashboard.ha_stream.open_diagnostics')}</a>.</p></div>
<div class="card" data-layout-key="sensors"><h2>${s.t('dashboard.card.sensors')} <small id="sensage"></small></h2>
<table id="senstbl"><tr><td style="color:#888">${s.t('dashboard.status.reading')}</td></tr></table>
<p class="note">${s.t('dashboard.sensors.note')}</p></div>
${cameraCard}
<div class="card" data-layout-key="performance"><h2>${s.t('dashboard.card.performance')} <small id="perfage"></small></h2>
<div style="color:#666;font-size:.78rem;margin-bottom:8px">${s.t('dashboard.performance.samples_note')}</div>
<canvas id="perfchart" width="600" height="96" style="height:96px"></canvas>
<div class="leg"><span style="color:#4a9eff">■</span> CPU&nbsp;&nbsp;<span style="color:#48c774">■</span> RAM&nbsp;&nbsp;<span style="color:#f5a623">■</span> GPU (${s.t('dashboard.chart.percent_used')}) · ~4&nbsp;min</div>
<table id="perf"><tr><td style="color:#888">${s.t('dashboard.status.sampling')}</td></tr></table></div>
<div class="card" data-layout-key="top-processes"><h2>${s.t('dashboard.card.top_processes')} <span class="top-process-modes" role="group" aria-label="${s.t('dashboard.processes.rank_by')}"><button type="button" class="top-process-mode on" data-mode="cpu" aria-pressed="true" onclick="setTopMode('cpu')">CPU</button><button type="button" class="top-process-mode" data-mode="ram" aria-pressed="false" onclick="setTopMode('ram')">RAM</button></span></h2>
<table class="dt" id="topproc"><tr><td style="color:#888">${s.t('dashboard.status.top_processes')}</td></tr></table></div>
<div class="card" data-layout-key="remote-webview"><h2>${s.t('dashboard.card.remote_webview')} <small id="insthdr"></small></h2>
<div style="display:flex;gap:8px;margin-bottom:4px">
 <button id="inspstart" type="button" class="pbtn" onclick="inspStart()">${s.t('dashboard.action.enable')}</button>
 <button type="button" class="pbtn" onclick="inspStop()">${s.t('dashboard.action.stop')}</button></div>
<p class="note" id="insthint"></p></div>
${tcard('livetbl', 'dashboard.card.live_state', '', `<p class="note">${s.t('dashboard.live_state.note')}</p>`)}
${tcard('behavtbl', 'dashboard.card.behaviour')}
${tcard('disptbl', 'dashboard.card.display_tuning')}
</div>
<p class="note" style="text-align:center;margin-top:18px"><a href="${href(s, 'api')}" style="color:#9cf">${s.t('dashboard.footer.api_explorer')}</a>
 · <a href="api/v1/diag" target="_blank" style="color:#9cf">${s.t('dashboard.footer.diagnostics')}</a> · <a href="${REPO}" target="_blank" rel="noopener" style="color:#9cf">GitHub</a></p>`;
}

// ---- controlsHtml(): cold = every button disabled "checking"; hydrated = root/nav ready ------------
function controlsHtml(s, checking) {
  const button = (action, label, reason, style = '') => `<button class="pbtn"${style ? ` style="${style}"` : ''}${reason ? ` title="${esc(reason)}"` : ''} onclick="act('${action}')"${reason ? ' disabled' : ''}>${label}</button>`;
  const checkingText = checking ? s.text('dashboard.controls.checking_capabilities') : null;
  const lbl = (key) => `<span class="lbl"> ${s.t(key)}</span>`;
  return `<div class="ctlrow">
 ${button('back', `←${lbl('dashboard.controls.back')}`, checkingText)}
 ${button('recents', `▢${lbl('dashboard.controls.recents')}`, checkingText)}
 ${button('launcher', `⊞${lbl('dashboard.controls.launcher')}`, checkingText || s.text('dashboard.controls.no_separate_launcher'), 'margin-left:auto')}
 ${button('admin_launcher', `⚙${lbl('dashboard.controls.admin_launcher')}`, checkingText)}
</div>
<div class="ctlrow ctlrow-secondary">
 ${button('dashboard', `⌂${lbl('dashboard.controls.dashboard')}`, checkingText)}
 ${button('reload', `↻ ${s.t('dashboard.controls.reload')}`, checkingText, 'border-color:#7a6330;color:#f5cf82')}
 ${button('reboot', `⟳ ${s.t('dashboard.controls.reboot')}`, checkingText, 'margin-left:auto;border-color:#7a3a2a;color:#f5a08a')}
</div>`;
}

// ---- hydration fragments ----------------------------------------------------------------------------
const row = (label, cell) => `<tr><th>${esc(label)}</th><td>${cell}</td></tr>`;
const cfgIcon = (s, anchor) => `&nbsp;<a class="cfglink" href="${href(s, `configure#${anchor}`)}" title="${s.t('dashboard.link.edit_configure')}" aria-label="${s.t('dashboard.link.edit')}">✎</a>`;
const installIcon = (s, anchor) => `&nbsp;<a class="cfglink" href="${href(s, `install#${anchor}`)}" title="${s.t('dashboard.link.open_install')}" aria-label="${s.t('dashboard.link.open')}">✎</a>`;
const fact = (s, suffix) => s.text(`dashboard.fact.${suffix}`);

function infoRows(s) {
  return [
    row('ha-paneld', `0.9.8 (908)&nbsp;<a class="gh gh-inline" href="${REPO}/releases" target="_blank" rel="noopener" title="${s.t('dashboard.fact.releases_on_github')}" aria-label="${s.t('dashboard.fact.releases_on_github')}"><svg viewBox="0 0 24 24"><path d="${GH_ICON}"/></svg></a>`),
    row(fact(s, 'panel_id'), `hallway_panel${cfgIcon(s, 'cfg-panel_id')}`),
    row(fact(s, 'friendly_name'), `Hallway panel${cfgIcon(s, 'cfg-friendly_name')}`),
    row(fact(s, 'android'), '8.1.0 (API 27)'),
    row(fact(s, 'firmware'), 'rk3326-userdebug 8.1.0 OPM8.190605.005 eng.builder.20230101'),
    row(fact(s, 'device'), 'Example Devices EX-86 (ex86)'),
    row(fact(s, 'device_id'), '<span class="secret">0123456789abcdef</span>'),
    row(fact(s, 'cpu'), '4 × Cortex-A35 @ 1.51 GHz'),
    row(fact(s, 'ram'), '1.9 GB'),
    row(fact(s, 'storage'), '5.1 GB free of 7.3 GB'),
    row(fact(s, 'display'), '480 × 480 · 240 dpi · <span class="diag" data-in="4.0″" data-cm="10.2 cm" title="W 7.2 × H 7.2 cm" onclick="diagToggle(this)">4.0″</span>'),
    row(fact(s, 'system_webview'), '<span style="color:#f5c451">Chromium 68.0.3440.91 ⚠</span>'),
    row(fact(s, 'ha_companion'), '2024.9.1-full (not installed as renderer)'),
  ].join('\n');
}

function netRows(s) {
  return [
    row(fact(s, 'local_ip'), '<span class="secret">192.0.2.10</span>'),
    row(fact(s, 'local_ipv6'), '<span class="secret">2001:db8::10</span>'),
    row(fact(s, 'http_port'), '8888'),
    row(fact(s, 'mqtt'), `mqtt://192.0.2.20:1883 (connected)${cfgIcon(s, 'cfg-mqtt_broker')}`),
    row(fact(s, 'mdns'), 'hallway-panel.local · _ha-paneld._tcp'),
    row(fact(s, 'network_adb'), `${s.t('dashboard.value.off')}${cfgIcon(s, 'cfg-network_adb')}`),
  ].join('\n');
}

function profileRows(s) {
  return [
    row(fact(s, 'platform'), 'example-86 (Example 86 wall panel)'),
    row(fact(s, 'soc'), 'Rockchip PX30'),
    row(fact(s, 'model'), 'EX-86'),
    row(fact(s, 'led'), 'Rockchip /dev/ledjni'),
    row(fact(s, 'light_sensor'), 'yes (ambient, polled)'),
    row(fact(s, 'proximity'), 'yes (infrared, learned)'),
    row(fact(s, 'navbar'), `hidden${cfgIcon(s, 'cfg-navbar_mode')}`),
    row(fact(s, 'zigbee'), `router firmware 7.4.4${cfgIcon(s, 'cfg-zigbee_router')}`),
    row(fact(s, 'relays'), '2 (/sys/class/gpio)'),
    row(fact(s, 'cpu_profile'), `schedutil${cfgIcon(s, 'cfg-cpu_governor')}`),
  ].join('\n');
}

function contextRows(s) {
  const f = (key, values) => esc(s.format(key, values));
  const age = s.format('dashboard.runtime.mqtt.age_seconds_ago', { seconds: 4 });
  return [
    row(fact(s, 'wifi_stability'), '3 drops in the last hour · RSSI −71 dBm'),
    `<tr><th>${esc(fact(s, 'ha_network_path'))}</th><td id="hanetcell">${f('dashboard.runtime.ha_network_healthy_slow', { evidence: 'p95 412 ms over 120 probes' })}</td></tr>`,
    row(fact(s, 'ha_renderer'), f('dashboard.runtime.renderer.admitted_age', { status: s.format('dashboard.runtime.renderer.builtin', { summary: s.text('dashboard.runtime.renderer.rendered_cached') }), age: '3m' })),
    row(fact(s, 'mqtt_timing'), f('dashboard.runtime.mqtt.status', { state: s.text('dashboard.runtime.mqtt.state.connected'), transport: 'tcp', lastOk: age, lastAuth: age, auth: 'ok', family: s.format('dashboard.runtime.mqtt.family_automatic', { family: 'IPv4' }) })),
    row(fact(s, 'state_convergence'), f('dashboard.runtime.convergence', { channels: 14, dirty: 0, inFlight: 1, unknown: 0, successes: 212, failures: 0, pending: 1 })),
    row(fact(s, 'local_state_sync'), 'in sync · 38 entities'),
    row(fact(s, 'app_database'), esc([s.format('dashboard.runtime.database.schema', { version: 42 }), s.format('dashboard.runtime.database.used', { bytes: '1.2 MB' }), s.format('dashboard.runtime.database.on_disk', { bytes: '1.6 MB' })].join(' · '))),
    row(fact(s, 'security_mode'), s.t('dashboard.runtime.security_hardened')),
    row(fact(s, 'audio_playback'), f('dashboard.runtime.audio_failed_detail', { detail: 'HTTP 404 from media source' })),
    row(fact(s, 'camera'), f('dashboard.runtime.camera.live_many_streaming', { count: 2, streaming: 1, stream: s.format('dashboard.runtime.camera.stream_url', { url: 'http://192.0.2.10:8889/stream.mjpeg' }) })),
    row(fact(s, 'log_shipping'), `on · syslog://192.0.2.30:514${cfgIcon(s, 'cfg-log_ship_enabled')}`),
    `<tr><th>${esc(fact(s, 'ha_lifecycle'))}</th><td id="halifecell">${s.t('dashboard.runtime.ha_events_refused')}</td></tr>`,
    row(fact(s, 'webview_reporting'), 'package version and engine version disagree'),
  ].join('\n');
}

function capRows(s) {
  const cap = (nameKey, colour, note) => `<tr><th>${s.t(nameKey)}</th><td><span style="color:${colour}">●</span> ${esc(note)}</td></tr>`;
  const note = (key, values) => s.format(`dashboard.capability.note.${key}`, values);
  return [
    cap('dashboard.capability.root_su', '#d9a528', note('helper_routed')),
    cap('dashboard.capability.helper_daemon', '#48c774', note('daemon_state', { state: note('daemon_running'), detail: note('daemon_sandbox_path') })),
    cap('dashboard.capability.shizuku', '#888', `${note('preferred_route_prefix')} ${note('shizuku_disabled')}`),
    cap('dashboard.capability.verified_operations', '#48c774', note('root_or_helper')),
    cap('dashboard.capability.screen_brightness', '#48c774', note('brightness_helper')),
    cap('dashboard.capability.screen_power', '#d9a528', note('dim_only', { reason: note('dim_backlight_powered') })),
    cap('dashboard.capability.rgb_led', '#48c774', note('rockchip_led_helper')),
    cap('dashboard.capability.hardware_buttons', '#d9a528', note('buttons_accessibility_not_verified', { count: 2, detail: 'no key events seen since boot' })),
    cap('dashboard.capability.system_actions', '#d04a3b', note('needs_su_or_helper')),
  ].join('\n');
}

function liveRows(s) {
  return [
    [s.text('dashboard.live.screen_brightness'), '47% (120)'],
    [s.text('dashboard.live.volume'), '60%'],
    [s.text('dashboard.live.navigate'), '/lovelace/hallway'],
    [s.text('dashboard.live.led'), `${s.text('dashboard.value.on')} · rgb(255,160,0) @ 40`],
  ].map(([label, value]) => row(label, esc(value))).join('\n');
}

function settingRow(s, key, shown) {
  const labelKey = `settings.${key}.label`;
  return row(s.has(labelKey) ? s.text(labelKey) : key, `${esc(shown)}${cfgIcon(s, `cfg-${key}`)}`);
}

function behaviourRows(s) {
  const on = s.text('dashboard.value.on'); const off = s.text('dashboard.value.off');
  return [
    ['wake_on_wave', on], ['prevent_idle_dim', on], ['watchdog_enabled', on], ['kiosk_lock', off], ['touch_sound', off],
    ['silence_boot_chime', on], ['keep_awake', off], ['navbar_mode', 'hidden'], ['log_ship_enabled', on],
    ['home_dashboard', s.format('dashboard.value.auto_detail', { value: s.text('dashboard.value.ha_default_view') })],
    ['ha_area', s.format('dashboard.value.local_override', { value: 'hallway' })],
    ['dashboard_package', s.text('dashboard.value.builtin_renderer')],
    ['launcher_package', s.format('dashboard.value.auto_detail', { value: 'ha-paneld admin launcher' })],
  ].map(([key, shown]) => settingRow(s, key, shown)).join('\n');
}

function displayRows(s) {
  return [
    settingRow(s, 'auto_brightness', s.text('dashboard.value.on')),
    settingRow(s, 'auto_brightness_minimum_percent', '10% (26)'),
    settingRow(s, 'auto_brightness_response_percent', '40%'),
    settingRow(s, 'auto_brightness_ha_entity', 'sensor.hallway_illuminance'),
    row(s.text('dashboard.display.logical_density'), `240 dpi (${s.t('dashboard.display.factory_base')} 240)${installIcon(s, 'cfg-display')}`),
    row(s.text('dashboard.display.text_size'), `1.0${installIcon(s, 'cfg-display')}`),
    row(s.text('settings.wake_on_wave.label'), `learned · ready${cfgIcon(s, 'cfg-wake_on_wave')}`),
    row(s.text('dashboard.display.tamed_packages'), `${s.t('dashboard.value.none')}${installIcon(s, 'cfg-tame')}`),
  ].join('\n');
}

function bannersHtml(s) {
  const t = s.t;
  return [
    `<div class="setup crit">⚠ <b>${t('dashboard.banner.webview_old.title')}</b> (Chromium 68) — ${t('dashboard.banner.webview_old.explanation')} <a href="https://example.invalid/webview" target="_blank" rel="noopener">${t('dashboard.banner.webview_old.update_action')}</a> ${t('dashboard.banner.webview_old.target', { version: 87 })}. <small>${t('dashboard.banner.webview_old.engine_note')}</small> <a href="${href(s, 'install')}">${t('dashboard.banner.manage_install')}</a></div>`,
    `<div class="setup info" data-update="Home Assistant Companion" data-version="2024.10.1">⬆ <b>Home Assistant Companion</b> ${t('dashboard.banner.update.available', { latest: '2024.10.1', current: '2024.9.1' })} — <a href="${href(s, 'install')}">${t('dashboard.banner.manage_install')}</a> <button class="pbtn" onclick="ignoreUpdate(this)">${t('dashboard.banner.update.ignore')}</button></div>`,
    `<div class="setup crit">⚠ <b>${t('dashboard.banner.schema_rollback.title')}</b> (schema 43 → 42) — ${t('dashboard.banner.schema_rollback.explanation')} <a href="${href(s, 'configure')}">${t('dashboard.banner.schema_rollback.configure_action')}</a> ${t('dashboard.banner.schema_rollback.or_restore')} <a href="${href(s, 'install')}">${t('shell.nav.install')}</a>.</div>`,
    `<div class="setup">👋 <b>${t('dashboard.banner.proximity_learning.title')}</b>. ${t('dashboard.banner.proximity_learning.touch_available')} <a href="${href(s, 'configure#cfg-proximity-learning')}">${t('dashboard.banner.proximity_learning.action')}</a>.</div>`,
    `<div class="setup">🏠 <b>${t('dashboard.banner.ha_sign_in.title')}</b> ${t('dashboard.banner.ha_sign_in.explanation')} <a href="${href(s, 'configure#cfg-ha-oauth')}">${t('dashboard.banner.ha_sign_in.action')}</a>.</div>`,
    `<div class="setup">⚠ ${t('dashboard.banner.setup_needs.prefix')} <a href="${href(s, 'configure')}">${esc(`${s.text('dashboard.banner.setup_needs.valid_credentials')} ${s.text('dashboard.banner.setup_needs.joiner')} ${s.text('dashboard.banner.setup_needs.reachable_broker')}`)}</a> ${t('dashboard.banner.setup_needs.suffix')}</div>`,
  ].join('');
}

function infoJson(s) {
  return {
    banners: bannersHtml(s), shot: true, shotCached: '', versionCode: 908, package: 'io.github.maxlyth.hapaneld',
    controls: controlsHtml(s, false),
    cards: {
      livetbl: liveRows(s), behavtbl: behaviourRows(s), disptbl: displayRows(s),
      infotbl: infoRows(s), nettbl: netRows(s), proftbl: profileRows(s), contexttbl: contextRows(s), captbl: capRows(s),
    },
  };
}

// ---- polled endpoints -------------------------------------------------------------------------------
const wave = (n, base, span) => Array.from({ length: n }, (_, i) => Math.round(base + span * Math.sin(i / 5)));
function perf() {
  return {
    cpu: 23, cores: [31, 18, 22, 20], freqMhz: [1512, 1200], freqMaxMhz: 1512, gpu: 12, gpuMhz: 400,
    memUsedMb: 1210, memTotalMb: 1987, load: [1.42, 1.18, 0.97], tempC: 51.3,
    hist: { cpu: wave(120, 25, 10), ram: wave(120, 60, 3), gpu: wave(120, 12, 8) },
    top: [
      { name: 'org.chromium.webview_shell:sandboxed_process0', cpu: 14, ramMb: 212 },
      { name: 'system_server', cpu: 5, ramMb: 148 },
      { name: 'surfaceflinger', cpu: 3, ramMb: 36 },
      { name: 'io.github.maxlyth.hapaneld', cpu: 2, ramMb: 96, self: true },
    ],
    topRam: [{ name: 'org.chromium.webview_shell:sandboxed_process0', ramMb: 212, cpu: 14 }, { name: 'system_server', ramMb: 148, cpu: 5 }],
    builtin: { ttiColdMs: 4200, ttiWarmMedianMs: 1800, reloads24h: 1 },
    dashboard: {
      mode: 'builtin_direct', likelyCause: 'state_stream', confidence: 'medium', sampleCount: 40,
      interaction: { count: 12, p50Ms: 84, p95Ms: 212, worstMs: 318, inputDelayMs: 22, processingMs: 188, presentationMs: 108 },
      blocking: { blockedMsPerSec: 96, blockedP95MsPerSec: 180, longestFrameMs: 142 },
      history: { interactionMs: wave(48, 120, 60), updatesPerSec: wave(48, 20, 8), blockedMsPerSec: wave(48, 100, 50) },
      stateStream: { updatesPerSec: 18.4, updatesP95PerSec: 31, payloadBytesPerSec: 14200, payloadP95BytesPerSec: 26000, mainThreadMsPerSec: 22, mainThreadP95MsPerSec: 40, longestStateTaskMs: 61, hydrationUpdates: 512, droppedFrames: 2 },
      filter: { active: true, entityCount: 38 },
      topEntities: [
        { entityId: 'sensor.hallway_power_meter_instantaneous_demand', updates1h: 3600, payloadBytes1h: 1843200 },
        { entityId: 'sensor.outdoor_temperature', updates1h: 120, payloadBytes1h: 61440 },
      ],
    },
  };
}

const SENSORS = {
  light: { present: true, lux: 42, age_s: 3 },
  proximity: { present: true, mode: 'ranged', learning: 'learned', near: false, raw: 12, level: 8, presenceReady: true, sessionActive: false, age_s: 1 },
  temperature: { present: true, c: 22.5, age_s: 60 },
  humidity: { present: true, pct: 41, age_s: 4000 },
  volume_pct: 60, brightness: 120,
};

const CAMERA = {
  state: 'live', clients: 2, stream_clients: 1, encoder: 'OMX.rk.video_encoder.avc', encode_width: 1280, encode_height: 720,
  encode_fps: 15, requested_fps: 30, encode_kbps: 2000, delivered_fps: 14.2, delivered_kbps: 2400, last_frame_age_ms: 80, action: 'none', fault: 'none',
};

// A tiny solid PNG for the screenshot card (a real capture's aspect ratio is carried by the anchor).
function png(size = 48) {
  const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (buf) => { let c = 0xffffffff; for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (type, data) => {
    const out = Buffer.alloc(12 + data.length); out.writeUInt32BE(data.length, 0); out.write(type, 4, 'ascii'); data.copy(out, 8);
    out.writeUInt32BE(crc(out.subarray(4, 8 + data.length)), 8 + data.length); return out;
  };
  const header = Buffer.alloc(13); header.writeUInt32BE(size, 0); header.writeUInt32BE(size, 4); header[8] = 8; header[9] = 2;
  const raw = Buffer.alloc((size * 3 + 1) * size);
  for (let y = 0; y < size; y += 1) for (let x = 0; x < size; x += 1) raw.set([30 + x * 3, 60, 90 + y * 2], y * (size * 3 + 1) + 1 + x * 3);
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
const PNG = png();

export default {
  name: 'dashboard',
  path: '/',
  html(context) {
    const { s } = context;
    const revealBtn = `<button id="revbtn" class="pbtn" onclick="toggleReveal()" title="${s.t('dashboard.reveal.title')}">${s.t('dashboard.action.reveal')}</button>`;
    const haLink = `<a class="pbtn" href="https://example.invalid/home-assistant" target="_blank" rel="noopener" title="${s.t('dashboard.open_in_ha.title')}">${s.t('shell.open_in_ha')}</a>`;
    return shellHtml({
      ...context, active: 'dashboard', sectionTitle: null, prefixes: ['shell.', 'dashboard.', 'runtime.'],
      bodyAttrs: 'data-ver="0.9.8" data-build="layout-gate" data-cfg="layout-gate" data-hydrate="1" data-hardened="1"',
      rightControls: `${haLink}${revealBtn} ${ghLink(s)}`,
      body: body(s),
      extraScripts: '<script src="assets/card-size-memory.js"></script>\n<script src="assets/card-column-alignment.js"></script>\n<script src="info.js"></script>\n',
    });
  },
  api(url, method, context) {
    const path = url.pathname;
    if (path === '/api/v1/info') return infoJson(context.s);
    if (path === '/api/v1/perf') return perf();
    if (path === '/api/v1/sensors') return SENSORS;
    if (path === '/api/v1/camera/status') return CAMERA;
    if (path === '/api/v1/status') return { camera: CAMERA };
    if (path === '/api/v1/inspect') return { running: false, port: 9222, status: 'ok', start_allowed: true };
    if (path === '/api/v1/screenshot.png') return { __raw: true, type: 'image/png', body: PNG };
    return {};
  },
  async ready(frame) {
    await frame.waitForFunction(() => {
      const filled = (id) => { const t = document.getElementById(id); return t && t.querySelector('th') && !/color:#888/.test(t.rows[0]?.cells[0]?.getAttribute('style') || ''); };
      return ['infotbl', 'nettbl', 'proftbl', 'contexttbl', 'captbl', 'livetbl', 'behavtbl', 'disptbl', 'perf', 'smtbl', 'streamtbl', 'senstbl', 'camtbl'].every(filled)
        && document.querySelectorAll('#topproc tr').length > 2 && document.querySelectorAll('#noisyentities tr').length > 2
        && document.querySelector('#bannerzone .setup') && document.querySelector('#ctlzone button:not([disabled])')
        && document.getElementById('insthint').textContent.length > 0
        && document.querySelector('#shotcard .shot.loaded');
    });
  },
};
