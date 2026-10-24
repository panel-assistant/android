package io.panelassistant.android.http

import io.panelassistant.android.testsupport.scratchDirectory

import io.panelassistant.android.config.SettingsRegistry
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.panelassistant.android.Config
import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.sensors.SensorReporter
import io.panelassistant.android.setDashboardEntityLearningEnabled
import io.panelassistant.android.setHaConnection
import io.panelassistant.android.util.guardDbAppStaging
import io.ktor.server.application.Application
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import sun.misc.Unsafe

/**
 * Temporary characterization fixture while the server is split into constructible route owners.
 * Mounts the complete production registration with real guards and readers. Only the collaborators
 * needed for registration and the baseline requests are initialized; other handlers are not simulated.
 * Optional profile/provisioning owners remain absent, matching that supported production composition.
 * Keep allocation/reflection here and retire it as each owner gains its normal constructor.
 */
internal class PaneldServerHttpFixture(
    wakeWords: io.panelassistant.android.assist.wakeword.WakeWordCatalog? = null,
    repairCompanionUrl: () -> Boolean = { error("Unexpected Companion repair") },
    stopping: Boolean = false,
    installComponent: (String, String, String) -> Boolean = { _, _, _ -> error("Unexpected install") },
    density: io.panelassistant.android.control.DensityController? = null,
    camera: io.panelassistant.android.camera.CameraSurface = io.panelassistant.android.camera.AbsentCameraSurface,
    permissionStatus: () -> Map<io.panelassistant.android.platform.PanelPermissionRepair.Grant, io.panelassistant.android.platform.PanelPermissionRepair.State> = { emptyMap() },
    powerSafety: () -> io.panelassistant.android.control.PowerSafetyAssessment = { error("Unexpected power assessment") },
    repairPowerSafety: () -> io.panelassistant.android.control.PowerSafetyRepairResult = { error("Unexpected power repair") },
    logApp: io.panelassistant.android.logship.LogCapture? = null,
    logSystem: io.panelassistant.android.logship.LogCapture? = null,
    logWebView: io.panelassistant.android.logship.LogCapture? = null,
    webViewConsoleEnabled: () -> Boolean = { false },
    systemLogAvailable: () -> Boolean = { false },
) : java.io.Closeable {
    val directory = scratchDirectory("paneld-http-baseline").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = directory
        override fun getCacheDir(): File = directory
        override fun getNoBackupFilesDir(): File = directory
        override fun getPackageName(): String = "io.github.maxlyth.hapaneld"
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    }
    internal val values = mutableMapOf<String, Any?>("panel_id" to "contract-panel")
    private val preferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "contains" -> values.containsKey(args!![0])
            "getAll" -> values.toMap()
            "getString", "getInt", "getLong", "getFloat", "getBoolean", "getStringSet" -> values[args!![0]] ?: args[1]
            "edit" -> editor()
            else -> error("Baseline unexpectedly accessed preferences: ${method.name}")
        }
    } as SharedPreferences
    val config = Config(preferences).also { config ->
        Config::class.java.getDeclaredField("contentResolver").apply {
            isAccessible = true
            set(config, object : android.content.ContentResolver(null) {})
        }
    }
    val pending = PendingUploadStore().apply { open() }
    private var observations = ManagementObservations(
        context,
        density ?: io.panelassistant.android.control.DensityController(canSu = false),
        { error("Unexpected management probe") },
        { _, _ -> error("Unexpected diagnostic read") },
        scope,
        { false },
        nowMs = { 0L },
    )
    val sizingCache get() = observations.densityCache
    val snapCache get() = observations.snapCache
    val clearStorageGate = io.panelassistant.android.util.GenerationSingleFlight()
    val companionCache get() = observations.companionServerCache
    val manageTable = io.panelassistant.android.ManageTable()
    /** The stub's pages and socket enter these operations as a dispatcher would; channel commands are absent. */
    private val operations = io.panelassistant.android.ManageOperations(manageTable, { io.panelassistant.android.security.LocalApprovalBroker.instance })
    val door: io.panelassistant.android.ManageDoor = object : io.panelassistant.android.ManageDoor {
        override fun manage(
            commandId: String, op: String, args: org.json.JSONObject, origin: io.panelassistant.android.CommandOrigin,
            blobs: io.panelassistant.android.ManageBlobs?, onPending: () -> Unit, done: (io.panelassistant.android.ManageReply) -> Unit,
        ) = operations.manage(commandId, op, args, origin, blobs, onPending, done)
        override fun submit(
            command: io.panelassistant.android.panelassistant.PanelAssistantCommand,
            origin: io.panelassistant.android.CommandOrigin,
            done: (io.panelassistant.android.panelassistant.PanelAssistantCommandResult) -> Unit,
        ) = done(io.panelassistant.android.panelassistant.PanelAssistantCommandResult.Failed("failed"))
        override fun subscribe(family: String, origin: io.panelassistant.android.CommandOrigin, args: org.json.JSONObject, sink: io.panelassistant.android.ManageSink) =
            operations.subscribe(family, origin, args, sink)
        override fun closeOrigin(origin: io.panelassistant.android.CommandOrigin) = operations.closeOrigin(origin)
        override fun onStopped(action: () -> Unit): AutoCloseable = operations.onStopped(action)
    }
    val server = allocate(PaneldServer::class.java).apply {
        field("manageTable", this@PaneldServerHttpFixture.manageTable)
        field("door", { door })
        field("goneCounts", GoneCounts())
        field("stubBlobs", StubBlobs())
        field("config", config)
        field("scope", scope)
        field("system", allocate(io.panelassistant.android.control.SystemController::class.java))
        field("clearStorageGate", clearStorageGate)
        field("onRepairCompanionUrl", { _: () -> io.panelassistant.android.util.InstallOperationResult -> repairCompanionUrl() })
        companionCache.set(io.panelassistant.android.control.CompanionDb.ServerObservation.EMPTY)
        field("camera", camera)
        field("permissionStatus", permissionStatus)
        field("density", density ?: allocate(io.panelassistant.android.control.DensityController::class.java))
        // No capture or input route at all until a test supplies one with [useInteractive].
        field("interactive", io.panelassistant.android.control.InteractiveController(
            canSu = false,
            root = io.panelassistant.android.control.FakeRootShell(available = false, runResult = false),
            daemon = io.panelassistant.android.control.FakeDaemon(available = false),
            accessibility = object : io.panelassistant.android.platform.AccessibilityActions {
                override fun back() = false
                override fun recents() = false
                override fun tap(x: Int, y: Int) = false
            },
        ))
        field("profile", io.panelassistant.android.control.fakeProfile())
        field("catalogueLoader\$delegate", lazy {
            // Source-text reason: load runtime catalogues as the app does, so full-mount requests
            // exercise locale negotiation and translated responses without Android's AssetManager.
            io.panelassistant.android.i18n.CatalogueLoader { File("src/main/assets", it).readText() }
        })
        field("entityLearning", allocate(io.panelassistant.android.dashboard.EntityLearningManager::class.java))
        field("appContext", context)
        field("pageHealth", PageHealth(context, config))
        field("cacheDir", directory)
        field("dashboardIssueCustody", EmptyDashboardIssues)
        field("screenshots", ScreenshotCache(directory))
        // Source-text reason: provide actual bundled asset payloads through the platform reader seam.
        field("asset", { name: String -> File("src/main/assets", name).readText() })
        // The bundle's listing and bytes from the same bundled files, as the APK's AssetManager would give them.
        field("bundleAssets", object : UiBundleAssets {
            private val root = File("src/main/assets")
            override fun names() = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList()
            override fun bytes(name: String) = File(root, name).readBytes()
        })
        field("pendingApks", pending)
        field("guardDbStaging", guardDbAppStaging(context))
        wakeWords?.let { field("wakeWords", it) }
        field("onWakeWordsChanged", {})
        field("assistPipelines", NO_PIPELINES)
        field("voiceTest", NO_VOICE_TEST)
        field("playAudio", { _: String -> error("Unexpected playback") })
        field("onInstallComponent", installComponent)
        field("haOAuth", HaOAuthRuntime(
            config,
            { CatalogueLoader { name -> File("src/main/assets", name).readText() } },
            { _, _, _ -> error("Unexpected OAuth exchange") },
            AutoBrightnessHttpApi.UNAVAILABLE,
            { _, _, _ -> error("Unexpected OAuth commit") },
            { error("Unexpected setup evaluation") },
        ))
        field("sensors", allocate(SensorReporter::class.java))
        field("onProximityCalibration", { _: String, _: String -> false })
        field("autoBrightnessHttpApi", AutoBrightnessHttpApi.UNAVAILABLE)
        field("autoSleepHttpApi", AutoSleepHttpApi.UNAVAILABLE)
        field("radioStatus", { null })
        field("onZigbeeJoinRetry", { error("Unavailable radio must not join") })
        field("webViewConsoleEnabled", webViewConsoleEnabled)
        field("systemLogAvailable", systemLogAvailable)
        if (logApp != null) field("logApp", logApp)
        if (logSystem != null) field("logSystem", logSystem)
        if (logWebView != null) field("logWebView", logWebView)
        field("powerSafety", powerSafety)
        field("onRepairPowerSafety", repairPowerSafety)
        field("freshPowerSafetyRepairCapability", { io.panelassistant.android.control.PowerRepairCapability.APP_ONLY })
        val privilege = io.panelassistant.android.control.PrivilegedRouteObservation(
            directSuReady = true,
            helperRootReady = false,
        )
        observations.snapCache.set(ManagementSnapshot(
            emptyMap(), emptyMap(), io.panelassistant.android.config.Capabilities(),
            emptyList(), privilege, null, null, 1.0f, false,
        ))
        observations.densityCache.set(io.panelassistant.android.control.DisplaySizingObservation(240, 320, 1.0f))
        field("managementObservations", observations)
        field("storageHealth", { io.panelassistant.android.storage.StorageHealthSnapshot.UNCHECKED })
        val refreshStorage: suspend () -> io.panelassistant.android.storage.StorageHealthSnapshot? = { null }
        field("refreshStorageHealth", refreshStorage)
        field("stopping", stopping)
        field("inspectLock", Any())
    }

    fun mount(application: Application) = server.mount(application)

    fun useVoice(
        hasMicrophone: Boolean,
        enabled: Boolean = false,
        assistPipelines: io.panelassistant.android.assist.AssistPipelineDirectory = NO_PIPELINES,
        voiceTest: io.panelassistant.android.assist.VoiceTestTrigger = NO_VOICE_TEST,
    ) {
        server.field("assistPipelines", assistPipelines)
        server.field("voiceTest", voiceTest)
        values["voice_enabled"] = enabled
        val previous = requireNotNull(observations.snapCache.peek())
        observations.snapCache.set(ManagementSnapshot(
            previous.facts, previous.live, previous.caps.copy(
                microphone = if (hasMicrophone) io.panelassistant.android.audio.MicrophonePresence.PROVEN
                else io.panelassistant.android.audio.MicrophonePresence.ABSENT,
            ),
            previous.capabilityRows, previous.privilege, previous.densityCur, previous.densityBase,
            previous.fontScale, previous.wifiChronic,
        ))
    }

    fun backupBuilder(
        wakeWords: io.panelassistant.android.assist.wakeword.WakeWordCatalog?,
        config: Config = this.config,
        calibrations: io.panelassistant.android.persistence.ProximityCalibrationCustody =
            io.panelassistant.android.persistence.CatalogProximityCalibrationCustody(context),
        dashboardIssues: io.panelassistant.android.persistence.DashboardIssueCustody = EmptyDashboardIssues,
        exportState: (() -> List<io.panelassistant.android.persistence.ConfigVault.StateRow>)? = null,
        captureCompanion: () -> CapturedCompanion = { error("Companion explicitly excluded") },
        profileAdmin: io.panelassistant.android.device.profile.ProfileAdmin? = null,
        effectiveValue: (io.panelassistant.android.config.SettingSpec, Map<String, String>) -> String = { spec, _ -> spec.default },
    ) = PanelBackupBuilder(
        appContext = context,
        config = config,
        cacheDir = directory,
        configLiveValues = { emptyMap() },
        effectiveValue = effectiveValue,
        profileAdmin = profileAdmin,
        captureCompanion = captureCompanion,
        wakeWords = wakeWords,
        calibrations = calibrations,
        dashboardIssues = dashboardIssues,
        exportState = exportState ?: {
            io.panelassistant.android.dashboard.readThenClose(
                io.panelassistant.android.dashboard.EntityCatalogStore(context), { it.close() },
            ) { it.exportAppState() }
        },
    )

    fun useManagementStatus(
        companion: io.panelassistant.android.control.CompanionDb.ServerObservation =
            io.panelassistant.android.control.CompanionDb.ServerObservation.EMPTY,
        mdns: () -> Pair<String?, io.panelassistant.android.util.InstallPresentation?> = { null to null },
        onRefresh: () -> Unit,
    ) {
        values["friendly_name"] = "Contract panel"
        values["manufacturer"] = "Contract manufacturer"
        values["model"] = "Contract model"
        val privilege = io.panelassistant.android.control.PrivilegedRouteObservation(
            false, false,
        )
        val observations = ManagementObservations(
            context, io.panelassistant.android.control.DensityController(canSu = false),
            { error("Unexpected management probe") }, { _, _ -> error("Unexpected diagnostic probe") },
            scope, { false }, nowMs = { 0L },
        )
        observations.snapCache.set(ManagementSnapshot(
            emptyMap(), emptyMap(), io.panelassistant.android.config.Capabilities(), emptyList(),
            privilege, null, null, 1f, false,
        ))
        observations.companionServerCache.set(companion)
        server.field("managementObservations", observations)
        val profileType = io.panelassistant.android.device.DeviceProfile::class.java
        server.field("profile", Proxy.newProxyInstance(profileType.classLoader, arrayOf(profileType)) { _, method, _ ->
            when (method.name) {
                "getAppCanSu" -> false
                else -> error("Unexpected profile observation: ${method.name}")
            }
        })
        server.field("powerSafety", {
            io.panelassistant.android.control.PowerSafetyAssessment(
                io.panelassistant.android.control.PowerRiskLevel.SAFE,
                io.panelassistant.android.control.PowerSafetyObservation(
                    true, true, false, false, true, 60_000, true, 1, 1, false, true, "none",
                ),
                emptyList(), "safe", "none",
            )
        })
        server.field("storageHealth", { io.panelassistant.android.storage.StorageHealthSnapshot.UNCHECKED })
        val refresh: suspend () -> io.panelassistant.android.storage.StorageHealthSnapshot? = {
            onRefresh()
            io.panelassistant.android.storage.StorageHealthSnapshot.UNCHECKED
        }
        server.field("refreshStorageHealth", refresh)
        server.field("mdnsWarningProjection", mdns)
        server.field("camera", io.panelassistant.android.camera.AbsentCameraSurface)
    }

    fun useWarmDiagnostics(report: String, stopping: Boolean = false) {
        val observations = ManagementObservations(
            context,
            io.panelassistant.android.control.DensityController(canSu = false),
            { error("Unexpected management probe") },
            { _, _ -> error("No refresh after shutdown") },
            scope,
            { stopping },
        )
        observations.diagCache.set(report)
        observations.diagCache.invalidate()
        server.field("managementObservations", observations)
        server.field("stopping", stopping)
    }

    fun useInteractive(controller: io.panelassistant.android.control.InteractiveController) {
        server.field("interactive", controller)
    }

    fun useUnresolvedHome() {
        server.field("system", pageSystem())
    }

    private fun pageSystem() = io.panelassistant.android.control.SystemController(
        object : io.panelassistant.android.platform.SystemEnv {
            override val ownPackage = "io.github.maxlyth.hapaneld"
            override fun isInstalled(pkg: String) = pkg == "com.example.dashboard"
            override fun launchComponent(pkg: String): String? = null
            override fun homeActivities(): List<io.panelassistant.android.platform.ActivityRef> = emptyList()
            override fun defaultHome(): io.panelassistant.android.platform.ActivityRef? = null
            override fun directStart(component: String): Boolean = error("Unexpected activity launch")
        },
    )

    /** Real page rendering with deterministic identity, renderer discovery and bundled catalogues. */
    fun enablePages() {
        config.setRaw(requireNotNull(SettingsRegistry.spec("friendly_name")), "Contract <panel>")
        config.setRaw(requireNotNull(SettingsRegistry.spec("manufacturer")), "Contract manufacturer")
        config.setRaw(requireNotNull(SettingsRegistry.spec("model")), "Contract model")
        config.setDashboardPackage("com.example.dashboard")
        server.field("configLiveValues", { emptyMap<String, String>() })
        server.field("lastHaDiscovery", io.panelassistant.android.DiscoveryResult())
        server.field("catalogueLoader\$delegate", lazy {
            io.panelassistant.android.i18n.CatalogueLoader { name -> File("src/main/assets", name).readText() }
        })
        val system = pageSystem()
        server.field("system", system)
        val profile = Proxy.newProxyInstance(
            io.panelassistant.android.device.DeviceProfile::class.java.classLoader,
            arrayOf(io.panelassistant.android.device.DeviceProfile::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getRecommendedWebView" -> null
                "getAppCanSu" -> false
                "getProfileLinks" -> emptyList<Any>()
                "getHasRecents" -> false
                "getId" -> "generic"
                "getSocClass" -> "unknown"
                else -> error("Unexpected page profile read: ${method.name}")
            }
        } as io.panelassistant.android.device.DeviceProfile
        server.field("profile", profile)
        server.field("setupState", SetupState(
            config, system,
            allocate(io.panelassistant.android.dashboard.EntityLearningManager::class.java),
            profile, context, { 0 },
            { io.panelassistant.android.DiscoveryResult() }, { false },
            { system.resolveDashboard(config.dashboardPackage) == io.panelassistant.android.control.SystemController.BUILTIN_DASHBOARD },
            scope, io.panelassistant.android.util.RendererPreparationCoordinator(
                builtinPackage = "builtin", state = { error("Unexpected renderer preparation") },
                borrow = { null }, persist = { error("Unexpected renderer persistence") },
            ),
        ))
    }


    /** Cached management observations keep Configure on the real banner path without Android probes. */
    fun enableConfigurePage(proximity: Boolean = false, root: Boolean = false) {
        config.markPanelAssistantConnected()
        config.setDashboardPackage(io.panelassistant.android.control.SystemController.BUILTIN_DASHBOARD)
        val sensor = PaneldServer::class.java.getDeclaredField("sensors").run {
            isAccessible = true
            get(server)
        }
        SensorReporter::class.java.getDeclaredField("proximityAcquisition").apply {
            isAccessible = true
        }.set(sensor, if (proximity) io.panelassistant.android.sensors.ProximityAcquisition.ANDROID_HAL
            else io.panelassistant.android.sensors.ProximityAcquisition.ABSENT)
        val privilege = io.panelassistant.android.control.PrivilegedRouteObservation(
            directSuReady = root,
            helperRootReady = false,
        )
        observations.snapCache.set(ManagementSnapshot(
            mapOf("Device" to "Warm <panel>", "Device ID" to "secret-value"), emptyMap(),
            io.panelassistant.android.config.Capabilities(), emptyList(),
            privilege, 200, 160, 1.0f, false,
        ))
        server.field("powerSafety", {
            io.panelassistant.android.control.PowerSafetyAssessment(
                level = io.panelassistant.android.control.PowerRiskLevel.SAFE,
                observation = io.panelassistant.android.control.PowerSafetyObservation(
                    keepAwakeConfigured = true, wakeLockHeld = true,
                    wifiLockRequired = false, wifiLockHeld = false,
                    preventIdleDimConfigured = true, screenOffTimeoutMs = 30_000,
                    interactive = true, pluggedMask = 1, stayOnWhilePluggedIn = 1,
                    deviceIdleMode = false, ignoringBatteryOptimizations = true,
                    screenOffMechanism = "test",
                ),
                reasonCodes = emptyList(), summary = "safe", action = "none",
            )
        })
    }

    fun enableEntityPage() {
        config.setDashboardEntityLearningEnabled(true)
        config.setDashboardPackage(io.panelassistant.android.control.SystemController.BUILTIN_DASHBOARD)
    }


    fun enableInstallPage(root: Boolean = false) {
        enableConfigurePage(root = root)
        config.setHaConnection("http://ha.invalid:8123", "contract-token")
        val companion = io.panelassistant.android.control.CompanionDb.ServerObservation.EMPTY
        observations.companionServerCache.set(companion)
        val sizing = io.panelassistant.android.control.DisplaySizingObservation(200, 160, 1.0f)
        observations.densityCache.set(sizing)
        observations.companionHelperCache.set(false)
        server.field("tameProfileCandidates", emptyList<io.panelassistant.android.device.TameCandidate>())
        val tame = allocate(io.panelassistant.android.control.TameController::class.java)
        io.panelassistant.android.control.TameController::class.java.getDeclaredField("context").apply {
            isAccessible = true
        }.set(tame, context)
        server.field("tame", tame)
    }

    fun enableColdDashboard() {
        observations = ManagementObservations(
            context, io.panelassistant.android.control.DensityController(canSu = false),
            { error("Cold dashboard must not request management probes") },
            { _, _ -> error("Cold dashboard must not request diagnostic probes") },
            scope, { false },
        )
        server.field("managementObservations", observations)
        server.field("camera", io.panelassistant.android.camera.AbsentCameraSurface)
    }

    fun enableWarmDashboard() {
        enableInstallPage()
        server.field("camera", io.panelassistant.android.camera.AbsentCameraSurface)
        server.field("storageHealth", { io.panelassistant.android.storage.StorageHealthSnapshot.UNCHECKED })
        val bridge = TermuxBridgeProbe.State.ABSENT
        observations.termuxBridgeCache.set(bridge)
        server.field("effectiveBrightness", { 128 })
        val volume = allocate(io.panelassistant.android.control.VolumeController::class.java)
        io.panelassistant.android.control.VolumeController::class.java.getDeclaredField("am").apply {
            isAccessible = true
        }.set(volume, allocate(android.media.AudioManager::class.java))
        io.panelassistant.android.control.VolumeController::class.java.getDeclaredField("stream").apply {
            isAccessible = true
        }.set(volume, android.media.AudioManager.STREAM_MUSIC)
        server.field("volume", volume)
    }

    override fun close() {
        operations.stop()
        scope.cancel()
        pending.close()
        check(directory.deleteRecursively()) { "Could not remove baseline directory" }
    }

    private fun PaneldServer.field(name: String, value: Any) {
        PaneldServer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun <T> allocate(type: Class<T>): T = type.cast(unsafe.allocateInstance(type))

    private fun editor(): SharedPreferences.Editor {
        val pendingValues = mutableMapOf<String, Any?>()
        var clear = false
        return Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { pendingValues[args!![0] as String] = args[1]; proxy }
                method.name == "remove" -> { pendingValues[args!![0] as String] = null; proxy }
                method.name == "clear" -> { clear = true; proxy }
                method.name == "apply" || method.name == "commit" -> {
                    if (clear) values.clear()
                    pendingValues.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                    if (method.name == "commit") true else null
                }
                else -> error("Unexpected editor operation: ${method.name}")
            }
        } as SharedPreferences.Editor
    }

    private companion object {
        val EmptyDashboardIssues = object : io.panelassistant.android.persistence.DashboardIssueCustody {
            override fun export() = emptyList<io.panelassistant.android.persistence.DashboardIgnoredIssue>()
            override fun restore(rows: List<io.panelassistant.android.persistence.DashboardIgnoredIssue>) = 0
        }
        val NO_PIPELINES = object : io.panelassistant.android.assist.AssistPipelineDirectory {
            override suspend fun list() =
                io.panelassistant.android.assist.AssistPipelineDirectory.Result.NotConfigured("no Home Assistant link in this fixture")
        }
        val NO_VOICE_TEST = io.panelassistant.android.assist.VoiceTestTrigger {
            io.panelassistant.android.assist.VoiceTestTrigger.Result.Unavailable("no voice runtime in this fixture")
        }
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
    }
}
