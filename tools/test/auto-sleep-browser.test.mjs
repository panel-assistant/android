import assert from 'node:assert/strict';
import { chromium } from 'playwright-core';
import { browserTest, chrome, deferred, json, root } from './fixtures/browser-harness.mjs';
import { requestBody } from './fixtures/dashboard-screenshot.mjs';
import { autoSleepHistory, configureVisualFixture, fixture, settled, startHarness } from './fixtures/configure-page.mjs';

// Auto-sleep on the Configure page: its prerequisites, its chart and how the card holds still.

browserTest('Auto-sleep requires an assigned Area before OFF can be switched ON', async (t) => {
  let prerequisiteCalls = 0;
  let prerequisiteAssigned = false;
  const firstPrerequisite = deferred();
  const schema = [{
    key: 'auto_sleep', label: 'Auto sleep',
    help: 'Automatically wake the panel when activity is detected and switch the screen off after the learned delay. Manual screen control remains separate.',
    summary: 'Turn the screen off when nobody is around or after inactivity.',
    group: 'Behaviour',
    type: 'BOOL', available: true,
  }];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'false' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') {
      prerequisiteCalls++;
      return prerequisiteAssigned ? json({ eligible: true, phase: 'assigned', area_name: 'Studio' }) : firstPrerequisite.promise;
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });

  const toggle = page.locator('#cfg-auto_sleep [role=switch]');
  const prerequisiteStatus = page.locator('#auto-sleep-prerequisite-status');
  await prerequisiteStatus.waitFor();
  assert.equal(await prerequisiteStatus.textContent(), 'Checking this panel’s Home Assistant Area…');
  assert.equal(await toggle.getAttribute('aria-disabled'), 'true');
  assert.equal(await toggle.getAttribute('tabindex'), '0');
  assert.equal(await toggle.getAttribute('aria-describedby'), 'auto-sleep-prerequisite-status');
  await toggle.press('Enter');
  assert.equal(await toggle.getAttribute('aria-checked'), 'false');
  await toggle.click({ force: true });
  assert.equal(await toggle.getAttribute('aria-checked'), 'false');

  firstPrerequisite.resolve(json({ eligible: false, phase: 'unassigned', area_name: null }));
  await page.getByText('Assign this panel to a Home Assistant Area before enabling Auto sleep.').waitFor();
  assert.equal(await toggle.getAttribute('aria-disabled'), 'true');
  assert.match(await page.locator('#cfg-auto_sleep').innerText(), /Turn the screen off when nobody is around/);
  assert.doesNotMatch(await page.locator('[data-config-group="Auto-sleep"] h2').innerText(), /Android\/app behaviour/);

  prerequisiteAssigned = true;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.getByText('Home Assistant Area: Studio').waitFor();
  assert.equal(await toggle.getAttribute('aria-disabled'), 'false');
  await toggle.click();
  assert.equal(await toggle.getAttribute('aria-checked'), 'true');
  assert.ok(prerequisiteCalls >= 2);
});

browserTest('Touch inactivity can enable Auto-sleep without an Area and sends a fixed seconds delay', async (t) => {
  const calls = [];
  const posts = [];
  const settings = { auto_sleep_source: 'panel', auto_sleep_touch_delay_seconds: '30', auto_sleep: 'false' };
  const schema = [
    { key: 'auto_sleep_source', label: 'Auto-sleep activity source', group: 'Behaviour', type: 'ENUM',
      available: true, options: ['panel', 'home_assistant', 'touch'] },
    { key: 'auto_sleep_touch_delay_seconds', label: 'Touch inactivity delay (seconds)', group: 'Behaviour',
      type: 'INT', available: true, min: 5, max: 86400 },
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
  ];
  const harness = await startHarness(async (path, request) => {
    calls.push(path);
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') {
        const posted = Object.fromEntries(new URLSearchParams(await requestBody(request)));
        posts.push(posted);
        Object.assign(settings, posted);
        return json({ status: 'saved' });
      }
      return json({ settings, ha_expose: {}, ha_auth: {} });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });

  const toggle = page.locator('#cfg-auto_sleep [role=switch]');
  await toggle.waitFor();
  await page.locator('#cfg-auto_sleep_source input[value="touch"]').check();
  assert.equal(await page.locator('#cfg-auto_sleep_source input:checked').inputValue(), 'touch');
  assert.equal(await page.locator('#cfg-auto_sleep_touch_delay_seconds input').inputValue(), '30');
  assert.equal(await toggle.getAttribute('aria-disabled'), 'false');
  await page.getByText('Home Assistant and presence sensors are not required.', { exact: false }).waitFor();
  const toggleSaved = page.waitForResponse((response) => response.request().method() === 'POST'
    && (response.request().postData() || '').includes('auto_sleep=true'));
  await toggle.click();
  assert.equal(await toggle.getAttribute('aria-checked'), 'true');
  // The save's answer re-renders the card; a fill begun before that render ends in the replaced field.
  await toggleSaved;
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => setTimeout(resolve, 0))));
  const delay = page.locator('#cfg-auto_sleep_touch_delay_seconds input');
  await delay.fill('45');
  await delay.press('Tab');
  for (const end = Date.now() + 10_000; posts.length < 3 && Date.now() < end;) await page.waitForTimeout(25);
  assert.equal(await page.locator('#auto-sleep-chart').count(), 0);
  // Each finished gesture sends its own setting, in the order the person made them.
  assert.deepEqual(posts, [{ auto_sleep_source: 'touch' }, { auto_sleep: 'true' }, { auto_sleep_touch_delay_seconds: '45' }]);
  assert.equal(await toggle.getAttribute('aria-checked'), 'true');
  // The card describes the new delay at once, without a reload: the touch source has no status to wait for.
  await page.waitForFunction(() => document.querySelector('#auto-sleep-summary-announcement')?.textContent.includes('45 seconds without touch'));
  // What the panel now holds is what the card describes.
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.querySelector('#auto-sleep-summary-announcement')?.textContent.includes('45 seconds without touch'));
  assert.equal(await page.locator('#cfg-auto_sleep [role=switch]').getAttribute('aria-checked'), 'true');
  assert.equal(calls.includes('/api/v1/auto-sleep/prerequisite'), false);
  assert.equal(calls.includes('/api/v1/auto-sleep'), false);
  assert.equal(calls.includes('/api/v1/auto-sleep/history'), false);
});

browserTest('Auto-sleep Area focus refresh supersedes a slow prerequisite request', async (t) => {
  const firstPrerequisite = deferred();
  let prerequisiteCalls = 0;
  const schema = [{ key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true }];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'false' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') {
      prerequisiteCalls++;
      return prerequisiteCalls === 1
        ? firstPrerequisite.promise
        : json({ eligible: true, phase: 'assigned', area_name: 'Studio' });
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.waitForFunction(() => document.querySelector('#auto-sleep-prerequisite-status')?.textContent.includes('Checking'));
  await page.waitForFunction(() => window.fetch && document.querySelector('#cfg-auto_sleep'));
  for (let attempt = 0; attempt < 20 && prerequisiteCalls < 1; attempt++) await page.waitForTimeout(25);
  assert.equal(prerequisiteCalls, 1);

  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Studio', { exact: true }).waitFor();
  assert.equal(prerequisiteCalls, 2);
  firstPrerequisite.resolve(json({ eligible: false, phase: 'unassigned', area_name: null }));
  await page.waitForTimeout(100);
  assert.equal(await page.locator('#auto-sleep-prerequisite-status').textContent(), 'Home Assistant Area: Studio');
  assert.equal(await page.locator('#cfg-auto_sleep [role=switch]').getAttribute('aria-disabled'), 'false');
});

browserTest('Auto-sleep card stays continuously painted across unrelated startup results', async (t) => {
  const homeDashboards = deferred();
  const brightnessStatus = deferred();
  const brightnessHistory = deferred();
  const sleepStatus = deferred();
  const source = 'sensor.office_illuminance';
  const revision = 'office-source';
  const schema = autoSleepHydrationSchema();
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({
      settings: autoSleepHydrationSettings(source),
      ha_expose: {}, ha_auth: { configured: true, oauth: true },
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/discovery') return json({});
    if (path === '/api/v1/config/home-dashboards') return homeDashboards.promise;
    if (path === '/api/v1/ha/oauth/status') return json({ phase: 'connected', display_name: 'Panel User' });
    if (path === '/api/v1/auto-brightness') return brightnessStatus.promise;
    if (path === '/api/v1/auto-brightness/history') return brightnessHistory.promise;
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') return sleepStatus.promise;
    if (path === '/api/v1/auto-sleep/history') return json(autoSleepHistory({ hours: 24 }));
  }, configureVisualFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 900 } });
  page.setDefaultTimeout(3_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.addStyleTag({ content: ':root{font-size:24px!important}html,body,#cfg-groups{overflow-anchor:none!important}' });
  await page.locator('#auto-sleep-summary-announcement').waitFor();
  assert.equal(await page.locator('#auto-sleep-summary-announcement').textContent(), 'Loading…');
  await page.evaluate(() => {
    const panel = document.querySelector('#auto-sleep-status');
    const summary = document.querySelector('#auto-sleep-summary');
    const chart = document.querySelector('#auto-sleep-chart');
    const first = {
      summaryHeight: summary.getBoundingClientRect().height,
      chartRelativeY: chart.getBoundingClientRect().y - panel.getBoundingClientRect().y,
    };
    window.__autoSleepColdSummaryGeometry = { active: true, frames: 0, min: { ...first }, max: { ...first } };
    const sample = () => {
      const audit = window.__autoSleepColdSummaryGeometry;
      if (!audit.active) return;
      const values = {
        summaryHeight: summary.getBoundingClientRect().height,
        chartRelativeY: chart.getBoundingClientRect().y - panel.getBoundingClientRect().y,
      };
      audit.frames++;
      Object.keys(values).forEach((key) => {
        audit.min[key] = Math.min(audit.min[key], values[key]);
        audit.max[key] = Math.max(audit.max[key], values[key]);
      });
      requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
  });
  sleepStatus.resolve(json({
    enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1,
  }));
  await page.locator('.auto-sleep-lane.source').waitFor();
  await page.waitForTimeout(100);
  const coldSummaryGeometry = await page.evaluate(() => {
    window.__autoSleepColdSummaryGeometry.active = false;
    return window.__autoSleepColdSummaryGeometry;
  });
  assert.ok(coldSummaryGeometry.frames >= 4, `expected cold summary frame samples, got ${coldSummaryGeometry.frames}`);
  Object.keys(coldSummaryGeometry.min).forEach((key) => {
    assert.ok(coldSummaryGeometry.max[key] - coldSummaryGeometry.min[key] <= 0.5,
      `${key} moved during first status load by ${coldSummaryGeometry.max[key] - coldSummaryGeometry.min[key]}px`);
  });
  await page.locator('#cfg-ha-oauth').getByText('Connected as Panel User').waitFor();
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Office', { exact: true }).waitFor();

  const autoSleepCard = await page.locator('[data-config-group="Auto-sleep"]').elementHandle();
  const panel = await page.locator('#auto-sleep-status').elementHandle();
  const chart = await page.locator('#auto-sleep-chart').elementHandle();
  const content = await page.locator('.auto-sleep-chart-content').elementHandle();
  const snapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  const settledSummary = await page.locator('#auto-sleep-summary').textContent();
  const settledArea = await page.locator('#auto-sleep-prerequisite-status').textContent();
  const originalDashboardCard = await page.locator('[data-config-group="Dashboard"]').elementHandle();
  const originalDisplayCard = await page.locator('[data-config-group="Display"]').elementHandle();
  assert.deepEqual(await page.locator('#cfg-groups > [data-config-group]').evaluateAll((cards) =>
    cards.map((card) => card.getAttribute('data-config-group'))), [
    'Identity', 'Home Assistant connection', 'Dashboard', 'Built-in renderer', 'Behaviour', 'Auto-sleep', 'Display',
    'System', 'Sensors', 'Diagnostics', 'Logging',
  ]);
  await page.locator('#auto-sleep-status').evaluate((panelNode) => panelNode.scrollIntoView({ block: 'center' }));
  await page.waitForTimeout(100);
  assert.equal(await page.locator('#auto-sleep-status').evaluate((panelNode) => {
    const rect = panelNode.getBoundingClientRect();
    return rect.bottom > 0 && rect.top < window.innerHeight;
  }), true);
  assert.equal(await page.locator('#auto-sleep-chart').evaluate((chartNode) => {
    const rect = chartNode.getBoundingClientRect();
    return rect.bottom > 0 && rect.top < window.innerHeight;
  }), true);
  await page.evaluate(() => {
    const card = document.querySelector('[data-config-group="Auto-sleep"]');
    const panelNode = document.querySelector('#auto-sleep-status');
    const chartNode = document.querySelector('#auto-sleep-chart');
    const overlay = document.querySelector('.auto-sleep-loading-overlay');
    const initialRect = panelNode.getBoundingClientRect();
    const initialCardRect = card.getBoundingClientRect();
    const initialChartRect = chartNode.getBoundingClientRect();
    const initialScrollY = window.scrollY;
    window.__autoSleepPaintAudit = {
      cardRemoved: 0, panelParkedHidden: 0, overlayShown: 0, frames: 0,
      replacedFrames: 0, disconnectedFrames: 0, hiddenFrames: 0, invisibleFrames: 0,
      cardViewportMissFrames: 0, panelViewportMissFrames: 0, chartViewportMissFrames: 0,
      maxXDelta: 0, maxYDelta: 0, maxWidthDelta: 0, maxHeightDelta: 0,
      maxCardYDelta: 0, maxChartYDelta: 0, maxChartRelativeYDelta: 0,
      minScrollY: initialScrollY, maxScrollY: initialScrollY,
      minDocumentY: initialRect.y + initialScrollY, maxDocumentY: initialRect.y + initialScrollY,
      scrollCorrectionCalls: 0,
    };
    const nativeScrollTo = window.scrollTo.bind(window);
    window.scrollTo = (...args) => {
      window.__autoSleepPaintAudit.scrollCorrectionCalls++;
      return nativeScrollTo(...args);
    };
    window.__autoSleepPaintSampling = true;
    const sample = () => {
      if (!window.__autoSleepPaintSampling) return;
      const audit = window.__autoSleepPaintAudit;
      const current = document.querySelector('#auto-sleep-status');
      audit.frames++;
      if (current !== panelNode) audit.replacedFrames++;
      if (!panelNode.isConnected) audit.disconnectedFrames++;
      if (panelNode.closest('[hidden]')) audit.hiddenFrames++;
      const style = getComputedStyle(panelNode);
      if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0) audit.invisibleFrames++;
      const rect = panelNode.getBoundingClientRect();
      const cardRect = card.getBoundingClientRect();
      const chartRect = chartNode.getBoundingClientRect();
      if (cardRect.bottom <= 0 || cardRect.top >= window.innerHeight) audit.cardViewportMissFrames++;
      if (rect.bottom <= 0 || rect.top >= window.innerHeight) audit.panelViewportMissFrames++;
      if (chartRect.bottom <= 0 || chartRect.top >= window.innerHeight) audit.chartViewportMissFrames++;
      audit.maxXDelta = Math.max(audit.maxXDelta, Math.abs(rect.x - initialRect.x));
      audit.maxYDelta = Math.max(audit.maxYDelta, Math.abs(rect.y - initialRect.y));
      audit.maxWidthDelta = Math.max(audit.maxWidthDelta, Math.abs(rect.width - initialRect.width));
      audit.maxHeightDelta = Math.max(audit.maxHeightDelta, Math.abs(rect.height - initialRect.height));
      audit.maxCardYDelta = Math.max(audit.maxCardYDelta, Math.abs(cardRect.y - initialCardRect.y));
      audit.maxChartYDelta = Math.max(audit.maxChartYDelta, Math.abs(chartRect.y - initialChartRect.y));
      audit.maxChartRelativeYDelta = Math.max(audit.maxChartRelativeYDelta,
        Math.abs((chartRect.y - rect.y) - (initialChartRect.y - initialRect.y)));
      audit.minScrollY = Math.min(audit.minScrollY, window.scrollY);
      audit.maxScrollY = Math.max(audit.maxScrollY, window.scrollY);
      audit.minDocumentY = Math.min(audit.minDocumentY, rect.y + window.scrollY);
      audit.maxDocumentY = Math.max(audit.maxDocumentY, rect.y + window.scrollY);
      requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
    new MutationObserver((records) => {
      records.forEach((record) => {
        Array.from(record.removedNodes).forEach((node) => {
          if (node === card || node.nodeType === 1 && node.contains(card)) window.__autoSleepPaintAudit.cardRemoved++;
        });
        Array.from(record.addedNodes).forEach((node) => {
          if (node === panelNode && record.target.nodeType === 1 && record.target.closest('[hidden]')) {
            window.__autoSleepPaintAudit.panelParkedHidden++;
          }
        });
        if (record.type === 'attributes' && record.target === overlay && record.oldValue !== null) {
          window.__autoSleepPaintAudit.overlayShown++;
        }
      });
    }).observe(document.body, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['hidden'], attributeOldValue: true,
    });
  });

  homeDashboards.resolve(json({
    queried: true, items: [{ path: 'overview', title: 'Overview', group: 'dashboard' }],
    default: { explicit: true, path: 'overview' },
  }));
  await page.waitForFunction(() => Array.from(document.querySelectorAll('#cfg-home_dashboard option')).some((option) => option.textContent.includes('Overview')));
  await page.waitForTimeout(150);
  brightnessStatus.resolve(json({
    available: true, state: 'enabled', sourceAvailable: true, entityId: source, latestLux: 42, sourceRevision: revision,
  }));
  brightnessHistory.resolve(json({ points: [], sourceRevision: revision, latestEpochMinute: Math.floor(Date.now() / 60000) }));
  await page.locator('#auto-brightness-learning').getByText(`enabled · Source: ${source}`).waitFor();
  await page.waitForFunction(() => document.querySelector('#cfg-groups').classList.contains('config-viewport-anchored'));
  await page.evaluate(() => document.dispatchEvent(new WheelEvent('wheel', { bubbles: true })));
  await page.waitForTimeout(1600);
  await page.evaluate(() => { window.__autoSleepPaintSampling = false; });

  assert.equal(await autoSleepCard.evaluate((node) => node.isConnected && node === document.querySelector('[data-config-group="Auto-sleep"]')), true);
  assert.equal(await panel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await chart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await content.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-content')), true);
  assert.equal(await snapshot.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-snapshot')), true);
  assert.equal(await originalDashboardCard.evaluate((node) => node.isConnected), false,
    'delayed Home Dashboard result did not exercise an upstream card render');
  assert.equal(await originalDisplayCard.evaluate((node) => node.isConnected), false,
    'delayed adaptive-brightness result did not exercise an upstream card render');
  assert.equal(await page.locator('#cfg-groups').evaluate((root) => root.classList.contains('config-viewport-anchored')), false);
  assert.equal(await page.locator('[data-config-group="Dashboard"]').evaluate((card) => getComputedStyle(card).contentVisibility), 'auto');
  assert.equal(await page.locator('#auto-sleep-summary').textContent(), settledSummary);
  assert.equal(await page.locator('#auto-sleep-prerequisite-status').textContent(), settledArea);
  assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
  const paintAudit = await page.evaluate(() => window.__autoSleepPaintAudit);
  assert.ok(paintAudit.frames >= 10, `expected continuous frame samples, got ${paintAudit.frames}`);
  assert.deepEqual({
    cardRemoved: paintAudit.cardRemoved,
    panelParkedHidden: paintAudit.panelParkedHidden,
    overlayShown: paintAudit.overlayShown,
    replacedFrames: paintAudit.replacedFrames,
    disconnectedFrames: paintAudit.disconnectedFrames,
    hiddenFrames: paintAudit.hiddenFrames,
    invisibleFrames: paintAudit.invisibleFrames,
    cardViewportMissFrames: paintAudit.cardViewportMissFrames,
    panelViewportMissFrames: paintAudit.panelViewportMissFrames,
    chartViewportMissFrames: paintAudit.chartViewportMissFrames,
  }, {
    cardRemoved: 0,
    panelParkedHidden: 0,
    overlayShown: 0,
    replacedFrames: 0,
    disconnectedFrames: 0,
    hiddenFrames: 0,
    invisibleFrames: 0,
    cardViewportMissFrames: 0,
    panelViewportMissFrames: 0,
    chartViewportMissFrames: 0,
  });
  assert.ok(paintAudit.maxXDelta <= 0.5, `panel x moved by ${paintAudit.maxXDelta}px`);
  assert.ok(paintAudit.maxYDelta <= 0.5,
    `panel y moved by ${paintAudit.maxYDelta}px: ${JSON.stringify(paintAudit)}`);
  assert.ok(paintAudit.maxWidthDelta <= 0.5, `panel width changed by ${paintAudit.maxWidthDelta}px`);
  assert.ok(paintAudit.maxHeightDelta <= 0.5, `panel height changed by ${paintAudit.maxHeightDelta}px`);
  assert.ok(paintAudit.maxCardYDelta <= 0.5, `Auto-sleep card y moved by ${paintAudit.maxCardYDelta}px`);
  assert.ok(paintAudit.maxChartYDelta <= 0.5, `chart y moved by ${paintAudit.maxChartYDelta}px`);
  assert.ok(paintAudit.maxChartRelativeYDelta <= 0.5,
    `chart moved within the panel by ${paintAudit.maxChartRelativeYDelta}px`);
  assert.ok(paintAudit.scrollCorrectionCalls >= 1,
    `expected an app-owned viewport correction, got ${paintAudit.scrollCorrectionCalls}`);
  assert.ok(paintAudit.scrollCorrectionCalls <= 4,
    `viewport correction did not converge promptly (${paintAudit.scrollCorrectionCalls} scroll calls)`);
  assert.ok(paintAudit.maxDocumentY - paintAudit.minDocumentY >= 10,
    `fixture did not exercise upstream layout movement (${paintAudit.maxDocumentY - paintAudit.minDocumentY}px)`);
  assert.ok(paintAudit.maxScrollY - paintAudit.minScrollY >= 10,
    `viewport was not compensated for upstream movement (${paintAudit.maxScrollY - paintAudit.minScrollY}px)`);
});

browserTest('Auto-sleep hydration does not scroll toward an off-screen Auto-sleep card', async (t) => {
  const homeDashboards = deferred();
  const source = 'sensor.office_illuminance';
  const revision = 'office-source';
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(autoSleepHydrationSchema());
    if (path === '/api/v1/config') return json({
      settings: autoSleepHydrationSettings(source), ha_expose: {}, ha_auth: { configured: true, oauth: true },
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/discovery') return json({});
    if (path === '/api/v1/config/home-dashboards') return homeDashboards.promise;
    if (path === '/api/v1/ha/oauth/status') return json({ phase: 'connected', display_name: 'Panel User' });
    if (path === '/api/v1/auto-brightness') return json({
      available: true, state: 'enabled', sourceAvailable: true, entityId: source, latestLux: 42, sourceRevision: revision,
    });
    if (path === '/api/v1/auto-brightness/history') {
      return json({ points: [], sourceRevision: revision, latestEpochMinute: Math.floor(Date.now() / 60000) });
    }
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') return json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 });
    if (path === '/api/v1/auto-sleep/history') return json(autoSleepHistory({ hours: 24 }));
  }, configureVisualFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 900 } });
  page.setDefaultTimeout(3_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('.auto-sleep-lane.source').waitFor();
  await page.locator('#auto-brightness-learning').getByText(`enabled · Source: ${source}`).waitFor();
  assert.equal(await page.evaluate(() => window.scrollY), 0);
  assert.equal(await page.locator('#auto-sleep-status').evaluate((panelNode) =>
    panelNode.getBoundingClientRect().top >= window.innerHeight), true);

  const beforeScrollY = await page.evaluate(() => window.scrollY);
  await page.evaluate(() => {
    const panel = document.querySelector('#auto-sleep-status');
    window.__offscreenAutoSleepAudit = {
      active: true, frames: 0, minScrollY: window.scrollY, maxScrollY: window.scrollY,
      onscreenFrames: 0, exactLayoutFrames: 0, originalPanel: panel,
    };
    const sample = () => {
      const audit = window.__offscreenAutoSleepAudit;
      if (!audit.active) return;
      const rect = panel.getBoundingClientRect();
      audit.frames++;
      audit.minScrollY = Math.min(audit.minScrollY, window.scrollY);
      audit.maxScrollY = Math.max(audit.maxScrollY, window.scrollY);
      if (rect.bottom > 0 && rect.top < window.innerHeight) audit.onscreenFrames++;
      if (document.querySelector('#cfg-groups').classList.contains('config-viewport-anchored')) audit.exactLayoutFrames++;
      requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
  });
  homeDashboards.resolve(json({
    queried: true, items: [{ path: 'overview', title: 'Overview', group: 'dashboard' }],
    default: { explicit: true, path: 'overview' },
  }));
  await page.waitForFunction(() => Array.from(document.querySelectorAll('#cfg-home_dashboard option'))
    .some((option) => option.textContent.includes('Overview')));
  await page.waitForTimeout(1600);
  const offscreenAudit = await page.evaluate(() => {
    window.__offscreenAutoSleepAudit.active = false;
    const audit = window.__offscreenAutoSleepAudit;
    return { ...audit, samePanel: audit.originalPanel === document.querySelector('#auto-sleep-status') };
  });
  assert.ok(offscreenAudit.frames >= 10);
  assert.equal(offscreenAudit.samePanel, true);
  assert.equal(offscreenAudit.onscreenFrames, 0);
  assert.equal(offscreenAudit.exactLayoutFrames, 0);
  assert.equal(offscreenAudit.minScrollY, beforeScrollY);
  assert.equal(offscreenAudit.maxScrollY, beforeScrollY);
  assert.equal(await page.evaluate(() => window.scrollY), beforeScrollY);
  assert.equal(await page.locator('#cfg-groups').evaluate((root) => root.classList.contains('config-viewport-anchored')), false);
  assert.equal(await page.locator('#auto-sleep-status').evaluate((panelNode) =>
    panelNode.getBoundingClientRect().top >= window.innerHeight), true);
});

for (const [detail, message] of [
  ['no_device_source', 'This Area has no motion, occupancy or presence binary sensor from a device integration.'],
  ['insufficient_history', 'This Area’s activity sensors have too little usable history or are unavailable.'],
  ['unknown_source_reason', 'No credible device-backed activity source is available in this Area.'],
]) {
  browserTest(`Auto-sleep explains ${detail} in the existing red history line`, async (t) => {
    let historyCalls = 0;
    const harness = await startHarness((path) => {
      if (path === '/api/v1/config/schema') return json([
        { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
      ]);
      if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true' }, ha_expose: {}, ha_auth: { configured: true } });
      if (path === '/api/v1/apps') return json({ apps: [] });
      if (path === '/api/v1/radio' || path === '/api/v1/proximity') return json({ present: false });
      if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
      if (path === '/api/v1/auto-sleep') return json({
        enabled: true, available: false, phase: 'no_credible_sources', reason: 'no_credible_sources',
        detail, area_name: 'Office', source_count: 0, discovered_source_count: 0,
      });
      if (path === '/api/v1/auto-sleep/history') { historyCalls++; return json({ available: false }); }
      return json({});
    });
    const browser = await chromium.launch({ executablePath: chrome, headless: true });
    const page = await browser.newPage({ viewport: { width: 480, height: 800 } });
    page.setDefaultTimeout(2_000);
    t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
    await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
    const line = page.locator('#auto-sleep-history-message');
    await page.waitForFunction(() => document.querySelector('#auto-sleep-history-message')?.classList.contains('error'));
    assert.equal(await line.textContent(), message);
    assert.equal(await line.evaluate((node) => getComputedStyle(node).color), 'rgb(211, 75, 66)');
    assert.equal(await page.locator('#auto-sleep-status').count(), 1);
    assert.equal(await page.locator('#auto-sleep-chart').count(), 1);
    assert.equal(await page.locator('.auto-sleep-lane.source').count(), 0);
    assert.equal(historyCalls, 0);
  });
}

browserTest('Auto-sleep blank-Area discovery failure is terminal without cold-chart reflow', async (t) => {
  let statusCalls = 0;
  let historyCalls = 0;
  const terminal = {
    enabled: true, available: false, phase: 'discovery_failed', reason: 'discovery_failed',
    detail: 'registry_projection', area_name: '', source_count: 0, discovered_source_count: 0,
  };
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness(async (path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({
      settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true },
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/home-dashboards') return json({ items: [], default: {} });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      // If a faulty readiness loop starts, retain its Loading state long enough for a frame sample.
      if (statusCalls > 2) await new Promise((resolve) => setTimeout(resolve, 300));
      return json(terminal);
    }
    if (path === '/api/v1/auto-sleep/history') { historyCalls++; return json({ available: false, detail: 'runtime_unavailable' }); }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 800 } });
  page.setDefaultTimeout(3_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Office', { exact: true }).waitFor();
  await page.getByText('Auto-sleep source discovery failed. Check the Home Assistant connection.').waitFor();
  assert.equal(statusCalls, 2);

  const panel = await page.locator('#auto-sleep-status').elementHandle();
  const chart = await page.locator('#auto-sleep-chart').elementHandle();
  const content = await page.locator('.auto-sleep-chart-content').elementHandle();
  const snapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  await page.evaluate(() => {
    window.__autoSleepColdSamples = [];
    window.__autoSleepSummaryMutations = 0;
    new MutationObserver(() => { window.__autoSleepSummaryMutations++; })
      .observe(document.querySelector('#auto-sleep-summary'), { childList: true, characterData: true, subtree: true });
    function sample() {
      const activity = document.querySelector('#auto-sleep-status').getBoundingClientRect();
      const chartBox = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
      const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
      window.__autoSleepColdSamples.push({ panelHeight: activity.height, chartHeight: chartBox.height, systemY: system.y });
      window.__autoSleepColdFrame = requestAnimationFrame(sample);
    }
    window.__autoSleepColdFrame = requestAnimationFrame(sample);
  });
  await page.waitForTimeout(1_600);
  const observed = await page.evaluate(() => {
    cancelAnimationFrame(window.__autoSleepColdFrame);
    const ranges = {};
    for (const key of ['panelHeight', 'chartHeight', 'systemY']) {
      const values = window.__autoSleepColdSamples.map((sample) => sample[key]);
      ranges[key] = Math.max(...values) - Math.min(...values);
    }
    return { ranges, summaryMutations: window.__autoSleepSummaryMutations };
  });

  assert.equal(statusCalls, 2);
  assert.equal(historyCalls, 0);
  assert.equal(observed.summaryMutations, 0);
  assert.ok(Object.values(observed.ranges).every((range) => range <= 0.5), JSON.stringify(observed.ranges));
  assert.equal(await panel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await chart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await content.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-content')), true);
  assert.equal(await snapshot.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-snapshot')), true);
  assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
  assert.equal(await page.locator('.auto-sleep-empty').textContent(), 'No replay data');
});

browserTest('Auto-sleep blank-Area transport failure recovers without retry reflow', async (t) => {
  let statusCalls = 0;
  let historyCalls = 0;
  const recoveredStatus = deferred();
  const transportFailure = {
    enabled: true, available: false, phase: 'discovery_failed', reason: 'discovery_failed',
    detail: 'registry_transport', area_name: '', source_count: 0, discovered_source_count: 0,
  };
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({
      settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true },
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/home-dashboards') return json({ items: [], default: {} });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      return statusCalls <= 2 ? json(transportFailure) : recoveredStatus.promise;
    }
    if (path === '/api/v1/auto-sleep/history') { historyCalls++; return json(autoSleepHistory({ hours: 24 })); }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 800 } });
  page.setDefaultTimeout(7_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Office', { exact: true }).waitFor();
  for (let attempt = 0; attempt < 40 && statusCalls < 2; attempt++) await page.waitForTimeout(25);
  assert.equal(statusCalls, 2);
  const settledSummary = await page.locator('#auto-sleep-summary').textContent();

  await page.evaluate(() => {
    window.__autoSleepTransportSamples = [];
    window.__autoSleepTransportSummaryMutations = 0;
    new MutationObserver(() => { window.__autoSleepTransportSummaryMutations++; })
      .observe(document.querySelector('#auto-sleep-summary'), { childList: true, characterData: true, subtree: true });
    function sample() {
      const activity = document.querySelector('#auto-sleep-status').getBoundingClientRect();
      const chartBox = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
      const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
      window.__autoSleepTransportSamples.push({ panelHeight: activity.height, chartHeight: chartBox.height, systemY: system.y });
      window.__autoSleepTransportFrame = requestAnimationFrame(sample);
    }
    window.__autoSleepTransportFrame = requestAnimationFrame(sample);
  });
  for (let attempt = 0; attempt < 120 && statusCalls < 3; attempt++) await page.waitForTimeout(50);
  assert.equal(statusCalls, 3);
  const observed = await page.evaluate(() => {
    cancelAnimationFrame(window.__autoSleepTransportFrame);
    const ranges = {};
    for (const key of ['panelHeight', 'chartHeight', 'systemY']) {
      const values = window.__autoSleepTransportSamples.map((sample) => sample[key]);
      ranges[key] = Math.max(...values) - Math.min(...values);
    }
    return { ranges, summaryMutations: window.__autoSleepTransportSummaryMutations };
  });
  assert.equal(await page.locator('#auto-sleep-summary').textContent(), settledSummary);
  assert.equal(observed.summaryMutations, 0);
  assert.ok(Object.values(observed.ranges).every((range) => range <= 0.5), JSON.stringify(observed.ranges));
  assert.equal(historyCalls, 0);

  recoveredStatus.resolve(json({
    enabled: true, available: true, phase: 'live', reason: 'clear', area_name: 'Office', source_count: 1,
  }));
  await page.locator('.auto-sleep-lane.source').waitFor();
  assert.equal(statusCalls, 3);
  assert.equal(historyCalls, 1);
  assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
});

browserTest('Auto-sleep case-equivalent Area settles despite a late stale retry', async (t) => {
  let statusCalls = 0;
  let historyCalls = 0;
  let assignedArea = 'Office';
  const staleRetry = deferred();
  const transportFailure = {
    enabled: true, available: false, phase: 'discovery_failed', reason: 'discovery_failed',
    detail: 'registry_transport', area_name: '', source_count: 0, discovered_source_count: 0,
  };
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({
      settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true },
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/home-dashboards') return json({ items: [], default: {} });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: assignedArea });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      if (statusCalls <= 2) return json(transportFailure);
      if (statusCalls === 3) return staleRetry.promise;
      return json({ enabled: true, available: true, phase: 'live', area_name: ' studio ', source_count: 1 });
    }
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      return json(autoSleepHistory({ hours: 24, areaName: 'STUDIO' }));
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 800 } });
  page.setDefaultTimeout(7_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Office', { exact: true }).waitFor();
  for (let attempt = 0; attempt < 140 && statusCalls < 3; attempt++) await page.waitForTimeout(50);
  assert.equal(statusCalls, 3);

  assignedArea = 'Studio';
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Studio', { exact: true }).waitFor();
  await page.locator('.auto-sleep-lane.source').waitFor();
  assert.equal(statusCalls, 4);
  assert.equal(historyCalls, 1);
  const settledSummary = await page.locator('#auto-sleep-summary').textContent();

  staleRetry.resolve(json({
    enabled: true, available: false, phase: 'discovery_failed', reason: 'discovery_failed',
    detail: 'registry_projection', area_name: 'Garage', source_count: 0,
  }));
  await page.waitForTimeout(300);
  assert.equal(statusCalls, 4);
  assert.equal(historyCalls, 1);
  assert.equal(await page.locator('#auto-sleep-summary').textContent(), settledSummary);
  assert.equal(await page.locator('.auto-sleep-lane.source').count(), 1);
  assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
});

browserTest('Auto-sleep retains chart geometry and swaps a refreshed source snapshot atomically', async (t) => {
  const sourcePost = deferred();
  const refreshedHistory = deferred();
  const homeDashboards = deferred();
  let historyCalls = 0;
  const requestedHistoryHours = [];
  let sourcePosts = 0;
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/home-dashboards') return homeDashboards.promise;
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') return json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 });
    if (path === '/api/v1/auto-sleep/source' && request.method === 'POST') { sourcePosts++; return sourcePost.promise; }
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      requestedHistoryHours.push(new URL(request.url, 'http://panel.test').searchParams.get('hours'));
      if (historyCalls === 1) return json(autoSleepHistory({ hours: 24, laneCount: 20 }));
      if (historyCalls === 2) return refreshedHistory.promise;
      return json({ detail: 'terminal' }, 409);
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const sourceRow = page.locator('.auto-sleep-lane.source').first();
  await sourceRow.waitFor();
  assert.equal(requestedHistoryHours[0], '24');
  assert.equal(await page.getByRole('button', { name: '24h' }).getAttribute('aria-pressed'), 'true');

  const label = sourceRow.locator('.auto-sleep-label');
  const track = sourceRow.locator('.auto-sleep-track');
  assert.equal(await label.getAttribute('title'), 'Office ceiling motion');
  assert.equal(await track.getAttribute('title'), 'Click to suppress this source');
  assert.equal(await sourceRow.getAttribute('title'), null);
  assert.equal(await sourceRow.locator('.auto-sleep-interval').getAttribute('title'), null);
  const oldPanel = await page.locator('#auto-sleep-status').elementHandle();
  const oldAutoSleepCard = await page.locator('[data-config-group="Auto-sleep"]').elementHandle();
  const oldChart = await page.locator('#auto-sleep-chart').elementHandle();
  const oldContent = await page.locator('.auto-sleep-chart-content').elementHandle();
  const oldSnapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  const oldScroll = await page.locator('.auto-sleep-source-scroll').elementHandle();
  await sourceRow.focus();
  await oldScroll.evaluate((node) => { node.scrollTop = 75; });
  const beforePageY = await page.evaluate(() => {
    document.body.style.minHeight = '2000px';
    window.scrollTo(0, 400);
    return window.pageYOffset;
  });
  const before = await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  });
  await page.evaluate(() => {
    const card = document.querySelector('[data-config-group="Auto-sleep"]');
    const panel = document.querySelector('#auto-sleep-status');
    const overlay = document.querySelector('.auto-sleep-loading-overlay');
    window.__autoSleepVisualMutations = { cardRemoved: 0, hiddenAncestor: 0, overlayShown: 0 };
    new MutationObserver((records) => {
      records.forEach((record) => {
        Array.from(record.removedNodes).forEach((node) => {
          if (node === card || node.nodeType === 1 && node.contains(card)) window.__autoSleepVisualMutations.cardRemoved++;
        });
        Array.from(record.addedNodes).forEach((node) => {
          if (node === panel && record.target.nodeType === 1 && record.target.closest('[hidden]')) {
            window.__autoSleepVisualMutations.hiddenAncestor++;
          }
        });
        if (record.type === 'attributes' && record.target === overlay && record.oldValue !== null) {
          window.__autoSleepVisualMutations.overlayShown++;
        }
      });
    }).observe(document.body, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['hidden'], attributeOldValue: true,
    });
  });

  await sourceRow.press('Enter');
  const overlay = page.locator('.auto-sleep-loading-overlay');
  await overlay.waitFor({ state: 'hidden' });
  assert.equal(await page.locator('.auto-sleep-lane.source').count(), 20);
  assert.equal(await sourceRow.getAttribute('aria-disabled'), 'true');
  assert.equal(await page.locator('#auto-sleep-history-message').textContent(), 'Refreshing activity history…');
  assert.equal(await page.getByRole('button', { name: '24h' }).getAttribute('aria-disabled'), 'true');
  assert.equal(await page.getByRole('button', { name: '24h' }).evaluate((button) => button.disabled), false);
  assert.equal(await oldSnapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await oldScroll.evaluate((node) => node.isConnected && node.scrollTop), 75);
  assert.equal(await sourceRow.evaluate((node) => document.activeElement === node), true);
  assert.equal(await page.evaluate(() => window.pageYOffset), beforePageY);
  const duringPost = await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  });
  assert.deepEqual(duringPost, before);

  // An unrelated asynchronous Configure probe may rebuild other cards while the source update is
  // pending. The settled Auto-sleep card must stay rendered and unchanged throughout that work.
  homeDashboards.resolve(json({ queried: true, items: [{ path: 'overview', title: 'Overview', group: 'dashboard' }], default: {} }));
  await page.waitForTimeout(50);
  assert.equal(await oldAutoSleepCard.evaluate((node) => node.isConnected && node === document.querySelector('[data-config-group="Auto-sleep"]')), true);
  assert.equal(await oldPanel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await oldChart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await oldContent.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-content')), true);
  assert.equal(await oldSnapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await oldScroll.evaluate((node) => node.isConnected && node.scrollTop), 75);
  assert.equal(await sourceRow.evaluate((node) => document.activeElement === node), true);
  assert.equal(await page.evaluate(() => window.pageYOffset), beforePageY);
  assert.deepEqual(await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  }), before);
  assert.deepEqual(await page.evaluate(() => window.__autoSleepVisualMutations), {
    cardRemoved: 0,
    hiddenAncestor: 0,
    overlayShown: 0,
  });

  sourcePost.resolve(json({ updated: true }));
  for (let attempt = 0; attempt < 20 && historyCalls < 2; attempt++) await page.waitForTimeout(25);
  assert.equal(historyCalls, 2);
  await sourceRow.press('Enter');
  await page.waitForTimeout(50);
  assert.equal(sourcePosts, 1);
  assert.equal(await sourceRow.getAttribute('aria-disabled'), 'true');
  assert.equal(await page.locator('.auto-sleep-lane.source').count(), 20);
  assert.equal(await oldSnapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await oldScroll.evaluate((node) => node.isConnected && node.scrollTop), 75);
  assert.equal(await sourceRow.evaluate((node) => document.activeElement === node), true);
  assert.deepEqual(await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  }), before);

  refreshedHistory.resolve(json(autoSleepHistory({ included: false, hours: 24, laneCount: 20 })));
  await overlay.waitFor({ state: 'hidden' });
  await page.getByText('Office ceiling motion · Suppressed').waitFor();
  assert.equal(await page.locator('#auto-sleep-history-message').textContent(), '');
  assert.deepEqual(await page.evaluate(() => window.__autoSleepVisualMutations), {
    cardRemoved: 0,
    hiddenAncestor: 0,
    overlayShown: 0,
  });
  assert.equal(await oldSnapshot.evaluate((node) => node.isConnected), false);
  assert.equal(await page.locator('.auto-sleep-lane.source').first().getAttribute('aria-disabled'), 'false');
  assert.equal(await page.locator('.auto-sleep-track').nth(1).getAttribute('title'), 'Click to include this source');

  await page.getByRole('button', { name: '6h' }).click();
  await page.getByText('History request failed (HTTP not_commandable).').waitFor();
  assert.equal(await page.locator('.auto-sleep-lane.source').count(), 20);
  assert.equal(requestedHistoryHours.at(-1), '6');
  assert.equal(await page.getByRole('button', { name: '24h' }).getAttribute('aria-pressed'), 'true');
});

browserTest('Auto-sleep status changes cannot move a settled chart', async (t) => {
  let statusCalls = 0;
  let historyCalls = 0;
  const replacementHistory = deferred();
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/discovery') return json({});
    if (path === '/api/v1/config/home-dashboards') return json({ queried: true, items: [], default: {} });
    if (path === '/api/v1/ha/oauth/status') return json({ phase: 'connected', display_name: 'Panel User' });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      return json({
        enabled: true, available: true, phase: 'live', area_name: 'Office', learned_lease_ms: 1_080_000,
        reason: statusCalls === 1 ? 'source_active' : 'all_sources_unavailable',
        source_count: statusCalls === 1 ? 1 : 64,
        manual_suppression: statusCalls === 1,
      });
    }
    if (path === '/api/v1/auto-sleep/source' && request.method === 'POST') return json({ updated: true });
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      return historyCalls === 1 ? json(autoSleepHistory({ hours: 24 })) : replacementHistory.promise;
    }
  }, configureVisualFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const sourceRow = page.locator('.auto-sleep-lane.source').first();
  await sourceRow.waitFor();
  await page.addStyleTag({ content: ':root{font-size:24px!important}' });
  await page.waitForTimeout(250);

  const panel = await page.locator('#auto-sleep-status').elementHandle();
  const chart = await page.locator('#auto-sleep-chart').elementHandle();
  const snapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  assert.equal(await page.locator('#auto-sleep-summary-announcement').textContent(),
    'Home Assistant Area: Office · Phase: Live · Reason: Source active · Learned delay: 18 min · Sources: 1 · Manual screen override: active');
  assert.deepEqual(await page.locator('.auto-sleep-summary-line').allTextContents(), [
    'Home Assistant Area: Office',
    'Phase: Live',
    'Reason: Source active',
    'Delay: 18 min · Sources: 1',
    'Manual override: active',
  ]);
  for (const index of [0, 1, 2, 3, 4]) {
    assert.equal(await page.locator('.auto-sleep-summary-line').nth(index).evaluate((line) => line.scrollWidth <= line.clientWidth + 1), true);
  }
  await page.evaluate(() => {
    const panelNode = document.querySelector('#auto-sleep-status');
    const summary = document.querySelector('#auto-sleep-summary');
    const chartNode = document.querySelector('#auto-sleep-chart');
    const system = document.querySelector('[data-config-group="System"]');
    const first = {
      summaryHeight: summary.getBoundingClientRect().height,
      chartY: chartNode.getBoundingClientRect().y,
      chartRelativeY: chartNode.getBoundingClientRect().y - panelNode.getBoundingClientRect().y,
      panelHeight: panelNode.getBoundingClientRect().height,
      systemY: system.getBoundingClientRect().y,
    };
    window.__autoSleepSummaryGeometry = { frames: 0, min: { ...first }, max: { ...first }, active: true };
    const sample = () => {
      const audit = window.__autoSleepSummaryGeometry;
      if (!audit.active) return;
      const panelBox = panelNode.getBoundingClientRect();
      const chartBox = chartNode.getBoundingClientRect();
      const values = {
        summaryHeight: summary.getBoundingClientRect().height,
        chartY: chartBox.y,
        chartRelativeY: chartBox.y - panelBox.y,
        panelHeight: panelBox.height,
        systemY: system.getBoundingClientRect().y,
      };
      audit.frames++;
      Object.keys(values).forEach((key) => {
        audit.min[key] = Math.min(audit.min[key], values[key]);
        audit.max[key] = Math.max(audit.max[key], values[key]);
      });
      requestAnimationFrame(sample);
    };
    requestAnimationFrame(sample);
  });

  await sourceRow.press('Enter');
  await page.waitForFunction(() => window.__autoSleepSummaryGeometry.frames >= 2 && document.querySelector('#auto-sleep-summary').textContent.includes('All sources unavailable'));
  assert.equal(historyCalls, 2);
  await page.waitForTimeout(100);
  await page.evaluate(() => { window.__autoSleepSummaryGeometry.active = false; });

  assert.equal(await page.locator('#auto-sleep-summary-announcement').textContent(),
    'Home Assistant Area: Office · Phase: Live · Reason: All sources unavailable · Learned delay: 18 min · Sources: 64 · Manual screen override: inactive');
  assert.deepEqual(await page.locator('.auto-sleep-summary-line').allTextContents(), [
    'Home Assistant Area: Office',
    'Phase: Live',
    'Reason: All sources unavailable',
    'Delay: 18 min · Sources: 64',
    'Manual override: inactive',
  ]);
  for (const index of [0, 1, 2, 3, 4]) {
    assert.equal(await page.locator('.auto-sleep-summary-line').nth(index).evaluate((line) => line.scrollWidth <= line.clientWidth + 1), true);
  }
  assert.equal(await panel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await chart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await snapshot.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-snapshot')), true);
  assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
  const geometry = await page.evaluate(() => window.__autoSleepSummaryGeometry);
  assert.ok(geometry.frames >= 4, `expected continuous frame samples, got ${geometry.frames}`);
  Object.keys(geometry.min).forEach((key) => {
    assert.ok(geometry.max[key] - geometry.min[key] <= 0.5,
      `${key} moved by ${geometry.max[key] - geometry.min[key]}px`);
  });
  await page.setViewportSize({ width: 320, height: 800 });
  await page.waitForTimeout(100);
  for (const index of [0, 1, 2, 3, 4]) {
    assert.equal(await page.locator('.auto-sleep-summary-line').nth(index).evaluate((line) => line.scrollWidth <= line.clientWidth + 1), true);
  }
});

browserTest('Auto-sleep restores its cached chart synchronously when disabled and re-enabled', async (t) => {
  let enabled = true;
  let statusCalls = 0;
  const reenabledStatus = deferred();
  const reenabledHistory = deferred();
  let historyCalls = 0;
  const schema = [{ key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true }];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') { enabled = !enabled; return json({}); }
      return json({ settings: { auto_sleep: String(enabled) }, ha_expose: {}, ha_auth: { configured: true } });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      return statusCalls === 1 ? json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 }) : reenabledStatus.promise;
    }
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      return historyCalls === 1 ? json(autoSleepHistory()) : reenabledHistory.promise;
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('.auto-sleep-lane.source').waitFor();

  await page.locator('#cfg-auto_sleep [role=switch]').click();
  await page.locator('#auto-sleep-status').waitFor({ state: 'detached' });
  await page.getByText('Home Assistant Area: Office').waitFor();

  await page.evaluate(() => {
    window.__autoSleepInsertionBusy = [];
    new MutationObserver(() => {
      const chart = document.querySelector('#auto-sleep-chart');
      if (chart && !window.__autoSleepInsertionBusy.length) {
        window.__autoSleepInsertionBusy.push({
          busy: chart.getAttribute('aria-busy'),
          overlayHidden: chart.querySelector('.auto-sleep-loading-overlay').hidden,
        });
      }
    }).observe(document.querySelector('#cfg-groups'), { childList: true, subtree: true });
  });
  await page.locator('#cfg-auto_sleep [role=switch]').click();
  const chart = page.locator('#auto-sleep-chart');
  await chart.waitFor();
  await chart.locator('.auto-sleep-lane.source').waitFor();
  await chart.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
  assert.deepEqual(await page.evaluate(() => window.__autoSleepInsertionBusy[0]), { busy: 'false', overlayHidden: true });
  await page.waitForFunction(() => document.querySelector('#auto-sleep-chart')?.getAttribute('aria-busy') === 'true');
  assert.equal(await chart.locator('.auto-sleep-loading-overlay').isVisible(), false);
  const stableGeometry = await page.evaluate(() => {
    const panelBox = document.querySelector('#auto-sleep-status').getBoundingClientRect();
    const summaryBox = document.querySelector('#auto-sleep-summary').getBoundingClientRect();
    const chartBox = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    return {
      summaryHeight: summaryBox.height, chartRelativeY: chartBox.y - panelBox.y,
      panelHeight: panelBox.height, chartHeight: chartBox.height, legendY: legend.y,
    };
  });
  await page.waitForTimeout(250);
  assert.deepEqual(await page.evaluate(() => {
    const panelBox = document.querySelector('#auto-sleep-status').getBoundingClientRect();
    const summaryBox = document.querySelector('#auto-sleep-summary').getBoundingClientRect();
    const chartBox = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    return {
      summaryHeight: summaryBox.height, chartRelativeY: chartBox.y - panelBox.y,
      panelHeight: panelBox.height, chartHeight: chartBox.height, legendY: legend.y,
    };
  }), stableGeometry);
  assert.equal(await chart.locator('.auto-sleep-loading-overlay').count(), 1);

  reenabledStatus.resolve(json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 }));
  for (let attempt = 0; attempt < 20 && historyCalls < 2; attempt++) await page.waitForTimeout(25);
  assert.equal(historyCalls, 2);
  assert.deepEqual(await page.evaluate(() => {
    const panelBox = document.querySelector('#auto-sleep-status').getBoundingClientRect();
    const summaryBox = document.querySelector('#auto-sleep-summary').getBoundingClientRect();
    const chartBox = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    return {
      summaryHeight: summaryBox.height, chartRelativeY: chartBox.y - panelBox.y,
      panelHeight: panelBox.height, chartHeight: chartBox.height, legendY: legend.y,
    };
  }), stableGeometry);
  reenabledHistory.resolve(json(autoSleepHistory({ label: 'Office ceiling motion refreshed' })));
  await chart.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
  await page.getByText('Office ceiling motion refreshed').waitFor();
});

browserTest('Auto-sleep source failure keeps the exact focused and scrolled chart snapshot', async (t) => {
  const failedSourcePost = deferred();
  const schema = [{ key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true }];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') return json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 20 });
    if (path === '/api/v1/auto-sleep/history') return json(autoSleepHistory({ laneCount: 20 }));
    if (path === '/api/v1/auto-sleep/source' && request.method === 'POST') return failedSourcePost.promise;
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const row = page.locator('.auto-sleep-lane.source').first();
  await row.waitFor();
  const snapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  const scroll = await page.locator('.auto-sleep-source-scroll').elementHandle();
  await row.focus();
  await scroll.evaluate((node) => { node.scrollTop = 61; });
  await row.press('Enter');
  await page.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
  assert.equal(await page.locator('#auto-sleep-chart').getAttribute('aria-busy'), 'true');
  failedSourcePost.resolve(json({ error: 'failed' }, 500));
  await page.getByText('Could not update this activity source (HTTP failed).').waitFor();
  await page.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
  assert.equal(await snapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await scroll.evaluate((node) => node.isConnected && node.scrollTop), 61);
  assert.equal(await row.evaluate((node) => document.activeElement === node), true);
});

for (const staleSourceOutcome of ['success', 'failure']) {
  browserTest(`Auto-sleep ignores stale source ${staleSourceOutcome} after an Area transition`, async (t) => {
    let areaName = 'Office';
    let statusCalls = 0;
    let historyCalls = 0;
    const sourcePost = deferred();
    const studioStatus = deferred();
    const studioHistory = deferred();
    const schema = [{ key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true }];
    const harness = await startHarness((path, request) => {
      if (path === '/api/v1/config/schema') return json(schema);
      if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true' }, ha_expose: {}, ha_auth: { configured: true } });
      if (path === '/api/v1/apps') return json({ apps: [] });
      if (path === '/api/v1/radio') return json({ present: false });
      if (path === '/api/v1/proximity') return json({ present: false });
      if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: areaName });
      if (path === '/api/v1/auto-sleep') {
        statusCalls++;
        return statusCalls === 1
          ? json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 })
          : studioStatus.promise;
      }
      if (path === '/api/v1/auto-sleep/history') {
        historyCalls++;
        return historyCalls === 1 ? json(autoSleepHistory()) : studioHistory.promise;
      }
      if (path === '/api/v1/auto-sleep/source' && request.method === 'POST') return sourcePost.promise;
    });
    const browser = await chromium.launch({ executablePath: chrome, headless: true });
    const page = await browser.newPage();
    page.setDefaultTimeout(2_000);
    t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
    await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
    const row = page.locator('.auto-sleep-lane.source').first();
    await row.waitFor();
    await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Office', { exact: true }).waitFor();
    const snapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
    await row.press('Enter');
    await page.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
    assert.equal(await page.locator('#auto-sleep-chart').getAttribute('aria-busy'), 'true');

    areaName = 'Studio';
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Studio', { exact: true }).waitFor();
    for (let attempt = 0; attempt < 20 && statusCalls < 2; attempt++) await page.waitForTimeout(25);
    assert.equal(statusCalls, 2);
    sourcePost.resolve(staleSourceOutcome === 'success' ? json({ updated: true }) : json({ error: 'failed' }, 500));
    await page.waitForTimeout(100);
    assert.equal(statusCalls, 2);
    assert.equal(historyCalls, 1);
    assert.equal(await snapshot.evaluate((node) => node.isConnected), true);
    assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
    assert.equal(await page.getByText(/Could not update this activity source/).count(), 0);

    studioStatus.resolve(json({ enabled: true, available: true, phase: 'live', area_name: 'Studio', source_count: 1 }));
    for (let attempt = 0; attempt < 20 && historyCalls < 2; attempt++) await page.waitForTimeout(25);
    studioHistory.resolve(json(autoSleepHistory({ label: 'Studio motion', areaName: 'Studio' })));
    await page.getByText('Studio motion').waitFor();
  });
}

browserTest('Auto-sleep replaces stale startup history when the first Area prerequisite arrives', async (t) => {
  const prerequisite = deferred();
  let statusCalls = 0;
  let historyCalls = 0;
  const schema = [{ key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true }];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') return prerequisite.promise;
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      return json({ enabled: true, available: true, phase: 'live', area_name: statusCalls === 1 ? 'Area A' : 'Area B', source_count: 1 });
    }
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      return json(autoSleepHistory({ label: historyCalls === 1 ? 'Area A motion' : 'Area B motion', areaName: historyCalls === 1 ? 'Area A' : 'Area B' }));
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.getByText('Area A motion').waitFor();
  const staleSnapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();

  prerequisite.resolve(json({ eligible: true, phase: 'assigned', area_name: 'Area B' }));
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Area B', { exact: true }).waitFor();
  await page.getByText('Area B motion').waitFor();
  assert.equal(statusCalls, 2);
  assert.equal(historyCalls, 2);
  assert.equal(await staleSnapshot.evaluate((node) => node.isConnected), false);
  assert.equal(await page.getByText('Area A motion').count(), 0);
});

browserTest('Auto-sleep Area refresh retains A until B is complete and converges unassigned to off without losing edits', async (t) => {
  let areaName = 'Area A';
  let statusCalls = 0;
  let historyCalls = 0;
  const areaBHistory = deferred();
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') return areaName
      ? json({ eligible: true, phase: 'assigned', area_name: areaName })
      : json({ eligible: false, phase: 'unassigned', area_name: null });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      return json({ enabled: true, available: true, phase: 'live', area_name: statusCalls <= 2 ? 'Area A' : 'Area B', source_count: 1 });
    }
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      if (historyCalls <= 2) return json(autoSleepHistory({ label: 'Area A motion', areaName: 'Area A' }));
      return areaBHistory.promise;
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.getByText('Area A motion').waitFor();
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Area A', { exact: true }).waitFor();
  const areaAPanel = await page.locator('#auto-sleep-status').elementHandle();
  const areaAChart = await page.locator('#auto-sleep-chart').elementHandle();
  const areaAContent = await page.locator('.auto-sleep-chart-content').elementHandle();
  const areaASnapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  const beforeAreaChange = await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  });

  areaName = 'Area B';
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Area B', { exact: true }).waitFor();
  assert.equal(statusCalls, 2);
  await page.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
  assert.equal(await page.locator('#auto-sleep-chart').getAttribute('aria-busy'), 'true');
  assert.equal(await areaAPanel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await areaAChart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await areaAContent.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-content')), true);
  assert.equal(await areaASnapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await page.getByText('Area A motion').count(), 1);
  assert.deepEqual(await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  }), beforeAreaChange);
  assert.equal(historyCalls, 1);
  for (let attempt = 0; attempt < 80 && historyCalls < 2; attempt++) await page.waitForTimeout(25);
  assert.equal(historyCalls, 2);
  assert.equal(await areaASnapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await page.getByText('Area A motion').count(), 1);
  for (let attempt = 0; attempt < 240 && historyCalls < 3; attempt++) await page.waitForTimeout(25);
  assert.equal(historyCalls, 3);
  areaBHistory.resolve(json(autoSleepHistory({ label: 'Area B motion', areaName: 'Area B' })));
  await page.getByText('Area B motion').waitFor();
  await page.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
  assert.equal(await areaASnapshot.evaluate((node) => node.isConnected), false);
  assert.equal(await areaAPanel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await areaAChart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await areaAContent.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-content')), true);
  assert.deepEqual(await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  }), beforeAreaChange);

  await page.locator('#cfg-friendly_name input').fill('Uncommitted panel name');
  areaName = null;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.getByText('Assign this panel to a Home Assistant Area before enabling Auto sleep.').waitFor();
  const toggle = page.locator('#cfg-auto_sleep [role=switch]');
  assert.equal(await toggle.getAttribute('aria-checked'), 'false');
  assert.equal(await toggle.getAttribute('aria-disabled'), 'true');
  assert.equal(await toggle.getAttribute('tabindex'), '0');
  assert.equal(await page.locator('#auto-sleep-status').count(), 0);
  assert.equal(await page.locator('#cfg-friendly_name input').inputValue(), 'Uncommitted panel name', 'the typed text stays in its field');
  assert.equal(await page.locator('#cfg-friendly_name input').evaluate((node) => node === document.activeElement), true);
});

function autoSleepHydrationSchema() {
  const field = (key, group, type = 'STRING', extra = {}) => ({
    key, group, type, label: key.replaceAll('_', ' '), available: true,
    options: type === 'ENUM' ? ['Off', 'On'] : [],
    ...extra,
  });
  return [
    field('panel_id', 'Identity'), field('friendly_name', 'Identity'), field('manufacturer', 'Identity'),
    field('model', 'Identity'), field('ha_area', 'Identity', 'STRING', { picker: 'ha_area' }),
    field('auto_sleep', 'Behaviour', 'BOOL'), field('navbar_mode', 'Behaviour', 'ENUM'),
    field('wake_on_wave', 'Behaviour', 'BOOL'), field('kiosk_lock', 'Behaviour', 'BOOL'),
    field('watchdog_enabled', 'Behaviour', 'BOOL'), field('touch_sound', 'Behaviour', 'BOOL'),
    field('silence_boot_chime', 'Behaviour', 'BOOL'),
    field('dark_mode', 'Display', 'BOOL'),
    field('auto_brightness_ha_entity', 'Display', 'STRING', { picker: 'ha_illuminance' }),
    field('auto_brightness', 'Display', 'BOOL'), field('auto_brightness_minimum_percent', 'Display', 'INT'),
    field('auto_brightness_response_percent', 'Display', 'INT'),
    field('cpu_governor', 'System', 'ENUM'), field('zigbee_router', 'System', 'BOOL'),
    field('prevent_idle_dim', 'Behaviour', 'BOOL'), field('keep_awake', 'Behaviour', 'BOOL'),
    field('dashboard_package', 'Dashboard', 'STRING', { picker: 'renderer' }),
    field('dashboard_entity_learning', 'Dashboard', 'BOOL'),
    field('home_dashboard', 'Dashboard', 'STRING', { picker: 'ha_dashboard' }),
    field('dashboard_fullscreen', 'Dashboard', 'BOOL'), field('dashboard_native_kiosk', 'Dashboard', 'BOOL'),
    field('dashboard_idle_return_min', 'Dashboard', 'INT'), field('ha_url', 'Dashboard'),
    field('ha_token', 'Dashboard', 'PASSWORD'), field('dashboard_zoom', 'Dashboard', 'INT'),
    field('launcher_package', 'System'),
    field('network_adb', 'System', 'BOOL'),
    field('log_ship_enabled', 'Logging', 'BOOL'), field('log_ship_host', 'Logging'),
    field('log_ship_port', 'Logging', 'INT'), field('log_ship_protocol', 'Logging', 'ENUM'),
    field('screen', 'Sensors', 'INT'), field('volume', 'Sensors', 'INT'), field('illuminance', 'Sensors', 'INT'),
    field('proximity', 'Sensors', 'BOOL'), field('proximity_level', 'Sensors', 'INT'),
    field('auto_sleep_activity', 'Sensors', 'BOOL'),
    field('diag_ip', 'Diagnostics'), field('diag_cpu', 'Diagnostics', 'INT'),
    field('diag_memory', 'Diagnostics', 'INT'), field('diag_soc_temp', 'Diagnostics', 'FLOAT'),
    field('diag_boot', 'Diagnostics'), field('diag_wifi_rssi', 'Diagnostics', 'INT'),
    field('diag_wifi_outages_24h', 'Diagnostics', 'INT'),
  ];
}

function autoSleepHydrationSettings(source) {
  return {
    panel_id: 'panel-example', friendly_name: 'Example panel', manufacturer: 'Panel maker', model: 'Panel model',
    ha_area: 'Office',
    ha_url: 'https://ha.example', ha_token: '', home_dashboard: '',
    dashboard_package: 'builtin', dashboard_zoom: '100', auto_sleep: 'true', touch_sound: 'false',
    auto_brightness: 'true', auto_brightness_ha_entity: source, auto_brightness_minimum_percent: '10',
    auto_brightness_response_percent: '50', log_ship_enabled: 'false',
  };
}
