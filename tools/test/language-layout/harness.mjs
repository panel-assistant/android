// Shared harness for the language layout gate: every page served from app/src/main/assets, in every
// catalogue locale, light and dark, Chromium and WebKit, at the panel sizes and in the Panel Assistant
// sidebar views. The page modules in ./pages supply the server-rendered frame (mirroring the Kotlin page
// builders), the API data the page scripts render from, and a ready condition. Everything else — the
// shell, the sidebar host, the measurements and the verdict — lives here once.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile, readdir } from 'node:fs/promises';
import { existsSync, readFileSync } from 'node:fs';
import { extname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium, webkit } from 'playwright-core';
import { measureLayout } from './measure.mjs';

export const ROOT = fileURLToPath(new URL('../../..', import.meta.url));
export const ASSETS = resolve(ROOT, 'app/src/main/assets');
export const CHROME = process.env.CHROME || '/usr/bin/chromium';
export const THEMES = ['light', 'dark'];
export const BROWSERS = ['chromium', 'webkit'];

// Panel sizes are the escape hatch on the panel's own screen: only cut-off text (a) and unreachable
// controls (c) block there. The four supported views block on every error. The sidebar views put the
// page in an iframe beside Home Assistant's own chrome, exactly as Panel Assistant proxies it.
export const VIEWS = [
  { name: 'panel-square', tier: 'panel', width: 480, height: 480 },
  { name: 'panel-wide', tier: 'panel', width: 520, height: 480 },
  { name: 'panel-tall', tier: 'panel', width: 480, height: 800 },
  { name: 'sidebar-desktop', tier: 'supported', width: 1440, height: 900, host: { toolbar: 56, sidebar: 256 } },
  { name: 'sidebar-phone', tier: 'supported', width: 390, height: 844, host: { toolbar: 56, sidebar: 0 } },
  { name: 'sidebar-tablet', tier: 'supported', width: 820, height: 1180, host: { toolbar: 56, sidebar: 56 } },
  { name: 'direct-desktop', tier: 'supported', width: 1440, height: 900 },
];

// A card may grow this much over English at the same view before the wrapping is excessive.
export const GROWTH_LIMIT = 1.2;
// Absolute allowance for sub-line rounding (under one line of body text).
const GROWTH_SLACK_PX = 12;
const CANCELLED_FETCH = /due to access control checks|Load failed|Fetch is aborted|The operation was aborted/i;

// Breakages that only a shorter translation can fix (English must stay as it is and no layout keeps the
// label whole), recorded for the translation pipeline. Each is reported, never silently dropped.
const KNOWN = JSON.parse(readFileSync(new URL('./known-issues.json', import.meta.url), 'utf8')).issues;

const MIME = {
  '.css': 'text/css', '.js': 'application/javascript', '.json': 'application/json',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.html': 'text/html',
};

export function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[character]);
}

export async function loadCatalogues() {
  const files = (await readdir(resolve(ASSETS, 'i18n'))).filter((name) => name.endsWith('.json')).sort();
  const catalogues = new Map();
  for (const name of files) {
    const catalogue = JSON.parse(await readFile(resolve(ASSETS, 'i18n', name), 'utf8'));
    assert.equal(name, `${catalogue.locale}.json`, `catalogue filename must match its declared locale: ${name}`);
    catalogues.set(catalogue.locale, catalogue);
  }
  assert.ok(catalogues.has('en'), 'the English source catalogue is required');
  // English first: every other locale is compared with it.
  const locales = ['en', ...[...catalogues.keys()].filter((locale) => locale !== 'en')];
  return { catalogues, locales, scales: localeScales(catalogues) };
}

/**
 * Each locale's expected length scale: the median ratio of translated to English string length across
 * the whole catalogue, computed at run time so there is nothing to maintain. A card is expected to grow
 * with its language; only growth well beyond that scale is a layout corner case.
 */
export function localeScales(catalogues) {
  const english = catalogues.get('en').strings;
  const scales = {};
  for (const [locale, catalogue] of catalogues) {
    const ratios = Object.entries(english)
      .filter(([key, record]) => typeof record?.text === 'string' && record.text.length && typeof catalogue.strings[key]?.text === 'string')
      .map(([key, record]) => catalogue.strings[key].text.length / record.text.length)
      .sort((a, b) => a - b);
    const middle = Math.floor(ratios.length / 2);
    scales[locale] = ratios.length ? Math.round((ratios.length % 2 ? ratios[middle] : (ratios[middle - 1] + ratios[middle]) / 2) * 1000) / 1000 : 1;
  }
  return scales;
}

/** The per-locale strings view a page module renders with; missing keys fall back to English. */
export function stringsFor(catalogues, locale) {
  const own = catalogues.get(locale)?.strings || {};
  const english = catalogues.get('en').strings;
  const text = (key) => {
    const value = own[key]?.text ?? english[key]?.text;
    if (typeof value !== 'string') throw new Error(`English catalogue is missing ${key}`);
    return value;
  };
  const format = (key, values = {}) => text(key).replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, (match, name) =>
    Object.prototype.hasOwnProperty.call(values, name) ? String(values[name]) : match);
  return {
    locale,
    text,
    format,
    t: (key, values) => escapeHtml(format(key, values)),
    has: (key) => typeof english[key]?.text === 'string',
    keys: (prefix) => Object.keys(english).filter((key) => key.startsWith(prefix)),
  };
}

/** Mirror of browserI18nPayload(): the resolved projection for the page's catalogue prefixes. */
export function i18nPayload(catalogues, locale, prefixes) {
  const english = catalogues.get('en').strings;
  const own = catalogues.get(locale)?.strings || {};
  const strings = {}; const languages = {};
  for (const key of Object.keys(english)) {
    if (!prefixes.some((prefix) => key.startsWith(prefix))) continue;
    const localized = typeof own[key]?.text === 'string';
    strings[key] = localized ? own[key].text : english[key].text;
    languages[key] = localized ? locale : 'en';
  }
  const provenance = prefixes.some((prefix) => ['entities.', 'install.', 'runtime.'].includes(prefix));
  return JSON.stringify({ locale, strings, ...(provenance ? { languages } : {}) })
    .replaceAll('<', '\\u003c').replaceAll('>', '\\u003e').replaceAll('&', '\\u0026')
    .replaceAll('\u2028', '\\u2028').replaceAll('\u2029', '\\u2029');
}

export const PANEL = { id: 'hallway_panel', name: 'Hallway panel' };
const PEERS = [
  { panel_id: PANEL.id, name: PANEL.name, self: true, host: '192.0.2.10', port: 8888 },
  { panel_id: 'kitchen_wall_panel', name: 'Kitchen wall panel with a long name', host: '192.0.2.11', port: 8888 },
];

/**
 * Mirror of PaneldServer.pageShell() + navBar(). Direct pages carry the header, the mDNS switcher and
 * the ?theme= pin script; embedded pages (X-Panel-Assistant-Embed) drop the header and switcher, take
 * the theme from the switch and mark the body data-embedded.
 */
export function shellHtml({ s, catalogues, locale, theme, embed, active, sectionTitle, prefixes, bodyAttrs = '', rightControls = '', body, extraScripts = '', setupTab = false }) {
  const title = escapeHtml(sectionTitle ? `${PANEL.name} · ${sectionTitle}` : PANEL.name);
  const themeAttr = embed ? ` data-theme="${theme}"` : '';
  const header = embed
    ? `<span id="pswitch" hidden data-self-id="${PANEL.id}" data-self-name="${PANEL.name}"></span>`
    : `<div class="hdr"><button id="navburger" class="navburger pbtn" aria-label="${s.t('shell.menu.label')}">☰</button><h1><img src="icon.svg" class="logo" alt=""><span class="brand">ha-paneld</span> <small id="pswitch" data-self-id="${PANEL.id}" data-self-name="${PANEL.name}"><span class="sep">·</span>${PANEL.name}</small></h1>
 <span style="display:flex;gap:10px;align-items:center">${rightControls}</span></div>
`;
  const switcher = embed ? '' : '<script src="assets/switcher.js"></script>\n';
  const href = (path) => (locale === 'en' ? path : `${path}${path.includes('?') ? '&' : '?'}lang=${locale}`);
  const tab = (id, path, key) => `<a href="${href(path)}"${id === active ? ' class="active"' : ''}>${s.t(key)}</a>`;
  const nav = `<div class="nav">${setupTab ? tab('setup', 'setup', 'shell.nav.setup') : ''}${tab('dashboard', './', 'shell.nav.dashboard')}${tab('configure', 'configure', 'shell.nav.configure')}${tab('profiles', 'profiles', 'shell.nav.profile')}${tab('entities', 'entities', 'shell.nav.entities')}${tab('install', 'install', 'shell.nav.install')}${tab('logs', 'logs', 'shell.nav.logs')}<a href="${href('api')}">API</a></div>`;
  const migration = `<div id="migrationbar" class="setup">⚠ <b>${s.t('shell.migration.title')}</b> ${s.t('shell.migration.body')} <a href="https://example.invalid/migration" target="_blank" rel="noopener">https://example.invalid/migration</a> <button id="migration-dismiss" class="pbtn" type="button">${s.t('shell.migration.dismiss')}</button></div>`;
  return `<!doctype html><html lang="${escapeHtml(locale)}"${themeAttr}><head><base href="/"><meta charset="utf-8">
<script>/* ?theme=light|dark pins the UI theme for testing (else the browser preference rules) */
(function(){var m=location.search.match(/[?&]theme=(dark|light)\\b/);if(m)document.documentElement.setAttribute("data-theme",m[1])})();</script>
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title>
<link rel="icon" type="image/svg+xml" href="favicon.svg">
<link rel="stylesheet" href="info.css">
<script id="ha-i18n" type="application/json">${i18nPayload(catalogues, locale, prefixes)}</script>
<script src="assets/i18n.js"></script></head><body ${bodyAttrs}${embed ? ' data-embedded' : ''}><div class="wrap">
<div class="topbar">${header}${nav}</div>
${switcher}<div id="halifebar" class="setup" style="display:none"></div>
<div id="hanetbar" class="setup" style="display:none"></div>
${migration}
<div id="verbar" class="setup" style="display:none">⟳ ${s.t('shell.new_version.installed')} — <a href="#" onclick="location.reload();return false">${s.t('shell.action.reload')}</a> ${s.t('shell.new_version.refresh_suffix')}</div>
${body}
${extraScripts}<script src="assets/power-safety.js"></script>
<script src="assets/buildwatch.js"></script>
</div></body></html>`;
}

/** Mirror of PaneldServer.page(): the tabbed-page wrapper around pageShell(). */
export function tabbedPage(options) {
  const { s, active, body } = options;
  const description = `<span id="hardened-approval-description" class="sr-only">${s.t('configure.hardened.action_approval')}</span><span id="hardened-approval-conditional-description" class="sr-only">${s.t('configure.hardened.setting_approval')}</span><span id="hardened-approval-section-description" class="sr-only">${s.t('shell.hardened.section')}</span><span id="hardened-approval-section-conditional-description" class="sr-only">${s.t('shell.hardened.section_conditional')}</span>`;
  const key = ['configure', 'install'].includes(active) ? `<p class="hardened-approval-key${active === 'install' ? ' top' : ''}">${s.t('shell.hardened.key')}</p>` : '';
  const ghLink = `<a class="gh" href="https://example.invalid/repository" target="_blank" rel="noopener" title="${s.t('shell.github.title')}" aria-label="GitHub"><svg viewBox="0 0 24 24"><path d="M12 2a10 10 0 0 0-3 19.5v-3.4c-2.8.6-3.4-1.2-3.4-1.2-.4-1.1-1.1-1.4-1.1-1.4-.9-.6.1-.6.1-.6 1 .1 1.5 1 1.5 1 .9 1.5 2.4 1.1 3 .8.1-.6.3-1.1.6-1.3-2.2-.3-4.6-1.1-4.6-5 0-1.1.4-2 1-2.7-.1-.3-.4-1.3.1-2.7 0 0 .8-.3 2.8 1a9.6 9.6 0 0 1 5 0c1.9-1.3 2.8-1 2.8-1 .5 1.4.2 2.4.1 2.7.6.7 1 1.6 1 2.7 0 3.9-2.4 4.7-4.6 5 .4.3.7.9.7 1.9v2.8A10 10 0 0 0 12 2z"/></svg></a>`;
  const haLink = `<a class="pbtn" href="https://example.invalid/home-assistant" target="_blank" rel="noopener">${s.t('shell.open_in_ha')}</a>`;
  return shellHtml({
    ...options,
    rightControls: options.rightControls ?? `${haLink}${ghLink}`,
    bodyAttrs: `${options.bodyAttrs || ''}data-build="layout-gate" data-cfg="layout-gate"`,
    body: `${description}\n${active === 'install' ? key : ''}\n${body}\n${active !== 'install' ? key : ''}`,
    prefixes: options.prefixes || ['shell.', `${active}.`, 'runtime.'],
  });
}

function hostHtml(view, src, theme) {
  const dark = theme === 'dark';
  const bg = dark ? '#111111' : '#fafafa'; const fg = dark ? '#e1e1e1' : '#212121';
  const bar = dark ? '#1c1c1c' : '#03a9f4';
  const menu = ['Overview', 'Energy', 'Map', 'Logbook', 'History', 'Media', 'Panel Assistant', 'Developer tools', 'Settings'];
  const sidebar = view.host.sidebar
    ? `<nav style="flex:0 0 ${view.host.sidebar}px;height:100vh;overflow:hidden;background:${dark ? '#1c1c1c' : '#ffffff'};border-right:1px solid ${dark ? '#333' : '#e0e0e0'};font:14px system-ui">${menu.map((item) => `<div style="height:48px;display:flex;align-items:center;padding:0 16px;white-space:nowrap">${view.host.sidebar > 100 ? item : '▣'}</div>`).join('')}</nav>`
    : '';
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Home Assistant</title>
<style>html,body{margin:0;height:100%;overflow:hidden;background:${bg};color:${fg}}</style></head>
<body><div style="display:flex;height:100vh">${sidebar}<main style="flex:1 1 auto;min-width:0;display:flex;flex-direction:column;height:100vh">
<header style="flex:0 0 ${view.host.toolbar}px;display:flex;align-items:center;padding:0 16px;background:${bar};color:#fff;font:20px system-ui">Panel Assistant</header>
<iframe name="panel" src="${escapeHtml(src)}" style="flex:1 1 auto;border:0;width:100%;display:block"></iframe></main></div></body></html>`;
}

async function serveAsset(pathname, response) {
  const aliases = { '/info.css': 'info.css', '/info.js': 'info.js', '/favicon.svg': 'favicon.svg', '/icon.svg': 'icon.svg' };
  const relativePath = aliases[pathname] || (pathname.startsWith('/assets/') ? pathname.slice('/assets/'.length) : null);
  if (!relativePath) return false;
  const file = resolve(ASSETS, decodeURIComponent(relativePath));
  const inside = relative(ASSETS, file);
  if (inside === '..' || inside.startsWith('../') || !existsSync(file)) { response.writeHead(404); response.end('not found'); return true; }
  response.writeHead(200, { 'content-type': `${MIME[extname(file)] || 'application/octet-stream'}; charset=utf-8`, 'cache-control': 'no-store' });
  response.end(await readFile(file));
  return true;
}

function send(response, payload) {
  if (payload === undefined) payload = {};
  if (payload && typeof payload === 'object' && payload.__raw) {
    response.writeHead(payload.status || 200, { 'content-type': payload.type || 'text/plain; charset=utf-8' });
    response.end(payload.body);
    return;
  }
  response.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  response.end(JSON.stringify(payload));
}

/** One origin serving every page definition; the query selects locale, theme and embedded mode. */
export async function startLayoutServer(pages, catalogues, locales) {
  const byPath = new Map(pages.map((page) => [page.path, page]));
  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url, 'http://layout.test');
      const query = url.searchParams;
      if (url.pathname === '/__host') {
        const view = VIEWS.find((item) => item.name === query.get('view'));
        response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
        response.end(hostHtml(view, query.get('src'), query.get('theme')));
        return;
      }
      if (await serveAsset(url.pathname, response)) return;
      const referer = request.headers.referer ? new URL(request.headers.referer) : null;
      const locale = locales.includes(query.get('lang')) ? query.get('lang')
        : locales.includes(referer?.searchParams.get('lang')) ? referer.searchParams.get('lang') : 'en';
      const embed = (query.get('embed') ?? referer?.searchParams.get('embed')) === '1';
      const theme = THEMES.includes(query.get('theme') ?? referer?.searchParams.get('theme')) ? (query.get('theme') ?? referer.searchParams.get('theme')) : 'dark';
      const pageFromReferer = referer ? byPath.get(referer.pathname) : null;
      const context = { catalogues, locale, theme, embed, s: stringsFor(catalogues, locale) };
      const page = byPath.get(url.pathname);
      if (page) {
        response.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
        response.end(page.html(context));
        return;
      }
      if (url.pathname === '/health') { send(response, { __raw: true, body: 'ok build=layout-gate' }); return; }
      if (url.pathname === '/api/v1/peers') { send(response, PEERS); return; }
      const owner = pageFromReferer || pages.find((item) => item.ownsApi?.(url.pathname));
      const payload = owner?.api ? await owner.api(url, request.method, context) : undefined;
      send(response, payload);
    } catch (error) {
      response.writeHead(500); response.end(String(error?.stack || error));
    }
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  return server;
}

export function selection(pages, locales) {
  const pick = (name, all) => {
    const raw = (process.env[name] || '').split(',').map((item) => item.trim()).filter(Boolean);
    if (!raw.length) return all;
    const unknown = raw.filter((item) => !all.includes(item));
    if (unknown.length) throw new Error(`${name} names unknown values: ${unknown.join(', ')} (known: ${all.join(', ')})`);
    return all.filter((item) => raw.includes(item));
  };
  const selectedLocales = pick('LAYOUT_LOCALES', locales);
  return {
    pages: pick('LAYOUT_PAGES', pages.map((page) => page.name)),
    browsers: pick('LAYOUT_BROWSERS', BROWSERS),
    views: pick('LAYOUT_VIEWS', VIEWS.map((view) => view.name)),
    themes: pick('LAYOUT_THEMES', THEMES),
    // English is the comparison baseline, so it is always measured.
    locales: selectedLocales.includes('en') ? selectedLocales : ['en', ...selectedLocales],
  };
}

export async function launch(browserName) {
  if (browserName === 'chromium') {
    assert.ok(existsSync(CHROME), `required Chromium executable is missing: ${CHROME}`);
    return chromium.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  }
  return webkit.launch({ headless: true });
}

// Wait for fonts, then until the card wall holds still for three consecutive frames (masonry and column
// alignment may still be moving cards when the ready condition first holds) and no card is split across
// columns. Gives up after two seconds.
async function settle(frame) {
  await frame.evaluate(async () => {
    if (document.fonts && document.fonts.ready) await document.fonts.ready;
    const signature = () => [document.documentElement.scrollHeight, ...[...document.querySelectorAll('.card,h2')].map((node) => {
      const box = node.getBoundingClientRect(); return `${Math.round(box.left)},${Math.round(box.top)},${Math.round(box.height)}`;
    })].join('|');
    const deadline = performance.now() + 2000;
    let last = ''; let stable = 0;
    while (stable < 3 && performance.now() < deadline) {
      await new Promise((done) => requestAnimationFrame(done));
      const next = signature();
      // WebKit can show a card split across two columns for a few frames after hydration; a card that
      // stays split past the deadline is measured (and fails) as it is.
      const fragmented = [...document.querySelectorAll('.cards>.card')].some((card) => card.getClientRects().length > 1);
      stable = next === last && !fragmented ? stable + 1 : 0; last = next;
    }
  });
}

/** Load one cell and return its measurement plus any page errors. */
export async function measureCell(page, origin, pageDef, view, theme, locale, mutate = null) {
  const errors = [];
  const onError = (error) => errors.push(error.message);
  page.on('pageerror', onError);
  try {
    const embed = Boolean(view.host);
    const src = `${pageDef.path}?lang=${locale}&theme=${theme}${embed ? '&embed=1' : ''}`;
    let frame;
    if (embed) {
      await page.goto(`${origin}/__host?view=${view.name}&theme=${theme}&src=${encodeURIComponent(src)}`, { waitUntil: 'domcontentloaded' });
      const handle = await page.waitForSelector('iframe[name="panel"]');
      frame = await handle.contentFrame();
      await frame.waitForLoadState('domcontentloaded');
    } else {
      await page.goto(`${origin}${src}`, { waitUntil: 'domcontentloaded' });
      frame = page.mainFrame();
    }
    await pageDef.ready(frame, { locale, theme, view });
    if (pageDef.exercise) await pageDef.exercise(frame, { locale, theme, view });
    // Narrow card walls skip rendering off-screen cards (content-visibility:auto). Measure every card as
    // it renders once scrolled into view, identically for English and the locale.
    await frame.evaluate(() => document.head.insertAdjacentHTML('beforeend', '<style id="layout-gate-render-all">*{content-visibility:visible!important}</style>'));
    await settle(frame);
    // Measure injected defects in the same browser turn: a later hydration render can replace the
    // mutated card while we await settlement and erase the negative control before it is observed.
    const result = await frame.evaluate(mutate
      ? `(() => { (${mutate.toString()})(); return (${measureLayout.toString()})(); })()`
      : measureLayout);
    return { ...result, errors };
  } finally {
    page.off('pageerror', onError);
  }
}

/**
 * Turn one measurement into errors and reports. English at the same page, view, theme and browser is the
 * baseline for designed truncation and wrapping (an ellipsis English shows too is data truncation, and
 * English cosmetics stay report-only) and for the card growth rule. Overflow, overlap and unreachable
 * controls are absolute.
 */
export function verdict(result, english, view, locale, scale = 1) {
  const errors = []; const reports = [];
  const englishKeys = (list) => new Set((english?.[list] || []).map((item) => item.key));
  const isEnglish = locale === 'en';
  const panel = view.tier === 'panel';
  const add = (family, item, blocking) => (blocking ? errors : reports).push(`(${family}) ${item.key}${item.text ? ` “${item.text}”` : ''}${item.by && item.by !== 'self' ? ` [by ${item.by}]` : ''}${item.lines ? ` [${item.lines} lines]` : ''}${item.amount ? ` [${item.amount}px]` : ''}${item.culprits ? ` [${item.culprits.join(' | ')}]` : ''}`);

  const cutInEnglish = englishKeys('cut');
  for (const item of result.cut) add('a cut off', item, !isEnglish && !cutInEnglish.has(item.key));
  for (const item of result.offscreen) add('c unreachable', item, true);
  for (const item of result.overflow) add('b overflow', item, !panel);
  for (const item of result.overlaps) add('b overlap', item, !panel);
  const wrapsInEnglish = englishKeys('wraps');
  // Row headers of key/value tables wrap in a fixed 46% column; making them single-line needs either
  // shorter translations or a table layout that changes English, so they are reported, not blocking,
  // (see tools/test/README.md). Column headers and controls block.
  for (const item of result.wraps) add(item.rowHeader ? 'd row header wraps' : 'd wraps', item, !panel && !isEnglish && !item.rowHeader && !wrapsInEnglish.has(item.key));
  if (!isEnglish && english) {
    // Rule (iii): every card more than 20% taller than English is reported. It blocks only when its
    // growth exceeds the locale's expected length scale by more than 20% (a corner case, not the length
    // inherent in the language). A language shorter than English (scale < 1) is still held to English's
    // height, since a card cannot shrink below its controls.
    const expected = Math.max(1, scale) * GROWTH_LIMIT;
    for (const [key, card] of Object.entries(result.cards)) {
      const base = english.cards[key];
      if (!base) continue;
      if (card.height <= base.height * GROWTH_LIMIT + GROWTH_SLACK_PX) continue;
      const blocking = card.height > base.height * expected + GROWTH_SLACK_PX;
      add('d card growth', { key, text: `${base.height}px → ${card.height}px (+${Math.round((card.height / base.height - 1) * 100)}%; ${locale} scale ${scale}, limit +${Math.round((expected - 1) * 100)}%)` }, !panel && blocking);
    }
  }
  // A fetch the previous navigation left in flight is cancelled, not a page failure.
  for (const message of result.errors) if (!CANCELLED_FETCH.test(message)) errors.push(`(script) ${message}`);
  if (result.lang !== locale) errors.push(`(frame) document language ${result.lang} is not ${locale}`);
  return { errors, reports };
}

/** Run every selected cell for one page in one browser. Returns counts, failures and wall time. */
export async function runPage({ pageDef, browserName, origin, sel, scales = {}, known = KNOWN }) {
  const started = Date.now();
  const browser = await launch(browserName);
  const failures = []; const reports = []; let cells = 0; const matchedKnown = new Set();
  try {
    for (const viewName of sel.views) {
      const view = VIEWS.find((item) => item.name === viewName);
      const context = await browser.newContext({ viewport: { width: view.width, height: view.height }, deviceScaleFactor: 1 });
      // Card-size memory and similar per-origin state must not carry one locale's geometry into the next.
      await context.addInitScript(() => { try { localStorage.clear(); sessionStorage.clear(); } catch (_) { /* storage may be unavailable */ } });
      const page = await context.newPage();
      page.setDefaultTimeout(15_000);
      try {
        for (const theme of sel.themes) {
          let english = null;
          for (const locale of sel.locales) {
            const cell = `${pageDef.name}/${browserName}/${view.name}/${theme}/${locale}`;
            let result;
            try { result = await measureCell(page, origin, pageDef, view, theme, locale); } catch (error) {
              failures.push(`${cell}: (load) ${String(error.message).split('\n')[0]}`); cells += 1; continue;
            }
            if (locale === 'en') english = result;
            const outcome = verdict(result, english, view, locale, scales[locale] ?? 1);
            outcome.errors.forEach((message) => {
              const excused = known.find((entry) => entry.page === pageDef.name && entry.view === view.name && entry.locale === locale
                && (!entry.browsers || entry.browsers.includes(browserName)) && message.startsWith(`(${entry.family}) ${entry.key}`));
              if (excused) { matchedKnown.add(excused); reports.push(`${cell}: known translation length ${message}`); } else failures.push(`${cell}: ${message}`);
            });
            outcome.reports.forEach((message) => reports.push(`${cell}: ${message}`));
            cells += 1;
          }
        }
      } finally {
        await context.close();
      }
    }
  } finally {
    await browser.close();
  }
  // A known entry that no longer occurs in a cell this run measured is stale: the translation or layout
  // was fixed, so the entry must go (otherwise it would silently excuse a future regression).
  for (const entry of known) {
    if (entry.page !== pageDef.name || matchedKnown.has(entry) || (entry.browsers && !entry.browsers.includes(browserName))) continue;
    if (sel.views.includes(entry.view) && sel.locales.includes(entry.locale) && sel.themes.length === THEMES.length) {
      failures.push(`${pageDef.name}/${browserName}/${entry.view}/*/${entry.locale}: stale known-issue entry (${entry.family}) ${entry.key} — remove it from language-layout/known-issues.json`);
    }
  }
  return { page: pageDef.name, browser: browserName, cells, failures, reports, ms: Date.now() - started };
}
