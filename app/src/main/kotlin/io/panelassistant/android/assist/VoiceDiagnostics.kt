package io.panelassistant.android.assist

import io.panelassistant.android.audio.PcmConsumer
import io.panelassistant.android.audio.PcmFrame
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/** How loud the microphone is, in dB relative to full scale; -96 stands for digital silence. */
data class VoiceLevel(
    /** RMS over the meter's window. */
    val rmsDbfs: Float,
    /** The loudest sample in the window. */
    val peakDbfs: Float,
    /** The quietest 100 ms of the last few seconds: the room's noise floor as the panel hears it. */
    val floorDbfs: Float,
    /** Samples at full scale in the window: the input clipped. */
    val clipped: Int,
) {
    /** As the wake-word log lines carry it. */
    fun describe(): String =
        String.format(Locale.ROOT, "level rms %.1f dBFS peak %.1f dBFS clipped %d", rmsDbfs, peakDbfs, clipped)
}

/**
 * Measures what one microphone lease receives: RMS, peak and clipped samples over the last [windowFrames]
 * frames, and a floor from the quietest 100 ms block of the last three seconds. It keeps fixed arrays, so a
 * frame costs one pass over its samples and nothing is allocated per frame.
 */
class VoiceLevelMeter(private val windowFrames: Int) : PcmConsumer {
    init {
        require(windowFrames >= 1) { "windowFrames must be at least 1" }
    }

    private val squares = DoubleArray(windowFrames)
    private val counts = IntArray(windowFrames)
    private val peaks = IntArray(windowFrames)
    private val clips = IntArray(windowFrames)
    private var next = 0
    private var filled = 0

    private val blocks = DoubleArray(FLOOR_BLOCKS)
    private var blockNext = 0
    private var blocksFilled = 0
    private var blockSquares = 0.0
    private var blockSamples = 0
    private var blockFrames = 0

    @Synchronized
    override fun onFrame(frame: PcmFrame) {
        var sum = 0.0
        var peak = 0
        var clipped = 0
        val samples = frame.samples
        for (s in samples) {
            val v = s.toInt()
            val a = if (v < 0) -v else v
            sum += v.toDouble() * v
            if (a > peak) peak = a
            if (a >= CLIP) clipped++
        }
        squares[next] = sum
        counts[next] = samples.size
        peaks[next] = peak
        clips[next] = clipped
        next = (next + 1) % windowFrames
        if (filled < windowFrames) filled++

        blockSquares += sum
        blockSamples += samples.size
        if (++blockFrames == FRAMES_PER_BLOCK) {
            blocks[blockNext] = if (blockSamples == 0) 0.0 else sqrt(blockSquares / blockSamples)
            blockNext = (blockNext + 1) % FLOOR_BLOCKS
            if (blocksFilled < FLOOR_BLOCKS) blocksFilled++
            blockSquares = 0.0
            blockSamples = 0
            blockFrames = 0
        }
    }

    /** What the window holds, or null before the first frame. */
    @Synchronized
    fun reading(): VoiceLevel? {
        if (filled == 0) return null
        var sum = 0.0
        var samples = 0
        var peak = 0
        var clipped = 0
        for (i in 0 until filled) {
            sum += squares[i]
            samples += counts[i]
            if (peaks[i] > peak) peak = peaks[i]
            clipped += clips[i]
        }
        val rms = if (samples == 0) 0.0 else sqrt(sum / samples)
        val floor = if (blocksFilled == 0) rms else (0 until blocksFilled).minOf { blocks[it] }
        return VoiceLevel(dbfs(rms), dbfs(peak.toDouble()), dbfs(floor), clipped)
    }

    companion object {
        private const val CLIP = 32_767
        private const val FRAMES_PER_BLOCK = 10
        private const val FLOOR_BLOCKS = 30
        const val SILENCE_DBFS = -96f

        fun dbfs(amplitude: Double): Float =
            if (amplitude <= 0.0) SILENCE_DBFS else (20.0 * log10(amplitude / 32_768.0)).toFloat().coerceAtLeast(SILENCE_DBFS)
    }
}

/** What one command turn ended with, as the satellite runner saw it; held for [LastCommand]. */
internal data class VoiceTurnRecord(
    /** What speech-to-text heard. */
    val heard: String?,
    /** What Assist replied, its final text when it sent one. */
    val reply: String?,
    /** Panel Assistant's result type (`action_done`, `query_answer`, `error`), or derived when it sent none. */
    val outcome: String,
    /** The error or intent code, such as `no_intent_match` or `stt-no-text-recognized`. */
    val code: String?,
    /** How long the panel streamed the command, or null when it never listened. */
    val listenMs: Long?,
) {
    companion object {
        const val REPLIED = "replied"
        const val NO_REPLY = "no_reply"
        const val ERROR = "error"
    }
}

/**
 * The last command the panel heard, in memory only: shown to the owner on the Voice card, never written to
 * `/diag`, a log or a backup, and forgotten when voice is turned off.
 */
data class LastCommand(
    /** Wall-clock milliseconds when the turn ended. */
    val at: Long,
    val wakeWord: String?,
    /** The wake word's window mean when it fired; null for a turn nobody woke (tap-to-talk, a follow-up). */
    val wakeScore: Float?,
    val heard: String?,
    val reply: String?,
    val outcome: String,
    val code: String?,
    val listenMs: Long?,
)

/** The wake-word test: the live listener's best score against its cutoff, with real runs swallowed. */
data class WakeTestReading(
    /** `idle` before any test, `running`, or `done` when it timed out or was stopped. */
    val state: String,
    val model: String?,
    val peak: Float?,
    val cutoff: Float?,
    /** Activations the test swallowed. */
    val fired: Int,
    val remainingMs: Long,
) {
    companion object {
        val IDLE = WakeTestReading("idle", null, null, null, 0, 0L)
    }
}

/** Everything the owner's diagnostics read: the level meter while a session holds it, the wake test and the last command. */
data class VoiceDiagnosticsSnapshot(
    /** The browser session that owns the meter and the test, or null. */
    val session: String?,
    val level: VoiceLevel?,
    val wakeTest: WakeTestReading,
    val lastCommand: LastCommand?,
) {
    companion object {
        val EMPTY = VoiceDiagnosticsSnapshot(null, null, WakeTestReading.IDLE, null)
    }
}

/** The owner's voice diagnostics as the HTTP routes reach them. */
interface VoiceDiagnosticsControl {
    fun diagnostics(): VoiceDiagnosticsSnapshot

    /** Runs `level`, `wake_test`, `stop` or `heartbeat` for [sessionId]; null when accepted, else why not. */
    fun diagnosticsAction(action: String, sessionId: String): String?

    companion object {
        const val ACTION_LEVEL = "level"
        const val ACTION_WAKE_TEST = "wake_test"
        const val ACTION_STOP = "stop"
        const val ACTION_HEARTBEAT = "heartbeat"
        val ACTIONS = setOf(ACTION_LEVEL, ACTION_WAKE_TEST, ACTION_STOP, ACTION_HEARTBEAT)

        val NONE: VoiceDiagnosticsControl = object : VoiceDiagnosticsControl {
            override fun diagnostics() = VoiceDiagnosticsSnapshot.EMPTY
            override fun diagnosticsAction(action: String, sessionId: String) = "voice diagnostics are not available"
        }

        /** Follows whichever coordinator [current] names now, as the service replaces it. */
        fun following(current: () -> VoiceDiagnosticsControl): VoiceDiagnosticsControl = object : VoiceDiagnosticsControl {
            override fun diagnostics() = current().diagnostics()
            override fun diagnosticsAction(action: String, sessionId: String) = current().diagnosticsAction(action, sessionId)
        }
    }
}
