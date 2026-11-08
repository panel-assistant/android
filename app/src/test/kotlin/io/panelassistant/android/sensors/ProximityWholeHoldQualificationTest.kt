package io.panelassistant.android.sensors

import io.panelassistant.android.sensors.ProximityCalibrationEngine.*
import io.panelassistant.android.sensors.ProximityCalibrationEngine.Companion.CAPTURE_HOLD_MS
import io.panelassistant.android.sensors.ProximityCalibrationEngine.Companion.COUNTDOWN_MS
import org.junit.Assert.*
import org.junit.Test

/** A calibration hold keeps its original deadline across clear-band samples. */
class ProximityWholeHoldQualificationTest {
    @Test fun aClearBandSampleDoesNotRestartOrAbandonAStartedHold() {
        for (scale in listOf(1f, -1f, .001f)) for (finishOnTick in listOf(false, true)) {
            val h = Hold(scale)
            val started = h.startNear()
            h.observe(8f, started)
            assertEquals(Cue.HOLD, h.state.cue)
            assertEquals(CAPTURE_HOLD_MS, h.state.cueRemainingMs)
            for (i in 1..19) {
                // A real clear-band observation halfway through the same otherwise separated hold.
                h.observe(if (i == 10) 2f else 8f, started + i * 250)
                assertEquals(Stage.NEAR, h.state.stage)
                assertEquals(Cue.HOLD, h.state.cue)
                assertEquals(CAPTURE_HOLD_MS - i * 250, h.state.cueRemainingMs)
                assertEquals(h.original, h.state.calibration)
                assertEquals(0, h.writes)
            }
            if (finishOnTick) h.engine.tick(started + CAPTURE_HOLD_MS)
            else h.observe(8f, started + CAPTURE_HOLD_MS)
            assertEquals(Stage.RETURN_CLEAR, h.state.stage)
            assertEquals(CapabilityStatus.NOT_TESTED, h.state.presenceStatus)
            assertEquals(h.original, h.state.calibration)
            assertEquals(0, h.writes)
        }
    }

    @Test fun aStartedHoldThatNeverSeparatesEndsAsNotDetectedAtItsOriginalDeadline() {
        for (scale in listOf(1f, -1f, .001f)) for (finishOnTick in listOf(false, true)) {
            val h = Hold(scale)
            val started = h.startNear()
            h.observe(8f, started)
            for (i in 1..19) {
                // One excursion starts no independently verified capability; the whole hold is clear.
                h.observe(2f, started + i * 250)
                assertEquals(Stage.NEAR, h.state.stage)
                assertEquals(Cue.HOLD, h.state.cue)
                assertEquals(CAPTURE_HOLD_MS - i * 250, h.state.cueRemainingMs)
                assertEquals(0, h.writes)
            }
            if (finishOnTick) h.engine.tick(started + CAPTURE_HOLD_MS)
            else h.observe(2f, started + CAPTURE_HOLD_MS)
            assertEquals(Stage.WAVE_BASELINE, h.state.stage)
            assertEquals(CapabilityStatus.NOT_DETECTED, h.state.presenceStatus)
            assertFalse(h.state.presenceSupported)
            assertFalse(h.state.canSave)
            assertEquals(h.original, h.state.calibration)
            assertEquals(0, h.writes)
        }
    }

    private class Hold(private val scale: Float) {
        val original = Calibration(mode = Mode.RANGED, clearRaw = 100f * scale, nearRaw = 200f * scale)
        var writes = 0
        val engine = ProximityCalibrationEngine(initial = original, observedSourceMode = Mode.RANGED,
            commit = { writes++; true })
        val state get() = engine.current()
        fun observe(raw: Float, now: Long) = engine.observe(raw * scale, now)
        fun startNear(): Long {
            engine.start(0)
            observe(2f, 0)
            engine.action("begin", 0)
            for (i in 0..20) observe(2f, COUNTDOWN_MS + i * 250)
            assertEquals(Stage.NEAR, state.stage)
            return COUNTDOWN_MS + CAPTURE_HOLD_MS + COUNTDOWN_MS
        }
    }
}
