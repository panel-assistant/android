package io.panelassistant.android.provisioning

import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCostRegistry
import io.panelassistant.android.metrics.FeatureCosts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/**
 * Service-owned read facade. It binds observations to one immutable startup profile and caches a
 * short-lived snapshot so the JSON and text endpoints do not independently hammer device readers.
 */
internal class ProvisioningCoordinator(
    private val core: ProvisioningCoreIdentity,
    private val profile: ProvisioningProfile,
    private val collector: ProvisioningObservationCollector,
    private val monotonicMs: () -> Long,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val featureCosts: FeatureCostRegistry = FeatureCosts.registry,
) : ProvisioningReader {
    override val expectedProfileRef = profile.ref

    private val refreshMutex = Mutex()

    @Volatile
    private var cached: CachedSnapshot? = null

    init {
        require(cacheTtlMs >= 0) { "cacheTtlMs must be non-negative" }
    }

    override suspend fun plan(
        activation: ProvisioningActivationSnapshot,
        forceRefresh: Boolean,
    ): ProvisioningReadResult {
        val cost = featureCosts.span(FeatureCostOperation.PROVISIONING_PLAN)
        if (!activation.isStableFor(profile.ref)) {
            cost.outcome(FeatureCostOutcome.REJECTED).close()
            return ProvisioningReadResult.Unavailable("profile_activation_unstable")
        }
        return try {
            val observations = observations(forceRefresh)
            val plan = ProvisioningPlanner.plan(core, profile, activation, observations)
            cost.work(units = plan.items.size.toLong())
            ProvisioningReadResult.Ready(plan)
        } catch (cancelled: CancellationException) {
            cost.outcome(FeatureCostOutcome.CANCELLED)
            throw cancelled
        } catch (_: Throwable) {
            cost.outcome(FeatureCostOutcome.FAILURE)
            ProvisioningReadResult.Unavailable("observation_snapshot_unavailable")
        } finally {
            cost.close()
        }
    }

    private suspend fun observations(forceRefresh: Boolean): ProvisioningObservationSnapshot {
        val now = monotonicMs()
        cached?.takeIf { !forceRefresh && now - it.collectedAtMs <= cacheTtlMs }?.let { return it.snapshot }

        refreshMutex.lock()
        try {
            val lockedNow = monotonicMs()
            cached?.takeIf { !forceRefresh && lockedNow - it.collectedAtMs <= cacheTtlMs }?.let {
                return it.snapshot
            }
            val cost = featureCosts.span(FeatureCostOperation.PROVISIONING_OBSERVATION_REFRESH)
                .work(units = OBSERVATION_PROBE_COUNT)
            return try {
                collector.collect().also {
                    cached = CachedSnapshot(monotonicMs(), it)
                }
            } catch (cancelled: CancellationException) {
                cost.outcome(FeatureCostOutcome.CANCELLED)
                throw cancelled
            } catch (failure: Throwable) {
                cost.outcome(FeatureCostOutcome.FAILURE)
                throw failure
            } finally {
                cost.close()
            }
        } finally {
            refreshMutex.unlock()
        }
    }

    private data class CachedSnapshot(
        val collectedAtMs: Long,
        val snapshot: ProvisioningObservationSnapshot,
    )

    private companion object {
        const val DEFAULT_CACHE_TTL_MS = 5_000L
        const val OBSERVATION_PROBE_COUNT = 3L
    }
}
