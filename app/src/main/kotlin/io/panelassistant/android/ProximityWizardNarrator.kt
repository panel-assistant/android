package io.panelassistant.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Optional Home Assistant narration for the native proximity journey. */
internal class ProximityWizardNarrator(
    private val speak: suspend (String, String, (Long) -> Unit) -> Unit,
    private val stopPlayback: (Long) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private data class Request(
        val sequence: Long,
        val epoch: Long,
        val prompt: String,
        val text: String,
        val localeTag: String,
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var current: Job? = null
    private var currentRequest: Request? = null
    private var pending: Request? = null
    private var semanticPrompt: String? = null
    private var sequence = 0L
    private var epoch = 0L
    private var playbackOwner: Pair<Long, Long>? = null
    private var closed = false

    /** Repeated render polls are silent; the latest new stage waits for the current sentence to finish. */
    fun narrate(prompt: String, text: String, localeTag: String): Boolean = synchronized(lock) {
        if (closed || prompt.isBlank() || text.isBlank() || localeTag.isBlank() || prompt == semanticPrompt) return false
        semanticPrompt = prompt
        val request = Request(++sequence, epoch, prompt, text, localeTag)
        if (current?.isActive == true) {
            pending = request
        } else {
            startLocked(request)
        }
        true
    }

    private fun startLocked(request: Request) {
        currentRequest = request
        current = scope.launch {
            try {
                speak(request.text, request.localeTag) { audioGeneration ->
                    synchronized(lock) {
                        if (closed || request.epoch != epoch || currentRequest?.sequence != request.sequence) {
                            runCatching { stopPlayback(audioGeneration) }
                        } else {
                            playbackOwner = request.sequence to audioGeneration
                        }
                    }
                }
                delay(INTER_PROMPT_GAP_MS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Narration is guidance. Home Assistant or playback failure never gates calibration.
            } finally {
                synchronized(lock) {
                    if (currentRequest?.sequence == request.sequence) {
                        if (playbackOwner?.first == request.sequence) playbackOwner = null
                        current = null
                        currentRequest = null
                        if (!closed && request.epoch == epoch) {
                            pending?.also { next ->
                                pending = null
                                startLocked(next)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Leaving the Activity silences it and lets a later visible presentation speak afresh. */
    fun stop() = synchronized(lock) {
        semanticPrompt = null
        pending = null
        epoch++
        stopCurrentLocked()
        current?.cancel()
        current = null
        currentRequest = null
    }

    private fun stopCurrentLocked() {
        playbackOwner?.second?.let { audioGeneration -> runCatching { stopPlayback(audioGeneration) } }
        playbackOwner = null
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            semanticPrompt = null
            pending = null
            epoch++
            stopCurrentLocked()
            current?.cancel()
            current = null
            currentRequest = null
            scope.cancel()
        }
    }

    private companion object {
        const val INTER_PROMPT_GAP_MS = 450L
    }
}
