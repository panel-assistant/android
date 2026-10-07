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
 * The player reports [started] and [ended] (after playing out) and asks [dropped] before writing each
 * buffer. Thread-safe.
 */
internal class VoiceStreamClaims(
    private val nanoTime: () -> Long = System::nanoTime,
    private val windowNs: Long = CLAIM_WINDOW_NS,
    /** How long an event waits for its stream to start before the announcement fails. */
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
) : VoiceStreamSource {
    private class Stream(val id: Long, val startedAtNs: Long) {
        val ended = CompletableDeferred<Unit>()
        var claimed = false

        @Volatile var dropped = false
    }

    private class Waiter {
        val stream = CompletableDeferred<Stream>()

        @Volatile var assigned: Stream? = null
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
            waiter.assigned = stream
            waiter.stream.complete(stream)
        }
        stream.id
    }

    /** Stream [id] ended and everything written for it has played out (or it was dropped). */
    fun ended(id: Long) {
        synchronized(lock) { recent.firstOrNull { it.id == id } }?.ended?.complete(Unit)
    }

    /** Whether stream [id] was dropped by the cancellation of the announcement that claimed it. */
    fun dropped(id: Long): Boolean = synchronized(lock) { recent.firstOrNull { it.id == id }?.dropped == true }

    override suspend fun play(eventAtNs: Long) {
        val waiter = Waiter()
        val recentEnough = synchronized(lock) {
            val found = recent.firstOrNull {
                !it.claimed && !it.dropped && it.startedAtNs - (eventAtNs - windowNs) >= 0L
            }
            if (found != null) found.claimed = true else waiters.addLast(waiter)
            found
        }
        if (recentEnough != null) return awaitEnd(recentEnough)
        val stream = try {
            withTimeout(startTimeoutMs) { waiter.stream.await() }
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

    /** True when [waiter] was still waiting and is now withdrawn; false when a stream already went to it. */
    private fun abandon(waiter: Waiter): Boolean = synchronized(lock) { waiters.remove(waiter) }

    private fun drop(stream: Stream) {
        stream.dropped = true
    }

    private companion object {
        const val CLAIM_WINDOW_NS = 2_000_000_000L
        const val START_TIMEOUT_MS = 10_000L
        const val MAX_RECENT = 8
    }
}

/** The voice stream as an announcement lane plays it. */
internal fun interface VoiceStreamSource {
    /** Play the stream for an event that arrived at [eventAtNs]; see [VoiceStreamClaims]. */
    suspend fun play(eventAtNs: Long)
}

/** No stream started for a streamed announcement. */
internal class VoiceStreamMissing : java.io.IOException("no voice stream started for the announcement")

/** One streamed announcement as the playback coordinator runs it. */
internal class StreamedSpeechRun(private val source: VoiceStreamSource, private val eventAtNs: Long) : AudioPlaybackRun {
    override suspend fun execute() = source.play(eventAtNs)

    /** The coordinator cancels the run's job as well, and that cancellation drops the stream. */
    override fun cancel() = Unit
}
