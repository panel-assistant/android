package io.panelassistant.android.assist.wakeword

import android.util.Log
import java.nio.ByteBuffer

/**
 * JNI binding to `libhapaneld_mww.so` (`app/src/main/cpp/microwakeword/`): the TFLite Micro
 * frontend plus streaming interpreter that scores one micro-wake-word model. The library is
 * bundled for both supported ARM ABIs; [available] is false when it cannot be loaded, and callers then
 * report wake word detection as unavailable instead of failing.
 *
 * The native methods are registered from `JNI_OnLoad` against this class, so its name and
 * package are part of the binary contract.
 */
class NativeMicroWakeWord private constructor(
    private var handle: Long,
    /** Model input stride in feature frames: one inference per this many 10 ms steps. */
    val stride: Int,
) : WakeWordScorer {

    /**
     * True once the engine reported a result it cannot produce, which is not recoverable for this
     * model: the arena and streaming state are gone. The detector drops a failed model rather than
     * scoring silence through it forever.
     */
    @Volatile
    var failed: Boolean = false
        private set

    // Every native call and the teardown share one monitor. Reading the handle and calling through it
    // must be one step: closing between the two would hand the engine a pointer it has already freed,
    // and the audio thread calls this every ten milliseconds while any thread may tear down.
    @Synchronized
    override fun score(samples: ShortArray, count: Int): Int {
        val h = handle
        if (h == 0L || failed) return WakeWordScorer.NO_INFERENCE
        val result = nativeProcessAudio(h, samples, count)
        if (result < WakeWordScorer.NO_INFERENCE || result > WakeWordScorer.MAX_PROBABILITY) {
            // Out of contract: the engine cannot score this model any more. Say so once and stop,
            // rather than reporting a stream of silence that reads as a working listener.
            failed = true
            Log.w(TAG, "wake-word engine reported an unusable result ($result); this model is stopping")
            return WakeWordScorer.NO_INFERENCE
        }
        return result
    }

    @Synchronized
    override fun reset() {
        val h = handle
        if (h != 0L && !failed) nativeReset(h)
    }

    @Synchronized
    override fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeDestroy(h)
        }
    }

    companion object {
        private const val TAG = "ha-paneld/wake-word-ndk"
        private const val SAMPLE_RATE_HZ = 16_000

        /** True once the native library loaded. Loading is attempted on first use, never at class init. */
        val available: Boolean by lazy {
            try {
                System.loadLibrary("hapaneld_mww")
                true
            } catch (e: Throwable) {
                Log.i(TAG, "libhapaneld_mww not loadable on this ABI — wake word detection unavailable", e)
                false
            }
        }

        /**
         * Create a scorer for [config] from a direct [model] buffer, or null when the library is
         * unavailable or the model is rejected (logged natively).
         */
        fun create(model: ByteBuffer, config: MicroWakeWordModelConfig): NativeMicroWakeWord? {
            if (!available) return null
            if (!model.isDirect) {
                Log.w(TAG, "model buffer for ${config.id} is not direct")
                return null
            }
            val handle = nativeCreate(model, SAMPLE_RATE_HZ, config.featureStepSizeMs, config.tensorArenaSize)
            if (handle == 0L) {
                Log.w(TAG, "native engine rejected model ${config.id}")
                return null
            }
            return NativeMicroWakeWord(handle, nativeStride(handle))
        }

        @JvmStatic
        private external fun nativeCreate(model: ByteBuffer, sampleRate: Int, featureStepSizeMs: Int, tensorArenaBytes: Int): Long

        @JvmStatic
        private external fun nativeProcessAudio(handle: Long, samples: ShortArray, count: Int): Int

        @JvmStatic
        private external fun nativeStride(handle: Long): Int

        @JvmStatic
        private external fun nativeReset(handle: Long)

        @JvmStatic
        private external fun nativeDestroy(handle: Long)
    }
}
