package io.panelassistant.android.dashboard

import android.database.SQLException
import io.panelassistant.android.storage.DatabaseBusyRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maintenance pass composes three real parts: one [MaintenanceIntervalGate] admission, one
 * [DatabaseBusyRetry] run, and the SQLite work between them. An earlier revision consumed the
 * admission INSIDE the retried operation: the first BUSY spent the gate, the retry
 * re-ran into a refusing gate, and the pass ended reporting nothing — the work was never retried and
 * the failure never latched. These tests drive the production composition shape — gate consumed
 * once, before the retry begins — with the real gate and the real retry policy under a scripted
 * clock; the matching source contract pins `observedMaintenance` to the same shape, and the mutation
 * battery moves the admission back inside the retried operation to prove both fail.
 */
class MaintenanceRetryCompositionTest {
    /** android.database.SQLException is a stub on the JVM; the message getter is overridden so the
     *  classifier sees the reporter's wording regardless of what the stubbed constructor drops. */
    private class BusySqlException : SQLException() {
        override val message: String = "database is locked (code 5)"
    }

    /** The production shape: admission first, then every retry attempt re-runs only the work. */
    private class Pass(intervalMs: Long) {
        val gate = MaintenanceIntervalGate(intervalMs)
        var nowMillis = 0L
        val retry = DatabaseBusyRetry(
            monotonicMillis = { nowMillis },
            sleep = { delay -> nowMillis += delay },
        )
        var successReported = 0
        var latched: Throwable? = null

        fun run(work: () -> Boolean) {
            if (!gate.admit(nowMillis)) return
            val attempt = retry.begin()
            while (true) {
                try {
                    if (work()) successReported++
                    return
                } catch (failure: SQLException) {
                    if (!attempt.admitRetry(failure)) {
                        latched = failure
                        return
                    }
                }
            }
        }
    }

    @Test
    fun aBusyFirstAttemptRetriesTheWorkWithoutReconsultingTheGate() {
        val pass = Pass(intervalMs = 600_000L)
        var attempts = 0
        pass.run {
            attempts++
            if (attempts == 1) throw BusySqlException()
            true
        }
        assertEquals("the work must be re-run after a BUSY first attempt", 2, attempts)
        assertEquals("the completed retry must report durable-write success exactly once", 1, pass.successReported)
        assertNull("expected contention must not latch", pass.latched)
        // The admission was spent by this pass, not lost to its failed first attempt: the next pass
        // inside the interval is gated as normal.
        assertFalse(pass.gate.admit(pass.nowMillis))
    }

    @Test
    fun exhaustionLatchesTheFailureInsteadOfEndingAWritelessPass() {
        val pass = Pass(intervalMs = 600_000L)
        var attempts = 0
        pass.run {
            attempts++
            // A mutation that drops the budget check must fail here, not hang the suite.
            assertTrue("the retry must exhaust within its budget", attempts <= 100)
            throw BusySqlException()
        }
        assertTrue("starvation past the budget must surface the original failure", pass.latched is BusySqlException)
        assertTrue("the budget must have admitted real retries before latching", attempts > 1)
        assertEquals("a failed pass must not report durable-write success", 0, pass.successReported)
    }

    @Test
    fun aGateRefusalRunsNoWorkAndReportsNothing() {
        val pass = Pass(intervalMs = 600_000L)
        pass.run { true }
        assertEquals(1, pass.successReported)
        var secondRan = false
        pass.run {
            secondRan = true
            true
        }
        assertFalse("a pass inside the interval must not run the work at all", secondRan)
        assertEquals("a gated pass must not report success", 1, pass.successReported)
    }
}
