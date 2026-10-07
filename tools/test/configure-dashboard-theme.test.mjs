// Dashboard theme on the Configure page, in both engines and every release locale. The Ambient choice
// adds a fourth option and a longer help paragraph; this checks the select offers all four choices with
// their catalogued labels, keeps the stored wire values, that neither the option nor the one-line summary
// makes the row overflow at panel and desktop widths or large text, and that the full help opens in the
// help popover inside the viewport without moving the page.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';

const root = join(process.cwd(), '..', '..', 'app', 'src', 'main', 'assets');
const chrome = process.env.CHROME || '/usr/bin/chromium';
const OPTIONS = ['Follow Home Assistant', 'Dark', 'Light', 'Ambient'];
const LOCALES = ['en', 'de', 'fr', 'it', 'es', 'zh-Hans', 'nl', 'pl', 'uk', 'cs', 'pt-BR'];

const english = JSON.parse(readFileSync(join(root, 'i18n', 'en.json'), 'utf8')).strings;

// The runtime renders a target string only when it is current for the English source and not held as
// an English fallback; anything else shows English. Mirror that, so the test measures what ships.
function catalogue(locale) {
  if (locale === 'en') return {};
  const target = JSON.parse(readFileSync(join(root, 'i18n', `${locale}.json`), 'utf8')).strings;
  const shown = {};
  for (const [key, record] of Object.entries(target)) {
    if (english[key] && record.sourceHash === english[key].sourceHash && record.state !== 'english-fallback') {
      shown[key] = record.text;
    }
  }
  return shown;
}

function text(strings, key) {
  return strings[key] ?? english[key].text;
}

function page(strings, locale) {
  return `<!doctype html><html lang="${locale}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css"></head><body>
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <button id="tab-basic"></button><button id="tab-adv"></button>
    <p id="cfg-msg"></p><p id="cfg-status"></p><div id="cfg-groups" class="cards"></div>
    <div id="proximity-learning-mount"></div><div id="savebar" hidden><button id="savebtn"></button></div>
    <div id="cfg-help" class="cfg-help" popover="manual"><div class="cfg-help-head"><b id="cfg-help-title"></b><button id="cfg-help-close" type="button">x</button></div><div id="cfg-help-body" class="cfg-help-body"></div><div class="cfg-help-foot"><a id="cfg-help-more"></a></div></div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};
      window.HaI18n={t:function(k,f){var s=${JSON.stringify(strings)};return Object.prototype.hasOwnProperty.call(s,k)?s[k]:f;}};</script>
    <script src="/configure-state.js"></script><script src="/configure-view.js"></script><script src="/configure-help.js"></script><script src="/configure-controls.js"></script><script src="/configure-brightness.js"></script><script src="/configure-auto-sleep.js"></script><script src="/configure-cards.js"></script><script src="/configure-render.js"></script><script src="/configure.js"></script><script src="/proximity-learning.js"></script>
  </body></html>`;
}

function schema(strings, locale) {
  return [{
    key: 'dashboard_theme', type: 'ENUM', group: 'Dashboard', tier: 'ADVANCED', available: true,
    label: text(strings, 'settings.dashboard_theme.label'), labelLanguage: locale,
    help: text(strings, 'settings.dashboard_theme.help'), helpLanguage: locale,
    summary: text(strings, 'settings.dashboard_theme.summary'), summaryLanguage: locale,
    default: 'Follow Home Assistant', options: OPTIONS,
    optionLabels: OPTIONS.map((o) => text(strings, `configure.enum.dashboard_theme.${o.toLowerCase().replace(/[^a-z0-9]+/g, '_')}`)),
  }, {
    key: 'dashboard_fullscreen', type: 'BOOL', group: 'Dashboard', tier: 'BASIC', available: true,
    label: text(strings, 'settings.dashboard_fullscreen.label'),
  }];
}

async function harness(locale, systemBarsAvailable = true) {
  const strings = catalogue(locale);
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const send = (body, type = 'application/json') => { response.setHeader('content-type', type); response.end(body); };
    if (path === '/') return send(page(strings, locale), 'text/html');
    if (['/configure-state.js', '/configure-view.js', '/configure-help.js', '/configure-controls.js', '/configure-brightness.js', '/configure-auto-sleep.js', '/configure-cards.js', '/configure-render.js', '/configure.js'].includes(path) || path === '/proximity-learning.js') return send(await readFile(join(root, path.slice(1)), 'utf8'), 'application/javascript');
    if (path === '/info.css') return send(await readFile(join(root, 'info.css'), 'utf8'), 'text/css');
    if (path === '/api/v1/config/schema') {
      const fields = schema(strings, locale);
      fields.find(f => f.key === 'dashboard_fullscreen').available = systemBarsAvailable;
      return send(JSON.stringify(fields));
    }
    if (path === '/api/v1/config') return send(JSON.stringify({ settings: { dashboard_theme: 'Ambient', dashboard_package: 'builtin' }, ha_expose: {}, ha_auth: { configured: false } }));
    if (path === '/api/v1/apps') return send(JSON.stringify({ apps: [] }));
    if (path === '/api/v1/config/home-dashboards') return send(JSON.stringify({ queried: true, items: [], default: { explicit: false, path: '' } }));
    if (['/api/v1/radio', '/api/v1/proximity'].includes(path)) return send(JSON.stringify({ present: false }));
    if (path === '/api/v1/config/discovery') return send('{}');
    response.statusCode = 404; response.end('not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, url: `http://127.0.0.1:${server.address().port}`, strings };
}

const engines = [
  { name: 'chromium', type: chromium, launch: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'webkit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
];

async function themeRow(p) {
  const row = p.locator('#cfg-dashboard_theme');
  await row.waitFor();
  return row.evaluate((el) => {
    const select = el.querySelector('select');
    const help = el.querySelector('.flabel .fsum');
    const box = el.getBoundingClientRect();
    const helpBox = help.getBoundingClientRect();
    const selectBox = select.getBoundingClientRect();
    return {
      values: [...select.options].map((o) => o.value),
      labels: [...select.options].map((o) => o.textContent),
      selected: select.value,
      help: help.textContent,
      // Measured from the laid-out boxes, not scrollWidth: WebKit counts a select's own clipped
      // label as scrollable overflow of its container even though the control stays inside the row.
      rowOverflow: [...el.querySelectorAll('*')].some((child) => {
        const b = child.getBoundingClientRect();
        return b.width > 0 && (b.left < box.left - 1 || b.right > box.right + 1);
      }),
      helpInside: helpBox.left >= box.left - 1 && helpBox.right <= box.right + 1,
      selectInside: selectBox.left >= box.left - 1 && selectBox.right <= box.right + 1,
      pageOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
    };
  });
}

for (const engine of engines) {
  const engineTest = engine.available ? test : test.skip;
  engineTest(`${engine.name}: the Dashboard theme row offers Ambient and fits in every locale`, async (t) => {
    const browser = await engine.type.launch(engine.launch);
    t.after(() => browser.close());
    for (const locale of LOCALES) {
      const h = await harness(locale);
      try {
        for (const width of [360, 480, 1280]) {
          for (const font of [16, 24]) {
            const p = await browser.newPage({ viewport: { width, height: 900 } });
            await p.addInitScript((size) => {
              document.addEventListener('DOMContentLoaded', () => { document.documentElement.style.fontSize = `${size}px`; });
            }, font);
            await p.goto(h.url);
            await p.locator('#cfg-dashboard_fullscreen').waitFor();
            await p.evaluate(() => window.cfgTab(true));
            const r = await themeRow(p);
            const before = await p.evaluate(() => ({ y: scrollY, top: document.getElementById('cfg-dashboard_theme').getBoundingClientRect().top }));
            await p.locator('#cfg-dashboard_theme .info-btn').click();
            const pop = await p.evaluate(() => {
              const box = document.getElementById('cfg-help').getBoundingClientRect();
              return {
                text: document.getElementById('cfg-help-body').textContent,
                items: document.querySelectorAll('#cfg-help-body li').length,
                bold: document.querySelectorAll('#cfg-help-body strong').length,
                inside: box.width > 0 && box.left >= 0 && box.top >= 0 && box.right <= innerWidth + 0.5 && box.bottom <= innerHeight + 0.5,
                y: scrollY, top: document.getElementById('cfg-dashboard_theme').getBoundingClientRect().top,
              };
            });
            const where = `${locale} ${width}px ${font}px`;
            assert.deepEqual(r.values, OPTIONS, `${where}: wire values`);
            assert.equal(r.selected, 'Ambient', `${where}: the stored Ambient value is selected`);
            assert.deepEqual(r.labels, [
              text(h.strings, 'configure.enum.dashboard_theme.follow_home_assistant'),
              text(h.strings, 'configure.enum.dashboard_theme.dark'),
              text(h.strings, 'configure.enum.dashboard_theme.light'),
              text(h.strings, 'configure.enum.dashboard_theme.ambient'),
            ], `${where}: catalogued labels`);
            assert.equal(r.help, text(h.strings, 'settings.dashboard_theme.summary'), `${where}: summary`);
            // The help's markdown renders as structure: one list item per "- " line, one bold span per
            // **…** pair, and no marker left in the text a reader sees.
            const help = text(h.strings, 'settings.dashboard_theme.help');
            assert.equal(pop.items, (help.match(/^- /gm) || []).length, `${where}: help list items`);
            assert.equal(pop.bold, (help.match(/\*\*/g) || []).length / 2, `${where}: help bold option names`);
            assert.equal(/\*\*|^- /m.test(pop.text), false, `${where}: no markdown markers in the help`);
            assert.ok(pop.items > 0, `${where}: the theme help is a list`);
            assert.equal(pop.inside, true, `${where}: the help popover stays inside the viewport`);
            assert.deepEqual([pop.y, pop.top], [before.y, before.top], `${where}: opening the help does not move the page`);
            assert.equal(r.rowOverflow, false, `${where}: the row must not overflow`);
            assert.equal(r.helpInside, true, `${where}: the summary stays inside its row`);
            assert.equal(r.selectInside, true, `${where}: the select stays inside its row`);
            assert.equal(r.pageOverflow, false, `${where}: no horizontal page scroll`);
            await p.close();
          }
        }
      } finally {
        await new Promise((resolve) => h.server.close(resolve));
      }
    }
  });
}

for (const engine of engines) {
  const engineTest = engine.available ? test : test.skip;
  engineTest(`${engine.name}: Configure hides system-bar control when no usable bars exist`, async t => {
    const browser = await engine.type.launch(engine.launch);
    t.after(() => browser.close());
    for (const available of [false, true]) {
      const h = await harness('en', available);
      try {
        const p = await browser.newPage({ viewport: { width: 480, height: 480 } });
        await p.goto(h.url);
        await p.evaluate(() => window.cfgTab(true));
        await p.locator('#cfg-dashboard_theme').waitFor();
        assert.equal(await p.locator('#cfg-dashboard_fullscreen').count(), available ? 1 : 0);
        await p.close();
      } finally { await new Promise(resolve => h.server.close(resolve)); }
    }
  });
}
