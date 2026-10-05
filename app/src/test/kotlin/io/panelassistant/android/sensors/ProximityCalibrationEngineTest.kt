package io.panelassistant.android.sensors

import io.panelassistant.android.sensors.ProximityCalibrationEngine.*
import org.junit.Assert.*
import org.junit.Test

class ProximityCalibrationEngineTest {
    @Test fun guidedCadenceLeavesTimeToReadOrHearEachInstruction() {
        assertEquals(8_000L, ProximityCalibrationEngine.COUNTDOWN_MS)
        assertEquals(5_000L, ProximityCalibrationEngine.CAPTURE_HOLD_MS)
        assertEquals(30_000L, ProximityCalibrationEngine.APPROACH_TIMEOUT_MS)
        assertEquals(45_000L, ProximityCalibrationEngine.WAVE_PHASE_TIMEOUT_MS)
    }

    @Test fun singleHandApproachWakesBeforeWithdrawalInRangedAndBothBinaryPolarities() {
        for ((clear, hand) in listOf(100f to 5f, 5f to 100f, 0f to 1f, 1f to 0f)) {
            val mode = if (clear in 0f..1f && hand in 0f..1f) Mode.BINARY else Mode.RANGED
            val h = Journey(Calibration(mode = mode, clearRaw = clear, nearRaw = hand,
                wave = WaveCalibration(clearRaw = clear, nearRaw = hand)))
            h.observe(clear, live = false); h.tick(800); h.observe(hand)
            assertFalse(h.tick(199).gesture)
            assertTrue("The screen can light while the hand is still approaching/near", h.tick(1).gesture)
            assertFalse("Holding through a subsequent touch cannot wake twice", h.tick(5_000).gesture)
            h.observe(clear)
            assertFalse("Withdrawal only rearms the gesture", h.tick(150).gesture)
            h.tick(700); h.observe(hand)
            assertTrue(h.tick(200).gesture)
            assertEquals(0, h.writes)
        }
    }

    @Test fun trainingSeparatesOneApproachFromClearingForTheNextCheck() {
        val h = Journey()
        h.presence(100f, 50f); h.capture(50f); h.tick(COUNTDOWN_MS)
        h.observe(5f); h.tick(250)
        assertEquals(Cue.MOVE_AWAY, h.state.cue)
        h.observe(50f)
        assertEquals(Stage.WAVES, h.state.stage)
        h.tick(COUNTDOWN_MS); h.observe(50f); h.tick(800)
        assertEquals(Cue.WAVE, h.state.cue)
        h.observe(5f); h.tick(250)
        assertEquals(Cue.MOVE_AWAY, h.state.cue)
        assertEquals(0, h.state.accepted)
        h.observe(50f); h.tick(150)
        assertEquals(1, h.state.accepted)
        assertEquals(Cue.WAVE, h.state.cue)
    }

    @Test fun rejectedApproachCannotRepeatWhileHeldEvenAfterCooldownIsReleased() {
        val h = Journey(legacy())
        h.observe(40f); h.tick(800); h.observe(5f)
        assertTrue(h.tick(200).gesture)
        h.engine.releaseGestureCooldown()
        repeat(5) { h.observe(5f); assertFalse(h.tick(200).gesture) }
        h.observe(40f); h.tick(800); h.observe(5f)
        assertTrue(h.tick(200).gesture)
    }

    @Test fun historicalNearBeforeDebounceCancelsApproachAndRestartNearCannotWake() {
        val h = Journey(legacy())
        h.observe(40f); h.tick(800); h.observe(5f); h.tick(100)
        h.observe(5f, live = false)
        assertFalse(h.tick(200).gesture)
        h.observe(5f)
        assertFalse(h.tick(1_000).gesture)
        val restarted = Journey(legacy())
        restarted.observe(5f)
        assertFalse(restarted.tick(1_000).gesture)
    }

    @Test fun normalApproachAndHandWaveUseIndependentAnchorsAndEvents() {
        val h = Journey()
        h.presence(100f, 50f)
        h.wave(50f, 5f)
        assertEquals(Stage.REVIEW, h.state.stage)
        assertTrue(h.state.presenceSupported)
        assertTrue(h.state.waveSupported)
        assertEquals(0, h.writes)
        h.save()
        assertEquals(50f, h.saved!!.nearRaw, 0f)
        assertEquals(50f, h.saved!!.wave!!.clearRaw, 0f)
        assertEquals(5f, h.saved!!.wave!!.nearRaw, 0f)
        h.observe(100f); h.tick(200)
        h.tick(1_000); assertFalse(h.observe(50f).presenceApproach)
        val approach = h.tick(150)
        assertTrue(approach.presenceApproach)
        assertFalse(approach.gesture)
        assertEquals(true, approach.near)
        assertFalse(h.tick(100).presenceApproach)
        val wave = h.pulse(50f, 5f)
        assertTrue(wave.gesture)
        assertFalse(wave.presenceApproach)
        assertEquals(true, wave.near)
    }

    @Test fun rangedCalibrationWorksAcrossPositiveScalesAndBothDirections() {
        for ((clear, body, hand) in listOf(Triple(100f, 50f, 5f), Triple(1f, 20f, 80f), Triple(.1f, .05f, .005f))) {
            val h = Journey()
            h.presence(clear, body); h.wave(body, hand); h.save()
            assertEquals(Mode.RANGED, h.saved!!.mode)
            assertEquals(body, h.saved!!.nearRaw, 0f)
            assertEquals(hand, h.saved!!.wave!!.nearRaw, 0f)
        }
    }

    @Test fun verifiedRawContractRemainsRangedForZeroOneEndpointsAfterCancelAndRestart() {
        val h = Journey(sourceMode = Mode.RANGED)
        h.begin(0f)
        h.engine.action("cancel", h.now)
        h.presence(0f, 1f)
        h.capture(1f)
        h.tick(WAVE_PHASE_TIMEOUT_MS)
        h.save()
        assertEquals(Mode.RANGED, h.saved!!.mode)
        val restarted = Journey(h.saved, sourceMode = Mode.RANGED)
        assertTrue(restarted.observe(2f).available)
        assertEquals(Mode.RANGED, restarted.state.calibration!!.mode)
    }

    @Test fun binaryNormalApproachCanSavePresenceWithoutAnIndistinguishableSingleWave() {
        for ((clear, body) in listOf(0f to 1f, 1f to 0f)) {
            val h = Journey()
            h.presence(clear, body)
            h.capture(body)
            assertEquals(Stage.WAVE_CAPTURE, h.state.stage)
            h.tick(WAVE_PHASE_TIMEOUT_MS)
            assertEquals(Stage.REVIEW, h.state.stage)
            assertTrue(h.state.presenceSupported)
            assertFalse(h.state.waveSupported)
            assertTrue(h.state.canSave)
            h.save()
            assertEquals(Mode.BINARY, h.saved!!.mode)
            assertNull(h.saved!!.wave)
            assertFalse(h.pulse(clear, body).gesture)
        }
    }

    @Test fun unseenBodyApproachCanStillValidateWaveOnlyInEitherBinaryPolarity() {
        for ((clear, hand) in listOf(0f to 1f, 1f to 0f)) {
            val h = Journey()
            h.begin(clear); h.capture(clear)
            h.tick(APPROACH_TIMEOUT_MS)
            assertEquals(Stage.WAVE_BASELINE, h.state.stage)
            h.wave(clear, hand)
            assertFalse(h.state.presenceSupported)
            assertTrue(h.state.waveSupported)
            h.save()
            assertFalse(h.saved!!.presenceSupported)
            assertNull(h.observe(clear).near)
            assertTrue(h.pulse(clear, hand).gesture)
            assertNull(h.state.level)
        }
    }

    @Test fun neitherCapabilityLeavesPreviousCalibrationUnchangedAndCannotSave() {
        val old = legacy()
        val h = Journey(old)
        h.begin(0f); h.capture(0f); h.tick(APPROACH_TIMEOUT_MS)
        h.capture(0f); h.tick(WAVE_PHASE_TIMEOUT_MS)
        assertEquals(Stage.REVIEW, h.state.stage)
        assertFalse(h.state.presenceSupported)
        assertFalse(h.state.waveSupported)
        assertFalse(h.state.canSave)
        h.engine.action("save", h.now)
        assertEquals(old, h.state.calibration)
        assertEquals(0, h.writes)
    }

    @Test fun doublePatternRequiresTwoTransitionsAndOrdinarySingleApproachCannotWake() {
        val h = Journey(pattern = WavePattern.DOUBLE)
        h.presence(0f, 1f)
        h.wave(0f, 1f)
        h.save()
        assertEquals(WavePattern.DOUBLE, h.saved!!.wave!!.pattern)
        assertFalse(h.pulse(0f, 1f).gesture)
        h.tick(2_000)
        assertFalse(h.pulse(0f, 1f).gesture)
        assertTrue(h.pulse(0f, 1f).gesture)
        assertFalse(h.tick(200).gesture)
    }

    @Test fun firstProbeBackfillAndHistoricalReturnNeverPublishPresenceApproachOrWake() {
        val h = Journey(legacy())
        assertFalse(h.observe(5f, live = false).presenceApproach)
        assertFalse(h.tick(200).presenceApproach)
        h.observe(40f); h.tick(200)
        h.tick(800); h.observe(5f, live = false)
        assertFalse(h.tick(200).presenceApproach)
        h.observe(40f, live = false)
        assertFalse(h.tick(200).gesture)
        h.tick(800); h.observe(5f)
        assertTrue(h.tick(150).presenceApproach)
        h.observe(40f, live = false)
        assertFalse(h.tick(150).gesture)
    }

    @Test fun sourceLossBetweenObservedEdgeAndDebounceRevokesBothEvents() {
        val h = Journey(legacy())
        h.observe(40f); h.tick(200); h.tick(800); h.observe(5f)
        val generation = h.state.generation
        h.engine.sourceUnavailable(h.now + 50); h.now += 50
        val result = h.tick(200)
        assertFalse(result.presenceApproach)
        assertFalse(result.gesture)
        assertFalse(result.wakeReady)
        assertTrue(result.generation > generation)
    }

    @Test fun ordinaryMovementCannotTrainOrPersist() {
        val h = Journey()
        repeat(20) { assertFalse(h.pulse(40f, 5f).gesture) }
        assertEquals(0, h.writes)
        assertNull(h.state.calibration)
    }

    @Test fun fixedCalibrationNeverRebasesAfterMovementOrLongHolds() {
        val old = legacy()
        val h = Journey(old)
        repeat(20) { h.pulse(38f, 1f) }
        h.observe(20f); h.tick(60_000)
        assertEquals(old, h.state.calibration)
        assertEquals(0, h.writes)
    }

    @Test fun cancellationAndTimeoutPreservePreviousCalibrationAtEveryCollectionStage() {
        for (target in listOf(Stage.INTRO, Stage.CLEAR, Stage.NEAR, Stage.RETURN_CLEAR, Stage.WAVE_BASELINE, Stage.WAVE_CAPTURE, Stage.WAVES, Stage.REVIEW)) {
            for (timeout in listOf(false, true)) {
                val old = legacy()
                val h = Journey(old)
                h.to(target)
                if (timeout) h.tick(SESSION_TIMEOUT_MS) else h.engine.action("cancel", h.now)
                assertEquals(if (timeout) Stage.TIMED_OUT else Stage.CANCELLED, h.state.stage)
                assertEquals(old, h.state.calibration)
                assertEquals(0, h.writes)
            }
        }
    }

    @Test fun optionalWaveSourceLossIsNotDowngradedToPresenceOnlySuccess() {
        val h = Journey(legacy())
        h.presence(100f, 50f); h.capture(50f)
        assertEquals(Stage.WAVE_CAPTURE, h.state.stage)
        h.engine.sourceUnavailable(h.now)
        assertEquals(Stage.FAILED, h.state.stage)
        assertEquals(0, h.writes)
        assertFalse(h.state.canSave)
    }

    @Test fun introWaitsForFreshAcquisitionThenNormalCalibrationCanComplete() {
        val h = Journey()
        h.engine.start(h.now)
        h.engine.sourceUnavailable(h.now)
        assertEquals(Stage.INTRO, h.state.stage)
        assertTrue(h.state.active)
        assertFalse(h.state.available)
        h.observe(100f, live = false, capture = false)
        assertFalse(h.state.available)
        h.observe(100f, live = false, capture = true)
        h.engine.action("begin", h.now)
        h.capture(100f); h.capture(50f); h.capture(100f)
        h.wave(50f, 5f); h.save()
        assertEquals(1, h.writes)
    }

    @Test fun malformedIntroSampleAndSourceLossAfterBeginStillFail() {
        val h = Journey(legacy())
        h.engine.start(h.now); h.observe(Float.NaN)
        assertEquals(Stage.FAILED, h.state.stage)
        val second = Journey(legacy()); second.begin(40f)
        second.engine.sourceUnavailable(second.now)
        assertEquals(Stage.FAILED, second.state.stage)
    }

    @Test fun atomicCommitFailurePreservesPreviousLiveAndDurableCalibration() {
        val old = legacy()
        val h = Journey(old, failCommit = true)
        h.presence(100f, 50f); h.wave(50f, 5f)
        h.engine.action("save", h.now)
        assertEquals(Stage.FAILED, h.state.stage)
        assertEquals(old, h.state.calibration)
        assertNull(h.saved)
        assertEquals(1, h.writes)
    }

    @Test fun restartDropsCandidateButRestoresVersionOneWaveCompatibilityWithoutWriting() {
        val old = legacy()
        val h = Journey(old); h.presence(100f, 50f); h.capture(50f)
        val restarted = Journey(old)
        assertNull(restarted.state.stage)
        assertTrue(restarted.pulse(40f, 5f).gesture)
        assertEquals(0, restarted.writes)
    }

    @Test fun noisyTransitionsDoNotWakeOrConsumeCooldown() {
        val h = Journey(legacy())
        h.observe(40f); h.tick(200); h.tick(800); h.observe(5f)
        h.tick(20); h.observe(40f); assertFalse(h.tick(200).gesture)
        assertTrue(h.pulse(40f, 5f).gesture)
    }

    @Test fun rejectedServiceAdmissionReleasesCooldownWithoutLosingClearEvidence() {
        val h = Journey(legacy())
        assertTrue(h.pulse(40f, 5f).gesture)
        h.engine.releaseGestureCooldown()
        h.tick(550); h.observe(5f)
        assertTrue(h.tick(300).gesture)
        h.observe(40f)
        assertFalse(h.tick(150).gesture)
    }

    @Test fun cueCountdownAndHeldCaptureAreEngineOwnedAndObservationStagesAdvanceAutomatically() {
        val h = Journey()
        h.begin(100f)
        assertEquals(Cue.MOVE_AWAY, h.state.cue)
        assertEquals(COUNTDOWN_MS, h.state.cueRemainingMs)
        h.tick(COUNTDOWN_MS)
        assertEquals(Cue.HOLD, h.state.cue)
        assertEquals(CAPTURE_HOLD_MS, h.state.cueDurationMs)
        h.tick(CAPTURE_HOLD_MS)
        assertEquals(Stage.NEAR, h.state.stage)
        assertEquals(Cue.APPROACH, h.state.cue)
        assertEquals(COUNTDOWN_MS, h.state.cueRemainingMs)
    }

    @Test fun noisyClearObservationCannotBecomeBodyApproachUntilTheBodyActuallyMovesNear() {
        val h = Journey(); h.begin(100f)
        h.tick(COUNTDOWN_MS)
        for (raw in listOf(98f, 100f, 102f, 99f, 101f)) { h.observe(raw); h.tick(200) }
        h.tick(CAPTURE_HOLD_MS - 1_000)
        assertEquals(Stage.NEAR, h.state.stage)
        h.tick(COUNTDOWN_MS)
        repeat(10) { h.observe(if (it % 2 == 0) 98f else 102f); h.tick(200) }
        assertEquals(Stage.NEAR, h.state.stage)
        h.observe(50f); h.tick(CAPTURE_HOLD_MS)
        assertEquals(Stage.RETURN_CLEAR, h.state.stage)
    }

    @Test fun rangedSourceWithBinaryEndpointMediansRetainsObservedIntermediateValues() {
        val h = Journey()
        h.begin(0f)
        h.observe(.2f); h.observe(0f)
        h.capture(0f)
        // No body response: hand support may still use the endpoints 0 and 1.
        h.tick(APPROACH_TIMEOUT_MS)
        h.wave(0f, 1f)
        h.save()
        assertEquals(Mode.RANGED, h.saved!!.mode)
        assertFalse(h.observe(.8f).stage == Stage.FAILED)
        assertTrue(h.state.available)
    }

    @Test fun waveOnlyBinaryRepresentationCannotChangeDuringValidation() {
        val old = legacy()
        val h = Journey(old)
        h.begin(0f); h.capture(0f); h.tick(APPROACH_TIMEOUT_MS)
        h.capture(0f); h.tick(COUNTDOWN_MS); h.pulse(0f, 1f)
        assertEquals(Stage.WAVES, h.state.stage)
        h.observe(8f)
        assertEquals(Stage.FAILED, h.state.stage)
        assertEquals(old, h.state.calibration)
        assertEquals(0, h.writes)
    }

    @Test fun observedRangedSourceWithZeroOneWaveAnchorsStillAcceptsIntermediateValidationValues() {
        val h = Journey()
        h.begin(0f); h.observe(.2f); h.observe(0f)
        h.capture(0f); h.tick(APPROACH_TIMEOUT_MS)
        h.capture(0f); h.tick(COUNTDOWN_MS); h.pulse(0f, 1f)
        assertEquals(Stage.WAVES, h.state.stage)
        h.observe(.8f); h.observe(0f)
        h.tick(COUNTDOWN_MS)
        repeat(REQUIRED_WAVES) { h.pulse(0f, 1f) }
        assertEquals(Stage.REVIEW, h.state.stage)
        h.save()
        assertEquals(Mode.RANGED, h.saved!!.mode)
    }

    @Test fun binaryCalibrationRejectsNestedWaveAnchorsOutsideObservedRepresentation() {
        assertThrows(IllegalArgumentException::class.java) {
            Calibration(mode = Mode.BINARY, clearRaw = 0f, nearRaw = 1f,
                wave = WaveCalibration(clearRaw = 1f, nearRaw = 2f))
        }
    }

    @Test fun finiteExtremeRawValuesUseDoubleArithmeticWithoutCrashing() {
        val c = Calibration(mode = Mode.RANGED, clearRaw = -Float.MAX_VALUE, nearRaw = -Float.MAX_VALUE / 2)
        assertEquals(0, c.level(-Float.MAX_VALUE))
        assertEquals(100, c.level(Float.MAX_VALUE))
    }

    private class Journey(initial: Calibration? = null, pattern: WavePattern = WavePattern.SINGLE, failCommit: Boolean = false, sourceMode: Mode? = null) {
        var now = 0L
        var writes = 0
        var saved: Calibration? = null
        val engine = ProximityCalibrationEngine(initial, requestedWavePattern = pattern, observedSourceMode = sourceMode) {
            writes++; if (failCommit) false else { saved = it; true }
        }
        val state get() = engine.current()
        fun observe(raw: Float, live: Boolean = true, capture: Boolean = live) = engine.observe(raw, now, live, capture)
        fun tick(delta: Long): Result { now += delta; return engine.tick(now) }
        fun begin(clear: Float) { engine.start(now); observe(clear, false, true); engine.action("begin", now) }
        fun capture(raw: Float) { tick(COUNTDOWN_MS); observe(raw); tick(CAPTURE_HOLD_MS) }
        fun presence(clear: Float, body: Float) { begin(clear); capture(clear); capture(body); capture(clear); assertEquals(Stage.WAVE_BASELINE, state.stage) }
        fun pulse(clear: Float, near: Float): Result {
            observe(clear); tick(800); observe(near)
            val entry = tick(400)
            observe(clear)
            val release = tick(150)
            return release.copy(gesture = entry.gesture || release.gesture)
        }
        fun wave(baseline: Float, hand: Float) {
            assertEquals(Stage.WAVE_BASELINE, state.stage)
            capture(baseline)
            assertEquals(Stage.WAVE_CAPTURE, state.stage)
            tick(COUNTDOWN_MS)
            pulse(baseline, hand)
            assertEquals(Stage.WAVES, state.stage)
            tick(COUNTDOWN_MS)
            repeat(REQUIRED_WAVES) {
                pulse(baseline, hand)
                if (state.wavePattern == WavePattern.DOUBLE) pulse(baseline, hand)
            }
            assertEquals(Stage.REVIEW, state.stage)
        }
        fun save() { assertTrue(state.canSave); engine.action("save", now); assertEquals(Stage.SAVED, state.stage) }
        fun to(target: Stage) {
            engine.start(now); observe(100f, false, true)
            if (target == Stage.INTRO) return
            engine.action("begin", now); if (target == Stage.CLEAR) return
            capture(100f); if (target == Stage.NEAR) return
            capture(50f); if (target == Stage.RETURN_CLEAR) return
            capture(100f); if (target == Stage.WAVE_BASELINE) return
            capture(50f); if (target == Stage.WAVE_CAPTURE) return
            tick(COUNTDOWN_MS); pulse(50f, 5f); if (target == Stage.WAVES) return
            tick(COUNTDOWN_MS); repeat(REQUIRED_WAVES) { pulse(50f, 5f) }
            assertEquals(target, state.stage)
        }
    }
    companion object {
        private const val COUNTDOWN_MS = ProximityCalibrationEngine.COUNTDOWN_MS
        private const val CAPTURE_HOLD_MS = ProximityCalibrationEngine.CAPTURE_HOLD_MS
        private const val APPROACH_TIMEOUT_MS = ProximityCalibrationEngine.APPROACH_TIMEOUT_MS
        private const val WAVE_PHASE_TIMEOUT_MS = ProximityCalibrationEngine.WAVE_PHASE_TIMEOUT_MS
        private const val SESSION_TIMEOUT_MS = ProximityCalibrationEngine.SESSION_TIMEOUT_MS
        private const val REQUIRED_WAVES = ProximityCalibrationEngine.REQUIRED_WAVES
        private fun legacy() = Calibration(version = 1, mode = Mode.RANGED, clearRaw = 40f, nearRaw = 5f)
    }
}
