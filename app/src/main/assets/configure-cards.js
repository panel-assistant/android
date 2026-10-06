// Configure page-specific connection, camera, radio and voice cards.
(function (cfg) {
  "use strict";


  function validHaUrlForOAuth() {
    try {
      var url = new URL(String(cfg.values.ha_url || "").trim());
      return (url.protocol === "http:" || url.protocol === "https:") && !!url.hostname &&
        !url.username && !url.password && !url.search && !url.hash;
    } catch (_) { return false; }
  }

  function syncHaOAuthAvailability() {
    if (!cfg.haOauthButton) return;
    cfg.haOauthButton.disabled = !validHaUrlForOAuth();
    cfg.haOauthButton.title = cfg.haOauthButton.disabled ? cfg.i18nText("configure.oauth.valid_url_first", "Enter a valid Home Assistant URL first.") : "";
  }

  function copyText(value) {
    if (navigator.clipboard && navigator.clipboard.writeText) return navigator.clipboard.writeText(value);
    var input = cfg.el("textarea", { "aria-hidden": "true" });
    input.value = value; input.style.position = "fixed"; input.style.left = "-9999px";
    document.body.appendChild(input); input.select();
    var copied = document.execCommand("copy");
    document.body.removeChild(input);
    return copied ? Promise.resolve() : Promise.reject(new Error("copy unavailable"));
  }

  function startHaOAuth() {
    if (!cfg.haOauthButton || !validHaUrlForOAuth()) return;
    var target = String(cfg.values.ha_url || "").trim().replace(/\/+$/, "");
    // Choosing browser sign-in supersedes a manually typed token immediately. This also prevents a
    // private-window completion, which cannot signal the ordinary browser context, from being overwritten
    // by that stale form value on a later unrelated save.
    cfg.values.ha_token = "";
    cfg.savedValues.ha_token = "";
    var tokenInput = document.querySelector("#cfg-ha_token input");
    if (tokenInput) tokenInput.value = "";
    cfg.recomputeDirty(); cfg.updateSaveUi();
    cfg.haOauthButton.disabled = true;
    setHaOauthStatus(cfg.i18nText("configure.oauth.starting", "Starting sign-in…"), false);
    cfg.haOauthAuthorizationUrl = "";
    cfg.haOauthTargetUrl = "";
    cfg.haOauthLinks.hidden = true;
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
            ? cfg.i18nText("configure.oauth.valid_url_first", "Enter a valid Home Assistant URL first.")
            : cfg.i18nText("configure.oauth.start_failed", "Could not start sign-in."));
          failure.localizedMessage = failure.message;
          throw failure;
        }
        return body.authorization_url;
      });
    }).then(function (authorizationUrl) {
      cfg.haOauthAuthorizationUrl = authorizationUrl;
      cfg.haOauthTargetUrl = target;
      var openLink = cfg.haOauthLinks.querySelector("a");
      openLink.href = authorizationUrl;
      cfg.haOauthLinks.hidden = false;
      setHaOauthStatus(cfg.i18nText("configure.oauth.link_ready", "Sign-in link ready. Open it normally or copy it into a private window."), false);
    }).catch(function (error) {
      setHaOauthStatus(error && error.localizedMessage
        ? error.localizedMessage
        : cfg.i18nText("configure.oauth.start_failed", "Could not start sign-in."), false);
    }).then(function () { syncHaOAuthAvailability(); });
  }

  function haConnectionStatusText() {
    if (cfg.haUserStatus.phase === "connected") {
      return cfg.haUserStatus.display_name
        ? cfg.i18nText("configure.oauth.connected_as", "Connected as {name}", { name: cfg.haUserStatus.display_name })
        : cfg.i18nText("configure.oauth.connected", "Connected");
    }
    if (cfg.haUserStatus.phase === "rejected") return cfg.i18nText("configure.oauth.rejected", "Sign-in rejected — reconnect to Home Assistant");
    if (cfg.haUserStatus.phase === "unavailable") {
      return cfg.i18nText("configure.oauth.status_unavailable", "{method} · status unavailable", {
        method: cfg.haAuth.oauth ? cfg.i18nText("configure.oauth.method_oauth", "OAuth configured") : cfg.i18nText("configure.oauth.method_token", "Long-lived token configured")
      });
    }
    return cfg.haAuth.oauth ? cfg.i18nText("configure.oauth.method_oauth", "OAuth configured")
      : cfg.haAuth.configured ? cfg.i18nText("configure.oauth.method_token", "Long-lived token configured")
      : cfg.i18nText("configure.oauth.not_configured", "Not configured");
  }

  function setHaOauthStatus(text, connected) {
    if (!cfg.haOauthStatus) return;
    cfg.haOauthStatus.textContent = text;
    cfg.haOauthStatus.classList.toggle("connected", connected === true);
  }

  function renderHaConnectionStatus() {
    setHaOauthStatus(haConnectionStatusText(), cfg.haUserStatus.phase === "connected");
  }

  function loadHaUserStatus() {
    var request = ++cfg.haUserStatusRequest;
    if (!cfg.haAuth.configured) {
      cfg.haUserStatus = { phase: "not_configured" };
      renderHaConnectionStatus();
      return;
    }
    var succeeded = false;
    fetch("api/v1/ha/oauth/status", { headers: { "Accept": "application/json" }, cache: "no-store" })
      .then(function (response) { if (!response.ok) throw response.status; return response.json(); })
      .then(function (body) {
        if (request !== cfg.haUserStatusRequest) return;
        cfg.haUserStatus = body || { phase: "unavailable" };
        succeeded = true;
      })
      .catch(function () {
        if (request === cfg.haUserStatusRequest) { cfg.haUserStatus = { phase: "unavailable" };
          if (typeof window !== "undefined" && window.configCardSizeGeometryInvalid) window.configCardSizeGeometryInvalid(); }
      })
      .then(function () {
        if (request === cfg.haUserStatusRequest) { renderHaConnectionStatus(); if (succeeded && typeof window !== "undefined" && window.configCardSizeSourceReady) {
          window.configCardSizeSourceReady("ha");if (window.configCardSizeGeometryChanged) window.configCardSizeGeometryChanged(); }
          if (succeeded) cfg.reloadSchemaForHaLanguage(); }
      });
  }

  function haOAuthRow() {
    cfg.haOauthButton = cfg.el("button", {
      class: "pbtn", type: "button", text: cfg.haAuth.configured ? cfg.i18nText("configure.oauth.reconnect", "Reconnect") : cfg.i18nText("configure.oauth.connect", "Connect")
    });
    cfg.haOauthButton.addEventListener("click", startHaOAuth);
    cfg.haOauthStatus = cfg.el("div", {
      class: "ha-oauth-status",
      text: haConnectionStatusText()
    });
    renderHaConnectionStatus();
    var openLink = cfg.el("a", {
      class: "pbtn", target: "_blank", rel: "noopener noreferrer", referrerpolicy: "no-referrer", text: cfg.i18nText("configure.oauth.open_sign_in", "Open sign-in")
    });
    var copyButton = cfg.el("button", { class: "pbtn", type: "button", text: cfg.i18nText("configure.oauth.copy_link", "Copy link") });
    copyButton.addEventListener("click", function () {
      copyText(cfg.haOauthAuthorizationUrl).then(function () {
        setHaOauthStatus(cfg.i18nText("configure.oauth.link_copied", "Sign-in link copied."), false);
      }).catch(function () { setHaOauthStatus(cfg.i18nText("configure.oauth.copy_failed", "Could not copy the link."), false); });
    });
    cfg.haOauthLinks = cfg.el("div", { class: "ha-oauth-links" }, [openLink, copyButton]);
    cfg.haOauthLinks.hidden = !cfg.haOauthAuthorizationUrl;
    if (cfg.haOauthAuthorizationUrl) openLink.href = cfg.haOauthAuthorizationUrl;
    var guidance = !cfg.haAuth.configured
      ? cfg.i18nText("configure.oauth.enter_url", "Enter the Home Assistant URL above, then connect this panel.")
      : !cfg.haAuth.oauth ? cfg.i18nText("configure.oauth.browser_recommended", "Browser sign-in is recommended; the long-lived token remains available as an advanced fallback.") : "";
    var row = cfg.el("div", { class: "frow ha-oauth-row", id: "cfg-ha-oauth" }, [
      cfg.el("div", { class: "flabel" }, [
        cfg.el("span", { text: cfg.i18nText("configure.oauth.browser_sign_in", "Browser sign-in") }),
        cfg.haOauthStatus,
        cfg.el("small", { text: cfg.i18nText("configure.oauth.sign_in_help", "Sign in from this computer. To sign in as another user, copy the link into a private window.") }),
        cfg.EMBEDDED ? cfg.el("small", { class: "ha-oauth-guidance", text: cfg.i18nText("configure.oauth.panel_reachability", "To finish sign-in, this browser must reach the panel's address. If it cannot, open Configure from the panel's network or sign in on the panel.") }) : null,
        guidance ? cfg.el("small", { class: "ha-oauth-guidance", text: guidance }) : null
      ]),
      cfg.el("div", { class: "fctl ha-oauth-actions" }, [cfg.haOauthButton, cfg.haOauthLinks])
    ]);
    syncHaOAuthAvailability();
    return row;
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
      .then(function (d) { cameraCapability = d; cfg.render(); })
      .catch(function () { /* the block stays on its generic wording */ })
      .then(function () { cameraCapabilityLoading = false; });
  }

  // True only when the panel does not offer the camera at all.
  function cameraGroupUnavailable() {
    var camera = cfg.schema.filter(function (f) { return cfg.presentationGroup(f) === "Camera"; });
    return camera.length > 0 && camera.every(function (f) { return !f.available; });
  }

  function cameraUnavailableNode() {
    var reason = cameraCapability && cameraCapability.state === "absent" ? cameraCapability.fault_detail : null;
    var text;
    if (reason === "suppressed_by_profile") {
      text = cfg.i18nText("configure.camera.suppressed", "This panel's device profile sets hardware.camera to false, so the camera is not offered. Edit the profile to offer it.");
    } else if (reason === "undetermined") {
      text = cfg.i18nText("configure.camera.undetermined", "The panel is still checking whether it has a camera. This is not yet an answer about the hardware.");
    } else {
      text = cfg.i18nText("configure.camera.not_enumerated", "This panel reports no camera. If it has one, declare hardware.camera in its device profile, alongside an LED the panel can light while the camera is open.");
    }
    return cfg.el("div", { class: "camera-unavailable", role: "status", text: text });
  }

  // What the panel's microphone is and what its own capture check found (GET api/v1/voice/microphone),
  // so the Voice card says why voice is quiet instead of vanishing or failing silently. Re-read every few
  // seconds while the page is visible on a panel with a microphone, through failed requests too, and at
  // once when the page becomes visible again, so a mute switch pressed meanwhile shows within seconds.
  var voiceMicrophone = null, voiceMicrophoneAt = 0, voiceMicrophoneLoading = false, voiceMicrophoneTimer = null, voiceMicrophoneWatched = false;
  function loadVoiceMicrophone() {
    if (voiceMicrophoneLoading || Date.now() - voiceMicrophoneAt < 3000) return;
    if (!voiceMicrophoneWatched) {
      voiceMicrophoneWatched = true;
      document.addEventListener("visibilitychange", function () { if (!document.hidden) rereadVoiceMicrophone(); });
    }
    voiceMicrophoneLoading = true;
    fetch("api/v1/voice/microphone", { headers: { "Accept": "application/json" }, cache: "no-store" })
      .then(function (r) { if (!r.ok) throw r.status; return r.json(); })
      .then(function (d) {
        var changed = JSON.stringify(d) !== JSON.stringify(voiceMicrophone);
        voiceMicrophone = d;
        if (changed) cfg.render();
      })
      .catch(function () { /* the card keeps what it last knew; the next read retries */ })
      .then(function () {
        voiceMicrophoneLoading = false;
        voiceMicrophoneAt = Date.now();
        // A panel with no microphone has nothing to re-read; a hidden page waits for visibilitychange.
        var absent = voiceMicrophone && voiceMicrophone.presence === "absent" && voiceMicrophone.check !== "running";
        clearTimeout(voiceMicrophoneTimer);
        if (!absent && !document.hidden) voiceMicrophoneTimer = setTimeout(rereadVoiceMicrophone, 3000);
      });
  }
  function rereadVoiceMicrophone() { voiceMicrophoneAt = 0; loadVoiceMicrophone(); }

  // True only when the panel does not offer voice at all.
  function voiceGroupUnavailable() {
    var voice = cfg.schema.filter(function (f) { return cfg.presentationGroup(f) === "Voice"; });
    return voice.length > 0 && voice.every(function (f) { return !f.available; });
  }

  // Why voice is not listening, or null when nothing needs saying.
  function voiceMicrophoneNode() {
    loadVoiceMicrophone();
    var status = voiceMicrophone, text = null;
    if (voiceGroupUnavailable()) {
      text = cfg.i18nText("configure.voice.microphone_absent", "This panel reports no microphone, or its device profile sets hardware.microphone to false, so voice is not offered.");
    } else if (status && status.muted === true) {
      text = cfg.i18nText("configure.voice.microphone_muted", "Microphone muted. The panel hears nothing until its microphone is unmuted.");
    } else if (status && status.check === "silent") {
      text = cfg.i18nText("configure.voice.microphone_silent", "The microphone recorded only silence when the panel checked it, so the panel is not listening. Try another audio source, or turn the voice assistant off and on to check again.");
    } else if (status && status.check === "no_audio") {
      text = cfg.i18nText("configure.voice.microphone_no_audio", "The microphone delivered no audio when the panel checked it, so the panel is not listening. Turn the voice assistant off and on to check again.");
    }
    return text ? cfg.el("div", { class: "camera-unavailable", role: "status", text: text }) : null;
  }

  function radioJoined() {
    return !!(cfg.radio && cfg.radio.attributes && cfg.radio.attributes.joined === true);
  }

  function requestJoin(btn) {
    if (!cfg.radio || !cfg.radio.router_enabled || radioJoined()) return;
    if (!confirm(cfg.i18nText("configure.zigbee.join_confirm", "Enable Permit join in Zigbee2MQTT or ZHA first.\n\nThis will request Repeater mode and begin a new 15-minute joining period. It will not reboot or restart the panel.\n\nPermit join is enabled — request join?"))) return;
    btn.disabled = true;
    fetch("api/v1/radio/join", { method: "POST" })
      .then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (body) {
          if (!r.ok) throw (body.status || ("HTTP " + r.status));
          return body;
        });
      })
      .then(function () {
        cfg.joinCooldownUntil = Date.now() + 60000;
        cfg.radio.state = "starting";
        if (!cfg.radio.attributes) cfg.radio.attributes = {};
        cfg.radio.attributes.joined = null;
        cfg.render();
        pollRadio(0);
      })
      .catch(function (e) {
        btn.disabled = false;
        alert(cfg.i18nText("configure.zigbee.join_failed", "Join request failed ({error}).", { error: e }));
      });
  }

  function zigbeeJoinRow() {
    if (!cfg.radio || !cfg.radio.present) return null;
    var joined = radioJoined();
    var enabled = cfg.radio.router_enabled === true;
    var coolingDown = Date.now() < cfg.joinCooldownUntil;
    var request = cfg.el("button", { class: "pbtn", type: "button", text: cfg.i18nText("configure.zigbee.request_join", "Request join") });
    request.disabled = !enabled || joined || coolingDown;
    request.title = joined ? cfg.i18nText("configure.zigbee.already_joined", "This Zigbee router is already joined.")
      : !enabled ? cfg.i18nText("configure.zigbee.enable_first", "Turn on the Zigbee router switch and save first.")
      : coolingDown ? cfg.i18nText("configure.zigbee.recent_request", "A join request was sent recently.")
      : cfg.i18nText("configure.zigbee.request_help", "Request Repeater mode while coordinator permit-join is open.");
    request.onclick = function () { requestJoin(request); };
    return cfg.el("div", { class: "frow", id: "cfg-zigbee_join" }, [
      cfg.el("div", { class: "flabel" }, [
        cfg.el("span", { text: cfg.i18nText("configure.zigbee.join_network", "Join Zigbee network") }),
        cfg.el("small", { text: cfg.i18nText("configure.zigbee.join_help", "Open permit-join on your coordinator, then request Repeater mode.") }),
      ]),
      cfg.el("div", { class: "fctl" }, [request]),
    ]);
  }

  // Per-card maturity badges: [text, css-modifier]. Applied to the card heading by render().
  // The custom wake word guide, through the site's own redirect so the page can move.
  var WAKE_WORD_GUIDE_URL = "https://panel-assistant.io/go/custom-wake-words";

  function loadHomeDashboards() {
    if (cfg.dirty || cfg.haAuth.configured !== true) return;
    var request = ++cfg.homeDashboardRequest;
    fetch("api/v1/config/home-dashboards", { cache: "no-store" }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(function (body) {
      if (request !== cfg.homeDashboardRequest || cfg.dirty) return;
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
      var changed = next.length !== cfg.homeDashboardItems.length || next.some(function (dashboard, index) {
        var previous = cfg.homeDashboardItems[index];
        return !previous || dashboard.path !== previous.path || dashboard.title !== previous.title ||
          dashboard.icon !== previous.icon || dashboard.group !== previous.group;
      }) || nextDefault.explicit !== cfg.homeDashboardDefault.explicit || nextDefault.path !== cfg.homeDashboardDefault.path ||
        nextQueried !== cfg.homeDashboardQueried;
      if (!changed) return;
      cfg.homeDashboardItems = next;
      cfg.homeDashboardDefault = nextDefault;
      cfg.homeDashboardQueried = nextQueried;
      cfg.render();
      cfg.configCardGeometryChanged();
    }).catch(function () {});
  }

  // Fetched once per page load; a 503 (no Home Assistant connection, or the coordinator lane not wired
  // up yet) is remembered as false rather than retried on every render, and the picker degrades to the
  // raw JSON textarea.
  function loadVoicePipelines() {
    if (cfg.voicePipelinesCatalog !== null) return;
    var request = ++cfg.voicePipelinesRequest;
    fetch("api/v1/voice/pipelines", { cache: "no-store" }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(function (body) {
      if (request !== cfg.voicePipelinesRequest) return;
      cfg.voicePipelinesCatalog = (body && Array.isArray(body.pipelines)) ? body.pipelines : [];
      cfg.render();
    }).catch(function () {
      if (request !== cfg.voicePipelinesRequest) return;
      cfg.voicePipelinesCatalog = false;
      cfg.render();
    });
  }

  function loadVoiceWakeWords() {
    if (cfg.voiceWakeWordsCatalog !== null) return;
    var request = ++cfg.voiceWakeWordsRequest;
    fetch("api/v1/voice/wake-words", { cache: "no-store" }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(function (body) {
      if (request !== cfg.voiceWakeWordsRequest) return;
      cfg.voiceWakeWordsCatalog = (body && Array.isArray(body.wake_words)) ? body.wake_words : [];
      cfg.render();
    }).catch(function () {
      if (request !== cfg.voiceWakeWordsRequest) return;
      cfg.voiceWakeWordsCatalog = false;
      cfg.render();
    });
  }

  // The import of a wake word the owner trained, across the whole Voice card: the microWakeWord .json
  // manifest and .tflite model are picked together, and the panel checks them with its own engine
  // before offering the word. The way to train one is one tap away.
  function voiceWakeWordImportRow() {
    var filesInput = cfg.el("input", { type: "file", multiple: "", accept: ".json,.tflite,application/json" });
    var importButton = cfg.el("button", { type: "button", class: "pbtn", text: cfg.i18nText("configure.voice.import_button", "Import") });
    importButton.addEventListener("click", function () {
      var files = Array.prototype.slice.call(filesInput.files || []);
      var modelFile = files.find(function (file) { return /\.tflite$/i.test(file.name); });
      var manifestFile = files.find(function (file) { return /\.json$/i.test(file.name); });
      if (!manifestFile || !modelFile) {
        cfg.voiceWakeWordImportStatus = cfg.i18nText("configure.voice.import_choose_files", "Choose the .json manifest and the .tflite model first.");
        cfg.render();
        return;
      }
      importButton.disabled = true;
      importVoiceWakeWord(manifestFile, modelFile);
    });
    return cfg.el("div", { class: "frow frow-action voice-wake-word-import" }, [
      cfg.el("div", { class: "flabel" }, [
        cfg.el("span", { text: cfg.i18nText("configure.voice.import_wake_word", "Import trained wake word") }),
        cfg.el("small", { text: cfg.i18nText("configure.voice.import_help", "Import a microWakeWord model you trained: its .json manifest and .tflite file.") }),
        cfg.el("small", {}, [cfg.el("a", {
          class: "voice-wake-word-guide", href: WAKE_WORD_GUIDE_URL, target: "_blank", rel: "noopener noreferrer",
          text: cfg.i18nText("configure.voice.import_guide", "How to train your own wake word")
        })]),
        cfg.voiceWakeWordImportStatus ? cfg.el("small", { class: "voice-wake-word-import-status", text: cfg.voiceWakeWordImportStatus }) : null,
      ]),
      cfg.el("div", { class: "fctl" }, [filesInput, importButton]),
    ]);
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
      cfg.voiceWakeWordImportStatus = result.ok
        ? cfg.i18nText("configure.voice.import_done", "Imported {wake_word}.", { wake_word: String(result.body.wake_word || result.body.id || "") })
        : cfg.i18nText("configure.voice.import_failed", "Import failed: {reason}", { reason: String(result.body.error || "") });
      cfg.voiceWakeWordsCatalog = null;
      loadVoiceWakeWords();
    }).catch(function () {
      cfg.voiceWakeWordImportStatus = cfg.i18nText("configure.voice.import_failed", "Import failed: {reason}", { reason: "" });
      cfg.render();
    });
  }

  function loadRadio() {
    return fetch("api/v1/radio").then(function (r) { return r.json(); }).then(function (body) {
      cfg.radio = body && body.present ? body : null;
      cfg.render();
      cfg.configCardSourceReady("radio");
      cfg.configCardGeometryChanged();
      return cfg.radio;
    }).catch(function () {
      cfg.radio = null;
      cfg.render();
      cfg.configCardGeometryInvalid();
      return null;
    });
  }

  function pollRadio(n) {
    if (cfg.joinPollTimer) clearTimeout(cfg.joinPollTimer);
    loadRadio().then(function () {
      if (radioJoined() || !cfg.radio || !cfg.radio.router_enabled || n >= 180) return;
      cfg.joinPollTimer = setTimeout(function () { pollRadio(n + 1); }, 5000);
    });
  }

  function handleHaOAuthResult(status) {
      cfg.haOauthAuthorizationUrl = "";
      if (cfg.haOauthLinks) {
        cfg.haOauthLinks.hidden = true;
        var openLink = cfg.haOauthLinks.querySelector("a");
        if (openLink) openLink.removeAttribute("href");
      }
      if (status === "success") {
        cfg.haAuth = { configured: true, oauth: true };
        if (cfg.haOauthTargetUrl) {
          cfg.values.ha_url = cfg.haOauthTargetUrl;
          cfg.savedValues.ha_url = cfg.haOauthTargetUrl;
        }
        // Browser sign-in supersedes any manually typed token still present in this form.
        cfg.values.ha_token = "";
        cfg.savedValues.ha_token = "";
        cfg.haOauthTargetUrl = "";
        cfg.autoSleepAreaGeneration++;
        cfg.autoSleepSourceUpdating = Object.create(null);
        cfg.invalidateAutoSleepData(true);
        cfg.autoSleepAssignedAreaName = "";
        cfg.autoSleepPrerequisiteRequest++;
        cfg.autoSleepPrerequisite = { eligible: false, phase: "checking", area_name: "" };
        cfg.recomputeDirty(); cfg.updateSaveUi(); cfg.render();
        cfg.scheduleAutoSleepPrerequisite();
        if (cfg.values.auto_sleep === "true") setTimeout(cfg.loadAutoSleepData, 0);
        loadHaUserStatus();
        document.getElementById("cfg-msg").textContent = cfg.i18nText("configure.oauth.configured", "Home Assistant configured.");
      } else {
        cfg.haOauthTargetUrl = "";
        setHaOauthStatus(cfg.i18nText("configure.oauth.not_completed", "Sign-in was not completed. Start again."), false);
        syncHaOAuthAvailability();
      }
  }



  cfg.handleHaOAuthResult = handleHaOAuthResult;
  cfg.syncHaOAuthAvailability = syncHaOAuthAvailability;
  cfg.loadHaUserStatus = loadHaUserStatus;
  cfg.haOAuthRow = haOAuthRow;
  cfg.loadCameraCapability = loadCameraCapability;
  cfg.cameraGroupUnavailable = cameraGroupUnavailable;
  cfg.cameraUnavailableNode = cameraUnavailableNode;
  cfg.voiceGroupUnavailable = voiceGroupUnavailable;
  cfg.voiceMicrophoneNode = voiceMicrophoneNode;
  cfg.zigbeeJoinRow = zigbeeJoinRow;
  cfg.loadHomeDashboards = loadHomeDashboards;
  cfg.loadVoicePipelines = loadVoicePipelines;
  cfg.loadVoiceWakeWords = loadVoiceWakeWords;
  cfg.voiceWakeWordImportRow = voiceWakeWordImportRow;
  cfg.loadRadio = loadRadio;
})(window.ConfigurePage);
