import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';

const script = await readFile(new URL('../../app/src/main/assets/buildwatch.js', import.meta.url), 'utf8');
const stylesheet = await readFile(new URL('../../app/src/main/assets/info.css', import.meta.url), 'utf8');
const chrome = process.env.CHROME || '/usr/bin/chromium';
const engines = [
  { name: 'Chromium', type: chromium, options: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'WebKit', type: webkit, options: {}, available: existsSync(webkit.executablePath()) },
];

async function fixture(t, engine, { notice = false, visible = false, width = 480 } = {}) {
  const browser = await engine.type.launch({ headless: true, ...engine.options });
  t.after(() => browser.close());
  const page = await browser.newPage({ viewport: { width, height: 700 } });
  let state = { notice, build: 'build-a', dismissOk: true };
  const requests = [];
  const heldHealth = [];
  let signalHeldHealth;
  const heldHealthStarted = new Promise((resolve) => { signalHeldHealth = resolve; });
  await page.route('http://panel.test/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    requests.push({ method: request.method(), path });
    if (path === '/health') {
      const body = `ha-paneld build=${state.build} cfg=cfg-a pa_notice=${state.notice ? 1 : 0}\n`;
      if (state.holdNextHealth) {
        state.holdNextHealth = false;
        await new Promise((release) => { heldHealth.push(release); signalHeldHealth(); });
      }
      return route.fulfill({ contentType: 'text/plain', body });
    }
    if (path === '/api/v1/migration-notice/dismiss') {
      if (state.dismissOk) state.notice = false;
      return route.fulfill({ status: state.dismissOk ? 200 : 503, contentType: 'application/json', body: JSON.stringify({ ok: state.dismissOk }) });
    }
    if (path === '/assets/buildwatch.js') return route.fulfill({ contentType: 'application/javascript', body: script });
    if (path === '/info.css') return route.fulfill({ contentType: 'text/css', body: stylesheet });
    if (path === '/') return route.fulfill({ contentType: 'text/html', body: `<!doctype html><html lang="en"><head><base href="/"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="info.css"></head><body data-build="build-a" data-cfg="cfg-a"><div class="wrap"><div id="halifebar" class="setup" style="display:none"></div><div id="hanetbar" class="setup" style="display:none"></div><div id="verbar" class="setup" style="display:none"></div><div id="migrationbar" class="setup" style="display:${visible ? '' : 'none'}">⚠ <b>Panel Assistant is required</b> Add the Panel Assistant integration in Home Assistant to manage this panel. MQTT support will be removed. <a href="https://panel-assistant.io/go/migration">Learn more</a> <button id="migration-dismiss" class="pbtn" type="button">Dismiss until the next update</button></div><main>Page</main></div><script src="assets/buildwatch.js"></script></body></html>` });
    return route.fulfill({ status: 404 });
  });
  await page.clock.install();
  await page.goto('http://panel.test/');
  await page.waitForTimeout(100);
  async function poll() {
    const response = page.waitForResponse((r) => new URL(r.url()).pathname === '/health');
    await page.clock.fastForward(10_100);
    await response;
    await page.waitForTimeout(100);
  }
  return { page, requests, poll, heldHealth, heldHealthStarted, update: (next) => { state = { ...state, ...next }; } };
}

for (const engine of engines) {
  const browserTest = engine.available ? test : test.skip;
  for (const width of [360, 1100]) {
    browserTest(`${engine.name} ${width}px: appears without Panel Assistant`, async (t) => {
      const rig = await fixture(t, engine, { notice: true, width });
      assert.equal(await rig.page.locator('#migrationbar').isVisible(), true);
      assert.match(await rig.page.locator('#migrationbar').innerText(), /Panel Assistant is required/);
      assert.equal(await rig.page.locator('#migrationbar a').getAttribute('href'), 'https://panel-assistant.io/go/migration');
      const geometry = await rig.page.locator('#migrationbar').evaluate((bar) => ({
        left: bar.getBoundingClientRect().left,
        right: bar.getBoundingClientRect().right,
        viewport: document.documentElement.clientWidth,
        scrollWidth: document.documentElement.scrollWidth,
      }));
      assert.ok(geometry.left >= 0 && geometry.right <= geometry.viewport, `notice outside viewport: ${JSON.stringify(geometry)}`);
      assert.ok(geometry.scrollWidth <= geometry.viewport, `horizontal overflow: ${JSON.stringify(geometry)}`);
    });

    browserTest(`${engine.name} ${width}px: clears when Panel Assistant connects`, async (t) => {
      const rig = await fixture(t, engine, { notice: true, visible: true, width });
      assert.equal(await rig.page.locator('#migrationbar').isVisible(), true);
      rig.update({ notice: false });
      await rig.poll();
      assert.equal(await rig.page.locator('#migrationbar').isVisible(), false);
    });

    browserTest(`${engine.name} ${width}px: returns after update`, async (t) => {
      const rig = await fixture(t, engine, { notice: true, visible: true, width });
      await rig.page.locator('#migrationbar button').click();
      await rig.page.waitForFunction(() => getComputedStyle(document.getElementById('migrationbar')).display === 'none');
      assert.ok(rig.requests.some((request) => request.method === 'POST' && request.path === '/api/v1/migration-notice/dismiss'));
      rig.update({ notice: true, build: 'build-b' });
      await rig.page.locator('body').evaluate((body) => { const input = document.createElement('input'); body.append(input); input.focus(); });
      await rig.poll();
      assert.equal(await rig.page.locator('#migrationbar').isVisible(), true);
      assert.equal(await rig.page.locator('#migrationbar button').isEnabled(), true);
      await rig.page.locator('#migrationbar button').click();
      await rig.page.waitForFunction(() => getComputedStyle(document.getElementById('migrationbar')).display === 'none');
    });

    browserTest(`${engine.name} ${width}px: failed dismissal keeps the notice`, async (t) => {
      const rig = await fixture(t, engine, { notice: true, visible: true, width });
      rig.update({ dismissOk: false });
      await rig.page.locator('#migrationbar button').click();
      await rig.page.waitForFunction(() => !document.querySelector('#migrationbar button').disabled);
      assert.equal(await rig.page.locator('#migrationbar').isVisible(), true);
      assert.ok(rig.requests.some((request) => request.method === 'POST' && request.path === '/api/v1/migration-notice/dismiss'));
    });

    browserTest(`${engine.name} ${width}px: an older health response cannot undo dismissal`, async (t) => {
      const rig = await fixture(t, engine, { notice: true, visible: true, width });
      rig.update({ holdNextHealth: true });
      const oldPoll = rig.page.waitForRequest((request) => new URL(request.url()).pathname === '/health');
      await rig.page.clock.fastForward(10_100);
      await oldPoll;
      await rig.heldHealthStarted;
      assert.equal(rig.heldHealth.length, 1, 'old health response must be held before dismissal');
      await rig.page.locator('#migrationbar button').click();
      await rig.page.waitForFunction(() => getComputedStyle(document.getElementById('migrationbar')).display === 'none');
      const oldResponse = rig.page.waitForResponse((response) => new URL(response.url()).pathname === '/health');
      rig.heldHealth[0]();
      await oldResponse;
      await rig.page.waitForTimeout(100);
      assert.equal(await rig.page.locator('#migrationbar').isVisible(), false);
    });
  }
}
