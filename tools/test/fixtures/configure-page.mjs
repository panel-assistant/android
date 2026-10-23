// The Configure page the browser tests load, its harness and the waits they share.
import { i18nBridge } from './i18n-bridge.mjs';
import { startHarness as startHarnessWith } from './browser-harness.mjs';

// These journeys exercise every setting, advanced ones included, so their Configure stubs open in the
// Advanced view the way a returning user's browser would; configure-density.test.mjs covers Basic.
export const HELP_POPOVER = '<div id="cfg-help" class="cfg-help" popover="manual"><div class="cfg-help-head"><b id="cfg-help-title"></b><button id="cfg-help-close" type="button">x</button></div><div id="cfg-help-body" class="cfg-help-body"></div><div class="cfg-help-foot"><a id="cfg-help-more"></a></div></div>';
export const ADVANCED_VIEW = `<script>try{localStorage.getItem('ha-paneld.configure.view.v1')||localStorage.setItem('ha-paneld.configure.view.v1','{"advanced":true}')}catch(e){}</script>`;

export function fixture(translations = {}, locale = 'en') {
  return `<!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="/info.css">${i18nBridge({ locale, strings: translations })}</head><body>
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <button id="tab-basic"></button><button id="tab-adv"></button>
    <div id="cfg-msg" class="sr-only" role="status" aria-live="polite" aria-atomic="true"></div><p id="cfg-status"></p><div id="cfg-groups"></div>
    <div id="proximity-learning-mount"></div>${HELP_POPOVER}
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    ${ADVANCED_VIEW}<script src="/configure-state.js"></script><script src="/configure-view.js"></script><script src="/configure-help.js"></script><script src="/configure-controls.js"></script><script src="/configure-brightness.js"></script><script src="/configure-auto-sleep.js"></script><script src="/configure-cards.js"></script><script src="/configure-render.js"></script><script src="/configure.js"></script><script src="/proximity-learning.js"></script>
  </body></html>`;
}

// The page follows the panel through the `config` family (GET /api/v1/config/values). A fixture that does
// not answer that read itself answers it with its GET /api/v1/config body, which carries the same fields.
export const startHarness = (routes, pageFixture = fixture, assetDelayMs = 0) => startHarnessWith(async (path, request) => {
  if (path !== '/api/v1/config/values') return routes(path, request);
  return (await routes(path, request)) ?? routes('/api/v1/config', request);
}, pageFixture, assetDelayMs);

export function configureVisualFixture() {
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css">${i18nBridge()}</head><body>
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <button id="tab-basic"></button><button id="tab-adv"></button>
    <div id="cfg-msg" class="sr-only" role="status" aria-live="polite" aria-atomic="true"></div><p id="cfg-status"></p>
    <div id="cfg-groups" class="cards" data-card-size-page="configure" data-card-size-epoch="1" data-card-size-restore="1"></div>
    <div id="proximity-learning-mount"></div>${HELP_POPOVER}
    <script src="/assets/card-size-memory.js"></script><script src="/assets/card-column-alignment.js"></script>
    ${ADVANCED_VIEW}<script src="/configure-state.js"></script><script src="/configure-view.js"></script><script src="/configure-help.js"></script><script src="/configure-controls.js"></script><script src="/configure-brightness.js"></script><script src="/configure-auto-sleep.js"></script><script src="/configure-cards.js"></script><script src="/configure-render.js"></script><script src="/configure.js"></script><script src="/proximity-learning.js"></script>
  </body></html>`;
}

// A smooth scroll runs on the compositor, during which headless Chromium stops servicing
// requestAnimationFrame — Playwright's default polling. Every wait that straddles one polls on a timer.
export const settled = (page, predicate) => page.waitForFunction(predicate, null, { polling: 100 });

export function autoSleepHistory({ included = true, label = 'Office ceiling motion', hours = 6, laneCount = 1, areaName = 'Office', areaKey = 'a'.repeat(64) } = {}) {
  const end = Date.now();
  const start = end - hours * 60 * 60 * 1000;
  return {
    available: true,
    hours,
    area_name: areaName,
    area_key: areaKey,
    window_start_epoch_ms: start,
    window_end_epoch_ms: end,
    segments: [{ start_epoch_ms: start, end_epoch_ms: end, output: included ? 'hold_awake' : 'allow_sleep' }],
    source_lanes: Array.from({ length: laneCount }, (_, index) => ({
      source_key: index.toString(16).padStart(64, 'b').slice(-64), label: index ? `${label} ${index + 1}` : label, included,
      segments: [{ start_epoch_ms: start, end_epoch_ms: end, state: included ? 'on' : 'off' }],
    })),
  };
}
