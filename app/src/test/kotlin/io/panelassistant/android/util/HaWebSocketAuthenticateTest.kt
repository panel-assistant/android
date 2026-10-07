package io.panelassistant.android.util

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import io.panelassistant.android.sensors.HaAuthenticationException
import io.panelassistant.android.sensors.HaProtocolException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The one Home Assistant WebSocket login shared by the Assist, presence, exact-entity and Panel
 * Assistant clients. Each caller wraps it in its own deadline; the exchange itself is pinned here.
 */
class HaWebSocketAuthenticateTest {
    /** A session whose server side is two channels: frames queued for the client, frames it sent. */
    private class ScriptedSession(vararg replies: Frame) : WebSocketSession {
        override val incoming = Channel<Frame>(Channel.UNLIMITED).apply { replies.forEach { trySend(it) } }
        override val outgoing = Channel<Frame>(Channel.UNLIMITED)
        override val coroutineContext: CoroutineContext = EmptyCoroutineContext
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override suspend fun flush() = Unit
        @Deprecated("not used") override fun terminate() = Unit

        fun sent(): List<JSONObject> = buildList {
            while (true) add(JSONObject((outgoing.tryReceive().getOrNull() as? Frame.Text ?: break).readText()))
        }
    }

    private fun text(type: String) = Frame.Text(JSONObject().put("type", type).toString())

    private fun authenticate(session: ScriptedSession): Throwable? = runBlocking {
        try {
            HaWebSocketClients.authenticate(session, "token")
            null
        } catch (failure: Exception) {
            failure
        }
    }

    @Test fun `auth_ok completes after sending the token, skipping non-text frames`() {
        val session = ScriptedSession(Frame.Binary(true, byteArrayOf(1)), text("auth_required"), text("auth_ok"))
        assertEquals(null, authenticate(session))
        val auth = session.sent().single()
        assertEquals("auth", auth.getString("type"))
        assertEquals("token", auth.getString("access_token"))
    }

    @Test fun `auth_invalid is an authentication failure`() {
        val failure = authenticate(ScriptedSession(text("auth_required"), text("auth_invalid")))
        if (failure !is HaAuthenticationException) fail("expected HaAuthenticationException, got $failure")
    }

    @Test fun `a server that does not ask for authentication is a protocol failure and gets no token`() {
        val session = ScriptedSession(text("result"))
        val failure = authenticate(session)
        if (failure !is HaProtocolException) fail("expected HaProtocolException, got $failure")
        assertEquals(emptyList<JSONObject>(), session.sent())
    }

    @Test fun `an unexpected reply to auth is a protocol failure`() {
        val failure = authenticate(ScriptedSession(text("auth_required"), text("event")))
        if (failure !is HaProtocolException || failure is HaAuthenticationException) {
            fail("expected HaProtocolException, got $failure")
        }
    }

    @Test fun `a malformed frame escapes as JSONException for the caller to classify`() {
        val failure = authenticate(ScriptedSession(Frame.Text("{not json")))
        if (failure !is JSONException) fail("expected JSONException, got $failure")
    }
}
