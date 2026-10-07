// The translation bridge every page shell loads before its page scripts: the ha-i18n payload plus the
// real assets/i18n.js. Fixtures that load page scripts use this instead of stubbing HaI18n.
import { readdirSync, readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../../../app/src/main/assets/i18n.js', import.meta.url), 'utf8');
// Inlined, so its own comment's </script> must not close the element.
const inline = source.replace(/<\/script/gi, '<\\/script');

// The server serves the release locales (one catalogue file each) plus the debug pseudolocale, so the
// pages keep ?lang on their own links for exactly these.
const LOCALES = readdirSync(new URL('../../../app/src/main/assets/i18n/', import.meta.url))
  .filter((name) => name.endsWith('.json')).map((name) => name.slice(0, -5)).concat('en-XA');

function payloadJson({ locale = 'en', locales = LOCALES, strings = {}, ...page } = {}) {
  return JSON.stringify({ locale, locales, strings, ...page });
}

// For a browser fixture's <head>. The payload is the page's projection: locale and strings, plus any
// page fields (install's languages).
export function i18nBridge(payload) {
  return `<script id="ha-i18n" type="application/json">${payloadJson(payload).replace(/</g, '\\u003c')}</script><script>${inline}</script>`;
}

// For a node:vm fixture: the real HaI18n built from the payload. When `calls` is given, each
// translation lookup is recorded as { key, fallback, values } before the real helper answers.
export function i18nHelper(payload, calls) {
  const text = payloadJson(payload);
  const window = { document: { getElementById: (id) => (id === 'ha-i18n' ? { textContent: text } : null) } };
  vm.runInNewContext(source, { window }, { filename: 'i18n.js' });
  const real = window.HaI18n;
  if (!calls) return real;
  return Object.freeze({ ...real, t: (key, fallback, values) => { calls.push(values === undefined ? { key, fallback } : { key, fallback, values }); return real.t(key, fallback, values); } });
}
