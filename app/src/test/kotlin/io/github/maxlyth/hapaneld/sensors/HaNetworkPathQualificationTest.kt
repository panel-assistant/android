package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.HaAuthOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Representative degradation of the REAL transport on the JVM.
 *
 * The production `KtorHaExactEntityStreamTransport` (Ktor over OkHttp) connects to a raw RFC 6455
 * server in this process that speaks the Home Assistant WebSocket handshake and answers `ping`
 * frames under an injected policy: prompt, delayed, dropped, or refused while "restarting". The
 * stream owner and the network-path monitor are the production classes; only the socket peer and
 * the clocks' cadence are the test's. Real time, so every scenario allows generous margins and the
 * class is opt-in: set `HA_PANELD_NETWORK_QUALIFICATION=1` in the environment to run it. The
 * deterministic traces live in `HaNetworkPathTest`; this proves the wiring end to end.
 */
class HaNetworkPathQualificationTest {
    private lateinit var server: FakeHaServer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pokes = ConcurrentLinkedQueue<Triple<Boolean, Boolean, HaNetworkPathSeverity>>()
    private lateinit var monitor: HaNetworkPathMonitor
    private lateinit var owner: HaExactEntityStreamOwner

    @Before fun start() {
        assumeTrue("set HA_PANELD_NETWORK_QUALIFICATION=1 to run", System.getenv("HA_PANELD_NETWORK_QUALIFICATION") == "1")
        server = FakeHaServer().also { it.start() }
        val clock: () -> Long = { System.nanoTime() / 1_000_000L }
        monitor = HaNetworkPathMonitor(
            nowMs = clock,
            onChanged = { pokes += HaNetworkPathRuntime.snapshot()!!.reportableKey },
            // A three-second window so recovery can be observed in real time.
            path = HaNetworkPath(windowMs = WINDOW_MS),
        )
        HaNetworkPathRuntime.install(monitor)
        val base = "http://127.0.0.1:${server.port}"
        owner = HaExactEntityStreamOwner(
            scope = scope,
            auth = HaApiSessionProvider { HaApiSession(base, "token", owner = HaAuthOwner(base, "refresh", "client", "")) },
            transport = KtorHaExactEntityStreamTransport(rest = NoRest, monotonicMillis = clock),
            probeIntervalMs = PROBE_MS,
            pongTimeoutMs = PONG_TIMEOUT_MS,
            reconnectBaseMs = 50L,
            reconnectMaxMs = 100L,
            subscribeTimeoutMs = 5_000L,
            monotonicMillis = clock,
        )
        owner.bindNetworkPath(monitor)
    }

    @After fun stop() {
        if (!::server.isInitialized) return
        owner.close()
        HaNetworkPathRuntime.uninstall(monitor)
        scope.cancel()
        server.stop()
    }

    private fun await(what: String, deadlineMs: Long, until: (HaNetworkPath.Snapshot) -> Boolean): HaNetworkPath.Snapshot {
        val end = System.nanoTime() + deadlineMs * 1_000_000L
        var last: HaNetworkPath.Snapshot
        do {
            last = HaNetworkPathRuntime.snapshot()!!
            if (until(last)) return last
            Thread.sleep(25L)
        } while (System.nanoTime() < end)
        throw AssertionError("$what not reached within $deadlineMs ms; last=$last pongs=${server.pongsSent} pings=${server.pingsSeen}")
    }

    @Test fun healthyPathIsMeasuredHealthyWithSingleDigitRoundTrips() {
        server.pongDelayMs.set(0L)
        owner.replacePresenceRegistryWatch(true)
        val snap = await("four round trips", 4_000L) { it.roundTrips >= 4 }
        assertEquals(HaNetworkPathSeverity.HEALTHY, snap.severity)
        assertTrue("p95 ${snap.p95Ms} on loopback", snap.p95Ms in 0L..HaNetworkPath.WARN_P95_MS)
        assertEquals(setOf("device_registry_updated", "entity_registry_updated", "area_registry_updated"),
            server.subscriptions.map { it.getString("event_type") }.toSet())
        assertEquals("only the requested registry subscriptions reach Core", 3, server.subscriptions.size)
        assertEquals(0, snap.networkFailures)
        assertEquals(0, snap.serverFailures)
        assertTrue(pokes.contains(Triple(true, false, HaNetworkPathSeverity.HEALTHY)))
    }

    /**
     * The split, proved end to end on a real socket rather than on a replayed trace: a server that is
     * genuinely slow to answer — a real pong delay, over real Ktor/OkHttp, through the real frame
     * decode — raises RESPONSIVENESS and never touches the path verdict.
     *
     * This is the strongest evidence available for the rule, because every term the deterministic
     * traces cannot model (socket scheduling, decode cost, the reader thread's turn on the CPU) is
     * present here and still nothing about the path changes. Nothing is lost, so nothing may be said.
     */
    @Test fun sustainedLatencyOnTheRealSocketIsAResponsivenessVerdictAndNeverAPathOne() {
        server.pongDelayMs.set(180L)
        owner.replacePresenceRegistryWatch(true)
        val warned = await("responsiveness warning", 6_000L) { it.responsiveness == HaNetworkPathSeverity.WARNING }
        assertTrue("p95 ${warned.p95Ms}", warned.p95Ms >= 180L)
        assertEquals(0, warned.networkFailures)
        assertEquals(HaNetworkPathSeverity.HEALTHY, warned.severity)
        server.pongDelayMs.set(1_200L)
        val severe = await("responsiveness severe", 12_000L) { it.responsiveness == HaNetworkPathSeverity.SEVERE }
        assertTrue("p95 ${severe.p95Ms}", severe.p95Ms > HaNetworkPath.SEVERE_P95_MS)
        assertEquals("multi-second replies are round trips, not loss", 0, severe.networkFailures)
        assertEquals("a lossless path stays healthy however slow the server is", HaNetworkPathSeverity.HEALTHY, severe.severity)
        assertFalse("the prominent surfaces must stay silent", severe.degraded)
        // The poke key is (measuring, settling, PATH verdict), so a purely slow server moves it
        // exactly once — when measurement begins — and the native chip is never woken after that.
        assertEquals(
            listOf(
                Triple(false, false, HaNetworkPathSeverity.HEALTHY),
                Triple(true, false, HaNetworkPathSeverity.HEALTHY),
            ),
            pokes.toList(),
        )
    }

    @Test fun droppedPongsAreLossAndTheVerdictAgesOutAfterRecovery() {
        server.pongDelayMs.set(0L)
        owner.replacePresenceRegistryWatch(true)
        await("baseline", 4_000L) { it.roundTrips >= 2 }
        server.pongDelayMs.set(null)
        val severe = await("severe by consecutive misses", 12_000L) { it.severity == HaNetworkPathSeverity.SEVERE }
        assertTrue(severe.consecutiveFailures >= 2)
        assertTrue(severe.networkFailures >= 2)
        assertEquals(0, severe.serverFailures)
        // The stream reconnected after each timeout on the real transport.
        assertTrue("reconnects=${server.connections}", server.connections >= 2)
        server.pongDelayMs.set(0L)
        val recovered = await("healthy again", WINDOW_MS + 8_000L) { it.severity == HaNetworkPathSeverity.HEALTHY && it.networkFailures == 0 }
        assertEquals(0, recovered.consecutiveFailures)
        assertTrue(recovered.roundTrips >= 1)
    }

    @Test fun anOverloadedServerThatStreamsButNeverPongsIsNeverLossAndKeepsTheSocket() {
        server.pongDelayMs.set(0L)
        owner.replacePresenceRegistryWatch(true)
        await("baseline", 4_000L) { it.roundTrips >= 2 }
        // Home Assistant keeps pushing event frames every 50 ms but stops answering pings.
        server.eventEveryMs.set(50L)
        server.pongDelayMs.set(null)
        val busy = await("server-attributed stalls", 3L * PONG_TIMEOUT_MS + 4_000L) { it.serverFailures >= 2 }
        assertEquals(0, busy.networkFailures)
        assertEquals(0, busy.consecutiveFailures)
        assertEquals(HaNetworkPathSeverity.HEALTHY, busy.severity)
        assertEquals("the shared socket was never torn down", 1, server.connections)
        assertTrue("pings=${server.pingsSeen}", server.pingsSeen >= 4)
        // Pongs return: round trips resume on the same connection.
        server.eventEveryMs.set(null)
        server.pongDelayMs.set(0L)
        val back = await("round trips again", 6_000L) { it.lastRoundTripAgeMs in 0L..1_000L }
        assertEquals(1, server.connections)
        assertEquals(HaNetworkPathSeverity.HEALTHY, back.severity)
    }

    @Test fun aServerRestartOnAHealthyPathIsNeverLoss() {
        server.pongDelayMs.set(0L)
        owner.replacePresenceRegistryWatch(true)
        await("baseline", 4_000L) { it.roundTrips >= 2 }
        // Home Assistant goes away: every socket closed, the port refuses while it "restarts".
        server.restart(downForMs = 1_500L)
        val during = await("server failures", 6_000L) { it.serverFailures >= 2 }
        assertEquals(0, during.networkFailures)
        assertEquals(0, during.consecutiveFailures)
        assertEquals(HaNetworkPathSeverity.HEALTHY, during.severity)
        val back = await("live again", 8_000L) { it.roundTrips >= 1 && it.lastRoundTripAgeMs in 0L..1_000L && server.connections >= 2 }
        assertEquals(HaNetworkPathSeverity.HEALTHY, back.severity)
        assertEquals(0, back.networkFailures)
    }

    @Test fun anUnreachableHostIsThePathsFailureNotTheServers() {
        // A black-holed address (RFC 5737 TEST-NET, unrouted): the connect attempt times out.
        val black = "http://192.0.2.1:8123"
        val clock: () -> Long = { System.nanoTime() / 1_000_000L }
        val second = HaExactEntityStreamOwner(
            scope = scope,
            auth = HaApiSessionProvider { HaApiSession(black, "token", owner = HaAuthOwner(black, "r", "c", "")) },
            transport = KtorHaExactEntityStreamTransport(rest = NoRest, monotonicMillis = clock),
            probeIntervalMs = PROBE_MS,
            pongTimeoutMs = PONG_TIMEOUT_MS,
            reconnectBaseMs = 50L,
            reconnectMaxMs = 100L,
            subscribeTimeoutMs = 30_000L,
            monotonicMillis = clock,
        )
        val attributed = mutableListOf<HaPathFailureKind>()
        try {
            second.bindNetworkPath(object : HaNetworkPathObserver {
                override fun onSocketState(state: HaSocketState) = Unit
                override fun onRoundTrip(rttMs: Long) = Unit
                override fun onProbeTimeout() = Unit
                override fun onConnectionFailure(kind: HaPathFailureKind) {
                    synchronized(attributed) { attributed += kind }
                }
            })
            second.replacePresenceRegistryWatch(true)
            // The per-route connect timeout is 5 s; two attempts prove the classification is stable.
            val deadline = System.nanoTime() + 25_000L * 1_000_000L
            while (System.nanoTime() < deadline && synchronized(attributed) { attributed.size } < 2) Thread.sleep(50L)
            val seen = synchronized(attributed) { attributed.toList() }
            assertTrue("attributions=$seen", seen.size >= 2)
            assertTrue("a black-holed host is the path's fault", seen.all { it == HaPathFailureKind.NETWORK })
        } finally {
            second.close()
        }
        // No verdict is claimed here on purpose: this owner never reached an authenticated socket,
        // so it owns no measurement (see `measurementIsOwnedByAnAuthenticatedSocket...`). The
        // live-socket-then-path-dies verdict is proven by `droppedPongsAreLossAndTheVerdictAgesOut...`.
    }

    private object NoRest : HaAmbientTransport {
        override suspend fun state(baseUrl: String, accessToken: String, entityId: String): JSONObject? = null
        override suspend fun states(baseUrl: String, accessToken: String): JSONArray = JSONArray()
        override suspend fun config(baseUrl: String, accessToken: String): JSONObject = JSONObject()
    }

    /**
     * A minimal RFC 6455 text-frame server speaking the Home Assistant handshake. Client frames are
     * masked and unmasked here; server frames are sent unmasked. Protocol pings are answered.
     */
    private class FakeHaServer {
        val subscriptions = CopyOnWriteArrayList<JSONObject>()
        val pongDelayMs = AtomicReference<Long?>(0L)
        /** When set, every live connection receives an entity event frame this often (an overloaded server that still streams). */
        val eventEveryMs = AtomicReference<Long?>(null)
        @Volatile var pingsSeen = 0
        @Volatile var pongsSent = 0
        @Volatile var connections = 0
        private val listener = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val port: Int = listener.localPort
        private val pool = Executors.newCachedThreadPool()
        private val sockets = CopyOnWriteArrayList<Socket>()
        @Volatile private var accepting = true
        @Volatile private var downUntilNanos = 0L

        fun start() {
            pool.execute {
                while (!listener.isClosed) {
                    val socket = try { listener.accept() } catch (_: IOException) { return@execute }
                    if (System.nanoTime() < downUntilNanos || !accepting) {
                        runCatching { socket.close() }
                        continue
                    }
                    connections++
                    sockets += socket
                    pool.execute { runCatching { serve(socket) }; sockets.remove(socket); runCatching { socket.close() } }
                }
            }
        }

        /** Close every live socket and refuse new ones for [downForMs]. */
        fun restart(downForMs: Long) {
            downUntilNanos = System.nanoTime() + downForMs * 1_000_000L
            sockets.forEach { runCatching { it.close() } }
        }

        fun stop() {
            accepting = false
            runCatching { listener.close() }
            sockets.forEach { runCatching { it.close() } }
            pool.shutdownNow()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }

        private fun serve(socket: Socket) {
            socket.tcpNoDelay = true
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = socket.getOutputStream()
            handshake(input, output)
            val send = { text: String -> synchronized(output) { writeText(output, text) } }
            send(JSONObject().put("type", "auth_required").put("ha_version", "2026.8.0").toString())
            // Event streamer: an id outside the subscription ranges routes as an entity event with
            // nothing in it, which the consumer sees as traffic and nothing else.
            pool.execute {
                while (!socket.isClosed) {
                    val every = eventEveryMs.get()
                    if (every == null) { Thread.sleep(25L); continue }
                    Thread.sleep(every)
                    if (socket.isClosed) return@execute
                    runCatching {
                        send(JSONObject().put("id", 1_000).put("type", "event").put("event", JSONObject().put("a", JSONObject())).toString())
                    }.onFailure { return@execute }
                }
            }
            while (!socket.isClosed) {
                val (opcode, payload) = readFrame(input) ?: return
                when (opcode) {
                    0x8 -> return
                    0x9 -> synchronized(output) { writeFrame(output, 0xA, payload) }
                    0x1 -> {
                        val json = JSONObject(String(payload, Charsets.UTF_8))
                        when (json.optString("type")) {
                            "auth" -> send(JSONObject().put("type", "auth_ok").put("ha_version", "2026.8.0").toString())
                            "subscribe_events", "subscribe_entities" -> {
                                subscriptions += json
                                send(JSONObject().put("id", json.getInt("id")).put("type", "result")
                                    .put("success", true).put("result", JSONObject.NULL).toString())
                            }
                            "ping" -> {
                                pingsSeen++
                                val delay = pongDelayMs.get() ?: continue
                                val id = json.getInt("id")
                                pool.execute {
                                    if (delay > 0L) Thread.sleep(delay)
                                    if (!socket.isClosed) runCatching {
                                        send(JSONObject().put("id", id).put("type", "pong").toString())
                                        pongsSent++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun handshake(input: DataInputStream, output: OutputStream) {
            val request = StringBuilder()
            while (!request.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) throw IOException("client closed during handshake")
                request.append(b.toChar())
            }
            val key = Regex("Sec-WebSocket-Key: *(\\S+)", RegexOption.IGNORE_CASE).find(request)?.groupValues?.get(1)
                ?: throw IOException("no websocket key")
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            )
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
            )
            output.flush()
        }

        private fun readFrame(input: InputStream): Pair<Int, ByteArray>? {
            val b0 = input.read().takeIf { it >= 0 } ?: return null
            val b1 = input.read().takeIf { it >= 0 } ?: return null
            val opcode = b0 and 0x0F
            val masked = b1 and 0x80 != 0
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) length = (input.read() shl 8 or input.read()).toLong()
            else if (length == 127L) { length = 0L; repeat(8) { length = (length shl 8) or input.read().toLong() } }
            val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
            val payload = ByteArray(length.toInt()).also { readFully(input, it) }
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            return opcode to payload
        }

        private fun readFully(input: InputStream, into: ByteArray) {
            var read = 0
            while (read < into.size) {
                val n = input.read(into, read, into.size - read)
                if (n < 0) throw IOException("client closed mid-frame")
                read += n
            }
        }

        private fun writeText(output: OutputStream, text: String) = writeFrame(output, 0x1, text.toByteArray(Charsets.UTF_8))

        private fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
            output.write(0x80 or opcode)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size < 65_536 -> { output.write(126); output.write(payload.size shr 8); output.write(payload.size and 0xFF) }
                else -> { output.write(127); for (shift in 56 downTo 0 step 8) output.write((payload.size.toLong() shr shift and 0xFF).toInt()) }
            }
            output.write(payload)
            output.flush()
        }
    }

    private companion object {
        const val PROBE_MS = 200L
        const val PONG_TIMEOUT_MS = 2_000L
        const val WINDOW_MS = 3_000L
    }
}
