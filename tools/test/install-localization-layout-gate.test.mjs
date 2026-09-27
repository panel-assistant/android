import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { existsSync } from 'node:fs';
import { readFile, readdir } from 'node:fs/promises';
import { extname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium } from 'playwright-core';
import { exerciseStates, payload } from './language-layout/pages/install.mjs';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const ASSETS = resolve(ROOT, 'app/src/main/assets');
const FIXTURE = resolve(ROOT, 'tools/test/fixtures/install-localization-layout.html');
const CHROME = process.env.CHROME || '/usr/bin/chromium';
const LOCALES = await catalogueLocales();
const THEMES = ['light', 'dark'];
const VIEWPORTS = [
  { name: 'square-panel', width: 480, height: 480 },
  { name: 'wide-panel', width: 520, height: 480 },
  { name: 'tall-panel', width: 480, height: 800 },
  { name: 'column-boundary', width: 857, height: 800 },
  { name: 'desktop', width: 1440, height: 900 },
];
const MIME = { '.css': 'text/css', '.js': 'application/javascript', '.svg': 'image/svg+xml' };

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

async function startServer() {
  const fixture = await readFile(FIXTURE, 'utf8');
  const [english, installSource] = await Promise.all([
    readFile(resolve(ASSETS, 'i18n/en.json'), 'utf8'), readFile(resolve(ASSETS, 'install.js'), 'utf8'),
  ]);
  const keys = [...new Set([
    ...Object.keys(JSON.parse(english).strings),
    ...[...installSource.matchAll(/"((?:install|runtime)\.[^"]+)"/g)].map((match) => match[1]),
  ])].filter((key) => key.startsWith('install.') || key.startsWith('runtime.power_safety.') || key.startsWith('runtime.mdns.'));
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://layout.test');
    if (url.pathname === '/fixture') {
      const locale = LOCALES.includes(url.searchParams.get('lang')) ? url.searchParams.get('lang') : 'en';
      const theme = THEMES.includes(url.searchParams.get('theme')) ? url.searchParams.get('theme') : 'dark';
      const projection = JSON.stringify({ locale, languages: Object.fromEntries(keys.map((key) => [key, locale])) }).replaceAll('<', '\\u003c');
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      response.end(fixture.replaceAll('__LOCALE__', locale).replaceAll('__THEME__', theme).replace('__PROJECTION__', projection));
      return;
    }
    if (url.pathname.startsWith('/api/')) {
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify(payload(url, request.method)));
      return;
    }
    try {
      const relativePath = url.pathname === '/info.css' ? 'info.css' : url.pathname.replace(/^\/assets\//, '');
      const file = resolve(ASSETS, relativePath);
      const inside = relative(ASSETS, file);
      if (inside === '..' || inside.startsWith('../')) throw new Error('asset path escapes root');
      response.writeHead(200, { 'content-type': `${MIME[extname(file)] || 'application/octet-stream'}; charset=utf-8` });
      response.end(await readFile(file));
    } catch { response.writeHead(404); response.end('not found'); }
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  return server;
}

async function geometry(page) {
  return page.evaluate(() => {
    const visible = (node) => {
      const style = getComputedStyle(node), box = node.getBoundingClientRect();
      return style.display !== 'none' && style.visibility !== 'hidden' && box.width > 0 && box.height > 0;
    };
    const cards = [...document.querySelectorAll('#install-cards > .card')].filter(visible);
    const controls = [...document.querySelectorAll('button,select,input,a.pbtn')].filter(visible);
    const outside = controls.filter((node) => {
      const box = node.getBoundingClientRect();
      return box.left < -1 || box.right > innerWidth + 1;
    }).map((node) => node.id || node.textContent.trim().slice(0, 40));
    const overlaps = [];
    for (const parent of document.querySelectorAll('.comppick,#apk-preview div')) {
      const nodes = [...parent.children].filter(visible);
      for (let i = 0; i < nodes.length; i += 1) for (let j = i + 1; j < nodes.length; j += 1) {
        const a = nodes[i].getBoundingClientRect(), b = nodes[j].getBoundingClientRect();
        if (Math.min(a.right, b.right) - Math.max(a.left, b.left) > 1 && Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 1) overlaps.push(`${nodes[i].tagName}:${i}<>${nodes[j].tagName}:${j}`);
      }
    }
    return {
      overflow: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - innerWidth,
      outside, overlaps,
      cardWidths: cards.map((card) => card.getBoundingClientRect().width),
      cls: window.__installLayoutShifts.reduce((sum, value) => sum + value, 0),
      language: document.documentElement.lang,
      theme: document.documentElement.dataset.theme,
      hostileImages: document.querySelectorAll('img[src="x"]').length,
      hostileElements: [...document.querySelectorAll('img[src="x"]')].map((node) => node.parentElement?.outerHTML.slice(0, 240)),
      hostileOwned: window.__hostileOwned,
      states: {
        busy: document.querySelector('#comp-msg').textContent,
        destructive: document.querySelector('#uninst-msg').textContent,
        preview: document.querySelector('#rs-preview').textContent,
        restore: document.querySelector('#bk-msg').textContent,
        alerts: document.querySelector('#audit-out').textContent,
        power: document.querySelector('.power-safety-acknowledge-result').textContent,
      },
    };
  });
}

test('Install layout fixture stays bound to production dynamic surfaces', async () => {
  const [fixture, install, power] = await Promise.all([readFile(FIXTURE, 'utf8'), readFile(resolve(ASSETS, 'install.js'), 'utf8'), readFile(resolve(ASSETS, 'power-safety.js'), 'utf8')]);
  for (const marker of ['install-cards', 'comp-msg', 'apk-preview', 'uninst-msg', 'rs-preview', 'audit-out', 'radiocard']) assert.ok(fixture.includes(marker), `fixture lost ${marker}`);
  for (const marker of ['window.installComp', 'window.doUninstall', 'window.restorePick', 'window.restoreConfirm', 'window.healthAudit']) assert.ok(install.includes(marker), `production Install script lost ${marker}`);
  assert.match(power, /data-power-safety-(?:repair|acknowledge)/, 'production power-safety interaction remains bound');
});

test('Install dynamic states fit every release locale, theme and target viewport', { timeout: 240_000 }, async (t) => {
  assert.ok(existsSync(CHROME), `required Chromium executable is missing: ${CHROME}`);
  const server = await startServer();
  const browser = await chromium.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  t.after(async () => { await browser.close(); await new Promise((done) => server.close(done)); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  const evidence = [];
  for (const locale of LOCALES) for (const theme of THEMES) for (const viewport of VIEWPORTS) {
    const page = await browser.newPage({ viewport });
    const errors = []; page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(`${origin}/fixture?lang=${locale}&theme=${theme}`, { waitUntil: 'domcontentloaded' });
    await exerciseStates(page);
    const measured = await geometry(page); const cell = `${locale}/${theme}/${viewport.name}`;
    assert.deepEqual(errors, [], `${cell}: browser errors`);
    assert.equal(measured.language, locale, `${cell}: document locale`);
    assert.equal(measured.theme, theme, `${cell}: theme`);
    assert.ok(measured.overflow <= 1, `${cell}: horizontal overflow ${measured.overflow}px`);
    assert.deepEqual(measured.outside, [], `${cell}: controls leave viewport`);
    assert.deepEqual(measured.overlaps, [], `${cell}: action controls overlap`);
    assert.ok(measured.cardWidths.every((width) => width > 100 && width <= viewport.width + 1), `${cell}: card containment`);
    assert.ok(measured.cls <= 0.10, `${cell}: settled CLS ${measured.cls}`);
    assert.equal(measured.hostileImages, 0, `${cell}: hostile markup became an element: ${measured.hostileElements.join(' | ')}`);
    assert.equal(measured.hostileOwned, undefined, `${cell}: hostile markup executed`);
    Object.entries(measured.states).forEach(([state, value]) => assert.ok(value.length > 20, `${cell}: ${state} state was not exercised`));
    evidence.push(measured); await page.close();
  }
  assert.equal(evidence.length, 90, 'complete 9 locale × 2 theme × 5 viewport matrix');
  console.log(`Install localization layout evidence: ${JSON.stringify({ cells: evidence.length, maximumOverflow: Math.max(...evidence.map((item) => item.overflow)), maximumCls: Math.max(...evidence.map((item) => item.cls)) })}`);

  const mutant = await browser.newPage({ viewport: VIEWPORTS[0] });
  await mutant.goto(`${origin}/fixture?lang=de&theme=dark`, { waitUntil: 'domcontentloaded' }); await exerciseStates(mutant);
  await mutant.evaluate(() => {
    document.body.insertAdjacentHTML('beforeend', '<div id="overflow-mutant" style="width:900px;height:1px"></div>');
    document.querySelector('#apk-preview').insertAdjacentHTML('beforeend', '<div id="overlap-mutants"><button style="position:fixed;left:10px;top:10px;width:80px;height:40px">A</button><button style="position:fixed;left:10px;top:10px;width:80px;height:40px">B</button></div>');
    document.body.insertAdjacentHTML('beforeend', '<button id="outside-mutant" style="position:fixed;left:520px;top:60px">Outside</button><img src="x" alt="hostile mutant">');
    window.__hostileOwned = 1;
    window.__installLayoutShifts.push(0.2);
  });
  const broken = await geometry(mutant);
  assert.ok(broken.overflow > 1, 'negative control proves overflow detection');
  assert.ok(broken.outside.includes('outside-mutant'), 'negative control proves off-viewport control detection');
  assert.ok(broken.overlaps.length > 0, 'negative control proves overlap detection');
  assert.ok(broken.cls > 0.10, 'negative control proves CLS threshold detection');
  assert.ok(broken.hostileImages > 0, 'negative control proves hostile element detection');
  assert.equal(broken.hostileOwned, 1, 'negative control proves hostile execution detection');
});
