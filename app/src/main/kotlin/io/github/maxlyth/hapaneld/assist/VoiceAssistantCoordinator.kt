package io.github.maxlyth.hapaneld.assist

import io.github.maxlyth.hapaneld.audio.GainStage
import io.github.maxlyth.hapaneld.audio.MicLease
import io.github.maxlyth.hapaneld.audio.MicPurpose
import io.github.maxlyth.hapaneld.audio.MicrophoneGain
import io.github.maxlyth.hapaneld.audio.MicrophoneSource
import io.github.maxlyth.hapaneld.audio.MicrophoneSourceLifecycle
import io.github.maxlyth.hapaneld.audio.PcmConsumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** The voice settings the coordinator acts on, parsed once per (re)start. */
data class VoiceSettings(
    val enabled: Boolean,
    /** Wake-word model ids to arm, in order. */
    val wakeWords: List<String>,
    /** Wake-word model id to Assist pipeline id; an absent or blank value means the preferred pipeline. */
    val pipelines: Map<String, String>,
    /** Decibels of pre-amplification for the pipeline audio only; the wake-word listener never sees it. */
    val micGainDb: Int = 0,
) {
    companion object {
        /** Tolerant of malformed JSON: the registry validates on write, but a hand-edited store must not crash the service. */
        fun parse(
            enabled: Boolean,
            wakeWordsJson: String?,
            pipelinesJson: String?,
            micGainDb: Int = 0,
        ): VoiceSettings {
            val words = runCatching {
                val array = JSONArray(wakeWordsJson ?: "[]")
                (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
            }.getOrDefault(emptyList()).distinct()
            val map = runCatching {
                val obj = JSONObject(pipelinesJson ?: "{}")
                obj.keys().asSequence().associateWith { obj.optString(it) }
            }.getOrDefault(emptyMap())
            return VoiceSettings(
                enabled,
                words,
                map,
                micGainDb.coerceIn(MicrophoneGain.MIN_DB, MicrophoneGain.MAX_DB),
            )
        }
    }
}

/**
 * A wake-word activation as the coordinator sees it: which model fired, the phrase it is trained on, and
 * the capture time of the frame that completed it, which measures how soon the command audio attaches.
 */
data class WakeWordActivation(val modelId: String, val phrase: String, val heardAtNs: Long = 0L)

/** Arms the microWakeWord engine on the models [catalog] can load, bundled or imported. */
internal class MicroWakeWordEngineFactory(
    private val catalog: io.github.maxlyth.hapaneld.assist.wakeword.WakeWordCatalog,
) : WakeWordEngineFactory {
    override fun create(modelIds: List<String>, onActivation: (WakeWordActivation) -> Unit): WakeWordEngine? {
        val models = modelIds.mapNotNull(catalog::load)
        if (models.isEmpty()) return null
        val detector = io.github.maxlyth.hapaneld.assist.wakeword.WakeWordDetector(
            models,
            { hit -> onActivation(WakeWordActivation(hit.modelId, hit.phrase, hit.timestampNs)) },
            maxActive = models.size,
        )
        return object : WakeWordEngine, PcmConsumer by detector {
            override fun close() = detector.close()
        }
    }
}

/** One conversation turn as the satellite asks Home Assistant for it. */
internal data class VoiceTurnRequest(
    /** The wake word that started the conversation, which names its pipeline; null for one Home Assistant started. */
    val wakeWordId: String?,
    /** A later turn of the same conversation, where nobody said the wake word again. */
    val continued: Boolean = false,
    /** When the wake word was heard, `System.nanoTime()` base; 0 when no wake word started this turn. */
    val heardAtNs: Long = 0L,
)

/** An armed wake-word listener: a microphone consumer that reports activations until closed. */
interface WakeWordEngine : PcmConsumer, AutoCloseable

/**
 * Builds an engine for the requested bundled model ids, or returns null when no engine can run here
 * (no native library for this ABI, no bundled model, nothing requested). A null engine leaves the
 * feature in tap-to-talk mode: nothing holds the microphone until a run is asked for.
 */
fun interface WakeWordEngineFactory {
    fun create(modelIds: List<String>, onActivation: (WakeWordActivation) -> Unit): WakeWordEngine?

    companion object {
        val NONE = WakeWordEngineFactory { _, _ -> null }
    }
}

/** One conversation turn: Home Assistant runs the pipeline, the panel streams and plays. */
internal fun interface AssistRunner {
    suspend fun run(
        request: VoiceTurnRequest,
        attachAudio: (PcmConsumer) -> AutoCloseable,
        playback: AssistPlayback,
    ): AssistOutcome
}

/** Something Home Assistant asked the panel to say, as the coordinator plays it. */
internal data class VoiceAnnouncement(
    val url: String,
    val preannounceUrl: String?,
    /** Listen for an answer afterwards, as for `start_conversation`. */
    val listenAfter: Boolean,
    /** Called once the announcement has played, or could not be. */
    val done: () -> Unit,
)

/**
 * Owns the voice assistant's lifecycle on the panel: arms the wake-word engine on the shared
 * microphone, turns an activation (or a tap-to-talk trigger) into one Assist pipeline run, plays the
 * reply, follows a continued conversation for a bounded number of turns, and reports the phase
 * through [VoiceStateAuthority].
 *
 * Microphone discipline: the engine holds one `WAKE_WORD` lease while idle; a run pauses that lease
 * and takes its own `ASSIST` lease so the capture never stops between the wake word and the
 * utterance. No code here blocks the calling thread; every wait is a coroutine on [scope].
 *
 * Foreground policy: the microphone foreground-service type is claimed immediately before a lease is
 * taken and released as soon as no lease remains, through [foregroundMicrophone], so the type is
 * never held over a closed device. Android 14 refuses that claim when the
 * app is in the background, so a refused claim is retried on a bounded timer rather than treated as
 * terminal, and is retried immediately by [retryStart] when the panel's own activity comes forward.
 */
class VoiceAssistantCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val settings: () -> VoiceSettings,
    private val microphoneAvailable: () -> Boolean,
    private val source: () -> MicrophoneSource?,
    private val engineFactory: WakeWordEngineFactory,
    private val runnerFactory: () -> AssistRunner,
    private val playback: AssistPlayback,
    private val foregroundMicrophone: (Boolean) -> Boolean,
    private val state: VoiceStateAuthority,
    private val foregroundRetryMs: Long = DEFAULT_FOREGROUND_RETRY_MS,
    private val maxConversationTurns: Int = DEFAULT_MAX_CONVERSATION_TURNS,
    /** Shows the room that the panel has started listening: a chime, and a ripple on screen. */
    private val attention: () -> Unit = {},
) : AutoCloseable {

    private val lock = Any()
    private var armed = false
    private var engine: WakeWordEngine? = null
    private var wakeLease: MicLease? = null
    private var runJob: Job? = null
    private var retryJob: Job? = null
    private var foregroundClaimed = false
    private val closed = AtomicBoolean(false)

    // Bumped whenever the listener is replaced or torn down. A callback carries the generation it was
    // armed with, so a hit delivered by an engine that has since been closed or replaced is refused
    // instead of starting a run for a listener the panel no longer has.
    private var engineGeneration = 0L

    // Settings that arrived while a run was in flight. Reconfiguring underneath a run would close the
    // paused wake lease and open an unpaused one alongside the run's own, putting two consumers on one
    // capture; the run's teardown applies this instead.
    private var pendingReconfigure = false

    // True from the moment a run is admitted until its capture attachment has been closed. The
    // microphone foreground-service type must outlive that attachment: dropping it while the run is
    // still unwinding tells the platform the microphone is closed while it is still being read.
    // A run occupies the coordinator from admission until its own cleanup has finished, cancellation
    // included. Only the run that set this clears it, and nothing else is admitted meanwhile: one
    // owner at a time is what makes the shared microphone claim, the phase the panel reports and the
    // capture attachment unambiguous, without any of them needing to be reasoned about separately.

    // The source this coordinator actually obtained. Teardown shuts down what was opened and never
    // asks for a source: the supplier builds one on demand, so calling it here would open the
    // microphone on a panel that never used it, purely to close it again.
    private var obtainedSource: MicrophoneSource? = null

    /** True while the wake-word engine holds the microphone. */
    val listening: Boolean get() = synchronized(lock) { wakeLease != null }

    /** True while a pipeline run is in flight. */
    val running: Boolean get() = synchronized(lock) { runJob != null }

    /**
     * Apply the current settings: arm when enabled on a microphone-capable panel, otherwise stand
     * down. Safe to call repeatedly; a settings change is applied by calling it again.
     */
    fun start() {
        if (closed.get()) return
        val current = settings()
        if (!current.enabled || !microphoneAvailable()) {
            stop()
            return
        }
        synchronized(lock) {
            armed = true
            if (runJob != null) {
                // Apply it when the run drains, not underneath it.
                pendingReconfigure = true
                return
            }
            disarmLocked()
            armEngineLocked(current)
        }
    }

    /** Retry a start that the platform refused, for a caller that knows the app just came forward. */
    fun retryStart() {
        val shouldRetry = synchronized(lock) { armed && wakeLease == null && engine == null && runJob == null }
        if (shouldRetry) start()
    }

    /** Stand down: cancel any run, release the microphone, drop the foreground claim, report `off`. */
    fun stop() {
        val job: Job?
        synchronized(lock) {
            armed = false
            retryJob?.cancel()
            retryJob = null
            // Deliberately not cleared here. The run clears it when its cleanup has drained, so a
            // replacement cannot be admitted alongside a run that is still holding capture.
            job = runJob
            disarmLocked()
        }
        job?.cancel()
        state.set(VoiceState.OFF)
    }

    /** Press-to-talk: start one run with the preferred pipeline, as if a wake word had fired. */
    fun trigger(): VoiceTestTrigger.Result {
        if (closed.get()) return VoiceTestTrigger.Result.Unavailable("voice assistant is shut down")
        val current = settings()
        if (!current.enabled) return VoiceTestTrigger.Result.Refused("voice assistant is disabled")
        if (!microphoneAvailable()) return VoiceTestTrigger.Result.Unavailable("this panel has no microphone")
        return when (beginRun(activation = null, current)) {
            RunAdmission.STARTED -> VoiceTestTrigger.Result.Accepted
            RunAdmission.BUSY -> VoiceTestTrigger.Result.Refused("a voice run is already in progress")
            // The platform refused the microphone foreground service, which on recent Android happens
            // whenever the panel asks from the background. Saying "busy" would send the operator
            // looking for a run that does not exist. It says to try again rather than promising a
            // retry: nothing here retains the request, and no wake-word listener ships to rearm it.
            RunAdmission.FOREGROUND_REFUSED ->
                VoiceTestTrigger.Result.Unavailable("the panel could not claim the microphone; bring the dashboard forward and try again")
            RunAdmission.NOT_ELIGIBLE -> VoiceTestTrigger.Result.Refused("the voice assistant is not listening")
        }
    }

    /** Why a run was or was not admitted; the press-to-talk caller reports each of these differently. */
    private enum class RunAdmission { STARTED, BUSY, FOREGROUND_REFUSED, NOT_ELIGIBLE }

    /**
     * Proves teardown for the service boundary. Cancels any run and waits, within [timeoutMs], first
     * for that run to finish unwinding and then for the capture thread to release the device.
     * Reports whether both actually completed, because a boundary that is told teardown finished
     * while a coroutine is still running is the case the boundary exists to catch.
     */
    fun shutdown(timeoutMs: Long): Boolean {
        if (!closed.compareAndSet(false, true)) return true
        val job = synchronized(lock) { runJob }
        stop()
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000L
        val drained = job == null || runBlocking {
            withTimeoutOrNull(remainingMs(deadline)) { job.join() } != null
        }
        val lifecycle = synchronized(lock) { obtainedSource } as? MicrophoneSourceLifecycle
        val released = lifecycle?.shutdown(remainingMs(deadline)) ?: true
        return drained && released
    }

    private fun remainingMs(deadlineNanos: Long): Long =
        ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(0L)

    override fun close() {
        shutdown(DEFAULT_CLOSE_TIMEOUT_MS)
    }

    /** A hit from the listener armed at [generation]; refused once that listener has been replaced. */
    private fun onActivation(generation: Long, activation: WakeWordActivation) {
        beginRun(activation, settings(), generation)
    }

    private fun beginRun(activation: WakeWordActivation?, current: VoiceSettings): RunAdmission =
        beginRun(activation, current, null)

    /**
     * Play what Home Assistant asked the panel to say, replacing whatever it was saying or hearing: Home
     * Assistant has already ended that pipeline. The wake-word listener is paused for the playback, so the
     * panel never wakes itself; with [VoiceAnnouncement.listenAfter] a turn follows, as after a wake word.
     */
    internal fun announce(announcement: VoiceAnnouncement) {
        if (closed.get()) return announcement.done()
        scope.launch {
            repeat(ANNOUNCE_ADMISSION_ATTEMPTS) {
                val previous = synchronized(lock) { runJob }
                previous?.cancelAndJoin()
                when (beginRun(activation = null, settings(), null, announcement)) {
                    RunAdmission.STARTED -> return@launch
                    RunAdmission.BUSY -> Unit
                    else -> return@launch announcement.done()
                }
            }
            announcement.done()
        }
    }

    /**
     * Returns false when the run was refused: one is already in flight, the coordinator has stood
     * down or shut down, the setting is off, or the caller belongs to a superseded listener. The
     * admission decision is taken inside the lock so a callback cannot pass a check that a concurrent
     * stop has already invalidated.
     */
    private fun beginRun(
        activation: WakeWordActivation?,
        current: VoiceSettings,
        generation: Long?,
        announcement: VoiceAnnouncement? = null,
    ): RunAdmission {
        // Playing an announcement needs no microphone; listening afterwards does.
        val listens = announcement == null || announcement.listenAfter
        val mic = obtainSource()
        if (listens && mic == null) return RunAdmission.NOT_ELIGIBLE
        synchronized(lock) {
            if (closed.get()) return RunAdmission.NOT_ELIGIBLE
            if (runJob != null) return RunAdmission.BUSY
            if (generation != null && (generation != engineGeneration || !armed)) return RunAdmission.NOT_ELIGIBLE
            if (listens && !current.enabled) return RunAdmission.NOT_ELIGIBLE
            if (listens && !claimForegroundLocked()) {
                state.set(VoiceState.ERROR)
                return RunAdmission.FOREGROUND_REFUSED
            }
            wakeLease?.pause()
            runJob = scope.launch {
                var failed = false
                try {
                    if (announcement != null) {
                        state.set(VoiceState.RESPONDING)
                        val played = runCatching {
                            announcement.preannounceUrl?.let { playback.play(it) }
                            playback.play(announcement.url)
                        }
                        announcement.done()
                        played.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    }
                    if (listens && mic != null) failed = !converse(mic, activation, current)
                } finally {
                    synchronized(lock) {
                        // This run is the only one the coordinator has admitted, so its cleanup owns
                        // the shared state outright and releases the seat for the next request.
                        runJob = null
                        if (pendingReconfigure) {
                            pendingReconfigure = false
                            disarmLocked()
                            if (armed) armEngineLocked(settings())
                        }
                        val lease = wakeLease
                        // A failure stays visible as `error` until the next run replaces it; the panel is
                        // still listening, which the resumed wake lease proves, but the operator sees why
                        // the last attempt produced nothing.
                        val rest = if (failed) VoiceState.ERROR else VoiceState.IDLE
                        if (lease != null) {
                            lease.resume()
                            state.set(rest)
                        } else {
                            releaseForegroundLocked()
                            state.set(if (armed) rest else VoiceState.OFF)
                        }
                    }
                }
            }
        }
        return RunAdmission.STARTED
    }

    /** Returns false when the exchange ended in a reportable error. */
    private suspend fun converse(mic: MicrophoneSource, activation: WakeWordActivation?, current: VoiceSettings): Boolean {
        var request = VoiceTurnRequest(activation?.modelId, heardAtNs = activation?.heardAtNs ?: 0L)
        var turns = 0
        while (true) {
            state.set(VoiceState.LISTENING)
            attention()
            val outcome = runnerFactory().run(
                request,
                // Closing the attachment is the panel's own signal that it has stopped listening and is
                // waiting on Home Assistant, which is the only phase boundary observable from here.
                attachAudio = { consumer ->
                    val lease = mic.lease(
                        MicPurpose.ASSIST,
                        consumer = GainStage(consumer, current.micGainDb),
                    )
                    AutoCloseable {
                        lease.close()
                        state.set(VoiceState.PROCESSING)
                    }
                },
                playback = playback,
            )
            val error = outcome.error
            if (error != null) return error.silent
            turns += 1
            if (!outcome.continueConversation || turns >= maxConversationTurns) return true
            request = request.copy(continued = true, heardAtNs = 0L)
        }
    }

    private fun obtainSource(): MicrophoneSource? = source()?.also { synchronized(lock) { obtainedSource = it } }

    private fun armEngineLocked(current: VoiceSettings) {
        val mic = obtainSource()
        val generation = ++engineGeneration
        val built = if (mic != null && current.wakeWords.isNotEmpty()) {
            engineFactory.create(current.wakeWords) { activation -> onActivation(generation, activation) }
        } else {
            null
        }
        if (mic == null || built == null) {
            // Tap-to-talk only: nothing holds the microphone until a run is requested.
            state.set(VoiceState.IDLE)
            return
        }
        if (!claimForegroundLocked()) {
            built.close()
            state.set(VoiceState.ERROR)
            scheduleRetryLocked()
            return
        }
        engine = built
        wakeLease = mic.lease(MicPurpose.WAKE_WORD, consumer = built)
        state.set(VoiceState.IDLE)
    }

    private fun disarmLocked() {
        // Retiring the listener invalidates its callbacks: a hit already in flight names a generation
        // that no longer matches and is refused rather than starting a run for a closed engine.
        engineGeneration += 1
        wakeLease?.close()
        wakeLease = null
        engine?.close()
        engine = null
        if (runJob == null) releaseForegroundLocked()
    }

    private fun claimForegroundLocked(): Boolean {
        if (foregroundClaimed) return true
        foregroundClaimed = foregroundMicrophone(true)
        return foregroundClaimed
    }

    private fun releaseForegroundLocked() {
        if (!foregroundClaimed) return
        foregroundMicrophone(false)
        foregroundClaimed = false
    }

    private fun scheduleRetryLocked() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(foregroundRetryMs)
            synchronized(lock) { retryJob = null }
            retryStart()
        }
    }

    companion object {
        const val DEFAULT_FOREGROUND_RETRY_MS = 5 * 60_000L
        const val DEFAULT_CLOSE_TIMEOUT_MS = 2_000L
        const val DEFAULT_MAX_CONVERSATION_TURNS = 5
        private const val ANNOUNCE_ADMISSION_ATTEMPTS = 3
    }
}
