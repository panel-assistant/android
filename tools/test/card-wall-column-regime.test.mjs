// Card-wall column-regime contract.
//
// `.cards` is `columns:400px;column-gap:18px`, so the browser lays out N columns in a content box of
// width W as N = max(1, floor((W + 18) / 418)). Two rules in info.css used to hand-compute a VIEWPORT
// threshold from that arithmetic:
//
//   - the single-column-only `content-visibility:auto; contain-intrinsic-size:auto 300px` placeholders
//     were scoped to `@media (max-width:857px)` — but that same query sets `.wrap{padding:8px}`, so the
//     content box is `viewport - 16`, two columns start at a 834px viewport, and 834-857px ran
//     single-column placeholder heights over a genuinely two-column masonry. Feeding placeholder
//     heights to the column balancer is exactly what that rule's own comment says must never happen.
//   - `#revbtn` was hidden below `@media (max-width:1275px)`, computed as `3*400 + 2*18 + 40` from
//     `.wrap`'s 20px padding — the same reasoning error, correct only while nothing changes the padding.
//
// Both derivations are also wrong by the width of a classic scrollbar, and wrong again inside the Panel
// Assistant sidebar iframe, where the frame's width is not the viewport's.
//
// The rules are now container queries on `.cards` and `.topbar`, whose inline size IS the content box
// the columns are computed from, so no threshold restates a padding. A third literal, the `.wrap` page
// gutter, stepped from 20px to 8px at that same 857px line: widening by 1px cost 24px of content box, so
// with a space-consuming scrollbar the wall genuinely LOST a column between 857px and 858px. It now
// ramps across the transition instead, keeping content width monotone in viewport width for any
// scrollbar. These assertions are deliberately RELATIVE rather
// than a list of expected widths: they compare the regime against the column count the browser actually
// produced, so they stay true under any padding, scrollbar or embedding, and they fail on the literals.
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

// 800-900 covers the 1->2 column transition, 1240-1300 the 2->3 transition and the #revbtn threshold.
// Every integer width, because a dead band is exactly what a coarse sample misses.
const SWEEP = [
  ...Array.from({ length: 101 }, (_, i) => 800 + i),
  ...Array.from({ length: 61 }, (_, i) => 1240 + i),
];

function wallPage(containerId) {
  const cards = Array.from({ length: 14 }, (_, index) => `
    <div class="card" data-layout-key="probe-${index}"><h2>Card ${index}</h2>
    <div style="height:${180 + (index % 5) * 60}px">card ${index}</div></div>`).join('');
  return `<!doctype html><html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css">${i18nBridge()}</head><body><div class="wrap">
    <div class="topbar"><div class="hdr">
      <h1><span class="brand">ha-paneld</span></h1>
      <span><button id="revbtn" type="button">Reveal</button></span></div>
      <nav class="nav"><a href="/" class="active">Dashboard</a></nav></div>
    <div class="cards" id="${containerId}">${cards}</div>
    </div></body></html>`;
}

// The embedded shell is an iframe whose width is set independently of the outer viewport — the case the
// viewport-anchored literals cannot see at all.
function embedPage() {
  return `<!doctype html><html><head><meta charset="utf-8"><style>
    html,body{margin:0;padding:0}
    iframe{display:block;border:0;width:900px;height:900px}
  </style>${i18nBridge()}</head><body><iframe id="frame" src="/wall?id=cfg-groups"></iframe></body></html>`;
}

async function startHarness() {
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://panel.test');
    if (url.pathname === '/wall') {
      response.setHeader('content-type', 'text/html; charset=utf-8');
      return response.end(wallPage(url.searchParams.get('id') || 'cfg-groups'));
    }
    if (url.pathname === '/embed') {
      response.setHeader('content-type', 'text/html; charset=utf-8');
      return response.end(embedPage());
    }
    if (url.pathname === '/info.css') {
      response.setHeader('content-type', 'text/css');
      return response.end(await readFile(join(root, 'info.css'), 'utf8'));
    }
    response.writeHead(404);
    response.end('not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, url: `http://127.0.0.1:${server.address().port}` };
}

// Counting distinct card left edges is the only honest column count: getComputedStyle(...).columnCount
// reports `auto` for a column-width layout and tells you nothing.
const READ_WALL = `(() => {
  const wrap = document.querySelector('.wrap');
  const root = document.querySelector('.cards');
  const cards = Array.from(root.children).filter((n) => n.classList.contains('card'));
  const lefts = [...new Set(cards.map((c) => Math.round(c.getBoundingClientRect().left)))];
  const revbtn = document.getElementById('revbtn');
  const style = getComputedStyle(wrap);
  return {
    columns: lefts.length,
    contentWidth: Math.round(wrap.getBoundingClientRect().width
      - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)),
    clientWidth: document.documentElement.clientWidth,
    innerWidth: window.innerWidth,
    estimated: cards.filter((c) => getComputedStyle(c).contentVisibility === 'auto').length,
    cardCount: cards.length,
    revealVisible: getComputedStyle(revbtn).display !== 'none',
  };
})()`;

// The column count the spec requires for the content box the browser actually laid out.
function expectedColumns(contentWidth) {
  return Math.max(1, Math.floor((contentWidth + 18) / 418));
}

function assertRegime(sample, label) {
  // 1. The placeholder estimator is single-column-only by its own comment. Anywhere it is live over a
  //    multi-column wall, placeholder heights reach the column balancer — the defect this pins.
  if (sample.estimated > 0) {
    assert.equal(sample.columns, 1,
      `${label}: ${sample.estimated}/${sample.cardCount} cards carry content-visibility:auto over a `
      + `${sample.columns}-column wall (content box ${sample.contentWidth}px)`);
  }
  // 2. Reveal needs three columns to be worth showing; it must track the real column count, not a
  //    viewport literal that assumes a padding.
  assert.equal(sample.revealVisible, sample.columns >= 3,
    `${label}: #revbtn ${sample.revealVisible ? 'shown' : 'hidden'} over a ${sample.columns}-column wall `
    + `(content box ${sample.contentWidth}px)`);
  // 3. The layout the browser produced must be the column arithmetic, so the regime rules above are
  //    being compared against a wall that is actually laid out the way the spec says.
  assert.equal(sample.columns, expectedColumns(sample.contentWidth),
    `${label}: ${sample.columns} columns in a ${sample.contentWidth}px content box, expected `
    + `${expectedColumns(sample.contentWidth)}`);
}

function assertNoDeadBand(samples, label) {
  for (let i = 1; i < samples.length; i += 1) {
    const previous = samples[i - 1];
    const current = samples[i];
    if (current.width !== previous.width + 1) continue;   // the sweep has two disjoint ranges
    assert.ok(current.columns >= previous.columns,
      `${label}: widening ${previous.width}px -> ${current.width}px LOSES a column `
      + `(${previous.columns} -> ${current.columns})`);
  }
}

const browsers = [
  // Headless Chromium passes --hide-scrollbars by default, which renders overlay scrollbars that
  // consume no layout width — so a sweep could pass while a real window, whose classic scrollbar narrows
  // the content box below the viewport width, still showed the bug.
  {
    name: 'chromium',
    type: chromium,
    launch: {
      executablePath: chrome,
      args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'],
      ignoreDefaultArgs: ['--hide-scrollbars'],
    },
    available: existsSync(chrome),
  },
  // Safari is a first-class target for every web surface, so the sweep runs in both engines: a
  // Chromium-only sweep can pass while the bug is plainly visible in WebKit.
  { name: 'webkit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
];

for (const browser of browsers) {
  const browserTest = browser.available ? test : test.skip;

  browserTest(`${browser.name}: the single-column regime and #revbtn track the real column count (standalone)`, async (t) => {
    const harness = await startHarness();
    const instance = await browser.type.launch(browser.launch);
    t.after(async () => { await instance.close(); await new Promise((resolve) => harness.server.close(resolve)); });

    for (const scrollbar of [false, true]) {
      const context = await instance.newContext({ viewport: { width: 900, height: 700 }, reducedMotion: 'reduce' });
      // A classic (space-consuming) scrollbar narrows the content box without changing the viewport —
      // so a viewport-derived threshold is wrong by its width. Headless Chromium hides scrollbars by
      // default, which is precisely why a passing sweep could still miss a real browser window.
      // Styling ::-webkit-scrollbar is what switches WebKit off overlay scrollbars onto space-consuming
      // ones; Chromium needs only the forced overflow, having been launched without --hide-scrollbars.
      if (scrollbar) await context.addInitScript(() => {
        document.addEventListener('DOMContentLoaded', () => {
          document.documentElement.style.overflowY = 'scroll';
          const style = document.createElement('style');
          style.textContent = 'html::-webkit-scrollbar{width:15px}html::-webkit-scrollbar-thumb{background:#888}';
          document.head.appendChild(style);
        });
      });
      const page = await context.newPage();
      await page.goto(`${harness.url}/wall?id=cfg-groups`, { waitUntil: 'load' });

      const samples = [];
      for (const width of SWEEP) {
        await page.setViewportSize({ width, height: 700 });
        await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        const sample = await page.evaluate(READ_WALL);
        samples.push({ ...sample, width });
        assertRegime(sample, `${browser.name} standalone scrollbar=${scrollbar} ${width}px`);
      }
      assertNoDeadBand(samples, `${browser.name} standalone scrollbar=${scrollbar}`);
      // A scrollbar variant that never produces a space-consuming scrollbar tests nothing, and would
      // quietly turn the one case the viewport literals get wrong into a passing cell.
      if (scrollbar) {
        const overlay = samples.filter((s) => s.clientWidth >= s.innerWidth);
        assert.equal(overlay.length, 0,
          `${browser.name}: the forced-scrollbar sweep consumed no layout width at ${overlay.length}/`
          + `${samples.length} widths (first ${overlay[0]?.width}px) — those cells prove nothing`);
      }
      await context.close();
    }
  });

  browserTest(`${browser.name}: the same holds inside an embedded-mode frame`, async (t) => {
    const harness = await startHarness();
    const instance = await browser.type.launch(browser.launch);
    t.after(async () => { await instance.close(); await new Promise((resolve) => harness.server.close(resolve)); });

    // A fixed outer viewport with a resized frame: the frame's width is the only thing that moves, which
    // is how the Panel Assistant sidebar actually embeds these pages.
    const context = await instance.newContext({ viewport: { width: 1400, height: 900 }, reducedMotion: 'reduce' });
    const page = await context.newPage();
    await page.goto(`${harness.url}/embed`, { waitUntil: 'load' });
    const frame = page.frameLocator('#frame');
    await frame.locator('.cards').waitFor();

    const samples = [];
    for (const width of SWEEP) {
      await page.evaluate((w) => { document.getElementById('frame').style.width = `${w}px`; }, width);
      await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      const sample = await page.frames()[1].evaluate(READ_WALL);
      samples.push({ ...sample, width });
      assertRegime(sample, `${browser.name} embedded ${width}px frame`);
    }
    assertNoDeadBand(samples, `${browser.name} embedded`);
    await context.close();
  });
}
