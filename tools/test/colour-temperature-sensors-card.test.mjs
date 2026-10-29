import assert from 'node:assert/strict';
import { browserTest } from './fixtures/browser-harness.mjs';
import { sensorCard } from './fixtures/dashboard-sensors.mjs';

browserTest('live card shows estimated kelvin with receipt age beside existing sensor readings', async t => {
  const rowsFor = await sensorCard(t);
  assert.deepEqual(await rowsFor({ colour_temperature: { present: true, kelvin: 2726.6, age_s: 3 }, light: { present: true, lux: 20, age_s: 2 } }), [
    { label: 'Ambient light', val: '20 lx', suf: '· 2s ago' },
    { label: 'Estimated colour temperature', val: '2726.6 K', suf: '· 3s ago' },
  ]);
});
browserTest('supported source without a usable sample shows no reading and missing source has no row', async t => {
  const rowsFor = await sensorCard(t);
  // NaN and Infinity are not JSON wire numbers: both serialize as null. Exercise null once.
  for (const kelvin of [null, 0, -1, '2726']) {
    assert.deepEqual(await rowsFor({ colour_temperature: { present: true, kelvin, age_s: null } }),
      [{ label: 'Estimated colour temperature', val: 'no reading yet', suf: '' }]);
  }
  assert.deepEqual(await rowsFor({ colour_temperature: { present: false } }),
    [{ label: '', val: 'no sensors on this panel', suf: '' }]);
});
