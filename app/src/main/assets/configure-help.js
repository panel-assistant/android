// Configure help popover and text-node markdown renderer.
(function (cfg) {
  "use strict";


  // The RTSP port the camera transport listens on. Must equal CameraRtspServer.DEFAULT_PORT;
  // CameraSurfaceContractTest asserts the two agree so this cannot drift silently.
  var CAMERA_RTSP_PORT = 8554;

  /**
   * Turn named words inside a help string into links, leaving the wording itself in the settings
   * registry. Each pair is [word, href]; the first occurrence of each word becomes an anchor and
   * everything else stays plain text, so re-wording the help does not have to be mirrored here.
   */
  function linkifyWords(text, pairs) {
    var nodes = [document.createTextNode(text)];
    pairs.forEach(function (pair) {
      for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        if (node.nodeType !== 3) continue;
        var at = node.textContent.indexOf(pair[0]);
        if (at < 0) continue;
        var before = node.textContent.slice(0, at);
        var after = node.textContent.slice(at + pair[0].length);
        var link = cfg.el("a", { href: pair[1], text: pair[0] });
        if (pair[1].indexOf("rtsp:") !== 0) link.setAttribute("target", "_blank");
        link.setAttribute("title", pair[1]);
        nodes.splice(i, 1, document.createTextNode(before), link, document.createTextNode(after));
        break;
      }
    });
    return nodes;
  }

  // Markdown subset carried by settings help: blank-line paragraphs, "- " list lines, **bold**, *italic*,
  // `code` and [text](url). Output is built only from elements and text nodes, never from HTML, and a
  // link keeps its target only for http(s) or a scheme-less page path.
  var HELP_INLINE_RE = /(\*\*[^*]+\*\*|\*[^*\s][^*]*\*|`[^`]+`|\[[^\]]+\]\([^)\s]+\))/g;
  function helpInline(text) {
    var nodes = [], last = 0, match;
    HELP_INLINE_RE.lastIndex = 0;
    while ((match = HELP_INLINE_RE.exec(text))) {
      if (match.index > last) nodes.push(document.createTextNode(text.slice(last, match.index)));
      var token = match[0], node;
      if (token.indexOf("**") === 0) node = cfg.el("strong", { text: token.slice(2, -2) });
      else if (token.charAt(0) === "`") node = cfg.el("code", { text: token.slice(1, -1) });
      else if (token.charAt(0) === "*") node = cfg.el("em", { text: token.slice(1, -1) });
      else {
        var link = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(token);
        var safe = /^https?:\/\//i.test(link[2]) || !/^[a-z][a-z0-9+.-]*:/i.test(link[2]);
        node = safe ? cfg.el("a", { href: link[2], text: link[1] }) : document.createTextNode(link[1]);
        if (safe && /^https?:\/\//i.test(link[2])) { node.setAttribute("target", "_blank"); node.setAttribute("rel", "noopener"); }
      }
      nodes.push(node);
      last = match.index + token.length;
    }
    if (last < text.length) nodes.push(document.createTextNode(text.slice(last)));
    return nodes;
  }
  function renderHelpMarkdown(text) {
    return String(text || "").split(/\n\s*\n/).filter(function (block) { return block.trim(); }).map(function (block) {
      var lines = block.split("\n");
      if (lines.every(function (line) { return /^\s*- /.test(line); })) {
        return cfg.el("ul", {}, lines.map(function (line) { return cfg.el("li", {}, helpInline(line.replace(/^\s*- /, ""))); }));
      }
      return cfg.el("p", {}, helpInline(lines.join(" ")));
    });
  }
  function helpPlainText(text) {
    return String(text || "").replace(/\[([^\]]+)\]\([^)]*\)/g, "$1").replace(/[*`]/g, "").replace(/^\s*- /gm, "");
  }

  // One help popover for the whole page, outside the card wall so it never changes a card's height.
  // Native `popover` puts it in the top layer; older WebViews get the same element as a fixed box.
  var helpPop = document.getElementById("cfg-help");
  var helpAnchor = null, helpPinned = false, helpHoverTimer = null;
  var helpHover = !!(window.matchMedia && window.matchMedia("(hover: hover) and (pointer: fine)").matches);
  function hasLongHelp(f) { return !!String(f.help || "").trim(); }
  function includeHiddenSummary(f) {
    return !cfg.descriptions && f.shortDescriptionUsefulInPopover === true && !!String(f.summary || "").trim();
  }
  function helpAvailable(f) { return hasLongHelp(f) || includeHiddenSummary(f); }
  function helpWebsiteUrl(f) {
    var tools = document.getElementById("cfg-tools");
    var version = tools && tools.getAttribute("data-app-version") || "";
    var locale = window.HaI18n && typeof window.HaI18n.locale === "string" ? window.HaI18n.locale : (document.documentElement.lang || "en");
    return "https://panel-assistant.io/go/settings?v=" + encodeURIComponent(version) +
      "&lang=" + encodeURIComponent(locale) + "&section=" + encodeURIComponent(f.key);
  }
  function placeHelp() {
    if (!helpPop || !helpAnchor) return;
    if (window.innerWidth <= 600) { helpPop.style.left = ""; helpPop.style.top = ""; return; }
    var rect = helpAnchor.getBoundingClientRect(), width = helpPop.offsetWidth, height = helpPop.offsetHeight;
    var left = Math.min(Math.max(16, rect.left - 8), window.innerWidth - width - 16);
    var top = rect.bottom + 8;
    if (top + height > window.innerHeight - 16) top = Math.max(16, rect.top - height - 8);
    helpPop.style.left = Math.round(left) + "px";
    helpPop.style.top = Math.round(top) + "px";
  }
  function closeHelp() {
    if (helpHoverTimer) clearTimeout(helpHoverTimer);
    helpHoverTimer = null;
    if (helpAnchor) helpAnchor.setAttribute("aria-expanded", "false");
    helpAnchor = null; helpPinned = false;
    if (!helpPop) return;
    helpPop.classList.remove("open");
    if (typeof helpPop.hidePopover === "function") { try { helpPop.hidePopover(); } catch (_) {} }
  }
  function openHelp(button, f, pin) {
    if (!helpPop) return;
    if (helpAnchor === button && pin && helpPinned) { closeHelp(); return; }
    if (helpAnchor && helpAnchor !== button) helpAnchor.setAttribute("aria-expanded", "false");
    document.getElementById("cfg-help-title").textContent = f.label;
    var body = document.getElementById("cfg-help-body");
    body.textContent = "";
    if (hasLongHelp(f) && f.helpLanguage) body.setAttribute("lang", f.helpLanguage); else body.removeAttribute("lang");
    if (includeHiddenSummary(f)) {
      var summary = cfg.el("p", { text: f.summary });
      if (f.summaryLanguage) summary.setAttribute("lang", f.summaryLanguage);
      body.appendChild(summary);
    }
    if (hasLongHelp(f)) helpBodyNodes(f).forEach(function (node) { body.appendChild(node); });
    document.getElementById("cfg-help-more").setAttribute("href", helpWebsiteUrl(f));
    helpAnchor = button; helpPinned = !!pin;
    button.setAttribute("aria-expanded", "true");
    helpPop.classList.add("open");
    if (typeof helpPop.showPopover === "function") { try { helpPop.showPopover(); } catch (_) {} }
    body.scrollTop = 0;
    placeHelp();
    if (pin) {
      var close = document.getElementById("cfg-help-close");
      try { close.focus({ preventScroll: true }); } catch (_) { close.focus(); }
    }
  }
  function helpButton(f) {
    if (!helpAvailable(f)) return null;
    var button = cfg.el("button", {
      class: "info-btn", type: "button", "aria-expanded": "false", "aria-controls": "cfg-help",
      "aria-label": cfg.i18nText("configure.help.about", "About {label}", { label: f.label })
    });
    button.addEventListener("click", function (event) { event.stopPropagation(); openHelp(button, f, true); });
    if (helpHover) {
      button.addEventListener("mouseenter", function () {
        if (helpPinned) return;
        helpHoverTimer = setTimeout(function () { openHelp(button, f, false); }, 180);
      });
      button.addEventListener("mouseleave", function () {
        if (helpHoverTimer) clearTimeout(helpHoverTimer);
        helpHoverTimer = setTimeout(function () { if (!helpPinned && helpAnchor === button) closeHelp(); }, 120);
      });
    }
    return button;
  }
  if (helpPop && document.addEventListener) {
    document.getElementById("cfg-help-close").addEventListener("click", function () {
      var anchor = helpAnchor;
      closeHelp();
      if (anchor && anchor.isConnected) { try { anchor.focus({ preventScroll: true }); } catch (_) { anchor.focus(); } }
    });
    helpPop.addEventListener("mouseenter", function () { if (helpHoverTimer) clearTimeout(helpHoverTimer); });
    helpPop.addEventListener("mouseleave", function () { if (!helpPinned) closeHelp(); });
    document.addEventListener("click", function (event) {
      if (helpPinned && !helpPop.contains(event.target)) closeHelp();
    });
    document.addEventListener("keydown", function (event) { if (event.key === "Escape" && helpAnchor) closeHelp(); });
    window.addEventListener("scroll", function () { if (helpAnchor && !helpAnchor.isConnected) closeHelp(); else placeHelp(); }, { passive: true });
    window.addEventListener("resize", placeHelp);
  }

  // The full help of one setting, for the help popover. Two settings name things the reader may want
  // to open or copy, so their words become links; every other help renders its markdown subset.
  function helpBodyNodes(f) {
    if (f.key === "dashboard_zoom") {
      var zoomKids = renderHelpMarkdown(f.help);
      if (f.displaySizingAvailable === true) {
        if (!zoomKids.length) zoomKids.push(cfg.el("p"));
        var last = zoomKids[zoomKids.length - 1];
        last.appendChild(document.createTextNode(cfg.i18nText("configure.display.recommend_prefix", " Recommend use ")));
        last.appendChild(cfg.el("a", { href: cfg.localizedPageHref("install#cfg-display"), text: cfg.i18nText("configure.display.sizing", "Display Sizing") }));
        last.appendChild(document.createTextNode(cfg.i18nText("configure.display.recommend_suffix", " for better results")));
      }
      return zoomKids;
    }
    if (f.key === "camera_enabled") {
      // The RTSP link uses whatever host this page was reached on, which is the address that will
      // also work from Home Assistant.
      return [cfg.el("p", {}, linkifyWords(f.help, [
        ["RTSP", "rtsp://" + location.hostname + ":" + CAMERA_RTSP_PORT + "/live"],
        ["JPEG", "api/v1/camera/snapshot.jpg"],
      ]))];
    }
    return renderHelpMarkdown(f.help);
  }

  cfg.helpPlainText = helpPlainText;
  cfg.closeHelp = closeHelp;
  cfg.helpButton = helpButton;
})(window.ConfigurePage);
