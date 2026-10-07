import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium } from 'playwright-core';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';

const asset = fileURLToPath(new URL('../../app/src/main/assets/install.js', import.meta.url));
const powerAsset = fileURLToPath(new URL('../../app/src/main/assets/power-safety.js', import.meta.url));
const catalogueDir = fileURLToPath(new URL('../../app/src/main/assets/i18n/', import.meta.url));
const chrome = process.env.CHROME || '/usr/bin/chromium';
const browserTest = existsSync(chrome) ? test : test.skip;

function objectLiteral(source, name) {
  const marker = `var ${name} = Object.freeze(`;
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `${name} declaration is missing`);
  const bodyStart = start + marker.length;
  let depth = 0, quote = '', escaped = false;
  for (let index = bodyStart; index < source.length; index += 1) {
    const char = source[index];
    if (quote) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === quote) quote = '';
      continue;
    }
    if (char === '"' || char === "'") quote = char;
    else if (char === '{' || char === '[') depth += 1;
    else if (char === '}' || char === ']') depth -= 1;
    else if (char === ')' && depth === 0) return source.slice(bodyStart, index);
  }
  assert.fail(`${name} declaration is unterminated`);
}

function frozenObject(source, name) {
  return Function(`"use strict";return (${objectLiteral(source, name)});`)();
}

function digest(value) {
  return createHash('sha256').update(JSON.stringify(value)).digest('hex');
}

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

test('Install browser copies the exact frozen v3 presentation and direct-token tables', async () => {
  const source = await readFile(asset, 'utf8');
  const presentations = frozenObject(source, 'PRESENTATIONS');
  assert.equal(Object.keys(presentations).length, 114);
  assert.equal(digest(presentations), 'a03ea51ae259d4813b724ce246f7e70941eb575f54d5015bcc9a4ebf5c4828bc');
  assert.equal(digest(frozenObject(source, 'COMPONENT_STATUS')), '73563afe880f7bccf8b8c31d582a29405b8a498724a97cda7b54686a56f9530e');
  assert.equal(digest(frozenObject(source, 'APK_STATUS')), '2849cf9486f18979fe79465df4c9c76e11c558ef03ae3e98faa1f013b34f9cd8');
  assert.equal(digest(frozenObject(source, 'RESTORE_OUTCOME')), '69226bae3ec367703099c6136b4ff7b7ef264d8ad6efe6a02428bb155e7662b1');
  assert.equal(digest(frozenObject(source, 'RADIO_STATE')), '9e383eec164a6462adc21aceab958e6e023503e21ddcb16a1a4d3671ebfeec9d');
  assert.equal(digest(frozenObject(source, 'CONFIG_IMPORT_STATUS')), 'b28e9c65c69c405fc4576d46a08920a3272b303624d436fa00883ecf8298ea23');
  assert.deepEqual(frozenObject(source, 'COMPONENT_LABEL'), { paneld: 'ha-paneld', companion: 'HA Companion', webview: 'System WebView', apk: 'APK' });
});

const samples = Object.freeze({
  owner: 'paneld', component: 'paneld', channel: 'stable', count: '2', package: 'io.example.app',
  version: '1.2.3', current: '1.0', latest: '1.2', cap: '1.1', current_engine: 'WebView 130',
  target_chromium: '130', from_schema: '8', to_schema: '7', release_url: 'https://example.invalid/release',
  usable_bytes: '1024', total_bytes: '2048', used_percent: '50.0', database_bytes: '512', wal_bytes: '16',
  failure: 'io', operation: 'database-checkpoint', bound_ip: '192.0.2.1', lan_ip: '192.0.2.2',
  attempts: '2', reason_code: 'no-response',
});

async function rig(t, options = {}) {
  const source = await readFile(asset, 'utf8');
  const presentations = frozenObject(source, 'PRESENTATIONS');
  const params = frozenObject(source, 'PARAMS');
  const strings = {};
  const languages = {};
  for (const [code, spec] of Object.entries(presentations)) {
    const keys = typeof spec === 'string' ? [spec] : [spec.one, spec.other];
    for (const key of keys) {
      strings[key] = `LOC:${code}:${Object.keys(samples).map((name) => `{${name}}`).join('|')}`;
      languages[key] = 'zh-Hans';
    }
  }
  Object.assign(strings, options.extraStrings || {});
  Object.keys(options.extraStrings || {}).forEach((key) => { languages[key] = 'zh-Hans'; });
  if (options.untranslated) delete languages[typeof presentations[options.untranslated] === 'string' ? presentations[options.untranslated] : presentations[options.untranslated].other];
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
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); server.closeAllConnections?.(); await new Promise((done) => server.close(done)); });
  await page.goto(`http://127.0.0.1:${server.address().port}/${options.query || ''}`, { waitUntil: 'domcontentloaded' });
  return { page, presentations, params };
}

browserTest('Panel version picker displays PA channel without a local selector', async (t) => {
  let channel = 'prerelease';
  let requestedChannel;
  const { page } = await rig(t, {
    noHelper: true,
    html: '<div class="comprow" data-name="paneld"><span class="cpa-channel">—</span><select class="cvsel"></select></div>',
    route(request, response, url) {
      if (url.pathname !== '/api/v1/install/versions') return false;
      requestedChannel = url.searchParams.get('channel');
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ channel, versions: [] })); return true;
    },
  });
  await page.evaluate(() => window.loadVersions('paneld'));
  assert.equal(requestedChannel, null);
  assert.equal(await page.locator('.cchan').count(), 0);
  assert.equal(await page.locator('.cpa-channel').textContent(), 'Prerelease');
  channel = 'stable';
  await page.evaluate(() => window.loadVersions('paneld'));
  assert.equal(await page.locator('.cpa-channel').textContent(), 'Stable');
  channel = null;
  await page.evaluate(() => window.loadVersions('paneld'));
  assert.equal(await page.locator('.cpa-channel').textContent(), '—');
});

browserTest('Version picker explains unavailable releases and keeps permitted choices usable', async (t) => {
  const versions = [
    { tag: 'v4', version: '4.0', installable: false, unavailableReason: 'above_panel_limit', maxVersion: '2.0' },
    { tag: 'v3', version: '3.0', installable: false, unavailableReason: 'older_app_id' },
    { tag: 'v2', version: '2.0', installable: true, action: 'Upgrade', apk: 'https://github.com/example/app/releases/download/v2/app.apk' },
    { tag: 'v1', version: '1.0', installable: false, unavailableReason: 'no_matching_asset' },
    { tag: 'legacy', version: '0.9', installable: false },
  ];
  const { page } = await rig(t, {
    noHelper: true,
    html: '<div class="comprow" data-name="paneld"><span class="cver">1.0</span><select class="cchan"><option>stable</option></select><select class="cvsel"></select><a class="cnotes"></a><button class="cinstall" data-root="1"></button><a class="cdl">Download</a></div>',
    route(request, response, url) {
      if (url.pathname !== '/api/v1/install/versions') return false;
      response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify({ versions })); return true;
    },
  });
  await page.evaluate(() => window.loadVersions('paneld'));
  assert.deepEqual(await page.locator('.cvsel option').allTextContents(), [
    '4.0 (not supported by this panel; limit 2.0)', '3.0 (older app, cannot replace this one)', '2.0', '1.0 (no compatible download)', '0.9 (no compatible download)',
  ]);
  assert.equal(await page.locator('.cvsel').inputValue(), 'v2');
  assert.equal(await page.locator('.cinstall').isDisabled(), false);
  assert.equal(await page.locator('.cdl').isVisible(), true);
  for (const tag of ['v4', 'v3', 'v1', 'legacy']) {
    await page.selectOption('.cvsel', tag);
    await page.evaluate(() => window.verChanged('paneld'));
    assert.equal(await page.locator('.cinstall').isDisabled(), true, tag);
    assert.equal(await page.locator('.cdl').isVisible(), false, tag);
  }
  await page.selectOption('.cvsel', 'v2');
  await page.evaluate(() => window.verChanged('paneld'));
  assert.equal(await page.locator('.cinstall').isDisabled(), false);
});

browserTest('Version picker retains installed release fallback with a formatted version label', async (t) => {
  const { page } = await rig(t, {
    noHelper: true,
    html: '<div class="comprow" data-name="paneld" data-installed-version="0.9.9"><span class="cver">0.9.9 (1136)</span><select class="cvsel"></select></div>',
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
  await page.evaluate(() => window.loadVersions('paneld'));
  assert.equal(await page.locator('.cvsel').inputValue(), 'v0.9.9');
  assert.equal(await page.locator('.cver').textContent(), '0.9.9 (1136)');
});

browserTest('Version picker uses every shipped locale for all unavailable reasons', async (t) => {
  for (const locale of ['de', 'es', 'fr', 'it', 'nl', 'pl', 'uk', 'cs', 'pt-BR', 'zh-Hans']) {
    const projection = await realCatalogueProjection(locale, ['install.']);
    const { page } = await rig(t, {
      projection,
      html: '<div class="comprow" data-name="paneld"><select class="cchan"><option>stable</option></select><select class="cvsel"></select></div>',
      route(request, response, url) {
        if (url.pathname !== '/api/v1/install/versions') return false;
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify({ versions: ['no_matching_asset', 'older_app_id', 'above_panel_limit'].map((reason) => ({ tag: reason, version: '4.0', installable: false, unavailableReason: reason, maxVersion: '2.0' })) }));
        return true;
      },
    });
    await page.evaluate(() => window.loadVersions('paneld'));
    assert.deepEqual(await page.locator('.cvsel option').allTextContents(), ['no_matching_asset', 'older_app_id', 'above_panel_limit'].map((reason) => {
      const key = `install.progress.${reason}`;
      assert.equal(projection.languages[key], locale, `${locale}/${key}: reviewed translation required`);
      return '4.0 ' + projection.strings[key].replace('{version}', '2.0');
    }));
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

browserTest('Install accepts every frozen presentation code with its exact parameter shape', async (t) => {
  const { page, presentations, params } = await rig(t);
  const cases = Object.keys(presentations).map((code) => {
    const rule = params[code] || {};
    return { code, params: Object.fromEntries([...(rule.required || []), ...(rule.optional || [])].map((name) => [name, samples[name]])) };
  });
  const outcomes = await page.evaluate((items) => items.map((envelope) => window.HaPaneldInstallPresentation.present(envelope, `RAW:${envelope.code}`)), cases);
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
    'Update ha-paneld: 1 to 2 at https://example.invalid/release',
    'Update HA Companion: 1 to 2 at https://example.invalid/release',
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
  await page.waitForFunction(() => document.querySelectorAll('#audit-out .setup').length === 2);
  const rows = await page.locator('#audit-out .setup').evaluateAll((nodes) => nodes.map((node) => ({ text: node.textContent, lang: node.getAttribute('lang'), html: node.innerHTML })));
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
  await page.waitForFunction(() => document.querySelectorAll('#audit-out .setup').length === 1);
  const rendered = await page.locator('#audit-out .setup').evaluate((node) => ({
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
  await mismatch.page.waitForFunction(() => document.querySelectorAll('#audit-out .setup').length === 1);
  assert.equal(await mismatch.page.locator('#audit-out .setup').getAttribute('lang'), 'en');
  assert.equal(await mismatch.page.locator('#audit-out .setup').innerHTML(), '<i>legacy power warning</i>');
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

browserTest('Install protected-form success keeps the supported explicit language in its card redirect', async (t) => {
  const route = (_request, response, url) => {
    if (url.pathname !== '/api/v1/display/density') return false;
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ ok: true, message: 'Applied.' })); return true;
  };
  for (const locale of ['zh-Hans', 'cs', 'pt-BR']) {
    const { page } = await rig(t, {
      route,
      query: `?lang=${locale}`,
      html: '<form action="/api/v1/display/density"><button id="density-submit" type="submit">Apply</button></form>',
    });
    await page.click('#density-submit');
    await page.waitForURL((url) => url.pathname === '/install' && url.searchParams.get('lang') === locale && url.hash === '#cfg-display', { timeout: 3_000 });
    assert.equal(new URL(page.url()).searchParams.get('lang'), locale);
  }
});

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
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); server.closeAllConnections?.(); await new Promise((done) => server.close(done)); });
  await page.goto(`http://127.0.0.1:${server.address().port}/`, { waitUntil: 'domcontentloaded' });
  await page.click('button[type="submit"]');
  await page.waitForFunction(() => document.querySelector('.power-safety-acknowledge-result')?.textContent === 'LOC partial');
  assert.equal(await page.locator('button[type="submit"]').textContent(), 'LOC hide caution');
  assert.equal(await page.locator('button[type="submit"]').getAttribute('title'), 'LOC hide title');
  await page.click('button[type="submit"]');
  await page.waitForFunction(() => document.querySelector('.power-safety-acknowledge-result')?.textContent === 'LOC not hidden');
  assert.deepEqual(calls, ['/api/v1/power-safety/repair', '/api/v1/power-safety/acknowledge']);
});
