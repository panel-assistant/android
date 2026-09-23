package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.DiscoveryOutcome
import io.github.maxlyth.hapaneld.DiscoveryReason
import io.github.maxlyth.hapaneld.DiscoveryResult
import io.github.maxlyth.hapaneld.http.SetupJourney.MqttSetupState
import io.github.maxlyth.hapaneld.http.SetupJourney.ProofSource
import io.github.maxlyth.hapaneld.http.SetupJourney.RenderProof
import io.github.maxlyth.hapaneld.http.SetupJourney.RendererChoice
import io.github.maxlyth.hapaneld.http.SetupJourney.Stage
import io.github.maxlyth.hapaneld.http.SetupJourney.Status
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupJourneyTest {
    private fun inputs(
        identityConfirmed: Boolean = true,
        brokerConfigured: Boolean = true,
        mqttUserConfigured: Boolean = true,
        mqttPasswordConfigured: Boolean = true,
        mqtt: MqttSetupState = MqttSetupState.CONNECTED,
        renderer: RendererChoice = RendererChoice.Builtin,
        haUrl: String = "http://ha.local:8123",
        haCredentialed: Boolean = true,
        haOAuthInFlight: Boolean = false,
        discovery: DiscoveryResult = DiscoveryResult(),
        // Defaults to answered because this helper models a panel that WORKS: an unanswered filter question
        // deliberately holds the first render, so leaving it false here would make every unrelated test
        // assert against a panel that is mid-setup by construction.
        entityFilterAnswered: Boolean = true,
        // Same reasoning: a working panel has made its dashboard choice.
        homeDashboardChosen: Boolean = true,
        webViewTooOld: Boolean = false,
        webViewFixable: Boolean = false,
        proof: RenderProof = RenderProof(ProofSource.BUILTIN_FRONTEND_CONNECTED, certain = true, observedAtMs = 1L),
        currentFingerprint: String = "",
    ) = SetupJourney.Inputs(
        identityConfirmed = identityConfirmed,
        panelId = "kitchen_panel",
        brokerConfigured = brokerConfigured,
        mqttUserConfigured = mqttUserConfigured,
        mqttPasswordConfigured = mqttPasswordConfigured,
        mqtt = mqtt,
        renderer = renderer,
        haUrl = haUrl,
        haCredentialed = haCredentialed,
        haOAuthInFlight = haOAuthInFlight,
        discovery = discovery,
        entityFilterAnswered = entityFilterAnswered,
        homeDashboardChosen = homeDashboardChosen,
        webViewTooOld = webViewTooOld,
        webViewFixable = webViewFixable,
        proof = proof,
        currentFingerprint = currentFingerprint,
    )

    @Test fun aFullyConfiguredPanelWithAProvenRenderIsComplete() {
        val j = SetupJourney.evaluate(inputs())
        assertTrue(j.complete)
        assertNull(j.next)
    }

    @Test fun everyDocumentedMqttTokenMapsToAKnownState() {
        // The canonical vocabulary is documented on MqttBridge.state. If a state is added there and not
        // here it silently becomes UNKNOWN, which reads as "still connecting" forever and suppresses all
        // guidance — a failure mode with no visible symptom other than a user waiting indefinitely.
        val bridge = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/MqttBridge.kt"),
        ).first { it.isFile }.readText()
        val documented = Regex("""\*\s+\*\s+(connected \| .*?)\.""", RegexOption.DOT_MATCHES_ALL)
            .find(bridge)?.groupValues?.get(1)
            ?: bridge.substringAfter("connected | ").substringBefore(".").let { "connected | $it" }
        val tokens = documented.split("|").map { it.trim().trim('*', ' ', '\n') }.filter { it.isNotEmpty() }
        assertTrue("could not read the documented token list", tokens.size >= 7)
        tokens.forEach { token ->
            assertFalse("undocumented mapping for '$token'", MqttSetupState.of(token) == MqttSetupState.UNKNOWN)
        }
        // Two states are produced in code but not in that comment; pin them explicitly.
        assertEquals(MqttSetupState.AUTH_RETRYING, MqttSetupState.of("auth-retrying"))
        assertEquals(MqttSetupState.CONFIG_ERROR, MqttSetupState.of("config-error"))
        assertEquals(MqttSetupState.UNKNOWN, MqttSetupState.of("something-new"))
    }

    @Test fun mqttNeverBlocksReachingADashboard() {
        // PostUpdateReturnPolicy states MQTT is optional and must not strand a panel. So a broker that is
        // unreachable or rejecting must not stop the journey completing, only degrade control.
        listOf(MqttSetupState.UNREACHABLE, MqttSetupState.AUTH_FAILED, MqttSetupState.CONFIG_ERROR).forEach { state ->
            val j = SetupJourney.evaluate(inputs(mqtt = state))
            assertTrue("$state should not block a dashboard", j.complete)
            assertFalse(j.step(Stage.MQTT_CONNECTION).blocking)
            assertFalse(j.step(Stage.MQTT_CREDENTIALS).blocking)
        }
    }

    @Test fun aRejectedLoginPointsAtCredentialsAndAnUnreachableHostAtTheConnection() {
        // The two failures have different remedies, so they must not share one message.
        assertEquals(
            Stage.MQTT_CREDENTIALS,
            SetupJourney.evaluate(inputs(mqtt = MqttSetupState.AUTH_FAILED)).next,
        )
        listOf(MqttSetupState.UNREACHABLE, MqttSetupState.CONFIG_ERROR).forEach { state ->
            assertEquals(Stage.MQTT_CONNECTION, SetupJourney.evaluate(inputs(mqtt = state)).next)
        }
    }

    @Test fun theNextStepFollowsStageOrderRatherThanSeverity() {
        // Ranking blocking stages first made severity reorder the walk under the user's feet. Order is
        // what setup walks — declaration order alone — and in that order the missing HA credential
        // (needed by the dashboard/area page AND by the area mechanism at first MQTT registration)
        // comes before a broken broker, however loud the broker failure is.
        val j = SetupJourney.evaluate(inputs(mqtt = MqttSetupState.UNREACHABLE, haCredentialed = false))
        assertEquals(Stage.HA_CREDENTIALS, j.next)
        assertFalse(j.complete)
        // With the credential in place, the broker failure is next in walk order.
        val mqttOnly = SetupJourney.evaluate(inputs(mqtt = MqttSetupState.UNREACHABLE))
        assertEquals(Stage.MQTT_CONNECTION, mqttOnly.next)
        // Completion still ignores the non-blocking failure — only the render-side stages hold it back.
        assertTrue(j.step(Stage.HA_CREDENTIALS).blocking)
    }

    @Test fun theWalkNeverJumpsOverAStepThatIsMidFlight() {
        // The moment the panel-side OAuth attempt arms, sign-in reads IN_FLIGHT. Ranking blocked above
        // in-flight would send the wizard to the LATER blocked broker step and abandon the unfinished
        // sign-in. Strict walk order: the first step that is blocked OR in flight is next, full stop.
        val midSignIn = SetupJourney.evaluate(inputs(
            haCredentialed = false, haOAuthInFlight = true,
            brokerConfigured = false, mqttUserConfigured = false, mqttPasswordConfigured = false,
            mqtt = MqttSetupState.DISABLED, homeDashboardChosen = false, entityFilterAnswered = false,
            proof = RenderProof(),
        ))
        assertEquals(Status.IN_FLIGHT, midSignIn.step(Stage.HA_CREDENTIALS).status)
        assertEquals(Stage.HA_CREDENTIALS, midSignIn.next)
    }

    @Test fun transientMqttStatesAreNeverPresentedAsSomethingToFix() {
        // The regression SetupBanner was extracted to prevent: a mid-connect broker reported as missing.
        listOf(
            MqttSetupState.CONNECTING,
            MqttSetupState.ANNOUNCING,
            MqttSetupState.DISCOVERING,
            MqttSetupState.AUTH_RETRYING,
        ).forEach { state ->
            val j = SetupJourney.evaluate(inputs(mqtt = state))
            assertEquals("$state must read as in-flight", Status.IN_FLIGHT, j.step(Stage.MQTT_CONNECTION).status)
            assertFalse("$state must not be BLOCKED", j.step(Stage.MQTT_CONNECTION).status == Status.BLOCKED)
        }
    }

    @Test fun aConfiguredBrokerThatHasNotReportedYetIsTransientNotBroken() {
        val j = SetupJourney.evaluate(inputs(mqtt = MqttSetupState.UNKNOWN))
        assertEquals(Status.IN_FLIGHT, j.step(Stage.MQTT_CONNECTION).status)
        assertTrue(j.complete)
    }

    @Test fun anUnconfirmedIdentityBlocksBeforeAnythingElse() {
        // panel_id auto-derives, so a non-blank value proves nothing about the user having chosen it.
        val j = SetupJourney.evaluate(inputs(identityConfirmed = false))
        assertEquals(Stage.IDENTITY, j.next)
        assertFalse(j.complete)
    }

    @Test fun aForeignRendererDoesNotInheritOurHomeAssistantRequirements() {
        // The Companion app owns its own URL and sign-in; demanding ours would invent unfinishable work.
        val j = SetupJourney.evaluate(
            inputs(
                renderer = RendererChoice.Foreign("io.homeassistant.companion.android.minimal", installed = true),
                haUrl = "",
                haCredentialed = false,
                proof = RenderProof(ProofSource.USER_ATTESTED, certain = true, observedAtMs = 2L),
            ),
        )
        assertEquals(Status.SKIPPED, j.step(Stage.HA_URL).status)
        assertEquals(Status.SKIPPED, j.step(Stage.HA_CREDENTIALS).status)
        assertTrue(j.complete)
    }

    @Test fun anExplicitRendererThatIsNotInstalledBlocks() {
        val j = SetupJourney.evaluate(inputs(renderer = RendererChoice.Foreign("de.ozerov.fully", installed = false)))
        assertEquals(Stage.RENDERER, j.next)
        assertTrue(j.step(Stage.RENDERER).blocking)
    }

    @Test fun aForeignRenderIsNeverClaimedAsProvenAutomatically() {
        // Foregrounding proves the app runs, not that a dashboard drew — it could be showing its own
        // onboarding or a connection error. Only a human can settle it.
        val j = SetupJourney.evaluate(
            inputs(
                renderer = RendererChoice.Foreign("io.homeassistant.companion.android", installed = true),
                proof = RenderProof(ProofSource.FOREIGN_FOREGROUND_ONLY, certain = false),
            ),
        )
        // BLOCKED, not UNKNOWN: "confirm you can see your dashboard" is a real instruction. Left as
        // UNKNOWN the journey reported incomplete with no next step at all — a silent dead end.
        assertEquals(Status.BLOCKED, j.step(Stage.RENDER_PROOF).status)
        assertEquals("foreign_renderer_unobservable", j.step(Stage.RENDER_PROOF).detail)
        assertFalse(j.complete)
        assertEquals(Stage.RENDER_PROOF, j.next)
    }

    @Test fun noStateEverReportsIncompleteWithNothingToDo() {
        // A dead end is the worst possible outcome: the user is told setup is unfinished and given no
        // action. Sweep the combination space and assert it cannot happen.
        val mqttStates = MqttSetupState.entries
        val renderers = listOf(
            RendererChoice.Builtin,
            RendererChoice.Unresolved,
            RendererChoice.Foreign("io.homeassistant.companion.android", installed = true),
            RendererChoice.Foreign("de.ozerov.fully", installed = false),
        )
        var checked = 0
        for (identity in listOf(true, false)) {
            for (broker in listOf(true, false)) {
                for (mqtt in mqttStates) {
                    for (renderer in renderers) {
                        for (url in listOf("http://ha.local:8123", "")) {
                            for (cred in listOf(true, false)) {
                                for (certain in listOf(true, false)) {
                                    val j = SetupJourney.evaluate(
                                        inputs(
                                            identityConfirmed = identity, brokerConfigured = broker,
                                            mqttUserConfigured = broker, mqttPasswordConfigured = broker,
                                            mqtt = mqtt, renderer = renderer, haUrl = url,
                                            haCredentialed = cred,
                                            proof = RenderProof(
                                                if (certain) ProofSource.USER_ATTESTED else ProofSource.NONE,
                                                certain = certain,
                                            ),
                                        ),
                                    )
                                    checked++
                                    if (!j.complete) {
                                        assertTrue(
                                            "incomplete with no next step: mqtt=$mqtt renderer=$renderer " +
                                                "identity=$identity broker=$broker url='$url' cred=$cred " +
                                                "certain=$certain",
                                            j.next != null,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(checked > 500)
    }

    @Test fun proofDoesNotSurviveAConfigurationChange() {
        // Re-arming is what makes "setup" and "repair" the same computation: a moved broker, a revoked
        // token or a switched renderer must void a proof rather than let the journey coast on it.
        val j = SetupJourney.evaluate(
            inputs(
                proof = RenderProof(ProofSource.BUILTIN_FRONTEND_CONNECTED, certain = true, fingerprint = "old"),
                currentFingerprint = "new",
            ),
        )
        // The built-in renderer reloads and re-signals by itself, so this is a wait rather than a task.
        assertEquals(Status.IN_FLIGHT, j.step(Stage.RENDER_PROOF).status)
        assertEquals("stale_proof", j.step(Stage.RENDER_PROOF).detail)
        assertFalse(j.complete)
        // A foreign renderer cannot re-prove anything on its own, so it must be asked again.
        val foreign = SetupJourney.evaluate(
            inputs(
                renderer = RendererChoice.Foreign("io.homeassistant.companion.android", installed = true),
                haUrl = "", haCredentialed = false,
                proof = RenderProof(ProofSource.USER_ATTESTED, certain = true, fingerprint = "old"),
                currentFingerprint = "new",
            ),
        )
        assertEquals(Status.BLOCKED, foreign.step(Stage.RENDER_PROOF).status)
    }

    @Test fun matchingFingerprintsKeepAProof() {
        val j = SetupJourney.evaluate(
            inputs(
                proof = RenderProof(ProofSource.BUILTIN_FRONTEND_CONNECTED, certain = true, fingerprint = "same"),
                currentFingerprint = "same",
            ),
        )
        assertEquals(Status.SATISFIED, j.step(Stage.RENDER_PROOF).status)
        assertTrue(j.complete)
    }

    @Test fun renderProofIsNotAskedForWhilePrerequisitesAreMissing() {
        // Asking "can you see your dashboard?" before there is any chance of one is noise, not diagnosis.
        val j = SetupJourney.evaluate(inputs(haCredentialed = false, proof = RenderProof()))
        assertEquals("prerequisites_missing", j.step(Stage.RENDER_PROOF).detail)
        assertEquals(Stage.HA_CREDENTIALS, j.next)
    }

    @Test fun anInFlightSignInIsAWaitNotAnInstruction() {
        val j = SetupJourney.evaluate(inputs(haCredentialed = false, haOAuthInFlight = true, proof = RenderProof()))
        assertEquals(Status.IN_FLIGHT, j.step(Stage.HA_CREDENTIALS).status)
        // Nothing is BLOCKED, so the only thing reported is the wait.
        assertEquals(Stage.HA_CREDENTIALS, j.next)
    }

    @Test fun aFreshPanelAsksForIdentityFirstThenBrokerThenSignIn() {
        // The whole journey, in order, from a wiped panel on a network where discovery cannot work.
        val hopeless = DiscoveryResult(
            outcome = DiscoveryOutcome.UNAVAILABLE,
            reason = DiscoveryReason.BROKER_NOT_ON_LINK,
        )
        var state = inputs(
            identityConfirmed = false, brokerConfigured = false, mqttUserConfigured = false,
            mqttPasswordConfigured = false, mqtt = MqttSetupState.DISABLED, haUrl = "",
            haCredentialed = false, discovery = hopeless, proof = RenderProof(),
        )
        assertEquals(Stage.IDENTITY, SetupJourney.evaluate(state).next)

        // Home Assistant comes BEFORE MQTT: suggested_area applies only at the device's first
        // registration (the moment MQTT connects), and valid area names are only readable after
        // sign-in — the one order in which every user's panel lands in its correct area.
        state = state.copy(identityConfirmed = true)
        assertEquals(Stage.HA_URL, SetupJourney.evaluate(state).next)
        // The step carries the discovery outcome, so the UI can say why the field is blank rather than
        // just presenting an empty box on a network where discovery can never succeed.
        assertTrue(SetupJourney.discoveryHopeless(hopeless))
        assertEquals("unavailable", SetupJourney.evaluate(state).step(Stage.HA_URL).detail)

        state = state.copy(haUrl = "http://ha.local:8123")
        assertEquals(Stage.HA_CREDENTIALS, SetupJourney.evaluate(state).next)

        state = state.copy(haCredentialed = true, homeDashboardChosen = false, entityFilterAnswered = false)
        // The dashboard+area page follows sign-in immediately (the lists need the credential) and
        // precedes MQTT (the area must be chosen before the broker connect registers the device).
        assertEquals(Stage.HOME_DASHBOARD, SetupJourney.evaluate(state).next)
        assertFalse(SetupJourney.evaluate(state).complete)

        state = state.copy(homeDashboardChosen = true)
        assertEquals(Stage.MQTT_BROKER, SetupJourney.evaluate(state).next)

        state = state.copy(brokerConfigured = true, mqttUserConfigured = true, mqttPasswordConfigured = true, mqtt = MqttSetupState.CONNECTED)
        // The filter question comes BEFORE the render, so it is next here and the render is not yet claimed
        // to be loading — the panel is deliberately holding.
        assertEquals(Stage.ENTITY_FILTER, SetupJourney.evaluate(state).next)
        assertFalse(SetupJourney.evaluate(state).complete)

        state = state.copy(entityFilterAnswered = true)
        assertEquals(Stage.RENDER_PROOF, SetupJourney.evaluate(state).next)
        assertFalse(SetupJourney.evaluate(state).complete)

        state = state.copy(proof = RenderProof(ProofSource.BUILTIN_FRONTEND_CONNECTED, certain = true))
        assertTrue(SetupJourney.evaluate(state).complete)
        assertNull(SetupJourney.evaluate(state).next)
    }

    @Test fun aTooOldBrowserEngineBlocksBeforeAnythingIsAskedOfHomeAssistant() {
        // The failure this prevents: complete the whole journey — address, sign-in, filter — then discover the
        // panel could never render. Raised at the renderer step so it comes before HA_URL and HA_CREDENTIALS.
        val ancient = inputs(webViewTooOld = true, webViewFixable = true, proof = RenderProof())
        val j = SetupJourney.evaluate(ancient)
        assertEquals(Stage.RENDERER, j.next)
        assertEquals("webview_too_old_fixable", j.step(Stage.RENDERER).detail)
        assertFalse(j.complete)

        // Without a pinned build the block stands but the detail says the offer cannot be made, so the UI
        // explains instead of showing a button that would fail.
        val unfixable = SetupJourney.evaluate(ancient.copy(webViewFixable = false))
        assertEquals("webview_too_old", unfixable.step(Stage.RENDERER).detail)
    }

    @Test fun aForeignRenderersEngineIsNotOursToDeclareBroken() {
        // They share the system WebView, but another app's internals are not observable from here — so this
        // never blocks a Companion panel on our judgement. The built-in card's copy carries the warning.
        val foreign = inputs(
            renderer = RendererChoice.Foreign("io.homeassistant.companion.android", installed = true),
            webViewTooOld = true,
            proof = RenderProof(ProofSource.USER_ATTESTED, certain = true),
        )
        assertEquals(Status.SATISFIED, SetupJourney.evaluate(foreign).step(Stage.RENDERER).status)
    }

    @Test fun theRenderIsNotReportedAsLoadingWhileTheFilterQuestionHoldsIt() {
        // The renderer is held until the question is answered. Reporting the proof step as in-flight then
        // would have the wizard tell the user to go and watch for a dashboard it is itself preventing.
        val held = inputs(entityFilterAnswered = false, proof = RenderProof())
        val j = SetupJourney.evaluate(held)
        assertEquals(Stage.ENTITY_FILTER, j.next)
        assertEquals(Status.UNKNOWN, j.step(Stage.RENDER_PROOF).status)
        assertEquals("prerequisites_missing", j.step(Stage.RENDER_PROOF).detail)
    }

    @Test fun decliningTheFilterStillReachesADashboard() {
        // "Answered" is recorded, not inferred from the filter being on, precisely so that a user who says
        // no is not asked forever on a panel that then never renders.
        val declined = inputs(entityFilterAnswered = true)
        assertTrue(SetupJourney.evaluate(declined).complete)
    }

    @Test fun aForeignRendererIsNeverAskedAboutTheFilter() {
        // Only the built-in renderer's subscription is ours to narrow; asking about someone else's would be
        // a question the user cannot act on and a stage that could never be satisfied.
        val foreign = inputs(
            renderer = RendererChoice.Foreign("io.homeassistant.companion.android", installed = true),
            entityFilterAnswered = false,
            proof = RenderProof(ProofSource.USER_ATTESTED, certain = true),
        )
        val j = SetupJourney.evaluate(foreign)
        assertEquals(Status.SKIPPED, j.step(Stage.ENTITY_FILTER).status)
        assertTrue(j.complete)
    }

    @Test fun theFilterQuestionIsNotAskedBeforeThereIsAHomeAssistantToCountAgainst() {
        // It needs a live connection to count entities, and an earlier stage already owns the instruction.
        val noCreds = inputs(haCredentialed = false, entityFilterAnswered = false, proof = RenderProof())
        val j = SetupJourney.evaluate(noCreds)
        assertEquals(Stage.HA_CREDENTIALS, j.next)
        assertEquals(Status.UNKNOWN, j.step(Stage.ENTITY_FILTER).status)
    }

    @Test fun theDashboardQuestionIsNotAskedBeforeSignInAndNeverOfAForeignRenderer() {
        // Same shape as the filter question: the list of dashboards to offer needs a signed-in HA, and a
        // foreign renderer navigates itself, so there is nothing of ours to choose.
        val noCreds = inputs(haCredentialed = false, homeDashboardChosen = false, proof = RenderProof())
        assertEquals(Status.UNKNOWN, SetupJourney.evaluate(noCreds).step(Stage.HOME_DASHBOARD).status)

        val foreign = inputs(
            renderer = RendererChoice.Foreign("io.homeassistant.companion.android", installed = true),
            homeDashboardChosen = false,
            proof = RenderProof(ProofSource.USER_ATTESTED, certain = true),
        )
        val j = SetupJourney.evaluate(foreign)
        assertEquals(Status.SKIPPED, j.step(Stage.HOME_DASHBOARD).status)
        assertTrue(j.complete)
    }

    @Test fun choosingTheAccountsDefaultDashboardStillCompletesTheJourney() {
        // "Chosen" is recorded, not inferred from home_dashboard — following the account's default leaves
        // the setting blank, which must not read as "never asked" or the wizard would re-arm forever.
        assertTrue(SetupJourney.evaluate(inputs(homeDashboardChosen = true)).complete)
    }

    @Test fun theRenderIsNotReportedAsLoadingWhileTheDashboardQuestionHoldsIt() {
        // Same contract as the filter hold: while a blocking question is open the proof step must not claim
        // the dashboard is loading, because nothing is loading on purpose.
        val held = inputs(homeDashboardChosen = false, proof = RenderProof())
        val j = SetupJourney.evaluate(held)
        assertEquals(Stage.HOME_DASHBOARD, j.next)
        assertEquals(Status.UNKNOWN, j.step(Stage.RENDER_PROOF).status)
        assertTrue(j.needsUser)
    }

    @Test fun everyStageAppearsExactlyOnceInOrder() {
        val steps = SetupJourney.evaluate(inputs()).steps
        assertEquals(Stage.entries, steps.map { it.stage })
    }

    @Test fun theDashboardAndAreaAreChosenBeforeTheFirstMqttConnect() {
        // suggested_area applies only at the device's FIRST registration, which happens the moment MQTT
        // connects — and the valid area names are only readable after HA sign-in. If MQTT ever moves
        // ahead of HOME_DASHBOARD again, every non-admin user loses the ability to place their panel in
        // an area at all.
        assertTrue(Stage.HOME_DASHBOARD.ordinal < Stage.MQTT_BROKER.ordinal)
        assertTrue(Stage.HA_CREDENTIALS.ordinal < Stage.HOME_DASHBOARD.ordinal)
        // MQTT still precedes the filter question, so the journey cannot present as finished while the
        // broker was never even asked about.
        assertTrue(Stage.MQTT_CONNECTION.ordinal < Stage.ENTITY_FILTER.ordinal)
    }

    @Test fun aConfiguredPanelReEarningItsRenderProofIsNotWaitingOnAPerson() {
        // Reported on a configured panel: the "Set up" tab and the "Setting up this panel?" banner appeared on a
        // fully configured, working panel. Its only unsatisfied stage was render_proof = awaiting_frontend,
        // which for the built-in renderer is process-scoped and re-earned after every restart. Nothing was
        // missing, so nothing should have invited the owner into the wizard.
        val reproving = inputs(proof = RenderProof())
        val j = SetupJourney.evaluate(reproving)
        assertEquals(Status.IN_FLIGHT, j.step(Stage.RENDER_PROOF).status)
        assertFalse("in-flight proof is not an instruction", j.complete)
        assertFalse("nothing is blocked, so no first-run affordance may show", j.needsUser)
    }

    @Test fun aGenuineFirstRunAndARealRepairBothStillNeedAPerson() {
        // The affordances must survive for the cases that actually want them.
        assertTrue("fresh panel", SetupJourney.evaluate(inputs(identityConfirmed = false)).needsUser)
        assertTrue(
            "revoked credential",
            SetupJourney.evaluate(inputs(haCredentialed = false, proof = RenderProof())).needsUser,
        )
        assertTrue(
            "foreign renderer needing human attestation",
            SetupJourney.evaluate(
                inputs(
                    renderer = RendererChoice.Foreign("io.homeassistant.companion.android", installed = true),
                    proof = RenderProof(),
                ),
            ).needsUser,
        )
        assertTrue("engine too old to render", SetupJourney.evaluate(inputs(webViewTooOld = true)).needsUser)
        // And a healthy proven panel needs nobody.
        assertFalse(SetupJourney.evaluate(inputs()).needsUser)
    }
}
