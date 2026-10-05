package io.panelassistant.android.camera

import io.panelassistant.android.camera.CameraSessionState.Admission
import io.panelassistant.android.testsupport.TestSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rate a stream ASKED for is not always the rate the encoder was given, and the difference is
 * invisible to anyone reading `encode_fps` alone.
 *
 * The capture rate is fixed by whoever OPENS the session. A snapshot opens it at the configured
 * default, and a stream arriving afterwards joins that session rather than reconfiguring it, so the
 * encoder is bound to `minOf(binding.fps, boundFps)`. A consumer that reads `encode_fps` as the
 * request therefore reports the one case where the answer is "no" as a clean "15 of 15": it moves the
 * target to wherever the panel landed and calls that success. `requested_fps` exists so it cannot.
 */
class CameraRequestedFpsContractTest {
    private val state = CameraSessionState { CameraSessionPolicy(frameIntervalMs = 66L) }

    /**
     * The behavioural case the projection is built on: a snapshot opens the session, a stream then
     * asks for 30, and the admission is a JOIN — the session is not reopened, so the capture rate the
     * snapshot set stands, while the stream's own request survives on the state as `streamBinding`.
     */
    @Test fun aStreamJoiningASnapshotOpenedSessionKeepsItsOwnRequestedRate() {
        val opened = state.acquire(gate = null, nowMs = 1_000L, kind = LeaseKind.SNAPSHOT)
        assertTrue("a snapshot on an idle camera opens the session", opened is Admission.Open)
        assertEquals("a snapshot carries no stream binding at all", null, state.streamBinding)
        state.openSucceeded((opened as Admission.Open).attempt)

        val joined = state.acquire(
            gate = null,
            nowMs = 3_000L,
            kind = LeaseKind.STREAM,
            binding = StreamBinding(fps = 30, kbps = 2_000),
        )
        assertTrue(
            "the stream joins the session the snapshot opened rather than opening its own — which is " +
                "exactly why the capture rate is not raised to meet it",
            joined is Admission.Join,
        )
        assertTrue("the joining stream is still the one that binds the encoder", (joined as Admission.Join).startEncoder)
        assertEquals(
            "the stream's own request survives on the session state, and is the only record of it",
            30,
            state.streamBinding?.fps,
        )
    }

    /** Both numbers reach a consumer, separately, in both renderings of the projection. */
    @Test fun theProjectionCarriesTheRequestBesideTheRateTheEncoderWasGiven() {
        val clamped = CameraPresentation(
            state = CameraState.LIVE, outcome = "ok", fault = CameraFault.NONE, faultDetail = null,
            recovery = "none", clients = 2, lastFrameAgeMs = 40L, consecutiveFailures = 0,
            indication = CameraIndication.OVERLAY, summary = "", action = "none",
            streamClients = 1, streamPort = 8554, requestedFps = 30, encoder = "c2.rk.avc.encoder",
            encodeWidth = 1280, encodeHeight = 720, encodeFps = 15, encodeKbps = 2_000,
            deliveredFps = 14.9, deliveredKbps = 1_660,
        )

        val json = JSONObject(clamped.statusJson())
        assertEquals("the request the person made", 30, json.getInt("requested_fps"))
        assertEquals("the rate the encoder was actually given", 15, json.getInt("encode_fps"))
        assertTrue(
            "the support dump must carry the request too, or a copied diagnostic hides the clamp",
            clamped.diagnosticLine().contains("requested_fps=30"),
        )

        val noStream = clamped.copy(requestedFps = null, streamClients = 0)
        assertTrue(
            "with no stream lease there is no request to report, and null is not zero",
            JSONObject(noStream.statusJson()).isNull("requested_fps"),
        )
        assertTrue(noStream.diagnosticLine().contains("requested_fps=none"))
    }

    /** A field consumers are told is always present must be declared as always present. */
    @Test fun theStatusSchemaDeclaresTheRequestedRateWireFormat() {
        // Source-text reason: the published OpenAPI schema is the status wire contract.
        val spec = JSONObject(TestSources.asset("openapi.json").readText())
        val camera = spec.getJSONObject("components").getJSONObject("schemas").getJSONObject("CameraHealth")
        assertNotNull(camera.getJSONObject("properties").getJSONObject("requested_fps"))
        assertTrue(
            "requested_fps is emitted unconditionally, so it belongs in the required list",
            camera.getJSONArray("required").toString().contains("requested_fps"),
        )
    }
}
