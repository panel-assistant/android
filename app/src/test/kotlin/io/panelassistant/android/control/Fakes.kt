package io.panelassistant.android.control

import io.panelassistant.android.device.DeviceProfile
import io.panelassistant.android.device.EvdevButton
import io.panelassistant.android.device.LedMechanism
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.device.SuForm
import io.panelassistant.android.metrics.MetricSource
import io.panelassistant.android.platform.AccessibilityActions
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.DaemonLongResult
import io.panelassistant.android.platform.RootRunOutcome
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.platform.ScreenPower
import io.panelassistant.android.platform.SystemEnv

/** Minimal [DeviceProfile] for controller tests — only the fields a controller reads need setting. */
fun fakeProfile(
    appCanSu: Boolean = false,
    cpuGovernors: Map<String, String>? = null,
    relayBase: String? = null,
    relayBaseFallbacks: List<String> = emptyList(),
    buttonLedGpioBase: Int? = null,
    zigbeeGatewayDir: String? = null,
    evdevButtons: List<EvdevButton> = emptyList(),
    id: String = "test",
    revision: String = "test-revision",
    displayName: String = "Test",
    hasButtonBacklight: Boolean = false,
    recommendedDensity: Int? = null,
    recommendedFontScale: Float? = null,
): DeviceProfile = object : DeviceProfile {
    override val id = id
    override val revision = revision
    override val displayName = displayName
    override val socClass = "test"
    override val suForm = if (appCanSu) SuForm.TOOLBOX else SuForm.NONE
    override val appCanSu = appCanSu
    override val ledMechanism = LedMechanism.NONE
    override val screenOff = ScreenOff.BRIGHTNESS_ZERO
    override val zigbeeGatewayDir = zigbeeGatewayDir
    override val relayBase = relayBase
    override val relayBaseFallbacks = relayBaseFallbacks
    override val buttonLedGpioBase = buttonLedGpioBase
    override val hasButtonBacklight = hasButtonBacklight
    override val manufacturer: String? = null
    override val model: String? = null
    override val evdevButtons = evdevButtons
    override val cpuGovernors = cpuGovernors
    override val recommendedDensity = recommendedDensity
    override val recommendedFontScale = recommendedFontScale
}

/**
 * Fake [RootShell]. [outputs] maps a command substring to the stdout [runOutput] returns (the longest
 * matching key wins, so a specific path beats a generic one); [run] records the command
 * in [ran] and report [runResult]. No real process is ever spawned.
 */
class FakeRootShell(
    private val outputs: Map<String, String> = emptyMap(),
    private val available: Boolean = true,
    var runResult: Boolean = true,
    /** The effect a real root command would have had on the panel. Lets a test tell an actuator that
     *  genuinely changed the device from one that only reported success. */
    private val onRun: (String) -> Unit = {},
    /** A panel where no root process can be created at all — su absent, or present and not executable
     *  by this app. Never a root manager that ran the command and refused it, which is [runResult]
     *  false with this left alone. */
    private val suLaunchable: Boolean = true,
) : RootShell {
    val ran = mutableListOf<String>()
    val outputRan = mutableListOf<String>()
    val isolatedOutputRan = mutableListOf<String>()
    var availabilityChecks = 0
        private set
    override fun available(): Boolean { availabilityChecks++; return available }
    override fun run(cmd: String): Boolean { ran += cmd; onRun(cmd); return runResult }
    override fun runClassified(cmd: String): RootRunOutcome = when {
        run(cmd) -> RootRunOutcome.RAN_OK
        !suLaunchable -> RootRunOutcome.NO_LAUNCH
        else -> RootRunOutcome.RAN_FAILED
    }
    override fun runOutput(cmd: String): String? {
        outputRan += cmd
        return outputs.entries.sortedByDescending { it.key.length }.firstOrNull { cmd.contains(it.key) }?.value
    }
    override fun runOutputIsolatedBounded(cmd: String, maxBytes: Long, timeoutMs: Long): String? {
        isolatedOutputRan += cmd
        return outputs.entries.sortedByDescending { it.key.length }.firstOrNull { cmd.contains(it.key) }
            ?.value?.takeIf { it.toByteArray().size.toLong() <= maxBytes }
    }
    override fun runBytes(cmd: String): ByteArray? = null
}

/** Fake [MetricSource] for controllers that read through [io.panelassistant.android.metrics.PanelMetrics]
 *  (e.g. [CpuController]'s governor reads). Only the fields a test needs are set; everything else is the
 *  "unavailable" default. */
class FakeMetricSource(
    /** The live governor; a test changes it to model a governor set outside the panel's API. */
    @Volatile var governor: String? = null,
    private val availableGovernors: String? = null,
) : MetricSource {
    val governorRootFallbacks = mutableListOf<Boolean>()
    val availableGovernorRootFallbacks = mutableListOf<Boolean>()
    override fun perfDump(): String? = null
    override fun statText(): String? = null
    override fun meminfoText(): String? = null
    override fun loadavgText(): String? = null
    override fun thermalMilliValues(): List<Long> = emptyList()
    override fun gpuLoadRaw(): String? = null
    override fun cpuFreqCurMhz(): List<Long> = emptyList()
    override fun cpuFreqMaxMhz(): Long = 0
    override fun localIp(): String? = null
    override fun selinuxEnforce(): String? = null
    override fun cpuGovernor(allowRootFallback: Boolean): String? {
        governorRootFallbacks += allowRootFallback
        return governor
    }
    override fun cpuAvailableGovernors(allowRootFallback: Boolean): String? {
        availableGovernorRootFallbacks += allowRootFallback
        return availableGovernors
    }
    override fun roomClimateDaemon(): String? = null
}

/** Fake [Daemon]. [replies] maps an exact command line to its reply; sends are recorded in [sent]. */
class FakeDaemon(
    private val replies: Map<String, String> = emptyMap(),
    private val available: Boolean = true,
    /** Exact-command [sendLong] outcomes for results no reply line can express — a panel that goes
     *  down mid-command ends the socket rather than answering, which is [DaemonLongResult.Indeterminate]. */
    private val longOutcomes: Map<String, DaemonLongResult> = emptyMap(),
    /** The effect a real daemon command would have had on the panel — see [FakeRootShell.onRun]. */
    private val onSend: (String) -> Unit = {},
) : Daemon {
    val sent = mutableListOf<String>()
    val longTimeouts = mutableListOf<Long>()
    var availabilityChecks = 0
        private set
    override fun available(): Boolean { availabilityChecks++; return available }
    override fun send(cmd: String): String? { sent += cmd; onSend(cmd); return replies[cmd] }
    override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult {
        longTimeouts += timeoutMs
        longOutcomes[cmd]?.let { sent += cmd; return it }
        return send(cmd)?.let(DaemonLongResult::Reply) ?: DaemonLongResult.NotSubmitted
    }
    override fun sendBytes(cmd: String): ByteArray? = null
}

/** Fake [Backlight]: serves a settable [level] for getBrightness; records set calls. */
class FakeBacklight(var level: Int = 160) : Backlight {
    val calls = mutableListOf<String>()
    override fun getBrightness() = level
    override fun setBrightness(level: Int) { calls += "set:$level"; this.level = level }
    override fun setBrightnessRaw(level: Int) { calls += "raw:$level"; this.level = level }
}

/** Fake [ScreenPower]: settable interactivity and credential state; counts wake pulses. */
class FakeScreenPower(
    var interactive: Boolean = true,
    var deviceSecure: Boolean = false,
) : ScreenPower {
    var pulses = 0
    var brightnessPulses = 0
    override fun isInteractive() = interactive
    // Deliberately does NOT flip [interactive]: the real ACQUIRE_CAUSES_WAKEUP does wake the device,
    // but tests here assert the pulse was issued rather than modelling the platform's response, and
    // the exit-safety contract relies on driving interactivity explicitly.
    override fun pulseWake() { pulses++ }
    override fun brightenWhileOn(): Boolean {
        if (!interactive) return false
        brightnessPulses++
        return true
    }
    override fun isDeviceSecure() = deviceSecure

    // Android-sleep primitives. [timeoutMs] is the live SCREEN_OFF_TIMEOUT; [shortTimeoutSleeps] and
    // [lockSleeps] model whether the platform actually goes noninteractive when asked.
    var canWrite = false
    var timeoutMs = Int.MAX_VALUE
    var shortTimeoutSleeps = true
    var adminActive = false
    var lockSleeps = true
    var locks = 0
    override fun canWriteSystemSettings() = canWrite
    override fun screenOffTimeoutMs() = timeoutMs
    override fun setScreenOffTimeoutMs(ms: Int): Boolean {
        if (!canWrite) return false
        timeoutMs = ms
        timeoutWrites++
        // A set stay-on-while-plugged-in mask keeps Android awake whatever the timeout says.
        if (ms < 60_000 && shortTimeoutSleeps && stayOnMask <= 0) interactive = false
        return true
    }
    var timeoutWrites = 0
    var canWriteSecure = false
    var stayOnMask = 0
    override fun canWriteSecureSettings() = canWriteSecure
    override fun stayOnWhilePluggedInMask() = stayOnMask
    override fun setStayOnWhilePluggedInMask(mask: Int): Boolean {
        if (!canWriteSecure) return false
        stayOnMask = mask
        return true
    }
    override fun isDeviceAdminActive() = adminActive
    override fun lockNow(): Boolean {
        if (!adminActive) return false
        locks++
        if (lockSleeps) interactive = false
        return true
    }
    override fun releaseDeviceAdmin(): Boolean {
        if (!adminActive) return false
        adminActive = false
        return true
    }
}

/**
 * Fake [WakeTap]: [canArm] is settable (models overlay-permission held/not); records arm/disarm and
 * captures the arm callback so a test can [fireTap] to simulate a touch on the dark screen.
 */
class FakeWakeTap(
    var canArm: Boolean = true,
    var armSucceeds: Boolean = true,
) : io.panelassistant.android.platform.WakeTap {
    var armed = false
    private var onTap: (() -> Unit)? = null
    override fun canArm() = canArm
    override fun arm(onTap: () -> Unit): Boolean {
        if (!canArm || !armSucceeds) return false
        armed = true
        this.onTap = onTap
        return true
    }
    override fun disarm() { armed = false; onTap = null }
    /** Simulate a tap while armed — invokes the wake callback ScreenController registered. */
    fun fireTap() { onTap?.invoke() }
    /** The callback currently armed, kept by a test to run it late (a touch worker that lost a race). */
    fun armedCallback(): (() -> Unit)? = onTap
}

/**
 * Fake [SystemEnv]: an in-memory package/activity environment. [installed] gates [isInstalled];
 * [launchers] maps a package to its launch component; [homes] is the CATEGORY_HOME activity list;
 * [default] is the current default HOME. [directStarts] records best-effort direct starts.
 */
class FakeSystemEnv(
    override val ownPackage: String = "io.github.maxlyth.hapaneld",
    var installed: Set<String> = emptySet(),
    var launchers: Map<String, String> = emptyMap(),
    var homes: List<ActivityRef> = emptyList(),
    var default: ActivityRef? = null,
    var directStartSucceeds: Boolean = true,
    var preserveHomeSucceeds: Boolean = true,
    var homeTakeoverAllowed: Boolean = true,
    var pendingHomeTakeover: Boolean = false,
    var backgroundStartsAllowed: Boolean = false,
) : SystemEnv {
    val directStarts = mutableListOf<String>()
    var homeStarts = 0
    override fun backgroundStartsAllowed() = backgroundStartsAllowed
    override fun startDashboardHome(): Boolean {
        homeStarts++
        return true
    }
    override fun isInstalled(pkg: String) = pkg in installed
    override fun launchComponent(pkg: String) = launchers[pkg]
    override fun homeActivities() = homes
    override fun defaultHome() = default
    override fun preservePreviousHome() = preserveHomeSucceeds
    override fun homeTakeoverAllowed() = homeTakeoverAllowed
    override fun homeTakeoverPending() = pendingHomeTakeover
    override fun directStart(component: String): Boolean {
        directStarts += component
        return directStartSucceeds
    }
}

/**
 * Fake [io.panelassistant.android.platform.VendorScreen] modelling Stargate on the X2i: [node] is the
 * backlight node it writes behind Android's back ([answers] false: Stargate not running; [blanks] false:
 * it answers but the node does not follow; [readable] false: the app cannot read the node).
 */
class FakeVendorScreen(
    var node: Int = 128,
    var answers: Boolean = true,
    var blanks: Boolean = true,
    var readable: Boolean = true,
    private val onLevel: Int = 128,
) : io.panelassistant.android.platform.VendorScreen {
    val calls = mutableListOf<Boolean>()
    override fun set(on: Boolean): Boolean {
        calls += on
        if (!answers) return false
        if (blanks || on) node = if (on) onLevel else 0
        if (on) sinceOnMs = 0
        return true
    }
    /** Stargate 2.8.2: [lateOverwriteMs] after `on=true` its own level lands on the node again, once. */
    var lateOverwriteMs: Long? = null
    private var sinceOnMs: Long? = null
    /** Let [ms] pass; the controller's nap and the test both call this. */
    fun advance(ms: Long) {
        val before = sinceOnMs ?: return
        sinceOnMs = before + ms
        lateOverwriteMs?.let { at -> if (before < at && before + ms >= at) node = onLevel }
    }
    override fun level(): Int? = if (readable) node else null
    var holds = 0
    override fun holdBrightness(): Boolean { holds++; return answers }
}

/** [Backlight] over the Android setting, which re-drives [vendor]'s node only when the value changes. */
class SettingBacklight(private val vendor: FakeVendorScreen, var setting: Int = 131) : Backlight {
    override fun getBrightness() = vendor.node
    override fun setBrightness(level: Int) {
        if (level != setting) vendor.node = level
        setting = level
    }
    override fun setBrightnessRaw(level: Int) = setBrightness(level)
    override fun getCommanded() = setting
}

/** The app's own sizing route on a panel whose grants are held unless [granted] says otherwise. A write
 *  lands on the fake's settings only when granted, so a test sees what the panel would read back. */
class FakeAppSizing(
    var base: Int = 240,
    var override: Int? = null,
    var font: Float = 1.0f,
    private val granted: Boolean = true,
) : io.panelassistant.android.control.AppSizing {
    val writes = mutableListOf<String>()
    override fun densityReady() = granted
    override fun fontReady() = granted
    override fun density() = base to override
    override fun setDensity(dpi: Int?): Boolean {
        writes += "density:${dpi ?: "reset"}"
        if (granted) override = dpi
        return granted
    }
    override fun fontScale() = font
    override fun setFontScale(scale: Float): Boolean {
        writes += "font:$scale"
        if (granted) font = scale
        return granted
    }
}

/**
 * Fake accessibility service on a panel whose screensaver (a dream) may be in front. Back ends the
 * dream, as Android's DreamService does; each Back is recorded in [events], which a test can share
 * with a [FakeRootShell] `onRun` to check that Back came before an activity start.
 */
class FakeDreamAccessibility(
    @Volatile var dreaming: Boolean,
    val events: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf()),
) : AccessibilityActions {
    override fun dreamShowing() = dreaming
    override fun back(): Boolean { events += "back"; dreaming = false; return true }
    override fun recents() = false
    override fun tap(x: Int, y: Int) = false
}
