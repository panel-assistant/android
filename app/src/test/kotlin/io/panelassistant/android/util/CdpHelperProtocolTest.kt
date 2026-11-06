package io.panelassistant.android.util

import io.panelassistant.android.control.RelayExposureState
import io.panelassistant.android.control.cdpExposureState
import io.panelassistant.android.platform.HelperCdpState
import org.junit.Assert.*
import org.junit.Test

class CdpHelperProtocolTest {
    @Test fun capabilityRequiresMinorThirteenOnTheCarryingConnection() {
        val wire = wire("CDPSTATE ABSENT\n")
        wire.afterVersion = { wire.version = "HELPER version=1.12.0 proto=1.12" }
        assertEquals(HelperCdpState.ABSENT, wire.client.cdpState())
        assertNull(wire.client.cdpState())
        assertEquals(listOf(Triple(1, "CDPSTATE", 17L)), wire.bytes)
        assertEquals(listOf(1 to "VERSION", 2 to "VERSION"), wire.bootstraps)
        assertEquals(2, wire.closed)
        assertTrue(wire.rawCommands.isEmpty())
        for (version in listOf("ERR", "HELPER version=1.12.0 proto=1.12", "HELPER version=2.0.0 proto=2.13", "HELPER version=1.13.0 proto=1.13 proto=1.13")) {
            val refused = wire("CDPSTATE ABSENT\n").apply { this.version = version }
            assertNull(refused.client.cdpState()); assertTrue(refused.bytes.isEmpty())
        }
    }

    @Test fun completeExactFrameIsRequiredForEachStrictState() {
        for (state in HelperCdpState.entries) {
            assertEquals(state, wire("CDPSTATE ${state.name}\n").client.cdpState())
        }
        for (raw in listOf(null, "ERR\n", "CDPSTATE ABSENT", "CDPSTATE ABSENT\r\n", "CDPSTATE ABSENT\n\n", "CDPSTATE absent\n", "CDPSTATE UNKNOWN extra\n", " CDPSTATE ABSENT\n", "CDPSTATE ABSENT\u0000", "CDPSTATE ABSENT\nX")) {
            assertNull(raw, wire(raw).client.cdpState())
        }
    }

    @Test fun repeatedMissingOldRefusedOrMalformedObservationsRemainUnknown() {
        for (mode in listOf("missing", "old", "refused", "malformed", "unknown")) {
            val wire = wire("CDPSTATE ABSENT\n")
            when (mode) {
                "missing" -> wire.missing = true
                "old" -> wire.version = "HELPER version=1.12.0 proto=1.12"
                "refused" -> wire.replies["CDPSTATE"] = "ERR\n".toByteArray()
                "malformed" -> wire.replies["CDPSTATE"] = "CDPSTATE ABSENT".toByteArray()
                "unknown" -> wire.replies["CDPSTATE"] = "CDPSTATE UNKNOWN\n".toByteArray()
            }
            repeat(3) { assertEquals(mode, RelayExposureState.UNKNOWN, cdpExposureState(wire.client)) }
            assertTrue(wire.rawCommands.isEmpty())
        }
        val wire = wire("CDPSTATE PRESENT\n")
        assertEquals(RelayExposureState.PRESENT, cdpExposureState(wire.client))
        wire.replies["CDPSTATE"] = "CDPSTATE ABSENT\n".toByteArray()
        assertEquals(RelayExposureState.ABSENT, cdpExposureState(wire.client))
        assertEquals(2, wire.opened); assertEquals(2, wire.closed)
    }

    private fun wire(raw: String?) = ZigbeeReadFixture().apply {
        version = "HELPER version=1.13.0 proto=1.13"
        replies["CDPSTATE"] = raw?.toByteArray()
    }
}
