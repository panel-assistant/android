package io.github.maxlyth.hapaneld.control

/**
 * Decides whether a deliberate dashboard restart is announced on the panel.
 *
 * Pure Kotlin with a caller-supplied clock, so the policy is JVM-unit-testable. Genuine crash rebuilds
 * never reach this: only deliberate restarts ask.
 */
class DeliberateRestartAnnouncements(private val learningWindowMs: Long) {

    /** The native entity-bootstrap hold is on screen, telling the user the dashboard may reload. */
    @Synchronized fun learningNoticeShown(nowMs: Long) = Unit

    /**
     * Whether to announce a deliberate restart. [learning] is a restart the entity-filter learner asked
     * for; [dashboardShown] is whether a dashboard is on screen to be restarted.
     */
    @Synchronized fun shouldAnnounce(learning: Boolean, dashboardShown: Boolean, nowMs: Long): Boolean = true
}
