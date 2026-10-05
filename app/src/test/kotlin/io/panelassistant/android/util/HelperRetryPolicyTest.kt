package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HelperRetryPolicyTest {
    @Test fun helperRetryBackoffGrowsMonotonicallyAndCaps() {
        val delays = generateSequence(0L, ::nextHelperRetryDelayMs).drop(1).take(8).toList()
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L, 60_000L, 60_000L, 60_000L), delays)
    }

    @Test fun successfulSubscriptionCanResetToInitialDelay() {
        assertEquals(HELPER_RETRY_INITIAL_MS, nextHelperRetryDelayMs(0L))
    }
}
