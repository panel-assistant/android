// The Voice card's microphone tuning: the owner's diagnostics (a live level meter, a wake-word test that reads the
// listener's own score against its cutoff, and the last command the panel heard) and a wizard that measures the
// room and suggests voice_mic_gain_db and voice_sensitivity. Every instruction lives here, in the browser; the
// panel only meters and scores. Save writes the existing settings, and Cancel puts back what they were.
(function (cfg) {
  "use strict";
  if (!cfg || !window.PaneldTransport) return;

  // English text, the fallback for every configure.voice.tuning.* key.
  var S = {
    title: "Microphone tuning",
    start: "Tune the microphone",
    level_show: "Show the microphone level",
    level_stop: "Stop the level",
    level_value: "Level {rms} dBFS, peak {peak} dBFS, room {floor} dBFS",
    level_clipped: "Clipped {count} times: the microphone is overloaded.",
    level_idle: "The level shows while it is measured; measuring keeps the microphone open beside the wake word.",
    wake_test: "Test the wake word",
    wake_stop: "Stop the test",
    wake_running: "Say the wake word. Best score {peak}, needs {cutoff}; heard {fired} times; {seconds} s left.",
    wake_waiting: "Say the wake word. Waiting for a score; {seconds} s left.",
    wake_done: "Best score {peak}, needed {cutoff}; heard {fired} times.",
    wake_note: "While the test runs, the panel counts the wake word instead of answering it.",
    last_title: "Last command",
    last_none: "No command heard since voice was turned on.",
    last_heard: "Assist heard: {text}",
    last_heard_nothing: "Assist heard nothing it could transcribe.",
    last_reply: "Replied: {text}",
    last_woke: "Woke at {score}.",
    result_action_done: "Result: done.",
    result_query_answer: "Result: answered.",
    result_replied: "Result: replied.",
    result_no_reply: "Result: no reply.",
    result_error: "Result: failed ({code}).",
    result_no_intent_match: "Result: not understood.",
    step_quiet: "Step 1 of 4: the room",
    step_quiet_text: "Stay quiet for a few seconds so the panel can hear the room.",
    step_speak: "Step 2 of 4: your voice",
    step_speak_text: "From where you usually stand, speak normally for a few seconds, as if giving a command.",
    step_wake: "Step 3 of 4: the wake word",
    step_wake_text: "Go to the far side of the room and say {wake_word} three times, pausing between each.",
    step_wake_progress: "Heard {fired} of 3. Best score {peak}, needs {cutoff}.",
    step_source: "Step 4 of 4: compare settings (optional)",
    step_source_text: "Try an audio source or capture setting, then say the wake word three times again. The others go back as they were.",
    source_try: "Try this setting",
    source_result: "{source}: heard {fired} of 3, best score {peak}",
    source_not_opened: "{source}: the panel could not use this setting, so it was not compared",
    review: "Suggested settings",
    review_gain: "Microphone gain: {value} dB",
    review_sensitivity: "Wake word sensitivity: {value}",
    review_source: "Heard best with {value}",
    review_quiet_gain: "Your voice reached {peak} dBFS; the gain lifts the command towards -20 dBFS.",
    review_loud_gain: "Your voice clipped or came close; the gain is lowered.",
    review_good_gain: "Your voice reached {peak} dBFS, which needs no change.",
    review_high: "The wake word came close to its cutoff without firing every time, so the panel can wake more readily.",
    review_low: "The panel woke while the room was quiet, so it asks for a clearer match.",
    review_far: "The wake word stayed well short of its cutoff. Move the panel or try another audio source; gain does not reach the wake word.",
    review_ok: "The wake word fired reliably.",
    measuring: "Measuring… {seconds} s",
    next: "Next",
    skip: "Skip",
    save: "Save",
    cancel: "Cancel",
    saved: "Saved.",
    cancelled: "Cancelled. The settings are as they were.",
    refused: "The panel did not accept that: {reason}"
  };
  function L(key, values) { return cfg.i18nText("configure.voice.tuning." + key, S[key], values); }

  // The settings the optional comparison step can try, one value at a time against where the wizard started:
  // the audio source and, where the panel offers them, the capture route's rate, channels and channel mix.
  var COMPARED = ["voice_audio_source", "voice_capture_rate", "voice_capture_channels", "voice_capture_downmix"];
  var QUIET_MS = 3000, SPEAK_MS = 5000, HEARTBEAT_MS = 5000, SOURCE_SETTLE_MS = 1500;
  var session = "tab-" + Math.random().toString(36).slice(2, 12) + Date.now().toString(36);
  var latest = null, watch = null, heartbeat = null;
  var metering = false, testing = false;
  // The wizard: null when idle, else its step and what it has measured.
  var wizard = null;

  function el(tag, attrs, kids) { return cfg.el(tag, attrs, kids); }
  function button(id, text, onClick) {
    var b = el("button", { id: id, class: "pbtn", type: "button", text: text });
    b.addEventListener("click", onClick);
    return b;
  }
  function fixed(n, places) { return n == null || !isFinite(n) ? "–" : Number(n).toFixed(places); }
  function db(n) { return n == null || !isFinite(n) ? "–" : String(Math.round(n)); }

  var root = el("div", { id: "voice-tuning", class: "voice-tuning" });
  var meter = el("meter", { id: "voice-level", min: "-70", max: "0", low: "-45", high: "-6", optimum: "-20", value: "-70", style: "width:100%" });
  var levelText = el("p", { id: "voice-level-text", class: "note", text: L("level_idle") });
  var levelToggle = button("voice-level-toggle", L("level_show"), function () { metering ? stopAll() : act("level"); });
  var wakeText = el("p", { id: "voice-wake-test-text", class: "note", text: L("wake_note") });
  var wakeToggle = button("voice-wake-test", L("wake_test"), function () { testing ? stopAll() : act("wake_test"); });
  var lastHeard = el("p", { id: "voice-last-heard", class: "note" });
  var lastReply = el("p", { id: "voice-last-reply", class: "note" });
  var lastResult = el("p", { id: "voice-last-result", class: "note" });
  var startButton = button("voice-tuning-start", L("start"), function () { begin(); });
  var status = el("p", { id: "voice-tuning-status", class: "note", role: "status", "aria-live": "polite" });

  var wizardBox = el("div", { id: "voice-tuning-wizard", hidden: "" });
  var stepTitle = el("p", { id: "voice-tuning-step", class: "prox-learning-state" });
  var stepText = el("p", { id: "voice-tuning-instruction", class: "note" });
  var stepProgress = el("p", { id: "voice-tuning-progress", class: "muted prox-learning-evidence" });
  var sourcePick = el("select", { id: "voice-tuning-source" });
  var sourceTry = button("voice-tuning-try", L("source_try"), function () { trySource(sourcePick.value); });
  var sourceRow = el("div", { class: "prox-learning-actions", hidden: "" }, [sourcePick, sourceTry]);
  var sourceResults = el("div", { id: "voice-tuning-source-results" });
  var nextButton = button("voice-tuning-next", L("next"), function () { advance(); });
  var skipButton = button("voice-tuning-skip", L("skip"), function () { review(); });
  var saveButton = button("voice-tuning-save", L("save"), function () { save(); });
  var cancelButton = button("voice-tuning-cancel", L("cancel"), function () { cancel(); });
  saveButton.className = "pbtn primary";
  [stepTitle, stepText, stepProgress, sourceRow, sourceResults,
    el("div", { class: "prox-learning-actions" }, [nextButton, skipButton, saveButton, cancelButton])].forEach(function (n) { wizardBox.appendChild(n); });

  [el("p", { class: "prox-learning-state", text: L("title") }),
    el("div", { class: "prox-learning-actions" }, [startButton, levelToggle, wakeToggle]),
    meter, levelText, wakeText, wizardBox, status,
    el("p", { class: "prox-learning-state", text: L("last_title") }),
    lastHeard, lastReply, lastResult].forEach(function (n) { root.appendChild(n); });

  async function post(action) {
    var reply = await window.PaneldTransport.request("voice.diagnostics.set", { action: action, sessionId: session });
    if (reply.outcome !== "applied") {
      var body = reply.result, reason = body && typeof body === "object" ? body.error : (typeof body === "string" ? body : "");
      var error = new Error(L("refused", { reason: reason || reply.code || reply.outcome }));
      error.reason = reason;
      throw error;
    }
    // The reply leaves the last command out (only the GET family carries it), so keep the one already shown.
    if (reply.result && typeof reply.result === "object") show(Object.assign({}, latest, reply.result));
    return reply.result;
  }

  async function act(action) {
    status.textContent = "";
    try {
      await post(action);
      if (action === "level") metering = true;
      if (action === "wake_test") testing = true;
      keepAlive();
      paint();
      return true;
    } catch (error) {
      status.textContent = error.message;
      return false;
    }
  }

  // One heartbeat at a time, each sent once the last has answered, while this tab holds the meter or the test.
  function keepAlive() {
    if (heartbeat || !(metering || testing)) return;
    heartbeat = setTimeout(function () {
      heartbeat = null;
      if (!(metering || testing)) return;
      post("heartbeat").then(keepAlive, function () { metering = testing = false; paint(); });
    }, HEARTBEAT_MS);
  }

  async function stopAll() {
    metering = testing = false;
    clearTimeout(heartbeat); heartbeat = null;
    try { await post("stop"); } catch (_) { /* ended already */ }
    paint();
  }

  // ---- What the panel reports, every 500 ms while the card is shown.
  function show(d) {
    latest = d;
    var mine = d.session === session;
    // The panel reports a level while it holds the meter for this session, silence included.
    if (metering && !(mine && d.level)) metering = false;
    if (testing && !(mine && d.wake_test && d.wake_test.state === "running")) testing = false;
    if (wizard && mine && d.level) wizard.samples.push(d.level);
    paint();
    if (wizard) progress();
  }

  function paint() {
    var d = latest || {};
    var level = d.session === session ? d.level : null;
    levelToggle.textContent = metering ? L("level_stop") : L("level_show");
    wakeToggle.textContent = testing ? L("wake_stop") : L("wake_test");
    levelToggle.hidden = wakeToggle.hidden = startButton.hidden = !!wizard;
    if (level) {
      meter.value = Math.max(-70, Math.min(0, level.rms_dbfs));
      levelText.textContent = L("level_value", { rms: db(level.rms_dbfs), peak: db(level.peak_dbfs), floor: db(level.floor_dbfs) }) +
        (level.clipped > 0 ? " " + L("level_clipped", { count: level.clipped }) : "");
    } else {
      meter.value = -70;
      levelText.textContent = L("level_idle");
    }
    var test = d.wake_test || { state: "idle" };
    if (test.state === "running" && d.session === session) {
      var seconds = Math.ceil((test.remaining_ms || 0) / 1000);
      wakeText.textContent = test.peak == null ? L("wake_waiting", { seconds: seconds })
        : L("wake_running", { peak: fixed(test.peak, 2), cutoff: fixed(test.cutoff, 2), fired: test.fired, seconds: seconds });
    } else if (test.state === "done" && test.peak != null) {
      wakeText.textContent = L("wake_done", { peak: fixed(test.peak, 2), cutoff: fixed(test.cutoff, 2), fired: test.fired });
    } else {
      wakeText.textContent = L("wake_note");
    }
    var last = d.last_command;
    if (!last) {
      lastHeard.textContent = L("last_none");
      lastReply.textContent = lastResult.textContent = "";
    } else {
      lastHeard.textContent = (last.heard ? L("last_heard", { text: "“" + last.heard + "”" }) : L("last_heard_nothing")) +
        (last.wake_score != null ? " " + L("last_woke", { score: fixed(last.wake_score, 2) }) : "");
      lastReply.textContent = last.reply ? L("last_reply", { text: last.reply }) : "";
      lastResult.textContent = resultText(last);
    }
  }

  function resultText(last) {
    if (last.code === "no_intent_match") return L("result_no_intent_match");
    if (last.outcome === "error") return L("result_error", { code: last.code || "error" });
    var known = { action_done: 1, query_answer: 1, replied: 1, no_reply: 1 };
    return known[last.outcome] ? L("result_" + last.outcome) : L("result_replied");
  }

  // ---- The wizard.
  function setting(key) { return cfg.values[key] == null ? "" : String(cfg.values[key]); }
  function field(key) { return cfg.schema.filter(function (f) { return f.key === key; })[0]; }
  function optionLabel(key, value) {
    var f = field(key);
    if (!f || !f.options) return value;
    var index = f.options.indexOf(value);
    return index < 0 ? value : cfg.localizedEnumOption(f, value, index);
  }
  function wakeWordName() {
    var words = [];
    try { words = JSON.parse(setting("voice_wake_words") || "[]"); } catch (_) { words = []; }
    var id = (latest && latest.wake_test && latest.wake_test.model) || words[0] || "";
    var catalog = Array.isArray(cfg.voiceWakeWordsCatalog) ? cfg.voiceWakeWordsCatalog : [];
    var entry = catalog.filter(function (w) { return w.id === id; })[0];
    return "“" + (entry && entry.wake_word ? entry.wake_word : id.replace(/_/g, " ")) + "”";
  }

  async function begin() {
    if (wizard) return;
    wizard = {
      step: "quiet", samples: [], start: {
        voice_mic_gain_db: setting("voice_mic_gain_db") || "0",
        voice_sensitivity: setting("voice_sensitivity") || "normal",
      }, sources: [], falseWakes: 0
    };
    COMPARED.forEach(function (key) { if (field(key)) wizard.start[key] = setting(key); });
    status.textContent = "";
    wizardBox.hidden = false;
    if (!(await act("level"))) { await abandon(); return; }
    // The quiet step listens for the wake word too, where one is armed: a wake heard in a quiet room is a false one.
    wizard.wakeArmed = await act("wake_test");
    status.textContent = "";
    enter("quiet");
  }

  function enter(step) {
    wizard.step = step;
    wizard.samples = [];
    wizard.until = step === "quiet" ? Date.now() + QUIET_MS : step === "speak" ? Date.now() + SPEAK_MS : 0;
    clearTimeout(wizard.timer);
    if (wizard.until) wizard.timer = setTimeout(function () { finishTimed(step); }, wizard.until - Date.now());
    sourceRow.hidden = step !== "source";
    nextButton.hidden = step === "quiet" || step === "speak" || step === "review";
    nextButton.disabled = step === "wake";
    skipButton.hidden = step !== "source";
    saveButton.hidden = step !== "review";
    var titles = { quiet: "step_quiet", speak: "step_speak", wake: "step_wake", source: "step_source", review: "review" };
    stepTitle.textContent = L(titles[step]);
    stepText.textContent = step === "quiet" ? L("step_quiet_text") : step === "speak" ? L("step_speak_text")
      : step === "wake" ? L("step_wake_text", { wake_word: wakeWordName() }) : step === "source" ? L("step_source_text") : "";
    if (step === "source") fillSources();
    paint();
    progress();
  }

  function progress() {
    if (!wizard) return;
    var test = latest && latest.wake_test;
    if (wizard.until) {
      stepProgress.textContent = L("measuring", { seconds: Math.max(0, Math.ceil((wizard.until - Date.now()) / 1000)) });
    } else if ((wizard.step === "wake" || wizard.step === "source") && test && test.state !== "idle") {
      stepProgress.textContent = L("step_wake_progress", { fired: test.fired, peak: fixed(test.peak, 2), cutoff: fixed(test.cutoff, 2) });
      if (wizard.step === "wake") nextButton.disabled = !(test.fired >= 3 || test.state === "done");
    } else if (wizard.step !== "review") {
      stepProgress.textContent = "";
    }
  }

  function pick(samples, name, reduce) {
    var values = samples.map(function (s) { return s[name]; }).filter(function (v) { return typeof v === "number"; });
    return values.length ? values.reduce(function (a, b) { return reduce(a, b); }) : null;
  }

  async function finishTimed(step) {
    if (!wizard || wizard.step !== step) return;
    var samples = wizard.samples;
    if (step === "quiet") {
      wizard.floor = pick(samples, "floor_dbfs", Math.min);
      var test = latest && latest.wake_test;
      wizard.falseWakes = test && test.state === "running" ? test.fired : 0;
      enter("speak");
    } else {
      wizard.peak = pick(samples, "peak_dbfs", Math.max);
      wizard.clipped = samples.reduce(function (sum, s) { return sum + (s.clipped || 0); }, 0);
      // A fresh test for the wake word, so the quiet step's count does not carry over. A panel with no wake
      // word armed has nothing to test, and goes straight to the suggestions.
      if (wizard.wakeArmed && await act("wake_test")) enter("wake");
      else review();
    }
  }

  function advance() {
    if (!wizard) return;
    if (wizard.step === "wake") {
      wizard.wake = Object.assign({}, latest && latest.wake_test);
      enter("source");
    } else if (wizard.step === "source") {
      recordSource();
      review();
    }
  }

  // The one-of-several choice is the shared menu, as every other setting's is. Each option is one setting at one
  // value ("key=value"), grouped under its setting; trying one puts every other compared setting back as it was.
  function candidate(value) {
    var at = value.indexOf("=");
    return { key: value.slice(0, at), value: value.slice(at + 1) };
  }
  function candidateLabel(value) {
    var c = candidate(value), f = field(c.key);
    var option = optionLabel(c.key, c.value);
    return f && f.label ? f.label + ": " + option : option;
  }

  function fillSources() {
    sourcePick.textContent = "";
    COMPARED.forEach(function (key) {
      var f = field(key);
      if (!f || !f.options || !f.options.length) return;
      var group = el("optgroup", { label: f.label || key });
      f.options.forEach(function (value, index) {
        var option = el("option", { value: key + "=" + value, text: cfg.localizedEnumOption(f, value, index) });
        if (key === "voice_audio_source" && value === setting(key)) option.selected = true;
        group.appendChild(option);
      });
      sourcePick.appendChild(group);
    });
    if (!sourcePick.options.length) sourceRow.hidden = true;
  }

  // Whether the microphone is open on the route it was asked for. The open ladder and the panel's fallback to
  // its standard route can open another one, and what that heard says nothing about the setting tried.
  function openedAsAsked(route) {
    var a = route && route.active, r = route && route.requested;
    if (!a || !r) return false;
    return a.source === r.source && a.rate_hz === r.rate_hz && a.channels === r.channels &&
      (r.channels < 2 || a.downmix === r.downmix);
  }

  function recordSource() {
    var test = latest && latest.wake_test;
    if (!wizard.trying || !test || test.peak == null) return;
    var usable = !wizard.tryRefused && openedAsAsked(latest.route);
    wizard.sources = wizard.sources.filter(function (s) { return s.source !== wizard.trying; });
    wizard.sources.push({ source: wizard.trying, fired: test.fired, peak: test.peak, usable: usable });
    sourceResults.textContent = "";
    wizard.sources.forEach(function (s) {
      var text = s.usable ? L("source_result", { source: candidateLabel(s.source), fired: s.fired, peak: fixed(s.peak, 2) })
        : L("source_not_opened", { source: candidateLabel(s.source) });
      sourceResults.appendChild(el("p", { class: "note", text: text }));
    });
  }

  // The compared settings as the wizard started, with one candidate applied over them.
  function routeWith(value) {
    var values = {};
    COMPARED.forEach(function (key) { if (key in wizard.start) values[key] = wizard.start[key]; });
    if (value) { var c = candidate(value); values[c.key] = c.value; }
    return values;
  }

  // A trial's writes are drained before the wizard leaves the comparison (Skip, Next, Save or Cancel), so a reply
  // that lands late cannot leave the trial's value on the panel after the wizard has saved or put back the settings.
  async function trySource(value) {
    if (!wizard || !value) return;
    var w = wizard;
    if (w.stopped || w.writing) return;
    var run = applyTrial(w, value);
    w.writing = run;
    try { await run; } finally { if (w.writing === run) w.writing = null; }
  }

  // Stops the pending trial after the write in hand and waits for it, so cfg.values holds what the panel holds.
  async function endTrial(w) {
    w.stopped = true;
    if (w.writing) await w.writing.catch(function () {});
    w.writing = null;
  }

  async function applyTrial(w, value) {
    recordSource();
    sourceTry.disabled = true;
    // The panel reopens its microphone on another route only once nothing holds the old one.
    await stopAll();
    if (w.stopped) return;
    var values = routeWith(value), tried = candidate(value).key;
    // Put the others back first, so the microphone never opens on two changes at once.
    var order = Object.keys(values).filter(function (k) { return k !== tried; }).concat([tried]);
    var refused = false;
    for (var i = 0; i < order.length; i++) {
      var key = order[i];
      if (values[key] === "" || setting(key) === values[key]) continue;
      var reply = await window.PaneldTransport.command(key, values[key]);
      if (reply && reply.outcome && reply.outcome !== "applied") {
        // Not applied: the panel keeps its value, so a later write of this key must not be skipped.
        refused = true;
        if (!w.stopped) status.textContent = L("refused", { reason: reply.code || reply.outcome });
        continue;
      }
      // Recorded even once the trial is stopped, so a later save or rollback sees what the panel now holds.
      cfg.values[key] = values[key];
      if (w.stopped) return;
    }
    w.tryRefused = refused;
    if (w.stopped) return;
    await new Promise(function (done) { setTimeout(done, SOURCE_SETTLE_MS); });
    if (wizard !== w || w.stopped) return;
    w.trying = value;
    sourceTry.disabled = false;
    if (await act("level") && wizard === w && !w.stopped) await act("wake_test");
    if (wizard !== w || w.stopped) return;
    nextButton.hidden = false;
    nextButton.disabled = false;
  }

  // ---- Suggestions from what was measured.
  function suggestGain(current) {
    var peak = wizard.peak;
    if (peak == null) return { value: current, why: null };
    if (wizard.clipped > 0 || peak + current > -3) {
      return { value: Math.max(-24, Math.min(current, 0, Math.round(-6 - peak))), why: L("review_loud_gain") };
    }
    if (peak < -30) return { value: Math.min(24, Math.max(current, Math.round(-20 - peak))), why: L("review_quiet_gain", { peak: db(peak) }) };
    return { value: current, why: L("review_good_gain", { peak: db(peak) }) };
  }

  function suggestSensitivity(current) {
    var wake = wizard.wake || {};
    if (wizard.falseWakes > 0) return { value: "low", why: L("review_low") };
    if (wake.peak == null || wake.cutoff == null) return { value: current, why: null };
    if (wake.fired >= 3) return { value: current, why: L("review_ok") };
    if (wake.peak >= wake.cutoff - 0.12) return { value: "high", why: L("review_high") };
    return { value: current, why: L("review_far") };
  }

  function bestSource() {
    var best = null;
    wizard.sources.forEach(function (s) {
      if (!s.usable) return;
      if (!best || s.fired > best.fired || (s.fired === best.fired && s.peak > best.peak)) best = s;
    });
    return best ? best.source : null;
  }

  async function review() {
    if (!wizard) return;
    var w = wizard;
    if (w.reviewing) return;
    w.reviewing = true;
    await endTrial(w);
    if (wizard !== w) return;
    if (wizard.step === "source") recordSource();
    await stopAll();
    var gain = suggestGain(parseInt(wizard.start.voice_mic_gain_db, 10) || 0);
    var sensitivity = suggestSensitivity(wizard.start.voice_sensitivity);
    var best = bestSource();
    wizard.suggested = Object.assign(routeWith(best), { voice_mic_gain_db: String(gain.value), voice_sensitivity: sensitivity.value });
    enter("review");
    stepProgress.textContent = "";
    [L("review_gain", { value: (gain.value > 0 ? "+" : "") + gain.value }), gain.why,
      L("review_sensitivity", { value: optionLabel("voice_sensitivity", sensitivity.value) }), sensitivity.why,
      best ? L("review_source", { value: candidateLabel(best) }) : null
    ].forEach(function (line) { if (line) stepProgress.appendChild(el("span", { class: "voice-tuning-suggestion", text: line + " " })); });
  }

  async function write(values) {
    for (var key in values) {
      if (values[key] == null || values[key] === "" || setting(key) === values[key]) continue;
      var reply = await window.PaneldTransport.command(key, values[key]);
      if (reply && reply.outcome && reply.outcome !== "applied") throw new Error(L("refused", { reason: reply.code || reply.outcome }));
      cfg.values[key] = values[key];
    }
  }

  async function save() {
    if (!wizard || !wizard.suggested) return;
    var w = wizard;
    await endTrial(w);
    if (wizard !== w) return;
    try {
      await write(wizard.suggested);
      finish(L("saved"));
    } catch (error) { status.textContent = error.message; }
  }

  async function cancel() {
    if (!wizard) return;
    var w = wizard, start = w.start;
    clearTimeout(w.timer);
    await endTrial(w);
    if (wizard !== w) return;
    await stopAll();
    try {
      await write(start);
      finish(L("cancelled"));
    } catch (error) { status.textContent = error.message; }
  }

  async function abandon() {
    var message = status.textContent;
    await stopAll();
    finish(message);
  }

  function finish(message) {
    if (wizard) clearTimeout(wizard.timer);
    wizard = null;
    wizardBox.hidden = true;
    sourceResults.textContent = "";
    status.textContent = message || "";
    if (cfg.render) cfg.render();
    paint();
  }

  // The Voice card asks for this node while voice is on; the diagnostics family is read only while it is shown.
  cfg.voiceTuningNode = function () {
    if (!watch) {
      watch = window.PaneldTransport.subscribe("voice", function (d, state) {
        if (!root.isConnected) { watch.close(); watch = null; return; }
        if (state === "known" && d && typeof d === "object") show(d);
      });
    }
    paint();
    return root;
  };
})(window.ConfigurePage);
