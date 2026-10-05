package io.panelassistant.android.sensors

import io.panelassistant.android.Config
import io.panelassistant.android.mqtt.MqttAddressFamilyPolicy
import io.panelassistant.android.util.HaWebSocketClients
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale

internal sealed interface HaCurrentUserStatus {
    data class Connected(
        val displayName: String?,
        val language: String? = null,
    ) : HaCurrentUserStatus
    data object NotConfigured : HaCurrentUserStatus
    data object Rejected : HaCurrentUserStatus
    data object Unavailable : HaCurrentUserStatus
}

internal data class HaCurrentUserRead(
    val user: JSONObject,
    val language: String?,
)

internal fun interface HaCurrentUserTransport {
    suspend fun read(baseUrl: String, accessToken: String): HaCurrentUserRead
}

internal class HaCurrentUserClient(
    private val auth: HaApiSessionProvider,
    private val transport: HaCurrentUserTransport = KtorHaCurrentUserTransport(),
) {
    constructor(config: Config) : this(
        DashboardHaApiSessionProvider(config),
        KtorHaCurrentUserTransport { MqttAddressFamilyPolicy.fromConfig(config.mqttAddressFamily) },
    )

    suspend fun status(): HaCurrentUserStatus {
        var session = auth.resolve(false)
        if (session.rejected) return HaCurrentUserStatus.Rejected
        if (session.baseUrl.isBlank() || session.accessToken.isNullOrBlank()) return HaCurrentUserStatus.NotConfigured
        val result = try {
            transport.read(session.baseUrl, checkNotNull(session.accessToken))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HaAuthenticationException) {
            session = auth.resolve(true)
            if (session.rejected || session.accessToken.isNullOrBlank()) return HaCurrentUserStatus.Rejected
            try {
                transport.read(session.baseUrl, checkNotNull(session.accessToken))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: HaAuthenticationException) {
                return HaCurrentUserStatus.Rejected
            } catch (_: Throwable) {
                return HaCurrentUserStatus.Unavailable
            }
        } catch (_: Throwable) {
            return HaCurrentUserStatus.Unavailable
        }
        return HaCurrentUserStatus.Connected(
            displayName = sanitizeName(result.user.optString("name")),
            language = canonicalHaLanguage(result.language),
        )
    }

    private fun sanitizeName(raw: String): String? = raw
        .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_NAME_CHARS)
        .takeIf(String::isNotBlank)

    private companion object {
        const val MAX_NAME_CHARS = 96
    }
}

private const val MAX_FRAME_BYTES = 256L * 1024L

internal interface HaCurrentUserSocket {
    suspend fun receive(): JSONObject
    suspend fun send(message: JSONObject)
}

internal fun haLanguageCommand(): JSONObject = JSONObject()
    .put("id", 2)
    .put("type", "frontend/get_user_data")
    .put("key", "language")

internal fun parseHaLanguage(response: JSONObject): String? {
    if (response.optString("type") != "result" || response.optInt("id") != 2 ||
        !response.optBoolean("success")
    ) return null
    val raw = response.optJSONObject("result")
        ?.optJSONObject("value")
        ?.opt("language") as? String
    return canonicalHaLanguage(raw)
}

internal fun canonicalHaLanguage(raw: String?): String? {
    val candidate = raw?.trim()?.replace('_', '-')?.takeIf(String::isNotEmpty) ?: return null
    if (candidate.length > MAX_LANGUAGE_TAG_CHARS || !candidate.matches(LANGUAGE_TAG_PATTERN)) return null
    return Locale.forLanguageTag(candidate).toLanguageTag()
        .takeUnless { it.equals("und", ignoreCase = true) || it.length > MAX_LANGUAGE_TAG_CHARS }
}

internal suspend fun readRequiredHaCurrentUser(
    socket: HaCurrentUserSocket,
    accessToken: String,
): JSONObject {
    if (socket.receive().optString("type") != "auth_required") {
        throw HaProtocolException("Home Assistant did not request WebSocket authentication")
    }
    socket.send(JSONObject().put("type", "auth").put("access_token", accessToken))
    when (socket.receive().optString("type")) {
        "auth_ok" -> Unit
        "auth_invalid" -> throw HaAuthenticationException("Home Assistant rejected the access token")
        else -> throw HaProtocolException("Unexpected Home Assistant authentication response")
    }
    socket.send(JSONObject().put("id", 1).put("type", "auth/current_user"))
    val response = socket.receive()
    if (response.optString("type") != "result" || response.optInt("id") != 1 ||
        !response.optBoolean("success")
    ) throw HaProtocolException("Home Assistant rejected the current-user request")
    return response.optJSONObject("result")
        ?: throw HaProtocolException("Home Assistant returned an invalid current user")
}

/** A missing preference must not turn a successfully authenticated user into an unavailable one. */
internal suspend fun readOptionalHaLanguage(socket: HaCurrentUserSocket): String? = try {
    withTimeoutOrNull(OPTIONAL_LANGUAGE_TIMEOUT_MS) {
        socket.send(haLanguageCommand())
        parseHaLanguage(socket.receive())
    }
} catch (_: Throwable) {
    // A socket/session can report its own CancellationException after the required request has
    // succeeded. Preserve caller cancellation, but otherwise keep the authenticated user result.
    currentCoroutineContext().ensureActive()
    null
}

private class KtorHaCurrentUserTransport(
    private val socketFamilyPolicy: () -> MqttAddressFamilyPolicy = { MqttAddressFamilyPolicy.AUTOMATIC },
) : HaCurrentUserTransport {
    override suspend fun read(baseUrl: String, accessToken: String): HaCurrentUserRead = withContext(Dispatchers.IO) {
        val policy = socketFamilyPolicy()
        val client = HaWebSocketClients.client(preferIpv4 = policy.initialPreferIpv4, ipv4Only = policy.ipv4Only)
        var socket: DefaultClientWebSocketSession? = null
        try {
            val active = withTimeout(TIMEOUT_MS) {
                HaWebSocketClients.open(client, io.panelassistant.android.dashboard.EntityFilterProtocol.upstreamWebSocketUrl(baseUrl), MAX_FRAME_BYTES)
            }
            socket = active
            val protocol = KtorHaCurrentUserSocket(active)
            val user = withTimeout(TIMEOUT_MS) { readRequiredHaCurrentUser(protocol, accessToken) }
            HaCurrentUserRead(user, readOptionalHaLanguage(protocol))
        } finally {
            runCatching { socket?.close() }
            client.close()
        }
    }

    private class KtorHaCurrentUserSocket(
        private val session: DefaultClientWebSocketSession,
    ) : HaCurrentUserSocket {
        override suspend fun send(message: JSONObject) {
            session.send(Frame.Text(message.toString()))
        }

        override suspend fun receive(): JSONObject = session.readJson()
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}

private suspend fun DefaultClientWebSocketSession.readJson(): JSONObject {
    while (true) {
        val frame = incoming.receive()
        if (frame is Frame.Text) return JSONObject(frame.readText())
    }
}

private const val OPTIONAL_LANGUAGE_TIMEOUT_MS = 2_000L
private const val MAX_LANGUAGE_TAG_CHARS = 63
private val LANGUAGE_TAG_PATTERN = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8}){0,7}")
