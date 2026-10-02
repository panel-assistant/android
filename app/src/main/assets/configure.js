// Configure save/load coordination and page startup. Load after the Configure modules.
(function (cfg) {
  "use strict";

  function updateSaveUi() {
    var button = document.getElementById("savebtn");
    var bar = document.getElementById("savebar");
    button.disabled = !cfg.dirty || cfg.saving;
    button.textContent = cfg.saving ? cfg.i18nText("configure.save.saving", "Saving…") : cfg.i18nText("configure.save.action", "Save changes");
    bar.hidden = !cfg.dirty && !cfg.saving;
    document.body.classList.toggle("cfg-dirty", cfg.dirty || cfg.saving);
    syncUnsavedNavigationGuard();
  }
  function recomputeDirty() {
    cfg.dirtyValues = Object.create(null);
    cfg.dirtyExpose = Object.create(null);
    cfg.schema.forEach(function (f) {
      if (cfg.values[f.key] !== cfg.savedValues[f.key]) cfg.dirtyValues[f.key] = true;
      if (f.ha && (cfg.expose[f.key] !== false) !== (cfg.savedExpose[f.key] !== false)) cfg.dirtyExpose[f.key] = true;
    });
    cfg.dirty = Object.keys(cfg.dirtyValues).length > 0 || Object.keys(cfg.dirtyExpose).length > 0;
  }
  function setDirty() {
    cfg.editGeneration++;
    recomputeDirty();
    updateSaveUi();
    cfg.syncHaOAuthAvailability();
    cfg.syncAutoSleepCardSignature();
    cfg.reloadSavedLocaleWhenSettled();
  }
  function clearDirty() {
    cfg.dirty = false;
    cfg.dirtyValues = Object.create(null);
    cfg.dirtyExpose = Object.create(null);
    updateSaveUi();
  }

  // Individual controls own their value normalization, but the shared save affordance must be the
  // final writer after an input event. Capture the event while its target still belongs to the form,
  // then reconcile after target handlers have finished (including handlers that replace that target).
  var dirtyUiReconcileQueued = false;
  function queueDirtyUiReconcile(event) {
    var groups = document.getElementById("cfg-groups");
    if (!groups || !event.target || !groups.contains(event.target) || dirtyUiReconcileQueued) return;
    dirtyUiReconcileQueued = true;
    setTimeout(function () {
      dirtyUiReconcileQueued = false;
      recomputeDirty();
      updateSaveUi();
      cfg.reloadSavedLocaleWhenSettled();
    }, 0);
  }

  function guardUnsavedNavigation(event) {
    event.preventDefault();
    event.returnValue = "";
  }

  var unsavedNavigationGuardArmed = false;
  function syncUnsavedNavigationGuard() {
    if (typeof window.addEventListener !== "function" || typeof window.removeEventListener !== "function") return;
    var shouldArm = cfg.dirty || cfg.saving;
    if (shouldArm === unsavedNavigationGuardArmed) return;
    unsavedNavigationGuardArmed = shouldArm;
    if (shouldArm) window.addEventListener("beforeunload", guardUnsavedNavigation);
    else window.removeEventListener("beforeunload", guardUnsavedNavigation);
  }

  function keepSaveMessageVisible(text) {
    return /Home Assistant sign-in|sign-in workflow/i.test(String(text || ""));
  }

  function restampConfigWatchBaseline() {
    // OUR save changes the cfg fingerprint too. Re-stamp even when newer local edits prevent a form
    // reload, otherwise buildwatch.js falsely reports those acknowledged changes as external.
    setTimeout(function () {
      fetch("health").then(function (r) { return r.text(); }).then(function (t) {
        var m = t.match(/cfg=(\S+)/); if (m) document.body.setAttribute("data-cfg", m[1]);
      }).catch(function () {});
    }, 500);
  }

  function firstInvalidDirtySetting() {
    var minimumKey = "auto_brightness_minimum_percent";
    var maximumKey = "auto_brightness_maximum_percent";
    if ((cfg.dirtyValues[minimumKey] || cfg.dirtyValues[maximumKey]) &&
        Number(cfg.values[minimumKey]) >= Number(cfg.values[maximumKey])) {
      for (var b = 0; b < cfg.schema.length; b++) {
        var bound = cfg.schema[b];
        if (bound.key !== maximumKey) continue;
        return { field: bound, control: document.querySelector("#cfg-" + maximumKey + " input"),
          message: cfg.i18nText("configure.validation.invalid", "{label} has an invalid value.", { label: bound.label }) };
      }
    }
    for (var i = 0; i < cfg.schema.length; i++) {
      var field = cfg.schema[i];
      if (!cfg.dirtyValues[field.key]) continue;
      var row = document.getElementById("cfg-" + field.key);
      // The first INVALID control in the row, not the row's first control. A row may carry more than
      // one — the dashboard picker is a select plus a revealed custom-path input — and the leading
      // control is typically the valid one while the control being edited is not. Taking the first
      // match would report the row as valid and let the bad value reach the server.
      var controls = row && row.querySelectorAll ? row.querySelectorAll("input,select,textarea") : [];
      var control = null;
      for (var c = 0; c < controls.length; c++) {
        if (controls[c].disabled || !controls[c].checkValidity || controls[c].checkValidity()) continue;
        control = controls[c];
        break;
      }
      if (!control) continue;
      // Name the rule the browser actually broke: an in-range value off the step grid is not a range error.
      var message = control.validity && control.validity.stepMismatch && field.step != null
        ? cfg.i18nText("configure.validation.step", "{label} must be a multiple of {step}.", { label: field.label, step: field.step })
        : field.min != null && field.max != null
        ? cfg.i18nText("configure.validation.range", "{label} must be between {min} and {max}.", { label: field.label, min: field.min, max: field.max })
        : cfg.i18nText("configure.validation.invalid", "{label} has an invalid value.", { label: field.label });
      return { field: field, control: control, message: message };
    }
    return null;
  }

  window.cfgSave = function () {
    if (!cfg.dirty || cfg.saving) return;
    var msg = document.getElementById("cfg-msg");
    var invalid = firstInvalidDirtySetting();
    if (invalid) {
      msg.textContent = invalid.message;
      if (invalid.control && invalid.control.reportValidity) invalid.control.reportValidity();
      if (invalid.control && invalid.control.focus) invalid.control.focus();
      return;
    }
    cfg.saving = true;
    // Fence any mDNS suggestions computed against the pre-save broker/HA baseline.
    cfg.configDiscoveryRequest++;
    var submittedGeneration = cfg.editGeneration;
    updateSaveUi();
    var body = new URLSearchParams();
    var submittedValues = {}, submittedExpose = {};
    cfg.schema.forEach(function (f) {
      var v = cfg.values[f.key];
      if (cfg.dirtyValues[f.key]) {
        if (f.secret) { if (v) { body.set(f.key, v); submittedValues[f.key] = v; } } // blank password = keep current
        else if (v != null) { body.set(f.key, v); submittedValues[f.key] = v; }
      }
      if (f.ha && cfg.dirtyExpose[f.key]) {
        submittedExpose[f.key] = cfg.expose[f.key] !== false;
        body.set("ha_expose_" + f.key, submittedExpose[f.key] ? "true" : "false");
      }
    });
    msg.textContent = cfg.i18nText("configure.save.saving", "Saving…");
    fetch("api/v1/config", {
      method: "POST", headers: { "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    }).then(function (r) {
      // The panel refuses as JSON `message` or as plain text; keep whichever arrived so the reason is shown.
      var text = r.ok ? null : r.clone().text().catch(function () { return ""; });
      return cfg.approvalAwareJson(r).then(function (body) {
        if (!r.ok) {
          return text.then(function (raw) {
            var partial = body && body.status === "saved-partial";
            var reason = (body && typeof body.message === "string" && body.message) ||
              (body && typeof body.error === "string" ? body.error + (body.reason ? " (" + body.reason + ")" : "") : raw.trim());
            var error = new Error(partial
              ? cfg.i18nText("configure.save.failed", "Save failed.")
              : reason
              ? cfg.i18nText("configure.save.refused", "Not saved: {reason}", { reason: reason })
              : cfg.i18nText("configure.error.http", "Failed (HTTP {status})", { status: r.status }));
            error.configOutcome = body;
            error.refused = true;
            throw error;
          });
        }
        return body;
      });
    })
      .then(function (outcome) {
        var outcomeMessage = outcome && outcome.status === "saved-apply-pending"
          ? cfg.i18nText("configure.save.hardware_pending", "Saved desired value; hardware application is pending.")
          : cfg.i18nText("configure.save.saved", "Saved.");
        var autoBrightnessSourceChanged = Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness_ha_entity");
        if (autoBrightnessSourceChanged) cfg.beginAutoBrightnessSourceTransition(submittedValues.auto_brightness_ha_entity);
        Object.keys(submittedValues).forEach(function (key) { cfg.savedValues[key] = submittedValues[key]; });
        Object.keys(submittedExpose).forEach(function (key) { cfg.savedExpose[key] = submittedExpose[key]; });
        cfg.acceptSavedUiLanguage(submittedValues);
        recomputeDirty();
        cfg.loadRadio();
        restampConfigWatchBaseline();
        var autoSleepInputsChanged = ["auto_sleep", "auto_sleep_source", "ha_url", "ha_token"].some(function (key) {
          return Object.prototype.hasOwnProperty.call(submittedValues, key);
        });
        var haConnectionInputsChanged = ["ha_url", "ha_token"].some(function (key) {
          return Object.prototype.hasOwnProperty.call(submittedValues, key);
        });
        if (autoSleepInputsChanged) {
          cfg.invalidateAutoSleepData(haConnectionInputsChanged);
        }
        if (cfg.editGeneration !== submittedGeneration) {
          (outcome && outcome.pending || []).forEach(function (key) {
            if (Object.prototype.hasOwnProperty.call(submittedValues, key)) cfg.applyPending[key] = submittedValues[key];
          });
          scheduleApplyPendingPoll();
          cfg.saving = false;
          updateSaveUi();
          if (cfg.localeDocumentReloadPending && !cfg.dirty) {
            cfg.reloadForSavedLocale();
            return;
          }
          msg.textContent = cfg.dirty ?
            ((outcome && outcome.pending && outcome.pending.length) ?
              cfg.i18nText("configure.save.outcome_newer_changes", "{outcome} Newer changes still need saving.", { outcome: outcomeMessage })
              : cfg.i18nText("configure.save.saved_newer_changes", "Saved; newer changes still need saving.")) :
            outcomeMessage;
          if (autoSleepInputsChanged && cfg.values.auto_sleep === "true") setTimeout(cfg.loadAutoSleepData, 0);
          if (autoBrightnessSourceChanged) setTimeout(function () { cfg.loadAutoBrightnessData(true); }, 0);
          return;
        }
        cfg.saving = false;
        msg.textContent = cfg.i18nText("configure.save.saving", "Saving…"); clearDirty();
        // The optional Presence & wake card is server-rendered only while Wake on wave is enabled.
        if (cfg.localeDocumentReloadPending || Object.prototype.hasOwnProperty.call(submittedValues, "wake_on_wave")) {
          cfg.reloadForSavedLocale();
          return;
        }
        // Reload the form from the server, then land on a terminal message — don't leave a
        // "reconnecting…" string hanging (it reads as stuck even though the save is done).
        // Clear before load(): its render pass is the single owner that schedules enabled status.
        load(function (ok) {
          msg.textContent = ok ? outcomeMessage : cfg.i18nText("configure.save.reload_failed", "Saved (reload failed — refresh the page).");
          var autoBrightnessSettingChanged = Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness") ||
              Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness_minimum_percent") ||
              Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness_maximum_percent") ||
              Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness_response_percent") ||
              autoBrightnessSourceChanged;
          if (autoBrightnessSourceChanged || (ok && autoBrightnessSettingChanged)) {
            cfg.loadAutoBrightnessData(true);
          }
          // Re-enabling keeps the last completed replay visible, but it must still validate status
          // and history in the background. invalidateAutoSleepData() deliberately retains that
          // settled snapshot, so render() cannot infer the refresh from a missing status object.
          if (ok && autoSleepInputsChanged && cfg.values.auto_sleep === "true") {
            setTimeout(cfg.loadAutoSleepData, 0);
          }
          if (ok && !(outcome && outcome.pending && outcome.pending.length)) {
            if (!keepSaveMessageVisible(outcomeMessage)) {
              setTimeout(function () { if (msg.textContent === outcomeMessage) msg.textContent = ""; }, 2500);
            }
          }
        }, haConnectionInputsChanged);
      })
      .catch(function (e) {
        cfg.saving = false;
        if (e && e.configOutcome && e.configOutcome.status === "saved-partial") {
          var applied = Array.isArray(e.configOutcome.applied) ? e.configOutcome.applied : [];
          var appliedValues = {};
          applied.forEach(function (key) {
            if (!Object.prototype.hasOwnProperty.call(submittedValues, key)) return;
            appliedValues[key] = submittedValues[key];
            cfg.savedValues[key] = submittedValues[key];
          });
          cfg.acceptSavedUiLanguage(appliedValues);
          if (cfg.localeDocumentReloadPending) {
            recomputeDirty();
            updateSaveUi();
            if (!cfg.dirty) {
              cfg.reloadForSavedLocale(e.message);
              return;
            }
          }
          load(function () {
            if (cfg.localeDocumentReloadPending) {
              cfg.reloadForSavedLocale(e.message);
              return;
            }
            msg.textContent = e.message;
            updateSaveUi();
          }, false);
        } else {
          msg.textContent = e && e.approvalRequired
            ? cfg.approvalMessage(e.body)
            : e && e.refused
            ? e.message
            : cfg.i18nText("configure.save.failed", "Save failed.");
          updateSaveUi();
        }
      });
  };

  function load(done, forceHaUserStatusRefresh, refreshAutoSleepPrerequisite) {
    ++cfg.haAreaSeedGeneration;
    ++cfg.schemaLanguageRequest;
    var schemaUrl = cfg.configSchemaUrl(cfg.haUserStatus.phase === "connected" ? cfg.haUserStatus.language : "");
    Promise.all([
      fetch(schemaUrl, { headers: { "Accept": "application/json" }, cache: "no-store" }).then(cfg.readLocalizedSchema),
      fetch("api/v1/config", { headers: { "Accept": "application/json" }, cache: "no-store" }).then(function (r) { return r.json(); }),
      // Installed launchable apps for the package pickers; tolerate failure (picker falls back to text).
      fetch("api/v1/apps").then(function (r) { return r.json(); }).catch(function () { return { apps: [] }; }),
    ]).then(function (res) {
      var previousHaUrl = cfg.values.ha_url;
      var previousHaConfigured = cfg.haAuth.configured === true;
      var previousHaOauth = cfg.haAuth.oauth === true;
      cfg.schema = res[0];
      cfg.values = res[1].settings || {};
      cfg.expose = res[1].ha_expose || {};
      cfg.haAuth = res[1].ha_auth || {};
      cfg.applyPending = res[1].apply_pending || {};
      cfg.applyStalled = stalledMap(res[1].apply_stalled);
      cfg.haAreaSeed = res[1].ha_area_catalog || null;
      cfg.haAreaUserOverride = res[1].ha_area_user_override === true;
      if (cfg.values.auto_sleep === "true") cfg.configCardExpected.autoSleep = true;
      if (cfg.haAuth.configured === true) cfg.configCardExpected.ha = true;
      var haConnectionChanged = forceHaUserStatusRefresh === true ||
        previousHaUrl !== cfg.values.ha_url ||
        previousHaConfigured !== (cfg.haAuth.configured === true) ||
        previousHaOauth !== (cfg.haAuth.oauth === true);
      // Keep an already-rendered identity stable across unrelated saves. A real HA connection edit
      // clears the old user's name and performs a fresh no-store current-user probe below.
      if (haConnectionChanged) {
        cfg.haUserStatus = { phase: "unknown" };
        cfg.autoSleepAreaGeneration++;
        cfg.autoSleepSourceUpdating = Object.create(null);
        cfg.invalidateAutoSleepData(true);
        cfg.autoSleepAssignedAreaName = "";
        cfg.autoSleepPrerequisiteRequest++;
        cfg.autoSleepPrerequisite = { eligible: false, phase: "checking", area_name: "" };
      }
      cfg.apps = (res[2] && res[2].apps) || [];
      cfg.rendererChoices = (res[2] && Array.isArray(res[2].renderers)) ? res[2].renderers : [];
      // Normalize bool values to the "true"/"false" strings the toggle compares against.
      cfg.schema.forEach(function (f) {
        if (f.type === "BOOL" && typeof cfg.values[f.key] === "boolean") cfg.values[f.key] = cfg.values[f.key] ? "true" : "false";
        if (cfg.values[f.key] != null && typeof cfg.values[f.key] !== "string") cfg.values[f.key] = String(cfg.values[f.key]);
        if (Object.prototype.hasOwnProperty.call(cfg.applyPending, f.key)) {
          var desired = cfg.applyPending[f.key];
          if (f.type === "BOOL" && typeof desired === "boolean") desired = desired ? "true" : "false";
          cfg.values[f.key] = typeof desired === "string" ? desired : String(desired);
        }
      });
      cfg.savedValues = Object.assign({}, cfg.values);
      cfg.savedExpose = Object.assign({}, cfg.expose);
      // A successful full reload replaces the form's local snapshot, so no previously tracked edit remains.
      clearDirty();
      cfg.render();
      loadDiscoverySuggestions();
      cfg.loadHomeDashboards();
      if (!done && Object.keys(cfg.applyPending).length) {
        document.getElementById("cfg-msg").textContent = applyPendingBannerText();
      }
      scheduleApplyPendingPoll();
      if (refreshAutoSleepPrerequisite !== false) cfg.scheduleAutoSleepPrerequisite();
      if (haConnectionChanged) cfg.loadHaUserStatus();
      cfg.configCardSourceReady("core");
      cfg.configCardGeometryChanged();
      cfg.consumeLocaleReloadMessage();
      if (done) done(true);
    }).catch(function (e) {
      document.getElementById("cfg-status").textContent = cfg.i18nText("configure.load_failed", "Could not load settings ({error}).", { error: e });
      cfg.consumeLocaleReloadMessage();
      if (done) done(false);
    });
  }

  function loadDiscoverySuggestions() {
    var request = ++cfg.configDiscoveryRequest;
    fetch("api/v1/config/discovery", { cache: "no-store" }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(function (suggestions) {
      // Never rebuild the form over an edit made while the bounded mDNS browse was in flight.
      if (request !== cfg.configDiscoveryRequest || cfg.dirty) return;
      var applied = false;
      ["mqtt_broker", "ha_url"].forEach(function (key) {
        var suggestion = suggestions && suggestions[key];
        if (!suggestion || cfg.values[key] || cfg.savedValues[key] || cfg.dirtyValues[key]) return;
        cfg.values[key] = String(suggestion);
        applied = true;
      });
      if (!applied) return;
      cfg.editGeneration++;
      recomputeDirty();
      cfg.render();
      cfg.configCardGeometryChanged();
    }).catch(function () {});
  }

  // The keys whose apply path this panel has been found not to have. Their desired value is still saved
  // and still retried; only the sentence about it changes, because "waiting to apply" was a promise the
  // panel could not keep and it never went away.
  function stalledMap(list) {
    var map = {};
    (Array.isArray(list) ? list : []).forEach(function (key) { map[key] = true; });
    return map;
  }

  function applyPendingStatusText(key) {
    return cfg.applyStalled[key] === true
      ? cfg.i18nText("configure.save.hardware_unavailable", "Saved. This panel has no way to apply it yet; it will be applied if that changes.")
      : cfg.i18nText("configure.save.hardware_pending", "Saved desired value; hardware application is pending.");
  }

  function applyPendingBannerText() {
    var waiting = [], stalled = [];
    Object.keys(cfg.applyPending).forEach(function (key) {
      (cfg.applyStalled[key] === true ? stalled : waiting).push(key);
    });
    var parts = [];
    if (waiting.length) {
      parts.push(cfg.i18nText("configure.save.waiting_to_apply", "Saved settings waiting to apply: {settings}.", {
        settings: waiting.join(", ")
      }));
    }
    if (stalled.length) {
      parts.push(cfg.i18nText("configure.save.unavailable_to_apply", "Saved, but this panel has no way to apply them yet: {settings}.", {
        settings: stalled.join(", ")
      }));
    }
    return parts.join(" ");
  }

  function scheduleApplyPendingPoll() {
    if (cfg.applyPendingTimer) clearTimeout(cfg.applyPendingTimer);
    cfg.applyPendingTimer = null;
    if (!Object.keys(cfg.applyPending).length) return;
    cfg.applyPendingTimer = setTimeout(function () {
      fetch("api/v1/config", { headers: { "Accept": "application/json" }, cache: "no-store" })
        .then(function (response) { if (!response.ok) throw response.status; return response.json(); })
        .then(function (body) {
          var next = body.apply_pending || {};
          var nextStalled = stalledMap(body.apply_stalled);
          var currentKeys = Object.keys(cfg.applyPending), nextKeys = Object.keys(next);
          var changed = currentKeys.length !== nextKeys.length || nextKeys.some(function (key) {
            return !Object.prototype.hasOwnProperty.call(cfg.applyPending, key) ||
              String(cfg.applyPending[key]) !== String(next[key]) ||
              (nextStalled[key] === true) !== (cfg.applyStalled[key] === true);
          });
          if (!changed) return;
          var changedKeys = currentKeys.concat(nextKeys).filter(function (key, index, keys) {
            return keys.indexOf(key) === index;
          });
          if (!cfg.dirty) {
            load(function (ok) {
              if (ok && !nextKeys.length) {
                document.getElementById("cfg-msg").textContent = cfg.i18nText("configure.save.now_applied", "Saved settings are now applied.");
              }
            }, false, false);
            return;
          }
          cfg.applyPending = next;
          cfg.applyStalled = nextStalled;
          changedKeys.forEach(function (key) {
            var row = document.getElementById("cfg-" + key);
            var label = row && row.querySelector(".flabel");
            if (!label) return;
            var status = label.querySelector(".apply-pending-status");
            if (Object.prototype.hasOwnProperty.call(next, key)) {
              var statusText = applyPendingStatusText(key);
              if (!status) label.appendChild(cfg.el("small", { class: "apply-pending-status", text: statusText }));
              else if (status.textContent !== statusText) status.textContent = statusText;
            } else if (status) status.remove();
          });
          cfg.syncAutoSleepCardSignature();
          if (!nextKeys.length) {
            var msg = document.getElementById("cfg-msg");
            msg.textContent = cfg.i18nText("configure.save.applied_newer_changes", "Saved settings are now applied; newer changes still need saving.");
          }
        })
        .catch(function () {})
        .then(scheduleApplyPendingPoll);
    }, 3000);
  }

  cfg.updateSaveUi = updateSaveUi;
  cfg.recomputeDirty = recomputeDirty;
  cfg.setDirty = setDirty;
  cfg.applyPendingStatusText = applyPendingStatusText;

  if ("BroadcastChannel" in window) {
    var haOauthChannel = new BroadcastChannel("ha-paneld-ha-oauth");
    // Node-backed asset tests expose BroadcastChannel too; do not let its listener hold that process open.
    if (haOauthChannel.unref) haOauthChannel.unref();
    haOauthChannel.addEventListener("message", function (event) {
      if (event.data && (event.data.status === "success" || event.data.status === "failure")) {
        cfg.handleHaOAuthResult(event.data.status);
      }
    });
  }

  if (window.addEventListener) {
    document.addEventListener("input", queueDirtyUiReconcile, true);
    document.addEventListener("change", queueDirtyUiReconcile, true);
    document.addEventListener("click", queueDirtyUiReconcile, true);
    window.addEventListener("focus", cfg.scheduleAutoSleepPrerequisite);
    document.addEventListener("visibilitychange", function () {
      if (document.visibilityState === "visible") cfg.scheduleAutoSleepPrerequisite();
    });
  }

  cfg.readConfigView();
  cfg.syncConfigViewUi();
  (function wireConfigTools() {
    ["tier-basic", "tier-adv"].forEach(function (id) {
      var radio = document.getElementById(id);
      if (radio && radio.type === "radio") radio.addEventListener("change", function () {
        if (radio.checked) window.cfgTab(id === "tier-adv");
      });
    });
    var desc = document.getElementById("cfg-desc");
    if (desc) desc.addEventListener("change", function () {
      cfg.descriptions = desc.checked;
      cfg.configViewChanged();
    });
    var filter = document.getElementById("cfg-filter");
    if (!filter) return;
    var filterTimer = null;
    function applyFilter() {
      filterTimer = null;
      var next = filter.value.trim();
      if (next === cfg.filterText) return;
      var wasFiltering = !!cfg.filterText;
      cfg.filterText = next;
      cfg.closeHelp();
      cfg.render();
      if (wasFiltering && !cfg.filterText) cfg.configCardGeometryChanged();
    }
    filter.addEventListener("input", function () {
      if (filterTimer) clearTimeout(filterTimer);
      filterTimer = setTimeout(applyFilter, 120);
    });
    filter.addEventListener("keydown", function (event) {
      if (event.key !== "Escape" || !filter.value) return;
      filter.value = "";
      applyFilter();
    });
  })();

  load(null, true);
  cfg.loadRadio();
})(window.ConfigurePage);
