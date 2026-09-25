package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.AdmissionOutcome
import io.github.maxlyth.hapaneld.RendererAdmissionState
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The renderer health object is part of the public status API. These tests keep the shipped OpenAPI
 * document in step with what the projection emits. Its behaviour is proven in
 * `RendererAdmissionPresentationTest`.
 */
class RendererHealthSurfaceContractTest {
    // Source-text reason: the shipped OpenAPI document is the public API contract.
    private fun openApi() = JSONObject(File("src/main/assets/openapi.json").readText())

    @Test fun openApiDescribesTheRendererContractItActuallyEmits() {
        val api = openApi()
        val schema = api.getJSONObject("components").getJSONObject("schemas").getJSONObject("RendererHealth")
        val properties = schema.getJSONObject("properties")

        val outcomes = properties.getJSONObject("outcome").getJSONArray("enum")
            .let { array -> (0 until array.length()).map { array.getString(it) } }
        // Every outcome the panel can publish must be documented, derived from the enum rather than
        // transcribed — a new admission outcome would otherwise ship undocumented.
        AdmissionOutcome.entries.forEach {
            assertTrue("${it.name.lowercase()} is missing from the OpenAPI outcome enum", it.name.lowercase() in outcomes)
        }
        assertTrue("ok" in outcomes && "ok_cached" in outcomes && "unobserved" in outcomes)

        val states = properties.getJSONObject("state").getJSONArray("enum")
            .let { array -> (0 until array.length()).map { array.getString(it) } }
        RendererAdmissionState.entries.forEach {
            assertTrue("${it.wire} is missing from the OpenAPI state enum", it.wire in states)
        }

        val status = api.getJSONObject("paths").getJSONObject("/api/v1/status").getJSONObject("get")
        assertTrue(
            status.getJSONObject("responses").getJSONObject("200")
                .getJSONObject("content").getJSONObject("application/json")
                .getJSONObject("schema").getJSONArray("required").toString().contains("renderer"),
        )
        assertTrue(api.getJSONObject("paths").getJSONObject("/api/v1/diag").toString().contains("renderer"))
    }

    @Test fun everyFieldTheProjectionEmitsIsDocumentedAndRequired() {
        // Derived from what `statusJson()` actually produces rather than transcribed beside it. The
        // last field added to this object shipped undocumented and was caught by the exact-tree gate
        // after composition, which is the expensive place to find it.
        val emitted = JSONObject(
            io.github.maxlyth.hapaneld.RendererAdmissionPresentation.of(
                mode = io.github.maxlyth.hapaneld.RendererMode.BUILTIN,
                haUrl = "https://home-assistant.example.invalid",
                addressFamilyPolicy = "Automatic",
                live = null,
                nowElapsedMs = 1_000L,
                processStartElapsedMs = 0L,
                packageUpdatedAtMs = 1L,
                nowWallMs = 2L,
            ).statusJson(),
        ).keys().asSequence().toSet()

        val api = openApi()
        val schema = api.getJSONObject("components").getJSONObject("schemas").getJSONObject("RendererHealth")
        val documented = schema.getJSONObject("properties").keys().asSequence().toSet()
        val required = schema.getJSONArray("required")
            .let { array -> (0 until array.length()).map { array.getString(it) } }.toSet()

        assertEquals("OpenAPI documents fields the panel does not emit", emitted, documented)
        assertEquals("the object is always fully populated, so every field is required", emitted, required)
    }
}
