// Render the real Configure script and page stylesheet in both browser engines. The status card uses
// the same catalogue text and card structure as the server-generated Dashboard Networking card.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { existsSync, readFileSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';

const root = join(process.cwd(), '..', '..', 'app', 'src', 'main', 'assets');
const english = JSON.parse(readFileSync(join(root, 'i18n', 'en.json'), 'utf8')).strings;
const wording = (key) => english[key].text;

function page(kind) {
  const content = kind === 'configure'
    ? `<span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
      <button id="tab-basic"></button><button id="tab-adv"></button><p id="cfg-msg"></p><p id="cfg-status"></p>
      <div id="cfg-groups" class="cards"></div><div id="proximity-learning-mount"></div>
      <div id="savebar" hidden><button id="savebtn"></button></div>
      <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
      <script src="/configure.js"></script><script src="/proximity-learning.js"></script>`
    : `<div class="cards"><section class="card" id="nettbl"><h2>${wording('dashboard.card.networking')}</h2>
      <table><tbody><tr><th>HA network path</th><td>warning</td></tr></tbody></table>
      <p class="note" id="guidance">${wording('dashboard.networking.warning_guidance')}</p></section></div>`;
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css"></head><body><main class="wrap">${content}</main></body></html>`;
}

async function harness() {
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const send = (body, type = 'application/json') => { response.setHeader('content-type', type); response.end(body); };
    if (path === '/configure' || path === '/status') return send(page(path.slice(1)), 'text/html');
    if (path === '/configure.js' || path === '/proximity-learning.js')
      return send(await readFile(join(root, path.slice(1)), 'utf8'), 'application/javascript');
    if (path === '/info.css') return send(await readFile(join(root, 'info.css'), 'utf8'), 'text/css');
    if (path === '/api/v1/config/schema') return send(JSON.stringify([{
      key: 'dashboard_network_warning', type: 'BOOL', group: 'Display', tier: 'BASIC', available: true,
      label: wording('settings.dashboard_network_warning.label'),
      help: wording('settings.dashboard_network_warning.help'),
      summary: wording('settings.dashboard_network_warning.summary'), default: 'true',
    }]));
    if (path === '/api/v1/config') return send(JSON.stringify({
      settings: { dashboard_network_warning: false, dashboard_package: 'builtin' },
      ha_expose: {}, ha_auth: { configured: false },
    }));
    if (path === '/api/v1/apps') return send(JSON.stringify({ apps: [] }));
    if (path === '/api/v1/config/home-dashboards') return send(JSON.stringify({ queried: true, items: [], default: { explicit: false, path: '' } }));
    if (path === '/api/v1/config/discovery') return send('{}');
    if (path === '/api/v1/radio' || path === '/api/v1/proximity') return send(JSON.stringify({ present: false }));
    response.statusCode = 404; response.end('not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, url: `http://127.0.0.1:${server.address().port}` };
}

for (const engine of [
  { name: 'Chromium', type: chromium, launch: { executablePath: process.env.CHROME || '/usr/bin/chromium', args: ['--no-sandbox'] } },
  { name: 'WebKit', type: webkit, launch: {} },
]) {
  const browserTest = existsSync(engine.launch.executablePath || engine.type.executablePath()) ? test : test.skip;
  browserTest(`${engine.name}: network setting and status guidance fit panel and desktop layouts`, async (t) => {
    const h = await harness();
    const browser = await engine.type.launch(engine.launch);
    t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
    for (const width of [320, 480, 1280]) for (const font of [16, 24]) {
      for (const kind of ['configure', 'status']) {
        const p = await browser.newPage({ viewport: { width, height: 800 } });
        await p.goto(`${h.url}/${kind}`);
        await p.evaluate((size) => { document.documentElement.style.fontSize = `${size}px`; }, font);
        const target = kind === 'configure' ? p.locator('#cfg-dashboard_network_warning') : p.locator('#guidance');
        await target.waitFor();
        if (kind === 'configure') {
          assert.equal(await target.locator('[role=switch]').getAttribute('aria-checked'), 'false');
          assert.ok((await target.textContent()).includes(wording('settings.dashboard_network_warning.summary')));
        } else {
          assert.equal(await target.textContent(), wording('dashboard.networking.warning_guidance'));
        }
        const geometry = await target.evaluate((node) => {
          const box = node.getBoundingClientRect();
          const card = node.closest('.card').getBoundingClientRect();
          return { inside: box.left >= card.left - 1 && box.right <= card.right + 1,
            scroll: document.documentElement.scrollWidth - document.documentElement.clientWidth };
        });
        assert.equal(geometry.inside, true, `${engine.name}/${kind}/${width}/${font}: card containment`);
        assert.ok(geometry.scroll <= 1, `${engine.name}/${kind}/${width}/${font}: no horizontal scroll (${geometry.scroll}px)`);
        await p.close();
      }
    }
  });
}
