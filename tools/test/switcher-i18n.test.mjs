import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import vm from 'node:vm';
import { i18nHelper } from './fixtures/i18n-bridge.mjs';

const defaultAsset = fileURLToPath(new URL('../../app/src/main/assets/switcher.js', import.meta.url));
const switcherAsset = process.argv[2] ? resolve(process.argv[2]) : defaultAsset;
const ENGLISH_TITLE = 'Switch to another ha-paneld panel (keeps the current view)';

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
  const calls = [];
  const window = {
    document,
    location: { pathname: '/configure', search: '?lang=zh-Hans', hash: '#network', href: '' },
    addEventListener() {},
  };
  window.HaI18n = i18nHelper({ strings: translate === undefined ? {} : { 'shell.panel_switcher.title': translate } }, calls);
  const peers = suppliedPeers || [
    { panel_id: 'beta', name: 'Beta panel', ip: '192.168.1.20', port: 8080 },
    { panel_id: 'alpha', name: 'Alpha panel', ip: '192.168.1.10', port: 8080, self: true },
  ];

  vm.runInNewContext(source, {
    window,
    document,
    fetch: async () => ({ json: async () => peers }),
    URL,
    Number,
    Promise,
    setTimeout,
    clearTimeout,
  });
  await new Promise((done) => setImmediate(done));
  await new Promise((done) => setImmediate(done));
  return { calls, created, host, select: created.find((node) => node.tagName === 'SELECT'), source, window };
}

test('panel switcher resolves its title through the shared catalogue helper', async () => {
  const rig = await loadSwitcher({ translate: '切换到另一个 ha-paneld 面板（保留当前视图）' });

  assert.deepEqual(rig.calls, [{ key: 'shell.panel_switcher.title', fallback: ENGLISH_TITLE }]);
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

test('untrusted translated title and peer names remain inert text', async () => {
  const payload = '<img src=x onerror="globalThis.peerExecuted=true">';
  const rig = await loadSwitcher({
    translate: payload,
    peers: [
      { panel_id: 'beta', name: payload, ip: '192.168.1.20', port: 8080 },
      { panel_id: 'alpha', name: 'Alpha panel', ip: '192.168.1.10', port: 8080, self: true },
    ],
  });

  assert.equal(rig.select.title, payload);
  assert.deepEqual(rig.select.options.map((option) => option.textContent), [payload, 'Alpha panel']);
  assert.deepEqual(rig.created.map((node) => node.tagName), ['SELECT', 'OPTION', 'OPTION', 'SPAN']);
  assert.doesNotMatch(rig.source, /\.innerHTML\s*=/);
});
