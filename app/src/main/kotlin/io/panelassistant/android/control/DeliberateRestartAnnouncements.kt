package io.panelassistant.android.control

/**
 * Decides whether a deliberate dashboard restart is announced on the panel.
 *
 * A lone deliberate restart is always announced, so an on-purpose reset is never mistaken for a crash.
 * First entity-filter learning is different: it is a burst of restarts (the applied set, then runtime
 * promotions tens of seconds apart), and a fresh toast per rebuild read on hardware as a dashboard that
 * kept crashing. So the burst is said once and then kept quiet: a learning restart is announced only
 * when no learning announcement was made within [learningWindowMs], and each quiet one extends the
 * window. The native entity-bootstrap hold counts as that announcement, because it stays on screen
 * telling the user the dashboard may reload while the panel learns, and it survives WebView teardown
 * where an overlay did not.
 *
 * Only learning restarts are ever silenced; every other deliberate restart announces exactly as before
 * and leaves the window alone. Genuine crash rebuilds never reach this at all. Pure Kotlin with a
 * caller-supplied monotonic clock, so the policy is JVM-unit-testable.
 */
class DeliberateRestartAnnouncements(private val learningWindowMs: Long) {
    private var lastLearningAnnouncementMs: Long? = null

    /** The native entity-bootstrap hold is on screen, telling the user the dashboard may reload. */
    @Synchronized fun learningNoticeShown(nowMs: Long) {
        lastLearningAnnouncementMs = nowMs
    }

    /**
     * Whether to announce a deliberate restart. [learning] is a restart the entity-filter learner asked
     * for; [dashboardShown] is whether a dashboard is on screen to be restarted.
     */
    @Synchronized fun shouldAnnounce(learning: Boolean, dashboardShown: Boolean, nowMs: Long): Boolean {
        if (!learning) return true
        // Nothing the user can see is restarting, and nothing was said, so the window stays as it is.
        if (!dashboardShown) return false
        val last = lastLearningAnnouncementMs
        val windowOpen = last != null && nowMs - last in 0 until learningWindowMs
        lastLearningAnnouncementMs = nowMs
        return !windowOpen
    }
}
