// Language layout gate: every page × catalogue locale × theme × view, in Chromium and WebKit.
// Run with `node --test language-layout-gate.mjs`; narrow it with LAYOUT_PAGES, LAYOUT_BROWSERS,
// LAYOUT_VIEWS, LAYOUT_THEMES and LAYOUT_LOCALES (comma lists). Each page × browser is one test, so a CI
// shard is one LAYOUT_PAGES × LAYOUT_BROWSERS pair. See README.md.
import test from 'node:test';
import { loadCatalogues, runPage, selection, startLayoutServer } from './language-layout/harness.mjs';
import { PAGES } from './language-layout/pages/index.mjs';

const { catalogues, locales } = await loadCatalogues();
const sel = selection(PAGES, locales);
const server = await startLayoutServer(PAGES, catalogues, locales);
const origin = `http://127.0.0.1:${server.address().port}`;
test.after(() => new Promise((done) => server.close(done)));

const REPORT_LIMIT = Number(process.env.LAYOUT_REPORT_LIMIT || 40);

for (const pageName of sel.pages) {
  const pageDef = PAGES.find((page) => page.name === pageName);
  for (const browserName of sel.browsers) {
    test(`${pageName} · ${browserName}: every locale, theme and view lays out without errors`, { timeout: 1_800_000 }, async () => {
      const outcome = await runPage({ pageDef, browserName, origin, sel });
      console.log(`language-layout ${JSON.stringify({ page: outcome.page, browser: outcome.browser, cells: outcome.cells, failures: outcome.failures.length, reports: outcome.reports.length, ms: outcome.ms })}`);
      if (outcome.reports.length) {
        console.log(`reported (non-blocking), first ${Math.min(REPORT_LIMIT, outcome.reports.length)} of ${outcome.reports.length}:\n  ${outcome.reports.slice(0, REPORT_LIMIT).join('\n  ')}`);
      }
      if (outcome.failures.length) {
        throw new Error(`${outcome.failures.length} layout error(s) in ${pageName} · ${browserName}:\n  ${outcome.failures.slice(0, 200).join('\n  ')}`);
      }
    });
  }
}
