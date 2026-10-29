import assert from 'node:assert/strict';
import { browserTest } from './fixtures/browser-harness.mjs';
import { sensorCard } from './fixtures/dashboard-sensors.mjs';

browserTest('calibrated ranged source displays API level and raw value even while far', async t => {
  const rowsFor = await sensorCard(t);
  for (const [near, level, raw, val, suf] of [[false, 11, 971, '11%', 'raw 971 · far'], [true, 89, 1200, '89%', 'raw 1200 · near']]) {
    assert.deepEqual(await rowsFor({ proximity: { present: true, mode: 'ranged', health: 'healthy', presenceReady: true, near, level, raw } }),
      [{ label: 'Proximity', val, suf }]);
  }
});
browserTest('uncalibrated and active setup show actual scalar readings without provisional percentage', async t => {
  const rowsFor = await sensorCard(t);
  assert.deepEqual(await rowsFor({ proximity: { present: true, mode: 'ranged', health: 'healthy', phase: 'calibration_required', raw: 971 } }),
    [{ label: 'Proximity', val: 'raw 971', suf: 'calibration required' }]);
  assert.deepEqual(await rowsFor({ proximity: { present: true, mode: 'ranged', health: 'healthy', presenceReady: true, sessionActive: true, phase: 'calibrating', level: 80, raw: 1100 } }),
    [{ label: 'Proximity', val: 'raw 1100', suf: 'calibrating' }]);
});
browserTest('unavailable source never presents retained scalar readings as current', async t => {
  const rowsFor = await sensorCard(t);
  assert.deepEqual(await rowsFor({ proximity: { present: true, mode: 'ranged', health: 'source_unavailable', presenceReady: true, near: true, level: 99, raw: 1300 } }),
    [{ label: 'Proximity', val: '· unavailable', suf: '' }]);
});
browserTest('binary source keeps its state representation and never fabricates a distance', async t => {
  const rowsFor = await sensorCard(t);
  assert.deepEqual(await rowsFor({ proximity: { present: true, mode: 'binary', health: 'healthy', near: true, raw: 0, level: 100, presenceReady: true } }),
    [{ label: 'Proximity', val: 'near', suf: 'raw 0' }]);
});
