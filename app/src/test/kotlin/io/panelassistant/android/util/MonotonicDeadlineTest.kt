package io.panelassistant.android.util

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class MonotonicDeadlineTest {
    @Test fun phasesConsumeOneDeadlineWithoutResettingTheirTimeouts() {
        var nowNanos = 0L
        val deadline = MonotonicDeadline(1_000L) { nowNanos }

        assertEquals(1_000L, deadline.remainingMs())
        nowNanos += TimeUnit.MILLISECONDS.toNanos(400L)
        assertEquals(600L, deadline.remainingMs())
        nowNanos += TimeUnit.MILLISECONDS.toNanos(599L) + 500_000L
        assertEquals(1L, deadline.remainingMs())
        nowNanos += 500_000L
        assertEquals(0L, deadline.remainingMs())
    }

    @Test fun zeroAndNegativeTimeoutsAreImmediatelyNonBlocking() {
        assertEquals(0L, MonotonicDeadline(0L) { 10L }.remainingMs())
        assertEquals(0L, MonotonicDeadline(-1L) { 10L }.remainingMs())
    }

    @Test fun nanoTimeWrapDoesNotResetTheDeadline() {
        var nowNanos = Long.MAX_VALUE - 500_000L
        val deadline = MonotonicDeadline(1L) { nowNanos }

        nowNanos += 750_000L
        assertEquals(1L, deadline.remainingMs())
        nowNanos += 250_000L
        assertEquals(0L, deadline.remainingMs())
    }

    @Test fun childDeadlineCannotOutliveItsCapOrAnExpiredParent() {
        var nowNanos = 0L
        val parent = MonotonicDeadline(100L) { nowNanos }
        val child = parent.cappedTo(25L)

        nowNanos += TimeUnit.MILLISECONDS.toNanos(24L)
        assertEquals(1L, child.remainingMs())
        assertEquals(76L, parent.remainingMs())
        nowNanos += TimeUnit.MILLISECONDS.toNanos(1L)
        assertEquals(0L, child.remainingMs())

        nowNanos += TimeUnit.MILLISECONDS.toNanos(75L)
        assertEquals(0L, parent.remainingMs())
        assertEquals(0L, parent.cappedTo(1_000L).remainingMs())
    }
}
