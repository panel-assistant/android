package io.panelassistant.android.sensors

import org.junit.Assert.*
import org.junit.Test

class ProximitySampleFreshnessTest {
    @Test fun onlyNewMonotonicTimestampWithin750MillisecondsIsFresh() {
        for ((timestamp, expected) in listOf(
            10_000_000_000L to true, 9_250_000_000L to true, 9_249_000_000L to false,
            10_001_000_000L to false, 9_000_000_000L to false, 8_000_000_000L to false,
            0L to false, -1L to false,
        )) {
            val gate = ProximityTimestampGate()
            assertTrue(gate.accept(ns(9_000), 9_000, WALL - 1_000))
            assertEquals(expected, gate.accept(timestamp, 10_000, WALL))
        }
    }

    @Test fun retainedBackfillCanEstablishStateButCannotBecomeALiveWave() {
        val engine = ProximityCalibrationEngine(ProximityCalibrationEngine.Calibration(
            version = 1, mode = ProximityCalibrationEngine.Mode.BINARY, clearRaw = 0f, nearRaw = 1f,
        ))
        engine.observe(0f, 10_000)
        engine.tick(10_200)
        val gate = ProximityTimestampGate()
        assertTrue(gate.accept(ns(10_000), 10_000, WALL))
        engine.observe(1f, 10_800, live = gate.accept(ns(9_000), 10_800, WALL + 800))
        engine.observe(0f, 11_100, live = gate.accept(ns(11_100), 11_100, WALL + 1_100))
        assertFalse(engine.tick(11_300).gesture)
    }
    @Test fun monotonicSeedAndEpochEdgesInOneStreamProduceOneLiveWave() {
        val gate = ProximityTimestampGate()
        val engine = ProximityCalibrationEngine(ProximityCalibrationEngine.Calibration(
            version = 1, mode = ProximityCalibrationEngine.Mode.BINARY, clearRaw = 1f, nearRaw = 0f,
        ))
        val elapsed = 1_218_386_275L
        val wall = 1_788_784_339_895L
        assertTrue(gate.accept(ns(elapsed), elapsed, wall))
        assertFalse(engine.observe(1f, elapsed, live = false, calibrationLive = true).gesture)
        engine.tick(elapsed + 200)
        val nearFresh = gate.accept(ns(wall + 1_000), elapsed + 1_000, wall + 1_000)
        assertTrue(nearFresh)
        assertFalse(engine.observe(0f, elapsed + 1_000, live = nearFresh).gesture)
        assertTrue(engine.tick(elapsed + 1_200).gesture)
        val clearFresh = gate.accept(ns(wall + 1_400), elapsed + 1_400, wall + 1_400)
        assertTrue(clearFresh)
        assertFalse(engine.observe(1f, elapsed + 1_400, live = clearFresh).gesture)
        assertFalse(engine.tick(elapsed + 1_550).gesture)
        assertFalse(engine.tick(elapsed + 1_600).gesture)
        // An epoch highwater cannot poison a later monotonic registration seed.
        assertTrue(gate.accept(ns(elapsed + 2_000), elapsed + 2_000, wall + 2_000))
    }

    @Test fun eitherClockRejectsStaleFutureOutOfOrderAndDuplicateWithoutPoisoningHighwater() {
        for (wallDomain in listOf(false, true)) {
            val gate = ProximityTimestampGate()
            fun sample(at: Long) = ns(if (wallDomain) WALL + at - 10_000 else at)
            fun accept(sampleAt: Long, receivedAt: Long) =
                gate.accept(sample(sampleAt), receivedAt, WALL + receivedAt - 10_000)
            assertTrue(accept(10_000, 10_000))
            assertFalse(accept(11_000, 11_751))
            assertTrue(accept(11_752, 11_752))
            assertFalse(accept(13_000, 12_000))
            assertTrue(accept(12_000, 12_000))
            assertFalse(accept(11_999, 12_001))
            assertFalse(accept(12_000, 12_002))
            assertTrue(accept(12_003, 12_003))
        }
    }

    @Test fun crossDomainBackfillCannotPassAnIndependentHighwater() {
        val gate = ProximityTimestampGate()
        assertTrue(gate.accept(ns(10_000), 10_000, WALL))
        // Fresh by age and first-ever epoch value, but older than the admitted monotonic seed.
        assertFalse(gate.accept(ns(WALL - 50), 10_100, WALL + 100))
        assertTrue(gate.accept(ns(WALL + 200), 10_200, WALL + 200))
        assertFalse(gate.accept(ns(10_150), 10_250, WALL + 250))
        assertTrue(gate.accept(ns(10_300), 10_300, WALL + 300))
        assertFalse(gate.accept(ns(WALL + 250), 10_350, WALL + 350))
        assertTrue(gate.accept(ns(WALL + 400), 10_400, WALL + 400))
    }

    @Test fun freshCrossDomainProbeReturnStillCannotActuate() {
        val gate = ProximityTimestampGate()
        val engine = ProximityCalibrationEngine(ProximityCalibrationEngine.Calibration(
            version = 1, mode = ProximityCalibrationEngine.Mode.BINARY, clearRaw = 0f, nearRaw = 1f,
        ))
        assertTrue(gate.accept(ns(10_000), 10_000, WALL))
        engine.observe(0f, 10_000, live = false, calibrationLive = true)
        engine.tick(10_200)
        assertTrue(gate.accept(ns(WALL + 1_000), 11_000, WALL + 1_000))
        engine.observe(1f, 11_000)
        val probeFresh = gate.accept(ns(11_400), 11_400, WALL + 1_400)
        assertTrue(probeFresh)
        engine.observe(0f, 11_400, live = false, calibrationLive = probeFresh)
        assertFalse(engine.tick(11_600).gesture)
    }

    @Test fun clockStepRejectsCurrentEventAndQuarantinesWallEvidenceWhileElapsedRecovers() {
        val gate = ProximityTimestampGate()
        assertTrue(gate.accept(ns(WALL), 10_000, WALL))
        assertFalse(gate.accept(ns(WALL + 5_100), 10_100, WALL + 5_100))
        assertFalse(gate.accept(ns(WALL + 5_500), 10_500, WALL + 5_500))
        assertTrue(gate.accept(ns(10_700), 10_700, WALL + 5_700))
        assertTrue(gate.accept(ns(WALL + 6_000), 11_000, WALL + 6_000))
    }

    @Test fun clockRollbackCannotReAdmitHistoricalEpochValuesUntilAnExplicitNewAcquisition() {
        val gate = ProximityTimestampGate()
        assertTrue(gate.accept(ns(WALL), 10_000, WALL))
        assertFalse(gate.accept(ns(WALL - 4_900), 10_100, WALL - 4_900))
        assertFalse(gate.accept(ns(WALL - 3_900), 11_100, WALL - 3_900))
        assertTrue(gate.accept(ns(11_101), 11_101, WALL - 3_899))
        gate.reset()
        assertTrue(gate.accept(ns(WALL - 3_898), 11_102, WALL - 3_898))
    }

    @Test fun ambiguousClockDomainsAndRegressingReceiptTimeFailClosed() {
        val gate = ProximityTimestampGate()
        assertFalse(gate.accept(ns(10_000), 10_000, 10_000))
        gate.reset()
        assertTrue(gate.accept(ns(10_000), 10_000, WALL))
        assertFalse(gate.accept(ns(9_999), 9_999, WALL - 1))
        assertFalse(gate.accept(0, 10_001, WALL + 1))
        assertFalse(gate.accept(-1, 10_001, WALL + 1))
        assertTrue(gate.accept(ns(10_002), 10_002, WALL + 2))
    }

    private fun ns(milliseconds: Long) = milliseconds * 1_000_000L

    companion object {
        private const val WALL = 1_788_784_339_895L
    }

}
