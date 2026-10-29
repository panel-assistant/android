// The Entities-page advisory row for a template selector (issue #113).
//
// The reporter allowed the check and then looked for the entity their template names. Allowing has
// never added an entity — for any check — so the row has to say that where the button is, and hand
// over the only route that does work: catalogue search plus a manual pin.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { loadTransport, textElement } from './page-transport.mjs';

const source = fs.readFileSync(process.argv[2], 'utf8');
const focused = [];
const scrolled = [];

function element(name = '') {
  const node = textElement(name);
  node.focus = () => { focused.push(name); };
  node.getBoundingClientRect = () => ({ top: 4000, bottom: 4400 });
  node.scrollIntoView = () => { scrolled.push(name); };
  return node;
}

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
  createTextNode: (text) => { const node = element('#text'); node.textContent = text; return node; },
};
global.setInterval = () => 0;
global.confirm = () => false;
global.alert = () => {};

const templateReason = 'Panel Assistant does not evaluate templates, so it cannot tell which entities this '
  + "filter returns. Entities it only reads are Home Assistant's own, delivered outside this filter, so "
  + 'their absence here is correct and they need nothing.';
const blocking = {
  type: 'unbounded_selector', blocking: true, ignored: false, fingerprint: '0123456789abcdef',
  severity: 'error', view_title: 'Cameras', source_locations: ['dashboard.views[0].cards[0]'],
  rule_summary: 'Unbounded template entity selector', candidate_count: null, limit: 64,
  reason: templateReason,
  recommendation: 'Bound the filter structurally, or pin only the entities the template returns.',
};
const gap = {
  type: 'compatibility_gap', blocking: false, ignorable: false, ignored: false, fingerprint: 'gap-1',
  severity: 'warning', view_title: 'Cameras', source_locations: ['dashboard.views[0].cards[1]'],
  rule_summary: 'Button Card uses JavaScript templates', reason: 'Dependencies are hidden.',
  recommendation: 'Keep dependencies explicit.',
};

const response = (data) => ({ ok: true, status: 200, json: async () => data, text: async () => '' });
global.fetch = async (url) => {
  if (url.includes('/entities/issues')) {
    return response({
      dashboard_issue_count: 2, blocking_issue_count: 1, ignored_issue_count: 0,
      items: [blocking, gap], dynamic_expressions: [],
    });
  }
  return response({
    state: 'blocked', stream_entity_count: 10, stream_mode: 'filtered', catalog_count: 100,
    suggested_count: 0, blocking_issue_count: 1, automatic_activation_blocked: true,
    unresolved_count: 0, last_sync_at: 0, db_bytes: 0,
  });
};

loadTransport(process.argv[2]);

vm.runInThisContext(source, { filename: process.argv[2] });
await new Promise((resolve) => setImmediate(resolve));
await new Promise((resolve) => setImmediate(resolve));

const rows = ids['entity-issues-list'].children;
assert.equal(rows.length, 2, 'both advisory rows render');
const [blockingRow, gapRow] = rows;

// The template-specific explanation reaches the reader through the browser DOM renderer.
assert.match(blockingRow.textContent, /does not evaluate templates/);
assert.match(blockingRow.textContent, /delivered outside this filter/);
assert.match(blockingRow.textContent, /they need nothing/);

// What Allow does is stated where Allow is offered, not buried.
assert.match(blockingRow.textContent, /Allowing this check never adds entities/);
assert.match(blockingRow.textContent, /set it to Pinned/);
assert.match(blockingRow.textContent, /a manual pin is kept until you remove it/);
assert.match(blockingRow.textContent, /Search the entity catalogue/);

// A row that offers no Allow makes no claim about it.
assert.ok(!gapRow.textContent.includes('Allowing this check'), 'a non-ignorable row must not offer allow copy');
assert.ok(!gapRow.textContent.includes('Search the entity catalogue'));
assert.equal(gapRow.querySelectorAll('button').length, 0, 'a non-ignorable row offers no control');

// Both controls exist in the rendered row; follow the actual catalogue action a reader can click.
const buttons = blockingRow.querySelectorAll('button');
assert.equal(buttons.length, 2);
const route = buttons.find(button => button.textContent === 'Search the entity catalogue');
assert.ok(route, 'the advisory offers a catalogue search action');
assert.ok(buttons.some(button => button.textContent === 'Ignore potential entities and continue'));
await ids['entity-issues-list'].fire('click', { target: route });
assert.deepEqual(focused, ['entity-search'], 'the rendered route focuses the catalogue search box');

// An unrelated click inside the issues card does nothing.
focused.length = 0;
scrolled.length = 0;
await ids['entity-issues-list'].fire('click', { target: { closest: () => null } });
assert.deepEqual(focused, [], 'an unrelated click must not move focus');
assert.equal(scrolled.length, 0);

window.PaneldTransport.close();
console.log('entity template advisory cases passed');
