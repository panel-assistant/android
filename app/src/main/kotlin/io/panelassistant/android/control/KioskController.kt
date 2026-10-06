package io.panelassistant.android.control

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.panelassistant.android.Config
import io.panelassistant.android.KioskAdminUi
import io.panelassistant.android.shouldKioskReturnToDashboard
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.platform.RootShell
import io.panelassistant.android.util.DurableRecoveryMarker
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * **Experimental kiosk lock.** Stops a NON-admin from ACCIDENTALLY navigating away from the dashboard on
 * an open/generic panel (no vendor lockdown). It is deliberately NOT an anti-adversary / jailbreak guard —
 * the design goal is "casual users can't wander off" with **no way to brick the panel and no way for an
 * admin to get locked out.**
 *
 * No device-owner policy is used, so nothing can brick the panel. Two enforcement layers are used:
 *  - **Aggressive return loop (the reliable, universal one)** — while locked, poll the dashboard's
 *    foreground state ([SystemController.dashboardState]) and, the moment it's backgrounded (Recents / any
 *    other app), re-launch it ([SystemController.launchHome]). A casual user physically CAN'T stay away —
 *    they're pulled back within ~[RETURN_POLL_MS]. This is what actually enforces the lock, because on some
 *    OEM SystemUIs the nav-button disable below is ignored.
 *  - `policy_control immersive.full=*` — HIDE both bars for a clean full-screen look. STICKY immersive, so a
 *    deliberate swipe still briefly flashes them; on such a panel the return loop is what stops an escape.
 *
 * Escapes, layered so no single failure strands an admin: the `:8888` toggle (any LAN browser), the HA
 * switch, adb, a **reboot** (clears the runtime lock; the service re-asserts only after a delay, so every
 * power-cycle gives an unlocked window), and an **on-device unlock gesture** — [UNLOCK_TAPS] rapid taps in
 * the top-left corner, network-independent, casual-proof — which fires [onUnlockRequested].
 */
class KioskController(
    context: Context,
    private val system: SystemController,
    private val config: Config,
    private val persistentPolicyEligible: Boolean,
    private val root: RootShell = Su,
) {
    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var unlockView: View? = null
    private var taps = 0
    private var lastTapAt = 0L
    @Volatile private var locked = false
    @Volatile private var returnThread: Thread? = null
    private val admissionClosed = AtomicBoolean(false)
    private val returnGeneration = AtomicLong()
    private val applyLock = Any()
    private val policyRecovery = KioskPolicyRecovery(
        root = root,
        statusBarMarker = DurableRecoveryMarker(ctx.noBackupFilesDir.resolve(STATUS_BAR_RECOVERY_FILE)),
        immersiveMarker = DurableRecoveryMarker(ctx.noBackupFilesDir.resolve(IMMERSIVE_RECOVERY_FILE)),
        legacyMigrationComplete = DurableRecoveryMarker(
            ctx.noBackupFilesDir.resolve(LEGACY_MIGRATION_COMPLETE_FILE),
        ),
        persistentPolicyEligible = persistentPolicyEligible,
    )

    /** Fired when the on-device unlock gesture completes. The service wires this to persist OFF + publish
     *  to HA + call [apply]`(false)` — all off the main thread. */
    @Volatile var onUnlockRequested: (() -> Unit)? = null

    /** Apply or clear the lock. Runs the root commands on the CALLING thread (blocking) — call it off the
     *  main thread (the MQTT thread, a boot coroutine, or the service's unlock thread); the overlay work is
     *  posted to the main thread internally. */
    fun apply(on: Boolean): Boolean = synchronized(applyLock) {
        if (on && admissionClosed.get()) return false
        if (on) {
            // A sandbox-walled profile has no route for these persistent shell mutations. Refuse the
            // setting before it can publish recovery ownership that this installation cannot clear.
            if (!canEnablePersistentPolicy()) return false
            if (!acquireKioskPolicy(policyRecovery::enable, policyRecovery::disable)) return false
            // apply() is explicitly off-main; grant before posting view-only overlay work so a slow or
            // wedged root shell can never block the Android UI looper.
            ensureOverlayPerm()
            showUnlockCorner()
            locked = true
            if (admissionClosed.get()) {
                locked = false
                hideUnlockCorner()
                return false
            }
            startReturnLoop()
            Log.i(TAG, "kiosk lock ON")
            true
        } else {
            // Revoke the poll generation before any blocking root cleanup. A dashboard-state call
            // already in flight must not observe a later ON and revive itself or launch stale work.
            locked = false
            stopReturnLoop()
            val persistentStateCleared = policyRecovery.disable()
            hideUnlockCorner()
            Log.i(TAG, "kiosk lock OFF")
            persistentStateCleared
        }
    }

    /**
     * Retry platform cleanup left by a prior process before this runtime can reassert kiosk. The first
     * marker-aware build also performs one conservative migration when persisted config says an older
     * build may have owned kiosk state before durable ownership markers existed.
     */
    fun recoverPersistentState(legacyMayBeActive: Boolean): Boolean = synchronized(applyLock) {
        policyRecovery.recover(legacyMayBeActive)
    }

    /** Profile eligibility avoids pointless probes on sandboxed hardware; availability proves live su. */
    fun canEnablePersistentPolicy(): Boolean = persistentPolicyEligible && root.available()

    fun isPersistentPolicyEligible(): Boolean = persistentPolicyEligible

    /** Close future ON admission immediately; durable OFF cleanup remains available to teardown. */
    fun closeAdmission() {
        admissionClosed.set(true)
        locked = false
        returnGeneration.incrementAndGet()
        synchronized(this) { returnThread?.interrupt() }
    }

    /** While locked, snap the dashboard back to the foreground the instant the user lands elsewhere
     *  (Recents / another app). This is the mechanism that actually enforces the lock on OEMs that ignore
     *  the nav-button disable. Reuses the watchdog's foreground probe; a no-op when it reads UNKNOWN
     *  (no root/daemon). Leaves ha-paneld's own admin UI alone so the unlock paths aren't fought. */
    private fun startReturnLoop(): Unit = synchronized(this) {
        if (!locked || returnThread?.isAlive == true) return
        val generation = returnGeneration.incrementAndGet()
        lateinit var worker: Thread
        worker = Thread {
            try {
                while (returnGeneration.isCurrent(generation, locked)) {
                    val cost = FeatureCosts.registry.beginSynchronous(FeatureCostOperation.KIOSK_STATE_POLL)
                    var outcome = FeatureCostOutcome.SUCCESS
                    try {
                        val pkg = config.dashboardPackage
                        val state = system.dashboardState(pkg)
                        if (!returnGeneration.isCurrent(generation, locked)) {
                            outcome = FeatureCostOutcome.CANCELLED
                            break
                        }
                        if (shouldKioskReturnToDashboard(state, KioskAdminUi.isVisible())) {
                            Log.i(TAG, "left the dashboard while locked -> returning to it")
                            system.launchHome(pkg)
                        }
                    } catch (e: Exception) {
                        outcome = FeatureCostOutcome.FAILURE
                    } finally {
                        FeatureCosts.registry.finishSynchronous(
                            FeatureCostOperation.KIOSK_STATE_POLL,
                            cost,
                            outcome = outcome,
                            workUnits = 1,
                        )
                    }
                    if (!returnGeneration.isCurrent(generation, locked)) break
                    try { Thread.sleep(RETURN_POLL_MS) } catch (e: InterruptedException) { break }
                }
            } finally {
                synchronized(this@KioskController) {
                    if (returnThread === worker) {
                        returnThread = null
                        // If OFF→ON happened while an old blocking probe was unwinding, restore one
                        // current poller only after the obsolete thread has fully terminated.
                        if (locked) startReturnLoop()
                    }
                }
            }
        }.apply { isDaemon = true; name = "kiosk-return" }
        returnThread = worker
        worker.start()
    }

    private fun stopReturnLoop() {
        val old = synchronized(this) {
            returnGeneration.incrementAndGet()
            returnThread?.also(Thread::interrupt)
        }
        if (old != null && old !== Thread.currentThread()) {
            runCatching { old.join(RETURN_STOP_JOIN_MS) }
        }
    }

    /** Ensure SYSTEM_ALERT_WINDOW (root-granted; already held for the navbar/touch-sound, granted here
     *  defensively in case this is the first overlay on the panel). */
    private fun ensureOverlayPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            root.run("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow")
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun showUnlockCorner() = main.post {
        if (unlockView != null) return@post
        val v = View(ctx)
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) countTap()
            true // consume: this small corner is a dedicated unlock zone
        }
        val size = (UNLOCK_DP * ctx.resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(v, lp); unlockView = v }
            .onFailure { Log.w(TAG, "unlock overlay addView failed: ${it.message}") }
    }

    private fun hideUnlockCorner() = main.post {
        unlockView?.let { runCatching { wm.removeView(it) } }
        unlockView = null
        taps = 0
    }

    /** Count a tap in the unlock corner; [UNLOCK_TAPS] within [TAP_GAP_MS] of each other fire the unlock. */
    private fun countTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapAt > TAP_GAP_MS) taps = 0
        lastTapAt = now
        if (++taps >= UNLOCK_TAPS) {
            taps = 0
            Log.i(TAG, "kiosk unlock gesture")
            onUnlockRequested?.invoke()
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    companion object {
        private const val TAG = "ha-paneld/kiosk"
        private const val UNLOCK_TAPS = 7    // rapid taps in the corner to release (casual-proof)
        private const val TAP_GAP_MS = 1_500L // max gap between taps before the count resets
        private const val UNLOCK_DP = 48     // corner unlock-zone size (a small dead zone while locked)
        internal const val RETURN_POLL_MS = 3_000L // experimental return loop; bounds steady privileged probes
        internal const val RETURN_STOP_JOIN_MS = 6_000L
        private const val STATUS_BAR_RECOVERY_FILE = "kiosk-statusbar-recovery.pending"
        private const val IMMERSIVE_RECOVERY_FILE = "kiosk-immersive-recovery.pending"
        private const val LEGACY_MIGRATION_COMPLETE_FILE = "kiosk-marker-migration.complete"
    }
}

/** Durable ownership for the two platform mutations that survive an app process death. */
internal class KioskPolicyRecovery(
    private val root: RootShell,
    private val statusBarMarker: DurableRecoveryMarker,
    private val immersiveMarker: DurableRecoveryMarker,
    private val legacyMigrationComplete: DurableRecoveryMarker,
    private val persistentPolicyEligible: Boolean = true,
) {
    fun enable(): Boolean {
        if (!persistentPolicyEligible || !root.available()) return false
        val immersive = armThenApply(immersiveMarker, IMMERSIVE_ON)
        return immersive
    }

    fun disable(): Boolean {
        val statusBar = statusBarMarker.recoverIfArmed {
            // Marker-aware builds no longer assert this inconsistent OEM layer. A successful use of the
            // platform's shared shell token is the only portable legacy cleanup signal available.
            root.run(STATUS_BAR_OFF)
        }
        val immersive = immersiveMarker.recoverIfArmed {
            root.run(IMMERSIVE_OFF) && immersiveIsDisabled(root.runOutput(IMMERSIVE_READBACK))
        }
        return statusBar && immersive
    }

    fun recover(legacyMayBeActive: Boolean): Boolean {
        if (!legacyMigrationComplete.isArmed()) {
            val hasRecoveryEvidence = hasRecoveryEvidence()
            if (!hasRecoveryEvidence && legacyMayBeActive) {
                // Profile declarations are not proof of current root. A raw persisted setting may be
                // retried later, but it cannot publish platform ownership until a live preflight passes.
                if (!persistentPolicyEligible) return legacyMigrationComplete.arm()
                if (!root.available()) return true
                // Older builds could leave these two mutations active without markers. Publish both
                // recovery obligations before touching either platform setting so a crash remains safe.
                val statusArmed = statusBarMarker.arm()
                val immersiveArmed = immersiveMarker.arm()
                if (!statusArmed || !immersiveArmed) return false
            }
            if (!disable()) return false
            if (!legacyMigrationComplete.arm()) return false
        }
        return disable()
    }

    fun hasRecoveryEvidence(): Boolean = statusBarMarker.isArmed() || immersiveMarker.isArmed()

    private fun armThenApply(marker: DurableRecoveryMarker, command: String): Boolean {
        if (!marker.arm()) return false
        if (root.run(command)) return true
        // Command failure is not proof that the platform made no partial mutation. Retain the marker so
        // OFF is retried before process exit and again at the next startup.
        return false
    }

    companion object {
        internal const val STATUS_BAR_OFF = "cmd statusbar disable-for-setup false"
        internal const val IMMERSIVE_ON = "settings put global policy_control 'immersive.full=*'"
        internal const val IMMERSIVE_OFF = "settings delete global policy_control"
        internal const val IMMERSIVE_READBACK = "settings get global policy_control"

        internal fun immersiveIsDisabled(output: String?): Boolean =
            output?.trim()?.let { it.isEmpty() || it.equals("null", ignoreCase = true) } == true

    }
}

internal fun AtomicLong.isCurrent(generation: Long, enabled: Boolean): Boolean =
    enabled && get() == generation

/** A failed command may still have partially changed global policy. Attempt OFF immediately while
 * retaining any unverified recovery marker, but never acknowledge the acquisition to local enforcement. */
internal fun acquireKioskPolicy(
    enable: () -> Boolean,
    cleanup: () -> Boolean,
): Boolean {
    if (enable()) return true
    cleanup()
    return false
}
