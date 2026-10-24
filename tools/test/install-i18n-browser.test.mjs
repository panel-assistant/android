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
  let browser;
  t.after(async () => {
    try { await browser?.close(); }
    finally { server.closeAllConnections?.(); await new Promise((done) => server.close(done)); }
  });
  browser = await (options.engine === 'WebKit' ? webkit.launch({ headless: true }) : chromium.launch({ executablePath: chrome, headless: true }));
  const page = await browser.newPage();
  await page.goto(`http://127.0.0.1:${server.address().port}/${options.query || ''}`, { waitUntil: 'domcontentloaded' });
  return { page, presentations, params };
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
    html: '<div class="comprow" data-name="companion"><span class="cver">1.0</span><select class="cchan"><option>stable</option></select><div class="cvsel choice-list"></div><a class="cnotes">Notes</a><button class="cinstall" data-root="1"></button><a class="cdl">Download</a></div>',
    route(request, response, url) {
      if (url.pathname !== '/api/v1/install/versions') return false;
      response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify({ versions })); return true;
    },
  });
  await page.evaluate(() => window.loadVersions('companion'));
  assert.deepEqual(await page.locator('.cvsel span').allTextContents(), [
    '4.0 (not supported by this panel; limit 2.0)', '2.0', '1.0 (no compatible download)', '0.9 (no compatible download)',
  ]);
  assert.equal(await page.locator('.cvsel input:checked').inputValue(), 'v2');
  assert.equal(await page.locator('.cinstall').isDisabled(), false);
  assert.equal(await page.locator('.cdl').isVisible(), true);
  for (const tag of ['v4', 'v1', 'legacy']) {
    await page.locator('.cvsel input').evaluateAll((inputs, tag) => { for (const input of inputs) input.checked = input.value === tag; }, tag);
    await page.evaluate(() => window.verChanged('companion'));
    assert.equal(await page.locator('.cinstall').isDisabled(), true, tag);
    assert.equal(await page.locator('.cdl').isVisible(), false, tag);
  }
  await page.locator('.cvsel input[value="v2"]').check();
  await page.evaluate(() => window.verChanged('companion'));
  assert.equal(await page.locator('.cinstall').isDisabled(), false);
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
    html: '<div class="comprow" data-name="companion" data-installed-version="0.9.9"><span class="cver">0.9.9 (1136)</span><div class="cvsel choice-list"></div></div>',
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
  assert.equal(await page.locator('.cvsel input:checked').inputValue(), 'v0.9.9');
  assert.equal(await page.locator('.cver').textContent(), '0.9.9 (1136)');
});

browserTest('Version picker uses every shipped locale for all unavailable reasons', async (t) => {
  for (const locale of ['de', 'es', 'fr', 'it', 'nl', 'pl', 'uk', 'cs', 'pt-BR', 'zh-Hans']) {
    const projection = await realCatalogueProjection(locale, ['install.']);
    const { page } = await rig(t, {
      projection,
      html: '<div class="comprow" data-name="companion"><select class="cchan"><option>stable</option></select><div class="cvsel choice-list"></div></div>',
      route(request, response, url) {
        if (url.pathname !== '/api/v1/install/versions') return false;
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify({ versions: ['no_matching_asset', 'above_panel_limit'].map((reason) => ({ tag: reason, version: '4.0', installable: false, unavailableReason: reason, maxVersion: '2.0' })) }));
        return true;
      },
    });
    await page.evaluate(() => window.loadVersions('companion'));
    assert.deepEqual(await page.locator('.cvsel span').allTextContents(), ['no_matching_asset', 'above_panel_limit'].map((reason) => {
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
    'Update Panel Assistant: 1 to 2 at https://example.invalid/release',
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

for (const engine of ['Chromium', 'WebKit']) browserTest(`Release radio labels wrap and submit the chosen tag in ${engine}`, async t => {
  let posted;
  const { page } = await rig(t, { engine, noHelper: true,
    html: '<div class="comprow" data-name="companion"><div class="cvsel choice-list" onchange="verChanged(\'companion\')"></div><a class="cnotes">Notes</a><button class="cinstall" data-root="1" onclick="installSel(\'companion\',this)"></button><p id="comp-msg"></p></div>',
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
  await page.locator('.cvsel input[value="too-new"]+span').click();
  assert.equal(await page.locator('.cinstall').isDisabled(), true);
  assert.match(await page.locator('.cvsel input:checked+span').innerText(), /limit 2.0/);
  assert.deepEqual(await page.locator('.cvsel span').evaluateAll(nodes => nodes.map(node => node.scrollWidth <= node.clientWidth)), [true,true,true]);
  await page.locator('.cvsel input[value="v2"]').focus();
  await page.keyboard.press('Space');
  await page.keyboard.press('ArrowDown');
  assert.equal(await page.locator('.cvsel input:checked').inputValue(), 'v1');
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
