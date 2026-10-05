package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliberateRestartAnnouncementsTest {
    private val window = 10L * 60_000
    private val policy = DeliberateRestartAnnouncements(learningWindowMs = window)

    private fun announcements(vararg restarts: Pair<Long, Boolean>): Int =
        restarts.count { (at, learning) -> policy.shouldAnnounce(learning, dashboardShown = true, nowMs = at) }

    @Test fun learningBurstOverAShownDashboardIsAnnouncedOnce() {
        // First learning on a fresh panel: the applied set, then runtime promotions tens of seconds apart.
        assertEquals(1, announcements(1_000L to true, 21_000L to true, 45_000L to true, 70_000L to true))
    }

    @Test fun learningBurstAfterTheBootstrapNoticeIsNotAnnouncedAtAll() {
        policy.learningNoticeShown(nowMs = 0L)
        // The hold ends with no dashboard on screen yet: nothing is restarting that the user can see.
        assertFalse(policy.shouldAnnounce(learning = true, dashboardShown = false, nowMs = 5_000L))
        assertEquals(0, announcements(25_000L to true, 50_000L to true, 75_000L to true))
    }

    @Test fun loneDeliberateRestartIsAnnounced() {
        assertTrue(policy.shouldAnnounce(learning = false, dashboardShown = true, nowMs = 1_000L))
    }

    @Test fun loneDeliberateRestartWithNoDashboardIsStillAnnounced() {
        assertTrue(policy.shouldAnnounce(learning = false, dashboardShown = false, nowMs = 1_000L))
    }

    @Test fun otherRestartsInsideALearningWindowAreStillAnnounced() {
        assertTrue(policy.shouldAnnounce(learning = true, dashboardShown = true, nowMs = 1_000L))
        assertTrue(policy.shouldAnnounce(learning = false, dashboardShown = true, nowMs = 2_000L))
        assertTrue(policy.shouldAnnounce(learning = false, dashboardShown = true, nowMs = 3_000L))
        // …and they neither close nor extend the learning window.
        assertFalse(policy.shouldAnnounce(learning = true, dashboardShown = true, nowMs = 4_000L))
    }

    @Test fun learningRestartAfterTheWindowLapsesIsAnnouncedAgain() {
        assertTrue(policy.shouldAnnounce(learning = true, dashboardShown = true, nowMs = 0L))
        assertTrue(policy.shouldAnnounce(learning = true, dashboardShown = true, nowMs = window))
    }

    @Test fun eachQuietLearningRestartExtendsTheWindow() {
        assertTrue(policy.shouldAnnounce(learning = true, dashboardShown = true, nowMs = 0L))
        assertFalse(policy.shouldAnnounce(learning = true, dashboardShown = true, nowMs = window - 1))
        assertFalse(policy.shouldAnnounce(learning = true, dashboardShown = true, nowMs = 2 * window - 2))
    }

    @Test fun hiddenLearningRestartWithoutANoticeDoesNotOpenTheWindow() {
        // No bootstrap notice was shown (for example, the admission screen was up), so the first learning
        // restart the user can see must still explain itself.
        assertFalse(policy.shouldAnnounce(learning = true, dashboardShown = false, nowMs = 0L))
        assertTrue(policy.shouldAnnounce(learning = true, dashboardShown = true, nowMs = 1_000L))
    }

    @Test fun bootstrapNoticeLapsesLikeAnyOtherAnnouncement() {
        policy.learningNoticeShown(nowMs = 0L)
        assertTrue(policy.shouldAnnounce(learning = true, dashboardShown = true, nowMs = window))
    }
}
