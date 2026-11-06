import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';

const asset = process.argv[2] || fileURLToPath(new URL('../../app/src/main/assets/install.js', import.meta.url));
const powerAsset = fileURLToPath(new URL('../../app/src/main/assets/power-safety.js', import.meta.url));
const catalogueDir = fileURLToPath(new URL('../../app/src/main/assets/i18n/', import.meta.url));
const chrome = process.env.CHROME || '/usr/bin/chromium';
const browserTest = existsSync(chrome) ? test : test.skip;

async function realCatalogueProjection(locale, prefixes) {
  const source = JSON.parse(await readFile(`${catalogueDir}/en.json`, 'utf8'));
  const target = JSON.parse(await readFile(`${catalogueDir}/${locale}.json`, 'utf8'));
  const strings = {}, languages = {};
  for (const [key, english] of Object.entries(source.strings)) {
    if (!prefixes.some((prefix) => key.startsWith(prefix))) continue;
    const candidate = target.strings[key];
    const localized = candidate && candidate.sourceHash === english.sourceHash &&
      (candidate.state === 'machine-cross-checked' || candidate.state === 'community-corrected');
    strings[key] = localized ? candidate.text : english.text;
    languages[key] = localized ? locale : 'en';
  }
  return { locale, strings, languages };
}

const samples = Object.freeze({
  owner: 'paneld', component: 'paneld', channel: 'stable', count: '2', package: 'io.example.app',
  version: '1.2.3', current: '1.0', latest: '1.2', cap: '1.1', current_engine: 'WebView 130',
  target_chromium: '130', from_schema: '8', to_schema: '7', release_url: 'https://example.invalid/release',
  usable_bytes: '1024', total_bytes: '2048', used_percent: '50.0', database_bytes: '512', wal_bytes: '16',
  failure: 'io', operation: 'database-checkpoint', bound_ip: '192.0.2.1', lan_ip: '192.0.2.2',
  attempts: '2', reason_code: 'no-response',
});

// Cases come from the published English catalogue, independently of install.js's private tables.
// These ten protocol codes intentionally reuse a different catalogue family or a plural selector.
async function presentationCases() {
  const catalogue = JSON.parse(await readFile(`${catalogueDir}/en.json`, 'utf8')).strings;
  const aliases = {
    'restore-completed-with-state': ['install.restore.state_values.one', 'install.restore.state_values.other'],
    'destructive-operation-in-progress': ['profiles.result.destructive-operation-in-progress'],
    'profile-restart-unavailable': ['profiles.result.profile-restart-unavailable'],
    'profile-activation-abort-persist-failed': ['profiles.result.profile-activation-abort-persist-failed'],
    'companion-urls-repaired': ['install.companion.urls_repaired.one', 'install.companion.urls_repaired.other'],
    'status-companion-url-missing': ['install.status.companion_url_missing.one', 'install.status.companion_url_missing.other'],
    'status-mdns-not-running': ['runtime.mdns.not_running'],
    'status-mdns-stale-address': ['runtime.mdns.stale_address'],
    'status-mdns-unresponsive': ['runtime.mdns.unresponsive.one', 'runtime.mdns.unresponsive.other'],
    'status-mdns-recovering': ['runtime.mdns.recovering'],
  };
  const codes = Object.fromEntries(Object.keys(catalogue).filter(key => key.startsWith('install.presentation.'))
    .map(key => [key.slice('install.presentation.'.length).replaceAll('_', '-'), [key]]));
  Object.assign(codes, aliases);
  return Object.entries(codes).map(([code, keys]) => {
    const names = new Set(keys.flatMap(key => catalogue[key].placeholders.map(name => name.slice(1, -1))));
    if (code === 'operation-working') names.add('owner');
    if (['status-storage-warning', 'status-storage-critical', 'status-storage-database-failure'].includes(code)) {
      for (const name of ['usable_bytes', 'total_bytes', 'used_percent', 'database_bytes', 'wal_bytes']) names.add(name);
    }
    for (const name of names) assert.ok(Object.hasOwn(samples, name), `${code}: sample for ${name}`);
    return { code, keys, params: Object.fromEntries([...names].map(name => [name, samples[name]])) };
  });
}

async function rig(t, options = {}) {
  const source = await readFile(asset, 'utf8');
  const cases = await presentationCases();
  const strings = {};
  const languages = {};
  for (const { code, keys } of cases) {
    for (const key of keys) {
      strings[key] = `LOC:${code}:${Object.keys(samples).map((name) => `{${name}}`).join('|')}`;
      languages[key] = 'zh-Hans';
    }
  }
  Object.assign(strings, options.extraStrings || {});
  Object.keys(options.extraStrings || {}).forEach((key) => { languages[key] = 'zh-Hans'; });
  if (options.untranslated) for (const key of cases.find(item => item.code === options.untranslated).keys) delete languages[key];
  const projection = options.projection || { locale: 'zh-Hans', strings, languages };
  const projectedLocale = projection.locale;
  const status = options.status || { warnings: [], warning_presentations: [] };
  const server = createServer((request, response) => {
    const requestUrl = new URL(request.url, 'http://panel.test');
    const path = requestUrl.pathname;
    if (options.route && options.route(request, response, requestUrl)) return;
    if (path === '/install.js') { response.writeHead(200, { 'content-type': 'application/javascript' }); response.end(source); return; }
    if (path === '/api/v1/radio') { response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify(options.radio || { present: false })); return; }
    if (path === '/api/v1/status') { response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify(status)); return; }
    response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    response.end(`<!doctype html><html lang="${projectedLocale}"><head>${i18nBridge(options.noHelper ? {} : projection)}</head><body>${options.html || '<div id="audit-out"></div><div id="bk-msg"></div>'}<script>window.CardColumnAlignment={attach:()=>()=>{}};</script><script src="/install.js"></script></body></html>`);
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  let browser;
  t.after(async () => {
    try { await browser?.close(); }
    finally { server.closeAllConnections?.(); await new Promise((done) => server.close(done)); }
  });
  browser = await (options.engine === 'WebKit' ? webkit.launch({ headless: true }) : chromium.launch({ executablePath: chrome, headless: true }));
  const page = await browser.newPage();
  await page.goto(`http://127.0.0.1:${server.address().port}/${options.query || ''}`, { waitUntil: 'domcontentloaded' });
  return { page, cases };
}

browserTest('Version picker explains unavailable releases and keeps permitted choices usable', async (t) => {
  const versions = [
    { tag: 'v4', version: '4.0', installable: false, unavailableReason: 'above_panel_limit', maxVersion: '2.0' },
    { tag: 'v2', version: '2.0', installable: true, action: 'Upgrade', apk: 'https://github.com/example/app/releases/download/v2/app.apk' },
    { tag: 'v1', version: '1.0', installable: false, unavailableReason: 'no_matching_asset' },
    { tag: 'legacy', version: '0.9', installable: false },
  ];
  const { page } = await rig(t, {
    noHelper: true,
    html: '<div class="comprow" data-name="companion"><span class="cver">1.0</span><select class="cchan"><option>stable</option></select><select class="cvsel"></select><a class="cnotes">Notes</a><button class="cinstall" data-root="1"></button><a class="cdl">Download</a><span class="cvreason" hidden></span></div>',
    route(request, response, url) {
      if (url.pathname !== '/api/v1/install/versions') return false;
      response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify({ versions })); return true;
    },
  });
  await page.evaluate(() => window.loadVersions('companion'));
  assert.deepEqual(await page.locator('.cvsel option').allTextContents(), [
    '4.0 (not supported by this panel; limit 2.0)', '2.0', '1.0 (no compatible download)', '0.9 (no compatible download)',
  ]);
  assert.equal(await page.locator('.cvsel').inputValue(), 'v2');
  assert.equal(await page.locator('.cinstall').isDisabled(), false);
  assert.equal(await page.locator('.cdl').isVisible(), true);
  assert.equal(await page.locator('.cvreason').isVisible(), false);
  for (const [tag, reason] of [['v4', '(not supported by this panel; limit 2.0)'], ['v1', '(no compatible download)'], ['legacy', '(no compatible download)']]) {
    await page.locator('.cvsel').selectOption(tag);
    await page.evaluate(() => window.verChanged('companion'));
    assert.equal(await page.locator('.cinstall').isDisabled(), true, tag);
    assert.equal(await page.locator('.cdl').isVisible(), false, tag);
    assert.equal(await page.locator('.cvreason').textContent(), reason, tag);
  }
  await page.locator('.cvsel').selectOption('v2');
  await page.evaluate(() => window.verChanged('companion'));
  assert.equal(await page.locator('.cinstall').isDisabled(), false);
  assert.equal(await page.locator('.cvreason').isVisible(), false);
  for (const url of ['https://github.com/example/app/releases/tag/v2', 'javascript:alert(1)', 'http://github.com/example/app', 'https://github.com.evil.example/app']) {
    versions[1].notes = url; versions[1].apk = url;
    await page.evaluate(() => window.loadVersions('companion'));
    const safe = url.startsWith('https://github.com/');
    assert.equal(await page.locator('.cnotes').isVisible(), safe);
    assert.equal(await page.locator('.cdl').isVisible(), safe);
    assert.equal(await page.locator('.cnotes').getAttribute('href'), safe ? url : null);
    assert.equal(await page.locator('.cdl').getAttribute('href'), safe ? url : null);
  }
});

browserTest('Version picker retains installed release fallback with a formatted version label', async (t) => {
  const { page } = await rig(t, {
    noHelper: true,
    html: '<div class="comprow" data-name="companion" data-installed-version="0.9.9"><span class="cver">0.9.9 (1136)</span><select class="cvsel"></select></div>',
    route(request, response, url) {
      if (url.pathname !== '/api/v1/install/versions') return false;
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ versions: [
        { tag: 'v1.0.0', version: '1.0.0', installable: false },
        { tag: 'v0.9.9', version: '0.9.9', installable: false },
      ] }));
      return true;
    },
  });
  await page.evaluate(() => window.loadVersions('companion'));
  assert.equal(await page.locator('.cvsel').inputValue(), 'v0.9.9');
  assert.equal(await page.locator('.cver').textContent(), '0.9.9 (1136)');
});

browserTest('Version picker uses every shipped locale for all unavailable reasons', async (t) => {
  for (const locale of ['de', 'es', 'fr', 'it', 'nl', 'pl', 'uk', 'cs', 'pt-BR', 'zh-Hans']) {
    const projection = await realCatalogueProjection(locale, ['install.']);
    const { page } = await rig(t, {
      projection,
      html: '<div class="comprow" data-name="companion"><select class="cchan"><option>stable</option></select><select class="cvsel"></select><span class="cvreason" hidden></span></div>',
      route(request, response, url) {
        if (url.pathname !== '/api/v1/install/versions') return false;
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify({ versions: ['no_matching_asset', 'above_panel_limit'].map((reason) => ({ tag: reason, version: '4.0', installable: false, unavailableReason: reason, maxVersion: '2.0' })) }));
        return true;
      },
    });
    await page.evaluate(() => window.loadVersions('companion'));
    for (const reason of ['no_matching_asset', 'above_panel_limit']) {
      const key = `install.progress.${reason}`;
      assert.equal(projection.languages[key], locale, `${locale}/${key}: reviewed translation required`);
      await page.locator('.cvsel').selectOption(reason);
      await page.evaluate(() => window.verChanged('companion'));
      assert.equal(await page.locator('.cvreason').textContent(), projection.strings[key].replace('{version}', '2.0'), `${locale}/${key}`);
    }
  }
});

browserTest('Install localizes closed wire tokens from the real German catalogue projection', async (t) => {
  const projection = await realCatalogueProjection('de', ['shell.', 'install.']);
  const { page } = await rig(t, {
    projection,
    radio: { present: true, status: 'ready', state: 'degraded_unjoined' },
    html: '<div id="radiocard" style="display:none"><span id="radio-status"></span><span id="radio-health"></span></div><div id="bk-msg"></div>',
  });

  await page.waitForFunction(() => document.getElementById('radio-health').textContent.length > 0);
  assert.equal(await page.locator('#radio-health').textContent(), 'aktiviert, aber keinem Netz beigetreten');
  assert.notEqual(await page.locator('#radio-health').textContent(), 'degraded_unjoined');

  const restore = await page.evaluate(() => {
    window.HaPaneldInstallPresentation.renderRestoreResult({
      message: 'complete',
      presentation: { code: 'restore-completed', params: {} },
      result: { config: { status: 'rollback_failed' } },
    });
    return document.getElementById('bk-msg').textContent;
  });
  assert.match(restore, /Rollback fehlgeschlagen/);
  assert.doesNotMatch(restore, /rollback_failed/);
});

browserTest('Install accepts every published presentation code with its catalogue parameter shape', async (t) => {
  const { page, cases } = await rig(t);
  assert.equal(cases.length, 115, 'the published protocol vocabulary is nonempty and complete');
  const outcomes = await page.evaluate((items) => items.map((envelope) => window.HaPaneldInstallPresentation.present(envelope, `RAW:${envelope.code}`)), cases.map(({ code, params }) => ({ code, params })));
  outcomes.forEach((outcome, index) => {
    assert.equal(outcome.fallback, false, `${cases[index].code} unexpectedly fell back`);
    assert.match(outcome.text, new RegExp(`^LOC:${cases[index].code}:`));
  });
});

browserTest('Install rejects malformed, unknown, unsafe and untranslated metadata exactly', async (t) => {
  const { page } = await rig(t, { untranslated: 'managed-up-to-date' });
  const fallback = '<b>exact compatibility</b>';
  const cases = [
    null, [], {}, { code: 'future-code', params: {} }, { code: 'managed-up-to-date', params: { component: 'paneld', current: '1' } },
    { code: 'managed-apk-missing', params: { component: 'paneld' } },
    { code: 'managed-apk-missing', params: { component: 'paneld', version: '1', extra: 'x' } },
    { code: 'managed-apk-missing', params: { component: 7, version: '1' } },
    { code: 'managed-apk-missing', params: { component: 'paneld', version: 'x'.repeat(513) } },
    { code: 'status-update-available', params: { component: 'paneld', current: '1', latest: '2', release_url: 'javascript:alert(1)' } },
    { code: 'version-install', params: {}, extra: true },
  ];
  const outcomes = await page.evaluate(({ cases, fallback }) => cases.map((item) => window.HaPaneldInstallPresentation.present(item, fallback)), { cases, fallback });
  assert.ok(outcomes.every((item) => item.fallback && item.text === fallback));
});

browserTest('Install substitutes exact visible names for closed component parameters', async (t) => {
  const { page } = await rig(t, { extraStrings: { 'install.presentation.status_update_available': 'Update {component}: {current} to {latest} at {release_url}' } });
  const outcomes = await page.evaluate(() => ['paneld', 'companion', 'webview', 'apk'].map((component) => window.HaPaneldInstallPresentation.present({
    code: 'status-update-available', params: { component, current: '1', latest: '2', release_url: 'https://example.invalid/release' },
  }, 'fallback').text));
  assert.deepEqual(outcomes, [
    'Update Panel Assistant: 1 to 2 at https://example.invalid/release',
    'Update Home Assistant Companion: 1 to 2 at https://example.invalid/release',
    'Update System WebView: 1 to 2 at https://example.invalid/release',
    'Update APK: 1 to 2 at https://example.invalid/release',
  ]);
});

browserTest('Install presentation rendering keeps hostile values as text, raw evidence English and HTTPS links explicit', async (t) => {
  const { page } = await rig(t);
  const outcome = await page.evaluate(() => {
    const hostile = document.createElement('div'); document.body.appendChild(hostile);
    window.HaPaneldInstallPresentation.set(hostile, { code: 'managed-apk-missing', params: { component: 'paneld', version: '<img src=x onerror=window.__owned=1>' } }, 'RAW hostile');
    const raw = document.createElement('div'); document.body.appendChild(raw);
    window.HaPaneldInstallPresentation.set(raw, { code: 'package-uninstall-failed', params: { package: 'io.example.app' } }, '<img src=x onerror=window.__owned=2>');
    const linked = document.createElement('div'); document.body.appendChild(linked);
    window.HaPaneldInstallPresentation.set(linked, { code: 'status-update-available', params: { component: 'paneld', current: '1', latest: '2', release_url: 'https://example.invalid/release' } }, 'fallback');
    return { hostile: hostile.textContent, raw: raw.textContent, rawLang: raw.querySelector('[lang="en"]')?.textContent, images: document.querySelectorAll('img').length, href: linked.querySelector('a')?.href, owned: window.__owned };
  });
  assert.match(outcome.hostile, /<img src=x/);
  assert.equal(outcome.rawLang, '<img src=x onerror=window.__owned=2>');
  assert.equal(outcome.images, 0);
  assert.equal(outcome.href, 'https://example.invalid/release');
  assert.equal(outcome.owned, undefined);
});

browserTest('Install restore result localizes closed outcomes and isolates arbitrary English detail', async (t) => {
  const { page } = await rig(t);
  const outcome = await page.evaluate(() => {
    window.HaPaneldInstallPresentation.renderRestoreResult({
      message: 'completed',
      presentation: { code: 'restore-completed', params: {} },
      result: {
        config: { status: 'succeeded', items: 3 },
        companion: { status: 'partial', detail: '<img src=x onerror=window.__restoreOwned=1>' },
      },
    });
    const node = document.getElementById('bk-msg');
    return {
      text: node.textContent,
      parentLang: node.getAttribute('lang'),
      english: Array.from(node.querySelectorAll('[lang="en"]')).map((item) => item.textContent),
      images: node.querySelectorAll('img').length,
      owned: window.__restoreOwned,
    };
  });
  assert.match(outcome.text, /^LOC:restore-completed:/);
  assert.match(outcome.text, /Config: succeeded/);
  assert.match(outcome.text, /Companion: partial/);
  assert.equal(outcome.parentLang, null);
  assert.deepEqual(outcome.english, ['<img src=x onerror=window.__restoreOwned=1>']);
  assert.equal(outcome.images, 0);
  assert.equal(outcome.owned, undefined);
});

browserTest('Install warning overlay localizes valid entries and preserves exact per-item English fallback', async (t) => {
  const status = {
    warnings: ['<b>legacy known</b>', '<i>legacy unknown</i>'],
    warning_presentations: [{ code: 'status-no-renderer', params: {} }, null],
  };
  const { page } = await rig(t, { status });
  await page.evaluate(() => healthAudit({ disabled: false }));
  await page.waitForFunction(() => document.querySelectorAll('#audit-out .notice').length === 2);
  const rows = await page.locator('#audit-out .notice').evaluateAll((nodes) => nodes.map((node) => {
    const text = node.querySelector('.notice-text');
    return { severity: node.dataset.severity, pill: node.querySelector('.notice-pill').textContent, text: text.textContent, lang: text.getAttribute('lang') };
  }));
  assert.deepEqual(rows.map((row) => [row.severity, row.pill]), [['warning', 'Warning'], ['warning', 'Warning']], 'no warning_severities: every row is a Warning');
  assert.match(rows[0].text, /^LOC:status-no-renderer:/);
  assert.equal(rows[0].lang, null);
  assert.equal(rows[1].text, 'legacy unknown');
  assert.equal(rows[1].lang, 'en');
  assert.equal(await page.locator('#audit-out i').count(), 1, 'legacy fallback retains its byte-compatible server HTML path');
});

browserTest('Install power warnings require matching typed advisory state and retain its dynamic prose as exact evidence', async (t) => {
  const typed = {
    state: 'caution', warning: true,
    summary: '<img src=x onerror=window.__powerSummaryOwned=1>',
    action: '<b>typed acknowledgement action</b>',
  };
  const status = {
    warnings: ['<i>legacy power warning</i>'],
    warning_presentations: [{ code: 'status-power-caution', params: {} }],
    power_safety: typed,
  };
  const { page } = await rig(t, { status });
  await page.evaluate(() => healthAudit({ disabled: false }));
  await page.waitForFunction(() => document.querySelectorAll('#audit-out .notice').length === 1);
  const rendered = await page.locator('#audit-out .notice-text').evaluate((node) => ({
    text: node.textContent,
    english: Array.from(node.querySelectorAll('[lang="en"]')).map((part) => part.textContent),
    images: node.querySelectorAll('img').length,
  }));
  assert.match(rendered.text, /^LOC:status-power-caution:/);
  assert.deepEqual(rendered.english, [typed.summary, typed.action]);
  assert.equal(rendered.images, 0);
  assert.equal(await page.evaluate(() => typeof window.__powerSummaryOwned), 'undefined');

  const mismatch = await rig(t, { status: { ...status, power_safety: { ...typed, state: 'at_risk' } } });
  await mismatch.page.evaluate(() => healthAudit({ disabled: false }));
  await mismatch.page.waitForFunction(() => document.querySelectorAll('#audit-out .notice').length === 1);
  assert.equal(await mismatch.page.locator('#audit-out .notice-text').getAttribute('lang'), 'en');
  assert.equal(await mismatch.page.locator('#audit-out .notice-text').innerHTML(), '<i>legacy power warning</i>');
});

browserTest('Install warning rows take their severity from warning_severities and drop the legacy glyph', async (t) => {
  const aligned = {
    warnings: ['⛔ <b>stopped</b> now', '⚠ plain', 'ℹ note'],
    warning_severities: ['action_needed', 'bogus', 'note'],
  };
  const { page } = await rig(t, { status: aligned });
  await page.evaluate(() => healthAudit({ disabled: false }));
  await page.waitForFunction(() => document.querySelectorAll('#audit-out .notice').length === 3);
  const rows = await page.locator('#audit-out .notice').evaluateAll((nodes) => nodes.map((node) => [node.dataset.severity, node.querySelector('.notice-pill').textContent, node.querySelector('.notice-text').textContent]));
  assert.deepEqual(rows, [['action_needed', 'Action needed', 'stopped now'], ['warning', 'Warning', 'plain'], ['note', 'Note', 'note']]);

  const misaligned = await rig(t, { status: { warnings: ['one', 'two'], warning_severities: ['action_needed'] } });
  await misaligned.page.evaluate(() => healthAudit({ disabled: false }));
  await misaligned.page.waitForFunction(() => document.querySelectorAll('#audit-out .notice').length === 2);
  assert.deepEqual(await misaligned.page.locator('#audit-out .notice').evaluateAll((nodes) => nodes.map((node) => node.dataset.severity)), ['warning', 'warning']);
});

browserTest('Install APK and uninstall results localize their controlled summary while retaining exact legacy evidence', async (t) => {
  const route = (request, response, url) => {
    if (url.pathname === '/api/v1/packages') {
      response.writeHead(200, { 'content-type': 'application/json' }); response.end('{"packages":[{"pkg":"io.example.app","label":"Example"}]}'); return true;
    }
    if (url.pathname === '/api/v1/install/apk/commit') {
      response.writeHead(200, { 'content-type': 'application/json' }); response.end('{"status":"started"}'); return true;
    }
    if (url.pathname === '/api/v1/install/status') {
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ running: false, message: '<b>exact APK result</b>', presentation: { code: 'managed-install-committed', params: { component: 'apk', version: '1.2.3' } } })); return true;
    }
    if (url.pathname === '/api/v1/uninstall') {
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ ok: false, result: '<i>exact uninstall failure</i>', presentation: { code: 'package-uninstall-failed', params: { package: 'io.example.app' } } })); return true;
    }
    return false;
  };
  const { page } = await rig(t, {
    route,
    extraStrings: { 'install.apk.dynamic.result_label': 'LOC result:' },
    html: '<div id="apk-msg"></div><div id="apk-preview"><button data-token="token"></button></div><select id="uninst-pkg"><option value="io.example.app">Example</option></select><div id="uninst-msg"></div>',
  });
  await page.evaluate(() => window.apkInstall(document.querySelector('#apk-preview button')));
  await page.waitForFunction(() => document.getElementById('apk-msg').textContent.includes('LOC:managed-install-committed:'));
  assert.match(await page.locator('#apk-msg').textContent(), /^LOC result: LOC:managed-install-committed:/);
  assert.equal(await page.locator('#apk-msg [lang="en"]').count(), 0, 'non-raw completion must show only the localized controlled result');
  assert.equal(await page.locator('#apk-msg b').count(), 0, 'raw APK evidence must remain inert text');

  page.on('dialog', (dialog) => dialog.accept());
  await page.evaluate(() => window.doUninstall(document.createElement('button')));
  await page.waitForFunction(() => document.querySelector('#uninst-msg [lang="en"]'));
  assert.match(await page.locator('#uninst-msg').textContent(), /^LOC:package-uninstall-failed:/);
  assert.equal(await page.locator('#uninst-msg [lang="en"]').textContent(), 'Failed: <i>exact uninstall failure</i>');
  assert.equal(await page.locator('#uninst-msg i').count(), 0, 'raw uninstall evidence must remain inert text');
});

browserTest('Install APK progress shows panel confirmation instructions and keeps generic running fallbacks', async (t) => {
  const hostile = '<img src=x onerror=window.__apkOwned=1>';
  const statuses = [
    { running: true, message: 'Tap Install on the panel to finish the update', presentation: { code: 'install-awaiting-confirmation', params: { component: 'apk' } } },
    { running: true, message: hostile, presentation: { code: 'install-awaiting-confirmation', params: { component: 'apk', unexpected: hostile } } },
    { running: true, message: hostile, presentation: { code: 'unknown-running-code', params: {} } },
    { running: true, message: hostile },
    { running: false, message: hostile, presentation: { code: 'managed-install-committed', params: { component: 'apk', version: '1.2.3' } } },
  ];
  for (const engine of ['Chromium', 'WebKit']) {
    let current = 0, commits = 0, reads = 0;
    const { page } = await rig(t, {
      engine,
      route(_request, response, url) {
        if (url.pathname === '/api/v1/install/apk/commit') {
          commits++; response.writeHead(200, { 'content-type': 'application/json' }); response.end('{"status":"started"}'); return true;
        }
        if (url.pathname !== '/api/v1/install/status') return false;
        reads++; response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify(statuses[current])); return true;
      },
      extraStrings: { 'install.presentation.install_awaiting_confirmation': 'LOC confirm {component} on the panel', 'install.apk.dynamic.installing': 'LOC installing', 'install.apk.dynamic.result_label': 'LOC result:' },
      html: '<div id="apk-msg"></div><div id="apk-preview"><button id="install" data-token="token" onclick="apkInstall(this)">Install</button><button id="discard">Discard</button></div>',
    });
    for (current = 0; current < statuses.length; current++) {
      const nextStatus = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/install/status');
      if (current === 0) await page.click('#install');
      await (await nextStatus).finished();
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      const text = await page.locator('#apk-msg').textContent();
      if (current === 0) assert.equal(text, 'LOC confirm APK on the panel', engine + ': pending system confirmation must tell the operator what to do');
      else if (statuses[current].running) assert.equal(text, 'LOC installing', engine + ': invalid or absent running presentation keeps the localized generic fallback');
      else assert.match(text, /^LOC result: LOC:managed-install-committed:/);
      assert.equal(await page.locator('#apk-msg').getAttribute('lang'), null);
      assert.equal(await page.locator('#apk-msg img').count(), 0);
      assert.equal(await page.evaluate(() => typeof window.__apkOwned), 'undefined');
      assert.equal(await page.locator('#install').isDisabled(), true);
      assert.equal(await page.locator('#discard').isDisabled(), true);
    }
    assert.equal(commits, 1, engine + ': progress does not repeat the admitted install');
    assert.equal(reads, statuses.length);
  }
});

for (const engine of ['Chromium', 'WebKit']) for (const [endpoint, fragment, fieldsHtml] of [
  ['/api/v1/display/density', '#cfg-display', '<input name="density" value="240"><button name="action" value="reset">Reset</button>'],
  ['/api/v1/tame', '#cfg-tame', '<input name="pkg" value="io.example.vendor"><input type="hidden" name="action" value="tame"><button>Tame</button>'],
]) {
  browserTest(`Install protected-form ${endpoint} success keeps the supported explicit language in ${engine}`, async (t) => {
    const route = (_request, response, url) => {
      if (url.pathname !== endpoint) return false;
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ ok: true, message: 'Applied.' })); return true;
    };
    for (const locale of ['zh-Hans', 'cs', 'pt-BR']) {
      await t.test(locale, async (t) => {
        const { page } = await rig(t, {
          engine, route, query: `?lang=${locale}`,
          html: `<form action="${endpoint}" method="post">${fieldsHtml}</form>`,
        });
        await page.locator('form button').click();
        await page.waitForURL((url) => url.pathname === '/install' && url.searchParams.get('lang') === locale && url.hash === fragment, { timeout: 3_000 });
        assert.equal(new URL(page.url()).searchParams.get('lang'), locale);
      });
    }
  });
}

for (const engine of ['Chromium', 'WebKit']) for (const [endpoint, fields] of [
  ['/api/v1/display/density', { density: '240', font: '1', action: 'reset' }],
  ['/api/v1/tame', { pkg: 'io.example.vendor', action: 'tame' }],
]) {
  browserTest(`Protected forms ${endpoint} localize failures and preserve approval guidance in ${engine}`, async (t) => {
    for (const locale of ['de', 'es', 'fr', 'it', 'nl', 'pl', 'uk', 'cs', 'pt-BR', 'zh-Hans']) {
      await t.test(locale, async (t) => {
        const projection = await realCatalogueProjection(locale, ['install.']);
        const { page } = await rig(t, {
          engine, projection, query: `?lang=${locale}`,
          html: '<form action="/api/v1/display/density" method="post"><input name="density" value="240"><input name="font" value="1"><button name="action" value="reset">Reset</button></form>' +
            '<form action="/api/v1/tame" method="post"><input name="pkg" value="io.example.vendor"><input type="hidden" name="action" value="tame"><button>Tame</button></form>',
        });
        await page.evaluate(() => document.body.setAttribute('data-hardened', ''));
        const form = page.locator(`form[action="${endpoint}"]`), button = form.locator('button');
        const note = form.locator('.protected-form-result');
        const diagnostic = '<img src=x onerror=window.__formOwned=1> exact failure';
        for (const [kind, status, body, expected] of [
          ['network', 0, null, engine === 'WebKit' ? 'Load failed' : 'Failed to fetch'],
          ['rejected', 500, { message: diagnostic }, diagnostic],
          ['challenge', 202, { error: 'approval-required' }, projection.strings['install.progress.approval_retry']],
        ]) {
          const requests = [];
          let answer;
          await page.route(`**${endpoint}`, async (route) => {
            requests.push({ method: route.request().method(), fields: Object.fromEntries(new URLSearchParams(route.request().postData())) });
            await new Promise((resolve) => { answer = resolve; });
            if (kind === 'network') await route.abort('failed');
            else await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
          });
          await button.click();
          assert.equal(await button.isDisabled(), true, `${locale}/${kind}: submission is held`);
          assert.equal(await note.textContent(), projection.strings['install.approval.waiting']);
          assert.ok(answer, 'the actual HTTP request reached the route');
          answer();
          await button.waitFor({ state: 'visible' });
          await page.waitForFunction((action) => !document.querySelector(`form[action="${action}"] button`).disabled, endpoint);
          if (kind === 'network' || kind === 'rejected') {
            assert.equal(await note.locator('[lang="en"]').count(), 1, 'a failure keeps its exact diagnostic separate from localized framing');
            assert.equal(await note.locator('span').first().textContent(), projection.strings['install.backup.dynamic.form_failed']);
            assert.equal(await note.locator('[lang="en"]').textContent(), expected);
            assert.equal(await note.getAttribute('lang'), null);
            assert.equal(await note.locator('img').count(), 0);
            assert.equal(await page.evaluate(() => window.__formOwned), undefined);
          } else {
            assert.equal(await note.textContent(), expected, `${locale}/${kind}: keep localized approval guidance`);
            assert.equal(await note.locator('[lang="en"]').count(), 0);
          }
          // Check for an immediate retry or navigation after the failed submission.
          await page.waitForTimeout(120);
          assert.deepEqual(requests, [{ method: 'POST', fields }]);
          assert.equal(new URL(page.url()).pathname, '/');
          await page.unroute(`**${endpoint}`);
        }
      });
    }
  });
}

browserTest('Install Backup, Export and Import failures localize prefixes and isolate exact diagnostics', async (t) => {
  const route = (_request, response, url) => {
    if (url.pathname === '/api/v1/backup') {
      response.writeHead(500, { 'content-type': 'application/json' }); response.end('{"message":"<img src=x onerror=window.__backupOwned=1>"}'); return true;
    }
    if (url.pathname === '/api/v1/config/export') {
      response.writeHead(500, { 'content-type': 'application/json' }); response.end('{"error":"<img src=x onerror=window.__exportOwned=1>"}'); return true;
    }
    if (url.pathname === '/api/v1/config/import') {
      response.writeHead(400, { 'content-type': 'application/json' }); response.end('{"status":"<img src=x onerror=window.__importOwned=1>"}'); return true;
    }
    return false;
  };
  const { page } = await rig(t, {
    route,
    extraStrings: {
      'install.backup.dynamic.backup_failed_prefix': 'LOC backup failed:',
      'install.backup.dynamic.export_failed_prefix': 'LOC export failed:',
      'install.backup.dynamic.import_failed_prefix': 'LOC import failed:',
    },
    html: '<input id="bk-pw" value="secret"><input id="bk-plain" type="checkbox"><input id="bk-comp" type="checkbox"><div id="bk-msg"></div><div id="cfg-export-result"></div><div id="cfg-import-result"></div><input id="cfg-import" type="file" onchange="configImport(this)">',
  });
  await page.evaluate(() => window.doBackup(document.createElement('button')));
  await page.waitForFunction(() => document.querySelector('#bk-msg [lang="en"]'));
  await page.evaluate(() => window.configExport(false, document.createElement('button')));
  await page.waitForFunction(() => document.querySelector('#cfg-export-result [lang="en"]'));
  await page.locator('#cfg-import').setInputFiles({ name: 'config.json', mimeType: 'application/json', buffer: Buffer.from('{}') });
  await page.waitForFunction(() => document.querySelector('#cfg-import-result [lang="en"]'));
  const outcomes = await page.evaluate(() => ['bk-msg', 'cfg-export-result', 'cfg-import-result'].map((id) => {
    const node = document.getElementById(id);
    return { text: node.textContent, raw: node.querySelector('[lang="en"]')?.textContent, images: node.querySelectorAll('img').length };
  }));
  assert.deepEqual(outcomes, [
    { text: 'LOC backup failed: <img src=x onerror=window.__backupOwned=1>', raw: '<img src=x onerror=window.__backupOwned=1>', images: 0 },
    { text: 'LOC export failed: <img src=x onerror=window.__exportOwned=1>', raw: '<img src=x onerror=window.__exportOwned=1>', images: 0 },
    { text: 'LOC import failed: <img src=x onerror=window.__importOwned=1>', raw: '<img src=x onerror=window.__importOwned=1>', images: 0 },
  ]);
  assert.deepEqual(await page.evaluate(() => ({
    backup: typeof window.__backupOwned,
    export: typeof window.__exportOwned,
    import: typeof window.__importOwned,
  })), { backup: 'undefined', export: 'undefined', import: 'undefined' });
});

browserTest('Install Backup failures preserve controlled evidence and allow a later archive download', async (t) => {
  const hostile = '<img src=x onerror=window.__backupOwned=1>';
  const failures = [
    { status: 507, body: { ok: false, error: 'backup-staging-retained', message: 'Sensitive temporary backup data could not be removed. Check panel storage, then retry; no backup was downloaded.', presentation: { code: 'backup-staging-retained', params: {} } }, code: 'backup-staging-retained' },
    { status: 413, body: { ok: false, error: 'companion-backup-too-large', presentation: { code: 'backup-companion-too-large', params: {} } }, code: 'backup-companion-too-large' },
    { status: 507, body: { message: hostile, presentation: { code: 'backup-staging-retained', params: { unexpected: hostile } } } },
    { status: 507, body: { message: hostile, presentation: { code: 'unknown-backup-code', params: {} } } },
    { status: 422, body: { error: hostile } },
  ];
  for (const engine of ['Chromium', 'WebKit']) {
    let requests = 0, downloads = 0;
    const { page } = await rig(t, {
      engine,
      route(_request, response, url) {
        if (url.pathname !== '/api/v1/backup') return false;
        const failure = failures[requests++];
        if (failure) {
          response.writeHead(failure.status, { 'content-type': 'application/json' });
          response.end(JSON.stringify(failure.body));
        } else {
          response.writeHead(200, { 'content-type': 'application/octet-stream' }); response.end('backup archive');
        }
        return true;
      },
      extraStrings: { 'install.backup.dynamic.backup_failed_prefix': 'LOC backup failed:', 'install.backup.dynamic.downloaded_encrypted': 'LOC downloaded' },
      html: '<input id="bk-pw" value="secret"><input id="bk-plain" type="checkbox"><input id="bk-comp" type="checkbox"><button id="backup" onclick="doBackup(this)">Backup</button><div id="bk-msg"></div>',
    });
    page.on('download', () => { downloads++; });
    await page.evaluate(() => {
      window.__archiveUrls = 0;
      const create = URL.createObjectURL.bind(URL);
      URL.createObjectURL = blob => { window.__archiveUrls++; return create(blob); };
    });
    for (const failure of failures) {
      await page.click('#backup');
      await page.waitForFunction(() => !document.getElementById('backup').disabled);
      const actual = await page.locator('#bk-msg').textContent();
      if (failure.code) {
        assert.ok(actual.startsWith('LOC backup failed: LOC:' + failure.code + ':'), engine + ': controlled backup failure: ' + actual);
        assert.equal(await page.locator('#bk-msg [lang="en"]').count(), 0);
      } else {
        assert.equal(actual, 'LOC backup failed: ' + hostile);
        assert.equal(await page.locator('#bk-msg [lang="en"]').textContent(), hostile);
      }
      assert.equal(await page.locator('#bk-msg img').count(), 0);
      assert.equal(await page.evaluate(() => typeof window.__backupOwned), 'undefined');
      assert.equal(downloads, 0, engine + ': failed backup must not download');
      assert.equal(await page.evaluate(() => window.__archiveUrls), 0, engine + ': failed backup must not create an archive URL');
    }
    const downloadEvent = page.waitForEvent('download');
    await page.click('#backup');
    const download = await downloadEvent;
    assert.ok(download.suggestedFilename().endsWith('.hpb'));
    await page.waitForFunction(() => !document.getElementById('backup').disabled);
    assert.equal(await page.locator('#bk-msg').textContent(), 'LOC downloaded');
    assert.equal(downloads, 1);
    assert.equal(await page.evaluate(() => window.__archiveUrls), 1);
    assert.equal(requests, failures.length + 1);
  }
});

browserTest('Install Restore confirms show preflight refusals after a fresh preview without starting work', async (t) => {
  const hostile = '<img src=x onerror=window.__restoreOwned=1>';
  const failures = [
    { passphrase: '', body: { ok: false, error: 'this bundle is encrypted — enter its passphrase', presentation: { code: 'restore-passphrase-required', params: {} } }, code: 'restore-passphrase-required' },
    { passphrase: 'wrong', body: { ok: false, error: 'wrong passphrase or corrupt bundle', presentation: { code: 'restore-passphrase-or-bundle-invalid', params: {} } }, code: 'restore-passphrase-or-bundle-invalid' },
    { passphrase: 'wrong', body: { ok: false, error: hostile, presentation: { code: 'restore-passphrase-required', params: { unexpected: hostile } } } },
    { passphrase: 'wrong', body: { ok: false, error: hostile } },
  ];
  for (const engine of ['Chromium', 'WebKit']) {
    const starts = [], polls = [];
    let previews = 0;
    const { page } = await rig(t, {
      engine,
      route(request, response, url) {
        if (url.pathname === '/api/v1/install/status') { polls.push(url.pathname); response.writeHead(200, { 'content-type': 'application/json' }); response.end('{}'); return true; }
        if (url.pathname !== '/api/v1/restore') return false;
        request.resume();
        const preview = url.searchParams.get('dry_run') === '1';
        if (preview) previews++;
        const body = preview ? { ok: true, panel_id: 'test panel', config_keys: 1, companion_files: 0 } : failures[starts.length].body;
        if (!preview) starts.push(request.headers['x-backup-passphrase'] || '');
        response.writeHead(preview ? 200 : 400, { 'content-type': 'application/json' }); response.end(JSON.stringify(body)); return true;
      },
      html: '<input id="rs-pw" value="correct"><input id="rs-file" type="file" onchange="restorePick(this)"><div id="rs-preview"></div><div id="bk-msg"></div>',
    });
    await page.locator('#rs-file').setInputFiles({ name: 'panel.hpb', mimeType: 'application/octet-stream', buffer: Buffer.from('sealed backup fixture') });
    await page.locator('#rs-preview button').waitFor();
    page.once('dialog', dialog => dialog.dismiss());
    await page.locator('#rs-preview button').click();
    assert.deepEqual(starts, [], engine + ': cancelled confirmation sends nothing');
    page.on('dialog', dialog => dialog.accept());
    for (const failure of failures) {
      await page.locator('#rs-pw').fill(failure.passphrase);
      if (previews === 1) assert.equal(await page.locator('#rs-preview button').count(), 0, engine + ': changing the passphrase invalidates confirmation');
      await page.locator('#rs-file').setInputFiles({ name: 'panel.hpb', mimeType: 'application/octet-stream', buffer: Buffer.from('sealed backup fixture') });
      await page.locator('#rs-preview button').waitFor();
      await page.locator('#rs-preview button').click();
      await page.waitForFunction(() => !document.querySelector('#rs-preview button').disabled);
      const actual = await page.locator('#bk-msg').textContent();
      if (failure.code) assert.ok(actual.startsWith('LOC:' + failure.code + ':'), engine + ': controlled restore refusal: ' + actual);
      else assert.equal(actual, 'Could not start: error');
      assert.equal(await page.locator('#bk-msg img').count(), 0);
      assert.equal(await page.evaluate(() => typeof window.__restoreOwned), 'undefined');
    }
    assert.equal(previews, failures.length + 1);
    assert.deepEqual(starts, failures.map(failure => failure.passphrase));
    assert.deepEqual(polls, [], engine + ': preflight refusals never poll or apply a restore');
  }
});

browserTest('Install power-safety alerts localize repair and acknowledgement states', async (t) => {
  const source = await readFile(powerAsset, 'utf8');
  const strings = {
    'runtime.power_safety.repair.applying': 'LOC applying',
    'runtime.power_safety.repair.partial': 'LOC partial',
    'runtime.power_safety.button.hide': 'LOC hide caution',
    'runtime.power_safety.button.hide_title': 'LOC hide title',
    'runtime.power_safety.ack.saving': 'LOC saving',
    'runtime.power_safety.ack.not_hidden': 'LOC not hidden',
  };
  const projection = { locale: 'de', strings, languages: Object.fromEntries(Object.keys(strings).map((key) => [key, 'de'])) };
  const calls = [];
  const server = createServer((request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    if (path === '/power-safety.js') { response.writeHead(200, { 'content-type': 'application/javascript' }); response.end(source); return; }
    if (path === '/api/v1/power-safety/repair') {
      calls.push(path); response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ status: 'partial', message: 'raw partial detail', power_safety: { acknowledge_available: true, acknowledgement_fingerprint: 'fingerprint-1' } })); return;
    }
    if (path === '/api/v1/power-safety/acknowledge') {
      calls.push(path); response.writeHead(409, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ acknowledged: false, message: 'raw acknowledgement detail' })); return;
    }
    response.writeHead(200, { 'content-type': 'text/html' });
    response.end(`<!doctype html><html lang="de"><head>${i18nBridge(projection)}</head><body>
      <div data-power-safety-banner><form action="/api/v1/power-safety/repair" data-power-safety-repair>
      <button type="submit">Repair</button><span class="power-safety-repair-result"></span></form></div>
      <script src="/power-safety.js"></script></body></html>`);
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  let browser;
  t.after(async () => {
    try { await browser?.close(); }
    finally { server.closeAllConnections?.(); await new Promise((done) => server.close(done)); }
  });
  browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  await page.goto(`http://127.0.0.1:${server.address().port}/`, { waitUntil: 'domcontentloaded' });
  await page.click('button[type="submit"]');
  await page.waitForFunction(() => document.querySelector('.power-safety-acknowledge-result')?.textContent === 'LOC partial');
  assert.equal(await page.locator('button[type="submit"]').textContent(), 'LOC hide caution');
  assert.equal(await page.locator('button[type="submit"]').getAttribute('title'), 'LOC hide title');
  await page.click('button[type="submit"]');
  await page.waitForFunction(() => document.querySelector('.power-safety-acknowledge-result')?.textContent === 'LOC not hidden');
  assert.deepEqual(calls, ['/api/v1/power-safety/repair', '/api/v1/power-safety/acknowledge']);
});

browserTest('Restore preview shows escaped server warnings before confirmation', async (t) => {
  const warnings = [
    'network_adb skipped because this panel cannot apply it',
    '<img src=x onerror="window.restoreWarningExecuted=true"> & "unavailable"',
  ];
  let previews = 0;
  let restores = 0;
  const { page } = await rig(t, {
    noHelper: true,
    html: '<input type="file" id="rs-file" onchange="restorePick(this)"><input id="rs-pw"><div id="rs-preview"></div>',
    route(request, response, url) {
      if (url.pathname !== '/api/v1/restore') return false;
      if (url.searchParams.get('dry_run') === '1') previews += 1;
      else restores += 1;
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({
        ok: true, dry_run: true, panel_id: 'source', config_keys: 2,
        config_skipped: ['network_adb'], config_warnings: warnings,
        companion_files: 0, state_unavailable: false,
      }));
      return true;
    },
  });
  await page.locator('#rs-file').setInputFiles({ name: 'panel.hpb', mimeType: 'application/octet-stream', buffer: Buffer.from('backup') });
  await page.locator('#rs-preview button').waitFor();
  assert.deepEqual(await page.locator('#rs-preview p.note').allTextContents(), warnings.map((warning) => `⚠ ${warning}`));
  assert.equal(await page.locator('#rs-preview img').count(), 0);
  assert.equal(await page.evaluate(() => window.restoreWarningExecuted), undefined);
  assert.equal(await page.locator('#rs-preview button').isEnabled(), true);
  assert.equal(await page.evaluate(() => {
    const preview = document.getElementById('rs-preview');
    const button = preview.querySelector('button');
    return Array.from(preview.querySelectorAll('p.note')).every((warning) =>
      Boolean(warning.compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING));
  }), true);
  assert.equal(previews, 1);
  assert.equal(restores, 0);
});

for (const engine of ['Chromium', 'WebKit']) browserTest(`Release menu shows the full reason beside the menu and submits the chosen tag in ${engine}`, async t => {
  let posted;
  const { page } = await rig(t, { engine, noHelper: true,
    html: '<div class="comprow" data-name="companion"><div class="comppick"><label class="muted">Version <select class="cvsel" onchange="verChanged(\'companion\')"></select></label><a class="cnotes">Notes</a><button class="cinstall" data-root="1" onclick="installSel(\'companion\',this)"></button><span class="muted cvreason" hidden></span></div><p id="comp-msg"></p></div>',
    route(request, response, url) {
      if (url.pathname === '/api/v1/install/versions') {
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify({ versions: [{tag:'too-new',version:'4.0',installable:false,unavailableReason:'above_panel_limit',maxVersion:'2.0'}, {tag:'v2',version:'2.0',installable:true}, {tag:'v1',version:'1.0',installable:true}] })); return true;
      }
      if (url.pathname === '/api/v1/install/component') {
        request.on('data', chunk => { posted = String(chunk); });
        request.on('end', () => { response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify({status:'busy'})); }); return true;
      }
    }
  });
  await page.setViewportSize({ width:390, height:844 });
  await page.addStyleTag({ content: await readFile(new URL('../../app/src/main/assets/info.css', import.meta.url), 'utf8') });
  await page.evaluate(() => loadVersions('companion'));
  await page.locator('.cvsel').selectOption('too-new');
  assert.equal(await page.locator('.cinstall').isDisabled(), true);
  assert.match(await page.locator('.cvreason').innerText(), /limit 2.0/);
  // The closed menu may clip a long label; the reason beside it must be whole and on screen.
  assert.equal(await page.locator('.cvsel').evaluate(node => node.getBoundingClientRect().right <= innerWidth), true);
  assert.equal(await page.locator('.cvreason').evaluate(node => node.scrollWidth <= node.clientWidth && node.getBoundingClientRect().right <= innerWidth), true);
  await page.locator('.cvsel').selectOption('v1');
  assert.equal(await page.locator('.cvreason').isVisible(), false);
  await page.locator('.cinstall').click();
  await page.waitForFunction(() => document.querySelector('#comp-msg').textContent.includes('Another install'));
  assert.equal(new URLSearchParams(posted).get('version'), 'v1');
});

browserTest('Import shows queued work as pending alongside the acknowledged applied count', async (t) => {
  const { page } = await rig(t, {
    route(_request, response, url) {
      if (url.pathname !== '/api/v1/config/import') return false;
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify(url.searchParams.get('dry_run') === '1'
        ? { changes: [{ key: 'kiosk_lock', from: 'false', to: 'true' }], expected_cfg: 'preview' }
        : { status: 'partial', applied: ['friendly_name'], pending: ['kiosk_lock'], skipped: [], warnings: [], errors: [] }));
      return true;
    },
    html: '<div id="cfg-import-result"></div><input id="cfg-import" type="file" onchange="configImport(this)">',
  });
  page.on('dialog', (dialog) => dialog.accept());
  await page.locator('#cfg-import').setInputFiles({ name: 'config.json', mimeType: 'application/json', buffer: Buffer.from('{}') });
  await page.waitForFunction(() => document.querySelector('#cfg-import-result [lang="en"]'));
  assert.match(await page.locator('#cfg-import-result').textContent(), /applied 1, skipped 0/);
  assert.equal(await page.locator('#cfg-import-result [lang="en"]').textContent(), 'pending:\n  kiosk_lock');
});
