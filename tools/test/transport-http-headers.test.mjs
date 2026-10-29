// The HTTP transport asks the form routes for JSON, lets the browser keep a cached screenshot, reports a failed
// route's status, and keeps one polling loop per subscription across a hide and show.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../../app/src/main/assets/transport.js', import.meta.url), 'utf8');

function load(fetch) {
  const timers = [], listeners = {};
  const window = {};
  const document = { body: { dataset: {} }, baseURI: 'http://panel.test/', hidden: false, addEventListener(name, fn) { listeners[name] = fn; } };
  vm.runInNewContext(source, {
    window, URL, Promise, document,
    location: { href: 'http://panel.test/' },
    setTimeout(fn, ms) { timers.push({ fn, ms }); return timers.length; },
    clearTimeout(id) { if (timers[id - 1]) timers[id - 1].cleared = true; },
    fetch,
  });
  return { transport: window.PaneldTransport, timers, document, listeners };
}

const reply = (status, body) => Promise.resolve({ status, headers: { get: () => 'application/json', forEach() {} }, text: () => Promise.resolve(body) });
const settle = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };

test('the form routes are asked for JSON and only a screenshot may be cached', async () => {
  const requests = [];
  const { transport } = load((url, init) => { requests.push({ url, init }); return reply(200, '{}'); });
  for (const op of ['display.density', 'power_safety.acknowledge', 'power_safety.repair', 'tame', 'settings']) {
    await transport.request(op, {});
    assert.equal(requests.at(-1).init.headers.Accept, 'application/json', op);
  }
  await transport.request('screenshot', { cached: 'a'.repeat(64) });
  assert.equal(requests.at(-1).init.cache, 'default');
  await transport.request('perf', {});
  assert.equal(requests.at(-1).init.cache, 'no-store');
  assert.equal(requests.length, 7, 'each caller request reached fetch');
});

test('a failed route reports its status', async () => {
  const { transport } = load(() => reply(409, '{"error":"busy"}'));
  const answer = await transport.request('auto_sleep', {});
  assert.equal(answer.outcome, 'refused');
  assert.equal(answer.status, 409);
});

test('a hide and show during a poll in flight leaves one polling loop', async () => {
  let release;
  const { transport, timers, document, listeners } = load(() => new Promise((resolve) => { release = () => resolve(reply(200, '{"v":1}')); }));
  transport.subscribe('perf', () => {});
  const early = release;
  document.hidden = true; listeners.visibilitychange();
  document.hidden = false; listeners.visibilitychange();
  assert.notEqual(release, early, 'the show started its own poll');
  release();          // the poll the show started
  early();            // the poll from before the hide answers late
  await settle(); await settle();
  assert.equal(timers.filter((timer) => timer.ms === 2000 && !timer.cleared).length, 1);
});
