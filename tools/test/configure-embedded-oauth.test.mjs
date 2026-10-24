import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';

const assets = fileURLToPath(new URL('../../app/src/main/assets/', import.meta.url));
const chrome = process.env.CHROME || '/usr/bin/chromium';
const scripts = ['configure-state', 'configure-view', 'configure-help', 'configure-controls', 'configure-brightness', 'configure-auto-sleep', 'configure-cards', 'configure-render', 'configure'];
const sandbox = "frame-ancestors 'self'; sandbox allow-scripts allow-forms allow-downloads allow-modals allow-popups allow-popups-to-escape-sandbox";
const haUrl = 'http://ha.local:8123';
const schema = [{ key: 'ha_url', label: 'Home Assistant URL', group: 'Home Assistant connection', type: 'STRING', tier: 'BASIC', available: true, help: '' }];
const config = { settings: { ha_url: haUrl }, ha_expose: {}, ha_auth: { configured: true, oauth: true } };

function configure(embedded) {
  return `<!doctype html><html lang="en"><head>${i18nBridge()}</head><body ${embedded ? 'data-embedded data-ui-relay' : ''}>
    <button id="tab-basic"></button><button id="tab-adv"></button><div id="cfg-status"></div><div id="cfg-groups"></div>
    <div id="proximity-learning-mount"></div><div id="cfg-msg" role="status"></div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    ${scripts.map(name => `<script src="/${name}.js"></script>`).join('')}
  </body></html>`;
}

// The parent is a fixture HA sidebar peer. Configure uses the production postMessage transport, with
// the production opaque sandbox header; the peer answers manage frames and setup-family observations.
async function fixture() {
  const state = { requests: [], releaseStart: null, authorizationUrl: '' };
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const send = (body, type = 'application/json') => { response.setHeader('content-type', type); response.end(typeof body === 'string' ? body : JSON.stringify(body)); };
    if (path === '/') return send(`<!doctype html><html><body><iframe id="frame" src="/embedded"></iframe><script>
      const frame=document.getElementById('frame'), subscriptions=new Map();
      let account={phase:'connected',display_name:'Old panel account'};
      const post=value=>frame.contentWindow.postMessage({type:'panel-assistant-ui',frame:value},'*');
      const notify=id=>post({subscription:id,observations:[{state:'known',value:{setup:{},'ha.oauth.status':account}}]});
      window.setPanelAccount=value=>{account=value;for(const [id,family] of subscriptions)if(family==='setup')notify(id);};
      window.addEventListener('message',async event=>{
        if(event.source!==frame.contentWindow||event.data?.type!=='panel-assistant-ui')return;
        const request=event.data.frame;
        if(request.op==='subscribe'){
          subscriptions.set(request.command_id,request.args.family);
          post({command_id:request.command_id,outcome:'applied',result:{subscription:request.command_id}});
          if(request.args.family==='setup')notify(request.command_id);
        }else if(request.op==='unsubscribe'){
          subscriptions.delete(request.args.subscription);post({command_id:request.command_id,outcome:'applied'});
        }else{
          const reply=await fetch('/peer',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(request)}).then(r=>r.json());
          post({...reply,command_id:request.command_id});
        }
      });
    </script></body></html>`, 'text/html');
    if (path === '/embedded' || path === '/standalone') {
      if (path === '/embedded') response.setHeader('content-security-policy', sandbox);
      return send(configure(path === '/embedded'), 'text/html');
    }
    if (scripts.some(name => path === `/${name}.js`)) return send(await readFile(assets + path.slice(1), 'utf8'), 'application/javascript');
    if (path === '/authorize') return send('<!doctype html><html><body><h1>Home Assistant authorization</h1></body></html>', 'text/html');
    let op, args;
    if (path === '/peer') {
      let body = ''; for await (const chunk of request) body += chunk;
      ({ op, args = {} } = JSON.parse(body));
    } else {
      const routes = { '/api/v1/config/schema': 'config.schema', '/api/v1/config': 'config', '/api/v1/config/values': 'config.values', '/api/v1/ha/oauth/status': 'ha.oauth.status', '/api/v1/ha/oauth/start': 'ha.oauth.start' };
      op = routes[path];
      let body = ''; for await (const chunk of request) body += chunk;
      args = Object.fromEntries(new URLSearchParams(body));
    }
    let result = {};
    if (op === 'config.schema') result = schema;
    if (op === 'config' || op === 'config.values') result = config;
    if (op === 'ha.oauth.status') result = { phase: 'connected', display_name: 'Old panel account' };
    if (op === 'ha.oauth.start') {
      state.requests.push(args);
      result = await new Promise(resolve => { state.releaseStart = resolve; });
      if (result.error) response.statusCode = path === '/peer' ? 200 : 400;
    }
    if (path === '/peer') return send({ outcome: result.error ? 'refused' : 'applied', result });
    return send(result);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const url = `http://127.0.0.1:${server.address().port}`;
  state.authorizationUrl = `${url}/authorize?client_id=http%3A%2F%2Fpanel.local%3A8888%2F&state=one-use`;
  return { state, url, server };
}

async function open(t, engine, { embedded = true, blocked = false } = {}) {
  const panel = await fixture();
  const browser = await engine.type.launch(engine.launch);
  t.after(async () => { panel.state.releaseStart?.({ error: 'test-ended' }); await browser.close(); await new Promise(resolve => panel.server.close(resolve)); });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  await page.addInitScript(block => {
    window.copiedAuthorizationUrl = null;
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async value => { window.copiedAuthorizationUrl = value; } } });
    if (block) window.open = () => null;
  }, blocked);
  await page.goto(panel.url + (embedded ? '/' : '/standalone'));
  const content = embedded ? page.frames().find(frame => frame.url().endsWith('/embedded')) : page;
  await content.getByText('Connected as Old panel account', { exact: true }).waitFor();
  return { ...panel, browser, page, content, row: content.locator('#cfg-ha-oauth') };
}

async function startArrived(state) {
  for (let attempt = 0; attempt < 100 && !state.releaseStart; attempt++) await new Promise(resolve => setTimeout(resolve, 10));
  assert.ok(state.releaseStart, 'the panel received the OAuth start command');
}

for (const engine of [
  { name: 'chromium', type: chromium, launch: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'webkit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
]) {
  const browserTest = engine.available ? test : test.skip;

  browserTest(`${engine.name}: standalone Reconnect opens a detached authorization tab`, async t => {
    const f = await open(t, engine, { embedded: false });
    const popupReady = f.page.waitForEvent('popup', { timeout: 2_000 });
    await f.row.getByRole('button', { name: 'Reconnect', exact: true }).click();
    await startArrived(f.state);
    f.state.releaseStart({ ok: true, authorization_url: f.state.authorizationUrl });
    const popup = await popupReady;
    await popup.waitForURL(f.state.authorizationUrl);
    assert.equal(await popup.evaluate(() => window.opener), null);
    assert.equal(await popup.evaluate(() => { localStorage.setItem('authorization-check', 'yes'); return localStorage.getItem('authorization-check'); }), 'yes');
    assert.equal(await f.row.getByRole('link', { name: 'Open sign-in' }).getAttribute('href'), f.state.authorizationUrl);
    assert.deepEqual(f.state.requests, [{ ha_url: haUrl, ui_locale: 'en', return_surface: 'configure', preserve_explicit_english: '0' }]);
  });

  browserTest(`${engine.name}: a failed standalone sign-in start closes its reserved tab and can be retried`, async t => {
    const f = await open(t, engine, { embedded: false });
    const popupReady = f.page.waitForEvent('popup', { timeout: 2_000 });
    await f.row.getByRole('button', { name: 'Reconnect', exact: true }).click();
    await startArrived(f.state);
    const popup = await popupReady;
    const closed = popup.waitForEvent('close');
    f.state.releaseStart({ error: 'invalid-panel-origin' });
    await closed;
    await f.row.getByText('Could not start sign-in.', { exact: true }).waitFor();
    assert.equal(await f.row.getByRole('button', { name: 'Reconnect', exact: true }).isEnabled(), true);
    assert.equal(await f.row.getByRole('link', { name: 'Open sign-in' }).count(), 0);
  });

  browserTest(`${engine.name}: a blocked popup keeps Open sign-in and Copy link usable`, async t => {
    const f = await open(t, engine, { embedded: false, blocked: true });
    await f.row.getByRole('button', { name: 'Reconnect', exact: true }).click();
    await startArrived(f.state);
    f.state.releaseStart({ ok: true, authorization_url: f.state.authorizationUrl });
    const link = f.row.getByRole('link', { name: 'Open sign-in' });
    await link.waitFor();
    assert.equal(await link.getAttribute('href'), f.state.authorizationUrl);
    assert.match(await link.getAttribute('rel'), /noopener noreferrer/);
    await f.row.getByRole('button', { name: 'Copy link' }).click();
    assert.equal(await f.content.evaluate(() => window.copiedAuthorizationUrl), f.state.authorizationUrl);
    assert.equal(f.browser.contexts()[0].pages().length, 1);
    const popupReady = f.page.waitForEvent('popup');
    await link.click();
    const popup = await popupReady;
    await popup.waitForURL(f.state.authorizationUrl);
    assert.equal(await popup.evaluate(() => window.opener), null);
  });

  browserTest(`${engine.name}: sidebar account status follows the panel after a same-address user switch`, async t => {
    const f = await open(t, engine);
    await f.page.evaluate(() => window.setPanelAccount({ phase: 'connected', display_name: 'Different panel account' }));
    await f.row.getByText('Connected as Different panel account', { exact: true }).waitFor();
    assert.equal(await f.content.locator('#cfg-ha_url input').inputValue(), haUrl);
    assert.equal(await f.row.getByRole('button', { name: 'Reconnect', exact: true }).isEnabled(), true);
  });

  browserTest(`${engine.name}: opaque sidebar Configure retains its panel-owned sign-in fallback`, async t => {
    const f = await open(t, engine);
    assert.equal(await f.content.evaluate(() => self.origin), 'null');
    assert.equal(await f.page.evaluate(() => document.querySelector('iframe').contentDocument === null), true);
    await f.row.getByRole('button', { name: 'Reconnect', exact: true }).click();
    await startArrived(f.state);
    f.state.releaseStart({ ok: true, authorization_url: f.state.authorizationUrl });
    const link = f.row.getByRole('link', { name: 'Open sign-in' });
    await link.waitFor();
    assert.equal(await link.getAttribute('href'), f.state.authorizationUrl);
    assert.match(await f.row.textContent(), /browser.*reach.*panel|panel.*address.*browser/i);
    await f.row.getByRole('button', { name: 'Copy link' }).click();
    assert.equal(await f.content.evaluate(() => window.copiedAuthorizationUrl), f.state.authorizationUrl);
    assert.deepEqual(f.state.requests, [{ ha_url: haUrl, ui_locale: 'en', return_surface: 'configure', preserve_explicit_english: '0' }]);
  });
}
