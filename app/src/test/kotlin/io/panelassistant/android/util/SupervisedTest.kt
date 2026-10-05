package io.panelassistant.android.util

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The supervised periodic primitive — the exception boundary and scheduling that back every reliability loop. */
class SupervisedTest {

    @Test fun runsRepeatedlyAfterTheInitialDelay() = runTest {
        var n = 0
        val job = periodic(intervalMs = 1_000, initialDelayMs = 5_000, name = "t") { n++ }
        advanceTimeBy(4_000)
        assertEquals("nothing before the initial delay", 0, n)
        advanceTimeBy(3_000) // now ~7s in: bodies at ~5s, 6s, 7s
        assertTrue("ran repeatedly after the initial delay (n=$n)", n >= 2)
        job.cancelAndJoin()
    }

    @Test fun survivesAThrowingIteration() = runTest {
        var n = 0
        val errors = mutableListOf<Throwable>()
        val job = periodic(intervalMs = 1_000, name = "t", onError = { errors.add(it) }) {
            n++
            if (n == 1) throw RuntimeException("boom")
        }
        advanceTimeBy(3_500)
        job.cancelAndJoin()
        assertTrue("loop kept running after a throw (n=$n)", n >= 3)
        assertEquals(1, errors.size)
    }

    @Test fun cancellationStopsTheLoop() = runTest {
        var n = 0
        val job = periodic(intervalMs = 1_000, name = "t") { n++ }
        advanceTimeBy(2_500)
        val atCancel = n
        assertTrue("ran a few times first (n=$n)", atCancel >= 2)
        job.cancelAndJoin()
        advanceTimeBy(5_000)
        assertEquals("no ticks after cancellation", atCancel, n)
    }
}
