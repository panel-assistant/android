import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium, webkit } from 'playwright-core';
import { i18nBridge } from './fixtures/i18n-bridge.mjs';

// An optional asset path lets the maintained asset mutation driver exercise this same UI contract.
const asset = process.argv[2] || fileURLToPath(new URL('../../app/src/main/assets/install.js', import.meta.url));
async function rig(t, engine) {
  const browser = await (engine === 'WebKit' ? webkit.launch({ headless: true }) : chromium.launch({ executablePath: process.env.CHROME || '/usr/bin/chromium', headless: true }));
  t.after(() => browser.close());
  const page = await browser.newPage();
  page.on('dialog', dialog => dialog.accept());
  await page.setContent('<html lang="en"><head>' + i18nBridge({}) + '</head><body><input type="file" id="rs-file" onchange="restorePick(this)"><input id="rs-pw" value="first secret"><div id="rs-preview"></div><p id="bk-msg"></p></body></html>');
  await page.evaluate(() => {
    window.CardColumnAlignment = { attach: () => () => {} };
    window.previews = []; window.sent = [];
    window.PaneldTransport = {
      request: (op, args) => {
        if (op === 'restore' && args.dry_run) return new Promise((resolve, reject) => previews.push({ resolve, reject, args }));
        if (op === 'restore') {
          sent.push(args);
          return Promise.resolve({ outcome: 'applied', result: { status: 'busy' } });
        }
        return Promise.resolve({ outcome: 'applied', result: {} });
      }, subscribe: () => ({ close() {} })
    };
  });
  await page.addScriptTag({ path: asset });
  const picker = page.locator('#rs-file');
  return {
    page,
    pick: name => picker.setInputFiles({ name: name + '.hpb', mimeType: 'application/octet-stream', buffer: Buffer.from('backup ' + name) }),
    settle: (index, result, reject = false) => page.evaluate(async ({ index, result, reject }) => {
      if (reject) previews[index].reject(new Error('network unavailable'));
      else previews[index].resolve({ outcome: 'applied', result });
      // Drain promise reactions, including api's unwrap and the UI's catch, before asserting absence.
      await new Promise(resolve => setTimeout(resolve, 0));
    }, { index, result, reject }),
    shown: () => page.locator('#rs-preview').textContent(),
    buttons: () => page.locator('#rs-preview button').count(),
    confirm: () => page.locator('#rs-preview button').click(),
    submitted: () => page.evaluate(() => Promise.all(sent.map(async args => ({ file: args.blob.name, bytes: await args.blob.text(), passphrase: args.passphrase }))))
  };
}
const preview = name => ({ ok: true, panel_id: 'Preview ' + name, config_keys: 10, companion_files: 0 });
for (const engine of ['Chromium', 'WebKit']) {
  test(`restore reverse completion binds the displayed backup and password in ${engine}`, async t => {
    const ui = await rig(t, engine);
    await ui.pick('A');
    await ui.page.locator('#rs-pw').fill('B secret');
    await ui.pick('B');
    await ui.settle(1, preview('B'));
    await ui.settle(0, preview('A'));
    assert.match(await ui.shown(), /Preview B/, 'late A must preserve displayed B');
    await ui.confirm();
    assert.deepEqual(await ui.submitted(), [{ file: 'B.hpb', bytes: 'backup B', passphrase: 'B secret' }]);
    assert.deepEqual(await ui.page.evaluate(() => previews.map(p => [p.args.blob.name, p.args.passphrase])), [['A.hpb', 'first secret'], ['B.hpb', 'B secret']]);
  });
  for (const failure of ['busy', 'refused', 'network']) {
    test(`restore B ${failure} cannot admit late A in ${engine}`, async t => {
      const ui = await rig(t, engine);
      await ui.pick('A'); await ui.pick('B');
      await ui.settle(1, failure === 'busy' ? { status: 'busy' } : { ok: false, error: 'invalid bundle' }, failure === 'network');
      await ui.settle(0, preview('A'));
      assert.equal(await ui.buttons(), 0, 'a failed current preview cannot expose stale confirmation');
      await ui.page.evaluate(() => restoreConfirm(document.createElement('button')));
      assert.deepEqual(await ui.submitted(), []);
      // A fresh successful selection still works after failure.
      await ui.pick('C'); await ui.settle(2, preview('C')); await ui.confirm();
      assert.deepEqual(await ui.submitted(), [{ file: 'C.hpb', bytes: 'backup C', passphrase: 'first secret' }]);
    });
  }
  test(`restore selection and passphrase changes invalidate confirmation in ${engine}`, async t => {
    const ui = await rig(t, engine);
    await ui.pick('A'); await ui.settle(0, preview('A'));
    await ui.page.evaluate(() => { window.oldButton = document.querySelector('#rs-preview button'); });
    await ui.page.locator('#rs-pw').fill('changed secret');
    assert.equal(await ui.buttons(), 0, 'editing passphrase removes prior confirmation');
    await ui.page.evaluate(() => restoreConfirm(oldButton));
    assert.deepEqual(await ui.submitted(), []);
    await ui.pick('B'); await ui.settle(1, preview('B'));
    await ui.page.locator('#rs-file').setInputFiles([]);
    assert.equal(await ui.buttons(), 0, 'clearing selection removes prior confirmation');
    await ui.pick('C'); await ui.settle(2, preview('C'));
    await ui.pick('D');
    assert.equal(await ui.buttons(), 0, 'a new pending selection removes prior confirmation');
    await ui.settle(3, { ok: false, error: 'bad passphrase' });
    assert.equal(await ui.buttons(), 0, 'a failed replacement removes prior confirmation');
    await ui.pick('E'); await ui.settle(4, preview('E')); await ui.confirm();
    assert.deepEqual(await ui.submitted(), [{ file: 'E.hpb', bytes: 'backup E', passphrase: 'changed secret' }]);
  });
  test(`restore checks the current passphrase even without an input event in ${engine}`, async t => {
    const ui = await rig(t, engine);
    await ui.pick('A'); await ui.settle(0, preview('A'));
    await ui.page.evaluate(() => { document.getElementById('rs-pw').value = 'silent change'; });
    await ui.confirm();
    assert.deepEqual(await ui.submitted(), [], 'a changed field cannot confirm a previous preview');
    assert.equal(await ui.buttons(), 0);
  });
  test(`restore pending passphrase change fences late success and failure in ${engine}`, async t => {
    const ui = await rig(t, engine);
    await ui.pick('A'); await ui.page.locator('#rs-pw').fill('new secret');
    await ui.settle(0, preview('A'));
    assert.equal(await ui.buttons(), 0, 'changed passphrase invalidates pending success');
    await ui.pick('B'); await ui.pick('C'); await ui.settle(2, preview('C'));
    const before = await ui.shown();
    await ui.settle(1, {}, true);
    assert.equal(await ui.shown(), before, 'a stale network failure preserves current preview');
    await ui.confirm();
    assert.deepEqual(await ui.submitted(), [{ file: 'C.hpb', bytes: 'backup C', passphrase: 'new secret' }]);
  });
}
