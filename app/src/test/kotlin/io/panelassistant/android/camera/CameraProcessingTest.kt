package io.panelassistant.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The processing choices these panels allow, and the ones they do not.
 *
 * Enumerated from both camera panels on 2026-08-31: `availableCapabilities` is a single byte,
 * `BACKWARD_COMPATIBLE`, so there is no `MANUAL_SENSOR`, no `BURST_CAPTURE` and no `RAW` — bracketed
 * capture and per-frame exposure, which is what HDR is made of, are not available to build on at any
 * level. `availableSceneModes` is `[0]`, so there is no vendor HDR mode either. What both boards *do*
 * offer is exposure compensation of -6..+6 at 1/3 EV, and OFF/FAST/HIGH_QUALITY for both noise
 * reduction and edge enhancement. These assertions pin how that is spent.
 */
class CameraProcessingTest {

    // The real values both panels report.
    private val fast = 1
    private val highQuality = 2
    private val offFastHq = intArrayOf(0, 1, 2)

    @Test fun aStillGetsTheExpensivePipelineAndAStreamDoesNot() {
        assertEquals(
            "one frame a person looks at can afford it",
            highQuality,
            CameraProcessing.qualityMode(forStream = false, available = offFastHq, fast = fast, highQuality = highQuality),
        )
        assertEquals(
            "the same cost fifteen times a second, beside a rendering dashboard, cannot",
            fast,
            CameraProcessing.qualityMode(forStream = true, available = offFastHq, fast = fast, highQuality = highQuality),
        )
    }

    @Test fun aDeviceThatDoesNotOfferTheModeIsLeftAlone() {
        // Null means "set nothing", so an unsupported device keeps its own default rather than being
        // handed a value it never advertised — which is a rejected request, not a better picture.
        assertNull(CameraProcessing.qualityMode(false, intArrayOf(0), fast, highQuality))
        assertNull(CameraProcessing.qualityMode(true, intArrayOf(0), fast, highQuality))
        assertNull(CameraProcessing.qualityMode(false, null, fast, highQuality))
        assertNull(CameraProcessing.qualityMode(false, intArrayOf(), fast, highQuality))
        // A device offering only FAST gets FAST for a still rather than nothing.
        assertEquals(fast, CameraProcessing.qualityMode(true, intArrayOf(0, 1), fast, highQuality))
        assertNull("but a still must not be given a mode that is absent", CameraProcessing.qualityMode(false, intArrayOf(0, 1), fast, highQuality))
    }

    @Test fun exposureIsExpressedInStopsAndClampedToTheDeviceRange() {
        // Both panels: -6..+6 at 1/3 EV, i.e. plus or minus two stops.
        fun steps(ev: Double) = CameraProcessing.exposureSteps(ev, lower = -6, upper = 6, stepNumerator = 1, stepDenominator = 3)
        assertEquals("no bias means no bias", 0, steps(0.0))
        assertEquals("one stop up is three steps of a third", 3, steps(1.0))
        assertEquals(-3, steps(-1.0))
        assertEquals("two stops is the whole range", 6, steps(2.0))
        assertEquals(-6, steps(-2.0))
        assertEquals("beyond the range clamps rather than overflows", 6, steps(9.0))
        assertEquals(-6, steps(-9.0))
        assertEquals("a third of a stop is one step", 1, steps(0.33))
    }

    @Test fun aDifferentStepSizeStillMeansTheSameNumberOfStops() {
        // The setting is in EV so it survives a board that counts in halves rather than thirds.
        assertEquals(2, CameraProcessing.exposureSteps(1.0, -4, 4, stepNumerator = 1, stepDenominator = 2))
        assertEquals(4, CameraProcessing.exposureSteps(2.0, -4, 4, stepNumerator = 1, stepDenominator = 2))
    }

    @Test fun aDeviceAdvertisingNoRangeGetsNoBias() {
        // An empty range needs no guard of its own: coercing into 0..0 is already no bias. The mutation
        // battery proved the guard that used to be here could not change any answer, so it was deleted
        // rather than left standing as protection that was not doing anything.
        assertEquals("no range means the control does not exist", 0, CameraProcessing.exposureSteps(2.0, 0, 0, 1, 3))
        assertEquals("and the same in the other direction", 0, CameraProcessing.exposureSteps(-2.0, 0, 0, 1, 3))
        assertEquals("a nonsense step is not a licence to invent one", 0, CameraProcessing.exposureSteps(2.0, -6, 6, 0, 3))
        assertEquals(0, CameraProcessing.exposureSteps(2.0, -6, 6, 1, 0))
    }
}
