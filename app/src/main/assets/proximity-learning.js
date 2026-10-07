// The browser launches and monitors setup; all physical instructions and Save live on the panel.
(function () {
  "use strict";
  if (!document.getElementById("proximity-learning-mount")) return;
  var cardRoot = document.getElementById("cfg-groups");
  if (!cardRoot) return;
  var timer = null, active = false, busy = false, cardSizeInvalid = false;
  var ownedSession = null, currentSession = null, heartbeatTimer = null;

  var t = window.HaI18n.t;
  function label(key, fallback, values) { return t("configure.proximity.setup." + key, fallback, values); }
  function node(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }
  var card = node("section", "card prox-learning");
  card.id = "cfg-proximity-learning";
  card.setAttribute("data-layout-key", "configure-presence-wake");
  var heading = node("h2", "", t("configure.proximity.title", "Presence & wake"));
  var state = node("p", "prox-learning-state", label("waiting", "Waiting for sensor status…"));
  var detail = node("p", "note", label("local", "Optional setup runs on the panel. Touch-to-wake remains available."));
  var evidence = node("p", "muted prox-learning-evidence", "");
  var actions = node("div", "prox-learning-actions");
  var start = node("button", "pbtn", label("start", "Set up proximity on panel"));
  var cancel = node("button", "pbtn", label("cancel", "Cancel setup"));
  var reset = node("button", "pbtn", label("reset", "Restore profile defaults"));
  [start, cancel, reset].forEach(function (button) { button.type = "button"; actions.appendChild(button); });
  start.disabled = reset.disabled = true;
  cancel.hidden = true;
  var result = node("p", "note", "");
  result.setAttribute("role", "status"); result.setAttribute("aria-live", "polite"); result.setAttribute("aria-atomic", "true");
  [heading, state, detail, evidence, actions, result].forEach(function (child) { card.appendChild(child); });
  function placeCard() {
    var after = cardRoot.querySelector('[data-config-group="Auto-sleep"]') ||
      cardRoot.querySelector('[data-config-group="Behaviour"]');
    if (after) cardRoot.insertBefore(card, after.nextSibling);
    else cardRoot.insertBefore(card, cardRoot.querySelector('[data-config-group="Logging"]'));
  }
  window.repositionProximityLearningCard = placeCard;
  placeCard();

  function stopHeartbeat() {
    clearTimeout(heartbeatTimer); heartbeatTimer = null; ownedSession = null;
  }
  function render(d) {
    var phase = d.phase || d.learning || "calibration_required";
    active = d.sessionActive === true || !!(d.session && d.session.active) || phase === "calibrating";
    currentSession = d.sessionId || (d.session && d.session.id) || null;
    var stage = d.stage || (d.session && d.session.stage);
    if (ownedSession) {
      if (stage === "saved" || stage === "cancelled" || (currentSession && currentSession !== ownedSession)) stopHeartbeat();
      else if (!active) { clearTimeout(heartbeatTimer); heartbeatTimer = null; }
      else if (!heartbeatTimer && currentSession === ownedSession) scheduleHeartbeat();
    }
    var available = d.present !== false && phase !== "source_unavailable";
    var phases = {
      ready: ["ready", "Proximity is ready"],
      calibration_required: ["required", "Proximity setup is available on the panel"],
      calibrating: ["calibrating", "Setup is running on the panel"],
      source_unavailable: ["unavailable", "Proximity source is unavailable"]
    };
    var status = phases[available ? phase : "source_unavailable"] || phases.calibration_required;
    state.textContent = label(status[0], status[1]);
    if (available && phase === "ready" && typeof d.presenceSupported === "boolean" && typeof d.waveSupported === "boolean") {
      state.textContent = d.presenceSupported
        ? (d.waveSupported ? label("ready_both", "Presence and wave are ready") : label("ready_presence", "Presence is ready; wave is unavailable"))
        : (d.waveSupported ? label("ready_wave", "Wave is ready; presence is unavailable") : label("ready_neither", "Presence and wave are unavailable"));
    }
    var mode = d.signalMode || d.mode;
    detail.textContent = active
      ? label("follow", "Follow the instructions on the panel. Keep the browser tab open until setup finishes; you can leave it in the background.")
      : label("local", "Optional setup runs on the panel. Touch-to-wake remains available.");
    if (mode === "binary") detail.textContent += " " + label("binary", "This sensor reports near or clear only. Its detection distance cannot be adjusted.");
    if (!available) detail.textContent = label("no_source", "Wake-to-wave is unavailable. Touch-to-wake remains available.");
    var stages = {
      intro: ["intro", "Ready to begin on the panel"], clear: ["clear", "Measuring the clear observation"],
      near: ["near", "Measuring the near observation"], return_clear: ["return_clear", "Checking the return to clear"],
      wave_baseline: ["wave_baseline", "Checking the starting position for hand waves"],
      wave_capture: ["wave_capture", "Measuring a deliberate hand wave"],
      waves: ["waves", "Validating waves: {seen}/{required}"], review: ["review", "Ready for review and Save on the panel"],
      saved: ["saved", "Calibration saved"], cancelled: ["cancelled", "Setup cancelled. Existing calibration is unchanged."],
      timed_out: ["timed_out", "Setup timed out. Existing calibration is unchanged."], failed: ["failed", "Setup could not complete. Existing calibration is unchanged."]
    };
    var progress = stages[stage];
    evidence.textContent = active && stage === "intro" && d.health === "source_unavailable"
      ? label("waiting", "Waiting for sensor status…")
      : progress ? label("stage." + progress[0], progress[1], {
      seen: d.acceptedGestures == null ? 0 : d.acceptedGestures,
      required: d.requiredGestures == null ? 3 : d.requiredGestures
    }) : "";
    start.hidden = active;
    start.disabled = busy || d.present === false || (!available && d.canCalibrate !== true) || d.canCalibrate === false || (d.canCalibrate == null && d.canTeach === false);
    cancel.hidden = !active;
    cancel.disabled = busy || !currentSession;
    reset.disabled = busy || active || !available || d.profileDefaultAvailable !== true;
  }
  function changed() {
    if (window.configCardSizeSourceReady) window.configCardSizeSourceReady("proximity");
    if (cardSizeInvalid && window.configCardSizeGeometryChanged) window.configCardSizeGeometryChanged();
    cardSizeInvalid = false;
  }
  async function refresh() {
    clearTimeout(timer);
    try {
      var response = await fetch("api/v1/proximity", { cache: "no-store", mode: "same-origin" });
      if (!response.ok) throw new Error("status " + response.status);
      render(await response.json()); changed();
    } catch (_) {
      state.textContent = label("status_unavailable", "Proximity status is unavailable");
      start.disabled = reset.disabled = true;
      cardSizeInvalid = true;
      if (window.configCardSizeGeometryInvalid) window.configCardSizeGeometryInvalid();
    }
    clearTimeout(timer); timer = setTimeout(refresh, active ? 1000 : 5000);
  }
  async function request(action, sessionId) {
    var body = { action: action };
    if (sessionId) body.sessionId = sessionId;
    var controller = action === "heartbeat" ? new AbortController() : null;
    var timeout = controller ? setTimeout(function () { controller.abort(); }, 5000) : null;
    try {
      var response = await fetch("api/v1/proximity/calibration", {
        method: "POST", mode: "same-origin", cache: "no-store",
        signal: controller ? controller.signal : undefined,
        headers: { "Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json", "X-Proximity-UI": "1" },
        body: new URLSearchParams(body).toString()
      });
      var text = await response.text();
      if (!response.ok) {
        var reason = text.trim();
        try { reason = JSON.parse(text).error || ""; } catch (_) {}
        var error = new Error(reason || label("rejected", "The panel did not accept that action."));
        error.opaque = !!reason;
        throw error;
      }
      return JSON.parse(text);
    } finally { clearTimeout(timeout); }
  }
  function scheduleHeartbeat() {
    clearTimeout(heartbeatTimer);
    if (!ownedSession || !active) return;
    heartbeatTimer = setTimeout(async function () {
      var id = ownedSession;
      try {
        var data = await request("heartbeat", id);
        if (ownedSession !== id) return;
        render(data);
      } catch (_) {
        // Do not acquire ownership from status polling or restart an expired session.
      }
      heartbeatTimer = null; scheduleHeartbeat();
    }, 5000);
  }
  async function post(action) {
    if (busy) return;
    busy = true;
    start.disabled = cancel.disabled = reset.disabled = true;
    result.removeAttribute("lang");
    result.textContent = label("working", "Working…");
    try {
      var data = await request(action, active ? currentSession : null);
      if (action === "start" && data.sessionId && (data.phase === "calibrating" || data.sessionActive === true)) {
        ownedSession = data.sessionId;
      }
      busy = false; render(data); changed();
      result.textContent = "";
    } catch (error) {
      if (error.opaque) result.setAttribute("lang", "en");
      result.textContent = error.message || label("rejected", "The panel did not accept that action.");
    } finally { busy = false; refresh(); }
  }
  start.addEventListener("click", function () { if (!active) post("start"); });
  cancel.addEventListener("click", function () { if (active && currentSession) post("cancel"); });
  reset.addEventListener("click", function () {
    if (active || !window.confirm(label("confirm_reset", "Replace the saved calibration with this sensor profile’s defaults?"))) return;
    post("reset");
  });
  document.addEventListener("visibilitychange", function () { if (!document.hidden) refresh(); });
  refresh();
})();
