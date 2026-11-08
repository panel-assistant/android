// The page's one transport (native transport protocol section 20). Every page module reads, commands and
// subscribes through window.PaneldTransport and never names an HTTP path:
//
//   request(op, args[, options]) -> Promise<{outcome, code, result, headers?, status?}>  a 20.2 `manage` request
//   command(channel, value)      -> Promise<{outcome, code}>                     a 20.2 channel command
//   subscribe(family, onValue[, args]) -> handle with close()                    a 20.4 subscription
//   close()                                                                      ends every subscription
//
// `outcome` is applied, superseded, pending_approval, refused or failed; a non-applied answer carries a
// section 11 `code`, and `result` always carries the body the route returns today (an error body too).
// A request that never reached the panel (network down, socket closed) rejects instead of resolving.
// `onValue(value, state, first)` receives the family's body with state "known", or null with "unavailable";
// `first` marks the first known value after the subscription was (re)opened.
//
// Two implementations behind it:
//  - the 20.2 frame protocol, over one of two carriers: postMessage to Panel Assistant's sidebar when
//    <body data-ui-relay> is present (the sidebar relays each frame over the panel's session), or a
//    WebSocket to the stub door `ws(s)://<page host>/ui` when <body data-ui-socket> is present (its value,
//    when not empty, is the door's path relative to the page base);
//  - HTTP over the existing REST routes otherwise (a hardened panel's LAN pages and the fixture harness).
//    It maps each name back to its route through ROUTES below and polls a subscription at its family's floor.
// A hidden tab or a page being left ends every subscription and opens them again on return, here and nowhere else.
(function () {
  "use strict";
  if (window.PaneldTransport) return;

  // ---- The one name -> route table (20.6). Paths are under api/v1/ unless they start with "/".
  // "METHOD path" or ["METHOD path", options]. Options: b = POST body ("form" default, "json", "raw":
  // args.blob or args.body is the body and the other args go to the query), q = args sent in the query of
  // a POST, h = {arg: request header}, H = fixed request headers, f = fixed args, t = raw body type,
  // c = let the browser cache the answer as the route's headers allow.
  // A route that answers an HTML form page unless asked for JSON; the socket door always asks for JSON.
  var JSON_ANSWER = { Accept: "application/json" };
  var ROUTES = {
    "page.info": "GET /", "page.configure": "GET /configure", "page.setup": "GET /setup", "page.profiles": "GET /profiles",
    "page.install": "GET /install", "page.logs": "GET /logs", "page.entities": "GET /entities",
    "health": "GET /health",
    "diag": "GET diag",
    "openapi.json": "GET openapi.json",
    "action.dashboard": ["POST action", { f: { a: "dashboard" } }],
    "action": "POST action",
    "apps": "GET apps", "packages": "GET packages",
    "auto_brightness": "GET auto-brightness", "auto_brightness.history": "GET auto-brightness/history",
    "auto_brightness.sources": "GET auto-brightness/sources",
    "auto_brightness.reset": "POST auto-brightness/reset", "auto_brightness.resume": "POST auto-brightness/resume",
    "auto_brightness.source": "POST auto-brightness/source",
    "auto_sleep": "GET auto-sleep", "auto_sleep.history": "GET auto-sleep/history", "auto_sleep.prerequisite": "GET auto-sleep/prerequisite",
    "auto_sleep.source": ["POST auto-sleep/source", { b: "json" }],
    "auto_sleep.presence_sources": "GET auto-sleep/presence-sources",
    "auto_sleep.set_presence_sources": ["POST auto-sleep/presence-sources", { b: "json" }],
    "backup": "POST backup",
    "restore": ["POST restore", { b: "raw", h: { passphrase: "X-Backup-Passphrase" } }],
    "camera.status": "GET camera/status",
    "companion.repair_url": "POST companion/repair-url",
    "config": "GET config",
    "config.values": "GET config/values",
    "settings": ["POST config", { H: JSON_ANSWER }],
    "config.schema": "GET config/schema", "config.discovery": "GET config/discovery", "config.ha_area": "GET config/ha-area",
    "config.home_dashboards": "GET config/home-dashboards",
    "config.export": "GET config/export",
    "config.import": ["POST config/import", { b: "raw" }],
    "config.revisions": "GET config/revisions", "config.revisions.restore": "POST config/revisions/{id}/restore",
    "dashboard.clear_storage": "POST dashboard/clear-storage",
    "dashboard.entities": "GET dashboard/entities", "dashboard.entities.sync": "GET dashboard/entities/sync",
    "dashboard.entities.issues": "GET dashboard/entities/issues",
    "dashboard.entities.activate": ["POST dashboard/entities/activate", { b: "json" }],
    "dashboard.entities.issues.set": ["POST dashboard/entities/issues", { b: "json" }],
    "dashboard.entities.override": ["POST dashboard/entities/override", { b: "json" }],
    "dashboard.entities.overrides": ["POST dashboard/entities/overrides", { b: "json" }],
    "dashboard.entities.policy": ["POST dashboard/entities/policy", { b: "json" }],
    "dashboard.entities.reset": ["POST dashboard/entities/reset", { b: "json" }],
    "dashboard.entities.sync.set": ["POST dashboard/entities/sync", { b: "json" }],
    "dashboard.entities.export": "GET dashboard/entities/export",
    "dashboard.entity_filter": "GET dashboard/entity-filter", "dashboard.entity_filter.set": ["POST dashboard/entity-filter", { b: "json" }],
    "display": "GET display", "display.density": ["POST display/density", { H: JSON_ANSWER }],
    "guard_db.clock": "GET guard-db/clock", "guard_db.status": "GET guard-db/status", "guard_db.evidence": "GET guard-db/evidence",
    "guard_db.arm": "POST guard-db/arm", "guard_db.stage": "POST guard-db/stage", "guard_db.discard": "POST guard-db/discard",
    "guard_db.evidence.retire": "POST guard-db/evidence/retire",
    "ha.oauth.panel_start": "GET ha/oauth/panel-start", "ha.oauth.callback": "GET ha/oauth/callback",
    "ha.oauth.start": "POST ha/oauth/start", "ha.oauth.status": "GET ha/oauth/status",
    "hand_back_home": "POST hand-back-home",
    "info": "GET info",
    "input": "POST input",
    "inspect": "GET inspect", "inspect.start": "POST inspect/start", "inspect.stop": "POST inspect/stop",
    "install.apk": ["POST install/apk", { b: "raw", t: "application/octet-stream" }],
    "install.apk.allow": "POST install/apk/allow", "install.apk.commit": "POST install/apk/commit",
    "install.apk.discard": "POST install/apk/discard", "install.apk.fetch.cancel": "POST install/apk/fetch/cancel",
    "install.apk.from_url": "POST install/apk/from-url",
    "install.apk.pending": "GET install/apk/pending", "install.status": "GET install/status",
    "install.component": "POST install/component", "install.versions": "GET install/versions",
    "logs.stream": "GET logs/stream",
    "peers": "GET peers",
    "perf": "GET perf", "perf.binding": "GET perf/binding",
    "power_safety": "GET power-safety", "power_safety.acknowledge": ["POST power-safety/acknowledge", { H: JSON_ANSWER }],
    "power_safety.repair": ["POST power-safety/repair", { H: JSON_ANSWER }],
    "profiles": "GET profiles", "profiles.revision": ["GET profiles/{id}/revisions/{revision}", { H: { Accept: "application/yaml, text/yaml, text/plain" } }],
    "profiles.device_draft": ["GET profiles/device-draft", { H: { Accept: "application/yaml, text/yaml, text/plain" } }], "profiles.drivers": "GET profiles/drivers",
    "profiles.report": "GET profiles/report", "profiles.schema": "GET profiles/schema", "profiles.template": ["GET profiles/template", { H: { Accept: "application/yaml, text/yaml, text/plain" } }],
    "profiles.compare": ["POST profiles/compare", { b: "json" }], "profiles.preview": ["POST profiles/preview", { b: "json" }],
    "profiles.probe": ["POST profiles/probe", { b: "raw", t: "application/yaml; charset=utf-8", h: { preview_token: "X-Profile-Preview-Token" } }],
    "profiles.validate": ["POST profiles/validate", { b: "json" }],
    "profiles.activate": ["POST profiles/activate", { b: "json" }], "profiles.delete": ["POST profiles/delete", { b: "json" }],
    "profiles.import": ["POST profiles/import", { b: "raw", t: "application/yaml; charset=utf-8", h: { preview_token: "X-Profile-Preview-Token" } }],
    "profiles.rollback": ["POST profiles/rollback", { b: "json" }], "profiles.save": ["POST profiles/save", { b: "json" }],
    "profiles.select": ["POST profiles/select", { b: "json" }],
    "proximity": "GET proximity",
    "proximity.calibration": ["POST proximity/calibration", { H: { "X-Proximity-UI": "1" } }],
    "radio": "GET radio", "radio.join": "POST radio/join",
    // The route marks a cached capture immutable, so the browser keeps it for the page's placeholder.
    "screenshot": ["GET screenshot.png", { c: 1 }],
    "sensors": "GET sensors",
    "setup": ["GET setup", { h: { presence: "X-ha-paneld-setup-presence" } }],
    "setup.attest": "POST setup/attest", "setup.entity_filter": "POST setup/entity-filter",
    "setup.home_dashboard": "POST setup/home-dashboard", "setup.identity": "POST setup/identity",
    "status": "GET status",
    "tame": ["POST tame", { H: JSON_ANSWER }], "tame.record": "POST tame/record", "tame.suggest": "GET tame/suggest",
    "uninstall": "POST uninstall", "updates.ignore": "POST updates/ignore",
    "voice.microphone": "GET voice/microphone", "voice.pipelines": "GET voice/pipelines",
    "voice.wake_words": "GET voice/wake-words", "voice.wake_words.set": ["POST voice/wake-words", { b: "json" }],
    "voice.test": "POST voice/test",
  };
  // Channel commands the HTTP implementation can carry: action channels post to /action; every other
  // channel is a setting key and posts to /config.
  var ACTION_CHANNELS = { reload: 1, reboot: 1 };

  // ---- Subscription families (20.4), exactly as the panel defines them (ManageFamilies in
  // ManageOperations.kt; a JVM test pins the two together). A one-read family delivers that read's body;
  // a family of several reads delivers one object keyed by op name, on both transports. Floors are in
  // milliseconds.
  var FAMILIES = {
    perf: { floor: 2000, reads: ["perf"] },
    sensors: { floor: 2000, reads: ["sensors"] },
    camera: { floor: 2000, reads: ["camera.status"] },
    health: { floor: 5000, reads: ["health"] },
    config: { floor: 500, reads: ["config.values"] },
    entities: { floor: 5000, reads: ["dashboard.entities", "dashboard.entities.sync", "dashboard.entities.issues"] },
    proximity: { floor: 1000, reads: ["proximity"] },
    microphone: { floor: 2000, reads: ["voice.microphone"] },
    radio: { floor: 5000, reads: ["radio"] },
    install: { floor: 2000, reads: ["install.status", "install.apk.pending"] },
    setup: { floor: 2000, reads: ["setup", "ha.oauth.status"] },
    logs: { floor: 1000, reads: [], append: true },
  };

  var DEADLINE_MS = 10000;
  // Operations that move or apply a whole file: the most a relayed request may ask the panel for. A file the
  // panel sends crosses its own uplink first (a screenshot is about 1 MB on a large screen), which on a slow or
  // remote link takes longer than ten seconds, and Panel Assistant stops waiting at the deadline.
  var LONG_DEADLINE_MS = {
    backup: 120000, restore: 120000, "config.import": 120000, "install.apk": 120000,
    screenshot: 120000, "config.export": 120000, "dashboard.entities.export": 120000,
  };
  var BLOB_LIMIT = 256 * 1024;

  function route(op) {
    var entry = Object.prototype.hasOwnProperty.call(ROUTES, op) ? ROUTES[op] : null;
    if (!entry) return null;
    var spec = typeof entry === "string" ? entry : entry[0];
    var options = typeof entry === "string" ? {} : entry[1];
    var space = spec.indexOf(" ");
    return { method: spec.slice(0, space), path: spec.slice(space + 1), options: options };
  }

  function encodeQuery(values) {
    var parts = [];
    Object.keys(values).forEach(function (key) {
      var value = values[key];
      if (value === undefined || value === null) return;
      parts.push(encodeURIComponent(key) + "=" + encodeURIComponent(value === true ? "1" : value === false ? "0" : String(value)));
    });
    return parts.join("&");
  }

  // The HTTP form of one request: {url, init}. Shared by the HTTP implementation and the tests.
  function httpRequestFor(op, args) {
    var r = route(op);
    if (!r) return null;
    var rest = {}, headers = {}, init = { method: r.method, cache: r.options.c ? "default" : "no-store", headers: headers };
    var options = r.options;
    Object.keys(args || {}).forEach(function (key) { rest[key] = args[key]; });
    Object.keys(options.f || {}).forEach(function (key) { rest[key] = options.f[key]; });
    var path = r.path.replace(/\{(\w+)\}/g, function (_, key) {
      var value = rest[key]; delete rest[key]; return encodeURIComponent(value == null ? "" : value);
    });
    Object.keys(options.h || {}).forEach(function (key) {
      if (rest[key] != null) headers[options.h[key]] = String(rest[key]);
      delete rest[key];
    });
    Object.keys(options.H || {}).forEach(function (name) { headers[name] = options.H[name]; });
    // Root paths ("/configure") resolve against the page base like every other URL, so they work behind a proxy path.
    var url = /^\//.test(path) ? path.slice(1) : "api/v1/" + path;
    var query = {};
    if (r.method === "GET") query = rest;
    else if (options.b === "raw") {
      var body = rest.blob !== undefined ? rest.blob : rest.body;
      delete rest.blob; delete rest.body;
      query = rest;
      if (body !== undefined) init.body = body;
      if (options.t) headers["Content-Type"] = options.t;
    } else {
      (options.q || []).forEach(function (key) { if (key in rest) { query[key] = rest[key]; delete rest[key]; } });
      if (options.b === "json") {
        headers["Content-Type"] = "application/json";
        init.body = JSON.stringify(rest);
      } else if (Object.keys(rest).length) {
        headers["Content-Type"] = "application/x-www-form-urlencoded";
        init.body = encodeQuery(rest);
      }
    }
    var qs = encodeQuery(query);
    return { url: qs ? url + "?" + qs : url, init: init };
  }

  // HTTP status -> 20.2 outcome and section 11 code.
  function outcomeFor(status, body) {
    if (status === 202 && body && body.error === "approval-required") return { outcome: "pending_approval" };
    if (status >= 200 && status < 300) return { outcome: "applied" };
    if (status === 403) return { outcome: "refused", code: "refused_hardened" };
    if (status === 404 || status === 405 || status === 410) return { outcome: "refused", code: "unknown_command" };
    if (status === 409 || status === 423 || status === 429) return { outcome: "refused", code: "not_commandable" };
    if (status >= 400 && status < 500) return { outcome: "refused", code: "invalid_value" };
    if (status === 503) return { outcome: "failed", code: "hardware_unavailable" };
    return { outcome: "failed", code: "failed" };
  }

  function responseHeaders(response) {
    var out = null;
    if (!response.headers || !response.headers.forEach) return out;
    response.headers.forEach(function (value, name) {
      name = String(name).toLowerCase();
      if (name.indexOf("x-") !== 0 || name === "x-frame-options" || name === "x-content-type-options") return;
      (out = out || {})[name] = value;
    });
    return out;
  }

  // A body as the page reads it, by its (lower-case) type: JSON parsed, text as a string, anything else a Blob.
  function readBody(response, type) {
    return type.indexOf("json") >= 0 || type === ""
      ? response.text().then(function (text) {
        if (!text) return {};
        try { return JSON.parse(text); } catch (_) { return type ? { message: text } : text; }
      })
      : type.indexOf("text/") === 0 ? response.text() : response.blob();
  }

  // ---- HTTP implementation ----------------------------------------------------------------------
  function HttpTransport() {}
  HttpTransport.prototype.request = function (op, args) {
    var http = httpRequestFor(op, args);
    if (!http) return Promise.resolve({ outcome: "refused", code: "unknown_command", result: null });
    return fetch(http.url, http.init).then(function (response) {
      var type = (response.headers && response.headers.get && response.headers.get("Content-Type") || "").toLowerCase();
      return readBody(response, type).then(function (body) {
        var reply = outcomeFor(response.status, body);
        reply.result = body;
        if (response.status < 200 || response.status > 299) reply.status = response.status;
        var headers = responseHeaders(response);
        if (headers) reply.headers = headers;
        return reply;
      });
    });
  };
  HttpTransport.prototype.command = function (channel, value) {
    if (ACTION_CHANNELS[channel]) {
      var http = httpRequestFor("action.dashboard", {});
      http.init.body = encodeQuery({ a: channel });
      return fetch(http.url, http.init).then(function (response) {
        return response.text().then(function (text) {
          var body; try { body = text ? JSON.parse(text) : {}; } catch (_) { body = { message: text }; }
          var reply = outcomeFor(response.status, body); reply.result = body; return reply;
        });
      });
    }
    var settings = {}; settings[channel] = value;
    return this.request("settings", settings);
  };
  HttpTransport.prototype.start = function (sub) {
    var self = this, family = FAMILIES[sub.family];
    if (family.append) return startEventStream(sub);
    // Each start owns its loop: a poll still in flight from before a stop ends with it.
    var generation = sub.generation = (sub.generation || 0) + 1;
    var last = null;
    function poll() {
      if (sub.generation !== generation) return;
      // The subscription's args reach every read of the family, as the panel passes them (a wizard's presence).
      var reads = family.reads, single = reads.length === 1;
      Promise.all(reads.map(function (op) {
        return self.request(op, sub.args).then(function (reply) {
          if (reply.outcome !== "applied") throw reply;
          return reply.result;
        });
      })).then(function (bodies) {
        var value = bodies[0];
        if (!single) { value = {}; reads.forEach(function (op, i) { value[op] = bodies[i]; }); }
        var text = typeof value === "string" ? value : JSON.stringify(value);
        if (sub.stopped || sub.generation !== generation || text === last) return;
        last = text;
        sub.onValue(value, "known");
      }, function () {
        if (sub.stopped || sub.generation !== generation || last === "") return;
        last = "";
        sub.onValue(null, "unavailable");
      }).then(function () {
        if (!sub.stopped && sub.generation === generation) sub.timer = setTimeout(poll, family.floor);
      });
    }
    sub.stopped = false;
    poll();
  };
  HttpTransport.prototype.stop = function (sub) {
    sub.stopped = true;
    if (sub.timer) clearTimeout(sub.timer);
    if (sub.source) { sub.source.close(); sub.source = null; }
  };
  HttpTransport.prototype.close = function () {};

  // The logs family keeps the existing Server-Sent Events stream in this implementation.
  function startEventStream(sub) {
    sub.stopped = false;
    sub.fresh = true;
    var url = httpRequestFor("logs.stream", sub.args).url;
    var source = sub.source = new EventSource(url);
    source.onopen = function () { if (!sub.stopped) sub.onValue([], "known"); };
    // EventSource reconnects by itself and the panel replays the backlog, so the next opening starts afresh.
    source.onerror = function () { if (!sub.stopped) { sub.fresh = true; sub.onValue(null, "unavailable"); } };
    source.onmessage = function (event) { if (!sub.stopped) sub.onValue([event.data], "known"); };
  }

  // ---- Frame implementation (20.2 objects over a carrier) -----------------------------------------
  // A carrier opens a link: connect(handlers) returns {send(message), close()} and calls handlers.open(),
  // handlers.message(object) and handlers.close(). `reconnects` says whether a closed link is opened again by
  // itself (the socket, with backoff) or only when the page next needs it (the relay).
  function FrameTransport(carrier) {
    this.carrier = carrier;
    this.socket = null;
    this.ready = false;
    this.queue = [];
    this.pending = {};
    this.subs = {};       // subscription id -> sub
    this.live = [];       // subs that should be open
    this.attempt = 0;
    this.sequence = 0;
    this.closed = false;
    this.reconnectTimer = null;
    // The relay's recovery after a closed signal: until every live subscription is open again, subscriptions
    // are retried from 1 s, doubling to 30 s, and a subscribe answered with anything but applied stays live.
    this.recovering = false;
    this.retries = 0;
    this.retryTimer = null;
  }
  FrameTransport.prototype.nextId = function (prefix) {
    this.sequence += 1;
    return prefix + this.sequence.toString(36) + Math.random().toString(36).slice(2, 6);
  };
  FrameTransport.prototype.connect = function () {
    var self = this;
    if (self.socket || self.closed) return;
    var socket = self.socket = self.carrier.connect({ open: onopen, message: onmessage, close: onclose });
    function onopen() {
      if (self.socket !== socket) return;
      self.ready = true;
      self.attempt = 0;
      // Only requests still waiting for their answer are queued: a request that timed out or failed with
      // a closed socket left the queue when it was rejected, so a failed write never runs later.
      var queue = self.queue; self.queue = [];
      queue.forEach(function (entry) { socket.send(entry.message); self.sent(entry.id); });
      // Re-subscribe after a reconnect: the panel forgot the old subscriptions with the old socket.
      self.live.forEach(function (sub) { if (!sub.id && !sub.opening) self.open(sub); });
    }
    function onmessage(message) {
      if (self.socket !== socket) return;
      if (message && message.subscription && Array.isArray(message.observations)) {
        var sub = self.subs[message.subscription];
        if (!sub || sub.stopped) return;
        message.observations.forEach(function (observation) {
          if (observation.state === "known") sub.onValue(observation.value, "known");
          else sub.onValue(null, "unavailable");
        });
        return;
      }
      var waiter = message && self.pending[message.command_id];
      if (!waiter) return;
      if (message.outcome === "pending_approval" && message.interim) {
        // Interim: the panel holds the command until it is approved on its own screen, then answers again.
        // A pending_approval without `interim` is final, as an HTTP 202 approval answer is.
        clearTimeout(waiter.timer); waiter.timer = null;
        return;
      }
      delete self.pending[message.command_id];
      if (waiter.timer) clearTimeout(waiter.timer);
      var reply = { outcome: message.outcome, code: message.code, result: message.result === undefined ? null : message.result };
      if (message.headers) reply.headers = message.headers;
      waiter.resolve(reply);
    }
    function onclose() {
      if (self.socket !== socket) return;
      self.socket = null;
      self.ready = false;
      self.subs = {};
      var pending = self.pending; self.pending = {};
      self.queue = []; // every queued request belongs to a waiter rejected below; subscriptions reopen on reconnect
      Object.keys(pending).forEach(function (id) {
        if (pending[id].timer) clearTimeout(pending[id].timer);
        pending[id].reject(new Error("panel socket closed"));
      });
      self.live.forEach(function (sub) {
        sub.id = null; sub.opening = false;
        if (!sub.stopped) sub.onValue(null, "unavailable");
      });
      if (self.closed) return;
      if (!self.carrier.reconnects) {
        if (self.live.length) { self.recovering = true; self.retries = 0; self.retryLater(); }
        return;
      }
      // Full-jitter exponential backoff from 1 s, capped at 30 s.
      var ceiling = Math.min(30000, 1000 * Math.pow(2, self.attempt));
      self.attempt += 1;
      self.reconnectTimer = setTimeout(function () { self.reconnectTimer = null; self.connect(); }, Math.random() * ceiling);
    }
  };
  FrameTransport.prototype.retryLater = function () {
    var self = this;
    if (self.retryTimer || self.closed) return;
    var delay = Math.min(30000, 1000 * Math.pow(2, self.retries));
    self.retries += 1;
    self.retryTimer = setTimeout(function () { self.retryTimer = null; self.resume(); }, delay);
  };
  // Open the link if it is closed (its opening subscribes every live subscription), or else every live
  // subscription that is not open.
  FrameTransport.prototype.resume = function () {
    var self = this;
    if (!self.socket) { self.connect(); return; }
    if (self.ready) self.live.forEach(function (sub) { if (!sub.id && !sub.opening) self.open(sub); });
  };
  // The sidebar's open signal: the relay works again, so reopen now rather than at the next retry.
  FrameTransport.prototype.recover = function () {
    if (this.retryTimer) { clearTimeout(this.retryTimer); this.retryTimer = null; }
    this.retries = 0;
    this.resume();
  };
  FrameTransport.prototype.send = function (message) {
    this.connect();
    if (this.ready) { this.socket.send(message); this.sent(message.command_id); } else this.queue.push({ id: message.command_id, message: message });
  };
  // A request that has left is answered, or fails with its socket, as an HTTP request is; it is never given
  // up while the panel may still apply it, so its no-answer timer only runs while it waits in the queue.
  FrameTransport.prototype.sent = function (id) {
    var waiter = this.pending[id];
    if (waiter && waiter.timer) { clearTimeout(waiter.timer); waiter.timer = null; }
  };
  FrameTransport.prototype.exchange = function (message, deadline) {
    var self = this;
    return new Promise(function (resolve, reject) {
      var waiter = { resolve: resolve, reject: reject, timer: null };
      self.pending[message.command_id] = waiter;
      // Still queued well past the deadline: the request never reached a panel that could answer.
      waiter.timer = setTimeout(function () {
        delete self.pending[message.command_id];
        self.queue = self.queue.filter(function (entry) { return entry.id !== message.command_id; });
        reject(new Error("no answer from the panel"));
      }, deadline + 5000);
      self.send(message);
    });
  };
  FrameTransport.prototype.request = function (op, args, options) {
    var self = this;
    var deadline = (options && options.deadline_ms) || LONG_DEADLINE_MS[op] || DEADLINE_MS;
    return uploadBlobArgs(self, args || {}).then(function (sent) {
      var message = { kind: "manage", command_id: self.nextId("r"), op: op, args: sent, deadline_ms: deadline };
      // The existing `settings` op carries its keys beside args, as Panel Assistant sends them.
      if (op === "settings") message.settings = sent;
      return self.exchange(message, deadline);
    }).then(function (reply) {
      var blob = reply.result && reply.result.blob;
      if (!blob || typeof blob.url !== "string") return reply;
      // A body too large for a frame reads as it would have inline; binary and a download stay bytes.
      var attachment = /^attachment/i.test((reply.headers && reply.headers["content-disposition"]) || "");
      var type = String(blob.type || "").toLowerCase();
      return fetch(blob.url, { cache: "no-store" }).then(function (response) {
        if (!response.ok) throw new Error("blob download failed");
        return attachment || !type ? response.blob() : readBody(response, type);
      }).then(function (body) { reply.result = body; return reply; });
    });
  };
  FrameTransport.prototype.command = function (channel, value) {
    return this.exchange({ kind: "command", command_id: this.nextId("c"), channel: channel, value: value, deadline_ms: DEADLINE_MS }, DEADLINE_MS);
  };
  FrameTransport.prototype.open = function (sub) {
    var self = this;
    sub.opening = true;
    sub.fresh = true;
    var args = { family: sub.family };
    Object.keys(sub.args || {}).forEach(function (key) { args[key] = sub.args[key]; });
    var message = { kind: "manage", command_id: self.nextId("s"), op: "subscribe", args: args, deadline_ms: DEADLINE_MS };
    // Register before the answer: the first update may arrive in the same burst as the answer.
    self.subs[message.command_id] = sub;
    self.exchange(message, DEADLINE_MS).then(function (reply) {
      sub.opening = false;
      var id = reply.outcome === "applied" && reply.result && reply.result.subscription;
      if (id && id !== message.command_id) { delete self.subs[message.command_id]; self.subs[id] = sub; }
      if (!id) {
        delete self.subs[message.command_id];
        if (sub.stopped) return;
        sub.onValue(null, "unavailable");
        if (self.recovering) self.retryLater();
        return;
      }
      sub.id = id;
      if (sub.stopped) self.unsubscribe(sub);
      if (self.recovering && self.live.every(function (live) { return live.id; })) {
        self.recovering = false; self.retries = 0;
        if (self.retryTimer) { clearTimeout(self.retryTimer); self.retryTimer = null; }
      }
    }, function () { sub.opening = false; delete self.subs[message.command_id]; });
  };
  FrameTransport.prototype.unsubscribe = function (sub) {
    var id = sub.id;
    sub.id = null;
    if (!id) return;
    delete this.subs[id];
    if (this.ready) this.exchange({ kind: "manage", command_id: this.nextId("u"), op: "unsubscribe", args: { subscription: id }, deadline_ms: DEADLINE_MS }, DEADLINE_MS).catch(function () {});
  };
  FrameTransport.prototype.start = function (sub) {
    sub.stopped = false;
    if (this.live.indexOf(sub) < 0) this.live.push(sub);
    this.connect();
    if (!sub.opening && !sub.id) this.open(sub);
  };
  FrameTransport.prototype.stop = function (sub) {
    sub.stopped = true;
    var index = this.live.indexOf(sub);
    if (index >= 0) this.live.splice(index, 1);
    this.unsubscribe(sub);
  };
  FrameTransport.prototype.close = function () {
    this.closed = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.retryTimer) clearTimeout(this.retryTimer);
    if (this.socket) this.socket.close();
  };

  // The stub door's carrier: one WebSocket, opened again with backoff when it closes.
  function socketCarrier(url) {
    return {
      reconnects: true,
      connect: function (handlers) {
        var socket = new WebSocket(url);
        socket.onopen = handlers.open;
        socket.onmessage = function (event) {
          var message;
          try { message = JSON.parse(event.data); } catch (_) { return; }
          handlers.message(message);
        };
        socket.onclose = handlers.close;
        return { send: function (message) { socket.send(JSON.stringify(message)); }, close: function () { socket.close(); } };
      },
    };
  }

  // The sidebar relay's carrier: frames posted to Panel Assistant's sidebar, the page's parent, on the page's own
  // origin. Only the parent's messages of this type are read. {closed: true} closes the link as a socket close
  // does; it opens again on the next frame the page sends, after the transport's retry backoff, or at once on
  // {open: true}, which the sidebar posts when the relay works again.
  var RELAY_TYPE = "panel-assistant-ui";
  function relayCarrier(onOpenSignal) {
    var current = null;
    window.addEventListener("message", function (event) {
      var data = event.data;
      if (event.source !== window.parent || event.origin !== location.origin || !data || data.type !== RELAY_TYPE) return;
      if (data.closed) { var link = current; current = null; if (link) link.handlers.close(); return; }
      if (data.open) { onOpenSignal(); return; }
      if (current && data.frame) current.handlers.message(data.frame);
    });
    return {
      reconnects: false,
      connect: function (handlers) {
        var link = current = { handlers: handlers };
        Promise.resolve().then(function () { if (current === link) handlers.open(); });
        return {
          send: function (message) { window.parent.postMessage({ type: RELAY_TYPE, frame: message }, location.origin); },
          close: function () { if (current === link) current = null; },
        };
      },
    };
  }

  // 20.5 reader-to-panel: a Blob in args.blob is uploaded through blob.upload first and replaced by its
  // reference. Text bodies (args.body) under the frame limit ride the request itself.
  function uploadBlobArgs(transport, args) {
    var blob = args.blob;
    var isBlob = typeof Blob !== "undefined" && blob instanceof Blob;
    var text = typeof args.body === "string" ? args.body : null;
    if (!isBlob && !(text !== null && text.length > BLOB_LIMIT)) return Promise.resolve(args);
    var bytesBlob = isBlob ? blob : new Blob([text], { type: "text/plain" });
    var copy = {};
    Object.keys(args).forEach(function (key) { if (key !== "blob" && key !== "body") copy[key] = args[key]; });
    return bytesBlob.arrayBuffer().then(function (buffer) {
      var bytes = new Uint8Array(buffer);
      var ref = { size: bytes.length, sha256: sha256Hex(bytes), type: bytesBlob.type || "application/octet-stream" };
      return transport.request("blob.upload", ref).then(function (reply) {
        var target = reply.result && (reply.result.url || (reply.result.blob && reply.result.blob.url));
        if (reply.outcome !== "applied" || !target) throw new Error("blob upload refused");
        return fetch(target, { method: "PUT", body: bytes, headers: { "Content-Type": ref.type } }).then(function (response) {
          if (!response.ok) throw new Error("blob upload failed");
          ref.url = target;
          copy.blob = ref;
          return copy;
        });
      });
    });
  }

  // SHA-256 in plain JavaScript: crypto.subtle exists only in secure contexts, and a panel on the LAN
  // is served over plain HTTP.
  var K = [];
  (function () {
    function frac(x) { return (x - Math.floor(x)) * 0x100000000 | 0; }
    for (var n = 2, found = 0; found < 64; n++) {
      var prime = true;
      for (var d = 2; d * d <= n; d++) if (n % d === 0) { prime = false; break; }
      if (prime) { K.push(frac(Math.pow(n, 1 / 3))); found++; }
    }
  })();
  function sha256Hex(bytes) {
    var h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    var length = bytes.length, padded = new Uint8Array(((length + 9 + 63) >> 6) << 6);
    padded.set(bytes); padded[length] = 0x80;
    var bits = length * 8, view = new DataView(padded.buffer);
    view.setUint32(padded.length - 8, Math.floor(bits / 0x100000000));
    view.setUint32(padded.length - 4, bits >>> 0);
    var w = new Array(64);
    for (var offset = 0; offset < padded.length; offset += 64) {
      for (var i = 0; i < 16; i++) w[i] = view.getUint32(offset + i * 4);
      for (i = 16; i < 64; i++) {
        var a0 = w[i - 15], a1 = w[i - 2];
        var s0 = (a0 >>> 7 | a0 << 25) ^ (a0 >>> 18 | a0 << 14) ^ (a0 >>> 3);
        var s1 = (a1 >>> 17 | a1 << 15) ^ (a1 >>> 19 | a1 << 13) ^ (a1 >>> 10);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
      }
      var a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], k = h[7];
      for (i = 0; i < 64; i++) {
        var t1 = (k + ((e >>> 6 | e << 26) ^ (e >>> 11 | e << 21) ^ (e >>> 25 | e << 7)) + ((e & f) ^ (~e & g)) + K[i] + w[i]) | 0;
        var t2 = (((a >>> 2 | a << 30) ^ (a >>> 13 | a << 19) ^ (a >>> 22 | a << 10)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
        k = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      h[0] = (h[0] + a) | 0; h[1] = (h[1] + b) | 0; h[2] = (h[2] + c) | 0; h[3] = (h[3] + d) | 0;
      h[4] = (h[4] + e) | 0; h[5] = (h[5] + f) | 0; h[6] = (h[6] + g) | 0; h[7] = (h[7] + k) | 0;
    }
    return h.map(function (word) { return ("00000000" + (word >>> 0).toString(16)).slice(-8); }).join("");
  }

  // ---- Selection, subscriptions and the hidden-tab rule ------------------------------------------
  var impl = null;
  var subscriptions = [];
  function transport() {
    if (impl) return impl;
    var body = document.body;
    var data = body && body.dataset ? body.dataset : {};
    var door = data.uiSocket;
    if (data.uiRelay !== undefined && window.parent && window.parent !== window) {
      var relay = impl = new FrameTransport(relayCarrier(function () { relay.recover(); }));
    } else if (door !== undefined && typeof WebSocket !== "undefined") {
      // Base-relative, like every page URL, so the door is found behind a proxy path too.
      var url = new URL(door || "ui", document.baseURI || location.href);
      url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
      impl = new FrameTransport(socketCarrier(url.href));
    } else {
      impl = new HttpTransport();
    }
    return impl;
  }
  function hidden() { return !!document.hidden; }

  function subscribe(family, onValue, args) {
    if (!Object.prototype.hasOwnProperty.call(FAMILIES, family)) throw new Error("unknown family " + family);
    var sub = { family: family, args: args || {}, stopped: true, closed: false, fresh: false };
    // The first known value after the panel (re)opened the subscription is flagged: an append family's first
    // update is its backlog, which replaces what an earlier opening delivered.
    sub.onValue = function (value, state) {
      var first = sub.fresh && state === "known";
      if (first) sub.fresh = false;
      onValue(value, state, first);
    };
    subscriptions.push(sub);
    if (!hidden()) transport().start(sub);
    return {
      close: function () {
        if (sub.closed) return;
        sub.closed = true;
        var index = subscriptions.indexOf(sub);
        if (index >= 0) subscriptions.splice(index, 1);
        if (!sub.stopped) transport().stop(sub);
      },
    };
  }

  if (document.addEventListener) {
    document.addEventListener("visibilitychange", function () {
      subscriptions.forEach(function (sub) {
        if (hidden() && !sub.stopped) transport().stop(sub);
        else if (!hidden() && sub.stopped) transport().start(sub);
      });
    });
  }
  // Leaving the page ends its subscriptions too: in the sidebar the relay outlives the page, and the panel
  // holds at most eight families per browser connection. A page restored from the back-forward cache reopens them.
  if (typeof window.addEventListener === "function") {
    window.addEventListener("pagehide", function () {
      if (impl) subscriptions.forEach(function (sub) { if (!sub.stopped) impl.stop(sub); });
    });
    window.addEventListener("pageshow", function (event) {
      if (event && event.persisted && !hidden()) subscriptions.forEach(function (sub) { if (sub.stopped) transport().start(sub); });
    });
  }

  // Server-rendered forms that post to api/v1/... go through the transport when JavaScript runs. A
  // page module that owns a form handles it first and prevents the default; this one runs last.
  var NAMES_BY_PATH = {};
  Object.keys(ROUTES).forEach(function (name) {
    var r = route(name);
    if (r.method === "POST" && !r.options.f && !NAMES_BY_PATH[r.path]) NAMES_BY_PATH[r.path] = name;
  });
  function formName(form) {
    var action;
    try { action = new URL(form.getAttribute("action") || "", document.baseURI || location.href); } catch (_) { return null; }
    var base = new URL("api/v1/", document.baseURI || location.href);
    if (action.origin !== base.origin || action.pathname.indexOf(base.pathname) !== 0) return null;
    return NAMES_BY_PATH[action.pathname.slice(base.pathname.length)] || null;
  }
  if (typeof window.addEventListener === "function") {
    window.addEventListener("submit", function (event) {
      var form = event.target;
      if (event.defaultPrevented || !form || String(form.method).toLowerCase() !== "post") return;
      var name = formName(form);
      if (!name) return;
      event.preventDefault();
      var args = {};
      new FormData(form).forEach(function (value, key) { args[key] = value; });
      if (event.submitter && event.submitter.name) args[event.submitter.name] = event.submitter.value;
      var status = form.querySelector("[role=status]");
      api.request(name, args).then(function (reply) {
        if (reply.outcome === "applied") { location.reload(); return; }
        var body = reply.result || {};
        var text = body.message || body.error || reply.code || reply.outcome;
        if (status) status.textContent = text; else window.alert(text);
      }, function (error) {
        if (status) status.textContent = String(error && error.message || error); else window.alert(String(error && error.message || error));
      });
    });
  }

  var api = {
    request: function (op, args, options) { return transport().request(op, args || {}, options); },
    command: function (channel, value) { return transport().command(channel, value); },
    subscribe: subscribe,
    close: function () {
      subscriptions.slice().forEach(function (sub) { if (!sub.stopped) transport().stop(sub); sub.closed = true; });
      subscriptions = [];
      if (impl) impl.close();
      impl = null;
    },
    // Data, for the generic form handler's tests and the fixture dispatcher.
    routes: ROUTES,
    families: FAMILIES,
    httpRequestFor: httpRequestFor,
    outcomeFor: outcomeFor,
    sha256Hex: sha256Hex,
  };
  window.PaneldTransport = api;
})();
