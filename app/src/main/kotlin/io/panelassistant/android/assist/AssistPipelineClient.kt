package io.panelassistant.android.assist

import io.panelassistant.android.Config
import io.panelassistant.android.dashboard.EntityFilterProtocol
import io.panelassistant.android.mqtt.MqttAddressFamilyPolicy
import io.panelassistant.android.panelassistant.PanelAssistantVoice
import io.panelassistant.android.sensors.DashboardHaApiSessionProvider
import io.panelassistant.android.sensors.HaApiSession
import io.panelassistant.android.sensors.HaApiSessionProvider
import io.panelassistant.android.sensors.HaAuthenticationException
import io.panelassistant.android.sensors.HaProtocolException
import io.panelassistant.android.util.HaTransportFault
import io.panelassistant.android.util.HaWebSocketClients
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** The panel's end of one authenticated Assist WebSocket, with the handshake already done. */
internal interface AssistSocket {
    /** The next inbound text frame, or null once the peer stopped sending. */
    suspend fun receiveText(): String?
    suspend fun sendText(text: String)
    suspend fun close()
}

/**
 * Opens an authenticated Assist socket. The handshake lives behind this seam, exactly as the exact
 * entity stream puts it behind its own transport, so a rejected token surfaces the same way whether
 * it was refused at `auth_invalid` or by the connection never being made.
 */
internal fun interface AssistTransport {
    /** @throws HaAuthenticationException when Home Assistant refuses the token. */
    suspend fun connect(baseUrl: String, accessToken: String): AssistSocket
}

/**
 * Plays one reply to completion.
 *
 * The contract is narrow because the run reports its outcome from it: [play] returns **only** when
 * the audio has actually finished playing. Handing the url to a queue and returning is not
 * completion, and an implementation that does so makes every run claim the panel spoke when it may
 * not have.
 *
 * A reply that failed, or that a later announcement superseded before it finished, is not a
 * successful reply: throw [AssistPlaybackException] to say which. The panel plays announcements
 * through one coordinator that keeps only the newest, so supersession is an ordinary outcome rather
 * than a fault, and it still means this run's answer was never heard.
 *
 * Cancelling the parent run is not a playback failure — it unwinds normally and reports nothing.
 * Cancellation of the playback alone, while the run is still live, is a reply that did not play.
 */
internal fun interface AssistPlayback {
    suspend fun play(url: String)
}

/**
 * A reply that could not be played to completion. [code] reaches the run's outcome unchanged, so it
 * must be one of the playback codes on [AssistPipelineClient].
 */
internal class AssistPlaybackException(
    val code: String = AssistPipelineClient.CODE_PLAYBACK_FAILED,
    message: String,
) : RuntimeException(message)

internal sealed interface AssistCatalogResult {
    data class Catalog(val catalog: AssistPipelineCatalog) : AssistCatalogResult
    data class Failed(val error: AssistError) : AssistCatalogResult
}

/**
 * Home Assistant's Assist pipelines as the panel reads them on its own: the catalogue for the Configure
 * page's pipeline picker, and text-to-speech for the panel's spoken instructions. Voice turns are not
 * run here; Panel Assistant runs them for the panel's satellite.
 */
internal class AssistPipelineClient(
    private val auth: HaApiSessionProvider,
    private val transport: AssistTransport = KtorAssistTransport(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    /** How long Home Assistant may take to synthesise the text. */
    private val defaultRunTimeoutMs: Long = DEFAULT_RUN_TIMEOUT_MS,
    private val playbackTimeoutMs: Long = DEFAULT_PLAYBACK_TIMEOUT_MS,
) {
    constructor(config: Config) : this(
        DashboardHaApiSessionProvider(config),
        KtorAssistTransport(socketFamilyPolicy = { MqttAddressFamilyPolicy.fromConfig(config.mqttAddressFamily) }),
    )

    suspend fun listPipelines(): AssistCatalogResult = withContext(dispatcher) {
        when (val connection = connect()) {
            is Connection.Failed -> AssistCatalogResult.Failed(connection.error)
            is Connection.Open -> try {
                withTimeout(handshakeTimeoutMs) { readCatalog(connection.socket) }
            } catch (_: TimeoutCancellationException) {
                AssistCatalogResult.Failed(AssistError(CODE_TIMEOUT, "Home Assistant did not list its pipelines"))
            } finally {
                // Uncancellable for the same reason the run's teardown is: a cancelled listing must
                // still close its socket and the HTTP client behind it.
                withContext(NonCancellable) { connection.socket.close() }
            }
        }
    }

    private sealed interface Synthesis {
        data class Url(val url: String) : Synthesis
        data class Failed(val error: AssistError) : Synthesis
    }

    /** Synthesise [text] with [pipelineId]'s text-to-speech on a short socket of its own, then play it. */
    private suspend fun speakThrough(pipelineId: String, text: String, playback: AssistPlayback): AssistOutcome =
        withContext(dispatcher) {
            when (val connection = connect()) {
                is Connection.Failed -> AssistOutcome(error = connection.error)
                is Connection.Open -> try {
                    val synthesis = withTimeoutOrNull(defaultRunTimeoutMs) {
                        synthesize(connection.socket, pipelineId, text)
                    } ?: Synthesis.Failed(AssistError(CODE_TIMEOUT, "Home Assistant did not synthesise the text in time"))
                    when (synthesis) {
                        is Synthesis.Failed -> AssistOutcome(error = synthesis.error)
                        is Synthesis.Url -> play(PanelAssistantVoice.resolveUrl(connection.baseUrl, synthesis.url), playback)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    AssistOutcome(error = AssistError(CODE_UNAVAILABLE, error.javaClass.simpleName.take(MAX_DETAIL_CHARS)))
                } finally {
                    // Uncancellable: a cancelled narration must still close its socket and HTTP client.
                    withContext(NonCancellable) { connection.socket.close() }
                }
            }
        }

    private suspend fun synthesize(socket: AssistSocket, pipelineId: String, text: String): Synthesis {
        socket.sendText(
            AssistPipelineJson.runMessage(RUN_REQUEST_ID, pipelineId, text),
        )
        while (true) {
            val raw = socket.receiveText()
                ?: return Synthesis.Failed(AssistError(CODE_CLOSED, "Home Assistant closed the socket"))
            when (val message = runCatching { AssistPipelineJson.parseMessage(raw) }.getOrNull()) {
                is AssistMessage.Event -> if (message.id == RUN_REQUEST_ID) {
                    when (val event = message.event) {
                        is AssistEvent.TtsEnd -> event.url?.let { return Synthesis.Url(it) }
                        is AssistEvent.Failure -> return Synthesis.Failed(AssistError(event.code, event.message))
                        AssistEvent.RunEnd ->
                            return Synthesis.Failed(AssistError(CODE_CLOSED, "The pipeline ended without speech"))
                        else -> Unit
                    }
                }
                is AssistMessage.Result -> if (message.id == RUN_REQUEST_ID && !message.success) {
                    return Synthesis.Failed(
                        AssistError(message.code ?: CODE_UNAVAILABLE, message.message ?: "Home Assistant refused the run"),
                    )
                }
                else -> Unit
            }
        }
    }

    private suspend fun play(url: String, playback: AssistPlayback): AssistOutcome {
        val finished = try {
            withTimeoutOrNull(playbackTimeoutMs) { playback.play(url) } != null
        } catch (failed: AssistPlaybackException) {
            return AssistOutcome(ttsUrl = url, error = AssistError(failed.code, failed.message.orEmpty()))
        }
        return if (finished) {
            AssistOutcome(ttsUrl = url)
        } else {
            AssistOutcome(ttsUrl = url, error = AssistError(CODE_PLAYBACK_TIMEOUT, "The speech did not finish playing in time"))
        }
    }

    /**
     * Synthesises [text] with a Home Assistant Assist pipeline that speaks [localeTag], then plays
     * the returned media through the same completion-aware playback used for Assist replies. The
     * configured preferred pipeline wins when its TTS language matches; otherwise another matching
     * configured pipeline supplies its own engine and voice. Missing language metadata fails closed
     * so panel instructions are never narrated in a language the user did not select.
     */
    suspend fun speakText(
        text: String,
        localeTag: String,
        playback: AssistPlayback,
    ): AssistOutcome {
        val spoken = text.trim()
        if (spoken.isEmpty()) {
            return AssistOutcome(error = AssistError(CODE_INVALID_TEXT, "There is no text to speak"))
        }
        val locale = localeTag.normalizedLanguageTag()
        if (locale.isEmpty()) {
            return AssistOutcome(error = AssistError(CODE_TTS_LANGUAGE_UNAVAILABLE, "No speech language was selected"))
        }
        val catalog = when (val listed = listPipelines()) {
            is AssistCatalogResult.Catalog -> listed.catalog
            is AssistCatalogResult.Failed -> return AssistOutcome(error = listed.error)
        }
        val preferred = catalog.pipelines.firstOrNull { it.id == catalog.preferredId }
        val pipeline = preferred?.takeIf { it.ttsLanguage.matchesLanguage(locale) }
            ?: catalog.pipelines.firstOrNull { it.ttsLanguage.matchesLanguage(locale) }
            ?: return AssistOutcome(
                error = AssistError(
                    CODE_TTS_LANGUAGE_UNAVAILABLE,
                    "Home Assistant has no text-to-speech pipeline for $locale",
                ),
            )
        return speakThrough(pipeline.id, spoken, playback)
    }

    private fun String.normalizedLanguageTag(): String = trim().replace('_', '-').lowercase()

    private fun String?.matchesLanguage(requested: String): Boolean {
        val offered = this?.normalizedLanguageTag().orEmpty()
        if (offered.isEmpty()) return false
        if (offered == requested) return true
        return offered.substringBefore('-') == requested.substringBefore('-')
    }

    private suspend fun readCatalog(socket: AssistSocket): AssistCatalogResult {
        socket.sendText(AssistPipelineJson.listMessage(LIST_REQUEST_ID))
        while (true) {
            val raw = socket.receiveText()
                ?: return AssistCatalogResult.Failed(AssistError(CODE_CLOSED, "Home Assistant closed the socket"))
            val message = runCatching { AssistPipelineJson.parseMessage(raw) }.getOrNull() ?: continue
            if (message !is AssistMessage.Result || message.id != LIST_REQUEST_ID) continue
            val result = message.result
            return if (!message.success || result == null) {
                AssistCatalogResult.Failed(
                    AssistError(
                        message.code ?: CODE_LIST_FAILED,
                        message.message ?: "Home Assistant refused to list its pipelines",
                    ),
                )
            } else {
                AssistCatalogResult.Catalog(AssistPipelineJson.parseCatalog(result))
            }
        }
    }

    private suspend fun connect(): Connection {
        var session = auth.resolve(false)
        if (session.rejected) {
            return Connection.Failed(AssistError(CODE_AUTH_REJECTED, "Home Assistant rejected the panel's token"))
        }
        if (session.baseUrl.isBlank() || session.accessToken.isNullOrBlank()) {
            return Connection.Failed(credentialFailure(session))
        }
        return try {
            open(session)
        } catch (_: HaAuthenticationException) {
            // Exactly one forced refresh and one retry. A token can expire between resolve and
            // handshake; anything beyond one retry is a storm against a server already saying no.
            session = auth.resolve(true)
            if (session.rejected) {
                Connection.Failed(AssistError(CODE_AUTH_REJECTED, "Home Assistant rejected the panel's token"))
            } else if (session.accessToken.isNullOrBlank()) {
                // A refresh that failed in transport left no token, but the credential is not the
                // thing that is wrong.
                Connection.Failed(credentialFailure(session))
            } else {
                try {
                    open(session)
                } catch (_: HaAuthenticationException) {
                    Connection.Failed(AssistError(CODE_AUTH_REJECTED, "Home Assistant rejected the panel's token"))
                }
            }
        }
    }

    /**
     * Why this panel has no usable token. The three reasons are kept apart on purpose: a panel whose
     * network is broken must not tell its owner to sign in again, and a refresh that was never
     * attempted is not the same fact as one that was refused. The classified evidence is reported
     * rather than [HaApiSession.transientDetail], which is raw platform text and can embed the
     * configured host.
     */
    private fun credentialFailure(session: HaApiSession): AssistError = when {
        session.transientDetail != null || session.transientEvidence.fault != HaTransportFault.NONE ->
            AssistError(
                CODE_HA_UNREACHABLE,
                "Home Assistant could not be reached: ${session.transientEvidence.orUnclassified().fault.wire}",
            )
        session.notAttempted ->
            AssistError(CODE_CREDENTIALS_UNAVAILABLE, "No Home Assistant credential was tried")
        else -> AssistError(CODE_NOT_CONFIGURED, "This panel has no Home Assistant credential")
    }

    private suspend fun open(session: HaApiSession): Connection = try {
        val socket = withTimeout(handshakeTimeoutMs) {
            transport.connect(session.baseUrl, checkNotNull(session.accessToken))
        }
        Connection.Open(socket, session.baseUrl)
    } catch (_: TimeoutCancellationException) {
        Connection.Failed(AssistError(CODE_TIMEOUT, "Home Assistant did not accept the connection in time"))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rejected: HaAuthenticationException) {
        throw rejected
    } catch (error: Throwable) {
        Connection.Failed(AssistError(CODE_UNAVAILABLE, error.javaClass.simpleName.take(MAX_DETAIL_CHARS)))
    }

    private sealed interface Connection {
        data class Open(val socket: AssistSocket, val baseUrl: String) : Connection
        data class Failed(val error: AssistError) : Connection
    }

    internal companion object {
        const val LIST_REQUEST_ID = 1
        const val RUN_REQUEST_ID = 1
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 10_000L
        const val DEFAULT_RUN_TIMEOUT_MS = 60_000L
        const val DEFAULT_PLAYBACK_TIMEOUT_MS = 60_000L
        const val CODE_AUTH_REJECTED = "auth_rejected"
        const val CODE_CREDENTIALS_UNAVAILABLE = "credentials_unavailable"
        const val CODE_NOT_CONFIGURED = "not_configured"
        const val CODE_UNAVAILABLE = "unavailable"
        const val CODE_TIMEOUT = "timeout"
        const val CODE_CLOSED = "closed"
        const val CODE_LIST_FAILED = "list_failed"
        const val CODE_INVALID_TEXT = "invalid_text"
        const val CODE_TTS_LANGUAGE_UNAVAILABLE = "tts_language_unavailable"
        const val CODE_PLAYBACK_FAILED = "playback_failed"
        const val CODE_PLAYBACK_TIMEOUT = "playback_timeout"

        /** A later announcement replaced this reply before it finished; the answer was not heard. */
        const val CODE_PLAYBACK_SUPERSEDED = "playback_superseded"

        /** The panel has a credential but could not reach Home Assistant to use or refresh it. */
        const val CODE_HA_UNREACHABLE = "ha_unreachable"
        const val MAX_DETAIL_CHARS = 120

    }
}

internal class KtorAssistTransport(
    private val socketFamilyPolicy: () -> MqttAddressFamilyPolicy = { MqttAddressFamilyPolicy.AUTOMATIC },
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
) : AssistTransport {
    override suspend fun connect(baseUrl: String, accessToken: String): AssistSocket = withContext(Dispatchers.IO) {
        val policy = socketFamilyPolicy()
        val client = HaWebSocketClients.client(preferIpv4 = policy.initialPreferIpv4, ipv4Only = policy.ipv4Only)
        var socket: DefaultClientWebSocketSession? = null
        try {
            val active = withTimeout(connectTimeoutMs) {
                HaWebSocketClients.open(client, EntityFilterProtocol.upstreamWebSocketUrl(baseUrl), MAX_WS_FRAME_BYTES)
            }
            socket = active
            authenticate(active, accessToken)
            KtorAssistSocket(client, active)
        } catch (error: Throwable) {
            runCatching { socket?.close() }
            client.close()
            throw error
        }
    }

    private suspend fun authenticate(socket: DefaultClientWebSocketSession, accessToken: String) {
        withTimeout(connectTimeoutMs) {
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
    }

    private suspend fun readJson(socket: DefaultClientWebSocketSession): JSONObject {
        while (true) {
            val frame = socket.incoming.receive()
            if (frame is Frame.Text) return JSONObject(frame.readText())
        }
    }

    private class KtorAssistSocket(
        private val client: HttpClient,
        private val socket: DefaultClientWebSocketSession,
    ) : AssistSocket {
        override suspend fun receiveText(): String? {
            while (true) {
                val frame = try {
                    socket.incoming.receive()
                } catch (_: ClosedReceiveChannelException) {
                    return null
                }
                if (frame is Frame.Text) return frame.readText()
            }
        }

        override suspend fun sendText(text: String) = socket.send(Frame.Text(text))

        override suspend fun close() {
            runCatching { socket.close() }
            client.close()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** Event frames are small; this matches the bound the exact entity stream applies. */
        const val MAX_WS_FRAME_BYTES = 2L * 1024L * 1024L
    }
}
