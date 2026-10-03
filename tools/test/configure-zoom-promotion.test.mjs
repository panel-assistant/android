// Renderer zoom on the Configure page, in both engines. The server promotes zoom (first in its group,
// basic tier, sizing help) only where display sizing is unavailable; this checks the page renders that
// schema with zoom leading the Built-in renderer card on the basic view, and leaves the privileged
// schema exactly as before: zoom last, advanced-only, with the Display Sizing recommendation.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';

const root = join(process.cwd(), '..', '..', 'app', 'src', 'main', 'assets');
const chrome = process.env.CHROME || '/usr/bin/chromium';
const SIZING_HELP = 'How you size the dashboard on this panel, which cannot change its display density. Lower it to fit more on screen; raise it to enlarge.';

function page() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css"></head><body>
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <button id="tab-basic"></button><button id="tab-adv"></button>
    <p id="cfg-msg"></p><p id="cfg-status"></p><div id="cfg-groups" class="cards"></div>
    <div id="proximity-learning-mount"></div><div id="savebar" hidden><button id="savebtn"></button></div>
    <div id="cfg-help" class="cfg-help" popover="manual"><div class="cfg-help-head"><b id="cfg-help-title"></b><button id="cfg-help-close" type="button">x</button></div><div id="cfg-help-body" class="cfg-help-body"></div><div class="cfg-help-foot"><a id="cfg-help-more"></a></div></div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    <script src="/configure-state.js"></script><script src="/configure-view.js"></script><script src="/configure-help.js"></script><script src="/configure-controls.js"></script><script src="/configure-brightness.js"></script><script src="/configure-auto-sleep.js"></script><script src="/configure-cards.js"></script><script src="/configure-render.js"></script><script src="/configure.js"></script><script src="/proximity-learning.js"></script>
  </body></html>`;
}

// Mirrors configSchemaJson: promotion moves zoom to the front of the Dashboard group and changes only
// its tier and help.
function schema(promoted) {
  const field = (key, label, extra = {}) => ({ key, label, group: 'Dashboard', type: 'BOOL', tier: 'BASIC', available: true, ...extra });
  const zoom = field('dashboard_zoom', 'Zoom (%)', {
    type: 'INT', min: 50, max: 300, step: 1,
    tier: promoted ? 'BASIC' : 'ADVANCED',
    help: promoted ? SIZING_HELP : 'Browser zoom.',
    displaySizingAvailable: !promoted,
  });
  const rest = [
    field('dashboard_fullscreen', 'Fullscreen'),
    field('dashboard_theme', 'Theme', { type: 'ENUM', options: ['auto'] }),
    field('camera_kbps', 'Bitrate (kbps)', { type: 'INT', min: 250, max: 8000, step: 250 }),
    field('camera_exposure', 'Exposure', { type: 'FLOAT', min: -2, max: 2, step: 0.5 }),
    { key: 'ui_language', label: 'Interface language', group: 'System', type: 'ENUM', tier: 'BASIC', available: true, options: ['auto', 'en', 'fr'] },
  ];
  return promoted ? [zoom, ...rest] : [...rest, zoom];
}

async function harness(promoted, onPost = () => ({ status: 200, body: JSON.stringify({ ok: true, status: 'saved', pending: [] }) })) {
  const posts = [];
  let documents = 0;
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const send = (body, type = 'application/json') => { response.setHeader('content-type', type); response.end(body); };
    if (path === '/') { documents++; return send(page(), 'text/html'); }
    if (['/configure-state.js', '/configure-view.js', '/configure-help.js', '/configure-controls.js', '/configure-brightness.js', '/configure-auto-sleep.js', '/configure-cards.js', '/configure-render.js', '/configure.js'].includes(path) || path === '/proximity-learning.js') return send(await readFile(join(root, path.slice(1)), 'utf8'), 'application/javascript');
    if (path === '/info.css') return send(await readFile(join(root, 'info.css'), 'utf8'), 'text/css');
    if (path === '/api/v1/config/schema') return send(JSON.stringify(schema(promoted)));
    if (path === '/api/v1/config' && request.method === 'POST') {
      let raw = '';
      for await (const chunk of request) raw += chunk;
      const form = new URLSearchParams(raw);
      posts.push(Object.fromEntries(form));
      const reply = onPost(form);
      response.statusCode = reply.status;
      return send(reply.body, reply.type);
    }
    if (path === '/api/v1/config') return send(JSON.stringify({ settings: { dashboard_zoom: 100, dashboard_package: 'builtin', camera_kbps: 2000, ui_language: 'auto' }, ha_expose: {}, ha_auth: { configured: false } }));
    if (path === '/api/v1/apps') return send(JSON.stringify({ apps: [] }));
    if (path === '/api/v1/config/home-dashboards') return send(JSON.stringify({ queried: true, items: [], default: { explicit: false, path: '' } }));
    if (['/api/v1/radio', '/api/v1/proximity'].includes(path)) return send(JSON.stringify({ present: false }));
    if (path === '/api/v1/config/discovery') return send('{}');
    response.statusCode = 404; response.end('not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, posts, documents: () => documents, url: `http://127.0.0.1:${server.address().port}` };
}

const engines = [
  { name: 'chromium', type: chromium, launch: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'webkit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
];

// Each row's full help is read from the help popover its info button opens.
async function card(pageHandle) {
  const rows = pageHandle.locator('[data-config-group="Built-in renderer"] .frow');
  await rows.first().waitFor();
  const found = await rows.evaluateAll((els) => els.filter((el) => el.id.startsWith('cfg-')).map((el) => ({
    id: el.id,
    overflow: el.scrollWidth > el.clientWidth + 1,
  })));
  for (const row of found) {
    const button = pageHandle.locator(`#${row.id} .info-btn`);
    row.help = '';
    if (!(await button.count())) continue;
    await button.click();
    row.help = await pageHandle.locator('#cfg-help-body').textContent();
    await pageHandle.keyboard.press('Escape');
  }
  return found;
}

for (const engine of engines) {
  const engineTest = engine.available ? test : test.skip;

  engineTest(`${engine.name}: without display sizing zoom leads the renderer card on the basic view`, async (t) => {
    const h = await harness(true);
    const browser = await engine.type.launch(engine.launch);
    t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
    for (const width of [360, 1024]) {
      const p = await browser.newPage({ viewport: { width, height: 800 } });
      await p.goto(h.url);
      await card(p);
      await p.evaluate(() => window.cfgTab(false));
      const rows = await card(p);
      assert.equal(rows[0].id, 'cfg-dashboard_zoom', `${width}px: zoom must lead the card`);
      assert.equal(rows[0].help, SIZING_HELP);
      assert.deepEqual(rows.filter((row) => row.overflow), [], `${width}px: no row may overflow`);
      await p.close();
    }
  });

  engineTest(`${engine.name}: with display sizing the form is as before`, async (t) => {
    const h = await harness(false);
    const browser = await engine.type.launch(engine.launch);
    t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
    const p = await browser.newPage({ viewport: { width: 1024, height: 800 } });
    await p.goto(h.url);
    await card(p);
    await p.evaluate(() => window.cfgTab(false));
    assert.equal((await card(p)).some((row) => row.id === 'cfg-dashboard_zoom'), false, 'zoom stays advanced-only');
    await p.evaluate(() => window.cfgTab(true));
    const rows = await card(p);
    assert.equal(rows.at(-1).id, 'cfg-dashboard_zoom');
    assert.match(rows.at(-1).help, /^Browser zoom\. Recommend use Display Sizing for better results$/);
  });
}

// Saving through the page, as a person would: type a value, press Save, read the message line.
async function save(p, key, value) {
  await p.evaluate(() => window.cfgTab(true));
  const input = p.locator(`#cfg-${key} input`);
  await input.fill(String(value));
  await p.evaluate(() => window.cfgSave());
}

for (const engine of engines) {
  const engineTest = engine.available ? test : test.skip;

  engineTest(`${engine.name}: a value between a setting's arrow steps saves; an out-of-range value does not`, async (t) => {
    const h = await harness(true);
    const browser = await engine.type.launch(engine.launch);
    t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
    const p = await browser.newPage({ viewport: { width: 1024, height: 800 } });
    await p.goto(h.url);
    await card(p);
    await save(p, 'camera_kbps', 9000);
    assert.equal(await p.locator('#cfg-msg').textContent(), 'Bitrate (kbps) must be a whole number between 250 and 8000.');
    assert.deepEqual(h.posts, []);
    await save(p, 'camera_kbps', 1100);
    await p.waitForFunction(() => /Saved/.test(document.getElementById('cfg-msg').textContent));
    assert.deepEqual(h.posts, [{ camera_kbps: '1100' }]);
  });

  for (const numeric of [
    { key: 'dashboard_zoom', value: 96, refused: [49, 301], message: 'Zoom (%) must be a whole number between 50 and 300.' },
    { key: 'camera_exposure', value: 0.3, refused: [-2.1, 2.1], message: 'Exposure must be between -2 and 2.' },
  ]) {
    engineTest(`${engine.name}: ${numeric.key} names its range on refusal and saves an in-range value`, async (t) => {
      const h = await harness(true);
      const browser = await engine.type.launch(engine.launch);
      t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
      const p = await browser.newPage({ viewport: { width: 1024, height: 800 } });
      await p.goto(h.url);
      await card(p);
      for (const value of numeric.refused) {
        await save(p, numeric.key, value);
        assert.equal(await p.locator('#cfg-msg').textContent(), numeric.message);
        assert.deepEqual(h.posts, [], 'a range refusal must not reach the server');
      }
      await save(p, numeric.key, numeric.value);
      await p.waitForFunction(() => /Saved/.test(document.getElementById('cfg-msg').textContent));
      assert.deepEqual(h.posts, [{ [numeric.key]: String(numeric.value) }]);
    });
  }

  engineTest(`${engine.name}: a fraction in a whole-number setting is rounded, not refused`, async (t) => {
    const h = await harness(true);
    const browser = await engine.type.launch(engine.launch);
    t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
    const p = await browser.newPage({ viewport: { width: 1024, height: 800 } });
    await p.goto(h.url);
    await card(p);
    await save(p, 'dashboard_zoom', 96.5);
    await p.waitForFunction(() => /Saved/.test(document.getElementById('cfg-msg').textContent));
    assert.deepEqual(h.posts, [{ dashboard_zoom: '97' }]);
  });

  for (const refusal of [
    { name: 'plain-text 400', status: 400, type: 'text/plain', body: 'dashboard_zoom: expected an integer\n', shown: 'Not saved: dashboard_zoom: expected an integer' },
    { name: 'JSON 409', status: 409, type: 'application/json', body: JSON.stringify({ ok: false, error: 'ha-sign-in-required', message: 'Connect Home Assistant with Browser sign-in before selecting the Built-in renderer.' }), shown: 'Not saved: Connect Home Assistant with Browser sign-in before selecting the Built-in renderer.' },
    { name: 'partial-save JSON 500', status: 500, type: 'application/json', body: JSON.stringify({ status: 'saved-partial', applied: [], message: 'Some settings were saved, but dashboard_zoom could not be durably accepted.' }), shown: 'Not saved: Some settings were saved, but dashboard_zoom could not be durably accepted.' },
    { name: 'JSON code without a message', status: 403, type: 'application/json', body: JSON.stringify({ ok: false, error: 'embed-proof-rejected', reason: 'replayed' }), shown: 'Not saved: embed-proof-rejected (replayed)' },
  ]) {
    engineTest(`${engine.name}: a ${refusal.name} refusal shows the panel's reason`, async (t) => {
      const h = await harness(true, () => refusal);
      const browser = await engine.type.launch(engine.launch);
      t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
      const p = await browser.newPage({ viewport: { width: 1024, height: 800 } });
      await p.goto(h.url);
      await card(p);
      await save(p, 'dashboard_zoom', 96);
      await p.waitForFunction(() => /^Not saved/.test(document.getElementById('cfg-msg').textContent));
      assert.equal(await p.locator('#cfg-msg').textContent(), refusal.shown);
    });
  }
}

for (const engine of engines) {
  const engineTest = engine.available ? test : test.skip;
  engineTest(`${engine.name}: choosing an interface language saves it and reloads the page to render in it`, async (t) => {
    const h = await harness(true);
    const browser = await engine.type.launch(engine.launch);
    t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
    const p = await browser.newPage({ viewport: { width: 1024, height: 800 } });
    await p.goto(h.url);
    await p.locator('#cfg-ui_language select').waitFor();
    await p.locator('#cfg-ui_language select').selectOption('fr');
    await p.evaluate(() => window.cfgSave());
    await p.waitForLoadState('load');
    for (let i = 0; i < 50 && h.documents() < 2; i++) await new Promise((r) => setTimeout(r, 100));
    assert.deepEqual(h.posts, [{ ui_language: 'fr' }]);
    assert.equal(h.documents(), 2, 'the page is recreated once so the server renders the saved language');
  });
}
