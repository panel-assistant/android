// Configure field controls and entity pickers.
(function (cfg) {
  "use strict";


  // One editable entity picker owns its catalog query, body-level listbox and all listeners/timers.
  // Callers supply only the source URL, presentation text and selected-value callback.
  function createEntityPicker(options) {
    var listId = options.listId;
    var input = cfg.el("input", {
      type: "text", value: options.value == null ? "" : options.value, class: "ha-entity-input", role: "combobox",
      "aria-label": options.label, "aria-autocomplete": "list", "aria-expanded": "false",
      "aria-controls": listId, "aria-haspopup": "listbox",
      placeholder: options.placeholder, autocomplete: "off", maxlength: options.maxLength || 255
    });
    var list = cfg.el("div", { id: listId, class: "ha-entity-listbox", role: "listbox" });
    list.hidden = true;
    var note = cfg.el("small", { class: "picker-note", text: options.messages.initial });
    var picker = cfg.el("div", { class: "ha-entity-picker" }, [input, note]);
    var sourcePolls = 0;
    var sourceItems = options.items || [];
    var sourceRequest = 0, sourceTimer = null;
    var activeIndex = -1;
    var renderedItems = [];
    var disposed = false;

    // The listbox is a fixed body-level portal: no card overflow or transformed ancestor can move it,
    // while it still inherits the page theme and remains part of the input's ARIA relationship.
    document.body.appendChild(list);

    function viewportSize() {
      var visual = window.visualViewport;
      var left = visual && visual.offsetLeft || 0;
      var top = visual && visual.offsetTop || 0;
      var width = visual && visual.width || window.innerWidth || document.documentElement.clientWidth;
      var height = visual && visual.height || window.innerHeight || document.documentElement.clientHeight;
      return {
        left: left, top: top, width: width, height: height,
        right: left + width, bottom: top + height
      };
    }
    function positionList() {
      if (disposed || list.hidden) return;
      var rect = input.getBoundingClientRect();
      var viewport = viewportSize();
      var edge = 8, gap = 4, preferredHeight = 280, preferredWidth = 520;
      var viewportCapacity = Math.max(0, viewport.width - edge * 2);
      var minimumWidth = Math.min(Math.max(rect.width, 240), viewportCapacity);
      var desiredWidth = Math.min(Math.max(rect.width, preferredWidth), viewportCapacity);
      // Stay aligned with the input when its right-hand side has room, widening only into that space.
      // On a narrow/right-edge layout, shift left only as much as needed to preserve the old minimum.
      var left = Math.max(viewport.left + edge, Math.min(rect.left, viewport.right - minimumWidth - edge));
      var width = Math.min(desiredWidth, Math.max(0, viewport.right - edge - left));
      if (width < minimumWidth) {
        width = minimumWidth;
        left = Math.max(viewport.left + edge, viewport.right - edge - width);
      }
      var below = Math.max(0, viewport.bottom - rect.bottom - gap - edge);
      var above = Math.max(0, rect.top - viewport.top - gap - edge);
      var opensAbove = below < 160 && above > below;
      var available = opensAbove ? above : below;
      var maxHeight = Math.min(preferredHeight, Math.max(72, available), Math.max(0, viewport.height - edge * 2));
      var desiredTop = opensAbove ? rect.top - gap - maxHeight : rect.bottom + gap;
      list.style.width = width + "px";
      list.style.maxHeight = maxHeight + "px";
      list.style.left = left + "px";
      list.style.top = Math.max(viewport.top + edge, Math.min(desiredTop, viewport.bottom - edge - maxHeight)) + "px";
    }
    function setActive(index) {
      if (!renderedItems.length) index = -1;
      else if (index < 0) index = renderedItems.length - 1;
      else if (index >= renderedItems.length) index = 0;
      activeIndex = index;
      Array.prototype.forEach.call(list.children, function (option, i) {
        option.classList.toggle("active", i === activeIndex);
        option.setAttribute("aria-selected", i === activeIndex ? "true" : "false");
      });
      if (activeIndex < 0) input.removeAttribute("aria-activedescendant");
      else {
        var active = list.children[activeIndex];
        input.setAttribute("aria-activedescendant", active.id);
        if (active.scrollIntoView) active.scrollIntoView({ block: "nearest" });
      }
    }
    function closeList() {
      list.hidden = true;
      input.setAttribute("aria-expanded", "false");
      input.removeAttribute("aria-activedescendant");
      activeIndex = -1;
    }
    function choose(index) {
      var item = renderedItems[index];
      if (!item) return;
      input.value = item.id;
      options.onChange(item.id);
      closeList();
      input.focus();
    }
    function openList() {
      if (disposed || !renderedItems.length) { closeList(); return; }
      list.hidden = false;
      input.setAttribute("aria-expanded", "true");
      positionList();
    }
    function populate() {
      list.innerHTML = "";
      renderedItems = [];
      activeIndex = -1;
      sourceItems.forEach(function (item) {
        var id = item.entity_id || item.entityId || "";
        if (!id) return;
        var name = item.friendly_name || item.friendlyName || item.name || id;
        var unit = item.unit || item.unit_of_measurement || options.defaultUnit || "";
        var index = renderedItems.length;
        renderedItems.push({ id: id, name: name, unit: unit });
        var option = cfg.el("div", {
          id: listId + "-option-" + index, class: "ha-entity-option", role: "option",
          "aria-selected": "false"
        }, [
          cfg.el("span", { class: "ha-entity-name", text: name }),
          cfg.el("small", { text: id + (unit ? " · " + unit : "") })
        ]);
        option.addEventListener("pointerdown", function (event) {
          // Keep input focus stable until selection completes (covers mouse, pen and touch).
          event.preventDefault();
          choose(index);
        });
        // Assistive technology and old WebViews may emit click without a preceding pointer event.
        option.addEventListener("click", function () {
          if (input.value !== id || !list.hidden) choose(index);
        });
        list.appendChild(option);
      });
      if (sourceItems.length) note.textContent = options.messages.available(sourceItems.length);
      if (document.activeElement === input) openList();
      else closeList();
    }
    function loadSources(query) {
      var request = ++sourceRequest;
      note.textContent = options.messages.loading;
      fetch(options.url(query))
        .then(function (r) { if (!r.ok) throw r.status; return r.json(); })
        .then(function (body) {
          if (request !== sourceRequest) return;
          sourceItems = (body && (body.items || body.candidates)) || [];
          if (options.onItems) options.onItems(sourceItems);
          populate();
          if (!body || body.available === false) {
            note.textContent = options.messages.unavailable;
          } else if (!sourceItems.length) {
            if (body.refreshing === true && sourcePolls < 20) {
              sourcePolls++;
              note.textContent = options.messages.loading;
              setTimeout(function () { if (request === sourceRequest) loadSources(query); }, 500);
            } else {
              note.textContent = options.messages.none;
            }
          }
        })
        .catch(function () {
          if (request !== sourceRequest) return;
          note.textContent = options.messages.failed;
        });
    }
    input.addEventListener("focus", function () {
      if (!sourceItems.length) loadSources(input.value.trim());
      else openList();
    });
    input.addEventListener("input", function () {
      options.onChange(input.value);
      closeList();
      if (sourceTimer) clearTimeout(sourceTimer);
      var query = input.value.trim();
      sourceTimer = setTimeout(function () { loadSources(query); }, query.length >= 2 ? 250 : 500);
    });
    input.addEventListener("keydown", function (event) {
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        if (!renderedItems.length) return;
        event.preventDefault();
        openList();
        setActive(activeIndex + (event.key === "ArrowDown" ? 1 : -1));
      } else if (event.key === "Enter" && !list.hidden && activeIndex >= 0) {
        event.preventDefault(); choose(activeIndex);
      } else if (event.key === "Escape" && !list.hidden) {
        event.preventDefault(); closeList();
      } else if (event.key === "Tab") closeList();
    });
    input.addEventListener("blur", function () {
      setTimeout(function () {
        if (disposed || document.activeElement === input) return;
        closeList();
        if (options.onBlur) options.onBlur();
      }, 0);
    });
    function outsidePointer(event) {
      if (event.target !== input && !list.contains(event.target)) closeList();
    }
    function reposition() { positionList(); }
    document.addEventListener("pointerdown", outsidePointer, true);
    window.addEventListener("resize", reposition);
    window.addEventListener("scroll", reposition, true);
    if (window.visualViewport) {
      window.visualViewport.addEventListener("resize", reposition);
      window.visualViewport.addEventListener("scroll", reposition);
    }
    var cleanup = function () {
      if (disposed) return;
      disposed = true;
      sourceRequest++; // invalidate fetches and catalog-refresh polls owned by this render
      if (sourceTimer) { clearTimeout(sourceTimer); sourceTimer = null; }
      document.removeEventListener("pointerdown", outsidePointer, true);
      window.removeEventListener("resize", reposition);
      window.removeEventListener("scroll", reposition, true);
      if (window.visualViewport) {
        window.visualViewport.removeEventListener("resize", reposition);
        window.visualViewport.removeEventListener("scroll", reposition);
      }
      if (list.parentNode) list.parentNode.removeChild(list);
    };
    populate();
    return { element: picker, cleanup: cleanup };
  }

  // One input control bound to values[f.key]; Save appears only while the form differs from its baseline.
  function control(f) {
    var v = cfg.values[f.key];
    if (f.type === "BOOL") {
      var sourceBlocked = f.key === "auto_brightness" && !cfg.ambientLightSourceReady();
      var prerequisiteBlocked = f.key === "auto_sleep" && cfg.autoSleepUsesHa() && v !== "true" && cfg.autoSleepPrerequisite.eligible !== true;
      var blocked = sourceBlocked || prerequisiteBlocked;
      var t = cfg.el("div", {
        class: "toggle" + (v === "true" && !sourceBlocked ? " on" : "") + (blocked ? " blocked" : ""),
        role: "switch", tabindex: "0", "aria-label": f.label,
        "aria-checked": v === "true" && !sourceBlocked ? "true" : "false", "aria-disabled": blocked ? "true" : "false"
      });
      if (f.key === "auto_sleep") t.setAttribute("aria-describedby", "auto-sleep-prerequisite-status");
      if (sourceBlocked) t.title = cfg.i18nText("configure.brightness.waiting_valid_reading", "Waiting for a valid ambient light reading.");
      function toggleValue() {
        if (f.key === "auto_brightness" && !cfg.ambientLightSourceReady()) return;
        if (f.key === "auto_sleep" && cfg.autoSleepUsesHa() && cfg.values[f.key] !== "true" && cfg.autoSleepPrerequisite.eligible !== true) return;
        v = (cfg.values[f.key] === "true") ? "false" : "true";
        cfg.values[f.key] = v;
        t.classList.toggle("on", v === "true");
        t.setAttribute("aria-checked", v === "true" ? "true" : "false");
        cfg.setDirty(f.key);
        if (f.key === "auto_sleep") cfg.updateAutoSleepPrerequisiteUi();
      }
      t.addEventListener("click", toggleValue);
      t.addEventListener("keydown", function (event) {
        if (event.key !== "Enter" && event.key !== " ") return;
        event.preventDefault();
        toggleValue();
      });
      return t;
    }
    if (f.type === "ENUM") {
      var s = cfg.el("select");
      f.options.forEach(function (o, index) {
        var label = cfg.localizedEnumOption(f, o, index);
        var op = cfg.el("option", { value: o, text: label }); if (o === v) op.selected = true; s.appendChild(op);
      });
      s.addEventListener("change", function () {
        cfg.values[f.key] = s.value; cfg.setDirty(f.key);
        if (f.key === "auto_sleep_source") {
          cfg.autoSleepPrerequisiteRequest++;
          cfg.invalidateAutoSleepData(true);
          var previousPanel = document.getElementById("auto-sleep-status");
          if (previousPanel) previousPanel.remove();
          cfg.render(); cfg.loadAutoSleepPrerequisite();
        }
      });
      return s;
    }
    // Dashboard-app picker: Auto and ha-paneld's built-in renderer remain first. The server adds only
    // installed supported Companion variants; arbitrary launchable apps never become renderer choices.
    if (f.picker === "renderer") {
      var cur = v == null ? "" : v;
      var sel = cfg.el("select", { class: "pkgsel" });
      sel.appendChild(cfg.el("option", { value: "", text: cfg.localizedPlaceholder(f.placeholder || "auto") }));
      var seen = { "": true };
      var KNOWN = [
        { pkg: "builtin", label: cfg.i18nText("configure.renderer.builtin", "Built-in renderer (ha-paneld)") }
      ].concat(cfg.rendererChoices);
      KNOWN.forEach(function (r) {
        if (!r || !r.pkg || seen[r.pkg]) return;
        seen[r.pkg] = true;
        var op = cfg.el("option", { value: r.pkg, text: r.label });
        if (r.pkg === cur) op.selected = true;
        sel.appendChild(op);
      });
      // A currently-set external renderer is preserved so it isn't silently lost.
      if (cur && !seen[cur]) {
        var o2 = cfg.el("option", { value: cur, text: cfg.i18nText("configure.renderer.configured_external", "{package} · configured external renderer", { package: cur }) });
        o2.selected = true; sel.appendChild(o2);
      }
      sel.addEventListener("change", function () {
        cfg.values[f.key] = sel.value;
        cfg.setDirty(f.key);
        // Renderer-owned rows follow the in-flight picker value immediately; saving is not required
        // just to reveal the built-in renderer's controls.
        if (f.key === "dashboard_package") cfg.render();
      });
      return sel;
    }
    // Package picker: a dropdown of installed apps. Blank = "Auto-detect"; a currently-set package that
    // isn't in the list (e.g. since-uninstalled or a manual entry) is kept as its own option.
    if (f.picker === "package") {
      var cur = v == null ? "" : v;
      var sel = cfg.el("select", { class: "pkgsel" });
      var autoLabel = cfg.localizedPlaceholder(f.placeholder || "Auto-detect");
      sel.appendChild(cfg.el("option", { value: "", text: autoLabel }));
      var seen = { "": true };
      cfg.apps.forEach(function (a) {
        seen[a.pkg] = true;
        var op = cfg.el("option", { value: a.pkg, text: a.label + " · " + a.pkg });
        if (a.pkg === cur) op.selected = true;
        sel.appendChild(op);
      });
      if (cur && !seen[cur]) {
        var o2 = cfg.el("option", { value: cur, text: cfg.i18nText("configure.package.not_installed", "{package} · (not installed)", { package: cur }) });
        o2.selected = true; sel.appendChild(o2);
      }
      sel.addEventListener("change", function () { cfg.values[f.key] = sel.value; cfg.setDirty(f.key); });
      return sel;
    }
    // Wake-word picker: a checkbox per wake word the panel holds (the bundled ones and any imported).
    // Degrades to the raw JSON textarea while the list is unavailable. The import has its own row below
    // it (voiceWakeWordImportRow), across the whole card.
    if (f.picker === "voice_wake_words") {
      if (cfg.voiceWakeWordsCatalog === null) cfg.loadVoiceWakeWords();
      var wakeWrap = cfg.el("div", { class: "voice-wake-words-picker" });
      if (!Array.isArray(cfg.voiceWakeWordsCatalog)) {
        var wakeRaw = cfg.el("textarea", { class: "voice-wake-words-raw", rows: "2", text: v == null ? "" : v });
        wakeRaw.addEventListener("input", function () { cfg.values[f.key] = wakeRaw.value; cfg.setDirty(f.key); });
        wakeWrap.appendChild(wakeRaw);
        return wakeWrap;
      }
      var activeWakeWords = [];
      try {
        var parsedActive = JSON.parse(v || "[]");
        if (Array.isArray(parsedActive)) activeWakeWords = parsedActive.filter(function (w) { return typeof w === "string" && w; });
      } catch (e) { activeWakeWords = []; }
      cfg.voiceWakeWordsCatalog.forEach(function (word) {
        var id = word && word.id ? String(word.id) : "";
        if (!id) return;
        var box = cfg.el("input", { type: "checkbox" });
        box.checked = activeWakeWords.indexOf(id) >= 0;
        box.addEventListener("change", function () {
          activeWakeWords = activeWakeWords.filter(function (w) { return w !== id; });
          if (box.checked) activeWakeWords.push(id);
          cfg.values[f.key] = JSON.stringify(activeWakeWords);
          cfg.setDirty(f.key);
          cfg.render();
        });
        wakeWrap.appendChild(cfg.el("label", { class: "voice-wake-word-row", style: "display:block" }, [box, cfg.el("span", { text: word.wake_word ? String(word.wake_word) : id })]));
      });
      return wakeWrap;
    }
    // Wake-word-pipeline picker: one native select per configured wake word (from voice_wake_words),
    // offering the Home Assistant Assist pipelines fetched from /api/v1/voice/pipelines. Degrades to the
    // raw JSON textarea while that endpoint hasn't answered yet, or answered 503 (no Home Assistant
    // connection, or the voice-coordinator lane not wired up yet) — Safari-first: plain fetch/select/
    // textarea, no picker library.
    if (f.picker === "voice_pipelines") {
      if (cfg.voicePipelinesCatalog === null) cfg.loadVoicePipelines();
      if (cfg.voiceWakeWordsCatalog === null) cfg.loadVoiceWakeWords();
      var pipelinesWrap = cfg.el("div", { class: "voice-pipelines-picker" });
      // The raw textarea is focused/mid-edit exactly when it is document.activeElement — checked
      // against THIS render's about-to-be-replaced node, before reconcileConfigCards swaps it out.
      // A catalogue fetch resolving while someone is typing must not rip the control out from under
      // them: keep rendering the raw-textarea view for this pass even though the catalogue is now
      // available, so nothing they typed (committed to `values` or not) or their cursor position is
      // lost. The next render — the following keystroke's `input` event, or a blur — re-evaluates and
      // switches to the picker once it is safe to.
      var activeRawTextarea = document.activeElement &&
        document.activeElement.classList && document.activeElement.classList.contains("voice-pipelines-raw") &&
        document.activeElement.getAttribute("data-field-key") === f.key ? document.activeElement : null;
      if (cfg.voicePipelinesCatalog === null || cfg.voicePipelinesCatalog === false || activeRawTextarea) {
        var pipelinesRaw = cfg.el("textarea", {
          class: "voice-pipelines-raw", rows: "2", "data-field-key": f.key, text: v == null ? "" : v,
        });
        // `input`, not `change`: `change` only fires on blur, so a catalogue response landing mid-
        // keystroke previously re-rendered with whatever was last blurred, discarding anything typed
        // since. Committing on every keystroke means `values[f.key]` is always current, so nothing
        // typed is ever lost even if the very next render switches this field to the select picker.
        pipelinesRaw.addEventListener("input", function () {
          cfg.values[f.key] = pipelinesRaw.value; cfg.setDirty(f.key);
        });
        pipelinesWrap.appendChild(pipelinesRaw);
        if (cfg.voicePipelinesCatalog === false) {
          pipelinesWrap.appendChild(cfg.el("small", { text: cfg.i18nText("configure.voice.pipeline_unavailable", "Pipeline list unavailable — edit as JSON.") }));
        }
        return pipelinesWrap;
      }
      var configuredWakeWords = [];
      try {
        var parsedWakeWords = JSON.parse(cfg.values.voice_wake_words || "[]");
        if (Array.isArray(parsedWakeWords)) {
          configuredWakeWords = parsedWakeWords.filter(function (w) { return typeof w === "string" && w; });
        }
      } catch (e) { configuredWakeWords = []; }
      if (!configuredWakeWords.length) {
        pipelinesWrap.appendChild(cfg.el("small", { text: cfg.i18nText("configure.voice.configure_wake_word_first", "Configure a wake word above first.") }));
        return pipelinesWrap;
      }
      var pipelineMapping = {};
      try {
        var parsedMapping = JSON.parse(v || "{}");
        if (parsedMapping && typeof parsedMapping === "object" && !Array.isArray(parsedMapping)) {
          pipelineMapping = parsedMapping;
        }
      } catch (e) { pipelineMapping = {}; }
      configuredWakeWords.forEach(function (word) {
        var pipelineRow = cfg.el("div", { class: "voice-pipeline-row", style: "display:flex;align-items:center;gap:8px;margin-bottom:6px" });
        var known = Array.isArray(cfg.voiceWakeWordsCatalog) && cfg.voiceWakeWordsCatalog.filter(function (w) { return w && w.id === word; })[0];
        pipelineRow.appendChild(cfg.el("span", { class: "voice-pipeline-label", style: "flex:0 0 auto", text: known && known.wake_word ? String(known.wake_word) : word }));
        var pipelineSelect = cfg.el("select", { style: "flex:1 1 auto;min-width:0;max-width:100%" });
        pipelineSelect.appendChild(cfg.el("option", { value: "", text: cfg.i18nText("configure.voice.preferred_pipeline", "Preferred pipeline") }));
        var retainedPipelineId = pipelineMapping[word];
        var matchedRetained = false;
        cfg.voicePipelinesCatalog.forEach(function (p) {
          var pid = p && p.id ? String(p.id) : "";
          if (!pid) return;
          var pname = p && p.name ? String(p.name) : pid;
          var pipelineOption = cfg.el("option", { value: pid, text: pname });
          if (retainedPipelineId === pid) { pipelineOption.selected = true; matchedRetained = true; }
          pipelineSelect.appendChild(pipelineOption);
        });
        // A retained id Home Assistant no longer offers (the pipeline was removed/renamed there) must
        // never render as if nothing were selected — that reads as "Preferred pipeline" (unset) while
        // silently keeping the stale id. Represent it honestly, mirroring the renderer/package pickers'
        // "configured external renderer"/"(not installed)" retained-value pattern, and it stays
        // clearable through the existing empty "Preferred pipeline" option.
        if (retainedPipelineId && !matchedRetained) {
          var unknownOption = cfg.el("option", {
            value: String(retainedPipelineId), text: cfg.i18nText("configure.voice.pipeline_not_listed", "{pipeline} · not in Home Assistant's list", { pipeline: retainedPipelineId }),
          });
          unknownOption.selected = true;
          pipelineSelect.appendChild(unknownOption);
        }
        pipelineSelect.addEventListener("change", function () {
          if (pipelineSelect.value) pipelineMapping[word] = pipelineSelect.value;
          else delete pipelineMapping[word];
          cfg.values[f.key] = JSON.stringify(pipelineMapping);
          cfg.setDirty(f.key);
        });
        pipelineRow.appendChild(pipelineSelect);
        pipelinesWrap.appendChild(pipelineRow);
      });
      return pipelinesWrap;
    }
    // Home dashboard picker: Home Assistant provides this signed-in user's dashboards in its own order.
    // Deliberately a NATIVE select. A custom popup was tried (to carry the dashboards' icons like HA's
    // own picker) and was a bust on hardware review: it escaped the card, ran off the viewport and stole
    // wheel scrolling — the browser's own popup gets all of that right on every platform, and the
    // compact form deliberately favours clean rows over icons. The wizard's dedicated page keeps the icon
    // list; this form keeps HA's GROUPING via native optgroups, which is the part that carries real information.
    // Auto intentionally remains first; a legacy/custom configured path is preserved rather than silently lost.
    // "Custom…" reveals a plain text input UNDER the select, which is how a specific view below a
    // dashboard root (/alpha/beta) is entered — Home Assistant's list endpoint only ever
    // returns roots. A revealed input, rather than an editable combobox, is what keeps the earlier
    // hardware verdict intact: nothing floats, nothing escapes the card, the native popup still owns
    // the list. A configured path that is not in the list now lands here instead of in a dead-end
    // option, so it can finally be edited on the panel.
    if (f.picker === "ha_dashboard") {
      var currentDashboard = v == null ? "" : v;
      var dashboardSelect = cfg.el("select", { class: "pkgsel" });
      var autoText = !cfg.homeDashboardQueried ? cfg.i18nText("configure.dashboard.auto_list_unavailable", "Auto — dashboard list unavailable")
        : (cfg.homeDashboardDefault.explicit
          ? cfg.i18nText("configure.dashboard.auto_account_default", "Auto — follow this account’s default")
          : cfg.i18nText("configure.dashboard.auto_no_default", "Auto — no default set for this account"));
      var autoOption = cfg.el("option", { value: "", text: autoText });
      dashboardSelect.appendChild(autoOption);
      var dashboardPaths = { "": true };
      var groupHosts = {};
      ["panel", "dashboard"].forEach(function (group) {
        var members = cfg.homeDashboardItems.filter(function (d) { return (d.group || "dashboard") === group; });
        if (!members.length) return;
        var host = cfg.el("optgroup", { label: group === "panel"
          ? cfg.i18nText("configure.dashboard.group_ha", "Home Assistant dashboards")
          : cfg.i18nText("configure.dashboard.group_yours", "Your dashboards") });
        groupHosts[group] = host;
        dashboardSelect.appendChild(host);
        members.forEach(function (dashboard) {
          var path = String(dashboard && dashboard.path || "").trim();
          if (!path || dashboardPaths[path]) return;
          dashboardPaths[path] = true;
          var title = String(dashboard.title || "").trim() || path;
          var option = cfg.el("option", { value: path, text: title + " · " + path });
          if (path === currentDashboard) option.selected = true;
          host.appendChild(option);
        });
      });
      var customActive = !!currentDashboard && !dashboardPaths[currentDashboard];
      dashboardSelect.appendChild(cfg.el("option", { value: cfg.CUSTOM_DASHBOARD, text: cfg.i18nText("configure.dashboard.custom", "Custom — enter a dashboard path…") }));
      if (customActive) dashboardSelect.value = cfg.CUSTOM_DASHBOARD;
      var customInput = cfg.el("input", {
        type: "text", class: "hd-custom-input", value: currentDashboard,
        placeholder: "/dashboard-name/tab-name", id: "cfg-home_dashboard-path",
        maxlength: f.maxLength || 2048, "aria-label": cfg.i18nText("configure.dashboard.path_label", "Dashboard path"),
        "aria-describedby": "cfg-home_dashboard-path-note",
      });
      // The note explains what the value will DO (including the fallback warning), so it is wired to the
      // input for assistive technology and announced politely as it changes rather than only on focus.
      var customNote = cfg.el("small", { class: "hd-area-note hd-custom-note",
        id: "cfg-home_dashboard-path-note", role: "status", "aria-live": "polite" });
      var customWrap = cfg.el("div", { class: "hd-custom" }, [customInput, customNote]);
      // The renderer resolves an explicit path against the dashboards this account can see and falls
      // back to its default when the ROOT is not one of them — silently, by design. Once a path can be
      // typed that silence becomes the likeliest failure, so say it here instead. It stays a warning
      // and never blocks the save: the list may be unfetched, and the dashboard may not exist yet.
      function refreshCustomNote() {
        var typedPath = customInput.value.trim();
        var root = cfg.dashboardRootOf(typedPath);
        var unknown = root && cfg.homeDashboardQueried && cfg.homeDashboardItems.length && !dashboardPaths[root];
        customNote.textContent = unknown
          ? cfg.i18nText("configure.dashboard.path_not_visible", "{path} is not a dashboard this panel’s Home Assistant account can see — the panel will fall back to its default until that dashboard exists.", { path: root })
          : cfg.i18nText("configure.dashboard.path_help", "A path on this Home Assistant, starting with a dashboard from the list above.");
        customNote.classList.toggle("warn", !!unknown);
      }
      // Validity is decided here rather than by the browser's pattern engine, and is cleared entirely
      // whenever Custom is not the live control. A hidden control that stays invalid blocks Save with
      // nothing on screen to fix — reportValidity() cannot show anything on an invisible field — so an
      // abandoned malformed path would make every later Auto or listed save fail for no visible reason.
      function validateCustom() {
        var typedPath = customInput.value.trim();
        customInput.setCustomValidity(
          !typedPath || cfg.wellFormedDashboardPath(typedPath) ? ""
            : cfg.i18nText("configure.dashboard.path_invalid", "Enter a dashboard path such as /dashboard-name/tab-name."),
        );
      }
      function syncCustom() {
        var on = dashboardSelect.value === cfg.CUSTOM_DASHBOARD;
        customWrap.hidden = !on;
        // Required only while it is the live control, so an empty box cannot be saved as a silent Auto.
        customInput.required = on;
        // Disabled when it is not: barred from constraint validation, and skipped by the row scan.
        customInput.disabled = !on;
        if (on) { validateCustom(); refreshCustomNote(); }
      }
      dashboardSelect.addEventListener("change", function () {
        syncCustom();
        cfg.values[f.key] = dashboardSelect.value === cfg.CUSTOM_DASHBOARD ? customInput.value.trim() : dashboardSelect.value;
        cfg.setDirty(f.key);
        if (dashboardSelect.value === cfg.CUSTOM_DASHBOARD) customInput.focus();
      });
      customInput.addEventListener("input", function () {
        cfg.values[f.key] = customInput.value.trim();
        cfg.setDirty(f.key);
        validateCustom();
        refreshCustomNote();
      });
      syncCustom();
      var notes = [];
      if (!cfg.homeDashboardQueried) {
        notes.push(cfg.el("small", { class: "hd-area-note", text:
          cfg.i18nText("configure.dashboard.fetch_failed", "Couldn’t fetch this account’s dashboard list from Home Assistant yet. Try again after the connection recovers.") }));
      } else if (!cfg.homeDashboardItems.length) {
        notes.push(cfg.el("small", { class: "hd-area-note", text:
          cfg.i18nText("configure.dashboard.none_accessible", "This account cannot access any dashboards. Create one or grant access in Home Assistant.") }));
      } else if (!cfg.homeDashboardDefault.explicit && !currentDashboard) {
        // The demotion rule, in native terms: Auto still exists but the field says why picking a real
        // dashboard is the recommendation when the account carries no server-side default.
        notes.push(cfg.el("small", { class: "hd-area-note", text:
          cfg.i18nText("configure.dashboard.no_default", "This account has no default dashboard set — pick the dashboard this panel should show.") }));
      }
      // The select is never disabled now, even with no listed dashboards: Custom is still a legal
      // answer then, and it is exactly the case where someone needs to type a path by hand.
      return cfg.el("div", { class: "hd-picker" }, [dashboardSelect, customWrap].concat(notes));
    }
    // Home Assistant Area: a pop-up list of HA's REAL areas, never free text — nobody knows what to type,
    // the names live in HA. The stored value is the panel's REQUEST; Home Assistant's own value is
    // canonical, so this control shows what HA says and the note explains when an edit cannot move an
    // already-registered device (admin-only in HA).
    if (f.picker === "ha_area") {
      // Populated EAGERLY, when the field renders. It used to load on focus/pointerdown, which appended
      // options into a native dropdown the user had just opened — the picker re-laid-out on every insert
      // and visibly flickered before settling (hardware report). Nothing may mutate an open picker.
      var areaCurrent = v == null ? "" : v;
      var areaWrap = cfg.el("div", { class: "ha-area-picker" });
      var areaSelect = cfg.el("select", { class: "pkgsel", "aria-label": f.label });
      areaSelect.appendChild(cfg.el("option", { value: "", text: cfg.i18nText("configure.area.none", "No area") }));
      if (areaCurrent) {
        var cur = cfg.el("option", { value: areaCurrent, text: areaCurrent });
        cur.selected = true;
        areaSelect.appendChild(cur);
      }
      var areaNote = cfg.el("small", { class: "hd-area-note", hidden: "hidden" });
      var areaTouched = false;
      var areaHa = "";
      var areaQueried = false;
      var areaAdmin = null;
      // The note describes the VALUE, not permissions. Shown for every non-admin session it told a panel
      // whose value MATCHED Home Assistant that it had been overridden locally — untrue, and alarming on a
      // panel that had just converged correctly. It may appear only
      // while the local request genuinely differs from what Home Assistant holds, and it must stay honest
      // after an edit, so it is recomputed rather than decided once.
      function syncAreaNote() {
        var localArea = cfg.values[f.key] == null ? "" : String(cfg.values[f.key]);
        if (areaQueried && cfg.haAreaUserOverride && localArea !== areaHa) {
          // Name what Home Assistant actually holds so a local override remains distinguishable from
          // an adopted value at a glance.
          areaNote.textContent = areaHa
            ? cfg.i18nText("configure.area.local_override_ha", "Local override only — Home Assistant has “{area}”", { area: areaHa })
            : cfg.i18nText("configure.area.local_override", "Local override only");
          areaNote.removeAttribute("hidden");
        } else {
          areaNote.setAttribute("hidden", "hidden");
        }
      }
      // ONE insertion, never one per area: appending options individually re-lays-out the control on every
      // append. And when the catalog is already known this runs BEFORE the node is attached, so returning to
      // the Config tab costs no layout change at all — the picker is complete the moment it appears.
      function fillAreaOptions(a) {
        var have = {};
        for (var i = 0; i < areaSelect.options.length; i++) have[areaSelect.options[i].value] = true;
        var areaFrag = document.createDocumentFragment();
        (a && a.areas || []).forEach(function (area) {
          if (!area || !area.name || have[area.name]) return;
          have[area.name] = true;
          areaFrag.appendChild(cfg.el("option", { value: area.name, text: area.name }));
        });
        // Home Assistant is canonical: where it holds an area for this device, that is what this control
        // reads, even if the local request has never been set. A panel whose HA device sits in Office must
        // never present itself as having no area — reported on an upgraded panel whose local value was blank.
        var haArea = a && a.device && a.device.found ? a.device.area_name : "";
        if (haArea && !have[haArea]) {
          have[haArea] = true;
          areaFrag.appendChild(cfg.el("option", { value: haArea, text: haArea }));
        }
        areaSelect.appendChild(areaFrag); // the single layout-affecting mutation
        if (!areaTouched && !cfg.haAreaUserOverride && haArea && areaSelect.value !== haArea) {
          areaSelect.value = haArea;
          // Adopting HA's value is not a pending edit — the server already treats HA as the source of
          // truth — so this must not mark the form dirty and invite a redundant save.
          cfg.values[f.key] = haArea;
        }
        areaHa = haArea;
        areaQueried = !!(a && a.queried);
        areaAdmin = a && typeof a.admin === "boolean" ? a.admin : null;
        syncAreaNote();
      }
      var areaSeedGeneration = cfg.haAreaSeedGeneration;
      var areaRequest = ++cfg.haAreaCatalogRequest;
      if (cfg.haAreaSeed) fillAreaOptions(cfg.haAreaSeed);
      // The panel endpoint owns a short, owner-keyed cache. Always ask it so failed queries and config,
      // credential or identity changes can recover without a full browser reload.
      fetch("api/v1/config/ha-area", { cache: "no-store" }).then(function (r) { return r.json(); }).then(function (a) {
        if (areaSeedGeneration !== cfg.haAreaSeedGeneration || areaRequest !== cfg.haAreaCatalogRequest) return;
        if (a && a.queried) cfg.haAreaSeed = a;
        fillAreaOptions(a);
      }).catch(function () {});
      areaSelect.addEventListener("change", function () {
        areaTouched = true;
        cfg.values[f.key] = areaSelect.value;
        cfg.setDirty(f.key);
        syncAreaNote();
      });
      areaWrap.appendChild(areaSelect);
      areaWrap.appendChild(areaNote);
      return areaWrap;
    }
    // Home Assistant illuminance source: free-form ids remain valid while HA is unavailable.
    if (f.picker === "ha_illuminance") {
      var sourceReadyAtRender = cfg.ambientLightSourceReady();
      var entityPicker = createEntityPicker({
        value: v, label: f.label, maxLength: f.maxLength, listId: "ha-illuminance-listbox",
        placeholder: cfg.ambientSourcePlaceholder(), defaultUnit: "lx", items: cfg.haSourceItems,
        url: function (query) { return "api/v1/auto-brightness/sources?q=" + encodeURIComponent(query || "") + "&limit=200"; },
        messages: {
          initial: cfg.i18nText("configure.brightness.focus_to_load", "Focus to load Home Assistant illuminance sensors."),
          loading: cfg.i18nText("configure.brightness.sources_loading", "Loading Home Assistant illuminance sensors…"),
          available: function (count) { return cfg.i18nText("configure.brightness.sources_available", "{count} illuminance source(s) available. Blank uses the panel sensor.", { count: count }); },
          unavailable: cfg.i18nText("configure.brightness.sources_unavailable", "Home Assistant sources are unavailable; an exact sensor entity id can still be entered."),
          none: cfg.i18nText("configure.brightness.sources_none", "No Home Assistant illuminance sensors found; an exact sensor entity id can still be entered."),
          failed: cfg.i18nText("configure.brightness.sources_load_failed", "Could not load Home Assistant sources; an exact sensor entity id can still be entered.")
        },
        onItems: function (items) { cfg.haSourceItems = items; },
        onChange: function (value) { cfg.values[f.key] = value; cfg.setDirty(f.key); },
        onBlur: function () { if (sourceReadyAtRender !== cfg.ambientLightSourceReady()) cfg.render(); }
      });
      cfg.haPickerCleanups.push(entityPicker.cleanup);
      return entityPicker.element;
    }
    var type = f.type === "PASSWORD" ? "password" : (f.type === "INT" || f.type === "FLOAT") ? "number" : "text";
    var inp = cfg.el("input", { type: type, value: f.secret && !cfg.dirtyValues[f.key] ? "" : (v == null ? "" : v) });
    if (f.secret) inp.placeholder = cfg.i18nText("configure.secret.blank_keeps_current", "blank keeps current");
    else if (f.placeholder) inp.placeholder = f.placeholder;   // e.g. "auto (io.homeassistant…)" on package fields
    if (f.min != null) inp.min = f.min;
    if (f.max != null) inp.max = f.max;
    if (f.maxLength != null) inp.maxLength = f.maxLength;
    if (f.step != null) inp.step = f.step;
    if (f.type === "FLOAT" && f.step == null) inp.step = "any";
    if ((f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_maximum_percent" || f.key === "auto_brightness_response_percent") && !cfg.ambientLightSourceReady()) {
      inp.disabled = true;
      inp.title = cfg.i18nText("configure.brightness.select_source_first", "Select an ambient light source first.");
    }
    inp.addEventListener("input", function () {
      cfg.values[f.key] = inp.value; cfg.setDirty(f.key);
      if (f.key === "auto_sleep_touch_delay_seconds") cfg.updateAutoSleepSummary();
      if (f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_maximum_percent" || f.key === "auto_brightness_response_percent") cfg.queueAutoBrightnessHistory();
    });
    // A whole-number setting rounds a typed fraction (96.5 → 97) rather than refusing it.
    if (f.type === "INT") inp.addEventListener("change", function () { cfg.roundWholeNumber(f, inp); });
    return inp;
  }

  // Link / broken-link icons (Lucide link + unlink — a complementary pair; currentColor so CSS tints them).
  var SVG_ATTRS = 'viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"';
  var ICON_LINK = '<svg ' + SVG_ATTRS + '><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/></svg>';
  var ICON_UNLINK = '<svg ' + SVG_ATTRS + '><path d="M18.84 12.25l1.72-1.71a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M5.17 11.75l-1.71 1.71a5 5 0 0 0 7.07 7.07l1.71-1.71"/><line x1="8" y1="2" x2="8" y2="5"/><line x1="2" y1="8" x2="5" y2="8"/><line x1="16" y1="19" x2="16" y2="22"/><line x1="19" y1="16" x2="22" y2="16"/></svg>';

  // Expose-to-HA toggle (only on settings that are HA entities): an icon button, no checkbox —
  // a link icon = exposed as an HA entity (highlighted), a broken-link icon = hidden. Click toggles it.
  function pip(f) {
    if (!f.ha) return null;
    var on = cfg.expose[f.key] !== false;
    var btn = cfg.el("button", { class: "pip", type: "button" });
    function render() {
      btn.classList.toggle("on", on);
      btn.innerHTML = on ? ICON_LINK : ICON_UNLINK;
      btn.title = on ? cfg.i18nText("configure.exposure.hide_title", "Exposed to Home Assistant — click to hide")
                     : cfg.i18nText("configure.exposure.expose_title", "Hidden from Home Assistant — click to expose");
      btn.setAttribute("aria-label", on ? cfg.i18nText("configure.exposure.exposed", "Exposed to Home Assistant") : cfg.i18nText("configure.exposure.hidden", "Hidden from Home Assistant"));
      btn.setAttribute("aria-pressed", on ? "true" : "false");
    }
    btn.addEventListener("click", function () { on = !on; cfg.expose[f.key] = on; render(); cfg.setDirty(f.key, true); });
    render();
    return btn;
  }

  cfg.control = control;
  cfg.pip = pip;
})(window.ConfigurePage);
