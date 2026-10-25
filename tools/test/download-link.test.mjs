// The page shell's download handler (PageShell.kt) over the socket: a modified click keeps the plain link, a link
// to a new tab opens that tab inside the click, and a saved file keeps the panel's own name.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const shell = readFileSync(new URL('../../app/src/main/kotlin/io/panelassistant/android/http/PageShell.kt', import.meta.url), 'utf8');
const script = shell.match(/<script>(\/\* Over the stub's socket a download link[\s\S]*?)<\/script>/)[1];

function load(reply, door = 'data-ui-socket', blocked = false) {
  let onClick;
  const calls = [], saved = [], opened = [];
  const window = {
    open(url, target) { calls.push(['open', url, target]); return blocked ? null : { opener: 'x', location: {}, close() { calls.push(['close']); } }; },
    alert(text) { calls.push(['alert', text]); },
  };
  const document = {
    addEventListener(name, fn) { if (name === 'click') onClick = fn; },
    body: { hasAttribute: (name) => name === door, appendChild() {} },
    createElement: () => ({ click() { saved.push(this.download); }, remove() {} }),
  };
  window.PaneldTransport = { request(op) { calls.push(['request', op]); return Promise.resolve(reply); } };
  vm.runInNewContext(script, {
    window, document, PaneldTransport: window.PaneldTransport, Blob, URL: { createObjectURL: (blob) => { opened.push(blob); return 'blob:x'; }, revokeObjectURL() {} },
    setTimeout() {},
  });
  const click = async (link, modifiers = {}) => {
    const event = { target: { closest: () => link }, defaultPrevented: false, button: 0, ...modifiers, preventDefault() { this.defaultPrevented = true; } };
    onClick(event);
    const synchronous = calls.slice();
    for (let i = 0; i < 10; i++) await Promise.resolve();
    return { event, synchronous };
  };
  return { click, calls, saved, opened };
}

const link = (target) => ({ target, hasAttribute: () => false, getAttribute: (name) => ({ 'data-op': 'config.export', 'data-file': 'config.json' })[name] });

test('a modified click is left to the plain link', async () => {
  const rig = load({ outcome: 'applied', result: new Blob(['{}']) });
  for (const modifier of ['ctrlKey', 'metaKey', 'shiftKey', 'altKey']) {
    const { event } = await rig.click(link(''), { [modifier]: true });
    assert.equal(event.defaultPrevented, false, modifier);
  }
  const { event } = await rig.click(link(''), { button: 1 });
  assert.equal(event.defaultPrevented, false, 'middle button');
  assert.deepEqual(rig.calls, []);
});

test('a new tab opens within the click, before the answer', async () => {
  const rig = load({ outcome: 'applied', result: new Blob(['png']) });
  const { synchronous } = await rig.click(link('_blank'));
  assert.equal(synchronous[0][0], 'open');
});

test('a blocked new tab still saves the file', async () => {
  const rig = load({ outcome: 'applied', result: new Blob(['png']) }, 'data-ui-socket', true);
  await rig.click({ target: '_blank', hasAttribute: () => false, getAttribute: (name) => ({ 'data-op': 'screenshot', 'data-file': 'screenshot.png' })[name] });
  assert.deepEqual(rig.calls, [['open', '', '_blank'], ['request', 'screenshot']]);
  assert.deepEqual(rig.saved, ['screenshot.png']);
});

test('a saved file keeps the name the panel gave it', async () => {
  const rig = load({ outcome: 'applied', result: new Blob(['{}']), headers: { 'content-disposition': 'attachment; filename="panel-7-config.json"' } });
  await rig.click(link(''));
  assert.deepEqual(rig.saved, ['panel-7-config.json']);
});

test("in the sidebar relay a download link goes through the transport too", async () => {
  const rig = load({ outcome: 'applied', result: new Blob(['{}']) }, 'data-ui-relay');
  const { event } = await rig.click(link(''));
  assert.equal(event.defaultPrevented, true);
  assert.deepEqual(rig.calls, [['request', 'config.export']]);
  assert.deepEqual(rig.saved, ['config.json']);
});

test('a page on plain HTTP leaves the link to the browser', async () => {
  const rig = load({ outcome: 'applied', result: new Blob(['{}']) }, null);
  const { event } = await rig.click(link(''));
  assert.equal(event.defaultPrevented, false);
  assert.deepEqual(rig.calls, []);
});

test('in the sidebar the Diagnostics link fetches diag through the transport and saves it as text', async () => {
  const rig = load({ outcome: 'applied', result: 'ha-paneld diagnostics\n' }, 'data-ui-relay');
  const diag = { target: '_blank', hasAttribute: (name) => name === 'data-relay-only', getAttribute: (name) => ({ 'data-op': 'diag', 'data-file': 'diag.txt' })[name] };
  const { event } = await rig.click(diag);
  assert.equal(event.defaultPrevented, true);
  assert.deepEqual(rig.calls, [['request', 'diag']]);
  assert.deepEqual(rig.saved, ['diag.txt']);
  assert.equal(rig.opened[0].type, 'text/plain');
  assert.equal(await rig.opened[0].text(), 'ha-paneld diagnostics\n');
});

test('on the LAN socket page a relay-only link (Diagnostics) is left to its plain link', async () => {
  const rig = load({ outcome: 'applied', result: 'x' });
  const diag = { target: '_blank', hasAttribute: (name) => name === 'data-relay-only', getAttribute: (name) => ({ 'data-op': 'diag', 'data-file': 'diag.txt' })[name] };
  const { event } = await rig.click(diag);
  assert.equal(event.defaultPrevented, false);
  assert.deepEqual(rig.calls, []);
});
