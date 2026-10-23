package io.panelassistant.android.testsupport

import io.panelassistant.android.testsupport.scratchDirectory

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import io.panelassistant.android.Config
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.AutoBrightnessController
import io.panelassistant.android.control.BootChimeController
import io.panelassistant.android.control.BootChimeHardware
import io.panelassistant.android.control.BootChimeState
import io.panelassistant.android.control.BootChimeStateStore
import io.panelassistant.android.control.BrightnessController
import io.panelassistant.android.control.ControlApplyOutcome
import io.panelassistant.android.control.CpuController
import io.panelassistant.android.control.FakeBacklight
import io.panelassistant.android.control.FakeDaemon
import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.FakeScreenPower
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.control.FakeWakeTap
import io.panelassistant.android.control.LedEffectController
import io.panelassistant.android.control.NavbarController
import io.panelassistant.android.control.NavigateController
import io.panelassistant.android.control.RelayController
import io.panelassistant.android.control.ScreenController
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.control.TouchSoundController
import io.panelassistant.android.control.VolumeController
import io.panelassistant.android.control.WatchdogController
import io.panelassistant.android.control.WifiOutageCounts
import io.panelassistant.android.control.ZigbeeController
import io.panelassistant.android.control.fakeProfile
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.hardware.LedController
import io.panelassistant.android.config.SettingValue
import io.panelassistant.android.panelassistant.CommandValue
import io.panelassistant.android.panelassistant.PanelAssistantChannelCatalog
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantValueKind
import io.panelassistant.android.panelassistant.decode
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.setAutoSleep
import io.panelassistant.android.state.SoftwareTarget
import io.panelassistant.android.state.SoftwareUpdateSources
import io.panelassistant.android.state.StateConverger
import io.panelassistant.android.storage.StorageHealthSeverity
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.storage.StorageQuickCheck
import io.panelassistant.android.util.MonotonicDeadline
import org.junit.Assert.assertTrue
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The panel's real state core and command dispatcher on fake hardware, wired as the service wires one
 * network-runtime generation, for tests that observe native state and commands through those owners.
 */
internal abstract class PanelRuntimeRig {

    protected fun submitNative(rig: Rig, channel: String, payload: String): PanelAssistantCommandResult =
        submitNative(rig, channel, nativeValue(channel, payload))

    protected fun submitNative(rig: Rig, channel: String, value: CommandValue): PanelAssistantCommandResult {
        val done = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<PanelAssistantCommandResult>()
        rig.commands.submit(PanelAssistantCommand(channel, value, admit = { null }), io.panelassistant.android.CommandOrigin.panelAssistant()) {
            result.set(it)
            done.countDown()
        }
        assertTrue("$channel native command finished", done.await(20, TimeUnit.SECONDS))
        return result.get()
    }

    /**
     * The value Panel Assistant decodes for a test's [payload] on [channel]: a switch from on/off, a number, an
     * option code, text, or a light or media object as JSON. A button takes any payload as its press; a channel
     * this build does not describe keeps the raw string, so the dispatcher answers it as unknown.
     */
    protected fun nativeValue(channel: String, payload: String): CommandValue {
        val descriptor = PanelAssistantChannelCatalog.describe(channel) ?: return CommandValue.Text(payload)
        val wire: Any? = when (descriptor.kind) {
            PanelAssistantValueKind.BOOLEAN -> SettingValue.parseBool(payload)
            PanelAssistantValueKind.NUMBER -> payload.toDoubleOrNull()
            PanelAssistantValueKind.OPTION, PanelAssistantValueKind.TEXT -> payload
            PanelAssistantValueKind.LIGHT, PanelAssistantValueKind.MEDIA -> org.json.JSONObject(payload)
            PanelAssistantValueKind.BUTTON -> return CommandValue.Press
            PanelAssistantValueKind.UPDATE, PanelAssistantValueKind.EVENT -> null
        }
        return requireNotNull(decode(descriptor, wire)) { "$channel does not take $payload" }
    }

    /** Waits until the ordered command worker has run everything queued before it. */
    protected fun barrier(rig: Rig, busy: String) {
        val done = CountDownLatch(1)
        val key = if (busy == "navigate") "home_dashboard" else "navigate"
        rig.commands.submit(
            PanelAssistantCommand(key, CommandValue.Text(""), admit = { PanelAssistantCommandResult.Refused("barrier") }),
            io.panelassistant.android.CommandOrigin.panelAssistant(),
        ) { done.countDown() }
        assertTrue("command worker drained", done.await(20, TimeUnit.SECONDS))
    }

    /** Every channel a panel with every capability registers once those capabilities are observed. */
    protected fun everyChannel(): Set<String> {
        val rig = rig(hasCamera = true, hasCht8305 = true)
        try {
            rig.core.ensureCapabilityChannels(
                Capabilities(zigbeePresent = true, cpuGovernors = true, networkAdb = true, relays = 2, buttonLeds = 1),
            )
            return rig.core.stateChannelKeys()
        } finally {
            rig.close()
        }
    }

    // ---- rig ----

    protected class Rig(
        val tmp: File,
        val config: Config,
        val sysfs: SysfsRootShell,
        val core: io.panelassistant.android.PanelStateCore,
        val commands: io.panelassistant.android.PanelCommandDispatcher,
        val storage: java.util.concurrent.atomic.AtomicReference<StorageHealthSnapshot>,
        val storageReads: AtomicInteger,
        val updateSources: java.util.concurrent.atomic.AtomicReference<SoftwareUpdateSources>,
        val autoSleepConfigChanges: AtomicInteger,
        /** The music stream volume the panel reads; a test changes it as the volume keys would. */
        val audio: android.media.ScriptedAudioManager,
        /** The CPU governor source; a test changes its governor as a thermal daemon would. */
        val metrics: io.panelassistant.android.control.FakeMetricSource,
        val screen: ScreenController,
        /** The root shell the CPU governor writes go through; a test sets its runResult false to refuse them. */
        val cpuShell: FakeRootShell,
    ) {
        /** The service's start order: the state core's startup step. */
        fun start() {
            core.start()
        }

        fun close() {
            runCatching { commands.stop(MonotonicDeadline(1_000)) }
            runCatching { core.stop(MonotonicDeadline(1_000)) }
            tmp.deleteRecursively()
        }
    }

    protected fun rig(
        hasTemperature: Boolean = true,
        buttonKeys: io.panelassistant.android.device.PanelButtonKeys = io.panelassistant.android.device.PanelButtonKeys.UNKNOWN,
        hasHumidity: Boolean = true,
        hasCht8305: Boolean = false,
        hasCamera: Boolean = false,
        cameraAvailability: () -> Boolean? = { hasCamera },
        learnedProximityState: () -> Boolean? = { null },
        lightChannel: () -> Boolean? = { true },
        led: LedController = object : LedController {
            override fun available() = true
            override fun colorCapable() = true
            override fun setRgb(r: Int, g: Int, b: Int) = true
            override fun off() = true
        },
        companionHomeReturns: MutableList<String>? = null,
        // A speaker panel by default, as every bundled profile is; null is a profile declaring none.
        media: io.panelassistant.android.media.PanelMediaPlayer? = io.panelassistant.android.media.PanelMediaPlayer(
            streams = { _, _, _ -> error("this rig plays no media") },
            post = { it() },
            announce = { _, _ -> false },
            cancelAnnouncement = {},
            muted = { false },
            setMuted = {},
        ),
        autoSleepActivity: () -> io.panelassistant.android.AutoSleepActivitySnapshot = { io.panelassistant.android.AutoSleepActivitySnapshot() },
        helperSend: ((String) -> String?)? = null,
        /** A panel whose CPU governors are readable, starting on this governor; null offers none. */
        cpuGovernor: String? = null,
        /** The named operations the dispatcher serves, and its subscription families. */
        manageTable: io.panelassistant.android.ManageTable = io.panelassistant.android.ManageTable(),
        manageFamilies: Map<String, io.panelassistant.android.ManageFamily> = io.panelassistant.android.ManageFamilies.DEFAULT,
        approvalPollMs: Long = 1_000,
        /** Whether the reboot takes the panel down: the helper's REBOOT AWAIT ends without an answer, as on a
         *  panel going down. Otherwise it falls to root, which a JVM cannot reboot. */
        rebootSucceeds: Boolean = true,
        /** Whether the panel has a lock credential, which refuses every reboot. */
        deviceSecure: Boolean = true,
        /** Touch-sound hardware; null leaves the controller unconstructed, as no touch_sound command is sent. */
        touchSoundHardware: io.panelassistant.android.control.TouchSoundHardware? = null,
        /** Whether the panel has a proximity source; false is a profile with none. */
        hasProximity: Boolean = true,
        /** Whether a durable (commit) write reaches storage; apply still publishes at once, as the SQLite store does. */
        commitsSucceed: () -> Boolean = { true },
        /** The SCREEN_OFF_TIMEOUT setting prevent_idle_dim writes; null keeps Android's, which is an inert stub here. */
        screenOffTimeout: io.panelassistant.android.control.ScreenTimeoutController? = null,
        brightnessSetting: io.panelassistant.android.control.BrightnessSetting? = null,
        /** The screen-off route a profile declares, the backlight the screen controller reads (a test
         *  moves its level as a vendor brightness writer would) and the root shell it powers it through. */
        screenOff: ScreenOff = ScreenOff.BRIGHTNESS_ZERO,
        screenBacklight: FakeBacklight = FakeBacklight(160),
        screenRoot: RootShell = FakeRootShell(),
        /** Boot-chime hardware; null keeps the inert controller. When given, the controller persists its
         *  setting in this rig's Config, as production does. */
        bootChimeHardware: BootChimeHardware? = null,
        /** Root for the network ADB controller; null leaves it allocated and unusable, as no network_adb command is sent. */
        adbRoot: RootShell? = null,
        wifiOutages: () -> WifiOutageCounts? = { WifiOutageCounts(last24h = 3) },
        /** The helper the system controller sends reload and reboot to; a test reads what it was sent. */
        systemDaemon: FakeDaemon = FakeDaemon(
            replies = mapOf("RELOAD io.homeassistant.companion.android.minimal" to "OK"),
            longOutcomes = if (rebootSucceeds) mapOf("REBOOT AWAIT" to DaemonLongResult.Indeterminate) else emptyMap(),
        ),
        /** The packages and activities the system controller resolves starts against. */
        systemEnv: FakeSystemEnv = FakeSystemEnv(),
        /** What the system controller reads a screensaver from and ends it with; by default no service. */
        systemAccessibility: io.panelassistant.android.platform.AccessibilityActions =
            io.panelassistant.android.input.PanelAccessibilityService,
        onZigbeeExplicitRetry: () -> Unit = {},
        /** Retires a queued HTTP value an external command superseded; a test records the keys. */
        onExternalSettingApplied: (String) -> Boolean = { true },
        /** The kiosk controller's lock and its start-up recovery; a test records the calls. */
        kioskApply: (Boolean) -> Boolean = { true },
        kioskRecover: (Boolean) -> Boolean = { true },
        /** The panel's Back routes; by default neither root nor an accessibility service is there. */
        navigation: io.panelassistant.android.control.InteractiveController = io.panelassistant.android.control.InteractiveController(
            canSu = false,
            root = FakeRootShell(available = false, runResult = false),
            accessibility = object : io.panelassistant.android.platform.AccessibilityActions {
                override fun back() = false
                override fun recents() = false
                override fun tap(x: Int, y: Int) = false
            },
        ),
        /** Whether the navbar controller acknowledges each mode at once, drawing nothing; otherwise it is unusable. */
        navbarAcknowledges: Boolean = false,
        /** Runs before each acknowledged navbar mode change completes; a test can hold the command worker in it. */
        beforeNavbarAcknowledged: () -> Unit = {},
        configure: (Config) -> Unit = {},
    ): Rig {
        val tmp = scratchDirectory("panel-runtime-rig").toFile()
        val prefs = MemoryPreferences(commitsSucceed)
        val context = FakeContext(tmp, prefs)
        val config = newConfig(prefs, context.contentResolver)
        config.setRaw(requireNotNull(SettingsRegistry.spec("manufacturer")), "Golden Manufacturing")
        config.setRaw(requireNotNull(SettingsRegistry.spec("model")), "Golden Panel")
        config.setAutoSleep(true)
        // Most config entities are opt-in. Expose a representative set. Host-metric diagnostics stay
        // hidden: they read /proc.
        listOf(
            "diag_wifi_outages_24h", "wake_on_wave", "auto_sleep",
            "auto_sleep_activity", "touch_sound", "kiosk_lock", "auto_brightness", "navbar_mode",
        ).forEach { config.setHaExposed(it, true) }
        configure(config)

        val sysfs = SysfsRootShell()
        val relay = RelayController(
            fakeProfile(relayBase = RELAY_BASE, buttonLedGpioBase = LED_GPIO_BASE),
            sysfs,
        )
        val brightness = if (brightnessSetting != null) {
            BrightnessController(context, FakeRootShell(), FakeDaemon(), setting = brightnessSetting)
        } else screenOffTimeout?.let { BrightnessController(context, FakeRootShell(), FakeDaemon(), screenTimeout = it) }
            ?: BrightnessController(context, FakeRootShell(), FakeDaemon())
        val screen = ScreenController(
            if (brightnessSetting != null) brightness else screenBacklight, FakeScreenPower(), screenRoot, FakeDaemon(), FakeWakeTap(),
            screenOff, nap = {}, accessibility = io.panelassistant.android.control.FakeDreamAccessibility(false),
        )
        val companion = "io.homeassistant.companion.android.minimal"
        val system = SystemController(
            systemEnv, FakeRootShell(runResult = rebootSucceeds), systemDaemon,
            builtinForeground = { false }, homeDashboard = { config.homeDashboard },
            onCompanionHome = { pkg, path -> companionHomeReturns?.add("$pkg:$path") },
            isDeviceSecure = { deviceSecure }, accessibility = systemAccessibility,
        )
        val bootChime = if (bootChimeHardware != null) {
            BootChimeController(
                configured = { config.silenceBootChime },
                setConfigured = config::setSilenceBootChime,
                stateStore = object : BootChimeStateStore {
                    private var state: BootChimeState? = null
                    override fun load(): BootChimeState? = state
                    override fun save(state: BootChimeState): Boolean { this.state = state; return true }
                    override fun clear(): Boolean { state = null; return true }
                },
                hardware = bootChimeHardware,
            )
        } else {
            BootChimeController(
                configured = { false },
                setConfigured = {},
                stateStore = object : BootChimeStateStore {
                    override fun load(): BootChimeState? = null
                    override fun save(state: BootChimeState) = true
                    override fun clear() = true
                },
                hardware = object : BootChimeHardware {
                    override fun capture(): BootChimeState? = null
                    override fun silence() = ControlApplyOutcome.APPLIED
                    override fun restore(state: BootChimeState) = ControlApplyOutcome.APPLIED
                },
            )
        }
        val capabilities = Capabilities(
            hasProximity = hasProximity,
            hasLearnedProximity = true,
            hasLight = true,
            hasTemperature = true,
            hasHumidity = true,
            hasButtonBacklight = true,
            microphone = io.panelassistant.android.audio.MicrophonePresence.PROVEN,
            hasCamera = hasCamera,
            relays = 2,
            buttonLeds = 1,
            canInstallVerifiedApps = true,
            hasWifi = true,
            cpuGovernors = cpuGovernor != null,
        )
        val storage = java.util.concurrent.atomic.AtomicReference(storageSnapshot(StorageHealthSeverity.HEALTHY, walBytes = 4_096))
        val storageReads = AtomicInteger()
        val updateSources = java.util.concurrent.atomic.AtomicReference(updateSources())
        val autoSleepConfigChanges = AtomicInteger()

        val ledEffect = LedEffectController(led)
        val zigbee = ZigbeeController(fakeProfile(), FakeRootShell())
        val metrics = io.panelassistant.android.control.FakeMetricSource(
            governor = cpuGovernor,
            availableGovernors = cpuGovernor?.let { "performance powersave schedutil" },
        )
        // A governor write the root shell runs becomes the governor the panel reads back, unless it is refused.
        lateinit var cpuShell: FakeRootShell
        cpuShell = FakeRootShell(onRun = { command ->
            if (cpuShell.runResult) Regex("echo ([a-z0-9_]+) >").find(command)?.let { metrics.governor = it.groupValues[1] }
        })
        val cpu = CpuController(
            fakeProfile(), cpuShell, FakeDaemon(), io.panelassistant.android.metrics.PanelMetrics(metrics),
        )
        // Only its best-effort reconnect reassertion runs, on a worker that logs and discards failure.
        val adb = adbRoot?.let {
            AdbController(config, io.panelassistant.android.control.NetworkAdbDisableTransitionMarker(File(tmp, "network-adb-disable.v1")), it)
        } ?: allocate(AdbController::class.java)
        val volume = VolumeController(context)
        val core = io.panelassistant.android.PanelStateCore(
            config = config,
            brightness = brightness,
            screen = screen,
            led = led,
            ledEffect = ledEffect,
            volume = volume,
            system = system,
            bootChime = bootChime,
            zigbee = zigbee,
            relay = relay,
            cpu = cpu,
            adb = adb,
            buttonsEnabled = true,
            buttonKeys = buttonKeys,
            capabilities = { capabilities },
            hasProximity = hasProximity,
            hasTemperature = hasTemperature,
            hasHumidity = hasHumidity,
            hasCht8305 = hasCht8305,
            hasButtonBacklight = true,
            media = media,
            hasCamera = cameraAvailability,
            lightChannel = lightChannel,
            canBack = navigation::canBack,
            autoSleepActivity = autoSleepActivity,
            softwareUpdateSources = { updateSources.get() },
            storageHealth = { storageReads.incrementAndGet(); storage.get() },
            helperSend = helperSend ?: io.panelassistant.android.util.HelperClient::send,
            wifiOutages = wifiOutages,
            learnedProximityEligibility = { true },
            learnedProximityState = learnedProximityState,
        )
        val commands = io.panelassistant.android.PanelCommandDispatcher(
            config = config,
            core = core,
            brightness = brightness,
            screen = screen,
            navigate = NavigateController(context),
            volume = volume,
            system = system,
            // Never usable unless asked for: a navbar command that reaches the controller throws here.
            navbar = allocate(NavbarController::class.java).also { if (navbarAcknowledges) acknowledgeModes(it, beforeNavbarAcknowledged) },
            watchdog = WatchdogController(system, config),
            // The real controller over fake hardware when a test drives touch_sound; otherwise never usable.
            touchSound = touchSoundHardware?.let { touchSoundController(context, it) }
                ?: allocate(TouchSoundController::class.java),
            bootChime = bootChime,
            zigbee = zigbee,
            relay = relay,
            cpu = cpu,
            adb = adb,
            hasProximity = hasProximity,
            hasButtonBacklight = true,
            hasMicrophone = true,
            media = media,
            hasCamera = cameraAvailability,
            // Automatic brightness is disabled; the real command's preference capture reads that Config.
            autoBright = allocate(AutoBrightnessController::class.java).also { controller ->
                AutoBrightnessController::class.java.getDeclaredField("config").apply { isAccessible = true }.set(controller, config)
            },
            kioskApply = kioskApply,
            goBack = navigation::back,
            kioskRecover = kioskRecover,
            onAutoSleepConfigChanged = { autoSleepConfigChanges.incrementAndGet() },
            onZigbeeExplicitRetry = onZigbeeExplicitRetry,
            onExternalSettingApplied = onExternalSettingApplied,
            helperSend = helperSend ?: io.panelassistant.android.util.HelperClient::send,
            goRecents = navigation::recents,
            manageTable = manageTable,
            manageFamilies = manageFamilies,
            approvalPollMs = approvalPollMs,
        )
        return Rig(
            tmp, config, sysfs, core, commands, storage, storageReads, updateSources, autoSleepConfigChanges,
            context.audio, metrics, screen, cpuShell,
        )
    }

    /** Let an allocated [navbar] take mode changes: each one is acknowledged as applied at once. */
    private fun acknowledgeModes(navbar: NavbarController, beforeAcknowledged: () -> Unit) {
        fun field(name: String, value: Any) =
            NavbarController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(navbar, value)
        fun read(work: Any, getter: String): Any? = work.javaClass.getDeclaredMethod(getter).apply { isAccessible = true }.invoke(work)
        field("lifecycleLock", Any())
        field("modeDispatcher", io.panelassistant.android.util.LatestDispatcher.singleSlot<Any>("rig-navbar", consume = { work ->
            beforeAcknowledged()
            @Suppress("UNCHECKED_CAST")
            (read(work, "getCompletion") as java.util.concurrent.CompletableFuture<io.panelassistant.android.control.NavbarModeApplyOutcome>?)
                ?.complete(
                    io.panelassistant.android.control.NavbarModeApplyOutcome(
                        read(work, "getMode") as String,
                        io.panelassistant.android.control.NavbarModeApplyStatus.APPLIED,
                    ),
                )
        }))
    }

    /** The production controller with its state policy over [hardware] and an in-memory record. */
    private fun touchSoundController(
        context: Context,
        hardware: io.panelassistant.android.control.TouchSoundHardware,
    ): TouchSoundController {
        // Allocated rather than constructed: its touch overlay needs a WindowManager this rig has not got.
        // Every field apply() reads is set to what the constructor would give it.
        val controller = allocate(TouchSoundController::class.java)
        fun field(name: String, value: Any) =
            TouchSoundController::class.java.getDeclaredField(name).apply { isAccessible = true }.set(controller, value)
        field("ctx", context)
        field("clickGain", 0.5f)
        field("am", requireNotNull(context.getSystemService(Context.AUDIO_SERVICE)))
        field("main", android.os.Handler(android.os.Looper.getMainLooper()))
        val store = object : io.panelassistant.android.control.TouchSoundStateStore {
            private var active: Boolean? = null
            private var prior: io.panelassistant.android.control.TouchSoundState? = null
            override fun active() = active
            override fun prior() = prior
            override fun saveEnabled(prior: io.panelassistant.android.control.TouchSoundState): Boolean {
                active = true; this.prior = prior; return true
            }
            override fun saveDisabledAndClearPrior(): Boolean { active = false; prior = null; return true }
            override fun retireLegacyStreamState() = true
        }
        field("statePolicy", io.panelassistant.android.control.TouchSoundStatePolicy(store, hardware))
        return controller
    }

    // ---- fakes ----

    /** Relay and button-LED sysfs nodes whose reads reflect writes; one write can be held on a latch. */
    protected class SysfsRootShell : RootShell {
        private val nodes = ConcurrentHashMap(
            mapOf(
                "$RELAY_BASE/relay1" to "0",
                "$RELAY_BASE/relay2" to "1",
                "/sys/class/gpio/gpio$LED_GPIO_BASE/value" to "0",
            ),
        )
        val writes = CopyOnWriteArrayList<String>()
        var refusedWrite: String? = null
        var refuseGpioPreparation = false
        var unreadable: String? = null
        @Volatile private var blockPath: String? = null
        val blockEntered = CountDownLatch(1)
        val blockRelease = CountDownLatch(1)

        fun blockNextWrite(path: String) {
            blockPath = path
        }

        override fun available() = true
        override fun run(cmd: String) = true
        override fun runOutput(cmd: String): String? = null
        override fun runBytes(cmd: String): ByteArray? = null
        override fun listSysfs(path: String): String? = if (path == RELAY_BASE) "relay1 relay2" else null
        override fun readSysfs(path: String): String? = if (path == unreadable) null else nodes[path]
        override fun prepareOutputGpio(gpio: Int) = !refuseGpioPreparation
        override fun writeSysfs(path: String, value: String): Boolean {
            if (path == blockPath) {
                blockPath = null
                blockEntered.countDown()
                check(blockRelease.await(30, TimeUnit.SECONDS)) { "blocked write never released" }
            }
            writes += "$path=$value"
            if (path == refusedWrite) return false
            nodes[path] = value
            return true
        }
    }

    protected class FakeContext(
        private val files: File,
        private val prefs: SharedPreferences,
    ) : ContextWrapper(null) {
        val audio = android.media.ScriptedAudioManager()
        private val resolver = object : ContentResolver(null) {}
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = if (name == Context.AUDIO_SERVICE) audio else null
        override fun getContentResolver(): ContentResolver = resolver
        override fun getNoBackupFilesDir(): File = files
        override fun getFilesDir(): File = files
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getPackageName(): String = "io.panelassistant.android"
    }

    /** Thread-safe in-memory preferences; a commit fails, storing nothing, while [commits] answers false. */
    protected class MemoryPreferences(commits: () -> Boolean = { true }) :
        SharedPreferences by proxy(ConcurrentHashMap(), commits = commits)

    protected companion object {
        fun drainStatePump() {
            val drained = CountDownLatch(1)
            StateConverger.dispatch { drained.countDown() }
            assertTrue("convergence pump drained", drained.await(5, TimeUnit.SECONDS))
        }

        const val PANEL = "golden"

        const val RELAY_BASE = "/sys/class/strelay"
        const val LED_GPIO_BASE = 147
        fun awaitCondition(condition: () -> Boolean): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!condition()) {
                if (System.nanoTime() >= deadline) return false
                Thread.sleep(10)
            }
            return true
        }

        fun updateSources(paneldTarget: SoftwareTarget? = SoftwareTarget("1.2.4", "v1.2.4", "https://example.invalid/releases/v1.2.4")) =
            SoftwareUpdateSources(
                paneldVersion = "1.2.3",
                paneldChannel = "stable",
                paneldTarget = paneldTarget,
                companionMinimalVersion = "2026.1.1-minimal",
                companionFullVersion = null,
                companionChannel = "stable",
                companionCap = null,
                companionTarget = null,
                runningOperation = null,
            )

        fun storageSnapshot(severity: StorageHealthSeverity, walBytes: Long) = StorageHealthSnapshot(
            severity = severity,
            pressureSeverity = severity,
            checkedAtMillis = 1_700_000_000_000L,
            usableBytes = 6_000_000_000L,
            totalBytes = 8_000_000_000L,
            usedPercent = 25.0,
            mainDatabaseBytes = 1_048_576L,
            walBytes = walBytes,
            sidecarBytes = 32_768L,
            pageSizeBytes = 4_096L,
            pageCount = 256L,
            freelistCount = 3L,
            schemaVersion = 7,
            quickCheck = StorageQuickCheck.OK,
        )

        fun newConfig(prefs: SharedPreferences, resolver: ContentResolver): Config {
            // The production constructor opens SQLite; the internal JVM seam takes the stores directly.
            val constructor = Config::class.java.declaredConstructors.single {
                it.parameterTypes.contentEquals(
                    arrayOf(
                        SharedPreferences::class.java,
                        ContentResolver::class.java,
                        SharedPreferences::class.java,
                        SharedPreferences::class.java,
                        Resources::class.java,
                    ),
                )
            }
            constructor.isAccessible = true
            return constructor.newInstance(prefs, resolver, prefs, prefs, null) as Config
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> allocate(type: Class<T>): T {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), type) as T
        }

        fun proxy(
            values: MutableMap<String, Any>,
            listeners: CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener> = CopyOnWriteArrayList(),
            commits: () -> Boolean = { true },
        ): SharedPreferences =
            Proxy.newProxyInstance(
                SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java),
            ) { instance, method, args ->
                when (method.name) {
                    "getAll" -> synchronized(values) { values.toMap() }
                    "getString", "getInt", "getLong", "getFloat", "getBoolean", "getStringSet" ->
                        values[args!![0] as String] ?: args[1]
                    "contains" -> values.containsKey(args!![0] as String)
                    "edit" -> editor(values, commits) { key ->
                        listeners.forEach { it.onSharedPreferenceChanged(instance as SharedPreferences, key) }
                    }
                    "registerOnSharedPreferenceChangeListener" -> {
                        listeners.addIfAbsent(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener)
                        null
                    }
                    "unregisterOnSharedPreferenceChangeListener" -> {
                        listeners.remove(args!![0] as SharedPreferences.OnSharedPreferenceChangeListener)
                        null
                    }
                    "toString" -> "MemoryPreferences"
                    "hashCode" -> System.identityHashCode(values)
                    "equals" -> false
                    else -> error("unexpected SharedPreferences call: ${method.name}")
                }
            } as SharedPreferences

        fun editor(
            values: MutableMap<String, Any>,
            commits: () -> Boolean = { true },
            onChange: (String) -> Unit = {},
        ): SharedPreferences.Editor {
            val writes = LinkedHashMap<String, Any?>()
            val removals = LinkedHashSet<String>()
            var clear = false
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "putString", "putInt", "putLong", "putFloat", "putBoolean", "putStringSet" -> editor.also {
                        writes[args!![0] as String] = args[1]
                        removals.remove(args[0] as String)
                    }
                    "remove" -> editor.also {
                        writes.remove(args!![0] as String)
                        removals.add(args[0] as String)
                    }
                    "clear" -> editor.also { clear = true }
                    "commit", "apply" -> {
                        // Only a durable commit can be refused; apply publishes at once, as SqliteStatePreferences does.
                        if (method.name == "commit" && !commits()) return@newProxyInstance false
                        synchronized(values) {
                            if (clear) values.clear()
                            removals.forEach { values.remove(it) }
                            writes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                        }
                        (removals + writes.keys).forEach(onChange)
                        if (method.name == "commit") true else null
                    }
                    "toString" -> "MemoryPreferencesEditor"
                    else -> error("unexpected Editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
}
