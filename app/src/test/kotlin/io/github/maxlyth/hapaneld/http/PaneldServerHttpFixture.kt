package io.github.maxlyth.hapaneld.http

import android.content.ContextWrapper
import android.content.SharedPreferences
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.migration.IdentityMigrationSurface
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantTransportFacts
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantTransportPhase
import io.github.maxlyth.hapaneld.sensors.HaCurrentUserClient
import io.github.maxlyth.hapaneld.sensors.SensorReporter
import io.github.maxlyth.hapaneld.util.guardDbAppStaging
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
    camera: io.github.maxlyth.hapaneld.camera.CameraSurface = io.github.maxlyth.hapaneld.camera.AbsentCameraSurface,
    powerSafety: () -> io.github.maxlyth.hapaneld.control.PowerSafetyAssessment = { error("Unexpected power assessment") },
    repairPowerSafety: () -> io.github.maxlyth.hapaneld.control.PowerSafetyRepairResult = { error("Unexpected power repair") },
    logApp: io.github.maxlyth.hapaneld.logship.LogCapture? = null,
) : java.io.Closeable {
    private val directory = Files.createTempDirectory("paneld-http-baseline").toFile()
    private val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = directory
        override fun getCacheDir(): File = directory
        override fun getNoBackupFilesDir(): File = directory
        override fun getPackageName(): String = "io.github.maxlyth.hapaneld"
    }
    private val values = mutableMapOf<String, Any?>("panel_id" to "contract-panel")
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
    private val pending = PendingUploadStore().apply { open() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val server = allocate(PaneldServer::class.java).apply {
        field("config", config)
        field("scope", scope)
        field("camera", camera)
        field("entityLearning", allocate(io.github.maxlyth.hapaneld.dashboard.EntityLearningManager::class.java))
        field("appContext", context)
        field("cacheDir", directory)
        field("screenshots", ScreenshotCache(directory))
        // Source-text reason: provide actual bundled asset payloads through the platform reader seam.
        field("asset", { name: String -> File("src/main/assets", name).readText() })
        field("pendingApks", pending)
        field("guardDbStaging", guardDbAppStaging(context))
        field("identityMigration", IdentityMigrationSurface.NONE)
        field("playAudio", { _: String -> error("Unexpected playback") })
        field("onInstallComponent", { _: String, _: String, _: String -> error("Unexpected install") })
        field("panelAssistantTransportFacts", {
            PanelAssistantTransportFacts("", "", PanelAssistantTransportPhase.STOPPED, null)
        })
        field("releasePanelAssistantTransport", { error("Unexpected transport release") })
        field("haOAuthFlow", HaOAuthFlow())
        field("haCurrentUser", allocate(HaCurrentUserClient::class.java))
        field("sensors", allocate(SensorReporter::class.java))
        field("onProximityCalibration", { _: String, _: String -> false })
        field("autoBrightnessHttpApi", AutoBrightnessHttpApi.UNAVAILABLE)
        field("autoSleepHttpApi", AutoSleepHttpApi.UNAVAILABLE)
        field("radioStatus", { null })
        field("onZigbeeJoinRetry", { error("Unavailable radio must not join") })
        field("webViewConsoleEnabled", { false })
        field("logShipStatus", { io.github.maxlyth.hapaneld.logship.LogShipStatusProjection(false, false, "disabled") })
        if (logApp != null) field("logApp", logApp)
        field("powerSafety", powerSafety)
        field("onRepairPowerSafety", repairPowerSafety)
        field("freshPowerSafetyRepairCapability", { io.github.maxlyth.hapaneld.control.PowerRepairCapability.APP_ONLY })
        val privilege = io.github.maxlyth.hapaneld.control.PrivilegedRouteObservation(
            directSuReady = true,
            helperRootReady = false,
            shizuku = io.github.maxlyth.hapaneld.shizuku.ShizukuBridge.Snapshot(
                io.github.maxlyth.hapaneld.shizuku.ShizukuState.DISABLED, ready = false,
            ),
        )
        val snap = PaneldServer::class.java.declaredClasses.single { it.simpleName == "Snap" }
            .declaredConstructors.single().run {
                isAccessible = true
                newInstance(emptyMap<String, String>(), emptyMap<String, String>(),
                    io.github.maxlyth.hapaneld.config.Capabilities(), emptyList<Any>(),
                    privilege, null, null, 1.0f, false)
            }
        field("snapCache", io.github.maxlyth.hapaneld.util.Cached<Any>(Long.MAX_VALUE) { snap }.also { it.set(snap) })
        field("diagCache", io.github.maxlyth.hapaneld.util.Cached<Any>(Long.MAX_VALUE) { error("Unexpected diagnostic read") })
        field("densityCache", io.github.maxlyth.hapaneld.util.Cached<Any>(Long.MAX_VALUE) { error("Unexpected density read") })
        field("stopping", false)
    }

    fun mount(application: Application) = server.mount(application)

    fun useInteractive(controller: io.github.maxlyth.hapaneld.control.InteractiveController) {
        server.field("interactive", controller)
    }

    /** Real page rendering with deterministic identity, renderer discovery and bundled catalogues. */
    fun enablePages() {
        config.setFriendlyName("Contract <panel>")
        config.setHardware("Contract manufacturer", "Contract model")
        config.setDashboardPackage("com.example.dashboard")
        server.field("configLiveValues", { emptyMap<String, String>() })
        server.field("mqttState", { "connecting" })
        server.field("lastHaDiscovery", io.github.maxlyth.hapaneld.DiscoveryResult())
        server.field("catalogueLoader\$delegate", lazy {
            io.github.maxlyth.hapaneld.i18n.CatalogueLoader { name -> File("src/main/assets", name).readText() }
        })
        server.field("system", io.github.maxlyth.hapaneld.control.SystemController(
            object : io.github.maxlyth.hapaneld.platform.SystemEnv {
                override val ownPackage = "io.github.maxlyth.hapaneld"
                override fun isInstalled(pkg: String) = pkg == "com.example.dashboard"
                override fun launchComponent(pkg: String): String? = null
                override fun homeActivities(): List<io.github.maxlyth.hapaneld.platform.ActivityRef> = emptyList()
                override fun defaultHome(): io.github.maxlyth.hapaneld.platform.ActivityRef? = null
                override fun directStart(component: String) = Unit
            },
        ))
        server.field("profile", Proxy.newProxyInstance(
            io.github.maxlyth.hapaneld.device.DeviceProfile::class.java.classLoader,
            arrayOf(io.github.maxlyth.hapaneld.device.DeviceProfile::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getRecommendedWebView" -> null
                "getAppCanSu" -> false
                else -> error("Unexpected page profile read: ${method.name}")
            }
        })
    }


    /** Cached management observations keep Configure on the real banner path without Android probes. */
    fun enableConfigurePage(proximity: Boolean = false) {
        config.setMqtt("mqtt://contract.invalid:1883", "", "")
        val sensor = PaneldServer::class.java.getDeclaredField("sensors").run {
            isAccessible = true
            get(server)
        }
        SensorReporter::class.java.getDeclaredField("proximityAcquisition").apply {
            isAccessible = true
        }.set(sensor, if (proximity) io.github.maxlyth.hapaneld.sensors.ProximityAcquisition.ANDROID_HAL
            else io.github.maxlyth.hapaneld.sensors.ProximityAcquisition.ABSENT)
        val privilege = io.github.maxlyth.hapaneld.control.PrivilegedRouteObservation(
            directSuReady = false,
            helperRootReady = false,
            shizuku = io.github.maxlyth.hapaneld.shizuku.ShizukuBridge.Snapshot(
                io.github.maxlyth.hapaneld.shizuku.ShizukuState.DISABLED, ready = false,
            ),
        )
        val snap = PaneldServer::class.java.declaredClasses.single { it.simpleName == "Snap" }
            .declaredConstructors.single().run {
                isAccessible = true
                newInstance(
                    mapOf("MQTT" to "connecting"), emptyMap<String, String>(),
                    io.github.maxlyth.hapaneld.config.Capabilities(), emptyList<Any>(),
                    privilege, null, null, 1.0f, false,
                )
            }
        server.field("snapCache", io.github.maxlyth.hapaneld.util.Cached<Any>(Long.MAX_VALUE) { snap }.also { it.set(snap) })
        server.field("powerSafety", {
            io.github.maxlyth.hapaneld.control.PowerSafetyAssessment(
                level = io.github.maxlyth.hapaneld.control.PowerRiskLevel.SAFE,
                observation = io.github.maxlyth.hapaneld.control.PowerSafetyObservation(
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
        config.setDashboardPackage(io.github.maxlyth.hapaneld.control.SystemController.BUILTIN_DASHBOARD)
        server.field("webViewTooOldOnce\$delegate", lazy { false })
    }

    override fun close() {
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
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
            isAccessible = true
            get(null) as Unsafe
        }
    }
}
