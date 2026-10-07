package io.panelassistant.android.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The streams Panel Assistant sends to the panel's voice player, and which announcement each one is.
 *
 * A streamed announcement or reply arrives as an event on the session, a separate connection from the
 * stream, so arrival order says nothing reliable about which stream is whose: a stream can be delayed
 * past a newer event. What does identify it is when it is scheduled to play. Panel Assistant starts the
 * group stream and only then sends the event, and every chunk carries the server time of its first
 * sample, which the library maps to local time ([scheduled]). So an event owns the stream whose first
 * sample is scheduled from [beforeNs] before to [afterNs] after the event's arrival, nearest first; at
 * most one stream per event and one event per stream.
 *
 * [play] returns once its stream has ended and played out. No matching stream within [startTimeoutMs]
 * of the event: [VoiceStreamMissing], and the URL plays instead. The event still owns a stream scheduled
 * in its window that arrives later, as it does after it is cancelled, so that stream is dropped: it never
 * plays over or after the URL speech, and never plays as a newer announcement. Cancelling [play] drops a
 * stream it already owns: its audio stops at once and the rest is discarded. A stream no event owns is
 * [Ownership.PENDING] (silent) until one does, and dropped once no event can still claim it.
 *
 * The player reports [started], [scheduled] and [ended] (after playing out) and asks [ownership] before
 * writing each buffer. Thread-safe.
 */
internal class VoiceStreamClaims(
    private val nanoTime: () -> Long = System::nanoTime,
    private val beforeNs: Long = CLAIM_BEFORE_NS,
    private val afterNs: Long = CLAIM_AFTER_NS,
    /** How long after the event its stream may still be matched; after that the URL path plays instead. */
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
) : VoiceStreamSource {
    enum class Ownership { PENDING, OWNED, DROPPED }

    private class Stream(val id: Long) {
        val ended = CompletableDeferred<Unit>()
        var scheduledNs: Long? = null
        var owner: Event? = null

        @Volatile var dropped = false
    }

    private class Event(val atNs: Long) {
        val stream = CompletableDeferred<Stream>()
        var matched: Stream? = null

        /** It stopped waiting (fell back or was cancelled); a stream it matches later is dropped. */
        var abandoned = false
    }

    private val lock = Any()
    private var nextId = 0L
    private val streams = ArrayDeque<Stream>()
    private val events = ArrayDeque<Event>()

    /** A stream started now; returns its id. Nobody owns it until its schedule is known. */
    fun started(): Long = synchronized(lock) {
        val stream = Stream(++nextId)
        streams.addLast(stream)
        while (streams.size > MAX_RECENT) streams.removeFirst()
        stream.id
    }

    /** Stream [id]'s first sample is scheduled to play at [atNs] (the [nanoTime] base); matches its event. */
    fun scheduled(id: Long, atNs: Long) {
        val (event, stream) = synchronized(lock) {
            val stream = find(id) ?: return
            if (stream.scheduledNs != null) return
            stream.scheduledNs = atNs
            val event = events.filter { it.matched == null && inWindow(atNs, it.atNs) }
                .minByOrNull { kotlin.math.abs(atNs - it.atNs) } ?: return
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

    /** Whether stream [id] may play: owned by a live announcement, not yet owned, or never to play. */
    fun ownership(id: Long): Ownership = synchronized(lock) {
        val stream = find(id) ?: return Ownership.DROPPED
        if (!stream.dropped && stream.owner == null) {
            val at = stream.scheduledNs
            if (at != null && nanoTime() - at > beforeNs + UNCLAIMED_MARGIN_NS) stream.dropped = true
        }
        when {
            stream.dropped -> Ownership.DROPPED
            stream.owner != null -> Ownership.OWNED
            else -> Ownership.PENDING
        }
    }

    /** Whether stream [id] will never play. */
    fun dropped(id: Long): Boolean = ownership(id) == Ownership.DROPPED

    override suspend fun play(eventAtNs: Long) {
        val event = Event(eventAtNs)
        val found = synchronized(lock) {
            events.addLast(event)
            while (events.size > MAX_RECENT) events.removeFirst()
            val stream = streams.filter { candidate ->
                candidate.owner == null && !candidate.dropped &&
                    candidate.scheduledNs?.let { inWindow(it, eventAtNs) } == true
            }.minByOrNull { kotlin.math.abs(it.scheduledNs!! - eventAtNs) }
            if (stream != null) {
                event.matched = stream
                stream.owner = event
            }
            stream
        }
        if (found != null) return awaitEnd(found)
        val stream = try {
            withTimeout(startTimeoutMs - (nanoTime() - eventAtNs) / 1_000_000L) { event.stream.await() }
        } catch (late: TimeoutCancellationException) {
            if (abandon(event)) throw VoiceStreamMissing()
            event.stream.await() // It was matched as the wait ran out: it is this announcement's.
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

    /** True when [event] had no stream and now gives up the one it would match; false when it has one. */
    private fun abandon(event: Event): Boolean = synchronized(lock) {
        val unmatched = event.matched == null
        if (unmatched) event.abandoned = true
        unmatched
    }

    private fun drop(stream: Stream) {
        stream.dropped = true
    }

    private fun find(id: Long): Stream? = streams.firstOrNull { it.id == id }

    private fun inWindow(scheduledNs: Long, eventAtNs: Long): Boolean =
        scheduledNs - eventAtNs >= -beforeNs && scheduledNs - eventAtNs <= afterNs

    private companion object {
        /**
         * A stream scheduled up to 2 s before the event still belongs to it (the contract's claim window;
         * it absorbs a late event and clock-mapping error).
         */
        const val CLAIM_BEFORE_NS = 2_000_000_000L

        /**
         * Panel Assistant schedules the first sample at its send-ahead after starting the stream, which
         * it does just before sending the event: aiosendspin floors that at the player's 500 ms minimum
         * buffer (sendspin-cpp `DEFAULT_MIN_BUFFER_MS`), so a stream is normally scheduled about 0.5 s
         * after its event arrives. 1.5 s leaves a second for group setup and a slow event loop.
         */
        const val CLAIM_AFTER_NS = 1_500_000_000L
        const val START_TIMEOUT_MS = 3_000L

        /** An event's claim may run a moment after the event arrived; an unowned stream waits this long more. */
        const val UNCLAIMED_MARGIN_NS = 1_000_000_000L
        const val MAX_RECENT = 16
    }
}

/** The voice stream as an announcement lane plays it. */
internal fun interface VoiceStreamSource {
    /** Play the stream for an event that arrived at [eventAtNs]; see [VoiceStreamClaims]. */
    suspend fun play(eventAtNs: Long)
}

/** No stream matched a streamed announcement in time; a stream matching it later is dropped. */
internal class VoiceStreamMissing : java.io.IOException("no voice stream started for the announcement")

/**
 * One streamed announcement as the playback coordinator runs it. When no stream can be claimed in time
 * (the panel's player is not connected), it plays the event's [fallbackUrls] in order instead, as the
 * URL path does: preannouncement first, then the message.
 */
internal class StreamedSpeechRun(
    private val source: VoiceStreamSource,
    private val eventAtNs: Long,
    private val fallbackUrls: List<String> = emptyList(),
    private val fallback: (String) -> AudioPlaybackRun = { throw UnsupportedOperationException("no URL playback") },
) : AudioPlaybackRun {
    @Volatile private var current: AudioPlaybackRun? = null

    override suspend fun execute() {
        try {
            return source.play(eventAtNs)
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
