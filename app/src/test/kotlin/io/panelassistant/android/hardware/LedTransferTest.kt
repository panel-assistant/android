package io.panelassistant.android.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-profile LED transfer functions: passthrough default + the rk3576 4-bit stub. */
class LedTransferTest {

    @Test fun identityPassesThroughClamped() {
        val t = LedTransfer.Identity
        assertEquals(0, t.red(0))
        assertEquals(200, t.green(200))
        assertEquals(255, t.blue(255))
        assertEquals(0, t.red(-5))       // clamped
        assertEquals(255, t.blue(999))   // clamped
    }

    @Test fun rk3576FourBitMapsToTheNativeRangeSameOnEveryChannel() {
        val t = LedTransfer.Rk3576FourBit
        assertEquals(0, t.red(0))
        assertEquals(15, t.red(255))
        // Same curve on every channel for the stub.
        for (v in intArrayOf(0, 17, 128, 200, 255)) {
            assertEquals(t.red(v), t.green(v))
            assertEquals(t.green(v), t.blue(v))
        }
    }

    @Test fun rk3576FourBitTruncatesTowardsZero() {
        val t = LedTransfer.Rk3576FourBit
        assertEquals(7, t.red(128))          // 128*15/255 = 7.5 -> 7
        assertEquals(9, t.red(165))          // 165*15/255 = 9.7 -> 9
        assertEquals(1, t.red(17))
        for (v in 0..16) assertEquals("sub-level $v -> 0", 0, t.red(v))
    }

    @Test fun rk3576FourBitNeverFlashesWhiteAsAColourDims() {
        // A muted blue (65,89,132) must never dim through an equal-channel grey/white (r==g==b, all > 0):
        // truncation drops the weaker channels to 0 toward the dominant blue. White-flash regression guard.
        val t = LedTransfer.Rk3576FourBit
        for (br in 0..255) {
            val r = t.red(65 * br / 255)
            val g = t.green(89 * br / 255)
            val b = t.blue(132 * br / 255)
            assertTrue("white flash at br=$br -> ($r,$g,$b)", !(r == g && g == b && r > 0))
        }
    }
}
