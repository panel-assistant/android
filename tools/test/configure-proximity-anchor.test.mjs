// Panel Assistant's Repair links to /configure#cfg-proximity-learning. At page load that card must flash, be
// scrolled into view and offer its setup button, in both engines, at phone and desktop width, in light and dark.
// An unknown anchor must stay inert: no error, no flash, page at the top.
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
const scripts = ['configure-state', 'configure-view', 'configure-help', 'configure-controls', 'configure-brightness', 'configure-auto-sleep', 'configure-cards', 'configure-render', 'configure', 'proximity-learning'];

function page() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css">${i18nBridge()}</head><body>
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <button id="tab-basic"></button><button id="tab-adv"></button>
    <div id="cfg-status" class="muted"></div><div id="cfg-groups" class="cards"></div>
    <div id="proximity-learning-mount"></div><div id="cfg-msg" class="sr-only" role="status" aria-live="polite" aria-atomic="true"></div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    ${scripts.map((name) => `<script src="/${name}.js"></script>`).join('')}
  </body></html>`;
}

// Tall groups before and after the card (it sits before Logging), so it sits below the first screen and can centre.
function schema() {
  const fields = [];
  for (let g = 0; g < 8; g++) for (let f = 0; f < 8; f++) fields.push({
    key: `g${g}_f${f}`, label: `Setting ${g}.${f}`, group: g === 7 ? 'Logging' : `Group ${g}`, type: 'BOOL', tier: 'BASIC', available: true,
    help: 'A deliberately long help text that wraps over several lines on a narrow panel so the card is tall. '.repeat(3),
  });
  return fields;
}

async function harness() {
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const send = (body, type = 'application/json') => { response.setHeader('content-type', type); response.end(body); };
    if (path === '/' || path === '/configure') return send(page(), 'text/html');
    if (scripts.some((name) => path === `/${name}.js`)) return send(await readFile(join(root, path.slice(1)), 'utf8'), 'application/javascript');
    if (path === '/info.css') return send(await readFile(join(root, 'info.css'), 'utf8'), 'text/css');
    if (path === '/api/v1/config/schema') return send(JSON.stringify(schema()));
    if (path === '/api/v1/config' || path === '/api/v1/config/values') return send(JSON.stringify({ settings: {}, ha_expose: {}, ha_auth: { configured: false } }));
    if (path === '/api/v1/apps') return send(JSON.stringify({ apps: [] }));
    if (path === '/api/v1/config/home-dashboards') return send(JSON.stringify({ queried: true, items: [], default: { explicit: false, path: '' } }));
    if (path === '/api/v1/proximity') return send(JSON.stringify({ present: true, phase: 'calibration_required', canCalibrate: true, sessionActive: false }));
    if (path === '/api/v1/radio') return send(JSON.stringify({ present: false }));
    if (path === '/api/v1/config/discovery') return send('{}');
    response.statusCode = 404; response.end('not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, url: `http://127.0.0.1:${server.address().port}` };
}

const catalogue = JSON.parse(await readFile(join(root, 'i18n', 'en.json'), 'utf8'));
const startText = catalogue.strings['configure.proximity.setup.start'].text;

const engines = [
  { name: 'chromium', type: chromium, launch: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'webkit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
];
const viewports = [{ name: 'phone', width: 390, height: 844 }, { name: 'desktop', width: 1280, height: 800 }];
const ID = 'cfg-proximity-learning';

async function open(engine, t, viewport, colorScheme, hash) {
  const h = await harness();
  const browser = await engine.type.launch(engine.launch);
  t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
  const p = await browser.newPage({ viewport: { width: viewport.width, height: viewport.height }, colorScheme });
  const errors = [];
  p.on('pageerror', (e) => errors.push(e.message));
  // Record when the flash class arrives and leaves, so the test reads the flash's length instead of racing its end.
  await p.addInitScript((id) => {
    window.__flash = {};
    new MutationObserver(() => {
      const on = document.getElementById(id)?.classList.contains('flash');
      if (on && !window.__flash.on) window.__flash.on = performance.now();
      if (!on && window.__flash.on && !window.__flash.off) window.__flash.off = performance.now();
    }).observe(document, { subtree: true, attributes: true, attributeFilter: ['class'], childList: true });
  }, ID);
  await p.goto(`${h.url}/configure#${hash}`);
  await p.locator('#cfg-g0_f0').waitFor({ state: 'attached' });
  return { p, errors };
}

for (const engine of engines) for (const vp of viewports) for (const scheme of ['light', 'dark']) {
  (engine.available ? test : test.skip)(`${engine.name} ${vp.name} ${scheme}: /configure#cfg-proximity-learning flashes and shows the setup card`, async (t) => {
    const { p, errors } = await open(engine, t, vp, scheme, ID);
    await p.waitForFunction((id) => document.getElementById(id)?.classList.contains('flash'), ID, { timeout: 5000 });
    await p.waitForTimeout(1500); // settle: the jump repeats for 1400 ms after each render
    const box = await p.evaluate((id) => {
      const el = document.getElementById(id), r = el.getBoundingClientRect();
      const button = [...el.querySelectorAll('button')].find((b) => !b.hidden);
      return { top: r.top, bottom: r.bottom, left: r.left, right: r.right, scrollY: window.scrollY, scrollW: document.documentElement.scrollWidth,
        text: button && button.textContent, disabled: button && button.disabled };
    }, ID);
    assert.ok(box.scrollY > 0, `the page must scroll to the card: ${JSON.stringify(box)}`);
    assert.ok(box.top >= 0 && box.bottom <= vp.height, `card must end in view: ${JSON.stringify(box)}`);
    assert.ok(box.left >= 0 && box.right <= vp.width && box.scrollW <= vp.width, `card must not overflow horizontally: ${JSON.stringify(box)}`);
    assert.equal(box.text, startText);
    assert.equal(box.disabled, false, 'a panel that needs calibration offers an enabled setup button');
    await p.waitForFunction(() => window.__flash.off, null, { timeout: 3000 });
    const flash = await p.evaluate(() => window.__flash.off - window.__flash.on);
    assert.ok(flash >= 1500, `the card must flash for about 1.8 s, not ${Math.round(flash)} ms`);
    assert.deepEqual(errors, []);
  });
}

for (const engine of engines) {
  (engine.available ? test : test.skip)(`${engine.name}: an unknown anchor loads quietly at the top`, async (t) => {
    const { p, errors } = await open(engine, t, viewports[0], 'light', 'cfg-not-a-card');
    await p.waitForTimeout(2200);
    const state = await p.evaluate(() => ({ scrollY: window.scrollY, flashing: document.querySelectorAll('.flash').length }));
    assert.deepEqual(state, { scrollY: 0, flashing: 0 });
    assert.deepEqual(errors, []);
  });
}
