// Render sensor wire values through the complete shipped Dashboard and its HTTP transport.
import { chromium } from 'playwright-core';
import { chrome, json, startHarness } from './browser-harness.mjs';
import { screenshotFixture, screenshotRoutes } from './dashboard-screenshot.mjs';

export async function sensorCard(t) {
  let payload = {};
  const fallback = screenshotRoutes();
  const harness = await startHarness((path, request) => path === '/api/v1/sensors' ? json(payload) : fallback(path, request), screenshotFixture);
  let browser;
  t.after(async () => {
    await browser?.close();
    harness.server.closeAllConnections?.();
    await new Promise(resolve => harness.server.close(resolve));
  });
  browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  return async value => {
    payload = value;
    await page.goto(harness.url, { waitUntil: 'load', timeout: 5000 });
    await page.waitForFunction(() => document.getElementById('sensage').textContent === '· live', null, { timeout: 5000 });
    return page.locator('#senstbl tr').evaluateAll(rows => rows.map(row => ({
      label: row.children[0].textContent,
      val: row.children[1].children[0].textContent,
      suf: row.children[1].children[1].textContent,
    })));
  };
}
