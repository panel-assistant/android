import assert from 'node:assert/strict';
import test from 'node:test';
import { BROWSERS, launch, loadCatalogues, startLayoutServer } from './language-layout/harness.mjs';
import configure from './language-layout/pages/configure.mjs';
import dashboard from './language-layout/pages/dashboard.mjs';
import entities from './language-layout/pages/entities.mjs';
import logs from './language-layout/pages/logs.mjs';

const checks = [
  { page: dashboard, locale: 'nl', selector: '#noisyentities th.rate', container: 'th' },
  { page: dashboard, locale: 'cs', selector: '#noisyentities th.payload', container: 'th' },
  { page: entities, locale: 'uk', selector: '[data-table="current"] .col-override button[data-sort]', container: 'th' },
  { page: entities, locale: 'de', selector: '.entity-issue-head .pill', container: '.entity-issues', box: true },
  { page: logs, locale: 'en', selector: '.note code', container: '.note' },
  { page: configure, locale: 'de', selector: '#cfg-voice_pipelines .flabel-tail', container: '.frow', box: true, width: 320, fontSize: 24 },
];

for (const engine of BROWSERS) {
  test(`${engine}: existing app pages fit enlarged browser text on a phone`, async (t) => {
    const browser = await launch(engine);
    const { catalogues, locales } = await loadCatalogues();
    const server = await startLayoutServer([configure, dashboard, entities, logs], catalogues, locales);
    t.after(async () => { await browser.close(); await new Promise((resolve) => server.close(resolve)); });
    const page = await browser.newPage({ viewport: { width: 390, height: 840 } });
    page.setDefaultTimeout(5000);
    const failures = [];
    for (const check of checks) {
      await page.setViewportSize({ width: check.width ?? 390, height: 840 });
      const response = await page.goto(`http://127.0.0.1:${server.address().port}${check.page.path}?lang=${check.locale}`);
      assert.equal(response.status(), 200, await response.text());
      await page.addStyleTag({ content: `html{font-size:${check.fontSize ?? 20}px}` });
      if (check.page === configure) await configure.ready(page.mainFrame());
      const subject = page.locator(check.selector).first();
      await subject.scrollIntoViewIfNeeded();
      const overflow = await subject.evaluate((node, { container, box }) => {
        const outer = node.closest(container).getBoundingClientRect();
        const range = document.createRange();
        range.selectNodeContents(node);
        const inside = box ? node.getBoundingClientRect() : range.getBoundingClientRect();
        return Math.ceil(Math.max(outer.left - inside.left, inside.right - outer.right));
      }, { container: check.container, box: check.box });
      if (overflow > 1) failures.push(`${check.page.name} (${check.locale}) ${check.selector} overflows by ${overflow}px`);
    }
    assert.deepEqual(failures, []);
  });
}
