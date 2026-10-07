package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings

/** Runtime profile authoring. All content is hydrated through the guarded /api/v1/profile routes. */
internal fun profilesBody(strings: AppStrings): String = """
<link rel="stylesheet" href="assets/profiles.css">
<main class="profile-page">
  <div class="profile-toolbar" aria-label="${esc(strings.get("profiles.toolbar.actions_label"))}">
    <div class="profile-pickers">
      <label for="profile-select" class="muted">${esc(strings.get("profiles.toolbar.revision"))}</label>
      <select id="profile-select" aria-label="${esc(strings.get("profiles.toolbar.revision_label"))}"><option>${esc(strings.get("profiles.status.loading_catalog"))}</option></select>
      <label class="profile-revisions muted" for="profile-revisions"><input id="profile-revisions" type="checkbox">${esc(strings.get("profiles.toolbar.show_superseded"))}</label>
    </div>
    <div class="profile-actions">
      <div class="profile-action-group" aria-label="${esc(strings.get("profiles.toolbar.editing_label"))}">
        <button class="pbtn" id="profile-new" type="button">${esc(strings.get("profiles.action.new"))}</button>
        <button class="pbtn" id="profile-edit" type="button" disabled>${esc(strings.get("profiles.action.edit"))}</button>
        <button class="pbtn" id="profile-fork" type="button" disabled>${esc(strings.get("profiles.action.fork"))}</button>
        <label class="pbtn" for="profile-import">${esc(strings.get("profiles.action.import"))}<input id="profile-import" type="file" accept=".yaml,.yml,application/yaml,text/yaml" hidden></label>
        <button class="pbtn" id="profile-export" type="button">${esc(strings.get("profiles.action.export"))}</button>
      </div>
      <span class="profile-action-break" aria-hidden="true"></span>
      <div class="profile-action-group" aria-label="${esc(strings.get("profiles.toolbar.review_label"))}">
        <button class="pbtn primary" id="profile-validate" type="button" disabled>${esc(strings.get("profiles.action.validate_yaml"))}</button>
        <button class="pbtn" id="profile-compare" type="button" disabled>${esc(strings.get("profiles.action.compare"))}</button>
      </div>
      <div class="profile-action-group" aria-label="${esc(strings.get("profiles.toolbar.activation_label"))}">
        <button class="pbtn primary" id="savebtn" type="button" disabled>${esc(strings.get("profiles.action.save_revision"))}</button>
        <button class="pbtn primary" id="profile-activate" type="button"${hardenedApprovalAttrs(strings = strings)} disabled>${esc(strings.get("profiles.action.activate"))}</button>
        <button class="pbtn" id="profile-auto" type="button"${hardenedApprovalAttrs(strings = strings)} disabled>${esc(strings.get("profiles.action.use_automatic"))}</button>
        <button class="pbtn" id="profile-rollback" type="button"${hardenedApprovalAttrs(strings = strings)} disabled>${esc(strings.get("profiles.action.rollback"))}</button>
        <button class="pbtn danger" id="profile-delete" type="button" disabled>${esc(strings.get("profiles.action.delete"))}</button>
      </div>
    </div>
  </div>
  <div id="profile-badges" class="profile-badges" aria-label="${esc(strings.get("profiles.state.label"))}"></div>
  <nav id="profile-links" class="profile-links" aria-label="${esc(strings.get("profiles.references.label"))}" hidden></nav>
  <div id="profile-status" class="profile-status" role="status" aria-live="polite">${esc(strings.get("profiles.status.loading_catalog"))}</div>
  <div class="profile-workspace">
    <section class="profile-editor-pane" aria-labelledby="profile-editor-title">
      <div class="profile-editor-head"><h2 id="profile-editor-title">${esc(strings.get("profiles.editor.title"))}</h2><span id="profile-editor-meta" class="profile-editor-meta"></span></div>
      <div id="profile-editor"></div>
    </section>
    <aside class="profile-inspector" aria-labelledby="profile-inspector-title">
      <div class="profile-inspector-head"><h2 id="profile-inspector-title">${esc(strings.get("profiles.inspector.title"))}</h2></div>
      <div class="profile-inspector-body">
        <section><h3>${esc(strings.get("profiles.section.catalog_runtime"))}</h3><div id="profile-catalog-issues" class="profile-issues"></div></section>
        <section><h3>${esc(strings.get("profiles.section.validation"))}</h3><div id="profile-issues" class="profile-issues"></div></section>
        <section><h3>${esc(strings.get("profiles.section.compared_active"))}</h3><div id="profile-diff" class="profile-diff"></div></section>
        <section><h3>${esc(strings.get("profiles.section.observed"))}</h3><p class="profile-report-note">${esc(strings.get("profiles.observed.note"))}</p><div id="profile-report" class="profile-report"></div></section>
        <div class="profile-draft" id="profile-generic-draft" hidden>
          <p><b>${esc(strings.get("profiles.generic.title"))}</b> ${esc(strings.get("profiles.generic.body"))}</p>
          <p><button class="pbtn" id="profile-draft" type="button">${esc(strings.get("profiles.action.generate_draft"))}</button> <button class="pbtn" id="profile-use-draft" type="button" hidden>${esc(strings.get("profiles.action.copy_draft"))}</button></p>
        </div>
      </div>
    </aside>
  </div>
</main>
<div id="profile-modal" class="profile-modal" role="dialog" aria-modal="true" aria-labelledby="profile-modal-title" hidden>
  <div class="profile-modal-card"><h2 id="profile-modal-title">${esc(strings.get("profiles.modal.default_title"))}</h2><pre id="profile-modal-detail"></pre>
    <div class="profile-modal-actions"><button class="pbtn" id="profile-modal-cancel" type="button">${esc(strings.get("profiles.action.cancel"))}</button><button class="pbtn primary" id="profile-modal-confirm" type="button">${esc(strings.get("profiles.action.confirm"))}</button></div>
  </div>
</div>
<script src="assets/vendor/profile-editor/codemirror.js"></script>
<script src="assets/profiles.js"></script>"""
