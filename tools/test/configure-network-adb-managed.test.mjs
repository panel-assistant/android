// The Network ADB and Keep ADB off rows explain the state of ADB and never block the owner. The real Configure
// scripts read a projection of each state: Keep ADB off holding ADB (the switch still works and says what one
// press does), Hardened mode holding it (both rows say so and cannot change), ADB the firmware opened (the row
// names it and points at Keep ADB off), and nothing held (an ordinary switch, with its pending line).
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
  { key: 'adb_keep_off', label: 'Keep ADB off', group: 'System', type: 'BOOL', tier: 'BASIC', available: true,
    summary: 'More secure. Stops ADB, which Panel Assistant does not need on this panel.' },
];

async function harness(projection) {
  const state = {
    settings: { network_adb: false, adb_keep_off: false, ...(projection.settings || {}) },
    ha_expose: {}, ha_auth: { configured: false }, renderer_pending: [],
    apply_pending: projection.applyPending || {}, apply_stalled: [],
    network_adb_managed: projection.managed === true,
    network_adb_owner_switch: projection.ownerSwitch ?? null,
    network_adb_external: projection.external === true,
    hardened_mode: projection.hardened === true,
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
      return send({ ok: true });
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

async function open(engine, t, projection) {
  const h = await harness(projection);
  const browser = await engine.type.launch(engine.launch);
  t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
  const p = await browser.newPage({ viewport: { width: 390, height: 844 } });
  await p.goto(h.url);
  await p.locator('#cfg-network_adb [role=switch]').waitFor();
  // Let the half-second values follow-up arrive, so a later render cannot change what is read.
  await p.waitForTimeout(800);
  return { ...h, p };
}

for (const engine of engines) {
  const engineTest = engine.available ? test : test.skip;

  engineTest(`${engine.name}: while Keep ADB off holds ADB the Network ADB switch still works and says what one press does`, async (t) => {
    const h = await open(engine, t, { managed: true, ownerSwitch: 'available', settings: { adb_keep_off: true },
      // A desired ON an older version queued while its lease held ADB.
      applyPending: {} });
    const row = h.p.locator('#cfg-network_adb');
    const toggle = row.locator('[role=switch]');
    assert.equal(await toggle.getAttribute('aria-disabled'), 'false', 'the switch is enabled');
    assert.equal(await toggle.getAttribute('aria-checked'), 'false', 'and reads off: ADB is held off');
    const text = await row.innerText();
    assert.match(text, /ADB is off because Keep ADB off is on\. Turning this on switches that option off and opens ADB\./);
    assert.doesNotMatch(text, /lost its Home Assistant connection|minutes|manages ADB/, 'no waiting time and no takeover');
    const described = (await toggle.getAttribute('aria-describedby') || '').split(/\s+/);
    assert.ok(described.includes('cfg-network_adb-managed'), 'the switch is described by the note');
    await toggle.click();
    await h.p.waitForTimeout(400);
    assert.equal(h.posts.length, 1, 'one press sends one request');
    assert.equal(h.posts[0].network_adb, 'true');
  });

  engineTest(`${engine.name}: in Hardened mode both rows say Hardened mode keeps ADB off and neither can be changed here`, async (t) => {
    const h = await open(engine, t, { managed: true, ownerSwitch: null, hardened: true,
      applyPending: { network_adb: 'true' } });
    const adb = h.p.locator('#cfg-network_adb');
    const adbToggle = adb.locator('[role=switch]');
    assert.equal(await adbToggle.getAttribute('aria-disabled'), 'true');
    assert.equal(await adbToggle.getAttribute('aria-checked'), 'false');
    assert.match(await adb.innerText(), /Hardened mode keeps ADB off\. Switch to Relaxed mode at the panel to turn on Network ADB\./);
    assert.doesNotMatch(await adb.innerText(), /hardware application is pending/, 'a queued ON never reads as pending');
    const keep = h.p.locator('#cfg-adb_keep_off');
    const keepToggle = keep.locator('[role=switch]');
    assert.equal(await keepToggle.getAttribute('aria-disabled'), 'true');
    assert.equal(await keepToggle.getAttribute('aria-checked'), 'true', 'Hardened mode keeps it on');
    assert.match(await keep.innerText(), /Hardened mode keeps ADB off\. Switch to Relaxed mode at the panel to change this\./);
    await adbToggle.click({ force: true });
    await keepToggle.click({ force: true });
    await adbToggle.press('Enter');
    await h.p.waitForTimeout(300);
    assert.deepEqual(h.posts, [], 'a press sends nothing');
  });

  engineTest(`${engine.name}: ADB the firmware opened is named under Network ADB, with the option that closes it`, async (t) => {
    const h = await open(engine, t, { external: true });
    const row = h.p.locator('#cfg-network_adb');
    const toggle = row.locator('[role=switch]');
    assert.equal(await toggle.getAttribute('aria-disabled'), 'false');
    assert.equal(await toggle.getAttribute('aria-checked'), 'false', 'the app keeps nothing open');
    assert.match(await row.innerText(), /ADB is open on this panel: its own system opened it\. Anyone on your network can use it without a password\. Keep ADB off below closes it\./);
    const keepToggle = h.p.locator('#cfg-adb_keep_off [role=switch]');
    assert.equal(await keepToggle.getAttribute('aria-disabled'), 'false');
    await keepToggle.click();
    await h.p.waitForTimeout(400);
    assert.equal(h.posts.length, 1);
    assert.equal(h.posts[0].adb_keep_off, 'true');
  });

  engineTest(`${engine.name}: Keep ADB off chosen before the panel has its session says when ADB closes`, async (t) => {
    const h = await open(engine, t, { settings: { adb_keep_off: true } });
    const keep = h.p.locator('#cfg-adb_keep_off');
    assert.equal(await keep.locator('[role=switch]').getAttribute('aria-checked'), 'true');
    assert.equal(await keep.locator('[role=switch]').getAttribute('aria-disabled'), 'false');
    assert.match(await keep.innerText(), /Keep ADB off is on\. ADB closes when the panel connects to Home Assistant\./);
    assert.doesNotMatch(await h.p.locator('#cfg-network_adb').innerText(), /Keep ADB off is on/);
  });

  engineTest(`${engine.name}: a lease an earlier version left is never blamed on Keep ADB off`, async (t) => {
    const h = await open(engine, t, { managed: true, ownerSwitch: 'available' });
    const row = h.p.locator('#cfg-network_adb');
    assert.equal(await row.locator('[role=switch]').getAttribute('aria-disabled'), 'false');
    assert.doesNotMatch(await row.innerText(), /Keep ADB off is on|Hardened mode/);
  });

  engineTest(`${engine.name}: with nothing held Network ADB stays an ordinary switch`, async (t) => {
    const h = await open(engine, t, { applyPending: { network_adb: 'true' } });
    const row = h.p.locator('#cfg-network_adb');
    const toggle = row.locator('[role=switch]');
    // The pending line renders after the switch; wait for it rather than read the row mid-render.
    await row.locator(`text=${PENDING_TEXT}`).waitFor({ timeout: 5000 });
    assert.equal(await toggle.getAttribute('aria-disabled'), 'false');
    assert.doesNotMatch(await row.innerText(), /Keep ADB off is on|Hardened mode|its own system opened it/);
    assert.match(await row.innerText(), /hardware application is pending/);
    assert.doesNotMatch(await h.p.locator('#cfg-adb_keep_off').innerText(), /Hardened mode/);
  });
}
