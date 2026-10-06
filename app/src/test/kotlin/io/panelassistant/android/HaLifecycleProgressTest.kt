package io.panelassistant.android

import io.panelassistant.android.sensors.HaLifecycle
import io.panelassistant.android.sensors.HaLifecycleNotice
import io.panelassistant.android.sensors.HaLifecyclePhase
import io.panelassistant.android.sensors.HaLifecycleReason
import io.panelassistant.android.sensors.HaLifecycleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restart card's progress bar, driven through the lifecycle tracker the card reads: a Panel Assistant
 * notice arrives, the tracker's own clock advances, and the card's state and fill come from its snapshot.
 */
class HaLifecycleProgressTest {

    private fun restart(expectedMs: Long?, elapsedMs: Long? = 0L) = HaLifecycle().apply {
        onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.STARTING, HaLifecycleReason.RESTART, elapsedMs, expectedMs), 0L)
    }

    private fun barAt(tracker: HaLifecycle, nowMs: Long): Int? {
        val snap = tracker.snapshot(nowMs)
        val state = haLifecycleNoticeState(snap, null) ?: return null
        return haLifecycleProgress(state, snap.expectedMs, snap.elapsedMs)
    }

    @Test fun aMeasuredRestartFillsTowardTheExpectedTime() {
        val tracker = restart(expectedMs = 60_000L, elapsedMs = 15_000L)
        assertEquals(HA_LIFECYCLE_PROGRESS_MAX / 4, barAt(tracker, 0L))
        assertEquals(HA_LIFECYCLE_PROGRESS_MAX / 2, barAt(tracker, 15_000L))
        val later = barAt(tracker, 16_000L)!!
        assertTrue("the bar keeps advancing on the snapshot clock", later > HA_LIFECYCLE_PROGRESS_MAX / 2)
    }

    @Test fun anUnmeasuredRestartShowsNoBar() {
        assertNull(barAt(restart(expectedMs = null, elapsedMs = null), 5_000L))
        assertNull("elapsed alone is not a measurement", barAt(restart(expectedMs = null, elapsedMs = 3_000L), 5_000L))
    }

    @Test fun anOverdueRestartSitsFullAndNeverRunsBackwards() {
        val tracker = restart(expectedMs = 30_000L)
        assertEquals(HA_LIFECYCLE_PROGRESS_MAX, barAt(tracker, 30_000L))
        assertEquals(HA_LIFECYCLE_PROGRESS_MAX, barAt(tracker, 45_000L))
        assertEquals(HA_LIFECYCLE_PROGRESS_MAX, barAt(tracker, 600_000L))
    }

    // Widths measured on a 1920x1200 landscape panel (density 1.4125) during a real restart.
    @Test fun onALandscapePanelTheBarIsOnlyAsWideAsTheHeadline() {
        assertEquals(1085, haLifecycleBarWidth(headlineTextPx = 1084.3f, contentWidthPx = 1820))
    }

    @Test fun onASquarePanelAWrappedHeadlineGivesTheBarTheCardWidth() {
        // 480x480: the headline needs about 760 px on one line, so it wraps across the 408 px content.
        assertEquals(408, haLifecycleBarWidth(headlineTextPx = 760f, contentWidthPx = 408))
    }

    @Test fun backOnlineRemovesTheBar() {
        val tracker = restart(expectedMs = 60_000L)
        tracker.onNativeNotice(HaLifecycleNotice(HaLifecyclePhase.READY, HaLifecycleReason.RESTART, null, null), 20_000L)
        val snap = tracker.snapshot(21_000L)
        assertEquals(HaLifecycleState.BACK_ONLINE, haLifecycleNoticeState(snap, null))
        assertEquals("the measurement outlives the restart", 60_000L, snap.expectedMs)
        assertNull(barAt(tracker, 21_000L))
    }
}
