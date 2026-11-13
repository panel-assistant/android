// While Panel Assistant keeps network ADB off, the app cannot turn it on, so the Configure row must not offer
// an ON that can only wait. The real Configure scripts read a projection that says ADB is managed (with a
// desired ON left queued by an older version) and must show the switch off and disabled, say why, and never
// show the "pending" line. Without management the same row stays an ordinary switch.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';

const root = join(process.cwd(), '..', '..', 'app', 'src', 'main', 'assets');
const chrome = process.env.CHROME || '/usr/bin/chromium';
const SCRIPTS = ['configure-state.js', 'configure-view.js', 'configure-help.js', 'configure-controls.js', 'configure-brightness.js',
  'configure-auto-sleep.js', 'configure-cards.js', 'configure-render.js', 'configure.js'];
const PENDING_TEXT = 'Saved desired value; hardware application is pending.';

function page() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css">${i18nBridge()}</head><body>
    <span id="hardened-approval-conditional-description"></span>
    <div id="cfg-status" class="muted"></div><div id="cfg-msg" class="sr-only" role="status" aria-live="polite" aria-atomic="true"></div>
    <div id="cfg-all-cards"><div id="cfg-groups" class="cards"></div></div>
    <div id="cfg-help" class="cfg-help" popover="manual"><div class="cfg-help-head"><b id="cfg-help-title"></b><button id="cfg-help-close" type="button">x</button></div><div id="cfg-help-body" class="cfg-help-body"></div><div class="cfg-help-foot"><a id="cfg-help-more"></a></div></div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    ${SCRIPTS.map((name) => `<script src="/${name}"></script>`).join('')}
  </body></html>`;
}

const SCHEMA = [
  { key: 'network_adb', label: 'Network ADB', group: 'System', type: 'BOOL', tier: 'BASIC', available: true,
    summary: 'Security risk. Keep ADB listening on port 5555 across boots.' },
];

async function harness(managed, ownerSwitch) {
  const state = {
    settings: { network_adb: false },
    ha_expose: {}, ha_auth: { configured: false }, renderer_pending: [],
    // A desired ON an older version queued while Panel Assistant already managed ADB. The panel's own
    // projection drops it while ADB is managed; the plain managed case keeps it to prove the page does too.
    apply_pending: ownerSwitch === undefined ? { network_adb: 'true' } : {}, apply_stalled: [],
    ...(managed === undefined ? {} : { network_adb_managed: managed }),
    ...(ownerSwitch === undefined ? {} : { network_adb_owner_switch: ownerSwitch, network_adb_owner_switch_after_min: 5 }),
  };
  const posts = [];
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const send = (body, type = 'application/json') => { response.setHeader('content-type', type); response.end(typeof body === 'string' ? body : JSON.stringify(body)); };
    if (path === '/') return send(page(), 'text/html');
    if (SCRIPTS.includes(path.slice(1))) return send(await readFile(join(root, path.slice(1)), 'utf8'), 'application/javascript');
    if (path === '/info.css') return send(await readFile(join(root, 'info.css'), 'utf8'), 'text/css');
    if (path === '/api/v1/config/schema') return send(SCHEMA);
    if (path === '/api/v1/config' && request.method === 'POST') {
      let raw = '';
      for await (const chunk of request) raw += chunk;
      posts.push(Object.fromEntries(new URLSearchParams(raw)));
      // The panel lets the owner's switch through once it has been without its session long enough.
      if (ownerSwitch === 'available') return send({ ok: true });
      response.statusCode = 409;
      return send({ ok: false, error: 'network-adb-managed-by-panel-assistant', message: 'Panel Assistant manages network ADB on this panel.' });
    }
    if (path === '/api/v1/config' || path === '/api/v1/config/values') return send(state);
    if (path === '/api/v1/apps') return send({ apps: [] });
    if (path === '/api/v1/config/home-dashboards') return send({ queried: true, items: [], default: { explicit: false, path: '' } });
    if (path === '/api/v1/radio') return send({ present: false });
    if (path === '/api/v1/config/discovery') return send({});
    response.statusCode = 404; response.end('not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, posts, url: `http://127.0.0.1:${server.address().port}` };
}

const engines = [
  { name: 'chromium', type: chromium, launch: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'webkit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
];

async function open(engine, t, managed, ownerSwitch) {
  const h = await harness(managed, ownerSwitch);
  const browser = await engine.type.launch(engine.launch);
  t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
  const p = await browser.newPage({ viewport: { width: 390, height: 844 } });
  await p.goto(h.url);
  await p.locator('#cfg-network_adb [role=switch]').waitFor();
  return { ...h, p };
}

for (const engine of engines) {
  const engineTest = engine.available ? test : test.skip;

  engineTest(`${engine.name}: a managed Network ADB row reads off, is disabled, says why and never says pending`, async (t) => {
    const h = await open(engine, t, true);
    const row = h.p.locator('#cfg-network_adb');
    const toggle = row.locator('[role=switch]');
    // Let the half-second values follow-up arrive, so a later render cannot bring the pending line back.
    await h.p.waitForTimeout(800);
    assert.equal(await toggle.getAttribute('aria-checked'), 'false', 'the switch reads off');
    assert.equal(await toggle.getAttribute('aria-disabled'), 'true', 'the switch is disabled');
    const text = await row.innerText();
    assert.match(text, /Panel Assistant manages ADB on this panel/, 'the row names who manages ADB');
    assert.match(text, /Removing the panel from Panel Assistant turns ADB back on/, 'the row says how ADB comes back');
    assert.doesNotMatch(text, /hardware application is pending/, 'the row never says the ON is pending');
    const described = (await toggle.getAttribute('aria-describedby') || '').split(/\s+/);
    assert.ok(described.includes('cfg-network_adb-managed'), 'the switch is described by the managed line');
    await toggle.click({ force: true });
    await toggle.press('Enter');
    await h.p.waitForTimeout(300);
    assert.deepEqual(h.posts, [], 'a press sends nothing');
    assert.equal(await toggle.getAttribute('aria-checked'), 'false', 'a press leaves it off');
    assert.equal(await row.locator(`text=${PENDING_TEXT}`).count(), 0);
  });

  engineTest(`${engine.name}: a managed row on a panel that has just lost its connection stays disabled and says when the switch works`, async (t) => {
    const h = await open(engine, t, true, 'waiting');
    const row = h.p.locator('#cfg-network_adb');
    const toggle = row.locator('[role=switch]');
    await h.p.waitForTimeout(800);
    assert.equal(await toggle.getAttribute('aria-disabled'), 'true', 'the switch is still disabled');
    const text = await row.innerText();
    assert.match(text, /lost its Home Assistant connection; this switch works after 5 minutes without it/);
    assert.doesNotMatch(text, /Removing the panel from Panel Assistant/, 'removal needs the connection this panel has lost');
    await toggle.click({ force: true });
    await h.p.waitForTimeout(300);
    assert.deepEqual(h.posts, [], 'a press sends nothing yet');
  });

  engineTest(`${engine.name}: once the panel has been without its connection long enough the switch works and says what it does`, async (t) => {
    const h = await open(engine, t, true, 'available');
    const row = h.p.locator('#cfg-network_adb');
    const toggle = row.locator('[role=switch]');
    await h.p.waitForTimeout(800);
    assert.equal(await toggle.getAttribute('aria-disabled'), 'false', 'the switch is enabled');
    assert.equal(await toggle.getAttribute('aria-checked'), 'false', 'and reads off: ADB is still closed');
    const text = await row.innerText();
    assert.match(text, /Turning this on takes ADB back from Panel Assistant until the panel connects again/);
    assert.doesNotMatch(text, /keeps it off/);
    await toggle.click();
    await h.p.waitForTimeout(400);
    assert.equal(h.posts.length, 1, 'one press sends one request');
    assert.equal(h.posts[0].network_adb, 'true');
  });

  engineTest(`${engine.name}: an unmanaged Network ADB row stays an ordinary switch`, async (t) => {
    const h = await open(engine, t, false);
    const row = h.p.locator('#cfg-network_adb');
    const toggle = row.locator('[role=switch]');
    // The pending line renders after the switch; wait for it rather than read the row mid-render.
    await row.locator(`text=${PENDING_TEXT}`).waitFor({ timeout: 5000 });
    assert.equal(await toggle.getAttribute('aria-disabled'), 'false');
    assert.doesNotMatch(await row.innerText(), /Panel Assistant manages ADB/);
    // Without management a queued ON is a real pending value and says so.
    assert.match(await row.innerText(), /hardware application is pending/);
  });
}
