package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.LiveSettingRequestOutcome
import io.github.maxlyth.hapaneld.control.SystemController
import io.github.maxlyth.hapaneld.migration.RestoreAttempt
import io.github.maxlyth.hapaneld.platform.ActivityRef
import io.github.maxlyth.hapaneld.platform.SystemEnv
import io.github.maxlyth.hapaneld.util.BorrowedRendererSettings
import io.github.maxlyth.hapaneld.util.InstallProgress
import io.github.maxlyth.hapaneld.util.RendererPreparationCoordinator
import io.github.maxlyth.hapaneld.util.RendererPreparationState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptedRestoreTransactionTest {
    @Test fun rendererFailureRollsBackToExecutionTimeValuesUsingTheAdmittedTicket() = runBlocking {
        checkRendererFailure(supersede = false)
    }

    @Test fun rendererFailureDoesNotRollBackANewerConfiguration() = runBlocking {
        checkRendererFailure(supersede = true)
    }

    private suspend fun checkRendererFailure(supersede: Boolean) {
        PaneldServerHttpFixture().use { fixture ->
            val config = fixture.config
            config.setFriendlyName("Restore contract panel")
            var liveReads = 0
            var transactionReads = 0
            var rendererAttempts = 0
            val values = ConfigValueProjection(
                config = config,
                configLiveValues = { liveReads++; emptyMap() },
                renderedLiveValues = { error("No page render") },
                pendingLiveSettings = { emptyMap() },
                stalledLiveSettings = { emptySet() },
                proximityJson = { error("No page render") },
                powerSafetyJson = { error("No page render") },
                haAreaCatalogJson = { error("No page render") },
            )
            val renderer = RendererPreparationCoordinator(
                builtinPackage = "builtin",
                state = { RendererPreparationState("builtin", "") },
                borrow = { BorrowedRendererSettings("http://ha.test", "", "", 0, "", null) },
                persist = {
                    rendererAttempts++
                    assertEquals("restored-panel", config.panelId)
                    false
                },
            )
            val system = SystemController(object : SystemEnv {
                override val ownPackage = "io.github.maxlyth.hapaneld"
                override fun isInstalled(pkg: String) = false
                override fun launchComponent(pkg: String): String? = error("No launch after failed preparation")
                override fun homeActivities(): List<ActivityRef> = error("No HOME after failed preparation")
                override fun defaultHome(): ActivityRef? = error("No HOME after failed preparation")
                override fun directStart(component: String) = error("No launch after failed preparation")
            })
            val executor = AcceptedConfigTransaction.restoreExecutor(
                appContext = fixture.context,
                config = config,
                transaction = {
                    transactionReads++
                    AcceptedConfigTransaction(
                        config = config,
                        revisions = RevisionStore(fixture.directory),
                        rendererPreparation = renderer,
                        system = system,
                        values = values,
                        applySetting = { _, _ -> LiveSettingRequestOutcome.APPLIED },
                        onEntityTargetChanged = {},
                        setEntityLearningEnabled = { true },
                        effectiveDashboardIsBuiltin = { false },
                        requestTameReconcileAfterCommit = { true },
                        snapInvalidate = {},
                        onReconfigure = {
                            if (supersede) config.setPanelId("newer-panel")
                        },
                    )
                },
                restoreCompanion = { error("No Companion payload") },
                profileAdmin = null,
                onProfileRestart = { error("No profile payload") },
                onProfileRestartAbort = { error("No profile payload") },
                onDurableStateRestored = { error("No state payload") },
                onWakeWordsChanged = { error("No wake word payload") },
            )
            assertEquals(0, transactionReads)
            assertEquals(0, liveReads)
            config.setPanelId("execution-time-panel")
            val ticket = requireNotNull(InstallProgress.start("restore"))
            val answers = mutableListOf<Boolean>()
            try {
                executor.execute(
                    RestoreConfigPlan(mapOf("panel_id" to "restored-panel"), emptyList(), emptyList()),
                    null, null, null, null, emptyMap(), emptyList(), false, false,
                    emptyList(), null, ticket, RestoreAttempt { answers += it },
                )
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertEquals(1, rendererAttempts)
                assertTrue(liveReads > 0)
                assertEquals(if (supersede) "newer-panel" else "execution-time-panel", config.panelId)
                assertEquals(if (supersede) "partial" else "failed", result.getString("status"))
                assertEquals(
                    if (supersede) "rollback_failed" else "rolled_back",
                    result.getJSONObject("config").getString("status"),
                )
                assertEquals(listOf(false), answers)
                assertFalse(InstallProgress.running)
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }
}
