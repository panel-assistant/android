package io.github.maxlyth.hapaneld.assist

import io.github.maxlyth.hapaneld.audio.PcmConsumer
import io.github.maxlyth.hapaneld.audio.PcmFrame
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantTransportConnection
import io.github.maxlyth.hapaneld.panelassistant.PanelAssistantVoice
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
        val outcome = SatelliteTurnRunner(voice).run(VoiceTurnRequest("okay_nabu"), { attached++; AutoCloseable {} }, {})
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

    @Test fun `a panel that never hears the end of the command stops listening on its own`() = runTest {
        val link = link()
        var closed = 0
        val turn = async {
            SatelliteTurnRunner(link.voice, nanoTime = { testScheduler.currentTime * 1_000_000L }, maxListenMs = 1_000L).run(
                VoiceTurnRequest("okay_nabu"),
                { AutoCloseable { closed++ } },
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

    @Test fun `what the microphone hears while the panel's chime sounds is sent as silence`() = runTest {
        val link = link()
        var capture: PcmConsumer? = null
        val turn = async {
            SatelliteTurnRunner(link.voice, chime = { 100L..200L }).run(
                VoiceTurnRequest("okay_nabu"),
                { consumer -> capture = consumer; AutoCloseable {} },
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
