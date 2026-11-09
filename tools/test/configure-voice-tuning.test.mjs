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
  { key: 'voice_mic_gain_db', type: 'INT', group: 'Voice', tier: 'ADVANCED', available: true, label: 'Microphone gain', min: -24, max: 24 },
];

/**
 * A panel's voice diagnostics as the coordinator keeps them: one session owns the meter and the wake-word test;
 * the test drives what the microphone "hears" through [voice].
 */
async function panel() {
  const settings = { voice_enabled: 'true', voice_audio_source: 'voice_recognition', voice_sensitivity: 'normal', voice_mic_gain_db: '0', dashboard_package: 'builtin' };
  const voice = {
    session: null, level: false, test: null, lastTest: { state: 'idle', model: null, peak: null, cutoff: null, fired: 0, remaining_ms: 0 },
    hearing: { rms_dbfs: -60, peak_dbfs: -52, floor_dbfs: -63, clipped: 0 },
    last: null, actions: [], unmarked: 0,
  };
  const writes = [];
  const json = () => JSON.stringify({
    session: voice.session,
    level: voice.level ? voice.hearing : null,
    wake_test: voice.test ? { ...voice.test, state: 'running', remaining_ms: 20000 } : voice.lastTest,
    last_command: voice.last,
    route: null,
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
      return send(json(), 'application/json', 202);
    }
    if (path === '/api/v1/config/schema') return send(JSON.stringify(SCHEMA));
    if (path === '/api/v1/config' && request.method === 'POST') {
      const form = await body();
      for (const [key, value] of form) { writes.push(`${key}=${value}`); settings[key] = value; }
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
  return { voice, writes, settings, server, url: `http://127.0.0.1:${server.address().port}` };
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
    await eventually('the optional source step', async () => (await text(p, 'voice-tuning-step')) === 'Step 4 of 4: audio source (optional)' || await text(p, 'voice-tuning-step'));
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
    await eventually('the source step', async () => (await text(p, 'voice-tuning-step')) === 'Step 4 of 4: audio source (optional)' || await text(p, 'voice-tuning-step'));
    await p.selectOption('#voice-tuning-source', 'mic');
    await p.click('#voice-tuning-try');
    await eventually('the other source is tried', async () => h.writes.includes('voice_audio_source=mic') && h.voice.test !== null || JSON.stringify([h.writes, h.voice.actions]), 6000);
    await p.click('#voice-tuning-cancel');
    await eventually('cancel restores the source', async () => (await text(p, 'voice-tuning-status')) === 'Cancelled. The settings are as they were.' || await text(p, 'voice-tuning-status'));
    assert.deepEqual(h.writes, ['voice_audio_source=mic', 'voice_audio_source=voice_recognition']);
    assert.equal(h.settings.voice_audio_source, 'voice_recognition');
    assert.equal(h.voice.session, null);
    assert.equal(await p.locator('#voice-tuning-wizard').isHidden(), true);
  });
}
