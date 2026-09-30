// Configure auto-sleep prerequisite, status and activity replay.
(function (cfg) {
  "use strict";


  function autoSleepHuman(value) {
    var token = String(value == null ? "unknown" : value).toLowerCase();
    var labels = {
      unknown: cfg.i18nText("configure.auto_sleep.value.unknown", "Unknown"),
      disabled: cfg.i18nText("configure.auto_sleep.value.disabled", "Disabled"),
      authenticating: cfg.i18nText("configure.auto_sleep.value.authenticating", "Authenticating"),
      discovering: cfg.i18nText("configure.auto_sleep.value.discovering", "Discovering"),
      learning: cfg.i18nText("configure.auto_sleep.value.learning", "Learning"),
      connecting: cfg.i18nText("configure.auto_sleep.value.connecting", "Connecting"),
      synchronizing: cfg.i18nText("configure.auto_sleep.value.synchronizing", "Synchronizing"),
      live: cfg.i18nText("configure.auto_sleep.value.live", "Live"),
      no_area: cfg.i18nText("configure.auto_sleep.value.no_area", "No area"),
      no_credible_sources: cfg.i18nText("configure.auto_sleep.value.no_credible_sources", "No credible sources"),
      no_included_sources: cfg.i18nText("configure.auto_sleep.value.no_included_sources", "No included sources"),
      auth_failed: cfg.i18nText("configure.auto_sleep.value.auth_failed", "Authentication failed"),
      discovery_failed: cfg.i18nText("configure.auto_sleep.value.discovery_failed", "Discovery failed"),
      reconnecting: cfg.i18nText("configure.auto_sleep.value.reconnecting", "Reconnecting"),
      stopped: cfg.i18nText("configure.auto_sleep.value.stopped", "Stopped"),
      no_sources_configured: cfg.i18nText("configure.auto_sleep.value.no_sources_configured", "No sources configured"),
      all_sources_unavailable: cfg.i18nText("configure.auto_sleep.value.all_sources_unavailable", "All sources unavailable"),
      source_active: cfg.i18nText("configure.auto_sleep.value.source_active", "Source active"),
      partial_source_loss: cfg.i18nText("configure.auto_sleep.value.partial_source_loss", "Partial source loss"),
      source_activity_lease: cfg.i18nText("configure.auto_sleep.value.source_activity_lease", "Source activity lease"),
      touch_activity: cfg.i18nText("configure.auto_sleep.value.touch_activity", "Touch activity"),
      source_loss_wake: cfg.i18nText("configure.auto_sleep.value.source_loss_wake", "Woken on source loss"),
      proximity_activity: cfg.i18nText("configure.auto_sleep.value.proximity_activity", "Proximity activity"),
      lease_expired: cfg.i18nText("configure.auto_sleep.value.lease_expired", "Lease expired")
    };
    return labels[token] || token.replace(/_/g, " ").replace(/^./, function (c) { return c.toUpperCase(); });
  }

  function autoSleepSummaryModel(status) {
    status = status || {};
    if (autoSleepUsesTouch()) {
      var seconds = Number(cfg.values.auto_sleep_touch_delay_seconds || 30);
      var touchLines = [
        cfg.i18nText("configure.auto_sleep.source_touch", "Touch inactivity"),
        cfg.i18nText("configure.auto_sleep.fixed_delay", "Screen off after {count} seconds without touch.", { count: seconds })
      ];
      return { lines: touchLines, accessible: touchLines.join(" · ") };
    }
    if (autoSleepUsesPanel()) {
      var localLines = [
        cfg.i18nText("configure.auto_sleep.source_panel", "This panel’s proximity sensor"),
        cfg.i18nText("configure.auto_sleep.phase", "Phase: {phase}", { phase: autoSleepHuman(status.phase || "unknown") }),
        cfg.i18nText("configure.auto_sleep.reason", "Reason: {reason}", { reason: autoSleepHuman(status.reason || "unknown") }),
        cfg.i18nText("configure.auto_sleep.panel_setup_help", "Uses calibrated presence at this panel. Set up proximity first; automatic sleep pauses if the sensor is unavailable.")
      ];
      return { lines: localLines, accessible: localLines.join(" · ") };
    }
    var areaName = status.area_name != null ? status.area_name : status.areaName;
    var area = String(areaName || "").trim() || cfg.i18nText("configure.auto_sleep.not_learned", "not learned");
    var leaseMs = status.learned_lease_ms != null ? status.learned_lease_ms : status.learnedLeaseMs;
    var lease = typeof leaseMs === "number" && isFinite(leaseMs)
      ? cfg.i18nText("configure.duration.minutes", "{count} min", { count: Math.round(leaseMs / 60000) })
      : cfg.i18nText("configure.auto_sleep.not_learned", "not learned");
    var count = status.source_count != null ? status.source_count : status.sourceCount;
    var suppressed = status.manual_suppression === true || status.manualSuppression === true;
    var phase = autoSleepHuman(status.phase);
    var reason = autoSleepHuman(status.reason);
    var sources = count == null ? 0 : count;
    var override = suppressed ? cfg.i18nText("configure.state.active", "active") : cfg.i18nText("configure.state.inactive", "inactive");
    return {
      lines: [
        cfg.i18nText("configure.auto_sleep.area", "Home Assistant Area: {area}", { area: area }),
        cfg.i18nText("configure.auto_sleep.phase", "Phase: {phase}", { phase: phase }),
        cfg.i18nText("configure.auto_sleep.reason", "Reason: {reason}", { reason: reason }),
        cfg.i18nText("configure.auto_sleep.delay_sources", "Delay: {delay} · Sources: {count}", { delay: lease, count: sources }),
        cfg.i18nText("configure.auto_sleep.manual_override", "Manual override: {state}", { state: override })
      ],
      accessible: cfg.i18nText("configure.auto_sleep.summary_accessible", "Home Assistant Area: {area} · Phase: {phase} · Reason: {reason} · Learned delay: {delay} · Sources: {count} · Manual screen override: {state}",
        { area: area, phase: phase, reason: reason, delay: lease, count: sources, state: override })
    };
  }

  function autoSleepLoadingSummaryModel() {
    var loading = cfg.i18nText("configure.loading", "Loading…");
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
    return cfg.el("small", { id: "auto-sleep-summary", class: "auto-sleep-summary", "aria-hidden": "true" }, [
      cfg.el("span", { class: "auto-sleep-summary-line" }),
      cfg.el("span", { class: "auto-sleep-summary-line" }),
      cfg.el("span", { class: "auto-sleep-summary-line" }),
      cfg.el("span", { class: "auto-sleep-summary-line" }),
      cfg.el("span", { class: "auto-sleep-summary-line" })
    ]);
  }

  function autoSleepSummaryAnnouncementNode() {
    return cfg.el("span", {
      id: "auto-sleep-summary-announcement", class: "sr-only", role: "status", "aria-live": "polite", "aria-atomic": "true"
    });
  }

  function autoSleepUsesPanel() { return cfg.values.auto_sleep_source === "panel"; }
  function autoSleepUsesTouch() { return cfg.values.auto_sleep_source === "touch"; }
  function autoSleepUsesHa() { return !autoSleepUsesPanel() && !autoSleepUsesTouch(); }

  function autoSleepPrerequisiteText() {
    if (autoSleepUsesPanel()) return cfg.i18nText("configure.auto_sleep.panel_setup_help", "Uses calibrated presence at this panel. Set up proximity first; automatic sleep pauses if the sensor is unavailable.");
    if (autoSleepUsesTouch()) return cfg.i18nText("configure.auto_sleep.touch_help", "Uses touch activity on this panel. Home Assistant and presence sensors are not required.");
    var phase = String(cfg.autoSleepPrerequisite.phase || "unavailable").toLowerCase();
    var areaName = cfg.autoSleepPrerequisite.area_name != null ? cfg.autoSleepPrerequisite.area_name : cfg.autoSleepPrerequisite.areaName;
    if (phase === "checking") return cfg.i18nText("configure.auto_sleep.area_checking", "Checking this panel’s Home Assistant Area…");
    if (cfg.autoSleepPrerequisite.eligible === true && phase === "assigned") return cfg.i18nText("configure.auto_sleep.area", "Home Assistant Area: {area}", {
      area: String(areaName || "").trim() || cfg.i18nText("configure.auto_sleep.assigned", "Assigned")
    });
    if (phase === "unassigned") return cfg.i18nText("configure.auto_sleep.assign_area_first", "Assign this panel to a Home Assistant Area before enabling Auto sleep.");
    if (phase === "auth_failed") return cfg.i18nText("configure.auto_sleep.reconnect_first", "Reconnect Home Assistant before enabling Auto sleep.");
    return cfg.i18nText("configure.auto_sleep.area_check_failed", "Could not check this panel’s Home Assistant Area. Check the Home Assistant connection.");
  }

  function autoSleepPrerequisiteNode() {
    return cfg.el("div", {
      id: "auto-sleep-prerequisite-status",
      class: "auto-sleep-prerequisite" + (cfg.autoSleepPrerequisite.eligible === true ? " eligible" : ""),
      role: "status", "aria-live": "polite", text: autoSleepPrerequisiteText()
    });
  }

  function updateAutoSleepPrerequisiteUi() {
    var status = document.getElementById("auto-sleep-prerequisite-status");
    if (status) {
      var next = autoSleepPrerequisiteText();
      if (status.textContent !== next) status.textContent = next;
      status.classList.toggle("eligible", cfg.autoSleepPrerequisite.eligible === true);
    }
    var toggle = document.querySelector("#cfg-auto_sleep [role=switch]");
    if (!toggle) return;
    var blocked = autoSleepUsesHa() && cfg.values.auto_sleep !== "true" && cfg.autoSleepPrerequisite.eligible !== true;
    toggle.classList.toggle("blocked", blocked);
    toggle.setAttribute("aria-disabled", blocked ? "true" : "false");
    toggle.setAttribute("tabindex", "0");
  }

  function convergeAutoSleepOffForMissingArea() {
    if (!autoSleepUsesHa()) return;
    if (cfg.values.auto_sleep !== "true") return;
    cfg.values.auto_sleep = "false";
    cfg.savedValues.auto_sleep = "false";
    invalidateAutoSleepData(true);
    cfg.recomputeDirty();
    cfg.updateSaveUi();
    var toggle = document.querySelector("#cfg-auto_sleep [role=switch]");
    if (toggle) {
      toggle.classList.remove("on");
      toggle.setAttribute("aria-checked", "false");
    }
    var panel = document.getElementById("auto-sleep-status");
    if (panel) panel.remove();
  }

  function loadAutoSleepPrerequisite() {
    if (!autoSleepUsesHa()) { updateAutoSleepPrerequisiteUi(); return; }
    if (!cfg.schema.some(function (field) { return field.key === "auto_sleep" && field.available; })) return;
    if (cfg.autoSleepPrerequisiteTimer) { clearTimeout(cfg.autoSleepPrerequisiteTimer); cfg.autoSleepPrerequisiteTimer = null; }
    // Focus/visibility refreshes are background validation. Keep a settled Area verdict visible
    // while they run; changing it to Checking… and straight back produces a conspicuous colour/text
    // flash without giving the user any useful new state.
    if (String(cfg.autoSleepPrerequisite.phase || "checking").toLowerCase() === "checking") {
      cfg.autoSleepPrerequisite = { eligible: false, phase: "checking", area_name: "" };
      updateAutoSleepPrerequisiteUi();
    }
    var request = ++cfg.autoSleepPrerequisiteRequest;
    fetch("api/v1/auto-sleep/prerequisite", { cache: "no-store" })
      .then(function (response) {
        if (!response.ok) throw new Error("HTTP " + response.status);
        return response.json();
      })
      .then(function (body) {
        if (request !== cfg.autoSleepPrerequisiteRequest) return;
        cfg.autoSleepPrerequisite = body || { eligible: false, phase: "unavailable", area_name: "" };
        var phase = String(cfg.autoSleepPrerequisite.phase || "unavailable").toLowerCase();
        var nextAreaName = cfg.autoSleepPrerequisite.area_name != null ? cfg.autoSleepPrerequisite.area_name : cfg.autoSleepPrerequisite.areaName;
        nextAreaName = phase === "assigned" && cfg.autoSleepPrerequisite.eligible === true ? String(nextAreaName || "").trim() : "";
        var initialAreaMismatch = nextAreaName && !cfg.autoSleepAssignedAreaName && (
          cfg.autoSleepStatus && !autoSleepAreaMatchesName(cfg.autoSleepStatus, nextAreaName) ||
          cfg.autoSleepHistory && !autoSleepAreaMatchesName(cfg.autoSleepHistory, nextAreaName)
        );
        if (nextAreaName && (initialAreaMismatch || cfg.autoSleepAssignedAreaName && nextAreaName !== cfg.autoSleepAssignedAreaName)) {
          cfg.autoSleepAssignedAreaName = nextAreaName;
          cfg.autoSleepAreaGeneration++;
          cfg.autoSleepSourceUpdating = Object.create(null);
          // The completed replay is still useful as a stable placeholder while the new Area is
          // discovered. Fence its requests, but keep its DOM beneath the busy overlay until the
          // replacement history is complete; clearing it here makes the whole card collapse and
          // expand through an empty state before every Area refresh.
          invalidateAutoSleepData();
          cfg.autoSleepHistoryWaiting = cfg.values.auto_sleep === "true";
          cfg.autoSleepHistoryWaitingMessage = cfg.autoSleepHistoryWaiting ? cfg.i18nText("configure.auto_sleep.history_preparing", "Preparing activity history…") : "";
          updateAutoSleepHistory();
          if (cfg.values.auto_sleep === "true") loadAutoSleepData();
        } else if (nextAreaName) {
          cfg.autoSleepAssignedAreaName = nextAreaName;
        }
        if (phase === "unassigned") {
          if (cfg.autoSleepAssignedAreaName) {
            cfg.autoSleepAreaGeneration++;
            cfg.autoSleepSourceUpdating = Object.create(null);
          }
          cfg.autoSleepAssignedAreaName = "";
          convergeAutoSleepOffForMissingArea();
        }
      })
      .catch(function () {
        if (request !== cfg.autoSleepPrerequisiteRequest) return;
        cfg.autoSleepPrerequisite = { eligible: false, phase: "unavailable", area_name: "" };
      })
      .then(function () {
        if (request === cfg.autoSleepPrerequisiteRequest) updateAutoSleepPrerequisiteUi();
      });
  }

  function scheduleAutoSleepPrerequisite() {
    if (cfg.autoSleepPrerequisiteTimer) clearTimeout(cfg.autoSleepPrerequisiteTimer);
    cfg.autoSleepPrerequisiteTimer = setTimeout(function () {
      cfg.autoSleepPrerequisiteTimer = null;
      loadAutoSleepPrerequisite();
    }, 150);
  }

  // Under <base href="/"> a bare fragment link would resolve against the base and leave this page, so the
  // link sets the fragment itself and cancels the navigation.
  function proximitySetupLink() {
    var link = cfg.el("a", { href: "#cfg-proximity-learning", class: "pbtn", text: cfg.i18nText("configure.auto_sleep.setup_proximity", "Set up proximity on panel") });
    link.addEventListener("click", function (ev) {
      ev.preventDefault();
      location.hash = "cfg-proximity-learning";
    });
    return link;
  }

  function autoSleepPanel() {
    if (autoSleepUsesTouch()) {
      var touchSummary = autoSleepSummaryNode();
      var touchAnnouncement = autoSleepSummaryAnnouncementNode();
      setAutoSleepSummary(touchSummary, touchAnnouncement, autoSleepSummaryModel());
      return cfg.el("div", { class: "autobright-panel", id: "auto-sleep-status" }, [touchSummary, touchAnnouncement]);
    }
    if (autoSleepUsesPanel()) {
      var localSummary = autoSleepSummaryNode();
      var localAnnouncement = autoSleepSummaryAnnouncementNode();
      setAutoSleepSummary(localSummary, localAnnouncement, autoSleepSummaryModel(cfg.autoSleepStatus));
      return cfg.el("div", { class: "autobright-panel", id: "auto-sleep-status" }, [
        cfg.el("strong", { text: cfg.i18nText("configure.auto_sleep.source_panel", "This panel’s proximity sensor") }),
        localSummary, localAnnouncement,
        proximitySetupLink()
      ]);
    }
    if (!cfg.autoSleepStatus && !cfg.autoSleepLoading) {
      cfg.autoSleepHistoryWaiting = true;
      cfg.autoSleepHistoryWaitingMessage = cfg.i18nText("configure.auto_sleep.history_preparing", "Preparing activity history…");
    }
    var windows = cfg.el("div", { class: "auto-sleep-windows", role: "group", "aria-label": cfg.i18nText("configure.auto_sleep.history_period", "Activity history period") });
    [6, 24, 48].forEach(function (hours) {
      var button = cfg.el("button", { class: "pbtn", type: "button", text: hours + "h", "data-hours": hours, "aria-pressed": hours === cfg.autoSleepHistoryHours ? "true" : "false" });
      button.onclick = function () {
        if (autoSleepHistoryBusy() || hours === cfg.autoSleepHistoryHours) return;
        cfg.autoSleepHistoryHours = hours;
        if (autoSleepHistoryReady(cfg.autoSleepStatus)) loadAutoSleepHistory();
        else updateAutoSleepHistory();
      };
      windows.appendChild(button);
    });
    var chart = cfg.el("div", { id: "auto-sleep-chart", class: "auto-sleep-history", "aria-describedby": "auto-sleep-chart-description" }, [
      cfg.el("div", { class: "auto-sleep-chart-content" }),
      cfg.el("div", { class: "auto-sleep-loading-overlay", role: "status", "aria-live": "polite", text: cfg.i18nText("configure.auto_sleep.history_preparing", "Preparing activity history…") })
    ]);
    var summary = autoSleepSummaryNode();
    var announcement = autoSleepSummaryAnnouncementNode();
    setAutoSleepSummary(summary, announcement, cfg.autoSleepStatus ? autoSleepSummaryModel(cfg.autoSleepStatus) : autoSleepLoadingSummaryModel());
    var panel = cfg.el("div", { class: "autobright-panel", id: "auto-sleep-status" }, [
      cfg.el("div", { class: "autobright-head auto-sleep-head" }, [
      cfg.el("div", {}, [
          cfg.el("strong", { text: cfg.i18nText("configure.auto_sleep.activity", "Auto-sleep activity") })
        ]),
        cfg.el("div", { class: "autobright-actions" }, [windows])
      ]),
      summary,
      announcement,
      chart,
      cfg.el("div", { class: "autobright-legend auto-sleep-legend" }, [
        cfg.el("span", { class: "detected", text: cfg.i18nText("configure.auto_sleep.detected_awake", "Detected / Awake") }),
        cfg.el("span", { class: "clear", text: cfg.i18nText("configure.auto_sleep.clear_sleep", "Clear / Sleep") }),
        cfg.el("span", { class: "inhibited", text: cfg.i18nText("configure.state.unavailable", "Unavailable") })
      ]),
      cfg.el("div", { id: "auto-sleep-chart-description", class: "sr-only", text: cfg.i18nText("configure.auto_sleep.history_replaying", "Replaying activity history.") }),
      cfg.el("div", {
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
    setAutoSleepSummary(summary, announcement, cfg.autoSleepStatus ? autoSleepSummaryModel(cfg.autoSleepStatus) : autoSleepLoadingSummaryModel());
  }

  function autoSleepHistoryMessage() {
    if (cfg.autoSleepHistoryError) return cfg.autoSleepHistoryError;
    return autoSleepHistoryBusy() && cfg.autoSleepHistory && cfg.autoSleepHistory.available !== false
      ? cfg.i18nText("configure.auto_sleep.history_refreshing", "Refreshing activity history…") : "";
  }

  function autoSleepHistoryBusy() {
    return cfg.autoSleepHistoryLoading || cfg.autoSleepHistoryWaiting || cfg.autoSleepLoading;
  }

  function autoSleepAreaMatchesName(value, expected) {
    var actual = value && (value.area_name != null ? value.area_name : value.areaName);
    return String(actual || "").trim().toLowerCase() === String(expected || "").trim().toLowerCase();
  }

  function autoSleepAreaMatches(value) {
    var expected = String(cfg.autoSleepAssignedAreaName || "").trim();
    if (!expected) return true;
    return autoSleepAreaMatchesName(value, expected);
  }

  function autoSleepAreaTransitioning(value) {
    var expected = String(cfg.autoSleepAssignedAreaName || "").trim();
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
    if (phase === "no_area") return cfg.i18nText("configure.auto_sleep.history_requires_area", "Assign this panel to a Home Assistant Area to calculate activity history.");
    if (phase === "no_credible_sources") return cfg.i18nText("configure.auto_sleep.no_credible_sources", "No credible device-backed activity source is available in this Area.");
    if (phase === "auth_failed") return cfg.i18nText("configure.auto_sleep.auth_failed", "Home Assistant authentication failed. Reconnect Home Assistant to calculate activity history.");
    if (phase === "discovery_failed" && detail === "history_parse") return cfg.i18nText("configure.auto_sleep.timestamps_unreadable", "Home Assistant returned activity timestamps this panel could not read.");
    if (phase === "discovery_failed" && detail === "history_limit") return cfg.i18nText("configure.auto_sleep.history_too_large", "Home Assistant activity history is too large to process safely.");
    if (phase === "discovery_failed") return cfg.i18nText("configure.auto_sleep.discovery_failed", "Auto-sleep source discovery failed. Check the Home Assistant connection.");
    if (phase === "status_failed") return cfg.i18nText("configure.auto_sleep.status_failed", "Auto-sleep status request failed (HTTP {status}).", { status: Number(status && status.status_code) });
    return cfg.i18nText("configure.auto_sleep.history_unavailable", "Auto-sleep activity history is unavailable.");
  }

  function scheduleAutoSleepReadiness(afterFailure) {
    if (cfg.autoSleepHistoryReadyTimer) clearTimeout(cfg.autoSleepHistoryReadyTimer);
    var request = cfg.autoSleepRequest;
    var delay = afterFailure ? cfg.autoSleepHistoryRetryDelayMs : cfg.autoSleepReadinessDelayMs;
    if (afterFailure) cfg.autoSleepHistoryRetryDelayMs = Math.min(60 * 1000, cfg.autoSleepHistoryRetryDelayMs * 2);
    else cfg.autoSleepReadinessDelayMs = Math.min(5000, cfg.autoSleepReadinessDelayMs + 500);
    cfg.autoSleepHistoryReadyTimer = setTimeout(function () {
      cfg.autoSleepHistoryReadyTimer = null;
      if (request === cfg.autoSleepRequest && cfg.values.auto_sleep === "true") loadAutoSleepData();
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
    var areaKey = cfg.autoSleepHistory && (cfg.autoSleepHistory.area_key != null ? cfg.autoSleepHistory.area_key : cfg.autoSleepHistory.areaKey);
    sourceKey = String(sourceKey || "").trim();
    areaKey = String(areaKey || "").trim();
    if (!areaKey || !sourceKey || cfg.autoSleepSourceUpdating[sourceKey]) return;
    var included = source.included !== false;
    var updateGeneration = cfg.autoSleepAreaGeneration;
    var updateToken = {};
    cfg.autoSleepSourceUpdating[sourceKey] = updateToken;
    cfg.autoSleepHistoryWaiting = true;
    cfg.autoSleepHistoryWaitingMessage = cfg.i18nText("configure.auto_sleep.history_preparing", "Preparing activity history…");
    cfg.autoSleepHistoryError = "";
    updateAutoSleepHistory(false);
    fetch("api/v1/auto-sleep/source", {
      method: "POST",
      headers: { "Accept": "application/json", "Content-Type": "application/json" },
      body: JSON.stringify({ area_key: areaKey, source_key: sourceKey, included: !included })
    }).then(function (response) {
      if (!response.ok) { var error = new Error("HTTP " + response.status); error.status = response.status; throw error; }
      return response.json().catch(function () { return {}; });
    }).then(function () {
      if (updateGeneration !== cfg.autoSleepAreaGeneration || cfg.autoSleepSourceUpdating[sourceKey] !== updateToken) return;
      delete cfg.autoSleepSourceUpdating[sourceKey];
      invalidateAutoSleepData();
      loadAutoSleepData();
    }).catch(function (error) {
      if (updateGeneration !== cfg.autoSleepAreaGeneration || cfg.autoSleepSourceUpdating[sourceKey] !== updateToken) return;
      delete cfg.autoSleepSourceUpdating[sourceKey];
      cfg.autoSleepHistoryWaiting = false;
      cfg.autoSleepHistoryWaitingMessage = "";
      cfg.autoSleepHistoryError = error && error.status
        ? cfg.i18nText("configure.auto_sleep.source_update_failed_http", "Could not update this activity source (HTTP {status}).", { status: error.status })
        : cfg.i18nText("configure.auto_sleep.source_update_failed", "Could not update this activity source.");
      updateAutoSleepHistory();
    });
  }

  function autoSleepDisplayedHours(history) {
    var hours = history && history.hours;
    if (typeof hours === "number" && isFinite(hours) && hours > 0) return hours;
    var start = history && (history.window_start_epoch_ms != null ? history.window_start_epoch_ms : history.windowStartEpochMs);
    var end = history && (history.window_end_epoch_ms != null ? history.window_end_epoch_ms : history.windowEndEpochMs);
    return typeof start === "number" && typeof end === "number" && end > start ? Math.round((end - start) / 3600000) : cfg.autoSleepHistoryHours;
  }

  function autoSleepHistoryBounds(history) {
    var now = Date.now(), fallbackStart = now - cfg.autoSleepHistoryHours * 60 * 60 * 1000;
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
        ? cfg.i18nText("configure.duration.hours_minutes", "{hours} h {minutes} min", { hours: Math.floor(minutes / 60), minutes: minutes % 60 })
        : cfg.i18nText("configure.duration.minutes", "{count} min", { count: minutes });
    }
    return cfg.i18nText("configure.auto_sleep.calculated_summary", "Calculated auto-sleep: hold awake {awake}, allow sleep {sleep}, inhibited {inhibited}.", {
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
    var history = cfg.autoSleepHistory;
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
    var replacement = cfg.el("div", { class: "auto-sleep-chart-snapshot" });
    if (!policySegments.length) replacement.appendChild(cfg.el("div", { class: "auto-sleep-empty", text: cfg.autoSleepHistoryError || cfg.i18nText("configure.auto_sleep.no_replay_data", "No replay data") }));
    function span(segment, kind) {
      var start = segment.start_epoch_ms != null ? segment.start_epoch_ms : segment.startEpochMs;
      var end = segment.end_epoch_ms != null ? segment.end_epoch_ms : segment.endEpochMs;
      var state = kind === "policy" ? String(segment.output || "inhibited").toLowerCase() : String(segment.state || "unavailable").toLowerCase();
      var left = Math.max(0, Math.min(100, (start - bounds.start) / Math.max(1, bounds.end - bounds.start) * 100));
      var right = Math.max(left, Math.min(100, (end - bounds.start) / Math.max(1, bounds.end - bounds.start) * 100));
      var names = {
        hold_awake: cfg.i18nText("configure.auto_sleep.hold_awake", "Hold awake"),
        allow_sleep: cfg.i18nText("configure.auto_sleep.allow_sleep", "Allow sleep"),
        inhibited: cfg.i18nText("configure.auto_sleep.inhibited", "Inhibited"),
        on: cfg.i18nText("configure.auto_sleep.detected", "Detected"),
        off: cfg.i18nText("configure.auto_sleep.clear", "Clear"),
        unavailable: cfg.i18nText("configure.state.unavailable", "Unavailable")
      };
      var node = cfg.el("span", { class: "auto-sleep-interval " + state });
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
        hold_awake: cfg.i18nText("configure.auto_sleep.hold_awake_lower", "hold awake"),
        allow_sleep: cfg.i18nText("configure.auto_sleep.allow_sleep_lower", "allow sleep"),
        inhibited: cfg.i18nText("configure.auto_sleep.inhibited_lower", "inhibited"),
        on: cfg.i18nText("configure.auto_sleep.detected_lower", "detected"),
        off: cfg.i18nText("configure.auto_sleep.clear_lower", "clear"),
        unavailable: cfg.i18nText("configure.state.unavailable_lower", "unavailable")
      };
      var detail = Object.keys(counts).map(function (state) {
        return cfg.i18nText("configure.auto_sleep.interval_count", "{state} {count} interval(s)", { state: names[state] || autoSleepHuman(state), count: counts[state] });
      }).join(", ");
      var sourceKey = source && (source.source_key != null ? source.source_key : source.sourceKey);
      sourceKey = String(sourceKey || "").trim();
      var included = !source || source.included !== false;
      var updating = !!(sourceKey && cfg.autoSleepSourceUpdating[sourceKey]);
      var interactionBlocked = updating || autoSleepHistoryBusy();
      function sourceInteractionBlocked() {
        return !!cfg.autoSleepSourceUpdating[sourceKey] || autoSleepHistoryBusy();
      }
      var stateText = source ? (included ? cfg.i18nText("configure.auto_sleep.included", "included") : cfg.i18nText("configure.auto_sleep.suppressed_lower", "suppressed")) : cfg.i18nText("configure.auto_sleep.calculated", "calculated");
      var labelText = label + (source && !included ? cfg.i18nText("configure.auto_sleep.suppressed_suffix", " · Suppressed") : "") + (updating ? cfg.i18nText("configure.auto_sleep.updating_suffix", " · Updating…") : "");
      var rowAttrs = {
        class: "auto-sleep-lane " + (kind === "policy" ? "policy" : "source") + (included ? "" : " suppressed") + (updating ? " updating" : ""),
        role: sourceKey ? "button" : "img",
        "aria-label": cfg.i18nText("configure.auto_sleep.lane_label", "{label}{kind}{state}, over {hours} hours: {detail}", {
          label: label,
          kind: source ? cfg.i18nText("configure.auto_sleep.activity_source_separator", " activity source, ") : cfg.i18nText("configure.auto_sleep.result_separator", " result, "),
          state: stateText, hours: displayedHours, detail: detail || cfg.i18nText("configure.auto_sleep.no_intervals", "no intervals")
        })
      };
      if (sourceKey) {
        rowAttrs.tabindex = "0";
        rowAttrs["aria-pressed"] = included ? "true" : "false";
        rowAttrs["aria-disabled"] = interactionBlocked ? "true" : "false";
        trackAttrs.title = included ? cfg.i18nText("configure.auto_sleep.click_suppress", "Click to suppress this source") : cfg.i18nText("configure.auto_sleep.click_include", "Click to include this source");
      }
      var track = cfg.el("div", trackAttrs);
      segments.forEach(function (segment) { track.appendChild(span(segment, kind)); });
      var row = cfg.el("div", rowAttrs, [
        cfg.el("div", { class: "auto-sleep-label", text: labelText, title: label }), track
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
      replacement.appendChild(lane(cfg.i18nText("configure.auto_sleep.calculated_label", "Calculated auto-sleep"), policySegments, "policy"));
      var sources = cfg.el("div", { class: "auto-sleep-source-scroll" });
      sourceLanes.forEach(function (source) { sources.appendChild(lane(source.label || cfg.i18nText("configure.auto_sleep.activity_source", "Activity source"), Array.isArray(source.segments) ? source.segments : [], "source", source)); });
      replacement.appendChild(sources);
      var axis = cfg.el("div", { class: "auto-sleep-axis", "aria-hidden": "true" }, [cfg.el("span", { text: "" }), cfg.el("div", { class: "auto-sleep-axis-track" })]);
      var axisTrack = axis.lastChild;
      for (var tick = 0; tick <= 6; tick += 1) {
        var at = new Date(bounds.start + tick / 6 * (bounds.end - bounds.start));
        var marker = cfg.el("span", { text: ("0" + at.getHours()).slice(-2) + ":" + ("0" + at.getMinutes()).slice(-2) });
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
    var description = autoSleepHistorySummary(policySegments, bounds) + " " + cfg.i18nText("configure.auto_sleep.source_lanes_shown", "{count} source lanes are shown.", { count: sourceLanes.length });
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
      message.classList.toggle("error", !!cfg.autoSleepHistoryError);
    }
    for (var index = 0; index < windowButtons.length; index += 1) {
      var selected = Number(windowButtons[index].getAttribute("data-hours")) === cfg.autoSleepHistoryHours;
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
    if (cfg.values.auto_sleep !== "true" || cfg.autoSleepHistoryLoading) return;
    cfg.autoSleepHistoryLoading = true; cfg.autoSleepHistoryWaiting = false; cfg.autoSleepHistoryWaitingMessage = "";
    cfg.autoSleepHistoryError = ""; updateAutoSleepHistory();
    var request = ++cfg.autoSleepHistoryRequest, retryAutomatically = false, receivedHistory = false, succeeded = false;
    if (cfg.autoSleepHistoryReadyTimer) { clearTimeout(cfg.autoSleepHistoryReadyTimer); cfg.autoSleepHistoryReadyTimer = null; }
    fetch("api/v1/auto-sleep/history?hours=" + cfg.autoSleepHistoryHours, { cache: "no-store" })
      .then(function (response) {
        if (!response.ok) { var httpError = new Error("http"); httpError.status = response.status; throw httpError; }
        return response.json();
      })
      .then(function (body) {
        if (request !== cfg.autoSleepHistoryRequest) return;
        if (!body || body.available === false) {
          var unavailable = new Error("unavailable"); unavailable.detail = body && body.detail; throw unavailable;
        }
        if (!autoSleepAreaMatches(body)) {
          var staleArea = new Error("stale area"); staleArea.detail = "sources_changed"; throw staleArea;
        }
        cfg.autoSleepHistory = body;
        receivedHistory = true;
        succeeded = true;
        cfg.autoSleepReadinessDelayMs = 1000;
        cfg.autoSleepHistoryRetryDelayMs = 5000;
      }).catch(function (error) {
        if (request !== cfg.autoSleepHistoryRequest) return;
        if (error && error.status >= 400 && error.status < 500) {
          cfg.autoSleepHistoryError = cfg.i18nText("configure.auto_sleep.history_request_failed", "History request failed (HTTP {status}).", { status: error.status });
        }
        else if (error && error.status) {
          retryAutomatically = true;
          cfg.autoSleepHistoryWaiting = true;
          cfg.autoSleepHistoryWaitingMessage = cfg.i18nText("configure.auto_sleep.history_retrying", "Activity history is temporarily unavailable. Retrying automatically…");
        }
        else if (error && (error.detail === "runtime_unavailable" || error.detail === "sources_changed")) {
          retryAutomatically = true;
          cfg.autoSleepHistoryWaiting = true;
          cfg.autoSleepHistoryWaitingMessage = error.detail === "sources_changed"
            ? cfg.i18nText("configure.auto_sleep.sources_changed", "Activity sources changed. Refreshing history…")
            : cfg.i18nText("configure.auto_sleep.history_preparing", "Preparing activity history…");
        }
        else if (error && error.detail === "history_auth") cfg.autoSleepHistoryError = cfg.i18nText("configure.auto_sleep.history_rejected", "Home Assistant rejected the history request. Reconnect Home Assistant.");
        else if (error && (error.detail === "history_transport" || error.detail === "history_unavailable")) {
          retryAutomatically = true;
          cfg.autoSleepHistoryWaiting = true;
          cfg.autoSleepHistoryWaitingMessage = cfg.i18nText("configure.auto_sleep.ha_history_retrying", "Home Assistant history is temporarily unavailable. Retrying automatically…");
        }
        else if (error && error.name === "TypeError") {
          retryAutomatically = true;
          cfg.autoSleepHistoryWaiting = true;
          cfg.autoSleepHistoryWaitingMessage = cfg.i18nText("configure.auto_sleep.panel_reconnecting", "The panel connection changed. Reconnecting activity history…");
        }
        else if (error && error.detail === "history_parse") cfg.autoSleepHistoryError = cfg.i18nText("configure.auto_sleep.history_unreadable", "Home Assistant returned activity history this build could not read.");
        else if (error && error.detail === "history_limit") cfg.autoSleepHistoryError = cfg.i18nText("configure.auto_sleep.history_rows_exceeded", "Home Assistant returned more activity history rows than the replay safety bound allows.");
        else cfg.autoSleepHistoryError = cfg.i18nText("configure.auto_sleep.response_unreadable", "The activity history response could not be read.");
        if (!retryAutomatically && cfg.autoSleepHistory) cfg.autoSleepHistoryHours = autoSleepDisplayedHours(cfg.autoSleepHistory);
      }).then(function () {
        if (request !== cfg.autoSleepHistoryRequest) return;
        cfg.autoSleepHistoryLoading = false; updateAutoSleepHistory(receivedHistory);
        if ((succeeded || !retryAutomatically) && typeof window !== "undefined" && window.configCardSizeSourceReady) {
          window.configCardSizeSourceReady("autoSleep");
          if (window.configCardSizeGeometryChanged) window.configCardSizeGeometryChanged();
        }
        if (retryAutomatically) scheduleAutoSleepReadiness(true);
      });
  }

  function invalidateAutoSleepHistory(clearSnapshot) {
    cfg.autoSleepHistoryRequest++;
    cfg.autoSleepHistoryLoading = false;
    if (clearSnapshot) cfg.autoSleepHistory = null;
    cfg.autoSleepHistoryError = "";
    cfg.autoSleepHistoryWaiting = false;
    cfg.autoSleepHistoryWaitingMessage = "";
    cfg.autoSleepReadinessDelayMs = 1000;
    cfg.autoSleepHistoryRetryDelayMs = 5000;
    if (cfg.autoSleepHistoryReadyTimer) { clearTimeout(cfg.autoSleepHistoryReadyTimer); cfg.autoSleepHistoryReadyTimer = null; }
  }

  function invalidateAutoSleepData(clearSnapshot) {
    cfg.autoSleepRequest++;
    cfg.autoSleepLoading = false;
    if (clearSnapshot === true) cfg.autoSleepStatus = null;
    invalidateAutoSleepHistory(clearSnapshot === true);
  }

  // Read status before history. Transitional discovery is followed automatically with capped backoff;
  // once LIVE, steady state owns no timer and the replay is fetched exactly once.
  function loadAutoSleepData() {
    if (cfg.values.auto_sleep !== "true" || autoSleepUsesTouch() || cfg.autoSleepLoading) return;
    cfg.autoSleepLoading = true;
    cfg.autoSleepHistoryWaiting = true;
    cfg.autoSleepHistoryWaitingMessage = cfg.i18nText("configure.auto_sleep.history_preparing", "Preparing activity history…");
    cfg.autoSleepHistoryError = "";
    updateAutoSleepSummary();
    updateAutoSleepHistory();
    var request = ++cfg.autoSleepRequest;
    fetch("api/v1/auto-sleep", { cache: "no-store" })
      .then(function (response) {
        if (!response.ok) { var statusError = new Error("status"); statusError.status = response.status; throw statusError; }
        return response.json();
      })
      .then(function (body) {
        if (request !== cfg.autoSleepRequest) return;
        cfg.autoSleepStatus = body || { available: false, phase: "unavailable" };
      }).catch(function (error) {
        if (request !== cfg.autoSleepRequest) return;
        cfg.autoSleepStatus = error && error.status >= 400 && error.status < 500 ?
          { available: false, phase: "status_failed", reason: "status_http", status_code: error.status, source_count: 0, manual_suppression: false } :
          { available: false, phase: "unavailable", reason: "request_failed", source_count: 0, manual_suppression: false };
      }).then(function () {
      if (request !== cfg.autoSleepRequest) return;
      cfg.autoSleepLoading = false;
      updateAutoSleepSummary();
      if (autoSleepUsesPanel()) {
        cfg.autoSleepHistoryWaiting = false;
        scheduleAutoSleepReadiness(false);
        return;
      }
      if (autoSleepHistoryReady(cfg.autoSleepStatus)) {
        cfg.autoSleepHistoryWaiting = false;
        cfg.autoSleepHistoryWaitingMessage = "";
        cfg.autoSleepHistoryError = "";
        loadAutoSleepHistory();
      } else if (autoSleepAreaTransitioning(cfg.autoSleepStatus) || autoSleepHistoryPreparing(cfg.autoSleepStatus) || autoSleepStatusRetryable(cfg.autoSleepStatus)) {
        var retryAfterFailure = autoSleepStatusRetryable(cfg.autoSleepStatus);
        cfg.autoSleepHistoryWaiting = true;
        cfg.autoSleepHistoryWaitingMessage = autoSleepAreaTransitioning(cfg.autoSleepStatus) || autoSleepHistoryPreparing(cfg.autoSleepStatus) ?
          cfg.i18nText("configure.auto_sleep.history_preparing", "Preparing activity history…") : cfg.i18nText("configure.auto_sleep.status_waiting", "Waiting for auto-sleep status…");
        cfg.autoSleepHistoryError = "";
        updateAutoSleepHistory();
        scheduleAutoSleepReadiness(retryAfterFailure);
      } else {
        cfg.autoSleepHistoryWaiting = false;
        cfg.autoSleepHistoryWaitingMessage = "";
        cfg.autoSleepHistoryError = autoSleepHistoryTerminalMessage(cfg.autoSleepStatus);
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

  cfg.autoSleepUsesTouch = autoSleepUsesTouch;
  cfg.autoSleepUsesHa = autoSleepUsesHa;
  cfg.autoSleepPrerequisiteNode = autoSleepPrerequisiteNode;
  cfg.updateAutoSleepPrerequisiteUi = updateAutoSleepPrerequisiteUi;
  cfg.loadAutoSleepPrerequisite = loadAutoSleepPrerequisite;
  cfg.scheduleAutoSleepPrerequisite = scheduleAutoSleepPrerequisite;
  cfg.autoSleepPanel = autoSleepPanel;
  cfg.updateAutoSleepSummary = updateAutoSleepSummary;
  cfg.updateAutoSleepHistory = updateAutoSleepHistory;
  cfg.invalidateAutoSleepData = invalidateAutoSleepData;
  cfg.loadAutoSleepData = loadAutoSleepData;
})(window.ConfigurePage);
