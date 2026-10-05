package io.panelassistant.android.http

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the current stateless probe without constructing the Android-backed server graph.
 *
 * A deterministic DNS-stall and exact-attempt-count test still needs one production seam: move the
 * probe into an internal BrokerProbe with injected resolve(host) and connect(host, port, timeoutMs)
 * functions plus an overall deadline. The current hardwired InetAddress call has no controllable clock
 * or resolver, so a unit test cannot safely manufacture a blocked DNS lookup.
 */
class BrokerProbeAdversarialTest {
    @Test(timeout = 3_000)
    fun `invalid broker is rejected before a socket is opened`() {
        val result = probe("http://not-an-mqtt-broker.example:1883")

        assertEquals(false, result.getBoolean("ok"))
        assertEquals("invalid-url", result.getString("error"))
        assertEquals(setOf("ok", "error"), result.keys().asSequence().toSet())
    }

    @Test(timeout = 3_000)
    fun `reachable loopback broker reports the resolved endpoint`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val result = probe("tcp://127.0.0.1:${listener.localPort}")

            assertTrue(result.getBoolean("ok"))
            assertEquals("127.0.0.1", result.getString("host"))
            assertEquals(listener.localPort, result.getInt("port"))
            assertEquals("127.0.0.1", result.getString("resolved"))
            assertFalse(result.has("error"))
        }
    }

    @Test(timeout = 3_000)
    fun `closed loopback endpoint returns unreachable within the connect bound`() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val started = System.nanoTime()

        val result = probe("mqtt://127.0.0.1:$port")

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertFalse(result.getBoolean("ok"))
        assertEquals("unreachable", result.getString("error"))
        assertEquals(port, result.getInt("port"))
        assertTrue("probe exceeded its 2 second socket-connect bound: ${elapsedMs}ms", elapsedMs < 2_500)
    }

    private fun probe(url: String): JSONObject = JSONObject(probeBrokerJson(url))
}
