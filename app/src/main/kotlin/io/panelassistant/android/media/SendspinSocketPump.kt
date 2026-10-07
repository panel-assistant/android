package io.panelassistant.android.media

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.panelassistant.android.util.HaWebSocketClients
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** The native side of the Sendspin socket bridge (`app/src/main/cpp/sendspin/transport`). */
internal interface SendspinWire {
    /** The next request: `[type, transport id (4 bytes, big-endian), payload]`, or null after [timeoutMs]. */
    fun take(timeoutMs: Int): ByteArray?

    /** The socket [transportId] opened, received a message or closed ([type] as in [SendspinSocketPump]). */
    fun deliver(transportId: Int, type: Int, data: ByteArray?, receiveUs: Long)

    /** The library's clock (CLOCK_MONOTONIC microseconds), which stamps each received message. */
    fun nowUs(): Long
}

/** One WebSocket as the pump drives it; a message is (is text, bytes). */
internal interface SendspinSocket {
    suspend fun send(text: Boolean, data: ByteArray)

    /** The next complete message, or null once the socket has closed. */
    suspend fun receive(): Pair<Boolean, ByteArray>?

    suspend fun close()
}

internal fun interface SendspinSocketOpener {
    suspend fun open(url: String): SendspinSocket
}

/**
 * Carries the Sendspin client's WebSocket over the app's own WebSocket client, so `wss://` uses the
 * panel's TLS trust exactly as the Home Assistant session does. It takes the library's requests (open a
 * URL, send a frame, close) and hands back the socket's open, every message and its close. Each socket has
 * the library's transport id; anything for a socket that is no longer current is dropped. Runs until
 * cancelled, and closes its socket then. The library is the only sender: requests are taken and sent in
 * order on one coroutine.
 */
internal class SendspinSocketPump(
    private val wire: SendspinWire,
    private val opener: SendspinSocketOpener,
    private val takeTimeoutMs: Int = TAKE_TIMEOUT_MS,
) {
    private class Current(val id: Int, val job: Job) {
        @Volatile var socket: SendspinSocket? = null
    }

    suspend fun run() = coroutineScope {
        var current: Current? = null
        try {
            while (true) {
                val request = runInterruptible(Dispatchers.IO) { wire.take(takeTimeoutMs) } ?: continue
                if (request.size < HEADER_BYTES) continue
                val type = request[0].toInt()
                val id = ((request[1].toInt() and 0xFF) shl 24) or ((request[2].toInt() and 0xFF) shl 16) or
                    ((request[3].toInt() and 0xFF) shl 8) or (request[4].toInt() and 0xFF)
                val payload = request.copyOfRange(HEADER_BYTES, request.size)
                when (type) {
                    OPEN -> {
                        current?.let { replaced -> end(replaced) }
                        val url = String(payload, Charsets.UTF_8)
                        lateinit var opened: Current
                        val job = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { serve(opened, url) }
                        opened = Current(id, job)
                        current = opened
                        job.start()
                    }
                    TEXT, BINARY -> {
                        val socket = current?.takeIf { it.id == id }?.socket ?: continue
                        try {
                            socket.send(type == TEXT, payload)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // The reader sees the broken socket and reports its close.
                        }
                    }
                    CLOSE -> current?.takeIf { it.id == id }?.let { closing ->
                        end(closing)
                        current = null
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { current?.let { end(it) } }
        }
    }

    private suspend fun serve(slot: Current, url: String) {
        val socket = try {
            opener.open(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            wire.deliver(slot.id, CLOSE, null, 0L)
            return
        }
        slot.socket = socket
        wire.deliver(slot.id, OPEN, null, 0L)
        try {
            while (true) {
                val (text, data) = socket.receive() ?: break
                wire.deliver(slot.id, if (text) TEXT else BINARY, data, wire.nowUs())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed socket closes like a closed one.
        } finally {
            withContext(NonCancellable) {
                runCatching { socket.close() }
                wire.deliver(slot.id, CLOSE, null, 0L)
            }
        }
    }

    /** Close [slot]'s socket; a socket still connecting is abandoned and reported closed. */
    private suspend fun end(slot: Current) {
        val socket = slot.socket
        slot.job.cancel()
        if (socket == null) {
            slot.job.join()
            if (slot.socket == null) wire.deliver(slot.id, CLOSE, null, 0L)
        } else {
            runCatching { socket.close() }
            slot.job.join()
        }
    }

    companion object {
        const val OPEN = 1
        const val TEXT = 2
        const val BINARY = 3
        const val CLOSE = 4
        private const val HEADER_BYTES = 5
        private const val TAKE_TIMEOUT_MS = 100
    }
}

/** Opens Sendspin sockets through the panel's shared Home Assistant WebSocket client and TLS trust. */
internal class KtorSendspinSocketOpener : SendspinSocketOpener {
    override suspend fun open(url: String): SendspinSocket {
        val client = HaWebSocketClients.client()
        val session = try {
            HaWebSocketClients.open(client, url, MAX_INBOUND_FRAME_BYTES)
        } catch (failure: Throwable) {
            client.close()
            throw failure
        }
        return KtorSendspinSocket(client, session)
    }

    private class KtorSendspinSocket(
        private val client: HttpClient,
        private val session: DefaultClientWebSocketSession,
    ) : SendspinSocket {
        override suspend fun send(text: Boolean, data: ByteArray) {
            session.send(if (text) Frame.Text(String(data, Charsets.UTF_8)) else Frame.Binary(true, data))
        }

        override suspend fun receive(): Pair<Boolean, ByteArray>? {
            while (true) {
                val frame = try {
                    session.incoming.receive()
                } catch (_: ClosedReceiveChannelException) {
                    return null
                }
                when (frame) {
                    is Frame.Text -> return true to frame.readBytes()
                    is Frame.Binary -> return false to frame.readBytes()
                    is Frame.Close -> return null
                    else -> Unit
                }
            }
        }

        override suspend fun close() {
            try {
                session.close()
            } finally {
                client.close()
            }
        }
    }

    private companion object {
        /** Sendspin audio chunks are small; this bounds a misbehaving server. */
        const val MAX_INBOUND_FRAME_BYTES = 1024L * 1024L
    }
}
