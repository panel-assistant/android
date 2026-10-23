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
    private var grant: PanelAssistantVoiceStreamGrant? = null
    private var sender: Any? = null
    private var resetRequest: Long? = null
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
            this.grant = grant
            outbound.clear()
            requests.clear()
            resetRequest = null
            stopped = null
            connectPlayer()
        }
    }

    /** Called under lock: a sender belongs only to this player lifetime, including its close callback. */
    private fun connectPlayer() {
        val granted = grant ?: return
        val mine = Any().also { sender = it }
        player.open(granted, closed = { synchronized(lock) { if (sender === mine) reset() } }) { frame, text ->
            if (frame.size > MAX_FRAME_BYTES) return@open log("voice stream dropped an oversized frame (${frame.size} bytes)")
            synchronized(lock) {
                if (sender !== mine) return@open
                enqueue(JSONObject().put("type", COMMAND_FRAME).put("frame", Base64.getEncoder().encodeToString(frame)).put("text", text))
            }
        }
    }

    /** The ordered reset result separates old server frames from the replacement handshake. */
    private fun reset() {
        if (token == null || sender == null) return
        sender = null
        outbound.clear()
        resetRequest = null
        player.close()
        ends.values.filter { !it.isCompleted }.forEach { it.complete(VoiceStreamEnd(OUTCOME_FAILED, listenAfter = false)) }
        enqueue(JSONObject().put("type", COMMAND_RESET))
    }

    /** The next command to send as message [id], or null when none is waiting. */
    fun next(id: Long): String? = synchronized(lock) {
        val session = token ?: return null
        val item = outbound.removeFirstOrNull() ?: return null
        requests += id
        if (item.optString("type") == COMMAND_RESET) resetRequest = id
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
                        if (synchronized(lock) { sender == null }) return true
                        val bytes = (event.opt("frame") as? String)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                        val text = event.opt("text") as? Boolean
                        val streamId = event.opt("stream_id").takeUnless { it == JSONObject.NULL }
                        if (bytes == null || text == null || !event.has("stream_id") || (streamId != null && streamIdOf(streamId) == null)) {
                            log("voice stream ignored a malformed frame")
                            return true
                        }
                        streamIdOf(streamId)?.let(::playing)
                        player.receive(bytes, text)
                    }
                    EVENT_CLOSED -> synchronized(lock) { reset() }
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
                if (id == resetRequest) {
                    resetRequest = null
                    if (frame.opt("success") == true) connectPlayer()
                }
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
            grant = null
            sender = null
            resetRequest = null
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
            if (token == null || streamIdOf(streamId) == null) return VoiceStreamEnd(OUTCOME_FAILED, listenAfter = false)
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
        const val COMMAND_RESET = "panel_assistant/voice_stream_reset"
        const val EVENT_CLOSED = "sendspin_closed"
        const val COMMAND_STOP = "panel_assistant/voice_stream_stop"
        const val EVENT_SENDSPIN = "sendspin"
        const val EVENT_STREAM_END = "voice_stream_end"
        const val OUTCOME_FAILED = "failed"
        private val OUTCOMES = setOf("played", "preempted", OUTCOME_FAILED)
        private const val MAX_RECENT = 16

        /** The largest Noise transport message (65 535 bytes), so the largest Sendspin message. */
        private const val MAX_FRAME_BYTES = 65_535
    }
}
