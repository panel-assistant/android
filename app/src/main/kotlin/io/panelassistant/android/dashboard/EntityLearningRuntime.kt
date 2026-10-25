package io.panelassistant.android.dashboard

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Process-local rendezvous between the service-owned learner and DashboardActivity's JS bridge. */
object EntityLearningRuntime {
    internal val homeDashboardAuthority = HomeDashboardResolutionAuthority()
    @Volatile private var current: EntityLearningManager? = null
    @Volatile private var attachment = CompletableDeferred<EntityLearningManager>()

    @Synchronized fun attach(manager: EntityLearningManager) {
        current = manager
        attachment.complete(manager)
    }

    @Synchronized fun detach(manager: EntityLearningManager) {
        if (current === manager) {
            current = null
            attachment = CompletableDeferred()
        }
    }
    fun recordAccessBatch(text: String) { current?.recordAccessBatch(text) }
    fun recordMetricBatch(text: String) { current?.recordMetricBatch(text) }
    fun performanceSummaryJson(): String = current?.performanceSummaryJson() ?: "[]"
    fun blockingIssueCount(): Int = current?.blockingIssueCount() ?: 0
    fun bootstrapProblem(): EntityBootstrapProblem? = current?.bootstrapProblem()
    /** Explicit user retry only. syncNow rejects the request while an existing scan is active. */
    fun retryBootstrap(): Boolean = current?.syncNow("dashboard-retry") ?: false
    fun syncRunning(): Boolean = current?.syncRunning() ?: false
    /** The held renderer asking whether the dashboard changed; never a catalogue resync. */
    fun probeDashboardChange(): Boolean = current?.probeDashboardChange("dashboard-hold") ?: false

    /** Live bring-up milestones for the panel's hold screen — real signals, never a bare spinner. */
    data class BootstrapMilestone(val stage: Int, val count: Int?, val expectedTotal: Int? = null)

    fun bootstrapMilestoneState(): BootstrapMilestone? {
        val manager = current ?: return null
        val scanned = manager.scanProgress()
        if (scanned != null) return BootstrapMilestone(1, scanned, manager.scanExpectedTotal())
        val catalog = manager.completedScanCount() ?: manager.catalogCount() ?: return BootstrapMilestone(1, null)
        return BootstrapMilestone(2, catalog)
    }

    /** Await service attachment, then perform a scan-independent authenticated dashboard resolution. */
    suspend fun resolveHomeDashboard(
        homeDashboard: String,
        stillCurrent: () -> Boolean = { true },
        forceLive: Boolean = false,
    ): EntityLearningProtocol.HomeDashboardResolution? {
        // Service ownership can legitimately wait behind a predecessor's bounded teardown. The activity
        // scope cancels this await on destruction; an arbitrary short timeout would strand a healthy
        // same-process restart on a manual Retry screen before its new owner has had a chance to attach.
        val manager = current ?: withTimeoutOrNull(45_000L) { attachment.await() } ?: return null
        val resolved = manager.resolveHomeDashboard(homeDashboard, stillCurrent, forceLive)
        return resolved.takeIf { current === manager && stillCurrent() }
    }
    fun canIgnoreBlockingIssues(): Boolean = current?.canIgnoreAllBlockingIssues() ?: false
    fun ignoreBlockingIssues(): Boolean = current?.ignoreAllBlockingIssues() ?: false
    fun disableAutomaticFilter(): Boolean = current?.setEnabled(false) ?: false
}
