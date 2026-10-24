// The Configure ambient light source picker: free-form entry, keyboard selection and viewport containment,
// and only sources that report a light level are offered, so the one picked is one the panel accepts.
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

// These journeys exercise every setting, advanced ones included, so their Configure stubs open in the
// Advanced view the way a returning user's browser would; configure-density.test.mjs covers Basic.
const HELP_POPOVER = '<div id="cfg-help" class="cfg-help" popover="manual"><div class="cfg-help-head"><b id="cfg-help-title"></b><button id="cfg-help-close" type="button">x</button></div><div id="cfg-help-body" class="cfg-help-body"></div><div class="cfg-help-foot"><a id="cfg-help-more"></a></div></div>';
const ADVANCED_VIEW = `<script>try{localStorage.getItem('ha-paneld.configure.view.v1')||localStorage.setItem('ha-paneld.configure.view.v1','{"advanced":true}')}catch(e){}</script>`;

function json(body, status = 200) {
  return { status, headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) };
}

function configureVisualFixture() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css">${i18nBridge()}</head><body>
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <button id="tab-basic"></button><button id="tab-adv"></button>
    <div id="cfg-status" class="muted"></div>
    <div id="cfg-groups" class="cards" data-card-size-page="configure" data-card-size-epoch="1" data-card-size-restore="1"></div>
    <div id="proximity-learning-mount"></div><div id="cfg-msg" class="sr-only" role="status" aria-live="polite" aria-atomic="true"></div>${HELP_POPOVER}
    <script src="/assets/card-size-memory.js"></script><script src="/assets/card-column-alignment.js"></script>
    ${ADVANCED_VIEW}<script src="/configure-state.js"></script><script src="/configure-view.js"></script><script src="/configure-help.js"></script><script src="/configure-controls.js"></script><script src="/configure-brightness.js"></script><script src="/configure-auto-sleep.js"></script><script src="/configure-cards.js"></script><script src="/configure-render.js"></script><script src="/configure.js"></script><script src="/proximity-learning.js"></script>
  </body></html>`;
}

// assetDelayMs models a real panel, where a referenced script arrives some time after the document. On
// localhost every asset is instant, which hides whether a script runs before or after first paint.
async function startHarness(routes, pageFixture = fixture, assetDelayMs = 0) {
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    if (path === '/') return response.end(pageFixture());
    if (['/configure-state.js', '/configure-view.js', '/configure-help.js', '/configure-controls.js', '/configure-brightness.js', '/configure-auto-sleep.js', '/configure-cards.js', '/configure-render.js', '/configure.js', '/proximity-learning.js', '/install.js', '/info.js', '/power-safety.js'].includes(path)) {
      response.setHeader('content-type', 'application/javascript; charset=utf-8');
      return response.end(await readFile(join(root, path.slice(1)), 'utf8'));
    }
    if (path.startsWith('/assets/') && path.endsWith('.js')) {
      response.setHeader('content-type', 'application/javascript; charset=utf-8');
      const body = await readFile(join(root, path.slice('/assets/'.length)), 'utf8');
      if (assetDelayMs) await new Promise((resolve) => setTimeout(resolve, assetDelayMs));
      return response.end(body);
    }
    if (path === '/info.css') {
      response.setHeader('content-type', 'text/css');
      return response.end(await readFile(join(root, 'info.css'), 'utf8'));
    }
    const result = await routes(path, request);
    response.writeHead(result?.status || (result ? 200 : 404), result?.headers);
    response.end(result?.body || 'not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, url: `http://127.0.0.1:${server.address().port}` };
}

const browserTest = existsSync(chrome) ? test : test.skip;

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

async function requestBody(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return Buffer.concat(chunks).toString('utf8');
}

for (const [engine, launcher] of [['Chromium', chromium], ['WebKit', webkit]]) {
  browserTest(`Configure entity picker keeps free-form entry, keyboard selection and viewport containment in ${engine}`, async (t) => {
    let sourceReads = 0;
    const staleSource = deferred();
    const brightnessStatus = deferred();
    const settings = { auto_brightness_ha_entity: '' };
    const harness = await startHarness(async (path, request) => {
      if (path === '/api/v1/config/schema') return json([{
        key: 'auto_brightness_ha_entity', label: 'Ambient source', group: 'Display',
        type: 'STRING', tier: 'ADVANCED', available: true, picker: 'ha_illuminance',
      }]);
      if (path === '/api/v1/config' && request.method === 'POST') Object.assign(settings, Object.fromEntries(new URLSearchParams(await requestBody(request))));
      if (path === '/api/v1/config' || path === '/api/v1/config/values') return json({ settings, ha_expose: {}, ha_auth: {} });
      if (path === '/api/v1/auto-brightness/sources') {
        sourceReads++;
        if (sourceReads === 2) return staleSource.promise;
        return json({
          items: [{ entity_id: 'sensor.office_lux', friendly_name: 'Office light', unit: 'lx' }], available: true,
        });
      }
      if (path === '/api/v1/apps') return json({ apps: [] });
      if (path === '/api/v1/auto-brightness' || path === '/api/v1/auto-brightness/history') return brightnessStatus.promise;
      return json({});
    }, configureVisualFixture);
    const browser = await launcher.launch(engine === 'Chromium'
      ? { executablePath: chrome, headless: true } : { headless: true });
    const page = await browser.newPage({ viewport: { width: 320, height: 600 } });
    page.setDefaultTimeout(5_000);
    t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
    await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
    const input = page.locator('.ha-entity-input');
    await input.waitFor();
    await input.focus();
    await page.locator('.ha-entity-option').waitFor();
    assert.equal(await input.getAttribute('aria-expanded'), 'true');
    const geometry = await page.locator('.ha-entity-listbox').evaluate((list) => {
      const rect = list.getBoundingClientRect();
      return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, width: innerWidth, height: innerHeight };
    });
    assert.ok(geometry.left >= 0 && geometry.right <= geometry.width && geometry.top >= 0 && geometry.bottom <= geometry.height,
      `picker escaped viewport: ${JSON.stringify(geometry)}`);
    await input.press('ArrowDown');
    assert.equal(await input.getAttribute('aria-activedescendant'), 'ha-illuminance-listbox-option-0');
    await input.press('Enter');
    assert.equal(await input.inputValue(), 'sensor.office_lux');
    const staleRequest = page.waitForRequest((request) => request.url().includes('/auto-brightness/sources?q=sensor.custom_lux'));
    await input.fill('sensor.custom_lux');
    assert.equal(await input.inputValue(), 'sensor.custom_lux');
    await staleRequest;
    // Leaving the field commits the typed text; the view change that follows rebuilds the form.
    await input.press('Tab');
    await page.evaluate(() => window.cfgTab(false));
    const staleResponse = page.waitForResponse((response) => response.url().includes('/auto-brightness/sources?q=sensor.custom_lux'));
    staleSource.resolve(json({ items: [{ entity_id: 'sensor.stale' }], available: true }));
    await staleResponse;
    assert.equal(await page.locator('.ha-entity-listbox').count(), 0, 'rerender removed the body-level portal');
  });
}

for (const [engine, launcher] of [['Chromium', chromium], ['WebKit', webkit]]) {
  browserTest(`Configure offers only ambient sources that report a light level, and sends the picked one at once, in ${engine}`, async (t) => {
    const posts = [];
    const settings = { auto_brightness_ha_entity: '' };
    const harness = await startHarness(async (path, request) => {
      if (path === '/api/v1/config/schema') return json([{
        key: 'auto_brightness_ha_entity', label: 'Ambient source', group: 'Display',
        type: 'STRING', tier: 'ADVANCED', available: true, picker: 'ha_illuminance',
      }]);
      if (path === '/api/v1/config' || path === '/api/v1/config/values') {
        if (request.method === 'POST') {
          const form = new URLSearchParams(await requestBody(request));
          posts.push(Object.fromEntries(form));
          Object.assign(settings, Object.fromEntries(form));
          return json({ status: 'saved', settings, ha_expose: {}, ha_auth: {} });
        }
        return json({ settings, ha_expose: {}, ha_auth: {} });
      }
      // As served: a dead sensor sorts first by name and carries no reading.
      if (path === '/api/v1/auto-brightness/sources') return json({ available: true, items: [
        { entityId: 'sensor.study_presence_old', friendlyName: 'Study Presence Light Sensor', unit: 'lx', currentLux: null, available: false },
        { entityId: 'sensor.study_window_illuminance', friendlyName: 'Study Window illuminance', unit: 'lx', currentLux: 0, available: true },
      ] });
      if (path === '/api/v1/apps') return json({ apps: [] });
      if (path === '/api/v1/auto-brightness' || path === '/api/v1/auto-brightness/history') return json({ available: true });
      return json({});
    }, configureVisualFixture);
    const browser = await launcher.launch(engine === 'Chromium'
      ? { executablePath: chrome, headless: true } : { headless: true });
    const page = await browser.newPage({ viewport: { width: 480, height: 800 } });
    page.setDefaultTimeout(5_000);
    t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
    await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
    const input = page.locator('.ha-entity-input');
    await input.waitFor();
    await input.focus();
    await page.locator('.ha-entity-option').first().waitFor();
    await input.press('ArrowDown');
    await input.press('Enter');
    // Picking a suggestion finishes the gesture: one write carrying only that setting, with no Save step.
    for (let i = 0; i < 40 && posts.length === 0; i++) await page.waitForTimeout(25);
    await page.waitForTimeout(300);
    assert.deepEqual(posts, [{ auto_brightness_ha_entity: 'sensor.study_window_illuminance' }]);
    assert.equal(await page.locator('.ha-entity-option').count(), 1, 'a source with no reading is not offered');
  });
}

for (const [engine, launcher] of [['Chromium', chromium], ['WebKit', webkit]]) {
  browserTest(`Configure says sensors exist but none reports, rather than none found, in ${engine}`, async (t) => {
    let items = [
      { entityId: 'sensor.hall_lux', friendlyName: 'Hall lux', unit: 'lx', currentLux: null, available: false },
      { entityId: 'sensor.porch_lux', friendlyName: 'Porch lux', unit: 'lx', currentLux: null, available: false },
    ];
    const harness = await startHarness((path) => {
      if (path === '/api/v1/config/schema') return json([{
        key: 'auto_brightness_ha_entity', label: 'Ambient source', group: 'Display',
        type: 'STRING', tier: 'ADVANCED', available: true, picker: 'ha_illuminance',
      }]);
      if (path === '/api/v1/config' || path === '/api/v1/config/values') return json({ settings: { auto_brightness_ha_entity: '' }, ha_expose: {}, ha_auth: {} });
      if (path === '/api/v1/auto-brightness/sources') return json({ available: true, items });
      if (path === '/api/v1/apps') return json({ apps: [] });
      if (path === '/api/v1/auto-brightness' || path === '/api/v1/auto-brightness/history') return json({ available: true });
      return json({});
    }, configureVisualFixture);
    const browser = await launcher.launch(engine === 'Chromium'
      ? { executablePath: chrome, headless: true } : { headless: true });
    const page = await browser.newPage({ viewport: { width: 480, height: 800 } });
    page.setDefaultTimeout(5_000);
    t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
    await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
    const input = page.locator('.ha-entity-input');
    const note = page.locator('.picker-note');
    await input.waitFor();
    // A late status render can replace the field; focus the current one until its catalogue answers.
    await page.waitForFunction(() => {
      const field = document.querySelector('.ha-entity-input');
      if (field && document.activeElement !== field) field.focus();
      return /reporting|No Home/.test(document.querySelector('.picker-note')?.textContent || '');
    }, null, { polling: 200, timeout: 15_000 });
    assert.equal(await page.locator('.ha-entity-option').count(), 0, 'no source without a reading is offered');
    assert.match(await note.textContent(), /none is reporting a light level/);
    items = [];
    await input.fill('x');
    await page.waitForFunction(() => /No Home Assistant illuminance sensors found/.test(document.querySelector('.picker-note').textContent), null, { timeout: 15_000 });
  });
}

for (const [engine, launcher] of [['Chromium', chromium], ['WebKit', webkit]]) {
  browserTest(`Basic keeps a blocked setting's ambient source beside it and choosing a source enables it in ${engine}`, async (t) => {
    const posts = [];
    const settings = { auto_brightness_ha_entity: '', auto_brightness: 'false', dark_mode: 'false' };
    const harness = await startHarness(async (path, request) => {
      if (path === '/api/v1/config/schema') return json([
        { key: 'auto_brightness_ha_entity', label: 'Ambient source', group: 'Display', type: 'STRING', tier: 'ADVANCED', available: true, picker: 'ha_illuminance' },
        { key: 'auto_brightness', label: 'Auto brightness', group: 'Display', type: 'BOOL', tier: 'BASIC', available: true },
        { key: 'dark_mode', label: 'Dark mode', group: 'Display', type: 'BOOL', tier: 'ADVANCED', available: true },
      ]);
      if (path === '/api/v1/config' || path === '/api/v1/config/values') {
        if (request.method === 'POST') {
          const form = Object.fromEntries(new URLSearchParams(await requestBody(request)));
          posts.push(form);
          Object.assign(settings, form);
          return json({ status: 'saved' });
        }
        return json({ settings, ha_expose: {}, ha_auth: {} });
      }
      if (path === '/api/v1/auto-brightness/sources') return json({ available: true, items: [
        { entityId: 'sensor.window_lux', friendlyName: 'Window light', currentLux: 42, available: true, unit: 'lx' },
      ] });
      if (path === '/api/v1/auto-brightness') return json({ available: true, localSourcePresent: false, sourceAvailable: false });
      if (path === '/api/v1/auto-brightness/history') return json({ points: [] });
      if (path === '/api/v1/apps') return json({ apps: [] });
      return json({});
    }, configureVisualFixture);
    const browser = await launcher.launch(engine === 'Chromium' ? { executablePath: chrome, headless: true } : { headless: true });
    t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
    const page = await browser.newPage({ viewport: { width: 480, height: 480 } });
    page.setDefaultTimeout(3_000);
    await page.addInitScript(() => localStorage.setItem('ha-paneld.configure.view.v1', '{"advanced":false}'));
    await page.goto(harness.url);
    const toggle = page.locator('#cfg-auto_brightness [role="switch"]');
    await toggle.waitFor();
    assert.equal(await toggle.getAttribute('aria-disabled'), 'true');
    assert.deepEqual(await page.locator('[data-config-group="Display"] .frow').evaluateAll((rows) => rows.map((row) => row.id)),
      ['cfg-auto_brightness_ha_entity', 'cfg-auto_brightness'], 'the prerequisite precedes its blocked basic setting');
    assert.equal(await page.locator('[data-config-group="Display"] .cfg-more').textContent(), 'Show 1 advanced setting');
    assert.equal(await page.locator('#cfg-auto_brightness_ha_entity .adv-mark').count(), 1, 'the prerequisite retains its advanced metadata');
    await toggle.focus();
    await toggle.press('Enter');
    assert.deepEqual(posts, [], 'a blocked toggle sends nothing');
    const input = page.locator('#cfg-auto_brightness_ha_entity input');
    await input.fill('sensor.unavailable_lux');
    await input.press('Tab');
    await page.waitForFunction(() => window.ConfigurePage.values.auto_brightness_ha_entity === 'sensor.unavailable_lux');
    assert.equal(await toggle.getAttribute('aria-disabled'), 'true');
    assert.equal(await input.isVisible(), true, 'a configured source without a valid reading remains replaceable');
    await input.fill('');
    await input.focus();
    await page.locator('.ha-entity-option').first().waitFor();
    await input.press('ArrowDown');
    await input.press('Enter');
    await page.keyboard.press('Escape');
    await page.keyboard.press('Tab');
    await page.waitForFunction(() => document.querySelector('#cfg-auto_brightness [role="switch"]')?.getAttribute('aria-disabled') === 'false');
    await toggle.click();
    await page.waitForFunction(() => window.ConfigurePage.values.auto_brightness === 'true');
    for (let i = 0; i < 50 && posts.length < 3; i++) await page.waitForTimeout(20);
    assert.deepEqual(posts, [
      { auto_brightness_ha_entity: 'sensor.unavailable_lux' },
      { auto_brightness_ha_entity: 'sensor.window_lux' },
      { auto_brightness: 'true' },
    ]);
    assert.equal(await page.locator('#cfg-dark_mode').count(), 0, 'unrelated advanced settings stay hidden');
    assert.equal(await input.count(), 0, 'a usable source restores the ordinary Basic view');
    await page.evaluate(() => window.cfgTab(true));
    assert.equal(await input.isVisible(), true, 'Advanced still offers the source');
    await page.evaluate(() => window.cfgTab(false));
    assert.equal(await input.count(), 0);
  });
}
