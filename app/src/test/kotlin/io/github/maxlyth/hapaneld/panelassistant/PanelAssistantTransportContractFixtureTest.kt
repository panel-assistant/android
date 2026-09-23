package io.github.maxlyth.hapaneld.panelassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelAssistantTransportContractFixtureTest {
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
    }
}
