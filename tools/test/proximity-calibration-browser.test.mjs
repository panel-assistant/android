import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import test from 'node:test';
import { chromium } from 'playwright-core';

const asset = await readFile(new URL('../../app/src/main/assets/proximity-learning.js', import.meta.url), 'utf8');
const stylesheet = await readFile(process.argv[2] || new URL('../../app/src/main/assets/info.css', import.meta.url), 'utf8');
const chrome = process.env.CHROME || '/usr/bin/chromium';
const browserTest = existsSync(chrome) ? test : test.skip;

async function fixture(t, initial = {}) {
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  t.after(() => browser.close());
  const page = await browser.newPage();
  const posts = [];
  let refusal = null;
  let status = { present: true, phase: 'ready', signalMode: 'binary', profileDefaultAvailable: false, ...initial };
  await page.clock.install();
  await page.route('http://panel.test/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === '/') return route.fulfill({ contentType: 'text/html', body: '<div id="cfg-groups"></div><div id="proximity-learning-mount"></div>' });
    if (path === '/api/v1/proximity/calibration') {
      const body = Object.fromEntries(new URLSearchParams(request.postData()));
      posts.push({ body, headers: request.headers() });
      if (refusal) return route.fulfill(refusal);
      if (body.action === 'start') status = { ...status, phase: 'calibrating', stage: 'intro', sessionActive: true, sessionId: 'starter-session' };
      if (body.action === 'cancel') status = { ...status, phase: 'ready', stage: 'cancelled', sessionActive: false, sessionId: null };
    }
    return route.fulfill({ json: status });
  });
  await page.goto('http://panel.test/');
  await page.addStyleTag({ content: stylesheet });
  await page.addScriptTag({ content: asset });
  const expectedStatus = initial.present === false || initial.phase === 'source_unavailable' ? 'Proximity source is unavailable'
    : initial.phase === 'calibrating' ? 'Setup is running on the panel' : 'Proximity is ready';
  await page.getByText(expectedStatus, { exact: true }).waitFor();
  return { page, posts, setStatus: (value) => { status = value; }, refuse: (value) => { refusal = value; } };
}

browserTest('browser launches on-panel setup, heartbeats only its own session, and cancels with session binding', async (t) => {
  const { page, posts } = await fixture(t);
  assert.equal(await page.getByRole('button', { name: 'Cancel setup', includeHidden: true }).isVisible(), false);
  assert.equal(await page.getByRole('button', { name: 'Set up proximity on panel', exact: true }).isVisible(), true);
  assert.match(await page.locator('.prox-learning .note').first().textContent(), /detection distance cannot be adjusted/);
  assert.equal(await page.getByRole('button', { name: /Teach|Test a wave|Forget/ }).count(), 0);
  await page.getByRole('button', { name: 'Set up proximity on panel', exact: true }).click();
  await page.getByText('Ready to begin on the panel', { exact: true }).waitFor();
  assert.equal(await page.getByRole('button', { name: 'Set up proximity on panel', exact: true, includeHidden: true }).isVisible(), false);
  assert.equal(await page.getByRole('button', { name: 'Cancel setup' }).isVisible(), true);
  assert.match(await page.locator('.prox-learning .note').first().textContent(), /Follow the instructions on the panel/);
  assert.equal(await page.getByRole('button', { name: 'Save', exact: true }).count(), 0);
  assert.equal(await page.getByRole('button', { name: 'Restore profile defaults' }).isDisabled(), true);
  assert.equal(posts[0].body.action, 'start');
  assert.equal(posts[0].headers['x-proximity-ui'], '1');
  const heartbeat = page.waitForResponse((response) => response.request().postData()?.includes('action=heartbeat'));
  await page.clock.runFor(5100);
  await heartbeat;
  assert.ok(posts.some(({ body }) => body.action === 'heartbeat' && body.sessionId === 'starter-session'));
  await page.getByRole('button', { name: 'Cancel setup' }).click();
  await page.getByText('Setup cancelled. Existing calibration is unchanged.', { exact: true }).waitFor();
  assert.equal(await page.getByRole('button', { name: 'Cancel setup', includeHidden: true }).isVisible(), false);
  assert.equal(await page.getByRole('button', { name: 'Set up proximity on panel', exact: true }).isVisible(), true);
  assert.ok(posts.some(({ body }) => body.action === 'cancel' && body.sessionId === 'starter-session'));
  const count = posts.length;
  await page.clock.runFor(10000);
  assert.equal(posts.length, count, 'finished session must not keep sending heartbeats');
});

browserTest('a monitoring browser never adopts heartbeat ownership and shows progress without physical instructions', async (t) => {
  const { page, posts } = await fixture(t, { phase: 'calibrating', sessionActive: true, sessionId: 'another-browser', stage: 'waves', acceptedGestures: 2, requiredGestures: 3 });
  assert.equal(await page.locator('.prox-learning-evidence').textContent(), 'Validating waves: 2/3');
  await page.clock.runFor(16000);
  assert.equal(posts.length, 0);
  assert.equal(await page.getByRole('button', { name: 'Set up proximity on panel', includeHidden: true }).isVisible(), false);
});

browserTest('profile reset requires confirmation and does not restart calibration', async (t) => {
  const { page, posts } = await fixture(t, { profileDefaultAvailable: true });
  page.once('dialog', (dialog) => dialog.dismiss());
  await page.getByRole('button', { name: 'Restore profile defaults' }).click();
  assert.equal(posts.length, 0);
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Restore profile defaults' }).click();
  await page.waitForFunction(() => document.querySelector('.prox-learning-actions button:last-child').disabled === false);
  assert.deepEqual(posts.map(({ body }) => body), [{ action: 'reset' }]);
});

browserTest('profile reset stays disabled when no profile default exists', async (t) => {
  const { page, posts } = await fixture(t);
  const reset = page.getByRole('button', { name: 'Restore profile defaults' });
  assert.equal(await reset.isDisabled(), true);
  await reset.evaluate((button) => button.click());
  assert.equal(posts.length, 0);
});

browserTest('starter heartbeat pauses after failure and resumes for an on-panel retry with the same session', async (t) => {
  const { page, posts, setStatus } = await fixture(t);
  await page.getByRole('button', { name: 'Set up proximity on panel' }).click();
  await page.getByText('Ready to begin on the panel', { exact: true }).waitFor();
  setStatus({ present: true, phase: 'ready', stage: 'failed', sessionActive: false, sessionId: 'starter-session' });
  await page.clock.runFor(1100);
  await page.getByText('Setup could not complete. Existing calibration is unchanged.', { exact: true }).waitFor();
  const count = posts.length;
  await page.clock.runFor(10000);
  assert.equal(posts.length, count);
  setStatus({ present: true, phase: 'calibrating', stage: 'intro', sessionActive: true, sessionId: 'starter-session' });
  await page.clock.runFor(5100);
  await page.getByText('Ready to begin on the panel', { exact: true }).waitFor();
  const heartbeat = page.waitForResponse((response) => response.request().postData()?.includes('action=heartbeat'));
  await page.clock.runFor(5100);
  await heartbeat;
  assert.ok(posts.some(({ body }) => body.action === 'heartbeat' && body.sessionId === 'starter-session'));
});

browserTest('a present source without an initial reading can explicitly launch setup on the panel', async (t) => {
  const { page, posts, setStatus } = await fixture(t, { present: true, phase: 'source_unavailable', health: 'source_unavailable', raw: null, canCalibrate: true });
  const start = page.getByRole('button', { name: 'Set up proximity on panel', exact: true });
  assert.equal(await start.isEnabled(), true, 'an on-change source needs on-panel instructions before its first physical transition');
  assert.equal(posts.length, 0, 'source admission must never start setup automatically');
  assert.equal(await page.getByRole('button', { name: 'Restore profile defaults' }).isDisabled(), true);
  await start.click();
  await page.getByText('Waiting for sensor status…', { exact: true }).waitFor();
  assert.equal(await page.getByText('Ready to begin on the panel', { exact: true }).count(), 0);
  assert.deepEqual(posts.map(({ body }) => body), [{ action: 'start' }]);
  assert.equal(posts[0].headers['x-proximity-ui'], '1');
  setStatus({ present: true, phase: 'calibrating', stage: 'intro', sessionActive: true, sessionId: 'starter-session', health: 'healthy', raw: 0, canCalibrate: true });
  await page.clock.runFor(1100);
  await page.getByText('Ready to begin on the panel', { exact: true }).waitFor();
});

browserTest('missing or explicitly non-calibratable sources cannot launch setup', async (t) => {
  for (const status of [
    { present: false, phase: 'source_unavailable', canCalibrate: true },
    { present: true, phase: 'source_unavailable', canCalibrate: false },
  ]) {
    const { page, posts } = await fixture(t, status);
    assert.equal(await page.getByRole('button', { name: 'Set up proximity on panel', exact: true }).isDisabled(), true);
    assert.equal(posts.length, 0);
  }
});

browserTest('ready status reports presence and wave capabilities independently', async (t) => {
  for (const [presenceSupported, waveSupported, expected] of [
    [true, true, 'Presence and wave are ready'],
    [true, false, 'Presence is ready; wave is unavailable'],
    [false, true, 'Wave is ready; presence is unavailable'],
    [false, false, 'Presence and wave are unavailable'],
  ]) {
    const { page, setStatus, posts } = await fixture(t);
    assert.equal(await page.locator('.prox-learning-state').textContent(), 'Proximity is ready', 'older status without capability fields retains its fallback');
    setStatus({ present: true, phase: 'ready', presenceSupported, waveSupported });
    await page.clock.runFor(5100);
    await page.getByText(expected, { exact: true }).waitFor();
    assert.equal(posts.length, 0, 'rendering capabilities must not change settings');
  }
});

browserTest('new hand-wave stages report progress while physical instructions remain on the panel', async (t) => {
  const session = { present: true, phase: 'calibrating', sessionActive: true, sessionId: 'other-browser' };
  const { page, setStatus, posts } = await fixture(t, { ...session, stage: 'wave_baseline' });
  assert.equal(await page.locator('.prox-learning-evidence').textContent(), 'Checking the starting position for hand waves');
  setStatus({ ...session, stage: 'wave_capture' });
  await page.clock.runFor(1100);
  await page.getByText('Measuring a deliberate hand wave', { exact: true }).waitFor();
  assert.match(await page.locator('.prox-learning .note').first().textContent(), /Follow the instructions on the panel/);
  assert.equal(posts.length, 0);
});

for (const [kind, refusal] of [
  ['JSON', { status: 403, json: { error: "Start proximity setup from this panel's HTML UI." } }],
  ['plain text', { status: 403, contentType: 'text/plain', body: 'cross-origin refused\n' }],
]) {
  browserTest(`a ${kind} refusal shows the panel's own reason`, async (t) => {
    const { page, refuse } = await fixture(t);
    refuse(refusal);
    await page.getByRole('button', { name: 'Set up proximity on panel', exact: true }).click();
    const expected = refusal.json ? refusal.json.error : 'cross-origin refused';
    await page.getByText(expected, { exact: true }).waitFor({ timeout: 3000 });
  });
}
