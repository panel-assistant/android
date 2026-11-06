import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';

const assets = new URL('../../app/src/main/assets/', import.meta.url);
const state = await readFile(new URL('configure-state.js', assets), 'utf8');
const help = await readFile(new URL('configure-help.js', assets), 'utf8');
const engines = [
  { name: 'chromium', type: chromium, launch: { executablePath: process.env.CHROME || '/usr/bin/chromium', args: ['--no-sandbox'] } },
  { name: 'webkit', type: webkit, launch: {} },
];

// Load the production help button and popover at an HA-shaped document URL. The endpoint attribute
// is the panel-rendered Configure context; neither base rewriting nor the sidebar sandbox changes it.
for (const engine of engines) for (const embedded of [false, true]) {
  test(`${engine.name}: ${embedded ? 'sidebar' : 'direct'} camera help uses panel endpoint including IPv6`, async t => {
    const browser = await engine.type.launch(engine.launch);
    t.after(() => browser.close());
    const page = await browser.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    for (const [panelUrl, expected] of [
      ['http://192.0.2.10:8888/', 'rtsp://192.0.2.10:8554/live'],
      ['http://[fd00::1234]:8888/', 'rtsp://[fd00::1234]:8554/live'],
      ['', embedded ? null : 'rtsp://panel.test:8554/live'],
    ]) {
      const documentUrl = embedded ? 'https://ha.test/api/panel_assistant/embed/token/configure' : 'http://panel.test:8888/configure';
      await page.route('**/*', route => route.fulfill({ contentType: 'text/html',
        headers: embedded ? { 'Content-Security-Policy': "sandbox allow-scripts allow-forms allow-popups" } : {},
        body: `<!doctype html><html><head>${i18nBridge()}</head><body ${embedded ? 'data-embedded data-ui-relay' : ''}>
        <div id="cfg-help" data-panel-url="${panelUrl}"><b id="cfg-help-title"></b><button id="cfg-help-close">Close</button><div id="cfg-help-body"></div><a id="cfg-help-more"></a></div>
        <script>${state}</script><script>${help}</script><script>
        document.body.appendChild(ConfigurePage.helpButton({key:'camera_enabled',label:'Camera',help:'Watch the panel camera over RTSP.'}));
        </script></body></html>` }));
      await page.goto(documentUrl);
      await page.getByRole('button', { name: 'About Camera' }).click();
      const link = page.locator('#cfg-help-body a');
      assert.equal(await link.count(), expected ? 1 : 0);
      if (expected) {
        assert.equal(await link.getAttribute('href'), expected);
        assert.equal(await link.getAttribute('title'), expected);
        assert.equal(await link.textContent(), 'RTSP');
      }
      assert.equal(await page.locator('#cfg-help-body').textContent(), 'Watch the panel camera over RTSP.');
      await page.unroute('**/*');
    }
    assert.deepEqual(errors, []);
  });
}
