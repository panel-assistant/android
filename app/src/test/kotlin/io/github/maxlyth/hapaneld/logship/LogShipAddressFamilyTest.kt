package io.github.maxlyth.hapaneld.logship

import io.github.maxlyth.hapaneld.util.LogShipEndpoint
import io.github.maxlyth.hapaneld.util.LoopbackPortPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression cover for a dual-stack collector name resolving first to a global IPv6 address while
 * UDP reports a successful local send and nothing arrives. The same collector by IPv4 literal works
 * on every transport.
 */
class LogShipAddressFamilyTest {

    private companion object {
        /**
         * Large enough that the gap between one shared deadline and one per candidate is seconds
         * wide. These socket tests run inside the composed-wave gate alongside other builds, so a
         * bound derived from a fast unloaded run reports contention as a shared-deadline defect.
         */
        const val DEADLINE_MS = 4_000L
    }

    private val v4: InetAddress = InetAddress.getByName("192.0.2.118")
    private val v6: InetAddress = InetAddress.getByName("2001:db8::1")

    @Test fun ipv4IsTriedBeforeIpv6() {
        // The exact resolution order the panel saw: AAAA records first.
        val ordered = LogShipEndpoint.orderedCandidates(listOf(v6, v4))

        assertTrue("IPv4 must be tried first", ordered.first() is Inet4Address)
        assertEquals(listOf(v4, v6), ordered)
    }

    @Test fun everyAddressIsKeptSoAStreamTransportCanFallBack() {
        val ordered = LogShipEndpoint.orderedCandidates(listOf(v6, v4))

        // Dropping the non-preferred family would turn a recoverable TCP/HTTP failure into a
        // permanent one for a collector that genuinely listens on IPv6 only.
        assertEquals(2, ordered.size)
        assertTrue(ordered.any { it is Inet6Address })
    }

    @Test fun aSingleFamilyNameIsUnaffected() {
        // An IPv6-only collector has nothing else to choose, so ordering must not strand it.
        assertEquals(listOf(v6), LogShipEndpoint.orderedCandidates(listOf(v6)))
        assertEquals(listOf(v4), LogShipEndpoint.orderedCandidates(listOf(v4)))
        assertEquals(emptyList<InetAddress>(), LogShipEndpoint.orderedCandidates(emptyList()))
    }

    @Test fun relativeOrderWithinAFamilyIsPreserved() {
        val a = InetAddress.getByName("10.0.0.1")
        val b = InetAddress.getByName("10.0.0.2")

        // Only the family grouping is imposed; the resolver's own ordering within a family is the
        // platform's business and must survive.
        assertEquals(listOf(a, b, v6), LogShipEndpoint.orderedCandidates(listOf(a, b, v6)))
    }

    /**
     * The end-to-end shape of the hardware defect: a name that resolves to an unreachable address
     * first and a live one second must still ship.
     *
     * The two halves are different FAMILIES, which is why this one was missed on the first pass: it
     * reserved the port on `::1` alone and assumed the same number was free on `127.0.0.1`. Family
     * does not change the hazard — the number was drawn from one address's ephemeral range and
     * anything holding it on the other could answer the route this test needs dead.
     */
    @Test(timeout = 30_000)
    fun tcpFallsBackPastAnUnreachableFirstAddress() {
        val dead = InetAddress.getByName("127.0.0.1")
        val live = InetAddress.getByName("::1")
        val routes = LoopbackPortPair.refusedAndLive(dead, live, liveBacklog = 4)
        routes.use {
            val listener = routes.live
            val port = routes.port
            val received = java.util.concurrent.ArrayBlockingQueue<String>(1)
            Thread {
                runCatching {
                    listener.accept().use { it.getInputStream().readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()?.let { received.offer(it) }
            }.apply { isDaemon = true }.start()

            val sink = NetworkLogSinkFactory.create(
                LogShipTarget("collector.test", port, LogShipEndpoint.SYSLOG_TCP, "panel"),
                { it },
                LogAddressResolver { _, _ -> listOf(dead, live) },
                1_500,
            )
            sink.use { it.connect(); it.send(listOf("<14>1 t h ha-paneld - - - fallback-probe\n")) }
            val frame = requireNotNull(received.poll(10, java.util.concurrent.TimeUnit.SECONDS)) {
                "nothing arrived at the live address"
            }
            assertTrue(frame, frame.contains("fallback-probe"))
        }
    }

    @Test(timeout = 15_000)
    fun httpPostFailureFallsBackAndPreservesOriginalHost() {
        val first = InetAddress.getByName("127.0.0.2")
        val second = InetAddress.getByName("127.0.0.1")
        val routes = LoopbackPortPair.bind(first, 4, second, 4)
        routes.first.use { rejected -> routes.second.use { accepted ->
            val requests = ArrayBlockingQueue<String>(2)
            serveHttp(rejected, 503, requests)
            serveHttp(accepted, 204, requests)
            val sink = NetworkLogSinkFactory.create(
                LogShipTarget("collector.example", rejected.localPort, LogShipEndpoint.HTTP, "panel"),
                { it },
                LogAddressResolver { _, _ -> listOf(first, second) },
                2_000,
            )
            sink.use { it.connect(); it.send(listOf("{\"message\":\"fallback\"}")) }
            val seen = listOf(requireNotNull(requests.poll(2, TimeUnit.SECONDS)), requireNotNull(requests.poll(2, TimeUnit.SECONDS)))
            assertTrue(seen.all { "Host: collector.example:${rejected.localPort}" in it })
        } }
    }

    @Test(timeout = 15_000)
    fun probeReportsCandidateThatActuallyAcceptedFallback() {
        val first = InetAddress.getByName("127.0.0.2")
        val second = InetAddress.getByName("127.0.0.1")
        val routes = LoopbackPortPair.bind(first, 4, second, 4)
        routes.first.use { a -> routes.second.use { b ->
            serveHttp(a, 503, ArrayBlockingQueue(1))
            serveHttp(b, 204, ArrayBlockingQueue(1))
            val result = NetworkLogSinkFactory.probe(
                LogShipTarget("collector.example", a.localPort, LogShipEndpoint.HTTP, "panel"),
                "{}".toByteArray(),
                LogAddressResolver { _, _ -> listOf(first, second) },
                2_000,
            )
            assertTrue(result.toString(), result.ok)
            assertEquals(second, result.candidate)
            assertEquals(204, result.status)
        } }
    }

    @Test(timeout = 5_000)
    fun probeReportsFinalCandidateWhenEveryAttemptFails() {
        val first = InetAddress.getByName("127.0.0.2")
        val second = InetAddress.getByName("127.0.0.3")
        // BOTH candidates must fail, so both ports are owned and refusing for the whole probe
        // rather than one of them being assumed free.
        val routes = LoopbackPortPair.refusedOnBoth(first, second)
        val port = routes.port
        val result = routes.use {
            NetworkLogSinkFactory.probe(
                LogShipTarget("collector.example", port, LogShipEndpoint.HTTP, "panel"),
                "{}".toByteArray(),
                LogAddressResolver { _, _ -> listOf(first, second) },
                1_000,
            )
        }
        assertTrue(result.toString(), !result.ok)
        assertEquals(second, result.candidate)
    }

    @Test(timeout = 5_000)
    fun httpStatusDoesNotLeakOntoLaterTransportFailure() {
        val first = InetAddress.getByName("127.0.0.2")
        val second = InetAddress.getByName("127.0.0.3")
        ServerSocket(0, 4, first).use { rejected ->
            serveHttp(rejected, 503, ArrayBlockingQueue(1))
            val result = NetworkLogSinkFactory.probe(
                LogShipTarget("collector.example", rejected.localPort, LogShipEndpoint.HTTP, "panel"),
                "{}".toByteArray(),
                LogAddressResolver { _, _ -> listOf(first, second) },
                1_000,
            )
            assertTrue(result.toString(), !result.ok)
            assertEquals(second, result.candidate)
            assertEquals(null, result.status)
            assertTrue(result.toString(), result.error != "http-503")
        }
    }

    @Test(timeout = 5_000)
    fun httpCloseInterruptsActivePost() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val accepted = ArrayBlockingQueue<Socket>(1)
            Thread { runCatching { listener.accept() }.getOrNull()?.let { accepted.offer(it) } }.apply { isDaemon = true }.start()
            val sink = NetworkLogSinkFactory.create(
                LogShipTarget("collector.test", listener.localPort, LogShipEndpoint.HTTP, "panel"),
                { it },
                LogAddressResolver { _, _ -> listOf(InetAddress.getLoopbackAddress()) },
                4_000,
            )
            sink.connect()
            val failure = AtomicReference<Throwable?>()
            val worker = Thread { runCatching { sink.send(listOf("{}")) }.exceptionOrNull().let(failure::set) }
            worker.start()
            val socket = requireNotNull(accepted.poll(2, TimeUnit.SECONDS))
            sink.close()
            worker.join(1_000)
            socket.close()
            assertTrue("close did not interrupt active POST", !worker.isAlive)
            assertTrue("send unexpectedly succeeded", failure.get() != null)
        }
    }

    @Test(timeout = 30_000)
    fun httpCandidatesShareOneAbsoluteDeadline() {
        val first = InetAddress.getByName("127.0.0.1")
        val second = InetAddress.getByName("127.0.0.2")
        // BOTH candidates must hang. With a listener on only the first, the second was refused
        // instantly, so one shared deadline and one deadline per candidate both finished in about
        // one deadline and this assertion could not tell them apart - it stayed green under a
        // mutation that made the budget per-candidate, at this revision and at every earlier one.
        // Two hanging candidates make the difference the whole span of a second deadline.
        val routes = LoopbackPortPair.bind(first, 1, second, 1)
        val hangs = Hangs()
        routes.use { hangs.use {
            hangs.on(routes.first)
            hangs.on(routes.second)
            val sink = NetworkLogSinkFactory.create(
                LogShipTarget("collector.test", routes.port, LogShipEndpoint.HTTP, "panel"),
                { it },
                LogAddressResolver { _, _ -> listOf(first, second) },
                DEADLINE_MS,
            )
            val started = System.nanoTime()
            sink.connect()
            runCatching { sink.send(listOf("{}")) }
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            sink.close()
            // Midway between the two outcomes: one shared deadline spends DEADLINE_MS in total, a
            // per-candidate deadline spends it twice. Seconds of slack on either side, so host load
            // moves the measurement without moving the verdict.
            assertTrue(
                "candidate timeouts multiplied the ${DEADLINE_MS}ms deadline: ${elapsed}ms",
                elapsed < DEADLINE_MS * 3 / 2,
            )
        } }
    }

    /**
     * Accepts and holds connections so a candidate consumes its whole deadline instead of failing
     * fast, and owns what it accepts. Sleeping in a detached daemon thread would leave sockets alive
     * for seconds after the test returned, contaminating whatever ran next in the same JVM; these
     * are parked on a latch instead, then closed and joined on the way out.
     */
    private class Hangs : AutoCloseable {
        private val release = java.util.concurrent.CountDownLatch(1)
        private val threads = mutableListOf<Thread>()
        private val accepted = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        private val listeners = mutableListOf<ServerSocket>()

        fun on(server: ServerSocket) {
            listeners += server
            threads += Thread {
                runCatching { server.accept() }.getOrNull()?.let { socket ->
                    accepted.add(socket)
                    runCatching { release.await() }
                }
            }.apply { isDaemon = true; start() }
        }

        override fun close() {
            release.countDown()
            // Close the LISTENERS first. A thread still parked in accept() only returns when its
            // ServerSocket closes, so joining before that just burned the timeout on every one.
            listeners.forEach { runCatching { it.close() } }
            synchronized(accepted) { accepted.forEach { runCatching { it.close() } } }
            threads.forEach { it.join(2_000) }
        }
    }

    private fun serveHttp(server: ServerSocket, status: Int, requests: ArrayBlockingQueue<String>) {
        Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                    val length = head.lines().firstOrNull { it.startsWith("Content-Length:", true) }
                        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) read += input.read(body, read, length - read)
                    requests.offer(head.toString() + body.toString(Charsets.UTF_8))
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
