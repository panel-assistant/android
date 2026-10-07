package io.panelassistant.android.media

import org.json.JSONObject

/** How Panel Assistant ended one stream (`voice_stream_end`): `played`, `preempted` or `failed`. */
internal data class VoiceStreamEnd(val outcome: String, val listenAfter: Boolean)

/** The voice stream as an announcement lane plays it; Panel Assistant decides what plays and when it ends. */
internal interface VoiceStreamPlayback {
    /**
     * Hold until Panel Assistant ends stream [streamId], and return how it ended. Cancelling stops the
     * stream on this panel: its audio is silenced and Panel Assistant is told to stop sending it.
     */
    suspend fun play(streamId: String): VoiceStreamEnd

    /** How stream [streamId] ended, once it has. */
    fun ended(streamId: String): VoiceStreamEnd?
}

/** One streamed announcement in the playback lane: it holds the lane, and so the panel's media, until its end. */
internal class StreamedSpeechRun(private val source: VoiceStreamPlayback, private val streamId: String) : AudioPlaybackRun {
    override suspend fun execute() {
        source.play(streamId)
    }

    /** The coordinator cancels the run's job as well, which stops the stream. */
    override fun cancel() = Unit
}

private val STREAM_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

/** A Panel Assistant stream id: a short token, or null when absent or malformed. */
internal fun streamIdOf(raw: Any?): String? = (raw as? String)?.takeIf(STREAM_ID::matches)

/** The stream a message plays (`stream: true` with its `stream_id`), or null for the URL path. */
internal fun streamedId(json: JSONObject): String? = if (json.opt("stream") == true) streamIdOf(json.opt("stream_id")) else null
