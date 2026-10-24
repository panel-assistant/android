// A choice of one value from several is the shared pop-up menu on every page, never a list of radio
// buttons: radio lists kept returning because a native menu cannot wrap a long label and nothing failed.
// Render each page in both engines, drive its states, and record every radio input that ever appears.
// The Configure page's Basic and Advanced view switch is the one named exception.
import assert from 'node:assert/strict';
import test from 'node:test';
import { BROWSERS, VIEWS, launch, loadCatalogues, measureCell, startLayoutServer } from './language-layout/harness.mjs';
import { PAGES } from './language-layout/pages/index.mjs';

const VIEW_SWITCH = new Set(['tier-basic', 'tier-adv']);
const { catalogues, locales } = await loadCatalogues();
const server = await startLayoutServer(PAGES, catalogues, locales);
const origin = `http://127.0.0.1:${server.address().port}`;
const view = VIEWS.find((candidate) => candidate.name === 'direct-desktop');
test.after(() => new Promise((done) => server.close(done)));

function recordRadios() {
  window.__radios = new Set();
  const note = (node) => {
    if (node.nodeType !== 1) return;
    for (const input of [node, ...node.querySelectorAll('input')]) {
      if (input.matches('input[type=radio]')) window.__radios.add(input.id || `name=${input.name}`);
    }
  };
  new MutationObserver((records) => records.forEach((record) => {
    record.addedNodes.forEach(note);
    if (record.type === 'attributes') note(record.target);
  })).observe(document, { subtree: true, childList: true, attributes: true, attributeFilter: ['type'] });
}

for (const engine of BROWSERS) {
  test(`${engine}: one-of-several choices on every page are menus, not radio buttons`, { timeout: 180_000 }, async () => {
    const browser = await launch(engine);
    try {
      for (const pageDef of PAGES) {
        const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
        await page.addInitScript(recordRadios);
        await measureCell(page, origin, pageDef, view, 'light', 'en');
        const radios = await page.evaluate(() => [...window.__radios, ...[...document.querySelectorAll('input[type=radio]')].map((input) => input.id || `name=${input.name}`)]);
        assert.deepEqual([...new Set(radios)].filter((id) => !VIEW_SWITCH.has(id)), [], `${pageDef.name}: a value choice rendered as radio buttons; use the shared menu`);
        await page.close();
      }
    } finally { await browser.close(); }
  });
}
