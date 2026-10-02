import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const asset = new URL('../../app/src/main/assets/buildwatch.js', import.meta.url);
const englishCatalogueAsset = new URL('../../app/src/main/assets/i18n/en.json', import.meta.url);
const englishCatalogue = JSON.parse(await readFile(englishCatalogueAsset, 'utf8')).strings;

function node(id = '') {
  let ownText = '';
  const children = [];
  return {
    id,
    style: { display: 'none' },
    className: 'setup',
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

function interpolate(text, values) {
  return text.replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, (placeholder, name) => (
    Object.prototype.hasOwnProperty.call(values || {}, name) ? String(values[name]) : placeholder
  ));
}

function english(key, values) {
  assert.ok(englishCatalogue[key], `English catalogue must contain ${key}`);
  return interpolate(englishCatalogue[key].text, values);
}

async function loadBuildwatch({ translations = {}, locale = 'en', helper = true, helperValue, dirty = false, pathname = '/' } = {}) {
  const source = await readFile(asset, 'utf8');
  const ids = Object.fromEntries(['verbar', 'halifebar', 'halifecell', 'hanetbar', 'hanetcell'].map((id) => [id, node(id)]));
  const bodyAttributes = { 'data-build': 'build-a', 'data-cfg': 'cfg-a' };
  const calls = [];
  const created = [];
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
      created.push(element);
      return element;
    },
  };
  const window = { document };
  if (helperValue !== undefined) {
    window.HaI18n = helperValue;
  } else if (helper) {
    window.HaI18n = {
      locale,
      t(key, fallback, values) {
        calls.push({ key, fallback, values });
        const selected = Object.prototype.hasOwnProperty.call(translations, key) ? translations[key] : fallback;
        return interpolate(selected, values);
      },
    };
  }
  const context = {
    document,
    window,
    location,
    fetch: async () => ({ text: async () => health.line }),
    setInterval(callback) { ticks.push(callback); return 1; },
    Intl,
    URL,
  };
  vm.runInNewContext(source, context, { filename: 'buildwatch.js' });

  async function poll(line) {
    health.line = line;
    ticks[0]();
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  }
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
  calls.splice(0);
  return { calls, created, ids, location, poll, source };
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
    ['ha=shutting_down ha_src=mqtt', 'shell.runtime.ha_lifecycle.offline', '⚠ 家庭助理已离线。'],
    ['ha=starting', 'shell.runtime.ha_lifecycle.starting', '⟳ 家庭助理正在启动。'],
    ['ha=back_online', 'shell.runtime.ha_lifecycle.back_online', '✓ 家庭助理已恢复在线。'],
    ['ha=shutting_down ha_src=socket', 'shell.runtime.ha_lifecycle.shutting_down', '⚠ 家庭助理正在关闭。'],
  ];
  for (const [tokens, key, banner] of cases) {
    rig.calls.splice(0);
    await rig.poll(tokens);
    assert.equal(rig.ids.halifebar.textContent, banner);
    assert.equal(rig.ids.halifecell.textContent, banner.substring(2), 'CJK row copy must not be stripped');
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
      assert.ok(rig.calls.some((call) => call.key === expected), `${state} × ${response} must use ${expected}`);
      assert.notEqual(rig.ids.hanetcell.textContent, '');
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
  assert.equal(rig.ids.hanetbar.className, 'setup');
  assert.notEqual(rig.ids.hanetbar.textContent, '');
  for (const inheritedName of ['constructor', '__proto__']) {
    await rig.poll('ha_net=warning ha_resp=healthy ha_net_p95=25 ha_net_n=30 ha_net_miss=0');
    assert.equal(rig.ids.hanetbar.style.display, '');
    assert.notEqual(rig.ids.hanetcell.textContent, '');
    await rig.poll(`ha_net=${inheritedName} ha_resp=healthy ha_net_p95=1 ha_net_n=1 ha_net_miss=0`);
    assert.equal(rig.ids.hanetbar.style.display, 'none', `${inheritedName} must retract a stale warning`);
    assert.equal(rig.ids.hanetcell.textContent, '', `${inheritedName} must clear a stale diagnostics row`);
  }
  await rig.poll('ha_net=severe ha_resp=healthy ha_net_p95=25 ha_net_n=30 ha_net_miss=1');
  assert.equal(rig.ids.hanetbar.className, 'setup crit');
  await rig.poll('ha_net=healthy ha_resp=healthy ha_net_p95=25 ha_net_n=30 ha_net_miss=0');
  assert.equal(rig.ids.hanetbar.style.display, 'none', 'healthy observation must retract an earlier warning');
});

test('network evidence uses shell projection keys and locale-formatted bounded numbers', async () => {
  const rig = await loadBuildwatch({ locale: 'de-DE' });
  const cases = [
    ['ha_net=healthy ha_resp=healthy ha_net_p95=4200 ha_net_n=30 ha_net_miss=0', 'shell.runtime.ha_network_evidence_p95_no_misses'],
    ['ha_net=healthy ha_resp=healthy ha_net_p95=4200 ha_net_n=2000 ha_net_miss=1234', 'shell.runtime.ha_network_evidence_p95_missed'],
    ['ha_net=healthy ha_resp=healthy ha_net_p95=-1 ha_net_n=30 ha_net_miss=0', 'shell.runtime.ha_network_evidence_no_reply_no_misses'],
    ['ha_net=healthy ha_resp=healthy ha_net_p95=-1 ha_net_n=30 ha_net_miss=3', 'shell.runtime.ha_network_evidence_no_reply_missed'],
    ['ha_net=healthy ha_resp=healthy ha_net_p95=-1 ha_net_n=0 ha_net_miss=0 ha_net_age=-1', 'shell.runtime.ha_network_evidence_no_probes'],
    ['ha_net=healthy ha_resp=healthy ha_net_p95=-1 ha_net_n=0 ha_net_miss=0 ha_net_age=45000', 'shell.runtime.ha_network_evidence_no_answer'],
  ];
  for (const [tokens, expected] of cases) {
    rig.calls.splice(0);
    await rig.poll(tokens);
    assert.ok(rig.calls.some((call) => call.key === expected), `must use ${expected}`);
  }
  assert.ok(rig.calls.some((call) => call.key === 'shell.runtime.duration_seconds'));

  rig.calls.splice(0);
  await rig.poll('ha_net=healthy ha_resp=healthy ha_net_p95=-1 ha_net_n=0 ha_net_miss=0 ha_net_age=420000');
  assert.ok(rig.calls.some((call) => call.key === 'shell.runtime.duration_minutes'));

  await rig.poll('ha_net=severe ha_resp=healthy ha_net_p95=4200 ha_net_n=2000 ha_net_miss=1234');
  assert.match(rig.ids.hanetbar.textContent, /4\.200/);
  assert.match(rig.ids.hanetbar.textContent, /1\.234/);
  assert.match(rig.ids.hanetbar.textContent, /2\.000/);
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
      assert.equal(rig.ids.hanetbar.textContent, `⚠ ${translations[bannerKey]}`);
      assert.equal(rig.ids.hanetcell.textContent, row);
      assert.ok(rig.calls.some((call) => call.key === bannerKey));
      assert.ok(rig.calls.some((call) => call.key === rowKey));
      assert.doesNotMatch(rig.ids.hanetbar.textContent + rig.ids.hanetcell.textContent, /9876|9,876|4321|4,321|1234|1,234|7654321|7,654,321|p95|miss/i);
    }
  }

  for (const cause of ['future_value', 'constructor', '__proto__']) {
    await rig.poll(`ha_net=warning ha_net_cause=${cause} ha_resp=healthy ha_net_p95=1 ha_net_n=1 ha_net_miss=1`);
    assert.equal(rig.ids.hanetbar.style.display, 'none');
    assert.equal(rig.ids.hanetcell.textContent, '');
  }
});

test('version and settings banners create a safe localized reload link and preserve English without helper', async () => {
  const translations = {
    'shell.new_version.installed': '已安装新版本',
    'shell.settings_changed.externally': '设置已在其他位置更改',
    'shell.action.reload': '重新加载',
    'shell.new_version.refresh_suffix': '以刷新此页面。',
  };
  const version = await loadBuildwatch({ translations, locale: 'zh-Hans', dirty: true });
  await version.poll('build=build-b cfg=cfg-a');
  assert.equal(version.ids.verbar.textContent, '⟳ 已安装新版本 — 重新加载 以刷新此页面。');
  assert.equal(version.ids.verbar.children[1].tagName, 'A');
  assert.equal(version.ids.verbar.children[1].href, '#');
  version.ids.verbar.children[1].listeners.click({ preventDefault() {} });
  assert.equal(version.location.reloads, 1);
  await version.poll('build=build-b cfg=cfg-a');
  assert.equal(version.ids.verbar.textContent, '⟳ 已安装新版本 — 重新加载 以刷新此页面。');
  assert.equal(version.ids.verbar.children.filter((child) => child.tagName === 'A').length, 1);

  const settings = await loadBuildwatch({ translations, locale: 'zh-Hans', dirty: true, pathname: '/configure' });
  await settings.poll('build=build-a cfg=cfg-b');
  assert.equal(settings.ids.verbar.textContent, '⟳ 设置已在其他位置更改 — 重新加载 以刷新此页面。');
  await settings.poll('build=build-a cfg=cfg-b');
  assert.equal(settings.ids.verbar.textContent, '⟳ 设置已在其他位置更改 — 重新加载 以刷新此页面。');
  assert.equal(settings.ids.verbar.children.filter((child) => child.tagName === 'A').length, 1);

  const english = await loadBuildwatch({ helper: false, dirty: true });
  await english.poll('build=build-b cfg=cfg-a');
  assert.equal(english.ids.verbar.textContent, '⟳ A newer ha-paneld is installed — reload to refresh this page.');
  assert.doesNotMatch(english.source, /\.innerHTML\s*=/);
  assert.doesNotMatch(english.source, /replace\(\/\^\[\^A-Za-z\]/);
});

test('untrusted catalogue strings stay inert text in lifecycle, network, and reload banners', async () => {
  const payload = '<img src=x onerror="globalThis.translationExecuted=true">';
  const translations = {
    'shell.runtime.ha_lifecycle.starting': payload,
    'shell.runtime.ha_network.banner_warning': payload + ' {evidence}',
    'shell.runtime.ha_network.banner_latency_warning': payload,
    'dashboard.runtime.ha_network_latency_warning': payload,
    'shell.runtime.ha_network_evidence_p95_no_misses': payload,
    'shell.new_version.installed': payload,
    'shell.action.reload': payload,
    'shell.new_version.refresh_suffix': payload,
  };
  const rig = await loadBuildwatch({ translations, dirty: true });

  await rig.poll('ha=starting ha_net=warning ha_resp=healthy ha_net_p95=25 ha_net_n=30 ha_net_miss=0');
  assert.equal(rig.ids.halifebar.textContent, `⟳ ${payload}`);
  assert.equal(rig.ids.halifecell.textContent, payload);
  assert.equal(rig.ids.hanetbar.textContent, `⚠ ${payload} ${payload}`);
  assert.equal(rig.ids.hanetcell.textContent, `losing probes; ${payload}`);

  await rig.poll('ha_net=warning ha_net_cause=latency ha_resp=healthy ha_net_p95=25 ha_net_n=30 ha_net_miss=0');
  assert.equal(rig.ids.hanetbar.textContent, `⚠ ${payload}`);
  assert.equal(rig.ids.hanetcell.textContent, payload);

  await rig.poll('build=build-b cfg=cfg-a');
  assert.equal(rig.ids.verbar.textContent, `⟳ ${payload} — ${payload} ${payload}`);
  assert.deepEqual(rig.created.map((element) => element.tagName), ['A']);
  assert.doesNotMatch(rig.source, /\.innerHTML\s*=/);
});

test('missing or non-callable translation helpers preserve exact English runtime copy', async () => {
  for (const options of [{ helper: false }, { helperValue: { locale: 'en', t: 'not-a-function' } }]) {
    const rig = await loadBuildwatch({ ...options, dirty: true });
    await rig.poll('ha=starting ha_net=warning ha_resp=healthy ha_net_p95=25 ha_net_n=30 ha_net_miss=0 build=build-b cfg=cfg-a');

    assert.equal(rig.ids.halifebar.textContent, '⟳ Home Assistant is starting — controls will return shortly.');
    assert.equal(rig.ids.halifecell.textContent, 'Home Assistant is starting — controls will return shortly.');
    assert.equal(
      rig.ids.hanetbar.textContent,
      '⚠ Probes to Home Assistant are going missing: p95 25 ms, no misses in the last 5 min. Packets are not getting through. Check the Wi-Fi path between this panel and Home Assistant before blaming the panel.'
    );
    assert.equal(rig.ids.hanetcell.textContent, 'losing probes; p95 25 ms, no misses in the last 5 min');
    assert.equal(rig.ids.verbar.textContent, '⟳ A newer ha-paneld is installed — reload to refresh this page.');
  }
});

test('missing and non-callable helpers reproduce every English network row and banner composition', async () => {
  const helperCases = [
    ['missing helper', { helper: false }],
    ['non-callable helper', { helperValue: { locale: 'en', t: 'not-a-function' } }],
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
        rig.ids.hanetbar.textContent,
        `⚠ ${english(`shell.runtime.ha_network.banner_latency_${state}`)}`,
        `${helperLabel}: latency ${state} banner`,
      );
      assert.doesNotMatch(rig.ids.hanetbar.textContent + rig.ids.hanetcell.textContent, /9876|4321|1234|p95|miss/i);
    }

    for (const evidenceCase of evidenceCases) {
      const evidence = english(evidenceCase.key, evidenceCase.values);
      for (const state of ['warning', 'severe']) {
        await rig.poll(`ha_net=${state} ha_resp=healthy ${evidenceCase.tokens}`);
        const bannerKey = `shell.runtime.ha_network.banner_${state}`;
        assert.equal(
          rig.ids.hanetbar.textContent,
          `⚠ ${english(bannerKey, { evidence })}`,
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
  await rig.poll('ha=shutting_down ha_src=native ha_reason=core_update ha_elapsed_ms=1000 ha_expected_ms=121000');
  assert.match(rig.ids.halifecell.textContent, /Home Assistant Core update/);
  assert.match(rig.ids.halifecell.textContent, /Expected back in about 2 min/);
  await rig.poll('ha=starting ha_src=native ha_reason=restart ha_elapsed_ms=181000 ha_expected_ms=121000');
  assert.match(rig.ids.halifebar.textContent, /Taking longer than usual/);
  assert.match(rig.ids.halifebar.textContent, /1 min past the estimate/);
  assert.equal(rig.ids.halifebar.children.at(-1).tagName, 'SMALL');
  await rig.poll('ha=starting ha_reason=restart ha_elapsed_ms=3721000 ha_expected_ms=121000');
  assert.match(rig.ids.halifebar.textContent, /1 h past the estimate/);
  await rig.poll('ha=back_online ha_src=native ha_reason=restart ha_elapsed_ms=3721000 ha_expected_ms=121000');
  assert.doesNotMatch(rig.ids.halifebar.textContent, /Taking longer|past the estimate/);
  await rig.poll('ha=normal ha_reason=unknown');
  assert.equal(rig.ids.halifebar.style.display, 'none');
});
