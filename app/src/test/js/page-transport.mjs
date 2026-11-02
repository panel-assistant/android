import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';

/**
 * The real page transport (assets/transport.js), loaded the way the page shell loads it: after `window`
 * and `document` exist and before the page script, from the page script's own assets directory. Its HTTP
 * implementation then drives the harness's fake `fetch` exactly as the page does in a browser; fake answers
 * get a full Response shape from complete-fake-responses.cjs.
 */
export function loadTransport(pageScript) {
  createRequire(import.meta.url)('./complete-fake-responses.cjs');
  const file = path.join(path.dirname(pageScript), 'transport.js');
  vm.runInThisContext(fs.readFileSync(file, 'utf8'), { filename: file });
}

// Minimal text DOM for the entities fixtures. Supplying createTextNode selects the same rendering
// branch browsers execute; textContent includes descendants and clearing a node removes its children.
export function textElement(name = '') {
  let text = '', html = '';
  const listeners = new Map(), attributes = new Map();
  return {
    name, dataset: {}, className: '', checked: false, disabled: false, value: '', hidden: false,
    children: [], parentElement: null,
    get textContent() { return text + this.children.map(child => child.textContent).join(''); },
    set textContent(value) { text = String(value); html = ''; this.children.length = 0; },
    get innerHTML() { return html; },
    set innerHTML(value) { html = String(value); text = ''; this.children.length = 0; },
    classList: { toggle() {}, remove() {}, add() {} },
    setAttribute(key, value) { attributes.set(key, String(value)); },
    getAttribute(key) { return attributes.get(key) ?? null; },
    appendChild(child) { child.parentElement = this; this.children.push(child); return child; },
    matches(selector) { return selector.startsWith('.') ? this.className.split(/\s+/).includes(selector.slice(1)) : this.name === selector; },
    closest(selector) { return this.matches(selector) ? this : this.parentElement?.closest(selector) ?? null; },
    querySelectorAll(selector) { return this.children.flatMap(child => [...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector)]); },
    querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; },
    addEventListener(type, listener) { listeners.set(type, listener); },
    async fire(type, event = {}) { return listeners.get(type)?.call(this, event); },
  };
}
