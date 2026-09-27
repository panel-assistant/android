// Setup: mirror of the /setup route — pageShell() (not page(): no hardened-approval key, GitHub link
// only, data-build without data-cfg) around setupBody(strings, preserveExplicitEnglish, embedded), with the
// Setup tab shown because setup needs the user. setup.js renders the step from GET /api/v1/setup; the
// journey here stops on the richest translated card: MQTT after a failed connection, which carries the
// failure banner, the lead, three fields with help, the "Where do I find these?" disclosure and the button.
import { shellHtml } from '../harness.mjs';

const STAGES = ['identity', 'renderer', 'ha_url', 'ha_credentials', 'home_dashboard',
  'mqtt_broker', 'mqtt_credentials', 'mqtt_connection', 'entity_filter', 'render_proof'];
const SATISFIED = new Set(['identity', 'renderer', 'ha_url', 'ha_credentials', 'home_dashboard', 'mqtt_broker', 'mqtt_credentials']);

export function journey() {
  return {
    complete: false, repair: false, next: 'mqtt_credentials',
    panel: { id: 'hallway_panel', name: 'Hallway panel' },
    discovery: {}, home_dashboard: { value: 'hallway-panel' },
    entity_filter: { relevant: true, enabled: false, counting: false, count: 321, level: 'green', confidence: 'estimated', tier: 'capable' },
    steps: STAGES.map((stage) => ({
      stage,
      status: stage === 'mqtt_connection' ? 'blocked' : SATISFIED.has(stage) ? 'satisfied' : 'unknown',
      detail: stage === 'mqtt_connection' ? 'unreachable' : '',
    })),
  };
}

const GH_LINK = (s) => `<a class="gh" href="https://example.invalid/repository" target="_blank" rel="noopener" title="${s.t('shell.github.title')}" aria-label="GitHub"><svg viewBox="0 0 24 24"><path d="M12 2a10 10 0 0 0-3 19.5v-3.4c-2.8.6-3.4-1.2-3.4-1.2-.4-1.1-1.1-1.4-1.1-1.4-.9-.6.1-.6.1-.6 1 .1 1.5 1 1.5 1 .9 1.5 2.4 1.1 3 .8.1-.6.3-1.1.6-1.3-2.2-.3-4.6-1.1-4.6-5 0-1.1.4-2 1-2.7-.1-.3-.4-1.3.1-2.7 0 0 .8-.3 2.8 1a9.6 9.6 0 0 1 5 0c1.9-1.3 2.8-1 2.8-1 .5 1.4.2 2.4.1 2.7.6.7 1 1.6 1 2.7 0 3.9-2.4 4.7-4.6 5 .4.3.7.9.7 1.9v2.8A10 10 0 0 0 12 2z"/></svg></a>`;

export default {
  name: 'setup',
  path: '/setup',
  html(context) {
    const { s, locale, embed } = context;
    // The harness always requests an explicit ?lang=, so setupHref() keeps it even for English.
    const escape = `configure?lang=${locale}`;
    const body = `
<div class="wiz" id="wiz">
  <ol class="wiz-dots" id="wiz-dots" aria-label="${s.t('setup.frame.progress_label')}"></ol>
  <div id="wiz-step" class="wiz-step" role="region" aria-live="polite" aria-atomic="false">
    <p class="muted">${s.t('setup.frame.loading')}</p>
  </div>
  <p class="wiz-escape"><a href="${escape}"${embed ? '' : ` onclick="document.cookie='wiz_escape=1;path=/;max-age=3600'"`}>${s.t('setup.frame.skip_exit')}</a></p>
</div>
<script src="assets/setup.js"></script>`;
    return shellHtml({
      ...context, active: 'setup', setupTab: true, sectionTitle: s.text('shell.nav.setup'),
      bodyAttrs: 'data-build="layout-gate"', rightControls: GH_LINK(s), body,
      prefixes: ['shell.', 'setup.', 'runtime.'],
    });
  },
  api(url) {
    const path = url.pathname;
    if (path === '/api/v1/setup') return journey();
    if (path === '/api/v1/config/discovery') return { mqtt_broker: 'tcp://192.0.2.20:1883' };
    if (path === '/api/v1/config/ha-area') return { queried: true, ha_username: 'panel_user', admin: true, areas: [{ name: 'Hallway' }], device: { found: true, area_name: 'Hallway' }, requested: '' };
    if (path === '/api/v1/config/home-dashboards') return { queried: true, items: [{ path: 'hallway-panel', title: 'Hallway panel dashboard', group: 'dashboard' }], default: { explicit: false, path: '' } };
    return {};
  },
  async ready(frame) {
    await frame.waitForFunction(() => document.querySelector('#wiz-step .card') && document.querySelector('#wiz-mqtt_broker')
      && document.querySelectorAll('#wiz-dots li').length >= 5 && document.querySelector('#wiz-step details'));
  },
  async exercise(frame) {
    // mqttFailure() focuses the broker field 120 ms after rendering; let that land before measuring.
    await frame.waitForTimeout(200);
    await frame.evaluate(() => { document.querySelector('#wiz-step details').open = true; });
  },
};
