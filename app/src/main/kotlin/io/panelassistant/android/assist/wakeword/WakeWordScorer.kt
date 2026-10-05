package io.panelassistant.android.assist.wakeword

/**
 * Scores a stream of 16 kHz mono PCM16 audio for one wake-word model. Feeding is sequential and
 * stateful; [reset] drops the stream context. Not thread-safe: one caller thread per instance.
 */
interface WakeWordScorer : AutoCloseable {
    /**
     * Feed [count] samples from the front of [samples]. Returns the probability (0..255) of the
     * last inference this call completed, or [NO_INFERENCE] when the model's stride is not yet full.
     */
    fun score(samples: ShortArray, count: Int = samples.size): Int

    /** Forget the stream: frontend noise estimates, stride accumulation and streaming variables. */
    fun reset()

    companion object {
        const val NO_INFERENCE = -1
        const val MAX_PROBABILITY = 255
    }
}
