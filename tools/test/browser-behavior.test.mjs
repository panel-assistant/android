import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { chromium, webkit } from 'playwright-core';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';
import { browserTest, chrome, deferred, json, root, startHarness as startHarnessWith } from './fixtures/browser-harness.mjs';
import { screenshotFixture, screenshotRoutes, requestBody } from './fixtures/dashboard-screenshot.mjs';


// These journeys exercise every setting, advanced ones included, so their Configure stubs open in the
// Advanced view the way a returning user's browser would; configure-density.test.mjs covers Basic.
const HELP_POPOVER = '<div id="cfg-help" class="cfg-help" popover="manual"><div class="cfg-help-head"><b id="cfg-help-title"></b><button id="cfg-help-close" type="button">x</button></div><div id="cfg-help-body" class="cfg-help-body"></div><div class="cfg-help-foot"><a id="cfg-help-more"></a></div></div>';
const ADVANCED_VIEW = `<script>try{localStorage.getItem('ha-paneld.configure.view.v1')||localStorage.setItem('ha-paneld.configure.view.v1','{"advanced":true}')}catch(e){}</script>`;


function fixture(translations = {}, locale = 'en') {
  return `<!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="/info.css">${i18nBridge({ locale, strings: translations })}</head><body>
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <button id="tab-basic"></button><button id="tab-adv"></button>
    <div id="cfg-msg" class="sr-only" role="status" aria-live="polite" aria-atomic="true"></div><p id="cfg-status"></p><div id="cfg-groups"></div>
    <div id="proximity-learning-mount"></div>${HELP_POPOVER}
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    ${ADVANCED_VIEW}<script src="/configure-state.js"></script><script src="/configure-view.js"></script><script src="/configure-help.js"></script><script src="/configure-controls.js"></script><script src="/configure-brightness.js"></script><script src="/configure-auto-sleep.js"></script><script src="/configure-cards.js"></script><script src="/configure-render.js"></script><script src="/configure.js"></script><script src="/proximity-learning.js"></script>
  </body></html>`;
}

// The page follows the panel through the `config` family (GET /api/v1/config/values). A fixture that does
// not answer that read itself answers it with its GET /api/v1/config body, which carries the same fields.
const startHarness = (routes, pageFixture = fixture, assetDelayMs = 0) => startHarnessWith(async (path, request) => {
  if (path !== '/api/v1/config/values') return routes(path, request);
  return (await routes(path, request)) ?? routes('/api/v1/config', request);
}, pageFixture, assetDelayMs);

function configureVisualFixture() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css">${i18nBridge()}</head><body>
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <button id="tab-basic"></button><button id="tab-adv"></button>
    <div id="cfg-msg" class="sr-only" role="status" aria-live="polite" aria-atomic="true"></div><p id="cfg-status"></p>
    <div id="cfg-groups" class="cards" data-card-size-page="configure" data-card-size-epoch="1" data-card-size-restore="1"></div>
    <div id="proximity-learning-mount"></div>${HELP_POPOVER}
    <script src="/assets/card-size-memory.js"></script><script src="/assets/card-column-alignment.js"></script>
    ${ADVANCED_VIEW}<script src="/configure-state.js"></script><script src="/configure-view.js"></script><script src="/configure-help.js"></script><script src="/configure-controls.js"></script><script src="/configure-brightness.js"></script><script src="/configure-auto-sleep.js"></script><script src="/configure-cards.js"></script><script src="/configure-render.js"></script><script src="/configure.js"></script><script src="/proximity-learning.js"></script>
  </body></html>`;
}



browserTest('Configure bypasses caches and lets a supported HA language supersede an unsupported stored tag', async (t) => {
  const reads = [];
  const schema = (label) => [
    { key: 'friendly_name', label, group: 'Identity', type: 'STRING', available: true },
    { key: 'ui_language', label: 'Interface language', group: 'System', type: 'ENUM', available: true,
      options: ['auto', 'en', 'de', 'fr', 'it', 'es', 'zh-Hans'] },
  ];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') {
      const url = new URL(request.url, 'http://panel.test');
      reads.push({ path, url: url.pathname + url.search, cacheControl: request.headers['cache-control'] });
      return json(schema(url.searchParams.get('ha_lang') === 'de-DE' ? 'Anzeigename' : 'Friendly name'));
    }
    if (path === '/api/v1/config') {
      reads.push({ path, url: request.url, cacheControl: request.headers['cache-control'] });
      return json({ settings: { friendly_name: 'Panel', ui_language: 'auto' }, ha_expose: {}, ha_auth: { configured: true } });
    }
    if (path === '/api/v1/ha/oauth/status') return json({ phase: 'connected', display_name: 'Owner', language: 'de-DE' });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/discovery') return json({});
    if (path === '/api/v1/config/home-dashboards') return json({ queried: true, items: [], default: { explicit: false, path: '' } });
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.addInitScript(() => localStorage.setItem('selectedLanguage', JSON.stringify('ja-JP')));
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.getByText('Anzeigename', { exact: true }).waitFor();

  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('selectedLanguage'))), 'ja-JP');
  assert.deepEqual(await page.locator('#cfg-ui_language option').allTextContents(),
    ['Automatic', 'English', 'Deutsch', 'Français', 'Italiano', 'Español', '简体中文']);
  assert.deepEqual(reads.filter((read) => read.path === '/api/v1/config/schema').map((read) => read.url), [
    '/api/v1/config/schema?lang=ja-JP',
    '/api/v1/config/schema?lang=ja-JP&ha_lang=de-DE',
  ]);
  for (const read of reads) assert.equal(read.cacheControl, 'no-cache');
});

browserTest('Configure shows each enum option by its served label while preserving submitted wire values', async (t) => {
  const posts = [];
  // The server serves optionLabels beside options; an option without a label record is served as itself.
  const schema = [
    { key: 'navbar_mode', label: '导航栏模式', help: '选择导航栏的显示方式。', group: 'Behaviour', tier: 'BASIC', type: 'ENUM', options: ['Off', 'Always on', 'Swipe reveal', 'Native', 'Future wire'], optionLabels: ['关闭', '始终显示导航栏（本地化长标签）', '滑动显示', '原生', 'Future wire'], available: true },
    { key: 'cpu_governor', label: '处理器调速器', help: '选择处理器调速策略。', group: 'System', tier: 'ADVANCED', type: 'ENUM', options: ['Performance', 'Efficiency', 'Auto'], optionLabels: ['性能', '节能', '自动'], available: true },
  ];
  const translations = {};
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') {
        posts.push(await requestBody(request));
        return json({ status: 'saved' });
      }
      return json({ settings: { navbar_mode: 'Off', cpu_governor: 'Auto' }, ha_expose: {}, ha_auth: {} });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/discovery') return json({});
    if (path === '/health') return { body: 'ok cfg=test' };
    return null;
  }, () => fixture(translations, 'zh-Hans'));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 800 } });
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const navbar = page.locator('#cfg-navbar_mode select');
  await navbar.waitFor();
  assert.deepEqual(await navbar.locator('option').allTextContents(), ['关闭', '始终显示导航栏（本地化长标签）', '滑动显示', '原生', 'Future wire']);
  assert.deepEqual(await navbar.locator('option').evaluateAll((options) => options.map((option) => option.value)), ['Off', 'Always on', 'Swipe reveal', 'Native', 'Future wire']);
  assert.deepEqual(await page.locator('#cfg-cpu_governor option').allTextContents(), ['性能', '节能', '自动']);

  const sent = page.waitForResponse((response) => response.url().endsWith('/api/v1/config') && response.request().method() === 'POST');
  await navbar.selectOption('Swipe reveal');
  await sent;
  assert.equal(posts.length, 1);
  assert.equal(new URLSearchParams(posts[0]).get('navbar_mode'), 'Swipe reveal');
  assert.ok(!posts[0].includes(encodeURIComponent('滑动显示')));

});

browserTest('Configure enum labels fit a 1361px viewport at large text in every release locale', async (t) => {
  const enumSpecs = [
    ['navbar_mode', 'Behaviour', ['Off', 'Always on', 'Swipe reveal', 'Native']],
    ['cpu_governor', 'System', ['Performance', 'Efficiency', 'Auto']],
    ['camera_resolution', 'Camera', ['480p', '720p', '1080p']],
    ['dashboard_theme', 'Dashboard', ['Follow Home Assistant', 'Dark', 'Light']],
    ['voice_audio_source', 'Voice', ['voice_recognition', 'mic', 'voice_communication']],
    ['voice_sensitivity', 'Voice', ['low', 'normal', 'high']],
    ['log_ship_protocol', 'Logging', ['syslog-udp', 'syslog-tcp', 'http']],
  ];
  const schema = enumSpecs.map(([key, group, options]) => ({
    key, group, options, label: key, help: `${key} help`, tier: 'BASIC', type: 'ENUM', available: true,
  }));
  const settings = Object.fromEntries(enumSpecs.map(([key, , options]) => [key, options[0]]));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  t.after(async () => browser.close());

  for (const locale of ['de', 'es', 'fr', 'it', 'zh-Hans', 'nl', 'pl', 'uk', 'cs', 'pt-BR']) {
    const target = JSON.parse(await readFile(new URL(`../../app/src/main/assets/i18n/${locale}.json`, import.meta.url), 'utf8')).strings;
    const translations = Object.fromEntries(Object.entries(target)
      .filter(([key]) => key.startsWith('configure.enum.')).map(([key, record]) => [key, record.text]));
    const harness = await startHarness((path) => {
      if (path === '/api/v1/config/schema') return json(schema);
      if (path === '/api/v1/config') return json({ settings, ha_expose: {}, ha_auth: {} });
      if (path === '/api/v1/apps') return json({ apps: [] });
      if (path === '/api/v1/radio') return json({ present: false });
      if (path === '/api/v1/proximity') return json({ present: false });
      if (path === '/api/v1/config/discovery') return json({});
      return null;
    }, () => fixture(translations, locale));
    const page = await browser.newPage({ viewport: { width: 480, height: 900 } });
    try {
      await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
      await page.locator('#cfg-log_ship_protocol select').waitFor();
      await page.evaluate(() => { document.documentElement.style.fontSize = '24px'; });
      const geometries = await page.locator('.frow select').evaluateAll((selects) => selects.map((select) => {
        const row = select.closest('.frow');
        return {
          id: row.id, rowClient: row.clientWidth, rowScroll: row.scrollWidth,
          selectClient: select.clientWidth, selectScroll: select.scrollWidth,
        };
      }));
      assert.equal(geometries.length, enumSpecs.length, `${locale} must render every synthetic enum`);
      for (const geometry of geometries) {
        assert.ok(geometry.selectClient > 0, `${locale} ${geometry.id} select is usable: ${JSON.stringify(geometry)}`);
        assert.ok(geometry.rowScroll <= geometry.rowClient + 1, `${locale} ${geometry.id} row overflow: ${JSON.stringify(geometry)}`);
        assert.ok(geometry.selectScroll <= geometry.selectClient + 1, `${locale} ${geometry.id} select overflow: ${JSON.stringify(geometry)}`);
      }
    } finally {
      await page.close();
      await new Promise((resolve) => harness.server.close(resolve));
    }
  }
});

// The row's supporting line is the summary; each string carries its own language, as the server resolves
// them independently.
function localizedField(key, label, labelLanguage, help, helpLanguage, group = 'Identity') {
  return { key, label, labelLanguage, summary: help, summaryLanguage: helpLanguage, group, type: 'STRING', available: true };
}

browserTest('Configure renders translated, fallback and mixed schema fields with per-string language tags', async (t) => {
  const schema = [
    localizedField('friendly_name', 'Anzeigename', 'de', 'Name dieses Panels.', 'de'),
    localizedField('manufacturer', 'Manufacturer', 'en', 'Optional manufacturer override.', 'en'),
    localizedField('model', 'Modell', 'de', 'Optional model override.', 'en'),
    localizedField('ha_url', 'Home Assistant URL', 'en', 'Adresse von Home Assistant.', 'de', 'Home Assistant connection'),
  ];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({
      settings: { friendly_name: 'Panel', manufacturer: '', model: '', ha_url: '' },
      ha_expose: {}, ha_auth: { configured: false },
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#cfg-ha_url').waitFor();

  const rendered = await page.locator('.frow').evaluateAll((rows) => Object.fromEntries(rows.map((row) => {
    const label = row.querySelector('.flabel > span');
    const help = row.querySelector('.flabel > small');
    const control = row.querySelector('.fctl input');
    return [row.id, {
      label: label?.textContent, labelLanguage: label?.getAttribute('lang'),
      help: help?.textContent, helpLanguage: help?.getAttribute('lang'),
      controlLanguage: control?.getAttribute('lang'),
    }];
  })));
  assert.deepEqual(rendered['cfg-friendly_name'], {
    label: 'Anzeigename', labelLanguage: 'de', help: 'Name dieses Panels.', helpLanguage: 'de', controlLanguage: 'de',
  });
  assert.deepEqual(rendered['cfg-manufacturer'], {
    label: 'Manufacturer', labelLanguage: 'en', help: 'Optional manufacturer override.', helpLanguage: 'en', controlLanguage: 'en',
  });
  assert.deepEqual(rendered['cfg-model'], {
    label: 'Modell', labelLanguage: 'de', help: 'Optional model override.', helpLanguage: 'en', controlLanguage: 'de',
  });
  assert.deepEqual(rendered['cfg-ha_url'], {
    label: 'Home Assistant URL', labelLanguage: 'en', help: 'Adresse von Home Assistant.', helpLanguage: 'de', controlLanguage: 'en',
  });
});

for (const scenario of [
  { name: 'German', initial: 'auto', saved: 'de', finalLabel: 'Anzeigename', haLanguage: '', queryOverride: true },
  { name: 'Czech', optionLabel: 'Čeština', initial: 'auto', saved: 'cs', finalLabel: 'Friendly name', haLanguage: '', queryOverride: true },
  { name: 'Brazilian Portuguese', optionLabel: 'Português (Brasil)', initial: 'auto', saved: 'pt-BR', finalLabel: 'Friendly name', haLanguage: '', queryOverride: true },
  { name: 'English', initial: 'auto', saved: 'en', finalLabel: 'Friendly name', haLanguage: '', queryOverride: false },
  { name: 'Automatic', initial: 'en', saved: 'auto', finalLabel: 'Anzeigename', haLanguage: 'de-DE', queryOverride: true },
]) {
  browserTest(`Saving ${scenario.name} releases a supported browser override and recreates Configure`, async (t) => {
    const state = { uiLanguage: scenario.initial, documents: 0, posts: [], schemaUrls: [] };
    const schemaFor = (language) => [
      localizedField('friendly_name', language === 'de' ? 'Anzeigename' : 'Friendly name', language, '', null),
      { key: 'ui_language', label: 'Interface language', labelLanguage: 'en', help: '', helpLanguage: null,
        group: 'System', type: 'ENUM', available: true, options: ['auto', 'en', 'de', 'fr', 'it', 'es', 'zh-Hans', 'cs', 'pt-BR'] },
    ];
    const harness = await startHarness(async (path, request) => {
      if (path === '/api/v1/config/schema') {
        const url = new URL(request.url, 'http://panel.test');
        state.schemaUrls.push(url.pathname + url.search);
        const explicit = url.searchParams.get('lang');
        const ha = url.searchParams.get('ha_lang');
        const language = explicit === 'fr' ? 'fr' : state.uiLanguage !== 'auto' ? state.uiLanguage : ha === 'de-DE' ? 'de' : 'en';
        return json(schemaFor(language));
      }
      if (path === '/api/v1/config') {
        if (request.method === 'POST') {
          const body = new URLSearchParams(await requestBody(request));
          state.uiLanguage = body.get('ui_language');
          state.posts.push(state.uiLanguage);
          return json({ ok: true, message: 'Saved.' });
        }
        return json({
          settings: { friendly_name: 'Panel', ui_language: state.uiLanguage }, ha_expose: {},
          ha_auth: { configured: !!scenario.haLanguage },
        });
      }
      if (path === '/api/v1/ha/oauth/status') {
        return json({ phase: 'connected', display_name: 'Owner', language: scenario.haLanguage });
      }
      if (path === '/api/v1/apps') return json({ apps: [] });
      if (path === '/api/v1/radio') return json({ present: false });
      if (path === '/api/v1/proximity') return json({ present: false });
      if (path === '/api/v1/config/discovery') return json({});
      if (path === '/health') return { body: 'ok cfg=locale' };
    }, () => { state.documents++; return fixture(); });
    const browser = await chromium.launch({ executablePath: chrome, headless: true });
    const page = await browser.newPage();
    page.setDefaultTimeout(3_000);
    t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
    await page.addInitScript(() => {
      if (sessionStorage.getItem('locale-test-seeded')) return;
      sessionStorage.setItem('locale-test-seeded', '1');
      localStorage.setItem('selectedLanguage', JSON.stringify('fr'));
    });
    await page.goto(harness.url + (scenario.queryOverride ? '?lang=fr' : ''), { waitUntil: 'domcontentloaded', timeout: 5_000 });
    if (scenario.optionLabel) assert.equal(await page.locator(`#cfg-ui_language option[value="${scenario.saved}"]`).textContent(), scenario.optionLabel);
    // Choosing the language sends it at once; its success recreates the document under the new precedence.
    // (A same-document URL change also counts as a navigation, so wait for the new document itself.)
    await page.evaluate(() => { window.beforeLanguageSend = true; });
    await page.locator('#cfg-ui_language select').selectOption(scenario.saved);
    await page.waitForFunction(() => !window.beforeLanguageSend && document.getElementById('cfg-ui_language'));
    await page.getByText(scenario.finalLabel, { exact: true }).waitFor();

    assert.deepEqual(state.posts, [scenario.saved]);
    assert.equal(state.documents, 2);
    assert.equal(new URL(page.url()).searchParams.has('lang'), false);
    assert.equal(await page.evaluate(() => localStorage.getItem('selectedLanguage')), null);
    const postReloadSchemas = state.schemaUrls.slice(state.schemaUrls.lastIndexOf('/api/v1/config/schema?lang=fr') + 1);
    assert.ok(postReloadSchemas.length > 0);
    assert.equal(postReloadSchemas.some((url) => new URL(url, 'http://panel.test').searchParams.has('lang')), false);
    if (scenario.haLanguage) assert.ok(postReloadSchemas.includes('/api/v1/config/schema?ha_lang=de-DE'));
  });
}

browserTest('Unrelated and refused sends preserve the supported browser language override', async (t) => {
  const state = { friendlyName: 'Panel', documents: 0, posts: [] };
  const schema = [
    localizedField('friendly_name', 'Friendly name', 'en', '', null),
    { key: 'ui_language', label: 'Interface language', labelLanguage: 'en', help: '', helpLanguage: null,
      group: 'System', type: 'ENUM', available: true, options: ['auto', 'en', 'de', 'fr', 'it', 'es', 'zh-Hans'] },
  ];
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') {
        const body = new URLSearchParams(await requestBody(request));
        state.posts.push(Object.fromEntries(body));
        if (body.has('ui_language')) return json({ error: 'save-refused', message: 'Locale save refused.' }, 500);
        state.friendlyName = body.get('friendly_name');
        return json({ ok: true, message: 'Saved.' });
      }
      return json({
        settings: { friendly_name: state.friendlyName, ui_language: 'auto' },
        ha_expose: {}, ha_auth: { configured: false },
      });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/health') return { body: 'ok cfg=locale-negative' };
  }, () => { state.documents++; return fixture({ 'configure.save.refused': '未保存：{reason}' }); });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(3_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.addInitScript(() => localStorage.setItem('selectedLanguage', JSON.stringify('fr')));
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });

  const renamed = page.waitForResponse((response) => response.url().endsWith('/api/v1/config') && response.request().method() === 'POST');
  await page.locator('#cfg-friendly_name input').fill('Renamed panel');
  await page.locator('#cfg-friendly_name input').press('Tab');
  await renamed;
  await page.waitForTimeout(300);
  assert.equal(await page.evaluate(() => localStorage.getItem('selectedLanguage')), '"fr"');
  assert.equal(state.documents, 1);
  assert.equal(await page.locator('#cfg-friendly_name input').inputValue(), 'Renamed panel');

  await page.locator('#cfg-ui_language select').selectOption('en');
  const refusal = page.locator('#cfg-ui_language .cfg-row-message');
  await refusal.getByText('未保存：Locale save refused.', { exact: true }).waitFor();
  await page.waitForFunction((text) => document.getElementById('cfg-msg').textContent === text, '未保存：Locale save refused.');
  assert.equal(await page.locator('#cfg-ui_language select').inputValue(), 'auto', 'the refused language goes back to the panel value');
  await page.waitForTimeout(300);
  assert.equal(await page.evaluate(() => localStorage.getItem('selectedLanguage')), '"fr"');
  assert.equal(state.documents, 1);
  assert.deepEqual(state.posts, [
    { friendly_name: 'Renamed panel' },
    { ui_language: 'en' },
  ]);
});

function autoSleepHistory({ included = true, label = 'Office ceiling motion', hours = 6, laneCount = 1, areaName = 'Office', areaKey = 'a'.repeat(64) } = {}) {
  const end = Date.now();
  const start = end - hours * 60 * 60 * 1000;
  return {
    available: true,
    hours,
    area_name: areaName,
    area_key: areaKey,
    window_start_epoch_ms: start,
    window_end_epoch_ms: end,
    segments: [{ start_epoch_ms: start, end_epoch_ms: end, output: included ? 'hold_awake' : 'allow_sleep' }],
    source_lanes: Array.from({ length: laneCount }, (_, index) => ({
      source_key: index.toString(16).padStart(64, 'b').slice(-64), label: index ? `${label} ${index + 1}` : label, included,
      segments: [{ start_epoch_ms: start, end_epoch_ms: end, state: included ? 'on' : 'off' }],
    })),
  };
}

function autoSleepHydrationSchema() {
  const field = (key, group, type = 'STRING', extra = {}) => ({
    key, group, type, label: key.replaceAll('_', ' '), available: true,
    options: type === 'ENUM' ? ['Off', 'On'] : [],
    ...extra,
  });
  return [
    field('panel_id', 'Identity'), field('friendly_name', 'Identity'), field('manufacturer', 'Identity'),
    field('model', 'Identity'), field('ha_area', 'Identity', 'STRING', { picker: 'ha_area' }),
    field('auto_sleep', 'Behaviour', 'BOOL'), field('navbar_mode', 'Behaviour', 'ENUM'),
    field('wake_on_wave', 'Behaviour', 'BOOL'), field('kiosk_lock', 'Behaviour', 'BOOL'),
    field('watchdog_enabled', 'Behaviour', 'BOOL'), field('touch_sound', 'Behaviour', 'BOOL'),
    field('silence_boot_chime', 'Behaviour', 'BOOL'),
    field('dark_mode', 'Display', 'BOOL'),
    field('auto_brightness_ha_entity', 'Display', 'STRING', { picker: 'ha_illuminance' }),
    field('auto_brightness', 'Display', 'BOOL'), field('auto_brightness_minimum_percent', 'Display', 'INT'),
    field('auto_brightness_response_percent', 'Display', 'INT'),
    field('cpu_governor', 'System', 'ENUM'), field('zigbee_router', 'System', 'BOOL'),
    field('prevent_idle_dim', 'Behaviour', 'BOOL'), field('keep_awake', 'Behaviour', 'BOOL'),
    field('dashboard_package', 'Dashboard', 'STRING', { picker: 'renderer' }),
    field('dashboard_entity_learning', 'Dashboard', 'BOOL'),
    field('home_dashboard', 'Dashboard', 'STRING', { picker: 'ha_dashboard' }),
    field('dashboard_fullscreen', 'Dashboard', 'BOOL'), field('dashboard_native_kiosk', 'Dashboard', 'BOOL'),
    field('dashboard_idle_return_min', 'Dashboard', 'INT'), field('ha_url', 'Dashboard'),
    field('ha_token', 'Dashboard', 'PASSWORD'), field('dashboard_zoom', 'Dashboard', 'INT'),
    field('launcher_package', 'System'),
    field('network_adb', 'System', 'BOOL'),
    field('log_ship_enabled', 'Logging', 'BOOL'), field('log_ship_host', 'Logging'),
    field('log_ship_port', 'Logging', 'INT'), field('log_ship_protocol', 'Logging', 'ENUM'),
    field('screen', 'Sensors', 'INT'), field('volume', 'Sensors', 'INT'), field('illuminance', 'Sensors', 'INT'),
    field('proximity', 'Sensors', 'BOOL'), field('proximity_level', 'Sensors', 'INT'),
    field('auto_sleep_activity', 'Sensors', 'BOOL'),
    field('diag_ip', 'Diagnostics'), field('diag_cpu', 'Diagnostics', 'INT'),
    field('diag_memory', 'Diagnostics', 'INT'), field('diag_soc_temp', 'Diagnostics', 'FLOAT'),
    field('diag_boot', 'Diagnostics'), field('diag_wifi_rssi', 'Diagnostics', 'INT'),
    field('diag_wifi_outages_24h', 'Diagnostics', 'INT'),
  ];
}

function autoSleepHydrationSettings(source) {
  return {
    panel_id: 'panel-example', friendly_name: 'Example panel', manufacturer: 'Panel maker', model: 'Panel model',
    ha_area: 'Office',
    ha_url: 'https://ha.example', ha_token: '', home_dashboard: '',
    dashboard_package: 'builtin', dashboard_zoom: '100', auto_sleep: 'true', touch_sound: 'false',
    auto_brightness: 'true', auto_brightness_ha_entity: source, auto_brightness_minimum_percent: '10',
    auto_brightness_response_percent: '50', log_ship_enabled: 'false',
  };
}

function localizedDashboardHydrationFixture(locale) {
  return screenshotFixture({ locale }).replace(
    '<body data-hardened="0">',
    '<body data-hardened="0" data-hydrate="1"><div id="bannerzone"></div>',
  );
}

function controlsFixture() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css">${i18nBridge()}</head><body data-hardened="0">
    <canvas id="perfchart" width="600" height="96"></canvas><canvas id="respchart" width="600" height="150"></canvas>
    <table id="perf"></table><table id="smtbl"></table><table id="streamtbl"></table><table id="topproc"></table><table id="noisyentities"></table>
    <small id="smhdr"></small><small id="perfage"></small><small id="sensage"></small><small id="insthdr"></small><table id="senstbl"></table><p id="insthint"></p>
    <div class="card" style="max-width:100%;padding:8px"><div id="ctlzone"><div class="ctlrow">
      <button class="pbtn" onclick="act('back')">←<span class="lbl"> Back</span></button>
      <button class="pbtn" onclick="act('recents')">▢<span class="lbl"> Recents</span></button>
      <button class="pbtn" onclick="act('launcher')">⊞<span class="lbl"> Launcher</span></button>
      <button class="pbtn" onclick="act('admin_launcher')">⚙<span class="lbl"> Admin launcher</span></button>
    </div><div class="ctlrow ctlrow-secondary">
      <button class="pbtn" onclick="act('dashboard')">⌂<span class="lbl"> Dashboard</span></button>
      <button class="pbtn" onclick="act('reload')">↻ Reload</button><button class="pbtn" onclick="act('reboot')">⟳ Reboot</button>
    </div></div></div>
    <div id="dashboard-cards"></div><script>window.CardColumnAlignment={attach:()=>()=>{}};</script><script src="/info.js"></script></body></html>`;
}

function cardMemoryFixture(cold) {
  return `<!doctype html><html><head><style>
    #dashboard-cards{width:820px}.card{box-sizing:border-box;width:400px;min-height:80px;border:1px solid transparent;padding:10px}
    .top-process-card tr{height:34px}
  </style>${i18nBridge()}</head><body data-hydrate="${cold ? 'cold' : '0'}" data-hardened="0">
    <div id="bannerzone"></div><div id="dashboard-cards" data-card-size-page="dashboard" data-card-size-epoch="1" data-card-size-restore="1">
      <div class="card" data-layout-key="panel-info"${cold ? '' : ' style="height:220px"'}><table id="infotbl"><tr><td>reading…</td></tr></table></div>
      <div class="card" data-layout-key="live-metrics">
        <canvas id="perfchart" width="600" height="96"></canvas><canvas id="respchart" width="600" height="150"></canvas>
        <table id="perf"></table><table id="smtbl"></table><table id="streamtbl"></table><table id="noisyentities"></table>
        <small id="smhdr"></small><small id="perfage"></small><small id="sensage"></small><small id="insthdr"></small>
        <table id="senstbl"></table><p id="insthint"></p><div id="ctlzone"></div>
      </div>
      <div class="card" data-layout-key="top-processes"><h2>Top processes</h2><table id="topproc"></table></div>
    </div>
    <script src="/assets/card-size-memory.js"></script><script>window.__alignmentCalls=0;window.CardColumnAlignment={attach:()=>()=>window.__alignmentCalls++};</script><script src="/info.js"></script>
  </body></html>`;
}

function configureCardMemoryFixture(compact) {
  return `<!doctype html><html><head><style>#cfg-groups{width:820px}.card{box-sizing:border-box;width:400px;min-height:80px;padding:10px}
    [data-config-group="Display"]{height:${compact ? '100' : '220'}px}</style>${i18nBridge()}</head><body>
    <button id="tab-basic"></button><button id="tab-adv"></button><div id="cfg-msg" class="sr-only" role="status" aria-live="polite" aria-atomic="true"></div><p id="cfg-status"></p>
    <div id="cfg-groups" data-card-size-page="configure" data-card-size-epoch="1" data-card-size-restore="1" data-card-size-proximity="0"></div>
    <script src="/assets/card-size-memory.js"></script><script>window.__alignmentCalls=0;window.CardColumnAlignment={attach:()=>()=>window.__alignmentCalls++};</script>
    ${ADVANCED_VIEW}<script src="/configure-state.js"></script><script src="/configure-view.js"></script><script src="/configure-help.js"></script><script src="/configure-controls.js"></script><script src="/configure-brightness.js"></script><script src="/configure-auto-sleep.js"></script><script src="/configure-cards.js"></script><script src="/configure-render.js"></script><script src="/configure.js"></script></body></html>`;
}

function installCardMemoryFixture(compact) {
  return `<!doctype html><html><head><style>#install-cards{width:820px}.card{box-sizing:border-box;width:400px;min-height:80px;padding:10px}
    [data-layout-key="managed-components"]{height:${compact ? '100' : '220'}px}</style>${i18nBridge()}</head><body>
    <div id="install-cards" data-card-size-page="install" data-card-size-epoch="1" data-card-size-restore="1">
      <div class="card" data-layout-key="managed-components"><div class="comprow" data-name="paneld"><span class="cver">0.9.6</span>
        <select class="cchan"><option value="stable">Stable</option></select><div class="cvsel choice-list">loading…</div>
        <a class="cnotes"></a><button class="cinstall" data-root="1"></button><a class="cdl"></a></div><p id="comp-msg"></p></div>
      <div class="card" id="radiocard" data-layout-key="radio-firmware" style="display:none"><span id="radio-status"></span><span id="radio-health"></span></div>
    </div><script src="/assets/card-size-memory.js"></script>
    <script>window.__alignmentCalls=0;window.CardColumnAlignment={attach:()=>()=>window.__alignmentCalls++};</script><script src="/install.js"></script>
  </body></html>`;
}

function powerSafetyFixture() {
  return `<!doctype html><html><head><meta charset="utf-8">${i18nBridge()}</head><body>
    <div class="notice" data-severity="action_needed" data-power-safety-banner><span class="notice-pill">Action needed</span><span class="notice-text"><b class="notice-title">Panel power safety: at risk</b> <span class="notice-detail">Panel power safety needs attention.</span></span><span class="notice-action"><form method="post" action="/api/v1/power-safety/repair" data-power-safety-repair style="display:inline"><button class="pbtn" type="submit" data-hardened-approval>Repair power safety</button> <span class="power-safety-repair-result" role="status" aria-live="polite"></span></form></span></div>
    <script src="/power-safety.js"></script>
  </body></html>`;
}

browserTest('Power safety partial repair shows the server result without reloading', async (t) => {
  const calls = [];
  let pageLoads = 0;
  const serverMessage = 'App guards are active; Android stay-awake still needs a manual change.';
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/power-safety/repair') {
      calls.push({ method: request.method, path });
      return json({
        status: 'partial',
        message: serverMessage,
        power_safety: { acknowledge_available: false },
      });
    }
  }, () => {
    pageLoads += 1;
    return powerSafetyFixture();
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: 'Repair power safety' }).click();
  await assert.doesNotReject(() => page.locator('.power-safety-repair-result').waitFor({ state: 'visible' }));
  await assert.doesNotReject(() => page.waitForFunction(
    (expected) => document.querySelector('.power-safety-repair-result')?.textContent === expected,
    serverMessage,
  ));
  await new Promise((resolve) => setTimeout(resolve, 1400));

  assert.deepEqual(calls, [{ method: 'POST', path: '/api/v1/power-safety/repair' }]);
  assert.equal(pageLoads, 1, 'partial repair reloaded the page');
  assert.equal(await page.locator('[data-power-safety-banner]').count(), 1);
  assert.equal(await page.getByRole('button', { name: 'Repair power safety' }).isEnabled(), true);
});

browserTest('Power safety partial repair offers and submits the exact acknowledgement fingerprint', async (t) => {
  const fingerprint = '0123456789abcdef'.repeat(4);
  const calls = [];
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/power-safety/repair') {
      calls.push({ method: request.method, path, body: await requestBody(request) });
      return json({
        status: 'partial',
        message: 'Automatic repair is complete; the remaining caution requires manual Android settings.',
        power_safety: {
          acknowledge_available: true,
          acknowledgement_fingerprint: fingerprint,
        },
      });
    }
    if (path === '/api/v1/power-safety/acknowledge') {
      calls.push({ method: request.method, path, body: await requestBody(request) });
      return json({ acknowledged: true, message: 'Caution hidden while this evidence remains unchanged.' });
    }
  }, powerSafetyFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: 'Repair power safety' }).click();

  const acknowledgeForm = page.locator('form[data-power-safety-acknowledge]');
  await acknowledgeForm.waitFor();
  assert.equal(await acknowledgeForm.locator('input[name="fingerprint"]').inputValue(), fingerprint);
  const hideButton = page.getByRole('button', { name: 'Hide this caution' });
  assert.equal(await hideButton.getAttribute('data-hardened-approval'), '');
  assert.equal(await hideButton.isEnabled(), true);

  await hideButton.click();
  await page.locator('[data-power-safety-banner]').waitFor({ state: 'detached' });

  assert.deepEqual(calls, [
    { method: 'POST', path: '/api/v1/power-safety/repair', body: '' },
    { method: 'POST', path: '/api/v1/power-safety/acknowledge', body: `fingerprint=${fingerprint}` },
  ]);
});

for (const scenario of [
  {
    name: '503 repair failure',
    responses: [{
      status: 503,
      body: { status: 'failed', message: 'The app-owned guard could not be verified.' },
    }],
    button: 'Repair power safety',
    message: 'The app-owned guard could not be verified.',
  },
  {
    name: '202 physical approval challenge',
    responses: [{
      status: 202,
      body: { error: 'approval-required', message: 'Approve this request physically on the panel.' },
    }],
    button: 'Repair power safety',
    message: 'Approve this request physically on the panel.',
  },
  {
    name: '409 stale acknowledgement',
    responses: [
      {
        status: 200,
        body: {
          status: 'partial',
          message: 'Only a manual Android guard remains.',
          power_safety: {
            acknowledge_available: true,
            acknowledgement_fingerprint: 'fedcba9876543210'.repeat(4),
          },
        },
      },
      {
        status: 409,
        body: { acknowledged: false, message: 'Power-safety evidence changed; review the current caution.' },
      },
    ],
    button: 'Hide this caution',
    message: 'Power-safety evidence changed; review the current caution.',
  },
]) {
  browserTest(`Power safety ${scenario.name} keeps the banner and re-enables its action`, async (t) => {
    let requestIndex = 0;
    const harness = await startHarness(async (path) => {
      if (path !== '/api/v1/power-safety/repair' && path !== '/api/v1/power-safety/acknowledge') return;
      const response = scenario.responses[requestIndex++];
      return json(response.body, response.status);
    }, powerSafetyFixture);
    const browser = await chromium.launch({ executablePath: chrome, headless: true });
    const page = await browser.newPage();
    t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

    await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
    await page.getByRole('button', { name: 'Repair power safety' }).click();
    if (scenario.responses.length > 1) {
      await page.getByRole('button', { name: 'Hide this caution' }).click();
    }
    await page.waitForFunction(
      (expected) => document.querySelector('[data-power-safety-banner] [role="status"]')?.textContent === expected,
      scenario.message,
    );

    assert.equal(await page.locator('[data-power-safety-banner]').count(), 1);
    assert.equal(await page.getByRole('button', { name: scenario.button }).isEnabled(), true);
    assert.equal(requestIndex, scenario.responses.length);
  });
}

browserTest('Remote Controls keep Dashboard and Reload labelled, tappable and wired at a narrow panel width', async (t) => {
  const calls = [];
  let actionsReceived;
  const bothActions = new Promise((resolve) => { actionsReceived = resolve; });
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/action') {
      calls.push({ method: request.method, body: await requestBody(request) });
      if (calls.length === 2) actionsReceived();
      return { status: 202, body: 'accepted\n' };
    }
    if (path === '/api/v1/perf') return json({});
    if (path === '/api/v1/sensors') return json({});
    if (path === '/api/v1/inspect') return json({ status: 'needs-root', running: false, port: 9222 });
  }, controlsFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 360, height: 720 } });
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
  for (const width of [360, 480]) {
    await page.setViewportSize({ width, height: 720 });
    const geometry = await page.locator('#ctlzone').evaluate((zone) => {
      document.documentElement.style.fontSize = '20px'; fitControls();
      return {
        overflow: zone.scrollWidth > zone.clientWidth,
        collapsed: zone.querySelector('.ctlrow').classList.contains('collapsed'),
        buttons: Array.from(zone.querySelectorAll('.pbtn')).map((button) => {
          const rect = button.getBoundingClientRect();
          return { text: button.textContent.trim(), width: rect.width, height: rect.height };
        }),
        secondaryButtons: Array.from(zone.querySelectorAll('.ctlrow-secondary .pbtn')).map((button) => button.textContent.trim()),
      };
    });

    assert.equal(geometry.overflow, false, `${width}px Controls must not introduce horizontal scrolling`);
    assert.equal(geometry.collapsed, false, `${width}px Controls keep their action labels visible`);
    assert.ok(geometry.buttons.every((button) => button.width >= 48 && button.height >= 48), `every ${width}px narrow control needs a 48x48px touch target: ${JSON.stringify(geometry.buttons)}`);
    assert.deepEqual(geometry.secondaryButtons.slice(0, 2), ['⌂ Dashboard', '↻ Reload']);
    assert.ok(geometry.buttons.every((button) => !button.text.startsWith('Vol ')), 'volume actions are absent from the Controls card');
  }

  await page.getByRole('button', { name: '⌂ Dashboard', exact: true }).click();
  await page.getByRole('button', { name: '↻ Reload', exact: true }).click();
  await bothActions;
  assert.deepEqual(calls, [
    { method: 'POST', body: 'a=dashboard' },
    { method: 'POST', body: 'a=reload' },
  ]);
});

browserTest('Top processes switches between CPU and resident RAM rankings', async (t) => {
  const routes = screenshotRoutes({ perf: {
    top: [{ name: 'cpu-heavy', cpu: 61.2 }],
    topRam: [{ name: 'memory-heavy', ramMb: 128.5 }],
    hist: { cpu: [], ram: [], gpu: [] },
  } });
  const harness = await startHarness(routes, screenshotFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(1_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });

  await assert.doesNotReject(page.locator('#topproc').getByText('cpu-heavy').waitFor());
  assert.equal(await page.locator('#topproc th.num').textContent(), '% CPU');
  assert.equal(await page.locator('.top-process-mode[data-mode="cpu"]').getAttribute('aria-pressed'), 'true');

  await page.locator('.top-process-mode[data-mode="ram"]').click();
  await assert.doesNotReject(page.locator('#topproc').getByText('memory-heavy').waitFor());
  assert.equal(await page.locator('#topproc th.num').textContent(), 'RAM');
  assert.equal(await page.locator('#topproc td.num').textContent(), '128.5 MB');
  assert.equal(await page.locator('.top-process-mode[data-mode="ram"]').getAttribute('aria-pressed'), 'true');
  assert.equal(await page.locator('.top-process-mode[data-mode="cpu"]').getAttribute('aria-pressed'), 'false');

  await page.locator('.top-process-mode[data-mode="cpu"]').click();
  await assert.doesNotReject(page.locator('#topproc').getByText('cpu-heavy').waitFor());
  assert.equal(await page.locator('#topproc th.num').textContent(), '% CPU');
  assert.equal(await page.locator('.top-process-mode[data-mode="cpu"]').getAttribute('aria-pressed'), 'true');
  assert.equal(await page.locator('.top-process-mode[data-mode="ram"]').getAttribute('aria-pressed'), 'false');
});

browserTest('Dashboard hydration forwards only an admitted explicit locale', async (t) => {
  for (const [locale, expected] of [
    ['zh-Hans', '/api/v1/info?lang=zh-Hans'],
    ['en', '/api/v1/info?lang=en'],
    ['zh-Hans?theme=dark', '/api/v1/info'],
  ]) {
    const requests = [];
    const harness = await startHarness((path, request) => {
      if (path === '/api/v1/info') {
        requests.push(request.url);
        return json({ banners: '', cards: {}, controls: '', shot: false, shotCached: '' });
      }
      if (path === '/api/v1/perf') return json({ hist: { cpu: [], ram: [], gpu: [] } });
      if (path === '/api/v1/sensors') return json({});
      if (path === '/api/v1/inspect') return json({ status: 'needs-root', running: false, port: 9222 });
    }, () => localizedDashboardHydrationFixture(locale));
    const browser = await chromium.launch({ executablePath: chrome, headless: true });
    const page = await browser.newPage();
    try {
      await page.goto(harness.url + '/?theme=dark&unrelated=1', { waitUntil: 'domcontentloaded', timeout: 5_000 });
      await page.waitForFunction(() => document.body.getAttribute('data-hydrate') === '1' && document.querySelector('#topproc'));
      await new Promise((resolve) => setTimeout(resolve, 100));
      assert.deepEqual(requests, [expected]);
    } finally {
      await browser.close();
      await new Promise((resolve) => harness.server.close(resolve));
    }
  }
});

browserTest('Top processes explains when resident RAM is unavailable from an older helper', async (t) => {
  const routes = screenshotRoutes({ perf: {
    top: [{ name: 'cpu-heavy', cpu: 20 }], topRam: null, hist: { cpu: [], ram: [], gpu: [] },
  } });
  const harness = await startHarness(routes, screenshotFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(1_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('.top-process-mode[data-mode="ram"]').click();

  await assert.doesNotReject(page.locator('#topproc').getByText('RAM data unavailable').waitFor());
  assert.equal(await page.locator('#topproc tr').count(), 2);
});

browserTest('Configure help wraps a frozen URL without applying break-all globally', async (t) => {
  const help = 'Built-in renderer: Home Assistant base URL, e.g. http://homeassistant.local:8123. Blank disables it.';
  const schema = [{
    key: 'ha_url', label: 'Home Assistant URL', help, group: 'Dashboard', tier: 'BASIC',
    type: 'STRING', available: true,
  }];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { ha_url: '' }, ha_expose: {}, ha_auth: {} });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/health') return { body: 'ok cfg=test' };
    return null;
  }, configureVisualFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 1361, height: 720 } });
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
  await page.locator('#cfg-ha_url .info-btn').click();
  const helpNode = page.locator('#cfg-help-body', { hasText: help });
  await helpNode.waitFor();
  const layout = await helpNode.evaluate((node) => {
    const ordinary = document.createElement('p');
    ordinary.textContent = 'ordinary prose';
    document.body.appendChild(ordinary);
    const helpStyle = getComputedStyle(node);
    const ordinaryStyle = getComputedStyle(ordinary);
    return {
      helpClientWidth: node.clientWidth,
      helpScrollWidth: node.scrollWidth,
      helpOverflowWrap: helpStyle.overflowWrap,
      helpWordBreak: helpStyle.wordBreak,
      ordinaryOverflowWrap: ordinaryStyle.overflowWrap,
      ordinaryWordBreak: ordinaryStyle.wordBreak,
    };
  });

  assert.equal(layout.helpScrollWidth, layout.helpClientWidth, `Configure help must not overflow: ${JSON.stringify(layout)}`);
  assert.equal(layout.helpOverflowWrap, 'anywhere');
  assert.equal(layout.helpWordBreak, 'normal');
  assert.equal(layout.ordinaryOverflowWrap, 'normal');
  assert.equal(layout.ordinaryWordBreak, 'normal');
});

browserTest('Configure exposure-linked native selects stay inside a 1361px viewport card', async (t) => {
  const schema = [
    { key: 'navbar_mode', label: '导航栏模式', help: '选择导航栏的显示方式。', labelLanguage: 'zh-Hans', helpLanguage: 'zh-Hans', group: 'Behaviour', tier: 'BASIC', type: 'ENUM', options: ['auto', 'visible', 'hidden'], available: true, ha: true },
    { key: 'cpu_governor', label: 'CPU 调速器', help: '选择系统性能策略。', labelLanguage: 'zh-Hans', helpLanguage: 'zh-Hans', group: 'System', tier: 'ADVANCED', type: 'ENUM', options: ['ondemand', 'performance', 'powersave'], available: true, ha: true },
  ];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({
      settings: { navbar_mode: 'auto', cpu_governor: 'ondemand' },
      ha_expose: { navbar_mode: true, cpu_governor: true }, ha_auth: {},
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/health') return { body: 'ok cfg=test' };
    return null;
  }, configureVisualFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 1361, height: 851 } });
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
  for (const key of ['navbar_mode', 'cpu_governor']) {
    const row = page.locator(`#cfg-${key}`);
    await row.waitFor();
    const geometry = await row.evaluate((node) => {
      const control = node.querySelector('.fctl');
      const select = control.querySelector('select');
      return {
        rowClientWidth: node.clientWidth, rowScrollWidth: node.scrollWidth,
        controlClientWidth: control.clientWidth, controlScrollWidth: control.scrollWidth,
        selectWidth: select.getBoundingClientRect().width,
        hasExposureControl: !!control.querySelector('.pip'),
      };
    });
    assert.equal(geometry.hasExposureControl, true);
    assert.ok(geometry.selectWidth > 0, `${key} retains a usable select: ${JSON.stringify(geometry)}`);
    assert.ok(geometry.controlScrollWidth <= geometry.controlClientWidth + 1, `${key} control must not overflow: ${JSON.stringify(geometry)}`);
    assert.ok(geometry.rowScrollWidth <= geometry.rowClientWidth + 1, `${key} row must not overflow: ${JSON.stringify(geometry)}`);
  }
});

browserTest('Configure refuses an invalid brightness floor without sending it and shows the panel value again', async (t) => {
  let configPosts = 0;
  const schema = [
    { key: 'auto_brightness', label: 'Adaptive brightness', group: 'Display', type: 'BOOL', available: true },
    { key: 'auto_brightness_minimum_percent', label: 'Minimum brightness', group: 'Display', type: 'INT', min: 4, max: 99, available: true },
  ];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') { configPosts++; return json({}); }
      return json({ settings: { auto_brightness: 'true', auto_brightness_minimum_percent: '12' }, ha_expose: {}, ha_auth: {} });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/auto-brightness') return json({ available: true, state: 'learning', sourceAvailable: true, latestLux: 42 });
    if (path.startsWith('/api/v1/auto-brightness/history')) return json({ points: [], bucket_minutes: 5 });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/health') return { body: 'ok cfg=test' };
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(1_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const floor = page.locator('#cfg-auto_brightness_minimum_percent input');
  await floor.fill('2');
  await floor.press('Tab');
  const rule = 'Minimum brightness must be a whole number between 4 and 99.';
  await page.locator('#cfg-auto_brightness_minimum_percent .cfg-row-message').getByText(rule, { exact: true }).waitFor();
  await page.waitForFunction((text) => document.getElementById('cfg-msg').textContent === text, rule);
  assert.equal(await floor.inputValue(), '12', 'the panel value comes back');
  await assert.rejects(page.waitForRequest((request) => request.url().endsWith('/api/v1/config') && request.method() === 'POST', { timeout: 200 }), /Timeout/);
  assert.equal(configPosts, 0);
});

browserTest('Configure renderer picker reflects the installed Companion catalogue across a send and reload', async (t) => {
  const full = { pkg: 'io.homeassistant.companion.android', label: 'Home Assistant Companion (full)' };
  const minimal = { pkg: 'io.homeassistant.companion.android.minimal', label: 'Home Assistant Companion (minimal)' };
  const arbitrary = { pkg: 'com.example.launchable', label: 'Unrelated launcher' };
  const schema = [
    { key: 'dashboard_package', label: 'Dashboard app', group: 'Dashboard', type: 'STRING', picker: 'renderer', placeholder: 'Auto', available: true },
  ];
  let dashboardPackage = 'builtin';
  let renderers = [];
  const posts = [];
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') {
        const params = new URLSearchParams(await requestBody(request));
        posts.push(Object.fromEntries(params));
        if (params.has('dashboard_package')) dashboardPackage = params.get('dashboard_package');
        return json({ ok: true, status: 'applied', applied: ['dashboard_package'], pending: [], message: 'Saved.' });
      }
      return json({ settings: { dashboard_package: dashboardPackage }, ha_expose: {}, ha_auth: {} });
    }
    if (path === '/api/v1/apps') return json({ apps: [arbitrary], renderers });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/discovery') return json({});
    if (path === '/health') return { body: 'ok cfg=renderer-picker' };
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(3_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  async function pickerOptions() {
    await page.locator('#cfg-dashboard_package select').waitFor();
    return page.locator('#cfg-dashboard_package select option').evaluateAll((options) =>
      options.map((option) => ({ value: option.value, label: option.textContent, selected: option.selected })));
  }
  async function assertCatalogue(nextRenderers, expected) {
    renderers = nextRenderers;
    await page.reload({ waitUntil: 'domcontentloaded', timeout: 5_000 });
    assert.deepEqual(await pickerOptions(), expected);
  }
  const auto = { value: '', label: 'Auto', selected: false };
  const builtin = { value: 'builtin', label: 'Built-in renderer (Panel Assistant)', selected: true };
  const fullOption = { value: full.pkg, label: full.label, selected: false };
  const minimalOption = { value: minimal.pkg, label: minimal.label, selected: false };

  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  assert.deepEqual(await pickerOptions(), [auto, builtin], 'neither Companion variant installed');
  assert.equal(await page.locator('.companion-renderer-hint').count(), 0, 'Auto has no Companion-only hint');
  await assertCatalogue([full], [auto, builtin, fullOption]);
  await assertCatalogue([minimal], [auto, builtin, minimalOption]);
  await assertCatalogue([full, minimal], [auto, builtin, fullOption, minimalOption]);
  assert.equal(await page.locator(`option[value="${arbitrary.pkg}"]`).count(), 0,
    'an arbitrary launchable app must not become a renderer');

  const companionSaved = page.waitForResponse((response) => response.url().endsWith('/api/v1/config') && response.request().method() === 'POST');
  await page.locator('#cfg-dashboard_package select').selectOption(minimal.pkg);
  await page.locator('.companion-renderer-hint').getByText("Turn on the Companion app's launcher option for it to take Home.").waitFor();
  assert.equal(await page.locator('.companion-renderer-hint').count(), 1, 'the selected Companion shows one hint');
  await companionSaved;
  assert.deepEqual(posts, [{ dashboard_package: minimal.pkg }], 'choosing the renderer sends it once');
  await page.reload({ waitUntil: 'domcontentloaded', timeout: 5_000 });
  assert.equal(await page.locator('#cfg-dashboard_package select').inputValue(), minimal.pkg,
    'saved Companion survives a page reload');

  const builtinSaved = page.waitForResponse((response) => response.url().endsWith('/api/v1/config') && response.request().method() === 'POST');
  await page.locator('#cfg-dashboard_package select').selectOption('builtin');
  assert.equal(await page.locator('.companion-renderer-hint').count(), 0, 'Built-in has no Companion-only hint');
  await builtinSaved;
  assert.deepEqual(posts.at(-1), { dashboard_package: 'builtin' });
  assert.equal(posts.length, 2);
  await page.reload({ waitUntil: 'domcontentloaded', timeout: 5_000 });
  assert.equal(await page.locator('#cfg-dashboard_package select').inputValue(), 'builtin',
    'switching back to Built-in survives a page reload');
});

browserTest('Configure retains an explicit renderer when the app catalogue fails', async (t) => {
  const minimal = 'io.homeassistant.companion.android.minimal';
  const thirdParty = 'com.example.wallpanel';
  const schema = [
    { key: 'dashboard_package', label: 'Dashboard app', group: 'Dashboard', type: 'STRING', picker: 'renderer', placeholder: 'Auto', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'Identity', type: 'STRING', available: true },
  ];
  let dashboardPackage = minimal;
  let friendlyName = 'Wall panel';
  const posts = [];
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') {
        const params = new URLSearchParams(await requestBody(request));
        posts.push(Object.fromEntries(params));
        if (params.has('dashboard_package')) dashboardPackage = params.get('dashboard_package');
        if (params.has('friendly_name')) friendlyName = params.get('friendly_name');
        return json({ ok: true, status: 'applied', applied: [...params.keys()], pending: [], message: 'Saved.' });
      }
      return json({ settings: { dashboard_package: dashboardPackage, friendly_name: friendlyName }, ha_expose: {}, ha_auth: {} });
    }
    if (path === '/api/v1/apps') return { status: 500, headers: { 'content-type': 'text/plain' }, body: 'catalogue unavailable' };
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/discovery') return json({});
    if (path === '/health') return { body: 'ok cfg=renderer-catalogue-failure' };
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(3_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  assert.equal(await page.locator('#cfg-dashboard_package select').inputValue(), minimal,
    'a configured supported Companion remains selected when its catalogue cannot be queried');
  assert.deepEqual(
    await page.locator('#cfg-dashboard_package select option').evaluateAll((options) => options.map((option) => option.value)),
    ['', 'builtin', minimal],
  );

  dashboardPackage = thirdParty;
  await page.reload({ waitUntil: 'domcontentloaded', timeout: 5_000 });
  assert.equal(await page.locator('#cfg-dashboard_package select').inputValue(), thirdParty,
    'a configured third-party renderer remains selected');
  await page.locator('#cfg-friendly_name input').fill('Example device');
  const saved = page.waitForResponse((response) => response.url().endsWith('/api/v1/config') && response.request().method() === 'POST');
  await page.locator('#cfg-friendly_name input').press('Tab');
  await saved;
  await page.waitForTimeout(300);
  assert.deepEqual(posts, [{ friendly_name: 'Example device' }],
    'an unrelated send must not erase the renderer hidden by a failed catalogue');
  assert.equal(await page.locator('#cfg-dashboard_package select').inputValue(), thirdParty);
  assert.equal(dashboardPackage, thirdParty);
});

browserTest('Sensitivity preview survives transient HA source loss after its send', async (t) => {
  const source = 'sensor.office_illuminance';
  const revision = 'ambient-source-revision';
  const nowMinute = Math.floor(Date.now() / 60000);
  const localDay = new Date(nowMinute * 60000).toISOString().slice(0, 10);
  const sensitivityQueries = [];
  let sensitivity = '50';
  let transientlyUnavailable = false;
  const schema = [
    { key: 'auto_brightness_ha_entity', label: 'Ambient source', group: 'Display', type: 'STRING', available: true },
    { key: 'auto_brightness', label: 'Adaptive brightness', group: 'Display', type: 'BOOL', available: true },
    { key: 'auto_brightness_response_percent', label: 'Sensitivity', group: 'Display', type: 'INT', min: 0, max: 100, available: true },
  ];
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') {
        sensitivity = new URLSearchParams(await requestBody(request)).get('auto_brightness_response_percent');
        transientlyUnavailable = true;
        return json({ ok: true });
      }
      return json({ settings: {
        auto_brightness_ha_entity: source,
        auto_brightness: 'true',
        auto_brightness_response_percent: sensitivity,
      }, ha_expose: {}, ha_auth: {} });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/auto-brightness') return json({
      available: true, state: 'learning', sourceAvailable: !transientlyUnavailable,
      localSourcePresent: false, entityId: source,
      latestLux: transientlyUnavailable ? null : 42, sourceRevision: revision,
    });
    if (path === '/api/v1/auto-brightness/history') {
      const projected = Number(new URL(request.url, 'http://panel.test').searchParams.get('sensitivity'));
      sensitivityQueries.push(projected);
      return json({ sensitivity: projected, sourceRevision: revision, latestEpochMinute: nowMinute, bucket_minutes: 5, points: [{
        epochMinute: nowMinute, localDay, minuteOfDay: 720, dayAge: 0,
        observedMeanLux: 42, minLux: 40, maxLux: 44, expectedLux: 30,
        proposedBrightness: projected <= 10 ? 120 : 180,
      }] });
    }
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/health') return { body: 'ok cfg=test' };
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#auto-brightness-learning').waitFor();

  // Typing previews the curve; Enter commits the field and sends it.
  const sent = page.waitForResponse((response) => response.url().endsWith('/api/v1/config') && response.request().method() === 'POST');
  await page.locator('#cfg-auto_brightness_response_percent input').fill('10');
  await page.locator('#cfg-auto_brightness_response_percent input').press('Enter');
  await sent;
  for (let attempt = 0; attempt < 40 && !sensitivityQueries.includes(10); attempt++) await page.waitForTimeout(25);
  assert.ok(sensitivityQueries.includes(10), `missing live sensitivity preview: ${sensitivityQueries}`);
  await page.locator('#auto-brightness-learning').waitFor();
  await page.locator('#auto-brightness-learning').getByText(`Waiting for ambient light… · Source: ${source}`).waitFor();
  assert.equal(await page.locator('#auto-brightness-learning').count(), 1);
  assert.equal(await page.locator('[data-config-group="Display"]').count(), 1);
  assert.equal(sensitivity, '10');
});
browserTest('Configure shows a localized approval challenge on the row and puts the panel value back', async (t) => {
  const schema = [{ key: 'ha_url', label: 'Home Assistant URL', group: 'Dashboard', type: 'STRING', available: true }];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') return json({ error: 'approval-required', message: 'Approve this change on the panel.' }, 202);
      return json({ settings: { ha_url: 'http://ha.local:8123' }, ha_expose: {}, ha_auth: {} });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
  }, () => fixture({ 'configure.approval.retry': '请在面板上批准此请求，然后重试。' }));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(1_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const address = page.locator('#cfg-ha_url input');
  await address.fill('http://new-ha.local:8123');
  await address.press('Tab');
  const challenge = '请在面板上批准此请求，然后重试。';
  await page.locator('#cfg-ha_url .cfg-row-message').getByText(challenge, { exact: true }).waitFor();
  await page.waitForFunction((text) => document.getElementById('cfg-msg').textContent === text, challenge);
  assert.equal(await address.inputValue(), 'http://ha.local:8123', 'an unapproved change is not shown as the panel value');
});

browserTest('Configure preserves the selected locale in its Display Sizing deep link', async (t) => {
  const schema = [{
    key: 'dashboard_zoom', label: '缩放', help: '帮助', helpLanguage: 'zh-Hans', group: 'Dashboard',
    type: 'INT', min: 50, max: 200, available: true, displaySizingAvailable: true,
  }];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { dashboard_zoom: '100' }, ha_expose: {}, ha_auth: {} });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
  }, () => fixture({ 'configure.display.sizing': '显示尺寸' }, 'zh-Hans'));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#cfg-dashboard_zoom .info-btn').click();
  assert.equal(await page.getByRole('link', { name: '显示尺寸' }).getAttribute('href'), 'install?lang=zh-Hans#cfg-display');
});

browserTest('Configure localizes unavailable adaptive brightness without displaying backend prose', async (t) => {
  const schema = [{ key: 'auto_brightness', label: '自动亮度', group: 'Display', type: 'BOOL', available: true }];
  const rawDetail = 'Adaptive brightness runtime is not connected.';
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_brightness: 'true' }, ha_expose: {}, ha_auth: {} });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-brightness') return json({ available: false, localSourcePresent: true, sourceRevision: 1, detail: rawDetail });
    if (path === '/api/v1/auto-brightness/history') return json({ points: [], sourceRevision: 1 });
  }, () => fixture({ 'configure.brightness.runtime_unavailable': '自适应亮度运行时不可用。' }, 'zh-Hans'));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.getByText('自适应亮度运行时不可用。', { exact: true }).waitFor();
  assert.equal((await page.locator('body').textContent()).includes(rawDetail), false);
});

browserTest('Unrelated Configure sends preserve the connected HA identity without probing again', async (t) => {
  let statusGets = 0;
  const schema = [
    { key: 'ha_url', label: 'Home Assistant URL', group: 'Dashboard', type: 'STRING', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') return json({});
      return json({
        settings: { ha_url: 'https://ha.example', friendly_name: 'Alpha' },
        ha_expose: {},
        ha_auth: { configured: true, oauth: true },
      });
    }
    if (path === '/api/v1/ha/oauth/status') {
      statusGets++;
      return json({ phase: 'connected', display_name: 'Panel User' });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/health') return { body: 'ok cfg=auth-status-test' };
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(1_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#cfg-ha-oauth').getByText('Connected as Panel User').waitFor();
  assert.equal(statusGets, 1);

  await page.locator('#cfg-friendly_name input').fill('Example panel');
  const saved = page.waitForResponse((response) => response.url().endsWith('/api/v1/config') && response.request().method() === 'POST');
  await page.locator('#cfg-friendly_name input').press('Tab');
  await saved;
  await page.locator('#cfg-ha-oauth').getByText('Connected as Panel User').waitFor();
  await page.waitForTimeout(100);
  assert.equal(statusGets, 1);
});

browserTest('Dashboard restores card height before delayed cold hydration then stores fresh geometry', async (t) => {
  let cold = false;
  let releaseHydration;
  const hydrationGate = new Promise((resolve) => { releaseHydration = resolve; });
  let releaseLiveCards;
  const liveCardsGate = new Promise((resolve) => { releaseLiveCards = resolve; });
  const harness = await startHarness(async (path) => {
    if (path === '/api/v1/info') {
      await hydrationGate;
      return json({ banners: '', shot: false, controls: '', cards: { infotbl: '<tr><td>private-hydrated-sentinel</td></tr>' } });
    }
    if (path === '/api/v1/perf') { await liveCardsGate; return json({ top: [{ name: 'steady-process', cpu: 12 }] }); }
    if (path === '/api/v1/sensors') { await liveCardsGate; return json({}); }
    if (path === '/api/v1/inspect') { await liveCardsGate; return json({ status: 'needs-root', running: false, port: 9222 }); }
  }, () => cardMemoryFixture(cold));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
  page.setDefaultTimeout(3_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.waitForTimeout(1400);
  assert.equal(await page.evaluate(() => localStorage.getItem('ha-paneld.card-sizes.v1.dashboard')), null);
  releaseLiveCards();
  await page.waitForFunction(() => {
    const raw = localStorage.getItem('ha-paneld.card-sizes.v1.dashboard');
    return raw && JSON.parse(raw).cards['panel-info'] === 220;
  });
  cold = true;
  await page.reload({ waitUntil: 'domcontentloaded', timeout: 5_000 });
  assert.equal(await page.locator('[data-layout-key="panel-info"]').evaluate((card) => card.style.minHeight), '220px');
  assert.equal(await page.locator('[data-layout-key="panel-info"]').getAttribute('data-card-size-hint'), '220');

  releaseHydration();
  await page.getByText('private-hydrated-sentinel').waitFor();
  const alignmentBeforeRelease = await page.evaluate(() => window.__alignmentCalls);
  await page.waitForFunction(() => document.querySelector('[data-layout-key="panel-info"]').style.minHeight === '');
  assert.ok(await page.evaluate(() => window.__alignmentCalls) > alignmentBeforeRelease);
  const stored = await page.evaluate(() => localStorage.getItem('ha-paneld.card-sizes.v1.dashboard'));
  assert.equal(stored.includes('private-hydrated-sentinel'), false);
  const parsed = JSON.parse(stored);
  assert.equal(typeof parsed.cards['panel-info'], 'number');
  assert.ok(parsed.cards['panel-info'] >= 48 && parsed.cards['panel-info'] < 220);

  cold = false;
  await page.reload({ waitUntil: 'domcontentloaded', timeout: 5_000 });
  assert.equal(await page.locator('[data-layout-key="panel-info"]').evaluate((card) => card.style.minHeight), `${parsed.cards['panel-info']}px`);
});

browserTest('Dashboard retains remembered Top processes height until restarted sampler has CPU rows', async (t) => {
  let topAvailable = false;
  const topRows = Array.from({ length: 6 }, (_, index) => ({ name: `process-${index + 1}`, cpu: 20 - index }));
  const harness = await startHarness(async (path) => {
    if (path === '/api/v1/perf') return json({ top: topAvailable ? topRows : null });
    if (path === '/api/v1/sensors') return json({});
    if (path === '/api/v1/inspect') return json({ status: 'needs-root', running: false, port: 9222 });
  }, () => cardMemoryFixture(false));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
  page.setDefaultTimeout(5_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.addInitScript(() => {
    localStorage.setItem('ha-paneld.card-sizes.v1.dashboard', JSON.stringify({
      schema: 1, epoch: 1, savedAt: Date.now(),
      context: { columns: 2, cardWidth: 400, rootFont: 16, dpr: 1 },
      cards: { 'top-processes': 260 },
    }));
  });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const card = page.locator('[data-layout-key="top-processes"]');
  assert.equal(await card.evaluate((node) => node.style.minHeight), '260px');
  await page.waitForTimeout(1_600);
  assert.equal(await card.getAttribute('data-card-size-hint'), '260');
  assert.equal(await card.evaluate((node) => node.style.minHeight), '260px');

  topAvailable = true;
  await page.evaluate(() => perf());
  await page.locator('#topproc').getByText('process-6').waitFor();
  await page.waitForFunction(() => !document.querySelector('[data-layout-key="top-processes"]').hasAttribute('data-card-size-hint'));
  const stored = JSON.parse(await page.evaluate(() => localStorage.getItem('ha-paneld.card-sizes.v1.dashboard')));
  const steadyHeight = await card.evaluate((node) => Math.round(node.getBoundingClientRect().height));
  assert.ok(steadyHeight > 80);
  assert.equal(stored.cards['top-processes'], steadyHeight);
});

browserTest('Dashboard eventually releases Top processes hint when CPU ranking stays unavailable', async (t) => {
  const harness = await startHarness(async (path) => {
    if (path === '/api/v1/perf') return json({ top: null });
    if (path === '/api/v1/sensors') return json({});
    if (path === '/api/v1/inspect') return json({ status: 'needs-root', running: false, port: 9222 });
  }, () => cardMemoryFixture(false));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
  page.setDefaultTimeout(5_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.addInitScript(() => {
    localStorage.setItem('ha-paneld.card-sizes.v1.dashboard', JSON.stringify({
      schema: 1, epoch: 1, savedAt: Date.now(),
      context: { columns: 2, cardWidth: 400, rootFont: 16, dpr: 1 },
      cards: { 'top-processes': 260 },
    }));
  });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const card = page.locator('[data-layout-key="top-processes"]');
  assert.equal(await card.getAttribute('data-card-size-hint'), '260');
  // Each `perf` family value is one reading; drive eleven of them (the floor would otherwise space them 2 s apart).
  await page.evaluate(async () => { for (let poll = 0; poll < 11; poll++) perf({ top: null }, 'known'); });
  await page.waitForFunction(() => !document.querySelector('[data-layout-key="top-processes"]').hasAttribute('data-card-size-hint'));
  assert.equal(await card.evaluate((node) => node.style.minHeight), `${await card.evaluate((node) => node._hwm)}px`);
});

browserTest('Configure reapplies remembered card heights across its client-side render cycle', async (t) => {
  let compact = false, releaseCold;
  let coldGate = Promise.resolve();
  const harness = await startHarness(async (path) => {
    if (compact && ['/api/v1/radio', '/api/v1/auto-brightness', '/api/v1/auto-brightness/history'].includes(path)) await coldGate;
    if (path === '/api/v1/config/schema') return json([{ key: 'dark_mode', type: 'BOOL', group: 'Display', label: 'Dark mode', available: true }]);
    if (path === '/api/v1/config') return json({ settings: { dark_mode: false }, ha_expose: {}, ha_auth: {} });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/auto-brightness') return json({ available: false, sourceRevision: 1 });
    if (path === '/api/v1/auto-brightness/history') return json({ points: [], sourceRevision: 1 });
  }, () => configureCardMemoryFixture(compact));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => JSON.parse(localStorage.getItem('ha-paneld.card-sizes.v1.configure.advanced') || '{}').cards?.['configure-display'] === 220);
  compact = true; coldGate = new Promise((resolve) => { releaseCold = resolve; });
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.locator('[data-layout-key="configure-display"]').waitFor();
  assert.equal(await page.locator('[data-layout-key="configure-display"]').evaluate((card) => card.style.minHeight), '220px');
  releaseCold();
  await page.waitForFunction(() => document.querySelector('[data-layout-key="configure-display"]').style.minHeight === '');
  await page.waitForFunction(() => JSON.parse(localStorage.getItem('ha-paneld.card-sizes.v1.configure.advanced')).cards['configure-display'] === 100);
});

browserTest('Install holds remembered heights until its initial version and radio loads settle', async (t) => {
  let compact = false, releaseCold;
  let coldGate = Promise.resolve();
  const harness = await startHarness(async (path) => {
    if (compact && ['/api/v1/radio', '/api/v1/install/versions'].includes(path)) await coldGate;
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/install/versions') return json({ versions: [{ tag: 'v0.9.6', version: '0.9.6', installable: true, action: 'Install' }] });
  }, () => installCardMemoryFixture(compact));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => JSON.parse(localStorage.getItem('ha-paneld.card-sizes.v1.install') || '{}').cards?.['managed-components'] === 220);
  compact = true; coldGate = new Promise((resolve) => { releaseCold = resolve; });
  await page.reload({ waitUntil: 'domcontentloaded' });
  assert.equal(await page.locator('[data-layout-key="managed-components"]').evaluate((card) => card.style.minHeight), '220px');
  await page.waitForTimeout(1400);
  assert.equal(await page.locator('[data-layout-key="managed-components"]').evaluate((card) => card.style.minHeight), '220px');
  releaseCold();
  await page.waitForFunction(() => document.querySelector('[data-layout-key="managed-components"]').style.minHeight === '');
  await page.waitForFunction(() => JSON.parse(localStorage.getItem('ha-paneld.card-sizes.v1.install')).cards['managed-components'] === 100);
});

browserTest('OAuth, auto-brightness and proximity journeys use their bounded local endpoints', async (t) => {
  const calls = [];
  let proximity = { present: true, phase: 'ready', requiredGestures: 3, acceptedGestures: 3, session: { active: false } };
  const schema = [
    { key: 'ha_url', label: 'Home Assistant URL', group: 'Dashboard', type: 'STRING', available: true },
    { key: 'ha_token', label: 'Token', group: 'Dashboard', type: 'STRING', secret: true, available: true },
    { key: 'auto_brightness', label: 'Adaptive brightness', group: 'Display', type: 'BOOL', available: true },
  ];
  const harness = await startHarness((path, request) => {
    calls.push(`${request.method} ${path}`);
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { ha_url: 'https://ha.example', ha_token: 'old', auto_brightness: 'true' }, ha_expose: {}, ha_auth: {} });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/ha/oauth/start') return json({ authorization_url: 'https://ha.example/auth/authorize?state=opaque' });
    if (path === '/api/v1/auto-brightness') return json({ available: true, state: 'learning', source_label: 'panel sensor', sourceAvailable: true, latestLux: 42 });
    if (path.startsWith('/api/v1/auto-brightness/history')) return json({ points: [], bucket_minutes: 5 });
    if (path === '/api/v1/proximity') return json(proximity);
    if (path === '/api/v1/proximity/calibration') { proximity = { ...proximity, phase: 'calibrating', stage: 'intro', sessionActive: true, sessionId: 'test-session' }; return json(proximity); }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(1_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.getByRole('button', { name: 'Connect' }).click();
  await assert.doesNotReject(page.getByText('Sign-in link ready. Open it normally or copy it into a private window.').waitFor());
  await assert.doesNotReject(page.getByRole('link', { name: 'Open sign-in' }).getAttribute('href').then((href) => assert.match(href, /state=opaque/)));
  await page.getByRole('button', { name: 'Set up proximity on panel' }).click();
  await assert.doesNotReject(page.getByText('Ready to begin on the panel').waitFor());
  assert.ok(calls.includes('POST /api/v1/ha/oauth/start'));
  assert.ok(calls.includes('GET /api/v1/auto-brightness'));
  assert.ok(calls.includes('GET /api/v1/auto-brightness/history'));
  assert.ok(calls.includes('POST /api/v1/proximity/calibration'));
});

browserTest('Proximity setup localizes browser controls and displays errors as safe text', async (t) => {
  const translations = {
    'configure.proximity.title': '存在与唤醒',
    'configure.proximity.setup.ready': '接近感应已就绪',
    'configure.proximity.setup.local': '可选设置在面板上进行。触摸唤醒仍然可用。',
    'configure.proximity.setup.start': '在面板上设置接近感应',
  };
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json([]);
    if (path === '/api/v1/config') return json({ settings: {}, ha_expose: {}, ha_auth: {} });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: true, phase: 'ready' });
    if (path === '/api/v1/proximity/calibration') return json({ error: '<opaque diagnostic & exact evidence>' }, 409);
  }, () => fixture(translations, 'zh-Hans'));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.getByText('接近感应已就绪').waitFor();
  assert.equal(await page.locator('.prox-learning .note').first().textContent(), '可选设置在面板上进行。触摸唤醒仍然可用。');
  await page.getByRole('button', { name: '在面板上设置接近感应' }).click();
  const result = page.locator('.prox-learning [role=status]');
  await result.getByText('<opaque diagnostic & exact evidence>', { exact: true }).waitFor();
  assert.equal(await result.locator('diagnostic').count(), 0);
});

browserTest('Configure shows a durable pending apply on its row until a push says it applied, without resending', async (t) => {
  // The panel keeps the desired value and retries the hardware itself; the page must neither resend nor
  // drop the row back to the observed value meanwhile, and the note goes once a `config` push shows it applied.
  const state = { settings: { touch_sound: false }, apply_pending: {}, ha_expose: {}, ha_auth: { configured: false } };
  const posted = [];
  let valuesUnavailable = false;
  const schema = [{ key: 'touch_sound', label: 'Touch sound', group: 'Behaviour', type: 'BOOL', available: true }];
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config' && request.method === 'POST') {
      posted.push(Object.fromEntries(new URLSearchParams(await requestBody(request))));
      state.apply_pending = { touch_sound: 'true' };
      return json({
        ok: true, status: 'saved-apply-pending', applied: [], pending: ['touch_sound'], rejected: [],
        message: 'Settings were saved; retrying hardware apply: touch_sound. If they remain pending, restart the service.',
        ...state,
      }, 202);
    }
    if (path === '/api/v1/config/values' && valuesUnavailable) return { status: 503, headers: { 'content-type': 'application/json' }, body: 'not json' };
    if (path === '/api/v1/config' || path === '/api/v1/config/values') return json(state);
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
  }, () => fixture({
    'configure.save.hardware_pending': '已保存所需值；硬件应用仍在等待。',
  }, 'zh-Hans'));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });

  const toggle = page.locator('#cfg-touch_sound [role=switch]');
  const note = page.locator('#cfg-touch_sound .apply-pending-status');
  await toggle.click();
  await note.getByText('已保存所需值；硬件应用仍在等待。', { exact: true }).waitFor();
  assert.deepEqual(posted, [{ touch_sound: 'true' }]);
  assert.equal(await toggle.getAttribute('aria-checked'), 'true');
  assert.equal(await page.locator('#cfg-touch_sound .cfg-row-message').count(), 0, 'a pending apply is not a failure');

  // A failed read of the family changes nothing on the row.
  valuesUnavailable = true;
  await page.waitForTimeout(1_200);
  valuesUnavailable = false;
  assert.equal(await note.count(), 1);
  assert.equal(await toggle.getAttribute('aria-checked'), 'true');

  // The hardware catches up: the next push carries the observed value and no pending entry.
  state.settings.touch_sound = true;
  state.apply_pending = {};
  await note.waitFor({ state: 'detached', timeout: 3_000 });
  assert.equal(await toggle.getAttribute('aria-checked'), 'true');
  assert.equal(posted.length, 1, 'the page never retries a durable pending apply');
});

browserTest('Persistent pending apply does not rebuild auto-sleep or reset the HA identity', async (t) => {
  let configGets = 0;
  let valuesGets = 0;
  let historyGets = 0;
  let autoSleepStatusGets = 0;
  let prerequisiteGets = 0;
  let statusGets = 0;
  let pendingDesired = 'true';
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'touch_sound', label: 'Touch sound', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'ha_url', label: 'Home Assistant URL', group: 'Dashboard', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config' || path === '/api/v1/config/values') {
      if (path === '/api/v1/config') configGets++; else valuesGets++;
      return json({
        settings: { auto_sleep: true, touch_sound: false, ha_url: 'https://ha.example' },
        apply_pending: { touch_sound: pendingDesired }, ha_expose: {}, ha_auth: { configured: true, oauth: true },
      });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/ha/oauth/status') { statusGets++; return json({ phase: 'connected', display_name: 'Panel User' }); }
    if (path === '/api/v1/auto-sleep/prerequisite') { prerequisiteGets++; return json({ eligible: true, phase: 'assigned', area_name: 'Office' }); }
    if (path === '/api/v1/auto-sleep') { autoSleepStatusGets++; return json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 }); }
    if (path === '/api/v1/auto-sleep/history') { historyGets++; return json(autoSleepHistory({ hours: 24 })); }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#cfg-ha-oauth').getByText('Connected as Panel User').waitFor();
  await page.locator('.auto-sleep-lane.source').waitFor();
  const snapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();

  await page.waitForTimeout(3_300);

  // The page follows the panel through the values read alone; nothing else is fetched again.
  assert.equal(configGets, 1);
  assert.ok(valuesGets >= 4, `the config family polls its values read (${valuesGets})`);
  assert.equal(statusGets, 1);
  assert.equal(prerequisiteGets, 1);
  assert.equal(autoSleepStatusGets, 1);
  assert.equal(historyGets, 1);
  assert.equal(await snapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
  assert.equal(await page.locator('#cfg-ha-oauth .ha-oauth-status').textContent(), 'Connected as Panel User');

  pendingDesired = 'false';
  await page.waitForFunction(() => document.querySelector('#cfg-touch_sound [role=switch]').getAttribute('aria-checked') === 'false', null, { timeout: 5_000 });
  await page.waitForTimeout(300);
  assert.equal(configGets, 1);
  assert.equal(statusGets, 1);
  assert.equal(prerequisiteGets, 1);
  assert.equal(autoSleepStatusGets, 1);
  assert.equal(historyGets, 1);
  assert.equal(await page.locator('#cfg-touch_sound .apply-pending-status').count(), 1);
  assert.equal(await page.locator('#cfg-ha-oauth .ha-oauth-status').textContent(), 'Connected as Panel User');
  assert.equal(await page.locator('#auto-sleep-prerequisite-status').textContent(), 'Home Assistant Area: Office');
});

browserTest('Auto-sleep requires an assigned Area before OFF can be switched ON', async (t) => {
  let prerequisiteCalls = 0;
  let prerequisiteAssigned = false;
  const firstPrerequisite = deferred();
  const schema = [{
    key: 'auto_sleep', label: 'Auto sleep',
    help: 'Automatically wake the panel when activity is detected and switch the screen off after the learned delay. Manual screen control remains separate.',
    summary: 'Turn the screen off when nobody is around or after inactivity.',
    group: 'Behaviour',
    type: 'BOOL', available: true,
  }];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'false' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') {
      prerequisiteCalls++;
      return prerequisiteAssigned ? json({ eligible: true, phase: 'assigned', area_name: 'Studio' }) : firstPrerequisite.promise;
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });

  const toggle = page.locator('#cfg-auto_sleep [role=switch]');
  const prerequisiteStatus = page.locator('#auto-sleep-prerequisite-status');
  await prerequisiteStatus.waitFor();
  assert.equal(await prerequisiteStatus.textContent(), 'Checking this panel’s Home Assistant Area…');
  assert.equal(await toggle.getAttribute('aria-disabled'), 'true');
  assert.equal(await toggle.getAttribute('tabindex'), '0');
  assert.equal(await toggle.getAttribute('aria-describedby'), 'auto-sleep-prerequisite-status');
  await toggle.press('Enter');
  assert.equal(await toggle.getAttribute('aria-checked'), 'false');
  await toggle.click({ force: true });
  assert.equal(await toggle.getAttribute('aria-checked'), 'false');

  firstPrerequisite.resolve(json({ eligible: false, phase: 'unassigned', area_name: null }));
  await page.getByText('Assign this panel to a Home Assistant Area before enabling Auto sleep.').waitFor();
  assert.equal(await toggle.getAttribute('aria-disabled'), 'true');
  assert.match(await page.locator('#cfg-auto_sleep').innerText(), /Turn the screen off when nobody is around/);
  assert.doesNotMatch(await page.locator('[data-config-group="Auto-sleep"] h2').innerText(), /Android\/app behaviour/);

  prerequisiteAssigned = true;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.getByText('Home Assistant Area: Studio').waitFor();
  assert.equal(await toggle.getAttribute('aria-disabled'), 'false');
  await toggle.click();
  assert.equal(await toggle.getAttribute('aria-checked'), 'true');
  assert.ok(prerequisiteCalls >= 2);
});

browserTest('Touch inactivity can enable Auto-sleep without an Area and sends a fixed seconds delay', async (t) => {
  const calls = [];
  const posts = [];
  const settings = { auto_sleep_source: 'panel', auto_sleep_touch_delay_seconds: '30', auto_sleep: 'false' };
  const schema = [
    { key: 'auto_sleep_source', label: 'Auto-sleep activity source', group: 'Behaviour', type: 'ENUM',
      available: true, options: ['panel', 'home_assistant', 'touch'] },
    { key: 'auto_sleep_touch_delay_seconds', label: 'Touch inactivity delay (seconds)', group: 'Behaviour',
      type: 'INT', available: true, min: 5, max: 86400 },
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
  ];
  const harness = await startHarness(async (path, request) => {
    calls.push(path);
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') {
        const posted = Object.fromEntries(new URLSearchParams(await requestBody(request)));
        posts.push(posted);
        Object.assign(settings, posted);
        return json({ status: 'saved' });
      }
      return json({ settings, ha_expose: {}, ha_auth: {} });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });

  const toggle = page.locator('#cfg-auto_sleep [role=switch]');
  await toggle.waitFor();
  await page.locator('#cfg-auto_sleep_source input[value="touch"]').check();
  assert.equal(await page.locator('#cfg-auto_sleep_source input:checked').inputValue(), 'touch');
  assert.equal(await page.locator('#cfg-auto_sleep_touch_delay_seconds input').inputValue(), '30');
  assert.equal(await toggle.getAttribute('aria-disabled'), 'false');
  await page.getByText('Home Assistant and presence sensors are not required.', { exact: false }).waitFor();
  const toggleSaved = page.waitForResponse((response) => response.request().method() === 'POST'
    && (response.request().postData() || '').includes('auto_sleep=true'));
  await toggle.click();
  assert.equal(await toggle.getAttribute('aria-checked'), 'true');
  // The save's answer re-renders the card; a fill begun before that render ends in the replaced field.
  await toggleSaved;
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => setTimeout(resolve, 0))));
  const delay = page.locator('#cfg-auto_sleep_touch_delay_seconds input');
  await delay.fill('45');
  await delay.press('Tab');
  for (const end = Date.now() + 10_000; posts.length < 3 && Date.now() < end;) await page.waitForTimeout(25);
  assert.equal(await page.locator('#auto-sleep-chart').count(), 0);
  // Each finished gesture sends its own setting, in the order the person made them.
  assert.deepEqual(posts, [{ auto_sleep_source: 'touch' }, { auto_sleep: 'true' }, { auto_sleep_touch_delay_seconds: '45' }]);
  assert.equal(await toggle.getAttribute('aria-checked'), 'true');
  // The card describes the new delay at once, without a reload: the touch source has no status to wait for.
  await page.waitForFunction(() => document.querySelector('#auto-sleep-summary-announcement')?.textContent.includes('45 seconds without touch'));
  // What the panel now holds is what the card describes.
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.querySelector('#auto-sleep-summary-announcement')?.textContent.includes('45 seconds without touch'));
  assert.equal(await page.locator('#cfg-auto_sleep [role=switch]').getAttribute('aria-checked'), 'true');
  assert.equal(calls.includes('/api/v1/auto-sleep/prerequisite'), false);
  assert.equal(calls.includes('/api/v1/auto-sleep'), false);
  assert.equal(calls.includes('/api/v1/auto-sleep/history'), false);
});

browserTest('Auto-sleep Area focus refresh supersedes a slow prerequisite request', async (t) => {
  const firstPrerequisite = deferred();
  let prerequisiteCalls = 0;
  const schema = [{ key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true }];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'false' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') {
      prerequisiteCalls++;
      return prerequisiteCalls === 1
        ? firstPrerequisite.promise
        : json({ eligible: true, phase: 'assigned', area_name: 'Studio' });
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.waitForFunction(() => document.querySelector('#auto-sleep-prerequisite-status')?.textContent.includes('Checking'));
  await page.waitForFunction(() => window.fetch && document.querySelector('#cfg-auto_sleep'));
  for (let attempt = 0; attempt < 20 && prerequisiteCalls < 1; attempt++) await page.waitForTimeout(25);
  assert.equal(prerequisiteCalls, 1);

  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Studio', { exact: true }).waitFor();
  assert.equal(prerequisiteCalls, 2);
  firstPrerequisite.resolve(json({ eligible: false, phase: 'unassigned', area_name: null }));
  await page.waitForTimeout(100);
  assert.equal(await page.locator('#auto-sleep-prerequisite-status').textContent(), 'Home Assistant Area: Studio');
  assert.equal(await page.locator('#cfg-auto_sleep [role=switch]').getAttribute('aria-disabled'), 'false');
});

browserTest('Auto-sleep card stays continuously painted across unrelated startup results', async (t) => {
  const homeDashboards = deferred();
  const brightnessStatus = deferred();
  const brightnessHistory = deferred();
  const sleepStatus = deferred();
  const source = 'sensor.office_illuminance';
  const revision = 'office-source';
  const schema = autoSleepHydrationSchema();
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({
      settings: autoSleepHydrationSettings(source),
      ha_expose: {}, ha_auth: { configured: true, oauth: true },
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/discovery') return json({});
    if (path === '/api/v1/config/home-dashboards') return homeDashboards.promise;
    if (path === '/api/v1/ha/oauth/status') return json({ phase: 'connected', display_name: 'Panel User' });
    if (path === '/api/v1/auto-brightness') return brightnessStatus.promise;
    if (path === '/api/v1/auto-brightness/history') return brightnessHistory.promise;
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') return sleepStatus.promise;
    if (path === '/api/v1/auto-sleep/history') return json(autoSleepHistory({ hours: 24 }));
  }, configureVisualFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 900 } });
  page.setDefaultTimeout(3_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.addStyleTag({ content: ':root{font-size:24px!important}html,body,#cfg-groups{overflow-anchor:none!important}' });
  await page.locator('#auto-sleep-summary-announcement').waitFor();
  assert.equal(await page.locator('#auto-sleep-summary-announcement').textContent(), 'Loading…');
  await page.evaluate(() => {
    const panel = document.querySelector('#auto-sleep-status');
    const summary = document.querySelector('#auto-sleep-summary');
    const chart = document.querySelector('#auto-sleep-chart');
    const first = {
      summaryHeight: summary.getBoundingClientRect().height,
      chartRelativeY: chart.getBoundingClientRect().y - panel.getBoundingClientRect().y,
    };
    window.__autoSleepColdSummaryGeometry = { active: true, frames: 0, min: { ...first }, max: { ...first } };
    const sample = () => {
      const audit = window.__autoSleepColdSummaryGeometry;
      if (!audit.active) return;
      const values = {
        summaryHeight: summary.getBoundingClientRect().height,
        chartRelativeY: chart.getBoundingClientRect().y - panel.getBoundingClientRect().y,
      };
      audit.frames++;
      Object.keys(values).forEach((key) => {
        audit.min[key] = Math.min(audit.min[key], values[key]);
        audit.max[key] = Math.max(audit.max[key], values[key]);
      });
      requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
  });
  sleepStatus.resolve(json({
    enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1,
  }));
  await page.locator('.auto-sleep-lane.source').waitFor();
  await page.waitForTimeout(100);
  const coldSummaryGeometry = await page.evaluate(() => {
    window.__autoSleepColdSummaryGeometry.active = false;
    return window.__autoSleepColdSummaryGeometry;
  });
  assert.ok(coldSummaryGeometry.frames >= 4, `expected cold summary frame samples, got ${coldSummaryGeometry.frames}`);
  Object.keys(coldSummaryGeometry.min).forEach((key) => {
    assert.ok(coldSummaryGeometry.max[key] - coldSummaryGeometry.min[key] <= 0.5,
      `${key} moved during first status load by ${coldSummaryGeometry.max[key] - coldSummaryGeometry.min[key]}px`);
  });
  await page.locator('#cfg-ha-oauth').getByText('Connected as Panel User').waitFor();
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Office', { exact: true }).waitFor();

  const autoSleepCard = await page.locator('[data-config-group="Auto-sleep"]').elementHandle();
  const panel = await page.locator('#auto-sleep-status').elementHandle();
  const chart = await page.locator('#auto-sleep-chart').elementHandle();
  const content = await page.locator('.auto-sleep-chart-content').elementHandle();
  const snapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  const settledSummary = await page.locator('#auto-sleep-summary').textContent();
  const settledArea = await page.locator('#auto-sleep-prerequisite-status').textContent();
  const originalDashboardCard = await page.locator('[data-config-group="Dashboard"]').elementHandle();
  const originalDisplayCard = await page.locator('[data-config-group="Display"]').elementHandle();
  assert.deepEqual(await page.locator('#cfg-groups > [data-config-group]').evaluateAll((cards) =>
    cards.map((card) => card.getAttribute('data-config-group'))), [
    'Identity', 'Home Assistant connection', 'Dashboard', 'Built-in renderer', 'Behaviour', 'Auto-sleep', 'Display',
    'System', 'Sensors', 'Diagnostics', 'Logging',
  ]);
  await page.locator('#auto-sleep-status').evaluate((panelNode) => panelNode.scrollIntoView({ block: 'center' }));
  await page.waitForTimeout(100);
  assert.equal(await page.locator('#auto-sleep-status').evaluate((panelNode) => {
    const rect = panelNode.getBoundingClientRect();
    return rect.bottom > 0 && rect.top < window.innerHeight;
  }), true);
  assert.equal(await page.locator('#auto-sleep-chart').evaluate((chartNode) => {
    const rect = chartNode.getBoundingClientRect();
    return rect.bottom > 0 && rect.top < window.innerHeight;
  }), true);
  await page.evaluate(() => {
    const card = document.querySelector('[data-config-group="Auto-sleep"]');
    const panelNode = document.querySelector('#auto-sleep-status');
    const chartNode = document.querySelector('#auto-sleep-chart');
    const overlay = document.querySelector('.auto-sleep-loading-overlay');
    const initialRect = panelNode.getBoundingClientRect();
    const initialCardRect = card.getBoundingClientRect();
    const initialChartRect = chartNode.getBoundingClientRect();
    const initialScrollY = window.scrollY;
    window.__autoSleepPaintAudit = {
      cardRemoved: 0, panelParkedHidden: 0, overlayShown: 0, frames: 0,
      replacedFrames: 0, disconnectedFrames: 0, hiddenFrames: 0, invisibleFrames: 0,
      cardViewportMissFrames: 0, panelViewportMissFrames: 0, chartViewportMissFrames: 0,
      maxXDelta: 0, maxYDelta: 0, maxWidthDelta: 0, maxHeightDelta: 0,
      maxCardYDelta: 0, maxChartYDelta: 0, maxChartRelativeYDelta: 0,
      minScrollY: initialScrollY, maxScrollY: initialScrollY,
      minDocumentY: initialRect.y + initialScrollY, maxDocumentY: initialRect.y + initialScrollY,
      scrollCorrectionCalls: 0,
    };
    const nativeScrollTo = window.scrollTo.bind(window);
    window.scrollTo = (...args) => {
      window.__autoSleepPaintAudit.scrollCorrectionCalls++;
      return nativeScrollTo(...args);
    };
    window.__autoSleepPaintSampling = true;
    const sample = () => {
      if (!window.__autoSleepPaintSampling) return;
      const audit = window.__autoSleepPaintAudit;
      const current = document.querySelector('#auto-sleep-status');
      audit.frames++;
      if (current !== panelNode) audit.replacedFrames++;
      if (!panelNode.isConnected) audit.disconnectedFrames++;
      if (panelNode.closest('[hidden]')) audit.hiddenFrames++;
      const style = getComputedStyle(panelNode);
      if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) audit.invisibleFrames++;
      const rect = panelNode.getBoundingClientRect();
      const cardRect = card.getBoundingClientRect();
      const chartRect = chartNode.getBoundingClientRect();
      if (cardRect.bottom <= 0 || cardRect.top >= window.innerHeight) audit.cardViewportMissFrames++;
      if (rect.bottom <= 0 || rect.top >= window.innerHeight) audit.panelViewportMissFrames++;
      if (chartRect.bottom <= 0 || chartRect.top >= window.innerHeight) audit.chartViewportMissFrames++;
      audit.maxXDelta = Math.max(audit.maxXDelta, Math.abs(rect.x - initialRect.x));
      audit.maxYDelta = Math.max(audit.maxYDelta, Math.abs(rect.y - initialRect.y));
      audit.maxWidthDelta = Math.max(audit.maxWidthDelta, Math.abs(rect.width - initialRect.width));
      audit.maxHeightDelta = Math.max(audit.maxHeightDelta, Math.abs(rect.height - initialRect.height));
      audit.maxCardYDelta = Math.max(audit.maxCardYDelta, Math.abs(cardRect.y - initialCardRect.y));
      audit.maxChartYDelta = Math.max(audit.maxChartYDelta, Math.abs(chartRect.y - initialChartRect.y));
      audit.maxChartRelativeYDelta = Math.max(audit.maxChartRelativeYDelta,
        Math.abs((chartRect.y - rect.y) - (initialChartRect.y - initialRect.y)));
      audit.minScrollY = Math.min(audit.minScrollY, window.scrollY);
      audit.maxScrollY = Math.max(audit.maxScrollY, window.scrollY);
      audit.minDocumentY = Math.min(audit.minDocumentY, rect.y + window.scrollY);
      audit.maxDocumentY = Math.max(audit.maxDocumentY, rect.y + window.scrollY);
      requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
    new MutationObserver((records) => {
      records.forEach((record) => {
        Array.from(record.removedNodes).forEach((node) => {
          if (node === card || node.nodeType === 1 && node.contains(card)) window.__autoSleepPaintAudit.cardRemoved++;
        });
        Array.from(record.addedNodes).forEach((node) => {
          if (node === panelNode && record.target.nodeType === 1 && record.target.closest('[hidden]')) {
            window.__autoSleepPaintAudit.panelParkedHidden++;
          }
        });
        if (record.type === 'attributes' && record.target === overlay && record.oldValue !== null) {
          window.__autoSleepPaintAudit.overlayShown++;
        }
      });
    }).observe(document.body, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['hidden'], attributeOldValue: true,
    });
  });

  homeDashboards.resolve(json({
    queried: true, items: [{ path: 'overview', title: 'Overview', group: 'dashboard' }],
    default: { explicit: true, path: 'overview' },
  }));
  await page.waitForFunction(() => Array.from(document.querySelectorAll('#cfg-home_dashboard option')).some((option) => option.textContent.includes('Overview')));
  await page.waitForTimeout(150);
  brightnessStatus.resolve(json({
    available: true, state: 'enabled', sourceAvailable: true, entityId: source, latestLux: 42, sourceRevision: revision,
  }));
  brightnessHistory.resolve(json({ points: [], sourceRevision: revision, latestEpochMinute: Math.floor(Date.now() / 60000) }));
  await page.locator('#auto-brightness-learning').getByText(`enabled · Source: ${source}`).waitFor();
  await page.waitForFunction(() => document.querySelector('#cfg-groups').classList.contains('config-viewport-anchored'));
  await page.evaluate(() => document.dispatchEvent(new WheelEvent('wheel', { bubbles: true })));
  await page.waitForTimeout(1600);
  await page.evaluate(() => { window.__autoSleepPaintSampling = false; });

  assert.equal(await autoSleepCard.evaluate((node) => node.isConnected && node === document.querySelector('[data-config-group="Auto-sleep"]')), true);
  assert.equal(await panel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await chart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await content.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-content')), true);
  assert.equal(await snapshot.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-snapshot')), true);
  assert.equal(await originalDashboardCard.evaluate((node) => node.isConnected), false,
    'delayed Home Dashboard result did not exercise an upstream card render');
  assert.equal(await originalDisplayCard.evaluate((node) => node.isConnected), false,
    'delayed adaptive-brightness result did not exercise an upstream card render');
  assert.equal(await page.locator('#cfg-groups').evaluate((root) => root.classList.contains('config-viewport-anchored')), false);
  assert.equal(await page.locator('[data-config-group="Dashboard"]').evaluate((card) => getComputedStyle(card).contentVisibility), 'auto');
  assert.equal(await page.locator('#auto-sleep-summary').textContent(), settledSummary);
  assert.equal(await page.locator('#auto-sleep-prerequisite-status').textContent(), settledArea);
  assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
  const paintAudit = await page.evaluate(() => window.__autoSleepPaintAudit);
  assert.ok(paintAudit.frames >= 10, `expected continuous frame samples, got ${paintAudit.frames}`);
  assert.deepEqual({
    cardRemoved: paintAudit.cardRemoved,
    panelParkedHidden: paintAudit.panelParkedHidden,
    overlayShown: paintAudit.overlayShown,
    replacedFrames: paintAudit.replacedFrames,
    disconnectedFrames: paintAudit.disconnectedFrames,
    hiddenFrames: paintAudit.hiddenFrames,
    invisibleFrames: paintAudit.invisibleFrames,
    cardViewportMissFrames: paintAudit.cardViewportMissFrames,
    panelViewportMissFrames: paintAudit.panelViewportMissFrames,
    chartViewportMissFrames: paintAudit.chartViewportMissFrames,
  }, {
    cardRemoved: 0,
    panelParkedHidden: 0,
    overlayShown: 0,
    replacedFrames: 0,
    disconnectedFrames: 0,
    hiddenFrames: 0,
    invisibleFrames: 0,
    cardViewportMissFrames: 0,
    panelViewportMissFrames: 0,
    chartViewportMissFrames: 0,
  });
  assert.ok(paintAudit.maxXDelta <= 0.5, `panel x moved by ${paintAudit.maxXDelta}px`);
  assert.ok(paintAudit.maxYDelta <= 0.5,
    `panel y moved by ${paintAudit.maxYDelta}px: ${JSON.stringify(paintAudit)}`);
  assert.ok(paintAudit.maxWidthDelta <= 0.5, `panel width changed by ${paintAudit.maxWidthDelta}px`);
  assert.ok(paintAudit.maxHeightDelta <= 0.5, `panel height changed by ${paintAudit.maxHeightDelta}px`);
  assert.ok(paintAudit.maxCardYDelta <= 0.5, `Auto-sleep card y moved by ${paintAudit.maxCardYDelta}px`);
  assert.ok(paintAudit.maxChartYDelta <= 0.5, `chart y moved by ${paintAudit.maxChartYDelta}px`);
  assert.ok(paintAudit.maxChartRelativeYDelta <= 0.5,
    `chart moved within the panel by ${paintAudit.maxChartRelativeYDelta}px`);
  assert.ok(paintAudit.scrollCorrectionCalls >= 1,
    `expected an app-owned viewport correction, got ${paintAudit.scrollCorrectionCalls}`);
  assert.ok(paintAudit.scrollCorrectionCalls <= 4,
    `viewport correction did not converge promptly (${paintAudit.scrollCorrectionCalls} scroll calls)`);
  assert.ok(paintAudit.maxDocumentY - paintAudit.minDocumentY >= 10,
    `fixture did not exercise upstream layout movement (${paintAudit.maxDocumentY - paintAudit.minDocumentY}px)`);
  assert.ok(paintAudit.maxScrollY - paintAudit.minScrollY >= 10,
    `viewport was not compensated for upstream movement (${paintAudit.maxScrollY - paintAudit.minScrollY}px)`);
});

browserTest('Auto-sleep hydration does not scroll toward an off-screen Auto-sleep card', async (t) => {
  const homeDashboards = deferred();
  const source = 'sensor.office_illuminance';
  const revision = 'office-source';
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(autoSleepHydrationSchema());
    if (path === '/api/v1/config') return json({
      settings: autoSleepHydrationSettings(source), ha_expose: {}, ha_auth: { configured: true, oauth: true },
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/discovery') return json({});
    if (path === '/api/v1/config/home-dashboards') return homeDashboards.promise;
    if (path === '/api/v1/ha/oauth/status') return json({ phase: 'connected', display_name: 'Panel User' });
    if (path === '/api/v1/auto-brightness') return json({
      available: true, state: 'enabled', sourceAvailable: true, entityId: source, latestLux: 42, sourceRevision: revision,
    });
    if (path === '/api/v1/auto-brightness/history') {
      return json({ points: [], sourceRevision: revision, latestEpochMinute: Math.floor(Date.now() / 60000) });
    }
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') return json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 });
    if (path === '/api/v1/auto-sleep/history') return json(autoSleepHistory({ hours: 24 }));
  }, configureVisualFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 900 } });
  page.setDefaultTimeout(3_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('.auto-sleep-lane.source').waitFor();
  await page.locator('#auto-brightness-learning').getByText(`enabled · Source: ${source}`).waitFor();
  assert.equal(await page.evaluate(() => window.scrollY), 0);
  assert.equal(await page.locator('#auto-sleep-status').evaluate((panelNode) =>
    panelNode.getBoundingClientRect().top >= window.innerHeight), true);

  const beforeScrollY = await page.evaluate(() => window.scrollY);
  await page.evaluate(() => {
    const panel = document.querySelector('#auto-sleep-status');
    window.__offscreenAutoSleepAudit = {
      active: true, frames: 0, minScrollY: window.scrollY, maxScrollY: window.scrollY,
      onscreenFrames: 0, exactLayoutFrames: 0, originalPanel: panel,
    };
    const sample = () => {
      const audit = window.__offscreenAutoSleepAudit;
      if (!audit.active) return;
      const rect = panel.getBoundingClientRect();
      audit.frames++;
      audit.minScrollY = Math.min(audit.minScrollY, window.scrollY);
      audit.maxScrollY = Math.max(audit.maxScrollY, window.scrollY);
      if (rect.bottom > 0 && rect.top < window.innerHeight) audit.onscreenFrames++;
      if (document.querySelector('#cfg-groups').classList.contains('config-viewport-anchored')) audit.exactLayoutFrames++;
      requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
  });
  homeDashboards.resolve(json({
    queried: true, items: [{ path: 'overview', title: 'Overview', group: 'dashboard' }],
    default: { explicit: true, path: 'overview' },
  }));
  await page.waitForFunction(() => Array.from(document.querySelectorAll('#cfg-home_dashboard option'))
    .some((option) => option.textContent.includes('Overview')));
  await page.waitForTimeout(1600);
  const offscreenAudit = await page.evaluate(() => {
    window.__offscreenAutoSleepAudit.active = false;
    const audit = window.__offscreenAutoSleepAudit;
    return { ...audit, samePanel: audit.originalPanel === document.querySelector('#auto-sleep-status') };
  });
  assert.ok(offscreenAudit.frames >= 10);
  assert.equal(offscreenAudit.samePanel, true);
  assert.equal(offscreenAudit.onscreenFrames, 0);
  assert.equal(offscreenAudit.exactLayoutFrames, 0);
  assert.equal(offscreenAudit.minScrollY, beforeScrollY);
  assert.equal(offscreenAudit.maxScrollY, beforeScrollY);
  assert.equal(await page.evaluate(() => window.scrollY), beforeScrollY);
  assert.equal(await page.locator('#cfg-groups').evaluate((root) => root.classList.contains('config-viewport-anchored')), false);
  assert.equal(await page.locator('#auto-sleep-status').evaluate((panelNode) =>
    panelNode.getBoundingClientRect().top >= window.innerHeight), true);
});

for (const [detail, message] of [
  ['no_device_source', 'This Area has no motion, occupancy or presence binary sensor from a device integration.'],
  ['insufficient_history', 'This Area’s activity sensors have too little usable history or are unavailable.'],
  ['unknown_source_reason', 'No credible device-backed activity source is available in this Area.'],
]) {
  browserTest(`Auto-sleep explains ${detail} in the existing red history line`, async (t) => {
    let historyCalls = 0;
    const harness = await startHarness((path) => {
      if (path === '/api/v1/config/schema') return json([
        { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
      ]);
      if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true' }, ha_expose: {}, ha_auth: { configured: true } });
      if (path === '/api/v1/apps') return json({ apps: [] });
      if (path === '/api/v1/radio' || path === '/api/v1/proximity') return json({ present: false });
      if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
      if (path === '/api/v1/auto-sleep') return json({
        enabled: true, available: false, phase: 'no_credible_sources', reason: 'no_credible_sources',
        detail, area_name: 'Office', source_count: 0, discovered_source_count: 0,
      });
      if (path === '/api/v1/auto-sleep/history') { historyCalls++; return json({ available: false }); }
      return json({});
    });
    const browser = await chromium.launch({ executablePath: chrome, headless: true });
    const page = await browser.newPage({ viewport: { width: 480, height: 800 } });
    page.setDefaultTimeout(2_000);
    t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
    await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
    const line = page.locator('#auto-sleep-history-message');
    await page.waitForFunction(() => document.querySelector('#auto-sleep-history-message')?.classList.contains('error'));
    assert.equal(await line.textContent(), message);
    assert.equal(await line.evaluate((node) => getComputedStyle(node).color), 'rgb(211, 75, 66)');
    assert.equal(await page.locator('#auto-sleep-status').count(), 1);
    assert.equal(await page.locator('#auto-sleep-chart').count(), 1);
    assert.equal(await page.locator('.auto-sleep-lane.source').count(), 0);
    assert.equal(historyCalls, 0);
  });
}

browserTest('Auto-sleep blank-Area discovery failure is terminal without cold-chart reflow', async (t) => {
  let statusCalls = 0;
  let historyCalls = 0;
  const terminal = {
    enabled: true, available: false, phase: 'discovery_failed', reason: 'discovery_failed',
    detail: 'registry_projection', area_name: '', source_count: 0, discovered_source_count: 0,
  };
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness(async (path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({
      settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true },
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/home-dashboards') return json({ items: [], default: {} });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      // If a faulty readiness loop starts, retain its Loading state long enough for a frame sample.
      if (statusCalls > 2) await new Promise((resolve) => setTimeout(resolve, 300));
      return json(terminal);
    }
    if (path === '/api/v1/auto-sleep/history') { historyCalls++; return json({ available: false, detail: 'runtime_unavailable' }); }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 800 } });
  page.setDefaultTimeout(3_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Office', { exact: true }).waitFor();
  await page.getByText('Auto-sleep source discovery failed. Check the Home Assistant connection.').waitFor();
  assert.equal(statusCalls, 2);

  const panel = await page.locator('#auto-sleep-status').elementHandle();
  const chart = await page.locator('#auto-sleep-chart').elementHandle();
  const content = await page.locator('.auto-sleep-chart-content').elementHandle();
  const snapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  await page.evaluate(() => {
    window.__autoSleepColdSamples = [];
    window.__autoSleepSummaryMutations = 0;
    new MutationObserver(() => { window.__autoSleepSummaryMutations++; })
      .observe(document.querySelector('#auto-sleep-summary'), { childList: true, characterData: true, subtree: true });
    function sample() {
      const activity = document.querySelector('#auto-sleep-status').getBoundingClientRect();
      const chartBox = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
      const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
      window.__autoSleepColdSamples.push({ panelHeight: activity.height, chartHeight: chartBox.height, systemY: system.y });
      window.__autoSleepColdFrame = requestAnimationFrame(sample);
    }
    window.__autoSleepColdFrame = requestAnimationFrame(sample);
  });
  await page.waitForTimeout(1_600);
  const observed = await page.evaluate(() => {
    cancelAnimationFrame(window.__autoSleepColdFrame);
    const ranges = {};
    for (const key of ['panelHeight', 'chartHeight', 'systemY']) {
      const values = window.__autoSleepColdSamples.map((sample) => sample[key]);
      ranges[key] = Math.max(...values) - Math.min(...values);
    }
    return { ranges, summaryMutations: window.__autoSleepSummaryMutations };
  });

  assert.equal(statusCalls, 2);
  assert.equal(historyCalls, 0);
  assert.equal(observed.summaryMutations, 0);
  assert.ok(Object.values(observed.ranges).every((range) => range <= 0.5), JSON.stringify(observed.ranges));
  assert.equal(await panel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await chart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await content.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-content')), true);
  assert.equal(await snapshot.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-snapshot')), true);
  assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
  assert.equal(await page.locator('.auto-sleep-empty').textContent(), 'No replay data');
});

browserTest('Auto-sleep blank-Area transport failure recovers without retry reflow', async (t) => {
  let statusCalls = 0;
  let historyCalls = 0;
  const recoveredStatus = deferred();
  const transportFailure = {
    enabled: true, available: false, phase: 'discovery_failed', reason: 'discovery_failed',
    detail: 'registry_transport', area_name: '', source_count: 0, discovered_source_count: 0,
  };
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({
      settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true },
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/home-dashboards') return json({ items: [], default: {} });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      return statusCalls <= 2 ? json(transportFailure) : recoveredStatus.promise;
    }
    if (path === '/api/v1/auto-sleep/history') { historyCalls++; return json(autoSleepHistory({ hours: 24 })); }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 800 } });
  page.setDefaultTimeout(7_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Office', { exact: true }).waitFor();
  for (let attempt = 0; attempt < 40 && statusCalls < 2; attempt++) await page.waitForTimeout(25);
  assert.equal(statusCalls, 2);
  const settledSummary = await page.locator('#auto-sleep-summary').textContent();

  await page.evaluate(() => {
    window.__autoSleepTransportSamples = [];
    window.__autoSleepTransportSummaryMutations = 0;
    new MutationObserver(() => { window.__autoSleepTransportSummaryMutations++; })
      .observe(document.querySelector('#auto-sleep-summary'), { childList: true, characterData: true, subtree: true });
    function sample() {
      const activity = document.querySelector('#auto-sleep-status').getBoundingClientRect();
      const chartBox = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
      const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
      window.__autoSleepTransportSamples.push({ panelHeight: activity.height, chartHeight: chartBox.height, systemY: system.y });
      window.__autoSleepTransportFrame = requestAnimationFrame(sample);
    }
    window.__autoSleepTransportFrame = requestAnimationFrame(sample);
  });
  for (let attempt = 0; attempt < 120 && statusCalls < 3; attempt++) await page.waitForTimeout(50);
  assert.equal(statusCalls, 3);
  const observed = await page.evaluate(() => {
    cancelAnimationFrame(window.__autoSleepTransportFrame);
    const ranges = {};
    for (const key of ['panelHeight', 'chartHeight', 'systemY']) {
      const values = window.__autoSleepTransportSamples.map((sample) => sample[key]);
      ranges[key] = Math.max(...values) - Math.min(...values);
    }
    return { ranges, summaryMutations: window.__autoSleepTransportSummaryMutations };
  });
  assert.equal(await page.locator('#auto-sleep-summary').textContent(), settledSummary);
  assert.equal(observed.summaryMutations, 0);
  assert.ok(Object.values(observed.ranges).every((range) => range <= 0.5), JSON.stringify(observed.ranges));
  assert.equal(historyCalls, 0);

  recoveredStatus.resolve(json({
    enabled: true, available: true, phase: 'live', reason: 'clear', area_name: 'Office', source_count: 1,
  }));
  await page.locator('.auto-sleep-lane.source').waitFor();
  assert.equal(statusCalls, 3);
  assert.equal(historyCalls, 1);
  assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
});

browserTest('Auto-sleep case-equivalent Area settles despite a late stale retry', async (t) => {
  let statusCalls = 0;
  let historyCalls = 0;
  let assignedArea = 'Office';
  const staleRetry = deferred();
  const transportFailure = {
    enabled: true, available: false, phase: 'discovery_failed', reason: 'discovery_failed',
    detail: 'registry_transport', area_name: '', source_count: 0, discovered_source_count: 0,
  };
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({
      settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true },
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/home-dashboards') return json({ items: [], default: {} });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: assignedArea });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      if (statusCalls <= 2) return json(transportFailure);
      if (statusCalls === 3) return staleRetry.promise;
      return json({ enabled: true, available: true, phase: 'live', area_name: ' studio ', source_count: 1 });
    }
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      return json(autoSleepHistory({ hours: 24, areaName: 'STUDIO' }));
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 800 } });
  page.setDefaultTimeout(7_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Office', { exact: true }).waitFor();
  for (let attempt = 0; attempt < 140 && statusCalls < 3; attempt++) await page.waitForTimeout(50);
  assert.equal(statusCalls, 3);

  assignedArea = 'Studio';
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Studio', { exact: true }).waitFor();
  await page.locator('.auto-sleep-lane.source').waitFor();
  assert.equal(statusCalls, 4);
  assert.equal(historyCalls, 1);
  const settledSummary = await page.locator('#auto-sleep-summary').textContent();

  staleRetry.resolve(json({
    enabled: true, available: false, phase: 'discovery_failed', reason: 'discovery_failed',
    detail: 'registry_projection', area_name: 'Garage', source_count: 0,
  }));
  await page.waitForTimeout(300);
  assert.equal(statusCalls, 4);
  assert.equal(historyCalls, 1);
  assert.equal(await page.locator('#auto-sleep-summary').textContent(), settledSummary);
  assert.equal(await page.locator('.auto-sleep-lane.source').count(), 1);
  assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
});

browserTest('Auto-sleep retains chart geometry and swaps a refreshed source snapshot atomically', async (t) => {
  const sourcePost = deferred();
  const refreshedHistory = deferred();
  const homeDashboards = deferred();
  let historyCalls = 0;
  const requestedHistoryHours = [];
  let sourcePosts = 0;
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/home-dashboards') return homeDashboards.promise;
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') return json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 });
    if (path === '/api/v1/auto-sleep/source' && request.method === 'POST') { sourcePosts++; return sourcePost.promise; }
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      requestedHistoryHours.push(new URL(request.url, 'http://panel.test').searchParams.get('hours'));
      if (historyCalls === 1) return json(autoSleepHistory({ hours: 24, laneCount: 20 }));
      if (historyCalls === 2) return refreshedHistory.promise;
      return json({ detail: 'terminal' }, 409);
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const sourceRow = page.locator('.auto-sleep-lane.source').first();
  await sourceRow.waitFor();
  assert.equal(requestedHistoryHours[0], '24');
  assert.equal(await page.getByRole('button', { name: '24h' }).getAttribute('aria-pressed'), 'true');

  const label = sourceRow.locator('.auto-sleep-label');
  const track = sourceRow.locator('.auto-sleep-track');
  assert.equal(await label.getAttribute('title'), 'Office ceiling motion');
  assert.equal(await track.getAttribute('title'), 'Click to suppress this source');
  assert.equal(await sourceRow.getAttribute('title'), null);
  assert.equal(await sourceRow.locator('.auto-sleep-interval').getAttribute('title'), null);
  const oldPanel = await page.locator('#auto-sleep-status').elementHandle();
  const oldAutoSleepCard = await page.locator('[data-config-group="Auto-sleep"]').elementHandle();
  const oldChart = await page.locator('#auto-sleep-chart').elementHandle();
  const oldContent = await page.locator('.auto-sleep-chart-content').elementHandle();
  const oldSnapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  const oldScroll = await page.locator('.auto-sleep-source-scroll').elementHandle();
  await sourceRow.focus();
  await oldScroll.evaluate((node) => { node.scrollTop = 75; });
  const beforePageY = await page.evaluate(() => {
    document.body.style.minHeight = '2000px';
    window.scrollTo(0, 400);
    return window.pageYOffset;
  });
  const before = await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  });
  await page.evaluate(() => {
    const card = document.querySelector('[data-config-group="Auto-sleep"]');
    const panel = document.querySelector('#auto-sleep-status');
    const overlay = document.querySelector('.auto-sleep-loading-overlay');
    window.__autoSleepVisualMutations = { cardRemoved: 0, hiddenAncestor: 0, overlayShown: 0 };
    new MutationObserver((records) => {
      records.forEach((record) => {
        Array.from(record.removedNodes).forEach((node) => {
          if (node === card || node.nodeType === 1 && node.contains(card)) window.__autoSleepVisualMutations.cardRemoved++;
        });
        Array.from(record.addedNodes).forEach((node) => {
          if (node === panel && record.target.nodeType === 1 && record.target.closest('[hidden]')) {
            window.__autoSleepVisualMutations.hiddenAncestor++;
          }
        });
        if (record.type === 'attributes' && record.target === overlay && record.oldValue !== null) {
          window.__autoSleepVisualMutations.overlayShown++;
        }
      });
    }).observe(document.body, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['hidden'], attributeOldValue: true,
    });
  });

  await sourceRow.press('Enter');
  const overlay = page.locator('.auto-sleep-loading-overlay');
  await overlay.waitFor({ state: 'hidden' });
  assert.equal(await page.locator('.auto-sleep-lane.source').count(), 20);
  assert.equal(await sourceRow.getAttribute('aria-disabled'), 'true');
  assert.equal(await page.locator('#auto-sleep-history-message').textContent(), 'Refreshing activity history…');
  assert.equal(await page.getByRole('button', { name: '24h' }).getAttribute('aria-disabled'), 'true');
  assert.equal(await page.getByRole('button', { name: '24h' }).evaluate((button) => button.disabled), false);
  assert.equal(await oldSnapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await oldScroll.evaluate((node) => node.isConnected && node.scrollTop), 75);
  assert.equal(await sourceRow.evaluate((node) => document.activeElement === node), true);
  assert.equal(await page.evaluate(() => window.pageYOffset), beforePageY);
  const duringPost = await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  });
  assert.deepEqual(duringPost, before);

  // An unrelated asynchronous Configure probe may rebuild other cards while the source update is
  // pending. The settled Auto-sleep card must stay rendered and unchanged throughout that work.
  homeDashboards.resolve(json({ queried: true, items: [{ path: 'overview', title: 'Overview', group: 'dashboard' }], default: {} }));
  await page.waitForTimeout(50);
  assert.equal(await oldAutoSleepCard.evaluate((node) => node.isConnected && node === document.querySelector('[data-config-group="Auto-sleep"]')), true);
  assert.equal(await oldPanel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await oldChart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await oldContent.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-content')), true);
  assert.equal(await oldSnapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await oldScroll.evaluate((node) => node.isConnected && node.scrollTop), 75);
  assert.equal(await sourceRow.evaluate((node) => document.activeElement === node), true);
  assert.equal(await page.evaluate(() => window.pageYOffset), beforePageY);
  assert.deepEqual(await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  }), before);
  assert.deepEqual(await page.evaluate(() => window.__autoSleepVisualMutations), {
    cardRemoved: 0,
    hiddenAncestor: 0,
    overlayShown: 0,
  });

  sourcePost.resolve(json({ updated: true }));
  for (let attempt = 0; attempt < 20 && historyCalls < 2; attempt++) await page.waitForTimeout(25);
  assert.equal(historyCalls, 2);
  await sourceRow.press('Enter');
  await page.waitForTimeout(50);
  assert.equal(sourcePosts, 1);
  assert.equal(await sourceRow.getAttribute('aria-disabled'), 'true');
  assert.equal(await page.locator('.auto-sleep-lane.source').count(), 20);
  assert.equal(await oldSnapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await oldScroll.evaluate((node) => node.isConnected && node.scrollTop), 75);
  assert.equal(await sourceRow.evaluate((node) => document.activeElement === node), true);
  assert.deepEqual(await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  }), before);

  refreshedHistory.resolve(json(autoSleepHistory({ included: false, hours: 24, laneCount: 20 })));
  await overlay.waitFor({ state: 'hidden' });
  await page.getByText('Office ceiling motion · Suppressed').waitFor();
  assert.equal(await page.locator('#auto-sleep-history-message').textContent(), '');
  assert.deepEqual(await page.evaluate(() => window.__autoSleepVisualMutations), {
    cardRemoved: 0,
    hiddenAncestor: 0,
    overlayShown: 0,
  });
  assert.equal(await oldSnapshot.evaluate((node) => node.isConnected), false);
  assert.equal(await page.locator('.auto-sleep-lane.source').first().getAttribute('aria-disabled'), 'false');
  assert.equal(await page.locator('.auto-sleep-track').nth(1).getAttribute('title'), 'Click to include this source');

  await page.getByRole('button', { name: '6h' }).click();
  await page.getByText('History request failed (HTTP not_commandable).').waitFor();
  assert.equal(await page.locator('.auto-sleep-lane.source').count(), 20);
  assert.equal(requestedHistoryHours.at(-1), '6');
  assert.equal(await page.getByRole('button', { name: '24h' }).getAttribute('aria-pressed'), 'true');
});

browserTest('Auto-sleep status changes cannot move a settled chart', async (t) => {
  let statusCalls = 0;
  let historyCalls = 0;
  const replacementHistory = deferred();
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/discovery') return json({});
    if (path === '/api/v1/config/home-dashboards') return json({ queried: true, items: [], default: {} });
    if (path === '/api/v1/ha/oauth/status') return json({ phase: 'connected', display_name: 'Panel User' });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      return json({
        enabled: true, available: true, phase: 'live', area_name: 'Office', learned_lease_ms: 1_080_000,
        reason: statusCalls === 1 ? 'source_active' : 'all_sources_unavailable',
        source_count: statusCalls === 1 ? 1 : 64,
        manual_suppression: statusCalls === 1,
      });
    }
    if (path === '/api/v1/auto-sleep/source' && request.method === 'POST') return json({ updated: true });
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      return historyCalls === 1 ? json(autoSleepHistory({ hours: 24 })) : replacementHistory.promise;
    }
  }, configureVisualFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const sourceRow = page.locator('.auto-sleep-lane.source').first();
  await sourceRow.waitFor();
  await page.addStyleTag({ content: ':root{font-size:24px!important}' });
  await page.waitForTimeout(250);

  const panel = await page.locator('#auto-sleep-status').elementHandle();
  const chart = await page.locator('#auto-sleep-chart').elementHandle();
  const snapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  assert.equal(await page.locator('#auto-sleep-summary-announcement').textContent(),
    'Home Assistant Area: Office · Phase: Live · Reason: Source active · Learned delay: 18 min · Sources: 1 · Manual screen override: active');
  assert.deepEqual(await page.locator('.auto-sleep-summary-line').allTextContents(), [
    'Home Assistant Area: Office',
    'Phase: Live',
    'Reason: Source active',
    'Delay: 18 min · Sources: 1',
    'Manual override: active',
  ]);
  for (const index of [0, 1, 2, 3, 4]) {
    assert.equal(await page.locator('.auto-sleep-summary-line').nth(index).evaluate((line) => line.scrollWidth <= line.clientWidth + 1), true);
  }
  await page.evaluate(() => {
    const panelNode = document.querySelector('#auto-sleep-status');
    const summary = document.querySelector('#auto-sleep-summary');
    const chartNode = document.querySelector('#auto-sleep-chart');
    const system = document.querySelector('[data-config-group="System"]');
    const first = {
      summaryHeight: summary.getBoundingClientRect().height,
      chartY: chartNode.getBoundingClientRect().y,
      chartRelativeY: chartNode.getBoundingClientRect().y - panelNode.getBoundingClientRect().y,
      panelHeight: panelNode.getBoundingClientRect().height,
      systemY: system.getBoundingClientRect().y,
    };
    window.__autoSleepSummaryGeometry = { frames: 0, min: { ...first }, max: { ...first }, active: true };
    const sample = () => {
      const audit = window.__autoSleepSummaryGeometry;
      if (!audit.active) return;
      const panelBox = panelNode.getBoundingClientRect();
      const chartBox = chartNode.getBoundingClientRect();
      const values = {
        summaryHeight: summary.getBoundingClientRect().height,
        chartY: chartBox.y,
        chartRelativeY: chartBox.y - panelBox.y,
        panelHeight: panelBox.height,
        systemY: system.getBoundingClientRect().y,
      };
      audit.frames++;
      Object.keys(values).forEach((key) => {
        audit.min[key] = Math.min(audit.min[key], values[key]);
        audit.max[key] = Math.max(audit.max[key], values[key]);
      });
      requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
  });

  await sourceRow.press('Enter');
  await page.waitForFunction(() => window.__autoSleepSummaryGeometry.frames >= 2 && document.querySelector('#auto-sleep-summary').textContent.includes('All sources unavailable'));
  assert.equal(historyCalls, 2);
  await page.waitForTimeout(100);
  await page.evaluate(() => { window.__autoSleepSummaryGeometry.active = false; });

  assert.equal(await page.locator('#auto-sleep-summary-announcement').textContent(),
    'Home Assistant Area: Office · Phase: Live · Reason: All sources unavailable · Learned delay: 18 min · Sources: 64 · Manual screen override: inactive');
  assert.deepEqual(await page.locator('.auto-sleep-summary-line').allTextContents(), [
    'Home Assistant Area: Office',
    'Phase: Live',
    'Reason: All sources unavailable',
    'Delay: 18 min · Sources: 64',
    'Manual override: inactive',
  ]);
  for (const index of [0, 1, 2, 3, 4]) {
    assert.equal(await page.locator('.auto-sleep-summary-line').nth(index).evaluate((line) => line.scrollWidth <= line.clientWidth + 1), true);
  }
  assert.equal(await panel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await chart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await snapshot.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-snapshot')), true);
  assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
  const geometry = await page.evaluate(() => window.__autoSleepSummaryGeometry);
  assert.ok(geometry.frames >= 4, `expected continuous frame samples, got ${geometry.frames}`);
  Object.keys(geometry.min).forEach((key) => {
    assert.ok(geometry.max[key] - geometry.min[key] <= 0.5,
      `${key} moved by ${geometry.max[key] - geometry.min[key]}px`);
  });
  await page.setViewportSize({ width: 320, height: 800 });
  await page.waitForTimeout(100);
  for (const index of [0, 1, 2, 3, 4]) {
    assert.equal(await page.locator('.auto-sleep-summary-line').nth(index).evaluate((line) => line.scrollWidth <= line.clientWidth + 1), true);
  }
});

browserTest('Auto-sleep restores its cached chart synchronously when disabled and re-enabled', async (t) => {
  let enabled = true;
  let statusCalls = 0;
  const reenabledStatus = deferred();
  const reenabledHistory = deferred();
  let historyCalls = 0;
  const schema = [{ key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true }];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') { enabled = !enabled; return json({}); }
      return json({ settings: { auto_sleep: String(enabled) }, ha_expose: {}, ha_auth: { configured: true } });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      return statusCalls === 1 ? json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 }) : reenabledStatus.promise;
    }
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      return historyCalls === 1 ? json(autoSleepHistory()) : reenabledHistory.promise;
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('.auto-sleep-lane.source').waitFor();

  await page.locator('#cfg-auto_sleep [role=switch]').click();
  await page.locator('#auto-sleep-status').waitFor({ state: 'detached' });
  await page.getByText('Home Assistant Area: Office').waitFor();

  await page.evaluate(() => {
    window.__autoSleepInsertionBusy = [];
    new MutationObserver(() => {
      const chart = document.querySelector('#auto-sleep-chart');
      if (chart && !window.__autoSleepInsertionBusy.length) {
        window.__autoSleepInsertionBusy.push({
          busy: chart.getAttribute('aria-busy'),
          overlayHidden: chart.querySelector('.auto-sleep-loading-overlay').hidden,
        });
      }
    }).observe(document.querySelector('#cfg-groups'), { childList: true, subtree: true });
  });
  await page.locator('#cfg-auto_sleep [role=switch]').click();
  const chart = page.locator('#auto-sleep-chart');
  await chart.waitFor();
  await chart.locator('.auto-sleep-lane.source').waitFor();
  await chart.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
  assert.deepEqual(await page.evaluate(() => window.__autoSleepInsertionBusy[0]), { busy: 'false', overlayHidden: true });
  await page.waitForFunction(() => document.querySelector('#auto-sleep-chart')?.getAttribute('aria-busy') === 'true');
  assert.equal(await chart.locator('.auto-sleep-loading-overlay').isVisible(), false);
  const stableGeometry = await page.evaluate(() => {
    const panelBox = document.querySelector('#auto-sleep-status').getBoundingClientRect();
    const summaryBox = document.querySelector('#auto-sleep-summary').getBoundingClientRect();
    const chartBox = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    return {
      summaryHeight: summaryBox.height, chartRelativeY: chartBox.y - panelBox.y,
      panelHeight: panelBox.height, chartHeight: chartBox.height, legendY: legend.y,
    };
  });
  await page.waitForTimeout(250);
  assert.deepEqual(await page.evaluate(() => {
    const panelBox = document.querySelector('#auto-sleep-status').getBoundingClientRect();
    const summaryBox = document.querySelector('#auto-sleep-summary').getBoundingClientRect();
    const chartBox = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    return {
      summaryHeight: summaryBox.height, chartRelativeY: chartBox.y - panelBox.y,
      panelHeight: panelBox.height, chartHeight: chartBox.height, legendY: legend.y,
    };
  }), stableGeometry);
  assert.equal(await chart.locator('.auto-sleep-loading-overlay').count(), 1);

  reenabledStatus.resolve(json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 }));
  for (let attempt = 0; attempt < 20 && historyCalls < 2; attempt++) await page.waitForTimeout(25);
  assert.equal(historyCalls, 2);
  assert.deepEqual(await page.evaluate(() => {
    const panelBox = document.querySelector('#auto-sleep-status').getBoundingClientRect();
    const summaryBox = document.querySelector('#auto-sleep-summary').getBoundingClientRect();
    const chartBox = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    return {
      summaryHeight: summaryBox.height, chartRelativeY: chartBox.y - panelBox.y,
      panelHeight: panelBox.height, chartHeight: chartBox.height, legendY: legend.y,
    };
  }), stableGeometry);
  reenabledHistory.resolve(json(autoSleepHistory({ label: 'Office ceiling motion refreshed' })));
  await chart.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
  await page.getByText('Office ceiling motion refreshed').waitFor();
});

browserTest('Auto-sleep source failure keeps the exact focused and scrolled chart snapshot', async (t) => {
  const failedSourcePost = deferred();
  const schema = [{ key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true }];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') return json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 20 });
    if (path === '/api/v1/auto-sleep/history') return json(autoSleepHistory({ laneCount: 20 }));
    if (path === '/api/v1/auto-sleep/source' && request.method === 'POST') return failedSourcePost.promise;
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const row = page.locator('.auto-sleep-lane.source').first();
  await row.waitFor();
  const snapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  const scroll = await page.locator('.auto-sleep-source-scroll').elementHandle();
  await row.focus();
  await scroll.evaluate((node) => { node.scrollTop = 61; });
  await row.press('Enter');
  await page.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
  assert.equal(await page.locator('#auto-sleep-chart').getAttribute('aria-busy'), 'true');
  failedSourcePost.resolve(json({ error: 'failed' }, 500));
  await page.getByText('Could not update this activity source (HTTP failed).').waitFor();
  await page.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
  assert.equal(await snapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await scroll.evaluate((node) => node.isConnected && node.scrollTop), 61);
  assert.equal(await row.evaluate((node) => document.activeElement === node), true);
});

for (const staleSourceOutcome of ['success', 'failure']) {
  browserTest(`Auto-sleep ignores stale source ${staleSourceOutcome} after an Area transition`, async (t) => {
    let areaName = 'Office';
    let statusCalls = 0;
    let historyCalls = 0;
    const sourcePost = deferred();
    const studioStatus = deferred();
    const studioHistory = deferred();
    const schema = [{ key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true }];
    const harness = await startHarness((path, request) => {
      if (path === '/api/v1/config/schema') return json(schema);
      if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true' }, ha_expose: {}, ha_auth: { configured: true } });
      if (path === '/api/v1/apps') return json({ apps: [] });
      if (path === '/api/v1/radio') return json({ present: false });
      if (path === '/api/v1/proximity') return json({ present: false });
      if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: areaName });
      if (path === '/api/v1/auto-sleep') {
        statusCalls++;
        return statusCalls === 1
          ? json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 })
          : studioStatus.promise;
      }
      if (path === '/api/v1/auto-sleep/history') {
        historyCalls++;
        return historyCalls === 1 ? json(autoSleepHistory()) : studioHistory.promise;
      }
      if (path === '/api/v1/auto-sleep/source' && request.method === 'POST') return sourcePost.promise;
    });
    const browser = await chromium.launch({ executablePath: chrome, headless: true });
    const page = await browser.newPage();
    page.setDefaultTimeout(2_000);
    t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
    await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
    const row = page.locator('.auto-sleep-lane.source').first();
    await row.waitFor();
    await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Office', { exact: true }).waitFor();
    const snapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
    await row.press('Enter');
    await page.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
    assert.equal(await page.locator('#auto-sleep-chart').getAttribute('aria-busy'), 'true');

    areaName = 'Studio';
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Studio', { exact: true }).waitFor();
    for (let attempt = 0; attempt < 20 && statusCalls < 2; attempt++) await page.waitForTimeout(25);
    assert.equal(statusCalls, 2);
    sourcePost.resolve(staleSourceOutcome === 'success' ? json({ updated: true }) : json({ error: 'failed' }, 500));
    await page.waitForTimeout(100);
    assert.equal(statusCalls, 2);
    assert.equal(historyCalls, 1);
    assert.equal(await snapshot.evaluate((node) => node.isConnected), true);
    assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
    assert.equal(await page.getByText(/Could not update this activity source/).count(), 0);

    studioStatus.resolve(json({ enabled: true, available: true, phase: 'live', area_name: 'Studio', source_count: 1 }));
    for (let attempt = 0; attempt < 20 && historyCalls < 2; attempt++) await page.waitForTimeout(25);
    studioHistory.resolve(json(autoSleepHistory({ label: 'Studio motion', areaName: 'Studio' })));
    await page.getByText('Studio motion').waitFor();
  });
}

browserTest('Auto-sleep replaces stale startup history when the first Area prerequisite arrives', async (t) => {
  const prerequisite = deferred();
  let statusCalls = 0;
  let historyCalls = 0;
  const schema = [{ key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true }];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') return prerequisite.promise;
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      return json({ enabled: true, available: true, phase: 'live', area_name: statusCalls === 1 ? 'Area A' : 'Area B', source_count: 1 });
    }
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      return json(autoSleepHistory({ label: historyCalls === 1 ? 'Area A motion' : 'Area B motion', areaName: historyCalls === 1 ? 'Area A' : 'Area B' }));
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.getByText('Area A motion').waitFor();
  const staleSnapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();

  prerequisite.resolve(json({ eligible: true, phase: 'assigned', area_name: 'Area B' }));
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Area B', { exact: true }).waitFor();
  await page.getByText('Area B motion').waitFor();
  assert.equal(statusCalls, 2);
  assert.equal(historyCalls, 2);
  assert.equal(await staleSnapshot.evaluate((node) => node.isConnected), false);
  assert.equal(await page.getByText('Area A motion').count(), 0);
});

browserTest('Auto-sleep Area refresh retains A until B is complete and converges unassigned to off without losing edits', async (t) => {
  let areaName = 'Area A';
  let statusCalls = 0;
  let historyCalls = 0;
  const areaBHistory = deferred();
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') return areaName
      ? json({ eligible: true, phase: 'assigned', area_name: areaName })
      : json({ eligible: false, phase: 'unassigned', area_name: null });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      return json({ enabled: true, available: true, phase: 'live', area_name: statusCalls <= 2 ? 'Area A' : 'Area B', source_count: 1 });
    }
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      if (historyCalls <= 2) return json(autoSleepHistory({ label: 'Area A motion', areaName: 'Area A' }));
      return areaBHistory.promise;
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.getByText('Area A motion').waitFor();
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Area A', { exact: true }).waitFor();
  const areaAPanel = await page.locator('#auto-sleep-status').elementHandle();
  const areaAChart = await page.locator('#auto-sleep-chart').elementHandle();
  const areaAContent = await page.locator('.auto-sleep-chart-content').elementHandle();
  const areaASnapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  const beforeAreaChange = await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  });

  areaName = 'Area B';
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Area B', { exact: true }).waitFor();
  assert.equal(statusCalls, 2);
  await page.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
  assert.equal(await page.locator('#auto-sleep-chart').getAttribute('aria-busy'), 'true');
  assert.equal(await areaAPanel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await areaAChart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await areaAContent.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-content')), true);
  assert.equal(await areaASnapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await page.getByText('Area A motion').count(), 1);
  assert.deepEqual(await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  }), beforeAreaChange);
  assert.equal(historyCalls, 1);
  for (let attempt = 0; attempt < 80 && historyCalls < 2; attempt++) await page.waitForTimeout(25);
  assert.equal(historyCalls, 2);
  assert.equal(await areaASnapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await page.getByText('Area A motion').count(), 1);
  for (let attempt = 0; attempt < 240 && historyCalls < 3; attempt++) await page.waitForTimeout(25);
  assert.equal(historyCalls, 3);
  areaBHistory.resolve(json(autoSleepHistory({ label: 'Area B motion', areaName: 'Area B' })));
  await page.getByText('Area B motion').waitFor();
  await page.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
  assert.equal(await areaASnapshot.evaluate((node) => node.isConnected), false);
  assert.equal(await areaAPanel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await areaAChart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await areaAContent.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-content')), true);
  assert.deepEqual(await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  }), beforeAreaChange);

  await page.locator('#cfg-friendly_name input').fill('Uncommitted panel name');
  areaName = null;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.getByText('Assign this panel to a Home Assistant Area before enabling Auto sleep.').waitFor();
  const toggle = page.locator('#cfg-auto_sleep [role=switch]');
  assert.equal(await toggle.getAttribute('aria-checked'), 'false');
  assert.equal(await toggle.getAttribute('aria-disabled'), 'true');
  assert.equal(await toggle.getAttribute('tabindex'), '0');
  assert.equal(await page.locator('#auto-sleep-status').count(), 0);
  assert.equal(await page.locator('#cfg-friendly_name input').inputValue(), 'Uncommitted panel name', 'the typed text stays in its field');
  assert.equal(await page.locator('#cfg-friendly_name input').evaluate((node) => node === document.activeElement), true);
});

browserTest('Logging Configure card omits sink actions and Dashboard-owned live state', async (t) => {
  let statusCalls = 0;
  const schema = [
    { key: 'log_ship_enabled', label: 'Ship logs', group: 'Logging', type: 'BOOL', available: true },
    { key: 'log_ship_host', label: 'Sink host', group: 'Logging', type: 'STRING', available: true },
    { key: 'log_ship_port', label: 'Sink port', group: 'Logging', type: 'INT', available: true, min: 1, max: 65535 },
    { key: 'log_ship_protocol', label: 'Protocol', group: 'Logging', type: 'ENUM', available: true,
      options: ['syslog-udp', 'syslog-tcp', 'http'] },
  ];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({
      settings: { log_ship_enabled: true, log_ship_host: 'collector.lan', log_ship_port: 514,
        log_ship_protocol: 'syslog-tcp' },
      ha_expose: {}, ha_auth: { configured: false },
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/logship/status') {
      statusCalls++;
      return json({ enabled: true, configured: true, text: 'tcp://collector.lan:514 · connected · 1 line sent' });
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(7_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.evaluate(() => window.cfgTab(true));
  const card = page.locator('[data-config-group="Logging"]');
  await card.waitFor();
  assert.equal(await card.getByText('Check the sink', { exact: true }).count(), 0);
  assert.equal(await card.getByRole('button', { name: 'Test sink' }).count(), 0);
  assert.equal(await card.getByText('Current state', { exact: true }).count(), 0);
  await page.waitForTimeout(50);
  assert.equal(statusCalls, 0);
});

// ---------------------------------------------------------------------------
// Dashboard card-wall placement contract.
//
// Covers restored scroll placement, responsive-header first paint and peer updates across the
// single-column and multi-column boundary.
const DASHBOARD_WIDTHS = [360, 480, 600, 700, 800, 833, 834, 857, 858, 900];
const DASHBOARD_TOPBAR_GAP = 16;   // info.css .topbar{margin-bottom:16px} — the one bar-to-content gap

function dashboardFixture() {
  const cards = Array.from({ length: 14 }, (_, index) => `
    <div class="card" data-layout-key="probe-${index}"><h2>Card ${index}</h2>
    <div class="probe-body" style="height:96px">card ${index}</div></div>`).join('');
  return `<!doctype html><html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css">${i18nBridge()}</head><body data-hydrate="1"><div class="wrap">
    <div class="topbar"><div class="hdr">
      <button id="navburger" class="navburger pbtn" aria-label="Menu">&#9776;</button>
      <h1><img src="/icon.svg" class="logo" alt=""><span class="brand">ha-paneld</span>
      <small id="pswitch" data-self-id="probe" data-self-name="Probe Panel"><span class="sep">&middot;</span>Probe Panel</small></h1>
      <span style="display:flex;gap:10px;align-items:center"><a class="gh" href="#"><svg viewBox="0 0 16 16"></svg></a></span></div>
    <nav class="nav"><a href="/" class="active">Dashboard</a><a href="/configure">Configure</a><a href="/entities">Entities</a>
    <a href="/install">Install</a><a href="/profiles">Profile</a><a href="/logs">Logs</a><a href="/fleet">Fleet</a></nav></div>
    <script src="/assets/switcher.js"></script>
    <div id="bannerzone"></div>
    <div class="cards" id="dashboard-cards" data-card-size-page="dashboard" data-card-size-epoch="1" data-card-size-restore="1">${cards}</div>
    <script src="/assets/card-size-memory.js"></script>
    <script src="/assets/card-column-alignment.js"></script>
    <script>
      // Stand in for /api/v1/info: replace card bodies and the banner well after first paint, exactly as
      // hydration does, so the contract is asserted across that transition rather than only at rest.
      window.__hydrated = false;
      setTimeout(function () {
        document.querySelectorAll('#dashboard-cards .probe-body').forEach(function (body, index) {
          body.style.height = (300 + (index % 7) * 70) + 'px';
        });
        if (window.CardSizeMemory) window.CardSizeMemory.settle('dashboard-cards', 40);
        window.__hydrated = true;
      }, 250);
    </script>
  </div></body></html>`;
}

// Must run at document-start: the whole point is to observe the topbar BEFORE the header fit runs.
// Sampling from a body-end script would start after switcher.js and could never see a late collapse.
const DASHBOARD_OBSERVER = `
  window.__frames = []; window.__shifts = [];
  try { new PerformanceObserver((list) => { for (const entry of list.getEntries()) {
    if (!entry.hadRecentInput) window.__shifts.push(entry.value); } })
    .observe({ type: 'layout-shift', buffered: true }); } catch (_) {}
  (function tick() { const bar = document.querySelector('.topbar');
    if (bar) window.__frames.push(Math.round(bar.getBoundingClientRect().height * 100) / 100);
    if (window.__frames.length < 240) requestAnimationFrame(tick); })();
`;

const READ_WALL = `(() => {
  const wrap = document.querySelector('.wrap');
  const topbar = document.querySelector('.topbar');
  const banner = document.getElementById('bannerzone');
  const root = document.getElementById('dashboard-cards');
  const abs = (el) => el.getBoundingClientRect().top + window.scrollY;
  const cards = Array.from(root.children).filter((n) => n.classList.contains('card') && n.style.display !== 'none');
  const lefts = [...new Set(cards.map((c) => Math.round(c.getBoundingClientRect().left)))];
  const padTop = parseFloat(getComputedStyle(wrap).paddingTop) || 0;
  return {
    predicted: abs(wrap) + padTop + topbar.getBoundingClientRect().height + ${DASHBOARD_TOPBAR_GAP}
      + banner.getBoundingClientRect().height,
    firstCardTop: abs(cards[0]),
    columns: lefts.length,
    topbarHeight: Math.round(topbar.getBoundingClientRect().height * 100) / 100,
    publishedTopbar: getComputedStyle(document.documentElement).getPropertyValue('--topbar-h').trim(),
    estimatedCards: cards.filter((c) => getComputedStyle(c).contentVisibility === 'auto').length,
    cardCount: cards.length,
  };
})()`;

browserTest('dashboard card wall sits at one computed coordinate at every width, before and after hydration', async (t) => {
  const harness = await startHarness(() => null, dashboardFixture);
  const browser = await chromium.launch({ executablePath: chrome, args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'] });
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  for (const width of DASHBOARD_WIDTHS) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce' });
    for (const run of ['cold', 'warm']) {           // warm reuses the context, so a snapshot exists
      const page = await context.newPage();
      await page.goto(harness.url, { waitUntil: 'load' });

      const before = await page.evaluate(READ_WALL);
      assert.ok(Math.abs(before.firstCardTop - before.predicted) <= 1,
        `${width}px ${run}: wall top ${before.firstCardTop} is not the computed coordinate ${before.predicted} before hydration`);

      await page.waitForFunction('window.__hydrated === true');
      await page.waitForTimeout(400);
      const after = await page.evaluate(READ_WALL);
      assert.ok(Math.abs(after.firstCardTop - after.predicted) <= 1,
        `${width}px ${run}: wall top ${after.firstCardTop} is not the computed coordinate ${after.predicted} after hydration`);

      // The single-column-only estimator must never be live on this wall — that mismatch is what made
      // 834-857px (a genuinely two-column masonry) run single-column placeholder heights.
      assert.equal(after.estimatedCards, 0,
        `${width}px ${run}: ${after.estimatedCards}/${after.cardCount} Dashboard cards still use an estimated intrinsic size`);

      // The topbar height is measured and published, never a frozen literal.
      assert.equal(after.publishedTopbar, `${after.topbarHeight}px`,
        `${width}px ${run}: --topbar-h is "${after.publishedTopbar}" but the bar measures ${after.topbarHeight}px`);
      await page.close();
    }
    await context.close();
  }
});

browserTest('dashboard topbar height is final at first paint, so the wall never snaps', async (t) => {
  const harness = await startHarness(() => null, dashboardFixture, 120);
  const browser = await chromium.launch({ executablePath: chrome, args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'] });
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  for (const width of [360, 480, 600, 700, 900]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce' });
    await context.addInitScript(DASHBOARD_OBSERVER);
    const page = await context.newPage();
    await page.goto(harness.url, { waitUntil: 'load' });
    await page.waitForFunction('window.__hydrated === true');
    await page.waitForTimeout(300);
    const seen = await page.evaluate('[...new Set(window.__frames)]');
    const cls = await page.evaluate('window.__shifts.reduce((a, b) => a + b, 0)');
    // An animation frame can land before the header script executes without anything being painted, so
    // the sampled heights are diagnostic only. The property that matters — and the one the tail-loaded
    // The header must settle before first paint rather than shifting the page afterward.
    assert.ok(cls < 0.02,
      `${width}px: layout shift ${cls} — the wall moved after paint; topbar heights seen ${JSON.stringify(seen)}`);
    await context.close();
  }
});

browserTest('dashboard scroll position survives a reload at every narrow width', async (t) => {
  const harness = await startHarness(() => null, dashboardFixture);
  const browser = await chromium.launch({ executablePath: chrome, args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'] });
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  for (const width of DASHBOARD_WIDTHS) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    await page.goto(harness.url, { waitUntil: 'load' });
    await page.waitForFunction('window.__hydrated === true');
    await page.waitForTimeout(400);

    const key = 'probe-10';
    const read = `(() => { const el = document.querySelector('[data-layout-key="${key}"]');
      return { viewportTop: Math.round(el.getBoundingClientRect().top * 100) / 100 }; })()`;
    const target = await page.evaluate(`(() => { const el = document.querySelector('[data-layout-key="${key}"]');
      const bar = document.querySelector('.topbar').getBoundingClientRect().height;
      return Math.round(el.getBoundingClientRect().top + window.scrollY - bar); })()`);
    await page.evaluate(`window.scrollTo(0, ${target})`);
    await page.waitForTimeout(200);
    const before = await page.evaluate(read);

    await page.reload({ waitUntil: 'load' });
    await page.waitForFunction('window.__hydrated === true');
    await page.waitForTimeout(400);
    const after = await page.evaluate(read);

    const drift = Math.round((after.viewportTop - before.viewportTop) * 100) / 100;
    assert.ok(Math.abs(drift) <= 2,
      `${width}px: reload displaced the wall by ${drift}px (was -413px at 480px, +38px at 900px before the fix)`);
    await context.close();
  }
});

// --- APK upload discard + recovery (Issue #96) ---
// The original report: upload an APK, realise it is the wrong one, press Back — the staged file
// keeps the slot and every later upload answers "upload-busy" with no visible way out. These tests
// drive the real install.js against the real markup shape and assert on the REQUESTS the page makes,
// because the defect class is a UI that looks recovered while the panel still holds the bytes.

function apkInstallCardFixture() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css">${i18nBridge()}</head><body>
    <span id="hardened-approval-description"></span>
    <div id="install-cards">
      <div class="card" data-layout-key="apk-install"><h2>Install an APK</h2>
        <label style="display:flex;flex-direction:row;gap:8px;align-items:center"><input type="checkbox" id="apk-allow" checked onchange="apkAllow(this)"> Enable APK install on this panel</label>
        <div id="apk-ui">
          <label class="pbtn" style="cursor:pointer">⭱ Choose APK…<input type="file" id="apk-file" accept=".apk,application/vnd.android.package-archive" style="display:none" onchange="apkPick(this)"></label>
          <label style="margin-top:10px">Or fetch from a link<input type="url" id="apk-url" inputmode="url" autocomplete="off" spellcheck="false" placeholder="https://example.com/app.apk"></label>
          <button class="pbtn" onclick="apkFetchUrl()">⇩ Fetch and inspect</button>
          <div id="apk-preview" style="margin-top:10px"></div>
        </div>
        <p class="note" id="apk-msg"></p>
      </div>
    </div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script><script src="/install.js"></script>
  </body></html>`;
}

function apkIdentityResponse(token) {
  return json({ ok: true, token, package: 'example.panel', version: '1.2.3', signer: 'unsigned' });
}

browserTest('APK preview offers Install and Cancel, and Cancel discards the exact token', async (t) => {
  const calls = [];
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/install/apk/pending') return json({ pending: false });
    if (path === '/api/v1/install/apk') {
      calls.push({ path, body: await requestBody(request) });
      return apkIdentityResponse('tok-1');
    }
    if (path === '/api/v1/install/apk/discard') {
      calls.push({ path, body: await requestBody(request) });
      return json({ ok: true, discarded: true });
    }
  }, apkInstallCardFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
  await page.setInputFiles('#apk-file', { name: 'wrong.apk', mimeType: 'application/vnd.android.package-archive', buffer: Buffer.from('apk-bytes') });

  const install = page.getByRole('button', { name: 'Install example.panel' });
  const cancel = page.getByRole('button', { name: 'Cancel' });
  await install.waitFor();
  assert.equal(await cancel.count(), 1, 'the inspected preview must offer Cancel beside Install');
  assert.equal(await install.getAttribute('data-hardened-approval'), '', 'installing is the approval-gated act');
  assert.equal(await cancel.getAttribute('data-hardened-approval'), null,
    'cancel removes uncommitted bytes and must stay available in Hardened mode');
  assert.match(await page.locator('#apk-preview').textContent(), /choose another file or link/);

  await cancel.click();
  await assert.doesNotReject(() => page.waitForFunction(
    () => document.querySelector('#apk-preview')?.textContent.includes('Pending APK discarded.'),
  ));
  assert.deepEqual(calls.map((c) => c.path), ['/api/v1/install/apk', '/api/v1/install/apk/discard']);
  assert.equal(calls[1].body, 'token=tok-1', 'the preview cancel must discard exactly the inspected entry');
  assert.equal(await install.count(), 0, 'a discarded preview must not keep offering Install');
});

browserTest('APK preview retires Cancel when installation starts', async (t) => {
  const calls = [];
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/install/apk/pending') return json({ pending: false });
    if (path === '/api/v1/install/apk') {
      await requestBody(request);
      return apkIdentityResponse('tok-start');
    }
    if (path === '/api/v1/install/apk/commit') {
      calls.push({ path, body: await requestBody(request) });
      return json({ status: 'started' });
    }
    if (path === '/api/v1/install/apk/discard') {
      calls.push({ path, body: await requestBody(request) });
      return json({ ok: true, discarded: false });
    }
    if (path === '/api/v1/install/status') return json({ running: true });
  }, apkInstallCardFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
  await page.setInputFiles('#apk-file', { name: 'install.apk', mimeType: 'application/vnd.android.package-archive', buffer: Buffer.from('apk-bytes') });

  const install = page.getByRole('button', { name: 'Install example.panel' });
  const cancel = page.getByRole('button', { name: 'Cancel' });
  await install.waitFor();
  await install.click();
  await assert.doesNotReject(() => page.waitForFunction(() => {
    const actions = Array.from(document.querySelectorAll('#apk-preview button'));
    return document.querySelector('#apk-msg')?.textContent.includes('Installing')
      && actions.length === 2
      && actions.every((action) => action.disabled);
  }));
  assert.equal(await install.isDisabled(), true, 'Install stays retired after the panel accepts the commit');
  assert.equal(await cancel.isDisabled(), true, 'Cancel cannot claim the panel is free after installation starts');
  await cancel.evaluate((button) => button.click());
  await page.waitForTimeout(100);
  assert.deepEqual(calls, [{ path: '/api/v1/install/apk/commit', body: 'token=tok-start' }],
    'a retired Cancel action must not issue a discard request for an APK now owned by the installer');
});

browserTest('Choosing another APK replaces the inspected one without a discard round-trip', async (t) => {
  const calls = [];
  let uploads = 0;
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/install/apk/pending') return json({ pending: false });
    if (path === '/api/v1/install/apk') {
      calls.push({ path, body: await requestBody(request) });
      return apkIdentityResponse(`tok-${++uploads}`);
    }
    if (path === '/api/v1/install/apk/discard') calls.push({ path, body: await requestBody(request) });
  }, apkInstallCardFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
  await page.setInputFiles('#apk-file', { name: 'wrong.apk', mimeType: 'application/vnd.android.package-archive', buffer: Buffer.from('first') });
  await page.getByRole('button', { name: 'Install example.panel' }).waitFor();

  await page.setInputFiles('#apk-file', { name: 'right.apk', mimeType: 'application/vnd.android.package-archive', buffer: Buffer.from('second') });
  await assert.doesNotReject(() => page.waitForFunction(
    () => document.querySelector('#apk-preview button[data-token="tok-2"]') !== null,
  ));
  assert.deepEqual(calls.map((c) => c.path), ['/api/v1/install/apk', '/api/v1/install/apk'],
    'replacement is one new upload — the server supersedes, no discard request needed');
  assert.equal(calls[1].body, 'second', 'the replacement bytes are the newly chosen file');
});

browserTest('A reload surfaces the panel-held pending upload with a probe-scoped Discard action', async (t) => {
  const calls = [];
  let pendingHeld = true;
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/install/apk/pending') return json(pendingHeld
      ? { pending: true, discard: 'pid-1', package: 'example.panel', version: '1.2.3', signer: 'unsigned' }
      : { pending: false });
    if (path === '/api/v1/install/apk/discard') {
      calls.push({ path, body: await requestBody(request) });
      pendingHeld = false;
      return json({ ok: true, discarded: true });
    }
  }, apkInstallCardFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
  const discard = page.getByRole('button', { name: 'Discard pending upload' });
  await discard.waitFor();
  assert.match(await page.locator('#apk-preview').textContent(), /example\.panel/,
    'recovery must name what the panel is holding, not just offer a button');
  assert.match(await page.locator('#apk-preview').textContent(), /replace it/,
    'recovery must also teach the replacement route');

  await discard.click();
  await assert.doesNotReject(() => page.waitForFunction(
    () => document.querySelector('#apk-preview')?.textContent.includes('Pending APK discarded.'),
  ));
  assert.deepEqual(calls.map((c) => c.path), ['/api/v1/install/apk/discard']);
  assert.equal(calls[0].body, 'token=pid-1',
    'the recovery discard is scoped by the probe reference — a reload lost the commit token, and a blind discard could delete a replacement');
});

browserTest('A stale recovery card cannot delete a replacement upload and repaints the truth', async (t) => {
  const calls = [];
  let probes = 0;
  const entries = [
    { pending: true, discard: 'pid-1', package: 'example.panel', version: '1.2.3', signer: 'unsigned' },
    { pending: true, discard: 'pid-2', package: 'replacement.panel', version: '9.9.9', signer: 'unsigned' },
  ];
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/radio') return json({ present: false });
    // First probe (page load) sees the original entry; every later probe sees the replacement that
    // another client staged in the meantime.
    if (path === '/api/v1/install/apk/pending') return json(entries[probes++ === 0 ? 0 : 1]);
    if (path === '/api/v1/install/apk/discard') {
      const body = await requestBody(request);
      calls.push({ path, body });
      if (body === 'token=pid-1') return json({ ok: false, error: 'different-pending' }, 409);
      return json({ ok: true, discarded: true });
    }
  }, apkInstallCardFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
  const discard = page.getByRole('button', { name: 'Discard pending upload' });
  await discard.waitFor();
  await discard.click();

  // The stale reference removed nothing; the page re-probed and now names the replacement.
  await assert.doesNotReject(() => page.waitForFunction(
    () => document.querySelector('#apk-preview')?.textContent.includes('replacement.panel'),
  ));
  await page.getByRole('button', { name: 'Discard pending upload' }).click();
  await assert.doesNotReject(() => page.waitForFunction(
    () => document.querySelector('#apk-preview')?.textContent.includes('Pending APK discarded.'),
  ));
  assert.deepEqual(calls.map((c) => c.body), ['token=pid-1', 'token=pid-2'],
    'each discard names exactly the entry its card was painted from; the refused one deleted nothing');
});

browserTest('upload-busy offers Discard only when the panel actually holds a pending entry', async (t) => {
  let pendingHeld = false;
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/install/apk/pending') return json(pendingHeld
      ? { pending: true, discard: 'pid-busy', package: 'example.panel', version: '1.2.3', signer: 'unsigned' }
      : { pending: false });
    if (path === '/api/v1/install/apk') {
      await requestBody(request);
      return json({ ok: false, error: 'upload-busy' }, 409);
    }
  }, apkInstallCardFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded' });

  // While the slot is genuinely receiving another transfer there is nothing to discard: the busy
  // text stands alone, without a button that would answer "nothing was pending".
  await page.setInputFiles('#apk-file', { name: 'a.apk', mimeType: 'application/vnd.android.package-archive', buffer: Buffer.from('a') });
  await assert.doesNotReject(() => page.waitForFunction(
    () => document.querySelector('#apk-preview')?.textContent.includes('busy with another APK'),
  ));
  await page.waitForTimeout(300);
  assert.equal(await page.getByRole('button', { name: 'Discard pending upload' }).count(), 0,
    'no discard offer when the probe says nothing is staged');

  // Once a staged entry is what holds the slot, the same busy answer upgrades to a recovery action.
  pendingHeld = true;
  await page.setInputFiles('#apk-file', { name: 'b.apk', mimeType: 'application/vnd.android.package-archive', buffer: Buffer.from('b') });
  await assert.doesNotReject(() => page.getByRole('button', { name: 'Discard pending upload' }).waitFor());
  assert.match(await page.locator('#apk-preview').textContent(), /example\.panel/);
});

// ---- Home dashboard: choosing a specific VIEW, not only a dashboard root (issue #90) --------------
// Home Assistant's list endpoint returns dashboard ROOTS, so a view below one (/dashboard-test/alpha)
// can only ever be typed. These drive the real control and assert on the value that reaches the config
// POST, because every failure this feature can have is a wrong-but-plausible saved path: the sentinel
// leaking out, an empty box meaning Auto, or a stale custom value surviving a switch back to the list.

const DASHBOARD_CATALOG = {
  queried: true,
  items: [
    { path: '/lovelace', title: 'Overview', icon: 'mdi:view-dashboard', group: 'dashboard' },
    { path: '/alpha', title: 'Office', icon: 'mdi:desk', group: 'dashboard' },
  ],
  default: { explicit: true, path: '/lovelace' },
};

function dashboardHarness(options = {}) {
  const state = { posts: [], current: options.current ?? '/alpha' };
  const schema = [{
    key: 'home_dashboard', label: 'Home dashboard', group: 'Dashboard', type: 'STRING',
    picker: 'ha_dashboard', maxLength: 2048, available: true,
  }];
  const routes = async (path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') {
        const body = new URLSearchParams(await requestBody(request));
        state.posts.push(body.get('home_dashboard'));
        state.current = body.get('home_dashboard');
        return json({});
      }
      // configure.js only fetches the dashboard catalogue for a configured Home Assistant, so the
      // connected state is part of the fixture rather than incidental to it.
      return json({ settings: { home_dashboard: state.current, ha_url: 'http://ha.local:8123' }, ha_expose: {}, ha_auth: { configured: true } });
    }
    if (path === '/api/v1/config/home-dashboards') return json(options.catalog ?? DASHBOARD_CATALOG);
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/health') return { body: 'ok cfg=test' };
  };
  return { state, routes };
}

async function openDashboardPicker(t, options = {}) {
  const { state, routes } = dashboardHarness(options);
  const harness = await startHarness(routes);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage(options.viewport ? { viewport: options.viewport } : {});
  // Roomier than this file's usual 1.5s: these walks make several round trips (catalogue fetch, then a
  // send per transition), and one run timed out at 2s while a Gradle gate was saturating the machine.
  // It bounds a wait, so it cannot mask a wrong value — only a slow one.
  page.setDefaultTimeout(10_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const row = page.locator('#cfg-home_dashboard');
  const select = row.locator('select');
  // The catalogue lands on a second request; wait for a real dashboard option so the assertions below
  // are never racing an empty select. Options inside a closed select are never "visible" to Playwright,
  // so this waits on the DOM rather than on visibility.
  await page.waitForFunction(() => {
    const select = document.querySelector('#cfg-home_dashboard select');
    return !!(select && select.querySelector('option[value="__custom__"]'));
  });
  return { page, row, select, input: row.locator('input.hd-custom-input'), state };
}

// Arm the response wait BEFORE the gesture that sends: the POST can complete faster than a listener
// registered afterwards can attach, which reads as "never sent" rather than as a race.
async function sentOnce(page, act) {
  const sent = page.waitForResponse((r) => r.url().endsWith('/api/v1/config') && r.request().method() === 'POST');
  await act();
  await sent;
}
const noSend = (page, timeout = 300) => assert.rejects(
  page.waitForRequest((r) => r.url().endsWith('/api/v1/config') && r.method() === 'POST', { timeout }), /Timeout/);

browserTest('A custom dashboard view is posted once, exactly as typed, when its field is committed', async (t) => {
  const { page, select, input, state } = await openDashboardPicker(t);

  // Choosing Custom starts the gesture; the path field finishes it.
  await select.selectOption('__custom__');
  await noSend(page);
  await input.fill('/dashboard-test/alpha');
  await noSend(page, 1_200);
  await sentOnce(page, () => input.press('Tab'));
  await page.waitForTimeout(300);

  // The sentinel must never be what gets sent, and the view must survive intact.
  assert.deepEqual(state.posts, ['/dashboard-test/alpha']);
});

browserTest('Returning to Custom sends the path still in its field', async (t) => {
  const { page, select, input, state } = await openDashboardPicker(t, { current: '/dashboard-test/alpha' });

  await sentOnce(page, () => select.selectOption('/lovelace'));
  // The response reaches the test before the page has handled it; the form is rebuilt once it has.
  await page.waitForFunction(() => document.querySelector('#cfg-home_dashboard input.hd-custom-input').value === '/lovelace');
  await input.evaluate((el) => { el.value = '/dashboard-test/alpha'; });
  await sentOnce(page, () => select.selectOption('__custom__'));
  assert.deepEqual(state.posts, ['/lovelace', '/dashboard-test/alpha'], 'the path shown is the path sent');
});

browserTest('An empty custom dashboard path never reaches the server as Auto', async (t) => {
  const { page, select, input, state } = await openDashboardPicker(t);

  // The field opens prefilled with the path this panel is already on, so emptying it is a deliberate
  // act — and still must not be saved as a blank, which the server would read as Auto.
  await select.selectOption('__custom__');
  await input.fill('');
  await input.press('Tab');

  await page.locator('#cfg-home_dashboard .cfg-row-message').getByText('Home dashboard has an invalid value.', { exact: true }).waitFor();
  await page.waitForFunction((text) => document.getElementById('cfg-msg').textContent === text, 'Home dashboard has an invalid value.');
  await noSend(page);
  assert.deepEqual(state.posts, []);
  assert.equal(await page.locator('#cfg-home_dashboard select').inputValue(), '/alpha', 'the panel value comes back');
});

browserTest('A configured view path opens in Custom mode and stays editable', async (t) => {
  const { select, input } = await openDashboardPicker(t, { current: '/dashboard-test/alpha' });

  // Previously this arrived as an inert "· configured dashboard" option that could not be corrected.
  assert.equal(await select.inputValue(), '__custom__');
  assert.equal(await input.inputValue(), '/dashboard-test/alpha');
  assert.equal(await input.isVisible(), true);
});

browserTest('Switching Custom to Auto and to a listed dashboard posts each choice', async (t) => {
  const { page, select, input, state } = await openDashboardPicker(t, { current: '/dashboard-test/alpha' });

  await sentOnce(page, () => select.selectOption(''));
  assert.equal(await input.isVisible(), false, 'the path input must be hidden once Auto is chosen');
  assert.deepEqual(state.posts, [''], 'Auto must post a blank, not the abandoned custom path');

  await sentOnce(page, () => select.selectOption('/lovelace'));
  assert.deepEqual(state.posts, ['', '/lovelace']);
});

browserTest('A view under an unknown dashboard warns but is still sent', async (t) => {
  const { page, row, select, input, state } = await openDashboardPicker(t);

  await select.selectOption('__custom__');
  await input.fill('/dashboard-test/alpha');
  // The renderer would silently fall back to the account default here, so the warning is the only
  // thing standing between the user and a panel showing the wrong dashboard for no visible reason.
  await assert.doesNotReject(row.locator('.hd-custom-note.warn').waitFor());

  await input.fill('/alpha/upper-floor');
  await assert.doesNotReject(row.locator('.hd-custom-note:not(.warn)').waitFor());

  // …and an unknown root must never block the send: the dashboard may not exist yet.
  await input.fill('/dashboard-test/alpha');
  await sentOnce(page, () => input.press('Tab'));
  assert.deepEqual(state.posts, ['/dashboard-test/alpha']);
});

browserTest('The dashboard picker fits a 480px panel without overflowing its card', async (t) => {
  const { row, select, input } = await openDashboardPicker(t, {
    current: '/dashboard-test/alpha', viewport: { width: 480, height: 480 },
  });

  const rowBox = await row.boundingBox();
  for (const [name, locator] of [['select', select], ['custom path input', input]]) {
    const box = await locator.boundingBox();
    assert.ok(box.width > 0, `${name} has no width at 480px`);
    assert.ok(
      box.x >= rowBox.x - 1 && box.x + box.width <= rowBox.x + rowBox.width + 1,
      `${name} overflows its row at 480px (${box.x}+${box.width} vs ${rowBox.x}+${rowBox.width})`,
    );
  }
});

browserTest('Custom is reachable when the account can see no dashboards at all', async (t) => {
  // The branch that used to disable the control outright — which is precisely when a path has to be
  // typed by hand: a non-admin account that sees nothing, or a catalogue Home Assistant will not return.
  const { page, select, input, state } = await openDashboardPicker(t, {
    current: '',
    catalog: { queried: true, items: [], default: { explicit: false, path: '' } },
  });

  assert.equal(await select.isDisabled(), false, 'the picker must stay usable with an empty catalogue');
  await select.selectOption('__custom__');
  await input.fill('/alpha/beta');
  await sentOnce(page, () => input.press('Tab'));

  assert.deepEqual(state.posts, ['/alpha/beta']);
});

browserTest('A malformed Custom path left behind never blocks a later Auto or listed save', async (t) => {
  // A path input that keeps a validity constraint while it is not the live control leaves an INVALID
  // hidden control in the row once a malformed value is typed and then abandoned. The row-wide validity
  // scan finds it, Save is refused, and reportValidity() on something invisible shows nothing — a save
  // that can never succeed with no cause on screen.
  const { page, select, input, state } = await openDashboardPicker(t, { current: '/alpha' });

  await select.selectOption('__custom__');
  await input.fill('http://elsewhere.example/x');   // rejected by the client pattern
  // Auto: the bad value is now hidden, not cleared
  await sentOnce(page, () => select.selectOption(''));
  assert.deepEqual(state.posts, [''], 'Auto must be sent even after an abandoned malformed Custom path');

  await select.selectOption('__custom__');
  await input.fill('..//bad');
  await sentOnce(page, () => select.selectOption('/lovelace'));   // a listed dashboard, same trap
  assert.deepEqual(state.posts, ['', '/lovelace']);
  assert.equal(await page.locator('#cfg-home_dashboard .cfg-row-message').count(), 0);
});

browserTest('The client never refuses a dashboard route the server would accept', async (t) => {
  // Shared matrix, not a second opinion. Two independently maintained expressions once agreed as
  // strings while differing in behaviour, so both sides read tools/test/fixtures/dashboard-path-parity.json.
  // Exact parity is unreachable — the server percent-decodes to reject traversal and a client
  // expression cannot — so the contract is one-directional: the client may be a superset, never
  // stricter. A stricter client blocks a legal route, worst when the catalogue is unavailable and
  // Custom is the only way in.
  const matrix = JSON.parse(await readFile(join(process.cwd(), 'fixtures', 'dashboard-path-parity.json'), 'utf8'));
  const { select, input } = await openDashboardPicker(t, { current: '/alpha' });
  await select.selectOption('__custom__');

  const refused = [];
  for (const route of matrix.serverAccepts) {
    await input.fill(route);
    if (!(await input.evaluate((el) => el.checkValidity()))) refused.push(route);
  }
  assert.deepEqual(refused, [], 'client refused routes the server admits');

  // The client is a superset by design, so it is not asked to mirror every server refusal — only to
  // catch the shapes worth stopping before the round trip. Deciding traversal needs decoding it does
  // not do, which is precisely why the server stays the authority.
  const admitted = [];
  for (const route of matrix.clientMustReject) {
    await input.fill(route);
    if (await input.evaluate((el) => el.checkValidity())) admitted.push(route);
  }
  assert.deepEqual(admitted, [], 'client admitted routes it must catch before the round trip');
});

// --- Entities page: catalogue search feedback (issue #114) -----------------------------------------
//
// The reporter's page was tall enough that a working search looked broken: the matches land in the
// fourth card, and nothing near the box said so. These run against the real entities.js and info.css
// in a real viewport, because the thing under test is where the page ends up, not what a shim recorded.

// Mirrors entitiesBody() in PaneldServer.kt. The server markup is pinned separately by
// EntitySearchFeedbackUiContractTest; this fixture only has to be a faithful stand-in for it.
function entitiesFixture() {
  const table = (id, title, short, filter) => `
    <div class="card entity-list" data-filter="${filter}" data-table="${id}" data-short="${short}"><h2>${title}</h2>
      <div class="entity-bulk"><button class="pbtn" data-bulk="pinned">Pin selected</button><span class="muted entity-selected">0 selected</span></div>
      <div class="tablewrap"><table class="entity-table"><thead><tr>
        <th class="col-select"><input type="checkbox" class="entity-select-page" aria-label="Select this page"></th>
        <th class="col-entity"><button data-sort="entity_id">Entity</button></th>
        <th class="col-access"><button data-sort="access_1h">Accesses</button></th>
        <th class="col-rate"><button data-sort="rate_1h_bps">Data rate</button></th>
        <th class="col-reason"><button data-sort="reasons">Reason</button></th>
        <th class="col-last"><button data-sort="last_access_at">Last access</button></th>
        <th class="col-override"><button data-sort="override">Override</button></th>
      </tr></thead><tbody></tbody></table></div>
      <div><button class="pbtn entity-prev">Previous</button><button class="pbtn entity-next">Next</button><span class="muted entity-msg">Loading…</span></div>
    </div>`;
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css">${i18nBridge()}</head><body>
    <div class="cards entity-cards">
      <div class="card"><h2>Entity subscription filter</h2>
        <div id="entity-status">Loading…</div>
        <div><button class="pbtn" id="entity-sync">Scan dashboard now</button><button class="pbtn" id="entity-activate" disabled>Checking…</button><button class="pbtn" id="entity-reset" type="button">Reset learned data</button></div>
        <div id="entity-action-result" class="entity-action-result muted" role="status" aria-live="polite"></div>
        <fieldset class="entity-policy"><legend>Automatic promotion</legend>
          <label><input type="checkbox" id="entity-auto-static"> Static</label>
          <label><input type="checkbox" id="entity-auto-runtime"> Runtime</label>
        </fieldset>
      </div>
      <div class="entity-search-row">
        <label class="sr-only" for="entity-search">Search the complete Home Assistant entity catalogue</label>
        <input id="entity-search" type="search" autocomplete="off" placeholder="Search the complete Home Assistant entity catalogue" aria-describedby="entity-search-status">
        <div id="entity-search-status" class="entity-search-status muted" role="status" aria-live="polite"></div>
      </div>
      <div class="card entity-issues" id="entity-issues"><h2>Entity-discovery compatibility</h2>
        <div id="entity-issues-summary" class="muted" role="status" aria-live="polite"></div>
        <div id="entity-issues-list" class="entity-issues-list"></div>
        <section id="entity-dynamic" class="entity-dynamic" hidden><h3>Dynamic expressions</h3><div id="entity-dynamic-list"></div></section>
        <button class="pbtn" id="entity-issues-rescan" type="button">Re-scan</button>
      </div>
      ${table('current', 'Current subscribed entities', 'Current', 'subscribed')}
      ${table('suggested', 'Suggested dashboard entities', 'Suggested', 'candidate')}
      ${table('review', 'Stale or noisy entities', 'Stale or noisy', 'review')}
    </div>
    <script src="/assets/entities.js"></script>
  </body></html>`;
}

function entityRows(count, prefix) {
  return Array.from({ length: count }, (_, index) => ({
    entity_id: `${prefix}.entity_${index}`, reasons: 'dashboard', static: true,
    access_1m: 0, access_1h: 1, access_1d: 2, rate_1m_bps: 0, rate_1h_bps: 0, rate_1d_bps: 0, last_access: Date.now(),
  }));
}

async function startEntitiesHarness(t, { matches = { subscribed: 60, candidate: 3, review: 0 } } = {}) {
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/dashboard/entities/sync') {
      return json({
        state: 'active', sync_running: false, stream_entity_count: 120, stream_mode: 'filtered',
        catalog_count: 3769, suggested_count: 3, last_sync_at: Date.now(), db_bytes: 1024,
        auto_static: true, auto_runtime: true, apply_required: false,
        blocking_issue_count: 0, ignored_issue_count: 0, unresolved_count: 0,
      });
    }
    if (path === '/api/v1/dashboard/entities/issues') {
      return json({ items: [], dashboard_issue_count: 0, blocking_issue_count: 0, ignored_issue_count: 0, dynamic_expressions: [] });
    }
    if (path === '/api/v1/dashboard/entities') {
      const params = new URL(request.url, 'http://panel.test').searchParams;
      const filter = params.get('filter');
      const query = (params.get('q') || '').trim();
      // Without a query the Current table is long — the tall page the reporter actually had.
      const total = query ? matches[filter] : (filter === 'subscribed' ? 120 : 0);
      return json({ items: entityRows(Math.min(total, 100), filter === 'candidate' ? 'light' : 'sensor'), total });
    }
    if (path === '/api/v1/dashboard/entities/policy') return json({ ok: true });
  }, entitiesFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 900 } });
  page.setDefaultTimeout(4_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('[data-table="current"] tbody tr').first().waitFor();
  return page;
}

const suggestedTop = (page) => page.locator('[data-table="suggested"]').evaluate((node) => node.getBoundingClientRect().top);

// A smooth scroll runs on the compositor, during which headless Chromium stops servicing
// requestAnimationFrame — Playwright's default polling. Every wait that straddles one polls on a timer.
const settled = (page, predicate) => page.waitForFunction(predicate, null, { polling: 100 });
const suggestedTopIn = (page) => page.evaluate(() =>
  document.querySelector('[data-table="suggested"]').getBoundingClientRect().top);
// A smooth scroll is still travelling when scrollY first becomes non-zero. Anything that then asserts
// on a scroll position has to let the animation land first, or it measures a moving target.
async function scrollRest(page) {
  let previous = -1;
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const now = await page.evaluate(() => window.scrollY);
    if (now === previous) return now;
    previous = now;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  return previous;
}

browserTest('Catalogue search reports its per-section counts and brings Suggested into view', async (t) => {
  const page = await startEntitiesHarness(t);
  const viewport = await page.evaluate(() => window.innerHeight);

  assert.equal(await page.evaluate(() => window.scrollY), 0);
  assert.equal(await suggestedTopIn(page) >= viewport, true, 'Suggested must start below the fold for this to mean anything');
  assert.equal(await page.locator('#entity-search-status').textContent(), '');

  await page.locator('#entity-search').fill('kitchen');
  // The acknowledgement is immediate, ahead of the 250ms debounce and any request.
  await page.locator('#entity-search-status').getByText('Searching…').waitFor();

  await settled(page, () => document.getElementById('entity-search-status').textContent.startsWith('Matches:'));
  assert.equal(
    await page.locator('#entity-search-status').textContent(),
    'Matches: Current: 60, Suggested: 3, Stale or noisy: 0',
  );

  await settled(page, () => {
    const rect = document.querySelector('[data-table="suggested"]').getBoundingClientRect();
    return rect.top < window.innerHeight && rect.bottom > 0;
  });
  assert.equal(await scrollRest(page) > 0, true, 'the page moved to reach Suggested');
  assert.equal(await page.evaluate(() => document.activeElement.id), 'entity-search', 'focus must stay in the search box');
});

browserTest('Only a typed query moves the Entities page', async (t) => {
  const page = await startEntitiesHarness(t);
  const viewport = await page.evaluate(() => window.innerHeight);
  const belowFold = async () => (await suggestedTopIn(page)) >= viewport;

  // Repainting a section empties its tbody before refilling it, so the page briefly collapses and the
  // browser clamps the scroll offset. That predates this change — it is what render() has always done —
  // and it is asserted here so the negative cases below cannot be credited to it.
  await page.evaluate(() => window.scrollTo(0, 400));
  await page.evaluate(() => document.querySelectorAll('.entity-list tbody').forEach((body) => { body.innerHTML = ''; }));
  assert.equal(await page.evaluate(() => window.scrollY) < 400, true, 'clearing the tables alone already clamps the scroll');

  // A mutation refresh repaints every section — refilling the tables emptied above — and must never
  // carry the viewport to Suggested.
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.locator('#entity-auto-static').click();   // the harness echoes the old policy back, so the box may re-tick; only the save is under test
  await settled(page, () => !document.getElementById('entity-auto-static').disabled
    && document.querySelectorAll('[data-table="current"] tbody tr').length === 100);
  assert.equal(await page.evaluate(() => window.scrollY), 0, 'a policy save must not move the page');
  assert.equal(await belowFold(), true, 'a policy save must leave Suggested below the fold');

  // The positive control: a typed query does move the page, so the negatives are not passing vacuously.
  await page.locator('#entity-search').fill('kitchen');
  await settled(page, () => document.getElementById('entity-search-status').textContent.startsWith('Matches:'));
  // A smooth scroll can pause longer than scrollRest's 100 ms sample on a loaded runner; wait on the outcome.
  await settled(page, () => document.querySelector('[data-table="suggested"]').getBoundingClientRect().top < window.innerHeight);
  await scrollRest(page);   // let the animation land before the next step resets the offset

  // Clearing restores the unfiltered lists without chasing anything.
  await page.evaluate(() => window.scrollTo(0, 0));
  await scrollRest(page);
  await page.locator('#entity-search').fill('');
  await settled(page, () => document.getElementById('entity-search-status').textContent === ''
    && document.querySelectorAll('[data-table="current"] tbody tr').length === 100);
  assert.equal(await scrollRest(page), 0, 'clearing the box must not move the page');
  assert.equal(await belowFold(), true, 'clearing the box must leave Suggested below the fold');
});

browserTest('The search status line reserves its height, so feedback shifts nothing below it', async (t) => {
  const page = await startEntitiesHarness(t);
  // Document-relative, not viewport-relative: announcing the counts also starts a reveal of the
  // matched card, and that scroll can land after the measurement, moving a viewport-relative top by
  // the scroll delta while nothing about the layout has changed. This test is about the reserved
  // height, so it measures the position in the document, which a scroll cannot alter.
  const issuesTop = () => page.locator('#entity-issues').evaluate((node) => node.getBoundingClientRect().top + window.scrollY);

  const before = await issuesTop();
  await page.locator('#entity-search').fill('kitchen');
  await page.locator('#entity-search-status').getByText('Searching…').waitFor();
  await page.evaluate(() => window.scrollTo(0, 0));
  assert.equal(Math.abs((await issuesTop()) - before) < 1, true, 'the appearing status line must not move the card below it');

  await page.waitForFunction(() => document.getElementById('entity-search-status').textContent.startsWith('Matches:'));
  await page.evaluate(() => window.scrollTo(0, 0));
  assert.equal(Math.abs((await issuesTop()) - before) < 1, true, 'the counts line must not move the card below it');
});

browserTest('Configure stops promising a value is about to apply once the panel cannot apply it', async (t) => {
  // The defect this closes: "waiting to apply" stands forever on a panel whose root path is absent, over a
  // setting the user never touched. The desired value is still saved and still retried, so the row keeps
  // showing it — only the promise changes, and it changes on the open page as the panel reports it.
  let stalled = [];
  let documents = 0;
  const schema = [
    { key: 'silence_boot_chime', label: 'Silence boot chime', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'touch_sound', label: 'Touch sound', group: 'Behaviour', type: 'BOOL', available: true },
  ];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config' || path === '/api/v1/config/values') {
      return json({
        settings: { silence_boot_chime: false, touch_sound: false },
        apply_pending: { silence_boot_chime: 'true', touch_sound: 'true' },
        apply_stalled: stalled, renderer_pending: [],
        ha_expose: {}, ha_auth: { configured: false },
      });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
  }, () => { documents++; return fixture(); });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });

  const chime = page.locator('#cfg-silence_boot_chime .apply-pending-status');
  const touch = page.locator('#cfg-touch_sound .apply-pending-status');
  await chime.waitFor();
  assert.match(await chime.textContent(), /hardware application is pending/,
    'while the panel may still apply it, waiting is the truthful thing to say');
  assert.match(await touch.textContent(), /hardware application is pending/);

  // Two independent boots have now found no apply path for the boot chime. Touch sound has not stalled.
  stalled = ['silence_boot_chime'];
  await chime.getByText(/no way to apply it yet/).waitFor();
  assert.doesNotMatch(await chime.textContent(), /pending/, 'the stalled key must not still be promised');
  assert.match(await touch.textContent(), /hardware application is pending/,
    'a key that has not stalled keeps the ordinary note');
  assert.equal(documents, 1, 'the open page follows the panel without reloading');

  // The desired value is unchanged durable state: the row still shows what was saved, not the hardware.
  assert.equal(
    await page.locator('#cfg-silence_boot_chime [role=switch]').getAttribute('aria-checked'), 'true',
    'a stalled value is still the saved desired value');
});

browserTest('Panel proximity Auto-sleep works without HA prerequisites or an HA history chart', async (t) => {
  let prerequisiteGets = 0, historyGets = 0;
  const schema = [
    { key: 'auto_sleep_source', label: 'Auto-sleep presence source', group: 'Behaviour', type: 'ENUM', available: true, options: ['panel', 'home_assistant'] },
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
  ];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: true, auto_sleep_source: 'panel' }, ha_expose: {} });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio' || path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') { prerequisiteGets++; return json({ eligible: false, phase: 'unassigned' }); }
    if (path === '/api/v1/auto-sleep/history') { historyGets++; return json({ available: false }); }
    if (path === '/api/v1/auto-sleep') return json({ enabled: true, source: 'panel', phase: 'source_unavailable', reason: 'all_sources_unavailable' });
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const source = page.locator('#cfg-auto_sleep_source .choice-list');
  await source.waitFor();
  await page.locator('#cfg-proximity-learning').waitFor();
  assert.deepEqual(await page.locator('#cfg-groups').evaluate((root) => Array.from(root.children).map((card) =>
    card.getAttribute('data-config-group') || card.id
  )), ['Auto-sleep', 'cfg-proximity-learning'],
  'Presence follows the dedicated Auto-sleep card even when its script attaches after Configure');
  assert.equal(await page.locator('[data-config-group="Auto-sleep"] #cfg-auto_sleep').count(), 1);
  assert.equal(await page.locator('[data-config-group="Auto-sleep"] #cfg-auto_sleep_source').count(), 1);
  assert.deepEqual(await source.locator('span').allTextContents(), ['This panel’s proximity sensor', 'Home Assistant Area devices']);
  const toggle = page.locator('#cfg-auto_sleep [role=switch]');
  assert.equal(await toggle.getAttribute('aria-disabled'), 'false');
  await toggle.click();
  assert.equal(await toggle.getAttribute('aria-disabled'), 'false');
  await toggle.click();
  await page.locator('#auto-sleep-status').waitFor();
  assert.equal(await toggle.getAttribute('aria-checked'), 'true');
  assert.equal(await page.locator('#auto-sleep-chart').count(), 0);
  assert.equal(await page.locator('#auto-sleep-status a').getAttribute('href'), '#cfg-proximity-learning');
  assert.equal(prerequisiteGets, 0);
  assert.equal(historyGets, 0);
  await source.locator('input[value="home_assistant"]').check();
  await page.waitForFunction(() => document.querySelector('#cfg-auto_sleep [role=switch]').getAttribute('aria-checked') === 'false');
  assert.equal(await page.locator('#cfg-auto_sleep [role=switch]').getAttribute('aria-disabled'), 'true');
  assert.ok(prerequisiteGets > 0);
});

for (const [engine, launcher] of [['Chromium', chromium], ['WebKit', webkit]]) {
  browserTest(`Configure maximum brightness previews, validates and fits in ${engine}`, async (t) => {
    let settings = { auto_brightness: 'true', auto_brightness_minimum_percent: '12', auto_brightness_maximum_percent: '100' };
    const posts = [];
    const harness = await startHarness(async (path, request) => {
      if (path === '/api/v1/config/schema') return json([
        { key: 'auto_brightness', label: 'Adaptive brightness', group: 'Display', type: 'BOOL', available: true },
        { key: 'auto_brightness_minimum_percent', label: 'Minimum level', group: 'Display', type: 'INT', min: 4, max: 99, available: true },
        { key: 'auto_brightness_maximum_percent', label: 'Maximum level', summary: 'Highest level auto-brightness will choose.', group: 'Display', type: 'INT', min: 5, max: 100, available: true },
      ]);
      if (path === '/api/v1/config') {
        if (request.method === 'POST') {
          const values = Object.fromEntries(new URLSearchParams(await requestBody(request)));
          posts.push(values);
          // The panel checks each bound against the stored other one (SettingsRegistry).
          const next = { ...settings, ...values };
          if (Number(next.auto_brightness_maximum_percent) <= Number(next.auto_brightness_minimum_percent)) {
            return json({ error: 'invalid-value', message: 'auto_brightness_maximum_percent: must be above the minimum level' }, 400);
          }
          settings = next; return json({ settings, ha_expose: {}, ha_auth: {} });
        }
        return json({ settings, ha_expose: {}, ha_auth: {} });
      }
      if (path === '/api/v1/config/values') return json({ settings, ha_expose: {}, ha_auth: {}, apply_pending: {}, apply_stalled: [], renderer_pending: [] });
      if (path === '/api/v1/auto-brightness') return json({ available: true, sourceAvailable: true, latestLux: 42 });
      if (path === '/api/v1/auto-brightness/history') return json({ points: [], bucket_minutes: 5 });
      if (path === '/api/v1/apps') return json({ apps: [] });
      if (path === '/api/v1/radio' || path === '/api/v1/proximity') return json({ present: false });
      if (path === '/health') return { body: 'ok cfg=test' };
      return json({});
    }, configureVisualFixture);
    const browser = await launcher.launch(engine === 'Chromium' ? { executablePath: chrome, headless: true } : { headless: true });
    const page = await browser.newPage({ viewport: { width: 320, height: 600 } });
    page.setDefaultTimeout(5_000);
    t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
    await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
    const maximum = page.locator('#cfg-auto_brightness_maximum_percent input');
    await page.waitForFunction(() => document.querySelector('#cfg-auto_brightness_maximum_percent input')?.disabled === false);
    const preview = page.waitForRequest((request) => new URL(request.url()).searchParams.get('maximum_percent') === '70');
    const committed = page.waitForResponse((response) => response.url().endsWith('/api/v1/config') && response.request().method() === 'POST');
    await maximum.fill('70'); await maximum.press('Enter');
    assert.equal(new URL((await preview).url()).searchParams.get('minimum_percent'), '12');
    await committed;
    assert.deepEqual(posts, [{ auto_brightness_maximum_percent: '70' }]);
    for (const width of [320, 768, 1200]) {
      await page.setViewportSize({ width, height: 600 });
      const bounds = await (await page.waitForFunction(() => {
        const row = document.querySelector('#cfg-auto_brightness_maximum_percent');
        if (!row) return false;
        row.scrollIntoView({ block: 'nearest' });
        return row.clientWidth > 0 ? { width: row.clientWidth, scrollWidth: row.scrollWidth } : false;
      })).jsonValue();
      assert.ok(bounds.width > 0 && bounds.scrollWidth <= bounds.width + 1, `${engine} row overflows at ${width}: ${JSON.stringify(bounds)}`);
    }
    // A maximum at the minimum is refused by the panel: the row says why and shows the stored maximum again.
    await maximum.fill('12'); await maximum.press('Enter');
    const refusal = 'Not saved: auto_brightness_maximum_percent: must be above the minimum level';
    await page.locator('#cfg-auto_brightness_maximum_percent .cfg-row-message').getByText(refusal, { exact: true }).waitFor();
    await page.waitForFunction((text) => document.getElementById('cfg-msg').textContent === text, refusal);
    assert.equal(await maximum.inputValue(), '70');
    assert.deepEqual(posts, [{ auto_brightness_maximum_percent: '70' }, { auto_brightness_maximum_percent: '12' }]);
    // An out-of-range maximum is never sent: the row names the rule and the stored maximum comes back.
    await maximum.fill('101'); await maximum.press('Enter');
    const rule = 'Maximum level must be a whole number between 5 and 100.';
    await page.locator('#cfg-auto_brightness_maximum_percent .cfg-row-message').getByText(rule, { exact: true }).waitFor();
    await page.waitForFunction((text) => document.getElementById('cfg-msg').textContent === text, rule);
    assert.equal(await maximum.inputValue(), '70');
    assert.equal(posts.length, 2);
    const sent = page.waitForResponse((response) => response.url().endsWith('/api/v1/config') && response.request().method() === 'POST');
    await maximum.fill('60'); await maximum.press('Enter');
    await sent;
    assert.deepEqual(posts.at(-1), { auto_brightness_maximum_percent: '60' });
    await page.waitForFunction(() => !document.querySelector('#cfg-auto_brightness_maximum_percent .cfg-row-message'));
    await page.reload({ waitUntil: 'domcontentloaded' });
    assert.equal(await maximum.inputValue(), '60');
  });
}

for (const [engine, launcher] of [['Chromium', chromium], ['WebKit', webkit]]) {
  browserTest(`Auto-sleep choices wrap and retain native arrow-key focus in ${engine}`, async (t) => {
    const settings = { auto_sleep_source: 'panel' };
    const posts = [];
    const harness = await startHarness(async (path, request) => {
      if (path === '/api/v1/config/schema') return json([{ key: 'auto_sleep_source', label: 'Activity source', group: 'Behaviour', type: 'ENUM', available: true, options: ['panel', 'home_assistant', 'touch'] }]);
      if (path === '/api/v1/config') {
        if (request.method === 'POST') {
          const values = Object.fromEntries(new URLSearchParams(await requestBody(request)));
          posts.push(values); Object.assign(settings, values); return json({});
        }
        return json({ settings, ha_expose: {} });
      }
      if (path === '/api/v1/apps') return json({ apps: [] });
      if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: false, phase: 'unassigned' });
      if (path === '/api/v1/radio' || path === '/api/v1/proximity') return json({ present: false });
    });
    const browser = await launcher.launch(engine === 'Chromium' ? { executablePath: chrome, headless: true } : { headless: true });
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    t.after(async () => { await browser.close(); await new Promise(resolve => harness.server.close(resolve)); });
    await page.goto(harness.url);
    const selected = page.locator('#cfg-auto_sleep_source input:checked');
    await selected.focus();
    // The send's settle re-renders the row; focus returns to the chosen radio.
    const focusSettled = () => page.waitForFunction(() => document.activeElement === document.querySelector('#cfg-auto_sleep_source input:checked'), null, { timeout: 1_000 });
    await page.keyboard.press('ArrowDown');
    assert.equal(await selected.inputValue(), 'home_assistant');
    await focusSettled();
    await page.keyboard.press('ArrowDown');
    assert.equal(await selected.inputValue(), 'touch');
    await focusSettled();
    assert.equal(await selected.count(), 1);
    // Each arrow key is a finished choice, sent at once; focus stays on the chosen radio across the sends.
    for (let attempt = 0; attempt < 40 && posts.length < 2; attempt++) await page.waitForTimeout(25);
    assert.deepEqual(posts, [{ auto_sleep_source: 'home_assistant' }, { auto_sleep_source: 'touch' }]);
    await page.waitForTimeout(300);
    assert.equal(await selected.inputValue(), 'touch');
    assert.equal(await selected.evaluate(input => input === document.activeElement), true);
    assert.deepEqual(await page.locator('#cfg-auto_sleep_source .choice-list span').evaluateAll(nodes => nodes.map(node => node.scrollWidth <= node.clientWidth)), [true, true, true]);
  });
}
