package io.panelassistant.android.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded BUSY retry admission is pure logic: a scripted monotonic clock and a recording sleeper
 * drive it with no wall-clock participation, so nothing here can flake under CPU load.
 */
class DatabaseBusyRetryTest {
    /** Exception shapes the classifier maps by message; mirrors Android and JDBC driver wording. */
    private class SQLiteDatabaseLockedException(message: String = "database is locked (code 5)") :
        RuntimeException(message)
    private class SQLiteDiskIOException : RuntimeException("disk i/o error (code 10)")
    private class SQLiteCorruptException : RuntimeException("database disk image is malformed")
    private class SQLiteFullException : RuntimeException("database or disk is full")

    private class Harness(budgetMillis: Long = 12_000L) {
        var nowMillis = 0L
        val sleeps = mutableListOf<Long>()
        val retry = DatabaseBusyRetry(
            budgetMillis = budgetMillis,
            monotonicMillis = { nowMillis },
            sleep = { delay ->
                sleeps += delay
                nowMillis += delay
            },
        )
    }

    @Test
    fun busyIsRetriedWithinTheBudgetAndTheReporterExceptionShapeQualifies() {
        val harness = Harness()
        val run = harness.retry.begin()
        // The exact shape from the Issue #91 logcat: the type matches nothing, only the message does.
        assertTrue(run.admitRetry(SQLiteDatabaseLockedException("database is locked (code 5)")))
        assertTrue(run.admitRetry(SQLiteDatabaseLockedException()))
        assertEquals(listOf(50L, 100L), harness.sleeps)
    }

    @Test
    fun backoffDoublesAndCapsWhileTheBudgetLasts() {
        val harness = Harness(budgetMillis = 100_000L)
        val run = harness.retry.begin()
        repeat(9) { assertTrue(run.admitRetry(SQLiteDatabaseLockedException())) }
        assertEquals(listOf(50L, 100L, 200L, 400L, 800L, 1_600L, 3_200L, 3_200L, 3_200L), harness.sleeps)
    }

    @Test
    fun starvationBeyondTheBudgetIsRefusedSoTheCallerLatches() {
        val harness = Harness(budgetMillis = 1_000L)
        val run = harness.retry.begin()
        var admitted = 0
        while (run.admitRetry(SQLiteDatabaseLockedException())) {
            admitted++
            // A mutation that drops the budget check must fail here, not hang the suite.
            assertTrue("retry must stop within the budget", admitted <= 100)
        }
        // 50+100+200+400 = 750; the next delay (800) would overrun the 1000ms budget, so it refuses
        // BEFORE sleeping: a refused attempt never spends more of the caller's time.
        assertEquals(4, admitted)
        assertEquals(listOf(50L, 100L, 200L, 400L), harness.sleeps)
    }

    @Test
    fun budgetCountsElapsedTimeNotAttempts() {
        val harness = Harness(budgetMillis = 1_000L)
        val run = harness.retry.begin()
        // Each attempt itself burned time inside SQLite's own busy handler before failing.
        harness.nowMillis += 980L
        assertFalse(run.admitRetry(SQLiteDatabaseLockedException()))
        assertTrue(harness.sleeps.isEmpty())
    }

    @Test
    fun genuineFaultsAreRefusedOnTheFirstAttemptWithZeroRetries() {
        val harness = Harness()
        listOf(
            SQLiteDiskIOException(),
            SQLiteCorruptException(),
            SQLiteFullException(),
            RuntimeException("unclassified"),
        ).forEach { failure ->
            assertFalse("${failure.message} must never be retried", harness.retry.begin().admitRetry(failure))
        }
        assertTrue("a genuine fault must not spend any delay", harness.sleeps.isEmpty())
    }

    @Test
    fun aKindChangeInTheMiddleOfARetryRunLatchesImmediately() {
        val harness = Harness()
        val run = harness.retry.begin()
        assertTrue(run.admitRetry(SQLiteDatabaseLockedException()))
        // Contention that turns into corruption mid-run is a genuine fault, not more contention.
        assertFalse(run.admitRetry(SQLiteCorruptException()))
        assertEquals(listOf(50L), harness.sleeps)
    }

    @Test
    fun anOwnerClosingDuringTheBackoffSleepRefusesTheRetry() {
        // close() can race the backoff: the pre-sleep check passed, then the owner began closing
        // while this run slept. Admitting would aim one more database attempt at a closing store.
        var closing = false
        var nowMillis = 0L
        val retry = DatabaseBusyRetry(
            monotonicMillis = { nowMillis },
            sleep = { delay ->
                nowMillis += delay
                closing = true
            },
        )
        assertFalse(retry.begin().admitRetry(SQLiteDatabaseLockedException(), abandoned = { closing }))
    }

    @Test
    fun anAbandoningOwnerRefusesWithoutSleeping() {
        val harness = Harness()
        val run = harness.retry.begin()
        assertFalse(run.admitRetry(SQLiteDatabaseLockedException(), abandoned = { true }))
        assertTrue("an abandoned run must not delay the closing owner", harness.sleeps.isEmpty())
    }

    @Test
    fun anInterruptedBackoffRefusesAndRestoresTheInterruptFlag() {
        var nowMillis = 0L
        val retry = DatabaseBusyRetry(
            monotonicMillis = { nowMillis },
            sleep = { throw InterruptedException() },
        )
        val run = retry.begin()
        try {
            assertFalse(run.admitRetry(SQLiteDatabaseLockedException()))
            assertTrue("the interrupt must be restored, not swallowed", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun retryStateIsPerRunSoOneOperationCannotSpendAnotherOperationsBudget() {
        val harness = Harness(budgetMillis = 1_000L)
        val first = harness.retry.begin()
        var spent = 0
        while (first.admitRetry(SQLiteDatabaseLockedException())) {
            spent++
            assertTrue("the first run must exhaust within its budget", spent <= 100)
        }
        // A fresh run after the first exhausted its budget starts from a fresh budget and delay.
        val second = harness.retry.begin()
        assertTrue(second.admitRetry(SQLiteDatabaseLockedException()))
        assertEquals(50L, harness.sleeps.last())
    }
}
