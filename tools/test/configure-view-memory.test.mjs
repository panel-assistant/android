import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../../app/src/main/assets/configure-view.js', import.meta.url), 'utf8');
const type = 'panel-assistant-config-view';
const origin = 'https://home.test';

function page() {
  const listeners = {}, inputs = {}, sent = [];
  const filter = { value: '', id: 'cfg-filter' };
  const cfg = { advanced: false, descriptions: true, revealedGroups: {}, filterText: '',
    CONFIG_VIEW_STORAGE_KEY: 'ha-paneld.configure.view.v1', closeHelp() {}, render() {} };
  const parent = { postMessage(message, target) { sent.push({ message, target }); } };
  const window = { ConfigurePage: cfg, parent,
    addEventListener(name, callback) { listeners[name] = callback; },
    get localStorage() { throw new Error('opaque origin'); } };
  const document = { body: { hasAttribute: name => name === 'data-ui-relay' },
    getElementById: id => id === 'cfg-filter' ? filter : null,
    addEventListener(name, callback) { inputs[name] = callback; } };
  vm.runInNewContext(source, { window, document, location: { origin }, setTimeout, clearTimeout });
  return { cfg, parent, filter, sent, inputs, reply(value, options = {}) {
    listeners.message({ source: parent, origin, data: { type, value }, ...options });
  } };
}

test('embedded Configure restores browser preferences and writes changes despite denied storage', () => {
  const p = page();
  p.cfg.readConfigView();
  assert.equal(p.sent[0].message.read, true);
  assert.equal(p.sent[0].target, origin);
  const saved = { advanced: true, descriptions: false, revealed: ['Voice'], filter: 'microphone' };
  p.reply(saved, { source: {} });
  p.reply(saved, { origin: 'https://other.test' });
  assert.equal(p.cfg.advanced, false);
  p.reply(saved);
  assert.equal(p.cfg.advanced, true);
  assert.equal(p.cfg.descriptions, false);
  assert.equal(p.cfg.revealedGroups.Voice, true);
  assert.equal(p.filter.value, 'microphone');
  p.cfg.advanced = false;
  p.cfg.configViewChanged();
  const message = JSON.parse(JSON.stringify(p.sent.at(-1).message));
  assert.deepEqual(message, { type, value: { ...saved, advanced: false } });
});

test('late preferences do not erase view changes or filter text entered before debounce', () => {
  const saved = { advanced: false, descriptions: true, revealed: [], filter: 'old' };
  const tier = page();
  tier.cfg.readConfigView();
  tier.cfg.advanced = true;
  tier.cfg.configViewChanged();
  tier.reply(saved);
  assert.equal(tier.cfg.advanced, true);
  const search = page();
  search.cfg.readConfigView();
  search.filter.value = 'new search';
  search.inputs.input({ target: search.filter });
  search.reply(saved);
  assert.equal(search.filter.value, 'new search');
});
