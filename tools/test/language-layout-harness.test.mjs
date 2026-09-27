// Negative controls for the language layout gate: every check must be able to fail. A German Configure
// cell is loaded in each browser, broken on purpose, and the verdict must name each error family;
// on the panel's own screen overflow, overlap and wrapping must only be reported.
import assert from 'node:assert/strict';
import test from 'node:test';
import { BROWSERS, VIEWS, launch, loadCatalogues, measureCell, startLayoutServer, verdict } from './language-layout/harness.mjs';
import { PAGES } from './language-layout/pages/index.mjs';

const { catalogues, locales } = await loadCatalogues();
const configure = PAGES.find((page) => page.name === 'configure');
const server = await startLayoutServer(PAGES, catalogues, locales);
const origin = `http://127.0.0.1:${server.address().port}`;
test.after(() => new Promise((done) => server.close(done)));

function breakLayout() {
  const card = document.querySelector('#cfg-groups .card');
  // Growth without new text: squeeze the second card's rows so the same words need many more lines.
  const squeezed = document.querySelector('[data-layout-key="configure-mqtt"]');
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
        assert.deepEqual(verdict(clean, english, view, 'de').errors.filter((line) => line.includes('mutant')), [], `${view.name}: the unbroken cell reports no mutant`);

        const { errors, reports } = verdict(broken, english, view, 'de');
        const found = (list, family, key) => list.some((line) => line.includes(`(${family}`) && (!key || line.includes(key)));
        const blocking = view.tier !== 'panel';
        assert.ok(found(errors, 'a cut off', 'mutant-cut'), `${view.name}: cut-off text must fail\n${errors.join('\n')}`);
        assert.ok(found(errors, 'c unreachable', 'mutant-offscreen'), `${view.name}: an off-screen control must fail`);
        assert.equal(found(blocking ? errors : reports, 'b overflow', 'mutant-nowrap'), true, `${view.name}: nowrap overflow must be ${blocking ? 'an error' : 'reported'}`);
        assert.equal(found(blocking ? errors : reports, 'b overflow', 'page'), true, `${view.name}: sideways page scroll must be ${blocking ? 'an error' : 'reported'}`);
        assert.equal(found(blocking ? errors : reports, 'b overlap'), true, `${view.name}: overlapping controls must be ${blocking ? 'an error' : 'reported'}`);
        assert.equal(found(blocking ? errors : reports, 'd wraps', 'mutant-title'), true, `${view.name}: a wrapping card title must be ${blocking ? 'an error' : 'reported'}`);
        assert.equal(found(blocking ? errors : reports, 'd card growth', 'configure-mqtt'), true, `${view.name}: card growth the text does not explain must be ${blocking ? 'an error' : 'reported'}\n${reports.concat(errors).filter((line) => line.includes('growth')).join('\n')}`);
        if (!blocking) {
          assert.ok(!errors.some((line) => /\((b|d) /.test(line)), `${view.name}: panel sizes must not block on overflow or wrapping\n${errors.join('\n')}`);
        }
      }
    } finally {
      await browser.close();
    }
  });
}

test('the CI matrix shards exactly the gate pages and browsers', async () => {
  const { readFile } = await import('node:fs/promises');
  const workflow = await readFile(new URL('../../.github/workflows/ui-layout.yml', import.meta.url), 'utf8');
  const list = (name) => workflow.match(new RegExp(`\\n\\s+${name}: \\[([^\\]]+)\\]`))[1].split(',').map((item) => item.trim()).sort();
  assert.deepEqual(list('page'), PAGES.map((page) => page.name).sort());
  assert.deepEqual(list('browser'), [...BROWSERS].sort());
});
