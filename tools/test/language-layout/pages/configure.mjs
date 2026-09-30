// Configure: mirror of PaneldServer.configureBody() inside page(), with the settings schema derived from
// the real SettingsRegistry so every shipped setting label and help string is laid out in every locale.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { ROOT, tabbedPage } from '../harness.mjs';

const REGISTRY = resolve(ROOT, 'app/src/main/kotlin/io/github/maxlyth/hapaneld/config/SettingsRegistry.kt');

/** Each `SettingSpec(...)` call in the registry, parsed for the fields the Configure form lays out. */
export function registrySpecs() {
  const source = readFileSync(REGISTRY, 'utf8');
  const specs = [];
  let at = source.indexOf('SettingSpec(');
  while (at >= 0) {
    let depth = 0; let end = at + 'SettingSpec'.length; let quote = null;
    for (; end < source.length; end += 1) {
      const character = source[end];
      if (quote) { if (character === '\\') end += 1; else if (character === quote) quote = null; continue; }
      if (character === '"') quote = '"';
      else if (character === '(') depth += 1;
      else if (character === ')') { depth -= 1; if (depth === 0) break; }
    }
    const chunk = source.slice(at, end + 1);
    const field = (name) => chunk.match(new RegExp(`\\b${name}\\s*=\\s*("(?:[^"\\\\]|\\\\.)*"|[^,\\n)]+)`))?.[1]?.trim();
    const literal = (name) => { const raw = field(name); return raw && raw.startsWith('"') ? JSON.parse(raw) : undefined; };
    const number = (name) => { const raw = field(name); const value = raw === undefined ? NaN : Number(raw.replaceAll('_', '')); return Number.isFinite(value) ? value : null; };
    const key = literal('key');
    if (key) {
      const optionList = chunk.match(/\boptions\s*=\s*listOf\(([^)]*)\)/)?.[1] || '';
      specs.push({
        key,
        type: field('type')?.replace('SettingType.', ''),
        group: literal('group'),
        options: [...optionList.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((match) => match[1]),
        min: number('min'), max: number('max'), step: number('step'),
        picker: literal('picker') ?? null,
        secret: field('secret') === 'true',
        hidden: field('hidden') === 'true',
        help: /\bhelp\s*=/.test(chunk),
      });
    }
    at = source.indexOf('SettingSpec(', end);
  }
  return specs;
}

const SPECS = registrySpecs();
if (SPECS.length < 60) throw new Error(`SettingsRegistry parse found only ${SPECS.length} settings`);

function schema(s) {
  return SPECS.filter((spec) => !spec.hidden).map((spec) => ({
    key: spec.key, type: spec.type, group: spec.group,
    labelKey: `settings.${spec.key}.label`, helpKey: spec.help ? `settings.${spec.key}.help` : null,
    label: s.has(`settings.${spec.key}.label`) ? s.text(`settings.${spec.key}.label`) : spec.key,
    labelLanguage: s.locale,
    help: spec.help && s.has(`settings.${spec.key}.help`) ? s.text(`settings.${spec.key}.help`) : '',
    helpLanguage: spec.help ? s.locale : null,
    summary: s.has(`settings.${spec.key}.summary`) ? s.text(`settings.${spec.key}.summary`) : '',
    summaryLanguage: s.locale,
    default: '', tier: 'ADVANCED', scope: 'DEVICE', secret: spec.secret, readOnly: false, available: true,
    displaySizingAvailable: spec.key === 'dashboard_zoom',
    options: spec.options.length ? spec.options : (spec.type === 'ENUM' ? ['auto'] : []),
    picker: spec.picker, min: spec.min, max: spec.max, step: spec.step,
    maxLength: ['STRING', 'PASSWORD'].includes(spec.type) ? 256 : null,
    ha: true, exposed: spec.type === 'BOOL', placeholder: null,
  }));
}

function settings() {
  const values = {};
  for (const spec of SPECS) {
    values[spec.key] = spec.type === 'BOOL' ? 'true'
      : spec.type === 'ENUM' ? (spec.options[0] || 'auto')
        : ['INT', 'LONG', 'FLOAT'].includes(spec.type) ? String(spec.min ?? 1)
          : spec.secret ? '' : '';
  }
  Object.assign(values, {
    panel_id: 'hallway_panel', friendly_name: 'Hallway panel', ha_area: 'hallway',
    mqtt_broker: 'mqtt://192.0.2.20:1883', mqtt_user: 'panel', auto_sleep: 'true', auto_sleep_source: 'home_assistant',
    auto_brightness: 'true', auto_brightness_ha_entity: 'sensor.hallway_illuminance',
    auto_brightness_response_percent: '40', auto_brightness_minimum_percent: '10',
  });
  return values;
}

function autoSleepHistory() {
  const end = Date.now(); const start = end - 24 * 3600 * 1000; const mid = start + 12 * 3600 * 1000;
  return {
    available: true, hours: 24, area_name: 'Hallway', area_key: 'a'.repeat(64),
    window_start_epoch_ms: start, window_end_epoch_ms: end,
    segments: [{ start_epoch_ms: start, end_epoch_ms: mid, output: 'allow_sleep' }, { start_epoch_ms: mid, end_epoch_ms: end, output: 'hold_awake' }],
    source_lanes: ['Hallway ceiling motion sensor', 'Front door contact', 'Stair presence'].map((label, index) => ({
      source_key: String(index).padStart(64, 'b'), label, included: index !== 2,
      segments: [{ start_epoch_ms: start, end_epoch_ms: mid, state: 'off' }, { start_epoch_ms: mid, end_epoch_ms: end, state: 'on' }],
    })),
  };
}

function api(url) {
  const path = url.pathname;
  const s = this;
  if (path === '/api/v1/config/schema') return schema(s);
  if (path === '/api/v1/config') return { settings: settings(), ha_expose: {}, ha_auth: { configured: true } };
  if (path === '/api/v1/ha/oauth/status') return { phase: 'connected', display_name: 'Owner', language: 'en' };
  if (path === '/api/v1/apps') return { apps: [{ pkg: 'io.homeassistant.companion.android', label: 'Home Assistant' }, { pkg: 'com.example.browser', label: 'Example browser' }] };
  if (path === '/api/v1/radio') return { present: true, status: 'Zigbee router firmware 7.4.4', state: 'joined' };
  if (path === '/api/v1/proximity') return { present: false };
  if (path === '/api/v1/config/discovery') return {};
  if (path === '/api/v1/config/home-dashboards') return { queried: true, items: [{ path: 'lovelace', title: 'Overview', group: 'dashboard' }, { path: 'hallway-panel', title: 'Hallway panel dashboard', group: 'dashboard' }], default: { explicit: false, path: '' } };
  if (path === '/api/v1/auto-sleep/prerequisite') return { eligible: true, phase: 'assigned', area_name: 'Hallway' };
  if (path === '/api/v1/auto-sleep') return { enabled: true, available: true, phase: 'live', area_name: 'Hallway', source_count: 3, learned_delay_minutes: 18 };
  if (path === '/api/v1/auto-sleep/history') return autoSleepHistory();
  if (path === '/api/v1/auto-brightness') return { available: true, state: 'learning', sourceAvailable: true, localSourcePresent: false, entityId: 'sensor.hallway_illuminance', latestLux: 42, sourceRevision: 1 };
  if (path === '/api/v1/auto-brightness/history') {
    const now = Math.floor(Date.now() / 60000);
    return { sensitivity: 40, sourceRevision: 1, latestEpochMinute: now, bucket_minutes: 5, points: Array.from({ length: 24 }, (_, index) => ({
      epochMinute: now - (23 - index) * 60, localDay: 0, minuteOfDay: index * 60, dayAge: 0,
      observedMeanLux: 20 + index * 4, minLux: 10 + index * 4, maxLux: 30 + index * 4, expectedLux: 18 + index * 4, proposedBrightness: 60 + index * 6,
    })) };
  }
  if (path === '/api/v1/auto-brightness/sources') return { items: [{ entity_id: 'sensor.hallway_illuminance', name: 'Hallway illuminance' }] };
  if (path === '/api/v1/camera/status') return { state: 'absent' };
  if (path === '/api/v1/voice/pipelines') return { items: [{ id: 'default', name: 'Home Assistant' }] };
  return {};
}

export default {
  name: 'configure',
  path: '/configure',
  html(context) {
    const { s } = context;
    const body = `
<div id="cfg-tools" class="cfg-tools" data-app-version="0.0.0"><input id="cfg-filter" class="cfg-filter" type="search" autocomplete="off" placeholder="${s.t('configure.filter.placeholder')}" aria-label="${s.t('configure.filter.label')}"><div class="cfg-seg" role="radiogroup" aria-label="${s.t('configure.tier.label')}"><label><input type="radio" name="cfg-tier" id="tier-basic" value="basic" checked>${s.t('configure.tab.basic')}</label><label><input type="radio" name="cfg-tier" id="tier-adv" value="advanced">${s.t('configure.tab.advanced')}</label></div><label class="cfg-desc-switch"><input type="checkbox" id="cfg-desc" checked><span class="cfg-desc-track" aria-hidden="true"></span>${s.t('configure.descriptions')}</label><span id="cfg-count" class="muted cfg-count" aria-live="polite"></span></div>
<div id="cfg-status" class="muted" style="margin-bottom:10px">${s.t('configure.status.loading')}</div>
<div id="cfg-all-cards">
<div id="cfg-groups" class="cards" data-card-size-page="configure" data-card-size-epoch="1" data-card-size-restore="1" data-card-size-proximity="0"></div>
</div>
<div id="savebar" class="savebar" role="region" aria-label="${s.t('configure.unsaved.label')}" hidden><button id="savebtn" type="button" disabled onclick="cfgSave()">${s.t('configure.action.save')}</button><span id="cfg-msg" class="muted" role="status" aria-live="polite" aria-atomic="true"></span></div>
<div id="cfg-help" class="cfg-help" popover="manual" role="dialog" aria-labelledby="cfg-help-title"><div class="cfg-help-head"><b id="cfg-help-title"></b><button id="cfg-help-close" class="cfg-help-close" type="button" aria-label="${s.t('configure.help.close')}">×</button></div><div id="cfg-help-body" class="cfg-help-body"></div><div class="cfg-help-foot"><a id="cfg-help-more" target="_blank" rel="noopener">${s.t('configure.help.more')}</a></div></div>
<script src="assets/card-size-memory.js"></script>
<script src="assets/card-column-alignment.js"></script>
<script src="assets/configure.js"></script>`;
    return tabbedPage({ ...context, active: 'configure', sectionTitle: s.text('shell.nav.configure'), body, prefixes: ['shell.', 'configure.', 'runtime.'] });
  },
  api(url, method, context) { return api.call(context.s, url); },
  async ready(frame) {
    // Measure every row in every locale: the gate reads the Advanced view, which holds all of them.
    await frame.waitForFunction(() => typeof window.cfgTab === 'function');
    await frame.evaluate(() => window.cfgTab(true));
    await frame.waitForFunction(() => document.querySelectorAll('#cfg-groups .card').length >= 6 && document.querySelector('.auto-sleep-lane.source'));
  },
};
