package io.panelassistant.android.http

import io.panelassistant.android.haUrl
import io.panelassistant.android.LiveSettingRequestOutcome
import io.panelassistant.android.backup.PanelBackup
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.device.profile.*
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.SystemEnv
import io.panelassistant.android.util.BorrowedRendererSettings
import io.panelassistant.android.util.InstallProgress
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.RendererPreparationState
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Format conversion through the same restore transaction and exporter used by the Install page. */
class LegacyBackupRoundTripTest {
    @Test fun legacySettingsAndProfileSurviveRestoreExportAndFreshRestore() = roundTrip(false)
    @Test fun legacyCompanionBytesSurviveRestoreExportAndFreshRestore() = roundTrip(true)

    private val companionPackage = "io.homeassistant.companion.android.minimal"
    private val companionPath = "databases/HomeAssistantDB"
    private val companionBytes = "representative companion bytes".toByteArray()
    private val facts = DeviceFacts("test-panel", "test-device", "fw-1")
    private val importedYaml = ProfileYaml.serialize(testProfileDocument(id = "community.backup.panel", facts = facts))
    private val importedRef = ProfileRef("community.backup.panel", ProfileYaml.sha256(importedYaml))
    private val profileBackup = ProfileBackup(
        revisions = listOf(ProfileBackupRevision(importedRef, importedYaml)),
        selection = ProfileSelection.Pinned(importedRef), active = importedRef, lastKnownGood = null,
    )

    private fun roundTrip(includeCompanion: Boolean) {
        // Legacy means the plain JSON container, not the settings schema. This uses the schema-15
        // shape already exercised by the legacy route fixtures, with non-default durable values.
        val legacy = JSONObject().put("kind", "ha-paneld-backup").put("schema", 15)
            .put("panel_id", "source")
            .put("config", JSONObject().put("friendly_name", "Recovered kitchen").put("ha_url", "http://ha.test"))
            .put("profiles", profileBackup.toJson())
            .put("entity_state", JSONObject()
                .put("instance_key", "ha-instance").put("instance_origin", "http://ha.test")
                .put("instance_uuid", "instance-uuid").put("dashboard_path", "/lovelace/home")
                .put("filter_ids", "light.kitchen").put("filter_enabled", true)
                .put("filter_owner", "ha-instance|/lovelace/home").put("learning_applied", true)
                .put("applied_owner", "ha-instance|/lovelace/home")
                .put("overrides", "+light.kitchen\n-switch.porch")
                .put("override_owner", "ha-instance|/lovelace/home"))
        if (includeCompanion) legacy.put("companion", JSONObject().put("pkg", companionPackage)
            .put("files", JSONArray().put(JSONObject().put("rel", companionPath)
                .put("b64", Base64.getEncoder().encodeToString(companionBytes)))))
        PaneldServerHttpFixture().use { first ->
            val preferences = TransientProfilePreferences()
            val firstProfiles = registry(first, preferences)
            firstProfiles.resolveForStartup()
            assertTrue(firstProfiles.markResolvedStartupHealthy())
            val restoredBytes = mutableMapOf<String, ByteArray>()
            restore(first, firstProfiles, preferences, legacy.toString().toByteArray(), restoredBytes)
            assertRecovered(first, registry(first, preferences), restoredBytes, includeCompanion)
            val builder = first.backupBuilder(
                wakeWords = null, profileAdmin = registry(first, preferences), exportState = { emptyList() },
                captureCompanion = {
                    assertTrue(restoredBytes.containsKey(companionPath))
                    val file = File.createTempFile("captured-", ".payload", first.directory)
                    file.writeBytes(restoredBytes.getValue(companionPath))
                    CapturedCompanion(companionPackage, listOf(CapturedCompanionFile(companionPath, file)),
                        java.io.Closeable { file.delete() })
                },
                effectiveValue = { spec, live -> effectiveSettingValue(first.config, spec, live) },
            )
            val request = if (includeCompanion) CompanionBackupRequest.REQUIRED else CompanionBackupRequest.EXCLUDED
            builder.build(request, "").use { current ->
                assertEquals("zip", current.extension)
                val text = PanelBackup.readManifest(current.file, 1024 * 1024)
                assertNotNull("The normal export must be a readable current archive", text)
                val manifest = JSONObject(text!!)
                assertEquals(SettingsRegistry.SCHEMA, manifest.optInt("schema"))
                assertEquals("Recovered kitchen", manifest.getJSONObject("config").optString("friendly_name"))
                assertTrue("The current export must retain imported profiles", manifest.has("profiles"))
                assertTrue(manifest.getJSONObject("profiles").has("entry"))
                if (includeCompanion) {
                    val file = manifest.getJSONObject("companion").getJSONArray("files").getJSONObject(0)
                    assertTrue(file.has("entry"))
                    assertFalse(file.has("b64"))
                    assertEquals(companionBytes.size.toLong(), file.optLong("size"))
                }
                PaneldServerHttpFixture().use { second ->
                    val freshPreferences = TransientProfilePreferences()
                    second.config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Fresh target")
                    assertNotEquals("Recovered kitchen", second.config.friendlyName)
                    val secondProfiles = registry(second, freshPreferences)
                    assertTrue(secondProfiles.exportBackup().revisions.isEmpty())
                    secondProfiles.resolveForStartup()
                    assertTrue(secondProfiles.markResolvedStartupHealthy())
                    val appliedBytes = mutableMapOf<String, ByteArray>()
                    restore(second, secondProfiles, freshPreferences, current.file.readBytes(), appliedBytes)
                    assertRecovered(second, registry(second, freshPreferences), appliedBytes, includeCompanion)
                }
            }
        }
    }

    private fun assertRecovered(
        fixture: PaneldServerHttpFixture, profiles: RuntimeProfileRegistry,
        companion: Map<String, ByteArray>, included: Boolean,
    ) {
        assertEquals("Recovered kitchen", fixture.config.friendlyName)
        assertEquals("http://ha.test", fixture.config.haUrl)
        val entity = fixture.config.dashboardEntityBackupState()
        assertEquals("light.kitchen", entity.filterIds)
        assertEquals("+light.kitchen\n-switch.porch", entity.overrides)
        assertEquals("ha-instance|/lovelace/home", entity.overrideOwner)
        assertEquals("instance-uuid", entity.instanceUuid)
        assertEquals(ProfileSelection.Pinned(importedRef), profiles.status().selection)
        assertEquals(importedRef, profiles.status().active?.ref)
        assertTrue(profiles.exportBackup().revisions.any { it.ref == importedRef && it.rawYaml == importedYaml })
        if (included) assertArrayEquals(companionBytes, companion[companionPath]) else assertTrue(companion.isEmpty())
    }

    private fun registry(fixture: PaneldServerHttpFixture, preferences: ProfilePreferences) = RuntimeProfileRegistry(
        filesDir = File(fixture.directory, "profiles"), preferences = preferences,
        bundledLoader = { mapOf("generic.yaml" to ProfileYaml.serialize(testProfileDocument(id = "generic", fallback = true))) },
        facts = facts, coreVersion = "1.0.0", clock = { 1000L },
    )

    private fun restore(
        fixture: PaneldServerHttpFixture, profiles: RuntimeProfileRegistry, preferences: ProfilePreferences,
        bytes: ByteArray, companion: MutableMap<String, ByteArray>,
    ) {
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
            kotlinx.coroutines.CoroutineExceptionHandler { _, error -> failure.set(error) })
        val config = fixture.config
        config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Before")
        val values = ConfigValueProjection(config, { emptyMap() }, { emptyMap() }, { emptyMap() },
            { emptySet() }, { "{}" }, { "{}" }, { "{}" })
        val renderer = RendererPreparationCoordinator(
            builtinPackage = "builtin", state = { RendererPreparationState("builtin", "") },
            borrow = { BorrowedRendererSettings("http://ha.test", "", "", 0, "", null) }, persist = { true },
        )
        val system = SystemController(object : SystemEnv {
            override val ownPackage = "io.github.maxlyth.hapaneld"
            override fun isInstalled(pkg: String) = false
            override fun launchComponent(pkg: String): String? = null
            override fun homeActivities(): List<ActivityRef> = emptyList()
            override fun defaultHome(): ActivityRef? = null
            override fun directStart(component: String) = false
        })
        val capabilities = { Capabilities(networkAdb = true, hasProximity = true, canControlBootChime = true,
            microphone = io.panelassistant.android.audio.MicrophonePresence.PROVEN) }
        val executor = AcceptedConfigTransaction.restoreExecutor(
            appContext = fixture.context, config = config, capabilities = capabilities,
            transaction = { AcceptedConfigTransaction(
                config = config, revisions = RevisionStore(fixture.directory), rendererPreparation = renderer,
                system = system, values = values, applySetting = { _, _ -> LiveSettingRequestOutcome.APPLIED },
                onEntityTargetChanged = {}, setEntityLearningEnabled = { true }, effectiveDashboardIsBuiltin = { false },
                requestTameReconcileAfterCommit = { true }, snapInvalidate = {}, onReconfigure = {},
            ) },
            restoreCompanion = { plan ->
                assertEquals(companionPackage, plan.packageName)
                plan.files.forEach { companion[it.relativePath] = it.file.readBytes() }
                CompanionApplyResult(true, InstallProgress.ComponentResult(InstallProgress.Outcome.SUCCEEDED, plan.files.size))
            },
            profileAdmin = profiles,
            onProfileRestart = {
                val restarted = registry(fixture, preferences)
                val resolved = restarted.resolveForStartup()
                assertEquals(importedRef, resolved.summary.ref)
                assertNotNull(resolved.activationGeneration)
                assertTrue(restarted.markActivationHealthy(resolved.activationGeneration!!))
                true
            },
            onProfileRestartAbort = { error("Successful profile restore must not abort") },
            onDurableStateRestored = {}, onWakeWordsChanged = {},
        )
        val route = RestoreRoutes(
            cacheDir = fixture.directory, config = config, reader = BackupArchiveReader(fixture.directory) { setOf(companionPackage) },
            profileAdmin = profiles, ensureCompanionHelper = { true }, companionHelperReady = { true },
            authorizeSensitive = { _, _, _, _ -> true }, rejectHardenedNetworkAdb = { _, _ -> false },
            capabilities = capabilities, scope = scope, executor = executor, wakeWords = null,
        )
        try {
            testApplication {
                application { routing { post("/api/v1/restore") { route.handle(call.op()) } } }
                val response = client.post("/api/v1/restore") { setBody(bytes) }
                val body = response.bodyAsText()
                assertEquals(body, HttpStatusCode.OK, response.status)
                assertEquals(body, "started", JSONObject(body).optString("status"))
                withTimeout(5_000) { while (InstallProgress.running) delay(10) }
                assertNull(failure.get()?.stackTraceToString(), failure.get())
                val status = JSONObject(InstallProgress.json())
                assertEquals(status.toString(), "succeeded", status.optJSONObject("result")?.optString("status"))
            }
        } finally { scope.cancel() }
    }
}
