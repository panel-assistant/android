import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import vm from 'node:vm';
import { chromium } from 'playwright-core';
import { browserTest, chrome, json, startHarness } from './fixtures/browser-harness.mjs';
import { i18nBridge, i18nHelper, installTransport } from './fixtures/i18n-bridge.mjs';

const defaultAsset = fileURLToPath(new URL('../../app/src/main/assets/switcher.js', import.meta.url));
const switcherAsset = process.argv[2] ? resolve(process.argv[2]) : defaultAsset;
const ENGLISH_TITLE = 'Switch to another panel (keeps the current view)';

function classList() {
  return { add() {}, remove() {}, toggle() {}, contains() { return false; } };
}

function element(tagName = 'div') {
  const node = {
    tagName: tagName.toUpperCase(),
    children: [],
    attributes: {},
    classList: classList(),
    selectedIndex: -1,
    appendChild(child) {
      this.children.push(child);
      if (child.selected) this.selectedIndex = this.children.length - 1;
      return child;
    },
    removeChild(child) {
      this.children.splice(this.children.indexOf(child), 1);
      return child;
    },
    setAttribute(name, value) { this.attributes[name] = String(value); },
    getAttribute(name) { return this.attributes[name] || ''; },
  };
  Object.defineProperties(node, {
    firstChild: { get() { return this.children[0] || null; } },
    options: { get() { return this.children; } },
  });
  return node;
}

async function loadSwitcher({ translate, peers: suppliedPeers } = {}) {
  const source = await readFile(switcherAsset, 'utf8');
  const host = element('small');
  host.attributes['data-self-id'] = 'alpha';
  host.attributes['data-self-name'] = 'Alpha panel';
  host.appendChild(element('span'));

  const created = [];
  const document = {
    body: { classList: classList() },
    documentElement: { getAttribute() { return ''; }, style: { setProperty() {} } },
    getElementById(id) { return id === 'pswitch' ? host : null; },
    querySelector() { return null; },
    querySelectorAll() { return []; },
    createElement(tagName) {
      const node = element(tagName);
      created.push(node);
      return node;
    },
  };
  const window = {
    document,
    location: { pathname: '/configure', search: '?lang=zh-Hans', hash: '#network', href: '' },
    addEventListener() {},
  };
  window.HaI18n = i18nHelper({ strings: translate === undefined ? {} : { 'shell.panel_switcher.title': translate } });
  const peers = suppliedPeers || [
    { panel_id: 'beta', name: 'Beta panel', ip: '192.168.1.20', port: 8080 },
    { panel_id: 'alpha', name: 'Alpha panel', ip: '192.168.1.10', port: 8080, self: true },
  ];

  const context = vm.createContext({
    window,
    document,
    fetch: async () => ({ status: 200, headers: { get: () => 'application/json', forEach() {} }, text: async () => JSON.stringify(peers) }),
    URL,
    Number,
    Promise,
    setTimeout,
    clearTimeout,
  });
  installTransport(context);
  vm.runInContext(source, context);
  await new Promise((done) => setImmediate(done));
  await new Promise((done) => setImmediate(done));
  return { select: created.find((node) => node.tagName === 'SELECT'), window };
}

test('panel switcher resolves its title through the shared catalogue helper', async () => {
  const rig = await loadSwitcher({ translate: '切换到另一个 ha-paneld 面板（保留当前视图）' });

  assert.equal(rig.select.title, '切换到另一个 ha-paneld 面板（保留当前视图）');
  assert.deepEqual(rig.select.options.map((option) => option.textContent), ['Alpha panel', 'Beta panel']);
  assert.deepEqual(rig.select.options.map((option) => option.value), ['', 'beta']);

  rig.select.selectedIndex = 1;
  rig.select.onchange();
  assert.equal(rig.window.location.href, 'http://192.168.1.20:8080/configure?lang=zh-Hans#network');
  assert.equal(rig.select.selectedIndex, 0);
});

test('panel switcher retains the exact English title when untranslated', async () => {
  const rig = await loadSwitcher();

  assert.equal(rig.select.title, ENGLISH_TITLE);
});

browserTest('untrusted translated title and peer names remain inert text in the browser', async t => {
  const payload = '<img src=x onerror="globalThis.peerExecuted=true">';
  const peers = [
    { panel_id: 'beta', name: payload, ip: '192.168.1.20', port: 8080 },
    { panel_id: 'alpha', name: 'Alpha panel', ip: '192.168.1.10', port: 8080, self: true },
  ];
  const harness = await startHarness(path => path === '/api/v1/peers' ? json(peers) : undefined,
    () => `<!doctype html><html><head>${i18nBridge({ strings: { 'shell.panel_switcher.title': payload } })}</head><body><small id="pswitch" data-self-id="alpha" data-self-name="Alpha panel"></small><script src="/assets/switcher.js"></script></body></html>`);
  let browser;
  t.after(async () => { await browser?.close(); harness.server.closeAllConnections?.(); await new Promise(done => harness.server.close(done)); });
  browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  await page.goto(harness.url);
  await page.locator('#pswitch select').waitFor();
  assert.equal(await page.locator('#pswitch select').getAttribute('title'), payload);
  assert.deepEqual(await page.locator('#pswitch option').allTextContents(), [payload, 'Alpha panel']);
  assert.equal(await page.locator('#pswitch img').count(), 0);
  assert.equal(await page.evaluate(() => globalThis.peerExecuted), undefined);
});
