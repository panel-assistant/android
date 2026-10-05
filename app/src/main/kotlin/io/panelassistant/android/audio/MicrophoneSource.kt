package io.panelassistant.android.audio

import kotlinx.coroutines.flow.StateFlow

/**
 * Shared microphone contract.
 *
 * The panel has one microphone and Android grants one capture client per device, so no feature
 * opens its own `AudioRecord`. One [MicrophoneSource] owns the capture and fans frames out to
 * every consumer that holds a [MicLease]. The capture loop never blocks on a consumer: each lease
 * has its own bounded queue, a slow consumer loses its oldest frames and is told through
 * [PcmConsumer.onDropped], and capture cadence is unaffected by what any consumer does.
 *
 * The canonical stream is 16 kHz mono signed 16-bit PCM in [PcmFrame.samples]; a consumer that
 * needs another rate resamples on its own side. The source opens the hardware only while at least
 * one lease is held and closes it when the last lease is released, so the platform's microphone
 * privacy indicator reflects real use.
 */
interface MicrophoneSource {
    /**
     * Acquire a lease and start receiving frames. [priority] orders holders when purposes
     * compete: a higher-priority lease may pause lower ones through [MicLease.pause]; the source
     * itself never silently drops a holder. [queueFrames] bounds the per-consumer queue in frames
     * (10 ms each at the canonical format).
     */
    fun lease(
        purpose: MicPurpose,
        priority: Int = purpose.defaultPriority,
        consumer: PcmConsumer,
        queueFrames: Int = DEFAULT_QUEUE_FRAMES,
    ): MicLease

    /** Observable capture state for status surfaces and tests. */
    val state: StateFlow<MicState>

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNELS = 1
        const val FRAME_MS = 10
        const val SAMPLES_PER_FRAME = SAMPLE_RATE_HZ * FRAME_MS / 1000
        const val DEFAULT_QUEUE_FRAMES = 50
    }
}

/** One frame of canonical PCM. The array is owned by the consumer once delivered. */
class PcmFrame(
    val samples: ShortArray,
    val sampleRate: Int = MicrophoneSource.SAMPLE_RATE_HZ,
    /** Monotonic capture timestamp of the first sample, `System.nanoTime()` base. */
    val timestampNs: Long,
)

/** Receives frames on the source's delivery thread; must return promptly and never block. */
interface PcmConsumer {
    fun onFrame(frame: PcmFrame)

    /** Called once per gap with the number of frames this consumer lost since its last frame. */
    fun onDropped(count: Int) {}
}

/** Why a holder wants the microphone; drives default priority and status reporting. */
enum class MicPurpose(val defaultPriority: Int) {
    /** Always-on wake-word detection; lowest priority, expects to be paused by everything else. */
    WAKE_WORD(10),

    /** An Assist pipeline run after a wake word or tap-to-talk. */
    ASSIST(50),

    /** Live push-to-talk intercom. */
    INTERCOM(70),

    /** Camera-plus-microphone streaming to Home Assistant. */
    STREAM(30),

    /** Guided calibration prompts and level checks. */
    CALIBRATION(60),
}

/** A held claim on the microphone. Closing it is idempotent. */
interface MicLease : AutoCloseable {
    val purpose: MicPurpose
    val priority: Int

    /** True while frames are being delivered to this lease's consumer. */
    val active: Boolean

    /** Stop delivery without releasing the claim; frames captured meanwhile are not queued. */
    fun pause()

    /** Resume delivery after [pause]. */
    fun resume()

    override fun close()
}

/** Snapshot of the shared capture. */
sealed class MicState {
    /** No lease is held; the hardware is closed. */
    object Closed : MicState()

    /** The hardware is open for the listed holders, highest priority first. */
    data class Open(val holders: List<MicHolder>) : MicState()

    /** The hardware could not be opened or failed mid-capture; leases remain and may recover. */
    data class Error(val reason: String, val holders: List<MicHolder>) : MicState()
}

data class MicHolder(val purpose: MicPurpose, val priority: Int, val active: Boolean)
