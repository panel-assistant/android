package io.github.maxlyth.hapaneld.http

import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.control.TameController
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.control.TameReconcileResult
import io.github.maxlyth.hapaneld.util.LatestDispatcher

/**
 * The single vendor-taming execution owner. Queue entries are only wake-up signals: every pass reads
 * the current durable desired value, so reordered/coalesced requests can never replay a stale delta.
 */
internal class TameReconcileAuthority(
    readDesired: () -> Set<String>,
    reconcile: (Set<String>, () -> Boolean) -> TameReconcileResult,
    private val stopping: () -> Boolean,
    private val retryDelayMs: Long = 5_000L,
    private val onBacklogChanged: (Int) -> Unit = {},
) {
    constructor(config: Config, tame: TameController, stopping: () -> Boolean) : this(
        readDesired = { config.tameVendorPackages.toSet() },
        reconcile = { desired, stopping ->
            val cost = FeatureCosts.registry.span(FeatureCostOperation.TAME_MUTATION)
            try {
                tame.reconcileBlocklist(desired, stopping).also { result ->
                    cost.work(units = result.attempted.toLong())
                    if (result.retryableFailure) cost.outcome(FeatureCostOutcome.FAILURE)
                }
            } catch (error: Exception) {
                cost.outcome(FeatureCostOutcome.FAILURE)
                Log.w("ha-paneld/http", "vendor package reconciliation failed", error)
                TameReconcileResult(attempted = 0, retryableFailure = true)
            } finally {
                cost.close()
            }
        },
        stopping = stopping,
        onBacklogChanged = { pending ->
            FeatureCosts.registry.setBacklog(FeatureCostOperation.TAME_MUTATION, pending)
        },
    )

    /**
     * One commit-order submission seam. Admission loss is observable but not correctness-critical: the
     * desired config and write-ahead ownership markers are durable, and startup requests another pass.
     */
    fun requestAfterCommit(): Boolean {
        val admission = request()
        when (admission) {
            LatestDispatcher.Admission.ACCEPTED -> Unit
            LatestDispatcher.Admission.COALESCED ->
                FeatureCosts.registry.recordCoalesced(FeatureCostOperation.TAME_MUTATION)
            LatestDispatcher.Admission.REJECTED,
            LatestDispatcher.Admission.CLOSED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.TAME_MUTATION)
        }
        return admission == LatestDispatcher.Admission.ACCEPTED ||
            admission == LatestDispatcher.Admission.COALESCED
    }

    private val retryLock = Object()
    @Volatile private var closed = false
    private fun shouldStop(): Boolean = closed || stopping()

    private val dispatcher = LatestDispatcher<String, Unit>(
        threadName = "ha-paneld-tame",
        maxPendingKeys = 1,
        consume = consume@{ _, _ ->
        reportBacklog()
        try {
            var failureRetries = 0
            while (!shouldStop()) {
                val desired = readDesired()
                val result = reconcile(desired, ::shouldStop)
                if (shouldStop()) return@consume
                // A concurrent commit supersedes this pass. Re-read immediately rather than acting on a
                // submitted generation/delta; a queued signal remains as a harmless final consistency pass.
                if (readDesired() != desired) {
                    failureRetries = 0
                    continue
                }
                if (!result.retryableFailure) return@consume
                // Do not turn a permanently failing privileged command into months of polling. Durable desired
                // state plus ownership markers retain the mismatch for startup/the next config wake-up.
                if (failureRetries++ >= MAX_FAILURE_RETRIES) return@consume
                try {
                    synchronized(retryLock) {
                        if (!shouldStop() && retryDelayMs > 0) retryLock.wait(retryDelayMs)
                    }
                } catch (_: InterruptedException) {
                    return@consume
                }
            }
        } finally {
            reportBacklog()
        }
        },
    )

    fun request(): LatestDispatcher.Admission = dispatcher.submit(RECONCILE_KEY, Unit).also {
        reportBacklog()
    }

    fun closeAndJoin(timeoutMs: Long): Boolean {
        synchronized(retryLock) {
            closed = true
            retryLock.notifyAll()
        }
        // A package transaction owns its write-ahead marker until its final action completes. Do not
        // interrupt it: cancel pending work and stop the pass at the next package boundary instead.
        dispatcher.close()
        reportBacklog()
        return dispatcher.awaitTermination(timeoutMs)
    }

    private fun reportBacklog() {
        runCatching { onBacklogChanged(dispatcher.pendingCount()) }
    }

    private companion object {
        const val RECONCILE_KEY = "desired"
        const val MAX_FAILURE_RETRIES = 3
    }
}
