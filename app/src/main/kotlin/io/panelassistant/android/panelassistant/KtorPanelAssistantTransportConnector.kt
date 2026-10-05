package io.panelassistant.android.panelassistant

import io.panelassistant.android.dashboard.EntityFilterProtocol
import io.panelassistant.android.mqtt.MqttAddressFamilyPolicy
import io.panelassistant.android.sensors.HaAuthenticationException
import io.panelassistant.android.sensors.HaProtocolException
import io.panelassistant.android.util.HaWebSocketClients
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Opens the native transport's socket through the shared [HaWebSocketClients] factory, so the
 * address-family policy, per-route connect timeout and inbound frame bound are the same as every
 * other Home Assistant socket on the panel.
 */
internal class KtorPanelAssistantTransportConnector(
    /** Read at every connect, so a changed `mqtt_address_family` applies to the next attempt. */
    private val socketFamilyPolicy: () -> MqttAddressFamilyPolicy,
) : PanelAssistantTransportConnector {
    /**
     * Runs on the caller's dispatcher (the owner's IO worker). Switching context here would let a
     * cancellation that lands as the call returns discard a connection that was already opened.
     */
    override suspend fun connect(baseUrl: String, accessToken: String): PanelAssistantTransportConnection {
        val policy = socketFamilyPolicy()
        val client = HaWebSocketClients.client(
            preferIpv4 = policy.initialPreferIpv4,
            ipv4Only = policy.ipv4Only,
        )
        var socket: DefaultClientWebSocketSession? = null
        try {
            val active = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                HaWebSocketClients.open(client, EntityFilterProtocol.upstreamWebSocketUrl(baseUrl), MAX_INBOUND_FRAME_BYTES)
            } ?: throw IOException("Home Assistant WebSocket connect timed out")
            socket = active
            authenticate(active, accessToken)
            return KtorConnection(client, active)
        } catch (failure: Throwable) {
            runCatching { socket?.close() }
            client.close()
            throw failure
        }
    }

    private suspend fun authenticate(socket: DefaultClientWebSocketSession, accessToken: String) {
        val completed = withTimeoutOrNull(AUTH_TIMEOUT_MS) {
            if (readJson(socket).optString("type") != "auth_required") {
                throw HaProtocolException("Home Assistant did not request WebSocket authentication")
            }
            socket.send(Frame.Text(JSONObject().put("type", "auth").put("access_token", accessToken).toString()))
            when (readJson(socket).optString("type")) {
                "auth_ok" -> Unit
                "auth_invalid" -> throw HaAuthenticationException("Home Assistant rejected the access token")
                else -> throw HaProtocolException("Unexpected Home Assistant authentication response")
            }
        }
        if (completed == null) throw HaProtocolException("Home Assistant WebSocket authentication timed out")
    }

    private suspend fun readJson(socket: DefaultClientWebSocketSession): JSONObject {
        while (true) {
            val frame = socket.incoming.receive()
            if (frame is Frame.Text) {
                return try {
                    JSONObject(frame.readText())
                } catch (malformed: JSONException) {
                    throw HaProtocolException("Home Assistant sent a malformed authentication frame")
                }
            }
        }
    }

    private class KtorConnection(
        private val client: HttpClient,
        private val socket: DefaultClientWebSocketSession,
    ) : PanelAssistantTransportConnection {
        override suspend fun send(text: String) {
            socket.send(Frame.Text(text))
        }

        override suspend fun sendBinary(bytes: ByteArray) {
            socket.send(Frame.Binary(true, bytes))
        }

        override suspend fun receive(timeoutMs: Long): String? {
            val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000L
            while (true) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0L) return null
                // select, not a cancelled receive: when the timeout wins, no frame has been taken.
                val frame = select<Frame?> {
                    socket.incoming.onReceive { it }
                    onTimeout(remainingMs) { null }
                } ?: return null
                if (frame is Frame.Text) return frame.readText()
            }
        }

        override suspend fun close() {
            try {
                socket.close()
            } finally {
                client.close()
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val AUTH_TIMEOUT_MS = 15_000L

        /** Matches the exact-entity stream owner; Panel Assistant sends no large frame in this protocol. */
        const val MAX_INBOUND_FRAME_BYTES = 2L * 1024L * 1024L
    }
}
