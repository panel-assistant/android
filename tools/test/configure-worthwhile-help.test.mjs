import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';

const root = process.env.CONFIGURE_ASSET_ROOT || join(process.cwd(), '..', '..', 'app', 'src', 'main', 'assets');
const chrome = process.env.CHROME || '/usr/bin/chromium';

function page() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css"></head><body><div class="wrap">
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <div id="cfg-tools" class="cfg-tools" data-app-version="9.9.9-rc1"><input id="cfg-filter" class="cfg-filter" type="search"><div class="cfg-seg" role="radiogroup"><label><input type="radio" name="cfg-tier" id="tier-basic" value="basic" checked>Basic</label><label><input type="radio" name="cfg-tier" id="tier-adv" value="advanced">Advanced</label></div><label class="cfg-desc-switch"><input type="checkbox" id="cfg-desc" checked><span class="cfg-desc-track"></span>Descriptions</label><span id="cfg-count" class="muted cfg-count"></span></div>
    <p id="cfg-msg"></p><p id="cfg-status"></p>
    <div id="cfg-groups" class="cards" data-card-size-page="configure" data-card-size-epoch="1" data-card-size-restore="1"></div>
    <div id="proximity-learning-mount"></div><div id="savebar" class="savebar" hidden><button id="savebtn"></button></div>
    <div id="cfg-help" class="cfg-help" popover="manual" role="dialog"><div class="cfg-help-head"><b id="cfg-help-title"></b><button id="cfg-help-close" class="cfg-help-close" type="button">x</button></div><div id="cfg-help-body" class="cfg-help-body"></div><div class="cfg-help-foot"><a id="cfg-help-more">More on the website</a></div></div>
    </div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    <script src="/card-size-memory.js"></script><script src="/configure-state.js"></script><script src="/configure-view.js"></script><script src="/configure-help.js"></script><script src="/configure-controls.js"></script><script src="/configure-brightness.js"></script><script src="/configure-auto-sleep.js"></script><script src="/configure-cards.js"></script><script src="/configure-render.js"></script><script src="/configure.js"></script>
  </body></html>`;
}

const HELP = 'Retained **caveat** with `?fps=` and [details](https://example.org/settings).';
function field(key, help, flag, extra = {}) {
  return { key, label: key, group: 'Behaviour', tier: 'BASIC', type: 'BOOL', available: true,
    summary: `Summary ${key}`, help, shortDescriptionUsefulInPopover: flag, ...extra };
}
const SCHEMA = [
  field('touch_sound', '', false),
  field('watchdog_enabled', '', true, { summary: 'Relance **le tableau** <b>local</b>.', summaryLanguage: 'fr', helpLanguage: 'en' }),
  field('long_only', HELP, false, { helpLanguage: 'en', summaryLanguage: 'fr' }),
  field('both', HELP, true, { summary: 'Résumé **simple** <img src=x>', summaryLanguage: 'fr', helpLanguage: 'en' }),
  field('blank', '   ', true, { summary: '   ' }),
  field('missing_flag', '', undefined),
  field('dashboard_zoom', '', false, { displaySizingAvailable: true }),
  field('advanced', HELP, false, { tier: 'ADVANCED' }),
  field('test_secret', '', false, { type: 'PASSWORD', secret: true }),
];
async function harness() {
  const posts = [];
  const settings = Object.fromEntries(SCHEMA.map(f => [f.key, f.secret ? "" : "false"]));
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const send = (body, type = 'application/json') => { response.setHeader('content-type', type); response.end(body); };
    if (path === '/') return send(page(), 'text/html');
    if (['/configure-state.js', '/configure-view.js', '/configure-help.js', '/configure-controls.js', '/configure-brightness.js', '/configure-auto-sleep.js', '/configure-cards.js', '/configure-render.js', '/configure.js', '/card-size-memory.js'].includes(path)) return send(await readFile(join(root, path.slice(1)), 'utf8'), 'application/javascript');
    if (path === '/info.css') return send(await readFile(join(root, 'info.css'), 'utf8'), 'text/css');
    if (path === '/api/v1/config/schema') return send(JSON.stringify(SCHEMA));
    if (path === '/api/v1/config' && request.method === 'POST') {
      let body = '';
      for await (const chunk of request) body += chunk;
      const values = Object.fromEntries(new URLSearchParams(body));
      posts.push(values);
      for (const [key, value] of Object.entries(values)) if (key !== 'test_secret') settings[key] = value;
      return send(JSON.stringify({ status: 'saved', pending: [] }));
    }
    if (path === '/api/v1/config') return send(JSON.stringify({ settings, ha_expose: {}, ha_auth: { configured: false } }));
    if (path === '/api/v1/apps') return send(JSON.stringify({ apps: [] }));
    if (['/api/v1/radio', '/api/v1/proximity'].includes(path)) return send(JSON.stringify({ present: false }));
    if (path === '/api/v1/config/discovery' || path === '/api/v1/config/home-dashboards') return send('{}');
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
  test(`${engine.name}: summary truth table, mixed languages and live view state`, async t => {
    assert.equal(engine.available, true, `${engine.name} must run`);
    const h = await harness();
    const browser = await engine.type.launch(engine.launch);
    t.after(async () => { await browser.close(); await new Promise(resolve => h.server.close(resolve)); });
    const p = await browser.newPage({ viewport: { width: 480, height: 480 } });
    await p.goto(h.url);
    await p.locator('#cfg-touch_sound').waitFor();
    const icon = key => p.locator(`#cfg-${key} .info-btn`);
    const body = p.locator('#cfg-help-body');
    for (const key of ['touch_sound', 'watchdog_enabled', 'blank', 'missing_flag', 'dashboard_zoom'])
      assert.equal(await icon(key).count(), 0, `${key}: descriptions on needs long help`);
    for (const key of ['long_only', 'both']) {
      assert.equal(await icon(key).count(), 1);
      await icon(key).click();
      assert.deepEqual(await body.locator('> p').allTextContents(), ['Retained caveat with ?fps= and details.']);
      await p.keyboard.press('Escape');
    }
    await p.locator('.cfg-desc-switch').click();
    for (const key of ['touch_sound', 'blank', 'missing_flag', 'dashboard_zoom'])
      assert.equal(await icon(key).count(), 0, `${key}: needs flag and nonempty summary`);
    for (const key of ['watchdog_enabled', 'long_only', 'both']) assert.equal(await icon(key).count(), 1, key);
    await icon('both').click();
    assert.deepEqual(await body.locator('> p').allTextContents(), ['Résumé **simple** <img src=x>', 'Retained caveat with ?fps= and details.']);
    assert.equal(await body.getAttribute('lang'), 'en');
    assert.equal(await body.locator('> p').first().getAttribute('lang'), 'fr');
    assert.equal(await body.locator('img').count(), 0, 'summary stays plain text');
    assert.equal(await body.locator('strong').textContent(), 'caveat');
    assert.equal(await body.locator('code').textContent(), '?fps=');
    assert.equal(await body.locator('a').getAttribute('href'), 'https://example.org/settings');
    await p.keyboard.press('Escape');
    await icon('long_only').click();
    assert.deepEqual(await body.locator('> p').allTextContents(), ['Retained caveat with ?fps= and details.'], 'false suppresses duplicate summary');
    await p.keyboard.press('Escape');
    await icon('watchdog_enabled').click();
    assert.deepEqual(await body.locator('> p').allTextContents(), ['Relance **le tableau** <b>local</b>.']);
    assert.equal(await body.getAttribute('lang'), null, 'summary-only clears stale long-help language');
    assert.equal(await body.locator('> p').getAttribute('lang'), 'fr');
    assert.equal(await body.locator('b').count(), 0);
    assert.equal(new URL(await p.locator('#cfg-help-more').getAttribute('href')).searchParams.get('section'), 'watchdog_enabled');
    await p.locator('.cfg-desc-switch').click();
    assert.equal(await p.locator('#cfg-help').isVisible(), false, 'live toggle closes old content');
    assert.equal(await icon('watchdog_enabled').count(), 0, 'live toggle rebuilds icons');
    assert.equal(await p.locator('#savebar').isHidden(), true, 'view does not dirty settings');
    await p.locator('#cfg-touch_sound [role="switch"]').click();
    await p.locator('.cfg-more').click();
    await p.locator('#cfg-advanced').waitFor();
    await p.locator('.cfg-desc-switch').click();
    assert.equal(await p.locator('#cfg-advanced').count(), 1, 'card reveal survives');
    assert.equal(await p.locator('#cfg-touch_sound [role="switch"]').getAttribute('aria-checked'), 'true');
    assert.equal(await p.locator('#savebar').isVisible(), true);
    await p.locator('#cfg-filter').fill('watchdog');
    await p.waitForFunction(() => document.querySelectorAll('#cfg-groups .frow').length === 1);
    await p.locator('.cfg-desc-switch').click();
    assert.equal(await p.locator('#cfg-filter').inputValue(), 'watchdog');
    assert.equal(await p.locator('#cfg-groups .frow').count(), 1);
    assert.equal(await icon('watchdog_enabled').count(), 0);
    await p.locator('#cfg-filter').fill('');
    await p.locator('#cfg-touch_sound').waitFor();
    assert.equal(await p.locator('#cfg-touch_sound [role="switch"]').getAttribute('aria-checked'), 'true');
    await p.locator('label:has(#tier-adv)').click();
    await p.locator('.cfg-desc-switch').click();
    assert.equal(await p.locator('#tier-adv').isChecked(), true);
    assert.equal(await p.locator('#cfg-groups .frow').count(), SCHEMA.length);
    assert.equal(await p.locator('#cfg-touch_sound [role="switch"]').getAttribute('aria-checked'), 'true');
    await p.reload();
    await p.locator('#cfg-touch_sound').waitFor();
    assert.equal(await p.locator('#cfg-desc').isChecked(), false, 'view preference persists');
    assert.equal(await p.locator('#tier-adv').isChecked(), true);
  });
  test(`${engine.name}: descriptions preserve an unsaved password and save its visible draft`, async t => {
    assert.equal(engine.available, true, `${engine.name} must run`);
    const h = await harness();
    const browser = await engine.type.launch(engine.launch);
    t.after(async () => { await browser.close(); await new Promise(resolve => h.server.close(resolve)); });
    const p = await browser.newPage({ viewport: { width: 480, height: 480 } });
    await p.goto(h.url);
    const secret = p.locator('#cfg-test_secret input');
    await secret.waitFor();
    assert.equal(await secret.inputValue(), '', 'initial saved secrets stay masked');
    await secret.fill('new owner draft & symbols=kept');
    await p.locator('.cfg-desc-switch').click();
    assert.equal(await secret.inputValue(), 'new owner draft & symbols=kept', 'description toggle retains the visible unsaved password');
    await p.locator('.cfg-desc-switch').click();
    assert.equal(await secret.inputValue(), 'new owner draft & symbols=kept');
    const submitted = p.waitForResponse(response => response.url().endsWith('/api/v1/config') && response.request().method() === 'POST');
    await p.evaluate(() => window.cfgSave());
    await submitted;
    assert.equal(h.posts.length, 1);
    assert.equal(h.posts[0].test_secret, 'new owner draft & symbols=kept', 'production save posts the actual visible draft');
    await p.waitForFunction(() => document.getElementById('cfg-msg').textContent === 'Saved.');
    assert.equal(await secret.inputValue(), '', 'acknowledged secret is masked after server reload');
    assert.equal(await p.locator('#savebar').isHidden(), true);
    await p.locator('.cfg-desc-switch').click();
    assert.equal(await secret.inputValue(), '', 'clean render does not redisplay an acknowledged secret');
  });

}
