// Profiles toolbar actions: the order a reader sees (row by row, left to right) is the order the keyboard
// visits them (DOM order, no positive tabindex), in every catalogue locale at the narrow views where the
// action row becomes a grid, in Chromium and WebKit.
import assert from 'node:assert/strict';
import test from 'node:test';
import { launch, loadCatalogues, startLayoutServer, VIEWS } from './language-layout/harness.mjs';
import { PAGES } from './language-layout/pages/index.mjs';

const { catalogues, locales } = await loadCatalogues();
const server = await startLayoutServer(PAGES, catalogues, locales);
const origin = `http://127.0.0.1:${server.address().port}`;
const profiles = PAGES.find((page) => page.name === 'profiles');
const views = VIEWS.filter((view) => ['sidebar-phone', 'panel-square'].includes(view.name));
test.after(() => new Promise((done) => server.close(done)));

for (const browserName of ['chromium', 'webkit']) {
  test(`profiles actions read in keyboard order · ${browserName}`, { timeout: 600_000 }, async () => {
    const browser = await launch(browserName);
    try {
      const mismatches = [];
      for (const view of views) {
        const context = await browser.newContext({ viewport: { width: view.width, height: view.height } });
        const page = await context.newPage();
        for (const locale of locales) {
          const src = `${profiles.path}?lang=${locale}&theme=light${view.host ? '&embed=1' : ''}`;
          let frame;
          if (view.host) {
            await page.goto(`${origin}/__host?view=${view.name}&theme=light&src=${encodeURIComponent(src)}`);
            frame = await (await page.waitForSelector('iframe[name="panel"]')).contentFrame();
          } else {
            await page.goto(origin + src);
            frame = page.mainFrame();
          }
          await frame.waitForSelector('#profile-validate');
          const result = await frame.evaluate(() => {
            const nodes = [...document.querySelectorAll('.profile-actions button, .profile-actions label')]
              .filter((node) => !node.closest('[hidden]') && node.getBoundingClientRect().width > 0);
            const id = (node) => node.id || node.textContent.trim();
            const dom = nodes.map(id);
            const visual = nodes.map((node) => ({ node, box: node.getBoundingClientRect() }))
              .sort((a, b) => (Math.abs(a.box.top - b.box.top) > a.box.height / 2 ? a.box.top - b.box.top : a.box.left - b.box.left))
              .map(({ node }) => id(node));
            const tabindex = nodes.filter((node) => node.tabIndex > 0).map(id);
            return { dom, visual, tabindex };
          });
          if (result.dom.join() !== result.visual.join() || result.tabindex.length) {
            mismatches.push(`${view.name}/${locale}: DOM ${result.dom.join(' > ')} | visual ${result.visual.join(' > ')}${result.tabindex.length ? ` | tabindex ${result.tabindex.join()}` : ''}`);
          }
        }
        await context.close();
      }
      assert.deepEqual(mismatches, []);
    } finally {
      await browser.close();
    }
  });
}
