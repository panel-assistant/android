// Configure view, filter, card-size memory and viewport anchoring.
(function (cfg) {
  "use strict";

  var configCardRoot = document.getElementById("cfg-groups");
  cfg.configCardExpected = { core: true, radio: true, brightness: true };
  var configCardReady = Object.create(null), configCardMemoryReady = false;
  if (configCardRoot && configCardRoot.getAttribute("data-card-size-proximity") === "1") cfg.configCardExpected.proximity = true;
  function configCardSourceReady(source) {
    if (configCardReady[source]) return;
    configCardReady[source] = true;
    var complete = Object.keys(cfg.configCardExpected).every(function (key) { return configCardReady[key]; });
    if (!complete) return;
    configCardMemoryReady = true;
    if (window.CardSizeMemory) window.CardSizeMemory.settle("cfg-groups", 1200);
  }
  function configCardGeometryChanged() {
    if (configCardMemoryReady && window.CardSizeMemory) window.CardSizeMemory.settle("cfg-groups", 1200);
  }
  function configCardGeometryInvalid() {
    if (window.CardSizeMemory) window.CardSizeMemory.invalidate("cfg-groups");
  }
  window.configCardSizeSourceReady = configCardSourceReady;
  window.configCardSizeGeometryChanged = configCardGeometryChanged;
  window.configCardSizeGeometryInvalid = configCardGeometryInvalid;

  // Tier, Descriptions and each card's advanced reveal are remembered per browser. The filter is not.
  function readConfigView() {
    try {
      var stored = JSON.parse(window.localStorage.getItem(cfg.CONFIG_VIEW_STORAGE_KEY) || "null");
      if (!stored || typeof stored !== "object") return;
      cfg.advanced = stored.advanced === true;
      cfg.descriptions = stored.descriptions !== false;
      if (Array.isArray(stored.revealed)) stored.revealed.forEach(function (group) {
        if (typeof group === "string") cfg.revealedGroups[group] = true;
      });
    } catch (_) {}
  }
  function writeConfigView() {
    try {
      window.localStorage.setItem(cfg.CONFIG_VIEW_STORAGE_KEY, JSON.stringify({
        advanced: cfg.advanced, descriptions: cfg.descriptions, revealed: Object.keys(cfg.revealedGroups)
      }));
    } catch (_) {}
  }
  // The view is part of the card-height memory's layout context, so each combination keeps its own
  // remembered heights.
  function syncConfigViewUi() {
    var root = document.getElementById("cfg-groups");
    if (root) {
      root.classList.toggle("cfg-nosum", !cfg.descriptions);
      root.setAttribute("data-card-size-context", (cfg.advanced ? "advanced" : "basic") + (cfg.descriptions ? "" : ".labels"));
    }
    var basic = document.getElementById("tier-basic"), adv = document.getElementById("tier-adv");
    if (basic && basic.type === "radio") basic.checked = !cfg.advanced;
    if (adv && adv.type === "radio") adv.checked = cfg.advanced;
    var desc = document.getElementById("cfg-desc");
    if (desc) desc.checked = cfg.descriptions;
  }
  function configViewChanged() {
    writeConfigView();
    syncConfigViewUi();
    cfg.closeHelp();
    cfg.render();
    if (!cfg.filterText) configCardGeometryChanged();
  }
  // Only an explicit ADVANCED tier hides a row from Basic, so missing metadata never makes a setting
  // unreachable.
  function advancedField(f) { return f.tier === "ADVANCED"; }
  function fieldMatchesFilter(f, terms) {
    var haystack = (f.key + " " + f.label + " " + (f.summary || "") + " " + cfg.helpPlainText(f.help)).toLowerCase();
    return terms.every(function (term) { return haystack.indexOf(term) >= 0; });
  }
  function filterTerms() {
    return cfg.filterText.toLowerCase().split(/\s+/).filter(Boolean);
  }
  // A deep link to a setting the current view hides reveals that setting's card first.
  function revealHashTarget() {
    if (cfg.hashJumpUntil !== 0 || !location.hash || location.hash.indexOf("#cfg-") !== 0) return;
    var key = location.hash.slice(5);
    var field = cfg.schema.find(function (f) { return f.key === key; });
    if (field && advancedField(field) && !cfg.advanced) cfg.revealedGroups[cfg.presentationGroup(field)] = true;
  }

  function focusHash() {
    if (cfg.hashJumpUntil < 0 || !location.hash) return;
    var target = document.getElementById(location.hash.slice(1));
    if (!target) return;
    if (!cfg.hashJumpUntil) {
      cfg.hashJumpUntil = Date.now() + 1400;
      target.classList.add("flash");
      setTimeout(function () { target.classList.remove("flash"); }, 1800);
    } else if (Date.now() > cfg.hashJumpUntil) {
      cfg.hashJumpUntil = -1;
      return;
    }
    // A jump computed on content-visibility placeholders lands short once the cards above render
    // (WebKit has no scroll anchoring to hide it). Scroll on exact layout, and repeat after each
    // render in the settle window until the user moves, since a render drops the exact layout; the
    // bounded release then keeps the target where the jump put it.
    var root = document.getElementById("cfg-groups");
    root.classList.add("config-viewport-anchored");
    target.scrollIntoView({ block: "center" });
    scheduleConfigExactLayoutRelease(root, configViewportAnchor(target));
  }

  // The single-column placeholder regime is defined exactly once, by info.css's container query on
  // `.cards`, which publishes `--single-column` on every card it covers. Reading that flag keeps this
  // in step with the CSS by construction. It replaces `matchMedia("(max-width: 857px)")`, which
  // restated a viewport threshold the CSS derived from a padding that same query changed — so the two
  // disagreed across 834-857px — and which could not see a scrollbar's width or the width of the Panel
  // Assistant sidebar iframe these pages now render inside.
  function configSingleColumnRegime(node) {
    if (!window.getComputedStyle) return true;
    return window.getComputedStyle(node).getPropertyValue("--single-column").trim() === "1";
  }

  function configViewportAnchor(node) {
    if (!node || !node.isConnected || typeof node.getBoundingClientRect !== "function") return null;
    if (!configSingleColumnRegime(node)) return null;
    var rect = node.getBoundingClientRect();
    var viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
    if (!isFinite(rect.top) || !isFinite(rect.bottom) || rect.height <= 0 || viewportHeight <= 0 ||
        rect.bottom <= 0 || rect.top >= viewportHeight) return null;
    return { node: node, top: rect.top };
  }

  function restoreConfigViewportAnchor(anchor) {
    if (!anchor || !anchor.node.isConnected || !window.scrollTo) return;
    // The browser may already have applied some native scroll anchoring while cards above this one
    // were reconciled. Compensate from the CURRENT scroll position so both mechanisms converge on
    // the same viewport coordinate instead of resetting scrollY to its stale pre-render value. A
    // scroll can itself change which one-column cards content-visibility considers onscreen, so
    // force and settle that layout synchronously before paint rather than correcting it later.
    for (var attempt = 0; attempt < 8; attempt += 1) {
      var top = anchor.node.getBoundingClientRect().top;
      var delta = top - anchor.top;
      if (!isFinite(delta) || Math.abs(delta) <= 0.5) return;
      var beforeY = window.pageYOffset || 0;
      window.scrollTo(window.pageXOffset || 0, beforeY + delta);
      if ((window.pageYOffset || 0) === beforeY) return;
    }
  }

  var configExactLayoutReleaseTimer = null;
  var configExactLayoutRoot = null;
  var configExactLayoutCompensationCancelled = false;
  function scheduleConfigExactLayoutRelease(root, anchor) {
    if (configExactLayoutReleaseTimer) clearTimeout(configExactLayoutReleaseTimer);
    configExactLayoutReleaseTimer = null;
    configExactLayoutRoot = anchor ? root : null;
    configExactLayoutCompensationCancelled = false;
    if (!anchor) return;
    configExactLayoutReleaseTimer = setTimeout(function () {
      configExactLayoutReleaseTimer = null;
      configExactLayoutRoot = null;
      if (!root.isConnected || !root.classList.contains("config-viewport-anchored")) return;
      root.classList.remove("config-viewport-anchored");
      if (!configExactLayoutCompensationCancelled) restoreConfigViewportAnchor(anchor);
      configExactLayoutCompensationCancelled = false;
    }, 1400);
  }

  function cancelConfigExactLayoutCompensation() {
    // Keep exact layout until the bounded release, but never let its final scroll correction compete
    // with navigation the user has started since the render.
    if (configExactLayoutReleaseTimer) configExactLayoutCompensationCancelled = true;
    if (cfg.hashJumpUntil > 0) cfg.hashJumpUntil = -1;
  }
  if (document.addEventListener) {
    ["pointerdown", "touchstart", "wheel", "keydown"].forEach(function (type) {
      document.addEventListener(type, cancelConfigExactLayoutCompensation, true);
    });
  }
  if (window.addEventListener) {
    window.addEventListener("pagehide", function () {
      if (configExactLayoutReleaseTimer) clearTimeout(configExactLayoutReleaseTimer);
      configExactLayoutReleaseTimer = null;
      configExactLayoutCompensationCancelled = true;
      var root = configExactLayoutRoot || document.getElementById("cfg-groups");
      configExactLayoutRoot = null;
      if (root) root.classList.remove("config-viewport-anchored");
    });
  }

  function advancedRevealButton(group, revealed, count) {
    var button = cfg.el("button", {
      class: "cfg-more", type: "button", "aria-expanded": revealed ? "true" : "false",
      text: revealed ? cfg.i18nText("configure.advanced.hide", "Hide advanced settings")
        : count === 1 ? cfg.i18nText("configure.advanced.show_one", "Show 1 advanced setting")
        : cfg.i18nText("configure.advanced.show", "Show {count} advanced settings", { count: count })
    });
    button.addEventListener("click", function () {
      if (cfg.revealedGroups[group]) delete cfg.revealedGroups[group]; else cfg.revealedGroups[group] = true;
      configViewChanged();
      var next = document.querySelector('[data-config-group="' + group + '"] > .cfg-more');
      if (next) { try { next.focus({ preventScroll: true }); } catch (_) { next.focus(); } }
    });
    return button;
  }

  window.cfgTab = function (adv) {
    cfg.advanced = !!adv;
    configViewChanged();
  };

  cfg.configCardSourceReady = configCardSourceReady;
  cfg.configCardGeometryChanged = configCardGeometryChanged;
  cfg.configCardGeometryInvalid = configCardGeometryInvalid;
  cfg.readConfigView = readConfigView;
  cfg.writeConfigView = writeConfigView;
  cfg.configViewChanged = configViewChanged;
  cfg.syncConfigViewUi = syncConfigViewUi;
  cfg.advancedField = advancedField;
  cfg.fieldMatchesFilter = fieldMatchesFilter;
  cfg.filterTerms = filterTerms;
  cfg.revealHashTarget = revealHashTarget;
  cfg.focusHash = focusHash;
  cfg.configViewportAnchor = configViewportAnchor;
  cfg.restoreConfigViewportAnchor = restoreConfigViewportAnchor;
  cfg.scheduleConfigExactLayoutRelease = scheduleConfigExactLayoutRelease;
  cfg.advancedRevealButton = advancedRevealButton;
})(window.ConfigurePage);
