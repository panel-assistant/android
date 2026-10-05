package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardEntityFilterFallbackTest {
    @Test fun `automatic configured filter failure holds native renderer`() {
        assertEquals(
            EntityFilterFailureDisposition.HOLD_NATIVE,
            entityFilterFailureDisposition(
                automaticLearningEnabled = true,
                filterConfigured = true,
            ),
        )
    }

    @Test fun `manual filter retains legacy direct fallback`() {
        assertEquals(
            EntityFilterFailureDisposition.ALLOW_DIRECT,
            entityFilterFailureDisposition(
                automaticLearningEnabled = false,
                filterConfigured = true,
            ),
        )
    }

    @Test fun `automatic learning without an active filter does not invent a hold`() {
        assertEquals(
            EntityFilterFailureDisposition.ALLOW_DIRECT,
            entityFilterFailureDisposition(
                automaticLearningEnabled = true,
                filterConfigured = false,
            ),
        )
    }

    @Test fun `malformed automatic filter holds while malformed manual filter keeps direct behavior`() {
        assertEquals(
            EntityFilterFailureDisposition.HOLD_NATIVE,
            invalidEntityFilterFailureDisposition(
                signature = "invalid:learning=true",
                automaticLearningEnabled = true,
                filterConfigured = true,
            ),
        )
        assertEquals(
            EntityFilterFailureDisposition.ALLOW_DIRECT,
            invalidEntityFilterFailureDisposition(
                signature = "invalid:learning=false",
                automaticLearningEnabled = false,
                filterConfigured = true,
            ),
        )
    }

    @Test fun `ready bootstrap defers renderer creation until screen wake`() {
        assertEquals(true, deferReadyEntityBootstrapUntilWake(screenAwake = false))
        assertEquals(false, deferReadyEntityBootstrapUntilWake(screenAwake = true))
    }

    @Test fun `automatic retries are bounded and back off`() {
        val policy = EntityFilterRetryPolicy(longArrayOf(30L, 120L, 600L))

        assertEquals(30L, policy.nextDelay(screenAwake = true))
        policy.recordAttempt()
        assertEquals(120L, policy.nextDelay(screenAwake = true))
        policy.recordAttempt()
        assertEquals(600L, policy.nextDelay(screenAwake = true))
        policy.recordAttempt()
        assertEquals(null, policy.nextDelay(screenAwake = true))
    }

    @Test fun `dark screen suppresses retry without spending budget`() {
        val policy = EntityFilterRetryPolicy(longArrayOf(30L, 120L))

        assertEquals(null, policy.nextDelay(screenAwake = false))
        assertEquals(null, policy.nextDelay(screenAwake = false))
        assertEquals(30L, policy.nextDelay(screenAwake = true))
    }

    @Test fun `successful installation resets automatic retry budget`() {
        val policy = EntityFilterRetryPolicy(longArrayOf(30L, 120L))
        policy.recordAttempt()
        policy.recordAttempt()
        assertEquals(null, policy.nextDelay(screenAwake = true))

        policy.reset()

        assertEquals(30L, policy.nextDelay(screenAwake = true))
    }
}
