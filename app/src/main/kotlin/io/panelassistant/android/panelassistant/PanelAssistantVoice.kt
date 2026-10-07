package io.panelassistant.android.panelassistant

import io.panelassistant.android.assist.VoiceSettings
import io.panelassistant.android.assist.wakeword.MicroWakeWordModelConfig
import io.panelassistant.android.audio.MicrophonePresence
import io.panelassistant.android.audio.MicrophoneStatus
import io.panelassistant.android.audio.PcmConsumer
import io.panelassistant.android.audio.PcmFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One wake word the panel can listen for, as Home Assistant's selector shows it. */
internal data class PanelAssistantWakeWord(val id: String, val phrase: String, val languages: List<String>)

/** What the panel hears and which pipeline each wake word runs; its own settings are the one store. */
internal data class PanelAssistantVoiceConfiguration(
    val enabled: Boolean,
    val wakeWords: List<PanelAssistantWakeWord>,
    val active: List<String>,
    /** Wake word id to pipeline id; absent or blank is Home Assistant's preferred pipeline. */
    val pipelines: Map<String, String>,
    /** What the panel has and what its own capture check found, so Home Assistant can say why voice is quiet. */
    val microphone: MicrophoneStatus = MicrophoneStatus(MicrophonePresence.PROVEN),
) {
    companion object {
        /** The configuration a panel offers, or null when it has no microphone to be a satellite with. */
        fun of(
            microphone: MicrophoneStatus,
            settings: VoiceSettings,
            models: List<MicroWakeWordModelConfig>,
        ): PanelAssistantVoiceConfiguration? {
            if (!microphone.presence.offered) return null
            val ids = models.map { it.id }.toSet()
            return PanelAssistantVoiceConfiguration(
                enabled = settings.enabled,
                wakeWords = models.map { PanelAssistantWakeWord(it.id, it.wakeWord, it.trainedLanguages) },
                active = settings.wakeWords.filter { it in ids },
                pipelines = settings.pipelines.filterKeys { it in ids },
                microphone = microphone,
            )
        }
    }
}

/** Home Assistant asked the panel to play something, and perhaps to listen afterwards. */
internal data class PanelAssistantAnnouncement(
    val announceId: String,
    val url: String,
    val preannounceUrl: String?,
    val listenAfter: Boolean,
    /**
     * When the event arrived (`System.nanoTime()` base) if Panel Assistant streams the audio (`stream:
     * true`); null for the URL path. A streamed announcement plays the voice stream, not [url].
     */
    val streamAtNs: Long? = null,
)

/** What Home Assistant says during one conversation turn. */
internal sealed interface VoiceTurnEvent {
    /** Stop streaming: speech-to-text has heard the end of the command. */
    data object ListenEnd : VoiceTurnEvent

    /** [streamAtNs] as on [PanelAssistantAnnouncement]: non-null when the reply arrives on the voice stream. */
    data class Play(val url: String, val continueConversation: Boolean, val streamAtNs: Long? = null) : VoiceTurnEvent

    data class Failed(val code: String) : VoiceTurnEvent

    /** The turn is over; nothing follows. */
    data object End : VoiceTurnEvent
}

/**
 * The panel's side of the Assist satellite, carried on the Panel Assistant session.
 *
 * The session loop is the only sender of text requests, so everything the voice side wants to say is
 * queued here and taken by [next] with the loop's next message id; a turn's audio is the one thing sent
 * from elsewhere, as binary frames, which carry no id. Home Assistant runs the pipeline; the panel streams
 * what it hears after a wake word, stops when told, and plays what it is handed.
 */
internal class PanelAssistantVoice(
    private val scope: CoroutineScope,
    /** The current configuration, or null when this panel offers no voice at all. */
    private val configuration: () -> PanelAssistantVoiceConfiguration?,
    private val onAnnouncement: (PanelAssistantAnnouncement) -> Unit,
    private val log: (String) -> Unit = {},
    /** Home Assistant's colour for each wake word's pipeline, as opaque ARGB; the same on every panel. */
    private val onColors: (Map<String, Int>) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** Signalled whenever a request is waiting for the session loop. */
    val wake = Channel<Unit>(Channel.CONFLATED)

    private val lock = Any()
    private var live: Live? = null
    private val outbound = ArrayDeque<Outbound>()
    private val requests = HashMap<Long, Outbound>()
    private val turns = HashMap<Long, VoiceTurn>()

    private class Live(val connection: PanelAssistantTransportConnection, val token: String, val baseUrl: String)

    private sealed interface Outbound {
        data object Configure : Outbound
        class Run(val turn: VoiceTurn) : Outbound
        class Played(val announceId: String?) : Outbound
        class Cancel(val runId: Long) : Outbound
    }

    /** Whether the panel offers voice on its next session. */
    fun offered(): Boolean = configuration() != null

    /** The panel's wake words or pipelines changed; tell a live session. */
    fun configurationChanged() = enqueue(Outbound.Configure)

    /** Start one conversation turn; null when no session carries voice. */
    fun begin(wakeWordId: String?, continued: Boolean): VoiceTurn? {
        val turn = synchronized(lock) {
            val session = live ?: return null
            VoiceTurn(scope, session.connection, wakeWordId, continued, ::cancel).also {
                outbound.addLast(Outbound.Run(it))
            }
        }
        wake.trySend(Unit)
        return turn
    }

    /** The panel finished playing a reply (null) or the named announcement. */
    fun played(announceId: String?) = enqueue(Outbound.Played(announceId))

    private fun cancel(turn: VoiceTurn) {
        val runId = synchronized(lock) {
            outbound.removeAll { it is Outbound.Run && it.turn === turn }
            turn.runId?.takeIf { turns.remove(it) != null }
        } ?: return
        enqueue(Outbound.Cancel(runId))
    }

    private fun enqueue(item: Outbound) {
        synchronized(lock) {
            if (live == null) return
            if (item is Outbound.Configure && Outbound.Configure in outbound) return
            outbound.addLast(item)
        }
        wake.trySend(Unit)
    }

    // --- The session loop's side. Called only from that one coroutine. ---

    fun open(connection: PanelAssistantTransportConnection, token: String, baseUrl: String) {
        synchronized(lock) {
            live = Live(connection, token, baseUrl)
            outbound.clear()
            outbound.addLast(Outbound.Configure)
        }
        wake.trySend(Unit)
    }

    /** The next request to send as message [id], or null when none is waiting. */
    fun next(id: Long): String? = synchronized(lock) {
        val session = live ?: return null
        val item = outbound.removeFirstOrNull() ?: return null
        val message = JSONObject().put("id", id).put("session", session.token)
        when (item) {
            Outbound.Configure -> {
                val current = configuration() ?: return null
                message.put("type", COMMAND_VOICE_CONFIGURATION)
                    .put("enabled", current.enabled)
                    .put(
                        "wake_words",
                        JSONArray(
                            current.wakeWords.map { word ->
                                JSONObject().put("id", word.id).put("wake_word", word.phrase)
                                    .put("trained_languages", JSONArray(word.languages))
                            },
                        ),
                    )
                    .put("active", JSONArray(current.active))
                    .put("pipelines", JSONObject(current.pipelines.filterValues { it.isNotBlank() }))
                    .put(
                        "microphone",
                        JSONObject().put("presence", current.microphone.presence.wireValue)
                            .put("check", current.microphone.check.wireValue)
                            .apply { current.microphone.detail?.let { put("detail", it) } },
                    )
            }
            is Outbound.Run -> {
                item.turn.runId = id
                turns[id] = item.turn
                message.put("type", COMMAND_VOICE_RUN)
                    .put("wake_word_id", item.turn.wakeWordId ?: JSONObject.NULL)
                    .put("continued", item.turn.continued)
            }
            is Outbound.Played -> {
                message.put("type", COMMAND_VOICE_PLAYED)
                item.announceId?.let { message.put("announce_id", it) }
            }
            // Core's own command, which carries no session: ending the subscription cancels the pipeline.
            is Outbound.Cancel -> {
                message.remove("session")
                message.put("type", "unsubscribe_events").put("subscription", item.runId)
            }
        }
        requests[id] = item
        message.toString()
    }

    /**
     * Consume [frame] when it belongs to voice: an announcement on the hello subscription, an event of a
     * turn, or the result of a voice request. Returns false for every other frame.
     */
    fun onFrame(frame: JSONObject, helloId: Long): Boolean {
        val id = PanelAssistantTransportProtocol.messageId(frame) ?: return false
        val type = frame.optString("type")
        val event = frame.optJSONObject("event")
        if (type == "event" && id == helloId) {
            if (event?.optString("kind") == EVENT_VOICE_COLORS) {
                onColors(colors(event.optJSONObject("colors")))
                return true
            }
            if (event?.optString("kind") != EVENT_VOICE_ANNOUNCE) return false
            val announcement = announcement(event)
            if (announcement == null) log("voice ignored a malformed announcement") else onAnnouncement(announcement)
            return true
        }
        synchronized(lock) {
            if (type == "event") {
                val turn = turns[id] ?: return false
                val parsed = turnEvent(event)
                if (parsed == VoiceTurnEvent.End) turns.remove(id)
                parsed?.let(turn::deliver)
                return true
            }
            if (type != "result") return false
            val item = requests.remove(id) ?: return false
            val success = frame.opt("success") == true
            val code = frame.optJSONObject("error")?.optString("code").orEmpty().ifEmpty { "failed" }
            when (item) {
                is Outbound.Run -> {
                    val handler = frame.optJSONObject("result")?.opt("handler_id") as? Int
                    if (success && handler != null && handler in 1..255) {
                        item.turn.attached(handler)
                    } else {
                        turns.remove(id)
                        item.turn.fail(code)
                    }
                }
                is Outbound.Cancel -> Unit
                else -> if (!success) log("voice request ${item.javaClass.simpleName} refused: $code")
            }
            return true
        }
    }

    /** The session ended: every turn it carried is over. */
    fun close() {
        val ended = synchronized(lock) {
            live = null
            val all = turns.values.toList() + outbound.filterIsInstance<Outbound.Run>().map { it.turn }
            outbound.clear()
            requests.clear()
            turns.clear()
            all
        }
        ended.forEach { it.fail(CODE_SESSION_CLOSED) }
    }

    private fun announcement(event: JSONObject): PanelAssistantAnnouncement? {
        val announceId = (event.opt("announce_id") as? String)?.takeIf(ANNOUNCE_ID::matches) ?: return null
        val url = (event.opt("url") as? String)?.takeIf { it.isNotBlank() } ?: return null
        return PanelAssistantAnnouncement(
            announceId = announceId,
            url = resolve(url),
            preannounceUrl = (event.opt("preannounce_url") as? String)?.takeIf { it.isNotBlank() }?.let(::resolve),
            listenAfter = event.opt("listen_after") == true,
            streamAtNs = streamAt(event),
        )
    }

    /** The arrival time of an event Panel Assistant streams (`stream: true`), or null for the URL path. */
    private fun streamAt(event: JSONObject): Long? = if (event.opt("stream") == true) nanoTime() else null

    private fun colors(map: JSONObject?): Map<String, Int> {
        map ?: return emptyMap()
        return map.keys().asSequence().mapNotNull { id ->
            val hex = (map.opt(id) as? String)?.takeIf(COLOR::matches) ?: return@mapNotNull null
            id to (0xFF000000.toInt() or hex.substring(1).toInt(16))
        }.toMap()
    }

    private fun resolve(url: String): String = synchronized(lock) { live?.baseUrl }
        ?.let { base -> resolveUrl(base, url) } ?: url

    private fun turnEvent(event: JSONObject?): VoiceTurnEvent? = when (event?.optString("kind")) {
        "listen_end" -> VoiceTurnEvent.ListenEnd
        "play" -> (event.opt("url") as? String)?.takeIf { it.isNotBlank() }?.let { url ->
            VoiceTurnEvent.Play(resolve(url), event.opt("continue_conversation") == true, streamAt(event))
        }
        "error" -> VoiceTurnEvent.Failed((event.opt("code") as? String).orEmpty().ifEmpty { "error" })
        "end" -> VoiceTurnEvent.End
        else -> null
    }

    companion object {
        const val COMMAND_VOICE_CONFIGURATION = "panel_assistant/voice_configuration"
        const val COMMAND_VOICE_RUN = "panel_assistant/voice_run"
        const val COMMAND_VOICE_PLAYED = "panel_assistant/voice_played"
        const val EVENT_VOICE_ANNOUNCE = "voice_announce"
        const val EVENT_VOICE_COLORS = "voice_colors"
        private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
        const val CODE_SESSION_CLOSED = "session_closed"
        private val ANNOUNCE_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        /** A Home Assistant path, resolved against the address this panel's own session reached. */
        fun resolveUrl(baseUrl: String, url: String): String {
            if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) return url
            val base = baseUrl.trim().trimEnd('/')
            return if (url.startsWith("/")) base + url else "$base/$url"
        }
    }
}

/**
 * One conversation turn's audio and events. As a [PcmConsumer] it buffers what the microphone hears from
 * the moment it is attached, before Home Assistant has named the turn's binary handler, so nothing said
 * straight after the wake word is lost; once the handler is known the buffered and the live audio go out
 * in order. The capture thread only encodes and enqueues: a stalled socket costs the oldest audio, never
 * the shared capture.
 */
internal class VoiceTurn(
    scope: CoroutineScope,
    private val connection: PanelAssistantTransportConnection,
    val wakeWordId: String?,
    val continued: Boolean,
    private val onCancel: (VoiceTurn) -> Unit,
) : PcmConsumer {
    /** Set by the session loop when it sends the turn's `voice_run`. */
    @Volatile
    var runId: Long? = null

    val events = Channel<VoiceTurnEvent>(Channel.UNLIMITED)

    private val handler = CompletableDeferred<Int>()
    private val audio = Channel<ByteArray>(MAX_BUFFERED_FRAMES, BufferOverflow.DROP_OLDEST)

    @Volatile
    private var listening = true

    @Volatile
    private var finished = false

    private val sender = scope.launch {
        try {
            val id = handler.await().toByte()
            for (frame in audio) {
                frame[0] = id
                connection.sendBinary(frame)
                // An empty payload is Core's end of the audio.
                if (frame.size == 1) break
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A socket that failed mid-turn ends the session, and the session's close fails the turn.
        }
    }

    override fun onFrame(frame: PcmFrame) {
        if (!listening) return
        val samples = frame.samples
        val bytes = ByteArray(1 + samples.size * 2)
        for (index in samples.indices) {
            val sample = samples[index].toInt()
            bytes[1 + index * 2] = sample.toByte()
            bytes[2 + index * 2] = (sample shr 8).toByte()
        }
        audio.trySend(bytes)
    }

    override fun onDropped(count: Int) = Unit

    /** Stop streaming and tell Home Assistant the command is over. */
    fun endAudio() {
        if (!listening) return
        listening = false
        audio.trySend(ByteArray(1))
        audio.close()
    }

    /** Leave the turn: stop the audio and, if Home Assistant has not ended it, cancel it there. */
    fun close() {
        listening = false
        audio.close()
        sender.cancel()
        if (!finished) onCancel(this)
        finished = true
    }

    internal fun attached(handlerId: Int) {
        handler.complete(handlerId)
    }

    internal fun deliver(event: VoiceTurnEvent) {
        if (event == VoiceTurnEvent.End) finished = true
        events.trySend(event)
    }

    internal fun fail(code: String) {
        finished = true
        listening = false
        audio.close()
        sender.cancel()
        handler.cancel()
        events.trySend(VoiceTurnEvent.Failed(code))
        events.trySend(VoiceTurnEvent.End)
    }

    private companion object {
        /** Two seconds of 10 ms frames, held while Home Assistant names the handler. */
        const val MAX_BUFFERED_FRAMES = 200
    }
}
