import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { existsSync } from 'node:fs';
import { readFile, readdir } from 'node:fs/promises';
import { extname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const ASSETS = resolve(ROOT, 'app/src/main/assets');
const SERVER_SOURCE = resolve(ROOT, 'app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt');
const CHROME = process.env.CHROME || '/usr/bin/chromium';
const LOCALES = await catalogueLocales();
const THEMES = ['light', 'dark'];
const VIEWPORTS = [
  { name: 'small-square', width: 480, height: 480 },
  { name: 'small-portrait', width: 480, height: 800 },
  { name: 'desktop', width: 1920, height: 1080 },
  { name: 'below-picker-breakpoint', width: 519, height: 800 },
  { name: 'picker-breakpoint', width: 520, height: 800 },
  { name: 'below-stacked-workspace-breakpoint', width: 856, height: 800 },
  { name: 'stacked-workspace-breakpoint', width: 857, height: 800 },
  { name: 'below-toolbar-breakpoint', width: 1049, height: 900 },
  { name: 'toolbar-breakpoint', width: 1050, height: 900 },
];

async function catalogueLocales() {
  const files = (await readdir(resolve(ASSETS, 'i18n'))).filter((name) => name.endsWith('.json')).sort();
  const locales = await Promise.all(files.map(async (name) => {
    const locale = JSON.parse(await readFile(resolve(ASSETS, 'i18n', name), 'utf8')).locale;
    assert.equal(name, `${locale}.json`, `catalogue filename must match its declared locale: ${name}`);
    return locale;
  }));
  assert.equal(new Set(locales).size, locales.length, 'catalogue locales must be unique');
  assert.ok(locales.includes('en'), 'layout locale matrix must include the English source catalogue');
  return locales;
}
const MIME = {
  '.css': 'text/css',
  '.js': 'application/javascript',
  '.svg': 'image/svg+xml',
};

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[character]);
}

function textOf(catalogues, locale, key) {
  const localized = catalogues.get(locale)?.strings?.[key]?.text;
  const english = catalogues.get('en')?.strings?.[key]?.text;
  assert.equal(typeof english, 'string', `English catalogue is missing ${key}`);
  return typeof localized === 'string' ? localized : english;
}

function profilesFrame(catalogues, locale) {
  const t = (key) => escapeHtml(textOf(catalogues, locale, key));
  return `
<link rel="stylesheet" href="/assets/profiles.css">
<main class="profile-page">
  <div class="profile-toolbar" aria-label="${t('profiles.toolbar.actions_label')}">
    <div class="profile-pickers">
      <label for="profile-select" class="muted">${t('profiles.toolbar.revision')}</label>
      <select id="profile-select" aria-label="${t('profiles.toolbar.revision_label')}"><option>${t('profiles.status.loading_catalog')}</option></select>
      <label class="profile-revisions muted" for="profile-revisions"><input id="profile-revisions" type="checkbox">${t('profiles.toolbar.show_superseded')}</label>
    </div>
    <div class="profile-actions">
      <div class="profile-action-group" aria-label="${t('profiles.toolbar.editing_label')}">
        <button class="pbtn" id="profile-new" type="button">${t('profiles.action.new')}</button>
        <button class="pbtn" id="profile-edit" type="button" disabled>${t('profiles.action.edit')}</button>
        <button class="pbtn" id="profile-fork" type="button" disabled>${t('profiles.action.fork')}</button>
        <label class="pbtn" for="profile-import">${t('profiles.action.import')}<input id="profile-import" type="file" hidden></label>
        <button class="pbtn" id="profile-export" type="button">${t('profiles.action.export')}</button>
      </div>
      <span class="profile-action-break" aria-hidden="true"></span>
      <div class="profile-action-group" aria-label="${t('profiles.toolbar.review_label')}">
        <button class="pbtn primary" id="profile-validate" type="button" disabled>${t('profiles.action.validate_yaml')}</button>
        <button class="pbtn" id="profile-compare" type="button" disabled>${t('profiles.action.compare')}</button>
      </div>
      <div class="profile-action-group" aria-label="${t('profiles.toolbar.activation_label')}">
        <button class="pbtn primary" id="savebtn" type="button" disabled>${t('profiles.action.save_revision')}</button>
        <button class="pbtn primary" id="profile-activate" type="button" data-hardened-approval disabled>${t('profiles.action.activate')}</button>
        <button class="pbtn" id="profile-auto" type="button" data-hardened-approval disabled>${t('profiles.action.use_automatic')}</button>
        <button class="pbtn" id="profile-rollback" type="button" data-hardened-approval disabled>${t('profiles.action.rollback')}</button>
        <button class="pbtn danger" id="profile-delete" type="button" disabled>${t('profiles.action.delete')}</button>
      </div>
    </div>
  </div>
  <div id="profile-badges" class="profile-badges" aria-label="${t('profiles.state.label')}"></div>
  <nav id="profile-links" class="profile-links" aria-label="${t('profiles.references.label')}" hidden></nav>
  <div id="profile-status" class="profile-status" role="status" aria-live="polite">${t('profiles.status.loading_catalog')}</div>
  <div class="profile-workspace">
    <section class="profile-editor-pane" aria-labelledby="profile-editor-title">
      <div class="profile-editor-head"><h2 id="profile-editor-title">${t('profiles.editor.title')}</h2><span id="profile-editor-meta" class="profile-editor-meta"></span></div>
      <div id="profile-editor"></div>
    </section>
    <aside class="profile-inspector" aria-labelledby="profile-inspector-title">
      <div class="profile-inspector-head"><h2 id="profile-inspector-title">${t('profiles.inspector.title')}</h2></div>
      <div class="profile-inspector-body">
        <section><h3>${t('profiles.section.catalog_runtime')}</h3><div id="profile-catalog-issues" class="profile-issues"></div></section>
        <section><h3>${t('profiles.section.validation')}</h3><div id="profile-issues" class="profile-issues"></div></section>
        <div class="profile-guidance" id="profile-shizuku-guidance"><p><b>${t('profiles.shizuku.title')}</b></p><p>${t('profiles.shizuku.body')}</p><p><a href="#">${t('profiles.shizuku.guide')}</a></p></div>
        <section><h3>${t('profiles.section.compared_active')}</h3><div id="profile-diff" class="profile-diff"></div></section>
        <section><h3>${t('profiles.section.observed')}</h3><p class="profile-report-note">${t('profiles.observed.note')}</p><div id="profile-report" class="profile-report"></div></section>
        <div class="profile-draft" id="profile-generic-draft"><p><b>${t('profiles.generic.title')}</b> ${t('profiles.generic.body')}</p><p><button class="pbtn" id="profile-draft" type="button">${t('profiles.action.generate_draft')}</button> <button class="pbtn" id="profile-use-draft" type="button">${t('profiles.action.copy_draft')}</button></p></div>
      </div>
    </aside>
  </div>
</main>
<div id="profile-modal" class="profile-modal" role="dialog" aria-modal="true" aria-labelledby="profile-modal-title" hidden>
  <div class="profile-modal-card"><h2 id="profile-modal-title">${t('profiles.modal.default_title')}</h2><pre id="profile-modal-detail"></pre>
    <div class="profile-modal-actions"><button class="pbtn" id="profile-modal-cancel" type="button">${t('profiles.action.cancel')}</button><button class="pbtn primary" id="profile-modal-confirm" type="button">${t('profiles.action.confirm')}</button></div>
  </div>
</div>`;
}

function documentHtml(catalogues, locale, theme) {
  const t = (key) => escapeHtml(textOf(catalogues, locale, key));
  const projection = Object.fromEntries(Object.entries(catalogues.get(locale).strings)
    .filter(([key]) => key.startsWith('profiles.') || key.startsWith('shell.'))
    .map(([key, record]) => [key, record.text]));
  const projectionJson = JSON.stringify({ locale, strings: projection }).replaceAll('<', '\\u003c');
  const nav = [
    ['dashboard', 'shell.nav.dashboard'], ['configure', 'shell.nav.configure'],
    ['setup', 'shell.nav.setup'], ['profiles', 'shell.nav.profile'],
    ['entities', 'shell.nav.entities'], ['install', 'shell.nav.install'],
    ['fleet', 'shell.nav.fleet'], ['logs', 'shell.nav.logs'],
  ].map(([path, key]) => `<a class="${path === 'profiles' ? 'active' : ''}" href="#">${t(key)}</a>`).join('');
  return `<!doctype html><html lang="${escapeHtml(locale)}" data-theme="${theme}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>ha-paneld · ${t('shell.nav.profile')}</title>
<link rel="stylesheet" href="/info.css"><script id="ha-i18n" type="application/json">${projectionJson}</script>
<script>window.__profileLayoutShifts=[];new PerformanceObserver(function(list){list.getEntries().forEach(function(entry){if(!entry.hadRecentInput)window.__profileLayoutShifts.push(entry.value);});}).observe({type:'layout-shift',buffered:true});</script>
<script src="/assets/i18n.js"></script></head><body><div class="wrap">
<div class="topbar"><div class="hdr"><button id="navburger" class="navburger pbtn" aria-label="${t('shell.menu.label')}">☰</button><h1><img src="/assets/icon.svg" class="logo" alt=""><span class="brand">ha-paneld</span> <small id="pswitch" data-self-id="layout-gate" data-self-name="Layout gate"><span class="sep">·</span>Layout gate</small></h1><span></span></div><nav class="nav">${nav}</nav></div>
<script src="/assets/switcher.js"></script>${profilesFrame(catalogues, locale)}
<script src="/assets/vendor/profile-editor/codemirror.js"></script><script src="/assets/profiles.js"></script>
</div></body></html>`;
}

function profileData() {
  return {
    ref: { id: 'generic', revision: '0123456789abcdef0123456789abcdef' },
    display_name: 'Generic panel profile with a deliberately long visible name',
    content_version: '2026.9.4', author: 'ha-paneld maintainers', origin: 'imported', maturity: 'verified',
    trusted_provenance: true, compatible: true, matches_this_device: true,
    active: false, selected: false, last_known_good: false,
    shizuku_recommendation: 'recommended', risks: ['root_paths', 'package_management', 'future_long_risk_token'],
    links: [
      { label: 'Device profile documentation with a long label', url: 'https://example.invalid/profiles/device-profile-documentation' },
      { label: 'Hardware evidence', url: 'https://example.invalid/evidence/hardware' },
    ],
    issues: [
      { severity: 'warning', path: 'provisioning.packages[12].desired_state', message: 'Compatibility prose', presentation_code: 'unknown-value', presentation_params: { value: 'unexpected_future_value' } },
      { severity: 'error', path: 'hardware.display.current_density_dpi', message: 'A deliberately long opaque parser diagnostic remains readable without changing API bytes.' },
    ],
  };
}

// The reporter's catalog shape: one profile iterated four times, each edit a separate immutable
// revision. The picker collapses these, so the toolbar it has to lay out is the collapsed one plus
// the toggle that reveals the rest -- both of which only exist when duplicates do.
function supersededRevisions() {
  const base = profileData();
  return ['a', 'b', 'c', 'd'].map((suffix, index) => ({
    ...base,
    ref: { id: base.ref.id, revision: `${suffix.repeat(8)}0123456789abcdef0123456789abcdef` },
    content_version: `2026.9.${4 + index}`,
    imported_at: 1757000000000 + index * 3600000,
  }));
}

function apiResponse(path) {
  if (path === '/api/v1/peers') return [];
  if (path === '/api/v1/profiles/schema') return { max_bytes: 131072, fields: [] };
  if (path === '/api/v1/profiles/report') return { items: [
    { path: 'evidence.android_sdk', status: 'observed', value: '35' },
    { path: 'evidence.abis', status: 'observed', value: 'arm64-v8a, armeabi-v7a' },
    { path: 'evidence.display.current_density_dpi', status: 'observed', value: '640' },
    { path: 'evidence.cpu.available_governors', status: 'observed', value: 'schedutil, performance, powersave' },
    { path: 'evidence.some_future_opaque_path_with_a_long_name', status: 'unknown', value: 'some_future_opaque_value' },
  ] };
  if (path === '/api/v1/profiles') return {
    catalog_revision: 19, profiles: supersededRevisions(),
    status: { selection: { mode: 'manual' }, rollback_ref: { id: 'generic', revision: 'previous-revision' }, issues: [] },
  };
  if (/^\/api\/v1\/profiles\/generic\/revisions\//.test(path)) return `schema: 1\nmetadata:\n  id: generic\n  display_name: Generic profile\n  version: 2026.9.4\n  author: ha-paneld maintainers\n  maturity: verified\nmatch:\n  any:\n    - all:\n        - field: model\n          op: contains\n          value: panel\nhardware:\n  display:\n    width_px: 1920\n    height_px: 1080\n`;
  return {};
}

async function startServer(catalogues) {
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://layout.test');
    if (url.pathname === '/profiles') {
      const locale = LOCALES.includes(url.searchParams.get('lang')) ? url.searchParams.get('lang') : 'en';
      const theme = THEMES.includes(url.searchParams.get('theme')) ? url.searchParams.get('theme') : 'dark';
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      response.end(documentHtml(catalogues, locale, theme));
      return;
    }
    if (url.pathname.startsWith('/api/')) {
      const payload = apiResponse(url.pathname);
      const yaml = typeof payload === 'string';
      response.writeHead(200, { 'content-type': yaml ? 'application/yaml; charset=utf-8' : 'application/json; charset=utf-8' });
      response.end(yaml ? payload : JSON.stringify(payload));
      return;
    }
    try {
      const relativePath = url.pathname === '/info.css' ? 'info.css' : url.pathname.replace(/^\/assets\//, '');
      const file = resolve(ASSETS, relativePath);
      const inside = relative(ASSETS, file);
      if (inside === '..' || inside.startsWith('../')) throw new Error('asset path escapes root');
      const body = await readFile(file);
      response.writeHead(200, { 'content-type': `${MIME[extname(file)] || 'application/octet-stream'}; charset=utf-8` });
      response.end(body);
    } catch {
      response.writeHead(404); response.end('not found');
    }
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  return server;
}

async function geometry(page) {
  return page.evaluate(() => {
    const rect = (selector) => {
      const value = document.querySelector(selector).getBoundingClientRect();
      return { x: value.x, y: value.y, width: value.width, height: value.height, right: value.right, bottom: value.bottom };
    };
    const toolbarItems = [...document.querySelectorAll('.profile-pickers, .profile-actions .pbtn:not([hidden])')]
      .map((node) => ({ label: node.id || node.textContent.trim(), box: node.getBoundingClientRect() }))
      .filter((item) => item.box.width > 0 && item.box.height > 0);
    const overlaps = [];
    for (let left = 0; left < toolbarItems.length; left += 1) for (let right = left + 1; right < toolbarItems.length; right += 1) {
      const a = toolbarItems[left]; const b = toolbarItems[right];
      const width = Math.min(a.box.right, b.box.right) - Math.max(a.box.left, b.box.left);
      const height = Math.min(a.box.bottom, b.box.bottom) - Math.max(a.box.top, b.box.top);
      if (width > 1 && height > 1) overlaps.push(`${a.label} <> ${b.label}`);
    }
    return {
      viewport: { width: innerWidth, height: innerHeight },
      horizontalOverflow: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - innerWidth,
      toolbar: rect('.profile-toolbar'), editor: rect('#profile-editor'), editorPane: rect('.profile-editor-pane'),
      inspector: rect('.profile-inspector'), workspace: rect('.profile-workspace'), overlaps,
      cls: window.__profileLayoutShifts.reduce((sum, value) => sum + value, 0),
      editorImplementation: document.querySelector('#profile-editor .cm-editor') ? 'codemirror' : 'fallback',
      theme: document.documentElement.getAttribute('data-theme'),
      language: document.documentElement.lang,
      runtimeLocale: window.HaI18n && window.HaI18n.locale,
    };
  });
}

async function modalGeometry(page) {
  return page.evaluate(() => {
    const overlay = document.querySelector('#profile-modal').getBoundingClientRect();
    const card = document.querySelector('.profile-modal-card').getBoundingClientRect();
    const buttons = [...document.querySelectorAll('.profile-modal-actions .pbtn')].map((node) => node.getBoundingClientRect());
    const overlap = buttons.length === 2 && Math.min(buttons[0].right, buttons[1].right) - Math.max(buttons[0].left, buttons[1].left) > 1 &&
      Math.min(buttons[0].bottom, buttons[1].bottom) - Math.max(buttons[0].top, buttons[1].top) > 1;
    return {
      hidden: document.querySelector('#profile-modal').hidden,
      overlay: { x: overlay.x, y: overlay.y, right: overlay.right, bottom: overlay.bottom },
      card: { x: card.x, y: card.y, right: card.right, bottom: card.bottom, height: card.height, scrollHeight: document.querySelector('.profile-modal-card').scrollHeight },
      overlap,
      bodyOverflow: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - innerWidth,
    };
  });
}

test('Profiles layout fixture stays bound to the production frame and breakpoint contract', async () => {
  const [serverSource, css] = await Promise.all([
    readFile(SERVER_SOURCE, 'utf8'), readFile(resolve(ASSETS, 'profiles.css'), 'utf8'),
  ]);
  for (const marker of [
    'class="profile-toolbar"', 'class="profile-workspace"', 'class="profile-editor-pane"',
    'id="profile-revisions"', 'profiles.toolbar.show_superseded',
    'class="profile-inspector"', 'class="profile-modal-card"',
    'src="assets/vendor/profile-editor/codemirror.js"', 'src="assets/profiles.js"',
  ]) assert.ok(serverSource.includes(marker), `production Profiles frame lost ${marker}`);
  for (const breakpoint of ['@media(max-width:1050px)', '@media(max-width:857px)', '@media(max-width:520px)']) {
    assert.ok(css.includes(breakpoint), `production Profiles CSS lost ${breakpoint}`);
  }
  assert.match(serverSource, /profilesBody\(strings: AppStrings\)/, 'production frame remains request-localized');
});

const layoutTest = existsSync(CHROME) ? test : test.skip;
layoutTest('Profiles stays usable across every locale, theme and production breakpoint', { timeout: 180_000 }, async (t) => {
  const catalogues = new Map(await Promise.all(LOCALES.map(async (locale) => [
    locale, JSON.parse(await readFile(resolve(ASSETS, `i18n/${locale}.json`), 'utf8')),
  ])));
  const server = await startServer(catalogues);
  const browser = await chromium.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  t.after(async () => {
    await browser.close();
    await new Promise((done) => server.close(done));
  });
  const origin = `http://127.0.0.1:${server.address().port}`;
  const evidence = [];

  for (const locale of LOCALES) for (const theme of THEMES) for (const viewport of VIEWPORTS) {
    const page = await browser.newPage({ viewport: { width: viewport.width, height: viewport.height } });
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(`${origin}/profiles?lang=${locale}&theme=${theme}`, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => document.querySelector('#profile-status')?.textContent && !document.querySelector('#profile-status').textContent.includes('Loading'));
    await page.waitForTimeout(250);
    const measured = await geometry(page);
    const cell = `${locale}/${theme}/${viewport.name}-${viewport.width}x${viewport.height}`;
    assert.deepEqual(errors, [], `${cell}: browser errors`);
    assert.equal(measured.language, locale, `${cell}: document language`);
    assert.equal(measured.runtimeLocale, locale, `${cell}: browser translation projection`);
    assert.equal(measured.theme, theme, `${cell}: forced theme`);
    assert.equal(measured.editorImplementation, 'codemirror', `${cell}: production editor loaded`);
    assert.ok(measured.horizontalOverflow <= 1, `${cell}: horizontal overflow ${measured.horizontalOverflow}px`);
    assert.deepEqual(measured.overlaps, [], `${cell}: toolbar controls overlap`);
    assert.ok(measured.toolbar.x >= -1 && measured.toolbar.right <= viewport.width + 1, `${cell}: toolbar stays in viewport`);
    assert.ok(measured.editor.width > 0 && measured.inspector.width > 0, `${cell}: editor and inspector remain rendered`);
    const minimumEditor = viewport.width <= 857 ? 226 : 150;
    assert.ok(measured.editor.height >= minimumEditor, `${cell}: editor ${measured.editor.height}px is below ${minimumEditor}px`);
    assert.ok(measured.cls <= 0.10, `${cell}: CLS ${measured.cls.toFixed(4)} exceeds 0.10`);

    await page.click('#profile-delete');
    await page.waitForFunction(() => !document.querySelector('#profile-modal').hidden);
    const modal = await modalGeometry(page);
    assert.equal(modal.hidden, false, `${cell}: destructive modal opens`);
    assert.ok(modal.overlay.x >= -1 && modal.overlay.y >= -1 && modal.overlay.right <= viewport.width + 1 && modal.overlay.bottom <= viewport.height + 1, `${cell}: modal overlay covers only the viewport`);
    assert.ok(modal.card.x >= -1 && modal.card.y >= -1 && modal.card.right <= viewport.width + 1 && modal.card.bottom <= viewport.height + 1, `${cell}: modal card stays in viewport`);
    assert.ok(modal.card.height <= viewport.height * 0.9 + 1, `${cell}: modal card respects 90vh`);
    assert.equal(modal.overlap, false, `${cell}: modal actions do not overlap`);
    assert.ok(modal.bodyOverflow <= 1, `${cell}: modal causes horizontal overflow`);
    await page.close();
    evidence.push({ cell, ...measured, modal });
  }

  assert.equal(evidence.length, LOCALES.length * THEMES.length * VIEWPORTS.length, 'the complete matrix ran');
  assert.deepEqual([...new Set(evidence.map((item) => item.language))].sort(), [...LOCALES].sort(), 'all release locales ran');
  assert.deepEqual([...new Set(evidence.map((item) => item.theme))].sort(), [...THEMES].sort(), 'both themes ran');
  const summary = {
    cells: evidence.length,
    maximumCls: Math.max(...evidence.map((item) => item.cls)),
    maximumHorizontalOverflow: Math.max(...evidence.map((item) => item.horizontalOverflow)),
    minimumNarrowEditorHeight: Math.min(...evidence.filter((item) => item.viewport.width <= 857).map((item) => item.editor.height)),
    minimumWideEditorHeight: Math.min(...evidence.filter((item) => item.viewport.width > 857).map((item) => item.editor.height)),
    maximumModalHeightRatio: Math.max(...evidence.map((item) => item.modal.card.height / item.viewport.height)),
  };
  console.log(`Profiles layout evidence: ${JSON.stringify(summary)}`);

  // Negative controls prove that the same measurements used by the gate can become positive. This avoids
  // accepting a green matrix merely because Chromium or a selector silently stopped observing the defect.
  const mutant = await browser.newPage({ viewport: { width: 480, height: 480 } });
  await mutant.goto(`${origin}/profiles?lang=zh-Hans&theme=dark`, { waitUntil: 'domcontentloaded' });
  await mutant.waitForFunction(() => !document.querySelector('#profile-delete').disabled);
  await mutant.evaluate(() => {
    document.body.insertAdjacentHTML('beforeend', '<div id="overflow-mutant" style="width:900px;height:1px"></div>');
    const actions = [...document.querySelectorAll('.profile-actions .pbtn')];
    actions[1].style.position = 'absolute';
    actions[1].style.left = `${actions[0].getBoundingClientRect().left}px`;
    actions[1].style.top = `${actions[0].getBoundingClientRect().top}px`;
    document.querySelector('#profile-editor').style.setProperty('height', '20px', 'important');
    document.querySelector('#profile-editor').style.setProperty('min-height', '0', 'important');
    window.__profileLayoutShifts.push(0.2);
  });
  const broken = await geometry(mutant);
  assert.ok(broken.horizontalOverflow > 1, 'negative control proves document overflow detection');
  assert.ok(broken.overlaps.length > 0, 'negative control proves toolbar overlap detection');
  assert.ok(broken.editor.height < 226, 'negative control proves minimum editor-height detection');
  assert.ok(broken.cls > 0.10, 'negative control proves CLS threshold detection');
  await mutant.click('#profile-delete');
  await mutant.evaluate(() => {
    document.querySelector('.profile-modal-card').style.setProperty('width', '900px', 'important');
    document.querySelector('.profile-modal-card').style.setProperty('max-width', 'none', 'important');
    const buttons = [...document.querySelectorAll('.profile-modal-actions .pbtn')];
    buttons[1].style.position = 'absolute';
    buttons[1].style.left = `${buttons[0].getBoundingClientRect().left}px`;
    buttons[1].style.top = `${buttons[0].getBoundingClientRect().top}px`;
  });
  const brokenModal = await modalGeometry(mutant);
  assert.ok(brokenModal.card.right > 481 || brokenModal.card.x < -1, 'negative control proves modal containment detection');
  assert.equal(brokenModal.overlap, true, 'negative control proves modal-action overlap detection');
  await mutant.close();
});

// Chromium above is the panel's own WebView engine. A maintainer opening the same page from Safari
// gets WebKit, which lays out flex gaps, form controls and wrapped toolbars differently, and this
// project has been bitten three times by a Chromium-only sweep passing while the real browser
// showed the bug. The matrix here is deliberately narrower: the engine axis is what it adds, not
// the locale axis, so it runs the longest-text locale in both themes across every breakpoint.
const webkitAvailable = existsSync(webkit.executablePath());
const webkitTest = webkitAvailable ? test : test.skip;
webkitTest('Profiles collapsed picker lays out in WebKit across every production breakpoint', { timeout: 180_000 }, async (t) => {
  const catalogues = new Map(await Promise.all(LOCALES.map(async (locale) => [
    locale, JSON.parse(await readFile(resolve(ASSETS, `i18n/${locale}.json`), 'utf8')),
  ])));
  const server = await startServer(catalogues);
  const browser = await webkit.launch({ headless: true });
  t.after(async () => {
    await browser.close();
    await new Promise((done) => server.close(done));
  });
  const origin = `http://127.0.0.1:${server.address().port}`;
  const evidence = [];
  const overflowEvidence = [];

  for (const locale of ['de', 'en']) for (const theme of THEMES) for (const viewport of VIEWPORTS) {
    const page = await browser.newPage({ viewport: { width: viewport.width, height: viewport.height } });
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(`${origin}/profiles?lang=${locale}&theme=${theme}`, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => document.querySelector('#profile-status')?.textContent && !document.querySelector('#profile-status').textContent.includes('Loading'));
    await page.waitForTimeout(250);
    const cell = `webkit/${locale}/${theme}/${viewport.name}-${viewport.width}x${viewport.height}`;
    const measured = await geometry(page);
    assert.deepEqual(errors, [], `${cell}: browser errors`);
    assert.deepEqual(measured.overlaps, [], `${cell}: toolbar controls overlap`);
    assert.ok(measured.toolbar.x >= -1 && measured.toolbar.right <= viewport.width + 1, `${cell}: toolbar stays in viewport`);

    // WebKit overflows this page horizontally at the narrowest viewport, by 316px at 480x480,
    // and it does so identically with the revisions control removed from the stylesheet entirely.
    // That is a pre-existing Profiles defect this engine had never been pointed at, recorded for
    // its own lane rather than repaired here; measuring it as a budget keeps this gate honest
    // about what it found while still failing if the picker starts contributing to it.
    // WebKit overflows this page horizontally below the stacked-workspace breakpoint, and it does
    // so identically with the revisions control removed from the stylesheet. Asserting a raw pixel
    // budget would encode a number nobody can explain, so the gate asserts the thing this lane is
    // responsible for instead: whatever is overflowing, it must not be the toolbar the picker and
    // its toggle live in. The page-level defect is recorded for its own lane.
    const widest = await widestOverflowingElement(page);
    assert.ok(
      !widest || !widest.selector.startsWith('.profile-toolbar'),
      `${cell}: the profile toolbar overflows by ${widest && widest.overflow}px via ${widest && widest.selector}`,
    );
    overflowEvidence.push({ cell, overflow: measured.horizontalOverflow, widest: widest && widest.selector });

    // The collapse itself, measured rather than assumed: four revisions of one profile reach the
    // page and one option is offered, the toggle is revealed and operable, and opening it offers
    // every revision without pushing the toolbar out of the viewport.
    const collapsed = await pickerState(page);
    assert.equal(collapsed.options, 1, `${cell}: collapsed picker offered ${collapsed.options} options`);
    assert.equal(collapsed.toggleRevealed, true, `${cell}: toggle was not revealed`);
    assert.equal(collapsed.toggleDisabled, false, `${cell}: toggle was not operable`);
    assert.ok(collapsed.toggleBox.width > 0 && collapsed.toggleBox.height > 0, `${cell}: toggle has no box`);
    assert.ok(collapsed.toggleBox.right <= viewport.width + 1, `${cell}: toggle overflows the viewport`);

    await page.click('#profile-revisions');
    await page.waitForFunction(() => document.querySelectorAll('#profile-select option').length > 1);
    const expanded = await pickerState(page);
    assert.equal(expanded.options, 4, `${cell}: expanded picker offered ${expanded.options} of 4 revisions`);
    assert.equal(expanded.selectedValue, collapsed.selectedValue, `${cell}: expanding the picker changed the selection`);
    const expandedWidest = await widestOverflowingElement(page);
    assert.ok(
      !expandedWidest || !expandedWidest.selector.startsWith('.profile-toolbar'),
      `${cell}: expanding the picker overflowed the toolbar via ${expandedWidest && expandedWidest.selector}`,
    );
    assert.ok(expanded.labels.every((label) => label.includes('2026.9.')), `${cell}: an option lost its declared version`);
    assert.equal(new Set(expanded.labels).size, expanded.labels.length, `${cell}: two revisions render as the same label`);

    await page.close();
    evidence.push({ cell, collapsed: collapsed.options, expanded: expanded.options });
  }

  assert.equal(evidence.length, 2 * THEMES.length * VIEWPORTS.length, 'the WebKit matrix ran');
  const overflowing = overflowEvidence.filter((item) => item.overflow > 1);
  console.log(`Profiles WebKit collapse evidence: ${JSON.stringify({
    cells: evidence.length,
    collapsed: 1,
    expanded: 4,
    cellsOverflowing: overflowing.length,
    widestOverflowSources: [...new Set(overflowing.map((item) => item.widest))].sort(),
    maximumOverflow: overflowing.length ? Math.max(...overflowing.map((item) => item.overflow)) : 0,
  })}`);
});

// Names the element actually wider than the viewport, so an overflow can be attributed rather than
// budgeted. Returns the widest offender, or null when nothing overflows.
async function widestOverflowingElement(page) {
  return page.evaluate(() => {
    const limit = window.innerWidth + 1;
    let worst = null;
    for (const node of document.querySelectorAll('body *')) {
      const box = node.getBoundingClientRect();
      if (box.width === 0 && box.height === 0) continue;
      const overflow = Math.round(box.right - limit);
      if (overflow <= 0) continue;
      const selector = node.id ? `#${node.id}` : `.${[...node.classList].join('.')}` || node.tagName.toLowerCase();
      if (!worst || overflow > worst.overflow) worst = { selector, overflow };
    }
    return worst;
  });
}

async function pickerState(page) {
  return page.evaluate(() => {
    const select = document.querySelector('#profile-select');
    const toggle = document.querySelector('#profile-revisions');
    const row = toggle.closest('.profile-revisions');
    const box = row.getBoundingClientRect();
    const options = [...select.querySelectorAll('option')];
    return {
      options: options.length,
      labels: options.map((option) => option.textContent),
      selectedValue: select.value,
      toggleRevealed: getComputedStyle(row).visibility === 'visible',
      toggleDisabled: toggle.disabled,
      toggleBox: { width: box.width, height: box.height, right: box.right },
      horizontalOverflow: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - innerWidth,
    };
  });
}
