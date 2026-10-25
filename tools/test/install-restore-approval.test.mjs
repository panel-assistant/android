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
window.held = null; window.frames_seen = []; window.fetches = [];
var frame = document.getElementById('frame');
function reply(answer) { frame.contentWindow.postMessage({ type: 'panel-assistant-ui', frame: answer }, location.origin); }
window.answerHeld = function (answer) { var id = window.held.command_id; window.held = null; answer.command_id = id; reply(answer); };
window.answerFetch = function (index, answer) { answer.command_id = window.fetches[index].command_id; reply(answer); };
window.addEventListener('message', function (event) {
  var f = event.data && event.data.frame;
  if (event.source !== frame.contentWindow || !f) return;
  if (f.op === 'install.apk.from_url') { window.fetches.push(f); window.held = f; return; }
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
        '<input id="apk-url" value="https://example.invalid/app.apk"><button id="apk-fetch" onclick="apkFetchUrl()">Fetch</button><p id="apk-msg"></p><div id="apk-preview"></div>' +
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
  if (action === 'fetch') await panel.locator('#apk-fetch').click();
  else if (action === 'backup') await panel.locator('#bk-download').click();
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

const inspectedApk = { outcome: 'applied', result: { ok: true, package: 'io.example.fetched', version: '1.2.3', signer: 'abcd', token: 'preview-token' } };
const fetchResults = [
  ['success', inspectedApk, 'io.example.fetched'],
  ['denial', { outcome: 'refused', code: 'approval_denied' }, 'denied on the panel'],
  ['download failure', { outcome: 'failed', result: { ok: false, error: 'fetch-failed' } }, 'the panel could not download that link'],
];
for (const engine of ['Chromium', 'WebKit']) {
  for (const [name, answer, finalText] of fetchResults) {
    browserTest(`APK URL fetch clears its waiting hint after ${name} in ${engine}`, async (t) => {
      const { sidebar, panel } = await rig(t, engine, 'fetch');
      const message = panel.locator('#apk-msg');
      await message.filter({ hasText: "panel's screen" }).waitFor({ timeout: 1000 });
      assert.equal(await panel.locator('#apk-preview').textContent(), 'Downloading + inspecting…Cancel');
      assert.deepEqual(await sidebar.evaluate(() => window.fetches.map(f => [f.op, f.args.url])),
        [['install.apk.from_url', 'https://example.invalid/app.apk']]);
      await sidebar.evaluate(answer => window.answerFetch(0, answer), answer);
      await panel.locator('#apk-preview').filter({ hasText: finalText }).waitFor({ timeout: 5000 });
      if (name === 'success') {
        assert.equal(await panel.locator('#apk-preview button[data-token="preview-token"]').count(), 2);
      }
      assert.equal(await message.textContent(), '', 'a finished fetch must stop asking for approval');
    });
  }
  for (const [name, answer] of fetchResults) {
    browserTest(`An older APK URL fetch ${name} preserves the newer pending fetch in ${engine}`, async (t) => {
      const { sidebar, panel } = await rig(t, engine, 'fetch');
      await panel.locator('#apk-url').fill('https://example.invalid/newer.apk');
      await panel.locator('#apk-fetch').click();
      await sidebar.waitForFunction(() => window.fetches.length === 2);
      const before = await panel.locator('#apk-preview').innerHTML();
      const hint = await panel.locator('#apk-msg').textContent();
      assert.match(hint, /panel's screen/);
      assert.equal(await sidebar.evaluate(() => window.fetches[1].args.url), 'https://example.invalid/newer.apk');
      // Post a second message after the reply as a delivery barrier. Promise reactions from the real
      // transport finish before this next message task, so stale completion has actually been processed.
      await sidebar.evaluate(async answer => {
        const frame = document.getElementById('frame').contentWindow;
        await new Promise(resolve => {
          frame.addEventListener('message', function barrier(event) {
            if (event.data !== 'fetch-settled') return;
            frame.removeEventListener('message', barrier); resolve();
          });
          window.answerFetch(0, answer);
          frame.postMessage('fetch-settled', location.origin);
        });
      }, answer);
      assert.equal(await panel.locator('#apk-msg').textContent(), hint);
      assert.equal(await panel.locator('#apk-preview').innerHTML(), before);
      // The current reply still owns the preview, rather than all replies being ignored.
      await sidebar.evaluate(answer => window.answerFetch(1, answer), inspectedApk);
      await panel.locator('#apk-preview').filter({ hasText: 'io.example.fetched' }).waitFor({ timeout: 5000 });
    });
  }
}
