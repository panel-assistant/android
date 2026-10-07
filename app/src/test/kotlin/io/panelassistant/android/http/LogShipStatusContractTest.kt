package io.panelassistant.android.http

import io.panelassistant.android.logship.LogShipStatusProjection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogShipStatusContractTest {
    @Test fun endpointProjectionSerializesTheDedicatedLiveState() {
        val json = JSONObject(logShipStatusJson(LogShipStatusProjection(true, true, "tcp://collector:514 · connected")))
        assertTrue(json.getBoolean("enabled"))
        assertTrue(json.getBoolean("configured"))
        assertEquals("tcp://collector:514 · connected", json.getString("text"))
    }
}
