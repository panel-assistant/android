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

    @Test fun anAttendedRawCountApproachCanBeSetUpAndClearsAgain() {
        replayAttendedApproach(attendedA, driftMargin = 300f)
    }

    @Test fun anAttendedRangedApproachCanBeSetUpAfterTheInitialClearReadingSettles() {
        // The initial operator-confirmed clear stream decays from 59 to 35 counts during
        // preparation. Keep that transient in setup; the normal countdown precedes capture.
        // Operational replay starts with the later returned-clear excerpt, then reuses the
        // earlier near excerpt. That ordering is a software scenario, not another physical visit.
        replayAttendedApproach(attendedB, driftMargin = 100f, declaredRange = 9f, operationalClear = attendedB[2])
    }

    private fun replayAttendedApproach(recording: List<IntArray>, driftMargin: Float, declaredRange: Float = 0f,
        operationalClear: IntArray = recording[0]) {
        val saved = mutableListOf<ProximityCalibrationEngine.Calibration>()
        val engine = ProximityCalibrationEngine(
            observedSourceMode = ProximityCalibrationEngine.Mode.RANGED,
            driftMargin = driftMargin, driftDeclaredRange = declaredRange,
            commit = { saved.add(it); true },
        )
        var now = 0L
        fun replay(samples: IntArray, until: ProximityCalibrationEngine.Stage? = null): List<ProximityCalibrationEngine.Result> {
            val started = now + 400L
            val results = mutableListOf<ProximityCalibrationEngine.Result>()
            for (index in samples.indices step 2) {
                now = started + samples[index]
                val result = engine.observe(samples[index + 1].toFloat(), now)
                assertFalse(result.gesture)
                results += result
                if (until != null && result.stage == until) break
            }
            return results
        }
        engine.start(now)
        engine.action("begin", now)
        assertEquals(ProximityCalibrationEngine.Stage.NEAR, replay(recording[0], ProximityCalibrationEngine.Stage.NEAR).last().stage)
        assertEquals(ProximityCalibrationEngine.Stage.RETURN_CLEAR, replay(recording[1], ProximityCalibrationEngine.Stage.RETURN_CLEAR).last().stage)
        val verified = replay(recording[2], ProximityCalibrationEngine.Stage.WAVE_BASELINE).last()
        assertEquals(ProximityCalibrationEngine.Stage.WAVE_BASELINE, verified.stage)
        assertEquals(ProximityCalibrationEngine.CapabilityStatus.VALIDATED, verified.presenceStatus)
        assertTrue(saved.isEmpty())

        // No hand wave was recorded. Let that separate wizard step expire before saving presence.
        now += ProximityCalibrationEngine.WAVE_PHASE_TIMEOUT_MS
        assertEquals(ProximityCalibrationEngine.Stage.REVIEW, engine.tick(now).stage)
        assertEquals(ProximityCalibrationEngine.Stage.SAVED, engine.action("save", now).stage)
        assertEquals(1, saved.size)
        assertTrue(saved.single().presenceSupported)
        assertNull(saved.single().wave)

        // Replay the same labelled excerpts operationally; this is a second software replay,
        // not a second physical visit or a claim about the panel's installed runtime.
        val quiet = replay(operationalClear)
        assertEquals(false, quiet.last().near)
        assertTrue(quiet.none { it.presenceApproach })
        val occupied = replay(recording[1])
        assertEquals(true, occupied.last().near)
        assertEquals(1, occupied.count { it.presenceApproach })
        assertTrue(occupied.dropWhile { it.near != true }.all { it.near == true })
        val returned = replay(recording[2])
        assertEquals(false, returned.last().near)
        assertTrue(returned.none { it.presenceApproach })
        assertEquals(1, saved.size)
    }

    companion object {
        // Operator-confirmed clear, near and returned-clear snapshot excerpts. Each begins
        // two seconds after confirmation and covers the next twenty seconds, before the
        // next movement request. Every other ~5 Hz HTTP snapshot is retained, with relative host request
        // milliseconds followed by its unchanged raw count. Host polling does not prove sensor
        // acquisition times: this replay explicitly treats each retained snapshot as fresh.
        // Phases are joined at a 400ms gap; instruction/delivery latency is not replayed.
        private val attendedA = listOf(
            intArrayOf(
                0, 609, 398, 609, 797, 608, 1200, 608, 1598, 608, 2002, 608, 2399, 608, 2799, 609,
                3197, 609, 3597, 607, 3997, 607, 4397, 609, 4800, 609, 5200, 610, 5599, 610, 5999, 612,
                6397, 613, 6799, 613, 7198, 611, 7598, 612, 8001, 609, 8401, 609, 8797, 608, 9197, 608,
                9599, 608, 9998, 609, 10397, 608, 10797, 608, 11197, 608, 11597, 608, 11997, 609, 12399, 609,
                12798, 607, 13198, 609, 13598, 608, 14001, 609, 14397, 609, 14799, 609, 15199, 609, 15599, 608,
                15997, 607, 16397, 608, 16797, 608, 17201, 609, 17598, 607, 17998, 609, 18398, 608, 18798, 608,
                19197, 610, 19600, 608,
            ),
            intArrayOf(
                0, 641, 400, 642, 800, 641, 1201, 642, 1600, 643, 1999, 642, 2401, 641, 2800, 643,
                3204, 641, 3600, 641, 4000, 641, 4400, 642, 4799, 642, 5201, 642, 5599, 640, 6001, 642,
                6403, 639, 6800, 642, 7203, 641, 7601, 642, 8000, 642, 8402, 641, 8800, 640, 9200, 639,
                9600, 641, 9999, 642, 10400, 641, 10802, 641, 11203, 640, 11600, 640, 12001, 641, 12402, 642,
                12800, 639, 13200, 639, 13600, 641, 14001, 641, 14400, 641, 14799, 642, 15199, 642, 15600, 644,
                16004, 645, 16399, 644, 16799, 645, 17204, 645, 17604, 649, 18004, 644, 18400, 645, 18801, 642,
                19199, 643, 19604, 642,
            ),
            intArrayOf(
                0, 609, 400, 609, 800, 607, 1204, 608, 1601, 609, 1999, 608, 2399, 608, 2800, 608,
                3200, 608, 3602, 608, 4004, 607, 4402, 608, 4799, 608, 5200, 609, 5600, 607, 6001, 608,
                6402, 608, 6801, 607, 7202, 609, 7600, 608, 8004, 608, 8400, 609, 8799, 607, 9203, 608,
                9600, 608, 10003, 608, 10399, 607, 10800, 607, 11201, 608, 11602, 609, 12000, 609, 12400, 608,
                12802, 609, 13200, 608, 13601, 608, 14001, 607, 14401, 608, 14800, 608, 15200, 608, 15601, 608,
                16000, 608, 16399, 608, 16799, 608, 17200, 609, 17599, 608, 18001, 609, 18401, 608, 18799, 609,
                19201, 608, 19600, 608,
            ),
        )

        private val attendedB = listOf(
            intArrayOf(
                0, 59, 400, 60, 800, 59, 1202, 59, 1602, 59, 2000, 58, 2400, 59, 2801, 57,
                3201, 59, 3605, 58, 4002, 60, 4400, 61, 4800, 60, 5202, 54, 5602, 43, 6001, 40,
                6400, 37, 6804, 37, 7205, 36, 7601, 35, 8004, 37, 8400, 36, 8801, 36, 9200, 36,
                9600, 36, 10004, 37, 10401, 37, 10801, 34, 11201, 35, 11602, 36, 12001, 36, 12403, 37,
                12801, 37, 13205, 37, 13601, 36, 14001, 36, 14404, 36, 14800, 36, 15202, 36, 15602, 36,
                16000, 34, 16401, 36, 16801, 35, 17203, 37, 17600, 35, 18002, 35, 18400, 36, 18801, 36,
                19201, 35, 19600, 35,
            ),
            intArrayOf(
                0, 50, 398, 55, 802, 55, 1203, 54, 1598, 53, 1999, 52, 2401, 54, 2802, 52,
                3199, 51, 3600, 50, 4003, 52, 4403, 55, 4799, 52, 5200, 51, 5600, 54, 6003, 58,
                6400, 56, 6800, 55, 7200, 55, 7600, 56, 7998, 51, 8401, 52, 8799, 51, 9201, 51,
                9600, 52, 10000, 51, 10451, 51, 10798, 50, 11199, 50, 11598, 53, 11999, 54, 12403, 54,
                12799, 52, 13200, 56, 13599, 56, 13999, 53, 14398, 54, 14803, 54, 15199, 53, 15598, 51,
                16001, 53, 16399, 54, 16799, 54, 17199, 51, 17599, 51, 18003, 53, 18399, 54, 18799, 55,
                19198, 54, 19599, 54,
            ),
            intArrayOf(
                0, 36, 397, 36, 797, 35, 1867, 36, 1909, 36, 1997, 36, 2397, 36, 3513, 36,
                3565, 36, 3647, 36, 3997, 36, 4400, 36, 4797, 36, 5198, 37, 5599, 35, 5997, 35,
                6398, 37, 6799, 36, 7197, 36, 7598, 35, 8000, 36, 8397, 36, 8797, 36, 9198, 35,
                9597, 36, 9998, 36, 10396, 37, 10923, 37, 11230, 36, 12739, 36, 12775, 35, 12816, 35,
                12865, 35, 13196, 37, 13596, 36, 13996, 35, 14396, 36, 14796, 35, 15197, 36, 15597, 35,
                15996, 35, 16397, 36, 16796, 37, 17196, 36, 17598, 36, 17998, 35, 18398, 36, 18797, 37,
                19197, 35, 19598, 36, 19996, 37,
            ),
        )

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
