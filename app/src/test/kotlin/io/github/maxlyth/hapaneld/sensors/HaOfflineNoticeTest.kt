package io.github.maxlyth.hapaneld.sensors

import org.junit.Assert.*
import org.junit.Test

class HaOfflineNoticeTest {
    private fun notice(phase: HaLifecyclePhase, expected: Long? = null) =
        HaLifecycleNotice(phase, HaLifecycleReason.RESTART, 0L, expected)

    @Test fun unknownLossHasTenSecondGraceAndFastReconnectIsSilent() {
        val tracker = HaLifecycle()
        tracker.onDisconnected(100L, HaLifecycleSource.NATIVE)
        assertEquals(10_000L, tracker.snapshot(100L).offlineGraceRemainingMs)
        assertNull(HaLifecycleMessage.text(tracker.snapshot(10_099L)))
        assertTrue(HaLifecycleMessage.text(tracker.snapshot(10_100L))!!.contains("Reason unknown"))
        val fast = HaLifecycle()
        fast.onDisconnected(100L, HaLifecycleSource.NATIVE)
        fast.onNativeNotice(notice(HaLifecyclePhase.READY), 500L)
        assertEquals(HaLifecycleState.NORMAL, fast.snapshot(500L).state)
        fast.onEvent(HaLifecycleEvent.STARTED, HaLifecycleSource.MQTT, 1_000L)
        assertEquals(HaLifecycleState.NORMAL, fast.snapshot(1_000L).state)
    }

    @Test fun shutdownIsImmediateAndAuthenticationAndBirthCannotClearNativeStartup() {
        val tracker = HaLifecycle()
        tracker.onNativeNotice(notice(HaLifecyclePhase.SHUTTING_DOWN), 100L)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, tracker.snapshot(100L).state)
        tracker.onAuthenticatedRunning(200L)
        tracker.onEvent(HaLifecycleEvent.STARTED, HaLifecycleSource.MQTT, 300L)
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

    @Test fun directCoreStartedClearsNativeStartupWithoutRepeatingTheRecoveryWindow() {
        val tracker = HaLifecycle()
        tracker.onNativeNotice(notice(HaLifecyclePhase.STARTING), 100L)
        tracker.onAuthenticatedRunning(200L)
        tracker.onEvent(HaLifecycleEvent.STARTED, HaLifecycleSource.MQTT, 300L)
        assertEquals(HaLifecycleState.STARTING, tracker.snapshot(300L).state)
        tracker.onEvent(HaLifecycleEvent.STARTED, HaLifecycleSource.SOCKET, 400L)
        assertEquals(HaLifecycleState.BACK_ONLINE, tracker.snapshot(400L).state)
        tracker.onNativeNotice(notice(HaLifecyclePhase.READY), 500L)
        assertEquals(7_900L, tracker.snapshot(500L).backOnlineRemainingMs)
        assertEquals(HaLifecycleState.NORMAL, tracker.snapshot(8_400L).state)
        tracker.onNativeNotice(notice(HaLifecyclePhase.READY), 8_500L)
        assertEquals(HaLifecycleState.NORMAL, tracker.snapshot(8_500L).state)
    }

    @Test fun nativeDisconnectCannotEraseTheExactSocketRefusal() {
        val tracker = HaLifecycle()
        tracker.onSubscriptionRejected()
        tracker.onDisconnected(100L, HaLifecycleSource.NATIVE)
        assertTrue(tracker.snapshot(100L).refused)
        tracker.onDisconnected(200L, HaLifecycleSource.SOCKET)
        assertFalse(tracker.snapshot(200L).refused)
    }

    @Test fun firstRestartReportsNoMeasuredEstimateAndRetiredNativeOwnerCannotStrandIt() {
        val tracker = HaLifecycle()
        tracker.onNativeNotice(notice(HaLifecyclePhase.SHUTTING_DOWN), 100L)
        assertTrue(HaLifecycleMessage.text(tracker.snapshot(10_000L))!!.endsWith("Time back has not been measured yet"))
        tracker.onSourceRetired(HaLifecycleSource.NATIVE, 11_000L)
        assertEquals(HaLifecycleState.NORMAL, tracker.snapshot(11_000L).state)
        tracker.onDisconnected(12_000L)
        tracker.onAuthenticatedRunning(12_001L)
        assertEquals(HaLifecycleState.NORMAL, tracker.snapshot(12_001L).state)
    }
}
