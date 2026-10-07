package io.panelassistant.android.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The streams Panel Assistant sends to the panel's voice player, and which announcement each one is.
 *
 * Panel Assistant is the only server the player talks to, and it streams only what it is about to tell the
 * panel to play, so every stream plays as it arrives. A streamed announcement or reply arrives as an event
 * on the session, a separate connection from the stream: the stream may start just before the event, or
 * after it. [play] therefore claims the stream that started at most [windowNs] before the event, or else the
 * next one to start, and returns once that stream has ended and its audio has played out. Cancelling [play]
 * drops the claimed stream: its audio stops at once and the rest is discarded.
 *
 * An announcement that stops waiting before its stream starts (it fell back to the URL, or was cancelled)
 * still owns that stream: the next stream to start is dropped on arrival, so it never plays over or after
 * the URL speech. An event arriving at least the start timeout after the abandoned one's ends that
 * ownership, since by then the abandoned stream may never come; a stream for an announcement cancelled
 * sooner by a newer one still arrives first, and is still dropped. A stream nobody claims within
 * [windowNs] plus a margin can no longer be claimed, and is dropped too.
 *
 * The player reports [started] and [ended] (after playing out) and asks [dropped] before writing each
 * buffer. Thread-safe.
 */
internal class VoiceStreamClaims(
    private val nanoTime: () -> Long = System::nanoTime,
    private val windowNs: Long = CLAIM_WINDOW_NS,
    /** How long after the event its stream may still start; after that the URL path plays instead. */
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
) : VoiceStreamSource {
    private class Stream(val id: Long, val startedAtNs: Long) {
        val ended = CompletableDeferred<Unit>()
        var claimed = false

        @Volatile var dropped = false
    }

    private class Waiter(val eventAtNs: Long) {
        val stream = CompletableDeferred<Stream>()

        @Volatile var assigned: Stream? = null

        /** It stopped waiting; the stream it would have taken is dropped. Guarded by the claims' lock. */
        var abandoned = false
    }

    private val lock = Any()
    private var nextId = 0L
    private val recent = ArrayDeque<Stream>()
    private val waiters = ArrayDeque<Waiter>()

    /** A stream started now; returns its id. The oldest waiting claim takes it. */
    fun started(): Long = synchronized(lock) {
        val stream = Stream(++nextId, nanoTime())
        recent.addLast(stream)
        while (recent.size > MAX_RECENT) recent.removeFirst()
        val waiter = waiters.removeFirstOrNull()
        if (waiter != null) {
            stream.claimed = true
            if (waiter.abandoned) {
                stream.dropped = true
            } else {
                waiter.assigned = stream
                waiter.stream.complete(stream)
            }
        }
        stream.id
    }

    /** Stream [id] ended and everything written for it has played out (or it was dropped). */
    fun ended(id: Long) {
        synchronized(lock) { recent.firstOrNull { it.id == id } }?.ended?.complete(Unit)
    }

    /** Whether stream [id] is dropped: its announcement was cancelled or gave up on it, or nobody can claim it. */
    fun dropped(id: Long): Boolean = synchronized(lock) {
        val stream = recent.firstOrNull { it.id == id } ?: return false
        if (!stream.claimed && !stream.dropped && nanoTime() - stream.startedAtNs > windowNs + UNCLAIMED_MARGIN_NS) {
            stream.dropped = true
        }
        stream.dropped
    }

    override suspend fun play(eventAtNs: Long) {
        val waiter = Waiter(eventAtNs)
        val recentEnough = synchronized(lock) {
            waiters.removeAll { it.abandoned && eventAtNs - it.eventAtNs >= startTimeoutMs * 1_000_000L }
            val found = recent.firstOrNull {
                !it.claimed && !it.dropped && it.startedAtNs - (eventAtNs - windowNs) >= 0L
            }
            if (found != null) found.claimed = true else waiters.addLast(waiter)
            found
        }
        if (recentEnough != null) return awaitEnd(recentEnough)
        val stream = try {
            withTimeout(startTimeoutMs - (nanoTime() - eventAtNs) / 1_000_000L) { waiter.stream.await() }
        } catch (late: TimeoutCancellationException) {
            if (abandon(waiter)) throw VoiceStreamMissing()
            waiter.stream.await() // It started as the wait ran out: it is this announcement's.
        } catch (cancelled: CancellationException) {
            if (!abandon(waiter)) waiter.assigned?.let(::drop)
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

    /**
     * True when [waiter] was still waiting and now gives its stream up (dropped when it starts); false when
     * a stream already went to it.
     */
    private fun abandon(waiter: Waiter): Boolean = synchronized(lock) {
        val waiting = waiter in waiters
        if (waiting) waiter.abandoned = true
        waiting
    }

    private fun drop(stream: Stream) {
        stream.dropped = true
    }

    private companion object {
        const val CLAIM_WINDOW_NS = 2_000_000_000L
        const val START_TIMEOUT_MS = 3_000L

        /** An event's claim may run a moment after the event arrived; an unclaimed stream waits this long more. */
        const val UNCLAIMED_MARGIN_NS = 1_000_000_000L
        const val MAX_RECENT = 8
    }
}

/** The voice stream as an announcement lane plays it. */
internal fun interface VoiceStreamSource {
    /** Play the stream for an event that arrived at [eventAtNs]; see [VoiceStreamClaims]. */
    suspend fun play(eventAtNs: Long)
}

/** No stream started for a streamed announcement in time; a stream starting later is dropped. */
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
