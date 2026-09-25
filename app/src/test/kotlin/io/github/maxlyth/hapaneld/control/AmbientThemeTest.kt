package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln1p

/**
 * The Ambient theme's rules: a dark room goes dark after the dwell, a brief change does nothing, the
 * hysteresis gap holds, and a panel with nothing to measure says why it is following Home Assistant.
 */
class AmbientThemeTest {

    private val dark = 0.05
    private val light = 0.9

    // --- dwell ---------------------------------------------------------------------------------

    @Test fun `a dark room turns the theme dark once the dwell has passed and not before`() {
        val decider = AmbientThemeDecider(initialDark = false)
        assertFalse(decider.observe(0L, dark))
        assertFalse(decider.observe(59_999L, dark))
        assertEquals(false, decider.dark)
        assertTrue(decider.observe(60_000L, dark))
        assertEquals(true, decider.dark)
    }

    @Test fun `a pending change names the moment it would be adopted`() {
        val decider = AmbientThemeDecider(initialDark = false)
        assertNull(decider.pendingDeadlineMs())
        decider.observe(5_000L, dark)
        assertEquals(65_000L, decider.pendingDeadlineMs())
        assertEquals(true, decider.pendingDark())
        // Further readings on the same side do not restart the wait.
        decider.observe(30_000L, dark)
        assertEquals(65_000L, decider.pendingDeadlineMs())
    }

    @Test fun `the first verdict also waits out a dwell`() {
        val decider = AmbientThemeDecider(initialDark = null)
        assertFalse(decider.observe(0L, dark))
        assertNull(decider.dark)
        assertTrue(decider.observe(60_000L, dark))
        assertEquals(true, decider.dark)
    }

    // --- a brief change --------------------------------------------------------------------------

    @Test fun `a light switched on for half a minute changes nothing`() {
        val decider = AmbientThemeDecider(initialDark = true)
        assertFalse(decider.observe(0L, light))
        assertFalse(decider.observe(30_000L, dark))
        assertNull("returning to the current side abandons the change", decider.pendingDeadlineMs())
        assertFalse(decider.observe(61_000L, dark))
        assertEquals(true, decider.dark)
    }

    @Test fun `an interrupted change starts its dwell again`() {
        val decider = AmbientThemeDecider(initialDark = true)
        decider.observe(0L, light)
        decider.observe(40_000L, dark)
        assertFalse(decider.observe(50_000L, light))
        assertFalse("the first 40 s no longer count", decider.observe(100_000L, light))
        assertEquals(true, decider.dark)
        assertTrue(decider.observe(110_000L, light))
        assertEquals(false, decider.dark)
    }

    // --- hysteresis --------------------------------------------------------------------------------

    @Test fun `a room sitting just inside the gap never switches either way`() {
        val fromDark = AmbientThemeDecider(initialDark = true)
        val fromLight = AmbientThemeDecider(initialDark = false)
        val justBelowLight = AmbientThemeDecider.LIGHT_AT_OR_ABOVE - 0.001
        val justAboveDark = AmbientThemeDecider.DARK_AT_OR_BELOW + 0.001
        for (t in 0L..3_600_000L step 1_000L) {
            assertFalse(fromDark.observe(t, justBelowLight))
            assertFalse(fromLight.observe(t, justAboveDark))
        }
        assertEquals(true, fromDark.dark)
        assertEquals(false, fromLight.dark)
    }

    @Test fun `each threshold switches at its own boundary`() {
        val toLight = AmbientThemeDecider(initialDark = true)
        toLight.observe(0L, AmbientThemeDecider.LIGHT_AT_OR_ABOVE)
        assertTrue(toLight.observe(60_000L, AmbientThemeDecider.LIGHT_AT_OR_ABOVE))
        assertEquals(false, toLight.dark)

        val toDark = AmbientThemeDecider(initialDark = false)
        toDark.observe(0L, AmbientThemeDecider.DARK_AT_OR_BELOW)
        assertTrue(toDark.observe(60_000L, AmbientThemeDecider.DARK_AT_OR_BELOW))
        assertEquals(true, toDark.dark)
    }

    @Test fun `a reading inside the gap abandons a pending change`() {
        val decider = AmbientThemeDecider(initialDark = false)
        decider.observe(0L, dark)
        decider.observe(30_000L, 0.2)
        assertNull(decider.pendingDeadlineMs())
        assertFalse(decider.observe(60_000L, dark))
        assertEquals(false, decider.dark)
    }

    @Test fun `thresholds leave a real gap and the dwell is at least a minute`() {
        assertTrue(AmbientThemeDecider.DARK_AT_OR_BELOW < AmbientThemeDecider.LIGHT_AT_OR_ABOVE)
        assertTrue(AmbientThemeDecider.DWELL_MS >= 60_000L)
    }

    @Test fun `restarting the dwell keeps the verdict`() {
        val decider = AmbientThemeDecider(initialDark = true)
        decider.observe(0L, light)
        decider.restartDwell()
        assertNull(decider.pendingDeadlineMs())
        assertEquals(true, decider.dark)
        assertFalse(decider.observe(60_000L, light))
    }

    @Test fun `a non-finite level is ignored`() {
        val decider = AmbientThemeDecider(initialDark = false)
        decider.observe(0L, dark)
        assertFalse(decider.observe(10_000L, Double.NaN))
        assertEquals(0L + 60_000L, decider.pendingDeadlineMs())
    }

    // --- through the auto-brightness model ---------------------------------------------------------

    /** A learned basement: its own lights are 200 lx and off is 0 lx, so the range spans exactly that. */
    private val basement = BaselineEstimate(
        expectedLogLux = ln1p(200.0),
        madLogLux = 0.1,
        coveredDays = 4,
        matchedMinutes = 20,
        brightnessRange = AdaptiveBrightnessRange(lowLogLux = 0.0, highLogLux = ln1p(200.0), learnedWeight = 1.0),
    )

    private class Room(initialDark: Boolean?) {
        val policy = AdaptiveBrightnessPolicy()
        val decider = AmbientThemeDecider(initialDark)
        var now = 0L
        var switchedAt: Long? = null

        fun hold(lux: Double, forMs: Long, baseline: BaselineEstimate) {
            val end = now + forMs
            while (now < end) {
                now += 1_000L
                val result = checkNotNull(policy.evaluate(1_000_000_000L + now, 1_000L, lux, baseline, 100))
                val level = AdaptiveLuxCurve.normalizedLevel(result.effectiveLux, result.estimate.brightnessRange)
                if (decider.observe(now, level)) switchedAt = now
            }
        }
    }

    @Test fun `a basement whose lights go off turns dark through the model after the dwell`() {
        val room = Room(initialDark = false)
        room.hold(200.0, 40L * 60_000L, basement)
        assertEquals(false, room.decider.dark)
        assertNull(room.switchedAt)

        val off = room.now
        room.hold(0.0, 59_000L, basement)
        assertEquals("not before the dwell", false, room.decider.dark)
        room.hold(0.0, 3L * 60_000L, basement)
        assertEquals(true, room.decider.dark)
        val after = checkNotNull(room.switchedAt) - off
        assertTrue("switched ${after}ms after the lights went off", after in 60_000L..62_000L)
    }

    @Test fun `a basement lit for half a minute at night stays dark through the model`() {
        val room = Room(initialDark = true)
        room.hold(0.0, 40L * 60_000L, basement)
        room.hold(200.0, 30_000L, basement)
        room.hold(0.0, 5L * 60_000L, basement)
        assertEquals(true, room.decider.dark)
        assertNull(room.switchedAt)
    }

    @Test fun `normalised level is the backlight's room fraction without the minimum floor`() {
        val range = basement.brightnessRange
        assertEquals(0.0, AdaptiveLuxCurve.normalizedLevel(0.0, range), 1e-9)
        assertEquals(1.0, AdaptiveLuxCurve.normalizedLevel(200.0, range), 1e-9)
        for (lux in listOf(0.0, 1.0, 5.0, 20.0, 80.0, 200.0, 5_000.0)) {
            val level = AdaptiveLuxCurve.normalizedLevel(lux, range)
            val backlight = AdaptiveLuxCurve.rawBrightness(lux, range)
            val expected = BrightnessController.MIN_VISIBLE + level * (255 - BrightnessController.MIN_VISIBLE)
            assertEquals("lux $lux", expected, backlight.toDouble(), 1.0)
            // Raising the automatic minimum moves the backlight, never the room's level.
            assertTrue(AdaptiveLuxCurve.rawBrightness(lux, range, minimumBrightness = 128) >= backlight)
        }
        // A cold model reads the fixed curve, exactly as the backlight does.
        assertEquals(
            (AdaptiveLuxCurve.rawBrightness(100.0) - BrightnessController.MIN_VISIBLE).toDouble() /
                (255 - BrightnessController.MIN_VISIBLE),
            AdaptiveLuxCurve.normalizedLevel(100.0),
            1e-9,
        )
    }

    // --- no light sensor -------------------------------------------------------------------------

    @Test fun `a panel with no light source is told so before anything else`() {
        for (auto in listOf(true, false)) for (verdict in listOf(true, false, null)) {
            assertEquals(
                AmbientThemeReason.NO_LIGHT_SOURCE,
                AmbientThemeReason.of(auto, hasLightSource = false, verdictDark = verdict, sourceAvailable = false),
            )
        }
    }

    @Test fun `every other reason is reported for its own state`() {
        assertEquals(AmbientThemeReason.AUTO_BRIGHTNESS_OFF, AmbientThemeReason.of(false, true, true, true))
        assertEquals(AmbientThemeReason.WAITING, AmbientThemeReason.of(true, true, null, true))
        assertEquals(AmbientThemeReason.HOLDING, AmbientThemeReason.of(true, true, true, false))
        assertEquals(AmbientThemeReason.ROOM_DARK, AmbientThemeReason.of(true, true, true, true))
        assertEquals(AmbientThemeReason.ROOM_LIGHT, AmbientThemeReason.of(true, true, false, true))
    }
}
