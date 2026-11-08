package io.panelassistant.android.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityTouchLearnerTest {
    private fun replay(name: String, learner: ProximityTouchLearner = ProximityTouchLearner(), offset: Long = 0L): ProximityTouchLearner {
        for ((at, raw) in ProximityTouchReplay.load(name).events()) {
            if (raw == null) learner.touch(at + offset) else learner.observe(raw, at + offset)
        }
        return learner
    }

    @Test fun x2iTapsLearnClearPresentAndHandEvenStartingWithSomeonePresent() {
        // The capture opens with someone at the desk (~636); clear must still be the empty room (~617).
        val model = checkNotNull(replay("x2i-taps.csv").model())
        assertEquals(617.5, model.clear.mean, 2.5)
        assertTrue("present ${model.presentRaw}", model.presentRaw in 632f..652f)
        assertTrue("hand ${model.waveRaw}", (model.waveRaw ?: 0f) > 1_000f)
        assertTrue(model.nearIsHigher)
        assertEquals(3, model.seenTouches)
        assertEquals(0, model.unseenTouches)
    }

    @Test fun officePanelTapsLearnTheSameShapeInItsOwnUnits() {
        val model = checkNotNull(replay("office-taps.csv").model())
        assertEquals(37.0, model.clear.mean, 2.0)
        assertTrue("present ${model.presentRaw}", model.presentRaw in 54f..63f)
        assertTrue("hand ${model.waveRaw}", (model.waveRaw ?: 0f) > 200f)
        assertTrue(model.seenTouches >= 3)
    }

    @Test fun oneTouchIsNotEnoughToCalibrate() {
        assertNull(replay("x2i-attended.csv").model())
    }

    @Test fun invertedPolarityIsLearnedFromTheTouchesNotAssumed() {
        val learner = ProximityTouchLearner()
        for ((at, raw) in ProximityTouchReplay.load("office-taps.csv").events()) {
            if (raw == null) learner.touch(at) else learner.observe(1_000f - raw, at)
        }
        val model = checkNotNull(learner.model())
        assertEquals(963.0, model.clear.mean, 2.0)
        assertTrue(!model.nearIsHigher)
        assertTrue((model.waveRaw ?: 1_000f) < 800f)
    }

    @Test fun touchesSoonAfterASensorCausedWakeAreExcluded() {
        val replay = ProximityTouchReplay.load("x2i-taps.csv")
        val learner = ProximityTouchLearner()
        replay.touches.forEach { learner.exclude(it - 2_000, it + ProximityTouchLearner.WAVE_WAKE_EXCLUSION_MS) }
        for ((at, raw) in replay.events()) if (raw == null) learner.touch(at) else learner.observe(raw, at)
        assertNull(learner.model())
    }

    @Test fun touchesASensorCannotSeeCountAgainstItAndAreNeverForcedIntoAThreshold() {
        val learner = ProximityTouchLearner()
        var now = 0L
        fun hold(raw: Float, ms: Long) { val end = now + ms; while (now < end) { learner.observe(raw + (now / 250 % 3), now); now += 250 } }
        hold(500f, 40_000)
        repeat(5) { hold(500f, 6_000); learner.touch(now); hold(500f, 20_000) }
        assertNull(learner.model())
    }

    @Test fun durableStateRestoresTheModelAfterARestart() {
        val learned = replay("x2i-taps.csv")
        val restored = ProximityTouchLearner().apply { restore(learned.toJson(), 0L) }
        val model = checkNotNull(restored.model())
        assertEquals(learned.model()!!.presentRaw, model.presentRaw, .01f)
        assertEquals(learned.model()!!.clear.mean, model.clear.mean, .01)
    }

    @Test fun aHybridSeedIsRefinedByTouchesRatherThanReplacedAtOnce() {
        val seed = ProximityTouchLearner.Seed(clearRaw = 617f, presentRaw = 700f, waveRaw = 1_400f)
        val seeded = ProximityTouchLearner(seed)
        var now = 0L
        repeat(40) { seeded.observe(617f + it % 2, now); now += 250 }
        assertEquals(700f, checkNotNull(seeded.model()).presentRaw, .01f)
        val refined = replay("x2i-taps.csv", seeded, offset = now + 10_000).model()!!
        // Two seed votes against three touches: the median now sits on the touch-learned level.
        assertTrue("present ${refined.presentRaw}", refined.presentRaw < 700f)
    }
}
