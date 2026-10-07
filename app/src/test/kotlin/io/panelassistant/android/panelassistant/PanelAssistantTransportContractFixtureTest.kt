package io.panelassistant.android.panelassistant

import io.panelassistant.android.testsupport.TestSources
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelAssistantTransportContractFixtureTest {
    @Test
    fun `Android producer messages match the source-stamped golden`() {
        val fixture = JSONObject(resource(PRODUCER_FIXTURE))
        if (!recordingProducer()) assertTrue(fixture.getString("sourceRevision").matches(Regex("^[0-9a-f]{40}$")))
        val descriptor = PanelAssistantChannelCatalog.describe("relay1")
        assertNotNull("relay1 must remain describable", descriptor)
        val messages = listOf(
            JSONObject(
                PanelAssistantTransportProtocol.hello(
                    1,
                    PanelAssistantHelloIdentity("d".repeat(64), "0.9.8-rc2", 801),
                    PanelAssistantTransportProtocol.CAPABILITIES,
                    listOf(descriptor!!),
                    (PanelAssistantChannelCatalog.RETIRED_CHANNELS + listOf("humidity", "temperature")).sorted(),
                ),
            ),
            JSONObject(
                PanelAssistantTransportProtocol.reportState(
                    2,
                    "fixture-session",
                    PanelAssistantTransportProtocol.SYNC_DELTA,
                    JSONArray().put(
                        JSONObject()
                            .put("channel", "relay1")
                            .put("state", PanelAssistantTransportProtocol.STATE_KNOWN)
                            .put("value", true),
                    ),
                ),
            ),
            JSONObject(
                PanelAssistantTransportProtocol.commandResult(
                    3,
                    "fixture-session",
                    "fixture-command",
                    PanelAssistantTransportProtocol.OUTCOME_REFUSED,
                    "approval_timeout",
                ),
            ),
            JSONObject(PanelAssistantTransportProtocol.restartNotice(4, "fixture-session", "app", "settings", 30_000)),
        )
        val expected = fixture.getJSONArray("transportMessages").objects()
        assertEquals(expected.map { it.getString("name") }, listOf("hello", "report_state", "command_result", "restart_notice"))
        if (recordingProducer()) {
            recordProducer("transportMessages", JSONArray().apply {
                listOf("hello", "report_state", "command_result", "restart_notice").zip(messages).forEach { (name, message) ->
                    put(JSONObject().put("name", name).put("message", message))
                }
            })
        } else {
            assertEquals(expected.map { it.getJSONObject("message").toString() }, messages.map(JSONObject::toString))
        }
    }

    @Test
    fun `every channel descriptor this build describes matches the exported producer fixture`() {
        val fixture = JSONObject(resource(PRODUCER_FIXTURE))
        val wires = (PanelAssistantChannelCatalogTest.convergerChannels()
            .mapNotNull(PanelAssistantChannelCatalog::wireChannel) + listOf("reload", "reboot")).distinct().sorted()
        val described = wires.map { wire ->
            val descriptor = PanelAssistantChannelCatalog.describe(wire)
            assertNotNull("$wire must remain describable", descriptor)
            descriptor!!.toJson().toString()
        }
        val exported = fixture.getJSONArray("channelDescriptors").objects()
        if (recordingProducer()) {
            recordProducer("channelDescriptors", JSONArray().apply { described.forEach { put(JSONObject(it)) } })
        } else {
            assertEquals(wires, exported.map { it.getString("channel") })
            assertEquals(exported.map(JSONObject::toString), described)
        }
    }

    @Test
    fun `shared Home Assistant replies pass through the real parser`() {
        val fixture = JSONObject(resource(FIXTURE))
        assertTrue(fixture.getString("sourceRevision").matches(Regex("^[0-9a-f]{40}$")))

        fixture.getJSONArray("results").objects().forEach { case ->
            val name = case.getString("name")
            val offered = case.getJSONArray("offeredCapabilities").strings()
            case.optJSONObject("request")?.let { request ->
                assertEquals("$name request offer", offered, request.getJSONArray("capabilities").strings())
            }
            val frame = JSONObject()
                .put("id", HELLO_ID)
                .put("type", "result")
                .put("success", true)
                .put("result", case.getJSONObject("result"))
            val parsed = runCatching {
                PanelAssistantTransportProtocol.helloOutcome(frame, HELLO_ID, offered)
            }

            if (case.getBoolean("valid")) {
                assertTrue("$name must parse: ${parsed.exceptionOrNull()}", parsed.isSuccess)
                val accepted = parsed.getOrNull() as? PanelAssistantHelloOutcome.Accepted
                assertNotNull("$name must produce an accepted session", accepted)
                if (PanelAssistantTransportProtocol.CAPABILITY_EMBED_PROOF in accepted!!.session.capabilities) {
                    val embed = accepted.session.embed
                    assertNotNull("$name must carry its granted embed proof", embed)
                    assertEquals("0123456789abcdef", embed!!.keyId)
                    assertArrayEquals(ByteArray(32) { it.toByte() }, embed.key())
                } else if (case.getJSONObject("result").has("embed")) {
                    assertNull("$name must ignore an ungranted embed proof", accepted.session.embed)
                }
                if (PanelAssistantTransportProtocol.CAPABILITY_VOICE_STREAM_SESSION in accepted.session.capabilities) {
                    val stream = accepted.session.voiceStream
                    assertNotNull("$name must carry its granted voice stream", stream)
                    assertEquals("CAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg", stream!!.serverId)
                    assertArrayEquals(ByteArray(32) { it.toByte() }, stream.psk())
                } else if (case.getJSONObject("result").has("voice_stream_session")) {
                    assertNull("$name must ignore an ungranted voice stream", accepted.session.voiceStream)
                }
            } else {
                assertTrue(
                    "$name must be rejected by the parser",
                    parsed.isFailure || parsed.getOrNull() !is PanelAssistantHelloOutcome.Accepted,
                )
            }
        }
    }

    /** A player that records what the session hands it, and lets the test send as the library would. */
    private class VectorPlayer : PanelAssistantVoiceStreamPeer {
        val received = mutableListOf<ByteArray>()
        var send: ((ByteArray, Boolean) -> Unit)? = null
        override fun clientId() = null
        override fun open(grant: PanelAssistantVoiceStreamGrant, send: (ByteArray, Boolean) -> Unit) { this.send = send }
        override fun receive(frame: ByteArray, text: Boolean) { received += frame }
        override fun mute() = Unit
        override fun unmute() = Unit
        override fun close() = Unit
    }

    @Test
    fun `every voice stream vector is taken by the panel exactly when it is valid`() {
        val vectors = JSONObject(resource(FIXTURE)).getJSONArray("voiceStream").objects()
        assertTrue("the shared vectors carry voice stream cases", vectors.isNotEmpty())
        vectors.forEach { case ->
            val name = case.getString("name")
            val payload = case.getJSONObject("payload")
            val valid = case.getBoolean("valid")
            when (payload.optString("kind")) {
                PanelAssistantVoiceStream.EVENT_SENDSPIN, PanelAssistantVoiceStream.EVENT_STREAM_END -> {
                    val player = VectorPlayer()
                    val stream = PanelAssistantVoiceStream(player)
                    val frame = JSONObject().put("id", HELLO_ID).put("type", "event").put("event", payload)
                    assertTrue(name, stream.onFrame(frame, HELLO_ID))
                    val taken = player.received.isNotEmpty() || stream.ended(payload.optString("stream_id")) != null
                    assertEquals(name, valid, taken)
                }
                else -> {
                    // A play: a valid streamed one plays exactly its stream_id; a valid plain one, or any invalid one, streams nothing.
                    val plays = if (payload.has("action")) {
                        (io.panelassistant.android.media.PanelMediaCommand.parse(payload) as? io.panelassistant.android.media.PanelMediaCommand.Play)?.stream
                    } else {
                        io.panelassistant.android.media.streamedId(payload)
                    }
                    assertEquals(name, if (valid && payload.opt("stream") == true) payload.getString("stream_id") else null, plays)
                }
            }
        }
    }

    @Test
    fun `the panel sends exactly the valid voice stream commands and never an invalid one`() {
        val vectors = JSONObject(resource(FIXTURE)).getJSONArray("messages").objects().filter {
            it.getJSONObject("message").getString("type") in setOf(PanelAssistantVoiceStream.COMMAND_FRAME, PanelAssistantVoiceStream.COMMAND_STOP)
        }
        assertTrue("the shared vectors carry voice stream commands", vectors.size >= 2)
        vectors.forEach { case ->
            val name = case.getString("name")
            val message = case.getJSONObject("message")
            val player = VectorPlayer()
            val stream = PanelAssistantVoiceStream(player)
            stream.open(message.getString("session"), PanelAssistantVoiceStreamGrant("S".repeat(43), ByteArray(32)))
            if (message.getString("type") == PanelAssistantVoiceStream.COMMAND_FRAME) {
                // The panel can only produce frames it holds as bytes with a type: give it those the vector names.
                val bytes = (message.opt("frame") as? String)?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
                val text = message.opt("text") as? Boolean
                if (bytes == null || text == null) return@forEach assertFalse("$name is valid but not producible", case.getBoolean("valid"))
                player.send!!(bytes, text)
            } else {
                val streamId = message.opt("stream_id") as? String
                    ?: return@forEach assertFalse("$name is valid but not producible", case.getBoolean("valid"))
                kotlinx.coroutines.runBlocking {
                    val held = launch(kotlinx.coroutines.Dispatchers.Unconfined) { stream.play(streamId) }
                    held.cancel()
                }
            }
            val sent = stream.next(7L)?.let(::JSONObject)?.apply { remove("id") }
            if (case.getBoolean("valid")) {
                assertEquals(name, canonical(message), sent?.let(::canonical))
            } else {
                assertNull("$name must never be sent", sent)
            }
        }
    }

    private fun canonical(json: JSONObject): Map<String, String> = json.keys().asSequence().sorted().associateWith { json.get(it).toString() }

    private fun recordingProducer(): Boolean = System.getenv("HAPANELD_RECORD_ANDROID_PRODUCER") == "1"

    /** Record actual producer values; the owner stamps a real code commit before submission. */
    private fun recordProducer(field: String, value: JSONArray) {
        val target = TestSources.appFile("src/test/resources/panel-assistant-contract/android_producer_v1.json")
        val fixture = JSONObject(target.readText())
        val revision = System.getenv("HAPANELD_ANDROID_PRODUCER_REVISION") ?: "pending"
        require(revision == "pending" || revision.matches(Regex("^[0-9a-f]{40}$")))
        fixture.put("sourceRevision", revision).put(field, value)
        target.writeText(fixture.toString(2) + "\n")
        println("recorded $field to ${target.absolutePath}; sourceRevision=$revision")
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader()
            .use { it.readText() }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).map { getJSONObject(it) }

    private fun JSONArray.strings(): List<String> =
        (0 until length()).map { getString(it) }

    private companion object {
        const val HELLO_ID = 1L
        const val FIXTURE = "/panel-assistant-contract/panel_assistant_transport_v1_vectors.json"
        const val PRODUCER_FIXTURE = "/panel-assistant-contract/android_producer_v1.json"
    }
}
