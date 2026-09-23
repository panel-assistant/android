package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

/**
 * The guided-setup wizard's safety rules, pinned against the asset source the same way the other
 * `*AssetTest`s pin theirs. Each of these is a discipline with an incident or a design decision behind
 * it, not a style preference — the comments name which.
 */
class SetupWizardAssetTest {
    private val js = listOf(
        File("src/main/assets/setup.js"),
        File("app/src/main/assets/setup.js"),
    ).first { it.isFile }.readText()

    private val css = listOf(
        File("src/main/assets/info.css"),
        File("app/src/main/assets/info.css"),
    ).first { it.isFile }.readText()

    private val english = JSONObject(listOf(
        File("src/main/assets/i18n/en.json"),
        File("app/src/main/assets/i18n/en.json"),
    ).first { it.isFile }.readText()).getJSONObject("strings")

    private fun english(key: String): String = english.getJSONObject(key).getString("text")

    @Test fun theWizardNeverReadsTheRedactedConfigEndpoint() {
        // GET /api/v1/config blanks every secret, and a POST assembled from its output has previously
        // wiped a panel's credentials. The wizard's guarantee is structural: it never fetches it at all —
        // its reads are the journey and the read-only discovery suggestions, nothing else.
        val fetchTargets = Regex("""fetch\((\"[^\"]*\"|[A-Za-z_]+)""").findAll(js).map { it.groupValues[1] }.toSet()
        assertTrue(fetchTargets.contains("\"api/v1/setup\""))
        assertTrue(fetchTargets.contains("\"api/v1/config/discovery\""))
        assertTrue("POSTs must flow through the one helper", fetchTargets.contains("path"))
        assertFalse("the wizard must never GET /api/v1/config", fetchTargets.contains("\"api/v1/config\""))
        // The config literal may appear only as a postForm destination.
        Regex("\"api/v1/config\"").findAll(js).forEach { m ->
            val lead = js.substring(maxOf(0, m.range.first - 12), m.range.first)
            assertTrue("\"api/v1/config\" used outside postForm(", lead.contains("postForm("))
        }
    }

    @Test fun onlyTheWizardExplicitlyClaimsGuidedSetupPresence() {
        val journeyFetch = js.substring(js.indexOf("function getJourney()"))
            .substringBefore("function postForm(")
        assertTrue(journeyFetch.contains("\"X-ha-paneld-setup-presence\": \"active\""))
        assertTrue(journeyFetch.contains("fetch(\"api/v1/setup\""))
    }

    @Test fun aBlankPasswordIsNeverSentAndNeverPrefilled() {
        // Blank means "keep the current password" server-side; sending it anyway would depend on that
        // quirk forever, and pre-filling would require reading a secret back, which is impossible by the
        // rule above. The field also opts out of browser autofill so a laptop's password manager cannot
        // quietly insert an unrelated credential into an MQTT field.
        assertTrue(js.contains("if (typed.mqtt_password) fields.mqtt_password"))
        assertTrue(js.contains("autocomplete: \"off\""))
        val pwField = js.substring(js.indexOf("field(\"mqtt_password\""))
            .substringBefore("details")
        assertFalse("the password field must never carry a value", pwField.contains("value:"))
    }

    @Test fun savedIsNeverPresentedAsWorking() {
        // POST /config returns before MQTT verification runs. The wizard must flow into a verifying
        // phase with an honesty deadline — a point where it stops presenting retries as progress — and
        // a user-visible exit, because an indefinite wait with no way out is where users start clicking.
        assertTrue(js.contains("verify = { t0: Date.now()"))
        // Tightened from 30s once the pre-flight + first-configuration fast path made a healthy first
        // save connect in single-digit seconds: past twenty, something is genuinely wrong.
        assertTrue("the 20s honesty ceiling is the contract", js.contains("t > 20000"))
        assertTrue(js.contains("Cancel and edit these details"))
        assertTrue("fields lock during save/verify", js.contains("fs.disabled = locked"))
    }

    @Test fun asyncStatusIsAccessibleAndCalm() {
        assertTrue(js.contains("\"aria-live\": \"polite\""))
        // Announce transitions, not poll ticks — a 2s poll into an aria-live region is a metronome.
        assertTrue(js.contains("if (live.textContent !== text)"))
        assertFalse("popups are never part of the journey", js.contains("window.open"))
        // The breathing dot must degrade to a static one; the TEXT carries the information either way.
        assertTrue(css.contains("@media (prefers-reduced-motion:reduce){.wiz-dots li.busy::before,.wiz-status::before{animation:none"))
    }

    @Test fun theSignInStepIsHonestAboutAccountIdentity() {
        // Browser sign-in is the recommended route (it shows the HA login and lets you pick the account),
        // and the inherit-your-session case is softened to "may" because it only happens when this browser
        // already holds a Home Assistant session. Must stay jargon-free.
        val signin = js.substring(js.indexOf("function signinCard"))
        assertTrue(
            "browser route must be the recommended one",
            signin.indexOf("setup.sign_in.recommended") < signin.indexOf("setup.sign_in.panel.title"),
        )
        val visibleCopy = listOf(
            "setup.sign_in.browser.explanation",
            "setup.sign_in.browser.account_note",
            "setup.sign_in.panel.explanation",
        ).joinToString(" ", transform = ::english)
        assertTrue(visibleCopy.contains("the panel may connect as you"))
        assertFalse("must not overstate the inherited-session case", visibleCopy.contains("connect as YOU"))
        listOf("OAuth", "session", "cookie", "private window", "incognito").forEach {
            assertFalse("\"$it\" is jargon for this audience", visibleCopy.contains(it, ignoreCase = it != "OAuth"))
        }
    }

    @Test fun aTransientNetworkFailureNeverShowsRawBrowserErrorText() {
        // The user saw Safari's raw "Load failed" on the wizard. A network-level fetch rejection carries a
        // browser-specific TypeError message that must never reach the UI; postForm normalizes it.
        assertTrue(js.contains("Couldn’t reach the panel"))
        assertFalse(js.contains("Load failed"))
    }

    @Test fun aReArmedJourneyReadsAsARepairNeverAsAFactoryReset() {
        // A blank password field on a re-armed panel is exactly what makes a returning user believe
        // everything they built is gone. The repair banner must say otherwise BEFORE the fields appear,
        // and the numbered journey must vanish — step dots imply starting over.
        assertTrue(js.contains("nothing has been reset"))
        assertTrue("repair mode must hide the progress strip", js.contains("if (journey.repair) { dotsHost.textContent = \"\"; dotsHost.hidden = true; return; }"))
    }

    @Test fun theDashboardIsChosenThroughANativeSelectBeforeTheFilterQuestion() {
        // The dashboard choice must precede the entity filter because the filter's learned list is keyed
        // by the dashboard path. The control is a NATIVE select, deliberately: two custom pickers were
        // tried on hardware — an open icon list (swamped the page, buried the Area field) and a portal
        // popup (escaped the card and viewport, stole wheel scrolling) — and the browser's own control
        // beat both. Grouping survives as optgroups; icons did not, by design.
        assertTrue(js.contains("function homeDashboardCard()"))
        assertTrue(js.contains("Select the HA dashboard for this panel"))
        assertTrue(js.contains("fetch(\"api/v1/config/home-dashboards\""))
        assertTrue(js.contains("el(\"optgroup\""))
        assertFalse("the custom picker widget is deleted, not dormant", js.contains("haDashboardPicker"))
        assertFalse(
            "the wizard page must not load the deleted picker asset",
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt")
                .let { if (it.isFile) it else File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt") }
                .readText().contains("dashboard-picker.js"),
        )
        // Preselection: an account default's CONCRETE dashboard row comes selected (confirming it writes
        // an explicit path); with no default the placeholder keeps the button waiting for a real choice.
        assertTrue(js.contains("var preselect = current || (def.explicit ? def.path : null);"))
        // The demotion rule: with no real server-side default on the account, "follow the default" is not
        // the recommendation — the user is asked to nominate a dashboard.
        assertTrue(js.contains("This account has no default dashboard set"))
        // Setting before answer, same load-bearing order as the filter step: the config POST re-targets
        // the entity catalog so the filter question that follows counts against the right dashboard.
        val answer = js.substring(js.indexOf("function answerHomeDashboard("))
            .substringBefore("/* ---------- step 5")
        assertTrue(
            "the config POST must precede the answer POST",
            answer.indexOf("api/v1/config") < answer.indexOf("api/v1/setup/home-dashboard"),
        )
        // An empty dashboard list means "could not ask Home Assistant", never a dead end.
        assertTrue(js.contains("Couldn’t fetch the dashboard list from Home Assistant yet"))
        assertTrue(js.contains("Try again"))
    }

    @Test fun completedStepsAreRevisitableAndTheJourneyStillOwnsWhatComesNext() {
        // No way back was a hardware usability defect: completed dots are links (URL-addressable,
        // /setup#broker — the browser back button and manual deep-links work), the current and future
        // dots are NOT links (no skipping ahead), a revisit never re-arms state by itself, and a re-save
        // that moves the real journey clears the revisit so the user sees the consequence.
        assertTrue(js.contains("function forcedRevisitKey()"))
        assertTrue("future steps must be ignored", js.contains("DOT_OF_KEY[key] < currentIdx ? key : null"))
        assertTrue(js.contains("You’re revisiting an earlier step."))
        assertTrue(js.contains("Continue setup →"))
        assertTrue("a re-save ends the revisit", js.contains("journey.next !== lastRealNext) clearRevisit()"))
        assertTrue("repair mode keeps no strip and no revisit", js.contains("!journey || journey.repair) return null"))
    }

    @Test fun theAreaIsConfirmedOnTheDashboardPageAndOnlyEditableWhereEditingCanAct() {
        // Moving an already-registered device is admin-only in Home Assistant. So the wizard offers the
        // area field only to an admin session or for a device with no area yet (where the request seeds
        // suggested_area at first registration); otherwise it shows the honest read-only sentence. Both
        // UIs treat HA's value as canonical.
        assertTrue(js.contains("fetch(\"api/v1/config/ha-area\""))
        assertTrue(js.contains("var editable = hdArea.admin || !current;"))
        assertTrue(js.contains("This panel is already registered in Home Assistant"))
        assertTrue(js.contains("Set the HA Area that your panel is in"))
        // A pop-up LIST of HA's real areas, never free text — nobody knows what to type — and no area
        // creation here (that belongs in Home Assistant; the list is the whole safety argument).
        assertTrue(js.contains("el(\"select\", { id: \"wiz-ha_area\""))
        assertFalse("the area field must not be free text", js.contains("id: \"wiz-ha_area\", list:"))
        assertTrue(js.contains("\"No area\""))
        // The area rides in the same config POST as the dashboard, only when touched.
        assertTrue(js.contains("if (typed.ha_area !== undefined) fields.ha_area = typed.ha_area;"))
        val configure = listOf(
            File("src/main/assets/configure.js"),
            File("app/src/main/assets/configure.js"),
        ).first { it.isFile }.readText()
        assertTrue(configure.contains("if (f.picker === \"ha_area\")"))
        assertTrue(configure.contains("fetch(\"api/v1/config/ha-area\""))
        assertTrue(configure.contains("var areaTouched = false;"))
        assertTrue(configure.contains("if (!areaTouched && !haAreaUserOverride && haArea"))
        assertTrue(configure.contains("areaTouched = true;"))
        assertTrue(configure.contains("Local override only"))
        // The resting note names what Home Assistant actually holds so a local override remains
        // distinguishable from an adopted value at a glance.
        assertTrue(configure.contains("Local override only — Home Assistant has"))
        // The override note may only appear when a non-admin local request genuinely differs from Home
        // Assistant. A matching value is not an override, while an admin change can be written back to HA.
        assertTrue(configure.contains("if (areaQueried && haAreaUserOverride && localArea !== areaHa)"))
        assertTrue(configure.contains("areaAdmin = a && typeof a.admin === \"boolean\" ? a.admin : null;"))
        assertFalse(
            "permission alone must not label a matching value as an override",
            configure.contains("a.admin === false && haArea"),
        )
        assertEquals(
            "the note must be recomputed on load AND after an edit",
            2,
            Regex("syncAreaNote\\(\\);").findAll(configure).count(),
        )
        // The option list is attached in ONE mutation. Appending per area re-laid-out the control on every
        // insert, which reads as the picker shuffling before it settles.
        assertTrue(configure.contains("document.createDocumentFragment()"))
        assertTrue(configure.contains("areaSelect.appendChild(areaFrag)"))
        // Failed or wrong-owner responses must be recoverable without a full page reload; the server owns
        // the short atomic cache while the browser only batches each returned option list off-document.
        assertFalse(configure.contains("var haAreaCatalog = null;"))
        assertTrue(
            configure.contains(
                "var haAreaSeed = null, haAreaSeedGeneration = 0, haAreaCatalogRequest = 0, haAreaUserOverride = false;",
            ),
        )
        assertTrue(configure.contains("haAreaSeed = res[1].ha_area_catalog || null;"))
        assertTrue(configure.contains("haAreaUserOverride = res[1].ha_area_user_override === true;"))
        assertTrue(configure.contains("if (haAreaSeed) fillAreaOptions(haAreaSeed);"))
        assertTrue(configure.contains("if (!areaTouched && !haAreaUserOverride && haArea"))
        assertTrue(configure.contains("++haAreaSeedGeneration;"))
        assertTrue(configure.contains("var areaRequest = ++haAreaCatalogRequest;"))
        assertTrue(configure.contains("areaRequest !== haAreaCatalogRequest"))
        assertTrue(configure.contains("The panel endpoint owns a short, owner-keyed cache"))
        assertTrue(configure.contains("class: \"ha-area-picker\""))
        val server = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()
        assertTrue(server.contains("private fun haAreaCatalogJson(): String?"))
        assertTrue(server.contains("haAreaCacheEntryUsable("))
        assertTrue(server.contains("catalog.ownerKey == snapshot.ownerKey && ownsHaAreaSnapshot(snapshot)"))
        assertTrue(server.contains("synchronized(directConfigMutationLock)"))
        assertTrue(server.contains("synchronized(haAreaWarmLock)"))
        assertTrue(server.contains("haAreaCatalogJson()?.let"))
        assertTrue(server.contains("\\\"ha_area_user_override\\\""))
        val css = listOf(
            File("src/main/assets/info.css"),
            File("app/src/main/assets/info.css"),
        ).first { it.isFile }.readText()
        assertTrue(css.contains(".frow .fctl .ha-area-picker{width:min(260px,100%);min-width:0;max-width:100%}"))
        assertTrue(css.contains(".frow .fctl .ha-area-picker select{display:block;width:100%;min-width:0;max-width:100%}"))
        assertTrue(css.contains(".ha-area-picker{width:100%;min-width:0}"))
        // The area list is fetched when the field RENDERS. Loading it on focus/pointerdown appended options
        // into a native dropdown the user had just opened, and the picker visibly flickered as it re-laid
        // out on every insert before settling. Nothing may mutate an open
        // picker, so the lazy triggers must stay gone.
        assertFalse(
            "the area list must not load on interaction — it mutates an open dropdown",
            configure.contains("areaSelect.addEventListener(\"focus\"") ||
                configure.contains("areaSelect.addEventListener(\"pointerdown\""),
        )
        // And the control must show what HA holds, even when the local request has never been set.
        assertTrue(configure.contains("areaSelect.value = haArea"))
    }

    @Test fun theEntityFilterIsAskedBeforeTheFirstRenderNotOfferedAfterIt() {
        // Reversed on hardware evidence. It used to be a suggestion on the completion card, on the reasoning
        // that the offer only makes sense once the user has seen the wall of entities. But on a weak panel an
        // unfiltered first load is slow and laggy — measured at 94% p95 renderer CPU against 3,769 entities —
        // so by the time the wall has been seen the first impression is already spent. It is now its own step
        // ahead of the render, and the completion card must not carry the offer at all.
        val done = js.substring(js.indexOf("function doneCard()"))
        assertFalse("the offer must not return to the completion card", done.contains("dashboard_entity_learning"))
        assertTrue(done.contains("setup.done.filter_note")) // confirmation only, so answering is never silent
        // The completion screen is a SUBSEQUENT page whose job is the two things a new owner wants next.
        assertTrue(done.contains("setup.done.action.configure") && done.contains("setup.done.action.dashboard"))
        // The taming pointer is a tip with a link, not a step with a button.
        assertTrue(done.contains("setup.done.taming_link"))

        val ask = js.substring(js.indexOf("function entityFilterCard()"))
        assertTrue(ask.contains("dashboard_entity_learning: \"true\""))
        // Both answers must lead to a dashboard: declining is a real choice, not a dead end.
        assertTrue(ask.contains("setup.filter.action.disable"))
        assertTrue(js.contains("function answerEntityFilter("))
        // Order is load-bearing: the SETTING commits before the ANSWER, because the answer is what releases
        // the renderer. Reversed, the panel would start its first load unfiltered — the whole failure this
        // step exists to prevent.
        val answer = js.substring(js.indexOf("function answerEntityFilter("))
            .substringBefore("/* ---------- step 6")
        assertTrue(
            "the config POST must precede the answer POST",
            answer.indexOf("api/v1/config") < answer.indexOf("api/v1/setup/entity-filter"),
        )
    }

    @Test fun aTooOldBrowserEngineIsRaisedBeforeSignInAndSaysSwitchingWontHelp() {
        // The journey blocks at the renderer step, so this card appears before the Home Assistant address and
        // sign-in rather than after a completed journey on a panel that could never render.
        assertTrue(js.contains("case \"webview\": return webViewCard("))
        assertTrue(js.contains("step(\"renderer\").detail.indexOf(\"webview_too_old\") === 0 ? \"webview\" : \"renderer\""))
        val card = js.substring(js.indexOf("function webViewCard("))
        // The wrong turn people take next. Both renderers draw into the one system WebView.
        assertTrue(card.contains("will not help"))
        // Reuses the existing heal endpoint rather than inventing a second install path.
        assertTrue(card.contains("api/v1/webview/heal"))
        // With no pinned build for the model, explain instead of offering a button that cannot work.
        assertTrue(card.contains("no known-good engine bundled"))
        assertTrue(card.indexOf("if (fixable)") < card.indexOf("no known-good engine bundled"))
    }

    @Test fun aMovingVerdictAlwaysReadsAsStillMeasuring() {
        // The count arrives live, so the verdict can cross from green to amber to red while the user reads.
        // That only works if it is explicitly provisional: without the label, a card that changes its mind
        // reads as unreliable. With it, the same movement reads as the panel doing its homework.
        val paint = js.substring(js.indexOf("function paintEntityFilter()"))
        assertTrue(paint.contains("if (counting)"))
        assertTrue(paint.contains("tag.textContent = i18nText(\"setup.filter.confidence.counting\""))
        assertTrue(paint.contains("text.textContent = i18nText(\"setup.filter.confidence.counting_explanation\""))
        // Confidence is never asserted as measurement when it was inferred.
        assertTrue(
            paint.contains("ef.confidence === \"measured\" ? i18nText(\"setup.filter.confidence.measured\"") &&
                paint.contains(": i18nText(\"setup.filter.confidence.estimated\""),
        )
        // The displayed count must never run backwards — a settled total below the last partial would read
        // as the panel losing entities.
        assertTrue(paint.contains("efShown > efTarget"))
        assertTrue(js.contains("prefers-reduced-motion: reduce"))
    }

    @Test fun theEntityFilterCardIsPatchedInPlaceNeverRebuiltByThePoll() {
        // Same discipline as the MQTT card's focus loss: rebuilding on a 2s poll would tear down the buttons
        // under the cursor and restart the count from zero. One stable key, contents patched.
        assertTrue(js.contains("case \"entity_filter\": return \"entity_filter\";"))
        assertTrue(js.contains("if (key === \"entity_filter\") paintEntityFilter();"))
        // A scan in flight must keep the poll fast even though no STEP is in flight, or the number lurches.
        assertTrue(js.contains("var counting = journey && journey.entity_filter && journey.entity_filter.counting;"))
    }

    @Test fun theSaveThrobExistsOnlyDuringCommissioningAndRespectsReducedMotion() {
        // Urgency treatment on /configure while setup is unfinished, gone the moment it completes, and a
        // persistent outline when motion is reduced — the signal survives, only the animation is dropped.
        assertTrue(css.contains("body.commissioning.cfg-dirty #savebtn{animation:wiz-throb"))
        assertTrue(css.contains("@media (prefers-reduced-motion:reduce){body.commissioning.cfg-dirty #savebtn{animation:none;outline:"))
        val server = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()
        // The class is a pure function of journey state, scoped to the Configure page alone.
        // Gated on needsUser, not on completeness: RENDER_PROOF is process-scoped for the built-in renderer, so a
        // configured panel re-earning it after a restart must not get a throbbing Save button.
        assertTrue(server.contains("""val commissioning = active == "configure" && setupNeedsUser()"""))
    }

    @Test fun aPollNeverRebuildsAnInputBearingStepWhoseCardIsUnchanged() {
        // The focus-loss showstopper: an in-flight 2s poll rebuilt the MQTT card because the guard
        // compared the stage name against the card key. render() now compares one canonical desiredKey()
        // against renderedKey and rebuilds ONLY on a real change, so a field being typed in is left alone.
        assertTrue(js.contains("function desiredKey()"))
        assertTrue(js.contains("if (key === renderedKey) {"))
        assertTrue("an unchanged key must still return without rebuilding", js.contains("return; // otherwise nothing material changed"))
        // MQTT is one card across all its transient connection states, distinct only when truly blocked.
        assertTrue(js.contains("step(\"mqtt_connection\").status === \"blocked\" ? \"mqtt:fail\" : \"mqtt\""))
        // Discovery must not repaint (and steal focus) while a field is focused.
        assertTrue(js.contains("var editing = document.activeElement"))
    }

    @Test fun thePanelIdFieldRejectsHomeAssistantProhibitedCharactersAsTyped() {
        // What the field shows must equal what is stored — no silent hyphen→underscore on save.
        assertTrue(js.contains("sanitize: function (v) { return String(v).toLowerCase().replace(/[^a-z0-9_]/g, \"_\"); }"))
        assertTrue(js.contains("if (attrs && attrs.sanitize)"))
    }

    @Test fun theWizardPageIsExemptFromTheConfigWatchReload() {
        // buildwatch.js auto-reloads a page when its data-cfg token changes; mid-typing that destroys the
        // user's input. The /setup shell therefore omits data-cfg (the wizard polls for itself), keeping
        // only the build token so a reinstall still refreshes.
        val server = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        ).first { it.isFile }.readText()
        val routeStart = server.indexOf("get(\"/setup\") {")
        val route = server.substring(routeStart, server.indexOf("get(\"/profiles\") {", routeStart))
        assertTrue(route.contains("""data-build="$"""))
        // The comment in that route may NAME the attribute; what must be absent is its emission.
        assertFalse("emitting data-cfg on /setup lets buildwatch destroy in-progress typing", route.contains("""data-cfg="$"""))
    }

    @Test fun aFinishedPanelsDashboardTabCarriesNoCommissioningNarration() {
        // Reported twice from the fleet: a working panel's Dashboard tab showed "MQTT connected — publishing
        // Home Assistant discovery. The dashboard setup step appears next." The `announcing` state is transient
        // but recurs on every bridge reconnect (HA restart, broker blip, panel wake), so the banner kept
        // returning to narrate a step finished long ago. The Configure tab keeps it unconditionally — there it
        // is feedback for a save the user just made.
        val server = File(
            listOf(
                "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
                "app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
            ).first { File(it).isFile },
        ).readText()
        assertTrue(server.contains("val mqttProgress = if (!setupNeedsUser()) \"\" else {"))
        // One helper behind every first-run affordance, so they cannot drift apart again.
        assertTrue(server.contains("private fun setupNeedsUser(): Boolean = SetupJourney.evaluate(setupJourneyInputs()).needsUser"))
        assertEquals(
            "one declaration plus six call sites: Set up tab, resume banner, Save throb, sign-in return, " +
                "MQTT progress, and the stale-tab page redirect",
            7,
            Regex("""setupNeedsUser\(\)""").findAll(server).count(),
        )
    }
}
