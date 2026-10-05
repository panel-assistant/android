package io.panelassistant.android.camera

import org.json.JSONObject

/** The capped output sizes a stream or snapshot may ask for; the profile cap clamps every request. */
enum class CameraResolution(val wire: String, val width: Int, val height: Int) {
    P480("480p", 640, 480),
    P720("720p", 1280, 720),
    P1080("1080p", 1920, 1080);

    companion object {
        fun parse(raw: String?): CameraResolution? = entries.firstOrNull { it.wire == raw }

        /** Never more than [cap]: a URL may ask for less than the ceiling and never for more. */
        fun clamp(requested: CameraResolution, cap: CameraResolution): CameraResolution =
            if (requested.ordinal > cap.ordinal) cap else requested
    }
}

/** Why a consumer was refused a frame. Each token is safe to put in an HTTP body verbatim. */
enum class CameraRefusal(val token: String) {
    /** This board has no camera. */
    ABSENT("camera-unavailable"),
    /** The master switch is off. */
    DISABLED("camera-disabled"),
    /** The switch is on but Android has not granted the app the camera permission. */
    PERMISSION("camera-permission-needed"),
    /** The camera-in-use indicator could not be shown, so the camera did not open. */
    INDICATION("camera-indication-unavailable"),
    /** Android refused the camera-typed foreground service, which happens when no activity is visible. */
    FOREGROUND("camera-foreground-refused"),
    /** The session exists but produced no frame within the bounded wait. */
    STARVED("camera-starved"),
    /** A frame arrived but could not be encoded as JPEG; the session itself is healthy. */
    ENCODE("camera-encode-failed"),
    /** The device could not be opened or configured; see the status object for the classified fault. */
    FAILED("camera-unavailable"),
    /** No hardware H.264 encoder could be opened within the caps, so a stream is refused; snapshots still work. */
    STREAM_ENCODER("camera-stream-encoder-unavailable"),
    /** The stream transport already serves as many clients as it will; try again when one leaves. */
    BUSY("camera-busy"),
    /** The service is tearing down. */
    STOPPING("camera-stopping"),
    ;

    companion object {
        /**
         * Recovers the identifier a stored token came from. [ABSENT] and [FAILED] share
         * `camera-unavailable` on the wire deliberately — a consumer holding an HTTP body cannot act on
         * the difference — so the token alone is ambiguous and [cameraPresent] resolves it. Scanning
         * declaration order instead answers [ABSENT] for every `camera-unavailable`, which tells a panel
         * whose camera merely failed to open that it has no camera, and turns a recoverable 503 into a
         * 404 documented as "this board has no camera at all". Only a completed empty enumeration is
         * settled absence; a suppressed camera or an unanswered probe remains a 503 refusal. An
         * unrecognised token is likewise a fault rather than absent hardware.
         */
        fun fromToken(token: String, capabilityReason: CameraCapabilityReason): CameraRefusal = when (token) {
            ABSENT.token -> if (capabilityReason == CameraCapabilityReason.NOT_ENUMERATED) ABSENT else FAILED
            else -> entries.firstOrNull { it.token == token } ?: FAILED
        }

        /**
         * The snapshot route's status for a refusal. Only genuinely absent hardware is `404`; every
         * refusal a camera-bearing panel can raise is a `503`, because the resource exists and the
         * panel is declining to serve it right now.
         */
        fun snapshotStatusCode(refusal: CameraRefusal): Int =
            if (refusal == ABSENT) 404 else 503
    }
}

sealed interface SnapshotResult {
    class Jpeg(val bytes: ByteArray) : SnapshotResult
    data class Refused(val reason: CameraRefusal) : SnapshotResult
}

/**
 * What the HTTP surface needs from the camera owner: the status projection and a blocking snapshot.
 * The owner behind it opens the camera only for the duration of the request and closes it when no
 * other subscriber remains, so a caller must expect the open cost on every snapshot.
 */
interface CameraSurface {
    fun presentation(): CameraPresentation

    /** Blocking; call from an IO dispatcher. A null [requested] takes the profile default. */
    fun snapshot(requested: CameraResolution?): SnapshotResult
}

/** The projection for a board with no camera at all — the status object is emitted unconditionally. */
object AbsentCameraSurface : CameraSurface {
    override fun presentation(): CameraPresentation = CameraPresentation.absent()
    override fun snapshot(requested: CameraResolution?): SnapshotResult =
        SnapshotResult.Refused(CameraRefusal.ABSENT)
}

enum class CameraState(val wire: String) {
    /** No camera on this board. Emitted rather than omitted, so a consumer never infers from absence. */
    ABSENT("absent"),
    DISABLED("disabled"),
    PERMISSION_NEEDED("permission_needed"),
    /** Enabled, permitted, and closed because nobody is watching. Idle cost is zero here. */
    IDLE("idle"),
    OPENING("opening"),
    LIVE("live"),
    /** The retry ceiling was reached; the session stays visibly here until a subscriber reattaches. */
    DEGRADED("degraded"),
    STOPPING("stopping"),
}

/** Closed vocabulary. A raw exception message never becomes one of these; only its class name does. */
enum class CameraFault(val wire: String) {
    NONE("none"),
    INDICATION("indication"),
    FOREGROUND("foreground"),
    PERMISSION("permission"),
    OPEN("open"),
    CONFIGURE("configure"),
    DEVICE_ERROR("device_error"),
    DISCONNECTED("disconnected"),
    STARVED("starved"),
    /** The last frame could not be encoded; classified separately from starvation because the camera delivered. */
    ENCODE("encode"),
    /** The hardware H.264 stream encoder could not be opened within the caps, or failed while streaming. */
    STREAM_ENCODER("stream_encoder"),
}

/**
 * The `outcome` field's reset at the master switch.
 *
 * `outcome` remembers the last refusal so that a camera which stopped working is visible while it is
 * idle. The owner stamps `camera-disabled` when the switch ends a session and whenever a consumer is
 * refused while the switch is off, and until now nothing cleared it when the switch came back on: found
 * on a camera panel during the trial (2026-09-01), status and the diag line stayed at
 * `state=idle outcome=camera-disabled` after off→on until the next delivered frame, so an enabled,
 * healthy, idle camera read as refused to anyone reading `outcome` alone. The earliest truthful reset
 * is the enable itself, because the switch turning on is exactly what removes that refusal.
 *
 * It is the only refusal the switch may clear, and it may clear it only when nothing else is refusing.
 * A permission, foreground, indication, starvation, encode or stopping refusal was earned by something
 * the switch does not change, so it is returned untouched. And the switch's own refusal is not cleared
 * blindly either: the session's failure memory outlives the session that earned it, so an encoder hold,
 * a retry backoff or a degraded session can still be refusing consumers at the moment the switch comes
 * back on. `CameraSessionState.retainedRefusal` answers that from the same code an acquire consults,
 * and its answer is restated in place of the switch's. Nothing here ever claims a frame: `ok` says no
 * refusal stands, and `last_frame_age_ms` still says when a frame last arrived.
 */
object CameraOutcome {
    const val OK = "ok"

    /**
     * What `outcome` reads once the switch is on. [retained] is what the session would still refuse,
     * from `CameraSessionState.retainedRefusal` — null when it would refuse nothing. Idempotent, which
     * matters because the owner is called on any camera-key change and not only on an edge.
     */
    fun onEnable(outcome: String, retained: CameraRefusal?): String = when {
        outcome != CameraRefusal.DISABLED.token -> outcome
        retained != null -> retained.token
        else -> OK
    }
}

/** Which route is telling the room the camera is on. `none` is only legal when the camera is closed. */
enum class CameraIndication(val wire: String) { NONE("none"), OVERLAY("overlay"), LED("led") }

/**
 * The one public-safe projection of camera session health, rendered identically by `GET /api/v1/status`
 * and the `/api/v1/diag` dump — so severity cannot drift between the two.
 *
 * Modelled on [io.panelassistant.android.RendererAdmissionPresentation], including its boundary:
 * device paths, client addresses, credentials and raw exception text never enter this type. The
 * fault arrives already classified as [CameraFault]; [faultDetail] is a sanitized class name.
 *
 * [lastFrameAgeMs] is named for what it measures: the age of a real captured frame, never the age of a
 * verdict or a state change. The renderer's `observed_age_ms` was misread once because it was not.
 *
 * The stream fields describe the one encode session: what it is bound to ([encodeWidth] and friends,
 * null while no encoder runs) and what it actually delivered over the last few seconds
 * ([deliveredFps], [deliveredKbps]) — the measurement the plan's open question about whether the
 * targets are honoured is answered with. The panel's own stream URL may appear in [summary]; the
 * diagnostic line carries only the port, because the dump omits network identifiers.
 */
data class CameraPresentation(
    val state: CameraState,
    /**
     * Wire form of the last outcome: `ok`, or the refusal token that last blocked a consumer. A refusal
     * is cleared at two boundaries only — a session opening for a client, and the master switch turning
     * on, which clears the switch's own refusal alone ([CameraOutcome.onEnable]).
     */
    val outcome: String,
    val fault: CameraFault,
    val faultDetail: String?,
    /** How a degraded state may recover on its own, or `none` when nothing is degraded. */
    val recovery: String,
    /** Subscribers currently holding the session open, streams and snapshots alike. */
    val clients: Int,
    val lastFrameAgeMs: Long?,
    val consecutiveFailures: Int,
    val indication: CameraIndication,
    val summary: String,
    val action: String,
    /** Stream clients among [clients]. */
    val streamClients: Int = 0,
    /** The RTSP port while the transport is listening; null when the feature is off or the port could not be bound. */
    val streamPort: Int? = null,
    /**
     * The frame rate the current stream ASKED for, which [encodeFps] does not always equal.
     *
     * The capture rate is fixed by whoever opens the session, so a snapshot opens it at the configured
     * default and a stream arriving afterwards joins that session rather than reconfiguring it. A
     * consumer that reads [encodeFps] as the request therefore reports a clamped session as a healthy
     * one — "15 of 15" to somebody who asked for 30. Null while no stream lease is held; when two
     * streams share the session this is the first one's request, because the second's binding is
     * discarded where the session is joined rather than opened.
     */
    val requestedFps: Int? = null,
    val encoder: String? = null,
    val encodeWidth: Int? = null,
    val encodeHeight: Int? = null,
    val encodeFps: Int? = null,
    val encodeKbps: Int? = null,
    val deliveredFps: Double? = null,
    val deliveredKbps: Int? = null,
    /**
     * How the last answered snapshot got its frame. The exposure gate falls back on a spent budget when
     * a device never reports `CONTROL_AE_STATE`, and that fallback is otherwise invisible: the snapshot
     * still returns a picture, just not a settled one. Reporting it is what lets a panel taking the
     * fallback be told apart from one converging properly.
     */
    val snapshotExposure: SnapshotExposureOutcome = SnapshotExposureOutcome.NONE,
    /** How many snapshots that fallback has answered; `0` alongside `converged` is a healthy sensor. */
    val snapshotExposureFallbacks: Int = 0,
) {
    /** Stable flat JSON for `GET /api/v1/status`; `state` stays first for shell clients. */
    fun statusJson(): String = buildString {
        fun field(name: String, value: Any?) {
            if (length > 1) append(',')
            append(JSONObject.quote(name)).append(':')
            when (value) {
                null -> append("null")
                is Double -> append(Math.round(value * 10) / 10.0)
                is Number -> append(value)
                is Boolean -> append(value)
                else -> append(JSONObject.quote(value.toString()))
            }
        }
        append('{')
        field("state", state.wire)
        field("outcome", outcome)
        field("fault", fault.wire)
        field("fault_detail", faultDetail)
        field("recovery", recovery)
        field("clients", clients)
        field("last_frame_age_ms", lastFrameAgeMs)
        field("consecutive_failures", consecutiveFailures)
        field("indication", indication.wire)
        field("live", state == CameraState.LIVE)
        field("stream_clients", streamClients)
        field("stream_port", streamPort)
        field("requested_fps", requestedFps)
        field("encoder", encoder)
        field("encode_width", encodeWidth)
        field("encode_height", encodeHeight)
        field("encode_fps", encodeFps)
        field("encode_kbps", encodeKbps)
        field("delivered_fps", deliveredFps)
        field("delivered_kbps", deliveredKbps)
        field("snapshot_exposure", snapshotExposure.wire)
        field("snapshot_exposure_fallbacks", snapshotExposureFallbacks)
        field("summary", summary)
        field("action", action)
        append('}')
    }

    /** One terminal-safe line for the copy-paste support dump. No address, no URL: the port alone. */
    fun diagnosticLine(): String = buildString {
        append("[camera] state=").append(state.wire)
        append(" outcome=").append(outcome)
        append(" fault=").append(fault.wire)
        append(" detail=").append(faultDetail ?: "none")
        append(" recovery=").append(recovery)
        append(" clients=").append(clients)
        append(" last_frame=").append(lastFrameAgeMs?.let { fmtAge(it) } ?: "never")
        append(" failures=").append(consecutiveFailures)
        append(" indication=").append(indication.wire)
        append(" stream_clients=").append(streamClients)
        append(" stream_port=").append(streamPort?.toString() ?: "off")
        append(" encoder=").append(encoder ?: "none")
        append(" encode=")
        if (encodeWidth != null && encodeHeight != null) {
            append(encodeWidth).append('x').append(encodeHeight).append('@').append(encodeFps ?: 0).append('/').append(encodeKbps ?: 0).append("kbps")
        } else {
            append("none")
        }
        append(" delivered=")
        if (deliveredFps != null) {
            append(Math.round(deliveredFps * 10) / 10.0).append("fps/").append(deliveredKbps ?: 0).append("kbps")
        } else {
            append("none")
        }
        // Appended rather than placed beside `encode=`, where it reads more naturally, because
        // consumers pin the encode-to-delivered run of this line as one substring. The dump is flat
        // key=value text whose order carries no contract, so the field goes where it costs nothing.
        append(" requested_fps=").append(requestedFps?.toString() ?: "none")
        append(" snapshot_exposure=").append(snapshotExposure.wire)
        append('/').append(snapshotExposureFallbacks)
    }

    companion object {
        /**
         * The projection for a panel offering no camera. [reason] says which of the three ways of
         * arriving at "no" this is, carried in `fault_detail` — already the sanitized sub-classification
         * field — so no new field is added to a status object every consumer parses.
         *
         * Each case gets an action, because "no camera on this panel" with nothing to do next is the
         * whole complaint: a suppressed camera is somebody's decision and reversible, an enumeration
         * that found nothing is a fact about the board, and a probe that has not answered is neither and
         * must not be read as either. The enumeration case names the profile flag *and* the indication
         * route, because a profile with no off-display indication may not enable the camera at all
         * (privacy contract §2) and guidance that stops at the flag would send somebody down a path the
         * panel will refuse.
         */
        fun absent(reason: CameraCapabilityReason = CameraCapabilityReason.NOT_ENUMERATED): CameraPresentation {
            val summary = when (reason) {
                CameraCapabilityReason.SUPPRESSED_BY_PROFILE -> "no camera: the device profile suppresses it"
                CameraCapabilityReason.UNDETERMINED -> "checking whether this panel has a camera"
                else -> "no camera on this panel"
            }
            val action = when (reason) {
                CameraCapabilityReason.SUPPRESSED_BY_PROFILE ->
                    "the profile sets hardware.camera false; edit the profile to offer this panel's camera"
                CameraCapabilityReason.UNDETERMINED ->
                    "the camera enumeration has not answered yet; this is not yet a statement about the hardware"
                else ->
                    "if this panel has a camera, declare hardware.camera in its profile alongside an LED the panel can light"
            }
            return CameraPresentation(
                state = CameraState.ABSENT, outcome = CameraRefusal.ABSENT.token, fault = CameraFault.NONE,
                faultDetail = reason.wire, recovery = "none", clients = 0, lastFrameAgeMs = null,
                consecutiveFailures = 0, indication = CameraIndication.NONE,
                summary = summary, action = action,
            )
        }

        fun disabled(): CameraPresentation = CameraPresentation(
            state = CameraState.DISABLED, outcome = CameraRefusal.DISABLED.token, fault = CameraFault.NONE,
            faultDetail = null, recovery = "none", clients = 0, lastFrameAgeMs = null,
            consecutiveFailures = 0, indication = CameraIndication.NONE,
            summary = "camera off", action = "turn on the camera setting to serve snapshots and a stream",
        )

        fun permissionNeeded(streamPort: Int? = null): CameraPresentation = CameraPresentation(
            state = CameraState.PERMISSION_NEEDED, outcome = CameraRefusal.PERMISSION.token,
            fault = CameraFault.PERMISSION, faultDetail = null, recovery = "none", clients = 0,
            lastFrameAgeMs = null, consecutiveFailures = 0, indication = CameraIndication.NONE,
            summary = "camera on, but Android has not granted the permission",
            action = "grant the camera permission on the panel when prompted",
            streamPort = streamPort,
        )

        internal fun fmtAge(ms: Long): String {
            val s = ms / 1000
            return when {
                s < 60 -> "${s}s"
                s < 3600 -> "${s / 60}m${s % 60}s"
                else -> "${s / 3600}h${(s % 3600) / 60}m"
            }
        }
    }
}
