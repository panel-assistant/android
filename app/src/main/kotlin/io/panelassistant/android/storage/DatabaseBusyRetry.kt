package io.panelassistant.android.storage

/**
 * Bounded retry admission for expected internal SQLite BUSY contention.
 *
 * ha-paneld deliberately runs several `SQLiteOpenHelper` pools over one WAL database, so a writer on
 * one pool can exhaust its busy timeout while another pool briefly holds the write lock for
 * maintenance. That is expected concurrency, not storage-fault evidence (Issue #91). A retry run
 * admits another attempt only for a failure classified [StorageDatabaseFailureKind.BUSY], within one
 * elapsed-time budget; the caller keeps its unchanged latch-and-rethrow path for a refused attempt.
 * Every other failure kind — IO, corruption, storage-full, unknown — is refused on the first
 * attempt, preserving fail-closed behavior for genuine faults, including a kind change in the middle
 * of a retry run.
 *
 * The budget rides out one bounded maintenance chunk plus one checkpoint TRUNCATE with margin on
 * slow eMMC — not the historical multi-ten-second FULL auto-vacuum purge, which the maintenance
 * chunking and incremental-vacuum work removes at its source. It is also the worst-case stall for an
 * HTTP handler whose write loses the race, so it stays modest. Elapsed time comes from a monotonic
 * clock; wall-clock time never participates.
 */
internal class DatabaseBusyRetry(
    private val budgetMillis: Long = BUSY_RETRY_BUDGET_MILLIS,
    private val initialDelayMillis: Long = INITIAL_DELAY_MILLIS,
    private val maxDelayMillis: Long = MAX_DELAY_MILLIS,
    private val monotonicMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    init {
        require(budgetMillis > 0L)
        require(initialDelayMillis in 1L..maxDelayMillis)
    }

    fun begin(): Run = Run()

    /** One write's retry state. Create one per logical operation, never share across operations. */
    internal inner class Run {
        private val startedAtMillis = monotonicMillis()
        private var delayMillis = initialDelayMillis

        /**
         * Decides whether the caller may re-run its failed block, sleeping the backoff delay first
         * when it may. Returns false — leaving the caller to latch and rethrow the original failure —
         * for any non-BUSY classification, an exhausted budget, an abandoning owner, or an
         * interrupted backoff sleep. The caller's block must be self-contained: each attempt's
         * transaction is fully rolled back by its own throw, so a re-run cannot double-apply.
         */
        fun admitRetry(failure: Throwable, abandoned: () -> Boolean = { false }): Boolean {
            if (classifyDatabaseFailure(failure) != StorageDatabaseFailureKind.BUSY) return false
            if (monotonicMillis() - startedAtMillis + delayMillis > budgetMillis) return false
            if (abandoned()) return false
            try {
                sleep(delayMillis)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            delayMillis = (delayMillis * 2L).coerceAtMost(maxDelayMillis)
            // The owner may have begun closing while this run slept; admitting now would aim one
            // more database attempt at a closing store, so the backoff is followed by a recheck.
            return !abandoned()
        }
    }

    companion object {
        const val BUSY_RETRY_BUDGET_MILLIS = 12_000L
        private const val INITIAL_DELAY_MILLIS = 50L
        private const val MAX_DELAY_MILLIS = 3_200L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
