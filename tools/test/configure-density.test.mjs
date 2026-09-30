// Configure density, in both engines at a phone width and on a 480x480 panel: Basic is the default view
// and each card reveals only its own advanced rows, a card with no basic row stays out of Basic, every
// row carries a one-line summary that one switch removes, the filter finds any setting and never marks
// the form dirty, and the full help opens in a popover that renders its markdown, links to the website,
// stays on screen and never moves the page.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';

const root = join(process.cwd(), '..', '..', 'app', 'src', 'main', 'assets');
const chrome = process.env.CHROME || '/usr/bin/chromium';

function page() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css"></head><body><div class="wrap">
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <div id="cfg-tools" class="cfg-tools" data-app-version="9.9.9-rc1"><input id="cfg-filter" class="cfg-filter" type="search"><div class="cfg-seg" role="radiogroup"><label><input type="radio" name="cfg-tier" id="tier-basic" value="basic" checked>Basic</label><label><input type="radio" name="cfg-tier" id="tier-adv" value="advanced">Advanced</label></div><label class="cfg-desc-switch"><input type="checkbox" id="cfg-desc" checked><span class="cfg-desc-track"></span>Descriptions</label><span id="cfg-count" class="muted cfg-count"></span></div>
    <p id="cfg-msg"></p><p id="cfg-status"></p>
    <div id="cfg-groups" class="cards" data-card-size-page="configure" data-card-size-epoch="1" data-card-size-restore="1"></div>
    <div id="proximity-learning-mount"></div><div id="savebar" class="savebar" hidden><button id="savebtn"></button></div>
    <div id="cfg-help" class="cfg-help" popover="manual" role="dialog"><div class="cfg-help-head"><b id="cfg-help-title"></b><button id="cfg-help-close" class="cfg-help-close" type="button">x</button></div><div id="cfg-help-body" class="cfg-help-body"></div><div class="cfg-help-foot"><a id="cfg-help-more">More on the website</a></div></div>
    </div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    <script src="/card-size-memory.js"></script><script src="/configure-state.js"></script><script src="/configure-view.js"></script><script src="/configure-help.js"></script><script src="/configure-controls.js"></script><script src="/configure-brightness.js"></script><script src="/configure-auto-sleep.js"></script><script src="/configure-cards.js"></script><script src="/configure-render.js"></script><script src="/configure.js"></script>
  </body></html>`;
}

const MARKDOWN_HELP = 'Wake-word detector threshold.\n\n- **Low** requires a clearer match.\n- **High** matches more readily.';
const LONG = 'A deliberately long help paragraph that a phone would wrap across several lines. '.repeat(8).trim();
function field(key, label, group, tier, extra = {}) {
  return { key, label, group, tier, type: 'BOOL', available: true, summary: `${label} in one line.`, help: `${label} help text.`, ...extra };
}
const SCHEMA = [
  field('touch_sound', 'Touch sound', 'Behaviour', 'BASIC'),
  field('watchdog_enabled', 'App watchdog', 'Behaviour', 'BASIC'),
  field('silence_boot_chime', 'Silence boot chime', 'Behaviour', 'ADVANCED', { help: LONG }),
  field('prevent_idle_dim', 'Prevent idle dim', 'Behaviour', 'ADVANCED'),
  field('voice_enabled', 'Voice assistant', 'Voice', 'BASIC'),
  field('voice_sensitivity', 'Wake sensitivity with a label long enough to wrap on a phone', 'Voice', 'ADVANCED', { help: MARKDOWN_HELP }),
  field('camera_enabled', 'Camera', 'Camera', 'ADVANCED'),
  field('camera_fps', 'Frame rate', 'Camera', 'ADVANCED', { summary: 'Default frame rate; a stream URL can override it.', help: 'Override it with ?fps= in a stream URL.' }),
];

async function harness() {
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const send = (body, type = 'application/json') => { response.setHeader('content-type', type); response.end(body); };
    if (path === '/') return send(page(), 'text/html');
    if (['/configure-state.js', '/configure-view.js', '/configure-help.js', '/configure-controls.js', '/configure-brightness.js', '/configure-auto-sleep.js', '/configure-cards.js', '/configure-render.js', '/configure.js', '/card-size-memory.js'].includes(path)) return send(await readFile(join(root, path.slice(1)), 'utf8'), 'application/javascript');
    if (path === '/info.css') return send(await readFile(join(root, 'info.css'), 'utf8'), 'text/css');
    if (path === '/api/v1/config/schema') return send(JSON.stringify(SCHEMA));
    if (path === '/api/v1/config') return send(JSON.stringify({ settings: Object.fromEntries(SCHEMA.map((f) => [f.key, 'false'])), ha_expose: {}, ha_auth: { configured: false } }));
    if (path === '/api/v1/apps') return send(JSON.stringify({ apps: [] }));
    if (['/api/v1/radio', '/api/v1/proximity'].includes(path)) return send(JSON.stringify({ present: false }));
    if (path === '/api/v1/config/discovery' || path === '/api/v1/config/home-dashboards') return send('{}');
    response.statusCode = 404; response.end('not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, url: `http://127.0.0.1:${server.address().port}` };
}

const engines = [
  { name: 'chromium', type: chromium, launch: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'webkit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
];
const VIEWPORTS = [{ width: 390, height: 844 }, { width: 480, height: 480 }];

const rowIds = (p) => p.locator('#cfg-groups .frow').evaluateAll((els) => els.map((el) => el.id));
const card = (p, group) => p.locator(`[data-config-group="${group}"]`);

for (const engine of engines) {
  const engineTest = engine.available ? test : test.skip;
  for (const viewport of VIEWPORTS) {
    const where = `${engine.name} ${viewport.width}x${viewport.height}`;

    engineTest(`${where}: Basic first, a per-card reveal, summaries and the Descriptions switch`, async (t) => {
      const h = await harness();
      const browser = await engine.type.launch(engine.launch);
      t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
      const p = await browser.newPage({ viewport });
      await p.goto(h.url);
      await p.locator('#cfg-touch_sound').waitFor();

      assert.deepEqual(await rowIds(p), ['cfg-touch_sound', 'cfg-watchdog_enabled', 'cfg-voice_enabled']);
      assert.equal(await card(p, 'Camera').count(), 0, 'a card with no basic row is not rendered in Basic');
      assert.equal(await p.locator('#cfg-groups').textContent().then((t) => /hidden cards?/i.test(t)), false);
      assert.equal(await card(p, 'Behaviour').locator('.cfg-more').textContent(), 'Show 2 advanced settings');
      assert.equal(await card(p, 'Voice').locator('.cfg-more').textContent(), 'Show 1 advanced setting');
      assert.equal(await p.locator('#cfg-count').textContent(), '3 of 8 settings');
      assert.equal(await p.locator('#cfg-touch_sound .fsum').textContent(), 'Touch sound in one line.');

      // The info button sits level with its label: its centre within 1px of the label's capital-letter centre.
      const levels = await p.evaluate(() => [...document.querySelectorAll('.flabel-tail')].map((tail) => {
        const word = tail.firstElementChild, button = tail.querySelector('.info-btn');
        const style = getComputedStyle(word), ink = document.createElement('canvas').getContext('2d');
        ink.font = style.font; const cap = ink.measureText('H');
        const range = document.createRange(); range.selectNodeContents(word); const lines = range.getClientRects(); const line = lines[lines.length - 1];
        const capMiddle = line.bottom - cap.fontBoundingBoxDescent - cap.actualBoundingBoxAscent / 2;
        const box = button.getBoundingClientRect();
        return Math.abs(box.top + box.height / 2 - capMiddle);
      }));
      assert.ok(levels.length > 0 && Math.max(...levels) <= 1, `info buttons sit level with their labels (worst ${Math.max(...levels).toFixed(1)}px)`);

      await card(p, 'Behaviour').locator('.cfg-more').click();
      assert.deepEqual(await rowIds(p), ['cfg-touch_sound', 'cfg-watchdog_enabled', 'cfg-silence_boot_chime', 'cfg-prevent_idle_dim', 'cfg-voice_enabled'],
        'the reveal shows only that card\'s advanced rows');
      assert.equal(await card(p, 'Behaviour').locator('.cfg-more').textContent(), 'Hide advanced settings');
      assert.equal(await card(p, 'Behaviour').getAttribute('data-layout-key'), 'configure-behaviour.adv', 'the reveal joins the card key');
      assert.equal(await p.locator('#cfg-silence_boot_chime .adv-mark').count(), 1, 'an advanced row carries the diamond');
      assert.equal(await p.locator('#cfg-touch_sound .adv-mark').count(), 0);

      await p.locator('.cfg-desc-switch').click();
      assert.equal(await p.locator('#cfg-touch_sound .fsum').isVisible(), false, 'Descriptions off leaves labels only');
      assert.equal(await p.locator('#cfg-groups').getAttribute('data-card-size-context'), 'basic.labels');

      await p.locator('label:has(#tier-adv)').click();
      assert.equal((await rowIds(p)).length, 8, 'Advanced shows every row');
      assert.equal(await card(p, 'Camera').count(), 1);
      assert.equal(await p.locator('.cfg-more').count(), 0, 'Advanced has no per-card reveal');

      // The view is remembered per browser; the reveal and the tier survive a reload.
      await p.reload();
      await p.locator('#cfg-camera_fps').waitFor();
      assert.equal(await p.locator('#tier-adv').isChecked(), true);
      assert.equal(await p.locator('#cfg-desc').isChecked(), false);
      assert.equal(await p.locator('#savebar').isHidden(), true, 'view changes never mark the form dirty');
    });

    engineTest(`${where}: the filter finds any setting by label, summary or help and stays out of the form and memory`, async (t) => {
      const h = await harness();
      const browser = await engine.type.launch(engine.launch);
      t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
      const p = await browser.newPage({ viewport });
      await p.goto(h.url);
      await p.locator('#cfg-touch_sound').waitFor();
      const storedBefore = await p.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('ha-paneld.card-sizes')).sort());

      await p.locator('#cfg-filter').fill('fps=');
      await p.waitForFunction(() => document.querySelectorAll('#cfg-groups .frow').length === 1);
      assert.deepEqual(await rowIds(p), ['cfg-camera_fps'], 'help text finds an advanced row in an all-advanced card');
      await p.locator('#cfg-filter').fill('stream url');
      await p.waitForFunction(() => document.getElementById('cfg-camera_fps') && document.querySelectorAll('#cfg-groups .frow').length === 1);
      await p.locator('#cfg-filter').fill('watchdog');
      await p.waitForFunction(() => document.getElementById('cfg-watchdog_enabled') && document.querySelectorAll('#cfg-groups .frow').length === 1);
      assert.equal(await p.locator('.cfg-more').count(), 0, 'no reveal while filtering');
      await p.locator('#cfg-filter').fill('no such setting');
      await p.waitForFunction(() => !document.querySelector('#cfg-groups .frow'));
      assert.equal(await p.locator('#cfg-status').isVisible(), true);
      assert.equal(await p.locator('#savebar').isHidden(), true, 'filter text never marks the form dirty');
      await p.waitForTimeout(1600);
      const storedAfter = await p.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('ha-paneld.card-sizes')).sort());
      const filtered = await p.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('ha-paneld.card-sizes'))
        .some((k) => /fps|watchdog/.test(localStorage.getItem(k))));
      assert.equal(filtered, false, 'filter text never enters the card memory');
      assert.ok(storedAfter.every((k) => storedBefore.includes(k) || /\.basic$/.test(k)), `no filtered context stored: ${storedAfter}`);

      await p.locator('#cfg-filter').press('Escape');
      await p.waitForFunction(() => document.querySelectorAll('#cfg-groups .frow').length === 3);
    });

    engineTest(`${where}: the help popover renders markdown, links out, stays on screen and never moves the page`, async (t) => {
      const h = await harness();
      const browser = await engine.type.launch(engine.launch);
      t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
      const p = await browser.newPage({ viewport });
      await p.goto(`${h.url}/#cfg-voice_sensitivity`);
      await p.locator('#cfg-voice_sensitivity').waitFor();
      assert.equal(await card(p, 'Voice').locator('.cfg-more').textContent(), 'Hide advanced settings',
        'a deep link to an advanced setting reveals its card');
      await p.waitForTimeout(1500);

      // The last word of a wrapping label, the diamond and the info button share one line at every
      // width the label can take, so the button never sits on a line by itself.
      const spread = await p.locator('#cfg-voice_sensitivity .flabel').evaluate((label) => {
        const tail = label.querySelector('.flabel-tail');
        let worst = 0;
        for (let width = 90; width <= 360; width += 2) {
          label.style.width = `${width}px`; label.style.flex = 'none';
          const middles = [...tail.children].map((n) => { const b = n.getBoundingClientRect(); return b.top + b.height / 2; });
          worst = Math.max(worst, Math.max(...middles) - Math.min(...middles));
        }
        label.style.width = ''; label.style.flex = '';
        return Math.round(worst);
      });
      assert.ok(spread <= 6, `${where}: last word, diamond and info button stay on one line (${spread}px)`);

      const row = p.locator('#cfg-voice_sensitivity');
      const before = await p.evaluate(() => ({ y: scrollY, top: document.getElementById('cfg-voice_sensitivity').getBoundingClientRect().top }));
      await row.locator('.info-btn').click();
      const opened = await p.evaluate(() => {
        const pop = document.getElementById('cfg-help'), box = pop.getBoundingClientRect(), body = document.getElementById('cfg-help-body');
        return {
          y: scrollY, top: document.getElementById('cfg-voice_sensitivity').getBoundingClientRect().top,
          inside: box.width > 0 && box.left >= 0 && box.top >= 0 && box.right <= innerWidth + 0.5 && box.bottom <= innerHeight + 0.5,
          bottomGap: innerHeight - box.bottom,
          strong: [...body.querySelectorAll('li strong')].map((n) => n.textContent),
          paragraphs: body.querySelectorAll('p').length,
          markers: /\*\*|^- /m.test(body.textContent),
          more: new URL(document.getElementById('cfg-help-more').href),
          expanded: document.querySelector('#cfg-voice_sensitivity .info-btn').getAttribute('aria-expanded'),
          overscroll: getComputedStyle(pop).overscrollBehaviorY || getComputedStyle(pop).overscrollBehavior,
        };
      });
      assert.deepEqual([opened.y, opened.top], [before.y, before.top], 'opening the help does not move the page');
      assert.equal(opened.inside, true, 'the popover stays inside the viewport');
      if (viewport.width < 600) assert.ok(opened.bottomGap >= 8 && opened.bottomGap <= 60, `bottom sheet under 600px (${opened.bottomGap}px from the bottom)`);
      assert.deepEqual(opened.strong, ['Low', 'High']);
      assert.equal(opened.paragraphs, 1);
      assert.equal(opened.markers, false, 'no markdown markers reach the reader');
      assert.equal(opened.more.origin + opened.more.pathname, 'https://panel-assistant.io/go/settings');
      assert.equal(opened.more.searchParams.get('section'), 'voice_sensitivity');
      assert.equal(opened.more.searchParams.get('v'), '9.9.9-rc1');
      assert.equal(opened.expanded, 'true');
      assert.equal(opened.overscroll, 'contain');

      // Scrolling inside a long help never hands the scroll on to the page.
      await p.keyboard.press('Escape');
      assert.equal(await p.locator('#cfg-help').isVisible(), false, 'Escape closes the help');
      await card(p, 'Behaviour').locator('.cfg-more').click();
      await p.locator('#cfg-silence_boot_chime .info-btn').click();
      const y0 = await p.evaluate(() => scrollY);
      await p.locator('#cfg-help-body').hover();
      await p.mouse.wheel(0, 2000);
      await p.waitForTimeout(300);
      assert.equal(await p.evaluate(() => scrollY), y0, 'wheel inside the help does not scroll the page');
      await p.locator('#cfg-help-close').click();
      assert.equal(await p.locator('#cfg-help').isVisible(), false, 'the close button closes a pinned help');
      assert.equal(await p.locator('#savebar').isHidden(), true);
    });
  }
}
