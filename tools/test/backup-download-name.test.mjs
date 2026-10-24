// The Backup button's real download path: two backups in a row must be saved under different names that end in
// the extension the restore picker accepts (a browser appends '-2' after the extension on a repeat name).
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

for (const engine of ['Chromium', 'WebKit']) {
  const runnable = engine === 'WebKit' || existsSync(chrome);
  (runnable ? test : test.skip)(`${engine}: two backups download under distinct names with the extension last`, async (t) => {
    const source = await readFile(asset, 'utf8');
    const server = createServer((request, response) => {
      const path = new URL(request.url, 'http://panel.test').pathname;
      if (path === '/install.js') { response.writeHead(200, { 'content-type': 'application/javascript' }); response.end(source); return; }
      if (path === '/api/v1/backup') { response.writeHead(200, { 'content-type': 'application/octet-stream' }); response.end(Buffer.from('HPB1-archive')); return; }
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      response.end(`<!doctype html><html><head>${i18nBridge({})}<title>test panel · Install</title></head><body><input id="bk-pw" value="secret"><input id="bk-plain" type="checkbox"><input id="bk-comp" type="checkbox"><div id="bk-msg"></div><script>window.CardColumnAlignment={attach:()=>()=>{}};</script><script src="/install.js"></script></body></html>`);
    });
    await new Promise((done) => server.listen(0, '127.0.0.1', done));
    const browser = await (engine === 'WebKit' ? webkit.launch({ headless: true }) : chromium.launch({ executablePath: chrome, headless: true }));
    t.after(async () => { try { await browser.close(); } finally { server.closeAllConnections?.(); await new Promise((done) => server.close(done)); } });
    const page = await (await browser.newContext({ acceptDownloads: true })).newPage();
    await page.goto(`http://127.0.0.1:${server.address().port}/`, { waitUntil: 'domcontentloaded' });
    const names = [];
    for (const plain of [false, true, false]) {
      if (plain) await page.evaluate(() => { document.getElementById('bk-pw').value = ''; document.getElementById('bk-plain').checked = true; });
      else await page.evaluate(() => { document.getElementById('bk-pw').value = 'secret'; });
      const [download] = await Promise.all([page.waitForEvent('download'), page.evaluate(() => window.doBackup(document.createElement('button')))]);
      names.push(download.suggestedFilename());
    }
    assert.equal(new Set(names).size, 3, `names collided: ${names}`);
    assert.match(names[0], /^test[ _]panel-backup-\d{8}T\d{9}Z\.hpb$/);
    assert.match(names[1], /^test[ _]panel-backup-\d{8}T\d{9}Z\.zip$/);
    assert.match(names[2], /\.hpb$/);
  });
}
