// Real Configure, Setup and Entities entry points, with API replies supplied at the HTTP boundary.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';
import { configureVisualFixture } from './fixtures/configure-page.mjs';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';

// Point at an archived base's assets to establish old-code-red without changing this worktree.
const assets = process.env.SHARED_SEVERITY_ASSETS || new URL('../../app/src/main/assets/', import.meta.url).pathname;
const chrome = process.env.CHROME || '/usr/bin/chromium';
const engines = [
  ['Chromium', chromium, { executablePath: chrome, args: ['--no-sandbox'] }, chrome],
  ['WebKit', webkit, {}, webkit.executablePath()],
];
const dashboards = { queried: true, items: [{ path: '/home', title: 'Home', group: 'dashboard' }], default: { explicit: true, path: '/home' } };
const stages = ['identity', 'renderer', 'ha_url', 'ha_credentials', 'home_dashboard', 'entity_filter', 'render_proof'];
function journey(next, entity_filter = {}) {
  return { complete: false, repair: false, next, panel: { id: 'sample', name: 'Sample' }, discovery: {},
    home_dashboard: { value: '/missing/view' }, entity_filter: { relevant: true, enabled: false, count: 80,
      level: 'green', confidence: 'estimated', tier: 'capable', ...entity_filter },
    steps: stages.map((stage) => ({ stage, status: stage === next ? 'pending' : 'unknown', detail: '' })) };
}
function shell(body, script) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/info.css">${i18nBridge()}</head><body><div class="wrap">${body}</div><script src="/assets/${script}.js"></script></body></html>`;
}
const setupFixture = () => shell('<div class="wiz" id="wiz"><ol class="wiz-dots" id="wiz-dots"></ol><div id="wiz-step" class="wiz-step"></div></div>', 'setup');

async function harness(t, fixture, route) {
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    if (path === '/') return response.end(fixture());
    if (path.endsWith('.js') || path === '/info.css') {
      response.setHeader('content-type', path.endsWith('.js') ? 'application/javascript' : 'text/css');
      return response.end(await readFile(resolve(assets, path.replace(/^\/(assets\/)?/, '')), 'utf8'));
    }
    const result = await route(path, request) || {};
    response.writeHead(result.status || 200, { 'content-type': 'application/json' });
    response.end(JSON.stringify(result.body || {}));
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  t.after(() => new Promise((done) => { server.close(done); server.closeAllConnections(); }));
  return `http://127.0.0.1:${server.address().port}`;
}
async function severity(page, selector, word) {
  const row = page.locator(selector);
  await row.locator('.notice-pill').getByText(word, { exact: true }).waitFor();
  const geometry = await row.evaluate((el) => {
    const pill = el.querySelector('.notice-pill'), r = el.getBoundingClientRect(), p = pill.getBoundingClientRect();
    return { color: getComputedStyle(pill).color, rowColor: getComputedStyle(pill.parentElement).color,
      visible: p.width > 0 && p.height > 0, fits: p.left >= r.left - 1 && p.right <= r.right + 1,
      overflow: document.documentElement.scrollWidth > innerWidth + 1 };
  });
  assert.equal(geometry.visible, true, `${selector}: severity word is visible`);
  assert.equal(geometry.fits, true, `${selector}: pill stays inside its inline message`);
  assert.equal(geometry.overflow, false, `${selector}: no horizontal page overflow`);
  assert.equal(geometry.color, geometry.rowColor, `${selector}: pill inherits its shared row's text color`);
}
async function statusRelation(page, input, note) {
  assert.equal(await page.locator(input).getAttribute('aria-describedby'), note.slice(1));
  assert.equal(await page.locator(note).getAttribute('role'), 'status');
  assert.equal(await page.locator(note).getAttribute('aria-live'), 'polite');
}
async function tokenColor(page, selector, property, token) {
  const colors = await page.locator(selector).first().evaluate((el, { property, token }) => {
    const probe = document.body.appendChild(document.createElement('span'));
    probe.style.color = `var(${token})`;
    const actual = getComputedStyle(el)[property], expected = getComputedStyle(probe).color;
    probe.remove();
    return { actual, expected };
  }, { property, token });
  assert.equal(colors.actual, colors.expected, `${selector}: ${property} resolves ${token}`);
}

for (const [name, engine, launch, executable] of engines) {
  for (const theme of ['dark', 'light']) for (const width of [360, 1280]) {
    const prefix = `${name} ${theme} ${width}px`;
    async function open(t, fixture, route) {
      const url = await harness(t, fixture, route);
      const browser = await engine.launch({ headless: true, ...launch });
      t.after(() => browser.close());
      const page = await browser.newPage({ viewport: { width, height: 900 }, colorScheme: theme });
      page.setDefaultTimeout(4_000);
      await page.goto(url, { waitUntil: 'domcontentloaded' });
      return page;
    }
    const options = { skip: !existsSync(executable), timeout: 30_000 };

    test(`${prefix}: Configure warnings preserve details, live relations and prerequisite colors`, options, async (t) => {
      const schema = [
        { key: 'home_dashboard', label: 'Dashboard', type: 'STRING', picker: 'ha_dashboard', available: true, group: 'Home Assistant' },
        { key: 'auto_brightness', label: 'Auto brightness', type: 'BOOL', available: true, group: 'Display' },
        { key: 'auto_sleep', label: 'Auto sleep', type: 'BOOL', available: true, group: 'Behaviour' },
      ];
      const settings = { home_dashboard: '/missing/view', auto_brightness: 'true', auto_brightness_ha_entity: 'sensor.lux',
        auto_brightness_minimum_percent: '10', auto_brightness_maximum_percent: '90', auto_sleep: 'true' };
      let eligible = true;
      let releaseHistory;
      const historyReply = new Promise((done) => { releaseHistory = done; });
      const page = await open(t, configureVisualFixture, async (path, request) => {
        if (path === '/api/v1/config/schema') return { body: schema };
        if (path === '/api/v1/config' && request.method === 'POST') {
          const chunks = [];
          for await (const chunk of request) chunks.push(chunk);
          Object.assign(settings, Object.fromEntries(new URLSearchParams(Buffer.concat(chunks).toString())));
          return { body: { status: 'saved' } };
        }
        if (path === '/api/v1/config' || path === '/api/v1/config/values') return { body: { settings, ha_expose: {}, ha_auth: { configured: true } } };
        if (path === '/api/v1/config/home-dashboards') return { body: dashboards };
        if (path === '/api/v1/auto-brightness') return { body: { available: true, entity_id: 'sensor.lux', source_revision: 'one' } };
        if (path === '/api/v1/auto-brightness/history') return { body: { points: [], source_revision: 'one' } };
        if (path === '/api/v1/auto-brightness/reset') return { status: 500, body: { error: 'reset refused' } };
        if (path === '/api/v1/auto-sleep/prerequisite') return { body: { eligible, phase: eligible ? 'assigned' : 'unassigned', area_name: eligible ? 'Studio' : '' } };
        if (path === '/api/v1/auto-sleep/history') return historyReply;
        if (path === '/api/v1/auto-sleep') return { body: { enabled: true, available: true, phase: 'live', area_name: 'Studio', source_count: 1 } };
      });
      await severity(page, '#cfg-home_dashboard-path-note', 'Warning');
      await tokenColor(page, '#cfg-home_dashboard-path-note', 'color', '--dim');
      await tokenColor(page, '#cfg-home_dashboard-path-note .notice-pill', 'color', '--dim');
      const customSize = await page.locator('#cfg-home_dashboard-path-note .notice-detail').evaluate((el) => getComputedStyle(el).fontSize);
      assert.match(await page.locator('#cfg-home_dashboard-path-note').innerText(), /\/missing.*fall back to its default/);
      await statusRelation(page, '#cfg-home_dashboard-path', '#cfg-home_dashboard-path-note');
      await page.locator('#cfg-home_dashboard-path').fill('/home/view');
      assert.equal(await page.locator('#cfg-home_dashboard-path-note .notice-pill').count(), 0, 'known root returns to ordinary help');
      assert.equal(await page.locator('#cfg-home_dashboard-path-note').evaluate((el) => getComputedStyle(el).fontSize), customSize, 'custom warning retains ordinary help typography');
      await Promise.all([
        page.waitForResponse((reply) => reply.url().endsWith('/api/v1/config') && reply.request().method() === 'POST'),
        page.locator('#cfg-home_dashboard-path').press('Tab'),
      ]);
      await page.waitForFunction(() => document.querySelector('#cfg-home_dashboard-path')?.value === '/home/view');
      await page.locator('#auto-sleep-history-message').waitFor({ state: 'attached' });
      const historySize = await page.locator('#auto-sleep-history-message').evaluate((el) => getComputedStyle(el).fontSize);
      releaseHistory({ status: 401 });
      await severity(page, '#auto-sleep-history-message', 'Warning');
      assert.equal(await page.locator('#auto-sleep-history-message .notice-detail').evaluate((el) => getComputedStyle(el).fontSize), historySize, 'history error retains ordinary message typography');
      assert.match(await page.locator('#auto-sleep-history-message').innerText(), /History request failed \(HTTP invalid_value\)/);
      assert.equal(await page.locator('#auto-sleep-history-message').getAttribute('aria-live'), 'polite');
      page.on('dialog', (dialog) => dialog.accept());
      await page.getByRole('button', { name: 'Reset learned history', exact: true }).click();
      await severity(page, '#auto-brightness-learning .autobright-message', 'Warning');
      assert.match(await page.locator('#auto-brightness-learning .autobright-message').innerText(), /Action failed/);
      await tokenColor(page, '#auto-sleep-prerequisite-status', 'color', '--dim');
      eligible = false;
      await page.evaluate(() => window.dispatchEvent(new Event('focus')));
      await page.getByText('Assign this panel to a Home Assistant Area before enabling Auto-sleep.', { exact: true }).waitFor();
      await tokenColor(page, '#auto-sleep-prerequisite-status', 'color', '--status-warn');
      await statusRelation(page, '#cfg-auto_sleep [role=switch]', '#auto-sleep-prerequisite-status');
    });

    test(`${prefix}: Setup custom path warning returns to plain help without losing its live relation`, options, async (t) => {
      const page = await open(t, setupFixture, (path) => {
        if (path === '/api/v1/setup') return { body: journey('home_dashboard') };
        if (path === '/api/v1/config/home-dashboards') return { body: dashboards };
      });
      await severity(page, '#wiz-home_dashboard_custom_note', 'Warning');
      await tokenColor(page, '#wiz-home_dashboard_custom_note', 'color', '--dim');
      await tokenColor(page, '#wiz-home_dashboard_custom_note .notice-pill', 'color', '--dim');
      const customSize = await page.locator('#wiz-home_dashboard_custom_note .notice-detail').evaluate((el) => getComputedStyle(el).fontSize);
      await statusRelation(page, '#wiz-home_dashboard_custom', '#wiz-home_dashboard_custom_note');
      assert.match(await page.locator('#wiz-home_dashboard_custom_note').innerText(), /\/missing.*fall back to its default/);
      await page.locator('#wiz-home_dashboard_custom').fill('/home/view');
      assert.equal(await page.locator('#wiz-home_dashboard_custom_note .notice-pill').count(), 0);
      assert.equal(await page.locator('#wiz-home_dashboard_custom_note').evaluate((el) => getComputedStyle(el).fontSize), customSize, 'custom warning retains ordinary help typography');
      assert.match(await page.locator('#wiz-home_dashboard_custom_note').innerText(), /A path on this Home Assistant/);
      await statusRelation(page, '#wiz-home_dashboard_custom', '#wiz-home_dashboard_custom_note');
    });

    test(`${prefix}: Setup verdict follows the displayed count and retains facts and focus across live samples`, options, async (t) => {
      let current = journey('entity_filter', { counting: true, count: 800, recommend_above: 200, struggle_above: 600, tier_label: 'Sample chip' });
      const page = await open(t, setupFixture, (path) => path === '/api/v1/setup' ? { body: current } : undefined);
      await page.locator('#ef-count').waitFor();
      await page.locator('#ef-decline').focus();
      const samples = await page.evaluate(() => new Promise((done) => {
        const values = [];
        const sample = () => {
          const count = Number(document.getElementById('ef-count').textContent.replaceAll(',', ''));
          values.push({ count, word: document.querySelector('#ef-verdict .notice-pill')?.textContent });
          if (count === 800) done(values); else setTimeout(sample, 20);
        };
        sample();
      }));
      assert.ok(samples.some(({ count }) => count > 0 && count < 800), 'count visibly eases between server samples');
      for (const { count, word } of samples) assert.equal(word, count >= 600 ? 'Action needed' : count >= 200 ? 'Warning' : 'Note', `displayed count ${count}`);
      await severity(page, '#ef-verdict', 'Action needed');
      assert.equal(await page.locator('#ef-panel').innerText(), 'Sample chip');
      assert.equal(await page.locator('#ef-tag').innerText(), 'Counting');
      assert.equal(await page.locator('#ef-decline').evaluate((el) => el === document.activeElement), true);
      current = journey('entity_filter', { counting: false, count: 800, level: 'amber', confidence: 'measured', tier_label: 'Sample chip' });
      await page.evaluate(() => new BroadcastChannel('ha-paneld-ha-oauth').postMessage('connected'));
      await page.getByText('Measured', { exact: true }).waitFor();
      await severity(page, '#ef-verdict', 'Warning');
      assert.equal(await page.locator('#ef-head').innerText(), 'Filtering is worth turning on');
      assert.equal(await page.locator('#ef-count').innerText(), '800');
      assert.equal(await page.locator('#ef-counting').isVisible(), false);
      assert.equal(await page.locator('#ef-decline').evaluate((el) => el === document.activeElement), true);
    });

    test(`${prefix}: Entities policy border and emitted reason icons use semantic theme colors`, options, async (t) => {
      const fixture = () => shell(`<div class="cards entity-cards"><div class="card"><div id="entity-status"></div>
        <button id="entity-sync"></button><button id="entity-activate"></button><button id="entity-reset"></button><div id="entity-action-result"></div>
        <fieldset class="entity-policy"><legend>Automatic promotion</legend><label><input id="entity-auto-static" type="checkbox">Dashboard configuration</label><label><input id="entity-auto-runtime" type="checkbox">Runtime access</label></fieldset></div>
        <input id="entity-search"><div id="entity-search-status"></div><section id="entity-issues"><div id="entity-issues-summary"></div><div id="entity-issues-list"></div><button id="entity-issues-rescan"></button></section>
        <section id="entity-dynamic" hidden><div id="entity-dynamic-list"></div></section><section class="card entity-list" data-table="current" data-filter="subscribed">
        <div class="tablewrap"><table class="entity-table"><thead><tr><th>Entity</th></tr></thead><tbody></tbody></table></div><button class="entity-prev"></button><button class="entity-next"></button><input class="entity-select-page" type="checkbox"><span class="entity-selected"></span><span class="entity-msg"></span></section></div>`, 'entities');
      const page = await open(t, fixture, (path) => {
        if (path === '/api/v1/dashboard/entities/issues') return { body: { items: [], dynamic_expressions: [] } };
        if (path === '/api/v1/dashboard/entities') return { body: { total: 1, items: [{ entity_id: 'sensor.a', static: true, runtime: true, reasons: 'Dashboard configuration' }] } };
        return { body: { state: 'active', auto_static: true, auto_runtime: true, stream_entity_count: 1, catalog_count: 1 } };
      });
      await page.locator('.reason-icon').first().waitFor({ state: 'attached' });
      await tokenColor(page, '.entity-policy', 'borderTopColor', '--border');
      await tokenColor(page, '.reason-icon', 'color', '--dim');
      assert.deepEqual(await page.locator('.reason-icon').evaluateAll((nodes) => nodes.map((el) => el.getAttribute('aria-label'))), ['Dashboard configuration', 'Runtime hass.states access']);
      assert.equal(await page.locator('.reason-icons').isVisible(), width <= 600);
      assert.equal(await page.locator('.reason-text').isVisible(), width > 600);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true);
    });
  }
}
