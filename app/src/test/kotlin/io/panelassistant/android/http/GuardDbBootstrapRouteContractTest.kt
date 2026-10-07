package io.panelassistant.android.http

import io.panelassistant.android.testsupport.TestSources
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
        assertTrue(paths.getJSONObject("/api/v1/guard-db/clock").has("get"))
        val stage = paths.getJSONObject("/api/v1/guard-db/stage").getJSONObject("post")
        val stageSchema = stage.getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertEquals(setOf("token", "role"), stageSchema.getJSONArray("required").toSet())
        assertEquals(listOf("A", "B"), stageSchema.getJSONObject("properties")
            .getJSONObject("role").getJSONArray("enum").toList())

        val arm = paths.getJSONObject("/api/v1/guard-db/arm").getJSONObject("post")
        val armSchema = arm.getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertEquals(setOf("session", "overall_budget_ms"), armSchema.getJSONArray("required").toSet())
        assertEquals("^[0-9a-f]{64}$", armSchema.getJSONObject("properties")
            .getJSONObject("session").getString("pattern"))
        assertEquals("int64", armSchema.getJSONObject("properties")
            .getJSONObject("overall_budget_ms").getString("format"))

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
        val retireResponses = retire.getJSONObject("responses")
        val completed = retireResponses.getJSONObject("200").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertEquals(
            setOf("ok", "state", "session", "retirement_generation", "settlement"),
            completed.getJSONArray("required").toSet(),
        )
        assertEquals(listOf("empty"), completed.getJSONObject("properties")
            .getJSONObject("state").getJSONArray("enum").toList())
        val pending = retireResponses.getJSONObject("202").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
            .getJSONArray("oneOf").getJSONObject(1)
        assertEquals(listOf("retiring"), pending.getJSONObject("properties")
            .getJSONObject("state").getJSONArray("enum").toList())
        assertTrue(retireResponses.has("413"))
        assertTrue(retireResponses.has("423"))
    }

    private fun org.json.JSONArray.toList(): List<String> =
        (0 until length()).map(::getString)

    private fun org.json.JSONArray.toSet(): Set<String> = toList().toSet()
}
