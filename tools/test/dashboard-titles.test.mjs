// Built-in Home Assistant dashboards arrive titled by their frontend translation key; setup and the
// Configure picker share one name map (HaDashboardTitles) so both show the names Home Assistant shows.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import test from 'node:test';
import vm from 'node:vm';

const asset = join(process.cwd(), '..', '..', 'app', 'src', 'main', 'assets', 'i18n.js');

async function load(strings = {}) {
  const source = await readFile(asset, 'utf8');
  const script = { textContent: JSON.stringify({ strings }) };
  const window = { document: { getElementById: (id) => id === 'ha-i18n' ? script : null } };
  vm.runInNewContext(source, { window, JSON, Object, Array, String });
  return window.HaDashboardTitles;
}

test('built-in panels show their names, other dashboards keep their own title', async () => {
  const titles = await load();
  assert.equal(titles.panelTitle({ path: '/home', title: 'home', group: 'panel' }), 'Overview');
  assert.equal(titles.panelTitle({ path: '/light', title: 'light', group: 'panel' }), 'Lights');
  assert.equal(titles.panelTitle({ path: '/home', title: 'Mine', group: 'dashboard' }), '');
  assert.equal(titles.panelTitle({ path: '/toString', group: 'panel' }), '');
});

test('names come from the translated setup.dashboard.panel keys', async () => {
  const titles = await load({ 'setup.dashboard.panel.home': 'Übersicht' });
  assert.equal(titles.panelTitle({ path: '/home', group: 'panel' }), 'Übersicht');
});
