package io.panelassistant.android.testsupport

/**
 * The real translation bridge for node-driven asset tests, loaded the way the page shell loads it
 * before every page script: assets/i18n.js with no ha-i18n payload, so every lookup answers its
 * English fallback. Paste it into a node script after `window` exists and before the page script
 * runs; the script must have `vm` in scope and `process.argv[1]` pointing at a file in assets/.
 * Browser and .mjs fixtures use tools/test/fixtures/i18n-bridge.mjs instead.
 */
object NodeI18n {
    const val REAL_BRIDGE: String =
        "(()=>{const d=global.document;global.document={getElementById:()=>null};" +
            "vm.runInThisContext(require('fs').readFileSync(require('path').join(require('path').dirname(process.argv[1]),'i18n.js'),'utf8'));" +
            "global.document=d;})();"
}
