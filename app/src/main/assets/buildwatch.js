// Build watch (shared by every page). /health carries these tokens:
//  - build= : per-INSTALL token (changes on every (re)install, even a same-version dev re-spin). If it
//    changes while a page is open, the app was updated → AUTO-RELOAD to pull fresh html/css/js.
//    Unsaved edits (a focused field, or the Profiles editor's enabled Save button) must never be
//    destroyed — show the #verbar banner instead and let the user choose. Baseline = <body data-build>.
// Configure follows setting changes through its own `config` family (configure.js), not a reload.
(function () {
  "use strict";
  var LB = document.body.getAttribute("data-build") || "";
  var i18nText = window.HaI18n.t;
  function requestedLocale() {
    var locale = window.HaI18n && typeof window.HaI18n.locale === "string"
      ? window.HaI18n.locale
      : (document.documentElement && document.documentElement.lang) || "en";
    try {
      new Intl.NumberFormat(locale).format(0);
      return locale;
    } catch (_) {
      return "en";
    }
  }
  function localizedNumber(value) {
    return new Intl.NumberFormat(requestedLocale()).format(Number(value));
  }
  function ownValue(values, key) {
    return Object.prototype.hasOwnProperty.call(values, key) ? values[key] : null;
  }
  function dirty() {
    if (document.querySelector("input:focus,textarea:focus")) return true;
    var save = document.getElementById("savebtn");
    return !!(save && !save.disabled); // enabled Save = unsaved Profiles edits
  }
  function clearNode(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }
  function banner(key, english) {
    var b = document.getElementById("verbar");
    if (!b) return;
    var reload = document.createElement("a");
    reload.href = "#";
    reload.textContent = i18nText("shell.action.reload", "reload");
    reload.addEventListener("click", function (event) {
      event.preventDefault();
      location.reload();
    });
    window.HaNotice.fill(b, "update", i18nText(key, english), "", reload);
    b.style.display = "";
  }
  // Home Assistant lifecycle. /health carries `ha=<state>` only while the panel is watching, so an
  // absent token means "nothing to say" rather than "healthy". Rendered here rather than through the
  // Information page's banner zone because that hydrates once and would freeze mid-outage; this poll
  // already runs on every page.
  // Only Panel Assistant's notice reports a deliberate shutdown; a connection loss that follows one
  // claims only that Home Assistant is gone.
  var HA_TEXT = {
    shutting_down: { key: "shell.runtime.ha_lifecycle.shutting_down", text: "Home Assistant is shutting down — controls may be temporarily unavailable.", step: "stopping" },
    starting: { key: "shell.runtime.ha_lifecycle.starting", text: "Home Assistant is starting.", step: "starting" },
    back_online: { key: "shell.runtime.ha_lifecycle.back_online", text: "Home Assistant is back online." }
  };
  var HA_TEXT_OFFLINE = { key: "shell.runtime.ha_lifecycle.offline", text: "Home Assistant has gone offline — controls may be temporarily unavailable.", step: "stopping" };
  // The diagnostics row's idle wording, kept here so the row and the banner are rendered from the SAME
  // /health observation. A server-rendered advisory used to sit beside them; it could not retract
  // itself after recovery, so it was removed rather than kept in step.
  var HA_ROW_REFUSED = "watching; Home Assistant does not permit WebSocket lifecycle events for this user";
  function haBanner(state, refused, notice) {
    var copy = ownValue(HA_TEXT, state);
    if (notice && state === "connection_lost") copy = HA_TEXT_OFFLINE;
    var outage = notice && (state === "shutting_down" || state === "starting" || state === "connection_lost");
    var overdue = outage && notice.expected !== null && notice.elapsed !== null && notice.elapsed > notice.expected;
    var grace = outage && state === "connection_lost" && notice.grace > 0;
    var text = !grace && copy ? i18nText(copy.key, copy.text) : "";
    if (overdue) text = i18nText("shell.runtime.ha_lifecycle.taking_longer", "Taking longer than usual");
    var supporting = "", forecast = "";
    if (outage && !grace) {
      var reasons = {restart:"Home Assistant restart",host_reboot:"Host reboot",core_update:"Home Assistant Core update",unknown:"Reason unknown"};
      var reason = ownValue(reasons, notice.reason) ? notice.reason : "unknown";
      supporting = i18nText("shell.runtime.ha_lifecycle.reason_" + reason, reasons[reason]);
      forecast = i18nText("shell.runtime.ha_lifecycle.not_measured", "Time back has not been measured yet");
      if (notice.expected !== null && notice.elapsed !== null) {
        var seconds = Math.ceil(Math.abs(notice.expected - notice.elapsed) / 1000);
        var clock = Math.floor(seconds / 60) + ":" + ("0" + (seconds % 60)).slice(-2);
        forecast = i18nText("shell.runtime.ha_lifecycle." + (overdue ? "overdue" : "expected_in"),
          overdue ? "{duration} past the estimate" : "Expected back in {duration}", {duration:clock});
      }
    }
    var b = document.getElementById("halifebar");
    if (b) {
      if (!text) { b.style.display = "none"; } else {
        // The pill names the step Home Assistant is in; back online is the resolved row.
        var STEP_WORDS = { stopping: "Stopping", starting: "Starting" };
        var word = copy.step ? i18nText("shell.runtime.ha_lifecycle.step_" + copy.step, STEP_WORDS[copy.step]) : null;
        window.HaNotice.fill(b, copy.step ? "working" : "resolved", text, supporting ? supporting + " · " + forecast : "", null, word);
        b.style.display = "";
      }
    }
    var row = document.getElementById("halifecell");
    if (!row) return;
    if (!state) { row.textContent = ""; return; }
    if (text) { row.textContent = text + (supporting ? " — " + supporting + "; " + forecast : ""); return; }
    if (grace) { row.textContent = ""; return; }
    if (state === "connection_lost") {
      row.textContent = i18nText("dashboard.runtime.ha_connection_lost", "connection lost");
      return;
    }
    if (state === "normal") {
      row.textContent = refused
        ? i18nText("dashboard.runtime.ha_events_refused", HA_ROW_REFUSED)
        : i18nText("dashboard.runtime.ha_watching", "watching");
      return;
    }
    row.textContent = "";
  }
  // Home Assistant network path. /health carries `ha_net=<healthy|warning|severe>` plus terse numbers
  // only while the panel holds a Home Assistant socket, so an absent token means "not measured", and
  // the banner and row are rendered from the SAME observation as the lifecycle pair above. The banner
  // is an Action needed notice row for the severe verdict and a Warning row otherwise; both retract
  // themselves on the next poll after recovery.
  var HA_NET_TEXT = {
    warning: "Probes to Home Assistant are going missing",
    severe: "The network path to Home Assistant is failing"
  };
  // Latency-raised copy. The path itself is slow, which sends a person somewhere different to look
  // than lost packets do, so the two are never worded the same.
  var HA_NET_TEXT_SLOW = {
    warning: ["shell.runtime.ha_network.banner_latency_warning", "The network path to Home Assistant is slow. Every action waits on this path. It is measured at the network level, so this is not the panel or Home Assistant being slow — check the Wi-Fi or the link between them."],
    severe: ["shell.runtime.ha_network.banner_latency_severe", "The network path to Home Assistant is very slow. Every action waits on this path. It is measured at the network level, so this is not the panel or Home Assistant being slow — check the Wi-Fi or the link between them."]
  };
  var HA_NET_ADVICE = "Packets are not getting through. Check the Wi-Fi path between this panel and Home Assistant before blaming the panel.";
  var HA_NET_ROW = { healthy: "healthy", warning: "losing probes", severe: "failing", settling: "settling after startup; no verdict yet" };
  // The other half of the same measurement: how fast Home Assistant answers on a path that is intact.
  // It becomes a CLAUSE in the same row and can NEVER raise the banner — latency alone is a performance
  // observation, not a reason to interrupt anyone, and treating it as one told a wired panel its
  // network was slow. Mirrors HaNetworkPathPresentation.responsivenessClause.
  var HA_RESP_CLAUSE = { healthy: "", warning: "Home Assistant answering slowly; ", severe: "Home Assistant answering very slowly; " };
  var HA_NET_ROW_SLOW = {
    warning: {
      healthy: ["dashboard.runtime.ha_network_latency_warning", "slow"],
      warning: ["dashboard.runtime.ha_network_latency_warning_response_slow", "slow; Home Assistant answering slowly"],
      severe: ["dashboard.runtime.ha_network_latency_warning_response_very_slow", "slow; Home Assistant answering very slowly"]
    },
    severe: {
      healthy: ["dashboard.runtime.ha_network_latency_severe", "very slow"],
      warning: ["dashboard.runtime.ha_network_latency_severe_response_slow", "very slow; Home Assistant answering slowly"],
      severe: ["dashboard.runtime.ha_network_latency_severe_response_very_slow", "very slow; Home Assistant answering very slowly"]
    }
  };
  // An empty window is two facts: a socket that has only just connected, and a stream that parked
  // and stopped probing. Only the age of the last reply tells them apart (same wording as Kotlin).
  function haNetAge(ms) {
    if (ms < 60000) {
      var seconds = localizedNumber(Math.floor(ms / 1000));
      return i18nText("shell.runtime.duration_seconds", "{count} s", { count: seconds });
    }
    var minutes = localizedNumber(Math.floor(ms / 60000));
    return i18nText("shell.runtime.duration_minutes", "{count} min", { count: minutes });
  }
  function haNetEvidence(p95, n, miss, age) {
    var keyPrefix = "shell.runtime.ha_network_evidence_";
    if (!n) {
      return age < 0
        ? i18nText(keyPrefix + "no_probes", "no probes yet in the last 5 min")
        : i18nText(
          keyPrefix + "no_answer",
          "no probe answered in the last 5 min; last reply {lastReplyAge} ago",
          { lastReplyAge: haNetAge(age) }
        );
    }
    var values = {
      p95Ms: localizedNumber(p95),
      missCount: localizedNumber(miss),
      probeCount: localizedNumber(n)
    };
    if (p95 < 0 && miss > 0) {
      return i18nText(keyPrefix + "no_reply_missed", "no reply, {missCount} of {probeCount} probes missed in the last 5 min", values);
    }
    if (p95 < 0) {
      return i18nText(keyPrefix + "no_reply_no_misses", "no reply, no misses in the last 5 min");
    }
    if (miss > 0) {
      return i18nText(keyPrefix + "p95_missed", "p95 {p95Ms} ms, {missCount} of {probeCount} probes missed in the last 5 min", values);
    }
    return i18nText(keyPrefix + "p95_no_misses", "p95 {p95Ms} ms, no misses in the last 5 min", values);
  }
  function haNetEchoEvidence(echo) {
    var values = {
      p95Ms: localizedNumber(echo.p95),
      missCount: localizedNumber(echo.miss),
      probeCount: localizedNumber(echo.n),
      burstCount: localizedNumber(echo.bursts)
    };
    return echo.p95 < 0
      ? i18nText("shell.runtime.ha_network_echo_no_reply", "no reply, {missCount} of {probeCount} network checks missed across {burstCount} bursts", values)
      : i18nText("shell.runtime.ha_network_echo_evidence", "p95 {p95Ms} ms, {missCount} of {probeCount} network checks missed across {burstCount} bursts", values);
  }
  function haNetWithEvidence(verdict, evidence) {
    return i18nText("shell.runtime.ha_network_with_evidence", "{verdict}; {evidence}", { verdict: verdict, evidence: evidence });
  }
  function haNetBanner(state, resp, p95, n, miss, age, cause, source, echo) {
    // The source and its evidence arrive on the same health observation. Old panels omit source;
    // their latency verdict still has no echo figures, so socket numbers stay off that wording.
    var slow = cause === "latency";
    var knownLossCause = cause === "" || cause === "loss";
    var b = document.getElementById("hanetbar");
    var row = document.getElementById("hanetcell");
    if ((source !== "" && source !== "socket" && source !== "echo") ||
        (source === "echo" && !echo)) {
      if (b) b.style.display = "none";
      if (row) row.textContent = "";
      return;
    }
    var evidence = source === "echo" ? haNetEchoEvidence(echo) : haNetEvidence(p95, n, miss, age);
    if (b) {
      var text = slow ? ownValue(HA_NET_TEXT_SLOW, state) : (knownLossCause ? ownValue(HA_NET_TEXT, state) : null);
      if (!text) { b.style.display = "none"; } else {
        if (slow) {
          var message = i18nText(text[0], text[1]);
          if (source === "echo") message = haNetWithEvidence(message, evidence);
        } else {
          var bannerKey = state === "severe"
            ? "shell.runtime.ha_network.banner_severe"
            : "shell.runtime.ha_network.banner_warning";
          var bannerFallback = text + ": {evidence}. " + HA_NET_ADVICE;
          var message = i18nText(bannerKey, bannerFallback, { evidence: evidence });
        }
        window.HaNotice.fill(b, state === "severe" ? "action_needed" : "warning", "", message.replace(/^\s*[⛔⚠ℹ⬆⟳🔒✓]\uFE0F?\s*/u, ""));
        b.style.display = "";
      }
    }
    if (!row) return;
    if (!state) { row.textContent = ""; return; }
    if (state === "settling") {
      row.textContent = i18nText("dashboard.runtime.ha_network_settling", HA_NET_ROW.settling);
      return;
    }
    if ((!slow && !knownLossCause) ||
        (slow && (!Object.prototype.hasOwnProperty.call(HA_NET_ROW_SLOW, state) ||
          !Object.prototype.hasOwnProperty.call(HA_NET_ROW_SLOW[state], resp))) ||
        !Object.prototype.hasOwnProperty.call(HA_NET_ROW, state) ||
        !Object.prototype.hasOwnProperty.call(HA_RESP_CLAUSE, resp)) {
      row.textContent = "";
      return;
    }
    var clause = HA_RESP_CLAUSE[resp] || "";
    if (slow) {
      var slowRow = HA_NET_ROW_SLOW[state][resp];
      var verdict = i18nText(slowRow[0], slowRow[1]);
      row.textContent = source === "echo" ? haNetWithEvidence(verdict, evidence) : verdict;
      return;
    }
    var suffix = clause ? (resp === "severe" ? "_very_slow" : "_slow") : "";
    var rowStem = { healthy: "healthy", warning: "losing_probes", severe: "failing" }[state];
    var rowKey = state === "healthy" && !suffix
      ? "dashboard.runtime.ha_network_healthy"
      : "dashboard.runtime.ha_network_" + rowStem + suffix;
    var rowFallback = HA_NET_ROW[state] + "; " + clause + "{evidence}";
    row.textContent = i18nText(rowKey, rowFallback, { evidence: evidence });
  }
  function vc(t) {
    if (typeof t !== "string") return;
    var mh = t.match(/ha=(\S+)/);
    var mr = t.match(/ha_refused=1/);
    var reason = t.match(/(?:^|\s)ha_reason=(\S+)/);
    function timingToken(name) {
      var match = t.match(new RegExp("(?:^|\\s)" + name + "=(\\d+)(?:\\s|$)"));
      return match ? Number(match[1]) : null;
    }
    haBanner(mh ? mh[1] : "", !!mr, reason ? {
      reason:reason[1], elapsed:timingToken("ha_elapsed_ms"), expected:timingToken("ha_expected_ms"), grace:timingToken("ha_grace_ms") || 0
    } : null);
    var mn = t.match(/ha_net=(\S+)/);
    var mrs = t.match(/ha_resp=(\S+)/);
    var mcz = t.match(/ha_net_cause=(\S+)/);
    var mp = t.match(/ha_net_p95=(-?\d+)/);
    var mc = t.match(/ha_net_n=(\d+)/);
    var mm = t.match(/ha_net_miss=(\d+)/);
    var ma = t.match(/ha_net_age=(-?\d+)/);
    var msr = t.match(/(?:^|\s)ha_net_source=(\S+)/);
    function echoInteger(name) {
      var match = t.match(new RegExp("(?:^|\\s)ha_net_echo_" + name + "=(-?\\d+)(?:\\s|$)"));
      var value = match ? Number(match[1]) : null;
      return Number.isSafeInteger(value) ? value : null;
    }
    var echo = { n: echoInteger("n"), miss: echoInteger("miss"), p95: echoInteger("p95"), bursts: echoInteger("bursts") };
    if (echo.n === null || echo.n <= 0 || echo.miss === null || echo.miss < 0 || echo.miss > echo.n ||
        echo.p95 === null || echo.p95 < -1 || echo.bursts === null || echo.bursts <= 0) echo = null;
    haNetBanner(mn ? mn[1] : "", mrs ? mrs[1] : "", mp ? parseInt(mp[1], 10) : -1, mc ? parseInt(mc[1], 10) : 0, mm ? parseInt(mm[1], 10) : 0, ma ? parseInt(ma[1], 10) : -1, mcz ? mcz[1] : "", msr ? msr[1] : "", echo);
    var mb = t.match(/build=(\S+)/);
    if (mb && LB && mb[1] !== LB) {
      if (dirty()) banner("shell.new_version.installed", "A newer Panel Assistant is installed"); else location.reload();
    }
  }
  window.PaneldTransport.subscribe("health", vc);
})();
