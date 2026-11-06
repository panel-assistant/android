package io.panelassistant.android.assist

import io.panelassistant.android.assist.wakeword.LoadedWakeWordModel
import io.panelassistant.android.assist.wakeword.MicroWakeWordModelConfig
import io.panelassistant.android.assist.wakeword.WakeWordScorer
import io.panelassistant.android.audio.FakeMicrophoneSource
import io.panelassistant.android.audio.MicPurpose
import io.panelassistant.android.audio.MicrophonePresence
import io.panelassistant.android.audio.MicrophoneSource
import io.panelassistant.android.media.AudioPlaybackCoordinator
import io.panelassistant.android.media.AudioPlaybackRun
import io.panelassistant.android.media.AudioPlaybackRunFactory
import io.panelassistant.android.panelassistant.PanelAssistantTransportConnection
import io.panelassistant.android.panelassistant.PanelAssistantVoice
import io.panelassistant.android.panelassistant.VoiceTurnEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The stop word as the panel runs it: the coordinator, the satellite turn over the Panel Assistant link, the
 * panel's one announcement lane, and the shared microphone, with only the model's score and Home Assistant's
 * side played by the test. Frames carry the capture clock, so every hold is measured in frames, not waited for.
 */
class StopWordTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mic = FakeMicrophoneSource()
    private val state = VoiceStateAuthority()
    private val logs = CopyOnWriteArrayList<String>()
    private var stopWord = true

    /** The stop model's score for every frame from now on, 0..255. */
    @Volatile private var score = 0

    /** A point a thread waits at until the test lets it on, so a race can be laid out step by step. */
    private class Hold {
        val reached = CountDownLatch(1)
        private val release = CountDownLatch(1)

        /** True when the held thread gave up waiting, so the race it was held for never happened. */
        @Volatile var expired = false
            private set

        fun stop() {
            reached.countDown()
            if (!release.await(2, TimeUnit.SECONDS)) expired = true
        }

        fun go() = release.countDown()
    }

    /** Held once, at the next model inference, the next submission to the speaker, or the next sound to play. */
    @Volatile private var atInference: Hold? = null
    @Volatile private var atSubmission: Hold? = null
    @Volatile private var atPlay: Hold? = null

    private fun take(hold: Hold?, clear: () -> Unit) = hold?.also { clear() }?.stop()

    private val stopModel = MicroWakeWordModelConfig(
        id = "stop", wakeWord = "Stop", author = "test", website = null, modelFile = "stop.tflite",
        trainedLanguages = listOf("en"), version = 2, probabilityCutoff = StopWordModel.CUTOFF,
        featureStepSizeMs = 10, slidingWindowSize = 5, tensorArenaSize = 0,
    )

    private fun model() = LoadedWakeWordModel(
        stopModel,
        object : WakeWordScorer {
            override fun score(samples: ShortArray, count: Int): Int {
                take(atInference) { atInference = null }
                return score
            }
            override fun reset() = Unit
            override fun close() = Unit
        },
    )

    /** What the panel's speaker plays: each sound until it is cancelled, except the ones that end at once. */
    private val started = CopyOnWriteArrayList<String>()
    private val cancelled = CopyOnWriteArrayList<String>()
    private val audio = AudioPlaybackCoordinator(
        AudioPlaybackRunFactory { url ->
            object : AudioPlaybackRun {
                private val done = CompletableDeferred<Unit>()
                override suspend fun execute() {
                    started += url
                    if (url.endsWith(CHIME)) return
                    done.await()
                }

                override fun cancel() {
                    cancelled += url
                    done.cancel()
                }
            }
        },
        Dispatchers.Default,
        // Inside the submission: the sound has its generation, and the playback has not yet named it.
        onBusyChanged = { busy -> if (busy) take(atSubmission) { atSubmission = null } },
    )

    /** Home Assistant's side of the panel's session. */
    private val sent = CopyOnWriteArrayList<JSONObject>()
    private var nextId = 2L
    private val voice = PanelAssistantVoice(scope, { null }, {}).also { voice ->
        voice.open(
            object : PanelAssistantTransportConnection {
                override suspend fun send(text: String) = Unit
                override suspend fun receive(timeoutMs: Long): String? = null
                override suspend fun close() = Unit
                override suspend fun sendBinary(bytes: ByteArray) = Unit
            },
            "session",
            "https://ha.example",
        )
    }

    // The session loop's side, once [voice] is assigned: it takes each request as the panel queues it.
    init {
        scope.launch { for (ignored in voice.wake) pump() }
    }

    @Synchronized
    private fun pump() {
        while (true) {
            val text = voice.next(nextId) ?: return
            nextId++
            sent += JSONObject(text)
        }
    }

    private fun event(runId: Long, kind: String, vararg fields: Pair<String, Any>) {
        val event = JSONObject().put("kind", kind)
        fields.forEach { (key, value) -> event.put(key, value) }
        voice.onFrame(JSONObject().put("id", runId).put("type", "event").put("event", event), HELLO_ID)
    }

    private var wake: ((WakeWordActivation) -> Unit)? = null

    private val coordinator = VoiceAssistantCoordinator(
        scope = scope,
        settings = { VoiceSettings(enabled = true, wakeWords = listOf("okay_nabu"), pipelines = emptyMap(), stopWord = stopWord) },
        microphone = { MicrophonePresence.PROVEN },
        source = { mic },
        engineFactory = { _, onActivation ->
            wake = onActivation
            object : WakeWordEngine {
                override fun onFrame(frame: io.panelassistant.android.audio.PcmFrame) = Unit
                override fun close() = Unit
            }
        },
        runnerFactory = { SatelliteTurnRunner(voice, onEvent = { if (it is VoiceTurnEvent.Play) take(atPlay) { atPlay = null } }) },
        // As the service wires it: the reply plays through the one announcement lane.
        playback = AnnouncementLanePlayback(audio, pollMs = 5L, onStarted = { state.set(VoiceState.RESPONDING) }),
        foregroundMicrophone = { true },
        state = state,
        stopWordModel = ::model,
        log = { logs += it },
    ).also { it.start() }

    @After
    fun tearDown() {
        coordinator.shutdown(2_000L)
        runBlocking { audio.close(1_000L) }
        scope.cancel()
    }

    // --- The capture clock: one 10 ms frame at a time, the model scoring [score] on each.

    private var clockMs = 0L

    private fun hear(ms: Long, saying: Boolean) {
        score = if (saying) LOUD else 0
        repeat((ms / FRAME_MS).toInt()) {
            mic.pushFrame(ShortArray(MicrophoneSource.SAMPLES_PER_FRAME), timestampNs = clockMs * 1_000_000L)
            clockMs += FRAME_MS
        }
        score = 0
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val met = runBlocking { withTimeoutOrNull(2_000L) { while (!condition()) delay(5L); true } } ?: false
        assertTrue(what, met)
    }

    /** What the panel has sent so far; the session loop's next turn is taken here as well as on its wake. */
    private fun sentOf(type: String): List<JSONObject> {
        pump()
        return sent.filter { it.optString("type") == type }
    }

    /** Wake, and have Home Assistant start the turn; returns the run's id. */
    private fun listening(): Long {
        eventually("the wake word is armed") { wake != null }
        wake!!(WakeWordActivation("okay_nabu", "Okay Nabu"))
        eventually("the turn is requested") { sentOf(PanelAssistantVoice.COMMAND_VOICE_RUN).isNotEmpty() }
        val runId = sentOf(PanelAssistantVoice.COMMAND_VOICE_RUN).single().getLong("id")
        voice.onFrame(
            JSONObject().put("id", runId).put("type", "result").put("success", true).put("result", JSONObject().put("handler_id", 5)),
            HELLO_ID,
        )
        eventually("the panel listens") { state.current() == VoiceState.LISTENING }
        return runId
    }

    private fun thinking(runId: Long) {
        event(runId, "listen_end")
        eventually("the panel thinks") { state.current() == VoiceState.PROCESSING }
    }

    /** A turn whose reply [text] the panel is now speaking. */
    private fun replying(text: String): Long {
        val runId = listening()
        hear(2_000, saying = false)
        thinking(runId)
        event(runId, "reply", "text" to text, "final" to true)
        event(runId, "play", "url" to REPLY)
        eventually("the reply is playing") { started.contains("https://ha.example$REPLY") && state.current() == VoiceState.RESPONDING }
        return runId
    }

    private fun assertStopped(sound: String) {
        eventually("the run ends") { !coordinator.running }
        assertTrue("the sound is silenced", cancelled.contains(sound))
        assertEquals(VoiceState.IDLE, state.current())
        assertEquals("the wake word hears again", listOf(MicPurpose.WAKE_WORD), mic.activeLeases.map { it.purpose })
        assertTrue(logs.any { it.contains("stop word heard") && it.endsWith("stopping") })
    }

    /** The run ended, nothing is left on the speaker, and the wake word listens again. */
    private fun assertSilent() {
        eventually("the run ends") { !coordinator.running }
        eventually("nothing is left playing") { audio.snapshot().state == AudioPlaybackCoordinator.State.IDLE }
        assertEquals(VoiceState.IDLE, state.current())
        assertEquals("the wake word hears again", listOf(MicPurpose.WAKE_WORD), mic.activeLeases.map { it.purpose })
        assertTrue(logs.any { it.contains("stop word heard") && it.endsWith("stopping") })
    }

    private fun assertNotStopped(sound: String) {
        assertTrue(coordinator.running)
        assertFalse("the sound plays on", cancelled.contains(sound))
    }

    @Test fun `saying stop while the panel speaks its reply silences it, ends the turn in Home Assistant and listens for the wake word again`() {
        val runId = replying("It is five o'clock.")
        hear(1_600, saying = false)
        hear(100, saying = true)
        assertStopped("https://ha.example$REPLY")
        pump()
        assertEquals("Home Assistant hears the reply is over", 1, sentOf(PanelAssistantVoice.COMMAND_VOICE_PLAYED).count { !it.has("announce_id") })
        assertEquals("and cancels the pipeline", runId, sentOf("unsubscribe_events").single().getLong("subscription"))
    }

    @Test fun `a stop heard as the reply arrives, before its sound is submitted, still silences it`() {
        val runId = listening()
        hear(2_000, saying = false)
        thinking(runId)
        hear(600, saying = false)
        val play = Hold().also { atPlay = it }
        event(runId, "reply", "text" to "It is five o'clock.", "final" to true)
        event(runId, "play", "url" to REPLY)
        assertTrue("the run has the reply in hand", play.reached.await(2, TimeUnit.SECONDS))
        hear(100, saying = true)
        play.go()
        assertSilent()
        assertFalse("the run waited at the reply until the stop was heard", play.expired)
    }

    @Test fun `a stop heard while the reply's sound is being submitted still silences it`() {
        val runId = listening()
        hear(2_000, saying = false)
        thinking(runId)
        hear(600, saying = false)
        // Four inferences toward a stop; the fifth completes it, and is held as it is scored.
        hear(40, saying = true)
        val inference = Hold().also { atInference = it }
        score = LOUD
        val delivering = thread { mic.pushFrame(ShortArray(MicrophoneSource.SAMPLES_PER_FRAME), timestampNs = clockMs * 1_000_000L) }
        assertTrue("the last frame is being scored", inference.reached.await(2, TimeUnit.SECONDS))
        val submission = Hold().also { atSubmission = it }
        event(runId, "reply", "text" to "It is five o'clock.", "final" to true)
        event(runId, "play", "url" to REPLY)
        assertTrue("the reply is being submitted", submission.reached.await(2, TimeUnit.SECONDS))
        inference.go()
        delivering.join(2_000L)
        score = 0
        assertFalse("the last frame was delivered", delivering.isAlive)
        assertTrue("the stop was heard while the sound was being submitted", logs.any { it.endsWith("stopping") })
        submission.go()
        assertSilent()
        assertFalse("each thread waited at its point of the race", inference.expired || submission.expired)
    }

    @Test fun `the words of the command never stop the panel`() {
        listening()
        assertEquals("the stop word hears the command too", 1, mic.activeLeases.count { it.purpose == MicPurpose.WAKE_WORD })
        hear(2_000, saying = false)
        hear(500, saying = true)
        assertTrue(coordinator.running)
        assertEquals(VoiceState.LISTENING, state.current())
        assertTrue(logs.none { it.contains("stop word") })
    }

    @Test fun `stop is held while the panel's own reply says it, and heard after`() {
        val reply = "Okay, stopping the music in the kitchen."
        replying(reply)
        hear(2_000, saying = false)
        hear(100, saying = true)
        assertNotStopped("https://ha.example$REPLY")
        assertTrue(logs.any { it.endsWith("while the reply says it; ignored") })
        // Held from the first frame after the text arrived, for as long as saying it can take.
        hear(StopWordListener.textHoldMs(reply) - 2_100, saying = false)
        hear(100, saying = true)
        assertStopped("https://ha.example$REPLY")
    }

    @Test fun `stop is held for the first moment of each sound the panel plays`() {
        replying("It is five o'clock.")
        hear(1_000, saying = true)
        assertNotStopped("https://ha.example$REPLY")
        assertTrue(logs.any { it.endsWith("as playback starts; ignored") })
        hear(600, saying = false)
        hear(100, saying = true)
        assertStopped("https://ha.example$REPLY")
    }

    @Test fun `stop is held for half a second after the panel stops listening, then cancels the pipeline while it thinks`() {
        val runId = listening()
        hear(2_000, saying = false)
        thinking(runId)
        hear(300, saying = true)
        assertTrue(coordinator.running)
        assertTrue(logs.any { it.endsWith("as the panel stops listening; ignored") })
        hear(300, saying = false)
        hear(100, saying = true)
        eventually("the run ends") { !coordinator.running }
        assertEquals(VoiceState.IDLE, state.current())
        pump()
        assertEquals(runId, sentOf("unsubscribe_events").single().getLong("subscription"))
        assertEquals(
            "Home Assistant may already be answering, so it hears the panel will not play it",
            1,
            sentOf(PanelAssistantVoice.COMMAND_VOICE_PLAYED).count { !it.has("announce_id") },
        )
    }

    @Test fun `stop silences a ringing timer, reports it stopped and listens for nothing after it`() {
        eventually("the wake word is armed") { wake != null }
        val done = CompletableDeferred<Boolean>()
        coordinator.announce(VoiceAnnouncement(TIMER, CHIME, listenAfter = true, done = { done.complete(it) }))
        eventually("the timer rings") { started.contains(TIMER) }
        hear(1_600, saying = false)
        hear(100, saying = true)
        assertStopped(TIMER)
        assertTrue("Panel Assistant hears the owner stopped it, so it rings no more", done.getCompleted())
        assertTrue("no turn follows", sentOf(PanelAssistantVoice.COMMAND_VOICE_RUN).isEmpty())
    }

    @Test fun `an announcement that says stop does not stop itself`() {
        eventually("the wake word is armed") { wake != null }
        val text = "Stop what you are doing, dinner is ready."
        coordinator.announce(VoiceAnnouncement(TIMER, null, listenAfter = false, text = text, done = {}))
        eventually("the announcement plays") { started.contains(TIMER) }
        hear(2_000, saying = false)
        hear(100, saying = true)
        assertNotStopped(TIMER)
        assertTrue(logs.any { it.endsWith("while the reply says it; ignored") })
        hear(StopWordListener.textHoldMs(text) - 2_100, saying = false)
        hear(100, saying = true)
        assertStopped(TIMER)
    }

    @Test fun `with the stop word off nothing listens for it`() {
        stopWord = false
        replying("It is five o'clock.")
        assertEquals("the wake word's own lease, paused", listOf(MicPurpose.WAKE_WORD), mic.leases.filter { !it.closed }.map { it.purpose })
        hear(1_600, saying = false)
        hear(100, saying = true)
        assertNotStopped("https://ha.example$REPLY")
    }

    private companion object {
        const val HELLO_ID = 1L
        const val FRAME_MS = 10L
        const val LOUD = 250
        const val REPLY = "/api/tts_proxy/reply.mp3"
        const val CHIME = "chime.mp3"
        const val TIMER = "https://ha.example/api/tts_proxy/timer.mp3"
    }
}
