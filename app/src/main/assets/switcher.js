// ha-paneld header panel-switcher — served as a static asset (CI-linted: `node --check`).
// When MORE THAN ONE ha-paneld panel is on the LAN (mDNS, via /api/v1/peers), the friendly name in the
// title becomes a dropdown of ALL panels (sorted alphabetically so the menu looks identical on every
// panel); the current panel is pre-selected. Picking another jumps THIS browser window to the SAME view
// (path) on that panel. When only this panel is present, the title stays plain text.
(function () {
  var el = document.getElementById('pswitch');
  if (!el) return;
  var selfId = el.getAttribute('data-self-id') || '';
  var selfName = el.getAttribute('data-self-name') || '';

  var i18nText = window.HaI18n.t;

  function isSelf(p) { return p.self === true || p.panel_id === selfId; }

  // Deterministic, locale-independent name sort — identical on every panel.
  function byName(a, b) {
    var x = (a.name || a.panel_id || '').toLowerCase();
    var y = (b.name || b.panel_id || '').toLowerCase();
    return x < y ? -1 : (x > y ? 1 : 0);
  }

  function peerBase(ip, port) {
    if (typeof ip !== 'string') return '';
    var parts = ip.split('.');
    if (parts.length !== 4 || parts.some(function (part) {
      return !/^\d{1,3}$/.test(part) || Number(part) > 255;
    })) return '';
    port = Number(port);
    if (!Number.isInteger(port) || port < 1 || port > 65535) return '';
    return 'http://' + parts.join('.') + ':' + port;
  }

  function build(all) {
    all = all.slice().sort(byName);
    var sel = document.createElement('select');
    var targets = [];
    sel.className = 'pswitch-sel';
    sel.title = i18nText(
      'shell.panel_switcher.title',
      'Switch to another ha-paneld panel (keeps the current view)'
    );
    sel.setAttribute('autocomplete', 'off');   // don't let the browser restore a stale selection
    all.forEach(function (p) {
      var o = document.createElement('option');
      o.textContent = p.name || p.panel_id;     // textContent, never innerHTML (values are peer-supplied)
      if (isSelf(p)) {
        o.value = '';                           // this panel — selecting it does nothing
        o.selected = true;
        targets.push('');
      } else {
        o.value = p.panel_id;
        targets.push(peerBase(p.ip, p.port));
      }
      sel.appendChild(o);
    });
    sel.onchange = function () {
      var base = targets[sel.selectedIndex] || '';
      sel.selectedIndex = selfIndex(sel);       // snap back to THIS panel so a later Back shows the right name
      if (!base) return;                        // "this panel" chosen — nothing to do
      // Same path/query/hash on the chosen peer: /configure on A -> /configure on B.
      window.location.href = base + window.location.pathname + window.location.search + window.location.hash;
    };
    // Back/forward can restore this page from the bfcache frozen with the last pick still selected —
    // `pageshow` fires on that restore (persisted=true) as well as normal load; reset to self either way.
    window.addEventListener('pageshow', function () { sel.selectedIndex = selfIndex(sel); });
    while (el.firstChild) el.removeChild(el.firstChild);
    var sep = document.createElement('span');   // equal margins both sides (info.css #pswitch .sep)
    sep.className = 'sep';
    sep.textContent = '·';
    el.appendChild(sep);
    el.appendChild(sel);
    // The dropdown changes the header width vs the plain "· name" text — re-run the responsive header
    // fit (tab collapse + overflow hide) now that the final header content is in place. Call the header
    // authority directly: broadcasting a synthetic `resize` also drove every unrelated resize listener —
    // the card-column aligner, CardSizeMemory's release(), the info.js high-water-mark reset and
    // fitControls() — at whatever moment /api/v1/peers happened to resolve, and only on a multi-panel LAN.
    // That made card geometry depend on peer-discovery timing, which is why the displacement was
    // reproducible but inconsistent. This header re-fit is a header concern and stays one.
    if (window.PanelHeaderFit) window.PanelHeaderFit.refit();
  }

  function selfIndex(sel) {
    for (var i = 0; i < sel.options.length; i++) if (!sel.options[i].value) return i;
    return 0;
  }

  fetch('api/v1/peers').then(function (r) { return r.json(); }).then(function (list) {
    if (!Array.isArray(list)) return;
    if (list.filter(function (p) { return !isSelf(p); }).length === 0) return; // only this panel — plain text
    // Show ALL panels (self in its alphabetical place). Synthesize self if the roster somehow omitted it,
    // so the current panel always appears and is selectable-as-current.
    if (!list.some(isSelf) && selfName) list = list.concat([{ panel_id: selfId, name: selfName, self: true }]);
    build(list);
  }).catch(function () { /* best-effort — leave the plain title on any failure */ });
})();

// ---- Responsive tab bar -> header hamburger ------------------------------------------------------
// When the .nav tab list can't fit on one line, collapse it into the #navburger hamburger (the first
// item in the header). Overflow is measured by forcing the nav to a single line (.nav-measure) and
// comparing its content width to the available width; on load + (debounced) resize we toggle
// body.nav-collapsed. The hamburger toggles body.nav-open, which drops the tabs as a vertical menu.
(function () {
  var body = document.body;
  var nav = document.querySelector('.nav');
  var burger = document.getElementById('navburger');
  var hdr = document.querySelector('.hdr');

  function setMenuOpen(open) {
    body.classList.toggle('nav-open', !!open);
    if (burger) burger.setAttribute('aria-expanded', open ? 'true' : 'false');
  }

  // Collapse the tab bar into the hamburger when it can't fit one line.
  function fitNav() {
    if (!nav || !burger) return;
    body.classList.remove('nav-collapsed');   // restore the inline bar so the measurement is clean
    nav.classList.add('nav-measure');          // force one line -> overflow shows as horizontal scroll
    var overflow = nav.scrollWidth > nav.clientWidth + 1;
    nav.classList.remove('nav-measure');
    body.classList.toggle('nav-collapsed', overflow);
    if (!overflow) setMenuOpen(false);                 // fits again -> ensure the menu is closed
  }

  // Prevent horizontal overflow of the (nowrap) header by dropping items in priority order until it
  // fits: the GitHub icon first, then the "ha-paneld" brand text. Reset + re-apply each pass so hidden
  // items reappear when the window widens again.
  var HIDE_ORDER = ['hdr-hide-gh', 'hdr-hide-brand'];
  function fitHeader() {
    if (!hdr) return;
    HIDE_ORDER.forEach(function (c) { body.classList.remove(c); });
    for (var i = 0; i < HIDE_ORDER.length; i++) {
      if (hdr.scrollWidth <= hdr.clientWidth + 1) return;
      body.classList.add(HIDE_ORDER[i]);
    }
  }

  // The sticky topbar's height is decided here and nowhere else — it depends on whether the tab bar
  // collapsed, which depends on the rendered tab labels, so no media query can state it. Publish the
  // measured value so anything that has to sit below the bar reads one live number instead of keeping its
  // own frozen guess (profiles.css carried `top:91px`/`83px`, an 8px step where the padding step is 12px,
  // and neither accounted for the ~40px the collapse removes).
  function publishTopbarHeight() {
    var bar = document.querySelector('.topbar');
    if (!bar || !document.documentElement.style.setProperty) return;
    var h = Math.round(bar.getBoundingClientRect().height * 100) / 100;
    if (h > 0) document.documentElement.style.setProperty('--topbar-h', h + 'px');
  }

  function fitAll() { fitNav(); fitHeader(); publishTopbarHeight(); }   // nav first — the hamburger changes the header's width
  window.PanelHeaderFit = { refit: fitAll };

  if (burger) {
    burger.setAttribute('aria-expanded', 'false');
    if (nav && !nav.id) nav.id = 'primary-navigation';
    burger.setAttribute('aria-controls', nav && nav.id ? nav.id : 'primary-navigation');
    burger.addEventListener('click', function (e) {
      e.stopPropagation();                       // don't let the document handler immediately re-close it
      setMenuOpen(!body.classList.contains('nav-open'));
    });
    document.addEventListener('click', function () { setMenuOpen(false); }); // outside tap closes
    document.addEventListener('keydown', function (event) {
      if (event.key !== 'Escape' || !body.classList.contains('nav-open')) return;
      setMenuOpen(false);
      burger.focus();
    });
  }
  var t;
  window.addEventListener('resize', function () { clearTimeout(t); t = setTimeout(fitAll, 120); });
  fitAll();
})();

/* Testing aid: when ?theme= pinned the UI theme (see the inline head script), carry the param across
   the tab links so clicking through the pages keeps the forced theme. No persistence — drop the param
   (or open a clean URL) and the browser preference rules again. */
(function () {
  var forced = document.documentElement.getAttribute("data-theme");
  if (!forced) return;
  document.querySelectorAll(".nav a[href]").forEach(function (a) {
    var u = new URL(a.getAttribute("href"), document.baseURI);
    u.searchParams.set("theme", forced);
    a.setAttribute("href", u.href);
  });
})();
