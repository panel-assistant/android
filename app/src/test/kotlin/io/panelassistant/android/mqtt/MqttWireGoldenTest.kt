package io.panelassistant.android.mqtt

import io.panelassistant.android.config.SettingsRegistry
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import android.media.AudioManager
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.Config
import io.panelassistant.android.MqttAddressFamily
import io.panelassistant.android.MqttBridge
import io.panelassistant.android.mqttKnownConfigTopics
import io.panelassistant.android.requestWatchdogLocalObservation
import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.control.AdbController
import io.panelassistant.android.control.AutoBrightnessController
import io.panelassistant.android.control.BootChimeController
import io.panelassistant.android.control.BootChimeHardware
import io.panelassistant.android.control.BootChimeState
import io.panelassistant.android.control.BootChimeStateStore
import io.panelassistant.android.control.BuiltinDashboard
import io.panelassistant.android.control.BrightnessController
import io.panelassistant.android.control.ControlApplyOutcome
import io.panelassistant.android.control.CpuController
import io.panelassistant.android.control.FakeBacklight
import io.panelassistant.android.control.FakeDaemon
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
import io.panelassistant.android.i18n.AppLocale
import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.panelassistant.PanelAssistantChannelCatalog
import io.panelassistant.android.panelassistant.PanelAssistantChannelDescriptor
import io.panelassistant.android.panelassistant.PanelAssistantCommand
import io.panelassistant.android.panelassistant.PanelAssistantCommandProcessor
import io.panelassistant.android.panelassistant.PanelAssistantCommandResult
import io.panelassistant.android.panelassistant.PanelAssistantCommandTranslation
import io.panelassistant.android.panelassistant.PanelAssistantValueKind
import io.panelassistant.android.panelassistant.PanelAssistantValueTranslation
import io.panelassistant.android.panelassistant.PanelAssistantWireValue
import io.panelassistant.android.storage.StorageHealthSeverity
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.storage.StorageQuickCheck
import io.panelassistant.android.testsupport.TestSources
import io.panelassistant.android.util.MonotonicDeadline
import io.panelassistant.android.util.ServiceRuntimeOwner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Captures the established MQTT wire output of the REAL [MqttBridge] — a full connect announcement, a
 * representative burst of Home Assistant commands and local sensor updates, and retirement — and
 * compares it byte for byte against a checked-in golden fixture. It exists so a refactor of the state
 * converger wiring or the command dispatcher can prove it changed no topic, payload, retain flag,
 * subscription or publication order.
 *
 * The separately tested Panel Assistant migration problem is omitted from this historical fixture.
 * Only the broker client is replaced: a recording [MqttTransport] injected through the bridge's
 * constructor seam. Everything the bridge decides — discovery, cleanup, pruning, state convergence,
 * command dispatch — runs as in production against fake hardware.
 *
 * Wire facts that are not captured: QoS is fixed at 1 inside [HiveMqTransport] and is not part of the
 * [MqttTransport] interface, so it cannot vary through the bridge and is not recorded. Connect options
 * are recorded from [MqttConnectConfig], excluding the route planner object.
 *
 * Determinism. The bridge publishes from several threads (connect announcement, command worker and the
 * process-wide state-convergence pump) and the state converger admits at most four unacknowledged
 * publications, so the order of later publications depends on when acknowledgements arrive. The fake
 * therefore HOLDS every acknowledgement. The test waits until the bridge is quiescent, then releases
 * every held acknowledgement in FIFO order from a task on the convergence pump itself, so all follow-up
 * work those acknowledgements schedule queues behind the release and runs in one fixed order. Rounds
 * repeat until a quiet round has nothing held.
 *
 * Normalisation: the Home Assistant device `sw_version` embeds the app version name and build code,
 * which change with every release; that exact string is replaced by [SW_VERSION_TOKEN], and the test
 * asserts the token appears only in `homeassistant/` discovery configs so normalisation cannot mask a
 * state or attributes byte.
 *
 * Fixture: `mqtt-wire-golden/bridge.txt` on the test classpath (the test resources directory), one line
 * per wire event in order: `retain<TAB>topic<TAB>base64(payload)` for publications (`-` for an empty
 * payload, which base64 cannot produce), `subscribe<TAB>filter`
 * and `connect<TAB>…` for session setup, with `# announce`, `# burst` and `# final` section markers.
 * Re-record with `HAPANELD_RECORD_MQTT_GOLDEN=1`; record mode writes the source-tree copy. Compare mode
 * reads the classpath copy, which Gradle fingerprints as a test input, so the source-tree path is only
 * ever written and is assembled from segments rather than spelled as one runtime-read literal.
 *
 * This base holds the rig; the tests live in the classes below it, split so parallel test forks balance:
 * every case waits out quiet windows in real time, so one class holding them all set the length of the
 * whole unit-test run.
 */
internal abstract class MqttWireRig {

    protected fun queuedAuthorityChange(authority: String, expectedMqttWrites: Int) {
        val rig = rig()
        try {
            rig.announce()
            rig.sysfs.blockNextWrite("$RELAY_BASE/relay1")
            rig.transport.deliver("ha-paneld/$PANEL/relay1/set", "OFF")
            assertTrue("first MQTT handler is running", rig.sysfs.blockEntered.await(10, TimeUnit.SECONDS))
            val relay2Before = rig.sysfs.writes.count { it.startsWith("$RELAY_BASE/relay2=") }
            val actionsBefore = rig.companionUpdateRequests.get()
            rig.transport.deliver("ha-paneld/$PANEL/relay2/set", "ON")
            rig.transport.deliver("ha-paneld/$PANEL/update_companion/set", "PRESS")
            rig.config.setPanelAssistantAuthority(authority)
            val completed = CountDownLatch(1)
            val result = java.util.concurrent.atomic.AtomicReference<PanelAssistantCommandResult>()
            rig.bridge.submitPanelAssistantCommand(
                PanelAssistantCommand("relay1", "ON", admit = { null }),
            ) { result.set(it); completed.countDown() }
            rig.sysfs.blockRelease.countDown()
            assertTrue("native command runs after queued MQTT commands", completed.await(10, TimeUnit.SECONDS))
            assertEquals(PanelAssistantCommandResult.Applied, result.get())
            assertEquals("queued MQTT relay mutations", expectedMqttWrites,
                rig.sysfs.writes.count { it.startsWith("$RELAY_BASE/relay2=") } - relay2Before)
            assertEquals("queued MQTT action mutations", expectedMqttWrites,
                rig.companionUpdateRequests.get() - actionsBefore)
            assertEquals("native handler reached hardware", "$RELAY_BASE/relay1=1", rig.sysfs.writes.last())
        } finally {
            rig.sysfs.blockRelease.countDown()
            rig.close()
        }
    }

    // ---- scenario ----

    protected fun capture(problems: MutableList<String>): List<String> {
        fun check(condition: Boolean, message: String) {
            if (!condition) problems += message
        }
        val rig = rig()
        val transport = rig.transport
        val sysfs = rig.sysfs
        val bridge = rig.bridge
        val storage = rig.storage
        val autoSleepConfigChanges = rig.autoSleepConfigChanges
        val companionUpdateRequests = rig.companionUpdateRequests

        try {
            // ---- connect announcement ----
            transport.mark("# announce")
            rig.announce()
            check(bridge.isConnected(), "announcement was never acknowledged")

            // ---- command and local-update burst ----
            transport.mark("# burst")
            command("screen", """{"state":"OFF"}""", transport)
            command("volume", "35", transport)
            command("auto_sleep", "OFF", transport)
            // This panel has no native navigation bar: the refusal republishes the canonical mode.
            command("navbar", "Native", transport)
            command("relay1", "ON", transport)
            command("button_led1", "ON", transport)

            // Genuine coalescence: hold the command worker inside a relay1 write, queue two relay2
            // commands behind it, and prove only the newest one ever reaches the hardware.
            val relay2Node = "$RELAY_BASE/relay2"
            sysfs.blockNextWrite("$RELAY_BASE/relay1")
            val before = transport.size()
            transport.deliver("ha-paneld/$PANEL/relay1/set", "OFF")
            check(sysfs.blockEntered.await(10, TimeUnit.SECONDS), "relay1 write never started")
            val relay2WritesBefore = sysfs.writes.count { it.startsWith(relay2Node) }
            transport.deliver("ha-paneld/$PANEL/relay2/set", "ON")
            transport.deliver("ha-paneld/$PANEL/relay2/set", "OFF")
            sysfs.blockRelease.countDown()
            transport.awaitPublication(before, "relay2 state") { it.topic() == "ha-paneld/$PANEL/relay2/state" }
            transport.drain()
            val relay2Writes = sysfs.writes.filter { it.startsWith(relay2Node) }.drop(relay2WritesBefore)
            check(
                relay2Writes == listOf("$relay2Node=0"),
                "the queued relay2 ON was not coalesced into the newer OFF: $relay2Writes",
            )
            println("coalescence: relay2 ON superseded by OFF while relay1 write was held; relay2 writes ${sysfs.writes.filter { it.startsWith(relay2Node) }}")

            // An ACTION: never conflated. The legacy PRESS publishes nothing itself, so its execution is
            // proven by the injected callback, and a LATEST command queued behind it on the same worker
            // is the ordering barrier.
            val actionMark = transport.size()
            transport.deliver("ha-paneld/$PANEL/update_companion/set", "PRESS")
            command("volume", "36", transport, from = actionMark)

            // Local producers reconciled through the bridge's public update methods.
            local(transport, "ha-paneld/$PANEL/illuminance/state") { bridge.publishLight(120) }
            local(transport, "ha-paneld/$PANEL/temperature/state") { bridge.publishTemperature(21.46f) }
            local(transport, "ha-paneld/$PANEL/humidity/state") { bridge.publishHumidity(40.4f) }
            local(transport, "ha-paneld/$PANEL/proximity_level/state") { bridge.publishProximity(true, 50) }
            storage.set(storageSnapshot(StorageHealthSeverity.WARNING, walBytes = 65_536))
            local(transport, "ha-paneld/$PANEL/storage_health/attributes") { bridge.publishStorageHealth() }

            // ---- retirement ----
            transport.mark("# final")
            val retirement = bridge.stop(MonotonicDeadline(30_000), publishOffline = true)
            check(retirement.ownersDrained.get(40, TimeUnit.SECONDS), "bridge owners did not drain")
            retirement.finalization.get(40, TimeUnit.SECONDS)
            check(transport.heldCount() == 0, "publications stayed unacknowledged after retirement")
            check(autoSleepConfigChanges.get() == 1, "auto_sleep command ran ${autoSleepConfigChanges.get()} times, not once")
            check(companionUpdateRequests.get() == 1, "update_companion action ran ${companionUpdateRequests.get()} times, not once")
            println("commands delivered: ${transport.delivered.get()}")
        } finally {
            rig.close()
        }
        return transport.snapshot()
    }

    // ---- native and MQTT command parity ----

    /** Native values beside the payloads an MQTT client sends for them. */
    protected fun commandSamples(descriptor: PanelAssistantChannelDescriptor): List<Pair<Any, String>> = when (descriptor.kind) {
        PanelAssistantValueKind.BOOLEAN -> listOf(true to "ON", false to "OFF")
        PanelAssistantValueKind.NUMBER -> listOf(35L to "35")
        PanelAssistantValueKind.TEXT -> listOf("/lovelace/1" to "/lovelace/1")
        PanelAssistantValueKind.OPTION -> requireNotNull(LEGACY_LABELS[descriptor.channel]) { descriptor.channel }.toList()
        PanelAssistantValueKind.LIGHT -> when {
            descriptor.family == "button_led" -> listOf(org.json.JSONObject().put("on", true) to "ON")
            descriptor.channel == "led" -> listOf(
                org.json.JSONObject().put("on", true).put("color", org.json.JSONObject().put("r", 1).put("g", 2).put("b", 3)).put("effect", "pulse") to
                    """{"state":"ON","color":{"r":1,"g":2,"b":3},"effect":"pulse"}""",
            )
            else -> listOf(org.json.JSONObject().put("on", true).put("brightness", 128) to """{"state":"ON","brightness":128}""")
        }
        PanelAssistantValueKind.UPDATE, PanelAssistantValueKind.IMAGE, PanelAssistantValueKind.EVENT -> emptyList()
        // Native only: no MQTT payload exists to compare (NativeMediaChannelTest covers it).
        PanelAssistantValueKind.MEDIA -> emptyList()
        PanelAssistantValueKind.BUTTON -> listOf(org.json.JSONObject.NULL to "PRESS")
    }

    /** The payload form a command takes on the wire: its value kind, with the three light payloads apart. */
    protected fun wireShape(descriptor: PanelAssistantChannelDescriptor): String = when {
        descriptor.kind != PanelAssistantValueKind.LIGHT -> descriptor.kind.name
        descriptor.family == "button_led" -> "button_led"
        descriptor.channel == "led" -> "led"
        else -> "LIGHT"
    }

    protected fun normalise(payload: String): String =
        if (payload.startsWith("{")) org.json.JSONObject(payload).toString() else payload

    /** The publications and hardware writes [action] causes, sorted, with discovery excluded. */
    protected fun effect(rig: Rig, channel: String, action: () -> Unit): List<String> {
        val lines = rig.transport.size()
        val writes = rig.sysfs.writes.size
        action()
        if (channel.startsWith("button_led")) {
            // Read-back leaves the command worker, then publication leaves the convergence pump.
            // A quiet transport and a pump barrier do not prove that the send worker has finished.
            val topic = "ha-paneld/$PANEL/$channel/state"
            rig.transport.awaitPublication(lines, "$channel command state") { it.topic() == topic }
            assertTrue("$channel command state published", rig.transport.snapshot().drop(lines).any {
                it.isPublication() && it.topic() == topic
            })
        }
        rig.transport.drain()
        val published = rig.transport.snapshot().drop(lines).filter { it.isPublication() && !it.topic().startsWith("homeassistant/") }
        return (published + rig.sysfs.writes.drop(writes).map { "write\t$it" }).sorted()
    }

    protected fun submitNative(rig: Rig, channel: String, payload: String): PanelAssistantCommandResult {
        val done = CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicReference<PanelAssistantCommandResult>()
        rig.bridge.submitPanelAssistantCommand(PanelAssistantCommand(channel, payload, admit = { null })) {
            result.set(it)
            done.countDown()
        }
        assertTrue("$channel native command finished", done.await(20, TimeUnit.SECONDS))
        return result.get()
    }

    /** Waits until the ordered command worker has run everything queued before it. */
    protected fun barrier(rig: Rig, busy: String) {
        val done = CountDownLatch(1)
        val key = if (busy == "navigate") "home_dashboard" else "navigate"
        rig.bridge.submitPanelAssistantCommand(
            PanelAssistantCommand(key, "", admit = { PanelAssistantCommandResult.Refused("barrier") }),
        ) { done.countDown() }
        assertTrue("command worker drained", done.await(20, TimeUnit.SECONDS))
    }

    // ---- rig ----

    /** The real bridge on fake hardware and a recording transport; [announce] runs one connect to quiescence. */
    protected class Rig(
        val tmp: File,
        val config: Config,
        val transport: RecordingTransport,
        val sysfs: SysfsRootShell,
        val bridge: MqttBridge,
        val storage: java.util.concurrent.atomic.AtomicReference<StorageHealthSnapshot>,
        val storageReads: AtomicInteger,
        val updateSources: java.util.concurrent.atomic.AtomicReference<SoftwareUpdateSources>,
        val autoSleepConfigChanges: AtomicInteger,
        val companionUpdateRequests: AtomicInteger,
        val selfUpdateRequests: AtomicInteger,
    ) {
        fun announce() {
            bridge.start()
            transport.awaitPublication(0, "availability online", timeoutSeconds = 60) { it.topic() == "ha-paneld/$PANEL/availability" && it.decodedPayload() == "online" }
            transport.drain()
            awaitCondition { bridge.isConnected() }
            transport.drain()
        }

        fun close() {
            runCatching { bridge.stop(MonotonicDeadline(1_000)) }
            tmp.deleteRecursively()
        }
    }

    protected fun rig(
        runtimeBroker: String = "tcp://127.0.0.1:1883",
        hasTemperature: Boolean = true,
        hasHumidity: Boolean = true,
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
        cameraSnapshotUrl: () -> String? = { null },
        helperSend: ((String) -> String?)? = null,
        configure: (Config) -> Unit = {},
    ): Rig {
        val tmp = Files.createTempDirectory("mqtt-wire-golden").toFile()
        val prefs = MemoryPreferences()
        val context = FakeContext(tmp, prefs)
        val config = newConfig(prefs, context.contentResolver)
        config.setRaw(requireNotNull(SettingsRegistry.spec("manufacturer")), "Golden Manufacturing")
        config.setRaw(requireNotNull(SettingsRegistry.spec("model")), "Golden Panel")
        config.setAutoSleep(true)
        // Most config entities are opt-in. Expose a representative set so their discovery payloads, not
        // only their tombstones, are on the wire. Host-metric diagnostics stay hidden: they read /proc.
        listOf(
            "diag_wifi_outages_24h", "wake_on_wave", "auto_sleep",
            "auto_sleep_activity", "touch_sound", "kiosk_lock", "auto_brightness", "navbar_mode",
        ).forEach { config.setHaExposed(it, true) }
        configure(config)

        val transport = RecordingTransport()
        val sysfs = SysfsRootShell()
        val relay = RelayController(
            fakeProfile(relayBase = RELAY_BASE, buttonLedGpioBase = LED_GPIO_BASE),
            sysfs,
        )
        val brightness = BrightnessController(context, FakeRootShell(), FakeDaemon())
        val screen = ScreenController(
            FakeBacklight(160), FakeScreenPower(), FakeRootShell(), FakeDaemon(), FakeWakeTap(),
            ScreenOff.BRIGHTNESS_ZERO, nap = {},
        )
        val companion = "io.homeassistant.companion.android.minimal"
        val system = SystemController(
            FakeSystemEnv(), FakeRootShell(), FakeDaemon(replies = mapOf("RELOAD $companion" to "OK")),
            builtinForeground = { false }, homeDashboard = { config.homeDashboard },
            onCompanionHome = { pkg, path -> companionHomeReturns?.add("$pkg:$path") },
        )
        val bootChime = BootChimeController(
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
        val capabilities = Capabilities(
            hasProximity = true,
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
        )
        val storage = java.util.concurrent.atomic.AtomicReference(storageSnapshot(StorageHealthSeverity.HEALTHY, walBytes = 4_096))
        val storageReads = AtomicInteger()
        val updateSources = java.util.concurrent.atomic.AtomicReference(updateSources())
        val english = CatalogueLoader { path -> File("src/main/assets/$path").readText() }
        val autoSleepConfigChanges = AtomicInteger()
        val companionUpdateRequests = AtomicInteger()
        val selfUpdateRequests = AtomicInteger()

        val bridge = MqttBridge(
            config = config,
            brightness = brightness,
            screen = screen,
            led = led,
            ledEffect = LedEffectController(led),
            navigate = NavigateController(context),
            volume = VolumeController(context),
            system = system,
            // Never reached by the scenario: the navbar command below takes the capability refusal path.
            navbar = allocate(NavbarController::class.java),
            watchdog = WatchdogController(system, config),
            // Never reached: touch_sound needs an Android audio stack, so no touch_sound command is sent.
            touchSound = allocate(TouchSoundController::class.java),
            bootChime = bootChime,
            zigbee = ZigbeeController(fakeProfile(), FakeRootShell()),
            relay = relay,
            // Not reached: the capability snapshot reports no CPU governors.
            cpu = allocate(CpuController::class.java),
            // Only its best-effort reconnect reassertion runs, on a worker that logs and discards failure.
            adb = allocate(AdbController::class.java),
            buttonsEnabled = true,
            hasEvdevButtons = false,
            capabilities = { capabilities },
            hasProximity = true,
            hasTemperature = hasTemperature,
            hasHumidity = hasHumidity,
            hasCht8305 = false,
            hasButtonBacklight = true,
            hasMicrophone = true,
            media = media,
            hasCamera = cameraAvailability,
            lightChannel = lightChannel,
            // Never reached: no screen brightness or auto-brightness command is sent.
            autoBright = allocate(AutoBrightnessController::class.java),
            configUrl = { "http://192.0.2.10:8888/" },
            onUpdateCompanion = { companionUpdateRequests.incrementAndGet() },
            onSelfUpdate = { selfUpdateRequests.incrementAndGet() },
            softwareUpdateSources = { updateSources.get() },
            onDirectKioskSetting = { true },
            migrationNoticeEnglish = { key -> english.strings(AppLocale.ENGLISH).get(key) },
            storageHealth = { storageReads.incrementAndGet(); storage.get() },
            wifiOutages = { WifiOutageCounts(last24h = 3) },
            learnedProximityEligibility = { true },
            learnedProximityState = learnedProximityState,
            onAutoSleepConfigChanged = { autoSleepConfigChanges.incrementAndGet() },
            runtimePanelId = PANEL,
            runtimeFriendlyName = "Golden panel",
            runtimeBroker = runtimeBroker,
            runtimeMqttUser = "panel-user",
            runtimeMqttPassword = "panel-password",
            runtimeMqttAddressFamily = "Automatic",
            transport = transport,
            autoSleepActivity = autoSleepActivity,
            cameraSnapshotUrl = cameraSnapshotUrl,
            helperSend = helperSend ?: io.panelassistant.android.util.HelperClient::send,
        )
        return Rig(tmp, config, transport, sysfs, bridge, storage, storageReads, updateSources, autoSleepConfigChanges, companionUpdateRequests, selfUpdateRequests)
    }

    protected fun command(
        name: String,
        payload: String,
        transport: RecordingTransport,
        from: Int = transport.size(),
    ) {
        transport.deliver("ha-paneld/$PANEL/$name/set", payload)
        transport.awaitPublication(from, "$name state") { it.topic() == "ha-paneld/$PANEL/$name/state" }
        transport.drain()
    }

    protected fun local(transport: RecordingTransport, expectTopic: String, action: () -> Unit) {
        val from = transport.size()
        action()
        transport.awaitPublication(from, expectTopic) { it.topic() == expectTopic }
        transport.drain()
    }

    // ---- recording transport ----

    protected class RecordingTransport : MqttTransport {
        private val lock = Object()
        private val lines = ArrayList<String>()
        private val held = ArrayList<(Boolean) -> Unit>()
        private val subscriptions = LinkedHashMap<String, (String, ByteArray, Boolean) -> Unit>()
        private var lease: MqttConnectionLease? = null
        @Volatile private var lastActivityNanos = System.nanoTime()
        val delivered = AtomicInteger()
        private var blockedTopic: String? = null
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)

        fun blockNextPublish(topic: String) { blockedTopic = topic }

        private fun touch() {
            lastActivityNanos = System.nanoTime()
        }

        fun mark(section: String) = synchronized(lock) { lines += section; touch() }

        fun size(): Int = synchronized(lock) { lines.size }

        fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

        fun heldCount(): Int = synchronized(lock) { held.size }

        override fun connect(config: MqttConnectConfig, callbacks: MqttCallbacks) {
            val connection = MqttConnectionLease()
            synchronized(lock) {
                lines += listOf(
                    "connect",
                    "host=${config.host}",
                    "port=${config.port}",
                    "tls=${config.tls}",
                    "clientId=${config.clientId}",
                    "user=${config.user}",
                    "password=${config.password}",
                    "keepAlive=${config.keepAliveSeconds}",
                    "will=${config.willTopic}:${config.willPayload}",
                    "automaticReconnect=${config.automaticReconnect}",
                ).joinToString("\t")
                lease = connection
                touch()
            }
            callbacks.onConnected(connection, MqttAddressFamily.IPV4)
        }

        override fun disconnectDetached(): CompletableFuture<Unit> {
            synchronized(lock) { lease = null; touch() }
            return CompletableFuture.completedFuture(Unit)
        }

        override fun publishThenDisconnect(
            publications: List<MqttFinalPublish>,
            timeoutMs: Long,
        ): CompletableFuture<Unit> {
            synchronized(lock) {
                publications.forEach { lines += line(it.topic, it.payload, it.retain) }
                lease = null
                touch()
            }
            return CompletableFuture.completedFuture(Unit)
        }

        override fun publish(
            topic: String,
            payload: ByteArray,
            retain: Boolean,
            expectedConnection: MqttConnectionLease?,
            onComplete: ((Boolean) -> Unit)?,
        ) {
            if (blockedTopic == topic) {
                blockedTopic = null
                publishEntered.countDown()
                // Model a transport send that does not honor the worker's shutdown interrupt.
                while (releasePublish.count > 0L) {
                    try { releasePublish.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
                }
            }
            val admitted = synchronized(lock) {
                val current = lease
                if (current == null || (expectedConnection != null && expectedConnection !== current)) {
                    lines += "# dropped\t$topic"
                    false
                } else {
                    lines += line(topic, payload, retain)
                    onComplete?.let { held += it }
                    true
                }.also { touch() }
            }
            if (!admitted) onComplete?.invoke(false)
        }

        override fun subscribe(
            topicFilter: String,
            expectedConnection: MqttConnectionLease?,
            onMessage: (topic: String, payload: ByteArray, retained: Boolean) -> Unit,
        ) {
            synchronized(lock) {
                lines += "subscribe\t$topicFilter"
                subscriptions[topicFilter] = onMessage
                touch()
            }
        }

        override fun isCurrent(connection: MqttConnectionLease): Boolean =
            synchronized(lock) { lease === connection }

        /** Deliver a fresh (non-retained) inbound message to the matching subscription, as the broker does. */
        fun deliver(topic: String, payload: String) {
            val handler = synchronized(lock) {
                subscriptions.entries.firstOrNull { matches(it.key, topic) }?.value
            } ?: error("no subscription matches $topic")
            touch()
            delivered.incrementAndGet()
            handler(topic, payload.toByteArray(Charsets.UTF_8), false)
        }

        /** Wait for an expected publication. A timeout is recorded as a wire line, so it surfaces in the
         * golden diff instead of aborting the capture. */
        fun awaitPublication(from: Int, label: String, timeoutSeconds: Long = 10, predicate: (String) -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (System.nanoTime() < deadline) {
                if (synchronized(lock) { lines.drop(from).any { it.isPublication() && predicate(it) } }) return
                Thread.sleep(10)
            }
            mark("# timed out waiting for $label")
        }

        /** Release held acknowledgements in rounds until a quiet round has nothing left to release. */
        fun drain() {
            repeat(MAX_ROUNDS) {
                awaitQuiet()
                val batch = synchronized(lock) { held.toList().also { held.clear() } }
                if (batch.isEmpty()) return
                val done = CountDownLatch(1)
                StateConverger.dispatch {
                    try {
                        batch.forEach { it(true) }
                    } finally {
                        // Queued behind every task the acknowledgements themselves scheduled.
                        StateConverger.dispatch { done.countDown() }
                    }
                }
                check(done.await(30, TimeUnit.SECONDS)) { "acknowledgement release did not finish" }
                touch()
            }
            error("publications never settled after $MAX_ROUNDS acknowledgement rounds")
        }

        private fun awaitQuiet() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (true) {
                val pumpIdle = CountDownLatch(1)
                StateConverger.dispatch { pumpIdle.countDown() }
                check(pumpIdle.await(30, TimeUnit.SECONDS)) { "state convergence pump wedged" }
                val idleNanos = System.nanoTime() - lastActivityNanos
                if (idleNanos >= TimeUnit.MILLISECONDS.toNanos(QUIET_MS)) return
                check(System.nanoTime() < deadline) { "bridge never became quiet" }
                Thread.sleep(25)
            }
        }

        private fun matches(filter: String, topic: String): Boolean {
            val f = filter.split('/')
            val t = topic.split('/')
            if (f.size != t.size) return false
            return f.indices.all { f[it] == "+" || f[it] == t[it] }
        }

        private fun line(topic: String, payload: ByteArray, retain: Boolean): String {
            val normalised = String(payload, Charsets.UTF_8).let { text ->
                check(text.toByteArray(Charsets.UTF_8).contentEquals(payload)) { "non-UTF-8 payload on $topic" }
                text.replace(SW_VERSION, SW_VERSION_TOKEN)
                    .replace("1.2.3 (${BuildConfig.VERSION_CODE})", "1.2.3 (@@VERSION_CODE@@)")
            }
            // Base64 never yields "-", so it unambiguously marks an empty payload without a trailing tab.
            val encoded = if (normalised.isEmpty()) EMPTY_PAYLOAD else Base64.getEncoder().encodeToString(normalised.toByteArray(Charsets.UTF_8))
            return "$retain\t$topic\t$encoded"
        }

        companion object {
            const val MAX_ROUNDS = 200
            const val QUIET_MS = 400L
        }
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
        override fun fireAndForget(cmd: String) = true
        override fun listSysfs(path: String): String? = if (path == RELAY_BASE) "relay1 relay2" else null
        override fun readSysfs(path: String): String? = nodes[path]
        override fun prepareOutputGpio(gpio: Int) = true
        override fun writeSysfs(path: String, value: String): Boolean {
            if (path == blockPath) {
                blockPath = null
                blockEntered.countDown()
                check(blockRelease.await(30, TimeUnit.SECONDS)) { "blocked write never released" }
            }
            writes += "$path=$value"
            nodes[path] = value
            return true
        }
    }

    protected class FakeContext(
        private val files: File,
        private val prefs: SharedPreferences,
    ) : ContextWrapper(null) {
        private val audio = allocate(AudioManager::class.java)
        private val resolver = object : ContentResolver(null) {}
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = if (name == Context.AUDIO_SERVICE) audio else null
        override fun getContentResolver(): ContentResolver = resolver
        override fun getNoBackupFilesDir(): File = files
        override fun getFilesDir(): File = files
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun getPackageName(): String = "io.github.maxlyth.hapaneld"
    }

    /** Thread-safe in-memory preferences whose commits always succeed. */
    protected class MemoryPreferences : SharedPreferences by proxy(ConcurrentHashMap())

    // ---- rendering ----

    protected fun summarise(lines: List<String>): String {
        val sections = LinkedHashMap<String, MutableList<String>>()
        var current = "# preamble"
        lines.forEach { line ->
            if (line.startsWith("# ") && !line.startsWith("# dropped")) current = line
            else sections.getOrPut(current) { mutableListOf() } += line
        }
        val publications = lines.filter { it.isPublication() }
        return buildString {
            appendLine("MQTT wire golden summary")
            sections.forEach { (name, body) -> appendLine("$name: ${body.count { it.isPublication() }} publications, ${body.size} lines") }
            appendLine("discovery configs: ${publications.count { it.topic().startsWith("homeassistant/") }}")
            appendLine("state topics: ${publications.map { it.topic() }.filter { it.endsWith("/state") }.toSet().size}")
            appendLine("relay/button_led topics: ${publications.map { it.topic() }.filter { it.contains("/relay") || it.contains("/button_led") }.toSortedSet()}")
            appendLine("software update topics: ${publications.map { it.topic() }.filter { it.contains("/update/") || it.startsWith("homeassistant/update/") }.toSortedSet()}")
            appendLine("attributes topics: ${publications.map { it.topic() }.filter { it.endsWith("/attributes") }.toSortedSet()}")
            appendLine("retain=false publications: ${publications.count { it.startsWith("false\t") }}")
            appendLine("retain=false state topics: ${publications.filter { it.startsWith("false\t") && it.topic().endsWith("/state") }.map { it.topic() }.toSortedSet()}")
            appendLine("dropped: ${lines.count { it.startsWith("# dropped") }}")
        }
    }

    protected fun renderDiff(expected: List<String>, actual: List<String>): String {
        val first = expected.indices.firstOrNull { it >= actual.size || expected[it] != actual[it] } ?: expected.size
        return buildString {
            appendLine("MQTT wire output differs from $FIXTURE (expected ${expected.size} lines, actual ${actual.size}); first difference at line ${first + 1}:")
            var shown = 0
            var index = first
            while (shown < 20 && (index < expected.size || index < actual.size)) {
                val e = expected.getOrNull(index)
                val a = actual.getOrNull(index)
                if (e != a) {
                    appendLine("  line ${index + 1}")
                    appendLine("    expected: ${e?.let(::render) ?: "<none>"}")
                    appendLine("    actual:   ${a?.let(::render) ?: "<none>"}")
                    shown++
                }
                index++
            }
        }
    }

    protected companion object {
        fun drainStatePump() {
            val drained = CountDownLatch(1)
            StateConverger.dispatch { drained.countDown() }
            assertTrue("convergence pump drained", drained.await(5, TimeUnit.SECONDS))
        }

        const val PANEL = "golden"

        /** The MQTT labels each option code has always stood for: the legacy wire contract, written out. */
        val LEGACY_LABELS: Map<String, Map<Any, String>> = mapOf(
            "navbar" to mapOf("off" to "Off", "always_on" to "Always on", "swipe_reveal" to "Swipe reveal", "native" to "Native"),
            "cpu_governor" to mapOf("performance" to "Performance", "efficiency" to "Efficiency", "auto" to "Auto"),
        )

        /** Everything commandable except `cpu_governor`, `network_adb` and `zigbee_router`, which need hardware this rig lacks. */
        val COMMANDABLE_IN_RIG = setOf(
            "auto_brightness", "auto_sleep", "button_led1", "buttons", "camera_enabled",
            "home_dashboard", "kiosk_lock", "led", "navbar", "navigate", "prevent_idle_dim",
            "relay1", "relay2", "screen", "silence_boot_chime", "touch_sound",
            "volume", "wake_on_wave", "watchdog",
        )
        const val RELAY_BASE = "/sys/class/strelay"
        const val LED_GPIO_BASE = 147
        const val FIXTURE = "mqtt-wire-golden/bridge.txt"
        const val RECORD_ENV = "HAPANELD_RECORD_MQTT_GOLDEN"
        const val SW_VERSION_TOKEN = "@@SW_VERSION@@"
        const val EMPTY_PAYLOAD = "-"
        val SW_VERSION: String = jsonEscaped(
            io.panelassistant.android.appVersion(Config.VERSION, BuildConfig.VERSION_CODE),
        )

        fun jsonEscaped(value: String): String = io.panelassistant.android.util.Json.esc(value)

        // Source-text reason: locates this suite's own golden fixtures under src/test/resources, not app code.
        fun sourceFixture(): File = TestSources.appDir("src").resolve("test").resolve("resources")
            .resolve("mqtt-wire-golden").resolve("bridge.txt")

        fun String.isPublication(): Boolean = startsWith("true\t") || startsWith("false\t")
        fun String.topic(): String = split('\t')[1]
        fun String.isConfig(): Boolean = isPublication() && topic().startsWith("homeassistant/") && decodedPayload().isNotEmpty()
        fun String.decodedPayload(): String = split('\t')[2].let { encoded ->
            if (encoded == EMPTY_PAYLOAD) "" else String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        }
        fun render(line: String): String =
            if (line.isPublication()) "retain=${line.substringBefore('\t')} ${line.topic()} ${line.decodedPayload()}" else line

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
                panelAssistantOwnsPaneldUpdate = false,
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
            // The production constructor opens SQLite; the internal JVM seam has no content resolver,
            // which the discovery device block needs for serial_number.
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
                    "edit" -> editor(values) { key ->
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

        fun editor(values: MutableMap<String, Any>, onChange: (String) -> Unit = {}): SharedPreferences.Editor {
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

/** The bridge's whole wire output against the golden fixture, and the typed native form of every announced state. */
internal class MqttWireGoldenTest : MqttWireRig() {

    @Test(timeout = 180_000)
    fun `bridge wire output matches the golden fixture`() {
        val problems = mutableListOf<String>()
        val actual = capture(problems)
        val historical = actual.filterNot { line ->
            line.isPublication() && line.topic() in setOf(
                "homeassistant/binary_sensor/${PANEL}_panel_assistant_required/config",
                "ha-paneld/$PANEL/panel_assistant_required/state",
                "ha-paneld/$PANEL/panel_assistant_required/attributes",
            )
        }
        println(summarise(actual))
        if (System.getenv(RECORD_ENV) != "1") {
            val stream = javaClass.getResourceAsStream("/$FIXTURE")
                ?: error("missing golden fixture /$FIXTURE; record it with $RECORD_ENV=1")
            val expected = stream.bufferedReader().use { it.readLines() }.filter { it.isNotEmpty() }
            // The wire comparison comes first so a changed byte is reported as a readable diff even when
            // it also derails a scenario step; scenario problems are appended to the same failure.
            if (expected != historical) fail(renderDiff(expected, historical) + problems.joinToString("") { "\nscenario: $it" })
        }
        assertEquals("scenario problems", emptyList<String>(), problems)

        val swVersion = jsonEscaped(io.panelassistant.android.appVersion(Config.VERSION, BuildConfig.VERSION_CODE))
        actual.filter { it.isPublication() && !it.topic().startsWith("homeassistant/") }.forEach { line ->
            assertFalse(
                "normalisation token reached a non-discovery topic: ${line.topic()}",
                line.decodedPayload().contains(SW_VERSION_TOKEN),
            )
        }
        assertTrue("the device software version was never normalised", actual.any { it.isPublication() && it.decodedPayload().contains(SW_VERSION_TOKEN) })
        assertFalse("un-normalised software version leaked", actual.any { it.isPublication() && it.decodedPayload().contains(swVersion) })

        if (System.getenv(RECORD_ENV) == "1") {
            // Never bake a derailed scenario into the golden.
            assertEquals(
                "scenario markers in a recording",
                emptyList<String>(),
                actual.filter { it.startsWith("# timed out") || it.startsWith("# dropped") },
            )
            val target = sourceFixture()
            target.parentFile.mkdirs()
            target.writeText(historical.joinToString("\n", postfix = "\n"))
            println("recorded ${historical.size} lines to ${target.absolutePath}")
        }
    }

    /** Every state the bridge announces, on every channel it serves, has a typed native form. */
    @Test(timeout = 180_000)
    fun `every announced channel state translates to a native value`() {
        val rig = rig()
        try {
            rig.announce()
            val lines = rig.transport.snapshot()
            val translated = mutableSetOf<String>()
            for (converger in rig.bridge.stateChannelKeys()) {
                val wire = PanelAssistantChannelCatalog.wireChannel(converger) ?: continue
                val descriptor = requireNotNull(PanelAssistantChannelCatalog.describe(wire)) { wire }
                lines.filter { it.isPublication() && it.topic().startsWith("ha-paneld/$PANEL/") && it.topic().endsWith("/state") }
                    .filter { it.topic().removePrefix("ha-paneld/$PANEL/").removeSuffix("/state") == converger }
                    .forEach { line ->
                        val payload = line.decodedPayload()
                        assertNotNull(
                            "$wire state $payload has no native form",
                            PanelAssistantValueTranslation.translate(descriptor, StateConverger.Observation.Known(payload)),
                        )
                        translated += wire
                    }
            }
            assertTrue("retained state channels were announced: $translated", translated.containsAll(
                listOf("screen", "led", "navigate", "home_dashboard", "volume", "relay1", "navbar", "storage_health"),
            ))
        } finally {
            rig.close()
        }
    }
}

/** Command parity between MQTT and the native adapter, split from the fixture so test forks balance. */
internal class MqttNativeParityTest : MqttWireRig() {

    @Test fun `retired update settings are tombstoned and cannot install through either transport`() {
        val retired = mapOf(
            "self_update" to "switch", "update_channel" to "select",
            "companion_auto_update" to "switch", "companion_update_channel" to "select",
            "webview_auto_update" to "switch",
        )
        val rig = rig(hasCamera = true)
        try {
            rig.announce()
            val announcement = rig.transport.snapshot()
            val native = io.panelassistant.android.panelassistant.PanelAssistantShadowReporter(log = {})
            rig.bridge.addStateSink(native.bindShape(rig.bridge::nativeChannelShape))
            assertEquals(retired.keys.sorted(), native.offer().unsupported)
            assertTrue(native.offer().descriptors.map { it.channel }.containsAll(listOf("update_paneld", "update_companion")))
            retired.forEach { (channel, platform) ->
                assertFalse(channel, channel in rig.bridge.stateChannelKeys())
                assertNull(channel, PanelAssistantChannelCatalog.describe(channel))
                val discovery = "homeassistant/$platform/${PANEL}_${channel}/config"
                val state = "ha-paneld/$PANEL/$channel/state"
                for (topic in listOf(discovery, state)) {
                    val publications = announcement.filter { it.isPublication() && it.topic() == topic }
                    assertTrue("$topic was cleared", publications.isNotEmpty())
                    assertTrue("$topic has only retained tombstones", publications.all { it.startsWith("true\t") && it.decodedPayload().isEmpty() })
                }
            }
            val mark = rig.transport.size()
            repeat(2) {
                retired.keys.forEach { channel ->
                    val payload = if (channel.endsWith("channel")) "Pre-release" else "ON"
                    rig.transport.deliver("ha-paneld/$PANEL/$channel/set", payload)
                    assertEquals(channel,
                        PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_UNKNOWN_CHANNEL),
                        submitNative(rig, channel, payload))
                }
                barrier(rig, "retired")
            }
            rig.transport.drain()
            assertEquals(0, rig.companionUpdateRequests.get())
            assertEquals(0, rig.selfUpdateRequests.get())
            assertTrue(rig.transport.snapshot().drop(mark).none { line ->
                line.isPublication() && retired.keys.any { line.topic() == "ha-paneld/$PANEL/$it/state" } && line.decodedPayload().isNotEmpty()
            })

            rig.transport.deliver("ha-paneld/$PANEL/update_companion/set", "PRESS")
            rig.transport.deliver("ha-paneld/$PANEL/update_paneld/set", "PRESS")
            barrier(rig, "explicit")
            assertEquals(1, rig.companionUpdateRequests.get())
            assertEquals(1, rig.selfUpdateRequests.get())
        } finally {
            rig.close()
        }
    }


    @Test fun `repeated built-in navigate reloads this panels home`() {
        val rig = rig(configure = { config ->
            config.setHomeDashboard("/lovelace/this-panel")
            config.lastNavigate = "/lovelace/other-view"
        })
        try {
            rig.announce()
            BuiltinDashboard.navPath = null
            BuiltinDashboard.consumeReloadRequest()

            assertEquals(
                PanelAssistantCommandResult.Applied,
                submitNative(rig, "navigate", "/lovelace/other-view"),
            )

            assertTrue("same-route Navigate must request a real home reload", BuiltinDashboard.consumeReloadRequest())
            assertNull("the old view must not override home on reload", BuiltinDashboard.navPath)
        } finally {
            BuiltinDashboard.navPath = null
            BuiltinDashboard.consumeReloadRequest()
            rig.close()
        }
    }

    @Test fun `repeated Companion navigate requests this panels home after reload`() {
        val returns = mutableListOf<String>()
        val companion = "io.homeassistant.companion.android.minimal"
        val rig = rig(configure = { config ->
            config.setDashboardPackage(companion)
            config.setHomeDashboard("/kitchen-panel/0")
            config.lastNavigate = "/kitchen-panel/other"
        }, companionHomeReturns = returns)
        try {
            rig.announce()
            assertEquals(
                PanelAssistantCommandResult.Applied,
                submitNative(rig, "navigate", "/kitchen-panel/other"),
            )
            assertEquals(listOf("$companion:/kitchen-panel/0"), returns)
        } finally {
            rig.close()
        }
    }

    /**
     * Every commandable channel the bridge serves: the native value must translate to the exact payload
     * an MQTT client sends, and the native adapter must route it to a handler. One channel per wire shape
     * is also driven once over MQTT and once over the native adapter in two identical rigs, and both must
     * leave the same publications and hardware writes; each of those comparisons waits out a quiet window
     * per rig, so the rest share the shape's comparison. The MQTT payloads are written out here,
     * independently of the translation under test.
     */
    @Test(timeout = 300_000)
    fun `every commandable channel has the same effect over MQTT and the native adapter`() {
        val mqtt = rig()
        val native = rig()
        try {
            mqtt.announce()
            native.announce()
            val channels = native.bridge.stateChannelKeys()
                .mapNotNull(PanelAssistantChannelCatalog::wireChannel).distinct().sorted()
                .mapNotNull(PanelAssistantChannelCatalog::describe)
                .filter { it.platform in PanelAssistantCommandTranslation.COMMANDABLE_PLATFORMS }
                // Native only: no MQTT effect to compare (NativeMediaChannelTest covers it).
                .filter { it.kind != PanelAssistantValueKind.MEDIA }
            assertEquals(
                "commandable channels this rig serves",
                COMMANDABLE_IN_RIG,
                channels.map { it.channel }.toSet(),
            )
            val results = mutableMapOf<String, PanelAssistantCommandResult>()
            val representatives = channels.groupBy(::wireShape).values.map { it.first() }
            assertEquals("wire shapes compared", setOf("BOOLEAN", "NUMBER", "TEXT", "OPTION", "button_led", "led", "LIGHT"), representatives.map(::wireShape).toSet())
            for (descriptor in channels) for ((value, mqttPayload) in commandSamples(descriptor)) {
                val label = "${descriptor.channel} $value"
                assertEquals("$label payload", normalise(mqttPayload), PanelAssistantCommandTranslation.payload(descriptor, value)?.let(::normalise))
            }
            for (descriptor in representatives) for ((value, mqttPayload) in commandSamples(descriptor)) {
                val label = "${descriptor.channel} $value"
                val overMqtt = effect(mqtt, descriptor.channel) {
                    mqtt.transport.deliver("ha-paneld/$PANEL/${descriptor.channel}/set", mqttPayload)
                    barrier(mqtt, descriptor.channel)
                }
                var result: PanelAssistantCommandResult? = null
                val overNative = effect(native, descriptor.channel) {
                    result = submitNative(native, descriptor.channel, PanelAssistantCommandTranslation.payload(descriptor, value)!!)
                }
                results[label] = result!!
                assertEquals("$label effect", overMqtt, overNative)
                // What MQTT published as this channel's state, the native transport reports as a typed value.
                overNative.filter { it.startsWith("true\tha-paneld/$PANEL/${descriptor.channel}/state\t") }.forEach { line ->
                    val payload = line.substringAfterLast('\t').let { String(Base64.getDecoder().decode(it)) }
                    assertNotNull(
                        "$label state $payload has no native form",
                        PanelAssistantValueTranslation.translate(descriptor, StateConverger.Observation.Known(payload)),
                    )
                }
            }
            // The remaining channels only on the native rig, after every comparison, so the two rigs'
            // histories stay identical for the comparisons above.
            for (descriptor in channels - representatives.toSet()) for ((value, _) in commandSamples(descriptor)) {
                results["${descriptor.channel} $value"] =
                    submitNative(native, descriptor.channel, PanelAssistantCommandTranslation.payload(descriptor, value)!!)
            }
            val unrouted = results.filterValues {
                it == PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_UNKNOWN_CHANNEL) ||
                    it == PanelAssistantCommandResult.Refused(PanelAssistantCommandProcessor.CODE_NOT_COMMANDABLE)
            }
            assertEquals("commands the adapter did not route to a handler", emptyMap<String, PanelAssistantCommandResult>(), unrouted)
            // Refused, failed and pending outcomes still reached the common handler; only these two mean it did not.
            assertTrue("a command was applied", results.values.count { it == PanelAssistantCommandResult.Applied } >= 20)
            println("native parity results: $results")
        } finally {
            mqtt.close()
            native.close()
        }
    }
}

/** Discovery tombstones when a panel is withdrawn and configs again when it is released. */
internal class MqttDiscoveryWithdrawalTest : MqttWireRig() {

    @Test(timeout = 180_000)
    fun `a withdrawn panel tombstones every discovery topic it ever announced and publishes no config`() {
        val rig = rig { it.setPanelAssistantMqttDiscovery("withdraw") }
        try {
            rig.transport.mark("# announce")
            rig.announce()
            // The address the announcement evaluated is recorded even though no config carried it, so
            // the network callback's address check does not re-announce.
            rig.bridge.refreshDiscoveryAddress()
            // A re-announce is debounced, so give one time to fire before asserting that none did.
            Thread.sleep(1_000L)
            rig.transport.drain()
            val lines = rig.transport.snapshot()
            val configs = lines.filter { it.isPublication() && it.topic().startsWith("homeassistant/") }
            assertEquals(
                "one retained tombstone per historical config topic, and nothing else on the discovery prefix",
                mqttKnownConfigTopics(PANEL).map { "true\t$it\t$EMPTY_PAYLOAD" }.sorted(),
                configs.sorted(),
            )
            assertTrue("state topics still publish while withdrawn", lines.any { it.isPublication() && it.topic() == "ha-paneld/$PANEL/screen/state" })
            assertTrue("withdrawal clears migration state", lines.any { it.isPublication() && it.topic() == "ha-paneld/$PANEL/panel_assistant_required/state" && it.decodedPayload().isEmpty() && it.startsWith("true\t") })
            assertTrue("withdrawal clears migration guidance", lines.any { it.isPublication() && it.topic() == "ha-paneld/$PANEL/panel_assistant_required/attributes" && it.decodedPayload().isEmpty() && it.startsWith("true\t") })
            assertTrue("availability still publishes while withdrawn", lines.any { it.isPublication() && it.topic() == "ha-paneld/$PANEL/availability" && it.decodedPayload() == "online" })
            assertTrue("the command subscription is unchanged", lines.contains("subscribe\tha-paneld/$PANEL/+/set"))
            assertEquals("dropped publications", 0, lines.count { it.startsWith("# dropped") })
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 180_000)
    fun `withdrawing a live panel tombstones each topic once and releasing it announces the same configs again`() {
        val rig = rig()
        try {
            rig.transport.mark("# announce")
            rig.announce()
            val announced = rig.transport.snapshot().filter { it.isConfig() }.toSortedSet()
            assertTrue("the announcement published configs", announced.isNotEmpty())

            rig.transport.mark("# withdraw")
            val withdrawFrom = rig.transport.size()
            rig.config.setPanelAssistantMqttDiscovery("withdraw")
            rig.bridge.refreshPanelAssistantDiscovery()
            rig.transport.awaitPublication(withdrawFrom, "discovery tombstones") { it.topic().startsWith("homeassistant/") && it.decodedPayload().isEmpty() }
            rig.transport.drain()
            val withdrawn = rig.transport.snapshot().drop(withdrawFrom).filter { it.isPublication() && it.topic().startsWith("homeassistant/") }
            assertEquals(
                "one re-announce: every known topic tombstoned exactly once, no config",
                mqttKnownConfigTopics(PANEL).map { "true\t$it\t$EMPTY_PAYLOAD" }.sorted(),
                withdrawn.sorted(),
            )
            // An update entity's shape changing while withdrawn converges nothing: its config stays removed.
            val shapeFrom = rig.transport.size()
            rig.updateSources.set(updateSources(paneldTarget = null))
            rig.bridge.publishSoftwareUpdates()
            rig.transport.drain()
            assertEquals(
                "no discovery publication for a reshaped update entity while withdrawn",
                emptyList<String>(),
                rig.transport.snapshot().drop(shapeFrom).filter { it.isPublication() && it.topic().startsWith("homeassistant/") },
            )

            rig.transport.mark("# release")
            rig.updateSources.set(updateSources())
            val releaseFrom = rig.transport.size()
            rig.config.setPanelAssistantMqttDiscovery("announce")
            rig.bridge.refreshPanelAssistantDiscovery()
            rig.transport.awaitPublication(releaseFrom, "discovery configs") { it.isConfig() }
            rig.transport.drain()
            val released = rig.transport.snapshot().drop(releaseFrom)
            assertEquals("the release announces exactly the configs the connect announcement did", announced, released.filter { it.isConfig() }.toSortedSet())
            assertEquals("dropped publications", 0, rig.transport.snapshot().count { it.startsWith("# dropped") })
        } finally {
            rig.close()
        }
    }
}

/** The Panel Assistant migration problem entity announced until a connection is seen. */
internal class MqttMigrationProblemTest : MqttWireRig() {

    @Test(timeout = 180_000)
    fun `unconnected panel announces actionable problem until connection is seen despite local dismissal`() {
        val rig = rig()
        val configTopic = "homeassistant/binary_sensor/${PANEL}_panel_assistant_required/config"
        val stateTopic = "ha-paneld/$PANEL/panel_assistant_required/state"
        val attributesTopic = "ha-paneld/$PANEL/panel_assistant_required/attributes"
        try {
            rig.announce()
            val first = rig.transport.snapshot()
            assertTrue("unconnected panel must announce a nonempty migration config", first.any {
                it.isPublication() && it.topic() == configTopic && it.decodedPayload().isNotEmpty()
            })
            val discovery = first.last { it.isPublication() && it.topic() == configTopic && it.decodedPayload().isNotEmpty() }.decodedPayload()
            val entity = org.json.JSONObject(discovery)
            val english = org.json.JSONObject(File("src/main/assets/i18n/en.json").readText()).getJSONObject("strings")
            assertEquals(english.getJSONObject("shell.migration.title").getString("text"), entity.getString("name"))
            assertEquals("problem", entity.getString("device_class"))
            assertFalse("normal device entity, not hidden diagnostic", entity.has("entity_category"))
            assertEquals(stateTopic, entity.getString("state_topic"))
            assertEquals(attributesTopic, entity.getString("json_attributes_topic"))
            assertTrue("keep the panel's Visit URL", discovery.contains("http://192.0.2.10:8888/"))
            assertTrue(first.any { it.isPublication() && it.topic() == stateTopic && it.decodedPayload() == "ON" && it.startsWith("true\t") })
            val attributes = org.json.JSONObject(first.last { it.isPublication() && it.topic() == attributesTopic }.decodedPayload())
            val englishBody = english.getJSONObject("shell.migration.body").getString("text")
            assertEquals(englishBody, attributes.getString("message"))
            assertEquals("https://panel-assistant.io/go/migration", attributes.getString("url"))

            assertTrue(rig.config.dismissMigrationNotice())
            val afterDismiss = rig.transport.size()
            rig.bridge.refreshPanelAssistantDiscovery()
            rig.transport.awaitPublication(afterDismiss, "problem after local dismissal") { it.topic() == configTopic && it.decodedPayload().isNotEmpty() }
            rig.transport.drain()
            assertTrue(rig.transport.snapshot().drop(afterDismiss).any { it.isPublication() && it.topic() == configTopic && it.decodedPayload().isNotEmpty() })

            val afterConnection = rig.transport.size()
            rig.config.setPanelAssistantUpdateOwnerSeenMs(1L)
            rig.transport.awaitPublication(afterConnection, "problem tombstone") { it.topic() == configTopic && it.decodedPayload().isEmpty() }
            rig.transport.drain()
            val cleared = rig.transport.snapshot().drop(afterConnection)
            assertTrue(cleared.any { it.isPublication() && it.topic() == configTopic && it.decodedPayload().isEmpty() && it.startsWith("true\t") })
            assertTrue(cleared.any { it.isPublication() && it.topic() == stateTopic && it.decodedPayload().isEmpty() && it.startsWith("true\t") })
            assertTrue(cleared.any { it.isPublication() && it.topic() == attributesTopic && it.decodedPayload().isEmpty() && it.startsWith("true\t") })
            assertFalse(cleared.any { it.isPublication() && it.topic() == configTopic && it.decodedPayload().isNotEmpty() })

            val afterRefresh = rig.transport.size()
            rig.config.setPanelAssistantUpdateOwnerSeenMs(2L)
            rig.transport.drain()
            assertFalse("a later lease timestamp must not reannounce discovery", rig.transport.snapshot().drop(afterRefresh).any {
                it.isPublication() && it.topic() == configTopic
            })
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 90_000)
    fun `panel already seen by Panel Assistant never announces migration problem`() {
        val rig = rig { it.markPanelAssistantConnected() }
        try {
            rig.announce()
            val configTopic = "homeassistant/binary_sensor/${PANEL}_panel_assistant_required/config"
            val lines = rig.transport.snapshot()
            assertFalse(lines.any { it.isPublication() && it.topic() == configTopic && it.decodedPayload().isNotEmpty() })
            assertTrue(lines.any { it.isPublication() && it.topic() == configTopic && it.decodedPayload().isEmpty() && it.startsWith("true\t") })
            for (topic in listOf("ha-paneld/$PANEL/panel_assistant_required/state", "ha-paneld/$PANEL/panel_assistant_required/attributes")) {
                assertTrue(lines.any { it.isPublication() && it.topic() == topic && it.decodedPayload().isEmpty() && it.startsWith("true\t") })
            }
        } finally {
            rig.close()
        }
    }
}

/** Native authority takeover of queued MQTT commands, the native hello, and watchdog observation without MQTT. */
internal class MqttNativeAuthorityTest : MqttWireRig() {

    @Test(timeout = 90_000)
    fun queuedMqttCommandsYieldAtExecutionAfterNativeTakeover() {
        queuedAuthorityChange("native", expectedMqttWrites = 0)
    }

    @Test(timeout = 90_000)
    fun queuedMqttCommandsContinueWhenAuthorityRemainsShadow() {
        queuedAuthorityChange("shadow", expectedMqttWrites = 1)
    }

    @Test fun nativeHelloStatesTheChannelsThePanelCannotFillAndDescribesNone() {
        // A profile declaring no LED (the Shelly X2i; fakeProfile declares none) gets LedFactory's no-op controller.
        val rig = rig(
            hasTemperature = false, hasHumidity = false, learnedProximityState = { false },
            led = io.panelassistant.android.hardware.LedFactory.detect(fakeProfile()),
        )
        rig.updateSources.set(updateSources().copy(companionMinimalVersion = null, companionFullVersion = null))
        try {
            val native = io.panelassistant.android.panelassistant.PanelAssistantShadowReporter(log = {})
            rig.bridge.addStateSink(native.bindShape(rig.bridge::nativeChannelShape))
            val offer = native.offer()
            val absent = listOf("camera_enabled", "camera_snapshot", "humidity", "led", "proximity", "proximity_level", "temperature", "update_companion")
            assertEquals((absent + PanelAssistantChannelCatalog.RETIRED_CHANNELS).sorted(), offer.unsupported)
            val described = offer.descriptors.map { it.channel }
            absent.forEach { assertFalse("$it must not be described", it in described) }
            assertTrue("a channel the panel fills stays described", "screen" in described && "update_paneld" in described)
            assertEquals("the plain descriptor list agrees with the offer", described, native.descriptors().map { it.channel })
        } finally {
            rig.close()
        }
    }

    @Test(timeout = 90_000) fun nativeCameraReportsItsSharedControlWhileOnAndOff() {
        for (enabled in listOf(false, true)) {
            val rig = rig(hasCamera = true, configure = { it.setCameraEnabled(enabled) })
            try {
                val native = io.panelassistant.android.panelassistant.PanelAssistantShadowReporter(log = {})
                rig.bridge.addStateSink(native.bindShape(rig.bridge::nativeChannelShape))
                rig.announce()
                val offer = native.offer()
                assertFalse("camera_enabled" in offer.unsupported)
                val camera = offer.descriptors.single { it.channel == "camera_enabled" }
                assertEquals("camera", camera.platform)
                native.open(offer.descriptors)
                val observations = JSONObject(requireNotNull(native.next(1, "camera-session", 0))).getJSONArray("observations")
                val report = (0 until observations.length()).map(observations::getJSONObject).single { it.getString("channel") == "camera_enabled" }
                assertEquals("known", report.getString("state"))
                assertEquals(enabled, report.getBoolean("value"))
            } finally {
                rig.close()
            }
        }
    }

    @Test fun nativeKeepsTheLedDescribedWhenAProfileWithAnLedCannotReachItYet() {
        // A daemon-driven LED (TPA10, SMT1019) whose helper is not answering at hello probes false. The
        // profile still declares the LED, so the light stays described and its entity is never removed.
        val unreachable = object : LedController {
            override fun available() = false
            override fun colorCapable() = true
            override fun setRgb(r: Int, g: Int, b: Int) = false
            override fun off() = false
        }
        val rig = rig(led = unreachable)
        try {
            val native = io.panelassistant.android.panelassistant.PanelAssistantShadowReporter(log = {})
            rig.bridge.addStateSink(native.bindShape(rig.bridge::nativeChannelShape))
            val offer = native.offer()
            assertFalse("led" in offer.unsupported)
            assertTrue("led" in offer.descriptors.map { it.channel })
        } finally {
            rig.close()
        }
    }

    @Test fun nativeDescribesAChannelAgainOnceThePanelGainsIt() {
        val learned = java.util.concurrent.atomic.AtomicReference<Boolean?>(false)
        val rig = rig(learnedProximityState = learned::get)
        try {
            val native = io.panelassistant.android.panelassistant.PanelAssistantShadowReporter(log = {})
            rig.bridge.addStateSink(native.bindShape(rig.bridge::nativeChannelShape))
            val before = native.offer()
            assertEquals((listOf("camera_enabled", "camera_snapshot", "proximity", "proximity_level") + PanelAssistantChannelCatalog.RETIRED_CHANNELS).sorted(), before.unsupported)
            native.open(before.descriptors)
            assertFalse(native.descriptorsChanged())
            learned.set(true)
            assertTrue("gaining the reading ends the session so it is described again", native.descriptorsChanged())
            val after = native.offer()
            assertEquals((listOf("camera_enabled", "camera_snapshot") + PanelAssistantChannelCatalog.RETIRED_CHANNELS).sorted(), after.unsupported)
            assertTrue(after.descriptors.map { it.channel }.containsAll(listOf("proximity", "proximity_level")))
        } finally {
            rig.close()
        }
    }

    @Test fun nativeNeverStatesAnUnsettledOrPresentChannelUnsupported() {
        // Proximity not yet loaded (or closed), temperature and humidity present, a Companion installed: every
        // one is described as before; only retired settings are stated unsupported.
        val rig = rig(hasCamera = true, learnedProximityState = { null })
        try {
            val native = io.panelassistant.android.panelassistant.PanelAssistantShadowReporter(log = {})
            rig.bridge.addStateSink(native.bindShape(rig.bridge::nativeChannelShape))
            val offer = native.offer()
            assertEquals(PanelAssistantChannelCatalog.RETIRED_CHANNELS.sorted(), offer.unsupported)
            assertTrue(offer.descriptors.map { it.channel }.containsAll(
                listOf("humidity", "led", "proximity", "proximity_level", "temperature", "update_companion"),
            ))
            rig.updateSources.set(updateSources().copy(companionMinimalVersion = null, companionFullVersion = null))
            assertEquals("only retired settings and Companion settled absence are stated", (listOf("update_companion") + PanelAssistantChannelCatalog.RETIRED_CHANNELS).sorted(), native.offer().unsupported)
            // A failed package lookup is not an absence: the update channel stays described and is never stated.
            rig.updateSources.set(updateSources().copy(
                companionMinimalVersion = null, companionFullVersion = null, companionPresenceUnknown = true,
            ))
            val unknown = native.offer()
            assertEquals(PanelAssistantChannelCatalog.RETIRED_CHANNELS.sorted(), unknown.unsupported)
            assertTrue("update_companion" in unknown.descriptors.map { it.channel })
        } finally {
            rig.close()
        }
    }

    @Test fun watchdogObservesNativeChangesWithoutMqttConnection() {
        val rig = rig(runtimeBroker = "unsupported://broker")
        val owner = ServiceRuntimeOwner(rig.bridge, "native-observation-test")
        val native = io.panelassistant.android.panelassistant.PanelAssistantShadowReporter(log = {})
        rig.bridge.addStateSink(native.bind(rig.bridge::stateChannelKeys))
        try {
            requestWatchdogLocalObservation(owner) { it }
            drainStatePump()
            assertEquals("an unstarted service cannot observe hardware", 0, rig.storageReads.get())
            assertTrue(owner.start { it.start() }.get(5, TimeUnit.SECONDS))
            assertEquals("config-error", rig.bridge.state)
            assertEquals(null, rig.bridge.heartbeatConnectionGeneration())
            assertEquals(HeartbeatAdmission.Decision.NoCurrentConnection, HeartbeatAdmission.decide(
                rig.bridge.heartbeatConnectionGeneration(), emptyList(),
            ))
            requestWatchdogLocalObservation(owner) { it }
            drainStatePump()
            native.open(native.descriptors())
            fun report(id: Long): org.json.JSONObject {
                val json = native.next(id, "session", 0L)
                assertTrue("native report $id must be ready", json != null)
                return org.json.JSONObject(requireNotNull(json)).also {
                    native.onResult(
                        io.panelassistant.android.panelassistant.PanelAssistantReportResult.Acknowledged(id, emptyMap()),
                        0L,
                    )
                }
            }
            fun value(report: org.json.JSONObject, channel: String): String? {
                val observations = report.getJSONArray("observations")
                return (0 until observations.length()).map(observations::getJSONObject)
                    .firstOrNull { it.getString("channel") == channel }?.get("value")?.toString()
            }
            val initial = report(1)
            assertEquals("healthy", value(initial, "storage_health"))
            assertEquals("false", value(initial, "relay1"))
            report(2) // Complete the initial native snapshot.
            rig.storage.set(storageSnapshot(StorageHealthSeverity.WARNING, walBytes = 65_536))
            rig.sysfs.writeSysfs("$RELAY_BASE/relay1", "1")
            requestWatchdogLocalObservation(owner) { it }
            drainStatePump()
            val changed = report(3)
            assertEquals("warning", value(changed, "storage_health"))
            assertEquals("true", value(changed, "relay1"))

            val readsBeforeRetirement = rig.storageReads.get()
            owner.closeAdmission()
            rig.storage.set(storageSnapshot(StorageHealthSeverity.HEALTHY, walBytes = 4_096))
            rig.sysfs.writeSysfs("$RELAY_BASE/relay1", "0")
            requestWatchdogLocalObservation(owner) { it }
            drainStatePump()
            assertEquals("retired service must not sample", readsBeforeRetirement, rig.storageReads.get())
            assertEquals("retired service must not report", null, native.next(4, "session", 0L))
            assertEquals("config-error", rig.bridge.state)
            assertFalse("an invalid URL must not connect or publish MQTT", rig.transport.snapshot().any {
                it.startsWith("connect\t") || it.isPublication()
            })
        } finally {
            owner.shutdown(1_000) {}
            rig.close()
        }
    }

    @Test fun watchdogLocalObservationIsBoundedAndRechecksOwnerAtExecution() {
        val rig = rig(runtimeBroker = "unsupported://broker")
        val owner = ServiceRuntimeOwner(rig.bridge, "bounded-observation-test")
        val release = CountDownLatch(1)
        try {
            assertTrue(owner.start { it.start() }.get(5, TimeUnit.SECONDS))
            val entered = CountDownLatch(1)
            StateConverger.dispatch {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val before = rig.storageReads.get()
            repeat(100) { requestWatchdogLocalObservation(owner) { it } }
            release.countDown()
            drainStatePump()
            // The audit has two storage observers: health and its attributes.
            assertEquals("a burst admits exactly one hardware pass", before + 2, rig.storageReads.get())

            val retireRelease = CountDownLatch(1)
            try {
                val retireEntered = CountDownLatch(1)
                StateConverger.dispatch {
                    retireEntered.countDown()
                    check(retireRelease.await(10, TimeUnit.SECONDS))
                }
                assertTrue(retireEntered.await(5, TimeUnit.SECONDS))
                requestWatchdogLocalObservation(owner) { it }
                owner.closeAdmission()
            } finally {
                retireRelease.countDown()
            }
            drainStatePump()
            assertEquals("queued work must recheck its retired owner", before + 2, rig.storageReads.get())
        } finally {
            release.countDown()
            owner.shutdown(1_000) {}
            rig.close()
        }
    }
}
