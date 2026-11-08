import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import test from 'node:test';
import vm from 'node:vm';

const asset = join(process.cwd(), '..', '..', 'app', 'src', 'main', 'assets', 'i18n.js');

async function load(rawPayload) {
  const source = await readFile(asset, 'utf8');
  const script = rawPayload == null ? null : { textContent: rawPayload };
  const window = {
    document: { getElementById: (id) => id === 'ha-i18n' ? script : null },
  };
  vm.runInNewContext(source, { window, JSON, Object, Array, String });
  return window.HaI18n;
}

test('i18n helper resolves string and catalogue-entry projections with caller-owned fallback', async () => {
  const i18n = await load(JSON.stringify({
    locale: 'zh-Hans',
    strings: {
      'ui.nav.dashboard': '仪表板',
      'ui.status.ready': { text: '已连接到 {service}' },
    },
  }));

  assert.equal(i18n.locale, 'zh-Hans');
  assert.equal(i18n.t('ui.nav.dashboard', 'Dashboard'), '仪表板');
  assert.equal(i18n.t('ui.status.ready', 'Connected to {service}', { service: 'HA' }), '已连接到 HA');
  assert.equal(i18n.t('ui.nav.configure', 'Configure'), 'Configure');
});

test('i18n helper preserves unknown placeholders and machine tokens', async () => {
  const i18n = await load(JSON.stringify({
    strings: {
      'ui.detail': 'Link {state} · HA {missing} · io.homeassistant.companion.android',
    },
  }));

  assert.equal(
    i18n.t('ui.detail', 'fallback', { state: 'online' }),
    'Link online · HA {missing} · io.homeassistant.companion.android',
  );
  assert.equal(i18n.t('missing', 'Version {version}', { version: '$& 0.9.7-rc3' }), 'Version $& 0.9.7-rc3');
  assert.equal(i18n.t('toString', 'safe fallback'), 'safe fallback');
});

test('browser translations and fallback preserve the shared literal value contract', async () => {
  const corpus = JSON.parse(await readFile(join(process.cwd(), '..', '..', 'app', 'src', 'test', 'resources', 'i18n', 'named-template-cases.json'), 'utf8'));
  for (const { name, template, values, expected } of corpus) {
    const i18n = await load(JSON.stringify({ strings: { template } }));
    assert.equal(i18n.t('template', 'unused fallback', Object.fromEntries(values)), expected, name);
    assert.equal(i18n.t('missing', template, Object.fromEntries(values)), expected, name);
  }
});

test('text helper assigns textContent and malformed input fails safely to English', async () => {
  const translated = await load(JSON.stringify({ strings: { warning: '<b>不是标记</b>' } }));
  const node = { textContent: '' };
  assert.equal(translated.text(node, 'warning', 'Not markup'), '<b>不是标记</b>');
  assert.equal(node.textContent, '<b>不是标记</b>');

  const malformed = await load('{not-json');
  assert.equal(malformed.locale, 'en');
  assert.equal(malformed.t('warning', 'English remains available'), 'English remains available');
});
