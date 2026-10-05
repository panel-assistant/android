package io.panelassistant.android.control

import io.panelassistant.android.sensors.HaPresenceSelectedHistory
import io.panelassistant.android.sensors.HaPresenceTransition
import io.panelassistant.android.sensors.HaPresenceValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSleepReplayTest {
    @Test fun `warmup carries a lease into the cropped window and emits its exact deadline`() {
        val history = history(
            SOURCE to listOf(
                transition(SOURCE, 0L, HaPresenceValue.OFF),
                transition(SOURCE, 30 * MINUTE, HaPresenceValue.ON),
                transition(SOURCE, 31 * MINUTE, HaPresenceValue.OFF),
            ),
            end = 120 * MINUTE,
        )
        val trace = AutoSleepReplay.run(
            history.sourceIds,
            autoSleepHistoryEvents(history),
            untilMs = history.endEpochMs,
            fromMs = history.startEpochMs,
            config = AutoSleepPolicyConfig(60 * MINUTE),
        )
        val segments = AutoSleepReplay.segments(trace, 60 * MINUTE, 120 * MINUTE)

        assertEquals(60 * MINUTE, segments.first().startEpochMs)
        assertEquals(91 * MINUTE, segments.first().endEpochMs)
        assertEquals(AutoSleepOutput.HOLD_AWAKE, segments.first().output)
        assertEquals(91 * MINUTE, trace.single { it.cause == AutoSleepTraceCause.DEADLINE }.atMs)
        assertEquals(AutoSleepOutput.ALLOW_SLEEP, segments.last().output)
    }

    @Test fun `same-time source changes become one complete aggregate transition`() {
        val history = history(
            "binary_sensor.one" to listOf(
                transition("binary_sensor.one", 0L, HaPresenceValue.OFF),
                transition("binary_sensor.one", MINUTE, HaPresenceValue.ON),
            ),
            "binary_sensor.two" to listOf(
                transition("binary_sensor.two", 0L, HaPresenceValue.OFF),
                transition("binary_sensor.two", MINUTE, HaPresenceValue.UNAVAILABLE),
            ),
            end = 2 * MINUTE,
        )

        val events = autoSleepHistoryEvents(history)

        assertEquals(listOf(0L, MINUTE), events.map(AutoSleepEvent::atMs))
        val simultaneous = events.last() as AutoSleepEvent.SourcesHydrated
        assertEquals(AutoSleepSourceState.ON, simultaneous.states["binary_sensor.one"])
        assertEquals(AutoSleepSourceState.UNAVAILABLE, simultaneous.states["binary_sensor.two"])
        assertEquals(MINUTE, simultaneous.activityMarker?.atMs)
    }

    @Test fun `all unavailable history is represented as inhibited rather than guessed inactive`() {
        val history = history(
            SOURCE to listOf(transition(SOURCE, 0L, HaPresenceValue.UNAVAILABLE)),
            end = 10 * MINUTE,
        )
        val trace = AutoSleepReplay.run(
            history.sourceIds, autoSleepHistoryEvents(history), history.endEpochMs,
            fromMs = history.startEpochMs,
        )
        val segments = AutoSleepReplay.segments(trace, 2 * MINUTE, history.endEpochMs)

        assertEquals(1, segments.size)
        assertEquals(AutoSleepOutput.INHIBITED, segments.single().output)
        assertEquals(AutoSleepReason.ALL_SOURCES_UNAVAILABLE, segments.single().reason)
        assertTrue(segments.single().startEpochMs >= 2 * MINUTE)
    }

    @Test fun `minute projection samples bucket starts and remains bounded`() {
        val events = mutableListOf<AutoSleepEvent>()
        val states = mapOf(SOURCE to AutoSleepSourceState.OFF)
        repeat(1_000) { index ->
            events += AutoSleepEvent.SourcesHydrated(
                atMs = index * 20_000L,
                states = states,
                feed = AutoSleepFeedPosition(0L, index.toLong()),
            )
        }
        val end = 6L * 60L * MINUTE
        val trace = AutoSleepReplay.run(setOf(SOURCE), events, end)
        val segments = AutoSleepReplay.minuteSegments(trace, 0L, end)

        assertTrue(segments.size <= 360)
        assertEquals(0L, segments.first().startEpochMs)
        assertEquals(end, segments.last().endEpochMs)

        val unaligned = listOf(
            AutoSleepTracePoint(0L, AutoSleepTraceCause.EVENT, decision(AutoSleepOutput.HOLD_AWAKE)),
            AutoSleepTracePoint(90_001L, AutoSleepTraceCause.DEADLINE, decision(AutoSleepOutput.ALLOW_SLEEP)),
            AutoSleepTracePoint(3 * MINUTE, AutoSleepTraceCause.END, decision(AutoSleepOutput.ALLOW_SLEEP)),
        )
        val sampled = AutoSleepReplay.minuteSegments(unaligned, 0L, 3 * MINUTE)
        assertEquals(2 * MINUTE, sampled.last().startEpochMs)
        assertEquals(AutoSleepOutput.ALLOW_SLEEP, sampled.last().output)

        val dayEnd = 24L * 60L * MINUTE
        val alternating = (0..1_440).map { minute ->
            val output = if (minute % 2 == 0) AutoSleepOutput.HOLD_AWAKE else AutoSleepOutput.ALLOW_SLEEP
            AutoSleepTracePoint(minute * MINUTE, AutoSleepTraceCause.EVENT, decision(output))
        }
        assertEquals(1_440, AutoSleepReplay.minuteSegments(alternating, 0L, dayEnd).size)
    }

    @Test fun `source lanes carry warmup state crop changes and retain every source`() {
        val sources = (1..40).map { index ->
            val id = "binary_sensor.motion_$index"
            id to listOf(
                transition(id, 0L, HaPresenceValue.OFF),
                transition(id, 30 * MINUTE, HaPresenceValue.ON),
                transition(id, 61 * MINUTE, HaPresenceValue.OFF),
            )
        }.toTypedArray()
        val history = history(*sources, end = 120 * MINUTE)

        val lanes = AutoSleepReplay.sourceSegments(history, 60 * MINUTE, 120 * MINUTE)

        assertEquals(40, lanes.size)
        lanes.values.forEach { segments ->
            assertEquals(HaPresenceValue.ON, segments.first().value)
            assertEquals(60 * MINUTE, segments.first().startEpochMs)
            assertEquals(61 * MINUTE, segments.first().endEpochMs)
            assertEquals(HaPresenceValue.OFF, segments.last().value)
            assertEquals(120 * MINUTE, segments.last().endEpochMs)
        }
    }

    @Test fun `bucketed source lane preserves a short detected pulse`() {
        val history = history(
            SOURCE to listOf(
                transition(SOURCE, 0L, HaPresenceValue.OFF),
                transition(SOURCE, 70_000L, HaPresenceValue.ON),
                transition(SOURCE, 75_000L, HaPresenceValue.OFF),
            ),
            end = 3 * MINUTE,
        )

        val segments = AutoSleepReplay.bucketSourceSegments(history, 0L, 3 * MINUTE, 60_000L)
            .getValue(SOURCE)

        assertEquals(listOf(HaPresenceValue.OFF, HaPresenceValue.ON, HaPresenceValue.OFF), segments.map { it.value })
    }

    @Test fun `bucketed source lane exposes partial unavailability instead of claiming clear`() {
        val history = history(
            SOURCE to listOf(
                transition(SOURCE, 0L, HaPresenceValue.OFF),
                transition(SOURCE, 70_000L, HaPresenceValue.UNAVAILABLE),
                transition(SOURCE, 75_000L, HaPresenceValue.OFF),
            ),
            end = 3 * MINUTE,
        )

        val segments = AutoSleepReplay.bucketSourceSegments(history, 0L, 3 * MINUTE, 60_000L)
            .getValue(SOURCE)

        assertEquals(
            listOf(HaPresenceValue.OFF, HaPresenceValue.UNAVAILABLE, HaPresenceValue.OFF),
            segments.map { it.value },
        )
    }

    private fun history(
        vararg sources: Pair<String, List<HaPresenceTransition>>,
        end: Long,
    ) = HaPresenceSelectedHistory(
        sourceIds = sources.mapTo(linkedSetOf()) { it.first },
        startEpochMs = 0L,
        endEpochMs = end,
        transitions = sources.toMap(),
    )

    private fun transition(entity: String, at: Long, value: HaPresenceValue) =
        HaPresenceTransition(entity, at, value)

    private fun decision(output: AutoSleepOutput) = AutoSleepDecision(
        0L,
        output,
        if (output == AutoSleepOutput.ALLOW_SLEEP) AutoSleepReason.LEASE_EXPIRED
        else AutoSleepReason.SOURCE_ACTIVITY_LEASE,
        null,
        15 * MINUTE,
        1,
        0,
    )

    private companion object {
        const val SOURCE = "binary_sensor.presence"
        const val MINUTE = 60_000L
    }
}
