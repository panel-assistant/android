// The Home Assistant network-path banner and diagnostics row in buildwatch.js.
//
// Both are rendered from the /health tokens by the ten-second poll, so they retract on recovery and
// never depend on a server-side render. This fixture feeds the script one /health line at a time and
// asserts what each surface shows: absent tokens hide and empty, a warning is a Warning row, severe is
// an Action needed row, and the wording carries only the terse evidence numbers.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import { loadTransport } from './page-transport.mjs';
import { i18nHelper, installHaNotice } from '../../../../tools/test/fixtures/i18n-bridge.mjs';

const source = fs.readFileSync(process.argv[2], 'utf8');

function element(id) {
  let ownText = '';
  const children = [];
  const attributes = {};
  return {
    id, innerHTML: '', className: '', style: { display: 'none' }, children,
    get textContent() { return ownText + children.map((child) => child.textContent).join(''); },
    set textContent(value) { ownText = String(value); children.splice(0); },
    appendChild(child) { children.push(child); return child; },
    setAttribute(name, value) { attributes[name] = String(value); },
    getAttribute(name) { return attributes[name] ?? ''; },
  };
}
// The row as a person reads it: severity, pill word, and the message.
function notice(row) {
  const part = (cls) => row.children.find((child) => child.className === cls);
  return { severity: row.getAttribute('data-severity'), pill: part('notice-pill')?.textContent, text: part('notice-text')?.textContent ?? '' };
}

const ids = { hanetbar: element('hanetbar'), hanetcell: element('hanetcell'), halifebar: element('halifebar'), halifecell: element('halifecell') };
let health = '';
let ticks = [];

global.document = {
  getElementById: (id) => ids[id] || null,
  body: { getAttribute() { return ''; } },
  querySelector: () => null,
  querySelectorAll: () => [],
  addEventListener() {},
  createElement: (tag) => element(tag),
  createTextNode: (text) => { const node = element('#text'); node.textContent = text; return node; },
};
global.window = { addEventListener() {}, HaI18n: i18nHelper() };
global.location = { pathname: '/', reload() { throw new Error('reload must not fire'); } };
global.fetch = () => Promise.resolve({ text: () => Promise.resolve(health) });
global.setInterval = () => 0;
// The page follows the `health` family, which the page transport polls at the family's floor: those timers
// are the poll ticks; any other timer runs at once.
global.setTimeout = (fn, ms) => {
  if (window.PaneldTransport && ms === window.PaneldTransport.families.health.floor) ticks.push(fn); else fn();
  return 0;
};

installHaNotice(globalThis);
loadTransport(process.argv[2]);
vm.runInThisContext(source, { filename: 'buildwatch.js' });
// The subscription's first read, sent as the page starts.
await new Promise((resolve) => setImmediate(resolve));

async function poll(line) {
  health = line;
  assert.equal(ticks.length, 1, 'one poll is registered');
  ticks.pop()();
  // The poll's request and its delivery settle within the turn.
  await new Promise((resolve) => setImmediate(resolve));
}

const base = 'ha-paneld 0.9.7 panel=p build=b cfg=c';

// 1. No token: not measured. Banner hidden, row emptied, tone untouched.
await poll(base);
assert.equal(ids.hanetbar.style.display, 'none');
assert.equal(ids.hanetcell.textContent, '');

// 2. Healthy: no banner, but the row says so with the evidence.
await poll(base + ' ha_net=healthy ha_resp=healthy ha_net_p95=23 ha_net_n=30 ha_net_miss=0');
assert.equal(ids.hanetbar.style.display, 'none');
assert.equal(ids.hanetcell.textContent, 'healthy; p95 23 ms, no misses in the last 5 min');

// 3. Warning: soft tone, wording names the path and the numbers, no markup.
await poll(base + ' ha=normal ha_net=warning ha_resp=healthy ha_net_p95=240 ha_net_n=30 ha_net_miss=0');
assert.equal(ids.hanetbar.style.display, '');
assert.deepEqual([notice(ids.hanetbar).severity, notice(ids.hanetbar).pill], ['warning', 'Warning']);
assert.equal(ids.hanetbar.innerHTML, '', 'textContent only');
assert.equal(
  notice(ids.hanetbar).text,
  'Probes to Home Assistant are going missing: p95 240 ms, no misses in the last 5 min. '
  + 'Packets are not getting through. Check the Wi-Fi path between this panel and Home Assistant before blaming the panel.',
);
assert.equal(ids.hanetcell.textContent, 'losing probes; p95 240 ms, no misses in the last 5 min');
// The lifecycle pair on the same line is untouched by the network tokens.
assert.equal(ids.halifebar.style.display, 'none');

// 4. Severe: the existing severe-warning tone, misses counted, thousands separated.
await poll(base + ' ha_net=severe ha_resp=healthy ha_net_p95=4200 ha_net_n=30 ha_net_miss=3');
assert.deepEqual([notice(ids.hanetbar).severity, notice(ids.hanetbar).pill], ['action_needed', 'Action needed']);
assert.equal(
  notice(ids.hanetbar).text,
  'The network path to Home Assistant is failing: p95 4,200 ms, 3 of 30 probes missed in the last 5 min. '
  + 'Packets are not getting through. Check the Wi-Fi path between this panel and Home Assistant before blaming the panel.',
);
assert.equal(ids.hanetcell.textContent, 'failing; p95 4,200 ms, 3 of 30 probes missed in the last 5 min');

// 5. Severe with nothing answering at all: p95 is -1 and the wording says so rather than "-1 ms".
await poll(base + ' ha_net=severe ha_resp=healthy ha_net_p95=-1 ha_net_n=4 ha_net_miss=4');
assert.equal(ids.hanetcell.textContent, 'failing; no reply, 4 of 4 probes missed in the last 5 min');

// 6. Measuring with no probe yet: honest, not "0 ms".
await poll(base + ' ha_net=healthy ha_resp=healthy ha_net_p95=-1 ha_net_n=0 ha_net_miss=0 ha_net_age=-1');
assert.equal(ids.hanetcell.textContent, 'healthy; no probes yet in the last 5 min');

// 6b. An empty window with a remembered reply is a parked stream, not a fresh connect.
await poll(base + ' ha_net=healthy ha_resp=healthy ha_net_p95=-1 ha_net_n=0 ha_net_miss=0 ha_net_age=420000');
assert.equal(ids.hanetcell.textContent, 'healthy; no probe answered in the last 5 min; last reply 7 min ago');
await poll(base + ' ha_net=healthy ha_resp=healthy ha_net_p95=-1 ha_net_n=0 ha_net_miss=0 ha_net_age=45000');
assert.equal(ids.hanetcell.textContent, 'healthy; no probe answered in the last 5 min; last reply 45 s ago');

// 7. Recovery: the token drops back to healthy and the banner retracts on the next poll.
await poll(base + ' ha_net=healthy ha_resp=healthy ha_net_p95=30 ha_net_n=30 ha_net_miss=1');
assert.equal(ids.hanetbar.style.display, 'none');
assert.equal(ids.hanetcell.textContent, 'healthy; p95 30 ms, 1 of 30 probes missed in the last 5 min');

// 8. Socket gone (renderer deselected): tokens vanish and both surfaces clear, not freeze.
await poll(base + ' ha_net=severe ha_resp=healthy ha_net_p95=4200 ha_net_n=30 ha_net_miss=3');
await poll(base);
assert.equal(ids.hanetbar.style.display, 'none');
assert.equal(ids.hanetcell.textContent, '');


// ---- the lane's defining browser cases -------------------------------------------------------
// A slow server on an intact path fills the response row and leaves the banner hidden. This is the
// exact shape that told a wired panel, whose real path was around a millisecond, that its
// network was slow.
await poll(base + ' ha_net=healthy ha_resp=warning ha_net_p95=592 ha_net_n=30 ha_net_miss=0');
assert.equal(ids.hanetbar.style.display, 'none');
assert.equal(ids.hanetcell.textContent, 'healthy; Home Assistant answering slowly; p95 592 ms, no misses in the last 5 min');

await poll(base + ' ha_net=healthy ha_resp=severe ha_net_p95=4200 ha_net_n=30 ha_net_miss=0');
assert.equal(ids.hanetbar.style.display, 'none');
assert.equal(ids.hanetcell.textContent, 'healthy; Home Assistant answering very slowly; p95 4,200 ms, no misses in the last 5 min');

// Settling: measured, but no verdict yet, and never a banner.
await poll(base + ' ha_net=settling');
assert.equal(ids.hanetbar.style.display, 'none');
assert.equal(ids.hanetcell.textContent, 'settling after startup; no verdict yet');

// Loss still raises the banner: that is the evidence the prominence is reserved for.
await poll(base + ' ha_net=warning ha_resp=healthy ha_net_p95=30 ha_net_n=30 ha_net_miss=2');
assert.notEqual(ids.hanetbar.style.display, 'none');
assert.equal(ids.hanetcell.textContent, 'losing probes; p95 30 ms, 2 of 30 probes missed in the last 5 min');

// ---- the latency cause, which the first submission never actually reached ---------------------
// The banner took six arguments while the poll passed seven, so `cause` was dropped and a
// zero-loss latency verdict told the user packets were going missing. These fail on that.

await poll(base + ' ha_net=warning ha_net_cause=latency ha_resp=healthy ha_net_p95=9 ha_net_n=30 ha_net_miss=0');
assert.notEqual(ids.hanetbar.style.display, 'none');
assert.ok(/slow/.test(ids.hanetbar.textContent), 'latency banner must say slow: ' + ids.hanetbar.textContent);
assert.ok(!/going missing|not getting through|probes missed/.test(ids.hanetbar.textContent),
  'loss wording must never appear for a latency cause: ' + ids.hanetbar.textContent);
// The verdict is the probe's; the WebSocket p95 must not be offered as proof of it.
assert.ok(!/p95/.test(ids.hanetbar.textContent), 'the other instrument\'s evidence must be omitted: ' + ids.hanetbar.textContent);
assert.ok(!/p95/.test(ids.hanetcell.textContent), 'the row must omit it too: ' + ids.hanetcell.textContent);
assert.equal(ids.hanetcell.textContent, 'slow');

await poll(base + ' ha_net=severe ha_net_cause=latency ha_resp=healthy ha_net_p95=9 ha_net_n=30 ha_net_miss=0');
assert.ok(/very slow/.test(ids.hanetbar.textContent), ids.hanetbar.textContent);
assert.deepEqual([notice(ids.hanetbar).severity, notice(ids.hanetbar).pill], ['action_needed', 'Action needed']);
assert.equal(ids.hanetcell.textContent, 'very slow');

await poll(base + ' ha_net=warning ha_net_cause=latency ha_resp=warning ha_net_p95=9 ha_net_n=30 ha_net_miss=0');
assert.equal(ids.hanetcell.textContent, 'slow; Home Assistant answering slowly');
await poll(base + ' ha_net=severe ha_net_cause=latency ha_resp=severe ha_net_p95=9 ha_net_n=30 ha_net_miss=0');
assert.equal(ids.hanetcell.textContent, 'very slow; Home Assistant answering very slowly');

// An unknown future cause cannot be misreported as packet loss or retain stale text.
for (const cause of ['future_value', 'constructor', '__proto__']) {
  await poll(base + ' ha_net=warning ha_net_cause=loss ha_resp=healthy ha_net_p95=30 ha_net_n=30 ha_net_miss=2');
  await poll(base + ' ha_net=warning ha_net_cause=' + cause + ' ha_resp=healthy ha_net_p95=30 ha_net_n=30 ha_net_miss=2');
  assert.equal(ids.hanetbar.style.display, 'none');
  assert.equal(ids.hanetcell.textContent, '');
}

// A loss cause keeps the loss wording and its own evidence.
await poll(base + ' ha_net=warning ha_net_cause=loss ha_resp=healthy ha_net_p95=30 ha_net_n=30 ha_net_miss=2');
assert.ok(/going missing/.test(ids.hanetbar.textContent), ids.hanetbar.textContent);
assert.ok(/2 of 30 probes missed/.test(ids.hanetbar.textContent), ids.hanetbar.textContent);

// Legacy fallback: a panel answering without the token behaves exactly as before.
await poll(base + ' ha_net=warning ha_resp=healthy ha_net_p95=30 ha_net_n=30 ha_net_miss=2');
assert.ok(/going missing/.test(ids.hanetbar.textContent), ids.hanetbar.textContent);
assert.ok(/2 of 30 probes missed/.test(ids.hanetcell.textContent), ids.hanetcell.textContent);

// An echo verdict carries its own evidence; the old socket fields keep their wire meaning.
const socketEvidence = ' ha_resp=healthy ha_net_p95=9 ha_net_n=30 ha_net_miss=0';
const echoEvidence = ' ha_net_source=echo ha_net_echo_n=5 ha_net_echo_miss=2 ha_net_echo_p95=12 ha_net_echo_bursts=1';
await poll(base + ' ha_net=severe ha_net_cause=loss' + socketEvidence + echoEvidence);
assert.match(notice(ids.hanetbar).text, /2 of 5 network checks missed across 1 bursts/);
assert.equal(ids.hanetcell.textContent, 'failing; p95 12 ms, 2 of 5 network checks missed across 1 bursts');
assert.doesNotMatch(ids.hanetbar.textContent + ids.hanetcell.textContent, /p95 9 ms|no misses|last 5 min/);
await poll(base + ' ha_net=severe ha_net_cause=latency' + socketEvidence
  + ' ha_net_source=echo ha_net_echo_n=5 ha_net_echo_miss=0 ha_net_echo_p95=500 ha_net_echo_bursts=1');
assert.match(notice(ids.hanetbar).text, /very slow/);
assert.match(notice(ids.hanetbar).text, /p95 500 ms, 0 of 5 network checks missed across 1 bursts/);
assert.equal(ids.hanetcell.textContent, 'very slow; p95 500 ms, 0 of 5 network checks missed across 1 bursts');
assert.doesNotMatch(ids.hanetbar.textContent + ids.hanetcell.textContent, /p95 9 ms|last 5 min/);
await poll(base + ' ha_net=healthy' + socketEvidence + echoEvidence);
assert.equal(ids.hanetbar.style.display, 'none');
assert.equal(ids.hanetcell.textContent, 'healthy; p95 12 ms, 2 of 5 network checks missed across 1 bursts');
await poll(base + ' ha_net=severe ha_net_cause=loss' + socketEvidence
  + ' ha_net_source=echo ha_net_echo_n=5 ha_net_echo_miss=5 ha_net_echo_p95=-1 ha_net_echo_bursts=1');
assert.match(ids.hanetcell.textContent, /no reply, 5 of 5 network checks missed across 1 bursts/);
// Missing, malformed, or future source evidence must never borrow the socket numbers.
for (const suffix of [
  ' ha_net_source=future', ' ha_net_source=constructor', ' ha_net_source=__proto__',
  ' ha_net_source=echo',
  ' ha_net_source=echo ha_net_echo_n=5 ha_net_echo_miss=6 ha_net_echo_p95=500 ha_net_echo_bursts=1',
]) {
  await poll(base + ' ha_net=severe ha_net_cause=loss' + socketEvidence + suffix);
  assert.equal(ids.hanetbar.style.display, 'none');
  assert.equal(ids.hanetcell.textContent, '');
}
await poll(base + ' ha_net=severe ha_net_cause=loss' + socketEvidence + echoEvidence);
await poll(base);
assert.equal(ids.hanetbar.style.display, 'none');
assert.equal(ids.hanetcell.textContent, '');
// Explicit socket source remains the legacy positive control.
await poll(base + ' ha_net=warning ha_net_cause=loss ha_net_source=socket ha_resp=healthy ha_net_p95=30 ha_net_n=30 ha_net_miss=2');
assert.equal(ids.hanetcell.textContent, 'losing probes; p95 30 ms, 2 of 30 probes missed in the last 5 min');

console.log('ha network banner cases passed');
