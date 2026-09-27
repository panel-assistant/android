// Profiles: mirror of PaneldServer.profilesBody() inside page(), with a catalog of four revisions of one
// profile (the collapsed picker plus its superseded toggle), a device report, catalog issues and, once
// exercised, a validated preview with issues and a comparison against the active profile.
import { tabbedPage } from '../harness.mjs';

/** Mirror of PaneldServer.profilesBody(strings). `s.t(key)` is the escaped localized string. */
export function profilesBody(s, { guidanceShown = false } = {}) {
  const t = (key) => s.t(key);
  // Production ships both inspector notes hidden; profiles.js reveals them after the catalogue loads.
  const hidden = guidanceShown ? '' : ' hidden';
  return `<link rel="stylesheet" href="assets/profiles.css">
<main class="profile-page">
  <div class="profile-toolbar" aria-label="${t('profiles.toolbar.actions_label')}">
    <div class="profile-pickers">
      <label for="profile-select" class="muted">${t('profiles.toolbar.revision')}</label>
      <select id="profile-select" aria-label="${t('profiles.toolbar.revision_label')}"><option>${t('profiles.status.loading_catalog')}</option></select>
      <label class="profile-revisions muted" for="profile-revisions"><input id="profile-revisions" type="checkbox">${t('profiles.toolbar.show_superseded')}</label>
    </div>
    <div class="profile-actions">
      <div class="profile-action-group" aria-label="${t('profiles.toolbar.editing_label')}">
        <button class="pbtn" id="profile-new" type="button">${t('profiles.action.new')}</button>
        <button class="pbtn" id="profile-edit" type="button" disabled>${t('profiles.action.edit')}</button>
        <button class="pbtn" id="profile-fork" type="button" disabled>${t('profiles.action.fork')}</button>
        <label class="pbtn" for="profile-import">${t('profiles.action.import')}<input id="profile-import" type="file" accept=".yaml,.yml,application/yaml,text/yaml" hidden></label>
        <button class="pbtn" id="profile-export" type="button">${t('profiles.action.export')}</button>
      </div>
      <span class="profile-action-break" aria-hidden="true"></span>
      <div class="profile-action-group" aria-label="${t('profiles.toolbar.review_label')}">
        <button class="pbtn primary" id="profile-validate" type="button" disabled>${t('profiles.action.validate_yaml')}</button>
        <button class="pbtn" id="profile-compare" type="button" disabled>${t('profiles.action.compare')}</button>
      </div>
      <div class="profile-action-group" aria-label="${t('profiles.toolbar.activation_label')}">
        <button class="pbtn primary" id="savebtn" type="button" disabled>${t('profiles.action.save_revision')}</button>
        <button class="pbtn primary" id="profile-activate" type="button"${hardenedApprovalAttrs(s)} disabled>${t('profiles.action.activate')}</button>
        <button class="pbtn" id="profile-auto" type="button"${hardenedApprovalAttrs(s)} disabled>${t('profiles.action.use_automatic')}</button>
        <button class="pbtn" id="profile-rollback" type="button"${hardenedApprovalAttrs(s)} disabled>${t('profiles.action.rollback')}</button>
        <button class="pbtn danger" id="profile-delete" type="button" disabled>${t('profiles.action.delete')}</button>
      </div>
    </div>
  </div>
  <div id="profile-badges" class="profile-badges" aria-label="${t('profiles.state.label')}"></div>
  <nav id="profile-links" class="profile-links" aria-label="${t('profiles.references.label')}" hidden></nav>
  <div id="profile-status" class="profile-status" role="status" aria-live="polite">${t('profiles.status.loading_catalog')}</div>
  <div class="profile-workspace">
    <section class="profile-editor-pane" aria-labelledby="profile-editor-title">
      <div class="profile-editor-head"><h2 id="profile-editor-title">${t('profiles.editor.title')}</h2><span id="profile-editor-meta" class="profile-editor-meta"></span></div>
      <div id="profile-editor"></div>
    </section>
    <aside class="profile-inspector" aria-labelledby="profile-inspector-title">
      <div class="profile-inspector-head"><h2 id="profile-inspector-title">${t('profiles.inspector.title')}</h2></div>
      <div class="profile-inspector-body">
        <section><h3>${t('profiles.section.catalog_runtime')}</h3><div id="profile-catalog-issues" class="profile-issues"></div></section>
        <section><h3>${t('profiles.section.validation')}</h3><div id="profile-issues" class="profile-issues"></div></section>
        <div class="profile-guidance" id="profile-shizuku-guidance"${hidden}>
          <p><b>${t('profiles.shizuku.title')}</b></p>
          <p>${t('profiles.shizuku.body')}</p>
          <p><a href="https://example.invalid/docs/shizuku" target="_blank" rel="noopener">${t('profiles.shizuku.guide')}</a></p>
        </div>
        <section><h3>${t('profiles.section.compared_active')}</h3><div id="profile-diff" class="profile-diff"></div></section>
        <section><h3>${t('profiles.section.observed')}</h3><p class="profile-report-note">${t('profiles.observed.note')}</p><div id="profile-report" class="profile-report"></div></section>
        <div class="profile-draft" id="profile-generic-draft"${hidden}>
          <p><b>${t('profiles.generic.title')}</b> ${t('profiles.generic.body')}</p>
          <p><button class="pbtn" id="profile-draft" type="button">${t('profiles.action.generate_draft')}</button> <button class="pbtn" id="profile-use-draft" type="button" hidden>${t('profiles.action.copy_draft')}</button></p>
        </div>
      </div>
    </aside>
  </div>
</main>
<div id="profile-modal" class="profile-modal" role="dialog" aria-modal="true" aria-labelledby="profile-modal-title" hidden>
  <div class="profile-modal-card"><h2 id="profile-modal-title">${t('profiles.modal.default_title')}</h2><pre id="profile-modal-detail"></pre>
    <div class="profile-modal-actions"><button class="pbtn" id="profile-modal-cancel" type="button">${t('profiles.action.cancel')}</button><button class="pbtn primary" id="profile-modal-confirm" type="button">${t('profiles.action.confirm')}</button></div>
  </div>
</div>
<script src="assets/vendor/profile-editor/codemirror.js"></script>
<script src="assets/profiles.js"></script>`;
}

// Mirror of hardenedApprovalAttrs() for an unconditional approval.
function hardenedApprovalAttrs(s) {
  return ` data-hardened-approval aria-describedby="hardened-approval-description" title="${s.t('configure.hardened.action_approval')}"`;
}

export function profileData() {
  return {
    ref: { id: 'generic', revision: '0123456789abcdef0123456789abcdef' },
    display_name: 'Generic panel profile with a deliberately long visible name',
    content_version: '2026.9.4', author: 'ha-paneld maintainers', origin: 'imported', maturity: 'verified',
    trusted_provenance: true, compatible: true, matches_this_device: true,
    active: false, selected: false, last_known_good: false,
    shizuku_recommendation: 'recommended', risks: ['root_paths', 'package_management', 'future_long_risk_token'],
    links: [
      { label: 'Device profile documentation with a long label', url: 'https://example.invalid/profiles/device-profile-documentation' },
      { label: 'Hardware evidence', url: 'https://example.invalid/evidence/hardware' },
    ],
    issues: [
      { severity: 'warning', path: 'provisioning.packages[12].desired_state', message: 'Compatibility prose', presentation_code: 'unknown-value', presentation_params: { value: 'unexpected_future_value' } },
      { severity: 'error', path: 'hardware.display.current_density_dpi', message: 'A deliberately long opaque parser diagnostic remains readable without changing API bytes.' },
    ],
  };
}

// The reporter's catalog shape: one profile iterated four times, each edit a separate immutable
// revision. The picker collapses these, so the toolbar it has to lay out is the collapsed one plus
// the toggle that reveals the rest -- both of which only exist when duplicates do.
export function supersededRevisions() {
  const base = profileData();
  return ['a', 'b', 'c', 'd'].map((suffix, index) => ({
    ...base,
    ref: { id: base.ref.id, revision: `${suffix.repeat(8)}0123456789abcdef0123456789abcdef` },
    content_version: `2026.9.${4 + index}`,
    imported_at: 1757000000000 + index * 3600000,
  }));
}

export const PROFILE_YAML = `schema: 1\nmetadata:\n  id: generic\n  display_name: Generic profile\n  version: 2026.9.4\n  author: ha-paneld maintainers\n  maturity: verified\nmatch:\n  any:\n    - all:\n        - field: model\n          op: contains\n          value: panel\nhardware:\n  display:\n    width_px: 1920\n    height_px: 1080\n`;

export const PROFILE_REPORT = { items: [
  { path: 'evidence.android_sdk', status: 'observed', value: '35' },
  { path: 'evidence.abis', status: 'observed', value: 'arm64-v8a, armeabi-v7a' },
  { path: 'evidence.display.current_density_dpi', status: 'observed', value: '640' },
  { path: 'evidence.cpu.available_governors', status: 'observed', value: 'schedutil, performance, powersave' },
  { path: 'evidence.some_future_opaque_path_with_a_long_name', status: 'unknown', value: 'some_future_opaque_value' },
] };

// Catalog and runtime issues the rich state shows in the inspector's first section.
const CATALOG_ISSUES = [
  { severity: 'warning', path: 'catalog.profiles[3]', message: 'Profile catalog issue', presentation_code: 'unknown-value', presentation_params: { value: 'unexpected_future_value' } },
  { severity: 'info', message: 'Runtime reports the active profile is applied.' },
];

/**
 * The /api/v1/profiles* responses by path. `rich` adds catalog issues; the old Profiles gate keeps the
 * healthy catalog it has always measured. A string is served as YAML.
 */
export function profilesApi(path, { rich = false } = {}) {
  if (path === '/api/v1/peers') return [];
  if (path === '/api/v1/profiles/schema') return { max_bytes: 131072, fields: [] };
  if (path === '/api/v1/profiles/report') return PROFILE_REPORT;
  if (path === '/api/v1/profiles') return {
    catalog_revision: 19, profiles: supersededRevisions(),
    status: { selection: { mode: 'manual' }, rollback_ref: { id: 'generic', revision: 'previous-revision' }, issues: rich ? CATALOG_ISSUES : [] },
  };
  if (path === '/api/v1/profiles/probe') return {
    compatible: true, content_sha256: 'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789',
    summary: supersededRevisions()[3],
    issues: profileData().issues,
    diff_from_active: [
      { path: 'hardware.display.current_density_dpi', before: '320', after: '640' },
      { path: 'provisioning.packages[12].desired_state', before: null, after: 'unexpected_future_value' },
    ],
    report: PROFILE_REPORT,
  };
  if (/^\/api\/v1\/profiles\/generic\/revisions\//.test(path)) return PROFILE_YAML;
  return {};
}

export default {
  name: 'profiles',
  path: '/profiles',
  html(context) {
    const { s } = context;
    return tabbedPage({ ...context, active: 'profiles', sectionTitle: s.text('shell.nav.profile'), body: profilesBody(s), prefixes: ['shell.', 'profiles.', 'runtime.'] });
  },
  api(url) {
    const payload = profilesApi(url.pathname, { rich: true });
    return typeof payload === 'string' ? { __raw: true, type: 'application/yaml; charset=utf-8', body: payload } : payload;
  },
  async ready(frame) {
    await frame.waitForFunction(() => document.querySelector('#profile-editor .cm-editor')
      && !document.querySelector('#profile-validate')?.disabled
      && document.querySelectorAll('#profile-report > *').length > 1
      && document.querySelectorAll('#profile-catalog-issues .profile-issue').length > 0);
  },
  async exercise(frame) {
    // Validate: fills the validation issues, the comparison and the valid status line.
    await frame.click('#profile-validate');
    await frame.waitForFunction(() => document.querySelectorAll('#profile-diff .profile-diff-row').length > 0
      && document.querySelector('#profile-status')?.classList.contains('ok'));
  },
};
