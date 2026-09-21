(function () {
  "use strict";

  var API = "api/v1/profiles";
  var model = {
    catalogRevision: 0,
    profiles: [],
    status: {},
    selected: null,
    showAllRevisions: false,
    source: "",
    originalSource: "",
    preview: null,
    editable: false,
    loading: false,
    editor: null,
    sourceLoaded: false,
    maxBytes: 128 * 1024,
    viewGeneration: 0,
  };
  var suppressEditorChange = false;
  var PROFILE_EDITOR_MIN_LINES = 12;
  var profileLayoutFrame = 0;
  var own = Object.prototype.hasOwnProperty;

  // A missing or malformed request-local projection must leave the complete English editor usable.
  function fallbackText(fallback, values) {
    return String(fallback == null ? "" : fallback).replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g,
      function (placeholder, name) {
        return values && own.call(values, name) ? String(values[name]) : placeholder;
      });
  }
  function t(key, fallback, values) {
    var english = fallbackText(fallback, values);
    if (window.HaI18n && typeof window.HaI18n.t === "function") {
      try {
        var localized = window.HaI18n.t(key, fallback, values);
        return typeof localized === "string" ? localized : english;
      } catch (_) { return english; }
    }
    return english;
  }
  function locale() {
    return window.HaI18n && typeof window.HaI18n.locale === "string"
      ? window.HaI18n.locale : (document.documentElement.lang || "en");
  }
  // CodeMirror uses these exact, case-sensitive English phrases as stable lookup identities.
  // Keep the finite map here so request-local catalogue projection supplies all bundled editor chrome.
  function editorPhrases() {
    return {
      "Find": t("profiles.editor.codemirror.find", "Find"),
      "Replace": t("profiles.editor.codemirror.replace", "Replace"),
      "next": t("profiles.editor.codemirror.next", "next"),
      "previous": t("profiles.editor.codemirror.previous", "previous"),
      "all": t("profiles.editor.codemirror.all", "all"),
      "match case": t("profiles.editor.codemirror.match_case", "match case"),
      "regexp": t("profiles.editor.codemirror.regexp", "regexp"),
      "by word": t("profiles.editor.codemirror.by_word", "by word"),
      "replace": t("profiles.editor.codemirror.replace_action", "replace"),
      "replace all": t("profiles.editor.codemirror.replace_all", "replace all"),
      "replaced match on line $": t("profiles.editor.codemirror.replaced_match_on_line", "replaced match on line $"),
      "replaced $ matches": t("profiles.editor.codemirror.replaced_matches", "replaced $ matches"),
      "close": t("profiles.editor.codemirror.close", "close"),
      "Go to line": t("profiles.editor.codemirror.go_to_line", "Go to line"),
      "go": t("profiles.editor.codemirror.go", "go"),
      "Control character": t("profiles.editor.codemirror.control_character", "Control character"),
      "folded code": t("profiles.editor.codemirror.folded_code", "folded code"),
      "Fold line": t("profiles.editor.codemirror.fold_line", "Fold line"),
      "Unfold line": t("profiles.editor.codemirror.unfold_line", "Unfold line"),
      "unfold": t("profiles.editor.codemirror.unfold", "unfold"),
      "Diagnostics": t("profiles.editor.codemirror.diagnostics", "Diagnostics"),
      "No diagnostics": t("profiles.editor.codemirror.no_diagnostics", "No diagnostics"),
      "Selection deleted": t("profiles.editor.codemirror.selection_deleted", "Selection deleted"),
      "Completions": t("profiles.editor.codemirror.completions", "Completions"),
      "current match": t("profiles.editor.codemirror.current_match", "current match"),
      "on line": t("profiles.editor.codemirror.on_line", "on line"),
    };
  }
  function closedText(value, translations) {
    var raw = string(value);
    var lookup = raw.toLowerCase();
    return own.call(translations, lookup) ? translations[lookup]() : raw;
  }
  function localizedList(values) {
    try { return new Intl.ListFormat(locale(), { style: "long", type: "conjunction" }).format(values); }
    catch (_) { return values.join(", "); }
  }
  function riskText(risk) {
    var labels = {
      root_paths: function () { return t("profiles.risk.root_paths", "root paths"); },
      relay_or_gpio_writes: function () { return t("profiles.risk.relay_or_gpio_writes", "relay or GPIO writes"); },
      evdev_read: function () { return t("profiles.risk.evdev_read", "evdev read"); },
      evdev_grab: function () { return t("profiles.risk.evdev_grab", "evdev grab"); },
      package_disable_recommendations: function () { return t("profiles.risk.package_disable_recommendations", "package-disable recommendations"); },
      webview_install: function () { return t("profiles.risk.webview_install", "WebView install"); },
      overrides_bundled: function () { return t("profiles.risk.overrides_bundled", "overrides bundled profile"); },
    };
    return closedText(risk, labels);
  }
  function presentedText(owner, compatibility) {
    var fallback = string(compatibility);
    if (!owner || typeof owner !== "object" || Array.isArray(owner)) return fallback;
    if (typeof owner.presentation_code !== "string") return fallback;
    var code = owner.presentation_code;
    var params = owner.presentation_params;
    if (!own.call(PRESENTATIONS, code) || !params || typeof params !== "object" || Array.isArray(params)) return fallback;
    var expected = own.call(PRESENTATION_PARAMS, code) ? PRESENTATION_PARAMS[code] : [];
    var names = Object.keys(params);
    if (names.length !== expected.length || expected.some(function (name) {
      var value = params[name];
      return !own.call(params, name) || typeof value !== "string" || value.length > 512;
    })) return fallback;
    return t(PRESENTATIONS[code], fallback, params);
  }
  function presentedError(error, compatibility) {
    return presentedText(error && error.body, compatibility == null ? error && error.message : compatibility);
  }

  // Populated only with backend-owned stable semantic codes. Unknown or malformed metadata always
  // returns the exact compatibility message rather than constructing a translation key from input.
  var PRESENTATIONS = Object.freeze({
    "preview-token-required": "profiles.result.preview-token-required",
    "explicit-confirmation-required": "profiles.result.explicit-confirmation-required",
    "expected-catalog-revision-required": "profiles.result.expected-catalog-revision-required",
    "invalid-profile-ref": "profiles.result.invalid-profile-ref",
    "invalid-delete-request": "profiles.result.invalid-delete-request",
    "profile-ref-both-forms-supplied": "profiles.result.profile-ref-both-forms-supplied",
    "yaml-content-type-required": "profiles.result.yaml-content-type-required",
    "json-content-type-required": "profiles.result.json-content-type-required",
    "profile-yaml-too-large": "profiles.result.profile-yaml-too-large",
    "profile-action-too-large": "profiles.result.profile-action-too-large",
    "profile-body-timeout": "profiles.result.profile-body-timeout",
    "invalid-utf8": "profiles.result.invalid-utf8",
    "invalid-json": "profiles.result.invalid-json",
    "destructive-operation-in-progress": "profiles.result.destructive-operation-in-progress",
    "profile-restart-unavailable": "profiles.result.profile-restart-unavailable",
    "profile-activation-abort-persist-failed": "profiles.result.profile-activation-abort-persist-failed",
    "profile-imported": "profiles.result.profile-imported",
    "profile-selection-unchanged": "profiles.result.profile-selection-unchanged",
    "profile-selection-staged": "profiles.result.profile-selection-staged",
    "profile-revision-deleted": "profiles.result.profile-revision-deleted",
    "activation-pending": "profiles.result.activation-pending",
    "activation-applying-selected": "profiles.result.activation-applying-selected",
    "activation-applying-auto-update": "profiles.result.activation-applying-auto-update",
    "activation-applying-bundled-revision": "profiles.result.activation-applying-bundled-revision",
    "preview-token-invalid": "profiles.result.preview-token-invalid",
    "imported-catalog-revision-limit": "profiles.result.imported-catalog-revision-limit",
    "imported-profile-revision-limit": "profiles.result.imported-profile-revision-limit",
    "imported-catalog-byte-limit": "profiles.result.imported-catalog-byte-limit",
    "catalog-reservation-failed": "profiles.result.catalog-reservation-failed",
    "profile-store-failed": "profiles.result.profile-store-failed",
    "profile-revision-not-found": "profiles.result.profile-revision-not-found",
    "profile-incompatible": "profiles.result.profile-incompatible",
    "activation-in-progress": "profiles.result.activation-in-progress",
    "selection-persist-failed": "profiles.result.selection-persist-failed",
    "rollback-unavailable": "profiles.result.rollback-unavailable",
    "bundled-profile-delete-forbidden": "profiles.result.bundled-profile-delete-forbidden",
    "referenced-profile-delete-forbidden": "profiles.result.referenced-profile-delete-forbidden",
    "profile-delete-failed": "profiles.result.profile-delete-failed",
    "catalog-stale": "profiles.result.catalog-stale",
    "expected-mapping": "profiles.issue.expected-mapping",
    "expected-list": "profiles.issue.expected-list",
    "expected-string": "profiles.issue.expected-string",
    "expected-boolean": "profiles.issue.expected-boolean",
    "expected-integer": "profiles.issue.expected-integer",
    "expected-finite-number": "profiles.issue.expected-finite-number",
    "expected-integer-or-strategy": "profiles.issue.expected-integer-or-strategy",
    "expected-32-bit-integer-or-strategy": "profiles.issue.expected-32-bit-integer-or-strategy",
    "required-mapping": "profiles.issue.required-mapping",
    "required-list": "profiles.issue.required-list",
    "required-string": "profiles.issue.required-string",
    "required-boolean": "profiles.issue.required-boolean",
    "required-integer": "profiles.issue.required-integer",
    "unknown-field": "profiles.issue.unknown-field",
    "unknown-value": "profiles.issue.unknown-value",
    "unsupported-yaml-type": "profiles.issue.unsupported-yaml-type",
    "bounded-text": "profiles.issue.bounded-text",
    "bounded-text-basic": "profiles.issue.bounded-text-basic",
    "duplicate-profile-link-url": "profiles.issue.duplicate-profile-link-url",
    "duplicate-profile-link-label": "profiles.issue.duplicate-profile-link-label",
    "duplicate-cpu-architecture": "profiles.issue.duplicate-cpu-architecture",
    "unknown-core-driver": "profiles.issue.unknown-core-driver",
    "unknown-su-form": "profiles.issue.unknown-su-form",
    "unknown-led-mechanism": "profiles.issue.unknown-led-mechanism",
    "unknown-core-transfer": "profiles.issue.unknown-core-transfer",
    "unknown-screen-off-route": "profiles.issue.unknown-screen-off-route",
    "core-version-required": "profiles.issue.core-version-required",
    "unsupported-schema": "profiles.issue.unsupported-schema",
    "invalid-https-url": "profiles.issue.invalid-https-url",
    "unsupported-privileged-path": "profiles.issue.unsupported-privileged-path",
    "profile-template-unavailable": "profiles.result.profile-template-unavailable",
    "passive-device-draft-unavailable": "profiles.result.passive-device-draft-unavailable",
    "passive-report-unavailable": "profiles.result.passive-report-unavailable",
    "profile-administration-unavailable": "profiles.result.profile-administration-unavailable",
    "profile-source-byte-limit": "profiles.issue.profile-source-byte-limit",
    "profile-source-empty": "profiles.issue.profile-source-empty",
    "yaml-single-document-required": "profiles.issue.yaml-single-document-required",
    "yaml-nesting-too-deep": "profiles.issue.yaml-nesting-too-deep",
    "yaml-nesting-depth-limit": "profiles.issue.yaml-nesting-depth-limit",
    "yaml-string-length-limit": "profiles.issue.yaml-string-length-limit",
    "yaml-map-entry-limit": "profiles.issue.yaml-map-entry-limit",
    "yaml-mapping-key-string-required": "profiles.issue.yaml-mapping-key-string-required",
    "yaml-list-entry-limit": "profiles.issue.yaml-list-entry-limit",
    "yaml-parser-event-limit": "profiles.issue.yaml-parser-event-limit",
    "profile-id-invalid": "profiles.issue.profile-id-invalid",
    "semantic-version-required": "profiles.issue.semantic-version-required",
    "profile-link-count-limit": "profiles.issue.profile-link-count-limit",
    "unicode-format-controls-forbidden": "profiles.issue.unicode-format-controls-forbidden",
    "introduced-year-range": "profiles.issue.introduced-year-range",
    "cpu-cluster-count-limit": "profiles.issue.cpu-cluster-count-limit",
    "cpu-core-count-range": "profiles.issue.cpu-core-count-range",
    "cpu-total-count-limit": "profiles.issue.cpu-total-count-limit",
    "license-expression-invalid": "profiles.issue.license-expression-invalid",
    "tested-firmware-bounds": "profiles.issue.tested-firmware-bounds",
    "limitations-bounds": "profiles.issue.limitations-bounds",
    "match-priority-range": "profiles.issue.match-priority-range",
    "generic-fallback-only": "profiles.issue.generic-fallback-only",
    "match-group-required": "profiles.issue.match-group-required",
    "match-group-count-limit": "profiles.issue.match-group-count-limit",
    "match-predicate-required": "profiles.issue.match-predicate-required",
    "match-predicate-count-limit": "profiles.issue.match-predicate-count-limit",
    "match-values-count-range": "profiles.issue.match-values-count-range",
    "match-value-invalid": "profiles.issue.match-value-invalid",
    "dotted-release-version-required": "profiles.issue.dotted-release-version-required",
    "app-su-needs-su-form": "profiles.issue.app-su-needs-su-form",
    "su-blpower-needs-app-su": "profiles.issue.su-blpower-needs-app-su",
    "daemon-blpower-sandbox-only": "profiles.issue.daemon-blpower-sandbox-only",
    "daemon-led-sandbox-only": "profiles.issue.daemon-led-sandbox-only",
    "relay-fallback-count-limit": "profiles.issue.relay-fallback-count-limit",
    "relay-paths-unique": "profiles.issue.relay-paths-unique",
    "gpio-block-base-range": "profiles.issue.gpio-block-base-range",
    "gpio-range": "profiles.issue.gpio-range",
    "room-temperature-offset-range": "profiles.issue.room-temperature-offset-range",
    "unknown-core-strategy": "profiles.issue.unknown-core-strategy",
    "unknown-core-strategy-value": "profiles.issue.unknown-core-strategy-value",
    "density-range": "profiles.issue.density-range",
    "font-scale-range": "profiles.issue.font-scale-range",
    "physical-ppi-range": "profiles.issue.physical-ppi-range",
    "touch-click-gain-range": "profiles.issue.touch-click-gain-range",
    "evdev-mapping-count-limit": "profiles.issue.evdev-mapping-count-limit",
    "evdev-device-node-invalid": "profiles.issue.evdev-device-node-invalid",
    "linux-input-code-range": "profiles.issue.linux-input-code-range",
    "keycode-format-invalid": "profiles.issue.keycode-format-invalid",
    "duplicate-evdev-mapping": "profiles.issue.duplicate-evdev-mapping",
    "unknown-ha-cpu-tier": "profiles.issue.unknown-ha-cpu-tier",
    "linux-governor-name-invalid": "profiles.issue.linux-governor-name-invalid",
    "unknown-webview-artifact": "profiles.issue.unknown-webview-artifact",
    "package-count-limit": "profiles.issue.package-count-limit",
    "android-package-name-invalid": "profiles.issue.android-package-name-invalid",
    "duplicate-package-desired-state": "profiles.issue.duplicate-package-desired-state",
    "package-tag-bounds": "profiles.issue.package-tag-bounds",
    "package-note-length-limit": "profiles.issue.package-note-length-limit",
    "recipe-count-limit": "profiles.issue.recipe-count-limit",
    "duplicate-recipe-selection": "profiles.issue.duplicate-recipe-selection",
    "unknown-core-recipe": "profiles.issue.unknown-core-recipe",
    "capability-driver-required": "profiles.issue.capability-driver-required",
    "unused-driver-declared": "profiles.issue.unused-driver-declared",
    "activation-applying-persist-failed": "profiles.issue.activation-applying-persist-failed",
    "activation-rolled-back-unhealthy-auto": "profiles.result.activation-rolled-back-unhealthy-auto",
    "activation-rolled-back-unhealthy-pinned": "profiles.result.activation-rolled-back-unhealthy-pinned",
    "activation-unhealthy-rollback-complete": "profiles.issue.activation-unhealthy-rollback-complete",
    "activation-unhealthy-rollback-persist-failed": "profiles.issue.activation-unhealthy-rollback-persist-failed",
    "activation-auto-update-stage-failed": "profiles.issue.activation-auto-update-stage-failed",
    "activation-rolled-back-unresolved": "profiles.result.activation-rolled-back-unresolved",
    "activation-unresolved-selection-restored": "profiles.issue.activation-unresolved-selection-restored",
    "activation-unresolved-rollback-persist-failed": "profiles.issue.activation-unresolved-rollback-persist-failed",
    "activation-rolled-back-incompatible": "profiles.result.activation-rolled-back-incompatible",
    "activation-incompatible-selection-restored": "profiles.issue.activation-incompatible-selection-restored",
    "activation-incompatible-recovery-persist-failed": "profiles.issue.activation-incompatible-recovery-persist-failed",
    "pinned-successor-held": "profiles.issue.pinned-successor-held",
    "pinned-revision-retired": "profiles.issue.pinned-revision-retired",
    "repin-persist-failed-auto": "profiles.issue.repin-persist-failed-auto",
    "repin-persist-failed-pinned": "profiles.issue.repin-persist-failed-pinned",
    "catalog-fallback-invalid-emergency-used": "profiles.issue.catalog-fallback-invalid-emergency-used",
    "required-profile-read-failed": "profiles.issue.required-profile-read-failed",
    "imported-path-noncanonical": "profiles.issue.imported-path-noncanonical",
    "imported-file-size-limit": "profiles.issue.imported-file-size-limit",
    "imported-catalog-count-quota": "profiles.issue.imported-catalog-count-quota",
    "imported-profile-count-quota": "profiles.issue.imported-profile-count-quota",
    "imported-catalog-byte-quota": "profiles.issue.imported-catalog-byte-quota",
    "imported-profile-read-failed": "profiles.issue.imported-profile-read-failed",
    "activation-device-mismatch": "profiles.issue.activation-device-mismatch",
    "activation-touchscreen-grab-forbidden": "profiles.issue.activation-touchscreen-grab-forbidden",
    "imported-filename-hash-mismatch": "profiles.issue.imported-filename-hash-mismatch",
    "imported-document-id-mismatch": "profiles.issue.imported-document-id-mismatch",
    "duplicate-revision-ignored": "profiles.issue.duplicate-revision-ignored",
    "pinned-revision-missing": "profiles.issue.pinned-revision-missing",
    "pinned-revision-incompatible": "profiles.issue.pinned-revision-incompatible",
    "bundled-generic-fallback-missing": "profiles.issue.bundled-generic-fallback-missing",
    "ambiguous-automatic-match": "profiles.issue.ambiguous-automatic-match",
    "emergency-profile-in-use": "profiles.issue.emergency-profile-in-use",
  });
  var PRESENTATION_PARAMS = Object.freeze({
    "profile-imported": Object.freeze(["display_name","version"]),
    "imported-catalog-revision-limit": Object.freeze(["max"]),
    "imported-profile-revision-limit": Object.freeze(["id","max"]),
    "imported-catalog-byte-limit": Object.freeze(["max"]),
    "unknown-value": Object.freeze(["value"]),
    "unsupported-yaml-type": Object.freeze(["type"]),
    "bounded-text": Object.freeze(["min","max"]),
    "bounded-text-basic": Object.freeze(["min","max"]),
    "unknown-core-driver": Object.freeze(["value"]),
    "unknown-su-form": Object.freeze(["value"]),
    "unknown-led-mechanism": Object.freeze(["value"]),
    "unknown-core-transfer": Object.freeze(["value"]),
    "unknown-screen-off-route": Object.freeze(["value"]),
    "core-version-required": Object.freeze(["required","current"]),
    "unsupported-schema": Object.freeze(["actual","expected"]),
    "unsupported-privileged-path": Object.freeze(["allowed"]),
    "profile-source-byte-limit": Object.freeze(["max"]),
    "yaml-nesting-depth-limit": Object.freeze(["max"]),
    "yaml-string-length-limit": Object.freeze(["max"]),
    "yaml-map-entry-limit": Object.freeze(["max"]),
    "yaml-list-entry-limit": Object.freeze(["max"]),
    "unknown-core-strategy-value": Object.freeze(["value"]),
    "unknown-webview-artifact": Object.freeze(["value"]),
    "unknown-core-recipe": Object.freeze(["value"]),
    "capability-driver-required": Object.freeze(["value"]),
    "unused-driver-declared": Object.freeze(["value"]),
    "activation-rolled-back-unhealthy-pinned": Object.freeze(["id","revision"]),
    "activation-incompatible-selection-restored": Object.freeze(["id","revision"]),
    "activation-incompatible-recovery-persist-failed": Object.freeze(["id","revision"]),
    "pinned-successor-held": Object.freeze(["id","retired_revision","current_revision"]),
    "pinned-revision-retired": Object.freeze(["id","retired_revision","current_revision"]),
    "repin-persist-failed-pinned": Object.freeze(["id","revision"]),
    "imported-document-id-mismatch": Object.freeze(["document_id","storage_id"]),
    "ambiguous-automatic-match": Object.freeze(["priority","ids"]),
  });
  var SCHEMA_DESCRIPTION_KEYS = Object.freeze({
    "metadata.maturity": "profiles.schema.description.metadata_maturity",
    "match.any[].all[].field": "profiles.schema.description.match_field",
    "match.any[].all[].op": "profiles.schema.description.match_operator",
    "platform.su_form": "profiles.schema.description.su_form",
    "hardware.led.mechanism": "profiles.schema.description.led_mechanism",
    "hardware.led.transfer": "profiles.schema.description.led_transfer",
    "hardware.screen_off": "profiles.schema.description.screen_off",
    "identity.model_label_strategy": "profiles.schema.description.model_label_strategy",
    "provisioning.access.shizuku": "profiles.schema.description.shizuku_recommendation",
    "provisioning.software.webview.artifact": "profiles.schema.description.webview_artifact",
    "provisioning.packages[].desired_state": "profiles.schema.description.package_desired_state",
    "provisioning.packages[].importance": "profiles.schema.description.package_importance",
    "provisioning.recipes[].id": "profiles.schema.description.recipe_id",
  });
  function localizedSchemaFields(fields) {
    if (!Array.isArray(fields)) return [];
    return fields.map(function (field) {
      if (!field || typeof field !== "object" || Array.isArray(field)) return field;
      var clone = Object.assign({}, field);
      var path = string(field.path);
      if (own.call(SCHEMA_DESCRIPTION_KEYS, path) && typeof field.description === "string") {
        clone.description = t(SCHEMA_DESCRIPTION_KEYS[path], field.description);
      }
      return clone;
    });
  }

  function byId(id) { return document.getElementById(id); }
  function string(value) { return value == null ? "" : String(value); }
  function fitProfileWorkspace() {
    profileLayoutFrame = 0;
    var workspace = document.querySelector(".profile-workspace");
    var editor = byId("profile-editor");
    var editorHead = document.querySelector(".profile-editor-head");
    var inspector = document.querySelector(".profile-inspector");
    var inspectorHead = document.querySelector(".profile-inspector-head");
    var inspectorBody = document.querySelector(".profile-inspector-body");
    if (!workspace || !editor || !editorHead || !inspector || !inspectorHead || !inspectorBody) return;
    if (window.matchMedia && window.matchMedia("(max-width:857px)").matches) {
      workspace.style.removeProperty("height");
      return;
    }
    var content = editor.querySelector(".cm-content") || byId("profile-source-fallback") || editor;
    var style = window.getComputedStyle(content);
    var fontSize = parseFloat(style.fontSize) || 13;
    var lineHeight = parseFloat(style.lineHeight);
    if (!isFinite(lineHeight)) lineHeight = fontSize * 1.45;
    var minEditorHeight = Math.ceil(PROFILE_EDITOR_MIN_LINES * lineHeight);
    var rowGap = parseFloat(window.getComputedStyle(workspace).rowGap) || 0;
    var minInspectorHeight = Math.ceil(inspectorHead.getBoundingClientRect().height) +
      Math.ceil(inspectorBody.scrollHeight) + 2;
    var minWorkspaceHeight = Math.ceil(editorHead.getBoundingClientRect().height) + minEditorHeight +
      minInspectorHeight + rowGap + 2;
    var documentTop = workspace.getBoundingClientRect().top + window.scrollY;
    var wrap = workspace.closest(".wrap");
    var bottomInset = wrap ? parseFloat(window.getComputedStyle(wrap).paddingBottom) || 0 : 0;
    var availableHeight = Math.floor(window.innerHeight - documentTop - Math.max(12, bottomInset));
    workspace.style.height = Math.max(minWorkspaceHeight, availableHeight) + "px";
  }
  function scheduleProfileLayout() {
    if (profileLayoutFrame) window.cancelAnimationFrame(profileLayoutFrame);
    profileLayoutFrame = window.requestAnimationFrame(fitProfileWorkspace);
  }
  function bindProfileLayout() {
    window.addEventListener("resize", scheduleProfileLayout);
    if (window.ResizeObserver) {
      var observer = new ResizeObserver(scheduleProfileLayout);
      [".profile-toolbar", ".profile-badges", ".profile-links", ".profile-status", ".profile-inspector-body"].forEach(function (selector) {
        var node = document.querySelector(selector); if (node) observer.observe(node);
      });
    }
    scheduleProfileLayout();
  }
  function refKey(ref) { return ref ? string(ref.id) + "@" + string(ref.revision) : ""; }
  function selectedSummary() {
    var key = refKey(model.selected);
    return model.profiles.find(function (item) { return refKey(item.ref) === key; }) || null;
  }
  function reviewedSummary() {
    if (model.preview && model.preview.source === model.source && model.preview.summary) return model.preview.summary;
    if (model.editable && isDirty()) return null;
    return selectedSummary();
  }
  function summaryForRef(ref) {
    var key = refKey(ref);
    return model.profiles.find(function (item) { return refKey(item.ref) === key; }) || null;
  }
  function setStatus(message, kind) {
    var out = byId("profile-status");
    if (!out) return;
    out.textContent = message || "";
    out.className = "profile-status" + (kind ? " " + kind : "");
  }
  function httpError(response, body) {
    var error = new Error(string(body && (body.message || body.error)) || t("profiles.error.http", "HTTP {status}", { status: response.status }));
    error.status = response.status;
    error.body = body;
    return error;
  }
  function approvalMessage(body) {
    return string(body && body.message) || t("profiles.approval.retry", "Approve this request on the panel, then retry it.");
  }
  function textError(response, message) {
    var body = { message: message };
    var code = response.headers.get("X-Profile-Presentation-Code");
    var encodedParams = response.headers.get("X-Profile-Presentation-Params");
    if (code != null) body.presentation_code = code;
    if (encodedParams != null) {
      try { body.presentation_params = JSON.parse(encodedParams); }
      catch (_) { body.presentation_params = null; }
    }
    return httpError(response, body);
  }
  function jsonFetch(url, options) {
    return fetch(url, options).then(function (response) {
      return response.text().then(function (text) {
        var body = {};
        try { body = text ? JSON.parse(text) : {}; } catch (_) { body = { message: text }; }
        // Profile selection normally succeeds with HTTP 202 too. The structured error field is what
        // distinguishes a staged restart from a Hardened-mode physical approval challenge.
        if (response.status === 202 && body && body.error === "approval-required") throw httpError(response, body);
        if (!response.ok) throw httpError(response, body);
        return body;
      });
    });
  }
  function yamlFetch(url) {
    return fetch(url, { headers: { "Accept": "application/yaml, text/yaml, text/plain" } }).then(function (response) {
      if (!response.ok) return response.text().then(function (text) { throw textError(response, text); });
      return response.text();
    });
  }
  function postYaml(path, yaml, token) {
    var headers = { "Accept": "application/json", "Content-Type": "application/yaml; charset=utf-8" };
    if (token) headers["X-Profile-Preview-Token"] = token;
    return jsonFetch(API + path, { method: "POST", headers: headers, body: yaml });
  }
  function postJson(path, body) {
    return jsonFetch(API + path, {
      method: "POST",
      headers: { "Accept": "application/json", "Content-Type": "application/json" },
      body: JSON.stringify(body || {}),
    });
  }

  function initEditor() {
    var host = byId("profile-editor");
    if (!host) return;
    if (window.ProfileCodeEditor && typeof window.ProfileCodeEditor.create === "function") {
      try {
        model.editor = window.ProfileCodeEditor.create(host, {
          value: "",
          readOnly: true,
          phrases: editorPhrases(),
          invalidDiagnostic: t("profiles.validation.invalid_profile", "Invalid profile"),
          onChange: editorChanged,
        });
        return;
      } catch (_) {}
    }
    var fallback = document.createElement("textarea");
    fallback.id = "profile-source-fallback";
    fallback.setAttribute("aria-label", t("profiles.editor.title", "Profile YAML"));
    fallback.spellcheck = false;
    fallback.readOnly = true;
    fallback.addEventListener("input", function () { editorChanged(fallback.value); });
    host.appendChild(fallback);
    model.editor = {
      getValue: function () { return fallback.value; },
      setValue: function (value) { fallback.value = string(value); },
      setReadOnly: function (value) { fallback.readOnly = !!value; },
      setSchema: function () {},
      setDiagnostics: function () {},
      focus: function () { fallback.focus(); },
    };
  }
  function editorChanged(value) {
    model.source = string(value);
    if (suppressEditorChange) return;
    model.viewGeneration++;
    model.loading = false;
    model.preview = null;
    renderIssues([{ severity: "info", message: t("profiles.status.yaml_changed_issue", "YAML changed; validate it to refresh issues and comparison.") }]);
    renderDiff(null);
    setStatus(t("profiles.status.yaml_changed", "YAML changed. Validate before saving or activating."));
    updateActions();
  }
  function setEditor(value, editable) {
    model.source = string(value);
    model.originalSource = string(value);
    model.editable = !!editable;
    model.preview = null;
    model.sourceLoaded = true;
    model.viewGeneration++;
    suppressEditorChange = true;
    model.editor.setValue(model.source);
    model.editor.setReadOnly(!model.editable);
    model.editor.setDiagnostics([]);
    suppressEditorChange = false;
    var useDraft = byId("profile-use-draft"); if (useDraft) useDraft.hidden = true;
    updateActions();
  }
  function isDirty() { return model.editable && model.source !== model.originalSource; }
  function confirmDiscard() { return !isDirty() || confirm(t("profiles.confirm.discard", "Discard this unsaved profile edit?")); }

  // A profile whose YAML is iterated during bring-up accumulates one immutable revision per edit,
  // and every one of them used to render as a sibling option distinguished only by a sha256 prefix.
  // Revisions are grouped by profile id and only the representative of each group is offered by
  // default; the rest stay in the catalogue, stay selectable through the revisions toggle, and are
  // never removed by anything here. Nothing about this changes what the panel runs.
  function catalogGroups(profiles) {
    var order = [];
    var byId = {};
    profiles.forEach(function (profile) {
      var id = string(profile.ref && profile.ref.id);
      if (!own.call(byId, id)) { byId[id] = []; order.push(id); }
      byId[id].push(profile);
    });
    return order.map(function (id) {
      var revisions = byId[id];
      // Only imported revisions collapse. A bundled id can also list more than one entry -- a
      // retired copy is kept beside its successor so a pinned revision stays resolvable -- and
      // hiding one of those would hide a rollback destination the panel may need. Iterating on an
      // imported YAML is the defect; shipped content is not it.
      var collapsible = revisions.every(function (item) { return string(item.origin).toLowerCase() === "imported"; });
      return {
        id: id,
        revisions: revisions,
        collapsible: collapsible,
        representative: collapsible ? representativeRevision(revisions) : null,
      };
    });
  }
  // Newest wins, but only where "newest" is a fact: import times are absent on bundled content and
  // are rewritten wholesale by a backup restore, so equal or missing times fall back to the list's
  // own deterministic order rather than to an invented one. What the panel is actually running
  // outranks both, so an active or selected revision always represents its own group and can never
  // be the one the collapse hides.
  function representativeRevision(revisions) {
    var active = revisions.find(function (item) { return item.active; });
    if (active) return active;
    var selected = revisions.find(function (item) { return item.selected; });
    if (selected) return selected;
    return revisions.reduce(function (best, item) {
      var bestAt = Number(best.imported_at) || 0;
      var itemAt = Number(item.imported_at) || 0;
      return itemAt > bestAt ? item : best;
    }, revisions[0]);
  }
  // A ten-character hash told the reporter nothing about which of four near-identical entries was
  // which. The declared version and the import time are what a person iterating on a YAML actually
  // recognises; the hash tail stays last so two same-version same-minute revisions remain distinct.
  function revisionLabel(profile) {
    var parts = [];
    var version = string(profile.content_version);
    if (version) parts.push(version);
    var importedAt = Number(profile.imported_at);
    if (importedAt > 0) {
      var rendered = "";
      try { rendered = new Date(importedAt).toLocaleString(); } catch (_) { rendered = ""; }
      if (rendered) parts.push(rendered);
    }
    parts.push(string(profile.ref && profile.ref.revision).slice(0, 10));
    return parts.join(" · ");
  }
  function optionLabel(profile) {
    var rawOrigin = string(profile.origin);
    var originLookup = rawOrigin.toLowerCase();
    var origin = originLookup === "bundled" ? "bundled" : originLookup === "imported" ? "local" : rawOrigin;
    var state = profile.compatible === false ? "incompatible" : profile.active ? "active" : profile.selected ? "selected" : "";
    var variant = (origin === "bundled" || origin === "local") ? origin + (state ? "_" + state : "") : "";
    var optionTemplates = {
      bundled: ["profiles.catalog.option.bundled", "{name} · Bundled · {revision}"],
      local: ["profiles.catalog.option.local", "{name} · Local · {revision}"],
      bundled_incompatible: ["profiles.catalog.option.bundled_incompatible", "{name} · Bundled · {revision} · incompatible"],
      local_incompatible: ["profiles.catalog.option.local_incompatible", "{name} · Local · {revision} · incompatible"],
      bundled_active: ["profiles.catalog.option.bundled_active", "{name} · Bundled · {revision} · active"],
      local_active: ["profiles.catalog.option.local_active", "{name} · Local · {revision} · active"],
      bundled_selected: ["profiles.catalog.option.bundled_selected", "{name} · Bundled · {revision} · selected"],
      local_selected: ["profiles.catalog.option.local_selected", "{name} · Local · {revision} · selected"],
    };
    var values = { name: string(profile.display_name || profile.ref.id), revision: revisionLabel(profile) };
    return own.call(optionTemplates, variant)
      ? t(optionTemplates[variant][0], optionTemplates[variant][1], values)
      : values.name + " · " + origin + " · " + values.revision + (state ? " · " + state : "");
  }
  function renderCatalog() {
    var select = byId("profile-select");
    if (!select) return;
    var wanted = refKey(model.selected);
    select.textContent = "";
    var groups = catalogGroups(model.profiles);
    var superseded = 0;
    var rendered = [];
    groups.forEach(function (group) {
      // An option is offered when it represents its group, when the toggle is open, or when it is
      // the one the user currently has selected: a collapse that could hide the current selection
      // would leave the control disagreeing with what the page thinks is selected.
      var offered = group.collapsible ? group.revisions.filter(function (profile) {
        return profile === group.representative || model.showAllRevisions || refKey(profile.ref) === wanted;
      }) : group.revisions;
      if (group.collapsible) superseded += group.revisions.length - 1;
      var parent = select;
      if (offered.length > 1) {
        parent = document.createElement("optgroup");
        parent.label = string(group.revisions[0].display_name || group.id);
        select.appendChild(parent);
      }
      offered.forEach(function (profile) {
        var option = document.createElement("option");
        option.value = refKey(profile.ref);
        option.textContent = optionLabel(profile);
        parent.appendChild(option);
        rendered.push(profile);
      });
    });
    renderRevisionsToggle(superseded);
    if (wanted && rendered.some(function (item) { return refKey(item.ref) === wanted; })) select.value = wanted;
    else {
      // The fallback is chosen from what was actually appended, never from the whole catalogue: an
      // unrendered option cannot be assigned to select.value, and assigning one silently leaves the
      // control and model.selected describing different revisions.
      var active = rendered.find(function (item) { return item.active; }) || rendered[0];
      model.selected = active ? active.ref : null;
      if (active) select.value = refKey(active.ref);
    }
    renderBadges();
    updateActions();
  }
  function renderRevisionsToggle(superseded) {
    var toggle = byId("profile-revisions");
    if (!toggle) return;
    var row = toggle.closest ? toggle.closest(".profile-revisions") : null;
    (row || toggle).hidden = superseded < 1;
    toggle.checked = !!model.showAllRevisions;
  }
  function badge(label, kind) {
    var item = document.createElement("span");
    item.className = "profile-badge" + (kind ? " " + kind : "");
    item.textContent = label;
    return item;
  }
  function profileLinkLabelSafe(label) {
    return !/[\u00ad\u061c\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff]/i.test(label);
  }
  function renderProfileLinks(summary) {
    var root = byId("profile-links");
    if (!root) return;
    root.textContent = "";
    root.hidden = true;
    if (!summary || summary.compatible === false) return;
    (summary.links || []).forEach(function (candidate) {
      var raw = string(candidate && candidate.url);
      var label = string(candidate && candidate.label || t("profiles.reference.default", "Reference"));
      var parsed;
      if (!raw || raw.length > 500 || !profileLinkLabelSafe(label)) return;
      try { parsed = new URL(raw); } catch (_) { return; }
      if (parsed.protocol !== "https:" || !parsed.hostname || parsed.username || parsed.password) return;
      var link = document.createElement("a");
      link.className = "profile-link";
      link.href = parsed.href;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      link.referrerPolicy = "no-referrer";
      var labelNode = document.createElement("bdi");
      labelNode.className = "profile-link-label";
      labelNode.dir = "auto";
      labelNode.textContent = label;
      var hostNode = document.createElement("bdi");
      hostNode.className = "profile-link-host";
      hostNode.dir = "ltr";
      hostNode.textContent = parsed.hostname;
      link.appendChild(labelNode);
      link.appendChild(document.createTextNode(" · "));
      link.appendChild(hostNode);
      root.appendChild(link);
    });
    root.hidden = !root.childNodes.length;
  }
  function renderBadges() {
    var root = byId("profile-badges");
    if (!root) return;
    root.textContent = "";
    var summary = reviewedSummary();
    var guidance = byId("profile-shizuku-guidance");
    if (guidance) guidance.hidden = true;
    renderProfileLinks(summary);
    if (!summary) return;
    var origins = {
      bundled: function () { return t("profiles.origin.bundled", "Bundled"); },
      imported: function () { return t("profiles.origin.local", "Local"); },
      local: function () { return t("profiles.origin.local", "Local"); },
    };
    root.appendChild(badge(closedText(summary.origin, origins), string(summary.origin).toLowerCase() === "bundled" ? "bundled" : ""));
    if (summary.maturity) {
      var maturity = string(summary.maturity);
      var maturityLookup = maturity.toLowerCase();
      var trustedVerified = maturityLookup === "verified" && summary.trusted_provenance === true;
      var maturityLabels = {
        draft: function () { return t("profiles.maturity.draft", "draft"); },
        experimental: function () { return t("profiles.maturity.experimental", "experimental"); },
      };
      var maturityLabel = trustedVerified ? t("profiles.maturity.verified_trusted", "✓ Verified")
        : (maturityLookup === "verified" ? t("profiles.maturity.verified_author", "Author: verified") : closedText(maturity, maturityLabels));
      root.appendChild(badge(maturityLabel, trustedVerified ? "maturity verified" : "maturity"));
    }
    if (summary.compatible === false) root.appendChild(badge(t("profiles.state.incompatible", "Incompatible"), "risk"));
    if (summary.matches_this_device === false) root.appendChild(badge(t("profiles.state.does_not_match", "Does not match this device"), "risk"));
    if (summary.active) root.appendChild(badge(t("profiles.state.active", "Active"), "active"));
    if (summary.selected && !summary.active) root.appendChild(badge(t("profiles.state.selected", "Selected"), "pending"));
    if (summary.last_known_good) root.appendChild(badge(t("profiles.state.last_known_good", "Last known good"), "lkg"));
    var shizuku = string(summary.shizuku_recommendation);
    if (shizuku.toLowerCase() === "recommended") {
      root.appendChild(badge(t("profiles.shizuku.recommended", "Shizuku: recommended"), "shizuku"));
    }
    // Keep exceptional access guidance out of ordinary profile summaries.
    if (guidance) guidance.hidden = shizuku.toLowerCase() !== "recommended";
    (summary.risks || []).forEach(function (risk) { root.appendChild(badge(riskText(risk), "warning")); });
    var activation = model.status.activation || {};
    var activationLabels = {
      pending: function () { return t("profiles.activation.pending", "pending"); },
      applying: function () { return t("profiles.activation.applying", "applying"); },
    };
    var activationState = string(activation.state).toLowerCase();
    if (own.call(activationLabels, activationState)) {
      var activationCompatibility = string(activation.message) || closedText(activation.state, activationLabels);
      root.appendChild(badge(presentedText(activation, activationCompatibility), "pending"));
    }
  }
  function renderIssues(issues) {
    var root = byId("profile-issues");
    if (!root) return;
    root.textContent = "";
    if (!issues || !issues.length) {
      var empty = document.createElement("div"); empty.className = "profile-empty"; empty.textContent = t("profiles.validation.none", "No validation issues."); root.appendChild(empty);
      model.editor.setDiagnostics([]);
      return;
    }
    var diagnostics = [];
    issues.forEach(function (issue) {
      var rawSeverity = string(issue.severity || "error");
      var severity = rawSeverity.toLowerCase();
      var row = document.createElement("div"); row.className = "profile-issue " + (/^(info|warning|error)$/.test(severity) ? severity : "error");
      var loc = document.createElement("span"); loc.className = "profile-issue-loc";
      var at = [];
      if (issue.path) at.push(string(issue.path));
      if (issue.line) at.push(issue.column
        ? t("profiles.issue.line_column", "line {line}:{column}", { line: issue.line, column: issue.column })
        : t("profiles.issue.line", "line {line}", { line: issue.line }));
      var severityLabels = {
        info: function () { return t("profiles.severity.info", "info"); },
        warning: function () { return t("profiles.severity.warning", "warning"); },
        error: function () { return t("profiles.severity.error", "error"); },
      };
      loc.textContent = at.join(" · ") || closedText(rawSeverity, severityLabels);
      var compatibility = string(issue.message || issue.code || t("profiles.validation.invalid_profile", "Invalid profile"));
      var message = document.createElement("span"); message.textContent = presentedText(issue, compatibility);
      row.appendChild(loc); row.appendChild(message); root.appendChild(row);
      if (issue.offset != null) diagnostics.push({
        from: Number(issue.offset) || 0,
        to: Number(issue.end_offset) || Number(issue.offset) || 0,
        severity: severity,
        message: message.textContent,
      });
    });
    model.editor.setDiagnostics(diagnostics);
  }
  function renderCatalogIssues(issues) {
    var root = byId("profile-catalog-issues");
    if (!root) return;
    root.textContent = "";
    if (!issues || !issues.length) {
      var empty = document.createElement("div"); empty.className = "profile-empty"; empty.textContent = t("profiles.catalog.healthy", "Catalog and active runtime are healthy."); root.appendChild(empty); return;
    }
    issues.forEach(function (issue) {
      var rawSeverity = string(issue.severity || "error");
      var severity = rawSeverity.toLowerCase();
      var row = document.createElement("div"); row.className = "profile-issue " + (/^(info|warning|error)$/.test(severity) ? severity : "error");
      var severityLabels = {
        info: function () { return t("profiles.severity.info", "info"); },
        warning: function () { return t("profiles.severity.warning", "warning"); },
        error: function () { return t("profiles.severity.error", "error"); },
      };
      var loc = document.createElement("span"); loc.className = "profile-issue-loc"; loc.textContent = issue.path ? string(issue.path) : closedText(rawSeverity, severityLabels);
      var compatibility = string(issue.message || t("profiles.catalog.issue_default", "Profile catalog issue"));
      var message = document.createElement("span"); message.textContent = presentedText(issue, compatibility);
      row.appendChild(loc); row.appendChild(message); root.appendChild(row);
    });
  }
  function renderDiff(diff) {
    var root = byId("profile-diff");
    if (!root) return;
    root.textContent = "";
    if (diff == null) {
      var pending = document.createElement("div"); pending.className = "profile-empty"; pending.textContent = t("profiles.compare.pending", "Validate the current YAML to compare it with the active profile."); root.appendChild(pending); return;
    }
    if (!diff || !diff.length) {
      var empty = document.createElement("div"); empty.className = "profile-empty"; empty.textContent = t("profiles.compare.no_changes", "No semantic changes from the active profile."); root.appendChild(empty); return;
    }
    diff.forEach(function (change) {
      var row = document.createElement("div"); row.className = "profile-diff-row";
      var path = document.createElement("div"); path.className = "profile-diff-path"; path.textContent = string(change.path || change.field || t("profiles.compare.item_default", "profile"));
      var value = document.createElement("div"); value.className = "profile-diff-value";
      var before = change.before == null ? t("profiles.compare.unset", "(unset)") : string(change.before);
      var after = change.after == null ? t("profiles.compare.unset", "(unset)") : string(change.after);
      value.textContent = before + " → " + after;
      row.appendChild(path); row.appendChild(value); root.appendChild(row);
    });
  }
  function renderReport(report) {
    var root = byId("profile-report");
    if (!root) return;
    root.textContent = "";
    var entries = report && report.items || [];
    if (!entries.length) {
      var empty = document.createElement("div"); empty.className = "profile-empty"; empty.textContent = t("profiles.report.none", "No passive device report for this profile."); root.appendChild(empty); return;
    }
    function reportLabel(path) {
      var labels = {
        "match.any[].all[].field = model": function () { return t("profiles.report.match_model", "Match field: model"); },
        "match.any[].all[].field = device": function () { return t("profiles.report.match_device", "Match field: device"); },
        "match.any[].all[].field = product_version": function () { return t("profiles.report.match_product_version", "Match field: product version"); },
        "evidence.android_sdk": function () { return t("profiles.report.android_sdk", "Android SDK"); },
        "evidence.abis": function () { return t("profiles.report.supported_abis", "Supported ABIs"); },
        "evidence.board": function () { return t("profiles.report.board", "Board"); },
        "evidence.hardware": function () { return t("profiles.report.hardware", "Hardware"); },
        "evidence.display.width_px": function () { return t("profiles.report.display_width", "Display width"); },
        "evidence.display.height_px": function () { return t("profiles.report.display_height", "Display height"); },
        "evidence.display.current_density_dpi": function () { return t("profiles.report.display_density", "Current display density"); },
        "evidence.led.dev_ledjni_readable": function () { return t("profiles.report.led_device_access", "LED device access"); },
        "evidence.led.sysfs_rgb_readable": function () { return t("profiles.report.led_sysfs_access", "LED sysfs access"); },
        "evidence.cpu.available_governors": function () { return t("profiles.report.cpu_governors", "Available CPU governors"); },
        "sensors.light_technology": function () { return t("profiles.report.light_sensor", "Light sensor"); },
        "sensors.proximity_technology": function () { return t("profiles.report.proximity_sensor", "Proximity sensor"); },
        "evidence.sensors.temperature": function () { return t("profiles.report.temperature_sensor", "Temperature sensor"); },
        "evidence.sensors.humidity": function () { return t("profiles.report.humidity_sensor", "Humidity sensor"); },
      };
      return own.call(labels, path) ? labels[path]() : string(path);
    }
    entries.forEach(function (fact) {
      var row = document.createElement("div"); row.className = "profile-diff-row";
      var key = document.createElement("div"); key.className = "profile-diff-path"; key.textContent = reportLabel(string(fact.key || fact.path || t("profiles.report.item_default", "fact")));
      var value = document.createElement("div"); value.className = "profile-diff-value";
      var rawStatus = string(fact.status || "unknown");
      var statuses = {
        unknown: function () { return t("profiles.report.status.unknown", "unknown"); },
        observed: function () { return t("profiles.report.status.observed", "observed"); },
      };
      var status = closedText(rawStatus, statuses);
      value.textContent = fact.value == null ? status : (rawStatus.toLowerCase() === "observed" ? string(fact.value) : status + " · " + string(fact.value));
      row.appendChild(key); row.appendChild(value); root.appendChild(row);
    });
  }
  function updateActions() {
    var summary = selectedSummary();
    var review = reviewedSummary();
    var active = model.profiles.find(function (item) { return item.active; }) || null;
    var dirty = isDirty();
    var select = byId("profile-select"); if (select) select.disabled = model.loading;
    var save = byId("savebtn");
    if (save) save.disabled = !model.editable || !dirty || !(model.preview && model.preview.compatible && model.preview.source === model.source);
    var validate = byId("profile-validate"); if (validate) validate.disabled = !model.source || model.loading;
    var compare = byId("profile-compare"); if (compare) compare.disabled = !model.source || model.loading;
    var edit = byId("profile-edit"); if (edit) edit.disabled = !summary || !model.sourceLoaded || model.editable;
    var fork = byId("profile-fork"); if (fork) fork.disabled = !summary || !model.sourceLoaded || model.editable;
    var activate = byId("profile-activate"); if (activate) activate.disabled = !summary || !model.sourceLoaded || summary.compatible === false || summary.matches_this_device === false || summary.active || dirty || model.loading;
    var automatic = byId("profile-auto"); if (automatic) automatic.disabled = dirty || model.loading || model.status.selection && model.status.selection.mode === "auto";
    var remove = byId("profile-delete"); if (remove) remove.disabled = !summary || summary.origin === "bundled" || summary.active || summary.selected || summary.last_known_good || dirty || model.loading;
    var rollback = byId("profile-rollback"); if (rollback) rollback.disabled = !(model.status.rollback_ref || model.status.rollback_auto) || dirty || model.loading;
    var genericDraft = byId("profile-generic-draft"); if (genericDraft) genericDraft.hidden = !(active && active.ref && active.ref.id === "generic");
    var meta = byId("profile-editor-meta");
    if (meta) meta.textContent = review
      ? string(review.ref.id) + (review.content_version ? " · v" + string(review.content_version) : "") + (review.author ? " · " + string(review.author) : "") + " · sha256:" + string(review.ref.revision).slice(0, 16)
      : (model.editable ? t("profiles.editor.unsaved", "Unsaved profile") : t("profiles.editor.none_selected", "No profile selected"));
    renderBadges();
  }

  function loadCatalog(preferredRef) {
    var generation = ++model.viewGeneration;
    setStatus(t("profiles.status.loading_catalog", "Loading profiles…"));
    return jsonFetch(API).then(function (data) {
      if (generation !== model.viewGeneration) return;
      model.catalogRevision = Number(data.catalog_revision) || 0;
      model.profiles = data.profiles || [];
      model.status = data.status || data;
      renderCatalogIssues(model.status.issues || []);
      if (preferredRef) model.selected = preferredRef;
      renderCatalog();
      if (!model.selected) { setEditor("", false); setStatus(t("profiles.state.no_profiles", "No profiles are available.")); return; }
      return loadSelected();
    }).catch(function (error) {
      setStatus(t("profiles.error.load_catalog", "Could not load profiles: {error}", { error: presentedError(error) }), "error");
      renderIssues([{ severity: "error", message: error.message }]);
    });
  }
  function loadSelected() {
    if (!model.selected) return Promise.resolve();
    var ref = model.selected;
    var generation = ++model.viewGeneration;
    setStatus(t("profiles.status.loading_profile", "Loading {id}…", { id: ref.id }));
    return yamlFetch(API + "/" + encodeURIComponent(ref.id) + "/revisions/" + encodeURIComponent(ref.revision)).then(function (yaml) {
      if (generation !== model.viewGeneration || refKey(model.selected) !== refKey(ref)) return;
      setEditor(yaml, false);
      var summary = selectedSummary(); renderIssues(summary && summary.issues || []); renderDiff([]);
      setStatus(t("profiles.status.viewing_revision", "Viewing immutable revision {revision}.", { revision: string(ref.revision).slice(0, 12) }));
    }).catch(function (error) {
      if (generation !== model.viewGeneration || refKey(model.selected) !== refKey(ref)) return;
      setEditor("", false); model.sourceLoaded = false; renderIssues([{ severity: "error", message: error.message }]); renderDiff(null); updateActions();
      setStatus(t("profiles.error.load_profile", "Could not load profile: {error}", { error: presentedError(error) }), "error");
    });
  }

  function loadReport() {
    return jsonFetch(API + "/report").then(renderReport).catch(function () { renderReport({}); });
  }

  function preview(openCompare) {
    if (!model.source) return Promise.resolve(null);
    var source = model.source;
    var generation = model.viewGeneration;
    model.loading = true; updateActions(); setStatus(t("profiles.status.validating", "Validating YAML…"));
    return postYaml("/probe", source).then(function (result) {
      if (generation !== model.viewGeneration || source !== model.source) return null;
      result.source = source;
      model.preview = result;
      renderIssues(result.issues || []);
      renderDiff(result.diff_from_active || []);
      renderReport(result.report || {});
      var matchesDevice = !result.summary || result.summary.matches_this_device !== false;
      setStatus(result.compatible
        ? (matchesDevice
          ? t("profiles.status.valid_hash", "Valid profile · sha256:{sha256}", { sha256: string(result.content_sha256).slice(0, 12) })
          : t("profiles.status.valid_wrong_hardware", "Valid profile, but it is intended for different hardware. You can save it here, but you cannot activate it on this panel."))
        : t("profiles.status.incompatible", "Profile is not compatible; fix the reported issues."),
      result.compatible && matchesDevice ? "ok" : "error");
      if (openCompare) openModal(t("profiles.modal.compare_title", "Compare with active profile"), diffText(result.diff_from_active || []), null);
      return result;
    }).catch(function (error) {
      if (generation !== model.viewGeneration || source !== model.source) return null;
      var issues = error.body && error.body.issues || [{ severity: "error", message: error.message }];
      model.preview = null; renderIssues(issues); setStatus(t("profiles.error.validation_failed", "Validation failed: {error}", { error: presentedError(error) }), "error"); return null;
    }).finally(function () {
      if (generation === model.viewGeneration) { model.loading = false; updateActions(); }
    });
  }
  function diffText(diff) {
    if (!diff || !diff.length) return t("profiles.compare.no_changes", "No semantic changes from the active profile.");
    return diff.map(function (change) {
      return string(change.path || change.field || t("profiles.compare.item_default", "profile")) + "\n  " + string(change.before == null ? t("profiles.compare.unset", "(unset)") : change.before) + "\n  → " + string(change.after == null ? t("profiles.compare.unset", "(unset)") : change.after);
    }).join("\n\n");
  }
  function ensurePreview() {
    if (model.preview && model.preview.source === model.source && model.preview.compatible) return Promise.resolve(model.preview);
    return preview(false).then(function (result) { if (!result || !result.compatible) throw new Error(t("profiles.error.must_validate", "Profile must validate before it can be saved")); return result; });
  }
  function saveProfile() {
    var requestedSource = model.source;
    ensurePreview().then(function (result) {
      var source = model.source;
      model.loading = true; updateActions(); setStatus(t("profiles.status.saving", "Saving immutable revision…"));
      return postYaml("/import", source, result.preview_token).then(function (mutation) {
        return { mutation: mutation, source: source };
      });
    }).then(function (saved) {
      if (!saved || saved.source !== model.source) return;
      var mutation = saved.mutation;
      var compatibility = string(mutation.message || t("profiles.status.saved", "Profile revision saved."));
      setStatus(presentedText(mutation, compatibility), "ok");
      var ref = mutation.ref || mutation.imported_ref || null;
      return loadCatalog(ref);
    }).catch(function (error) {
      if (requestedSource !== model.source) return;
      // Import consumes its one-shot token even when persistence/quota checks reject the mutation.
      // Force a fresh validation so Retry can never loop on a dead token.
      model.preview = null;
      var issue = error.body && error.body.issues && error.body.issues[0];
      var reason = issue ? presentedText(issue, issue.message || issue.code) : presentedError(error);
      if (error.status === 409) setStatus(presentedError(error, t("profiles.error.stale_preview", "This preview or catalog revision is stale. Validate again before saving.")), "error");
      else setStatus(t("profiles.error.save_failed", "Save failed: {error} Resolve the issue, then Validate again.", { error: reason }), "error");
    }).finally(function () { model.loading = false; updateActions(); });
  }

  function beginEdit(fork) {
    var summary = selectedSummary();
    if (!summary) return;
    model.editable = true;
    model.preview = null;
    model.editor.setReadOnly(false);
    setStatus((fork || summary.origin === "bundled")
      ? t("profiles.status.editing_fork", "Editing a self-contained local fork. The bundled revision will not change.")
      : t("profiles.status.editing_revision", "Editing as a new immutable local revision."));
    model.editor.focus(); updateActions();
  }
  function loadTemplate() {
    var generation = ++model.viewGeneration;
    yamlFetch(API + "/template").then(function (yaml) {
      if (generation !== model.viewGeneration) return;
      model.selected = null; setEditor(yaml, true); model.originalSource = ""; renderIssues([]); renderDiff([]); setStatus(t("profiles.status.new_unsaved", "New unsaved profile. Validate it before saving.")); model.editor.focus(); updateActions();
    }).catch(function (error) { if (generation === model.viewGeneration) setStatus(t("profiles.error.create_template", "Could not create a template: {error}", { error: presentedError(error) }), "error"); });
  }
  function loadDeviceDraft() {
    var generation = ++model.viewGeneration;
    yamlFetch(API + "/device-draft").then(function (yaml) {
      if (generation !== model.viewGeneration) return;
      model.selected = null; setEditor(yaml, false); renderIssues([]); renderDiff([]);
      setStatus(t("profiles.status.passive_draft", "Passive Generic draft · read-only · unknown hardware remains marked TODO."));
      var use = byId("profile-use-draft"); if (use) use.hidden = false;
    }).catch(function (error) { if (generation === model.viewGeneration) setStatus(t("profiles.error.build_draft", "Could not build the passive draft: {error}", { error: presentedError(error) }), "error"); });
  }
  function useDraft() {
    model.editable = true; model.editor.setReadOnly(false); model.originalSource = ""; model.preview = null;
    var use = byId("profile-use-draft"); if (use) use.hidden = true;
    setStatus(t("profiles.status.draft_copied", "Draft copied into a new unsaved profile. Resolve every TODO before activation.")); model.editor.focus(); updateActions();
  }
  function importFile(input) {
    var file = input.files && input.files[0]; if (!file) return;
    if (!confirmDiscard()) { input.value = ""; return; }
    if (file.size > model.maxBytes) {
      setStatus(t("profiles.error.too_large", "Profile is too large. The limit is {bytes} bytes.", { bytes: model.maxBytes }), "error");
      input.value = "";
      return;
    }
    var generation = ++model.viewGeneration;
    file.text().then(function (yaml) {
      if (generation !== model.viewGeneration) return;
      model.selected = null; setEditor(yaml, true); model.originalSource = ""; renderIssues([]); renderDiff([]); setStatus(t("profiles.status.imported_preview", "Imported locally for preview; nothing has been saved or activated.")); model.editor.focus(); return preview(false);
    }).catch(function (error) { if (generation === model.viewGeneration) setStatus(t("profiles.error.read_file", "Could not read profile: {error}", { error: error.message }), "error"); });
    input.value = "";
  }
  function exportSource() {
    if (!model.source) return;
    var summary = selectedSummary();
    var stem = summary ? string(summary.ref.id) : "panel-profile";
    var blob = new Blob([model.source], { type: "application/yaml;charset=utf-8" });
    var url = URL.createObjectURL(blob); var link = document.createElement("a");
    link.href = url; link.download = stem.replace(/[^a-zA-Z0-9._-]+/g, "-") + ".yaml";
    document.body.appendChild(link); link.click(); link.remove(); URL.revokeObjectURL(url);
  }

  function openModal(title, detail, onConfirm, confirmLabel, hardenedApproval) {
    var modal = byId("profile-modal");
    byId("profile-modal-title").textContent = title;
    byId("profile-modal-detail").textContent = detail + (hardenedApproval
      ? "\n\n" + t("profiles.modal.hardened_body", "Shielded action: when Hardened mode is enabled, approve this request on the physical panel.")
      : "");
    var confirm = byId("profile-modal-confirm");
    confirm.textContent = confirmLabel || t("profiles.action.confirm", "Confirm");
    confirm.hidden = !onConfirm;
    if (hardenedApproval) {
      confirm.setAttribute("data-hardened-approval", "");
      confirm.setAttribute("aria-describedby", "hardened-approval-description");
      confirm.setAttribute("title", t("profiles.modal.hardened_title", "Requires physical on-panel approval for this action when Hardened mode is enabled."));
    } else {
      confirm.removeAttribute("data-hardened-approval");
      confirm.removeAttribute("aria-describedby");
      confirm.removeAttribute("title");
    }
    confirm.onclick = function () { closeModal(); if (onConfirm) onConfirm(); };
    modal.hidden = false; byId("profile-modal-cancel").focus();
  }
  function closeModal() { byId("profile-modal").hidden = true; }
  function activate(ref, action) {
    var summary = ref ? summaryForRef(ref) : null;
    // Imported revisions are allowed to be stored inertly for catalog transfer, but a known
    // wrong-device revision must never reach the Hardened approval endpoint from this page.
    if (summary && summary.matches_this_device === false) {
      setStatus(t("profiles.error.activation_wrong_device", "Activation blocked: this imported profile does not match this device's immutable build identity."), "error");
      return;
    }
    var risks = summary && summary.risks || [];
    var riskSummary = localizedList(risks.map(riskText));
    var detail = [
      ref ? t("profiles.modal.profile_line", "Profile: {profile}", { profile: string(summary && summary.display_name || ref.id) })
        : t("profiles.modal.profile_auto_line", "Profile: automatic device matching"),
      ref ? t("profiles.modal.revision_line", "Revision: sha256:{sha256}", { sha256: string(ref.revision) })
        : t("profiles.modal.revision_auto_line", "Revision: the best compatible profile selected at restart"),
      risks.length ? t("profiles.modal.risks_line", "Privileged/risk flags: {risks}", { risks: riskSummary })
        : t("profiles.modal.no_risks_line", "Privileged/risk flags: none declared"),
      "",
      t("profiles.modal.restart_warning", "The panel service will restart. If the revision does not become healthy, ha-paneld will return to the last-known-good profile."),
    ].join("\n");
    openModal(action === "rollback" ? t("profiles.modal.rollback_title", "Roll back profile?") : t("profiles.modal.activate_title", "Activate profile?"), detail, function () {
      model.loading = true; updateActions(); setStatus(action === "rollback" ? t("profiles.status.rollback_starting", "Starting rollback…") : t("profiles.status.activating", "Activating profile…"));
      var path = action === "rollback" ? "/rollback" : "/select";
      var request = { expected_catalog_revision: model.catalogRevision, confirm: true };
      if (ref) { request.id = ref.id; request.revision = ref.revision; } else request.auto = true;
      postJson(path, request).then(function (result) {
        var compatibility = string(result.message || t("profiles.status.selection_saved", "Selection saved; waiting for the panel service to restart."));
        setStatus(presentedText(result, compatibility), "ok");
        if (result.restart_required) pollAfterRestart(ref, 0); else loadCatalog(ref);
      }).catch(function (error) {
        if (error.body && error.body.error === "approval-required") setStatus(approvalMessage(error.body));
        else {
          var issue = error.body && error.body.issues && error.body.issues[0];
          var reason = issue ? presentedText(issue, issue.message || issue.code) : presentedError(error);
          setStatus(error.status === 409
            ? presentedError(error, t("profiles.error.activation_catalog_changed", "The profile catalog changed in another tab. Reload and confirm again."))
            : t("profiles.error.activation_failed", "Activation failed: {error}", { error: reason }), "error");
        }
      }).finally(function () { model.loading = false; updateActions(); });
    }, action === "rollback" ? t("profiles.action.confirm_rollback", "Confirm rollback") : t("profiles.action.confirm_restart", "Confirm and restart"), true);
  }
  function pollAfterRestart(ref, attempt) {
    if (attempt > 60) { setStatus(t("profiles.error.restart_slow", "The restart is taking longer than expected. Reload to check the active profile."), "error"); return; }
    setTimeout(function () {
      fetch("health", { cache: "no-store" }).then(function (response) {
        if (!response.ok) throw new Error("not ready");
        return jsonFetch(API + "?t=" + Date.now());
      }).then(function (data) {
        model.catalogRevision = Number(data.catalog_revision) || model.catalogRevision;
        model.profiles = data.profiles || [];
        model.status = data.status || data;
        var active = model.profiles.find(function (item) { return item.active; });
        var activation = model.status.activation || {};
        var automaticHealthy = !ref && model.status.selection && model.status.selection.mode === "auto" && activation.state === "active";
        var pinnedHealthy = active && refKey(active.ref) === refKey(ref) && activation.state === "active";
        if (automaticHealthy || pinnedHealthy) { setStatus(t("profiles.status.active_healthy", "Profile is active and healthy."), "ok"); loadCatalog(ref); return; }
        if (activation.state === "rolled_back" || activation.state === "auto_rolled_back") { setStatus(t("profiles.error.auto_rollback", "The candidate did not become healthy; ha-paneld automatically restored the last-known-good profile."), "error"); loadCatalog(active && active.ref); return; }
        pollAfterRestart(ref, attempt + 1);
      }).catch(function () { pollAfterRestart(ref, attempt + 1); });
    }, attempt < 5 ? 1000 : 2500);
  }
  function deleteSelected() {
    var summary = selectedSummary(); if (!summary) return;
    openModal(t("profiles.modal.delete_title", "Delete inactive profile revision?"), t("profiles.modal.delete_detail", "{profile}\nsha256:{sha256}\n\nThis cannot be undone. Active, selected, bundled, and last-known-good revisions cannot be deleted.", { profile: string(summary.display_name || summary.ref.id), sha256: string(summary.ref.revision) }), function () {
      model.loading = true; updateActions();
      postJson("/delete", { id: summary.ref.id, revision: summary.ref.revision, expected_catalog_revision: model.catalogRevision, confirm: true }).then(function (result) {
        var compatibility = string(result.message || t("profiles.status.deleted", "Profile deleted."));
        setStatus(presentedText(result, compatibility), "ok"); return loadCatalog();
      }).catch(function (error) { setStatus(error.status === 409 ? presentedError(error, t("profiles.error.delete_catalog_changed", "The catalog changed; reload before deleting.")) : t("profiles.error.delete_failed", "Delete failed: {error}", { error: presentedError(error) }), "error"); })
        .finally(function () { model.loading = false; updateActions(); });
    }, t("profiles.action.delete_revision", "Delete revision"));
  }
  function bind() {
    byId("profile-select").addEventListener("change", function (event) {
      if (!confirmDiscard()) { event.target.value = refKey(model.selected); return; }
      var summary = model.profiles.find(function (item) { return refKey(item.ref) === event.target.value; });
      model.selected = summary && summary.ref; loadSelected();
    });
    var revisions = byId("profile-revisions");
    if (revisions) revisions.addEventListener("change", function () { model.showAllRevisions = !!this.checked; renderCatalog(); });
    byId("profile-new").addEventListener("click", function () { if (confirmDiscard()) loadTemplate(); });
    byId("profile-edit").addEventListener("click", function () { beginEdit(false); });
    byId("profile-fork").addEventListener("click", function () { beginEdit(true); });
    byId("profile-import").addEventListener("change", function () { importFile(this); });
    byId("profile-export").addEventListener("click", exportSource);
    byId("profile-validate").addEventListener("click", function () { preview(false); });
    byId("profile-compare").addEventListener("click", function () { preview(true); });
    byId("savebtn").addEventListener("click", saveProfile);
    byId("profile-activate").addEventListener("click", function () { var summary = selectedSummary(); if (summary) activate(summary.ref, "activate"); });
    byId("profile-auto").addEventListener("click", function () { activate(null, "activate"); });
    byId("profile-rollback").addEventListener("click", function () { if (model.status.rollback_ref || model.status.rollback_auto) activate(model.status.rollback_ref || null, "rollback"); });
    byId("profile-delete").addEventListener("click", deleteSelected);
    byId("profile-draft").addEventListener("click", function () { if (confirmDiscard()) loadDeviceDraft(); });
    byId("profile-use-draft").addEventListener("click", useDraft);
    byId("profile-modal-cancel").addEventListener("click", closeModal);
    byId("profile-modal").addEventListener("click", function (event) { if (event.target === this) closeModal(); });
    window.addEventListener("beforeunload", function (event) {
      if (!isDirty()) return;
      event.preventDefault();
      event.returnValue = "";
    });
  }

  initEditor(); bind(); bindProfileLayout(); renderCatalogIssues([]); renderIssues([]); renderDiff([]); renderReport({});
  jsonFetch(API + "/schema").then(function (schema) {
    var advertised = Number(schema.max_bytes);
    if (advertised > 0) model.maxBytes = Math.min(advertised, 256 * 1024);
    if (model.editor && typeof model.editor.setSchema === "function") model.editor.setSchema(localizedSchemaFields(schema.fields));
  }).catch(function () {});
  loadReport(); loadCatalog();
}());
