package io.github.maxlyth.hapaneld.http

import java.io.File
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
        val document = JSONObject(sourceFile("src/main/assets/openapi.json").readText())
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
        val response = operation.getJSONObject("responses").getJSONObject("200").getString("description")
        assertTrue(response.contains("false for both syslog-udp and syslog-tcp"))
        assertTrue(response.contains("marker must be verified"))
    }

    private fun sourceFile(path: String): File = sequenceOf(File(path), File("app/$path"))
        .first { it.isFile }
}
