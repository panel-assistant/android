import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
import { chromium } from 'playwright-core';
import { browserTest, chrome, startHarness } from './fixtures/browser-harness.mjs';
import { i18nBridge, i18nHelper, installHaNotice, installTransport } from './fixtures/i18n-bridge.mjs';

const asset = new URL('../../app/src/main/assets/buildwatch.js', import.meta.url);
const englishCatalogueAsset = new URL('../../app/src/main/assets/i18n/en.json', import.meta.url);
const englishCatalogue = JSON.parse(await readFile(englishCatalogueAsset, 'utf8')).strings;

function node(id = '') {
  let ownText = '';
  const children = [];
  const attributes = {};
  return {
    id,
    style: { display: 'none' },
    className: 'notice',
    setAttribute(name, value) { attributes[name] = String(value); },
    getAttribute(name) { return Object.prototype.hasOwnProperty.call(attributes, name) ? attributes[name] : null; },
    disabled: true,
    children,
    listeners: {},
    get firstChild() { return children[0] || null; },
    get textContent() { return ownText + children.map((child) => child.textContent).join(''); },
    set textContent(value) { ownText = String(value); children.splice(0); },
    appendChild(child) { children.push(child); return child; },
    removeChild(child) { children.splice(children.indexOf(child), 1); return child; },
    addEventListener(name, listener) { this.listeners[name] = listener; },
  };
}

// What a notice row says, read the way a person reads it: severity, pill word, message, action.
function notice(row) {
  const part = (cls) => row.children.find((child) => child.className === cls);
  return {
    severity: row.getAttribute('data-severity'),
    pill: part('notice-pill')?.textContent ?? null,
    text: part('notice-text')?.textContent ?? '',
    action: part('notice-action')?.textContent ?? null,
  };
}
const noticeText = (row) => notice(row).text;

function interpolate(text, values) {
  return text.replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, (placeholder, name) => (
    Object.prototype.hasOwnProperty.call(values || {}, name) ? String(values[name]) : placeholder
  ));
}

function english(key, values) {
  assert.ok(englishCatalogue[key], `English catalogue must contain ${key}`);
  return interpolate(englishCatalogue[key].text, values);
}

async function loadBuildwatch({ translations = {}, locale = 'en', helper = true, dirty = false, pathname = '/' } = {}) {
  const source = await readFile(asset, 'utf8');
  const ids = Object.fromEntries(['verbar', 'halifebar', 'halifecell', 'hanetbar', 'hanetcell'].map((id) => [id, node(id)]));
  const bodyAttributes = { 'data-build': 'build-a' };
  const calls = [];
  const ticks = [];
  const health = { line: '' };
  const location = { origin: 'http://panel.test', pathname, reloads: 0, reload() { this.reloads += 1; } };
  const document = {
    baseURI: 'http://panel.test/',
    documentElement: { lang: locale },
    body: { getAttribute: (name) => bodyAttributes[name] || '' },
    getElementById: (id) => ids[id] || null,
    querySelector: () => (dirty ? node('focused') : null),
    createTextNode(text) { const textNode = node(); textNode.textContent = text; return textNode; },
    createElement(tag) {
      const element = node(tag);
      element.tagName = tag.toUpperCase();
      return element;
    },
  };
  const window = { document };
  window.HaI18n = i18nHelper({ locale, strings: helper ? translations : {} }, calls);
  const context = {
    document,
    window,
    location,
    fetch: async () => ({ status: 200, headers: { get: () => 'text/plain', forEach() {} }, text: async () => health.line }),
    setTimeout(callback) { ticks.push(callback); return ticks.length; },
    clearTimeout() {},
    Intl,
    URL,
  };
  vm.createContext(context);
  installHaNotice(context);
  installTransport(context);
  vm.runInContext(source, context, { filename: 'buildwatch.js' });
  const settle = () => new Promise((done) => setImmediate(done));

  // The transport polls the health family at its floor; each poll runs the timer it scheduled last.
  async function poll(line) {
    health.line = line;
    ticks.at(-1)();
    await settle();
  }
  await settle();
  calls.splice(0);
  return { calls, ids, location, poll };
}

test('lifecycle copy uses the closed key set, keeps glyphs decorative, and clears unknown states', async () => {
  const translations = {
    'shell.runtime.ha_lifecycle.offline': '家庭助理已离线。',
    'shell.runtime.ha_lifecycle.starting': '家庭助理正在启动。',
    'shell.runtime.ha_lifecycle.back_online': '家庭助理已恢复在线。',
    'shell.runtime.ha_lifecycle.shutting_down': '家庭助理正在关闭。',
    'dashboard.runtime.ha_connection_lost': '连接已断开',
    'dashboard.runtime.ha_events_refused': '正在监视；事件被拒绝',
    'dashboard.runtime.ha_watching': '正在监视',
  };
  const rig = await loadBuildwatch({ translations, locale: 'zh-Hans' });

  const cases = [
    ['ha=shutting_down', 'shell.runtime.ha_lifecycle.shutting_down', '家庭助理正在关闭。', 'working', 'Stopping'],
    ['ha=starting', 'shell.runtime.ha_lifecycle.starting', '家庭助理正在启动。', 'working', 'Starting'],
    ['ha=back_online', 'shell.runtime.ha_lifecycle.back_online', '家庭助理已恢复在线。', 'resolved', 'Resolved'],
  ];
  for (const [tokens, key, banner, severity, pill] of cases) {
    rig.calls.splice(0);
    await rig.poll(tokens);
    assert.equal(noticeText(rig.ids.halifebar), banner);
    assert.equal(notice(rig.ids.halifebar).severity, severity);
    assert.equal(notice(rig.ids.halifebar).pill, pill, 'the pill names the lifecycle step');
    assert.equal(rig.ids.halifecell.textContent, banner, 'CJK row copy must not be stripped');
    assert.ok(rig.calls.some((call) => call.key === key));
  }

  await rig.poll('ha=connection_lost');
  assert.equal(rig.ids.halifecell.textContent, '连接已断开');
  await rig.poll('ha=normal ha_refused=1');
  assert.equal(rig.ids.halifecell.textContent, '正在监视；事件被拒绝');
  await rig.poll('ha=normal');
  assert.equal(rig.ids.halifecell.textContent, '正在监视');
  await rig.poll('ha=future_state');
  assert.equal(rig.ids.halifebar.style.display, 'none');
  assert.equal(rig.ids.halifecell.textContent, '');
  for (const inheritedName of ['constructor', '__proto__']) {
    await rig.poll('ha=starting');
    assert.equal(rig.ids.halifebar.style.display, '');
    assert.notEqual(rig.ids.halifecell.textContent, '');
    await rig.poll(`ha=${inheritedName}`);
    assert.equal(rig.ids.halifebar.style.display, 'none');
    assert.equal(rig.ids.halifecell.textContent, '');
  }
});

test('network row selects every severity and responsiveness matrix key', async () => {
  const rig = await loadBuildwatch();
  const stateStems = { healthy: 'healthy', warning: 'losing_probes', severe: 'failing' };
  const responseSuffixes = { healthy: '', warning: '_slow', severe: '_very_slow' };

  for (const [state, stem] of Object.entries(stateStems)) {
    for (const [response, suffix] of Object.entries(responseSuffixes)) {
      rig.calls.splice(0);
      await rig.poll(`ha_net=${state} ha_resp=${response} ha_net_p95=25 ha_net_n=30 ha_net_miss=0`);
      const expected = state === 'healthy' && !suffix
        ? 'dashboard.runtime.ha_network_healthy'
        : `dashboard.runtime.ha_network_${stem}${suffix}`;
      assert.equal(rig.ids.hanetcell.textContent, english(expected, {
        evidence: english('shell.runtime.ha_network_evidence_p95_no_misses', { p95Ms: '25' }),
      }), `${state} × ${response} must render its translated observation`);
    }
  }

  await rig.poll('ha_net=future_path ha_resp=healthy ha_net_p95=1 ha_net_n=1 ha_net_miss=0');
  assert.equal(rig.ids.hanetcell.textContent, '');
  await rig.poll('ha_net=healthy ha_net_p95=1 ha_net_n=1 ha_net_miss=0');
  assert.equal(rig.ids.hanetcell.textContent, '');
  await rig.poll('ha_net=healthy ha_resp=future_speed ha_net_p95=1 ha_net_n=1 ha_net_miss=0');
  assert.equal(rig.ids.hanetcell.textContent, '');

  rig.calls.splice(0);
  await rig.poll('ha_net=settling');
  assert.ok(rig.calls.some((call) => call.key === 'dashboard.runtime.ha_network_settling'));
  assert.equal(rig.ids.hanetcell.textContent, 'settling after startup; no verdict yet');
  assert.equal(rig.ids.hanetbar.style.display, 'none');

  await rig.poll('ha_net=warning ha_resp=healthy ha_net_p95=25 ha_net_n=30 ha_net_miss=0');
  assert.deepEqual([notice(rig.ids.hanetbar).severity, notice(rig.ids.hanetbar).pill], ['warning', 'Warning']);
  assert.notEqual(noticeText(rig.ids.hanetbar), '');
  for (const inheritedName of ['constructor', '__proto__']) {
    await rig.poll('ha_net=warning ha_resp=healthy ha_net_p95=25 ha_net_n=30 ha_net_miss=0');
    assert.equal(rig.ids.hanetbar.style.display, '');
    assert.notEqual(rig.ids.hanetcell.textContent, '');
    await rig.poll(`ha_net=${inheritedName} ha_resp=healthy ha_net_p95=1 ha_net_n=1 ha_net_miss=0`);
    assert.equal(rig.ids.hanetbar.style.display, 'none', `${inheritedName} must retract a stale warning`);
    assert.equal(rig.ids.hanetcell.textContent, '', `${inheritedName} must clear a stale diagnostics row`);
  }
  await rig.poll('ha_net=severe ha_resp=healthy ha_net_p95=25 ha_net_n=30 ha_net_miss=1');
  assert.deepEqual([notice(rig.ids.hanetbar).severity, notice(rig.ids.hanetbar).pill], ['action_needed', 'Action needed']);
  await rig.poll('ha_net=healthy ha_resp=healthy ha_net_p95=25 ha_net_n=30 ha_net_miss=0');
  assert.equal(rig.ids.hanetbar.style.display, 'none', 'healthy observation must retract an earlier warning');
});

test('network evidence uses shell projection keys and locale-formatted bounded numbers', async () => {
  const rig = await loadBuildwatch({ locale: 'de-DE' });
  const cases = [
    ['ha_net=healthy ha_resp=healthy ha_net_p95=4200 ha_net_n=30 ha_net_miss=0', 'shell.runtime.ha_network_evidence_p95_no_misses', { p95Ms: '4.200' }],
    ['ha_net=healthy ha_resp=healthy ha_net_p95=4200 ha_net_n=2000 ha_net_miss=1234', 'shell.runtime.ha_network_evidence_p95_missed', { p95Ms: '4.200', missCount: '1.234', probeCount: '2.000' }],
    ['ha_net=healthy ha_resp=healthy ha_net_p95=-1 ha_net_n=30 ha_net_miss=0', 'shell.runtime.ha_network_evidence_no_reply_no_misses'],
    ['ha_net=healthy ha_resp=healthy ha_net_p95=-1 ha_net_n=30 ha_net_miss=3', 'shell.runtime.ha_network_evidence_no_reply_missed', { missCount: '3', probeCount: '30' }],
    ['ha_net=healthy ha_resp=healthy ha_net_p95=-1 ha_net_n=0 ha_net_miss=0 ha_net_age=-1', 'shell.runtime.ha_network_evidence_no_probes'],
    ['ha_net=healthy ha_resp=healthy ha_net_p95=-1 ha_net_n=0 ha_net_miss=0 ha_net_age=45000', 'shell.runtime.ha_network_evidence_no_answer', { lastReplyAge: '45 s' }],
  ];
  for (const [tokens, expected, values] of cases) {
    rig.calls.splice(0);
    await rig.poll(tokens);
    assert.equal(rig.ids.hanetcell.textContent, english('dashboard.runtime.ha_network_healthy', {
      evidence: english(expected, values),
    }), `must render ${expected}`);
  }
  assert.ok(rig.calls.some((call) => call.key === 'shell.runtime.duration_seconds'));

  rig.calls.splice(0);
  await rig.poll('ha_net=healthy ha_resp=healthy ha_net_p95=-1 ha_net_n=0 ha_net_miss=0 ha_net_age=420000');
  assert.ok(rig.calls.some((call) => call.key === 'shell.runtime.duration_minutes'));

  await rig.poll('ha_net=severe ha_resp=healthy ha_net_p95=4200 ha_net_n=2000 ha_net_miss=1234');
  assert.match(noticeText(rig.ids.hanetbar), /4\.200/);
  assert.match(noticeText(rig.ids.hanetbar), /1\.234/);
  assert.match(noticeText(rig.ids.hanetbar), /2\.000/);
  assert.ok(rig.calls.some((call) => call.key === 'shell.runtime.ha_network.banner_severe'));
  await rig.poll('ha_net=warning ha_resp=healthy ha_net_p95=25 ha_net_n=30 ha_net_miss=0');
  assert.ok(rig.calls.some((call) => call.key === 'shell.runtime.ha_network.banner_warning'));
});

test('layer-3 latency uses the closed localized matrix without borrowing WebSocket evidence', async () => {
  const translations = {
    'shell.runtime.ha_network.banner_latency_warning': '路径较慢；请检查网络。',
    'shell.runtime.ha_network.banner_latency_severe': '路径非常慢；请检查网络。',
    'dashboard.runtime.ha_network_latency_warning': '较慢',
    'dashboard.runtime.ha_network_latency_warning_response_slow': '较慢；Home Assistant 响应较慢',
    'dashboard.runtime.ha_network_latency_warning_response_very_slow': '较慢；Home Assistant 响应非常慢',
    'dashboard.runtime.ha_network_latency_severe': '非常慢',
    'dashboard.runtime.ha_network_latency_severe_response_slow': '非常慢；Home Assistant 响应较慢',
    'dashboard.runtime.ha_network_latency_severe_response_very_slow': '非常慢；Home Assistant 响应非常慢',
  };
  const rig = await loadBuildwatch({ translations, locale: 'zh-Hans' });
  const expected = {
    warning: {
      healthy: ['shell.runtime.ha_network.banner_latency_warning', 'dashboard.runtime.ha_network_latency_warning', '较慢'],
      warning: ['shell.runtime.ha_network.banner_latency_warning', 'dashboard.runtime.ha_network_latency_warning_response_slow', '较慢；Home Assistant 响应较慢'],
      severe: ['shell.runtime.ha_network.banner_latency_warning', 'dashboard.runtime.ha_network_latency_warning_response_very_slow', '较慢；Home Assistant 响应非常慢'],
    },
    severe: {
      healthy: ['shell.runtime.ha_network.banner_latency_severe', 'dashboard.runtime.ha_network_latency_severe', '非常慢'],
      warning: ['shell.runtime.ha_network.banner_latency_severe', 'dashboard.runtime.ha_network_latency_severe_response_slow', '非常慢；Home Assistant 响应较慢'],
      severe: ['shell.runtime.ha_network.banner_latency_severe', 'dashboard.runtime.ha_network_latency_severe_response_very_slow', '非常慢；Home Assistant 响应非常慢'],
    },
  };
  for (const state of ['warning', 'severe']) {
    for (const response of ['healthy', 'warning', 'severe']) {
      rig.calls.splice(0);
      await rig.poll(`ha_net=${state} ha_net_cause=latency ha_resp=${response} ha_net_p95=9876 ha_net_n=4321 ha_net_miss=1234 ha_net_age=7654321`);
      const [bannerKey, rowKey, row] = expected[state][response];
      assert.equal(noticeText(rig.ids.hanetbar), `${translations[bannerKey]}`);
      assert.equal(rig.ids.hanetcell.textContent, row);
      assert.ok(rig.calls.some((call) => call.key === bannerKey));
      assert.ok(rig.calls.some((call) => call.key === rowKey));
      assert.doesNotMatch(noticeText(rig.ids.hanetbar) + rig.ids.hanetcell.textContent, /9876|9,876|4321|4,321|1234|1,234|7654321|7,654,321|p95|miss/i);
    }
  }

  for (const cause of ['future_value', 'constructor', '__proto__']) {
    await rig.poll(`ha_net=warning ha_net_cause=${cause} ha_resp=healthy ha_net_p95=1 ha_net_n=1 ha_net_miss=1`);
    assert.equal(rig.ids.hanetbar.style.display, 'none');
    assert.equal(rig.ids.hanetcell.textContent, '');
  }
});

test('echo evidence uses localized source-specific numbers and preserves translated verdicts', async () => {
  const translations = {
    'shell.runtime.ha_network_echo_evidence': '回声 {p95Ms} 毫秒；丢失 {missCount}/{probeCount}；{burstCount} 轮',
    'shell.runtime.ha_network_echo_no_reply': '回声无回复；丢失 {missCount}/{probeCount}；{burstCount} 轮',
    'shell.runtime.ha_network_with_evidence': '{verdict} — {evidence}',
    'shell.runtime.ha_network.banner_latency_severe': '路径非常慢',
    'dashboard.runtime.ha_network_latency_severe': '非常慢',
  };
  const rig = await loadBuildwatch({ translations, locale: 'de' });
  const socket = ' ha_resp=healthy ha_net_p95=9 ha_net_n=30 ha_net_miss=0';
  await rig.poll('ha_net=severe ha_net_cause=latency ha_net_source=echo' + socket
    + ' ha_net_echo_n=2000 ha_net_echo_miss=0 ha_net_echo_p95=4200 ha_net_echo_bursts=6');
  assert.equal(noticeText(rig.ids.hanetbar), '路径非常慢 — 回声 4.200 毫秒；丢失 0/2.000；6 轮');
  assert.equal(rig.ids.hanetcell.textContent, '非常慢 — 回声 4.200 毫秒；丢失 0/2.000；6 轮');
  await rig.poll('ha_net=severe ha_net_cause=loss ha_net_source=echo' + socket
    + ' ha_net_echo_n=5 ha_net_echo_miss=5 ha_net_echo_p95=-1 ha_net_echo_bursts=1');
  assert.match(noticeText(rig.ids.hanetbar), /回声无回复；丢失 5\/5；1 轮/);
  assert.match(rig.ids.hanetcell.textContent, /回声无回复；丢失 5\/5；1 轮/);
  assert.doesNotMatch(rig.ids.hanetcell.textContent, /p95 9|last 5 min/);
});

browserTest('echo loss and latency display their own evidence in the real page', async t => {
  let line = '';
  const harness = await startHarness(path => path === '/health'
    ? { headers: { 'content-type': 'text/plain' }, body: line } : undefined,
  () => `<!doctype html><html><head>${i18nBridge()}</head><body><div id="hanetbar"></div><div id="hanetcell"></div><script src="/assets/buildwatch.js"></script></body></html>`);
  let browser;
  t.after(async () => { await browser?.close(); harness.server.closeAllConnections?.(); await new Promise(done => harness.server.close(done)); });
  browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  const socket = ' ha_resp=healthy ha_net_p95=9 ha_net_n=30 ha_net_miss=0';
  const cases = [
    ['ha_net=severe ha_net_cause=loss ha_net_source=echo' + socket
      + ' ha_net_echo_n=5 ha_net_echo_miss=2 ha_net_echo_p95=12 ha_net_echo_bursts=1',
    'failing; p95 12 ms, 2 of 5 network checks missed across 1 bursts', true],
    ['ha_net=severe ha_net_cause=latency ha_net_source=echo' + socket
      + ' ha_net_echo_n=5 ha_net_echo_miss=0 ha_net_echo_p95=500 ha_net_echo_bursts=1',
    'very slow; p95 500 ms, 0 of 5 network checks missed across 1 bursts', true],
    ['ha_net=healthy ha_net_source=echo' + socket
      + ' ha_net_echo_n=5 ha_net_echo_miss=0 ha_net_echo_p95=12 ha_net_echo_bursts=1',
    'healthy; p95 12 ms, 0 of 5 network checks missed across 1 bursts', false],
    ['ha_net=severe ha_net_cause=loss ha_net_source=future' + socket, '', false],
    ['ha_net=severe ha_net_cause=loss ha_net_source=echo' + socket, '', false],
    ['', '', false],
  ];
  for (const [health, expected, visible] of cases) {
    line = health;
    const response = page.waitForResponse(reply => new URL(reply.url()).pathname === '/health');
    await page.goto(harness.url);
    await (await response).finished();
    await page.evaluate(() => new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done))));
    await page.waitForFunction(value => document.getElementById('hanetcell').textContent === value, expected);
    assert.equal(await page.locator('#hanetcell').textContent(), expected);
    assert.equal(await page.locator('#hanetbar').isVisible(), visible);
    if (visible) {
      const banner = await page.locator('#hanetbar .notice-text').textContent();
      assert.match(banner, /network checks missed across 1 bursts/);
      assert.doesNotMatch(banner, /p95 9 ms|no misses|last 5 min/);
    }
  }
});

test('the version banner creates a safe localized reload link and preserves English when untranslated', async () => {
  const translations = {
    'shell.new_version.installed': '已安装新版本',
    'shell.action.reload': '重新加载',
  };
  const version = await loadBuildwatch({ translations, locale: 'zh-Hans', dirty: true });
  await version.poll('build=build-b cfg=cfg-a');
  assert.deepEqual(notice(version.ids.verbar), { severity: 'update', pill: 'Update', text: '已安装新版本', action: '重新加载' });
  const reload = version.ids.verbar.children.at(-1).children[0];
  assert.equal(reload.tagName, 'A');
  assert.equal(reload.href, '#');
  reload.listeners.click({ preventDefault() {} });
  assert.equal(version.location.reloads, 1);
  await version.poll('build=build-b cfg=cfg-a');
  assert.deepEqual(notice(version.ids.verbar), { severity: 'update', pill: 'Update', text: '已安装新版本', action: '重新加载' });

  // Configure follows setting changes through its own live updates: a new settings fingerprint on the
  // health line neither reloads the page nor raises a banner, whether or not a field is being edited.
  for (const dirty of [false, true]) {
    const settings = await loadBuildwatch({ translations, locale: 'zh-Hans', dirty, pathname: '/configure' });
    await settings.poll('build=build-a cfg=cfg-b');
    await settings.poll('build=build-a cfg=cfg-c');
    assert.equal(settings.location.reloads, 0, `a settings change does not reload Configure (editing: ${dirty})`);
    assert.equal(noticeText(settings.ids.verbar), '', `a settings change raises no banner (editing: ${dirty})`);
  }

  const english = await loadBuildwatch({ helper: false, dirty: true });
  await english.poll('build=build-b cfg=cfg-a');
  assert.deepEqual(notice(english.ids.verbar), { severity: 'update', pill: 'Update', text: 'A newer Panel Assistant is installed', action: 'reload' });
});

browserTest('untrusted catalogue strings stay inert text in lifecycle, network, and reload banners', async t => {
  const payload = '<img src=x onerror="globalThis.translationExecuted=true">';
  const translations = {
    'shell.runtime.ha_lifecycle.starting': payload,
    'shell.runtime.ha_network.banner_warning': payload + ' {evidence}',
    'shell.runtime.ha_network.banner_latency_warning': payload,
    'dashboard.runtime.ha_network_latency_warning': payload,
    'shell.runtime.ha_network_evidence_p95_no_misses': payload,
    'shell.new_version.installed': payload,
    'shell.action.reload': payload,
  };
  let line = '';
  const harness = await startHarness(path => path === '/health' ? { headers: { 'content-type': 'text/plain' }, body: line } : undefined,
    () => `<!doctype html><html><head>${i18nBridge({ strings: translations })}</head><body data-build="build-a"><button id="savebtn">Save</button><div id="verbar"></div><div id="halifebar"></div><div id="halifecell"></div><div id="hanetbar"></div><div id="hanetcell"></div><script src="/assets/buildwatch.js"></script></body></html>`);
  let browser;
  t.after(async () => { await browser?.close(); harness.server.closeAllConnections?.(); await new Promise(done => harness.server.close(done)); });
  browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  for (const latency of [false, true]) {
    line = `ha=starting ha_net=warning ${latency ? 'ha_net_cause=latency ' : ''}ha_resp=healthy ha_net_p95=25 ha_net_n=30 ha_net_miss=0 build=build-b cfg=cfg-a`;
    await page.goto(harness.url);
    await page.waitForFunction(() => document.querySelector('#verbar .notice-text'));
    assert.equal(await page.locator('#halifebar .notice-text').textContent(), payload);
    assert.equal(await page.locator('#halifecell').textContent(), payload);
    assert.equal(await page.locator('#hanetbar .notice-text').textContent(), latency ? payload : `${payload} ${payload}`);
    assert.equal(await page.locator('#hanetcell').textContent(), latency ? payload : `losing probes; ${payload}`);
    assert.equal(await page.locator('#verbar .notice-text').textContent(), payload);
    assert.equal(await page.locator('#verbar .notice-action').textContent(), payload);
    assert.equal(await page.locator('img').count(), 0);
    assert.equal(await page.evaluate(() => globalThis.translationExecuted), undefined);
  }
});

test('untranslated keys preserve exact English runtime copy', async () => {
  for (const options of [{ helper: false }]) {
    const rig = await loadBuildwatch({ ...options, dirty: true });
    await rig.poll('ha=starting ha_net=warning ha_resp=healthy ha_net_p95=25 ha_net_n=30 ha_net_miss=0 build=build-b cfg=cfg-a');

    assert.equal(noticeText(rig.ids.halifebar), 'Home Assistant is starting.');
    assert.equal(rig.ids.halifecell.textContent, 'Home Assistant is starting.');
    assert.equal(
      noticeText(rig.ids.hanetbar),
      'Probes to Home Assistant are going missing: p95 25 ms, no misses in the last 5 min. Packets are not getting through. Check the Wi-Fi path between this panel and Home Assistant before blaming the panel.'
    );
    assert.equal(rig.ids.hanetcell.textContent, 'losing probes; p95 25 ms, no misses in the last 5 min');
    assert.equal(noticeText(rig.ids.verbar), 'A newer Panel Assistant is installed');
  }
});

test('untranslated keys reproduce every English network row and banner composition', async () => {
  const helperCases = [
    ['untranslated', { helper: false }],
  ];
  const rows = [
    ['healthy', 'healthy', 'dashboard.runtime.ha_network_healthy'],
    ['healthy', 'warning', 'dashboard.runtime.ha_network_healthy_slow'],
    ['healthy', 'severe', 'dashboard.runtime.ha_network_healthy_very_slow'],
    ['warning', 'healthy', 'dashboard.runtime.ha_network_losing_probes'],
    ['warning', 'warning', 'dashboard.runtime.ha_network_losing_probes_slow'],
    ['warning', 'severe', 'dashboard.runtime.ha_network_losing_probes_very_slow'],
    ['severe', 'healthy', 'dashboard.runtime.ha_network_failing'],
    ['severe', 'warning', 'dashboard.runtime.ha_network_failing_slow'],
    ['severe', 'severe', 'dashboard.runtime.ha_network_failing_very_slow'],
  ];
  const evidenceCases = [
    {
      tokens: 'ha_net_p95=25 ha_net_n=30 ha_net_miss=0',
      key: 'shell.runtime.ha_network_evidence_p95_no_misses',
      values: { p95Ms: '25', missCount: '0', probeCount: '30' },
    },
    {
      tokens: 'ha_net_p95=25 ha_net_n=30 ha_net_miss=3',
      key: 'shell.runtime.ha_network_evidence_p95_missed',
      values: { p95Ms: '25', missCount: '3', probeCount: '30' },
    },
    {
      tokens: 'ha_net_p95=-1 ha_net_n=30 ha_net_miss=0',
      key: 'shell.runtime.ha_network_evidence_no_reply_no_misses',
      values: { p95Ms: '-1', missCount: '0', probeCount: '30' },
    },
    {
      tokens: 'ha_net_p95=-1 ha_net_n=30 ha_net_miss=3',
      key: 'shell.runtime.ha_network_evidence_no_reply_missed',
      values: { p95Ms: '-1', missCount: '3', probeCount: '30' },
    },
    {
      tokens: 'ha_net_p95=-1 ha_net_n=0 ha_net_miss=0 ha_net_age=-1',
      key: 'shell.runtime.ha_network_evidence_no_probes',
      values: {},
    },
    {
      tokens: 'ha_net_p95=-1 ha_net_n=0 ha_net_miss=0 ha_net_age=45000',
      key: 'shell.runtime.ha_network_evidence_no_answer',
      values: { lastReplyAge: english('shell.runtime.duration_seconds', { count: '45' }) },
    },
  ];

  for (const [helperLabel, options] of helperCases) {
    const rig = await loadBuildwatch(options);
    const representativeEvidence = english('shell.runtime.ha_network_evidence_p95_no_misses', {
      p95Ms: '25', missCount: '0', probeCount: '30',
    });
    for (const [state, response, key] of rows) {
      await rig.poll(`ha_net=${state} ha_resp=${response} ha_net_p95=25 ha_net_n=30 ha_net_miss=0`);
      assert.equal(
        rig.ids.hanetcell.textContent,
        english(key, { evidence: representativeEvidence }),
        `${helperLabel}: ${state} × ${response} row must match its English catalogue record`
      );
    }

    const latencyRows = [
      ['warning', 'healthy', 'dashboard.runtime.ha_network_latency_warning'],
      ['warning', 'warning', 'dashboard.runtime.ha_network_latency_warning_response_slow'],
      ['warning', 'severe', 'dashboard.runtime.ha_network_latency_warning_response_very_slow'],
      ['severe', 'healthy', 'dashboard.runtime.ha_network_latency_severe'],
      ['severe', 'warning', 'dashboard.runtime.ha_network_latency_severe_response_slow'],
      ['severe', 'severe', 'dashboard.runtime.ha_network_latency_severe_response_very_slow'],
    ];
    for (const [state, response, key] of latencyRows) {
      await rig.poll(`ha_net=${state} ha_net_cause=latency ha_resp=${response} ha_net_p95=9876 ha_net_n=4321 ha_net_miss=1234`);
      assert.equal(rig.ids.hanetcell.textContent, english(key), `${helperLabel}: latency ${state} × ${response}`);
      assert.equal(
        noticeText(rig.ids.hanetbar),
        `${english(`shell.runtime.ha_network.banner_latency_${state}`)}`,
        `${helperLabel}: latency ${state} banner`,
      );
      assert.doesNotMatch(noticeText(rig.ids.hanetbar) + rig.ids.hanetcell.textContent, /9876|4321|1234|p95|miss/i);
    }

    for (const evidenceCase of evidenceCases) {
      const evidence = english(evidenceCase.key, evidenceCase.values);
      for (const state of ['warning', 'severe']) {
        await rig.poll(`ha_net=${state} ha_resp=healthy ${evidenceCase.tokens}`);
        const bannerKey = `shell.runtime.ha_network.banner_${state}`;
        assert.equal(
          noticeText(rig.ids.hanetbar),
          `${english(bannerKey, { evidence })}`,
          `${helperLabel}: ${state} banner must compose ${evidenceCase.key} exactly`
        );
        const rowKey = state === 'warning'
          ? 'dashboard.runtime.ha_network_losing_probes'
          : 'dashboard.runtime.ha_network_failing';
        assert.equal(
          rig.ids.hanetcell.textContent,
          english(rowKey, { evidence }),
          `${helperLabel}: ${state} row must compose ${evidenceCase.key} exactly`
        );
      }
    }
  }
});

test('offline story waits for grace and shows measured countdown with subdued quantified overrun', async () => {
  const rig = await loadBuildwatch();
  await rig.poll('ha=connection_lost ha_reason=unknown ha_grace_ms=10000');
  assert.equal(rig.ids.halifebar.style.display, 'none');
  await rig.poll('ha=connection_lost ha_reason=unknown ha_grace_ms=0');
  assert.match(rig.ids.halifecell.textContent, /Reason unknown/);
  assert.match(rig.ids.halifecell.textContent, /Time back has not been measured yet/);
  await rig.poll('ha=shutting_down ha_reason=core_update ha_elapsed_ms=1000 ha_expected_ms=121000');
  assert.match(rig.ids.halifecell.textContent, /Home Assistant Core update/);
  assert.match(rig.ids.halifecell.textContent, /Expected back in 2:00/);
  await rig.poll('ha=starting ha_reason=restart ha_elapsed_ms=181000 ha_expected_ms=121000');
  assert.match(noticeText(rig.ids.halifebar), /Taking longer than usual/);
  assert.match(noticeText(rig.ids.halifebar), /1:00 past the estimate/);
  assert.equal(notice(rig.ids.halifebar).severity, 'working');
  await rig.poll('ha=starting ha_reason=restart ha_elapsed_ms=3721000 ha_expected_ms=121000');
  assert.match(noticeText(rig.ids.halifebar), /60:00 past the estimate/);
  await rig.poll('ha=back_online ha_reason=restart ha_elapsed_ms=3721000 ha_expected_ms=121000');
  assert.doesNotMatch(noticeText(rig.ids.halifebar), /Taking longer|past the estimate/);
  await rig.poll('ha=normal ha_reason=unknown');
  assert.equal(rig.ids.halifebar.style.display, 'none');
});
