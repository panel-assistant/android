// The Configure Voice card follows the panel's microphone mute without a reload: a mute made while the
// page was hidden shows as soon as it is visible again, and failed reads do not stop it re-reading.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';

const root = join(process.cwd(), '..', '..', 'app', 'src', 'main', 'assets');
const chrome = process.env.CHROME || '/usr/bin/chromium';
const NOTICE = 'Microphone muted. The panel hears nothing until its microphone is unmuted.';
const SCRIPTS = ['/configure-state.js', '/configure-view.js', '/configure-help.js', '/configure-controls.js', '/configure-brightness.js', '/configure-auto-sleep.js', '/configure-cards.js', '/configure-render.js', '/configure.js', '/proximity-learning.js'];

const page = `<!doctype html><html lang="en"><head><meta charset="utf-8"><link rel="stylesheet" href="/info.css"></head><body>
  <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
  <button id="tab-basic"></button><button id="tab-adv"></button>
  <p id="cfg-msg"></p><p id="cfg-status"></p><div id="cfg-groups" class="cards"></div>
  <div id="proximity-learning-mount"></div><div id="savebar" hidden><button id="savebtn"></button></div>
  <div id="cfg-help" class="cfg-help" popover="manual"><div class="cfg-help-head"><b id="cfg-help-title"></b><button id="cfg-help-close" type="button">x</button></div><div id="cfg-help-body" class="cfg-help-body"></div><div class="cfg-help-foot"><a id="cfg-help-more"></a></div></div>
  <script>window.CardColumnAlignment={attach:()=>()=>{}};window.HaI18n={t:function(k,f){return f;}};</script>
  ${SCRIPTS.map((s) => `<script src="${s}"></script>`).join('')}
</body></html>`;

/** A panel whose microphone the test mutes, and whose microphone route the test can make fail. */
async function panel() {
  const state = { muted: false, failing: 0, reads: 0 };
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const send = (body, type = 'application/json') => { response.setHeader('content-type', type); response.end(body); };
    if (path === '/') return send(page, 'text/html');
    if (SCRIPTS.includes(path)) return send(await readFile(join(root, path.slice(1)), 'utf8'), 'application/javascript');
    if (path === '/info.css') return send(await readFile(join(root, 'info.css'), 'utf8'), 'text/css');
    if (path === '/api/v1/voice/microphone') {
      state.reads += 1;
      if (state.failing > 0) { state.failing -= 1; response.statusCode = 503; return response.end('busy'); }
      return send(JSON.stringify({ presence: 'proven', check: 'not_run', detail: null, muted: state.muted }));
    }
    if (path === '/api/v1/config/schema') {
      return send(JSON.stringify([{ key: 'voice_enabled', type: 'BOOL', group: 'Voice', tier: 'BASIC', available: true, label: 'Voice assistant' }]));
    }
    if (path === '/api/v1/config') return send(JSON.stringify({ settings: { voice_enabled: 'false', dashboard_package: 'builtin' }, ha_expose: {}, ha_auth: { configured: false } }));
    if (path === '/api/v1/apps') return send(JSON.stringify({ apps: [] }));
    if (path === '/api/v1/config/home-dashboards') return send(JSON.stringify({ queried: true, items: [], default: { explicit: false, path: '' } }));
    if (['/api/v1/radio', '/api/v1/proximity'].includes(path)) return send(JSON.stringify({ present: false }));
    if (path === '/api/v1/config/discovery') return send('{}');
    response.statusCode = 404; response.end('not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { state, server, url: `http://127.0.0.1:${server.address().port}` };
}

/** Make the page report hidden or visible, as a panel's screen or a browser tab switch would. */
async function setHidden(p, hidden) {
  await p.evaluate((h) => {
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => h });
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => (h ? 'hidden' : 'visible') });
    document.dispatchEvent(new Event('visibilitychange'));
  }, hidden);
}

const notice = (p) => p.evaluate((text) => [...document.querySelectorAll('[role="status"]')].some((n) => n.textContent === text), NOTICE);

async function eventually(p, want, ms, what) {
  const until = Date.now() + ms;
  while (Date.now() < until) {
    if (await notice(p) === want) return;
    await p.waitForTimeout(200);
  }
  assert.equal(await notice(p), want, what);
}

const engines = [
  { name: 'chromium', type: chromium, launch: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'webkit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
];

for (const engine of engines) {
  const engineTest = engine.available ? test : test.skip;

  engineTest(`${engine.name}: a mute made while the page was hidden shows when it is visible again`, async (t) => {
    const browser = await engine.type.launch(engine.launch);
    const h = await panel();
    t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
    const p = await browser.newPage({ viewport: { width: 480, height: 900 } });
    await p.goto(h.url);
    await p.locator('#cfg-voice_enabled').waitFor();
    await eventually(p, false, 2000, 'no notice while the microphone is on');
    await setHidden(p, true);
    await p.waitForTimeout(4000); // past any read already scheduled before the page was hidden
    h.state.muted = true;
    await setHidden(p, false);
    await eventually(p, true, 2000, 'the mute shows as soon as the page is visible again');
    h.state.muted = false;
    await eventually(p, false, 5000, 'unmuting clears it while the page stays visible');
  });

  engineTest(`${engine.name}: failed reads do not stop the Voice card following the mute`, async (t) => {
    const browser = await engine.type.launch(engine.launch);
    const h = await panel();
    t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
    const p = await browser.newPage({ viewport: { width: 480, height: 900 } });
    await p.goto(h.url);
    await p.locator('#cfg-voice_enabled').waitFor();
    await eventually(p, false, 2000, 'no notice while the microphone is on');
    const before = h.state.reads;
    h.state.failing = 2;
    await p.waitForTimeout(7000); // both failing reads are answered
    assert.ok(h.state.reads >= before + 2, `the card kept reading through failures (${h.state.reads - before} reads)`);
    h.state.muted = true;
    await eventually(p, true, 5000, 'the mute shows once reads succeed again');
  });
}
