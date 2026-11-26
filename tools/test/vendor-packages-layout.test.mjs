// Vendor packages rows keep their tag chips whole on a phone with enlarged text, with root (action buttons)
// and without it (the rows sit greyed inside a .locked wrapper, actions disabled), in both browser engines.
// The rows mirror VendorPackagesCard.kt tameRowHtml(); the stylesheet is the real info.css.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';

const root = join(process.cwd(), '..', '..', 'app', 'src', 'main', 'assets');
const css = readFileSync(join(root, 'info.css'), 'utf8');
const catalogue = (locale) => JSON.parse(readFileSync(join(root, 'i18n', `${locale}.json`), 'utf8')).strings;
const LOCALES = ['en', 'es', 'nl', 'uk'];

function row(t, { label, pkg, tags, recommended = false, tamed = false, disabled = false }) {
  const pills = (recommended && !tamed ? [`<span class="pill">${t('install.tame.badge.recommended')}</span>`] : [])
    .concat(tags.map((tag) => `<span class="pill">${t(`install.tame.tag.${tag}`)}</span>`)).map((p) => ` ${p}`).join('');
  const button = `<form method="post" action="api/v1/tame" style="margin:0"><button type="submit" style="white-space:nowrap"${disabled ? ' disabled' : ''}>${t(tamed ? 'install.tame.action.reenable' : 'install.tame.action.tame')}</button></form>`;
  return `<div style="display:flex;align-items:center;gap:10px;padding:9px 0;border-top:1px solid #222">
   <span style="flex:1;min-width:0;overflow:hidden">${label}${pills}<br><small>${pkg}</small></span>
   ${button}
  </div>`;
}

function page(locale, locked) {
  const strings = catalogue(locale);
  const english = catalogue('en');
  const t = (key) => (strings[key] ?? english[key]).text;
  const rows = [
    row(t, { label: 'Vendor launcher', pkg: 'com.example.vendor.launcher', tags: ['vendor'], recommended: true, disabled: locked }),
    row(t, { label: 'Vendor update service', pkg: 'com.example.vendor.updater', tags: ['vendor', 'overlay'], tamed: true, disabled: locked }),
  ].join('\n');
  return `<!doctype html><html lang="${locale}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <style>${css}</style><style>html{font-size:20px}</style></head><body><main class="wrap"><div class="cards">
    <div class="card" id="cfg-tame"><h2>${t('install.card.vendor_packages')}</h2>${locked ? `<div class="locked">${rows}</div>` : rows}</div>
    </div></main></body></html>`;
}

const server = createServer((request, response) => {
  const url = new URL(request.url, 'http://panel.test');
  if (url.pathname !== '/' || !LOCALES.includes(url.searchParams.get('locale'))) { response.statusCode = 404; response.end(); return; }
  response.setHeader('content-type', 'text/html');
  response.end(page(url.searchParams.get('locale'), url.searchParams.get('locked') === '1'));
});

for (const engine of [
  { name: 'Chromium', type: chromium, launch: { executablePath: process.env.CHROME || '/usr/bin/chromium', args: ['--no-sandbox'] } },
  { name: 'WebKit', type: webkit, launch: {} },
]) {
  test(`vendor package tag chips stay whole with and without root at 390 px with enlarged text in ${engine.name}`, { skip: engine.name === 'WebKit' && !existsSync(webkit.executablePath()) }, async () => {
    await new Promise((resolve) => server.listening ? resolve() : server.listen(0, '127.0.0.1', resolve));
    const base = `http://127.0.0.1:${server.address().port}`;
    const browser = await engine.type.launch(engine.launch);
    const problems = [];
    let chips = 0;
    try {
      const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
      for (const locale of LOCALES) for (const locked of [false, true]) {
        await page.goto(`${base}/?locale=${locale}&locked=${locked ? 1 : 0}`);
        const m = await page.evaluate(() => {
          const out = []; let count = 0;
          for (const pill of document.querySelectorAll('#cfg-tame .pill')) {
            count += 1;
            const box = pill.parentElement.getBoundingClientRect(); const r = pill.getBoundingClientRect();
            if (r.right > box.right + 0.5 || r.left < box.left - 0.5) out.push(`"${pill.textContent}" clipped by ${(r.right - box.right).toFixed(1)}px`);
          }
          if (document.documentElement.scrollWidth > innerWidth + 1) out.push(`page scrolls sideways by ${document.documentElement.scrollWidth - innerWidth}px`);
          return { out, count };
        });
        chips += m.count;
        for (const p of m.out) problems.push(`${locale} ${locked ? 'without root' : 'with root'}: ${p}`);
      }
    } finally {
      await browser.close();
    }
    assert.equal(chips, LOCALES.length * 2 * 4, 'every tag chip is rendered');
    assert.deepEqual(problems, []);
  });
}

test.after(() => server.close());
