package io.panelassistant.android.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive duty cycle, driven entirely by an explicit clock.
 *
 * The policy under test is an asymmetry: earning a wider interval takes a sustained clean history,
 * and losing it takes one bad burst. These traces pin both directions and the boundaries between.
 */
class PathProbeScheduleTest {
    private val schedule = PathProbeSchedule()
    private val min = PathProbeSchedule.MIN_INTERVAL_MS
    private val max = PathProbeSchedule.MAX_INTERVAL_MS
    private val toWiden = PathProbeSchedule.CLEAN_BURSTS_TO_WIDEN

    /** Run [n] clean bursts from [startMs], returning the clock after the last one. */
    private fun clean(startMs: Long, n: Int): Long {
        var t = startMs
        repeat(n) {
            schedule.started(t)
            schedule.completed(t, clean = true)
            t += schedule.currentIntervalMs
        }
        return t
    }

    @Test fun theFirstBurstIsDueImmediatelyBecauseNothingIsKnownYet() {
        // A panel that has just started measuring knows nothing about its path, and the moment a user
        // is most likely to be looking is exactly then. Waiting a full interval to find out is wrong.
        assertTrue(schedule.due(0L))
        assertEquals(min, schedule.currentIntervalMs)
    }

    @Test fun aBurstIsNotDueAgainUntilItsIntervalHasElapsed() {
        schedule.started(0L)
        schedule.completed(0L, clean = true)
        assertFalse(schedule.due(min - 1L))
        assertTrue(schedule.due(min))
    }

    @Test fun theIntervalWidensOnlyAfterAWholeRunOfCleanBurstsAndThenDoubles() {
        var t = clean(0L, toWiden - 1)
        assertEquals("a partial run must not widen anything", min, schedule.currentIntervalMs)
        assertEquals(toWiden - 1, schedule.cleanRun)
        t = clean(t, 1)
        assertEquals(min * 2, schedule.currentIntervalMs)
        assertEquals("the run resets after widening", 0, schedule.cleanRun)
        clean(t, toWiden)
        assertEquals(min * 4, schedule.currentIntervalMs)
    }

    @Test fun theIntervalNeverGrowsPastTheCeiling() {
        var t = 0L
        // Far more clean bursts than the ceiling needs.
        repeat(40) { t = clean(t, toWiden) }
        assertEquals(max, schedule.currentIntervalMs)
        // And it stays there rather than overflowing past it.
        clean(t, toWiden)
        assertEquals(max, schedule.currentIntervalMs)
    }

    @Test fun oneUncleanBurstSnapsStraightBackToTheFloorFromTheCeiling() {
        var t = 0L
        repeat(40) { t = clean(t, toWiden) }
        assertEquals(max, schedule.currentIntervalMs)
        schedule.started(t)
        schedule.completed(t, clean = false)
        assertEquals("suspicion is cheap to act on", min, schedule.currentIntervalMs)
        assertEquals("the clean history is forfeited entirely", 0, schedule.cleanRun)
        assertTrue(schedule.due(t + min))
        assertFalse(schedule.due(t + min - 1L))
    }

    @Test fun aPartialCleanRunIsForfeitedByOneBadBurstRatherThanCarriedOver() {
        var t = clean(0L, toWiden - 1)
        schedule.started(t)
        schedule.completed(t, clean = false)
        assertEquals(0, schedule.cleanRun)
        // The next clean burst starts a NEW run, so widening is a full run away again.
        t += min
        clean(t, toWiden - 1)
        assertEquals(min, schedule.currentIntervalMs)
    }

    @Test fun escalationBringsTheNextBurstForwardWithoutClaimingAnythingAboutThePath() {
        var t = 0L
        repeat(40) { t = clean(t, toWiden) }
        assertEquals(max, schedule.currentIntervalMs)
        // Sit just inside the ceiling interval, where the next burst is still a long way off.
        schedule.started(t)
        schedule.completed(t, clean = true)
        val soon = t + 1_000L
        assertFalse("the next burst is nowhere near due", schedule.due(soon))
        // A silent pong timeout on the shared socket: answer it now, but nothing about the path has
        // been measured yet, so the interval itself must not move.
        schedule.escalate(soon)
        assertTrue(schedule.due(soon))
        assertEquals("escalation is not a measurement", max, schedule.currentIntervalMs)
    }

    @Test fun escalationNeverPushesADueBurstIntoTheFuture() {
        schedule.started(0L)
        schedule.completed(0L, clean = true)
        // Already due at min; escalating later must not delay it.
        schedule.escalate(min + 5_000L)
        assertTrue(schedule.due(min))
    }

    @Test fun resetForgetsTheCadenceSoAReDemandedSocketStartsClean() {
        var t = 0L
        repeat(40) { t = clean(t, toWiden) }
        assertEquals(max, schedule.currentIntervalMs)
        schedule.reset()
        assertEquals(min, schedule.currentIntervalMs)
        assertEquals(0, schedule.cleanRun)
        assertTrue("the first burst after a reset is immediate again", schedule.due(t))
        assertEquals(-1L, schedule.lastBurstAgeMs(t))
    }

    @Test fun theLastBurstAgeIsReportedFromTheStartOfTheBurst() {
        assertEquals(-1L, schedule.lastBurstAgeMs(0L))
        schedule.started(1_000L)
        assertEquals(4_000L, schedule.lastBurstAgeMs(5_000L))
        // Never negative, however the clock is read.
        assertEquals(0L, schedule.lastBurstAgeMs(500L))
    }
}
