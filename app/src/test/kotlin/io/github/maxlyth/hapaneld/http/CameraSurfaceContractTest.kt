package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.camera.CameraFault
import io.github.maxlyth.hapaneld.camera.CameraIndication
import io.github.maxlyth.hapaneld.camera.CameraPresentation
import io.github.maxlyth.hapaneld.camera.CameraRefusal
import io.github.maxlyth.hapaneld.camera.CameraResolution
import io.github.maxlyth.hapaneld.camera.CameraState
import io.github.maxlyth.hapaneld.camera.SnapshotExposureOutcome
import io.github.maxlyth.hapaneld.testsupport.TestSources
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    private val server by lazy { TestSources.kotlin("http/PaneldServer.kt").readText() }
    private val diag by lazy { TestSources.kotlin("http/DiagReader.kt").readText() }
    private val openApi by lazy { JSONObject(File("src/main/assets/openapi.json").readText()) }
    private val configureJs by lazy { File("src/main/assets/configure.js").readText() }
    private val transport by lazy { TestSources.kotlin("camera/CameraRtspServer.kt").readText() }

    /**
     * The Configure page builds an openable RTSP address out of the page's own host and a port literal,
     * because a link is only useful if it is the address the transport is actually listening on. The
     * literal therefore has to track `CameraRtspServer.DEFAULT_PORT`, and nothing else would notice if
     * it stopped doing so: the link would render, look right, and go nowhere.
     */
    @Test fun theConfigurePageLinksTheRtspPortTheTransportActuallyListensOn() {
        val declared = Regex("""DEFAULT_PORT = (\d+)""").find(transport)?.groupValues?.get(1)
        assertNotNull("CameraRtspServer must declare a default port", declared)
        assertTrue(
            "configure.js must use the transport's port, not its own: expected $declared",
            configureJs.contains("var CAMERA_RTSP_PORT = $declared;"),
        )
        assertTrue("the link must be built for the /live mount", configureJs.contains("\":\" + CAMERA_RTSP_PORT + \"/live\""))
    }

    /**
     * The two addresses are linked from the words the settings registry already uses, so the wording
     * stays in one place. That only works while the registry keeps saying them.
     */
    @Test fun theCameraHelpNamesTheWordsTheConfigurePageTurnsIntoLinks() {
        val registry = TestSources.kotlin("config/SettingsRegistry.kt").readText()
        // From `help = ` onward, not from the key onward: the comment above the help text also names
        // RTSP and JPEG, so a region-wide search is satisfied by the comment even when the user-visible
        // string has lost the word. The mutation battery caught exactly that.
        val help = registry
            .substringAfter("key = \"camera_enabled\"")
            .substringAfter("help = ")
            .substringBefore("availableWhen")
        assertFalse("the help region must not have swallowed the comment above it", help.contains("//"))
        listOf("RTSP", "JPEG").forEach {
            assertTrue("the camera help must still contain the word $it for the link to attach to", help.contains(it))
            assertTrue("configure.js must linkify $it", configureJs.contains("[\"$it\", "))
        }
        assertTrue(
            "the snapshot link must be the real route",
            configureJs.contains("\"api/v1/camera/snapshot.jpg\""),
        )
    }

    @Test fun theStatusEndpointEmitsTheCameraObjectUnconditionally() {
        assertTrue(server.contains("\\\"camera\\\":\${camera.presentation().statusJson()}"))
        // Unconditional means unconditional: no `?.` anywhere between the field and the presentation,
        // whether on the surface itself or on the call producing it. A consumer that has to infer
        // applicability from an absent field is one bad inference away from calling a blank fleet healthy.
        assertFalse(server.contains("camera?.presentation()"))
        assertFalse(server.contains("camera.presentation()?."))
    }

    @Test fun theDumpCarriesTheCameraLineAndTheProjectionIsBuiltOnce() {
        assertTrue(diag.contains("camera?.let { appendLine(it.diagnosticLine()) }"))
        assertEquals(
            "the dump must take the projection as a parameter rather than reaching for a live camera itself",
            1,
            Regex("camera: io\\.github\\.maxlyth\\.hapaneld\\.camera\\.CameraPresentation\\?").findAll(diag).count(),
        )
        assertTrue(server.contains("camera = camera.presentation(),"))
    }

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
     * enumeration still named seven bodies (found 2026-08-30). Reachability is read from the code rather
     * than assumed: every refusal the owner's `snapshot()` names, and every refusal the enum carries that
     * is not produced only on the stream path, must be named in the documented response for its status.
     */
    @Test fun everyRefusalTheSnapshotRouteCanReturnIsNamedInItsDocumentedOutcome() {
        val owner = TestSources.kotlin("camera/CameraSessionOwner.kt").readText()
        val state = TestSources.kotlin("camera/CameraSessionState.kt").readText()
        val snapshotFn = owner.substring(owner.indexOf("override fun snapshot("))
        val snapshotBody = snapshotFn.substring(0, snapshotFn.indexOf("\n    }\n") + 7)
        val namedInSnapshot = Regex("CameraRefusal\\.([A-Z_]+)").findAll(snapshotBody).map { it.groupValues[1] }.toSet()
        assertTrue("the waiter path names its refusals in snapshot()", namedInSnapshot.isNotEmpty())
        // Produced only where a stream is admitted or its encoder fails; a snapshot never sees them.
        val streamOnly = setOf(CameraRefusal.STREAM_ENCODER, CameraRefusal.BUSY)
        streamOnly.forEach {
            assertFalse("${it.name} is stream-only, so the snapshot path must not name it", it.name in namedInSnapshot)
            assertTrue("${it.name} is produced on the stream path", "CameraRefusal.${it.name}" in transport || "CameraRefusal.${it.name}" in state)
        }
        val reachable = CameraRefusal.entries.filter { it !in streamOnly }
        namedInSnapshot.forEach { name -> assertTrue("$name named in snapshot() is reachable", reachable.any { it.name == name }) }
        // The route sends exactly one refusal as 404 and every other as 503. It no longer decides that
        // inline: the mapping is `CameraRefusal.snapshotStatusCode`, so that `CameraRefusalTokenTest` can
        // pin it per refusal without a server. The route's obligation here is to delegate to it rather
        // than to re-derive a status of its own.
        assertTrue(
            "the snapshot route must take its status from the pure mapping",
            "CameraRefusal.snapshotStatusCode(result.reason)" in server,
        )
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

    @Test fun openApiDescribesCameraStreamSettingsAsDefaultsNotCeilings() {
        val schemas = openApi.getJSONObject("components").getJSONObject("schemas")
        val healthProperties = schemas.getJSONObject("CameraHealth").getJSONObject("properties")
        val bitrateDescription = healthProperties.getJSONObject("encode_kbps").getString("description")
        assertTrue(bitrateDescription.contains("override the configured camera_kbps default upward or downward"))
        assertFalse(bitrateDescription.contains("never above"))

        val snapshotParameters = openApi.getJSONObject("paths")
            .getJSONObject("/api/v1/camera/snapshot.jpg")
            .getJSONObject("get")
            .getJSONArray("parameters")
        val resolutionDescription = snapshotParameters.getJSONObject(0).getString("description")
        assertTrue(resolutionDescription.contains("overrides the configured camera_resolution default upward or downward"))
        assertFalse(resolutionDescription.contains("ceiling"))
    }
}
