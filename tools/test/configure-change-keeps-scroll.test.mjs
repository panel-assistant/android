// android #211: changing a setting on the Configure page must leave the page where it was. The render after a
// change rebuilds the cards. When the changed control still had focus (as a select does on a panel once its
// picker closes), removing it made the browser lay out the emptied grid at once and clamp the scroll to the top,
// throwing the person back to the start of a long page. Both engines, at a panel's width.
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
const SCRIPTS = ['card-size-memory.js', 'configure-state.js', 'configure-view.js', 'configure-help.js', 'configure-controls.js', 'configure-brightness.js',
  'configure-auto-sleep.js', 'configure-cards.js', 'configure-render.js', 'configure.js'];

function page() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css">${i18nBridge()}</head><body>
    <span id="hardened-approval-conditional-description"></span>
    <div id="cfg-status" class="muted"></div><div id="cfg-msg" class="sr-only" role="status" aria-live="polite" aria-atomic="true"></div>
    <div id="cfg-all-cards"><div id="cfg-groups" class="cards"></div></div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    ${SCRIPTS.map((name) => `<script src="/${name}"></script>`).join('')}
  </body></html>`;
}

// Tall cards before and after the changed one, so it sits well down a long page.
const SCHEMA = [];
const settings = {};
for (let g = 0; g < 8; g++) for (let f = 0; f < 6; f++) {
  const key = `g${g}_f${f}`;
  SCHEMA.push({ key, label: `Setting ${g}.${f}`, group: g === 4 ? 'Behaviour' : `Group ${g}`, type: 'BOOL', tier: 'BASIC', available: true,
    help: 'A long help text that wraps over several lines on a narrow panel so the card is tall. '.repeat(2) });
  settings[key] = false;
}
SCHEMA.push({ key: 'navbar_mode', label: 'Navbar mode', group: 'Behaviour', type: 'ENUM', tier: 'BASIC', available: true,
  options: ['Off', 'Always on', 'Swipe reveal'] });
settings.navbar_mode = 'Swipe reveal';

async function harness() {
  const state = { settings, ha_expose: {}, ha_auth: { configured: false }, apply_pending: {}, apply_stalled: [], renderer_pending: [] };
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
      const form = new URLSearchParams(raw);
      posts.push(Object.fromEntries(form));
      for (const [key, value] of form) state.settings[key] = typeof state.settings[key] === 'boolean' ? value === 'true' : value;
      return send({ ok: true, status: 'saved', applied: [...form.keys()], pending: [], rejected: [], message: '', ...state });
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

for (const engine of engines) {
  (engine.available ? test : test.skip)(`${engine.name}: changing a setting far down the page keeps the page where it was`, async (t) => {
    const h = await harness();
    const browser = await engine.type.launch(engine.launch);
    t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
    const p = await browser.newPage({ viewport: { width: 480, height: 376 } });
    await p.goto(h.url);
    const select = p.locator('#cfg-navbar_mode select');
    await select.waitFor();
    await p.waitForTimeout(1500);   // the first values update has arrived and rendered
    await p.evaluate(() => { const r = document.querySelector('#cfg-navbar_mode').getBoundingClientRect(); window.scrollBy(0, r.top - 150); });
    await p.waitForTimeout(300);
    const before = await p.evaluate(() => ({ y: window.scrollY, top: document.querySelector('#cfg-navbar_mode').getBoundingClientRect().top }));
    assert.ok(before.y > 1000, `the setting must start well down the page: ${JSON.stringify(before)}`);
    // Record how far the setting strays on screen at every scroll while the change settles, so a jump that is later
    // undone still fails. The scroll position itself may move: when cards above come back shorter, holding the
    // setting still means scrolling up by the same amount.
    await p.evaluate(() => {
      const top = () => document.querySelector('#cfg-navbar_mode')?.getBoundingClientRect().top;
      window.__start = top(); window.__strayed = 0;
      addEventListener('scroll', () => { const now = top(); if (now !== undefined) window.__strayed = Math.max(window.__strayed, Math.abs(now - window.__start)); });
    });

    // As on a panel, where the native picker closes and leaves the select focused while the change settles.
    await select.focus();
    await p.evaluate(() => {
      const s = document.querySelector('#cfg-navbar_mode select');
      s.value = 'Off';
      s.dispatchEvent(new Event('input', { bubbles: true }));
      s.dispatchEvent(new Event('change', { bubbles: true }));
    });
    const end = Date.now() + 5000;
    while (h.posts.length === 0 && Date.now() < end) await p.waitForTimeout(25);
    assert.deepEqual(h.posts, [{ navbar_mode: 'Off' }]);
    await p.waitForTimeout(2000);   // the save's reply and the next values update have rendered

    const after = await p.evaluate(() => ({ y: window.scrollY, strayed: window.__strayed,
      top: document.querySelector('#cfg-navbar_mode').getBoundingClientRect().top, value: document.querySelector('#cfg-navbar_mode select').value }));
    assert.equal(after.value, 'Off');
    assert.ok(Math.abs(after.top - before.top) <= 24, `the changed setting must stay where it was on screen: ${JSON.stringify({ before, after })}`);
    assert.ok(after.strayed <= 24, `the setting must never jump away while the change settles: ${JSON.stringify({ before, after })}`);
  });
}
