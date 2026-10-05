package io.panelassistant.android.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure px, density-free: a 480px-tall view with a 96px bottom band (y in [384,480]) and a 40px
 * upward-travel threshold. Mirrors the on-panel 48dp band / 20dp threshold at density 2.
 */
class BottomSwipeDetectorTest {
    private fun detector() = BottomSwipeDetector(bandPx = 96f, minTravelPx = 40f)
    private val H = 480

    @Test fun tapInBandNeverFires() {
        val d = detector()
        d.onDown(200f, 450f, H, enabled = true)
        assertTrue(d.tracking)
        // Sub-threshold jitter — a real tap never crosses 40px of upward travel.
        assertFalse(d.onMove(200f, 445f))
        assertFalse(d.onMove(205f, 448f))
        assertFalse(d.onMove(198f, 451f))
    }

    @Test fun downAboveBandNeverFires() {
        val d = detector()
        d.onDown(200f, 380f, H, enabled = true) // 380 < 384 = above the band
        assertFalse(d.tracking)
        assertFalse(d.onMove(200f, 100f)) // huge upward travel, but origin disqualified it
    }

    @Test fun bandBoundaryIsInclusive() {
        val d = detector()
        d.onDown(200f, 384f, H, enabled = true) // exactly height - bandPx
        assertTrue(d.tracking)
        assertTrue(d.onMove(200f, 340f)) // dy 44 >= 40
    }

    @Test fun upwardSwipeInBandFiresExactlyOnce() {
        val d = detector()
        d.onDown(200f, 450f, H, enabled = true)
        assertFalse(d.onMove(200f, 430f)) // dy 20 < 40
        assertTrue(d.onMove(200f, 405f)) // dy 45 >= 40, vertical → fire
        assertFalse(d.onMove(200f, 300f)) // already fired
        assertFalse(d.onMove(200f, 200f))
    }

    @Test fun horizontalDominantMoveDoesNotFire() {
        val d = detector()
        d.onDown(200f, 450f, H, enabled = true)
        assertFalse(d.onMove(300f, 405f)) // dy 45 but |dx| 100 dominates — a carousel/slider drag
    }

    @Test fun gestureCurlingUpwardLaterFires() {
        val d = detector()
        d.onDown(200f, 450f, H, enabled = true)
        assertFalse(d.onMove(300f, 405f)) // horizontal first — passes through
        assertTrue(d.onMove(210f, 400f)) // now vertical-dominant (dy 50 > |dx| 10) → fire
    }

    @Test fun disabledAtDownNeverFires() {
        val d = detector()
        d.onDown(200f, 450f, H, enabled = false) // navbar not in swipe mode / renderer not foreground
        assertFalse(d.tracking)
        assertFalse(d.onMove(200f, 300f))
    }

    @Test fun secondPointerAborts() {
        val d = detector()
        d.onDown(200f, 450f, H, enabled = true)
        assertTrue(d.tracking)
        d.abort() // POINTER_DOWN (pinch) — a bottom-band pinch on a map card must not reveal
        assertFalse(d.tracking)
        assertFalse(d.onMove(200f, 300f))
    }

    @Test fun upRearmsOnNextDown() {
        val d = detector()
        d.onDown(200f, 450f, H, enabled = true)
        assertTrue(d.onMove(200f, 400f)) // fire
        d.abort() // UP
        d.onDown(200f, 450f, H, enabled = true) // fresh gesture
        assertTrue(d.tracking)
        assertTrue(d.onMove(200f, 400f)) // fires again
    }

    @Test fun travelMeasuredFromDownOrigin() {
        val d = detector()
        d.onDown(200f, 450f, H, enabled = true)
        assertFalse(d.onMove(200f, 460f)) // moved DOWN 10px from origin
        assertTrue(d.onMove(200f, 405f)) // net 45px up from the ORIGIN (not the last sample) → fire
    }
}
