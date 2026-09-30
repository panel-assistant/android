import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium } from 'playwright-core';

const assets = fileURLToPath(new URL('../../app/src/main/assets/', import.meta.url));
const chrome = process.env.CHROME || '/usr/bin/chromium';
const browserTest = existsSync(chrome) ? test : test.skip;
const authorizationUrl = 'http://ha.local:8123/auth/authorize?client_id=http%3A%2F%2Fpanel.local%3A8888%2F&state=one-use';

browserTest('embedded Configure gives a copyable panel-owned authorization URL and LAN callback guidance', async (t) => {
  const requests = [];
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    if (path === '/') {
      response.setHeader('content-type', 'text/html');
      response.end(`<!doctype html><html><body data-embedded><button id="tab-basic"></button><button id="tab-adv"></button><p id="cfg-msg"></p><p id="cfg-status"></p><div id="cfg-groups"></div><div id="proximity-learning-mount"></div><div id="savebar" hidden><button id="savebtn"></button></div><script>window.CardColumnAlignment={attach:()=>()=>{}};</script><script src="/configure-state.js"></script><script src="/configure-view.js"></script><script src="/configure-help.js"></script><script src="/configure-controls.js"></script><script src="/configure-brightness.js"></script><script src="/configure-auto-sleep.js"></script><script src="/configure-cards.js"></script><script src="/configure-render.js"></script><script src="/configure.js"></script></body></html>`);
      return;
    }
    if (['/configure-state.js', '/configure-view.js', '/configure-help.js', '/configure-controls.js', '/configure-brightness.js', '/configure-auto-sleep.js', '/configure-cards.js', '/configure-render.js', '/configure.js'].includes(path)) {
      response.setHeader('content-type', 'application/javascript');
      response.end(await readFile(assets + path.slice(1), 'utf8'));
      return;
    }
    if (path === '/api/v1/config/schema') {
      response.setHeader('content-type', 'application/json');
      response.end(JSON.stringify([{ key: 'ha_url', label: 'Home Assistant URL', group: 'Home Assistant connection', type: 'STRING', tier: 'BASIC', available: true, help: '' }]));
      return;
    }
    if (path === '/api/v1/config') {
      response.setHeader('content-type', 'application/json');
      response.end(JSON.stringify({ settings: { ha_url: 'http://ha.local:8123' }, ha_expose: {}, ha_auth: { configured: true, oauth: true } }));
      return;
    }
    if (path === '/api/v1/ha/oauth/start' && request.method === 'POST') {
      let body = '';
      for await (const chunk of request) body += chunk;
      requests.push(new URLSearchParams(body));
      response.setHeader('content-type', 'application/json');
      response.end(JSON.stringify({ ok: true, authorization_url: authorizationUrl }));
      return;
    }
    response.setHeader('content-type', 'application/json');
    response.end('{}');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ executablePath: chrome, args: ['--no-sandbox'] });
  t.after(async () => { await browser.close(); await new Promise((resolve) => server.close(resolve)); });
  const page = await browser.newPage();
  page.setDefaultTimeout(3_000);
  await page.addInitScript(() => {
    window.copiedAuthorizationUrl = null;
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async (value) => { window.copiedAuthorizationUrl = value; } } });
  });
  await page.goto(`http://127.0.0.1:${server.address().port}/`);
  const row = page.locator('#cfg-ha-oauth');
  await row.waitFor();
  await row.getByRole('button', { name: 'Reconnect' }).click();
  const openLink = row.getByRole('link', { name: 'Open sign-in' });
  await openLink.waitFor();
  assert.equal(await openLink.getAttribute('href'), authorizationUrl);
  assert.match(await openLink.getAttribute('rel'), /noopener noreferrer/);
  await row.getByRole('button', { name: 'Copy link' }).click();
  assert.equal(await page.evaluate(() => window.copiedAuthorizationUrl), authorizationUrl);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].get('ha_url'), 'http://ha.local:8123');
  assert.equal(requests[0].get('return_surface'), 'configure');
  assert.equal(requests[0].has('ha_token'), false);
  assert.equal(requests[0].has('ha_refresh_token'), false);
  assert.match(await row.textContent(), /browser.*reach.*panel|panel.*address.*browser/i);
});
