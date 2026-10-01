package io.github.maxlyth.hapaneld.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import io.github.maxlyth.hapaneld.camera.CameraSessionState.Admission
import io.github.maxlyth.hapaneld.camera.CameraSessionState.Failure
import io.github.maxlyth.hapaneld.camera.CameraSessionState.Phase
import io.github.maxlyth.hapaneld.camera.CameraSessionState.Release
import io.github.maxlyth.hapaneld.util.HaTransportFault
import io.github.maxlyth.hapaneld.util.localIpv4
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** How the owner opens an encoder; a seam so the `MediaCodec` adapter is not welded into the lifecycle. */
fun interface EncoderFactory {
    fun open(width: Int, height: Int, fps: Int, kbps: Int, handler: Handler, listener: VideoEncoder.Listener): EncoderOpen
}

/**
 * The Android adapter around [CameraSessionState]: it drives the device, the light and the foreground
 * standing, and every ownership decision is the state machine's. Stream, snapshot and the later motion
 * detector are subscribers holding a [Lease]; the device opens on the first lease and closes on the
 * last, and no subscriber can open or close the hardware on another's behalf.
 *
 * Hardware belongs to an [Attempt], never to the owner. Each `openCamera` — the first and every reopen
 * — creates its own attempt object holding its own reader, device and session, and every callback
 * carries that object. A callback from a superseded attempt can therefore release only what that
 * attempt owned; it is recognised by identity and never reaches a newer attempt's hardware or the
 * state machine.
 *
 * Gate order before the device opens, each classified when it refuses: the board has a camera, the
 * master switch is on, Android has granted the permission, the camera-in-use light is confirmed
 * positive for the current screen state, and Android has confirmed camera-typed foreground standing.
 * Only then `openCamera`. The light stays a prerequisite for as long as the session runs: every
 * watchdog tick re-checks it and closes the session when it is negative.
 *
 * Foreground standing can only be started while an activity is visible. Both camera-bearing profiles
 * keep the dashboard activity resumed through screen-off, so a session can start at any time there; on
 * a panel whose screen-off route sleeps Android, a cold start is refused as a foreground refusal whose
 * action says to wake the panel. A session already running survives either.
 *
 * A session is one repeating YUV capture into an [ImageReader]. Frames are handed to subscribers no
 * faster than the configured frame-rate cap: the sensor may capture faster, and frames arriving sooner
 * than the cap allows are observed for liveness but not delivered. The encoder is one more consumer of
 * those paced frames: it runs exactly while a stream lease exists, bound by the first stream client's
 * parameters within the caps, fed by buffer copy at its own paced rate, and its output fans out through
 * the [transport]. Nothing is kept warm: when the last lease closes, the encoder, the attempt's
 * hardware, the handler thread and the foreground standing are released.
 */
class CameraSessionOwner(
    private val context: Context,
    /**
     * Whether this panel has a camera. A supplier rather than a value because the answer may come from
     * enumerating Android's cameras, which must not happen while the service is constructing on the
     * main thread; every call site below reads the cheap switch first so an off panel never enumerates.
     */
    private val hasCamera: () -> Boolean,
    private val enabled: () -> Boolean,
    // Configured defaults, not ceilings: an omitted URL parameter takes these, a supplied one wins.
    // See StreamRequest for why the clamping was removed.
    private val defaultResolution: () -> CameraResolution,
    private val defaultFps: () -> Int,
    private val defaultKbps: () -> Int,
    /** Exposure bias in stops; clamped to the sensor's advertised range at configure time. */
    private val exposureEv: () -> Float = { 0f },
    private val permissionGranted: () -> Boolean,
    private val indicator: CameraIndicator,
    private val foreground: CameraForegroundGate,
    private val transport: CameraStreamTransport = AbsentStreamTransport,
    private val encoderFactory: EncoderFactory = EncoderFactory { w, h, fps, kbps, handler, listener ->
        MediaCodecH264Encoder.open(w, h, fps, kbps, handler, listener)
    },
    private val localAddress: () -> String? = ::localIpv4,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    /**
     * Why the panel does or does not have a camera, for the projection alone — never for a gate, which
     * reads [hasCamera] as it always has. Supplied by the one place that reads the profile declaration
     * and the enumeration probe. The default keeps every construction that does not care working, and
     * says only what [hasCamera] already said.
     */
    private val capabilityReason: () -> CameraCapabilityReason = {
        if (hasCamera()) CameraCapabilityReason.PRESENT else CameraCapabilityReason.NOT_ENUMERATED
    },
) : CameraSurface, CameraStreamSource {

    private val lock = Any()
    private var policy = CameraSessionPolicy(frameIntervalMs = 1_000L / 15)
    private val state = CameraSessionState { policy }
    private var outcome = "ok"
    private var fault = CameraFault.NONE
    private var faultDetail: String? = null
    private var recovery = "none"
    private var admissionClosed = false

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    /** The attempt whose hardware is current, or null. Only ever replaced under [lock]. */
    private var current: Attempt? = null
    private var boundTarget: CameraResolution? = null
    private var boundFps = 15

    /**
     * What the current repeating request was built for, or null before one exists.
     *
     * One repeating request serves both the snapshot and the stream, so the processing budget cannot be
     * per frame — but it must not be fixed at session open either. Deciding it once was the defect the
     * first submission carried: a stream that joined a snapshot-opened session inherited the expensive
     * pipeline on every frame, which is exactly the cost the policy exists to avoid, and a snapshot on a
     * stream-opened session was stuck with the cheap one. The request now follows demand, and this field
     * exists so an unchanged demand does not re-issue it for nothing.
     */
    private var appliedForStream: Boolean? = null
    /**
     * Paces delivery to the session's bound rate. A [FramePacer] rather than a bare "at least one interval
     * since the last delivery" comparison: the sensor ticks every 33.3 ms and a 15 fps interval is 66 ms,
     * so a frame landing one millisecond early failed the bare comparison, the next one passed 100 ms
     * after the last delivery, and two thirds of the cap was all that ever reached anyone (10 of 15 and
     * 20 of 30, measured on both camera boards). The pacer's slop admits that frame; its cumulative due
     * times keep the long-run rate at the cap.
     */
    private var deliveryPacer = FramePacer(15)

    /** One open attempt and the hardware it alone owns. */
    private inner class Attempt(val id: Long) {
        /**
         * Whether the device has reported converged auto-exposure for this attempt. Written from the
         * capture callback on the camera thread and read under no lock, so it is volatile; a stale read
         * costs at most one more frame of waiting, never a wrong picture.
         */
        @Volatile var exposureSettled: Boolean = false
        var reader: ImageReader? = null
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        /** The capture size this attempt configured; the encoder, if wanted, encodes exactly this. */
        var size: Size? = null

        /** Kept so the repeating request can be rebuilt when stream demand changes. */
        var characteristics: CameraCharacteristics? = null
        var fpsRange: Range<Int>? = null

        /**
         * The encoder this attempt opened, if a stream wanted one. It is the attempt's hardware, not
         * the session's: it is configured for this attempt's capture size, fed only this attempt's
         * frames, and comes down with this attempt's capture on every path (a reopen, a degrade, a
         * finish). Keeping it here is what lets an ended attempt's teardown close its own codec after
         * the next attempt has opened — and only its own.
         */
        var encoder: VideoEncoder? = null
        var encoderPacer: FramePacer? = null
        /** The parameter sets [encoder] published; a joiner is granted them while the encoder runs. */
        var streamParams: StreamParams? = null

        /**
         * Releases the foreground standing this attempt promoted. Teardown runs out of order — an
         * ended session's finish can reach here after the next session has opened and promoted — so
         * the release names this attempt and does nothing once a newer one holds standing.
         */
        fun releaseStanding() = foreground.demote(id)

        /**
         * Close this attempt's codec, and retract what it advertised through the transport — but only
         * if the advertisement is still its own: a newer attempt's encoder may have published since,
         * and its sets must stay up. Always safe to call, whoever is live now.
         */
        fun closeEncoder() {
            val enc: VideoEncoder?
            val retract: Boolean
            synchronized(lock) {
                enc = encoder; encoder = null
                encoderPacer = null
                streamParams = null
                retract = advertisedBy == id
                if (retract) advertisedBy = null
            }
            if (enc == null) return
            runCatching { enc.close() }
            // Outside the lock, so a newer encoder may publish between the decision and this call; the
            // transport retracts only this attempt's sets.
            if (retract) transport.onEncoderStopped(id)
        }

        fun release() {
            // The encoder stops first so nothing is produced while the capture beneath it is torn down.
            closeEncoder()
            val s: CameraCaptureSession?
            val d: CameraDevice?
            val r: ImageReader?
            synchronized(lock) {
                s = session; session = null
                d = device; device = null
                r = reader; reader = null
            }
            runCatching { s?.close() }
            runCatching { d?.close() }
            runCatching { r?.close() }
        }
    }

    /**
     * The attempt whose parameter sets the transport currently advertises, so that closing a codec
     * retracts its own advertisement and never a newer encoder's. Stamped when sets are published.
     */
    private var advertisedBy: Long? = null
    /**
     * Settled by the running encoder attempt: its parameter sets, or why there is none. The session's,
     * not an attempt's: a joiner rides a bounded reopen out on it. It is replaced when a new session
     * starts, so no joiner can be granted an ended session's sets.
     */
    private var streamReady = CompletableFuture<StreamOutcome>()
    private val stats = StreamStats()

    private sealed interface StreamOutcome {
        data class Ready(val params: StreamParams) : StreamOutcome
        data class Refused(val reason: CameraRefusal) : StreamOutcome
    }

    init {
        publishPermissionPrompt(freshEnable = false)
        transport.setListening(enabled() && hasCamera())
    }

    /** A subscriber's claim on the open session. Close exactly once; closing the last one closes the device. */
    inner class Lease internal constructor(private val id: Long, val generation: Long) : AutoCloseable {
        private var open = true
        override fun close() {
            val release: Release
            val ended: Attempt?
            val generation: Long
            synchronized(lock) {
                if (!open) return
                open = false
                release = state.release(id)
                ended = if (release == Release.Close) takeCurrentLocked() else null
                if (release == Release.Close) {
                    val now = nowMs()
                    val retained = state.retainedRefusal(now, LeaseKind.SNAPSHOT)
                        ?: state.retainedRefusal(now, LeaseKind.STREAM)
                    outcome = retained?.token ?: CameraOutcome.OK
                }
                generation = state.generation
            }
            when (release) {
                Release.Close -> post { finishAttempt(ended, stopping = false, endedGeneration = generation) }
                Release.StopEncoder -> post { stopEncoderIfUnwanted() }
                Release.None -> Unit
            }
        }
    }

    // ---- CameraSurface ---------------------------------------------------------------------------

    override fun presentation(): CameraPresentation {
        // Both the transport's facts and the interface walk stay outside the owner lock, and so does
        // the camera capability: it may enumerate Android's cameras, which is not a read to make under
        // this lock. It is safe to read early because camera presence does not change at runtime.
        val facts = transport.facts()
        val address = facts.port?.let { localAddress() }
        val reason = capabilityReason()
        val present = reason.capable
        return synchronized(lock) {
            when {
                !present -> CameraPresentation.absent(reason)
                state.phase == Phase.STOPPING -> current(facts, address)
                !enabled() -> CameraPresentation.disabled()
                !permissionGranted() -> CameraPresentation.permissionNeeded(streamPort = facts.port)
                else -> current(facts, address)
            }
        }
    }

    override fun snapshot(requested: CameraResolution?): SnapshotResult {
        val lease = acquire(requested) ?: return SnapshotResult.Refused(lastRefusal())
        val waiter = CompletableFuture<ByteArray?>()
        try {
            synchronized(lock) { state.addWaiter(waiter, nowMs()) }
            val bytes = runCatching { waiter.get(SNAPSHOT_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            if (bytes != null) return synchronized(lock) {
                when {
                    state.phase == Phase.STOPPING -> SnapshotResult.Refused(CameraRefusal.STOPPING)
                    !enabled() || lease.generation != state.generation -> SnapshotResult.Refused(CameraRefusal.DISABLED)
                    else -> SnapshotResult.Jpeg(bytes)
                }
            }
            // Null means one of three things, told apart by what the session did meanwhile: a teardown
            // that drained the waiter (report the teardown's own refusal), a frame that arrived but would
            // not encode (the encoder's fault, not the camera's), or genuinely no frame in time.
            val refusal = synchronized(lock) {
                when {
                    state.phase == Phase.STOPPING -> CameraRefusal.STOPPING
                    !enabled() -> CameraRefusal.DISABLED
                    state.phase == Phase.DEGRADED -> CameraRefusal.FAILED
                    fault == CameraFault.ENCODE -> CameraRefusal.ENCODE
                    else -> CameraRefusal.STARVED
                }
            }
            return SnapshotResult.Refused(refusal)
        } finally {
            synchronized(lock) { state.removeWaiter(waiter) }
            lease.close()
        }
    }

    // ---- CameraStreamSource ----------------------------------------------------------------------

    override fun acquireStream(request: StreamRequest): StreamAdmission {
        val bound = request.bind(defaultResolution(), defaultFps(), defaultKbps())
        val lease = acquireLease(bound.resolution, LeaseKind.STREAM, bound.binding)
            ?: return StreamAdmission.Refused(lastRefusal())
        val ready = synchronized(lock) {
            val live = current
            val params = live?.streamParams
            if (live?.encoder != null && params != null) CompletableFuture.completedFuture<StreamOutcome>(StreamOutcome.Ready(params)) else streamReady
        }
        return when (val outcome = runCatching { ready.get(ENCODER_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()) {
            is StreamOutcome.Ready -> StreamAdmission.Granted(lease, outcome.params, lease.generation)
            is StreamOutcome.Refused -> {
                lease.close()
                StreamAdmission.Refused(outcome.reason)
            }
            null -> {
                lease.close()
                StreamAdmission.Refused(CameraRefusal.STARVED)
            }
        }
    }

    override fun requestKeyFrame() {
        val enc = synchronized(lock) { current?.encoder } ?: return
        post { enc.requestKeyFrame() }
    }

    // ---- lifecycle used by the service --------------------------------------------------------------

    /**
     * The master switch moved. Off ends a live or opening session at once; on clears the switch's own
     * refusal from the outcome, republishes the prompt and listens.
     */
    fun onEnabledChanged() {
        if (enabled()) {
            // The switch is re-read inside the lock that stores the outcome, so a disable landing
            // between the branch above and the store below cannot leave a stale `ok` behind. The
            // session is asked what it would still refuse — worst first, because one field answers for
            // every consumer and a snapshot blocker outranks a stream-only one.
            synchronized(lock) {
                if (enabled()) {
                    val now = nowMs()
                    val retained = state.retainedRefusal(now, LeaseKind.SNAPSHOT)
                        ?: state.retainedRefusal(now, LeaseKind.STREAM)
                    outcome = CameraOutcome.onEnable(outcome, retained)
                }
            }
            publishPermissionPrompt(freshEnable = true)
            transport.setListening(hasCamera())
            return
        }
        publishPermissionPrompt(freshEnable = false)
        transport.setListening(false)
        val disabled: Boolean
        val ended: Attempt?
        val generation: Long
        synchronized(lock) {
            disabled = state.disable()
            if (disabled) outcome = CameraRefusal.DISABLED.token
            ended = if (disabled) takeCurrentLocked() else null
            generation = state.generation
        }
        // Decide on the ending, not on whether an attempt was current: inside a reopen window there is
        // no attempt, but the session still ended and its waiters, encoder and thread must be settled.
        if (disabled) post { finishAttempt(ended, stopping = false, endedGeneration = generation) }
    }

    /** Refuse new leases; existing ones drain through [stop]. */
    fun closeAdmission() {
        synchronized(lock) { admissionClosed = true }
    }

    /** Close everything now and quit the thread. Returns once the device is released. */
    fun stop(): CompletableFuture<Unit> {
        closeAdmission()
        transport.setListening(false)
        val done = CompletableFuture<Unit>()
        val ended: Attempt?
        val h: Handler?
        val generation: Long
        synchronized(lock) {
            state.stopping()
            outcome = CameraRefusal.STOPPING.token
            ended = takeCurrentLocked()
            h = handler
            generation = state.generation
        }
        if (ended == null || h == null) {
            finishAttempt(ended, stopping = true, endedGeneration = generation)
            done.complete(Unit)
            return done
        }
        val posted = h.post {
            finishAttempt(ended, stopping = true, endedGeneration = generation)
            done.complete(Unit)
        }
        if (!posted) {
            // The looper quit between the read and the post: close inline rather than never.
            finishAttempt(ended, stopping = true, endedGeneration = generation)
            done.complete(Unit)
        }
        return done
    }

    // ---- leases -------------------------------------------------------------------------------------

    /** Blocking: returns a snapshot lease on an open session, or null after recording the classified refusal. */
    fun acquire(requested: CameraResolution?): Lease? = acquireLease(requested, LeaseKind.SNAPSHOT, null)

    private fun acquireLease(requested: CameraResolution?, kind: LeaseKind, binding: StreamBinding?): Lease? {
        val leaseId: Long
        val leaseGeneration: Long
        val pending: CompletableFuture<CameraRefusal?>?
        var startEncoderFor: Long? = null
        // Read outside the lock, like the presentation's: the capability may enumerate Android's
        // cameras. Unlike the switch below it cannot change while the panel runs, so reading it early
        // cannot produce the stale-observation defect the critical section beneath exists to prevent.
        val reason = capabilityReason()
        val present = reason.capable
        synchronized(lock) {
            // The switch and the permission are observed and applied in ONE critical section. Split
            // across two, an enable landing in the gap let a caller that had already read the switch as
            // off store `camera-disabled` over the enable's own reset, putting back the very refusal
            // this class exists to clear — and this time on a camera that was on. Both reads are the
            // same cheap non-blocking reads the presentation already makes under this lock.
            val gate = when {
                !present -> if (reason == CameraCapabilityReason.NOT_ENUMERATED) {
                    CameraRefusal.ABSENT
                } else {
                    CameraRefusal.FAILED
                }
                admissionClosed -> CameraRefusal.STOPPING
                !enabled() -> CameraRefusal.DISABLED
                !permissionGranted() -> CameraRefusal.PERMISSION
                else -> null
            }
            when (val admission = state.acquire(gate, nowMs(), kind, binding)) {
                is Admission.Refused -> {
                    outcome = admission.reason.token
                    if (admission.reason == CameraRefusal.PERMISSION) publishPermissionPrompt(freshEnable = false)
                    return null
                }
                is Admission.Open -> {
                    leaseId = admission.lease
                    leaseGeneration = state.generation
                    beginOpenLocked(admission.attempt, requested, binding?.fps)
                    pending = state.awaitOpen()
                }
                is Admission.Join -> {
                    leaseId = admission.lease
                    leaseGeneration = state.generation
                    // A first stream lease on a live session: the attempt is current by construction
                    // (only openSucceeded reaches LIVE, and every LIVE exit nulls it under this lock), and
                    // streamReady is an unsettled future (every encoder ending replaces a done one).
                    if (admission.startEncoder) startEncoderFor = requireNotNull(state.currentAttempt) { "a live session has a current attempt" }
                    pending = state.awaitOpen()
                }
            }
        }
        startEncoderFor?.let { attemptId -> post { startEncoder(attemptId) } }
        val refusal = pending?.let {
            runCatching { it.get(OPEN_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(CameraRefusal.FAILED)
        }
        if (refusal != null) {
            val release: Release
            val ended: Attempt?
            val generation: Long
            synchronized(lock) {
                release = state.release(leaseId)
                ended = if (release == Release.Close) takeCurrentLocked() else null
                generation = state.generation
            }
            when (release) {
                Release.Close -> post { finishAttempt(ended, stopping = false, endedGeneration = generation) }
                Release.StopEncoder -> post { stopEncoderIfUnwanted() }
                Release.None -> Unit
            }
            return null
        }
        return Lease(leaseId, leaseGeneration)
    }

    // The capability is read before the lock is taken, never under it: the presence probe may enumerate,
    // and `CameraCapabilitySourceContractTest` holds this owner to never enumerating under its own lock.
    private fun lastRefusal(): CameraRefusal {
        val reason = capabilityReason()
        return synchronized(lock) { CameraRefusal.fromToken(outcome, reason) }
    }

    // ---- open ---------------------------------------------------------------------------------------

    /**
     * Under [lock]: the first lease binds the parameters and starts attempt [attemptId] on the owned
     * thread. [bindingFps] is the stream's resolved rate when a stream opened the session, and null when
     * a snapshot did; either way it is the pace the session runs at until it ends, so a later joiner
     * gets the session's rate rather than its own. That is the same rule the encode parameters already
     * follow — the first stream client binds them — and the reason is the same: there is one session.
     */
    private fun beginOpenLocked(attemptId: Long, requested: CameraResolution?, bindingFps: Int?) {
        val h = handler ?: HandlerThread("ha-paneld-camera").let { t ->
            t.start()
            thread = t
            Handler(t.looper).also { handler = it }
        }
        boundTarget = requested ?: defaultResolution()
        boundFps = (bindingFps ?: defaultFps()).coerceIn(1, 30)
        appliedForStream = null
        policy = CameraSessionPolicy(frameIntervalMs = 1_000L / boundFps)
        deliveryPacer = FramePacer(boundFps)
        // The finish of the session that ended may still be pending, and it will not touch a readiness
        // that is no longer its own; so the new session replaces a settled one itself, and its first
        // joiner waits for its encoder's sets rather than being granted the ended encoder's.
        if (streamReady.isDone) streamReady = CompletableFuture()
        h.post { open(attemptId) }
    }

    private fun open(attemptId: Long) {
        val attempt: Attempt
        val target: CameraResolution
        val fps: Int
        synchronized(lock) {
            // A reopen posted before a close, a disable or a stop is a no-op: the attempt is not current.
            if (!state.isCurrent(attemptId) || state.phase != Phase.OPENING) return
            attempt = Attempt(attemptId)
            current = attempt
            target = boundTarget ?: defaultResolution()
            fps = boundFps
        }
        if (!indicator.show()) return openFailed(attempt, CameraFault.INDICATION, CameraRefusal.INDICATION, null)
        if (!foreground.promote(attempt.id, FOREGROUND_WAIT_MS)) {
            return openFailed(attempt, CameraFault.FOREGROUND, CameraRefusal.FOREGROUND, null)
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val h = synchronized(lock) { handler } ?: return openFailed(attempt, CameraFault.NONE, CameraRefusal.STOPPING, null)
        val chosen = runCatching { chooseCamera(manager) }.getOrNull()
            ?: return openFailed(attempt, CameraFault.OPEN, CameraRefusal.FAILED, "no_camera_id")
        // Both caps are ceilings. A board that cannot stay under the resolution cap is refused rather
        // than exceeded; the frame-rate cap is enforced on delivery, so the sensor range only needs to
        // be the lowest one on offer.
        val size = chooseSize(chosen.second, target)
            ?: return openFailed(attempt, CameraFault.CONFIGURE, CameraRefusal.FAILED, "no_size_within_cap")
        val fpsRange = chooseFpsRange(chosen.second, fps)
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        r.setOnImageAvailableListener({ rd -> rd.acquireLatestImage()?.let { onFrame(it, attempt) } }, h)
        synchronized(lock) {
            attempt.reader = r
            attempt.size = size
        }
        try {
            @Suppress("MissingPermission")
            manager.openCamera(chosen.first, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val live = synchronized(lock) {
                        if (state.isCurrent(attempt.id) && state.phase == Phase.OPENING) { attempt.device = camera; true } else false
                    }
                    if (!live) { camera.close(); return }
                    configure(attempt, camera, r, fpsRange, chosen.second)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    deviceFault(attempt, CameraFault.DISCONNECTED, null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    deviceFault(attempt, CameraFault.DEVICE_ERROR, "error_$error")
                }
            }, h)
        } catch (e: SecurityException) {
            // Android 11 grants the foreground type silently and refuses here instead.
            val permitted = permissionGranted()
            openFailed(
                attempt,
                if (permitted) CameraFault.FOREGROUND else CameraFault.PERMISSION,
                if (permitted) CameraRefusal.FOREGROUND else CameraRefusal.PERMISSION,
                e.javaClass.simpleName,
            )
        } catch (e: CameraAccessException) {
            openFailed(attempt, CameraFault.OPEN, CameraRefusal.FAILED, "cae_${e.reason}")
        } catch (e: RuntimeException) {
            openFailed(attempt, CameraFault.OPEN, CameraRefusal.FAILED, e.javaClass.simpleName)
        }
    }

    private fun configure(
        attempt: Attempt,
        camera: CameraDevice,
        r: ImageReader,
        fpsRange: Range<Int>?,
        characteristics: CameraCharacteristics?,
    ) {
        val h = synchronized(lock) { handler } ?: return
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    val live = synchronized(lock) {
                        if (state.isCurrent(attempt.id) && state.phase == Phase.OPENING) {
                            attempt.session = s
                            attempt.characteristics = characteristics
                            attempt.fpsRange = fpsRange
                            true
                        } else false
                    }
                    if (!live) { s.close(); return }
                    val forStream = synchronized(lock) {
                        appliedForStream = state.encoderWanted
                        state.encoderWanted
                    }
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(r.surface)
                            fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                            applyProcessing(this, characteristics, forStream)
                        }.build()
                        // A real callback, where there used to be null. Without it the session had no
                        // way to know whether exposure had converged, so a snapshot could only answer
                        // with the first frame that arrived — which off a cold sensor is taken before
                        // auto-exposure has done anything, and is what made stills far darker than the
                        // stream. Only the transition to settled is recorded; nothing here un-settles a
                        // session, because a converged sensor that briefly re-hunts should not send an
                        // already-waiting snapshot back to the start.
                        s.setRepeatingRequest(request, exposureWatcher(attempt), h)
                    } catch (e: Exception) {
                        openFailed(attempt, CameraFault.CONFIGURE, CameraRefusal.FAILED, e.javaClass.simpleName)
                        return
                    }
                    val became: Boolean
                    val encoderWanted: Boolean
                    synchronized(lock) {
                        became = state.openSucceeded(attempt.id)
                        if (became) { outcome = "ok"; fault = CameraFault.NONE; faultDetail = null; recovery = "none" }
                        encoderWanted = became && state.encoderWanted
                    }
                    if (became) {
                        scheduleTick(attempt)
                        // A stream lease taken before or during the open starts its encoder with the session.
                        if (encoderWanted) startEncoder(attempt.id)
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    openFailed(attempt, CameraFault.CONFIGURE, CameraRefusal.FAILED, "configure_failed")
                }
            }, h)
        } catch (e: Exception) {
            openFailed(attempt, CameraFault.CONFIGURE, CameraRefusal.FAILED, e.javaClass.simpleName)
        }
    }

    /**
     * Attempt [attempt] failed. Its own hardware is always released; the state machine is consulted only
     * if the attempt is still current, so a superseded attempt's late failure can never act on a newer one.
     */
    private fun openFailed(attempt: Attempt, f: CameraFault, refusal: CameraRefusal, detail: String?) {
        attempt.release()
        val decision: Failure
        val generation: Long
        synchronized(lock) {
            decision = state.openFailed(attempt.id, f, refusal, nowMs())
            if (decision != Failure.Ignored) {
                outcome = refusal.token
                fault = f
                faultDetail = HaTransportFault.sanitize(detail)
                if (current === attempt) current = null
            }
            generation = state.generation
        }
        when (decision) {
            Failure.Ignored -> Unit
            Failure.Close -> {
                attempt.releaseStanding()
                indicator.hide()
                synchronized(lock) {
                    recovery = if (state.phase == Phase.DEGRADED) "reattach a client after the hold"
                    else "next open retries after ${state.retryNotBeforeMs - nowMs()}ms"
                }
                quitThread()
                endStream(refusal, generation)
            }
            is Failure.Reopen -> {
                attempt.releaseStanding()
                indicator.hide()
                synchronized(lock) { recovery = "reopening in ${decision.afterMs}ms (attempt ${decision.attempt})" }
                scheduleReopen(decision.afterMs)
            }
            is Failure.Degrade -> degrade(attempt, f, decision.attempt)
        }
    }

    /** Fire a reopen decided earlier: a fresh attempt identity with a fresh first-frame grace. */
    private fun scheduleReopen(afterMs: Long) {
        val h = synchronized(lock) { handler } ?: return
        h.postDelayed({
            val next = synchronized(lock) { state.reopenAttempt(nowMs()) } ?: return@postDelayed
            open(next)
        }, afterMs)
    }

    // ---- encoder ------------------------------------------------------------------------------------

    /**
     * The listener for [attempt]'s encoder. Every callback checks that the attempt is still current: a
     * superseded attempt's codec keeps running until its teardown closes it, and its late parameter
     * sets, access units or failure belong to nobody — they must not complete the newer session's
     * readiness, reach its clients, or end its stream.
     *
     * A check is only worth its effect if nothing can replace the attempt between the two. Where the
     * effect cannot call back into the owner it happens inside the check's critical section; where it
     * can — the transport dropping a client closes that client's lease — it happens outside, and the
     * transport is told whose effect it is and applies it to nothing else.
     */
    private fun encoderListener(attempt: Attempt) = object : VideoEncoder.Listener {
        override fun onParameterSets(sets: ParameterSets) {
            // One critical section for the check and both effects. Split, a replacement session admitted
            // between them kept this still-pending readiness as its own, and its first joiner was granted
            // this superseded codec's sets. Neither call can re-enter the owner: the transport only records
            // the sets, by its contract, and the readiness has blocking waiters, never dependent stages.
            synchronized(lock) {
                if (!state.isCurrent(attempt.id)) return
                val enc = attempt.encoder ?: return
                val facts = enc.facts
                val params = StreamParams(facts.width, facts.height, facts.fps, facts.kbps, facts.name, sets)
                attempt.streamParams = params
                advertisedBy = attempt.id
                // The transport learns the new sets BEFORE any waiter is woken, so a DESCRIBE that wakes on
                // this encoder can never be answered with the previous encoder's retained SPS/PPS.
                transport.onParameterSets(sets, attempt.id)
                streamReady.complete(StreamOutcome.Ready(params))
            }
        }

        override fun onAccessUnit(nals: List<ByteArray>, keyFrame: Boolean, ptsUs: Long, bytes: Int) {
            if (synchronized(lock) { !state.isCurrent(attempt.id) }) return
            stats.onFrame(nowMs(), bytes)
            // Outside the lock: shedding a slow client closes its lease. A newer attempt may publish
            // after the check above, so the unit names its attempt and the transport delivers it only
            // while that attempt's sets are the advertised ones.
            transport.onAccessUnit(nals, keyFrame, ptsUs, attempt.id)
        }

        override fun onEncoderError(detail: String) {
            // The encoder failed, not the camera: stop the encoder alone, hold stream leases off for
            // the policy's backoff so a reconnecting client cannot set the retry rate, and drop the
            // stream clients so they reconnect after it. Snapshot subscribers never notice.
            if (synchronized(lock) { attempt.encoder == null }) return
            // The codec is this attempt's own and comes down whoever is live now, so a superseded
            // attempt's codec failing late touches nothing else.
            attempt.closeEncoder()
            // Whether the session is still this attempt's is decided in the critical section that records
            // the fault and detaches the waiters, and the clients dropped are the generation read there:
            // a session that replaced this one meanwhile keeps its waiters, its clients and its outcome.
            val settled: CompletableFuture<StreamOutcome>
            val generation: Long
            synchronized(lock) {
                if (!state.isCurrent(attempt.id)) return
                fault = CameraFault.STREAM_ENCODER
                faultDetail = HaTransportFault.sanitize(detail)
                outcome = CameraRefusal.STREAM_ENCODER.token
                state.encoderFailed(nowMs())
                settled = detachStreamReadyLocked()
                generation = state.generation
            }
            Log.w(TAG, "encoder failed while streaming ($detail); stopping the stream, snapshots unaffected")
            stats.reset()
            settled.complete(StreamOutcome.Refused(CameraRefusal.STREAM_ENCODER))
            transport.onStreamEnded(generation)
        }
    }

    /**
     * On the camera thread: open the encoder for the live attempt if a stream lease still wants one.
     * The guard is the attempt's own encoder, never a session-wide one: an ended attempt's codec that
     * is still awaiting its teardown must not stop a newer attempt from starting its own.
     */
    private fun startEncoder(attemptId: Long) {
        val h: Handler
        val attempt: Attempt
        val size: Size?
        val binding: StreamBinding?
        synchronized(lock) {
            if (!state.isCurrent(attemptId) || state.phase != Phase.LIVE || !state.encoderWanted) return
            attempt = current?.takeIf { it.id == attemptId } ?: return
            if (attempt.encoder != null) return
            h = handler ?: return
            size = attempt.size
            binding = state.streamBinding
        }
        if (size == null || binding == null) return refuseEncoder(attemptId, "no_capture_size")
        // Never faster than the feed: the session's pace is fixed when it opens, so an encoder started
        // by a stream that joined an already-open session must not be configured above what it will be
        // fed. This is a physical bound, not a policy one — the configured values are defaults now.
        val fps = minOf(binding.fps, boundFps)
        when (val opened = encoderFactory.open(size.width, size.height, fps, binding.kbps, h, encoderListener(attempt))) {
            is EncoderOpen.Refused -> refuseEncoder(attemptId, opened.detail)
            is EncoderOpen.Ready -> {
                // Re-checked where the codec is installed: the open ran outside the lock, and a codec
                // installed into an attempt whose teardown has already run would never be closed.
                val installed = synchronized(lock) {
                    if (!state.isCurrent(attemptId)) return@synchronized false
                    attempt.encoder = opened.encoder
                    attempt.encoderPacer = if (fps < boundFps) FramePacer(fps) else null
                    attempt.streamParams = null
                    stats.reset()
                    true
                }
                if (!installed) {
                    runCatching { opened.encoder.close() }
                    return
                }
                // A stream is now being served: drop to the cheap pipeline for every frame.
                refreshProcessing(attemptId)
            }
        }
    }

    /**
     * No encoder for this session's stream clients — including the ones a reopen carried over, who would
     * otherwise hold the camera open behind a lit light with nothing ever restarting the encoder.
     * They are dropped like any other stream ending; the hold keeps their reconnects off the codec.
     */
    private fun refuseEncoder(attemptId: Long, detail: String) {
        val settled: CompletableFuture<StreamOutcome>
        val generation: Long
        synchronized(lock) {
            // Decided with its effects, not before the open: the open ran outside the lock, and a
            // session or attempt that replaced this one meanwhile starts its own encoder for its own
            // clients, which this refusal must neither hold off nor drop.
            if (!state.isCurrent(attemptId)) return
            fault = CameraFault.STREAM_ENCODER
            faultDetail = HaTransportFault.sanitize(detail)
            outcome = CameraRefusal.STREAM_ENCODER.token
            state.encoderFailed(nowMs())
            settled = detachStreamReadyLocked()
            generation = state.generation
        }
        settled.complete(StreamOutcome.Refused(CameraRefusal.STREAM_ENCODER))
        transport.onStreamEnded(generation)
        Log.w(TAG, "stream refused: no usable encoder ($detail); snapshots are unaffected")
    }

    /**
     * The last stream lease left; unless another arrived in the meantime, the encoder goes with it. The
     * check and the swap of the waiters' future are one critical section, so a stream lease acquired
     * between them cannot be handed a future that is then refused under it.
     */
    private fun stopEncoderIfUnwanted() {
        val settled: CompletableFuture<StreamOutcome>
        val live: Attempt?
        synchronized(lock) {
            if (state.encoderWanted) return
            settled = streamReady
            streamReady = CompletableFuture()
            live = current
        }
        stopEncoder(live, ownsSession = true)
        // Nobody is streaming any more, so a snapshot may have the expensive pipeline again.
        synchronized(lock) { state.currentAttempt }?.let { refreshProcessing(it) }
        settled.complete(StreamOutcome.Refused(CameraRefusal.FAILED))
    }

    /**
     * [attempt]'s encoder comes down. Two owners, two halves: the codec, its pacer, its parameter sets
     * and the advertisement it published are the attempt's, closed unconditionally by
     * [Attempt.closeEncoder]; the session's stream readiness and its measurement are reset only while
     * the session this encoder served is still the live one — [ownsSession] is [CameraTeardown]'s
     * decision, and a finish that lost it leaves the newer session's readiness alone.
     *
     * The readiness is reset, not refused: across a bounded reopen the pending future is completed by
     * the restarted encoder, so a joiner rides the reopen out instead of being refused a session that
     * is about to come back. A future that was already settled by the stopped encoder is replaced, so
     * a joiner during the reopen waits for the new parameter sets rather than being granted the old
     * encoder's. Terminal paths settle explicitly.
     */
    private fun stopEncoder(attempt: Attempt?, ownsSession: Boolean) {
        attempt?.closeEncoder()
        if (!ownsSession) return
        synchronized(lock) { if (streamReady.isDone) streamReady = CompletableFuture() }
        stats.reset()
    }

    /** Under [lock]: hand back the readiness its waiters hold, and give the next encoder a fresh one. */
    private fun detachStreamReadyLocked(): CompletableFuture<StreamOutcome> =
        streamReady.also { streamReady = CompletableFuture() }

    /**
     * The stream side of the session that ended as [endedGeneration]. The ending is posted, and a new
     * session may have started — with its own stream clients and waiters — before it runs. Those belong
     * to the new session and must not be dropped by the old one.
     *
     * The waiters are the live session's, so they are settled only while the ended session is still the
     * live one, decided in the critical section that detaches them: split, a replacement admitted between
     * the check and the swap had its own waiters refused. The clients are dropped outside the lock,
     * because dropping one closes its lease and re-enters the owner, so the guard travels with the call:
     * the transport drops only clients granted at [endedGeneration] or earlier. A replacement's clients
     * are granted later and stay; the ended session's are dropped even when a replacement has started.
     */
    private fun endStream(refusal: CameraRefusal, endedGeneration: Long) {
        // The same rule the rest of the teardown follows: the waiters belong to the live session.
        val settled = synchronized(lock) {
            val owns = CameraTeardown.ownsSessionGlobals(
                stopping = false,
                endedGeneration = endedGeneration,
                currentGeneration = state.generation,
            )
            if (owns) detachStreamReadyLocked() else null
        }
        settled?.complete(StreamOutcome.Refused(refusal))
        transport.onStreamEnded(endedGeneration)
    }

    // ---- frames -------------------------------------------------------------------------------------

    private fun onFrame(image: Image, attempt: Attempt) {
        image.use { img ->
            val now = nowMs()
            val ready: List<SnapshotWaiter>
            val enc: VideoEncoder?
            synchronized(lock) {
                ready = state.frame(attempt.id, now, attempt.exposureSettled) ?: return
                // The frame-rate cap bounds delivery: a frame arriving sooner than the cap allows is
                // observed for liveness but not handed on to anyone. The encoder has its own pacer only
                // when its stream runs slower than the session; at the session's rate a second pacer
                // with its own origin would drop frames the first one had already spaced correctly.
                if (!deliveryPacer.admit(now)) {
                    ready.forEach { state.requeue(it) }
                    return
                }
                enc = attempt.encoder?.takeIf { attempt.encoderPacer?.admit(now) ?: true }
            }
            enc?.feed(img, now * 1_000L)
            if (ready.isEmpty()) return
            val jpeg = toJpegOrNull(img)
            synchronized(lock) {
                if (jpeg == null) {
                    fault = CameraFault.ENCODE
                    faultDetail = null
                    outcome = CameraRefusal.ENCODE.token
                } else if (fault == CameraFault.ENCODE) {
                    fault = CameraFault.NONE
                    outcome = "ok"
                }
            }
            ready.forEach { it.future.complete(jpeg) }
        }
    }

    /**
     * Records converged auto-exposure for [attempt]. `CONVERGED`, `LOCKED` and `FLASH_REQUIRED` all mean
     * the sensor has finished deciding; a device that reports no AE state at all simply never settles
     * here and the snapshot falls back on [SnapshotExposure.SETTLE_BUDGET_MS], which is why that budget
     * exists rather than a precondition.
     */
    private fun exposureWatcher(attempt: Attempt) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (attempt.exposureSettled) return
            val ae = result.get(CaptureResult.CONTROL_AE_STATE) ?: return
            if (ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                ae == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
            ) {
                attempt.exposureSettled = true
            }
        }
    }

    /**
     * Rebuild the repeating request when stream demand changes, so the processing budget follows what the
     * session is actually serving rather than what opened it.
     *
     * Called on both transitions the session already has — an encoder starting and an encoder stopping —
     * and it does nothing when the demand is unchanged, so an ordinary snapshot on a quiet session costs
     * one comparison. Changing noise reduction and edge enhancement are request keys, not session keys,
     * so this re-issues a request rather than reconfiguring the capture session, and the attempt's
     * settled-exposure flag is deliberately left alone: a viewer arriving must not send an already
     * waiting snapshot back to the start.
     */
    private fun refreshProcessing(attemptId: Long) {
        val h = synchronized(lock) { handler } ?: return
        h.post {
            val attempt: Attempt
            val session: CameraCaptureSession
            val reader: ImageReader
            val forStream: Boolean
            synchronized(lock) {
                if (!state.isCurrent(attemptId) || state.phase != Phase.LIVE) return@post
                val a = current?.takeIf { it.id == attemptId } ?: return@post
                session = a.session ?: return@post
                reader = a.reader ?: return@post
                forStream = state.encoderWanted
                if (appliedForStream == forStream) return@post
                appliedForStream = forStream
                attempt = a
            }
            runCatching {
                val device = synchronized(lock) { attempt.device } ?: return@runCatching
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(reader.surface)
                    attempt.fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    applyProcessing(this, attempt.characteristics, forStream)
                }.build()
                session.setRepeatingRequest(request, exposureWatcher(attempt), h)
            }.onFailure {
                Log.w(TAG, "processing refresh failed: ${it.javaClass.simpleName}")
                synchronized(lock) { appliedForStream = null }
            }
        }
    }

    /**
     * The processing these sensors actually offer, applied at session configure.
     *
     * Every value is taken from what the device advertises and skipped when it advertises none, so a
     * board that supports none of this is left exactly as it was. Nothing here is HDR: these cameras
     * report `BACKWARD_COMPATIBLE` only, so bracketed capture and per-frame exposure — what HDR is made
     * of — do not exist to build on. These are the levers that do.
     */
    private fun applyProcessing(builder: CaptureRequest.Builder, c: CameraCharacteristics?, forStream: Boolean) {
        if (c == null) return
        CameraProcessing.qualityMode(
            forStream = forStream,
            available = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
            fast = CaptureRequest.NOISE_REDUCTION_MODE_FAST,
            highQuality = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        )?.let { builder.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }

        CameraProcessing.qualityMode(
            forStream = forStream,
            available = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
            fast = CaptureRequest.EDGE_MODE_FAST,
            highQuality = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
        )?.let { builder.set(CaptureRequest.EDGE_MODE, it) }

        val range = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        if (range != null && step != null) {
            val steps = CameraProcessing.exposureSteps(
                requestedEv = exposureEv().toDouble(),
                lower = range.lower,
                upper = range.upper,
                stepNumerator = step.numerator,
                stepDenominator = step.denominator,
            )
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
        }
    }

    // ---- watchdog -----------------------------------------------------------------------------------

    private fun scheduleTick(attempt: Attempt) {
        val h = synchronized(lock) { handler } ?: return
        h.postDelayed({ tick(attempt) }, policy.starvationMs / 2)
    }

    /** What the tick decided, applied to hardware outside the lock once the state machine has moved. */
    private sealed interface TickAction {
        data object Continue : TickAction
        data class Finish(val ended: Attempt?, val stopping: Boolean, val generation: Long) : TickAction
        data class Reopen(val afterMs: Long) : TickAction
        data class Degrade(val fault: CameraFault, val attempt: Int, val generation: Long) : TickAction
    }

    private fun tick(attempt: Attempt) {
        synchronized(lock) { if (!state.isCurrent(attempt.id) || state.phase != Phase.LIVE) return }
        // The light is a continuing prerequisite: a session the room is not being told about closes.
        // Blocking LED work, so it runs outside the lock; the decision below re-checks the attempt.
        val indicated = indicator.refresh()
        // Decide AND move the state machine in one critical section, so a disable, a last-lease release
        // or a stop that lands after the decision cannot be overtaken by it: every transition below is
        // guarded on the attempt still being current, and a refused transition is a no-op here.
        val action: TickAction = synchronized(lock) {
            val decision = state.tick(attempt.id, nowMs(), enabled(), indicated) ?: return
            when (decision) {
                CameraSessionPolicy.Decision.Continue -> TickAction.Continue
                is CameraSessionPolicy.Decision.Close -> {
                    // Every close goes through a real state-machine ending, never a sentinel. IDLE here
                    // means the policy saw no clients, which cannot happen on a live session because the
                    // last lease ends it synchronously; endNow() is the honest no-op if it ever does.
                    outcome = when (decision.reason) {
                        CameraSessionPolicy.CloseReason.DISABLED -> CameraRefusal.DISABLED.token
                        CameraSessionPolicy.CloseReason.STOPPING -> CameraRefusal.STOPPING.token
                        CameraSessionPolicy.CloseReason.IDLE -> "ok"
                    }
                    val stopping = decision.reason == CameraSessionPolicy.CloseReason.STOPPING
                    if (stopping) state.stopping() else state.endNow()
                    TickAction.Finish(takeCurrentLocked(), stopping, state.generation)
                }
                is CameraSessionPolicy.Decision.Reopen -> {
                    if (!state.reopening(attempt.id, decision.attempt)) return
                    fault = decision.fault
                    outcome = if (decision.fault == CameraFault.INDICATION) CameraRefusal.INDICATION.token else CameraRefusal.FAILED.token
                    recovery = "reopening in ${decision.afterMs}ms (attempt ${decision.attempt})"
                    if (current === attempt) current = null
                    TickAction.Reopen(decision.afterMs)
                }
                is CameraSessionPolicy.Decision.Degrade -> {
                    if (!state.degraded(attempt.id, decision.attempt, nowMs())) return
                    fault = decision.fault
                    outcome = CameraRefusal.FAILED.token
                    recovery = "reattach a client after the hold"
                    current = null
                    TickAction.Degrade(decision.fault, decision.attempt, state.generation)
                }
            }
        }
        when (action) {
            TickAction.Continue -> scheduleTick(attempt)
            is TickAction.Finish -> finishAttempt(action.ended ?: attempt, stopping = action.stopping, endedGeneration = action.generation)
            is TickAction.Reopen -> {
                // The encoder comes down with the capture beneath it and restarts with the reopened
                // session; stream clients keep their place and joiners ride the reopen out.
                stopEncoder(attempt, ownsSession = true)
                attempt.release()
                attempt.releaseStanding()
                indicator.hide()
                scheduleReopen(action.afterMs)
            }
            is TickAction.Degrade -> {
                stopEncoder(attempt, ownsSession = true)
                attempt.release()
                attempt.releaseStanding()
                indicator.hide()
                quitThread()
                endStream(CameraRefusal.FAILED, action.generation)
                Log.w(TAG, "camera session degraded after ${action.attempt} failures: ${action.fault.wire}")
            }
        }
    }

    private fun deviceFault(attempt: Attempt, f: CameraFault, detail: String?) {
        val opening: Boolean
        synchronized(lock) {
            if (!state.noteDeviceFault(attempt.id, f)) {
                // A superseded attempt reporting late: only its own hardware is touched.
                opening = false
            } else {
                fault = f
                faultDetail = HaTransportFault.sanitize(detail)
                opening = state.phase == Phase.OPENING
            }
        }
        if (opening) openFailed(attempt, f, CameraRefusal.FAILED, detail) else if (!synchronized(lock) { state.isCurrent(attempt.id) }) attempt.release()
        // A live attempt's fault is picked up by the next tick, which decides the ladder.
    }

    private fun degrade(attempt: Attempt, f: CameraFault, count: Int) {
        // openFailed() has already moved the state machine to DEGRADED for this attempt; this only
        // brings the hardware side down and records the presentation.
        attempt.releaseStanding()
        indicator.hide()
        val generation: Long
        synchronized(lock) {
            fault = f
            outcome = CameraRefusal.FAILED.token
            recovery = "reattach a client after the hold"
            if (current === attempt) current = null
            generation = state.generation
        }
        quitThread()
        endStream(CameraRefusal.FAILED, generation)
        Log.w(TAG, "camera session degraded after $count failures: ${f.wire}")
    }

    // ---- close --------------------------------------------------------------------------------------

    /** Under [lock]: detach the current attempt so its hardware can come down outside the lock. */
    private fun takeCurrentLocked(): Attempt? = current.also { current = null }

    /**
     * Bring the session down: the state machine has already ended it, as generation [endedGeneration].
     * The attempt's encoder stops first so nothing is produced while the capture beneath it is torn
     * down; then the attempt's hardware, if any; then — only if no newer session has started in the
     * meantime — every stream client is dropped so it reconnects and pays the open cost.
     */
    private fun finishAttempt(attempt: Attempt?, stopping: Boolean, endedGeneration: Long) {
        // This finish was posted, so the next session may already have been admitted and opened
        // before it ran. Whether the session-global effects are still this finish's to perform is
        // [CameraTeardown]'s rule; the attempt's own codec, hardware and standing are always its own.
        val ownsGlobals = CameraTeardown.ownsSessionGlobals(
            stopping = stopping,
            endedGeneration = endedGeneration,
            currentGeneration = synchronized(lock) { state.generation },
        )
        stopEncoder(attempt, ownsSession = ownsGlobals)
        attempt?.release()
        // Stopping outright takes standing whoever holds it, because nothing newer can be coming; a
        // session merely ending releases only its own, because a newer session may already hold it.
        if (stopping) foreground.demoteAll() else attempt?.releaseStanding()
        // The light goes out only for the session that still owns it. Putting it out while a newer
        // session has the camera open would leave the room uninformed with the camera running, which
        // is the one thing the indicator exists to prevent.
        if (ownsGlobals) {
            if (stopping) indicator.forceHide() else indicator.hide()
        }
        quitThread()
        endStream(if (stopping) CameraRefusal.STOPPING else lastRefusal(), endedGeneration)
    }

    private fun quitThread() {
        val t: HandlerThread?
        synchronized(lock) {
            // Keep the thread while a reopen or another attempt still needs it.
            if (state.phase == Phase.OPENING || state.phase == Phase.LIVE) return
            t = thread; thread = null; handler = null
        }
        t?.quitSafely()
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private fun post(action: () -> Unit) {
        val h = synchronized(lock) { handler }
        // A looper that quit between the read and the post refuses the post; run inline rather than drop it.
        if (h == null || !h.post(action)) action()
    }

    private fun publishPermissionPrompt(freshEnable: Boolean) {
        CameraPermissionPrompt.publish(
            wantsPermission = enabled() && hasCamera() && !permissionGranted(),
            freshEnable = freshEnable && hasCamera(),
        )
    }

    private fun current(facts: StreamTransportFacts, address: String?): CameraPresentation {
        val now = nowMs()
        val last = state.lastFrameAtMs
        val phase = state.phase
        val clients = state.clients
        val streaming = state.streamClients
        val failures = state.consecutiveFailures
        val encoderFacts = current?.encoder?.facts
        val presented = when (phase) {
            Phase.IDLE -> CameraState.IDLE
            Phase.OPENING -> CameraState.OPENING
            Phase.LIVE -> CameraState.LIVE
            Phase.DEGRADED -> CameraState.DEGRADED
            Phase.STOPPING -> CameraState.STOPPING
        }
        // The warning travels with the URL: the place a person copies it from is the place they are
        // about to paste it into a card on this very panel, which is the loop the panel cannot afford.
        val stream = when {
            facts.port == null -> "stream not listening"
            address != null -> "stream at rtsp://$address:${facts.port}$STREAM_PATH (not for this panel's own dashboard)"
            else -> "stream listening on port ${facts.port}"
        }
        return CameraPresentation(
            state = presented, outcome = outcome, fault = fault, faultDetail = faultDetail, recovery = recovery,
            clients = clients, lastFrameAgeMs = last?.let { (now - it).coerceAtLeast(0) },
            consecutiveFailures = failures, indication = indicator.route(),
            // Carried on every live projection, including an idle one: the question "is this panel's
            // camera taking the exposure fallback?" is asked precisely when nobody is watching.
            snapshotExposure = state.lastSnapshotExposure,
            snapshotExposureFallbacks = state.snapshotExposureFallbacks,
            summary = when (phase) {
                Phase.LIVE -> "camera open for $clients client${if (clients == 1) "" else "s"}" +
                    (if (streaming > 0) " ($streaming streaming)" else "") + "; $stream"
                Phase.OPENING -> "camera opening; $stream"
                Phase.DEGRADED -> "camera gave up after $failures failures (${fault.wire}); $stream"
                Phase.STOPPING -> "camera stopping"
                Phase.IDLE -> "camera closed; nobody is watching; $stream"
            },
            action = when {
                // Ahead of the degraded branch on purpose: when the profile declares a camera the board
                // does not expose, every open fails here and "check the camera hardware" sends somebody
                // to look for a fault in a board that is behaving correctly. The profile is the fault.
                faultDetail == "no_camera_id" ->
                    "the profile declares a camera this board does not expose; correct hardware.camera in the profile"
                // The switch is deliberately not offered as a remedy: endSession clears neither the retry
                // backoff nor the degraded phase, so toggling it does not retry, and the text used to say
                // it did (privacy contract §6).
                phase == Phase.DEGRADED -> "check the camera hardware; a new client after the hold retries"
                fault == CameraFault.FOREGROUND -> "wake the panel: a camera session can only start while the dashboard is visible"
                fault == CameraFault.INDICATION -> "the camera-in-use light could not be shown; check overlay permission and the LED"
                fault == CameraFault.ENCODE -> "the camera delivers frames but they could not be encoded; report this with the panel's diagnostics"
                fault == CameraFault.STREAM_ENCODER -> "no hardware H.264 encoder fits the camera bitrate cap; snapshots still work, streaming does not"
                else -> "none"
            },
            streamClients = streaming,
            streamPort = facts.port,
            // What the stream asked for, which is not always what the encoder was given. The capture
            // rate is fixed by whoever OPENS the session — a snapshot opens it at the configured
            // default — and a stream arriving afterwards joins that session rather than reconfiguring
            // it, so `startEncoder` binds `minOf(binding.fps, boundFps)`. Reporting only the bound
            // rate answers "am I getting what I asked for?" with the wrong number, in the one case
            // where the answer is no. Null while no stream lease is held, and, by the same design that
            // discards a later joiner's binding, this is the FIRST current stream lease's request.
            requestedFps = state.streamBinding?.fps,
            encoder = encoderFacts?.name,
            encodeWidth = encoderFacts?.width,
            encodeHeight = encoderFacts?.height,
            encodeFps = encoderFacts?.fps,
            encodeKbps = encoderFacts?.kbps,
            deliveredFps = if (encoderFacts != null) stats.fps(now) else null,
            deliveredKbps = if (encoderFacts != null) stats.kbps(now) else null,
        )
    }

    private fun chooseCamera(manager: CameraManager): Pair<String, CameraCharacteristics>? {
        val ids = manager.cameraIdList.takeIf { it.isNotEmpty() } ?: return null
        val described = ids.map { it to manager.getCameraCharacteristics(it) }
        // Prefer a front-facing lens: the firmware's own facing flag has been observed to lie, so
        // this is a preference among the cameras present, never a filter.
        return described.firstOrNull { it.second.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
            ?: described.first()
    }

    /** The largest YUV size within the cap, preferring the cap's aspect; null when nothing fits. */
    private fun chooseSize(characteristics: CameraCharacteristics, target: CameraResolution): Size? {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val targetAspect = target.width.toDouble() / target.height
        return sizes
            .filter { it.width <= target.width && it.height <= target.height }
            .maxWithOrNull(
                compareBy<Size> { kotlin.math.abs(it.width.toDouble() / it.height - targetAspect) < 0.2 }
                    .thenBy { it.width.toLong() * it.height },
            )
    }

    /** The lowest sensor rate on offer at or above the cap; delivery is paced to the cap regardless. */
    private fun chooseFpsRange(characteristics: CameraCharacteristics, maxFps: Int): Range<Int>? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        if (ranges.isEmpty()) return null
        return ranges.filter { it.upper <= maxFps }.maxByOrNull { it.upper }
            ?: ranges.minWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
    }

    /** JPEG bytes, or null when the compressor refused or threw; the caller classifies that as an encode fault. */
    private fun toJpegOrNull(image: Image): ByteArray? = runCatching {
        val nv21 = yuv420ToNv21(image)
        val out = ByteArrayOutputStream()
        val ok = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            .compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
        if (ok && out.size() > 0) out.toByteArray() else null
    }.onFailure { Log.w(TAG, "JPEG encode failed: ${it.javaClass.simpleName}") }.getOrNull()

    companion object {
        private const val TAG = "ha-paneld/camera"
        private const val OPEN_WAIT_MS = 8_000L
        private const val FOREGROUND_WAIT_MS = 3_000L
        private const val SNAPSHOT_WAIT_MS = 5_000L
        /** Codec start plus its first config buffer is well under a second; a bound, not a budget. */
        private const val ENCODER_WAIT_MS = 6_000L
        private const val JPEG_QUALITY = 85
        const val STREAM_PATH = RtspSession.MOUNT_PATH

        /** Pack a YUV_420_888 image as NV21 honouring row and pixel strides. */
        internal fun yuv420ToNv21(image: Image): ByteArray {
            val width = image.width
            val height = image.height
            val ySize = width * height
            val out = ByteArray(ySize + ySize / 2)
            val planes = image.planes
            val y = planes[0]
            val yBuffer = y.buffer
            val yRowStride = y.rowStride
            var pos = 0
            if (yRowStride == width && y.pixelStride == 1) {
                yBuffer.get(out, 0, ySize)
                pos = ySize
            } else {
                val row = ByteArray(yRowStride)
                for (r in 0 until height) {
                    yBuffer.position(r * yRowStride)
                    yBuffer.get(row, 0, minOf(yRowStride, yBuffer.remaining()))
                    for (c in 0 until width) out[pos++] = row[c * y.pixelStride]
                }
            }
            val u = planes[1]
            val v = planes[2]
            val uBuffer = u.buffer
            val vBuffer = v.buffer
            val chromaHeight = height / 2
            val chromaWidth = width / 2
            for (r in 0 until chromaHeight) {
                for (c in 0 until chromaWidth) {
                    val vi = r * v.rowStride + c * v.pixelStride
                    val ui = r * u.rowStride + c * u.pixelStride
                    out[pos++] = vBuffer.get(vi)
                    out[pos++] = uBuffer.get(ui)
                }
            }
            return out
        }
    }
}
