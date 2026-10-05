package io.panelassistant.android.control

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.Test

class AutoSleepPolicyTest {
    @Test fun `policy and discovery use the same safety floor`() {
        assertEquals(
            MIN_AUTO_SLEEP_LEASE_MS,
            io.panelassistant.android.sensors.MIN_AUTO_SLEEP_LEASE_MS,
        )
    }

    @Test
    fun `pure aggregate reduction preserves its input and captures coalesced activity`() {
        val initial = AutoSleepPolicyReducer.initial(
            setOf("one", "two"),
            AutoSleepPolicyConfig(15 * MINUTE),
        )
        val hydrated = AutoSleepPolicyReducer.reduce(
            initial,
            AutoSleepEvent.SourcesHydrated(
                atMs = 0L,
                states = mapOf("one" to AutoSleepSourceState.OFF, "two" to AutoSleepSourceState.OFF),
                feed = AutoSleepFeedPosition(1L, 1L),
            ),
        )
        val activity = AutoSleepPolicyReducer.reduce(
            hydrated.state,
            AutoSleepEvent.SourcesHydrated(
                atMs = 10 * MINUTE,
                states = hydrated.state.sources,
                feed = AutoSleepFeedPosition(1L, 2L),
                activityMarker = AutoSleepActivityMarker(1L, 9 * MINUTE),
            ),
        )

        assertNull(initial.lastEventAtMs)
        assertEquals(15 * MINUTE, hydrated.decision.nextDeadlineMs)
        assertEquals(24 * MINUTE, activity.decision.nextDeadlineMs)
        assertNotEquals(hydrated.state, activity.state)

        val duplicate = AutoSleepPolicyReducer.reduce(
            activity.state,
            AutoSleepEvent.SourcesHydrated(
                11 * MINUTE,
                activity.state.sources,
                AutoSleepFeedPosition(1L, 2L),
                AutoSleepActivityMarker(1L, 9 * MINUTE),
            ),
        )
        assertEquals(24 * MINUTE, duplicate.decision.nextDeadlineMs)

        val distinctSameMillisecond = AutoSleepPolicyReducer.reduce(
            duplicate.state,
            AutoSleepEvent.SourcesHydrated(
                12 * MINUTE,
                duplicate.state.sources,
                AutoSleepFeedPosition(1L, 3L),
                AutoSleepActivityMarker(2L, 9 * MINUTE),
            ),
        )
        assertEquals(2L, distinctSameMillisecond.state.activityMarker?.sequence)
        assertEquals(24 * MINUTE, distinctSameMillisecond.decision.nextDeadlineMs)

        val alreadyExpired = AutoSleepPolicyReducer.reduce(
            distinctSameMillisecond.state,
            AutoSleepEvent.SourcesHydrated(
                30 * MINUTE,
                distinctSameMillisecond.state.sources,
                AutoSleepFeedPosition(1L, 4L),
                AutoSleepActivityMarker(3L, 9 * MINUTE),
            ),
        )
        assertEquals(AutoSleepOutput.ALLOW_SLEEP, alreadyExpired.decision.output)
        assertNull(alreadyExpired.decision.nextDeadlineMs)
    }

    @Test
    fun `aggregate activity marker must use the same monotonic clock`() {
        val initial = AutoSleepPolicyReducer.initial(setOf("one"))
        val observed = AutoSleepPolicyReducer.reduce(
            initial,
            AutoSleepEvent.SourcesHydrated(
                100L,
                mapOf("one" to AutoSleepSourceState.OFF),
                AutoSleepFeedPosition(1L, 1L),
                AutoSleepActivityMarker(1L, 90L),
            ),
        ).state

        assertFailsWith<IllegalArgumentException> {
            AutoSleepPolicyReducer.reduce(
                observed,
                AutoSleepEvent.SourcesHydrated(
                    101L,
                    observed.sources,
                    AutoSleepFeedPosition(1L, 2L),
                    AutoSleepActivityMarker(0L, 91L),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AutoSleepPolicyReducer.reduce(
                observed,
                AutoSleepEvent.SourcesHydrated(
                    101L,
                    observed.sources,
                    AutoSleepFeedPosition(1L, 2L),
                    AutoSleepActivityMarker(2L, 89L),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AutoSleepPolicyReducer.reduce(
                observed,
                AutoSleepEvent.SourcesHydrated(
                    101L,
                    observed.sources,
                    AutoSleepFeedPosition(1L, 2L),
                    AutoSleepActivityMarker(2L, 102L),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AutoSleepPolicyReducer.reduce(
                observed,
                AutoSleepEvent.SourcesHydrated(
                    101L,
                    observed.sources,
                    AutoSleepFeedPosition(1L, 0L),
                    AutoSleepActivityMarker(1L, 90L),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AutoSleepPolicyReducer.reduce(
                observed,
                AutoSleepEvent.SourcesHydrated(
                    101L,
                    mapOf("one" to AutoSleepSourceState.ON),
                    AutoSleepFeedPosition(1L, 1L),
                    AutoSleepActivityMarker(1L, 90L),
                ),
            )
        }
    }

    @Test
    fun `new feed generation rehydrates unchanged sources while duplicate snapshots remain idempotent`() {
        val initial = AutoSleepPolicyReducer.initial(setOf("one"))
        val first = AutoSleepPolicyReducer.reduce(
            initial,
            AutoSleepEvent.SourcesHydrated(
                0L,
                mapOf("one" to AutoSleepSourceState.OFF),
                AutoSleepFeedPosition(1L, 1L),
            ),
        )
        val expired = AutoSleepPolicyReducer.reduce(first.state, AutoSleepEvent.TimeAdvanced(16 * MINUTE))
        assertEquals(AutoSleepOutput.ALLOW_SLEEP, expired.decision.output)

        val rehydrated = AutoSleepPolicyReducer.reduce(
            expired.state,
            AutoSleepEvent.SourcesHydrated(
                20 * MINUTE,
                expired.state.sources,
                AutoSleepFeedPosition(2L, 0L),
            ),
        )
        assertEquals(30 * MINUTE, rehydrated.decision.nextDeadlineMs)

        val duplicate = AutoSleepPolicyReducer.reduce(
            rehydrated.state,
            AutoSleepEvent.SourcesHydrated(
                21 * MINUTE,
                rehydrated.state.sources,
                AutoSleepFeedPosition(2L, 0L),
            ),
        )
        assertEquals(30 * MINUTE, duplicate.decision.nextDeadlineMs)
        assertFailsWith<IllegalArgumentException> {
            AutoSleepPolicyReducer.reduce(
                duplicate.state,
                AutoSleepEvent.SourcesHydrated(
                    22 * MINUTE,
                    duplicate.state.sources,
                    AutoSleepFeedPosition(1L, 2L),
                ),
            )
        }
    }

    @Test
    fun `partial loss and recovery each lease once`() {
        val initial = AutoSleepPolicyReducer.initial(setOf("one", "two"))
        val partialStates = mapOf("one" to AutoSleepSourceState.OFF, "two" to AutoSleepSourceState.UNAVAILABLE)
        val partial = AutoSleepPolicyReducer.reduce(
            initial,
            AutoSleepEvent.SourcesHydrated(0L, partialStates, AutoSleepFeedPosition(1L, 1L)),
        )
        assertEquals(60 * MINUTE, partial.decision.nextDeadlineMs)

        val duplicate = AutoSleepPolicyReducer.reduce(
            partial.state,
            AutoSleepEvent.SourcesHydrated(10 * MINUTE, partialStates, AutoSleepFeedPosition(1L, 2L)),
        )
        assertEquals(60 * MINUTE, duplicate.decision.nextDeadlineMs)
        val expired = AutoSleepPolicyReducer.reduce(duplicate.state, AutoSleepEvent.TimeAdvanced(60 * MINUTE))
        val recoveredStates = partialStates + ("two" to AutoSleepSourceState.OFF)
        val recovered = AutoSleepPolicyReducer.reduce(
            expired.state,
            AutoSleepEvent.SourcesHydrated(61 * MINUTE, recoveredStates, AutoSleepFeedPosition(1L, 3L)),
        )
        assertEquals(71 * MINUTE, recovered.decision.nextDeadlineMs)
        val recoveredDuplicate = AutoSleepPolicyReducer.reduce(
            recovered.state,
            AutoSleepEvent.SourcesHydrated(62 * MINUTE, recoveredStates, AutoSleepFeedPosition(1L, 4L)),
        )
        assertEquals(71 * MINUTE, recoveredDuplicate.decision.nextDeadlineMs)
    }

    @Test
    fun `learned lease changes are future only and cannot lower the correction floor`() {
        val initial = AutoSleepPolicyReducer.initial(setOf("one"))
        val hydrated = AutoSleepPolicyReducer.reduce(
            initial,
            AutoSleepEvent.SourcesHydrated(
                0L, mapOf("one" to AutoSleepSourceState.OFF), AutoSleepFeedPosition(1L, 1L),
            ),
        )
        val learned = AutoSleepPolicyReducer.reduce(
            hydrated.state,
            AutoSleepEvent.LearnedLeaseChanged(MINUTE, 30 * MINUTE),
        )
        assertEquals(10 * MINUTE, learned.decision.nextDeadlineMs)
        val activity = AutoSleepPolicyReducer.reduce(
            learned.state,
            AutoSleepEvent.SourcesHydrated(
                2 * MINUTE,
                learned.state.sources,
                AutoSleepFeedPosition(1L, 2L),
                activityMarker = AutoSleepActivityMarker(1L, 2 * MINUTE),
            ),
        )
        assertEquals(32 * MINUTE, activity.decision.nextDeadlineMs)
        val expired = AutoSleepPolicyReducer.reduce(activity.state, AutoSleepEvent.TimeAdvanced(32 * MINUTE))
        val slept = AutoSleepPolicyReducer.reduce(expired.state, AutoSleepEvent.AutomaticSleepRecorded(32 * MINUTE))
        val corrected = AutoSleepPolicyReducer.reduce(slept.state, AutoSleepEvent.Touch(33 * MINUTE, true))
        assertEquals(35 * MINUTE, corrected.decision.effectiveLeaseMs)
        val lowered = AutoSleepPolicyReducer.reduce(
            corrected.state,
            AutoSleepEvent.LearnedLeaseChanged(34 * MINUTE, 15 * MINUTE),
        )
        assertEquals(35 * MINUTE, lowered.decision.effectiveLeaseMs)
        assertEquals(68 * MINUTE, lowered.decision.nextDeadlineMs)
    }

    @Test
    fun `touch correction requires controller proof that the touch woke an owned automatic sleep`() {
        val initial = AutoSleepPolicyReducer.initial(setOf("one"))
        val active = AutoSleepPolicyReducer.reduce(
            initial,
            AutoSleepEvent.SourcesHydrated(
                0L, mapOf("one" to AutoSleepSourceState.ON), AutoSleepFeedPosition(1L, 1L),
            ),
        )
        val rejectedSleep = AutoSleepPolicyReducer.reduce(
            active.state,
            AutoSleepEvent.AutomaticSleepRecorded(MINUTE),
        )
        assertNull(rejectedSleep.state.lastAutomaticSleepMs)
        val inactive = AutoSleepPolicyReducer.reduce(
            rejectedSleep.state,
            AutoSleepEvent.SourcesHydrated(
                2 * MINUTE, mapOf("one" to AutoSleepSourceState.OFF), AutoSleepFeedPosition(1L, 2L),
            ),
        )
        val touch = AutoSleepPolicyReducer.reduce(inactive.state, AutoSleepEvent.Touch(3 * MINUTE))
        assertEquals(10 * MINUTE, touch.decision.effectiveLeaseMs)

        val sleeping = acceptedAutomaticSleepState()
        val screenWake = AutoSleepPolicyReducer.reduce(sleeping, AutoSleepEvent.ScreenWoken(16 * MINUTE))
        val afterScreenWake = AutoSleepPolicyReducer.reduce(
            screenWake.state,
            AutoSleepEvent.Touch(16 * MINUTE + 30_000L),
        )
        assertEquals(10 * MINUTE, afterScreenWake.decision.effectiveLeaseMs)

        val sourceWake = AutoSleepPolicyReducer.reduce(
            sleeping,
            AutoSleepEvent.SourcesHydrated(
                16 * MINUTE, mapOf("one" to AutoSleepSourceState.ON), AutoSleepFeedPosition(1L, 2L),
            ),
        )
        val afterSourceWake = AutoSleepPolicyReducer.reduce(
            sourceWake.state,
            AutoSleepEvent.Touch(16 * MINUTE + 30_000L),
        )
        assertEquals(10 * MINUTE, afterSourceWake.decision.effectiveLeaseMs)

        val proximityWake = AutoSleepPolicyReducer.reduce(
            sleeping,
            AutoSleepEvent.QualifiedProximity(16 * MINUTE),
        )
        val afterProximityWake = AutoSleepPolicyReducer.reduce(
            proximityWake.state,
            AutoSleepEvent.Touch(16 * MINUTE + 30_000L),
        )
        assertEquals(10 * MINUTE, afterProximityWake.decision.effectiveLeaseMs)

        val reconnectWake = AutoSleepPolicyReducer.reduce(
            sleeping,
            AutoSleepEvent.SourcesHydrated(
                16 * MINUTE,
                sleeping.sources,
                AutoSleepFeedPosition(2L, 0L),
            ),
        )
        val afterReconnectWake = AutoSleepPolicyReducer.reduce(
            reconnectWake.state,
            AutoSleepEvent.Touch(16 * MINUTE + 30_000L),
        )
        assertEquals(10 * MINUTE, afterReconnectWake.decision.effectiveLeaseMs)

        val ownedWake = AutoSleepPolicyReducer.reduce(sleeping, AutoSleepEvent.Touch(16 * MINUTE, true))
        assertEquals(15 * MINUTE, ownedWake.decision.effectiveLeaseMs)
    }

    @Test
    fun `atomic hydration of healthy inactive sources starts the learned lease not partial-loss maximum`() {
        val policy = TestAutoSleepPolicy(setOf("one", "two"), AutoSleepPolicyConfig(20 * MINUTE))

        val decision = policy.transition(AutoSleepEvent.SourcesHydrated(
            1_000,
            mapOf("one" to AutoSleepSourceState.OFF, "two" to AutoSleepSourceState.OFF),
            AutoSleepFeedPosition(1L, 1L),
        ))

        assertEquals(AutoSleepReason.SOURCE_ACTIVITY_LEASE, decision.reason)
        assertEquals(20 * MINUTE, decision.effectiveLeaseMs)
        assertEquals(1_000 + 20 * MINUTE, decision.nextDeadlineMs)
    }

    @Test fun noSourcesOrNoUsableSourcesInhibitSleep() {
        val empty = TestAutoSleepPolicy(emptySet()).advanceTo(0L)
        assertEquals(AutoSleepOutput.INHIBITED, empty.output)
        assertEquals(AutoSleepReason.NO_SOURCES_CONFIGURED, empty.reason)

        val unavailable = TestAutoSleepPolicy(setOf("binary_sensor.activity")).advanceTo(0L)
        assertEquals(AutoSleepOutput.INHIBITED, unavailable.output)
        assertEquals(AutoSleepReason.ALL_SOURCES_UNAVAILABLE, unavailable.reason)
    }

    @Test fun activeSourceHardHoldsThenAllOffStartsLearnedLease() {
        val policy = TestAutoSleepPolicy(
            setOf("binary_sensor.activity"),
            AutoSleepPolicyConfig(learnedLeaseMs = 20 * MINUTE),
        )
        val active = policy.transition(source(0L, AutoSleepSourceState.ON))
        assertEquals(AutoSleepOutput.HOLD_AWAKE, active.output)
        assertEquals(AutoSleepReason.SOURCE_ACTIVE, active.reason)
        assertNull(active.nextDeadlineMs)

        val inactive = policy.transition(source(MINUTE, AutoSleepSourceState.OFF))
        assertEquals(AutoSleepOutput.HOLD_AWAKE, inactive.output)
        assertEquals(21 * MINUTE, inactive.nextDeadlineMs)
        assertEquals(AutoSleepOutput.HOLD_AWAKE, policy.advanceTo(21 * MINUTE - 1).output)
        assertEquals(AutoSleepOutput.ALLOW_SLEEP, policy.advanceTo(21 * MINUTE).output)
    }

    @Test fun oneUnavailableSourceForcesSixtyMinuteLeaseWhileHealthySourcesRemain() {
        val policy = TestAutoSleepPolicy(
            setOf("binary_sensor.first", "binary_sensor.second"),
            AutoSleepPolicyConfig(learnedLeaseMs = 15 * MINUTE),
        )
        val partial = policy.transition(
            AutoSleepEvent.SourcesHydrated(
                0L,
                mapOf(
                    "binary_sensor.first" to AutoSleepSourceState.OFF,
                    "binary_sensor.second" to AutoSleepSourceState.UNAVAILABLE,
                ),
                AutoSleepFeedPosition(1L, 1L),
            ),
        )
        assertEquals(AutoSleepOutput.HOLD_AWAKE, partial.output)
        assertEquals(AutoSleepReason.PARTIAL_SOURCE_LOSS, partial.reason)
        assertEquals(60 * MINUTE, partial.effectiveLeaseMs)
        assertEquals(60 * MINUTE, partial.nextDeadlineMs)
        assertEquals(AutoSleepOutput.ALLOW_SLEEP, policy.advanceTo(60 * MINUTE).output)

        val lost = policy.transition(
            AutoSleepEvent.SourcesHydrated(
                61 * MINUTE,
                mapOf(
                    "binary_sensor.first" to AutoSleepSourceState.UNAVAILABLE,
                    "binary_sensor.second" to AutoSleepSourceState.UNAVAILABLE,
                ),
                AutoSleepFeedPosition(1L, 2L),
            ),
        )
        assertEquals(AutoSleepOutput.INHIBITED, lost.output)
    }

    @Test fun touchResetsFullLeaseAndQualifiedProximityOnlyExtendsBriefly() {
        val policy = TestAutoSleepPolicy(
            setOf("binary_sensor.activity"),
            AutoSleepPolicyConfig(
                learnedLeaseMs = 20 * MINUTE,
                qualifiedProximityExtensionMs = 5 * MINUTE,
            ),
        )
        policy.transition(source(0L, AutoSleepSourceState.OFF))
        assertEquals(20 * MINUTE, policy.transition(AutoSleepEvent.Touch(0L)).nextDeadlineMs)
        assertEquals(30 * MINUTE, policy.transition(AutoSleepEvent.Touch(10 * MINUTE)).nextDeadlineMs)

        val proximity = policy.transition(AutoSleepEvent.QualifiedProximity(31 * MINUTE))
        assertEquals(AutoSleepReason.PROXIMITY_ACTIVITY, proximity.reason)
        assertEquals(36 * MINUTE, proximity.nextDeadlineMs)
        assertEquals(AutoSleepOutput.ALLOW_SLEEP, policy.advanceTo(36 * MINUTE).output)
    }

    @Test fun learnedLeaseAndProximityExtensionAreBounded() {
        val short = TestAutoSleepPolicy(
            setOf("binary_sensor.activity"),
            AutoSleepPolicyConfig(learnedLeaseMs = 1L, qualifiedProximityExtensionMs = Long.MAX_VALUE),
        )
        val off = short.transition(source(0L, AutoSleepSourceState.OFF))
        assertEquals(10 * MINUTE, off.effectiveLeaseMs)
        assertEquals(10 * MINUTE, off.nextDeadlineMs)
        val proximity = short.transition(AutoSleepEvent.QualifiedProximity(16 * MINUTE))
        assertEquals(46 * MINUTE, proximity.nextDeadlineMs)

        val long = TestAutoSleepPolicy(
            setOf("binary_sensor.activity"),
            AutoSleepPolicyConfig(learnedLeaseMs = Long.MAX_VALUE),
        )
        assertEquals(60 * MINUTE, long.transition(source(0L, AutoSleepSourceState.OFF)).effectiveLeaseMs)
    }

    @Test fun promptTouchAfterAutomaticSleepRaisesCorrectionByFiveMinutes() {
        val policy = TestAutoSleepPolicy(
            setOf("binary_sensor.activity"),
            AutoSleepPolicyConfig(learnedLeaseMs = 15 * MINUTE),
        )
        policy.transition(source(0L, AutoSleepSourceState.OFF))
        policy.advanceTo(15 * MINUTE)
        policy.transition(AutoSleepEvent.AutomaticSleepRecorded(15 * MINUTE))

        val corrected = policy.transition(AutoSleepEvent.Touch(16 * MINUTE, true))
        assertEquals(20 * MINUTE, corrected.effectiveLeaseMs)
        assertEquals(36 * MINUTE, corrected.nextDeadlineMs)

        policy.advanceTo(36 * MINUTE)
        policy.transition(AutoSleepEvent.AutomaticSleepRecorded(36 * MINUTE))
        val correctedAgain = policy.transition(AutoSleepEvent.Touch(37 * MINUTE, true))
        assertEquals(25 * MINUTE, correctedAgain.effectiveLeaseMs)
    }

    @Test fun lateTouchDoesNotRaiseCorrection() {
        val policy = TestAutoSleepPolicy(setOf("binary_sensor.activity"))
        policy.transition(source(0L, AutoSleepSourceState.OFF))
        policy.advanceTo(15 * MINUTE)
        policy.transition(AutoSleepEvent.AutomaticSleepRecorded(15 * MINUTE))
        val decision = policy.transition(AutoSleepEvent.Touch(18 * MINUTE, true))
        assertEquals(10 * MINUTE, decision.effectiveLeaseMs)
    }

    @Test fun explicitWakeStartsAFullLeaseWithoutLearningAPrematureSleepCorrection() {
        val policy = TestAutoSleepPolicy(setOf("binary_sensor.activity"))
        policy.transition(source(0L, AutoSleepSourceState.OFF))
        policy.advanceTo(15 * MINUTE)
        policy.transition(AutoSleepEvent.AutomaticSleepRecorded(15 * MINUTE))

        val decision = policy.transition(AutoSleepEvent.ScreenWoken(16 * MINUTE))

        assertEquals(10 * MINUTE, decision.effectiveLeaseMs)
        assertEquals(26 * MINUTE, decision.nextDeadlineMs)
    }

    @Test fun manualAuthorityGatesActuationWithoutChangingEvidenceOutput() {
        val policy = TestAutoSleepPolicy(setOf("binary_sensor.activity"))
        val hold = policy.transition(source(0L, AutoSleepSourceState.ON))
        assertEquals(AutoSleepOutput.HOLD_AWAKE, hold.output)
        assertEquals(
            AutoSleepAutomaticAction.WAKE,
            hold.actionFor(screenIsAwake = false, AutoSleepActuationGate.AUTOMATIC),
        )
        assertEquals(
            AutoSleepAutomaticAction.NONE,
            hold.actionFor(screenIsAwake = false, AutoSleepActuationGate.MANUAL_OVERRIDE),
        )

        policy.transition(source(MINUTE, AutoSleepSourceState.OFF))
        val allow = policy.advanceTo(16 * MINUTE)
        assertEquals(AutoSleepOutput.ALLOW_SLEEP, allow.output)
        assertEquals(
            AutoSleepAutomaticAction.SLEEP,
            allow.actionFor(screenIsAwake = true, AutoSleepActuationGate.AUTOMATIC),
        )
        assertEquals(
            AutoSleepAutomaticAction.NONE,
            allow.actionFor(screenIsAwake = true, AutoSleepActuationGate.MANUAL_OVERRIDE),
        )
    }

    @Test fun replayUsesTheLivePolicyAndEmitsTheExactDeadlineTransition() {
        val events = listOf(
            source(0L, AutoSleepSourceState.ON),
            source(MINUTE, AutoSleepSourceState.OFF),
            AutoSleepEvent.Touch(5 * MINUTE),
        )
        val config = AutoSleepPolicyConfig(learnedLeaseMs = 15 * MINUTE)
        val trace = AutoSleepReplay.run(
            sourceIds = setOf("binary_sensor.activity"),
            events = events,
            untilMs = 25 * MINUTE,
            config = config,
        )
        val deadline = trace.single { it.cause == AutoSleepTraceCause.DEADLINE }
        assertEquals(20 * MINUTE, deadline.atMs)
        assertEquals(AutoSleepOutput.ALLOW_SLEEP, deadline.decision.output)

        val live = TestAutoSleepPolicy(setOf("binary_sensor.activity"), config)
        events.forEach(live::transition)
        assertEquals(deadline.decision, live.advanceTo(20 * MINUTE))
        assertEquals(AutoSleepOutput.ALLOW_SLEEP, trace.last().decision.output)
    }

    @Test fun sourceBoundariesFailClosedAndLatePolicyEventsClamp() {
        val policy = TestAutoSleepPolicy(setOf("binary_sensor.activity"))
        policy.advanceTo(MINUTE)
        assertEquals(MINUTE, policy.advanceTo(MINUTE - 1).atMs)
        assertFailsWith<IllegalArgumentException> {
            policy.transition(AutoSleepEvent.SourcesHydrated(
                MINUTE,
                mapOf("binary_sensor.unknown" to AutoSleepSourceState.ON),
                AutoSleepFeedPosition(1L, 1L),
            ))
        }
        assertFailsWith<IllegalArgumentException> {
            AutoSleepReplay.run(
                sourceIds = setOf("binary_sensor.activity"),
                events = listOf(AutoSleepEvent.Touch(MINUTE), AutoSleepEvent.Touch(0L)),
                untilMs = MINUTE,
            )
        }
    }

    @Test fun `late screen wake uses current policy time and retains its lease`() {
        val initial = AutoSleepPolicyReducer.initial(setOf("binary_sensor.activity"))
        val hydrated = AutoSleepPolicyReducer.reduce(
            initial,
            AutoSleepEvent.SourcesHydrated(
                0L,
                mapOf("binary_sensor.activity" to AutoSleepSourceState.OFF),
                AutoSleepFeedPosition(1L, 1L),
            ),
        )
        val advanced = AutoSleepPolicyReducer.reduce(hydrated.state, AutoSleepEvent.TimeAdvanced(10 * MINUTE))
        val wake = AutoSleepPolicyReducer.reduce(advanced.state, AutoSleepEvent.ScreenWoken(9 * MINUTE))

        assertEquals(10 * MINUTE, wake.state.lastEventAtMs)
        assertEquals(AutoSleepOutput.HOLD_AWAKE, wake.decision.output)
        assertEquals(AutoSleepReason.TOUCH_ACTIVITY, wake.decision.reason)
        assertEquals(20 * MINUTE, wake.decision.nextDeadlineMs)
    }

    private fun source(atMs: Long, state: AutoSleepSourceState) = AutoSleepEvent.SourcesHydrated(
        atMs,
        mapOf("binary_sensor.activity" to state),
        AutoSleepFeedPosition(1L, atMs),
        AutoSleepActivityMarker(atMs, atMs).takeIf { state == AutoSleepSourceState.ON },
    )

    private fun acceptedAutomaticSleepState(): AutoSleepPolicyState {
        val initial = AutoSleepPolicyReducer.initial(setOf("one"))
        val hydrated = AutoSleepPolicyReducer.reduce(
            initial,
            AutoSleepEvent.SourcesHydrated(
                0L, mapOf("one" to AutoSleepSourceState.OFF), AutoSleepFeedPosition(1L, 1L),
            ),
        )
        val expired = AutoSleepPolicyReducer.reduce(hydrated.state, AutoSleepEvent.TimeAdvanced(15 * MINUTE))
        return AutoSleepPolicyReducer.reduce(
            expired.state,
            AutoSleepEvent.AutomaticSleepRecorded(15 * MINUTE),
        ).state
    }

    companion object {
        private const val MINUTE = 60_000L
    }
}
