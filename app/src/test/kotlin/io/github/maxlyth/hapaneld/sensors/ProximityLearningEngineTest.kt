package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.sensors.ProximityLearningEngine.ActuationStatus
import io.github.maxlyth.hapaneld.sensors.ProximityLearningEngine.GuidedLabel
import io.github.maxlyth.hapaneld.sensors.ProximityLearningEngine.HealthStatus
import io.github.maxlyth.hapaneld.sensors.ProximityLearningEngine.LearningStatus
import io.github.maxlyth.hapaneld.sensors.ProximityLearningEngine.Mode
import io.github.maxlyth.hapaneld.sensors.ProximityLearningEngine.Polarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityLearningEngineTest {
    @Test fun learnsAGradedLowerIsNearScaleAndNormalizesItAcrossTheFleetRange() {
        val engine = ProximityLearningEngine()
        learnBaseline(engine, far = 100f)

        engine.observe(90f, 800)
        engine.observe(70f, 900)
        engine.observe(40f, 1_000)
        engine.observe(10f, 1_100)
        engine.observe(50f, 1_200)
        val learned = engine.observe(100f, 1_300)

        assertEquals(LearningStatus.READY, learned.learning)
        assertEquals(HealthStatus.HEALTHY, learned.health)
        assertEquals(Mode.GRADED, learned.mode)
        assertEquals(Polarity.NEAR_IS_LOWER, learned.polarity)
        assertEquals(0, learned.normalizedLevel)
        assertEquals(false, learned.presence)

        val middle = engine.observe(55f, 1_400)
        assertTrue(middle.normalizedLevel!! in 45..55)
        assertEquals(false, middle.presence)
        val near = engine.observe(10f, 1_500)
        assertEquals(100, near.normalizedLevel)
        assertEquals(true, near.presence)
    }

    @Test fun learnsBinaryHigherIsNearWithoutAnyFirmwareOrProfileHint() {
        val engine = ProximityLearningEngine()
        learnBaseline(engine, far = 0f)
        teachBinaryExcursion(engine, far = 0f, near = 1f, startAt = 800)

        assertEquals(Mode.BINARY, engine.current().mode)
        assertEquals(Polarity.NEAR_IS_HIGHER, engine.current().polarity)
        assertEquals(0, engine.observe(0f, 1_200).normalizedLevel)
        val near = engine.observe(1f, 1_300)
        assertEquals(100, near.normalizedLevel)
        assertEquals(true, near.presence)
        assertEquals(0, engine.observe(0.4f, 1_400).normalizedLevel)
        assertEquals(100, engine.observe(0.6f, 1_500).normalizedLevel)
    }

    @Test fun binaryLearningIsTranslationInvariantAcrossRawOffsets() {
        val low = ProximityLearningEngine()
        learnBaseline(low, far = 0f)
        teachBinaryExcursion(low, far = 0f, near = 1f, startAt = 800)

        val high = ProximityLearningEngine()
        learnBaseline(high, far = 254f)
        teachBinaryExcursion(high, far = 254f, near = 255f, startAt = 800)

        assertEquals(low.current().mode, high.current().mode)
        assertEquals(low.current().polarity, high.current().polarity)
        assertEquals(0, high.observe(254f, 1_100).normalizedLevel)
        assertEquals(100, high.observe(255f, 1_200).normalizedLevel)
    }

    @Test fun binaryLearningIsInvariantAcrossVendorRawScales() {
        for (near in listOf(0.01f, 1f, 1_000f)) {
            val engine = ProximityLearningEngine()
            learnBaseline(engine, far = 0f)
            teachBinaryExcursion(engine, far = 0f, near = near, startAt = 800)

            assertEquals("far at scale $near", 0, engine.observe(0f, 1_100).normalizedLevel)
            assertEquals("near at scale $near", 100, engine.observe(near, 1_200).normalizedLevel)
            assertEquals(Polarity.NEAR_IS_HIGHER, engine.current().polarity)
        }
    }

    @Test fun emitsOnlyACompleteArmedFarExcursionFarGesture() {
        val engine = ProximityLearningEngine()
        learnBaseline(engine, far = 0f)
        teachBinaryExcursion(engine, far = 0f, near = 1f, startAt = 800)

        engine.observe(0f, 1_200)
        val armed = engine.observe(0f, 1_500)
        assertEquals(ActuationStatus.ARMED, armed.actuation)

        val departure = engine.observe(1f, 1_600)
        assertFalse(departure.gesture)
        assertEquals(ActuationStatus.EXCURSION, departure.actuation)
        assertFalse(engine.observe(1f, 1_700).gesture)
        val returned = engine.observe(0f, 1_800)
        assertTrue(returned.gesture)
        assertEquals(1L, returned.gestureSequence)
        assertEquals(ActuationStatus.COOLDOWN, returned.actuation)

        // A second excursion during cooldown cannot act, even though it is a complete transition.
        engine.observe(1f, 1_900)
        val cooledReturn = engine.observe(0f, 2_000)
        assertFalse(cooledReturn.gesture)
        assertEquals(1L, cooledReturn.gestureSequence)
    }

    @Test fun teachingExcursionNeverActsAndAProlongedPresenceIsNotAGesture() {
        val engine = ProximityLearningEngine()
        learnBaseline(engine, far = 100f)
        engine.observe(50f, 800)
        engine.observe(10f, 900)
        val teachingReturn = engine.observe(100f, 1_000)
        assertFalse(teachingReturn.gesture)

        engine.observe(100f, 1_100)
        engine.observe(100f, 1_400)
        engine.observe(10f, 1_500)
        engine.observe(10f, 4_700)
        val returnAfterTimeout = engine.observe(100f, 4_800)
        assertFalse(returnAfterTimeout.gesture)
        assertFalse(returnAfterTimeout.deliberateExample)
    }

    @Test fun prolongedKnownNearRemainsPresenceAndNeverBecomesANewFarBaseline() {
        val engine = ProximityLearningEngine(
            ProximityLearningEngine.Policy(
                maximumGestureMs = 300,
                changePointHoldMs = 600,
            ),
        )
        learnBaseline(engine, far = 100f)
        engine.observe(10f, 800)
        engine.observe(10f, 900)
        engine.observe(100f, 1_000)
        engine.observe(100f, 1_400)

        assertEquals(true, engine.observe(10f, 1_500).presence)
        val held = engine.observe(10f, 2_200)
        assertEquals(LearningStatus.READY, held.learning)
        assertEquals(HealthStatus.HEALTHY, held.health)
        assertEquals(true, held.presence)

        val cleared = engine.observe(100f, 2_300)
        assertEquals(LearningStatus.READY, cleared.learning)
        assertEquals(Polarity.NEAR_IS_LOWER, cleared.polarity)
        assertEquals(0, cleared.normalizedLevel)
        assertFalse(cleared.gesture)
    }

    @Test fun prolongedMidRangePlateauFailsClosedThenStartsANewBehaviorEpoch() {
        val engine = ProximityLearningEngine(
            ProximityLearningEngine.Policy(maximumGestureMs = 300, changePointHoldMs = 600),
        )
        learnBaseline(engine, far = 100f)
        engine.observe(50f, 800)
        engine.observe(0f, 900)
        engine.observe(100f, 1_000)

        engine.observe(20f, 1_500)
        val uncertain = engine.observe(20f, 1_900)
        assertEquals(HealthStatus.MODEL_SHIFT, uncertain.health)
        assertNull(uncertain.normalizedLevel)

        val rebasing = engine.observe(20f, 2_200)
        assertEquals(LearningStatus.RELEARNING, rebasing.learning)
        assertNull(rebasing.presence)
    }

    @Test fun behaviorEpochDropsAllPreviouslyAcceptedWakeEvidence() {
        val engine = ProximityLearningEngine(
            ProximityLearningEngine.Policy(maximumGestureMs = 300, changePointHoldMs = 600),
        )
        assertTrue(engine.restore(ProximityLearningEngine.Snapshot(
            farRaw = 100f,
            nearRaw = 0f,
            noise = 1f,
            mode = Mode.GRADED,
            polarity = Polarity.NEAR_IS_LOWER,
            completedExcursions = 8,
            deliberateExamples = 5,
        )))
        verifySeed(engine, far = 100f, startAt = 0)
        assertEquals(5, engine.current().deliberateExamples)

        engine.observe(20f, 500)
        engine.observe(20f, 900)
        val replacementLearning = engine.observe(20f, 1_200)

        assertEquals(LearningStatus.RELEARNING, replacementLearning.learning)
        assertEquals(0, replacementLearning.deliberateExamples)
        assertEquals(1L, replacementLearning.wakeEvidenceGeneration)
    }

    @Test fun invalidationAuthorityRunsBeforeWakeEvidenceMutation() {
        var allowInvalidation = false
        var examplesSeenByAuthority = -1
        var generationSeenByAuthority = -1L
        lateinit var engine: ProximityLearningEngine
        engine = ProximityLearningEngine(
            ProximityLearningEngine.Policy(maximumGestureMs = 300, changePointHoldMs = 600),
            prepareWakeEvidenceInvalidation = {
                examplesSeenByAuthority = engine.current().deliberateExamples
                generationSeenByAuthority = engine.current().wakeEvidenceGeneration
                allowInvalidation
            },
        )
        assertTrue(engine.restore(ProximityLearningEngine.Snapshot(
            farRaw = 100f,
            nearRaw = 0f,
            noise = 1f,
            mode = Mode.GRADED,
            polarity = Polarity.NEAR_IS_LOWER,
            completedExcursions = 8,
            deliberateExamples = 5,
        )))
        verifySeed(engine, far = 100f, startAt = 0)

        engine.observe(20f, 500)
        engine.observe(20f, 900)
        val refused = engine.observe(20f, 1_200)
        assertEquals(5, examplesSeenByAuthority)
        assertEquals(0L, generationSeenByAuthority)
        assertEquals(5, refused.deliberateExamples)
        assertEquals(0L, refused.wakeEvidenceGeneration)

        allowInvalidation = true
        val committed = engine.observe(20f, 1_300)
        assertEquals(0, committed.deliberateExamples)
        assertEquals(1L, committed.wakeEvidenceGeneration)
    }

    @Test fun boundedShortBurstTraceCannotCreateWakeEvidence() {
        val engine = ProximityLearningEngine(ProximityLearningRuntime.learningPolicy(sparseSource = false))
        var now = 0L
        while (now <= 31_000L) {
            engine.observe(20f + ((now / 50L) % 4L).toFloat(), now)
            now += 50L
        }

        repeat(110) { burst ->
            val samples = 1 + burst % 4
            repeat(samples) {
                engine.observe(84f + (it % 3), now)
                now += 40L
            }
            val clearUntil = now + 2_100L
            while (now < clearUntil) {
                engine.observe(20f + ((now / 50L) % 4L).toFloat(), now)
                now += 50L
            }
        }

        val output = engine.current()
        assertEquals(LearningStatus.LEARNING_EXCURSION, output.learning)
        assertEquals(Mode.UNKNOWN, output.mode)
        assertEquals(0, output.deliberateExamples)
        assertEquals(0L, output.modelSequence)
    }

    @Test fun presenceFailsClosedWhileLearningOnBadInputAndWhenStale() {
        val engine = ProximityLearningEngine()
        val learning = engine.observe(10f, 100)
        assertNull(learning.presence)
        assertNull(learning.normalizedLevel)
        assertEquals(HealthStatus.LEARNING, learning.health)

        val invalid = engine.observe(Float.NaN, 200)
        assertFalse(invalid.accepted)
        assertEquals(HealthStatus.INVALID_SAMPLE, invalid.health)
        assertNull(invalid.presence)
        assertEquals(ActuationStatus.SUPPRESSED_UNHEALTHY, invalid.actuation)

        val seeded = ProximityLearningEngine()
        assertTrue(seeded.warmSeed(ProximityLearningEngine.WarmSeed(100f, 0f, Mode.GRADED)))
        verifySeed(seeded, far = 100f, startAt = 0)
        assertEquals(false, seeded.current().presence)
        val stale = seeded.tick(6_000)
        assertEquals(HealthStatus.STALE, stale.health)
        assertNull(stale.presence)
        assertNull(stale.normalizedLevel)
    }

    @Test fun transportLossCancelsAnInflightGestureBeforeReconnect() {
        val engine = ProximityLearningEngine()
        learnBaseline(engine, far = 0f)
        teachBinaryExcursion(engine, far = 0f, near = 1f, startAt = 800)
        engine.observe(0f, 1_200)
        engine.observe(0f, 1_500)
        engine.observe(1f, 1_600)

        val unavailable = engine.sourceUnavailable()
        assertEquals(HealthStatus.STALE, unavailable.health)
        assertNull(unavailable.presence)
        val reconnectedFar = engine.observe(0f, 1_700)

        assertFalse(reconnectedFar.gesture)
        assertEquals(false, reconnectedFar.presence)
    }

    @Test fun monotonicTimeRegressionIsRejectedAndRequiresANewValidSample() {
        val engine = ProximityLearningEngine()
        engine.observe(10f, 100)

        val rejected = engine.observe(10f, 99)
        assertFalse(rejected.accepted)
        assertEquals(HealthStatus.CLOCK_REGRESSION, rejected.health)
        assertNull(rejected.presence)
        assertEquals(HealthStatus.CLOCK_REGRESSION, engine.tick(100).health)

        val recovered = engine.observe(10f, 101)
        assertTrue(recovered.accepted)
        assertEquals(HealthStatus.LEARNING, recovered.health)
    }

    @Test fun guidedFarAndNearLabelsReachReadinessWithFewerUnambiguousSamples() {
        val engine = ProximityLearningEngine()
        engine.observe(20f, 0, GuidedLabel.FAR)
        engine.observe(20f, 50, GuidedLabel.FAR)
        val farReady = engine.observe(20f, 100, GuidedLabel.FAR)
        assertEquals(LearningStatus.LEARNING_EXCURSION, farReady.learning)
        assertTrue(farReady.readiness >= 50)

        engine.observe(5f, 150, GuidedLabel.NEAR)
        engine.observe(5f, 250, GuidedLabel.NEAR)
        val ready = engine.observe(20f, 350, GuidedLabel.FAR)
        assertEquals(LearningStatus.READY, ready.learning)
        assertEquals(Polarity.NEAR_IS_LOWER, ready.polarity)
        assertEquals(false, ready.presence)
        assertEquals(100, ready.readiness)
    }

    @Test fun warmSeedAndSnapshotRestoreBothVerifyAgainstLiveFarBeforeBecomingReady() {
        val engine = ProximityLearningEngine()
        assertTrue(engine.warmSeed(ProximityLearningEngine.WarmSeed(100f, 0f, Mode.GRADED, 0.5f)))
        assertEquals(LearningStatus.VERIFYING_SEED, engine.current().learning)
        assertNull(engine.current().presence)

        verifySeed(engine, far = 100f, startAt = 0)
        assertEquals(LearningStatus.READY, engine.current().learning)
        assertEquals(50, engine.observe(50f, 400).normalizedLevel)
        engine.observe(100f, 500)
        val snapshot = engine.snapshot()!!
        assertEquals(snapshot.completedExcursions, engine.current().completedExcursions)
        assertEquals(snapshot.deliberateExamples, engine.current().deliberateExamples)

        val restored = ProximityLearningEngine()
        assertTrue(restored.restore(snapshot))
        assertEquals(LearningStatus.VERIFYING_SEED, restored.current().learning)
        assertNull(restored.current().normalizedLevel)
        verifySeed(restored, far = snapshot.farRaw, startAt = 1_000)
        assertEquals(LearningStatus.READY, restored.current().learning)
        assertTrue(restored.observe(50f, 1_400).normalizedLevel!! in 45..55)
    }

    @Test fun restoredMidRangeBaselineShiftEventuallyOpensANewEpochButFullNearDoesNot() {
        val policy = ProximityLearningEngine.Policy(maximumGestureMs = 300, changePointHoldMs = 600)
        val shifted = ProximityLearningEngine(policy)
        assertTrue(shifted.warmSeed(ProximityLearningEngine.WarmSeed(100f, 0f, Mode.GRADED)))
        assertEquals(LearningStatus.VERIFYING_SEED, shifted.observe(20f, 0).learning)
        val rebasing = shifted.observe(20f, 700)
        assertEquals(LearningStatus.RELEARNING, rebasing.learning)
        assertNull(rebasing.normalizedLevel)

        val occupied = ProximityLearningEngine(policy)
        assertTrue(occupied.warmSeed(ProximityLearningEngine.WarmSeed(100f, 0f, Mode.GRADED)))
        occupied.observe(0f, 0)
        val heldNear = occupied.observe(0f, 700)
        assertEquals(LearningStatus.VERIFYING_SEED, heldNear.learning)
        assertNull(heldNear.presence)
    }

    @Test fun twoConsistentContradictoryExcursionsRelearnPolarityWithoutActuating() {
        val engine = ProximityLearningEngine()
        learnBaseline(engine, far = 100f)
        engine.observe(60f, 800)
        engine.observe(10f, 900)
        engine.observe(100f, 1_000)
        engine.observe(100f, 1_100)
        engine.observe(100f, 1_400)
        assertEquals(ActuationStatus.ARMED, engine.current().actuation)

        val conflicting = engine.observe(150f, 1_500)
        assertEquals(LearningStatus.RELEARNING, conflicting.learning)
        assertEquals(HealthStatus.MODEL_SHIFT, conflicting.health)
        assertNull(conflicting.presence)
        assertFalse(conflicting.gesture)
        engine.observe(190f, 1_600)
        val firstReturn = engine.observe(100f, 1_700)
        assertEquals(LearningStatus.READY, firstReturn.learning)
        assertEquals(Polarity.NEAR_IS_LOWER, firstReturn.polarity)
        assertFalse(firstReturn.gesture)

        engine.observe(100f, 1_800)
        engine.observe(100f, 2_100)
        engine.observe(150f, 2_200)
        engine.observe(190f, 2_300)
        val relearned = engine.observe(100f, 2_400)
        assertEquals(LearningStatus.READY, relearned.learning)
        assertEquals(Polarity.NEAR_IS_HIGHER, relearned.polarity)
        assertEquals(Mode.GRADED, relearned.mode)
        assertEquals(0, relearned.deliberateExamples)
        assertEquals(1L, relearned.wakeEvidenceGeneration)
        assertFalse(relearned.gesture)

        engine.observe(100f, 2_500)
        engine.observe(100f, 2_800)
        engine.observe(190f, 2_900)
        engine.observe(190f, 3_000)
        assertTrue(engine.observe(100f, 3_100).gesture)
    }

    @Test fun oneOppositeDirectionSpikeCannotReplaceTheLearnedModel() {
        val engine = ProximityLearningEngine()
        learnBaseline(engine, far = 100f)
        engine.observe(10f, 800)
        engine.observe(10f, 900)
        engine.observe(100f, 1_000)

        engine.observe(150f, 1_100)
        val returned = engine.observe(100f, 1_200)

        assertEquals(LearningStatus.READY, returned.learning)
        assertEquals(Polarity.NEAR_IS_LOWER, returned.polarity)
        assertEquals(0, returned.normalizedLevel)
        assertFalse(returned.gesture)
    }

    @Test fun aPersistentOutOfModelChangePointDropsTheOldModelAndLearnsFailClosed() {
        val engine = ProximityLearningEngine(
            ProximityLearningEngine.Policy(
                maximumGestureMs = 300,
                changePointHoldMs = 600,
            ),
        )
        assertTrue(engine.warmSeed(ProximityLearningEngine.WarmSeed(100f, 0f, Mode.GRADED)))
        verifySeed(engine, far = 100f, startAt = 0)

        assertEquals(LearningStatus.RELEARNING, engine.observe(200f, 400).learning)
        engine.observe(200f, 800)
        val shifted = engine.observe(200f, 1_100)
        assertEquals(LearningStatus.RELEARNING, shifted.learning)
        assertEquals(HealthStatus.MODEL_SHIFT, shifted.health)
        assertNull(shifted.presence)
        assertNull(engine.snapshot())
    }

    @Test fun gradedFarSidePlateauTooSmallForAnEpisodeNeverDropsAvailability() {
        val engine = restoredGradedEngine()

        var now = 1_000L
        while (now <= 5_500L) {
            val output = engine.observe(48f, now)
            assertEquals("health at $now", HealthStatus.HEALTHY, output.health)
            assertEquals("presence at $now", false, output.presence)
            assertTrue("level at $now", output.normalizedLevel != null)
            now += 50L
        }
        while (now <= 5_850L) {
            val output = engine.observe(37.8244f, now)
            assertEquals("return health at $now", HealthStatus.HEALTHY, output.health)
            assertEquals("return presence at $now", false, output.presence)
            now += 50L
        }

        assertEquals(LearningStatus.READY, engine.current().learning)
        assertEquals(119, engine.current().completedExcursions)
        assertEquals(Polarity.NEAR_IS_HIGHER, engine.current().polarity)
        assertTrue(engine.snapshot() != null)
    }

    @Test fun gradedSubDwellOppositeTailNeverDropsAvailability() {
        val engine = restoredGradedEngine()
        var now = 1_000L
        for (raw in floatArrayOf(25.875f, 33.75f, 38.25f, 32.625f, 37.8244f, 37.8244f, 37.8244f)) {
            val output = engine.observe(raw, now)
            assertEquals("learning at $now", LearningStatus.READY, output.learning)
            assertEquals("health at $now", HealthStatus.HEALTHY, output.health)
            assertEquals("presence at $now", false, output.presence)
            assertTrue("level at $now", output.normalizedLevel != null)
            now += 50L
        }

        assertEquals(119, engine.current().completedExcursions)
        assertEquals(Polarity.NEAR_IS_HIGHER, engine.current().polarity)
        assertTrue(engine.snapshot() != null)
    }

    @Test fun persistedGradedModelSurvivesItsRestartTailUntilLongChangePointEvidence() {
        val source = restoredGradedEngine()
        val trusted = source.snapshot()!!
        val persisted = ProximityLearningRuntime.persistedModelJson(trusted, guidedReady = true)
        val restartedModel = ProximityLearningRuntime.persistedModel(persisted)!!
        val restarted = ProximityLearningEngine(ProximityLearningRuntime.learningPolicy(sparseSource = false))
        assertTrue(restarted.restore(restartedModel.snapshot, trustedPersistedModel = true))

        for (now in 0L..300L step 100L) {
            val output = restarted.observe(37.8244f, now)
            assertEquals("anchor health at $now", HealthStatus.LEARNING, output.health)
            assertEquals("anchor learning at $now", LearningStatus.VERIFYING_SEED, output.learning)
            assertEquals("anchor presence at $now", false, output.presence)
            assertTrue("anchor level at $now", output.normalizedLevel != null)
        }

        var output = restarted.current()
        for (now in 350L until 30_350L step 50L) {
            output = restarted.observe(25.875f, now)
            assertEquals("tail learning at $now", LearningStatus.VERIFYING_SEED, output.learning)
            assertEquals("tail health at $now", HealthStatus.LEARNING, output.health)
            assertEquals("tail presence at $now", false, output.presence)
            assertTrue("tail level at $now", output.normalizedLevel != null)
            assertEquals(trusted.mode, output.mode)
            assertEquals(trusted.polarity, output.polarity)
            assertEquals(trusted.completedExcursions, output.completedExcursions)
        }

        output = restarted.observe(25.875f, 30_350L)
        assertEquals(LearningStatus.RELEARNING, output.learning)
        assertEquals(HealthStatus.MODEL_SHIFT, output.health)
        assertNull(output.presence)
        assertNull(output.normalizedLevel)
        assertNull(restarted.snapshot())
    }

    @Test fun repeatedCompressedExcursionsSafelyRelearnTheRangeWithoutFalseGestures() {
        val engine = ProximityLearningEngine()
        assertTrue(engine.warmSeed(ProximityLearningEngine.WarmSeed(100f, 0f, Mode.GRADED)))
        verifySeed(engine, far = 100f, startAt = 0)

        var now = 400L
        repeat(3) {
            engine.observe(60f, now)
            engine.observe(60f, now + 100)
            val returned = engine.observe(100f, now + 200)
            assertFalse(returned.gesture)
            now += 400
        }

        val normalized = engine.observe(60f, now)
        assertTrue(normalized.normalizedLevel!! >= 95)
        assertEquals(true, normalized.presence)
    }

    @Test fun observeReusesOneFixedOutputViewInsteadOfAllocatingPerSample() {
        val engine = ProximityLearningEngine()
        val first = engine.observe(1f, 0)
        val second = engine.observe(1f, 100)
        val tick = engine.tick(150)

        assertSame(first, second)
        assertSame(second, tick)
        assertSame(tick, engine.current())
    }

    @Test fun invalidSeedsAndSnapshotsCannotReplaceCurrentState() {
        val engine = ProximityLearningEngine()
        assertFalse(engine.warmSeed(ProximityLearningEngine.WarmSeed(1f, 1f)))
        assertEquals(LearningStatus.LEARNING_FAR, engine.current().learning)
        assertFalse(
            engine.restore(
                ProximityLearningEngine.Snapshot(
                    schemaVersion = 99,
                    farRaw = 1f,
                    nearRaw = 0f,
                    noise = 0f,
                    mode = Mode.BINARY,
                    polarity = Polarity.NEAR_IS_LOWER,
                    completedExcursions = 1,
                ),
            ),
        )
        assertEquals(LearningStatus.LEARNING_FAR, engine.current().learning)
    }

    @Test fun sparseOnChangeSourceLearnsAndRearmsAcrossQuietFarIntervals() {
        val engine = ProximityLearningEngine(
            ProximityLearningEngine.Policy(
                baselineSamples = 1,
                baselineDurationMs = 0,
                farArmMs = 50,
                minimumExcursionMs = 20,
                maximumGestureMs = 500,
                cooldownMs = 100,
                changePointHoldMs = 1_000,
            ),
        )

        assertEquals(LearningStatus.LEARNING_EXCURSION, engine.observe(0f, 0).learning)
        engine.observe(1f, 100)
        assertEquals(LearningStatus.READY, engine.observe(0f, 150).learning)

        assertFalse(engine.observe(1f, 250).gesture)
        assertTrue(engine.observe(0f, 300).gesture)
        assertFalse(engine.observe(1f, 400).gesture)
        val secondReturn = engine.observe(0f, 450)
        assertTrue(secondReturn.gesture)
        assertEquals(2L, secondReturn.gestureSequence)
    }

    @Test fun sparseBootNearUsesDwellEvidenceToAvoidInvertedNormalization() {
        val engine = ProximityLearningEngine(
            ProximityLearningEngine.Policy(
                baselineSamples = 1,
                baselineDurationMs = 0,
                farArmMs = 50,
                minimumExcursionMs = 20,
                maximumGestureMs = 500,
                changePointHoldMs = 1_000,
            ),
        )

        assertEquals(LearningStatus.LEARNING_EXCURSION, engine.observe(1f, 0).learning)
        engine.observe(0f, 50)
        val returnedNear = engine.observe(1f, 200)

        assertEquals(LearningStatus.READY, returnedNear.learning)
        assertEquals(Polarity.NEAR_IS_HIGHER, returnedNear.polarity)
        assertEquals(100, returnedNear.normalizedLevel)
        assertEquals(0, engine.observe(0f, 300).normalizedLevel)
    }

    @Test fun sparseBootNearCanPromoteAHeldChangedStateAsFarWithoutGuessingEarly() {
        val engine = ProximityLearningEngine(
            ProximityLearningEngine.Policy(
                baselineSamples = 1,
                baselineDurationMs = 0,
                farArmMs = 50,
                minimumExcursionMs = 20,
                maximumGestureMs = 500,
                changePointHoldMs = 1_000,
            ),
        )

        engine.observe(1f, 0)
        val changed = engine.observe(0f, 50)
        assertNull(changed.normalizedLevel)
        val heldFar = engine.observe(0f, 1_100)

        assertEquals(LearningStatus.READY, heldFar.learning)
        assertEquals(0, heldFar.normalizedLevel)
        assertEquals(false, heldFar.presence)
    }

    @Test fun denseHeldPlateauRestartsBaselineInsteadOfCreatingAnInvertedModel() {
        val engine = ProximityLearningEngine(
            ProximityLearningEngine.Policy(
                baselineSamples = 8,
                baselineDurationMs = 700,
                farArmMs = 50,
                minimumExcursionMs = 20,
                maximumGestureMs = 500,
                changePointHoldMs = 1_000,
            ),
        )
        repeat(8) { engine.observe(100f, it * 100L) }
        assertEquals(LearningStatus.LEARNING_EXCURSION, engine.current().learning)

        engine.observe(0f, 800)
        val restarted = engine.observe(0f, 2_100)

        assertEquals(LearningStatus.LEARNING_FAR, restarted.learning)
        assertNull(restarted.normalizedLevel)
        assertNull(restarted.presence)
        assertEquals(0L, restarted.modelSequence)
    }

    @Test fun misdeclaredContinuousSourceFallsBackToQuietSingleSampleEvidence() {
        val engine = ProximityLearningEngine()

        assertEquals(LearningStatus.LEARNING_FAR, engine.observe(254f, 0).learning)
        val nearEdge = engine.observe(255f, 2_500)
        assertEquals(LearningStatus.LEARNING_EXCURSION, nearEdge.learning)
        val returned = engine.observe(254f, 2_800)

        assertEquals(LearningStatus.READY, returned.learning)
        assertEquals(Polarity.NEAR_IS_HIGHER, returned.polarity)
        assertEquals(0, returned.normalizedLevel)
        assertTrue(returned.deliberateExample)
    }

    @Test fun partialAndTimedOutExcursionsDoNotCountAsDeliberateExamples() {
        val engine = ProximityLearningEngine()
        assertTrue(engine.warmSeed(ProximityLearningEngine.WarmSeed(100f, 0f, Mode.GRADED)))
        verifySeed(engine, far = 100f, startAt = 0)

        engine.observe(60f, 400)
        val partial = engine.observe(100f, 500)
        assertFalse(partial.deliberateExample)
        assertEquals(0, partial.deliberateExamples)

        engine.observe(100f, 800)
        engine.observe(0f, 900)
        engine.observe(0f, 4_100)
        val timedOut = engine.observe(100f, 4_200)
        assertFalse(timedOut.deliberateExample)
        assertEquals(0, timedOut.deliberateExamples)
        assertTrue(timedOut.completedExcursions >= 2)
    }

    @Test fun timedOutColdExcursionCannotCreateTheFirstModel() {
        val engine = ProximityLearningEngine(
            ProximityLearningEngine.Policy(
                baselineSamples = 8,
                baselineDurationMs = 700,
                maximumGestureMs = 500,
                changePointHoldMs = 1_000,
            ),
        )
        repeat(8) { engine.observe(100f, it * 100L) }
        engine.observe(100f, 2_900)
        engine.observe(0f, 3_000)
        val returned = engine.observe(100f, 3_700)

        assertEquals(LearningStatus.LEARNING_EXCURSION, returned.learning)
        assertNull(returned.normalizedLevel)
        assertNull(returned.presence)
        assertEquals(0L, returned.modelSequence)
        assertEquals(0, returned.deliberateExamples)
    }

    @Test fun unarmedQualifiedExcursionDoesNotCountAsADeliberateExample() {
        val engine = ProximityLearningEngine(
            ProximityLearningEngine.Policy(farArmMs = 2_000L),
        )
        assertTrue(engine.warmSeed(ProximityLearningEngine.WarmSeed(100f, 0f, Mode.GRADED)))
        verifySeed(engine, far = 100f, startAt = 0)

        engine.observe(0f, 400)
        engine.observe(0f, 500)
        val returned = engine.observe(100f, 700)

        assertEquals(LearningStatus.READY, returned.learning)
        assertFalse(returned.gesture)
        assertFalse(returned.deliberateExample)
        assertEquals(0, returned.deliberateExamples)
    }

    @Test fun productionDensePolicyRejectsDenseBaselineJitterAsLearningEvidence() {
        val engine = ProximityLearningEngine(ProximityLearningRuntime.learningPolicy(sparseSource = false))
        val denseBaselineJitter = floatArrayOf(154f, 156f, 159f, 161f, 158f, 155f, 160f, 157f)
        var output = engine.current()

        repeat(4_000) { sample ->
            output = engine.observe(
                denseBaselineJitter[sample % denseBaselineJitter.size],
                sample * 120L,
            )
        }

        assertEquals(LearningStatus.LEARNING_EXCURSION, output.learning)
        assertNull(output.normalizedLevel)
        assertNull(output.presence)
        assertEquals(0, output.completedExcursions)
        assertEquals(0L, output.modelSequence)
    }

    @Test fun productionDensePolicyLearnsARealExcursionThenKeepsDenseBaselineJitterFar() {
        val engine = ProximityLearningEngine(ProximityLearningRuntime.learningPolicy(sparseSource = false))
        val denseBaselineJitter = floatArrayOf(154f, 156f, 159f, 161f, 158f, 155f, 160f, 157f)
        var now = 0L
        repeat(260) { sample ->
            engine.observe(denseBaselineJitter[sample % denseBaselineJitter.size], now)
            now += 120L
        }

        engine.observe(105f, now)
        engine.observe(80f, now + 100L)
        engine.observe(95f, now + 200L)
        engine.observe(80f, now + 300L)
        engine.observe(158f, now + 400L)
        engine.observe(158f, now + 500L)
        val learned = engine.observe(158f, now + 650L)
        assertEquals(LearningStatus.READY, learned.learning)
        assertEquals(HealthStatus.HEALTHY, learned.health)
        assertEquals(false, learned.presence)
        assertEquals(1, learned.completedExcursions)

        now += 800L
        var steady = learned
        repeat(4_000) { sample ->
            steady = engine.observe(denseBaselineJitter[sample % denseBaselineJitter.size], now)
            now += 120L
            assertEquals(false, steady.presence)
        }
        assertEquals(LearningStatus.READY, steady.learning)
        assertEquals(1, steady.completedExcursions)
        assertEquals(1L, steady.modelSequence)
    }

    @Test fun denseColdLearnerDoesNotCompleteEpisodesFromSubDwellPulses() {
        val engine = ProximityLearningEngine(ProximityLearningRuntime.learningPolicy(sparseSource = false))
        var output = engine.current()
        var completedEpisodeSeen = false

        for (now in 0L..330_000L step 50L) {
            val raw = if (now <= 30_000L) {
                quantizedNearMajority(now)
            } else {
                pathologicalDenseRaw(now - 30_050L)
            }
            output = engine.observe(raw, now)
            completedEpisodeSeen = completedEpisodeSeen || output.completedEpisode
        }

        assertFalse(completedEpisodeSeen)
        assertFalse(output.learning == LearningStatus.READY)
        assertNull(output.presence)
        assertEquals(0, output.completedExcursions)
        assertEquals(0L, output.modelSequence)
    }

    @Test fun denseReadyLearnerDoesNotCompleteEpisodesFromSubDwellReturns() {
        val engine = ProximityLearningEngine(ProximityLearningRuntime.learningPolicy(sparseSource = false))
        assertTrue(engine.warmSeed(ProximityLearningEngine.WarmSeed(20f, 80f, Mode.GRADED)))
        for (now in 0L..300L step 50L) engine.observe(quantizedFar(now), now)
        assertEquals(LearningStatus.READY, engine.current().learning)

        var now = 350L
        while (now < 2_000L) {
            engine.observe(quantizedNearMajority(now), now)
            now += 50L
        }
        val completedBefore = engine.current().completedExcursions
        var completedEpisodeSeen = false

        val traceStartedAt = now
        while (now <= traceStartedAt + 300_000L) {
            val output = engine.observe(pathologicalDenseRaw(now - traceStartedAt), now)
            completedEpisodeSeen = completedEpisodeSeen || output.completedEpisode
            now += 50L
        }

        assertFalse(completedEpisodeSeen)
        assertEquals(completedBefore, engine.current().completedExcursions)
        assertEquals(LearningStatus.READY, engine.current().learning)
    }

    @Test fun denseLearnerCompletesOnlyAfterSustainedExcursionAndReturn() {
        val engine = ProximityLearningEngine(ProximityLearningRuntime.learningPolicy(sparseSource = false))
        for (now in 0L..30_000L step 50L) engine.observe(quantizedFar(now), now)
        assertEquals(LearningStatus.LEARNING_EXCURSION, engine.current().learning)

        for (now in 30_050L..30_350L step 50L) {
            assertFalse(engine.observe(quantizedNearMajority(now), now).completedEpisode)
        }
        for (now in 30_400L until 30_650L step 50L) {
            assertFalse("return matured before 250 ms at $now", engine.observe(quantizedFar(now), now).completedEpisode)
        }
        val completed = engine.observe(quantizedFar(30_650L), 30_650L)

        assertTrue(completed.completedEpisode)
        assertEquals(350L, completed.episodeDurationMs)
        assertEquals(LearningStatus.READY, completed.learning)
        assertEquals(1, completed.completedExcursions)
        assertEquals(1L, completed.modelSequence)
        assertEquals(false, completed.presence)
    }

    @Test fun denseReadyCycleCompletesForBothRawPolarities() {
        for ((far, near) in listOf(20f to 80f, 80f to 20f)) {
            val engine = ProximityLearningEngine(ProximityLearningRuntime.learningPolicy(sparseSource = false))
            assertTrue(engine.warmSeed(ProximityLearningEngine.WarmSeed(far, near, Mode.GRADED)))
            for (now in 0L..2_400L step 50L) engine.observe(far, now)
            assertEquals("far=$far near=$near", ActuationStatus.ARMED, engine.current().actuation)

            for (now in 2_450L..2_750L step 50L) {
                assertFalse(engine.observe(near, now).completedEpisode)
            }
            for (now in 2_800L until 3_050L step 50L) {
                assertFalse(engine.observe(far, now).completedEpisode)
            }
            val completed = engine.observe(far, 3_050L)

            assertTrue("far=$far near=$near", completed.completedEpisode)
            assertEquals(350L, completed.episodeDurationMs)
            assertEquals(false, completed.presence)
            assertTrue(completed.gesture)
            assertEquals(1L, completed.gestureSequence)
        }
    }

    @Test fun sparseLearnerKeepsImmediateEdgeSemantics() {
        val engine = ProximityLearningEngine(ProximityLearningRuntime.learningPolicy(sparseSource = true))
        assertTrue(engine.warmSeed(ProximityLearningEngine.WarmSeed(20f, 80f, Mode.BINARY)))
        assertEquals(LearningStatus.READY, engine.observe(20f, 0L).learning)

        val departure = engine.observe(80f, 100L)
        assertEquals(true, departure.presence)
        val returned = engine.observe(20f, 400L)

        assertEquals(false, returned.presence)
        assertTrue(returned.completedEpisode)
        assertEquals(300L, returned.episodeDurationMs)
        assertEquals(1, returned.completedExcursions)
    }

    @Test fun empiricallySparseHalCompletesOneEdgeReturnUnderProductionPolicy() {
        val engine = ProximityLearningEngine(ProximityLearningRuntime.learningPolicy(sparseSource = false))
        engine.observe(20f, 0L, continuousEvidence = true)

        // A quiet cadence window admits the first changed edge as sparse. SensorReporter then opens
        // a new cadence window, so the one return edge is conservatively marked continuous again.
        val departure = engine.observe(80f, 2_500L, continuousEvidence = false)
        assertEquals(LearningStatus.LEARNING_EXCURSION, departure.learning)
        val returned = engine.observe(20f, 2_800L, continuousEvidence = true)

        assertTrue(returned.completedEpisode)
        assertEquals(300L, returned.episodeDurationMs)
        assertEquals(LearningStatus.READY, returned.learning)
        assertEquals(1, returned.completedExcursions)
        assertEquals(false, returned.presence)
    }

    @Test fun quantizedDenseIdleCannotSelfCertifyButASeparatedWaveCanTeach() {
        val engine = ProximityLearningEngine(ProximityLearningRuntime.learningPolicy(sparseSource = false))
        val idle = floatArrayOf(
            20.25f, 19.125f, 21.375f, 18f, 20.25f, 19.125f, 20.25f,
            21.375f, 20.25f, 19.125f, 22.5f, 19.125f, 21.375f, 19.125f,
        )
        var now = 0L
        var output = engine.current()

        while (now <= 600_000L) {
            val raw = when (now) {
                240_000L -> 23.625f
                270_000L -> 16.875f
                else -> idle[((now / 40L) % idle.size).toInt()]
            }
            output = engine.observe(raw, now)
            if (now == 30_000L || now == 300_000L || now == 600_000L) {
                assertFalse(output.learning == LearningStatus.READY)
                assertNull(output.normalizedLevel)
                assertNull(output.presence)
                assertEquals(0, output.deliberateExamples)
                assertEquals(0L, output.modelSequence)
            }
            now += 40L
        }

        repeat(20) {
            output = engine.observe(19.125f, now)
            now += 100L
        }
        for (raw in floatArrayOf(35f, 55f, 84f, 84f, 55f, 35f, 19.125f, 19.125f, 19.125f, 19.125f)) {
            output = engine.observe(raw, now)
            now += 100L
        }

        assertEquals(LearningStatus.READY, output.learning)
        assertEquals(Mode.GRADED, output.mode)
        assertEquals(Polarity.NEAR_IS_HIGHER, output.polarity)
        assertEquals(false, output.presence)
        assertEquals(1L, output.modelSequence)
        assertEquals(true, engine.observe(84f, now).presence)
    }

    @Test fun wideStartupNoiseThenNarrowIdleCannotDecayIntoAFalseModel() {
        val engine = ProximityLearningEngine(ProximityLearningRuntime.learningPolicy(sparseSource = false))
        val startup = floatArrayOf(
            477f, 481f, 484f, 480f, 487f, 482f, 485f, 479f,
            483f, 480f, 486f, 481f, 484f, 478f, 482f, 485f,
        )
        val narrowIdle = floatArrayOf(479f, 480f, 481f, 482f, 480f, 481f, 480f, 482f)
        var now = 0L
        var output = engine.current()

        while (now <= 30_000L) {
            output = engine.observe(startup[((now / 150L) % startup.size).toInt()], now)
            now += 150L
        }
        while (now < 586_858L) {
            val raw = if (now == 150_000L) 488f else narrowIdle[((now / 150L) % narrowIdle.size).toInt()]
            output = engine.observe(raw, now)
            now += 150L
        }
        val fatalIdleExcursion = arrayOf(
            586_858L to 478f,
            586_948L to 480f,
            587_072L to 481f,
            587_166L to 485f,
            587_258L to 483f,
            587_378L to 484f,
            587_472L to 485f,
            587_564L to 486f,
            587_774L to 485f,
            587_895L to 484f,
            587_984L to 485f,
            588_104L to 486f,
            588_195L to 485f,
            588_311L to 480f,
        )
        for ((at, raw) in fatalIdleExcursion) output = engine.observe(raw, at)

        assertEquals(LearningStatus.LEARNING_EXCURSION, output.learning)
        assertNull(output.normalizedLevel)
        assertNull(output.presence)
        assertEquals(0, output.deliberateExamples)
        assertEquals(0L, output.modelSequence)

        val separatedWave = arrayOf(
            590_000L to 480f,
            590_500L to 480f,
            591_000L to 512f,
            591_100L to 544f,
            591_200L to 512f,
            591_300L to 512f,
            591_500L to 480f,
            591_600L to 480f,
            591_750L to 480f,
        )
        for ((at, raw) in separatedWave) output = engine.observe(raw, at)

        assertEquals(LearningStatus.READY, output.learning)
        assertEquals(Mode.GRADED, output.mode)
        assertEquals(Polarity.NEAR_IS_HIGHER, output.polarity)
        assertEquals(false, output.presence)
        assertEquals(1L, output.modelSequence)
    }

    private fun learnBaseline(engine: ProximityLearningEngine, far: Float) {
        for (i in 0 until 8) engine.observe(far, i * 100L)
        assertEquals(LearningStatus.LEARNING_EXCURSION, engine.current().learning)
    }

    private fun teachBinaryExcursion(
        engine: ProximityLearningEngine,
        far: Float,
        near: Float,
        startAt: Long,
    ) {
        engine.observe(near, startAt)
        engine.observe(near, startAt + 100)
        val learned = engine.observe(far, startAt + 200)
        assertEquals(LearningStatus.READY, learned.learning)
        assertFalse(learned.gesture)
    }

    private fun verifySeed(engine: ProximityLearningEngine, far: Float, startAt: Long) {
        engine.observe(far, startAt)
        engine.observe(far, startAt + 100)
        engine.observe(far, startAt + 200)
        engine.observe(far, startAt + 300)
        assertEquals(LearningStatus.READY, engine.current().learning)
    }

    private fun restoredGradedEngine(): ProximityLearningEngine {
        val engine = ProximityLearningEngine(ProximityLearningRuntime.learningPolicy(sparseSource = false))
        assertTrue(
            engine.restore(
                ProximityLearningEngine.Snapshot(
                    farRaw = 37.8244f,
                    nearRaw = 69.0891f,
                    noise = 1.392f,
                    mode = Mode.GRADED,
                    polarity = Polarity.NEAR_IS_HIGHER,
                    completedExcursions = 119,
                ),
            ),
        )
        verifySeed(engine, far = 37.8244f, startAt = 0)
        return engine
    }

    private val quantizedFarValues = floatArrayOf(19f, 20f, 21f, 20f)
    private val quantizedNearMajorityValues = floatArrayOf(79f, 80f, 81f, 80f)

    private fun pathologicalDenseRaw(traceElapsedMs: Long): Float =
        if (traceElapsedMs >= 0L && traceElapsedMs % 1_000L < 200L) {
            quantizedFar(traceElapsedMs)
        } else {
            quantizedNearMajority(traceElapsedMs)
        }

    private fun quantizedFar(now: Long): Float =
        quantizedFarValues[((now / 50L) % quantizedFarValues.size).toInt()]

    private fun quantizedNearMajority(now: Long): Float =
        quantizedNearMajorityValues[((now / 50L) % quantizedNearMajorityValues.size).toInt()]
}
