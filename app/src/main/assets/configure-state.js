// Shared Configure state, localization and DOM helpers. Load first.
window.ConfigurePage = {};
(function (cfg) {
  "use strict";
  // Basic is the default view: each card shows its BASIC rows and offers its own advanced rows.
  cfg.schema = [];
  cfg.values = {};
  cfg.expose = {};
  cfg.haAuth = {};
  cfg.applyPending = {};
  cfg.applyStalled = {};
  cfg.applyPendingTimer = null;
  cfg.advanced = false;
  cfg.dirty = false;
  cfg.saving = false;
  cfg.editGeneration = 0;
  cfg.configDiscoveryRequest = 0;
  cfg.schemaLanguageRequest = 0;
  cfg.apps = [];
  cfg.rendererChoices = [];
  cfg.radio = null;
  cfg.savedValues = {};
  cfg.savedExpose = {};
  cfg.dirtyValues = Object.create(null);
  cfg.dirtyExpose = Object.create(null);
  cfg.joinCooldownUntil = 0;
  cfg.joinPollTimer = null;
  cfg.hashJumpUntil = 0;
  // Viewer-local presentation state. None of it is a setting, so none of it marks the form dirty.
  cfg.descriptions = true;
  cfg.revealedGroups = Object.create(null);
  cfg.filterText = "";
  cfg.CONFIG_VIEW_STORAGE_KEY = "ha-paneld.configure.view.v1";
  cfg.haSourceItems = [];
  cfg.homeDashboardItems = [];
  cfg.homeDashboardRequest = 0;
  cfg.homeDashboardQueried = false;
  // Assist pipeline catalogue for the voice_pipelines picker. null = not fetched yet, false = the
  // endpoint returned an error/503 (degrade to the raw JSON textarea), an array = the fetched catalogue.
  cfg.voicePipelinesCatalog = null;
  cfg.voicePipelinesRequest = 0;
  // The wake words this panel holds, bundled and imported, for the voice_wake_words picker; same
  // null / false / list convention as the pipeline catalogue.
  cfg.voiceWakeWordsCatalog = null;
  cfg.voiceWakeWordsRequest = 0;
  cfg.voiceWakeWordImportStatus = "";
  // A per-load, owner-safe seed supplied by the config response. The endpoint is still fetched every
  // render so a failed query, credential change or Home Assistant area edit can recover immediately.
  cfg.haAreaSeed = null;
  cfg.haAreaSeedGeneration = 0;
  cfg.haAreaCatalogRequest = 0;
  cfg.haAreaUserOverride = false;
  cfg.homeDashboardDefault = { explicit: false, path: "" };
  // Sentinel for the "Custom…" option. Not a reachable path: a real dashboard root must match
  // ^[a-z0-9][a-z0-9_-]*$, which no leading underscore can satisfy, so it can never collide.
  cfg.CUSTOM_DASHBOARD = "__custom__";
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
  cfg.haPickerCleanups = [];
  cfg.haPickerCleanup = function () {
    cfg.haPickerCleanups.splice(0).forEach(function (cleanup) { cleanup(); });
  };
  cfg.autoBrightStatus = null;
  cfg.autoBrightHistory = null;
  cfg.autoBrightLoading = false;
  cfg.autoBrightMessage = "";
  cfg.autoBrightHistoryTimer = null;
  cfg.autoBrightRefreshTimer = null;
  cfg.autoBrightTransitionTimer = null;
  cfg.autoBrightRequest = 0;
  cfg.autoBrightSourceTransition = false;
  cfg.autoBrightTransitionAttempts = 0;
  cfg.autoBrightTransitionSource = "";
  cfg.AUTO_BRIGHTNESS_REFRESH_MS = 5 * 60 * 1000;
  cfg.AUTO_BRIGHTNESS_TRANSITION_POLL_MS = 1000;
  cfg.AUTO_BRIGHTNESS_TRANSITION_MAX_ATTEMPTS = 10;
  cfg.autoSleepStatus = null;
  cfg.autoSleepLoading = false;
  cfg.autoSleepRequest = 0;
  cfg.autoSleepHistory = null;
  cfg.autoSleepHistoryLoading = false;
  cfg.autoSleepHistoryError = "";
  cfg.autoSleepHistoryRequest = 0;
  cfg.autoSleepHistoryHours = 24;
  cfg.autoSleepSourceUpdating = Object.create(null);
  cfg.autoSleepHistoryWaiting = false;
  cfg.autoSleepHistoryWaitingMessage = "";
  cfg.autoSleepHistoryReadyTimer = null;
  cfg.autoSleepReadinessDelayMs = 1000;
  cfg.autoSleepHistoryRetryDelayMs = 5000;
  cfg.autoSleepPrerequisite = { eligible: false, phase: "checking", area_name: "" };
  cfg.autoSleepPrerequisiteRequest = 0;
  cfg.autoSleepPrerequisiteTimer = null;
  cfg.autoSleepAssignedAreaName = "";
  cfg.autoSleepAreaGeneration = 0;
  cfg.haOauthButton = null;
  cfg.haOauthStatus = null;
  cfg.haOauthLinks = null;
  cfg.haOauthAuthorizationUrl = "";
  cfg.haOauthTargetUrl = "";
  cfg.haUserStatus = { phase: "unknown" };
  cfg.haUserStatusRequest = 0;
  cfg.localeDocumentReloadPending = false;
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
      : wireValue === "touch"
        ? i18nText("configure.auto_sleep.source_touch", "Touch inactivity")
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
  cfg.EMBEDDED = !!(document.body && document.body.hasAttribute("data-embedded"));

  function storeBrowserLanguage(value) {
    if (cfg.EMBEDDED) return;
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
    if (cfg.EMBEDDED) return "";
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
    cfg.localeDocumentReloadPending = true;
  }

  function reloadForSavedLocale(message) {
    if (message) {
      try { window.sessionStorage.setItem(localeReloadMessageKey, message); } catch (_) {}
    }
    window.location.reload();
  }

  function reloadSavedLocaleWhenSettled() {
    if (!cfg.localeDocumentReloadPending || cfg.dirty || cfg.saving || localeDocumentReloadScheduled) return;
    localeDocumentReloadScheduled = true;
    setTimeout(function () {
      localeDocumentReloadScheduled = false;
      if (cfg.localeDocumentReloadPending && !cfg.dirty && !cfg.saving) reloadForSavedLocale();
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
    if (cfg.values.ui_language !== "auto" || admittedBrowserLanguage(browserLanguageChoice()) ||
        cfg.haUserStatus.phase !== "connected" || !validLanguageTag(cfg.haUserStatus.language)) return;
    var request = ++cfg.schemaLanguageRequest;
    var generation = cfg.editGeneration;
    fetch(configSchemaUrl(cfg.haUserStatus.language), { headers: { "Accept": "application/json" }, cache: "no-store" })
      .then(function (response) {
        return response.json();
      })
      .then(function (nextSchema) {
        if (request !== cfg.schemaLanguageRequest || cfg.dirty || cfg.editGeneration !== generation ||
            !Array.isArray(nextSchema)) return;
        cfg.schema = nextSchema;
        cfg.render();
        cfg.configCardGeometryChanged();
      })
      .catch(function () {});
  }
  cfg.HARDENED_APPROVAL_SETTING_KEYS = {
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

  cfg.wellFormedDashboardPath = wellFormedDashboardPath;
  cfg.dashboardRootOf = dashboardRootOf;
  cfg.i18nText = i18nText;
  cfg.localizedPlaceholder = localizedPlaceholder;
  cfg.localizedEnumOption = localizedEnumOption;
  cfg.localizedPageHref = localizedPageHref;
  cfg.configSchemaUrl = configSchemaUrl;
  cfg.acceptSavedUiLanguage = acceptSavedUiLanguage;
  cfg.reloadForSavedLocale = reloadForSavedLocale;
  cfg.reloadSavedLocaleWhenSettled = reloadSavedLocaleWhenSettled;
  cfg.consumeLocaleReloadMessage = consumeLocaleReloadMessage;
  cfg.readLocalizedSchema = readLocalizedSchema;
  cfg.reloadSchemaForHaLanguage = reloadSchemaForHaLanguage;
  cfg.approvalMessage = approvalMessage;
  cfg.approvalAwareJson = approvalAwareJson;
  cfg.el = el;
})(window.ConfigurePage);
