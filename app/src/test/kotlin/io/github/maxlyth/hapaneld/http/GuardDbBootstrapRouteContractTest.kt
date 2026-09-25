package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbBootstrapRouteContractTest {
    @Test fun `maintenance bootstrap admits direct LAN peers but never loopback`() {
        listOf(ipv4(192, 168, 40, 12), ipv4(10, 0, 0, 7), ipv4(172, 31, 12, 50), "fc00::1234", "fe80::1234%eth0")
            .forEach { peer -> assertTrue(peer, guardDbDirectLanPeer(peer)) }

        listOf("127.0.0.1", "/127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost")
            .forEach { peer -> assertFalse(peer, guardDbDirectLanPeer(peer)) }
        listOf("198.51.100.4", "2001:4860:4860::8888", "not-an-address")
            .forEach { peer -> assertFalse(peer, guardDbDirectLanPeer(peer)) }
    }

    private fun ipv4(a: Int, b: Int, c: Int, d: Int): String = listOf(a, b, c, d).joinToString(".")

    @Test fun `OpenAPI Guard DB bootstrap surface contract`() {
        // Source-text reason: openapi.json is the published API schema contract.
        val paths = JSONObject(TestSources.asset("openapi.json").readText()).getJSONObject("paths")
        val clock = paths.getJSONObject("/api/v1/guard-db/clock").getJSONObject("get")
        assertTrue(clock.getString("description").contains("elapsed_realtime_ms"))
        assertTrue(clock.getString("description").contains("minimum_overall_budget_ms"))
        assertTrue(clock.getString("description").contains("recovery_reserve_ms"))
        assertTrue(clock.getJSONObject("responses").getJSONObject("403")
            .getString("description").contains("Loopback"))
        val stage = paths.getJSONObject("/api/v1/guard-db/stage").getJSONObject("post")
        val stageSchema = stage.getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertEquals(setOf("token", "role"), stageSchema.getJSONArray("required").toSet())
        assertEquals(listOf("A", "B"), stageSchema.getJSONObject("properties")
            .getJSONObject("role").getJSONArray("enum").toList())
        assertTrue(stage.getString("description").contains("direct LAN"))
        assertTrue(stage.getJSONObject("responses").getJSONObject("202")
            .getString("description").contains("approval-required"))
        assertTrue(stage.getJSONObject("responses").getJSONObject("403")
            .getString("description").contains("Loopback"))

        val arm = paths.getJSONObject("/api/v1/guard-db/arm").getJSONObject("post")
        val armSchema = arm.getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertEquals(setOf("session", "overall_budget_ms"), armSchema.getJSONArray("required").toSet())
        assertEquals("^[0-9a-f]{64}$", armSchema.getJSONObject("properties")
            .getJSONObject("session").getString("pattern"))
        assertEquals("int64", armSchema.getJSONObject("properties")
            .getJSONObject("overall_budget_ms").getString("format"))
        val accepted = arm.getJSONObject("responses").getJSONObject("202")
        assertTrue(accepted.getString("description").contains("preparing-clean-proof"))
        assertTrue(accepted.getString("description").contains("approval-required"))
        assertTrue(arm.getString("description").contains("same-boot"))
        assertTrue(arm.getString("description").contains("physical approval"))
        assertTrue(arm.getString("description").contains("SETTINGS authority"))

        val retire = paths.getJSONObject("/api/v1/guard-db/evidence/retire").getJSONObject("post")
        val retireSchema = retire.getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertEquals(
            setOf("session", "generation", "evidence_sha256"),
            retireSchema.getJSONArray("required").toSet(),
        )
        assertEquals(
            9223372036854775806L,
            retireSchema.getJSONObject("properties").getJSONObject("generation").getLong("maximum"),
        )
        assertTrue(retire.getString("description").contains("GUARDRETIRE TERMINAL"))
        assertTrue(retire.getString("description").contains("never blindly replayed"))
        assertTrue(retire.getString("description").contains("no-backup storage"))
        val retireResponses = retire.getJSONObject("responses")
        val completed = retireResponses.getJSONObject("200").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertEquals(
            setOf("ok", "state", "session", "retirement_generation", "settlement"),
            completed.getJSONArray("required").toSet(),
        )
        assertEquals(listOf("empty"), completed.getJSONObject("properties")
            .getJSONObject("state").getJSONArray("enum").toList())
        val acceptedDescription = retireResponses.getJSONObject("202").getString("description")
        assertTrue(acceptedDescription.contains("same direct LAN peer within 10 minutes"))
        assertTrue(acceptedDescription.contains("durable INTENT"))
        assertTrue(acceptedDescription.contains("mutation fence stays held"))
        assertTrue(acceptedDescription.contains("FINALIZED is never reported as actively RETIRING"))
        val pending = retireResponses.getJSONObject("202").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
            .getJSONArray("oneOf").getJSONObject(1)
        assertEquals(listOf("retiring"), pending.getJSONObject("properties")
            .getJSONObject("state").getJSONArray("enum").toList())
        assertFalse(retireResponses.getJSONObject("400").getString("description").contains("oversized"))
        assertTrue(retireResponses.has("413"))
        assertTrue(retireResponses.getJSONObject("403").getString("description").contains("Host/Origin"))
        assertTrue(retireResponses.has("423"))
    }

    private fun org.json.JSONArray.toList(): List<String> =
        (0 until length()).map(::getString)

    private fun org.json.JSONArray.toSet(): Set<String> = toList().toSet()
}
