package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First run must be completable from the panel itself, with no browser and no ADB.
 *
 * The deadlock this guards was observed on hardware: saving a Home Assistant URL relaunches
 * DashboardActivity, the readiness gate rejects it because no token exists yet, and it bounces to the
 * QR screen — while the on-panel sign-in that would mint that token sits *behind* the same gate. The
 * panel visibly reloaded several times and never showed a login. A second, quieter instance of the same
 * shape sat behind it: the entity-bootstrap hold waits for data that needs an authenticated connection,
 * and it ran before the sign-in branch, so fixing only the gate would have moved the deadlock rather
 * than removed it.
 *
 * These are ordering and reachability properties of a UI path that cannot be exercised in a JVM test, so
 * they are pinned against source structure — the same approach as LaunchScreenWiringContractTest.
 */
class PanelHaSignInReachabilityTest {
    private val dashboard = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt"),
    ).first { it.isFile }.readText()

    @Test fun aConfiguredUrlWithoutCredentialsIsTheOnlySignInPendingState() {
        assertTrue(haSignInPending("http://ha.local:8123", "", ""))
        // Either credential is enough to render, so neither is a sign-in-pending state.
        assertFalse(haSignInPending("http://ha.local:8123", "token", ""))
        assertFalse(haSignInPending("http://ha.local:8123", "", "refresh"))
        // No URL means nothing to sign in to: this must still strand to the configure surface.
        assertFalse(haSignInPending("", "", ""))
        assertFalse(haSignInPending("   ", "", ""))
    }

    @Test fun bothReadinessGatesConsultSignInBeforeBouncingToTheConfigureSurface() {
        // The gate itself is unchanged — MainActivity, LaunchScreenPolicy and PostUpdateReturnPolicy all
        // depend on builtInRendererReady() meaning "can render right now". Only the response to it moved.
        val gates = Regex("if \\(!config\\.builtInRendererReady\\(\\)\\) \\{").findAll(dashboard).toList()
        assertTrue("expected the onCreate and onNewIntent gates", gates.size == 2)
        gates.forEach { gate ->
            val block = dashboard.substring(gate.range.first, minOf(gate.range.first + 1600, dashboard.length))
            val escape = block.indexOf("haSignInPending(")
            val bounce = block.indexOf("fallbackToFirstRunSurface()")
            assertTrue("gate does not offer the sign-in escape", escape >= 0)
            assertTrue("gate no longer strands when there is nothing to sign in to", bounce >= 0)
            assertTrue("gate bounces before considering sign-in", bounce > escape)
        }
    }

    @Test fun signInIsCheckedBeforeTheEntityBootstrapHold() {
        val body = dashboard.substring(
            dashboard.indexOf("private fun buildAndLoad(config: Config)"),
            dashboard.indexOf("private fun buildCompatibleAndLoad("),
        )
        val signIn = body.indexOf("showPhysicalHaSignIn(url)")
        val hold = body.indexOf("showWaitingForEntityBootstrap()")
        assertTrue(signIn >= 0 && hold >= 0)
        // The hold derives only from the learning/filter flags, not from credentials, so ahead of the
        // sign-in branch it is entered unconditionally and never left.
        assertTrue("the entity-bootstrap hold must not precede sign-in", signIn < hold)
    }

    @Test fun aRelaunchDoesNotRestartASignInAlreadyOnScreen() {
        // Every config save relaunches this activity, and the browser form posts every key on each save.
        // Rebuilding the WebView here would discard a part-typed password.
        val body = dashboard.substring(
            dashboard.indexOf("private fun showPhysicalHaSignIn(haUrl: String)"),
            dashboard.indexOf("private fun showPhysicalHaSignIn(haUrl: String)") + 900,
        )
        val guard = body.indexOf("signInShownForUrl == haUrl")
        val teardown = body.indexOf("teardownWeb()")
        assertTrue("no same-endpoint guard before teardown", guard in 0 until teardown)
        // …and the marker must be cleared when the WebView does go away, or sign-in can never rebuild.
        val cleared = dashboard.substring(
            dashboard.indexOf("private fun teardownWeb()"),
            dashboard.indexOf("private fun teardownWeb()") + 400,
        )
        assertTrue(cleared.contains("signInShownForUrl = null"))
    }

    @Test fun thePanelLeavesSignInForTheDashboardOnceCredentialsExist() {
        // The hardware loop: after a browser sign-in the panel relaunched but reused the bare on-panel
        // sign-in WebView (OAuth-only client, no dashboard bridge) to load the dashboard, so Home
        // Assistant found no external auth and bounced back to its own login — the panel appeared to loop
        // the sign-in page. onNewIntent must detect credentials-now-present while sign-in is showing and
        // rebuild, ahead of the WebView-reuse path.
        val onNewIntent = dashboard.substring(
            dashboard.indexOf("override fun onNewIntent"),
            dashboard.indexOf("val nav = BuiltinDashboard.consumeNavPath()"),
        )
        val exit = onNewIntent.indexOf("signInShownForUrl != null && !haSignInPending(")
        val reuse = onNewIntent.indexOf("compatibilityReadyUrl != null")
        assertTrue("no credentials-arrived branch in onNewIntent", exit >= 0)
        assertTrue("the sign-in exit must precede the WebView-reuse path", exit < reuse)
        val block = onNewIntent.substring(exit, exit + 700)
        assertTrue("must rebuild, not reuse", block.contains("teardownWeb()") && block.contains("buildAndLoad(config)"))
    }

    @Test fun theMemoryCeilingReloadCannotDiscardASignInInProgress() {
        // The periodic reload measures idle from lastFullLoadAt, which sign-in never sets because it
        // performs no dashboard load. On a panel up longer than the interval that reads as idle-for-all-
        // of-uptime, so without this guard the first check reloads and wipes a part-entered login —
        // recreating the very reload churn this screen exists to end.
        val periodic = dashboard.substring(
            dashboard.indexOf("private fun onPeriodicCheck()"),
            dashboard.indexOf("override fun onTrimMemory("),
        )
        val guard = periodic.indexOf("signInShownForUrl != null")
        val reload = periodic.indexOf("doReload")
        assertTrue("no sign-in guard in the periodic check", guard >= 0)
        assertTrue("the guard must precede any reload decision", reload > guard)
    }

    @Test fun anAlreadyWorkingPanelIsNeverHeldByTheEntityFilterQuestion() {
        // The regression this pins was found by a canary on two configured panels: the answer flag is new, so
        // it defaults false on every upgrade, and the renderer held their dashboards to ask a question they had
        // never been asked. Worse, it could not resolve itself — the completion stamp that releases the hold is
        // earned by rendering, which was the thing being held.
        assertFalse(
            "a panel that already finished setup must render, whatever the new flag says",
            entityFilterQuestionPending(
                builtinRenderer = true,
                haUrl = "http://ha.local:8123",
                haToken = "t",
                haRefreshToken = "",
                entityFilterAnswered = false,
                setupEverCompleted = true,
            ),
        )
        // A genuine first run is still held — that is the whole point of the question.
        assertTrue(
            entityFilterQuestionPending(
                builtinRenderer = true,
                haUrl = "http://ha.local:8123",
                haToken = "t",
                haRefreshToken = "",
                entityFilterAnswered = false,
                setupEverCompleted = false,
            ),
        )
        // And answering releases it either way, so declining is never a dead end.
        assertFalse(
            entityFilterQuestionPending(
                builtinRenderer = true,
                haUrl = "http://ha.local:8123",
                haToken = "t",
                haRefreshToken = "",
                entityFilterAnswered = true,
                setupEverCompleted = false,
            ),
        )
    }

    @Test fun theEntityFilterHoldNeverPreemptsSignInOrAForeignRenderer() {
        // It requires a credential, so it can never mask the sign-in states haSignInPending handles, and a
        // foreign renderer's subscription is not ours to narrow.
        assertFalse(
            "no credential yet: sign-in owns this state",
            entityFilterQuestionPending(
                builtinRenderer = true, haUrl = "http://ha.local:8123", haToken = "", haRefreshToken = "",
                entityFilterAnswered = false, setupEverCompleted = false,
            ),
        )
        assertFalse(
            entityFilterQuestionPending(
                builtinRenderer = false, haUrl = "http://ha.local:8123", haToken = "t", haRefreshToken = "",
                entityFilterAnswered = false, setupEverCompleted = false,
            ),
        )
    }

    @Test fun theUpgradeMigrationsAreVersionedAndRunBeforeTheRenderer() {
        // Each newly introduced blocking question needs its own durable migration marker. Reusing v1 would
        // skip the new migration on every panel that had already consumed the older release migration.
        val config = File("src/main/kotlin/io/github/maxlyth/hapaneld/Config.kt").readText()
        val migration = config.substring(config.indexOf("fun migrateSetupQuestionsForExistingInstall()"))
            .substringBefore("\n    /**")
        assertTrue(migration.contains("if (!prefs.getBoolean(SETUP_QUESTION_MIGRATION_PREF, false))"))
        assertTrue(migration.contains("migrateHomeDashboardQuestionForExistingInstall()"))
        assertTrue(config.contains("SETUP_HOME_DASHBOARD_MIGRATION_PREF"))
        assertTrue(config.contains("device_local_setup_home_dashboard_migrated_v2"))

        // It must run before any renderer can start, or the first load races the exemption.
        val service = File("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt").readText()
        assertTrue(service.contains("config.migrateSetupQuestionsForExistingInstall()"))
        assertTrue(
            "the migration must precede ensurePanelId and everything after it",
            service.indexOf("config.migrateSetupQuestionsForExistingInstall()") <
                service.indexOf("config.ensurePanelId()"),
        )
    }

    @Test fun aPanelWithFilteringAlreadyOnIsNeverHeldToAnswerWhetherToTurnItOn() {
        // Panels could be stranded on the hold screen while running a filtered dashboard: the
        // one-shot migration had recorded "not a pre-existing install" after the configuration read back blank
        // at startup, and nothing else exempted them. The filter being ON is durable proof the question is
        // moot — it defaults off — and it is evaluated at the moment of the check rather than trusted from a
        // flag written once, which is the property that makes it a safety net rather than another guess.
        assertFalse(
            entityFilterQuestionPending(
                builtinRenderer = true, haUrl = "http://ha.local:8123", haToken = "t", haRefreshToken = "",
                entityFilterAnswered = false, setupEverCompleted = false, entityFilterEnabled = true,
            ),
        )
        // A fresh panel has it off, so the question is still asked.
        assertTrue(
            entityFilterQuestionPending(
                builtinRenderer = true, haUrl = "http://ha.local:8123", haToken = "t", haRefreshToken = "",
                entityFilterAnswered = false, setupEverCompleted = false, entityFilterEnabled = false,
            ),
        )
    }

    @Test fun theUpgradeMigrationRetriesUntilItSeesEvidenceAndCountsAnEnabledFilter() {
        // Marking itself done after a blank read is what stranded deployed panels, so it must not record completion
        // without evidence, and an already-enabled filter must count as evidence.
        val config = File("src/main/kotlin/io/github/maxlyth/hapaneld/Config.kt").readText()
        val m = config.substring(config.indexOf("fun migrateSetupQuestionsForExistingInstall()"))
            .substringBefore("\n    /**")
        val evidence = config.substring(config.indexOf("private fun configuredBeforeSetupQuestionTracking()"))
            .substringBefore("\n\n")
        // This migration's own evidence: an already-enabled filter and a stored broker both count.
        assertTrue(evidence.contains("dashboardEntityLearningEnabled || mqttBroker.isNotBlank()"))
        // The Home Assistant URL term is NOT spelled here. It is delegated to the shared rule so this
        // copy and the journey's cannot disagree about a handed-over URL, which is written before the
        // wizard opens and must never read as evidence of an install older than these questions.
        assertTrue(
            "the URL term must be delegated, not re-spelled",
            evidence.contains("panelConfiguredBeforeSetupTracking(") && evidence.contains("haSetupHandover ="),
        )
        assertFalse(
            "a bare haUrl term here would bring back the defect the shared rule removes",
            evidence.contains("|| haUrl.isNotBlank()"),
        )
        assertTrue("configured evidence must be the only non-wizard completion path",
            m.contains("else if (configuredBeforeSetupQuestionTracking())"))
        assertFalse("a blank fresh panel must leave v1 retryable",
            m.substringAfter("else if (configuredBeforeSetupQuestionTracking())").substringAfter("}").contains(
                "putBoolean(SETUP_QUESTION_MIGRATION_PREF, true)",
            ))
    }

    @Test fun theMigrationNeverStampsAPanelWhoseGuidedSetupHasBegun() {
        // Second hardware walk, 2026-07-26: the retrying migration read the WIZARD'S OWN broker save as
        // upgrade evidence on the first mid-journey service restart and durably stamped every question
        // answered — the panel rendered unfiltered, the journey reported complete, and the sign-in return
        // dumped the user to Configure with the dashboard and filter questions never asked. Identity
        // confirmation is written only by the wizard, so its presence proves the config evidence is
        // mid-journey: the migration must finish WITHOUT stamping and leave the answers to the wizard.
        val config = File("src/main/kotlin/io/github/maxlyth/hapaneld/Config.kt").readText()
        val m = config.substring(config.indexOf("fun migrateSetupQuestionsForExistingInstall()"))
            .substringBefore("\n    /**")
        val gate = m.substringAfter("if (setupIdentityConfirmed) {").substringBefore("}")
        assertTrue("the gate must come before any evidence is weighed",
            m.indexOf("if (setupIdentityConfirmed)") < m.indexOf("configuredBeforeSetupQuestionTracking()"))
        assertTrue("the gate records the marker (migration is done for a wizard-owned panel)",
            gate.contains("putBoolean(SETUP_QUESTION_MIGRATION_PREF, true)"))
        listOf("SETUP_ENTITY_FILTER_ANSWERED_PREF", "SETUP_HOME_DASHBOARD_CHOSEN_PREF", "SETUP_EVER_COMPLETED_PREF")
            .forEach { flag -> assertFalse("the gate must stamp no answers ($flag)", gate.contains(flag)) }
    }

    @Test fun bothHoldSitesConsultTheEnabledFilter() {
        val dashboard = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()
        assertEquals(
            "both the pre-render gate and its poll must pass the enabled signal",
            2,
            Regex("""entityFilterEnabled = config\.dashboardEntityLearningEnabled""").findAll(dashboard).count(),
        )
    }
}
