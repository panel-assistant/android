package io.panelassistant.android

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.panelassistant.android.camera.CameraCapabilityReason
import io.panelassistant.android.camera.cameraCapabilityReason
import io.panelassistant.android.control.AdbController
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.device.probe.AndroidPassiveProfileProbe
import io.panelassistant.android.device.profile.ProfileDraftFactory
import io.panelassistant.android.device.profile.RuntimeProfileRegistry
import io.panelassistant.android.device.profile.ResolvedProfile
import io.panelassistant.android.persistence.AppState
import io.panelassistant.android.persistence.CleanDatabaseProof
import io.panelassistant.android.persistence.StateQuiescence
import io.panelassistant.android.upgrade.UpgradeShutdownCoordinator
import io.panelassistant.android.upgrade.UpgradeShutdownClaim
import io.panelassistant.android.input.EvdevButtonClient
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.CompanionDb
import io.panelassistant.android.control.CompanionDataOperationGate
import io.panelassistant.android.control.CompanionDataOperationState
import io.panelassistant.android.control.CdpRelay
import io.panelassistant.android.control.RemoteDebugSecurityTransitionGate
import io.panelassistant.android.control.InteractiveController
import io.panelassistant.android.control.NavbarController
import io.panelassistant.android.control.PrivilegedRouteObservation
import io.panelassistant.android.control.WakeOutcome
import io.panelassistant.android.control.AndroidWifiDiagnostics
import io.panelassistant.android.control.AndroidWifiOutageStore
import io.panelassistant.android.control.WifiDiagnosticDemand
import io.panelassistant.android.control.WifiDiagnosticAdmissionTracker
import io.panelassistant.android.control.WifiOutageTracker
import io.panelassistant.android.control.WifiOutageCounts
import io.panelassistant.android.control.wifiOutageChronic
import io.panelassistant.android.control.wifiOutageStatusText
import io.panelassistant.android.control.availability
import io.panelassistant.android.control.networkAdbDiagnostic
import io.panelassistant.android.control.Su
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.control.ZigbeeObservation
import io.panelassistant.android.control.observeTypedShellCapability
import io.panelassistant.android.control.preventIdleDimDiagnostic
import io.panelassistant.android.device.LedMechanism
import io.panelassistant.android.hardware.Rk3576LedController
import io.panelassistant.android.hardware.SocketLedController
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.dashboard.EntityLearningManager
import io.panelassistant.android.dashboard.EntityLearningRuntime
import io.panelassistant.android.http.PaneldServer
import io.panelassistant.android.http.PanelInfo
import io.panelassistant.android.http.ManagementProjection
import io.panelassistant.android.http.ConfigDiscoverySuggestions
import io.panelassistant.android.http.DiagReader
import io.panelassistant.android.http.retainCompanionLeaseUntilHelperIdle
import io.panelassistant.android.logship.LogCapture
import io.panelassistant.android.logship.LogShipper
import io.panelassistant.android.logship.webViewConsoleEnabled
import io.panelassistant.android.security.ApprovalBroker
import io.panelassistant.android.security.LocalApprovalBroker
import io.panelassistant.android.sensors.SensorReporter
import io.panelassistant.android.sensors.SensorLightPublisher
import io.panelassistant.android.sensors.submitIlluminanceIfExposed
import io.panelassistant.android.panelassistant.PanelAssistantApprovalState
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.panelassistant.PanelAssistantCommandSink
import io.panelassistant.android.panelassistant.PanelAssistantStateReporter
import io.panelassistant.android.panelassistant.PanelAssistantTransportDemand
import io.panelassistant.android.storage.StorageHealthRuntime
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.util.localIpv4
import io.panelassistant.android.util.localIpv6
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import io.panelassistant.android.util.LatestOperationPolicy
import io.panelassistant.android.util.LatestOperationTimeoutPolicy
import io.panelassistant.android.util.ServiceRuntimeOwner
import io.panelassistant.android.util.SuccessStickyProbe
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.util.BorrowedRendererSettings
import io.panelassistant.android.util.BundledHelperInstaller
import io.panelassistant.android.util.RendererPreparationCoordinator
import io.panelassistant.android.util.RendererPreparationState
import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.LatestDispatcher
import io.panelassistant.android.util.submit
import io.panelassistant.android.util.OwnedThread
import io.panelassistant.android.util.awaitSuccessful
import io.panelassistant.android.util.awaitTrue
import io.panelassistant.android.util.ProfileRestartCoordinator
import io.panelassistant.android.util.ServiceRestartBarrier
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import io.panelassistant.android.util.UpdateChecker
import io.panelassistant.android.util.CompanionInstaller
import io.panelassistant.android.util.CompanionOperationStatus
import io.panelassistant.android.util.AppInstaller
import io.panelassistant.android.util.InstallOutcome
import io.panelassistant.android.util.InstallProgress
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.util.HelperLogcatOpenResult
import io.panelassistant.android.util.GuardDbProcessAdmission
import io.panelassistant.android.util.GuardDbSentinelLoad
import io.panelassistant.android.util.HelperInstallReconciler
import io.panelassistant.android.util.HelperInstallTransaction
import io.panelassistant.android.state.SoftwareComponent
import io.panelassistant.android.platform.AndroidDeviceName
import io.panelassistant.android.platform.PanelPermissionRepair
import io.panelassistant.android.util.periodic
import io.panelassistant.android.util.SystemProps
import io.panelassistant.android.dashboard.shouldReloadBuiltinAfterEntityFilterChange
import java.io.File
import java.net.SocketTimeoutException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Hard outer owner for synchronous SQLite/file proof. Its daemon worker cannot retain process exit. */
internal fun <T> runBoundedShutdownProof(timeoutMs: Long, proof: () -> T): T? {
    if (timeoutMs <= 0L) return null
    val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ha-paneld-shutdown-db-proof").apply { isDaemon = true }
    }
    val future = executor.submit(Callable(proof))
    return try {
        future.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        null
    } catch (_: ExecutionException) {
        null
    } catch (_: java.util.concurrent.CancellationException) {
        null
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } finally {
        future.cancel(true)
        executor.shutdownNow()
    }
}

internal fun defaultNetworkIpv4(addresses: Iterable<InetAddress>): String? =
    addresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress

/** Confirm declared Zigbee hardware when readable; preserve declared capability across probe failure. */
internal fun zigbeeCapabilityPresent(
    declaredGateway: Boolean,
    observation: ZigbeeObservation,
): Boolean = declaredGateway && (!observation.probeSucceeded || observation.present)

/**
 * Whether this panel offers a camera at all. [declared] is the profile's tri-state `hardware.camera`
 * and [observed] is what enumerating Android's cameras found, or null while that probe has not answered.
 *
 * The direction is deliberately the opposite of [zigbeeCapabilityPresent]. A zigbee gateway lives at a
 * profile-supplied path that nothing can discover, so there an observation may only veto a declaration.
 * A camera is enumerated by `CameraManager`, which is Android's authoritative and universal answer, so
 * here an observation may grant one: a board whose profile says nothing still offers the camera Android
 * can see, instead of every owner of camera hardware having to author a profile first.
 *
 * A declaration still wins in both directions. The two boards that declare a camera are unaffected by a
 * probe that throws, and a board whose enumerated device is not a usable room camera is suppressed with
 * an explicit `camera: false`.
 *
 * The boolean is derived from [cameraCapabilityReason] rather than restated here, because the panel now
 * has to explain the answer as well as act on it, and a capability that disagreed with its own stated
 * reason would be the worst of both.
 */
internal fun cameraCapabilityPresent(declared: Boolean?, observed: Boolean?): Boolean =
    cameraCapabilityReason(declared, observed).capable

internal fun commitBorrowedRendererTarget(
    commit: () -> Boolean,
    onCommitted: () -> Unit,
): Boolean {
    val committed = commit()
    if (committed) onCommitted()
    return committed
}

internal fun prepareEntityLearningStartup(
    startMdns: () -> Unit,
    reconcileRenderer: () -> RendererPreparationCoordinator.Result,
    startLearning: () -> Unit,
): RendererPreparationCoordinator.Result {
    startMdns()
    return reconcileRenderer().also { result ->
        if (result != RendererPreparationCoordinator.Result.CLOSED) startLearning()
    }
}

/**
 * Reconstruct the process-local Companion launch gate only when the app-private marker proves that
 * this app may have submitted a helper transaction before process death. Ordinary helper absence
 * without that marker is not a launch block. A retained lease is released only after an affirmative
 * IDLE status also clears the marker durably.
 */
internal fun restoreCompanionLaunchSuppression(
    packageName: String,
    operationState: CompanionDataOperationState,
    operationStatus: () -> CompanionOperationStatus,
    retain: (CompanionDataOperationGate.Lease) -> Unit,
): CompanionOperationStatus {
    if (!operationState.isPending()) {
        return CompanionOperationStatus.IDLE
    }
    val status = runCatching(operationStatus).getOrDefault(CompanionOperationStatus.UNAVAILABLE)
    val resolvedPackage = packageName.takeIf(CompanionDataOperationGate::isCompanionPackage)
        ?: CompanionInstaller.MINIMAL_PKG
    when (status) {
        CompanionOperationStatus.IDLE -> {
            if (!operationState.clear()) {
                CompanionDataOperationGate.acquire(resolvedPackage)?.let(retain)
            }
        }
        CompanionOperationStatus.BUSY,
        CompanionOperationStatus.UNSUPPORTED,
        CompanionOperationStatus.UNAVAILABLE ->
            CompanionDataOperationGate.acquire(resolvedPackage)?.let(retain)
    }
    return status
}

/** Values whose change replaces the network runtime's mDNS advertiser; a running state core and dispatcher are kept. */
internal data class NetworkRuntimeIdentity(
    internal val panelId: String,
    internal val friendlyName: String,
    internal val httpPort: Int,
)

internal data class ConfigOwnerRefreshPlan(
    val adaptiveBrightness: Boolean,
    val autoSleep: Boolean,
    val logShipping: Boolean,
    val keepAwake: Boolean,
    val launcherHome: Boolean,
    val rendererTarget: Boolean,
    val camera: Boolean,
    val panelAssistantTransport: Boolean,
    val androidDeviceName: Boolean,
)

internal fun configOwnerRefreshPlan(changedKeys: Set<String>): ConfigOwnerRefreshPlan {
    val ha = setOf("ha_url", "ha_token", "ha_refresh_token", "ha_token_expiry", "ha_client_id", HaConnectionRoutes.EPOCH_KEY)
    return ConfigOwnerRefreshPlan(
        adaptiveBrightness = changedKeys.any(ha::contains),
        autoSleep = changedKeys.any((ha + setOf("panel_id", "auto_sleep_source", "auto_sleep_touch_delay_seconds"))::contains),
        logShipping = changedKeys.any(setOf(
            "log_ship_enabled", "log_ship_system_enabled", "log_ship_host", "log_ship_port", "log_ship_protocol",
        )::contains),
        keepAwake = "keep_awake" in changedKeys,
        launcherHome = changedKeys.any(setOf("launcher_package", "dashboard_package", "ha_url")::contains),
        rendererTarget = changedKeys.any((ha + setOf("dashboard_package", "home_dashboard"))::contains),
        // The master switch closes a live session on this lane too, not only on the watchdog tick.
        camera = "camera_enabled" in changedKeys,
        // Credentials only; the owner itself ignores a change that leaves the credential identity equal.
        panelAssistantTransport = changedKeys.any(ha::contains),
        // A rename from Home Assistant, the sidebar or the Configure page, or a restored name.
        androidDeviceName = "friendly_name" in changedKeys,
    )
}

internal fun nextLiveSettingRetryAttempt(currentAttempt: Int, maximumAttempts: Int = 3): Int? =
    (currentAttempt + 1).takeIf { it < maximumAttempts }

internal data class NetworkConfigurationSnapshot(
    val runtime: NetworkRuntimeIdentity,
    /** The credential generation, not the derived access token: a refresh must not look like a change. */
    val haLink: HaAuthOwner,
    val haRouteEpoch: Long = 0L,
)

/**
 * Persistent foreground service. Hosts the Ktor HTTP listener, the JmDNS advertiser, the Panel
 * Assistant session and the hardware controllers for the panel's lifetime. Declared
 * `foregroundServiceType=specialUse` because a wall-panel on-LAN agent has no analogue among the
 * predefined FGS types.
 *
 * The service itself draws no UI and never takes HOME foreground. Renderer ownership remains in the
 * configured dashboard activity, whether that is the built-in Home Assistant renderer or a launchable
 * external dashboard application.
 */
class PaneldService : Service() {
    private var guardDbRedirect = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One dedicated transition lane owns initial start, config rebuilds, reconnects, and final teardown.
    // Observations capture the generation and its concrete state core, dispatcher and mDNS owners together, so tick/network work cannot read a generation from one runtime and then reach a replacement through a mutable field.
    internal data class NetworkRuntime(
        val core: PanelStateCore,
        val commands: PanelCommandDispatcher,
        val mdns: MdnsAdvertiser,
    )
    private lateinit var runtime: ServiceRuntimeOwner<NetworkRuntime>
    private val reconfigureKeysLock = Any()
    private val pendingReconfigureKeys = linkedSetOf<String>()
    private val liveSettingRetryAttempts = mutableMapOf<String, Int>()
    private val scheduledLiveSettingRetries = mutableSetOf<String>()
    private lateinit var restartLease: ServiceRestartBarrier.Lease
    private var startupInitialization: Job? = null
    @Volatile private var portalStartupOverlayDismissal: io.panelassistant.android.input.PortalStartupOverlayDismissal? = null
    internal var startupProfileRegistry: (android.content.Context) -> RuntimeProfileRegistry = { RuntimeProfileRegistry(it) }
    internal var portalStartupOverlayFactory: (android.content.Context, String) -> io.panelassistant.android.input.PortalStartupOverlayDismissal? =
        io.panelassistant.android.input.PanelAccessibilityService::preparePortalStartupOverlayDismissal
    private var pendingStart = false
    private lateinit var mainHandler: Handler
    private lateinit var liveSettingAuthority: LiveSettingAuthority
    private lateinit var config: Config
    private lateinit var appliedNetworkConfiguration: NetworkConfigurationSnapshot
    private lateinit var server: PaneldServer
    /** The named operations the HTTP routes register; every runtime generation's dispatcher serves them. */
    private val manageTable = ManageTable()
    private lateinit var rendererPreparation: RendererPreparationCoordinator
    private lateinit var entityLearning: EntityLearningManager
    // Daily health checks, maintenance and recovery for the app database and its files.
    private val storageHealth = ServiceStorageHealth(
        this, scope, { teardownBoundary.isStopping },
        object : ServiceStorageHealth.Database {
            override fun storageHealthObservation(cancellationSignal: CancellationSignal) =
                entityLearning.storageHealthObservation(cancellationSignal)
            override fun enforceHistoryRetention() = entityLearning.enforceHistoryRetention()
            override fun truncateDatabaseWal() = entityLearning.truncateDatabaseWal()
            override fun writeVerifiedConfigurationBackup() = entityLearning.writeVerifiedConfigurationBackup()
            override fun vacuumDatabase() = entityLearning.vacuumDatabase()
        },
        { ::companionDataOperationState.isInitialized && companionDataOperationState.isPending() },
    )
    private lateinit var companionDataOperationState: CompanionDataOperationState
    private val core: PanelStateCore get() = runtime.current().core
    // The Install tab's destructive operations, the update entities' sources and their observers.
    private val componentOperations = ServiceComponentOperations(this, scope, { config }, { core }, { profile })
    private val mdns: MdnsAdvertiser get() = runtime.current().mdns
    // Default-network callback that nudges reconnects when the network returns (see registerNetworkCallback).
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private data class MdnsRevalidation(
        val observed: ServiceRuntimeOwner.Observation<NetworkRuntime>,
        val addresses: MdnsLanAddresses?,
    )
    private val mdnsRevalidation = LatestDispatcher.singleSlot<MdnsRevalidation>(
        threadName = "ha-paneld-mdns-revalidation",
        consume = { request ->
            val current = runtime.observe() ?: return@singleSlot
            if (current.generation != request.observed.generation || current.value !== request.observed.value) {
                return@singleSlot
            }
            current.value.mdns.start(request.addresses?.primary, request.addresses?.secondary)
        },
        onFailure = { failure -> Log.w(TAG, "mDNS revalidation failed", failure) },
    )
    private lateinit var mdnsRuntimeReconciler: MdnsRuntimeReconciler<NetworkRuntime>
    @Volatile private var stateObservationTick: OwnedThread? = null
    private var proximityWizard: ProximityWizardCoordinator? = null
    private var approvalPrompt: ApprovalPrompt? = null

    /**
     * Whether this panel could repair its own Android System WebView, computed away from the screen.
     *
     * Cached rather than asked for on demand because deciding it costs a privileged probe: a cold
     * [Su.availableCachedIsolated] forks `su` and waits for it, and the caller is a blocked status screen
     * being drawn on the main thread. Null until the first answer lands, which the screen renders as
     * saying nothing rather than as a confident "this panel cannot".
     */
    private val lightPublisher = SensorLightPublisher(
        publish = { lux ->
            if (!teardownBoundary.isStopping) {
                runCatching { core.publishLight(lux) }
                    .onFailure { Log.w(TAG, "light publication failed", it) }
            }
        },
    )
    private lateinit var sensors: SensorReporter
    private lateinit var wifiDiagnostics: AndroidWifiDiagnostics
    private lateinit var wifiOutageTracker: WifiOutageTracker
    private lateinit var logShipper: LogShipper
    private lateinit var logCaptureApp: LogCapture
    private lateinit var logCaptureSystem: LogCapture
    private lateinit var logCaptureWebView: LogCapture

    // Controllers are fields so the network runtime can be rebuilt on a panel_id change.
    private lateinit var haLinks: ServiceHaLinks
    private val androidDeviceName by lazy { AndroidDeviceName.of(this) }
    // Outlives runtime generations: each new state core binds its converger here, and the transport owner
    // reports what it records only on a session Panel Assistant accepts. The button event's types include
    // the active profile's.
    private val panelAssistantReporter = PanelAssistantStateReporter(describe = { wire ->
        if (wire == "button" && ::profile.isInitialized) {
            // Each evdev button advertises its base event type; a button that opts in with release=true
            // also advertises its <base>_RELEASED type, so Home Assistant discovers only the release edges
            // that are actually emitted.
            val types = profile.evdevButtons.flatMapTo(linkedSetOf()) { button ->
                if (button.release) {
                    listOf(button.eventType, button.eventType + io.panelassistant.android.input.EvdevStreamSession.RELEASED_SUFFIX)
                } else {
                    listOf(button.eventType)
                }
            }
            io.panelassistant.android.panelassistant.PanelAssistantChannelCatalog.buttonDescriptor(types)
        } else io.panelassistant.android.panelassistant.PanelAssistantChannelCatalog.describe(wire)
    })

    // Native transport commands reach the current runtime generation's ordered command authority and the
    // same approval broker as every other remote command.
    private val panelAssistantCommands = object : PanelAssistantCommandSink {
        override fun submit(command: PanelAssistantCommand, done: (PanelAssistantCommandResult) -> Unit) {
            val commands = runCatching { runtime.current().commands }.getOrNull()
                ?: return done(PanelAssistantCommandResult.Failed(PanelAssistantCommandProcessor.CODE_FAILED))
            commands.submit(command, CommandOrigin.panelAssistant(command.requestId), done)
        }

        override fun approvalState(approvalId: String): PanelAssistantApprovalState =
            when (LocalApprovalBroker.instance.state(approvalId)) {
                ApprovalBroker.State.PENDING -> PanelAssistantApprovalState.PENDING
                ApprovalBroker.State.APPROVED -> PanelAssistantApprovalState.APPROVED
                ApprovalBroker.State.ABSENT -> PanelAssistantApprovalState.ABSENT
            }

        override fun withdrawApproval(approvalId: String) {
            LocalApprovalBroker.instance.deny(approvalId)
        }
    }
    private lateinit var camera: io.panelassistant.android.camera.CameraSessionOwner
    // The RTSP transport for the camera trial. It listens only while the camera switch is on, holds no
    // thread until then, and is drained before the camera owner so no client can reattach mid-stop.
    private lateinit var cameraStream: io.panelassistant.android.camera.CameraRtspServer
    private val notifications = ServiceNotifications(this)
    private lateinit var profile: DeviceProfile
    private lateinit var profileRegistry: RuntimeProfileRegistry
    private lateinit var passiveProfileProbe: AndroidPassiveProfileProbe
    private lateinit var profileRestart: ProfileRestartCoordinator
    private lateinit var recoveryRestart: ProfileRestartCoordinator
    private lateinit var webViewRebindRestart: ProfileRestartCoordinator
    private lateinit var activeProfileIdentity: String
    private var profileActivationGeneration: Long? = null
    // One-time-start guard for onStartCommand (see there for why). Reset in onDestroy.
    @Volatile private var started = false
    // This generation was created inside a process that had already committed to exiting, so it owns
    // nothing and must not be torn down as though it did. Never cleared: a stood-down generation stays
    // stood down for as long as the doomed process lives.
    @Volatile private var standingDown = false
    private val teardownBoundary = ServiceTeardownBoundary()
    // Screen, brightness, adaptive and ambient inputs, auto-sleep and power: built, started and closed
    // at the points below. Not named `display`, which Context already answers.
    private val displayPower: ServiceDisplay = ServiceDisplay(
        this, scope, teardownBoundary, { config }, { profile }, { runtime }, { ::runtime.isInitialized },
        { liveSettingAuthority }, { haLinks }, { sensors }, { ::sensors.isInitialized }, { controls },
    )
    // The panel's system controls: launcher and HOME, kiosk, navbar, watchdog, LEDs, volume, touch
    // sound, boot chime, Zigbee, relays, CPU, network ADB and USB debugging feedback.
    private val controls: ServiceSystemControls = ServiceSystemControls(
        this, scope, teardownBoundary, { config }, { profile }, { runtime }, { ::runtime.isInitialized },
        { liveSettingAuthority }, { server }, { mainHandler }, { displayPower.brightness }, { displayPower.autoBright }, { sensors },
        ::announcePanelAssistantRestart,
        vendorUp = { displayPower.restoreLevelOverVendor() },
    )
    // Voice, the microphone, audio playback and the media player: built, started and closed at the
    // points below, in the order this service has always used.
    private val voiceAudio: ServiceVoiceAudio = ServiceVoiceAudio(
        this, scope, teardownBoundary, notifications, { config }, { profile }, { standingDown },
        { if (::config.isInitialized) config.silenceBootChime else true },
        { runtime }, { controls.volume }, { mainHandler }, ::reconfigure,
    )
    // The screen-on and WebView-provider receivers, registered at startup and released in onDestroy.
    private val receivers = ServiceReceivers(
        this, teardownBoundary, voiceAudio.microphoneMute,
        { controls.vendorServiceStarter }, { displayPower.screen }, { core }, { webViewRebindRestart },
    )
    private val restartAfterInternalBoundary = AtomicBoolean(false)
    private var upgradeShutdownClaim: UpgradeShutdownClaim? = null
    private val startupRecoveryPrefs by lazy {
        AppState.preferences(this, "startup-recovery")
    }

    override fun onCreate() {
        super.onCreate()
        if (GuardDbProcessAdmission.maintenanceRequired()) {
            // A stale START_STICKY or explicit component intent may still name PaneldService after a
            // package replacement. Promote promptly, hand off to the narrow writer-free service, and
            // return before Config/AppState/profile/controller construction.
            notifications.startForegroundCompat(nativeString(R.string.guard_db_activity_label), silent = true)
            guardDbRedirect = true
            GuardDbMaintenanceService.start(this)
            stopSelf()
            return
        }
        // Android starts the foreground-service deadline before onCreate. Promote before profile/DB/
        // controller construction: slow root-backed initialization must never consume that deadline.
        // The bootstrap notification is deliberately silent until persisted notification policy is read.
        notifications.startForegroundCompat(nativeString(R.string.starting), silent = true)
        // stopSelf() clears START_STICKY, so an app-internal boundary has to re-arm its own start
        // request before exiting, and Android delivers that request into the still-live process. The
        // generation it creates here cannot outlive the exit: it could only claim the staged profile
        // activation, open the database and attach hardware owners alongside the outgoing runtime, then
        // die before proving any of it healthy — which is precisely what left the fresh process an
        // orphaned APPLYING to roll back. Stand down before any of that exists.
        if (PROCESS_BOUNDARY_COMMITMENT.admitServiceGeneration() == ServiceGenerationAdmission.STAND_DOWN) {
            standDownForCommittedProcessBoundary()
            return
        }
        restartLease = SERVICE_RESTART_BARRIER.enter()
        startupInitialization = scope.launch { initializeOnStartupLane() }
    }

    /** The service IO startup lane, launched once by [onCreate] after the restart lease is entered. */
    internal suspend fun initializeOnStartupLane() {
        // On a genuine Portal boot the vendor opens its denied overlay page within seconds of this
        // process starting, so arm the dismissal first. The profile store and the boot baseline are
        // independent of the settings migrations and of predecessor teardown below.
        val preparedProfiles = startupProfileRegistry(this@PaneldService)
        val resolvedProfile = preparedProfiles.resolveForStartup()
        if (teardownBoundary.isStopping) return
        preparePortalStartupOverlayDismissal(prepare = {
            portalStartupOverlayFactory(this@PaneldService, resolvedProfile.profile.id)
        })
        val preparedConfig = Config(this@PaneldService)
        preparedConfig.migrateLiveStore() // carry persisted settings across a schema bump before anything reads them
        // These migrations and generated identities precede every renderer and network owner.
        preparedConfig.migrateLogShipTcpDefault()
        preparedConfig.migrateSetupQuestionsForExistingInstall()
        preparedConfig.ensureDeviceUid()
        preparedConfig.ensurePanelId()
        val preparedLiveSettings = LiveSettingAuthority.persistent(
            this@PaneldService, PanelCommandDispatcher.APPLY_SETTING_KEYS,
            onLatePending = { key -> scheduleLiveSettingRetries(setOf(key)) },
        )
        val wifiStore = AndroidWifiOutageStore(this@PaneldService)
        val preparedEntityLearning = EntityLearningManager(
            context = this@PaneldService,
            config = preparedConfig,
            scope = scope,
            // mDNS is started by onStartCommand before the learner can synchronize.
            resolveInstanceUuid = { urls -> runtime.current().mdns.discoverHaInstanceUuid(urls) },
            expectedEntityTotal = {
                if (::haLinks.isInitialized) {
                    haLinks.panelAssistantTransport.expectedEntityCount(config.haAuthSnapshot().stableOwner())
                } else null
            },
            onFilterChanged = {
                if (shouldReloadBuiltinAfterEntityFilterChange(
                        controls.system.resolveDashboard(config.dashboardPackage),
                        SystemController.BUILTIN_DASHBOARD,
                        setupEntityFilterAnswered = config.setupEntityFilterAnswered,
                        setupEverCompleted = config.setupEverCompleted,
                    )) {
                    controls.system.reloadDashboard(
                        SystemController.BUILTIN_DASHBOARD,
                        reason = BuiltinDashboard.LEARNING_RELOAD_REASON,
                    )
                }
            },
        )
        // These controllers are constructed on main below, but each opens its namespace in
        // its constructor. Warm the same AppState cache while still on the service IO lane.
        AppState.preferences(this@PaneldService, "controller-state")
        AppState.preferences(this@PaneldService, "auto-brightness-runtime")
        AppState.preferences(this@PaneldService, "auto-sleep-learning")
        AppState.preferences(this@PaneldService, "startup-recovery")
        // Settle privileged startup before controllers cache grants or replay saved settings.
        // An older same-process service must finish teardown before this generation changes them.
        restartLease.awaitPredecessor()
        if (teardownBoundary.isStopping) return
        when (BundledHelperInstaller.ensureCurrent(this@PaneldService)) {
            BundledHelperInstaller.Result.INSTALLED -> Log.i(TAG, "migrated bundled root helper for this release")
            BundledHelperInstaller.Result.FAILED ->
                Log.w(TAG, "root helper migration failed; versioned helper features remain unavailable")
            BundledHelperInstaller.Result.BLOCKED_ACTIVE ->
                Log.i(TAG, "retaining current root helper while Guard DB maintenance owns recovery")
            BundledHelperInstaller.Result.REPROVISION_REQUIRED ->
                Log.w(TAG, "root helper matches this release but is not canonical; reprovision required")
            BundledHelperInstaller.Result.ALREADY_CURRENT,
            BundledHelperInstaller.Result.SKIPPED -> Unit
        }
        if (GuardDbProcessAdmission.ordinaryMutationsAllowed() && !teardownBoundary.isStopping) {
            PanelPermissionRepair.repair(
                this@PaneldService, voiceAudio.microphonePresence(resolvedProfile.profile).offered,
                cameraCapabilityReason(resolvedProfile.profile.cameraDeclared, cameraPresence.get()),
            )
        }
        withContext(Dispatchers.Main.immediate) {
            if (teardownBoundary.isStopping) return@withContext
            config = preparedConfig
            liveSettingAuthority = preparedLiveSettings
            profileRegistry = preparedProfiles
            initializeRuntimeAfterDatabase(resolvedProfile, wifiStore, preparedEntityLearning)
            if (pendingStart) startInitializedRuntime()
        }
    }

    private fun preparePortalStartupOverlayDismissal(
        prepare: () -> io.panelassistant.android.input.PortalStartupOverlayDismissal?,
        install: (io.panelassistant.android.input.PortalStartupOverlayDismissal) -> Unit = {
            io.panelassistant.android.input.PanelAccessibilityService.installPortalStartupOverlayDismissal(it)
        },
    ) {
        val dismissal = runCatching(prepare)
            .onFailure { Log.w(TAG, "could not prepare the Portal startup overlay dismissal", it) }.getOrNull()
        portalStartupOverlayDismissal = dismissal
        if (teardownBoundary.isStopping) dismissal?.close() else dismissal?.let(install)
    }

    private fun initializeRuntimeAfterDatabase(
        resolvedProfile: ResolvedProfile,
        wifiStore: AndroidWifiOutageStore,
        preparedEntityLearning: EntityLearningManager,
    ) {
        reconcileNativePresentationAfterPromotion()
        updateForegroundStatus(nativeString(R.string.starting))
        // Resolve one immutable profile revision before constructing any hardware owner. Activations are
        // restart-bound, so every controller below observes this exact object for the service lifetime.
        profile = resolvedProfile.profile
        activeProfileIdentity = resolvedProfile.summary.ref.let { "${it.id}@${it.revision}" }
        profileActivationGeneration = resolvedProfile.activationGeneration
        resolvedProfile.issues.forEach { issue ->
            Log.w(TAG, "profile ${issue.severity.name.lowercase()} ${issue.path}: ${issue.message}")
        }
        passiveProfileProbe = AndroidPassiveProfileProbe(this)
        mainHandler = Handler(Looper.getMainLooper())
        profileRestart = ProfileRestartCoordinator(
            schedule = { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
            restartProcess = {
                // START_STICKY is already the field-established restart route used for WebView
                // replacement. It avoids Android 12+'s background foreground-service start ban.
                requestSafeProcessBoundary("activating staged profile", "settings")
            },
            safeToRestart = { !InstallProgress.running &&
                !GuidedSetupPresence.activelyWalked(android.os.SystemClock.elapsedRealtime()) },
            shouldAbandon = { teardownBoundary.isStopping },
        )
        recoveryRestart = ProfileRestartCoordinator(
            schedule = { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
            restartProcess = {
                requestSafeProcessBoundary("bounded runtime recovery", "recovery")
            },
            safeToRestart = { !InstallProgress.running &&
                !GuidedSetupPresence.activelyWalked(android.os.SystemClock.elapsedRealtime()) },
            shouldAbandon = { teardownBoundary.isStopping },
            responseGraceMs = RECOVERY_RESTART_GRACE_MS,
        )
        // A WebView provider binds once per process, so the only way a panel parked on "Secure dashboard
        // bridge unavailable" can ever see a newly installed engine is on the far side of a process
        // boundary — the provider is bound once per app process
        // itself. Single-flight, so one provider install asks exactly once, and it survives the wait:
        // see webViewRebindRestartCoordinator for why nothing except teardown may discard it.
        webViewRebindRestart = webViewRebindRestartCoordinator(
            schedule = { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
            restartProcess = {
                requestSafeProcessBoundary("binding a newly installed WebView provider", "update")
            },
            destructiveOperationRunning = { InstallProgress.running },
            guidedSetupBeingWalked = {
                GuidedSetupPresence.activelyWalked(android.os.SystemClock.elapsedRealtime())
            },
            serviceStopping = { teardownBoundary.isStopping },
        )
        config.attachProfile(profile)   // supplies per-panel manufacturer/model defaults
        appliedNetworkConfiguration = currentNetworkConfigurationSnapshot()
        sensors = SensorReporter(this, config, profile)
        wifiOutageTracker = WifiOutageTracker(store = wifiStore)
        wifiDiagnostics = AndroidWifiDiagnostics(this) {
            Su.runOutputIsolatedBounded(
                "cmd wifi status 2>/dev/null || dumpsys wifi",
                maxBytes = 128 * 1024L,
                timeoutMs = 2_000L,
            )
        }
        // Shared demand-driven logcat captures (one producer and redaction pass per source): app,
        // system and browser feed the sink and their live viewers. System uses the helper when this
        // app cannot run su. Idle-stopped — no logcat runs until something subscribes.
        logCaptureApp = LogCapture.app(scope)
        logCaptureSystem = LogCapture.system(
            scope,
            suForm = profile.suForm,
            helperLines = { emit ->
                when (val opened = HelperClient.openLogcat()) {
                    is HelperLogcatOpenResult.Open -> opened.stream.use { stream ->
                        while (currentCoroutineContext().isActive) {
                            val line = try {
                                stream.readLine()
                            } catch (_: SocketTimeoutException) {
                                continue // bounded idle read lets cancellation close the helper socket
                            } ?: break
                            emit(line)
                        }
                    }
                    HelperLogcatOpenResult.Unsupported -> error("helper LOGCAT unsupported")
                    HelperLogcatOpenResult.Failed -> error("helper LOGCAT unavailable")
                }
            },
            rootAvailable = Su::availableCachedIsolated,
        )
        // The dashboard's JavaScript console, read over a CDP relay the user started. Off unless log
        // shipping is configured, and never in Hardened mode, where the relay itself is refused.
        logCaptureWebView = LogCapture.webView(scope) { webViewConsoleEnabled(config) }
        // Optional remote log shipping (off + inert unless a sink host is configured). Started in
        // onStartCommand alongside the other network subsystems; restarted on a /config change.
        logShipper = LogShipper(config, scope, logCaptureApp, logCaptureWebView, logCaptureSystem)

        displayPower.buildControllers()
        voiceAudio.buildAnnouncements()
        // The Home Assistant links and their health, built once in this order on the configuration above.
        haLinks = ServiceHaLinks(
            this, scope, config, teardownBoundary, panelAssistantReporter, panelAssistantCommands,
            voiceAudio.panelAssistantVoice, voiceAudio.voiceStream, { runtime }, { server },
        )
        displayPower.bindHaAmbient()
        controls.buildLighting()
        // Camera trial. Owned here, beside the LED it borrows for off-screen indication and the screen
        // whose intended-off state decides the handover; nothing runs until a subscriber asks for a frame.
        // The stream transport reads the owner through the FIELD so construction order does not matter:
        // it only asks for the camera once a client connects, which cannot happen before the owner
        // exists because the owner is what turns listening on.
        voiceAudio.buildSharedMicrophone()
        cameraStream = io.panelassistant.android.camera.CameraRtspServer(
            source = { camera },
            log = { Log.w(TAG, "camera stream: $it") },
            clockUs = { android.os.SystemClock.elapsedRealtimeNanos() / 1_000L },
            audio = io.panelassistant.android.camera.AndroidCameraAudioSource(
                source = { voiceAudio.sharedMicrophone.get() },
                admitted = {
                    // Any offered microphone: an unproven silent one costs only a silent track.
                    voiceAudio.microphonePresence().offered && config.cameraEnabled && !teardownBoundary.isStopping &&
                        androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                },
                foreground = { active -> voiceAudio.microphoneClaims.set("camera", active) },
                log = { message, error -> Log.w(TAG, message, error) },
            ),
        )
        camera = io.panelassistant.android.camera.CameraSessionOwner(
            context = this,
            hasCamera = { cameraPresent() },
            capabilityReason = { cameraReason() },
            enabled = { config.cameraEnabled },
            defaultResolution = { config.cameraResolution },
            defaultFps = { config.cameraFps },
            defaultKbps = { config.cameraKbps },
            exposureEv = { config.cameraExposureEv },
            transport = cameraStream,
            permissionGranted = {
                androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            },
            indicator = io.panelassistant.android.camera.CameraIndicator(
                context = this,
                ledEffect = controls.ledEffect,
                // Re-derive from persisted intent through whichever runtime is current, never a snapshot.
                restoreLed = { runCatching { runtime.current().core.reapplyStoredLed() } },
                screenOff = { displayPower.screen.isIntendedOff() },
                cameraLensOffsetPx = profile.cameraLensOffsetPx,
            ),
            foreground = io.panelassistant.android.camera.AndroidCameraForegroundGate(this),
        )
        controls.buildNavigation()
        voiceAudio.buildPlayback()
        controls.buildSystem()
        companionDataOperationState = CompanionDataOperationState.from(this)
        entityLearning = preparedEntityLearning
        controls.buildControls()
        displayPower.buildPowerAndSleep()

        runtime = ServiceRuntimeOwner(
            initial = buildNetworkRuntime(appliedNetworkConfiguration.runtime),
            threadName = "ha-paneld-runtime",
            onError = { operation, error -> Log.e(TAG, "runtime $operation failed", error) },
            onRecoverySaturated = {
                if (!teardownBoundary.isStopping && !recoveryRestart.request()) {
                    Log.w(TAG, "runtime recovery restart is already scheduled")
                }
            },
            latestOperation = LatestOperationPolicy(
                name = "network-reconfigure",
                timeout = LatestOperationTimeoutPolicy(
                    budgetMs = NETWORK_RECONFIGURE_BUDGET_MS,
                    schedule = { task, delayMs -> mainHandler.postDelayed(task, delayMs) },
                    cancel = mainHandler::removeCallbacks,
                    onTimeout = ::onNetworkReconfigureTimeout,
                ),
                operation = { performNetworkReconfigure(this) },
            ),
        )
        mdnsRuntimeReconciler = MdnsRuntimeReconciler(runtime, ::revalidateMdns)
        rendererPreparation = RendererPreparationCoordinator(
            builtinPackage = SystemController.BUILTIN_DASHBOARD,
            state = { RendererPreparationState(
                config.dashboardPackage,
                config.haUrl,
                config.rendererLaunchPending,
            ) },
            borrow = borrow@{
                val login = CompanionDb.readLogin(this, Su) ?: return@borrow null
                BorrowedRendererSettings(
                    url = login.url,
                    accessToken = login.accessToken,
                    refreshToken = login.refreshToken,
                    tokenExpiry = login.expirySec,
                    clientId = CompanionDb.COMPANION_CLIENT_ID,
                    zoom = CompanionDb.readPageZoom(this, Su)?.coerceIn(50, 300),
                )
            },
            persist = { borrowed ->
                commitBorrowedRendererTarget(
                    commit = {
                        config.setBorrowedRendererSettings(
                            url = borrowed.url,
                            accessToken = borrowed.accessToken,
                            refreshToken = borrowed.refreshToken,
                            tokenExpiry = borrowed.tokenExpiry,
                            clientId = borrowed.clientId,
                            zoom = borrowed.zoom,
                        )
                    },
                    onCommitted = entityLearning::onTargetConfigurationChanged,
                )
            },
            completeLaunch = config::completeRendererLaunch,
        )
        server = PaneldServer(
            config, cacheDir, scope, this, sensors, profile, controls.system, controls.volume, voiceAudio.audio::submit, ::reconfigure,
            // The dispatcher is replaceable. If its command admission is already draining, retain the
            // persisted setting and replay it against the replacement before reconfigure completes.
            { k, v ->
                requestLiveSetting(liveSettingAuthority, config, { runtime.current().commands }, k, v, { controls.kiosk.apply(false) }) {
                    displayPower.refreshAutoSleepPresence()
                }
            },
            onProximityCalibration = { action, id -> proximityWizard?.remote(action, id) == true },
            pendingLiveSettings = liveSettingAuthority::pendingSnapshot,
            stalledLiveSettings = liveSettingAuthority::pendingStalledSnapshot,
            assistPipelines = io.panelassistant.android.assist.HaAssistPipelineDirectory(config),
            voiceTest = io.panelassistant.android.assist.VoiceTestTrigger { voiceAudio.voice.trigger() },
            voiceMicrophone = { voiceAudio.voice.microphoneStatus() },
            wakeWords = voiceAudio.wakeWordCatalog,
            onWakeWordsChanged = { voiceAudio.restartVoice() },
            // Controller-sourced setting values (their state isn't in the config namespace) so the
            // config form/schema/dashboard show live truth. Called on Ktor IO threads (su-safe).
            configLiveValues = ::currentConfigLiveValues,
            managementProjection = ::managementProjection,
            recommendedDensity = profile.recommendedDensity,
            recommendedFontScale = profile.recommendedFontScale,
            // Vendor taming: the controller and this panel's curated recommendations (picker group 1).
            tame = controls.tame, tameProfileCandidates = profile.tameVendorCandidates,
            // Live log viewer sources (Logs tab). System is gated on Su.available() per request.
            logApp = logCaptureApp, logSystem = logCaptureSystem, logWebView = logCaptureWebView,
            webViewConsoleEnabled = { webViewConsoleEnabled(config) },
            effectiveBrightness = { displayPower.brightness.getBrightness() },
            onRepairCompanionUrl = componentOperations::repairCompanionUrl,
            onInstallComponent = { name, action, version -> componentOperations.installComponent(name, action, version) },
            powerSafety = { displayPower.powerSafety.assess(config.keepAwake, config.preventIdleDim) },
            freshPowerSafetyRepairCapability = displayPower.powerSafety::repairCapabilityFresh,
            onRepairPowerSafety = displayPower::repairPowerSafety,
            // One-line EFR32 radio status for the Install-tab Radio card; null when this panel has no radio.
            radioStatus = { if (profile.zigbeeGatewayDir != null) controls.zigbeeHealth.snapshot() else null },
            camera = camera,
            permissionStatus = { PanelPermissionRepair.observe(this, voiceAudio.microphonePresence().offered, cameraReason()) },
            panelAssistantTransport = haLinks.panelAssistantTransport,
            onZigbeeJoinRetry = { runtime.current().commands.requestZigbeeJoin() },
            // LAN ha-paneld peers over mDNS for the header panel switcher. Captures the `mdns` FIELD (not a
            // snapshot) so it follows reconfigure()'s reassignment; browsePeers null-guards the swap window.
            peers = { mdns.browsePeers() },
            // Report the current advertiser rather than a startup snapshot: DHCP may not have completed
            // when the service began, and the operator needs to see a later failed/deferred advertisement.
            mdnsWarningProjection = {
                val health = mdns.health()
                mdnsHealthWarning(health) to mdnsHealthPresentation(health)
            },
            configDiscoverySuggestions = {
                val haDiscovery = runtime.current().mdns.discoverHaBaseUrlDetailed()
                ConfigDiscoverySuggestions(haUrl = haDiscovery.value, haDiscovery = haDiscovery)
            },
            rendererPreparation = rendererPreparation,
            entityLearning = entityLearning,
            autoBrightnessHttpApi = displayPower.autoBrightnessHttpApi(),
            autoSleepHttpApi = displayPower.autoSleepHttpApi(),
            companionDataOperationState = companionDataOperationState,
            profileAdmin = profileRegistry,
            profileTemplate = {
                val report = passiveProfileProbe.report()
                ProfileDraftFactory.create(report.facts, report).rawYaml
            },
            profileDeviceDraft = {
                passiveProfileProbe.report().takeIf { profile.id == "generic" }
                    ?.let(ProfileDraftFactory::fromReport)
            },
            profileReport = passiveProfileProbe::report,
            profileProbe = { passiveProfileProbe.report() },
            onProfileRestart = { profileRestart.request() },
            onDurableStateRestored = {
                wifiOutageTracker.adoptRestoredRecord()
                sensors.reloadProximityCalibration()
                check(displayPower.autoSleep.adoptRestoredRecord()) { "sleep owner is stopping" }
                core.reapplyStoredLed()
                core.restoreButtonBacklight()
            },
            profileRestartAllowed = { !InstallProgress.running },
            onProfileRestartAbort = profileRegistry::abortPendingActivation,
            storageHealth = StorageHealthRuntime::snapshot,
            refreshStorageHealth = storageHealth::refreshStorageHealthForStatus,
            manageTable = manageTable,
            door = { runCatching { runtime.current().commands }.getOrNull() },
        )
        storageHealth.subscribe(::onStorageHealthSnapshot)
        sensors.setLearnedProximityListener {
            server.invalidateCapabilitySnapshot()
            runtime.observe()?.value?.core?.notifyLearnedProximityChanged()
        }
        sensors.setLightAvailabilityListener {
            server.invalidateCapabilitySnapshot()
            runtime.observe()?.value?.core?.notifyLightAvailabilityChanged()
        }
    }

    private fun buildNetworkRuntime(identity: NetworkRuntimeIdentity): NetworkRuntime {
        val core = buildCore()
        return NetworkRuntime(core, buildCommands(core), buildMdns(identity))
    }

    private fun buildCore(): PanelStateCore = PanelStateCore(
        config, displayPower.brightness, displayPower.screen, controls.led, controls.ledEffect, controls.volume, controls.system, controls.bootChime,
        controls.zigbee, controls.relay, controls.cpu, controls.adb,
        accessibilityEnabled(), profile.buttonKeys(),
        { capabilitiesSnapshot() },
        sensors.hasProximity(),
        sensors.hasTemperature(), sensors.hasHumidity(),
        profile.hasCht8305,
        // Button backlight is a distinct profiled node (TPA10), not a property of the RGB backend:
        // SMT1019 also uses SocketLedController for RGB but has no button-backlight node.
        profile.hasButtonBacklight,
        buttonBacklightTransfer = profile.buttonBacklightTransfer,
        media = voiceAudio.media.takeIf { profile.hasSpeaker },
        hasCamera = { cameraReason().takeUnless { it == CameraCapabilityReason.UNDETERMINED }?.capable },
        lightChannel = sensors::lightChannel,
        canBack = InteractiveController(canSu = profile.appCanSu)::canBack,
        autoSleepActivity = { displayPower.autoSleep.activitySnapshot() },
        softwareUpdateSources = componentOperations::softwareUpdateSources,
        zigbeeHealth = controls.zigbeeHealth::snapshot,
        storageHealth = StorageHealthRuntime::snapshot,
        wifiDiagnostics = wifiDiagnostics::snapshot,
        wifiOutages = { wifiOutageTracker.counts() },
        learnedProximityEligibility = sensors::hasLearnedProximity,
        learnedProximityState = sensors::learnedProximityState,
        usbDebugging = controls.usbDebugging::observation,
    ).also { core ->
        core.addStateSink(panelAssistantReporter.bindShape(core::nativeChannelShape))
        core.addEventSink(panelAssistantReporter::event)
    }

    private fun buildCommands(core: PanelStateCore): PanelCommandDispatcher = PanelCommandDispatcher(
        config, core, displayPower.brightness, displayPower.screen, controls.navigate, controls.volume, controls.system, controls.navbar,
        controls.watchdog, controls.touchSound, controls.bootChime, controls.zigbee, controls.relay, controls.cpu, controls.adb,
        hasProximity = sensors.hasProximity(),
        // Button backlight is a distinct profiled node (TPA10), not a property of the RGB backend:
        // SMT1019 also uses SocketLedController for RGB but has no button-backlight node.
        hasButtonBacklight = profile.hasButtonBacklight,
        buttonBacklightTransfer = profile.buttonBacklightTransfer,
        hasMicrophone = voiceAudio.microphonePresence().offered,
        media = voiceAudio.media.takeIf { profile.hasSpeaker },
        hasCamera = { cameraReason().takeUnless { it == CameraCapabilityReason.UNDETERMINED }?.capable },
        autoBright = displayPower.autoBright,
        onAutoBrightnessConfigChanged = { displayPower.refreshAdaptiveBrightnessInputs() },
        // A camera switch commanded from Home Assistant performs the same actuation the reconfigure
        // fan-out performs for a Configure-page change, so the owner has one authority, not two.
        onCameraEnabledChanged = { if (::camera.isInitialized) runCatching { camera.onEnabledChanged() } },
        onDashboardTargetChanged = entityLearning::onTargetConfigurationChanged,
        kioskApply = controls.kiosk::apply,
        goBack = InteractiveController(canSu = profile.appCanSu)::back,
        kioskRecover = controls.kiosk::recoverPersistentState,
        onExternalSettingApplied = liveSettingAuthority::discard,
        onZigbeeExplicitRetry = controls.zigbeeHealth::explicitRetry,
        goRecents = InteractiveController(canSu = profile.appCanSu)::recents,
        onAutoSleepConfigChanged = {
            acceptCommittedAutoSleepSetting(liveSettingAuthority) { displayPower.refreshAutoSleepPresence() }
        },
        manageTable = manageTable,
    )

    private fun buildMdns(identity: NetworkRuntimeIdentity): MdnsAdvertiser = MdnsAdvertiser(
        this,
        config,
        runtimePanelId = identity.panelId,
        runtimeFriendlyName = identity.friendlyName,
        runtimeHttpPort = identity.httpPort,
    )

    /** Make the journalled desired value immediately visible/durable, but pass the previous value through
     * to transition-sensitive handlers. Transient HA-fed inputs intentionally have no persisted value. */
    private fun applyLiveSettingObserved(
        commands: PanelCommandDispatcher,
        key: String,
        value: String,
        previousValue: String? = null,
    ): LiveSettingApplication = applyLiveSetting(config, commands, key, value, previousValue) { displayPower.refreshAutoSleepPresence() }

    private fun replayLiveSettingObserved(
        commands: PanelCommandDispatcher,
        key: String,
        value: String,
        previousValue: String?,
        fence: Long?,
    ): LiveSettingApplication {
        if (key != "auto_sleep" || fence == null) {
            return applyLiveSettingObserved(commands, key, value, previousValue)
        }
        return LiveSettingApplication.immediate(applyAutoSleepFencedReplay(
            fence = fence,
            currentGeneration = config.autoSleepGeneration,
            currentEnabled = config.autoSleep,
            persistOff = { generation ->
                config.setAutoSleepIf(expected = true, expectedGeneration = generation, on = false)
            },
            refreshController = { displayPower.refreshAutoSleepPresence() },
            applyBridge = { commands.convergeAutoSleep(expectedGeneration = fence, expectedValue = false) },
        ))
    }

    /**
     * Apply config the HTTP page has already written to [config], then rebuild the network runtime.
     */
    private fun reconfigure(changedKeys: Set<String>) {
        enqueueReconfigure(changedKeys, resetLiveRetries = true)
    }

    private fun enqueueReconfigure(changedKeys: Set<String>, resetLiveRetries: Boolean) {
        synchronized(reconfigureKeysLock) {
            pendingReconfigureKeys += changedKeys
            if (resetLiveRetries) changedKeys.forEach { liveSettingRetryAttempts[it] = 0 }
        }
        if (configOwnerRefreshPlan(changedKeys).launcherHome) {
            controls.onLauncherHomeChanged()
        }
        when (runtime.requestLatest()) {
            ServiceRuntimeOwner.LatestAdmission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.NETWORK_RECONFIGURE)
            ServiceRuntimeOwner.LatestAdmission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.NETWORK_RECONFIGURE)
            ServiceRuntimeOwner.LatestAdmission.ACCEPTED -> Unit
        }
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.NETWORK_RECONFIGURE,
            runtime.pendingLatestCount(),
        )
    }

    private fun performNetworkReconfigure(
        mutation: ServiceRuntimeOwner<NetworkRuntime>.LatestMutation,
    ) {
        val executionDeadline = mutation.deadline
        if (executionDeadline.remainingMs() <= 0L) return
        val changedKeys = synchronized(reconfigureKeysLock) {
            pendingReconfigureKeys.toSet().also { pendingReconfigureKeys.clear() }
        }
        val ownerRefresh = configOwnerRefreshPlan(changedKeys)
        // A committed configuration application starts a fresh comparison epoch while process-lifetime
        // startup evidence remains available in the same fixed-cardinality diagnostics projection.
        FeatureCosts.beginEpoch()
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.NETWORK_RECONFIGURE,
            runtime.pendingLatestCount(),
        )
        var cost: io.panelassistant.android.metrics.FeatureCostRegistry.Span? = null
        var obligationsCompleted = false
        try {
            val desired = currentNetworkConfigurationSnapshot()
            val replacementRequired =
                desired.runtime != appliedNetworkConfiguration.runtime || !mutation.isRunning
            val adaptiveSourceRestartRequired =
                replacementRequired || desired.haLink != appliedNetworkConfiguration.haLink ||
                    desired.haRouteEpoch != appliedNetworkConfiguration.haRouteEpoch
            val operation = if (replacementRequired) FeatureCostOperation.NETWORK_RECONFIGURE
                else FeatureCostOperation.CONFIG_LIVE_REFRESH
            val operationCost = FeatureCosts.registry.span(operation)
                .work(units = if (replacementRequired) 1 else 0)
            cost = operationCost
            if (ownerRefresh.adaptiveBrightness) {
                displayPower.refreshAdaptiveBrightnessInputs(restartSource = adaptiveSourceRestartRequired)
            }
            if (!replacementRequired) {
                val active = mutation.current
                val activeCommands = mutation.current.commands
                val replayKeys = liveSettingAuthority.pendingSnapshot().keys.intersect(changedKeys)
                if (replayKeys.isNotEmpty()) {
                    liveSettingAuthority.replayKeysObserved(replayKeys) { key, value, previous, fence ->
                        replayLiveSettingObserved(activeCommands, key, value, previous, fence)
                    }
                    scheduleLiveSettingRetries(replayKeys)
                }
                refreshLiveConfiguration(
                    active.core,
                    desired,
                    ownerRefresh = ownerRefresh,
                    resolveHaLink = desired.haLink != appliedNetworkConfiguration.haLink,
                    cost = operationCost,
                    changedKeys = changedKeys,
                )
                obligationsCompleted = true
                return
            }
            // Only mDNS carries the panel's name, id and port. A running runtime keeps its state core and
            // dispatcher and replaces the advertiser alone; a failed one is rebuilt whole.
            val advertiserOnly = mutation.isRunning
            val completed = mutation.replace(
                retire = { previous ->
                    // Start both retirement fences before awaiting either result. They share one monotonic
                    // budget; no locally reset phase timeout can accumulate beyond it.
                    val mdnsRetirement = previous.mdns.retire(executionDeadline)
                    val stateDrained = advertiserOnly || (previous.commands.stop(executionDeadline) and
                        previous.core.stop(executionDeadline))
                    val mdnsStopped = mdnsRetirement.awaitTrue(executionDeadline)
                    val withinDeadline = executionDeadline.remainingMs() > 0L
                    check(stateDrained && mdnsStopped && withinDeadline) {
                        "retiring network runtime did not prove cleanup " +
                            "(state=$stateDrained mdns=$mdnsStopped withinDeadline=$withinDeadline)"
                    }
                },
                build = { previous ->
                    if (advertiserOnly) previous.copy(mdns = buildMdns(desired.runtime))
                    else buildNetworkRuntime(desired.runtime)
                },
                start = { replacement ->
                    replacement.mdns.start()
                    replacement.core.maybeResolveHaLink()
                    if (!advertiserOnly) replacement.core.start()
                },
                complete = { replacement ->
                    liveSettingAuthority.replayKeysObserved(PanelCommandDispatcher.APPLY_SETTING_KEYS) { key, value, previous, fence ->
                        replayLiveSettingObserved(replacement.commands, key, value, previous, fence)
                    }
                    // Publish only the immutable configuration this concrete replacement consumed. A newer
                    // config can arrive while start() is blocked; retaining this older snapshot makes the
                    // conflated rerun replace/re-project again instead of mistaking that newer config for live.
                    refreshLiveConfiguration(
                        replacement.core,
                        desired,
                        ownerRefresh = ownerRefresh,
                        resolveHaLink = false,
                        cost = operationCost,
                        changedKeys = changedKeys,
                    )
                    Log.i(TAG, "reconfigured: panel=${desired.runtime.panelId}")
                },
            )
            if (!completed) {
                operationCost.outcome(FeatureCostOutcome.REJECTED)
                if (executionDeadline.remainingMs() > 0L) requestNetworkRecovery()
            } else {
                obligationsCompleted = true
                // A default-network callback can also land while replacement owns the unobservable
                // RECONFIGURING state. Reconcile its retained topology against the published successor.
                mdnsRuntimeReconciler.runtimeRunning()
            }
        } catch (e: InterruptedException) {
            cost?.outcome(FeatureCostOutcome.CANCELLED)
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            cost?.outcome(FeatureCostOutcome.FAILURE)
            Log.w(TAG, "network reconfigure failed", e)
            if (executionDeadline.remainingMs() > 0L) requestNetworkRecovery()
        } finally {
            if (obligationsCompleted && ownerRefresh.panelAssistantTransport) {
                runCatching { haLinks.refreshPanelAssistantTransport() }
            }
            if (!obligationsCompleted) synchronized(reconfigureKeysLock) {
                pendingReconfigureKeys += changedKeys
            }
            if (executionDeadline.remainingMs() <= 0L && !Thread.currentThread().isInterrupted) {
                cost?.outcome(FeatureCostOutcome.REJECTED)
            }
            cost?.close()
        }
    }

    private fun scheduleLiveSettingRetries(attemptedKeys: Set<String>) {
        val remaining = liveSettingAuthority.pendingSnapshot().keys.intersect(attemptedKeys)
        val retryable = liveSettingAuthority.retryablePendingKeys()
        val retry = synchronized(reconfigureKeysLock) {
            attemptedKeys.minus(remaining).forEach(liveSettingRetryAttempts::remove)
            remaining.filterTo(linkedSetOf()) { key ->
                if (key !in retryable || key in scheduledLiveSettingRetries) return@filterTo false
                val next = nextLiveSettingRetryAttempt(liveSettingRetryAttempts[key] ?: 0)
                if (next == null) false else {
                    liveSettingRetryAttempts[key] = next
                    scheduledLiveSettingRetries += key
                    true
                }
            }
        }
        if (retry.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                kotlinx.coroutines.delay(1_000)
            } finally {
                synchronized(reconfigureKeysLock) { scheduledLiveSettingRetries.removeAll(retry) }
            }
            val stillPending = liveSettingAuthority.retryablePendingKeys().intersect(retry)
            if (stillPending.isNotEmpty()) enqueueReconfigure(stillPending, resetLiveRetries = false)
        }
    }

    private fun requestNetworkRecovery() {
        if (!teardownBoundary.isStopping) recoveryRestart.request()
    }

    private fun onNetworkReconfigureTimeout() {
        FeatureCosts.registry.setBacklog(
            FeatureCostOperation.NETWORK_RECONFIGURE,
            runtime.pendingLatestCount(),
        )
        requestNetworkRecovery()
    }

    private fun currentNetworkIdentity(): NetworkRuntimeIdentity =
        NetworkRuntimeIdentity(config.panelId, config.friendlyName, config.httpPort)

    private fun currentNetworkConfigurationSnapshot(): NetworkConfigurationSnapshot =
        config.synchronizedTransaction {
            NetworkConfigurationSnapshot(
                runtime = currentNetworkIdentity(),
                haLink = currentHaLinkIdentity(),
                haRouteEpoch = config.haRouteEpoch(),
            )
        }

    private fun currentHaLinkIdentity(): HaAuthOwner = config.haAuthSnapshot().stableOwner()

    private fun refreshLiveConfiguration(
        core: PanelStateCore,
        desired: NetworkConfigurationSnapshot,
        ownerRefresh: ConfigOwnerRefreshPlan,
        resolveHaLink: Boolean,
        cost: io.panelassistant.android.metrics.FeatureCostRegistry.Span,
        changedKeys: Set<String>,
    ) {
        if (resolveHaLink) {
            core.maybeResolveHaLink()
            cost.work(units = 1L)
        }
        appliedNetworkConfiguration = desired
        if (ownerRefresh.logShipping) runCatching { logShipper.reconfigure() }
        if (ownerRefresh.keepAwake) displayPower.applyKeepAwake()
        if (ownerRefresh.autoSleep) runCatching { displayPower.refreshAutoSleepPresence() }
        if (ownerRefresh.rendererTarget) {
            haLinks.haExactEntityStream.replaceHaLink(currentHaLinkIdentity(), config.haRouteEpoch())
            io.panelassistant.android.http.PerfReader.updateRendererTarget(rendererTargetSnapshot())
        }
        if (ownerRefresh.camera && ::camera.isInitialized) runCatching { camera.onEnabledChanged() }
        // A camera switch moved on the Configure page, by a bundle import or by provisioning must reach
        // Home Assistant too; otherwise its switch keeps a position the panel has already left.
        if (ownerRefresh.camera) runCatching { core.publishCameraState() }
        runCatching { core.publishExposureChanges(changedKeys) }
        if (ownerRefresh.androidDeviceName) {
            val outcome = androidDeviceName.follow(config.chosenFriendlyName)
            if (outcome != AndroidDeviceName.Outcome.NOT_CHOSEN && outcome != AndroidDeviceName.Outcome.UNCHANGED) {
                Log.i(TAG, "android device name: ${outcome.name.lowercase()}")
            }
        }
    }

    /** Controller reads shared by the dashboard facts, live values and capability projection. */
    private data class ManagementControllerObservation(
        val cpuTier: String?,
        val cpuGovernorsAvailable: Boolean,
        val networkAdbPersisted: Boolean,
        val networkAdbActive: Boolean?,
        val zigbee: ZigbeeObservation,
        val relayCount: Int,
        val buttonLedCount: Int,
    )

    private fun observeManagementControllers(privilege: PrivilegedRouteObservation): ManagementControllerObservation {
        val persistedAdb = controls.adb.isPersisted()
        return ManagementControllerObservation(
            cpuTier = controls.cpu.currentTier(allowRootFallback = privilege.directSuReady),
            cpuGovernorsAvailable = controls.cpu.available(allowRootFallback = privilege.directSuReady),
            networkAdbPersisted = persistedAdb,
            // A persisted intent is reported by whether adbd really listens: its own property writes
            // would otherwise read as active while vendor init or an adbd restart has dropped TCP.
            networkAdbActive = if (persistedAdb) controls.adb.listenerState(allowRootCrossCheck = privilege.directSuReady)
                else controls.adb.activeState(allowRootCrossCheck = privilege.directSuReady),
            zigbee = controls.zigbee.observe(includeRole = true, directSuReady = privilege.directSuReady),
            relayCount = controls.relay.count(allowRootProbe = privilege.directSuReady),
            buttonLedCount = controls.relay.ledCount(),
        )
    }

    /**
     * Controller-sourced values, which is why `touch_sound` is deliberately absent.
     *
     * A key in this map overrides the persisted registry value on every reporting surface, so it must
     * name state the panel genuinely owns at that instant. Touch sound is not such a state: its platform
     * flag has other writers, and letting an observation of it stand in for the setting made an unrelated
     * save post a stale value and queue an apply the user never asked for. Its authority is the persisted
     * intent, which [io.panelassistant.android.http.PaneldServer.effectiveValue] reaches by falling
     * through to configuration.
     */
    private fun projectLiveValues(cpuTier: String?, networkAdbPersisted: Boolean): Map<String, String> =
        mapOf(
            "cpu_governor" to (cpuTier ?: "Auto"),
            "network_adb" to networkAdbPersisted.toString(),
            "zigbee_router" to config.zigbeeRouterEnabled.toString(),
        )

    /** Fresh controller values for direct equality, config export and concurrency checks. Direct POST
     * planning also consumes the transient CPU tier, so a full payload cannot reapply an unchanged tier. */
    private fun currentConfigLiveValues(): Map<String, String> = mapOf(
        "cpu_governor" to (controls.cpu.currentTier(allowRootFallback = false) ?: "Auto"),
        "network_adb" to controls.adb.isPersisted().toString(),
        "zigbee_router" to config.zigbeeRouterEnabled.toString(),
    )

    private fun managementProjection(privilege: PrivilegedRouteObservation): ManagementProjection {
        val controllers = observeManagementControllers(privilege)
        val diagnostic = DiagReader.capabilities(this, profile, privilege)
        // ONE outage read feeds both the diagnostics row and the /diag gate. Reading the tracker
        // twice would let an episode age out between them, and a report could then omit the line
        // while printing a row that says the Wi-Fi needs attention, or the reverse.
        val wifi = wifiOutageTracker.counts()
        return ManagementProjection(
            facts = panelInfo(controllers, diagnostic.rgbLedReady, wifi),
            live = projectLiveValues(
                cpuTier = controllers.cpuTier,
                networkAdbPersisted = controllers.networkAdbPersisted,
            ),
            capabilities = capabilitiesSnapshot(privilege, controllers),
            capabilityRows = diagnostic.rows,
            wifiChronic = wifiOutageChronic(wifi),
        )
    }

    /** Ordered facts for the info page (`GET /`). */
    private fun panelInfo(
        controllers: ManagementControllerObservation,
        rgbLedReady: Boolean,
        wifiOutages: WifiOutageCounts,
    ): Map<String, String> {
        val pv = SystemProps.get("ro.product.version")
        val appDatabase = runCatching { PanelInfo.databaseSummary(entityLearning.databaseUsage()) }.getOrNull()
        val extras = linkedMapOf(
            "panel_id" to config.panelId,
            "Friendly name" to config.friendlyName,
            "HTTP port" to config.httpPort.toString(),
            "Local IP" to (localIpv4() ?: "?"),
            "Local IPv6" to (localIpv6() ?: "—"),
            "Security mode" to if (config.hardenedSecurityEnabled) {
                "Hardened · high-impact remote actions need physical on-panel approval"
            } else {
                "Relaxed"
            },
            // Wakelock/Wi-Fi-lock intent vs reality — a panel that should be keep-awake but isn't
            // holding the lock is a strong hint for stalled-idle-connection reports.
            "Keep panel responsive" to if (config.keepAwake) (if (displayPower.power.isHeld()) "on · power locks held" else "on · power lock NOT held") else "off",
            "Prevent idle dim" to preventIdleDimDiagnostic(config.preventIdleDim, displayPower.brightness.screenOffTimeoutMs()),
            "Android dashboard lock" to if (config.kioskLock) "on" else "off",
            "mDNS" to mdns.statusPublic(),
            "Platform" to profile.displayName,
            "SoC" to (profile.soc?.displayText() ?: profile.socClass),
            "Model" to profile.panelModelLabel(pv),
            "LED" to ledLabel(rgbLedReady),
            "Light sensor" to sensorRow(sensors.hasLight(), profile.lightTech, sensors.lightDesc()),
            "Proximity" to sensorRow(sensors.hasProximity(), profile.proximityTech, sensors.proximityDesc()),
            // a11y service = software back/recents nav, NOT physical buttons (NSPanel Pro has none).
            "Nav actions (a11y)" to (if (accessibilityEnabled()) "yes" else "no"),
            // Soft navbar mode + whether the overlay can actually be drawn (SYSTEM_ALERT_WINDOW). Test the
            // modes that draw a bar rather than "not Off": Native draws nothing either, so warning about a
            // missing overlay permission there would be a false alarm on every natively-barred panel.
            "Navbar" to (
                config.navbarMode +
                    if (config.navbarMode in NavbarController.OVERLAY_MODES && !canDrawOverlays()) {
                        " · no overlay permission"
                    } else {
                        ""
                    }
                ),
            "Zigbee" to controllers.zigbee.status,
            "Relays" to controllers.relayCount.let { if (it > 0) it.toString() else "none" },
            "CPU profile" to (controllers.cpuTier ?: "n/a"),
            "Network ADB" to networkAdbDiagnostic(controllers.networkAdbPersisted, controllers.networkAdbActive),
            "USB debugging" to controls.usbDebugging.statusText(),
            "Log shipping" to logShipper.statusText(),
            "Audio playback" to voiceAudio.audio.snapshot().statusText(),
        )
        appDatabase?.let { extras["App database"] = it }
        // Rolling 24-hour Wi-Fi outage count — present only once the window holds an episode or
        // dropped evidence, so a stable panel (or an Ethernet panel) never carries a zero row.
        wifiOutageStatusText(wifiOutages)?.let { extras["Wi-Fi stability"] = it }
        if (pv.isNotEmpty()) extras["Product version"] = pv
        // Recent "changed outside a command" events (brightness/volume/backlight/governor) — shown only when
        // something has actually synced, so it doesn't clutter a steady panel. Flows to /diag too.
        core.recentSyncEvents().takeIf { it.isNotEmpty() }?.let { extras["Local-state sync"] = it.joinToString(" · ") }
        return PanelInfo.collect(this, extras, profile)
    }

    private fun ledLabel(rgbLedReady: Boolean): String {
        if (!rgbLedReady) return "none"
        return when {
        controls.led is Rk3576LedController -> "Rockchip /dev/ledjni (RGB)"
        controls.led is SocketLedController && profile.ledMechanism == LedMechanism.RK3576_IOCTL_DAEMON -> "Rockchip /dev/ledjni helper daemon (RGB)"
        controls.led is SocketLedController && profile.ledMechanism == LedMechanism.SYSFS_DAEMON -> "sysfs helper daemon (RGB)"
        controls.led is SocketLedController -> "helper daemon (RGB)"
        controls.led.colorCapable() -> "RGB"
        else -> "brightness"
        }
    }

    /** "no", or "yes" with any declared technology + runtime value-type/range appended ("yes · Infrared ·
     *  Binary · near/far (0 / 5 cm)"). */
    private fun sensorRow(present: Boolean, tech: String?, desc: String?): String =
        if (!present) "no" else "yes" + listOfNotNull(tech, desc).joinToString("") { " · $it" }

    /**
     * Whether Android enumerates any camera on this board.
     *
     * Sticky in BOTH directions, unlike the zigbee probe: camera hardware does not appear or vanish at
     * runtime, so an empty list is a definitive answer rather than a failed probe. Only a throw is
     * retried, which is the camera service not yet being up early in boot. That keeps the privacy
     * contract's "idle cost is zero" promise — a panel without a camera enumerates once, not forever.
     *
     * The acceptance rule is exactly the one the session's own `chooseCamera` applies: any enumerated
     * camera counts, because that function prefers a front-facing lens but never filters on it. So the
     * panel cannot offer a camera that the session would then refuse to open as `no_camera_id`.
     */
    private val cameraPresence = SuccessStickyProbe(
        probe = { getSystemService(CameraManager::class.java)?.cameraIdList?.isNotEmpty() },
        initialBackoffMs = 5_000L,
        maxBackoffMs = 300_000L,
    )

    /** The profile's declaration, or what Android enumerates when the profile is silent. */
    private fun cameraPresent(): Boolean = cameraReason().capable

    /**
     * Why this panel does or does not offer a camera, from the same two inputs as the capability. This is
     * the only place either input is read, so the answer the panel acts on and the answer it shows a user
     * are the same answer.
     */
    private fun cameraReason(): CameraCapabilityReason =
        cameraCapabilityReason(profile.cameraDeclared, cameraPresence.get())

    private val zigbeePresence = SuccessStickyProbe(
        probe = {
            controls.zigbee.observe(includeRole = false)
                .takeIf { it.probeSucceeded && it.present }
                ?.let { true }
        },
        initialBackoffMs = 5_000L,
        maxBackoffMs = 300_000L,
    )

    /** This panel's capability snapshot for the settings registry's availableWhen gates
     *  (the Configure form/schema + the dashboard's read-only values card). */
    private fun capabilitiesSnapshot(): Capabilities {
        val privilege = observeTypedShellCapability(
            directSuProbe = Su::available,
            helperRootProbe = HelperClient::available,
        )
        return capabilitiesSnapshot(
            directSuReady = privilege.directSuReady,
            typedShellControlReady = privilege.typedShellControlReady,
            controllers = null,
        )
    }

    private fun capabilitiesSnapshot(
        privilege: PrivilegedRouteObservation,
        controllers: ManagementControllerObservation,
    ): Capabilities = capabilitiesSnapshot(
        directSuReady = privilege.directSuReady,
        typedShellControlReady = privilege.rootControlReady,
        controllers = controllers,
    )

    private fun capabilitiesSnapshot(
        directSuReady: Boolean,
        typedShellControlReady: Boolean,
        controllers: ManagementControllerObservation?,
    ): Capabilities {
        val cost = FeatureCosts.registry.span(FeatureCostOperation.CAPABILITY_SNAPSHOT)
        return try {
            val wifi = wifiDiagnostics.snapshot(
                WifiDiagnosticDemand(ssid = true, rssi = true, privilegedRoute = directSuReady),
            )
            val wifiAvailable = wifi.availability()
            Capabilities(
                hasProximity = sensors.hasProximity(),
                hasLearnedProximity = sensors.hasLearnedProximity(),
                hasLight = sensors.lightChannel() == true,
                hasTemperature = sensors.hasTemperature(),
                hasHumidity = sensors.hasHumidity(),
                hasWifi = wifiAvailable.rssi,
                hasWifiSsid = wifiAvailable.ssid,
                hasCht8305 = profile.hasCht8305,
                hasCamera = cameraPresent(),
                microphone = voiceAudio.microphonePresence(),
                appCanSu = profile.appCanSu,
                hasRecents = profile.hasRecents,
                // Declared physical buttons. Populated because a capability whose value is always its
                // `false` default is indistinguishable from "this panel has none", and the navbar
                // no-way-out default reads it as evidence that a panel has no navigation affordance.
                hasEvdevButtons = profile.evdevButtons.isNotEmpty(),
                // Carried so a consumer can tell a real declaration from an unpopulated `false`.
                hardwareDeclarationsKnown = profile.declarationsFromCatalog,
                // Same declaration/probe authority used by stored-mode reads and command admission.
                hasNativeNavbar = config.hasNativeNavbar,
                hasAndroidStatusBar = config.hasAndroidStatusBar,
                cpuGovernors = controllers?.cpuGovernorsAvailable ?: controls.cpu.available(),
                // AdbController.available() is exactly Su.available(); reuse this snapshot's one root
                // authority probe rather than opening another shell transaction.
                networkAdb = directSuReady,
                zigbeePresent = controllers?.let {
                    zigbeeCapabilityPresent(
                        declaredGateway = profile.zigbeeGatewayDir != null,
                        observation = it.zigbee,
                    )
                } ?: (profile.zigbeeGatewayDir != null && zigbeePresence.get() == true),
                relays = controllers?.relayCount ?: controls.relay.count(),
                buttonLeds = controllers?.buttonLedCount ?: controls.relay.ledCount(),
                hasSystemDarkMode = Build.VERSION.SDK_INT >= 29,   // Android 10+ has the system dark/light setting
                hasSpeaker = profile.hasSpeaker,
                canInstallVerifiedApps = typedShellControlReady,
                canCaptureAndInput = typedShellControlReady,
                canSetDisplay = typedShellControlReady,
                canControlBootChime = typedShellControlReady,
            ).also { cost.work(units = 1) }
        } catch (e: Exception) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            throw e
        } finally {
            cost.close()
        }
    }

    /** The resolved renderer-target snapshot for perf smoothness attribution (the built-in sentinel, a
     *  foreign dashboard package, or none), resolved here off the sampling path and handed to PerfReader
     *  as an immutable value so PerfReader never touches PackageManager on the hot path. */
    private fun rendererTargetSnapshot(): RendererTarget? =
        RendererResolver.resolveControlTarget(config.dashboardPackage)

    /** Advertise the button-event entity only if our a11y service is actually enabled. */
    private fun accessibilityEnabled(): Boolean {
        return PanelPermissionRepair.accessibilityHeld(
            packageName,
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1,
        )
    }

    /** Whether SYSTEM_ALERT_WINDOW is held — required to draw the soft-navbar overlay. */
    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (guardDbRedirect) return START_NOT_STICKY
        // Start subsystems once. Android re-delivers onStartCommand on every startForegroundService()
        // re-issue and on START_STICKY re-create; re-running this block would call server.start() again,
        // binding a second Ktor server on :8888 -> BindException crashes the process (and would also
        // double-start mdns/sensors). started is reset in onDestroy so a genuine restart re-inits.
        // A stood-down generation has no owner to start. START_STICKY is the point of keeping it: the
        // live started-service record is what Android recreates once the committed process has exited.
        if (standingDown) return START_STICKY
        if (!::runtime.isInitialized) {
            pendingStart = true
            return START_STICKY
        }
        return startInitializedRuntime()
    }

    private fun startInitializedRuntime(): Int {
        if (started) return START_STICKY
        started = true
        val startupActivationGeneration = profileActivationGeneration
        var initialNativeDemand: PanelAssistantTransportDemand? = null
        val startup = runtime.start(block = runtimeStart@{ activeRuntime ->
            // Initialization already waited for the prior service's teardown before repairing grants
            // and constructing feature owners. Recheck admission before starting those owners.
            if (teardownBoundary.isStopping) return@runtimeStart

            // Everything below can start work, write hardware state, attach a process-global owner, or
            // create an overlay. Keep all of it behind the predecessor fence, not merely HTTP start.
            // Publish each holder before binding can announce its first state to a live renderer.
            haLinks.installRuntimeHolders()
            scope.launch { AppInstaller.deleteRetiredWebViewRollback(filesDir) }
            io.panelassistant.android.camera.CameraPermissionPrompt.install(
                object : io.panelassistant.android.camera.CameraPermissionPrompt.Store {
                    override var declined: Boolean
                        get() = config.cameraPermissionDeclined
                        set(value) { config.cameraPermissionDeclined = value }
                },
            )
            voiceAudio.start()
            storageHealth.startStorageHealthChecks()
            reconcileHelperInstallStaging()
            displayPower.registerBrightnessPreferenceObserver()
            controls.startUsbDebuggingFeedback()
            displayPower.startAdaptive()
            EntityLearningRuntime.attach(entityLearning)
            componentOperations.attachSoftwareUpdateObservers()
            val kioskRecoveredAtStartup = controls.recoverKioskAtStartup()
            if (teardownBoundary.isStopping) return@runtimeStart
            controls.reassertFeedback()
            sensors.prepare()
            approvalPrompt = ApprovalPrompt(
                LocalApprovalBroker.instance,
                wake = { displayPower.screen.ensureOn() },
                launch = controls.system::launchApprovalPrompt,
                post = { raise -> scope.launch { raise() } },
            )
            proximityWizard = ProximityWizardCoordinator(
                startSession = sensors::startProximityCalibration,
                active = sensors::proximityCalibrationActive,
                status = sensors::proximityJson,
                visible = sensors::proximityCalibrationVisible,
                localAction = sensors::proximityCalibrationAction,
                cancel = sensors::cancelProximitySession,
                heartbeat = sensors::proximityCalibrationHeartbeat,
                reset = sensors::resetProximityToProfile,
                acquireDisplay = displayPower.screen::acquireVisibleHold,
                releaseDisplay = displayPower.screen::releaseVisibleHold,
                launch = controls.system::launchProximityWizard,
                narrator = ProximityWizardNarrator(speak = { text, localeTag, onGeneration ->
                    io.panelassistant.android.assist.AssistPipelineClient(config).speakText(
                        text,
                        localeTag,
                        io.panelassistant.android.assist.AnnouncementLanePlayback(
                            voiceAudio.audio,
                            onGeneration = onGeneration,
                        ),
                    )
                }, stopPlayback = { generation -> voiceAudio.audio.cancelGeneration(generation) }),
            )
            val startupCompanionPackage = CompanionInstaller.installedPkg(this)
                ?: controls.system.resolveDashboard(config.dashboardPackage)
            restoreCompanionLaunchSuppression(
                packageName = startupCompanionPackage,
                operationState = companionDataOperationState,
                operationStatus = HelperClient::companionOperationStatus,
                retain = { lease ->
                    scope.launch(Dispatchers.IO) {
                        retainCompanionLeaseUntilHelperIdle(
                            lease = lease,
                            operationState = companionDataOperationState,
                            operationStatus = HelperClient::companionOperationStatus,
                            pollMs = COMPANION_STARTUP_STATUS_POLL_MS,
                            afterRelease = {
                                if (!teardownBoundary.isStopping) {
                                    runCatching {
                                        rendererPreparation.reconcileStartup(
                                            ensureHome = { pkg, ready ->
                                                controls.system.applyLauncherHomePolicy(config.launcherPackage, pkg, ready)
                                            },
                                            launchHome = controls.system::launchHome,
                                        )
                                    }.onFailure {
                                        Log.w(TAG, "Companion-safe startup renderer retry failed", it)
                                    }
                                }
                            },
                        )
                    }
                },
            )
            // Learning resolves the HA instance identity through this runtime. Start mDNS before either
            // the initial learner sync or a borrowed renderer commit can notify the learner of a target.
            val rendererResult = prepareEntityLearningStartup(
                startMdns = activeRuntime.mdns::start,
                reconcileRenderer = {
                    // Re-assert the configured HOME and repair an interrupted switch to the built-in renderer
                    // before the HTTP surface can accept another configuration transaction. The durable retry
                    // condition is built-in + blank URL; borrowed connection and zoom commit atomically.
                    rendererPreparation.reconcileStartup(
                        ensureHome = { pkg, ready ->
                            controls.system.applyLauncherHomePolicy(config.launcherPackage, pkg, ready)
                        },
                        launchHome = { pkg -> controls.system.launchHome(pkg) },
                    )
                },
                // Initialization touches SQLite; the service scope already owns background work.
                startLearning = {
                    scope.launch(Dispatchers.IO) {
                        if (!teardownBoundary.isStopping) entityLearning.start()
                    }
                },
            )
            if (rendererResult == RendererPreparationCoordinator.Result.PERSIST_FAILED) {
                Log.e(TAG, "built-in renderer startup preparation did not commit; leaving it retryable")
            }
            if (rendererResult == RendererPreparationCoordinator.Result.CLOSED) return@runtimeStart
            // Resolve "Open in HA" from the prepared built-in renderer session.
            activeRuntime.core.maybeResolveHaLink()
            displayPower.applyKeepAwake()
            io.panelassistant.android.http.PerfReader.start(scope, packageName, rendererTargetSnapshot())
            server.start()
            // Startup always reconciles both additions and removals from durable desired state against
            // the write-ahead overlay ownership markers. This completes work handed off by profile restart.
            server.requestTameReconcile()
            activeRuntime.core.start()
            displayPower.startAutoSleep()
            controls.startZigbeeHealth()
            liveSettingAuthority.replayKeysObserved(PanelCommandDispatcher.APPLY_SETTING_KEYS) { key, value, previous, fence ->
                replayLiveSettingObserved(activeRuntime.commands, key, value, previous, fence)
            }
            // Forward our own logcat to the configured aggregator (no-op unless a sink host is set).
            logShipper.start()
            // A failed queued mode stays authoritative. Otherwise restore durable intent through the
            // acknowledged command path, so a failed overlay attachment remains pending for retry.
            val restoredNavbarMode = config.navbarMode
            liveSettingAuthority.applyOrQueueOutcomeIf(
                key = "navbar_mode",
                value = restoredNavbarMode,
                previousValue = restoredNavbarMode,
                expected = {
                    config.navbarMode == restoredNavbarMode &&
                        "navbar_mode" !in liveSettingAuthority.pendingSnapshot()
                },
            ) { key, value, previous -> applyLiveSettingObserved(activeRuntime.commands, key, value, previous) }
            scheduleLiveSettingRetries(PanelCommandDispatcher.APPLY_SETTING_KEYS)
            controls.startPolicies(kioskRecoveredAtStartup)
            receivers.startScreenOnReconciliation()
            controls.startVendorService()
            receivers.startWebViewRebindWatch()
            sensors.start(
                onLux = { lux ->
                    submitIlluminanceIfExposed(
                        exposed = config.haExposed("illuminance", true),
                        lux = lux,
                        submit = lightPublisher::submit,
                    )
                },
                onLuxRaw = displayPower.autoBright::submitLux,
                onProximity = { near, level, reportMask ->
                    core.publishProximity(near, level, reportMask)
                    displayPower.autoSleep.noteProximityState(near.takeIf {
                        !sensors.proximityCalibrationActive() && sensors.proximityPresenceReady()
                    })
                },
                onPresenceApproach = {
                    val proximityGeneration = sensors.proximityGeneration()
                    val observedAt = android.os.SystemClock.elapsedRealtime()
                    displayPower.wakeOnWaveWorker.execute {
                        displayPower.screen.brightenForPresence {
                            !teardownBoundary.isStopping && sensors.proximityPresenceNear() &&
                                sensors.proximityGeneration() == proximityGeneration &&
                                android.os.SystemClock.elapsedRealtime() - observedAt in 0L..750L
                        }
                    }
                },
                onGesture = gesture@{
                    val token = sensors.proximityGestureToken()
                    if (!config.wakeOnWave || !sensors.proximityReady()) {
                        sensors.completeProximityGesture(token, false)
                        return@gesture
                    }
                    val proximityGeneration = sensors.proximityGeneration()
                    val generation = displayPower.screen.currentOffGeneration()
                    if (generation == null) {
                        sensors.completeProximityGesture(token, false)
                        return@gesture
                    }
                    val settingGeneration = config.wakeOnWaveGeneration
                    val submitted = displayPower.wakeOnWaveWorker.execute {
                        var accepted = false
                        try {
                            if (
                                teardownBoundary.isStopping || !config.wakeOnWave || !sensors.proximityReady() ||
                                sensors.proximityGeneration() != proximityGeneration ||
                                config.wakeOnWaveGeneration != settingGeneration
                            ) return@execute
                            accepted = displayPower.screen.wakeIfStillDark(generation) {
                                !teardownBoundary.isStopping && config.wakeOnWave && sensors.proximityReady() &&
                                    sensors.proximityGeneration() == proximityGeneration &&
                                    config.wakeOnWaveGeneration == settingGeneration
                            } == WakeOutcome.WOKEN
                            if (accepted && !teardownBoundary.isStopping) core.publishScreenOn()
                        } finally { sensors.completeProximityGesture(token, accepted) }
                    }
                    if (!submitted) sensors.completeProximityGesture(token, false)
                },
                onTemperature = { c -> core.publishTemperature(c) },
                onHumidity = { h -> core.publishHumidity(h) },
            )
            // Stream daemon-instrumented hardware buttons into the same event entity as a11y capture.
            // It starts and stops with this runtime so a recreated service cannot inherit a blocked reader.
            EvdevButtonClient.start(profile.evdevButtons)
            scope.periodic(
                intervalMs = 24 * 3_600 * 1_000L,
                initialDelayMs = 30_000L, // let startup settle before hitting the network
                tag = TAG,
                name = "update-check",
            ) {
                // Refresh release observations only; installation requires an explicit request.
                runCatching {
                    UpdateChecker.check(
                        this@PaneldService,
                        "stable",
                        profile.companionMaxVersion,
                    )
                }
            }
            startStateObservationTick()
            // Never-blank-screen watchdog. A screen-off kills the backlight but leaves the device
            // interactive, and nothing re-lights it — so a stray or stale screen-off command
            // or a firmware idle-dim can leave the panel dark and apparently bricked. If the screen
            // is dark but ha-paneld did NOT deliberately turn it off, re-light it; a user-intended
            // "screen off" (isIntendedOff) is left alone.
            scope.periodic(
                intervalMs = SCREEN_WATCHDOG_MS,
                initialDelayMs = 15_000L, // let boot settle before the first check
                tag = TAG,
                name = "never-blank",
            ) {
                displayPower.relightIfUnexpectedlyDark()
                controls.repairAdminHomeFromCapturedRoute()
            }
            // The HTTP surface, network owner, sensors, evdev and periodic safety owners have all been
            // constructed successfully. Only now may a pending profile replace the previous LKG target.
            profileActivationGeneration?.let { generation ->
                if (profileRegistry.markActivationHealthy(generation)) {
                    Log.i(TAG, "profile activation generation $generation is healthy")
                    profileActivationGeneration = null
                } else {
                    Log.w(TAG, "profile activation generation $generation could not be marked healthy")
                    throw IllegalStateException("profile activation health state could not be persisted")
                }
            }
            if (profileActivationGeneration == null) {
                if (!profileRegistry.markResolvedStartupHealthy()) {
                    Log.w(TAG, "could not persist the healthy profile revision snapshot")
                }
            }
            // Best-effort observation belongs after the required control plane and every active owner.
            // It must not contend with helper migration, live-setting replay, ADB reassertion or profile
            // activation proof. This uses the existing service scope and owns no retry/lifecycle.
            scope.launch(Dispatchers.IO) {
                server.prewarm()
            }
            controls.warmUpRelay()
            initialNativeDemand = haLinks.currentPanelAssistantTransportDemand()
        }, complete = {
            // Demand construction reads configuration before this short, lock-admitted publication.
            haLinks.publishDemand(initialNativeDemand)
        })
        Thread({
            val disposition = awaitServiceStartup(startup, startupActivationGeneration)
            Log.i(TAG, "service startup health: $disposition, stopping=${teardownBoundary.isStopping}")
            when (disposition) {
                ServiceStartupDisposition.RUNNING -> {
                    // DHCP may have arrived while ServiceRuntimeOwner was STARTING, when callbacks cannot
                    // borrow a runtime observation. Replay their latest topology after RUNNING is published.
                    mdnsRuntimeReconciler.runtimeRunning()
                    startupRecoveryPrefs.edit().clear().commit()
                    updateForegroundStatus(nativeString(R.string.listening_on_port, config.httpPort))
                }
                ServiceStartupDisposition.PROFILE_ACTIVATION_ROLLBACK -> {
                    updateForegroundStatus(nativeString(R.string.degraded_profile_startup))
                    Log.e(TAG, "profile activation startup failed; scheduling rollback restart")
                    profileRestart.request()
                }
                ServiceStartupDisposition.DEGRADED -> {
                    updateForegroundStatus(nativeString(R.string.degraded_startup))
                    val now = android.os.SystemClock.elapsedRealtime()
                    val decision = startupRecoveryDecision(
                        startupRecoveryPrefs.getInt("attempts", 0),
                        startupRecoveryPrefs.getLong("last_at", 0L),
                        now,
                    )
                    startupRecoveryPrefs.edit()
                        .putInt("attempts", decision.nextAttempt)
                        .putLong("last_at", now)
                        .commit()
                    if (decision.restart && recoveryRestart.request()) {
                        Log.e(TAG, "service startup failed; scheduling bounded recovery restart ${decision.nextAttempt}/3")
                    } else {
                        Log.e(TAG, "service startup failed; recovery restart limit reached, remaining degraded")
                    }
                }
            }
        }, "service-startup-health").apply { isDaemon = true; start() }
        registerNetworkCallback()
        return START_STICKY
    }

    private fun revalidateMdns(
        observed: ServiceRuntimeOwner.Observation<NetworkRuntime>,
        addresses: MdnsLanAddresses?,
    ) {
        // One dedicated latest-value slot prevents callback bursts from saturating the workers.
        mdnsRevalidation.submit(MdnsRevalidation(observed, addresses))
    }

    /** Temporary (privacy) IPv6 addresses are marked so the advertiser can prefer a stable one. */
    private fun mdnsNetworkChanged(linkAddresses: List<android.net.LinkAddress>) {
        mdnsRuntimeReconciler.networkChanged(
            linkAddresses.map { it.address },
            linkAddresses.filter { it.flags and android.system.OsConstants.IFA_F_TEMPORARY != 0 }
                .mapTo(mutableSetOf()) { it.address },
        )
    }

    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var defaultNetwork: Network? = null
            private val wifiAdmission = WifiDiagnosticAdmissionTracker()

            private fun observeTransport(network: Network, capabilities: NetworkCapabilities?) {
                if (network != defaultNetwork) return
                if (!wifiAdmission.changed(wifiDiagnostics.admission(capabilities))) return
                wifiDiagnostics.invalidate()
                if (::server.isInitialized) server.invalidateCapabilitySnapshot()
            }

            override fun onAvailable(network: Network) {
                defaultNetwork = network
                val capabilities = cm.getNetworkCapabilities(network)
                // Identity only: the outage tracker is told WHICH network arrived, never what this
                // synchronous snapshot thinks its transport is. That snapshot can predate the
                // authoritative onCapabilitiesChanged, and crediting an episode from it invents or
                // discards outages on stale information.
                wifiOutageTracker.onDefaultAvailable(network.hashCode().toLong())
                if (::haLinks.isInitialized) haLinks.panelAssistantTransport.nudge()
                observeTransport(network, capabilities)
                mdnsNetworkChanged(cm.getLinkProperties(network)?.linkAddresses.orEmpty())
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (network != defaultNetwork) return
                mdnsNetworkChanged(linkProperties.linkAddresses)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network == defaultNetwork) {
                    wifiOutageTracker.onTransportChanged(
                        network.hashCode().toLong(),
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                    )
                }
                observeTransport(network, capabilities)
            }

            override fun onLost(network: Network) {
                if (network != defaultNetwork) return
                // Behind the identity guard, so a make-before-break handover never reads as an outage.
                wifiOutageTracker.onDefaultLost()
                observeTransport(network, null)
                mdnsRuntimeReconciler.networkLost()
                defaultNetwork = null
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { netCallback = cb }
    }

    private fun onStorageHealthSnapshot(snapshot: StorageHealthSnapshot) {
        if (teardownBoundary.isStopping) return
        server.invalidateStorageHealthDiagnostics()
        notifications.updateStorageHealthNotification(snapshot)
        runtime.observe()?.value?.core?.publishStorageHealth()
    }

    /**
     * Re-assert the database's view of locale and night mode, now that the service has promoted.
     *
     * `Application.onCreate` deliberately reads both from the pre-database settings file, because
     * opening the database there spends the foreground-start deadline (see the comment in
     * `HaPaneldApp.onCreate`). The database stays authoritative — a failed copy leaves that file
     * stale — so the correction happens here instead, where it is off the deadline and
     * still before any ha-paneld screen is rendered.
     */
    private fun reconcileNativePresentationAfterPromotion() {
        runCatching {
            NativeLocale.apply(config.uiLanguage)
            if (Build.VERSION.SDK_INT < 29) {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    if (config.darkMode) {
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    } else {
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    },
                )
            }
        }.onFailure { Log.w(TAG, "could not reconcile native locale/night mode from the database", it) }
    }

    private fun updateForegroundStatus(statusText: String) {
        if (teardownBoundary.isStopping) return
        notifications.updateForegroundStatus(statusText, config.silenceBootChime)
    }

    /**
     * Hold Android's started-service record without owning anything until the committed process exits.
     *
     * Deliberately no stopSelf(): that live record is exactly what makes Android recreate this service
     * in the fresh process, which is where the staged activation is claimed and proved. The notification
     * is rewritten without touching config, which this generation never constructed.
     */
    private fun standDownForCommittedProcessBoundary() {
        standingDown = true
        Log.i(TAG, "service generation created inside a committed process boundary; standing down until the process exits")
        notifications.describeStandingDown(nativeString(R.string.starting))
    }

    /**
     * Close the synchronous admission gates shared by ordinary destruction and an explicit process
     * boundary. Each owner is guarded because a requested boundary can arrive during startup, before
     * every controller exists; normal destruction has already initialized all of them.
     */
    private fun closeServiceAdmissions() {
        approvalPrompt?.close()
        proximityWizard?.close()
        voiceAudio.closeAdmission()
        controls.closeAdmissions()
        displayPower.closeAdmission()
        if (::camera.isInitialized) camera.closeAdmission()
    }

    override fun onDestroy() {
        teardownBoundary.markStopping()
        controls.closeUsbDebuggingFeedback()
        notifications.releaseForeground()
        portalStartupOverlayDismissal?.close()
        if (guardDbRedirect) {
            stopForeground(true)
            super.onDestroy()
            return
        }
        // A stood-down generation constructed no controller, holds no restart lease and armed no
        // boundary, so every wait, drain and proof below would be about another generation's owners.
        if (standingDown) {
            super.onDestroy()
            return
        }
        if (!::runtime.isInitialized) {
            startupInitialization?.invokeOnCompletion { restartLease.completeTeardown() }
            startupInitialization?.cancel()
            scope.cancel()
            stopForeground(true)
            super.onDestroy()
            return
        }
        // Android invokes this on the main thread. All deliberate waits below consume one deadline so
        // individually safe phase timeouts cannot accumulate into an input-dispatch ANR.
        val teardownDeadline = MonotonicDeadline(SERVICE_DESTROY_BUDGET_MS)
        // App-local completion may continue off-main, but every subsidiary owner shares this one budget.
        // External display/system-policy recovery remains mandatory even after this deadline expires.
        val asyncTeardownDeadline = MonotonicDeadline(ASYNC_TEARDOWN_BUDGET_MS)
        // Bind any armed request to this exact lifecycle generation. A finalizer that was already in
        // flight before PREPARE cannot later satisfy or cancel the new request.
        upgradeShutdownClaim = UpgradeShutdownCoordinator.claimShutdown()
        storageHealth.close()
        lightPublisher.close()
        displayPower.closeObservers()
        receivers.releaseServiceReceivers()
        closeServiceAdmissions()
        // A deliberately dark panel is the only external state that becomes unrecoverable if Android
        // kills this process after onDestroy returns (the touch-wake overlay dies with it). Give its
        // dedicated recovery owner first use of the aggregate deadline, then let it keep retrying while
        // all slower server/network teardown proceeds off-main.
        val screenExitRecovery = displayPower.ensureScreenExitRecovery()
        val screenRecoveryWaitMs = teardownDeadline.remainingMs()
        if (screenRecoveryWaitMs > 0L) {
            runCatching { screenExitRecovery.get(screenRecoveryWaitMs, TimeUnit.MILLISECONDS) }
        }
        started = false
        netCallback?.let { cb ->
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(cb) }
            netCallback = null
        }
        if (!mdnsRevalidation.closeAndJoin(teardownDeadline.remainingMs())) {
            Log.w(TAG, "mDNS revalidation worker did not drain before service teardown")
        }
        stateObservationTick?.stop(teardownDeadline.remainingMs())
        // The existing runtime lane owns latest configuration work. Close its one admission point now;
        // the sticky shutdown queued below becomes the sole proof that any active mutation drained.
        runtime.closeAdmission()
        FeatureCosts.registry.setBacklog(FeatureCostOperation.NETWORK_RECONFIGURE, 0)
        // Close renderer transaction admission before waiting for the runtime lane. If startup has not
        // reconciled yet it exits early; if it has progressed further, final runtime cleanup below owns
        // every resource it may still open, including HTTP.
        val rendererDrained = if (!::rendererPreparation.isInitialized) true
            else rendererPreparation.close(teardownDeadline.remainingMs())
        if (!rendererDrained) {
            Log.w(TAG, "renderer transaction did not become idle before the service teardown deadline")
        }
        val httpOwnersStopped = AtomicBoolean(!::server.isInitialized)
        val audioDrained = AtomicBoolean(false)
        val ownerCleanup = ServiceOwnerCleanupTracker()
        val mdnsRetirement = AtomicReference<CompletableFuture<Boolean>?>(null)
        val sensorPersistenceClosed = AtomicReference<Future<Unit>?>(null)
        val stopped = runtime.shutdown(teardownDeadline.remainingMs()) { activeRuntime ->
            fun closeOwner(name: String, close: () -> Unit) {
                ownerCleanup.run(close)?.let { error ->
                    Log.w(TAG, "$name cleanup failed", error)
                }
            }
            fun closeOwnerResult(name: String, close: () -> Boolean) {
                val result = runCatching(close).onFailure { error ->
                    Log.w(TAG, "$name cleanup failed", error)
                }.getOrDefault(false)
                ownerCleanup.record(result)
                if (!result) Log.w(TAG, "$name cleanup did not complete")
            }
            // This cleanup is queued behind any in-flight startup/reconfigure. Closing HTTP here means
            // startup cannot bind a fresh listener after an earlier out-of-band stop has already run.
            // It is deliberately first: the LAN CDP relay is root-owned and survives app process death.
            httpOwnersStopped.set(
                if (!::server.isInitialized) true else runCatching { server.stop() }
                    .onFailure { Log.w(TAG, "HTTP cleanup failed", it) }
                    .getOrDefault(false),
            )
            // The physical-wake reconciliation publishes INTO the network runtime, so it must be provably
            // terminal before that runtime is retired. Its receiver is already unregistered, so no new
            // work can be posted; join the worker so a reconciliation already in flight cannot publish
            // through a retired owner. Ordered before the retirement fence below for exactly that reason.
            closeOwner("update entity observers") { componentOperations.detachSoftwareUpdateObservers() }
            receivers.closeScreenWakeWorker(::closeOwnerResult, asyncTeardownDeadline)
            // Commands and convergence observers can use the service-owned hardware controllers.
            // Latest live refresh/replacement work has already drained on this runtime lane. Prove the
            // remaining mutation owners terminal before dismantling any dependent controller. mDNS is
            // fenced at the same time, but no controller depends on it and its JmDNS goodbye
            // announcements take seconds, so the finalizer proves it before any successor starts.
            mdnsRetirement.set(activeRuntime.mdns.retire(asyncTeardownDeadline))
            val stateDrained = activeRuntime.commands.stop(asyncTeardownDeadline) and
                activeRuntime.core.stop(asyncTeardownDeadline)
            val lightPublisherDrained = lightPublisher.awaitTermination(asyncTeardownDeadline.remainingMs())
            if (!stateDrained || !lightPublisherDrained) {
                ownerCleanup.record(false)
                error(
                    "network mutation owners did not drain before hardware teardown " +
                        "(state=$stateDrained light=$lightPublisherDrained)",
                )
            }

            controls.closePolicies(::closeOwner)
            if (::entityLearning.isInitialized) EntityLearningRuntime.detach(entityLearning)
            voiceAudio.closeAudio(asyncTeardownDeadline, audioDrained)
            // The publication target and command ingress are now terminal; drain the remaining
            // producers and hardware owners in dependency order.
            controls.stopZigbeeHealth(::closeOwnerResult)
            closeOwner("evdev") { EvdevButtonClient.stop() }
            displayPower.closeHaAmbientAndSleep(::closeOwner, ::closeOwnerResult, asyncTeardownDeadline)
            voiceAudio.closeVoice(::closeOwnerResult, asyncTeardownDeadline)
            if (::haLinks.isInitialized) haLinks.close(::closeOwner)
            closeOwner("sensors") { sensorPersistenceClosed.set(sensors.stop()) }
            displayPower.closeAdaptive(::closeOwnerResult, asyncTeardownDeadline)
            controls.stopWatchdog(::closeOwner)
            // The stream transport before the camera: every client is disconnected and its lease
            // returned before the owner tears the session down, and no new client can reattach.
            if (::cameraStream.isInitialized) closeOwner("camera stream") { cameraStream.stop() }
            voiceAudio.closeSharedMicrophone(::closeOwnerResult, asyncTeardownDeadline)
            // Before the LED: a session may hold it for indication and gives it back on close.
            if (::camera.isInitialized) {
                closeOwnerResult("camera") {
                    runCatching { camera.stop().get(CAMERA_STOP_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }.isSuccess
                }
            }
            controls.closeLedEffect(::closeOwner)
            displayPower.closeWakeAndPower(::closeOwnerResult, asyncTeardownDeadline)
            closeOwner("log shipper") { logShipper.stop() }
            closeOwner("performance reader") { io.panelassistant.android.http.PerfReader.stop() }
            closeOwner("app log capture") { logCaptureApp.close() }
            closeOwner("system log capture") { logCaptureSystem.close() }
            closeOwner("webview console capture") { logCaptureWebView.close() }
        }
        if (!stopped) Log.w(TAG, "runtime teardown exceeded the service deadline; cleanup continues on its owner thread")
        if (!httpOwnersStopped.get()) Log.w(TAG, "HTTP owners did not stop cleanly before learner teardown")

        // Startup and all runtime producers are now terminal before their shared coroutine scope and
        // SQLite-backed learner disappear. On a timeout, deliberately leave the store open for process
        // teardown rather than racing late runtime cleanup.
        val root = scope.coroutineContext[Job]
        scope.cancel()
        val scopeWaitMs = teardownDeadline.remainingMs()
        val scopeDrained = when {
            root == null -> true
            scopeWaitMs <= 0L -> root.isCompleted
            else -> runBlocking {
                withTimeoutOrNull(scopeWaitMs) { root.join(); true } ?: false
            }
        }
        if (!scopeDrained) Log.w(TAG, "service jobs did not drain before the service teardown deadline")
        if (::entityLearning.isInitialized && !(stopped && httpOwnersStopped.get() && rendererDrained && scopeDrained)) {
            Log.w(TAG, "entity-learning store remains open until asynchronous teardown drains its producers")
        }
        val stateFlushMs = teardownDeadline.remainingMs()
        if (stateFlushMs <= 0L || !AppState.flush(this, stateFlushMs)) {
            Log.w(TAG, "application-state writes did not drain before the service teardown deadline")
        }
        // The main-thread flush is best effort only: timed-out runtime cleanup can admit sensor writes
        // later. The finalizer always flushes again after every producer and its persistence future drain.
        finishTeardownAsync(
            asyncTeardownDeadline,
            root,
            httpOwnersStopped,
            rendererDrained,
            audioDrained,
            ownerCleanup,
            mdnsRetirement,
            sensorPersistenceClosed,
        )
        // stopSelf() removes START_STICKY. Re-arm only an app-internal profile/WebView/recovery
        // boundary while the old predecessor barrier remains closed. A provisioner stop never sets
        // this flag. Any successor onCreate work completes before the finalizer's state fence/proof.
        if (restartAfterInternalBoundary.get()) {
            runCatching { PaneldService.start(this) }
                .onFailure { Log.e(TAG, "could not retain service start across process boundary", it) }
        }
        super.onDestroy()
    }

    /**
     * Finish work that exceeded Android's main-thread lifecycle budget. A completely proved ordinary
     * stop opens the next same-process lease; an explicit or incomplete teardown restores external state
     * and exits so a replacement cannot inherit ambiguous owners.
     */
    private fun finishTeardownAsync(
        finalizerDeadline: MonotonicDeadline,
        root: Job?,
        httpOwnersStopped: AtomicBoolean,
        rendererAlreadyDrained: Boolean,
        audioDrained: AtomicBoolean,
        ownerCleanup: ServiceOwnerCleanupTracker,
        mdnsRetirement: AtomicReference<CompletableFuture<Boolean>?>,
        sensorPersistenceClosed: AtomicReference<Future<Unit>?>,
    ) {
        Thread {
            var shutdownFreeze: StateQuiescence? = null
            try {
                var rendererDrained = rendererAlreadyDrained
                while (!rendererDrained) {
                    val waitMs = minOf(finalizerDeadline.remainingMs(), ASYNC_TEARDOWN_WAIT_MS)
                    if (waitMs <= 0L) return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "renderer transaction remained active",
                        shutdownFreeze = shutdownFreeze,
                    )
                    rendererDrained = rendererPreparation.close(waitMs)
                }
                while (!runtime.isStopped()) {
                    val waitMs = minOf(finalizerDeadline.remainingMs(), ASYNC_TEARDOWN_WAIT_MS)
                    if (waitMs <= 0L) return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "runtime cleanup did not finish",
                        shutdownFreeze = shutdownFreeze,
                    )
                    val shutdownSucceeded = runtime.shutdown(waitMs) {}
                    if (!shutdownSucceeded && !ownerCleanup.isComplete()) {
                        return@Thread finishTeardownAfterExternalStateIsSafe(
                            completed = false,
                            reason = "network mutation owners remained active",
                            shutdownFreeze = shutdownFreeze,
                        )
                    }
                    if (!shutdownSucceeded && runtime.hasFailedShutdown()) {
                        return@Thread finishTeardownAfterExternalStateIsSafe(
                            completed = false,
                            reason = "runtime cleanup failed",
                            shutdownFreeze = shutdownFreeze,
                        )
                    }
                }
                if (!ownerCleanup.isComplete()) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "one or more runtime owners did not clean up",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                if (!audioDrained.get()) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "audio cleanup did not drain",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                // A successor advertises the same instance, so this responder's retirement is proved before
                // any successor lease opens, as reconfiguration proves it before a replacement pair starts.
                if (mdnsRetirement.get()?.awaitTrue(finalizerDeadline) != true) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "mDNS responder did not stop",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                val sensorClose = sensorPersistenceClosed.get()
                    ?: return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "sensor cleanup did not start",
                        shutdownFreeze = shutdownFreeze,
                    )
                if (!awaitFinalizerFuture(finalizerDeadline, sensorClose)) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "proximity persistence did not drain",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                if (root != null && !root.isCompleted) {
                    val waitMs = finalizerDeadline.remainingMs()
                    val scopeDrained = waitMs > 0L && runBlocking {
                        withTimeoutOrNull(waitMs) { root.join(); true } ?: false
                    }
                    if (!scopeDrained) return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "service scope did not drain",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                if (!httpOwnersStopped.get()) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "HTTP owners did not stop cleanly",
                        shutdownFreeze = shutdownFreeze,
                    )
                }

                if (::entityLearning.isInitialized && !runFinalizerStep(finalizerDeadline) { entityLearning.close() }) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "entity-learning store did not close",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                // Runtime/scope/sensor/learner teardown above serializes their last writes. Fence any
                // unrelated same-process caller only now, so final producer persistence is not rejected.
                shutdownFreeze = AppState.freezeForServiceShutdown(this)
                if (shutdownFreeze == null) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "application-state shutdown admission was not frozen",
                        shutdownFreeze = null,
                    )
                }
                val stateFlushMs = finalizerDeadline.remainingMs()
                if (stateFlushMs <= 0L || !AppState.flush(this, stateFlushMs)) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "final application-state writes did not drain",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                val proofMs = finalizerDeadline.remainingMs()
                val cleanDatabaseProof = if (proofMs > 0L) {
                    runBoundedShutdownProof(proofMs) {
                        AppState.proveCleanServiceShutdown(this, proofMs)
                    }
                } else null
                if (cleanDatabaseProof == null) {
                    return@Thread finishTeardownAfterExternalStateIsSafe(
                        completed = false,
                        reason = "final application-state flush or WAL checkpoint did not prove stable",
                        shutdownFreeze = shutdownFreeze,
                    )
                }
                finishTeardownAfterExternalStateIsSafe(completed = true,
                    reason = "service teardown completed with a stable database",
                    shutdownFreeze = shutdownFreeze,
                    cleanDatabaseProof = cleanDatabaseProof,
                )
            } catch (error: Throwable) {
                Log.e(TAG, "asynchronous service teardown failed", error)
                finishTeardownAfterExternalStateIsSafe(
                    completed = false,
                    reason = "unexpected finalizer failure",
                    shutdownFreeze = shutdownFreeze,
                )
            }
        }.apply {
            isDaemon = true
            name = "ha-paneld-service-finalizer"
        }.start()
    }

    private fun runFinalizerStep(deadline: MonotonicDeadline, block: () -> Unit): Boolean {
        val waitMs = deadline.remainingMs()
        if (waitMs <= 0L) return false
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ha-paneld-service-finalizer-step").apply { isDaemon = true }
        }
        return try {
            executor.submit(Callable { block(); true }).get(waitMs, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            Log.e(TAG, "bounded service finalizer step failed", error)
            false
        } finally {
            executor.shutdownNow()
        }
    }

    private fun awaitFinalizerFuture(deadline: MonotonicDeadline, future: Future<*>): Boolean {
        val waitMs = deadline.remainingMs()
        if (waitMs <= 0L) return false
        return try {
            future.get(waitMs, TimeUnit.MILLISECONDS)
            true
        } catch (error: Throwable) {
            Log.e(TAG, "bounded service finalizer future failed", error)
            false
        }
    }

    /**
     * Route profile/WebView/recovery restarts through the complete Android lifecycle teardown. Closing
     * synchronous admission now prevents new hardware work before the main-loop stop reaches onDestroy;
     * onDestroy remains the only owner of producer drains, final state flush, checkpoint and exit proof.
     */
    private fun requestSafeProcessBoundary(reason: String, noticeReason: String) {
        if (!teardownBoundary.requestExplicitBoundary()) return
        // Accepting the request makes this process terminal — serviceTeardownDisposition always EXITs on
        // an explicit boundary — so every service generation created in it from here on must stand down.
        // Only this branch may commit: an ordinary clean stop RELEASEs a same-process successor and never
        // exits, and fencing that successor would leave nothing to restart the panel.
        PROCESS_BOUNDARY_COMMITMENT.commit()
        restartAfterInternalBoundary.set(true)
        announcePanelAssistantRestart("app", noticeReason, 30_000L)
        Log.i(TAG, "safe process restart requested: $reason")
        closeServiceAdmissions()
        mainHandler.post { stopSelf() }
    }

    private fun announcePanelAssistantRestart(scope: String, reason: String, expectedBackMs: Long) {
        if (::haLinks.isInitialized) haLinks.announcePanelAssistantRestart(scope, reason, expectedBackMs)
    }

    /**
     * App-local workers die at the process boundary, but the root CDP relay and display/system-policy
     * state do not. Never exit until the relay is proved absent and the screen, kiosk policy, and navbar
     * crop have been restored. Cleanup admission is closed first so an explicit external service stop
     * also leaves a usable panel even if Android has no remaining START_STICKY request to recreate us.
     */
    private fun finishTeardownAfterExternalStateIsSafe(
        completed: Boolean,
        reason: String,
        shutdownFreeze: StateQuiescence?,
        cleanDatabaseProof: CleanDatabaseProof? = null,
    ) {
        if (!completed) Log.e(TAG, "service teardown was incomplete: $reason")
        finishAfterExternalStateIsSafe(completed, reason, shutdownFreeze, cleanDatabaseProof)
    }

    private fun finishAfterExternalStateIsSafe(
        completed: Boolean,
        reason: String,
        shutdownFreeze: StateQuiescence? = null,
        cleanDatabaseProof: CleanDatabaseProof? = null,
    ) {
        runServiceBoundary(
            boundary = teardownBoundary,
            completed = completed,
            prepare = { controls.prepareExternalBoundary() },
            prove = { attempt ->
                val kioskSafe = controls.proveKioskSafe()
                val navbarSafe = controls.proveNavbarSafe()
                val screenSafe = runCatching {
                    proveScreenSafeForBoundary(displayPower.ensureScreenExitRecovery())
                }.onFailure { Log.e(TAG, "screen recovery before process restart failed", it) }
                    .getOrDefault(false)
                val relaySafe = runCatching {
                    if (GuardDbProcessAdmission.maintenanceRequired()) {
                        RemoteDebugSecurityTransitionGate.withLock {
                            val guard = (GuardDbProcessAdmission.current() as? GuardDbSentinelLoad.Valid)?.sentinel
                                ?: return@withLock false
                            CdpRelay.proveAbsentForGuardDbHandoff() &&
                                config.hardenedSecurityEnabled && controls.adb.hardenedRemoteDebugOff() &&
                                RemoteDebugSecurityTransitionGate.hardenedAuthorityEpoch() ==
                                guard.securityAuthorityEpoch
                        }
                    } else {
                        CdpRelay.stopAndVerifyForProcessExit()
                    }
                }.onFailure { Log.e(TAG, "CDP relay cleanup before process restart failed", it) }
                    .getOrDefault(false)
                // Durable markers preserve deferred policy cleanup. Once the screen is proved usable,
                // privilege loss must force a fresh process rather than fence START_STICKY forever.
                val forceFreshProcess = shouldForceFreshProcessAfterExternalRecovery(
                    attempt = attempt,
                    maxAttempts = EXTERNAL_STATE_BOUNDARY_ATTEMPTS,
                    kioskSafe = kioskSafe,
                    navbarSafe = navbarSafe,
                    screenSafe = screenSafe,
                    relaySafe = relaySafe,
                )
                val externalStateSafe = kioskSafe && navbarSafe && screenSafe && relaySafe
                if (!externalStateSafe && !forceFreshProcess) {
                    Log.e(
                        TAG,
                        "withholding process restart until external state is safe " +
                            "(attempt=$attempt kioskSafe=$kioskSafe navbarSafe=$navbarSafe " +
                            "screenSafe=$screenSafe relaySafe=$relaySafe)",
                    )
                }
                ServiceBoundaryProof(externalStateSafe, forceFreshProcess)
            },
            pauseBeforeRetry = {
                try {
                    Thread.sleep(EXTERNAL_STATE_RETRY_MS)
                } catch (_: InterruptedException) {
                    // This recovery owner must keep retrying until external state is safe to abandon.
                }
            },
            finish = { disposition ->
                if (disposition == ServiceTeardownDisposition.EXIT) {
                    UpgradeShutdownCoordinator.failShutdown(
                        this,
                        upgradeShutdownClaim,
                        shutdownFreeze,
                        restartLease::completeTeardown,
                        "shutdown_not_clean",
                    )
                    Log.i(TAG, "$reason; entering a clean process boundary")
                    kotlin.system.exitProcess(0)
                } else {
                    val heldForUpgrade = shutdownFreeze != null && cleanDatabaseProof != null &&
                        UpgradeShutdownCoordinator.holdAfterCleanShutdown(
                            upgradeShutdownClaim,
                            shutdownFreeze,
                            cleanDatabaseProof,
                            restartLease::completeTeardown,
                        )
                    if (!heldForUpgrade) {
                        shutdownFreeze?.close()
                        Log.i(TAG, "$reason; releasing the same-process service successor")
                        restartLease.completeTeardown()
                    } else {
                        Log.i(TAG, "$reason; holding the service successor for verified host transfer")
                    }
                }
            },
        )
    }

    /** The periodic hardware pass: one local observation of the current runtime every minute. */
    private fun startStateObservationTick() {
        if (stateObservationTick != null) return
        val worker = OwnedThread("state-observation") {
            while (true) {
                try { Thread.sleep(STATE_OBSERVATION_MS) } catch (_: InterruptedException) { break }
                requestLocalObservation(runtime) { it.core }
            }
        }
        stateObservationTick = worker
        worker.start()
    }

    private fun reconcileHelperInstallStaging() {
        val stagingDir = File(filesDir, HelperInstallTransaction.STAGING_DIR)
        val reconciler = HelperInstallReconciler(HelperClient)
        scope.periodic(
            intervalMs = INSTALL_RECONCILE_MS,
            tag = TAG,
            name = "helper-install-staging",
            onError = { error -> Log.w(TAG, "helper install staging reconciliation failed", error) },
        ) {
            val result = reconciler.reconcile(stagingDir)
            if (result.removed > 0 || result.remaining > 0) {
                Log.d(TAG, "helper install staging: removed=${result.removed} remaining=${result.remaining}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val TAG = "ha-paneld/svc"
        private const val CAMERA_STOP_MS = 3_000L
        // Local observation cadence; the one-minute state audit of state-convergence.md.
        private const val STATE_OBSERVATION_MS = 60_000L
        private const val RECOVERY_RESTART_GRACE_MS = 1_000L
        private const val COMPANION_STARTUP_STATUS_POLL_MS = 2_000L
        // Never-blank-screen watchdog poll interval; re-lights an unintentionally-dark panel within one tick.
        private const val SCREEN_WATCHDOG_MS = 60_000L
        private const val INSTALL_RECONCILE_MS = 60_000L
        internal const val WAKE_WORKER_JOIN_MS = 500L
        private const val SERVICE_DESTROY_BUDGET_MS = 1_000L
        private const val ASYNC_TEARDOWN_WAIT_MS = 10_000L
        private const val ASYNC_TEARDOWN_BUDGET_MS = 30_000L
        internal const val EXTERNAL_STATE_RETRY_MS = 1_000L
        private const val EXTERNAL_STATE_BOUNDARY_ATTEMPTS = 5
        private const val NETWORK_RECONFIGURE_BUDGET_MS = 15_000L

        /** First-configuration retire budget: nothing is published/owned, so this bounds only the
         *  best-effort close of a never-connected runtime. See the fast path in the retire lambda. */
        private val SERVICE_RESTART_BARRIER = ServiceRestartBarrier()
        private val PROCESS_BOUNDARY_COMMITMENT = ProcessBoundaryCommitment()

        fun start(context: Context, fromVisibleActivity: Boolean = false) {
            if (GuardDbProcessAdmission.maintenanceRequired()) {
                GuardDbMaintenanceService.start(context)
                return
            }
            // An armed upgrade has deliberately claimed this service down so an install can proceed
            // against a quiesced database. Every route that legitimately resumes it — cancelAndResume,
            // failShutdown — disarms the gate first and then calls back in here, so refusing while
            // armed blocks only an *undeliberate* start, such as the accessibility revival below.
            // The refusal lives here rather than at the caller because this is the single entry point:
            // a second copy in PanelAccessibilityService would be a second definition of the contract,
            // free to drift.
            if (UpgradeShutdownCoordinator.isArmed()) {
                Log.i(TAG, "not starting: an upgrade holds the service down")
                return
            }
            val intent = Intent(context, PaneldService::class.java)
            dispatchPanelServiceStart(
                Build.VERSION.SDK_INT,
                fromVisibleActivity,
                alreadyForeground = ServiceNotifications.anyServiceForeground,
                startOrdinary = { context.startService(intent) },
                startForeground = { context.startForegroundService(intent) },
            )
        }
    }
}

/**
 * A visible Oreo activity may start its own service without arming the cold-process FGS timer, and
 * through Android 11 a service already foreground in this process is never asked to promote again:
 * that request only arms a timer the running service has no reason to answer. From Android 12 an
 * ordinary start that reaches a new generation (one requested while the old one stops) could not
 * promote from the background, so there the foreground start stays.
 */
internal fun dispatchPanelServiceStart(
    sdkInt: Int,
    fromVisibleActivity: Boolean,
    alreadyForeground: Boolean,
    startOrdinary: () -> Unit,
    startForeground: () -> Unit,
) {
    if (sdkInt >= Build.VERSION_CODES.O &&
        !(alreadyForeground && sdkInt <= Build.VERSION_CODES.R) &&
        !(fromVisibleActivity && sdkInt <= Build.VERSION_CODES.O_MR1)
    ) {
        startForeground()
    } else {
        startOrdinary()
    }
}

/** The periodic hardware pass belongs to the running service, not to any transport. */
internal fun <T : Any> requestLocalObservation(
    runtime: ServiceRuntimeOwner<T>,
    core: (T) -> PanelStateCore,
) {
    val observed = runtime.observe() ?: return
    core(observed.value).requestLocalObservation { runtime.isCurrent(observed) }
}
