// In-page layout measurement for the language layout gate. `measureLayout` is serialised into the page
// (or the Panel Assistant sidebar iframe) by Playwright, so it must stay self-contained: no imports, no
// closures over module state. It reports four families of evidence, each keyed by a stable DOM path so a
// localized cell can be compared with English at the same page, view, theme and browser:
//
//   cut      (a) text clipped by its own box or by a clipping ancestor
//   overflow (b) horizontal page overflow and content escaping its card; overlap of controls and labels
//   offscreen(c) an interactive control outside the viewport or clipped out of reach
//   wraps    (d) a control or label whose own text runs onto a second line
//   cards        card heights, for the "no card grows more than about 20% over English" rule
export function measureLayout() {
  const CONTROL = 'button,a[href],input:not([type="hidden"]),select,textarea,summary,[role="button"],[role="tab"],[role="switch"]';
  const LABEL = 'button,.pbtn,[role="tab"],[role="switch"],.nav a,label,th,legend,.card>h2,.card h2';
  const ATOM = `${CONTROL},label,th,h2,h3`;
  const width = document.documentElement.clientWidth || innerWidth;
  const round = (value) => Math.round(value * 10) / 10;

  const hiddenByAncestor = (node) => !!node.closest('[hidden],.sr-only,template,noscript');
  const visible = (node) => {
    if (!(node instanceof Element) || hiddenByAncestor(node)) return false;
    // Content of a closed <details> keeps a layout box in some engines but is not rendered.
    if (typeof node.checkVisibility === 'function' && !node.checkVisibility()) return false;
    const style = getComputedStyle(node);
    if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse' || Number(style.opacity) === 0) return false;
    const box = node.getBoundingClientRect();
    return box.width > 1 && box.height > 1;
  };
  const anchorOf = (node) => node.id ? `#${node.id}`
    : node.dataset && (node.dataset.layoutKey || node.dataset.configGroup)
      ? `[${node.dataset.layoutKey ? 'data-layout-key' : 'data-config-group'}="${node.dataset.layoutKey || node.dataset.configGroup}"]`
      : null;
  const keyOf = (node) => {
    const parts = [];
    for (let current = node; current && current !== document.documentElement; current = current.parentElement) {
      const anchor = anchorOf(current);
      if (anchor) { parts.unshift(anchor); break; }
      let index = 1;
      for (let sibling = current.previousElementSibling; sibling; sibling = sibling.previousElementSibling) {
        if (sibling.tagName === current.tagName) index += 1;
      }
      parts.unshift(`${current.tagName.toLowerCase()}:nth-of-type(${index})`);
    }
    return parts.join('>');
  };
  const textOf = (node) => (node.innerText || node.textContent || node.value || '').replace(/\s+/g, ' ').trim().slice(0, 80);
  const clipsX = (style) => style.overflowX === 'hidden' || style.overflowX === 'clip';
  const clipsY = (style) => style.overflowY === 'hidden' || style.overflowY === 'clip';
  const scrolls = (style) => /(auto|scroll)/.test(style.overflowX + style.overflowY);
  const insideScroller = (node, stop) => {
    for (let current = node.parentElement; current && current !== stop; current = current.parentElement) {
      if (scrolls(getComputedStyle(current))) return true;
    }
    return false;
  };
  const clippingAncestor = (node) => {
    for (let current = node.parentElement; current && current !== document.body; current = current.parentElement) {
      const style = getComputedStyle(current);
      if (scrolls(style)) return null;              // reachable by scrolling that container
      if (clipsX(style) || clipsY(style)) return { node: current, style };
    }
    return null;
  };
  const scrollerOf = (node) => {
    for (let current = node.parentElement; current && current !== document.body; current = current.parentElement) {
      if (scrolls(getComputedStyle(current))) return current;
    }
    return null;
  };
  const directText = (node) => [...node.childNodes].some((child) => child.nodeType === 3 && child.textContent.trim());
  const textRects = (node) => {
    const rects = [];
    const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT);
    for (let text = walker.nextNode(); text; text = walker.nextNode()) {
      if (!text.textContent.trim()) continue;
      let blocked = false;
      for (let parent = text.parentElement; parent && parent !== node; parent = parent.parentElement) {
        const display = getComputedStyle(parent).display;
        if (parent.tagName === 'SMALL' || !/^inline/.test(display) || !visible(parent)) { blocked = true; break; }
      }
      if (blocked) continue;
      const range = document.createRange();
      range.selectNodeContents(text);
      for (const rect of range.getClientRects()) if (rect.width > 0.5 && rect.height > 0.5) rects.push(rect);
    }
    return rects;
  };

  const all = [...document.body.querySelectorAll('*')].filter(visible);
  const cut = [];
  const overflow = [];
  const offscreen = [];
  const wraps = [];
  const overlaps = [];
  const cards = {};

  // (b) the page itself must not scroll sideways.
  const pageOverflow = Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - width;
  if (pageOverflow > 1) {
    const culprits = all.filter((node) => node.getBoundingClientRect().right > width + 1 && ![...node.children].some((child) => visible(child) && child.getBoundingClientRect().right > width + 1));
    overflow.push({ key: 'page', text: `page scrolls sideways by ${round(pageOverflow)}px`, culprits: culprits.slice(0, 4).map(keyOf) });
  }

  for (const node of all) {
    // (a) own-box clipping: ellipsis, fixed height, or clip.
    if (directText(node) || node.matches('button,.pbtn')) {
      const style = getComputedStyle(node);
      const clippedX = clipsX(style) && node.scrollWidth > node.clientWidth + 1;
      const clippedY = clipsY(style) && node.scrollHeight > node.clientHeight + 1;
      if (clippedX || clippedY) cut.push({ key: keyOf(node), text: textOf(node), by: 'self' });
      else {
        const clip = clippingAncestor(node);
        if (clip) {
          const box = clip.node.getBoundingClientRect();
          // Text a clipping box hides where it also lies outside an enclosing scroll container's view
          // (a sticky editor gutter, say) is reached by scrolling that container, not cut off.
          const scroller = scrollerOf(clip.node);
          const view = scroller && scroller.getBoundingClientRect();
          const outsideView = (rect) => view && (rect.bottom > view.bottom || rect.top < view.top || rect.right > view.right || rect.left < view.left);
          const escapes = textRects(node).some((rect) => !outsideView(rect) && (
            (clipsX(clip.style) && (rect.right > box.right + 1 || rect.left < box.left - 1)) ||
            (clipsY(clip.style) && (rect.bottom > box.bottom + 1 || rect.top < box.top - 1))));
          if (escapes) cut.push({ key: keyOf(node), text: textOf(node), by: keyOf(clip.node) });
        }
      }
    }
    // (c) controls must stay inside the viewport and inside any non-scrolling clip.
    if (node.matches(CONTROL)) {
      const box = node.getBoundingClientRect();
      if (box.right > width + 1 || box.left < -1) offscreen.push({ key: keyOf(node), text: textOf(node), by: 'viewport' });
      else {
        const clip = clippingAncestor(node);
        if (clip) {
          const area = clip.node.getBoundingClientRect();
          const x = box.left + box.width / 2; const y = box.top + box.height / 2;
          if (x < area.left || x > area.right || y < area.top || y > area.bottom) offscreen.push({ key: keyOf(node), text: textOf(node), by: keyOf(clip.node) });
        }
      }
    }
    // (d) controls and labels keep their own text on one line.
    if (node.matches(LABEL)) {
      const tops = [];
      for (const rect of textRects(node)) if (!tops.some((top) => Math.abs(top - rect.top) < rect.height / 2)) tops.push(rect.top);
      // A row header (a th beside td cells in a key/value table) sits in a fixed share of a narrow card;
      // its wrapping is reported separately from column headers and controls.
      const rowHeader = node.tagName === 'TH' && !!node.parentElement && [...node.parentElement.children].some((cell) => cell.tagName === 'TD');
      if (tops.length > 1) wraps.push({ key: keyOf(node), text: textOf(node), lines: tops.length, ...(rowHeader ? { rowHeader: true } : {}) });
    }
  }

  // Cards: containment (b), overlap of controls and labels (b), and height for the growth rule (d).
  const cardNodes = all.filter((node) => node.matches('.card,.wiz,.profile-toolbar,.profile-editor-pane,.profile-inspector,details.ep,.savebar,.topbar'));
  for (const card of cardNodes) {
    if (cardNodes.some((other) => other !== card && other.contains(card) && other.matches('.card,.wiz'))) continue;
    const key = keyOf(card);
    const box = card.getBoundingClientRect();
    if (card.matches('.card,.wiz,details.ep')) cards[key] = { height: round(box.height) };
    const style = getComputedStyle(card);
    const inside = [...card.querySelectorAll('*')].filter(visible);
    if (!clipsX(style)) {
      for (const node of inside) {
        if (insideScroller(node, card)) continue;
        // The element's own box, and the text it paints (a nowrap line overflows without its box moving).
        const clip = clippingAncestor(node);
        const rects = [node.getBoundingClientRect(), ...(directText(node) && !(clip && card.contains(clip.node)) ? textRects(node) : [])];
        const escape = Math.max(...rects.map((rect) => Math.max(rect.right - box.right, box.left - rect.left)));
        if (escape > 1) {
          if ([...node.children].some((child) => visible(child) && (child.getBoundingClientRect().right > box.right + 1 || child.getBoundingClientRect().left < box.left - 1))) continue;
          overflow.push({ key: keyOf(node), text: textOf(node), by: key, amount: round(escape) });
        }
      }
    }
    const atoms = inside.filter((node) => node.matches(ATOM) && !insideScroller(node, card));
    for (let i = 0; i < atoms.length; i += 1) {
      const a = atoms[i].getBoundingClientRect();
      for (let j = i + 1; j < atoms.length; j += 1) {
        if (atoms[i].contains(atoms[j]) || atoms[j].contains(atoms[i])) continue;
        const b = atoms[j].getBoundingClientRect();
        const x = Math.min(a.right, b.right) - Math.max(a.left, b.left);
        const y = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top);
        if (x > 2 && y > 2) overlaps.push({ key: `${keyOf(atoms[i])} <> ${keyOf(atoms[j])}`, text: `${textOf(atoms[i])} <> ${textOf(atoms[j])}` });
      }
    }
  }

  return {
    width, height: innerHeight,
    lang: document.documentElement.lang,
    theme: document.documentElement.getAttribute('data-theme') || '',
    cut, overflow, overlaps, offscreen, wraps, cards,
  };
}
