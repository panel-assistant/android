// Entities: mirror of PaneldServer.entitiesBody() (the enabled built-in-renderer branch) and
// entityTableHtml() inside page(). The API fixture fills the richest translated state: a status line with
// every counter, blocking, allowed and limited-coverage issues with rule evidence and a dynamic expression,
// three populated tables with reason icons and overrides, a selection and a next page.
import { tabbedPage } from '../harness.mjs';

const TABLES = [
  { id: 'current', prefix: 'entities.table.current', filter: 'subscribed' },
  { id: 'suggested', prefix: 'entities.table.suggested', filter: 'candidate' },
  { id: 'review', prefix: 'entities.table.review', filter: 'review' },
];

function tableHtml({ id, prefix, filter }, s) {
  return `
<div class="card entity-list" data-filter="${filter}" data-table="${id}" data-short-key="${prefix}.short"><h2>${s.t(`${prefix}.title`)}</h2>
  <p class="muted">${s.t(`${prefix}.note`)}</p>
  <div class="entity-bulk" style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:10px">
    <button class="pbtn" data-bulk="pinned">${s.t('entities.bulk.pin_selected')}</button><button class="pbtn" data-bulk="auto">${s.t('entities.bulk.auto_selected')}</button><button class="pbtn" data-bulk="forced_exclude">${s.t('entities.bulk.exclude_selected')}</button>
    ${filter === 'candidate' ? `<button class="pbtn" data-all-candidates="true">${s.t('entities.bulk.pin_all_suggested')}</button>` : ''}<span class="muted entity-selected">${s.t('entities.selection.none')}</span>
  </div>
  <div class="tablewrap"><table class="entity-table"><thead><tr><th class="col-select"><input type="checkbox" class="entity-select-page" aria-label="${s.t('entities.table.select_page')}"></th><th class="col-entity"><button data-sort="entity_id">${s.t('entities.table.entity')}</button></th><th class="col-access"><button data-sort="access_1h">${s.t('entities.table.accesses')} <small>${s.t('entities.table.period_tooltip')}</small></button></th><th class="col-rate"><button data-sort="rate_1h_bps">${s.t('entities.table.data_rate')} <small>${s.t('entities.table.bytes_per_second')} · ${s.t('entities.table.period_tooltip')}</small></button></th><th class="col-reason"><button data-sort="reasons">${s.t('entities.table.reason')}</button></th><th class="col-last"><button data-sort="last_access_at">${s.t('entities.table.last_access')}</button></th><th class="col-override"><button data-sort="override">${s.t('entities.table.override')}</button></th></tr></thead><tbody></tbody></table></div>
  <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:10px">
    <button class="pbtn entity-prev">${s.t('entities.pagination.previous')}</button><button class="pbtn entity-next">${s.t('entities.pagination.next')}</button><span class="muted entity-msg">${s.t('entities.pagination.loading')}</span>
  </div>
</div>`;
}

/** entityOwnedMarkup(): server-owned <code> inside an escaped translated sentence. */
function owned(s, key, markers) {
  let html = s.t(key);
  for (const marker of markers) html = html.split(marker.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')).join(`<code>${marker}</code>`);
  return html;
}

const IDS = ['light.living_room', 'light.kitchen_ceiling', 'sensor.outdoor_temperature', 'switch.garden_fountain', 'binary_sensor.front_door',
  'climate.hallway', 'media_player.living_room_speaker', 'cover.bedroom_blinds', 'sensor.living_room_humidity', 'weather.home',
  'person.example', 'sun.sun'];

function rows(filter) {
  const now = Date.now();
  return IDS.map((entity_id, index) => ({
    entity_id,
    access_1m: index, access_1h: 60 + index * 7, access_1d: 1200 + index * 90,
    access_1m_rank: 0.2, access_1h_rank: 0.5, access_1d_rank: 0.9,
    rate_1m_bps: 12 + index, rate_1h_bps: 340 + index * 11, rate_1d_bps: 4096 + index * 128,
    rate_1m_rank: 0.1, rate_1h_rank: 0.6, rate_1d_rank: 0.95,
    reasons: filter === 'review' ? 'stale' : 'dashboard card',
    static: index % 2 === 0, runtime: index % 3 === 0, pinned: index === 1, excluded: filter === 'review' && index === 2,
    last_access: now - (index + 1) * 60_000,
  }));
}

const STATUS = {
  state: 'learning', sync_running: false, stream_entity_count: 184, stream_mode: 'filtered', catalog_count: 1240,
  suggested_count: 37, candidate_count: 37, blocking_issue_count: 1, ignored_issue_count: 1, unresolved_count: 2,
  strategy_selector_ignored: false, last_sync_at: Date.now() - 5 * 60_000, db_bytes: 3_145_728,
  auto_static: true, auto_runtime: true, apply_required: true, stream_change_required: true,
  desired_count: 190, pending_additions: 8, pending_removals: 2,
};

const ISSUES = {
  dashboard_issue_count: 3, blocking_issue_count: 1, ignored_issue_count: 1,
  items: [
    { type: 'selector', blocking: true, ignored: false, ignorable: true, fingerprint: 'blocking-template', presentation_code: 'template-selector',
      view_title: 'Living room', card_title: 'Lights overview', source_locations: ['views[0].cards[2]', 'views[0].cards[3]'],
      candidate_count: 70, limit: 64, rule_summary: 'label: lights', reason: 'template selects every light', recommendation: 'name the lights explicitly' },
    { type: 'selector', blocking: false, ignored: true, ignorable: true, fingerprint: 'allowed-broad', presentation_code: 'selector-broad',
      view_title: 'Energy', card_title: '', source_locations: ['views[1].cards[0]'],
      candidate_count: 120, limit: 64, rule_summary: 'domain: sensor', reason: 'selector matches every sensor', recommendation: 'narrow by area' },
    { type: 'limited_support', blocking: false, ignored: false, fingerprint: 'kiosk', presentation_code: 'kiosk_mode-limited-support',
      view_title_index: 3, source_locations: ['kiosk_mode'], rule_summary: 'kiosk_mode', reason: 'kiosk mode', recommendation: '' },
  ],
  dynamic_expressions: [
    { fingerprint: 'dyn-1', source_location: 'views[2].cards[1]', literal: "{{ states.light | selectattr('state','eq','on') | list }}", truncated: true },
  ],
};

export default {
  name: 'entities',
  path: '/entities',
  html(context) {
    const { s } = context;
    const body = `
<div class="cards entity-cards">
  <div class="card"><h2>${s.t('entities.filter.title')}</h2>
    <div id="entity-status">${s.t('entities.filter.loading')}</div>
    <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:12px">
      <button class="pbtn" id="entity-sync">${s.t('entities.filter.scan')}</button>
      <button class="pbtn" id="entity-activate" disabled>${s.t('entities.filter.checking')}</button>
      <button class="pbtn" id="entity-reset" type="button">${s.t('entities.filter.reset')}</button>
      <a class="pbtn" href="api/v1/dashboard/entities/export">${s.t('entities.filter.export')}</a>
    </div>
    <div id="entity-action-result" class="entity-action-result muted" role="status" aria-live="polite"></div>
    <fieldset class="entity-policy"><legend>${s.t('entities.policy.legend')}</legend>
      <label><input type="checkbox" id="entity-auto-static"> ${s.t('entities.policy.static')}</label>
      <label><input type="checkbox" id="entity-auto-runtime"> ${owned(s, 'entities.policy.runtime', ['hass.states'])}</label>
      <p class="muted">${s.t('entities.policy.note')}</p>
    </fieldset>
  </div>
  <div class="entity-search-row">
    <label class="sr-only" for="entity-search">${s.t('entities.search.label')}</label>
    <input id="entity-search" type="search" autocomplete="off" placeholder="${s.t('entities.search.placeholder')}" aria-describedby="entity-search-status">
    <div id="entity-search-status" class="entity-search-status muted" role="status" aria-live="polite"></div>
  </div>
  <div class="card entity-issues" id="entity-issues"><h2>${s.t('entities.issues.title')}</h2>
    <div id="entity-issues-summary" class="muted" role="status" aria-live="polite">${s.t('entities.issues.checking')}</div>
    <div id="entity-issues-list" class="entity-issues-list"></div>
    <section id="entity-dynamic" class="entity-dynamic" hidden>
      <h3>${s.t('entities.dynamic.title')}</h3>
      <p class="muted">${owned(s, 'entities.dynamic.body', ['{{ ... }}', '{% ... %}'])}</p>
      <div id="entity-dynamic-list" class="entity-dynamic-list"></div>
    </section>
    <button class="pbtn" id="entity-issues-rescan" type="button">${s.t('entities.issues.rescan')}</button>
  </div>
  ${TABLES.map((table) => tableHtml(table, s)).join('\n')}
</div>
<script src="assets/entities.js"></script>`;
    return tabbedPage({ ...context, active: 'entities', sectionTitle: s.text('shell.nav.entities'), body, prefixes: ['shell.', 'entities.', 'runtime.'] });
  },
  api(url) {
    const path = url.pathname;
    if (path === '/api/v1/dashboard/entities/sync') return STATUS;
    if (path === '/api/v1/dashboard/entities/issues') return ISSUES;
    if (path === '/api/v1/dashboard/entities') {
      const filter = url.searchParams.get('filter') || 'subscribed';
      return { items: rows(filter), total: filter === 'review' ? 12 : 240 };
    }
    return {};
  },
  async ready(frame) {
    await frame.waitForFunction(() => document.querySelectorAll('.entity-table tbody tr').length >= 36
      && document.querySelectorAll('#entity-issues-list .entity-issue').length >= 3
      && !document.getElementById('entity-dynamic').hidden
      && document.querySelector('#entity-status b'));
  },
  async exercise(frame) {
    // A selection shows the "{count} selected" state and the indeterminate page checkbox.
    await frame.evaluate(() => {
      document.querySelectorAll('.entity-list').forEach((card) => {
        // Each change re-renders the table body, so look the row up again every time.
        for (const index of [0, 1]) {
          const box = card.querySelectorAll('.entity-select')[index];
          box.checked = true; box.dispatchEvent(new Event('change', { bubbles: true }));
        }
      });
    });
    await frame.waitForFunction(() => [...document.querySelectorAll('.entity-selected')].every((node) => !/\b0\b/.test(node.textContent) && /2/.test(node.textContent)));
  },
};
