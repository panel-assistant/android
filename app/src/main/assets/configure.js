// Configure tab — schema-driven form. Fetches /api/v1/config/schema (metadata) + /api/v1/config
// (current values + per-key HA-exposure flags), renders Basic/Advanced grouped fields with an inline
// "expose to HA" pip on each HA-capable row, and saves via partial-merge POST. Vanilla, no build.
(function () {
  "use strict";
  // Advanced is the DEFAULT view until the reduced Basic set is settled.
  var schema = [], values = {}, expose = {}, haAuth = {}, applyPending = {}, applyStalled = {}, applyPendingTimer = null, advanced = true, dirty = false, saving = false, editGeneration = 0, configDiscoveryRequest = 0, schemaLanguageRequest = 0, apps = [], rendererChoices = [], radio = null;
  var savedValues = {}, savedExpose = {};
  var dirtyValues = Object.create(null), dirtyExpose = Object.create(null);
  var joinCooldownUntil = 0, joinPollTimer = null, hashJumpUntil = 0;
  var haSourceItems = [], haSourceRequest = 0, haSourceTimer = null;
  var homeDashboardItems = [], homeDashboardRequest = 0, homeDashboardQueried = false;
  // Assist pipeline catalogue for the voice_pipelines picker. null = not fetched yet, false = the
  // endpoint returned an error/503 (degrade to the raw JSON textarea), an array = the fetched catalogue.
  var voicePipelinesCatalog = null, voicePipelinesRequest = 0;
  // The wake words this panel holds, bundled and imported, for the voice_wake_words picker; same
  // null / false / list convention as the pipeline catalogue.
  var voiceWakeWordsCatalog = null, voiceWakeWordsRequest = 0, voiceWakeWordImportStatus = "";
  // A per-load, owner-safe seed supplied by the config response. The endpoint is still fetched every
  // render so a failed query, credential change or Home Assistant area edit can recover immediately.
  var haAreaSeed = null, haAreaSeedGeneration = 0, haAreaCatalogRequest = 0, haAreaUserOverride = false;
  var homeDashboardDefault = { explicit: false, path: "" };
  // Sentinel for the "Custom…" option. Not a reachable path: a real dashboard root must match
  // ^[a-z0-9][a-z0-9_-]*$, which no leading underscore can satisfy, so it can never collide.
  var CUSTOM_DASHBOARD = "__custom__";
  // A fast client-side reject for the shapes that can never address a dashboard (a URL, a protocol-
  // relative host, a traversal, whitespace). It is deliberately NOT the authority — SettingsRegistry's
  // home_dashboard validator is, running the same rule the renderer admits routes with — so this only
  // has to be tight enough to catch a typo before the round trip, never to be trusted.
  //
  // Evaluated through new RegExp, never as an HTML `pattern` attribute: current Chromium compiles
  // `pattern` with the `v` flag, where the unescaped `/` and `?` in `[^/?#\s]` are invalid, and a
  // browser that cannot parse a pattern silently IGNORES the constraint. That made this check inert on
  // Configure while the wizard's identical expression worked, and no string-comparing contract test
  // could see the difference. A trailing slash is accepted because the server canonicalizes `/alpha/`
  // to `/alpha`; the client must never refuse what the authority would accept.
  var DASHBOARD_PATH_PATTERN = "\\s*/?[a-z0-9][a-z0-9_-]*(?:/[^/?#]*)*(?:\\?[^#]*)?(?:#.*)?\\s*";
  function wellFormedDashboardPath(path) {
    return new RegExp("^(?:" + DASHBOARD_PATH_PATTERN + ")$").test(String(path || ""));
  }
  // The dashboard root a path belongs to (/alpha/view?k=1 → /alpha), or "" when it has none.
  function dashboardRootOf(path) {
    var route = String(path || "").trim().split("?")[0].split("#")[0];
    var first = route.split("/").filter(function (s) { return s !== ""; })[0] || "";
    return /^[a-z0-9][a-z0-9_-]*$/.test(first) ? "/" + first : "";
  }
  var haPickerCleanups = [];
  var haPickerCleanup = function () {
    haPickerCleanups.splice(0).forEach(function (cleanup) { cleanup(); });
  };
  var autoBrightStatus = null, autoBrightHistory = null, autoBrightLoading = false, autoBrightMessage = "";
  var autoBrightHistoryTimer = null, autoBrightRefreshTimer = null, autoBrightTransitionTimer = null, autoBrightRequest = 0;
  var autoBrightSourceTransition = false, autoBrightTransitionAttempts = 0, autoBrightTransitionSource = "";
  var AUTO_BRIGHTNESS_REFRESH_MS = 5 * 60 * 1000;
  var AUTO_BRIGHTNESS_TRANSITION_POLL_MS = 1000, AUTO_BRIGHTNESS_TRANSITION_MAX_ATTEMPTS = 10;
  var autoSleepStatus = null, autoSleepLoading = false, autoSleepRequest = 0;
  var autoSleepHistory = null, autoSleepHistoryLoading = false, autoSleepHistoryError = "", autoSleepHistoryRequest = 0;
  var autoSleepHistoryHours = 24;
  var autoSleepSourceUpdating = Object.create(null);
  var autoSleepHistoryWaiting = false, autoSleepHistoryWaitingMessage = "", autoSleepHistoryReadyTimer = null;
  var autoSleepReadinessDelayMs = 1000, autoSleepHistoryRetryDelayMs = 5000;
  var autoSleepPrerequisite = { eligible: false, phase: "checking", area_name: "" };
  var autoSleepPrerequisiteRequest = 0, autoSleepPrerequisiteTimer = null;
  var autoSleepAssignedAreaName = "", autoSleepAreaGeneration = 0;
  var haOauthButton = null, haOauthStatus = null, haOauthLinks = null;
  var haOauthAuthorizationUrl = "", haOauthTargetUrl = "";
  var haUserStatus = { phase: "unknown" }, haUserStatusRequest = 0;
  var localeDocumentReloadPending = false;
  var localeDocumentReloadScheduled = false;
  var localeReloadMessageConsumed = false;
  var localeReloadMessageKey = "ha-paneld-config-locale-reload-message";
  var UI_LANGUAGE_LABELS = {
    "auto": "Automatic", "en": "English", "de": "Deutsch", "fr": "Français",
    "it": "Italiano", "es": "Español", "zh-Hans": "简体中文",
    "nl": "Nederlands", "pl": "Polski", "uk": "Українська"
  };
  // Setting values are API/storage vocabulary. Keep them in option.value and translate only the
  // visible label through this closed map; an option added server-side before its catalogue entry
  // ships remains readable as its safe wire value instead of becoming an unbounded lookup key.
  var ENUM_OPTION_LABELS = {
    mqtt_address_family: {
      "Automatic": ["configure.enum.mqtt_address_family.automatic", "Automatic"],
      "Prefer IPv4": ["configure.enum.mqtt_address_family.prefer_ipv4", "Prefer IPv4"],
      "Force IPv4": ["configure.enum.mqtt_address_family.force_ipv4", "Force IPv4"]
    },
    navbar_mode: {
      "Off": ["configure.enum.navbar_mode.off", "Off"],
      "Always on": ["configure.enum.navbar_mode.always_on", "Always on"],
      "Swipe reveal": ["configure.enum.navbar_mode.swipe_reveal", "Swipe reveal"],
      "Native": ["configure.enum.navbar_mode.native", "Native"]
    },
    cpu_governor: {
      "Performance": ["configure.enum.cpu_governor.performance", "Performance"],
      "Efficiency": ["configure.enum.cpu_governor.efficiency", "Efficiency"],
      "Auto": ["configure.enum.cpu_governor.auto", "Auto"]
    },
    camera_resolution: {
      "480p": ["configure.enum.camera_resolution.480p", "480p (SD)"],
      "720p": ["configure.enum.camera_resolution.720p", "720p (HD)"],
      "1080p": ["configure.enum.camera_resolution.1080p", "1080p (Full HD)"]
    },
    dashboard_theme: {
      "Follow Home Assistant": ["configure.enum.dashboard_theme.follow_home_assistant", "Follow Home Assistant"],
      "Dark": ["configure.enum.dashboard_theme.dark", "Dark"],
      "Light": ["configure.enum.dashboard_theme.light", "Light"],
      "Ambient": ["configure.enum.dashboard_theme.ambient", "Ambient"]
    },
    update_channel: {
      "stable": ["configure.enum.update_channel.stable", "Stable"],
      "prerelease": ["configure.enum.update_channel.prerelease", "Prerelease"]
    },
    companion_update_channel: {
      "stable": ["configure.enum.update_channel.stable", "Stable"],
      "prerelease": ["configure.enum.update_channel.prerelease", "Prerelease"]
    },
    voice_audio_source: {
      "voice_recognition": ["configure.enum.voice_audio_source.voice_recognition", "Voice recognition"],
      "mic": ["configure.enum.voice_audio_source.mic", "Microphone"],
      "voice_communication": ["configure.enum.voice_audio_source.voice_communication", "Voice communication"]
    },
    voice_sensitivity: {
      "low": ["configure.enum.voice_sensitivity.low", "Low"],
      "normal": ["configure.enum.voice_sensitivity.normal", "Normal"],
      "high": ["configure.enum.voice_sensitivity.high", "High"]
    },
    log_ship_protocol: {
      "syslog-udp": ["configure.enum.log_ship_protocol.syslog_udp", "Syslog over UDP"],
      "syslog-tcp": ["configure.enum.log_ship_protocol.syslog_tcp", "Syslog over TCP"],
      "http": ["configure.enum.log_ship_protocol.http", "HTTP protocol"]
    }
  };

  function i18nText(key, fallback, vars) {
    return window.HaI18n && typeof window.HaI18n.t === "function"
      ? window.HaI18n.t(key, fallback, vars)
      : String(fallback == null ? "" : fallback).replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, function (placeholder, name) {
        return vars && Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : placeholder;
      });
  }

  function localizedPlaceholder(value) {
    if (value === "auto") return i18nText("configure.option.auto", "auto");
    if (value === "Auto-detect") return i18nText("configure.package.auto_detect", "Auto-detect");
    return value;
  }
  function localizedEnumOption(fieldKey, wireValue) {
    if (fieldKey === "auto_sleep_source") return wireValue === "panel"
      ? i18nText("configure.auto_sleep.source_panel", "This panel’s proximity sensor")
      : i18nText("configure.auto_sleep.source_ha", "Home Assistant Area devices");
    if (fieldKey === "ui_language" && Object.prototype.hasOwnProperty.call(UI_LANGUAGE_LABELS, wireValue)) {
      return wireValue === "auto"
        ? i18nText("configure.language.automatic", "Automatic")
        : UI_LANGUAGE_LABELS[wireValue];
    }
    var fieldLabels = Object.prototype.hasOwnProperty.call(ENUM_OPTION_LABELS, fieldKey)
      ? ENUM_OPTION_LABELS[fieldKey] : null;
    var binding = fieldLabels && Object.prototype.hasOwnProperty.call(fieldLabels, wireValue)
      ? fieldLabels[wireValue] : null;
    return binding ? i18nText(binding[0], binding[1]) : String(wireValue);
  }

  function validLanguageTag(value) {
    return typeof value === "string" && value.length <= 63 &&
      /^[A-Za-z0-9]{1,8}(?:[-_][A-Za-z0-9]{1,8})*$/.test(value);
  }

  // Keep HA's syntactically valid selectedLanguage value intact even when this release cannot render
  // it. Only an admitted locale is an override: an unsupported stored tag must not suppress the
  // connected HA user's later, supported language signal.
  function admittedBrowserLanguage(value) {
    if (!validLanguageTag(value)) return false;
    var lower = value.replace(/_/g, "-").toLowerCase();
    var admitted = Object.keys(UI_LANGUAGE_LABELS).filter(function (locale) { return locale !== "auto"; });
    if (admitted.some(function (locale) {
      var candidate = locale.toLowerCase();
      return lower === candidate || lower.indexOf(candidate + "-") === 0;
    })) return true;
    return lower === "zh" || lower === "zh-cn" || lower.indexOf("zh-cn-") === 0 ||
      lower === "zh-sg" || lower.indexOf("zh-sg-") === 0;
  }

  // Embedded in Panel Assistant's sidebar the page shares Home Assistant's origin, where selectedLanguage
  // is Home Assistant's own language setting: never read or write it there. The sidebar sends the
  // Home Assistant user's language with every request instead.
  var EMBEDDED = !!(document.body && document.body.hasAttribute("data-embedded"));

  function storeBrowserLanguage(value) {
    if (EMBEDDED) return;
    try {
      if (value) window.localStorage.setItem("selectedLanguage", JSON.stringify(value));
      else window.localStorage.removeItem("selectedLanguage");
    } catch (_) {}
  }

  function stripLanguageQuery() {
    if (!window.history || !window.history.replaceState) return;
    var params = new URLSearchParams(window.location.search);
    if (!params.has("lang")) return;
    params.delete("lang");
    var query = params.toString();
    window.history.replaceState(null, "", window.location.pathname + (query ? "?" + query : "") + window.location.hash);
  }

  function localizedPageHref(path) {
    var locale = window.HaI18n && typeof window.HaI18n.locale === "string" ? window.HaI18n.locale : "";
    if (!validLanguageTag(locale) || locale.toLowerCase() === "en") return path;
    var fragmentAt = path.indexOf("#");
    var address = fragmentAt < 0 ? path : path.slice(0, fragmentAt);
    var fragment = fragmentAt < 0 ? "" : path.slice(fragmentAt);
    return address + (address.indexOf("?") < 0 ? "?" : "&") + "lang=" + encodeURIComponent(locale) + fragment;
  }

  // HA uses this JSON-encoded localStorage key for its own browser language choice. The same shape
  // gives Configure a durable browser override without inventing another client-side authority.
  function browserLanguageChoice() {
    var query = new URLSearchParams(window.location.search).get("lang");
    if (query && query.toLowerCase() === "auto") {
      storeBrowserLanguage("");
      stripLanguageQuery();
      return "";
    }
    if (validLanguageTag(query)) {
      storeBrowserLanguage(query);
      return query;
    }
    if (EMBEDDED) return "";
    try {
      var stored = JSON.parse(window.localStorage.getItem("selectedLanguage") || "null");
      return validLanguageTag(stored) ? stored : "";
    } catch (_) { return ""; }
  }

  function configSchemaUrl(haLanguage) {
    var params = new URLSearchParams();
    var explicit = browserLanguageChoice();
    if (explicit) params.set("lang", explicit);
    if (validLanguageTag(haLanguage)) params.set("ha_lang", haLanguage);
    var query = params.toString();
    return "api/v1/config/schema" + (query ? "?" + query : "");
  }

  // The Configure selector is the explicit panel setting. Once its save is confirmed, an older
  // browser/query override must not continue to outrank the value the user just chose. Recreate the
  // document so every localized field is fetched under the resulting precedence; a concurrent edit
  // can defer that reload, but cannot lose it.
  function acceptSavedUiLanguage(submittedValues) {
    if (!Object.prototype.hasOwnProperty.call(submittedValues, "ui_language")) return;
    storeBrowserLanguage("");
    stripLanguageQuery();
    localeDocumentReloadPending = true;
  }

  function reloadForSavedLocale(message) {
    if (message) {
      try { window.sessionStorage.setItem(localeReloadMessageKey, message); } catch (_) {}
    }
    window.location.reload();
  }

  function reloadSavedLocaleWhenSettled() {
    if (!localeDocumentReloadPending || dirty || saving || localeDocumentReloadScheduled) return;
    localeDocumentReloadScheduled = true;
    setTimeout(function () {
      localeDocumentReloadScheduled = false;
      if (localeDocumentReloadPending && !dirty && !saving) reloadForSavedLocale();
    }, 0);
  }

  function consumeLocaleReloadMessage() {
    if (localeReloadMessageConsumed) return;
    localeReloadMessageConsumed = true;
    var reloadMessage = "";
    try {
      reloadMessage = window.sessionStorage.getItem(localeReloadMessageKey) || "";
      window.sessionStorage.removeItem(localeReloadMessageKey);
    } catch (_) {}
    if (reloadMessage) document.getElementById("cfg-msg").textContent = reloadMessage;
  }

  function readLocalizedSchema(response) {
    return response.json();
  }

  function reloadSchemaForHaLanguage() {
    if (values.ui_language !== "auto" || admittedBrowserLanguage(browserLanguageChoice()) ||
        haUserStatus.phase !== "connected" || !validLanguageTag(haUserStatus.language)) return;
    var request = ++schemaLanguageRequest;
    var generation = editGeneration;
    fetch(configSchemaUrl(haUserStatus.language), { headers: { "Accept": "application/json" }, cache: "no-store" })
      .then(function (response) {
        return response.json();
      })
      .then(function (nextSchema) {
        if (request !== schemaLanguageRequest || dirty || editGeneration !== generation ||
            !Array.isArray(nextSchema)) return;
        schema = nextSchema;
        render();
        configCardGeometryChanged();
      })
      .catch(function () {});
  }
  var configCardRoot = document.getElementById("cfg-groups");
  var configCardExpected = { core: true, radio: true, brightness: true };
  var configCardReady = Object.create(null), configCardMemoryReady = false;
  if (configCardRoot && configCardRoot.getAttribute("data-card-size-proximity") === "1") configCardExpected.proximity = true;
  function configCardSourceReady(source) {
    if (configCardReady[source]) return;
    configCardReady[source] = true;
    var complete = Object.keys(configCardExpected).every(function (key) { return configCardReady[key]; });
    if (!complete) return;
    configCardMemoryReady = true;
    if (window.CardSizeMemory) window.CardSizeMemory.settle("cfg-groups", 1200);
  }
  function configCardGeometryChanged() {
    if (configCardMemoryReady && window.CardSizeMemory) window.CardSizeMemory.settle("cfg-groups", 1200);
  }
  function configCardGeometryInvalid() {
    if (window.CardSizeMemory) window.CardSizeMemory.invalidate("cfg-groups");
  }
  window.configCardSizeSourceReady = configCardSourceReady;
  window.configCardSizeGeometryChanged = configCardGeometryChanged;
  window.configCardSizeGeometryInvalid = configCardGeometryInvalid;
  var HARDENED_APPROVAL_SETTING_KEYS = {
    self_update: true, update_channel: true, companion_auto_update: true,
    companion_update_channel: true, webview_auto_update: true,
    keep_awake: true, prevent_idle_dim: true
  };

  function approvalMessage(body) {
    return i18nText("configure.approval.retry", "Approve this request on the panel, then retry it.");
  }

  function approvalAwareJson(response) {
    return response.json().catch(function () { return {}; }).then(function (body) {
      if (response.status === 202 && body && body.error === "approval-required") {
        var error = new Error(approvalMessage(body));
        error.approvalRequired = true;
        error.body = body;
        throw error;
      }
      return body;
    });
  }

  function el(tag, attrs, kids) {
    var e = document.createElement(tag);
    attrs = attrs || {};
    for (var k in attrs) {
      if (k === "class") e.className = attrs[k];
      else if (k === "text") e.textContent = attrs[k];
      else if (k === "html") e.innerHTML = attrs[k];
      else e.setAttribute(k, attrs[k]);
    }
    (kids || []).forEach(function (c) { if (c) e.appendChild(c); });
    return e;
  }
  function updateSaveUi() {
    var button = document.getElementById("savebtn");
    var bar = document.getElementById("savebar");
    button.disabled = !dirty || saving;
    button.textContent = saving ? i18nText("configure.save.saving", "Saving…") : i18nText("configure.save.action", "Save changes");
    bar.hidden = !dirty && !saving;
    document.body.classList.toggle("cfg-dirty", dirty || saving);
    syncUnsavedNavigationGuard();
  }
  function recomputeDirty() {
    dirtyValues = Object.create(null);
    dirtyExpose = Object.create(null);
    schema.forEach(function (f) {
      if (values[f.key] !== savedValues[f.key]) dirtyValues[f.key] = true;
      if (f.ha && (expose[f.key] !== false) !== (savedExpose[f.key] !== false)) dirtyExpose[f.key] = true;
    });
    dirty = Object.keys(dirtyValues).length > 0 || Object.keys(dirtyExpose).length > 0;
  }
  function setDirty() {
    editGeneration++;
    recomputeDirty();
    updateSaveUi();
    syncHaOAuthAvailability();
    syncAutoSleepCardSignature();
    reloadSavedLocaleWhenSettled();
  }
  function clearDirty() {
    dirty = false;
    dirtyValues = Object.create(null);
    dirtyExpose = Object.create(null);
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
      reloadSavedLocaleWhenSettled();
    }, 0);
  }

  function guardUnsavedNavigation(event) {
    event.preventDefault();
    event.returnValue = "";
  }

  var unsavedNavigationGuardArmed = false;
  function syncUnsavedNavigationGuard() {
    if (typeof window.addEventListener !== "function" || typeof window.removeEventListener !== "function") return;
    var shouldArm = dirty || saving;
    if (shouldArm === unsavedNavigationGuardArmed) return;
    unsavedNavigationGuardArmed = shouldArm;
    if (shouldArm) window.addEventListener("beforeunload", guardUnsavedNavigation);
    else window.removeEventListener("beforeunload", guardUnsavedNavigation);
  }

  function validHaUrlForOAuth() {
    try {
      var url = new URL(String(values.ha_url || "").trim());
      return (url.protocol === "http:" || url.protocol === "https:") && !!url.hostname &&
        !url.username && !url.password && !url.search && !url.hash;
    } catch (_) { return false; }
  }

  function syncHaOAuthAvailability() {
    if (!haOauthButton) return;
    haOauthButton.disabled = !validHaUrlForOAuth();
    haOauthButton.title = haOauthButton.disabled ? i18nText("configure.oauth.valid_url_first", "Enter a valid Home Assistant URL first.") : "";
  }

  function keepSaveMessageVisible(text) {
    return /Home Assistant sign-in|sign-in workflow/i.test(String(text || ""));
  }

  function copyText(value) {
    if (navigator.clipboard && navigator.clipboard.writeText) return navigator.clipboard.writeText(value);
    var input = el("textarea", { "aria-hidden": "true" });
    input.value = value; input.style.position = "fixed"; input.style.left = "-9999px";
    document.body.appendChild(input); input.select();
    var copied = document.execCommand("copy");
    document.body.removeChild(input);
    return copied ? Promise.resolve() : Promise.reject(new Error("copy unavailable"));
  }

  function startHaOAuth() {
    if (!haOauthButton || !validHaUrlForOAuth()) return;
    var target = String(values.ha_url || "").trim().replace(/\/+$/, "");
    // Choosing browser sign-in supersedes a manually typed token immediately. This also prevents a
    // private-window completion, which cannot signal the ordinary browser context, from being overwritten
    // by that stale form value on a later unrelated save.
    values.ha_token = "";
    savedValues.ha_token = "";
    var tokenInput = document.querySelector("#cfg-ha_token input");
    if (tokenInput) tokenInput.value = "";
    recomputeDirty(); updateSaveUi();
    haOauthButton.disabled = true;
    setHaOauthStatus(i18nText("configure.oauth.starting", "Starting sign-in…"), false);
    haOauthAuthorizationUrl = "";
    haOauthTargetUrl = "";
    haOauthLinks.hidden = true;
    var oauthLocale = window.HaI18n && typeof window.HaI18n.locale === "string"
      ? window.HaI18n.locale : (document.documentElement.lang || "en");
    fetch("api/v1/ha/oauth/start", {
      method: "POST",
      headers: { "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        ha_url: target,
        ui_locale: oauthLocale,
        return_surface: "configure",
        preserve_explicit_english: new URLSearchParams(location.search).has("lang") && oauthLocale === "en" ? "1" : "0"
      }).toString()
    }).then(function (response) {
      return response.json().catch(function () { return {}; }).then(function (body) {
        if (!response.ok || !body.authorization_url) {
          var failure = new Error(body && body.error === "invalid-ha-url"
            ? i18nText("configure.oauth.valid_url_first", "Enter a valid Home Assistant URL first.")
            : i18nText("configure.oauth.start_failed", "Could not start sign-in."));
          failure.localizedMessage = failure.message;
          throw failure;
        }
        return body.authorization_url;
      });
    }).then(function (authorizationUrl) {
      haOauthAuthorizationUrl = authorizationUrl;
      haOauthTargetUrl = target;
      var openLink = haOauthLinks.querySelector("a");
      openLink.href = authorizationUrl;
      haOauthLinks.hidden = false;
      setHaOauthStatus(i18nText("configure.oauth.link_ready", "Sign-in link ready. Open it normally or copy it into a private window."), false);
    }).catch(function (error) {
      setHaOauthStatus(error && error.localizedMessage
        ? error.localizedMessage
        : i18nText("configure.oauth.start_failed", "Could not start sign-in."), false);
    }).then(function () { syncHaOAuthAvailability(); });
  }

  function haConnectionStatusText() {
    if (haUserStatus.phase === "connected") {
      return haUserStatus.display_name
        ? i18nText("configure.oauth.connected_as", "Connected as {name}", { name: haUserStatus.display_name })
        : i18nText("configure.oauth.connected", "Connected");
    }
    if (haUserStatus.phase === "rejected") return i18nText("configure.oauth.rejected", "Sign-in rejected — reconnect to Home Assistant");
    if (haUserStatus.phase === "unavailable") {
      return i18nText("configure.oauth.status_unavailable", "{method} · status unavailable", {
        method: haAuth.oauth ? i18nText("configure.oauth.method_oauth", "OAuth configured") : i18nText("configure.oauth.method_token", "Long-lived token configured")
      });
    }
    return haAuth.oauth ? i18nText("configure.oauth.method_oauth", "OAuth configured")
      : haAuth.configured ? i18nText("configure.oauth.method_token", "Long-lived token configured")
      : i18nText("configure.oauth.not_configured", "Not configured");
  }

  function setHaOauthStatus(text, connected) {
    if (!haOauthStatus) return;
    haOauthStatus.textContent = text;
    haOauthStatus.classList.toggle("connected", connected === true);
  }

  function renderHaConnectionStatus() {
    setHaOauthStatus(haConnectionStatusText(), haUserStatus.phase === "connected");
  }

  function loadHaUserStatus() {
    var request = ++haUserStatusRequest;
    if (!haAuth.configured) {
      haUserStatus = { phase: "not_configured" };
      renderHaConnectionStatus();
      return;
    }
    var succeeded = false;
    fetch("api/v1/ha/oauth/status", { headers: { "Accept": "application/json" }, cache: "no-store" })
      .then(function (response) { if (!response.ok) throw response.status; return response.json(); })
      .then(function (body) {
        if (request !== haUserStatusRequest) return;
        haUserStatus = body || { phase: "unavailable" };
        succeeded = true;
      })
      .catch(function () {
        if (request === haUserStatusRequest) { haUserStatus = { phase: "unavailable" };
          if (typeof window !== "undefined" && window.configCardSizeGeometryInvalid) window.configCardSizeGeometryInvalid(); }
      })
      .then(function () {
        if (request === haUserStatusRequest) { renderHaConnectionStatus(); if (succeeded && typeof window !== "undefined" && window.configCardSizeSourceReady) {
          window.configCardSizeSourceReady("ha");if (window.configCardSizeGeometryChanged) window.configCardSizeGeometryChanged(); }
          if (succeeded) reloadSchemaForHaLanguage(); }
      });
  }

  function haOAuthRow() {
    haOauthButton = el("button", {
      class: "pbtn", type: "button", text: haAuth.configured ? i18nText("configure.oauth.reconnect", "Reconnect") : i18nText("configure.oauth.connect", "Connect")
    });
    haOauthButton.addEventListener("click", startHaOAuth);
    haOauthStatus = el("div", {
      class: "ha-oauth-status",
      text: haConnectionStatusText()
    });
    renderHaConnectionStatus();
    var openLink = el("a", {
      class: "pbtn", target: "_blank", rel: "noopener noreferrer", referrerpolicy: "no-referrer", text: i18nText("configure.oauth.open_sign_in", "Open sign-in")
    });
    var copyButton = el("button", { class: "pbtn", type: "button", text: i18nText("configure.oauth.copy_link", "Copy link") });
    copyButton.addEventListener("click", function () {
      copyText(haOauthAuthorizationUrl).then(function () {
        setHaOauthStatus(i18nText("configure.oauth.link_copied", "Sign-in link copied."), false);
      }).catch(function () { setHaOauthStatus(i18nText("configure.oauth.copy_failed", "Could not copy the link."), false); });
    });
    haOauthLinks = el("div", { class: "ha-oauth-links" }, [openLink, copyButton]);
    haOauthLinks.hidden = !haOauthAuthorizationUrl;
    if (haOauthAuthorizationUrl) openLink.href = haOauthAuthorizationUrl;
    var guidance = !haAuth.configured
      ? i18nText("configure.oauth.enter_url", "Enter the Home Assistant URL above, then connect this panel.")
      : !haAuth.oauth ? i18nText("configure.oauth.browser_recommended", "Browser sign-in is recommended; the long-lived token remains available as an advanced fallback.") : "";
    var row = el("div", { class: "frow ha-oauth-row", id: "cfg-ha-oauth" }, [
      el("div", { class: "flabel" }, [
        el("span", { text: i18nText("configure.oauth.browser_sign_in", "Browser sign-in") }),
        haOauthStatus,
        el("small", { text: i18nText("configure.oauth.sign_in_help", "Sign in from this computer. To sign in as another user, copy the link into a private window.") }),
        EMBEDDED ? el("small", { class: "ha-oauth-guidance", text: i18nText("configure.oauth.panel_reachability", "To finish sign-in, this browser must reach the panel's address. If it cannot, open Configure from the panel's network or sign in on the panel.") }) : null,
        guidance ? el("small", { class: "ha-oauth-guidance", text: guidance }) : null
      ]),
      el("div", { class: "fctl ha-oauth-actions" }, [haOauthButton, haOauthLinks])
    ]);
    syncHaOAuthAvailability();
    return row;
  }

  function ambientLightSourceReady() {
    var selected = String(values.auto_brightness_ha_entity || "").trim();
    var statusEntity = autoBrightStatus && (autoBrightStatus.entityId || autoBrightStatus.entity_id) || "";
    var statusLux = autoBrightStatus && (autoBrightStatus.latestLux != null ? autoBrightStatus.latestLux : autoBrightStatus.latest_lux);
    var statusReady = !!(autoBrightStatus &&
      (autoBrightStatus.sourceAvailable === true || autoBrightStatus.source_available === true) &&
      typeof statusLux === "number" && isFinite(statusLux));
    if (!selected) return statusReady && !statusEntity;
    if (statusReady && statusEntity === selected) return true;
    return haSourceItems.some(function (item) {
      var id = item.entity_id || item.entityId || "";
      var lux = item.current_lux != null ? item.current_lux : item.currentLux;
      return id === selected && item.available === true && typeof lux === "number" && isFinite(lux);
    });
  }

  function ambientLightSourceConfigured() {
    if (String(values.auto_brightness_ha_entity || "").trim()) return true;
    return !!(autoBrightStatus &&
      (autoBrightStatus.localSourcePresent === true || autoBrightStatus.local_source_present === true));
  }

  function ambientSourcePlaceholder() {
    if (!autoBrightStatus) return i18nText("configure.brightness.source_checking", "Checking ambient light source…");
    var localPresent = autoBrightStatus.localSourcePresent === true || autoBrightStatus.local_source_present === true;
    return localPresent ? i18nText("configure.brightness.panel_sensor", "Panel ambient light sensor") : i18nText("configure.brightness.select_ha_sensor", "Select a Home Assistant illuminance sensor");
  }

  // One input control bound to values[f.key]; Save appears only while the form differs from its baseline.
  function control(f) {
    var v = values[f.key];
    if (f.type === "BOOL") {
      var sourceBlocked = f.key === "auto_brightness" && !ambientLightSourceReady();
      var prerequisiteBlocked = f.key === "auto_sleep" && !autoSleepUsesPanel() && v !== "true" && autoSleepPrerequisite.eligible !== true;
      var blocked = sourceBlocked || prerequisiteBlocked;
      var t = el("div", {
        class: "toggle" + (v === "true" && !sourceBlocked ? " on" : "") + (blocked ? " blocked" : ""),
        role: "switch", tabindex: "0", "aria-label": f.label,
        "aria-checked": v === "true" && !sourceBlocked ? "true" : "false", "aria-disabled": blocked ? "true" : "false"
      });
      if (f.key === "auto_sleep") t.setAttribute("aria-describedby", "auto-sleep-prerequisite-status");
      if (sourceBlocked) t.title = i18nText("configure.brightness.waiting_valid_reading", "Waiting for a valid ambient light reading.");
      function toggleValue() {
        if (f.key === "auto_brightness" && !ambientLightSourceReady()) return;
        if (f.key === "auto_sleep" && !autoSleepUsesPanel() && values[f.key] !== "true" && autoSleepPrerequisite.eligible !== true) return;
        v = (values[f.key] === "true") ? "false" : "true";
        values[f.key] = v;
        t.classList.toggle("on", v === "true");
        t.setAttribute("aria-checked", v === "true" ? "true" : "false");
        setDirty(f.key);
        if (f.key === "auto_sleep") updateAutoSleepPrerequisiteUi();
      }
      t.addEventListener("click", toggleValue);
      t.addEventListener("keydown", function (event) {
        if (event.key !== "Enter" && event.key !== " ") return;
        event.preventDefault();
        toggleValue();
      });
      return t;
    }
    if (f.type === "ENUM") {
      var s = el("select");
      f.options.forEach(function (o) {
        var label = localizedEnumOption(f.key, o);
        var op = el("option", { value: o, text: label }); if (o === v) op.selected = true; s.appendChild(op);
      });
      s.addEventListener("change", function () {
        values[f.key] = s.value; setDirty(f.key);
        if (f.key === "auto_sleep_source") {
          autoSleepPrerequisiteRequest++;
          invalidateAutoSleepData(true);
          var previousPanel = document.getElementById("auto-sleep-status");
          if (previousPanel) previousPanel.remove();
          render(); loadAutoSleepPrerequisite();
        }
      });
      return s;
    }
    // Dashboard-app picker: Auto and ha-paneld's built-in renderer remain first. The server adds only
    // installed supported Companion variants; arbitrary launchable apps never become renderer choices.
    if (f.picker === "renderer") {
      var cur = v == null ? "" : v;
      var sel = el("select", { class: "pkgsel" });
      sel.appendChild(el("option", { value: "", text: localizedPlaceholder(f.placeholder || "auto") }));
      var seen = { "": true };
      var KNOWN = [
        { pkg: "builtin", label: i18nText("configure.renderer.builtin", "Built-in renderer (ha-paneld)") }
      ].concat(rendererChoices);
      KNOWN.forEach(function (r) {
        if (!r || !r.pkg || seen[r.pkg]) return;
        seen[r.pkg] = true;
        var op = el("option", { value: r.pkg, text: r.label });
        if (r.pkg === cur) op.selected = true;
        sel.appendChild(op);
      });
      // A currently-set external renderer is preserved so it isn't silently lost.
      if (cur && !seen[cur]) {
        var o2 = el("option", { value: cur, text: i18nText("configure.renderer.configured_external", "{package} · configured external renderer", { package: cur }) });
        o2.selected = true; sel.appendChild(o2);
      }
      sel.addEventListener("change", function () {
        values[f.key] = sel.value;
        setDirty(f.key);
        // Renderer-owned rows follow the in-flight picker value immediately; saving is not required
        // just to reveal the built-in renderer's controls.
        if (f.key === "dashboard_package") render();
      });
      return sel;
    }
    // Package picker: a dropdown of installed apps. Blank = "Auto-detect"; a currently-set package that
    // isn't in the list (e.g. since-uninstalled or a manual entry) is kept as its own option.
    if (f.picker === "package") {
      var cur = v == null ? "" : v;
      var sel = el("select", { class: "pkgsel" });
      var autoLabel = localizedPlaceholder(f.placeholder || "Auto-detect");
      sel.appendChild(el("option", { value: "", text: autoLabel }));
      var seen = { "": true };
      apps.forEach(function (a) {
        seen[a.pkg] = true;
        var op = el("option", { value: a.pkg, text: a.label + " · " + a.pkg });
        if (a.pkg === cur) op.selected = true;
        sel.appendChild(op);
      });
      if (cur && !seen[cur]) {
        var o2 = el("option", { value: cur, text: i18nText("configure.package.not_installed", "{package} · (not installed)", { package: cur }) });
        o2.selected = true; sel.appendChild(o2);
      }
      sel.addEventListener("change", function () { values[f.key] = sel.value; setDirty(f.key); });
      return sel;
    }
    // Wake-word picker: a checkbox per wake word the panel holds (the bundled ones and any imported), and
    // the import of one the owner trained: its microWakeWord .json manifest and .tflite model, which the
    // panel checks with its own engine before offering it. Degrades to the raw JSON textarea while the
    // list is unavailable.
    if (f.picker === "voice_wake_words") {
      if (voiceWakeWordsCatalog === null) loadVoiceWakeWords();
      var wakeWrap = el("div", { class: "voice-wake-words-picker" });
      if (!Array.isArray(voiceWakeWordsCatalog)) {
        var wakeRaw = el("textarea", { class: "voice-wake-words-raw", rows: "2", text: v == null ? "" : v });
        wakeRaw.addEventListener("input", function () { values[f.key] = wakeRaw.value; setDirty(f.key); });
        wakeWrap.appendChild(wakeRaw);
        return wakeWrap;
      }
      var activeWakeWords = [];
      try {
        var parsedActive = JSON.parse(v || "[]");
        if (Array.isArray(parsedActive)) activeWakeWords = parsedActive.filter(function (w) { return typeof w === "string" && w; });
      } catch (e) { activeWakeWords = []; }
      voiceWakeWordsCatalog.forEach(function (word) {
        var id = word && word.id ? String(word.id) : "";
        if (!id) return;
        var box = el("input", { type: "checkbox" });
        box.checked = activeWakeWords.indexOf(id) >= 0;
        box.addEventListener("change", function () {
          activeWakeWords = activeWakeWords.filter(function (w) { return w !== id; });
          if (box.checked) activeWakeWords.push(id);
          values[f.key] = JSON.stringify(activeWakeWords);
          setDirty(f.key);
          render();
        });
        wakeWrap.appendChild(el("label", { class: "voice-wake-word-row", style: "display:block" }, [box, el("span", { text: word.wake_word ? String(word.wake_word) : id })]));
      });
      var manifestInput = el("input", { type: "file", accept: ".json,application/json" });
      var modelInput = el("input", { type: "file", accept: ".tflite" });
      var importButton = el("button", { type: "button", class: "btn", text: i18nText("configure.voice.import_wake_word", "Import trained wake word") });
      importButton.addEventListener("click", function () {
        var manifestFile = manifestInput.files && manifestInput.files[0];
        var modelFile = modelInput.files && modelInput.files[0];
        if (!manifestFile || !modelFile) {
          voiceWakeWordImportStatus = i18nText("configure.voice.import_choose_files", "Choose the .json manifest and the .tflite model first.");
          render();
          return;
        }
        importButton.disabled = true;
        importVoiceWakeWord(manifestFile, modelFile);
      });
      wakeWrap.appendChild(el("div", { class: "voice-wake-word-import", style: "display:grid;gap:6px;margin-top:8px" }, [
        el("small", { text: i18nText("configure.voice.import_help", "Import a microWakeWord model you trained: its .json manifest and .tflite file.") }),
        el("a", {
          class: "voice-wake-word-guide", href: WAKE_WORD_GUIDE_URL, target: "_blank", rel: "noopener noreferrer",
          text: i18nText("configure.voice.import_guide", "How to train your own wake word")
        }),
        manifestInput, modelInput, importButton,
        voiceWakeWordImportStatus ? el("small", { text: voiceWakeWordImportStatus }) : null,
      ]));
      return wakeWrap;
    }
    // Wake-word-pipeline picker: one native select per configured wake word (from voice_wake_words),
    // offering the Home Assistant Assist pipelines fetched from /api/v1/voice/pipelines. Degrades to the
    // raw JSON textarea while that endpoint hasn't answered yet, or answered 503 (no Home Assistant
    // connection, or the voice-coordinator lane not wired up yet) — Safari-first: plain fetch/select/
    // textarea, no picker library.
    if (f.picker === "voice_pipelines") {
      if (voicePipelinesCatalog === null) loadVoicePipelines();
      if (voiceWakeWordsCatalog === null) loadVoiceWakeWords();
      var pipelinesWrap = el("div", { class: "voice-pipelines-picker" });
      // The raw textarea is focused/mid-edit exactly when it is document.activeElement — checked
      // against THIS render's about-to-be-replaced node, before reconcileConfigCards swaps it out.
      // A catalogue fetch resolving while someone is typing must not rip the control out from under
      // them: keep rendering the raw-textarea view for this pass even though the catalogue is now
      // available, so nothing they typed (committed to `values` or not) or their cursor position is
      // lost. The next render — the following keystroke's `input` event, or a blur — re-evaluates and
      // switches to the picker once it is safe to.
      var activeRawTextarea = document.activeElement &&
        document.activeElement.classList && document.activeElement.classList.contains("voice-pipelines-raw") &&
        document.activeElement.getAttribute("data-field-key") === f.key ? document.activeElement : null;
      if (voicePipelinesCatalog === null || voicePipelinesCatalog === false || activeRawTextarea) {
        var pipelinesRaw = el("textarea", {
          class: "voice-pipelines-raw", rows: "2", "data-field-key": f.key, text: v == null ? "" : v,
        });
        // `input`, not `change`: `change` only fires on blur, so a catalogue response landing mid-
        // keystroke previously re-rendered with whatever was last blurred, discarding anything typed
        // since. Committing on every keystroke means `values[f.key]` is always current, so nothing
        // typed is ever lost even if the very next render switches this field to the select picker.
        pipelinesRaw.addEventListener("input", function () {
          values[f.key] = pipelinesRaw.value; setDirty(f.key);
        });
        pipelinesWrap.appendChild(pipelinesRaw);
        if (voicePipelinesCatalog === false) {
          pipelinesWrap.appendChild(el("small", { text: i18nText("configure.voice.pipeline_unavailable", "Pipeline list unavailable — edit as JSON.") }));
        }
        return pipelinesWrap;
      }
      var configuredWakeWords = [];
      try {
        var parsedWakeWords = JSON.parse(values.voice_wake_words || "[]");
        if (Array.isArray(parsedWakeWords)) {
          configuredWakeWords = parsedWakeWords.filter(function (w) { return typeof w === "string" && w; });
        }
      } catch (e) { configuredWakeWords = []; }
      if (!configuredWakeWords.length) {
        pipelinesWrap.appendChild(el("small", { text: i18nText("configure.voice.configure_wake_word_first", "Configure a wake word above first.") }));
        return pipelinesWrap;
      }
      var pipelineMapping = {};
      try {
        var parsedMapping = JSON.parse(v || "{}");
        if (parsedMapping && typeof parsedMapping === "object" && !Array.isArray(parsedMapping)) {
          pipelineMapping = parsedMapping;
        }
      } catch (e) { pipelineMapping = {}; }
      configuredWakeWords.forEach(function (word) {
        var pipelineRow = el("div", { class: "voice-pipeline-row", style: "display:flex;align-items:center;gap:8px;margin-bottom:6px" });
        var known = Array.isArray(voiceWakeWordsCatalog) && voiceWakeWordsCatalog.filter(function (w) { return w && w.id === word; })[0];
        pipelineRow.appendChild(el("span", { class: "voice-pipeline-label", style: "flex:0 0 auto", text: known && known.wake_word ? String(known.wake_word) : word }));
        var pipelineSelect = el("select", { style: "flex:1 1 auto;min-width:0;max-width:100%" });
        pipelineSelect.appendChild(el("option", { value: "", text: i18nText("configure.voice.preferred_pipeline", "Preferred pipeline") }));
        var retainedPipelineId = pipelineMapping[word];
        var matchedRetained = false;
        voicePipelinesCatalog.forEach(function (p) {
          var pid = p && p.id ? String(p.id) : "";
          if (!pid) return;
          var pname = p && p.name ? String(p.name) : pid;
          var pipelineOption = el("option", { value: pid, text: pname });
          if (retainedPipelineId === pid) { pipelineOption.selected = true; matchedRetained = true; }
          pipelineSelect.appendChild(pipelineOption);
        });
        // A retained id Home Assistant no longer offers (the pipeline was removed/renamed there) must
        // never render as if nothing were selected — that reads as "Preferred pipeline" (unset) while
        // silently keeping the stale id. Represent it honestly, mirroring the renderer/package pickers'
        // "configured external renderer"/"(not installed)" retained-value pattern, and it stays
        // clearable through the existing empty "Preferred pipeline" option.
        if (retainedPipelineId && !matchedRetained) {
          var unknownOption = el("option", {
            value: String(retainedPipelineId), text: i18nText("configure.voice.pipeline_not_listed", "{pipeline} · not in Home Assistant's list", { pipeline: retainedPipelineId }),
          });
          unknownOption.selected = true;
          pipelineSelect.appendChild(unknownOption);
        }
        pipelineSelect.addEventListener("change", function () {
          if (pipelineSelect.value) pipelineMapping[word] = pipelineSelect.value;
          else delete pipelineMapping[word];
          values[f.key] = JSON.stringify(pipelineMapping);
          setDirty(f.key);
        });
        pipelineRow.appendChild(pipelineSelect);
        pipelinesWrap.appendChild(pipelineRow);
      });
      return pipelinesWrap;
    }
    // Home dashboard picker: Home Assistant provides this signed-in user's dashboards in its own order.
    // Deliberately a NATIVE select. A custom popup was tried (to carry the dashboards' icons like HA's
    // own picker) and was a bust on hardware review: it escaped the card, ran off the viewport and stole
    // wheel scrolling — the browser's own popup gets all of that right on every platform, and the
    // compact form deliberately favours clean rows over icons. The wizard's dedicated page keeps the icon
    // list; this form keeps HA's GROUPING via native optgroups, which is the part that carries real information.
    // Auto intentionally remains first; a legacy/custom configured path is preserved rather than silently lost.
    // "Custom…" reveals a plain text input UNDER the select, which is how a specific view below a
    // dashboard root (/alpha/beta) is entered — Home Assistant's list endpoint only ever
    // returns roots. A revealed input, rather than an editable combobox, is what keeps the earlier
    // hardware verdict intact: nothing floats, nothing escapes the card, the native popup still owns
    // the list. A configured path that is not in the list now lands here instead of in a dead-end
    // option, so it can finally be edited on the panel.
    if (f.picker === "ha_dashboard") {
      var currentDashboard = v == null ? "" : v;
      var dashboardSelect = el("select", { class: "pkgsel" });
      var autoText = !homeDashboardQueried ? i18nText("configure.dashboard.auto_list_unavailable", "Auto — dashboard list unavailable")
        : (homeDashboardDefault.explicit
          ? i18nText("configure.dashboard.auto_account_default", "Auto — follow this account’s default")
          : i18nText("configure.dashboard.auto_no_default", "Auto — no default set for this account"));
      var autoOption = el("option", { value: "", text: autoText });
      dashboardSelect.appendChild(autoOption);
      var dashboardPaths = { "": true };
      var groupHosts = {};
      ["panel", "dashboard"].forEach(function (group) {
        var members = homeDashboardItems.filter(function (d) { return (d.group || "dashboard") === group; });
        if (!members.length) return;
        var host = el("optgroup", { label: group === "panel"
          ? i18nText("configure.dashboard.group_ha", "Home Assistant dashboards")
          : i18nText("configure.dashboard.group_yours", "Your dashboards") });
        groupHosts[group] = host;
        dashboardSelect.appendChild(host);
        members.forEach(function (dashboard) {
          var path = String(dashboard && dashboard.path || "").trim();
          if (!path || dashboardPaths[path]) return;
          dashboardPaths[path] = true;
          var title = String(dashboard.title || "").trim() || path;
          var option = el("option", { value: path, text: title + " · " + path });
          if (path === currentDashboard) option.selected = true;
          host.appendChild(option);
        });
      });
      var customActive = !!currentDashboard && !dashboardPaths[currentDashboard];
      dashboardSelect.appendChild(el("option", { value: CUSTOM_DASHBOARD, text: i18nText("configure.dashboard.custom", "Custom — enter a dashboard path…") }));
      if (customActive) dashboardSelect.value = CUSTOM_DASHBOARD;
      var customInput = el("input", {
        type: "text", class: "hd-custom-input", value: currentDashboard,
        placeholder: "/dashboard-name/tab-name", id: "cfg-home_dashboard-path",
        maxlength: f.maxLength || 2048, "aria-label": i18nText("configure.dashboard.path_label", "Dashboard path"),
        "aria-describedby": "cfg-home_dashboard-path-note",
      });
      // The note explains what the value will DO (including the fallback warning), so it is wired to the
      // input for assistive technology and announced politely as it changes rather than only on focus.
      var customNote = el("small", { class: "hd-area-note hd-custom-note",
        id: "cfg-home_dashboard-path-note", role: "status", "aria-live": "polite" });
      var customWrap = el("div", { class: "hd-custom" }, [customInput, customNote]);
      // The renderer resolves an explicit path against the dashboards this account can see and falls
      // back to its default when the ROOT is not one of them — silently, by design. Once a path can be
      // typed that silence becomes the likeliest failure, so say it here instead. It stays a warning
      // and never blocks the save: the list may be unfetched, and the dashboard may not exist yet.
      function refreshCustomNote() {
        var typedPath = customInput.value.trim();
        var root = dashboardRootOf(typedPath);
        var unknown = root && homeDashboardQueried && homeDashboardItems.length && !dashboardPaths[root];
        customNote.textContent = unknown
          ? i18nText("configure.dashboard.path_not_visible", "{path} is not a dashboard this panel’s Home Assistant account can see — the panel will fall back to its default until that dashboard exists.", { path: root })
          : i18nText("configure.dashboard.path_help", "A path on this Home Assistant, starting with a dashboard from the list above.");
        customNote.classList.toggle("warn", !!unknown);
      }
      // Validity is decided here rather than by the browser's pattern engine, and is cleared entirely
      // whenever Custom is not the live control. A hidden control that stays invalid blocks Save with
      // nothing on screen to fix — reportValidity() cannot show anything on an invisible field — so an
      // abandoned malformed path would make every later Auto or listed save fail for no visible reason.
      function validateCustom() {
        var typedPath = customInput.value.trim();
        customInput.setCustomValidity(
          !typedPath || wellFormedDashboardPath(typedPath) ? ""
            : i18nText("configure.dashboard.path_invalid", "Enter a dashboard path such as /dashboard-name/tab-name."),
        );
      }
      function syncCustom() {
        var on = dashboardSelect.value === CUSTOM_DASHBOARD;
        customWrap.hidden = !on;
        // Required only while it is the live control, so an empty box cannot be saved as a silent Auto.
        customInput.required = on;
        // Disabled when it is not: barred from constraint validation, and skipped by the row scan.
        customInput.disabled = !on;
        if (on) { validateCustom(); refreshCustomNote(); }
      }
      dashboardSelect.addEventListener("change", function () {
        syncCustom();
        values[f.key] = dashboardSelect.value === CUSTOM_DASHBOARD ? customInput.value.trim() : dashboardSelect.value;
        setDirty(f.key);
        if (dashboardSelect.value === CUSTOM_DASHBOARD) customInput.focus();
      });
      customInput.addEventListener("input", function () {
        values[f.key] = customInput.value.trim();
        setDirty(f.key);
        validateCustom();
        refreshCustomNote();
      });
      syncCustom();
      var notes = [];
      if (!homeDashboardQueried) {
        notes.push(el("small", { class: "hd-area-note", text:
          i18nText("configure.dashboard.fetch_failed", "Couldn’t fetch this account’s dashboard list from Home Assistant yet. Try again after the connection recovers.") }));
      } else if (!homeDashboardItems.length) {
        notes.push(el("small", { class: "hd-area-note", text:
          i18nText("configure.dashboard.none_accessible", "This account cannot access any dashboards. Create one or grant access in Home Assistant.") }));
      } else if (!homeDashboardDefault.explicit && !currentDashboard) {
        // The demotion rule, in native terms: Auto still exists but the field says why picking a real
        // dashboard is the recommendation when the account carries no server-side default.
        notes.push(el("small", { class: "hd-area-note", text:
          i18nText("configure.dashboard.no_default", "This account has no default dashboard set — pick the dashboard this panel should show.") }));
      }
      // The select is never disabled now, even with no listed dashboards: Custom is still a legal
      // answer then, and it is exactly the case where someone needs to type a path by hand.
      return el("div", { class: "hd-picker" }, [dashboardSelect, customWrap].concat(notes));
    }
    // Home Assistant Area: a pop-up list of HA's REAL areas, never free text — nobody knows what to type,
    // the names live in HA. The stored value is the panel's REQUEST; Home Assistant's own value is
    // canonical, so this control shows what HA says and the note explains when an edit cannot move an
    // already-registered device (admin-only in HA).
    if (f.picker === "ha_area") {
      // Populated EAGERLY, when the field renders. It used to load on focus/pointerdown, which appended
      // options into a native dropdown the user had just opened — the picker re-laid-out on every insert
      // and visibly flickered before settling (hardware report). Nothing may mutate an open picker.
      var areaCurrent = v == null ? "" : v;
      var areaWrap = el("div", { class: "ha-area-picker" });
      var areaSelect = el("select", { class: "pkgsel", "aria-label": f.label });
      areaSelect.appendChild(el("option", { value: "", text: i18nText("configure.area.none", "No area") }));
      if (areaCurrent) {
        var cur = el("option", { value: areaCurrent, text: areaCurrent });
        cur.selected = true;
        areaSelect.appendChild(cur);
      }
      var areaNote = el("small", { class: "hd-area-note", hidden: "hidden" });
      var areaTouched = false;
      var areaHa = "";
      var areaQueried = false;
      var areaAdmin = null;
      // The note describes the VALUE, not permissions. Shown for every non-admin session it told a panel
      // whose value MATCHED Home Assistant that it had been overridden locally — untrue, and alarming on a
      // panel that had just converged correctly. It may appear only
      // while the local request genuinely differs from what Home Assistant holds, and it must stay honest
      // after an edit, so it is recomputed rather than decided once.
      function syncAreaNote() {
        var localArea = values[f.key] == null ? "" : String(values[f.key]);
        if (areaQueried && haAreaUserOverride && localArea !== areaHa) {
          // Name what Home Assistant actually holds so a local override remains distinguishable from
          // an adopted value at a glance.
          areaNote.textContent = areaHa
            ? i18nText("configure.area.local_override_ha", "Local override only — Home Assistant has “{area}”", { area: areaHa })
            : i18nText("configure.area.local_override", "Local override only");
          areaNote.removeAttribute("hidden");
        } else {
          areaNote.setAttribute("hidden", "hidden");
        }
      }
      // ONE insertion, never one per area: appending options individually re-lays-out the control on every
      // append. And when the catalog is already known this runs BEFORE the node is attached, so returning to
      // the Config tab costs no layout change at all — the picker is complete the moment it appears.
      function fillAreaOptions(a) {
        var have = {};
        for (var i = 0; i < areaSelect.options.length; i++) have[areaSelect.options[i].value] = true;
        var areaFrag = document.createDocumentFragment();
        (a && a.areas || []).forEach(function (area) {
          if (!area || !area.name || have[area.name]) return;
          have[area.name] = true;
          areaFrag.appendChild(el("option", { value: area.name, text: area.name }));
        });
        // Home Assistant is canonical: where it holds an area for this device, that is what this control
        // reads, even if the local request has never been set. A panel whose HA device sits in Office must
        // never present itself as having no area — reported on an upgraded panel whose local value was blank.
        var haArea = a && a.device && a.device.found ? a.device.area_name : "";
        if (haArea && !have[haArea]) {
          have[haArea] = true;
          areaFrag.appendChild(el("option", { value: haArea, text: haArea }));
        }
        areaSelect.appendChild(areaFrag); // the single layout-affecting mutation
        if (!areaTouched && !haAreaUserOverride && haArea && areaSelect.value !== haArea) {
          areaSelect.value = haArea;
          // Adopting HA's value is not a pending edit — the server already treats HA as the source of
          // truth — so this must not mark the form dirty and invite a redundant save.
          values[f.key] = haArea;
        }
        areaHa = haArea;
        areaQueried = !!(a && a.queried);
        areaAdmin = a && typeof a.admin === "boolean" ? a.admin : null;
        syncAreaNote();
      }
      var areaSeedGeneration = haAreaSeedGeneration;
      var areaRequest = ++haAreaCatalogRequest;
      if (haAreaSeed) fillAreaOptions(haAreaSeed);
      // The panel endpoint owns a short, owner-keyed cache. Always ask it so failed queries and config,
      // credential or identity changes can recover without a full browser reload.
      fetch("api/v1/config/ha-area", { cache: "no-store" }).then(function (r) { return r.json(); }).then(function (a) {
        if (areaSeedGeneration !== haAreaSeedGeneration || areaRequest !== haAreaCatalogRequest) return;
        if (a && a.queried) haAreaSeed = a;
        fillAreaOptions(a);
      }).catch(function () {});
      areaSelect.addEventListener("change", function () {
        areaTouched = true;
        values[f.key] = areaSelect.value;
        setDirty(f.key);
        syncAreaNote();
      });
      areaWrap.appendChild(areaSelect);
      areaWrap.appendChild(areaNote);
      return areaWrap;
    }
    // Home Assistant illuminance source: a searchable editable combobox. The listbox is ordinary page
    // DOM rather than a browser-owned datalist so it stays inside the browser viewport on multi-monitor
    // desktops. Free-form values remain valid when HA or its entity catalog is temporarily unavailable.
    if (f.picker === "ha_illuminance") {
      var current = v == null ? "" : v;
      var sourceReadyAtRender = ambientLightSourceReady();
      var listId = "ha-illuminance-listbox";
      var input = el("input", {
        type: "text", value: current, class: "ha-entity-input", role: "combobox",
        "aria-label": f.label, "aria-autocomplete": "list", "aria-expanded": "false",
        "aria-controls": listId, "aria-haspopup": "listbox",
        placeholder: ambientSourcePlaceholder(), autocomplete: "off", maxlength: f.maxLength || 255
      });
      var list = el("div", { id: listId, class: "ha-entity-listbox", role: "listbox" });
      list.hidden = true;
      var note = el("small", { class: "picker-note", text: i18nText("configure.brightness.focus_to_load", "Focus to load Home Assistant illuminance sensors.") });
      var picker = el("div", { class: "ha-entity-picker" }, [input, note]);
      var sourcePolls = 0;
      var activeIndex = -1;
      var renderedItems = [];
      var disposed = false;

      // The listbox is a fixed body-level portal: no card overflow or transformed ancestor can move it,
      // while it still inherits the page theme and remains part of the input's ARIA relationship.
      document.body.appendChild(list);

      function viewportSize() {
        var visual = window.visualViewport;
        var left = visual && visual.offsetLeft || 0;
        var top = visual && visual.offsetTop || 0;
        var width = visual && visual.width || window.innerWidth || document.documentElement.clientWidth;
        var height = visual && visual.height || window.innerHeight || document.documentElement.clientHeight;
        return {
          left: left, top: top, width: width, height: height,
          right: left + width, bottom: top + height
        };
      }
      function positionList() {
        if (disposed || list.hidden) return;
        var rect = input.getBoundingClientRect();
        var viewport = viewportSize();
        var edge = 8, gap = 4, preferredHeight = 280, preferredWidth = 520;
        var viewportCapacity = Math.max(0, viewport.width - edge * 2);
        var minimumWidth = Math.min(Math.max(rect.width, 240), viewportCapacity);
        var desiredWidth = Math.min(Math.max(rect.width, preferredWidth), viewportCapacity);
        // Stay aligned with the input when its right-hand side has room, widening only into that space.
        // On a narrow/right-edge layout, shift left only as much as needed to preserve the old minimum.
        var left = Math.max(viewport.left + edge, Math.min(rect.left, viewport.right - minimumWidth - edge));
        var width = Math.min(desiredWidth, Math.max(0, viewport.right - edge - left));
        if (width < minimumWidth) {
          width = minimumWidth;
          left = Math.max(viewport.left + edge, viewport.right - edge - width);
        }
        var below = Math.max(0, viewport.bottom - rect.bottom - gap - edge);
        var above = Math.max(0, rect.top - viewport.top - gap - edge);
        var opensAbove = below < 160 && above > below;
        var available = opensAbove ? above : below;
        var maxHeight = Math.min(preferredHeight, Math.max(72, available), Math.max(0, viewport.height - edge * 2));
        var desiredTop = opensAbove ? rect.top - gap - maxHeight : rect.bottom + gap;
        list.style.width = width + "px";
        list.style.maxHeight = maxHeight + "px";
        list.style.left = left + "px";
        list.style.top = Math.max(viewport.top + edge, Math.min(desiredTop, viewport.bottom - edge - maxHeight)) + "px";
      }
      function setActive(index) {
        if (!renderedItems.length) index = -1;
        else if (index < 0) index = renderedItems.length - 1;
        else if (index >= renderedItems.length) index = 0;
        activeIndex = index;
        Array.prototype.forEach.call(list.children, function (option, i) {
          option.classList.toggle("active", i === activeIndex);
          option.setAttribute("aria-selected", i === activeIndex ? "true" : "false");
        });
        if (activeIndex < 0) input.removeAttribute("aria-activedescendant");
        else {
          var active = list.children[activeIndex];
          input.setAttribute("aria-activedescendant", active.id);
          if (active.scrollIntoView) active.scrollIntoView({ block: "nearest" });
        }
      }
      function closeList() {
        list.hidden = true;
        input.setAttribute("aria-expanded", "false");
        input.removeAttribute("aria-activedescendant");
        activeIndex = -1;
      }
      function choose(index) {
        var item = renderedItems[index];
        if (!item) return;
        input.value = item.id;
        values[f.key] = item.id;
        setDirty(f.key);
        closeList();
        input.focus();
      }
      function openList() {
        if (disposed || !renderedItems.length) { closeList(); return; }
        list.hidden = false;
        input.setAttribute("aria-expanded", "true");
        positionList();
      }
      function populate() {
        list.innerHTML = "";
        renderedItems = [];
        activeIndex = -1;
        haSourceItems.forEach(function (item) {
          var id = item.entity_id || item.entityId || "";
          if (!id) return;
          var name = item.friendly_name || item.friendlyName || item.name || id;
          var unit = item.unit || item.unit_of_measurement || "lx";
          var index = renderedItems.length;
          renderedItems.push({ id: id, name: name, unit: unit });
          var option = el("div", {
            id: listId + "-option-" + index, class: "ha-entity-option", role: "option",
            "aria-selected": "false"
          }, [
            el("span", { class: "ha-entity-name", text: name }),
            el("small", { text: id + (unit ? " · " + unit : "") })
          ]);
          option.addEventListener("pointerdown", function (event) {
            // Keep input focus stable until selection completes (covers mouse, pen and touch).
            event.preventDefault();
            choose(index);
          });
          // Assistive technology and old WebViews may emit click without a preceding pointer event.
          option.addEventListener("click", function () {
            if (input.value !== id || !list.hidden) choose(index);
          });
          list.appendChild(option);
        });
        if (haSourceItems.length) note.textContent = i18nText("configure.brightness.sources_available", "{count} illuminance source(s) available. Blank uses the panel sensor.", { count: haSourceItems.length });
        if (document.activeElement === input) openList();
        else closeList();
      }
      function loadSources(query) {
        var request = ++haSourceRequest;
        note.textContent = i18nText("configure.brightness.sources_loading", "Loading Home Assistant illuminance sensors…");
        fetch("api/v1/auto-brightness/sources?q=" + encodeURIComponent(query || "") + "&limit=200")
          .then(function (r) { if (!r.ok) throw r.status; return r.json(); })
          .then(function (body) {
            if (request !== haSourceRequest) return;
            haSourceItems = (body && (body.items || body.candidates)) || [];
            populate();
            if (!body || body.available === false) {
              note.textContent = i18nText("configure.brightness.sources_unavailable", "Home Assistant sources are unavailable; an exact sensor entity id can still be entered.");
            } else if (!haSourceItems.length) {
              if (body.refreshing === true && sourcePolls < 20) {
                sourcePolls++;
                note.textContent = i18nText("configure.brightness.sources_loading", "Loading Home Assistant illuminance sensors…");
                setTimeout(function () { if (request === haSourceRequest) loadSources(query); }, 500);
              } else {
                note.textContent = i18nText("configure.brightness.sources_none", "No Home Assistant illuminance sensors found; an exact sensor entity id can still be entered.");
              }
            }
          })
          .catch(function () {
            if (request !== haSourceRequest) return;
            note.textContent = i18nText("configure.brightness.sources_load_failed", "Could not load Home Assistant sources; an exact sensor entity id can still be entered.");
          });
      }
      input.addEventListener("focus", function () {
        if (!haSourceItems.length) loadSources(input.value.trim());
        else openList();
      });
      input.addEventListener("input", function () {
        values[f.key] = input.value; setDirty(f.key);
        closeList();
        if (haSourceTimer) clearTimeout(haSourceTimer);
        var query = input.value.trim();
        haSourceTimer = setTimeout(function () { loadSources(query); }, query.length >= 2 ? 250 : 500);
      });
      input.addEventListener("keydown", function (event) {
        if (event.key === "ArrowDown" || event.key === "ArrowUp") {
          if (!renderedItems.length) return;
          event.preventDefault();
          openList();
          setActive(activeIndex + (event.key === "ArrowDown" ? 1 : -1));
        } else if (event.key === "Enter" && !list.hidden && activeIndex >= 0) {
          event.preventDefault(); choose(activeIndex);
        } else if (event.key === "Escape" && !list.hidden) {
          event.preventDefault(); closeList();
        } else if (event.key === "Tab") closeList();
      });
      input.addEventListener("blur", function () {
        setTimeout(function () {
          if (disposed || document.activeElement === input) return;
          closeList();
          if (sourceReadyAtRender !== ambientLightSourceReady()) render();
        }, 0);
      });
      function outsidePointer(event) {
        if (event.target !== input && !list.contains(event.target)) closeList();
      }
      function reposition() { positionList(); }
      document.addEventListener("pointerdown", outsidePointer, true);
      window.addEventListener("resize", reposition);
      window.addEventListener("scroll", reposition, true);
      if (window.visualViewport) {
        window.visualViewport.addEventListener("resize", reposition);
        window.visualViewport.addEventListener("scroll", reposition);
      }
      var cleanup = function () {
        if (disposed) return;
        disposed = true;
        haSourceRequest++; // invalidate fetches and catalog-refresh polls owned by this render
        if (haSourceTimer) { clearTimeout(haSourceTimer); haSourceTimer = null; }
        document.removeEventListener("pointerdown", outsidePointer, true);
        window.removeEventListener("resize", reposition);
        window.removeEventListener("scroll", reposition, true);
        if (window.visualViewport) {
          window.visualViewport.removeEventListener("resize", reposition);
          window.visualViewport.removeEventListener("scroll", reposition);
        }
        if (list.parentNode) list.parentNode.removeChild(list);
      };
      haPickerCleanups.push(cleanup);
      populate();
      return picker;
    }
    var type = f.type === "PASSWORD" ? "password" : (f.type === "INT" || f.type === "FLOAT") ? "number" : "text";
    var inp = el("input", { type: type, value: f.secret ? "" : (v == null ? "" : v) });
    if (f.secret) inp.placeholder = i18nText("configure.secret.blank_keeps_current", "blank keeps current");
    else if (f.placeholder) inp.placeholder = f.placeholder;   // e.g. "auto (io.homeassistant…)" on package fields
    if (f.min != null) inp.min = f.min;
    if (f.max != null) inp.max = f.max;
    if (f.maxLength != null) inp.maxLength = f.maxLength;
    if (f.step != null) inp.step = f.step;
    if (f.type === "FLOAT" && f.step == null) inp.step = "any";
    if ((f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_response_percent") && !ambientLightSourceReady()) {
      inp.disabled = true;
      inp.title = i18nText("configure.brightness.select_source_first", "Select an ambient light source first.");
    }
    inp.addEventListener("input", function () {
      values[f.key] = inp.value; setDirty(f.key);
      if (f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_response_percent") queueAutoBrightnessHistory();
    });
    return inp;
  }

  function pointValue(point, names) {
    for (var i = 0; i < names.length; i++) {
      var value = point[names[i]];
      if (typeof value === "number" && isFinite(value)) return value;
    }
    return null;
  }

  function normalizedChartPoints() {
    var raw = autoBrightHistory && (autoBrightHistory.points || autoBrightHistory.items) || [];
    return raw.map(function (point) {
      return {
        minute: pointValue(point, ["minute", "epoch_minute", "epochMinute"]),
        localDay: point.local_day || point.localDay || "",
        minuteOfDay: pointValue(point, ["minute_of_day", "minuteOfDay"]),
        dayAge: pointValue(point, ["day_age", "dayAge"]),
        mean: pointValue(point, ["mean_lux", "observed_mean_lux", "observedMeanLux", "lux"]),
        min: pointValue(point, ["min_lux", "minLux"]),
        max: pointValue(point, ["max_lux", "maxLux"]),
        expected: pointValue(point, ["baseline_lux", "expected_lux", "expectedLux"]),
        brightness: pointValue(point, ["proposed_brightness", "proposedBrightness"])
      };
    }).filter(function (point) {
      return point.minute != null && point.mean != null && point.localDay &&
        point.minuteOfDay != null && point.dayAge != null && point.dayAge >= 0 && point.dayAge < 7;
    });
  }

  function autoBrightnessChartDays(points) {
    var grouped = {};
    points.forEach(function (point) {
      if (!grouped[point.localDay]) grouped[point.localDay] = { key: point.localDay, age: point.dayAge, points: [] };
      grouped[point.localDay].points.push(point);
    });
    return Object.keys(grouped).map(function (key) {
      grouped[key].points.sort(function (a, b) { return a.minute - b.minute; });
      return grouped[key];
    }).sort(function (a, b) { return b.age - a.age; });
  }

  // Age hierarchy for the days that are drawn individually.
  // Every dimension uses arithmetic and core canvas state rather than an optional filter effect, so
  // the intended softening remains present when an engine omits that effect.
  //
  // The blur is required — it is what makes a previous day read as background rather than as another
  // competing line. What it must NOT use is the `filter` property on the 2D context, which is how this
  // was first written: Chromium and the panel WebView applied it while Safari showed no softening, so
  // the same history rendered as two different charts. That difference is silent, which is the real
  // damage: the radius was tuned in a browser that was showing no effect and reached 52px at day-age 6
  // without anyone ever seeing the result. A capability probe is not a fix either — skipping the call
  // leaves the day unsoftened rather than giving it a fallback. So the radius below is real and the
  // blur is genuinely drawn; see the feathered stroke in line() for how it is realised portably.
  // How many days are drawn as lines. The rest of the week survives as the painted min/max region.
  //
  // Seven days times three traces was twenty-one lines, and adjacent days could not be told apart at
  // any combination of opacity, blur and colour. That is not a tuning failure: seven ordered steps do
  // not fit between today's saturated colour and the dimmest step still visible on the card, so the
  // encoding was being asked for more levels than the space holds. Three days fit comfortably, and the
  // week's spread is better served by one region than by four more lines nobody could separate.
  function autoBrightnessLineDays() { return 3; }

  // Age hierarchy for those three days. Every dimension uses arithmetic and core canvas state, so the
  // encoding does not disappear when an engine omits an optional drawing effect.
  //
  // The blur is required — it is what makes a previous day read as background rather than as another
  // competing line. What it must NOT use is the `filter` property on the 2D context, which is how this
  // was first written: Chromium and the panel WebView applied it while Safari showed no softening, so
  // the same history rendered as two different charts. That difference is silent, which is the real
  // damage: the radius was tuned in a browser that was showing no effect and reached 52px at day-age 6
  // without anyone ever seeing the result. A capability probe is not a fix either — skipping the call
  // leaves the day unsoftened rather than giving it a fallback. So the radius below is real and the
  // blur is genuinely drawn; see the feathered stroke in line() for how it is realised portably.
  //
  // Opacity is deliberately NOT part of the ramp. Ink conservation already dims a blurred trace by
  // roughly the ratio of its spread to its width, so an extra alpha cut on top bleached the older days
  // until they read as grey. Age is carried by radius and width alone, with chroma compensating.
  function autoBrightnessDayStyle(age) {
    var boundedAge = Math.max(0, Math.min(autoBrightnessLineDays() - 1, age));
    return {
      blurPx: [0, 1.2, 3.2][boundedAge],
      widthPx: [1.8, 1.5, 1.3][boundedAge]
    };
  }

  // The blur's cross-section, as [fraction of the radius, share of the layer's opacity], widest first.
  // Held in its own function so the whole age encoding can be swapped in one place when tuning against
  // real renderers — the comparison harness overrides this and autoBrightnessDayStyle and nothing else.
  // Weighted toward the core. Ink is conserved, so a flat profile spends most of it on the widest,
  // faintest pass and the peak opacity collapses — at radius 3 the spread is over five times the line
  // width, which puts the core near 5% alpha. A saturated colour at 5% over a near-black card composites
  // to almost the background, so the trace goes grey and loses its hue entirely. Concentrating the ink
  // in the middle keeps a coloured core with a soft edge, which is what a blurred line should look like.
  function autoBrightnessFeather() {
    return [[1, .06], [.72, .12], [.4, .22], [0, .6]];
  }

  // Compensates a blurred trace's colour. A faint mark loses its hue toward whatever is behind it, and
  // ink conservation makes a heavily blurred day faint by construction — so the more a day is blurred,
  // the more chroma its source colour needs in order to still read as blue, amber or purple rather than
  // as grey. Pushes the colour away from its own luminance grey; clamping means this can only ever
  // saturate, never wash toward white, which is what made an earlier light-to-dark ramp unusable.
  // Pure arithmetic on purpose — the CSS colour-mixing functions and relative colour syntax have
  // uneven engine support, and canvas takes a plain colour string anyway.
  function autoBrightnessAgedColor(color, blurPx) {
    var amount = 1 + blurPx * .13;
    var channels = [parseInt(color.slice(1, 3), 16), parseInt(color.slice(3, 5), 16), parseInt(color.slice(5, 7), 16)];
    var grey = .299 * channels[0] + .587 * channels[1] + .114 * channels[2];
    return "rgb(" + channels.map(function (channel) {
      return Math.max(0, Math.min(255, Math.round(grey + (channel - grey) * amount)));
    }).join(",") + ")";
  }

  function autoBrightnessSmoothing(age) {
    var boundedAge = Math.max(0, Math.min(6, Math.round(age)));
    return {
      medianWindow: [1, 3, 5, 7, 9, 11, 13][boundedAge],
      averageWindow: [1, 1, 3, 5, 7, 9, 11][boundedAge]
    };
  }

  function centeredMedian(values, windowSize) {
    if (windowSize <= 1) return values.slice();
    var radius = Math.floor(windowSize / 2);
    return values.map(function (_value, index) {
      var sample = values.slice(Math.max(0, index - radius), Math.min(values.length, index + radius + 1));
      sample.sort(function (a, b) { return a - b; });
      return sample[Math.floor(sample.length / 2)];
    });
  }

  function centeredWeightedAverage(values, windowSize) {
    if (windowSize <= 1) return values.slice();
    var radius = Math.floor(windowSize / 2);
    return values.map(function (_value, index) {
      var total = 0, weightTotal = 0;
      for (var offset = -radius; offset <= radius; offset += 1) {
        var sampleIndex = index + offset;
        if (sampleIndex < 0 || sampleIndex >= values.length) continue;
        var weight = radius + 1 - Math.abs(offset);
        total += values[sampleIndex] * weight;
        weightTotal += weight;
      }
      return weightTotal ? total / weightTotal : values[index];
    });
  }

  function smoothedRunValues(run, field, age, fallbackField) {
    var smoothing = autoBrightnessSmoothing(age);
    var values = run.map(function (point) {
      return point[field] == null && fallbackField ? point[fallbackField] : point[field];
    });
    return centeredWeightedAverage(centeredMedian(values, smoothing.medianWindow), smoothing.averageWindow);
  }

  function drawAutoBrightnessChart() {
    var canvas = document.getElementById("auto-brightness-chart");
    if (!canvas) return;
    var points = normalizedChartPoints();
    var width = Math.max(280, canvas.clientWidth || 0), height = 190;
    var ratio = Math.min(2, window.devicePixelRatio || 1);
    canvas.width = Math.round(width * ratio); canvas.height = Math.round(height * ratio);
    var ctx = canvas.getContext("2d"); ctx.scale(ratio, ratio);
    ctx.clearRect(0, 0, width, height);
    if (!points.length) {
      ctx.fillStyle = "#888"; ctx.font = "13px sans-serif"; ctx.textAlign = "center";
      ctx.fillText(i18nText("configure.brightness.history_none", "No ambient-light history yet"), width / 2, height / 2);
      return;
    }
    var days = autoBrightnessChartDays(points);
    var bucketMinutes = autoBrightHistory && (autoBrightHistory.bucket_minutes || autoBrightHistory.bucketMinutes) || 5;
    var pad = { left: 0, right: 0, top: 10, bottom: 24 };
    var plotW = width - pad.left - pad.right, plotH = height - pad.top - pad.bottom;
    var maxLux = 1;
    points.forEach(function (p) { maxLux = Math.max(maxLux, p.max == null ? p.mean : p.max, p.expected || 0); });
    var maxLog = Math.log(maxLux + 1);
    function x(p) { return pad.left + p.minuteOfDay / 1440 * plotW; }
    function y(value) { return pad.top + plotH - Math.log(Math.max(0, value) + 1) / maxLog * plotH; }
    function yBrightness(value) { return pad.top + plotH - Math.max(0, Math.min(255, value)) / 255 * plotH; }
    ctx.strokeStyle = "#343a44"; ctx.lineWidth = 1;
    [0, .5, 1].forEach(function (f) {
      var yy = pad.top + plotH * f; ctx.beginPath(); ctx.moveTo(pad.left, yy); ctx.lineTo(width - pad.right, yy); ctx.stroke();
    });
    [0, 360, 720, 1080, 1440].forEach(function (minute) {
      var xx = pad.left + minute / 1440 * plotW;
      ctx.beginPath(); ctx.moveTo(xx, pad.top); ctx.lineTo(xx, pad.top + plotH); ctx.stroke();
    });
    function runs(dayPoints, field) {
      var result = [], run = [];
      dayPoints.forEach(function (point) {
        var previous = run.length ? run[run.length - 1] : null;
        var discontinuous = previous && (
          point.minute - previous.minute !== bucketMinutes ||
          point.minuteOfDay - previous.minuteOfDay !== bucketMinutes
        );
        if (point[field] == null || discontinuous) {
          if (run.length) result.push(run);
          run = [];
        }
        if (point[field] != null) run.push(point);
      });
      if (run.length) result.push(run);
      return result;
    }
    // Draws one trace, optionally blurred. The blur is a feathered stroke: the same path is stroked
    // several times, from widthPx + 2 * blurPx down to widthPx, each pass at a fraction of the layer's
    // opacity. Those passes composite into a smooth-edged band with no hard core, which is what a
    // blurred line looks like — a stroke widened by 2r and falling off toward its edges IS the line
    // convolved with a radius-r kernel, to the accuracy this chart needs.
    //
    // It is done this way rather than with a canvas filter because stroking and globalAlpha are widely
    // implemented core 2D operations, so every target receives the same visual encoding even though
    // rasterisation details can differ. It is also cheap: a handful of extra strokes per trace, no pixel
    // readback, no offscreen buffer, nothing to allocate per frame.
    function line(day, field, color, widthPx, projectY, blurPx) {
      var featherPasses = autoBrightnessFeather();
      runs(day.points, field).forEach(function (run) {
        var values = smoothedRunValues(run, field, day.age);
        ctx.beginPath();
        run.forEach(function (point, index) {
          if (!index) ctx.moveTo(x(point), projectY(values[index])); else ctx.lineTo(x(point), projectY(values[index]));
        });
        ctx.strokeStyle = color;
        if (!blurPx) { ctx.lineWidth = widthPx; ctx.stroke(); return; }
        var layerAlpha = ctx.globalAlpha;
        // Conserve ink. A blur SPREADS a line's brightness over a wider area; it must never add more.
        // Without this the passes simply stack, so a blurred trace lights more pixels than the sharp one
        // and reads as bolder and glowing rather than smudged — the opposite of receding into the
        // background. Scaling the opacities by width/spread-width keeps the total roughly equal to the
        // unblurred stroke, so more blur automatically means more transparency.
        // Approximate on purpose: alpha compositing is not additive, so overlapping passes retain a
        // little more than this predicts. Close enough that blur reads as softening, not highlighting.
        var spreadInk = 0;
        featherPasses.forEach(function (pass) { spreadInk += (widthPx + 2 * blurPx * pass[0]) * pass[1]; });
        var conserve = spreadInk > 0 ? widthPx / spreadInk : 1;
        featherPasses.forEach(function (pass) {
          ctx.globalAlpha = layerAlpha * pass[1] * conserve;
          ctx.lineWidth = widthPx + 2 * blurPx * pass[0];
          ctx.stroke();
        });
        ctx.globalAlpha = layerAlpha;
      });
    }
    // The whole week's spread, as one painted region between the lowest and highest reading seen at each
    // time of day. Painted FIRST so the day lines sit on top of it, and tinted with the lux hue rather
    // than a neutral: the region IS the lux range, and a grey fill reads as haze over the background
    // instead of as something deliberate. It carries every day, including the ones drawn as lines, so
    // the days beyond the third are represented here rather than dropped.
    var lowest = {}, highest = {};
    points.forEach(function (point) {
      var slot = point.minuteOfDay;
      var low = point.min == null ? point.mean : point.min;
      var high = point.max == null ? point.mean : point.max;
      if (lowest[slot] == null || low < lowest[slot]) lowest[slot] = low;
      if (highest[slot] == null || high > highest[slot]) highest[slot] = high;
    });
    // Split on missing data exactly as the day traces do. A single polygon over every sampled slot
    // would bridge periods the week has no observations for, painting a filled region across a gap and
    // asserting a range that was never measured — the same defect the run-splitting in runs() exists to
    // prevent for lines, and more misleading here because a fill looks like coverage.
    var slots = Object.keys(lowest).map(Number).sort(function (a, b) { return a - b; });
    var spans = [], span = [];
    slots.forEach(function (slot) {
      if (span.length && slot - span[span.length - 1] !== bucketMinutes) { spans.push(span); span = []; }
      span.push(slot);
    });
    if (span.length) spans.push(span);
    ctx.fillStyle = "rgba(74,158,255,.15)";
    spans.forEach(function (run) {
      if (run.length < 2) return;
      ctx.beginPath();
      run.forEach(function (slot, index) {
        var yy = y(highest[slot]);
        if (!index) ctx.moveTo(pad.left + slot / 1440 * plotW, yy); else ctx.lineTo(pad.left + slot / 1440 * plotW, yy);
      });
      run.slice().reverse().forEach(function (slot) {
        ctx.lineTo(pad.left + slot / 1440 * plotW, y(lowest[slot]));
      });
      ctx.closePath(); ctx.fill();
    });
    // Oldest of the three first, so today is stroked last and stays on top of its neighbours.
    days.filter(function (day) { return day.age < autoBrightnessLineDays(); }).forEach(function (day) {
      var style = autoBrightnessDayStyle(day.age);
      ctx.save();
      line(day, "mean", autoBrightnessAgedColor("#4a9eff", style.blurPx), style.widthPx, y, style.blurPx);
      line(day, "expected", autoBrightnessAgedColor("#f1bd52", style.blurPx), style.widthPx, y, style.blurPx);
      line(day, "brightness", autoBrightnessAgedColor("#b77cff", style.blurPx), style.widthPx, yBrightness, style.blurPx);
      ctx.restore();
    });
    ctx.fillStyle = "#888"; ctx.font = "11px sans-serif";
    ctx.textAlign = "left"; ctx.fillText(Math.round(maxLux) + " lx", pad.left + 5, pad.top + 12); ctx.fillText("0", pad.left + 5, pad.top + plotH - 4);
    ctx.textAlign = "right"; ctx.fillText("100%", width - pad.right - 5, pad.top + 12); ctx.fillText("0%", width - pad.right - 5, pad.top + plotH - 4);
    [0, 360, 720, 1080, 1440].forEach(function (minute, index) {
      var xx = pad.left + minute / 1440 * plotW;
      ctx.textAlign = index === 0 ? "left" : index === 4 ? "right" : "center";
      ctx.fillText(index === 4 ? "24:00" : ("0" + Math.floor(minute / 60)).slice(-2) + ":00", xx, height - 6);
    });
  }

  function autoBrightnessSummary() {
    if (autoBrightSourceTransition) return i18nText("configure.brightness.source_updating", "Updating ambient-light source… · Source: {source}", { source: autoBrightnessSelectedSource() });
    if (!autoBrightStatus) return autoBrightLoading ? i18nText("configure.brightness.loading", "Loading adaptive brightness…") : i18nText("configure.brightness.status_unavailable", "Adaptive brightness status is unavailable.");
    if (autoBrightStatus.available === false) return i18nText("configure.brightness.runtime_unavailable", "Adaptive brightness runtime is unavailable.");
    if (autoBrightStatus.sourceAvailable === false || autoBrightStatus.source_available === false) {
      return i18nText("configure.brightness.waiting", "Waiting for ambient light… · Source: {source}", { source: autoBrightnessSelectedSource() });
    }
    var state = autoBrightStatus.state || "learning";
    if (autoBrightStatus.preferenceActive === true || autoBrightStatus.paused === true) state = "temporary preference";
    else if (state === "enabled" && autoBrightStatus.mode) state = autoBrightStatus.mode;
    var stateLabels = {
      learning: i18nText("configure.brightness.state_learning", "learning"),
      "temporary preference": i18nText("configure.brightness.state_temporary_preference", "temporary preference"),
      enabled: i18nText("configure.brightness.state_enabled", "enabled"),
      disabled: i18nText("configure.brightness.state_disabled", "disabled")
    };
    state = stateLabels[state] || state;
    return i18nText("configure.brightness.state_source", "{state} · Source: {source}", { state: state, source: autoBrightnessSelectedSource() });
  }

  function autoBrightnessSelectedSource() {
    if (autoBrightSourceTransition && autoBrightTransitionSource) return autoBrightTransitionSource === "panel sensor"
      ? i18nText("configure.brightness.panel_sensor_short", "panel sensor") : autoBrightTransitionSource;
    var selected = String(values.auto_brightness_ha_entity || "").trim();
    if (selected) return selected;
    return autoBrightStatus && (
      autoBrightStatus.source_label || autoBrightStatus.sourceLabel || autoBrightStatus.entity_id || autoBrightStatus.entityId
    ) || i18nText("configure.brightness.panel_sensor_short", "panel sensor");
  }

  function autoBrightnessSourceRevision(body) {
    if (!body) return null;
    var revision = body.sourceRevision != null ? body.sourceRevision : body.source_revision;
    return revision == null ? null : String(revision);
  }

  function autoBrightnessStatusMatchesSelection(status) {
    var expected = autoBrightSourceTransition
      ? (autoBrightTransitionSource === "panel sensor" ? "" : autoBrightTransitionSource)
      : String(values.auto_brightness_ha_entity || "").trim();
    var actual = status && (status.entityId != null ? status.entityId : status.entity_id);
    actual = actual == null ? "" : String(actual).trim();
    return actual === expected;
  }

  function autoBrightnessLatestEpochMinute() {
    if (!autoBrightHistory) return null;
    var value = autoBrightHistory.latestEpochMinute != null
      ? autoBrightHistory.latestEpochMinute : autoBrightHistory.latest_epoch_minute;
    return typeof value === "number" && isFinite(value) ? value : null;
  }

  function autoBrightnessFreshness() {
    var latest = autoBrightnessLatestEpochMinute();
    if (latest == null) return autoBrightSourceTransition ? i18nText("configure.brightness.new_history_loading", "Loading new source history…") : i18nText("configure.brightness.samples_none", "No samples yet");
    var date = new Date(latest * 60000);
    var ageMinutes = Math.max(0, Math.floor((Date.now() - date.getTime()) / 60000));
    var relative = ageMinutes < 1 ? i18nText("configure.time.just_now", "just now")
      : ageMinutes < 60 ? i18nText("configure.time.minutes_ago", "{count} min ago", { count: ageMinutes })
      : ageMinutes < 1440 ? i18nText("configure.time.hours_ago", "{count} hr ago", { count: Math.floor(ageMinutes / 60) })
      : i18nText("configure.time.days_ago", "{count} days ago", { count: Math.floor(ageMinutes / 1440) });
    return i18nText("configure.time.updated", "Updated {date} ({relative})", { date: date.toLocaleString(), relative: relative });
  }

  // Preview bounds come from /api/v1/config/schema, never from literals here. A copy in this file
  // disagrees with the server the moment either bound moves, and the failure is silent: an in-range
  // value outside the stale copy loses its query parameter, so the chart quietly projects the STORED
  // value while the operator believes they are previewing the one they just typed. That is exactly
  // what a hard-coded 95 did once the ceiling rose. A value outside the server's own bounds is still
  // omitted rather than sent — it would only earn a 400 — and the control is already invalid, so the
  // save gate reports it.
  function autoBrightnessPreviewSuffix(param, key) {
    var field = null;
    for (var i = 0; i < schema.length; i++) if (schema[i].key === key) { field = schema[i]; break; }
    var value = parseInt(values[key], 10);
    if (!isFinite(value) || !field || field.min == null || field.max == null) return "";
    return value < field.min || value > field.max ? "" : param + encodeURIComponent(value);
  }

  function autoBrightnessProjectionSensitivity() {
    if (!autoBrightHistory) return null;
    var value = parseInt(autoBrightHistory.sensitivity, 10);
    return isFinite(value) && value >= 0 && value <= 100 ? value : null;
  }

  function beginAutoBrightnessSourceTransition(source, preserveCurrentRequest) {
    // A save can overtake an already-running refresh. Fence that response before it can
    // make a matching pair from the previous source look current.
    if (!preserveCurrentRequest) autoBrightRequest += 1;
    autoBrightSourceTransition = true;
    autoBrightTransitionAttempts = 0;
    autoBrightTransitionSource = String(source || "").trim() || "panel sensor";
    autoBrightHistory = { points: [] };
    autoBrightMessage = i18nText("configure.brightness.selected_history_loading", "Loading history for the selected source…");
    if (autoBrightTransitionTimer) clearTimeout(autoBrightTransitionTimer);
    autoBrightTransitionTimer = null;
    if (autoBrightRefreshTimer) clearTimeout(autoBrightRefreshTimer);
    autoBrightRefreshTimer = null;
    render();
  }

  function finishAutoBrightnessSourceTransition() {
    autoBrightSourceTransition = false;
    autoBrightTransitionAttempts = 0;
    autoBrightTransitionSource = "";
    if (autoBrightTransitionTimer) clearTimeout(autoBrightTransitionTimer);
    autoBrightTransitionTimer = null;
  }

  function scheduleAutoBrightnessTransitionPoll() {
    if (autoBrightTransitionTimer) clearTimeout(autoBrightTransitionTimer);
    autoBrightTransitionTimer = null;
    if (!autoBrightSourceTransition || document.hidden) return;
    autoBrightTransitionTimer = setTimeout(function () {
      autoBrightTransitionTimer = null;
      autoBrightTransitionAttempts += 1;
      loadAutoBrightnessData(true);
    }, AUTO_BRIGHTNESS_TRANSITION_POLL_MS);
  }

  function loadAutoBrightnessData(force) {
    if (autoBrightLoading && !force) return;
    autoBrightLoading = true;
    var request = ++autoBrightRequest;
    var sensitivitySuffix = autoBrightnessPreviewSuffix("&sensitivity=", "auto_brightness_response_percent");
    var minimumSuffix = autoBrightnessPreviewSuffix("&minimum_percent=", "auto_brightness_minimum_percent");
    var succeeded = false;
    Promise.all([
      fetch("api/v1/auto-brightness", { cache: "no-store" }).then(function (r) { if (!r.ok) throw r.status; return r.json(); }),
      fetch("api/v1/auto-brightness/history?hours=168" + sensitivitySuffix + minimumSuffix, { cache: "no-store" }).then(function (r) { if (!r.ok) throw r.status; return r.json(); })
    ]).then(function (result) {
      if (request !== autoBrightRequest) return;
      var statusRevision = autoBrightnessSourceRevision(result[0]);
      var historyRevision = autoBrightnessSourceRevision(result[1]);
      var revisionsMatch = statusRevision != null && statusRevision === historyRevision &&
        autoBrightnessStatusMatchesSelection(result[0]);
      var latest = result[1].latestEpochMinute != null ? result[1].latestEpochMinute : result[1].latest_epoch_minute;
      autoBrightStatus = result[0];
      succeeded = true;
      if (!revisionsMatch) {
        if (!autoBrightSourceTransition) beginAutoBrightnessSourceTransition(autoBrightnessSelectedSource(), true);
        autoBrightHistory = { points: [] };
        if (autoBrightTransitionAttempts >= AUTO_BRIGHTNESS_TRANSITION_MAX_ATTEMPTS) {
          finishAutoBrightnessSourceTransition();
          autoBrightMessage = i18nText("configure.brightness.history_preparing", "History is still preparing for the selected source; it will retry on the normal refresh.");
        } else {
          autoBrightMessage = i18nText("configure.brightness.selected_history_loading", "Loading history for the selected source…");
          scheduleAutoBrightnessTransitionPoll();
        }
      } else if (autoBrightSourceTransition && latest == null && autoBrightTransitionAttempts < AUTO_BRIGHTNESS_TRANSITION_MAX_ATTEMPTS) {
        autoBrightHistory = { points: [], sourceRevision: result[1].sourceRevision };
        autoBrightMessage = i18nText("configure.brightness.first_sample_waiting", "Waiting for the first sample from the selected source…");
        scheduleAutoBrightnessTransitionPoll();
      } else {
        autoBrightHistory = result[1];
        if (autoBrightSourceTransition) {
          var timedOutEmpty = latest == null;
          finishAutoBrightnessSourceTransition();
          autoBrightMessage = timedOutEmpty ? i18nText("configure.brightness.selected_samples_none", "The selected source has no ambient-light samples yet.") : "";
        } else autoBrightMessage = "";
      }
    }).catch(function () {
      if (request !== autoBrightRequest) return;
      if (typeof window !== "undefined" && window.configCardSizeGeometryInvalid) window.configCardSizeGeometryInvalid();
      if (!autoBrightSourceTransition) autoBrightStatus = { available: false, detail: i18nText("configure.brightness.load_failed", "Could not load adaptive brightness.") };
      autoBrightHistory = { points: [] };
      if (autoBrightSourceTransition && autoBrightTransitionAttempts < AUTO_BRIGHTNESS_TRANSITION_MAX_ATTEMPTS) {
        autoBrightMessage = i18nText("configure.brightness.selected_source_loading", "The selected source is still loading…");
        scheduleAutoBrightnessTransitionPoll();
      } else if (autoBrightSourceTransition) {
        finishAutoBrightnessSourceTransition();
        autoBrightMessage = i18nText("configure.brightness.selected_source_failed", "The selected source could not be loaded; it will retry on the normal refresh.");
      }
    }).then(function () {
      if (request !== autoBrightRequest) return;
      autoBrightLoading = false; render();
      if (succeeded && typeof window !== "undefined" && window.configCardSizeSourceReady) {
        window.configCardSizeSourceReady("brightness");
        if (window.configCardSizeGeometryChanged) window.configCardSizeGeometryChanged();
      }
      scheduleAutoBrightnessRefresh();
    });
  }

  function scheduleAutoBrightnessRefresh() {
    if (autoBrightRefreshTimer) clearTimeout(autoBrightRefreshTimer);
    autoBrightRefreshTimer = null;
    if (autoBrightSourceTransition) return;
    if (values.auto_brightness !== "true" || document.hidden) return;
    autoBrightRefreshTimer = setTimeout(function () {
      autoBrightRefreshTimer = null;
      if (document.hidden) return;
      loadAutoBrightnessData(false);
    }, AUTO_BRIGHTNESS_REFRESH_MS);
  }

  if (document.addEventListener) {
    document.addEventListener("visibilitychange", function () {
      if (document.hidden) {
        if (autoBrightRefreshTimer) clearTimeout(autoBrightRefreshTimer);
        autoBrightRefreshTimer = null;
        if (autoBrightTransitionTimer) clearTimeout(autoBrightTransitionTimer);
        autoBrightTransitionTimer = null;
      } else if (values.auto_brightness === "true") {
        loadAutoBrightnessData(false);
      }
    });
  }

  function queueAutoBrightnessHistory() {
    if (autoBrightHistoryTimer) clearTimeout(autoBrightHistoryTimer);
    autoBrightHistoryTimer = setTimeout(function () { loadAutoBrightnessData(true); }, 300);
  }

  var autoBrightnessResizeTimer = null;
  if (window.addEventListener) {
    window.addEventListener("resize", function () {
      if (autoBrightnessResizeTimer) clearTimeout(autoBrightnessResizeTimer);
      autoBrightnessResizeTimer = setTimeout(drawAutoBrightnessChart, 100);
    });
  }

  function runAutoBrightnessAction(path, button) {
    button.disabled = true; autoBrightMessage = i18nText("configure.action.working", "Working…"); render();
    fetch(path, { method: "POST", headers: { "Accept": "application/json" } })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (body) { if (!r.ok) throw (body.error || ("HTTP " + r.status)); return body; }); })
      .then(function () { autoBrightMessage = i18nText("configure.action.updated", "Updated."); loadAutoBrightnessData(true); })
      .catch(function (error) { autoBrightMessage = i18nText("configure.action.failed", "Action failed ({error}).", { error: error }); button.disabled = false; render(); });
  }

  function autoBrightnessPanel() {
    var available = autoBrightStatus && autoBrightStatus.available !== false;
    var paused = autoBrightStatus && (
      autoBrightStatus.preferenceActive === true || autoBrightStatus.paused === true || autoBrightStatus.state === "paused"
    );
    var reset = el("button", { class: "pbtn", type: "button", text: i18nText("configure.brightness.reset_history", "Reset learned history") });
    var resume = el("button", { class: "pbtn", type: "button", text: i18nText("configure.brightness.resume_auto", "Resume full auto") });
    reset.disabled = !available || autoBrightLoading;
    resume.disabled = !available || !paused || autoBrightLoading;
    reset.onclick = function () {
      if (confirm(i18nText("configure.brightness.reset_confirm", "Delete the seven-day ambient-light history and restart learning?"))) runAutoBrightnessAction("api/v1/auto-brightness/reset", reset);
    };
    resume.onclick = function () { runAutoBrightnessAction("api/v1/auto-brightness/resume", resume); };
    var bucket = autoBrightHistory && (autoBrightHistory.bucket_minutes || autoBrightHistory.bucketMinutes);
    var dayCount = autoBrightnessChartDays(normalizedChartPoints()).length;
    var lineDayCount = Math.min(dayCount, autoBrightnessLineDays());
    var detail = i18nText("configure.brightness.days_drawn", "{count} day(s) drawn individually", { count: lineDayCount });
    if (dayCount > autoBrightnessLineDays()) detail += i18nText("configure.brightness.earlier_weekly_range", " · earlier history shown as weekly range");
    if (bucket) detail += i18nText("configure.brightness.minute_buckets", " · {count} minute buckets", { count: bucket });
    var projectionSensitivity = autoBrightnessProjectionSensitivity();
    if (projectionSensitivity != null) detail += i18nText("configure.brightness.sensitivity", " · Sensitivity {percent}%", { percent: projectionSensitivity });
    detail += " · " + autoBrightnessFreshness();
    var panel = el("div", { class: "autobright-panel", id: "auto-brightness-learning" }, [
      el("div", { class: "autobright-head" }, [
        el("div", {}, [el("strong", { text: i18nText("configure.brightness.daily_learning", "Daily ambient learning") }), el("small", { text: autoBrightnessSummary() })]),
        el("div", { class: "autobright-actions" }, [reset, resume])
      ]),
      el("canvas", { id: "auto-brightness-chart", class: "autobright-chart", role: "img", "aria-label": i18nText("configure.brightness.chart_label", "24-hour ambient-light pattern: the three most recent days drawn individually, with the rest of the week shown as a shaded minimum-to-maximum range") }),
      el("div", { class: "autobright-legend" }, [
        el("span", { class: "observed", text: i18nText("configure.brightness.observed", "Observed") }),
        el("span", { class: "expected", text: i18nText("configure.brightness.learned_baseline", "Learned baseline") }),
        el("span", { class: "proposed", text: i18nText("configure.brightness.proposed_level", "Proposed level") }),
        el("small", { text: detail })
      ]),
      el("div", { class: "autobright-message" + (autoBrightMessage.indexOf("failed") >= 0 ? " error" : ""), text: autoBrightMessage })
    ]);
    setTimeout(drawAutoBrightnessChart, 0);
    return panel;
  }

  function autoSleepHuman(value) {
    var token = String(value == null ? "unknown" : value).toLowerCase();
    var labels = {
      unknown: i18nText("configure.auto_sleep.value.unknown", "Unknown"),
      disabled: i18nText("configure.auto_sleep.value.disabled", "Disabled"),
      authenticating: i18nText("configure.auto_sleep.value.authenticating", "Authenticating"),
      discovering: i18nText("configure.auto_sleep.value.discovering", "Discovering"),
      learning: i18nText("configure.auto_sleep.value.learning", "Learning"),
      connecting: i18nText("configure.auto_sleep.value.connecting", "Connecting"),
      synchronizing: i18nText("configure.auto_sleep.value.synchronizing", "Synchronizing"),
      live: i18nText("configure.auto_sleep.value.live", "Live"),
      no_area: i18nText("configure.auto_sleep.value.no_area", "No area"),
      no_credible_sources: i18nText("configure.auto_sleep.value.no_credible_sources", "No credible sources"),
      no_included_sources: i18nText("configure.auto_sleep.value.no_included_sources", "No included sources"),
      auth_failed: i18nText("configure.auto_sleep.value.auth_failed", "Authentication failed"),
      discovery_failed: i18nText("configure.auto_sleep.value.discovery_failed", "Discovery failed"),
      reconnecting: i18nText("configure.auto_sleep.value.reconnecting", "Reconnecting"),
      stopped: i18nText("configure.auto_sleep.value.stopped", "Stopped"),
      no_sources_configured: i18nText("configure.auto_sleep.value.no_sources_configured", "No sources configured"),
      all_sources_unavailable: i18nText("configure.auto_sleep.value.all_sources_unavailable", "All sources unavailable"),
      source_active: i18nText("configure.auto_sleep.value.source_active", "Source active"),
      partial_source_loss: i18nText("configure.auto_sleep.value.partial_source_loss", "Partial source loss"),
      source_activity_lease: i18nText("configure.auto_sleep.value.source_activity_lease", "Source activity lease"),
      touch_activity: i18nText("configure.auto_sleep.value.touch_activity", "Touch activity"),
      source_loss_wake: i18nText("configure.auto_sleep.value.source_loss_wake", "Woken on source loss"),
      proximity_activity: i18nText("configure.auto_sleep.value.proximity_activity", "Proximity activity"),
      lease_expired: i18nText("configure.auto_sleep.value.lease_expired", "Lease expired")
    };
    return labels[token] || token.replace(/_/g, " ").replace(/^./, function (c) { return c.toUpperCase(); });
  }

  function autoSleepSummaryModel(status) {
    status = status || {};
    if (autoSleepUsesPanel()) {
      var localLines = [
        i18nText("configure.auto_sleep.source_panel", "This panel’s proximity sensor"),
        i18nText("configure.auto_sleep.phase", "Phase: {phase}", { phase: autoSleepHuman(status.phase || "unknown") }),
        i18nText("configure.auto_sleep.reason", "Reason: {reason}", { reason: autoSleepHuman(status.reason || "unknown") }),
        i18nText("configure.auto_sleep.panel_setup_help", "Uses calibrated presence at this panel. Set up proximity first; automatic sleep pauses if the sensor is unavailable.")
      ];
      return { lines: localLines, accessible: localLines.join(" · ") };
    }
    var areaName = status.area_name != null ? status.area_name : status.areaName;
    var area = String(areaName || "").trim() || i18nText("configure.auto_sleep.not_learned", "not learned");
    var leaseMs = status.learned_lease_ms != null ? status.learned_lease_ms : status.learnedLeaseMs;
    var lease = typeof leaseMs === "number" && isFinite(leaseMs)
      ? i18nText("configure.duration.minutes", "{count} min", { count: Math.round(leaseMs / 60000) })
      : i18nText("configure.auto_sleep.not_learned", "not learned");
    var count = status.source_count != null ? status.source_count : status.sourceCount;
    var suppressed = status.manual_suppression === true || status.manualSuppression === true;
    var phase = autoSleepHuman(status.phase);
    var reason = autoSleepHuman(status.reason);
    var sources = count == null ? 0 : count;
    var override = suppressed ? i18nText("configure.state.active", "active") : i18nText("configure.state.inactive", "inactive");
    return {
      lines: [
        i18nText("configure.auto_sleep.area", "Home Assistant Area: {area}", { area: area }),
        i18nText("configure.auto_sleep.phase", "Phase: {phase}", { phase: phase }),
        i18nText("configure.auto_sleep.reason", "Reason: {reason}", { reason: reason }),
        i18nText("configure.auto_sleep.delay_sources", "Delay: {delay} · Sources: {count}", { delay: lease, count: sources }),
        i18nText("configure.auto_sleep.manual_override", "Manual override: {state}", { state: override })
      ],
      accessible: i18nText("configure.auto_sleep.summary_accessible", "Home Assistant Area: {area} · Phase: {phase} · Reason: {reason} · Learned delay: {delay} · Sources: {count} · Manual screen override: {state}",
        { area: area, phase: phase, reason: reason, delay: lease, count: sources, state: override })
    };
  }

  function autoSleepLoadingSummaryModel() {
    var loading = i18nText("configure.loading", "Loading…");
    return { lines: [loading, "", "", "", ""], accessible: loading };
  }

  function setAutoSleepSummary(summary, announcement, model) {
    var lines = summary.querySelectorAll(".auto-sleep-summary-line");
    for (var index = 0; index < lines.length; index += 1) {
      var nextLine = model.lines[index] || "";
      if (lines[index].textContent !== nextLine) lines[index].textContent = nextLine;
    }
    if (summary.getAttribute("title") !== model.accessible) summary.setAttribute("title", model.accessible);
    if (announcement.textContent !== model.accessible) announcement.textContent = model.accessible;
  }

  function autoSleepSummaryNode() {
    return el("small", { id: "auto-sleep-summary", class: "auto-sleep-summary", "aria-hidden": "true" }, [
      el("span", { class: "auto-sleep-summary-line" }),
      el("span", { class: "auto-sleep-summary-line" }),
      el("span", { class: "auto-sleep-summary-line" }),
      el("span", { class: "auto-sleep-summary-line" }),
      el("span", { class: "auto-sleep-summary-line" })
    ]);
  }

  function autoSleepSummaryAnnouncementNode() {
    return el("span", {
      id: "auto-sleep-summary-announcement", class: "sr-only", role: "status", "aria-live": "polite", "aria-atomic": "true"
    });
  }

  function autoSleepUsesPanel() { return values.auto_sleep_source === "panel"; }

  function autoSleepPrerequisiteText() {
    if (autoSleepUsesPanel()) return i18nText("configure.auto_sleep.panel_setup_help", "Uses calibrated presence at this panel. Set up proximity first; automatic sleep pauses if the sensor is unavailable.");
    var phase = String(autoSleepPrerequisite.phase || "unavailable").toLowerCase();
    var areaName = autoSleepPrerequisite.area_name != null ? autoSleepPrerequisite.area_name : autoSleepPrerequisite.areaName;
    if (phase === "checking") return i18nText("configure.auto_sleep.area_checking", "Checking this panel’s Home Assistant Area…");
    if (autoSleepPrerequisite.eligible === true && phase === "assigned") return i18nText("configure.auto_sleep.area", "Home Assistant Area: {area}", {
      area: String(areaName || "").trim() || i18nText("configure.auto_sleep.assigned", "Assigned")
    });
    if (phase === "unassigned") return i18nText("configure.auto_sleep.assign_area_first", "Assign this panel to a Home Assistant Area before enabling Auto sleep.");
    if (phase === "auth_failed") return i18nText("configure.auto_sleep.reconnect_first", "Reconnect Home Assistant before enabling Auto sleep.");
    return i18nText("configure.auto_sleep.area_check_failed", "Could not check this panel’s Home Assistant Area. Check the Home Assistant connection.");
  }

  // Why the Camera group is not offered. The panel already answers this on /api/v1/camera/status —
  // state plus fault_detail — and reusing it keeps Configure, the Dashboard card and the status endpoint
  // reading one classification instead of three that drift apart inside a release.
  var cameraCapability = null, cameraCapabilityLoading = false;
  function loadCameraCapability() {
    if (cameraCapabilityLoading || cameraCapability) return;
    cameraCapabilityLoading = true;
    fetch("api/v1/camera/status", { headers: { "Accept": "application/json" }, cache: "no-store" })
      .then(function (r) { if (!r.ok) throw r.status; return r.json(); })
      .then(function (d) { cameraCapability = d; render(); })
      .catch(function () { /* the block stays on its generic wording */ })
      .then(function () { cameraCapabilityLoading = false; });
  }

  // True only when the panel does not offer the camera at all. A camera-bearing panel on the Basic tab
  // also shows an empty Camera group — every camera setting is ADVANCED — and must not be told it has no
  // camera. That case has available:true fields filtered out by tier, so it is excluded here.
  function cameraGroupUnavailable() {
    var camera = schema.filter(function (f) { return presentationGroup(f) === "Camera"; });
    return camera.length > 0 && camera.every(function (f) { return !f.available; });
  }

  function cameraUnavailableNode() {
    var reason = cameraCapability && cameraCapability.state === "absent" ? cameraCapability.fault_detail : null;
    var text;
    if (reason === "suppressed_by_profile") {
      text = i18nText("configure.camera.suppressed", "This panel's device profile sets hardware.camera to false, so the camera is not offered. Edit the profile to offer it.");
    } else if (reason === "undetermined") {
      text = i18nText("configure.camera.undetermined", "The panel is still checking whether it has a camera. This is not yet an answer about the hardware.");
    } else {
      text = i18nText("configure.camera.not_enumerated", "This panel reports no camera. If it has one, declare hardware.camera in its device profile, alongside an LED the panel can light while the camera is open.");
    }
    return el("div", { class: "camera-unavailable", role: "status", text: text });
  }

  function autoSleepPrerequisiteNode() {
    return el("div", {
      id: "auto-sleep-prerequisite-status",
      class: "auto-sleep-prerequisite" + (autoSleepPrerequisite.eligible === true ? " eligible" : ""),
      role: "status", "aria-live": "polite", text: autoSleepPrerequisiteText()
    });
  }

  function updateAutoSleepPrerequisiteUi() {
    var status = document.getElementById("auto-sleep-prerequisite-status");
    if (status) {
      var next = autoSleepPrerequisiteText();
      if (status.textContent !== next) status.textContent = next;
      status.classList.toggle("eligible", autoSleepPrerequisite.eligible === true);
    }
    var toggle = document.querySelector("#cfg-auto_sleep [role=switch]");
    if (!toggle) return;
    var blocked = !autoSleepUsesPanel() && values.auto_sleep !== "true" && autoSleepPrerequisite.eligible !== true;
    toggle.classList.toggle("blocked", blocked);
    toggle.setAttribute("aria-disabled", blocked ? "true" : "false");
    toggle.setAttribute("tabindex", "0");
  }

  function convergeAutoSleepOffForMissingArea() {
    if (autoSleepUsesPanel()) return;
    if (values.auto_sleep !== "true") return;
    values.auto_sleep = "false";
    savedValues.auto_sleep = "false";
    invalidateAutoSleepData(true);
    recomputeDirty();
    updateSaveUi();
    var toggle = document.querySelector("#cfg-auto_sleep [role=switch]");
    if (toggle) {
      toggle.classList.remove("on");
      toggle.setAttribute("aria-checked", "false");
    }
    var panel = document.getElementById("auto-sleep-status");
    if (panel) panel.remove();
  }

  function loadAutoSleepPrerequisite() {
    if (autoSleepUsesPanel()) { updateAutoSleepPrerequisiteUi(); return; }
    if (!schema.some(function (field) { return field.key === "auto_sleep" && field.available; })) return;
    if (autoSleepPrerequisiteTimer) { clearTimeout(autoSleepPrerequisiteTimer); autoSleepPrerequisiteTimer = null; }
    // Focus/visibility refreshes are background validation. Keep a settled Area verdict visible
    // while they run; changing it to Checking… and straight back produces a conspicuous colour/text
    // flash without giving the user any useful new state.
    if (String(autoSleepPrerequisite.phase || "checking").toLowerCase() === "checking") {
      autoSleepPrerequisite = { eligible: false, phase: "checking", area_name: "" };
      updateAutoSleepPrerequisiteUi();
    }
    var request = ++autoSleepPrerequisiteRequest;
    fetch("api/v1/auto-sleep/prerequisite", { cache: "no-store" })
      .then(function (response) {
        if (!response.ok) throw new Error("HTTP " + response.status);
        return response.json();
      })
      .then(function (body) {
        if (request !== autoSleepPrerequisiteRequest) return;
        autoSleepPrerequisite = body || { eligible: false, phase: "unavailable", area_name: "" };
        var phase = String(autoSleepPrerequisite.phase || "unavailable").toLowerCase();
        var nextAreaName = autoSleepPrerequisite.area_name != null ? autoSleepPrerequisite.area_name : autoSleepPrerequisite.areaName;
        nextAreaName = phase === "assigned" && autoSleepPrerequisite.eligible === true ? String(nextAreaName || "").trim() : "";
        var initialAreaMismatch = nextAreaName && !autoSleepAssignedAreaName && (
          autoSleepStatus && !autoSleepAreaMatchesName(autoSleepStatus, nextAreaName) ||
          autoSleepHistory && !autoSleepAreaMatchesName(autoSleepHistory, nextAreaName)
        );
        if (nextAreaName && (initialAreaMismatch || autoSleepAssignedAreaName && nextAreaName !== autoSleepAssignedAreaName)) {
          autoSleepAssignedAreaName = nextAreaName;
          autoSleepAreaGeneration++;
          autoSleepSourceUpdating = Object.create(null);
          // The completed replay is still useful as a stable placeholder while the new Area is
          // discovered. Fence its requests, but keep its DOM beneath the busy overlay until the
          // replacement history is complete; clearing it here makes the whole card collapse and
          // expand through an empty state before every Area refresh.
          invalidateAutoSleepData();
          autoSleepHistoryWaiting = values.auto_sleep === "true";
          autoSleepHistoryWaitingMessage = autoSleepHistoryWaiting ? i18nText("configure.auto_sleep.history_preparing", "Preparing activity history…") : "";
          updateAutoSleepHistory();
          if (values.auto_sleep === "true") loadAutoSleepData();
        } else if (nextAreaName) {
          autoSleepAssignedAreaName = nextAreaName;
        }
        if (phase === "unassigned") {
          if (autoSleepAssignedAreaName) {
            autoSleepAreaGeneration++;
            autoSleepSourceUpdating = Object.create(null);
          }
          autoSleepAssignedAreaName = "";
          convergeAutoSleepOffForMissingArea();
        }
      })
      .catch(function () {
        if (request !== autoSleepPrerequisiteRequest) return;
        autoSleepPrerequisite = { eligible: false, phase: "unavailable", area_name: "" };
      })
      .then(function () {
        if (request === autoSleepPrerequisiteRequest) updateAutoSleepPrerequisiteUi();
      });
  }

  function scheduleAutoSleepPrerequisite() {
    if (autoSleepPrerequisiteTimer) clearTimeout(autoSleepPrerequisiteTimer);
    autoSleepPrerequisiteTimer = setTimeout(function () {
      autoSleepPrerequisiteTimer = null;
      loadAutoSleepPrerequisite();
    }, 150);
  }

  // Under <base href="/"> a bare fragment link would resolve against the base and leave this page, so the
  // link sets the fragment itself and cancels the navigation.
  function proximitySetupLink() {
    var link = el("a", { href: "#cfg-proximity-learning", class: "pbtn", text: i18nText("configure.auto_sleep.setup_proximity", "Set up proximity on panel") });
    link.addEventListener("click", function (ev) {
      ev.preventDefault();
      location.hash = "cfg-proximity-learning";
    });
    return link;
  }

  function autoSleepPanel() {
    if (autoSleepUsesPanel()) {
      var localSummary = autoSleepSummaryNode();
      var localAnnouncement = autoSleepSummaryAnnouncementNode();
      setAutoSleepSummary(localSummary, localAnnouncement, autoSleepSummaryModel(autoSleepStatus));
      return el("div", { class: "autobright-panel", id: "auto-sleep-status" }, [
        el("strong", { text: i18nText("configure.auto_sleep.source_panel", "This panel’s proximity sensor") }),
        localSummary, localAnnouncement,
        proximitySetupLink()
      ]);
    }
    if (!autoSleepStatus && !autoSleepLoading) {
      autoSleepHistoryWaiting = true;
      autoSleepHistoryWaitingMessage = i18nText("configure.auto_sleep.history_preparing", "Preparing activity history…");
    }
    var windows = el("div", { class: "auto-sleep-windows", role: "group", "aria-label": i18nText("configure.auto_sleep.history_period", "Activity history period") });
    [6, 24, 48].forEach(function (hours) {
      var button = el("button", { class: "pbtn", type: "button", text: hours + "h", "data-hours": hours, "aria-pressed": hours === autoSleepHistoryHours ? "true" : "false" });
      button.onclick = function () {
        if (autoSleepHistoryBusy() || hours === autoSleepHistoryHours) return;
        autoSleepHistoryHours = hours;
        if (autoSleepHistoryReady(autoSleepStatus)) loadAutoSleepHistory();
        else updateAutoSleepHistory();
      };
      windows.appendChild(button);
    });
    var chart = el("div", { id: "auto-sleep-chart", class: "auto-sleep-history", "aria-describedby": "auto-sleep-chart-description" }, [
      el("div", { class: "auto-sleep-chart-content" }),
      el("div", { class: "auto-sleep-loading-overlay", role: "status", "aria-live": "polite", text: i18nText("configure.auto_sleep.history_preparing", "Preparing activity history…") })
    ]);
    var summary = autoSleepSummaryNode();
    var announcement = autoSleepSummaryAnnouncementNode();
    setAutoSleepSummary(summary, announcement, autoSleepStatus ? autoSleepSummaryModel(autoSleepStatus) : autoSleepLoadingSummaryModel());
    var panel = el("div", { class: "autobright-panel", id: "auto-sleep-status" }, [
      el("div", { class: "autobright-head auto-sleep-head" }, [
      el("div", {}, [
          el("strong", { text: i18nText("configure.auto_sleep.activity", "Auto-sleep activity") })
        ]),
        el("div", { class: "autobright-actions" }, [windows])
      ]),
      summary,
      announcement,
      chart,
      el("div", { class: "autobright-legend auto-sleep-legend" }, [
        el("span", { class: "detected", text: i18nText("configure.auto_sleep.detected_awake", "Detected / Awake") }),
        el("span", { class: "clear", text: i18nText("configure.auto_sleep.clear_sleep", "Clear / Sleep") }),
        el("span", { class: "inhibited", text: i18nText("configure.state.unavailable", "Unavailable") })
      ]),
      el("div", { id: "auto-sleep-chart-description", class: "sr-only", text: i18nText("configure.auto_sleep.history_replaying", "Replaying activity history.") }),
      el("div", {
        id: "auto-sleep-history-message", class: "autobright-message", role: "status", "aria-live": "polite",
        text: autoSleepHistoryMessage()
      })
    ]);
    drawAutoSleepChart(chart, true);
    return panel;
  }

  function updateAutoSleepSummary() {
    var summary = document.getElementById("auto-sleep-summary");
    var announcement = document.getElementById("auto-sleep-summary-announcement");
    if (!summary || !announcement) return;
    // Five fixed rows keep the chart origin stable while status values change. Loading occupies the
    // same geometry before the first response; later readiness refreshes retain the settled values.
    setAutoSleepSummary(summary, announcement, autoSleepStatus ? autoSleepSummaryModel(autoSleepStatus) : autoSleepLoadingSummaryModel());
  }

  function autoSleepHistoryMessage() {
    if (autoSleepHistoryError) return autoSleepHistoryError;
    return autoSleepHistoryBusy() && autoSleepHistory && autoSleepHistory.available !== false
      ? i18nText("configure.auto_sleep.history_refreshing", "Refreshing activity history…") : "";
  }

  function autoSleepHistoryBusy() {
    return autoSleepHistoryLoading || autoSleepHistoryWaiting || autoSleepLoading;
  }

  function autoSleepAreaMatchesName(value, expected) {
    var actual = value && (value.area_name != null ? value.area_name : value.areaName);
    return String(actual || "").trim().toLowerCase() === String(expected || "").trim().toLowerCase();
  }

  function autoSleepAreaMatches(value) {
    var expected = String(autoSleepAssignedAreaName || "").trim();
    if (!expected) return true;
    return autoSleepAreaMatchesName(value, expected);
  }

  function autoSleepAreaTransitioning(value) {
    var expected = String(autoSleepAssignedAreaName || "").trim();
    var actual = value && (value.area_name != null ? value.area_name : value.areaName);
    actual = String(actual || "").trim();
    // A differently named Area is a stale-but-valid result while HA registry discovery catches up.
    // A blank Area is also how terminal failures are reported, so it must not imply endless retry.
    return !!expected && !!actual && !autoSleepAreaMatchesName(value, expected);
  }

  function autoSleepHistoryReady(status) {
    var phase = String(status && status.phase || "").toLowerCase();
    var count = status && (status.source_count != null ? status.source_count : status.sourceCount);
    var discovered = status && (status.discovered_source_count != null ? status.discovered_source_count : status.discoveredSourceCount);
    return status && status.enabled === true && autoSleepAreaMatches(status) &&
      (phase === "live" && Number(count) > 0 || phase === "no_included_sources" && Number(discovered) > 0);
  }

  function autoSleepHistoryPreparing(status) {
    var phase = String(status && status.phase || "").toLowerCase();
    return ["authenticating", "discovering", "learning", "connecting", "synchronizing", "reconnecting"].indexOf(phase) >= 0;
  }

  function autoSleepStatusRetryable(status) {
    var reason = String(status && status.reason || "").toLowerCase();
    var detail = String(status && status.detail || "").toLowerCase();
    return reason === "request_failed" || detail === "registry_transport" || detail === "history_transport";
  }

  function autoSleepHistoryTerminalMessage(status) {
    var phase = String(status && status.phase || "").toLowerCase();
    var detail = String(status && status.detail || "").toLowerCase();
    if (phase === "no_area") return i18nText("configure.auto_sleep.history_requires_area", "Assign this panel to a Home Assistant Area to calculate activity history.");
    if (phase === "no_credible_sources") return i18nText("configure.auto_sleep.no_credible_sources", "No credible device-backed activity source is available in this Area.");
    if (phase === "auth_failed") return i18nText("configure.auto_sleep.auth_failed", "Home Assistant authentication failed. Reconnect Home Assistant to calculate activity history.");
    if (phase === "discovery_failed" && detail === "history_parse") return i18nText("configure.auto_sleep.timestamps_unreadable", "Home Assistant returned activity timestamps this panel could not read.");
    if (phase === "discovery_failed" && detail === "history_limit") return i18nText("configure.auto_sleep.history_too_large", "Home Assistant activity history is too large to process safely.");
    if (phase === "discovery_failed") return i18nText("configure.auto_sleep.discovery_failed", "Auto-sleep source discovery failed. Check the Home Assistant connection.");
    if (phase === "status_failed") return i18nText("configure.auto_sleep.status_failed", "Auto-sleep status request failed (HTTP {status}).", { status: Number(status && status.status_code) });
    return i18nText("configure.auto_sleep.history_unavailable", "Auto-sleep activity history is unavailable.");
  }

  function scheduleAutoSleepReadiness(afterFailure) {
    if (autoSleepHistoryReadyTimer) clearTimeout(autoSleepHistoryReadyTimer);
    var request = autoSleepRequest;
    var delay = afterFailure ? autoSleepHistoryRetryDelayMs : autoSleepReadinessDelayMs;
    if (afterFailure) autoSleepHistoryRetryDelayMs = Math.min(60 * 1000, autoSleepHistoryRetryDelayMs * 2);
    else autoSleepReadinessDelayMs = Math.min(5000, autoSleepReadinessDelayMs + 500);
    autoSleepHistoryReadyTimer = setTimeout(function () {
      autoSleepHistoryReadyTimer = null;
      if (request === autoSleepRequest && values.auto_sleep === "true") loadAutoSleepData();
    }, delay);
  }

  function autoSleepSegments(history) {
    var rows = history && history.segments;
    return Array.isArray(rows) ? rows.filter(function (row) {
      var start = row.start_epoch_ms != null ? row.start_epoch_ms : row.startEpochMs;
      var end = row.end_epoch_ms != null ? row.end_epoch_ms : row.endEpochMs;
      return typeof start === "number" && typeof end === "number" && end > start;
    }) : [];
  }

  function autoSleepSourceLanes(history) {
    var rows = history && (history.source_lanes || history.sourceLanes);
    return Array.isArray(rows) ? rows : [];
  }

  function toggleAutoSleepSource(source) {
    var sourceKey = source && (source.source_key != null ? source.source_key : source.sourceKey);
    var areaKey = autoSleepHistory && (autoSleepHistory.area_key != null ? autoSleepHistory.area_key : autoSleepHistory.areaKey);
    sourceKey = String(sourceKey || "").trim();
    areaKey = String(areaKey || "").trim();
    if (!areaKey || !sourceKey || autoSleepSourceUpdating[sourceKey]) return;
    var included = source.included !== false;
    var updateGeneration = autoSleepAreaGeneration;
    var updateToken = {};
    autoSleepSourceUpdating[sourceKey] = updateToken;
    autoSleepHistoryWaiting = true;
    autoSleepHistoryWaitingMessage = i18nText("configure.auto_sleep.history_preparing", "Preparing activity history…");
    autoSleepHistoryError = "";
    updateAutoSleepHistory(false);
    fetch("api/v1/auto-sleep/source", {
      method: "POST",
      headers: { "Accept": "application/json", "Content-Type": "application/json" },
      body: JSON.stringify({ area_key: areaKey, source_key: sourceKey, included: !included })
    }).then(function (response) {
      if (!response.ok) { var error = new Error("HTTP " + response.status); error.status = response.status; throw error; }
      return response.json().catch(function () { return {}; });
    }).then(function () {
      if (updateGeneration !== autoSleepAreaGeneration || autoSleepSourceUpdating[sourceKey] !== updateToken) return;
      delete autoSleepSourceUpdating[sourceKey];
      invalidateAutoSleepData();
      loadAutoSleepData();
    }).catch(function (error) {
      if (updateGeneration !== autoSleepAreaGeneration || autoSleepSourceUpdating[sourceKey] !== updateToken) return;
      delete autoSleepSourceUpdating[sourceKey];
      autoSleepHistoryWaiting = false;
      autoSleepHistoryWaitingMessage = "";
      autoSleepHistoryError = error && error.status
        ? i18nText("configure.auto_sleep.source_update_failed_http", "Could not update this activity source (HTTP {status}).", { status: error.status })
        : i18nText("configure.auto_sleep.source_update_failed", "Could not update this activity source.");
      updateAutoSleepHistory();
    });
  }

  function autoSleepDisplayedHours(history) {
    var hours = history && history.hours;
    if (typeof hours === "number" && isFinite(hours) && hours > 0) return hours;
    var start = history && (history.window_start_epoch_ms != null ? history.window_start_epoch_ms : history.windowStartEpochMs);
    var end = history && (history.window_end_epoch_ms != null ? history.window_end_epoch_ms : history.windowEndEpochMs);
    return typeof start === "number" && typeof end === "number" && end > start ? Math.round((end - start) / 3600000) : autoSleepHistoryHours;
  }

  function autoSleepHistoryBounds(history) {
    var now = Date.now(), fallbackStart = now - autoSleepHistoryHours * 60 * 60 * 1000;
    return {
      start: history && (history.window_start_epoch_ms != null ? history.window_start_epoch_ms : history.windowStartEpochMs) || fallbackStart,
      end: history && (history.window_end_epoch_ms != null ? history.window_end_epoch_ms : history.windowEndEpochMs) || now
    };
  }

  function autoSleepHistorySummary(segments, bounds) {
    var totals = { hold_awake: 0, allow_sleep: 0, inhibited: 0 };
    segments.forEach(function (segment) {
      var output = String(segment.output || "inhibited").toLowerCase();
      var start = segment.start_epoch_ms != null ? segment.start_epoch_ms : segment.startEpochMs;
      var end = segment.end_epoch_ms != null ? segment.end_epoch_ms : segment.endEpochMs;
      if (totals[output] != null) totals[output] += Math.max(0, Math.min(bounds.end, end) - Math.max(bounds.start, start));
    });
    function duration(ms) {
      var minutes = Math.round(ms / 60000);
      return minutes >= 60
        ? i18nText("configure.duration.hours_minutes", "{hours} h {minutes} min", { hours: Math.floor(minutes / 60), minutes: minutes % 60 })
        : i18nText("configure.duration.minutes", "{count} min", { count: minutes });
    }
    return i18nText("configure.auto_sleep.calculated_summary", "Calculated auto-sleep: hold awake {awake}, allow sleep {sleep}, inhibited {inhibited}.", {
      awake: duration(totals.hold_awake), sleep: duration(totals.allow_sleep), inhibited: duration(totals.inhibited)
    });
  }

  function setAutoSleepOverlayHidden(overlay, hidden) {
    if (overlay && overlay.hidden !== hidden) overlay.hidden = hidden;
  }

  function drawAutoSleepChart(target, replaceSnapshot) {
    var chart = target && target.nodeType ? target : document.getElementById("auto-sleep-chart");
    if (!chart) return;
    var content = chart.querySelector(".auto-sleep-chart-content");
    var overlay = chart.querySelector(".auto-sleep-loading-overlay");
    if (!content) return;
    var history = autoSleepHistory;
    var shouldReplace = replaceSnapshot === true || !content.firstChild;
    if (!shouldReplace) {
      var retainedBusy = autoSleepHistoryBusy();
      var retainedSettled = content.getAttribute("data-settled") === "true";
      Array.prototype.forEach.call(chart.querySelectorAll(".auto-sleep-lane.source[role=button]"), function (row) {
        row.setAttribute("aria-disabled", retainedBusy ? "true" : "false");
      });
      chart.classList.toggle("busy", retainedBusy);
      chart.setAttribute("aria-busy", retainedBusy ? "true" : "false");
      setAutoSleepOverlayHidden(overlay, !retainedBusy || retainedSettled);
      return;
    }
    var displayedHours = autoSleepDisplayedHours(history);
    var policySegments = autoSleepSegments(history), sourceLanes = autoSleepSourceLanes(history), bounds = autoSleepHistoryBounds(history);
    var replacement = el("div", { class: "auto-sleep-chart-snapshot" });
    if (!policySegments.length) replacement.appendChild(el("div", { class: "auto-sleep-empty", text: autoSleepHistoryError || i18nText("configure.auto_sleep.no_replay_data", "No replay data") }));
    function span(segment, kind) {
      var start = segment.start_epoch_ms != null ? segment.start_epoch_ms : segment.startEpochMs;
      var end = segment.end_epoch_ms != null ? segment.end_epoch_ms : segment.endEpochMs;
      var state = kind === "policy" ? String(segment.output || "inhibited").toLowerCase() : String(segment.state || "unavailable").toLowerCase();
      var left = Math.max(0, Math.min(100, (start - bounds.start) / Math.max(1, bounds.end - bounds.start) * 100));
      var right = Math.max(left, Math.min(100, (end - bounds.start) / Math.max(1, bounds.end - bounds.start) * 100));
      var names = {
        hold_awake: i18nText("configure.auto_sleep.hold_awake", "Hold awake"),
        allow_sleep: i18nText("configure.auto_sleep.allow_sleep", "Allow sleep"),
        inhibited: i18nText("configure.auto_sleep.inhibited", "Inhibited"),
        on: i18nText("configure.auto_sleep.detected", "Detected"),
        off: i18nText("configure.auto_sleep.clear", "Clear"),
        unavailable: i18nText("configure.state.unavailable", "Unavailable")
      };
      var node = el("span", { class: "auto-sleep-interval " + state });
      node.style.left = left + "%"; node.style.width = Math.max(.15, right - left) + "%";
      if (right - left >= 9) node.setAttribute("data-label", node.textContent = names[state] || autoSleepHuman(state));
      return node;
    }
    function lane(label, segments, kind, source) {
      var trackAttrs = { class: "auto-sleep-track" };
      var counts = {};
      segments.forEach(function (segment) {
        var state = kind === "policy" ? String(segment.output || "inhibited").toLowerCase() : String(segment.state || "unavailable").toLowerCase();
        counts[state] = (counts[state] || 0) + 1;
      });
      var names = {
        hold_awake: i18nText("configure.auto_sleep.hold_awake_lower", "hold awake"),
        allow_sleep: i18nText("configure.auto_sleep.allow_sleep_lower", "allow sleep"),
        inhibited: i18nText("configure.auto_sleep.inhibited_lower", "inhibited"),
        on: i18nText("configure.auto_sleep.detected_lower", "detected"),
        off: i18nText("configure.auto_sleep.clear_lower", "clear"),
        unavailable: i18nText("configure.state.unavailable_lower", "unavailable")
      };
      var detail = Object.keys(counts).map(function (state) {
        return i18nText("configure.auto_sleep.interval_count", "{state} {count} interval(s)", { state: names[state] || autoSleepHuman(state), count: counts[state] });
      }).join(", ");
      var sourceKey = source && (source.source_key != null ? source.source_key : source.sourceKey);
      sourceKey = String(sourceKey || "").trim();
      var included = !source || source.included !== false;
      var updating = !!(sourceKey && autoSleepSourceUpdating[sourceKey]);
      var interactionBlocked = updating || autoSleepHistoryBusy();
      function sourceInteractionBlocked() {
        return !!autoSleepSourceUpdating[sourceKey] || autoSleepHistoryBusy();
      }
      var stateText = source ? (included ? i18nText("configure.auto_sleep.included", "included") : i18nText("configure.auto_sleep.suppressed_lower", "suppressed")) : i18nText("configure.auto_sleep.calculated", "calculated");
      var labelText = label + (source && !included ? i18nText("configure.auto_sleep.suppressed_suffix", " · Suppressed") : "") + (updating ? i18nText("configure.auto_sleep.updating_suffix", " · Updating…") : "");
      var rowAttrs = {
        class: "auto-sleep-lane " + (kind === "policy" ? "policy" : "source") + (included ? "" : " suppressed") + (updating ? " updating" : ""),
        role: sourceKey ? "button" : "img",
        "aria-label": i18nText("configure.auto_sleep.lane_label", "{label}{kind}{state}, over {hours} hours: {detail}", {
          label: label,
          kind: source ? i18nText("configure.auto_sleep.activity_source_separator", " activity source, ") : i18nText("configure.auto_sleep.result_separator", " result, "),
          state: stateText, hours: displayedHours, detail: detail || i18nText("configure.auto_sleep.no_intervals", "no intervals")
        })
      };
      if (sourceKey) {
        rowAttrs.tabindex = "0";
        rowAttrs["aria-pressed"] = included ? "true" : "false";
        rowAttrs["aria-disabled"] = interactionBlocked ? "true" : "false";
        trackAttrs.title = included ? i18nText("configure.auto_sleep.click_suppress", "Click to suppress this source") : i18nText("configure.auto_sleep.click_include", "Click to include this source");
      }
      var track = el("div", trackAttrs);
      segments.forEach(function (segment) { track.appendChild(span(segment, kind)); });
      var row = el("div", rowAttrs, [
        el("div", { class: "auto-sleep-label", text: labelText, title: label }), track
      ]);
      if (sourceKey) {
        row.onclick = function () { if (!sourceInteractionBlocked()) toggleAutoSleepSource(source); };
        row.onkeydown = function (event) {
          if (event.key !== "Enter" && event.key !== " ") return;
          event.preventDefault();
          if (!sourceInteractionBlocked()) toggleAutoSleepSource(source);
        };
      }
      return row;
    }
    if (policySegments.length) {
      replacement.appendChild(lane(i18nText("configure.auto_sleep.calculated_label", "Calculated auto-sleep"), policySegments, "policy"));
      var sources = el("div", { class: "auto-sleep-source-scroll" });
      sourceLanes.forEach(function (source) { sources.appendChild(lane(source.label || i18nText("configure.auto_sleep.activity_source", "Activity source"), Array.isArray(source.segments) ? source.segments : [], "source", source)); });
      replacement.appendChild(sources);
      var axis = el("div", { class: "auto-sleep-axis", "aria-hidden": "true" }, [el("span", { text: "" }), el("div", { class: "auto-sleep-axis-track" })]);
      var axisTrack = axis.lastChild;
      for (var tick = 0; tick <= 6; tick += 1) {
        var at = new Date(bounds.start + tick / 6 * (bounds.end - bounds.start));
        var marker = el("span", { text: ("0" + at.getHours()).slice(-2) + ":" + ("0" + at.getMinutes()).slice(-2) });
        marker.style.left = tick / 6 * 100 + "%";
        axisTrack.appendChild(marker);
      }
      replacement.appendChild(axis);
    }
    content.replaceChildren(replacement);
    fitAutoSleepIntervalLabels(replacement);
    if (history && history.available !== false) content.setAttribute("data-settled", "true");
    else content.removeAttribute("data-settled");
    var busy = autoSleepHistoryBusy();
    Array.prototype.forEach.call(chart.querySelectorAll(".auto-sleep-lane.source[role=button]"), function (row) {
      row.setAttribute("aria-disabled", busy ? "true" : "false");
    });
    chart.classList.toggle("busy", busy);
    chart.setAttribute("aria-busy", busy ? "true" : "false");
    setAutoSleepOverlayHidden(overlay, !busy || content.getAttribute("data-settled") === "true");
    var description = autoSleepHistorySummary(policySegments, bounds) + " " + i18nText("configure.auto_sleep.source_lanes_shown", "{count} source lanes are shown.", { count: sourceLanes.length });
    var accessible = document.getElementById("auto-sleep-chart-description");
    if (accessible) accessible.textContent = description;
  }

  function updateAutoSleepHistory(replaceSnapshot) {
    var message = document.getElementById("auto-sleep-history-message");
    var windowButtons = document.querySelectorAll(".auto-sleep-windows .pbtn");
    var busy = autoSleepHistoryBusy();
    var chartContent = document.querySelector("#auto-sleep-chart .auto-sleep-chart-content");
    var settled = chartContent && chartContent.getAttribute("data-settled") === "true";
    if (message) {
      var nextMessage = autoSleepHistoryMessage();
      if (message.textContent !== nextMessage) message.textContent = nextMessage;
      message.classList.toggle("error", !!autoSleepHistoryError);
    }
    for (var index = 0; index < windowButtons.length; index += 1) {
      var selected = Number(windowButtons[index].getAttribute("data-hours")) === autoSleepHistoryHours;
      windowButtons[index].setAttribute("aria-pressed", selected ? "true" : "false");
      windowButtons[index].setAttribute("aria-disabled", busy ? "true" : "false");
      // Native disabled styling fades every button. Retain it only for a cold chart; a settled chart
      // remains visually unchanged while its request fencing is expressed through aria-disabled and
      // the click guard.
      windowButtons[index].disabled = busy && !settled;
    }
    drawAutoSleepChart(null, replaceSnapshot === true);
  }

  function loadAutoSleepHistory() {
    if (values.auto_sleep !== "true" || autoSleepHistoryLoading) return;
    autoSleepHistoryLoading = true; autoSleepHistoryWaiting = false; autoSleepHistoryWaitingMessage = "";
    autoSleepHistoryError = ""; updateAutoSleepHistory();
    var request = ++autoSleepHistoryRequest, retryAutomatically = false, receivedHistory = false, succeeded = false;
    if (autoSleepHistoryReadyTimer) { clearTimeout(autoSleepHistoryReadyTimer); autoSleepHistoryReadyTimer = null; }
    fetch("api/v1/auto-sleep/history?hours=" + autoSleepHistoryHours, { cache: "no-store" })
      .then(function (response) {
        if (!response.ok) { var httpError = new Error("http"); httpError.status = response.status; throw httpError; }
        return response.json();
      })
      .then(function (body) {
        if (request !== autoSleepHistoryRequest) return;
        if (!body || body.available === false) {
          var unavailable = new Error("unavailable"); unavailable.detail = body && body.detail; throw unavailable;
        }
        if (!autoSleepAreaMatches(body)) {
          var staleArea = new Error("stale area"); staleArea.detail = "sources_changed"; throw staleArea;
        }
        autoSleepHistory = body;
        receivedHistory = true;
        succeeded = true;
        autoSleepReadinessDelayMs = 1000;
        autoSleepHistoryRetryDelayMs = 5000;
      }).catch(function (error) {
        if (request !== autoSleepHistoryRequest) return;
        if (error && error.status >= 400 && error.status < 500) {
          autoSleepHistoryError = i18nText("configure.auto_sleep.history_request_failed", "History request failed (HTTP {status}).", { status: error.status });
        }
        else if (error && error.status) {
          retryAutomatically = true;
          autoSleepHistoryWaiting = true;
          autoSleepHistoryWaitingMessage = i18nText("configure.auto_sleep.history_retrying", "Activity history is temporarily unavailable. Retrying automatically…");
        }
        else if (error && (error.detail === "runtime_unavailable" || error.detail === "sources_changed")) {
          retryAutomatically = true;
          autoSleepHistoryWaiting = true;
          autoSleepHistoryWaitingMessage = error.detail === "sources_changed"
            ? i18nText("configure.auto_sleep.sources_changed", "Activity sources changed. Refreshing history…")
            : i18nText("configure.auto_sleep.history_preparing", "Preparing activity history…");
        }
        else if (error && error.detail === "history_auth") autoSleepHistoryError = i18nText("configure.auto_sleep.history_rejected", "Home Assistant rejected the history request. Reconnect Home Assistant.");
        else if (error && (error.detail === "history_transport" || error.detail === "history_unavailable")) {
          retryAutomatically = true;
          autoSleepHistoryWaiting = true;
          autoSleepHistoryWaitingMessage = i18nText("configure.auto_sleep.ha_history_retrying", "Home Assistant history is temporarily unavailable. Retrying automatically…");
        }
        else if (error && error.name === "TypeError") {
          retryAutomatically = true;
          autoSleepHistoryWaiting = true;
          autoSleepHistoryWaitingMessage = i18nText("configure.auto_sleep.panel_reconnecting", "The panel connection changed. Reconnecting activity history…");
        }
        else if (error && error.detail === "history_parse") autoSleepHistoryError = i18nText("configure.auto_sleep.history_unreadable", "Home Assistant returned activity history this build could not read.");
        else if (error && error.detail === "history_limit") autoSleepHistoryError = i18nText("configure.auto_sleep.history_rows_exceeded", "Home Assistant returned more activity history rows than the replay safety bound allows.");
        else autoSleepHistoryError = i18nText("configure.auto_sleep.response_unreadable", "The activity history response could not be read.");
        if (!retryAutomatically && autoSleepHistory) autoSleepHistoryHours = autoSleepDisplayedHours(autoSleepHistory);
      }).then(function () {
        if (request !== autoSleepHistoryRequest) return;
        autoSleepHistoryLoading = false; updateAutoSleepHistory(receivedHistory);
        if ((succeeded || !retryAutomatically) && typeof window !== "undefined" && window.configCardSizeSourceReady) {
          window.configCardSizeSourceReady("autoSleep");
          if (window.configCardSizeGeometryChanged) window.configCardSizeGeometryChanged();
        }
        if (retryAutomatically) scheduleAutoSleepReadiness(true);
      });
  }

  function invalidateAutoSleepHistory(clearSnapshot) {
    autoSleepHistoryRequest++;
    autoSleepHistoryLoading = false;
    if (clearSnapshot) autoSleepHistory = null;
    autoSleepHistoryError = "";
    autoSleepHistoryWaiting = false;
    autoSleepHistoryWaitingMessage = "";
    autoSleepReadinessDelayMs = 1000;
    autoSleepHistoryRetryDelayMs = 5000;
    if (autoSleepHistoryReadyTimer) { clearTimeout(autoSleepHistoryReadyTimer); autoSleepHistoryReadyTimer = null; }
  }

  function invalidateAutoSleepData(clearSnapshot) {
    autoSleepRequest++;
    autoSleepLoading = false;
    if (clearSnapshot === true) autoSleepStatus = null;
    invalidateAutoSleepHistory(clearSnapshot === true);
  }

  // Read status before history. Transitional discovery is followed automatically with capped backoff;
  // once LIVE, steady state owns no timer and the replay is fetched exactly once.
  function loadAutoSleepData() {
    if (values.auto_sleep !== "true" || autoSleepLoading) return;
    autoSleepLoading = true;
    autoSleepHistoryWaiting = true;
    autoSleepHistoryWaitingMessage = i18nText("configure.auto_sleep.history_preparing", "Preparing activity history…");
    autoSleepHistoryError = "";
    updateAutoSleepSummary();
    updateAutoSleepHistory();
    var request = ++autoSleepRequest;
    fetch("api/v1/auto-sleep", { cache: "no-store" })
      .then(function (response) {
        if (!response.ok) { var statusError = new Error("status"); statusError.status = response.status; throw statusError; }
        return response.json();
      })
      .then(function (body) {
        if (request !== autoSleepRequest) return;
        autoSleepStatus = body || { available: false, phase: "unavailable" };
      }).catch(function (error) {
        if (request !== autoSleepRequest) return;
        autoSleepStatus = error && error.status >= 400 && error.status < 500 ?
          { available: false, phase: "status_failed", reason: "status_http", status_code: error.status, source_count: 0, manual_suppression: false } :
          { available: false, phase: "unavailable", reason: "request_failed", source_count: 0, manual_suppression: false };
      }).then(function () {
      if (request !== autoSleepRequest) return;
      autoSleepLoading = false;
      updateAutoSleepSummary();
      if (autoSleepUsesPanel()) {
        autoSleepHistoryWaiting = false;
        scheduleAutoSleepReadiness(false);
        return;
      }
      if (autoSleepHistoryReady(autoSleepStatus)) {
        autoSleepHistoryWaiting = false;
        autoSleepHistoryWaitingMessage = "";
        autoSleepHistoryError = "";
        loadAutoSleepHistory();
      } else if (autoSleepAreaTransitioning(autoSleepStatus) || autoSleepHistoryPreparing(autoSleepStatus) || autoSleepStatusRetryable(autoSleepStatus)) {
        var retryAfterFailure = autoSleepStatusRetryable(autoSleepStatus);
        autoSleepHistoryWaiting = true;
        autoSleepHistoryWaitingMessage = autoSleepAreaTransitioning(autoSleepStatus) || autoSleepHistoryPreparing(autoSleepStatus) ?
          i18nText("configure.auto_sleep.history_preparing", "Preparing activity history…") : i18nText("configure.auto_sleep.status_waiting", "Waiting for auto-sleep status…");
        autoSleepHistoryError = "";
        updateAutoSleepHistory();
        scheduleAutoSleepReadiness(retryAfterFailure);
      } else {
        autoSleepHistoryWaiting = false;
        autoSleepHistoryWaitingMessage = "";
        autoSleepHistoryError = autoSleepHistoryTerminalMessage(autoSleepStatus);
        updateAutoSleepHistory();
        if (typeof window !== "undefined" && window.configCardSizeSourceReady) {
          window.configCardSizeSourceReady("autoSleep");
          if (window.configCardSizeGeometryChanged) window.configCardSizeGeometryChanged();
        }
      }
    });
  }

  // An interval names its state only where the whole name fits: a longer translation in a narrow bar is
  // left unlabelled rather than cut off (the legend and the lane description still carry the state).
  function fitAutoSleepIntervalLabels(scope) {
    if (!scope || !scope.isConnected) return;
    Array.prototype.forEach.call(scope.querySelectorAll(".auto-sleep-interval[data-label]"), function (node) {
      if (node.textContent !== node.getAttribute("data-label")) node.textContent = node.getAttribute("data-label");
      // The label is centred, so a long one overflows both edges; compare the painted text with the bar.
      var range = document.createRange(); range.selectNodeContents(node);
      var text = range.getBoundingClientRect(), bar = node.getBoundingClientRect();
      if (text.left < bar.left - 0.5 || text.right > bar.right + 0.5) node.textContent = "";
    });
  }

  var autoSleepResizeTimer = null;
  if (window.addEventListener) {
    window.addEventListener("resize", function () {
      if (autoSleepResizeTimer) clearTimeout(autoSleepResizeTimer);
      autoSleepResizeTimer = setTimeout(drawAutoSleepChart, 100);
    });
  }

  // Link / broken-link icons (Lucide link + unlink — a complementary pair; currentColor so CSS tints them).
  var SVG_ATTRS = 'viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"';
  var ICON_LINK = '<svg ' + SVG_ATTRS + '><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>';
  var ICON_UNLINK = '<svg ' + SVG_ATTRS + '><path d="M18.84 12.25l1.72-1.71a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M5.17 11.75l-1.71 1.71a5 5 0 0 0 7.07 7.07l1.71-1.71"/><line x1="8" y1="2" x2="8" y2="5"/><line x1="2" y1="8" x2="5" y2="8"/><line x1="16" y1="19" x2="16" y2="22"/><line x1="19" y1="16" x2="22" y2="16"/></svg>';

  // Expose-to-HA toggle (only on settings that are HA entities): an icon button, no checkbox —
  // a link icon = exposed as an HA entity (highlighted), a broken-link icon = hidden. Click toggles it.
  function pip(f) {
    if (!f.ha) return null;
    var on = expose[f.key] !== false;
    var btn = el("button", { class: "pip", type: "button" });
    function render() {
      btn.classList.toggle("on", on);
      btn.innerHTML = on ? ICON_LINK : ICON_UNLINK;
      btn.title = on ? i18nText("configure.exposure.hide_title", "Exposed to Home Assistant — click to hide")
                     : i18nText("configure.exposure.expose_title", "Hidden from Home Assistant — click to expose");
      btn.setAttribute("aria-label", on ? i18nText("configure.exposure.exposed", "Exposed to Home Assistant") : i18nText("configure.exposure.hidden", "Hidden from Home Assistant"));
      btn.setAttribute("aria-pressed", on ? "true" : "false");
    }
    btn.addEventListener("click", function () { on = !on; expose[f.key] = on; render(); setDirty(f.key, true); });
    render();
    return btn;
  }

  // The RTSP port the camera transport listens on. Must equal CameraRtspServer.DEFAULT_PORT;
  // CameraSurfaceContractTest asserts the two agree so this cannot drift silently.
  var CAMERA_RTSP_PORT = 8554;

  /**
   * Turn named words inside a help string into links, leaving the wording itself in the settings
   * registry. Each pair is [word, href]; the first occurrence of each word becomes an anchor and
   * everything else stays plain text, so re-wording the help does not have to be mirrored here.
   */
  function linkifyWords(text, pairs) {
    var nodes = [document.createTextNode(text)];
    pairs.forEach(function (pair) {
      for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        if (node.nodeType !== 3) continue;
        var at = node.textContent.indexOf(pair[0]);
        if (at < 0) continue;
        var before = node.textContent.slice(0, at);
        var after = node.textContent.slice(at + pair[0].length);
        var link = el("a", { href: pair[1], text: pair[0] });
        if (pair[1].indexOf("rtsp:") !== 0) link.setAttribute("target", "_blank");
        link.setAttribute("title", pair[1]);
        nodes.splice(i, 1, document.createTextNode(before), link, document.createTextNode(after));
        break;
      }
    });
    return nodes;
  }

  function row(f) {
    var help = null;
    if (f.key === "dashboard_zoom") {
      var helpKids = [el("span", { lang: f.helpLanguage, text: f.help })];
      if (f.displaySizingAvailable === true) {
        helpKids.push(document.createTextNode(i18nText("configure.display.recommend_prefix", " Recommend use ")));
        helpKids.push(el("a", { href: localizedPageHref("install#cfg-display"), text: i18nText("configure.display.sizing", "Display Sizing") }));
        helpKids.push(document.createTextNode(i18nText("configure.display.recommend_suffix", " for better results")));
      }
      help = el("small", {}, helpKids);
    } else if (f.key === "camera_enabled") {
      // Name the two addresses the help text already talks about, so they can be opened or copied
      // rather than retyped. The RTSP link uses whatever host this page was reached on, which is the
      // address that will also work from Home Assistant.
      help = el("small", { lang: f.helpLanguage }, linkifyWords(f.help, [
        ["RTSP", "rtsp://" + location.hostname + ":" + CAMERA_RTSP_PORT + "/live"],
        ["JPEG", "api/v1/camera/snapshot.jpg"],
      ]));
    } else if (f.key === "auto_sleep") {
      help = el("small", { lang: f.helpLanguage, text: f.help });
    } else if (f.help) {
      help = el("small", { lang: f.helpLanguage, text: f.help });
    }
    var companionRendererHint = f.key === "dashboard_package" && rendererChoices.some(function (renderer) {
      return renderer && renderer.pkg === values.dashboard_package;
    }) ? el("small", {
      class: "companion-renderer-hint",
      text: i18nText("configure.renderer.companion_launcher_hint", "Turn on the Companion app's launcher option for it to take Home.")
    }) : null;
    var protectedSetting = !!HARDENED_APPROVAL_SETTING_KEYS[f.key];
    var labelText = el("span", { lang: f.labelLanguage });
    if (protectedSetting) {
      // The shield is a pseudo-element, so a non-breaking space alone does not reliably bind it to
      // the label in older WebViews. Keep the final word and the shield in one non-wrapping inline run.
      var words = f.label.trim().split(/\s+/);
      if (words.length > 1) labelText.appendChild(document.createTextNode(words.slice(0, -1).join(" ") + " "));
      var shieldTail = el("span", { class: "hardened-label-tail", text: words[words.length - 1] || f.label });
      shieldTail.setAttribute("data-hardened-approval", "conditional");
      shieldTail.setAttribute("aria-describedby", "hardened-approval-conditional-description");
      shieldTail.setAttribute("title", i18nText("configure.hardened.setting_approval", "Changing this setting may require physical on-panel approval when Hardened mode is enabled."));
      labelText.appendChild(shieldTail);
    } else labelText.textContent = f.label;
    var label = el("div", { class: "flabel" }, [
      labelText,
      help,
      companionRendererHint,
      Object.prototype.hasOwnProperty.call(applyPending, f.key) ?
        el("small", { class: "apply-pending-status", text: applyPendingStatusText(f.key) }) : null,
    ]);
    // Read-only rows (diagnostic sensors) have no editable value — just the expose-to-HA pip.
    var valueControl = f.readOnly ? null : control(f);
    if (valueControl) {
      if (!valueControl.getAttribute("aria-label")) valueControl.setAttribute("aria-label", f.label);
      valueControl.setAttribute("lang", f.labelLanguage);
      if (protectedSetting) {
        valueControl.setAttribute("aria-describedby", "hardened-approval-conditional-description");
        valueControl.setAttribute("title", i18nText("configure.hardened.setting_approval", "Changing this setting may require physical on-panel approval when Hardened mode is enabled."));
      }
    }
    var ctl = el("div", { class: "fctl" }, f.readOnly ? [pip(f)] : [pip(f), valueControl]);
    // Anchor id so dashboard "edit" icons can deep-link straight to this setting.
    var dependencyDisabled = (f.key === "auto_brightness" || f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_response_percent") && !ambientLightSourceReady();
    return el("div", {
      class: "frow" + (f.available ? "" : " muted") + (dependencyDisabled ? " dependency-disabled" : ""),
      id: "cfg-" + f.key
    }, [label, ctl]);
  }

  function shouldRenderRow(f) {
    if ((f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_response_percent") && values.auto_brightness !== "true") return false;
    return true;
  }

  function radioJoined() {
    return !!(radio && radio.attributes && radio.attributes.joined === true);
  }

  function requestJoin(btn) {
    if (!radio || !radio.router_enabled || radioJoined()) return;
    if (!confirm(i18nText("configure.zigbee.join_confirm", "Enable Permit join in Zigbee2MQTT or ZHA first.\n\nThis will request Repeater mode and begin a new 15-minute joining period. It will not reboot or restart the panel.\n\nPermit join is enabled — request join?"))) return;
    btn.disabled = true;
    fetch("api/v1/radio/join", { method: "POST" })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (body) {
          if (!r.ok) throw (body.status || ("HTTP " + r.status));
          return body;
        });
      })
      .then(function () {
        joinCooldownUntil = Date.now() + 60000;
        radio.state = "starting";
        if (!radio.attributes) radio.attributes = {};
        radio.attributes.joined = null;
        render();
        pollRadio(0);
      })
      .catch(function (e) {
        btn.disabled = false;
        alert(i18nText("configure.zigbee.join_failed", "Join request failed ({error}).", { error: e }));
      });
  }

  function zigbeeJoinRow() {
    if (!radio || !radio.present) return null;
    var joined = radioJoined();
    var enabled = radio.router_enabled === true;
    var coolingDown = Date.now() < joinCooldownUntil;
    var request = el("button", { class: "pbtn", type: "button", text: i18nText("configure.zigbee.request_join", "Request join") });
    request.disabled = !enabled || joined || coolingDown;
    request.title = joined ? i18nText("configure.zigbee.already_joined", "This Zigbee router is already joined.")
      : !enabled ? i18nText("configure.zigbee.enable_first", "Turn on the Zigbee router switch and save first.")
      : coolingDown ? i18nText("configure.zigbee.recent_request", "A join request was sent recently.")
      : i18nText("configure.zigbee.request_help", "Request Repeater mode while coordinator permit-join is open.");
    request.onclick = function () { requestJoin(request); };
    return el("div", { class: "frow", id: "cfg-zigbee_join" }, [
      el("div", { class: "flabel" }, [
        el("span", { text: i18nText("configure.zigbee.join_network", "Join Zigbee network") }),
        el("small", { text: i18nText("configure.zigbee.join_help", "Open permit-join on your coordinator, then request Repeater mode.") }),
      ]),
      el("div", { class: "fctl" }, [request]),
    ]);
  }

  function focusHash() {
    if (hashJumpUntil < 0 || !location.hash) return;
    var target = document.getElementById(location.hash.slice(1));
    if (!target) return;
    if (!hashJumpUntil) {
      hashJumpUntil = Date.now() + 1400;
      target.classList.add("flash");
      setTimeout(function () { target.classList.remove("flash"); }, 1800);
    } else if (Date.now() > hashJumpUntil) {
      hashJumpUntil = -1;
      return;
    }
    // A jump computed on content-visibility placeholders lands short once the cards above render
    // (WebKit has no scroll anchoring to hide it). Scroll on exact layout, and repeat after each
    // render in the settle window until the user moves, since a render drops the exact layout; the
    // bounded release then keeps the target where the jump put it.
    var root = document.getElementById("cfg-groups");
    root.classList.add("config-viewport-anchored");
    target.scrollIntoView({ block: "center" });
    scheduleConfigExactLayoutRelease(root, configViewportAnchor(target));
  }

  // Per-card maturity badges: [text, css-modifier]. Applied to the card heading by render().
  // Logging lost its experimental badge after all three transports delivered marked probe records
  // AND real shipped log lines into a collector addressed by hostname. Display keeps its badge — that
  // work is still unvalidated.
  // The custom wake word guide, through the site's own redirect so the page can move.
  var WAKE_WORD_GUIDE_URL = "https://panel-assistant.io/go/custom-wake-words";
  var CARD_BADGES = {
    "Display": ["experimental", "exp"]
  };
  var CARD_NOTES = {
    "Sensors": "Home Assistant reporting",
    "Diagnostics": "Home Assistant reporting"
  };
  function groupTitle(group) {
    var titles = {
      "Identity": i18nText("configure.group.identity", "Identity"),
      "MQTT": "MQTT",
      "Behaviour": i18nText("configure.group.behaviour", "Behaviour"),
      "Auto-sleep": i18nText("configure.group.auto_sleep", "Auto-sleep"),
      "Display": i18nText("configure.group.display", "Display"),
      "Camera": i18nText("configure.group.camera", "Camera"),
      "System": i18nText("configure.group.system", "System"),
      "Sensors": i18nText("configure.group.sensors", "Sensors"),
      "Diagnostics": i18nText("configure.group.diagnostics", "Diagnostics"),
      "Logging": i18nText("configure.group.logging", "Logging"),
      "Voice": i18nText("configure.group.voice", "Voice"),
      "Home Assistant connection": i18nText("configure.group.ha_connection", "Home Assistant connection"),
      "Dashboard": i18nText("configure.group.dashboard", "Dashboard"),
      "Built-in renderer": i18nText("configure.group.builtin_renderer", "Built-in renderer")
    };
    return Object.prototype.hasOwnProperty.call(titles, group) ? titles[group] : group;
  }
  var BUILTIN_RENDERER_KEYS = {
    dashboard_entity_learning: true, dashboard_fullscreen: true, dashboard_native_kiosk: true,
    dashboard_idle_return_min: true, dashboard_zoom: true, dashboard_theme: true
  };
  var BUILTIN_RENDERER_ONLY_KEYS = { dashboard_idle_return_min: true };
  var HA_CONNECTION_KEYS = { ha_url: true, ha_token: true };
  var AUTO_SLEEP_KEYS = { auto_sleep_source: true, auto_sleep: true };
  var CONFIG_LAYOUT_KEYS = {
    "Identity": "configure-identity", "MQTT": "configure-mqtt", "Behaviour": "configure-behaviour",
    "Auto-sleep": "configure-auto-sleep",
    "Display": "configure-display", "System": "configure-system", "Sensors": "configure-sensors",
    "Diagnostics": "configure-diagnostics", "Logging": "configure-logging",
    "Home Assistant connection": "configure-ha-connection", "Dashboard": "configure-dashboard",
    "Built-in renderer": "configure-builtin-renderer"
  };
  function configLayoutKey(group) {
    return CONFIG_LAYOUT_KEYS[group] || ("configure-" + String(group).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 50));
  }
  function presentationGroup(f) {
    if (AUTO_SLEEP_KEYS[f.key]) return "Auto-sleep";
    if (f.group !== "Dashboard") return f.group;
    if (BUILTIN_RENDERER_KEYS[f.key]) return "Built-in renderer";
    if (HA_CONNECTION_KEYS[f.key]) return "Home Assistant connection";
    return "Dashboard";
  }

  var scheduleConfigColumnAlignment = window.CardColumnAlignment
    ? window.CardColumnAlignment.attach("cfg-groups")
    : function () {};

  function fieldsForConfigGroup(group) {
    return schema.filter(function (field) {
      return presentationGroup(field) === group && field.available &&
        (!BUILTIN_RENDERER_ONLY_KEYS[field.key] || !values.dashboard_package || values.dashboard_package === "builtin") &&
        (advanced || field.tier === "BASIC");
    });
  }

  function autoSleepCardSignature(fields) {
    return JSON.stringify([
      advanced,
      values.auto_sleep === "true",
      fields.map(function (field) {
        return [
          field.key,
          field.label,
          field.help,
          field.type,
          field.picker,
          field.readOnly,
          field.min,
          field.max,
          field.maxLength,
          field.options,
          shouldRenderRow(field),
          values[field.key],
          field.ha ? expose[field.key] !== false : null,
          Object.prototype.hasOwnProperty.call(applyPending, field.key),
          applyStalled[field.key] === true
        ];
      })
    ]);
  }

  function syncAutoSleepCardSignature() {
    var card = document.querySelector('[data-config-group="Auto-sleep"]');
    if (card) card.setAttribute("data-render-signature", autoSleepCardSignature(fieldsForConfigGroup("Auto-sleep")));
  }

  function reconcileConfigCards(root, cards) {
    Array.prototype.slice.call(root.children).forEach(function (child) {
      if (cards.indexOf(child) < 0) child.remove();
    });
    cards.forEach(function (card, index) {
      var current = root.children[index] || null;
      if (current !== card) root.insertBefore(card, current);
    });
  }

  // The single-column placeholder regime is defined exactly once, by info.css's container query on
  // `.cards`, which publishes `--single-column` on every card it covers. Reading that flag keeps this
  // in step with the CSS by construction. It replaces `matchMedia("(max-width: 857px)")`, which
  // restated a viewport threshold the CSS derived from a padding that same query changed — so the two
  // disagreed across 834-857px — and which could not see a scrollbar's width or the width of the Panel
  // Assistant sidebar iframe these pages now render inside.
  function configSingleColumnRegime(node) {
    if (!window.getComputedStyle) return true;
    return window.getComputedStyle(node).getPropertyValue("--single-column").trim() === "1";
  }

  function configViewportAnchor(node) {
    if (!node || !node.isConnected || typeof node.getBoundingClientRect !== "function") return null;
    if (!configSingleColumnRegime(node)) return null;
    var rect = node.getBoundingClientRect();
    var viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
    if (!isFinite(rect.top) || !isFinite(rect.bottom) || rect.height <= 0 || viewportHeight <= 0 ||
        rect.bottom <= 0 || rect.top >= viewportHeight) return null;
    return { node: node, top: rect.top };
  }

  function restoreConfigViewportAnchor(anchor) {
    if (!anchor || !anchor.node.isConnected || !window.scrollTo) return;
    // The browser may already have applied some native scroll anchoring while cards above this one
    // were reconciled. Compensate from the CURRENT scroll position so both mechanisms converge on
    // the same viewport coordinate instead of resetting scrollY to its stale pre-render value. A
    // scroll can itself change which one-column cards content-visibility considers onscreen, so
    // force and settle that layout synchronously before paint rather than correcting it later.
    for (var attempt = 0; attempt < 8; attempt += 1) {
      var top = anchor.node.getBoundingClientRect().top;
      var delta = top - anchor.top;
      if (!isFinite(delta) || Math.abs(delta) <= 0.5) return;
      var beforeY = window.pageYOffset || 0;
      window.scrollTo(window.pageXOffset || 0, beforeY + delta);
      if ((window.pageYOffset || 0) === beforeY) return;
    }
  }

  var configExactLayoutReleaseTimer = null;
  var configExactLayoutRoot = null;
  var configExactLayoutCompensationCancelled = false;
  function scheduleConfigExactLayoutRelease(root, anchor) {
    if (configExactLayoutReleaseTimer) clearTimeout(configExactLayoutReleaseTimer);
    configExactLayoutReleaseTimer = null;
    configExactLayoutRoot = anchor ? root : null;
    configExactLayoutCompensationCancelled = false;
    if (!anchor) return;
    configExactLayoutReleaseTimer = setTimeout(function () {
      configExactLayoutReleaseTimer = null;
      configExactLayoutRoot = null;
      if (!root.isConnected || !root.classList.contains("config-viewport-anchored")) return;
      root.classList.remove("config-viewport-anchored");
      if (!configExactLayoutCompensationCancelled) restoreConfigViewportAnchor(anchor);
      configExactLayoutCompensationCancelled = false;
    }, 1400);
  }

  function cancelConfigExactLayoutCompensation() {
    // Keep exact layout until the bounded release, but never let its final scroll correction compete
    // with navigation the user has started since the render.
    if (configExactLayoutReleaseTimer) configExactLayoutCompensationCancelled = true;
    if (hashJumpUntil > 0) hashJumpUntil = -1;
  }
  if (document.addEventListener) {
    ["pointerdown", "touchstart", "wheel", "keydown"].forEach(function (type) {
      document.addEventListener(type, cancelConfigExactLayoutCompensation, true);
    });
  }
  if (window.addEventListener) {
    window.addEventListener("pagehide", function () {
      if (configExactLayoutReleaseTimer) clearTimeout(configExactLayoutReleaseTimer);
      configExactLayoutReleaseTimer = null;
      configExactLayoutCompensationCancelled = true;
      var root = configExactLayoutRoot || document.getElementById("cfg-groups");
      configExactLayoutRoot = null;
      if (root) root.classList.remove("config-viewport-anchored");
    });
  }

  function render() {
    var root = document.getElementById("cfg-groups");
    var proximityCard = document.querySelector("#cfg-proximity-learning");
    var groups = [];
    schema.forEach(function (f) {
      var group = presentationGroup(f);
      if (groups.indexOf(group) < 0) groups.push(group);
    });
    function moveGroupTo(name, index) {
      var currentIndex = groups.indexOf(name);
      if (currentIndex < 0) return;
      groups.splice(currentIndex, 1);
      groups.splice(Math.min(index, groups.length), 0, name);
    }
    moveGroupTo("Home Assistant connection", 2);
    moveGroupTo("Dashboard", 3);
    moveGroupTo("Built-in renderer", 4);
    var behaviourIndex = groups.indexOf("Behaviour");
    if (behaviourIndex >= 0) moveGroupTo("Auto-sleep", behaviourIndex + 1);
    var loggingIndex = groups.indexOf("Logging");
    if (loggingIndex >= 0 && loggingIndex !== groups.length - 1) {
      groups.splice(loggingIndex, 1);
      groups.push("Logging");
    }
    var autoSleepFields = fieldsForConfigGroup("Auto-sleep");
    var nextAutoSleepSignature = autoSleepCardSignature(autoSleepFields);
    var retainedAutoSleepPanel = document.getElementById("auto-sleep-status");
    if (!retainedAutoSleepPanel || !retainedAutoSleepPanel.parentNode || typeof retainedAutoSleepPanel.contains !== "function") retainedAutoSleepPanel = null;
    var existingAutoSleepCard = retainedAutoSleepPanel && retainedAutoSleepPanel.closest('[data-config-group="Auto-sleep"]');
    var retainedAutoSleepCard = existingAutoSleepCard && values.auto_sleep === "true" &&
      existingAutoSleepCard.getAttribute("data-render-signature") === nextAutoSleepSignature ? existingAutoSleepCard : null;
    var retainedAutoSleepFocus = retainedAutoSleepPanel && retainedAutoSleepPanel.contains(document.activeElement) ? document.activeElement : null;
    var retainedAutoSleepScroll = retainedAutoSleepPanel && retainedAutoSleepPanel.querySelector(".auto-sleep-source-scroll");
    var retainedAutoSleepScrollTop = retainedAutoSleepScroll ? retainedAutoSleepScroll.scrollTop : 0;
    var retainedAutoSleepViewportAnchor = retainedAutoSleepCard ? configViewportAnchor(retainedAutoSleepPanel) : null;
    // Fresh off-screen cards normally use content-visibility's intrinsic placeholder until a later
    // frame. If Auto-sleep is already being viewed, that delayed replacement would relocate it after
    // this render has finished. Lay out this transaction's cards exactly; the narrow-screen lazy
    // optimization remains active whenever the auto-sleep panel is outside the viewport.
    root.classList.toggle("config-viewport-anchored", !!retainedAutoSleepViewportAnchor);
    var autoSleepParking = null;
    if (retainedAutoSleepPanel && !retainedAutoSleepCard) {
      // render() rebuilds unrelated Configure cards after asynchronous probes. Keep the activity
      // subtree connected while that happens so its chart, scroll and focus do not flash away.
      autoSleepParking = el("div", { hidden: "", "aria-hidden": "true" });
      root.parentNode.insertBefore(autoSleepParking, root.nextSibling);
      autoSleepParking.appendChild(retainedAutoSleepPanel);
    }
    if (haPickerCleanup) haPickerCleanup();
    haOauthButton = null; haOauthStatus = null; haOauthLinks = null;
    var shown = 0, explained = 0, desiredCards = [];
    groups.forEach(function (g) {
      var fields = fieldsForConfigGroup(g);
      if (!fields.length) {
        // The one group that explains itself when it has nothing to show: a missing Camera card is the
        // documented complaint, because nothing connected "my panel has a camera" to "set the flag".
        if (g !== "Camera" || !cameraGroupUnavailable()) return;
        loadCameraCapability();
        var absent = el("div", { class: "card" }, [
          el("h2", {}, [el("span", { text: groupTitle(g) })]),
          cameraUnavailableNode(),
        ]);
        absent.setAttribute("data-config-group", g);
        absent.setAttribute("data-layout-key", configLayoutKey(g));
        desiredCards.push(absent);
        explained += 1;
        return;
      }
      shown += fields.length;
      if (g === "Auto-sleep" && retainedAutoSleepCard) {
        desiredCards.push(retainedAutoSleepCard);
        return;
      }
      // Maturity badges on whole cards; Logging is intentionally no longer experimental.
      var h2kids = [el("span", { text: groupTitle(g) })];
      if (CARD_NOTES[g]) h2kids.push(el("small", { text: i18nText("configure.group.ha_reporting_note", " · Home Assistant reporting") }));
      var badge = CARD_BADGES[g];
      if (badge) h2kids.push(el("span", { class: "cardbadge " + badge[1], text: i18nText("configure.badge.experimental", "experimental") }));
      var card = el("div", { class: "card" }, [el("h2", {}, h2kids)]);
      card.setAttribute("data-config-group", g);
      card.setAttribute("data-layout-key", configLayoutKey(g));
      if (g === "Auto-sleep") card.setAttribute("data-render-signature", nextAutoSleepSignature);
      fields.forEach(function (f) {
        if (!shouldRenderRow(f)) return;
        card.appendChild(row(f));
        if (g === "Auto-sleep" && f.key === "auto_sleep") card.appendChild(autoSleepPrerequisiteNode());
        if (g === "Auto-sleep" && f.key === "auto_sleep" && values.auto_sleep === "true") {
          card.appendChild(retainedAutoSleepPanel || autoSleepPanel());
          if (!autoSleepStatus && !autoSleepLoading) setTimeout(loadAutoSleepData, 0);
        }
        if (g === "Home Assistant connection" && f.key === "ha_url") card.appendChild(haOAuthRow());
        if (f.key === "zigbee_router") {
          var join = zigbeeJoinRow();
          if (join) card.appendChild(join);
        }
      });
      if (g === "Display") {
        if (values.auto_brightness === "true" && ambientLightSourceConfigured()) card.appendChild(autoBrightnessPanel());
        if (!autoBrightStatus && !autoBrightLoading) loadAutoBrightnessData(false);
      }
      // Dashboard card action: clear the built-in renderer's browsing storage — the heal for a
      // corrupted localStorage/IndexedDB that survives reloads. Never logs the panel out (auth lives
      // in ha-paneld's config, not the WebView).
      if (g === "Built-in renderer") {
        var st = el("span", { class: "muted" });
        var clearStatusTimer = null;
        function setClearStatus(text, transient) {
          if (clearStatusTimer) clearTimeout(clearStatusTimer);
          clearStatusTimer = null;
          st.textContent = text;
          if (transient) clearStatusTimer = setTimeout(function () {
            clearStatusTimer = null;
            st.textContent = "";
          }, 4000);
        }
        var btn = el("button", {
          class: "pbtn", text: i18nText("configure.renderer.clear_storage", "Clear renderer storage"),
          "aria-describedby": "hardened-approval-description",
          title: i18nText("configure.hardened.action_approval", "Requires physical on-panel approval for this action when Hardened mode is enabled.")
        });
        btn.onclick = function () {
          setClearStatus(i18nText("configure.renderer.clearing", "Clearing…"), false);
          fetch("api/v1/dashboard/clear-storage", { method: "POST" })
            .then(function (r) { return approvalAwareJson(r).then(function () { return r; }); })
            .then(function (r) { setClearStatus(r.ok ? i18nText("configure.renderer.clear_requested", "Clear requested.") : i18nText("configure.error.http", "Failed (HTTP {status})", { status: r.status }), r.ok); })
            .catch(function (error) { setClearStatus(error && error.approvalRequired ? approvalMessage(error.body) : i18nText("configure.error.network", "Failed (network)"), false); });
        };
        card.appendChild(el("div", { class: "frow frow-action" }, [
          el("div", { class: "flabel" }, [
            el("span", {
              text: i18nText("configure.renderer.storage", "Renderer storage"), "data-hardened-approval": "",
              "aria-describedby": "hardened-approval-description",
              title: i18nText("configure.hardened.action_approval", "Requires physical on-panel approval for this action when Hardened mode is enabled.")
            }),
            el("small", { text: i18nText("configure.renderer.clear_storage_help", "Clear cached dashboard data. Keeps sign-in.") }),
          ]),
          el("div", { class: "fctl" }, [btn, st]),
        ]));
      }
      desiredCards.push(card);
    });
    var autoSleepCard = desiredCards.find(function (card) {
      return card.getAttribute("data-config-group") === "Auto-sleep";
    });
    var behaviourCard = desiredCards.find(function (card) {
      return card.getAttribute("data-config-group") === "Behaviour";
    });
    if (autoSleepCard && behaviourCard) {
      desiredCards.splice(desiredCards.indexOf(autoSleepCard), 1);
      desiredCards.splice(desiredCards.indexOf(behaviourCard) + 1, 0, autoSleepCard);
    }
    if (proximityCard) {
      var autoSleepCardIndex = desiredCards.findIndex(function (card) {
        return card.getAttribute("data-config-group") === "Auto-sleep";
      });
      var behaviourCardIndex = desiredCards.findIndex(function (card) {
        return card.getAttribute("data-config-group") === "Behaviour";
      });
      var presenceCardIndex = autoSleepCardIndex >= 0 ? autoSleepCardIndex + 1 : behaviourCardIndex + 1;
      desiredCards.splice(presenceCardIndex < 0 ? desiredCards.length : presenceCardIndex, 0, proximityCard);
    }
    // Reconcile by card instead of emptying the grid. In particular, an unchanged Auto-sleep card
    // never leaves the rendered tree while Home-dashboard and adaptive-brightness requests finish,
    // so low-end WebViews do not discard and repaint the large Auto-sleep chart raster.
    reconcileConfigCards(root, desiredCards);
    if (typeof window.repositionProximityLearningCard === "function") window.repositionProximityLearningCard();
    if (retainedAutoSleepPanel && retainedAutoSleepPanel.isConnected && !retainedAutoSleepCard) {
      updateAutoSleepSummary();
      updateAutoSleepHistory();
    }
    var anyContent = shown || explained;
    document.getElementById("cfg-status").style.display = anyContent ? "none" : "block";
    if (!anyContent) document.getElementById("cfg-status").textContent = i18nText("configure.empty", "No settings in this view.");
    if (retainedAutoSleepFocus && retainedAutoSleepFocus.isConnected && document.activeElement !== retainedAutoSleepFocus) {
      try { retainedAutoSleepFocus.focus({ preventScroll: true }); }
      catch (_) { retainedAutoSleepFocus.focus(); }
    }
    if (retainedAutoSleepScroll && retainedAutoSleepScroll.isConnected) retainedAutoSleepScroll.scrollTop = retainedAutoSleepScrollTop;
    if (autoSleepParking) autoSleepParking.remove();
    if (window.CardSizeMemory) {
      if (retainedAutoSleepViewportAnchor) window.CardSizeMemory.invalidate("cfg-groups");
      else window.CardSizeMemory.restore("cfg-groups");
    }
    restoreConfigViewportAnchor(retainedAutoSleepViewportAnchor);
    // Keep exact layout through the card-memory settle window. The bounded release restores normal
    // lazy rendering after the latest asynchronous result; direct user input cancels compensation so
    // a late timer can never pull the viewport away from a scroll or key navigation in progress.
    scheduleConfigExactLayoutRelease(root, retainedAutoSleepViewportAnchor);
    focusHash();
    scheduleConfigColumnAlignment();
  }

  window.cfgTab = function (adv) {
    advanced = adv;
    document.getElementById("tab-basic").classList.toggle("on", !adv);
    document.getElementById("tab-adv").classList.toggle("on", adv);
    render();
  };

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
    for (var i = 0; i < schema.length; i++) {
      var field = schema[i];
      if (!dirtyValues[field.key]) continue;
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
      var message = field.min != null && field.max != null
        ? i18nText("configure.validation.range", "{label} must be between {min} and {max}.", { label: field.label, min: field.min, max: field.max })
        : i18nText("configure.validation.invalid", "{label} has an invalid value.", { label: field.label });
      return { field: field, control: control, message: message };
    }
    return null;
  }

  window.cfgSave = function () {
    if (!dirty || saving) return;
    var msg = document.getElementById("cfg-msg");
    var invalid = firstInvalidDirtySetting();
    if (invalid) {
      msg.textContent = invalid.message;
      if (invalid.control.reportValidity) invalid.control.reportValidity();
      if (invalid.control.focus) invalid.control.focus();
      return;
    }
    saving = true;
    // Fence any mDNS suggestions computed against the pre-save broker/HA baseline.
    configDiscoveryRequest++;
    var submittedGeneration = editGeneration;
    updateSaveUi();
    var body = new URLSearchParams();
    var submittedValues = {}, submittedExpose = {};
    schema.forEach(function (f) {
      var v = values[f.key];
      if (dirtyValues[f.key]) {
        if (f.secret) { if (v) { body.set(f.key, v); submittedValues[f.key] = v; } } // blank password = keep current
        else if (v != null) { body.set(f.key, v); submittedValues[f.key] = v; }
      }
      if (f.ha && dirtyExpose[f.key]) {
        submittedExpose[f.key] = expose[f.key] !== false;
        body.set("ha_expose_" + f.key, submittedExpose[f.key] ? "true" : "false");
      }
    });
    msg.textContent = i18nText("configure.save.saving", "Saving…");
    fetch("api/v1/config", {
      method: "POST", headers: { "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    }).then(function (r) {
      return approvalAwareJson(r).then(function (body) {
        if (!r.ok) {
          var error = new Error(body && body.status === "saved-partial"
            ? i18nText("configure.save.failed", "Save failed.")
            : i18nText("configure.error.http", "Failed (HTTP {status})", { status: r.status }));
          error.configOutcome = body;
          throw error;
        }
        return body;
      });
    })
      .then(function (outcome) {
        var outcomeMessage = outcome && outcome.status === "saved-apply-pending"
          ? i18nText("configure.save.hardware_pending", "Saved desired value; hardware application is pending.")
          : i18nText("configure.save.saved", "Saved.");
        var autoBrightnessSourceChanged = Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness_ha_entity");
        if (autoBrightnessSourceChanged) beginAutoBrightnessSourceTransition(submittedValues.auto_brightness_ha_entity);
        Object.keys(submittedValues).forEach(function (key) { savedValues[key] = submittedValues[key]; });
        Object.keys(submittedExpose).forEach(function (key) { savedExpose[key] = submittedExpose[key]; });
        acceptSavedUiLanguage(submittedValues);
        recomputeDirty();
        loadRadio();
        restampConfigWatchBaseline();
        var autoSleepInputsChanged = ["auto_sleep", "auto_sleep_source", "ha_url", "ha_token"].some(function (key) {
          return Object.prototype.hasOwnProperty.call(submittedValues, key);
        });
        var haConnectionInputsChanged = ["ha_url", "ha_token"].some(function (key) {
          return Object.prototype.hasOwnProperty.call(submittedValues, key);
        });
        if (autoSleepInputsChanged) {
          invalidateAutoSleepData(haConnectionInputsChanged);
        }
        if (editGeneration !== submittedGeneration) {
          (outcome && outcome.pending || []).forEach(function (key) {
            if (Object.prototype.hasOwnProperty.call(submittedValues, key)) applyPending[key] = submittedValues[key];
          });
          scheduleApplyPendingPoll();
          saving = false;
          updateSaveUi();
          if (localeDocumentReloadPending && !dirty) {
            reloadForSavedLocale();
            return;
          }
          msg.textContent = dirty ?
            ((outcome && outcome.pending && outcome.pending.length) ?
              i18nText("configure.save.outcome_newer_changes", "{outcome} Newer changes still need saving.", { outcome: outcomeMessage })
              : i18nText("configure.save.saved_newer_changes", "Saved; newer changes still need saving.")) :
            outcomeMessage;
          if (autoSleepInputsChanged && values.auto_sleep === "true") setTimeout(loadAutoSleepData, 0);
          if (autoBrightnessSourceChanged) setTimeout(function () { loadAutoBrightnessData(true); }, 0);
          return;
        }
        saving = false;
        msg.textContent = i18nText("configure.save.saving", "Saving…"); clearDirty();
        // The optional Presence & wake card is server-rendered only while Wake on wave is enabled.
        if (localeDocumentReloadPending || Object.prototype.hasOwnProperty.call(submittedValues, "wake_on_wave")) {
          reloadForSavedLocale();
          return;
        }
        // Reload the form from the server, then land on a terminal message — don't leave a
        // "reconnecting…" string hanging (it reads as stuck even though the save is done).
        // Clear before load(): its render pass is the single owner that schedules enabled status.
        load(function (ok) {
          msg.textContent = ok ? outcomeMessage : i18nText("configure.save.reload_failed", "Saved (reload failed — refresh the page).");
          var autoBrightnessSettingChanged = Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness") ||
              Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness_minimum_percent") ||
              Object.prototype.hasOwnProperty.call(submittedValues, "auto_brightness_response_percent") ||
              autoBrightnessSourceChanged;
          if (autoBrightnessSourceChanged || (ok && autoBrightnessSettingChanged)) {
            loadAutoBrightnessData(true);
          }
          // Re-enabling keeps the last completed replay visible, but it must still validate status
          // and history in the background. invalidateAutoSleepData() deliberately retains that
          // settled snapshot, so render() cannot infer the refresh from a missing status object.
          if (ok && autoSleepInputsChanged && values.auto_sleep === "true") {
            setTimeout(loadAutoSleepData, 0);
          }
          if (ok && !(outcome && outcome.pending && outcome.pending.length)) {
            if (!keepSaveMessageVisible(outcomeMessage)) {
              setTimeout(function () { if (msg.textContent === outcomeMessage) msg.textContent = ""; }, 2500);
            }
          }
        }, haConnectionInputsChanged);
      })
      .catch(function (e) {
        saving = false;
        if (e && e.configOutcome && e.configOutcome.status === "saved-partial") {
          var applied = Array.isArray(e.configOutcome.applied) ? e.configOutcome.applied : [];
          var appliedValues = {};
          applied.forEach(function (key) {
            if (!Object.prototype.hasOwnProperty.call(submittedValues, key)) return;
            appliedValues[key] = submittedValues[key];
            savedValues[key] = submittedValues[key];
          });
          acceptSavedUiLanguage(appliedValues);
          if (localeDocumentReloadPending) {
            recomputeDirty();
            updateSaveUi();
            if (!dirty) {
              reloadForSavedLocale(e.message);
              return;
            }
          }
          load(function () {
            if (localeDocumentReloadPending) {
              reloadForSavedLocale(e.message);
              return;
            }
            msg.textContent = e.message;
            updateSaveUi();
          }, false);
        } else {
          msg.textContent = e && e.approvalRequired
            ? approvalMessage(e.body)
            : i18nText("configure.save.failed", "Save failed.");
          updateSaveUi();
        }
      });
  };

  function load(done, forceHaUserStatusRefresh, refreshAutoSleepPrerequisite) {
    ++haAreaSeedGeneration;
    ++schemaLanguageRequest;
    var schemaUrl = configSchemaUrl(haUserStatus.phase === "connected" ? haUserStatus.language : "");
    Promise.all([
      fetch(schemaUrl, { headers: { "Accept": "application/json" }, cache: "no-store" }).then(readLocalizedSchema),
      fetch("api/v1/config", { headers: { "Accept": "application/json" }, cache: "no-store" }).then(function (r) { return r.json(); }),
      // Installed launchable apps for the package pickers; tolerate failure (picker falls back to text).
      fetch("api/v1/apps").then(function (r) { return r.json(); }).catch(function () { return { apps: [] }; }),
    ]).then(function (res) {
      var previousHaUrl = values.ha_url;
      var previousHaConfigured = haAuth.configured === true;
      var previousHaOauth = haAuth.oauth === true;
      schema = res[0];
      values = res[1].settings || {};
      expose = res[1].ha_expose || {};
      haAuth = res[1].ha_auth || {};
      applyPending = res[1].apply_pending || {};
      applyStalled = stalledMap(res[1].apply_stalled);
      haAreaSeed = res[1].ha_area_catalog || null;
      haAreaUserOverride = res[1].ha_area_user_override === true;
      if (values.auto_sleep === "true") configCardExpected.autoSleep = true;
      if (haAuth.configured === true) configCardExpected.ha = true;
      var haConnectionChanged = forceHaUserStatusRefresh === true ||
        previousHaUrl !== values.ha_url ||
        previousHaConfigured !== (haAuth.configured === true) ||
        previousHaOauth !== (haAuth.oauth === true);
      // Keep an already-rendered identity stable across unrelated saves. A real HA connection edit
      // clears the old user's name and performs a fresh no-store current-user probe below.
      if (haConnectionChanged) {
        haUserStatus = { phase: "unknown" };
        autoSleepAreaGeneration++;
        autoSleepSourceUpdating = Object.create(null);
        invalidateAutoSleepData(true);
        autoSleepAssignedAreaName = "";
        autoSleepPrerequisiteRequest++;
        autoSleepPrerequisite = { eligible: false, phase: "checking", area_name: "" };
      }
      apps = (res[2] && res[2].apps) || [];
      rendererChoices = (res[2] && Array.isArray(res[2].renderers)) ? res[2].renderers : [];
      // Normalize bool values to the "true"/"false" strings the toggle compares against.
      schema.forEach(function (f) {
        if (f.type === "BOOL" && typeof values[f.key] === "boolean") values[f.key] = values[f.key] ? "true" : "false";
        if (values[f.key] != null && typeof values[f.key] !== "string") values[f.key] = String(values[f.key]);
        if (Object.prototype.hasOwnProperty.call(applyPending, f.key)) {
          var desired = applyPending[f.key];
          if (f.type === "BOOL" && typeof desired === "boolean") desired = desired ? "true" : "false";
          values[f.key] = typeof desired === "string" ? desired : String(desired);
        }
      });
      savedValues = Object.assign({}, values);
      savedExpose = Object.assign({}, expose);
      // A successful full reload replaces the form's local snapshot, so no previously tracked edit remains.
      clearDirty();
      render();
      loadDiscoverySuggestions();
      loadHomeDashboards();
      if (!done && Object.keys(applyPending).length) {
        document.getElementById("cfg-msg").textContent = applyPendingBannerText();
      }
      scheduleApplyPendingPoll();
      if (refreshAutoSleepPrerequisite !== false) scheduleAutoSleepPrerequisite();
      if (haConnectionChanged) loadHaUserStatus();
      configCardSourceReady("core");
      configCardGeometryChanged();
      consumeLocaleReloadMessage();
      if (done) done(true);
    }).catch(function (e) {
      document.getElementById("cfg-status").textContent = i18nText("configure.load_failed", "Could not load settings ({error}).", { error: e });
      consumeLocaleReloadMessage();
      if (done) done(false);
    });
  }

  function loadDiscoverySuggestions() {
    var request = ++configDiscoveryRequest;
    fetch("api/v1/config/discovery", { cache: "no-store" }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(function (suggestions) {
      // Never rebuild the form over an edit made while the bounded mDNS browse was in flight.
      if (request !== configDiscoveryRequest || dirty) return;
      var applied = false;
      ["mqtt_broker", "ha_url"].forEach(function (key) {
        var suggestion = suggestions && suggestions[key];
        if (!suggestion || values[key] || savedValues[key] || dirtyValues[key]) return;
        values[key] = String(suggestion);
        applied = true;
      });
      if (!applied) return;
      editGeneration++;
      recomputeDirty();
      render();
      configCardGeometryChanged();
    }).catch(function () {});
  }

  function loadHomeDashboards() {
    if (dirty || haAuth.configured !== true) return;
    var request = ++homeDashboardRequest;
    fetch("api/v1/config/home-dashboards", { cache: "no-store" }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(function (body) {
      if (request !== homeDashboardRequest || dirty) return;
      var next = (body && body.items || []).map(function (dashboard) {
        return {
          path: String(dashboard && dashboard.path || "").trim(),
          title: String(dashboard && dashboard.title || "").trim(),
          icon: String(dashboard && dashboard.icon || "").trim(),
          group: String(dashboard && dashboard.group || "dashboard").trim(),
        };
      }).filter(function (dashboard) { return dashboard.path; });
      var nextDefault = {
        explicit: !!(body && body.default && body.default.explicit),
        path: String(body && body.default && body.default.path || "").trim(),
      };
      var nextQueried = !!(body && body.queried);
      var changed = next.length !== homeDashboardItems.length || next.some(function (dashboard, index) {
        var previous = homeDashboardItems[index];
        return !previous || dashboard.path !== previous.path || dashboard.title !== previous.title ||
          dashboard.icon !== previous.icon || dashboard.group !== previous.group;
      }) || nextDefault.explicit !== homeDashboardDefault.explicit || nextDefault.path !== homeDashboardDefault.path ||
        nextQueried !== homeDashboardQueried;
      if (!changed) return;
      homeDashboardItems = next;
      homeDashboardDefault = nextDefault;
      homeDashboardQueried = nextQueried;
      render();
      configCardGeometryChanged();
    }).catch(function () {});
  }

  // Fetched once per page load; a 503 (no Home Assistant connection, or the coordinator lane not wired
  // up yet) is remembered as false rather than retried on every render, and the picker degrades to the
  // raw JSON textarea.
  function loadVoicePipelines() {
    if (voicePipelinesCatalog !== null) return;
    var request = ++voicePipelinesRequest;
    fetch("api/v1/voice/pipelines", { cache: "no-store" }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(function (body) {
      if (request !== voicePipelinesRequest) return;
      voicePipelinesCatalog = (body && Array.isArray(body.pipelines)) ? body.pipelines : [];
      render();
    }).catch(function () {
      if (request !== voicePipelinesRequest) return;
      voicePipelinesCatalog = false;
      render();
    });
  }

  function loadVoiceWakeWords() {
    if (voiceWakeWordsCatalog !== null) return;
    var request = ++voiceWakeWordsRequest;
    fetch("api/v1/voice/wake-words", { cache: "no-store" }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(function (body) {
      if (request !== voiceWakeWordsRequest) return;
      voiceWakeWordsCatalog = (body && Array.isArray(body.wake_words)) ? body.wake_words : [];
      render();
    }).catch(function () {
      if (request !== voiceWakeWordsRequest) return;
      voiceWakeWordsCatalog = false;
      render();
    });
  }

  // Reads the two files, sends them to the panel, and shows what the panel said. A model the panel's
  // engine refuses is not added, and one of the same name that was already there stays as it was.
  function importVoiceWakeWord(manifestFile, modelFile) {
    Promise.all([manifestFile.text(), modelFile.arrayBuffer()]).then(function (parts) {
      var bytes = new Uint8Array(parts[1]), binary = "";
      for (var i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
      return fetch("api/v1/voice/wake-words", {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: modelFile.name, manifest: parts[0], model: btoa(binary) }),
      });
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (body) { return { ok: r.ok, body: body }; });
    }).then(function (result) {
      voiceWakeWordImportStatus = result.ok
        ? i18nText("configure.voice.import_done", "Imported {wake_word}.", { wake_word: String(result.body.wake_word || result.body.id || "") })
        : i18nText("configure.voice.import_failed", "Import failed: {reason}", { reason: String(result.body.error || "") });
      voiceWakeWordsCatalog = null;
      loadVoiceWakeWords();
    }).catch(function () {
      voiceWakeWordImportStatus = i18nText("configure.voice.import_failed", "Import failed: {reason}", { reason: "" });
      render();
    });
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
    return applyStalled[key] === true
      ? i18nText("configure.save.hardware_unavailable", "Saved. This panel has no way to apply it yet; it will be applied if that changes.")
      : i18nText("configure.save.hardware_pending", "Saved desired value; hardware application is pending.");
  }

  function applyPendingBannerText() {
    var waiting = [], stalled = [];
    Object.keys(applyPending).forEach(function (key) {
      (applyStalled[key] === true ? stalled : waiting).push(key);
    });
    var parts = [];
    if (waiting.length) {
      parts.push(i18nText("configure.save.waiting_to_apply", "Saved settings waiting to apply: {settings}.", {
        settings: waiting.join(", ")
      }));
    }
    if (stalled.length) {
      parts.push(i18nText("configure.save.unavailable_to_apply", "Saved, but this panel has no way to apply them yet: {settings}.", {
        settings: stalled.join(", ")
      }));
    }
    return parts.join(" ");
  }

  function scheduleApplyPendingPoll() {
    if (applyPendingTimer) clearTimeout(applyPendingTimer);
    applyPendingTimer = null;
    if (!Object.keys(applyPending).length) return;
    applyPendingTimer = setTimeout(function () {
      fetch("api/v1/config", { headers: { "Accept": "application/json" }, cache: "no-store" })
        .then(function (response) { if (!response.ok) throw response.status; return response.json(); })
        .then(function (body) {
          var next = body.apply_pending || {};
          var nextStalled = stalledMap(body.apply_stalled);
          var currentKeys = Object.keys(applyPending), nextKeys = Object.keys(next);
          var changed = currentKeys.length !== nextKeys.length || nextKeys.some(function (key) {
            return !Object.prototype.hasOwnProperty.call(applyPending, key) ||
              String(applyPending[key]) !== String(next[key]) ||
              (nextStalled[key] === true) !== (applyStalled[key] === true);
          });
          if (!changed) return;
          var changedKeys = currentKeys.concat(nextKeys).filter(function (key, index, keys) {
            return keys.indexOf(key) === index;
          });
          if (!dirty) {
            load(function (ok) {
              if (ok && !nextKeys.length) {
                document.getElementById("cfg-msg").textContent = i18nText("configure.save.now_applied", "Saved settings are now applied.");
              }
            }, false, false);
            return;
          }
          applyPending = next;
          applyStalled = nextStalled;
          changedKeys.forEach(function (key) {
            var row = document.getElementById("cfg-" + key);
            var label = row && row.querySelector(".flabel");
            if (!label) return;
            var status = label.querySelector(".apply-pending-status");
            if (Object.prototype.hasOwnProperty.call(next, key)) {
              var statusText = applyPendingStatusText(key);
              if (!status) label.appendChild(el("small", { class: "apply-pending-status", text: statusText }));
              else if (status.textContent !== statusText) status.textContent = statusText;
            } else if (status) status.remove();
          });
          syncAutoSleepCardSignature();
          if (!nextKeys.length) {
            var msg = document.getElementById("cfg-msg");
            msg.textContent = i18nText("configure.save.applied_newer_changes", "Saved settings are now applied; newer changes still need saving.");
          }
        })
        .catch(function () {})
        .then(scheduleApplyPendingPoll);
    }, 3000);
  }

  function loadRadio() {
    return fetch("api/v1/radio").then(function (r) { return r.json(); }).then(function (body) {
      radio = body && body.present ? body : null;
      render();
      configCardSourceReady("radio");
      configCardGeometryChanged();
      return radio;
    }).catch(function () {
      radio = null;
      render();
      configCardGeometryInvalid();
      return null;
    });
  }

  function pollRadio(n) {
    if (joinPollTimer) clearTimeout(joinPollTimer);
    loadRadio().then(function () {
      if (radioJoined() || !radio || !radio.router_enabled || n >= 180) return;
      joinPollTimer = setTimeout(function () { pollRadio(n + 1); }, 5000);
    });
  }

  function handleHaOAuthResult(status) {
      haOauthAuthorizationUrl = "";
      if (haOauthLinks) {
        haOauthLinks.hidden = true;
        var openLink = haOauthLinks.querySelector("a");
        if (openLink) openLink.removeAttribute("href");
      }
      if (status === "success") {
        haAuth = { configured: true, oauth: true };
        if (haOauthTargetUrl) {
          values.ha_url = haOauthTargetUrl;
          savedValues.ha_url = haOauthTargetUrl;
        }
        // Browser sign-in supersedes any manually typed token still present in this form.
        values.ha_token = "";
        savedValues.ha_token = "";
        haOauthTargetUrl = "";
        autoSleepAreaGeneration++;
        autoSleepSourceUpdating = Object.create(null);
        invalidateAutoSleepData(true);
        autoSleepAssignedAreaName = "";
        autoSleepPrerequisiteRequest++;
        autoSleepPrerequisite = { eligible: false, phase: "checking", area_name: "" };
        recomputeDirty(); updateSaveUi(); render();
        scheduleAutoSleepPrerequisite();
        if (values.auto_sleep === "true") setTimeout(loadAutoSleepData, 0);
        loadHaUserStatus();
        document.getElementById("cfg-msg").textContent = i18nText("configure.oauth.configured", "Home Assistant configured.");
      } else {
        haOauthTargetUrl = "";
        setHaOauthStatus(i18nText("configure.oauth.not_completed", "Sign-in was not completed. Start again."), false);
        syncHaOAuthAvailability();
      }
  }

  if ("BroadcastChannel" in window) {
    var haOauthChannel = new BroadcastChannel("ha-paneld-ha-oauth");
    // Node-backed asset tests expose BroadcastChannel too; do not let its listener hold that process open.
    if (haOauthChannel.unref) haOauthChannel.unref();
    haOauthChannel.addEventListener("message", function (event) {
      if (event.data && (event.data.status === "success" || event.data.status === "failure")) {
        handleHaOAuthResult(event.data.status);
      }
    });
  }

  if (window.addEventListener) {
    document.addEventListener("input", queueDirtyUiReconcile, true);
    document.addEventListener("change", queueDirtyUiReconcile, true);
    document.addEventListener("click", queueDirtyUiReconcile, true);
    window.addEventListener("focus", scheduleAutoSleepPrerequisite);
    document.addEventListener("visibilitychange", function () {
      if (document.visibilityState === "visible") scheduleAutoSleepPrerequisite();
    });
  }

  load(null, true);
  loadRadio();
})();
