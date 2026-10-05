package io.panelassistant.android.http

import io.panelassistant.android.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbMaintenanceServerContractTest {
    @Test fun `OpenAPI wire format separates preparation from writer free custody commit`() {
        // Source-text reason: the published OpenAPI document is the guard-db API wire contract.
        val paths = JSONObject(TestSources.asset("openapi.json").readText()).getJSONObject("paths")
        val commit = paths.getJSONObject("/api/v1/guard-db/arm/commit").getJSONObject("post")
        val schema = commit.getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/json").getJSONObject("schema")
        assertEquals(setOf("session", "generation"), schema.getJSONArray("required").toSet())
        assertEquals(0, schema.getJSONObject("properties").getJSONObject("generation").getInt("minimum"))
        assertEquals(0, schema.getJSONObject("properties").getJSONObject("generation").getInt("maximum"))
        assertTrue(commit.getString("description").contains("writer-free"))
        assertTrue(commit.getString("description").contains("budget"))
        assertTrue(commit.getString("description").contains("SETTINGS authority"))
        assertTrue(commit.getString("description").contains("security epoch"))
        assertFalse(commit.getJSONObject("responses").has("410"))
        listOf("arm/commit", "refusal", "cancel", "action").forEach { route ->
            val responses = paths.getJSONObject("/api/v1/guard-db/$route").getJSONObject("post")
                .getJSONObject("responses")
            assertTrue("$route omits Hardened debug-off refusal", responses.has("412"))
        }
        val cancelDescription = paths.getJSONObject("/api/v1/guard-db/cancel").getJSONObject("post")
            .getString("description")
        assertTrue(cancelDescription.contains("FAILED_NO_MUTATION"))
        assertTrue(cancelDescription.contains("CAPTURE_INTENT is hold-only"))
        val accepted = commit.getJSONObject("responses").getJSONObject("202").getString("description")
        assertTrue(accepted.contains("approval-required"))
        assertTrue(accepted.contains("poll-status"))
        assertTrue(accepted.contains("exact typed EMPTY generation zero"))
        assertTrue(accepted.contains("fresh one-shot physical approval"))
        assertTrue(accepted.contains(
            "Any non-empty, unavailable, malformed or otherwise unknown status forbids replay",
        ))
        val acceptedSchema = commit.getJSONObject("responses").getJSONObject("202")
            .getJSONObject("content").getJSONObject("application/json").getJSONObject("schema")
            .getJSONArray("oneOf").getJSONObject(1)
        assertEquals(
            setOf("ok", "state", "session", "settlement", "retry_policy"),
            acceptedSchema.getJSONArray("required").toSet(),
        )
        assertEquals(
            "fresh-approval-after-exact-empty",
            acceptedSchema.getJSONObject("properties").getJSONObject("retry_policy")
                .getJSONArray("enum").getString(0),
        )

        val statusDescription = paths.getJSONObject("/api/v1/guard-db/status").getJSONObject("get")
            .getString("description")
        assertTrue(statusDescription.contains("fresh-approval-after-exact-empty"))
        assertTrue(statusDescription.contains(
            "Any non-empty, unavailable, malformed or otherwise unknown status forbids replay",
        ))
    }

    private fun org.json.JSONArray.toSet(): Set<String> =
        (0 until length()).map(::getString).toSet()
}
