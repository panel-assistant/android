package io.github.maxlyth.hapaneld.assist.wakeword

import io.github.maxlyth.hapaneld.audio.PcmConsumer
import io.github.maxlyth.hapaneld.audio.PcmFrame

/** One wake word heard: which model, its phrase, the window-mean probability (0..1) and when. */
data class WakeWordHit(
    val modelId: String,
    val phrase: String,
    val probability: Float,
    /** Capture timestamp of the frame that completed the detection, `System.nanoTime()` base. */
    val timestampNs: Long,
)

/** A model ready to score: its manifest plus a scorer bound to it. */
class LoadedWakeWordModel(val config: MicroWakeWordModelConfig, val scorer: WakeWordScorer) : AutoCloseable {
    override fun close() = scorer.close()
}

/**
 * Runs up to [maxActive] wake-word models over the shared microphone stream and reports hits.
 *
 * Detection policy (per model, mirroring the ESPHome `micro_wake_word` component):
 * - every inference's probability enters a ring of [MicroWakeWordModelConfig.slidingWindowSize];
 *   the model fires when the ring mean is at or above [MicroWakeWordModelConfig.probabilityCutoff]
 * - the first [warmupInferences] inferences after load or reset never fire, because the frontend's
 *   noise and gain estimates are still settling
 * - after a hit the ring is cleared and the model stays silent for [cooldownChunks] delivered frames
 *
 * Frames are scored on the delivering thread; [onFrame] does the inference inline and returns.
 * Nothing here touches the main looper.
 */
class WakeWordDetector(
    models: List<LoadedWakeWordModel>,
    private val listener: (WakeWordHit) -> Unit,
    maxActive: Int = DEFAULT_MAX_ACTIVE,
    private val cooldownChunks: Int = DEFAULT_COOLDOWN_CHUNKS,
    private val warmupInferences: Int = DEFAULT_WARMUP_INFERENCES,
) : PcmConsumer, AutoCloseable {

    /** True once every loaded model has stopped: the listener is armed but can no longer hear. */
    val exhausted: Boolean get() = slots.isNotEmpty() && slots.all { it.model.scorer.let { s -> s is NativeMicroWakeWord && s.failed } }

    private class Slot(val model: LoadedWakeWordModel) {
        val window = IntArray(model.config.slidingWindowSize)
        var next = 0
        var inferences = 0L
        var cooldownRemaining = 0

        fun clearWindow() {
            window.fill(0)
            next = 0
        }

        fun rearm() {
            clearWindow()
            inferences = 0
        }
    }

    private val slots: List<Slot>

    init {
        require(maxActive >= 1) { "maxActive must be at least 1" }
        require(models.size <= maxActive) { "${models.size} models exceed maxActive=$maxActive" }
        require(cooldownChunks >= 0) { "cooldownChunks must not be negative" }
        require(warmupInferences >= 0) { "warmupInferences must not be negative" }
        slots = models.map(::Slot)
    }

    val modelIds: List<String> get() = slots.map { it.model.config.id }

    override fun onFrame(frame: PcmFrame) {
        for (slot in slots) {
            // Scoring continues through the cooldown so the window is live again when it ends;
            // only firing is suppressed, for exactly cooldownChunks frames after a hit.
            val coolingDown = slot.cooldownRemaining > 0
            if (coolingDown) slot.cooldownRemaining--
            val probability = slot.model.scorer.score(frame.samples, frame.samples.size)
            if (probability < 0) continue
            slot.inferences++
            slot.window[slot.next] = probability
            slot.next = (slot.next + 1) % slot.window.size
            if (slot.inferences <= warmupInferences) continue
            if (coolingDown) continue
            val mean = slot.window.sum().toFloat() / (slot.window.size * WakeWordScorer.MAX_PROBABILITY)
            if (mean >= slot.model.config.probabilityCutoff) {
                slot.clearWindow()
                slot.cooldownRemaining = cooldownChunks
                listener(WakeWordHit(slot.model.config.id, slot.model.config.wakeWord, mean, frame.timestampNs))
            }
        }
    }

    /** A gap in the stream invalidates every model's streaming context; start each afresh. */
    override fun onDropped(count: Int) {
        for (slot in slots) {
            slot.model.scorer.reset()
            slot.rearm()
        }
    }

    override fun close() {
        slots.forEach { it.model.close() }
    }

    companion object {
        const val DEFAULT_MAX_ACTIVE = 2

        /** 2 s of 10 ms frames. */
        const val DEFAULT_COOLDOWN_CHUNKS = 200

        /** Matches ESPHome's MIN_SLICES_BEFORE_DETECTION. */
        const val DEFAULT_WARMUP_INFERENCES = 100
    }
}
