package io.github.maxlyth.hapaneld.control

import android.util.Log
import io.github.maxlyth.hapaneld.device.ScreenOff
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.NoWakeTap
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.platform.ScreenPower
import io.github.maxlyth.hapaneld.platform.WakeTap
import io.github.maxlyth.hapaneld.util.HelperClient
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.Collections
import java.util.IdentityHashMap

enum class WakeOutcome { WOKEN, ALREADY_ON, STALE_GENERATION, ACTUATION_FAILED }

/** Ownership proof for one screen-off transition created by an automatic policy. */
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
 * Screen on/off — vendor-free with one serialized transition owner.
 *
 * The active profile selects helper, direct `su`, keyevent, or brightness-zero as the preferred
 * route. The two bl_power routes may fall through to each other and then brightness when unavailable;
 * an explicit brightness-zero profile never probes a privileged actuator.
 *
 * This deliberately avoids `DevicePolicyManager.lockNow()`, which turns the screen off via the
 * keyguard and therefore demands the device PIN on wake. Its collaborators are seamed ([Backlight],
 * [ScreenPower], [RootShell], [Daemon], [WakeTap]) so the never-blank logic is unit-testable without a device.
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
 *   probed** (the same rule as `hasNativeNavbar`): where the touchscreen is a kernel wake source
 *   Android wakes itself and [reconcilePhysicalWake] adopts that wake; where it is not, Home Assistant
 *   is the way back. This is the only route on which a wake can reach the panel without passing
 *   through [wake], so it is the reason that adoption has to be reachable from a plain platform
 *   screen-on broadcast and not only from the MQTT sync tick — see [reconcilePhysicalWake]. The route
 *   also refuses to sleep a device with a configured credential
 *   ([ScreenPower.isDeviceSecure]), because waking into a lock screen on a wall panel is the same
 *   class of strand as rebooting one.
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
) {
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
    // Durable report of the last off, kept across wake unlike [appliedOffRoute] above (which exists
    // only while intentionally off). [routeSelection] reads these two directly rather than probing.
    @Volatile private var lastSelectedRoute: ScreenOff? = null
    @Volatile private var lastSelectionReason: String = REASON_UNEXERCISED
    private val stateGeneration = AtomicLong()
    @Volatile private var intendedOffGeneration = 0L
    @Volatile private var observedDarkGeneration = 0L
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
            ScreenOff.DAEMON_BLPOWER, ScreenOff.SU_BLPOWER -> observedBlPower()?.let(::fromPower) ?: effective()
            // Android's own interactivity IS this route's screen state; there is no backlight node to
            // read and no third "unknown" answer to give.
            ScreenOff.KEYEVENT -> !power.isInteractive()
            ScreenOff.BRIGHTNESS_ZERO -> effective()
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
            ScreenOff.KEYEVENT -> power.isInteractive()
            ScreenOff.BRIGHTNESS_ZERO -> effectiveLit()
        }
    }

    private fun observedBlPower(): Int? = when (route) {
        ScreenOff.DAEMON_BLPOWER -> daemon.send("BLPOWER")?.trim()?.toIntOrNull()
            ?.takeIf { it in 0..4 }
            ?: root.runOutput(blPowerRead())?.trim()?.toIntOrNull()?.takeIf { it in 0..4 }
        ScreenOff.SU_BLPOWER -> root.runOutput(blPowerRead())?.trim()?.toIntOrNull()
            ?.takeIf { it in 0..4 }
            ?: daemon.send("BLPOWER")?.trim()?.toIntOrNull()?.takeIf { it in 0..4 }
        ScreenOff.KEYEVENT, ScreenOff.BRIGHTNESS_ZERO -> null
    }

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
        wake()
        return true
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
        if (observedDark() != true) return null
        val epoch = sleepInternal(automatic)
        // A refused or degraded off would leave a dark backlight marked intended with no way back.
        if (appliedOffRoute != ScreenOff.DAEMON_BLPOWER && appliedOffRoute != ScreenOff.SU_BLPOWER) {
            if (intendedOff) wake()
            return null
        }
        Log.i(TAG, "adopted a dark screen left by a replaced process (automatic=$automatic)")
        return epoch
    }

    /** Record an explicit brightness so the fallback off/on restores to it. */
    fun noteLevel(level: Int) {
        if (level > 0) savedLevel = level.coerceIn(1, 255)
    }

    @Synchronized
    fun sleep() {
        sleepInternal(automatic = false)
    }

    /**
     * Turn the screen off on behalf of an automatic policy and return ownership of that exact epoch.
     * If the screen is already intentionally off, the existing (possibly manual) owner wins and no
     * epoch is returned. This prevents an automatic controller from adopting and later waking a manual
     * screen-off.
     */
    @Synchronized
    fun sleepAutomatically(): AutomaticOffEpoch? = sleepInternal(automatic = true)

    private fun sleepInternal(automatic: Boolean): AutomaticOffEpoch? {
        if (admissionClosed.get() || synchronized(visibleHolds) { visibleHolds.isNotEmpty() } || (automatic && intendedOff)) return null
        intendedOffGeneration = stateGeneration.incrementAndGet()
        automaticOffGeneration = if (automatic) intendedOffGeneration else 0L
        observedDarkGeneration = 0L
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
        if (refusal != null) return dimToFloor(refusal)
        // Guaranteed locally wakeable: arm the tap, then power the backlight off for real. Only the two
        // bl_power paths below take the panel *truly* dark — freeze the WebView there (no point rendering
        // behind a black backlight). The brightness fallback (0) is not guaranteed dark on panels that
        // clamp a minimum, so it does NOT freeze (correctness over the CPU saving on those rare panels).
        val poweredOff = when (route) {
            ScreenOff.DAEMON_BLPOWER -> when {
                daemon.send("SCREEN OFF") == "OK" -> ScreenOff.DAEMON_BLPOWER to REASON_DECLARED
                root.run(blPower(false)) -> ScreenOff.SU_BLPOWER to
                    "the declared helper daemon route was unavailable; fell back to su bl_power"
                else -> null
            }
            ScreenOff.SU_BLPOWER -> when {
                root.run(blPower(false)) -> ScreenOff.SU_BLPOWER to REASON_DECLARED
                daemon.send("SCREEN OFF") == "OK" -> ScreenOff.DAEMON_BLPOWER to
                    "the declared su bl_power route was unavailable; fell back to the helper daemon"
                else -> null
            }
            ScreenOff.KEYEVENT -> if (sleepByKeyevent()) ScreenOff.KEYEVENT to REASON_DECLARED else null
            ScreenOff.BRIGHTNESS_ZERO -> null
        }
        if (poweredOff != null) {
            val (poweredOffRoute, reason) = poweredOff
            appliedOffRoute = poweredOffRoute
            recordSelection(poweredOffRoute, reason)
            // A successful bl_power actuator is authoritative for this exact off epoch. This permits
            // a quick external wake to reconcile before the next heartbeat. Brightness-zero remains
            // unconfirmed until read back dark because some panels visibly clamp its raw zero.
            observedDarkGeneration = intendedOffGeneration
            BuiltinDashboard.onScreenAwake(false)
            Log.d(TAG, "screen -> off (${poweredOffRoute.name.lowercase()})")
            return automaticEpochOrNull()
        }
        // Last resort. A raw zero is admissible only because the overlay above was armed, so a tap
        // still re-lights the panel. The keyevent route has no overlay by design, and its way back is
        // the wakelock pulse, which nobody standing at the panel can trigger — so it must not reach a
        // dark it cannot let a person out of, and degrades to the visible floor instead.
        if (route == ScreenOff.KEYEVENT) return dimToFloor("an unconfirmed keyevent sleep")
        // No daemon, no su — dim to 0 (only a dim on panels that clamp a minimum). Uses the raw setter
        // so it can reach 0: the public setBrightness floors at MIN_VISIBLE to stay never-blank.
        val cur = backlight.getBrightness()
        if (cur > 0) savedLevel = cur
        backlight.setBrightnessRaw(0)
        appliedOffRoute = ScreenOff.BRIGHTNESS_ZERO
        recordSelection(
            ScreenOff.BRIGHTNESS_ZERO,
            if (route == ScreenOff.BRIGHTNESS_ZERO) {
                REASON_BRIGHTNESS_ZERO_NEVER_PROBES
            } else {
                "neither su bl_power nor the helper daemon was available; degraded to a brightness-only off"
            },
        )
        Log.d(TAG, "screen -> off (brightness fallback; saved=$savedLevel)")
        return automaticEpochOrNull()
    }

    /**
     * Degrade an off to a visible dim, the never-blank floor. [appliedOffRoute] records what was
     * actually applied rather than what was asked for, so [wake] restores the level this dimmed
     * instead of retrying a privileged actuator that was never used. The screen stays VISIBLE, so the
     * built-in renderer must not be frozen here: a frozen WebView on a still-lit dashboard would show
     * stale, un-tappable cards.
     */
    private fun dimToFloor(reason: String): AutomaticOffEpoch? {
        val cur = backlight.getBrightness()
        if (cur > 0) savedLevel = cur
        backlight.setBrightness(NO_WAKE_DIM)
        appliedOffRoute = ScreenOff.BRIGHTNESS_ZERO
        recordSelection(
            ScreenOff.BRIGHTNESS_ZERO,
            "the declared route was refused ($reason); dimmed to the never-blank floor instead",
        )
        Log.w(TAG, "screen-off with $reason — dimming to floor (never-blank; saved=$savedLevel)")
        return automaticEpochOrNull()
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
        if (!intendedOff && power.isInteractive() && observedLit() == true) return WakeOutcome.ALREADY_ON
        return runCatching {
            wake()
            WakeOutcome.WOKEN
        }.getOrElse {
            Log.e(TAG, "explicit wake failed", it)
            WakeOutcome.ACTUATION_FAILED
        }
    }

    @Synchronized
    fun wake() {
        stateGeneration.incrementAndGet()
        automaticOffGeneration = 0L
        intendedOff = false
        wakeTap.disarm()
        val wakingRoute = appliedOffRoute ?: route
        appliedOffRoute = null
        when (wakingRoute) {
            ScreenOff.DAEMON_BLPOWER -> {
                if (daemon.send("SCREEN ON") == "OK") {
                    completeWake("screen -> on (daemon bl_power)")
                    return
                }
                if (root.run(blPower(true))) {
                    completeWake("screen -> on (su bl_power fallback)")
                    return
                }
            }
            ScreenOff.SU_BLPOWER -> {
                if (root.run(blPower(true))) {
                    completeWake("screen -> on (su bl_power)")
                    return
                }
                if (daemon.send("SCREEN ON") == "OK") {
                    completeWake("screen -> on (daemon bl_power fallback)")
                    return
                }
            }
            ScreenOff.KEYEVENT -> {
                // The wakelock pulse inside completeWake is what actually guarantees this wake, and
                // it needs no privilege, so a failed injection is not worth reporting as a failure —
                // and the brightness fallback below must not run, because this route never changed
                // the brightness and restoring a remembered level would move it for no reason.
                val injected = injectKeyevent("WAKEUP")
                completeWake(
                    if (injected) "screen -> on (keyevent wakeup)"
                    else "screen -> on (wakelock pulse; keyevent wakeup unavailable)"
                )
                return
            }
            ScreenOff.BRIGHTNESS_ZERO -> Unit
        }
        backlight.setBrightness(savedLevel.coerceAtLeast(MIN_ON))
        completeWake("screen -> on (brightness fallback; $savedLevel)")
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

    /** Reconcile a physical/vendor wake that did not pass through [wake]. No actuator is touched: the
     * observed lit state is authoritative, but stale off intent and queued gesture generations must die. */
    @Synchronized
    fun noteObservedDark(expectedGeneration: Long?): Boolean {
        if (
            expectedGeneration == null || !intendedOff ||
            expectedGeneration != intendedOffGeneration || stateGeneration.get() != expectedGeneration
        ) return false
        observedDarkGeneration = expectedGeneration
        return true
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
        stateGeneration.incrementAndGet()
        intendedOffGeneration = 0L
        observedDarkGeneration = 0L
        automaticOffGeneration = 0L
        intendedOff = false
        appliedOffRoute = null
        wakeTap.disarm()
        BuiltinDashboard.onScreenAwake(true)
        onWakeCompleted?.invoke()
        return true
    }

    /**
     * Adopt a wake this controller did not perform, from whatever noticed it.
     *
     * [reconcileObservedLit] needs an epoch and a proof of lit, and until now the only place that
     * assembled both was the MQTT bridge's sync tick. That was sufficient while every route left
     * Android interactive, because then nothing outside ha-paneld could light the panel on its own —
     * a physical touch reached the armed overlay and came back through [wake]. [ScreenOff.KEYEVENT]
     * breaks that assumption: Android can wake itself from a power key or a wake-capable touchscreen
     * with ha-paneld uninvolved. Leaving the adoption inside MQTT would mean a panel that a person
     * woke by hand stays [intendedOff] with its renderer frozen until the broker is reachable — a lit
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
            wake()
            WakeOutcome.WOKEN
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
            wake()
            WakeOutcome.WOKEN
        }.getOrElse {
            Log.e(TAG, "automatic generation-safe wake failed", it)
            WakeOutcome.ACTUATION_FAILED
        }
    }

    /** Publish renderer wake only after the physical wake path and wakelock pulse have completed. */
    private fun completeWake(message: String) {
        power.pulseWake()
        BuiltinDashboard.onScreenAwake(true)
        onWakeCompleted?.invoke()
        Log.d(TAG, message)
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

    private fun blPowerRead(): String =
        "d=\$(ls -d /sys/class/backlight/*/ 2>/dev/null|head -1);cat \${d}bl_power 2>/dev/null"

    companion object {
        private const val TAG = "ha-paneld/screen"
        internal const val REASON_DECLARED = "matches the declared route"
        internal const val REASON_BRIGHTNESS_ZERO_NEVER_PROBES =
            "matches the declared route; brightness-zero never probes su or the helper daemon"
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
    }
}
