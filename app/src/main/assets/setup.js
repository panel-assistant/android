/* Guided setup (/setup): renders ONE step at a time from GET /api/v1/setup — the same authority the
 * panel itself reads, so the browser and the panel can never disagree about what comes next.
 *
 * Rules that keep this file safe and honest:
 *  - It NEVER reads GET /api/v1/config. That endpoint blanks secrets, and a POST assembled from its
 *    output has previously wiped a panel's credentials. Everything posted here was typed on this page
 *    (or accepted from the read-only discovery endpoint into a visibly editable field), and passwords
 *    are never pre-filled.
 *  - A 200 from POST /api/v1/config means "saved", not "working". MQTT verification runs afterwards,
 *    so saving flows into a verifying phase that polls the journey and reports what is actually
 *    happening — and admits, on a schedule, when something is probably wrong.
 *  - While saving or verifying, the step's fields are disabled (a native <fieldset> disable), because
 *    the alternative is a user editing other values mid-validation and losing track of what applied.
 *    The escape link to full settings stays live; the wizard never traps.
 */
(function () {
  "use strict";

  var stepHost = document.getElementById("wiz-step");
  var dotsHost = document.getElementById("wiz-dots");

  var journey = null;        // last GET /api/v1/setup body
  var typed = {};            // values the user has touched THIS page-load; the only POST source
  var discovery = null;      // one-shot /api/v1/config/discovery result (read-only suggestions)
  var verify = null;         // {t0, host} while an MQTT save is being checked
  var renderedKey = "";      // what the current DOM was built for — rebuild only on real change
  var pollTimer = null;
  var ladderTimer = null;

  /* Guarded like every secondary page: a missing or malformed catalogue projection must leave the
   * complete English wizard usable. Call sites remain the authority for that fallback copy. */
  function i18nText(key, fallback, values) {
    if (window.HaI18n && typeof window.HaI18n.t === "function") {
      return window.HaI18n.t(key, fallback, values);
    }
    return String(fallback == null ? "" : fallback).replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g,
      function (placeholder, name) {
        return values && Object.prototype.hasOwnProperty.call(values, name)
          ? String(values[name]) : placeholder;
      });
  }

  function requestedLocale() {
    return window.HaI18n && typeof window.HaI18n.locale === "string"
      ? window.HaI18n.locale : (document.documentElement.lang || "en");
  }

  function localizedNumber(value) {
    try { return Number(value).toLocaleString(requestedLocale()); }
    catch (_) { return Number(value).toLocaleString(); }
  }

  function pluralCategory(value) {
    try { return new Intl.PluralRules(requestedLocale()).select(Number(value)); }
    catch (_) { return Number(value) === 1 ? "one" : "other"; }
  }

  /* Authored links stay in the request locale. Opaque external OAuth and discovered URLs never pass
   * through this helper. Fragment-only links already retain the current query by browser semantics. */
  function internalHref(path) {
    var params = new URLSearchParams(location.search);
    // en-XA is emitted only by debug builds, but when it is the server-resolved locale its authored
    // links must retain the pseudolocale just like every release locale.
    var supported = ["en", "de", "fr", "it", "es", "zh-Hans", "nl", "pl", "uk", "cs", "pt-BR", "en-XA"];
    if (!params.has("lang") && !params.has("ha_lang")) return path;
    var lang = requestedLocale();
    if (supported.indexOf(lang) === -1) return path;
    var url = new URL(path, document.baseURI);
    if (url.origin !== location.origin) return path;
    url.searchParams.set("lang", lang);
    // Keep authored links base-relative, as the server renders them.
    var base = new URL(".", document.baseURI).href;
    if (url.href.indexOf(base) !== 0) return url.href;
    var relative = url.href.slice(base.length);
    return /^[?#]/.test(relative) || relative === "" ? "./" + relative : relative;
  }

  function el(tag, attrs, kids) {
    var n = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === "text") n.textContent = attrs[k];
      else if (k.indexOf("on") === 0) n.addEventListener(k.slice(2), attrs[k]);
      else n.setAttribute(k, attrs[k]);
    });
    (kids || []).forEach(function (k) { if (k) n.appendChild(k); });
    return n;
  }

  function step(name) {
    var s = (journey && journey.steps || []).filter(function (x) { return x.stage === name; })[0];
    return s || { stage: name, status: "unknown", detail: "" };
  }

  /* The registry's slug rule, mirrored so the preview shows what will actually be stored. */
  function slug(v) {
    return String(v || "").toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 63);
  }

  function brokerHost(url) {
    var m = /^[a-z+]+:\/\/(\[[^\]]+\]|[^:/]+)/i.exec(String(url || "").trim());
    return m ? m[1] : "";
  }

  /* ---------- server I/O (POST bodies come from `typed`, never from a GET) ---------- */

  function getJourney() {
    return fetch("api/v1/setup", {
      cache: "no-store",
      headers: { "X-ha-paneld-setup-presence": "active" },
    }).then(function (r) { return r.json(); });
  }

  function postForm(path, fields) {
    return postFormAttempt(path, fields, 2);
  }

  function postFormAttempt(path, fields, retriesLeft) {
    return fetch(path, {
      method: "POST",
      headers: { "Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams(fields).toString(),
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (body) {
        if (r.status === 202 && body.error === "approval-required") throw { approval: true, message: i18nText("setup.error.approval_required", "Approve this request on the panel, then save again.") };
        if (!r.ok) throw { message: body.error || body.message || i18nText("setup.error.http_refused", "The panel refused this (HTTP {status}).", { status: r.status }) };
        return body;
      });
    }, function (networkError) {
      // A network-level failure rejects the fetch itself with a TypeError whose text is browser-specific
      // (Safari and Chrome word it differently) — never show that raw. It means the request never
      // reached the panel. Retry ONCE, quietly: the panel's own brief restarts (config reconfigure,
      // deliberate process boundaries) failed real first-run saves under the user's finger on hardware,
      // and one two-second retry absorbs exactly that class of blip without hiding a genuine outage.
      if (retriesLeft > 0) {
        var delay = retriesLeft === 2 ? 2000 : 5000;
        return new Promise(function (resolve) { setTimeout(resolve, delay); })
          .then(function () { return postFormAttempt(path, fields, retriesLeft - 1); });
      }
      throw { message: i18nText("setup.error.panel_unreachable", "Couldn’t reach the panel — check the connection and try again.") };
    });
  }

  /* ---------- progress dots ---------- */

  /* The strip is a POSITION, not a checklist: exactly one dot is current — the one the journey's
   * `next` falls inside — dots before it read done, dots after it stay quiet. Deriving "current" from
   * per-stage status instead was tried and wrong: render_proof staying unknown made the Dashboard dot
   * claim "current" while the user was actually on Sign in. */
  // Home Assistant BEFORE MQTT: suggested_area applies only at the device's first registration (which
  // happens the moment MQTT connects), and valid area names are only readable after sign-in — this is
  // the one order in which a panel lands in its correct HA area for every user, admin or not.
  var DOT_OF_STAGE = {
    identity: 0,
    renderer: 1, ha_url: 1,
    ha_credentials: 2,
    home_dashboard: 3,
    mqtt_broker: 4, mqtt_credentials: 4, mqtt_connection: 4,
    // The filter question and the render that follows it are the same final dot: the render is what the
    // answer causes, so showing it as a step of its own would claim there is another decision to make.
    entity_filter: 5, render_proof: 5,
  };

  function renderDots() {
    // Repair mode: a numbered journey implies starting over, which is exactly the wrong message on a
    // panel that already worked. One thing broke; the step card names it. No strip.
    if (journey.repair) { dotsHost.textContent = ""; dotsHost.hidden = true; return; }
    dotsHost.hidden = false;
    // Dot 1 covers the renderer choice AND the HA server address; dot 3 is which HA dashboard shows.
    var labels = [
      i18nText("setup.progress.name", "Name"), i18nText("setup.progress.server", "Server"),
      i18nText("setup.progress.sign_in", "HA sign-in"), i18nText("setup.progress.dashboard", "HA dash"),
      i18nText("setup.progress.mqtt", "MQTT"), i18nText("setup.progress.optimize", "Optimize")
    ];
    var currentIdx = journey.complete ? labels.length : (DOT_OF_STAGE[journey.next] !== undefined ? DOT_OF_STAGE[journey.next] : labels.length);
    var anyInFlight = (journey.steps || []).some(function (s) {
      return s.status === "in_flight" && DOT_OF_STAGE[s.stage] === currentIdx;
    });
    dotsHost.textContent = "";
    labels.forEach(function (label, i) {
      if (i === 2 && step("ha_credentials").status === "skipped") return; // foreign renderer signs itself in
      // A foreign renderer navigates itself, so there is no dashboard choice to promise either.
      if (i === 3 && step("home_dashboard").status === "skipped") return;
      // A panel Panel Assistant carries has no broker to ask about.
      if (i === 4 && step("mqtt_broker").status === "skipped") return;
      // A foreign renderer owns its own subscription, so there is no filter question to promise.
      if (i === 5 && step("entity_filter").status === "skipped") return;
      var done = i < currentIdx || journey.complete;
      var cls = done ? "done" : i === currentIdx ? ((anyInFlight || verify) ? "busy" : "current") : "";
      // Completed dots are LINKS back to their step (the strip stays a position indicator; the links are
      // navigation, not state). The current and future dots are not links — there is no skipping ahead,
      // the journey authority owns what comes next. The hash also makes every visitable page
      // URL-addressable (/setup#broker), so the browser back button and manual deep-links work too.
      if (done && HASH_OF_DOT[i]) {
        dotsHost.appendChild(el("li", { class: cls }, [el("a", { href: "#" + HASH_OF_DOT[i], text: label, onclick: hashLink })]));
      } else {
        dotsHost.appendChild(el("li", { class: cls, text: label }));
      }
    });
  }

  /* Under <base href="/"> a bare "#step" link would resolve against the base and leave this page, so hash
   * links set the fragment themselves and cancel the navigation. */
  function hashLink(ev) {
    ev.preventDefault();
    location.hash = ev.currentTarget.getAttribute("href");
  }

  /* Back-navigation: a hash naming a COMPLETED step forces that card; anything else — future steps,
   * unknown names, repair mode — is ignored in favour of the journey's real next. */
  var HASH_OF_DOT = { 0: "name", 1: "server", 2: "signin", 3: "dash", 4: "mqtt", 5: "optimize" };
  var KEY_OF_HASH = { name: "identity", server: "renderer", signin: "signin",
    dash: "home_dashboard", mqtt: "mqtt", optimize: "entity_filter" };
  var DOT_OF_KEY = { identity: 0, renderer: 1, signin: 2, home_dashboard: 3, mqtt: 4, entity_filter: 5 };
  var lastRealNext; // clears a revisit automatically once a re-save moves the real journey

  function forcedRevisitKey() {
    var key = KEY_OF_HASH[(location.hash || "").replace("#", "")];
    if (!key || !journey || journey.repair) return null;
    var currentIdx = journey.complete ? 99 : (DOT_OF_STAGE[journey.next] !== undefined ? DOT_OF_STAGE[journey.next] : 99);
    return DOT_OF_KEY[key] < currentIdx ? key : null;
  }

  function clearRevisit() {
    try { history.replaceState(null, "", location.pathname + location.search); } catch (e) { location.hash = ""; }
  }

  /* ---------- shared step scaffolding ---------- */

  function field(key, label, help, attrs) {
    var input = el("input", Object.assign({
      id: "wiz-" + key, type: "text", autocomplete: "off", autocapitalize: "none", spellcheck: "false",
      oninput: function (e) {
        if (attrs && attrs.sanitize) {
          var caret = e.target.selectionStart;
          var before = e.target.value;
          var after = attrs.sanitize(before);
          if (after !== before) {
            e.target.value = after;
            // The panel-id sanitizer is 1:1 length-preserving, so the caret does not move; guard anyway.
            try { e.target.setSelectionRange(caret, caret); } catch (x) { /* detached */ }
          }
        }
        typed[key] = e.target.value;
        if (attrs && attrs.oninputExtra) attrs.oninputExtra(e);
      },
    }, attrs && attrs.input || {}));
    if (typed[key] !== undefined) input.value = typed[key];
    else if (attrs && attrs.value) { input.value = attrs.value; }
    return el("label", { class: "wiz-fld" }, [
      el("b", { text: label }),
      input,
      help ? el("small", { text: help }) : null,
    ]);
  }

  function card(title, lead, kids) {
    return el("div", { class: "card" }, [
      el("h2", { text: title }),
      lead ? el("p", { class: "wiz-lead", text: lead }) : null,
    ].concat(kids));
  }

  function primary(label, onclick) {
    return el("button", { class: "wiz-primary", type: "button", text: label, onclick: onclick });
  }

  function statusLine(text, ok) {
    return el("p", {
      class: "wiz-status" + (ok ? " ok" : ""), id: "wiz-live",
      role: "status", "aria-live": "polite", "aria-atomic": "true", text: text,
    });
  }

  function failBanner(title, lines, crit) {
    var d = el("div", { class: "setup" + (crit ? " crit" : "") });
    d.appendChild(el("b", { text: title }));
    lines.forEach(function (t) { d.appendChild(el("p", { text: t })); });
    return d;
  }

  function show(kids, key) {
    renderedKey = key;
    stepHost.textContent = "";
    // The single most important anti-factory-reset signal: a returning user seeing a step card must be
    // told their settings survived BEFORE they see an empty-looking password field and conclude a wipe.
    if (journey && journey.repair && key !== "done") {
      stepHost.appendChild(el("div", { class: "setup info" }, [
        el("b", { text: i18nText("setup.repair.title", "This panel has stopped working fully.") + " " }),
        document.createTextNode(i18nText("setup.repair.explanation", "Its settings are still here — nothing has been reset. One thing needs attention below.")),
      ]));
    }
    kids.forEach(function (k) { if (k) stepHost.appendChild(k); });
    var h = stepHost.querySelector("h2");
    if (h) { h.setAttribute("tabindex", "-1"); h.focus({ preventScroll: false }); }
  }

  /* ---------- step 1: name this panel ---------- */

  function identityCard() {
    var id = typed.panel_id !== undefined ? typed.panel_id : (journey.panel && journey.panel.id || "");
    var preview = el("div", { class: "wiz-preview", "aria-hidden": "true" });
    function paint(v) {
      var s = slug(v) || "panel";
      preview.textContent = "";
      preview.appendChild(document.createTextNode(i18nText("setup.identity.preview_intro",
        "Home Assistant will name everything on this panel from it:")));
      ["sensor." + s + "_temperature", "light." + s + "_screen", "button." + s + "_restart"]
        .forEach(function (entityId) {
          preview.appendChild(el("br"));
          preview.appendChild(document.createTextNode("\u00a0\u00a0"));
          preview.appendChild(el("b", { text: entityId }));
        });
    }
    paint(id);
    var body = card(i18nText("setup.identity.title", "Name this panel"),
      // Matches the real journey (the dots above): name, what the screen shows, the sign-in and the
      // dashboard. Kept loose ("a few quick questions") rather than a count, because steps are skipped for
      // a Companion-app renderer, and the broker is never promised: a panel Panel Assistant sets up has none.
      i18nText("setup.identity.lead", "A name, your Home Assistant server and sign-in, and the dashboard to show — a few quick questions and you’re done. You can stop and come back; nothing is lost."), [
      el("fieldset", { id: "wiz-fs", style: "border:0;padding:0;margin:0" }, [
        field("panel_id", i18nText("setup.identity.panel_id.label", "Panel ID"),
          i18nText("setup.identity.panel_id.help", "Pick something you’ll recognise in a long list — where it is, usually. Lowercase letters, digits and underscores."),
          {
            // Home Assistant object_ids are [a-z0-9_], so prohibited characters are converted as typed
            // rather than silently rewritten on save: what the field shows is what gets stored.
            value: id,
            sanitize: function (v) { return String(v).toLowerCase().replace(/[^a-z0-9_]/g, "_"); },
            oninputExtra: function (e) { paint(e.target.value); },
          }),
        preview,
        el("p", { class: "wiz-consequence", text: i18nText("setup.identity.panel_id.consequence", "You can change this later, but it renames every one of those entities, so anything already pointing at them (automations, dashboards, scripts) would need updating too. Most people set it once, here.") }),
        field("friendly_name", i18nText("setup.identity.friendly_name.label", "Friendly Name"), i18nText("setup.identity.friendly_name.help", "Just the label in Home Assistant. Safe to change any time."),
          { value: typed.friendly_name !== undefined ? typed.friendly_name : (journey.panel && journey.panel.name || "") }),
        primary(i18nText("setup.action.save_continue", "Save and continue"), saveIdentity),
      ]),
      el("p", { class: "muted", id: "wiz-err", role: "alert" }),
    ]);
    show([body], "identity");
  }

  function saveIdentity() {
    var fields = {};
    var id = typed.panel_id !== undefined ? typed.panel_id : (journey.panel && journey.panel.id || "");
    if (slug(id) === "") { stepErr(i18nText("setup.identity.error.name_required", "The panel needs a name — letters and digits.")); return; }
    if (typed.panel_id !== undefined) fields.panel_id = typed.panel_id;
    if (typed.friendly_name !== undefined) fields.friendly_name = typed.friendly_name;
    var startKey = renderedKey;
    lockStep(true);
    (Object.keys(fields).length ? postForm("api/v1/config", fields) : Promise.resolve({}))
      .then(function () { return postForm("api/v1/setup/identity", {}); })
      .then(function () { typed = {}; refresh(); })
      // A save can resolve after the poll has already advanced the step (server committed, response lost);
      // don't paint this step's stale error onto the next one.
      .catch(function (e) { lockStep(false); if (renderedKey === startKey) stepErr(e.message); });
  }

  /* ---------- step 2: connect to MQTT ---------- */

  function discoveryNote(forField) {
    var d = journey.discovery || {};
    if (discovery && discovery[forField] && typed[forField] === undefined) {
      return el("div", { class: "setup info", text: i18nText("setup.discovery.found", "Found on your network: {value}. Change it if that’s not right.", { value: discovery[forField] }) });
    }
    if (d.outcome === "unavailable") {
      var reasons = {
        broker_not_on_link: ["setup.discovery.unavailable.broker_not_on_link", "We couldn’t find this automatically — Home Assistant is on a different network segment from this panel, and automatic discovery only works within one segment. That’s normal on some networks — type it below; it works exactly the same."],
        mdns_not_running: ["setup.discovery.unavailable.mdns_not_running", "We couldn’t find this automatically — this panel’s network discovery service is not running. That’s normal on some networks — type it below; it works exactly the same."],
        no_multicast_lock: ["setup.discovery.unavailable.no_multicast_lock", "We couldn’t find this automatically — this panel cannot receive network discovery traffic. That’s normal on some networks — type it below; it works exactly the same."],
        no_responses_at_all: ["setup.discovery.unavailable.no_responses_at_all", "We couldn’t find this automatically — nothing answered this panel’s discovery request, so Home Assistant may be on another network segment or discovery traffic may be blocked. That’s normal on some networks — type it below; it works exactly the same."],
        error: ["setup.discovery.unavailable.error", "We couldn’t find this automatically — this panel’s network discovery attempt failed. That’s normal on some networks — type it below; it works exactly the same."]
      };
      var reasonCopy = reasons[d.reason];
      return el("div", { class: "setup info", text: reasonCopy
        ? i18nText(reasonCopy[0], reasonCopy[1])
        : i18nText("setup.discovery.unavailable.generic", "We couldn’t find this automatically. That’s normal on some networks — type it below; it works exactly the same.") });
    }
    return null;
  }

  function mqttCard(failure) {
    // A page reload at this step loses the module-state catalog fetched on the dashboard page; refetch
    // quietly so the username prefill still lands (into an untouched, still-empty field only).
    if (!hdArea) {
      fetch("api/v1/config/ha-area", { cache: "no-store" })
        .then(function (r) { return r.json(); })
        .then(function (a) {
          hdArea = a;
          var f = document.getElementById("wiz-mqtt_user");
          if (f && !f.value && typed.mqtt_user === undefined && a && a.ha_username) f.value = a.ha_username;
        })
        .catch(function () {});
    }
    var creds = step("mqtt_credentials");
    var pwHelp = creds.status === "satisfied"
      ? i18nText("setup.mqtt.password.help_existing", "The password isn’t shown for security. Leave it blank to keep the current one, or type a new one.")
      : i18nText("setup.mqtt.password.help_new", "The password for that Home Assistant user.");
    var kids = [
      // A failure means an address was already entered — repeating "we couldn't find one automatically"
      // next to "nothing answered at X" is two banners saying less than one.
      failure ? null : discoveryNote("mqtt_broker"),
      failure,
      el("fieldset", { id: "wiz-fs", style: "border:0;padding:0;margin:0" }, [
        field("mqtt_broker", i18nText("setup.mqtt.broker.label", "Broker URL"), i18nText("setup.mqtt.broker.help", "Usually tcp:// then your Home Assistant’s address and :1883."),
          { input: { inputmode: "url", placeholder: "tcp://192.168.1.10:1883" },
            value: discovery && discovery.mqtt_broker || "" }),
        // Prefilled with the signed-in account's LOGIN name when readable (admin accounts only — HA
        // exposes the shortname nowhere else): the Mosquitto add-on authenticates against HA users, so
        // it is the likely MQTT username. Visibly editable like every suggestion.
        field("mqtt_user", i18nText("setup.mqtt.username.label", "Username"),
          i18nText("setup.mqtt.username.help", "Usually your Home Assistant login name — the name typed on HA’s sign-in screen, not the display name. Many people create a dedicated HA user just for panels."),
          { value: (hdArea && hdArea.ha_username) || "" }),
        field("mqtt_password", i18nText("setup.mqtt.password.label", "Password"), pwHelp, { input: { type: "password", placeholder: creds.status === "satisfied" ? i18nText("setup.mqtt.password.unchanged", "(unchanged)") : "" } }),
        el("details", {}, [
          el("summary", { text: i18nText("setup.mqtt.help.title", "Where do I find these?") }),
          el("p", { text: i18nText("setup.mqtt.help.body", "In Home Assistant: Settings → Add-ons → Mosquitto broker. The address is usually tcp:// followed by your Home Assistant IP and :1883. The username and password are a Home Assistant user — Settings → People → Add person, with “Allow login” on.") }),
        ]),
        primary(i18nText("setup.action.save_continue", "Save and continue"), saveMqtt),
      ]),
      el("p", { class: "muted", id: "wiz-err", role: "alert" }),
    ];
    show([card(i18nText("setup.mqtt.title", "Connect to MQTT"),
      i18nText("setup.mqtt.lead", "MQTT is how the panel and Home Assistant talk. Without it, Home Assistant can’t see the panel at all."),
      kids)], "mqtt" + (failure ? ":fail" : ""));
  }

  function saveMqtt() {
    var broker = (typed.mqtt_broker !== undefined ? typed.mqtt_broker : (discovery && discovery.mqtt_broker || "")).trim();
    if (!/^(tcp|mqtt|ssl|mqtts|tls):\/\//i.test(broker)) {
      stepErr(i18nText("setup.mqtt.error.scheme", "The broker URL needs to start with tcp://, mqtt://, ssl://, mqtts:// or tls:// — for example tcp://192.168.1.10:1883."));
      focusField("mqtt_broker");
      return;
    }
    var fields = { mqtt_broker: broker };
    // An untouched prefill (the signed-in account's login name) must still be what gets saved — typed
    // only records edits, and the broker suggestion already follows this same accepted-if-shown rule.
    if (typed.mqtt_user !== undefined) fields.mqtt_user = typed.mqtt_user;
    else if (hdArea && hdArea.ha_username) fields.mqtt_user = hdArea.ha_username;
    if (typed.mqtt_password) fields.mqtt_password = typed.mqtt_password; // blank means keep — never send it
    // The click must answer INSTANTLY — the button itself relabels and visibly disables before any
    // network round-trip, because a quiet second after pressing save is exactly when people press it
    // again (seen on hardware review). lockStep disables it; the label change is what makes it read.
    lockStep(true);
    var saveBtn = stepHost.querySelector("button.wiz-primary");
    if (saveBtn) saveBtn.textContent = i18nText("setup.mqtt.action.checking_address", "Checking the address…");
    setLive(i18nText("setup.mqtt.state.checking_address", "Checking the address from the panel…"));
    // PRE-FLIGHT before committing anything: the panel resolves the host and touches the port in ~2s,
    // so a typo'd hostname gets a named verdict immediately instead of a slow connect workflow (user
    // request after editing the hostname mid-walk). Advisory: a second press saves anyway, and a probe
    // infrastructure failure never blocks the save.
    var probe = (mqttPreflightWaived === broker)
      ? Promise.resolve({ ok: true })
      : fetch("api/v1/config/probe-broker?url=" + encodeURIComponent(broker), { cache: "no-store" })
          .then(function (r) { return r.json(); })
          .catch(function () { return { ok: true }; });
    probe.then(function (p) {
      if (!p.ok && p.error !== undefined) {
        mqttPreflightWaived = broker; // the next press on the SAME address saves anyway
        lockStep(false);
        if (saveBtn) saveBtn.textContent = i18nText("setup.mqtt.action.save_anyway", "Save anyway");
        setLive("");
        stepErr(p.error === "unresolvable"
          ? i18nText("setup.mqtt.error.unresolvable", "“{host}” doesn’t resolve on the panel’s network — check the address. Press again to save anyway.", { host: p.host || brokerHost(broker) })
          : p.error === "invalid-url"
            ? i18nText("setup.mqtt.error.invalid_address", "That address isn’t a broker URL the panel can use — it needs tcp:// (or ssl://) and a host.")
            : i18nText("setup.mqtt.error.nothing_listening", "Nothing is listening at {host}:{port} — check the address and port. Press again to save anyway.", { host: p.host || brokerHost(broker), port: p.port || 1883 }));
        return;
      }
      if (saveBtn) saveBtn.textContent = i18nText("setup.state.saving", "Saving…");
      setLive(i18nText("setup.mqtt.state.saving_details", "Saving these details to the panel…"));
      postForm("api/v1/config", fields)
        .then(function () {
          if (saveBtn) saveBtn.textContent = i18nText("setup.mqtt.action.checking_connection", "Checking the connection…");
          verify = { t0: Date.now(), host: brokerHost(broker) };
          runLadder();
          schedule(1500);
        })
        .catch(function (e) {
          lockStep(false);
          if (saveBtn) saveBtn.textContent = i18nText("setup.action.save_continue", "Save and continue");
          setLive("");
          stepErr(e.message);
        });
    });
  }

  /* The honesty ladder: what "checking" is allowed to claim, and when to stop pretending.
   * The panel keeps retrying in the background either way — only the PRESENTATION gives up. */
  function runLadder() {
    clearInterval(ladderTimer);
    var host = verify.host || i18nText("setup.mqtt.host_fallback", "the broker");
    ladderTimer = setInterval(function () {
      if (!verify) { clearInterval(ladderTimer); return; }
      var t = Date.now() - verify.t0;
      var conn = step("mqtt_connection");
      if (conn.status === "satisfied") {
        clearInterval(ladderTimer);
        setLive(i18nText("setup.mqtt.state.connected", "✓ Connected to {host}.", { host: host }), true);
        setTimeout(function () { verify = null; typed = {}; refresh(); }, 1200);
        return;
      }
      if (conn.status === "blocked" && conn.detail !== "unknown") {
        // An auth rejection must SETTLE before the card blames the credentials. Seen on hardware:
        // correct credentials bounced on the very first connect (a broker mid-reload rejects, and the
        // identical retype then worked), and the wizard's misdiagnosis landed in the most
        // credibility-sensitive minute of the product's life. The panel keeps retrying underneath, so
        // give a first rejection a few seconds to recover; only a rejection that persists is a verdict.
        if (conn.detail === "auth_failed") {
          if (verify.authSeenAt === undefined) { verify.authSeenAt = t; }
          if (t - verify.authSeenAt < 5000) {
            setLive(i18nText("setup.mqtt.state.checking_sign_in", "Checking the sign-in with {host}…", { host: host }));
            return;
          }
        }
        clearInterval(ladderTimer);
        verify = null;
        lockStep(false);
        mqttCard(mqttFailure(conn.detail, host, false));
        return;
      }
      // While the connection is genuinely mid-flight the ladder must not diagnose. On slow hardware a
      // save is retire-old-bridge → rebuild → connect (root-shell fences included), so absolute time
      // alone said "wrong address or port" and then failed the card over a connection that landed
      // seconds later. A verdict needs a settled state, or a 60s ceiling — whichever
      // comes first — and the poll keeps running after any card, so a late success still advances.
      var inFlight = conn.status === "in_flight";
      // A verdict requires a SETTLED state. Even the ceiling may not diagnose while the journey still
      // reads in-flight — a live probe caught the red card sitting over a
      // genuinely connecting bridge. Past the ceiling with the state still moving, the ladder stops
      // pretending to know and keeps the cancel affordance in reach; the poll runs on either way, so a
      // late success still advances the journey.
      // Rungs tightened after the pre-flight + first-configuration fast path landed: a healthy first
      // save now connects in single-digit seconds even on the slowest supported chip, so anything past
      // twenty is genuinely wrong and the ladder may say so much sooner.
      if (t > 20000 && !inFlight) {
        clearInterval(ladderTimer);
        verify = null;
        lockStep(false);
        mqttCard(mqttFailure("unreachable", host, true));
      } else if (t > 30000) {
        setLive(i18nText("setup.mqtt.state.unusually_slow", "Unusually slow — the panel is still trying to reach {host}. You can keep waiting, or cancel and check the details.", { host: host }));
        revealCancel();
      } else if (t > 10000) {
        setLive(inFlight
          ? i18nText("setup.mqtt.state.rebuilding", "Still working — the panel is rebuilding its broker connection.")
          : i18nText("setup.mqtt.state.still_trying", "Still trying to reach {host}… A wrong address or port usually looks like this.", { host: host }));
        revealCancel();
      } else if (t > 6000) {
        revealCancel();
      } else if (t > 3000) {
        setLive(i18nText("setup.mqtt.state.checking_long", "Checking the broker… This usually takes a second or two."));
      } else {
        setLive(i18nText("setup.mqtt.state.checking", "Checking the broker…"));
      }
    }, 1000);
  }

  function mqttFailure(kind, host, presumed) {
    if (kind === "auth_failed") {
      typed.mqtt_password = undefined;
      setTimeout(function () { var f = focusField("mqtt_password"); if (f) f.value = ""; }, 120);
      return failBanner(i18nText("setup.mqtt.failure.auth.title", "The broker is there, but it rejected this username and password."), [
        i18nText("setup.mqtt.failure.auth.address_ok", "Good news: the address is right. The credentials aren’t."),
        i18nText("setup.mqtt.failure.auth.retype", "The password isn’t shown back to you, so it can’t be checked by eye — please type it again. The username is a Home Assistant user account, not the add-on’s name."),
      ], true);
    }
    if (kind === "config_error") {
      setTimeout(function () { focusField("mqtt_broker"); }, 120);
      return failBanner(i18nText("setup.mqtt.failure.config.title", "That address isn’t a broker URL the panel can use."), [
        i18nText("setup.mqtt.failure.config.explanation", "It needs to start with tcp://, mqtt://, ssl://, mqtts:// or tls:// — for example tcp://192.168.1.10:1883."),
      ], true);
    }
    setTimeout(function () { focusField("mqtt_broker"); }, 120);
    return failBanner(
      presumed ? i18nText("setup.mqtt.failure.unreachable.presumed_title", "We can’t reach {host} yet.", { host: host })
        : i18nText("setup.mqtt.failure.unreachable.confirmed_title", "Nothing answered at {host}.", { host: host }),
      [presumed
        ? i18nText("setup.mqtt.failure.unreachable.presumed_body", "The panel is still trying in the background, but this usually means the address or port is wrong. Worth checking: the broker’s IP address, the port (1883 for plain, 8883 for TLS), and that the Mosquitto add-on is running.")
        : i18nText("setup.mqtt.failure.unreachable.confirmed_body", "The panel reached the network but found no MQTT broker there. Worth checking: the broker’s IP address, the port (1883 for plain, 8883 for TLS), and that the Mosquitto add-on is running.")], true);
  }

  /* ---------- step 3: renderer (only when genuinely blocked — Auto resolves this for almost everyone) */

  function rendererCard() {
    var r = step("renderer");
    show([card(i18nText("setup.renderer.title", "Choose what shows on this screen"), null, [
      failBanner(i18nText("setup.renderer.failure.title", "The selected dashboard app isn’t available."), [
        r.detail ? i18nText("setup.renderer.failure.package_missing", "“{package}” is set as the dashboard app but isn’t installed on this panel.", { package: r.detail }) :
          i18nText("setup.renderer.failure.unresolved", "No dashboard app could be resolved for this panel."),
        i18nText("setup.renderer.failure.explanation", "Pick ha-paneld’s built-in renderer (or another installed app) in the Dashboard card of All settings, then return here."),
      ]),
      el("p", {}, [el("a", { class: "pbtn", href: internalHref("configure#cfg-dashboard_package"), text: i18nText("setup.renderer.action.open_dashboard_setting", "Open the Dashboard setting") })]),
    ])], "renderer");
  }

  /* ---------- step 3½: Home Assistant URL ---------- */

  /* Why the handed-over address the panel already tried outranks the mDNS suggestion here: reaching this
   * card at all means it did NOT answer, so the user is correcting a specific wrong address rather than
   * answering an open question. Showing the field blank — or silently swapping in a discovery guess —
   * would hide which address failed and invite them to retype the same one. */
  function handoverFailure() {
    var h = journey && journey.handover;
    return h && h.reason && h.url ? h : null;
  }

  function handoverReasonText(reason) {
    switch (reason) {
      case "unresolvable": return i18nText("setup.ha_url.handover.unresolvable", "This panel could not look up that address on its network.");
      case "unreachable": return i18nText("setup.ha_url.handover.unreachable", "Nothing answered at that address from this panel’s network.");
      case "timeout": return i18nText("setup.ha_url.handover.timeout", "That address did not answer in time.");
      case "tls": return i18nText("setup.ha_url.handover.tls", "That address uses HTTPS with a certificate this panel cannot verify.");
      case "not_home_assistant": return i18nText("setup.ha_url.handover.not_home_assistant", "Something answered at that address, but it was not Home Assistant.");
      default: return i18nText("setup.ha_url.handover.generic", "This panel could not reach Home Assistant at that address.");
    }
  }

  function haUrlCard() {
    var failed = handoverFailure();
    var kids = [];
    if (failed) {
      kids.push(failBanner(
        i18nText("setup.ha_url.handover.title", "Home Assistant’s address didn’t work from this panel"),
        [
          i18nText("setup.ha_url.handover.attempted", "Home Assistant said it is at {url}.", { url: failed.url }),
          handoverReasonText(failed.reason),
          i18nText("setup.ha_url.handover.explanation", "That is usually the address Home Assistant knows for itself rather than one this panel can reach. Enter the address you open Home Assistant at from this network."),
        ]));
    } else {
      kids.push(discoveryNote("ha_url"));
    }
    kids.push(el("fieldset", { id: "wiz-fs", style: "border:0;padding:0;margin:0" }, [
      field("ha_url", i18nText("setup.ha_url.label", "Home Assistant URL"), i18nText("setup.ha_url.help", "The address you open Home Assistant at, e.g. http://homeassistant.local:8123."),
        { input: { inputmode: "url", placeholder: "http://homeassistant.local:8123" },
          value: haUrlSuggestion() }),
      primary(i18nText("setup.action.save_continue", "Save and continue"), saveHaUrl),
    ]));
    kids.push(el("p", { class: "muted", id: "wiz-err", role: "alert" }));
    show([card(i18nText("setup.ha_url.title", "Where is Home Assistant?"),
      i18nText("setup.ha_url.lead", "ha-paneld uses this address to connect to Home Assistant."), kids)], "ha_url");
  }

  function haUrlSuggestion() {
    var failed = handoverFailure();
    if (failed) return failed.url;
    return discovery && discovery.ha_url || "";
  }

  function saveHaUrl() {
    var url = (typed.ha_url !== undefined ? typed.ha_url : haUrlSuggestion()).trim();
    if (!/^https?:\/\//i.test(url)) { stepErr(i18nText("setup.ha_url.error.scheme", "The URL needs to start with http:// or https://.")); focusField("ha_url"); return; }
    var startKey = renderedKey;
    lockStep(true);
    setLive(i18nText("setup.state.saving", "Saving…"));
    postForm("api/v1/config", { ha_url: url })
      .then(function () { typed = {}; refresh(); })
      .catch(function (e) { lockStep(false); if (renderedKey === startKey) { setLive(""); stepErr(e.message); } });
  }

  /* ---------- step 4: sign in ---------- */

  function signinCard(inFlight) {
    var haUrl = step("ha_url").detail;
    // Browser sign-in is recommended: in practice it shows the Home Assistant login and lets you sign in
    // as whichever account you want (confirmed in Safari), so it is both the easiest to type on and gives
    // you control over the panel's account. The "connects as you" case is softened to "may", because it
    // only happens when this browser is already logged in to Home Assistant — not the common case.
    // Browser sign-in returns to the panel's own address, which Panel Assistant's sidebar cannot reach, and the
    // integration never signs a panel in; embedded, only the on-panel route is offered.
    var embedded = document.body.hasAttribute("data-embedded");
    var browserRoute = embedded ? null : el("div", { class: "wiz-route recommended" }, [
      el("b", { text: i18nText("setup.sign_in.browser.title", "Sign in from this browser") }),
      el("span", { class: "wiz-badge", text: i18nText("setup.sign_in.recommended", "recommended") }),
      el("p", { text: i18nText("setup.sign_in.browser.explanation", "You’ll get the Home Assistant login here — sign in as whichever account you want the panel to use. You’ll leave this page and come back automatically.") }),
      el("p", { class: "muted", text: i18nText("setup.sign_in.browser.account_note", "If this browser is already signed in to Home Assistant, the panel may connect as you. To keep the panel on its own account, sign in as that account when the login appears.") }),
      el("button", { class: "wiz-primary", type: "button", text: i18nText("setup.sign_in.browser.action", "Sign in from this browser"), onclick: function (e) {
        e.target.disabled = true;
        postForm("api/v1/ha/oauth/start", {
          ha_url: haUrl,
          ui_locale: requestedLocale(),
          return_surface: "setup",
          preserve_explicit_english: new URLSearchParams(location.search).has("lang") && requestedLocale() === "en" ? "1" : "0",
        })
          .then(function (body) { location.assign(body.authorization_url); })
          .catch(function (err) { e.target.disabled = false; stepErr(err.message); });
      } }),
    ]);
    var panelRoute = el("div", { class: "wiz-route" }, [
      el("b", { text: i18nText("setup.sign_in.panel.title", "Or sign in on the panel") }),
      el("p", { text: i18nText("setup.sign_in.panel.explanation", "The panel shows its own Home Assistant login on its screen. It never reuses another login, so you always get a fresh one there — handy if you want to be sure the panel uses a dedicated account. Sign in on the panel and this page will notice.") }),
    ]);
    show([card(i18nText("setup.sign_in.title", "Sign in to Home Assistant"),
      i18nText("setup.sign_in.lead", "The built-in dashboard needs permission to read your Home Assistant."), [
      browserRoute,
      panelRoute,
      statusLine(inFlight ? i18nText("setup.sign_in.state.in_progress", "A sign-in is in progress… finish it here or on the panel.") :
        i18nText("setup.sign_in.state.waiting", "Choose an option above. This page updates by itself once you’ve signed in.")),
      el("p", { class: "muted", id: "wiz-err", role: "alert" }),
    ])], "signin");
  }

  /* ---------- step 5: proof / attestation / done ---------- */

  /* Deterministic bring-up milestones — nobody trusts a spinner, and the wizard used to go silent
   * after the filter answer while the panel visibly reloaded. Derived from real signals only. */
  function learningMilestones() {
    var ef = journey.entity_filter || {};
    var l = ef.learning || {};
    if (!ef.enabled) return null;
    var steps = [];
    if (l.scanned >= 0) {
      steps.push(i18nText(pluralCategory(l.scanned) === "one" ? "setup.proof.milestone.reading.one" : "setup.proof.milestone.reading.other", "● Reading your entities — {count} so far", { count: localizedNumber(l.scanned) }));
      steps.push(i18nText("setup.proof.milestone.build_pending", "○ Build the filtered set"));
      steps.push(i18nText("setup.proof.milestone.apply_pending", "○ Apply it — the dashboard will reload"));
    } else if (!l.applied) {
      steps.push(l.catalog >= 0 ? i18nText(pluralCategory(l.catalog) === "one" ? "setup.proof.milestone.entities_read_count.one" : "setup.proof.milestone.entities_read_count.other", "✓ Entities read ({count})", { count: localizedNumber(l.catalog) }) : i18nText("setup.proof.milestone.entities_read", "✓ Entities read"));
      steps.push(i18nText("setup.proof.milestone.building", "● Building the filtered set…"));
      steps.push(i18nText("setup.proof.milestone.apply_pending", "○ Apply it — the dashboard will reload"));
    } else {
      steps.push(l.catalog >= 0 ? i18nText(pluralCategory(l.catalog) === "one" ? "setup.proof.milestone.entities_read_count.one" : "setup.proof.milestone.entities_read_count.other", "✓ Entities read ({count})", { count: localizedNumber(l.catalog) }) : i18nText("setup.proof.milestone.entities_read", "✓ Entities read"));
      steps.push(i18nText("setup.proof.milestone.applied", "✓ Filtered set applied"));
      steps.push(i18nText("setup.proof.milestone.loading", "● Loading the dashboard — it may reload once or twice more as the panel fine-tunes"));
    }
    return el("div", { class: "wiz-milestones" }, steps.map(function (s) {
      return el("p", { class: "wiz-milestone", text: s });
    }));
  }

  function proofCard() {
    show([card(i18nText("setup.proof.title", "Almost there"),
      null, [
      statusLine(i18nText("setup.proof.state", "The panel should be setting up its dashboard now. This page will confirm when Home Assistant is rendering.")),
      learningMilestones(),
      el("p", { class: "muted" }, [
        document.createTextNode(i18nText("setup.proof.slow_prompt", "Taking too long?") + " "),
        el("a", { href: internalHref("./"), text: i18nText("setup.proof.dashboard_help", "The Dashboard tab lists anything that’s wrong.") }),
      ]),
    ])], "proof");
  }

  function attestCard() {
    show([card(i18nText("setup.attest.title", "Is your dashboard on the panel now?"),
      i18nText("setup.attest.lead", "The dashboard app manages its own connection, so only you can confirm this."), [
      primary(i18nText("setup.attest.yes", "Yes, it’s there"), function () {
        postForm("api/v1/setup/attest", {}).then(refresh).catch(function (e) { stepErr(e.message); });
      }),
      el("a", { class: "wiz-secondary", style: "display:block;text-align:center;padding:10px", href: internalHref("./"),
        text: i18nText("setup.attest.no", "No — it’s blank or wrong") }),
      el("p", { class: "muted", id: "wiz-err", role: "alert" }),
    ])], "attest");
  }

  /* ---------- the browser-engine blocker, raised before any sign-in ---------- */

  /* The panel's system WebView is below the floor a current Home Assistant frontend needs, so no amount of
   * correct configuration produces a working dashboard. Raised at the renderer step — before the Home
   * Assistant address and sign-in — because the alternative is letting someone complete the whole journey
   * for a panel that was never going to render. */
  function webViewCard() {
    var body = [
      el("p", { class: "wiz-lead", text: i18nText("setup.webview.explanation", "This panel’s browser engine is too old to show a current Home Assistant dashboard. Everything else can be set up perfectly and the dashboard will still come up blank or broken, so this is worth fixing first.") }),
      // Said plainly because it is the wrong turn people take next: both renderers draw the dashboard in the
      // panel's one system WebView, so switching to the Home Assistant app does not avoid this.
      el("p", { class: "wiz-consequence", text: i18nText("setup.webview.companion_does_not_help", "Choosing the Home Assistant app instead will not help — it draws the dashboard using the same engine.") }),
    ];
    body.push(el("p", { class: "wiz-cta" }, [
      el("a", { class: "pbtn", href: internalHref("./"), text: i18nText("setup.action.panel_dashboard", "Panel dashboard") }),
    ]));
    body.push(el("p", { class: "muted", id: "wiz-err", role: "alert" }));
    show([card(i18nText("setup.webview.title", "The panel’s browser engine needs updating"), null, body)], "webview");
  }

  /* ---------- step 4½: which dashboard this panel shows ----------
   * Asked right after sign-in and BEFORE the entity filter, because the filter's learned list is keyed by
   * the dashboard path — answering the filter first would scope it to a dashboard about to change. Home
   * Assistant stores the account's default dashboard server-side (per user) since 2025.12; when that
   * default exists its CONCRETE dashboard row comes preselected (confirming it writes an explicit path,
   * which keeps the panel deterministic), and following the default stays available as Auto.
   *
   * Both choices are NATIVE selects, deliberately: two custom pickers were tried on hardware — an open
   * icon list (swamped the page and buried the Area field) and a portal popup (escaped the card and the
   * viewport) — and the browser's own control beat both. Grouping survives as optgroups; icons did not
   * survive, by design: clean over decorated. */

  var hdCatalog = null;   // {items, default} from the read-only list endpoint; null = not loaded/failed
  var hdArea = null;      // {areas, device, admin, queried, requested} from the ha-area endpoint
  var hdCustom = false;   // "Custom…" is the live control, so the typed path owns the answer
  // Sentinel for the Custom option. A real dashboard root must match ^[a-z0-9][a-z0-9_-]*$, so a
  // leading underscore can never collide with one. Kept identical to configure.js by contract test.
  var CUSTOM_DASHBOARD = "__custom__";
  var DASHBOARD_PATH_PATTERN = "\\s*/?[a-z0-9][a-z0-9_-]*(?:/[^/?#]*)*(?:\\?[^#]*)?(?:#.*)?\\s*";
  function dashboardRootOf(path) {
    var route = String(path || "").trim().split("?")[0].split("#")[0];
    var first = route.split("/").filter(function (s) { return s !== ""; })[0] || "";
    return /^[a-z0-9][a-z0-9_-]*$/.test(first) ? "/" + first : "";
  }
  function wellFormedDashboardPath(path) {
    return new RegExp("^(?:" + DASHBOARD_PATH_PATTERN + ")$").test(String(path || ""));
  }
  var mqttPreflightWaived = null; // broker URL whose failed pre-flight the user chose to override

  function homeDashboardCard() {
    var body = [
      el("p", { class: "wiz-lead", text: i18nText("setup.dashboard.lead", "The dashboard this panel keeps returning to — after a reload, after idle, on boot.") }),
      el("div", { id: "hd-host" }, [statusLine(i18nText("setup.dashboard.state.fetching", "Fetching your dashboards from Home Assistant…"))]),
      el("div", { id: "hd-area" }),
      primary(i18nText("setup.dashboard.action.use", "Use this dashboard"), function (e) { answerHomeDashboard(e.target); }),
      el("p", { class: "muted", id: "wiz-err", role: "alert" }),
    ];
    show([card(i18nText("setup.dashboard.title", "Select the HA dashboard for this panel"), null, body)], "home_dashboard");
    loadHomeDashboardCatalog();
  }

  function loadHomeDashboardCatalog() {
    // Read-only list endpoints — allowed. Neither is the redacted config endpoint, and nothing from them
    // is ever posted back except the values the user explicitly selects.
    fetch("api/v1/config/home-dashboards", { cache: "no-store" })
      .then(function (r) { return r.json(); })
      .then(function (c) { hdCatalog = c; paintHomeDashboards(); })
      .catch(function () { hdCatalog = { queried: false, items: [], default: { explicit: false, path: "" } }; paintHomeDashboards(); });
    fetch("api/v1/config/ha-area", { cache: "no-store" })
      .then(function (r) { return r.json(); })
      .then(function (a) { hdArea = a; paintHomeDashboardArea(); })
      .catch(function () { hdArea = null; paintHomeDashboardArea(); });
  }

  /* The panel's AREA, confirmed on the same page: HA increasingly builds dynamic dashboards from areas,
   * so a wall panel should sit in the right one. A pop-up list of HA's real areas, never free text —
   * nobody knows what to type — and no creation here: picking from what exists is the job, creating
   * areas belongs in Home Assistant. Editing is offered only where it can act (an admin account, or a
   * device with no area yet, whose request seeds first registration); HA's value stays canonical. */
  function paintHomeDashboardArea() {
    var host = document.getElementById("hd-area");
    if (!host || renderedKey !== "home_dashboard") return;
    host.textContent = "";
    if (!hdArea || !hdArea.queried) return; // could not ask HA — the area is a bonus, never a blocker
    var current = hdArea.device && hdArea.device.found ? (hdArea.device.area_name || "") : "";
    var editable = hdArea.admin || !current;
    host.appendChild(el("b", { class: "hd-area-label", text: i18nText("setup.area.label", "Set the HA Area that your panel is in") }));
    if (!editable) {
      // A DISABLED select, not bare text: it must read as "a control you don't have permission for",
      // not as missing UI (hardware-review note). Same control either way; only the permission differs.
      var locked = el("select", {});
      locked.appendChild(el("option", { text: current }));
      locked.disabled = true;
      host.appendChild(locked);
      host.appendChild(el("small", { class: "hd-area-note", text: i18nText("setup.area.locked_note", "This panel is already registered in Home Assistant, so its area can only be changed there by an HA admin user — this panel will follow whatever is set.") }));
      return;
    }
    var sel = el("select", { id: "wiz-ha_area",
      onchange: function (e) { typed.ha_area = e.target.value; } });
    sel.appendChild(el("option", { value: "", text: current ? i18nText("setup.area.none", "No area") : i18nText("setup.area.none_yet", "No area yet — pick one") }));
    var chosenArea = typed.ha_area !== undefined ? typed.ha_area : (current || hdArea.requested || "");
    (hdArea.areas || []).forEach(function (a) {
      if (!a || !a.name) return;
      var o = el("option", { value: a.name, text: a.name });
      if (a.name === chosenArea) o.selected = true;
      sel.appendChild(o);
    });
    host.appendChild(sel);
    host.appendChild(el("small", { class: "hd-area-note", text: hdArea.admin
      ? i18nText("setup.area.admin_help", "Where this panel lives. Home Assistant uses areas to build dashboards automatically.")
      // Non-admin with an unregistered/area-less device: the choice is recorded and applied by the
      // first registration (suggested_area) or by the reconcile pass when an admin next signs in.
      : i18nText("setup.area.non_admin_help", "Where this panel lives. Your choice is saved and applies when the panel registers, or when a Home Assistant admin next signs in.") }));
  }

  function paintHomeDashboards() {
    var host = document.getElementById("hd-host");
    if (!host || renderedKey !== "home_dashboard") return;
    var items = (hdCatalog && hdCatalog.items) || [];
    var def = (hdCatalog && hdCatalog.default) || { explicit: false, path: "" };
    host.textContent = "";
    if (!hdCatalog || !hdCatalog.queried) {
      host.appendChild(el("div", { class: "setup info" }, [
        el("b", { text: i18nText("setup.dashboard.fetch_failed.title", "Couldn’t fetch the dashboard list from Home Assistant yet.") + " " }),
        document.createTextNode(i18nText("setup.dashboard.fetch_failed.explanation", "That can happen right after signing in.") + " "),
        el("a", { href: "#", text: i18nText("setup.dashboard.fetch_failed.retry", "Try again"), onclick: function (ev) {
          ev.preventDefault();
          host.textContent = "";
          host.appendChild(statusLine(i18nText("setup.dashboard.state.fetching", "Fetching your dashboards from Home Assistant…")));
          loadHomeDashboardCatalog();
        } }),
        el("span", { text: " " + i18nText("setup.dashboard.fetch_failed.continue", "Continue with the account’s default below.") }),
      ]));
    } else if (!items.length) {
      host.appendChild(el("div", { class: "setup problem" }, [
        el("b", { text: i18nText("setup.dashboard.none_accessible.title", "This account cannot access any Home Assistant dashboards.") + " " }),
        document.createTextNode(i18nText("setup.dashboard.none_accessible.explanation", "Create a dashboard or grant this account access, then try again.")),
      ]));
    }
    var current = typed.home_dashboard !== undefined ? typed.home_dashboard
      : (journey.home_dashboard && journey.home_dashboard.value || "");
    // Preselection: an already-configured value wins; else the account default's CONCRETE row; else a
    // placeholder that keeps the primary button waiting for a real choice.
    var preselect = current || (def.explicit ? def.path : null);
    // One change handler, attached below once the custom-path controls exist: selecting Custom must
    // never leave the sentinel sitting in the answer, even transiently.
    var sel = el("select", { id: "wiz-home_dashboard" });
    if (preselect === null) {
      var ph = el("option", { value: "", text: i18nText("setup.dashboard.option.choose", "Choose a dashboard…") });
      ph.disabled = true; ph.selected = true;
      sel.appendChild(ph);
    }
    var auto = el("option", { value: "", text: def.explicit
      ? i18nText("setup.dashboard.option.auto_default", "Auto — follow this account’s default")
      : i18nText("setup.dashboard.option.auto_none", "Auto — no default set for this account") });
    if (preselect === "") auto.selected = true;
    sel.appendChild(auto);
    var seen = { "": true };
    ["panel", "dashboard"].forEach(function (group) {
      var members = items.filter(function (d) { return (d.group || "dashboard") === group; });
      if (!members.length) return;
      var og = el("optgroup", { label: group === "panel" ? i18nText("setup.dashboard.group.ha", "Home Assistant dashboards") : i18nText("setup.dashboard.group.user", "Your dashboards") });
      members.forEach(function (d) {
        if (!d.path || seen[d.path]) return;
        seen[d.path] = true;
        var o = el("option", { value: d.path, text: d.title });
        if (d.path === preselect) { o.selected = true; typed.home_dashboard = d.path; }
        og.appendChild(o);
      });
      sel.appendChild(og);
    });
    // Home Assistant's list endpoint returns dashboard ROOTS only, so a specific view below one
    // (/alpha/beta) can never appear as an option — it has to be typed. A configured path
    // that is not in the list opens here rather than as an uneditable row, which is what finally lets
    // it be corrected on the panel itself.
    var customActive = !!preselect && !seen[preselect];
    sel.appendChild(el("option", { value: CUSTOM_DASHBOARD, text: i18nText("setup.dashboard.option.custom", "Custom — enter a dashboard path…") }));
    if (customActive) { hdCustom = true; typed.home_dashboard = preselect; }
    if (hdCustom) sel.value = CUSTOM_DASHBOARD;
    if (!def.explicit && !current) {
      host.appendChild(el("p", { class: "wiz-consequence", text: i18nText("setup.dashboard.no_default", "This account has no default dashboard set — pick the dashboard this panel should show.") }));
    }
    host.appendChild(sel);
    var customValue = hdCustom && typed.home_dashboard !== undefined ? typed.home_dashboard
      : (preselect || "");
    var customInput = el("input", { type: "text", id: "wiz-home_dashboard_custom", class: "hd-custom-input",
      value: customValue, placeholder: "/dashboard-name/tab-name", "aria-label": i18nText("setup.dashboard.path.label", "Dashboard path"),
      "aria-describedby": "wiz-home_dashboard_custom_note" });
    var customNote = el("small", { class: "hd-area-note hd-custom-note",
      id: "wiz-home_dashboard_custom_note", role: "status", "aria-live": "polite" });
    var customWrap = el("div", { class: "hd-custom" }, [customInput, customNote]);
    // The renderer resolves an explicit path against this account's dashboards and quietly falls back
    // to its default when the ROOT is not one of them. Once a path can be typed, that silence is the
    // likeliest way to end up on the wrong dashboard, so name it here. A warning, never a block: the
    // list may have failed to load, and the dashboard may not have been created yet.
    function refreshCustomNote() {
      var root = dashboardRootOf(typed.home_dashboard);
      var unknown = root && hdCatalog && hdCatalog.queried && items.length && !seen[root];
      customNote.textContent = unknown
        ? i18nText("setup.dashboard.path.unknown", "{root} is not a dashboard this account can see — the panel will fall back to its default until that dashboard exists.", { root: root })
        : i18nText("setup.dashboard.path.help", "A path on this Home Assistant, starting with a dashboard from the list.");
      customNote.classList.toggle("warn", !!unknown);
    }
    customInput.addEventListener("input", function () {
      typed.home_dashboard = customInput.value.trim();
      refreshCustomNote();
      paintHomeDashboardChoice();
    });
    customWrap.hidden = !hdCustom;
    host.appendChild(customWrap);
    sel.addEventListener("change", function () {
      hdCustom = sel.value === CUSTOM_DASHBOARD;
      customWrap.hidden = !hdCustom;
      typed.home_dashboard = hdCustom ? customInput.value.trim() : sel.value;
      if (hdCustom) { refreshCustomNote(); customInput.focus(); }
      paintHomeDashboardChoice();
    });
    if (hdCustom) refreshCustomNote();
    paintHomeDashboardChoice();
  }

  function paintHomeDashboardChoice() {
    var b = stepHost.querySelector("button.wiz-primary");
    if (!b) return;
    var def = (hdCatalog && hdCatalog.default) || { explicit: false, path: "" };
    var chosen = typed.home_dashboard !== undefined ? typed.home_dashboard
      : (journey.home_dashboard && journey.home_dashboard.value ||
        (hdCatalog === null ? undefined : (def.explicit ? def.path : undefined)));
    var noDashboards = !!(hdCatalog && hdCatalog.queried && !(hdCatalog.items || []).length);
    // With Custom live the answer is whatever has been typed so far, so the button waits for a path
    // that could actually address a dashboard. An empty box must never submit as "" — that is Auto,
    // and silently following the account default is the opposite of what Custom was chosen for.
    var customIncomplete = hdCustom && !(chosen && wellFormedDashboardPath(chosen));
    b.disabled = chosen === undefined || customIncomplete || (noDashboards && !hdCustom);
    b.textContent = chosen === "" && !hdCustom ? i18nText("setup.dashboard.action.follow_default", "Follow the account’s default") : i18nText("setup.dashboard.action.use", "Use this dashboard");
  }

  /* Save the SETTING first and record the ANSWER second — same load-bearing order as the entity filter:
   * the config POST re-targets the entity catalog to the chosen dashboard, so by the time the filter
   * question is asked its count and learned list are already scoped to the right dashboard. */
  function answerHomeDashboard(button) {
    var def = (hdCatalog && hdCatalog.default) || { explicit: false, path: "" };
    var chosen = typed.home_dashboard !== undefined ? typed.home_dashboard
      : (journey.home_dashboard && journey.home_dashboard.value ||
        (hdCatalog === null ? undefined : (def.explicit ? def.path : undefined)));
    if (chosen === undefined) { stepErr(i18nText("setup.dashboard.error.choose_first", "Pick a dashboard first.")); return; }
    // The button is already disabled for this, so reaching here means the DOM was driven some other
    // way. Refuse rather than post a blank as Auto — the server would accept it, and the panel would
    // quietly follow the account default instead of the view the user came here to set.
    if (hdCustom && !(chosen && wellFormedDashboardPath(chosen))) {
      stepErr(i18nText("setup.dashboard.error.path_required", "Enter a dashboard path, for example /dashboard-name/tab-name."));
      return;
    }
    lockStep(true);
    button.disabled = true;
    button.textContent = i18nText("setup.state.saving", "Saving…");
    var fields = { home_dashboard: chosen };
    // The area rides in the same save when the user touched it; HA remains canonical afterwards.
    if (typed.ha_area !== undefined) fields.ha_area = typed.ha_area;
    postForm("api/v1/config", fields)
      .then(function () { return postForm("api/v1/setup/home-dashboard", {}); })
      .then(function () {
        // Panel Assistant already chose this panel's native path. The filter defaults on
        // for that new panel, before its answer releases the first dashboard render.
        if (!(journey.handover && journey.handover.source && journey.entity_filter &&
          journey.entity_filter.relevant && !journey.entity_filter.answered)) return;
        return postForm("api/v1/config", { dashboard_entity_learning: "true" })
          .then(function () { return postForm("api/v1/setup/entity-filter", {}); });
      })
      .then(function () { typed = {}; refresh(); })
      .catch(function (e) {
        lockStep(false);
        button.disabled = false;
        paintHomeDashboardChoice();
        stepErr(e.message);
      });
  }

  /* ---------- step 5: the entity-filter question, asked BEFORE the first render ---------- */

  /* Copy per verdict level. The count and the panel are facts; these say what they mean, and the
   * confidence tag beside them says whether we measured it or inferred it. */
  var EF_COPY = {
    green: {
      head: i18nText("setup.filter.green.head", "Your dashboard should feel fine either way"),
      measured: i18nText("setup.filter.green.measured", "A panel with this chip handles this many entities without trouble — we have measured it at about this load."),
      estimated: i18nText("setup.filter.green.estimated", "This looks like a comfortable load for this panel, judging from its hardware and your entity count."),
      consequence: i18nText("setup.filter.green.consequence", "Filtering is still worth turning on — it keeps things quick as your setup grows, and you can change it later on the Configure tab."),
    },
    amber: {
      head: i18nText("setup.filter.amber.head", "Filtering is worth turning on"),
      measured: i18nText("setup.filter.amber.measured", "We have measured this chip at about this many entities, and filtering made a clear difference."),
      estimated: i18nText("setup.filter.amber.estimated", "That is a lot of updates for this chip. We have not measured this exact combination, so this is a judgement from the panel’s hardware and your entity count, not a measurement."),
      consequence: i18nText("setup.filter.amber.consequence", "Unfiltered, the panel spends its time keeping up with entities nothing on screen is showing. You can change this later on the Configure tab."),
    },
    red: {
      head: i18nText("setup.filter.red.head", "Turn filtering on — this panel will struggle without it"),
      measured: i18nText("setup.filter.red.measured", "We tested this on the same chip with about this many entities. Unfiltered, the part of the panel that draws your dashboard was busy 81% of the time instead of 6%, and it took roughly 60% longer to respond to a touch."),
      estimated: i18nText("setup.filter.red.estimated", "That is far more updates than this chip is likely to keep up with, judging from its hardware and your entity count."),
      consequence: i18nText("setup.filter.red.consequence", "You can still load it unfiltered if you want to see the difference for yourself. It will work — it will just feel very slow and laggy."),
    },
  };

  /* Tween the displayed count between poll samples. The server reports a real partial total every couple of
   * seconds; easing between samples is what turns that into a number that visibly climbs, which is the whole
   * reason for showing it — it tells the user the wait is doing something. It never displays more than the
   * last reported figure, so nothing here invents progress. */
  var efShown = 0, efTarget = 0, efAnim = null;

  function efReduceMotion() {
    return window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  }

  /* The band the DISPLAYED count falls in. Deliberately computed from what is on screen rather than taken
   * from the server's newest sample: the sample leads the tween, so trusting it turned the card red while
   * the number still read 640 — the colour changing before the number reaches the threshold, which is both
   * incoherent and throws away the one moment worth watching. Crossing the line is the point. */
  function efLevelOf(count) {
    var ef = journey.entity_filter || {};
    if (ef.struggle_above !== null && ef.struggle_above !== undefined && count >= ef.struggle_above) return "red";
    if (ef.recommend_above !== undefined && count >= ef.recommend_above) return "amber";
    return "green";
  }

  function efTween() {
    efAnim = null;
    if (efShown === efTarget) return;
    var delta = efTarget - efShown;
    efShown += Math.abs(delta) < 4 ? delta : Math.round(delta * 0.18);
    var n = document.getElementById("ef-count");
    if (n) n.textContent = localizedNumber(efShown);
    efApplyTone();
    if (efShown !== efTarget) efAnim = setTimeout(efTween, 60);
  }

  /* Tone + headline for the currently displayed count. Split out so the tween can repaint it on every step
   * without re-running the whole card. */
  function efApplyTone() {
    var ef = journey.entity_filter || {};
    var v = document.getElementById("ef-verdict");
    var head = document.getElementById("ef-head");
    if (!v || !head) return;
    var counting = !!ef.counting;
    // Once settled, the server's verdict is authoritative — it is the one bound to the confidence tag.
    var level = counting ? efLevelOf(efShown) : (ef.level || "green");
    v.className = "ef-verdict " + level;
    if (counting) {
      head.textContent = level === "red" ? i18nText("setup.filter.counting.red", "Heading past what this panel can keep up with")
        : level === "amber" ? i18nText("setup.filter.counting.amber", "Filtering is looking worthwhile")
        : i18nText("setup.filter.counting.green", "Counting what Home Assistant would send…");
    } else {
      head.textContent = (EF_COPY[level] || EF_COPY.green).head;
    }
    return level;
  }

  function entityFilterCard() {
    var body = [
      el("p", { class: "wiz-lead", text: i18nText("setup.filter.lead", "Home Assistant will send this panel a live update for every entity it can see. Filtering cuts that down to the ones your dashboard actually uses.") }),
      el("div", { class: "ef-verdict", id: "ef-verdict" }, [
        el("p", { class: "ef-verdict-head" }, [
          el("span", { class: "ef-dot" }),
          el("b", { id: "ef-head" }),
        ]),
        el("div", { class: "ef-facts" }, [
          el("p", { class: "ef-fact" }, [
            el("span", { text: i18nText("setup.filter.fact.entities", "Entities in Home Assistant") }),
            el("b", {}, [
              el("span", { id: "ef-count", text: "0" }),
              el("span", { class: "ef-counting", id: "ef-counting", text: i18nText("setup.filter.state.still_counting", "still counting") }),
            ]),
          ]),
          el("p", { class: "ef-fact" }, [
            el("span", { text: i18nText("setup.filter.fact.panel", "This panel") }),
            el("b", { id: "ef-panel" }),
          ]),
        ]),
        el("p", { class: "ef-basis", id: "ef-basis" }, [
          el("span", { class: "tag", id: "ef-tag" }),
          el("span", { id: "ef-basis-text" }),
        ]),
      ]),
      el("p", { class: "wiz-consequence", id: "ef-consequence" }),
      primary(i18nText("setup.filter.action.enable", "Turn on filtering and load the dashboard"), function (e) {
        answerEntityFilter(true, e.target);
      }),
      el("button", { class: "wiz-secondary", type: "button", id: "ef-decline",
        text: i18nText("setup.filter.action.disable", "Load the dashboard unfiltered"), onclick: function (e) { answerEntityFilter(false, e.target); } }),
      el("details", {}, [
        el("summary", { text: i18nText("setup.filter.details.title", "What filtering actually does") }),
        el("p", { text: i18nText("setup.filter.details.body", "The panel asks Home Assistant for updates on a named list of entities instead of all of them. It builds that list by reading your dashboard, then adds anything it sees the dashboard reach for while running. Nothing is hidden from you — the full list is on the Entities tab.") }),
      ]),
      el("p", { class: "muted", id: "wiz-err", role: "alert" }),
    ];
    show([card(i18nText("setup.filter.title", "One thing before we load your dashboard"), null, body)], "entity_filter");
    efShown = 0;
    paintEntityFilter();
  }

  /* Patch the verdict in place rather than rebuilding the card. A rebuild on every 2s poll would tear down
   * the buttons under the user's cursor and restart the count from zero — the same class of bug as the MQTT
   * card's focus loss, which is why the card key stays constant and only these fields change. */
  function paintEntityFilter() {
    var ef = journey.entity_filter || {};
    var v = document.getElementById("ef-verdict");
    if (!v) return;
    var counting = !!ef.counting;

    document.getElementById("ef-panel").textContent = ef.tier_label || TIER_WORDS[ef.tier] || i18nText("setup.filter.tier.unknown", "This panel");
    document.getElementById("ef-counting").hidden = !counting;

    efTarget = ef.count || 0;
    // Belt to the server's sticky count: a non-zero number on screen never collapses to 0 while a
    // recount is in flight — a settled red flashing 0/green mid-read looked flaky on hardware.
    if (counting && efTarget === 0 && efShown > 0) efTarget = efShown;
    if (efReduceMotion() || efShown > efTarget) {
      // Never animate downwards: a settled total below the last partial would read as losing entities.
      efShown = efTarget;
      document.getElementById("ef-count").textContent = localizedNumber(efShown);
    } else if (!efAnim) {
      efAnim = setTimeout(efTween, 60);
    }

    var level = efApplyTone();
    var copy = EF_COPY[level] || EF_COPY.green;
    var basis = document.getElementById("ef-basis");
    var tag = document.getElementById("ef-tag");
    var text = document.getElementById("ef-basis-text");
    if (counting) {
      // A verdict that moves while counting must read as measurement in progress, never as the panel
      // changing its mind — so it stays provisional and says so until the scan finishes.
      basis.className = "ef-basis counting";
      tag.textContent = i18nText("setup.filter.confidence.counting", "Counting");
      text.textContent = i18nText("setup.filter.confidence.counting_explanation", "Reading your entity list. The recommendation will settle when the count finishes.");
    } else {
      basis.className = "ef-basis " + (ef.confidence === "measured" ? "measured" : "estimated");
      tag.textContent = ef.confidence === "measured" ? i18nText("setup.filter.confidence.measured", "Measured") : i18nText("setup.filter.confidence.estimated", "Estimated");
      text.textContent = ef.confidence === "measured" ? copy.measured : copy.estimated;
    }
    document.getElementById("ef-consequence").textContent = copy.consequence;
  }

  var TIER_WORDS = {
    capable: i18nText("setup.filter.tier.capable", "A capable panel"),
    middling: i18nText("setup.filter.tier.middling", "A mid-range panel"),
    modest: i18nText("setup.filter.tier.modest", "A modest panel"),
    unknown: i18nText("setup.filter.tier.unknown", "This panel"),
  };

  /* Enabling posts the SETTING first and records the ANSWER second, and the order is load-bearing: the
   * answer is what releases the renderer's first load, so reversing it would let the panel start rendering
   * unfiltered — the exact outcome this step exists to prevent. */
  function answerEntityFilter(enable, button) {
    lockStep(true);
    button.disabled = true;
    var other = document.getElementById(enable ? "ef-decline" : "");
    if (other) other.disabled = true;
    button.textContent = enable ? i18nText("setup.filter.action.enabling", "Turning on filtering…") : i18nText("setup.filter.action.loading", "Loading the dashboard…");
    postForm("api/v1/config", { dashboard_entity_learning: enable ? "true" : "false" })
      .then(function () { return postForm("api/v1/setup/entity-filter", {}); })
      .then(function () { typed = {}; refresh(); })
      .catch(function (e) {
        lockStep(false);
        button.disabled = false;
        if (other) other.disabled = false;
        button.textContent = enable ? i18nText("setup.filter.action.enable", "Turn on filtering and load the dashboard") : i18nText("setup.filter.action.disable", "Load the dashboard unfiltered");
        stepErr(e.message);
      });
  }

  /* ---------- step 6: done ---------- */

  function doneCard() {
    var proof = step("render_proof");
    var attested = proof.detail === "user_attested";
    var mqttOk = step("mqtt_connection").status === "satisfied";
    var ef = journey.entity_filter || {};

    var completeStatus = attested
      ? (mqttOk ? i18nText("setup.done.status.attested_mqtt", "MQTT connected · Dashboard confirmed by you.") : i18nText("setup.done.status.attested_no_mqtt", "Dashboard confirmed by you."))
      : (mqttOk ? i18nText("setup.done.status.rendering_mqtt", "MQTT connected · Home Assistant is rendering on the panel.") : i18nText("setup.done.status.rendering_no_mqtt", "Home Assistant is rendering on the panel."));
    var top = [statusLine(completeStatus, true)];
    // Confirm the filter state so answering the question earlier is never a silent no-op.
    if (ef.relevant && ef.enabled) {
      top.push(el("p", { class: "muted", text: i18nText("setup.done.filter_note", "Entity filter is on — it’s learning which entities to show; review the list any time on the Entities tab. The panel’s dashboard may reload a few times in the first minutes while that settles — nothing is wrong.") }));
    }
    top.push(el("p", { class: "wiz-lead", text: i18nText("setup.done.configure_note", "Everything else — brightness, sleep, sounds, behaviour — is on the Configure tab, and the defaults are sensible, so there’s nothing you have to change.") }));
    // The two things a new owner actually wants next, as prominent buttons rather than quiet links: keep
    // configuring, or look at the panel overview. Labels match the nav tabs so they’re recognisable.
    top.push(el("p", { class: "wiz-cta" }, [
      el("a", { class: "pbtn primary", href: internalHref("configure"), text: i18nText("setup.done.action.configure", "Configure the panel") }),
      el("a", { class: "pbtn", href: internalHref("./"), text: i18nText("setup.done.action.dashboard", "Panel dashboard") }),
    ]));
    // The entity filter is NOT offered here any more. It is its own step, asked before the first render,
    // because on a weak panel an unfiltered first load is slow and laggy and that is the first thing a new
    // owner sees — so by the time this screen appears the question has already been answered.
    var cards = [card(i18nText("setup.done.title", "✓ This panel is set up"), null, top)];
    // A tip, not a step, and explicitly optional.
    cards.push(el("p", { class: "wiz-lead" }, [
      document.createTextNode(i18nText("setup.done.taming_intro", "Optional: panels often ship with vendor apps that pop up over the dashboard. ha-paneld can quieten them.") + " "),
      el("a", { href: internalHref("install"), text: i18nText("setup.done.taming_link", "Package taming is on the Install tab.") }),
    ]));
    show(cards, "done");
    var escape = document.querySelector(".wiz-escape");
    if (escape) escape.hidden = true; // the done card carries its own navigation
    clearTimeout(pollTimer);
  }

  /* ---------- small helpers ---------- */

  function lockStep(locked) {
    var fs = document.getElementById("wiz-fs");
    if (fs) fs.disabled = locked;
    var b = stepHost.querySelector("button.wiz-primary");
    if (b) b.disabled = locked;
  }

  function revealCancel() {
    if (document.getElementById("wiz-cancel")) return;
    var b = el("button", { id: "wiz-cancel", class: "wiz-secondary", type: "button",
      text: i18nText("setup.mqtt.action.cancel_edit", "Cancel and edit these details"), onclick: function () {
        clearInterval(ladderTimer);
        verify = null;
        // Unlock and FORCE the rebuild: the card key hasn't changed, so a plain refresh() short-circuits
        // in render() and leaves the locked card exactly as it was — a cancel that visibly did nothing
        // (hardware review). The user asked to edit; give them editable fields immediately.
        lockStep(false);
        renderedKey = "";
        render();
        refresh();
      } });
    // OUTSIDE the disabled fieldset, always. HTML disables every descendant of a disabled fieldset,
    // and the one control that exists to ESCAPE the locked state was born dead inside it — seen twice
    // on hardware ("the cancel button is disabled so has no use"). Never-trap means never.
    var live = document.getElementById("wiz-live");
    var fs = document.getElementById("wiz-fs");
    if (fs && live && fs.contains(live) && fs.parentNode) fs.parentNode.insertBefore(b, fs.nextSibling);
    else if (live && live.parentNode) live.parentNode.insertBefore(b, live.nextSibling);
  }

  function setLive(text, ok) {
    var live = document.getElementById("wiz-live");
    if (!live) {
      live = statusLine(text, ok);
      var btn = stepHost.querySelector("button.wiz-primary");
      if (btn && btn.parentNode) btn.parentNode.insertBefore(live, btn.nextSibling);
      else stepHost.appendChild(live);
      return;
    }
    if (live.textContent !== text) live.textContent = text; // announce transitions only, not poll ticks
    live.classList.toggle("ok", !!ok);
  }

  function stepErr(msg) {
    var e = document.getElementById("wiz-err");
    if (e) e.textContent = msg || "";
  }

  function focusField(key) {
    var f = document.getElementById("wiz-" + key);
    if (f) f.focus();
    return f;
  }

  /* ---------- the render decision ---------- */

  /* The ONE card the current journey should show, as a stable key. render() rebuilds only when this
   * changes, which is what stops a 2s in-flight poll from tearing down a field the user is typing in.
   * The MQTT card is "mqtt" whether the connection is discovering, connecting or announcing — those are
   * all one card with live inputs — and becomes "mqtt:fail" only when the connection is actually blocked.
   * The earlier guard compared the STAGE name ("mqtt_broker") against the card's rendered key ("mqtt")
   * and never matched, so it rebuilt every poll and stole focus to the title. */
  function desiredKey() {
    if (journey.complete) return "done";
    switch (journey.next) {
      case "identity": return "identity";
      case "mqtt_broker": case "mqtt_credentials": case "mqtt_connection":
        return step("mqtt_connection").status === "blocked" ? "mqtt:fail" : "mqtt";
      case "renderer":
        // A too-old engine is a renderer that cannot work, not a renderer that is unchosen.
        return step("renderer").detail.indexOf("webview_too_old") === 0 ? "webview" : "renderer";
      case "ha_url": return "ha_url";
      case "ha_credentials": return "signin";
      case "home_dashboard": return "home_dashboard";
      // One key for the whole question: the count climbing and the verdict moving are patched in place, so
      // the card must NOT be keyed on the level or every band crossing would rebuild it mid-read.
      case "entity_filter": return "entity_filter";
      case "render_proof": return step("render_proof").status === "blocked" ? "attest" : "proof";
      default: return "done";
    }
  }

  function buildCard(key) {
    if (key.indexOf("done") === 0) return doneCard();
    switch (key) {
      case "identity": return identityCard();
      case "entity_filter": return entityFilterCard();
      case "mqtt": return mqttCard(null);
      case "mqtt:fail":
        // A rejected login routes `next` to CREDENTIALS while the detail lives on CONNECTION.
        return mqttCard(mqttFailure(step("mqtt_connection").detail, brokerHost(typed.mqtt_broker || "") || i18nText("setup.mqtt.host_fallback", "the broker"), false));
      case "webview": return webViewCard();
      case "renderer": return rendererCard();
      case "ha_url": return haUrlCard();
      case "signin": return signinCard(step("ha_credentials").status === "in_flight");
      case "home_dashboard": return homeDashboardCard();
      case "attest": return attestCard();
      case "proof": return proofCard();
      default: return doneCard();
    }
  }

  function render() {
    if (!journey) return;
    renderDots();
    // During verification the ladder owns the DOM and the status line; a poll must never rebuild under it.
    if (verify) return;
    // A revisit ends by itself the moment a re-save moves the real journey forward — the user acted, so
    // show them the consequence rather than the old card.
    if (forcedRevisitKey() && lastRealNext !== undefined && journey.next !== lastRealNext) clearRevisit();
    lastRealNext = journey.next;
    var forced = forcedRevisitKey();
    var key = forced || desiredKey();
    if (key === renderedKey) {
      // Same card, but the entity-filter step has live contents: the count and the verdict are patched in
      // place so the number keeps climbing without the card being torn down under the user.
      if (key === "entity_filter") paintEntityFilter();
      if (key === "proof") {
        var stale = document.querySelector(".wiz-milestones");
        var fresh = learningMilestones();
        if (stale && fresh) stale.replaceWith(fresh);
      }
      return; // otherwise nothing material changed — leave the user's fields untouched
    }
    buildCard(key);
    if (forced) {
      // Revisits must never read as the journey moving backwards: say what this is, offer the way on.
      stepHost.insertBefore(el("div", { class: "setup info" }, [
        el("b", { text: i18nText("setup.revisit.notice", "You’re revisiting an earlier step.") + " " }),
        el("a", { href: "#", text: i18nText("setup.revisit.continue", "Continue setup →"), onclick: function (ev) {
          ev.preventDefault();
          clearRevisit();
          renderedKey = "";
          render();
        } }),
      ]), stepHost.firstChild);
    }
  }

  window.addEventListener("hashchange", function () { renderedKey = ""; render(); });

  /* ---------- polling: 2s while something is moving, 10s otherwise, never when hidden ---------- */

  function refresh() {
    return getJourney().then(function (j) {
      journey = j;
      render();
      schedule();
    }).catch(function () { schedule(5000); });
  }

  function schedule(overrideMs) {
    clearTimeout(pollTimer);
    if (journey && journey.complete && !verify) return;
    // A scan in flight is busy even though no STEP is in flight: the count is what is moving, and polling it
    // every ten seconds would make the number lurch instead of climb.
    var counting = journey && journey.entity_filter && journey.entity_filter.counting;
    var busy = verify || counting ||
      ["signin", "proof"].some(function (k) { return renderedKey.indexOf(k) === 0; }) ||
      (journey && journey.steps.some(function (s) { return s.status === "in_flight"; }));
    pollTimer = setTimeout(function () {
      if (document.hidden) { schedule(2000); return; }
      refresh();
    }, overrideMs || (busy ? 2000 : 10000));
  }

  /* A sign-in finishing in another tab announces itself; the poll is the fallback (the panel's own
   * WebView cannot broadcast to this browser, so for the panel route the poll IS the signal). */
  if (typeof BroadcastChannel !== "undefined") {
    try { new BroadcastChannel("ha-paneld-ha-oauth").onmessage = function () { refresh(); }; } catch (e) { /* older WebView */ }
  }

  /* One-shot discovery suggestions for blank fields — read-only, never persisted by the server. */
  fetch("api/v1/config/discovery", { cache: "no-store" })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      discovery = d;
      var editing = document.activeElement && document.activeElement.tagName === "INPUT" && stepHost.contains(document.activeElement);
      if (!editing && (renderedKey === "mqtt" || renderedKey === "ha_url")) { renderedKey = ""; render(); }
    })
    .catch(function () { discovery = null; });

  refresh();
})();
