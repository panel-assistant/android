// The Dashboard screenshot and the Diagnostics links inside the Panel Assistant sidebar: the page runs in an opaque
// sandboxed frame whose requests reach the panel through its parent. The real page-shell click handler, transport and
// link markup run under the production sandbox header; the parent here relays manage frames as the sidebar does.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';

const source = name => readFile(fileURLToPath(new URL(`../../app/src/main/${name}`, import.meta.url)), 'utf8');
const http = 'kotlin/io/panelassistant/android/http/';
const shell = await source(http + 'PageShell.kt');
const handler = shell.match(/<script>(\/\* Over the stub's socket a download link[\s\S]*?)<\/script>/)[1];
const dashboard = await source(http + 'DashboardPage.kt'), install = await source(http + 'InstallPage.kt');
// The production attributes, with only the render-time Kotlin expressions filled in.
const markup = text => text.replace(/\$\{[^}]*\}/g, 'label').replace(/\$\w+/g, '');
const links = [
  { name: 'Dashboard screenshot', html: dashboard.match(/<a class="shot"[^>]*>/)[0], op: 'screenshot', file: 'screenshot.png', body: 'png bytes' },
  { name: 'Dashboard Diagnostics', html: dashboard.match(/<a href="api\/v1\/diag"[^>]*>/)[0], op: 'diag', file: 'diag.txt', body: 'ha-paneld diagnostics\n' },
  { name: 'Install Diagnostics', html: install.match(/<a href="api\/v1\/diag"[^>]*>/)[0], op: 'diag', file: 'diag.txt', body: 'ha-paneld diagnostics\n' },
];
const transport = await source('assets/transport.js');
const chrome = process.env.CHROME || '/usr/bin/chromium';
const sandbox = "frame-ancestors 'self'; sandbox allow-scripts allow-forms allow-downloads allow-modals allow-popups allow-popups-to-escape-sandbox";

async function fixture() {
  const requests = [];
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const send = (body, type) => { response.setHeader('content-type', type); response.end(body); };
    if (path === '/') return send(`<!doctype html><html><body><iframe id="frame" src="/embedded"></iframe><script>
      const frame=document.getElementById('frame');
      window.addEventListener('message',async event=>{
        if(event.source!==frame.contentWindow||event.data?.type!=='panel-assistant-ui')return;
        const reply=await fetch('/peer',{method:'POST',body:JSON.stringify(event.data.frame)}).then(r=>r.json());
        frame.contentWindow.postMessage({type:'panel-assistant-ui',frame:reply},'*');
      });
    </script></body></html>`, 'text/html');
    if (path === '/embedded') {
      response.setHeader('content-security-policy', sandbox);
      return send(`<!doctype html><html><head><script src="/transport.js"></script><script>${handler}</script></head>
        <body data-embedded data-ui-relay>${links.map((link, i) => markup(link.html).replace('<a ', `<a id="link${i}" `) + 'link</a>').join('<br>')}</body></html>`, 'text/html');
    }
    if (path === '/transport.js') return send(transport, 'application/javascript');
    // A screenshot is too large for a frame: Panel Assistant hands the browser a blob to fetch.
    // The embed view admits the opaque frame's cross-origin fetch by its token (embed.py cors_allowed).
    if (path === '/blob/shot') { response.setHeader('access-control-allow-origin', request.headers.origin); return send(links[0].body, 'image/png'); }
    if (path !== '/peer') { response.statusCode = 404; return response.end(); }
    let body = ''; for await (const chunk of request) body += chunk;
    const message = JSON.parse(body);
    requests.push(message.op);
    const result = message.op === 'screenshot' ? { blob: { url: 'blob/shot', type: 'image/png' } } : links[1].body;
    send(JSON.stringify({ command_id: message.command_id, outcome: 'applied', result }), 'application/json');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return { requests, server, url: `http://127.0.0.1:${server.address().port}` };
}

for (const engine of [
  { name: 'chromium', type: chromium, launch: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'webkit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
]) {
  for (const [index, link] of links.entries()) {
    (engine.available ? test : test.skip)(`${engine.name}: in the sidebar the ${link.name} link saves what the panel sends`, async t => {
      const panel = await fixture();
      const browser = await engine.type.launch(engine.launch);
      t.after(async () => { await browser.close(); await new Promise(resolve => panel.server.close(resolve)); });
      const context = await browser.newContext({ acceptDownloads: true });
      const page = await context.newPage();
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.goto(panel.url);
      const frame = page.frames().find(candidate => candidate.url().endsWith('/embedded'));
      assert.equal(await frame.evaluate(() => self.origin), 'null');
      const download = page.waitForEvent('download', { timeout: 5_000 });
      await frame.locator(`#link${index}`).click();
      const saved = await download;
      assert.equal(saved.suggestedFilename(), link.file);
      assert.equal(await readFile(await saved.path(), 'utf8'), link.body);
      assert.deepEqual(panel.requests, [link.op]);
      assert.deepEqual(errors, []);
      // No tab is left behind: the opaque frame could not detach one's opener.
      assert.equal(context.pages().length, 1);
    });
  }
}
