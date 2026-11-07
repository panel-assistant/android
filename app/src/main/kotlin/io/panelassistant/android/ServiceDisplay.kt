package io.panelassistant.android

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.panelassistant.android.control.AutoBrightnessController
import io.panelassistant.android.control.AutoSleepController
import io.panelassistant.android.control.BrightnessController
import io.panelassistant.android.control.BrightnessPreferenceOrigin
import io.panelassistant.android.control.BrightnessSettingObserver
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.OverlayWakeTap
import io.panelassistant.android.control.PowerController
import io.panelassistant.android.control.PowerSafetyController
import io.panelassistant.android.control.ScreenController
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.http.AutoSleepHttpApi
import io.panelassistant.android.platform.AndroidScreenPower
import io.panelassistant.android.sensors.HaAmbientLuxSubscriber
import io.panelassistant.android.sensors.HaAmbientSourcePhase
import io.panelassistant.android.sensors.HaPresenceSourceManager
import io.panelassistant.android.sensors.HaSiteMetadataClient
import io.panelassistant.android.sensors.SensorReporter
import io.panelassistant.android.util.DashboardTheme
import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.ServiceRuntimeOwner
import io.panelassistant.android.util.SingleFlightExecutor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal fun shouldDisableAutoBrightnessForMissingSource(
    enabled: Boolean,
    haEntity: String,
    hasLocalSensor: Boolean,
): Boolean = enabled && haEntity.isBlank() && !hasLocalSensor

internal fun adaptiveHaSource(enabled: Boolean, configuredEntity: String): String? =
    configuredEntity.trim().takeIf { enabled && it.isNotEmpty() }

/**
 * Whether the built-in dashboard must be rebuilt for the Ambient theme: only when it is the renderer,
 * it is in front, and the WebView there carries a different effective policy from the one Ambient
 * resolves to now. A renderer that is not in front is left alone rather than pulled forward; it is
 * checked again when it returns to the front, and its next launch compares the same signature.
 *
 * [alreadyRequested] is the resolution a rebuild was last requested for. One request per resolution:
 * a relaunch that could not rebuild (an on-panel sign-in, say) pauses and resumes the dashboard, and
 * asking again on that resume would relaunch it forever.
 */
internal fun ambientThemeNeedsRebuild(
    effectivePolicy: String,
    appliedSignature: String?,
    builtinRenderer: Boolean,
    foreground: Boolean,
    alreadyRequested: String? = null,
): Boolean = builtinRenderer && foreground && appliedSignature != null && appliedSignature != effectivePolicy &&
    alreadyRequested != effectivePolicy

internal fun refreshAmbientSourceBinding(
    restartSource: Boolean,
    selectedSource: String?,
    markUnavailable: () -> Unit,
    setSource: (String?) -> Unit,
) {
    if (restartSource) {
        markUnavailable()
        setSource(null)
    }
    setSource(selectedSource)
}

internal fun applyAutoSleepAreaFailOff(
    expectedEpoch: Long,
    expectedSettingGeneration: Long,
    epochIsCurrent: (Long) -> Boolean,
    persistOff: (Long) -> AutoSleepWriteResult?,
    refreshController: () -> Unit,
    applyBridge: () -> LiveSettingApplyResult,
): LiveSettingApplyResult {
    if (!epochIsCurrent(expectedEpoch)) return LiveSettingApplyResult.APPLIED
    return when (persistOff(expectedSettingGeneration)) {
        null -> LiveSettingApplyResult.APPLIED
        AutoSleepWriteResult.FAILED -> LiveSettingApplyResult.FAILED
        AutoSleepWriteResult.COMMITTED -> {
            refreshController()
            applyBridge()
        }
        AutoSleepWriteResult.UNCHANGED -> applyBridge()
    }
}

internal fun applyAutoSleepFencedReplay(
    fence: Long,
    currentGeneration: Long,
    currentEnabled: Boolean,
    persistOff: (Long) -> AutoSleepWriteResult?,
    refreshController: () -> Unit,
    applyBridge: () -> LiveSettingApplyResult,
): LiveSettingApplyResult = when {
    currentGeneration == fence -> applyBridge()
    currentEnabled && currentGeneration + 1L == fence -> when (persistOff(currentGeneration)) {
        AutoSleepWriteResult.COMMITTED -> {
            refreshController()
            applyBridge()
        }
        AutoSleepWriteResult.UNCHANGED -> applyBridge()
        AutoSleepWriteResult.FAILED -> LiveSettingApplyResult.FAILED
        null -> LiveSettingApplyResult.APPLIED
    }
    else -> LiveSettingApplyResult.APPLIED
}

internal fun acceptCommittedAutoSleepSetting(
    authority: LiveSettingAuthority,
    refreshController: () -> Unit,
) {
    authority.discard("auto_sleep")
    refreshController()
}

/**
 * The panel's screen, brightness and power: the backlight and screen controllers with the vendor screen
 * route, adaptive brightness with its Home Assistant ambient source and site client, the Ambient theme
 * rebuild, auto-sleep, the power locks and power safety, the wake-on-wave worker and the screen's
 * recovery before a process exits.
 *
 * The service calls each hook at the point in its own build, start and teardown sequence where this
 * code ran before it moved here. Every constructor lookup is read when used, never captured: the
 * service creates this owner before any of them exists.
 */
internal class ServiceDisplay(
    private val service: Context,
    private val scope: CoroutineScope,
    private val teardownBoundary: ServiceTeardownBoundary,
    private val config: () -> Config,
    private val profile: () -> DeviceProfile,
    private val runtime: () -> ServiceRuntimeOwner<PaneldService.NetworkRuntime>,
    private val runtimeInstalled: () -> Boolean,
    private val liveSettingAuthority: () -> LiveSettingAuthority,
    private val haLinks: () -> ServiceHaLinks,
    private val sensors: () -> SensorReporter,
    private val sensorsReady: () -> Boolean,
    private val controls: () -> ServiceSystemControls,
) {
    private val core: PanelStateCore get() = runtime().current().core
    val wakeOnWaveWorker = SingleFlightExecutor("ha-paneld-wake-on-wave")
    private var vendorScreen: io.panelassistant.android.platform.VendorScreen = io.panelassistant.android.platform.NoVendorScreen
    lateinit var brightness: BrightnessController
        private set
    lateinit var autoBright: AutoBrightnessController
        private set
    lateinit var autoSleep: AutoSleepController
        private set
    lateinit var haAmbientLux: HaAmbientLuxSubscriber
        private set
    private lateinit var haSiteMetadata: HaSiteMetadataClient
    private var brightnessObserver: ContentObserver? = null
    private var screenOffTimeoutObserver: ContentObserver? = null
    private var haCandidateIdentity = ""
    private val adaptiveSiteGeneration = java.util.concurrent.atomic.AtomicLong()
    // One observer task at a time, in delivery order, on the service scope's IO pool: no thread of its own.
    private val brightnessObserverLane = Dispatchers.IO.limitedParallelism(1)
    lateinit var screen: ScreenController
        private set
    lateinit var power: PowerController
        private set
    lateinit var powerSafety: PowerSafetyController
        private set
    private val screenExitRecoveryOwner = AtomicReference<CompletableFuture<Boolean>?>(null)

    /** Brightness, the vendor screen route, the screen controller, adaptive brightness and the site client. */
    fun buildControllers() {
        brightness = BrightnessController(
            service,
            scale = io.panelassistant.android.control.BacklightScale(
                profile().backlightTransfer,
                profile().backlightRoute,
                config().ownedBacklightLevel,
            ),
        )
        vendorScreen = profile().vendorService?.let { io.panelassistant.android.platform.LocalRpcVendorScreen(it.port, config().vendorBrightnessCustody()) }
            ?: io.panelassistant.android.platform.NoVendorScreen
        screen = ScreenController(
            brightness,
            AndroidScreenPower(service),
            wakeTap = OverlayWakeTap(service),
            route = profile().screenOff,
            displacedTimeout = io.panelassistant.android.control.ConfigDisplacedScreenTimeout(config()),
            vendor = vendorScreen,
        )
        autoBright = AutoBrightnessController(
            service, brightness, config(), screen::actuateBrightnessIfOn,
            onAmbientThemeChanged = ::reconcileAmbientTheme,
        )
        haSiteMetadata = HaSiteMetadataClient(config())
    }

    /** The Home Assistant ambient-light source and the screen's wake callbacks; after the Home Assistant links. */
    fun bindHaAmbient() {
        haAmbientLux = HaAmbientLuxSubscriber(
            scope = scope,
            auth = haLinks().haSessionAuthority,
            transport = haLinks().haApi,
            streamOwner = haLinks().haExactEntityStream,
            onSample = { sample -> autoBright.submitHaLux(sample.lux) },
            onStatus = { status ->
                autoBright.setHaSourceAvailable(status.phase == HaAmbientSourcePhase.LIVE)
            },
        )
        screen.onWakeCompleted = {
            scope.launch(Dispatchers.Default) { autoBright.reapplyLatest() }
            if (::autoSleep.isInitialized) autoSleep.noteScreenWoken()
        }
        // After a LOCAL touch-wake, tell HA the screen is on so `light.<panel>_screen` tracks reality.
        screen.onWakeByTap = { automaticEpoch ->
            // Manual tap-wakes are already observed by the shared raw-touch listener; only an exact
            // automatic epoch is additional causal proof for the auto-sleep learner.
            if (automaticEpoch != null && ::autoSleep.isInitialized) autoSleep.noteTapWake(automaticEpoch)
            runCatching { core.publishScreenOn() }
        }
    }

    /** Power locks, the power-safety controller and auto-sleep. */
    fun buildPowerAndSleep() {
        power = PowerController(service)
        powerSafety = PowerSafetyController(
            context = service,
            power = power,
            screenOffMechanism = profile().screenOff.name.lowercase(),
            screenRouteSelection = screen::routeSelection,
            directRootExpected = profile().appCanSu,
            heldSleep = screen::heldSleepRecord,
        )

        autoSleep = AutoSleepController(
            context = service,
            scope = scope,
            config = config(),
            screen = screen,
            onScreenChanged = {
                if (runtimeInstalled() && !teardownBoundary.isStopping) {
                    runCatching { core.publishScreenState() }
                }
            },
            onProjectionChanged = {
                if (runtimeInstalled() && !teardownBoundary.isStopping) {
                    runCatching { runtime().observe()?.value?.core?.publishAutoSleepActivity() }
                }
            },
            onNoArea = { expectedEpoch ->
                if (runtimeInstalled() && !teardownBoundary.isStopping) {
                    val expectedSettingGeneration = config().autoSleepGeneration
                    val admitted = liveSettingAuthority().applyOrQueueIf(
                        key = "auto_sleep",
                        value = "false",
                        previousValue = "true",
                        fence = expectedSettingGeneration + 1L,
                        expected = {
                            config().autoSleep && config().autoSleepGeneration == expectedSettingGeneration &&
                                autoSleep.isCurrentConfigurationEpoch(expectedEpoch)
                        },
                    ) { _, _, _ ->
                        applyAutoSleepAreaFailOff(
                            expectedEpoch = expectedEpoch,
                            expectedSettingGeneration = expectedSettingGeneration,
                            epochIsCurrent = autoSleep::isCurrentConfigurationEpoch,
                            persistOff = { generation ->
                                config().setAutoSleepIf(
                                    expected = true,
                                    expectedGeneration = generation,
                                    on = false,
                                )
                            },
                            refreshController = { refreshAutoSleepPresence() },
                            applyBridge = {
                                runtime().observe()?.value?.commands?.convergeAutoSleep(
                                    expectedGeneration = expectedSettingGeneration + 1L,
                                    expectedValue = false,
                                ) ?: LiveSettingApplyResult.DEFERRED
                            },
                        )
                    }
                    if (!admitted) {
                        Log.w(TAG, "stale or failed auto-sleep Area-removal fail-off was not admitted")
                    }
                }
            },
            sourceManagerFactory = { offerAggregate ->
                HaPresenceSourceManager(
                    scope = scope,
                    config = config(),
                    streamOwner = haLinks().haExactEntityStream,
                    offerAggregate = offerAggregate,
                )
            },
        )
    }

    /** Runtime lane, startup: adaptive inputs, the Ambient theme return listener and idle-dim prevention. */
    fun startAdaptive() {
        refreshAdaptiveBrightnessInputs(restartSource = false)
        autoBright.activate()
        // A verdict reached while the dashboard was paused is applied when it returns to the front.
        BuiltinDashboard.setForegroundGainedListener {
            scope.launch(Dispatchers.Default) { reconcileAmbientTheme() }
        }
        brightness.applyPreventIdleDim(config().preventIdleDim, config())
    }

    fun applyKeepAwake() {
        // Keep the SoC + network awake (screen still free to sleep) so Doze/suspend can't freeze the
        // Home Assistant connection into a half-open, unreachable one. On by default; see keep_awake.
        runCatching { power.apply(config().keepAwake) }
    }

    fun startAutoSleep() {
        autoSleep.start()
    }

    fun restoreLevelOverVendor() {
        if (vendorScreen.holdBrightness()) screen.restoreLevelOverVendor()
    }

    /** The never-blank guard's periodic check. */
    fun relightIfUnexpectedlyDark() {
        if (screen.recoverUnexpectedDark()) {
            Log.w(TAG, "screen dark with no intent — re-lighting (never-blank guard)")
            core.publishScreenOn()
        }
    }

    fun closeAdmission() {
        if (::screen.isInitialized) screen.closeAdmission()
    }

    /** Main thread, teardown: stop the adaptive site fetch and the brightness setting observer. */
    fun closeObservers() {
        adaptiveSiteGeneration.incrementAndGet()
        brightnessObserver?.let { observer ->
            runCatching { service.contentResolver.unregisterContentObserver(observer) }
            brightnessObserver = null
        }
        screenOffTimeoutObserver?.let { observer ->
            runCatching { service.contentResolver.unregisterContentObserver(observer) }
            screenOffTimeoutObserver = null
        }
    }

    fun closeHaAmbientAndSleep(closeOwner: (String, () -> Unit) -> Unit, closeOwnerResult: (String, () -> Boolean) -> Unit, deadline: MonotonicDeadline) {
        if (::haAmbientLux.isInitialized) closeOwner("HA ambient light") { haAmbientLux.close() }
        if (::autoSleep.isInitialized) {
            closeOwnerResult("auto sleep") { autoSleep.closeAndJoin(deadline.remainingMs()) }
        }
    }

    fun closeAdaptive(closeOwnerResult: (String, () -> Boolean) -> Unit, deadline: MonotonicDeadline) {
        BuiltinDashboard.setForegroundGainedListener(null)
        if (::autoBright.isInitialized) {
            closeOwnerResult("adaptive brightness") {
                autoBright.closeAndJoin(deadline.remainingMs())
            }
        }
    }

    fun closeWakeAndPower(closeOwnerResult: (String, () -> Boolean) -> Unit, deadline: MonotonicDeadline) {
        closeOwnerResult("wake-on-wave worker") {
            wakeOnWaveWorker.closeAndJoin(
                minOf(deadline.remainingMs(), PaneldService.WAKE_WORKER_JOIN_MS),
            )
        }
        closeOwnerResult("power") { power.releaseAndVerify() }
    }

    fun autoBrightnessHttpApi() = io.panelassistant.android.http.ServiceAutoBrightnessHttpApi(
        { config() }, { autoBright }, { sensors() }, { haAmbientLux }, { refreshAdaptiveBrightnessInputs() },
    )

    fun autoSleepHttpApi(): AutoSleepHttpApi = object : AutoSleepHttpApi {
        override fun statusJson(): String = autoSleep.statusJson()
        override suspend fun historyJson(hours: Int): String = autoSleep.historyJson(hours)
        override suspend fun prerequisite() = autoSleep.prerequisite()
        override fun setSourceIncluded(areaKey: String, sourceKey: String, included: Boolean) =
            autoSleep.setSourceIncluded(areaKey, sourceKey, included)
        override fun noteAreaChanged() {
            refreshAutoSleepPresence()
        }
    }

    fun refreshAutoSleepPresence(): Boolean {
        val accepted = autoSleep.refresh()
        if (accepted && sensorsReady()) {
            autoSleep.noteProximityState(
                sensors().proximityPresenceNear().takeIf { sensors().proximityPresenceReady() },
            )
        }
        return accepted
    }

    fun registerBrightnessPreferenceObserver() {
        val observer = BrightnessSettingObserver(
            handler = Handler(Looper.getMainLooper()),
            initialLevel = brightness.getCommanded(),
            readSetting = {
                runCatching { Settings.System.getInt(service.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }
                    .getOrNull()
            },
            levelFromSetting = brightness::levelFromSetting,
            consumeOwnedWrite = { setting -> brightness.consumeOwnedSettingChange(setting) },
            observedDark = screen::observedDark,
            noteExternal = { level, prior ->
                autoBright.noteExternalBrightness(level, BrightnessPreferenceOrigin.ANDROID_SYSTEM, prior)
            },
            background = { task -> scope.launch(brightnessObserverLane) { task() } },
            captureOffGeneration = screen::currentOffGeneration,
            handleWhileOff = screen::noteBrightnessWhileOff,
        )
        service.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
            false,
            observer,
        )
        brightnessObserver = observer
        // A timeout changed in Android's own settings reaches Home Assistant now, not at the next pass.
        val timeoutObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                runCatching { core.reconcile("screen_off_timeout") }
            }
        }
        service.contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT),
            false,
            timeoutObserver,
        )
        screenOffTimeoutObserver = timeoutObserver
    }

    fun refreshAdaptiveBrightnessInputs(restartSource: Boolean = true) {
        if (shouldDisableAutoBrightnessForMissingSource(
                enabled = config().autoBrightness,
                haEntity = config().autoBrightnessHaEntity,
                hasLocalSensor = sensors().hasLight(),
            )
        ) {
            Log.w(TAG, "Disabling auto-brightness: no local light sensor or HA illuminance source is configured")
            config().setAutoBrightness(false)
        }
        val enabled = config().autoBrightness
        val selected = adaptiveHaSource(enabled, config().autoBrightnessHaEntity)
        val candidateIdentity = config().haUrl.trim()
        if (candidateIdentity != haCandidateIdentity) {
            haCandidateIdentity = candidateIdentity
            haAmbientLux.invalidateCandidates()
        }
        refreshAmbientSourceBinding(
            restartSource = restartSource,
            selectedSource = selected,
            markUnavailable = { autoBright.setHaSourceAvailable(false) },
            setSource = haAmbientLux::setSource,
        )
        val generation = adaptiveSiteGeneration.incrementAndGet()
        if (enabled) {
            scope.launch {
                val site = haSiteMetadata.fetch().metadata
                if (site != null && !teardownBoundary.isStopping && adaptiveSiteGeneration.get() == generation) {
                    autoBright.updateSite(site.latitude, site.longitude, site.timeZone)
                }
                if (selected != null && !teardownBoundary.isStopping && adaptiveSiteGeneration.get() == generation) {
                    try {
                        val seed = haAmbientLux.loadHistory(selected)
                        if (!teardownBoundary.isStopping && adaptiveSiteGeneration.get() == generation) {
                            autoBright.seedHaHistory(seed)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        Log.i(TAG, "Home Assistant ambient history bootstrap unavailable (${error.javaClass.simpleName})")
                    }
                }
            }
        }
        autoBright.reapplyLatest()
        // Turning auto-brightness on or off moves an Ambient panel's effective theme too.
        reconcileAmbientTheme()
    }

    // The resolution the last Ambient rebuild was requested for; see [ambientThemeNeedsRebuild].
    private var ambientRebuildRequestedFor: String? = null
    private val ambientRebuildLock = Any()

    /**
     * Take an Ambient panel's dashboard to the theme its room now resolves to, through the same
     * rebuild a Dashboard theme change uses: the renderer compares signatures on the relaunch and
     * bakes the matching document-start script.
     */
    private fun reconcileAmbientTheme(): Unit = synchronized(ambientRebuildLock) {
        if (teardownBoundary.isStopping) return
        val system = controls().systemOrNull() ?: return
        if (config().dashboardTheme != DashboardTheme.AMBIENT) return
        val effective = config().dashboardThemeEffective
        val applied = BuiltinDashboard.appliedThemeSignature
        if (applied == effective) ambientRebuildRequestedFor = null
        if (!ambientThemeNeedsRebuild(
                effectivePolicy = effective,
                appliedSignature = applied,
                builtinRenderer = system.resolveDashboard(config().dashboardPackage) == SystemController.BUILTIN_DASHBOARD,
                foreground = BuiltinDashboard.foreground,
                alreadyRequested = ambientRebuildRequestedFor,
            )
        ) return
        ambientRebuildRequestedFor = effective
        Log.i(TAG, "Ambient theme now resolves to $effective — rebuilding the dashboard")
        runCatching { system.launchHome(SystemController.BUILTIN_DASHBOARD) }
            .onFailure { Log.w(TAG, "Ambient theme rebuild failed", it) }
    }

    /** Explicit power-safety repair. This is referenced by the HTTP server during startup but can execute
     * only after admission on an IO dispatcher, behind the service replacement fence. */
    fun repairPowerSafety() = powerSafety.repair(
        readKeepAwakeConfigured = { config().keepAwake },
        readPreventIdleDimConfigured = { config().preventIdleDim },
        persistKeepAwake = {
            config().setKeepAwake(true)
            config().keepAwake
        },
        applyPreventIdleDim = {
            config().setPreventIdleDim(true)
            config().preventIdleDim && brightness.applyPreventIdleDim(true, config()).effective
        },
    ).also {
        // The repair may turn prevent_idle_dim on outside the dispatcher; report it now, not at the next audit.
        runCatching { core.reconcile("prevent_idle_dim") }
        runCatching { core.reconcile("screen_off_timeout") }
    }

    fun ensureScreenExitRecovery(): CompletableFuture<Boolean> {
        screenExitRecoveryOwner.get()?.let { return it }
        val completion = CompletableFuture<Boolean>()
        if (!screenExitRecoveryOwner.compareAndSet(null, completion)) {
            return checkNotNull(screenExitRecoveryOwner.get())
        }
        if (!::screen.isInitialized) {
            completion.complete(true)
            return completion
        }
        Thread {
            while (!completion.isDone) {
                val safe = runCatching { screen.restoreAndEstablishExitSafety() }
                    .onFailure { Log.e(TAG, "screen restore before process restart failed", it) }
                    .getOrDefault(false)
                if (safe) {
                    completion.complete(true)
                    return@Thread
                }
                try {
                    Thread.sleep(PaneldService.EXTERNAL_STATE_RETRY_MS)
                } catch (_: InterruptedException) {
                    // Screen recovery remains mandatory until hardware readback proves it is lit.
                }
            }
        }.apply {
            isDaemon = true
            name = "ha-paneld-screen-exit-recovery"
        }.start()
        return completion
    }

    private companion object {
        const val TAG = PaneldService.TAG
    }
}
