package io.panelassistant.android.util

import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.panelassistant.android.sensors.HaSocketState
import io.panelassistant.android.sensors.PathEchoSource
import io.panelassistant.android.sensors.PathProbeMonitor
import io.panelassistant.android.sensors.PathProbeRuntime
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Behavioral cover for the stranded-panel defect: a Home Assistant host whose AAAA is black-holed
 * from the panel's network segment while its A record works, where every previous WebSocket client
 * dialed exactly one resolved address, so the panel could never render despite a fully working
 * IPv4 path. These tests
 * drive the shared factory against real sockets: a dead route listed ahead of a live sibling must
 * still connect within the callers' 15 s outer deadline. The dead route is either an unrouted
 * documentation address, whose SYNs genuinely vanish, or a port this test owns without listening on,
 * which refuses immediately — both are real conditions the test controls end to end rather than
 * approximations built out of accept-queue saturation.
 *
 * The in-file [WsAcceptor] is a minimal RFC 6455 responder (101 upgrade + unmasked server frames),
 * deliberately dependency-free so this file exercises the production client stack alone.
 */
class HaWebSocketClientsFailoverTest {

    // ---- minimal WebSocket server harness ------------------------------------------------------

    private class WsAcceptor(
        bindAddress: InetAddress,
        private val payloadBytes: Int = 0,
        prebound: ServerSocket? = null,
    ) : AutoCloseable {
        val server = prebound ?: ServerSocket().apply { bind(InetSocketAddress(bindAddress, 0), 16) }
        val port: Int get() = server.localPort
        val upgrades = AtomicInteger()
        private val thread = Thread {
            runCatching {
                while (!server.isClosed) {
                    val socket = server.accept()
                    Thread { runCatching { serve(socket) } }.apply { isDaemon = true }.start()
                }
            }
        }.apply { isDaemon = true; start() }

        private fun serve(socket: Socket): Unit = socket.use { s ->
            val key = readUpgradeKey(s.getInputStream()) ?: return
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            )
            val out = s.getOutputStream()
            // Count BEFORE the response is written. The client returns from webSocketSession() the
            // moment it reads this 101, so a count published afterwards is a plain data race: the
            // test can reach its assertion while this thread is still between flush and increment.
            // Incrementing first orders the count ahead of the only thing the client waits on.
            upgrades.incrementAndGet()
            out.write(
                ("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
            )
            out.flush()
            if (payloadBytes > 0) writeTextFrame(out, ByteArray(payloadBytes) { 'a'.code.toByte() })
            // Hold the connection open until the client closes it.
            runCatching { while (s.getInputStream().read() >= 0) Unit }
        }

        private fun readUpgradeKey(input: InputStream): String? {
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                val byte = input.read()
                if (byte < 0) return null
                header.append(byte.toChar())
                if (header.length > 16_384) return null
            }
            return Regex("Sec-WebSocket-Key: (\\S+)", RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.get(1)
        }

        private fun writeTextFrame(out: OutputStream, payload: ByteArray) {
            out.write(0x81)
            when {
                payload.size < 126 -> out.write(payload.size)
                payload.size <= 0xFFFF -> {
                    out.write(126); out.write(payload.size ushr 8); out.write(payload.size and 0xFF)
                }
                else -> {
                    out.write(127)
                    for (shift in 56 downTo 0 step 8) out.write(((payload.size.toLong()) ushr shift).toInt() and 0xFF)
                }
            }
            out.write(payload)
            out.flush()
        }

        override fun close() { server.close() }
    }

    private companion object {
        /**
         * Timing assertions here separate two mechanisms, so each bound is derived from the
         * configured timeout that the losing mechanism would have to wait out — never from an
         * observed duration. Keeping the two far apart is what makes them load-tolerant: this suite
         * runs in the composed-wave gate beside other builds, and a bound that merely sits above a
         * fast local run turns host contention into a false failure for every lane in the wave.
         */
        const val ROUTE_TIMEOUT_MS = 20_000L

        /** Racing connects in ~250 ms; sequential iteration cannot finish before ROUTE_TIMEOUT_MS. */
        const val RACED_MAX_MS = 10_000L

        /**
         * OkHttp's own default connect timeout. Losing the configured value lands at this or, if
         * it is dropped to zero, at no bound at all; the assertion only needs to sit below the
         * shorter of the two.
         */
        const val ENGINE_DEFAULT_CONNECT_MS = 10_000L

        /**
         * Prompt cancellation releases in milliseconds. This keeps a real discriminator — a sixth
         * of the 30 s connect timeout, so "released early" cannot be satisfied by simply finishing
         * before it — while leaving room for a loaded scheduler.
         */
        const val PROMPT_CANCEL_MAX_MS = 5_000L
    }

    /**
     * The dead route is a real black hole, not a simulated one: RFC 5737 documentation space is not
     * routed anywhere, so the SYN is dropped exactly as it was on the panel segment whose AAAA went
     * nowhere. Nothing to saturate, no accept queue to fill, no port to own — an unrouted address is
     * dead at every port and is not a shared resource another build can take.
     */
    private val blackHoledAddress: InetAddress = InetAddress.getByName("192.0.2.1")

    private val liveLoopback: InetAddress = InetAddress.getByName("127.0.0.2")
    private val refusingLoopback: InetAddress = InetAddress.getByName("127.0.0.1")

    private fun loopbackAliasAvailable(): Boolean = runCatching {
        ServerSocket().use { it.bind(InetSocketAddress(liveLoopback, 0)); true }
    }.getOrDefault(false)

    // ---- route iteration -----------------------------------------------------------------------

    @Test fun routeCallbackReceivesThePeerOfTheConnectedWebSocket() {
        val expected = InetAddress.getByName("127.0.0.1")
        val connected = AtomicReference<InetAddress>()
        WsAcceptor(expected).use { server ->
            val client = HaWebSocketClients.client(
                onRouteConnected = { address, _ -> connected.set(address) },
            )
            try {
                runBlocking {
                    val session = withTimeout(15_000) {
                        HaWebSocketClients.open(
                            client,
                            "ws://127.0.0.1:${server.port}/api/websocket",
                            16L * 1024 * 1024,
                        )
                    }
                    session.close()
                }
            } finally {
                client.close()
            }
        }
        assertEquals("callback must expose the socket peer used by the WebSocket", expected, connected.get())
    }

    @Test fun diagnosticsDescribeTheConnectedFamilyAndItsOtherResolvedFamily() {
        val v4 = InetAddress.getByName("127.0.0.1")
        val v6 = InetAddress.getByName("::1")
        val cases = listOf(
            Triple(v4, listOf(v4, v6), "true"),
            Triple(v4, listOf(v4), "false"),
            Triple(v6, listOf(v6, v4), "true"),
            Triple(v6, listOf(v6), "false"),
        )
        val monitor = PathProbeMonitor(source = object : PathEchoSource {
            override fun burst(
                target: InetAddress,
                echoes: Int,
                perEchoTimeoutMs: Long,
                nowMs: () -> Long,
            ) = null
        })
        PathProbeRuntime.install(monitor) { 0L }
        try {
            // Keep one monitor across replacement connections: old DNS facts must not survive.
            for ((connectedAddress, answers, otherResolved) in cases) {
                WsAcceptor(connectedAddress).use { server ->
                    val lookups = AtomicInteger()
                    val client = HaWebSocketClients.client(
                        resolver = { lookups.incrementAndGet(); answers },
                        onRouteConnected = { address, otherResolved -> monitor.onRouteConnected(address, otherResolved) },
                    )
                    try {
                        runBlocking {
                            val session = withTimeout(15_000) {
                                HaWebSocketClients.open(
                                    client,
                                    "ws://panel-diag.test:${server.port}/api/websocket",
                                    16L * 1024 * 1024,
                                )
                            }
                            monitor.onSocketState(HaSocketState.LIVE)
                            val family = if (connectedAddress == v6) "ipv6" else "ipv4"
                            val line = PathProbeRuntime.diagnosticLine()
                            assertTrue(line, line.contains("family=$family other_resolved=$otherResolved"))
                            val status = org.json.JSONObject(PathProbeRuntime.statusJson())
                            assertEquals(family, status.getString("family"))
                            assertEquals(otherResolved.toBoolean(), status.getBoolean("other_resolved"))
                            assertEquals("diagnostics must not add DNS traffic", 1, lookups.get())
                            session.close()
                        }
                    } finally {
                        client.close()
                    }
                }
            }
        } finally {
            PathProbeRuntime.uninstall(monitor)
        }
    }

    @Test fun routeCallbackIgnoresARejectedWebSocketUpgrade() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val connected = AtomicReference<InetAddress>()
        val accepted = CountDownLatch(1)
        ServerSocket().apply { bind(InetSocketAddress(loopback, 0), 4) }.use { rejecting ->
            val server = Thread {
                runCatching {
                    rejecting.accept().use { socket ->
                        accepted.countDown()
                        socket.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 403 Forbidden\r\n" +
                                    "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(),
                            )
                            flush()
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val client = HaWebSocketClients.client(
                onRouteConnected = { address, _ -> connected.set(address) },
            )
            try {
                val result = runCatching {
                    runBlocking {
                        withTimeout(15_000) {
                            HaWebSocketClients.open(
                                client,
                                "ws://127.0.0.1:${rejecting.localPort}/api/websocket",
                                16L * 1024 * 1024,
                            )
                        }
                    }
                }
                assertTrue("a rejected upgrade must fail the WebSocket session", result.isFailure)
                assertTrue("the server never observed the TCP connection", accepted.await(5, TimeUnit.SECONDS))
                assertNull("a failed upgrade must not publish its TCP peer", connected.get())
            } finally {
                client.close()
                server.join(2_000)
            }
        }
    }

    @Test fun routeCallbackIgnoresAMalformedSwitchingProtocolsResponse() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val connected = AtomicReference<InetAddress>()
        ServerSocket().apply { bind(InetSocketAddress(loopback, 0), 4) }.use { malformed ->
            val server = Thread {
                runCatching {
                    malformed.accept().use { socket ->
                        socket.getOutputStream().apply {
                            write(
                                ("HTTP/1.1 101 Switching Protocols\r\n" +
                                    "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                                    "Sec-WebSocket-Accept: invalid\r\n\r\n").toByteArray(),
                            )
                            flush()
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val client = HaWebSocketClients.client(
                onRouteConnected = { address, _ -> connected.set(address) },
            )
            try {
                val result = runCatching {
                    runBlocking {
                        withTimeout(15_000) {
                            HaWebSocketClients.open(
                                client,
                                "ws://127.0.0.1:${malformed.localPort}/api/websocket",
                                16L * 1024 * 1024,
                            )
                        }
                    }
                }
                assertTrue("an invalid accept hash must fail the WebSocket session", result.isFailure)
                assertNull("a malformed 101 must not publish its TCP peer", connected.get())
            } finally {
                client.close()
                server.join(2_000)
            }
        }
    }

    @Test fun refusedRouteFallsBackToTheNextAddress() {
        assumeTrue("no loopback alias in this environment", loopbackAliasAvailable())
        // The refusing half is OURS for the whole test - bound, never listened on - so the first
        // route cannot quietly become live under us, and its RST is immediate rather than probed
        // for. Refusal is the fast-failing sibling of the black hole; CIO died here and the factory
        // must walk on.
        LoopbackPortPair.refusedAndLive(refusingLoopback, liveLoopback, liveBacklog = 16).use { routes ->
            WsAcceptor(liveLoopback, prebound = routes.live).use { live ->
                val client = HaWebSocketClients.client(
                    resolver = { listOf(refusingLoopback, liveLoopback) },
                )
                try {
                    runBlocking {
                        val session = withTimeout(15_000) {
                            client.webSocketSession("ws://ha.test:${routes.port}/api/websocket")
                        }
                        session.close()
                    }
                } finally {
                    client.close()
                }
                assertEquals("exactly one upgrade reached the live route", 1, live.upgrades.get())
            }
        }
    }

    @Test fun blackHoledRouteStillReachesTheLiveSiblingWithinTheCallerDeadline() {
        // One hostname, a black-holed address ahead of a working one - the field condition itself.
        // The dead half is unrouted documentation space, so its SYNs vanish without any queue to
        // saturate, and only address iteration can reach the sibling.
        WsAcceptor(InetAddress.getByName("127.0.0.1")).use { live ->
            val elapsedStart = System.nanoTime()
            val client = HaWebSocketClients.client(
                routeConnectTimeoutMs = ROUTE_TIMEOUT_MS,
                resolver = { listOf(blackHoledAddress, InetAddress.getByName("127.0.0.1")) },
            )
            try {
                runBlocking {
                    val session = withTimeout(ROUTE_TIMEOUT_MS + 10_000) {
                        client.webSocketSession("ws://ha.test:${live.port}/api/websocket")
                    }
                    session.close()
                }
            } finally {
                client.close()
            }
            val elapsedMs = (System.nanoTime() - elapsedStart) / 1_000_000
            // The bound separates two HYPOTHESES rather than measuring a wall-clock budget: racing
            // the families (fast fallback) connects in ~250 ms, while walking them sequentially
            // cannot beat one dead-route timeout. RACED_MAX_MS sits far above the former and
            // strictly below the latter, so host load has to stretch the connect 40x before it can
            // reach a verdict the mechanism does not justify.
            assertTrue(
                "connected in ${elapsedMs}ms; sequential iteration could not beat ${ROUTE_TIMEOUT_MS}ms",
                elapsedMs < RACED_MAX_MS,
            )
            assertEquals(1, live.upgrades.get())
        }
    }

    @Test fun aDeadOnlyRouteFailsWithinTheConfiguredConnectTimeout() {
        // A single black-holed route is a one-address walk: only the configured per-route connect
        // timeout bounds it. An unbounded engine connect ("connect_timeout=unknown ms" in the field
        // failure this lane fixes) is what this pins against returning.
        val client = HaWebSocketClients.client(
            routeConnectTimeoutMs = 100,
            resolver = { listOf(blackHoledAddress) },
        )
        val start = System.nanoTime()
        try {
            runBlocking {
                try {
                    withTimeout(15_000) {
                        client.webSocketSession("ws://ha.test:4711/api/websocket")
                    }
                    fail("a black-holed only route must not connect")
                } catch (expected: Exception) {
                    // any failure shape is fine; the bound under test is WHEN it fails
                }
            }
        } finally {
            client.close()
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // Strictly below the engine default: a dropped configuration cannot land here, because
        // OkHttp would still be waiting. The 1s configured value leaves most of that span as load
        // headroom rather than as a budget the box has to meet.
        assertTrue(
            "failed in ${elapsedMs}ms - the 100ms configured timeout applied, not the " +
                "${ENGINE_DEFAULT_CONNECT_MS}ms engine default",
            elapsedMs < ENGINE_DEFAULT_CONNECT_MS - 1_000,
        )
    }

    @Test fun cancellationWhileAnAttemptIsOutstandingReturnsPromptly() {
        // The cancellation has to land while an attempt is genuinely outstanding, and that must be
        // OBSERVED rather than inferred. A latch in the resolver did not do it: the resolver runs
        // before the dial, so cancelling on it could cancel with nothing yet in flight and "prompt"
        // would be trivially true - a pass for a reason the test never meant to check.
        //
        // So the far end is a listener this test owns which accepts and then says nothing. The latch
        // is counted down inside accept(), so it fires only once a real connection exists and the
        // client is waiting on a server that will never answer. That is an attempt outstanding by
        // observation, and it is strictly more than the previous version claimed.
        val accepted = CountDownLatch(1)
        val held = mutableListOf<Socket>()
        ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4) }.use { stalling ->
            val acceptor = Thread {
                runCatching {
                    while (!stalling.isClosed) {
                        val socket = stalling.accept()
                        synchronized(held) { held.add(socket) }
                        accepted.countDown()
                    }
                }
            }.apply { isDaemon = true; start() }
            val client = HaWebSocketClients.client(
                routeConnectTimeoutMs = 30_000,
                resolver = { listOf(InetAddress.getByName("127.0.0.1")) },
            )
            try {
                runBlocking {
                    val attempt = launch(Dispatchers.IO) {
                        runCatching {
                            client.webSocketSession("ws://ha.test:${stalling.localPort}/api/websocket")
                        }
                    }
                    assertTrue(
                        "no connection ever reached the far end, so cancellation was never exercised",
                        accepted.await(15, TimeUnit.SECONDS),
                    )
                    val cancelStart = System.nanoTime()
                    // Fail-closed, and still a real discriminator: "prompt" has to mean released in
                    // a small fraction of the 30 s timeout, not merely before it. The join timeout
                    // sits above the assertion so the assertion owns the verdict.
                    withTimeout(15_000) { attempt.cancelAndJoin() }
                    val cancelMs = (System.nanoTime() - cancelStart) / 1_000_000
                    assertTrue(
                        "cancellation released the caller in ${cancelMs}ms while an attempt was outstanding",
                        cancelMs < PROMPT_CANCEL_MAX_MS,
                    )
                }
            } finally {
                client.close()
                synchronized(held) { held.forEach { runCatching { it.close() } } }
                stalling.close()
                acceptor.join(2_000)
            }
        }
    }

    // ---- payload bounds ------------------------------------------------------------------------

    @Test fun multiMegabytePayloadSurvivesTheEngineSwap() {
        // Large entity catalogs flow through these sockets as multi-megabyte frames. The engine
        // swap must not shrink what deployed panels currently rely on.
        val payload = 5 * 1024 * 1024
        WsAcceptor(InetAddress.getByName("127.0.0.1"), payloadBytes = payload).use { server ->
            val client = HaWebSocketClients.client()
            try {
                runBlocking {
                    val session = withTimeout(30_000) {
                        HaWebSocketClients.open(client, "ws://127.0.0.1:${server.port}/api/websocket", 16L * 1024 * 1024)
                    }
                    val frame = withTimeout(30_000) { session.incoming.receive() }
                    val text = (frame as Frame.Text).readText()
                    assertEquals(payload, text.length)
                    session.close()
                }
            } finally {
                client.close()
            }
        }
    }

    @Test fun anOversizedInboundFrameFailsTheSessionInsteadOfDelivering() {
        // The pre-delivery contract the CIO engine used to enforce natively: a frame above the
        // configured bound must never reach the caller as a successful message. The wrapper closes
        // the session (1009 TOO_BIG) and receivers fail with FrameTooBigException.
        val bound = 256 * 1024
        WsAcceptor(InetAddress.getByName("127.0.0.1"), payloadBytes = bound * 4).use { server ->
            val client = HaWebSocketClients.client()
            try {
                runBlocking {
                    val session = withTimeout(30_000) {
                        HaWebSocketClients.open(client, "ws://127.0.0.1:${server.port}/api/websocket", bound.toLong())
                    }
                    try {
                        withTimeout(30_000) { session.incoming.receive() }
                        fail("a ${bound * 4} byte frame was delivered despite the $bound byte bound")
                    } catch (expected: io.ktor.websocket.FrameTooBigException) {
                        // the named contract: oversized fails, never silent delivery
                    }
                    session.close()
                }
            } finally {
                client.close()
            }
        }
    }

    @Test fun aCustomFrameBoundIsStructurallyUnavailableOnThisEngine() {
        // Documents WHY the factory exposes no maxFrameSize: Ktor's OkHttp engine throws on any
        // custom value at session start, so the previous per-site 2-32 MB bounds cannot ride
        // through this engine. If a Ktor upgrade ever makes this construction succeed, the bound
        // can be reintroduced - this pin is the reminder.
        WsAcceptor(InetAddress.getByName("127.0.0.1")).use { server ->
            val client = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
                install(io.ktor.client.plugins.websocket.WebSockets) { maxFrameSize = 1L shl 20 }
            }
            try {
                runBlocking {
                    try {
                        withTimeout(15_000) {
                            client.webSocketSession("ws://127.0.0.1:${server.port}/api/websocket")
                        }
                        fail("expected the engine to refuse a custom maxFrameSize")
                    } catch (expected: io.ktor.client.plugins.websocket.WebSocketException) {
                        assertTrue(
                            "engine names the unsupported switch: ${expected.message}",
                            expected.message.orEmpty().contains("not supported"),
                        )
                    }
                }
            } finally {
                client.close()
            }
        }
    }
}
