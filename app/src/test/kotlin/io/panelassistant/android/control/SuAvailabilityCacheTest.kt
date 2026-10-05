package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Su owns the TTL'd root-availability probe (formerly duplicated as PaneldServer's private suCache).
 * These assertions are the union of what that HTTP-layer cache guaranteed: su presence is probed at
 * most once per 60s window (result identity across callers) and re-probed once the window elapses.
 */
class SuAvailabilityCacheTest {
    @Test fun cachedProbeRunsAtMostOnceWithinTheTtlWindow() {
        val probes = AtomicInteger()
        var now = 1_000L
        val cache = Su.newAvailabilityCache(ttlMs = 60_000L, nowMs = { now }) {
            probes.incrementAndGet(); true
        }

        assertTrue(cache.get())
        assertTrue(cache.get())
        now = 60_999L
        assertTrue(cache.get())
        assertEquals(1, probes.get())   // result identity: one probe reused across the whole window
    }

    @Test fun cachedProbeReprobesAfterTheTtlExpires() {
        val probes = AtomicInteger()
        var now = 0L
        val cache = Su.newAvailabilityCache(ttlMs = 60_000L, nowMs = { now }) {
            probes.incrementAndGet() > 1   // flips result so a re-probe is observable
        }

        assertFalse(cache.get())
        now = 60_000L
        assertTrue(cache.get())          // window elapsed → fresh probe, new result
        assertEquals(2, probes.get())
    }

    @Test fun defaultWindowMatchesTheMigratedSixtySecondTtl() {
        val probes = AtomicInteger()
        var now = 0L
        val cache = Su.newAvailabilityCache(nowMs = { now }) { probes.incrementAndGet(); true }

        assertTrue(cache.get())
        now = 59_999L
        cache.get()
        assertEquals(1, probes.get())    // default TTL still fresh just before 60s
        now = 60_000L
        cache.get()
        assertEquals(2, probes.get())    // default TTL expires at exactly 60s
    }
}
