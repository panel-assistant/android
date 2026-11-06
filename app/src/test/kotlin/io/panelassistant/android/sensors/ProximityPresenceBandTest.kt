package io.panelassistant.android.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityPresenceBandTest {
    @Test fun overlappingRawRangesStillDistinguishASustainedShiftInBothPolaritiesAndScales() {
        for (scale in listOf(.01f, 1f, 100f, -1f)) {
            val h = Replay(565f * scale, 590f * scale)
            h.run(20_000) { noisy(565f, it) * scale }
            assertEquals(false, h.state.near)
            h.run(10_000) { noisy(590f, it) * scale }
            assertEquals(true, h.state.near)
            assertEquals(1, h.approaches)
            h.run(120_000) { noisy(590f, it) * scale }
            assertEquals("a still person must not become the new clear baseline", true, h.state.near)
            assertEquals(1, h.approaches)
            h.run(10_000) { noisy(565f, it) * scale }
            assertEquals(false, h.state.near)
        }
    }

    @Test fun setupUsesTheSameDistributionSeparationRatherThanRawRangeOverlap() {
        val quiet = requireNotNull(ProximityPresenceBand.QuietBand.from(floatArrayOf(550f, 558f, 565f, 570f, 580f)))
        val present = requireNotNull(ProximityPresenceBand.QuietBand.from(floatArrayOf(570f, 580f, 590f, 600f, 610f)))
        // These broad distributions need more evidence than five points: uncertainty is retained.
        assertFalse(ProximityPresenceBand.separated(quiet, present))
        val steadyQuiet = requireNotNull(ProximityPresenceBand.QuietBand.from(FloatArray(32) { noisy(565f, it * 250L) }))
        val steadyPresent = requireNotNull(ProximityPresenceBand.QuietBand.from(FloatArray(32) { noisy(590f, it * 250L) }))
        assertTrue(ProximityPresenceBand.separated(steadyQuiet, steadyPresent))
        assertFalse(ProximityPresenceBand.separated(steadyQuiet, steadyQuiet))
        assertTrue(ProximityPresenceBand.within(steadyQuiet, steadyQuiet))
        assertFalse(ProximityPresenceBand.within(steadyQuiet, steadyPresent))
        assertFalse(ProximityPresenceBand.within(steadyQuiet, ProximityPresenceBand.QuietBand(565.0, 10_000.0, 32)))
    }

    @Test fun startingOccupiedNeverTrainsThePersonAsEmptyOrEmitsAnApproach() {
        val h = Replay(565f, 590f)
        h.run(120_000) { noisy(590f, it) }
        assertEquals(true, h.state.near)
        assertEquals(565.0, h.state.quiet.mean, 0.0)
        assertEquals(0, h.approaches)
        h.run(10_000) { noisy(565f, it) }
        assertEquals(false, h.state.near)
        h.run(10_000) { noisy(590f, it) }
        assertEquals(1, h.approaches)
    }

    @Test fun slowQuietDriftTracksButAnOccupiedHoldDoesNotMoveTheBaseline() {
        for (polarity in listOf(1f, -1f)) {
            val h = Replay(100f * polarity, 126f * polarity)
            h.run(20_000) { noisy(100f, it) * polarity }
            h.run(1_800_000) { noisy(100f + it / 120_000f, it) * polarity }
            assertEquals(false, h.state.near)
            assertTrue(h.state.quiet.mean * polarity > 112)
            assertEquals(0, h.approaches)
            val anchored = h.state.quiet.mean
            h.run(120_000) { noisy(141f, it) * polarity }
            assertEquals(true, h.state.near)
            assertEquals(anchored, h.state.quiet.mean, .1)
            // Modest occupied-time environmental drift remains inside the clear band on departure.
            h.run(10_000) { noisy(116f, it) * polarity }
            assertEquals(false, h.state.near)
            h.run(1_800_000) { noisy(116f - it / 120_000f, it) * polarity }
            assertEquals(false, h.state.near)
            assertTrue(h.state.quiet.mean * polarity < 103)
        }
    }

    @Test fun briefHandExcursionDoesNotBecomePresenceAndSourceLossRevokesUnconfirmedEvidence() {
        val h = Replay(100f, 126f)
        h.run(20_000) { noisy(100f, it, 1f) }
        h.run(500) { 500f }
        h.run(10_000) { noisy(100f, it, 1f) }
        assertEquals(false, h.state.near)
        assertEquals(0, h.approaches)
        h.run(3_000) { noisy(126f, it, 1f) }
        assertNull(h.band.sourceUnavailable().near)
        h.run(10_000) { noisy(100f, it, 1f) }
        assertEquals(false, h.state.near)
        assertEquals(0, h.approaches)
    }

    @Test fun timerTicksStaleValuesAndDenseCallbackBurstsCannotManufactureEvidence() {
        val h = Replay(100f, 126f)
        h.run(20_000) { noisy(100f, it, 1f) }
        repeat(10_000) { h.band.observe(126f, h.now, fresh = true) }
        repeat(100) { h.now += 100; h.state = h.band.tick(h.now) }
        assertEquals(false, h.state.near)
        repeat(100) { h.now += 100; h.state = h.band.observe(126f, h.now, fresh = false) }
        assertEquals(false, h.state.near)
        assertEquals(0, h.approaches)
        h.run(10_000) { noisy(126f, it, 1f) }
        assertEquals(true, h.state.near)
        assertEquals(1, h.approaches)
    }

    @Test fun registrationAndHistoricalSamplesCanEstablishStateButNeverAnApproach() {
        val h = Replay(100f, 126f)
        h.run(20_000) { noisy(100f, it, 1f) }
        repeat(40) {
            h.now += 250
            h.state = h.band.observe(126f, h.now, live = false)
            assertFalse(h.state.approach)
        }
        assertEquals(true, h.state.near)
    }

    @Test fun explicitlyTrustedSparsePlateauSettlesWithoutCountingTimerTicksAsSamples() {
        val band = ProximityPresenceBand(100f, 126f)
        band.observe(100f, 0)
        assertNull(band.tick(4_000).near)
        assertEquals(false, band.tick(4_000, heldEvidence = true).near)
        val before = band.snapshot().quiet
        band.observe(126f, 10_000)
        assertTrue(band.needsTick())
        assertEquals(false, band.tick(13_999, heldEvidence = true).near)
        val entered = band.tick(14_000, heldEvidence = true)
        assertEquals(true, entered.near)
        assertTrue(entered.approach)
        assertFalse(band.needsTick())
        assertEquals(before, entered.quiet)
    }

    @Test fun slowerFreshAcquisitionsExtendTheBlockWithoutInventingSamples() {
        for (cadence in listOf(1_000L, 2_000L)) {
            val band = ProximityPresenceBand(100f, 126f)
            var now = 0L
            repeat(21) { band.observe(100f + it % 3, now); now += cadence }
            assertEquals(false, band.snapshot().near)
            var approaches = 0
            repeat(21) { if (band.observe(126f + it % 3, now).approach) approaches++; now += cadence }
            assertEquals(true, band.snapshot().near)
            assertEquals(1, approaches)
        }
    }

    @Test fun aNoiselessStepRemainsIndistinguishableFromAnArrival() {
        val band = ProximityPresenceBand(100f, 126f)
        for (i in 0..40) band.observe(100f, i * 250L)
        assertEquals(false, band.snapshot().near)
        for (i in 41..80) band.observe(101f, i * 250L)
        assertEquals(true, band.snapshot().near)
        assertEquals(100.0, band.snapshot().quiet.mean, .0001)
    }

    @Test fun unchangedBootPlateauNeverManufacturesAnArrivalAsBootstrapVarianceShrinks() {
        for (savedSpan in listOf(25f, 400f, 40_000f)) {
            val band = ProximityPresenceBand(700f, 700f + savedSpan)
            assertNull(band.learnedQuiet())
            var approaches = 0
            repeat(121) {
                if (band.observe(720f, it * 250L, live = it > 0).approach) approaches++
            }
            assertEquals(true, band.snapshot().near)
            assertEquals(0, approaches)
            assertNotNull(band.learnedQuiet())
            assertEquals(700.0, band.snapshot().quiet.mean, .0001)
        }
    }

    @Test fun measuredDepartureClearsAfterOccupiedDriftWithoutAbsorbingAPartialRetreat() {
        for (scale in listOf(1f, .01f, -1f)) {
            val h = Replay(974f * scale, 1674f * scale)
            h.run(10_000) { noisy(974f, it, .7f) * scale }
            assertEquals(false, h.state.near)
            val quiet = h.state.quiet
            h.run(10_000) { noisy(1674f, it, .7f) * scale }
            assertEquals(true, h.state.near)
            assertEquals(1, h.approaches)
            h.run(1_800_000) { noisy(1674f + 285f * it / 1_800_000f, it, .7f) * scale }
            assertEquals(true, h.state.near)
            assertEquals(quiet, h.state.quiet)
            h.run(10_000) { noisy(1674f + 285f - 300f, it, .7f) * scale }
            assertEquals("a partial retreat must remain occupied", true, h.state.near)
            h.run(10_000) { noisy(974f + 285f, it, .7f) * scale }
            assertEquals(false, h.state.near)
            h.run(10_000) { noisy(1674f + 285f, it, .7f) * scale }
            assertEquals(true, h.state.near)
            assertEquals(2, h.approaches)
        }
    }

    @Test fun aWeakMeasuredEntranceStillClearsAfterLongOccupiedDrift() {
        val h = Replay(700f, 1_100f)
        h.run(10_000) { noisy(700f, it, .15f) }
        h.run(10_000) { noisy(720f, it, .15f) }
        assertEquals(true, h.state.near)
        h.run(1_800_000) { noisy(720f + 285f * it / 1_800_000f, it, .15f) }
        assertEquals(true, h.state.near)
        h.run(10_000) { noisy(985f, it, .15f) }
        assertEquals(false, h.state.near)
        h.run(10_000) { noisy(1_005f, it, .15f) }
        assertEquals(true, h.state.near)
        assertEquals(2, h.approaches)
    }

    @Test fun aLargeHandExcursionCannotRaiseTheOccupiedReferenceAndClearOnWithdrawal() {
        val h = Replay(700f, 720f)
        h.run(10_000) { noisy(700f, it, .15f) }
        h.run(10_000) { noisy(720f, it, .15f) }
        assertEquals(true, h.state.near)
        h.run(10_000) { noisy(1_400f, it, .15f) }
        assertEquals(true, h.state.near)
        h.run(10_000) { noisy(720f, it, .15f) }
        assertEquals(true, h.state.near)
        assertEquals(1, h.approaches)
    }

    @Test fun anUnmeasuredHeldOffsetCannotCreateAnArrivalWhenFreshNoiseBecomesKnown() {
        val band = ProximityPresenceBand(700f, 1_100f)
        band.observe(720f, 0L, live = false)
        assertNull(band.tick(4_000L, heldEvidence = true).near)
        var approaches = 0
        for (i in 17..160) if (band.observe(720f, i * 250L).approach) approaches++
        assertEquals(true, band.snapshot().near)
        assertEquals(0, approaches)
    }

    @Test fun quietVariationBetweenBlocksDoesNotBecomeAnArrivalAfterColdStartup() {
        for (scale in listOf(1f, .001f, -1f)) {
            val band = ProximityPresenceBand(100f * scale, 500f * scale)
            var now = 0L
            var approaches = 0
            fun sample(mean: Float, count: Int) {
                repeat(count) {
                    now += 250
                    val state = band.observe((mean + if (it % 2 == 0) -.5f else .5f) * scale, now)
                    if (state.approach) approaches++
                }
            }
            // Each block has little jitter, but the empty-room process varies between blocks.
            for (mean in listOf(100f, 98f, 96f, 96f, 104f, 104f, 102f, 100f)) sample(mean, 8)
            sample(100f, 40)
            assertEquals(false, band.snapshot().near)
            assertEquals(0, approaches)
            sample(120f, 40)
            assertEquals(true, band.snapshot().near)
            assertEquals(1, approaches)
        }
    }

    @Test fun sustainedWiderNoiseAroundTheSameClearMeanCanBeLearnedWithoutOccupancy() {
        for (scale in listOf(1f, .001f, -1f)) {
            val h = Replay(700f * scale, 720f * scale)
            h.run(20_000) { 700f * scale }
            assertEquals(false, h.state.near)
            h.run(120_000) { (if (it / 250 % 2 == 0L) 690f else 710f) * scale }
            assertEquals(false, h.state.near)
            assertEquals(0, h.approaches)
            assertTrue(h.state.quiet.variance > 50 * scale * scale)
            h.run(10_000) { (720f + if (it / 250 % 2 == 0L) -10f else 10f) * scale }
            // This twenty-count shift is close to the wider process's statistical uncertainty;
            // a clearly separated shift still confirms normally after variance expansion.
            h.run(10_000) { (740f + if (it / 250 % 2 == 0L) -10f else 10f) * scale }
            assertEquals(true, h.state.near)
            assertEquals(1, h.approaches)
        }
        val flat = ProximityPresenceBand.QuietBand(700.0, 0.0, 8)
        val widened = ProximityPresenceBand.QuietBand(700.0, 100.0, 8)
        assertTrue(ProximityPresenceBand.meansWithin(flat, widened))
        assertFalse(ProximityPresenceBand.within(flat, widened))
    }

    @Test fun occupiedNoiseStaysFrozenButANoisierReturnToClearCanRelease() {
        val h = Replay(700f, 740f)
        h.run(20_000) { 700f }
        h.run(2_000) { if (it / 250 % 2 == 0L) 690f else 710f }
        h.run(4_000) { 700f }
        assertEquals(0.0, h.state.quiet.variance, .0001)
        h.run(10_000) { 740f }
        assertEquals(true, h.state.near)
        h.run(20_000) { if (it / 250 % 2 == 0L) 730f else 750f }
        assertEquals(true, h.state.near)
        assertEquals(0.0, h.state.quiet.variance, .0001)
        h.run(4_000) { if (it / 250 % 2 == 0L) 690f else 710f }
        assertEquals(true, h.state.near)
        assertEquals(0.0, h.state.quiet.variance, .0001)
        h.run(6_000) { if (it / 250 % 2 == 0L) 690f else 710f }
        assertEquals(false, h.state.near)
        assertTrue(h.state.quiet.variance > 50)
        assertEquals(1, h.approaches)
    }

    @Test fun widerOccupiedVarianceCannotExcuseAnUnchangedOccupiedMean() {
        for (scale in listOf(1f, .001f, -1f)) {
            val h = Replay(700f * scale, 1_100f * scale)
            h.run(30_000) { (700f + if (it / 250 % 2 == 0L) -1f else 1f) * scale }
            assertEquals(false, h.state.near)
            h.run(10_000) { (740f + if (it / 250 % 2 == 0L) -1f else 1f) * scale }
            assertEquals(true, h.state.near)
            val quiet = h.state.quiet
            h.run(30_000) { (740f + if (it / 250 % 2 == 0L) -100f else 100f) * scale }
            assertEquals(true, h.state.near)
            assertEquals(quiet, h.state.quiet)
            h.run(10_000) { (700f + if (it / 250 % 2 == 0L) -10f else 10f) * scale }
            assertEquals(false, h.state.near)
            assertTrue(h.state.quiet.variance > 50 * scale * scale)
            assertEquals(1, h.approaches)
        }
        assertFalse(ProximityPresenceBand.meanWithinQuiet(
            ProximityPresenceBand.QuietBand(700.0, 1.0, 8),
            ProximityPresenceBand.QuietBand(740.0, 10_000.0, 8),
        ))
    }

    private class Replay(clear: Float, near: Float) {
        val band = ProximityPresenceBand(clear, near)
        var now = 0L
        var state = band.snapshot()
        var approaches = 0
        fun run(duration: Long, sample: (Long) -> Float) {
            var elapsed = 0L
            while (elapsed < duration) {
                now += 250
                elapsed += 250
                state = band.observe(sample(elapsed), now)
                if (state.approach) approaches++
            }
        }
    }

    companion object {
        private val noise = floatArrayOf(-15f, -7f, 0f, 5f, 15f, 4f, -4f, 2f)
    private fun noisy(mean: Float, time: Long, scale: Float = 1f): Float =
            mean + noise[((time / 250) % noise.size).toInt()] * scale
    }
}
