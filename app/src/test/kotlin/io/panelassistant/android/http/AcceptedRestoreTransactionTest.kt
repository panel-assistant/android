package io.panelassistant.android.http

import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.HaAuthSnapshot
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.OwnerChoicesPlan
import io.panelassistant.android.device.profile.DeviceFacts
import io.panelassistant.android.device.profile.ProfileAdmin
import io.panelassistant.android.device.profile.ProfileBackupDecodeResult
import io.panelassistant.android.device.profile.ProfileMutation
import io.panelassistant.android.device.profile.ProfilePreferences
import io.panelassistant.android.device.profile.ProfileYaml
import io.panelassistant.android.device.profile.RuntimeProfileRegistry
import io.panelassistant.android.device.profile.testProfileDocument
import io.panelassistant.android.haAuthSnapshot
import io.panelassistant.android.setHaClientId
import io.panelassistant.android.setHaConnection
import io.panelassistant.android.setHaRefreshToken
import io.panelassistant.android.setHaTokenExpiry
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.SystemEnv
import io.panelassistant.android.util.BorrowedRendererSettings
import io.panelassistant.android.util.InstallProgress
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.RendererPreparationState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptedRestoreTransactionTest {
    @Test fun retainedOwnerChoiceMakesAStaleCatalogRestorePartialAfterRealConfigRollback() = runBlocking {
        for (stale in listOf(false, true)) for (withState in listOf(false, true)) {
            withRouteConfig { config, persistence, _, _ ->
              PaneldServerHttpFixture().use { fixture ->
                config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Restore contract panel")
                assertTrue(config.applyBatch { config.setPanelId("before") })
                val zoomBefore = config.getRaw(requireNotNull(SettingsRegistry.spec("dashboard_zoom")))
                val preferences = object : ProfilePreferences {
                    val data = mutableMapOf<String, Any?>()
                    override fun getString(key: String, default: String) = data[key] as? String ?: default
                    override fun getLong(key: String, default: Long) = data[key] as? Long ?: default
                    override fun put(vararg values: Pair<String, Any?>): Boolean {
                        values.forEach { (key, value) -> data[key] = value }
                        return true
                    }
                }
                val generic = ProfileYaml.serialize(testProfileDocument(id = "generic", fallback = true))
                val registry = RuntimeProfileRegistry(
                    fixture.directory, preferences, { mapOf("generic.yaml" to generic) },
                    DeviceFacts("test-panel", "test-device", "fw-1"), "1.0.0", { 1000L },
                )
                val payload = registry.exportBackup()
                val plan = registry.planBackupRestore(payload)
                assertTrue(plan.valid)
                var imported = false
                val executor = restoreExecutor(
                    fixture, capabilities = { Capabilities(networkAdb = true) }, persist = { true },
                    onReconfigure = {
                        if (stale && !imported) {
                            // This production callback runs after the final plan and durable config
                            // commit, before ancillary state and the profile catalog CAS.
                            assertEquals("restored", config.panelId)
                            val raw = ProfileYaml.serialize(testProfileDocument(id = "community.concurrent"))
                            val preview = registry.preview(raw)
                            assertTrue(preview.compatible)
                            assertTrue(registry.importProfile(raw, requireNotNull(preview.previewToken)) is ProfileMutation.Success)
                            imported = true
                        }
                    },
                    profileAdmin = registry, config = config,
                )
                val ticket = requireNotNull(InstallProgress.start("restore"))
                try {
                    runBlocking { executor.execute(
                        RestoreConfigPlan(mapOf("panel_id" to "restored", "dashboard_zoom" to "120"), emptyList(), emptyList()),
                        null, ProfileBackupDecodeResult(payload, emptyList()), plan, null, emptyList(), false,
                        emptyList(), null, ticket,
                        ownerChoices = OwnerChoicesPlan(if (withState) mapOf("ui_dashboard_layout" to "retained-layout") else emptyMap()),
                    )
                    }
                    val body = JSONObject(InstallProgress.json())
                    val result = body.getJSONObject("result")
                    assertEquals(stale, imported)
                    assertEquals(body.toString(), if (stale) "before" else "restored", config.panelId)
                    // initialize opens a fresh SQLite connection; these are durable rows, not
                    // the accepting Config owner's cached preferences.
                    val durable = persistence.initialize()
                    assertEquals(if (stale) "before" else "restored", durable["panel_id"])
                    assertEquals(if (stale) zoomBefore else "120", durable["dashboard_zoom"].toString())
                    if (withState) {
                        assertEquals("retained-layout", durable["ui_dashboard_layout"])
                        assertEquals("succeeded", result.getJSONObject("state").getString("status"))
                        assertEquals(1, result.getJSONObject("state").getInt("items"))
                    } else assertFalse(result.has("state"))
                    if (stale) {
                        assertTrue(registry.status().catalogRevision != plan.expectedCatalogRevision)
                        assertEquals("failed", result.getJSONObject("profiles").getString("status"))
                        assertEquals("rolled_back", result.getJSONObject("config").getString("status"))
                    }
                    assertEquals(if (!stale) "succeeded" else if (withState) "partial" else "failed", result.getString("status"))
                    if (stale && withState) {
                        assertEquals("restore-partial", body.getJSONObject("presentation").getString("code"))
                    }
                } finally {
                    InstallProgress.finish(ticket, "test cleanup")
                }
              }
            }
        }
    }

    @Test fun rendererFailureRollsBackToExecutionTimeValuesUsingTheAdmittedTicket() = runBlocking {
        checkRendererFailure(supersede = false)
    }

    @Test fun rendererFailureDoesNotRollBackANewerConfiguration() = runBlocking {
        checkRendererFailure(supersede = true)
    }

    @Test fun aRestoreWhoseCommitIsStaleIsReportedAsFailedNotCompleted() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            val config = fixture.config
            config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Restore contract panel")
            config.setPanelId("execution-time-panel")
            var moved = false
            val executor = restoreExecutor(
                fixture,
                // Sampled after the executor's revision baseline and before the commit: a newer
                // configuration lands in between, so the restore commit must be refused as stale.
                capabilities = {
                    if (!moved) { moved = true; config.setPanelId("moved-panel") }
                    Capabilities(networkAdb = true)
                },
                persist = { error("A stale commit never prepares the renderer") },
                onReconfigure = { error("A stale commit never reconfigures") },
            )
            val ticket = requireNotNull(InstallProgress.start("restore"))
            try {
                executor.execute(
                    RestoreConfigPlan(mapOf("panel_id" to "restored-panel"), emptyList(), emptyList()),
                    null, null, null, null, emptyList(), false,
                    emptyList(), null, ticket,
                )
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertTrue(moved)
                assertEquals("failed", result.getString("status"))
                assertEquals("failed", result.getJSONObject("config").getString("status"))
                assertEquals("moved-panel", config.panelId)
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    @Test fun aRestoreThatMovesThePanelToAnotherHomeAssistantDropsThePreviousSignIn() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            val config = fixture.config
            config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Restore contract panel")
            assertTrue(config.setHaConnection("http://a.test:8123", "a-access"))
            config.setHaRefreshToken("a-refresh")
            config.setHaTokenExpiry(9_999_999_999L)
            config.setHaClientId("a-client")
            val executor = restoreExecutor(
                fixture,
                capabilities = { Capabilities(networkAdb = true) },
                persist = { true },
                onReconfigure = {},
            )
            val ticket = requireNotNull(InstallProgress.start("restore"))
            try {
                executor.execute(
                    RestoreConfigPlan(mapOf("ha_url" to "http://b.test:8123"), emptyList(), emptyList()),
                    null, null, null, null, emptyList(), false,
                    emptyList(), null, ticket,
                )
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertEquals(result.toString(), "succeeded", result.getString("status"))
                assertEquals(HaAuthSnapshot("http://b.test:8123", "", "", 0L, ""), config.haAuthSnapshot())
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    private fun restoreExecutor(
        fixture: PaneldServerHttpFixture,
        capabilities: () -> Capabilities,
        persist: () -> Boolean,
        onReconfigure: () -> Unit,
        onLiveRead: () -> Unit = {},
        onTransactionRead: () -> Unit = {},
        profileAdmin: ProfileAdmin? = null,
        config: io.panelassistant.android.Config = fixture.config,
    ): RestoreExecutor {
        val values = ConfigValueProjection(
            config = config,
            configLiveValues = { onLiveRead(); emptyMap() },
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
            persist = { persist() },
        )
        val system = SystemController(object : SystemEnv {
            override val ownPackage = "io.github.maxlyth.hapaneld"
            override fun isInstalled(pkg: String) = false
            override fun launchComponent(pkg: String): String? = error("No launch after failed preparation")
            override fun homeActivities(): List<ActivityRef> = error("No HOME after failed preparation")
            override fun defaultHome(): ActivityRef? = error("No HOME after failed preparation")
            override fun directStart(component: String) = error("No launch after failed preparation")
        })
        return AcceptedConfigTransaction.restoreExecutor(
            appContext = fixture.context,
            config = config,
            capabilities = capabilities,
            transaction = {
                onTransactionRead()
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
                    onReconfigure = { onReconfigure() },
                )
            },
            restoreCompanion = { error("No Companion payload") },
            profileAdmin = profileAdmin,
            onProfileRestart = { true },
            onProfileRestartAbort = { true },
            onDurableStateRestored = {},
            onWakeWordsChanged = { error("No wake word payload") },
        )
    }

    private suspend fun checkRendererFailure(supersede: Boolean) {
        PaneldServerHttpFixture().use { fixture ->
            val config = fixture.config
            config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Restore contract panel")
            var liveReads = 0
            var transactionReads = 0
            var rendererAttempts = 0
            val executor = restoreExecutor(
                fixture,
                capabilities = { Capabilities(networkAdb = true) },
                persist = {
                    rendererAttempts++
                    assertEquals("restored-panel", config.panelId)
                    false
                },
                onReconfigure = { if (supersede) config.setPanelId("newer-panel") },
                onLiveRead = { liveReads++ },
                onTransactionRead = { transactionReads++ },
            )
            assertEquals(0, transactionReads)
            assertEquals(0, liveReads)
            config.setPanelId("execution-time-panel")
            val ticket = requireNotNull(InstallProgress.start("restore"))
            try {
                executor.execute(
                    RestoreConfigPlan(mapOf("panel_id" to "restored-panel"), emptyList(), emptyList()),
                    null, null, null, null, emptyList(), false,
                    emptyList(), null, ticket,
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
                assertFalse(InstallProgress.running)
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }
}
