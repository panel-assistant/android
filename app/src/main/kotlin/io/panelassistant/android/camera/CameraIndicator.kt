package io.panelassistant.android.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.panelassistant.android.control.LedEffectController
import io.panelassistant.android.control.Su
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Decides whether the room is being told the camera is on. Pure so the rule is a unit test: the overlay
 * alone is positive only while the display is lit; once the screen is intended off the overlay sits at
 * the never-blank floor where it is illegible, so only a lit LED counts.
 */
object CameraIndicationPolicy {
    fun positive(overlayAttached: Boolean, screenOff: Boolean, ledLit: Boolean): Boolean =
        overlayAttached && (!screenOff || ledLit)

    fun route(overlayAttached: Boolean, ledLit: Boolean): CameraIndication = when {
        ledLit -> CameraIndication.LED
        overlayAttached -> CameraIndication.OVERLAY
        else -> CameraIndication.NONE
    }
}

/**
 * Where the camera light is drawn, kept pure so the shape is a unit test rather than something only a
 * screenshot can check.
 *
 * Both camera-bearing panels put the lens at the **top centre** of the bezel, so a light in a corner
 * points at nothing. The indicator is the visible bottom of a circle **centred on the lens itself**: on
 * screen it reads as an arc curving under the camera, flush to the bezel. A margin here would detach the
 * arc from the bezel and lose that, so there is none.
 *
 * Where the lens is, is a per-board measurement (`hardware.camera_lens_offset_px`), not something the
 * app can infer — the two camera panels differ by 20 px.
 */
object CameraIndicatorGeometry {
    /**
     * Height of the visible arc, in screen pixels — what the room actually sees below the screen edge.
     * This is the design choice; the circle's size follows from it and from where the lens is.
     */
    const val VISIBLE_BAND_PX = 53

    /**
     * Fallback for a profile that has not measured its lens. Deliberately the value the first version of
     * this indicator assumed implicitly, so an unmeasured panel is no worse off than before.
     */
    const val DEFAULT_LENS_OFFSET_PX = 27

    /**
     * Radius: far enough that the circle's centre lands on the lens while its bottom stays
     * [VISIBLE_BAND_PX] below the screen edge. A higher lens means a bigger, flatter arc.
     *
     * Note what this trades away. The first version fixed the diameter at three dashboard headers less a
     * margin, and *derived* the centre from a visible fraction — which silently asserted the lens was 27
     * px above the active area. It is not: measured from photographs of both panels, it is 63 px on the
     * TPA10 and 43 px on the WF1589T. Diameter, lens-centring and band height are three constraints on
     * two degrees of freedom, so one had to give; the fixed diameter is the one that was never observable
     * from the room, while the other two are exactly what a person looking at the panel judges.
     */
    fun radiusPx(lensOffsetPx: Int): Float = (lensOffsetPx + VISIBLE_BAND_PX).toFloat()

    /** Overlay window width — the circle's full width. */
    fun windowWidthPx(lensOffsetPx: Int): Int = Math.round(radiusPx(lensOffsetPx) * 2f)

    /** Overlay window height — the visible band, so the arc meets the screen edge exactly. */
    val windowHeightPx: Int get() = VISIBLE_BAND_PX

    /** Circle centre within the window, horizontally centred. */
    fun centreX(widthPx: Int): Float = widthPx / 2f

    /**
     * Circle centre's y within the window: negative by exactly the lens offset, which is what puts the
     * centre *on the lens* rather than somewhere that happens to look close.
     */
    fun centreY(lensOffsetPx: Int): Float = -lensOffsetPx.toFloat()

    /** Radius inset by half the stroke so the outline is not clipped by the window edge. */
    fun radius(lensOffsetPx: Int, strokePx: Float): Float = radiusPx(lensOffsetPx) - strokePx / 2f

    /** A profile's measurement, or the fallback when it has none. */
    fun lensOffsetOrDefault(profileValue: Int?): Int =
        profileValue?.takeIf { it > 0 } ?: DEFAULT_LENS_OFFSET_PX
}

/**
 * How the camera light moves, and how it backs off once a session keeps running.
 *
 * Two stepped levels, deliberately not an animator. Cost is the whole argument: this light can be up for
 * as long as a camera session lasts, so whatever it costs, it costs continuously. Measured on a live
 * panel a stepped pulse costs two layer updates a second, under 1.4% of one core, while a `ValueAnimator`
 * on alpha redraws at the display refresh rate whether or not the value visibly changed - about 41%.
 *
 * A session opens at [BRIGHT]/[DIM] once a second, which is unmissable and right for a camera nobody
 * expected. That is wrong for one the owner deliberately left running, and the two differ only in how
 * long the session has lasted, so that is the only thing this schedule reads. After [PROMINENT_MS] the
 * gap after each flash stretches toward [MAX_GAP_MS] and both levels fade: the gap to nothing, the flash
 * only as far as [LIT_FLOOR]. At rest the room gets a real half-second flash about once a minute and
 * nothing in between, which is also far cheaper than what it replaces.
 *
 * The flash's length and its floor are the two things that must never drift toward nothing, because once
 * the gap has faded out the flash *is* the indication.
 */
object CameraIndicatorPulse {
    const val PERIOD_MS = 1_000L

    /** The lit half of every cycle, at every age. Only the gap after it ever changes. */
    const val STEP_MS = PERIOD_MS / 2
    const val BRIGHT = 1.0f
    const val DIM = 0.42f

    /** Full prominence for this long, then a ramp this long to the settled state. */
    const val PROMINENT_MS = 30_000L
    const val RAMP_MS = 120_000L

    /** With [STEP_MS] this makes the settled cycle exactly one minute. */
    const val MAX_GAP_MS = 59_500L

    /** The flash dims to here and stops. A flash that faded out would not be an indication at all. */
    const val LIT_FLOOR = 0.62f

    fun alphaFor(bright: Boolean): Float = if (bright) BRIGHT else DIM

    /** 0 while prominent, 1 once settled. The clamp is also what fails prominent on a backwards clock. */
    fun progressAt(elapsedMs: Long): Float =
        ((elapsedMs - PROMINENT_MS).toFloat() / RAMP_MS).coerceIn(0f, 1f)

    /** The gap after the flash. Geometric, so it stays frequent while somebody may still be reacting. */
    fun gapMsAt(elapsedMs: Long): Long =
        Math.round(STEP_MS * Math.pow(MAX_GAP_MS.toDouble() / STEP_MS, progressAt(elapsedMs).toDouble()))

    /** Alpha for either half: the flash descends only to [LIT_FLOOR], the gap all the way to nothing. */
    fun alphaAt(elapsedMs: Long, lit: Boolean): Float {
        val from = alphaFor(lit)
        val floor = if (lit) LIT_FLOOR else 0f
        return from - progressAt(elapsedMs) * (from - floor)
    }
}

/**
 * The camera-in-use light the room can see. In code:
 *
 * - An always-on-top, non-touchable overlay centred under the camera, drawn with the same window type
 *   the navigation-bar and kiosk overlays use, so page content cannot cover it and a tap never hits it.
 *   Its shape and placement are [CameraIndicatorGeometry].
 * - [show] returns only once the window is confirmed attached on the main thread AND the indication is
 *   positive for the current screen state; false means the owner must not open the camera.
 * - [refresh] is the continuing prerequisite: the owner calls it on every watchdog tick and closes the
 *   session when it returns false. While the screen is intended off the display is dark or at the
 *   never-blank floor, so the indication moves to the status LED through a [LedEffectController.Hold];
 *   if that hold cannot be taken or lit, the indication is negative and capture stops.
 * - [hide] keeps the light on for a minimum hold so rapid snapshot polling reads as one continuous
 *   indication; a [show] inside the hold cancels it. Releasing the LED never restores from a snapshot:
 *   [restoreLed] re-derives it from persisted intent, and that work runs on a worker thread because the
 *   LED HAL blocks on every write.
 * - The pulse backs off over the session on [CameraIndicatorPulse]'s schedule. [startPulse] returns it
 *   to full prominence, and every discontinuity goes through it: each camera open, including a reopen
 *   inside the post-close hold or after a fault, and the display coming back after screen-off. Consumers
 *   attaching and leaving inside one session never reach this class, so churn cannot flash it bright.
 */
class CameraIndicator(
    private val context: Context,
    private val ledEffect: LedEffectController,
    private val restoreLed: () -> Unit,
    /** True while the screen is intended off, including the never-blank dim floor. */
    private val screenOff: () -> Boolean,
    /** The active profile's measured lens offset in screen px; null falls back to the default. */
    private val cameraLensOffsetPx: Int? = null,
    private val holdAfterCloseMs: Long = HOLD_AFTER_CLOSE_MS,
    /** Monotonic clock for the attenuation schedule; injected so the schedule is testable without waiting. */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val lensOffsetPx = CameraIndicatorGeometry.lensOffsetOrDefault(cameraLensOffsetPx)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var view: View? = null
    private var generation = 0L
    private var ledHold: LedEffectController.Hold? = null
    private var ledLit = false
    /** When the current prominence period started, on [clock]. Only [startPulse] moves it. */
    private var pulseStartedAtMs = 0L

    /** Which half of the cycle is showing. [startPulse] clears it so the first run lights the arc. */
    private var lit = false

    /**
     * One half-cycle, re-posting itself for however long its own half lasts: the lit half is always
     * [CameraIndicatorPulse.STEP_MS], the gap after it is whatever the session's age says. It stops by
     * returning without re-posting once the view is gone, so a missed cancellation cannot leave it
     * running against a detached window.
     *
     * It keeps running while the screen is intended off, which is deliberate: pausing it would need a
     * resume path on every route back, and by then it is issuing a couple of layer updates a minute.
     * [refresh] restarts it at full prominence when the display returns.
     */
    private val pulse = object : Runnable {
        override fun run() {
            val delay: Long
            synchronized(lock) {
                val current = view ?: return
                val elapsed = clock() - pulseStartedAtMs
                lit = !lit
                current.alpha = CameraIndicatorPulse.alphaAt(elapsed, lit)
                delay = if (lit) CameraIndicatorPulse.STEP_MS else CameraIndicatorPulse.gapMsAt(elapsed)
            }
            main.postDelayed(this, delay)
        }
    }

    /** Start the pulse, or return it to full prominence. Main thread only. */
    private fun startPulse() {
        main.removeCallbacks(pulse)
        pulseStartedAtMs = clock()
        lit = false
        main.post(pulse)
    }

    fun route(): CameraIndication = synchronized(lock) { CameraIndicationPolicy.route(view != null, ledLit) }

    /** Attach the overlay and confirm the indication is positive; false means the camera must not open. */
    fun show(): Boolean {
        synchronized(lock) { generation++ }
        if (!ensureOverlayPermission()) {
            Log.w(TAG, "overlay permission missing; refusing to open the camera")
            return false
        }
        var attached = false
        val ran = onMain {
            synchronized(lock) {
                if (view != null) {
                    // A reopen inside the post-close hold is still a new opening of the camera, so it
                    // presents as new rather than inheriting however far the last one had faded.
                    attached = true
                    startPulse()
                    return@onMain
                }
                val candidate = IndicatorView(context, lensOffsetPx)
                val params = WindowManager.LayoutParams(
                    CameraIndicatorGeometry.windowWidthPx(lensOffsetPx),
                    CameraIndicatorGeometry.windowHeightPx,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    // Under the lens, touching the bezel: both camera panels centre the camera on the
                    // top edge, and any margin here would float the arc away from it.
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    x = 0
                    y = 0
                }
                attached = runCatching {
                    wm.addView(candidate, params)
                    view = candidate
                    startPulse()
                    true
                }.onFailure { Log.w(TAG, "indicator addView failed: ${it.javaClass.simpleName}") }
                    .getOrDefault(false)
            }
        }
        if (!ran || !attached) return false
        return refresh()
    }

    /**
     * Move the indication between the overlay and the LED to match the screen state, and report whether
     * the room is being told. Blocking LED work; never call on the main thread.
     */
    fun refresh(): Boolean {
        val dark = screenOff()
        synchronized(lock) {
            if (view == null) return false
            if (dark && ledHold == null) {
                val hold = ledEffect.hold()
                if (hold == null) {
                    Log.w(TAG, "screen is off and the LED is unavailable; indication is negative")
                } else if (hold.setSolid(255, 0, 0)) {
                    ledHold = hold
                    ledLit = true
                } else {
                    hold.close()
                    Log.w(TAG, "screen is off and the LED would not light; indication is negative")
                }
            } else if (dark && ledHold != null && !ledLit) {
                // A hold whose write failed earlier: try once more rather than sit negative for ever.
                ledLit = ledHold?.setSolid(255, 0, 0) == true
            } else if (!dark && ledHold != null) {
                // This branch is exactly the display coming back: the hold is only ever held while dark.
                // The clock measures how long the room has had the light in front of it, and a dark
                // screen showed it nothing, so the return starts it again.
                releaseLedLocked()
                main.post { startPulse() }
            }
            return CameraIndicationPolicy.positive(overlayAttached = true, screenOff = dark, ledLit = ledLit)
        }
    }

    /** Keep the light on for the minimum hold, then take everything down. */
    fun hide() {
        val token = synchronized(lock) { ++generation }
        main.postDelayed({
            val removeLed: Boolean
            synchronized(lock) {
                if (generation != token) return@postDelayed
                main.removeCallbacks(pulse)
                view?.let { runCatching { wm.removeView(it) } }
                view = null
                removeLed = ledHold != null
            }
            // The LED HAL blocks on every write; never restore it from the main looper.
            if (removeLed) offMain { synchronized(lock) { if (generation == token) releaseLedLocked() } }
        }, holdAfterCloseMs)
    }

    /**
     * Teardown: no hold, no delay. Safe from any thread, and never waits for the main thread: service
     * teardown calls this from its runtime lane while the main thread waits for that lane.
     */
    fun forceHide() {
        val token = synchronized(lock) { ++generation }
        val takeDown = Runnable {
            synchronized(lock) {
                if (generation != token) return@Runnable
                main.removeCallbacks(pulse)
                view?.let { runCatching { wm.removeView(it) } }
                view = null
            }
        }
        val onMainNow = Looper.myLooper() == Looper.getMainLooper()
        if (onMainNow) takeDown.run() else main.post(takeDown)
        if (onMainNow) offMain { synchronized(lock) { releaseLedLocked() } }
        else synchronized(lock) { releaseLedLocked() }
    }

    private fun releaseLedLocked() {
        val hold = ledHold ?: return
        ledHold = null
        ledLit = false
        hold.close()
        runCatching { restoreLed() }.onFailure { Log.w(TAG, "LED restore failed: ${it.javaClass.simpleName}") }
    }

    private fun offMain(action: () -> Unit) {
        Thread({ runCatching(action).onFailure { Log.w(TAG, "LED work failed: ${it.javaClass.simpleName}") } }, "camera-light-led").start()
    }

    private fun ensureOverlayPermission(): Boolean {
        if (canDraw()) return true
        // The navbar grants itself the same way on rooted panels; without root this stays false.
        Su.run("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        return canDraw()
    }

    private fun canDraw(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun onMain(action: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return true
        }
        val done = CountDownLatch(1)
        if (!main.post {
                try {
                    action()
                } finally {
                    done.countDown()
                }
            }
        ) return false
        return try {
            done.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** A red disc with a white ring: legible at walking-past distance, unmistakable as "recording". */
    private class IndicatorView(context: Context, private val lensOffsetPx: Int) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 30, 30) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f * context.resources.displayMetrics.density
        }

        override fun onDraw(canvas: Canvas) {
            // A whole circle whose centre sits above the screen edge, so the window clips everything
            // but its lowest third — the arc that curves under the lens.
            val cx = CameraIndicatorGeometry.centreX(width)
            val cy = CameraIndicatorGeometry.centreY(lensOffsetPx)
            val r = CameraIndicatorGeometry.radius(lensOffsetPx, ring.strokeWidth)
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ring)
        }
    }

    companion object {
        private const val TAG = "ha-paneld/camera-light"
        private const val MAIN_TIMEOUT_MS = 1_000L
        const val HOLD_AFTER_CLOSE_MS = 3_000L
    }
}
