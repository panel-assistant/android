package io.panelassistant.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the camera light backs off over a session. The geometry test pins what the arc looks like; this
 * pins what it does as the session runs on.
 *
 * Once the gap has faded out the flash IS the indication, so the assertions that matter are the two that
 * keep it one: it never shortens, and it never fades past its floor.
 */
class CameraIndicatorAttenuationTest {

    private val P = CameraIndicatorPulse
    private val settled = P.PROMINENT_MS + P.RAMP_MS

    /** Session ages across both phases and well past the end of the schedule. */
    private fun ages() = (0..600).map { it * 1_000L } + listOf(1L, settled - 1, settled + 1, 86_400_000L)

    @Test fun theFirstHalfMinuteIsTheLightWeAlreadyHad() {
        // Literals, not comparisons against the constants they come from: a derived assertion is a
        // tautology that cannot fail when the constant moves.
        assertEquals(30_000L, P.PROMINENT_MS)
        listOf(0L, 1L, 15_000L, P.PROMINENT_MS).forEach { age ->
            assertEquals(1.0f, P.alphaAt(age, lit = true), 0.0001f)
            assertEquals(0.42f, P.alphaAt(age, lit = false), 0.0001f)
            assertEquals("one flash a second while prominent", 500L, P.gapMsAt(age))
        }
    }

    @Test fun theFlashNeverShortensAndTheGapStretchesToOneAMinute() {
        assertEquals("the flash is half a second, at every age", 500L, P.STEP_MS)
        assertEquals("with the flash, the settled cycle is exactly one minute", 59_500L, P.MAX_GAP_MS)
        assertEquals("the ramp is two minutes", 120_000L, P.RAMP_MS)
        assertEquals(59_500L, P.gapMsAt(settled))
        // Geometric rather than linear: halfway through the ramp a linear gap would already be half a
        // minute, which is a cliff rather than a back-off while somebody may still be reacting.
        assertTrue("the gap must stay frequent through the middle of the ramp",
            P.gapMsAt(P.PROMINENT_MS + P.RAMP_MS / 2) < 10_000L)
        var previous = 0L
        ages().sorted().forEach { age ->
            val gap = P.gapMsAt(age)
            assertTrue("at ${age}ms the gap shrank from $previous to $gap", gap >= previous)
            assertTrue("at ${age}ms the gap exceeded one minute", gap <= P.MAX_GAP_MS)
            previous = gap
        }
    }

    @Test fun theFlashIsNeverTransparentAtAnyPointInAnySession() {
        // The one the feature stands on: at rest the blip is the whole indication.
        assertEquals(0.62f, P.LIT_FLOOR, 0.0001f)
        assertTrue("a flash that fades toward nothing is not an indication", P.LIT_FLOOR > 0.5f)
        ages().forEach { age ->
            val flash = P.alphaAt(age, lit = true)
            assertTrue("at ${age}ms the flash had faded to $flash", flash >= P.LIT_FLOOR - 0.0001f)
        }
    }

    @Test fun theGapFadesOutCompletelyBecauseTheFlashIsWhatIndicates() {
        // Deliberately the opposite of the flash rule: an arc held for fifty-nine of every sixty seconds
        // is the standing prominence this change exists to remove.
        assertEquals(0.0f, P.alphaAt(settled, lit = false), 0.0001f)
        assertTrue("it must fade rather than blanking the moment attenuation starts",
            P.alphaAt(P.PROMINENT_MS + P.RAMP_MS / 2, lit = false) > 0.15f)
        var previous = Float.MAX_VALUE
        ages().sorted().forEach { age ->
            val gap = P.alphaAt(age, lit = false)
            assertTrue("at ${age}ms the gap brightened again", gap <= previous + 0.0001f)
            assertTrue("the flash must always outshine the gap", P.alphaAt(age, lit = true) > gap)
            previous = gap
        }
    }

    @Test fun aBackwardsClockPresentsAsProminentRatherThanAlreadyFaded() {
        listOf(-1L, -1_000L, Long.MIN_VALUE / 2).forEach { age ->
            assertEquals(0f, P.progressAt(age), 0.0001f)
            assertEquals(1.0f, P.alphaAt(age, lit = true), 0.0001f)
            assertEquals(500L, P.gapMsAt(age))
        }
        // And it settles rather than running past its floor.
        assertEquals(1f, P.progressAt(settled * 10), 0.0001f)
        assertEquals(P.LIT_FLOOR, P.alphaAt(settled * 10, lit = true), 0.0001f)
    }
}
