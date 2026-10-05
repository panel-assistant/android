package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbMaintenanceSecurityReadyTest {
    @Test fun `exact durable Hardened epoch and repeated relay proof admit DB free maintenance`() {
        val events = mutableListOf<String>()

        assertTrue(guardDbMaintenanceSecurityReady(
            expectedEpoch = 41L,
            durableHardenedEpoch = { events += "epoch"; 41L },
            relayRunning = { events += "relay"; false },
            remoteDebugOff = { events += "adb"; true },
        ))
        assertEquals(listOf("epoch", "relay", "adb", "epoch", "relay"), events)
    }

    @Test fun `missing wrong or drifting Hardened epoch never admits maintenance`() {
        listOf("absent", "corrupt", "TRANSITION").forEach { state ->
            assertFalse(state, guardDbMaintenanceSecurityReady(41L, { null }, { false }, { true }))
        }
        listOf(40L, 42L).forEach { epoch ->
            assertFalse(epoch.toString(), guardDbMaintenanceSecurityReady(41L, { epoch }, { false }, { true }))
        }
        assertFalse(guardDbMaintenanceSecurityReady(0L, { 41L }, { false }, { true }))

        var read = 0
        assertFalse(guardDbMaintenanceSecurityReady(
            expectedEpoch = 41L,
            durableHardenedEpoch = { if (read++ == 0) 41L else 42L },
            relayRunning = { false },
            remoteDebugOff = { true },
        ))
        assertEquals(2, read)
    }

    @Test fun `relay before or after ADB proof and failed ADB proof all refuse`() {
        listOf("present", "unknown").forEach { relayState ->
            var adbCalls = 0
            assertFalse(relayState, guardDbMaintenanceSecurityReady(
                41L,
                { 41L },
                { true },
                { adbCalls++; true },
            ))
            assertEquals(relayState, 0, adbCalls)
        }

        var relayRead = 0
        assertFalse(guardDbMaintenanceSecurityReady(
            41L,
            { 41L },
            { relayRead++ != 0 },
            { true },
        ))
        assertEquals(2, relayRead)

        listOf("active", "unknown").forEach { adbState ->
            var epochRead = 0
            assertFalse(adbState, guardDbMaintenanceSecurityReady(
                41L,
                { epochRead++; 41L },
                { false },
                { false },
            ))
            assertEquals("failed ADB proof must short-circuit the second epoch read", 1, epochRead)
        }
    }
}
