package io.panelassistant.android.sensors

import org.junit.Assert.*
import org.junit.Test

class ProximitySteadyNoiseReplayTest {
    @Test fun aSustainedShiftSmallerThanTheSavedSpanIsDetectedAcrossRangedUnitsAndPolarities() {
        for (recording in listOf(noiseA, noiseB)) for (scale in listOf(1f, .001f, -1f)) {
            val clear = recording.average().toFloat() * scale
            val engine = ProximityCalibrationEngine(ProximityCalibrationEngine.Calibration(
                mode = ProximityCalibrationEngine.Mode.RANGED, clearRaw = clear, nearRaw = clear + 400 * scale,
            ))
            var now = 0L
            fun replay(shift: Float): List<ProximityCalibrationEngine.Result> = recording.map { value ->
                now += 250
                engine.observe((value + shift) * scale, now)
            }
            val quiet = replay(0f)
            assertEquals(false, quiet.last().near)
            assertTrue(quiet.none { it.presenceApproach })
            // Controlled transformations of recorded ambient noise, not labels of physical occupancy.
            val approached = replay(20f)
            assertEquals("scale=$scale", true, approached.last().near)
            assertEquals(1, approached.count { it.presenceApproach })
            val held = replay(20f)
            assertEquals(true, held.last().near)
            assertTrue(held.none { it.presenceApproach })
            assertEquals(false, replay(0f).last().near)
            assertEquals(1, replay(20f).count { it.presenceApproach })
        }
    }

    @Test fun repeatedCachedReadingsAndTicksCannotVerifyAnApproach() {
        val engine = ProximityCalibrationEngine(ProximityCalibrationEngine.Calibration(
            mode = ProximityCalibrationEngine.Mode.RANGED, clearRaw = 700f, nearRaw = 1100f,
        ))
        for (i in 0..40) engine.observe(700f + i % 3, i * 250L)
        for (i in 41..80) {
            val state = engine.observe(1000f, i * 250L, live = false, calibrationLive = false)
            assertFalse(state.presenceApproach)
            assertEquals(false, state.near)
            assertFalse(engine.tick(i * 250L).presenceApproach)
        }
    }

    @Test fun aCalibratedWaveRetainsItsFastEntryWhilePresenceRequiresASustainedShift() {
        val wave = ProximityCalibrationEngine.WaveCalibration(clearRaw = 700f, nearRaw = 1400f)
        val engine = ProximityCalibrationEngine(ProximityCalibrationEngine.Calibration(
            mode = ProximityCalibrationEngine.Mode.RANGED, clearRaw = 700f, nearRaw = 750f, wave = wave,
        ))
        for (i in 0..40) engine.observe(700f + i % 3, i * 250L)
        engine.observe(1400f, 10_500)
        val pulse = engine.tick(10_700)
        assertTrue(pulse.gesture)
        assertFalse(pulse.presenceApproach)
        assertEquals(false, pulse.near)
        engine.observe(700f, 10_800)
        assertFalse(engine.tick(11_000).gesture)
    }

    @Test fun uncalibratedAndWaveOnlyPanelsKeepAmbientNoiseThroughABriefSetup() {
        val waveOnly = ProximityCalibrationEngine.Calibration(
            mode = ProximityCalibrationEngine.Mode.RANGED, clearRaw = 700f, nearRaw = 750f,
            presenceSupported = false,
            wave = ProximityCalibrationEngine.WaveCalibration(clearRaw = 700f, nearRaw = 1400f),
        )
        val outcomes = mutableListOf<Pair<ProximityCalibrationEngine.Stage?, ProximityCalibrationEngine.CapabilityStatus>>()
        for (flatFirst in listOf(false, true)) for (initial in listOf(null, waveOnly)) {
            val engine = ProximityCalibrationEngine(initial)
            var now = 0L
            fun noisyHold(mean: Float, duration: Long) {
                repeat((duration / 250).toInt()) { index ->
                    now += 250
                    engine.observe(mean + if (index % 2 == 0) -10f else 10f, now)
                }
            }
            if (flatFirst) repeat(80) { now += 250; engine.observe(700f, now) }
            noisyHold(700f, if (flatFirst) 120_000 else 20_000)
            noisyHold(740f, 60_000)
            noisyHold(700f, 10_000)
            engine.start(now)
            engine.action("begin", now)
            fun capture(raw: Float) {
                now += ProximityCalibrationEngine.COUNTDOWN_MS
                engine.observe(raw, now)
                repeat((ProximityCalibrationEngine.CAPTURE_HOLD_MS / 250).toInt()) {
                    now += 250
                    engine.observe(raw, now)
                }
            }
            // A brief flat setup cannot erase the continuously learned ambient variation.
            capture(700f)
            assertEquals(ProximityCalibrationEngine.Stage.NEAR, engine.current().stage)
            capture(705f)
            outcomes += engine.current().stage to engine.current().presenceStatus
        }
        assertEquals(
            List(4) { ProximityCalibrationEngine.Stage.WAVE_BASELINE to ProximityCalibrationEngine.CapabilityStatus.NOT_DETECTED },
            outcomes,
        )
    }

    companion object {
        // Two 60-second raw ambient snapshot streams. Occupancy was not labelled. Replay assigns
        // a 250ms acquisition cadence and explicit synthetic shifts; it does not certify a room clear.
        private val noiseA = intArrayOf(
            701, 702, 702, 702, 701, 703, 701, 701, 701, 702, 700, 700, 699, 698, 698, 697, 699, 696, 696, 697, 698, 698, 697, 696, 697,
            697, 696, 696, 696, 698, 697, 697, 697, 699, 701, 703, 702, 704, 706, 706, 706, 706, 708, 706, 708, 708, 707, 705, 705, 706,
            705, 706, 705, 704, 705, 704, 704, 705, 708, 705, 705, 704, 703, 706, 706, 704, 704, 705, 706, 705, 705, 703, 705, 706, 703,
            704, 705, 703, 702, 702, 704, 702, 702, 702, 701, 701, 701, 703, 702, 702, 703, 703, 703, 702, 704, 702, 703, 703, 703, 702,
            706, 704, 705, 706, 704, 704, 703, 704, 703, 704, 704, 703, 704, 703, 703, 703, 702, 702, 705, 703, 702, 704, 704, 704, 704,
            703, 704, 702, 703, 705, 702, 703, 702, 702, 703, 703, 702, 702, 701, 702, 702, 703, 703, 705, 703, 703, 703, 702, 703, 703,
            699, 702, 703, 702, 702, 703, 703, 702, 701, 703, 701, 702, 703, 702, 704, 702, 703, 702, 705, 705, 702, 702, 703, 705, 703,
            700, 699, 700, 699, 698, 700, 700, 699, 700, 701, 699, 702, 702, 699, 700, 700, 701, 702, 702, 701, 703, 704, 703, 705, 702,
            705, 703, 700, 704, 702, 701, 702, 702, 702, 703, 702, 703, 705, 705, 705, 705, 703, 702, 704, 702, 700, 703, 703, 701, 702,
            701, 704, 701, 702, 703, 705, 704, 704, 702, 705, 706, 701, 704, 705, 702, 704, 705, 704, 703, 703, 702, 703, 703, 703, 704,
            703, 703, 703, 700, 701, 702, 704, 701, 703, 703, 703, 702, 703, 702, 701, 705, 703, 704, 705, 703, 703, 703, 702, 701, 700,
            700, 701, 701, 700, 701, 701, 701, 699, 699, 700, 700, 701, 699, 701, 700, 701, 701, 701, 700, 702, 703, 702, 703, 700, 700,
        )
        private val noiseB = intArrayOf(
            843, 842, 843, 847, 845, 847, 844, 843, 843, 846, 847, 846, 845, 843, 844, 844, 847, 842, 843, 843, 845, 846, 846, 844, 844,
            843, 843, 844, 846, 844, 843, 844, 843, 844, 845, 845, 842, 842, 842, 842, 843, 844, 844, 841, 842, 841, 843, 844, 846, 844,
            843, 842, 843, 845, 845, 843, 843, 845, 843, 843, 843, 843, 843, 844, 843, 844, 844, 844, 845, 843, 843, 842, 843, 842, 844,
            841, 844, 844, 844, 846, 843, 843, 845, 843, 843, 842, 845, 843, 843, 844, 844, 846, 845, 844, 845, 843, 844, 844, 845, 846,
            845, 845, 842, 842, 843, 843, 841, 843, 843, 842, 843, 843, 843, 843, 844, 843, 843, 844, 844, 843, 842, 842, 844, 843, 842,
            844, 843, 842, 843, 843, 843, 843, 841, 842, 843, 841, 843, 843, 844, 843, 843, 841, 842, 843, 843, 843, 843, 843, 843, 842,
            842, 843, 842, 842, 843, 843, 842, 842, 843, 842, 844, 843, 841, 842, 845, 843, 845, 845, 845, 846, 844, 841, 843, 843, 844,
            841, 843, 842, 844, 843, 845, 845, 846, 846, 843, 843, 846, 846, 842, 842, 844, 843, 842, 842, 843, 843, 842, 841, 842, 843,
            843, 843, 843, 842, 845, 844, 845, 845, 844, 845, 843, 844, 845, 843, 844, 844, 845, 844, 843, 845, 845, 845, 844, 844, 842,
            843, 843, 843, 844, 843, 844, 843, 846, 845, 844, 844, 842, 843, 843, 842, 843, 843, 843, 843, 843, 842, 844, 845, 847, 845,
            845, 843, 846, 845, 846, 842, 843, 842, 842, 842, 843, 843, 844, 844, 843, 842, 843, 843, 843, 843, 843, 843, 843, 843, 842,
            843, 843, 843, 843, 843, 843, 842, 842, 842, 842, 842, 842, 844, 843, 846, 843, 842, 843, 844, 842, 843, 843, 842, 842, 841,
        )
    }
}
