package io.github.maxlyth.hapaneld.assist

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import java.io.File

/**
 * How the panel shows it is listening: the Voice Preview Edition's wake sound, and a ripple over the
 * dashboard while ha-paneld's own dashboard is on screen. A panel showing another app's dashboard still
 * sounds the chime.
 */
internal object VoiceAttention {
    private const val TAG = "ha-paneld/voice"
    private const val WAKE_SOUND = "voice/wake_word_triggered.flac"

    /** Set by the dashboard while it is resumed, so the ripple is drawn in its own window. */
    @Volatile
    var ripple: (() -> Unit)? = null

    private var pool: SoundPool? = null

    @Volatile
    private var wakeSound = 0

    /** Load the sound once; a panel that cannot is silent rather than broken. */
    @Synchronized
    fun prepare(context: Context) {
        if (pool != null) return
        val built = SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        pool = built
        try {
            // The asset may be stored compressed in the APK, which SoundPool cannot read in place.
            val copy = File(context.cacheDir, "voice-wake.flac")
            context.assets.open(WAKE_SOUND).use { input -> copy.outputStream().use { input.copyTo(it) } }
            wakeSound = built.load(copy.path, 1)
        } catch (failure: Exception) {
            Log.w(TAG, "wake sound unavailable: ${failure.javaClass.simpleName}")
        }
    }

    /** The panel has started listening: chime, and ripple if the dashboard is showing. */
    fun cue() {
        val sound = wakeSound
        if (sound != 0) pool?.play(sound, 1f, 1f, 1, 0, 1f)
        ripple?.invoke()
    }
}
