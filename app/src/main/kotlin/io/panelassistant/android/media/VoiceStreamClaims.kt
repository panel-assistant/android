package io.panelassistant.android.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * A streamed event (`stream: true`) as the panel received it: when it arrived ([eventAtNs], the
 * `System.nanoTime()` base) and Panel Assistant's `stream_start_us`, the server-clock time of the first
 * sample of the stream that is its audio. Null [startUs] (a Panel Assistant without it) plays the URLs.
 */
internal data class StreamCue(val eventAtNs: Long, val startUs: Long?)

/**
 * The streams Panel Assistant sends to the panel's voice player, and which announcement each one is.
 *
 * Ownership is exact. A streamed event names its stream by `stream_start_us`, and every audio chunk
 * carries the server time of its first sample, which the player reports for each stream's first chunk
 * ([firstChunk]). An event owns the stream whose first chunk carries its `stream_start_us`, and no other;
 * arrival order and timing play no part, so a delayed stream can never be taken by a newer event.
 *
 * [play] returns once its stream has ended and played out. No such stream within [startTimeoutMs] of the
 * event, or an event without `stream_start_us`: [VoiceStreamMissing], and the URL plays instead. The event
 * keeps its correlation after it gives up, as after it is cancelled, so its stream arriving later is
 * dropped and never plays over or after the URL speech. Cancelling [play] drops a stream it already owns:
 * its audio stops at once and the rest is discarded. A stream no event owns is [Ownership.PENDING]: held
 * silent and unread until one does.
 *
 * The player reports [started], [firstChunk] and [ended] (after playing out) and asks [ownership] before
 * writing each buffer. Thread-safe.
 */
internal class VoiceStreamClaims(
    private val nanoTime: () -> Long = System::nanoTime,
    /** How long after the event its stream may still start; after that the URL path plays instead. */
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
) : VoiceStreamSource {
    enum class Ownership { PENDING, OWNED, DROPPED }

    private class Stream(val id: Long) {
        val ended = CompletableDeferred<Unit>()
        var startUs: Long? = null
        var owner: Event? = null

        @Volatile var dropped = false
    }

    private class Event(val startUs: Long) {
        val stream = CompletableDeferred<Stream>()
        var matched: Stream? = null

        /** It stopped waiting (fell back or was cancelled); its stream, arriving later, is dropped. */
        var abandoned = false
    }

    private val lock = Any()
    private var nextId = 0L
    private val streams = ArrayDeque<Stream>()
    private val events = ArrayDeque<Event>()

    /** A stream started now; returns its id. Nobody owns it until its first chunk says whose it is. */
    fun started(): Long = synchronized(lock) {
        val stream = Stream(++nextId)
        streams.addLast(stream)
        while (streams.size > MAX_RECENT) streams.removeFirst()
        stream.id
    }

    /** Stream [id]'s first chunk carries server time [startUs]; the event naming that time owns it. */
    fun firstChunk(id: Long, startUs: Long) {
        val (event, stream) = synchronized(lock) {
            val stream = find(id) ?: return
            if (stream.startUs != null) return
            stream.startUs = startUs
            val event = events.firstOrNull { it.matched == null && it.startUs == startUs } ?: return
            event.matched = stream
            stream.owner = event
            if (event.abandoned) {
                stream.dropped = true
                return
            }
            event to stream
        }
        event.stream.complete(stream)
    }

    /** Stream [id] ended and everything written for it has played out (or it was dropped). */
    fun ended(id: Long) {
        synchronized(lock) { find(id) }?.ended?.complete(Unit)
    }

    /** Whether stream [id] may play: owned by a live announcement, not (yet) owned, or never to play. */
    fun ownership(id: Long): Ownership = synchronized(lock) {
        val stream = find(id) ?: return Ownership.DROPPED
        when {
            stream.dropped -> Ownership.DROPPED
            stream.owner != null -> Ownership.OWNED
            else -> Ownership.PENDING
        }
    }

    /** Whether stream [id] will never play. */
    fun dropped(id: Long): Boolean = ownership(id) == Ownership.DROPPED

    override suspend fun play(cue: StreamCue) {
        val startUs = cue.startUs ?: throw VoiceStreamMissing()
        val event = Event(startUs)
        val found = synchronized(lock) {
            events.addLast(event)
            while (events.size > MAX_RECENT) events.removeFirst()
            val stream = streams.firstOrNull { it.owner == null && !it.dropped && it.startUs == startUs }
            if (stream != null) {
                event.matched = stream
                stream.owner = event
            }
            stream
        }
        if (found != null) return awaitEnd(found)
        val stream = try {
            withTimeout(startTimeoutMs - (nanoTime() - cue.eventAtNs) / 1_000_000L) { event.stream.await() }
        } catch (late: TimeoutCancellationException) {
            if (abandon(event)) throw VoiceStreamMissing()
            event.stream.await() // It arrived as the wait ran out: it is this announcement's.
        } catch (cancelled: CancellationException) {
            if (!abandon(event)) event.matched?.let(::drop)
            throw cancelled
        }
        awaitEnd(stream)
    }

    private suspend fun awaitEnd(stream: Stream) {
        try {
            stream.ended.await()
        } catch (cancelled: CancellationException) {
            drop(stream)
            throw cancelled
        }
    }

    /** True when [event] had no stream and now gives up the one it names; false when it has one. */
    private fun abandon(event: Event): Boolean = synchronized(lock) {
        val unmatched = event.matched == null
        if (unmatched) event.abandoned = true
        unmatched
    }

    private fun drop(stream: Stream) {
        stream.dropped = true
    }

    private fun find(id: Long): Stream? = streams.firstOrNull { it.id == id }

    private companion object {
        const val START_TIMEOUT_MS = 3_000L
        const val MAX_RECENT = 16
    }
}

/** The voice stream as an announcement lane plays it. */
internal fun interface VoiceStreamSource {
    /** Play the stream [cue] names; see [VoiceStreamClaims]. */
    suspend fun play(cue: StreamCue)
}

/** No stream for a streamed announcement in time (or it names none); its stream arriving later is dropped. */
internal class VoiceStreamMissing : java.io.IOException("no voice stream started for the announcement")

/**
 * One streamed announcement as the playback coordinator runs it. When no stream can be claimed in time
 * (the panel's player is not connected), it plays the event's [fallbackUrls] in order instead, as the
 * URL path does: preannouncement first, then the message.
 */
internal class StreamedSpeechRun(
    private val source: VoiceStreamSource,
    private val cue: StreamCue,
    private val fallbackUrls: List<String> = emptyList(),
    private val fallback: (String) -> AudioPlaybackRun = { throw UnsupportedOperationException("no URL playback") },
) : AudioPlaybackRun {
    @Volatile private var current: AudioPlaybackRun? = null

    override suspend fun execute() {
        try {
            return source.play(cue)
        } catch (missing: VoiceStreamMissing) {
            if (fallbackUrls.isEmpty()) throw missing
        }
        for (url in fallbackUrls) fallback(url).also { current = it }.execute()
    }

    /** The coordinator cancels the run's job as well, which drops a claimed stream. */
    override fun cancel() {
        current?.cancel()
    }
}

/** A streamed event's `stream_start_us`: a JSON integer ≥ 0, or null when absent, not an integer or negative. */
internal fun streamStartUs(json: org.json.JSONObject): Long? = when (val raw = json.opt("stream_start_us")) {
    is Int -> raw.toLong()
    is Long -> raw
    else -> null
}?.takeIf { it >= 0 }
