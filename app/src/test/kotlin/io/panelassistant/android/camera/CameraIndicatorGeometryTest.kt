package io.panelassistant.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera light's shape and movement, asserted where they are decided rather than in a photograph.
 *
 * Both camera panels centre the lens on the top bezel, so the indicator is the visible bottom of a circle
 * centred *on the lens* — on screen it reads as an arc curving under the camera. The first version derived
 * that centre from an invented visible fraction, which silently asserted the lens was 27 px above the
 * active area; photographs of both panels put it at 63 px and 43 px, and the arc looked wrong on each.
 * These assertions pin the relationship that fixes it, so a future change cannot quietly reintroduce a
 * centre that is merely close.
 */
class CameraIndicatorGeometryTest {

    // Measured from photographs of both panels on 2026-08-30, cross-checked two ways per photo.
    private val tpa10 = 63
    private val wf1589t = 43

    @Test fun theCircleIsCentredOnTheLensRatherThanOnAGuess() {
        listOf(tpa10, wf1589t).forEach { lens ->
            assertEquals(
                "the centre must sit exactly on the lens, which is what makes the arc wrap it",
                -lens.toFloat(),
                CameraIndicatorGeometry.centreY(lens),
                0.01f,
            )
        }
        // The two camera panels genuinely differ, which is why this cannot be one shared constant.
        assertNotEquals(CameraIndicatorGeometry.centreY(tpa10), CameraIndicatorGeometry.centreY(wf1589t))
    }

    @Test fun theVisibleBandIsConstantWhateverTheLensOffset() {
        // Pin the value as well as the relationship. Comparing the computed band against the constant it
        // is derived from is a tautology that cannot fail when the constant moves — the mutation battery
        // caught exactly that, so the height a person actually sees is asserted here as a literal.
        assertEquals("the visible band is what the room sees; it is not free to drift", 53, CameraIndicatorGeometry.VISIBLE_BAND_PX)
        listOf(tpa10, wf1589t, 10, 120).forEach { lens ->
            val bottomOfCircle = CameraIndicatorGeometry.centreY(lens) + CameraIndicatorGeometry.radiusPx(lens)
            assertEquals(
                "what the room sees stays the same height however deep the bezel is",
                CameraIndicatorGeometry.VISIBLE_BAND_PX.toFloat(),
                bottomOfCircle,
                0.01f,
            )
            assertEquals(CameraIndicatorGeometry.VISIBLE_BAND_PX, CameraIndicatorGeometry.windowHeightPx)
        }
    }

    @Test fun aDeeperBezelMeansABiggerFlatterArc() {
        assertTrue(
            "the TPA10's lens is higher, so its circle must be larger",
            CameraIndicatorGeometry.radiusPx(tpa10) > CameraIndicatorGeometry.radiusPx(wf1589t),
        )
        assertEquals(63f + 53f, CameraIndicatorGeometry.radiusPx(tpa10), 0.01f)
        assertEquals(43f + 53f, CameraIndicatorGeometry.radiusPx(wf1589t), 0.01f)
        assertEquals(232, CameraIndicatorGeometry.windowWidthPx(tpa10))
        assertEquals(192, CameraIndicatorGeometry.windowWidthPx(wf1589t))
    }

    @Test fun anUnmeasuredProfileFallsBackInsteadOfCollapsing() {
        assertEquals(
            CameraIndicatorGeometry.DEFAULT_LENS_OFFSET_PX,
            CameraIndicatorGeometry.lensOffsetOrDefault(null),
        )
        assertEquals(
            "a zero or negative measurement is not a lens position",
            CameraIndicatorGeometry.DEFAULT_LENS_OFFSET_PX,
            CameraIndicatorGeometry.lensOffsetOrDefault(0),
        )
        assertEquals(tpa10, CameraIndicatorGeometry.lensOffsetOrDefault(tpa10))
    }

    @Test fun theArcIsCentredAndInsetByTheStrokeSoTheOutlineIsNotClipped() {
        assertEquals(116f, CameraIndicatorGeometry.centreX(232), 0.01f)
        val stroke = 4f
        assertEquals(
            CameraIndicatorGeometry.radiusPx(tpa10) - stroke / 2f,
            CameraIndicatorGeometry.radius(tpa10, stroke),
            0.01f,
        )
        assertTrue(CameraIndicatorGeometry.radius(tpa10, stroke) < CameraIndicatorGeometry.radiusPx(tpa10))
    }

    /**
     * The pulse a session opens at is two stepped levels once a second. Cost is why: a stepped pulse is
     * two layer updates a second, while an animator on alpha redraws at the display rate — about 41% of
     * a core, charged for as long as the camera is open. These assertions pin the cheap shape, not the
     * look. What happens to that pulse as the session runs on is
     * [CameraIndicatorAttenuationTest]'s subject, not this one's.
     */
    @Test fun theSessionOpensAtTwoSteppedLevelsASecond() {
        assertEquals(1_000L, CameraIndicatorPulse.PERIOD_MS)
        assertEquals(
            "two steps per period, so the light changes twice a second and no more",
            500L,
            CameraIndicatorPulse.STEP_MS,
        )
        assertEquals(2L, CameraIndicatorPulse.PERIOD_MS / CameraIndicatorPulse.STEP_MS)
    }

    @Test fun theDimLevelIsStillVisibleBecauseABlinkWouldNotBe() {
        assertEquals(1.0f, CameraIndicatorPulse.BRIGHT, 0.001f)
        assertTrue(
            "the dim level must stay visible: an indication that disappears is not an indication",
            CameraIndicatorPulse.DIM > 0.2f,
        )
        assertTrue("the dim level must be visibly dimmer than bright", CameraIndicatorPulse.DIM < 0.8f)
    }

}
