package io.panelassistant.android.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthRecoveryTest {
    @Test fun `only authentication states reserve reconnect scheduling`() {
        assertEquals(true, isAuthRecoveryState("auth-retrying"))
        assertEquals(true, isAuthRecoveryState("auth-failed"))
        assertEquals(false, isAuthRecoveryState("unreachable"))
        assertEquals(false, isAuthRecoveryState("connecting"))
    }

    @Test fun `previously valid credentials retry transient rejection quickly`() {
        val r = AuthRecovery(jitter = { base, _ -> base })
        r.configure("same")
        r.authenticated(100)
        assertEquals(2_100, r.rejected(100))
        assertEquals("auth-retrying", r.snapshot(100).state)
        assertEquals(1, r.snapshot(100).consecutiveRejects)
    }

    @Test fun `new bad credentials are failed rather than transient`() {
        val r = AuthRecovery()
        r.configure("never-worked")
        r.rejected(0)
        assertEquals("auth-failed", r.snapshot(0).state)
    }

    @Test fun `persistent rejection crosses five minute threshold`() {
        val r = AuthRecovery(jitter = { base, _ -> base })
        r.configure("valid")
        r.authenticated(1)
        r.rejected(10)
        assertEquals("auth-retrying", r.snapshot(300_009).state)
        assertEquals("auth-failed", r.snapshot(300_010).state)
    }

    @Test fun `backoff follows bounded sequence and caps at sixty seconds`() {
        val r = AuthRecovery(jitter = { base, _ -> base })
        r.configure("x")
        val delays = (1..7).map { r.rejected(0) }
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L, 60_000L, 60_000L), delays)
    }

    @Test fun `success immediately clears rejection recovery`() {
        val r = AuthRecovery()
        r.configure("x")
        r.rejected(0)
        r.authenticated(5)
        val s = r.snapshot(5)
        assertEquals("connected", s.state)
        assertEquals(0, s.consecutiveRejects)
        assertEquals(0, s.retryAttempt)
        assertNull(s.nextRetryMs)
        assertEquals(5L, s.lastSuccessMs)
    }

    @Test fun `each retry supersedes the prior scheduled attempt`() {
        val r = AuthRecovery(jitter = { base, _ -> base })
        val first = r.beginRetry()
        assertEquals(true, r.isCurrentRetry(first))
        val second = r.beginRetry()
        assertEquals(false, r.isCurrentRetry(first))
        assertEquals(true, r.isCurrentRetry(second))
    }

    @Test fun `successful authentication supersedes any pending scheduled retry`() {
        val r = AuthRecovery(jitter = { base, _ -> base })
        val token = r.beginRetry()
        r.authenticated(0)
        assertEquals(false, r.isCurrentRetry(token))
    }

    @Test fun `retirement supersede discards the pending scheduled retry`() {
        val r = AuthRecovery(jitter = { base, _ -> base })
        val token = r.beginRetry()
        r.supersedeRetry()
        assertEquals(false, r.isCurrentRetry(token))
    }

    @Test fun `configure leaves retry currency untouched`() {
        val r = AuthRecovery(jitter = { base, _ -> base })
        val token = r.beginRetry()
        r.configure("changed")
        assertEquals(true, r.isCurrentRetry(token))
    }

    @Test fun `credential change resets backoff and prior-valid classification`() {
        val r = AuthRecovery(jitter = { base, _ -> base })
        r.configure("old")
        r.authenticated(0)
        r.rejected(1)
        r.rejected(2)
        r.configure("new")
        assertEquals(2_003L, r.rejected(3))
        assertEquals("auth-failed", r.snapshot(3).state)
        assertEquals(1, r.snapshot(3).retryAttempt)
    }
}
