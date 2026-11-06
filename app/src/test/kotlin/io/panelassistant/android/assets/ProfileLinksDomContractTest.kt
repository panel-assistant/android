package io.panelassistant.android.assets

import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.testsupport.Node
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileLinksDomContractTest {
    @Test
    fun profileLinksRenderOnlyCompatibleBoundedSafeHttpsDestinations() {
        Node.assumeAvailable()
        // Source-text reason: executes the shipped profiles.js; asserts DOM behaviour.
        val asset = TestSources.asset("profiles.js").absolutePath
        val script = """
            ${ProfilesAssetHarness.SCRIPT}
            (async () => {
            async function render(summary) {
              const rig = await loadProfiles([Object.assign({ref: {id: 'sample', revision: 'a'.repeat(64)}, origin: 'imported'}, summary)]);
              const root = rig.node('profile-links');
              return {root, links: root.childNodes};
            }
            async function reject(summary, name) {
              const {root, links} = await render(summary);
              assert.equal(links.length, 0, name);
              assert.equal(root.hidden, true, name);
            }
            const {root, links} = await render({
              compatible: true,
              links: [{label: "Product page", url: "https://vendor.example/panel"}]
            });
            assert.equal(links.length, 1, "valid link was not rendered");
            assert.equal(root.hidden, false);
            const link = links[0];
            assert.equal(link.tagName, "A");
            assert.equal(link.href, "https://vendor.example/panel", "wrong destination");
            assert.equal(link.target, "_blank");
            assert.equal(link.rel, "noopener noreferrer");
            assert.equal(link.referrerPolicy, "no-referrer");
            assert.equal(link.childNodes.length, 3, "label and hostname were not isolated");
            assert.equal(link.childNodes[0].tagName, "BDI");
            assert.equal(link.childNodes[0].dir, "auto");
            assert.equal(link.childNodes[0].textContent, "Product page");
            assert.equal(link.childNodes[1].textContent, " · ", "separator missing");
            assert.equal(link.childNodes[2].tagName, "BDI");
            assert.equal(link.childNodes[2].dir, "ltr");
            assert.equal(link.childNodes[2].textContent, "vendor.example");

            await reject({compatible: false, links: [{label: "Valid", url: "https://example.com/"}]}, "incompatible preview");
            await reject({compatible: true, links: [{label: "Script", url: "javascript:alert(1)"}]}, "invalid scheme");
            await reject({compatible: true, links: [{label: "Credential", url: "https://user:secret@example.com/"}]}, "credential URL");
            await reject({compatible: true, links: [{label: "Relative", url: "/docs/panel"}]}, "relative URL");
            await reject({compatible: true, links: [{label: "Oversize", url: "https://example.com/" + "x".repeat(500)}]}, "oversize URL");
            await reject({compatible: true, links: [{label: "Trusted\u202eelpmaxe", url: "https://example.com/"}]}, "bidi label");
            await reject({compatible: true, links: [{label: "Hidden\u200djoiner", url: "https://example.com/"}]}, "format-control label");
            })().catch(error => { console.error(error); process.exitCode = 1; });
        """.trimIndent()

        val (code, output) = Node.run("-e", script, asset)

        assertEquals(output, 0, code)
    }
}
