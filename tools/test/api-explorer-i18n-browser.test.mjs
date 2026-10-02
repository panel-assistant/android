import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium } from 'playwright-core';

const htmlAsset = fileURLToPath(new URL('../../app/src/main/assets/api.html', import.meta.url));
const apiAsset = fileURLToPath(new URL('../../app/src/main/assets/api.js', import.meta.url));
const helperAsset = fileURLToPath(new URL('../../app/src/main/assets/i18n.js', import.meta.url));
const englishCatalogue = fileURLToPath(new URL('../../app/src/main/assets/i18n/en.json', import.meta.url));
const chrome = process.env.CHROME || '/usr/bin/chromium';

const newKeys = Object.freeze([
  'api.action.send',
  'api.approval.conditional',
  'api.error.load_spec',
  'api.group.other',
  'api.header.back_to_panel',
  'api.intro.import',
  'api.intro.live',
  'api.intro.network',
  'api.request.body',
  'api.status.error',
]);
const reusedKeys = Object.freeze([
  'configure.hardened.action_approval',
  'shell.hardened.key',
]);

const translations = Object.freeze({
  'api.action.send': '发送 {method}',
  'api.approval.conditional': '部分值需要面板确认',
  'api.error.load_spec': '无法加载 {path}：{error}',
  'api.group.other': '其他',
  'api.header.back_to_panel': '← 返回面板',
  'api.intro.import': '将 openapi.json 导入 Swagger/Postman。',
  'api.intro.live': '面板 API 浏览器。',
  'api.intro.network': '无需认证；仅限 LAN。',
  'api.request.body': '请求正文（{type}）',
  'api.status.error': '错误',
  'configure.hardened.action_approval': '需要在面板上确认',
  'shell.hardened.key': '带盾牌的操作必须在面板上确认',
});

function operation({ tag = 'Fleet <img src=x onerror="window.__specOwned=1">', summary = 'Exact <svg onload="window.__specOwned=2"> summary', parameters = [], requestBody, approval } = {}) {
  return {
    ...(tag === null ? {} : { tags: [tag] }),
    summary,
    parameters,
    ...(requestBody ? { requestBody } : {}),
    responses: approval ? { 428: { description: `ApprovalRequired ${approval}` } } : { 200: { description: 'OK' } },
  };
}

const parameter = (name, where, required = false, description = '') => ({
  name, in: where, required, description, schema: { type: 'string' },
});

function fixtureSpec() {
  return {
    openapi: '3.0.0',
    paths: {
      '/api/v1/items/{id}': { get: operation({
        parameters: [
          parameter('id', 'path', true, 'Exact path <img src=x onerror="window.__parameterOwned=1">'),
          parameter('filter', 'query', false, 'Exact query description'),
          parameter('X-Probe', 'header', false, 'Exact header description'),
        ],
        approval: 'always',
      }) },
      '/api/v1/config': { post: operation({
        tag: 'Configuration', summary: 'Update configuration', approval: 'depending on value',
        requestBody: { content: { 'application/x-www-form-urlencoded': { schema: {
          type: 'object', required: ['mode'], properties: { mode: { type: 'string', enum: ['safe', 'fast'] }, note: { type: 'string' } },
        } } } },
      }) },
      '/api/v1/json': { post: operation({ tag: 'Bodies', summary: 'Submit JSON', requestBody: { content: { 'application/json': { schema: { type: 'object' } } } } }) },
      '/api/v1/binary': { post: operation({ tag: 'Bodies', summary: 'Upload bytes', requestBody: { content: { 'application/octet-stream': { schema: { type: 'string', format: 'binary' } } } } }) },
      '/api/v1/http-failure': { get: operation({ tag: 'Failures', summary: 'HTTP failure' }) },
      '/api/v1/network-failure': { get: operation({ tag: 'Failures', summary: 'Network failure' }) },
      '/api/v1/untagged': { get: operation({ tag: null, summary: 'Exact untagged summary' }) },
    },
  };
}

function jsonResponse(response, body, status = 200) {
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
  response.end(typeof body === 'string' ? body : JSON.stringify(body));
}

async function rig(t, { strings = translations, spec = fixtureSpec(), failSpec = false, initScript } = {}) {
  assert.ok(existsSync(chrome), `Chromium is required for the API Explorer browser gate: ${chrome}`);
  const [htmlSource, apiSource, helperSource] = await Promise.all([
    readFile(htmlAsset, 'utf8'), readFile(apiAsset, 'utf8'), readFile(helperAsset, 'utf8'),
  ]);
  const requests = [];
  const payload = JSON.stringify({
    locale: 'zh-Hans',
    strings,
    languages: Object.fromEntries(Object.keys(strings).map((key) => [key, 'zh-Hans'])),
  }).replaceAll('<', '\\u003c');
  const pageHtml = htmlSource
    .replace('__API_LANG__', 'zh-Hans')
    .replace('__API_BACK_HREF__', '/?lang=zh-Hans')
    .replace('__API_I18N_PAYLOAD__', payload);
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://panel.test');
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    requests.push({ method: request.method, path: url.pathname, search: url.search, headers: { ...request.headers }, body: Buffer.concat(chunks) });
    if (url.pathname === '/api') {
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }); response.end(pageHtml); return;
    }
    if (url.pathname === '/assets/i18n.js') {
      response.writeHead(200, { 'content-type': 'application/javascript' }); response.end(helperSource); return;
    }
    if (url.pathname === '/assets/api.js') {
      response.writeHead(200, { 'content-type': 'application/javascript' }); response.end(apiSource); return;
    }
    if (url.pathname === '/api/v1/openapi.json') {
      if (failSpec) { response.destroy(); return; }
      jsonResponse(response, spec); return;
    }
    if (url.pathname === '/api/v1/http-failure') {
      jsonResponse(response, '<img src=x onerror="window.__responseOwned=1">', 503); return;
    }
    if (url.pathname === '/api/v1/network-failure') { response.destroy(); return; }
    jsonResponse(response, { exact: '<img src=x onerror="window.__responseOwned=2">', path: url.pathname, search: url.search });
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  let browser;
  t.after(async () => {
    if (browser) await browser.close().catch(() => {});
    if (server.listening) {
      server.closeAllConnections?.();
      await new Promise((done) => server.close(done));
    }
  });
  browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(5_000);
  if (initScript) await page.addInitScript(initScript);
  await page.goto(`http://127.0.0.1:${server.address().port}/api?lang=zh-Hans`, { waitUntil: 'domcontentloaded' });
  if (!failSpec) await page.waitForFunction(() => document.querySelectorAll('details.ep').length === 7);
  return { page, requests };
}

test('API Explorer owns exactly ten new catalogue strings and reuses exactly two established strings', async () => {
  const [source, catalogue] = await Promise.all([readFile(apiAsset, 'utf8'), readFile(englishCatalogue, 'utf8').then(JSON.parse)]);
  const apiKeys = [...source.matchAll(/"(api\.[a-z0-9._-]+|configure\.hardened\.action_approval|shell\.hardened\.key)"/g)]
    .map((match) => match[1]);
  assert.deepEqual([...new Set(apiKeys.filter((key) => key.startsWith('api.')))].sort(), [...newKeys].sort());
  assert.deepEqual([...new Set(apiKeys.filter((key) => !key.startsWith('api.')))].sort(), [...reusedKeys].sort());
  for (const key of [...newKeys, ...reusedKeys]) assert.ok(catalogue.strings[key], `${key} has an English catalogue record`);
});

test('API Explorer localizes all controlled chrome while preserving frozen technical tokens', async (t) => {
  const { page } = await rig(t);
  assert.equal(await page.locator('html').getAttribute('lang'), 'zh-Hans');
  assert.equal(await page.locator('#api-back').textContent(), translations['api.header.back_to_panel']);
  assert.equal(await page.locator('#api-back').getAttribute('href'), '/?lang=zh-Hans');
  assert.equal(await page.locator('#api-intro-live').textContent(), translations['api.intro.live']);
  assert.equal(await page.locator('#api-intro-network').textContent(), translations['api.intro.network']);
  assert.equal(await page.locator('#api-intro-import').textContent(), translations['api.intro.import']);
  assert.equal(await page.locator('#api-intro-import a').textContent(), 'openapi.json');
  assert.equal(await page.locator('#api-intro-import a').getAttribute('href'), 'api/v1/openapi.json');
  assert.equal(await page.locator('#hardened-approval-description').textContent(), translations['configure.hardened.action_approval']);
  assert.equal(await page.locator('#api-approval-key').textContent(), translations['shell.hardened.key']);
  assert.match(await page.locator('button').first().textContent(), /^发送 GET$/);
  assert.match(await page.locator('label').filter({ hasText: 'application/json' }).textContent(), /^请求正文（application\/json）$/);
});

test('API Explorer keeps OpenAPI prose exact English, localizes an untagged group, and cannot execute hostile data', async (t) => {
  const hostileStrings = { ...translations, 'api.header.back_to_panel': '<img src=x onerror="window.__translationOwned=1">返回' };
  const { page } = await rig(t, { strings: hostileStrings });
  assert.equal(await page.locator('#api-back').textContent(), hostileStrings['api.header.back_to_panel']);
  assert.equal(await page.locator('h2[lang="en"]').first().textContent(), 'Fleet <img src=x onerror="window.__specOwned=1">');
  assert.equal(await page.locator('.sum[lang="en"]').first().textContent(), 'Exact <svg onload="window.__specOwned=2"> summary');
  const pathLabel = page.locator('label').filter({ hasText: 'id * (path)' });
  assert.match(await pathLabel.textContent(), /Exact path <img src=x onerror="window.__parameterOwned=1">/);
  assert.equal(await pathLabel.locator('span[lang="en"]').count(), 1);
  assert.equal(await page.locator('h2').filter({ hasText: /^其他$/ }).getAttribute('lang'), null);
  assert.equal(await page.locator('.sum[lang="en"]').filter({ hasText: 'Exact untagged summary' }).count(), 1);
  assert.equal(await page.locator('img, svg').count(), 0);
  assert.deepEqual(await page.evaluate(() => [window.__translationOwned, window.__specOwned, window.__parameterOwned]), [undefined, undefined, undefined]);
});

test('API Explorer submits path, query and header parameters and renders successful raw responses inert', async (t) => {
  const { page, requests } = await rig(t);
  const endpoint = page.locator('details').filter({ hasText: '/api/v1/items/{id}' });
  await endpoint.locator('summary').click();
  await endpoint.locator('input').nth(0).fill('a/b');
  await endpoint.locator('input').nth(1).fill('x y');
  await endpoint.locator('input').nth(2).fill('header-value');
  await endpoint.locator('button').click();
  await endpoint.locator('pre').waitFor({ state: 'visible' });
  const sent = requests.find((item) => item.path === '/api/v1/items/a%2Fb');
  assert.ok(sent);
  assert.equal(sent.search, '?filter=x%20y');
  assert.equal(sent.headers['x-probe'], 'header-value');
  assert.equal(await endpoint.locator('pre').getAttribute('lang'), 'und');
  assert.match(await endpoint.locator('pre').textContent(), /<img src=x onerror=/);
  assert.equal(await endpoint.locator('img').count(), 0);
  assert.equal(await page.evaluate(() => window.__responseOwned), undefined);
});

test('API Explorer serializes form, JSON and binary request bodies without altering their media types', async (t) => {
  const { page, requests } = await rig(t);
  const form = page.locator('details').filter({ hasText: '/api/v1/config' });
  await form.locator('summary').click();
  await form.locator('select').selectOption('fast');
  await form.locator('input').fill('hello world');
  await form.locator('button').click();
  await form.locator('pre').waitFor({ state: 'visible' });

  const json = page.locator('details').filter({ hasText: '/api/v1/json' });
  await json.locator('summary').click();
  await json.locator('textarea').fill('{"exact":"中文"}');
  await json.locator('button').click();
  await json.locator('pre').waitFor({ state: 'visible' });

  const binary = page.locator('details').filter({ hasText: '/api/v1/binary' });
  await binary.locator('summary').click();
  await binary.locator('input[type=file]').setInputFiles({ name: 'probe.bin', mimeType: 'application/octet-stream', buffer: Buffer.from([0, 1, 2, 255]) });
  await binary.locator('button').click();
  await binary.locator('pre').waitFor({ state: 'visible' });

  const formRequest = requests.find((item) => item.path === '/api/v1/config');
  assert.equal(formRequest.headers['content-type'], 'application/x-www-form-urlencoded');
  assert.equal(formRequest.body.toString(), 'mode=fast&note=hello%20world');
  const jsonRequest = requests.find((item) => item.path === '/api/v1/json');
  assert.equal(jsonRequest.headers['content-type'], 'application/json');
  assert.equal(jsonRequest.body.toString(), '{"exact":"中文"}');
  const binaryRequest = requests.find((item) => item.path === '/api/v1/binary');
  assert.equal(binaryRequest.headers['content-type'], 'application/octet-stream');
  assert.deepEqual([...binaryRequest.body], [0, 1, 2, 255]);
});

test('API Explorer distinguishes required and conditional Hardened approval accessibly', async (t) => {
  const { page } = await rig(t);
  const required = page.locator('details').filter({ hasText: '/api/v1/items/{id}' }).locator('button');
  assert.equal(await required.getAttribute('data-hardened-approval'), '');
  assert.equal(await required.getAttribute('aria-describedby'), 'hardened-approval-description');
  assert.equal(await required.getAttribute('title'), translations['configure.hardened.action_approval']);
  const conditional = page.locator('details').filter({ hasText: '/api/v1/config' }).locator('button');
  assert.equal(await conditional.getAttribute('data-hardened-approval'), 'conditional');
  assert.equal(await conditional.getAttribute('aria-describedby'), 'hardened-approval-conditional-description');
  assert.equal(await conditional.getAttribute('title'), translations['api.approval.conditional']);
});

test('API Explorer reports HTTP and network failures without interpreting response or browser evidence as markup', async (t) => {
  const { page } = await rig(t);
  const http = page.locator('details').filter({ hasText: '/api/v1/http-failure' });
  await http.locator('summary').click();
  await http.locator('button').click();
  await http.locator('pre').waitFor({ state: 'visible' });
  assert.match(await http.locator('.st').textContent(), /^503 application\/json/);
  assert.equal(await http.locator('pre').getAttribute('lang'), 'und');
  assert.match(await http.locator('pre').textContent(), /<img src=x onerror=/);

  const network = page.locator('details').filter({ hasText: '/api/v1/network-failure' });
  await network.locator('summary').click();
  await network.locator('button').click();
  await network.locator('.st b').waitFor();
  assert.equal(await network.locator('.st b').textContent(), translations['api.status.error']);
  assert.equal(await network.locator('.st span').getAttribute('lang'), 'und');
  assert.match(await network.locator('.st span').textContent(), /TypeError|fetch/i);
  assert.equal(await page.locator('img, svg').count(), 0);
  assert.equal(await page.evaluate(() => window.__responseOwned), undefined);
});

test('API Explorer spec-load failure localizes its frame and isolates hostile browser evidence as und text', async (t) => {
  const { page } = await rig(t, {
    failSpec: true,
    initScript: () => {
      const nativeFetch = window.fetch.bind(window);
      window.fetch = (input, options) => String(input).endsWith('api/v1/openapi.json')
        ? Promise.reject('<img src=x onerror="window.__errorOwned=1">')
        : nativeFetch(input, options);
    },
  });
  const message = page.locator('main.pickles code');
  await message.waitFor();
  assert.match(await message.textContent(), /^无法加载 api\/v1\/openapi\.json：<img/);
  assert.equal(await message.getAttribute('lang'), 'und');
  assert.equal(await message.locator('img').count(), 0);
  assert.equal(await page.evaluate(() => window.__errorOwned), undefined);
});

test('API Explorer remains usable with exact English fallbacks when translations are absent', async (t) => {
  const { page } = await rig(t, { strings: {} });
  assert.equal(await page.locator('#api-back').textContent(), '← back to panel');
  assert.equal(await page.locator('h2').filter({ hasText: /^other$/ }).count(), 1);
  assert.match(await page.locator('button').first().textContent(), /^Send GET$/);
  assert.equal(await page.locator('#api-approval-key').textContent(), 'Shielded actions need physical approval on this panel in Hardened mode; they cannot be approved remotely.');
});
