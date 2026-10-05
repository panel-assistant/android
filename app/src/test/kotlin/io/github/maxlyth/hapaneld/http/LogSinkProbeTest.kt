package io.github.maxlyth.hapaneld.http

import io.panelassistant.android.BuildConfig
import io.github.maxlyth.hapaneld.logship.LogShipRecord
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers POST /api/v1/config/probe-log-sink without constructing the Android-backed server graph,
 * the same way [BrokerProbeAdversarialTest] covers the broker probe.
 *
 * The behaviour worth pinning is the honesty of the UDP verdict: a datagram that leaves the panel
 * proves nothing about arrival, so the probe must report `delivered:false` even when it succeeds.
 */
class LogSinkProbeTest {

    @Test(timeout = 5_000)
    fun `a blank host is rejected before any network work`() {
        val result = probe(host = "")

        assertFalse(result.getBoolean("ok"))
        assertEquals("no-host", result.getString("error"))
        assertEquals(setOf("ok", "error"), result.keys().asSequence().toSet())
    }

    @Test(timeout = 5_000)
    fun `an unresolvable host names the resolution failure`() {
        val result = probe(host = "no-such-host.invalid")

        assertFalse(result.getBoolean("ok"))
        assertEquals("unresolvable", result.getString("error"))
        assertEquals("no-such-host.invalid", result.getString("host"))
    }

    /**
     * A bare `connect()` succeeds against *any* listening socket — an MQTT broker, an SSH daemon, a
     * mistyped port belonging to something else — so it cannot tell a working log sink from an
     * unrelated service. The probe must put a real RFC5424 frame on the wire and the test must read
     * it back, or "Connected" is an assertion the check never earned.
     */
    @Test(timeout = 5_000)
    fun `a tcp probe writes a real syslog frame rather than just connecting`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val received = java.util.concurrent.ArrayBlockingQueue<String>(1)
            Thread {
                runCatching {
                    listener.accept().use { it.getInputStream().readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()?.let { received.offer(it) }
            }.apply { isDaemon = true }.start()

            val result = probe(host = "127.0.0.1", port = listener.localPort, protocol = "syslog-tcp")

            assertTrue(result.toString(), result.getBoolean("ok"))
            assertFalse("a socket write is not collector acknowledgement", result.getBoolean("delivered"))
            assertEquals("syslog-tcp", result.getString("protocol"))
            assertEquals("127.0.0.1", result.getString("resolved"))
            assertFalse(result.has("error"))

            val frame = requireNotNull(received.poll(3, TimeUnit.SECONDS)) { "nothing was written" }
            assertTrue(frame, frame.startsWith("<14>1 "))
            assertTrue(frame, " test-panel ha-paneld - - ${LogShipRecord.structuredData(LogShipRecord.Build.CURRENT)} " in frame)
            assertTrue(frame, frame.trimEnd('\n').endsWith(result.getString("marker")))
            assertTrue("stream framing needs the delimiter", frame.endsWith("\n"))
        }
    }

    @Test(timeout = 5_000)
    fun `an http probe posts a real ndjson record and honours the status`() {
        HttpStub(status = 204).use { stub ->
            val result = probe(host = "127.0.0.1", port = stub.port, protocol = "http")

            assertTrue(result.toString(), result.getBoolean("ok"))
            assertTrue(result.getBoolean("delivered"))
            assertEquals(204, result.getInt("status"))
            val request = requireNotNull(stub.take()) { "nothing was posted" }
            assertTrue(request, request.startsWith("POST / HTTP/1.1"))
            assertTrue(request, "application/x-ndjson" in request)
            val event = JSONObject(request.substringAfter("\r\n\r\n").trim())
            assertEquals("ha-paneld", event.getString("app"))
            assertEquals(result.getString("marker"), event.getString("message"))
            assertEquals(BuildConfig.VERSION_CODE, event.getInt("versionCode"))
            assertEquals(BuildConfig.APPLICATION_ID, event.getString("package"))
        }
    }

    /** A listener that answers but rejects our format is a real, nameable failure — not "connected". */
    @Test(timeout = 5_000)
    fun `an http collector that rejects the record is not reported as working`() {
        HttpStub(status = 415).use { stub ->
            val result = probe(host = "127.0.0.1", port = stub.port, protocol = "http")

            assertFalse(result.getBoolean("ok"))
            assertEquals("http-415", result.getString("error"))
            assertEquals(415, result.getInt("status"))
        }
    }

    /** A port with nothing on it must fail, on every transport that can tell. */
    @Test(timeout = 5_000)
    fun `an http probe against a dead port is unreachable`() {
        val dead = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val result = probe(host = "127.0.0.1", port = dead, protocol = "http")

        assertFalse(result.getBoolean("ok"))
        assertEquals("unreachable", result.getString("error"))
    }

    /** Minimal HTTP endpoint that records one request and answers with a fixed status. */
    private class HttpStub(private val status: Int) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        private val requests = java.util.concurrent.ArrayBlockingQueue<String>(4)
        val port: Int get() = server.localPort

        init {
            Thread {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: return@Thread
                    client.use { socket ->
                        val input = socket.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) {
                            val b = input.read()
                            if (b < 0) return@use
                            head.append(b.toChar())
                        }
                        val length = head.lines()
                            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                        val body = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val n = input.read(body, read, length - read)
                            if (n < 0) break
                            read += n
                        }
                        requests.offer(head.toString() + body.copyOf(read).toString(Charsets.UTF_8))
                        socket.getOutputStream().apply {
                            write(
                                "HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.UTF_8),
                            )
                            flush()
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        fun take(): String? = requests.poll(3, TimeUnit.SECONDS)

        override fun close() = server.close()
    }

    @Test(timeout = 5_000)
    fun `a closed tcp port returns unreachable within the connect bound`() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val started = System.nanoTime()

        val result = probe(host = "127.0.0.1", port = port, protocol = "syslog-tcp")

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertFalse(result.getBoolean("ok"))
        assertEquals("unreachable", result.getString("error"))
        assertTrue("probe exceeded its 2 second connect bound: ${elapsedMs}ms", elapsedMs < 2_500)
    }

    @Test(timeout = 5_000)
    fun `udp sends one marked datagram and refuses to claim it arrived`() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 3_000
            val result = probe(host = "127.0.0.1", port = receiver.localPort, protocol = "syslog-udp")

            assertTrue(result.getBoolean("ok"))
            // The whole point: UDP cannot confirm delivery, so the probe must not pretend otherwise.
            assertFalse("UDP must never report delivery", result.getBoolean("delivered"))
            val marker = result.getString("marker")
            assertTrue(marker, marker.startsWith("ha-paneld-sink-probe-"))

            val packet = DatagramPacket(ByteArray(4096), 4096)
            receiver.receive(packet)
            val frame = String(packet.data, 0, packet.length, Charsets.UTF_8)
            // A well-formed RFC5424 frame, so a real collector accepts the probe rather than dropping
            // it — a probe its own collector discards would be worse than no probe at all.
            assertTrue(frame, frame.startsWith("<14>1 "))
            assertTrue(frame, " test-panel ha-paneld - - ${LogShipRecord.structuredData(LogShipRecord.Build.CURRENT)} " in frame)
            assertTrue(frame, frame.endsWith(marker))
        }
    }

    @Test(timeout = 5_000)
    fun `a scheme typed into the host field selects the transport`() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            // Stored protocol says TCP; the typed scheme says UDP and must win.
            val result = probe(
                host = "udp://127.0.0.1:${receiver.localPort}",
                port = 514,
                protocol = "syslog-tcp",
            )

            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("syslog-udp", result.getString("protocol"))
            assertEquals(receiver.localPort, result.getInt("port"))
            assertEquals("127.0.0.1", result.getString("host"))
        }
    }

    private fun probe(
        host: String,
        port: Int = 514,
        protocol: String = "syslog-udp",
        panelId: String = "test-panel",
    ): JSONObject = JSONObject(probeLogSinkJson(host, port, protocol, panelId))
}
