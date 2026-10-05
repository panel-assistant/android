package io.panelassistant.android.camera

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure behaviour of the projection that `GET /api/v1/status` and `/api/v1/diag` both render.
 * `CameraSurfaceContractTest` binds it to those two surfaces and to the OpenAPI document; this file
 * proves the projection itself, without an Activity, a camera device or a running server.
 */
class CameraPresentationTest {

    @Test fun absentReportsTheAbsentStateAndItsToken() {
        val p = CameraPresentation.absent()
        assertEquals(CameraState.ABSENT, p.state)
        assertEquals(CameraRefusal.ABSENT.token, p.outcome)
        assertEquals(CameraFault.NONE, p.fault)
        // The absent state carries which of the three ways of arriving at "no" this is.
        assertEquals(CameraCapabilityReason.NOT_ENUMERATED.wire, p.faultDetail)
        assertEquals(0, p.clients)
        assertNull(p.lastFrameAgeMs)
        assertEquals(CameraIndication.NONE, p.indication)
        assertTrue(p.summary.contains("no camera"))
        // An absent camera used to end the conversation. It now says what would change the answer,
        // including the indication route, because a profile without one may not enable a camera.
        assertTrue("must name the profile flag", p.action.contains("hardware.camera"))
        assertTrue("must name the indication requirement", p.action.contains("LED"))
    }

    @Test fun disabledReportsTheDisabledStateAndItsToken() {
        val p = CameraPresentation.disabled()
        assertEquals(CameraState.DISABLED, p.state)
        assertEquals(CameraRefusal.DISABLED.token, p.outcome)
        assertEquals(CameraFault.NONE, p.fault)
        assertTrue("must guide the user to the setting", p.action.contains("camera setting"))
    }

    @Test fun permissionNeededReportsTheClassifiedPermissionFault() {
        val p = CameraPresentation.permissionNeeded()
        assertEquals(CameraState.PERMISSION_NEEDED, p.state)
        assertEquals(CameraRefusal.PERMISSION.token, p.outcome)
        assertEquals(CameraFault.PERMISSION, p.fault)
        assertTrue(p.action.contains("permission"))
    }

    @Test fun statusJsonParsesAndCarriesEveryField() {
        val j = JSONObject(CameraPresentation.absent().statusJson())
        listOf(
            "state", "outcome", "fault", "fault_detail", "recovery", "clients",
            "last_frame_age_ms", "consecutive_failures", "indication", "live",
            "stream_clients", "stream_port", "encoder", "encode_width", "encode_height", "encode_fps", "encode_kbps",
            "delivered_fps", "delivered_kbps", "snapshot_exposure", "snapshot_exposure_fallbacks",
            "summary", "action",
        ).forEach { assertTrue("missing $it", j.has(it)) }
        assertEquals("absent", j.getString("state"))
        assertEquals("none", j.getString("fault"))
        // optString rather than getString: a regression that drops the reason leaves JSON null, and
        // getString throws JSONException there, which reports as a test error rather than as the
        // assertion failure it is.
        assertEquals(CameraCapabilityReason.NOT_ENUMERATED.wire, j.optString("fault_detail", ""))
        assertTrue(j.isNull("last_frame_age_ms"))
        assertFalse(j.getBoolean("live"))
        assertEquals(0, j.getInt("stream_clients"))
        listOf("stream_port", "encoder", "encode_width", "encode_height", "encode_fps", "encode_kbps", "delivered_fps", "delivered_kbps")
            .forEach { assertTrue("$it is null while nothing streams", j.isNull(it)) }
    }

    @Test fun streamFactsAreCarriedAndTheDeliveredRateIsRoundedToOneDecimal() {
        val p = CameraPresentation.absent().copy(
            state = CameraState.LIVE, clients = 2, streamClients = 1, streamPort = 8554, encoder = "OMX.rk.video_encoder.avc",
            encodeWidth = 1280, encodeHeight = 720, encodeFps = 15, encodeKbps = 2_000, deliveredFps = 14.96, deliveredKbps = 1_870,
        )
        val j = JSONObject(p.statusJson())
        // Presence first, as assertions: a field that stops being emitted must fail this test by
        // assertion, not by the JSON accessor throwing.
        listOf("stream_clients", "stream_port", "encoder", "encode_width", "encode_height", "encode_fps", "encode_kbps", "delivered_fps", "delivered_kbps")
            .forEach { assertTrue("missing $it", j.has(it)) }
        assertEquals(1, j.getInt("stream_clients"))
        assertEquals(8554, j.getInt("stream_port"))
        assertEquals("OMX.rk.video_encoder.avc", j.getString("encoder"))
        assertEquals(1280, j.getInt("encode_width"))
        assertEquals(720, j.getInt("encode_height"))
        assertEquals(15, j.getInt("encode_fps"))
        assertEquals(2_000, j.getInt("encode_kbps"))
        assertEquals(15.0, j.getDouble("delivered_fps"), 0.0)
        assertEquals(1_870, j.getInt("delivered_kbps"))
        val line = p.diagnosticLine()
        assertTrue(line, line.contains(" stream_clients=1 stream_port=8554 encoder=OMX.rk.video_encoder.avc encode=1280x720@15/2000kbps delivered=15.0fps/1870kbps"))
        assertFalse("the dump carries the port but never an address or URL", line.contains("rtsp://"))
    }

    /**
     * The exposure gate answers a snapshot either way — converged, or on a spent budget — so a panel
     * whose sensor never reports `CONTROL_AE_STATE` returns a picture that may still be dark and is
     * otherwise indistinguishable from a settled one. The projection is where that difference becomes
     * visible, so both the JSON and the dump must carry it.
     */
    @Test fun theSnapshotExposureDispositionIsVisibleInBothTheStatusObjectAndTheDump() {
        // Presence as an assertion before any accessor: a field that stops being emitted must fail this
        // test by assertion, not by the JSON accessor throwing, which reports as an error instead.
        val nothingYet = JSONObject(CameraPresentation.absent().statusJson())
        assertTrue("missing snapshot_exposure", nothingYet.has("snapshot_exposure"))
        assertTrue("missing snapshot_exposure_fallbacks", nothingYet.has("snapshot_exposure_fallbacks"))
        assertEquals("nothing is claimed before a snapshot", "none", nothingYet.optString("snapshot_exposure", ""))
        assertEquals(0, nothingYet.optInt("snapshot_exposure_fallbacks", -1))

        val converged = CameraPresentation.absent().copy(
            state = CameraState.IDLE, snapshotExposure = SnapshotExposureOutcome.CONVERGED,
        )
        assertEquals("converged", JSONObject(converged.statusJson()).optString("snapshot_exposure", ""))
        assertTrue(converged.diagnosticLine(), converged.diagnosticLine().contains(" snapshot_exposure=converged/0"))

        val fallingBack = CameraPresentation.absent().copy(
            state = CameraState.IDLE, snapshotExposure = SnapshotExposureOutcome.BUDGET, snapshotExposureFallbacks = 7,
        )
        val j = JSONObject(fallingBack.statusJson())
        assertTrue("missing snapshot_exposure", j.has("snapshot_exposure"))
        assertTrue("missing snapshot_exposure_fallbacks", j.has("snapshot_exposure_fallbacks"))
        assertEquals("budget", j.optString("snapshot_exposure", ""))
        assertEquals("the count is what separates one cold miss from a sensor that never converges", 7, j.optInt("snapshot_exposure_fallbacks", -1))
        assertTrue(fallingBack.diagnosticLine(), fallingBack.diagnosticLine().contains(" snapshot_exposure=budget/7"))
    }

    @Test fun permissionNeededCarriesTheListeningPortSoAUserSeesTheStreamIsWaitingOnThem() {
        assertEquals(8554, CameraPresentation.permissionNeeded(streamPort = 8554).streamPort)
        assertNull(CameraPresentation.permissionNeeded().streamPort)
        assertNull("off means not listening", CameraPresentation.disabled().streamPort)
    }

    @Test fun liveIsTrueOnlyForTheLiveState() {
        CameraState.entries.forEach { state ->
            val json = JSONObject(CameraPresentation.absent().copy(state = state).statusJson())
            assertEquals("$state", state == CameraState.LIVE, json.getBoolean("live"))
        }
    }

    @Test fun diagnosticLineIsOneLineAndSelfLabellingAndCarriesNoNewline() {
        val line = CameraPresentation.disabled().diagnosticLine()
        assertTrue(line.startsWith("[camera]"))
        assertFalse(line.contains("\n"))
        assertTrue(line.contains("state=disabled"))
        assertTrue(line.contains("outcome=camera-disabled"))
        assertTrue(line.contains("last_frame=never"))
        assertTrue(line.contains("indication=none"))
        assertTrue(line.contains("stream_port=off"))
        assertTrue(line.contains("encoder=none encode=none delivered=none"))
    }

    @Test fun diagnosticLineFormatsAKnownLastFrameAge() {
        val line = CameraPresentation.absent().copy(state = CameraState.LIVE, lastFrameAgeMs = 65_000L).diagnosticLine()
        assertFalse(line.contains("\n"))
        assertTrue(line.contains("last_frame=1m5s"))
    }

    // --- CameraOutcome: the reset at the master switch ----------------------------------------------
    //
    // The decision alone; `CameraSessionStateTest` drives the real session that supplies its argument.

    /**
     * The trial residual (2026-09-01): off, then on, with nobody watching and nothing else refusing.
     * Status must say so before any frame arrives, without claiming one.
     */
    @Test fun disableThenEnableBeforeAnyViewerReadsOkAndClaimsNoFrame() {
        val outcome = CameraOutcome.onEnable(CameraRefusal.DISABLED.token, retained = null)
        assertEquals(CameraOutcome.OK, outcome)
        val p = CameraPresentation.absent().copy(state = CameraState.IDLE, outcome = outcome, lastFrameAgeMs = null)
        val j = JSONObject(p.statusJson())
        assertEquals("idle", j.getString("state"))
        assertEquals("ok", j.getString("outcome"))
        assertTrue("no frame is fabricated by the reset", j.isNull("last_frame_age_ms"))
        assertFalse(j.getBoolean("live"))
    }

    /** An enable that is not an edge — any camera key change reaches the owner — must be a no-op. */
    @Test fun enableIsIdempotentOnAnOutcomeThatIsAlreadyOk() {
        assertEquals(CameraOutcome.OK, CameraOutcome.onEnable(CameraOutcome.OK, retained = null))
        assertEquals(CameraOutcome.OK, CameraOutcome.onEnable(CameraOutcome.OK, retained = CameraRefusal.FAILED))
    }

    /**
     * Off, then on, with the Android permission still missing. The reset does not stamp the permission
     * refusal — that is the presentation's short-circuit and the lease gate's job — and what a consumer
     * sees is the permission state with its own token, not `ok` and not `camera-disabled`.
     */
    @Test fun disableThenEnableWithMissingPermissionShowsThePermissionStateNotTheSwitch() {
        assertEquals(
            "the reset never invents a permission refusal; the gate stamps it when a consumer asks",
            CameraOutcome.OK,
            CameraOutcome.onEnable(CameraRefusal.DISABLED.token, retained = null),
        )
        val p = CameraPresentation.permissionNeeded()
        assertEquals(CameraState.PERMISSION_NEEDED, p.state)
        assertEquals(CameraRefusal.PERMISSION.token, p.outcome)
        assertTrue(p.diagnosticLine().contains("state=permission_needed outcome=camera-permission-needed"))
    }

    /** Every refusal the switch did not cause stands, whatever the session retains. */
    @Test fun enablePreservesEveryRefusalTheSwitchDidNotCause() {
        CameraRefusal.entries.filter { it != CameraRefusal.DISABLED }.forEach { refusal ->
            assertEquals(refusal.name, refusal.token, CameraOutcome.onEnable(refusal.token, retained = null))
            assertEquals("$refusal, still refusing", refusal.token, CameraOutcome.onEnable(refusal.token, CameraRefusal.FAILED))
        }
    }

    /** A refusal that still stands is restated in place of the switch's, never cleared. */
    @Test fun enableRestatesWhateverTheSessionStillRefuses() {
        CameraRefusal.entries.forEach { retained ->
            assertEquals(
                "$retained still refuses, so the switch may not report a clear camera",
                retained.token,
                CameraOutcome.onEnable(CameraRefusal.DISABLED.token, retained),
            )
        }
    }

    /** Status and the diag line are one projection: the reset cannot show in one and not the other. */
    @Test fun statusAndDiagnosticLineAgreeOnTheResetOutcome() {
        listOf(
            CameraOutcome.onEnable(CameraRefusal.DISABLED.token, retained = null),
            CameraOutcome.onEnable(CameraRefusal.DISABLED.token, CameraRefusal.STREAM_ENCODER),
            CameraOutcome.onEnable(CameraRefusal.STARVED.token, retained = null),
        ).forEach { outcome ->
            val p = CameraPresentation.absent().copy(state = CameraState.IDLE, outcome = outcome)
            assertEquals(outcome, JSONObject(p.statusJson()).getString("outcome"))
            assertTrue(p.diagnosticLine(), p.diagnosticLine().contains(" outcome=$outcome "))
        }
    }

    // --- CameraResolution -----------------------------------------------------------------------

    @Test fun parseRejectsAnyValueOutsideTheClosedVocabulary() {
        assertNull(CameraResolution.parse(null))
        assertNull(CameraResolution.parse(""))
        assertNull(CameraResolution.parse("4k"))
        assertNull(CameraResolution.parse("720P"))
        assertNull(CameraResolution.parse(" 720p"))
        assertEquals(CameraResolution.P480, CameraResolution.parse("480p"))
        assertEquals(CameraResolution.P720, CameraResolution.parse("720p"))
        assertEquals(CameraResolution.P1080, CameraResolution.parse("1080p"))
    }

    @Test fun clampNeverExceedsTheCapAndNeverRaisesBelowTheRequest() {
        assertEquals(CameraResolution.P480, CameraResolution.clamp(CameraResolution.P1080, CameraResolution.P480))
        assertEquals(CameraResolution.P720, CameraResolution.clamp(CameraResolution.P720, CameraResolution.P1080))
        assertEquals(CameraResolution.P1080, CameraResolution.clamp(CameraResolution.P1080, CameraResolution.P1080))
        // A request for less than the cap is honoured, never raised to the ceiling.
        assertEquals(CameraResolution.P480, CameraResolution.clamp(CameraResolution.P480, CameraResolution.P1080))
    }

    // --- fmtAge -----------------------------------------------------------------------------------

    @Test fun ageFormattingCoversSecondsMinutesAndHours() {
        assertEquals("0s", CameraPresentation.fmtAge(0L))
        assertEquals("23s", CameraPresentation.fmtAge(23_000L))
        assertEquals("59s", CameraPresentation.fmtAge(59_000L))
        assertEquals("1m0s", CameraPresentation.fmtAge(60_000L))
        assertEquals("1m5s", CameraPresentation.fmtAge(65_000L))
        assertEquals("47m59s", CameraPresentation.fmtAge((47 * 60 + 59) * 1000L))
        assertEquals("1h0m", CameraPresentation.fmtAge(3_600_000L))
        assertEquals("5h12m", CameraPresentation.fmtAge((5 * 3600 + 12 * 60) * 1000L))
    }
}
