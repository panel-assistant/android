// Entities page, issue #114 follow-up: bringing the first matches into view must not carry the search
// box away with it. Someone narrowing a broad result keeps typing, and after every refinement the box is
// still on screen below the sticky header, and the section the page moved to is not hidden under it.
// Real entities.js and info.css, in Chromium and WebKit, from a phone to a wide panel.
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { test } from 'node:test';
import { chromium, webkit } from 'playwright-core';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';

const chrome = process.env.CHROME || '/usr/bin/chromium';
const css = readFileSync('../../app/src/main/assets/info.css', 'utf8');
const entitiesSource = readFileSync('../../app/src/main/assets/entities.js', 'utf8');

const engines = [
  { name: 'chromium', type: chromium, launch: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'webkit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
];

// Matches per section for each query. No query gives a tall Current table, the page the reporter had.
const totals = {
  '': { subscribed: 120, candidate: 0, review: 0 },
  l: { subscribed: 80, candidate: 40, review: 5 },
  li: { subscribed: 30, candidate: 12, review: 0 },
  lig: { subscribed: 4, candidate: 0, review: 0 },
  ligx: { subscribed: 0, candidate: 0, review: 0 },
};

// Mirrors entitiesBody() in PaneldServer.kt, under a stand-in for the shell's sticky header.
function html() {
  const status = {
    state: 'active', sync_running: false, stream_entity_count: 120, stream_mode: 'filtered', catalog_count: 3769,
    suggested_count: 3, blocking_issue_count: 0, ignored_issue_count: 0, unresolved_count: 0, last_sync_at: 0,
    db_bytes: 8, auto_static: true, auto_runtime: true, apply_required: false,
  };
  const bootstrap = `window.setInterval=()=>0;var totals=${JSON.stringify(totals)};
    function rows(n,p){var out=[];for(var i=0;i<n;i++)out.push({entity_id:p+'.entity_'+i,reasons:'dashboard',static:true,access_1h:1,rate_1h_bps:0,last_access:0});return out}
    window.fetch=async(url)=>{var body;if(url.includes('/entities/issues'))body={items:[],dashboard_issue_count:0,blocking_issue_count:0,ignored_issue_count:0,dynamic_expressions:[]};
      else if(url.includes('/entities?')){var q=new URL(url,'http://panel.test').searchParams,total=(totals[(q.get('q')||'').trim()]||{})[q.get('filter')]||0;body={items:rows(Math.min(total,100),q.get('filter')),total:total}}
      else body=${JSON.stringify(status)};return{ok:true,status:200,json:async()=>body,text:async()=>''}};`;
  const table = (id, title, short, filter) => `
    <div class="card entity-list" data-filter="${filter}" data-table="${id}" data-short="${short}"><h2>${title}</h2>
      <div class="entity-bulk"><button class="pbtn" data-bulk="pinned">Pin selected</button><span class="muted entity-selected">0 selected</span></div>
      <div class="tablewrap"><table class="entity-table"><thead><tr>
        <th class="col-select"><input type="checkbox" class="entity-select-page" aria-label="Select this page"></th>
        <th class="col-entity"><button data-sort="entity_id">Entity</button></th>
        <th class="col-access"><button data-sort="access_1h">Accesses</button></th>
        <th class="col-override"><button data-sort="override">Override</button></th>
      </tr></thead><tbody></tbody></table></div>
      <div><button class="pbtn entity-prev">Previous</button><button class="pbtn entity-next">Next</button><span class="muted entity-msg">Loading…</span></div>
    </div>`;
  return `<!doctype html><html lang="en" data-theme="dark"><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>${css}</style>${i18nBridge()}</head><body>
    <script>${bootstrap}</script>
    <div class="wrap">
    <div class="topbar"><div class="hdr"><h1>Panel Assistant</h1></div><nav class="nav"><a class="active">Entities</a></nav></div>
    <script>document.documentElement.style.setProperty('--topbar-h',document.querySelector('.topbar').getBoundingClientRect().height+'px')</script>
    <div class="cards entity-cards">
      <div class="card"><h2>Entity subscription filter</h2>
        <div id="entity-status">Loading…</div>
        <div><button class="pbtn" id="entity-sync">Scan dashboard now</button><button class="pbtn" id="entity-activate" disabled>Checking…</button><button class="pbtn" id="entity-reset" type="button">Reset learned data</button></div>
        <div id="entity-action-result" class="entity-action-result muted" role="status" aria-live="polite"></div>
        <fieldset class="entity-policy"><legend>Automatic promotion</legend>
          <label><input type="checkbox" id="entity-auto-static"> Static</label>
          <label><input type="checkbox" id="entity-auto-runtime"> Runtime</label>
        </fieldset>
      </div>
      <div class="entity-search-row">
        <label class="sr-only" for="entity-search">Search the complete Home Assistant entity catalogue</label>
        <input id="entity-search" type="search" autocomplete="off" placeholder="Search the complete Home Assistant entity catalogue" aria-describedby="entity-search-status">
        <div id="entity-search-status" class="entity-search-status muted" role="status" aria-live="polite"></div>
      </div>
      <div class="card entity-issues" id="entity-issues"><h2>Entity-discovery compatibility</h2>
        <div id="entity-issues-summary" class="muted" role="status" aria-live="polite"></div>
        <div id="entity-issues-list" class="entity-issues-list"></div>
        <section id="entity-dynamic" class="entity-dynamic" hidden><h3>Dynamic expressions</h3><div id="entity-dynamic-list"></div></section>
        <button class="pbtn" id="entity-issues-rescan" type="button">Re-scan</button>
      </div>
      ${table('current', 'Current subscribed entities', 'Current', 'subscribed')}
      ${table('suggested', 'Suggested dashboard entities', 'Suggested', 'candidate')}
      ${table('review', 'Stale or noisy entities', 'Stale or noisy', 'review')}
    </div></div>
    <script>${entitiesSource.replaceAll('</script>', '<\\/script>')}</script></body></html>`;
}

// Where the search box and the section a reveal targets sit, in the viewport.
const geometry = (page, table) => page.evaluate((name) => {
  const rect = (node) => node.getBoundingClientRect();
  const input = rect(document.getElementById('entity-search'));
  const target = document.querySelector(`[data-table="${name}"]`);
  return {
    viewport: window.innerHeight, scrollY: window.scrollY,
    headerBottom: rect(document.querySelector('.topbar')).bottom,
    input: { top: input.top, bottom: input.bottom },
    rowBottom: rect(document.querySelector('.entity-search-row')).bottom,
    target: target ? { top: rect(target).top, bottom: rect(target).bottom } : null,
    focus: document.activeElement && document.activeElement.id,
    status: document.getElementById('entity-search-status').textContent,
  };
}, table);

function assertSearchInView(g, step) {
  assert.equal(g.input.top >= g.headerBottom - 1, true, `${step}: the search box is hidden under the header (${JSON.stringify(g)})`);
  assert.equal(g.input.bottom <= g.viewport + 1, true, `${step}: the search box is below the viewport (${JSON.stringify(g)})`);
  assert.equal(g.focus, 'entity-search', `${step}: typing must be able to continue, so focus stays in the box`);
}

function assertTargetShown(g, step) {
  assert.equal(g.target.top >= g.rowBottom - 1, true, `${step}: the revealed section starts under the search box (${JSON.stringify(g)})`);
  assert.equal(g.target.top < g.viewport, true, `${step}: the revealed section is not on screen (${JSON.stringify(g)})`);
}

// A scroll is settled when two readings 150ms apart agree.
async function rest(page) {
  let previous = -1;
  for (let attempt = 0; attempt < 30; attempt += 1) {
    const now = await page.evaluate(() => window.scrollY);
    if (now === previous) return;
    previous = now;
    await new Promise((resolve) => setTimeout(resolve, 150));
  }
}

async function typeAndSettle(page, keys, status) {
  await page.keyboard.type(keys);
  await page.waitForFunction((text) => document.getElementById('entity-search-status').textContent === text, status, { polling: 100 });
  await rest(page);
}

const viewports = [
  { name: 'phone', width: 390, height: 760 },
  { name: 'narrow panel', width: 480, height: 480 },
  { name: 'wide panel', width: 1280, height: 800 },
];

for (const engine of engines) {
  for (const viewport of viewports) {
    test(`${engine.name} ${viewport.name}: the search box stays in view through repeated refinement`, { skip: !engine.available, timeout: 60_000 }, async () => {
      const browser = await engine.type.launch({ headless: true, ...engine.launch });
      try {
        // Reduced motion is a production path (an instant jump), so no step measures a scroll in flight.
        const page = await browser.newPage({ viewport: { width: viewport.width, height: viewport.height }, reducedMotion: 'reduce' });
        page.setDefaultTimeout(8_000);
        await page.setContent(html(), { waitUntil: 'load' });
        await page.waitForFunction(() => document.querySelectorAll('[data-table="current"] tbody tr').length === 100);

        // Empty query: tap the box as a user would (on a short panel that scrolls it up to the fold), with
        // Suggested still below the fold so a reveal means something.
        await page.locator('#entity-search').click();
        await rest(page);
        let g = await geometry(page, 'suggested');
        assert.equal(g.target.top >= g.viewport, true, 'Suggested must start below the fold');
        assertSearchInView(g, 'empty query');

        // A broad query reveals Suggested.
        await typeAndSettle(page, 'l', 'Matches: Current: 80, Suggested: 40, Stale or noisy: 5');
        g = await geometry(page, 'suggested');
        assert.equal(g.scrollY > 0, true, 'a broad query moves the page to its matches');
        assertTargetShown(g, 'broad query');
        assertSearchInView(g, 'broad query');

        // Refining without going back to the box: Suggested is already on screen and stays there.
        await typeAndSettle(page, 'i', 'Matches: Current: 30, Suggested: 12, Stale or noisy: 0');
        g = await geometry(page, 'suggested');
        assertTargetShown(g, 'first refinement');
        assertSearchInView(g, 'first refinement');

        // Refining again moves the matches to Current, above: the page follows, the box stays.
        await typeAndSettle(page, 'g', 'Matches: Current: 4, Suggested: 0, Stale or noisy: 0');
        g = await geometry(page, 'current');
        assertTargetShown(g, 'second refinement');
        assertSearchInView(g, 'second refinement');

        // No matches: emptied tables shorten the page and the browser clamps the scroll, but nothing chases a
        // section and the box is still there to correct the query.
        await typeAndSettle(page, 'x', 'No entities match this search.');
        assertSearchInView(await geometry(page, 'current'), 'no matches');
      } finally {
        await browser.close();
      }
    });
  }
}
