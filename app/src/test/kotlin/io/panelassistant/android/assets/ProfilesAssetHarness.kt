package io.panelassistant.android.assets

/** Loads the complete shipped page and drives its registered DOM handlers and transport boundary. */
internal object ProfilesAssetHarness {
    const val SCRIPT = """
        const fs = require('fs'), vm = require('vm'), assert = require('node:assert/strict');
        const flush = () => new Promise(resolve => setImmediate(resolve));
        async function loadProfiles(profiles, afterDeleteProfiles) {
          let catalog = profiles;
          function element(tag) {
            let text = '';
            return {
              tagName: String(tag).toUpperCase(), childNodes: [], hidden: false, disabled: false,
              value: '', checked: false, style: {}, listeners: {},
              appendChild(child) { this.childNodes.push(child); return child; },
              set textContent(value) { text = String(value); this.childNodes = []; if (this.tagName === 'SELECT') this.value = ''; },
              get textContent() { return text || this.childNodes.map(child => child.textContent).join(''); },
              addEventListener(type, fn) { this.listeners[type] = fn; },
              dispatch(type) { assert.equal(typeof this.listeners[type], 'function', type + ' listener registered'); this.listeners[type].call(this, {target: this}); },
              classList: {names: {}, toggle(name, on) { this.names[name] = !!on; }, contains(name) { return !!this.names[name]; }},
              closest() { return null; }, querySelector() { return null; },
              setAttribute() {}, removeAttribute() {}, focus() {}
            };
          }
          const nodes = {}, requests = [];
          const node = id => nodes[id] || (nodes[id] = element(id === 'profile-select' ? 'select' : 'div'));
          node('profile-modal').hidden = true;
          const context = {URL, console, setTimeout, clearTimeout,
            document: {documentElement: {lang: 'en'}, getElementById: node, createElement: element,
              createTextNode: textContent => ({textContent}), querySelector() { return null; }},
            addEventListener() {}, requestAnimationFrame() { return 1; }, cancelAnimationFrame() {},
            HaI18n: {has() { return false; }, t(key, fallback, values) {
              return String(fallback).replace(/\{(\w+)\}/g, (whole, name) => values && name in values ? values[name] : whole);
            }, pageFailure(message) { assert.fail(message); }},
            PaneldTransport: {request(op, args) {
              requests.push({op, args});
              let result = {};
              if (op === 'profiles') result = {catalog_revision: 1, profiles: catalog, status: {selection: {mode: 'pinned'}}};
              else if (op === 'profiles.delete') {
                assert.ok(afterDeleteProfiles, 'delete response catalogue supplied');
                catalog = afterDeleteProfiles;
              }
              else if (op === 'profiles.revision') result = 'id: sample';
              else if (op !== 'profiles.schema' && op !== 'profiles.report') assert.fail('unexpected request ' + op);
              return Promise.resolve({outcome: 'applied', result});
            }}
          };
          context.window = context;
          // Source-text reason: execute the entire shipped asset, with no code extraction or source assertions.
          vm.runInNewContext(fs.readFileSync(process.argv[1], 'utf8'), context);
          await flush();
          assert.match(node('profile-status').textContent, /Viewing immutable revision/);
          assert.ok(requests.some(request => request.op === 'profiles.revision'), 'selected YAML was loaded');
          return {node, requests, options() {
            return node('profile-select').childNodes.flatMap(child => child.tagName === 'OPTGROUP' ? child.childNodes : [child]);
          }};
        }
    """
}
