import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { loadTransport, textElement } from './page-transport.mjs';

const source = fs.readFileSync(process.argv[2], 'utf8');
const created = [];
function element() {
  const value = textElement();
  value.indeterminate = false;
  value.querySelector = () => element();
  value.matches = () => false;
  value.getAttribute = name => value.dataset[name.replace('data-', '')];
  created.push(value);
  return value;
}

const ids = {};
['entity-status','entity-search','entity-search-status','entity-sync','entity-activate','entity-reset','entity-action-result','entity-auto-static','entity-auto-runtime','entity-issues','entity-issues-summary','entity-issues-list','entity-issues-rescan','entity-dynamic','entity-dynamic-list'].forEach(id => { ids[id] = element(); });
const body = element(), message = element(), previous = element(), next = element(), selectPage = element(), selected = element(), thead = element();
const bulk = element(), allCandidates = element(), overrideSelect = element();
bulk.dataset.bulk = 'pinned';
overrideSelect.value = 'pinned';
overrideSelect.dataset.id = 'light.test';
overrideSelect.matches = selector => selector === 'select[data-id]';
const card = element();
card.dataset.filter = 'suggested';
card.querySelector = selector => ({'tbody':body,'.entity-msg':message,'.entity-prev':previous,'.entity-next':next,'.entity-select-page':selectPage,'.entity-selected':selected,'thead':thead,'button[data-all-candidates]':allCandidates}[selector] || null);
card.querySelectorAll = selector => selector === 'button[data-bulk]' ? [bulk] : [];

let issueButton = null;
global.document = {
  hidden: false,
  getElementById: id => ids[id],
  querySelector: () => null,
  querySelectorAll(selector) {
    if (selector === '.entity-list') return [card];
    if (selector.includes('.entity-list select[data-id]')) return [overrideSelect, bulk, allCandidates, issueButton].filter(Boolean);
    return [];
  },
  createElement: () => element(),
  createTextNode: text => { const node = element(); node.textContent = text; return node; },
};
global.setInterval = () => 0;
global.confirm = () => true;
const alerts = [];
global.alert = message => { alerts.push(String(message)); };

const posts = [];
let resetResolve, policyResolve, deferPolicy = false, resetFetch;
const resetResponse = new Promise(resolve => { resetResolve = resolve; });
const policyResponse = new Promise(resolve => { policyResolve = resolve; });
resetFetch = () => resetResponse;
const response = (data, status = 200) => ({ok: status >= 200 && status < 300, status, json: async () => data, text: async () => typeof data === 'string' ? data : JSON.stringify(data)});
const status = {state:'active',sync_running:false,stream_entity_count:4,catalog_count:5,suggested_count:1,last_sync_at:1,db_bytes:0,auto_static:true,auto_runtime:true,apply_required:true,activation_required:false,stream_change_required:true,desired_count:5,pending_additions:1,pending_removals:0,blocking_issue_count:0,ignored_issue_count:1};
global.fetch = async (url, options = {}) => {
  const method = options.method || 'GET';
  if (method === 'POST') {
    posts.push(url);
    if (url.endsWith('/reset')) return resetFetch();
    if (url.endsWith('/policy') && deferPolicy) return policyResponse;
    return response({ok:true,stream_changed:false,entity_count:4});
  }
  // The entities family also reads the unfiltered list (dashboard.entities), as the panel's family does.
  if (url.endsWith('/dashboard/entities')) return response({ items: [], total: 0 });
  if (url.includes('/entities/issues')) return response({items:[{fingerprint:'issue-1',blocking:true}],dashboard_issue_count:1});
  if (url.includes('/entities?')) return response({items:[{entity_id:'light.test',reasons:'dashboard'}],total:1});
  if (url.endsWith('/entities/sync')) return response(status);
  throw new Error('unexpected request ' + method + ' ' + url);
};

global.window = {};

loadTransport(process.argv[2]);

vm.runInThisContext(source, {filename: process.argv[2]});
const settle = async () => { for (let i = 0; i < 5; i++) await new Promise(resolve => setImmediate(resolve)); };
await settle();
issueButton = created.find(item => item.className.includes('entity-issue-toggle'));
assert.ok(issueButton, 'issue-ignore control was not created');

const resetRun = ids['entity-reset'].fire('click');
await settle();
assert.deepEqual(posts, ['api/v1/dashboard/entities/reset']);
await ids['entity-sync'].fire('click');
await ids['entity-activate'].fire('click');
await ids['entity-auto-static'].fire('change');
await body.fire('change', {target: overrideSelect});
await allCandidates.fire('click');
await issueButton.fire('click');
assert.deepEqual(posts, ['api/v1/dashboard/entities/reset'], 'a competing mutation escaped while reset owned the gate');

resetResolve(response({ok:true,sync_started:false}));
await resetRun;
await settle();
assert.equal(ids['entity-reset'].disabled, false, 'reset remained disabled after success');
assert.equal(ids['entity-auto-static'].disabled, false, 'policy remained disabled after reset success');

resetFetch = async () => { throw new Error('offline'); };
await ids['entity-reset'].fire('click');
await settle();
assert.equal(alerts.at(-1), undefined, 'reset failures should use the inline status, not an alert');
assert.match(ids['entity-action-result'].textContent, /Reset failed\. offline/);
assert.equal(ids['entity-reset'].disabled, false, 'reset remained disabled after rejected fetch');
assert.equal(ids['entity-auto-static'].disabled, false, 'policy remained disabled after rejected fetch');

resetFetch = async () => response('reset denied', 409);
await ids['entity-reset'].fire('click');
await settle();
assert.match(ids['entity-action-result'].textContent, /Reset failed\. reset denied/);
assert.equal(ids['entity-reset'].disabled, false, 'reset remained disabled after non-2xx response');
assert.equal(ids['entity-auto-static'].disabled, false, 'policy remained disabled after non-2xx response');

deferPolicy = true;
const policyRun = ids['entity-auto-static'].fire('change');
await settle();
assert.equal(posts.at(-1), 'api/v1/dashboard/entities/policy');
const resetCount = posts.filter(url => url.endsWith('/reset')).length;
await ids['entity-reset'].fire('click');
assert.equal(posts.filter(url => url.endsWith('/reset')).length, resetCount, 'reset escaped while policy owned the gate');
policyResolve(response({ok:true}));
await policyRun;
await settle();

window.PaneldTransport.close();
console.log('entity mutation gate deferred-fetch cases passed');
