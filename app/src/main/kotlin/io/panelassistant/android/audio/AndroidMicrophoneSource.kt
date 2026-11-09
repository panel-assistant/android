package io.panelassistant.android.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
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
 * [route] chooses how the recorder is opened: the Android audio source (`VOICE_RECOGNITION` by default:
 * no AEC/AGC processing the wake-word detector would rather not have; `MIC`, raw; `VOICE_COMMUNICATION`,
 * echo-cancelled), the rate and channel count, the downmix, the preferred input device, the warm-up
 * discard and the platform echo canceller. Whatever it chooses, every consumer receives the contract's
 * 16 kHz mono 16-bit stream: a route other than that is converted by [ConvertingCaptureDevice] before
 * the fan-out, and the default route is handed over unconverted, exactly as before routes existed.
 */
class AndroidMicrophoneSource(
    context: Context,
    private val route: CaptureRoute = CaptureRoute(),
) : MicrophoneSource, MicrophoneSourceLifecycle, CaptureRouteReporting {

    private val applicationContext = context.applicationContext

    private val fanOut = MicrophoneFanOut(
        deviceFactory = FallbackCaptureDevices(
            requested = route,
            build = { chosen ->
                val device = AudioRecordCaptureDevice(androidRecorderFactory(applicationContext, chosen))
                if (chosen.passthrough) device else ConvertingCaptureDevice(device, chosen.downmix, chosen.warmupReads)
            },
            log = { Log.w(TAG, it) },
        ),
        logger = { message, error -> if (error != null) Log.w(TAG, message, error) else Log.i(TAG, message) },
    )

    override val state: StateFlow<MicState> get() = fanOut.state

    override fun lease(
        purpose: MicPurpose,
        priority: Int,
        consumer: PcmConsumer,
        queueFrames: Int,
    ): MicLease = fanOut.lease(purpose, priority, consumer, queueFrames)

    override fun captureRoute(): CaptureRouteReport = fanOut.routeReport(route)

    override fun shutdown(timeoutMs: Long): Boolean = fanOut.shutdown(timeoutMs)

    override fun close() = fanOut.close()
}

/**
 * The capture devices for one requested route, with the canonical route behind it.
 *
 * The open ladder only sees whether a recorder can be built. A HAL can build the requested recorder and
 * then refuse to start it, or start it and fail every read, and on such a panel the requested route would
 * leave the microphone deaf where the canonical `VOICE_RECOGNITION` 16 kHz mono route works. So a refused
 * start falls back to the canonical route inside the same generation, and a generation that ended on failed
 * reads without ever delivering audio counts against the route. After [maxFailures] such failures in a row
 * every later generation opens the canonical route directly, for as long as this source lives; a route
 * setting change builds a new source, which tries the requested route again. The route report's active
 * capture then shows what is really open, beside the route that was asked for.
 *
 * The requested route being the canonical one changes nothing: its devices are built exactly as before.
 */
internal class FallbackCaptureDevices(
    private val requested: CaptureRoute,
    private val build: (CaptureRoute) -> PcmCaptureDevice,
    private val log: (String) -> Unit = {},
    private val maxFailures: Int = MAX_ROUTE_FAILURES,
) : () -> PcmCaptureDevice {

    private val canonical = CaptureRoute()

    /** Consecutive generations the requested route failed; read and written only by devices of this factory. */
    private val failures = java.util.concurrent.atomic.AtomicInteger()

    override fun invoke(): PcmCaptureDevice =
        if (requested == canonical || failures.get() >= maxFailures) build(canonical) else Attempt(build(requested))

    private fun routeFailed(why: String) {
        val count = failures.incrementAndGet()
        log(
            if (count >= maxFailures) "capture route $requested $why; using the canonical route from now on"
            else "capture route $requested $why; falling back to the canonical route for this capture",
        )
    }

    /** One generation on the requested route, holding the canonical device it falls back to. */
    private inner class Attempt(private val primary: PcmCaptureDevice) : PcmCaptureDevice {
        private val lock = Any()
        private var revoked = false
        private var closed = false
        @Volatile private var current: PcmCaptureDevice = primary
        @Volatile private var heardAudio = false
        @Volatile private var failedRead = false

        override fun open(): Boolean {
            if (primary.open()) return true
            primary.close()
            val fallback = synchronized(lock) {
                // A stop or close while the requested route was refusing ends this generation outright.
                if (revoked) return false
                build(canonical).also { current = it }
            }
            routeFailed("could not be started")
            return fallback.open()
        }

        override fun read(into: ShortArray, offsetSamples: Int, maxSamples: Int): Int {
            val device = current
            val read = device.read(into, offsetSamples, maxSamples)
            if (device === primary) {
                if (read > 0) heardAudio = true else if (read < 0) failedRead = true
            }
            return read
        }

        override fun stop() {
            synchronized(lock) {
                revoked = true
                current
            }.stop()
        }

        override fun close() {
            val (closing, first) = synchronized(lock) {
                revoked = true
                val first = !closed
                closed = true
                current to first
            }
            closing.close()
            if (first && closing === primary) {
                if (heardAudio) failures.set(0) else if (failedRead) routeFailed("delivered no audio before its reads failed")
            }
        }

        override fun describe(): ActiveCapture? = current.describe()
    }

    companion object {
        /** Failed generations in a row after which the requested route is no longer tried. */
        const val MAX_ROUTE_FAILURES = 2
    }
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

    /** The source, rate and channel count this recorder was built with. */
    val format: RecorderFormat
        get() = RecorderFormat(MediaRecorder.AudioSource.VOICE_RECOGNITION, MicrophoneSource.SAMPLE_RATE_HZ, 1)

    /** The input device Android routes this recorder to, read now; null when unknown. */
    fun routedDevice(): String? = null

    /** The platform echo canceller's state, read from the platform now. */
    fun aecState(): AecState = AecState.REQUESTED_OFF
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
) : FormattedCaptureDevice {

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

    override val format: RecorderFormat? get() = recorder?.format

    override fun describe(): ActiveCapture? = recorder?.let {
        val format = it.format
        ActiveCapture(
            source = format.source,
            sampleRateHz = format.sampleRateHz,
            channels = format.channels,
            downmix = null,
            routedDevice = it.routedDevice(),
            aec = it.aecState(),
            warmupReads = 0,
        )
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
 * Builds the recorder one route asks for: the open ladder over the platform's `AudioRecord`, then the
 * preferred input device and the platform echo canceller.
 *
 * Everything that can refuse before the microphone is touched — a missing permission, a format the
 * platform rejects, a recorder that will not initialise — resolves to a null recorder here, so the
 * device's lifecycle never has to distinguish "could not build" from "was revoked".
 */
@SuppressLint("MissingPermission") // checked below; a refusal is reported as a recorder that cannot be built
internal fun androidRecorderFactory(context: Context, route: CaptureRoute): () -> PcmRecorder? = factory@{
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED
    ) {
        Log.w(TAG, "RECORD_AUDIO is not granted; microphone capture cannot start")
        return@factory null
    }
    val record = openRecorderLadder(route, ::buildAudioRecord, log = { Log.w(TAG, it) }) ?: return@factory null
    val device = preferInputDevice(context, record.record, route.preferredDevice)
    val aec = armPlatformAec(route.platformAec, record.record.audioSessionId, appSideCanceller = false, AndroidEchoCanceller)
    val recorder = AudioRecordRecorder(record.record, record.format, aec)
    Log.i(
        TAG,
        "route=${CaptureRoute.sourceName(record.format.source)}/${record.format.sampleRateHz}/${record.format.channels}/" +
            "${if (record.format.channels == 2) route.downmix.wireValue else "none"} device=$device aec=${recorder.aecState().wireValue}",
    )
    recorder
}

/** One `AudioRecord` the ladder accepted, with the format it was built in. */
internal class BuiltRecord<R>(val record: R, val format: RecorderFormat)

/**
 * Tries the route's source, then `VOICE_RECOGNITION`, then `MIC`, each first in the route's rate and
 * channel count and then in 16 kHz mono, and keeps the first recorder the platform initialises. A
 * [SecurityException] ends the ladder at once: it means the permission is gone, and no other source will
 * be granted it. Any other refusal moves to the next rung, so a route the hardware will not open falls
 * back to the canonical format rather than leaving the panel deaf.
 */
internal fun <R> openRecorderLadder(
    route: CaptureRoute,
    build: (source: Int, sampleRateHz: Int, channels: Int) -> R?,
    log: (String) -> Unit,
): BuiltRecord<R>? {
    val sources = listOf(route.source, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC).distinct()
    val formats = listOf(
        route.sampleRateHz to route.channels,
        MicrophoneSource.SAMPLE_RATE_HZ to MicrophoneSource.CHANNELS,
    ).distinct()
    for (source in sources) {
        for ((rate, channels) in formats) {
            val built = try {
                build(source, rate, channels)
            } catch (denied: SecurityException) {
                log("microphone permission refused while opening ${CaptureRoute.sourceName(source)}: ${denied.message}")
                return null
            } catch (t: Throwable) {
                log("AudioRecord ${CaptureRoute.sourceName(source)}/$rate/$channels could not be constructed: ${t.javaClass.simpleName}")
                null
            }
            if (built != null) return BuiltRecord(built, RecorderFormat(source, rate, channels))
        }
    }
    log("no capture route opened (tried ${sources.joinToString { CaptureRoute.sourceName(it) }})")
    return null
}

/** One initialised `AudioRecord`, or null when the platform refuses the format. Throws what the platform throws. */
@SuppressLint("MissingPermission") // the factory checked it; a revocation since surfaces as SecurityException
private fun buildAudioRecord(source: Int, sampleRateHz: Int, channels: Int): AudioRecord? {
    val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
    val minBytes = AudioRecord.getMinBufferSize(sampleRateHz, mask, AudioFormat.ENCODING_PCM_16BIT)
    if (minBytes <= 0) {
        Log.w(TAG, "AudioRecord rejected $sampleRateHz Hz x$channels (getMinBufferSize=$minBytes)")
        return null
    }
    // At least twice the driver minimum, and never less than 80 ms, so a capture thread that is
    // briefly descheduled does not overrun the ring and lose audio the consumers never hear about.
    val framesPer80Ms = sampleRateHz / 1000 * MicrophoneSource.FRAME_MS * 8
    val bufferBytes = maxOf(minBytes * 2, framesPer80Ms * channels * BYTES_PER_SAMPLE)
    val record = AudioRecord(source, sampleRateHz, mask, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
    if (record.state != AudioRecord.STATE_INITIALIZED) {
        Log.w(TAG, "AudioRecord did not initialise (source=$source rate=$sampleRateHz channels=$channels state=${record.state})")
        runCatching { record.release() }
        return null
    }
    return record
}

/**
 * Asks the recorder to prefer the input [preferred] names, from one snapshot of the input devices taken
 * now. Returns what the log line reports: `default` when nothing was asked or nothing matched, else the
 * chosen device as `type:name`. A refusal keeps the platform's own routing.
 */
private fun preferInputDevice(context: Context, record: AudioRecord, preferred: PreferredMicDevice): String {
    if (preferred == PreferredMicDevice.AUTO) return "default"
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "default"
    val inputs = runCatching { audio.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }.getOrDefault(emptyList())
    val order = when (preferred) {
        PreferredMicDevice.BUILTIN -> listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        PreferredMicDevice.USB -> listOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY)
        PreferredMicDevice.AUTO -> emptyList()
    }
    val chosen = order.firstNotNullOfOrNull { type -> inputs.firstOrNull { it.type == type } } ?: return "default"
    val accepted = runCatching { record.setPreferredDevice(chosen) }.getOrDefault(false)
    return if (accepted) describeDevice(chosen) else "default"
}

private fun describeDevice(device: AudioDeviceInfo): String = "${device.type}:${device.productName}"

private const val BYTES_PER_SAMPLE = 2

/**
 * The platform echo canceller, reduced to what arming it needs, so availability and read-back can be
 * scripted on a plain JVM.
 */
internal interface EchoCancellerPlatform {
    fun isAvailable(): Boolean

    /** Attach a canceller to the capture session; null when the platform refuses. */
    fun create(sessionId: Int): EchoCancellerEffect?
}

/** One attached canceller. */
internal interface EchoCancellerEffect {
    fun setEnabled(enabled: Boolean)

    /** Read from the platform every time; never a remembered value. */
    fun enabled(): Boolean

    fun release()
}

/** The arming result a recorder holds: the effect it kept, if any, and why not otherwise. */
internal class ArmedEchoCanceller(private val effect: EchoCancellerEffect?, private val refused: AecState) {
    /** [AecState.ARMED] only while the platform reads the kept effect back as enabled. */
    fun state(): AecState {
        val kept = effect ?: return refused
        return if (runCatching { kept.enabled() }.getOrDefault(false)) AecState.ARMED else AecState.READBACK_FALSE
    }

    fun release() {
        val kept = effect ?: return
        runCatching { kept.release() }
    }

    companion object {
        val OFF = ArmedEchoCanceller(null, AecState.REQUESTED_OFF)
    }
}

/**
 * Attach the platform echo canceller when the route asks for it, or asks for automatic and the platform
 * has one, and keep it only when it reads back as enabled. Never while [appSideCanceller] is on: two
 * cancellers in series fight each other, and an app-side one is the better-informed of the two.
 */
internal fun armPlatformAec(
    mode: PlatformAecMode,
    sessionId: Int,
    appSideCanceller: Boolean,
    platform: EchoCancellerPlatform,
): ArmedEchoCanceller {
    if (mode == PlatformAecMode.OFF || appSideCanceller) return ArmedEchoCanceller.OFF
    if (sessionId <= 0 || !runCatching { platform.isAvailable() }.getOrDefault(false)) {
        return ArmedEchoCanceller(null, AecState.UNAVAILABLE)
    }
    val effect = runCatching { platform.create(sessionId) }.getOrNull() ?: return ArmedEchoCanceller(null, AecState.UNAVAILABLE)
    val enabled = runCatching {
        effect.setEnabled(true)
        effect.enabled()
    }.getOrDefault(false)
    if (!enabled) {
        runCatching { effect.release() }
        return ArmedEchoCanceller(null, AecState.READBACK_FALSE)
    }
    return ArmedEchoCanceller(effect, AecState.ARMED)
}

/** [EchoCancellerPlatform] over `android.media.audiofx.AcousticEchoCanceler`. */
private object AndroidEchoCanceller : EchoCancellerPlatform {
    override fun isAvailable(): Boolean = AcousticEchoCanceler.isAvailable()

    override fun create(sessionId: Int): EchoCancellerEffect? {
        val canceller = AcousticEchoCanceler.create(sessionId) ?: return null
        return object : EchoCancellerEffect {
            override fun setEnabled(enabled: Boolean) {
                canceller.enabled = enabled
            }

            override fun enabled(): Boolean = canceller.enabled

            override fun release() = canceller.release()
        }
    }
}

/** The thin binding from [PcmRecorder] onto `AudioRecord`. Holds no lifecycle state of its own. */
private class AudioRecordRecorder(
    private val record: AudioRecord,
    override val format: RecorderFormat,
    private val aec: ArmedEchoCanceller,
) : PcmRecorder {

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

    override fun routedDevice(): String? = runCatching { record.routedDevice?.let(::describeDevice) }.getOrNull()

    override fun aecState(): AecState = aec.state()

    override fun stop() {
        runCatching { record.stop() }.onFailure { Log.w(TAG, "AudioRecord could not be stopped", it) }
    }

    override fun release() {
        aec.release()
        runCatching { record.release() }.onFailure { Log.w(TAG, "AudioRecord could not be released", it) }
        Log.i(TAG, "microphone released")
    }
}
