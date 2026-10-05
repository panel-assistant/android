package io.panelassistant.android.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncGateTest {
    // deadband 3, settleBand 6 (the brightness channel's values)
    private fun gate(cur: Int, prev: Int, pub: Int) = SyncGate.settled(cur, prev, pub, deadband = 3, settleBand = 6)

    @Test fun firesWhenSettledPastDeadband() {
        // Moved 50→60 (>3), and this tick == last tick (prev 60) → settled → publish.
        assertTrue(gate(cur = 60, prev = 60, pub = 50))
    }

    @Test fun withinDeadbandDoesNotFire() {
        assertFalse(gate(cur = 52, prev = 52, pub = 50))   // only 2 away from published
        assertFalse(gate(cur = 53, prev = 53, pub = 50))   // exactly deadband — not strictly greater
    }

    @Test fun stillMovingWaitsForNextTick() {
        // Big jump from the published value, but the value also moved a lot since the previous tick
        // (30→60 = 30 > settleBand) → still ramping → hold until it settles.
        assertFalse(gate(cur = 60, prev = 30, pub = 50))
        // Next tick it's steady (58→60, within settleBand) → now publish.
        assertTrue(gate(cur = 60, prev = 58, pub = 50))
    }

    @Test fun firstObservationHasNoPrevTick() {
        // prevTick < 0 (unknown) bypasses the settle check — publish on the first past-deadband read.
        assertTrue(gate(cur = 60, prev = -1, pub = 50))
    }

    @Test fun unknownCurrentOrPublishedNeverFires() {
        assertFalse(gate(cur = -1, prev = 50, pub = 50))
        assertFalse(gate(cur = 60, prev = 60, pub = -1))
    }
}

class SyncLogTest {
    @Test fun recentIsNewestFirstWithAges() {
        val log = SyncLog(cap = 8)
        log.record(1_000, "volume 30→40")
        log.record(3_000, "cpu_governor Auto→Performance")
        val out = log.recent(nowMs = 5_000)
        assertEquals(2, out.size)
        assertEquals("2s ago · cpu_governor Auto→Performance", out[0])   // newest first
        assertEquals("4s ago · volume 30→40", out[1])
    }

    @Test fun ringDropsOldestBeyondCap() {
        val log = SyncLog(cap = 3)
        for (i in 1..5) log.record(i.toLong() * 1000, "event$i")
        val out = log.recent(nowMs = 10_000)
        assertEquals(3, out.size)                                        // only the last 3 kept
        assertTrue(out[0].endsWith("event5"))
        assertTrue(out[2].endsWith("event3"))
        assertFalse(out.any { it.endsWith("event1") || it.endsWith("event2") })
    }

    @Test fun emptyLogYieldsNothing() {
        assertTrue(SyncLog().recent(nowMs = 1_000).isEmpty())
    }

    @Test fun clockSkewNeverGivesNegativeAge() {
        val log = SyncLog()
        log.record(5_000, "x")
        assertEquals("0s ago · x", log.recent(nowMs = 4_000).single())   // now < atMs → clamped to 0
    }
}
