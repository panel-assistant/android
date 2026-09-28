package io.github.maxlyth.hapaneld.logship

import io.github.maxlyth.hapaneld.util.LogShipEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Transport-level coverage: every case drives the **real** [NetworkLogSinkFactory] against a real
 * loopback socket and asserts the bytes that actually arrive.
 *
 * [LogShipperTest] covers generation lifecycle through an in-memory sink, which by construction
 * cannot tell UDP from TCP or a framed line from an unframed one. That gap is exactly how a
 * TCP-only "syslog" transport aimed at the UDP default port passed every test it had, so these
 * assertions are deliberately about wire bytes rather than about the shipper's internal state.
 */
class LogShipTransportTest {

    // ---- receivers ------------------------------------------------------------------------------

    private class UdpReceiver : AutoCloseable {
        private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val datagrams = CopyOnWriteArrayList<ByteArray>()
        val port: Int get() = socket.localPort

        init {
            thread(isDaemon = true, name = "udp-receiver") {
                val buffer = ByteArray(64 * 1024)
                while (!socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: Exception) {
                        return@thread
                    }
                    datagrams += packet.data.copyOfRange(0, packet.length)
                }
            }
        }

        override fun close() = socket.close()
    }

    private class TcpReceiver : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        private val sink = ByteArrayOutputStream()
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true, name = "tcp-receiver") {
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (_: Exception) { return@thread }
                    thread(isDaemon = true, name = "tcp-receiver-conn") {
                        client.use {
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                val read = try { it.getInputStream().read(buffer) } catch (_: Exception) { -1 }
                                if (read <= 0) break
                                synchronized(sink) { sink.write(buffer, 0, read) }
                            }
                        }
                    }
                }
            }
        }

        fun text(): String = synchronized(sink) { sink.toByteArray() }.toString(Charsets.UTF_8)

        override fun close() = server.close()
    }

    /** Minimal one-shot HTTP endpoint: records each request and answers with [status]. */
    private class HttpReceiver(private val status: Int = 204) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        val requestLines = CopyOnWriteArrayList<String>()
        val headers = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true, name = "http-receiver") {
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (_: Exception) { return@thread }
                    thread(isDaemon = true, name = "http-receiver-conn") {
                        client.use { socket ->
                            val input = socket.getInputStream()
                            val head = StringBuilder()
                            // Read byte-at-a-time to stop exactly at the header terminator; the body
                            // that follows is binary-framed by Content-Length, not by lines.
                            while (!head.endsWith("\r\n\r\n")) {
                                val b = input.read()
                                if (b < 0) return@use
                                head.append(b.toChar())
                            }
                            val lines = head.trim().split("\r\n")
                            requestLines += lines.first()
                            headers += lines.drop(1).joinToString("\n")
                            val length = lines.drop(1)
                                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                            val body = ByteArray(length)
                            var read = 0
                            while (read < length) {
                                val n = input.read(body, read, length - read)
                                if (n < 0) break
                                read += n
                            }
                            bodies += body.copyOf(read).toString(Charsets.UTF_8)
                            socket.getOutputStream().write(
                                "HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .toByteArray(Charsets.UTF_8),
                            )
                            socket.getOutputStream().flush()
                        }
                    }
                }
            }
        }

        override fun close() = server.close()
    }

    // ---- shipper harness ------------------------------------------------------------------------

    /** A live [LogShipper] on the real network factory, pointed at a loopback receiver. */
    private class Harness(
        protocol: String,
        port: Int,
        host: String = "127.0.0.1",
        panelId: String = "panel-a",
    ) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "logship-transport-test").apply { isDaemon = true }
        }
        private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        private val listener = AtomicReference<((String) -> Unit)?>(null)

        val shipper = LogShipper(
            configSnapshot = { LogShipConfigSnapshot(true, host, port, protocol, panelId) },
            scope = scope,
            subscribeCapture = { candidate ->
                listener.set(candidate)
                AutoCloseable { listener.compareAndSet(candidate, null) }
            },
            sinkFactory = NetworkLogSinkFactory,
        )

        fun start() = shipper.start()

        /** Feed a captured line, exactly as [LogCapture] would. */
        fun emit(line: String) {
            val target = requireNotNull(listener.get()) { "capture was never subscribed" }
            target(line)
        }

        override fun close() {
            shipper.stop()
            scope.cancel()
            executor.shutdownNow()
        }
    }

    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition()) {
            assertTrue("timed out waiting for condition", System.nanoTime() < deadline)
            Thread.sleep(10)
        }
    }

    /**
     * The parenthesised failure reason from a status line, or null while none has been reported.
     *
     * Deliberately strict: a run reports "disconnected" from the instant it is created, before the
     * transport has attempted anything, so a test that waits only for that word proves nothing. Only
     * the bracketed reason means a connection was really tried and really failed.
     */
    private fun failureReason(status: String): String? =
        FAILURE_REASON.find(status)?.groupValues?.get(1)

    /** `<PRI>1 TIMESTAMP HOSTNAME APP-NAME PROCID MSGID STRUCTURED-DATA MSG` (RFC5424 §6). */
    private data class Frame(
        val pri: Int,
        val timestamp: String,
        val hostname: String,
        val appName: String,
        val procId: String,
        val msgId: String,
        val structuredData: String,
        val message: String,
    )

    private fun parseRfc5424(raw: String): Frame {
        val match = requireNotNull(RFC5424.matchEntire(raw)) { "not an RFC5424 frame: $raw" }
        val (pri, ts, host, app, procId, msgId, sd, msg) = match.destructured
        return Frame(pri.toInt(), ts, host, app, procId, msgId, sd, msg)
    }

    /** A logcat `threadtime` line, the exact shape [LogCapture] emits. */
    private fun logcatLine(level: String, message: String) =
        "07-26 12:34:56.789  1234  5678 $level ha-paneld: $message"

    // ---- UDP ------------------------------------------------------------------------------------

    @Test(timeout = 20_000)
    fun udpSyslogSendsOneUnframedRfc5424DatagramPerLine() {
        UdpReceiver().use { receiver ->
            Harness(LogShipEndpoint.SYSLOG_UDP, receiver.port).use { harness ->
                harness.start()
                harness.emit(logcatLine("I", "first"))
                harness.emit(logcatLine("W", "second"))
                await { receiver.datagrams.size >= 2 }

                // One datagram per line: the length is the frame, so batching must not concatenate.
                assertEquals(2, receiver.datagrams.size)
                val frames = receiver.datagrams.map { parseRfc5424(it.toString(Charsets.UTF_8)) }

                // No stream framing inside a datagram — a trailing newline is RFC6587's, not RFC5426's.
                receiver.datagrams.forEach {
                    assertFalse(
                        "datagram must not carry stream framing",
                        it.toString(Charsets.UTF_8).endsWith("\n"),
                    )
                }

                assertEquals(listOf("ha-paneld", "ha-paneld"), frames.map { it.appName })
                assertEquals(listOf("panel-a", "panel-a"), frames.map { it.hostname })
                // PROCID / MSGID are the RFC's explicit nil value; STRUCTURED-DATA names the build.
                assertEquals(listOf("-", "-"), frames.map { it.procId })
                assertEquals(listOf("-", "-"), frames.map { it.msgId })
                val sd = LogShipRecord.structuredData(LogShipRecord.Build.CURRENT)
                assertEquals(listOf(sd, sd), frames.map { it.structuredData })
                assertTrue(frames[0].message.endsWith("first"))
                assertTrue(frames[1].message.endsWith("second"))
            }
        }
    }

    @Test(timeout = 20_000)
    fun udpSeverityFollowsTheLogcatLevel() {
        // facility 1 (user) → PRI = 8 + severity.
        val expected = mapOf("V" to 15, "D" to 15, "I" to 14, "W" to 12, "E" to 11, "F" to 10)
        UdpReceiver().use { receiver ->
            Harness(LogShipEndpoint.SYSLOG_UDP, receiver.port).use { harness ->
                harness.start()
                expected.keys.forEach { harness.emit(logcatLine(it, "level probe")) }
                // A line the level regex cannot parse must default to informational rather than drop.
                harness.emit("not a logcat line at all")
                await { receiver.datagrams.size >= expected.size + 1 }

                val pris = receiver.datagrams.map { parseRfc5424(it.toString(Charsets.UTF_8)).pri }
                assertEquals(expected.values.toList() + 14, pris)
            }
        }
    }

    @Test(timeout = 20_000)
    fun udpTruncatesAnOversizeLineOnACharacterBoundary() {
        // Multi-byte throughout, so a naive byte cut would land mid-sequence and corrupt the frame.
        val oversize = "é".repeat(4_000)
        UdpReceiver().use { receiver ->
            Harness(LogShipEndpoint.SYSLOG_UDP, receiver.port).use { harness ->
                harness.start()
                harness.emit(logcatLine("I", oversize))
                await { receiver.datagrams.isNotEmpty() }

                val bytes = receiver.datagrams.first()
                assertTrue(
                    "datagram ${bytes.size} B exceeded the ${LogShipper.UDP_DATAGRAM_MAX_BYTES} B cap",
                    bytes.size <= LogShipper.UDP_DATAGRAM_MAX_BYTES,
                )
                val text = bytes.toString(Charsets.UTF_8)
                // A clean boundary round-trips: re-encoding yields the identical bytes, and no
                // replacement character was produced by decoding a severed sequence.
                assertArrayEquals(bytes, text.toByteArray(Charsets.UTF_8))
                assertFalse("truncation split a UTF-8 sequence", text.contains('�'))
                assertTrue("truncation must be visible in the record", text.endsWith("…"))
                parseRfc5424(text)
            }
        }
    }

    @Test(timeout = 20_000)
    fun udpStatusNeverClaimsToBeConnected() {
        UdpReceiver().use { receiver ->
            Harness(LogShipEndpoint.SYSLOG_UDP, receiver.port).use { harness ->
                harness.start()
                harness.emit(logcatLine("I", "status probe"))
                await { receiver.datagrams.isNotEmpty() }
                await { "1 record sent" in harness.shipper.statusText() }

                val status = harness.shipper.statusText()
                // "connected" would assert a delivery guarantee UDP cannot make; a black-holed sink
                // would then be indistinguishable from a healthy one.
                assertFalse("UDP must not report a connection: $status", "connected" in status)
                // The destination is stated by the settings, not repeated here.
                assertFalse(status, "127.0.0.1" in status)
                assertFalse(status, "udp://" in status)
                assertTrue(status, "unacknowledged" in status)
            }
        }
    }

    // ---- TCP ------------------------------------------------------------------------------------

    @Test(timeout = 20_000)
    fun tcpSyslogSendsNewlineDelimitedFrames() {
        TcpReceiver().use { receiver ->
            Harness(LogShipEndpoint.SYSLOG_TCP, receiver.port).use { harness ->
                harness.start()
                harness.emit(logcatLine("E", "alpha"))
                harness.emit(logcatLine("I", "beta"))
                await { receiver.text().count { it == '\n' } >= 2 }
                await { "2 records sent" in harness.shipper.statusText() }

                val frames = receiver.text().split("\n").filter { it.isNotBlank() }.map(::parseRfc5424)
                assertEquals(2, frames.size)
                assertEquals(11, frames[0].pri)   // user.error
                assertEquals(14, frames[1].pri)   // user.info
                assertTrue(frames[0].message.endsWith("alpha"))
                assertTrue(frames[1].message.endsWith("beta"))
                assertTrue("stream framing requires a trailing delimiter", receiver.text().endsWith("\n"))
                assertTrue("connected" in harness.shipper.statusText())
            }
        }
    }

    /**
     * The reported defect, as a test: a stock collector listening only on UDP/514 refuses TCP, and
     * the whole point of the UDP transport is that this is no longer the default experience.
     */
    @Test(timeout = 20_000)
    fun tcpAgainstAUdpOnlyCollectorFailsWhileUdpSucceeds() {
        UdpReceiver().use { receiver ->
            Harness(LogShipEndpoint.SYSLOG_TCP, receiver.port).use { harness ->
                harness.start()
                harness.emit(logcatLine("I", "tcp attempt"))
                await { failureReason(harness.shipper.statusText()) != null }
                assertTrue(receiver.datagrams.isEmpty())
            }
            Harness(LogShipEndpoint.SYSLOG_UDP, receiver.port).use { harness ->
                harness.start()
                harness.emit(logcatLine("I", "udp attempt"))
                await { receiver.datagrams.isNotEmpty() }
            }
        }
    }

    // ---- HTTP -----------------------------------------------------------------------------------

    @Test(timeout = 20_000)
    fun httpPostsAnNdjsonBatch() {
        HttpReceiver().use { receiver ->
            Harness(LogShipEndpoint.HTTP, receiver.port).use { harness ->
                harness.start()
                harness.emit(logcatLine("I", "one"))
                await { receiver.bodies.isNotEmpty() }

                assertEquals("POST / HTTP/1.1", receiver.requestLines.first())
                assertTrue(
                    receiver.headers.first(),
                    "application/x-ndjson" in receiver.headers.first(),
                )
                val event = JSONObject(receiver.bodies.first().trim().lines().first())
                assertEquals("ha-paneld", event.getString("app"))
                assertEquals("panel-a", event.getString("host"))
                assertTrue(event.getString("message").endsWith("one"))
                assertTrue(event.getString("timestamp").endsWith("Z"))
            }
        }
    }

    @Test(timeout = 20_000)
    fun httpRejectionIsCountedAsLossRatherThanSilentlyForgotten() {
        HttpReceiver(status = 500).use { receiver ->
            Harness(LogShipEndpoint.HTTP, receiver.port).use { harness ->
                harness.start()
                harness.emit(logcatLine("I", "rejected"))
                await { "1 dropped" in harness.shipper.statusText() }

                val status = harness.shipper.statusText()
                // The batch already left the bounded queue, so a rejection is real data loss and the
                // reason must name the response rather than just the destination.
                assertTrue(status, "HTTP 500" in status)
                assertTrue(status, "disconnected" in status)
            }
        }
    }

    // ---- failure reporting ------------------------------------------------------------------------

    /**
     * Regression for the reported symptom. `Socket.connect` on an unresolved address throws
     * `UnknownHostException` whose message *is* the hostname, so the warning used to read
     * "syslog udp://collector:514: udp://collector" — it named the destination and no fault at all.
     */
    @Test(timeout = 20_000)
    fun anUnresolvableHostReportsTheFaultNotJustItsOwnName() {
        val host = "no-such-host.invalid"
        Harness(LogShipEndpoint.SYSLOG_TCP, 514, host = host).use { harness ->
            harness.start()
            harness.emit(logcatLine("I", "unreachable"))
            await { failureReason(harness.shipper.statusText()) != null }

            val status = harness.shipper.statusText()
            val reason = requireNotNull(failureReason(status)) { "no reason was reported: $status" }
            assertFalse(
                "the reason merely repeated the destination: $status",
                reason.equals(host, ignoreCase = true),
            )
            // The fault is now named in plain words rather than by exception class, so the status
            // cannot carry a destination; "host not found" still says what went wrong.
            assertEquals("host not found", reason)
            assertFalse("the status must not carry the destination: $status", host in status)
        }
    }

    /** Raw transport failures can name their destination, so status must project a destination-free reason. */
    @Test(timeout = 20_000)
    fun aFailingSinkNeverPutsTheDestinationBackIntoTheStatus() {
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        Harness(LogShipEndpoint.SYSLOG_TCP, dead).use { harness ->
            harness.start()
            harness.emit(logcatLine("I", "no destination in status"))
            await { failureReason(harness.shipper.statusText()) != null }

            val status = harness.shipper.statusText()
            assertFalse(status, "127.0.0.1" in status)
            assertFalse(status, dead.toString() in status)
            assertFalse(status, "tcp://" in status)
            assertTrue(status, status.startsWith("disconnected (connection refused)"))
        }
    }

    @Test(timeout = 20_000)
    fun aClosedPortReportsTheRefusal() {
        // Bind then release, so the port is known-dead rather than merely unlikely to be in use.
        val dead = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        Harness(LogShipEndpoint.SYSLOG_TCP, dead).use { harness ->
            harness.start()
            harness.emit(logcatLine("I", "refused"))
            await { failureReason(harness.shipper.statusText()) != null }
            assertTrue(harness.shipper.statusText(), "0 records sent" in harness.shipper.statusText())
        }
    }

    private companion object {
        val RFC5424 = Regex(
            // SD is the nil value or SD-ELEMENTs whose quoted PARAM-VALUEs may hold spaces and escapes.
            """^<(\d+)>1 (\S+) (\S+) (\S+) (\S+) (\S+) (-|(?:\[(?:[^"\]]|"(?:[^"\\]|\\.)*")*\])+) (.*)$""",
            RegexOption.DOT_MATCHES_ALL,
        )

        val FAILURE_REASON = Regex("""disconnected \((.+?)\)""")
    }
}
