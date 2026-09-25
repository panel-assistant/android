package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLoopTrackerTest {
    private fun tracker() = CrashLoopTracker(maxRelaunches = 3, windowMs = 60_000L, backoffMs = 600_000L)

    @Test fun allowsUpToMaxThenBacksOff() {
        val t = tracker()
        // 3 relaunches within the window are allowed…
        assertTrue(t.onRelaunchAttempt(0))
        assertTrue(t.onRelaunchAttempt(10_000))
        assertTrue(t.onRelaunchAttempt(20_000))
        // …the 4th (still in-window) is suppressed — crash-loop detected.
        assertFalse(t.onRelaunchAttempt(30_000))
    }

    @Test fun spacedOutRelaunchesNeverTrip() {
        val t = tracker()
        // One relaunch every 40s — never 3 within the 60s window, so never a crash-loop.
        var now = 0L
        repeat(10) { assertTrue(t.onRelaunchAttempt(now)); now += 40_000L }
    }

    @Test fun backoffExpiresThenRetriesAllowed() {
        val t = tracker()
        t.onRelaunchAttempt(0); t.onRelaunchAttempt(1_000); t.onRelaunchAttempt(2_000)
        assertFalse(t.onRelaunchAttempt(3_000))          // backoff starts at 3_000, ends 603_000
        assertFalse(t.onRelaunchAttempt(600_000))        // still in backoff
        assertTrue(t.onRelaunchAttempt(603_001))         // backoff expired → allowed again
    }

    @Test fun resetClearsHistoryAndBackoff() {
        val t = tracker()
        t.onRelaunchAttempt(0); t.onRelaunchAttempt(1_000); t.onRelaunchAttempt(2_000)
        assertFalse(t.onRelaunchAttempt(3_000))          // in backoff
        t.reset()                                        // dashboard recovered
        assertTrue(t.onRelaunchAttempt(3_000))           // full budget again
    }
}
