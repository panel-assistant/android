package io.github.maxlyth.hapaneld.panelassistant

import io.github.maxlyth.hapaneld.HaAuthOwner
import io.github.maxlyth.hapaneld.mqtt.MqttAddressFamilyPolicy
import io.github.maxlyth.hapaneld.sensors.HaApiSession
import io.github.maxlyth.hapaneld.sensors.HaApiSessionProvider
import io.github.maxlyth.hapaneld.sensors.HaAuthenticationException
import io.ktor.websocket.FrameTooBigException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the production connector over real loopback sockets against a minimal Home Assistant
 * WebSocket responder, so the factory path, authentication, frame bound and restart recovery are
 * exercised end to end rather than through a fake connection.
 */
class KtorPanelAssistantTransportConnectorTest {

    @Test fun `the owner completes hello over a real socket and recovers after Home Assistant restarts`() {
        val server = FakePanelAssistantServer().apply { start() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val policyReads = AtomicInteger()
        try {
            val owner = PanelAssistantTransportOwner(
                scope = scope,
                auth = HaApiSessionProvider { HaApiSession(server.baseUrl, "token", owner = OWNER) },
                connector = KtorPanelAssistantTransportConnector(
                    socketFamilyPolicy = { policyReads.incrementAndGet(); MqttAddressFamilyPolicy.FORCE_IPV4 },
                ),
                backoffBaseMs = 50L,
                backoffMaxMs = 200L,
                observeForHello = { true },
                log = {},
            )
            owner.replaceDemand(PanelAssistantTransportDemand(OWNER, IDENTITY))
            awaitPhase(owner, PanelAssistantTransportPhase.CONNECTED)
            assertEquals("0.3.0", owner.status.session?.integrationVersion)
            val hello = server.hellos.single()
            assertEquals(IDENTITY.did, hello.getString("did"))
            assertEquals("token", server.tokens.single())

            server.restart(downForMs = 600L)
            awaitCondition("reconnected after restart") {
                server.hellos.size == 2 && owner.status.phase == PanelAssistantTransportPhase.CONNECTED
            }

            // A clean WebSocket close straight after auth_ok lands while hello is being sent, which
            // Ktor reports as a CancellationException. The owner must retry rather than end.
            server.closeAfterAuth.set(2)
            server.restart(downForMs = 0L)
            awaitCondition("reconnected after clean closes") {
                server.closeAfterAuth.get() == 0 && server.hellos.size == 3 &&
                    owner.status.phase == PanelAssistantTransportPhase.CONNECTED
            }
            assertTrue("refused dials while down: ${policyReads.get()}", policyReads.get() >= 3)
            owner.close()
        } finally {
            scope.cancel()
            server.stop()
        }
    }

    @Test fun `auth_invalid surfaces as an authentication failure`() {
        val server = FakePanelAssistantServer(rejectToken = true).apply { start() }
        try {
            runBlocking {
                val failure = try {
                    KtorPanelAssistantTransportConnector { MqttAddressFamilyPolicy.AUTOMATIC }
                        .connect(server.baseUrl, "token")
                    null
                } catch (thrown: Exception) {
                    thrown
                }
                // The owner refreshes once and then slows only for this type; a transport failure
                // would retry the rejected token on every backoff step.
                assertTrue("expected an authentication failure, got $failure", failure is HaAuthenticationException)
            }
        } finally {
            server.stop()
        }
    }

    @Test fun `a receive timeout returns null, a later frame still arrives and an oversized frame fails the connection`() {
        val server = FakePanelAssistantServer().apply { start() }
        try {
            runBlocking {
                val connection = KtorPanelAssistantTransportConnector { MqttAddressFamilyPolicy.AUTOMATIC }
                    .connect(server.baseUrl, "token")
                try {
                    assertNull(connection.receive(100L))
                    server.sendToAll(JSONObject().put("id", 5).put("type", "pong").toString())
                    assertEquals(5, JSONObject(checkNotNull(connection.receive(5_000L))).getInt("id"))

                    server.sendToAll("x".repeat(2 * 1024 * 1024 + 1))
                    try {
                        connection.receive(5_000L)
                        fail("a frame above the 2 MiB bound was delivered")
                    } catch (expected: FrameTooBigException) {
                    }
                } finally {
                    connection.close()
                }
            }
        } finally {
            server.stop()
        }
    }

    private fun awaitPhase(owner: PanelAssistantTransportOwner, phase: PanelAssistantTransportPhase) =
        awaitCondition("phase $phase, last ${owner.status}") { owner.status.phase == phase }

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for $description")
            Thread.sleep(20L)
        }
    }

    /** Minimal RFC 6455 responder speaking Home Assistant's auth phase and `panel_assistant/hello`. */
    private class FakePanelAssistantServer(private val rejectToken: Boolean = false) {
        val hellos = CopyOnWriteArrayList<JSONObject>()
        val tokens = CopyOnWriteArrayList<String>()
        /** The next this many connections are closed with a close frame immediately after auth_ok. */
        val closeAfterAuth = AtomicInteger()
        private val listener = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        val baseUrl = "http://127.0.0.1:${listener.localPort}"
        private val pool = Executors.newCachedThreadPool()
        private val sockets = CopyOnWriteArrayList<Pair<Socket, OutputStream>>()
        @Volatile private var downUntilNanos = 0L

        fun start() {
            pool.execute {
                while (!listener.isClosed) {
                    val socket = try { listener.accept() } catch (_: IOException) { return@execute }
                    if (System.nanoTime() < downUntilNanos) {
                        runCatching { socket.close() }
                        continue
                    }
                    pool.execute { runCatching { serve(socket) }; runCatching { socket.close() } }
                }
            }
        }

        fun restart(downForMs: Long) {
            downUntilNanos = System.nanoTime() + downForMs * 1_000_000L
            sockets.forEach { (socket, _) -> runCatching { socket.close() } }
            sockets.clear()
        }

        fun sendToAll(text: String) {
            sockets.forEach { (_, output) -> synchronized(output) { writeFrame(output, 0x1, text.toByteArray()) } }
        }

        fun stop() {
            runCatching { listener.close() }
            sockets.forEach { (socket, _) -> runCatching { socket.close() } }
            pool.shutdownNow()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }

        private fun serve(socket: Socket) {
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = socket.getOutputStream()
            handshake(input, output)
            sockets += socket to output
            val send = { text: String -> synchronized(output) { writeFrame(output, 0x1, text.toByteArray()) } }
            send(JSONObject().put("type", "auth_required").toString())
            while (!socket.isClosed) {
                val (opcode, payload) = readFrame(input) ?: return
                when (opcode) {
                    0x8 -> return
                    0x9 -> synchronized(output) { writeFrame(output, 0xA, payload) }
                    0x1 -> {
                        val json = JSONObject(String(payload, Charsets.UTF_8))
                        when (json.optString("type")) {
                            "auth" -> {
                                tokens += json.getString("access_token")
                                send(JSONObject().put("type", if (rejectToken) "auth_invalid" else "auth_ok").toString())
                                if (!rejectToken && closeAfterAuth.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                                    synchronized(output) { writeFrame(output, 0x8, byteArrayOf(0x03, 0xE8.toByte())) }
                                    return
                                }
                            }
                            "panel_assistant/hello" -> {
                                hellos += json
                                send(
                                    JSONObject().put("id", json.getLong("id")).put("type", "result").put("success", true)
                                        .put(
                                            "result",
                                            JSONObject().put("protocol", 3).put("session", "s").put("authority", "mqtt")
                                                .put("capabilities", JSONArray())
                                                .put("integration", JSONObject().put("version", "0.3.0")),
                                        ).toString(),
                                )
                            }
                            "ping" -> send(JSONObject().put("id", json.getLong("id")).put("type", "pong").toString())
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
        val OWNER = HaAuthOwner(url = "http://127.0.0.1", refreshToken = "refresh", clientId = "", staticAccessToken = "")
        val IDENTITY = PanelAssistantHelloIdentity(did = "b".repeat(64), appVersion = "0.9.7-rc5", appVersionCode = 774)
    }
}
