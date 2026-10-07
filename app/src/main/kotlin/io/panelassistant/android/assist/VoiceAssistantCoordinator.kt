package io.panelassistant.android.assist

import io.panelassistant.android.audio.GainStage
import io.panelassistant.android.audio.MicLease
import io.panelassistant.android.audio.MicPurpose
import io.panelassistant.android.audio.MicState
import io.panelassistant.android.audio.MicrophoneCheck
import io.panelassistant.android.audio.MicrophoneGain
import io.panelassistant.android.audio.MicrophonePresence
import io.panelassistant.android.audio.MicrophoneSelfCheck
import io.panelassistant.android.audio.MicrophoneSource
import io.panelassistant.android.audio.MicrophoneStatus
import io.panelassistant.android.audio.PcmConsumer
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
    private val catalog: io.panelassistant.android.assist.wakeword.WakeWordCatalog,
    private val log: (String) -> Unit = {},
    /** The `voice_sensitivity` setting, read each time the listener is armed. */
    private val sensitivity: () -> String? = { null },
) : WakeWordEngineFactory {
    override fun create(modelIds: List<String>, onActivation: (WakeWordActivation) -> Unit): WakeWordEngine? {
        val models = modelIds.mapNotNull(catalog::load)
        if (models.isEmpty()) return null
        val detector = io.panelassistant.android.assist.wakeword.WakeWordDetector(
            models,
            { hit -> onActivation(WakeWordActivation(hit.modelId, hit.phrase, hit.timestampNs)) },
            maxActive = models.size,
            nearMiss = { id, mean -> log("wake word $id heard at ${"%.2f".format(mean)}, short of its cutoff") },
            cutoffOffset = io.panelassistant.android.assist.wakeword.WakeWordDetector.cutoffOffset(sensitivity()),
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
    /**
     * When the event arrived if Panel Assistant streams the announcement, chime and speech together;
     * null plays [preannounceUrl] and [url] as before.
     */
    val streamAtNs: Long? = null,
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
 *
 * Microphone proof: a [MicrophonePresence.PROVEN] microphone arms at once. An
 * [MicrophonePresence.UNPROVEN] one is first leased for a [MicrophoneSelfCheck], and the wake word arms
 * only when it passes; a failed check leaves the setting on and the panel reporting `error` with the
 * reason in [microphoneStatus], and runs again when voice is turned off and on, when the audio source
 * changes, or at the next start of the service.
 */
class VoiceAssistantCoordinator internal constructor(
    private val scope: CoroutineScope,
    private val settings: () -> VoiceSettings,
    private val microphone: () -> MicrophonePresence,
    private val source: () -> MicrophoneSource?,
    private val engineFactory: WakeWordEngineFactory,
    private val runnerFactory: () -> AssistRunner,
    private val playback: AssistPlayback,
    private val foregroundMicrophone: (Boolean) -> Boolean,
    private val state: VoiceStateAuthority,
    private val foregroundRetryMs: Long = DEFAULT_FOREGROUND_RETRY_MS,
    private val maxConversationTurns: Int = DEFAULT_MAX_CONVERSATION_TURNS,
    /** Shows the room that the panel has started listening: a chime, and a ripple on screen. */
    /** Named with the wake word whose pipeline is listening; a turn without one uses the first armed. */
    private val attention: (wakeWordId: String?) -> Unit = {},
    /** Told whenever [microphoneStatus] changes, so Home Assistant and Configure can say why. */
    private val onMicrophoneStatus: () -> Unit = {},
    private val checkTimeoutMs: Long = MicrophoneSelfCheck.TIMEOUT_MS,
    /** Android reports the microphone muted; [microphoneMuteChanged] is called when it changes. */
    private val muted: () -> Boolean = { false },
) : AutoCloseable {

    private val lock = Any()
    private var armed = false
    private var engine: WakeWordEngine? = null
    private var wakeLease: MicLease? = null
    private var runJob: Job? = null
    private var retryJob: Job? = null
    private var foregroundClaimed = false
    private val closed = AtomicBoolean(false)

    // The capture check of an unproven microphone: the source it judged, its verdict, and the job still
    // capturing, if any. A new source object (another audio source) is a new microphone to check.
    private var checkedSource: MicrophoneSource? = null
    private var check = MicrophoneCheck.NOT_RUN
    private var checkDetail: String? = null
    private var checkJob: Job? = null

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

    /** True while the wake-word engine holds the microphone. */
    val listening: Boolean get() = synchronized(lock) { wakeLease != null }

    /** True while a pipeline run is in flight. */
    val running: Boolean get() = synchronized(lock) { runJob != null }

    /**
     * The microphone as every surface reports it; the check is reported only for an unproven one, and
     * never while it is muted, when silence is the owner's choice rather than a verdict on the hardware.
     */
    fun microphoneStatus(): MicrophoneStatus {
        val presence = microphone()
        if (presence == MicrophonePresence.ABSENT) return MicrophoneStatus(presence)
        if (muted()) return MicrophoneStatus(presence, muted = true)
        if (presence != MicrophonePresence.UNPROVEN) return MicrophoneStatus(presence)
        return synchronized(lock) { MicrophoneStatus(presence, check, checkDetail) }
    }

    /**
     * The mute changed. Muting abandons an unproven microphone's check and any failed verdict, which
     * would only have measured the mute; unmuting checks it again, so voice listens once it passes.
     */
    fun microphoneMuteChanged() {
        if (closed.get()) return
        val recheck = synchronized(lock) {
            if (!armed || microphone() != MicrophonePresence.UNPROVEN) {
                false
            } else if (muted()) {
                // A passed check stands: the wake word stays armed and hears again on unmute.
                if (check != MicrophoneCheck.PASSED) {
                    abandonCheckLocked()
                    state.set(VoiceState.IDLE)
                }
                false
            } else {
                check != MicrophoneCheck.PASSED && runJob == null
            }
        }
        if (recheck) start()
    }

    /**
     * Apply the current settings: arm when enabled on a panel offering a microphone, otherwise stand
     * down. Safe to call repeatedly; a settings change is applied by calling it again.
     */
    fun start() {
        if (closed.get()) return
        val current = settings()
        if (!current.enabled || !microphone().offered) {
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
            checkJob?.cancel()
            checkedSource = null
            setCheckLocked(MicrophoneCheck.NOT_RUN, null)
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
        val status = microphoneStatus()
        if (!status.presence.offered) return VoiceTestTrigger.Result.Unavailable("this panel has no microphone")
        if (status.muted) return VoiceTestTrigger.Result.Unavailable(MUTED_REASON)
        when (status.check) {
            MicrophoneCheck.RUNNING -> return VoiceTestTrigger.Result.Refused("the panel is still checking its microphone")
            MicrophoneCheck.SILENT -> return VoiceTestTrigger.Result.Unavailable(SILENT_REASON)
            MicrophoneCheck.NO_AUDIO -> return VoiceTestTrigger.Result.Unavailable(NO_AUDIO_REASON)
            else -> Unit
        }
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
     * Cancels this feature and waits within [timeoutMs] for its run to release its capture lease.
     * The service separately drains the shared source after every feature has stopped.
     */
    fun shutdown(timeoutMs: Long): Boolean {
        if (!closed.compareAndSet(false, true)) return true
        // The run and a capture check each hold a lease until their own cleanup has run.
        val jobs = synchronized(lock) { listOfNotNull(runJob, checkJob) }
        stop()
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000L
        val drained = jobs.isEmpty() || runBlocking {
            withTimeoutOrNull(remainingMs(deadline)) { jobs.forEach { it.join() } } != null
        }
        // The service owns the source. Only this feature's wake-word/Assist leases are ours to
        // release; a camera STREAM lease must survive a voice restart or stand-down.
        return drained
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
        // Playing an announcement needs no microphone; listening afterwards does, and an announcement
        // still plays where the microphone has not proven itself.
        val usable = microphoneStatus().usable
        if (announcement == null && !usable) return RunAdmission.NOT_ELIGIBLE
        val listens = announcement == null || (announcement.listenAfter && usable)
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
                            val streamAt = announcement.streamAtNs
                            if (streamAt != null) {
                                playback.playStream(streamAt, listOfNotNull(announcement.preannounceUrl, announcement.url))
                            } else {
                                announcement.preannounceUrl?.let { playback.play(it) }
                                playback.play(announcement.url)
                            }
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
            // Cued first, so the listening tint that the state change raises already has this pipeline's colour.
            attention(request.wakeWordId ?: current.wakeWords.firstOrNull())
            state.set(VoiceState.LISTENING)
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

    private fun obtainSource(): MicrophoneSource? = source()

    private fun armEngineLocked(current: VoiceSettings) {
        val mic = obtainSource()
        if (mic != null && microphone() == MicrophonePresence.UNPROVEN &&
            !(checkedSource === mic && check == MicrophoneCheck.PASSED)
        ) {
            when {
                // A muted microphone would only record the mute; it is checked once unmuted.
                muted() -> state.set(VoiceState.IDLE)
                checkedSource === mic && check.failed -> state.set(VoiceState.ERROR)
                else -> checkLocked(mic)
            }
            return
        }
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
        if (runJob == null && checkJob == null) releaseForegroundLocked()
    }

    /** Lease [mic] for a [MicrophoneSelfCheck]; the wake word arms from its result if it passes. */
    private fun checkLocked(mic: MicrophoneSource) {
        if (checkJob != null && checkedSource === mic) return
        // A check of another source is superseded first, so its cleanup cannot drop the claim taken below.
        checkJob?.cancel()
        if (!claimForegroundLocked()) {
            state.set(VoiceState.ERROR)
            scheduleRetryLocked()
            return
        }
        checkedSource = mic
        setCheckLocked(MicrophoneCheck.RUNNING, null)
        state.set(VoiceState.IDLE)
        val probe = MicrophoneSelfCheck()
        val lease = mic.lease(MicPurpose.CALIBRATION, consumer = probe)
        val job = scope.launch { withTimeoutOrNull(checkTimeoutMs) { while (!probe.complete) delay(CHECK_POLL_MS) } }
        checkJob = job
        // Cleanup belongs to completion, not to the body: a check cancelled before its coroutine ever
        // runs (voice turned off, or shut down, straight after arming) must still release its lease and
        // the foreground claim. The handler runs on whichever thread completes or cancels the job.
        job.invokeOnCompletion { cause ->
            val detail = (mic.state.value as? MicState.Error)?.reason
            lease.close()
            synchronized(lock) {
                if (checkJob === job) checkJob = null
                if (cause == null && armed && checkedSource === mic && muted()) {
                    // Muted while it listened: the verdict measured the mute, so it is not kept.
                    abandonCheckLocked()
                } else if (cause == null && armed && checkedSource === mic) {
                    val verdict = probe.verdict()
                    setCheckLocked(verdict, detail.takeIf { verdict == MicrophoneCheck.NO_AUDIO })
                    if (verdict == MicrophoneCheck.PASSED && runJob == null) {
                        armEngineLocked(settings())
                    } else if (verdict.failed) {
                        state.set(VoiceState.ERROR)
                    }
                }
                if (checkJob == null && runJob == null && wakeLease == null) releaseForegroundLocked()
            }
        }
    }

    /** Forget the check and its verdict, cancelling one in progress, so the next arming checks afresh. */
    private fun abandonCheckLocked() {
        checkJob?.cancel()
        checkJob = null
        checkedSource = null
        setCheckLocked(MicrophoneCheck.NOT_RUN, null)
    }

    private fun setCheckLocked(next: MicrophoneCheck, detail: String?) {
        if (check == next && checkDetail == detail) return
        check = next
        checkDetail = detail
        onMicrophoneStatus()
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
        private const val CHECK_POLL_MS = 50L
        const val SILENT_REASON = "the microphone recorded only silence when the panel checked it"
        const val NO_AUDIO_REASON = "the microphone delivered no audio when the panel checked it"
        const val MUTED_REASON = "the microphone is muted"
    }
}
