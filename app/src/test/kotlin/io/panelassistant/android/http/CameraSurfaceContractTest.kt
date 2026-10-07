package io.panelassistant.android.http

import io.panelassistant.android.camera.CameraFault
import io.panelassistant.android.camera.CameraIndication
import io.panelassistant.android.camera.CameraPresentation
import io.panelassistant.android.camera.CameraRefusal
import io.panelassistant.android.camera.CameraResolution
import io.panelassistant.android.camera.CameraState
import io.panelassistant.android.camera.SnapshotExposureOutcome
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Binds the camera projection (`CameraPresentation`, proven behaviourally in `CameraPresentationTest`)
 * to the surfaces that must carry it: `/api/v1/status`, `/api/v1/diag` and the OpenAPI document. Modelled
 * on `RendererHealthSurfaceContractTest` — the same class of silent drift applies here: a status field
 * that quietly stopped being emitted, or an OpenAPI schema that fell out of sync with what the panel
 * actually sends, reads as a healthy fleet from the outside.
 */
class CameraSurfaceContractTest {
    // Source-text reason: the shipped openapi.json is the public API contract, parsed as data.
    private val openApi by lazy { JSONObject(File("src/main/assets/openapi.json").readText()) }

    @Test fun openApiListsCameraAsAlwaysPresentOnStatusAndMentionsItOnDiag() {
        val status = openApi.getJSONObject("paths").getJSONObject("/api/v1/status").getJSONObject("get")
        val schema = status.getJSONObject("responses").getJSONObject("200")
            .getJSONObject("content").getJSONObject("application/json").getJSONObject("schema")
        assertTrue(schema.getJSONArray("required").toString().contains("camera"))
        assertTrue(schema.getJSONObject("properties").has("camera"))
        assertTrue(openApi.getJSONObject("paths").getJSONObject("/api/v1/diag").toString().contains("camera"))
    }

    @Test fun everyFieldTheProjectionEmitsIsDocumentedAndRequired() {
        // Derived from what `statusJson()` actually produces rather than transcribed beside it, exactly
        // as the renderer's equivalent test does — the schema and the emitted keys are compared as sets
        // in both directions so neither can drift from the other unnoticed.
        val emitted = JSONObject(CameraPresentation.absent().statusJson()).keys().asSequence().toSet()
        val schema = openApi.getJSONObject("components").getJSONObject("schemas").getJSONObject("CameraHealth")
        val documented = schema.getJSONObject("properties").keys().asSequence().toSet()
        val required = schema.getJSONArray("required")
            .let { array -> (0 until array.length()).map { array.getString(it) } }.toSet()

        assertEquals("OpenAPI documents fields the panel does not emit", emitted, documented)
        assertEquals("the object is always fully populated, so every field is required", emitted, required)
    }

    @Test fun theDocumentedEnumsMatchTheKotlinWireValuesExactly() {
        val properties = openApi.getJSONObject("components").getJSONObject("schemas")
            .getJSONObject("CameraHealth").getJSONObject("properties")
        fun enumOf(field: String): Set<String> =
            properties.getJSONObject(field).getJSONArray("enum")
                .let { array -> (0 until array.length()).map { array.getString(it) } }.toSet()

        assertEquals(CameraState.entries.map { it.wire }.toSet(), enumOf("state"))
        assertEquals(CameraFault.entries.map { it.wire }.toSet(), enumOf("fault"))
        assertEquals(CameraIndication.entries.map { it.wire }.toSet(), enumOf("indication"))
        assertEquals(SnapshotExposureOutcome.entries.map { it.wire }.toSet(), enumOf("snapshot_exposure"))
    }

    @Test fun theSnapshotRouteIsDocumentedWithTheResolutionEnumAndNoFixedDefault() {
        val get = openApi.getJSONObject("paths").getJSONObject("/api/v1/camera/snapshot.jpg").getJSONObject("get")
        val params = get.getJSONArray("parameters")
        val res = (0 until params.length()).map { params.getJSONObject(it) }
            .first { it.getString("name") == "res" }
        assertEquals("query", res.getString("in"))
        assertFalse("res is optional", res.optBoolean("required", false))
        val schema = res.getJSONObject("schema")
        val enumValues = schema.getJSONArray("enum")
            .let { array -> (0 until array.length()).map { array.getString(it) } }
        assertEquals(CameraResolution.entries.map { it.wire }, enumValues)
        // Omission takes the profile default, which is a configured value rather than one fixed
        // default the spec could name — the schema must not claim one.
        assertFalse(schema.has("default"))
        val responses = get.getJSONObject("responses")
        listOf("200", "400", "403", "404", "503").forEach {
            assertTrue("missing response $it", responses.has(it))
        }
    }

    /**
     * The route's own 503 prose is the only place a consumer learns which bodies to expect, and it
     * drifted: `CameraFault.ENCODE` has mapped to `camera-encode-failed` since slice 3 while the
     * enumeration still named seven bodies (found 2026-08-30). Every refusal the enum carries that is not
     * produced only on the stream path must be named in the documented response for its status.
     */
    @Test fun everyRefusalTheSnapshotRouteCanReturnIsNamedInItsDocumentedOutcome() {
        // Produced only where a stream is admitted or its encoder fails; a snapshot never sees them.
        val streamOnly = setOf(CameraRefusal.STREAM_ENCODER, CameraRefusal.BUSY)
        val reachable = CameraRefusal.entries.filter { it !in streamOnly }
        assertEquals(404, CameraRefusal.snapshotStatusCode(CameraRefusal.ABSENT))
        reachable.filterNot { it == CameraRefusal.ABSENT }.forEach {
            assertEquals("${it.name} must not be answered as absent hardware", 503, CameraRefusal.snapshotStatusCode(it))
        }
        val responses = openApi.getJSONObject("paths").getJSONObject("/api/v1/camera/snapshot.jpg").getJSONObject("get").getJSONObject("responses")
        val notFound = responses.getJSONObject("404").getString("description")
        val unavailable = responses.getJSONObject("503").getString("description")
        reachable.forEach { refusal ->
            val documented = if (refusal == CameraRefusal.ABSENT) notFound else unavailable
            assertTrue("${refusal.token} is a reachable snapshot outcome and must be named in its documented response", refusal.token in documented)
        }
    }

    @Test fun theConfigSchemaDescribesTheFourCameraSettings() {
        val properties = openApi.getJSONObject("paths").getJSONObject("/api/v1/config").getJSONObject("post")
            .getJSONObject("requestBody").getJSONObject("content")
            .getJSONObject("application/x-www-form-urlencoded").getJSONObject("schema")
            .getJSONObject("properties")

        val enabled = properties.getJSONObject("camera_enabled")
        assertEquals("boolean", enabled.getString("type"))
        assertFalse(enabled.getBoolean("default"))

        val resolution = properties.getJSONObject("camera_resolution")
        assertEquals("720p", resolution.getString("default"))
        assertEquals(
            CameraResolution.entries.map { it.wire },
            resolution.getJSONArray("enum").let { array -> (0 until array.length()).map { array.getString(it) } },
        )

        val fps = properties.getJSONObject("camera_fps")
        assertEquals(1, fps.getInt("minimum"))
        assertEquals(30, fps.getInt("maximum"))
        assertEquals(15, fps.getInt("default"))

        val kbps = properties.getJSONObject("camera_kbps")
        assertEquals(250, kbps.getInt("minimum"))
        assertEquals(8000, kbps.getInt("maximum"))
        assertEquals(2000, kbps.getInt("default"))
    }
}
