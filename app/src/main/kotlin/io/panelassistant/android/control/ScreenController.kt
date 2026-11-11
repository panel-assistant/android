package io.panelassistant.android.control

import android.util.Log
import io.panelassistant.android.device.ScreenOff
import io.panelassistant.android.input.PanelAccessibilityService
import io.panelassistant.android.platform.AccessibilityActions
import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.NoWakeTap
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.platform.NoVendorScreen
import io.panelassistant.android.platform.ScreenPower
import io.panelassistant.android.platform.VendorScreen
import io.panelassistant.android.platform.WakeTap
import io.panelassistant.android.platform.endDream
import io.panelassistant.android.util.HelperClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Collections
import java.util.IdentityHashMap

enum class WakeOutcome { WOKEN, ALREADY_ON, STALE_GENERATION, ACTUATION_FAILED }

enum class SleepOutcome { APPLIED, REFUSED }

/** Recovery ownership for one automatic off or visible safety dim; not proof that sleep applied. */
@JvmInline
value class AutomaticOffEpoch internal constructor(internal val generation: Long)

/**
 * Declared vs. actually-applied screen-off route, for reporting only. [declared] is the profile's
 * preference; [selected] is what the most recent [ScreenController.sleep]/[ScreenController.sleepAutomatically]
 * actually applied, or null if none has run since this controller was constructed — that is reported
 * as unexercised, never probed, because reading this must never itself touch su or the helper daemon.
 * [selected] and [reason] survive wake: they describe the last off, not the current live state.
 */
data class RouteSelection(val declared: ScreenOff, val selected: ScreenOff?, val reason: String)

/**
 * Durable memory of the `SCREEN_OFF_TIMEOUT` value the [ScreenOff.SCREEN_TIMEOUT] route displaced;
 * -1 means nothing is displaced. Durable so a process that dies while the panel sleeps still puts the
 * original timeout back at its next start instead of leaving a short timeout behind for good.
 */
interface DisplacedScreenTimeout {
    var displacedMs: Int

    /** The `STAY_ON_WHILE_PLUGGED_IN` mask the route cleared for the sleep; -1 when none. */
    var displacedStayOnMask: Int
}

/** Process-local [DisplacedScreenTimeout] for tests and callers with no durable store. */
class InMemoryDisplacedScreenTimeout(
    override var displacedMs: Int = -1,
    override var displacedStayOnMask: Int = -1,
) : DisplacedScreenTimeout

/** The durable store: shared by the screen-off route and prevent-idle-dim, which must agree on it. */
class ConfigDisplacedScreenTimeout(private val config: io.panelassistant.android.Config) : DisplacedScreenTimeout {
    override var displacedMs: Int
        get() = config.displacedScreenOffTimeout
        set(v) { config.displacedScreenOffTimeout = v }

    override var displacedStayOnMask: Int
        get() = config.displacedStayOnWhilePluggedIn
        set(v) { config.displacedStayOnWhilePluggedIn = v }
}

/**
 * Screen on/off — vendor-free with one serialized transition owner.
 *
 * The active profile selects helper, direct `su`, keyevent, screen-timeout, device-admin or
 * brightness-zero as the preferred route. Both bl_power routes ask the helper first and use `su` only
 * when no helper answers at all (a helper refusal degrades to brightness, never to `su`), and read
 * `bl_power` from the helper only, so repeated observation never starts a root shell; screen-timeout falls through to device-admin and then the visible
 * dim; an explicit brightness-zero profile never probes a privileged actuator.
 *
 * Whether a screen-off that goes through the keyguard (`DevicePolicyManager.lockNow()`, or any route
 * that leaves Android noninteractive) is acceptable depends on the panel, not on a fixed rule. On a
 * vanilla or Google Play build that may carry a PIN, it means the PIN on every wake; on a vendor shell
 * that hides Android entirely (the Meta Portal) there is normally no credential and it is the only
 * rootless way to a dark screen. So the profile picks the route, the panel's live keyguard state
 * decides the consequence, and diagnostics report a PIN-on-wake rather than this class guessing. The
 * collaborators are seamed ([Backlight], [ScreenPower], [RootShell], [Daemon], [WakeTap],
 * [DisplacedScreenTimeout]) so the never-blank logic is unit-testable without a device.
 *
 * **Never-blank guarantee: ha-paneld must always be able to undo its own screen-off.** How that is
 * guaranteed is a property of the route, and the two families differ:
 *
 * - The bl_power and brightness routes leave the device Awake, so the dashboard stays foreground and
 *   ha-paneld cannot see a wake tap itself. Each real off therefore arms a [WakeTap] (a consuming
 *   touch overlay) and a tap re-lights the panel without reaching the dashboard. If that cannot be confirmed ([WakeTap.arm] returns
 *   false), the off degrades to a visible dim rather than a true dark, so the panel can never look
 *   bricked — the failure mode that stranded a freshly-provisioned panel dark and touch-dead.
 * - [ScreenOff.KEYEVENT] puts Android itself noninteractive, which is a first-class platform state
 *   rather than a backlight ha-paneld blanked behind the framework's back. Its way back is
 *   [ScreenPower.pulseWake] — a wakelock with `ACQUIRE_CAUSES_WAKEUP` that needs no privilege at all,
 *   so it survives root and the helper daemon both disappearing, which the bl_power routes' way back
 *   does not. The touch overlay is deliberately NOT armed on this route: a noninteractive device does
 *   not dispatch touches to windows, so arming it would claim a local wake the route cannot provide.
 *   **Local touch wake on this route is a platform property, declared by the profile rather than
 *   probed** (`hardware.local_wake`, read as [io.panelassistant.android.device.DeviceProfile.localWake]; the same rule as
 *   `hasNativeNavbar`). It informs what the owner is told and changes no behaviour here: where the touchscreen is a kernel wake source
 *   Android wakes itself and [reconcilePhysicalWake] adopts that wake; where it is not, Home Assistant
 *   is the way back. This is the only route on which a wake can reach the panel without passing
 *   through [wake], so it is the reason that adoption has to be reachable from a plain platform
 *   screen-on broadcast and not only from the state sync tick — see [reconcilePhysicalWake]. The route
 *   also refuses to sleep a device with a configured credential
 *   ([ScreenPower.isDeviceSecure]): it exists for rooted boards where nothing else reports the PIN.
 * - [ScreenOff.SCREEN_TIMEOUT] and [ScreenOff.DEVICE_ADMIN] reach the same noninteractive state with
 *   no privilege and share the keyevent route's way back (the wakelock pulse, and adoption of a
 *   platform wake through [reconcilePhysicalWake]); the overlay is not armed for the same reason.
 *   Screen-timeout stops the renderer holding the screen on, writes a short `SCREEN_OFF_TIMEOUT` and
 *   waits for Android to sleep; the displaced value is restored on every wake and at the next
 *   construction, so a crash cannot leave the short timeout in force. A set `stay_on_while_plugged_in`
 *   would keep Android awake regardless, so with `WRITE_SECURE_SETTINGS` it is cleared for the sleep
 *   and restored with the timeout; without that grant the route does not wait at all. When
 *   `WRITE_SETTINGS` is missing, stay-on cannot be cleared, or the timeout does not take, it falls
 *   back to `lockNow()` if the app is an active device admin,
 *   else to the visible dim. These two routes sleep a secured device too and record the PIN-on-wake
 *   consequence in the selection reason.
 * - [ScreenOff.VENDOR_RPC] asks the vendor's system app ([VendorScreen], Shelly's Stargate) to blank
 *   the backlight. Android stays interactive, so it belongs to the backlight family: the touch overlay
 *   is armed first, and the off counts once the backlight node reads dark, or once the vendor answered
 *   where the node cannot be read. Otherwise it undoes whatever may have applied (vendor relight and a
 *   setting change) and takes the screen-timeout chain above only once the node reads lit; while
 *   darkness may remain it keeps the touch wake and owns the off itself. The vendor writes the node
 *   behind Android's back and relights to a level of its own, so every wake then changes
 *   `SCREEN_BRIGHTNESS` to the app's level, which makes Android drive the node again, and holds it
 *   there for a few seconds against a late vendor write; that also relights a panel whose vendor no
 *   longer answers.
 */
class ScreenController(
    private val backlight: Backlight,
    private val power: ScreenPower,
    private val root: RootShell = Su,
    private val daemon: Daemon = HelperClient,
    private val wakeTap: WakeTap = NoWakeTap,
    private val route: ScreenOff,
    /** Bounded wait between interactivity read-backs. Seamed so tests confirm without real time. */
    private val nap: (Long) -> Unit = { Thread.sleep(it) },
    private val displacedTimeout: DisplacedScreenTimeout = InMemoryDisplacedScreenTimeout(),
    /** Count of touches the renderer has seen. A change during the screen-timeout wait means a person
     *  is using the panel, which cancels the off rather than failing it. */
    private val touchCount: () -> Long = { BuiltinDashboard.touchCount },
    private val vendor: VendorScreen = NoVendorScreen,
    /** A screensaver keeps the panel interactive and lit, so an ON would otherwise leave it in front. */
    private val accessibility: AccessibilityActions = PanelAccessibilityService,
) {
    init {
        // A process that died while the screen-timeout route had the panel asleep left its short
        // timeout in force. Put the original back before anything else reads the setting.
        restoreDisplacedTimeout()
    }

    // Last known "on" level, used by the brightness fallback. Survives an off/on cycle.
    @Volatile private var savedLevel = DEFAULT_ON

    /** Invoked after a LOCAL touch-wake. A non-null epoch proves that this exact physical tap woke the
     * automatic OFF generation; null means it woke a manual OFF and must never train auto-sleep. */
    @Volatile var onWakeByTap: ((AutomaticOffEpoch?) -> Unit)? = null
    /** Invoked after every completed physical wake. Keep callbacks non-blocking; the service schedules
     * auto-brightness reconciliation away from this serialized screen transition. */
    @Volatile var onWakeCompleted: (() -> Unit)? = null

    // True only between a genuine screen-off and the next wake. The never-blank watchdog uses this to
    // tell a USER-intended dark screen (leave it) from an unintended one (re-light it) — so a stray/
    // stale screen-off can never strand the panel dark, but a deliberate "screen off" still stays off.
    @Volatile private var intendedOff = false
    @Volatile private var appliedOffRoute: ScreenOff? = null
    private var brightnessZeroOff = false // guarded by this transition monitor; excludes a visible safety dim
    private var startupSuBlPowerReadUsed = false  // guarded by this monitor (adoptInheritedDark)
    // Durable report of the last off, kept across wake unlike [appliedOffRoute] above (which exists
    // only while intentionally off). [routeSelection] reads these two directly rather than probing.
    @Volatile private var lastSelectedRoute: ScreenOff? = null
    @Volatile private var lastSelectionReason: String = REASON_UNEXERCISED
    private val stateGeneration = AtomicLong()
    @Volatile private var intendedOffGeneration = 0L
    @Volatile private var observedDarkGeneration = 0L
    private var rendererAsleep = false // guarded by this transition monitor
    @Volatile private var automaticOffGeneration = 0L
    private val admissionClosed = AtomicBoolean(false)
    private val visibleHolds = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    /** A service-owned interactive journey temporarily refuses every app-controlled off route. */
    @Synchronized
    fun acquireVisibleHold(owner: Any): Boolean {
        if (admissionClosed.get()) return false
        if (ensureOn() == WakeOutcome.ACTUATION_FAILED) return false
        return synchronized(visibleHolds) {
            if (admissionClosed.get()) false else {
                visibleHolds.add(owner)
                true
            }
        }
    }

    /** Release does not restore an earlier dark state; ordinary policy may sleep again afterwards. */
    @Synchronized
    fun releaseVisibleHold(owner: Any) {
        synchronized(visibleHolds) { visibleHolds.remove(owner) }
    }

    fun isOn(): Boolean = power.isInteractive()

    /** Whether the last screen state ha-paneld set was a deliberate off (vs. never-asked / woken). */
    fun isIntendedOff(): Boolean = intendedOff

    /** What the screen-timeout sleep this controller currently holds displaced, or null while it holds
     *  none. A record left by a failed restore is not configured state, whether the panel is awake or
     *  a later off fell back to another route: the live values are then what the panel runs. */
    fun heldSleepRecord(): DisplacedScreenTimeout? =
        displacedTimeout.takeIf { intendedOff && appliedOffRoute == ScreenOff.SCREEN_TIMEOUT }

    /** Keep the held/live timeout choice and its write atomic with sleep and wake restoration. */
    @Synchronized
    fun updateScreenOffTimeout(apply: (DisplacedScreenTimeout?) -> Boolean): Boolean =
        apply(heldSleepRecord())

    /**
     * Declared route (the profile's preference) versus what the last actual off applied, and why they
     * differ. A pure read of durable state recorded at the last [sleepInternal] — it never probes su or
     * the helper daemon, so calling this is always safe from a diagnostics render.
     */
    fun routeSelection(): RouteSelection = RouteSelection(route, lastSelectedRoute, lastSelectionReason)

    private fun recordSelection(selected: ScreenOff, reason: String) {
        lastSelectedRoute = selected
        lastSelectionReason = reason
    }

    /** Serialize a brightness write with screen transitions. Either the write completes before a later
     * sleep (which then wins), or an already-intended off rejects it; ALS can never relight an OFF panel. */
    @Synchronized
    fun actuateBrightnessIfOn(action: () -> Unit): Boolean {
        if (intendedOff || admissionClosed.get()) return false
        action()
        return true
    }

    /** A live approach restores idle brightness only on a proven lit screen; it never owns a wake. */
    @Synchronized
    fun brightenForPresence(admit: () -> Boolean): Boolean {
        if (intendedOff || admissionClosed.get() || !power.isInteractive() || observedLit() != true) return false
        if (!admit()) return false
        return power.brightenWhileOn()
    }

    /** Best-effort: is the backlight actually dark? bl_power 4=off/0=on (root/daemon panels); else the
     *  brightness-fallback path where 0 == off. Unknown → false (never re-light on a guess). */
    fun observedDark(): Boolean? {
        val effective = { backlight.getBrightness().takeIf { it >= 0 }?.let { it <= 0 } }
        fun fromPower(power: Int): Boolean? {
            // A powered backlight with an effective level of zero is still physically dark.
            return if (power == 0) effective() else true
        }
        return when (route) {
            // Without a bl_power reading, a zero level is still dark; a positive one proves nothing while
            // this controller holds the backlight powered down, so it is unknown rather than lit. Treating it
            // as lit would let the state tick adopt a false wake and disarm the touch wake on a dark panel.
            ScreenOff.DAEMON_BLPOWER, ScreenOff.SU_BLPOWER -> observedBlPower()?.let(::fromPower)
                ?: effective().takeUnless { it == false && holdsBlPowerOff() }
            // Android's own interactivity IS this route's screen state; there is no backlight node to
            // read and no third "unknown" answer to give.
            ScreenOff.KEYEVENT, ScreenOff.SCREEN_TIMEOUT, ScreenOff.DEVICE_ADMIN -> !power.isInteractive()
            ScreenOff.BRIGHTNESS_ZERO -> effective()
            // The node, never the setting: the vendor blanks the node and leaves SCREEN_BRIGHTNESS lit.
            ScreenOff.VENDOR_RPC -> vendor.lit()?.not()
        }
    }

    /**
     * Affirmative proof that the screen is physically lit for a process handoff. Unlike [observedDark],
     * privileged bl_power profiles never degrade to brightness-only evidence: a positive level cannot
     * prove visibility while the panel's backlight power remains off.
     */
    fun observedLit(): Boolean? {
        val effectiveLit = { backlight.getBrightness().takeIf { it >= 0 }?.let { it > 0 } }
        return when (route) {
            ScreenOff.DAEMON_BLPOWER, ScreenOff.SU_BLPOWER -> observedBlPower()?.let { powerState ->
                if (powerState == 0) effectiveLit() else false
            }
            ScreenOff.KEYEVENT, ScreenOff.SCREEN_TIMEOUT, ScreenOff.DEVICE_ADMIN -> power.isInteractive()
            ScreenOff.BRIGHTNESS_ZERO -> effectiveLit()
            ScreenOff.VENDOR_RPC -> vendor.lit()?.let { it && power.isInteractive() }
        }
    }

    // Helper only on both bl_power routes: this read repeats every state tick, never-blank check and
    // reconcile, and repeated root reads never use su (maintainer rule, 2026-11-05). No answer is unknown.
    private fun observedBlPower(): Int? = when (route) {
        ScreenOff.DAEMON_BLPOWER, ScreenOff.SU_BLPOWER ->
            daemon.send("BLPOWER")?.trim()?.toIntOrNull()?.takeIf { it in 0..4 }
        ScreenOff.KEYEVENT, ScreenOff.SCREEN_TIMEOUT, ScreenOff.DEVICE_ADMIN, ScreenOff.BRIGHTNESS_ZERO,
        ScreenOff.VENDOR_RPC -> null
    }

    /** Startup adoption's darkness check. Without a helper `BLPOWER` answer, a positive level cannot rule
     *  out a backlight a replaced process powered down through su, so one su read of `bl_power` is made,
     *  once per process: rare by construction, and never on a tick. */
    private fun inheritedDark(): Boolean {
        if (observedBlPower() == null && !startupSuBlPowerReadUsed) {
            startupSuBlPowerReadUsed = true
            val power = root.runOutput(blPowerRead())?.trim()?.toIntOrNull()?.takeIf { it in 0..4 }
            if (power != null && power != 0) return true
        }
        return observedDark() == true
    }

    private fun holdsBlPowerOff(): Boolean =
        intendedOff && appliedOffRoute.let { it == ScreenOff.DAEMON_BLPOWER || it == ScreenOff.SU_BLPOWER }

    /** Cautious boolean used by the never-blank watchdog: unknown is not grounds to alter hardware. */
    fun looksDark(): Boolean = observedDark() == true

    /** Recover only a dark backlight on an otherwise interactive panel. A non-interactive device has
     * entered Android's normal screen sleep and must not be woken by the periodic never-blank guard.
     * That rule is what makes the watchdog inert on [ScreenOff.KEYEVENT], where "dark" IS
     * noninteractive: the guard exists to undo a backlight ha-paneld blanked behind the framework's
     * back, and it must not start fighting Android's own sleep to reach a route that has none.
     * The potentially slow hardware observation stays outside the transition monitor; its generation
     * is then admitted atomically with the wake so a concurrent explicit screen-off always wins. */
    fun recoverUnexpectedDark(): Boolean {
        if (intendedOff || admissionClosed.get() || !power.isInteractive()) return false
        val observedGeneration = stateGeneration.get()
        if (!looksDark()) return false
        return recoverUnexpectedDark(observedGeneration)
    }

    @Synchronized
    private fun recoverUnexpectedDark(observedGeneration: Long): Boolean {
        if (
            intendedOff || admissionClosed.get() ||
            stateGeneration.get() != observedGeneration || !power.isInteractive()
        ) return false
        // Brightness writes do not represent a screen-state generation, so fresh readback is the final
        // authority: a newer positive write must win over the stale dark observation made above.
        if (!looksDark() || !power.isInteractive()) return false
        return wake()
    }

    /**
     * Startup only: take ownership of a backlight that a replaced process left powered off. An update
     * install kills the old process without [close], so its deliberate off survives in the hardware but
     * not in [intendedOff], and the never-blank guard would relight a panel nobody asked to wake. Only
     * the bl_power routes can be inherited this way, and only with a touch wake to arm; anything less
     * is left to the guard. Returns the automatic epoch when [automatic], else null.
     */
    @Synchronized
    fun adoptInheritedDark(automatic: Boolean): AutomaticOffEpoch? {
        if (route != ScreenOff.DAEMON_BLPOWER && route != ScreenOff.SU_BLPOWER) return null
        if (intendedOff || admissionClosed.get() || !wakeTap.canArm() || !power.isInteractive()) return null
        if (!inheritedDark()) return null
        val outcome = sleepInternal(automatic)
        // A refused or degraded off would leave a dark backlight marked intended with no way back.
        if (appliedOffRoute != ScreenOff.DAEMON_BLPOWER && appliedOffRoute != ScreenOff.SU_BLPOWER) {
            if (intendedOff) wake()
            // No inherited OFF was adopted. Leave the startup retry and never-blank guard free
            // to recover a backlight whose consuming overlay could not yet attach.
            stateGeneration.incrementAndGet()
            intendedOffGeneration = 0L
            observedDarkGeneration = 0L
            automaticOffGeneration = 0L
            intendedOff = false
            brightnessZeroOff = false
            appliedOffRoute = null
            wakeTap.disarm()
            return null
        }
        Log.i(TAG, "adopted a dark screen left by a replaced process (automatic=$automatic)")
        return if (outcome == SleepOutcome.APPLIED) automaticEpochOrNull() else null
    }

    /** Record an explicit brightness so the fallback off/on restores to it. */
    fun noteLevel(level: Int) {
        if (level > 0) savedLevel = level.coerceIn(1, 255)
    }

    @Synchronized
    fun sleep(): SleepOutcome = sleepInternal(automatic = false)

    /**
     * Turn the screen off on behalf of an automatic policy and return ownership of that exact epoch.
     * If the screen is already intentionally off, the existing (possibly manual) owner wins and no
     * epoch is returned. This prevents an automatic controller from adopting and later waking a manual
     * screen-off.
     */
    @Synchronized
    fun sleepAutomatically(): AutomaticOffEpoch? =
        if (sleepInternal(automatic = true) == SleepOutcome.APPLIED) automaticEpochOrNull() else null

    /** A refused off may still have dimmed the screen. Keep its recovery separate from sleep proof. */
    @Synchronized
    fun automaticRecoveryEpoch(): AutomaticOffEpoch? = automaticEpochOrNull()

    private fun sleepInternal(automatic: Boolean): SleepOutcome {
        var androidSleepRefusal: String? = null
        var helperRefusedOff = false
        if (admissionClosed.get() || synchronized(visibleHolds) { visibleHolds.isNotEmpty() } || (automatic && intendedOff)) return SleepOutcome.REFUSED
        intendedOffGeneration = stateGeneration.incrementAndGet()
        automaticOffGeneration = if (automatic) intendedOffGeneration else 0L
        observedDarkGeneration = 0L
        brightnessZeroOff = false
        intendedOff = true
        // Never go dark without a guaranteed way back. Which guarantee applies is a property of the
        // route (see the class KDoc): the backlight families need a touch overlay, because they leave
        // Android interactive and cannot see the wake tap themselves, and a real off without one would
        // strand the panel dark and touch-dead — the "looks bricked" failure this rule exists for. The
        // keyevent route brings its own unprivileged wake instead, and refuses only where a device
        // credential would stand between that wake and the dashboard.
        val wakeTapGeneration = intendedOffGeneration
        val wakeTapAutomaticEpoch = automaticEpochOrNull()
        val refusal = when (route) {
            // Android's own sleep is undone by the unprivileged wakelock pulse in wake(), so this
            // route brings its own guarantee and never arms the overlay (see the class KDoc). The one
            // thing that can put a barrier in front of the dashboard on the way back is a configured
            // credential, so a secured device is refused rather than slept.
            ScreenOff.KEYEVENT ->
                if (power.isDeviceSecure()) "a device credential would gate the wake" else null
            // Same unprivileged way back as keyevent. A credential is reported, not refused: whether a
            // PIN on wake is acceptable is the panel owner's call (see the class KDoc).
            ScreenOff.SCREEN_TIMEOUT, ScreenOff.DEVICE_ADMIN -> null
            else -> if (
                wakeTap.canArm() && wakeTap.arm {
                    // A touch observer may hand work to another thread. By the time that worker runs,
                    // a manual command or a later automatic transition may own a different OFF epoch.
                    // Never let the old tap wake that newer state: retain and prove the exact epoch
                    // armed here.
                    if (wakeIfStillDark(wakeTapGeneration) == WakeOutcome.WOKEN) {
                        onWakeByTap?.invoke(wakeTapAutomaticEpoch)
                    }
                }
            ) null else "no touch-to-wake"
        }
        if (refusal != null && route != ScreenOff.VENDOR_RPC) return dimToFloor(refusal)
        // Guaranteed locally wakeable: arm the tap, then power the backlight off for real. Every route below
        // that powers the display off freezes the WebView. Brightness zero needs readback first:
        // a panel that clamps a visible minimum must keep its renderer awake.
        val poweredOff = when (route) {
            // Both bl_power routes ask the helper first and use su only when no helper answers at all.
            // A helper that answered and refused is not overridden by su: it degrades to the floor below.
            ScreenOff.DAEMON_BLPOWER, ScreenOff.SU_BLPOWER -> when (daemon.send("SCREEN OFF")) {
                "OK" -> ScreenOff.DAEMON_BLPOWER to
                    if (route == ScreenOff.DAEMON_BLPOWER) REASON_DECLARED else REASON_HELPER_BEFORE_SU
                null -> if (root.run(blPower(false))) {
                    ScreenOff.SU_BLPOWER to
                        if (route == ScreenOff.SU_BLPOWER) REASON_DECLARED else
                            "the declared helper daemon route was unavailable; fell back to su bl_power"
                } else null
                else -> { helperRefusedOff = true; null }
            }
            ScreenOff.KEYEVENT -> if (sleepByKeyevent()) ScreenOff.KEYEVENT to REASON_DECLARED else null
            ScreenOff.SCREEN_TIMEOUT -> when (val slept = sleepAndroid(REASON_DECLARED)) {
                AndroidSleep.Touched -> return cancelOffByTouch()
                is AndroidSleep.Slept -> slept.route to slept.reason
                is AndroidSleep.Refused -> { androidSleepRefusal = slept.reason; null }
            }
            ScreenOff.VENDOR_RPC -> when (val off = if (refusal != null) VendorOff.Refused(refusal) else sleepByVendor()) {
                is VendorOff.Dark -> ScreenOff.VENDOR_RPC to off.reason
                is VendorOff.Refused -> when (val slept = sleepAndroid("the vendor screen RPC was refused (${off.reason}); fell back to the screen timeout")) {
                    AndroidSleep.Touched -> return cancelOffByTouch()
                    is AndroidSleep.Slept -> slept.route to slept.reason
                    is AndroidSleep.Refused -> { androidSleepRefusal = "${off.reason}; ${slept.reason}"; null }
                }
            }
            ScreenOff.DEVICE_ADMIN ->
                if (sleepByLockNow()) ScreenOff.DEVICE_ADMIN to withPinNote(REASON_DECLARED)
                else {
                    androidSleepRefusal = "no active device admin could lock the screen"
                    null
                }
            ScreenOff.BRIGHTNESS_ZERO -> null
        }
        if (poweredOff != null) {
            val (poweredOffRoute, reason) = poweredOff
            appliedOffRoute = poweredOffRoute
            recordSelection(poweredOffRoute, reason)
            // A successful bl_power actuator is authoritative for this exact off epoch. This permits
            // a quick external wake to reconcile before the next heartbeat. Brightness-zero remains
            // unconfirmed until read back dark because some panels visibly clamp its raw zero.
            noteObservedDark(intendedOffGeneration)
            Log.d(TAG, "screen -> off (${poweredOffRoute.name.lowercase()})")
            return SleepOutcome.APPLIED
        }
        // Last resort. A raw zero is admissible only because the overlay above was armed, so a tap
        // still re-lights the panel. The keyevent route has no overlay by design, and its way back is
        // the wakelock pulse, which nobody standing at the panel can trigger — so it must not reach a
        // dark it cannot let a person out of, and degrades to the visible floor instead.
        if (route == ScreenOff.KEYEVENT) return dimToFloor("an unconfirmed keyevent sleep")
        if (route.sleepsAndroid || route == ScreenOff.VENDOR_RPC) {
            return dimToFloor(androidSleepRefusal ?: "an unconfirmed Android sleep")
        }
        // No daemon, no su — dim to 0 (only a dim on panels that clamp a minimum). Uses the raw setter
        // so it can reach 0: the public setBrightness floors at MIN_VISIBLE to stay never-blank.
        val cur = backlight.getCommanded()
        if (cur > 0) savedLevel = cur
        backlight.setBrightnessRaw(0)
        appliedOffRoute = ScreenOff.BRIGHTNESS_ZERO
        brightnessZeroOff = true
        recordSelection(
            ScreenOff.BRIGHTNESS_ZERO,
            if (route == ScreenOff.BRIGHTNESS_ZERO) {
                REASON_BRIGHTNESS_ZERO_NEVER_PROBES
            } else {
                if (helperRefusedOff) {
                    "the helper daemon refused the bl_power off; degraded to a brightness-only off"
                } else {
                    "neither su bl_power nor the helper daemon was available; degraded to a brightness-only off"
                }
            },
        )
        Log.d(TAG, "screen -> off (brightness fallback; saved=$savedLevel)")
        return if (observedDark() == true) {
            noteObservedDark(intendedOffGeneration)
            SleepOutcome.APPLIED
        } else {
            rendererAsleep = false
            BuiltinDashboard.onScreenAwake(true)
            SleepOutcome.REFUSED
        }
    }

    /**
     * Degrade an off to a visible dim, the never-blank floor. [appliedOffRoute] records what was
     * actually applied rather than what was asked for, so [wake] restores the level this dimmed
     * instead of retrying a privileged actuator that was never used. The screen stays VISIBLE, so the
     * built-in renderer must not be frozen here: a frozen WebView on a still-lit dashboard would show
     * stale, un-tappable cards.
     */
    private fun dimToFloor(reason: String): SleepOutcome {
        val cur = backlight.getCommanded()
        if (cur > 0) savedLevel = cur
        backlight.setBrightness(NO_WAKE_DIM)
        rendererAsleep = false
        BuiltinDashboard.onScreenAwake(true)
        appliedOffRoute = ScreenOff.BRIGHTNESS_ZERO
        recordSelection(
            ScreenOff.BRIGHTNESS_ZERO,
            "the declared route was refused ($reason); dimmed to the never-blank floor instead",
        )
        Log.w(TAG, "screen-off with $reason — dimming to floor (never-blank; saved=$savedLevel)")
        return SleepOutcome.REFUSED
    }

    private fun cancelOffByTouch(): SleepOutcome {
        // Someone is using the panel: the off is cancelled, not failed. No lock, no dim.
        wake()
        recordSelection(ScreenOff.SCREEN_TIMEOUT, REASON_CANCELLED_BY_TOUCH)
        Log.i(TAG, "screen-timeout off cancelled by a touch")
        return SleepOutcome.REFUSED
    }

    private sealed interface AndroidSleep {
        data class Slept(val route: ScreenOff, val reason: String) : AndroidSleep
        data class Refused(val reason: String) : AndroidSleep
        data object Touched : AndroidSleep
    }

    /** The unprivileged Android-sleep chain: the short screen timeout, then the device-admin lock. */
    private fun sleepAndroid(timeoutReason: String): AndroidSleep {
        val timeoutRefusal = sleepByTimeout() ?: return AndroidSleep.Slept(ScreenOff.SCREEN_TIMEOUT, withPinNote(timeoutReason))
        if (timeoutRefusal == REFUSAL_TOUCHED) return AndroidSleep.Touched
        if (sleepByLockNow()) {
            return AndroidSleep.Slept(
                ScreenOff.DEVICE_ADMIN,
                withPinNote("the screen timeout was unavailable ($timeoutRefusal); fell back to the device-admin lock"),
            )
        }
        return AndroidSleep.Refused("$timeoutRefusal, and no active device admin could lock the screen")
    }

    /**
     * Ask the vendor to blank the backlight with the touch overlay already armed, and prove it on the
     * node. Null on a confirmed dark; else why not, with the overlay disarmed and the vendor asked to
     * relight, so the Android-sleep fallback never starts behind a node the vendor left at zero.
     */
    private fun sleepByVendor(): VendorOff {
        val level = backlight.getBrightness()
        if (level > 0) savedLevel = level
        val answered = vendor.set(false)
        if (answered && awaitVendorLit(false)) return VendorOff.Dark(REASON_DECLARED)
        if (answered && vendor.lit() == null) return VendorOff.Dark(REASON_VENDOR_UNREAD)
        // Unconfirmed, and perhaps applied with its reply lost: undo it, by the vendor and by a setting
        // change Android re-drives, before another route owns the screen.
        vendor.set(true)
        stepSettingTo(savedLevel.coerceAtLeast(MIN_ON))
        if (vendor.lit() == true) {
            wakeTap.disarm()
            return VendorOff.Refused(if (answered) "the backlight did not read dark" else "it did not answer")
        }
        // Darkness may remain: only the armed touch wake is sure to let a person out, so keep it.
        return VendorOff.Dark(REASON_VENDOR_UNRESOLVED)
    }

    private sealed interface VendorOff {
        data class Dark(val reason: String) : VendorOff
        data class Refused(val reason: String) : VendorOff
    }

    /**
     * Put the app's commanded level back on a lit panel whose vendor set the node to a level of its own
     * (Stargate starts at its own saved level after a boot). Nothing while intentionally off.
     */
    @Synchronized
    fun restoreLevelOverVendor() {
        if (intendedOff || admissionClosed.get()) return
        val level = backlight.getCommanded()
        if (level > 0 && vendor.level() != level) settleAtAppLevel(level)
    }

    /**
     * Step the setting to [level], then hold it there for [VENDOR_SETTLE_MS] against a vendor write that
     * lands after the step: on Shelly 2.8.2 `Ui.Screen.Set` relit the node, the step put the app's level
     * on, and Stargate's own level (25) replaced it within 2 s. Assumes the node and the setting share a
     * scale, as on the Shelly profiles (identity transfer: setting 151 drives node 151).
     */
    private fun settleAtAppLevel(level: Int) {
        stepSettingTo(level)
        var waited = 0L
        // The first step always runs (fail-open); corrections stop once the controller is closing.
        while (waited < VENDOR_SETTLE_MS && !admissionClosed.get()) {
            nap(VENDOR_SETTLE_POLL_MS)
            waited += VENDOR_SETTLE_POLL_MS
            if (admissionClosed.get()) return
            val node = vendor.level() ?: return
            // The newest commanded level: a slider moved during the hold is followed, never overwritten.
            val target = backlight.getCommanded().takeIf { it > 0 } ?: level
            if (node != target) stepSettingTo(target)
        }
    }

    /** A retry touch belongs to the wake that armed it: any later transition or closure makes it inert. */
    @Synchronized
    private fun retryVendorWake(generation: Long) {
        if (admissionClosed.get() || stateGeneration.get() != generation) return
        wake()
    }

    /** The vendor writes the node behind Android's back, and Android drives it again only when the
     *  setting changes: an equal write leaves it as it is (measured on the X2i). So change it twice. */
    private fun stepSettingTo(level: Int) {
        backlight.setBrightness(if (level < 255) level + 1 else level - 1)
        backlight.setBrightness(level)
    }

    private fun awaitVendorLit(expected: Boolean): Boolean {
        var waited = 0L
        while (true) {
            val lit = vendor.lit() ?: return false
            if (lit == expected) return true
            if (waited >= VENDOR_CONFIRM_MS) return false
            nap(KEYEVENT_POLL_MS)
            waited += KEYEVENT_POLL_MS
        }
    }

    private fun automaticEpochOrNull(): AutomaticOffEpoch? =
        automaticOffGeneration.takeIf { it != 0L }?.let(::AutomaticOffEpoch)

    /**
     * Light the screen only if it is not already lit. An ON command that carries no brightness is a
     * request for a state, not for a relight: on a panel that is already on it must change nothing.
     * [wake] cannot promise that, because its brightness fallback restores [savedLevel], and on the
     * brightness-zero route that is whatever the last off remembered, floored to [MIN_ON] and then
     * persisted by the write, so a redundant ON dimmed a lit panel to a few percent.
     *
     * Both halves of the guard are load-bearing. [intendedOff] comes first because a brightness-zero
     * panel that clamps a minimum reads lit after a deliberate off, and lit alone would make ON after
     * OFF a permanent no-op there. The hardware read-back comes second because an unexpectedly dark
     * panel must still relight on an explicit ON; an unknown reading counts as dark, so never-blank
     * fails toward light. A device Android itself has put noninteractive is not on either, whatever
     * its backlight node reads, so it keeps the wakelock pulse a bare ON always delivered. Check and
     * act share the transition monitor, so a concurrent sleep is ordered wholly before this call or
     * wholly after it, never between the read and the wake.
     */
    @Synchronized
    fun ensureOn(): WakeOutcome {
        accessibility.endDream { Log.i(TAG, it) }
        if (!intendedOff && power.isInteractive() && observedLit() == true) return WakeOutcome.ALREADY_ON
        return runCatching {
            if (wake()) WakeOutcome.WOKEN else WakeOutcome.ACTUATION_FAILED
        }.getOrElse {
            Log.e(TAG, "explicit wake failed", it)
            WakeOutcome.ACTUATION_FAILED
        }
    }

    @Synchronized
    fun wake(): Boolean {
        // Before the pulse: a wake under the short timeout would fall asleep again moments later.
        restoreDisplacedTimeout()
        val wakingRoute = appliedOffRoute ?: route
        when (wakingRoute) {
            ScreenOff.DAEMON_BLPOWER, ScreenOff.SU_BLPOWER -> {
                val helper = daemon.send("SCREEN ON")
                if (helper == "OK") {
                    return completeWake("screen -> on (daemon bl_power)")
                }
                // Wake is event-driven, so su may follow any reply short of OK, whoever applied the off: a
                // helper refusal must not leave a backlight dark that this app powered down (never-blank).
                // Without su this simply fails through to the brightness restore below.
                if (root.run(blPower(true))) {
                    return completeWake("screen -> on (su bl_power)")
                }
            }
            ScreenOff.KEYEVENT -> {
                // The wakelock pulse inside completeWake is what actually guarantees this wake, and
                // it needs no privilege, so a failed injection is not worth reporting as a failure —
                // and the brightness fallback below must not run, because this route never changed
                // the brightness and restoring a remembered level would move it for no reason.
                val injected = injectKeyevent("WAKEUP")
                return completeWake(
                    if (injected) "screen -> on (keyevent wakeup)"
                    else "screen -> on (wakelock pulse; keyevent wakeup unavailable)"
                )
            }
            // Never changed the brightness either; the unprivileged pulse alone is the wake.
            ScreenOff.SCREEN_TIMEOUT, ScreenOff.DEVICE_ADMIN -> {
                return completeWake("screen -> on (wakelock pulse; ${wakingRoute.yamlName})")
            }
            ScreenOff.VENDOR_RPC -> {
                val answered = vendor.set(true)
                val relit = answered && awaitVendorLit(true)
                // The vendor relights to a level of its own, or, unanswered, leaves the node at the 0 it
                // wrote; either way the app's level goes back on, and stays on through a late vendor write.
                settleAtAppLevel(savedLevel.coerceAtLeast(MIN_ON))
                // Known dark, or unanswered and unknown: a vendor still enforcing its off may hold the
                // node at zero. Keep a consuming touch that retries this wake, so a person is
                // never left with a dark panel and no way back.
                val finalLit = vendor.lit()
                val woke = completeWake(if (relit) "screen -> on (vendor rpc)" else "screen -> on (brightness; the vendor screen RPC did not relight it)")
                if (!intendedOff && (finalLit == false || (!answered && finalLit == null)) && !admissionClosed.get() && wakeTap.canArm()) {
                    val generation = stateGeneration.get()
                    wakeTap.arm { retryVendorWake(generation) }
                }
                return woke
            }
            ScreenOff.BRIGHTNESS_ZERO -> Unit
        }
        backlight.setBrightness(savedLevel.coerceAtLeast(MIN_ON))
        return completeWake("screen -> on (brightness fallback; $savedLevel)")
    }

    /**
     * Put Android noninteractive and prove it happened. `input` is an `app_process` wrapper, and one
     * has been reported exiting zero under the helper daemon's sanitized environment without doing
     * anything, so a submitted request is never accepted as a state change: each transport is judged
     * by the interactivity it produced, and the next one is tried when the first only claimed to work.
     * Root goes first because a full environment is the form the behaviour was reported working in.
     *
     * The transport's own answer is read for nothing here, deliberately. A lost reply is indis-
     * tinguishable from a refusal, and on a daemon-only panel the reply is lost as a matter of course:
     * the helper client abandons an ordinary command read after half a second, while `input` is still
     * starting, so "actuated but the caller never heard" is this route's ordinary case, not its edge
     * case. Reading interactivity after every submitted attempt is what makes that case land right.
     *
     * Re-submitting SLEEP to an already-sleeping panel is harmless, which is part of why this route
     * takes named keys: `KEYCODE_SLEEP` asks for one direction, where `KEYCODE_POWER` would toggle and
     * a duplicate would undo the first.
     */
    private fun sleepByKeyevent(): Boolean =
        keyeventTransports().any { transport ->
            transport.submit("SLEEP")
            awaitInteractive(false, transport.confirmMs)
        }

    /**
     * Let Android's own screen timeout put the panel to sleep, and prove it did. Null on a confirmed
     * sleep, else why not. The displaced timeout is recorded durably BEFORE the short one is written,
     * so a process that dies at any point after the write still finds the value to restore.
     *
     * Android floors the timeout at its own minimum (10 s on AOSP) counted from the last user
     * activity, and releasing the renderer's keep-screen-on flag counts as activity, so this waits
     * [TIMEOUT_CONFIRM_MS] — much longer than the keyevent route, and inside the transition monitor.
     * A touch during that wait restarts Android's clock because a person is using the panel; that
     * ends the wait at once as [REFUSAL_TOUCHED], so the caller keeps the panel awake instead of
     * treating it as a timeout that did not take.
     */
    private fun sleepByTimeout(): String? {
        if (!power.canWriteSystemSettings()) return "WRITE_SETTINGS is not granted"
        // Stay-on-while-plugged-in keeps Android awake whatever the timeout says, so waiting out the
        // timeout under it only delays the fallback by the whole confirmation window. Clear it for
        // the sleep when Panel Assistant granted WRITE_SECURE_SETTINGS; otherwise refuse at once.
        val stayOn = power.stayOnWhilePluggedInMask()
        if (stayOn > 0 && !power.canWriteSecureSettings()) return REFUSAL_STAY_ON
        if (displacedTimeout.displacedMs <= 0) {
            val current = power.screenOffTimeoutMs()
            if (current <= 0) return "the current screen timeout could not be read"
            displacedTimeout.displacedMs = current
        }
        if (stayOn > 0) {
            // Recorded before the write, like the timeout, so a process that dies asleep restores it.
            if (displacedTimeout.displacedStayOnMask <= 0) displacedTimeout.displacedStayOnMask = stayOn
            if (!power.setStayOnWhilePluggedInMask(0)) {
                restoreDisplacedTimeout()
                return "$STAY_ON_SETTING could not be cleared"
            }
        }
        val touchesBefore = touchCount()
        BuiltinDashboard.releaseKeepScreenOn(true)
        val outcome = if (!power.setScreenOffTimeoutMs(SLEEP_TIMEOUT_MS)) {
            "the short screen timeout could not be written"
        } else {
            var waited = 0L
            while (true) {
                if (!power.isInteractive()) return null
                if (touchCount() != touchesBefore) break
                if (waited >= TIMEOUT_CONFIRM_MS) break
                // The renderer's WebView can take a view-level keep-screen-on of its own mid-wait
                // (media playback); ask the renderer to drop it again on every tick.
                BuiltinDashboard.reassertKeepScreenOnRelease()
                nap(KEYEVENT_POLL_MS)
                waited += KEYEVENT_POLL_MS
            }
            if (touchCount() != touchesBefore) REFUSAL_TOUCHED
            else "the short screen timeout did not make Android noninteractive"
        }
        restoreDisplacedTimeout()
        return outcome
    }

    /** `lockNow()` as an active device admin, proven by Android leaving the interactive state. */
    private fun sleepByLockNow(): Boolean =
        power.isDeviceAdminActive() && power.lockNow() && awaitInteractive(false, LOCK_CONFIRM_MS)

    private fun withPinNote(reason: String): String =
        if (power.isDeviceSecure()) "$reason; a screen lock credential is set, so waking needs the PIN" else reason

    /** Put back the timeout the screen-timeout route displaced and let the renderer hold the screen
     *  on again. A failed write keeps the record, so the next wake or start tries again. */
    private fun restoreDisplacedTimeout() {
        BuiltinDashboard.releaseKeepScreenOn(false)
        val stayOn = displacedTimeout.displacedStayOnMask
        if (stayOn > 0) {
            if (power.setStayOnWhilePluggedInMask(stayOn)) displacedTimeout.displacedStayOnMask = -1
            else Log.w(TAG, "could not restore $STAY_ON_SETTING ($stayOn); will retry on the next wake")
        }
        val displaced = displacedTimeout.displacedMs
        if (displaced <= 0) return
        if (power.setScreenOffTimeoutMs(displaced)) displacedTimeout.displacedMs = -1
        else Log.w(TAG, "could not restore the screen timeout ($displaced ms); will retry on the next wake")
    }

    /** Best-effort wake injection; the caller's wakelock pulse is the actual guarantee. */
    private fun injectKeyevent(name: String): Boolean =
        keyeventTransports().any { transport -> transport.submit(name) }

    /**
     * A way to inject, paired with the window inside which its injection can still take effect. The
     * confirmation window must never be shorter than the transport's actuation bound, or a sleep that
     * is merely slow gets recorded as a sleep that failed.
     */
    private class KeyeventTransport(val confirmMs: Long, val submit: (String) -> Boolean)

    private fun keyeventTransports(): List<KeyeventTransport> = listOf(
        // Root is synchronous: `input` has already run by the time the call returns, so the window
        // only has to cover Android acting on the key.
        KeyeventTransport(ROOT_CONFIRM_MS) { name -> root.run("input keyevent ${keycodeOf(name)}") },
        // The daemon's is not. Its reply is abandoned long before the helper's own injection deadline,
        // so this window matches that deadline rather than the socket timeout.
        KeyeventTransport(DAEMON_CONFIRM_MS) { name -> daemon.send("KEYEVENT $name") == "OK" },
    )

    /** Named keys only, resolved here so no caller or profile document can select another keycode. */
    private fun keycodeOf(name: String): Int = when (name) {
        "SLEEP" -> KEYCODE_SLEEP
        "WAKEUP" -> KEYCODE_WAKEUP
        else -> throw IllegalArgumentException("unsupported screen keyevent: $name")
    }

    /** Poll interactivity for a bounded interval. False means the actuator did not do what it said. */
    private fun awaitInteractive(expected: Boolean, windowMs: Long): Boolean {
        var waited = 0L
        while (true) {
            if (power.isInteractive() == expected) return true
            if (waited >= windowMs) return false
            nap(KEYEVENT_POLL_MS)
            waited += KEYEVENT_POLL_MS
        }
    }

    /** Token for generation-safe local wake work. Null means there is no deliberate screen-off to wake. */
    fun currentOffGeneration(): Long? = intendedOffGeneration.takeIf { intendedOff && it != 0L }

    /** Freeze only a confirmed dark epoch; a later recovery from a visible write can freeze it again. */
    @Synchronized
    fun noteObservedDark(expectedGeneration: Long?): Boolean {
        if (
            expectedGeneration == null || !intendedOff ||
            expectedGeneration != intendedOffGeneration || stateGeneration.get() != expectedGeneration
        ) return false
        observedDarkGeneration = expectedGeneration
        if (!rendererAsleep) {
            rendererAsleep = true
            BuiltinDashboard.onScreenAwake(false)
        }
        return true
    }

    /** An outside setting write belongs to the OFF epoch seen at delivery, never a newer one. */
    @Synchronized
    fun noteBrightnessWhileOff(expectedGeneration: Long, level: Int) {
        if (!intendedOff || expectedGeneration != intendedOffGeneration || !brightnessZeroOff) return
        noteLevel(level)
        maintainBrightnessZeroOff()
    }

    /** Firmware brightness changes cannot wake an owned raw-zero off. Keep their useful level for ON. */
    @Synchronized
    fun maintainBrightnessZeroOff() {
        if (!intendedOff || !brightnessZeroOff || observedDarkGeneration != intendedOffGeneration) return
        val level = backlight.getBrightness()
        if (level > 0) {
            // The hardware node and Android's setting may use different scales.
            backlight.getCommanded().takeIf { it > 0 }?.let(::noteLevel)
            backlight.setBrightnessRaw(0)
        }
    }

    @Synchronized
    fun reconcileObservedLit(expectedGeneration: Long?): Boolean {
        // The read belongs to one exact off epoch, and that epoch must previously have been observed
        // genuinely dark. This distinguishes a physical wake from brightness-zero that clamps visible.
        if (
            expectedGeneration == null || !intendedOff ||
            expectedGeneration != intendedOffGeneration || stateGeneration.get() != expectedGeneration ||
            observedDarkGeneration != expectedGeneration
        ) return false
        if (brightnessZeroOff) {
            // A failed reassertion is visibly ON, but the OFF owner still retries and owns its tap.
            rendererAsleep = false
            BuiltinDashboard.onScreenAwake(true)
            return true
        }
        stateGeneration.incrementAndGet()
        intendedOffGeneration = 0L
        observedDarkGeneration = 0L
        automaticOffGeneration = 0L
        intendedOff = false
        appliedOffRoute = null
        wakeTap.disarm()
        restoreDisplacedTimeout()
        rendererAsleep = false
        BuiltinDashboard.onScreenAwake(true)
        onWakeCompleted?.invoke()
        return true
    }

    /**
     * Adopt a wake this controller did not perform, from whatever noticed it.
     *
     * [reconcileObservedLit] needs an epoch and a proof of lit, and until now the only place that
     * assembled both was the state sync tick. That was sufficient while every route left
     * Android interactive, because then nothing outside ha-paneld could light the panel on its own —
     * a physical touch reached the armed overlay and came back through [wake]. [ScreenOff.KEYEVENT]
     * breaks that assumption: Android can wake itself from a power key or a wake-capable touchscreen
     * with ha-paneld uninvolved. Leaving the adoption inside the sync tick would mean a panel that a person
     * woke by hand stays [intendedOff] with its renderer frozen until Home Assistant is reachable — a lit
     * screen showing a stale, un-tappable dashboard, which is a worse failure than a dark one because
     * it looks alive.
     *
     * Whoever calls this must be off the main thread: on the bl_power routes [observedLit] reads a
     * backlight through root or the daemon, and neither is main-safe.
     */
    @Synchronized
    fun reconcilePhysicalWake(): Boolean {
        val generation = currentOffGeneration() ?: return false
        // Route-agnostic on purpose: a spurious screen-on for a route whose backlight is still off
        // must not clear the off intent, so the panel has to be observed genuinely lit first.
        if (observedLit() != true) return false
        return reconcileObservedLit(generation)
    }

    /** Reject a gesture queued for an older screen state. This is called only from the existing wake
     * worker, so privileged reads and writes remain off the sensor/main threads. */
    @Synchronized
    fun wakeIfStillDark(expectedGeneration: Long, admissionStillValid: () -> Boolean = { true }): WakeOutcome {
        if (!intendedOff) return WakeOutcome.ALREADY_ON
        if (expectedGeneration != intendedOffGeneration || stateGeneration.get() != expectedGeneration) {
            return WakeOutcome.STALE_GENERATION
        }
        if (!admissionStillValid()) return WakeOutcome.STALE_GENERATION
        return runCatching {
            if (wake()) WakeOutcome.WOKEN else WakeOutcome.ACTUATION_FAILED
        }.getOrElse {
            Log.e(TAG, "generation-safe wake failed", it)
            WakeOutcome.ACTUATION_FAILED
        }
    }

    /**
     * Wake only if [epoch] still owns the current automatic screen-off. Manual sleep/wake commands,
     * physical wake reconciliation, and any later automatic epoch make an older caller harmless.
     */
    @Synchronized
    fun wakeAutomaticallyIfOwned(
        epoch: AutomaticOffEpoch,
        admissionStillValid: () -> Boolean = { true },
    ): WakeOutcome {
        if (!intendedOff) return WakeOutcome.ALREADY_ON
        if (
            epoch.generation != automaticOffGeneration ||
            epoch.generation != intendedOffGeneration ||
            stateGeneration.get() != epoch.generation
        ) return WakeOutcome.STALE_GENERATION
        if (!admissionStillValid()) return WakeOutcome.STALE_GENERATION
        return runCatching {
            if (wake()) WakeOutcome.WOKEN else WakeOutcome.ACTUATION_FAILED
        }.getOrElse {
            Log.e(TAG, "automatic generation-safe wake failed", it)
            WakeOutcome.ACTUATION_FAILED
        }
    }

    /** Publish renderer wake only after the physical wake path and wakelock pulse have completed. */
    private fun completeWake(message: String): Boolean {
        power.pulseWake()
        if (!awaitInteractive(true, LOCK_CONFIRM_MS) || observedLit() == false || observedDark() == true) {
            Log.w(TAG, "screen wake was not confirmed")
            return false
        }
        stateGeneration.incrementAndGet()
        automaticOffGeneration = 0L
        intendedOffGeneration = 0L
        observedDarkGeneration = 0L
        intendedOff = false
        brightnessZeroOff = false
        appliedOffRoute = null
        wakeTap.disarm()
        rendererAsleep = false
        BuiltinDashboard.onScreenAwake(true)
        onWakeCompleted?.invoke()
        Log.d(TAG, message)
        return true
    }

    /** Release the wake-overlay owner without ever leaving an intentionally dark panel behind. */
    @Synchronized
    fun close() {
        closeAdmission()
        if (intendedOff) wake() else wakeTap.disarm()
    }

    /** Close future screen-off and brightness admission without performing any hardware I/O. */
    fun closeAdmission() {
        synchronized(visibleHolds) {
            admissionClosed.set(true)
            visibleHolds.clear()
        }
        onWakeByTap = null
        onWakeCompleted = null
    }

    /**
     * Restore the screen and establish the safest available process-exit boundary. A failed privileged
     * proof actively retries the wake actuators on every call, even after an earlier fallback cleared
     * off intent. If both helper and root have permanently disappeared, physical backlight-power proof
     * is impossible: after an active wake attempt, Android interactivity plus positive brightness is an
     * explicit fail-open boundary so recovery can replace the wedged process. That degraded result is
     * not claimed to prove that a privileged `bl_power=4` epoch was physically relit.
     */
    @Synchronized
    fun restoreAndEstablishExitSafety(): Boolean {
        closeAdmission()
        if (intendedOff || observedLit() != true) wake() else wakeTap.disarm()
        val privilegedProof = observedLit()
        if (privilegedProof != null) return privilegedProof
        // Deliberately prefer a fresh control plane over a permanent recovery fence when privileged
        // readback has vanished. This can remain physically dark after an earlier bl_power=4 write.
        return power.isInteractive() && backlight.getBrightness() > 0
    }

    // Write FB_BLANK to the first backlight device's bl_power (0=on, 4=off). Fails (exit!=0, so the
    // caller falls through) if there's no backlight node — never silently "succeeds" doing nothing.
    private fun blPower(on: Boolean): String {
        val v = if (on) 0 else 4
        return "d=\$(ls -d /sys/class/backlight/*/ 2>/dev/null|head -1);" +
            "[ -n \"\$d\" ]&&echo $v >\${d}bl_power"
    }

    // Fork-free: shell builtins only. Used once per process by [inheritedDark].
    private fun blPowerRead(): String =
        "for d in /sys/class/backlight/*/;do read v <\${d}bl_power&&echo \$v;break;done"

    companion object {
        private const val TAG = "ha-paneld/screen"
        internal const val REASON_DECLARED = "matches the declared route"
        internal const val REASON_HELPER_BEFORE_SU =
            "the helper daemon answered; su bl_power is used only when no helper is reachable"
        internal const val REASON_BRIGHTNESS_ZERO_NEVER_PROBES =
            "matches the declared route; brightness-zero never probes su or the helper daemon"
        internal const val REASON_CANCELLED_BY_TOUCH =
            "the off was cancelled by a touch during the screen-timeout wait; the panel stays awake"
        private const val REFUSAL_TOUCHED = "touched"
        internal const val REASON_VENDOR_UNREAD =
            "$REASON_DECLARED; the backlight node cannot be read, so the vendor's answer is taken as the off"
        internal const val REASON_VENDOR_UNRESOLVED =
            "the vendor's off could not be confirmed or undone, so the touch wake is kept"
        internal const val STAY_ON_SETTING = "stay_on_while_plugged_in"
        /** The screen-timeout refusal when stay-on is set and cannot be cleared; carried into the
         *  selection reason so diagnostics name the setting that defeated the timeout. */
        internal const val REFUSAL_STAY_ON =
            "$STAY_ON_SETTING keeps Android awake and WRITE_SECURE_SETTINGS is not granted to clear it"
        internal const val REASON_UNEXERCISED =
            "not yet exercised; no screen-off has occurred since this controller was constructed"
        private const val DEFAULT_ON = 160
        private const val MIN_ON = 10
        // Dim level for a screen-off that can't be made touch-wakeable: low but clearly on, never blank.
        private const val NO_WAKE_DIM = 10
        private const val KEYCODE_SLEEP = 223
        private const val KEYCODE_WAKEUP = 224
        // Android takes a moment to leave the interactive state, so the read-back is a short bounded
        // poll rather than one immediate sample. It runs inside the transition monitor, which the
        // privileged su/daemon calls around it already hold for a comparable time.
        /** Root injection is synchronous, so this only has to cover Android acting on the key. */
        private const val ROOT_CONFIRM_MS = 1_000L
        /**
         * Matches the helper's own `KEYEVENT` deadline. The helper kills a wedged `input` at 4 s, and
         * until then the injection may still land — long after this client abandoned its 500 ms read.
         * Confirming for less than that would call a slow sleep a failed one and dim a panel that is
         * about to go noninteractive anyway.
         */
        private const val DAEMON_CONFIRM_MS = 4_000L
        private const val KEYEVENT_POLL_MS = 100L
        /** Written as the short timeout; Android raises it to its own minimum. */
        private const val SLEEP_TIMEOUT_MS = 1_000
        /** Android's minimum timeout (10 s on AOSP) plus its dim phase and slack. */
        private const val TIMEOUT_CONFIRM_MS = 20_000L
        /** `lockNow()` turns the display off at once; this only covers Android acting on it. */
        private const val LOCK_CONFIRM_MS = 2_000L
        /** Stargate blanks or relights the node within a few hundred ms of answering. */
        private const val VENDOR_CONFIRM_MS = 2_000L
        /** The 2.8.2 overwrite landed within 2 s of the wake; hold a margin beyond it. */
        private const val VENDOR_SETTLE_MS = 3_000L
        private const val VENDOR_SETTLE_POLL_MS = 250L
    }
}
