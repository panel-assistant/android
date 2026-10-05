package io.panelassistant.android.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "ha-paneld/mic"

/**
 * The panel's one microphone, shared through [MicrophoneFanOut].
 *
 * This class is only the platform half: it hands the fan-out a way to build a capture device, and
 * the fan-out builds a fresh one for every capture generation. Everything about leases, queues,
 * fan-out and state lives in the platform-free core, which is why the behaviour that matters is
 * unit-testable without an emulator.
 *
 * [audioSource] is injectable so a setting can move the panel between `VOICE_RECOGNITION` (the
 * default: no AEC/AGC processing the wake-word detector would rather not have), `MIC` (raw) and
 * `VOICE_COMMUNICATION` (echo-cancelled, for a panel that plays TTS through its own speaker while
 * listening). Nothing else about the format is configurable — the contract's 16 kHz mono 16-bit
 * stream is what every consumer is written against.
 */
class AndroidMicrophoneSource(
    context: Context,
    audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
) : MicrophoneSource, MicrophoneSourceLifecycle {

    private val applicationContext = context.applicationContext

    private val fanOut = MicrophoneFanOut(
        deviceFactory = { AudioRecordCaptureDevice(androidRecorderFactory(applicationContext, audioSource)) },
        logger = { message, error -> if (error != null) Log.w(TAG, message, error) else Log.i(TAG, message) },
    )

    override val state: StateFlow<MicState> get() = fanOut.state

    override fun lease(
        purpose: MicPurpose,
        priority: Int,
        consumer: PcmConsumer,
        queueFrames: Int,
    ): MicLease = fanOut.lease(purpose, priority, consumer, queueFrames)

    override fun shutdown(timeoutMs: Long): Boolean = fanOut.shutdown(timeoutMs)

    override fun close() = fanOut.close()
}

/**
 * The four things a capture device does to a platform recorder.
 *
 * Small on purpose: it is the whole of the platform surface [AudioRecordCaptureDevice] touches, so
 * that device's lifecycle — which is where a missed revocation would leave a live microphone — is
 * provable on a plain JVM against a recorder a test can watch.
 */
internal interface PcmRecorder {
    /** Begin capture. Returns whether the platform confirms it is now recording; never throws. */
    fun startRecording(): Boolean

    fun read(into: ShortArray, offsetSamples: Int, maxSamples: Int): Int

    fun stop()

    fun release()
}

/**
 * One capture device: one recorder, from construction to release, and never a second one.
 *
 * **Why this shape.** The microphone goes live at `startRecording()`, so between starting a recorder
 * and publishing it there used to be a window in which a concurrent [stop] or [close] saw nothing
 * published, stopped nothing, and returned — after which the open published a recorder that was
 * already recording, and a teardown that had reported itself complete was followed by a live
 * microphone. Guarding that window is not enough, because every guard leaves a smaller one.
 *
 * Instead the window cannot exist. Publishing the recorder and starting it are a single critical
 * section, so there is no instant at which a started recorder is unpublished. Revocation is
 * permanent and lives in that same guarded state, so an open that was overtaken while its recorder
 * was being built loses the race deliberately: it releases a recorder it never started, and returns
 * false. And the device is single-use — [MicrophoneFanOut] builds a fresh one for every capture
 * generation — so there is no "reopen" to reset a flag for, and therefore no reset to race.
 *
 * [stop] stays distinct from [close] for the documented reason: releasing a recorder underneath a
 * blocked `read` is undefined, so stopping it first is what lets the reader return.
 */
internal class AudioRecordCaptureDevice(
    private val openRecorder: () -> PcmRecorder?,
    /**
     * Runs after a recorder exists and before the critical section that publishes and starts it —
     * exactly the window a revocation has to survive. Present so that window can be held open by a
     * test; in production it does nothing.
     */
    private val afterConstruction: () -> Unit = {},
) : PcmCaptureDevice {

    private val lock = Any()

    /** Mutated only under [lock]; read without it only on the capture thread's own read path. */
    @Volatile private var recorder: PcmRecorder? = null

    /** Set once, never cleared: this device is finished and must never start anything. */
    private var revoked = false

    /** Lock-free "capture is over" signal, so [read] never takes the lock it would block others on. */
    @Volatile private var ended = false

    override fun open(): Boolean {
        val constructed = openRecorder() ?: return false
        afterConstruction()
        synchronized(lock) {
            // Anything that revoked this device while the recorder was being built wins. The recorder
            // has never been started, so releasing it here is the whole of its lifetime, and nothing
            // is published that a stop could have missed.
            if (revoked || recorder != null) {
                constructed.release()
                return false
            }
            if (!constructed.startRecording()) {
                constructed.stop()
                constructed.release()
                return false
            }
            recorder = constructed
            return true
        }
    }

    override fun read(into: ShortArray, offsetSamples: Int, maxSamples: Int): Int {
        if (ended) return 0
        val active = recorder ?: return 0
        val read = active.read(into, offsetSamples, maxSamples)
        // A stop racing this read surfaces as an error code from a recorder that is no longer
        // recording. That is an ordinary end of capture, not a fault to latch.
        if (ended) return 0
        return read
    }

    override fun stop() {
        synchronized(lock) {
            revoked = true
            ended = true
            recorder?.stop()
        }
    }

    override fun close() {
        val closing: PcmRecorder?
        synchronized(lock) {
            revoked = true
            ended = true
            closing = recorder
            recorder = null
        }
        // Released outside the lock: revocation is already latched, so no open can publish behind it.
        if (closing != null) {
            closing.stop()
            closing.release()
        }
    }
}

/**
 * Builds one `AudioRecord` in the canonical format, or nothing.
 *
 * Everything that can refuse before the microphone is touched — a missing permission, a format the
 * platform rejects, a recorder that will not initialise — resolves to a null recorder here, so the
 * device's lifecycle never has to distinguish "could not build" from "was revoked".
 */
@SuppressLint("MissingPermission") // checked below; a refusal is reported as a recorder that cannot be built
internal fun androidRecorderFactory(context: Context, audioSource: Int): () -> PcmRecorder? = factory@{
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED
    ) {
        Log.w(TAG, "RECORD_AUDIO is not granted; microphone capture cannot start")
        return@factory null
    }
    val minBytes = AudioRecord.getMinBufferSize(
        MicrophoneSource.SAMPLE_RATE_HZ,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    if (minBytes <= 0) {
        Log.w(TAG, "AudioRecord rejected the canonical 16 kHz mono format (getMinBufferSize=$minBytes)")
        return@factory null
    }
    // At least twice the driver minimum, and never less than 80 ms, so a capture thread that is
    // briefly descheduled does not overrun the ring and lose audio the consumers never hear about.
    val bufferBytes = maxOf(minBytes * 2, MicrophoneSource.SAMPLES_PER_FRAME * BYTES_PER_SAMPLE * 8)
    val record = try {
        AudioRecord(
            audioSource,
            MicrophoneSource.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
    } catch (t: Throwable) {
        Log.w(TAG, "AudioRecord could not be constructed", t)
        return@factory null
    }
    if (record.state != AudioRecord.STATE_INITIALIZED) {
        Log.w(TAG, "AudioRecord did not initialise (state=${record.state})")
        runCatching { record.release() }
        return@factory null
    }
    Log.i(TAG, "microphone recorder built: source=$audioSource buffer=${bufferBytes}B")
    AudioRecordRecorder(record)
}

private const val BYTES_PER_SAMPLE = 2

/** The thin binding from [PcmRecorder] onto `AudioRecord`. Holds no lifecycle state of its own. */
private class AudioRecordRecorder(private val record: AudioRecord) : PcmRecorder {

    override fun startRecording(): Boolean {
        try {
            record.startRecording()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord could not start recording", t)
            return false
        }
        return record.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }

    override fun read(into: ShortArray, offsetSamples: Int, maxSamples: Int): Int = try {
        record.read(into, offsetSamples, maxSamples)
    } catch (t: Throwable) {
        Log.w(TAG, "AudioRecord read threw", t)
        -1
    }

    override fun stop() {
        runCatching { record.stop() }.onFailure { Log.w(TAG, "AudioRecord could not be stopped", it) }
    }

    override fun release() {
        runCatching { record.release() }.onFailure { Log.w(TAG, "AudioRecord could not be released", it) }
        Log.i(TAG, "microphone released")
    }
}
