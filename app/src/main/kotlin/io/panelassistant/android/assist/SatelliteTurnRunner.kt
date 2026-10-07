package io.panelassistant.android.assist

import io.panelassistant.android.audio.PcmConsumer
import io.panelassistant.android.audio.PcmFrame
import io.panelassistant.android.panelassistant.PanelAssistantVoice
import io.panelassistant.android.panelassistant.VoiceTurnEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One conversation turn as a Home Assistant voice satellite: the panel streams what it hears to Panel
 * Assistant, which runs the pipeline the panel named for the wake word, and plays the reply it is handed.
 *
 * The audio attaches before the turn is even requested, so the command said straight after the wake word
 * is buffered rather than lost while Home Assistant names the turn's binary handler; the session already
 * exists, so no connection or sign-in stands between the wake word and the first frame.
 */
internal class SatelliteTurnRunner(
    private val voice: PanelAssistantVoice,
    private val log: (String) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
    /** The panel's own ceiling on listening, whatever Home Assistant's voice detection decides. */
    private val maxListenMs: Long = MAX_LISTEN_MS,
    /** And on the whole turn, reply included. */
    private val maxTurnMs: Long = MAX_TURN_MS,
    /**
     * When the panel's own chime sounds, `System.nanoTime()` base. What the microphone hears then is sent
     * as silence: the stream still starts at the wake word, but neither voice detection nor speech-to-text
     * hears the chime, which once turned a command into nonsense.
     */
    private val chime: () -> LongRange = { LongRange.EMPTY },
) : AssistRunner {
    override suspend fun run(
        request: VoiceTurnRequest,
        attachAudio: (PcmConsumer) -> AutoCloseable,
        playback: AssistPlayback,
    ): AssistOutcome {
        val turn = voice.begin(request.wakeWordId, request.continued)
            ?: return AssistOutcome(error = AssistError(CODE_UNAVAILABLE, "Panel Assistant is not connected"))
        var attachment: AutoCloseable? = attachAudio(measured(withoutChime(turn), request.heardAtNs))
        fun stopListening() {
            attachment?.close()
            attachment = null
            turn.endAudio()
        }
        var continueConversation = false
        var error: AssistError? = null
        try {
            withTimeout(maxTurnMs) {
                var listenDeadline: Long? = nanoTime() + maxListenMs * 1_000_000L
                while (true) {
                    val remainingMs = listenDeadline?.let { (it - nanoTime()) / 1_000_000L }
                    val event = if (remainingMs == null) {
                        turn.events.receive()
                    } else {
                        withTimeoutOrNull(remainingMs.coerceAtLeast(0L)) { turn.events.receive() }
                    }
                    when (event) {
                        null -> {
                            stopListening()
                            listenDeadline = null
                        }
                        VoiceTurnEvent.ListenEnd -> {
                            stopListening()
                            listenDeadline = null
                        }
                        is VoiceTurnEvent.Play -> {
                            stopListening()
                            listenDeadline = null
                            continueConversation = event.continueConversation
                            try {
                                val stream = event.stream
                                if (stream != null) playback.playStream(stream) else playback.play(event.url)
                            } catch (failed: AssistPlaybackException) {
                                error = AssistError(failed.code, failed.message.orEmpty())
                            }
                            // Home Assistant holds the satellite in "responding" until it hears this.
                            voice.played(null)
                        }
                        is VoiceTurnEvent.Failed -> error = error ?: AssistError(event.code, "The pipeline failed")
                        VoiceTurnEvent.End -> break
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            error = AssistError(CODE_TIMEOUT, "The voice turn took too long")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            attachment?.close()
            turn.close()
        }
        return AssistOutcome(continueConversation = continueConversation && error == null, error = error)
    }

    private fun withoutChime(consumer: PcmConsumer): PcmConsumer = object : PcmConsumer {
        override fun onFrame(frame: PcmFrame) {
            consumer.onFrame(
                if (frame.timestampNs in chime()) PcmFrame(ShortArray(frame.samples.size), frame.sampleRate, frame.timestampNs) else frame,
            )
        }

        override fun onDropped(count: Int) = consumer.onDropped(count)
    }

    /** Logs, once, how long after the wake word the first command audio reached the turn. */
    private fun measured(consumer: PcmConsumer, heardAtNs: Long): PcmConsumer {
        if (heardAtNs == 0L) return consumer
        return object : PcmConsumer {
            @Volatile
            private var first = true

            override fun onFrame(frame: PcmFrame) {
                if (first) {
                    first = false
                    log("voice command audio attached ${(frame.timestampNs - heardAtNs) / 1_000_000L} ms after the wake word")
                }
                consumer.onFrame(frame)
            }

            override fun onDropped(count: Int) = consumer.onDropped(count)
        }
    }

    companion object {
        const val CODE_UNAVAILABLE = "unavailable"
        const val CODE_TIMEOUT = "timeout"
        const val MAX_LISTEN_MS = 15_000L
        const val MAX_TURN_MS = 120_000L
    }
}
