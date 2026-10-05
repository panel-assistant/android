package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watchdog used to fire its recovery once and latch. A panel whose scan could not answer because
 * Home Assistant was not up yet therefore stayed held, or sat on the problem screen, long after HA came
 * back: nothing else re-drives it, since the periodic sync skips a freshly-synced catalogue and skips
 * again while the screen is awake, and no HA-reconnect trigger exists.
 */
class EntityBootstrapRetryDelayTest {

    private val base = 30_000L
    private val ceiling = 300_000L

    @Test fun `the first retry waits the base interval`() {
        assertEquals(base, entityBootstrapRetryDelayMs(1, base, ceiling))
    }

    @Test fun `each retry widens until the ceiling`() {
        assertEquals(60_000L, entityBootstrapRetryDelayMs(2, base, ceiling))
        assertEquals(120_000L, entityBootstrapRetryDelayMs(3, base, ceiling))
        assertEquals(240_000L, entityBootstrapRetryDelayMs(4, base, ceiling))
        assertEquals(ceiling, entityBootstrapRetryDelayMs(5, base, ceiling))
    }

    @Test fun `it never idles longer than the ceiling, so a panel converges once HA answers`() {
        for (attempt in 1..1000) {
            val delay = entityBootstrapRetryDelayMs(attempt, base, ceiling)
            assertTrue("attempt $attempt gave $delay", delay in 1..ceiling)
        }
    }

    @Test fun `a long outage does not wrap the shift back to the base interval`() {
        // `shl` on a Long uses only the low six bits of its operand, so an unbounded attempt count would
        // collapse the backoff to the base every 64 attempts. At five minutes a retry, a panel reaches
        // attempt 64 in about five hours, which an overnight outage passes.
        assertEquals(ceiling, entityBootstrapRetryDelayMs(64, base, ceiling))
        assertEquals(ceiling, entityBootstrapRetryDelayMs(65, base, ceiling))
        assertEquals(ceiling, entityBootstrapRetryDelayMs(129, base, ceiling))
    }

    @Test fun `a ceiling below the base still yields a usable interval`() {
        assertEquals(5_000L, entityBootstrapRetryDelayMs(1, base, 5_000L))
        assertEquals(5_000L, entityBootstrapRetryDelayMs(9, base, 5_000L))
    }
}
