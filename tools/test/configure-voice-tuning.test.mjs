// The Voice card's microphone tuning, in both engines: the owner's diagnostics (level, wake-word test, the last
// command and what Assist made of it) and the wizard that measures the room, suggests the gain and sensitivity,
// saves them through the ordinary settings, and puts back what it changed when cancelled.
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';

const root = join(process.cwd(), '..', '..', 'app', 'src', 'main', 'assets');
const chrome = process.env.CHROME || '/usr/bin/chromium';
const SCRIPTS = ['/configure-state.js', '/configure-view.js', '/configure-help.js', '/configure-controls.js', '/configure-brightness.js', '/configure-auto-sleep.js', '/configure-cards.js', '/configure-render.js', '/configure-voice-tuning.js', '/configure.js'];

const page = `<!doctype html><html lang="en"><head><meta charset="utf-8"><link rel="stylesheet" href="/info.css">${i18nBridge()}</head><body>
  <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
  <button id="tab-basic"></button><button id="tab-adv"></button>
  <div id="cfg-status" class="muted"></div><div id="cfg-groups" class="cards"></div>
  <div id="cfg-msg" class="sr-only" role="status" aria-live="polite" aria-atomic="true"></div>
  <div id="cfg-help" class="cfg-help" popover="manual"><div class="cfg-help-head"><b id="cfg-help-title"></b><button id="cfg-help-close" type="button">x</button></div><div id="cfg-help-body" class="cfg-help-body"></div><div class="cfg-help-foot"><a id="cfg-help-more"></a></div></div>
  <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
  ${SCRIPTS.map((s) => `<script src="${s}"></script>`).join('')}
</body></html>`;

const SCHEMA = [
  { key: 'voice_enabled', type: 'BOOL', group: 'Voice', tier: 'BASIC', available: true, label: 'Voice assistant' },
  { key: 'voice_audio_source', type: 'ENUM', group: 'Voice', tier: 'ADVANCED', available: true, label: 'Audio source', options: ['voice_recognition', 'mic'], optionLabels: ['Voice recognition', 'Microphone'] },
  { key: 'voice_sensitivity', type: 'ENUM', group: 'Voice', tier: 'BASIC', available: true, label: 'Wake word sensitivity', options: ['low', 'normal', 'high'], optionLabels: ['Low', 'Normal', 'High'] },
  { key: 'voice_capture_rate', type: 'ENUM', group: 'Voice', tier: 'ADVANCED', available: true, label: 'Capture rate', options: ['auto', '16000', '48000'], optionLabels: ['Automatic', '16 kHz', '48 kHz'] },
  { key: 'voice_capture_channels', type: 'ENUM', group: 'Voice', tier: 'ADVANCED', available: true, label: 'Capture channels', options: ['auto', '1', '2'], optionLabels: ['Automatic', 'One', 'Two'] },
  { key: 'voice_mic_gain_db', type: 'INT', group: 'Voice', tier: 'ADVANCED', available: true, label: 'Microphone gain', min: -24, max: 24 },
];

/**
 * A panel's voice diagnostics as the coordinator keeps them: one session owns the meter and the wake-word test;
 * the test drives what the microphone "hears" through [voice].
 */
async function panel() {
  const settings = { voice_enabled: 'true', voice_audio_source: 'voice_recognition', voice_sensitivity: 'normal', voice_mic_gain_db: '0', voice_capture_rate: 'auto', voice_capture_channels: 'auto', dashboard_package: 'builtin' };
  const voice = {
    session: null, level: false, test: null, lastTest: { state: 'idle', model: null, peak: null, cutoff: null, fired: 0, remaining_ms: 0 },
    hearing: { rms_dbfs: -60, peak_dbfs: -52, floor_dbfs: -63, clipped: 0 },
    last: null, actions: [], unmarked: 0,
  };
  const writes = [];
  // Settings the panel cannot open (it falls back to its standard route) and writes it refuses, as `key=value`.
  const unopened = new Set(), refuse = new Set();
  // A write held unanswered until the test releases it, as `key=value`: the reply that lands after Cancel.
  const held = { write: null, waiting: false, release: null };
  const route = () => {
    const rate = settings.voice_capture_rate === 'auto' ? 16000 : Number(settings.voice_capture_rate);
    const channels = settings.voice_capture_channels === 'auto' ? 1 : Number(settings.voice_capture_channels);
    const requested = { source: settings.voice_audio_source, rate_hz: rate, channels, downmix: 'dominance' };
    const fellBack = Object.entries(settings).some(([key, value]) => unopened.has(`${key}=${value}`));
    const active = fellBack ? { source: 'voice_recognition', rate_hz: 16000, channels: 1, downmix: null }
      : { source: requested.source, rate_hz: rate, channels, downmix: channels === 2 ? 'dominance' : null };
    return { requested, active, rebuilds: 0 };
  };
  const json = (post = false) => JSON.stringify({
    session: voice.session,
    level: voice.level ? voice.hearing : null,
    wake_test: voice.test ? { ...voice.test, state: 'running', remaining_ms: 20000 } : voice.lastTest,
    // The panel's POST reply leaves the last command out; only the GET family carries it.
    ...(post ? {} : { last_command: voice.last }),
    route: route(),
  });
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://panel.test');
    const path = url.pathname;
    const send = (body, type = 'application/json', status = 200) => { response.statusCode = status; response.setHeader('content-type', type); response.end(body); };
    const body = async () => { let text = ''; for await (const chunk of request) text += chunk; return new URLSearchParams(text); };
    if (path === '/') return send(page, 'text/html');
    if (SCRIPTS.includes(path)) return send(await readFile(join(root, path.slice(1)), 'utf8'), 'application/javascript');
    if (path === '/info.css') return send(await readFile(join(root, 'info.css'), 'utf8'), 'text/css');
    if (path === '/api/v1/voice/microphone') return send(JSON.stringify({ presence: 'proven', check: 'not_run', detail: null, muted: false }));
    if (path === '/api/v1/voice/diagnostics' && request.method === 'GET') return send(json());
    if (path === '/api/v1/voice/diagnostics' && request.method === 'POST') {
      if (request.headers['x-voice-ui'] !== '1') { voice.unmarked += 1; return send('{"error":"unmarked"}', 'application/json', 403); }
      const form = await body();
      const action = form.get('action'), id = form.get('sessionId');
      voice.actions.push(action);
      if (voice.session && voice.session !== id && (voice.level || voice.test)) return send('{"error":"another browser is tuning this panel\'s microphone"}', 'application/json', 409);
      if (action === 'level') { voice.session = id; voice.level = true; }
      if (action === 'wake_test') { voice.session = id; voice.test = { model: 'okay_nabu', peak: null, cutoff: null, fired: 0 }; }
      if (action === 'stop') {
        if (voice.test) voice.lastTest = { ...voice.test, state: 'done', remaining_ms: 0 };
        voice.session = null; voice.level = false; voice.test = null;
      }
      if (action === 'heartbeat' && !(voice.level || voice.test)) return send('{"error":"this tuning session has ended"}', 'application/json', 409);
      return send(json(true), 'application/json', 202);
    }
    if (path === '/api/v1/config/schema') return send(JSON.stringify(SCHEMA));
    if (path === '/api/v1/config' && request.method === 'POST') {
      const form = await body();
      for (const [key, value] of form) {
        writes.push(`${key}=${value}`);
        if (refuse.has(`${key}=${value}`)) return send('{"error":"refused"}', 'application/json', 409);
        if (held.write === `${key}=${value}`) { held.write = null; await new Promise((resolve) => { held.waiting = true; held.release = resolve; }); }
        settings[key] = value;
      }
      return send('{}');
    }
    if (path === '/api/v1/config' || path === '/api/v1/config/values') return send(JSON.stringify({ settings, ha_expose: {}, ha_auth: { configured: false } }));
    if (path === '/api/v1/apps') return send(JSON.stringify({ apps: [] }));
    if (path === '/api/v1/config/home-dashboards') return send(JSON.stringify({ queried: true, items: [], default: { explicit: false, path: '' } }));
    if (['/api/v1/radio', '/api/v1/proximity'].includes(path)) return send(JSON.stringify({ present: false }));
    if (path === '/api/v1/config/discovery') return send('{}');
    response.statusCode = 404; response.end('not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { voice, writes, settings, unopened, refuse, held, server, url: `http://127.0.0.1:${server.address().port}` };
}

const text = (p, id) => p.evaluate((i) => document.getElementById(i)?.textContent ?? null, id);

async function eventually(what, check, ms = 5000) {
  const until = Date.now() + ms;
  let last;
  while (Date.now() < until) {
    last = await check();
    if (last === true) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert.fail(`${what} (last: ${JSON.stringify(last)})`);
}

const engines = [
  { name: 'chromium', type: chromium, launch: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'webkit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
];

for (const engine of engines) {
  // Both engines are required here: an engine that cannot launch fails rather than skips.
  async function open(t) {
    assert.ok(engine.available, `${engine.name} is installed`);
    const browser = await engine.type.launch(engine.launch);
    const h = await panel();
    t.after(async () => { await browser.close(); await new Promise((resolve) => h.server.close(resolve)); });
    const p = await browser.newPage({ viewport: { width: 480, height: 1400 } });
    await p.goto(h.url);
    await p.locator('#voice-tuning').waitFor();
    return { p, h };
  }

  test(`${engine.name}: the Voice card shows the last command, the live level and the wake word's score`, async (t) => {
    const { p, h } = await open(t);
    await eventually('nothing heard yet', async () => (await text(p, 'voice-last-heard')) === 'No command heard since voice was turned on.' || await text(p, 'voice-last-heard'));

    h.voice.last = { at: 1, wake_word: 'okay_nabu', wake_score: 0.98, heard: 'turn on the kitchen light', reply: 'Sorry, I could not find that device', outcome: 'error', code: 'no_intent_match', listen_ms: 2100 };
    await eventually('what Assist heard', async () => (await text(p, 'voice-last-heard')) === 'Assist heard: “turn on the kitchen light” Woke at 0.98.' || await text(p, 'voice-last-heard'));
    assert.equal(await text(p, 'voice-last-reply'), 'Replied: Sorry, I could not find that device');
    assert.equal(await text(p, 'voice-last-result'), 'Result: not understood.');
    h.voice.last = { ...h.voice.last, code: 'stt-no-text-recognized', heard: null, reply: null };
    await eventually('a failure says its code', async () => (await text(p, 'voice-last-result')) === 'Result: failed (stt-no-text-recognized).' || await text(p, 'voice-last-result'));
    assert.equal(await text(p, 'voice-last-heard'), 'Assist heard nothing it could transcribe. Woke at 0.98.');

    await p.click('#voice-level-toggle');
    h.voice.hearing = { rms_dbfs: -41.3, peak_dbfs: -20, floor_dbfs: -62.6, clipped: 0 };
    await eventually('the live level', async () => (await text(p, 'voice-level-text')) === 'Level -41 dBFS, peak -20 dBFS, room -63 dBFS' || await text(p, 'voice-level-text'));
    assert.equal(await p.evaluate(() => document.getElementById('voice-level').value), -41.3);
    assert.equal(await text(p, 'voice-level-toggle'), 'Stop the level');

    await p.click('#voice-wake-test');
    h.voice.test.peak = 0.81; h.voice.test.cutoff = 0.97; h.voice.test.fired = 1;
    await eventually('the wake word against its cutoff', async () => (await text(p, 'voice-wake-test-text')) === 'Say the wake word. Best score 0.81, needs 0.97; heard 1 times; 20 s left.' || await text(p, 'voice-wake-test-text'));
    await p.click('#voice-wake-test');
    await eventually('the test result stays', async () => (await text(p, 'voice-wake-test-text')) === 'Best score 0.81, needed 0.97; heard 1 times.' || await text(p, 'voice-wake-test-text'));
    assert.deepEqual(h.voice.actions, ['level', 'wake_test', 'stop']);
    assert.equal(h.voice.unmarked, 0, 'every tuning request carries the UI marker');
    assert.equal(h.voice.session, null, 'stopping lets the microphone go');
  });

  test(`${engine.name}: the wizard measures the room, suggests gain and sensitivity, and saves them`, async (t) => {
    const { p, h } = await open(t);
    await p.click('#voice-tuning-start');
    await eventually('the quiet step', async () => (await text(p, 'voice-tuning-step')) === 'Step 1 of 4: the room' || await text(p, 'voice-tuning-step'));
    assert.deepEqual(h.voice.actions, ['level', 'wake_test'], 'the quiet step meters the room and listens for a false wake');
    assert.equal(await p.locator('#voice-tuning-start').isHidden(), true);

    await eventually('the speaking step', async () => (await text(p, 'voice-tuning-step')) === 'Step 2 of 4: your voice' || await text(p, 'voice-tuning-step'));
    // Spoken from the usual spot, quietly: the command needs lifting towards -20 dBFS.
    h.voice.hearing = { rms_dbfs: -50, peak_dbfs: -40, floor_dbfs: -63, clipped: 0 };
    await eventually('the wake word step', async () => (await text(p, 'voice-tuning-step')) === 'Step 3 of 4: the wake word' || await text(p, 'voice-tuning-step'), 9000);
    assert.equal(await text(p, 'voice-tuning-instruction'), 'Go to the far side of the room and say “okay nabu” three times, pausing between each.');
    assert.equal(await p.locator('#voice-tuning-next').isDisabled(), true, 'not heard three times yet');

    // From across the room the wake word comes within reach of its cutoff once, and fires once.
    h.voice.test.peak = 0.9; h.voice.test.cutoff = 0.97; h.voice.test.fired = 1;
    await eventually('the wake score', async () => (await text(p, 'voice-tuning-progress')) === 'Heard 1 of 3. Best score 0.90, needs 0.97.' || await text(p, 'voice-tuning-progress'));
    h.voice.lastTest = { ...h.voice.test, state: 'done', remaining_ms: 0 };
    h.voice.test = null; h.voice.session = h.voice.level ? h.voice.session : null;
    await eventually('the test ran out, so the owner may go on', async () => !(await p.locator('#voice-tuning-next').isDisabled()));
    await p.click('#voice-tuning-next');
    await eventually('the optional source step', async () => (await text(p, 'voice-tuning-step')) === 'Step 4 of 4: compare settings (optional)' || await text(p, 'voice-tuning-step'));
    assert.equal(await p.locator('#voice-tuning-source').evaluate((s) => s.tagName), 'SELECT', 'the source is chosen from the shared menu');
    await p.click('#voice-tuning-skip');

    await eventually('the suggestions', async () => (await text(p, 'voice-tuning-step')) === 'Suggested settings' || await text(p, 'voice-tuning-step'));
    const suggested = await text(p, 'voice-tuning-progress');
    assert.match(suggested, /Microphone gain: \+20 dB/);
    assert.match(suggested, /Wake word sensitivity: High/);
    assert.equal(h.writes.length, 0, 'nothing is written before Save');
    await p.click('#voice-tuning-save');
    await eventually('the suggestion is saved', async () => (await text(p, 'voice-tuning-status')) === 'Saved.' || await text(p, 'voice-tuning-status'));
    assert.deepEqual(h.writes.sort(), ['voice_mic_gain_db=20', 'voice_sensitivity=high']);
    assert.equal(h.voice.session, null, 'the wizard lets the microphone go');
    assert.equal(h.voice.unmarked, 0);
  });

  test(`${engine.name}: cancelling the wizard puts back the audio source it tried`, async (t) => {
    const { p, h } = await open(t);
    await p.click('#voice-tuning-start');
    await eventually('the wake word step', async () => (await text(p, 'voice-tuning-step')) === 'Step 3 of 4: the wake word' || await text(p, 'voice-tuning-step'), 12000);
    h.voice.test.peak = 0.99; h.voice.test.cutoff = 0.97; h.voice.test.fired = 3;
    await eventually('heard three times', async () => !(await p.locator('#voice-tuning-next').isDisabled()));
    await p.click('#voice-tuning-next');
    await eventually('the source step', async () => (await text(p, 'voice-tuning-step')) === 'Step 4 of 4: compare settings (optional)' || await text(p, 'voice-tuning-step'));
    await p.selectOption('#voice-tuning-source', 'voice_audio_source=mic');
    await p.click('#voice-tuning-try');
    await eventually('the other source is tried', async () => h.writes.includes('voice_audio_source=mic') && h.voice.test !== null || JSON.stringify([h.writes, h.voice.actions]), 6000);
    await p.click('#voice-tuning-cancel');
    await eventually('cancel restores the source', async () => (await text(p, 'voice-tuning-status')) === 'Cancelled. The settings are as they were.' || await text(p, 'voice-tuning-status'));
    assert.deepEqual(h.writes, ['voice_audio_source=mic', 'voice_audio_source=voice_recognition']);
    assert.equal(h.settings.voice_audio_source, 'voice_recognition');
    assert.equal(h.voice.session, null);
    assert.equal(await p.locator('#voice-tuning-wizard').isHidden(), true);
  });

  test(`${engine.name}: cancelling while a tried setting is still being written waits for it, then puts it back`, async (t) => {
    const { p, h } = await open(t);
    const errors = [];
    p.on('pageerror', (e) => errors.push(e.message));
    await p.click('#voice-tuning-start');
    await eventually('the wake word step', async () => (await text(p, 'voice-tuning-step')) === 'Step 3 of 4: the wake word' || await text(p, 'voice-tuning-step'), 12000);
    h.voice.test.peak = 0.99; h.voice.test.cutoff = 0.97; h.voice.test.fired = 3;
    await eventually('heard three times', async () => !(await p.locator('#voice-tuning-next').isDisabled()));
    await p.click('#voice-tuning-next');
    await eventually('the source step', async () => (await text(p, 'voice-tuning-step')) === 'Step 4 of 4: compare settings (optional)' || await text(p, 'voice-tuning-step'));
    h.held.write = 'voice_audio_source=mic';
    await p.selectOption('#voice-tuning-source', 'voice_audio_source=mic');
    await p.click('#voice-tuning-try');
    await eventually('the tried write is pending', async () => h.held.waiting || JSON.stringify(h.writes), 6000);
    await p.click('#voice-tuning-cancel');
    await new Promise((resolve) => setTimeout(resolve, 300));
    assert.notEqual(await text(p, 'voice-tuning-status'), 'Cancelled. The settings are as they were.', 'Cancel does not report before the pending write is answered');
    h.held.release();
    await eventually('cancel restores the source', async () => (await text(p, 'voice-tuning-status')) === 'Cancelled. The settings are as they were.' || await text(p, 'voice-tuning-status'));
    await new Promise((resolve) => setTimeout(resolve, 800));
    assert.deepEqual(h.writes, ['voice_audio_source=mic', 'voice_audio_source=voice_recognition']);
    assert.equal(h.settings.voice_audio_source, 'voice_recognition', 'the panel keeps the setting the wizard started with');
    assert.equal(h.voice.session, null, 'the trial does not start a test after Cancel');
    assert.deepEqual(errors, []);
  });

  test(`${engine.name}: the comparison tries capture routes one at a time and saves the one that heard best`, async (t) => {
    const { p, h } = await open(t);
    await p.click('#voice-tuning-start');
    await eventually('the wake word step', async () => (await text(p, 'voice-tuning-step')) === 'Step 3 of 4: the wake word' || await text(p, 'voice-tuning-step'), 12000);
    h.voice.test.peak = 0.6; h.voice.test.cutoff = 0.97; h.voice.test.fired = 0;
    h.voice.lastTest = { ...h.voice.test, state: 'done', remaining_ms: 0 };
    h.voice.test = null;
    await eventually('the test ran out', async () => !(await p.locator('#voice-tuning-next').isDisabled()));
    await p.click('#voice-tuning-next');
    await eventually('the source step', async () => (await text(p, 'voice-tuning-step')) === 'Step 4 of 4: compare settings (optional)' || await text(p, 'voice-tuning-step'));
    const groups = await p.locator('#voice-tuning-source optgroup').evaluateAll((g) => g.map((x) => x.label));
    assert.deepEqual(groups, ['Audio source', 'Capture rate', 'Capture channels'], 'the capture route settings are offered beside the source');

    // Two channels first: it hears the wake word twice.
    await p.selectOption('#voice-tuning-source', 'voice_capture_channels=2');
    await p.click('#voice-tuning-try');
    await eventually('two channels are tried', async () => h.writes.includes('voice_capture_channels=2') && h.voice.test !== null || JSON.stringify(h.writes), 6000);
    h.voice.test.peak = 0.98; h.voice.test.cutoff = 0.97; h.voice.test.fired = 2;
    await eventually('the score is read', async () => (await text(p, 'voice-tuning-progress')) === 'Heard 2 of 3. Best score 0.98, needs 0.97.' || await text(p, 'voice-tuning-progress'));

    // Then 48 kHz on its own: channels go back to where the wizard started, and it hears nothing.
    await p.selectOption('#voice-tuning-source', 'voice_capture_rate=48000');
    await p.click('#voice-tuning-try');
    await eventually('48 kHz is tried alone', async () => h.writes.includes('voice_capture_rate=48000') && h.voice.test !== null || JSON.stringify(h.writes), 6000);
    assert.deepEqual(h.writes, ['voice_capture_channels=2', 'voice_capture_channels=auto', 'voice_capture_rate=48000']);
    assert.match(await text(p, 'voice-tuning-source-results'), /^Capture channels: Two: heard 2 of 3, best score 0\.98$/);
    h.voice.test.peak = 0.4; h.voice.test.cutoff = 0.97; h.voice.test.fired = 0;
    await eventually('the second score is read', async () => (await text(p, 'voice-tuning-progress')) === 'Heard 0 of 3. Best score 0.40, needs 0.97.' || await text(p, 'voice-tuning-progress'));

    await p.click('#voice-tuning-skip');
    await eventually('the suggestions', async () => (await text(p, 'voice-tuning-step')) === 'Suggested settings' || await text(p, 'voice-tuning-step'));
    assert.match(await text(p, 'voice-tuning-progress'), /Heard best with Capture channels: Two/);
    h.writes.length = 0;
    await p.click('#voice-tuning-save');
    await eventually('the best route is saved', async () => (await text(p, 'voice-tuning-status')) === 'Saved.' || await text(p, 'voice-tuning-status'));
    assert.deepEqual(h.writes.filter((w) => w.startsWith('voice_capture') || w.startsWith('voice_audio')), ['voice_capture_rate=auto', 'voice_capture_channels=2']);
    assert.equal(h.settings.voice_capture_rate, 'auto');
    assert.equal(h.settings.voice_capture_channels, '2');
    assert.equal(h.settings.voice_audio_source, 'voice_recognition');
  });

  test(`${engine.name}: a setting the panel could not open, or heard beside a refused put-back, is never compared`, async (t) => {
    const { p, h } = await open(t);
    await p.click('#voice-tuning-start');
    await eventually('the wake word step', async () => (await text(p, 'voice-tuning-step')) === 'Step 3 of 4: the wake word' || await text(p, 'voice-tuning-step'), 12000);
    h.voice.test.peak = 0.6; h.voice.test.cutoff = 0.97; h.voice.test.fired = 0;
    h.voice.lastTest = { ...h.voice.test, state: 'done', remaining_ms: 0 };
    h.voice.test = null;
    await eventually('the test ran out', async () => !(await p.locator('#voice-tuning-next').isDisabled()));
    await p.click('#voice-tuning-next');
    await eventually('the source step', async () => (await text(p, 'voice-tuning-step')) === 'Step 4 of 4: compare settings (optional)' || await text(p, 'voice-tuning-step'));

    // 48 kHz is accepted as a setting, but the panel cannot open it and listens on its standard route instead,
    // where the wake word happens to fire three times.
    h.unopened.add('voice_capture_rate=48000');
    await p.selectOption('#voice-tuning-source', 'voice_capture_rate=48000');
    await p.click('#voice-tuning-try');
    await eventually('48 kHz is tried', async () => h.writes.includes('voice_capture_rate=48000') && h.voice.test !== null || JSON.stringify(h.writes), 6000);
    h.voice.test.peak = 0.99; h.voice.test.cutoff = 0.97; h.voice.test.fired = 3;
    await eventually('the score is read', async () => (await text(p, 'voice-tuning-progress')) === 'Heard 3 of 3. Best score 0.99, needs 0.97.' || await text(p, 'voice-tuning-progress'));

    // Two channels next: the rate goes back first, and two channels open as asked and hear twice.
    await p.selectOption('#voice-tuning-source', 'voice_capture_channels=2');
    await p.click('#voice-tuning-try');
    await eventually('two channels are tried', async () => h.writes.includes('voice_capture_channels=2') && h.voice.test !== null || JSON.stringify(h.writes), 6000);
    assert.equal(await text(p, 'voice-tuning-source-results'), 'Capture rate: 48 kHz: the panel could not use this setting, so it was not compared');
    h.voice.test.peak = 0.98; h.voice.test.cutoff = 0.97; h.voice.test.fired = 2;
    await eventually('the second score is read', async () => (await text(p, 'voice-tuning-progress')) === 'Heard 2 of 3. Best score 0.98, needs 0.97.' || await text(p, 'voice-tuning-progress'));

    // Then the other audio source, but the panel refuses to put the channels back, so what it hears next is
    // not the source on its own, however well it hears.
    h.refuse.add('voice_capture_channels=auto');
    await p.selectOption('#voice-tuning-source', 'voice_audio_source=mic');
    await p.click('#voice-tuning-try');
    await eventually('the other source is tried', async () => h.writes.includes('voice_audio_source=mic') && h.voice.test !== null || JSON.stringify(h.writes), 6000);
    h.voice.test.peak = 0.99; h.voice.test.cutoff = 0.97; h.voice.test.fired = 3;
    await eventually('the third score is read', async () => (await text(p, 'voice-tuning-progress')) === 'Heard 3 of 3. Best score 0.99, needs 0.97.' || await text(p, 'voice-tuning-progress'));
    h.refuse.clear();

    await p.click('#voice-tuning-skip');
    await eventually('the suggestions', async () => (await text(p, 'voice-tuning-step')) === 'Suggested settings' || await text(p, 'voice-tuning-step'));
    assert.match(await text(p, 'voice-tuning-source-results'), /Audio source: Microphone: the panel could not use this setting, so it was not compared/);
    assert.match(await text(p, 'voice-tuning-progress'), /Heard best with Capture channels: Two/, 'only a setting heard as asked is suggested');

    await p.click('#voice-tuning-cancel');
    await eventually('cancel restores them', async () => (await text(p, 'voice-tuning-status')) === 'Cancelled. The settings are as they were.' || await text(p, 'voice-tuning-status'));
    assert.equal(h.settings.voice_capture_rate, 'auto');
    assert.equal(h.settings.voice_capture_channels, 'auto', 'the refused put-back is written again');
    assert.equal(h.settings.voice_audio_source, 'voice_recognition');
  });
}
