package io.github.maxlyth.hapaneld.panelassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelAssistantTransportContractFixtureTest {
    @Test
    fun `Android producer messages match the source-stamped golden`() {
        val fixture = JSONObject(resource(PRODUCER_FIXTURE))
        assertTrue(fixture.getString("sourceRevision").matches(Regex("^[0-9a-f]{40}$")))
        val descriptor = PanelAssistantChannelCatalog.describe("relay1")
        assertNotNull("relay1 must remain describable", descriptor)
        val messages = listOf(
            JSONObject(
                PanelAssistantTransportProtocol.hello(
                    1,
                    PanelAssistantHelloIdentity("d".repeat(64), "0.9.8-rc2", 801),
                    PanelAssistantTransportProtocol.CAPABILITIES,
                    listOf(descriptor!!),
                    listOf("humidity", "temperature"),
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
        )
        val expected = fixture.getJSONArray("transportMessages").objects()
        assertEquals(expected.map { it.getString("name") }, listOf("hello", "report_state", "command_result"))
        assertEquals(expected.map { it.getJSONObject("message").toString() }, messages.map(JSONObject::toString))
    }

    @Test
    fun `every channel descriptor this build describes matches the exported producer fixture`() {
        val fixture = JSONObject(resource(PRODUCER_FIXTURE))
        val wires = PanelAssistantChannelCatalogTest.convergerChannels()
            .mapNotNull(PanelAssistantChannelCatalog::wireChannel).distinct().sorted()
        val described = wires.map { wire ->
            val descriptor = PanelAssistantChannelCatalog.describe(wire)
            assertNotNull("$wire must remain describable", descriptor)
            descriptor!!.toJson().toString()
        }
        val exported = fixture.getJSONArray("channelDescriptors").objects()
        assertEquals(wires, exported.map { it.getString("channel") })
        assertEquals(exported.map(JSONObject::toString), described)
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
            } else {
                assertTrue(
                    "$name must be rejected by the parser",
                    parsed.isFailure || parsed.getOrNull() !is PanelAssistantHelloOutcome.Accepted,
                )
            }
        }
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
