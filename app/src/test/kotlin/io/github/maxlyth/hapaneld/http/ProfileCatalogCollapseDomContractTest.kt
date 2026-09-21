package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The reporter of Issue #138 ended bring-up with four `community.yc-sm10p` revisions in the picker,
 * separated only by a sha256 prefix. These are executable assertions about the shipped renderer, not
 * about a reimplementation of it: the real `profiles.js` source is sliced and evaluated, so a change
 * that reintroduces flat look-alike siblings fails here.
 */
class ProfileCatalogCollapseDomContractTest {
    @Test
    fun repeatedRevisionsOfOneProfileCollapseToOneOfferedEntryWithoutLosingTheSelection() {
        assumeTrue("Node.js is required for the executable profiles.js DOM contract", nodeAvailable())
        val asset = File("src/main/assets/profiles.js").absolutePath
        val script = """
            const fs = require("fs");
            const source = fs.readFileSync(process.argv[1], "utf8");
            const start = source.indexOf("  function catalogGroups(");
            const end = source.indexOf("  function badge(label, kind) {", start);
            if (start < 0 || end < 0) throw new Error("catalog renderer not found");

            function element(tag) {
              return {
                tagName: String(tag).toUpperCase(),
                childNodes: [],
                hidden: false,
                label: "",
                value: "",
                checked: false,
                appendChild: function (child) { this.childNodes.push(child); return child; },
                closest: function () { return null; },
                set textContent(value) { if (!String(value)) this.childNodes = []; this._text = String(value); },
                get textContent() { return this._text || ""; }
              };
            }

            const select = element("select");
            const toggle = element("input");
            global.document = { createElement: element };
            global.byId = function (id) {
              if (id === "profile-select") return select;
              if (id === "profile-revisions") return toggle;
              return null;
            };
            global.string = function (value) { return value == null ? "" : String(value); };
            global.own = Object.prototype.hasOwnProperty;
            global.t = function (key, fallback, values) {
              return String(fallback).replace(/\{(\w+)\}/g, function (whole, name) {
                return values && name in values ? String(values[name]) : whole;
              });
            };
            global.refKey = function (ref) { return ref ? String(ref.id) + "@" + String(ref.revision) : ""; };
            let badges = 0;
            let actions = 0;
            global.renderBadges = function () { badges++; };
            global.updateActions = function () { actions++; };
            const model = { profiles: [], selected: null, showAllRevisions: false };
            global.model = model;
            eval(source.slice(start, end));

            // The reporter's catalog: one bundled profile, plus four revisions of one imported
            // profile from four successive edits. Only the third was ever activated.
            function revision(revisionId, importedAt, extra) {
              return Object.assign({
                ref: { id: "community.yc-sm10p", revision: revisionId },
                display_name: "YC-SM10P",
                origin: "imported",
                content_version: "0.1.1",
                imported_at: importedAt,
                compatible: true,
                active: false,
                selected: false
              }, extra || {});
            }
            const bundled = {
              ref: { id: "generic", revision: "b0".repeat(32) },
              display_name: "Generic",
              origin: "bundled",
              content_version: "0.1.0",
              imported_at: null,
              compatible: true,
              active: false,
              selected: false
            };
            const first = revision("a1".repeat(32), 1757000000000);
            const second = revision("a2".repeat(32), 1757100000000);
            const third = revision("a3".repeat(32), 1757200000000, { active: true, selected: true });
            const fourth = revision("a4".repeat(32), 1757300000000);

            function render(profiles, selected, showAll) {
              select.childNodes = [];
              model.profiles = profiles;
              model.selected = selected;
              model.showAllRevisions = !!showAll;
              renderCatalog();
              return offered();
            }
            function offered() {
              const out = [];
              select.childNodes.forEach(function (node) {
                if (node.tagName === "OPTGROUP") node.childNodes.forEach(function (child) { out.push(child); });
                else out.push(node);
              });
              return out;
            }

            const catalog = [bundled, first, second, third, fourth];

            // Collapsed: one entry per profile, not four siblings. The active revision represents
            // its own group, so the panel's running configuration is what the user sees.
            let options = render(catalog, third.ref, false);
            if (options.length !== 2) throw new Error("expected one entry per profile, got " + options.length);
            if (options[1].value !== global.refKey(third.ref)) throw new Error("active revision did not represent its group");
            if (select.value !== global.refKey(third.ref)) throw new Error("select.value lost the active revision");
            if (model.selected !== third.ref) throw new Error("model.selected was rewritten during collapse");

            // Every superseded revision is still reachable, so the collapse hides history, never
            // removes it. This is the invariant that keeps a pinned-but-superseded revision usable.
            options = render(catalog, third.ref, true);
            if (options.length !== 5) throw new Error("expanded picker lost revisions: " + options.length);
            const expanded = options.map(function (option) { return option.value; });
            [first, second, third, fourth].forEach(function (item) {
              if (expanded.indexOf(global.refKey(item.ref)) < 0) throw new Error("revision missing when expanded");
            });

            // A revision the user has selected is always offered even while collapsed, because an
            // option that is not appended cannot be assigned to select.value and the control would
            // silently disagree with model.selected.
            options = render(catalog, first.ref, false);
            const collapsedValues = options.map(function (option) { return option.value; });
            if (collapsedValues.indexOf(global.refKey(first.ref)) < 0) throw new Error("collapse hid the current selection");
            if (select.value !== global.refKey(first.ref)) throw new Error("select.value lost the current selection");

            // With nothing selected and nothing active, the newest import represents the group.
            const inactive = [bundled, revision("c1".repeat(32), 1757000000000), revision("c2".repeat(32), 1757300000000)];
            options = render(inactive, null, false);
            if (options.length !== 2) throw new Error("inactive duplicates did not collapse");
            if (options[1].value !== global.refKey(inactive[2].ref)) throw new Error("newest import did not represent the group");

            // Options must say which revision they are without a hash lookup: version and import
            // time first, hash tail last so same-version same-minute revisions stay distinct.
            const label = options[1].textContent;
            if (label.indexOf("0.1.1") < 0) throw new Error("declared version missing from the option: " + label);
            if (label.indexOf("c2c2c2c2c2") < 0) throw new Error("hash tail missing from the option: " + label);
            if (label.indexOf(new Date(1757300000000).toLocaleString()) < 0) throw new Error("import time missing from the option: " + label);

            // What the panel is running outranks the import clock. Here an older revision is the
            // active one and a newer import sits beside it, with the selection elsewhere entirely,
            // so nothing but the active check can keep the running configuration on screen.
            const staleActive = revision("d1".repeat(32), 1757000000000, { active: true });
            const newerIdle = revision("d2".repeat(32), 1757400000000);
            options = render([bundled, staleActive, newerIdle], bundled.ref, false);
            if (options.length !== 2) throw new Error("active-plus-newer pair did not collapse");
            if (options[1].value !== global.refKey(staleActive.ref)) throw new Error("a newer import displaced the active revision");

            // A stale or absent selection falls back to an option that was actually appended. The
            // first catalog entry here is a superseded revision the collapse hides, so a fallback
            // taken from the whole catalog would assign select.value an option that does not exist.
            const orphanOld = revision("e1".repeat(32), 1757000000000);
            const orphanNew = revision("e2".repeat(32), 1757400000000);
            options = render([orphanOld, orphanNew], { id: "community.yc-sm10p", revision: "ff".repeat(32) }, false);
            if (options.length !== 1) throw new Error("orphaned selection did not collapse to one entry");
            if (select.value !== global.refKey(orphanNew.ref)) throw new Error("fallback assigned an option that was never rendered");
            if (global.refKey(model.selected) !== global.refKey(orphanNew.ref)) throw new Error("model.selected disagrees with the control");

            // The toggle only appears where there is history to reveal.
            render([bundled], null, false);
            if (toggle.hidden !== true) throw new Error("toggle shown with no superseded revisions");
            render(catalog, third.ref, false);
            if (toggle.hidden !== false) throw new Error("toggle hidden while revisions are collapsed");

            if (badges < 1 || actions < 1) throw new Error("renderCatalog stopped refreshing badges and actions");
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
