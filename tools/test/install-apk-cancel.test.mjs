// The real Install page and transport in Panel Assistant's sidebar: an APK URL request waits for
// on-panel approval while Cancel gets its own response. Only an accepted cancellation ends that UI.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';

const chrome = process.env.CHROME || '/usr/bin/chromium';
const browserTest = existsSync(chrome) ? test : test.skip;
const preview = { ok: true, package: 'org.example.apk', version: '1.0', signer: 'abc123', token: 'old-preview' };
const SIDEBAR = `<!doctype html><html><body><iframe id="frame" src="/panel"></iframe><script>
window.fetches = []; window.cancels = [];
var frame = document.getElementById('frame');
function reply(command, answer) {
  frame.contentWindow.postMessage({ type: 'panel-assistant-ui', frame: Object.assign({ command_id: command.command_id }, answer) }, location.origin);
}
window.answerFetch = function (index, answer) { reply(window.fetches[index], answer); };
window.answerCancel = function (index, answer) { reply(window.cancels[index], answer); };
window.addEventListener('message', function (event) {
  var f = event.data && event.data.frame;
  if (event.source !== frame.contentWindow || !f) return;
  if (f.op === 'install.apk.from_url') { window.fetches.push(f); return; }
  if (f.op === 'install.apk.fetch.cancel') { window.cancels.push(f); return; }
  reply(f, { outcome: 'applied', result: { pending: false } });
});
</script></body></html>`;

async function rig(t, engine) {
  const source = await readFile(new URL('../../app/src/main/assets/install.js', import.meta.url), 'utf8');
  const server = createServer((request, response) => {
    const path = new URL(request.url, 'http://ha.test').pathname;
    if (path === '/install.js') {
      response.writeHead(200, { 'content-type': 'application/javascript' }); response.end(source); return;
    }
    response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    response.end(path === '/panel'
      ? `<!doctype html><html lang="en"><head>${i18nBridge({})}</head><body data-ui-relay data-embedded data-hardened>` +
        '<input id="apk-url" value="https://example.org/app.apk"><button id="fetch" onclick="apkFetchUrl()">Fetch APK</button><div id="apk-preview"></div><p id="apk-msg"></p>' +
        '<script>window.CardColumnAlignment={attach:()=>()=>{}};</script><script src="/install.js"></script></body></html>'
      : SIDEBAR);
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  let browser;
  t.after(async () => {
    try { await browser?.close(); }
    finally { server.closeAllConnections?.(); await new Promise((done) => server.close(done)); }
  });
  browser = await (engine === 'WebKit' ? webkit.launch({ headless: true }) : chromium.launch({ executablePath: chrome, headless: true }));
  const sidebar = await browser.newPage();
  await sidebar.goto(`http://127.0.0.1:${server.address().port}/`, { waitUntil: 'load' });
  const panel = sidebar.frameLocator('#frame');
  await panel.locator('#fetch').click();
  await sidebar.waitForFunction(() => window.fetches.length === 1);
  await panel.locator('#apk-msg').filter({ hasText: "panel's screen" }).waitFor({ timeout: 1000 });
  await panel.locator('#apk-preview button').filter({ hasText: /^Cancel$/ }).click();
  await sidebar.waitForFunction(() => window.cancels.length === 1);
  assert.equal(await sidebar.evaluate(() => window.cancels[0].args.request),
    await sidebar.evaluate(() => window.fetches[0].args.request), 'Cancel names the waiting URL fetch');
  return { sidebar, panel };
}

for (const engine of ['Chromium', 'WebKit']) {
  browserTest(`Accepted sidebar Cancel ends the approval wait and ignores its eventual APK preview in ${engine}`, async (t) => {
    const { sidebar, panel } = await rig(t, engine);
    await sidebar.evaluate(() => window.answerCancel(0, { outcome: 'applied', result: { ok: true, cancelled: true } }));
    await panel.locator('#apk-preview').filter({ hasText: /Download cancelled/ }).waitFor({ timeout: 1500 });
    assert.equal(await panel.locator('#apk-preview button').count(), 0, 'the waiting Cancel button is gone');
    assert.equal(await panel.locator('#apk-msg').textContent(), '');

    // An already queued old reply must not resurrect an installable token after accepted Cancel.
    await sidebar.evaluate((result) => window.answerFetch(0, { outcome: 'applied', result }), preview);
    await sidebar.locator('#frame').evaluate(async (frame) => {
      await new Promise((done) => frame.contentWindow.requestAnimationFrame(() => frame.contentWindow.requestAnimationFrame(done)));
    });
    assert.match(await panel.locator('#apk-preview').textContent(), /Download cancelled/);
    assert.equal(await panel.locator('[data-token]').count(), 0);

    await panel.locator('#fetch').click();
    await sidebar.waitForFunction(() => window.fetches.length === 2);
    await sidebar.evaluate((result) => window.answerFetch(1, { outcome: 'applied', result }), { ...preview, token: 'new-preview' });
    await panel.locator('button[data-token="new-preview"]').filter({ hasText: /Install/ }).waitFor({ timeout: 1500 });
  });

  browserTest(`An older Cancel answer preserves a newer sidebar fetch and its approval hint in ${engine}`, async (t) => {
    const { sidebar, panel } = await rig(t, engine);
    await panel.locator('#apk-url').fill('https://example.org/new.apk');
    await panel.locator('#fetch').click();
    await sidebar.waitForFunction(() => window.fetches.length === 2);
    await sidebar.evaluate(() => window.answerCancel(0, { outcome: 'applied', result: { ok: true, cancelled: true } }));
    await sidebar.locator('#frame').evaluate(async (frame) => {
      await new Promise((done) => frame.contentWindow.requestAnimationFrame(() => frame.contentWindow.requestAnimationFrame(done)));
    });
    assert.match(await panel.locator('#apk-msg').textContent(), /panel's screen/, 'the newer approval hint survives the older Cancel reply');
    await panel.locator('#apk-preview button').filter({ hasText: /^Cancel$/ }).click();
    await sidebar.waitForFunction(() => window.cancels.length === 2);
    assert.equal(await sidebar.evaluate(() => window.cancels[1].args.request),
      await sidebar.evaluate(() => window.fetches[1].args.request), 'Cancel still names the newer fetch');
    assert.notEqual(await sidebar.evaluate(() => window.cancels[1].args.request),
      await sidebar.evaluate(() => window.cancels[0].args.request));
    await sidebar.evaluate((result) => window.answerFetch(1, { outcome: 'applied', result }), { ...preview, token: 'new-preview' });
    await panel.locator('button[data-token="new-preview"]').filter({ hasText: /Install/ }).waitFor({ timeout: 1500 });
  });

  for (const [name, answer] of [
    ['unsuccessful', { outcome: 'applied', result: { ok: true, cancelled: false } }],
    ['refused', { outcome: 'refused', code: 'forbidden' }],
  ]) {
    browserTest(`A sidebar Cancel that is ${name} keeps the original fetch usable in ${engine}`, async (t) => {
      const { sidebar, panel } = await rig(t, engine);
      await sidebar.evaluate((answer) => window.answerCancel(0, answer), answer);
      await panel.locator('#apk-msg').filter({ hasText: 'Cancelling' }).waitFor({ state: 'hidden', timeout: 1500 });
      assert.equal(await panel.locator('#apk-preview button').filter({ hasText: /^Cancel$/ }).isVisible(), true);
      assert.doesNotMatch(await panel.locator('#apk-preview').textContent(), /cancelled/i);
      await sidebar.evaluate((result) => window.answerFetch(0, { outcome: 'applied', result }), preview);
      await panel.locator('button[data-token="old-preview"]').filter({ hasText: /Install/ }).waitFor({ timeout: 1500 });
    });
  }
}
