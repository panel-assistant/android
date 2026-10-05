import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium } from 'playwright-core';

const asset = fileURLToPath(new URL('../../app/src/main/assets/profiles.js', import.meta.url));
const editorBundle = fileURLToPath(new URL('../../app/src/main/assets/vendor/profile-editor/codemirror.js', import.meta.url));
const contracts = fileURLToPath(new URL('../../app/src/main/kotlin/io/panelassistant/android/device/profile/ProfileContracts.kt', import.meta.url));
const englishCatalogue = fileURLToPath(new URL('../../app/src/main/assets/i18n/en.json', import.meta.url));
const chrome = process.env.CHROME || '/usr/bin/chromium';
const browserTest = existsSync(chrome) ? test : test.skip;

function objectFreezeBody(source, name) {
  const marker = `var ${name} = Object.freeze({`;
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `${name} declaration is missing`);
  let depth = 1;
  let quote = '';
  let escaped = false;
  const bodyStart = start + marker.length;
  for (let index = bodyStart; index < source.length; index += 1) {
    const char = source[index];
    if (quote) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === quote) quote = '';
      continue;
    }
    if (char === '"' || char === "'") quote = char;
    else if (char === '{') depth += 1;
    else if (char === '}' && --depth === 0) return source.slice(bodyStart, index);
  }
  assert.fail(`${name} declaration is unterminated`);
}

function stringMap(source, name) {
  return new Map([...objectFreezeBody(source, name).matchAll(/"([a-z0-9-]+)"\s*:\s*"([a-z0-9._-]+)"/g)]
    .map((match) => [match[1], match[2]]));
}

function paramMap(source) {
  const mapped = new Map();
  for (const match of objectFreezeBody(source, 'PRESENTATION_PARAMS').matchAll(/"([a-z0-9-]+)"\s*:\s*Object\.freeze\(\[([^\]]*)\]\)/g)) {
    mapped.set(match[1], [...match[2].matchAll(/"([a-z0-9_]+)"/g)].map((item) => item[1]).sort());
  }
  return mapped;
}

function json(body, status = 200) {
  return { status, type: 'application/json', body: JSON.stringify(body) };
}

function profile(overrides = {}) {
  return {
    ref: { id: 'sample', revision: 'abcdef0123456789' },
    display_name: '样例 <profile>&"', origin: 'bundled', maturity: 'verified', trusted_provenance: true,
    compatible: true, matches_this_device: true, active: true, selected: true, last_known_good: true,
    shizuku_recommendation: 'recommended', risks: ['root_paths', 'future_risk'], links: [], issues: [],
    ...overrides,
  };
}

function html(translations, withHelper = true, editorProbe = false, locale = 'zh-Hans', realEditor = false) {
  const helper = withHelper ? `<script>window.__calls=[];window.HaI18n={locale:${JSON.stringify(locale)},t:(key,fallback,values)=>{window.__calls.push(key);const c=${JSON.stringify(translations)};if(c.__throw===key)throw new Error('missing review projection');const value=Object.prototype.hasOwnProperty.call(c,key)?c[key]:fallback;return String(value==null?'':value).replace(/\\{([A-Za-z][A-Za-z0-9_]*)\\}/g,(p,n)=>values&&Object.prototype.hasOwnProperty.call(values,n)?String(values[n]):p);}};</script>` : '';
  const editor = editorProbe ? `<script>window.__editorValue='';window.ProfileCodeEditor={create:()=>({getValue:()=>window.__editorValue,setValue:(value)=>{window.__editorValue=value;},setReadOnly:()=>{},setSchema:(fields)=>{window.__schema=fields;},setDiagnostics:()=>{},focus:()=>{}})};</script>` : '';
  const ids = ['profile-select','profile-use-draft','profile-status','profile-shizuku-guidance','profile-new','profile-edit','profile-fork','profile-import','profile-export','profile-validate','profile-compare','savebtn','profile-activate','profile-auto','profile-rollback','profile-delete','profile-draft','profile-modal-cancel','profile-modal-confirm'];
  const controls = ids.map((id) => id === 'profile-select' ? `<select id="${id}"></select>` : id === 'profile-import' ? `<input id="${id}" type="file">` : `<button id="${id}">${id}</button>`).join('');
  return `<!doctype html><html lang="${locale}"><head><meta charset="utf-8"></head><body><div class="wrap"><div class="profile-toolbar">${controls}</div><div class="profile-workspace"><section id="profile-editor"><div class="profile-editor-head"></div></section><section class="profile-inspector"><div class="profile-inspector-head"></div><div class="profile-inspector-body"><div id="profile-editor-meta"></div><div id="profile-badges"></div><div id="profile-links"></div><div id="profile-catalog-issues"></div><div id="profile-issues"></div><div id="profile-diff"></div><div id="profile-report"></div></div></section></div></div><div id="profile-modal" hidden><h2 id="profile-modal-title"></h2><pre id="profile-modal-detail"></pre></div>${helper}${editor}${realEditor ? '<script src="/codemirror.js"></script>' : ''}<script src="/profiles.js"></script></body></html>`;
}

async function rig(t, { translations = {}, withHelper = true, editorProbe = false, locale = 'zh-Hans', realEditor = false, profiles = [profile()], status = {}, report, schema, yaml = 'schema: 1\n', route } = {}) {
  const source = await readFile(asset, 'utf8');
  const bundle = realEditor ? await readFile(editorBundle, 'utf8') : '';
  const requests = [];
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://panel.test');
    requests.push({ method: request.method, path: url.pathname, headers: { ...request.headers } });
    let result;
    if (url.pathname === '/') result = { type: 'text/html', body: html(translations, withHelper, editorProbe, locale, realEditor) };
    else if (url.pathname === '/profiles.js') result = { type: 'application/javascript', body: source };
    else if (url.pathname === '/codemirror.js') result = { type: 'application/javascript', body: bundle };
    else if (url.pathname === '/api/v1/profiles/schema') result = json(schema || { max_bytes: 131072, fields: [] });
    else if (url.pathname === '/api/v1/profiles/report') result = json(report || { items: [] });
    else if (url.pathname === '/api/v1/profiles') result = json({ catalog_revision: 7, profiles, status });
    else if (/\/api\/v1\/profiles\/[^/]+\/revisions\//.test(url.pathname)) result = { type: 'application/yaml', body: yaml };
    else if (route) result = await route(url, request);
    if (!result) result = json({});
    response.writeHead(result.status || 200, { 'content-type': `${result.type}; charset=utf-8`, ...result.headers });
    response.end(result.body);
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  let browser;
  t.after(async () => {
    if (browser) await browser.close().catch(() => {});
    // A failed browser assertion must not strand this test on an HTTP keep-alive socket.
    if (server.listening) {
      server.closeAllConnections?.();
      await new Promise((done) => server.close(done));
    }
  });
  browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(5_000);
  await page.goto(`http://127.0.0.1:${server.address().port}/`, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.querySelector('#profile-editor-meta')?.textContent.length > 0);
  return { page, requests };
}

test('Profiles maps the complete live CodeMirror phrase vocabulary to finite catalogue keys', async () => {
  const [source, bundle, catalogue] = await Promise.all([
    readFile(asset, 'utf8'),
    readFile(editorBundle, 'utf8'),
    readFile(englishCatalogue, 'utf8').then(JSON.parse),
  ]);
  // The pinned bundle also contains Folded lines, Unfolded lines and to, but the wrapper installs
  // neither foldKeymap nor direct fold commands, so those announcement phrases are unreachable.
  const expected = [
    'Find', 'Replace', 'next', 'previous', 'all', 'match case', 'regexp', 'by word', 'replace',
    'replace all', 'replaced match on line $', 'replaced $ matches', 'close', 'Go to line', 'go',
    'Control character', 'folded code', 'Fold line', 'Unfold line', 'unfold',
    'Diagnostics', 'No diagnostics', 'Selection deleted', 'Completions', 'current match', 'on line',
  ];
  const body = source.substring(source.indexOf('function editorPhrases()'), source.indexOf('\n  }', source.indexOf('function editorPhrases()')) + 4);
  const bindings = [...body.matchAll(/"([^"]+)": t\("(profiles\.editor\.codemirror\.[a-z_]+)", "([^"]+)"\)/g)]
    .map((match) => ({ phrase: match[1], key: match[2], fallback: match[3] }));
  assert.deepEqual(bindings.map(({ phrase }) => phrase), expected);
  assert.equal(new Set(bindings.map(({ key }) => key)).size, expected.length, 'each phrase owns one catalogue key');
  for (const { phrase, key, fallback } of bindings) {
    assert.equal(fallback, phrase, `${key} must retain CodeMirror's English lookup identity as its fallback`);
    assert.equal(catalogue.strings[key].text, phrase, `${key} must be authoritative for the phrase identity`);
    assert.ok(bundle.includes(JSON.stringify(phrase)), `${phrase} is not emitted by the pinned bundle`);
  }
});

browserTest('The real bundled editor renders localized search and go-to-line chrome', async (t) => {
  const expected = {
    en: ['Find', 'Replace', 'next', 'previous', 'all', 'match case', 'regexp', 'by word', 'replace', 'replace all', 'close', 'Go to line', 'go'],
    de: ['Suchen', 'Ersetzen', 'Weiter', 'Zurück', 'Alle', 'Groß-/Kleinschreibung beachten', 'Regulärer Ausdruck', 'Ganze Wörter', 'Ersetzen', 'Alle ersetzen', 'Schließen', 'Zu Zeile springen', 'Springen'],
    'zh-Hans': ['查找', '替换', '下一个', '上一个', '全选', '区分大小写', '正则表达式', '全字匹配', '替换', '全部替换', '关闭', '转到行', '转到'],
  };
  const catalogues = Object.fromEntries(await Promise.all(Object.keys(expected).map(async (locale) => {
    const document = JSON.parse(await readFile(new URL(`../../app/src/main/assets/i18n/${locale}.json`, import.meta.url), 'utf8'));
    return [locale, Object.fromEntries(Object.entries(document.strings).map(([key, record]) => [key, record.text]))];
  })));

  for (const [locale, labels] of Object.entries(expected)) {
    await t.test(locale, async (subtest) => {
      const { page } = await rig(subtest, {
        locale,
        realEditor: true,
        translations: catalogues[locale],
        profiles: [profile({ origin: 'imported' })],
      });
      await page.locator('#profile-edit').click();
      await page.locator('.cm-content').click();
      await page.keyboard.press('Control+f');
      const search = page.locator('.cm-search');
      await search.waitFor();
      assert.equal(await search.locator('input[name="search"]').getAttribute('placeholder'), labels[0]);
      assert.equal(await search.locator('input[name="replace"]').getAttribute('placeholder'), labels[1]);
      assert.deepEqual(await search.locator('button:not([name="close"]), label').allTextContents(), labels.slice(2, 10));
      assert.equal(await search.locator('button[name="close"]').getAttribute('aria-label'), labels[10]);
      await page.keyboard.press('Escape');
      await page.keyboard.press('Control+Alt+g');
      const dialog = page.locator('.cm-dialog');
      await dialog.waitFor();
      assert.equal((await dialog.locator('label').textContent()).trim(), `${labels[11]}:`);
      assert.equal(await dialog.locator('button.cm-button').textContent(), labels[12]);
    });
  }
});

test('Profiles presentation vocabulary, parameter shapes and English catalogue stay exactly aligned', async () => {
  const [source, kotlin, catalogueDocument] = await Promise.all([
    readFile(asset, 'utf8'),
    readFile(contracts, 'utf8'),
    readFile(englishCatalogue, 'utf8').then(JSON.parse),
  ]);
  const presentations = stringMap(source, 'PRESENTATIONS');
  const browserParams = paramMap(source);
  const supportedMatch = kotlin.match(/val SUPPORTED_CODES: Set<String> = setOf\(([\s\S]*?)\n\s*\)\n\s*private val PARAMS_BY_CODE/);
  assert.ok(supportedMatch, 'backend supported-code vocabulary is readable');
  const backendSupported = [...supportedMatch[1].matchAll(/"([a-z0-9-]+)"/g)].map((match) => match[1]).sort();
  // Backup restore presentation belongs to the integration/backup API. The passive-draft TODO
  // message is profile-authored guidance rendered inside raw YAML, never browser UI prose.
  const excluded = backendSupported.filter((code) => code.startsWith('backup-') ||
    code === 'profile-catalog-restore-unavailable' || code === 'draft-todos-recorded-as-limitations');
  const supported = backendSupported.filter((code) => !excluded.includes(code));
  const backendParamSection = kotlin.match(/private val PARAMS_BY_CODE:[\s\S]*?= mapOf\(([\s\S]*?)\n\s*\)\n\s*}/);
  assert.ok(backendParamSection, 'backend parameter vocabulary is readable');
  const backendParams = new Map();
  for (const match of backendParamSection[1].matchAll(/"([a-z0-9-]+)"\s+to\s+setOf\(([^)]*)\)/g)) {
    backendParams.set(match[1], [...match[2].matchAll(/"([a-z0-9_]+)"/g)].map((item) => item[1]).sort());
  }
  const browserCodes = [...presentations.keys()].sort();
  assert.deepEqual(browserCodes, supported, 'the browser consumes every and only backend-owned presentation code');
  const strings = catalogueDocument.strings;
  for (const code of supported) {
    const browserKey = presentations.get(code);
    assert.ok(strings[browserKey], `${code} maps to an English catalogue record`);
    const catalogueParams = strings[browserKey].placeholders.map((placeholder) => placeholder.slice(1, -1)).sort();
    assert.deepEqual(browserParams.get(code) || [], backendParams.get(code) || [], `${code} has the backend parameter shape`);
    assert.deepEqual(catalogueParams, backendParams.get(code) || [], `${code} has the catalogue parameter shape`);
  }
  assert.deepEqual([...browserParams.keys()].sort(), [...backendParams.keys()].filter((code) => supported.includes(code)).sort(),
    'parameterized HTML-visible code sets are exact');
  assert.ok(excluded.length > 0 && excluded.every((code) => !presentations.has(code)),
    'non-ProfileRoutes backup vocabulary remains outside the browser bundle');
  const presentationCatalogueKeys = Object.keys(strings).filter((key) =>
    key.startsWith('profiles.result.') || (key.startsWith('profiles.issue.') && !['profiles.issue.line', 'profiles.issue.line_column'].includes(key))
  ).sort();
  assert.deepEqual([...presentations.values()].sort(), presentationCatalogueKeys,
    'the catalogue has no orphaned or missing presentation records');
});

browserTest('Profiles renders every backend-owned browser presentation through its exact closed contract', async (t) => {
  const source = await readFile(asset, 'utf8');
  const presentations = stringMap(source, 'PRESENTATIONS');
  const paramsByCode = paramMap(source);
  const translations = {};
  const issues = [];
  const expected = [];
  for (const [code, key] of presentations) {
    const names = paramsByCode.get(code) || [];
    const params = Object.fromEntries(names.map((name) => [name, `<${name}>&`]));
    translations[key] = `localized:${code}${names.map((name) => `|{${name}}`).join('')}`;
    issues.push({ severity: 'warning', message: `compatibility:${code}`, presentation_code: code, presentation_params: params });
    expected.push(`localized:${code}${names.map((name) => `|<${name}>&`).join('')}`);
  }
  const { page } = await rig(t, { translations, status: { issues } });
  assert.deepEqual(
    await page.locator('#profile-catalog-issues .profile-issue span:nth-child(2)').allTextContents(),
    expected,
  );
  assert.equal(await page.locator('#profile-catalog-issues img').count(), 0,
    'presentation parameter text can never create markup');
});

browserTest('Profiles localizes catalogue, badges, issues and reports while preserving opaque values', async (t) => {
  const injection = '<img src=x onerror="window.__injected=1">中文';
  const translations = {
    'profiles.catalog.option.bundled_active': '{name} · 内置 · {revision} · 已启用',
    'profiles.origin.bundled': '内置', 'profiles.maturity.verified_trusted': '✓ 已验证',
    'profiles.state.active': injection, 'profiles.state.last_known_good': '上次正常',
    'profiles.shizuku.recommended': 'Shizuku：建议', 'profiles.risk.root_paths': 'root 路径',
    'profiles.report.match_model': '匹配字段：型号', 'profiles.report.status.unknown': '未知',
    'profiles.catalog.issue_default': '目录问题', 'profiles.issue.unknown-value': '未知值：{value}',
    'profiles.status.viewing_revision': '查看不可变版本 {revision}。',
  };
  const catalogIssue = { severity: 'warning', path: 'profiles.<raw>', message: 'Compatibility exact', presentation_code: 'unknown-value', presentation_params: { value: '<field>' } };
  const { page } = await rig(t, {
    translations,
    status: { issues: [catalogIssue] },
    report: { items: [
      { path: 'match.any[].all[].field = model', status: 'observed', value: '<MODEL>&' },
      { path: 'evidence.future_name', status: 'unknown', value: '<RAW>&' },
    ] },
  });
  assert.equal(await page.locator('#profile-select option').textContent(), '样例 <profile>&" · 内置 · abcdef0123 · 已启用');
  assert.deepEqual(await page.locator('#profile-badges .profile-badge').allTextContents(), ['内置', '✓ 已验证', injection, '上次正常', 'Shizuku：建议', 'root 路径', 'future_risk']);
  assert.equal(await page.locator('#profile-badges img').count(), 0);
  assert.equal(await page.evaluate(() => window.__injected), undefined);
  assert.deepEqual(await page.locator('#profile-report .profile-diff-path').allTextContents(), ['匹配字段：型号', 'evidence.future_name']);
  assert.deepEqual(await page.locator('#profile-report .profile-diff-value').allTextContents(), ['<MODEL>&', '未知 · <RAW>&']);
  assert.equal(await page.locator('#profile-catalog-issues .profile-issue-loc').textContent(), 'profiles.<raw>');
  assert.equal(await page.locator('#profile-catalog-issues .profile-issue span').nth(1).textContent(), '未知值：<field>');
});

browserTest('Profiles rejects unknown, malformed and parameter-mismatched issue metadata exactly', async (t) => {
  const cases = [
    { message: 'valid compatibility', presentation_code: 'unknown-value', presentation_params: { value: 'raw' } },
    { message: 'unknown code exact', presentation_code: 'future-code', presentation_params: {} },
    { message: 'missing param exact', presentation_code: 'unknown-value', presentation_params: {} },
    { message: 'extra param exact', presentation_code: 'unknown-value', presentation_params: { value: 'raw', extra: 'x' } },
    { message: 'non-string exact', presentation_code: 'unknown-value', presentation_params: { value: 7 } },
    { message: 'oversized exact', presentation_code: 'unknown-value', presentation_params: { value: 'x'.repeat(513) } },
    { message: 'array exact', presentation_code: 'unknown-value', presentation_params: [] },
    { message: 'numeric code exact', presentation_code: 7, presentation_params: {} },
  ];
  const { page } = await rig(t, {
    translations: { 'profiles.issue.unknown-value': 'localized:{value}' },
    status: { issues: cases.map((item) => ({ severity: 'warning', ...item })) },
  });
  assert.deepEqual(await page.locator('#profile-catalog-issues .profile-issue span:nth-child(2)').allTextContents(), [
    'localized:raw', 'unknown code exact', 'missing param exact', 'extra param exact',
    'non-string exact', 'oversized exact', 'array exact', 'numeric code exact',
  ]);
});

browserTest('Profiles keeps English fallback usable without the helper and unknown presentation metadata exact', async (t) => {
  const compatibility = 'Exact backend <message>&"';
  const { page } = await rig(t, {
    withHelper: false,
    profiles: [profile({ origin: 'Future_ORIGIN', maturity: 'Future_Maturity', risks: ['Future_RISK'] })],
    status: { issues: [{ severity: 'future_level', message: compatibility, presentation_code: 'future-code', presentation_params: {} }] },
    report: { items: [{ path: 'evidence.some_new.path', status: 'future_status', value: 'raw_value' }] },
  });
  assert.equal(await page.locator('#profile-select option').textContent(), '样例 <profile>&" · Future_ORIGIN · abcdef0123 · active');
  assert.deepEqual((await page.locator('#profile-badges .profile-badge').allTextContents()).slice(0, 3), ['Future_ORIGIN', 'Future_Maturity', 'Active']);
  assert.ok((await page.locator('#profile-badges .profile-badge').allTextContents()).includes('Future_RISK'));
  assert.equal(await page.locator('#profile-catalog-issues .profile-issue-loc').textContent(), 'future_level');
  assert.equal(await page.locator('#profile-catalog-issues .profile-issue span').nth(1).textContent(), compatibility);
  assert.equal(await page.locator('#profile-report .profile-diff-path').textContent(), 'evidence.some_new.path');
  assert.equal(await page.locator('#profile-report .profile-diff-value').textContent(), 'future_status · raw_value');
  assert.equal(await page.locator('#profile-source-fallback').getAttribute('aria-label'), 'Profile YAML');
});

browserTest('Profiles falls back per key when the shared helper rejects one projection', async (t) => {
  const { page } = await rig(t, {
    translations: {
      __throw: 'profiles.origin.bundled',
      'profiles.maturity.verified_trusted': '✓ 已验证',
      'profiles.state.active': '已启用',
    },
  });
  assert.deepEqual((await page.locator('#profile-badges .profile-badge').allTextContents()).slice(0, 3), [
    'Bundled', '✓ 已验证', '已启用',
  ]);
});

browserTest('Profiles selects every full option template and maps imported origin to Local', async (t) => {
  const translations = {
    'profiles.catalog.option.bundled': 'B:{name}:{revision}',
    'profiles.catalog.option.local': 'L:{name}:{revision}',
    'profiles.catalog.option.bundled_incompatible': 'BI:{name}:{revision}',
    'profiles.catalog.option.local_incompatible': 'LI:{name}:{revision}',
    'profiles.catalog.option.bundled_active': 'BA:{name}:{revision}',
    'profiles.catalog.option.local_active': 'LA:{name}:{revision}',
    'profiles.catalog.option.bundled_selected': 'BS:{name}:{revision}',
    'profiles.catalog.option.local_selected': 'LS:{name}:{revision}',
    'profiles.origin.local': '本地',
  };
  const variants = [];
  for (const origin of ['bundled', 'imported']) {
    for (const state of ['plain', 'incompatible', 'active', 'selected']) {
      const id = `${origin}-${state}`;
      variants.push(profile({
        ref: { id, revision: `0123456789-${id}` }, display_name: id, origin,
        compatible: state !== 'incompatible', active: state === 'active', selected: state === 'selected',
        last_known_good: false, maturity: '', shizuku_recommendation: 'none', risks: [],
      }));
    }
  }
  const { page } = await rig(t, { translations, profiles: variants });
  assert.deepEqual(await page.locator('#profile-select option').allTextContents(), [
    'B:bundled-plain:0123456789', 'BI:bundled-incompatible:0123456789',
    'BA:bundled-active:0123456789', 'BS:bundled-selected:0123456789',
    'L:imported-plain:0123456789', 'LI:imported-incompatible:0123456789',
    'LA:imported-active:0123456789', 'LS:imported-selected:0123456789',
  ]);
  await page.selectOption('#profile-select', 'imported-active@0123456789-imported-active');
  await page.waitForFunction(() => document.querySelector('#profile-editor-meta')?.textContent.includes('imported-active'));
  assert.equal((await page.locator('#profile-badges .profile-badge').allTextContents())[0], '本地');
});

browserTest('Profiles localizes only closed schema descriptions and preserves descriptor identities', async (t) => {
  const known = { path: 'metadata.maturity', type: 'string', required: true, enum_values: ['draft'], description: 'Author-declared evidence maturity; not a provenance or trust signal.' };
  const unknown = { path: 'future.path', type: 'string', required: false, enum_values: ['raw_token'], description: 'Exact future description <raw>&' };
  const { page } = await rig(t, {
    editorProbe: true,
    translations: { 'profiles.schema.description.metadata_maturity': '作者声明的成熟度。' },
    schema: { max_bytes: 131072, fields: [known, unknown] },
  });
  await page.waitForFunction(() => Array.isArray(window.__schema));
  assert.deepEqual(await page.evaluate(() => window.__schema), [
    { ...known, description: '作者声明的成熟度。' }, unknown,
  ]);
});

browserTest('Profiles schema localization map is the exact closed 13-field projection', async (t) => {
  const schemaKeys = {
    'metadata.maturity': 'profiles.schema.description.metadata_maturity',
    'match.any[].all[].field': 'profiles.schema.description.match_field',
    'match.any[].all[].op': 'profiles.schema.description.match_operator',
    'platform.su_form': 'profiles.schema.description.su_form',
    'hardware.led.mechanism': 'profiles.schema.description.led_mechanism',
    'hardware.led.transfer': 'profiles.schema.description.led_transfer',
    'hardware.screen_off': 'profiles.schema.description.screen_off',
    'identity.model_label_strategy': 'profiles.schema.description.model_label_strategy',
    'provisioning.access.shizuku': 'profiles.schema.description.shizuku_recommendation',
    'provisioning.software.webview.artifact': 'profiles.schema.description.webview_artifact',
    'provisioning.packages[].desired_state': 'profiles.schema.description.package_desired_state',
    'provisioning.packages[].importance': 'profiles.schema.description.package_importance',
    'provisioning.recipes[].id': 'profiles.schema.description.recipe_id',
  };
  const source = await readFile(asset, 'utf8');
  const actual = Object.fromEntries([...objectFreezeBody(source, 'SCHEMA_DESCRIPTION_KEYS')
    .matchAll(/"([^"]+)"\s*:\s*"([^"]+)"/g)].map((match) => [match[1], match[2]]));
  assert.deepEqual(actual, schemaKeys);
  const translations = Object.fromEntries(Object.values(schemaKeys).map((key) => [key, `localized:${key}`]));
  const fields = Object.keys(schemaKeys).map((path) => ({ path, type: 'enum', description: `raw:${path}`, enum_values: ['x'] }));
  fields.push({ path: 'future.enum', type: 'enum', description: 'future exact', enum_values: ['future'] });
  const { page } = await rig(t, { editorProbe: true, translations, schema: { max_bytes: 131072, fields } });
  await page.waitForFunction(() => Array.isArray(window.__schema));
  const projected = await page.evaluate(() => window.__schema);
  assert.deepEqual(projected.slice(0, -1).map((field) => field.description), Object.values(schemaKeys).map((key) => `localized:${key}`));
  assert.deepEqual(projected.at(-1), fields.at(-1));
  assert.deepEqual(fields.map((field) => field.path), projected.map((field) => field.path), 'descriptor identity/order is stable');
});

browserTest('Profiles preserves loaded YAML text byte-for-byte through the editor and export', async (t) => {
  const yaml = 'schema: 1\nmetadata:\n  display_name: "例 <&>"  \n# trailing whitespace follows   \n\n';
  const { page, requests } = await rig(t, { editorProbe: true, yaml });
  await page.waitForFunction((expected) => window.__editorValue === expected, yaml);
  const yamlRequest = requests.find((request) => /\/api\/v1\/profiles\/[^/]+\/revisions\//.test(request.path));
  assert.equal(yamlRequest?.headers.accept, 'application/yaml, text/yaml, text/plain',
    'every YAML fetch explicitly negotiates the plain-text response whose presentation headers are consumed on failure');
  await page.evaluate(() => {
    window.__exportBlob = null;
    window.__downloadName = null;
    URL.createObjectURL = (blob) => { window.__exportBlob = blob; return 'blob:profiles-test'; };
    URL.revokeObjectURL = () => {};
    HTMLAnchorElement.prototype.click = function () { window.__downloadName = this.download; };
  });
  await page.click('#profile-export');
  assert.deepEqual(await page.evaluate(async () => ({
    text: await window.__exportBlob.text(),
    bytes: Array.from(new Uint8Array(await window.__exportBlob.arrayBuffer())),
    name: window.__downloadName,
    type: window.__exportBlob.type,
  })), {
    text: yaml,
    bytes: Array.from(new TextEncoder().encode(yaml)),
    name: 'sample.yaml',
    type: 'application/yaml;charset=utf-8',
  });
});

browserTest('Profiles consumes typed plain-text error headers and preserves malformed compatibility prose', async (t) => {
  let mode = 'valid';
  const { page } = await rig(t, {
    translations: {
      'profiles.result.profile-template-unavailable': '配置模板不可用。',
      'profiles.error.create_template': '无法创建模板：{error}',
    },
    route: async (url) => {
      if (url.pathname !== '/api/v1/profiles/template') return json({});
      return {
        status: 503,
        type: 'text/plain',
        body: mode === 'valid' ? 'Exact typed compatibility' : `Exact ${mode} header compatibility`,
        headers: {
          'X-Profile-Presentation-Code': mode === 'unknown' ? 'future-template-code' : 'profile-template-unavailable',
          'X-Profile-Presentation-Params': mode === 'invalid-json' ? '{' : mode === 'mismatch' ? '{"extra":"x"}' : '{}',
        },
      };
    },
  });
  await page.click('#profile-new');
  await page.waitForFunction(() => document.querySelector('#profile-status')?.textContent === '无法创建模板：配置模板不可用。');
  for (const next of ['invalid-json', 'unknown', 'mismatch']) {
    mode = next;
    await page.click('#profile-new');
    await page.waitForFunction((expected) => document.querySelector('#profile-status')?.textContent === expected,
      `无法创建模板：Exact ${next} header compatibility`);
  }
});

browserTest('Profiles consumes known result presentation codes and preserves malformed fallback', async (t) => {
  let malformed = false;
  const { page } = await rig(t, {
    translations: { 'profiles.result.invalid-json': '请求正文不是有效 JSON。' },
    route: async (url) => {
      if (url.pathname === '/api/v1/profiles/probe') return json(malformed
        ? { message: 'Exact malformed compatibility', presentation_code: 'invalid-json' }
        : { message: 'Exact compatibility', presentation_code: 'invalid-json', presentation_params: {} }, 400);
      return json({});
    },
  });
  await page.click('#profile-edit');
  await page.locator('#profile-source-fallback').fill('schema: broken');
  await page.click('#profile-validate');
  await page.waitForFunction(() => document.querySelector('#profile-status')?.textContent.includes('请求正文不是有效 JSON。'));
  malformed = true;
  await page.locator('#profile-source-fallback').fill('schema: still-broken');
  await page.click('#profile-validate');
  await page.waitForFunction(() => document.querySelector('#profile-status')?.textContent.includes('Exact malformed compatibility'));
});

browserTest('Profiles localizes editor, validation, comparison and activation modal workflows', async (t) => {
  const translations = {
    'profiles.status.new_unsaved': '新的未保存配置。',
    'profiles.status.yaml_changed_issue': 'YAML 已更改：{unused}',
    'profiles.status.yaml_changed': 'YAML 已更改。请验证。',
    'profiles.status.valid_hash': '配置有效 · sha256:{sha256}',
    'profiles.modal.compare_title': '<比较活动配置>',
    'profiles.compare.unset': '（未设置）',
    'profiles.modal.activate_title': '启用配置？',
    'profiles.modal.profile_line': '配置：{profile}',
    'profiles.modal.revision_line': '版本：sha256:{sha256}',
    'profiles.modal.risks_line': '风险：{risks}',
    'profiles.risk.root_paths': 'root 路径',
    'profiles.modal.restart_warning': '面板服务将重启。',
    'profiles.modal.hardened_body': 'Hardened 模式需要面板批准。',
    'profiles.action.confirm_restart': '确认并重启',
    'profiles.modal.hardened_title': '需要面板批准',
  };
  const { page } = await rig(t, {
    translations,
    profiles: [profile({ active: false, selected: true, last_known_good: false })],
    route: async (url) => {
      if (url.pathname === '/api/v1/profiles/template') return { type: 'application/yaml', body: 'schema: 1\nname: new\n' };
      if (url.pathname === '/api/v1/profiles/probe') return json({ compatible: true, content_sha256: '1234567890abcdef', summary: profile(), issues: [], diff_from_active: [{ path: 'name', before: null, after: '<新>&' }], report: { items: [] } });
      return json({});
    },
  });
  await page.click('#profile-new');
  await page.waitForFunction(() => document.querySelector('#profile-status')?.textContent === '新的未保存配置。');
  await page.locator('#profile-source-fallback').fill('schema: 1\nname: changed\n');
  assert.equal(await page.locator('#profile-status').textContent(), 'YAML 已更改。请验证。');
  await page.click('#profile-compare');
  await page.waitForFunction(() => document.querySelector('#profile-modal')?.hidden === false);
  assert.equal(await page.locator('#profile-modal-title').textContent(), '<比较活动配置>');
  assert.equal(await page.locator('#profile-modal-title img').count(), 0);
  assert.match(await page.locator('#profile-modal-detail').textContent(), /（未设置）/);
  await page.click('#profile-modal-cancel');
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.querySelector('#profile-status')?.textContent.includes('abcdef012345'));
  await page.click('#profile-activate');
  assert.equal(await page.locator('#profile-modal-title').textContent(), '启用配置？');
  assert.match(await page.locator('#profile-modal-detail').textContent(), /风险：root 路径.*future_risk/);
  assert.match(await page.locator('#profile-modal-detail').textContent(), /Hardened 模式需要面板批准。/);
  assert.equal(await page.locator('#profile-modal-confirm').textContent(), '确认并重启');
  assert.equal(await page.locator('#profile-modal-confirm').getAttribute('title'), '需要面板批准');
});
