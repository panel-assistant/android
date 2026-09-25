// The Entities page under an allowed dashboard-strategy check (issue #133 follow-up).
//
// A strategy builds its cards from the entities Home Assistant sends, so with the check allowed an
// entity outside the subscription gets no card and never reaches runtime learning. Nothing the panel
// observes can list those entities, so the page has to say so, on the status line and on the row,
// and tell the reader what to pin and how. Any other allowed check, or the same check still pending
// a choice, says nothing of the kind.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(process.argv[2], 'utf8');

function element(name = '') {
  return {
    name,
    dataset: {}, className: '', textContent: '', innerHTML: '', checked: false, disabled: false,
    value: '', children: [], hidden: false,
    classList: { toggle() {}, remove() {}, add() {} },
    addEventListener() {},
    appendChild(child) { this.children.push(child); return child; },
    querySelector() { return null; }, querySelectorAll() { return []; },
    matches() { return false; }, closest() { return null; },
  };
}

const strategy = (ignored) => ({
  type: 'unbounded_selector', blocking: !ignored, ignored, ignorable: true, fingerprint: `strategy-${ignored}`,
  severity: ignored ? 'warning' : 'error', view_title: 'Dashboard', view_path: 'dashboard',
  source_locations: ['dashboard.strategy'], rule_summary: 'Dashboard strategy generates entity dependencies at runtime',
  candidate_count: null, limit: 64, reason: '', recommendation: '', presentation_code: 'dashboard-strategy',
});
const otherAllowed = {
  type: 'unbounded_selector', blocking: false, ignored: true, ignorable: true, fingerprint: 'selector-1',
  severity: 'warning', view_title: 'Lights', source_locations: ['dashboard.views[0].cards[0]'],
  rule_summary: 'Unbounded selector', candidate_count: null, limit: 64, reason: '', recommendation: '',
  presentation_code: 'selector-unbounded-or-dynamic',
};

async function render({ items, strategySelectorIgnored }) {
  const ids = {};
  for (const id of ['entity-status', 'entity-search', 'entity-search-status', 'entity-sync', 'entity-activate',
    'entity-reset', 'entity-action-result', 'entity-auto-static', 'entity-auto-runtime', 'entity-issues',
    'entity-issues-summary', 'entity-issues-list', 'entity-issues-rescan', 'entity-dynamic', 'entity-dynamic-list']) {
    ids[id] = element(id);
  }
  global.window = { innerHeight: 800, matchMedia: () => ({ matches: false }) };
  global.document = {
    hidden: false,
    documentElement: { clientHeight: 800 },
    getElementById: (id) => ids[id],
    querySelector: () => null,
    querySelectorAll: () => [],
    createElement: (tag) => element(tag),
  };
  global.setInterval = () => 0;
  global.confirm = () => false;
  global.alert = () => {};
  const response = (data) => ({ ok: true, status: 200, json: async () => data, text: async () => '' });
  global.fetch = async (url) => {
    if (url.includes('/entities/issues')) {
      return response({
        dashboard_issue_count: items.length, blocking_issue_count: items.filter((i) => i.blocking).length,
        ignored_issue_count: items.filter((i) => i.ignored).length, items, dynamic_expressions: [],
      });
    }
    return response({
      state: 'active', stream_entity_count: 4, stream_mode: 'filtered', catalog_count: 442,
      suggested_count: 0, blocking_issue_count: 0, ignored_issue_count: items.filter((i) => i.ignored).length,
      unresolved_count: 0, last_sync_at: 0, db_bytes: 0, strategy_selector_ignored: strategySelectorIgnored,
    });
  };
  vm.runInThisContext(source, { filename: process.argv[2] });
  await new Promise((resolve) => setImmediate(resolve));
  await new Promise((resolve) => setImmediate(resolve));
  return ids;
}

const occurrences = (text, needle) => text.split(needle).length - 1;
const statusNeedle = 'strategy dashboard: entities outside the subscription have no cards';
const noteNeedle = 'Home Assistant builds this dashboard from a strategy';

// Allowed strategy check under a filtered stream: the status line says so once, in the hot style.
let ids = await render({ items: [strategy(true)], strategySelectorIgnored: true });
assert.equal(occurrences(ids['entity-status'].innerHTML, statusNeedle), 1, 'the status line names the strategy consequence once');
assert.match(ids['entity-status'].innerHTML, new RegExp(`<span class="hot">${statusNeedle}</span>`));

// ...and its row explains why nothing can list the missing entities, and what to pin and how.
let rows = ids['entity-issues-list'].children;
assert.equal(rows.length, 1);
assert.equal(rows[0].className, 'entity-issue allowed');
assert.equal(occurrences(rows[0].innerHTML, noteNeedle), 1, 'the allowed strategy row carries the note exactly once');
assert.match(rows[0].innerHTML, /<p class="entity-issue-strategy-note">/);
assert.match(rows[0].innerHTML, /gets no card here and is never learned, so this page cannot list what is missing/);
assert.match(rows[0].innerHTML, /pin the entity behind every card that is missing here/);
// The note comes before the general allow note and its catalogue route, which it relies on.
assert.ok(rows[0].innerHTML.indexOf(noteNeedle) < rows[0].innerHTML.indexOf('Allowing this check never adds entities'));
assert.equal(occurrences(rows[0].innerHTML, 'Search the entity catalogue'), 1);

// The same strategy check still awaiting a choice has not dropped anything yet: no note, no status part.
ids = await render({ items: [strategy(false)], strategySelectorIgnored: false });
assert.equal(occurrences(ids['entity-status'].innerHTML, statusNeedle), 0);
rows = ids['entity-issues-list'].children;
assert.equal(rows.length, 1);
assert.equal(rows[0].className, 'entity-issue blocking');
assert.equal(occurrences(rows[0].innerHTML, noteNeedle), 0, 'a pending strategy check makes no missing-card claim');

// Any other allowed check keeps the general note only.
ids = await render({ items: [otherAllowed], strategySelectorIgnored: false });
assert.equal(occurrences(ids['entity-status'].innerHTML, statusNeedle), 0);
rows = ids['entity-issues-list'].children;
assert.equal(occurrences(rows[0].innerHTML, noteNeedle), 0, 'another allowed check makes no strategy claim');
assert.equal(occurrences(rows[0].innerHTML, 'Allowing this check never adds entities'), 1);

// The status line follows the server's flag, not the row: an unfiltered stream loses nothing.
ids = await render({ items: [strategy(true)], strategySelectorIgnored: false });
assert.equal(occurrences(ids['entity-status'].innerHTML, statusNeedle), 0, 'no status claim unless the server reports it');

console.log('entity strategy allowed cases passed');
