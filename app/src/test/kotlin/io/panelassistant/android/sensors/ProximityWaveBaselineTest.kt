package io.panelassistant.android.sensors

import io.panelassistant.android.sensors.ProximityCalibrationEngine.*
import io.panelassistant.android.sensors.ProximityCalibrationEngine.Companion.CAPTURE_HOLD_MS
import io.panelassistant.android.sensors.ProximityCalibrationEngine.Companion.COUNTDOWN_MS
import org.junit.Assert.*
import org.junit.Test

class ProximityWaveBaselineTest {
    @Test fun aStrongExampleStillAcceptsSmallerWavesAboveStandingNoise() {
        for (scale in listOf(1f, -1f, .001f)) for (peak in listOf(2121f, 60_000f)) {
            val h = Journey(scale)
            h.teach(peak)
            // Room, standing-person noise, the drift band and wrong-polarity movements are not waves.
            for (value in listOf(35f, 58f, 62f, 160f, -200f)) h.pulse(value)
            h.pulse(250f, duration = 100)
            h.pulse(250f, duration = 5_000)
            assertEquals(0, h.state.accepted)
            for ((index, value) in listOf(505f, 284f, 250f).withIndex()) {
                h.pulse(value)
                assertEquals("scale=$scale example=$peak wave=$value", index + 1, h.state.accepted)
                if (index == 0) {
                    h.pulse(250f, clearMs = 0)
                    assertEquals("A withdrawal must rearm before another wave", 1, h.state.accepted)
                }
            }
            assertEquals(Stage.REVIEW, h.state.stage)
            assertTrue(h.state.presenceSupported)
            assertTrue(h.state.waveSupported)
            assertEquals(0, h.writes)
            h.engine.action("save", h.now)
            assertEquals(1, h.writes)
            assertEquals(peak * scale, h.state.calibration!!.wave!!.nearRaw, 0f)

            // In use, the smaller hand wave wakes on entry, without requiring withdrawal first.
            for (value in listOf(35f, 60f, 160f)) assertFalse(h.hold(value, 1_000).gesture)
            h.hold(60f, 800)
            assertTrue(h.hold(250f, 400).gesture)
            assertFalse(h.hold(250f, 5_000).gesture)
        }
    }

    @Test fun smallerDoubleWavesStillRequireTwoQualifiedMovements() {
        val h = Journey(1f, WavePattern.DOUBLE)
        h.teach(2121f)
        repeat(3) { index ->
            h.pulse(250f)
            assertEquals(index, h.state.accepted)
            h.pulse(284f)
            assertEquals(index + 1, h.state.accepted)
        }
        assertEquals(Stage.REVIEW, h.state.stage)
        assertTrue(h.state.waveSupported)
        h.engine.action("save", h.now)
        assertEquals(1, h.writes)
        assertFalse(h.pulse(250f).gesture)
        assertTrue(h.pulse(284f).gesture)
    }

    @Test fun measuredStandingNoiseDoesNotBecomeAHandWaveWithoutADriftFloor() {
        val h = Journey(1f, drift = 0f, baselineNoise = 5f)
        h.teach(2121f)
        for (value in listOf(35f, 55f, 65f, 70f, 80f)) h.pulse(value)
        assertEquals("Noise and its hysteresis envelope must not count", 0, h.state.accepted)
        h.pulse(100f)
        assertEquals("A hand above that envelope is independent of the example peak", 1, h.state.accepted)
        h.engine.action("cancel", h.now)
        assertNull(h.state.calibration)
        assertEquals(0, h.writes)
    }

    private class Journey(private val scale: Float, private val pattern: WavePattern = WavePattern.SINGLE,
        drift: Float = 100f, private val baselineNoise: Float = 0f) {
        var now = 1_000L
        var writes = 0
        private val baseline = if (pattern == WavePattern.DOUBLE) 35f else 60f
        val engine = ProximityCalibrationEngine(observedSourceMode = Mode.RANGED,
            requestedWavePattern = pattern, driftMargin = drift * kotlin.math.abs(scale),
            driftDeclaredRange = 9f * kotlin.math.abs(scale), commit = { writes++; true })
        val state get() = engine.current()
        private fun observe(raw: Float) = engine.observe(raw * scale, now, live = true, calibrationLive = true)
        fun hold(raw: Float, duration: Long): Result {
            var result = observe(raw)
            var gesture = result.gesture
            var remaining = duration
            while (remaining > 0) {
                val step = minOf(50L, remaining)
                now += step; remaining -= step
                gesture = engine.tick(now).gesture || gesture
                result = observe(raw)
                gesture = result.gesture || gesture
            }
            return result.copy(gesture = gesture)
        }
        private fun capture(raw: Float) {
            now += COUNTDOWN_MS
            if (state.stage == Stage.WAVE_BASELINE && baselineNoise > 0f) {
                observe(raw)
                repeat((CAPTURE_HOLD_MS / 250).toInt()) {
                    now += 250
                    engine.tick(now)
                    observe(raw + if (it % 2 == 0) baselineNoise else -baselineNoise)
                }
            } else hold(raw, CAPTURE_HOLD_MS)
        }
        fun teach(peak: Float) {
            engine.start(now); observe(35f); engine.action("begin", now)
            capture(35f); capture(60f); capture(35f)
            assertEquals(Stage.WAVE_BASELINE, state.stage)
            capture(baseline)
            assertEquals(Stage.WAVE_CAPTURE, state.stage)
            now += COUNTDOWN_MS
            pulse(peak)
            assertEquals(Stage.WAVES, state.stage)
            hold(baseline, COUNTDOWN_MS)
        }
        fun pulse(raw: Float, duration: Long = 400, clearMs: Long = 800): Result {
            hold(baseline, clearMs)
            val entry = hold(raw, duration)
            val release = hold(baseline, 200)
            return release.copy(gesture = entry.gesture || release.gesture)
        }
    }
}
