import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import test from 'node:test';
import { webkit } from 'playwright-core';
import { chrome, json } from './fixtures/browser-harness.mjs';
import { configureVisualFixture, startHarness } from './fixtures/configure-page.mjs';
import { BROWSERS, launch } from './language-layout/harness.mjs';

const schema = ['Behaviour', 'Dashboard', 'Display'].map((group, index) => ({
  key: `text_${index}`, label: 'Panel name', group, type: 'STRING', tier: 'BASIC', available: true, ha: true,
}));

for (const engine of BROWSERS) {
  const available = existsSync(engine === 'chromium' ? chrome : webkit.executablePath());
  test(`${engine}: Configure text fields and their HA controls fit at 1280px`, { skip: !available }, async (t) => {
    const browser = await launch(engine);
    const harness = await startHarness((path) => {
      if (path === '/api/v1/config/schema') return json(schema);
      if (path === '/api/v1/config') return json({ settings: { text_0: 'Hallway panel' }, ha_expose: {} });
    }, () => configureVisualFixture().replace('<body>', '<body><div class="wrap">').replace('</body>', '</div></body>'));
    t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
    const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
    await page.goto(harness.url);
    const field = page.locator('#cfg-text_0 input[type=text]');
    await field.waitFor({ timeout: 5000 });
    const bounds = await field.evaluate(async (input) => {
      await document.fonts.ready;
      await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      const card = input.closest('.card').getBoundingClientRect();
      const peer = [...input.parentElement.children].find((node) => node !== input).getBoundingClientRect();
      return { card: card.right, input: input.getBoundingClientRect().right, peer: peer.right };
    });
    assert.ok(bounds.input <= bounds.card + 1 && bounds.peer <= bounds.card + 1,
      `name and its HA exposure control should stay inside the card: ${JSON.stringify(bounds)}`);
  });
}
