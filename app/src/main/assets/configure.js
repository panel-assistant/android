// Configure live settings and page startup. Load after the Configure modules.
//
// There is no Save button. Each finished gesture sends one `settings` op for its key, and the page follows
// the panel's `config` family the way Home Assistant's frontend follows entity state (PendingValueSync in
// home-assistant/frontend src/common/util/pending-value-sync.ts): while a key's send is scheduled or in
// flight the key ignores pushed values; once it settles the row takes the panel's value, and a refused
// send puts the panel's value back and says why on the row. Toggles, choices and pickers send at once; a
// typed field sends when it is committed (Enter or leaving it); repeated stepper presses on a number send
// once, a second after the last.
(function (cfg) {
  "use strict";

  var STEP_DEBOUNCE_MS = 1000;
  // Hold per key (an exposure pip holds "ha_expose_<key>"): {seq, call, timer, before, pushed, local}.
  var holds = Object.create(null);
  // Keys whose focused text field holds typed text that has not been sent.
  var typing = Object.create(null);
  // Whether the latest input event on a number field was a step (arrow key or spinner), not typing.
  var stepping = false;
  var configWatch = null;

  function fieldOf(key) {
    for (var i = 0; i < cfg.schema.length; i++) if (cfg.schema[i].key === key) return cfg.schema[i];
    return null;
  }
  function keyOf(node) {
    var row = node && node.closest ? node.closest(".frow") : null;
    return row && row.id.indexOf("cfg-") === 0 ? row.id.slice(4) : null;
  }
  function textEntry(node) {
    return !!node && (node.tagName === "TEXTAREA" ||
      (node.tagName === "INPUT" && !/^(checkbox|radio|button|submit)$/.test(node.type)));
  }
  function exposeId(key) { return "ha_expose_" + key; }

  // The panel's values as the form shows them: typed JSON as strings, a desired value waiting for hardware
  // in place of the observed one.
  function panelValues(body) {
    var values = {};
    [body.settings || {}, body.apply_pending || {}].forEach(function (source) {
      Object.keys(source).forEach(function (key) {
        var raw = source[key];
        values[key] = typeof raw === "boolean" ? (raw ? "true" : "false") : raw == null ? raw : String(raw);
      });
    });
    return values;
  }

  function stalledMap(list) {
    var map = {};
    (Array.isArray(list) ? list : []).forEach(function (key) { map[key] = true; });
    return map;
  }

  // Everything but the values: Home Assistant sign-in, saved values waiting for hardware, and settings the
  // dashboard on screen does not show yet; a field the body does not carry is left as it is. Returns whether
  // any of it changed.
  function adoptStatus(body) {
    var fields = {
      haAuth: ["ha_auth", function (v) { return v || {}; }],
      haAreaUserOverride: ["ha_area_user_override", function (v) { return v === true; }],
      applyPending: ["apply_pending", function (v) { return v || {}; }],
      applyStalled: ["apply_stalled", stalledMap],
      rendererPending: ["renderer_pending", stalledMap],
    };
    var changed = false;
    Object.keys(fields).forEach(function (name) {
      if (!Object.prototype.hasOwnProperty.call(body, fields[name][0])) return;
      var next = fields[name][1](body[fields[name][0]]);
      if (JSON.stringify(cfg[name]) === JSON.stringify(next)) return;
      cfg[name] = next;
      changed = true;
    });
    return changed;
  }

  // A change to the Home Assistant connection drops what was learned through the old one.
  function haConnectionChanged(previousUrl, previousAuth, force) {
    if (force !== true && previousUrl === cfg.values.ha_url &&
        (previousAuth.configured === true) === (cfg.haAuth.configured === true) &&
        (previousAuth.oauth === true) === (cfg.haAuth.oauth === true)) return false;
    cfg.homeDashboardRequest++;
    cfg.homeDashboardItems = [];
    cfg.homeDashboardDefault = { explicit: false, path: "" };
    cfg.homeDashboardQueried = false;
    cfg.haUserStatus = { phase: "unknown" };
    cfg.autoSleepAreaGeneration++;
    cfg.autoSleepSourceUpdating = Object.create(null);
    cfg.invalidateAutoSleepData(true);
    cfg.autoSleepAssignedAreaName = "";
    cfg.autoSleepPrerequisiteRequest++;
    cfg.autoSleepPrerequisite = { eligible: false, phase: "checking", area_name: "" };
    return true;
  }

  // What else on the page follows a changed setting, whichever side changed it.
  function refreshDependents(keys, haChanged) {
    function any(list) { return list.some(function (key) { return keys.indexOf(key) >= 0; }); }
    if (keys.indexOf("auto_brightness_ha_entity") >= 0) cfg.beginAutoBrightnessSourceTransition(cfg.values.auto_brightness_ha_entity);
    if (any(["auto_brightness", "auto_brightness_minimum_percent", "auto_brightness_maximum_percent", "auto_brightness_response_percent", "auto_brightness_ha_entity"])) {
      cfg.loadAutoBrightnessData(true);
    }
    if (any(["auto_sleep", "auto_sleep_source"]) && !haChanged) cfg.invalidateAutoSleepData(false);
    if ((haChanged || any(["auto_sleep", "auto_sleep_source"])) && cfg.values.auto_sleep === "true") setTimeout(cfg.loadAutoSleepData, 0);
    if (haChanged) {
      cfg.scheduleAutoSleepPrerequisite();
      cfg.loadHaUserStatus();
      cfg.loadHomeDashboards();
      cfg.voicePipelinesCatalog = null;
      cfg.loadVoicePipelines();
    }
    if (keys.indexOf("zigbee_router") >= 0) cfg.loadRadio();
  }

  // cfg.render waits while a field holds unsent typed text (configure-render.js); this is its test.
  cfg.typingHeld = function () { return Object.keys(typing).length > 0; };
  function renderWhenIdle() {
    cfg.render();
    if (!cfg.renderOwed) cfg.configCardGeometryChanged();
  }

  // Take the panel's state, from the first read or a `config` family update, for every key not held.
  function adopt(body) {
    var previousUrl = cfg.values.ha_url, previousAuth = cfg.haAuth;
    var values = panelValues(body), expose = body.ha_expose || {}, changedKeys = [];
    Object.keys(values).forEach(function (key) {
      cfg.panel[key] = values[key];
      var hold = holds[key];
      // A proposed value nobody sent gives way when the panel's own value changes (browser sign-in wrote it).
      if (hold && hold.local && !hold.call && values[key] !== hold.before) delete holds[key];
      else if (hold) { hold.pushed = true; return; }
      if (typing[key] || cfg.values[key] === values[key]) return;
      cfg.values[key] = values[key];
      delete cfg.rowMessages[key];
      changedKeys.push(key);
    });
    Object.keys(expose).forEach(function (key) {
      cfg.panelExpose[key] = expose[key];
      var hold = holds[exposeId(key)];
      if (hold) { hold.pushed = true; return; }
      if (cfg.expose[key] === expose[key]) return;
      cfg.expose[key] = expose[key];
      changedKeys.push(exposeId(key));
    });
    var statusChanged = adoptStatus(body);
    if (!changedKeys.length && !statusChanged) return;
    var haChanged = haConnectionChanged(previousUrl, previousAuth);
    renderWhenIdle();
    refreshDependents(changedKeys, haChanged);
  }

  function newHold(id, before) {
    return holds[id] || (holds[id] = { seq: 0, call: 0, timer: null, before: before, pushed: false, local: false, flight: false, queued: null, sent: undefined });
  }

  // The row keeps the message until it is changed again; the page's live region announces it once.
  function showRowMessage(key, message) {
    if (message) cfg.rowMessages[key] = message;
    else delete cfg.rowMessages[key];
    var old = document.querySelector("#cfg-" + key + " .cfg-row-message");
    if (old) old.remove();
    if (!message) return;
    // Cleared first, so a refusal identical to the last one is announced again.
    var live = document.getElementById("cfg-msg");
    live.textContent = "";
    setTimeout(function () { live.textContent = message; }, 50);
  }

  function refusal(reply, body) {
    var reason = (typeof reply.result === "string" && reply.result.trim()) || (typeof body.message === "string" && body.message.trim()) ||
      (typeof body.error === "string" ? body.error + (body.reason ? " (" + body.reason + ")" : "") : "");
    return reason
      ? cfg.i18nText("configure.save.refused", "Not saved: {reason}", { reason: reason })
      : cfg.i18nText("configure.error.http", "Failed (HTTP {status})", { status: reply.code || reply.outcome });
  }

  // Sends one finished gesture. [queued] is a gesture finished while an earlier one was in flight: it carries
  // the value as it was finished, and leaves alone any step or typing begun since.
  function send(key, expose, queued) {
    var id = expose ? exposeId(key) : key;
    var hold = newHold(id, expose ? cfg.panelExpose[key] : cfg.panel[key]);
    var value = queued ? queued.value : expose ? cfg.expose[key] !== false : cfg.values[key] == null ? "" : cfg.values[key];
    if (!queued) {
      if (hold.timer) { clearTimeout(hold.timer); hold.timer = null; }
      hold.local = false;
    }
    // One request per key at a time, so two quick gestures cannot reach the panel out of order: the latest
    // gesture finished meanwhile is kept, exactly as it was finished, and goes when that one answers.
    if (hold.flight) { hold.queued = { value: value }; return; }
    hold.flight = true;
    hold.sent = value;
    var call = hold.call = ++hold.seq;
    var args = {};
    args[id] = expose ? (value ? "true" : "false") : value;
    // A proposed address nobody has sent belongs with the credential being sent, as one change.
    var address = holds.ha_url;
    if (key === "ha_token" && address && address.local && !address.call) {
      args.ha_url = cfg.values.ha_url;
      delete holds.ha_url;
    }
    window.PaneldTransport.request("settings", args).then(function (reply) {
      settle(id, key, expose, call, reply);
    }, function () {
      settle(id, key, expose, call, null);
    });
  }

  function settle(id, key, expose, call, reply) {
    var hold = holds[id];
    if (!hold || hold.call !== call) return;
    hold.flight = false;
    // A gesture finished while this one was in flight goes now; its answer settles the row.
    if (hold.queued) { var next = hold.queued; hold.queued = null; send(key, expose, next); return; }
    // A newer change still being stepped or typed keeps the hold.
    if (hold.timer || hold.local) return;
    delete holds[id];
    var body = reply && reply.result && typeof reply.result === "object" ? reply.result : {};
    var partial = body.status === "saved-partial" && Array.isArray(body.applied) && body.applied.indexOf(id) >= 0;
    if (!reply || (reply.outcome !== "applied" && !partial)) {
      showRowMessage(key, !reply ? cfg.i18nText("configure.error.network", "Failed (network)")
        : reply.outcome === "pending_approval" ? cfg.approvalMessage(body) : refusal(reply, body));
      restore(key, expose);
      return;
    }
    // As Home Assistant does, the row keeps the value it sent until the next push; a push that arrived during
    // the hold and moved off the old value already carries the panel's newer (or normalised) value.
    var latest = expose ? cfg.panelExpose[key] : cfg.panel[key];
    var value = hold.pushed && latest !== hold.before ? latest : hold.sent;
    var field = fieldOf(key);
    if (!expose && field && field.secret) value = "";   // a secret is never shown back; blank keeps it
    if (expose) { cfg.expose[key] = value; cfg.panelExpose[key] = value; }
    else {
      cfg.panel[key] = value;
      if (!typing[key]) cfg.values[key] = value;   // newer typed text stays until it is committed
    }
    showRowMessage(key, partial ? refusal(reply, body) : "");
    if (!expose && key === "ui_language") { cfg.reloadForSavedUiLanguage(); return; }
    var previousUrl = cfg.values.ha_url, previousAuth = cfg.haAuth;
    adoptStatus(body);
    var haChanged = haConnectionChanged(previousUrl, previousAuth, key === "ha_url" || key === "ha_token");
    renderWhenIdle();
    refreshDependents([id], haChanged);
  }

  // A failed or refused change shows the panel's value again.
  function restore(key, expose) {
    if (expose) cfg.expose[key] = cfg.panelExpose[key];
    else if (!typing[key]) cfg.values[key] = cfg.panel[key];
    renderWhenIdle();
  }

  // A fraction typed into a whole-number field is rounded (96.5 → 97) rather than refused; the field's
  // commit then sends the rounded value.
  cfg.roundWholeNumber = function (field, control) {
    var n = Number(control.value);
    if (control.value === "" || !isFinite(n) || Number.isInteger(n)) return;
    control.value = String(Math.round(n));
    cfg.values[field.key] = control.value;
  };

  // The first control in the row the browser finds invalid, as the message that names its rule.
  function invalidMessage(key) {
    var field = fieldOf(key), row = document.getElementById("cfg-" + key);
    if (!field || !row) return "";
    var controls = row.querySelectorAll("input,select,textarea");
    for (var i = 0; i < controls.length; i++) {
      var control = controls[i], validity = control.validity;
      if (control.disabled || !control.checkValidity || control.checkValidity()) continue;
      // A step is the arrow increment, not a rule: the panel accepts any in-range value.
      if (validity && validity.stepMismatch && !validity.rangeUnderflow && !validity.rangeOverflow && !validity.badInput) continue;
      return field.type === "INT" && field.min != null && field.max != null
        ? cfg.i18nText("configure.validation.whole_range", "{label} must be a whole number between {min} and {max}.", { label: field.label, min: field.min, max: field.max })
        : field.min != null && field.max != null
        ? cfg.i18nText("configure.validation.range", "{label} must be between {min} and {max}.", { label: field.label, min: field.min, max: field.max })
        : cfg.i18nText("configure.validation.invalid", "{label} has an invalid value.", { label: field.label });
    }
    return "";
  }

  // Send a typed or stepped value now: the field was committed, left, or its step debounce ran out.
  function flush(key) {
    var hold = holds[key];
    if (!typing[key] && !(hold && (hold.timer || hold.local))) return;
    if (hold && hold.timer) { clearTimeout(hold.timer); hold.timer = null; }
    delete typing[key];
    var invalid = invalidMessage(key);
    var field = fieldOf(key);
    if (invalid) {
      // Nothing goes for this gesture, so the hold ends unless a send is still in flight (its answer ends it);
      // a hold left behind would ignore every later change made elsewhere.
      if (hold && !hold.flight) delete holds[key];
      showRowMessage(key, invalid);
      restore(key, false);
    } else if (!hold && (cfg.values[key] === cfg.panel[key] || (field && field.secret && !cfg.values[key]))) {
      if (cfg.renderOwed) renderWhenIdle();   // nothing to send: blank keeps a secret, an unchanged value stays
    } else {
      send(key, false);
    }
  }

  // Every control reports a local change of its setting here, once per event it handles.
  cfg.localChange = function (key, expose) {
    showRowMessage(key, "");
    cfg.syncHaOAuthAvailability();
    cfg.syncAutoSleepCardSignature();
    if (expose) { send(key, true); return; }
    var active = document.activeElement;
    if (textEntry(active) && keyOf(active) === key) {
      typing[key] = true;
      var hold = holds[key];
      if (stepping && active.type === "number") {
        hold = newHold(key, cfg.panel[key]);
        if (hold.timer) clearTimeout(hold.timer);
        hold.timer = setTimeout(function () { hold.timer = null; flush(key); }, STEP_DEBOUNCE_MS);
      } else if (hold && hold.timer) {
        clearTimeout(hold.timer);   // typing took over from stepping: wait for the commit
        hold.timer = null;
      }
      return;
    }
    send(key, false);
  };

  // A value the page proposes but has not sent (the discovered Home Assistant address): held like a local
  // edit, and sent when its field is committed or left.
  cfg.proposeValue = function (key, value) {
    if (holds[key] || typing[key]) return false;
    cfg.values[key] = value;
    newHold(key, cfg.panel[key]).local = true;
    return true;
  };
  cfg.renderWhenIdle = renderWhenIdle;

  function load() {
    ++cfg.haAreaSeedGeneration;
    ++cfg.schemaLanguageRequest;
    var read = function (op, args) {
      return window.PaneldTransport.request(op, args).then(function (reply) {
        if (reply.outcome !== "applied") throw new Error(op + " · " + (reply.code || reply.outcome));
        return reply.result;
      });
    };
    Promise.all([
      read("config.schema", cfg.configSchemaArgs(cfg.haUserStatus.phase === "connected" ? cfg.haUserStatus.language : "")),
      read("config"),
      // Installed launchable apps for the package pickers; tolerate failure (picker falls back to text).
      window.PaneldTransport.request("apps").then(function (reply) { return reply.result; }).catch(function () { return { apps: [] }; }),
    ]).then(function (res) {
      cfg.schema = res[0];
      cfg.values = panelValues(res[1]);
      cfg.panel = Object.assign({}, cfg.values);
      cfg.expose = Object.assign({}, res[1].ha_expose || {});
      cfg.panelExpose = Object.assign({}, cfg.expose);
      adoptStatus(res[1]);
      cfg.haAreaSeed = res[1].ha_area_catalog || null;
      cfg.haAreaUserOverride = res[1].ha_area_user_override === true;
      if (cfg.values.auto_sleep === "true") cfg.configCardExpected.autoSleep = true;
      if (cfg.haAuth.configured === true) cfg.configCardExpected.ha = true;
      haConnectionChanged(undefined, {}, true);
      cfg.apps = (res[2] && res[2].apps) || [];
      cfg.rendererChoices = (res[2] && Array.isArray(res[2].renderers)) ? res[2].renderers : [];
      cfg.render();
      loadDiscoverySuggestions();
      cfg.loadHomeDashboards();
      cfg.scheduleAutoSleepPrerequisite();
      cfg.loadHaUserStatus();
      cfg.configCardSourceReady("core");
      cfg.configCardGeometryChanged();
      // The first update carries the current state; later ones follow changes made anywhere.
      if (!configWatch) configWatch = window.PaneldTransport.subscribe("config", function (body, state) {
        if (state === "known" && body && typeof body === "object") adopt(body);
      });
    }).catch(function (e) {
      document.getElementById("cfg-status").textContent = cfg.i18nText("configure.load_failed", "Could not load settings ({error}).", { error: e });
      if (!Array.isArray(cfg.schema) || !cfg.schema.some(function (field) { return document.getElementById("cfg-" + field.key); })) window.HaI18n.pageFailure("config.schema / config · " + e);
    });
  }

  function loadDiscoverySuggestions() {
    var request = ++cfg.configDiscoveryRequest;
    window.PaneldTransport.request("config.discovery").then(function (reply) {
      if (reply.outcome !== "applied") throw new Error(reply.code || reply.outcome);
      return reply.result;
    }).then(function (suggestions) {
      if (request !== cfg.configDiscoveryRequest) return;
      var suggestion = suggestions && suggestions.ha_url;
      if (!suggestion || cfg.values.ha_url || cfg.panel.ha_url || !cfg.proposeValue("ha_url", String(suggestion))) return;
      renderWhenIdle();
    }).catch(function () {});
  }

  function applyPendingStatusText(key) {
    return cfg.applyStalled[key] === true
      ? cfg.i18nText("configure.save.hardware_unavailable", "Saved. This panel has no way to apply it yet; it will be applied if that changes.")
      : cfg.i18nText("configure.save.hardware_pending", "Saved desired value; hardware application is pending.");
  }

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
    // Before a control's own input handler reports the change: was this a step rather than typing?
    document.addEventListener("input", function (event) {
      var target = event.target;
      stepping = !!target && target.type === "number" && !event.inputType;
    }, true);
    // After the control's own handlers (bubble phase): a committed field sends, unless it is a number
    // still being stepped, whose debounce sends it.
    document.addEventListener("change", function (event) {
      var target = event.target, key = keyOf(target);
      if (!key || !textEntry(target)) return;
      if (stepping && target.type === "number" && target === document.activeElement && holds[key] && holds[key].timer) return;
      flush(key);
    });
    document.addEventListener("focusout", function (event) {
      var key = keyOf(event.target);
      if (key) flush(key);
      if (cfg.renderOwed && !cfg.typingHeld()) setTimeout(renderWhenIdle, 0);
    });
    // A step debounce still running when the page is hidden is sent now, not lost.
    document.addEventListener("visibilitychange", function () {
      if (document.visibilityState === "visible") { cfg.scheduleAutoSleepPrerequisite(); return; }
      Object.keys(holds).forEach(function (key) { if (holds[key].timer) flush(key); });
    });
    window.addEventListener("focus", cfg.scheduleAutoSleepPrerequisite);
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
      var next = filter.value.trim().slice(0, 512);
      if (next === cfg.filterText) return;
      var wasFiltering = !!cfg.filterText;
      cfg.filterText = next;
      cfg.writeConfigView();
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

  load();
  cfg.loadRadio();
})(window.ConfigurePage);
