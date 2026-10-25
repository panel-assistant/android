// A backup or restore sent from Panel Assistant's sidebar to a Hardened panel: the real install.js and transport.js
// in a frame whose parent stands in for the sidebar relay. Panel Assistant answers a relayed request once, after the
// panel's approval ends, so the page must say at once to approve it on the panel, and an approval that expires or
// is denied must end with a clear message and a usable button rather than an endless "Restoring…".
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';

const asset = fileURLToPath(new URL('../../app/src/main/assets/install.js', import.meta.url));
const chrome = process.env.CHROME || '/usr/bin/chromium';
const browserTest = existsSync(chrome) ? test : test.skip;

// The sidebar: relays frames from its frame, answers blob.upload with a PUT target and the restore preview at
// once, and holds the real restore until the test answers it, as Panel Assistant holds it for the approval.
const SIDEBAR = `<!doctype html><html><body><iframe id="frame" src="/panel"></iframe><script>
window.held = null; window.frames_seen = [];
var frame = document.getElementById('frame');
function reply(answer) { frame.contentWindow.postMessage({ type: 'panel-assistant-ui', frame: answer }, location.origin); }
window.answerHeld = function (answer) { var id = window.held.command_id; window.held = null; answer.command_id = id; reply(answer); };
window.addEventListener('message', function (event) {
  var f = event.data && event.data.frame;
  if (event.source !== frame.contentWindow || !f) return;
  if (f.op === 'blob.upload' || f.op === 'restore' || f.op === 'backup') window.frames_seen.push(f.op);
  if (f.op === 'blob.upload') return reply({ command_id: f.command_id, outcome: 'applied', result: { url: location.origin + '/blob/t' } });
  if (f.op === 'restore' && f.args.dry_run) return reply({ command_id: f.command_id, outcome: 'applied', result: { ok: true, dry_run: true, panel_id: 'office', config_keys: 89, companion_files: 0 } });
  if (f.op === 'restore' || f.op === 'backup') { window.held = f; return; }
  reply({ command_id: f.command_id, outcome: 'refused', code: 'unknown_command' });
});
</script></body></html>`;

async function rig(t, engine, action = 'restore') {
  const source = await readFile(asset, 'utf8');
  const server = createServer((request, response) => {
    const path = new URL(request.url, 'http://ha.test').pathname;
    if (path === '/install.js') { response.writeHead(200, { 'content-type': 'application/javascript' }); response.end(source); return; }
    if (path === '/blob/t') { request.resume(); request.on('end', () => { response.writeHead(200); response.end(); }); return; }
    response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    if (path === '/panel') {
      response.end(`<!doctype html><html lang="en"><head>${i18nBridge({})}</head><body data-ui-relay data-embedded data-hardened>` +
        '<input id="bk-pw" value="secret"><button id="bk-download" onclick="doBackup(this)">Download</button><input type="file" id="rs-file" onchange="restorePick(this)"><input id="rs-pw" value="secret"><div id="rs-preview"></div><p id="bk-msg"></p>' +
        '<script>window.CardColumnAlignment={attach:()=>()=>{}};</script><script src="/install.js"></script></body></html>');
      return;
    }
    response.end(SIDEBAR);
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  let browser;
  t.after(async () => {
    try { await browser?.close(); }
    finally { server.closeAllConnections?.(); await new Promise((done) => server.close(done)); }
  });
  browser = await (engine === 'WebKit' ? webkit.launch({ headless: true }) : chromium.launch({ executablePath: chrome, headless: true }));
  const sidebar = await browser.newPage();
  sidebar.on('dialog', (dialog) => dialog.accept());
  await sidebar.goto(`http://127.0.0.1:${server.address().port}/`, { waitUntil: 'load' });
  const panel = sidebar.frameLocator('#frame');
  if (action === 'backup') await panel.locator('#bk-download').click();
  else {
    await panel.locator('#rs-file').setInputFiles({ name: 'panel.hpb', mimeType: 'application/octet-stream', buffer: Buffer.from('sealed backup') });
    await panel.locator('#rs-preview button').click();
  }
  await sidebar.waitForFunction(() => window.held !== null);
  return { sidebar, panel };
}

for (const engine of ['Chromium', 'WebKit']) {
  browserTest(`A held sidebar restore says at once to approve it on the panel and ends clearly when the approval expires in ${engine}`, async (t) => {
    const { sidebar, panel } = await rig(t, engine);
    const message = panel.locator('#bk-msg');
    // Well inside the time a person waits before wondering: the page knows the panel is Hardened, so no guess.
    await assert.doesNotReject(message.filter({ hasText: "panel's screen" }).waitFor({ timeout: 1000 }),
      'while the panel holds the restore the page says to approve it there');
    assert.equal(await panel.locator('#rs-preview button').isDisabled(), true);

    await sidebar.evaluate(() => window.answerHeld({ outcome: 'refused', code: 'approval_timeout' }));
    await message.filter({ hasText: 'not approved on the panel in time' }).waitFor({ timeout: 5000 });
    assert.equal(await panel.locator('#rs-preview button').isEnabled(), true, 'the restore can be tried again');
    assert.deepEqual(await sidebar.evaluate(() => window.frames_seen), ['blob.upload', 'restore', 'blob.upload', 'restore']);
  });

  browserTest(`A sidebar restore denied on the panel ends with a clear message in ${engine}`, async (t) => {
    const { sidebar, panel } = await rig(t, engine);
    await sidebar.evaluate(() => window.answerHeld({ outcome: 'refused', code: 'approval_denied' }));
    await panel.locator('#bk-msg').filter({ hasText: 'denied on the panel' }).waitFor({ timeout: 5000 });
    assert.equal(await panel.locator('#rs-preview button').isEnabled(), true);
  });

  browserTest(`A held sidebar backup says at once to approve it on the panel and ends clearly when it is denied in ${engine}`, async (t) => {
    const { sidebar, panel } = await rig(t, engine, 'backup');
    const message = panel.locator('#bk-msg');
    await assert.doesNotReject(message.filter({ hasText: "panel's screen" }).waitFor({ timeout: 1000 }),
      'while the panel holds the backup the page says to approve it there');
    assert.equal(await panel.locator('#bk-download').isDisabled(), true);
    await sidebar.evaluate(() => window.answerHeld({ outcome: 'refused', code: 'approval_denied' }));
    await message.filter({ hasText: 'denied on the panel' }).waitFor({ timeout: 5000 });
    assert.equal(await panel.locator('#bk-download').isEnabled(), true, 'the backup can be tried again');
  });
}
