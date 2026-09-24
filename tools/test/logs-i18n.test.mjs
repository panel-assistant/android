import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const asset = process.argv[2] || new URL('../../app/src/main/assets/logs.js', import.meta.url);

function element(id) {
  return {
    id,
    textContent: '',
    value: id === 'lg-level' ? 'V' : '',
    checked: id === 'lg-follow',
    childNodes: [],
    scrollHeight: 0,
    scrollTop: 0,
    clientHeight: 100,
    classList: { toggle() {} },
    addEventListener(_name, callback) { this.listener = callback; },
    appendChild(child) {
      if (child?.children) this.childNodes.push(...child.children);
      else this.childNodes.push(child);
    },
    removeChild(child) { this.childNodes.splice(this.childNodes.indexOf(child), 1); },
    get firstChild() { return this.childNodes[0]; },
    style: {},
  };
}

async function loadLogs(translations = {}, helper = true) {
  const source = await readFile(asset, 'utf8');
  const ids = ['lg-out', 'lg-state', 'lg-pause', 'lg-src-app', 'lg-src-system', 'lg-src-webview', 'lg-level', 'lg-filter', 'lg-follow'];
  const nodes = Object.fromEntries(ids.map((id) => [id, element(id)]));
  const events = [];
  const document = {
    hidden: false,
    body: { getBoundingClientRect: () => ({ bottom: 600 }) },
    getElementById: (id) => nodes[id] || null,
    createElement: () => element('line'),
    createDocumentFragment: () => ({ children: [], appendChild(child) { this.children.push(child); } }),
    addEventListener(name, callback) { events.push({ name, callback }); },
  };
  const streams = [];
  class EventSource {
    constructor(url) { this.url = url; this.closed = false; streams.push(this); }
    close() { this.closed = true; }
  }
  const calls = [];
  const window = {
    innerHeight: 800,
    document,
    EventSource,
    addEventListener() {},
  };
  if (helper) {
    window.HaI18n = {
      t(key, fallback) {
        calls.push({ key, fallback });
        return Object.prototype.hasOwnProperty.call(translations, key) ? translations[key] : fallback;
      },
    };
  }
  vm.runInNewContext(source, { window, document, EventSource });
  return { window, document, nodes, streams, calls, events };
}

test('logs status and pause controls consume translations while preserving UI symbols and source tokens', async () => {
  const translations = {
    'logs.state.connecting': 'CONNECTING-T',
    'logs.state.app_live': 'APP-LIVE-T',
    'logs.state.app_paused': 'APP-PAUSED-T',
    'logs.state.system_live': 'SYSTEM-LIVE-T',
    'logs.state.system_paused': 'SYSTEM-PAUSED-T',
    'logs.state.webview_live': 'WEBVIEW-LIVE-T',
    'logs.state.webview_paused': 'WEBVIEW-PAUSED-T',
    'logs.state.reconnecting': 'RECONNECTING-T',
    'logs.state.hidden': 'HIDDEN-T',
    'logs.action.pause': 'PAUSE-T',
    'logs.action.resume': 'RESUME-T',
  };
  const rig = await loadLogs(translations);

  assert.equal(rig.nodes['lg-state'].textContent, '· CONNECTING-T');
  assert.equal(rig.streams[0].url, 'api/v1/logs/stream?source=app');
  rig.streams[0].onopen();
  assert.equal(rig.nodes['lg-state'].textContent, '· APP-LIVE-T');

  rig.window.lgPause();
  assert.equal(rig.nodes['lg-pause'].textContent, '▶ RESUME-T');
  assert.equal(rig.nodes['lg-state'].textContent, '· APP-PAUSED-T');
  rig.window.lgPause();
  assert.equal(rig.nodes['lg-pause'].textContent, '⏸ PAUSE-T');
  assert.equal(rig.nodes['lg-state'].textContent, '· APP-LIVE-T');

  rig.window.lgSource('system');
  assert.equal(rig.streams[1].url, 'api/v1/logs/stream?source=system');
  rig.streams[1].onopen();
  assert.equal(rig.nodes['lg-state'].textContent, '· SYSTEM-LIVE-T');
  rig.window.lgPause();
  assert.equal(rig.nodes['lg-state'].textContent, '· SYSTEM-PAUSED-T');
  rig.window.lgPause();
  assert.equal(rig.nodes['lg-state'].textContent, '· SYSTEM-LIVE-T');
  rig.streams[1].onerror();
  assert.equal(rig.nodes['lg-state'].textContent, '· RECONNECTING-T');

  rig.window.lgSource('webview');
  assert.equal(rig.streams[2].url, 'api/v1/logs/stream?source=webview');
  rig.streams[2].onopen();
  assert.equal(rig.nodes['lg-state'].textContent, '· WEBVIEW-LIVE-T');
  rig.window.lgPause();
  assert.equal(rig.nodes['lg-state'].textContent, '· WEBVIEW-PAUSED-T');
  rig.window.lgPause();
  assert.equal(rig.nodes['lg-state'].textContent, '· WEBVIEW-LIVE-T');

  rig.document.hidden = true;
  rig.events.find(({ name }) => name === 'visibilitychange').callback();
  assert.equal(rig.nodes['lg-state'].textContent, '· HIDDEN-T');

  assert.deepEqual(
    [...new Set(rig.calls.map(({ key }) => key))].sort(),
    Object.keys(translations).sort(),
  );
});

test('logs keeps raw SSE evidence verbatim and falls back safely when the i18n helper is absent', async () => {
  const raw = '09-03 12:34:56.789  123  456 E Tag: token=<redacted> /api/v1/status?x=1&y=2';
  const translated = await loadLogs({ 'logs.state.connecting': '已连接' });

  translated.streams[0].onmessage({ data: raw });
  assert.equal(translated.nodes['lg-out'].childNodes.length, 1);
  assert.equal(translated.nodes['lg-out'].childNodes[0].textContent, raw);

  const rig = await loadLogs({}, false);

  assert.equal(rig.nodes['lg-state'].textContent, '· connecting…');
  rig.streams[0].onmessage({ data: raw });
  assert.equal(rig.nodes['lg-out'].childNodes.length, 1);
  assert.equal(rig.nodes['lg-out'].childNodes[0].textContent, raw);

  rig.window.lgPause();
  assert.equal(rig.nodes['lg-pause'].textContent, '▶ Resume');
  rig.window.lgPause();
  assert.equal(rig.nodes['lg-pause'].textContent, '⏸ Pause');
});

test('logs script exposes one guarded English-safe adapter and never translates raw entries', async () => {
  const source = await readFile(asset, 'utf8');

  assert.match(source, /function\s+i18nText\s*\(key\s*,\s*(?:englishFallback|fallback)/);
  assert.match(source, /typeof\s+window\.HaI18n\.t\s*===\s*["']function["']/);
  assert.match(source, /d\.textContent\s*=\s*entry\.raw/);
  assert.match(source, /append\(\{\s*raw:\s*e\.data\s*,\s*lvl:\s*levelOf\(e\.data\)\s*\}\)/);
  assert.doesNotMatch(source, /i18nText\([^)]*entry\.raw/);
});
