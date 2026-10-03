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
    private const val CHIME_TAIL_NS = 100_000_000L

    /** Before Home Assistant has assigned any, and for a wake word it has not coloured. */
    const val DEFAULT_COLOR = 0xFF00FF88.toInt()

    /** Set by the dashboard while it is resumed, so the ripple is drawn in its own window. */
    @Volatile
    var ripple: (() -> Unit)? = null

    /** Set by the dashboard while it is resumed: tint the edges while the assistant is attending. */
    @Volatile
    var listening: ((Boolean) -> Unit)? = null

    @Volatile
    var attending = false
        private set

    /** Home Assistant's colour for each wake word's pipeline; every panel is sent the same ones. */
    @Volatile
    var colors: Map<String, Int> = emptyMap()

    /** The colour of the conversation now on screen: its wake word's pipeline's. */
    @Volatile
    var color: Int = DEFAULT_COLOR
        private set

    /** The assistant's phase changed; the dashboard shows whether it is still attending. */
    fun phase(state: VoiceState) {
        val now = state.inTurn
        if (now == attending) return
        attending = now
        listening?.invoke(now)
    }

    private var pool: SoundPool? = null

    @Volatile
    private var wakeSound = 0

    @Volatile
    private var wakeSoundNs = 0L

    /** When the last chime started and when it ends, `System.nanoTime()` base; empty before any. */
    @Volatile
    var chime: LongRange = LongRange.EMPTY
        private set

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
            wakeSoundNs = android.media.MediaMetadataRetriever().run {
                try {
                    setDataSource(copy.path)
                    (extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1_000_000L
                } finally {
                    release()
                }
            }
        } catch (failure: Exception) {
            Log.w(TAG, "wake sound unavailable: ${failure.javaClass.simpleName}")
        }
    }

    /** The panel has started listening for [wakeWordId]'s pipeline: chime, and ripple if the dashboard is showing. */
    fun cue(wakeWordId: String?) {
        color = wakeWordId?.let { colors[it] } ?: DEFAULT_COLOR
        val sound = wakeSound
        if (sound != 0 && pool?.play(sound, 1f, 1f, 1, 0, 1f) != 0) {
            val start = System.nanoTime()
            // A little past the sound itself: the room carries it a moment longer than the file.
            chime = start..(start + wakeSoundNs + CHIME_TAIL_NS)
        }
        ripple?.invoke()
    }
}
