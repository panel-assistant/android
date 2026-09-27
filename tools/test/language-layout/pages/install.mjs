// Install: mirror of PaneldServer.installBody() inside page() on a panel with root and the privileged
// installer ready, an old WebView the profile can heal, a Companion whose internal URL needs repair and a
// power-safety advisory offering repair — so every card and every top-of-tab warning renders. The API
// payload and the dynamic-state exercise are shared with install-localization-layout-gate.test.mjs.
import { tabbedPage } from '../harness.mjs';

const GH_ICON = 'M12 2a10 10 0 0 0-3 19.5v-3.4c-2.8.6-3.4-1.2-3.4-1.2-.4-1.1-1.1-1.4-1.1-1.4-.9-.6.1-.6.1-.6 1 .1 1.5 1 1.5 1 .9 1.5 2.4 1.1 3 .8.1-.6.3-1.1.6-1.3-2.2-.3-4.6-1.1-4.6-5 0-1.1.4-2 1-2.7-.1-.3-.4-1.3.1-2.7 0 0 .8-.3 2.8 1a9.6 9.6 0 0 1 5 0c1.9-1.3 2.8-1 2.8-1 .5 1.4.2 2.4.1 2.7.6.7 1 1.6 1 2.7 0 3.9-2.4 4.7-4.6 5 .4.3.7.9.7 1.9v2.8A10 10 0 0 0 12 2z';
const HOSTILE_ONE = '<img src=x onerror="window.__hostileOwned=1">';
export const hostile = HOSTILE_ONE.repeat(3);

/** API answers for install.js and power-safety.js, including hostile strings that must stay text. */
export function payload(url, method) {
  const path = url.pathname;
  if (path === '/api/v1/packages') return { packages: [{ pkg: 'io.example.hostile', label: HOSTILE_ONE }] };
  if (path === '/api/v1/install/versions') return { versions: [{ tag: 'v2026.9.4-long', version: '2026.9.4-expanded-release-candidate', installable: true, action: 'Upgrade', presentations: { action: { code: 'version-upgrade', params: {} } }, notes: 'https://example.invalid/releases', apk: 'https://example.invalid/releases/download/test/app.apk' }] };
  if (path === '/api/v1/install/apk/pending') return { pending: true, package: 'io.example.pending_application_with_a_very_long_identifier', version: '2026.9.4-expanded-release-candidate', discard: 'discard-reference' };
  if (path === '/api/v1/radio') return { present: true, status: 'Radio firmware 9.9.9', state: 'degraded_high_cpu', presentations: { status: null } };
  if (path === '/api/v1/status') return { warnings: ['System WebView compatibility warning', 'Storage is critically constrained with a deliberately long exact diagnostic value'], warning_presentations: [{ code: 'status-webview-old', params: { current_engine: '<img src=x onerror=window.__hostileOwned=1>', target_chromium: '130' } }, { code: 'status-storage-critical', params: { usable_bytes: '1024', total_bytes: '999999999999', used_percent: '99.9' } }] };
  if (path === '/api/v1/install/component') return { status: 'busy' };
  if (path === '/api/v1/install/status') return { running: false, component: 'restore', message: hostile, presentation: { code: 'restore-completed', params: {} } };
  if (path === '/api/v1/uninstall') return { ok: false, result: hostile, presentation: { code: 'package-uninstall-failed', params: { package: 'io.example.hostile_application_with_a_long_identifier' } } };
  if (path === '/api/v1/restore' && method === 'POST' && url.searchParams.get('dry_run') === '1') return { ok: true, panel_id: hostile, config_keys: 999999, companion_pkg: 'io.homeassistant.companion.android', companion_files: 123 };
  if (path === '/api/v1/restore' && method === 'POST') return { status: 'started' };
  if (path === '/api/v1/power-safety/repair') return { status: 'partial', message: hostile, power_safety: { acknowledge_available: true, acknowledgement_fingerprint: 'fingerprint' } };
  if (path === '/api/v1/power-safety/acknowledge') return { acknowledged: false, message: hostile };
  return {};
}

/**
 * Drive every dynamic Install state: a busy component install, a failed uninstall, the health-audit
 * alerts, a restore preview and its completion, and the power-safety repair then acknowledgement. Works on
 * a Playwright Page or Frame, against the real markup and the qualification fixture alike.
 */
// The qualification fixture presses like a user: layout shifts within 500 ms of real input are excluded
// from its CLS budget. The layout gate measures geometry, not CLS, and presses through the DOM instead — on
// narrow card walls content-visibility and the column alignment reflow cards while Playwright scrolls, and
// WebKit's pointer click then lands beside the button. Both run the same handlers.
async function press(page, selector, pointer) {
  if (pointer) await page.locator(selector).click();
  else await page.evaluate((target) => document.querySelector(target).click(), selector);
}

export async function exerciseStates(page, { pointer = true } = {}) {
  // The real page asks confirm() before uninstall and restore; Playwright would dismiss it.
  await page.evaluate(() => { window.confirm = () => true; });
  await page.waitForFunction(() => document.querySelectorAll('.cvsel option').length === 2 && document.querySelector('#apk-preview button'));
  await page.evaluate(() => window.installComp('paneld', 'update', document.querySelector('.cinstall')));
  // End states are recognised by their localized text or control state, never by length: a CJK
  // message is short, and the qualification fixture pads every string.
  await page.waitForFunction(() => document.querySelector('#comp-msg').textContent
    === (window.HaI18n ? window.HaI18n.t('install.progress.busy', 'Another install is already running — try again shortly.') : 'Another install is already running — try again shortly.'));
  await press(page, 'button[onclick^="doUninstall"]', pointer);
  await page.waitForFunction(() => document.querySelector('#uninst-msg').textContent.length > 0 && !document.querySelector('button[onclick^="doUninstall"]').disabled);
  await press(page, 'button[onclick^="healthAudit"]', pointer);
  await page.waitForFunction(() => document.querySelectorAll('#audit-out .setup').length === 2);
  await page.evaluate(() => {
    const input = document.querySelector('#rs-file');
    const transfer = new DataTransfer();
    transfer.items.add(new File(['bounded backup'], 'expanded-layout.hpb', { type: 'application/octet-stream' }));
    Object.defineProperty(input, 'files', { configurable: true, value: transfer.files });
    window.restorePick(input);
  });
  await page.waitForFunction(() => document.querySelectorAll('#rs-preview tr').length === 3);
  await press(page, '#rs-preview button', pointer);
  // Settled once the message is the result, not the localized in-progress line.
  await page.waitForFunction(() => {
    const text = document.querySelector('#bk-msg').textContent;
    const restoring = window.HaI18n ? window.HaI18n.t('install.backup.dynamic.restoring', 'Restoring…') : 'Restoring…';
    return text.length > 0 && text !== restoring && !text.includes('Restoring');
  });
  await press(page, 'form[data-power-safety-repair] button', pointer);
  await page.waitForFunction(() => document.querySelector('form[data-power-safety-acknowledge]'));
  await press(page, 'form[data-power-safety-acknowledge] button', pointer);
  await page.waitForFunction(() => {
    const text = document.querySelector('.power-safety-acknowledge-result')?.textContent || '';
    return text.length > 0 && text !== (window.HaI18n ? window.HaI18n.t('runtime.power_safety.ack.saving', 'Saving acknowledgement…') : 'Saving acknowledgement…');
  });
  await page.waitForTimeout(180);
}

// ---- mirrors of the PaneldServer helpers the Install body is built from ----

function builders(s, locale) {
  const href = (path) => {
    if (locale === 'en') return path;
    const at = path.indexOf('#');
    const address = at < 0 ? path : path.slice(0, at);
    const fragment = at < 0 ? '' : path.slice(at);
    return `${address}${address.includes('?') ? '&' : '?'}lang=${locale}${fragment}`;
  };
  const a11y = (conditional = false) => ` aria-describedby="${conditional ? 'hardened-approval-conditional-description' : 'hardened-approval-description'}" title="${s.t(conditional ? 'configure.hardened.setting_approval' : 'configure.hardened.action_approval')}"`;
  const approval = (conditional = false) => ` data-hardened-approval${conditional ? '="conditional"' : ''}${a11y(conditional)}`;
  const cardTitle = (title, badge = '', conditional = false) => `<h2 data-hardened-approval${conditional ? '="conditional"' : ''} aria-describedby="${conditional ? 'hardened-approval-section-conditional-description' : 'hardened-approval-section-description'}" title="${s.t(conditional ? 'shell.hardened.section_conditional' : 'shell.hardened.section')}">${title}${badge}</h2>`;
  const installed = (version) => (version != null ? `${s.t('install.shared.installed')} <span class="cver">${version}</span>` : `<span class="cver">${s.t('install.shared.not_installed')}</span>`);
  // pickerRow(installer = true)
  const pickerRow = (name, label, version) => `<div class="comprow" data-name="${name}">
<div class="compname"><b>${label}</b> <span class="muted">${installed(version)}</span></div>
<div class="comppick">
<label class="muted">${s.t('install.components.channel')} <select class="cchan" onchange="loadVersions('${name}')"><option value="stable" selected>${s.t('install.components.stable')}</option><option value="prerelease">${s.t('install.components.prerelease')}</option></select></label>
<label class="muted">${s.t('install.shared.version')} <select class="cvsel" onchange="verChanged('${name}')"><option>${s.t('install.shared.loading')}</option></select></label>
<a class="gh gh-inline cnotes" target="_blank" rel="noopener" title="${s.t('install.components.release_notes')}" aria-label="${s.t('install.components.release_notes')}" style="visibility:hidden"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="${GH_ICON}"/></svg></a>
<button class="pbtn cinstall"${a11y()} onclick="installSel('${name}',this)" data-root="1" disabled>${s.t('install.components.install')}</button>
</div></div>`;
  const simpleRow = (label, version, action) => `<div class="comprow">
<div class="compname"><b>${label}</b> <span class="muted">${installed(version)}</span></div>
<div class="comppick">${action}</div></div>`;
  return { href, a11y, approval, cardTitle, pickerRow, simpleRow };
}

// localizedPowerSafetyBanner(REPAIR, direct_root, inlineRepair) — the catalogue path for every locale.
function powerBanner(s) {
  return `<div class="setup crit" data-power-safety-banner>⛔ <b>${s.t('runtime.power_safety.level.at_risk')}</b> — ${s.t('runtime.power_safety.summary.at_risk')} ${s.t('runtime.power_safety.action.repair_direct')} <form method="post" action="api/v1/power-safety/repair" data-power-safety-repair style="display:inline"><button class="pbtn" type="submit" data-hardened-approval title="${s.t('runtime.power_safety.button.repair_title')}">${s.t('runtime.power_safety.button.repair')}</button> <span class="power-safety-repair-result" role="status" aria-live="polite"></span></form></div>`;
}

function warnings(s, b) {
  // adHocWarnings(): Companion internal URL needing repair, with the inline repair button.
  const companion = `<div class="setup crit">⚠ <b>${s.t('dashboard.banner.companion_url.title')}</b> ${s.t('dashboard.banner.companion_url.summary_many', { count: 2 })} <i>"Missing 'Host' header"</i>. ${s.t('dashboard.banner.companion_url.explanation')}<div style="margin-top:10px"><button class="pbtn"${b.approval()} onclick="repairCompUrl(this)">⚙ ${s.t('dashboard.banner.companion_url.repair')}</button> <span id="cu-fix" class="muted"></span></div></div>`;
  // installWarning(WEBVIEW_OLD, canHeal = true)
  const webview = `<div class="setup crit">⚠ <b>${s.t('install.warning.webview_old.title')}</b> (Chromium 95.0.4638.74) — ${s.t('install.warning.webview_old.body')} <a href="https://example.invalid/webview" target="_blank" rel="noopener">${s.t('install.warning.webview_old.help')}</a> (${s.t('install.warning.webview_old.target', { version: 120 })}).<div style="margin-top:10px"><button class="pbtn"${b.approval()} onclick="healWebView(this)">⬇ ${s.t('install.warning.webview_old.update')}</button> <span id="wv-heal" class="muted"></span></div></div>`;
  return powerBanner(s) + companion + webview;
}

function componentsCard(s, b) {
  const wvAction = `<button class="pbtn"${b.a11y()} onclick="installComp('webview','update',this)">⬇ ${s.t('install.components.update_webview')}</button>`;
  return `<div class="card" data-layout-key="managed-components">${b.cardTitle(s.t('install.components.title'), '', true)}
${b.pickerRow('paneld', 'ha-paneld', '0.9.8-rc4')}
${b.pickerRow('companion', 'HA Companion', '2026.9.4-minimal')}
${b.simpleRow('System WebView', 'Chromium 95.0.4638.74', wvAction)}

<p class="note">${s.t('install.components.channel_prefix')} <a href="${b.href('configure')}">${s.t('shell.nav.configure')}</a>${s.t('install.components.channel_suffix')}</p>
<p class="note" id="comp-msg"></p></div>`;
}

function apkCard(s, b) {
  return `<div class="card" data-layout-key="apk-install">${b.cardTitle(s.t('install.apk.title'))}
<p class="note">${s.t('install.apk.description')}</p>
<div class="setup">⚠ <b>${s.t('install.apk.security_title')}</b> ${s.t('install.apk.security_warning')} <small>(${s.t('install.apk.security_future_auth')})</small></div>
<label style="display:flex;flex-direction:row;gap:8px;align-items:center;margin:10px 0"><input type="checkbox" id="apk-allow" checked onchange="apkAllow(this)"> ${s.t('install.apk.enable')}</label>
<div id="apk-ui">
<label class="pbtn" style="cursor:pointer">⭱ ${s.t('install.apk.choose')}<input type="file" id="apk-file" accept=".apk,application/vnd.android.package-archive" style="display:none" onchange="apkPick(this)"></label>
<label style="margin-top:10px">${s.t('install.apk.fetch_label')}<input type="url" id="apk-url" inputmode="url" autocomplete="off" spellcheck="false" placeholder="https://example.com/app.apk"></label>
<button class="pbtn" style="margin-top:8px" onclick="apkFetchUrl()">⇩ ${s.t('install.apk.fetch')}</button>
<div id="apk-preview" style="margin-top:10px"></div>
</div>
<p class="note" id="apk-msg"></p></div>`;
}

function uninstallCard(s, b) {
  return `<div class="card" data-layout-key="uninstall-app">${b.cardTitle(s.t('install.uninstall.title'))}
<p class="note">${s.t('install.uninstall.description_prefix')} <a href="${b.href('install#cfg-tame')}">${s.t('install.uninstall.tame_link')}</a>${s.t('install.uninstall.description_suffix')}</p>
<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
<select id="uninst-pkg" style="min-width:220px;background:#1c1c1c;color:#eee;border:1px solid #444;border-radius:7px;padding:5px 8px"><option>${s.t('install.shared.loading')}</option></select>
<button class="pbtn"${b.a11y()} onclick="doUninstall(this)">${s.t('install.uninstall.action')}</button>
</div>
<p class="note" id="uninst-msg"></p></div>`;
}

function radioAndAudit(s, b) {
  return `<div class="card" id="radiocard" data-layout-key="radio-firmware" style="display:none"><h2>${s.t('install.radio.title')}</h2>
<table><tr><th>${s.t('install.radio.efr32')}</th><td id="radio-status">…</td></tr>
<tr><th>${s.t('install.radio.gateway_health')}</th><td id="radio-health">…</td></tr></table>
<p class="note">${s.t('install.radio.note_prefix')} <a href="${b.href('configure#cfg-zigbee_join')}">${s.t('install.radio.configure_join')}</a>. <span class="muted">${s.t('install.radio.thread_planned')}</span></p></div>
<div class="card" data-layout-key="health-audit"><h2>${s.t('install.audit.title')}</h2>
<p class="note">${s.t('install.audit.description')}</p>
<button class="pbtn" onclick="healthAudit(this)">${s.t('install.audit.run')}</button>
<div id="audit-out" style="margin-top:10px"></div>
<p class="note"><a href="api/v1/diag" target="_blank" style="color:#9cf">⭳ ${s.t('install.audit.diagnostics')}</a> — ${s.t('install.audit.diagnostics_help')}</p></div>`;
}

// tameRowHtml(showState = false, disabled = false)
function tameRow(s, b, { label, pkg, tags = [], recommended = false, note = '', tamed = false, removable = true }) {
  const tagNames = { core: 'install.tame.tag.core', vendor: 'install.tame.tag.vendor', user: 'install.tame.tag.user', overlay: 'install.tame.tag.overlay' };
  const tagHtml = tags.map((tag) => `<span class="vtag">${s.t(tagNames[tag])}</span>`).join('');
  const rec = recommended && !tamed ? `<span class="vtag rec">${s.t('install.tame.badge.recommended')}</span>` : '';
  const noteHtml = note ? `<br><small style="color:#9aa">${note}</small>` : '';
  const btn = tamed ? '' : 'background:#7a2e2e;border-color:#7a2e2e';
  const control = !removable
    ? `<span style="font-size:.8em;color:#777;white-space:nowrap">${s.t('install.tame.state.protected')}</span>`
    : `<form method="post" action="${b.href('api/v1/tame')}" style="margin:0"><input type="hidden" name="pkg" value="${pkg}"><input type="hidden" name="action" value="${tamed ? 'untame' : 'tame'}"><button type="submit"${b.a11y()} style="${btn};white-space:nowrap">${s.t(tamed ? 'install.tame.action.reenable' : 'install.tame.action.tame')}</button></form>`;
  return `  <div style="display:flex;align-items:center;gap:10px;padding:9px 0;border-top:1px solid #222">
   <span style="flex:1;min-width:0;overflow:hidden">${label}${rec}${tagHtml}<br><small style="color:#888">${pkg}</small>${noteHtml}</span>

   ${control}
  </div>`;
}

function tameCard(s, b) {
  const json = (key) => JSON.stringify(s.text(key)).replaceAll('<', '\\u003c');
  const rows = [
    tameRow(s, b, { label: 'Vendor launcher', pkg: 'com.example.vendor.launcher', tags: ['vendor'], recommended: true, note: 'Replaces the home screen on boot.' }),
    tameRow(s, b, { label: 'Vendor update service', pkg: 'com.example.vendor.updater', tags: ['vendor', 'overlay'], tamed: true }),
    tameRow(s, b, { label: 'System settings', pkg: 'com.example.settings', tags: ['core'], removable: false }),
  ].join('\n');
  return `<div class="card" id="cfg-tame" data-layout-key="vendor-packages">${b.cardTitle(s.t('install.card.vendor_packages'), '', true)}
<p class="note">${s.t('install.tame.description')}</p>
${rows}
<div style="display:flex;flex-direction:column;gap:8px;margin-top:12px" class="">
 <button type="button" onclick="pkgPick()">${s.t('install.tame.find')}</button>
 <form method="post" action="${b.href('api/v1/tame')}" style="display:grid;grid-template-columns:1fr auto;gap:8px;margin:0">
  <label for="tame-pkg" style="grid-column:1/-1">${s.t('install.tame.package_name')}</label>
  <input id="tame-pkg" name="pkg" autocapitalize="none" autocorrect="off" spellcheck="false" required pattern="[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*" maxlength="255" aria-describedby="tame-pkg-hint" placeholder="io.example.app" style="min-width:0" oninput="updateTamePackageSubmit()">
  <input type="hidden" name="action" value="tame">
  <button id="tame-package-submit" type="submit"${b.a11y()}>${s.t('install.tame.action.tame')}</button>
  <small id="tame-pkg-hint" class="note" style="grid-column:1/-1">${s.t('install.tame.package_hint')}</small>
 </form>
</div>
<div id="hand-back-home" style="margin-top:16px;padding-top:12px;border-top:1px solid #222">
 <h3 style="margin:0 0 4px">${s.t('install.tame.hand_back.title')}</h3>
 <p class="note" style="margin:0 0 4px">${s.t('install.tame.hand_back.description')}</p>
 <p class="note" style="margin:0 0 4px"><strong>${s.t('install.tame.hand_back.warning')}</strong></p>
 <p class="note" style="margin:0 0 8px">${s.t('install.tame.hand_back.scope')}</p>
 <button id="hand-back-home-button" type="button" onclick="handBackHome()"${b.a11y()}>${s.t('install.tame.hand_back.action')}</button>
 <p id="hand-back-home-status" class="note" role="status" aria-live="polite" style="margin:8px 0 0"></p>
</div>
<dialog id="pkgdlg" style="background:#1a1a1a;color:#eee;border:1px solid #333;border-radius:12px;max-width:520px;width:92%;padding:16px">
 <h3 data-hardened-approval="conditional" aria-describedby="hardened-approval-section-conditional-description" title="${s.t('shell.hardened.section_conditional')}" style="margin:0 0 4px">${s.t('install.tame.dialog.title')}</h3>
 <p class="note" style="margin:0 0 8px">${s.t('install.tame.dialog.description')}</p>
 <div id="pkgdlgbody" style="max-height:55vh;overflow:auto">${s.t('install.shared.loading')}</div>
 <form method="dialog" style="margin-top:12px;text-align:right"><button>${s.t('install.shared.close')}</button></form>
</dialog>
<script>function pkgPick(){var d=document.getElementById('pkgdlg');d.showModal();document.getElementById('pkgdlgbody').textContent=${json('install.shared.loading')};}
function updateTamePackageSubmit(){var input=document.getElementById('tame-pkg'),button=document.getElementById('tame-package-submit');if(!input||!button)return;button.disabled=input.disabled||!input.checkValidity();}updateTamePackageSubmit();
function handBackHome(){}</script></div>`;
}

function displayCard(s, b) {
  const badge = `<span class="cardbadge exp">${s.t('install.display.badge.experimental')}</span>`;
  return `<div class="card" id="cfg-display" data-layout-key="display-sizing">${b.cardTitle(s.t('install.display.title'), badge)}
<p class="note">${s.t('install.display.description')}</p>
<form method="post" action="${b.href('api/v1/display/density')}" class="" style="display:flex;flex-direction:column;gap:10px">
 <label style="display:flex;flex-direction:row;justify-content:space-between;align-items:center;gap:12px">
  <span>${s.t('install.display.logical_density')} <small style="color:#888">· ${s.t('install.display.profile_recommendation', { value: 160 })}</small></span>
  <input name="density" type="number" min="120" max="640" value="160" style="width:96px">
 </label>
 <label style="display:flex;flex-direction:row;justify-content:space-between;align-items:center;gap:12px">
  <span>${s.t('install.display.text_size')} <small style="color:#888">· ${s.t('install.display.default_scale')}</small></span>
  <input name="font" type="number" step="0.05" min="0.85" max="1.3" value="1.0" style="width:96px">
 </label>
 <div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:2px">
  <button type="submit"${b.a11y()}>${s.t('install.display.apply')}</button> <button type="submit" name="action" value="rec"${b.a11y()} formnovalidate>${s.t('install.display.ha_optimised')}</button>
  <button type="submit" name="action" value="reset" aria-describedby="hardened-approval-description" formnovalidate title="${s.t('install.display.reset_default_with_dpi', { value: 240 })} · ${s.t('configure.hardened.action_approval')}">${s.t('install.display.reset')}</button>
 </div>
</form></div>`;
}

// backupCardHtml(companionHelper = true, companionInstalled = true): the login choice is offered.
function backupCard(s, b) {
  return `<div class="card" data-layout-key="backup-restore">${b.cardTitle(s.t('install.backup.title'), '', true)}
<p class="note">${s.t('install.backup.description.with_companion')}</p>
<div style="display:flex;flex-direction:column;gap:8px;max-width:440px">
<label style="display:flex;flex-direction:row;gap:8px;align-items:center;font-size:.85rem"><input type="checkbox" id="bk-comp" checked> ${s.t('install.backup.companion.include_login')}</label>
<input type="password" id="bk-pw" placeholder="${s.t('install.backup.passphrase.placeholder')}">
<label style="display:flex;flex-direction:row;gap:8px;align-items:flex-start;font-size:.85rem;color:#c88"><input type="checkbox" id="bk-plain"> ${s.t('install.backup.plaintext_zip')}</label>
<button class="pbtn"${b.a11y()} onclick="doBackup(this)">⭳ ${s.t('install.backup.download')}</button>
</div>
<hr style="border:0;border-top:1px solid #2a2a2a;margin:14px 0">
<p class="note"><b>${s.t('install.backup.restore.title')}</b> ${s.t('install.backup.restore.description.with_companion')}</p>
<div style="display:flex;flex-direction:column;gap:8px;max-width:440px">
<input type="password" id="rs-pw" placeholder="${s.t('install.backup.restore.passphrase_placeholder')}">
<label class="pbtn" style="cursor:pointer">⭱ ${s.t('install.backup.restore.choose')}<input type="file" id="rs-file" accept=".hpb,.zip,application/octet-stream,application/zip" style="display:none" onchange="restorePick(this)"></label>
<div id="rs-preview"></div>
</div>
<p class="note" id="bk-msg"></p>
<hr style="border:0;border-top:1px solid #2a2a2a;margin:14px 0">
<p class="note"><b>${s.t('install.backup.config_bundle.title')}</b> ${s.t('install.backup.config_bundle.description')}</p>
<div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center">
 <a class="pbtn" href="api/v1/config/export">⭳ ${s.t('install.backup.config_bundle.export')}</a>
 <button class="pbtn" type="button"${b.a11y()} onclick="configExport(true,this)">⭳ ${s.t('install.backup.config_bundle.export_secrets')}</button>
 <label class="pbtn"${b.a11y()} style="cursor:pointer">⭱ ${s.t('install.backup.config_bundle.import')}<input type="file" id="cfg-import-file" accept="application/json" style="display:none" onchange="configImport(this)"></label>
</div>
<p id="cfg-export-result" class="note" role="status" aria-live="polite"></p>
<pre id="cfg-import-result" class="muted" style="white-space:pre-wrap;margin-top:10px"></pre></div>`;
}

export default {
  name: 'install',
  path: '/install',
  html(context) {
    const { s, locale } = context;
    const b = builders(s, locale);
    const body = `${warnings(s, b)}
<div class="cards" id="install-cards" data-card-size-page="install" data-card-size-epoch="1" data-card-size-restore="1">
${componentsCard(s, b)}
${apkCard(s, b)}
${uninstallCard(s, b)}
${radioAndAudit(s, b)}
${tameCard(s, b)}
${displayCard(s, b)}
${backupCard(s, b)}
</div>
<script src="assets/card-size-memory.js"></script>
<script src="assets/card-column-alignment.js"></script>
<script src="assets/install.js"></script>`;
    return tabbedPage({ ...context, active: 'install', sectionTitle: s.text('shell.nav.install'), body, prefixes: ['shell.', 'configure.hardened.', 'dashboard.banner.', 'install.', 'runtime.'] });
  },
  api(url, method) { return payload(url, method); },
  async ready(frame) {
    await frame.waitForFunction(() => document.querySelectorAll('.cvsel option').length === 2
      && document.querySelector('#uninst-pkg option[value]') && document.querySelector('#apk-preview button')
      && getComputedStyle(document.querySelector('#radiocard')).display !== 'none'
      && document.querySelector('#radio-status').textContent !== '…');
  },
  exercise: (frame) => exerciseStates(frame, { pointer: false }),
};
