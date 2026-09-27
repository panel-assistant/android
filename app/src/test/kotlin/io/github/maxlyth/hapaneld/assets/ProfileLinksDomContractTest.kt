package io.github.maxlyth.hapaneld.assets

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProfileLinksDomContractTest {
    @Test
    fun profileLinksRenderOnlyCompatibleBoundedSafeHttpsDestinations() {
        assumeTrue("Node.js is required for the executable profiles.js DOM contract", nodeAvailable())
        // Source-text reason: executes the shipped profiles.js; asserts DOM behaviour.
        val asset = File("src/main/assets/profiles.js").absolutePath
        val script = """
            const fs = require("fs");
            const source = fs.readFileSync(process.argv[1], "utf8");
            const start = source.indexOf("function profileLinkLabelSafe(");
            const end = source.indexOf("function renderBadges(", start);
            if (start < 0 || end < 0) throw new Error("profile link renderer not found");

            function element(tag) {
              let ownText = "";
              return {
                tagName: String(tag).toUpperCase(),
                childNodes: [],
                hidden: false,
                appendChild: function (child) { this.childNodes.push(child); ownText = ""; return child; },
                get textContent() {
                  return ownText || this.childNodes.map(function (child) { return child.textContent || ""; }).join("");
                },
                set textContent(value) { ownText = String(value); this.childNodes = []; }
              };
            }

            const root = element("div");
            global.document = {
              createElement: element,
              createTextNode: function (value) { return { nodeType: 3, textContent: String(value) }; }
            };
            global.byId = function (id) { return id === "profile-links" ? root : null; };
            global.string = function (value) { return value == null ? "" : String(value); };
            eval(source.slice(start, end));

            function render(summary) {
              renderProfileLinks(summary);
              return root.childNodes.slice();
            }
            function reject(summary, name) {
              if (render(summary).length !== 0 || root.hidden !== true) throw new Error(name + " was rendered");
            }

            const links = render({
              compatible: true,
              links: [{label: "Product page", url: "https://vendor.example/panel"}]
            });
            if (links.length !== 1 || root.hidden) throw new Error("valid link was not rendered");
            const link = links[0];
            if (link.tagName !== "A" || link.href !== "https://vendor.example/panel") throw new Error("wrong destination");
            if (link.target !== "_blank" || link.rel !== "noopener noreferrer" || link.referrerPolicy !== "no-referrer") {
              throw new Error("missing navigation isolation");
            }
            if (link.childNodes.length !== 3) throw new Error("label and hostname were not isolated");
            if (link.childNodes[0].tagName !== "BDI" || link.childNodes[0].dir !== "auto" || link.childNodes[0].textContent !== "Product page") {
              throw new Error("label isolation missing");
            }
            if (link.childNodes[1].textContent !== " · ") throw new Error("separator missing");
            if (link.childNodes[2].tagName !== "BDI" || link.childNodes[2].dir !== "ltr" || link.childNodes[2].textContent !== "vendor.example") {
              throw new Error("hostname isolation missing");
            }

            reject({compatible: false, links: [{label: "Valid", url: "https://example.com/"}]}, "incompatible preview");
            reject({compatible: true, links: [{label: "Script", url: "javascript:alert(1)"}]}, "invalid scheme");
            reject({compatible: true, links: [{label: "Credential", url: "https://user:secret@example.com/"}]}, "credential URL");
            reject({compatible: true, links: [{label: "Relative", url: "/docs/panel"}]}, "relative URL");
            reject({compatible: true, links: [{label: "Oversize", url: "https://example.com/" + "x".repeat(500)}]}, "oversize URL");
            reject({compatible: true, links: [{label: "Trusted\u202eelpmaxe", url: "https://example.com/"}]}, "bidi label");
            reject({compatible: true, links: [{label: "Hidden\u200djoiner", url: "https://example.com/"}]}, "format-control label");
        """.trimIndent()

        val process = ProcessBuilder("node", "-e", script, asset)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(output, 0, process.waitFor())
    }

    private fun nodeAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
        process.inputStream.close()
        process.waitFor() == 0
    }.getOrDefault(false)
}
