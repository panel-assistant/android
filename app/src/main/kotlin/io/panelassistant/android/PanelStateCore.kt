package io.panelassistant.android

import io.panelassistant.android.device.PanelButtonKeys
import io.panelassistant.android.state.SoftwareUpdates
import android.os.SystemClock
import android.util.Log
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.BootChimeController
import io.panelassistant.android.control.BrightnessController
import io.panelassistant.android.control.CpuController
import io.panelassistant.android.control.LedCommandPolicy
import io.panelassistant.android.control.screenOffTimeoutSeconds
import io.panelassistant.android.control.LedEffectController
import io.panelassistant.android.control.RelayController
import io.panelassistant.android.control.ScreenController
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.control.VolumeController
import io.panelassistant.android.control.WifiDiagnosticDemand
import io.panelassistant.android.control.WifiDiagnosticSnapshot
import io.panelassistant.android.control.WifiOutageCounts
import io.panelassistant.android.control.ZigbeeController
import io.panelassistant.android.control.ZigbeeHealthSnapshot
import io.panelassistant.android.control.ZigbeeHealthState
import io.panelassistant.android.hardware.LedController
import io.panelassistant.android.hardware.NoOpLedController
import io.panelassistant.android.input.ButtonBus
import io.panelassistant.android.metrics.PanelMetrics
import io.panelassistant.android.panelassistant.PanelAssistantChannelCatalog
import io.panelassistant.android.panelassistant.PanelAssistantChannelShape
import io.panelassistant.android.sensors.ProximityReportGate
import io.panelassistant.android.state.StateConverger
import io.panelassistant.android.state.StateConverger.NoReadingReason
import io.panelassistant.android.state.StateConverger.Observation
import io.panelassistant.android.state.StateSink
import io.panelassistant.android.state.SyncGate
import io.panelassistant.android.state.SyncLog
import io.panelassistant.android.storage.StorageHealthRuntime
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.state.SoftwareComponent
import io.panelassistant.android.state.SoftwareUpdateInputs
import io.panelassistant.android.state.SoftwareUpdateSources
import io.panelassistant.android.util.HaLink
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.util.Json
import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.RetirableMutationGate
import io.panelassistant.android.util.interruptAndJoin
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.json.JSONObject

/**
 * The panel's one state owner for a network runtime generation: every state channel's authoritative
 * observation, the local observation pass, and what the panel can describe. Output leaves only through
 * [addStateSink] and [addEventSink]; nothing here knows a transport's addressing.
 */
internal class PanelStateCore(
    private val config: Config,
    private val brightness: BrightnessController,
    private val screen: ScreenController,
    private val led: LedController,
    // Service-owned + injected so a runtime rebuild (reconfigure) can never orphan a running effect loop.
    private val ledEffect: LedEffectController,
    private val volume: VolumeController,
    private val system: SystemController,
    private val bootChime: BootChimeController,
    private val zigbee: ZigbeeController,
    private val relay: RelayController,
    private val cpu: CpuController,
    private val adb: AdbController,
    private val buttonsEnabled: Boolean,
    // Panel has hardware buttons instrumented via the daemon (evdev): the event channel exists even when
    // the accessibility key capture is off (e.g. the WF1589T power button).
    private val buttonKeys: PanelButtonKeys,
    // Capability snapshot supplier (null = no gating, used by tests). Called off the main thread.
    capabilities: (() -> Capabilities)? = null,
    private val hasProximity: Boolean,
    private val hasTemperature: Boolean,
    private val hasHumidity: Boolean,
    // Panel carries a CHT8305 room temp/humidity chip (daemon-read) — gates the opt-in Room sensors.
    private val hasCht8305: Boolean,
    private val hasButtonBacklight: Boolean,
    /** The profile's key-backlight curve; the reported state stays the Home Assistant level. */
    private val buttonBacklightTransfer: io.panelassistant.android.hardware.TransferCurve =
        io.panelassistant.android.hardware.TransferCurve.Identity,
    // The panel's media player, or null when the profile declares no speaker: the native `media`
    // channel is then stated unsupported rather than described.
    private val media: io.panelassistant.android.media.PanelMediaPlayer? = null,
    // Live profile/probe capability: null keeps the camera channels while enumeration is unsettled.
    private val hasCamera: () -> Boolean? = { false },
    // Live light-sensor answer: true described, false settled absent, null left out until it settles.
    private val lightChannel: () -> Boolean? = { true },
    // Whether the panel can perform Android's Back now (InteractiveController.canBack); false states it unsupported.
    private val canBack: () -> Boolean = { false },
    // Service-owned auto-sleep policy projection.
    private val autoSleepActivity: () -> AutoSleepActivitySnapshot = { AutoSleepActivitySnapshot() },
    // Inputs for the update channels: installed versions, same-policy catalog targets, install-lane
    // ownership and Panel Assistant ownership. Null registers no update channels.
    private val softwareUpdateSources: (() -> SoftwareUpdateSources)? = null,
    private val zigbeeHealth: () -> ZigbeeHealthSnapshot = { ZigbeeHealthSnapshot() },
    private val storageHealth: () -> StorageHealthSnapshot = StorageHealthRuntime::snapshot,
    // The root helper's command line, for the button backlight.
    private val helperSend: (String) -> String? = HelperClient::send,
    private val wifiDiagnostics: (WifiDiagnosticDemand) -> WifiDiagnosticSnapshot = { WifiDiagnosticSnapshot() },
    private val wifiOutages: () -> WifiOutageCounts? = { null },
    private val learnedProximityEligibility: () -> Boolean = { false },
    // Learned proximity once it is settled; null while the calibration is still loading or has closed, so a
    // transient false is never stated to Home Assistant as a panel without the sensor.
    private val learnedProximityState: () -> Boolean? = { null },
    private val usbDebugging: () -> Observation.Reportable = { Observation.Unavailable },
) {
    private val lifecycle = RetirableMutationGate()
    /** Notified when a local pass observed the capability probes; [CapabilityObservation.grew] means new channels. */
    @Volatile var onCapabilitiesObserved: ((CapabilityObservation) -> Unit)? = null
    @Volatile private var nativeEventSink: ((channel: String, event: String) -> Unit)? = null
    private val haLinkResolutionThread = AtomicReference<Thread?>()
    private var buttonSubscription: ButtonBus.Subscription? = null

    /** One immutable capability value per pass, with a short monotonic burst cache. */
    internal val capabilities = CapabilitySnapshotSource(
        supplier = capabilities,
        onFailure = { error ->
            Log.w(TAG, "capability snapshot failed; publishing conservative channels", error)
        },
    )
    // Channel shape grows monotonically after a live capability is confirmed. A transient negative
    // snapshot therefore reports unknown/unavailable state instead of deleting the channel forever.
    internal val capabilityShape = CapabilityShape()
    private val capabilityChannelLock = Any()

    // Last brightness reported on the screen channel (-1 = screen reported OFF); the local pass reconciles
    // the effective hardware backlight against this so firmware dims are reported between commands.
    @Volatile private var lastScreenBrightness = -1
    // LED hardware has no readback on every backend. Report desired state only after this generation
    // has a confirmed write; a failed command/effect frame remains Unknown instead of fabricating success.
    @Volatile private var ledActuationKnown = false
    // Effective (node-scale) level the last command settled at; -1 = capture on next tick.
    @Volatile private var screenEffectiveBaseline = -1
    // Per-channel sync state: previous tick's read (settle detection) + last reported volume.
    @Volatile private var prevTickBrightness = -1
    @Volatile private var prevTickVolume = -1
    @Volatile private var lastPublishedVolume = -1
    // Last CPU tier reported, from startup on, so the local pass fires only when the live sysfs governor
    // is changed by something else (a thermal daemon, another app).
    @Volatile private var lastPublishedGovTier: String? = null
    // Recent "changed outside the panel's API" events, surfaced on the info page + /diag for debugging.
    private val syncLog = SyncLog()
    @Volatile private var lastIlluminance: Int? = null
    private val proximityPublication = ProximityPublicationState()
    @Volatile private var lastTemperature: Float? = null
    @Volatile private var lastHumidity: Float? = null

    private val converger = createStateConverger()

    /** The process-wide convergence pump is only a scheduler; this core's lifecycle gate remains the
     * authority for whether a queued observation may touch this concrete generation. */
    internal fun dispatchStateWork(action: () -> Unit) {
        StateConverger.dispatch {
            lifecycle.runIfOpen(Unit, action)
        }
    }

    private fun createStateConverger(): StateConverger {
        val known = { payload: String -> Observation.Known(payload) }
        val unknown = Observation.Unknown
        val noReading = { reason: NoReadingReason -> Observation.NoReading(reason) }
        val c = StateConverger()
        fun channel(key: String, observe: () -> Observation) = c.register(StateConverger.Channel(key, observe))

        channel("diag_usb_debugging", usbDebugging)

        channel("storage_health") {
            val health = storageHealth()
            Observation.Known(health.severity.name.lowercase(Locale.ROOT), storageHealthAttributes(health))
        }
        // An update channel withheld (ha-paneld's under a Panel Assistant lease, or the Companion's while no
        // Companion is installed) is unavailable rather than leaving a stale version behind.
        if (softwareUpdateSources != null) for (component in SoftwareComponent.entries) {
            channel(SoftwareUpdates.stateChannelKey(component)) {
                softwareUpdateInputs(component)?.let { inputs ->
                    if (SoftwareUpdates.withheld(inputs)) Observation.Unavailable
                    else known(SoftwareUpdates.stateJson(inputs))
                } ?: unknown
            }
        }
        channel("screen") {
            when (screen.observedDark()) {
                true -> {
                    lastScreenBrightness = -1
                    screenEffectiveBaseline = -1
                    known("""{"state":"OFF"}""")
                }
                false -> {
                    val level = lastScreenBrightness.takeIf { it >= 0 } ?: brightness.getCommanded().coerceAtLeast(1)
                    // Baseline the first authoritative observation too. Otherwise the first local pass
                    // misclassifies an already-lit startup as a local wake and reports a duplicate state.
                    lastScreenBrightness = level
                    known("""{"state":"ON","brightness":$level}""")
                }
                null -> unknown
            }
        }
        channel("led") {
            LedCommandPolicy.statePayload(
                LedCommandPolicy.stored(config.lastLed, config.lastLedEffect),
                ledActuationKnown,
                ledEffect.status(),
            )?.let(known) ?: Observation.Unavailable
        }
        channel("navigate") { known(config.lastNavigate.ifEmpty { "/" }) }
        channel("home_dashboard") { known(config.homeDashboard) }
        // Commandable controls report independently of old exposure preferences; Home Assistant owns their visibility.
        channel("volume") {
            known(volume.getPercent().toString())
        }
        channel("media") { media?.let { known(it.observation()) } ?: unknown }
        if (hasButtonBacklight) channel("buttons") { known(buttonBacklightState(config.lastButtonBacklight)) }
        channel("wake_on_wave") {
            if (hasProximity) known(if (config.wakeOnWave) "ON" else "OFF") else unknown
        }
        channel("auto_sleep") { known(if (config.autoSleep) "ON" else "OFF") }
        // Persisted intent, not the platform flag: the flag has other writers, and reporting an
        // observation of it would let firmware drift flip the Home Assistant switch on its own.
        channel("touch_sound") { known(if (config.touchSound) "ON" else "OFF") }
        channel("watchdog") { known(if (config.watchdogEnabled) "ON" else "OFF") }
        channel("kiosk_lock") { known(if (config.kioskLock) "ON" else "OFF") }
        channel("silence_boot_chime") { known(if (bootChime.isEnabled()) "ON" else "OFF") }
        channel("prevent_idle_dim") { known(if (config.preventIdleDim) "ON" else "OFF") }
        channel("auto_brightness") { known(if (config.autoBrightness) "ON" else "OFF") }
        channel("camera_enabled") { known(if (config.cameraEnabled) "ON" else "OFF") }
        channel("auto_sleep_activity") {
            if (!config.haExposed("auto_sleep_activity", true)) return@channel noReading(NoReadingReason.NOT_SHARED)
            val snapshot = autoSleepActivity()
            if (!config.autoSleep) noReading(NoReadingReason.TURNED_OFF)
            else if (!snapshot.policyHealthy) noReading(NoReadingReason.NOT_READY)
            else Observation.Known(if (snapshot.holdingAwake) "ON" else "OFF", autoSleepActivityProjection(snapshot).attributes)
        }
        channel("navbar") { known(config.navbarMode) }
        // The stored choice, so an Ambient flip, which changes only the effective scheme, leaves it alone.
        channel("dashboard_theme") { known(config.dashboardTheme) }
        // Prevent idle dim holds Android's screen-off timeout at "never", so the number is usable only while it is off.
        channel("screen_off_timeout") {
            if (config.preventIdleDim) return@channel Observation.Unavailable
            brightness.configuredScreenOffTimeoutMs(screen.heldSleepRecord()).takeIf { it > 0 }
                ?.let { known(screenOffTimeoutSeconds(it).toString()) } ?: unknown
        }
        channel("dashboard_idle_return") {
            if (DashboardIdleReturnPolicy.appliesTo(config.dashboardPackage)) known(config.dashboardIdleReturnMin.toString())
            else Observation.Unavailable
        }

        // A sensor with no reading says why, so Home Assistant shows it unknown rather than a stale value.
        val waiting = noReading(NoReadingReason.WAITING)
        val notShared = noReading(NoReadingReason.NOT_SHARED)
        channel("illuminance") {
            if (config.haExposed("illuminance", true)) lastIlluminance?.let { known(it.toString()) } ?: waiting
            else notShared
        }
        channel("proximity") {
            if (!config.haExposed("proximity", true)) notShared
            else if (learnedProximityEligible()) proximityPublication.near?.let { known(if (it) "ON" else "OFF") } ?: waiting
            else waiting
        }
        channel("proximity_level") {
            if (!config.haExposed("proximity_level", true)) notShared
            else if (learnedProximityEligible()) proximityPublication.level?.let { known(it.toString()) } ?: waiting
            else waiting
        }
        channel("temperature") {
            if (config.haExposed("temperature", true)) {
                lastTemperature?.let { known(String.format(java.util.Locale.US, "%.1f", it)) } ?: waiting
            } else notShared
        }
        channel("humidity") {
            if (config.haExposed("humidity", true)) lastHumidity?.let { known(Math.round(it).toString()) } ?: waiting
            else notShared
        }

        for (key in diagKeys()) channel(key) {
            val exposedByDefault = requireNotNull(SettingsRegistry.spec(key)).haExposedByDefault
            val observation = diagnosticObservation(config.haExposed(key, exposedByDefault), diagValue(key)) {
                diagNoReadingReason(key)
            }
            // The integer alone cannot say "this is a floor", so the saturation flag rides with it as an
            // attribute; without it Home Assistant records a capped 200 as an exact measurement.
            if (key == "diag_wifi_outages_24h" && observation is Observation.Known) {
                wifiOutages()?.let { observation.copy(attributes = wifiOutageAttributes(it)) } ?: observation
            } else observation
        }
        return c
    }

    /** Every admitted observation reaches [sink], independently of any transport's delivery capacity. */
    fun addStateSink(sink: StateSink) = converger.addSink(sink)

    /** Hand transient events (button presses) to the native transport; it never blocks the caller. */
    fun addEventSink(sink: (channel: String, event: String) -> Unit) { nativeEventSink = sink }


    fun reconcile(key: String, admit: () -> Boolean = { true }) = converger.reconcile(key, admit)

    fun reconcileAll() = converger.reconcileAll()

    /** The registered channels, which grow as hardware capabilities are confirmed. */
    fun stateChannelKeys(): Set<String> = converger.keys()

    /**
     * The native describe's view of [stateChannelKeys]: a channel whose [hardwareAvailability] is settled
     * false is stated unsupported instead of described. An unsettled one stays described, and its observer reports no
     * reading with a reason until it has one, or is omitted if [PROVEN_ONLY_CHANNELS].
     */
    fun nativeChannelShape(): PanelAssistantChannelShape {
        val learned = runCatching(learnedProximityState).getOrNull()
        val keys = converger.keys().filterNot { it in PROVEN_ONLY_CHANNELS && hardwareAvailability(it, learned) == null }
        val (unsupported, served) = keys.partition { hardwareAvailability(it, learned) == false }
        val actions = listOf("reload" to system.canReloadDashboard(config.dashboardPackage), "reboot" to system.canReboot(), "android_settings" to true, "launcher" to true, "dashboard" to true,
            // The built-in dashboard goes back to its own previous page with no privileged route.
            "back" to (canBack() || system.isBuiltinDashboardTarget(config.dashboardPackage)), "recents" to true)
        // A panel profiled without physical keys never fires a key event, so the entity would only sit unknown.
        val noKeys = buttonKeys == PanelButtonKeys.NONE
        val events = listOf(BUTTON_EVENT_CHANNEL).filter { !noKeys && (buttonsEnabled || buttonKeys == PanelButtonKeys.PROFILED) }
        return PanelAssistantChannelShape(
            served + actions.filter { it.second }.map { it.first } + events,
            unsupported + actions.filterNot { it.second }.map { it.first } + PanelAssistantChannelCatalog.RETIRED_CHANNELS +
                listOf(BUTTON_EVENT_CHANNEL).filter { noKeys },
        )
    }

    /**
     * Whether this panel can fill a hardware-gated entity; the native describe states the channel
     * unsupported when false. Null when [key] is not hardware-gated here, or when its answer is not
     * settled; [learnedProximity] comes from the live, settled source.
     */
    fun hardwareAvailability(key: String, learnedProximity: Boolean?): Boolean? = when (key) {
        "temperature" -> hasTemperature
        "humidity" -> hasHumidity
        MEDIA_CHANNEL -> media != null
        "camera_enabled" -> hasCamera()
        "proximity", "proximity_level" -> learnedProximity
        "illuminance" -> lightChannel()
        // LedFactory returns the no-op controller only for a profile declaring no LED. A declared LED stays
        // described even when its probe fails: a daemon-driven one reads false until the helper answers.
        "led" -> if (led is NoOpLedController) false else null
        SoftwareUpdates.stateChannelKey(SoftwareComponent.COMPANION) ->
            softwareUpdateInputs(SoftwareComponent.COMPANION)
                ?.takeUnless { it.presenceUnknown }
                ?.let { !SoftwareUpdates.companionAbsent(it) }
        else -> null
    }

    /**
     * Add newly confirmed hardware channels without replacing the runtime. The possible shape never
     * shrinks, while each observer checks the latest live snapshot and reports unavailable rather than
     * presenting a transiently unreachable controller as healthy.
     */
    fun ensureCapabilityChannels(snapshot: Capabilities?): CapabilityObservation =
        synchronized(capabilityChannelLock) {
            val live = snapshot ?: Capabilities(
                zigbeePresent = zigbee.present(),
                cpuGovernors = cpu.available(),
                networkAdb = adb.available(),
                relays = relay.count(),
                buttonLeds = relay.ledCount(),
            )
            val observation = capabilityShape.observe(live)
            // capabilityChannelLock serializes this compound keys/register operation; StateConverger
            // independently synchronizes each registry mutation and observation snapshot.
            val registered = converger.keys()
            fun known(payload: String) = Observation.Known(payload)
            fun register(key: String, observe: () -> Observation) {
                if (key !in registered) converger.register(StateConverger.Channel(key, observe))
            }

            if (observation.possible.networkAdb) register("network_adb") {
                if (!capabilityShape.current().live.networkAdb) known("unknown")
                else known(if (adb.isPersisted()) "ON" else "OFF")
            }
            if (observation.possible.zigbee) register("zigbee_router") {
                if (!capabilityShape.current().live.zigbee) known("unknown")
                else if (config.zigbeeRouterConfigured && !config.zigbeeRouterEnabled) known("OFF")
                else known(if (zigbee.running()) "ON" else "OFF")
            }
            if (observation.possible.zigbee) register("zigbee_gateway_health") {
                if (!capabilityShape.current().live.zigbee) Observation.Unavailable
                else zigbeeHealth().let {
                    when {
                        it.state != ZigbeeHealthState.UNKNOWN -> Observation.Known(it.state.wireValue, it.attributesJson())
                        it.observedAtMs == 0L -> Observation.NoReading(NoReadingReason.WAITING)
                        else -> Observation.NoReading(NoReadingReason.NOT_READABLE)
                    }
                }
            }
            if (observation.possible.cpu) register("cpu_governor") {
                if (!capabilityShape.current().live.cpu) known("unknown")
                else cpu.currentTier()?.let(::known) ?: known("unknown")
            }
            for (n in 1..observation.possible.relays) {
                register("relay$n") {
                    if (capabilityShape.current().live.relays < n) known("unknown")
                    else relay.read(n)?.let { known(if (it) "ON" else "OFF") } ?: known("unknown")
                }
            }
            for (n in 1..observation.possible.buttonLeds) {
                register("button_led$n") {
                    if (capabilityShape.current().live.buttonLeds < n) known("unknown")
                    else relay.ledRead(n - 1)?.let { known(if (it) "ON" else "OFF") } ?: known("unknown")
                }
            }
            observation
        }

    /**
     * The service-owned startup step: restore the outputs the hardware loses on reboot through their
     * existing actuation, and start reporting button presses, whether or not any transport is up.
     */
    fun start() {
        lifecycle.runIfOpen(Unit) {
            // The local pass reports later volume and governor changes against the values the panel starts with.
            runCatching {
                lastPublishedVolume = volume.getPercent()
                lastPublishedGovTier = cpu.currentTier()
            }.onFailure { Log.w(TAG, "startup volume and CPU governor baseline failed", it) }
            runCatching { reapplyStoredLed() }
                .onFailure { Log.w(TAG, "startup LED restoration failed", it) }
            runCatching { restoreButtonBacklight() }
                .onFailure { Log.w(TAG, "startup button backlight restoration failed", it) }
            if (buttonSubscription == null) {
                buttonSubscription = ButtonBus.subscribe { event -> publishButton(event) }
            }
        }
    }

    /** Re-apply the stored button backlight level, which the hardware loses on reboot. */
    fun restoreButtonBacklight() {
        if (hasButtonBacklight) {
            config.lastButtonBacklight.takeIf { it >= 0 }?.let { helperSend(buttonBacklightCommand(it, buttonBacklightTransfer)) }
        }
    }

    /** Restore persisted LED intent through acknowledged actuation after startup, reconnect or a camera hold.
     * Persisted intent includes commands refused while the camera owned the output. */
    fun reapplyStoredLed() = actuateLed { LedCommandPolicy.stored(config.lastLed, config.lastLedEffect) }

    /**
     * Drive the LED to [desired] and report it. Observation authority is cleared before [desired] runs, so
     * a value it persists is never reported as confirmed until this write succeeded.
     */
    fun actuateLed(desired: () -> LedCommandPolicy.Desired): Boolean {
        ledActuationKnown = false
        val target = desired()
        ledActuationKnown = if (target.on && target.effect != null) {
            ledEffect.start(target.effect, target.red, target.green, target.blue, target.brightness)
        } else if (target.on) {
            ledEffect.setSolid(
                target.red * target.brightness / 255,
                target.green * target.brightness / 255,
                target.blue * target.brightness / 255,
            )
        } else {
            // Force the hardware off too — the LED can power up to a default on reboot, so reporting OFF
            // without driving it leaves HA and the physical LED disagreeing (seen on rk3576).
            ledEffect.setOff()
        }
        reconcile("led")
        return ledActuationKnown
    }

    /**
     * Resolve this panel's HA device-settings URL. A configured built-in renderer is authoritative: use
     * its exact endpoint and current access token, even when another transport's credentials belong to
     * another HA instance. Without one there is nothing to resolve.
     * Legacy cache entries have no [Config.haDeviceLinkTarget], so an upgrade immediately repairs them
     * rather than trusting their old random registry id for another 6h.
     */
    fun maybeResolveHaLink() {
        lifecycle.runIfOpen(Unit) admission@{
            if (haLinkResolutionThread.get() != null) return@admission
            lateinit var worker: Thread
            worker = Thread {
                try {
                    val nativeBase = config.haUrl.trim().trimEnd('/')
                    if (nativeBase.isBlank()) return@Thread
                    // The Panel Assistant entry names this panel's device; without one there is nothing to link.
                    val entryId = HaConnectionRoutes.panelAssistantEntryId(config) ?: return@Thread
                    val target = HaLink.resolutionTarget(nativeBase, entryId)
                    if (config.haDeviceLinkIsFresh(target, System.currentTimeMillis(), HA_LINK_TTL_MS)) return@Thread
                    val stillCurrent = {
                        lifecycle.isOpen() && config.haUrl.trim().trimEnd('/') == nativeBase &&
                            HaConnectionRoutes.panelAssistantEntryId(config) == entryId
                    }
                    val auth = HaCredentialManager.resolve(config, stillCurrent = stillCurrent)
                    val token = auth.session?.accessToken ?: return@Thread
                    val route = auth.route ?: return@Thread
                    val familyPolicy = io.panelassistant.android.util.AddressFamilyPolicy.fromConfig(config.addressFamily)
                    val link = HaLink.resolveWithAccessToken(
                        route.url, token, entryId,
                        preferIpv4 = familyPolicy.initialPreferIpv4,
                        ipv4Only = familyPolicy.ipv4Only,
                    ) ?: return@Thread
                    lifecycle.runIfOpen(Unit) {
                        if (stillCurrent() && HaConnectionRoutes.isCurrent(config, route)) config.setHaDeviceUrl(link, target)
                    }
                } finally {
                    haLinkResolutionThread.compareAndSet(worker, null)
                }
            }.apply { isDaemon = true; name = "ha-device-link" }
            if (!haLinkResolutionThread.compareAndSet(null, worker)) return@admission
            try {
                worker.start()
            } catch (failure: Throwable) {
                haLinkResolutionThread.compareAndSet(worker, null)
                throw failure
            }
        }
    }

    /** Whether this generation still admits work. */
    fun isOpen(): Boolean = lifecycle.isOpen()

    // ---- local events ----

    private fun publishButton(event: String) {
        lifecycle.runIfOpen(Unit) {
            // The event type's wire code is its key name in lower case.
            runCatching { nativeEventSink?.invoke(BUTTON_EVENT_CHANNEL, event.lowercase(Locale.ROOT)) }
        }
    }

    /** Report screen=ON after a LOCAL wake (e.g. wake-on-wave), so the screen light tracks reality. */
    fun publishScreenOn() {
        dispatchStateWork { publishScreenBrightness(brightness.getCommanded().coerceAtLeast(1)) }
    }

    /** Reconcile the existing physical screen light after a service-owned automatic transition. */
    fun publishScreenState() {
        dispatchStateWork {
            if (screen.isIntendedOff()) publishScreenOff()
            else publishScreenBrightness(brightness.getCommanded().coerceAtLeast(1))
        }
    }

    /** Report the current volume after a LOCAL change (e.g. navbar Volume ±). */
    fun publishVolume() {
        dispatchStateWork { reconcile("volume") }
    }

    /** A volume command settled: baseline the local pass on it and report it. */
    fun noteVolumeCommanded() {
        volume.getPercent().let { lastPublishedVolume = it }
        reconcile("volume")
    }

    /** Re-report the camera surface after a change made outside a command (the Configure page, a bundle
     *  import, provisioning). */
    fun publishCameraState() {
        dispatchStateWork {
            reconcile("camera_enabled")
        }
    }

    /** An exposure pip changed: re-report its channel so Panel Assistant drops a hidden value at once. */
    fun publishExposureChanges(changedKeys: Set<String>) {
        val channels = changedKeys.mapNotNull { SettingsRegistry.parseExposure(it)?.ha?.channel }
        if (channels.isNotEmpty()) dispatchStateWork { channels.forEach { reconcile(it) } }
    }

    fun publishZigbeeRouterState() {
        lifecycle.runIfOpen(Unit) { reconcile("zigbee_router") }
    }

    fun publishZigbeeHealth() {
        lifecycle.runIfOpen(Unit) {
            reconcile("zigbee_gateway_health")
        }
    }

    /** Re-project the shared authority after a startup/daily observation or immediate database failure. */
    fun publishStorageHealth() {
        lifecycle.runIfOpen(Unit) {
            reconcile("storage_health")
        }
    }

    fun publishLight(lux: Int) {
        lifecycle.runIfOpen(Unit) {
            lastIlluminance = lux
            reconcile("illuminance")
        }
    }

    /** Report fleet-normalized proximity. Null means the learner cannot currently make a trustworthy
     * statement; the entity is taken unavailable and what was stated before is cleared. */
    fun publishProximity(
        near: Boolean?,
        normalizedLevel: Int?,
        reportMask: Int = ProximityReportGate.BOTH,
    ) {
        val nextNear = near
        lifecycle.runIfOpen(Unit) publish@{
            proximityPublication.serialized proximity@{
                if (!learnedProximityEligible()) {
                    clearProximityStateLocked()
                    return@proximity
                }
                val level = normalizedLevel?.coerceIn(0, 100)
                val available = nextNear != null && level != null
                val admitted = admit(nextNear, level, reportMask)
                val availabilityChanged = admitted and ProximityPublicationState.AVAILABILITY_CHANGED != 0
                if (!available) return@proximity
                if (admitted and ProximityReportGate.PRESENCE != 0) {
                    reconcile("proximity")
                }
                if (admitted and ProximityReportGate.LEVEL != 0) {
                    reconcile("proximity_level")
                }
            }
        }
    }

    /** Run [block] in proximity's serialization with whether learned proximity is currently available. */
    fun <T> withProximity(block: (available: Boolean) -> T): T = proximityPublication.serialized {
        block(learnedProximityEligible() && available == true)
    }

    /** The light answer changed: the first reading describes the illuminance entity, and a never-reported
     *  sensor settling absent states it unsupported. */
    fun notifyLightAvailabilityChanged() {
        lifecycle.runIfOpen(Unit) {
            capabilities.invalidate()
        }
    }

    /** Apply an empirical mode-change notification without accepting truth from its producer. */
    fun notifyLearnedProximityChanged() {
        lifecycle.runIfOpen(Unit) {
            capabilities.invalidate()
            refreshLearnedProximityFromAuthority(
                admitted = true,
                eligible = learnedProximityEligibility,
                clearIneligibleState = ::clearProximityState,
            )
        }
    }

    private fun learnedProximityEligible(): Boolean =
        runCatching(learnedProximityEligibility).getOrDefault(false)

    private fun clearProximityState() {
        proximityPublication.serialized { clearProximityStateLocked() }
    }

    private fun ProximityPublicationState.clearProximityStateLocked() {
        clear()
    }

    // Rounded at report (1dp temp, integer humidity) so precision wobble can't create recorder rows.
    fun publishTemperature(celsius: Float) {
        lifecycle.runIfOpen(Unit) {
            lastTemperature = celsius
            reconcile("temperature")
        }
    }

    fun publishHumidity(percent: Float) {
        lifecycle.runIfOpen(Unit) {
            lastHumidity = percent
            reconcile("humidity")
        }
    }

    /** Re-sample and report the policy entity after a decision, health, source or suppression change. */
    fun publishAutoSleepActivity() {
        dispatchStateWork(::publishAutoSleepActivityNow)
    }

    fun publishAutoSleepActivityNow() {
        reconcile("auto_sleep_activity")
    }

    /** The media player's state changed: re-observe the `media` channel. */
    fun mediaChanged() {
        dispatchStateWork { reconcile(MEDIA_CHANNEL) }
    }

    /** Re-evaluate both update channels: catalog refreshes, install start and finish, package changes. */
    fun publishSoftwareUpdates() {
        dispatchStateWork(::reconcileSoftwareUpdatesNow)
    }

    /** [publishSoftwareUpdates] on the caller's thread, for a command that must report its own outcome. */
    fun reconcileSoftwareUpdatesNow() = SoftwareComponent.entries.forEach {
        reconcile(SoftwareUpdates.stateChannelKey(it))
    }

    fun softwareUpdateInputs(component: SoftwareComponent): SoftwareUpdateInputs? {
        val sources = softwareUpdateSources ?: return null
        val snapshot = try {
            sources()
        } catch (e: Exception) {
            Log.w(TAG, "update entity inputs unavailable", e)
            return null
        }
        return snapshot.inputs(component, capabilities.cached()?.canInstallVerifiedApps == true)
    }

    /** Report screen=OFF and reset the brightness baseline used by local-state reconciliation. */
    fun publishScreenOff() {
        lastScreenBrightness = -1
        screenEffectiveBaseline = -1
        reconcile("screen")
    }

    /** Report screen=ON at [level] and remember it as the last-reported brightness (the reconcile
     *  in [syncLocalState] compares the effective backlight against this). */
    fun publishScreenBrightness(level: Int) {
        lastScreenBrightness = level
        screenEffectiveBaseline = -1   // re-capture on the next tick, after the framework settles
        reconcile("screen")
    }

    // ---- local observation ----

    private val localObservationPending = AtomicBoolean(false)

    /** One queued/running local pass per generation, independent of any transport. */
    fun requestLocalObservation(stillCurrent: () -> Boolean) {
        if (!lifecycle.isOpen() || !localObservationPending.compareAndSet(false, true)) return
        StateConverger.dispatch {
            try {
                lifecycle.runIfOpen(Unit) {
                    if (stillCurrent()) runCatching { syncLocalState() }
                }
            } finally {
                localObservationPending.set(false)
            }
        }
    }

    /** Serialize the accepted hello's full observation before native full_begin. */
    suspend fun observeForNativeHello(stillCurrent: () -> Boolean): Boolean =
        suspendCancellableCoroutine { waiting ->
            StateConverger.dispatch {
                val observed = lifecycle.runIfOpen(false) {
                    if (!stillCurrent()) false else runCatching { syncLocalState(); stillCurrent() }.getOrDefault(false)
                }
                if (waiting.isActive) waiting.resume(observed)
            }
        }

    /**
     * Local-state observation: panel values can change OUTSIDE ha-paneld's API (auto-brightness and
     * any local app writing the setting, hardware volume keys, vendor firmware dimming the backlight
     * node), and HA must track them without being flooded. One pass per tick per channel: report only
     * when the value differs from the LAST REPORTED beyond the channel's deadband AND has settled (change
     * since the previous tick within the settle band) — fast oscillation reports nothing until it stops,
     * a slow ramp reports at most once per tick. Runs on the convergence pump (su-safe, off-main).
     */
    private fun syncLocalState() {
        runCatching {
            val capabilityObservation = ensureCapabilityChannels(
                capabilities.snapshot(CAPABILITY_RECOVERY_PROBE_MS),
            )
            capabilityObservation.changedKeys().forEach {
                reconcile(it)
            }
            onCapabilitiesObserved?.invoke(capabilityObservation)
        }
        val observedOffGeneration = screen.currentOffGeneration()
        val physicallyDark = screen.observedDark()
        if (physicallyDark == true) screen.noteObservedDark(observedOffGeneration)
        val becameOff = physicallyDark == true && lastScreenBrightness >= 0
        if (becameOff) {
            Log.i(TAG, "screen became physically dark outside the panel's API — syncing OFF")
            syncLog.record(SystemClock.elapsedRealtime(), "screen →OFF (physical)")
            publishScreenOff()
        } else if (physicallyDark == false && lastScreenBrightness < 0) {
            val reconciled = !screen.isIntendedOff() || screen.reconcileObservedLit(observedOffGeneration)
            if (reconciled) {
                val level = brightness.getCommanded().coerceAtLeast(1)
                Log.i(TAG, "screen became physically lit outside the panel's API — syncing ON")
                syncLog.record(SystemClock.elapsedRealtime(), "screen →ON (physical)")
                publishScreenBrightness(level)
            }
        }

        // Channel: commanded brightness (Android setting — the scale HA commands in). Catches
        // auto-brightness and any local actor. Skipped while the screen is deliberately off.
        if (!becameOff && !screen.isIntendedOff() && lastScreenBrightness >= 0
        ) {
            val cur = brightness.getCommanded()
            if (settled(cur, prevTickBrightness, lastScreenBrightness, deadband = 3)) {
                Log.i(TAG, "local brightness changed outside the panel's API: $lastScreenBrightness -> $cur — syncing")
                syncLog.record(SystemClock.elapsedRealtime(), "brightness $lastScreenBrightness→$cur")
                publishScreenBrightness(cur.coerceAtLeast(1))
            }
            prevTickBrightness = cur

            // Channel: effective backlight vs its post-command baseline — ONLY when the commanded
            // setting hasn't moved (else the channel above owns it). Catches firmware node-dims. On a
            // panel whose framework maps the setting through its own curve the node scale differs, so
            // drift is back-mapped proportionally; a profile curve already reads back on the HA scale.
            if (cur == lastScreenBrightness || kotlin.math.abs(cur - lastScreenBrightness) <= 3) {
                val eff = brightness.getBrightness()
                if (eff >= 0) {
                    val base = screenEffectiveBaseline
                    if (base < 0) {
                        screenEffectiveBaseline = eff   // command settled — remember its hardware level
                    } else if (kotlin.math.abs(eff - base) > SCREEN_DRIFT) {
                        val reported = reportedBacklightDrift(brightness.curved, lastScreenBrightness, eff, base)
                        Log.i(TAG, "screen backlight moved externally: baseline $base -> $eff — reporting $reported")
                        syncLog.record(SystemClock.elapsedRealtime(), "backlight →$reported (firmware dim)")
                        publishScreenBrightness(reported)
                        screenEffectiveBaseline = eff
                    }
                }
            }
        }

        // Channel: volume — hardware keys / local apps change it outside the panel's API.
        runCatching {
            val cur = volume.getPercent()
            if (settled(cur, prevTickVolume, lastPublishedVolume, deadband = 1)) {
                Log.i(TAG, "local volume changed outside the panel's API: $lastPublishedVolume -> $cur — syncing")
                syncLog.record(SystemClock.elapsedRealtime(), "volume $lastPublishedVolume→$cur")
                lastPublishedVolume = cur
                reconcile("volume")
            }
            prevTickVolume = cur
        }

        // Channel: CPU governor — a thermal daemon or another app can change the scaling governor;
        // currentTier() reads the LIVE sysfs governor. Categorical (not numeric) — report on any change vs
        // the baseline, no deadband. The baseline is set at startup, so this fires only on an external change.
        runCatching {
            if (capabilityShape.current().live.cpu) {
                val tier = cpu.currentTier()
                if (tier != null && lastPublishedGovTier != null && tier != lastPublishedGovTier) {
                    Log.i(TAG, "cpu governor changed outside the panel's API: $lastPublishedGovTier -> $tier — syncing")
                    syncLog.record(SystemClock.elapsedRealtime(), "cpu_governor $lastPublishedGovTier→$tier")
                    lastPublishedGovTier = tier
                    reconcile("cpu_governor")
                }
            }
        }

        // Diagnostic sensors — refresh each exposed one.
        runCatching { syncDiagnostics() }

        // Update channels: a Companion installed or removed outside ha-paneld or a changed install
        // route surface here within one tick.
        runCatching { reconcileSoftwareUpdatesNow() }

        // Architectural safety net: audit every registered state channel from its declared authority.
        runCatching { converger.reconcileAll() }
    }

    // Current string value for a diagnostic sensor, or null when unavailable on this panel.
    private fun diagValue(key: String): String? = when (key) {
        "diag_ip" -> PanelMetrics.shared.ipAddress()
        "diag_cpu" -> PanelMetrics.shared.systemSnapshot().cpuOverall?.toString()
        "diag_memory" -> PanelMetrics.shared.systemSnapshot().memPercent?.toString()
        "diag_soc_temp" -> PanelMetrics.shared.systemSnapshot().socTempC?.let { String.format(java.util.Locale.US, "%.1f", it) }
        "diag_boot" -> PanelMetrics.shared.bootTime()
        "diag_wifi_ssid" -> wifiDiagnostics(WifiDiagnosticDemand(
            ssid = true,
            privilegedRoute = capabilityShape.current().live.networkAdb,
        )).ssid
        "diag_wifi_rssi" -> wifiDiagnostics(WifiDiagnosticDemand(
            rssi = true,
            privilegedRoute = capabilityShape.current().live.networkAdb,
        )).rssiDbm?.toString()
        // Rolling outage counts keep their last reported value through a Wi-Fi dropout —
        // that dropout is exactly what they exist to report — so they are not WIFI_DIAGNOSTIC_KEYS.
        "diag_wifi_outages_24h" -> wifiOutages()?.last24h?.toString()
        // Room climate — apply the calibration offset to temperature; humidity is reported whole-percent.
        "room_temp" -> PanelMetrics.shared.roomClimate()?.tempC?.let { String.format(java.util.Locale.US, "%.1f", it + config.roomTempOffsetC) }
        "room_humidity" -> PanelMetrics.shared.roomClimate()?.humidityPct?.let { String.format(java.util.Locale.US, "%.0f", it) }
        else -> null
    }

    /** Why diagnostic [key] has no value right now. */
    private fun diagNoReadingReason(key: String): NoReadingReason = when (key) {
        // The first tick has no /proc/stat baseline to measure against.
        "diag_cpu" -> if (PanelMetrics.shared.systemSnapshot().cpuReadable) NoReadingReason.WAITING else NoReadingReason.NOT_READABLE
        "diag_wifi_ssid", "diag_wifi_rssi" ->
            if (wifiDiagnostics(WifiDiagnosticDemand()).active) NoReadingReason.NOT_READABLE else NoReadingReason.NOT_ON_WIFI
        "diag_wifi_outages_24h" -> NoReadingReason.WAITING
        else -> NoReadingReason.NOT_READABLE
    }

    /** The diagnostic channels this hardware registers. */
    fun diagKeys(): List<String> = if (hasCht8305) DIAG_KEYS + ROOM_KEYS else DIAG_KEYS

    /** Per-tick refresh of the exposed diagnostic sensors. */
    private fun syncDiagnostics() {
        for (key in diagKeys()) {
            val exposedByDefault = requireNotNull(SettingsRegistry.spec(key)).haExposedByDefault
            if (!config.haExposed(key, exposedByDefault)) continue
            reconcile(key)
        }
    }

    /** Sync predicate — delegates to the pure [SyncGate] (unit-tested). */
    private fun settled(cur: Int, prevTick: Int, published: Int, deadband: Int): Boolean =
        SyncGate.settled(cur, prevTick, published, deadband, SETTLE_BAND)

    /** Recent local-state sync events (newest first) for the info page + /diag. */
    fun recentSyncEvents(): List<String> = syncLog.recent(SystemClock.elapsedRealtime())

    /** Close admission, then drain every observer and the link worker within [deadline]. */
    fun stop(deadline: MonotonicDeadline): Boolean {
        lifecycle.closeAdmission()
        converger.close()
        haLinkResolutionThread.get()?.interrupt()
        val lifecycleDrained = lifecycle.awaitDrained(deadline)
        val stateDrained = converger.closeAndDrain(deadline)
        val haLinkDrained = haLinkResolutionThread.get().interruptAndJoin(deadline)
        buttonSubscription?.close()
        buttonSubscription = null
        val drained = lifecycleDrained && stateDrained && haLinkDrained
        if (!drained) {
            Log.w(TAG, "state core did not drain (lifecycle=$lifecycleDrained state=$stateDrained haLink=$haLinkDrained)")
        }
        return drained
    }

    companion object {
        private const val TAG = "ha-paneld/state"
        internal const val MEDIA_CHANNEL = "media"
        /** The hardware button event channel: no state, only `report_event`. */
        internal const val BUTTON_EVENT_CHANNEL = "button"
        /**
         * Described only once proven (maintainer, 2026-10-06): a light sensor that has never reported is left out until its
         * first reading, because its answer can settle absent seconds after a hello, and describing it first would end that
         * session and create then remove its entity on every start.
         */
        private val PROVEN_ONLY_CHANNELS = setOf("illuminance")
        private const val HA_LINK_TTL_MS = 6 * 3_600_000L // re-resolve the "Open in HA" link at most every 6h
        private const val SCREEN_DRIFT = 2   // reconcile threshold (0-255 scale) — ignore rounding jitter
        private const val SETTLE_BAND = 6    // "still moving" if the value shifted more than this since last tick
        // Diagnostic sensors (all default local-only).
        internal val DIAG_KEYS = listOf(
            "diag_ip", "diag_cpu", "diag_memory", "diag_soc_temp", "diag_boot",
            "diag_wifi_ssid", "diag_wifi_rssi", "diag_wifi_outages_24h",
        )
        // Room climate sensors — available only on panels with a CHT8305 (see hasCht8305).
        internal val ROOM_KEYS = listOf("room_temp", "room_humidity")
        private const val CAPABILITY_RECOVERY_PROBE_MS = 30_000L
    }
}

internal val WIFI_DIAGNOSTIC_KEYS = setOf("diag_wifi_ssid", "diag_wifi_rssi")

/** Whether the published count is a total or a known lower bound, so HA never records a floor as exact. */
internal fun wifiOutageAttributes(counts: WifiOutageCounts): String =
    JSONObject().put("is_lower_bound", counts.saturated).toString()

/** Path-free, bounded Home Assistant attributes derived from the shared immutable snapshot. */
internal fun storageHealthAttributes(snapshot: StorageHealthSnapshot): String {
    val probeRan = snapshot.checkedAtMillis > 0L
    val capacityKnown = probeRan && snapshot.totalBytes > 0L
    val sqliteMetricsKnown = probeRan && snapshot.pageSizeBytes > 0L && snapshot.pageCount > 0L
    val databaseFilesBytes = listOf(
        snapshot.mainDatabaseBytes,
        snapshot.walBytes,
        snapshot.sidecarBytes,
    ).fold(0L) { total, value ->
        if (value > Long.MAX_VALUE - total) Long.MAX_VALUE else total + value
    }
    fun valueOrNull(value: Any?, known: Boolean = probeRan): Any? = if (known) value else JSONObject.NULL
    return JSONObject()
        .put("storage_pressure", snapshot.pressureSeverity.name.lowercase(Locale.ROOT))
        .put("failure_category", snapshot.databaseFailureKind?.name?.lowercase(Locale.ROOT) ?: JSONObject.NULL)
        .put("failure_operation", snapshot.databaseFailureOperationLabel ?: JSONObject.NULL)
        .put("auto_vacuum", snapshot.autoVacuumMode.name.lowercase(Locale.ROOT))
        .put("usable_bytes", valueOrNull(snapshot.usableBytes, capacityKnown))
        .put("total_bytes", valueOrNull(snapshot.totalBytes, capacityKnown))
        .put("used_percent", if (capacityKnown) snapshot.usedPercent ?: JSONObject.NULL else JSONObject.NULL)
        .put("main_database_bytes", valueOrNull(snapshot.mainDatabaseBytes))
        .put("wal_bytes", valueOrNull(snapshot.walBytes))
        .put("database_sidecar_bytes", valueOrNull(snapshot.sidecarBytes))
        .put("database_files_bytes", valueOrNull(databaseFilesBytes))
        .put("page_size_bytes", valueOrNull(snapshot.pageSizeBytes, sqliteMetricsKnown))
        .put("page_count", valueOrNull(snapshot.pageCount, sqliteMetricsKnown))
        .put("freelist_count", valueOrNull(snapshot.freelistCount, sqliteMetricsKnown))
        .put("schema_version", valueOrNull(snapshot.schemaVersion, sqliteMetricsKnown && snapshot.schemaVersion > 0))
        .put("quick_check", snapshot.quickCheck.name.lowercase(Locale.ROOT))
        .put("checked_at_epoch_seconds", valueOrNull(snapshot.checkedAtMillis / 1_000L))
        .toString()
}

/** A diagnostic's observation: its value, or no reading with the reason [noValue] gives for a missing one. */
internal fun diagnosticObservation(
    exposed: Boolean,
    value: String?,
    noValue: () -> NoReadingReason,
): io.panelassistant.android.state.StateConverger.Observation = when {
    !exposed -> io.panelassistant.android.state.StateConverger.Observation.NoReading(NoReadingReason.NOT_SHARED)
    value == null -> io.panelassistant.android.state.StateConverger.Observation.NoReading(noValue())
    else -> io.panelassistant.android.state.StateConverger.Observation.Known(value)
}

internal enum class LearnedProximityRefresh { SKIPPED, ELIGIBLE, INELIGIBLE }

/** Service-owned policy projection for HA's binary activity entity. The entity is deliberately
 * binary: HA's recorder/history chart is the useful historical visualization, while attributes retain
 * only bounded categorical context for diagnosing why the policy is active or unavailable. */
internal data class AutoSleepActivitySnapshot(
    val holdingAwake: Boolean = false,
    val policyHealthy: Boolean = false,
    val reason: String = "unavailable",
    val learnedDelay: String = "unknown",
    val sourceCount: Int = 0,
    val phase: String = "unavailable",
    val manualSuppression: Boolean = false,
)

internal data class AutoSleepActivityProjection(
    val state: String,
    val attributes: String,
    val availability: String,
)

internal fun autoSleepActivityProjection(snapshot: AutoSleepActivitySnapshot): AutoSleepActivityProjection {
    fun category(raw: String): String = raw.trim().lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^a-z0-9_-]+"), "_")
        .trim('_')
        .take(48)
        .ifBlank { "unknown" }
    return AutoSleepActivityProjection(
        state = if (snapshot.holdingAwake) "ON" else "OFF",
        attributes = "{" +
            "\"reason\":${Json.str(category(snapshot.reason))}," +
            "\"learned_delay\":${Json.str(category(snapshot.learnedDelay))}," +
            "\"source_count\":${snapshot.sourceCount.coerceAtLeast(0)}," +
            "\"phase\":${Json.str(category(snapshot.phase))}," +
            "\"manual_suppression\":${snapshot.manualSuppression}" +
            "}",
        availability = if (snapshot.policyHealthy) "online" else "offline",
    )
}

/** A mode-change signal carries no truth. The admitted runtime generation samples the service-owned
 * authority immediately before it clears stale state and re-observes the channel. */
internal fun refreshLearnedProximityFromAuthority(
    admitted: Boolean,
    eligible: () -> Boolean,
    clearIneligibleState: () -> Unit,
): LearnedProximityRefresh {
    if (!admitted) return LearnedProximityRefresh.SKIPPED
    val current = runCatching(eligible).getOrDefault(false)
    if (!current) clearIneligibleState()
    return if (current) LearnedProximityRefresh.ELIGIBLE else LearnedProximityRefresh.INELIGIBLE
}

/**
 * The Home Assistant level to publish when the effective backlight moved from [baseline] to [effective]
 * without a command. A profile curve's reads are already on the Home Assistant scale ([curved]), so they are
 * published as read; otherwise the node may sit on a framework curve's scale and is back-mapped in proportion
 * to the last published [commanded] level.
 */
internal fun reportedBacklightDrift(curved: Boolean, commanded: Int, effective: Int, baseline: Int): Int =
    if (curved) {
        effective.coerceIn(1, 255)
    } else {
        (commanded.toLong() * effective / baseline.coerceAtLeast(1)).toInt().coerceIn(1, 255)
    }


internal data class CapabilityChannels(
    val zigbee: Boolean = false,
    val cpu: Boolean = false,
    val networkAdb: Boolean = false,
    val relays: Int = 0,
    val buttonLeds: Int = 0,
) {
    fun keys(): Set<String> = buildSet {
        if (zigbee) add("zigbee_router")
        if (cpu) add("cpu_governor")
        if (networkAdb) add("network_adb")
        for (n in 1..relays) add("relay$n")
        for (n in 1..buttonLeds) add("button_led$n")
    }
}

internal data class CapabilityObservation(
    val possible: CapabilityChannels,
    val live: CapabilityChannels,
    val previousLive: CapabilityChannels,
    val grew: Boolean,
) {
    fun changedKeys(): Set<String> = buildSet {
        if (live.zigbee != previousLive.zigbee && possible.zigbee) add("zigbee_router")
        if (live.cpu != previousLive.cpu && possible.cpu) add("cpu_governor")
        if (live.networkAdb != previousLive.networkAdb && possible.networkAdb) add("network_adb")
        if (live.relays != previousLive.relays) {
            for (n in minOf(live.relays, previousLive.relays) + 1..maxOf(live.relays, previousLive.relays)) {
                add("relay$n")
            }
        }
        if (live.buttonLeds != previousLive.buttonLeds) {
            for (n in minOf(live.buttonLeds, previousLive.buttonLeds) + 1..
                maxOf(live.buttonLeds, previousLive.buttonLeds)
            ) {
                add("button_led$n")
            }
        }
    }
}

/**
 * Separates monotonic channel shape from the latest live probe. Once hardware has been confirmed its
 * channel identity stays stable; a later unavailable snapshot gates its state observer instead of deleting
 * the entity. Newly confirmed channels can be registered and described by the existing runtime.
 */
internal class CapabilityShape(private val maxDynamicChannels: Int = 64) {
    init {
        require(maxDynamicChannels >= 0)
    }

    private var possible = CapabilityChannels()
    private var live = CapabilityChannels()

    @Synchronized
    fun observe(capabilities: Capabilities): CapabilityObservation {
        val previousLive = live
        live = CapabilityChannels(
            zigbee = capabilities.zigbeePresent,
            cpu = capabilities.cpuGovernors,
            networkAdb = capabilities.networkAdb,
            relays = capabilities.relays.coerceIn(0, maxDynamicChannels),
            buttonLeds = capabilities.buttonLeds.coerceIn(0, maxDynamicChannels),
        )
        val nextPossible = CapabilityChannels(
            zigbee = possible.zigbee || live.zigbee,
            cpu = possible.cpu || live.cpu,
            networkAdb = possible.networkAdb || live.networkAdb,
            relays = maxOf(possible.relays, live.relays),
            buttonLeds = maxOf(possible.buttonLeds, live.buttonLeds),
        )
        val grew = nextPossible != possible
        possible = nextPossible
        return CapabilityObservation(possible, live, previousLive, grew)
    }

    @Synchronized
    fun current(): CapabilityObservation =
        CapabilityObservation(possible, live, live, grew = false)
}

/** One immutable capability value per hello, with a short monotonic burst cache. */
internal class CapabilitySnapshotSource(
    private val supplier: (() -> Capabilities)?,
    private val onFailure: (Exception) -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var latest: Capabilities? = null
    private var latestAtMs = Long.MIN_VALUE

    /** The last snapshot taken, without probing. Null before the first one. */
    @Synchronized
    fun cached(): Capabilities? = latest

    @Synchronized
    fun snapshot(maxAgeMs: Long = 0L): Capabilities? {
        val source = supplier ?: return null
        val now = nowMs()
        if (maxAgeMs > 0L && latestAtMs != Long.MIN_VALUE) {
            val age = now - latestAtMs
            if (age in 0..maxAgeMs) return latest
        }
        val value = try {
            source()
        } catch (e: Exception) {
            onFailure(e)
            // A failed live probe must not abort the hello. All-false is the
            // fail-closed settings view; hardware entities driven by constructor/profile facts continue.
            Capabilities()
        }
        latest = value
        latestAtMs = nowMs()
        return value
    }

    @Synchronized
    fun invalidate() {
        latest = null
        latestAtMs = Long.MIN_VALUE
    }
}
