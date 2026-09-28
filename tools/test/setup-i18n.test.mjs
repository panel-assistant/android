import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';

const defaultAsset = fileURLToPath(new URL('../../app/src/main/assets/setup.js', import.meta.url));
const setupAsset = process.argv[2] ? resolve(process.argv[2]) : defaultAsset;
const chrome = process.env.CHROME || '/usr/bin/chromium';
const browserTest = existsSync(chrome) ? test : test.skip;

const STAGES = [
  'identity', 'renderer', 'ha_url', 'ha_credentials', 'home_dashboard',
  'mqtt_broker', 'mqtt_credentials', 'mqtt_connection', 'entity_filter', 'render_proof',
];

function journey(next, overrides = {}) {
  const statuses = overrides.statuses || {};
  return {
    complete: false,
    repair: false,
    next,
    panel: { id: 'sample_panel', name: 'Sample panel' },
    discovery: {},
    home_dashboard: { value: '' },
    entity_filter: {
      relevant: true, enabled: false, counting: false, count: 321,
      level: 'green', confidence: 'estimated', tier: 'capable',
    },
    steps: STAGES.map((stage) => ({
      stage,
      status: statuses[stage]?.status || (stage === next ? 'pending' : 'unknown'),
      detail: statuses[stage]?.detail || '',
    })),
    ...overrides,
    statuses: undefined,
  };
}

function json(body, status = 200) {
  return { status, headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) };
}

function requestBody(request) {
  return new Promise((resolveBody, reject) => {
    let body = '';
    request.setEncoding('utf8');
    request.on('data', (chunk) => { body += chunk; });
    request.on('end', () => resolveBody(body));
    request.on('error', reject);
  });
}

function fixture(translations, locale, withHelper) {
  const helper = withHelper ? `<script>
    window.__i18nCalls=[];
    window.HaI18n={locale:${JSON.stringify(locale)},t:(key,fallback,values)=>{
      window.__i18nCalls.push({key,fallback,values:values||null});
      const catalogue=${JSON.stringify(translations)};
      const selected=Object.prototype.hasOwnProperty.call(catalogue,key)?catalogue[key]:fallback;
      return String(selected == null?'':selected).replace(/\\{([A-Za-z][A-Za-z0-9_]*)\\}/g,
        (placeholder,name)=>values&&Object.prototype.hasOwnProperty.call(values,name)?String(values[name]):placeholder);
    }};
  </script>` : '';
  return `<!doctype html><html lang="${locale}"><head><meta charset="utf-8"></head><body>
    <ol id="wiz-dots"></ol><main id="wiz-step"></main>
    <a class="wiz-escape" href="/configure">escape</a>
    ${helper}<script src="/setup.js"></script>
  </body></html>`;
}

async function startHarness({
  initialJourney,
  translations = {},
  locale = 'zh-Hans',
  withHelper = true,
  route,
  dashboards = { queried: true, items: [], default: { explicit: false, path: '' } },
  area = { queried: false, areas: [], device: { found: false }, admin: false, requested: '' },
  discovery = {},
}) {
  const source = await readFile(setupAsset, 'utf8');
  const state = { current: initialJourney, requests: [] };
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://panel.test');
    state.requests.push({ method: request.method, path: url.pathname, search: url.search, body: request.method === 'POST' ? await requestBody(request) : '' });
    let result;
    if (url.pathname === '/') result = { body: fixture(translations, locale, withHelper), headers: { 'content-type': 'text/html; charset=utf-8' } };
    else if (url.pathname === '/setup.js') result = { body: source, headers: { 'content-type': 'application/javascript; charset=utf-8' } };
    else if (url.pathname === '/api/v1/setup') result = json(state.current);
    else if (url.pathname === '/api/v1/config/discovery') result = json(discovery);
    else if (url.pathname === '/api/v1/config/home-dashboards') result = json(dashboards);
    else if (url.pathname === '/api/v1/config/ha-area') result = json(area);
    else if (route) result = await route(url, request, state);
    if (!result) result = json({ ok: true });
    response.writeHead(result.status || 200, result.headers);
    response.end(result.body || '');
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  return { server, state, url: `http://127.0.0.1:${server.address().port}` };
}

async function openRig(t, options, path = '/', browserType = chromium) {
  const harness = await startHarness(options);
  const browser = await browserType.launch(browserType === chromium ? { executablePath: chrome, headless: true } : { headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(3_000);
  t.after(async () => {
    await browser.close();
    await new Promise((done) => harness.server.close(done));
  });
  await page.goto(harness.url + path, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#wiz-step .card').waitFor();
  return { ...harness, browser, page };
}

for (const [engine, browserType] of [['Chromium', chromium], ['WebKit', webkit]]) {
  const run = existsSync(browserType === chromium ? chrome : browserType.executablePath()) ? test : test.skip;
  run(`Setup explains the Home Assistant address for built-in and Companion renderers in ${engine}`, async (t) => {
    for (const renderer of ['builtin', 'companion']) {
      const rig = await openRig(t, {
        initialJourney: journey('ha_url', {
          statuses: renderer === 'companion' ? {
            home_dashboard: { status: 'skipped', detail: 'foreign_renderer' },
            entity_filter: { status: 'skipped', detail: 'foreign_renderer' },
          } : {},
        }),
        withHelper: false,
        locale: 'en',
      }, '/', browserType);
      assert.equal(await rig.page.locator('.card h2').textContent(), 'Where is Home Assistant?');
      assert.equal(await rig.page.locator('.card .wiz-lead').textContent(), 'ha-paneld uses this address to connect to Home Assistant.');
      assert.equal(await rig.page.locator('#wiz-ha_url').count(), 1);
    }
  });
}

async function reloadJourney(rig, nextJourney, path = '/?lang=zh-Hans') {
  rig.state.current = nextJourney;
  await rig.page.goto(rig.url + path, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await rig.page.locator('#wiz-step .card').waitFor();
}

async function eventually(read, accepted, timeoutMs = 3_000) {
  const deadline = Date.now() + timeoutMs;
  let value;
  do {
    value = await read();
    if (accepted(value)) return value;
    await new Promise((done) => setTimeout(done, 25));
  } while (Date.now() < deadline);
  return value;
}

browserTest('Setup localizes representative cards and stable state tokens with an English identity fallback', async (t) => {
  const translations = {
    'setup.identity.title': '命名此面板',
    'setup.identity.panel_id.label': '面板 ID',
    'setup.action.save_continue': '保存并继续',
    'setup.sign_in.title': '登录 Home Assistant',
    'setup.sign_in.state.in_progress': '登录正在进行中',
    'setup.filter.title': '加载仪表盘前还有一项',
    'setup.filter.red.head': '启用筛选',
    'setup.filter.confidence.measured': '实测',
    'setup.filter.tier.modest': '性能有限的面板',
    'setup.proof.title': '即将完成',
    'setup.proof.milestone.reading.one': '读取了 {count} 个实体',
    'setup.proof.milestone.reading.other': '读取了 {count} 个实体（复数）',
    'setup.done.title': '✓ 此面板已设置',
    'setup.done.status.attested_mqtt': 'MQTT 已连接 · 仪表盘已由你确认。',
  };
  const rig = await openRig(t, { initialJourney: journey('identity'), translations });

  assert.equal(await rig.page.locator('.card h2').textContent(), '命名此面板');
  assert.equal(await rig.page.locator('label[for], .wiz-fld').first().locator('b').textContent(), '面板 ID');
  assert.equal(await rig.page.locator('button.wiz-primary').textContent(), '保存并继续');

  await reloadJourney(rig, journey('ha_credentials', {
    statuses: { ha_credentials: { status: 'in_flight' } },
  }));
  assert.equal(await rig.page.locator('.card h2').textContent(), '登录 Home Assistant');
  assert.equal(await rig.page.locator('#wiz-live').textContent(), '登录正在进行中');

  await reloadJourney(rig, journey('entity_filter', {
    entity_filter: {
      relevant: true, enabled: false, counting: false, count: 321,
      level: 'red', confidence: 'measured', tier: 'modest', tier_label: 'Chip tier <span>&"exact"',
    },
  }));
  assert.equal(await rig.page.locator('.card h2').textContent(), '加载仪表盘前还有一项');
  assert.equal(await rig.page.locator('#ef-head').textContent(), '启用筛选');
  assert.equal(await rig.page.locator('#ef-tag').textContent(), '实测');
  assert.equal(await rig.page.locator('#ef-panel').textContent(), 'Chip tier <span>&"exact"');
  assert.equal(await rig.page.locator('#ef-panel span').count(), 0);
  await rig.page.waitForFunction(() => document.querySelector('#ef-count')?.textContent === '321');
  assert.equal(await rig.page.locator('#ef-count').textContent(), '321');

  await reloadJourney(rig, journey('entity_filter', {
    entity_filter: {
      relevant: true, enabled: false, counting: false, count: 321,
      level: 'red', confidence: 'measured', tier: 'modest',
    },
  }));
  assert.equal(await rig.page.locator('#ef-panel').textContent(), '性能有限的面板');

  await reloadJourney(rig, journey('render_proof', {
    statuses: { render_proof: { status: 'in_flight' } },
    entity_filter: { relevant: true, enabled: true, learning: { scanned: 1, applied: false } },
  }));
  assert.equal(await rig.page.locator('.card h2').textContent(), '即将完成');
  // CLDR correctly classifies both 1 and 2 as `other` for zh-Hans.
  assert.equal(await rig.page.locator('.wiz-milestone').first().textContent(), '读取了 1 个实体（复数）');

  await reloadJourney(rig, journey('render_proof', {
    statuses: { render_proof: { status: 'in_flight' } },
    entity_filter: { relevant: true, enabled: true, learning: { scanned: 2, applied: false } },
  }));
  assert.equal(await rig.page.locator('.wiz-milestone').first().textContent(), '读取了 2 个实体（复数）');

  await reloadJourney(rig, journey('render_proof', {
    complete: true,
    statuses: {
      render_proof: { status: 'satisfied', detail: 'user_attested' },
      mqtt_connection: { status: 'satisfied' },
    },
    entity_filter: { relevant: true, enabled: false },
  }));
  assert.equal(await rig.page.locator('.card h2').textContent(), '✓ 此面板已设置');
  assert.equal(await rig.page.locator('#wiz-live').textContent(), 'MQTT 已连接 · 仪表盘已由你确认。');

  const fallback = await openRig(t, { initialJourney: journey('identity'), withHelper: false, locale: 'en' });
  assert.equal(await fallback.page.locator('.card h2').textContent(), 'Name this panel');
  assert.equal(await fallback.page.locator('button.wiz-primary').textContent(), 'Save and continue');
});

browserTest('Setup renders translated markup as text and keeps package, dashboard, area and path evidence byte-exact', async (t) => {
  const packageId = 'com.vendor.<panel>&"raw"';
  const dashboardTitle = '厨房 <主屏>& "早晨"';
  const dashboardPath = '/wall-tablet/example?mode=a&b=2#lights';
  const areaName = '厨房 <东>&西';
  const injection = '<img src=x onerror="window.__injected=1">\u2028\u2029';
  const translations = {
    'setup.renderer.title': injection,
    'setup.renderer.failure.package_missing': '缺少软件包：{package}',
    'setup.renderer.action.open_dashboard_setting': '打开仪表盘设置',
    'setup.dashboard.title': '为此面板选择仪表盘',
    'setup.area.label': '选择 HA 区域',
    'setup.dashboard.group.ha': 'Home Assistant 仪表盘',
    'setup.dashboard.action.use': '使用此仪表盘',
  };
  const rig = await openRig(t, {
    initialJourney: journey('renderer', {
      statuses: { renderer: { status: 'blocked', detail: packageId } },
    }),
    translations,
    dashboards: {
      queried: true,
      items: [{ group: 'panel', title: dashboardTitle, path: dashboardPath }],
      default: { explicit: true, path: dashboardPath },
    },
    area: {
      queried: true,
      areas: [{ name: areaName }],
      device: { found: false }, admin: true, requested: areaName,
    },
  }, '/?lang=zh-Hans');

  assert.equal(await rig.page.locator('.card h2').textContent(), injection);
  assert.equal(await rig.page.locator('#wiz-step img').count(), 0);
  assert.equal(await rig.page.evaluate(() => window.__injected), undefined);
  assert.equal(await rig.page.locator('.setup p').first().textContent(), `缺少软件包：${packageId}`);
  assert.equal(await rig.page.locator('a.pbtn').getAttribute('href'), 'configure?lang=zh-Hans#cfg-dashboard_package');

  await reloadJourney(rig, journey('home_dashboard', {
    home_dashboard: { value: dashboardPath },
  }));
  await rig.page.locator('#wiz-home_dashboard').waitFor();
  assert.equal(await rig.page.locator('.card h2').textContent(), '为此面板选择仪表盘');
  assert.equal(await rig.page.locator('#wiz-home_dashboard option').filter({ hasText: dashboardTitle }).textContent(), dashboardTitle);
  assert.equal(await rig.page.locator('#wiz-home_dashboard').inputValue(), dashboardPath);
  assert.equal(await rig.page.locator('#wiz-ha_area option').filter({ hasText: areaName }).textContent(), areaName);
  assert.equal(await rig.page.locator('#wiz-ha_area').inputValue(), areaName);
  assert.equal(await rig.page.locator('#wiz-step img').count(), 0);
});

browserTest('a Panel Assistant handover answers the filter on before releasing first render', async (t) => {
  const rig = await openRig(t, {
    initialJourney: journey('home_dashboard', {
      handover: { source: true },
      home_dashboard: { value: '/dashboard-wall' },
    }),
    locale: 'en',
    dashboards: { queried: true, items: [{ group: 'panel', title: 'Wall', path: '/dashboard-wall' }], default: { explicit: true, path: '/dashboard-wall' } },
  });
  await rig.page.locator('#wiz-home_dashboard').waitFor();
  await rig.page.locator('button.wiz-primary').click();
  await eventually(
    () => Promise.resolve(rig.state.requests.filter((request) => request.method === 'POST').length),
    (count) => count >= 4,
  );
  const posts = rig.state.requests.filter((request) => request.method === 'POST');
  assert.deepEqual(posts.map((request) => request.path), [
    '/api/v1/config', '/api/v1/setup/home-dashboard',
    '/api/v1/config', '/api/v1/setup/entity-filter',
  ]);
  assert.equal(new URLSearchParams(posts[2].body).get('dashboard_entity_learning'), 'true');
  assert.equal(posts[2].body.includes('ha_token'), false);
});

browserTest('declining a previously enabled filter disables it before the answer', async (t) => {
  const rig = await openRig(t, {
    initialJourney: journey('entity_filter', {
      entity_filter: { relevant: true, enabled: true, counting: false, count: 321, level: 'green', confidence: 'measured', tier: 'capable' },
    }),
    locale: 'en',
  });
  await rig.page.locator('#ef-decline').click();
  await eventually(
    () => Promise.resolve(rig.state.requests.filter((request) => request.method === 'POST').length),
    (count) => count >= 2,
  );
  const posts = rig.state.requests.filter((request) => request.method === 'POST');
  assert.deepEqual(posts.map((request) => request.path), ['/api/v1/config', '/api/v1/setup/entity-filter']);
  assert.equal(new URLSearchParams(posts[0].body).get('dashboard_entity_learning'), 'false');
});

browserTest('revisiting a handed-over dashboard does not undo an answered filter choice', async (t) => {
  const rig = await openRig(t, {
    initialJourney: journey('home_dashboard', {
      handover: { source: true },
      home_dashboard: { value: '/dashboard-wall' },
      statuses: { entity_filter: { status: 'satisfied' } },
      entity_filter: { relevant: true, answered: true, enabled: false },
    }),
    locale: 'en',
    dashboards: { queried: true, items: [{ group: 'panel', title: 'Wall', path: '/dashboard-wall' }], default: { explicit: true, path: '/dashboard-wall' } },
  });
  await rig.page.locator('#wiz-home_dashboard').waitFor();
  await rig.page.locator('button.wiz-primary').click();
  await eventually(
    () => Promise.resolve(rig.state.requests.filter((request) => request.method === 'POST').length),
    (count) => count >= 2,
  );
  const posts = rig.state.requests.filter((request) => request.method === 'POST');
  assert.deepEqual(posts.map((request) => request.path), ['/api/v1/config', '/api/v1/setup/home-dashboard']);
});

browserTest('Setup preserves the active locale on every JavaScript-authored cross-page link', async (t) => {
  const completeJourney = journey('render_proof', {
    complete: true,
    statuses: { render_proof: { status: 'satisfied', detail: 'builtin_frontend_connected' } },
    entity_filter: { relevant: true, enabled: true },
  });
  const rig = await openRig(t, {
    initialJourney: journey('render_proof', {
      statuses: { render_proof: { status: 'in_flight' } },
    }),
  }, '/?lang=zh-Hans');

  assert.equal(await rig.page.locator('#wiz-step a').getAttribute('href'), './?lang=zh-Hans');

  await reloadJourney(rig, journey('render_proof', {
    statuses: { render_proof: { status: 'blocked' } },
  }));
  assert.equal(await rig.page.locator('#wiz-step a').getAttribute('href'), './?lang=zh-Hans');

  await reloadJourney(rig, journey('renderer', {
    statuses: { renderer: { status: 'blocked', detail: 'webview_too_old_unfixable' } },
  }));
  assert.equal(await rig.page.locator('#wiz-step a').getAttribute('href'), './?lang=zh-Hans');

  await reloadJourney(rig, completeJourney);
  const hrefs = await rig.page.locator('#wiz-step a').evaluateAll((links) => links.map((link) => link.getAttribute('href')));
  assert.deepEqual(hrefs, [
    'configure?lang=zh-Hans',
    './?lang=zh-Hans',
    'install?lang=zh-Hans',
  ]);

  const haLanguage = await openRig(t, {
    initialJourney: completeJourney,
    locale: 'de',
  }, '/?ha_lang=de-DE');
  assert.deepEqual(
    await haLanguage.page.locator('#wiz-step a').evaluateAll((links) => links.map((link) => link.getAttribute('href'))),
    ['configure?lang=de', './?lang=de', 'install?lang=de'],
  );

  const explicitEnglish = await openRig(t, {
    initialJourney: completeJourney,
    // The request-localized catalogue is canonical: explicit `lang=en` wins over the HA fallback.
    locale: 'en',
  }, '/?lang=en&ha_lang=de');
  assert.deepEqual(
    await explicitEnglish.page.locator('#wiz-step a').evaluateAll((links) => links.map((link) => link.getAttribute('href'))),
    ['configure?lang=en', './?lang=en', 'install?lang=en'],
  );

  const unsupportedOverride = await openRig(t, {
    initialJourney: completeJourney,
    locale: 'de',
  }, '/?lang=nl-NL&ha_lang=de');
  assert.deepEqual(
    await unsupportedOverride.page.locator('#wiz-step a').evaluateAll((links) => links.map((link) => link.getAttribute('href'))),
    ['configure?lang=de', './?lang=de', 'install?lang=de'],
  );

  const pseudoLocale = await openRig(t, {
    initialJourney: completeJourney,
    locale: 'en-XA',
  }, '/?lang=en-XA');
  assert.deepEqual(
    await pseudoLocale.page.locator('#wiz-step a').evaluateAll((links) => links.map((link) => link.getAttribute('href'))),
    ['configure?lang=en-XA', './?lang=en-XA', 'install?lang=en-XA'],
  );
});

browserTest('Setup binds its effective locale and closed return surface to browser sign-in', async (t) => {
  async function submitted(path, locale) {
    const rig = await openRig(t, {
      initialJourney: journey('ha_credentials'),
      locale,
      route(url, request) {
        if (url.pathname === '/api/v1/ha/oauth/start' && request.method === 'POST') {
          return json({ authorization_url: 'about:blank' });
        }
      },
    }, path);
    await rig.page.getByRole('button', { name: 'Sign in from this browser' }).click();
    return eventually(
      () => Promise.resolve(rig.state.requests.find((entry) => entry.path === '/api/v1/ha/oauth/start')),
      Boolean,
    );
  }

  const chinese = new URLSearchParams((await submitted('/?lang=zh-CN', 'zh-Hans')).body);
  assert.equal(chinese.get('ui_locale'), 'zh-Hans');
  assert.equal(chinese.get('return_surface'), 'setup');
  assert.equal(chinese.get('preserve_explicit_english'), '0');

  const explicitEnglish = new URLSearchParams((await submitted('/?lang=en', 'en')).body);
  assert.equal(explicitEnglish.get('ui_locale'), 'en');
  assert.equal(explicitEnglish.get('return_surface'), 'setup');
  assert.equal(explicitEnglish.get('preserve_explicit_english'), '1');
});

browserTest('Setup distinguishes a saved apply-pending 202 from a physical approval challenge', async (t) => {
  const translations = {
    'setup.ha_url.title': 'HA 地址',
    'setup.sign_in.title': 'HA 登录',
    'setup.error.approval_required': '请在面板上批准，然后再次保存。',
  };
  const accepted = await openRig(t, {
    initialJourney: journey('ha_url'),
    translations,
    discovery: { ha_url: 'http://homeassistant.local:8123' },
    route(url, request, state) {
      if (url.pathname === '/api/v1/config' && request.method === 'POST') {
        state.current = journey('ha_credentials');
        return json({ ok: true, status: 'saved-apply-pending' }, 202);
      }
    },
  });
  await accepted.page.locator('#wiz-ha_url').fill('http://homeassistant.local:8123');
  await accepted.page.locator('button.wiz-primary').click();
  assert.equal(await eventually(
    () => accepted.page.locator('.card h2').textContent(),
    (value) => value === 'HA 登录',
  ), 'HA 登录');
  assert.equal(await accepted.page.locator('#wiz-err').count(), 1);
  assert.equal(await accepted.page.locator('#wiz-err').textContent(), '');

  const challenged = await openRig(t, {
    initialJourney: journey('ha_url'),
    translations,
    discovery: { ha_url: 'http://homeassistant.local:8123' },
    route(url, request) {
      if (url.pathname === '/api/v1/config' && request.method === 'POST') {
        return json({ ok: false, error: 'approval-required', message: 'SERVER ENGLISH MUST NOT LEAK' }, 202);
      }
    },
  });
  await challenged.page.locator('#wiz-ha_url').fill('http://homeassistant.local:8123');
  await challenged.page.locator('button.wiz-primary').click();
  assert.equal(await eventually(
    () => challenged.page.locator('#wiz-err').textContent(),
    (value) => value.length > 0,
  ), '请在面板上批准，然后再次保存。');
  assert.doesNotMatch(await challenged.page.locator('#wiz-step').textContent(), /SERVER ENGLISH MUST NOT LEAK/);
  assert.equal(await challenged.page.locator('.card h2').textContent(), 'HA 地址');

});

browserTest('Setup maps discovery and probe codes to translated messages without translating host or port evidence', async (t) => {
  const host = 'broker-<east>&west';
  const port = '28<883>&';
  const discoveredBroker = 'tcp://[fe80::1234]:1883/path?<>&';
  const translations = {
    'setup.mqtt.title': '连接 MQTT',
    'setup.discovery.found': '网络中找到：{value}',
    'setup.discovery.unavailable.no_responses_at_all': '未收到任何发现响应。',
    'setup.mqtt.error.nothing_listening': '{host}:{port} 没有服务，请检查。',
  };
  const rig = await openRig(t, {
    initialJourney: journey('mqtt_broker', {
      discovery: {
        outcome: 'unavailable', reason: 'no_responses_at_all',
        explanation: 'SERVER ENGLISH MUST NOT LEAK',
      },
    }),
    translations,
    discovery: {},
    route(url) {
      if (url.pathname === '/api/v1/config/probe-broker') {
        return json({ ok: false, error: 'nothing-listening', host, port });
      }
    },
  });
  assert.equal(await rig.page.locator('.card h2').textContent(), '连接 MQTT');
  assert.equal(await rig.page.locator('.setup.info').textContent(), '未收到任何发现响应。');
  assert.doesNotMatch(await rig.page.locator('#wiz-step').textContent(), /SERVER ENGLISH MUST NOT LEAK/);

  await rig.page.locator('#wiz-mqtt_broker').fill('tcp://broker.example:1883');
  await rig.page.locator('button.wiz-primary').click();
  assert.equal(await eventually(
    () => rig.page.locator('#wiz-err').textContent(),
    (value) => value.length > 0,
  ), `${host}:${port} 没有服务，请检查。`);
  assert.equal(await rig.page.locator('#wiz-step img').count(), 0);

  // The fixture's read-only discovery payload remains an opaque URL. A separate rig avoids conflating
  // that payload with the journey's finite discovery reason code.
  const found = await openRig(t, {
    initialJourney: journey('mqtt_broker'), translations,
    discovery: { mqtt_broker: discoveredBroker },
  });
  await found.page.getByText(`网络中找到：${discoveredBroker}`, { exact: true }).waitFor();
  assert.equal(await found.page.locator('#wiz-mqtt_broker').inputValue(), discoveredBroker);
});

browserTest('Setup reports WebView installation only when the stable response token says it started', async (t) => {
  const translations = {
    'setup.webview.title': '更新浏览器引擎',
    'setup.webview.action.update': '更新引擎',
    'setup.webview.state.installing_short': '正在安装…',
    'setup.webview.state.installing': '推荐的引擎正在面板上安装。',
  };
  const webviewJourney = journey('renderer', {
    statuses: { renderer: { status: 'blocked', detail: 'webview_too_old_fixable' } },
  });
  const busy = await openRig(t, {
    initialJourney: webviewJourney,
    translations,
    route(url, request) {
      if (url.pathname === '/api/v1/webview/heal' && request.method === 'POST') return json({ status: 'busy' });
    },
  });
  const busyButton = busy.page.locator('button.wiz-primary');
  await busyButton.click();
  assert.deepEqual(await eventually(
    async () => ({ disabled: await busyButton.isDisabled(), text: await busyButton.textContent() }),
    (value) => !value.disabled && value.text === '更新引擎',
  ), { disabled: false, text: '更新引擎' });
  assert.doesNotMatch(await busy.page.locator('#wiz-step').textContent(), /推荐的引擎正在面板上安装/);

  const started = await openRig(t, {
    initialJourney: webviewJourney,
    translations,
    route(url, request) {
      if (url.pathname === '/api/v1/webview/heal' && request.method === 'POST') return json({ status: 'started' });
    },
  });
  await started.page.locator('button.wiz-primary').click();
  assert.match(await eventually(
    () => started.page.locator('#wiz-step').textContent(),
    (value) => value.includes('推荐的引擎正在面板上安装。'),
  ), /推荐的引擎正在面板上安装。/);
  assert.equal(await started.page.locator('button.wiz-primary').textContent(), '正在安装…');
  assert.equal(await started.page.locator('button.wiz-primary').isDisabled(), true);
});
