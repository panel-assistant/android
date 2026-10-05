package io.panelassistant.android.sensors

import org.junit.Assert.*
import org.junit.Test

class HaOfflineNoticeTest {
    private fun notice(phase: HaLifecyclePhase, expected: Long? = null) =
        HaLifecycleNotice(phase, HaLifecycleReason.RESTART, 0L, expected)

    @Test fun unknownLossHasTenSecondGraceAndFastReconnectIsSilent() {
        val tracker = HaLifecycle()
        tracker.onDisconnected(100L)
        assertEquals(10_000L, tracker.snapshot(100L).offlineGraceRemainingMs)
        assertNull(HaLifecycleMessage.text(tracker.snapshot(10_099L)))
        assertTrue(HaLifecycleMessage.text(tracker.snapshot(10_100L))!!.contains("Reason unknown"))
        val fast = HaLifecycle()
        fast.onDisconnected(100L)
        fast.onNativeNotice(notice(HaLifecyclePhase.READY), 500L)
        assertEquals(HaLifecycleState.NORMAL, fast.snapshot(500L).state)
        assertEquals(HaLifecycleState.NORMAL, fast.snapshot(1_000L).state)
    }

    @Test fun shutdownIsImmediateAndAuthenticationCannotClearNativeStartup() {
        val tracker = HaLifecycle()
        tracker.onNativeNotice(notice(HaLifecyclePhase.SHUTTING_DOWN), 100L)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, tracker.snapshot(100L).state)
        tracker.onAuthenticatedRunning(200L)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, tracker.snapshot(300L).state)
        tracker.onNativeNotice(notice(HaLifecyclePhase.STARTING), 400L)
        assertEquals(HaLifecycleState.STARTING, tracker.snapshot(200_000L).state)
        tracker.onNativeNotice(notice(HaLifecyclePhase.READY), 200_001L)
        assertEquals(HaLifecycleState.BACK_ONLINE, tracker.snapshot(200_001L).state)
        tracker.onNativeNotice(notice(HaLifecyclePhase.READY), 200_002L)
        assertEquals(7_999L, tracker.snapshot(200_002L).backOnlineRemainingMs)
    }

    @Test fun timingAdvancesFromServerDurationOnMonotonicClockAndHasAnExplicitOverrunUnit() {
        val tracker = HaLifecycle()
        tracker.onNativeNotice(notice(HaLifecyclePhase.STARTING, 60_000L).copy(elapsedMs = 20_000L), 5_000L)
        assertEquals(25_000L, tracker.snapshot(10_000L).elapsedMs)
        assertTrue(HaLifecycleMessage.text(tracker.snapshot(10_000L))!!.contains("Expected back in about 35 sec"))
        val overdue = HaLifecycleMessage.text(tracker.snapshot(3_700_000L))!!
        assertTrue(overdue.startsWith("Taking longer than usual"))
        assertTrue(overdue.contains("h past the estimate"))
        assertEquals(HaLifecycleDuration(2L, "minutes"), haLifecycleDuration(61_000L))
    }

    @Test fun firstRestartReportsNoMeasuredEstimateAndRetiredNativeOwnerCannotStrandIt() {
        val tracker = HaLifecycle()
        tracker.onNativeNotice(notice(HaLifecyclePhase.SHUTTING_DOWN), 100L)
        assertTrue(HaLifecycleMessage.text(tracker.snapshot(10_000L))!!.endsWith("Time back has not been measured yet"))
        tracker.onNativeRetired(11_000L)
        assertEquals(HaLifecycleState.NORMAL, tracker.snapshot(11_000L).state)
        tracker.onDisconnected(12_000L)
        tracker.onAuthenticatedRunning(12_001L)
        assertEquals(HaLifecycleState.NORMAL, tracker.snapshot(12_001L).state)
    }
}
