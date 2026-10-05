package io.panelassistant.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Panel Assistant ownership lease that withholds the MQTT ha-paneld update entity. */
class PanelAssistantUpdateLeaseTest {
    private val hour = 60L * 60L * 1_000L
    private val lease = PanelAssistantUpdateLease.LEASE_MS
    private val synced = 1_788_000_000_000L // a plausible 2026 wall clock

    private class Clocks(var wall: Long, var uptime: Long, var persisted: Long = 0L) {
        val writes = mutableListOf<Long>()
        fun lease() = PanelAssistantUpdateLease(
            wallNowMs = { wall },
            uptimeMs = { uptime },
            readPersisted = { persisted },
            writePersisted = { persisted = it; writes += it },
        )
    }

    @Test fun onlyTheExactAgreedHeaderValueDeclaresOwnership() {
        assertEquals("X-Panel-Assistant-Update-Owner", PanelAssistantUpdateLease.HEADER)
        assertTrue(PanelAssistantUpdateLease.declares("1"))
        for (value in listOf(null, "", "0", "true", "yes", " 1", "1 ", "11")) {
            assertFalse("value <$value>", PanelAssistantUpdateLease.declares(value))
        }
    }

    @Test fun anObservationStartsTheLeaseAndReportsTheTransitionOnce() {
        val clocks = Clocks(wall = synced, uptime = 10_000L)
        val subject = clocks.lease()
        assertFalse(subject.active())
        assertTrue(subject.observe())
        assertTrue(subject.active())
        clocks.uptime += 30_000L
        assertFalse("a renewal is not a new transition", subject.observe())
    }

    @Test fun theLeaseExpiresTwentyFourHoursAfterTheLastObservation() {
        val clocks = Clocks(wall = synced, uptime = 10_000L)
        val subject = clocks.lease()
        subject.observe()
        clocks.uptime += lease - 1
        clocks.wall += lease - 1
        assertTrue(subject.active())
        clocks.uptime += 1
        clocks.wall += 1
        assertFalse(subject.active())
    }

    @Test fun persistenceIsCoalescedToAtMostOneWriteAnHour() {
        val clocks = Clocks(wall = synced, uptime = 10_000L)
        val subject = clocks.lease()
        repeat(120) { // one hour of 30-second polls
            subject.observe()
            clocks.wall += 30_000L
            clocks.uptime += 30_000L
        }
        assertEquals(1, clocks.writes.size)
        repeat(2) {
            subject.observe()
            clocks.wall += 30_000L
            clocks.uptime += 30_000L
        }
        assertEquals("the second hour starts one more write", 2, clocks.writes.size)
    }

    @Test fun aRestartedPanelStartsSuppressedFromThePersistedLease() {
        val clocks = Clocks(wall = synced, uptime = 5_000L, persisted = synced - 2 * hour)
        assertTrue(clocks.lease().active())
        clocks.persisted = synced - lease
        assertFalse(clocks.lease().active())
    }

    @Test fun aFutureTimestampFromAnUnsyncedClockCountsOnlyWithinTwentyFourHoursOfUptime() {
        // Booted without a real-time clock: the wall clock reads 1970 until NTP syncs.
        val clocks = Clocks(wall = 60_000L, uptime = 60_000L, persisted = synced)
        assertTrue("not treated as expired", clocks.lease().active())
        clocks.uptime = lease - 1
        assertTrue(clocks.lease().active())
        clocks.uptime = lease
        assertFalse("not held forever", clocks.lease().active())
    }

    @Test fun persistedLeaseArithmeticIsExact() {
        assertFalse(PanelAssistantUpdateLease.persistedLeaseActive(0L, synced, 1_000L))
        assertFalse(PanelAssistantUpdateLease.persistedLeaseActive(-5L, synced, 1_000L))
        assertTrue(PanelAssistantUpdateLease.persistedLeaseActive(synced, synced, 1_000L))
        assertTrue(PanelAssistantUpdateLease.persistedLeaseActive(synced, synced + lease - 1, 1_000L))
        assertFalse(PanelAssistantUpdateLease.persistedLeaseActive(synced, synced + lease, 1_000L))
        assertTrue(PanelAssistantUpdateLease.persistedLeaseActive(synced + 1, synced, lease - 1))
        assertFalse(PanelAssistantUpdateLease.persistedLeaseActive(synced + 1, synced, lease))
    }

    @Test fun anObservationUnderAWrongClockIsRewrittenOnceTheClockSyncs() {
        val clocks = Clocks(wall = 60_000L, uptime = 60_000L)
        val subject = clocks.lease()
        subject.observe()
        assertEquals(listOf(60_000L), clocks.writes)
        clocks.wall = synced
        clocks.uptime += 30_000L
        subject.observe()
        assertEquals(listOf(60_000L, synced), clocks.writes)
        assertTrue(subject.active())
    }
}
