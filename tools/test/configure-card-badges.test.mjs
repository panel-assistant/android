// Render Configure card maturity badges through production scripts, CSS and localized catalogues.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';

const root = join(process.cwd(), '..', '..', 'app', 'src', 'main', 'assets');
const chrome = process.env.CHROME || '/usr/bin/chromium';

function page(projection) {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css"></head><body><div class="wrap">
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <div id="cfg-tools" class="cfg-tools" data-app-version="9.9.9-rc1"><input id="cfg-filter" class="cfg-filter" type="search"><div class="cfg-seg" role="radiogroup"><label><input type="radio" name="cfg-tier" id="tier-basic" value="basic" checked>Basic</label><label><input type="radio" name="cfg-tier" id="tier-adv" value="advanced">Advanced</label></div><label class="cfg-desc-switch"><input type="checkbox" id="cfg-desc" checked><span class="cfg-desc-track"></span>Descriptions</label><span id="cfg-count" class="muted cfg-count"></span></div>
    <p id="cfg-msg"></p><p id="cfg-status"></p>
    <div id="cfg-groups" class="cards" data-card-size-page="configure" data-card-size-epoch="1" data-card-size-restore="1"></div>
    <div id="proximity-learning-mount"></div><div id="savebar" class="savebar" hidden><button id="savebtn"></button></div>
    <div id="cfg-help" class="cfg-help" popover="manual" role="dialog"><div class="cfg-help-head"><b id="cfg-help-title"></b><button id="cfg-help-close" class="cfg-help-close" type="button">x</button></div><div id="cfg-help-body" class="cfg-help-body"></div><div class="cfg-help-foot"><a id="cfg-help-more">More on the website</a></div></div>
    </div>
    <script id="ha-i18n" type="application/json">${JSON.stringify(projection)}</script><script src="/i18n.js"></script><script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    <script src="/card-size-memory.js"></script><script src="/configure-state.js"></script><script src="/configure-view.js"></script><script src="/configure-help.js"></script><script src="/configure-controls.js"></script><script src="/configure-brightness.js"></script><script src="/configure-auto-sleep.js"></script><script src="/configure-cards.js"></script><script src="/configure-render.js"></script><script src="/configure.js"></script>
  </body></html>`;
}

const SCHEMA = [
  { key: 'display_test', label: 'Display setting', group: 'Display', tier: 'BASIC', type: 'BOOL', available: true, summary: 'Display setting.', help: 'Display help.' },
  { key: 'voice_enabled', label: 'Voice assistant', group: 'Voice', tier: 'BASIC', type: 'BOOL', available: true, summary: 'Voice setting.', help: 'Voice help.' },
];

async function harness(locale) {
  const projection = JSON.parse(await readFile(join(root, "i18n", `${locale}.json`), "utf8"));
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const send = (body, type = 'application/json') => { response.setHeader('content-type', type); response.end(body); };
    if (path === '/') return send(page(projection), 'text/html');
    if (['/i18n.js', '/configure-state.js', '/configure-view.js', '/configure-help.js', '/configure-controls.js', '/configure-brightness.js', '/configure-auto-sleep.js', '/configure-cards.js', '/configure-render.js', '/configure.js', '/card-size-memory.js'].includes(path)) return send(await readFile(join(root, path.slice(1)), 'utf8'), 'application/javascript');
    if (path === '/info.css') return send(await readFile(join(root, 'info.css'), 'utf8'), 'text/css');
    if (path === '/api/v1/config/schema') return send(JSON.stringify(SCHEMA));
    if (path === '/api/v1/config') return send(JSON.stringify({ settings: Object.fromEntries(SCHEMA.map((f) => [f.key, 'false'])), ha_expose: {}, ha_auth: { configured: false } }));
    if (path === '/api/v1/apps') return send(JSON.stringify({ apps: [] }));
    if (['/api/v1/radio', '/api/v1/proximity'].includes(path)) return send(JSON.stringify({ present: false }));
    if (path === '/api/v1/config/discovery' || path === '/api/v1/config/home-dashboards') return send('{}');
    response.statusCode = 404; response.end('not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, projection, url: `http://127.0.0.1:${server.address().port}` };
}

const engines = [
  { name: 'chromium', type: chromium, launch: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'webkit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
];
const LOCALES = ['en', 'de', 'es', 'fr', 'it', 'nl', 'pl', 'uk', 'zh-Hans'];
for (const engine of engines) {
  const engineTest = engine.available ? test : test.skip;
  engineTest(`${engine.name} 390px: Display has no maturity badge; Voice has a translated, styled Preview badge without overflow`, async (t) => {
    const browser = await engine.type.launch(engine.launch);
    t.after(() => browser.close());
    for (const locale of LOCALES) {
      const h = await harness(locale);
      const p = await browser.newPage({ viewport: { width: 390, height: 844 } });
      try {
        await p.goto(h.url);
        await p.locator('#cfg-voice_enabled').waitFor();
        assert.equal(await p.locator('[data-config-group="Display"] h2 .cardbadge').count(), 0, `${locale}: Display has no badge`);
        const badge = p.locator('[data-config-group="Voice"] h2 .cardbadge');
        assert.equal(await badge.count(), 1, `${locale}: Voice has one badge`);
        const expected = h.projection.strings['configure.badge.preview']?.text;
        assert.ok(expected, `${locale}: catalogue supplies Preview`);
        if (locale === 'en') assert.equal(expected, 'preview');
        assert.equal(await badge.textContent(), expected, `${locale}: Preview is translated`);
        assert.equal(await badge.isVisible(), true);
        const rendered = await badge.evaluate((el) => {
          const style = getComputedStyle(el), box = el.getBoundingClientRect(), heading = el.closest('h2').getBoundingClientRect();
          return { background: style.backgroundColor, color: style.color, border: parseFloat(style.borderTopWidth), radius: parseFloat(style.borderTopLeftRadius), inside: box.left >= heading.left && box.right <= heading.right + 0.5, overflow: document.documentElement.scrollWidth > innerWidth };
        });
        assert.notEqual(rendered.background, 'rgba(0, 0, 0, 0)', `${locale}: badge has a visible background`);
        assert.notEqual(rendered.color, rendered.background, `${locale}: badge text contrasts with its background`);
        assert.ok(rendered.border > 0 && rendered.radius > 0, `${locale}: badge has a border and rounded shape`);
        assert.equal(rendered.inside, true, `${locale}: badge fits its heading`);
        assert.equal(rendered.overflow, false, `${locale}: no horizontal page overflow`);
        const heights = await badge.evaluate((el) => {
          const card = el.closest('.card'), heading = el.closest('h2');
          const before = { card: card.getBoundingClientRect().height, heading: heading.getBoundingClientRect().height };
          el.remove();
          return { before, after: { card: card.getBoundingClientRect().height, heading: heading.getBoundingClientRect().height } };
        });
        assert.deepEqual(heights.before, heights.after, `${locale}: the badge leaves card-height hints valid`);
      } finally {
        await p.close();
        await new Promise((resolve) => h.server.close(resolve));
      }
    }
  });
}
