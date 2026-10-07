// A link straight to a setting (/configure#cfg-<key>) must land on that setting in both engines. In the
// single-column regime off-screen cards lay out at a placeholder height; a jump computed on that geometry
// drifts once the cards above render at their real height, which Chromium's scroll anchoring hides and
// WebKit does not.
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
const TARGET = 'g11_f11';

function page() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css">${i18nBridge()}</head><body>
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <button id="tab-basic"></button><button id="tab-adv"></button>
    <p id="cfg-msg"></p><p id="cfg-status"></p><div id="cfg-groups" class="cards"></div>
    <div id="proximity-learning-mount"></div><div id="savebar" hidden><button id="savebtn"></button></div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    <script src="/configure-state.js"></script><script src="/configure-view.js"></script><script src="/configure-help.js"></script><script src="/configure-controls.js"></script><script src="/configure-brightness.js"></script><script src="/configure-auto-sleep.js"></script><script src="/configure-cards.js"></script><script src="/configure-render.js"></script><script src="/configure.js"></script><script src="/proximity-learning.js"></script>
  </body></html>`;
}

// Twelve groups of twelve settings with long help, so every card is far taller than the 300px placeholder.
function schema() {
  const fields = [];
  for (let g = 0; g < 12; g++) for (let f = 0; f < 12; f++) fields.push({
    key: `g${g}_f${f}`, label: `Setting ${g}.${f}`, group: `Group ${g}`, type: 'BOOL', tier: 'BASIC', available: true,
    help: 'A deliberately long help text that wraps over several lines on a narrow panel so the card is tall. '.repeat(3),
  });
  return fields;
}

async function harness() {
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const send = (body, type = 'application/json') => { response.setHeader('content-type', type); response.end(body); };
    if (path === '/') return send(page(), 'text/html');
    if (['/configure-state.js', '/configure-view.js', '/configure-help.js', '/configure-controls.js', '/configure-brightness.js', '/configure-auto-sleep.js', '/configure-cards.js', '/configure-render.js', '/configure.js'].includes(path) || path === '/proximity-learning.js') return send(await readFile(join(root, path.slice(1)), 'utf8'), 'application/javascript');
    if (path === '/info.css') return send(await readFile(join(root, 'info.css'), 'utf8'), 'text/css');
    if (path === '/api/v1/config/schema') return send(JSON.stringify(schema()));
    if (path === '/api/v1/config') return send(JSON.stringify({ settings: {}, ha_expose: {}, ha_auth: { configured: false } }));
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

for (const engine of engines) {
  (engine.available ? test : test.skip)(`${engine.name}: a deep link at 480x480 lands on a setting far down the page`, async (t) => {
    const h = await harness();
    const browser = await engine.type.launch(engine.launch);
    t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
    const p = await browser.newPage({ viewport: { width: 480, height: 480 } });
    await p.goto(`${h.url}/#cfg-${TARGET}`);
    await p.locator(`#cfg-${TARGET}`).waitFor();
    await p.waitForTimeout(2500); // past the 1400 ms exact-layout release
    const box = await p.evaluate((id) => {
      const r = document.getElementById(id).getBoundingClientRect();
      return { top: r.top, bottom: r.bottom, scrollY: window.scrollY, height: document.documentElement.scrollHeight };
    }, `cfg-${TARGET}`);
    assert.ok(box.scrollY > 2000, `the page must actually scroll: ${JSON.stringify(box)}`);
    assert.ok(box.top >= 0 && box.bottom <= 480, `setting must end in view: ${JSON.stringify(box)}`);
  });
}
