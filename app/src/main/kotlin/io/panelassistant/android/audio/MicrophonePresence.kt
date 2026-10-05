package io.panelassistant.android.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlin.math.sqrt

/**
 * Whether this panel has a microphone, as three states rather than two.
 *
 * Present hardware is offered and the panel proves it: a microphone Android reports is offered even
 * though no profile has proven it, and the panel checks its own capture before listening on it. Only a
 * profile that records a microphone shown not to work, or a panel reporting none, makes it [ABSENT].
 */
enum class MicrophonePresence {
    /** The device profile records capture proven on this hardware; voice arms without a run-time check. */
    PROVEN,

    /** Android reports a built-in microphone no profile has proven; voice arms after a passing [MicrophoneSelfCheck]. */
    UNPROVEN,

    /** No microphone: the profile says so, or Android reports none. Voice is not offered. */
    ABSENT;

    /** Voice and its settings are offered: everything but [ABSENT]. */
    val offered: Boolean get() = this != ABSENT

    val wireValue: String get() = name.lowercase()

    companion object {
        /** [declared] is the profile's `hardware.microphone`; null defers to [deviceReports]. */
        fun resolve(declared: Boolean?, deviceReports: Boolean): MicrophonePresence = when (declared) {
            true -> PROVEN
            false -> ABSENT
            null -> if (deviceReports) UNPROVEN else ABSENT
        }

        /** Presence for [declared] on this device; Android's report is read once, since it describes the board. */
        fun of(declared: Boolean?, context: Context): MicrophonePresence =
            resolve(declared, deviceReports ?: deviceReportsBuiltInMicrophone(context).also { deviceReports = it })

        @Volatile private var deviceReports: Boolean? = null

        /** Whether Android lists a built-in microphone input on this device. */
        fun deviceReportsBuiltInMicrophone(context: Context): Boolean = runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return false
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        }.getOrDefault(false)
    }
}

/**
 * Android's aggregate microphone mute (`AudioManager.isMicrophoneMute`), which a hardware privacy switch
 * such as the Tuya TPA10's sets. Re-read when Android announces a change; [refresh] reports only a
 * change, so a caller acts once per press.
 */
class MicrophoneMute(private val read: () -> Boolean) {
    @Volatile
    var muted: Boolean = false
        private set

    /** Read the mute again; true when it changed. */
    @Synchronized
    fun refresh(): Boolean {
        val now = runCatching(read).getOrDefault(false)
        if (now == muted) return false
        muted = now
        return true
    }
}

/** What the panel's own capture check found. */
enum class MicrophoneCheck {
    /** Not run: the microphone is proven by its profile, absent, or voice has not armed on it yet. */
    NOT_RUN,

    /** The check is capturing now. */
    RUNNING,

    /** The microphone delivered a live signal. */
    PASSED,

    /** The microphone delivered only digital silence or a perfectly stationary floor. */
    SILENT,

    /** The capture did not open, or delivered too little audio to judge. */
    NO_AUDIO;

    val wireValue: String get() = name.lowercase()

    /** The check ran and voice will not listen on this microphone until it passes. */
    val failed: Boolean get() = this == SILENT || this == NO_AUDIO
}

/**
 * The microphone as every surface reports it: what the panel has, what its check found, and whether it is
 * muted. Mute is the owner's choice (a hardware switch, or another app), never a fault: while it lasts the
 * check is not run and no failed verdict is reported, and nothing here ever undoes it.
 */
data class MicrophoneStatus(
    val presence: MicrophonePresence,
    val check: MicrophoneCheck = MicrophoneCheck.NOT_RUN,
    /** The capture's own error when [check] is [MicrophoneCheck.NO_AUDIO], when it gave one. */
    val detail: String? = null,
    /** Android reports the microphone muted; never true for an [MicrophonePresence.ABSENT] one. */
    val muted: Boolean = false,
) {
    /** Voice may listen now: a proven microphone, or an unproven one whose check passed. */
    val usable: Boolean
        get() = presence == MicrophonePresence.PROVEN ||
            (presence == MicrophonePresence.UNPROVEN && check == MicrophoneCheck.PASSED)
}

/**
 * Judges a few seconds of the shared microphone's canonical PCM.
 *
 * A dead input is either exact zeros (a Tuya TPA10 on the default route) or a running converter
 * with nothing connected, whose output barely moves. Both are told apart from a live microphone in a
 * quiet room by two measures over [MEASURE_FRAMES] after a [SETTLE_FRAMES] start-up transient, which
 * some boards produce before falling silent: the count of distinct sample values, and the spread
 * between the loudest and quietest 100 ms window RMS. Derivation (2026-10-05): a Sonoff NSPanel Pro's
 * app-path captures in a quiet room show 232 to 1,596 distinct values and
 * a window spread of 31 or more over three seconds; a dead converter pair read as raw ALSA showed about
 * 55 distinct values over ten seconds and a spread of 0.2. Silent means fewer than [MIN_DISTINCT] values
 * and a spread under [MIN_SPREAD]; either live measure passes.
 */
class MicrophoneSelfCheck : PcmConsumer {
    private val lock = Any()
    private var seen = 0
    private var measured = 0
    private val distinct = HashSet<Short>()
    private var windowSum = 0.0
    private var windowFrames = 0
    private var quietest = Double.MAX_VALUE
    private var loudest = 0.0

    override fun onFrame(frame: PcmFrame) {
        synchronized(lock) {
            seen += 1
            if (seen <= SETTLE_FRAMES || measured >= MEASURE_FRAMES) return
            measured += 1
            for (sample in frame.samples) {
                if (distinct.size < MIN_DISTINCT) distinct.add(sample)
                windowSum += sample.toDouble() * sample
            }
            windowFrames += frame.samples.size
            if (measured % WINDOW_FRAMES == 0) {
                val rms = sqrt(windowSum / windowFrames.coerceAtLeast(1))
                quietest = minOf(quietest, rms)
                loudest = maxOf(loudest, rms)
                windowSum = 0.0
                windowFrames = 0
            }
        }
    }

    /** True once enough audio has arrived to judge it. */
    val complete: Boolean get() = synchronized(lock) { measured >= MEASURE_FRAMES }

    /** The verdict on what arrived; [MicrophoneCheck.NO_AUDIO] when too little did. */
    fun verdict(): MicrophoneCheck = synchronized(lock) {
        when {
            measured < MEASURE_FRAMES -> MicrophoneCheck.NO_AUDIO
            distinct.size >= MIN_DISTINCT || loudest - quietest >= MIN_SPREAD -> MicrophoneCheck.PASSED
            else -> MicrophoneCheck.SILENT
        }
    }

    companion object {
        /** Frames are 10 ms: skip the first second, then judge three. */
        const val SETTLE_FRAMES = 100
        const val MEASURE_FRAMES = 300
        const val WINDOW_FRAMES = 10
        const val MIN_DISTINCT = 100
        const val MIN_SPREAD = 2.0

        /** How long the check waits for its frames before calling the capture [MicrophoneCheck.NO_AUDIO]. */
        const val TIMEOUT_MS = 8_000L
    }
}
