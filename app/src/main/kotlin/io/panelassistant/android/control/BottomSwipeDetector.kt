package io.panelassistant.android.control

import kotlin.math.abs

/**
 * Classifies a touch gesture as a bottom-edge swipe-reveal (for the soft navbar) or not, from its
 * origin — never from content-scroll state. Pure Kotlin (no Android imports) so it is JVM-unit-tested;
 * [io.panelassistant.android.DashboardActivity]'s `BottomSwipeFrame` feeds it MotionEvent coordinates
 * and dp→px is done by the caller.
 *
 * Why in-activity instead of the [NavbarController] overlay strip: on the built-in renderer that strip
 * is a full-width bottom [android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] that
 * *consumes every touch* in its band and must re-inject non-swipe taps out-of-process — so the
 * dashboard's bottom band was tap-dead or laggy. This detector instead lets taps and scrolls through
 * untouched and steals only a genuine
 * upward edge-swipe.
 *
 * The gesture qualifies only if the DOWN lands within [bandPx] of the bottom edge (the same 48dp the
 * overlay used — a fast bezel flick's first digitizer sample can land tens of dp above the physical
 * edge, so the band stays wide; unlike the overlay a wide band costs nothing here because tracking a
 * non-qualifying gesture never consumes it), then travels upward ≥ [minTravelPx] and is
 * vertical-dominant (`dy > |dx|`, so a horizontal card carousel or slider drag that begins in the band
 * still passes through). A second pointer (pinch) or the gesture ending aborts it.
 */
class BottomSwipeDetector(private val bandPx: Float, private val minTravelPx: Float) {
    /** True while a DOWN that landed in the edge band is still a reveal candidate. Read by the
     *  container to hold `requestDisallowInterceptTouchEvent` so page content can't steal an
     *  edge-origin gesture before it qualifies. */
    var tracking = false
        private set

    private var fired = false
    private var downX = 0f
    private var downY = 0f

    /** DOWN at view-local ([x], [y]) in a view [viewHeightPx] tall. [enabled] = swipe-reveal is the
     *  live navbar mode AND the built-in renderer is foreground (else the gesture is never ours). */
    fun onDown(x: Float, y: Float, viewHeightPx: Int, enabled: Boolean) {
        tracking = enabled && viewHeightPx > 0 && y >= viewHeightPx - bandPx
        fired = false
        downX = x
        downY = y
    }

    /** Returns true exactly once per gesture — at the first sample that qualifies (the caller then
     *  steals the touch stream and triggers the reveal). Sub-threshold or non-vertical moves keep
     *  tracking (a gesture that curves upward later can still qualify). */
    fun onMove(x: Float, y: Float): Boolean {
        if (!tracking || fired) return false
        val dy = downY - y
        if (dy >= minTravelPx && dy > abs(x - downX)) {
            fired = true
            return true
        }
        return false
    }

    /** Gesture end / second finger / anything disqualifying — this gesture can never fire now. */
    fun abort() {
        tracking = false
    }

    companion object {
        /** Bottom edge band (dp) a DOWN must land in to be a reveal candidate. Canonical — the
         *  [NavbarController] overlay strip height points here so the two paths can't drift. */
        const val BAND_DP = 48

        /** Upward travel (dp) from the DOWN origin that arms the reveal. Canonical — [NavbarController]'s
         *  swipe threshold points here. */
        const val MIN_TRAVEL_DP = 20
    }
}
