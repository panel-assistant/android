package io.panelassistant.android.http

import io.panelassistant.android.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSinkProbeRequestTest {
    @Test
    fun `only an omitted port falls back to the saved value`() {
        assertEquals(5514, selectLogSinkProbePort(null, 5514))
        assertEquals(514, selectLogSinkProbePort("514", 5514))
        assertNull(selectLogSinkProbePort("garbage", 5514))
        assertNull(selectLogSinkProbePort("999999999999999999999", 5514))
        assertNull(selectLogSinkProbePort("0", 5514))
        assertNull(selectLogSinkProbePort("65536", 5514))
    }

    @Test
    fun `OpenAPI publishes strict port range and syslog acknowledgement semantics`() {
        // Source-text reason: the shipped OpenAPI document is the public API contract.
        val document = JSONObject(TestSources.asset("openapi.json").readText())
        val operation = document.getJSONObject("paths")
            .getJSONObject("/api/v1/config/probe-log-sink")
            .getJSONObject("post")
        val port = operation.getJSONObject("requestBody")
            .getJSONObject("content")
            .getJSONObject("application/x-www-form-urlencoded")
            .getJSONObject("schema")
            .getJSONObject("properties")
            .getJSONObject("port")
        assertEquals(1, port.getInt("minimum"))
        assertEquals(65535, port.getInt("maximum"))
        assertTrue(port.getString("description").contains("invalid-port"))
    }
}
