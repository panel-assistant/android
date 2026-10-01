// Install tab: managed-component version pickers + install/update, WebView heal, health audit, radio.
(function () {
  "use strict";
  var own = Object.prototype.hasOwnProperty;
  var projection = {};
  try {
    var projectionNode = document.getElementById('ha-i18n');
    projection = projectionNode ? JSON.parse(projectionNode.textContent || '{}') : {};
    if (!projection || typeof projection !== 'object' || Array.isArray(projection)) projection = {};
  } catch (_) { projection = {}; }
  var locale = window.HaI18n && typeof window.HaI18n.locale === 'string' ? window.HaI18n.locale : (document.documentElement && document.documentElement.lang || 'en');
  var languages = projection.languages && typeof projection.languages === 'object' && !Array.isArray(projection.languages) ? projection.languages : {};
  function fallbackText(fallback, values) {
    return String(fallback == null ? '' : fallback).replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, function (token, name) {
      return values && own.call(values, name) ? String(values[name]) : token;
    });
  }
  function t(key, fallback, values) {
    var english = fallbackText(fallback, values);
    if (!window.HaI18n || typeof window.HaI18n.t !== 'function') return english;
    try {
      var translated = window.HaI18n.t(key, fallback, values);
      return typeof translated === 'string' ? translated : english;
    } catch (_) { return english; }
  }
  function localized(key) { return locale === 'en' || languages[key] === locale; }
  function pluralText(oneKey, otherKey, count, oneFallback, otherFallback, values) {
    var category = Number(count) === 1 ? 'one' : 'other';
    try { category = new Intl.PluralRules(locale).select(Number(count)) === 'one' ? 'one' : 'other'; } catch (_) {}
    return t(category === 'one' ? oneKey : otherKey, category === 'one' ? oneFallback : otherFallback, values);
  }

  // This is an explicit copy of the frozen Install v3 code-to-key contract. Never derive a key from
  // received input: an unknown code must leave the byte-compatible English field untouched.
  var PRESENTATIONS = Object.freeze({"version-install":"install.presentation.version_install","version-upgrade":"install.presentation.version_upgrade","version-downgrade":"install.presentation.version_downgrade","operation-working":"install.presentation.operation_working","operation-cancelled":"install.presentation.operation_cancelled","apk-pending-lost":"install.presentation.apk_pending_lost","backup-ready":"install.presentation.backup_ready","backup-ready-state-unavailable":"install.presentation.backup_ready_state_unavailable","backup-cancelled":"install.presentation.backup_cancelled","backup-companion-too-large":"install.presentation.backup_companion_too_large","backup-staging-retained":"install.presentation.backup_staging_retained","restore-preview-complete":"install.presentation.restore_preview_complete","restore-request-rejected":"install.presentation.restore_request_rejected","restore-completed":"install.presentation.restore_completed","restore-completed-with-state":{"one":"install.restore.state_values.one","other":"install.restore.state_values.other","selector":"count"},"restore-completed-state-unavailable":"install.presentation.restore_completed_state_unavailable","restore-partial":"install.presentation.restore_partial","restore-failed":"install.presentation.restore_failed","package-uninstalled":"install.presentation.package_uninstalled","package-uninstall-failed":"install.presentation.package_uninstall_failed","guard-db-retirement-settled":"install.presentation.guard_db_retirement_settled","guard-db-candidate-discard-finished":"install.presentation.guard_db_candidate_discard_finished","guard-db-candidate-staging-finished":"install.presentation.guard_db_candidate_staging_finished","guard-db-arm-not-started":"install.presentation.guard_db_arm_not_started","managed-release-unresolved":"install.presentation.managed_release_unresolved","managed-apk-missing":"install.presentation.managed_apk_missing","managed-up-to-date":"install.presentation.managed_up_to_date","managed-install-committed":"install.presentation.managed_install_committed","managed-update-committed":"install.presentation.managed_update_committed","managed-downgrade-committed":"install.presentation.managed_downgrade_committed","managed-pinned":"install.presentation.managed_pinned","managed-safety-cap-refused":"install.presentation.managed_safety_cap_refused","managed-manual-downgrade-required":"install.presentation.managed_manual_downgrade_required","managed-play-managed":"install.presentation.managed_play_managed","managed-no-recommendation":"install.presentation.managed_no_recommendation","managed-no-newer":"install.presentation.managed_no_newer","managed-attempt-recorded":"install.presentation.managed_attempt_recorded","install-no-permitted-route":"install.presentation.install_no_permitted_route","install-download-too-large":"install.presentation.install_download_too_large","install-insufficient-storage":"install.presentation.install_insufficient_storage","install-staging-failed":"install.presentation.install_staging_failed","install-download-failed":"install.presentation.install_download_failed","install-deferred-saving-state":"install.presentation.install_deferred_saving_state","install-guard-db-owned":"install.presentation.install_guard_db_owned","install-durable-rejection":"install.presentation.install_durable_rejection","install-retryable-failure":"install.presentation.install_retryable_failure","component-not-present":"install.presentation.component_not_present","profile-catalog-restore-unavailable":"install.presentation.profile_catalog_restore_unavailable","backup-import-partial-selection-unchanged":"install.presentation.backup_import_partial_selection_unchanged","backup-restored-selection-unchanged":"install.presentation.backup_restored_selection_unchanged","backup-restored-selection-staged":"install.presentation.backup_restored_selection_staged","backup-selection-restore-failed":"install.presentation.backup_selection_restore_failed","backup-import-selection-stage-failed":"install.presentation.backup_import_selection_stage_failed","backup-restore-rejected-before-mutation":"install.presentation.backup_restore_rejected_before_mutation","destructive-operation-in-progress":"profiles.result.destructive-operation-in-progress","profile-restart-unavailable":"profiles.result.profile-restart-unavailable","profile-activation-abort-persist-failed":"profiles.result.profile-activation-abort-persist-failed","companion-unsupported-package":"install.presentation.companion_unsupported_package","companion-payload-invalid":"install.presentation.companion_payload_invalid","companion-helper-busy":"install.presentation.companion_helper_busy","companion-marker-failed":"install.presentation.companion_marker_failed","companion-urls-repaired":{"one":"install.companion.urls_repaired.one","other":"install.companion.urls_repaired.other","selector":"count"},"companion-owner-restored":"install.presentation.companion_owner_restored","companion-relaunch-unconfirmed":"install.presentation.companion_relaunch_unconfirmed","companion-prior-files-retained":"install.presentation.companion_prior_files_retained","companion-rollback-failed":"install.presentation.companion_rollback_failed","companion-helper-unavailable":"install.presentation.companion_helper_unavailable","companion-rejected-before-commit":"install.presentation.companion_rejected_before_commit","companion-indeterminate":"install.presentation.companion_indeterminate","restore-passphrase-required":"install.presentation.restore_passphrase_required","restore-passphrase-or-bundle-invalid":"install.presentation.restore_passphrase_or_bundle_invalid","restore-not-panel-backup":"install.presentation.restore_not_panel_backup","restore-schema-missing":"install.presentation.restore_schema_missing","restore-config-missing":"install.presentation.restore_config_missing","restore-config-invalid":"install.presentation.restore_config_invalid","restore-legacy-too-large":"install.presentation.restore_legacy_too_large","restore-companion-section-invalid":"install.presentation.restore_companion_section_invalid","restore-entity-object-invalid":"install.presentation.restore_entity_object_invalid","restore-profiles-object-invalid":"install.presentation.restore_profiles_object_invalid","restore-state-object-invalid":"install.presentation.restore_state_object_invalid","restore-archive-metadata-invalid":"install.presentation.restore_archive_metadata_invalid","restore-archive-entries-invalid":"install.presentation.restore_archive_entries_invalid","restore-entity-state-invalid":"install.presentation.restore_entity_state_invalid","restore-entity-owner-missing":"install.presentation.restore_entity_owner_missing","restore-app-state-invalid":"install.presentation.restore_app_state_invalid","restore-profile-archive-invalid":"install.presentation.restore_profile_archive_invalid","restore-profile-catalog-invalid":"install.presentation.restore_profile_catalog_invalid","restore-profile-catalog-not-restorable":"install.presentation.restore_profile_catalog_not_restorable","restore-profile-restore-unavailable":"install.presentation.restore_profile_restore_unavailable","restore-companion-helper-required":"install.presentation.restore_companion_helper_required","status-webview-old":"install.presentation.status_webview_old","status-no-renderer":"install.presentation.status_no_renderer","status-update-available":"install.presentation.status_update_available","status-schema-rollback":"install.presentation.status_schema_rollback","status-builtin-renderer-retries-stopped":"install.presentation.status_builtin_renderer_retries_stopped","status-external-renderer-crash-loop":"install.presentation.status_external_renderer_crash_loop","status-companion-url-missing":{"one":"install.status.companion_url_missing.one","other":"install.status.companion_url_missing.other","selector":"count"},"status-companion-probe-failed":"install.presentation.status_companion_probe_failed","status-zigbee-contained":"install.presentation.status_zigbee_contained","status-zigbee-containment-incomplete":"install.presentation.status_zigbee_containment_incomplete","status-zigbee-runaway":"install.presentation.status_zigbee_runaway","status-zigbee-high-cpu":"install.presentation.status_zigbee_high_cpu","status-zigbee-not-joined":"install.presentation.status_zigbee_not_joined","status-zigbee-legacy-watchdog":"install.presentation.status_zigbee_legacy_watchdog","status-storage-warning":"install.presentation.status_storage_warning","status-storage-critical":"install.presentation.status_storage_critical","status-storage-database-failure":"install.presentation.status_storage_database_failure","status-power-at-risk":"install.presentation.status_power_at_risk","status-power-caution":"install.presentation.status_power_caution","status-power-unknown":"install.presentation.status_power_unknown","status-mdns-not-running":"runtime.mdns.not_running","status-mdns-stale-address":"runtime.mdns.stale_address","status-mdns-unresponsive":{"one":"runtime.mdns.unresponsive.one","other":"runtime.mdns.unresponsive.other","selector":"attempts"},"status-mdns-recovering":"runtime.mdns.recovering"});
  var PARAMS = Object.freeze({"operation-working":{"required":["owner"]},"restore-completed-with-state":{"required":["count"]},"package-uninstalled":{"required":["package"]},"package-uninstall-failed":{"required":["package"],"raw":true},"managed-release-unresolved":{"required":["component","channel"]},"managed-apk-missing":{"required":["component","version"]},"managed-up-to-date":{"required":["component","current"]},"managed-install-committed":{"required":["component","version"]},"managed-update-committed":{"required":["component","version"]},"managed-downgrade-committed":{"required":["component","version"]},"managed-pinned":{"required":["component","current","latest","cap"]},"managed-safety-cap-refused":{"required":["component","version","cap"]},"managed-manual-downgrade-required":{"required":["component","current","cap"]},"managed-play-managed":{"required":["component"]},"managed-no-recommendation":{"required":["component"]},"managed-no-newer":{"required":["component","current"]},"managed-attempt-recorded":{"required":["component","version","current"]},"install-no-permitted-route":{"required":["component"]},"install-download-too-large":{"required":["component"],"raw":true},"install-insufficient-storage":{"required":["component"],"raw":true},"install-staging-failed":{"required":["component"]},"install-download-failed":{"required":["component"]},"install-deferred-saving-state":{"required":["component"]},"install-guard-db-owned":{"required":["component"]},"install-durable-rejection":{"required":["component"],"raw":true},"install-retryable-failure":{"required":["component"],"raw":true},"companion-urls-repaired":{"required":["count"]},"status-webview-old":{"required":["current_engine","target_chromium"]},"status-update-available":{"required":["component","current","latest","release_url"]},"status-schema-rollback":{"required":["from_schema","to_schema"]},"status-companion-url-missing":{"required":["count"]},"status-storage-warning":{"optional":["usable_bytes","total_bytes","used_percent","database_bytes","wal_bytes"]},"status-storage-critical":{"optional":["usable_bytes","total_bytes","used_percent","database_bytes","wal_bytes"]},"status-storage-database-failure":{"required":["failure","operation"],"optional":["usable_bytes","total_bytes","used_percent","database_bytes","wal_bytes"]},"status-mdns-stale-address":{"required":["bound_ip","lan_ip"]},"status-mdns-unresponsive":{"required":["attempts","reason_code"]},"status-mdns-recovering":{"required":["reason_code"]}});
  var ENUMS = Object.freeze({owner:["paneld","companion","webview","apk","package-uninstall","backup","restore-preview","restore","companion-url-repair","guard-db"],component:["paneld","companion","webview","apk"],channel:["stable","prerelease"],failure:["storage-full","io","corruption","busy"],operation:["app-state-write","ambient-history","ambient-history-reset","ambient-history-seed","catalog-access-history","catalog-issue-override","catalog-maintenance","catalog-metric-history","catalog-overrides","catalog-reset","catalog-scope-migration","catalog-status","catalog-sync","dashboard-performance-history","database-checkpoint","database-create","database-downgrade-tripwire","database-preopen-reconcile","database-upgrade","database-vault-read","database-vault-restore","database-version-read","proximity-history","proximity-history-reset","quick-check","storage-health-read","database"],reason_code:["own-advertisement-absent","multicast-socket-failed","teardown-failed","recreation-failed","no-response"]});
  var COMPONENT_STATUS = Object.freeze({"started":"install.component_status.started","busy":"install.component_status.busy","bad-component":"install.component_status.bad_component","bad-action":"install.component_status.bad_action","bad-version":"install.component_status.bad_version","uncomparable-version":"install.component_status.uncomparable_version","downgrade-refused":"install.component_status.downgrade_refused"});
  var APK_STATUS = Object.freeze({"started":"install.apk_status.started","busy":"install.apk_status.busy","stale-or-missing":"install.apk_status.stale_or_missing","disabled":"install.apk_status.disabled","no-root":"install.apk_status.no_root","invalid-url":"install.apk_status.invalid_url","upload-busy":"install.apk_status.upload_busy","stopping":"install.apk_status.stopping","upload-staging-failed":"install.apk_status.upload_staging_failed","insufficient-storage":"install.apk_status.insufficient_storage","upload-too-large":"install.apk_status.upload_too_large","fetch-too-large":"install.apk_status.fetch_too_large","upload-timeout":"install.apk_status.upload_timeout","fetch-timeout":"install.apk_status.fetch_timeout","fetch-failed":"install.apk_status.fetch_failed","cancelled":"install.apk_status.cancelled","invalid-request":"install.apk_status.invalid_request","not-an-apk":"install.apk_status.not_an_apk"});
  var RESTORE_OUTCOME = Object.freeze({"succeeded":"install.restore_outcome.succeeded","partial":"install.restore_outcome.partial","failed":"install.restore_outcome.failed","skipped":"install.restore_outcome.skipped","rolled_back":"install.restore_outcome.rolled_back","rollback_failed":"install.restore_outcome.rollback_failed"});
  var RADIO_STATE = Object.freeze({"off":"install.radio_state.off","starting":"install.radio_state.starting","healthy":"install.radio_state.healthy","degraded_unjoined":"install.radio_state.degraded_unjoined","degraded_high_cpu":"install.radio_state.degraded_high_cpu","runaway":"install.radio_state.runaway","contained":"install.radio_state.contained","containment_failed":"install.radio_state.containment_failed","unknown":"install.radio_state.unknown"});
  var CONFIG_IMPORT_STATUS = Object.freeze({"too-large":"install.config_import_status.too_large","timeout":"install.config_import_status.timeout","bad-bundle":"install.config_import_status.bad_bundle","wrong-kind-or-schema":"install.config_import_status.wrong_kind_or_schema","bad-expected-cfg":"install.config_import_status.bad_expected_cfg","rejected":"install.config_import_status.rejected","dry_run":"install.config_import_status.dry_run","no-op":"install.config_import_status.no_op","stale-preview":"install.config_import_status.stale_preview","error":"install.config_import_status.error","database-compatibility-refused":"install.config_import_status.database_compatibility_refused","applied":"install.config_import_status.applied","partial":"install.config_import_status.partial","restored":"install.config_import_status.restored"});
  var COMPONENT_LABEL = Object.freeze({paneld:'ha-paneld',companion:'HA Companion',webview:'System WebView',apk:'APK'});
  var POWER_PRESENTATION_STATE = Object.freeze({'status-power-at-risk':'at_risk','status-power-caution':'caution','status-power-unknown':'unknown'});
  function closedToken(table, value) {
    var raw = String(value == null ? '' : value), key = table[raw];
    return key && localized(key) ? t(key, raw) : raw;
  }
  function validParam(name, value) {
    if (typeof value !== 'string' || value.length > 512) return false;
    if (own.call(ENUMS, name)) return ENUMS[name].indexOf(value) >= 0;
    if (name === 'package') return value.length <= 255 && /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(value);
    if (name === 'count' || name === 'from_schema' || name === 'to_schema' || name === 'attempts') return /^(0|[1-9][0-9]{0,9})$/.test(value);
    if (/^(usable_bytes|total_bytes|database_bytes|wal_bytes)$/.test(name)) return /^(0|[1-9][0-9]{0,18})$/.test(value);
    if (name === 'used_percent') return /^(100(?:\.0+)?|[0-9]{1,2}(?:\.[0-9]+)?)$/.test(value);
    if (name === 'target_chromium') return /^[1-9][0-9]{0,2}$/.test(value);
    if (name === 'release_url') { try { var url = new URL(value); return url.protocol === 'https:' && value.length <= 512; } catch (_) { return false; } }
    if (name === 'bound_ip' || name === 'lan_ip') return value.length > 0 && value.length <= 45;
    if (/^(version|current|latest|cap|current_engine)$/.test(name)) return value.length > 0 && value.length <= 128;
    return true;
  }
  function envelopeBytes(value) {
    try {
      var json = JSON.stringify(value);
      if (typeof TextEncoder === 'function') return new TextEncoder().encode(json).length;
      return encodeURIComponent(json).replace(/%[0-9A-F]{2}|./g, 'x').length;
    } catch (_) { return 2049; }
  }
  function presentation(envelope, compatibility) {
    var fallback = String(compatibility == null ? '' : compatibility);
    if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope) || Object.keys(envelope).sort().join(',') !== 'code,params') return { text: fallback, fallback: true };
    var code = envelope.code, params = envelope.params;
    if (typeof code !== 'string' || code.length > 64 || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(code) || !own.call(PRESENTATIONS, code)) return { text: fallback, fallback: true };
    if (!params || typeof params !== 'object' || Array.isArray(params) || Object.keys(params).length > 8 || envelopeBytes(envelope) > 2048) return { text: fallback, fallback: true };
    var rule = PARAMS[code] || {}, required = rule.required || [], optional = rule.optional || [], names = Object.keys(params);
    if (required.some(function (name) { return !own.call(params, name); }) || names.some(function (name) { return required.indexOf(name) < 0 && optional.indexOf(name) < 0; }) || names.some(function (name) { return !validParam(name, params[name]); })) return { text: fallback, fallback: true };
    var keySpec = PRESENTATIONS[code], key = keySpec;
    if (typeof keySpec === 'object') {
      var count = Number(params[keySpec.selector]);
      var category = count === 1 ? 'one' : 'other';
      try { category = new Intl.PluralRules(locale).select(count) === 'one' ? 'one' : 'other'; } catch (_) {}
      key = keySpec[category];
    }
    if (!localized(key)) return { text: fallback, fallback: true };
    var displayParams = Object.assign({}, params);
    if (own.call(displayParams, 'component')) displayParams.component = COMPONENT_LABEL[displayParams.component];
    return { text: t(key, fallback, displayParams), fallback: false, rawEvidence: !!rule.raw, compatibility: fallback };
  }
  function setPresented(node, envelope, compatibility) {
    if (!node) return;
    var value = presentation(envelope, compatibility);
    node.removeAttribute('lang');
    if (value.fallback) {
      node.textContent = value.text;
      if (locale !== 'en') node.setAttribute('lang', 'en');
    } else if (value.rawEvidence && value.compatibility && value.compatibility !== value.text && typeof document.createTextNode === 'function') {
      node.textContent = '';
      var controlled = document.createElement('span'); controlled.textContent = value.text; node.appendChild(controlled);
      node.appendChild(document.createTextNode(' — '));
      var evidence = document.createElement('span'); evidence.className = 'install-raw-evidence'; evidence.setAttribute('lang', 'en'); evidence.textContent = value.compatibility; node.appendChild(evidence);
    } else if (envelope && envelope.code === 'status-update-available' && envelope.params && envelope.params.release_url && value.text.indexOf(envelope.params.release_url) >= 0 && typeof document.createTextNode === 'function') {
      var releaseUrl = envelope.params.release_url, parts = value.text.split(releaseUrl);
      node.textContent = ''; node.appendChild(document.createTextNode(parts.shift()));
      var link = document.createElement('a'); link.href = releaseUrl; link.textContent = releaseUrl; node.appendChild(link);
      node.appendChild(document.createTextNode(parts.join(releaseUrl)));
    } else node.textContent = value.text;
  }
  function appendEnglishEvidence(node, text, separator) {
    if (!node || text == null || text === '' || typeof document.createElement !== 'function') return;
    if (separator) node.appendChild(document.createTextNode(separator));
    var evidence = document.createElement('span');
    evidence.className = 'install-raw-evidence'; evidence.setAttribute('lang', 'en'); evidence.textContent = String(text);
    node.appendChild(evidence);
  }
  function renderPresentedEvidence(node, envelope, compatibility, labelKey, labelFallback) {
    if (!node) return;
    var exact = String(compatibility == null ? '' : compatibility), value = presentation(envelope, exact);
    node.removeAttribute('lang'); node.textContent = '';
    if (labelKey) {
      var label = document.createElement('span'); label.textContent = t(labelKey, labelFallback); node.appendChild(label);
      node.appendChild(document.createTextNode(' '));
    }
    if (value.fallback) appendEnglishEvidence(node, exact, '');
    else {
      var controlled = document.createElement('span'); controlled.textContent = value.text; node.appendChild(controlled);
      if (value.rawEvidence && exact && exact !== value.text) appendEnglishEvidence(node, exact, ' — ');
    }
  }
  function renderFailure(node, key, fallback, diagnostic) {
    if (!node) return;
    node.removeAttribute('lang'); node.textContent = '';
    var controlled = document.createElement('span'); controlled.textContent = t(key, fallback); node.appendChild(controlled);
    appendEnglishEvidence(node, String(diagnostic == null || diagnostic === '' ? 'request failed' : diagnostic), ' ');
  }
  function installCardHref(fragment) {
    var params = new URLSearchParams(location.search), supported = ['en', 'de', 'fr', 'it', 'es', 'zh-Hans', 'nl', 'pl', 'uk', 'en-XA'];
    var explicit = params.get('lang');
    if (supported.indexOf(explicit) < 0) return 'install' + fragment;
    return 'install?lang=' + encodeURIComponent(explicit) + fragment;
  }
  function renderPowerWarning(node, envelope, compatibility, advisory) {
    var expected = envelope && POWER_PRESENTATION_STATE[envelope.code];
    if (!expected || !advisory || typeof advisory !== 'object' || Array.isArray(advisory) || advisory.state !== expected || advisory.warning !== true || typeof advisory.summary !== 'string' || typeof advisory.action !== 'string' || advisory.summary.length > 2048 || advisory.action.length > 2048) return false;
    var value = presentation(envelope, compatibility);
    if (value.fallback) return false;
    node.textContent = '';
    var controlled = document.createElement('span'); controlled.textContent = value.text; node.appendChild(controlled);
    if (advisory.summary) appendEnglishEvidence(node, advisory.summary, ' — ');
    if (advisory.action) appendEnglishEvidence(node, advisory.action, ' ');
    return true;
  }
  function renderWarnings(node, warnings, overlays, powerSafety) {
    var validOverlay = Array.isArray(overlays) && overlays.length === warnings.length && overlays.length <= 11;
    node.textContent = '';
    warnings.forEach(function (legacy, index) {
      var row = document.createElement('div'); row.className = 'setup';
      var envelope = validOverlay ? overlays[index] : null;
      var value = presentation(envelope, legacy);
      if (own.call(POWER_PRESENTATION_STATE, envelope && envelope.code)) {
        if (!renderPowerWarning(row, envelope, legacy, powerSafety)) { row.setAttribute('lang', 'en'); row.innerHTML = String(legacy == null ? '' : legacy); }
      } else if (!value.fallback) setPresented(row, envelope, legacy);
      else { row.setAttribute('lang', 'en'); row.innerHTML = String(legacy == null ? '' : legacy); }
      node.appendChild(row);
    });
  }
  // Shared only as a frozen renderer so browser tests and future Install-owned fragments exercise the
  // same fail-closed contract rather than copying validation logic.
  window.HaPaneldInstallPresentation = Object.freeze({ present: presentation, set: setPresented, renderRestoreResult: renderRestoreResult });
  var hardenedApprovalTitle = t('configure.hardened.action_approval', 'Requires physical on-panel approval for this action when Hardened mode is enabled.');
  var hardenedApprovalAttrs = ' data-hardened-approval aria-describedby="hardened-approval-description" title="' + esc(hardenedApprovalTitle) + '"';
  var hardenedApprovalA11yAttrs = ' aria-describedby="hardened-approval-description" title="' + esc(hardenedApprovalTitle) + '"';
  var scheduleInstallColumnAlignment = function () {};
  var installCardMemoryReady = false, installInitialLoadsComplete = false;
  var msg = function (t) {
    var e = document.getElementById('comp-msg');
    if (e && e.textContent !== t) { e.textContent = t; scheduleInstallColumnAlignment(); }
  };
  var msgPresented = function (envelope, fallback) {
    var e = document.getElementById('comp-msg');
    if (e) { setPresented(e, envelope, fallback); scheduleInstallColumnAlignment(); }
  };
  var row = function (name) { return document.querySelector('.comprow[data-name="' + name + '"]'); };
  function markVersionGeometryValid() {
    if (installInitialLoadsComplete && !installCardMemoryReady) { installCardMemoryReady = true; scheduleInstallColumnAlignment(); }
  }

  function approvalMessage(body) {
    return body && body.message || t('install.progress.approval_retry', 'Approve this request on the panel, then retry it.');
  }

  function approvalError(body) {
    var error = new Error(approvalMessage(body));
    error.approvalRequired = true;
    error.body = body || {};
    return error;
  }

  // HTTP 202 is also used for ordinary asynchronous work, so only the structured
  // approval-required body stops the success path.
  function approvalAwareJson(response) {
    var decoded = typeof response.text === 'function'
      ? response.text().then(function (text) {
          try { return text ? JSON.parse(text) : {}; }
          catch (_) { return { message: String(text || '').trim() }; }
        })
      : response.json();
    return decoded.then(function (body) {
      if (response.status === 202 && body && body.error === 'approval-required') throw approvalError(body);
      return body;
    });
  }

  function requestFailure(error, fallback) {
    return error && error.approvalRequired ? error.message : fallback;
  }

  // Release metadata comes from the GitHub API. Keep link navigation on GitHub HTTPS even if an
  // upstream response is malformed or replaced; package/signer checks remain the install boundary.
  function safeGithubUrl(value) {
    try {
      var parsed = new URL(value);
      return parsed.protocol === 'https:' && parsed.hostname.toLowerCase() === 'github.com' ? parsed.href : '';
    } catch (_) { return ''; }
  }

  function setSafeLink(link, value, visibleProperty, visibleValue, hiddenValue) {
    if (!link) return;
    var safe = safeGithubUrl(value);
    if (safe) {
      link.href = safe;
      link.style[visibleProperty] = visibleValue;
    } else {
      link.removeAttribute('href');
      link.style[visibleProperty] = hiddenValue;
    }
  }

  // Populate a component's version <select> for the chosen channel, newest first. Pre-select the newest
  // installable release; fall back to the installed release only when the channel has no installable APK.
  window.loadVersions = function (name) {
    var r = row(name); if (!r) return;
    var chan = r.querySelector('.cchan').value, vsel = r.querySelector('.cvsel');
    var installed = (r.querySelector('.cver') || {}).textContent || '';
    vsel.textContent = '';
    var loading = document.createElement('option'); loading.textContent = t('install.shared.loading', 'loading…'); vsel.appendChild(loading);
    return fetch('api/v1/install/versions?name=' + encodeURIComponent(name) + '&channel=' + encodeURIComponent(chan))
      .then(function (res) { return res.json(); }).then(function (d) {
        var vs = (d && d.versions) || [];
        if (!vs.length) { vsel.textContent = ''; var none = document.createElement('option'); none.value = ''; none.textContent = t('install.progress.no_versions', 'no versions found'); vsel.appendChild(none); verChanged(name); markVersionGeometryValid(); return true; }
        vsel.innerHTML = '';
        var firstInstallable = -1, installedIndex = -1;
        vs.forEach(function (v, i) {
          var o = document.createElement('option');
          var unavailable = '';
          if (!v.installable) {
            if (v.unavailableReason === 'above_panel_limit') unavailable = t('install.progress.above_panel_limit', '(not supported by this panel; limit {version})', { version: v.maxVersion || '' });
            else if (v.unavailableReason === 'older_app_id') unavailable = t('install.progress.older_app_id', '(older app, cannot replace this one)');
            else unavailable = t('install.progress.no_matching_asset', '(no compatible download)');
          }
          o.value = v.tag; o.textContent = v.version + (unavailable ? ' ' + unavailable : '');
          o.setAttribute('data-notes', v.notes || ''); o.setAttribute('data-installable', v.installable ? '1' : '0');
          o.setAttribute('data-action', presentation(v.presentations && v.presentations.action, v.action || 'Install').text);
          o.setAttribute('data-apk', v.apk || '');
          if (firstInstallable < 0 && v.installable) firstInstallable = i;
          if (v.version === installed) installedIndex = i;
          vsel.appendChild(o);
        });
        vsel.selectedIndex = firstInstallable >= 0 ? firstInstallable : (installedIndex >= 0 ? installedIndex : 0);
        verChanged(name);
        markVersionGeometryValid();
        return true;
      }).catch(function () { installCardMemoryReady = false; vsel.textContent = ''; var failed = document.createElement('option'); failed.value = ''; failed.textContent = t('install.progress.check_failed', 'check failed'); vsel.appendChild(failed); verChanged(name);
        if (window.CardSizeMemory) window.CardSizeMemory.invalidate('install-cards');return false; });
  };

  // Sync the release-notes link + Install button to the currently-selected version.
  window.verChanged = function (name) {
    var r = row(name); if (!r) return;
    var o = r.querySelector('.cvsel').selectedOptions[0];
    var notes = r.querySelector('.cnotes'), btn = r.querySelector('.cinstall'), dl = r.querySelector('.cdl');
    var url = o ? o.getAttribute('data-notes') : '', installable = o && o.getAttribute('data-installable') === '1';
    setSafeLink(notes, url, 'visibility', 'visible', 'hidden');
    if (btn) {
      btn.textContent = o ? (o.getAttribute('data-action') || t('install.components.install', 'Install')) : t('install.components.install', 'Install');
      btn.disabled = !(btn.getAttribute('data-root') === '1' && installable);
    }
    // No-root panels render a Download link instead of the Install button (ha-paneld can't install
    // APKs without root): point it at the selected version's APK asset for a manual adb install -r.
    if (dl) {
      var apk = o ? o.getAttribute('data-apk') : '';
      setSafeLink(dl, installable ? apk : '', 'display', '', 'none');
    }
    scheduleInstallColumnAlignment();
  };

  // Install the selected version of a picker component.
  window.installSel = function (name, btn) {
    var r = row(name); if (!r) return;
    var o = r.querySelector('.cvsel').selectedOptions[0];
    if (!o || !o.value) return;
    start(name, { version: o.value }, btn);
  };

  // Single-action components (WebView heal, latest update/reinstall).
  window.installComp = function (name, action, btn) { start(name, { action: action }, btn); };

  // Shared: POST the install, then poll /install/status until done and reload so versions re-render.
  function start(name, extra, btn) {
    if (btn) btn.disabled = true;
    msg(t('install.progress.starting', 'Starting {component}…', { component: name }));
    var body = 'name=' + encodeURIComponent(name);
    if (extra.action) body += '&action=' + encodeURIComponent(extra.action);
    if (extra.version) body += '&version=' + encodeURIComponent(extra.version);
    fetch('api/v1/install/component', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body })
      .then(approvalAwareJson).then(function (d) {
        if (d.status === 'busy') { msg(t('install.progress.busy', 'Another install is already running — try again shortly.')); if (btn) btn.disabled = false; return; }
        if (d.status !== 'started') { var status = d.status || 'error'; msg(t('install.progress.could_not_start', 'Could not start: {status}', { status: closedToken(COMPONENT_STATUS, status) })); if (btn) btn.disabled = false; return; }
        pollInstall(0);
      }).catch(function (error) { msg(requestFailure(error, t('install.progress.start_failed', 'Failed to start — check root/daemon.'))); if (btn) btn.disabled = false; });
  }

  function pollInstall(n) {
    fetch('api/v1/install/status').then(function (r) { return r.json(); }).then(function (d) {
      if (d.running) { var workingFallback = t('install.progress.working', '{component}: working…', { component: d.component || t('install.components.install', 'Install') }); msgPresented(d.presentation, workingFallback); setTimeout(function () { pollInstall(n + 1); }, 2500); return; }
      var result = presentation(d.presentation, d.message || 'done');
      var done = t('install.progress.done_reload', '{component}: {result} — reloading…', { component: d.component || t('install.components.install', 'Install'), result: result.text });
      if (result.rawEvidence && !result.fallback) msgPresented(d.presentation, d.message || 'done'); else msg(done);
      setTimeout(function () { location.reload(); }, 2000);
    }).catch(function () {
      // Transient (e.g. ha-paneld restarted itself after a self-update) — keep trying a while.
      if (n < 40) setTimeout(function () { pollInstall(n + 1); }, 3000); else msg(t('install.progress.lost_contact_reload', 'Lost contact — reload to check.'));
    });
  }

  // Companion internal-URL repair (HA 2026.7 "Missing 'Host' header"): copy external_url into a blank
  // internal_url, then force-stop + relaunch the Companion.
  window.repairCompUrl = function (btn) {
    btn.disabled = true; var s = document.getElementById('cu-fix');
    if (s) s.textContent = t('install.progress.companion_repairing', 'Repairing + relaunching the Companion…');
    fetch('api/v1/companion/repair-url', { method: 'POST' }).then(approvalAwareJson).then(function (d) {
      if (d.status === 'busy') { if (s) s.textContent = t('install.progress.operation_busy', 'Another operation is running — try again shortly.'); btn.disabled = false; return; }
      if (s) setPresented(s, d.presentation, t('install.progress.companion_repair_started', 'Repair started — the Companion will relaunch; refresh this page in a few seconds to confirm.'));
    }).catch(function (error) { if (s) s.textContent = requestFailure(error, t('install.progress.companion_repair_failed', 'Failed to start — check root.')); btn.disabled = false; });
  };

  // On-demand health audit: force a fresh update check + re-probe, render the warnings inline.
  window.healthAudit = function (btn) {
    btn.disabled = true; var out = document.getElementById('audit-out');
    if (out) { out.textContent = ''; var checking = document.createElement('p'); checking.className = 'note'; checking.textContent = t('install.progress.audit_checking', 'Checking…'); out.appendChild(checking); scheduleInstallColumnAlignment(); }
    fetch('api/v1/status?refresh=1').then(function (r) { return r.json(); }).then(function (d) {
      var w = (d && d.warnings) || [];
      if (!out) return;
      if (!w.length) { out.textContent = ''; var clean = document.createElement('p'); clean.className = 'note'; clean.textContent = '✓ ' + t('install.progress.audit_clean', 'No problems detected — this panel looks ready.'); out.appendChild(clean); }
      else { renderWarnings(out, w, d.warning_presentations, d.power_safety); }
      btn.disabled = false; scheduleInstallColumnAlignment();
    }).catch(function () {
      if (out) { out.textContent = ''; var failure = document.createElement('p'); failure.className = 'note'; failure.textContent = t('install.progress.audit_failed', 'Audit failed — try again.'); out.appendChild(failure); }
      btn.disabled = false; scheduleInstallColumnAlignment();
    });
  };

  // --- APK upload (⚠ root/helper-installs an arbitrary APK; parse-then-confirm) ---
  var apkMsg = function (t) {
    var e = document.getElementById('apk-msg');
    if (e && e.textContent !== t) { e.textContent = t; scheduleInstallColumnAlignment(); }
  };

  window.apkAllow = function (cb) {
    var ui = document.getElementById('apk-ui'); if (ui) ui.style.display = cb.checked ? '' : 'none';
    scheduleInstallColumnAlignment();
    fetch('api/v1/install/apk/allow', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'on=' + (cb.checked ? '1' : '0') }).catch(function () {});
  };

  // One counter across BOTH APK sources. Whichever the operator acted on last owns the preview, so a
  // slower earlier response can never paint itself over a newer action and offer a token that does not
  // belong to what the operator is looking at.
  var apkPreviewGeneration = 0;
  var apkFetchRequest = null;

  // Server refusal codes in the operator's words. An administrator doing this from a phone, away from
  // the panel, cannot inspect logs — the difference between "too big", "stalled" and "unreachable" is
  // the difference between knowing what to do next and guessing.
  var APK_ERRORS = {
    'disabled': 'APK install is switched off on this panel',
    'no-root': 'this panel has no root or helper daemon',
    'invalid-url': 'that is not a valid https:// link',
    'upload-busy': 'the panel is still busy with another APK — try again shortly',
    'stopping': 'the panel is shutting down',
    'upload-staging-failed': 'the panel could not open a staging file',
    'insufficient-storage': 'not enough free space on the panel',
    'upload-too-large': 'that file is larger than the panel accepts',
    'fetch-too-large': 'that file is larger than the panel accepts',
    'upload-timeout': 'the upload took too long',
    'fetch-timeout': 'the download stalled or took too long',
    'fetch-failed': 'the panel could not download that link',
    'cancelled': 'the download was cancelled',
    'invalid-request': 'the panel rejected this request identifier',
    'not-an-apk': 'that file is not a readable APK'
  };
  function apkErrorText(code) {
    var fallback = APK_ERRORS[code] || code || 'error', key = APK_STATUS[code];
    return key && localized(key) ? t(key, fallback) : fallback;
  }

  // Render the inspected identity + the approval-marked Confirm-install button. Shared by both APK
  // sources so a fetched APK gets exactly the review an uploaded one does, from the same token.
  // Cancel sits beside Install because the identity shown here is the moment the operator learns they
  // picked the wrong file — the original report navigated Back here, and Back abandons the staged
  // file without releasing it.
  function renderApkPreview(prev, d, failedLabel) {
    if (!prev) return;
    if (!d.ok) {
      if (d.error === 'upload-busy') { renderApkBusyRecovery(prev, failedLabel); return; }
      prev.innerHTML = '<p class="note">' + failedLabel + ': ' + esc(apkErrorText(d.error)) + '</p>';
      scheduleInstallColumnAlignment();
      return;
    }
    prev.innerHTML = '<table class="dt"><tr><th>' + esc(t('install.apk.dynamic.package', 'Package')) + '</th><td>' + esc(d.package) + '</td></tr>' +
      '<tr><th>' + esc(t('install.shared.version', 'Version')) + '</th><td>' + esc(d.version) + '</td></tr>' +
      '<tr><th>' + esc(t('install.apk.dynamic.signer', 'Signer SHA-256')) + '</th><td style="word-break:break-all">' + esc(d.signer) + '</td></tr></table>' +
      '<div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:8px">' +
      '<button class="pbtn"' + hardenedApprovalAttrs + ' data-token="' + esc(d.token) + '" onclick="apkInstall(this)">⬇ ' + esc(t('install.apk.dynamic.install', 'Install {package}', { package: d.package })) + '</button>' +
      (locale === 'en' ? '<button class="pbtn" data-token="' + esc(d.token) + '" onclick="apkDiscard(this)">✕ Cancel</button>' : '<button class="pbtn" data-token="' + esc(d.token) + '" onclick="apkDiscard(this)">✕ ' + esc(t('install.apk.dynamic.cancel', 'Cancel')) + '</button>') +
      '</div>' +
      '<p class="note">' + esc(t('install.apk.dynamic.wrong_apk', 'Wrong APK? Cancel deletes it from the panel — or just choose another file or link, which replaces it.')) + '</p>';
    scheduleInstallColumnAlignment();
  }

  // "upload-busy" can mean two different situations needing different actions: a transfer is still
  // arriving (wait), or an inspected upload is holding the slot (discard it). Ask the panel which,
  // and only offer "Discard pending upload" when there is actually something to discard.
  function renderApkBusyRecovery(prev, failedLabel) {
    prev.innerHTML = '<p class="note">' + failedLabel + ': ' + esc(apkErrorText('upload-busy')) + '</p>';
    scheduleInstallColumnAlignment();
    var mine = apkPreviewGeneration;
    // A failed probe deliberately changes nothing: the busy text is already painted, and inventing a
    // discard offer without knowing something is staged is the dishonesty this probe exists to avoid.
    fetch('api/v1/install/apk/pending').then(function (r) { return r.json(); }).then(function (d) {
      if (mine !== apkPreviewGeneration || !d.pending) return;
      renderApkPendingRecovery(prev, d);
    }).catch(function () {});
  }

  // A staged upload survives the browser that made it. After a reload (token gone) or a busy answer,
  // name what the panel is holding and offer to discard it. The button carries the probe's discard
  // reference — not the commit token, which this surface never sees — so the discard is scoped to
  // exactly the entry being shown: if a newer upload has replaced it, the panel refuses and this page
  // re-probes rather than deleting something the operator never saw.
  function renderApkPendingRecovery(prev, d) {
    if (!prev) return;
    prev.innerHTML = '<p class="note">' + esc(t('install.apk.dynamic.pending', 'The panel is holding a previously inspected APK: {package} {version}. Uploading or fetching a new APK will replace it.', { package: d.package, version: d.version })) + '</p>' +
      (locale === 'en' ? '<button class="pbtn" data-token="' + esc(d.discard) + '" onclick="apkDiscard(this)">✕ Discard pending upload</button>' : '<button class="pbtn" data-token="' + esc(d.discard) + '" onclick="apkDiscard(this)">✕ ' + esc(t('install.apk.dynamic.discard_pending', 'Discard pending upload')) + '</button>');
    scheduleInstallColumnAlignment();
  }

  // After a page load the browser holds no preview token, but the panel may still be holding an
  // inspected upload from before the reload. Probe and surface it rather than letting the operator
  // rediscover it as an error.
  function apkProbePending() {
    var prev = document.getElementById('apk-preview');
    if (!prev) return;
    var mine = apkPreviewGeneration;
    // Fail-quiet on purpose: this is a page-load enhancement, and a probe error must not paint a
    // recovery card the panel never confirmed. A real pending entry resurfaces on the next action
    // as upload-busy, which re-probes.
    fetch('api/v1/install/apk/pending').then(function (r) { return r.json(); }).then(function (d) {
      if (mine !== apkPreviewGeneration || !d.pending) return;
      renderApkPendingRecovery(prev, d);
    }).catch(function () {});
  }

  // Upload the chosen APK (raw body), then render its parsed identity + a Confirm-install button.
  window.apkPick = function (input) {
    var f = input.files && input.files[0]; if (!f) return;
    var prev = document.getElementById('apk-preview');
    apkMsg('');
    var mine = ++apkPreviewGeneration;
    apkFetchRequest = null;
    if (prev) { prev.innerHTML = '<p class="note">' + esc(t('install.apk.dynamic.uploading', 'Uploading + inspecting {filename}…', { filename: f.name })) + '</p>'; scheduleInstallColumnAlignment(); }
    fetch('api/v1/install/apk', { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: f })
      .then(function (r) { return r.json(); }).then(function (d) {
        if (mine !== apkPreviewGeneration) return;
        renderApkPreview(prev, d, t('install.apk.dynamic.upload_failed', 'Upload failed'));
      }).catch(function () {
        if (mine !== apkPreviewGeneration) return;
        if (prev) prev.innerHTML = '<p class="note">' + esc(t('install.apk.dynamic.upload_failed_sentence', 'Upload failed.')) + '</p>';
        scheduleInstallColumnAlignment();
      });
  };

  // Have the panel fetch the APK itself. Same review, same token, same commit route as an upload —
  // this only changes where the bytes come from.
  //
  // Cancel tells the PANEL to stop, rather than only abandoning the browser's wait. That distinction
  // matters: a browser-side abort would leave the panel downloading and its staging slot held, so the
  // next attempt would be refused as busy while the page claimed the download had been cancelled.
  function newRequestId() {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID().replace(/-/g, '');
    return 'r' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
  }

  window.apkFetchUrl = function () {
    var input = document.getElementById('apk-url');
    var url = input ? String(input.value || '').trim() : '';
    var prev = document.getElementById('apk-preview');
    if (!url) { apkMsg(t('install.apk.dynamic.paste_url', 'Paste an https:// link to an APK first.')); return; }
    apkMsg('');
    var request = newRequestId();
    var mine = ++apkPreviewGeneration;
    apkFetchRequest = request;
    if (prev) {
      prev.innerHTML = '<p class="note">' + esc(t('install.apk.dynamic.downloading', 'Downloading + inspecting…')) + '</p>' +
        '<button class="pbtn" style="margin-top:8px" onclick="apkCancelFetch()">' + esc(t('install.apk.dynamic.cancel', 'Cancel')) + '</button>';
      scheduleInstallColumnAlignment();
    }
    fetch('api/v1/install/apk/from-url', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'url=' + encodeURIComponent(url) + '&request=' + encodeURIComponent(request)
    }).then(approvalAwareJson).then(function (d) {
      if (mine !== apkPreviewGeneration) return;
      apkFetchRequest = null;
      if (!d.ok && d.error === 'cancelled') {
        if (prev) { prev.innerHTML = '<p class="note">' + esc(t('install.apk.dynamic.download_cancelled', 'Download cancelled — the panel stopped it.')) + '</p>'; scheduleInstallColumnAlignment(); }
        return;
      }
      renderApkPreview(prev, d, t('install.apk.dynamic.download_failed', 'Download failed'));
    }).catch(function (error) {
      if (mine !== apkPreviewGeneration) return;
      apkFetchRequest = null;
      if (prev) {
        prev.innerHTML = '<p class="note">' + esc(requestFailure(error, t('install.apk.dynamic.download_failed_sentence', 'Download failed.'))) + '</p>';
        scheduleInstallColumnAlignment();
      }
    });
  };

  // Names the request being stopped, so this can never cancel a download the operator has since
  // started in its place.
  window.apkCancelFetch = function () {
    var request = apkFetchRequest;
    if (!request) return;
    apkMsg(t('install.apk.dynamic.cancelling', 'Cancelling…'));
    fetch('api/v1/install/apk/fetch/cancel', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'request=' + encodeURIComponent(request)
    }).then(function () { apkMsg(''); }).catch(function () { apkMsg(''); });
  };

  // Cancel/Discard is the counterpart of Install: it retires the inspected upload and deletes its
  // staged bytes on the panel. Every discard is scoped — the preview button carries the commit token,
  // the recovery button the probe's discard reference — so it can only remove the exact entry the
  // operator is looking at. It never touches a running install: an entry is claimed only when its
  // install genuinely starts, so a committed APK has left the pending slot for good.
  window.apkDiscard = function (btn) {
    var prev = document.getElementById('apk-preview');
    var token = btn.getAttribute('data-token') || '';
    var mine = ++apkPreviewGeneration;
    btn.disabled = true;
    apkMsg('');
    fetch('api/v1/install/apk/discard', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'token=' + encodeURIComponent(token)
    }).then(function (r) { return r.json(); }).then(function (d) {
      if (mine !== apkPreviewGeneration) return;
      if (d.ok) {
        if (prev) {
          prev.innerHTML = '<p class="note">' + esc(d.discarded ? t('install.apk.dynamic.discarded', 'Pending APK discarded.') : t('install.apk.dynamic.none_pending', 'No staged APK is pending.')) + '</p>';
          scheduleInstallColumnAlignment();
        }
        return;
      }
      // Refused: this view is stale — a newer upload owns the slot. Say so, then repaint from the
      // panel's truth instead of leaving a card that lies.
      if (prev) {
        prev.innerHTML = '<p class="note">' + esc(t('install.apk.dynamic.pending_changed', 'The pending upload changed — checking what the panel is holding…')) + '</p>';
        scheduleInstallColumnAlignment();
      }
      apkProbePending();
    }).catch(function (error) {
      btn.disabled = false;
      if (mine !== apkPreviewGeneration) return;
      apkMsg(requestFailure(error, t('install.apk.dynamic.discard_failed', 'Failed to discard.')));
    });
  };

  window.apkInstall = function (btn) {
    btn.disabled = true; apkMsg(t('install.apk.dynamic.installing', 'Installing…'));
    var body = 'token=' + encodeURIComponent(btn.getAttribute('data-token') || '');
    fetch('api/v1/install/apk/commit', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body }).then(approvalAwareJson).then(function (d) {
      if (d.status === 'busy') { apkMsg(t('install.apk.dynamic.install_busy', 'Another install is running — try again shortly.')); btn.disabled = false; return; }
      if (d.status === 'stale-or-missing') { apkMsg(t('install.apk.dynamic.stale', 'This APK was replaced or expired — choose or fetch it again.')); btn.disabled = false; return; }
      if (d.status !== 'started') { var status = d.status || 'error'; apkMsg(t('install.progress.could_not_start', 'Could not start: {status}', { status: apkErrorText(status) })); btn.disabled = false; return; }
      var prev = document.getElementById('apk-preview');
      if (prev) prev.querySelectorAll('button').forEach(function (action) { action.disabled = true; });
      pollApk(0);
    }).catch(function (error) { apkMsg(requestFailure(error, t('install.apk.dynamic.start_failed', 'Failed to start.'))); btn.disabled = false; });
  };

  function pollApk(n) {
    fetch('api/v1/install/status').then(function (r) { return r.json(); }).then(function (d) {
      if (d.running) { apkMsg(t('install.apk.dynamic.installing', 'Installing…')); setTimeout(function () { pollApk(n + 1); }, 2000); return; }
      var node = document.getElementById('apk-msg');
      if (node) { renderPresentedEvidence(node, d.presentation, d.message || 'done', 'install.apk.dynamic.result_label', 'Result:'); scheduleInstallColumnAlignment(); }
    }).catch(function () { if (n < 20) setTimeout(function () { pollApk(n + 1); }, 2500); else apkMsg(t('install.apk.dynamic.lost_contact', 'Lost contact.')); });
  }

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

  // --- Uninstall an app ---
  function loadPackages() {
    var sel = document.getElementById('uninst-pkg'); if (!sel) return Promise.resolve();
    return fetch('api/v1/packages').then(function (r) { return r.json(); }).then(function (d) {
      var ps = (d && d.packages) || [];
      if (!ps.length) { sel.textContent = ''; var none = document.createElement('option'); none.value = ''; none.textContent = t('install.uninstall.dynamic.none', 'no removable apps'); sel.appendChild(none); return true; }
      sel.innerHTML = ps.map(function (p) { return '<option value="' + esc(p.pkg) + '">' + esc(p.label) + ' (' + esc(p.pkg) + ')</option>'; }).join('');
      return true;
    }).catch(function () { sel.textContent = ''; var failed = document.createElement('option'); failed.value = ''; failed.textContent = t('install.uninstall.dynamic.load_failed', 'load failed'); sel.appendChild(failed); return false; });
  }
  window.doUninstall = function (btn) {
    var sel = document.getElementById('uninst-pkg'), msg = document.getElementById('uninst-msg');
    var pkg = sel && sel.value; if (!pkg) return;
    if (!confirm(t('install.uninstall.dynamic.confirm', 'Uninstall {package}? This removes the app and its data.', { package: pkg }))) return;
    btn.disabled = true; if (msg) msg.textContent = t('install.uninstall.dynamic.working', 'Uninstalling {package}…', { package: pkg });
    fetch('api/v1/uninstall', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'pkg=' + encodeURIComponent(pkg) })
      .then(approvalAwareJson).then(function (d) {
        if (msg) {
          var compatibility = d.ok ? ('Uninstalled ' + pkg) : ('Failed: ' + (d.result || d.error || 'error'));
          renderPresentedEvidence(msg, d.presentation, compatibility);
        }
        btn.disabled = false; if (d.ok) loadPackages();
      }).catch(function (error) { if (msg) msg.textContent = requestFailure(error, t('install.uninstall.dynamic.request_failed', 'Failed.')); btn.disabled = false; });
  };
  var initialPackagesLoad = loadPackages();

  // --- Encrypted backup / restore ---
  var bkMsg = function (t) {
    var e = document.getElementById('bk-msg');
    if (e && e.textContent !== t) { e.textContent = t; scheduleInstallColumnAlignment(); }
  };
  function restoreComponentLabel(name) {
    var labels = { config: ['install.restore.result.component.config', 'Config'], profiles: ['install.restore.result.component.profiles', 'Profiles'], companion: ['install.restore.result.component.companion', 'Companion'], rollback: ['install.restore.result.component.rollback', 'Rollback'] };
    return labels[name] ? t(labels[name][0], labels[name][1]) : name;
  }
  function renderRestoreResult(body) {
    var node = document.getElementById('bk-msg');
    if (!node) return;
    if (!body || typeof body !== 'object' || Array.isArray(body)) body = {};
    var topFallback = t('install.backup.dynamic.restore_result', 'Restore: {result}', { result: body.message || 'done' });
    node.textContent = '';
    var top = document.createElement('span'); setPresented(top, body.presentation, topFallback); node.appendChild(top);
    var result = body.result;
    if (!result || typeof result !== 'object' || Array.isArray(result)) { scheduleInstallColumnAlignment(); return; }
    ['config', 'profiles', 'companion', 'rollback'].forEach(function (name) {
      var item = result[name]; if (!item || typeof item !== 'object' || Array.isArray(item)) return;
      node.appendChild(document.createElement('br'));
      var line = document.createElement('span');
      line.appendChild(document.createTextNode(restoreComponentLabel(name) + ': ' + closedToken(RESTORE_OUTCOME, item.status)));
      if (item.items != null) line.appendChild(document.createTextNode(' · ' + String(item.items)));
      if (item.detail || item.presentation) {
        line.appendChild(document.createTextNode(' · '));
        var detail = document.createElement('span'); setPresented(detail, item.presentation, item.detail || closedToken(RESTORE_OUTCOME, item.status)); line.appendChild(detail);
      }
      node.appendChild(line);
    });
    scheduleInstallColumnAlignment();
  }
  var rsFile = null;

  window.doBackup = function (btn) {
    var pw = (document.getElementById('bk-pw') || {}).value || '';
    var plain = !!((document.getElementById('bk-plain') || {}).checked);
    var comp = document.getElementById('bk-comp');
    if (!pw && !plain) {
      bkMsg(t('install.backup.dynamic.passphrase_required', 'Enter a passphrase, or explicitly acknowledge the unencrypted plaintext ZIP.'));
      return;
    }
    btn.disabled = true; bkMsg(pw ? t('install.backup.dynamic.building_encrypted', 'Building encrypted backup…') : t('install.backup.dynamic.building_plain', 'Building unencrypted ZIP…'));
    fetch('api/v1/backup', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: 'passphrase=' + encodeURIComponent(pw) + '&allow_plaintext=' + (plain ? '1' : '0') +
        '&include_companion=' + (comp && comp.checked ? '1' : '0') })
      .then(function (r) {
        // A 202 approval JSON is not an archive. Parse it before the generic 2xx/blob branch so it
        // can never be saved with an .hpb or .zip filename.
        if (r.status === 202) return approvalAwareJson(r).then(function (d) {
          throw new Error(d.message || d.error || t('install.backup.dynamic.accepted_without_archive', 'Backup was accepted without an archive.'));
        });
        if (r.ok) return r.blob();
        return r.json().then(function (d) { throw new Error(d.message || d.error || t('install.backup.dynamic.request_failed', 'request failed')); });
      }).then(function (b) {
        var a = document.createElement('a'), url = URL.createObjectURL(b);
        var switcher = document.getElementById('pswitch'), panelName = switcher && switcher.dataset && switcher.dataset.selfName;
        a.href = url; a.download = (panelName || document.title.split('·')[0] || 'panel').trim() + '-backup.' + (pw ? 'hpb' : 'zip');
        document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
        bkMsg(pw ? t('install.backup.dynamic.downloaded_encrypted', 'Backup downloaded (encrypted) — keep the passphrase.') : t('install.backup.dynamic.downloaded_plain', 'Backup downloaded (unencrypted ZIP).')); btn.disabled = false;
      }).catch(function (e) {
        if (e && e.approvalRequired) bkMsg(e.message);
        else { renderFailure(document.getElementById('bk-msg'), 'install.backup.dynamic.backup_failed_prefix', 'Backup failed:', e && e.message); scheduleInstallColumnAlignment(); }
        btn.disabled = false;
      });
  };

  // Secret-inclusive config export is approval-gated in Hardened mode. Keep the request in-page so
  // an HTTP 202 challenge is rendered as guidance instead of navigating the browser to a JSON body.
  window.configExport = function (includeSecrets, btn) {
    var out = document.getElementById('cfg-export-result');
    if (btn) btn.disabled = true;
    if (out) out.textContent = includeSecrets ? t('install.backup.dynamic.export_requesting_secrets', 'Requesting credential-inclusive export…') : t('install.backup.dynamic.export_building', 'Building configuration export…');
    var path = 'api/v1/config/export' + (includeSecrets ? '?include_secrets=1' : '');
    fetch(path, { headers: { 'Accept': 'application/json' } }).then(function (r) {
      if (r.status === 202) return approvalAwareJson(r).then(function (d) {
        throw new Error(d.message || d.error || t('install.backup.dynamic.export_accepted_without_download', 'Export was accepted without a download.'));
      });
      if (!r.ok) return r.json().catch(function () { return {}; }).then(function (d) {
        throw new Error(d.message || d.error || ('HTTP ' + r.status));
      });
      return r.blob();
    }).then(function (blob) {
      var switcher = document.getElementById('pswitch'), panelName = switcher && switcher.dataset && switcher.dataset.selfName;
      var stem = String(panelName || document.title.split('·')[0] || 'panel').trim().replace(/[^a-zA-Z0-9._-]+/g, '-');
      var link = document.createElement('a'), url = URL.createObjectURL(blob);
      link.href = url; link.download = stem + '-config.json';
      document.body.appendChild(link); link.click(); link.remove(); URL.revokeObjectURL(url);
      if (out) out.textContent = includeSecrets ? t('install.backup.dynamic.export_downloaded_secrets', 'Configuration export downloaded with stored credentials.') : t('install.backup.dynamic.export_downloaded', 'Configuration export downloaded.');
      if (btn) btn.disabled = false;
    }).catch(function (error) {
      if (error && error.approvalRequired) { if (out) out.textContent = error.message; }
      else renderFailure(out, 'install.backup.dynamic.export_failed_prefix', 'Export failed:', error && error.message);
      if (btn) btn.disabled = false;
    });
  };

  // Pick a bundle → preview (dry run, non-destructive) → offer a Confirm restore. Passphrase only needed
  // for an encrypted bundle (the server says so if it is one and the field is blank).
  window.restorePick = function (input) {
    rsFile = input.files && input.files[0]; if (!rsFile) return;
    var pw = (document.getElementById('rs-pw') || {}).value || '';
    var prev = document.getElementById('rs-preview');
    if (prev) { prev.innerHTML = '<p class="note">' + esc(t('install.backup.dynamic.restore_reading', 'Reading preview…')) + '</p>'; scheduleInstallColumnAlignment(); }
    fetch('api/v1/restore?dry_run=1', { method: 'POST', headers: { 'X-Backup-Passphrase': pw }, body: rsFile })
      .then(function (r) { return r.json(); }).then(function (d) {
        if (!prev) return;
        if (!d.ok) { var refusedFallback = d.error || t('install.backup.dynamic.restore_unreadable', 'could not read bundle'); prev.textContent = ''; var refusedNode = document.createElement('p'); refusedNode.className = 'note'; setPresented(refusedNode, d.presentation, refusedFallback); prev.appendChild(refusedNode); scheduleInstallColumnAlignment(); return; }
        var comp = d.companion_files ? pluralText('install.backup.preview_files.one', 'install.backup.preview_files.other', d.companion_files, '{package} ({count} file)', '{package} ({count} files)', { package: d.companion_pkg, count: String(d.companion_files) }) : t('install.backup.dynamic.preview_none', 'none');
        prev.innerHTML = '<table class="dt"><tr><th>' + esc(t('install.backup.dynamic.preview_panel', 'Panel')) + '</th><td>' + esc(d.panel_id) + '</td></tr>' +
          '<tr><th>' + esc(t('install.backup.dynamic.preview_config_keys', 'Config keys')) + '</th><td>' + esc(d.config_keys) + '</td></tr>' +
          '<tr><th>' + esc(t('install.backup.dynamic.preview_companion', 'Companion login')) + '</th><td>' + esc(comp) + '</td></tr></table>' +
          // Only when the bundle marked itself incomplete. A healthy backup says nothing here, so the
          // warning cannot become background furniture the owner stops reading.
          (d.state_unavailable ? '<p class="note">⚠ ' + esc(t('install.backup.dynamic.preview_state_unavailable', 'This backup carries no panel state: the panel it came from could not read its own database. Restoring it will not bring panel state back.')) + '</p>' : '') +
          '<button class="pbtn"' + hardenedApprovalA11yAttrs + ' style="margin-top:8px" onclick="restoreConfirm(this)">⚠ ' + esc(t('install.backup.dynamic.restore_now', 'Restore this bundle now')) + '</button>';
        scheduleInstallColumnAlignment();
      }).catch(function () {
        if (prev) prev.innerHTML = '<p class="note">' + esc(t('install.backup.dynamic.preview_failed', 'Preview failed.')) + '</p>';
        scheduleInstallColumnAlignment();
      });
  };

  window.restoreConfirm = function (btn) {
    if (!rsFile) return;
    if (!confirm(t('install.backup.dynamic.restore_confirm', 'Restore overwrites this panel\'s config and Companion login. Continue?'))) return;
    var pw = (document.getElementById('rs-pw') || {}).value || '';
    btn.disabled = true; bkMsg(t('install.backup.dynamic.restoring', 'Restoring…'));
    fetch('api/v1/restore', { method: 'POST', headers: { 'X-Backup-Passphrase': pw }, body: rsFile })
      .then(approvalAwareJson).then(function (d) {
        if (d.status === 'busy') { bkMsg(t('install.progress.operation_busy', 'Another operation is running — try again shortly.')); btn.disabled = false; return; }
        if (d.status !== 'started') { bkMsg(t('install.progress.could_not_start', 'Could not start: {status}', { status: closedToken(COMPONENT_STATUS, d.status || 'error') })); btn.disabled = false; return; }
        pollRestore(0);
      }).catch(function (error) { bkMsg(requestFailure(error, t('install.backup.dynamic.restore_start_failed', 'Restore failed to start.'))); btn.disabled = false; });
  };

  function pollRestore(n) {
    fetch('api/v1/install/status').then(function (r) { return r.json(); }).then(function (d) {
      if (d.running) { bkMsg(t('install.backup.dynamic.restoring', 'Restoring…')); setTimeout(function () { pollRestore(n + 1); }, 2500); return; }
      renderRestoreResult(d);
    }).catch(function () { if (n < 20) setTimeout(function () { pollRestore(n + 1); }, 3000); else bkMsg(t('install.progress.lost_contact_reload', 'Lost contact — reload to check.')); });
  }

  // Config-only bundles are intentionally separate from full device backups. Preview first and carry
  // the config fingerprint into apply so another writer cannot invalidate the reviewed changes.
  window.configImport = function (input) {
    var file = input.files && input.files[0]; if (!file) return;
    var out = document.getElementById('cfg-import-result');
    file.text().then(function (bodyText) {
      return fetch('api/v1/config/import?dry_run=1', { method: 'POST', body: bodyText })
        .then(function (r) { return r.json().then(function (body) { if (!r.ok) throw (body.status || r.status); return body; }); })
        .then(function (dry) {
          var changes = dry.changes || [];
          var summary = changes.length ? changes.map(function (c) {
            return '  ' + c.key + ': ' + (c.from == null ? '(unset)' : c.from) + ' → ' + c.to;
          }).join('\n') : '  (no changes)';
          var changedLead = pluralText('install.import.changed.one', 'install.import.changed.other', changes.length, 'Import will change {count} setting:', 'Import will change {count} settings:', { count: String(changes.length) });
          if (!confirm(changedLead + '\n\n' + summary + '\n\n' + t('install.import.apply_question', 'Apply now?'))) {
            if (out) out.textContent = t('install.backup.dynamic.import_cancelled', 'Import cancelled.');
            return;
          }
          return fetch('api/v1/config/import?expected_cfg=' + encodeURIComponent(dry.expected_cfg || ''), { method: 'POST', body: bodyText })
            .then(function (r) { return approvalAwareJson(r).then(function (body) { return { ok: r.ok, status: r.status, body: body }; }); })
            .then(function (response) {
              if (response.status === 409) {
                if (out) out.textContent = t('install.backup.dynamic.import_changed', 'Import not applied: panel settings changed after the preview. Preview the file again.');
                return;
              }
              if (!response.ok) throw (response.body.status || response.status);
              var res = response.body;
              if (out) {
                out.textContent = '';
                var controlled = document.createElement('span');
                controlled.textContent = t('install.backup.dynamic.import_summary', 'Import {status} · applied {applied}, skipped {skipped}', { status: closedToken(CONFIG_IMPORT_STATUS, res.status), applied: String((res.applied || []).length), skipped: String((res.skipped || []).length) });
                out.appendChild(controlled);
                var evidenceParts = [];
                if ((res.warnings || []).length) evidenceParts.push('warnings:\n  ' + res.warnings.join('\n  '));
                if ((res.errors || []).length) evidenceParts.push('errors:\n  ' + res.errors.join('\n  '));
                if (evidenceParts.length) { out.appendChild(document.createTextNode('\n')); var evidence = document.createElement('span'); evidence.className = 'install-raw-evidence'; evidence.setAttribute('lang', 'en'); evidence.textContent = evidenceParts.join('\n'); out.appendChild(evidence); }
                out.appendChild(document.createTextNode('\n' + t('install.backup.dynamic.import_reloading', 'Reloading to show the imported settings…')));
              }
              setTimeout(function () { location.reload(); }, 1800);
            });
        });
    }).catch(function (e) {
      if (e && e.approvalRequired) { if (out) out.textContent = e.message; }
      else renderFailure(out, 'install.backup.dynamic.import_failed_prefix', 'Import failed:', String(e == null ? 'request failed' : e));
    });
    input.value = '';
  };

  // Vendor taming and display sizing are server-rendered forms. Submit them in place so a Hardened-mode
  // approval challenge stays on the current page with the same approve-then-retry guidance as the JS cards.
  if (document.addEventListener) document.addEventListener('submit', function (event) {
    var form = event.target;
    if (!form || form.tagName !== 'FORM') return;
    // Compare resolved URLs, not pathnames: behind Panel Assistant's proxy the base is not the origin root.
    var path, tame, density;
    try {
      path = new URL(form.action, document.baseURI);
      path = path.origin + path.pathname;
      tame = new URL('api/v1/tame', document.baseURI).href;
      density = new URL('api/v1/display/density', document.baseURI).href;
    } catch (_) { return; }
    if (path !== tame && path !== density) return;
    event.preventDefault();
    var submitter = event.submitter;
    var body = new URLSearchParams(new FormData(form));
    if (submitter && submitter.name) body.set(submitter.name, submitter.value);
    var note = form.querySelector('.protected-form-result');
    if (!note) {
      note = document.createElement('p'); note.className = 'note protected-form-result'; form.appendChild(note);
    }
    note.textContent = t('install.backup.dynamic.form_applying', 'Applying…');
    if (submitter) submitter.disabled = true;
    fetch(path, {
      method: 'POST',
      headers: { 'Accept': 'application/json', 'Content-Type': 'application/x-www-form-urlencoded' },
      body: body.toString(),
    }).then(function (response) {
      return approvalAwareJson(response).then(function (result) {
        if (!response.ok || !result || result.ok !== true) {
          throw new Error(result && (result.message || result.error) || ('HTTP ' + response.status));
        }
        return result;
      });
    }).then(function (result) {
      var applied = presentation(result.presentation, result.message || t('install.backup.dynamic.form_applied', 'Applied.'));
      note.textContent = applied.text + ' ' + t('install.backup.dynamic.form_returning', 'Returning to this card…');
      var target = installCardHref(path === tame ? '#cfg-tame' : '#cfg-display');
      setTimeout(function () { location.href = target; }, path === tame ? 1800 : 900);
    }).catch(function (error) {
      note.textContent = error && error.message ? error.message : t('install.backup.dynamic.form_failed', 'Could not apply this change.');
      if (submitter && submitter.id === 'tame-package-submit' && typeof updateTamePackageSubmit === 'function') {
        updateTamePackageSubmit();
      } else if (submitter) submitter.disabled = false;
    });
  });

  // Server-rendered Install cards support the same scroll-and-flash deep links as Configure fields.
  if (typeof location !== 'undefined' && location.hash) {
    var target = document.getElementById(location.hash.slice(1));
    if (target) setTimeout(function () {
      target.scrollIntoView({ behavior: 'smooth', block: 'center' });
      target.classList.add('flash');
      setTimeout(function () { target.classList.remove('flash'); }, 1800);
    }, 0);
  }

  var alignInstallColumns = window.CardColumnAlignment
    ? window.CardColumnAlignment.attach('install-cards')
    : function () {};
  scheduleInstallColumnAlignment = function () {
    alignInstallColumns();
    if (installCardMemoryReady && window.CardSizeMemory) window.CardSizeMemory.settle('install-cards', 1200);
  };
  scheduleInstallColumnAlignment();

  // Recover visibility of a staged upload abandoned before this page load — back/refresh loses the
  // preview token but not the panel-side file. (Issue #96)
  apkProbePending();

  // Radio card: show it only when this panel actually has an EFR32 radio gateway.
  var initialRadioLoad = fetch('api/v1/radio').then(function (r) { return r.json(); }).then(function (d) {
    if (!d || !d.present) return true;
    var card = document.getElementById('radiocard'), st = document.getElementById('radio-status'),
        health = document.getElementById('radio-health');
    if (st) setPresented(st, d.presentations && d.presentations.status, d.status || '');
    if (health) health.textContent = closedToken(RADIO_STATE, d.state || 'unknown');
    if (card) { card.style.display = ''; scheduleInstallColumnAlignment(); }
    return true;
  }).catch(function () { return false; });

  // Hydrate every picker component's version list on load.
  var initialVersionLoads = [];
  document.querySelectorAll('.comprow[data-name]').forEach(function (r) {
    initialVersionLoads.push(loadVersions(r.getAttribute('data-name')));
  });
  Promise.all([initialPackagesLoad, initialRadioLoad].concat(initialVersionLoads)).then(function (outcomes) {
    if (!outcomes.every(function (ok) { return ok !== false; })) return;
    installInitialLoadsComplete = true;
    installCardMemoryReady = true;
    scheduleInstallColumnAlignment();
  });
})();
