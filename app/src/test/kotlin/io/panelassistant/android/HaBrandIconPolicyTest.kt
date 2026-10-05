package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The sampling arithmetic that keeps a decode bounded. The byte cap bounds the COMPRESSED size only;
 * this is what stops a small file allocating an arbitrarily large bitmap on a constrained panel.
 */
class HaBrandIconPolicyTest {
    @Test fun anImageWithinTheCapDecodesAtFullSize() {
        assertEquals(1, iconSampleSize(192, 192, 1024))
        assertEquals(1, iconSampleSize(1024, 512, 1024))
    }

    @Test fun anOversizeImageIsSampledDownByPowersOfTwo() {
        assertEquals(2, iconSampleSize(2048, 512, 1024))
        assertEquals(4, iconSampleSize(4096, 4096, 1024))
        assertEquals(16, iconSampleSize(16000, 9000, 1024))
    }

    @Test fun aPathologicallyLargeImageStillResolvesToABoundedDecode() {
        val sample = iconSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 1024)!!
        assertEquals(0, sample and (sample - 1)) // power of two
        assertEquals(true, Int.MAX_VALUE / sample <= 1024)
    }

    @Test fun aNonImageIsRejectedRatherThanGuessedAt() {
        // A captive-portal HTML page reports non-positive bounds; it must never reach a real decode.
        assertNull(iconSampleSize(0, 0, 1024))
        assertNull(iconSampleSize(-1, 100, 1024))
        assertNull(iconSampleSize(100, 0, 1024))
        assertNull(iconSampleSize(100, 100, 0))
    }
}
