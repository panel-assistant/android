import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';

const assets = join(process.cwd(), '..', '..', 'app', 'src', 'main', 'assets');
const fixturePath = join(process.cwd(), '..', '..', 'app', 'build', 'test-fixtures', 'hide-mqtt-card.json');
const fixture = JSON.parse(await readFile(fixturePath, 'utf8').catch(() => {
  throw new Error(`missing JVM-produced Configure fixture at ${fixturePath}; run ./gradlew :app:testDebugUnitTest --rerun --tests io.github.maxlyth.hapaneld.http.MqttCardSchemaFixtureTest first`);
}));
const chrome = process.env.CHROME || '/usr/bin/chromium';

function page() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css"></head><body>
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <button id="tab-basic"></button><button id="tab-adv"></button>
    <p id="cfg-msg"></p><p id="cfg-status"></p><div id="cfg-groups" class="cards"></div>
    <div id="proximity-learning-mount"></div><div id="savebar" hidden><button id="savebtn"></button></div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    <script src="/configure.js"></script><script src="/proximity-learning.js"></script>
  </body></html>`;
}

async function harness(response) {
  const schema = response.schema;
  const config = response.config;
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    const send = (body, type = 'application/json') => { response.setHeader('content-type', type); response.end(body); };
    if (path === '/') return send(page(), 'text/html');
    if (path === '/configure.js' || path === '/proximity-learning.js') return send(await readFile(join(assets, path.slice(1)), 'utf8'), 'application/javascript');
    if (path === '/info.css') return send(await readFile(join(assets, 'info.css'), 'utf8'), 'text/css');
    if (path === '/api/v1/config/schema') return send(JSON.stringify(schema));
    if (path === '/api/v1/config') return send(JSON.stringify(config));
    if (path === '/api/v1/apps') return send(JSON.stringify({ apps: [] }));
    if (path === '/api/v1/config/discovery') return send('{}');
    if (path === '/api/v1/config/home-dashboards') return send(JSON.stringify({ queried: true, items: [], default: { explicit: false, path: '' } }));
    if (path === '/api/v1/radio' || path === '/api/v1/proximity') return send(JSON.stringify({ present: false }));
    response.statusCode = 404; response.end('not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, url: `http://127.0.0.1:${server.address().port}` };
}

const engines = [
  { name: 'Chromium', type: chromium, options: { executablePath: chrome, headless: true, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'WebKit', type: webkit, options: { headless: true }, available: existsSync(webkit.executablePath()) },
];

for (const engine of engines) {
  test(`${engine.name} is installed for the MQTT card proof`, () => {
    assert.equal(engine.available, true, `${engine.name} is required; install its Playwright browser before running this proof`);
  });
}

for (const engine of engines) {
  test(`${engine.name}: legacy Configure keeps MQTT while migrated Configure removes its card`, async (t) => {
    const browser = await engine.type.launch(engine.options);
    t.after(() => browser.close());
    const cases = [
      ['native blank', fixture.nativeBlank, false],
      ['native saved broker', fixture.nativeSaved, false],
      ['ungranted blank', fixture.legacyBlank, true],
      ['legacy MQTT', fixture.legacyMqtt, true],
      ['legacy shadow', fixture.legacyShadow, true],
    ];
    for (const [authority, response, mqttVisible] of cases) {
      const rig = await harness(response);
      try {
        const view = await browser.newPage({ viewport: { width: 480, height: 800 } });
        await view.goto(rig.url, { waitUntil: 'domcontentloaded' });
        for (const advanced of [false, true]) {
          await view.evaluate((isAdvanced) => window.cfgTab(isAdvanced), advanced);
          await view.locator('#cfg-friendly_name').waitFor();
          const viewName = advanced ? 'Advanced' : 'Basic';
          if (mqttVisible) {
            assert.equal(await view.locator('[data-config-group="MQTT"]').count(), 1, `${authority} retains the MQTT card in ${viewName}`);
            if (advanced) await view.locator('#cfg-mqtt_address_family').waitFor();
            else await view.locator('#cfg-mqtt_broker').waitFor();
          } else {
            assert.equal(await view.locator('[data-config-group="MQTT"]').count(), 0, `${authority} removes the MQTT card in ${viewName} despite its saved broker`);
            assert.equal(await view.locator('#cfg-mqtt_broker').count(), 0, `${authority} does not leave the broker row behind in ${viewName}`);
            assert.equal(await view.locator('#cfg-mqtt_address_family').count(), 0, `${authority} does not leave the address-family row behind in ${viewName}`);
          }
        }
        await view.close();
      } finally {
        await new Promise((resolve) => rig.server.close(resolve));
      }
    }
  });
}
