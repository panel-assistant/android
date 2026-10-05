package io.panelassistant.android.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HiveMqTransportBoundsTest {
    @Test fun `inbound limits remain application sized`() {
        assertEquals(65_536, HiveMqTransport.MAX_INBOUND_PACKET_BYTES)
        assertTrue(HiveMqTransport.MAX_INBOUND_IN_FLIGHT in 1..32)
    }
}
