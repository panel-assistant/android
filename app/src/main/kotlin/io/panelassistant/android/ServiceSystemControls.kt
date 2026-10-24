package io.panelassistant.android

import android.content.Context
import android.os.Handler
import android.util.Log
import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.AndroidZigbeeGatewayHealthSource
import io.panelassistant.android.control.AutoBrightnessController
import io.panelassistant.android.control.BootChimeController
import io.panelassistant.android.control.BrightnessController
import io.panelassistant.android.control.BrightnessPreferenceOrigin
import io.panelassistant.android.control.CpuController
import io.panelassistant.android.control.KioskController
import io.panelassistant.android.control.LedEffectController
import io.panelassistant.android.control.NavbarController
import io.panelassistant.android.control.NavigateController
import io.panelassistant.android.control.PrivilegeRoute
import io.panelassistant.android.control.PrivilegedRouteObservation
import io.panelassistant.android.control.RelayController
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.control.TameController
import io.panelassistant.android.control.TouchSoundController
import io.panelassistant.android.control.VolumeController
import io.panelassistant.android.control.WatchdogController
import io.panelassistant.android.control.ZigbeeController
import io.panelassistant.android.control.ZigbeeHealthMonitor
import io.panelassistant.android.control.resolveTouchSoundIntent
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.config.defaultBool
import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.hardware.LedController
import io.panelassistant.android.hardware.LedFactory
import io.panelassistant.android.http.PaneldServer
import io.panelassistant.android.platform.AndroidScreenPower
import io.panelassistant.android.platform.AndroidSystemEnv
import io.panelassistant.android.sensors.SensorReporter
import io.panelassistant.android.util.OwnedThread
import io.panelassistant.android.util.ServiceRuntimeOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal fun preferredAdminHomeRepairRoute(
    privilege: PrivilegedRouteObservation,
): PrivilegeRoute? = preferredAdminHomeRepairRoute(
    helperRootReady = privilege.helperRootReady,
    directSuReady = privilege.directSuReady,
)

internal fun preferredAdminHomeRepairRoute(
    helperRootReady: Boolean,
    directSuReady: Boolean,
): PrivilegeRoute? = when {
    helperRootReady -> PrivilegeRoute.DAEMON
    directSuReady -> PrivilegeRoute.SU
    else -> null
}

internal fun shouldAttemptPeriodicAdminHomeRepair(
    serviceStopping: Boolean,
    adminHomeSelected: Boolean,
    admittedRoute: PrivilegeRoute?,
    policyGeneration: Long,
    failedGeneration: Long,
): Boolean = !serviceStopping && adminHomeSelected && admittedRoute != null &&
    failedGeneration != policyGeneration

/**
 * The panel's system controls: the launcher and HOME policy, the kiosk lock and its delayed reassert,
 * the navbar, the app watchdog, the LEDs, volume, touch sound, the boot chime, Zigbee and its health
 * monitor, relays, the CPU profile, network ADB, the vendor service starter and USB debugging feedback.
 *
 * The service calls each hook at the point in its own build, start and teardown sequence where this
 * code ran before it moved here. Every constructor lookup is read when used, never captured: the
 * service creates this owner before any of them exists.
 */
internal class ServiceSystemControls(
    private val service: Context,
    private val scope: CoroutineScope,
    private val teardownBoundary: ServiceTeardownBoundary,
    private val config: () -> Config,
    private val profile: () -> DeviceProfile,
    private val runtime: () -> ServiceRuntimeOwner<PaneldService.NetworkRuntime>,
    private val runtimeInstalled: () -> Boolean,
    private val liveSettingAuthority: () -> LiveSettingAuthority,
    private val server: () -> PaneldServer,
    private val mainHandler: () -> Handler,
    private val brightness: () -> BrightnessController,
    private val autoBright: () -> AutoBrightnessController,
    private val sensors: () -> SensorReporter,
    private val announceRestart: (String, String, Long) -> Unit,
    private val vendorUp: () -> Unit,
) {
    private val core: PanelStateCore get() = runtime().current().core
    private val launcherHomePolicyGeneration = java.util.concurrent.atomic.AtomicLong()
    private val launcherHomeFailedGeneration = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    @Volatile private var kioskReassert: OwnedThread? = null
    @Volatile var vendorServiceStarter: io.panelassistant.android.control.VendorServiceStarter? = null
        private set
    lateinit var led: LedController
        private set
    // Effect loop for the LED — owned here (not the runtime) so a runtime rebuild never orphans it.
    lateinit var ledEffect: LedEffectController
        private set
    lateinit var navigate: NavigateController
        private set
    private val companionHomeReturnGeneration = java.util.concurrent.atomic.AtomicLong()
    lateinit var volume: VolumeController
        private set
    lateinit var system: SystemController
        private set
    lateinit var tame: TameController
        private set
    lateinit var navbar: NavbarController
        private set
    lateinit var watchdog: WatchdogController
        private set
    lateinit var kiosk: KioskController
        private set
    lateinit var touchSound: TouchSoundController
        private set
    lateinit var bootChime: BootChimeController
        private set
    lateinit var zigbee: ZigbeeController
        private set
    lateinit var zigbeeHealth: ZigbeeHealthMonitor
        private set
    lateinit var relay: RelayController
        private set
    lateinit var cpu: CpuController
        private set
    lateinit var adb: AdbController
        private set
    val usbDebugging by lazy { io.panelassistant.android.control.UsbDebuggingObserver(service) }
    private var usbDebuggingFeedback: io.panelassistant.android.control.UsbDebuggingFeedback? = null

    /** Closed first in onDestroy, before any early return, so even a partly built service releases it. */
    fun closeUsbDebuggingFeedback() {
        usbDebuggingFeedback?.close()
    }

    fun buildLighting() {
        led = LedFactory.detect(profile())
        ledEffect = LedEffectController(led)
    }

    fun buildNavigation() {
        navigate = NavigateController(service)
        volume = VolumeController(service)
    }

    fun buildSystem() {
        system = SystemController(AndroidSystemEnv(service), vendorHomePackages = profile().vendorHomePackages, isDeviceSecure = AndroidScreenPower(service)::isDeviceSecure, beforeReboot = {
            announceRestart("panel", "reboot", 120_000L)
        }, homeDashboard = { config().homeDashboard }, onCompanionHome = { pkg, home ->
            val generation = companionHomeReturnGeneration.incrementAndGet()
            val priorNavigate = config().lastNavigate
            scope.launch {
                kotlinx.coroutines.delay(8_000L) // the Companion frontend must finish its cold start
                if (teardownBoundary.isStopping || generation != companionHomeReturnGeneration.get() ||
                    system.resolveDashboard(config().dashboardPackage) != pkg || config().homeDashboard != home ||
                    config().lastNavigate != priorNavigate
                ) return@launch
                val returnId = CompanionHomeReturn.arm(pkg, home, android.os.SystemClock.elapsedRealtime(), valid = {
                    !teardownBoundary.isStopping && generation == companionHomeReturnGeneration.get() &&
                        system.resolveDashboard(config().dashboardPackage) == pkg && config().homeDashboard == home &&
                        config().lastNavigate == priorNavigate
                }, delivered = {
                    config().lastNavigate = home
                    if (runtimeInstalled()) core.reconcile("navigate")
                })
                if (!system.launchCompanionHomeReturn()) CompanionHomeReturn.clear(returnId)
            }
        })
    }

    fun buildControls() {
        watchdog = WatchdogController(system, config(), sensors()::proximityCalibrationActive)
        kiosk = KioskController(service, system, config(), profile().appCanSu)
        // On-device unlock gesture (7 corner taps): the same OFF the :8888 toggle sends, through the command
        // worker that orders every kiosk change. Off the main thread: the gesture fires on the overlay's touch
        // listener, and the request waits for the worker.
        kiosk.onUnlockRequested = {
            Thread {
                if (teardownBoundary.isStopping) return@Thread
                requestKioskSetting(config(), { runtime().current().commands }, "false") { kiosk.apply(false) }
            }.apply { isDaemon = true; name = "kiosk-unlock" }.start()
        }
        // When taming disables a vendor home launcher (e.g. eWeLink), immediately re-assert the dashboard
        // as home and foreground it — otherwise the panel sits on our admin launcher until the 300s
        // watchdog returns to the dashboard (a ~5-minute gap on boot). Activation is deferred until the
        // predecessor service has left the process; tame() itself runs in the service scope off-main.
        tame = TameController(service) {
            system.applyLauncherHomePolicy(
                config().launcherPackage,
                config().dashboardPackage,
                config().haUrl.isNotBlank(),
            )
            system.launchHome(config().dashboardPackage)
        }
        // The navbar gets the CONFIGURED dashboard value (may be the "builtin" sentinel), never
        // dashboardTarget(): that resolves builtin to our own package name (for perf attribution), and
        // reloadDashboard(ownPackage) would take the foreign-app path — `am force-stop` on ourselves.
        navbar = NavbarController(
            service, system, volume, brightness(), { config().launcherPackage }, { config().dashboardPackage },
            profile().appCanSu, profile().hasRecents,
            onBrightnessChanged = { level ->
                autoBright().noteExternalBrightness(level, BrightnessPreferenceOrigin.PANEL_CONTROLS)
            },
            onBrightnessApplied = { core.publishScreenOn() },
            onVolumeChanged = { core.publishVolume() },
        )
        touchSound = TouchSoundController(service, profile().touchClickGain)
        bootChime = BootChimeController(service, config())
        zigbee = ZigbeeController(profile())
        zigbeeHealth = ZigbeeHealthMonitor(
            configuredOn = { config().zigbeeRouterConfigured && config().zigbeeRouterEnabled },
            source = AndroidZigbeeGatewayHealthSource(profile().zigbeeGatewayDir, zigbee),
            onContain = {
                config().setZigbeeRouterEnabled(false)
                runCatching { core.publishZigbeeRouterState() }
            },
            onSnapshot = { _, _ ->
                runCatching { core.publishZigbeeHealth() }
            },
        )
        relay = RelayController(profile())
        cpu = CpuController(profile())
        adb = AdbController(service, config())
    }

    /** Runtime lane, startup: a Portal panel tells the owner when USB debugging is switched on. */
    fun startUsbDebuggingFeedback() {
        if (io.panelassistant.android.control.UsbDebuggingFeedback.supportsProfile(profile().id)) {
            scope.launch {
                io.panelassistant.android.i18n.CatalogueLoader.assets(service).prepareNative(config().uiLanguage)
                mainHandler().post {
                    if (!teardownBoundary.isStopping && usbDebuggingFeedback == null) {
                        usbDebuggingFeedback = io.panelassistant.android.control.UsbDebuggingFeedback.observePortal(
                            service, profile().id, mainHandler(), scope, usbDebugging::state,
                            { teardownBoundary.isStopping }, { config().uiLanguage },
                        )
                    }
                }
            }
        }
    }

    /** Runtime lane, startup: settle kiosk platform state; answers whether it recovered. */
    fun recoverKioskAtStartup(): Boolean {
        // Kiosk is config-owned across boots so every restart retains its deliberate unlocked
        // window. Never let a stale/in-flight HTTP journal bypass that delay or override a newer OFF.
        if (!liveSettingAuthority().discard("kiosk_lock")) {
            Log.w(TAG, "could not discard stale kiosk live-setting journal")
        }
        if (config().kioskLock && !kiosk.isPersistentPolicyEligible()) {
            Log.w(TAG, "kiosk lock is unavailable on this profile; clearing stale desired state")
            if (!config().commitKioskLock(false)) {
                Log.w(TAG, "could not durably clear unsupported kiosk setting")
            }
        }
        val kioskRecoveredAtStartup = kiosk.recoverPersistentState(config().kioskLock)
        if (!kioskRecoveredAtStartup) {
            Log.w(TAG, "pending kiosk platform state could not be recovered; continuing unlocked")
        }
        return kioskRecoveredAtStartup
    }

    fun reassertFeedback() {
        // Resolve touch-sound intent before the HTTP server can accept a save, then reassert it in
        // both directions so the panel matches what it reports. Touch sound never changes stream
        // volume; an ON reassertion also loads the owned click sample for this process.
        val touchSoundIntent = resolveTouchSoundIntentOnce()
        if (!touchSound.reassert(touchSoundIntent).applied) {
            Log.w(TAG, "touch sound could not be reasserted at startup; intent remains $touchSoundIntent")
        }
        bootChime.applyPersisted()
    }

    fun startZigbeeHealth() {
        if (profile().zigbeeGatewayDir != null) zigbeeHealth.start()
    }

    fun startPolicies(kioskRecoveredAtStartup: Boolean) {
        // Start the app watchdog if enabled (off by default; self-heals a dead/abandoned dashboard).
        watchdog.apply(config().watchdogEnabled)
        // Experimental kiosk lock: a reboot CLEARS the runtime lock (by design — the anti-brick net), so
        // re-assert it after a delay if it was enabled. The delay leaves an unlocked window each boot so
        // an admin is never stranded; skipped if it was turned off (corner gesture / :8888 / HA) meanwhile.
        if (config().kioskLock || !kioskRecoveredAtStartup) scheduleKioskReassert()
        // Boot recovery gives a durable interrupted-OFF marker priority over the older persisted
        // ownership bit; only a marker-free ON intent may re-enable TCP. See AdbController.reassert.
        runCatching { adb.reassert() }
    }

    fun startVendorService() {
        // After the screen-on receiver exists: a check deferred by a dark boot is retried by it.
        vendorServiceStarter = io.panelassistant.android.control.VendorServiceStarter
            .forService(service, profile().vendorService, returnSurface = {
                system.returnAfterVendorStart(config().launcherPackage, config().dashboardPackage, config().builtInRendererReady())
            }, stopping = { teardownBoundary.isStopping }, onUp = { vendorUp() }).also { it.request() }
    }

    fun warmUpRelay() {
        // Issue #93: read-only button-LED GPIO proof in its own coroutine, not queued behind prewarm().
        scope.launch(Dispatchers.IO) { relay.warmUp() }
    }

    /** The launcher, dashboard or URL changed: a new HOME policy generation, applied off the caller. */
    fun onLauncherHomeChanged() {
        launcherHomePolicyGeneration.incrementAndGet()
        launcherHomeFailedGeneration.set(Long.MIN_VALUE)
        scope.launch(Dispatchers.IO) {
            runCatching {
                system.applyLauncherHomePolicy(
                    config().launcherPackage,
                    config().dashboardPackage,
                    config().haUrl.isNotBlank(),
                )
            }.onFailure { Log.w(TAG, "launcher HOME policy apply failed", it) }
        }
    }

    fun closeAdmissions() {
        if (::kiosk.isInitialized) kiosk.closeAdmission()
        if (::navbar.isInitialized) navbar.closeAdmission()
    }

    fun closePolicies(closeOwner: (String, () -> Unit) -> Unit) {
        // Kiosk writes persistent system policy. Serialize OFF behind any admitted delayed reassert
        // before a forced process boundary; the persisted user preference itself remains unchanged.
        cancelKioskReassert()
        closeOwner("kiosk") { kiosk.apply(false) }
        closeOwner("navbar") { navbar.cleanup() }
    }

    fun stopZigbeeHealth(closeOwnerResult: (String, () -> Boolean) -> Unit) {
        if (::zigbeeHealth.isInitialized) closeOwnerResult("Zigbee health") { zigbeeHealth.stop() }
    }

    fun stopWatchdog(closeOwner: (String, () -> Unit) -> Unit) {
        closeOwner("watchdog") { watchdog.stop() }
    }

    fun closeLedEffect(closeOwner: (String, () -> Unit) -> Unit) {
        closeOwner("LED effect") { ledEffect.close() }
    }

    /** Finalizer: before proving external state safe, stop the kiosk reassert and drop the navbar crop. */
    fun prepareExternalBoundary() {
        cancelKioskReassert()
        if (::navbar.isInitialized) {
            runCatching { navbar.cleanup() }
                .onFailure { Log.e(TAG, "navbar cleanup before process restart failed", it) }
        }
    }

    /** Finalizer proof: kiosk policy off and its platform state recovered (true when never built). */
    fun proveKioskSafe(): Boolean {
        return if (!::kiosk.isInitialized) true else runCatching {
            kiosk.apply(false) && kiosk.recoverPersistentState(config().kioskLock)
        }
            .onFailure { Log.e(TAG, "kiosk policy cleanup before process restart failed", it) }
            .getOrDefault(false)
    }

    /** Finalizer proof: the navbar crop recovered (true when never built). */
    fun proveNavbarSafe(): Boolean {
        return if (!::navbar.isInitialized) true else runCatching {
            navbar.recoverPersistentState()
        }
            .onFailure { Log.e(TAG, "navbar overscan cleanup before process restart failed", it) }
            .getOrDefault(false)
    }

    /** The system controller once built, for readers that may run before construction. */
    fun systemOrNull(): SystemController? = if (::system.isInitialized) system else null

    /**
     * Settle this panel's touch-sound intent exactly once, and return it.
     *
     * Reporting has to be a pure function of durable intent. The platform `SOUND_EFFECTS_ENABLED` flag is
     * writable by firmware and by any other app holding `WRITE_SETTINGS`, so a reported value that
     * followed it drifted with no user action; the next unrelated Configure save then posted a value that
     * no longer matched, `DirectConfigMutationPlan` counted touch sound as changed, and the live-setting
     * journal queued an apply for a key nobody touched — permanently visible on a panel that cannot write
     * the flag at all.
     *
     * So an unresolved panel is adopted here, from its own actuation record first and from a single
     * reading of the flag second, and the answer is committed. From then on configuration `contains` the
     * key, [resolveTouchSoundIntent] short-circuits on it, and later drift can move the hardware but never
     * the setting. A failed commit changes nothing durable: the same evidence resolves again next boot.
     */
    private fun resolveTouchSoundIntentOnce(): Boolean {
        val resolution = resolveTouchSoundIntent(
            persistedIntent = config().touchSoundIntent,
            controllerRecord = touchSound.recordedState(),
            observedHardware = touchSound.observedPlatformState(),
            registryDefault = requireNotNull(SettingsRegistry.spec("touch_sound")).defaultBool(),
        )
        if (resolution.needsAdoption) {
            if (config().commitTouchSound(resolution.enabled)) {
                Log.i(TAG, "adopted touch sound intent ${resolution.enabled} (${resolution.origin})")
            } else {
                Log.w(TAG, "could not adopt touch sound intent (${resolution.origin}); retrying next boot")
            }
        }
        return resolution.enabled
    }

    /** Borrow the last management proof and the existing never-blank cadence. One failed periodic
     * mutation stays suppressed until the next explicit config generation; startup/config apply already
     * owns an immediate unrestricted attempt. */
    fun repairAdminHomeFromCapturedRoute() {
        val generation = launcherHomePolicyGeneration.get()
        val route = server().lastPrivilegeObservation()?.let(::preferredAdminHomeRepairRoute)
        if (!shouldAttemptPeriodicAdminHomeRepair(
                serviceStopping = teardownBoundary.isStopping,
                adminHomeSelected = system.isAdminLauncherSelection(config().launcherPackage),
                admittedRoute = route,
                policyGeneration = generation,
                failedGeneration = launcherHomeFailedGeneration.get(),
            )
        ) return
        val repaired = runCatching { system.ensureAdminLauncherHome(checkNotNull(route)) }
            .onFailure { Log.w(TAG, "could not reassert Panel launcher as HOME", it) }
            .getOrDefault(false)
        if (!repaired && launcherHomePolicyGeneration.get() == generation) {
            launcherHomeFailedGeneration.set(generation)
        }
    }

    private fun scheduleKioskReassert() {
        val worker = OwnedThread("kiosk-reassert") {
            val recovered = recoverAndMaybeEnableKiosk(
                escapeDelayMs = KIOSK_REASSERT_MS,
                retryDelayMs = KIOSK_RECOVERY_RETRY_MS,
                maxAttempts = KIOSK_RECOVERY_ATTEMPTS,
                shouldContinue = { !teardownBoundary.isStopping },
                // On the command worker, ordered after any kiosk change already queued; a dispatcher that
                // is draining for a replacement answers null and the attempt waits for the next one.
                recover = { runtime().current().commands.recoverKiosk(enable = false) },
                enabled = { config().kioskLock },
                enable = { runtime().current().commands.recoverKiosk(enable = true) },
            )
            if (!recovered && !teardownBoundary.isStopping) {
                Log.w(TAG, "kiosk platform recovery remains pending; leaving kiosk unlocked")
            }
        }
        kioskReassert = worker
        worker.start()
    }

    private fun cancelKioskReassert() {
        kioskReassert?.stop(KIOSK_CANCEL_JOIN_MS)
    }

    private companion object {
        const val TAG = PaneldService.TAG
        const val KIOSK_REASSERT_MS = 60_000L // post-boot delay before re-locking (admin escape window)
        const val KIOSK_RECOVERY_RETRY_MS = 1_000L
        const val KIOSK_RECOVERY_ATTEMPTS = 3
        const val KIOSK_CANCEL_JOIN_MS = 500L
    }
}
