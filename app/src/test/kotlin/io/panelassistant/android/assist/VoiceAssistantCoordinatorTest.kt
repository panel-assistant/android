package io.panelassistant.android.assist

import io.panelassistant.android.audio.FakeMicrophoneSource
import io.panelassistant.android.audio.MicPurpose
import io.panelassistant.android.audio.MicrophoneCheck
import io.panelassistant.android.audio.MicrophonePresence
import io.panelassistant.android.audio.MicrophoneSelfCheck
import io.panelassistant.android.audio.MicrophoneStatus
import io.panelassistant.android.audio.MicrophoneSource
import io.panelassistant.android.audio.PcmConsumer
import io.panelassistant.android.audio.PcmFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class VoiceAssistantCoordinatorTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mic = FakeMicrophoneSource()
    private var sourceRequests = 0
    private val state = VoiceStateAuthority()
    private val foregroundCalls = CopyOnWriteArrayList<Boolean>()
    private var foregroundAccepts = true
    private var settings = VoiceSettings(enabled = true, wakeWords = listOf("okay_nabu"), pipelines = mapOf("hey_jarvis" to "pipe-2"))
    private var presence = MicrophonePresence.PROVEN
    private var muted = false
    private var muteReader: io.panelassistant.android.audio.MicrophoneMute? = null
    private var statusChanges = 0

    private class FakeEngine(val onActivation: (WakeWordActivation) -> Unit) : WakeWordEngine {
        var closed = false
        override fun onFrame(frame: PcmFrame) {}
        override fun close() { closed = true }
    }

    private val engines = CopyOnWriteArrayList<FakeEngine>()
    private var engineAvailable = true
    private val engineFactory = WakeWordEngineFactory { _, onActivation ->
        if (!engineAvailable) null else FakeEngine(onActivation).also(engines::add)
    }

    /** Gates a run's unwinding, modelling a client whose teardown is NonCancellable. */
    private var teardownGate: CompletableDeferred<Unit>? = null

    private inner class ScriptedRunner : AssistRunner {
        val requests = CopyOnWriteArrayList<VoiceTurnRequest>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<AssistOutcome>()
        /** Completed once this run has fully unwound, including its capture attachment. */
        val finished = CompletableDeferred<Unit>()
        var attachedPurpose: MicPurpose? = null
        private var attachment: AutoCloseable? = null
        private var playbackHandle: AssistPlayback? = null

        /** Close the capture the way the real client does when the utterance ends. */
        fun finishCapture() {
            attachment?.close()
            attachment = null
        }

        /** Play a reply the way the real client does once Home Assistant returns one. */
        fun speak(url: String = "/api/tts_proxy/x.mp3") = runBlocking { playbackHandle?.play(url) }

        override suspend fun run(
            request: VoiceTurnRequest,
            attachAudio: (PcmConsumer) -> AutoCloseable,
            playback: AssistPlayback,
        ): AssistOutcome {
            requests += request
            attachment = attachAudio(object : PcmConsumer { override fun onFrame(frame: PcmFrame) {} })
            playbackHandle = playback
            started.complete(Unit)
            return try {
                release.await()
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    teardownGate?.await()
                    attachment?.close()
                    attachment = null
                }
                finished.complete(Unit)
            }
        }
    }

    private val runners = CopyOnWriteArrayList<ScriptedRunner>()
    private val played = CopyOnWriteArrayList<String>()
    private val pausedWhilePlaying = CopyOnWriteArrayList<Boolean>()

    /** The `listen_after` of each streamed announcement's `voice_stream_end`. */
    @Volatile private var streamListenAfter = true
    private val playback = object : AssistPlayback {
        override suspend fun play(url: String) {
            played += url
            mic.leases.firstOrNull()?.let { pausedWhilePlaying += it.paused }
            state.set(VoiceState.RESPONDING)
        }

        override suspend fun playStream(streamId: String): Boolean {
            play("stream@$streamId")
            return streamListenAfter
        }
    }

    /** Each cue's wake word, with the phase the panel was in when it was cued. */
    private val cues = java.util.Collections.synchronizedList(mutableListOf<Pair<String?, VoiceState>>())

    private var source: FakeMicrophoneSource = mic
    private var checkTimeoutMs = 2_000L

    private fun coordinator(retryMs: Long = 60_000, maxTurns: Int = 5, on: CoroutineScope = scope) = VoiceAssistantCoordinator(
        scope = on,
        settings = { settings },
        microphone = { presence },
        source = { sourceRequests += 1; source },
        engineFactory = engineFactory,
        runnerFactory = { ScriptedRunner().also(runners::add) },
        playback = playback,
        foregroundMicrophone = { active -> foregroundCalls += active; foregroundAccepts },
        state = state,
        foregroundRetryMs = retryMs,
        maxConversationTurns = maxTurns,
        attention = { wakeWordId -> cues += wakeWordId to state.current() },
        onMicrophoneStatus = { statusChanges += 1 },
        checkTimeoutMs = checkTimeoutMs,
        muted = { muteReader?.muted ?: muted },
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun awaitRunner(index: Int): ScriptedRunner = runBlocking {
        withTimeout(2_000) {
            while (runners.size <= index) kotlinx.coroutines.delay(5)
            runners[index].also { it.started.await() }
        }
    }

    /** Waits for a condition but never fails on it, so the assertion that follows reports the defect. */
    private fun settleUntil(condition: () -> Boolean) = runBlocking {
        withTimeoutOrNull(2_000) { while (!condition()) kotlinx.coroutines.delay(5) }
    }

    /** Waits for the in-flight run to unwind, so a phase can be asserted rather than waited for. */
    private fun awaitRunFinished(c: VoiceAssistantCoordinator) = runBlocking {
        withTimeout(2_000) { while (c.running) kotlinx.coroutines.delay(5) }
    }

    private fun awaitState(expected: VoiceState) = runBlocking {
        withTimeout(2_000) { while (state.current() != expected) kotlinx.coroutines.delay(5) }
    }

    @Test
    fun `start arms the engine on a wake-word lease and reports idle`() {
        val c = coordinator()
        c.start()
        assertEquals(1, engines.size)
        assertEquals(listOf(MicPurpose.WAKE_WORD), mic.activeLeases.map { it.purpose })
        assertEquals(VoiceState.IDLE, state.current())
        assertEquals(listOf(true), foregroundCalls)
    }

    /** Enough frames to settle and then judge: a room's floor that moves, or digital silence. */
    private fun FakeMicrophoneSource.speakForCheck(live: Boolean) {
        val total = MicrophoneSelfCheck.SETTLE_FRAMES + MicrophoneSelfCheck.MEASURE_FRAMES
        repeat(total) { n ->
            pushFrame(ShortArray(MicrophoneSource.SAMPLES_PER_FRAME) { i -> if (live) ((n * 7 + i * 13) % 400 - 200).toShort() else 0 })
        }
    }

    @Test
    fun `a proven microphone arms at once without a capture check`() {
        val c = coordinator()
        c.start()
        assertEquals(listOf(MicPurpose.WAKE_WORD), mic.leases.map { it.purpose })
        assertEquals(MicrophoneStatus(MicrophonePresence.PROVEN, MicrophoneCheck.NOT_RUN), c.microphoneStatus())
    }

    @Test
    fun `an unproven microphone that hears the room passes its check and then arms the wake word`() {
        presence = MicrophonePresence.UNPROVEN
        val c = coordinator()
        c.start()
        assertTrue("nothing listens before the check passes", engines.isEmpty())
        assertEquals(listOf(MicPurpose.CALIBRATION), mic.activeLeases.map { it.purpose })
        assertEquals(MicrophoneCheck.RUNNING, c.microphoneStatus().check)
        mic.speakForCheck(live = true)
        settleUntil { engines.isNotEmpty() }
        assertEquals(1, engines.size)
        assertEquals(listOf(MicPurpose.WAKE_WORD), mic.activeLeases.map { it.purpose })
        assertEquals(MicrophoneStatus(MicrophonePresence.UNPROVEN, MicrophoneCheck.PASSED), c.microphoneStatus())
        assertEquals(VoiceState.IDLE, state.current())
        assertTrue(statusChanges >= 2)
        // A settings change rearms the listener without checking the same microphone again.
        c.start()
        assertEquals(1, mic.leases.count { it.purpose == MicPurpose.CALIBRATION })
    }

    @Test
    fun `an unproven microphone that records silence fails its check, says why and does not listen`() {
        presence = MicrophonePresence.UNPROVEN
        val c = coordinator()
        c.start()
        mic.speakForCheck(live = false)
        settleUntil { c.microphoneStatus().check == MicrophoneCheck.SILENT }
        assertEquals(MicrophoneStatus(MicrophonePresence.UNPROVEN, MicrophoneCheck.SILENT), c.microphoneStatus())
        assertTrue(engines.isEmpty())
        settleUntil { mic.activeLeases.isEmpty() && foregroundCalls.lastOrNull() == false }
        assertTrue("the check releases the microphone", mic.activeLeases.isEmpty())
        assertEquals(listOf(true, false), foregroundCalls)
        assertEquals(VoiceState.ERROR, state.current())
        assertEquals(VoiceTestTrigger.Result.Unavailable(VoiceAssistantCoordinator.SILENT_REASON), c.trigger())
        // Rearming for another setting does not quietly retry; turning voice off and on does.
        c.start()
        assertEquals(1, mic.leases.count { it.purpose == MicPurpose.CALIBRATION })
        settings = settings.copy(enabled = false)
        c.start()
        assertEquals(MicrophoneCheck.NOT_RUN, c.microphoneStatus().check)
        settings = settings.copy(enabled = true)
        c.start()
        assertEquals(2, mic.leases.count { it.purpose == MicPurpose.CALIBRATION })
        mic.speakForCheck(live = true)
        settleUntil { engines.isNotEmpty() }
        assertEquals(MicrophoneCheck.PASSED, c.microphoneStatus().check)
    }

    /** Work queued for [parkedScope], run only by [drainParked], so a check can be cancelled before its body starts. */
    private val parked = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()

    private fun parkedScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + java.util.concurrent.Executor { parked.add(it) }.asCoroutineDispatcher())

    private fun drainParked() {
        while (true) (parked.poll() ?: return).run()
    }

    @Test
    fun `voice turned off before the check gets a thread still releases the microphone and the claim`() {
        presence = MicrophonePresence.UNPROVEN
        val c = coordinator(on = parkedScope())
        c.start()
        assertEquals(listOf(MicPurpose.CALIBRATION), mic.activeLeases.map { it.purpose })
        settings = settings.copy(enabled = false)
        c.start()
        drainParked()
        assertTrue("the check's lease is released", mic.activeLeases.isEmpty())
        assertEquals(listOf(true, false), foregroundCalls)
        assertEquals(MicrophoneCheck.NOT_RUN, c.microphoneStatus().check)
    }

    @Test
    fun `shutdown waits for a pending check, which releases the microphone and the claim once it runs`() {
        presence = MicrophonePresence.UNPROVEN
        val c = coordinator(on = parkedScope())
        c.start()
        assertFalse("shutdown does not report a drain the check has not done", c.shutdown(50))
        drainParked()
        assertTrue("the check's lease is released", mic.activeLeases.isEmpty())
        assertEquals(listOf(true, false), foregroundCalls)
    }

    @Test
    fun `an unproven microphone that delivers nothing reports no audio with the capture's own error`() {
        presence = MicrophonePresence.UNPROVEN
        checkTimeoutMs = 100
        val c = coordinator()
        c.start()
        mic.failWith("AudioRecord could not be opened")
        settleUntil { c.microphoneStatus().check == MicrophoneCheck.NO_AUDIO }
        assertEquals(
            MicrophoneStatus(MicrophonePresence.UNPROVEN, MicrophoneCheck.NO_AUDIO, "AudioRecord could not be opened"),
            c.microphoneStatus(),
        )
        assertTrue(engines.isEmpty())
        assertEquals(VoiceTestTrigger.Result.Unavailable(VoiceAssistantCoordinator.NO_AUDIO_REASON), c.trigger())
    }

    @Test
    fun `another audio source is another microphone to check`() {
        presence = MicrophonePresence.UNPROVEN
        val c = coordinator()
        c.start()
        mic.speakForCheck(live = false)
        settleUntil { c.microphoneStatus().check == MicrophoneCheck.SILENT }
        val other = FakeMicrophoneSource()
        source = other
        c.start()
        assertEquals(listOf(MicPurpose.CALIBRATION), other.activeLeases.map { it.purpose })
        other.speakForCheck(live = true)
        settleUntil { engines.isNotEmpty() }
        assertEquals(MicrophoneCheck.PASSED, c.microphoneStatus().check)
        assertEquals(listOf(MicPurpose.WAKE_WORD), other.activeLeases.map { it.purpose })
    }

    @Test
    fun `the check reports only for an unproven microphone`() {
        val c = coordinator()
        MicrophonePresence.entries.forEach { each ->
            presence = each
            assertEquals(MicrophoneStatus(each, MicrophoneCheck.NOT_RUN), c.microphoneStatus())
        }
    }

    @Test
    fun `a muted unproven microphone is not judged by its silence and is checked once unmuted`() {
        presence = MicrophonePresence.UNPROVEN
        muted = true
        val c = coordinator()
        c.start()
        assertTrue("a muted microphone is not checked", mic.leases.none { it.purpose == MicPurpose.CALIBRATION })
        assertEquals(MicrophoneStatus(MicrophonePresence.UNPROVEN, muted = true), c.microphoneStatus())
        assertEquals(VoiceState.IDLE, state.current())
        assertEquals(VoiceTestTrigger.Result.Unavailable(VoiceAssistantCoordinator.MUTED_REASON), c.trigger())
        muted = false
        c.microphoneMuteChanged()
        assertEquals(listOf(MicPurpose.CALIBRATION), mic.activeLeases.map { it.purpose })
        mic.speakForCheck(live = true)
        settleUntil { engines.isNotEmpty() }
        assertEquals(MicrophoneStatus(MicrophonePresence.UNPROVEN, MicrophoneCheck.PASSED), c.microphoneStatus())
    }

    @Test
    fun `muting withdraws a failed check, and unmuting checks again`() {
        presence = MicrophonePresence.UNPROVEN
        val c = coordinator()
        c.start()
        mic.speakForCheck(live = false)
        settleUntil { state.current() == VoiceState.ERROR }
        assertEquals(MicrophoneCheck.SILENT, c.microphoneStatus().check)
        val before = statusChanges
        muted = true
        c.microphoneMuteChanged()
        assertEquals(MicrophoneStatus(MicrophonePresence.UNPROVEN, muted = true), c.microphoneStatus())
        assertEquals("muting is not an error", VoiceState.IDLE, state.current())
        assertTrue("Home Assistant is told", statusChanges > before)
        muted = false
        c.microphoneMuteChanged()
        assertEquals(2, mic.leases.count { it.purpose == MicPurpose.CALIBRATION })
        mic.speakForCheck(live = true)
        settleUntil { engines.isNotEmpty() }
        assertEquals(MicrophoneCheck.PASSED, c.microphoneStatus().check)
    }

    @Test
    fun `a mute pressed while the check listens discards what it heard`() {
        presence = MicrophonePresence.UNPROVEN
        val c = coordinator()
        c.start()
        muted = true
        mic.speakForCheck(live = false)
        settleUntil { mic.activeLeases.isEmpty() }
        assertTrue(mic.activeLeases.isEmpty())
        assertEquals(MicrophoneStatus(MicrophonePresence.UNPROVEN, muted = true), c.microphoneStatus())
        assertEquals(VoiceState.IDLE, state.current())
        muted = false
        c.microphoneMuteChanged()
        assertEquals(MicrophoneStatus(MicrophonePresence.UNPROVEN, MicrophoneCheck.RUNNING), c.microphoneStatus())
    }

    @Test
    fun `a muted proven microphone stays armed and says it is muted`() {
        val c = coordinator()
        c.start()
        muted = true
        c.microphoneMuteChanged()
        assertEquals(MicrophoneStatus(MicrophonePresence.PROVEN, muted = true), c.microphoneStatus())
        assertEquals(listOf(MicPurpose.WAKE_WORD), mic.activeLeases.map { it.purpose })
        assertEquals(VoiceState.IDLE, state.current())
        muted = false
        c.microphoneMuteChanged()
        assertEquals(MicrophoneStatus(MicrophonePresence.PROVEN), c.microphoneStatus())
        assertEquals(1, engines.size)
    }

    @Test
    fun `absent, failed capture, muted and unmuted are four different answers`() {
        val c = coordinator()
        presence = MicrophonePresence.ABSENT
        muted = true
        val absent = c.microphoneStatus()
        presence = MicrophonePresence.UNPROVEN
        muted = false
        c.start()
        mic.speakForCheck(live = false)
        settleUntil { c.microphoneStatus().check == MicrophoneCheck.SILENT }
        val failed = c.microphoneStatus()
        muted = true
        c.microphoneMuteChanged()
        val mutedStatus = c.microphoneStatus()
        muted = false
        c.microphoneMuteChanged()
        mic.speakForCheck(live = true)
        settleUntil { c.microphoneStatus().check == MicrophoneCheck.PASSED }
        val unmuted = c.microphoneStatus()
        assertEquals(MicrophoneStatus(MicrophonePresence.ABSENT), absent)
        assertEquals(MicrophoneStatus(MicrophonePresence.UNPROVEN, MicrophoneCheck.SILENT), failed)
        assertEquals(MicrophoneStatus(MicrophonePresence.UNPROVEN, muted = true), mutedStatus)
        assertEquals(MicrophoneStatus(MicrophonePresence.UNPROVEN, MicrophoneCheck.PASSED), unmuted)
        assertEquals(4, setOf(absent, failed, mutedStatus, unmuted).size)
        // Only the failed capture is a failed check, so only it raises Home Assistant's Repair.
        assertEquals(listOf(failed), listOf(absent, failed, mutedStatus, unmuted).filter { it.check.failed })
    }

    @Test
    fun `a restarted reader corrects a mute left on the panel by the one before it, without announcing it`() {
        // Muted, the service stops (its reader closes), the switch is unmuted meanwhile, and a new service in
        // the same process starts a new reader: the panel must not keep showing the old reader's mute.
        val c = coordinator()
        c.start()
        val shown = java.util.Collections.synchronizedList(mutableListOf<Pair<Boolean, Boolean>>())
        VoiceAttention.muteShown = { m, announce -> shown += m to announce }
        try {
            var hardware = true
            val first = io.panelassistant.android.audio.MicrophoneMute(read = { hardware }, publish = publishMicrophoneMute { c })
            muteReader = first
            first.refresh()
            assertEquals(true, VoiceAttention.muted)
            assertEquals(true, c.microphoneStatus().muted)
            first.close()
            hardware = false

            val second = io.panelassistant.android.audio.MicrophoneMute(read = { hardware }, publish = publishMicrophoneMute { c })
            muteReader = second
            second.refresh()
            assertEquals("the panel's mute follows the new reading", false, VoiceAttention.muted)
            assertEquals(false to false, shown.last())
            assertEquals(MicrophoneStatus(MicrophonePresence.PROVEN), c.microphoneStatus())

            hardware = true
            second.refresh()
            second.refresh()
            assertEquals("a press during one reader's life is announced once", true to true, shown.last())
            assertEquals(listOf(true to false, false to false, true to true), shown.toList())
        } finally {
            VoiceAttention.muteShown = null
            VoiceAttention.microphoneMuted(false, announce = false)
        }
    }

    @Test
    fun `disabled settings stand the coordinator down and release the microphone`() {
        val c = coordinator()
        c.start()
        settings = settings.copy(enabled = false)
        c.start()
        assertTrue(mic.activeLeases.isEmpty())
        assertTrue(engines.single().closed)
        assertEquals(VoiceState.OFF, state.current())
        assertEquals(listOf(true, false), foregroundCalls)
    }

    @Test
    fun `no microphone capability means nothing is armed`() {
        presence = MicrophonePresence.ABSENT
        val c = coordinator()
        c.start()
        assertTrue(engines.isEmpty())
        assertTrue(mic.leases.isEmpty())
        assertEquals(VoiceState.OFF, state.current())
    }

    @Test
    fun `an activation pauses the wake lease, runs a turn for its wake word, then resumes`() {
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("hey_jarvis", "hey jarvis", heardAtNs = 42L))
        val runner = awaitRunner(0)
        assertEquals(VoiceTurnRequest("hey_jarvis", continued = false, heardAtNs = 42L), runner.requests.single())
        assertTrue(mic.leases[0].paused)
        assertEquals(MicPurpose.ASSIST, mic.leases[1].purpose)
        assertEquals(VoiceState.LISTENING, state.current())
        runner.release.complete(AssistOutcome(sttText = "turn on the lights"))
        awaitState(VoiceState.IDLE)
        assertFalse(mic.leases[0].paused)
        assertTrue(mic.leases[1].closed)
    }

    /**
     * The gain setting exists because these panels expose no platform noise suppression or automatic
     * gain control, so a far-field panel transcribes badly even when it wakes reliably. It must reach
     * the pipeline audio and nothing else: microWakeWord's frontend already applies PCAN adaptive gain
     * in its own feature domain, and amplifying its input would move it off the levels the models were
     * trained on and cost detections to clipping. So the two leases must see different signals for the
     * same utterance, and this is the test that fails if a future change collapses them.
     */
    @Test
    fun `configured gain amplifies the pipeline audio and never the wake-word listener`() {
        settings = settings.copy(micGainDb = 12)
        val c = coordinator()
        c.start()

        val wakeLease = mic.leases.single { it.purpose == MicPurpose.WAKE_WORD }
        val wakeDelivered = ShortArray(1) { 1000 }
        wakeLease.consumer.onFrame(PcmFrame(wakeDelivered, timestampNs = 1L))

        engines.single().onActivation(WakeWordActivation("hey_jarvis", "hey jarvis"))
        awaitRunner(0)
        val assistLease = mic.leases.single { it.purpose == MicPurpose.ASSIST }
        val delivered = ShortArray(1) { 1000 }
        assistLease.consumer.onFrame(PcmFrame(delivered, timestampNs = 2L))

        assertTrue("pipeline audio should be amplified, got ${delivered[0]}", delivered[0] > 3000)
        assertEquals("the wake-word listener must see the unamplified signal", 1000, wakeDelivered[0].toInt())
    }

    @Test
    fun `zero gain leaves the pipeline audio exactly as captured`() {
        settings = settings.copy(micGainDb = 0)
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("hey_jarvis", "hey jarvis"))
        awaitRunner(0)
        val assistLease = mic.leases.single { it.purpose == MicPurpose.ASSIST }
        val delivered = ShortArray(1) { 1000 }
        assistLease.consumer.onFrame(PcmFrame(delivered, timestampNs = 2L))
        assertEquals(1000, delivered[0].toInt())
    }

    @Test
    fun `a second activation during a run is ignored`() {
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        val runner = awaitRunner(0)
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        assertEquals(VoiceTestTrigger.Result.Refused("a voice run is already in progress"), c.trigger())
        assertEquals(1, runners.size)
        runner.release.complete(AssistOutcome())
        awaitState(VoiceState.IDLE)
    }

    @Test
    fun `a continued conversation keeps its wake word, is not woken again, and stops at the turn bound`() {
        val c = coordinator(maxTurns = 2)
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        val first = awaitRunner(0)
        first.release.complete(AssistOutcome(conversationId = "conv-1", continueConversation = true))
        val second = awaitRunner(1)
        assertEquals(VoiceTurnRequest("okay_nabu", continued = true), second.requests.single())
        second.release.complete(AssistOutcome(conversationId = "conv-1", continueConversation = true))
        awaitState(VoiceState.IDLE)
        assertEquals(2, runners.size)
    }

    @Test
    fun `a failed run reports error, a silent duplicate does not`() {
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        awaitRunner(0).release.complete(AssistOutcome(error = AssistError("timeout", "no reply")))
        // Wait for the run to finish, then assert the phase. Waiting for the phase itself would let a
        // coordinator that never reports the failure fail by timing out rather than by being wrong.
        awaitRunFinished(c)
        assertEquals("a failed run must stay visibly failed", VoiceState.ERROR, state.current())
        assertFalse(mic.leases[0].paused)
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        val second = awaitRunner(1)
        assertEquals(VoiceState.LISTENING, state.current())
        second.release.complete(AssistOutcome(error = AssistError(AssistError.DUPLICATE_WAKE_UP, "dup")))
        awaitRunFinished(c)
        assertEquals("a duplicate wake-up is not a failure the panel reports", VoiceState.IDLE, state.current())
    }

    @Test
    fun `tap to talk without an engine leases the microphone only for the run`() {
        engineAvailable = false
        val c = coordinator()
        c.start()
        assertTrue(mic.leases.isEmpty())
        assertEquals(VoiceState.IDLE, state.current())
        assertTrue("no lease, so no foreground claim", foregroundCalls.isEmpty())
        assertEquals(VoiceTestTrigger.Result.Accepted, c.trigger())
        val runner = awaitRunner(0)
        assertEquals(listOf(MicPurpose.ASSIST), mic.activeLeases.map { it.purpose })
        assertEquals(listOf(true), foregroundCalls)
        runner.release.complete(AssistOutcome())
        awaitState(VoiceState.IDLE)
        assertTrue(mic.activeLeases.isEmpty())
        assertEquals(listOf(true, false), foregroundCalls)
    }

    @Test
    fun `a refused foreground claim reports error, holds no lease and is retried`() {
        foregroundAccepts = false
        val c = coordinator(retryMs = 20)
        c.start()
        assertEquals(VoiceState.ERROR, state.current())
        assertTrue(mic.leases.isEmpty())
        foregroundAccepts = true
        awaitState(VoiceState.IDLE)
        assertEquals(1, mic.activeLeases.size)
    }

    @Test
    fun `trigger is refused while disabled and unavailable without a microphone`() {
        val c = coordinator()
        settings = settings.copy(enabled = false)
        assertEquals(VoiceTestTrigger.Result.Refused("voice assistant is disabled"), c.trigger())
        settings = settings.copy(enabled = true)
        presence = MicrophonePresence.ABSENT
        assertEquals(VoiceTestTrigger.Result.Unavailable("this panel has no microphone"), c.trigger())
    }

    @Test
    fun `shutdown cancels a run, releases everything and reports off`() {
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        awaitRunner(0)
        assertTrue(c.shutdown(1_000))
        awaitState(VoiceState.OFF)
        runBlocking { withTimeout(2_000) { while (mic.activeLeases.isNotEmpty()) kotlinx.coroutines.delay(5) } }
        assertTrue(engines.single().closed)
        assertEquals(false, foregroundCalls.last())
        c.start()
        assertTrue(mic.activeLeases.isEmpty())
    }

    @Test
    fun `shutdown waits for an in-flight run to unwind before reporting complete`() {
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        val runner = awaitRunner(0)
        // The run only finishes once released, so a shutdown that did not wait would report true here.
        scope.launch {
            kotlinx.coroutines.delay(150)
            runner.release.complete(AssistOutcome())
        }
        assertTrue("shutdown must wait for the run", c.shutdown(5_000))
        assertFalse(c.running)
        assertTrue(mic.activeLeases.isEmpty())
    }

    @Test
    fun `shutdown reports incomplete when a run does not finish within the deadline`() {
        val gate = CompletableDeferred<Unit>()
        teardownGate = gate
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        awaitRunner(0)
        // The run cannot finish unwinding while the gate is closed, so teardown must report the truth.
        assertFalse("a run still unwinding is not a completed teardown", c.shutdown(200))
        gate.complete(Unit)
    }

    @Test
    fun `Assist shutdown leaves room stream capture alive on the shared fanout`() {
        val frames = java.util.concurrent.LinkedBlockingQueue<ShortArray>()
        val delivered = java.util.concurrent.CountDownLatch(1)
        val fanout = io.panelassistant.android.audio.MicrophoneFanOut(deviceFactory = {
            object : io.panelassistant.android.audio.PcmCaptureDevice {
                override fun open() = true
                override fun read(buffer: ShortArray, offset: Int, count: Int): Int {
                    val frame = frames.take()
                    if (frame.isEmpty()) return 0
                    frame.copyInto(buffer, offset, 0, count)
                    return count
                }
                override fun stop() { frames.offer(shortArrayOf()) }
                override fun close() {}
            }
        })
        val room = fanout.lease(MicPurpose.STREAM, consumer = object : PcmConsumer {
            override fun onFrame(frame: PcmFrame) { delivered.countDown() }
        })
        val c = VoiceAssistantCoordinator(
            scope = scope, settings = { settings }, microphone = { MicrophonePresence.PROVEN },
            source = { fanout }, engineFactory = engineFactory,
            runnerFactory = { ScriptedRunner() }, playback = playback,
            foregroundMicrophone = { true }, state = state,
        )
        try {
            c.start()
            assertTrue(c.shutdown(1_000))
            frames.offer(ShortArray(160) { 42 })
            assertTrue("room audio still arrives after Assist shuts down", delivered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(room.active)
        } finally {
            room.close()
            fanout.shutdown(1_000)
        }
    }

    @Test
    fun `shutdown never asks for a microphone source that was never obtained`() {
        settings = settings.copy(enabled = false)
        val c = coordinator()
        c.start()
        val before = sourceRequests
        assertTrue(c.shutdown(1_000))
        assertEquals("teardown must not open a microphone to close it", before, sourceRequests)
    }

    @Test
    fun `a settings change during a run is applied only once the run drains`() {
        val c = coordinator()
        c.start()
        val first = engines.single()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        val runner = awaitRunner(0)
        assertTrue("the wake lease stands down for the run", mic.leases[0].paused)
        // A settings write lands mid-run. Reconfiguring here would close the paused wake lease and
        // open an unpaused one alongside the run's own, putting two consumers on one capture.
        settings = settings.copy(wakeWords = listOf("hey_jarvis"))
        c.start()
        assertEquals("no listener may be rebuilt underneath a run", 1, engines.size)
        assertFalse("the retired listener must not be closed mid-run", first.closed)
        assertEquals("no second wake lease may exist during a run", 1, mic.leases.count { it.purpose == MicPurpose.WAKE_WORD })
        runner.release.complete(AssistOutcome())
        awaitRunFinished(c)
        settleUntil { engines.size >= 2 }
        assertEquals("the deferred change applies when the run drains", 2, engines.size)
        assertTrue("the superseded listener is retired with it", first.closed)
    }

    @Test
    fun `the foreground claim is held until the run's capture attachment closes`() {
        val gate = CompletableDeferred<Unit>()
        teardownGate = gate
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        awaitRunner(0)
        assertEquals(listOf(true), foregroundCalls)
        // Standing down while the run is still unwinding must not drop the claim: the run's capture
        // attachment is still open, so the microphone is still being read.
        c.stop()
        assertFalse("the claim must outlive the capture it covers", foregroundCalls.contains(false))
        gate.complete(Unit)
        // Give the unwinding run a bounded chance to release, then assert. Swallowing the timeout is
        // what makes a coordinator that never releases fail on the assertion rather than by timing out.
        settleUntil { foregroundCalls.size >= 2 }
        assertEquals("the claim is released once the attachment has closed", listOf(true, false), foregroundCalls)
    }

    @Test
    fun `no run is admitted while a retiring run is still draining`() {
        val gate = CompletableDeferred<Unit>()
        teardownGate = gate
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        awaitRunner(0)
        // Stand down: the run is cancelled but still holds capture behind the gate. Admitting a
        // replacement here is what let a retiring run release a claim and publish a phase that
        // belonged to someone else, so the seat stays taken until its cleanup drains.
        c.stop()
        c.start()
        assertEquals(
            VoiceTestTrigger.Result.Refused("a voice run is already in progress"),
            c.trigger(),
        )
        assertEquals("no replacement may be admitted mid-drain", 1, runners.size)
        gate.complete(Unit)
        settleUntil { runners[0].finished.isCompleted && !c.running }
        assertFalse("the seat is released once cleanup drains", c.running)
        // Only now is the panel free to serve another request.
        assertEquals(VoiceTestTrigger.Result.Accepted, c.trigger())
        awaitRunner(1).release.complete(AssistOutcome())
    }

    @Test
    fun `a refused microphone claim on press-to-talk is reported as such, not as busy`() {
        engineAvailable = false
        foregroundAccepts = false
        val c = coordinator(retryMs = 20)
        c.start()
        val result = c.trigger()
        assertTrue("a refused claim is not a busy panel: $result", result is VoiceTestTrigger.Result.Unavailable)
        val reason = (result as VoiceTestTrigger.Result.Unavailable).reason
        assertTrue("the reason must name the microphone claim: $reason", reason.contains("microphone"))
        // It must tell the operator to try again rather than promise a retry: nothing retains the
        // request, and no wake-word listener ships that would rearm the panel on its own.
        assertTrue("the reason must ask for another attempt: $reason", reason.contains("try again"))
        assertEquals(VoiceState.ERROR, state.current())
        assertTrue("no run may have started", runners.isEmpty())
        // A later attempt succeeds once the platform allows the claim; nothing was queued meanwhile.
        foregroundAccepts = true
        assertEquals(VoiceTestTrigger.Result.Accepted, c.trigger())
        awaitRunner(0)
        assertEquals("only the accepted attempt runs; the refused one was not queued", 1, runners.size)
    }

    @Test
    fun `a hit from a replaced listener cannot start a run`() {
        val c = coordinator()
        c.start()
        val stale = engines.single()
        settings = settings.copy(wakeWords = listOf("hey_jarvis"))
        c.start()
        assertEquals("the listener was replaced", 2, engines.size)
        stale.onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        assertTrue("a superseded listener must not start a run", runners.isEmpty())
        assertFalse(c.running)
    }

    @Test
    fun `a hit arriving after stop cannot start a run`() {
        val c = coordinator()
        c.start()
        val engine = engines.single()
        c.stop()
        engine.onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        assertTrue("a hit after stand-down must not start a run", runners.isEmpty())
        assertEquals(VoiceState.OFF, state.current())
    }

    @Test
    fun `the panel reports processing when it stops listening and responding when it speaks`() {
        val seen = CopyOnWriteArrayList<VoiceState>()
        state.setChangeListener { seen += state.current() }
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("okay_nabu", "okay nabu"))
        val runner = awaitRunner(0)
        runner.finishCapture()
        runner.speak()
        runner.release.complete(AssistOutcome())
        awaitRunFinished(c)
        assertTrue("closing capture must report processing: $seen", seen.contains(VoiceState.PROCESSING))
        assertTrue("playing the reply must report responding: $seen", seen.contains(VoiceState.RESPONDING))
    }

    @Test
    fun `settings parsing tolerates malformed json and keeps every wake word`() {
        val parsed = VoiceSettings.parse(true, "[\"okay_nabu\",\"hey_jarvis\",\"alexa\"]", "{\"hey_jarvis\":\"p2\",\"alexa\":\"\"}")
        assertEquals(listOf("okay_nabu", "hey_jarvis", "alexa"), parsed.wakeWords)
        assertEquals(mapOf("hey_jarvis" to "p2", "alexa" to ""), parsed.pipelines)
        val broken = VoiceSettings.parse(true, "not json", "[1,2]")
        assertTrue(broken.wakeWords.isEmpty())
        assertTrue(broken.pipelines.isEmpty())
    }

    @Test
    fun `an announcement plays its chime then its message with the listener paused, then reports it played`() {
        val c = coordinator()
        c.start()
        val done = CompletableDeferred<Unit>()
        c.announce(VoiceAnnouncement("message.mp3", "chime.mp3", listenAfter = false) { done.complete(Unit) })
        runBlocking { withTimeout(2_000) { done.await() } }
        awaitRunFinished(c)
        assertEquals(listOf("chime.mp3", "message.mp3"), played)
        assertEquals("the panel must not hear itself", listOf(true, true), pausedWhilePlaying)
        assertFalse(mic.leases[0].paused)
        assertTrue("an announcement alone asks nothing of Home Assistant", runners.isEmpty())
    }

    @Test
    fun `a streamed announcement plays the stream alone with the listener paused, reports it played, then listens`() {
        val c = coordinator()
        c.start()
        val done = CompletableDeferred<Unit>()
        c.announce(VoiceAnnouncement("question.mp3", "chime.mp3", listenAfter = true, done = { done.complete(Unit) }, stream = "s42"))
        val runner = awaitRunner(0)
        assertTrue(done.isCompleted)
        assertEquals("the stream carries chime and speech; neither URL is fetched", listOf("stream@s42"), played)
        assertEquals("the panel must not hear itself", listOf(true), pausedWhilePlaying)
        assertEquals(VoiceTurnRequest(null), runner.requests.single())
        runner.release.complete(AssistOutcome())
        awaitState(VoiceState.IDLE)
    }

    @Test
    fun `a streamed announcement whose end says not to listen leaves the panel idle`() {
        streamListenAfter = false
        val c = coordinator()
        c.start()
        val done = CompletableDeferred<Unit>()
        c.announce(VoiceAnnouncement("question.mp3", null, listenAfter = true, done = { done.complete(Unit) }, stream = "s9"))
        runBlocking { withTimeout(5_000L) { done.await() } }
        awaitState(VoiceState.IDLE)
        assertEquals(listOf("stream@s9"), played)
        assertTrue("Panel Assistant's end withdrew the listen", runners.isEmpty())
    }

    @Test
    fun `start conversation listens after the announcement, with no wake word`() {
        val c = coordinator()
        c.start()
        val done = CompletableDeferred<Unit>()
        c.announce(VoiceAnnouncement("question.mp3", null, listenAfter = true) { done.complete(Unit) })
        val runner = awaitRunner(0)
        assertTrue(done.isCompleted)
        assertEquals(listOf("question.mp3"), played)
        assertEquals(VoiceTurnRequest(null), runner.requests.single())
        runner.release.complete(AssistOutcome())
        awaitState(VoiceState.IDLE)
    }

    @Test
    fun `an announcement plays with the assistant off and never opens the microphone`() {
        settings = settings.copy(enabled = false)
        val c = coordinator()
        c.start()
        val done = CompletableDeferred<Unit>()
        c.announce(VoiceAnnouncement("message.mp3", null, listenAfter = false) { done.complete(Unit) })
        runBlocking { withTimeout(2_000) { done.await() } }
        awaitRunFinished(c)
        assertEquals(listOf("message.mp3"), played)
        assertTrue(mic.leases.isEmpty())
        assertTrue(foregroundCalls.none { it })
    }

    @Test
    fun `a conversation Home Assistant starts while the assistant is off is still answered`() {
        settings = settings.copy(enabled = false)
        val c = coordinator()
        c.start()
        val done = CompletableDeferred<Unit>()
        c.announce(VoiceAnnouncement("question.mp3", null, listenAfter = true) { done.complete(Unit) })
        runBlocking { withTimeout(2_000) { done.await() } }
        assertTrue("an off assistant never listens", mic.leases.isEmpty())
        assertTrue(runners.isEmpty())
    }

    /**
     * The listening tint takes its colour from the pipeline, which is named by the wake word; so each cue
     * names the wake word, a conversation Home Assistant started names the first armed one (whose pipeline
     * speaks it), and the cue comes before the listening phase that raises the tint.
     */
    @Test
    fun `each cue names the wake word whose pipeline listens, before the listening phase shows`() {
        settings = settings.copy(wakeWords = listOf("okay_nabu", "hey_jarvis"))
        val c = coordinator()
        c.start()
        engines.single().onActivation(WakeWordActivation("hey_jarvis", "hey jarvis"))
        awaitRunner(0).release.complete(AssistOutcome())
        awaitRunFinished(c)
        c.announce(VoiceAnnouncement("question.mp3", null, listenAfter = true) {})
        awaitRunner(1).release.complete(AssistOutcome())
        awaitRunFinished(c)
        assertEquals(listOf("hey_jarvis", "okay_nabu"), cues.map { it.first })
        assertTrue("cued after listening began: ${cues.map { it.second }}", cues.none { it.second == VoiceState.LISTENING })
    }
}
