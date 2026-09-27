// API explorer: mirror of PaneldServer's GET /api, which fills the placeholders in assets/api.html and is
// not wrapped in pageShell(). The route ignores X-Panel-Assistant-Embed, so the embedded view is served
// the same document; api.js renders every endpoint from the real openapi.json.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { ASSETS, PANEL, escapeHtml, i18nPayload } from '../harness.mjs';

export const API_HTML = resolve(ASSETS, 'api.html');
export const API_SPEC = resolve(ASSETS, 'openapi.json');
// Production projection prefixes for /api.
export const API_PREFIXES = ['api.', 'configure.hardened.', 'shell.hardened.'];
// A consequential and a conditionally approved endpoint: their forms carry the most controls and labels.
export const EXPANDED_ENDPOINTS = [
  { path: '/api/v1/power-safety/repair', method: 'POST', approval: '' },
  { path: '/api/v1/config', method: 'POST', approval: 'conditional' },
];

/** Mirror of the /api route: title, language, localized back link and the i18n projection. */
export function apiFrame({ catalogues, locale }) {
  const localizedHref = (path) => (locale === 'en' ? path : `${path}?lang=${locale}`);
  return readFileSync(API_HTML, 'utf8')
    .replace('<title>ha-paneld · REST API</title>', `<title>${escapeHtml(`${PANEL.name} · REST API`)}</title>`)
    .replace('__API_LANG__', escapeHtml(locale))
    .replace('__API_BACK_HREF__', escapeHtml(localizedHref('./')))
    .replace('__API_I18N_PAYLOAD__', i18nPayload(catalogues, locale, API_PREFIXES));
}

/** Opens EXPANDED_ENDPOINTS in the rendered explorer (runs in the page). */
export function openEndpoints(targets) {
  const endpoints = [...document.querySelectorAll('details.ep')];
  targets.forEach((target) => {
    const endpoint = endpoints.find((node) =>
      node.querySelector('.path')?.textContent === target.path &&
      node.querySelector('.m')?.textContent === target.method);
    if (!endpoint) throw new Error(`missing layout endpoint ${target.method} ${target.path}`);
    endpoint.open = true;
  });
}

export default {
  name: 'api',
  path: '/api',
  html(context) { return apiFrame(context); },
  api(url) {
    if (url.pathname === '/api/v1/openapi.json') return { __raw: true, type: 'application/json; charset=utf-8', body: readFileSync(API_SPEC) };
    return {};
  },
  async ready(frame) {
    await frame.waitForFunction(() => document.querySelectorAll('details.ep').length > 20);
  },
  async exercise(frame) {
    await frame.evaluate(openEndpoints, EXPANDED_ENDPOINTS);
  },
};
