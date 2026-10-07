package io.panelassistant.android.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The Sendspin socket bridge on the app side: the library's requests in, the socket's events back. */
class SendspinSocketPumpTest {
    private class Wire : SendspinWire {
        val requests = LinkedBlockingQueue<ByteArray>()
        val delivered = mutableListOf<String>()

        fun request(type: Int, id: Int, payload: ByteArray = ByteArray(0)) {
            requests.add(byteArrayOf(type.toByte(), (id ushr 24).toByte(), (id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte()) + payload)
        }

        override fun take(timeoutMs: Int): ByteArray? = requests.poll(5, TimeUnit.MILLISECONDS)

        override fun deliver(transportId: Int, type: Int, data: ByteArray?, receiveUs: Long) {
            synchronized(delivered) { delivered += "$transportId:$type:${data?.let { String(it) } ?: ""}" }
        }

        override fun nowUs() = 7L
    }

    private class Socket : SendspinSocket {
        val inbound = Channel<Pair<Boolean, ByteArray>?>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        var closed = false

        override suspend fun send(text: Boolean, data: ByteArray) {
            sent += (if (text) "text:" else "binary:") + String(data)
        }

        override suspend fun receive(): Pair<Boolean, ByteArray>? = inbound.receive()

        override suspend fun close() {
            closed = true
            inbound.trySend(null)
        }
    }

    private fun Wire.awaitDelivered(count: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (synchronized(delivered) { delivered.size } < count && System.nanoTime() < deadline) Thread.sleep(2)
    }

    @Test fun framesCrossTheBridgeBothWaysForTheCurrentSocketOnly() = runBlocking {
        val wire = Wire()
        val opened = mutableListOf<String>()
        val socket = Socket()
        val pump = launch(Dispatchers.Default) {
            SendspinSocketPump(wire, { url -> opened += url; socket }, takeTimeoutMs = 5).run()
        }
        wire.request(SendspinSocketPump.OPEN, 3, "wss://ha.example/api/panel_assistant/sendspin".toByteArray())
        wire.awaitDelivered(1)
        assertEquals(listOf("wss://ha.example/api/panel_assistant/sendspin"), opened)
        assertEquals(listOf("3:1:"), wire.delivered)

        socket.inbound.send(false to "noise".toByteArray())
        socket.inbound.send(true to "hello".toByteArray())
        wire.awaitDelivered(3)
        assertEquals(listOf("3:1:", "3:3:noise", "3:2:hello"), wire.delivered)

        wire.request(SendspinSocketPump.BINARY, 3, "chunk".toByteArray())
        wire.request(SendspinSocketPump.TEXT, 2, "stale".toByteArray())
        wire.request(SendspinSocketPump.TEXT, 3, "state".toByteArray())
        wire.request(SendspinSocketPump.CLOSE, 3)
        wire.awaitDelivered(4)
        assertEquals(listOf("binary:chunk", "text:state"), socket.sent)
        assertTrue(socket.closed)
        assertEquals("3:4:", wire.delivered.last())
        pump.cancelAndJoin()
    }

    @Test fun aSocketThatCannotOpenIsReportedClosed() = runBlocking {
        val wire = Wire()
        val pump = launch(Dispatchers.Default) {
            SendspinSocketPump(wire, { throw IOException("refused") }, takeTimeoutMs = 5).run()
        }
        wire.request(SendspinSocketPump.OPEN, 9, "ws://ha.local:8123/api/panel_assistant/sendspin".toByteArray())
        wire.awaitDelivered(1)
        assertEquals(listOf("9:4:"), wire.delivered)
        pump.cancelAndJoin()
    }
}
