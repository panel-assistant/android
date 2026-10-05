package io.panelassistant.android.audio

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pre-amplification for the audio a panel streams to Home Assistant.
 *
 * A wall panel hears from across the room, and these panels offer nothing to help. Their audio HAL ships
 * `libaudiopreprocessing.so` but registers no `pre_processing` block, so `AcousticEchoCanceler`,
 * `NoiseSuppressor` and `AutomaticGainControl` all answer `isAvailable() == false`. Whatever leaves the
 * microphone is what Home Assistant transcribes.
 *
 * Wake-word detection does not need this and must not receive it. The microWakeWord frontend runs PCAN
 * adaptive gain and spectral noise reduction in its own feature domain, so it already tolerates a quiet,
 * distant caller; feeding it a scaled signal only moves it away from the levels the models were trained
 * on, and clipping would cost detections outright. Speech-to-text has no such adaptation, which is why
 * the two halves of the same utterance need different treatment: the wake word fires, and then the
 * command it precedes arrives too quiet to transcribe. So gain is applied to the pipeline audio alone.
 */
object MicrophoneGain {
    /** The usable range in decibels. Wider than this is a broken microphone, not a gain problem. */
    const val MIN_DB = -24
    const val MAX_DB = 24

    /** Linear amplitude factor for [db]. Out-of-range values are clamped rather than rejected. */
    fun factorFor(db: Int): Double =
        if (db == 0) 1.0 else 10.0.pow(db.coerceIn(MIN_DB, MAX_DB) / 20.0)

    /**
     * Scale the first [count] samples of [samples] by [factor], in place.
     *
     * Saturates at the 16-bit bounds. Letting a scaled sample wrap would turn the loudest part of an
     * utterance into full-scale noise of the opposite sign — worse than the quiet signal being fixed,
     * and worst exactly when someone raises their voice to be heard.
     */
    fun applyInPlace(samples: ShortArray, count: Int, factor: Double) {
        if (factor == 1.0) return
        val n = count.coerceAtMost(samples.size)
        for (i in 0 until n) {
            val scaled = (samples[i] * factor).roundToInt()
            samples[i] = when {
                scaled > Short.MAX_VALUE.toInt() -> Short.MAX_VALUE
                scaled < Short.MIN_VALUE.toInt() -> Short.MIN_VALUE
                else -> scaled.toShort()
            }
        }
    }
}

/**
 * A [PcmConsumer] that amplifies each frame before passing it on.
 *
 * At 0 dB it forwards the frame untouched, so the default costs nothing on the delivery thread. The
 * frame's array belongs to the consumer once delivered, so scaling happens in place rather than copying
 * 160 samples every 10 ms.
 */
class GainStage(private val downstream: PcmConsumer, gainDb: Int) : PcmConsumer {
    private val factor = MicrophoneGain.factorFor(gainDb)

    override fun onFrame(frame: PcmFrame) {
        MicrophoneGain.applyInPlace(frame.samples, frame.samples.size, factor)
        downstream.onFrame(frame)
    }

    override fun onDropped(count: Int) = downstream.onDropped(count)
}
