// Non-blocking layout-stability (CLS) matrix for the info page.
//
// Serves the real info.css/info.js (via tools/test/fixtures/info-fixture.html), mocks /perf + /proximity +
// /inspect with worst-case CYCLING data (process names long↔short, render drawing↔idle, raw sweeping),
// then measures Cumulative Layout Shift across a matrix of viewport WIDTHS × TEXT SIZES (the myopic
// axis) while the live cards are scrolled OFF-SCREEN. Each cell reports the median of repeated page
// loads, preserving the representative run's offender attribution. Diffs a committed baseline:
//   - worse than baseline (+epsilon)  -> REGRESSION (flagged, but exit 0 — never blocks a build)
//   - within baseline                 -> ok / known-backlog
// `--update-baseline` rewrites tools/test/baseline.json; use RUNS=5 or greater for baseline refreshes.
// Local: `node tools/test/layout-matrix.mjs`.
import { chromium } from 'playwright-core';
import http from 'node:http';
import { readFile, writeFile } from 'node:fs/promises';
import { extname, isAbsolute, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { attachUiSocket } from './fixtures/ui-socket.mjs';
import {
  parseRunCount,
  requireBaselineRunCount,
  summarizeSamples,
} from './layout-statistics.mjs';

const ROOT = fileURLToPath(new URL('../..', import.meta.url)); // repo root
const CHROME = process.env.CHROME || '/usr/bin/chromium';
const SECS = +(process.env.SECS || 7);
const RUNS = parseRunCount(process.env.RUNS);
const UPDATE_BASELINE = process.argv.includes('--update-baseline');
if (UPDATE_BASELINE) requireBaselineRunCount(RUNS);
const EPS = +(process.env.EPS || 0.06); // repeated sampling rejects timing outliers, allowing this to
                                        // flag material movement while remaining report-only.
const WIDTHS = [480, 1280, 1920, 2560]; // 480 = smallest real panel (NSPanel Pro 480×480; below that the
                                        // hardware can't run an HA dashboard); 1920 ≈ 10", 2560 ≈ 15" → up to 4 cols
const FONTS = [16, 20, 24];             // normal → large → x-large (myopic)
const MIME = { '.html': 'text/html', '.css': 'text/css', '.js': 'application/javascript', '.svg': 'image/svg+xml' };

let cyc = 0;
const PROCS = [
  [{ name: 'io.github.maxlyth.hapaneld', cpu: 12 }, { name: 'system_server', cpu: 8 }, { name: 'surfaceflinger', cpu: 4 }, { name: 'io.homeassistant.companion.android.minimal', cpu: 3 }, { name: 'kworker/0:1', cpu: 1 }],
  [{ name: 'system_server', cpu: 9 }, { name: 'zgateway', cpu: 2 }, { name: 'ksoftirqd/0', cpu: 1 }, { name: 'io.github.maxlyth.hapaneld', cpu: 6 }, { name: 'mosquitto', cpu: 1 }],
];
const RAWS = [70, 58, 44, 30, 22, 30, 44, 58];
function perfPayload() {
  const n = cyc++; const drawing = n % 3 === 0;
  return { enabled: true, cpu: 10 + (n % 7), cores: [12, 18, 9, 7], memUsedMb: 900 + (n % 50), memTotalMb: 2000,
    gpu: 5 + (n % 4), gpuMhz: 400, freqMhz: [1512], freqMaxMhz: 1512, load: [1.2 + (n % 2), 1.0, 0.8], tempC: 41 + (n % 3),
    hist: { cpu: [10, 12, 11], ram: [45, 46], gpu: [5, 6] }, top: PROCS[n % PROCS.length],
    render: { status: 'ok', pkg: 'io.homeassistant.companion.android.minimal', verdict: drawing ? 'occasional' : 'smooth',
      mainPct: 30 + (n % 20), jankPct: drawing ? 5 + (n % 10) : null, p99: 18, hist: [3, 5, 4] },
    builtin: { ttiColdMs: 12400, ttiWarmMedianMs: 4300, reloads24h: n % 3 ? 0 : 2 },
    dashboard: { mode: 'builtin_direct', sampleCount: 30, likelyCause: n % 2 ? 'state_stream' : 'rendering_or_media',
      confidence: 'medium', interaction: { count: 8, p50Ms: 200, p95Ms: 500, worstMs: 1830,
        inputDelayMs: 420, processingMs: 710, presentationMs: 700 },
      blocking: { blockedMsPerSec: 190, blockedP95MsPerSec: 460, longestFrameMs: 820 },
      stateStream: { updatesPerSec: 87.4, updatesP95PerSec: 174.9, payloadBytesPerSec: 164000,
        payloadP95BytesPerSec: 910000, mainThreadMsPerSec: 250, mainThreadP95MsPerSec: 620,
        longestStateTaskMs: 930, hydrationUpdates: 3410, droppedFrames: 1 },
      filter: { active: true, entityCount: 317 },
      topEntities: [
        { entityId: 'sensor.extremely_long_energy_monitoring_entity_identifier', updates1m: 845, payloadBps1m: 82400 },
        { entityId: 'sensor.second_noisy_diagnostic_measurement', updates1m: 320, payloadBps1m: 41200 },
        { entityId: 'binary_sensor.third_frequently_changing_entity', updates1m: 110, payloadBps1m: 12800 }],
      history: { interactionMs: [120, 220, 700, 140, 1830], updatesPerSec: [20, 80, 175, 45, 87],
        payloadBytesPerSec: [40000, 120000, 910000, 80000, 164000],
        mainThreadMsPerSec: [30, 180, 620, 90, 250], blockedMsPerSec: [20, 90, 460, 80, 190] } } };
}
function proxPayload() { const raw = RAWS[cyc % RAWS.length];
  return { present: true, raw, near: raw < 40, max: 9, calibrated: false, threshold: null, nearRaw: null, farRaw: null,
    nearBelow: true, margin: 0, sensitivity: 'MEDIUM', indistinct: false, graded: true, distinct: 32 }; }

async function measure(browser, port, width, font) {
  const page = await browser.newPage({ viewport: { width, height: 860 } });
  await page.route('**/perf', (r) => r.fulfill({ contentType: 'application/json', body: JSON.stringify(perfPayload()) }));
  await page.route('**/proximity', (r) => r.fulfill({ contentType: 'application/json', body: JSON.stringify(proxPayload()) }));
  await page.route('**/inspect', (r) => r.fulfill({ contentType: 'application/json', body: JSON.stringify({ running: false, port: 9222, status: 'no-socket' }) }));
  await page.goto(`http://127.0.0.1:${port}/tools/test/fixtures/info-fixture.html`, { waitUntil: 'domcontentloaded' });
  await page.evaluate((px) => { document.documentElement.style.fontSize = px + 'px'; }, font);
  await page.evaluate(() => {
    window.__cls = 0; window.__by = {};
    new PerformanceObserver((l) => { for (const e of l.getEntries()) { if (e.hadRecentInput) continue; window.__cls += e.value;
      for (const s of e.sources || []) { const n = s.node, c = n && n.closest && n.closest('.card'), h = c && c.querySelector('h2');
        const k = h ? h.textContent.replace(/\s+/g, ' ').trim() : (n && (n.id || n.tagName)) || '?'; window.__by[k] = (window.__by[k] || 0) + e.value; } }
    }).observe({ type: 'layout-shift', buffered: true });
  });
  await page.waitForFunction(() =>
    document.querySelector('#perf th')?.textContent === 'CPU'
    && Boolean(document.querySelector('#topproc td')?.textContent)
    && document.querySelector('#perfage')?.textContent === '· live',
  null, { timeout: 5000 });
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForTimeout(SECS * 1000);
  const r = await page.evaluate(() => ({ cls: +window.__cls.toFixed(4), by: window.__by }));
  await page.close();
  return r;
}

// The page talks to the fake stub door (WebSocket, the same mocks) unless LAYOUT_TRANSPORT=http.
const SOCKET = process.env.LAYOUT_TRANSPORT !== 'http';
const server = http.createServer(async (req, res) => {
  try { const p = decodeURIComponent(req.url.split('?')[0]); const file = resolve(ROOT, '.' + p); const rel = relative(ROOT, file);
    if (isAbsolute(rel) || rel === '..' || rel.startsWith('../')) throw new Error('path outside fixture root');
    let body = await readFile(file);
    if (SOCKET && p.endsWith('/info-fixture.html')) body = String(body).replace('<body>', '<body data-ui-socket="/ui">');
    res.writeHead(200, { 'Content-Type': MIME[extname(file)] || 'application/octet-stream' }); res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const port = server.address().port;
await attachUiSocket(server, async ({ url }) => {
  const path = url.split('?')[0];
  if (path.endsWith('/perf')) return { body: JSON.stringify(perfPayload()) };
  if (path.endsWith('/proximity')) return { body: JSON.stringify(proxPayload()) };
  if (path.endsWith('/inspect')) return { body: JSON.stringify({ running: false, port: 9222, status: 'no-socket' }) };
  return { status: 404, body: '{}' };
}, { record: null });
console.log(`transport: ${SOCKET ? 'WebSocket stub door' : 'HTTP'}`);
const browser = await chromium.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'] });

const results = {};
for (const w of WIDTHS) for (const f of FONTS) {
  const key = `${w}x${f}`; const samples = [];
  for (let run = 0; run < RUNS; run++) samples.push(await measure(browser, port, w, f));
  results[key] = summarizeSamples(samples);
}
await browser.close(); server.close();

let baseline = {};
try { baseline = JSON.parse(await readFile(join(ROOT, 'tools/test/baseline.json'), 'utf8')); } catch { /* none yet */ }

if (UPDATE_BASELINE) {
  const out = {}; for (const k of Object.keys(results)) out[k] = results[k].cls;
  await writeFile(join(ROOT, 'tools/test/baseline.json'), JSON.stringify(out, null, 2) + '\n');
  console.log('baseline updated:', JSON.stringify(out));
  process.exit(0);
}

console.log(`## CLS layout matrix (width × text-px) — median of ${RUNS}, non-blocking, good < 0.10\n`);
console.log('| cell | median CLS | run range | baseline | status | top offender |');
console.log('| --- | --- | --- | --- | --- | --- |');
let regressions = 0;
for (const w of WIDTHS) for (const f of FONTS) {
  const k = `${w}x${f}`; const cls = results[k].cls; const base = baseline[k];
  const range = RUNS === 1 ? '—' : `${results[k].minCls.toFixed(4)}–${results[k].maxCls.toFixed(4)}`;
  const top = Object.entries(results[k].by).sort((a, b) => b[1] - a[1])[0];
  const offender = top ? `${top[0]} (${top[1].toFixed(3)})` : '—';
  let status = 'ok';
  if (base == null) status = 'new (no baseline)';
  else if (cls > base + EPS) { status = '⚠ REGRESSION'; regressions++; }
  else if (cls > 0.1) status = 'known-backlog';
  console.log(`| ${k} | ${cls.toFixed(4)} | ${range} | ${base ?? '—'} | ${status} | ${offender} |`);
}
console.log(`\n${regressions} regression(s) with EPS=${EPS}. (Report-only — exit 0 regardless.)`);
process.exit(0);
