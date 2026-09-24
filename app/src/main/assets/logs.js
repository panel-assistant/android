// Logs tab — live log tail over SSE (/api/v1/logs/stream). Vanilla.
// Buffers every received line client-side (bounded), renders through the level + text filters, and
// follows the tail unless the user scrolls up or unticks Follow. Pause stops rendering (still
// buffering); EventSource auto-reconnects if the app restarts mid-view.
(function () {
  "use strict";
  var MAX_LINES = 3000;                 // client buffer + DOM cap (drop oldest)
  var LEVELS = { V: 0, D: 1, I: 2, W: 3, E: 4, F: 5 };
  var out = document.getElementById("lg-out");
  if (!out) return;

  var source = "app", es = null, buf = [], paused = false, follow = true;
  // threadtime: "MM-DD HH:MM:SS.mmm  PID  TID L TAG: msg" — pull the level char for filter/colour.
  var levelRe = /^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+([VDIWEF])\s/;

  function i18nText(key, fallback, vars) {
    return window.HaI18n && typeof window.HaI18n.t === "function"
      ? window.HaI18n.t(key, fallback, vars)
      : String(fallback == null ? "" : fallback).replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, function (placeholder, name) {
        return vars && Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : placeholder;
      });
  }

  function state(msg) { document.getElementById("lg-state").textContent = "· " + msg; }

  function levelOf(line) {
    var m = levelRe.exec(line);
    return m ? m[1] : "I";
  }

  function passes(entry) {
    var min = LEVELS[document.getElementById("lg-level").value] || 0;
    if ((LEVELS[entry.lvl] || 2) < min) return false;
    var f = document.getElementById("lg-filter").value.toLowerCase();
    return !f || entry.raw.toLowerCase().indexOf(f) >= 0;
  }

  function lineEl(entry) {
    var d = document.createElement("div");
    d.className = "lg-line lg-" + entry.lvl;
    d.textContent = entry.raw;
    return d;
  }

  function atBottom() { return out.scrollHeight - out.scrollTop - out.clientHeight < 40; }
  function toBottom() { out.scrollTop = out.scrollHeight; }

  // Full re-render (filter/level change, clear, resume) — rebuild from the buffer.
  window.lgRender = function () {
    var frag = document.createDocumentFragment();
    for (var i = 0; i < buf.length; i++) if (passes(buf[i])) frag.appendChild(lineEl(buf[i]));
    out.textContent = "";
    out.appendChild(frag);
    if (follow) toBottom();
  };

  function append(entry) {
    buf.push(entry);
    if (buf.length > MAX_LINES) buf.shift();
    if (paused) return;
    if (passes(entry)) {
      out.appendChild(lineEl(entry));
      while (out.childNodes.length > MAX_LINES) out.removeChild(out.firstChild);
      if (follow) toBottom();
    }
  }

  // Per-source live/paused state text — keyed by source id so lgSource()/lgPause() don't need to
  // branch on which source is active (i18nText() keys stay literal string args for the catalogue scan).
  var STATE_TEXT = {
    app: {
      live: function () { return i18nText("logs.state.app_live", "app · live"); },
      paused: function () { return i18nText("logs.state.app_paused", "app · paused"); },
    },
    system: {
      live: function () { return i18nText("logs.state.system_live", "system · live"); },
      paused: function () { return i18nText("logs.state.system_paused", "system · paused"); },
    },
    webview: {
      live: function () { return i18nText("logs.state.webview_live", "browser · live"); },
      paused: function () { return i18nText("logs.state.webview_paused", "browser · paused"); },
    },
  };

  function connect() {
    if (es) es.close();
    buf = [];
    out.textContent = "";
    state(i18nText("logs.state.connecting", "connecting…"));
    es = new EventSource("api/v1/logs/stream?source=" + source);
    es.onopen = function () { state(STATE_TEXT[source].live()); };
    es.onerror = function () { state(i18nText("logs.state.reconnecting", "reconnecting…")); };   // EventSource retries itself
    es.onmessage = function (e) { append({ raw: e.data, lvl: levelOf(e.data) }); };
  }

  window.lgSource = function (s) {
    if (s === source) return;
    source = s;
    document.getElementById("lg-src-app").classList.toggle("on", s === "app");
    document.getElementById("lg-src-system").classList.toggle("on", s === "system");
    document.getElementById("lg-src-webview").classList.toggle("on", s === "webview");
    connect();
  };

  window.lgPause = function () {
    paused = !paused;
    document.getElementById("lg-pause").textContent = paused
      ? "▶ " + i18nText("logs.action.resume", "Resume")
      : "⏸ " + i18nText("logs.action.pause", "Pause");
    if (!paused) window.lgRender();                          // flush what buffered while paused
    state(paused ? STATE_TEXT[source].paused() : STATE_TEXT[source].live());
  };

  window.lgClear = function () { buf = []; out.textContent = ""; };

  // User scrolling up suspends follow; scrolling back to the tail (or re-ticking) resumes it.
  window.lgScrolled = function () {
    var f = document.getElementById("lg-follow");
    if (atBottom()) { if (!follow && f.checked) follow = true; }
    else if (follow) { follow = false; f.checked = false; }
  };
  document.getElementById("lg-follow").addEventListener("change", function () {
    follow = this.checked;
    if (follow) toBottom();
  });

  // Fit the log view to the window: collapse it, measure the slack between the page bottom and the
  // viewport bottom, and hand that slack to the view. Self-adapts to header wrap / banners / the
  // note below — no hard-coded offsets. Min height keeps it usable on tiny windows (page scrolls).
  function fit() {
    out.style.height = "150px";
    var slack = window.innerHeight - document.body.getBoundingClientRect().bottom;
    out.style.height = Math.max(150, 150 + slack) + "px";
    if (follow) toBottom();
  }
  window.addEventListener("resize", fit);
  fit();

  // Stop the stream (and with it the panel-side logcat subprocess) when the page is hidden for a
  // while; reconnect on return. Closing the tab closes the SSE socket → server unsubscribes.
  document.addEventListener("visibilitychange", function () {
    if (document.hidden) { if (es) { es.close(); es = null; state(i18nText("logs.state.hidden", "paused (tab hidden)")); } }
    else if (!es) connect();
  });

  connect();
})();
