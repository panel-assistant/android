// The Entities page under an allowed dashboard-strategy check, in a real DOM with the shipped stylesheet,
// in Chromium and WebKit: the note and the status part render once, in order, and stay inside their card
// from a narrow portrait panel to a desktop browser.
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { test } from 'node:test';
import { chromium, webkit } from 'playwright-core';

const chrome = process.env.CHROME || '/usr/bin/chromium';
const css = readFileSync('../../app/src/main/assets/info.css', 'utf8');
const entitiesSource = readFileSync('../../app/src/main/assets/entities.js', 'utf8');

const engines = [
  { name: 'chromium', type: chromium, launch: { executablePath: chrome, args: ['--no-sandbox'] }, available: existsSync(chrome) },
  { name: 'webkit', type: webkit, launch: {}, available: existsSync(webkit.executablePath()) },
];

function issue(fingerprint, ignored) {
  return {
    type: 'unbounded_selector', blocking: !ignored, ignored, ignorable: true, fingerprint,
    severity: ignored ? 'warning' : 'error', view_title: 'Dashboard', view_path: 'dashboard', card_title: null,
    source_locations: ['dashboard.strategy'], rule_summary: 'Dashboard strategy generates entity dependencies at runtime',
    candidate_count: null, limit: 64, reason: '', recommendation: '', presentation_code: 'dashboard-strategy',
  };
}

function html() {
  const issues = [issue('allowed', true), issue('pending', false)];
  const status = {
    state: 'active', sync_running: false, stream_entity_count: 4, stream_mode: 'filtered', catalog_count: 442,
    suggested_count: 0, blocking_issue_count: 1, ignored_issue_count: 1, unresolved_count: 0, last_sync_at: 0,
    db_bytes: 8, auto_static: true, auto_runtime: true, apply_required: false, strategy_selector_ignored: true,
  };
  const bootstrap = `window.setInterval=()=>0;window.fetch=async(url)=>{if(url.includes('/entities/issues'))return{ok:true,status:200,json:async()=>(${JSON.stringify({ items: issues, dashboard_issue_count: 2, blocking_issue_count: 1, ignored_issue_count: 1, dynamic_expressions: [] })})};if(url.includes('/entities?'))return{ok:true,status:200,json:async()=>({items:[],total:0})};return{ok:true,status:200,json:async()=>(${JSON.stringify(status)}),text:async()=>''}};`;
  return `<!doctype html><html lang="en" data-theme="dark"><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>${css}</style></head><body>
    <script>${bootstrap}</script>
    <div class="cards entity-cards">
      <div class="card"><h2>Entity filter</h2><div id="entity-status"></div>
        <button class="pbtn" id="entity-sync"></button><button class="pbtn" id="entity-activate"></button><button class="pbtn" id="entity-reset"></button>
        <div id="entity-action-result"></div><input id="entity-auto-static" type="checkbox"><input id="entity-auto-runtime" type="checkbox">
        <input id="entity-search" type="search"><div id="entity-search-status"></div></div>
      <div class="card entity-issues" id="entity-issues"><h2>Entity-discovery checks</h2>
        <div id="entity-issues-summary" class="muted"></div><div id="entity-issues-list" class="entity-issues-list"></div>
        <section id="entity-dynamic" class="entity-dynamic" hidden><div id="entity-dynamic-list"></div></section>
        <button class="pbtn" id="entity-issues-rescan" type="button">Scan</button></div>
    </div>
    <script>${entitiesSource.replaceAll('</script>', '<\\/script>')}</script></body></html>`;
}

for (const engine of engines) {
  for (const width of [360, 1280]) {
    test(`${engine.name} ${width}px: allowed strategy note and status part render once, in order, inside their card`, { skip: !engine.available, timeout: 30_000 }, async () => {
      const browser = await engine.type.launch({ headless: true, ...engine.launch });
      try {
        const page = await browser.newPage({ viewport: { width, height: 900 } });
        await page.setContent(html(), { waitUntil: 'load' });
        await page.waitForFunction(() => document.querySelectorAll('.entity-issue').length === 2);
        await page.waitForFunction(() => document.querySelector('#entity-status .hot'));

        const facts = await page.evaluate(() => {
          const box = (el) => { const r = el.getBoundingClientRect(); return { left: r.left + scrollX, right: r.right + scrollX, top: r.top + scrollY, bottom: r.bottom + scrollY }; };
          const allowed = document.querySelector('[data-fingerprint="allowed"]');
          const pending = document.querySelector('[data-fingerprint="pending"]');
          const notes = allowed.querySelectorAll('.entity-issue-strategy-note');
          const note = notes[0];
          const allowNote = allowed.querySelector('.entity-issue-allow-note');
          return {
            notes: notes.length,
            pendingNotes: pending.querySelectorAll('.entity-issue-strategy-note').length,
            noteTag: note && note.localName,
            noteText: note && note.textContent,
            order: note && allowNote ? note.compareDocumentPosition(allowNote) & Node.DOCUMENT_POSITION_FOLLOWING : 0,
            note: note && box(note), allowNote: allowNote && box(allowNote), row: box(allowed),
            hot: Array.from(document.querySelectorAll('#entity-status .hot')).map((el) => el.textContent),
            pageOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
          };
        });

        assert.equal(facts.notes, 1, 'the allowed strategy row carries the note exactly once');
        assert.equal(facts.pendingNotes, 0, 'a strategy check still awaiting a choice carries no note');
        assert.equal(facts.noteTag, 'p');
        assert.match(facts.noteText, /^Home Assistant builds this dashboard from a strategy/);
        assert.ok(facts.order, 'the strategy note precedes the general allow note');
        assert.equal(facts.hot.filter((text) => text === 'strategy dashboard: entities outside the subscription have no cards').length, 1);
        assert.ok(facts.note.left >= facts.row.left - 0.5 && facts.note.right <= facts.row.right + 0.5, `note stays inside its row: ${JSON.stringify(facts)}`);
        assert.ok(facts.note.bottom <= facts.allowNote.top + 0.5, `note and allow note do not overlap: ${JSON.stringify(facts)}`);
        assert.ok(facts.allowNote.top - facts.note.bottom < 24, 'the two notes read as one block, not a detached paragraph');
        assert.equal(facts.pageOverflow, false, 'no horizontal page overflow');
      } finally {
        await browser.close();
      }
    });
  }
}
