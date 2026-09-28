package io.github.maxlyth.hapaneld.panelassistant

import io.github.maxlyth.hapaneld.HaAuthOwner
import io.github.maxlyth.hapaneld.audio.PcmFrame
import io.github.maxlyth.hapaneld.sensors.HaApiSession
import io.github.maxlyth.hapaneld.sensors.HaApiSessionProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's side of its Assist satellite, driven through the real transport owner against a scripted
 * Home Assistant: what the panel says after hello, how a turn's audio goes out, and what it does with
 * what Home Assistant sends back.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PanelAssistantVoiceTest {

    @Test fun `an accepted hello that grants voice is followed by the panel's wake words`() = runTest {
        val rig = rig()
        val hello = JSONObject(rig.connection.sent.first())
        assertTrue(hello.getJSONArray("capabilities").toList().contains("voice"))

        val configuration = rig.connection.sentOfType(PanelAssistantVoice.COMMAND_VOICE_CONFIGURATION).single()
        assertEquals("opaque-session", configuration.getString("session"))
        assertTrue(configuration.getBoolean("enabled"))
        assertEquals(listOf("okay_nabu", "hey_jarvis"), configuration.getJSONArray("active").toList())
        // A blank pipeline means Home Assistant's preferred one, so it is not sent at all.
        assertEquals("pipe-b", configuration.getJSONObject("pipelines").getString("hey_jarvis"))
        assertFalse(configuration.getJSONObject("pipelines").has("okay_nabu"))
        val word = configuration.getJSONArray("wake_words").getJSONObject(1)
        assertEquals("Hey Jarvis", word.getString("wake_word"))
    }

    @Test fun `a panel with nothing to configure offers no voice`() = runTest {
        val rig = rig(configuration = null)
        val hello = JSONObject(rig.connection.sent.first())
        assertFalse(hello.getJSONArray("capabilities").toList().contains("voice"))
        assertTrue(rig.connection.sentOfType(PanelAssistantVoice.COMMAND_VOICE_CONFIGURATION).isEmpty())
    }

    @Test fun `audio heard before Home Assistant names the handler goes out first, in order, then the end`() = runTest {
        val rig = rig()
        val turn = rig.voice.begin("hey_jarvis", continued = false)!!
        // Said straight after the wake word, before the turn has even been requested.
        turn.onFrame(frame(1, -2))
        turn.onFrame(frame(3, 4))
        runCurrent()
        val run = rig.connection.sentOfType(PanelAssistantVoice.COMMAND_VOICE_RUN).single()
        assertEquals("hey_jarvis", run.getString("wake_word_id"))
        assertFalse(run.getBoolean("continued"))
        assertTrue(rig.connection.binary.isEmpty())

        rig.connection.reply(run, JSONObject().put("handler_id", 7))
        runCurrent()
        turn.onFrame(frame(5, 6))
        turn.endAudio()
        runCurrent()

        assertEquals(4, rig.connection.binary.size)
        assertArrayEquals(byteArrayOf(7, 1, 0, -2, -1), rig.connection.binary[0])
        assertArrayEquals(byteArrayOf(7, 3, 0, 4, 0), rig.connection.binary[1])
        assertArrayEquals(byteArrayOf(7, 5, 0, 6, 0), rig.connection.binary[2])
        assertArrayEquals(byteArrayOf(7), rig.connection.binary[3])
    }

    @Test fun `a turn hears when to stop, what to play from its own server, and that it is over`() = runTest {
        val rig = rig()
        val turn = rig.voice.begin(null, continued = true)!!
        runCurrent()
        val run = rig.connection.sentOfType(PanelAssistantVoice.COMMAND_VOICE_RUN).single()
        assertTrue(run.isNull("wake_word_id"))
        assertTrue(run.getBoolean("continued"))
        rig.connection.reply(run, JSONObject().put("handler_id", 3))
        rig.connection.event(run, JSONObject().put("kind", "listen_end"))
        rig.connection.event(
            run,
            JSONObject().put("kind", "play").put("url", "/api/tts_proxy/a.mp3").put("continue_conversation", true),
        )
        rig.connection.event(run, JSONObject().put("kind", "end"))
        runCurrent()

        assertEquals(VoiceTurnEvent.ListenEnd, turn.events.receive())
        assertEquals(VoiceTurnEvent.Play("https://ha.example/api/tts_proxy/a.mp3", true), turn.events.receive())
        assertEquals(VoiceTurnEvent.End, turn.events.receive())

        rig.voice.played(null)
        runCurrent()
        val played = rig.connection.sentOfType(PanelAssistantVoice.COMMAND_VOICE_PLAYED).single()
        assertFalse(played.has("announce_id"))
    }

    @Test fun `an announcement is handed over resolved, and its end is reported by id`() = runTest {
        val rig = rig()
        rig.connection.inbound.trySend(
            JSONObject().put("id", 1).put("type", "event").put(
                "event",
                JSONObject().put("kind", "voice_announce").put("announce_id", "abc_1")
                    .put("url", "/api/tts_proxy/b.mp3").put("preannounce_url", JSONObject.NULL)
                    .put("listen_after", true),
            ).toString(),
        )
        runCurrent()
        assertEquals(
            listOf(PanelAssistantAnnouncement("abc_1", "https://ha.example/api/tts_proxy/b.mp3", null, true)),
            rig.announcements,
        )
        rig.voice.played("abc_1")
        runCurrent()
        assertEquals(
            "abc_1",
            rig.connection.sentOfType(PanelAssistantVoice.COMMAND_VOICE_PLAYED).single().getString("announce_id"),
        )
    }

    @Test fun `leaving a turn Home Assistant has not ended cancels it there`() = runTest {
        val rig = rig()
        val turn = rig.voice.begin("okay_nabu", continued = false)!!
        runCurrent()
        val run = rig.connection.sentOfType(PanelAssistantVoice.COMMAND_VOICE_RUN).single()
        rig.connection.reply(run, JSONObject().put("handler_id", 9))
        runCurrent()
        turn.close()
        runCurrent()
        val cancel = rig.connection.sentOfType("unsubscribe_events").single()
        assertEquals(run.getLong("id"), cancel.getLong("subscription"))
    }

    @Test fun `a session that ends takes its turns with it`() = runTest {
        val rig = rig()
        val turn = rig.voice.begin("okay_nabu", continued = false)!!
        runCurrent()
        rig.connection.inbound.trySend(
            JSONObject().put("id", 1).put("type", "event")
                .put("event", JSONObject().put("kind", "session_closed").put("reason", "entry_unloaded")).toString(),
        )
        runCurrent()
        assertEquals(VoiceTurnEvent.Failed(PanelAssistantVoice.CODE_SESSION_CLOSED), turn.events.receive())
        assertEquals(VoiceTurnEvent.End, turn.events.receive())
    }

    private class Rig(
        val voice: PanelAssistantVoice,
        val connection: ScriptedHa,
        val announcements: MutableList<PanelAssistantAnnouncement>,
    )

    private fun TestScope.rig(
        configuration: PanelAssistantVoiceConfiguration? = CONFIGURATION,
    ): Rig {
        val connection = ScriptedHa(grantVoice = configuration != null)
        val announcements = mutableListOf<PanelAssistantAnnouncement>()
        val voice = PanelAssistantVoice(backgroundScope, { configuration }, { announcements += it })
        val owner = PanelAssistantTransportOwner(
            scope = backgroundScope,
            auth = HaApiSessionProvider { HaApiSession("https://ha.example", "token", owner = OWNER) },
            connector = { _, _ -> connection },
            workerDispatcher = StandardTestDispatcher(testScheduler),
            monotonicMillis = { testScheduler.currentTime },
            jitter = { bound -> bound },
            observeForHello = { true },
            voice = voice,
        )
        owner.replaceDemand(PanelAssistantTransportDemand(OWNER, IDENTITY))
        runCurrent()
        return Rig(voice, connection, announcements)
    }

    /** Home Assistant's side of one socket: accepts hello and voice configuration, records the rest. */
    private class ScriptedHa(private val grantVoice: Boolean) : PanelAssistantTransportConnection {
        val inbound = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        val binary = mutableListOf<ByteArray>()

        fun sentOfType(type: String): List<JSONObject> =
            sent.map(::JSONObject).filter { it.optString("type") == type }

        fun reply(request: JSONObject, result: JSONObject) {
            inbound.trySend(
                JSONObject().put("id", request.getLong("id")).put("type", "result").put("success", true)
                    .put("result", result).toString(),
            )
        }

        fun event(request: JSONObject, event: JSONObject) {
            inbound.trySend(
                JSONObject().put("id", request.getLong("id")).put("type", "event").put("event", event).toString(),
            )
        }

        override suspend fun send(text: String) {
            sent += text
            val frame = JSONObject(text)
            when (frame.getString("type")) {
                "panel_assistant/hello" -> reply(
                    frame,
                    JSONObject().put("protocol", 2).put("session", "opaque-session").put("authority", "shadow")
                        .put("capabilities", JSONArray(if (grantVoice) listOf("voice") else emptyList<String>()))
                        .put("integration", JSONObject().put("version", "0.7.0")),
                )
                PanelAssistantVoice.COMMAND_VOICE_CONFIGURATION -> reply(frame, JSONObject())
                "ping" -> inbound.trySend(JSONObject().put("id", frame.getLong("id")).put("type", "pong").toString())
            }
        }

        override suspend fun sendBinary(bytes: ByteArray) {
            binary += bytes
        }

        override suspend fun receive(timeoutMs: Long): String? = select {
            inbound.onReceive { it }
            onTimeout(timeoutMs) { null }
        }

        override suspend fun close() {
            inbound.close()
        }
    }

    private companion object {
        val OWNER = HaAuthOwner(url = "https://ha.example", refreshToken = "refresh", clientId = "", staticAccessToken = "")
        val IDENTITY = PanelAssistantHelloIdentity(did = "0".repeat(64), appVersion = "0.9.9-rc1", appVersionCode = 990)
        val CONFIGURATION = PanelAssistantVoiceConfiguration(
            enabled = true,
            wakeWords = listOf(
                PanelAssistantWakeWord("okay_nabu", "Okay Nabu", listOf("en")),
                PanelAssistantWakeWord("hey_jarvis", "Hey Jarvis", listOf("en")),
            ),
            active = listOf("okay_nabu", "hey_jarvis"),
            pipelines = mapOf("okay_nabu" to "", "hey_jarvis" to "pipe-b"),
        )

        fun frame(vararg samples: Int) = PcmFrame(ShortArray(samples.size) { samples[it].toShort() }, timestampNs = 0L)

        fun JSONArray.toList(): List<Any> = List(length()) { get(it) }
    }
}
