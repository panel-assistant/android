package io.panelassistant.android.panelassistant

import io.panelassistant.android.media.VoiceStreamEnd
import io.panelassistant.android.media.VoiceStreamPlayback
import io.panelassistant.android.media.streamIdOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.util.Base64

/**
 * Panel Assistant's synchronised voice stream inside the session (`voice_stream_session`).
 *
 * The panel's Sendspin player ([PanelAssistantVoiceStreamPeer]) talks to Panel Assistant through this:
 * each message the player sends becomes a `panel_assistant/voice_stream_frame` command, taken by the
 * session loop with its next message id ([next]), and each `sendspin` event on the hello subscription goes
 * to the player in session order ([onFrame]). Panel Assistant alone decides what plays: an announcement or
 * reply it streams names its `stream_id`, and [play] holds until its `voice_stream_end`. When the panel
 * itself must stop speech, cancelling [play] mutes the player and sends `panel_assistant/voice_stream_stop`;
 * the player is unmuted by the next stream id Panel Assistant names.
 */
internal class PanelAssistantVoiceStream(
    private val player: PanelAssistantVoiceStreamPeer,
    private val log: (String) -> Unit = {},
) : VoiceStreamPlayback {
    /** Signalled whenever a command is waiting for the session loop. */
    val wake = Channel<Unit>(Channel.CONFLATED)

    private val lock = Any()
    private var token: String? = null
    private val outbound = ArrayDeque<JSONObject>()
    private val requests = HashSet<Long>()

    /** Each stream an announcement waits on or Panel Assistant has ended, oldest first. */
    private val ends = LinkedHashMap<String, CompletableDeferred<VoiceStreamEnd>>()

    /** The stream the panel stopped itself; the player stays muted until another stream id is named. */
    private var stopped: String? = null

    /** The player's client id for the hello, or null when it cannot run on this panel. */
    fun clientId(): String? = player.clientId()

    // --- The session loop's side. Called only from that one coroutine. ---

    /** The session granted the stream: connect the player over it. */
    fun open(sessionToken: String, grant: PanelAssistantVoiceStreamGrant) {
        synchronized(lock) {
            token = sessionToken
            outbound.clear()
            requests.clear()
            stopped = null
        }
        player.open(grant) { frame, text ->
            enqueue(JSONObject().put("type", COMMAND_FRAME).put("frame", Base64.getEncoder().encodeToString(frame)).put("text", text))
        }
    }

    /** The next command to send as message [id], or null when none is waiting. */
    fun next(id: Long): String? = synchronized(lock) {
        val session = token ?: return null
        val item = outbound.removeFirstOrNull() ?: return null
        requests += id
        item.put("id", id).put("session", session).toString()
    }

    /** Consume [frame] when it belongs to the stream: a `sendspin` or `voice_stream_end` event, or a command's result. */
    fun onFrame(frame: JSONObject, helloId: Long): Boolean {
        val id = PanelAssistantTransportProtocol.messageId(frame) ?: return false
        when (frame.optString("type")) {
            "event" -> {
                if (id != helloId) return false
                val event = frame.optJSONObject("event") ?: return false
                when (event.optString("kind")) {
                    EVENT_SENDSPIN -> {
                        val bytes = (event.opt("frame") as? String)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                        val text = event.opt("text") as? Boolean
                        val streamId = event.opt("stream_id").takeUnless { it == JSONObject.NULL }
                        if (bytes == null || text == null || (streamId != null && streamIdOf(streamId) == null)) {
                            log("voice stream ignored a malformed frame")
                            return true
                        }
                        streamIdOf(streamId)?.let(::playing)
                        player.receive(bytes, text)
                    }
                    EVENT_STREAM_END -> {
                        val streamId = streamIdOf(event.opt("stream_id"))
                        val outcome = (event.opt("outcome") as? String)?.takeIf { it in OUTCOMES }
                        if (streamId == null || outcome == null) {
                            log("voice stream ignored a malformed end")
                            return true
                        }
                        ended(streamId, VoiceStreamEnd(outcome, event.opt("listen_after") == true))
                    }
                    else -> return false
                }
                return true
            }
            "result" -> synchronized(lock) {
                if (!requests.remove(id)) return false
                if (frame.opt("success") != true) {
                    log("voice stream command refused: ${frame.optJSONObject("error")?.optString("code").orEmpty().ifEmpty { "failed" }}")
                }
                return true
            }
            else -> return false
        }
    }

    /** The session ended: the player disconnects and every stream still held is over. */
    fun close() {
        val held = synchronized(lock) {
            token = null
            outbound.clear()
            requests.clear()
            stopped = null
            ends.values.filter { !it.isCompleted }.also { ends.clear() }
        }
        player.close()
        // Without its session no end can arrive, so nothing may keep the wake word paused for it.
        held.forEach { it.complete(VoiceStreamEnd(OUTCOME_FAILED, listenAfter = false)) }
    }

    // --- The playback side. ---

    override suspend fun play(streamId: String): VoiceStreamEnd {
        val end = synchronized(lock) {
            if (token == null) return VoiceStreamEnd(OUTCOME_FAILED, listenAfter = false)
            ends.getOrPut(streamId) { CompletableDeferred() }.also { trim() }
        }
        playing(streamId)
        try {
            return end.await()
        } catch (cancelled: CancellationException) {
            if (!end.isCompleted) stop(streamId)
            throw cancelled
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun ended(streamId: String): VoiceStreamEnd? = synchronized(lock) {
        ends[streamId]?.takeIf { it.isCompleted }?.getCompleted()
    }

    /** Panel Assistant named [streamId] as playing: a different stream than the one stopped unmutes the player. */
    private fun playing(streamId: String) {
        val unmute = synchronized(lock) {
            (stopped != null && stopped != streamId).also { if (it) stopped = null }
        }
        if (unmute) player.unmute()
    }

    private fun ended(streamId: String, end: VoiceStreamEnd) {
        synchronized(lock) {
            ends.getOrPut(streamId) { CompletableDeferred() }.complete(end)
            trim()
        }
    }

    /** The panel stops [streamId] itself: silence it now and ask Panel Assistant to stop sending it. */
    private fun stop(streamId: String) {
        synchronized(lock) {
            stopped = streamId
            ends.remove(streamId)
        }
        player.mute()
        enqueue(JSONObject().put("type", COMMAND_STOP).put("stream_id", streamId))
    }

    private fun enqueue(command: JSONObject) {
        synchronized(lock) {
            if (token == null) return
            outbound.addLast(command)
        }
        wake.trySend(Unit)
    }

    /** Forget the oldest ended streams beyond [MAX_RECENT]; a stream still awaited is kept. */
    private fun trim() {
        val iterator = ends.entries.iterator()
        while (ends.size > MAX_RECENT && iterator.hasNext()) {
            if (iterator.next().value.isCompleted) iterator.remove()
        }
    }

    companion object {
        const val COMMAND_FRAME = "panel_assistant/voice_stream_frame"
        const val COMMAND_STOP = "panel_assistant/voice_stream_stop"
        const val EVENT_SENDSPIN = "sendspin"
        const val EVENT_STREAM_END = "voice_stream_end"
        const val OUTCOME_FAILED = "failed"
        private val OUTCOMES = setOf("played", "preempted", OUTCOME_FAILED)
        private const val MAX_RECENT = 16
    }
}
