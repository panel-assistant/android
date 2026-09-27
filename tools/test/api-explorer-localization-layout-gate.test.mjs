import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { existsSync } from 'node:fs';
import { readFile, readdir } from 'node:fs/promises';
import { extname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium } from 'playwright-core';
import { API_HTML, API_SPEC, EXPANDED_ENDPOINTS, apiFrame, openEndpoints } from './language-layout/pages/api.mjs';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const ASSETS = resolve(ROOT, 'app/src/main/assets');
const CHROME = process.env.CHROME || '/usr/bin/chromium';
const LOCALES = await catalogueLocales();
const THEMES = ['light', 'dark'];
const VIEWPORTS = [
  { name: 'narrow-panel', width: 320, height: 568 },
  { name: 'wide-browser', width: 900, height: 800 },
];
const MIME = {
  '.js': 'application/javascript',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
};

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

async function startServer(catalogues) {
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://layout.test');
    if (url.pathname === '/api') {
      const locale = LOCALES.includes(url.searchParams.get('lang')) ? url.searchParams.get('lang') : 'en';
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      response.end(apiFrame({ catalogues, locale }));
      return;
    }
    if (url.pathname === '/api/v1/openapi.json') {
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      response.end(await readFile(API_SPEC));
      return;
    }
    if (url.pathname.startsWith('/assets/') || url.pathname === '/favicon.svg') {
      try {
        const relativePath = url.pathname === '/favicon.svg' ? 'favicon.svg' : url.pathname.replace(/^\/assets\//, '');
        const file = resolve(ASSETS, relativePath);
        const inside = relative(ASSETS, file);
        if (inside === '..' || inside.startsWith('../')) throw new Error('asset path escapes root');
        response.writeHead(200, { 'content-type': `${MIME[extname(file)] || 'application/octet-stream'}; charset=utf-8` });
        response.end(await readFile(file));
      } catch (_) {
        response.writeHead(404); response.end('not found');
      }
      return;
    }
    response.writeHead(404); response.end('not found');
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  return server;
}

async function expandEndpoints(page) {
  await page.evaluate(openEndpoints, EXPANDED_ENDPOINTS);
  await page.waitForTimeout(80);
}

async function geometry(page) {
  return page.evaluate((expandedTargets) => {
    const visible = (node) => {
      const style = getComputedStyle(node);
      const box = node.getBoundingClientRect();
      return style.display !== 'none' && style.visibility !== 'hidden' && box.width > 0 && box.height > 0;
    };
    const boxOf = (node) => {
      const box = node.getBoundingClientRect();
      return { left: box.left, right: box.right, top: box.top, bottom: box.bottom, width: box.width, height: box.height };
    };
    const outsideViewport = [];
    const clippedControls = [];
    const overlaps = [];
    const controls = [...document.querySelectorAll('details[open] input,details[open] select,details[open] textarea,details[open] button')].filter(visible);
    controls.forEach((node) => {
      const box = node.getBoundingClientRect();
      const body = node.closest('.body')?.getBoundingClientRect();
      const label = `${node.tagName.toLowerCase()}:${node.closest('details')?.querySelector('.path')?.textContent || '?'}`;
      if (box.left < -1 || box.right > innerWidth + 1) outsideViewport.push(label);
      if (!body || box.left < body.left - 1 || box.right > body.right + 1 || box.top < body.top - 1 || box.bottom > body.bottom + 1) clippedControls.push(label);
    });
    for (let left = 0; left < controls.length; left += 1) for (let right = left + 1; right < controls.length; right += 1) {
      if (controls[left].closest('details') !== controls[right].closest('details')) continue;
      const a = controls[left].getBoundingClientRect();
      const b = controls[right].getBoundingClientRect();
      const width = Math.min(a.right, b.right) - Math.max(a.left, b.left);
      const height = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
      if (width > 1 && height > 1) overlaps.push(`${controls[left].tagName}<>${controls[right].tagName}`);
    }
    const expanded = expandedTargets.map((target) => {
      const endpoint = [...document.querySelectorAll('details.ep')].find((node) =>
        node.querySelector('.path')?.textContent === target.path &&
        node.querySelector('.m')?.textContent === target.method
      );
      return {
        ...target,
        open: Boolean(endpoint?.open),
        endpoint: endpoint ? boxOf(endpoint) : null,
        approval: endpoint?.querySelector('button')?.getAttribute('data-hardened-approval'),
        controls: endpoint ? [...endpoint.querySelectorAll('input,select,textarea,button')].filter(visible).length : 0,
      };
    });
    return {
      language: document.documentElement.lang,
      runtimeLocale: window.HaI18n?.locale,
      theme: document.documentElement.dataset.theme,
      endpoints: document.querySelectorAll('details.ep').length,
      horizontalOverflow: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - innerWidth,
      outsideViewport,
      clippedControls,
      overlaps,
      cls: (window.__apiLayoutShifts || []).reduce((sum, value) => sum + value, 0),
      expanded,
    };
  }, EXPANDED_ENDPOINTS);
}

test('API Explorer layout gate remains bound to the production frame, runtime and specification', async () => {
  const [html, script, helper, spec] = await Promise.all([
    readFile(API_HTML, 'utf8'),
    readFile(resolve(ASSETS, 'api.js'), 'utf8'),
    readFile(resolve(ASSETS, 'i18n.js'), 'utf8'),
    readFile(API_SPEC, 'utf8'),
  ]);
  for (const marker of ['__API_LANG__', '__API_BACK_HREF__', '__API_I18N_PAYLOAD__', 'id="root"', 'src="assets/i18n.js"', 'src="assets/api.js"']) {
    assert.ok(html.includes(marker), `production API frame lost ${marker}`);
  }
  for (const marker of ['api.approval.conditional', 'data-hardened-approval', 'function render(spec)', 'function endpoint(']) {
    assert.ok(script.includes(marker), `production API runtime lost ${marker}`);
  }
  assert.ok(helper.includes('root.HaI18n = Object.freeze'), 'production translation helper remains loaded');
  const paths = JSON.parse(spec).paths;
  EXPANDED_ENDPOINTS.forEach(({ path, method }) => assert.ok(paths[path]?.[method.toLowerCase()], `production OpenAPI specification lost ${method} ${path}`));
});

test('API Explorer fits every release locale and theme at narrow and wide widths', { timeout: 180_000 }, async (t) => {
  assert.ok(existsSync(CHROME), `Chromium is required for the API Explorer layout gate: ${CHROME}`);
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
  const clsFailures = [];

  for (const locale of LOCALES) for (const theme of THEMES) for (const viewport of VIEWPORTS) {
    const page = await browser.newPage({ viewport: { width: viewport.width, height: viewport.height } });
    await page.addInitScript(() => {
      window.__apiLayoutShifts = [];
      if ('PerformanceObserver' in window) {
        new PerformanceObserver((list) => {
          list.getEntries().forEach((entry) => { if (!entry.hadRecentInput) window.__apiLayoutShifts.push(entry.value); });
        }).observe({ type: 'layout-shift', buffered: true });
      }
    });
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(`${origin}/api?lang=${locale}&theme=${theme}`, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => document.querySelectorAll('details.ep').length > 20);
    await page.waitForTimeout(80);
    const collapsed = await geometry(page);
    await expandEndpoints(page);
    const expanded = await geometry(page);
    const cell = `${locale}/${theme}/${viewport.name}-${viewport.width}x${viewport.height}`;

    assert.deepEqual(errors, [], `${cell}: browser errors`);
    assert.equal(expanded.language, locale, `${cell}: document language`);
    assert.equal(expanded.runtimeLocale, locale, `${cell}: browser translation projection`);
    assert.equal(expanded.theme, theme, `${cell}: forced theme`);
    assert.ok(collapsed.endpoints > 20, `${cell}: complete OpenAPI page rendered`);
    assert.ok(collapsed.horizontalOverflow <= 1, `${cell}: collapsed page horizontal overflow ${collapsed.horizontalOverflow}px`);
    assert.ok(expanded.horizontalOverflow <= 1, `${cell}: expanded page horizontal overflow ${expanded.horizontalOverflow}px`);
    assert.deepEqual(expanded.outsideViewport, [], `${cell}: expanded controls leave viewport`);
    assert.deepEqual(expanded.clippedControls, [], `${cell}: expanded controls are clipped by their endpoint`);
    assert.deepEqual(expanded.overlaps, [], `${cell}: expanded controls overlap`);
    if (collapsed.cls > 0.10) clsFailures.push(`${cell}: ${collapsed.cls.toFixed(4)}`);
    assert.equal(expanded.expanded[0].open, true, `${cell}: consequential endpoint expanded`);
    assert.equal(expanded.expanded[0].approval, '', `${cell}: consequential endpoint keeps required approval marker`);
    assert.ok(expanded.expanded[0].controls > 0, `${cell}: consequential endpoint controls rendered`);
    assert.equal(expanded.expanded[1].open, true, `${cell}: conditional endpoint expanded`);
    assert.equal(expanded.expanded[1].approval, 'conditional', `${cell}: conditional endpoint keeps conditional approval marker`);
    assert.ok(expanded.expanded[1].controls > 0, `${cell}: conditional endpoint controls rendered`);
    evidence.push({ cell, collapsed, expanded });
    await page.close();
  }

  assert.equal(evidence.length, 36, 'complete 9 locale × 2 theme × 2 viewport matrix');
  const summary = {
    cells: evidence.length,
    endpointRenderings: evidence.reduce((sum, item) => sum + item.expanded.endpoints, 0),
    expandedEndpointRenderings: evidence.reduce((sum, item) => sum + item.expanded.expanded.length, 0),
    maximumCollapsedOverflow: Math.max(...evidence.map((item) => item.collapsed.horizontalOverflow)),
    maximumExpandedOverflow: Math.max(...evidence.map((item) => item.expanded.horizontalOverflow)),
    maximumCls: Math.max(...evidence.map((item) => item.collapsed.cls)),
  };
  console.log(`API Explorer localization layout evidence: ${JSON.stringify(summary)}`);
  assert.deepEqual(clsFailures, [], `load CLS exceeds 0.10: ${clsFailures.join(', ')}`);

  // Negative controls prove that the exact measurements used above become positive for regressions.
  const mutant = await browser.newPage({ viewport: { width: 320, height: 568 } });
  await mutant.goto(`${origin}/api?lang=zh-Hans&theme=dark`, { waitUntil: 'domcontentloaded' });
  await mutant.waitForFunction(() => document.querySelectorAll('details.ep').length > 20);
  await expandEndpoints(mutant);
  await mutant.evaluate(() => {
    document.body.insertAdjacentHTML('beforeend', '<div id="overflow-mutant" style="width:900px;height:1px"></div>');
    const endpoint = [...document.querySelectorAll('details.ep')].find((node) =>
      node.querySelector('.path')?.textContent === '/api/v1/config' && node.querySelector('.m')?.textContent === 'POST'
    );
    const controls = endpoint.querySelectorAll('input,select,textarea,button');
    controls[1].style.position = 'absolute';
    controls[1].style.left = `${controls[0].getBoundingClientRect().left}px`;
    controls[1].style.top = `${controls[0].getBoundingClientRect().top}px`;
    controls[0].style.width = '600px';
    window.__apiLayoutShifts = [0.2];
  });
  const broken = await geometry(mutant);
  assert.ok(broken.horizontalOverflow > 1, 'negative control proves overflow detection');
  assert.ok(broken.outsideViewport.length > 0, 'negative control proves off-viewport control detection');
  assert.ok(broken.clippedControls.length > 0, 'negative control proves clipped-control detection');
  assert.ok(broken.overlaps.length > 0, 'negative control proves control-overlap detection');
  assert.ok(broken.cls > 0.10, 'negative control proves CLS threshold detection');
  await mutant.close();
});
