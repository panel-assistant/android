package io.github.maxlyth.hapaneld.panelassistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PanelAssistantTransportProtocolTest {

    @Test fun `hello offers schema 2 with schema 1 compatibility and a contract digest`() {
        val bare = JSONObject(PanelAssistantTransportProtocol.hello(1L, IDENTITY))
        assertEquals(0, bare.getJSONArray("capabilities").length())
        assertEquals(0, bare.getJSONArray("channels").length())
        val hello = JSONObject(
            PanelAssistantTransportProtocol.hello(
                1L, IDENTITY, PanelAssistantTransportProtocol.CAPABILITIES,
                listOfNotNull(PanelAssistantChannelCatalog.describe("relay3")),
            ),
        )
        assertEquals(1, hello.getJSONObject("protocol").getInt("min"))
        assertEquals(2, hello.getJSONObject("protocol").getInt("max"))
        assertTrue(Regex("^[0-9a-f]{64}$").matches(hello.getString("contract_digest")))
        assertEquals(listOf("state", "commands", "approval", "mqtt_withdraw", "embed_proof", "voice"), hello.getJSONArray("capabilities").let { (0 until it.length()).map(it::getString) })
        val relay = hello.getJSONArray("channels").getJSONObject(0)
        assertEquals(listOf("relay3", "switch", "relay", "relay3", "relay", "3"), listOf("channel", "platform", "translation_key", "unique_suffix", "family", "index").map { relay.get(it).toString() })
    }

    @Test fun `hello states unsupported channels explicitly and says nothing when there are none`() {
        assertFalse(JSONObject(PanelAssistantTransportProtocol.hello(1L, IDENTITY)).has("unsupported"))
        val hello = JSONObject(
            PanelAssistantTransportProtocol.hello(1L, IDENTITY, unsupported = listOf("humidity", "temperature")),
        )
        assertEquals(listOf("humidity", "temperature"), hello.optJSONArray("unsupported")?.let { (0 until it.length()).map(it::getString) })
    }

    @Test fun `the contract digest is pinned to the canonical handshake text`() {
        // Pinned as a literal: a digest derived from JSON serialisation could differ between the
        // device's org.json and the JVM's, and the integration records whatever the panel sends.
        assertEquals(
            "5557a65e4b729110205fd4fefb69d665a08e33218f81521a50094a8bab577dd2",
            PanelAssistantTransportProtocol.CONTRACT_DIGEST,
        )
    }

    @Test fun `a command event carries its id, session, channel, typed value and deadline`() {
        fun event(body: JSONObject) = JSONObject().put("id", 1).put("type", "event").put("event", body.put("kind", "command"))
        val full = JSONObject().put("command_id", "Ab_-9").put("session", "s").put("channel", "screen")
            .put("value", JSONObject().put("on", true)).put("deadline_ms", 2_500)
        val parsed = PanelAssistantTransportProtocol.sessionEvent(event(full), 1L) as PanelAssistantSessionEvent.Command
        assertEquals(listOf("Ab_-9", "s", "screen", 2_500L), listOf(parsed.commandId, parsed.session, parsed.channel, parsed.deadlineMs))
        assertEquals(true, (parsed.value as JSONObject).getBoolean("on"))

        val bare = PanelAssistantTransportProtocol.sessionEvent(
            event(JSONObject().put("command_id", "c").put("value", JSONObject.NULL)), 1L,
        ) as PanelAssistantSessionEvent.Command
        assertEquals(10_000L, bare.deadlineMs)
        assertEquals(JSONObject.NULL, bare.value)
        assertNull(bare.session)
        assertNull(bare.channel)

        for (deadline in listOf<Any>(0, 60_001, "10", 1.5)) {
            val odd = PanelAssistantTransportProtocol.sessionEvent(
                event(JSONObject().put("command_id", "c").put("deadline_ms", deadline)), 1L,
            ) as PanelAssistantSessionEvent.Command
            assertNull("$deadline", odd.deadlineMs)
        }
        assertEquals(60_000L, (PanelAssistantTransportProtocol.sessionEvent(event(JSONObject().put("command_id", "c").put("deadline_ms", 60_000)), 1L) as PanelAssistantSessionEvent.Command).deadlineMs)
        for (id in listOf<Any>("", "a b", "x".repeat(65), 7)) {
            assertEquals(
                "$id",
                PanelAssistantSessionEvent.MalformedCommand,
                PanelAssistantTransportProtocol.sessionEvent(event(JSONObject().put("command_id", id)), 1L),
            )
        }
    }

    @Test fun `a command result names its session, command and outcome, with a code only when given`() {
        val refused = JSONObject(PanelAssistantTransportProtocol.commandResult(21L, "s", "c", "refused", "expired"))
        assertEquals(
            listOf(21, "panel_assistant/command_result", "s", "c", "refused", "expired"),
            listOf("id", "type", "session", "command_id", "outcome", "code").map(refused::get),
        )
        assertTrue(!JSONObject(PanelAssistantTransportProtocol.commandResult(22L, "s", "c", "applied", null)).has("code"))
    }

    @Test fun `a panel without identity sends a null did rather than omitting it`() {
        val hello = JSONObject(PanelAssistantTransportProtocol.hello(1L, IDENTITY.copy(did = null)))
        assertTrue(hello.has("did"))
        assertTrue(hello.isNull("did"))
    }

    @Test fun `an accepted result yields the session and integration version`() {
        val outcome = PanelAssistantTransportProtocol.helloOutcome(accepted(), 1L)
        assertEquals(
            PanelAssistantHelloOutcome.Accepted(
                PanelAssistantSession(1, "opaque", "shadow", emptyList(), "0.3.0"),
            ),
            outcome,
        )
    }

    @Test fun `granting embed_proof makes a well-formed 32-byte key required`() {
        val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        fun embed(keyId: Any?, value: Any?) = JSONObject().put("key_id", keyId).put("key", value)
        val granted = session(accepted().granting("embed_proof").apply { getJSONObject("result").put("embed", embed("0123456789abcdef", key)) })
        assertEquals("0123456789abcdef", granted.embed?.keyId)
        assertTrue(granted.embed!!.key().contentEquals(ByteArray(32) { it.toByte() }))
        assertFalse(granted.toString().contains(key))
        assertFalse(granted.embed.toString().contains(key))

        val short = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31))
        listOf<JSONObject?>(
            null,
            embed("0123456789ABCDEF", key),
            embed("0123456789abcde", key),
            embed(7, key),
            embed("0123456789abcdef", short),
            embed("0123456789abcdef", "$key="),
            embed("0123456789abcdef", key.replace('A', '+')),
            embed("0123456789abcdef", null),
        ).forEachIndexed { index, value ->
            val frame = accepted().granting("embed_proof").apply { if (value != null) getJSONObject("result").put("embed", value) }
            try {
                PanelAssistantTransportProtocol.helloOutcome(frame, 1L)
                fail("granted case $index was accepted without a usable key")
            } catch (expected: PanelAssistantProtocolException) {
            }
        }
    }

    @Test fun `without the embed_proof grant the key is ignored`() {
        val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        val frame = accepted().apply { getJSONObject("result").put("embed", JSONObject().put("key_id", "0123456789abcdef").put("key", key)) }
        assertNull(session(frame).embed)
    }

    @Test fun `every build offers mqtt_withdraw`() {
        assertTrue(PanelAssistantTransportProtocol.CAPABILITIES.contains("mqtt_withdraw"))
    }

    @Test fun `granting mqtt_withdraw makes a known discovery claim required and binding`() {
        assertEquals("withdraw", session(accepted().granting("mqtt_withdraw").claiming("withdraw")).mqttDiscovery)
        assertEquals("announce", session(accepted().granting("mqtt_withdraw").claiming("announce")).mqttDiscovery)
        listOf<(JSONObject) -> JSONObject>(
            { it },
            { it.claiming("hold") },
            { it.claiming(1) },
            { it.claiming(JSONObject.NULL) },
        ).forEachIndexed { index, claim ->
            try {
                PanelAssistantTransportProtocol.helloOutcome(claim(accepted().granting("mqtt_withdraw")), 1L)
                fail("granted case $index was accepted without a valid claim")
            } catch (expected: PanelAssistantProtocolException) {
            }
        }
    }

    @Test fun `without the mqtt_withdraw grant the discovery field is ignored, present or not`() {
        assertNull(session(accepted()).mqttDiscovery)
        assertNull(session(accepted().claiming("withdraw")).mqttDiscovery)
        assertNull(session(accepted().claiming("announce")).mqttDiscovery)
        assertNull(session(accepted().granting("state").claiming("withdraw")).mqttDiscovery)
        assertNull(session(accepted().claiming("hold")).mqttDiscovery)
    }

    @Test fun `mqtt discovery is released by any authority but native and keyed on the grant under native`() {
        fun rule(authority: String, granted: Boolean, claim: String?, persisted: String) =
            PanelAssistantTransportProtocol.mqttDiscovery(
                PanelAssistantSession(1, "s", authority, if (granted) listOf("mqtt_withdraw") else emptyList(), "0.3.0", claim),
                persisted,
            )
        for (authority in listOf("mqtt", "shadow", "future_mode")) {
            for (persisted in listOf("", "withdraw", "announce")) {
                assertEquals("$authority persisted=$persisted", "announce", rule(authority, true, "withdraw", persisted))
                assertEquals("$authority persisted=$persisted", "announce", rule(authority, false, null, persisted))
            }
        }
        // Granted: the claim, whatever was persisted.
        assertEquals("withdraw", rule("native", true, "withdraw", ""))
        assertEquals("withdraw", rule("native", true, "withdraw", "announce"))
        assertEquals("announce", rule("native", true, "announce", "withdraw"))
        // Not granted: the persisted value, and a claim that arrived without the grant changes nothing.
        assertEquals("withdraw", rule("native", false, null, "withdraw"))
        assertEquals("withdraw", rule("native", false, "announce", "withdraw"))
        assertEquals("announce", rule("native", false, "withdraw", "announce"))
        assertEquals("announce", rule("native", false, null, "announce"))
        // Nothing valid persisted: announce.
        assertEquals("announce", rule("native", false, "withdraw", ""))
        assertEquals("announce", rule("native", false, null, "hold"))
    }

    @Test fun `entry_removed is a refusal code`() {
        assertEquals(
            PanelAssistantHelloOutcome.Refused("entry_removed"),
            PanelAssistantTransportProtocol.helloOutcome(refused(PanelAssistantTransportProtocol.CODE_ENTRY_REMOVED), 1L),
        )
        assertEquals("entry_removed", PanelAssistantTransportProtocol.CODE_ENTRY_REMOVED)
    }

    @Test fun `a refusal yields its code and an unusable code reads as invalid_format`() {
        assertEquals(
            PanelAssistantHelloOutcome.Refused("unknown_panel"),
            PanelAssistantTransportProtocol.helloOutcome(refused("unknown_panel"), 1L),
        )
        assertEquals(
            PanelAssistantHelloOutcome.Refused("invalid_format"),
            PanelAssistantTransportProtocol.helloOutcome(refused("Not A Code"), 1L),
        )
    }

    @Test fun `replies are correlated by parsed id, so id 10 never answers hello 1`() {
        assertNull(PanelAssistantTransportProtocol.helloOutcome(accepted(id = 10), 1L))
        assertNull(PanelAssistantTransportProtocol.sessionEvent(closed(id = 10), 1L))
        assertEquals(
            PanelAssistantSessionEvent.Closed("superseded"),
            PanelAssistantTransportProtocol.sessionEvent(closed(id = 1), 1L),
        )
    }

    @Test fun `a report_state result acknowledges by parsed id and lists rejections by channel`() {
        val acknowledged = JSONObject().put("id", 12).put("type", "result").put("success", true)
            .put("result", JSONObject().put("rejected", JSONArray()
                .put(JSONObject().put("channel", "diag_ip").put("code", "invalid_value"))
                .put(JSONObject().put("channel", "Not A Channel").put("code", "unknown_channel"))))
        assertEquals(
            PanelAssistantReportResult.Acknowledged(12L, mapOf("diag_ip" to "invalid_value")),
            PanelAssistantTransportProtocol.reportResult(acknowledged),
        )
        assertEquals(
            PanelAssistantReportResult.Failed(13L, "session_unknown"),
            PanelAssistantTransportProtocol.reportResult(refused("session_unknown").put("id", 13)),
        )
        assertNull(PanelAssistantTransportProtocol.reportResult(acknowledged.put("id", "12")))
        assertNull(PanelAssistantTransportProtocol.reportResult(JSONObject().put("id", 12).put("type", "pong")))
    }

    @Test fun `a granted capability the panel did not offer is a protocol failure`() {
        val frame = accepted()
        frame.getJSONObject("result").put("capabilities", JSONArray().put("state"))
        try {
            PanelAssistantTransportProtocol.helloOutcome(frame, 1L, offered = emptyList())
            fail("state was granted without being offered")
        } catch (expected: PanelAssistantProtocolException) {
        }
        val offered = runCatching { PanelAssistantTransportProtocol.helloOutcome(frame, 1L) }
        assertEquals(listOf("state"), (offered.getOrNull() as? PanelAssistantHelloOutcome.Accepted)?.session?.capabilities)
    }

    @Test fun `a success result that breaks the contract is a protocol failure`() {
        listOf<(JSONObject) -> Unit>(
            { it.put("protocol", 3) },
            { it.put("session", "") },
            { it.remove("authority") },
            { it.put("capabilities", JSONArray().put("commands")) },
            { it.remove("integration") },
            { it.put("integration", JSONObject().put("version", "not a version")) },
        ).forEachIndexed { index, breakResult ->
            val frame = accepted()
            breakResult(frame.getJSONObject("result"))
            try {
                PanelAssistantTransportProtocol.helloOutcome(frame, 1L, offered = listOf("state"))
                fail("case $index was accepted")
            } catch (expected: PanelAssistantProtocolException) {
            }
        }
    }

    private fun accepted(id: Int = 1): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "result")
        .put("success", true)
        .put(
            "result",
            JSONObject()
                .put("protocol", 1)
                .put("session", "opaque")
                .put("authority", "shadow")
                .put("capabilities", JSONArray())
                .put("integration", JSONObject().put("version", "0.3.0")),
        )

    private fun JSONObject.granting(vararg capabilities: String): JSONObject =
        apply { getJSONObject("result").put("capabilities", JSONArray(capabilities.toList())) }

    private fun JSONObject.claiming(value: Any): JSONObject = apply { getJSONObject("result").put("mqtt_discovery", value) }

    private fun session(frame: JSONObject): PanelAssistantSession =
        (PanelAssistantTransportProtocol.helloOutcome(frame, 1L) as PanelAssistantHelloOutcome.Accepted).session

    private fun refused(code: String): JSONObject = JSONObject()
        .put("id", 1)
        .put("type", "result")
        .put("success", false)
        .put("error", JSONObject().put("code", code).put("message", "x"))

    private fun closed(id: Int): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "event")
        .put("event", JSONObject().put("kind", "session_closed").put("reason", "superseded"))

    private companion object {
        val IDENTITY = PanelAssistantHelloIdentity("a".repeat(64), "0.9.7-rc5", 774)
    }
}
