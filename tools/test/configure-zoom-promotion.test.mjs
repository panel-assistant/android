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
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    <script src="/configure.js"></script><script src="/proximity-learning.js"></script>
  </body></html>`;
}

// Mirrors configSchemaJson: promotion moves zoom to the front of the Dashboard group and changes only
// its tier and help.
function schema(promoted) {
  const field = (key, label, extra = {}) => ({ key, label, group: 'Dashboard', type: 'BOOL', tier: 'BASIC', available: true, ...extra });
  const zoom = field('dashboard_zoom', 'Zoom (%)', {
    type: 'INT', min: 50, max: 300, step: 10,
    tier: promoted ? 'BASIC' : 'ADVANCED',
    help: promoted ? SIZING_HELP : 'Browser zoom.',
    displaySizingAvailable: !promoted,
  });
  const rest = [
    field('dashboard_fullscreen', 'Fullscreen'),
    field('dashboard_theme', 'Theme', { type: 'ENUM', options: ['auto'] }),
  ];
  return promoted ? [zoom, ...rest] : [...rest, zoom];
}

async function harness(promoted) {
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const send = (body, type = 'application/json') => { response.setHeader('content-type', type); response.end(body); };
    if (path === '/') return send(page(), 'text/html');
    if (path === '/configure.js' || path === '/proximity-learning.js') return send(await readFile(join(root, path.slice(1)), 'utf8'), 'application/javascript');
    if (path === '/info.css') return send(await readFile(join(root, 'info.css'), 'utf8'), 'text/css');
    if (path === '/api/v1/config/schema') return send(JSON.stringify(schema(promoted)));
    if (path === '/api/v1/config') return send(JSON.stringify({ settings: { dashboard_zoom: 100, dashboard_package: 'builtin' }, ha_expose: {}, ha_auth: { configured: false } }));
    if (path === '/api/v1/apps') return send(JSON.stringify({ apps: [] }));
    if (path === '/api/v1/config/home-dashboards') return send(JSON.stringify({ queried: true, items: [], default: { explicit: false, path: '' } }));
    if (['/api/v1/radio', '/api/v1/proximity'].includes(path)) return send(JSON.stringify({ present: false }));
    if (path === '/api/v1/config/discovery') return send('{}');
    response.statusCode = 404; response.end('not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, url: `http://127.0.0.1:${server.address().port}` };
}

const engines = [
  { name: 'chromium', type: chromium, launch: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'webkit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
];

async function card(pageHandle) {
  const rows = pageHandle.locator('[data-config-group="Built-in renderer"] .frow');
  await rows.first().waitFor();
  return rows.evaluateAll((els) => els.filter((el) => el.id.startsWith('cfg-')).map((el) => ({
    id: el.id,
    help: el.querySelector('.flabel small')?.textContent || '',
    overflow: el.scrollWidth > el.clientWidth + 1,
  })));
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
