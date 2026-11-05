package io.panelassistant.android.http

import io.panelassistant.android.assist.wakeword.WakeWordCatalog
import io.panelassistant.android.backup.WakeWordBackup
import io.panelassistant.android.dashboard.EntityCatalogSchema
import io.panelassistant.android.dashboard.EntityCatalogStore
import io.panelassistant.android.persistence.ProximityCalibrationBackup
import io.panelassistant.android.persistence.ProximityCalibrationCustody
import io.panelassistant.android.sensors.ProximityCalibrationEngine
import io.panelassistant.android.sensors.ProximityCalibrationRuntime
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
import java.io.File
import java.sql.DriverManager

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

    @Test fun retainedModelsArePartialAfterRealConfigRollbackAndRefusedImportsAreNotSuccess() = runBlocking {
        for (failConfig in listOf(false, true)) for (payload in listOf("new", "refused", "absent")) {
            withRouteConfig { config, persistence, _, _ ->
                PaneldServerHttpFixture().use { fixture ->
                    val directory = File(fixture.directory, "models")
                    val original = importedWakeWord("porch")
                    if (payload == "refused") {
                        assertEquals(listOf("porch"), WakeWordBackup.restore(wakeWordCatalog(directory), listOf(original)).restored)
                    }
                    val catalog = wakeWordCatalog(directory, accepts = payload != "refused")
                    val imported = WakeWordCatalog.ImportedFiles("porch", original.manifest, byteArrayOf(4, 5, 6))
                    val words = if (payload == "absent") emptyList() else listOf(imported)
                    val before = config.panelId
                    var notifications = 0
                    var applyAttempts = 0
                    val executor = restoreExecutor(
                        fixture, capabilities = { Capabilities() }, persist = { true }, config = config,
                        onWakeWordsChanged = {
                            notifications++
                            assertTrue(wakeWordCatalog(directory).exportImported().single().model.contentEquals(imported.model))
                        },
                        onReconfigure = {
                            if (config.panelId == "restored") {
                                applyAttempts++
                                assertEquals(if (payload == "new") 1 else 0, notifications)
                                if (failConfig) error("configuration rearming failed")
                            }
                        },
                    )
                    val ticket = requireNotNull(InstallProgress.start("restore"))
                    try {
                        runBlocking { executor.execute(
                            RestoreConfigPlan(mapOf("panel_id" to "restored", "dashboard_zoom" to "120"), emptyList(), emptyList()),
                            null, null, null, null, emptyList(), false, words, catalog, ticket,
                        ) }
                        val body = JSONObject(InstallProgress.json())
                        val result = body.getJSONObject("result")
                        assertEquals(1, applyAttempts)
                        assertEquals(if (failConfig) before else "restored", persistence.initialize()["panel_id"])
                        val retained = wakeWordCatalog(directory).exportImported()
                        if (payload == "absent") {
                            assertTrue(retained.isEmpty())
                            assertFalse(result.has("wake_words"))
                        } else {
                            assertTrue(retained.single().model.contentEquals(if (payload == "new") imported.model else original.model))
                            assertEquals(if (payload == "new") 1 else 0, result.getJSONObject("wake_words").getInt("items"))
                        }
                        val expected = if (failConfig) {
                            if (payload == "new") "partial" else "failed"
                        } else if (payload == "refused") "partial" else "succeeded"
                        assertEquals("$failConfig/$payload: $body", expected, result.getString("status"))
                        if (failConfig) {
                            assertEquals("rolled_back", result.getJSONObject("config").getString("status"))
                            assertEquals("rolled_back", result.getJSONObject("rollback").getString("status"))
                            assertEquals(if (payload == "new") "restore-partial" else "restore-failed", body.getJSONObject("presentation").getString("code"))
                            assertEquals(if (payload == "new") "Restore partially completed" else "Restore failed", body.getString("message"))
                        }
                    } finally {
                        InstallProgress.finish(ticket, "test cleanup")
                    }
                }
            }
        }
    }

    @Test fun retainedCalibrationMakesAStaleCatalogRestorePartialAfterRealConfigRollback() = runBlocking {
        for (stale in listOf(true, false)) for (payload in listOf("missing", "existing", "absent")) {
            withRouteConfig { config, persistence, _, _ ->
                PaneldServerHttpFixture().use { fixture ->
                    val before = config.panelId
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
                    val profiles = registry.exportBackup()
                    val plan = registry.planBackupRestore(profiles)
                    assertTrue(plan.valid)
                    val snapshot = ProximityCalibrationRuntime.encode(
                        ProximityCalibrationEngine.Calibration(mode = ProximityCalibrationEngine.Mode.RANGED, clearRaw = 120f, nearRaw = 900f),
                    )
                    val saved = EntityCatalogStore.ProximityModelRow(
                        "a".repeat(64), ProximityCalibrationRuntime.STORAGE_VERSION, "explicit-calibration-v2", snapshot, true, 1000L,
                    )
                    val manifest = JSONObject("{" + ProximityCalibrationBackup.manifestFragment(listOf(saved)).removePrefix(",") + "}")
                    val rows = if (payload == "absent") emptyList() else ProximityCalibrationBackup.restorable(manifest, samePanel = true)
                    assertEquals(if (payload == "absent") 0 else 1, rows.size)
                    val database = File(fixture.directory, "calibrations.db")
                    val custody = SqliteCalibrations(database)
                    val existing = saved.copy(behaviorSignature = "local-calibration", updatedAt = 2000L)
                    if (payload == "existing") assertEquals(1, custody.restore(listOf(existing)))
                    var concurrentImport = false
                    var reloads = 0
                    val executor = restoreExecutor(
                        fixture, capabilities = { Capabilities() }, persist = { true }, config = config,
                        calibrations = custody, profileAdmin = registry,
                        onDurableStateRestored = { reloads++ },
                        onReconfigure = {
                            if (stale && !concurrentImport) {
                                assertEquals("restored", config.panelId)
                                val raw = ProfileYaml.serialize(testProfileDocument(id = "community.concurrent"))
                                val preview = registry.preview(raw)
                                assertTrue(registry.importProfile(raw, requireNotNull(preview.previewToken)) is ProfileMutation.Success)
                                concurrentImport = true
                            }
                        },
                    )
                    val ticket = requireNotNull(InstallProgress.start("restore"))
                    try {
                        runBlocking { executor.execute(
                            RestoreConfigPlan(mapOf("panel_id" to "restored", "dashboard_zoom" to "120"), emptyList(), emptyList()),
                            null, ProfileBackupDecodeResult(profiles, emptyList()), plan, null, emptyList(), false,
                            emptyList(), null, ticket, restorableCalibrations = rows,
                        ) }
                        val body = JSONObject(InstallProgress.json())
                        val result = body.getJSONObject("result")
                        assertEquals(stale, concurrentImport)
                        assertEquals(if (stale) before else "restored", persistence.initialize()["panel_id"])
                        // Reopen SQLite: the retained row is independent of the executor and its custody owner.
                        assertEquals(if (payload == "absent") emptyList() else listOf(if (payload == "existing") existing else saved), SqliteCalibrations(database).export())
                        if (stale) {
                            assertTrue(registry.status().catalogRevision != plan.expectedCatalogRevision)
                            assertEquals("failed", result.getJSONObject("profiles").getString("status"))
                            assertEquals("rolled_back", result.getJSONObject("config").getString("status"))
                        }
                        assertEquals(if (payload == "missing") 1 else 0, reloads)
                        assertEquals("$stale/$payload: $body", if (!stale) "succeeded" else if (payload == "missing") "partial" else "failed", result.getString("status"))
                        if (payload == "missing") {
                            assertTrue(body.toString(), result.has("state"))
                            assertEquals("succeeded", result.getJSONObject("state").getString("status"))
                            assertEquals(1, result.getJSONObject("state").getInt("items"))
                        } else assertFalse(result.has("state"))
                        if (stale) {
                            assertEquals("rolled_back", result.getJSONObject("rollback").getString("status"))
                            assertEquals(if (payload == "missing") "restore-partial" else "restore-failed", body.getJSONObject("presentation").getString("code"))
                            assertEquals(if (payload == "missing") "Restore partially completed" else "Restore failed", body.getString("message"))
                        }
                    } finally {
                        InstallProgress.finish(ticket, "test cleanup")
                    }
                }
            }
        }
    }

    @Test fun aRestoredCalibrationIsSavedAndTakenUpBeforeTheConfigurationCommits() = runBlocking {
        // Android kills the app when an app-op changes; on 2026-11-03 that landed 7 s into a restore on the
        // bench X2i, after its configuration was durable, and the calibration the backup carried was lost.
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Restore contract panel")
            val snapshot = ProximityCalibrationRuntime.encode(
                ProximityCalibrationEngine.Calibration(mode = ProximityCalibrationEngine.Mode.RANGED, clearRaw = 120f, nearRaw = 900f),
            )
            val saved = EntityCatalogStore.ProximityModelRow(
                "a".repeat(64), ProximityCalibrationRuntime.STORAGE_VERSION, "explicit-calibration-v2", snapshot, true, 1000L,
            )
            val manifest = JSONObject("{" + ProximityCalibrationBackup.manifestFragment(listOf(saved)).removePrefix(",") + "}")
            val database = File(fixture.directory, "calibrations.db")
            var reloads = 0
            var atCommit: Pair<List<EntityCatalogStore.ProximityModelRow>, Int>? = null
            val executor = restoreExecutor(
                fixture, capabilities = { Capabilities() }, persist = { true },
                calibrations = SqliteCalibrations(database),
                onDurableStateRestored = { reloads++ },
                // Runs once the configuration is durable and before anything the restore does after it:
                // what a process killed at the commit has kept.
                onReconfigure = { if (atCommit == null) atCommit = SqliteCalibrations(database).export() to reloads },
            )
            val ticket = requireNotNull(InstallProgress.start("restore"))
            try {
                executor.execute(
                    RestoreConfigPlan(mapOf("panel_id" to "restored", "dashboard_zoom" to "120"), emptyList(), emptyList()),
                    null, null, null, null, emptyList(), false,
                    emptyList(), null, ticket,
                    restorableCalibrations = ProximityCalibrationBackup.restorable(manifest, samePanel = true),
                )
                val result = JSONObject(InstallProgress.json()).getJSONObject("result")
                assertEquals(result.toString(), "restored", fixture.config.panelId)
                assertEquals(listOf(saved) to 1, atCommit)
                assertEquals(1, reloads)
                assertEquals(result.toString(), "succeeded", result.getString("status"))
                assertEquals(1, result.getJSONObject("state").getInt("items"))
            } finally {
                InstallProgress.finish(ticket, "test cleanup")
            }
        }
    }

    // JVM SQLite custody uses the production table schema and missing-only contract. Android's
    // CatalogProximityCalibrationCustody adapter is reviewed statically, not executed here.
    private class SqliteCalibrations(private val database: File) : ProximityCalibrationCustody {
        init {
            connection().use { db -> db.createStatement().use { it.execute(EntityCatalogSchema.PROXIMITY_MODEL_TABLE_SQL.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ")) } }
        }
        private fun connection() = DriverManager.getConnection("jdbc:sqlite:${database.path}")
        override fun restore(rows: List<EntityCatalogStore.ProximityModelRow>): Int = connection().use { db ->
            rows.sumOf { row ->
                db.prepareStatement("INSERT OR IGNORE INTO proximity_model(fingerprint,algorithm_version,behavior_signature,snapshot_json,ready,updated_at) VALUES(?,?,?,?,?,?)").use {
                    it.setString(1, row.fingerprint)
                    it.setInt(2, row.algorithmVersion)
                    it.setString(3, row.behaviorSignature)
                    it.setString(4, row.snapshotJson)
                    it.setInt(5, if (row.ready) 1 else 0)
                    it.setLong(6, row.updatedAt)
                    it.executeUpdate()
                }
            }
        }
        override fun export(): List<EntityCatalogStore.ProximityModelRow> = connection().use { db ->
            db.createStatement().use { statement ->
                statement.executeQuery("SELECT fingerprint,algorithm_version,behavior_signature,snapshot_json,ready,updated_at FROM proximity_model WHERE ready=1").use { rows ->
                    buildList {
                        while (rows.next()) add(EntityCatalogStore.ProximityModelRow(rows.getString(1), rows.getInt(2), rows.getString(3), rows.getString(4), rows.getInt(5) != 0, rows.getLong(6)))
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

    @Test fun aRestoreCutShortByTheEndOfTheProcessIsReportedAtEveryStartUntilAnotherOperationRuns() = runBlocking {
        // Android ends the app whenever one of its app-ops changes; on the bench X2i (2026-11-05) that cut a
        // restore at its first saved configuration, and the next process reported nothing at all.
        for (configSaved in listOf(true, false)) PaneldServerHttpFixture().use { fixture ->
            val config = fixture.config
            config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Restore contract panel")
            val reached = java.util.concurrent.CountDownLatch(1)
            val processEnds = java.util.concurrent.CountDownLatch(1)
            fun cutHere() { reached.countDown(); processEnds.await() }
            val executor = restoreExecutor(
                fixture, capabilities = { Capabilities(networkAdb = true) }, persist = { true },
                onReconfigure = { if (configSaved) cutHere() },
                calibrations = object : ProximityCalibrationCustody {
                    override fun export() = emptyList<EntityCatalogStore.ProximityModelRow>()
                    override fun restore(rows: List<EntityCatalogStore.ProximityModelRow>): Int {
                        if (!configSaved) cutHere()
                        return 0
                    }
                },
            )
            val ticket = requireNotNull(InstallProgress.start("Restore"))
            val restore = Thread {
                runBlocking {
                    executor.execute(
                        RestoreConfigPlan(mapOf("panel_id" to "restored-panel"), emptyList(), emptyList()),
                        null, null, null, null, emptyList(), false,
                        emptyList(), null, ticket,
                        restorableCalibrations = listOf(
                            EntityCatalogStore.ProximityModelRow("a".repeat(64), 1, "explicit", "{}", true, 1L),
                        ),
                    )
                }
            }.apply { start() }
            try {
                assertTrue(reached.await(10, java.util.concurrent.TimeUnit.SECONDS))
                // The process ends here, and its lane and in-memory result end with it.
                InstallProgress.finish(ticket, "process ended")
                fun nextStart() = io.panelassistant.android.ServiceComponentOperations(
                    fixture.context, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
                    { config }, { error("No core") }, { error("No profile") },
                ).run { attachSoftwareUpdateObservers(); detachSoftwareUpdateObservers() }
                for (start in 1..2) {
                    nextStart()
                    val status = JSONObject(InstallProgress.json())
                    assertFalse(status.getBoolean("running"))
                    assertEquals("Restore", status.getString("component"))
                    val result = status.getJSONObject("result")
                    assertTrue(status.toString(), result.optBoolean("interrupted"))
                    assertEquals(if (configSaved) "partial" else "failed", result.getString("status"))
                    assertEquals(
                        if (configSaved) "restore-partial" else "restore-failed",
                        status.getJSONObject("presentation").getString("code"),
                    )
                }
                // The owner's next operation supersedes the report, and later starts no longer make it.
                val next = requireNotNull(InstallProgress.start("WebView"))
                InstallProgress.finish(next, "done")
                nextStart()
                val status = JSONObject(InstallProgress.json())
                assertEquals("WebView", status.getString("component"))
                assertFalse(status.toString(), status.has("result"))
            } finally {
                processEnds.countDown()
                restore.join(10_000)
            }
        }
    }

    @Test fun aRestoreThatFinishedIsNotReportedAsInterruptedAtTheNextStart() = runBlocking {
        PaneldServerHttpFixture().use { fixture ->
            fixture.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Restore contract panel")
            val executor = restoreExecutor(
                fixture, capabilities = { Capabilities(networkAdb = true) }, persist = { true }, onReconfigure = {},
            )
            val ticket = requireNotNull(InstallProgress.start("Restore"))
            executor.execute(
                RestoreConfigPlan(mapOf("ha_url" to "http://b.test:8123"), emptyList(), emptyList()),
                null, null, null, null, emptyList(), false,
                emptyList(), null, ticket,
            )
            assertEquals("succeeded", JSONObject(InstallProgress.json()).getJSONObject("result").getString("status"))
            // The process ends after the result was recorded; the next start must not report a cut.
            io.panelassistant.android.ServiceComponentOperations(
                fixture.context, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
                { fixture.config }, { error("No core") }, { error("No profile") },
            ).run { attachSoftwareUpdateObservers(); detachSoftwareUpdateObservers() }
            val result = JSONObject(InstallProgress.json()).getJSONObject("result")
            assertFalse(result.toString(), result.optBoolean("interrupted"))
            assertEquals("succeeded", result.getString("status"))
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
        calibrations: ProximityCalibrationCustody = io.panelassistant.android.persistence.CatalogProximityCalibrationCustody(fixture.context),
        onDurableStateRestored: () -> Unit = {},
        onWakeWordsChanged: () -> Unit = { error("No wake word payload") },
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
            calibrations = calibrations,
            onDurableStateRestored = onDurableStateRestored,
            onWakeWordsChanged = onWakeWordsChanged,
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
