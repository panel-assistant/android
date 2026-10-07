// Small, dependency-free translation bridge for the :8888 pages.
//
// The server writes the request-localized catalogue projection into:
//   <script id="ha-i18n" type="application/json">...</script>
// and loads this file before any page-specific script. Callers remain the
// authority for English fallback text: HaI18n.t(key, english, values).
(function (root) {
  "use strict";

  var own = Object.prototype.hasOwnProperty;
  var payload = {};
  var strings = {};
  var source = root.document && root.document.getElementById("ha-i18n");

  if (source) {
    try {
      payload = JSON.parse(source.textContent || "{}");
      if (!payload || typeof payload !== "object" || Array.isArray(payload)) payload = {};
      if (payload.strings && typeof payload.strings === "object" && !Array.isArray(payload.strings)) {
        strings = payload.strings;
      }
    } catch (_) {
      // A missing or malformed projection must leave the English UI usable.
      payload = {};
      strings = {};
    }
  }

  function translatedText(key) {
    if (!own.call(strings, key)) return null;
    var entry = strings[key];
    if (typeof entry === "string") return entry;
    if (entry && typeof entry === "object" && typeof entry.text === "string") return entry.text;
    return null;
  }

  function interpolate(text, values) {
    if (!values || typeof values !== "object") return text;
    // Only named placeholders are substitutions. Unknown placeholders and all
    // other bytes (HA, MQTT, package ids, URLs, versions and units) pass through.
    return text.replace(/\{([A-Za-z][A-Za-z0-9_]*)\}/g, function (placeholder, name) {
      return own.call(values, name) ? String(values[name]) : placeholder;
    });
  }

  function t(key, englishFallback, values) {
    var translated = translatedText(String(key));
    var fallback = englishFallback == null ? "" : String(englishFallback);
    return interpolate(translated == null ? fallback : translated, values);
  }

  function text(node, key, englishFallback, values) {
    var value = t(key, englishFallback, values);
    if (node) node.textContent = value;
    return value;
  }

  // Page loaders share this bridge. Retain their DOM so existing background observations can
  // settle without losing controls or changing their lifetimes.
  function pageFailure(diagnostic) {
    if (document.querySelector("main.pickles")) return;
    var stylesheet = document.createElement("link");
    stylesheet.rel = "stylesheet";
    stylesheet.href = "assets/pickles.css";
    document.head.appendChild(stylesheet);
    var main = document.createElement("main");
    main.className = "pickles";
    main.setAttribute("role", "alert");
    var image = document.createElement("img");
    // Artwork: https://github.com/maxlyth/pickles — refresh app/src/main/assets/pickles.svg from that source when needed.
    image.src = "assets/pickles.svg";
    image.alt = "";
    main.appendChild(image);
    function line(tag, value) {
      var node = document.createElement(tag);
      node.textContent = value;
      if (tag === "code") node.lang = "und";
      main.appendChild(node);
    }
    line("h1", t("shell.pickles.title", "Pickles has escaped"));
    line("p", t("shell.pickles.story", "Our slow, stubborn panda has wandered off with this page."));
    line("p", t("shell.pickles.load_failed", "Could not load this page"));
    line("code", String(diagnostic));
    var retry = document.createElement("button");
    retry.type = "button";
    retry.textContent = t("shell.pickles.retry", "Try again");
    retry.addEventListener("click", function () { root.location.reload(); });
    main.appendChild(retry);
    document.body.appendChild(main);
    document.body.classList.add("pickles-failed");
  }

  root.HaI18n = Object.freeze({
    locale: typeof payload.locale === "string" ? payload.locale : "en",
    // Locales whose ?lang the pages carry on their own links.
    locales: Object.freeze(Array.isArray(payload.locales) ? payload.locales.slice() : []),
    t: t,
    has: function (key) { return translatedText(String(key)) != null; },
    text: text,
    pageFailure: pageFailure
  });
})(window);
