package io.github.maxlyth.hapaneld.http

import android.content.SharedPreferences
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.DiscoveryResult
import io.github.maxlyth.hapaneld.entityFilterQuestionPending
import io.github.maxlyth.hapaneld.http.SetupJourney
import io.github.maxlyth.hapaneld.http.SetupJourney.MqttSetupState
import io.github.maxlyth.hapaneld.http.SetupJourney.ProofSource
import io.github.maxlyth.hapaneld.http.SetupJourney.RenderProof
import io.github.maxlyth.hapaneld.http.SetupJourney.RendererChoice
import io.github.maxlyth.hapaneld.http.SetupJourney.Status
import io.github.maxlyth.hapaneld.panelConfiguredBeforeSetupTracking
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup journey, stormed with restarts.
 *
 * Both severe first-run defects of the vc468-477 hardware era were one shape: a service restart landed
 * mid-journey, and something inferred from the durable configuration the wizard had *just written* that
 * this panel was an upgraded install which had already answered the questions. The panel then rendered
 * unfiltered, the journey reported complete, and the sign-in return dumped the user on the Configure tab
 * with two questions never asked. It happened twice from different organs — once from the read-time
 * inference in the journey inputs, once from the retrying startup migration — and eleven hardware walks
 * were the only reason either was found, because a restart mid-walk is invisible to a unit test that
 * evaluates one state.
 *
 * This suite tests durable-state sequences rather than one isolated state. It drives the real [Config]
 * over surviving fake preferences, replays the durable writes each wizard step commits, and reconstructs
 * Config after every prefix of the walk. It deliberately does not claim to simulate service startup,
 * HTTP form handling, or Android process-static state; those require integration or hardware coverage. The
 * invariant is the one the product defines as the product rule: first run must work or fail cleanly —
 * no restart may answer a question on the user's behalf, and no state may report unfinished with nothing
 * to do.
 *
 * Deliberately pure JVM: this covers logic and durable-state lifecycle, which is where nine of the eleven
 * walks' defects lived. Hardware still owns timing, WebView and root-lane behaviour.
 */
class SetupRestartStormTest {

    /** What each wizard step durably commits, in the journey's stage order. */
    private val walk: List<Pair<String, (MutableMap<String, Any?>, Config) -> Unit>> = listOf(
        "identity" to { v, c -> v["panel_id"] = "alpha"; c.setupIdentityConfirmed = true },
        "renderer" to { v, _ -> v["dashboard_package"] = "builtin" },
        "ha_url" to { v, _ -> v["ha_url"] = "http://ha.local:8123" },
        "ha_sign_in" to { v, _ -> v["ha_token"] = "test-token" },
        "home_dashboard" to { v, c -> v["home_dashboard"] = "/alpha"; c.setupHomeDashboardChosen = true },
        "mqtt" to { v, _ ->
            v["mqtt_broker"] = "tcp://ha.local:1883"
            v["mqtt_user"] = "panel"
            v["mqtt_password"] = "secret"
        },
        "entity_filter" to { v, c -> c.setupEntityFilterAnswered = true },
    )

    private val homeDashboardStepIndex = walk.indexOfFirst { it.first == "home_dashboard" }
    private val entityFilterStepIndex = walk.indexOfFirst { it.first == "entity_filter" }

    // ---- layer 1: durable state across restart storms ------------------------------------------------

    @Test fun noRestartAtAnyPointOfTheWalkEverAnswersAQuestionForTheUser() {
        // The exact defect, generalised: break the walk after every prefix, restart the service one to
        // three times, and require that the two recorded answers are still owed unless the user gave them.
        var checked = 0
        for (breakAfter in 0..walk.size) {
            for (restarts in 1..3) {
                val values = mutableMapOf<String, Any?>()
                var config = boot(values)
                walk.take(breakAfter).forEach { (_, write) -> write(values, config) }
                repeat(restarts) { config = boot(values) }
                checked++

                val where = "broke after ${breakAfter.let { if (it == 0) "nothing" else walk[it - 1].first }}, " +
                    "$restarts restart(s)"
                if (breakAfter <= entityFilterStepIndex) {
                    assertFalse(
                        "the entity-filter question was answered by a restart ($where)",
                        config.setupEntityFilterAnswered,
                    )
                }
                if (breakAfter <= homeDashboardStepIndex) {
                    assertFalse(
                        "the dashboard question was answered by a restart ($where)",
                        config.setupHomeDashboardChosen,
                    )
                }
                // The panel must not claim it once completed setup either: that flips the wizard's wording
                // to "repair" and suppresses first-run affordances on a panel that has never finished.
                if (breakAfter in 1..walk.size) {
                    assertFalse("a mid-walk panel claimed it had completed setup before ($where)", config.setupEverCompleted)
                }
            }
        }
        assertTrue(checked >= 24)
    }

    @Test fun aGenuineUpgradeIsExcusedAndEachNewQuestionNeedsItsOwnMarker() {
        // The other direction of the same rule, and the reason it cannot simply be deleted: a panel
        // configured before these questions existed must never be held to answer them, or the installed
        // base stops rendering. Each newly introduced question needs its OWN durable marker — reusing v1
        // silently skipped the dashboard migration on every panel that had consumed the earlier release.
        val upgraded = mutableMapOf<String, Any?>(
            "mqtt_broker" to "tcp://ha.local:1883",
            "ha_url" to "http://ha.local:8123",
        )
        val afterUpgrade = boot(upgraded)
        assertTrue("a pre-existing install must be excused the filter question", afterUpgrade.setupEntityFilterAnswered)
        assertTrue("a pre-existing install must be excused the dashboard question", afterUpgrade.setupHomeDashboardChosen)
        assertTrue("and framed as a repair, not a factory-fresh panel", afterUpgrade.setupEverCompleted)

        // The 0.9.6 fleet state: v1 consumed on an earlier build, the dashboard question introduced after.
        val consumedV1 = mutableMapOf<String, Any?>(
            "mqtt_broker" to "tcp://ha.local:1883",
            "ha_url" to "http://ha.local:8123",
            "device_local_setup_questions_migrated_v1" to true,
            "device_local_setup_entity_filter_answered" to true,
            "device_local_setup_ever_completed" to true,
        )
        val afterSecondUpgrade = boot(consumedV1)
        assertTrue(
            "a panel that consumed the v1 migration must still receive the later question's migration",
            afterSecondUpgrade.setupHomeDashboardChosen,
        )

        // A blank panel is not an upgrade and must stay retryable: recording the migration against an empty
        // read is how three panels were once stranded on the hold screen.
        val blank = mutableMapOf<String, Any?>()
        val fresh = boot(blank)
        assertFalse(fresh.setupEntityFilterAnswered)
        assertFalse(fresh.setupHomeDashboardChosen)
        assertFalse(fresh.setupEverCompleted)
        assertFalse(
            "a blank panel must leave the migration retryable, never stamp itself done",
            blank["device_local_setup_questions_migrated_v1"] == true,
        )
    }

    @Test fun aPanelProvisionedByScriptStillReachesADashboardWithoutAnybodyWalkingTheWizard() {
        // Headless provisioning writes a full configuration and never confirms identity through the wizard.
        // That panel must take the upgrade path on its very first boot — nobody is standing there to answer.
        val provisioned = mutableMapOf<String, Any?>(
            "panel_id" to "alpha",
            "mqtt_broker" to "tcp://ha.local:1883",
            "mqtt_user" to "panel",
            "mqtt_password" to "secret",
            "ha_url" to "http://ha.local:8123",
            "ha_token" to "test-token",
            "dashboard_package" to "builtin",
        )
        var config = boot(provisioned)
        repeat(3) { config = boot(provisioned) }

        val journey = SetupJourney.evaluate(journeyInputs(config))
        assertTrue("a script-provisioned panel must not be held on a question", journey.complete)
        assertFalse(journey.needsUser)
    }

    @Test fun anUnseededScriptedInstallRendersTheAccountDefault() {
        // The behaviour the installer's seeds exist to change, pinned so it cannot be mistaken for a
        // defect later: with nothing seeded, a script-provisioned panel is excused both questions (test
        // above) and its home dashboard is blank — "whatever Home Assistant calls this account's
        // default". On a large account that is exactly the dashboard a slow panel struggles to draw.
        val panel = mutableMapOf<String, Any?>()
        var config = boot(panel)
        provisionerConfigPost(panel)
        repeat(3) { config = boot(panel) }

        assertEquals("an unseeded scripted install names no dashboard", "", config.homeDashboard)
        assertFalse("and leaves entity filtering off", config.dashboardEntityLearningEnabled)
    }

    @Test fun aSeededScriptedInstallKeepsItsDashboardAndAnswersAcrossRestarts() {
        // `provision.sh --home-dashboard /alpha --entity-filter on`: the two values, then the two
        // answers the installer records because an operator who supplied them should not be asked
        // again on the panel. The failure this guards is the one that produced the whole class of
        // first-run defects this suite exists for — a restart landing mid-setup and changing what the
        // panel believes it was told.
        // Ordered as the real install is, which matters: the APK is installed and the service starts
        // over a BLANK panel, and only then does the provisioner write. Seeding a map that already
        // looks configured lets the startup migration stamp itself before the seed exists, after
        // which it early-returns forever — an earlier revision of this test did exactly that, and the
        // mutation battery caught it by surviving three mutations of the code it claimed to cover.
        val panel = mutableMapOf<String, Any?>()
        var config = boot(panel)
        provisionerConfigPost(panel)
        panel["home_dashboard"] = "/alpha"
        panel["dashboard_entity_learning"] = true
        config.setupHomeDashboardChosen = true
        config.setupEntityFilterAnswered = true

        repeat(3) { config = boot(panel) }

        assertEquals("the seeded dashboard survives every restart", "/alpha", config.homeDashboard)
        assertTrue("the seeded filter policy survives every restart", config.dashboardEntityLearningEnabled)
        assertTrue("the recorded dashboard answer survives", config.setupHomeDashboardChosen)
        assertTrue("the recorded filter answer survives", config.setupEntityFilterAnswered)
        // Nothing may hold the first render to re-ask a question the installer already answered.
        assertFalse(
            "a seeded panel is never held on the entity-filter question",
            entityFilterQuestionPending(
                builtinRenderer = true,
                haUrl = config.haUrl,
                haToken = config.haToken,
                haRefreshToken = config.haRefreshToken,
                entityFilterAnswered = config.setupEntityFilterAnswered,
                setupEverCompleted = config.setupEverCompleted,
                entityFilterEnabled = config.dashboardEntityLearningEnabled,
            ),
        )
        val journey = SetupJourney.evaluate(journeyInputs(config))
        assertFalse("and guided setup does not relitigate it", journey.needsUser)
        // Bounded deliberately: this proves the durable decision the renderer reads, not the WebView
        // load itself. That the panel reaches only this path is attended hardware acceptance.
    }

    @Test fun aSeedThatFailedToRecordItsAnswerLeavesTheQuestionAskable() {
        // The provisioner's worst reachable failure: the value persisted but the answer POST did not.
        // That must degrade to "guided setup asks again", never to a panel that silently keeps a
        // half-applied decision — which is why the installer records answers only after the values.
        val seeded = mutableMapOf<String, Any?>(
            "ha_url" to "http://ha.local:8123",
            "ha_token" to "test-token",
            "dashboard_package" to "builtin",
            "home_dashboard" to "/alpha",
        )
        val config = Config(fakePreferences(seeded))

        assertEquals("the seeded value is still durable", "/alpha", config.homeDashboard)
        assertFalse("but the question is not recorded as answered", config.setupHomeDashboardChosen)
        assertTrue(
            "so the renderer still holds rather than rendering behind an unanswered question",
            entityFilterQuestionPending(
                builtinRenderer = true,
                haUrl = config.haUrl,
                haToken = config.haToken,
                haRefreshToken = config.haRefreshToken,
                entityFilterAnswered = config.setupEntityFilterAnswered,
                setupEverCompleted = config.setupEverCompleted,
                entityFilterEnabled = config.dashboardEntityLearningEnabled,
            ),
        )
    }

    /** What `provision.sh` commits in its one `POST /api/v1/config` for `--builtin`. */
    private fun provisionerConfigPost(values: MutableMap<String, Any?>) {
        values["panel_id"] = "alpha"
        values["ha_url"] = "http://ha.local:8123"
        values["ha_token"] = "test-token"
        values["dashboard_package"] = "builtin"
    }

    // ---- layer 2: the journey's promise, under restarts AND adversarial runtime state ----------------

    @Test fun thePureJourneyStateSweepNeverCompletesBehindTheUsersBackOrDeadEnds() {
        // The product rule: work or fail cleanly. Across every restart point of the walk and every hostile
        // runtime state we know how to reach — broker rejecting, broker unreachable, Home Assistant
        // credential revoked mid-journey, renderer missing, engine too old, proof never earned — assert
        // that the journey (a) never reports complete while a question the user has not answered is
        // outstanding, and (b) never reports unfinished with nothing for anyone to do.
        val renderers = listOf(
            RendererChoice.Builtin,
            RendererChoice.Unresolved,
            RendererChoice.Foreign("io.homeassistant.companion.android", installed = true),
            RendererChoice.Foreign("de.ozerov.fully", installed = false),
        )
        var checked = 0
        for (breakAfter in 0..walk.size) {
            val values = mutableMapOf<String, Any?>()
            var config = boot(values)
            walk.take(breakAfter).forEach { (_, write) -> write(values, config) }
            config = boot(values)

            val userAnsweredDashboard = breakAfter > homeDashboardStepIndex
            val userAnsweredFilter = breakAfter > entityFilterStepIndex

            for (mqtt in MqttSetupState.entries) {
                for (renderer in renderers) {
                    for (credentialed in listOf(true, false)) {
                        for (certain in listOf(true, false)) {
                            for (tooOld in listOf(false, true)) {
                                val inputs = journeyInputs(
                                    config,
                                    mqtt = mqtt,
                                    renderer = renderer,
                                    haCredentialedOverride = credentialed,
                                    proof = RenderProof(
                                        if (certain) ProofSource.BUILTIN_FRONTEND_CONNECTED else ProofSource.NONE,
                                        certain = certain,
                                    ),
                                    webViewTooOld = tooOld,
                                )
                                val j = SetupJourney.evaluate(inputs)
                                checked++
                                val where = "after ${breakAfter.let { if (it == 0) "nothing" else walk[it - 1].first }}: " +
                                    "mqtt=$mqtt renderer=$renderer cred=$credentialed proof=$certain old=$tooOld"

                                if (!j.complete) {
                                    assertNotNull("unfinished with nothing to do — $where", j.next)
                                    val next = j.step(j.next!!)
                                    assertTrue(
                                        "next points at a stage that is neither an instruction nor a wait — $where",
                                        next.status == Status.BLOCKED || next.status == Status.IN_FLIGHT,
                                    )
                                    if (j.steps.none { it.status == Status.IN_FLIGHT }) {
                                        assertEquals(
                                            "nothing is in flight, so the journey must name something to DO — $where",
                                            Status.BLOCKED,
                                            next.status,
                                        )
                                    }
                                }
                                // The begin-marker rule, stated as an outcome rather than an implementation:
                                // a panel the wizard owns cannot be finished unless the person finished it.
                                if (j.complete && renderer == RendererChoice.Builtin) {
                                    assertTrue(
                                        "completed with the dashboard question never answered — $where",
                                        userAnsweredDashboard,
                                    )
                                    assertTrue(
                                        "completed with the filter question never answered — $where",
                                        userAnsweredFilter,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("the sweep must actually be broad", checked > 1000)
    }

    // ---- a panel Home Assistant handed a URL to ---------------------------------------------------

    /**
     * The defect this suite exists to catch, reached by the route the handover opens.
     *
     * A panel the Panel Assistant integration installs gets `ha_url` written before anyone opens the
     * wizard, so `setupIdentityConfirmed` is still false. The startup migration used to read that URL as
     * proof of an install older than the setup questions and durably `commit()` every answer — after
     * which no read of the provenance marker could retract it, because the flags win over any later
     * inference. The operator's brand-new panel would render unfiltered and the wizard would frame a
     * genuine first run as a repair.
     *
     * Restarting inside that window is not exotic: a power cycle during mounting, an app update, a
     * service restart or a crash all land there, and the window closes only at the wizard's first step.
     */
    @Test fun `a restart on a freshly handed-over panel answers none of the setup questions`() {
        val values = mutableMapOf<String, Any?>(
            "ha_url" to "http://ha.local:8123",
            "ha_setup_handover" to true,
        )
        val config = boot(values)

        assertFalse(
            "the entity-filter question must still be asked",
            config.setupEntityFilterAnswered,
        )
        assertFalse(
            "the home-dashboard question must still be asked",
            config.setupHomeDashboardChosen,
        )
        assertFalse(
            "a first run must not be framed as a repair",
            config.setupEverCompleted,
        )
        listOf(
            "device_local_setup_entity_filter_answered",
            "device_local_setup_home_dashboard_chosen",
            "device_local_setup_ever_completed",
        ).forEach { key ->
            assertFalse("$key must not be durably stamped", values[key] == true)
        }
    }

    @Test fun `restarting repeatedly never answers a handed-over panel's questions`() {
        // The migration retries until it finds evidence, so once is not proof.
        val values = mutableMapOf<String, Any?>(
            "ha_url" to "http://ha.local:8123",
            "ha_setup_handover" to true,
        )
        repeat(5) { boot(values) }

        val config = boot(values)
        assertFalse(config.setupEntityFilterAnswered)
        assertFalse(config.setupHomeDashboardChosen)
        assertFalse(config.setupEverCompleted)
    }

    /**
     * The other direction, which the fix must not break: a genuinely pre-existing install still
     * migrates. Without the marker, a stored URL is exactly what an older panel looks like, and holding
     * such a panel on a question it predates is the defect the migration exists to prevent.
     */
    @Test fun `a pre-existing install with a typed URL still migrates`() {
        val values = mutableMapOf<String, Any?>("ha_url" to "http://ha.local:8123")
        val config = boot(values)

        assertTrue("an upgraded panel must not be held on a new question", config.setupEntityFilterAnswered)
        assertTrue(config.setupHomeDashboardChosen)
        assertTrue(config.setupEverCompleted)
    }

    @Test fun `the marker excuses only the URL, never a broker or a learned filter`() {
        // A panel Home Assistant adopted that ALSO carries pre-tracking evidence really is pre-existing.
        mapOf(
            "mqtt_broker" to "tcp://192.0.2.1:1883",
            "dashboard_entity_learning" to true,
        ).forEach { (key, value) ->
            val values = mutableMapOf<String, Any?>(
                "ha_url" to "http://ha.local:8123",
                "ha_setup_handover" to true,
                key to value,
            )
            val config = boot(values)
            assertTrue("$key is still evidence of a pre-existing install", config.setupEntityFilterAnswered)
        }
    }

    // ---- harness -------------------------------------------------------------------------------------

    /**
     * A service start over surviving durable state. The migration runs here for the same reason
     * PaneldService runs it here: before anything reads the answers, and before any renderer starts.
     */
    private fun boot(values: MutableMap<String, Any?>): Config =
        Config(fakePreferences(values)).also { it.migrateSetupQuestionsForExistingInstall() }

    /** A test-owned input builder for exercising the pure journey authority. */
    private fun journeyInputs(
        config: Config,
        mqtt: MqttSetupState = MqttSetupState.CONNECTED,
        renderer: RendererChoice = RendererChoice.Builtin,
        haCredentialedOverride: Boolean? = null,
        proof: RenderProof = RenderProof(ProofSource.BUILTIN_FRONTEND_CONNECTED, certain = true, observedAtMs = 1L),
        webViewTooOld: Boolean = false,
    ): SetupJourney.Inputs {
        // The shared rule, not a third copy of it. Reconstructing the predicate here is what let this
        // sweep pass while a handed-over panel was mis-read: the copy knew nothing about the marker, so
        // no amount of breadth could reach the state the handover creates.
        val configuredBeforeTracking = panelConfiguredBeforeSetupTracking(
            haUrl = config.haUrl,
            haSetupHandover = config.haSetupHandover,
            otherEvidence = config.mqttBroker.isNotBlank() || config.dashboardPackage.isNotBlank(),
        )
        val preTracking = !config.setupIdentityConfirmed && configuredBeforeTracking
        return SetupJourney.Inputs(
            identityConfirmed = config.setupIdentityConfirmed || configuredBeforeTracking,
            // A real panel always resolves an id: the generated default reads Android settings, which the
            // JVM seam cannot serve. Substituting a stable one keeps the blank-panel case in the sweep.
            panelId = runCatching { config.panelId }.getOrElse { "alpha" },
            brokerConfigured = config.mqttBroker.isNotBlank(),
            mqttUserConfigured = config.mqttUser.isNotBlank(),
            mqttPasswordConfigured = config.mqttPassword.isNotEmpty(),
            mqtt = mqtt,
            renderer = renderer,
            haUrl = config.haUrl,
            haCredentialed = haCredentialedOverride ?: (config.haToken.isNotBlank() || config.haRefreshToken.isNotBlank()),
            haOAuthInFlight = false,
            discovery = DiscoveryResult(),
            entityFilterAnswered = config.setupEntityFilterAnswered || preTracking,
            homeDashboardChosen = config.setupHomeDashboardChosen || preTracking,
            webViewTooOld = webViewTooOld && renderer == RendererChoice.Builtin,
            proof = proof,
            currentFingerprint = "",
        )
    }

    /**
     * Durable preferences that survive a Config instance, which is what makes a restart expressible: the
     * same map handed to a new Config is the same panel starting again.
     */
    private fun fakePreferences(values: MutableMap<String, Any?>): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAll" -> values.toMap()
                "getString" -> values[args!![0]] as? String ?: args[1]
                "getStringSet" -> values[args!![0]] as? Set<*> ?: args[1]
                "getInt" -> values[args!![0]] as? Int ?: args[1]
                "getLong" -> values[args!![0]] as? Long ?: args[1]
                "getFloat" -> values[args!![0]] as? Float ?: args[1]
                "getBoolean" -> values[args!![0]] as? Boolean ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "edit" -> fakeEditor(values)
                "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
                "toString" -> "FakeSharedPreferences"
                else -> error("unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences

    private fun fakeEditor(values: MutableMap<String, Any?>): SharedPreferences.Editor {
        val writes = LinkedHashMap<String, Any?>()
        val removals = LinkedHashSet<String>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when {
                method.name.startsWith("put") -> editor.also {
                    writes[args!![0] as String] = args[1]
                    removals.remove(args[0])
                }
                method.name == "remove" -> editor.also {
                    val key = args!![0] as String
                    writes.remove(key)
                    removals.add(key)
                }
                method.name == "clear" -> editor.also { values.clear() }
                method.name == "commit" || method.name == "apply" -> {
                    removals.forEach(values::remove)
                    values.putAll(writes)
                    writes.clear()
                    removals.clear()
                    if (method.name == "commit") true else null
                }
                method.name == "toString" -> "FakeEditor"
                else -> error("unexpected Editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        return editor
    }
}
