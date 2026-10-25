// Negative controls for the language layout gate: every check must be able to fail. A German Configure
// cell is loaded in each browser, broken on purpose, and the verdict must name each error family;
// on the panel's own screen overflow, overlap and wrapping must only be reported.
import assert from 'node:assert/strict';
import test from 'node:test';
import { BROWSERS, THEMES, VIEWS, launch, loadCatalogues, measureCell, runPage, selection, startLayoutServer, verdict } from './language-layout/harness.mjs';
import { PAGES } from './language-layout/pages/index.mjs';
import { journey } from './language-layout/pages/setup.mjs';
import { measureLayout } from './language-layout/measure.mjs';

const { catalogues, locales, scales } = await loadCatalogues();
const configure = PAGES.find((page) => page.name === 'configure');
const profiles = PAGES.find((page) => page.name === 'profiles');
const entities = PAGES.find((page) => page.name === 'entities');
const server = await startLayoutServer(PAGES, catalogues, locales);
const origin = `http://127.0.0.1:${server.address().port}`;
test.after(() => new Promise((done) => server.close(done)));

test('layout measurement ignores empty space between heading fragments', async () => {
  const browser = await launch('chromium');
  try {
    const page = await browser.newPage();
    await page.setContent(`<!doctype html><html lang="en"><style>
      .card{width:400px;height:300px;columns:2;column-gap:20px;column-fill:auto}
      h2{margin:0;height:50px;font-size:16px}.fill{height:280px}button{height:30px}
      </style><div class="card"><div class="fill"></div><h2 id="heading">Heading</h2><button id="action">Action</button></div>`);
    assert.equal(await page.locator('#heading').evaluate((heading) => heading.getClientRects().length), 2, 'the heading must actually span two columns');
    const result = await page.evaluate(measureLayout);
    assert.deepEqual(result.overlaps, [], 'the action is below the second heading fragment');
    await page.locator('#action').evaluate((action) => { action.style.transform = 'translateY(-10px)'; });
    const overlapping = await page.evaluate(measureLayout);
    assert.ok(overlapping.overlaps.some((item) => item.key === '#heading <> #action'), 'painted fragments must still detect an action moved onto the heading');
  } finally { await browser.close(); }
});

test('Setup dashboard confirmation keeps both actions inside its card', { timeout: 120_000 }, async () => {
  const setup = PAGES.find((page) => page.name === 'setup');
  const state = journey();
  state.next = 'render_proof';
  state.steps = state.steps.map((step) => ({ ...step, status: step.stage === 'render_proof' ? 'blocked' : 'complete' }));
  const attestation = {
    ...setup,
    api(url, ...args) { return url.pathname === '/api/v1/setup' ? state : setup.api(url, ...args); },
    async ready(frame) { await frame.waitForSelector('#wiz-step a.wiz-secondary'); },
  };
  const fixture = await startLayoutServer([attestation], catalogues, locales);
  try {
    for (const engine of BROWSERS) {
      const browser = await launch(engine);
      try {
        const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
        await measureCell(page, `http://127.0.0.1:${fixture.address().port}`, attestation, VIEWS.find((view) => view.name === 'direct-desktop'), 'light', 'en');
        const bounds = await page.locator('#wiz-step a.wiz-secondary').evaluate((action) => {
          const card = action.closest('.card').getBoundingClientRect();
          const box = action.getBoundingClientRect();
          return { left: box.left - card.left, right: card.right - box.right };
        });
        assert.ok(bounds.left >= 0 && bounds.right >= 0, `${engine}: the complete recovery action must fit inside its card: ${JSON.stringify(bounds)}`);
      } finally { await browser.close(); }
    }
  } finally { await new Promise((done) => fixture.close(done)); }
});

function breakLayout() {
  const card = document.querySelector('#cfg-groups .card');
  // Growth without new text: squeeze the second card's rows so the same words need many more lines.
  const squeezed = document.querySelector('[data-layout-key="configure-ha-connection"]');
  squeezed.querySelectorAll('p,small,label,div').forEach((node) => { node.style.maxWidth = '120px'; });
  const long = 'Überlange Beschriftung, die niemals in eine Zeile passt und die Karte deutlich wachsen lässt '.repeat(3);
  card.querySelector('h2').id = 'mutant-title';
  card.querySelector('h2').textContent = long;                                          // (d) label wraps, card grows
  card.insertAdjacentHTML('beforeend', `
    <div id="mutant-nowrap" style="white-space:nowrap">${'Kein Umbruch '.repeat(40)}</div>
    <p id="mutant-cut" style="width:60px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">Abgeschnittener Text</p>
    <div id="mutant-overlap" style="position:relative;height:40px"><button style="position:absolute;left:0;top:0;width:90px">Eins</button><button style="position:absolute;left:10px;top:5px;width:90px">Zwei</button></div>`);
  document.querySelector('.wrap').insertAdjacentHTML('beforeend', '<div id="mutant-wide" style="width:calc(100vw + 200px);height:4px"></div>');
  document.body.insertAdjacentHTML('beforeend', '<button id="mutant-offscreen" style="position:fixed;top:40px;left:calc(100vw + 40px)">Weg</button>');
}

for (const browserName of BROWSERS) {
  test(`descriptive Entities checkbox labels may wrap but clipping fails · ${browserName}`, { timeout: 120_000 }, async () => {
    const browser = await launch(browserName);
    try {
      const view = VIEWS.find((item) => item.name === 'sidebar-phone');
      const context = await browser.newContext({ viewport: { width: view.width, height: view.height } });
      const page = await context.newPage();
      const english = await measureCell(page, origin, entities, view, 'dark', 'en');
      const wrapped = await measureCell(page, origin, entities, view, 'dark', 'de', () => {
        const label = document.querySelector('.entity-policy label');
        label.id = 'mutant-descriptive-label';
        label.style.maxWidth = '180px';
      });
      const outcome = verdict(wrapped, english, view, 'de', scales.de);
      assert.ok(outcome.reports.some((line) => line.includes('(d descriptive label wraps) #mutant-descriptive-label')), outcome.reports.join('\n'));
      assert.ok(!outcome.errors.some((line) => line.includes('wraps') && line.includes('#mutant-descriptive-label')), outcome.errors.join('\n'));
      const clipped = await measureCell(page, origin, entities, view, 'dark', 'de', () => {
        const label = document.querySelector('.entity-policy label');
        label.id = 'mutant-descriptive-label';
        label.style.cssText = 'max-width:180px;max-height:12px;overflow:hidden';
      });
      assert.ok(verdict(clipped, english, view, 'de', scales.de).errors.some((line) => line.includes('(a cut off)') && line.includes('#mutant-descriptive-label')), 'clipped descriptive text must still fail');
      await context.close();
    } finally {
      await browser.close();
    }
  });
  test(`a text field's label may wrap, but clipping and other wraps on the page still fail · ${browserName}`, { timeout: 120_000 }, async () => {
    const browser = await launch(browserName);
    try {
      const view = VIEWS.find((item) => item.name === 'sidebar-phone');
      const context = await browser.newContext({ viewport: { width: view.width, height: view.height } });
      const page = await context.newPage();
      const english = await measureCell(page, origin, entities, view, 'dark', 'en');
      // Page functions are serialised into the browser, so each mutant is spelled out rather than closed over.
      const wrapLabel = () => {
        const label = document.querySelector('label[for="entity-search"]');
        label.id = 'mutant-field-label';
        label.style.maxWidth = '60px';
        const legend = document.querySelector('.entity-policy legend');
        legend.id = 'mutant-legend';
        legend.style.maxWidth = '60px';
      };
      const clipLabel = () => {
        const label = document.querySelector('label[for="entity-search"]');
        label.id = 'mutant-field-label';
        label.style.cssText = 'display:block;max-width:60px;max-height:12px;overflow:hidden';
      };
      const wrapped = verdict(await measureCell(page, origin, entities, view, 'dark', 'de', wrapLabel), english, view, 'de', scales.de);
      assert.ok(wrapped.reports.some((line) => line.includes('(d descriptive label wraps) #mutant-field-label')), wrapped.reports.join('\n'));
      assert.ok(!wrapped.errors.some((line) => line.includes('#mutant-field-label')), wrapped.errors.join('\n'));
      assert.ok(wrapped.errors.some((line) => line.includes('(d wraps) #mutant-legend')), `another wrap on the page must still fail\n${wrapped.errors.join('\n')}`);
      const clipped = verdict(await measureCell(page, origin, entities, view, 'dark', 'de', clipLabel), english, view, 'de', scales.de);
      assert.ok(clipped.errors.some((line) => line.includes('(a cut off)') && line.includes('#mutant-field-label')), 'a clipped field label must still fail');
      await context.close();
    } finally {
      await browser.close();
    }
  });
  test(`language layout checks can fail · ${browserName}`, { timeout: 120_000 }, async () => {
    const browser = await launch(browserName);
    try {
      for (const view of [VIEWS.find((item) => item.name === 'sidebar-desktop'), VIEWS.find((item) => item.name === 'panel-square')]) {
        const context = await browser.newContext({ viewport: { width: view.width, height: view.height } });
        const page = await context.newPage();
        const english = await measureCell(page, origin, configure, view, 'dark', 'en');
        const clean = await measureCell(page, origin, configure, view, 'dark', 'de');
        const broken = await measureCell(page, origin, configure, view, 'dark', 'de', breakLayout);
        await context.close();
        // The English baseline must not already contain what the mutants break, or its exemption would swallow them.
        assert.ok(!JSON.stringify(english).includes('mutant'), `${view.name}: English baseline must be free of mutants`);
        assert.deepEqual(verdict(clean, english, view, 'de', scales.de).errors.filter((line) => line.includes('mutant')), [], `${view.name}: the unbroken cell reports no mutant`);

        const { errors, reports } = verdict(broken, english, view, 'de', scales.de);
        const found = (list, family, key) => list.some((line) => line.includes(`(${family}`) && (!key || line.includes(key)));
        const blocking = view.tier !== 'panel';
        assert.ok(found(errors, 'a cut off', 'mutant-cut'), `${view.name}: cut-off text must fail\n${errors.join('\n')}`);
        assert.ok(found(errors, 'c unreachable', 'mutant-offscreen'), `${view.name}: an off-screen control must fail`);
        assert.equal(found(blocking ? errors : reports, 'b overflow', 'mutant-nowrap'), true, `${view.name}: nowrap overflow must be ${blocking ? 'an error' : 'reported'}`);
        assert.equal(found(blocking ? errors : reports, 'b overflow', 'page'), true, `${view.name}: sideways page scroll must be ${blocking ? 'an error' : 'reported'}`);
        assert.equal(found(blocking ? errors : reports, 'b overlap'), true, `${view.name}: overlapping controls must be ${blocking ? 'an error' : 'reported'}`);
        assert.equal(found(blocking ? errors : reports, 'd wraps', 'mutant-title'), true, `${view.name}: a wrapping card title must be ${blocking ? 'an error' : 'reported'}`);
        assert.equal(found(blocking ? errors : reports, 'd card growth', 'configure-ha-connection'), true, `${view.name}: card growth beyond the German scale must be ${blocking ? 'an error' : 'reported'}\n${reports.concat(errors).filter((line) => line.includes('growth')).join('\n')}`);
        if (!blocking) {
          assert.ok(!errors.some((line) => /\((b|d) /.test(line)), `${view.name}: panel sizes must not block on overflow or wrapping\n${errors.join('\n')}`);
        }
      }
      // Profiles must run the production CodeMirror editor, never the plain-textarea fallback.
      const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
      const page = await context.newPage();
      await page.route('**/profile-editor/codemirror.js', (route) => route.abort());
      await assert.rejects(measureCell(page, origin, profiles, VIEWS.find((item) => item.name === 'direct-desktop'), 'dark', 'en'), /fell back from CodeMirror/);
      await context.close();
    } finally {
      await browser.close();
    }
  });
}

test('breakpoint views are selected', () => {
  const views = selection(PAGES, locales).views;
  for (const name of ['profiles-519', 'profiles-520', 'profiles-856', 'profiles-857', 'profiles-1049', 'profiles-1050', 'api-320']) assert.ok(views.includes(name), name);
});

test('the CI layout jobs cover exactly the gate pages and browsers', async () => {
  const { readFile } = await import('node:fs/promises');
  const workflow = await readFile(new URL('../../.github/workflows/ui-layout.yml', import.meta.url), 'utf8');
  const groups = [...workflow.matchAll(/\n\s+- \{ browser: (\w+), pages: ([^}]+) \}/g)]
    .map((match) => ({ browser: match[1], pages: match[2].trim().split(/\s+/) }));
  assert.deepEqual([...new Set(groups.map((group) => group.browser))].sort(), [...BROWSERS].sort());
  for (const browser of BROWSERS) {
    const pages = groups.filter((group) => group.browser === browser).flatMap((group) => group.pages).sort();
    assert.deepEqual(pages, PAGES.map((page) => page.name).sort(), `${browser} runs every page exactly once`);
  }
});

test('every CI browser job runs the Playwright image of the pinned playwright-core', async () => {
  const { readFile } = await import('node:fs/promises');
  const workflow = await readFile(new URL('../../.github/workflows/ui-layout.yml', import.meta.url), 'utf8');
  const { devDependencies, dependencies } = JSON.parse(await readFile(new URL('./package.json', import.meta.url), 'utf8'));
  const version = { ...dependencies, ...devDependencies }['playwright-core'];
  const images = [...workflow.matchAll(/\n\s+image: (\S+)/g)].map((match) => match[1]);
  assert.equal(images.length, 2, 'the suite and layout jobs each name the image');
  for (const image of images) assert.match(image, new RegExp(`^mcr\\.microsoft\\.com/playwright:v${version.replaceAll('.', '\\.')}-noble@sha256:[0-9a-f]{64}$`));
});

test('card growth blocks only beyond the locale scale; every card over 20% is reported', () => {
  const view = VIEWS.find((item) => item.name === 'direct-desktop');
  const english = { cut: [], overflow: [], overlaps: [], offscreen: [], wraps: [], cards: { a: { height: 500 }, b: { height: 500 }, c: { height: 500 } }, errors: [], lang: 'en' };
  // de scale about 1.23: +30% is the language's own length, +60% is a corner case, +10% is not reported.
  const german = { ...english, cards: { a: { height: 650 }, b: { height: 800 }, c: { height: 550 } }, lang: 'de' };
  const { errors, reports } = verdict(german, english, view, 'de', 1.227);
  assert.deepEqual(errors.filter((line) => line.includes('card growth')).map((line) => line.split(' ')[3]), ['b']);
  assert.ok(reports.some((line) => line.includes('card growth) a ')), 'growth within the scale is still reported');
  assert.ok(![...errors, ...reports].some((line) => line.includes('card growth) c ')), 'growth under 20% is not reported');
  // A language shorter than English is held to English height, not shrunk expectations.
  const chinese = { ...english, cards: { a: { height: 590 }, b: { height: 700 }, c: { height: 500 } }, lang: 'zh-Hans' };
  const zh = verdict(chinese, english, view, 'zh-Hans', 0.4);
  assert.deepEqual(zh.errors.filter((line) => line.includes('card growth')).map((line) => line.split(' ')[3]), ['b']);
});

test('locale scales are the median translated-to-English length ratio', () => {
  assert.equal(scales.en, 1);
  for (const locale of locales) assert.ok(scales[locale] > 0.2 && scales[locale] < 2, `${locale} scale ${scales[locale]}`);
  console.log(`locale scales ${JSON.stringify(scales)}`);
});

test('a known-issue entry that no longer occurs fails as stale', { timeout: 120_000 }, async () => {
  const known = [{ page: 'configure', view: 'sidebar-desktop', locale: 'de', family: 'a cut off', key: '#no-such-element' }];
  const sel = { views: ['sidebar-desktop'], themes: THEMES, locales: ['en', 'de'] };
  const outcome = await runPage({ pageDef: configure, browserName: 'chromium', origin, sel, scales, known });
  assert.ok(outcome.failures.some((line) => line.includes('stale known-issue entry') && line.includes('#no-such-element')), outcome.failures.join('\n'));
});

test('Consequential field instructions stay visible while entering secrets', { timeout: 120_000 }, async () => {
  const install = PAGES.find(page => page.name === 'install');
  const view = { ...VIEWS.find(view => view.name === 'direct-desktop'), width:390, height:844 };
  for (const engine of BROWSERS) {
    const browser = await launch(engine);
    try {
      const page = await browser.newPage({ viewport: { width:view.width, height:view.height } });
      await measureCell(page, origin, configure, view, 'light', 'uk');
      const input = page.locator('#cfg-ha_token input');
      const instruction = await page.evaluate(() => HaI18n.t('configure.secret.blank_keeps_current', 'blank keeps current'));
      const note = page.getByText(instruction, { exact:true });
      assert.equal(await note.isVisible(), true, `${engine}: full retention instruction visible`);
      assert.ok(await input.evaluate(input => (input.getAttribute('aria-describedby') || '').split(' ').some(id => document.getElementById(id)?.textContent === window.HaI18n.t('configure.secret.blank_keeps_current', 'blank keeps current'))));
      assert.equal(await note.evaluate(node => node.scrollWidth <= node.clientWidth), true);
      await input.fill('example');
      assert.equal(await note.isVisible(), true);
      await measureCell(page, origin, install, view, 'light', 'fr');
      const required = await page.evaluate(() => HaI18n.t('install.backup.passphrase.placeholder', 'Passphrase (required for encrypted backup)'));
      await page.getByLabel(required, { exact:true }).fill('example');
      assert.equal(await page.getByText(required, { exact:true }).isVisible(), true, `${engine}: full backup requirement visible while typing`);
      assert.equal(await page.getByText(required, { exact:true }).evaluate(node => node.scrollWidth <= node.clientWidth), true);
    } finally { await browser.close(); }
  }
});
