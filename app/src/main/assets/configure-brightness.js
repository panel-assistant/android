// Configure adaptive-brightness status, history and chart.
(function (cfg) {
  "use strict";


  function ambientLightSourceReady() {
    var selected = String(cfg.values.auto_brightness_ha_entity || "").trim();
    var statusEntity = cfg.autoBrightStatus && (cfg.autoBrightStatus.entityId || cfg.autoBrightStatus.entity_id) || "";
    var statusLux = cfg.autoBrightStatus && (cfg.autoBrightStatus.latestLux != null ? cfg.autoBrightStatus.latestLux : cfg.autoBrightStatus.latest_lux);
    var statusReady = !!(cfg.autoBrightStatus &&
      (cfg.autoBrightStatus.sourceAvailable === true || cfg.autoBrightStatus.source_available === true) &&
      typeof statusLux === "number" && isFinite(statusLux));
    if (!selected) return statusReady && !statusEntity;
    if (statusReady && statusEntity === selected) return true;
    return cfg.haSourceItems.some(function (item) {
      var id = item.entity_id || item.entityId || "";
      var lux = item.current_lux != null ? item.current_lux : item.currentLux;
      return id === selected && item.available === true && typeof lux === "number" && isFinite(lux);
    });
  }

  function ambientLightSourceConfigured() {
    if (String(cfg.values.auto_brightness_ha_entity || "").trim()) return true;
    return !!(cfg.autoBrightStatus &&
      (cfg.autoBrightStatus.localSourcePresent === true || cfg.autoBrightStatus.local_source_present === true));
  }

  function ambientSourcePlaceholder() {
    if (!cfg.autoBrightStatus) return cfg.i18nText("configure.brightness.source_checking", "Checking ambient light source…");
    var localPresent = cfg.autoBrightStatus.localSourcePresent === true || cfg.autoBrightStatus.local_source_present === true;
    return localPresent ? cfg.i18nText("configure.brightness.panel_sensor", "Panel ambient light sensor") : cfg.i18nText("configure.brightness.select_ha_sensor", "Select a Home Assistant illuminance sensor");
  }

  function pointValue(point, names) {
    for (var i = 0; i < names.length; i++) {
      var value = point[names[i]];
      if (typeof value === "number" && isFinite(value)) return value;
    }
    return null;
  }

  function normalizedChartPoints() {
    var raw = cfg.autoBrightHistory && (cfg.autoBrightHistory.points || cfg.autoBrightHistory.items) || [];
    return raw.map(function (point) {
      return {
        minute: pointValue(point, ["minute", "epoch_minute", "epochMinute"]),
        localDay: point.local_day || point.localDay || "",
        minuteOfDay: pointValue(point, ["minute_of_day", "minuteOfDay"]),
        dayAge: pointValue(point, ["day_age", "dayAge"]),
        mean: pointValue(point, ["mean_lux", "observed_mean_lux", "observedMeanLux", "lux"]),
        min: pointValue(point, ["min_lux", "minLux"]),
        max: pointValue(point, ["max_lux", "maxLux"]),
        expected: pointValue(point, ["baseline_lux", "expected_lux", "expectedLux"]),
        brightness: pointValue(point, ["proposed_brightness", "proposedBrightness"])
      };
    }).filter(function (point) {
      return point.minute != null && point.mean != null && point.localDay &&
        point.minuteOfDay != null && point.dayAge != null && point.dayAge >= 0 && point.dayAge < 7;
    });
  }

  function autoBrightnessChartDays(points) {
    var grouped = {};
    points.forEach(function (point) {
      if (!grouped[point.localDay]) grouped[point.localDay] = { key: point.localDay, age: point.dayAge, points: [] };
      grouped[point.localDay].points.push(point);
    });
    return Object.keys(grouped).map(function (key) {
      grouped[key].points.sort(function (a, b) { return a.minute - b.minute; });
      return grouped[key];
    }).sort(function (a, b) { return b.age - a.age; });
  }

  // Age hierarchy for the days that are drawn individually.
  // Every dimension uses arithmetic and core canvas state rather than an optional filter effect, so
  // the intended softening remains present when an engine omits that effect.
  //
  // The blur is required — it is what makes a previous day read as background rather than as another
  // competing line. What it must NOT use is the `filter` property on the 2D context, which is how this
  // was first written: Chromium and the panel WebView applied it while Safari showed no softening, so
  // the same history rendered as two different charts. That difference is silent, which is the real
  // damage: the radius was tuned in a browser that was showing no effect and reached 52px at day-age 6
  // without anyone ever seeing the result. A capability probe is not a fix either — skipping the call
  // leaves the day unsoftened rather than giving it a fallback. So the radius below is real and the
  // blur is genuinely drawn; see the feathered stroke in line() for how it is realised portably.
  // How many days are drawn as lines. The rest of the week survives as the painted min/max region.
  //
  // Seven days times three traces was twenty-one lines, and adjacent days could not be told apart at
  // any combination of opacity, blur and colour. That is not a tuning failure: seven ordered steps do
  // not fit between today's saturated colour and the dimmest step still visible on the card, so the
  // encoding was being asked for more levels than the space holds. Three days fit comfortably, and the
  // week's spread is better served by one region than by four more lines nobody could separate.
  function autoBrightnessLineDays() { return 3; }

  // Age hierarchy for those three days. Every dimension uses arithmetic and core canvas state, so the
  // encoding does not disappear when an engine omits an optional drawing effect.
  //
  // The blur is required — it is what makes a previous day read as background rather than as another
  // competing line. What it must NOT use is the `filter` property on the 2D context, which is how this
  // was first written: Chromium and the panel WebView applied it while Safari showed no softening, so
  // the same history rendered as two different charts. That difference is silent, which is the real
  // damage: the radius was tuned in a browser that was showing no effect and reached 52px at day-age 6
  // without anyone ever seeing the result. A capability probe is not a fix either — skipping the call
  // leaves the day unsoftened rather than giving it a fallback. So the radius below is real and the
  // blur is genuinely drawn; see the feathered stroke in line() for how it is realised portably.
  //
  // Opacity is deliberately NOT part of the ramp. Ink conservation already dims a blurred trace by
  // roughly the ratio of its spread to its width, so an extra alpha cut on top bleached the older days
  // until they read as grey. Age is carried by radius and width alone, with chroma compensating.
  function autoBrightnessDayStyle(age) {
    var boundedAge = Math.max(0, Math.min(autoBrightnessLineDays() - 1, age));
    return {
      blurPx: [0, 1.2, 3.2][boundedAge],
      widthPx: [1.8, 1.5, 1.3][boundedAge]
    };
  }

  // The blur's cross-section, as [fraction of the radius, share of the layer's opacity], widest first.
  // Held in its own function so the whole age encoding can be swapped in one place when tuning against
  // real renderers — the comparison harness overrides this and autoBrightnessDayStyle and nothing else.
  // Weighted toward the core. Ink is conserved, so a flat profile spends most of it on the widest,
  // faintest pass and the peak opacity collapses — at radius 3 the spread is over five times the line
  // width, which puts the core near 5% alpha. A saturated colour at 5% over a near-black card composites
  // to almost the background, so the trace goes grey and loses its hue entirely. Concentrating the ink
  // in the middle keeps a coloured core with a soft edge, which is what a blurred line should look like.
  function autoBrightnessFeather() {
    return [[1, .06], [.72, .12], [.4, .22], [0, .6]];
  }

  // Compensates a blurred trace's colour. A faint mark loses its hue toward whatever is behind it, and
  // ink conservation makes a heavily blurred day faint by construction — so the more a day is blurred,
  // the more chroma its source colour needs in order to still read as blue, amber or purple rather than
  // as grey. Pushes the colour away from its own luminance grey; clamping means this can only ever
  // saturate, never wash toward white, which is what made an earlier light-to-dark ramp unusable.
  // Pure arithmetic on purpose — the CSS colour-mixing functions and relative colour syntax have
  // uneven engine support, and canvas takes a plain colour string anyway.
  function autoBrightnessAgedColor(color, blurPx) {
    var amount = 1 + blurPx * .13;
    var channels = [parseInt(color.slice(1, 3), 16), parseInt(color.slice(3, 5), 16), parseInt(color.slice(5, 7), 16)];
    var grey = .299 * channels[0] + .587 * channels[1] + .114 * channels[2];
    return "rgb(" + channels.map(function (channel) {
      return Math.max(0, Math.min(255, Math.round(grey + (channel - grey) * amount)));
    }).join(",") + ")";
  }

  function autoBrightnessSmoothing(age) {
    var boundedAge = Math.max(0, Math.min(6, Math.round(age)));
    return {
      medianWindow: [1, 3, 5, 7, 9, 11, 13][boundedAge],
      averageWindow: [1, 1, 3, 5, 7, 9, 11][boundedAge]
    };
  }

  function centeredMedian(values, windowSize) {
    if (windowSize <= 1) return values.slice();
    var radius = Math.floor(windowSize / 2);
    return values.map(function (_value, index) {
      var sample = values.slice(Math.max(0, index - radius), Math.min(values.length, index + radius + 1));
      sample.sort(function (a, b) { return a - b; });
      return sample[Math.floor(sample.length / 2)];
    });
  }

  function centeredWeightedAverage(values, windowSize) {
    if (windowSize <= 1) return values.slice();
    var radius = Math.floor(windowSize / 2);
    return values.map(function (_value, index) {
      var total = 0, weightTotal = 0;
      for (var offset = -radius; offset <= radius; offset += 1) {
        var sampleIndex = index + offset;
        if (sampleIndex < 0 || sampleIndex >= values.length) continue;
        var weight = radius + 1 - Math.abs(offset);
        total += values[sampleIndex] * weight;
        weightTotal += weight;
      }
      return weightTotal ? total / weightTotal : values[index];
    });
  }

  function smoothedRunValues(run, field, age, fallbackField) {
    var smoothing = autoBrightnessSmoothing(age);
    var values = run.map(function (point) {
      return point[field] == null && fallbackField ? point[fallbackField] : point[field];
    });
    return centeredWeightedAverage(centeredMedian(values, smoothing.medianWindow), smoothing.averageWindow);
  }

  function drawAutoBrightnessChart() {
    var canvas = document.getElementById("auto-brightness-chart");
    if (!canvas) return;
    var points = normalizedChartPoints();
    var width = Math.max(280, canvas.clientWidth || 0), height = 190;
    var ratio = Math.min(2, window.devicePixelRatio || 1);
    canvas.width = Math.round(width * ratio); canvas.height = Math.round(height * ratio);
    var ctx = canvas.getContext("2d"); ctx.scale(ratio, ratio);
    ctx.clearRect(0, 0, width, height);
    if (!points.length) {
      ctx.fillStyle = "#888"; ctx.font = "13px sans-serif"; ctx.textAlign = "center";
      ctx.fillText(cfg.i18nText("configure.brightness.history_none", "No ambient-light history yet"), width / 2, height / 2);
      return;
    }
    var days = autoBrightnessChartDays(points);
    var bucketMinutes = cfg.autoBrightHistory && (cfg.autoBrightHistory.bucket_minutes || cfg.autoBrightHistory.bucketMinutes) || 5;
    var pad = { left: 0, right: 0, top: 10, bottom: 24 };
    var plotW = width - pad.left - pad.right, plotH = height - pad.top - pad.bottom;
    var maxLux = 1;
    points.forEach(function (p) { maxLux = Math.max(maxLux, p.max == null ? p.mean : p.max, p.expected || 0); });
    var maxLog = Math.log(maxLux + 1);
    function x(p) { return pad.left + p.minuteOfDay / 1440 * plotW; }
    function y(value) { return pad.top + plotH - Math.log(Math.max(0, value) + 1) / maxLog * plotH; }
    function yBrightness(value) { return pad.top + plotH - Math.max(0, Math.min(255, value)) / 255 * plotH; }
    ctx.strokeStyle = "#343a44"; ctx.lineWidth = 1;
    [0, .5, 1].forEach(function (f) {
      var yy = pad.top + plotH * f; ctx.beginPath(); ctx.moveTo(pad.left, yy); ctx.lineTo(width - pad.right, yy); ctx.stroke();
    });
    [0, 360, 720, 1080, 1440].forEach(function (minute) {
      var xx = pad.left + minute / 1440 * plotW;
      ctx.beginPath(); ctx.moveTo(xx, pad.top); ctx.lineTo(xx, pad.top + plotH); ctx.stroke();
    });
    function runs(dayPoints, field) {
      var result = [], run = [];
      dayPoints.forEach(function (point) {
        var previous = run.length ? run[run.length - 1] : null;
        var discontinuous = previous && (
          point.minute - previous.minute !== bucketMinutes ||
          point.minuteOfDay - previous.minuteOfDay !== bucketMinutes
        );
        if (point[field] == null || discontinuous) {
          if (run.length) result.push(run);
          run = [];
        }
        if (point[field] != null) run.push(point);
      });
      if (run.length) result.push(run);
      return result;
    }
    // Draws one trace, optionally blurred. The blur is a feathered stroke: the same path is stroked
    // several times, from widthPx + 2 * blurPx down to widthPx, each pass at a fraction of the layer's
    // opacity. Those passes composite into a smooth-edged band with no hard core, which is what a
    // blurred line looks like — a stroke widened by 2r and falling off toward its edges IS the line
    // convolved with a radius-r kernel, to the accuracy this chart needs.
    //
    // It is done this way rather than with a canvas filter because stroking and globalAlpha are widely
    // implemented core 2D operations, so every target receives the same visual encoding even though
    // rasterisation details can differ. It is also cheap: a handful of extra strokes per trace, no pixel
    // readback, no offscreen buffer, nothing to allocate per frame.
    function line(day, field, color, widthPx, projectY, blurPx) {
      var featherPasses = autoBrightnessFeather();
      runs(day.points, field).forEach(function (run) {
        var values = smoothedRunValues(run, field, day.age);
        ctx.beginPath();
        run.forEach(function (point, index) {
          if (!index) ctx.moveTo(x(point), projectY(values[index])); else ctx.lineTo(x(point), projectY(values[index]));
        });
        ctx.strokeStyle = color;
        if (!blurPx) { ctx.lineWidth = widthPx; ctx.stroke(); return; }
        var layerAlpha = ctx.globalAlpha;
        // Conserve ink. A blur SPREADS a line's brightness over a wider area; it must never add more.
        // Without this the passes simply stack, so a blurred trace lights more pixels than the sharp one
        // and reads as bolder and glowing rather than smudged — the opposite of receding into the
        // background. Scaling the opacities by width/spread-width keeps the total roughly equal to the
        // unblurred stroke, so more blur automatically means more transparency.
        // Approximate on purpose: alpha compositing is not additive, so overlapping passes retain a
        // little more than this predicts. Close enough that blur reads as softening, not highlighting.
        var spreadInk = 0;
        featherPasses.forEach(function (pass) { spreadInk += (widthPx + 2 * blurPx * pass[0]) * pass[1]; });
        var conserve = spreadInk > 0 ? widthPx / spreadInk : 1;
        featherPasses.forEach(function (pass) {
          ctx.globalAlpha = layerAlpha * pass[1] * conserve;
          ctx.lineWidth = widthPx + 2 * blurPx * pass[0];
          ctx.stroke();
        });
        ctx.globalAlpha = layerAlpha;
      });
    }
    // The whole week's spread, as one painted region between the lowest and highest reading seen at each
    // time of day. Painted FIRST so the day lines sit on top of it, and tinted with the lux hue rather
    // than a neutral: the region IS the lux range, and a grey fill reads as haze over the background
    // instead of as something deliberate. It carries every day, including the ones drawn as lines, so
    // the days beyond the third are represented here rather than dropped.
    var lowest = {}, highest = {};
    points.forEach(function (point) {
      var slot = point.minuteOfDay;
      var low = point.min == null ? point.mean : point.min;
      var high = point.max == null ? point.mean : point.max;
      if (lowest[slot] == null || low < lowest[slot]) lowest[slot] = low;
      if (highest[slot] == null || high > highest[slot]) highest[slot] = high;
    });
    // Split on missing data exactly as the day traces do. A single polygon over every sampled slot
    // would bridge periods the week has no observations for, painting a filled region across a gap and
    // asserting a range that was never measured — the same defect the run-splitting in runs() exists to
    // prevent for lines, and more misleading here because a fill looks like coverage.
    var slots = Object.keys(lowest).map(Number).sort(function (a, b) { return a - b; });
    var spans = [], span = [];
    slots.forEach(function (slot) {
      if (span.length && slot - span[span.length - 1] !== bucketMinutes) { spans.push(span); span = []; }
      span.push(slot);
    });
    if (span.length) spans.push(span);
    ctx.fillStyle = "rgba(74,158,255,.15)";
    spans.forEach(function (run) {
      if (run.length < 2) return;
      ctx.beginPath();
      run.forEach(function (slot, index) {
        var yy = y(highest[slot]);
        if (!index) ctx.moveTo(pad.left + slot / 1440 * plotW, yy); else ctx.lineTo(pad.left + slot / 1440 * plotW, yy);
      });
      run.slice().reverse().forEach(function (slot) {
        ctx.lineTo(pad.left + slot / 1440 * plotW, y(lowest[slot]));
      });
      ctx.closePath(); ctx.fill();
    });
    // Oldest of the three first, so today is stroked last and stays on top of its neighbours.
    days.filter(function (day) { return day.age < autoBrightnessLineDays(); }).forEach(function (day) {
      var style = autoBrightnessDayStyle(day.age);
      ctx.save();
      line(day, "mean", autoBrightnessAgedColor("#4a9eff", style.blurPx), style.widthPx, y, style.blurPx);
      line(day, "expected", autoBrightnessAgedColor("#f1bd52", style.blurPx), style.widthPx, y, style.blurPx);
      line(day, "brightness", autoBrightnessAgedColor("#b77cff", style.blurPx), style.widthPx, yBrightness, style.blurPx);
      ctx.restore();
    });
    ctx.fillStyle = "#888"; ctx.font = "11px sans-serif";
    ctx.textAlign = "left"; ctx.fillText(Math.round(maxLux) + " lx", pad.left + 5, pad.top + 12); ctx.fillText("0", pad.left + 5, pad.top + plotH - 4);
    ctx.textAlign = "right"; ctx.fillText("100%", width - pad.right - 5, pad.top + 12); ctx.fillText("0%", width - pad.right - 5, pad.top + plotH - 4);
    [0, 360, 720, 1080, 1440].forEach(function (minute, index) {
      var xx = pad.left + minute / 1440 * plotW;
      ctx.textAlign = index === 0 ? "left" : index === 4 ? "right" : "center";
      ctx.fillText(index === 4 ? "24:00" : ("0" + Math.floor(minute / 60)).slice(-2) + ":00", xx, height - 6);
    });
  }

  function autoBrightnessSummary() {
    if (cfg.autoBrightSourceTransition) return cfg.i18nText("configure.brightness.source_updating", "Updating ambient-light source… · Source: {source}", { source: autoBrightnessSelectedSource() });
    if (!cfg.autoBrightStatus) return cfg.autoBrightLoading ? cfg.i18nText("configure.brightness.loading", "Loading adaptive brightness…") : cfg.i18nText("configure.brightness.status_unavailable", "Adaptive brightness status is unavailable.");
    if (cfg.autoBrightStatus.available === false) return cfg.i18nText("configure.brightness.runtime_unavailable", "Adaptive brightness runtime is unavailable.");
    if (cfg.autoBrightStatus.sourceAvailable === false || cfg.autoBrightStatus.source_available === false) {
      return cfg.i18nText("configure.brightness.waiting", "Waiting for ambient light… · Source: {source}", { source: autoBrightnessSelectedSource() });
    }
    var state = cfg.autoBrightStatus.state || "learning";
    if (cfg.autoBrightStatus.preferenceActive === true || cfg.autoBrightStatus.paused === true) state = "temporary preference";
    else if (state === "enabled" && cfg.autoBrightStatus.mode) state = cfg.autoBrightStatus.mode;
    var stateLabels = {
      learning: cfg.i18nText("configure.brightness.state_learning", "learning"),
      "temporary preference": cfg.i18nText("configure.brightness.state_temporary_preference", "temporary preference"),
      enabled: cfg.i18nText("configure.brightness.state_enabled", "enabled"),
      disabled: cfg.i18nText("configure.brightness.state_disabled", "disabled")
    };
    state = stateLabels[state] || state;
    return cfg.i18nText("configure.brightness.state_source", "{state} · Source: {source}", { state: state, source: autoBrightnessSelectedSource() });
  }

  function autoBrightnessSelectedSource() {
    if (cfg.autoBrightSourceTransition && cfg.autoBrightTransitionSource) return cfg.autoBrightTransitionSource === "panel sensor"
      ? cfg.i18nText("configure.brightness.panel_sensor_short", "panel sensor") : cfg.autoBrightTransitionSource;
    var selected = String(cfg.values.auto_brightness_ha_entity || "").trim();
    if (selected) return selected;
    return cfg.autoBrightStatus && (
      cfg.autoBrightStatus.source_label || cfg.autoBrightStatus.sourceLabel || cfg.autoBrightStatus.entity_id || cfg.autoBrightStatus.entityId
    ) || cfg.i18nText("configure.brightness.panel_sensor_short", "panel sensor");
  }

  function autoBrightnessSourceRevision(body) {
    if (!body) return null;
    var revision = body.sourceRevision != null ? body.sourceRevision : body.source_revision;
    return revision == null ? null : String(revision);
  }

  function autoBrightnessStatusMatchesSelection(status) {
    var expected = cfg.autoBrightSourceTransition
      ? (cfg.autoBrightTransitionSource === "panel sensor" ? "" : cfg.autoBrightTransitionSource)
      : String(cfg.values.auto_brightness_ha_entity || "").trim();
    var actual = status && (status.entityId != null ? status.entityId : status.entity_id);
    actual = actual == null ? "" : String(actual).trim();
    return actual === expected;
  }

  function autoBrightnessLatestEpochMinute() {
    if (!cfg.autoBrightHistory) return null;
    var value = cfg.autoBrightHistory.latestEpochMinute != null
      ? cfg.autoBrightHistory.latestEpochMinute : cfg.autoBrightHistory.latest_epoch_minute;
    return typeof value === "number" && isFinite(value) ? value : null;
  }

  function autoBrightnessFreshness() {
    var latest = autoBrightnessLatestEpochMinute();
    if (latest == null) return cfg.autoBrightSourceTransition ? cfg.i18nText("configure.brightness.new_history_loading", "Loading new source history…") : cfg.i18nText("configure.brightness.samples_none", "No samples yet");
    var date = new Date(latest * 60000);
    var ageMinutes = Math.max(0, Math.floor((Date.now() - date.getTime()) / 60000));
    var relative = ageMinutes < 1 ? cfg.i18nText("configure.time.just_now", "just now")
      : ageMinutes < 60 ? cfg.i18nText("configure.time.minutes_ago", "{count} min ago", { count: ageMinutes })
      : ageMinutes < 1440 ? cfg.i18nText("configure.time.hours_ago", "{count} hr ago", { count: Math.floor(ageMinutes / 60) })
      : cfg.i18nText("configure.time.days_ago", "{count} days ago", { count: Math.floor(ageMinutes / 1440) });
    return cfg.i18nText("configure.time.updated", "Updated {date} ({relative})", { date: date.toLocaleString(), relative: relative });
  }

  // Preview bounds come from /api/v1/config/schema, never from literals here. A copy in this file
  // disagrees with the server the moment either bound moves, and the failure is silent: an in-range
  // value outside the stale copy loses its query parameter, so the chart quietly projects the STORED
  // value while the operator believes they are previewing the one they just typed. That is exactly
  // what a hard-coded 95 did once the ceiling rose. A value outside the server's own bounds is still
  // omitted rather than sent — it would only earn a 400 — and the control is already invalid, so the
  // save gate reports it.
  function autoBrightnessPreviewSuffix(param, key) {
    var field = null;
    for (var i = 0; i < cfg.schema.length; i++) if (cfg.schema[i].key === key) { field = cfg.schema[i]; break; }
    var value = parseInt(cfg.values[key], 10);
    if (!isFinite(value) || !field || field.min == null || field.max == null) return "";
    return value < field.min || value > field.max ? "" : param + encodeURIComponent(value);
  }

  function autoBrightnessProjectionSensitivity() {
    if (!cfg.autoBrightHistory) return null;
    var value = parseInt(cfg.autoBrightHistory.sensitivity, 10);
    return isFinite(value) && value >= 0 && value <= 100 ? value : null;
  }

  function beginAutoBrightnessSourceTransition(source, preserveCurrentRequest) {
    // A save can overtake an already-running refresh. Fence that response before it can
    // make a matching pair from the previous source look current.
    if (!preserveCurrentRequest) cfg.autoBrightRequest += 1;
    cfg.autoBrightSourceTransition = true;
    cfg.autoBrightTransitionAttempts = 0;
    cfg.autoBrightTransitionSource = String(source || "").trim() || "panel sensor";
    cfg.autoBrightHistory = { points: [] };
    cfg.autoBrightMessage = cfg.i18nText("configure.brightness.selected_history_loading", "Loading history for the selected source…");
    if (cfg.autoBrightTransitionTimer) clearTimeout(cfg.autoBrightTransitionTimer);
    cfg.autoBrightTransitionTimer = null;
    if (cfg.autoBrightRefreshTimer) clearTimeout(cfg.autoBrightRefreshTimer);
    cfg.autoBrightRefreshTimer = null;
    cfg.render();
  }

  function finishAutoBrightnessSourceTransition() {
    cfg.autoBrightSourceTransition = false;
    cfg.autoBrightTransitionAttempts = 0;
    cfg.autoBrightTransitionSource = "";
    if (cfg.autoBrightTransitionTimer) clearTimeout(cfg.autoBrightTransitionTimer);
    cfg.autoBrightTransitionTimer = null;
  }

  function scheduleAutoBrightnessTransitionPoll() {
    if (cfg.autoBrightTransitionTimer) clearTimeout(cfg.autoBrightTransitionTimer);
    cfg.autoBrightTransitionTimer = null;
    if (!cfg.autoBrightSourceTransition || document.hidden) return;
    cfg.autoBrightTransitionTimer = setTimeout(function () {
      cfg.autoBrightTransitionTimer = null;
      cfg.autoBrightTransitionAttempts += 1;
      loadAutoBrightnessData(true);
    }, cfg.AUTO_BRIGHTNESS_TRANSITION_POLL_MS);
  }

  function loadAutoBrightnessData(force) {
    if (cfg.autoBrightLoading && !force) return;
    cfg.autoBrightLoading = true;
    var request = ++cfg.autoBrightRequest;
    var sensitivitySuffix = autoBrightnessPreviewSuffix("&sensitivity=", "auto_brightness_response_percent");
    var minimumSuffix = autoBrightnessPreviewSuffix("&minimum_percent=", "auto_brightness_minimum_percent");
    var succeeded = false;
    Promise.all([
      fetch("api/v1/auto-brightness", { cache: "no-store" }).then(function (r) { if (!r.ok) throw r.status; return r.json(); }),
      fetch("api/v1/auto-brightness/history?hours=168" + sensitivitySuffix + minimumSuffix, { cache: "no-store" }).then(function (r) { if (!r.ok) throw r.status; return r.json(); })
    ]).then(function (result) {
      if (request !== cfg.autoBrightRequest) return;
      var statusRevision = autoBrightnessSourceRevision(result[0]);
      var historyRevision = autoBrightnessSourceRevision(result[1]);
      var revisionsMatch = statusRevision != null && statusRevision === historyRevision &&
        autoBrightnessStatusMatchesSelection(result[0]);
      var latest = result[1].latestEpochMinute != null ? result[1].latestEpochMinute : result[1].latest_epoch_minute;
      cfg.autoBrightStatus = result[0];
      succeeded = true;
      if (!revisionsMatch) {
        if (!cfg.autoBrightSourceTransition) beginAutoBrightnessSourceTransition(autoBrightnessSelectedSource(), true);
        cfg.autoBrightHistory = { points: [] };
        if (cfg.autoBrightTransitionAttempts >= cfg.AUTO_BRIGHTNESS_TRANSITION_MAX_ATTEMPTS) {
          finishAutoBrightnessSourceTransition();
          cfg.autoBrightMessage = cfg.i18nText("configure.brightness.history_preparing", "History is still preparing for the selected source; it will retry on the normal refresh.");
        } else {
          cfg.autoBrightMessage = cfg.i18nText("configure.brightness.selected_history_loading", "Loading history for the selected source…");
          scheduleAutoBrightnessTransitionPoll();
        }
      } else if (cfg.autoBrightSourceTransition && latest == null && cfg.autoBrightTransitionAttempts < cfg.AUTO_BRIGHTNESS_TRANSITION_MAX_ATTEMPTS) {
        cfg.autoBrightHistory = { points: [], sourceRevision: result[1].sourceRevision };
        cfg.autoBrightMessage = cfg.i18nText("configure.brightness.first_sample_waiting", "Waiting for the first sample from the selected source…");
        scheduleAutoBrightnessTransitionPoll();
      } else {
        cfg.autoBrightHistory = result[1];
        if (cfg.autoBrightSourceTransition) {
          var timedOutEmpty = latest == null;
          finishAutoBrightnessSourceTransition();
          cfg.autoBrightMessage = timedOutEmpty ? cfg.i18nText("configure.brightness.selected_samples_none", "The selected source has no ambient-light samples yet.") : "";
        } else cfg.autoBrightMessage = "";
      }
    }).catch(function () {
      if (request !== cfg.autoBrightRequest) return;
      if (typeof window !== "undefined" && window.configCardSizeGeometryInvalid) window.configCardSizeGeometryInvalid();
      if (!cfg.autoBrightSourceTransition) cfg.autoBrightStatus = { available: false, detail: cfg.i18nText("configure.brightness.load_failed", "Could not load adaptive brightness.") };
      cfg.autoBrightHistory = { points: [] };
      if (cfg.autoBrightSourceTransition && cfg.autoBrightTransitionAttempts < cfg.AUTO_BRIGHTNESS_TRANSITION_MAX_ATTEMPTS) {
        cfg.autoBrightMessage = cfg.i18nText("configure.brightness.selected_source_loading", "The selected source is still loading…");
        scheduleAutoBrightnessTransitionPoll();
      } else if (cfg.autoBrightSourceTransition) {
        finishAutoBrightnessSourceTransition();
        cfg.autoBrightMessage = cfg.i18nText("configure.brightness.selected_source_failed", "The selected source could not be loaded; it will retry on the normal refresh.");
      }
    }).then(function () {
      if (request !== cfg.autoBrightRequest) return;
      cfg.autoBrightLoading = false; cfg.render();
      if (succeeded && typeof window !== "undefined" && window.configCardSizeSourceReady) {
        window.configCardSizeSourceReady("brightness");
        if (window.configCardSizeGeometryChanged) window.configCardSizeGeometryChanged();
      }
      scheduleAutoBrightnessRefresh();
    });
  }

  function scheduleAutoBrightnessRefresh() {
    if (cfg.autoBrightRefreshTimer) clearTimeout(cfg.autoBrightRefreshTimer);
    cfg.autoBrightRefreshTimer = null;
    if (cfg.autoBrightSourceTransition) return;
    if (cfg.values.auto_brightness !== "true" || document.hidden) return;
    cfg.autoBrightRefreshTimer = setTimeout(function () {
      cfg.autoBrightRefreshTimer = null;
      if (document.hidden) return;
      loadAutoBrightnessData(false);
    }, cfg.AUTO_BRIGHTNESS_REFRESH_MS);
  }

  if (document.addEventListener) {
    document.addEventListener("visibilitychange", function () {
      if (document.hidden) {
        if (cfg.autoBrightRefreshTimer) clearTimeout(cfg.autoBrightRefreshTimer);
        cfg.autoBrightRefreshTimer = null;
        if (cfg.autoBrightTransitionTimer) clearTimeout(cfg.autoBrightTransitionTimer);
        cfg.autoBrightTransitionTimer = null;
      } else if (cfg.values.auto_brightness === "true") {
        loadAutoBrightnessData(false);
      }
    });
  }

  function queueAutoBrightnessHistory() {
    if (cfg.autoBrightHistoryTimer) clearTimeout(cfg.autoBrightHistoryTimer);
    cfg.autoBrightHistoryTimer = setTimeout(function () { loadAutoBrightnessData(true); }, 300);
  }

  var autoBrightnessResizeTimer = null;
  if (window.addEventListener) {
    window.addEventListener("resize", function () {
      if (autoBrightnessResizeTimer) clearTimeout(autoBrightnessResizeTimer);
      autoBrightnessResizeTimer = setTimeout(drawAutoBrightnessChart, 100);
    });
  }

  function runAutoBrightnessAction(path, button) {
    button.disabled = true; cfg.autoBrightMessage = cfg.i18nText("configure.action.working", "Working…"); cfg.render();
    fetch(path, { method: "POST", headers: { "Accept": "application/json" } })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (body) { if (!r.ok) throw (body.error || ("HTTP " + r.status)); return body; }); })
      .then(function () { cfg.autoBrightMessage = cfg.i18nText("configure.action.updated", "Updated."); loadAutoBrightnessData(true); })
      .catch(function (error) { cfg.autoBrightMessage = cfg.i18nText("configure.action.failed", "Action failed ({error}).", { error: error }); button.disabled = false; cfg.render(); });
  }

  function autoBrightnessPanel() {
    var available = cfg.autoBrightStatus && cfg.autoBrightStatus.available !== false;
    var paused = cfg.autoBrightStatus && (
      cfg.autoBrightStatus.preferenceActive === true || cfg.autoBrightStatus.paused === true || cfg.autoBrightStatus.state === "paused"
    );
    var reset = cfg.el("button", { class: "pbtn", type: "button", text: cfg.i18nText("configure.brightness.reset_history", "Reset learned history") });
    var resume = cfg.el("button", { class: "pbtn", type: "button", text: cfg.i18nText("configure.brightness.resume_auto", "Resume full auto") });
    reset.disabled = !available || cfg.autoBrightLoading;
    resume.disabled = !available || !paused || cfg.autoBrightLoading;
    reset.onclick = function () {
      if (confirm(cfg.i18nText("configure.brightness.reset_confirm", "Delete the seven-day ambient-light history and restart learning?"))) runAutoBrightnessAction("api/v1/auto-brightness/reset", reset);
    };
    resume.onclick = function () { runAutoBrightnessAction("api/v1/auto-brightness/resume", resume); };
    var bucket = cfg.autoBrightHistory && (cfg.autoBrightHistory.bucket_minutes || cfg.autoBrightHistory.bucketMinutes);
    var dayCount = autoBrightnessChartDays(normalizedChartPoints()).length;
    var lineDayCount = Math.min(dayCount, autoBrightnessLineDays());
    var detail = cfg.i18nText("configure.brightness.days_drawn", "{count} day(s) drawn individually", { count: lineDayCount });
    if (dayCount > autoBrightnessLineDays()) detail += cfg.i18nText("configure.brightness.earlier_weekly_range", " · earlier history shown as weekly range");
    if (bucket) detail += cfg.i18nText("configure.brightness.minute_buckets", " · {count} minute buckets", { count: bucket });
    var projectionSensitivity = autoBrightnessProjectionSensitivity();
    if (projectionSensitivity != null) detail += cfg.i18nText("configure.brightness.sensitivity", " · Sensitivity {percent}%", { percent: projectionSensitivity });
    detail += " · " + autoBrightnessFreshness();
    var panel = cfg.el("div", { class: "autobright-panel", id: "auto-brightness-learning" }, [
      cfg.el("div", { class: "autobright-head" }, [
        cfg.el("div", {}, [cfg.el("strong", { text: cfg.i18nText("configure.brightness.daily_learning", "Daily ambient learning") }), cfg.el("small", { text: autoBrightnessSummary() })]),
        cfg.el("div", { class: "autobright-actions" }, [reset, resume])
      ]),
      cfg.el("canvas", { id: "auto-brightness-chart", class: "autobright-chart", role: "img", "aria-label": cfg.i18nText("configure.brightness.chart_label", "24-hour ambient-light pattern: the three most recent days drawn individually, with the rest of the week shown as a shaded minimum-to-maximum range") }),
      cfg.el("div", { class: "autobright-legend" }, [
        cfg.el("span", { class: "observed", text: cfg.i18nText("configure.brightness.observed", "Observed") }),
        cfg.el("span", { class: "expected", text: cfg.i18nText("configure.brightness.learned_baseline", "Learned baseline") }),
        cfg.el("span", { class: "proposed", text: cfg.i18nText("configure.brightness.proposed_level", "Proposed level") }),
        cfg.el("small", { text: detail })
      ]),
      cfg.el("div", { class: "autobright-message" + (cfg.autoBrightMessage.indexOf("failed") >= 0 ? " error" : ""), text: cfg.autoBrightMessage })
    ]);
    setTimeout(drawAutoBrightnessChart, 0);
    return panel;
  }

  cfg.ambientLightSourceReady = ambientLightSourceReady;
  cfg.ambientLightSourceConfigured = ambientLightSourceConfigured;
  cfg.ambientSourcePlaceholder = ambientSourcePlaceholder;
  cfg.beginAutoBrightnessSourceTransition = beginAutoBrightnessSourceTransition;
  cfg.loadAutoBrightnessData = loadAutoBrightnessData;
  cfg.queueAutoBrightnessHistory = queueAutoBrightnessHistory;
  cfg.autoBrightnessPanel = autoBrightnessPanel;
})(window.ConfigurePage);
