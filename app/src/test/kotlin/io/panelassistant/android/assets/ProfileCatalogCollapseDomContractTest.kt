package io.panelassistant.android.assets

import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.testsupport.Node
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reporter of Issue #138 ended bring-up with four `community.yc-sm10p` revisions in the picker,
 * separated only by a sha256 prefix. These are executable assertions about the shipped renderer, not
 * about a reimplementation of it: the complete `profiles.js` page is initialized through its transport boundary, so a change
 * that reintroduces flat look-alike siblings fails here.
 */
class ProfileCatalogCollapseDomContractTest {
    @Test
    fun repeatedRevisionsOfOneProfileCollapseToOneOfferedEntryWithoutLosingTheSelection() {
        Node.assumeAvailable()
        // Source-text reason: executes the shipped profiles.js; asserts DOM behaviour.
        val asset = TestSources.asset("profiles.js").absolutePath
        val script = """
            ${ProfilesAssetHarness.SCRIPT}
            (async () => {
            const refKey = ref => ref ? ref.id + '@' + ref.revision : '';
            let rig, select, toggle;
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

            async function render(profiles, selected, showAll) {
              rig = await loadProfiles(profiles);
              select = rig.node('profile-select');
              toggle = rig.node('profile-revisions');
              if (selected) {
                toggle.checked = true; toggle.dispatch('change');
                select.value = refKey(selected); select.dispatch('change');
                await flush();
              }
              toggle.checked = !!showAll; toggle.dispatch('change');
              const loaded = rig.requests.filter(request => request.op === 'profiles.revision').at(-1);
              assert.equal(refKey(loaded.args), select.value, 'loaded YAML agrees with the picker');
              return rig.options();
            }

            const catalog = [bundled, first, second, third, fourth];

            // Collapsed: one entry per profile, not four siblings. The active revision represents
            // its own group, so the panel's running configuration is what the user sees.
            let options = await render(catalog, third.ref, false);
            assert.equal(options.length, 2, "expected one entry per profile, got " + options.length);
            assert.equal(options[1].value, refKey(third.ref), "active revision did not represent its group");
            assert.equal(select.value, refKey(third.ref), "select.value lost the active revision");

            // Every superseded revision is still reachable, so the collapse hides history, never
            // removes it. This is the invariant that keeps a pinned-but-superseded revision usable.
            options = await render(catalog, third.ref, true);
            assert.equal(options.length, 5, "expanded picker lost revisions: " + options.length);
            const expanded = options.map(function (option) { return option.value; });
            [first, second, third, fourth].forEach(function (item) {
              assert.ok(expanded.includes(refKey(item.ref)), "revision missing when expanded");
            });

            // A revision the user has selected is always offered even while collapsed, because an
            // option that is not appended cannot be assigned to select.value.
            options = await render(catalog, first.ref, false);
            const collapsedValues = options.map(function (option) { return option.value; });
            assert.ok(collapsedValues.includes(refKey(first.ref)), "collapse hid the current selection");
            assert.equal(select.value, refKey(first.ref), "select.value lost the current selection");

            // With nothing selected and nothing active, the newest import represents the group.
            const inactive = [bundled, revision("c1".repeat(32), 1757000000000), revision("c2".repeat(32), 1757300000000)];
            options = await render(inactive, null, false);
            assert.equal(options.length, 2, "inactive duplicates did not collapse");
            assert.equal(options[1].value, refKey(inactive[2].ref), "newest import did not represent the group");

            // Options must say which revision they are without a hash lookup: version and import
            // time first, hash tail last so same-version same-minute revisions stay distinct.
            const label = options[1].textContent;
            assert.ok(label.includes("0.1.1"), "declared version missing from the option: " + label);
            assert.ok(label.includes("c2c2c2c2c2"), "hash tail missing from the option: " + label);
            assert.ok(label.includes(new Date(1757300000000).toLocaleString()), "import time missing from the option: " + label);

            // What the panel is running outranks the import clock. Here an older revision is the
            // active one and a newer import sits beside it, with the selection elsewhere entirely,
            // so nothing but the active check can keep the running configuration on screen.
            const staleActive = revision("d1".repeat(32), 1757000000000, { active: true });
            const newerIdle = revision("d2".repeat(32), 1757400000000);
            options = await render([bundled, staleActive, newerIdle], bundled.ref, false);
            assert.equal(options.length, 2, "active-plus-newer pair did not collapse");
            assert.equal(options[1].value, refKey(staleActive.ref), "a newer import displaced the active revision");

            // An absent selection falls back to an option that was actually appended. The
            // first catalog entry here is a superseded revision the collapse hides, so a fallback
            // taken from the whole catalog would assign select.value an option that does not exist.
            const orphanOld = revision("e1".repeat(32), 1757000000000);
            const orphanNew = revision("e2".repeat(32), 1757400000000);
            options = await render([orphanOld, orphanNew], null, false);
            assert.equal(options.length, 1, "orphaned selection did not collapse to one entry");
            assert.equal(select.value, refKey(orphanNew.ref), "fallback assigned an option that was never rendered");

            // Deleting the selected revision reloads the catalogue while the page still remembers
            // its old ref. Both the picker and the next YAML load must move to a rendered revision.
            const deleted = revision("e3".repeat(32), 1757500000000);
            rig = await loadProfiles([orphanOld, orphanNew, deleted], [orphanOld, orphanNew]);
            select = rig.node('profile-select');
            assert.equal(select.value, refKey(deleted.ref), 'delete starts from the selected newest revision');
            assert.equal(rig.node('profile-delete').disabled, false, 'selected inactive revision can be deleted');
            const beforeDelete = rig.requests.length;
            rig.node('profile-delete').dispatch('click');
            assert.equal(rig.node('profile-modal').hidden, false, 'delete confirmation is shown');
            rig.node('profile-modal-confirm').onclick();
            await flush();
            assert.equal(rig.node('profile-modal').hidden, true, 'delete confirmation closed');
            assert.deepEqual(rig.options().map(option => option.value), [refKey(orphanNew.ref)],
              'reload offers only the newest surviving representative');
            assert.equal(select.value, refKey(orphanNew.ref), 'deleted selection falls back to a rendered revision');
            const deleteRequests = rig.requests.slice(beforeDelete);
            const deleteRequest = deleteRequests.find(request => request.op === 'profiles.delete');
            assert.ok(deleteRequest, 'confirmation sends a delete request');
            assert.equal(refKey(deleteRequest.args), refKey(deleted.ref), 'the selected revision was deleted');
            const reloadIndex = deleteRequests.findIndex(request => request.op === 'profiles');
            assert.ok(reloadIndex >= 0, 'delete reloads the catalogue');
            const nextRevision = deleteRequests.slice(reloadIndex + 1).find(request => request.op === 'profiles.revision');
            assert.ok(nextRevision, 'reloaded selection requests its YAML');
            assert.equal(refKey(nextRevision.args), refKey(orphanNew.ref),
              'the next YAML request names the surviving picker revision');

            // A bundled id can list a retired copy beside its successor so that a pinned revision
            // stays resolvable across a core upgrade. Collapsing those would hide a rollback
            // destination, so bundled groups are left whole whatever the toggle says.
            const retiredBundled = Object.assign({}, bundled, { ref: { id: "generic", revision: "b1".repeat(32) }, content_version: "0.0.9" });
            options = await render([bundled, retiredBundled, first, second], null, false);
            const bundledValues = options.map(function (option) { return option.value; });
            assert.ok(bundledValues.includes(refKey(bundled.ref)), "bundled revision was collapsed away");
            assert.ok(bundledValues.includes(refKey(retiredBundled.ref)), "retired bundled revision was collapsed away");
            assert.equal(options.length, 3, "bundled pair did not stay whole beside a collapsed import: " + options.length);

            // A bundled pair is not hidden history, so it must not offer to reveal any: a toggle
            // that appears here would expand to exactly the list already on screen.
            await render([bundled, retiredBundled], null, false);
            assert.ok(!toggle.classList.contains("has-superseded"), "toggle offered to reveal bundled revisions that were never collapsed");
            assert.equal(toggle.disabled, true, "toggle stayed operable with nothing to reveal");

            // The toggle only appears where there is history to reveal.
            await render([bundled], null, false);
            assert.ok(!toggle.classList.contains("has-superseded"), "toggle shown with no superseded revisions");
            assert.equal(toggle.disabled, true, "toggle stayed operable with no superseded revisions");
            await render(catalog, third.ref, false);
            assert.ok(toggle.classList.contains("has-superseded"), "toggle not revealed while revisions are collapsed");
            assert.equal(toggle.disabled, false, "toggle left inoperable while revisions are collapsed");

            })().catch(error => { console.error(error); process.exitCode = 1; });
        """.trimIndent()

        val (code, output) = Node.run("-e", script, asset)

        assertEquals(output, 0, code)
    }
}
