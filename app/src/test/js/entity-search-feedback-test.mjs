// Entities-page search feedback: the status line and the one-shot reveal.
//
// The reveal is the risky half. A page that scrolls itself is only acceptable if it does so for a
// query the user typed and for nothing else, so every other repaint route on this page — the 5s and
// 10s polls, a mutation refresh, clearing the box, an empty result, a failed section and a response
// belonging to a superseded query — is asserted here to leave the scroll position alone.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { loadTransport, textElement } from './page-transport.mjs';

const source = fs.readFileSync(process.argv[2], 'utf8');

const scrolls = [];
const focused = [];
const VIEWPORT = 800;

function element(name = '') {
  const value = textElement(name);
  value.indeterminate = false;
  value.top = 0;
  value.querySelector = () => element();
  value.matches = () => false;
  value.getAttribute = key => value.dataset[key.replace('data-', '')];
  value.focus = () => { focused.push(name); };
  value.getBoundingClientRect = () => ({ top: value.top, bottom: value.top + 400, left: 0, right: 800 });
  value.scrollIntoView = options => { scrolls.push({ name, options }); };
  return value;
}

const ids = {};
for (const id of ['entity-status', 'entity-search', 'entity-search-status', 'entity-sync', 'entity-activate',
  'entity-reset', 'entity-action-result', 'entity-auto-static', 'entity-auto-runtime', 'entity-issues',
  'entity-issues-summary', 'entity-issues-list', 'entity-issues-rescan', 'entity-dynamic', 'entity-dynamic-list']) {
  ids[id] = element(id);
}

// Three sections in page order, with Suggested deliberately below the fold — the shape the reporter
// described, where a working search looked like it had done nothing.
function section(table, short, filter, top) {
  const card = element(table);
  card.dataset.table = table;
  card.dataset.short = short;
  card.dataset.filter = filter;
  card.top = top;
  const parts = {
    tbody: element(`${table}-body`), '.entity-msg': element(`${table}-msg`),
    '.entity-prev': element(`${table}-prev`), '.entity-next': element(`${table}-next`),
    '.entity-select-page': element(`${table}-selectpage`), '.entity-selected': element(`${table}-selected`),
    thead: element(`${table}-thead`), 'button[data-all-candidates]': element(`${table}-all`),
  };
  card.querySelector = (selector) => parts[selector] || null;
  card.querySelectorAll = () => [];
  return { card, parts };
}

const current = section('current', 'Current', 'subscribed', 100);
const suggested = section('suggested', 'Suggested', 'candidate', 2400);
const review = section('review', 'Stale or noisy', 'review', 3400);
const sections = [current, suggested, review];

global.window = {
  innerHeight: VIEWPORT,
  matchMedia: () => ({ matches: false }),
};
global.document = {
  hidden: false,
  documentElement: { clientHeight: VIEWPORT },
  getElementById: (id) => ids[id],
  querySelector: () => null,
  querySelectorAll: (selector) => (selector === '.entity-list' ? sections.map((s) => s.card) : []),
  createElement: tag => element(tag),
  createTextNode: text => { const node = element('#text'); node.textContent = text; return node; },
};
// The page refreshes in the background from its `entities` subscription, which the page transport polls at
// the family's floor; those timers are held here and fired by backgroundRefresh(), the others run for real.
const polls = [];
const realSetTimeout = setTimeout;
global.setTimeout = (callback, ms, ...rest) => (window.PaneldTransport && ms === window.PaneldTransport.families.entities.floor
  ? polls.push(callback)
  : realSetTimeout(callback, ms, ...rest));
global.confirm = () => true;
global.alert = () => {};

const response = (data, ok = true) => ({ ok, status: ok ? 200 : 500, json: async () => data, text: async () => JSON.stringify(data) });
const totals = { subscribed: 0, candidate: 0, review: 0 };
let failFilters = new Set();
let holdKeys = new Set();
const held = [];

const holds = (query, filter) => holdKeys.has(`${query}|*`) || holdKeys.has(`${query}|${filter}`);

function releaseHeld(match) {
  const ready = held.filter((entry) => match(entry));
  for (const entry of ready) { held.splice(held.indexOf(entry), 1); entry.send(); }
  return ready.length;
}

const syncStatus = {
  state: 'active', sync_running: false, stream_entity_count: 4, catalog_count: 5, suggested_count: 1,
  last_sync_at: 1, db_bytes: 0, auto_static: true, auto_runtime: true, apply_required: false,
  blocking_issue_count: 0, ignored_issue_count: 0,
};
const listRequests = [];

global.fetch = async (url, options = {}) => {
  if ((options.method || 'GET') === 'POST') return response({ ok: true });
  // The entities family also reads the unfiltered list (dashboard.entities), as the panel's family does.
  if (url.endsWith('/dashboard/entities')) return response({ items: [], total: 0 });
  if (url.includes('/entities/issues')) return response({ items: [], dashboard_issue_count: 0, dynamic_expressions: [] });
  if (url.endsWith('/entities/sync')) return response(syncStatus);
  if (url.includes('/entities?')) {
    const params = new URL(url, 'http://panel.test').searchParams;
    const filter = params.get('filter');
    const query = params.get('q');
    listRequests.push({ filter, query, offset: params.get('offset'), sort: params.get('sort'), dir: params.get('dir') });
    const answered = totals[filter];
    const send = () => (failFilters.has(filter)
      ? Promise.reject(new Error('list unavailable'))
      : Promise.resolve(response({ items: [], total: answered })));
    if (holds(query, filter)) return new Promise((resolve, reject) => { held.push({ query, filter, send: () => send().then(resolve, reject) }); });
    return send();
  }
  throw new Error(`unexpected request ${url}`);
};

loadTransport(process.argv[2]);
// A background refresh: the next subscription poll finds a newer scan, which is when the page reloads its lists.
async function backgroundRefresh() {
  syncStatus.last_sync_at += 1;
  for (const poll of polls.splice(0)) poll();
  await settle();
}

vm.runInThisContext(source, { filename: process.argv[2] });

const settle = async () => { for (let i = 0; i < 8; i += 1) await new Promise((resolve) => setImmediate(resolve)); };
const debounce = async () => { await new Promise((resolve) => setTimeout(resolve, 320)); await settle(); };
const searchStatus = ids['entity-search-status'];
const search = ids['entity-search'];

async function type(text) {
  search.value = text;
  await search.fire('input');
}

await settle();
assert.equal(scrolls.length, 0, 'the initial load must not scroll');
assert.equal(searchStatus.textContent, '', 'an untouched search box shows no status');

// --- 1. "Searching" is immediate, before the debounce has even fired -----------------------------
totals.subscribed = 1; totals.candidate = 3; totals.review = 0;
await type('kitchen');
assert.equal(searchStatus.textContent, 'Searching…', 'typing must acknowledge immediately');
assert.equal(scrolls.length, 0, 'no scroll before results exist');

// --- 2. same-generation per-section counts, only once every request has settled -------------------
await debounce();
assert.equal(
  searchStatus.textContent,
  'Matches: Current: 1, Suggested: 3, Stale or noisy: 0',
  'counts must name every section from one settled generation',
);

// --- 3. Suggested is brought into view exactly once ------------------------------------------------
assert.equal(scrolls.length, 1, 'a stable user query with matches reveals exactly one section');
assert.equal(scrolls[0].name, 'suggested', 'Suggested is preferred when it has matches');
assert.equal(scrolls[0].options.block, 'start');
assert.equal(focused.length, 0, 'revealing a section must not steal focus');

// --- 4. polling never scrolls and never re-announces -----------------------------------------------
const afterSearch = scrolls.length;
await backgroundRefresh();
await settle();
assert.equal(scrolls.length, afterSearch, 'a poll tick must not move the page');
assert.equal(searchStatus.textContent, 'Matches: Current: 1, Suggested: 3, Stale or noisy: 0');

// --- 5. a mutation refresh never scrolls ------------------------------------------------------------
await ids['entity-auto-static'].fire('change');
await settle();
assert.equal(scrolls.length, afterSearch, 'a policy save must not move the page');

// --- 6. pagination and sorting survive, and a poll does not reset them --------------------------------
await suggested.parts.thead.fire('click', { target: { closest: () => ({ dataset: { sort: 'access_1h' } }) } });
await settle();
await suggested.parts['.entity-next'].fire('click');
await settle();
listRequests.length = 0;
await backgroundRefresh();
await settle();
const polled = listRequests.find((entry) => entry.filter === 'candidate');
assert.ok(polled, 'the background refresh re-reads the Suggested section');
assert.equal(polled.offset, '100', 'a poll must keep the current page');
assert.equal(polled.sort, 'access_1h', 'a poll must keep the chosen sort column');
assert.equal(polled.dir, 'desc');
assert.equal(scrolls.length, afterSearch, 'sorting and paging must not move the page');

// --- 7. selection survives a background refresh --------------------------------------------------------
const checkbox = element('row-checkbox');
checkbox.checked = true;
checkbox.dataset.id = 'light.kitchen';
checkbox.matches = (selector) => selector === '.entity-select';
await suggested.parts.tbody.fire('change', { target: checkbox });
await settle();
assert.equal(suggested.parts['.entity-selected'].textContent, '1 selected');
await backgroundRefresh();
await settle();
assert.equal(suggested.parts['.entity-selected'].textContent, '1 selected', 'a poll must not clear the selection');

// --- 8. a superseded response never fills a slot the current generation still owes -------------------
// The dangerous interleave: the old query's answers arrive while the new query is still waiting on one
// section. Dropping them is what stops one settled line mixing two generations' numbers.
const beforeStale = scrolls.length;
totals.subscribed = 1; totals.candidate = 1; totals.review = 1;
holdKeys = new Set(['office|*']);
await type('office');
await debounce();
assert.equal(searchStatus.textContent, 'Searching…', 'an unsettled generation shows no counts');

totals.subscribed = 9; totals.candidate = 9; totals.review = 9;
holdKeys = new Set(['office|*', 'bedroom|review']);
await type('bedroom');
await debounce();
assert.equal(searchStatus.textContent, 'Searching…', 'the new generation is still owed one section');

assert.equal(releaseHeld((entry) => entry.query === 'office'), 3, 'the superseded responses were outstanding');
await settle();
assert.equal(searchStatus.textContent, 'Searching…', 'a superseded response must not complete the new generation');
assert.equal(scrolls.length, beforeStale, 'a superseded response never scrolls');

assert.equal(releaseHeld((entry) => entry.query === 'bedroom'), 1);
await settle();
assert.equal(
  searchStatus.textContent,
  'Matches: Current: 9, Suggested: 9, Stale or noisy: 9',
  'the settled line reports one generation only',
);
assert.equal(scrolls.length, beforeStale + 1, 'the fresh generation reveals once');
holdKeys = new Set();

// --- 8b. paging a section mid-search still lets the generation finish ---------------------------------
// Sorting or paging supersedes that section's pending request. If the section could then never answer,
// the counts line would sit on "Searching…" for good.
holdKeys = new Set(['landing|*']);
totals.subscribed = 2; totals.candidate = 2; totals.review = 2;
await type('landing');
await debounce();
assert.equal(searchStatus.textContent, 'Searching…');
holdKeys = new Set();
await suggested.parts['.entity-next'].fire('click');
await settle();
releaseHeld((entry) => entry.query === 'landing');
await settle();
assert.equal(
  searchStatus.textContent,
  'Matches: Current: 2, Suggested: 2, Stale or noisy: 2',
  'a section paged mid-search must still report into the pending generation',
);

// --- 8c. a keystroke during the debounce invalidates the dispatch already in flight ----------------
// A review finding: the input handler reset the timer and wrote "Searching…" but left the previous
// dispatch live. Its responses, landing inside the 250 ms window, replaced the acknowledgement
// with the OLD query's counts, rendered its rows and scrolled — before the new query was even sent, and
// while the user was still typing, which is exactly the "stable query" condition the reveal is meant to
// respect. A keystroke is a new intent; everything in flight before it is superseded on the spot.
const beforeKeystroke = scrolls.length;
totals.subscribed = 7; totals.candidate = 7; totals.review = 7;
holdKeys = new Set(['a|*']);
await type('a');
await debounce();
assert.equal(searchStatus.textContent, 'Searching…', 'the first query is dispatched and waiting');
totals.subscribed = 2; totals.candidate = 2; totals.review = 2;
await type('ab');
assert.equal(searchStatus.textContent, 'Searching…');
assert.equal(releaseHeld((entry) => entry.query === 'a'), 3, 'the superseded responses were in flight');
await settle();
assert.equal(searchStatus.textContent, 'Searching…', 'a response to a query typed over must not replace the acknowledgement');
assert.equal(scrolls.length, beforeKeystroke, 'a response to a query typed over must not scroll');
for (const { parts } of sections) {
  assert.ok(!parts['.entity-msg'].textContent.includes('of 7'), 'a response to a query typed over must not render its rows');
}
holdKeys = new Set();
await debounce();
assert.equal(searchStatus.textContent, 'Matches: Current: 2, Suggested: 2, Stale or noisy: 2', 'the query the user settled on reports');
assert.equal(scrolls.length, beforeKeystroke + 1, 'and reveals exactly once');
for (const { parts } of sections) assert.ok(parts['.entity-msg'].textContent.includes('of 2'));

// --- 9. the first matching section is used when Suggested has none ----------------------------------------
const beforeFallback = scrolls.length;
totals.subscribed = 0; totals.candidate = 0; totals.review = 4;
await type('stale');
await debounce();
assert.equal(searchStatus.textContent, 'Matches: Current: 0, Suggested: 0, Stale or noisy: 4');
assert.equal(scrolls.length, beforeFallback + 1);
assert.equal(scrolls[scrolls.length - 1].name, 'review', 'falls back to the first section with matches');

// --- 10. zero results announce, and never scroll -------------------------------------------------------------
const beforeEmpty = scrolls.length;
totals.subscribed = 0; totals.candidate = 0; totals.review = 0;
await type('nothing-matches-this');
await debounce();
assert.equal(searchStatus.textContent, 'No entities match this search.');
assert.equal(scrolls.length, beforeEmpty, 'an empty result must not move the page');

// --- 11. a failed section reports, and never scrolls -----------------------------------------------------------
const beforeFailure = scrolls.length;
failFilters = new Set(['candidate']);
totals.subscribed = 2; totals.review = 2;
await type('kitchen light');
await debounce();
assert.equal(searchStatus.textContent, 'Search results are temporarily unavailable.');
assert.equal(scrolls.length, beforeFailure, 'a failed section must not move the page');
failFilters = new Set();

// --- 12. clearing the box clears the line and never scrolls ------------------------------------------------------
const beforeClear = scrolls.length;
totals.subscribed = 5; totals.candidate = 5; totals.review = 5;
await type('');
assert.equal(searchStatus.textContent, '', 'clearing the box clears the status immediately');
await debounce();
assert.equal(searchStatus.textContent, '', 'an empty query has nothing to report');
assert.equal(scrolls.length, beforeClear, 'clearing must not move the page');

// --- 13. a revealed section that is already on screen is left alone ------------------------------------------------
const beforeVisible = scrolls.length;
suggested.card.top = 120;
totals.subscribed = 0; totals.candidate = 3; totals.review = 0;
await type('already visible');
await debounce();
assert.equal(scrolls.length, beforeVisible, 'a section already in view is not scrolled again');

window.PaneldTransport.close();
console.log('entity search feedback cases passed');
