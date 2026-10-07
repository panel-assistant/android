package io.panelassistant.android.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

internal interface AudioPlaybackRun {
    suspend fun execute()
    fun cancel()
}

internal fun interface AudioPlaybackRunFactory {
    fun create(url: String): AudioPlaybackRun
    fun createSpeech(url: String): AudioPlaybackRun = create(url)

    /** Speech Panel Assistant streams, the stream [cue] names ([VoiceStreamClaims]). */
    fun createStream(cue: StreamCue, fallbackUrls: List<String> = emptyList()): AudioPlaybackRun =
        throw UnsupportedOperationException("this panel has no voice stream")
}

/** Owns one latest-wins announcement lane for the service lifetime. */
internal class AudioPlaybackCoordinator(
    private val factory: AudioPlaybackRunFactory,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onFailure: (Throwable) -> Unit = {},
    /**
     * The lane started (true) or stopped (false) being busy: a request queued or playing. Called under
     * this coordinator's monitor, so it must only hand the change on.
     */
    private val onBusyChanged: (Boolean) -> Unit = {},
) {
    enum class State { IDLE, QUEUED, ACTIVE, FAILED, CLOSED }

    data class Snapshot(
        val state: State,
        val generation: Long,
        val error: String? = null,
    ) {
        fun statusText(): String = when (state) {
            State.IDLE -> "idle"
            State.QUEUED -> "queued"
            State.ACTIVE -> "active"
            State.FAILED -> "failed" + error?.let { " · $it" }.orEmpty()
            State.CLOSED -> "closed"
        }
    }

    private data class Request(
        val generation: Long,
        val url: String,
        val speech: Boolean,
        val stream: StreamCue? = null,
        val fallbackUrls: List<String> = emptyList(),
    )
    private class Active(val generation: Long, val run: AudioPlaybackRun, val job: Job) {
        private val cancelled = AtomicBoolean(false)
        fun cancel() {
            if (cancelled.compareAndSet(false, true)) {
                run.cancel()
                job.cancel()
            }
        }
    }

    private val owner = SupervisorJob()
    private val scope = CoroutineScope(owner + dispatcher)
    private val requests = Channel<Request>(Channel.CONFLATED)
    private var generation = 0L
    private var closed = false
    private var snapshot = Snapshot(State.IDLE, 0L)
        set(next) {
            val wasBusy = field.state == State.QUEUED || field.state == State.ACTIVE
            field = next
            val busy = next.state == State.QUEUED || next.state == State.ACTIVE
            if (busy != wasBusy) runCatching { onBusyChanged(busy) }
        }
    @Volatile private var active: Active? = null
    private val worker = scope.launch { consume() }

    /** Accept an announcement for this service lifetime. A newer accepted request replaces older work. */
    @Synchronized
    fun submit(url: String): Boolean = submitForGeneration(url) != null

    /**
     * As [submit], but returns the generation the accepted announcement was given, or null when
     * admission is closed. A caller that must watch its own announcement has to learn the generation
     * in the same critical section that assigned it: reading [snapshot] afterwards can return a
     * later announcement's generation and leave the caller watching work that is not its own.
     */
    @Synchronized
    fun submitForGeneration(url: String, speech: Boolean = false): Long? = admit(url, speech, null)

    /**
     * As [submitForGeneration], for speech Panel Assistant streams: the run plays the voice stream [cue]
     * names and completes when that stream has played out. It takes
     * the lane like any announcement, so a newer request replaces it and cancelling it drops its audio.
     */
    @Synchronized
    fun submitStreamForGeneration(cue: StreamCue, fallbackUrls: List<String> = emptyList()): Long? =
        admit("", true, cue, fallbackUrls)

    private fun admit(url: String, speech: Boolean, stream: StreamCue?, fallbackUrls: List<String> = emptyList()): Long? {
        if (closed) return null
        val request = Request(++generation, url, speech, stream, fallbackUrls)
        snapshot = Snapshot(State.QUEUED, request.generation)
        if (requests.trySend(request).isSuccess) return request.generation
        closed = true
        snapshot = Snapshot(State.CLOSED, generation)
        return null
    }

    /** Close admission immediately so an HTTP request cannot receive a false acceptance during teardown. */
    @Synchronized
    fun closeAdmission() {
        if (closed) return
        closed = true
        snapshot = Snapshot(State.CLOSED, generation)
        requests.close()
    }

    /** Trigger current resource cancellation without waiting; used from the Android main thread before teardown blocks it. */
    fun cancelCurrent() {
        active?.cancel()
    }

    /** Cancel only the queued or active announcement that was assigned [expectedGeneration]. */
    @Synchronized
    fun cancelGeneration(expectedGeneration: Long): Boolean {
        if (snapshot.generation != expectedGeneration) return false
        return when (snapshot.state) {
            State.QUEUED -> {
                snapshot = Snapshot(State.IDLE, expectedGeneration)
                true
            }
            State.ACTIVE -> {
                val current = active?.takeIf { it.generation == expectedGeneration } ?: return false
                snapshot = Snapshot(State.IDLE, expectedGeneration)
                current.cancel()
                true
            }
            State.IDLE, State.FAILED, State.CLOSED -> false
        }
    }

    /** Cancel the current run and wait up to [timeoutMs] for its owned resources to finish cleanup. */
    suspend fun close(timeoutMs: Long): Boolean {
        require(timeoutMs > 0L)
        closeAdmission()
        cancelCurrent()
        owner.cancel()
        return withTimeoutOrNull(timeoutMs) {
            owner.join()
            true
        } ?: false
    }

    @Synchronized
    fun snapshot(): Snapshot = snapshot

    private suspend fun consume() {
        try {
            while (true) {
                var request = requests.receiveCatching().getOrNull() ?: break
                while (true) {
                    val newer = requests.tryReceive().getOrNull() ?: break
                    request = newer
                }
                val previous = active
                if (previous != null) {
                    previous.cancel()
                    previous.job.cancelAndJoin()
                    clearActive(previous)
                }
                if (isClosed()) break
                if (!isPending(request.generation)) continue

                val run = try {
                    when {
                        request.stream != null -> factory.createStream(request.stream, request.fallbackUrls)
                        request.speech -> factory.createSpeech(request.url)
                        else -> factory.create(request.url)
                    }
                } catch (error: Throwable) {
                    fail(request.generation, error)
                    continue
                }
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        run.execute()
                        complete(request.generation)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        fail(request.generation, error)
                    }
                }
                val current = Active(request.generation, run, job)
                if (!publishActive(request.generation, current)) {
                    current.cancel()
                    current.job.cancel()
                    if (isClosed()) break else continue
                }
                job.invokeOnCompletion {
                    clearActive(current)
                }
                job.start()
            }
        } finally {
            withContext(NonCancellable) {
                val current = synchronized(this@AudioPlaybackCoordinator) {
                    active.also { active = null }
                }
                if (current != null) {
                    current.cancel()
                    current.job.cancelAndJoin()
                }
            }
        }
    }

    @Synchronized
    private fun isClosed(): Boolean = closed

    @Synchronized
    private fun isPending(requestGeneration: Long): Boolean =
        !closed && snapshot.generation == requestGeneration && snapshot.state == State.QUEUED

    @Synchronized
    private fun publishActive(requestGeneration: Long, current: Active): Boolean {
        if (closed || snapshot.generation != requestGeneration || snapshot.state != State.QUEUED) return false
        active = current
        snapshot = Snapshot(State.ACTIVE, requestGeneration)
        return true
    }

    @Synchronized
    private fun clearActive(expected: Active) {
        if (active === expected) active = null
    }

    @Synchronized
    private fun complete(requestGeneration: Long) {
        if (!closed && snapshot.generation == requestGeneration) snapshot = Snapshot(State.IDLE, requestGeneration)
    }

    private fun fail(requestGeneration: Long, error: Throwable) {
        runCatching { onFailure(error) }
        val detail = error.javaClass.simpleName.takeIf { it.isNotBlank() }?.take(MAX_ERROR_CHARS) ?: "error"
        synchronized(this) {
            if (!closed && snapshot.generation == requestGeneration) snapshot = Snapshot(State.FAILED, requestGeneration, detail)
        }
    }

    private companion object {
        const val MAX_ERROR_CHARS = 160
    }
}
