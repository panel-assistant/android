package io.github.maxlyth.hapaneld.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.maxlyth.hapaneld.R
import io.github.maxlyth.hapaneld.nativeString
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.util.LatestDispatcher
import io.github.maxlyth.hapaneld.util.submit
import io.github.maxlyth.hapaneld.util.DurableRecoveryMarker
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Soft on-screen navigation bar drawn as a system overlay (`TYPE_APPLICATION_OVERLAY`). For panels
 * whose firmware suppresses the native Android navigation bar — NSPanel Pro hardcodes
 * `config_showNavigationBar=false`, and both `policy_control` and `qemu.hw.mainkeys` are ignored —
 * so the user otherwise has no Back / Home / Recents. (Replaces the equivalent NSPanelToolsPro
 * feature for users who have uninstalled it.) Four modes:
 *
 * - `Off` — no overlay.
 * - `Always on` — persistent bar pinned to the bottom edge.
 * - `Swipe reveal` — bar hidden; a thin bottom-edge touch strip reveals it for [AUTO_HIDE_MS], then
 *   it auto-hides.
 * - `Native` — the firmware's own navigation bar has authority: this controller draws nothing,
 *   reveals nothing and takes no bottom-edge touches, so the platform's Back / Home / Recents are
 *   untouched. Physically identical to `Off`; the distinction is that `Off` means "no navigation
 *   affordance was wanted" while `Native` means "the system already provides one". Offered only
 *   where the device profile declares `has_native_navbar`, because choosing it on a panel with no
 *   system bar would leave no navigation at all.
 *
 * Buttons (left→right): Back, Launcher, Dashboard, Recents, Reload, Brightness−/+, Volume−/+. The panel has no physical
 * buttons, so the bar doubles as a hardware-button surface — brightness and volume step locally with
 * no round-trip to HA. Back/Recents use the accessibility service's global actions (no root, but
 * `PanelAccessibilityService` must be enabled); Launcher opens the device launcher / app drawer via
 * [SystemController]; brightness/volume go through their controllers — all of those work without a11y.
 * Drawing the overlay needs `SYSTEM_ALERT_WINDOW`, which the panels don't grant by default — we grant
 * it via root appops when available. Without the permission and without root the overlay silently
 * no-ops, like the other root-gated capabilities.
 *
 * All [WindowManager] mutations are posted to the main looper — `addView`/`removeView` must run on the
 * UI thread, but [apply] is called from the MQTT thread.
 */
class NavbarController(
    private val context: Context,
    private val system: SystemController,
    private val volume: VolumeController,
    private val brightness: BrightnessController,
    private val launcherPkg: () -> String,
    // Dashboard selection shared by the foreground-only Dashboard action and the force-stop+relaunch
    // Reload action (blank => the automatic built-in renderer; see [SystemController.resolveDashboard]).
    private val dashboardPkg: () -> String,
    // Back/Recents and tap route preference; live failure falls through (see NavActions).
    private val appCanSu: Boolean,
    // Omit the Recents button on panels whose firmware has no overview screen (e.g. Tuya TPA10).
    private val hasRecents: Boolean,
    // Notify HA after a LOCAL navbar change so light.<panel>_screen / number.<panel>_volume don't go
    // stale (the bar steps brightness/volume directly, bypassing the MQTT command path).
    private val onBrightnessChanged: (Int) -> Unit = {},
    private val onBrightnessApplied: () -> Unit = {},
    private val onVolumeChanged: () -> Unit = {},
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val lifecycleLock = Any()
    private var generation = 0L
    private var navGeneration = 0L
    private var closed = false
    private var cleanupSubmitted = false
    private val navActionGate = ReentrantReadWriteLock()
    private val overscanApplyLock = Any()
    private val overscanRecovery = NavbarOverscanRecovery(
        marker = DurableRecoveryMarker(context.noBackupFilesDir.resolve(OVERSCAN_RECOVERY_FILE)),
        run = Su::run,
        runOutput = Su::runOutput,
    )

    private var bar: View? = null      // the visible button row
    private var strip: View? = null    // the swipe-reveal edge trigger (Swipe-reveal mode only)
    private var brightLabel: TextView? = null  // live brightness % (wide panels only)
    private var volLabel: TextView? = null     // live volume % (wide panels only)
    private var volReceiver: BroadcastReceiver? = null  // refreshes volLabel on external volume changes
    private val hideRunnable = Runnable { animateBarOut() }
    private val repeaters = mutableSetOf<Runnable>()

    // Narrow-panel pop-up slider (brightness/volume): a small separate overlay above the bar. Only one open
    // at a time; [sliderKind] tracks which control owns it so re-tapping the same icon toggles it shut.
    private var sliderView: View? = null
    private var sliderKind: Slider? = null
    private val sliderHide = Runnable { dismissSlider() }

    private enum class Slider { BRIGHTNESS, VOLUME }

    private data class ModeWork(
        val generation: Long,
        val mode: String,
        val cleanup: Boolean = false,
        val completion: CompletableFuture<NavbarModeApplyOutcome>? = null,
    )
    private data class NavWork(val generation: Long, val action: () -> Unit, val finish: () -> Unit)

    private val modeDispatcher = LatestDispatcher.singleSlot<ModeWork>(
        threadName = "navbar-mode",
        consume = ::consumeModeWork,
        onDiscard = { work ->
            work.completion?.complete(
                NavbarModeApplyOutcome(
                    work.mode,
                    if (isClosed()) NavbarModeApplyStatus.CLOSED else NavbarModeApplyStatus.SUPERSEDED,
                ),
            )
        },
    )
    private val navDispatcher = LatestDispatcher.singleSlot<NavWork>(
        threadName = "navbar-action",
        consume = ::consumeNavWork,
        onDiscard = { it.finish() },
    )

    @Volatile
    var mode: String = MODE_OFF
        private set

    /** Last complete physical mode. [mode] is the latest desired value and may remain pending after a
     * failed actuation; callbacks that mutate overlays must follow this applied value instead. */
    @Volatile private var appliedMode: String = MODE_OFF

    init {
        // While the built-in renderer is foreground it detects the reveal swipe in-activity, so the
        // touch-consuming overlay strip must not sit over it (see [BuiltinDashboard]); re-arm when a
        // foreign surface returns to the foreground. Single-slot: a service restart's new controller
        // overwrites this cleanly even if the old one's cleanup() didn't run.
        BuiltinDashboard.setForegroundListener { fg -> onBuiltinForeground(fg) }
    }

    /**
     * Idempotent: enqueue a rebuild of the overlay state for [newMode]. Calls from MQTT, startup, or the
     * settings UI conflate into one latest-value slot; blocking permission and overscan work runs on the
     * controller's single mode worker, while every [WindowManager] mutation remains on the main thread.
     */
    fun apply(newMode: String) {
        val m = normalise(newMode)
        submitMode(m)
    }

    /** Return an acknowledgement that completes only after both privileged display state and the
     * main-thread overlay mutation have completed. */
    internal fun applyAsync(newMode: String): CompletableFuture<NavbarModeApplyOutcome> {
        val m = normalise(newMode)
        val completion = CompletableFuture<NavbarModeApplyOutcome>()
        submitMode(m, completion)
        return completion
    }

    /** Restore [previousMode] only while [expectedMode] is still the latest desired request. This makes
     * persistence/timeout compensation unable to overwrite a newer authoritative navbar transition. */
    internal fun rollbackAsync(
        expectedMode: String,
        previousMode: String,
    ): CompletableFuture<NavbarModeApplyOutcome> {
        val expected = normalise(expectedMode)
        val previous = normalise(previousMode)
        val completion = CompletableFuture<NavbarModeApplyOutcome>()
        val admission = synchronized(lifecycleLock) {
            when {
                closed -> {
                    completion.complete(NavbarModeApplyOutcome(previous, NavbarModeApplyStatus.CLOSED))
                    LatestDispatcher.Admission.CLOSED
                }
                mode != expected -> {
                    completion.complete(NavbarModeApplyOutcome(previous, NavbarModeApplyStatus.SUPERSEDED))
                    LatestDispatcher.Admission.COALESCED
                }
                else -> {
                    generation += 1
                    mode = previous
                    modeDispatcher.submit(ModeWork(generation, previous, completion = completion))
                }
            }
        }
        recordAdmission(FeatureCostOperation.NAVBAR_MODE_APPLY, admission, modeDispatcher.pendingCount())
        return completion
    }

    private fun submitMode(
        mode: String,
        completion: CompletableFuture<NavbarModeApplyOutcome>? = null,
    ) {
        val admission = synchronized(lifecycleLock) {
            if (closed) {
                completion?.complete(NavbarModeApplyOutcome(mode, NavbarModeApplyStatus.CLOSED))
                return@synchronized LatestDispatcher.Admission.CLOSED
            }
            generation += 1
            this.mode = mode
            modeDispatcher.submit(ModeWork(generation, mode, completion = completion))
        }
        recordAdmission(FeatureCostOperation.NAVBAR_MODE_APPLY, admission, modeDispatcher.pendingCount())
    }

    private fun consumeModeWork(work: ModeWork) {
        FeatureCosts.registry.setBacklog(FeatureCostOperation.NAVBAR_MODE_APPLY, 0)
        val cost = FeatureCosts.registry.span(FeatureCostOperation.NAVBAR_MODE_APPLY).work(units = 1)
        try {
            if (work.cleanup) {
                try {
                    performCleanup()
                    work.completion?.complete(NavbarModeApplyOutcome(work.mode, NavbarModeApplyStatus.APPLIED))
                    cost.close()
                } finally {
                    // Process-exit recovery waits for this worker to terminate before independently
                    // retrying overscan. Always close it even when platform cleanup throws.
                    modeDispatcher.close()
                }
            } else if (!isCurrent(work)) {
                cost.outcome(FeatureCostOutcome.CANCELLED)
                work.completion?.complete(NavbarModeApplyOutcome(work.mode, NavbarModeApplyStatus.SUPERSEDED))
                cost.close()
            } else {
                // Keep the mode worker occupied until the main-thread mutation acknowledges. Otherwise
                // a conflated successor can start its platform transition while this one is still pending.
                val succeeded = performApply(work)
                val status = when {
                    succeeded -> NavbarModeApplyStatus.APPLIED
                    !isCurrent(work) -> NavbarModeApplyStatus.SUPERSEDED
                    else -> NavbarModeApplyStatus.FAILED
                }
                if (status != NavbarModeApplyStatus.APPLIED) cost.outcome(
                    if (status == NavbarModeApplyStatus.SUPERSEDED) FeatureCostOutcome.CANCELLED
                    else FeatureCostOutcome.FAILURE,
                )
                work.completion?.complete(NavbarModeApplyOutcome(work.mode, status))
                cost.close()
            }
        } catch (e: Exception) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            Log.w(TAG, "navbar mode apply failed", e)
            work.completion?.complete(NavbarModeApplyOutcome(work.mode, NavbarModeApplyStatus.FAILED))
            cost.close()
        }
    }

    private fun performApply(work: ModeWork): Boolean {
        val target = work.mode
        val previous = appliedMode
        // Permission failure must be side-effect free: in particular, never create a persistent crop that
        // cannot be accompanied by the bar which explains and controls it.
        // Only the modes that draw a bar need SYSTEM_ALERT_WINDOW. Off and Native both draw nothing, so
        // neither may trigger the root appops grant — Native in particular runs on panels whose firmware
        // already provides navigation, where asking for an overlay permission would be gratuitous.
        val permissionReady = target !in OVERLAY_MODES || ensureOverlayPermission()
        if (!permissionReady || !isCurrent(work)) return false

        val transaction = transactNavbarMode(
            previousMode = previous,
            targetMode = target,
            permissionReady = true,
            applyOverscanFor = ::applyOverscanForMode,
            applyOverlayFor = ::applyOverlayMode,
        )
        if (!transaction.applied) {
            if (!transaction.rollbackSucceeded) {
                Log.e(TAG, "navbar transition failed and prior physical mode could not be restored")
            }
            return false
        }
        if (!isCurrent(work)) {
            val rolledBack = restorePhysicalMode(previous)
            if (!rolledBack) Log.e(TAG, "superseded navbar transition could not restore prior mode")
            return false
        }

        return try {
            updateModeBindings(target)
            val committed = synchronized(lifecycleLock) {
                if (!isCurrentLocked(work)) return@synchronized false
                appliedMode = target
                true
            }
            if (!committed) {
                val rolledBack = restorePhysicalMode(previous)
                runCatching { updateModeBindings(previous) }
                if (!rolledBack) Log.e(TAG, "superseded navbar binding update could not restore prior mode")
            }
            committed
        } catch (failure: Exception) {
            Log.w(TAG, "navbar binding update failed; restoring prior mode", failure)
            val rolledBack = restorePhysicalMode(previous)
            runCatching { updateModeBindings(previous) }
            if (!rolledBack) Log.e(TAG, "navbar binding failure could not restore prior physical mode")
            false
        }
    }

    private fun restorePhysicalMode(mode: String): Boolean =
        applyOverscanForMode(mode) &&
            applyOverlayMode(mode)

    private fun applyOverscanForMode(target: String): Boolean {
        if (target == MODE_ALWAYS) return !appCanSu || applyOwnedOverscan(BAR_HEIGHT_DP)
        // Avoid making ordinary Off/Swipe startup depend on transient su readiness when this process has
        // never applied a crop and no crash-recovery marker says an older process left one behind.
        if (!overscanRecovery.isArmed()) return true
        return applyOwnedOverscan(0)
    }

    private fun applyOverlayMode(target: String): Boolean = postOnMain {
        main.removeCallbacks(hideRunnable)
        removeBar(); removeStrip()
        when (target) {
            MODE_ALWAYS -> addBar(autoHide = false)
            MODE_SWIPE -> BuiltinDashboard.foreground || addStrip()
            else -> true
        }
    }

    private fun updateModeBindings(target: String) {
        // The receiver exists to keep the drawn bar's volume readout in step with external changes, so it
        // is tied to the modes that draw one rather than to "not Off".
        setVolumeReceiver(target in OVERLAY_MODES)
        synchronized(lifecycleLock) {
            BuiltinDashboard.setNavbarRevealHandler(
                if (target == MODE_SWIPE && canDraw()) {
                    {
                        main.post {
                            if (!isClosed() && appliedMode == MODE_SWIPE) reveal()
                        }
                    }
                } else null,
            )
        }
    }

    private fun setVolumeReceiver(enabled: Boolean) {
        synchronized(lifecycleLock) {
            if (!enabled) {
                unregisterVolumeReceiverLocked()
                return
            }
            // closeAdmission unregistered it synchronously from onDestroy; a mode apply still in flight
            // must not register it again, or it outlives the service's teardown.
            if (volReceiver != null || closed) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    main.post { if (!isClosed()) updateVolLabel() }
                }
            }
            // "android.media.VOLUME_CHANGED_ACTION" is an @hide constant — use the string literal.
            // RECEIVER_EXPORTED is required from API 33 for receiving system broadcasts.
            @Suppress("UnspecifiedRegisterReceiverFlag")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, IntentFilter(VOLUME_CHANGED_ACTION), Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(VOLUME_CHANGED_ACTION))
            }
            volReceiver = receiver
        }
    }

    private fun unregisterVolumeReceiverLocked() {
        volReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        volReceiver = null
    }

    private fun postOnMain(action: () -> Boolean): Boolean {
        val completion = CompletableFuture<Boolean>()
        val valid = java.util.concurrent.atomic.AtomicBoolean(true)
        val admitted = main.post {
            if (valid.compareAndSet(true, false)) {
                completion.complete(runCatching(action).getOrDefault(false))
            }
        }
        if (!admitted) completion.complete(false)
        return try {
            completion.get(MAIN_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (failure: Exception) {
            // If the runnable has not started, invalidate it so a late main-looper recovery cannot apply
            // the stale target after this worker has compensated/advanced to a newer generation. If it is
            // already executing, the queued rollback follows it on the same looper and restores ordering.
            valid.set(false)
            Log.w(TAG, "navbar main-thread acknowledgement timed out", failure)
            false
        }
    }

    private fun isCurrent(work: ModeWork): Boolean =
        synchronized(lifecycleLock) { isCurrentLocked(work) }

    private fun isCurrentLocked(work: ModeWork): Boolean =
        !closed && generation == work.generation && mode == work.mode

    private fun isClosed(): Boolean = synchronized(lifecycleLock) { closed }

    private fun performCleanup() {
        synchronized(lifecycleLock) {
            BuiltinDashboard.setNavbarRevealHandler(null)
            BuiltinDashboard.setForegroundListener(null)
            unregisterVolumeReceiverLocked()
        }
        main.post { tearDownViews() }
        if (appCanSu || overscanRecovery.isArmed()) resetOverscanForCleanup()
    }

    private fun consumeNavWork(work: NavWork) {
        FeatureCosts.registry.setBacklog(FeatureCostOperation.NAVBAR_ACTION, 0)
        val cost = FeatureCosts.registry.span(FeatureCostOperation.NAVBAR_ACTION).work(units = 1)
        val gate = navActionGate.readLock()
        gate.lock()
        try {
            val current = synchronized(lifecycleLock) {
                !closed && navGeneration == work.generation
            }
            if (!current) {
                cost.outcome(FeatureCostOutcome.CANCELLED)
            } else {
                runCatching { work.action() }
                    .onFailure { cost.outcome(FeatureCostOutcome.FAILURE) }
            }
        } finally {
            gate.unlock()
            work.finish()
            cost.close()
        }
    }

    private fun submitNavWork(action: () -> Unit, finish: () -> Unit) {
        val work = synchronized(lifecycleLock) {
            if (closed) null else NavWork(navGeneration, action, finish)
        }
        if (work == null) {
            finish()
            recordAdmission(FeatureCostOperation.NAVBAR_ACTION, LatestDispatcher.Admission.CLOSED, 0)
            return
        }
        val admission = navDispatcher.submit(work)
        recordAdmission(FeatureCostOperation.NAVBAR_ACTION, admission, navDispatcher.pendingCount())
    }

    private fun recordAdmission(
        operation: FeatureCostOperation,
        admission: LatestDispatcher.Admission,
        backlog: Int,
    ) {
        when (admission) {
            LatestDispatcher.Admission.ACCEPTED -> Unit
            LatestDispatcher.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(operation)
            LatestDispatcher.Admission.REJECTED, // unreachable for a single slot; dropped if it ever occurred
            LatestDispatcher.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(operation)
        }
        FeatureCosts.registry.setBacklog(operation, backlog)
    }

    /** The built-in renderer took ([fg]=true) or left the foreground. In Swipe-reveal mode, suppress the
     *  touch-consuming strip while it's foreground (it detects the reveal itself) and re-arm otherwise.
     *  No-op in Off / Always-on. addStrip/removeStrip are idempotent + canDraw()-gated. */
    private fun onBuiltinForeground(fg: Boolean) {
        main.post {
            if (isClosed() || appliedMode != MODE_SWIPE) return@post
            if (fg) removeStrip() else addStrip()
        }
    }

    // --- overlay construction (main thread only) ---

    private fun addBar(autoHide: Boolean): Boolean {
        if (bar != null) return true
        if (!canDraw()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not held (no root to grant it); navbar suppressed")
            return false
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BAR_BG)
            gravity = Gravity.CENTER_VERTICAL
        }
        // Wide panels (e.g. landscape TPA10) keep the ±-pairs with the live % readout; narrow panels (a
        // portrait NSPanel 120P) have no room for that, so they collapse each control to a single icon that
        // opens a vertical pop-up slider — which also leaves room for both Dashboard and Reload.
        if (widthDp() >= VALUE_WIDTH_THRESHOLD_DP) buildWideRow(row, autoHide)
        else buildNarrowRow(row, autoHide)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(BAR_HEIGHT_DP),
            overlayType(),
            // Not focusable (don't steal key focus from the dashboard) but still touchable.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        try {
            // Slide up from the bottom edge: start translated fully below the window frame, animate to 0.
            row.translationY = dp(BAR_HEIGHT_DP).toFloat()
            wm.addView(row, lp)
            row.animate().translationY(0f).setDuration(ANIM_MS).start()
            bar = row
            if (autoHide) scheduleHide()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "addView(bar) failed", e)
            return false
        }
    }

    /** Wide layout: nav group (Back · Launcher · Dashboard · [Recents] · Reload) + the ±-pairs with the live % readout.
     *  Each ±/value triple's members are weight [TIGHTEN] (35% tighter), with the reclaimed weight as
     *  spacers either side so the group keeps its default slot and nav spacing is untouched. */
    private fun buildWideRow(row: LinearLayout, autoHide: Boolean) {
        val side = 3 * (1f - TIGHTEN) / 2f
        // Match every group boundary to the triple→separator gap (bar ends + nav→separator), accounting for
        // nav cells (½ = 0.5) being wider than triple cells (½ = TIGHTEN/2).
        val edge = (side + TIGHTEN / 2f - 0.5f).coerceAtLeast(0f)
        row.addView(spacer(edge))
        addNavGroup(row, autoHide)
        row.addView(spacer(edge))
        row.addView(separator())
        row.addView(spacer(side))
        row.addView(repeatButton(R.drawable.ic_nav_bright_down, R.string.nav_brightness_down, autoHide) { stepBrightness(-BRIGHT_STEP); updateBrightLabel() })
        valueLabel().also { brightLabel = it; row.addView(it) }
        row.addView(repeatButton(R.drawable.ic_nav_bright_up, R.string.nav_brightness_up, autoHide) { stepBrightness(+BRIGHT_STEP); updateBrightLabel() })
        row.addView(spacer(side))
        row.addView(separator())
        row.addView(spacer(side))
        row.addView(repeatButton(R.drawable.ic_nav_vol_down, R.string.nav_volume_down, autoHide) { volume.step(up = false); updateVolLabel(); onVolumeChanged() })
        valueLabel().also { volLabel = it; row.addView(it) }
        row.addView(repeatButton(R.drawable.ic_nav_vol_up, R.string.nav_volume_up, autoHide) { volume.step(up = true); updateVolLabel(); onVolumeChanged() })
        row.addView(spacer(side))
        updateBrightLabel(); updateVolLabel()
    }

    /** Narrow layout (portrait NSPanel 120P): everything is a single weight-1.0 icon, evenly spread —
     *  Back · Launcher · Dashboard · [Recents] · Reload | Brightness | Volume. Brightness/Volume each open a vertical
     *  pop-up slider above the icon (there's no room for ±-pairs or a % label). */
    private fun buildNarrowRow(row: LinearLayout, autoHide: Boolean) {
        addNavGroup(row, autoHide)
        row.addView(separator())
        row.addView(sliderButton(R.drawable.ic_nav_bright_up, R.string.nav_brightness, Slider.BRIGHTNESS, autoHide))
        row.addView(separator())
        row.addView(sliderButton(R.drawable.ic_nav_vol_up, R.string.nav_volume, Slider.VOLUME, autoHide))
    }

    /** Back · Launcher · Dashboard · [Recents] · Reload — the navigation cluster shared by both layouts. Back/Recents/
     *  Launcher run a slow su / activity call, so navButton offloads it and holds the press highlight until
     *  it completes. Dashboard foregrounds the resolved renderer without reloading; Reload remains the
     *  explicit force-stop/relaunch recovery action. */
    private fun addNavGroup(row: LinearLayout, autoHide: Boolean) {
        row.addView(navButton(R.drawable.ic_nav_back, R.string.nav_back, autoHide) { NavActions.back(appCanSu) })
        row.addView(navButton(R.drawable.ic_nav_launcher, R.string.nav_launcher, autoHide) { system.launchLauncher(launcherPkg()) })
        row.addView(navButton(R.drawable.ic_nav_dashboard, R.string.nav_dashboard, autoHide) { system.launchHome(dashboardPkg()) })
        if (hasRecents) row.addView(navButton(R.drawable.ic_nav_recents, R.string.nav_recents, autoHide) { NavActions.recents(appCanSu) })
        row.addView(navButton(R.drawable.ic_nav_reload, R.string.nav_reload, autoHide) { system.reloadDashboard(dashboardPkg()) })
    }

    /** Narrow-panel control button: a single weight-1.0 icon that toggles a vertical pop-up slider for
     *  [kind] above it. Tap to open (re-tap to close); the slider drives brightness/volume live. */
    private fun sliderButton(icon: Int, description: Int, kind: Slider, autoHide: Boolean): View {
        val cell = iconCell(icon, description, 1f)
        cell.isClickable = true
        cell.setOnClickListener {
            if (autoHide) main.removeCallbacks(hideRunnable)   // keep the bar up while adjusting
            if (sliderKind == kind) dismissSlider() else showSlider(kind, cell)
        }
        return cell
    }

    /** Show a vertical drag-slider for [kind] in its own overlay, centred above [anchor]. Outside-tap or a
     *  short idle timeout closes it. Only one is ever open (re-shows replace the previous). */
    private fun showSlider(kind: Slider, anchor: View) {
        dismissSlider()
        if (!canDraw()) return
        val level0 = when (kind) {
            Slider.BRIGHTNESS -> brightness.getCommanded().coerceIn(0, 255) / 255f
            Slider.VOLUME -> volume.getPercent() / 100f
        }
        val slider = VerticalSlider(level0) { lv ->
            when (kind) {
                Slider.BRIGHTNESS -> {
                    val level = (lv * 255).toInt().coerceIn(10, 255)
                    onBrightnessChanged(level)
                    brightness.setBrightness(level)
                    onBrightnessApplied()
                }
                Slider.VOLUME -> { volume.setPercent((lv * 100).toInt()); onVolumeChanged() }
            }
            resetSliderTimeout()
        }
        val pad = dp(SLIDER_PAD_DP)
        val container = FrameLayout(context).apply {
            setPadding(pad, pad, pad, pad)
            background = roundedBg()
            addView(slider, FrameLayout.LayoutParams(dp(SLIDER_TRACK_W_DP) + 2 * pad, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            // Outside touch (FLAG_WATCH_OUTSIDE_TOUCH) closes; inside touches fall through to the slider.
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { dismissSlider(); true } else false }
        }
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val w = dp(SLIDER_TRACK_W_DP) + 4 * pad
        val lp = WindowManager.LayoutParams(
            w, dp(SLIDER_HEIGHT_DP), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = (loc[0] + anchor.width / 2 - w / 2).coerceAtLeast(0)
            y = dp(BAR_HEIGHT_DP) + dp(SLIDER_GAP_DP)
        }
        try {
            wm.addView(container, lp); sliderView = container; sliderKind = kind; resetSliderTimeout()
        } catch (e: Exception) {
            Log.w(TAG, "addView(slider) failed", e)
        }
    }

    private fun dismissSlider() {
        main.removeCallbacks(sliderHide)
        sliderView?.let { runCatching { wm.removeView(it) } }
        sliderView = null; sliderKind = null
    }

    private fun resetSliderTimeout() {
        main.removeCallbacks(sliderHide)
        main.postDelayed(sliderHide, SLIDER_IDLE_MS)
    }

    /** Vertical fill-slider: a rounded track filled from the bottom to the current 0..1 [level]; drag
     *  anywhere on it to set the level, which calls [onLevel] live. */
    private inner class VerticalSlider(level0: Float, val onLevel: (Float) -> Unit) : View(context) {
        private var level = level0.coerceIn(0f, 1f)
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SLIDER_TRACK_COLOR }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = w / 2f
            c.drawRoundRect(0f, 0f, w, h, r, r, trackPaint)
            val top = (h * (1f - level)).coerceIn(0f, h)
            c.drawRoundRect(0f, top, w, h, r, r, fillPaint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    level = (1f - e.y / height).coerceIn(0f, 1f)
                    onLevel(level); invalidate()
                }
                MotionEvent.ACTION_UP -> resetSliderTimeout()
            }
            return true
        }
    }

    /** Rounded charcoal background for the slider pop-up (same charcoal as the bar). */
    private fun roundedBg(): Drawable = android.graphics.drawable.GradientDrawable().apply {
        setColor(BAR_BG); cornerRadius = dp(SLIDER_RADIUS_DP).toFloat()
    }

    /** Slide the bar down off the bottom edge, then remove it (swipe-reveal auto-hide). */
    private fun animateBarOut() {
        val b = bar ?: return
        b.animate().translationY(dp(BAR_HEIGHT_DP).toFloat()).setDuration(ANIM_MS)
            // Detach via detachBar (NOT removeBar) — calling animate().cancel() from inside the animator's
            // own end action re-applies the transform and flashes the bar back at its resting position for
            // a frame. detachBar hides-then-removes without cancelling, killing that flash.
            .withEndAction { detachBar(b) }.start()
    }

    /** Detach the bar view without touching its animator. Make it INVISIBLE before `removeView` so the
     *  window manager's final layout pass on removal can't draw it at its resting position for a frame. */
    private fun detachBar(b: View) {
        dismissSlider()   // a pop-up slider must never outlive its bar
        runCatching { b.visibility = View.INVISIBLE; wm.removeView(b) }
        if (bar === b) {
            bar = null
            brightLabel = null
            volLabel = null
        }
    }

    private fun addStrip(): Boolean {
        if (strip != null) return true
        if (!canDraw()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not held (no root to grant it); navbar suppressed")
            return false
        }
        val edge = View(context)
        // lp is declared before the touch listener so the listener can capture it for the
        // FLAG_NOT_TOUCHABLE trick used to re-inject taps (see ACTION_UP handling below).
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(STRIP_HEIGHT_DP),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        // Consume the whole gesture (return true for all events) so the dashboard WebView doesn't
        // receive partial swipes — returning false on MOVE previously let an off-screen-origin swipe-up
        // scroll the dashboard while also revealing the bar. Touches above the strip still pass through
        // normally (they're outside this window's bounds).
        // Reveal is gated on a genuine upward swipe (≥ SWIPE_MIN_DP travel) so taps don't accidentally
        // pop the bar up. Taps consumed by the strip are re-injected through root or accessibility: the
        // strip is briefly flagged FLAG_NOT_TOUCHABLE so the tap reaches the app behind it rather than
        // looping back to the strip itself.
        var downX = 0f; var downY = 0f; var swiped = false
        val tapToken = AtomicLong()
        edge.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; swiped = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!swiped && downY - e.rawY >= dp(SWIPE_MIN_DP)) { swiped = true; reveal() }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!swiped) {
                        val x = downX.toInt(); val y = downY.toInt()
                        val token = tapToken.incrementAndGet()
                        // Pause strip touchability so the re-injected tap routes to the window behind
                        // instead of back to the strip. Restored after the routed tap completes.
                        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        runCatching { wm.updateViewLayout(edge, lp) }
                        submitNavWork(
                            action = { NavActions.tap(appCanSu, x.toFloat(), y.toFloat()) },
                            finish = {
                                if (!isClosed() && tapToken.get() == token) {
                                    main.post {
                                        if (!isClosed() && tapToken.get() == token && strip === edge && mode == MODE_SWIPE) {
                                            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                                            runCatching { wm.updateViewLayout(edge, lp) }
                                        }
                                    }
                                }
                            },
                        )
                    }
                    true
                }
                else -> true
            }
        }
        try {
            wm.addView(edge, lp)
            strip = edge
            return true
        } catch (e: Exception) {
            Log.w(TAG, "addView(strip) failed", e)
            return false
        }
    }

    /** Swipe-reveal: show the bar over the strip, schedule auto-hide. If a slide-out is in flight,
     *  cancel it and snap back to fully shown. */
    private fun reveal() {
        val b = bar
        if (b == null) {
            addBar(autoHide = true)
        } else {
            b.animate().cancel()
            b.translationY = 0f
            scheduleHide()
        }
    }

    private fun scheduleHide() {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, AUTO_HIDE_MS)
    }

    /** Tear down the bar immediately (mode change). Cancels any in-flight slide first — safe here since
     *  it's an external call, not the animator's own end action — then detaches. */
    private fun removeBar() {
        bar?.let { b -> runCatching { b.animate().cancel() }; detachBar(b) }
    }

    /** Live "NN%" readout placed between a ±-pair on wide panels. */
    private fun valueLabel(): TextView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        minWidth = dp(VALUE_LABEL_MIN_W_DP)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, TIGHTEN)
    }

    private fun updateBrightLabel() {
        brightLabel?.text = "${(brightness.getCommanded().coerceAtLeast(0) * 100 + 127) / 255}%"
    }

    private fun updateVolLabel() {
        volLabel?.text = "${volume.getPercent()}%"
    }

    /** Current display width in dp (rotation-aware) — gates the value readout. */
    private fun widthDp(): Int = context.resources.displayMetrics.let {
        (it.widthPixels / it.density).toInt()
    }

    private fun removeStrip() {
        strip?.let { runCatching { wm.removeView(it) } }
        strip = null
    }

    /** A weighted cell holding a FIXED-size, centred icon. The cell [weight] controls spacing (nav = 1.0
     *  baseline; triple members = [TIGHTEN] → narrower cells, tighter spacing), but the icon itself is a
     *  constant [ICON_SIZE_DP] regardless of cell width — so triple icons render the same size as nav
     *  icons even when their cells are narrow (otherwise FIT_CENTER shrinks them on narrow panels). */
    private fun iconCell(icon: Int, description: Int, weight: Float): FrameLayout {
        val iv = ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP), Gravity.CENTER)
        }
        return FrameLayout(context).apply {
            contentDescription = context.nativeString(description)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            // Instant press highlight on touch-down — the action itself (esp. Back/Recents via su) can
            // lag ~200-300ms, so without this a tap reads as "nothing happened". Shows on touch-down for
            // clickable nav buttons automatically; repeatButton sets isPressed for the ±-buttons.
            foreground = pressHighlight()
            addView(iv)
        }
    }

    /** Transparent normally, a translucent flash while pressed. No theme needed (overlay isn't themed). */
    private fun pressHighlight(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(PRESS_TINT))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    /** Weighted flexible gap — the reclaimed width either side of a tightened triple keeps the group
     *  centred in its slot so nav spacing stays at the default. */
    private fun spacer(weight: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
    }

    /** Single-tap nav button — weight 1.0, spread evenly across the bar (unchanged baseline). The press
     *  highlight is held from touch-down until [action] (a slow su / activity call, run off the UI
     *  thread) completes, with a [FEEDBACK_MIN_MS] floor, so the tap stays acknowledged during the lag. */
    private fun navButton(icon: Int, description: Int, autoHide: Boolean, action: () -> Unit): View {
        val iv = iconCell(icon, description, 1f)
        val pressToken = AtomicLong()
        iv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    iv.isPressed = true
                    if (autoHide) scheduleHide()
                    val token = pressToken.incrementAndGet()
                    val t0 = SystemClock.uptimeMillis()
                    submitNavWork(
                        action = action,
                        finish = finish@{
                            if (isClosed() || pressToken.get() != token) return@finish
                            val hold = (FEEDBACK_MIN_MS - (SystemClock.uptimeMillis() - t0)).coerceAtLeast(0L)
                            main.postDelayed({
                                if (!isClosed() && pressToken.get() == token && iv.isAttachedToWindow) {
                                    iv.isPressed = false
                                }
                            }, hold)
                        },
                    )
                    true
                }
                else -> true // consume UP/MOVE/CANCEL — the action's completion clears the highlight
            }
        }
        return iv
    }

    /** Press-and-hold triple button (weight [TIGHTEN]): [step] fires on touch-down, then repeats while
     *  held (volume / brightness ramping). Pauses auto-hide for the duration of the press. */
    private fun repeatButton(icon: Int, description: Int, autoHide: Boolean, step: () -> Unit): View {
        val iv = iconCell(icon, description, TIGHTEN)
        val repeater = object : Runnable {
            override fun run() {
                if (isClosed() || !iv.isPressed || !iv.isAttachedToWindow) {
                    repeaters.remove(this)
                    return
                }
                step()
                main.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        iv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    iv.isPressed = true   // show the press highlight (we consume the event below)
                    if (autoHide) main.removeCallbacks(hideRunnable) // don't hide mid-hold
                    step()
                    repeaters.add(repeater)
                    main.postDelayed(repeater, REPEAT_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    iv.isPressed = false
                    main.removeCallbacks(repeater)
                    repeaters.remove(repeater)
                    if (autoHide) scheduleHide()
                    true
                }
                else -> false
            }
        }
        return iv
    }

    /** Vertical divider between button groups (shorter than the bar height, centred). The wide
     *  side margins also push the groups apart so the ±-pairs cluster visibly tighter. */
    private fun separator(): View = View(context).apply {
        setBackgroundColor(SEP_COLOR)
        layoutParams = LinearLayout.LayoutParams(dp(SEP_WIDTH_DP), dp(SEP_HEIGHT_DP)).apply {
            gravity = Gravity.CENTER_VERTICAL
            val m = dp(SEP_MARGIN_DP); leftMargin = m; rightMargin = m
        }
    }

    /** Step screen brightness by [delta] (of 0–255), floored at 10 so the screen never goes black. */
    private fun stepBrightness(delta: Int) {
        val cur = brightness.getCommanded().let { if (it < 0) 128 else it }
        val level = (cur + delta).coerceIn(10, 255)
        onBrightnessChanged(level)
        brightness.setBrightness(level)
        onBrightnessApplied()
    }

    // --- permission ---

    private fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    /** Grant `SYSTEM_ALERT_WINDOW` via root appops if it isn't already held (panels have no UI flow). */
    private fun ensureOverlayPermission(): Boolean {
        if (canDraw()) return true
        Su.run("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        return canDraw()
    }

    private fun applyOwnedOverscan(heightDp: Int): Boolean = synchronized(overscanApplyLock) {
        overscanRecovery.applyBottom(dp(heightDp))
    }

    private fun resetOverscanForCleanup(): Boolean = synchronized(overscanApplyLock) {
        val recovered = overscanRecovery.resetAndVerify()
        if (!recovered) Log.w(TAG, "navbar overscan reset remains pending for the next process")
        return recovered
    }

    /** Close all future platform/overlay mutation admission without performing blocking root work. */
    fun closeAdmission() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            generation += 1
            navGeneration += 1
            mode = MODE_OFF
            BuiltinDashboard.setNavbarRevealHandler(null)
            BuiltinDashboard.setForegroundListener(null)
            unregisterVolumeReceiverLocked()
            navDispatcher.close()
        }
    }

    /** Reset any display overscan set by this controller. Call from the service's onDestroy so the
     *  reserved bottom margin doesn't persist after ha-paneld stops. */
    fun cleanup() {
        closeAdmission()
        val admission = synchronized(lifecycleLock) {
            if (cleanupSubmitted) return
            cleanupSubmitted = true
            modeDispatcher.submit(ModeWork(generation, MODE_OFF, cleanup = true))
        }
        recordAdmission(FeatureCostOperation.NAVBAR_MODE_APPLY, admission, modeDispatcher.pendingCount())
        main.post { tearDownViews() }
        val deadline = SystemClock.uptimeMillis() + CLEANUP_DRAIN_MS
        val actionGate = navActionGate.writeLock()
        val actionDrained = runCatching {
            actionGate.tryLock(remainingCleanupMs(deadline), TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (actionDrained) actionGate.unlock()
        else Log.w(TAG, "navbar action did not drain within ${CLEANUP_DRAIN_MS}ms")
        val navDrained = navDispatcher.awaitTermination(remainingCleanupMs(deadline))
        if (!navDrained) Log.w(TAG, "navbar action worker did not terminate before cleanup deadline")
        val modeDrained = modeDispatcher.awaitTermination(remainingCleanupMs(deadline))
        if (!modeDrained) Log.w(TAG, "navbar mode cleanup did not finish before cleanup deadline")
    }

    /**
     * Process-exit proof for persistent overscan. Never races a late mode mutation: cleanup first closes
     * admission, and this remains false until its sole platform worker has really terminated.
     */
    fun recoverPersistentState(): Boolean {
        if (!appCanSu && !overscanRecovery.isArmed()) return true
        if (!synchronized(lifecycleLock) { closed && cleanupSubmitted }) return false
        if (!modeDispatcher.awaitTermination(0L)) return false
        return resetOverscanForCleanup()
    }

    private fun remainingCleanupMs(deadline: Long): Long =
        (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L)

    private fun tearDownViews() {
        main.removeCallbacks(hideRunnable)
        main.removeCallbacks(sliderHide)
        repeaters.forEach(main::removeCallbacks)
        repeaters.clear()
        dismissSlider()
        removeBar()
        removeStrip()
    }

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun normalise(m: String): String =
        MODES.firstOrNull { it.equals(m.trim().trim('"'), ignoreCase = true) } ?: MODE_OFF

    companion object {
        private const val TAG = "ha-paneld/navbar"
        // AudioManager.VOLUME_CHANGED_ACTION is @hide — use the string literal directly.
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

        const val MODE_OFF = "Off"
        const val MODE_ALWAYS = "Always on"
        const val MODE_SWIPE = "Swipe reveal"
        const val MODE_NATIVE = "Native"
        val MODES = listOf(MODE_OFF, MODE_ALWAYS, MODE_SWIPE, MODE_NATIVE)

        /** Modes whose bar this app draws, and which therefore need `SYSTEM_ALERT_WINDOW`. `Off` and
         *  `Native` both draw nothing; only these two justify an overlay-permission warning. */
        val OVERLAY_MODES = setOf(MODE_ALWAYS, MODE_SWIPE)

        private const val BAR_HEIGHT_DP = 56   // taller so the icons read at a glance
        // Swipe-reveal capture zone + threshold. Canonical values live in [BottomSwipeDetector] so the
        // overlay strip (this) and the built-in renderer's in-activity detector can't drift. (12→28→48:
        // a fast off-screen swipe-up's DOWN sometimes landed above a thinner strip, so the dashboard
        // caught it and scrolled.)
        private const val STRIP_HEIGHT_DP = BottomSwipeDetector.BAND_DP
        private const val SWIPE_MIN_DP = BottomSwipeDetector.MIN_TRAVEL_DP
        private const val FEEDBACK_MIN_MS = 350L  // min press-highlight visible time (bridges su latency)
        private const val CLEANUP_DRAIN_MS = 4_000L
        private const val MAIN_ACK_TIMEOUT_MS = 2_000L
        private const val OVERSCAN_RECOVERY_FILE = "navbar-overscan-reset-pending"
        private const val ICON_SIZE_DP = 30    // fixed icon size, centred in its cell (uniform across all buttons)
        private const val TIGHTEN = 0.65f      // triple-member cell weight vs nav's 1.0 → ~35% tighter spacing
        private val BAR_BG = 0xC2282C34.toInt() // charcoal @ ~76% — translucent but still reads solid
        private val PRESS_TINT = 0x55FFFFFF.toInt() // press-feedback flash (~33% white)
        private const val AUTO_HIDE_MS = 5000L  // +25% over 4000 — 4s felt too brief in use
        private const val ANIM_MS = 220L       // swipe-reveal slide in/out duration
        private const val VALUE_WIDTH_THRESHOLD_DP = 600 // wide panels (TPA10) get the % readout; square NSPanels don't
        private const val VALUE_LABEL_MIN_W_DP = 42
        private const val BRIGHT_STEP = 32     // brightness (0–255) per tap ≈ 12.5%
        private const val REPEAT_DELAY_MS = 400L     // hold this long before ramping starts
        private const val REPEAT_INTERVAL_MS = 120L  // then step every this often while held
        private val SEP_COLOR = 0x99FFFFFF.toInt()   // 60% white group divider — clearly visible
        private const val SEP_WIDTH_DP = 2
        private const val SEP_HEIGHT_DP = 30
        private const val SEP_MARGIN_DP = 7          // gap each side of the group divider

        // Narrow-panel pop-up slider (brightness/volume).
        private const val SLIDER_HEIGHT_DP = 170     // total pop-up height above the bar
        private const val SLIDER_TRACK_W_DP = 14     // the draggable track width
        private const val SLIDER_PAD_DP = 10         // padding inside the rounded pop-up
        private const val SLIDER_GAP_DP = 6          // gap between the pop-up and the bar's top edge
        private const val SLIDER_RADIUS_DP = 14
        private const val SLIDER_IDLE_MS = 3000L     // auto-close after this long with no interaction
        private val SLIDER_TRACK_COLOR = 0x55FFFFFF.toInt() // unfilled track (~33% white)
    }
}

/** Marker-backed ownership and affirmative readback for persistent display overscan. */
/** Whether this platform implements the `wm overscan` subcommand the display crop depends on. */
internal enum class OverscanSupport {
    /** The subcommand exists; a crop can be applied and must therefore be recoverable. */
    SUPPORTED,

    /** The subcommand does not exist, so no crop can ever have been applied on this device. Modern
     *  AOSP removed it: an rk3576 Android 14 panel answers `Unknown command: overscan`. */
    UNIMPLEMENTED,

    /** The probe could not run — typically root is not available right now. Says nothing about the
     *  platform, so it must never be read as "no crop exists". */
    UNKNOWN,
}

internal class NavbarOverscanRecovery(
    private val marker: DurableRecoveryMarker,
    private val run: (String) -> Boolean,
    private val runOutput: (String) -> String?,
) {
    /** Cached once conclusive. A platform cannot gain or lose a shell subcommand without an OS change,
     *  which would restart this process, so a definite answer is permanent for its lifetime. */
    @Volatile private var cachedSupport: OverscanSupport? = null

    fun isArmed(): Boolean = marker.isArmed()

    /**
     * Probe whether the crop command exists.
     *
     * [runOutput] discards output whenever the shell exits non-zero, and `wm` exits non-zero for an
     * unknown subcommand, so a bare invocation cannot distinguish "this platform has no overscan" from
     * "root is unavailable". The probe therefore carries the inner command's own status out in its
     * payload rather than letting the shell's exit status stand for it. Setting the crop to zero is a
     * safe probe because both callers want no crop at that moment: the reset path is asking for exactly
     * this, and the apply path overwrites it with the real height immediately afterwards.
     *
     * Only a definite answer is cached. An unrecognised failure is [OverscanSupport.UNKNOWN] and must
     * stay re-probeable — caching it would be the same fail-open shape this class exists to remove.
     */
    internal fun support(): OverscanSupport {
        cachedSupport?.let { return it }
        val output = runOutput(SUPPORT_PROBE) ?: return OverscanSupport.UNKNOWN
        val resolved = classifySupport(output)
        if (resolved != OverscanSupport.UNKNOWN) cachedSupport = resolved
        return resolved
    }

    fun applyBottom(bottomPx: Int): Boolean {
        val bottom = bottomPx.coerceAtLeast(0)
        if (bottom == 0) return resetAndVerify()
        // Arming records that a crop may exist and must be undone. Doing that for a command the platform
        // cannot run is what wedged the controller: the marker could never clear, so every later mode
        // change — including the one back to Off — took the reset path and failed there forever. Where no
        // crop is possible, report success and let the bar simply overlay the bottom of the content; the
        // crop is cosmetic, and a drawn bar is far better than a navbar setting that can never change.
        if (support() != OverscanSupport.SUPPORTED) return true
        if (!marker.arm()) return false
        return run("wm overscan 0,0,0,$bottom")
    }

    fun resetAndVerify(): Boolean {
        if (!marker.isArmed()) return true
        // A marker left behind on a platform with no overscan command describes a crop that cannot exist.
        // Clear it instead of re-running a command that can never succeed. UNKNOWN deliberately falls
        // through to the real readback: an unavailable root shell is not evidence that nothing is cropped.
        if (support() == OverscanSupport.UNIMPLEMENTED) return marker.recoverIfArmed { true }
        return marker.recoverIfArmed {
            displayOverscanIsZero(runOutput(RESET_AND_READBACK))
        }
    }

    companion object {
        internal const val RESET_AND_READBACK =
            "wm overscan 0,0,0,0 && dumpsys display && dumpsys window policy"

        /** Emitted by [SUPPORT_PROBE] carrying the crop command's own exit status. The shell's status
         *  cannot serve: it has to stay zero for [runOutput] to hand back any payload at all. */
        internal const val RC_MARKER = "hapaneld-overscan-rc"

        /** Runs the exact command the crop uses, then reports that command's status inside the output. */
        internal const val SUPPORT_PROBE = "wm overscan 0,0,0,0 2>&1; echo " + RC_MARKER + "=\$?"

        /**
         * Classify one probe payload.
         *
         * `SUPPORTED` requires positive evidence — the crop command itself reported success. Everything
         * else is a failure, and only an explicit unknown-subcommand reply proves the platform lacks it.
         * A denied permission, a missing `wm` binary, a dead window service, a differently worded refusal
         * or a truncated payload are all indeterminate: they must not license arming the durable marker,
         * and they must not license clearing one either.
         */
        internal fun classifySupport(output: String): OverscanSupport {
            val status = RC_LINE.find(output)?.groupValues?.get(1)?.toIntOrNull()
                ?: return OverscanSupport.UNKNOWN
            if (status == 0) return OverscanSupport.SUPPORTED
            if (UNKNOWN_COMMAND.containsMatchIn(output)) return OverscanSupport.UNIMPLEMENTED
            return OverscanSupport.UNKNOWN
        }

        private val RC_LINE = Regex(Regex.escape(RC_MARKER) + "=(-?\\d+)")
        private val UNKNOWN_COMMAND = Regex("unknown command", RegexOption.IGNORE_CASE)

        internal fun displayOverscanIsZero(output: String?): Boolean {
            val displayInfo = output.orEmpty().lineSequence()
                .firstOrNull { line -> line.contains("DisplayInfo{") }
                ?: return false
            val real = REAL_SIZE.find(displayInfo) ?: return false
            val policy = OVERSCAN_SCREEN.find(output.orEmpty()) ?: return false
            val explicitOverscan = OVERSCAN.find(displayInfo)
            if (explicitOverscan != null && explicitOverscan.groupValues.drop(1).any { it.toIntOrNull() != 0 }) {
                return false
            }
            val realWidth = real.groupValues[1].toIntOrNull() ?: return false
            val realHeight = real.groupValues[2].toIntOrNull() ?: return false
            val left = policy.groupValues[1].toIntOrNull() ?: return false
            val top = policy.groupValues[2].toIntOrNull() ?: return false
            val width = policy.groupValues[3].toIntOrNull() ?: return false
            val height = policy.groupValues[4].toIntOrNull() ?: return false
            return left == 0 && top == 0 && width == realWidth && height == realHeight
        }

        private val OVERSCAN = Regex(
            "overscan\\s*\\((-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)\\)",
            RegexOption.IGNORE_CASE,
        )
        private val REAL_SIZE = Regex("\\breal\\s+(\\d+)\\s*x\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val OVERSCAN_SCREEN = Regex(
            "mOverscanScreen=\\((-?\\d+),(-?\\d+)\\)\\s+(\\d+)x(\\d+)",
            RegexOption.IGNORE_CASE,
        )
    }
}
