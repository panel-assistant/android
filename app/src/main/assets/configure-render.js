// Configure schema rows and card-wall rendering.
(function (cfg) {
  "use strict";


  function row(f) {
    var summary = f.summary ? cfg.el("small", { class: "fsum", lang: f.summaryLanguage, text: f.summary }) : null;
    var companionRendererHint = f.key === "dashboard_package" && cfg.rendererChoices.some(function (renderer) {
      return renderer && renderer.pkg === cfg.values.dashboard_package;
    }) ? cfg.el("small", {
      class: "companion-renderer-hint",
      text: cfg.i18nText("configure.renderer.companion_launcher_hint", "Turn on the Companion app's launcher option for it to take Home.")
    }) : null;
    var protectedSetting = !!cfg.HARDENED_APPROVAL_SETTING_KEYS[f.key];
    var isAdvanced = cfg.advancedField(f);
    var labelText = cfg.el("span", { lang: f.labelLanguage });
    // The last word of the label, the hardened shield, the advanced diamond and the help button share
    // one non-wrapping run, so a wrapped label takes its last word to the new line with them and the
    // button never sits on a line by itself. The shield is a pseudo-element, so a non-breaking space
    // alone would not bind it in older WebViews.
    var words = f.label.trim().split(/\s+/);
    if (words.length > 1) labelText.appendChild(document.createTextNode(words.slice(0, -1).join(" ") + " "));
    var lastWord = cfg.el("span", { class: protectedSetting ? "hardened-label-tail" : "flabel-word", text: words[words.length - 1] || f.label });
    if (protectedSetting) {
      lastWord.setAttribute("data-hardened-approval", "conditional");
      lastWord.setAttribute("aria-describedby", "hardened-approval-conditional-description");
      lastWord.setAttribute("title", cfg.i18nText("configure.hardened.setting_approval", "Changing this setting may require physical on-panel approval when Hardened mode is enabled."));
    }
    var advancedMark = isAdvanced ? cfg.el("span", {
      class: "adv-mark", role: "img", text: "\u25C6",
      title: cfg.i18nText("configure.advanced.marker", "Advanced setting"),
      "aria-label": cfg.i18nText("configure.advanced.marker", "Advanced setting")
    }) : null;
    labelText.appendChild(cfg.el("span", { class: "flabel-tail" }, [lastWord, advancedMark, cfg.helpButton(f)]));
    var label = cfg.el("div", { class: "flabel" }, [
      labelText,
      summary,
      companionRendererHint,
      Object.prototype.hasOwnProperty.call(cfg.applyPending, f.key) ?
        cfg.el("small", { class: "apply-pending-status", text: cfg.applyPendingStatusText(f.key) }) : null,
    ]);
    // Read-only rows (diagnostic sensors) have no editable value — just the expose-to-HA pip.
    var valueControl = f.readOnly ? null : cfg.control(f);
    if (valueControl) {
      if (!valueControl.getAttribute("aria-label")) valueControl.setAttribute("aria-label", f.label);
      valueControl.setAttribute("lang", f.labelLanguage);
      if (protectedSetting) {
        valueControl.setAttribute("aria-describedby", "hardened-approval-conditional-description");
        valueControl.setAttribute("title", cfg.i18nText("configure.hardened.setting_approval", "Changing this setting may require physical on-panel approval when Hardened mode is enabled."));
      }
    }
    var ctl = cfg.el("div", { class: "fctl" }, f.readOnly ? [cfg.pip(f)] : [cfg.pip(f), valueControl]);
    // Anchor id so dashboard "edit" icons can deep-link straight to this setting.
    // A switch or a lone exposure icon fits beside the label even on a phone.
    var compact = !valueControl || valueControl.classList.contains("toggle");
    var dependencyDisabled = (f.key === "auto_brightness" || f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_maximum_percent" || f.key === "auto_brightness_response_percent") && !cfg.ambientLightSourceReady();
    return cfg.el("div", {
      class: "frow" + (compact ? " frow-compact" : "") + (isAdvanced ? " adv" : "") + (f.available ? "" : " muted") + (dependencyDisabled ? " dependency-disabled" : ""),
      id: "cfg-" + f.key
    }, [label, ctl]);
  }

  function shouldRenderRow(f) {
    if ((f.key === "auto_brightness_minimum_percent" || f.key === "auto_brightness_maximum_percent" || f.key === "auto_brightness_response_percent") && cfg.values.auto_brightness !== "true") return false;
    return true;
  }
  var CARD_BADGES = {
    "Voice": ["preview", "preview"]
  };
  var CARD_NOTES = {
    "Sensors": "Home Assistant reporting",
    "Diagnostics": "Home Assistant reporting"
  };
  function groupTitle(group) {
    var titles = {
      "Identity": cfg.i18nText("configure.group.identity", "Identity"),
      "MQTT": "MQTT",
      "Behaviour": cfg.i18nText("configure.group.behaviour", "Behaviour"),
      "Auto-sleep": cfg.i18nText("configure.group.auto_sleep", "Auto-sleep"),
      "Display": cfg.i18nText("configure.group.display", "Display"),
      "Camera": cfg.i18nText("configure.group.camera", "Camera"),
      "System": cfg.i18nText("configure.group.system", "System"),
      "Sensors": cfg.i18nText("configure.group.sensors", "Sensors"),
      "Diagnostics": cfg.i18nText("configure.group.diagnostics", "Diagnostics"),
      "Logging": cfg.i18nText("configure.group.logging", "Logging"),
      "Voice": cfg.i18nText("configure.group.voice", "Voice"),
      "Home Assistant connection": cfg.i18nText("configure.group.ha_connection", "Home Assistant connection"),
      "Dashboard": cfg.i18nText("configure.group.dashboard", "Dashboard"),
      "Built-in renderer": cfg.i18nText("configure.group.builtin_renderer", "Built-in renderer")
    };
    return Object.prototype.hasOwnProperty.call(titles, group) ? titles[group] : group;
  }
  var BUILTIN_RENDERER_KEYS = {
    dashboard_entity_learning: true, dashboard_fullscreen: true, dashboard_native_kiosk: true,
    dashboard_idle_return_min: true, dashboard_zoom: true, dashboard_theme: true
  };
  var BUILTIN_RENDERER_ONLY_KEYS = { dashboard_idle_return_min: true };
  var HA_CONNECTION_KEYS = { ha_url: true, ha_token: true };
  var AUTO_SLEEP_KEYS = { auto_sleep_source: true, auto_sleep_touch_delay_seconds: true, auto_sleep: true };
  var CONFIG_LAYOUT_KEYS = {
    "Identity": "configure-identity", "MQTT": "configure-mqtt", "Behaviour": "configure-behaviour",
    "Auto-sleep": "configure-auto-sleep",
    "Display": "configure-display", "System": "configure-system", "Sensors": "configure-sensors",
    "Diagnostics": "configure-diagnostics", "Logging": "configure-logging",
    "Home Assistant connection": "configure-ha-connection", "Dashboard": "configure-dashboard",
    "Built-in renderer": "configure-builtin-renderer"
  };
  function configLayoutKey(group) {
    return CONFIG_LAYOUT_KEYS[group] || ("configure-" + String(group).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 50));
  }
  function presentationGroup(f) {
    if (AUTO_SLEEP_KEYS[f.key]) return "Auto-sleep";
    if (f.group !== "Dashboard") return f.group;
    if (BUILTIN_RENDERER_KEYS[f.key]) return "Built-in renderer";
    if (HA_CONNECTION_KEYS[f.key]) return "Home Assistant connection";
    return "Dashboard";
  }

  var scheduleConfigColumnAlignment = window.CardColumnAlignment
    ? window.CardColumnAlignment.attach("cfg-groups")
    : function () {};

  function fieldsForConfigGroup(group) {
    return cfg.schema.filter(function (field) {
      return presentationGroup(field) === group && field.available &&
        (!BUILTIN_RENDERER_ONLY_KEYS[field.key] || !cfg.values.dashboard_package || cfg.values.dashboard_package === "builtin");
    });
  }

  function autoSleepCardSignature(fields) {
    return JSON.stringify([
      cfg.advanced,
      cfg.revealedGroups["Auto-sleep"] === true,
      cfg.filterText,
      cfg.values.auto_sleep === "true",
      fields.map(function (field) {
        return [
          field.key,
          field.label,
          field.help,
          field.summary,
          field.tier,
          field.type,
          field.picker,
          field.readOnly,
          field.min,
          field.max,
          field.maxLength,
          field.options,
          shouldRenderRow(field),
          cfg.values[field.key],
          field.ha ? cfg.expose[field.key] !== false : null,
          Object.prototype.hasOwnProperty.call(cfg.applyPending, field.key),
          cfg.applyStalled[field.key] === true
        ];
      })
    ]);
  }

  function syncAutoSleepCardSignature() {
    var card = document.querySelector('[data-config-group="Auto-sleep"]');
    if (card) card.setAttribute("data-render-signature", autoSleepCardSignature(fieldsForConfigGroup("Auto-sleep")));
  }

  function reconcileConfigCards(root, cards) {
    Array.prototype.slice.call(root.children).forEach(function (child) {
      if (cards.indexOf(child) < 0) child.remove();
    });
    cards.forEach(function (card, index) {
      var current = root.children[index] || null;
      if (current !== card) root.insertBefore(card, current);
    });
  }

  function render() {
    var root = document.getElementById("cfg-groups");
    var proximityCard = document.querySelector("#cfg-proximity-learning");
    var groups = [];
    cfg.schema.forEach(function (f) {
      var group = presentationGroup(f);
      if (groups.indexOf(group) < 0) groups.push(group);
    });
    function moveGroupTo(name, index) {
      var currentIndex = groups.indexOf(name);
      if (currentIndex < 0) return;
      groups.splice(currentIndex, 1);
      groups.splice(Math.min(index, groups.length), 0, name);
    }
    moveGroupTo("Home Assistant connection", 2);
    moveGroupTo("Dashboard", 3);
    moveGroupTo("Built-in renderer", 4);
    var behaviourIndex = groups.indexOf("Behaviour");
    if (behaviourIndex >= 0) moveGroupTo("Auto-sleep", behaviourIndex + 1);
    var loggingIndex = groups.indexOf("Logging");
    if (loggingIndex >= 0 && loggingIndex !== groups.length - 1) {
      groups.splice(loggingIndex, 1);
      groups.push("Logging");
    }
    var autoSleepFields = fieldsForConfigGroup("Auto-sleep");
    var nextAutoSleepSignature = autoSleepCardSignature(autoSleepFields);
    var retainedAutoSleepPanel = document.getElementById("auto-sleep-status");
    if (!retainedAutoSleepPanel || !retainedAutoSleepPanel.parentNode || typeof retainedAutoSleepPanel.contains !== "function") retainedAutoSleepPanel = null;
    var existingAutoSleepCard = retainedAutoSleepPanel && retainedAutoSleepPanel.closest('[data-config-group="Auto-sleep"]');
    var retainedAutoSleepCard = existingAutoSleepCard && cfg.values.auto_sleep === "true" &&
      existingAutoSleepCard.getAttribute("data-render-signature") === nextAutoSleepSignature ? existingAutoSleepCard : null;
    var retainedAutoSleepFocus = retainedAutoSleepPanel && retainedAutoSleepPanel.contains(document.activeElement) ? document.activeElement : null;
    var retainedAutoSleepScroll = retainedAutoSleepPanel && retainedAutoSleepPanel.querySelector(".auto-sleep-source-scroll");
    var retainedAutoSleepScrollTop = retainedAutoSleepScroll ? retainedAutoSleepScroll.scrollTop : 0;
    var retainedAutoSleepViewportAnchor = retainedAutoSleepCard ? cfg.configViewportAnchor(retainedAutoSleepPanel) : null;
    // Fresh off-screen cards normally use content-visibility's intrinsic placeholder until a later
    // frame. If Auto-sleep is already being viewed, that delayed replacement would relocate it after
    // this render has finished. Lay out this transaction's cards exactly; the narrow-screen lazy
    // optimization remains active whenever the auto-sleep panel is outside the viewport.
    root.classList.toggle("config-viewport-anchored", !!retainedAutoSleepViewportAnchor);
    var autoSleepParking = null;
    if (retainedAutoSleepPanel && !retainedAutoSleepCard) {
      // render() rebuilds unrelated Configure cards after asynchronous probes. Keep the activity
      // subtree connected while that happens so its chart, scroll and focus do not flash away.
      autoSleepParking = cfg.el("div", { hidden: "", "aria-hidden": "true" });
      root.parentNode.insertBefore(autoSleepParking, root.nextSibling);
      autoSleepParking.appendChild(retainedAutoSleepPanel);
    }
    if (cfg.haPickerCleanup) cfg.haPickerCleanup();
    cfg.haOauthButton = null; cfg.haOauthStatus = null; cfg.haOauthLinks = null;
    var shown = 0, total = 0, explained = 0, desiredCards = [];
    var terms = cfg.filterTerms();
    cfg.revealHashTarget();
    groups.forEach(function (g) {
      var fields = fieldsForConfigGroup(g);
      if (!fields.length) {
        // The one group that explains itself when it has nothing to show: a missing Camera card is the
        // documented complaint, because nothing connected "my panel has a camera" to "set the flag".
        // Every camera setting is advanced, so the explanation belongs to the Advanced view.
        if (g !== "Camera" || !cfg.cameraGroupUnavailable() || !cfg.advanced || terms.length) return;
        cfg.loadCameraCapability();
        var absent = cfg.el("div", { class: "card" }, [
          cfg.el("h2", {}, [cfg.el("span", { text: groupTitle(g) })]),
          cfg.cameraUnavailableNode(),
        ]);
        absent.setAttribute("data-config-group", g);
        absent.setAttribute("data-layout-key", configLayoutKey(g));
        desiredCards.push(absent);
        explained += 1;
        return;
      }
      var rows = fields.filter(shouldRenderRow);
      var basicRows = rows.filter(function (f) { return !cfg.advancedField(f); }).length;
      var advancedRows = rows.length - basicRows;
      // Basic shows a card's BASIC rows and offers its own advanced rows; a card with no BASIC row is
      // not shown at all. A filter searches every row whatever the view.
      var revealed = !cfg.advanced && !terms.length && basicRows > 0 && cfg.revealedGroups[g] === true;
      var visibleFields = terms.length ? rows.filter(function (f) { return cfg.fieldMatchesFilter(f, terms); })
        : cfg.advanced || revealed ? rows
        : rows.filter(function (f) { return !cfg.advancedField(f); });
      total += rows.length;
      if (!visibleFields.length) return;
      shown += visibleFields.length;
      if (g === "Auto-sleep" && retainedAutoSleepCard) {
        desiredCards.push(retainedAutoSleepCard);
        return;
      }
      // Maturity badges on whole cards; Logging is intentionally no longer experimental.
      var h2kids = [cfg.el("span", { text: groupTitle(g) })];
      if (CARD_NOTES[g]) h2kids.push(cfg.el("small", { text: cfg.i18nText("configure.group.ha_reporting_note", " · Home Assistant reporting") }));
      var badge = CARD_BADGES[g];
      if (badge) h2kids.push(cfg.el("span", { class: "cardbadge " + badge[1], text: cfg.i18nText("configure.badge." + badge[0], badge[0]) }));
      var card = cfg.el("div", { class: "card" }, [cfg.el("h2", {}, h2kids)]);
      card.setAttribute("data-config-group", g);
      card.setAttribute("data-layout-key", configLayoutKey(g) + (revealed ? ".adv" : ""));
      if (g === "Auto-sleep") card.setAttribute("data-render-signature", nextAutoSleepSignature);
      visibleFields.forEach(function (f) {
        card.appendChild(row(f));
        if (g === "Auto-sleep" && f.key === "auto_sleep") card.appendChild(cfg.autoSleepPrerequisiteNode());
        if (g === "Auto-sleep" && f.key === "auto_sleep" && cfg.values.auto_sleep === "true") {
          card.appendChild(retainedAutoSleepPanel || cfg.autoSleepPanel());
          if (!cfg.autoSleepUsesTouch() && !cfg.autoSleepStatus && !cfg.autoSleepLoading) setTimeout(cfg.loadAutoSleepData, 0);
        }
        if (g === "Home Assistant connection" && f.key === "ha_url") card.appendChild(cfg.haOAuthRow());
        if (f.picker === "voice_wake_words" && Array.isArray(cfg.voiceWakeWordsCatalog)) card.appendChild(cfg.voiceWakeWordImportRow());
        if (f.key === "zigbee_router") {
          var join = cfg.zigbeeJoinRow();
          if (join) card.appendChild(join);
        }
      });
      if (g === "Display") {
        if (!terms.length && cfg.values.auto_brightness === "true" && cfg.ambientLightSourceConfigured()) card.appendChild(cfg.autoBrightnessPanel());
        if (!cfg.autoBrightStatus && !cfg.autoBrightLoading) cfg.loadAutoBrightnessData(false);
      }
      // Dashboard card action: clear the built-in renderer's browsing storage — the heal for a
      // corrupted localStorage/IndexedDB that survives reloads. Never logs the panel out (auth lives
      // in ha-paneld's config, not the WebView).
      if (g === "Built-in renderer" && !terms.length) {
        var st = cfg.el("span", { class: "muted" });
        var clearStatusTimer = null;
        function setClearStatus(text, transient) {
          if (clearStatusTimer) clearTimeout(clearStatusTimer);
          clearStatusTimer = null;
          st.textContent = text;
          if (transient) clearStatusTimer = setTimeout(function () {
            clearStatusTimer = null;
            st.textContent = "";
          }, 4000);
        }
        var btn = cfg.el("button", {
          class: "pbtn", text: cfg.i18nText("configure.renderer.clear_storage", "Clear renderer storage"),
          "aria-describedby": "hardened-approval-description",
          title: cfg.i18nText("configure.hardened.action_approval", "Requires physical on-panel approval for this action when Hardened mode is enabled.")
        });
        btn.onclick = function () {
          setClearStatus(cfg.i18nText("configure.renderer.clearing", "Clearing…"), false);
          fetch("api/v1/dashboard/clear-storage", { method: "POST" })
            .then(function (r) { return cfg.approvalAwareJson(r).then(function () { return r; }); })
            .then(function (r) { setClearStatus(r.ok ? cfg.i18nText("configure.renderer.clear_requested", "Clear requested.") : cfg.i18nText("configure.error.http", "Failed (HTTP {status})", { status: r.status }), r.ok); })
            .catch(function (error) { setClearStatus(error && error.approvalRequired ? cfg.approvalMessage(error.body) : cfg.i18nText("configure.error.network", "Failed (network)"), false); });
        };
        card.appendChild(cfg.el("div", { class: "frow frow-action" }, [
          cfg.el("div", { class: "flabel" }, [
            cfg.el("span", {
              text: cfg.i18nText("configure.renderer.storage", "Renderer storage"), "data-hardened-approval": "",
              "aria-describedby": "hardened-approval-description",
              title: cfg.i18nText("configure.hardened.action_approval", "Requires physical on-panel approval for this action when Hardened mode is enabled.")
            }),
            cfg.el("small", { text: cfg.i18nText("configure.renderer.clear_storage_help", "Clear cached dashboard data. Keeps sign-in.") }),
          ]),
          cfg.el("div", { class: "fctl" }, [btn, st]),
        ]));
      }
      if (!cfg.advanced && !terms.length && basicRows && advancedRows) card.appendChild(cfg.advancedRevealButton(g, revealed, advancedRows));
      desiredCards.push(card);
    });
    var countNode = document.getElementById("cfg-count");
    if (countNode) countNode.textContent = cfg.i18nText("configure.count", "{shown} of {total} settings", { shown: shown, total: total });
    var autoSleepCard = desiredCards.find(function (card) {
      return card.getAttribute("data-config-group") === "Auto-sleep";
    });
    var behaviourCard = desiredCards.find(function (card) {
      return card.getAttribute("data-config-group") === "Behaviour";
    });
    if (autoSleepCard && behaviourCard) {
      desiredCards.splice(desiredCards.indexOf(autoSleepCard), 1);
      desiredCards.splice(desiredCards.indexOf(behaviourCard) + 1, 0, autoSleepCard);
    }
    if (proximityCard) {
      var autoSleepCardIndex = desiredCards.findIndex(function (card) {
        return card.getAttribute("data-config-group") === "Auto-sleep";
      });
      var behaviourCardIndex = desiredCards.findIndex(function (card) {
        return card.getAttribute("data-config-group") === "Behaviour";
      });
      var presenceCardIndex = autoSleepCardIndex >= 0 ? autoSleepCardIndex + 1 : behaviourCardIndex + 1;
      desiredCards.splice(presenceCardIndex < 0 ? desiredCards.length : presenceCardIndex, 0, proximityCard);
    }
    // Reconcile by card instead of emptying the grid. In particular, an unchanged Auto-sleep card
    // never leaves the rendered tree while Home-dashboard and adaptive-brightness requests finish,
    // so low-end WebViews do not discard and repaint the large Auto-sleep chart raster.
    reconcileConfigCards(root, desiredCards);
    if (typeof window.repositionProximityLearningCard === "function") window.repositionProximityLearningCard();
    if (retainedAutoSleepPanel && retainedAutoSleepPanel.isConnected && !retainedAutoSleepCard) {
      cfg.updateAutoSleepSummary();
      cfg.updateAutoSleepHistory();
    }
    var anyContent = shown || explained;
    document.getElementById("cfg-status").style.display = anyContent ? "none" : "block";
    if (!anyContent) document.getElementById("cfg-status").textContent = cfg.i18nText("configure.empty", "No settings in this view.");
    if (retainedAutoSleepFocus && retainedAutoSleepFocus.isConnected && document.activeElement !== retainedAutoSleepFocus) {
      try { retainedAutoSleepFocus.focus({ preventScroll: true }); }
      catch (_) { retainedAutoSleepFocus.focus(); }
    }
    if (retainedAutoSleepScroll && retainedAutoSleepScroll.isConnected) retainedAutoSleepScroll.scrollTop = retainedAutoSleepScrollTop;
    if (autoSleepParking) autoSleepParking.remove();
    if (window.CardSizeMemory) {
      // Filtered heights describe no layout worth remembering.
      if (retainedAutoSleepViewportAnchor || terms.length) window.CardSizeMemory.invalidate("cfg-groups");
      else window.CardSizeMemory.restore("cfg-groups");
    }
    cfg.restoreConfigViewportAnchor(retainedAutoSleepViewportAnchor);
    // Keep exact layout through the card-memory settle window. The bounded release restores normal
    // lazy rendering after the latest asynchronous result; direct user input cancels compensation so
    // a late timer can never pull the viewport away from a scroll or key navigation in progress.
    cfg.scheduleConfigExactLayoutRelease(root, retainedAutoSleepViewportAnchor);
    cfg.focusHash();
    scheduleConfigColumnAlignment();
  }

  cfg.presentationGroup = presentationGroup;
  cfg.syncAutoSleepCardSignature = syncAutoSleepCardSignature;
  cfg.render = render;
})(window.ConfigurePage);
