package io.panelassistant.android.assist

import io.panelassistant.android.audio.PcmConsumer
import io.panelassistant.android.audio.PcmFrame
import io.panelassistant.android.panelassistant.PanelAssistantTransportConnection
import io.panelassistant.android.panelassistant.PanelAssistantVoice
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One satellite turn against the panel's voice link, with Home Assistant's side played by the test. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SatelliteTurnRunnerTest {

    @Test fun `with no session the turn fails at once and never opens the microphone`() = runTest {
        val voice = PanelAssistantVoice(backgroundScope, { null }, {})
        var attached = 0
        val outcome = SatelliteTurnRunner(voice).run(VoiceTurnRequest("okay_nabu"), { attached++; AutoCloseable {} }, {}, {})
        assertEquals(SatelliteTurnRunner.CODE_UNAVAILABLE, outcome.error?.code)
        assertEquals(0, attached)
    }

    @Test fun `a turn listens until told to stop, plays the reply, reports it played, and carries the follow-up`() = runTest {
        val link = link()
        val played = mutableListOf<String>()
        var capture: PcmConsumer? = null
        var closed = 0
        val turn = async {
            SatelliteTurnRunner(link.voice, nanoTime = { testScheduler.currentTime * 1_000_000L }).run(
                VoiceTurnRequest("hey_jarvis", heardAtNs = 1L),
                { consumer -> capture = consumer; AutoCloseable { closed++ } },
                { url -> played += url },
                {},
            )
        }
        runCurrent()
        // The microphone is attached before Home Assistant has even answered the turn.
        assertTrue(capture != null)
        val runId = link.request(PanelAssistantVoice.COMMAND_VOICE_RUN).getLong("id")
        link.answer(runId, JSONObject().put("handler_id", 5))
        capture!!.onFrame(PcmFrame(shortArrayOf(1), timestampNs = 2L))
        link.event(runId, "listen_end")
        runCurrent()
        assertEquals(1, closed)

        link.event(runId, "play", "url" to "/api/tts_proxy/r.mp3", "continue_conversation" to true)
        link.event(runId, "end")
        runCurrent()

        val outcome = turn.await()
        link.pump()
        assertNull(outcome.error)
        assertTrue(outcome.continueConversation)
        assertEquals(listOf("https://ha.example/api/tts_proxy/r.mp3"), played)
        assertTrue(link.sent.any { it.optString("type") == PanelAssistantVoice.COMMAND_VOICE_PLAYED })
        assertTrue("the terminator follows the audio", link.binary.last().size == 1)
    }

    @Test fun `a streamed reply plays the voice stream, not its URL, and is reported played as before`() = runTest {
        val link = link()
        val played = mutableListOf<String>()
        val turn = async {
            SatelliteTurnRunner(link.voice).run(
                VoiceTurnRequest("hey_jarvis"),
                { AutoCloseable {} },
                object : AssistPlayback {
                    override suspend fun play(url: String) { played += url }
                    override suspend fun playStream(streamId: String): Boolean {
                        played += "stream@$streamId"
                        return false
                    }
                },
                {},
            )
        }
        runCurrent()
        val runId = link.request(PanelAssistantVoice.COMMAND_VOICE_RUN).getLong("id")
        link.answer(runId, JSONObject().put("handler_id", 5))
        link.event(runId, "play", "url" to "/api/tts_proxy/r.mp3", "stream" to true, "stream_id" to "s1")
        link.event(runId, "end")
        runCurrent()
        assertNull(turn.await().error)
        link.pump()
        assertEquals(listOf("stream@s1"), played)
        assertTrue(link.sent.any { it.optString("type") == PanelAssistantVoice.COMMAND_VOICE_PLAYED && !it.has("announce_id") })
    }

    @Test fun `a panel that never hears the end of the command stops listening on its own`() = runTest {
        val link = link()
        var closed = 0
        val turn = async {
            SatelliteTurnRunner(link.voice, nanoTime = { testScheduler.currentTime * 1_000_000L }, maxListenMs = 1_000L).run(
                VoiceTurnRequest("okay_nabu"),
                { AutoCloseable { closed++ } },
                {},
                {},
            )
        }
        runCurrent()
        val runId = link.request(PanelAssistantVoice.COMMAND_VOICE_RUN).getLong("id")
        link.answer(runId, JSONObject().put("handler_id", 5))
        advanceTimeBy(999L)
        runCurrent()
        assertEquals(0, closed)
        advanceTimeBy(2L)
        runCurrent()
        assertEquals(1, closed)
        link.event(runId, "end")
        runCurrent()
        assertFalse(turn.await().continueConversation)
    }

    @Test fun `a turn Home Assistant never starts gives up after four seconds, frees the microphone and cancels it there`() = runTest {
        val link = link()
        var closed = 0
        val turn = async {
            SatelliteTurnRunner(link.voice, nanoTime = { testScheduler.currentTime * 1_000_000L }).run(
                VoiceTurnRequest("okay_nabu"),
                { AutoCloseable { closed++ } },
                {},
                {},
            )
        }
        runCurrent()
        // Sent, and never answered with the turn's audio handler.
        val runId = link.request(PanelAssistantVoice.COMMAND_VOICE_RUN).getLong("id")
        advanceTimeBy(SatelliteTurnRunner.MAX_START_MS - 1L)
        runCurrent()
        assertFalse(turn.isCompleted)
        advanceTimeBy(2L)
        runCurrent()
        assertTrue("the turn does not wait out its two-minute cap", turn.isCompleted)
        assertEquals(SatelliteTurnRunner.CODE_NOT_STARTED, turn.await().error?.code)
        assertEquals(1, closed)
        link.pump()
        assertEquals(runId, link.sent.single { it.optString("type") == "unsubscribe_events" }.getLong("subscription"))
    }

    @Test fun `a turn started within four seconds keeps every frame heard before it started`() = runTest {
        val link = link()
        var capture: PcmConsumer? = null
        val turn = async {
            SatelliteTurnRunner(link.voice).run(
                VoiceTurnRequest("okay_nabu"),
                { consumer -> capture = consumer; AutoCloseable {} },
                {},
                {},
            )
        }
        runCurrent()
        val runId = link.request(PanelAssistantVoice.COMMAND_VOICE_RUN).getLong("id")
        // 3.9 s of command audio arrives before Home Assistant names the handler.
        repeat(390) { capture!!.onFrame(PcmFrame(shortArrayOf(it.toShort()), timestampNs = it.toLong())) }
        advanceTimeBy(3_900L)
        link.answer(runId, JSONObject().put("handler_id", 5))
        link.event(runId, "listen_end")
        link.event(runId, "end")
        runCurrent()
        assertNull(turn.await().error)
        val sent = link.binary.filter { it.size == 3 }.map { (it[2].toInt() shl 8) or (it[1].toInt() and 0xFF) }
        assertEquals((0 until 390).toList(), sent)
    }

    @Test fun `the reply's text reaches the run as it is written`() = runTest {
        val link = link()
        val replies = mutableListOf<String>()
        val turn = async {
            SatelliteTurnRunner(link.voice).run(VoiceTurnRequest("okay_nabu"), { AutoCloseable {} }, {}, { replies += it })
        }
        runCurrent()
        val runId = link.started()
        link.event(runId, "listen_end")
        link.event(runId, "reply", "text" to "Stopping", "final" to false)
        link.event(runId, "reply", "text" to "Stopping the timer.", "final" to true)
        link.event(runId, "end")
        runCurrent()
        turn.await()
        assertEquals(listOf("Stopping", "Stopping the timer."), replies)
    }

    @Test fun `a reply cut short is still reported played, so Home Assistant leaves responding`() = runTest {
        val link = link()
        val speaking = kotlinx.coroutines.CompletableDeferred<Unit>()
        val turn = async {
            SatelliteTurnRunner(link.voice).run(
                VoiceTurnRequest("okay_nabu"),
                { AutoCloseable {} },
                { speaking.complete(Unit); kotlinx.coroutines.awaitCancellation() },
                {},
            )
        }
        runCurrent()
        val runId = link.started()
        link.event(runId, "play", "url" to "/api/tts_proxy/r.mp3")
        link.event(runId, "end")
        runCurrent()
        assertTrue(speaking.isCompleted)
        turn.cancel()
        runCurrent()
        link.pump()
        assertTrue(link.sent.any { it.optString("type") == PanelAssistantVoice.COMMAND_VOICE_PLAYED && !it.has("announce_id") })
    }

    @Test fun `what the microphone hears while the panel's chime sounds is sent as silence`() = runTest {
        val link = link()
        var capture: PcmConsumer? = null
        val turn = async {
            SatelliteTurnRunner(link.voice, chime = { 100L..200L }).run(
                VoiceTurnRequest("okay_nabu"),
                { consumer -> capture = consumer; AutoCloseable {} },
                {},
                {},
            )
        }
        runCurrent()
        val runId = link.request(PanelAssistantVoice.COMMAND_VOICE_RUN).getLong("id")
        link.answer(runId, JSONObject().put("handler_id", 5))
        capture!!.onFrame(PcmFrame(shortArrayOf(1000), timestampNs = 150L))
        capture!!.onFrame(PcmFrame(shortArrayOf(1000), timestampNs = 250L))
        link.event(runId, "listen_end")
        link.event(runId, "end")
        runCurrent()
        turn.await()
        assertEquals(listOf(0, 1000), link.binary.filter { it.size == 3 }.map { (it[2].toInt() shl 8) or (it[1].toInt() and 0xFF) })
    }

    // --- The Voice Notice, fed as production feeds it: the coordinator's cue and phases, the runner's events.

    /** One turn whose card [conversation] follows: cued, listening, then the runner's own run. */
    private fun TestScope.shownTurn(link: Link, conversation: VoiceConversation, continued: Boolean = false) = async {
        conversation.begin()
        conversation.state(VoiceState.LISTENING)
        // Built as the service builds it, so the card hears the turn only through the production wiring.
        satelliteTurnRunner(link.voice, {}, conversation).run(
            VoiceTurnRequest("okay_nabu", continued = continued),
            // As the coordinator wires them: the capture closing is the move to thinking, playback to replying.
            { AutoCloseable { conversation.state(VoiceState.PROCESSING) } },
            { conversation.state(VoiceState.RESPONDING) },
            {},
        )
    }

    private fun Link.started(): Long {
        val runId = request(PanelAssistantVoice.COMMAND_VOICE_RUN).getLong("id")
        answer(runId, JSONObject().put("handler_id", 5))
        return runId
    }

    private fun VoiceConversation.shows(phase: VoiceConversation.Phase, heard: String?, reply: String?, done: Int, current: Int?) {
        val card = card ?: throw AssertionError("the card is closed")
        assertEquals(listOf(phase, heard, reply, done, current), listOf(card.phase, card.heard, card.reply, card.done, card.current))
    }

    @Test fun `the card names each phase, quotes what was heard, grows the reply, and fills each step on its own event`() = runTest {
        val link = link()
        var now = 1_000L
        val conversation = VoiceConversation { now }
        val turn = shownTurn(link, conversation)
        runCurrent()
        val runId = link.started()
        conversation.shows(VoiceConversation.Phase.LISTENING, null, null, done = 0, current = 0)
        link.event(runId, "listen_end")
        runCurrent()
        conversation.shows(VoiceConversation.Phase.THINKING, null, null, done = 0, current = 0)
        link.event(runId, "transcript", "text" to "Turn on the kitchen lights")
        runCurrent()
        conversation.shows(VoiceConversation.Phase.THINKING, "Turn on the kitchen lights", null, done = 1, current = 1)
        link.event(runId, "reply", "text" to "Turned on", "final" to false)
        runCurrent()
        conversation.shows(VoiceConversation.Phase.THINKING, "Turn on the kitchen lights", "Turned on", done = 1, current = 1)
        link.event(runId, "reply", "text" to "Turned on 3 lights in the kitchen.", "final" to true)
        // A piece still in flight after the final answer never replaces it.
        link.event(runId, "reply", "text" to "Turned on 3", "final" to false)
        runCurrent()
        val reply = "Turned on 3 lights in the kitchen."
        conversation.shows(VoiceConversation.Phase.THINKING, "Turn on the kitchen lights", reply, done = 2, current = 2)
        link.event(runId, "play", "url" to "/api/tts_proxy/r.mp3")
        runCurrent()
        conversation.shows(VoiceConversation.Phase.REPLYING, "Turn on the kitchen lights", reply, done = 2, current = 2)
        link.event(runId, "end")
        runCurrent()
        assertNull(turn.await().error)
        conversation.shows(VoiceConversation.Phase.REPLYING, "Turn on the kitchen lights", reply, done = 3, current = null)

        // The conversation is over: the card stays a few seconds, longer for a longer reply, then leaves.
        conversation.state(VoiceState.IDLE)
        val leaveAt = conversation.card!!.leaveAtMs!!
        assertEquals(VoiceConversation.leaveAfterMs(reply), leaveAt - now)
        assertTrue(VoiceConversation.leaveAfterMs(reply) > VoiceConversation.leaveAfterMs(null))
        assertEquals(VoiceConversation.LEAVE_MAX_MS, VoiceConversation.leaveAfterMs("x".repeat(10_000)))
        now = leaveAt - 1
        assertTrue(conversation.card != null)
        now = leaveAt
        assertNull("the card has left", conversation.card)
    }

    @Test fun `a Panel Assistant that sends no text still shows the phase and the steps its events prove`() = runTest {
        val link = link()
        var now = 0L
        val conversation = VoiceConversation { now }
        val turn = shownTurn(link, conversation)
        runCurrent()
        val runId = link.started()
        link.event(runId, "listen_end")
        runCurrent()
        conversation.shows(VoiceConversation.Phase.THINKING, null, null, done = 0, current = 0)
        link.event(runId, "play", "url" to "/api/tts_proxy/r.mp3")
        runCurrent()
        // A reply to play proves the command was heard and understood.
        conversation.shows(VoiceConversation.Phase.REPLYING, null, null, done = 2, current = 2)
        link.event(runId, "end")
        runCurrent()
        turn.await()
        conversation.shows(VoiceConversation.Phase.REPLYING, null, null, done = 3, current = null)
        conversation.state(VoiceState.IDLE)
        assertEquals(VoiceConversation.LEAVE_MIN_MS, conversation.card!!.leaveAtMs)
    }

    @Test fun `a follow-up keeps the last exchange readable while the panel listens, until the new transcript replaces it`() = runTest {
        val link = link()
        val conversation = VoiceConversation { 0L }
        val first = shownTurn(link, conversation)
        runCurrent()
        val runId = link.started()
        link.event(runId, "transcript", "text" to "Set a timer")
        link.event(runId, "reply", "text" to "For how long?", "final" to true)
        link.event(runId, "play", "url" to "/api/tts_proxy/q.mp3", "continue_conversation" to true)
        link.event(runId, "end")
        runCurrent()
        assertTrue(first.await().continueConversation)
        conversation.shows(VoiceConversation.Phase.REPLYING, "Set a timer", "For how long?", done = 3, current = null)

        val second = shownTurn(link, conversation, continued = true)
        runCurrent()
        // The question it asked stays on screen: it may be the part someone missed.
        conversation.shows(VoiceConversation.Phase.LISTENING, "Set a timer", "For how long?", done = 0, current = 0)
        val nextId = link.started()
        link.event(nextId, "listen_end")
        runCurrent()
        conversation.shows(VoiceConversation.Phase.THINKING, "Set a timer", "For how long?", done = 0, current = 0)
        link.event(nextId, "transcript", "text" to "Ten minutes")
        runCurrent()
        conversation.shows(VoiceConversation.Phase.THINKING, "Ten minutes", null, done = 1, current = 1)
        link.event(nextId, "end")
        runCurrent()
        second.await()
    }

    @Test fun `a turn that fails after Home Assistant settled the intent is recorded as an error`() = runTest {
        val link = link()
        val turn = async {
            SatelliteTurnRunner(link.voice).run(VoiceTurnRequest("okay_nabu"), { AutoCloseable {} }, {}, {})
        }
        runCurrent()
        val runId = link.request(PanelAssistantVoice.COMMAND_VOICE_RUN).getLong("id")
        link.answer(runId, JSONObject().put("handler_id", 5))
        link.event(runId, "listen_end")
        link.event(runId, "error", "code" to "tts_failed")
        link.event(runId, "end", "outcome" to "action_done")
        runCurrent()

        val record = turn.await().turn
        assertEquals("the intent's outcome does not hide the failure after it", VoiceTurnRecord.ERROR, record?.outcome)
        assertEquals("tts_failed", record?.code)
    }

    @Test fun `a failed turn proves no further step, and an exchange that ended with nothing heard leaves at once`() = runTest {
        val link = link()
        val conversation = VoiceConversation { 0L }
        val turn = shownTurn(link, conversation)
        runCurrent()
        val runId = link.started()
        link.event(runId, "error", "code" to AssistError.DUPLICATE_WAKE_UP)
        link.event(runId, "end")
        runCurrent()
        assertEquals(AssistError.DUPLICATE_WAKE_UP, turn.await().error?.code)
        conversation.shows(VoiceConversation.Phase.THINKING, null, null, done = 0, current = 0)
        // The coordinator keeps a duplicate wake word quiet and rests idle: nothing was heard, nothing to read.
        conversation.state(VoiceState.IDLE)
        assertNull(conversation.card)
    }

    @Test fun `a tap closes the card for the rest of the exchange, and the next wake word opens it again`() = runTest {
        val link = link()
        val conversation = VoiceConversation { 0L }
        val turn = shownTurn(link, conversation)
        runCurrent()
        val runId = link.started()
        link.event(runId, "transcript", "text" to "What time is it")
        runCurrent()
        conversation.close()
        assertNull(conversation.card)
        link.event(runId, "reply", "text" to "It is ten o'clock.", "final" to true)
        link.event(runId, "end")
        runCurrent()
        turn.await()
        conversation.state(VoiceState.IDLE)
        assertNull("nothing reopens a card someone closed", conversation.card)
        conversation.begin()
        conversation.shows(VoiceConversation.Phase.LISTENING, null, null, done = 0, current = 0)
    }

    /** The voice link opened on a scripted session; the test plays the session loop. */
    private class Link(val voice: PanelAssistantVoice, private val scope: TestScope) {
        val sent = mutableListOf<JSONObject>()
        val binary = mutableListOf<ByteArray>()
        private var nextId = 2L

        val connection = object : PanelAssistantTransportConnection {
            override suspend fun send(text: String) = Unit
            override suspend fun receive(timeoutMs: Long): String? = null
            override suspend fun close() = Unit
            override suspend fun sendBinary(bytes: ByteArray) {
                binary += bytes
            }
        }

        /** Drain what the voice link wants sent, as the session loop does. */
        fun pump() {
            while (true) {
                val text = voice.next(nextId) ?: return
                nextId++
                sent += JSONObject(text)
            }
        }

        fun request(type: String): JSONObject {
            scope.runCurrent()
            pump()
            return sent.last { it.optString("type") == type }
        }

        fun answer(id: Long, result: JSONObject) {
            voice.onFrame(JSONObject().put("id", id).put("type", "result").put("success", true).put("result", result), HELLO_ID)
            scope.runCurrent()
        }

        fun event(id: Long, kind: String, vararg fields: Pair<String, Any>) {
            val event = JSONObject().put("kind", kind)
            fields.forEach { (key, value) -> event.put(key, value) }
            voice.onFrame(JSONObject().put("id", id).put("type", "event").put("event", event), HELLO_ID)
            pump()
        }
    }

    private fun TestScope.link(): Link {
        val voice = PanelAssistantVoice(backgroundScope, { null }, {})
        val link = Link(voice, this)
        voice.open(link.connection, "session", "https://ha.example")
        link.pump()
        return link
    }

    private companion object {
        const val HELLO_ID = 1L
    }
}
