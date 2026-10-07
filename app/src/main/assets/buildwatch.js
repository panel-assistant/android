// Build + config watch (shared by every page). /health carries these tokens:
//  - build= : per-INSTALL token (changes on every (re)install, even a same-version dev re-spin). If it
//    changes while a page is open, the app was updated → AUTO-RELOAD to pull fresh html/css/js.
//  - cfg=   : fingerprint of the panel settings. If it changes while the CONFIGURE page is open, the
//    settings were changed underneath this tab (the API, an HA entity, another browser) → auto-reload
//    so the form shows reality.
//  - pa_notice= : whether an unconnected panel still owes its owner the migration notice.
// Exception for both: unsaved edits (a focused field, or the Configure form's enabled Save button)
// must never be destroyed — show the #verbar banner instead and let the user choose.
// Baselines = <body data-build> / <body data-cfg>; configure.js re-stamps data-cfg after its own save
// so a save in THIS tab doesn't look like an external change.
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
    return !!(save && !save.disabled); // enabled Save = unsaved Configure edits
  }
  function clearNode(node) {
    while (node.firstChild) node.removeChild(node.firstChild);
  }
  function banner(key, english) {
    var b = document.getElementById("verbar");
    if (!b) return;
    clearNode(b);
    b.appendChild(document.createTextNode("⟳ " + i18nText(key, english) + " — "));
    var reload = document.createElement("a");
    reload.href = "#";
    reload.textContent = i18nText("shell.action.reload", "reload");
    reload.addEventListener("click", function (event) {
      event.preventDefault();
      location.reload();
    });
    b.appendChild(reload);
    b.appendChild(document.createTextNode(" " + i18nText("shell.new_version.refresh_suffix", "to refresh this page.")));
    b.style.display = "";
  }
  function migrationBanner(visible) {
    var b = document.getElementById("migrationbar");
    if (b) b.style.display = visible ? "" : "none";
  }
  // A poll started before durable dismissal can arrive afterwards; it cannot restore the old notice.
  var noticeEpoch = 0;
  var dismiss = document.getElementById("migration-dismiss");
  if (dismiss) dismiss.addEventListener("click", function () {
    dismiss.disabled = true;
    fetch("api/v1/migration-notice/dismiss", { method: "POST" })
      .then(function (response) {
        if (!response.ok) throw new Error("dismiss failed");
        return response.json();
      })
      .then(function (result) {
        dismiss.disabled = false;
        if (result && result.ok === true) { noticeEpoch++; migrationBanner(false); }
      })
      .catch(function () { dismiss.disabled = false; });
  });
  // Home Assistant lifecycle. /health carries `ha=<state>` only while the panel is watching, so an
  // absent token means "nothing to say" rather than "healthy". Rendered here rather than through the
  // Information page's banner zone because that hydrates once and would freeze mid-outage; this poll
  // already runs on every page.
  // `ha_src` matters for one state only, and it may be ABSENT: it names a source only when one
  // actually observed the state. Panel Assistant reports deliberate shutdown through the native
  // session; a connection loss without that notice claims only that Home Assistant is gone.
  var HA_TEXT = {
    shutting_down: { key: "shell.runtime.ha_lifecycle.offline", text: "Home Assistant has gone offline — controls may be temporarily unavailable.", glyph: "⚠" },
    starting: { key: "shell.runtime.ha_lifecycle.starting", text: "Home Assistant is starting — controls will return shortly.", glyph: "⟳" },
    back_online: { key: "shell.runtime.ha_lifecycle.back_online", text: "Home Assistant is back online.", glyph: "✓" }
  };
  var HA_TEXT_SOCKET = {
    shutting_down: { key: "shell.runtime.ha_lifecycle.shutting_down", text: "Home Assistant is shutting down — controls may be temporarily unavailable.", glyph: "⚠" }
  };
  // The diagnostics row's idle wording, kept here so the row and the banner are rendered from the SAME
  // /health observation. A server-rendered advisory used to sit beside them; it could not retract
  // itself after recovery, so it was removed rather than kept in step.
  var HA_ROW_REFUSED = "watching; Home Assistant does not permit WebSocket lifecycle events for this user";
  function haBanner(state, src, refused, notice) {
    var copy = ((src === "socket" || src === "native") && ownValue(HA_TEXT_SOCKET, state)) || ownValue(HA_TEXT, state);
    if (notice && state === "connection_lost") copy = HA_TEXT.shutting_down;
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
        var seconds = Math.max(1, Math.ceil(Math.abs(notice.expected - notice.elapsed) / 1000));
        var unit = seconds >= 3600 ? "hours" : seconds >= 60 ? "minutes" : "seconds";
        var value = unit === "hours" ? Math.ceil(seconds / 3600) : unit === "minutes" ? Math.ceil(seconds / 60) : seconds;
        var duration = i18nText("shell.runtime.ha_lifecycle.duration_" + unit, "{value} " + ({seconds:"sec",minutes:"min",hours:"h"}[unit]), {value:localizedNumber(value)});
        forecast = i18nText("shell.runtime.ha_lifecycle." + (overdue ? "overdue" : "expected_in"),
          overdue ? "{duration} past the estimate" : "Expected back in about {duration}", {duration:duration});
      }
    }
    var b = document.getElementById("halifebar");
    if (b) {
      if (!text) { b.style.display = "none"; } else {
        b.textContent = copy.glyph + " " + text;
        if (supporting) {
          var detail = document.createElement("span"); detail.style.display = "block"; detail.textContent = supporting;
          var timing = document.createElement("small"); timing.style.display = "block"; timing.textContent = forecast;
          b.appendChild(detail); b.appendChild(timing);
        }
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
  // reuses the existing severe-warning presentation (`setup crit`) for the severe verdict and the soft
  // amber `setup` tone for a warning; both retract themselves on the next poll after recovery.
  var HA_NET_TEXT = {
    warning: "⚠ Probes to Home Assistant are going missing",
    severe: "⚠ The network path to Home Assistant is failing"
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
  function haNetBanner(state, resp, p95, n, miss, age, cause) {
    // A latency verdict is owned by the layer-3 probe. Every number on this line — p95, probe count,
    // misses, reply age — comes from the WebSocket instead, so presenting them here would offer one
    // instrument's evidence as proof of another's verdict, and a severe path verdict could read
    // "very slow; p95 9 ms". They are omitted rather than borrowed. An absent cause keeps the
    // legacy loss wording, so an older panel answering this poll is unaffected.
    var slow = cause === "latency";
    var knownLossCause = cause === "" || cause === "loss";
    var b = document.getElementById("hanetbar");
    if (b) {
      var text = slow ? ownValue(HA_NET_TEXT_SLOW, state) : (knownLossCause ? ownValue(HA_NET_TEXT, state) : null);
      if (!text) { b.style.display = "none"; } else {
        if (slow) {
          b.textContent = "⚠ " + i18nText(text[0], text[1]);
        } else {
          var bannerEvidence = haNetEvidence(p95, n, miss, age);
          var bannerKey = state === "severe"
            ? "shell.runtime.ha_network.banner_severe"
            : "shell.runtime.ha_network.banner_warning";
          var bannerFallback = text.substring(2) + ": {evidence}. " + HA_NET_ADVICE;
          b.textContent = "⚠ " + i18nText(bannerKey, bannerFallback, { evidence: bannerEvidence });
        }
        b.className = state === "severe" ? "setup crit" : "setup";
        b.style.display = "";
      }
    }
    var row = document.getElementById("hanetcell");
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
      // Same rule as the banner: the verdict is the probe's, so the socket's numbers stay off it.
      // The responsiveness clause is kept because it names its own instrument out loud.
      var slowRow = HA_NET_ROW_SLOW[state][resp];
      row.textContent = i18nText(slowRow[0], slowRow[1]);
      return;
    }
    var evidence = haNetEvidence(p95, n, miss, age);
    var suffix = clause ? (resp === "severe" ? "_very_slow" : "_slow") : "";
    var rowStem = { healthy: "healthy", warning: "losing_probes", severe: "failing" }[state];
    var rowKey = state === "healthy" && !suffix
      ? "dashboard.runtime.ha_network_healthy"
      : "dashboard.runtime.ha_network_" + rowStem + suffix;
    var rowFallback = HA_NET_ROW[state] + "; " + clause + "{evidence}";
    row.textContent = i18nText(rowKey, rowFallback, { evidence: evidence });
  }
  function vc() {
    var requestedNoticeEpoch = noticeEpoch;
    fetch("health").then(function (r) { return r.text(); }).then(function (t) {
      var notice = t.match(/(?:^|\s)pa_notice=([01])(?:\s|$)/);
      if (notice && requestedNoticeEpoch === noticeEpoch) migrationBanner(notice[1] === "1");
      var mh = t.match(/ha=(\S+)/);
      var ms = t.match(/ha_src=(\S+)/);
      var mr = t.match(/ha_refused=1/);
      var reason = t.match(/(?:^|\s)ha_reason=(\S+)/);
      function timingToken(name) {
        var match = t.match(new RegExp("(?:^|\\s)" + name + "=(\\d+)(?:\\s|$)"));
        return match ? Number(match[1]) : null;
      }
      haBanner(mh ? mh[1] : "", ms ? ms[1] : "", !!mr, reason ? {
        reason:reason[1], elapsed:timingToken("ha_elapsed_ms"), expected:timingToken("ha_expected_ms"), grace:timingToken("ha_grace_ms") || 0
      } : null);
      var mn = t.match(/ha_net=(\S+)/);
      var mrs = t.match(/ha_resp=(\S+)/);
      var mcz = t.match(/ha_net_cause=(\S+)/);
      var mp = t.match(/ha_net_p95=(-?\d+)/);
      var mc = t.match(/ha_net_n=(\d+)/);
      var mm = t.match(/ha_net_miss=(\d+)/);
      var ma = t.match(/ha_net_age=(-?\d+)/);
      haNetBanner(mn ? mn[1] : "", mrs ? mrs[1] : "", mp ? parseInt(mp[1], 10) : -1, mc ? parseInt(mc[1], 10) : 0, mm ? parseInt(mm[1], 10) : 0, ma ? parseInt(ma[1], 10) : -1, mcz ? mcz[1] : "");
      var mb = t.match(/build=(\S+)/);
      if (mb && LB && mb[1] !== LB) {
        if (dirty()) banner("shell.new_version.installed", "A newer ha-paneld is installed"); else location.reload();
        return;
      }
      var mc = t.match(/cfg=(\S+)/);
      var LC = document.body.getAttribute("data-cfg") || "";
      if (mc && LC && mc[1] !== LC && (location.origin + location.pathname).indexOf(new URL("configure", document.baseURI).href) === 0) {
        if (dirty()) banner("shell.settings_changed.externally", "Settings were changed outside this page"); else location.reload();
      }
    }).catch(function () {});
  }
  setInterval(vc, 10000);
  vc();
})();
