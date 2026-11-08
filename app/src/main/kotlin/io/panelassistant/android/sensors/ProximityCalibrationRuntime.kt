package io.panelassistant.android.sensors

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.panelassistant.android.PresenceMode
import io.panelassistant.android.dashboard.EntityCatalogStore
import io.panelassistant.android.device.profile.ProfileProximityCalibration
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.json.JSONObject

/** Learned ranged presence, explicit local setup, and one atomic durable calibration save. */
internal class ProximityCalibrationRuntime(
    sourceIdentity: String,
    profileId: String,
    profileBaseline: ProximityCalibrationEngine.Calibration?,
    private val store: ProximityModelStore,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val wall: () -> Long = System::currentTimeMillis,
    requestedWavePattern: ProximityCalibrationEngine.WavePattern = ProximityCalibrationEngine.WavePattern.SINGLE,
    private val observedSourceMode: ProximityCalibrationEngine.Mode? = null,
    profileRevision: String? = null,
    private val driftMargin: Float = 0f,
    /** The source's declared maximum range; readings beyond it are counts, the band's unit. 0 = always counts. */
    private val driftDeclaredRange: Float = 0f,
    /** Keep a stored calibration inside the band as saved, rather than decline it (`proximityKeepsStoredInsideDriftBand`). */
    private val keepStoredInsideDriftBand: Boolean = false,
    /** Where presence comes from; decides whether touch-learned or wizard anchors run. */
    private val presenceMode: () -> PresenceMode = { PresenceMode.MANUAL },
    /** Durable touch-learning state, kept apart from the calibration table so it can never evict a saved model. */
    private val touchStore: ProximityTouchStore = ProximityTouchStore.NONE,
    /** Runs after setup saves a calibration; Automatic hands over to Hybrid so the capture is used. */
    private val onExplicitSave: () -> Unit = {},
) : AutoCloseable {
    data class Decision(val reportMask: Int, val near: Boolean?, val normalizedLevel: Int?, val deliberateGesture: Boolean, val presenceApproach: Boolean = false)

    // Keyed by what the readings mean: the profile and the route, sensor and firmware in sourceIdentity.
    // Not the profile's revision: an edit to its brightness or buttons, or a release that revises the
    // bundled file, leaves every reading as it was and must not hide the saved calibration.
    private val fingerprint = calibrationKey(profileId, sourceIdentity)
    // The key before that change, which also hashed the revision. Read once, when the new key misses.
    private val legacyFingerprint = profileRevision?.let { fingerprint("$profileId|$it|$sourceIdentity") }
    private val reportGate = ProximityReportGate(densePresenceStabilityMs = 0)
    private var userOverride = false
    private var readFailed = false

    /** A wave-only calibration whose boundaries cannot hold the source's existing wave drift margin. */
    private var driftUnsafe = false

    /** Profile and stored calibrations use the same presence and wave admission policy. */
    private val baseline = profileBaseline?.let { profile ->
        admitStored(profile).also {
            if (it == null) Log.w(TAG, "profile proximity calibration cannot hold this source's drift band; not used")
        }
    }
    /** The calibration saved by the on-panel setup, after admission; null when there is none. */
    private var wizard: ProximityCalibrationEngine.Calibration? = null
    private val touchKey = digest("touch-learned-v1|$profileId|$sourceIdentity")
    private var appliedMode = presenceMode()
    private var learner = newLearner(readCalibration(), appliedMode).also { learner ->
        runCatching { touchStore.read(touchKey)?.let { learner.restore(JSONObject(it), elapsed()) } }
            .onFailure { Log.w(TAG, "touch-learned proximity state could not be read", it) }
    }
    private var adopted: ProximityCalibrationEngine.Calibration? = null
    private val engine = ProximityCalibrationEngine(effective(appliedMode).also { adopted = it }, commit = { candidate ->
        val now = wall()
        // This is the only calibration write. The store commits the whole row in one transaction;
        // observation, cancellation, close, and restart have no persistence queue to race it.
        runCatching {
            store.writeProximityBatch(
                EntityCatalogStore.ProximityModelRow(
                    fingerprint, STORAGE_VERSION, "explicit-calibration-v2", encode(candidate), true, now,
                ), emptyList(), emptyList(), now,
            )
        }.isSuccess.also {
            if (it) {
                userOverride = true; readFailed = false; driftUnsafe = false
                wizard = candidate; adopted = candidate
                runCatching(onExplicitSave).onFailure { Log.w(TAG, "presence mode could not follow setup", it) }
                if (appliedMode == PresenceMode.HYBRID) learner = reseed(learner, candidate, appliedMode)
            }
        }
    }, requestedWavePattern = requestedWavePattern, observedSourceMode = observedSourceMode,
        driftMargin = driftMargin, driftDeclaredRange = driftDeclaredRange)
    @Volatile private var view = engine.current()
    @Volatile private var sessionId = ""
    @Volatile private var browserAt = 0L
    @Volatile private var visibleAt = 0L
    @Volatile private var startedAt = 0L
    @Volatile private var closed = false
    @Volatile private var failureMessage = ""
    @Volatile private var sourceProven = false
    @Volatile private var saving = false
    @Volatile private var gestureToken = 0L
    private var completedGestureToken = 0L
    private var processedAt = 0L

    constructor(
        context: Context,
        sourceIdentity: String,
        profileId: String,
        profileRevision: String,
        profile: ProfileProximityCalibration?,
        observedSourceMode: ProximityCalibrationEngine.Mode? = null,
        driftMargin: Float = 0f,
        driftDeclaredRange: Float = 0f,
        keepStoredInsideDriftBand: Boolean = false,
        presenceMode: () -> PresenceMode = { PresenceMode.MANUAL },
        onExplicitSave: () -> Unit = {},
    ) : this(
        sourceIdentity, profileId, profile?.let(::fromProfile),
        SqliteProximityModelStore(EntityCatalogStore(context.applicationContext)),
        requestedWavePattern = profileWavePattern(profile),
        observedSourceMode = observedSourceMode,
        profileRevision = profileRevision,
        driftMargin = driftMargin,
        driftDeclaredRange = driftDeclaredRange,
        keepStoredInsideDriftBand = keepStoredInsideDriftBand,
        presenceMode = presenceMode,
        touchStore = ProximityTouchStore.preferences(context.applicationContext),
        onExplicitSave = onExplicitSave,
    )

    @Synchronized
    fun observe(raw: Float, receivedAt: Long, sparseReporting: Boolean, live: Boolean = true, calibrationLive: Boolean = live): Decision {
        val now = processingTime(receivedAt)
        if (closed) return Decision(ProximityReportGate.NONE, null, null, false)
        expireOwner(now)
        if (!raw.isFinite()) sourceProven = false
        else if (calibrationLive) sourceProven = true
        view = engine.observe(raw, now, live, calibrationLive)
        if (calibrationLive && raw.isFinite() && !view.active && engine.observedRanged() && learner.observe(raw, now)) learned(now)
        applyMode(now)
        return decision(now, sparseReporting)
    }

    @Synchronized
    fun tick(receivedAt: Long = elapsed(), sparseReporting: Boolean = false, heldEvidence: Boolean = false): Decision {
        val now = processingTime(receivedAt)
        if (closed) return Decision(ProximityReportGate.NONE, null, null, false)
        expireOwner(now)
        view = engine.tick(now, heldEvidence && sourceProven)
        applyMode(now)
        return decision(now, sparseReporting)
    }

    @Synchronized
    fun sourceUnavailable(receivedAt: Long = elapsed()): Decision {
        val now = processingTime(receivedAt)
        sourceProven = false
        if (!closed) view = engine.sourceUnavailable(now)
        return decision(now, false)
    }

    fun needsTick(heldEvidence: Boolean = false): Boolean = !closed && (view.active || engine.needsTick(heldEvidence))
    fun isReady(): Boolean = isPresenceReady()
    fun isPresenceReady(): Boolean = !closed && sourceProven && !saving && !view.active && view.presenceReady
    fun isPresenceNear(): Boolean = isPresenceReady() && view.near == true
    fun isWaveReady(): Boolean = !closed && sourceProven && !saving && view.wakeReady
    fun observedRanged(): Boolean = !closed && engine.observedRanged()
    /** The saved setup model's signal mode, or null when setup has saved nothing. */
    fun wizardMode(): ProximityCalibrationEngine.Mode? = wizard?.mode
    fun isLearnedSignal(): Boolean = !closed && view.calibration?.presenceSupported == true

    /**
     * [isLearnedSignal] only when it is known: null once closed, and after the stored calibration could not
     * be read, when the profile baseline stands in and a false would say nothing about the panel's training.
     */
    @Synchronized
    fun learnedSignalState(): Boolean? = if (closed || readFailed) null else isLearnedSignal()
    fun generation(): Long = view.generation
    fun gestureToken(): Long = gestureToken

    @Synchronized
    fun completeGesture(token: Long, accepted: Boolean) {
        if (token != gestureToken || token <= completedGestureToken) return
        completedGestureToken = token
        if (!accepted) engine.releaseGestureCooldown()
        else {
            // The sensor woke the screen: the touches that follow would label its own trigger.
            val now = processingTime(elapsed())
            learner.exclude(now, now + ProximityTouchLearner.WAVE_WAKE_EXCLUSION_MS)
        }
    }

    /**
     * A screen touch: the person is at the panel. [woke] marks a tap that woke a dark screen. Ignored while
     * setup runs, because touches then answer its prompts.
     */
    @Synchronized
    fun touch(receivedAt: Long = elapsed(), woke: Boolean = false) {
        if (closed) return
        val now = processingTime(receivedAt)
        learner.suspend(view.active)
        if (!view.active && engine.observedRanged()) learner.touch(now, woke)
    }

    /** Touch learning's progress for status: null until a ranged source has been seen. */
    fun touchLearning(): ProximityTouchLearner.Model? = learner.model()

    private fun learned(now: Long) {
        runCatching { touchStore.write(touchKey, learner.toJson().toString()) }
            .onFailure { Log.w(TAG, "touch-learned proximity state could not be saved", it) }
        adopt(now)
    }

    private fun applyMode(now: Long) {
        val mode = presenceMode()
        if (mode == appliedMode) return
        appliedMode = mode
        learner = reseed(learner, wizard, mode)
        adopt(now)
    }

    /** Moves the engine to the mode's calibration when it differs materially from the running one. */
    private fun adopt(now: Long) {
        if (view.active || saving) return
        val next = effective(appliedMode) ?: return
        val current = adopted
        if (current != null && !materiallyDifferent(current, next)) return
        adopted = next
        val quiet = learner.model()?.clear?.takeIf { next.clearRaw == it.mean.toFloat() }
        view = engine.adopt(next, quiet, now)
        reportGate.reset()
    }

    private fun effective(mode: PresenceMode): ProximityCalibrationEngine.Calibration? = when (mode) {
        PresenceMode.MANUAL -> wizard ?: baseline
        PresenceMode.HYBRID -> learnedCalibration(wizard) ?: wizard ?: baseline
        // Until touches qualify, and always on a near/far source, a saved setup is better than nothing.
        PresenceMode.AUTOMATIC -> learnedCalibration(null) ?: wizard ?: baseline
        // Presence comes from elsewhere; the panel's own sensor keeps its best calibration for the entity and waves.
        PresenceMode.HOME_ASSISTANT, PresenceMode.OFF -> wizard ?: learnedCalibration(null) ?: baseline
    }

    /**
     * The touch-learned anchors as a calibration. Hybrid keeps the wizard's timings and its captured wave,
     * which measured real waves; otherwise the wave comes from the hand level at touches, with hysteresis
     * set low because a deliberate wave is weaker than a tap.
     */
    private fun learnedCalibration(template: ProximityCalibrationEngine.Calibration?): ProximityCalibrationEngine.Calibration? {
        val model = learner.model() ?: return null
        val ranged = template?.takeIf { it.mode == ProximityCalibrationEngine.Mode.RANGED }
        val clear = model.clear.mean.toFloat()
        val wave = ranged?.effectiveWave() ?: model.waveRaw?.let {
            ProximityCalibrationEngine.WaveCalibration(
                clearRaw = clear, nearRaw = it,
                nearEnter = ProximityTouchLearner.WAVE_NEAR_ENTER, clearExit = ProximityTouchLearner.WAVE_CLEAR_EXIT,
            )
        }
        val value = runCatching {
            (ranged ?: ProximityCalibrationEngine.Calibration(mode = ProximityCalibrationEngine.Mode.RANGED, clearRaw = clear, nearRaw = model.presentRaw))
                .copy(version = 2, clearRaw = clear, nearRaw = model.presentRaw, presenceSupported = true, wave = wave)
        }.recoverCatching {
            // A wizard wave whose direction disagrees with the learned presence cannot share one calibration.
            ProximityCalibrationEngine.Calibration(mode = ProximityCalibrationEngine.Mode.RANGED, clearRaw = clear, nearRaw = model.presentRaw)
        }.getOrNull() ?: return null
        return admitStored(value)
    }

    private fun materiallyDifferent(a: ProximityCalibrationEngine.Calibration, b: ProximityCalibrationEngine.Calibration): Boolean {
        if (a.mode != b.mode || a.presenceSupported != b.presenceSupported || (a.wave == null) != (b.wave == null)) return true
        if ((a.nearRaw > a.clearRaw) != (b.nearRaw > b.clearRaw)) return true
        val span = kotlin.math.abs(a.nearRaw - a.clearRaw).coerceAtLeast(Float.MIN_VALUE)
        fun moved(x: Float, y: Float) = kotlin.math.abs(x - y) > span * ADOPT_FRACTION
        val waveMoved = a.wave != null && b.wave != null &&
            (moved(a.wave.nearRaw, b.wave.nearRaw) || a.wave.nearEnter != b.wave.nearEnter)
        return moved(a.clearRaw, b.clearRaw) || moved(a.nearRaw, b.nearRaw) || waveMoved
    }

    private fun newLearner(wizardValue: ProximityCalibrationEngine.Calibration?, mode: PresenceMode) = ProximityTouchLearner(
        wizardValue?.takeIf { mode == PresenceMode.HYBRID && it.mode == ProximityCalibrationEngine.Mode.RANGED && it.presenceSupported }
            ?.let { ProximityTouchLearner.Seed(it.clearRaw, it.nearRaw, null) },
    )

    /** A different seed keeps every touch already learned. */
    private fun reseed(current: ProximityTouchLearner, wizardValue: ProximityCalibrationEngine.Calibration?, mode: PresenceMode) =
        newLearner(wizardValue, mode).also { it.restore(current.toJson(), elapsed()) }
    fun active(): Boolean = !closed && view.active

    /** HTML creates only the introduction. Only a visible local Activity can begin collecting. */
    @Synchronized
    fun start(): Boolean {
        if (closed || view.active || saving) return false
        val now = processingTime(elapsed())
        sessionId = UUID.randomUUID().toString()
        browserAt = now
        visibleAt = 0L
        startedAt = now
        failureMessage = ""
        sourceProven = false
        view = engine.start(now)
        return view.active
    }

    fun visible(): Boolean {
        if (closed || sessionId.isEmpty()) return false
        visibleAt = elapsed()
        return true
    }

    @Synchronized
    fun heartbeat(id: String): Boolean {
        if (closed || id.isEmpty() || id != sessionId) return false
        browserAt = elapsed()
        return true
    }

    /** Invoked on the service's background action lane, never on the Activity main thread. */
    @Synchronized
    fun localAction(action: String): Boolean {
        if (closed || sessionId.isEmpty() || saving) return false
        val now = processingTime(elapsed())
        expireOwner(now)
        if (action !in setOf("begin", "retry", "save", "cancel")) return false
        if (action != "cancel" && (visibleAt <= 0L || now - visibleAt > LOCAL_VISIBILITY_MS)) return false
        if (action == "begin" && (!sourceProven || !view.available)) return false
        if (action == "retry" && !view.active) {
            sourceProven = false
            startedAt = now
            browserAt = now
            failureMessage = ""
        }
        val before = view
        saving = action == "save" && before.stage == ProximityCalibrationEngine.Stage.REVIEW
        try {
            view = engine.action(action, now)
        } finally { saving = false }
        return before != view
    }

    @Synchronized
    fun cancel(id: String? = null, message: String = ""): Boolean {
        if (closed || (id != null && id != sessionId)) return false
        val wasActive = view.active
        if (wasActive) {
            view = engine.action("cancel", processingTime(elapsed()))
            failureMessage = message
        }
        return wasActive
    }

    @Synchronized
    fun resetToProfile(): Boolean {
        if (closed || view.active || saving || baseline == null) return false
        // The old key too: a calibration carried from it would otherwise be carried back at the next start.
        if (runCatching {
                store.clearProximityLearning(fingerprint)
                legacyFingerprint?.let(store::clearProximityLearning)
            }.isFailure) return false
        userOverride = false
        readFailed = false
        driftUnsafe = false
        sessionId = ""
        failureMessage = ""
        wizard = null
        adopted = baseline
        view = engine.reset(baseline, processingTime(elapsed()))
        reportGate.reset()
        return true
    }

    /**
     * Takes up a calibration a restore has just written to the store. Only a stored calibration that differs
     * from the one running is adopted: a read that fails or finds nothing leaves the panel exactly as it was,
     * and so does a setup in progress. A restored calibration passes the same drift check as one read at
     * start, so reloading now leaves the panel where a restart would: one inside the band is declined, or on
     * a route that keeps such calibrations, kept as saved.
     */
    @Synchronized
    fun reloadStored(): Boolean {
        if (closed || view.active || saving) return false
        val restored = runCatching { stored(fingerprint) }.getOrNull()?.second ?: return false
        val margined = admitStored(restored)
        if (margined == null) {
            if (driftUnsafe && !userOverride) return false
            driftUnsafe = true
            userOverride = false
            readFailed = false
            view = engine.reset(baseline, processingTime(elapsed()))
            reportGate.reset()
            return true
        }
        if (margined == view.calibration) return false
        userOverride = true
        readFailed = false
        driftUnsafe = false
        wizard = margined
        adopted = margined
        view = engine.reset(margined, processingTime(elapsed()))
        reportGate.reset()
        return true
    }

    fun summary(): String = when {
        closed || !sourceProven || !view.available -> "Proximity source unavailable"
        view.active -> "Follow proximity setup on the panel"
        view.calibration == null && readFailed -> STORAGE_UNREADABLE_MESSAGE
        view.calibration == null -> "Proximity setup is available on the panel"
        else -> "Calibrated proximity · ${view.mode?.name?.lowercase(Locale.ROOT)}"
    }

    private fun phase(state: ProximityCalibrationEngine.Result): String = when {
        state.active -> "calibrating"
        !sourceProven || !state.available -> "source_unavailable"
        // Unreadable storage is not a missing calibration: asking the owner to set it up again would be
        // wrong, and would replace one that may still be there.
        state.calibration == null && readFailed -> "source_unavailable"
        // Automatic and Hybrid calibrate themselves in use; setup is optional, so nothing is required yet.
        state.calibration == null && appliedMode.learnsFromTouches && engine.observedRanged() -> "learning"
        state.calibration == null -> "calibration_required"
        else -> "ready"
    }

    /** The `proximity` component of `GET /api/v1/status`, which Panel Assistant reads to raise its
     *  calibration Repair: the setup phase and whether a wave can wake the panel now. */
    fun statusJson(): String = JSONObject()
        .put("state", phase(view))
        .put("wake_ready", isWaveReady())
        .toString()

    /** Status never waits for a save transaction; the native instruction surface must stay responsive. */
    fun json(raw: Float = Float.NaN, ageSeconds: Long? = null): String {
        val state = view
        val phase = phase(state)
        val mode = (state.mode ?: observedSourceMode)?.name?.lowercase(Locale.ROOT) ?: "unknown"
        val message = failureMessage.ifEmpty {
            if (state.stage == null) summary() else state.message.ifEmpty { summary() }
        }
        return JSONObject().apply {
            put("present", true)
            put("phase", phase)
            put("learning", phase)
            put("stage", if (saving) "saving" else state.stage?.name?.lowercase(Locale.ROOT) ?: "")
            put("sessionId", sessionId)
            put("sessionActive", state.active)
            put("session", JSONObject().put("active", state.active).put("kind", "calibration").put("message", message))
            put("message", message)
            put("mode", mode)
            put("signalMode", mode)
            put("rangedEligible", (state.mode ?: observedSourceMode) == ProximityCalibrationEngine.Mode.RANGED)
            put("source", if (observedSourceMode == ProximityCalibrationEngine.Mode.RANGED) "driver_raw16" else "standard")
            put("health", if (sourceProven && state.available) "healthy" else "source_unavailable")
            put("polarity", state.calibration?.let { if (it.nearRaw > it.clearRaw) "near_is_higher" else "near_is_lower" } ?: "unknown")
            put("acceptedGestures", state.accepted)
            put("requiredGestures", 3)
            put("remainingSeconds", ((SESSION_TIMEOUT_MS - (elapsed() - startedAt)).coerceAtLeast(0L) / 1000L))
            put("ready", isReady())
            put("wakeReady", isWaveReady())
            put("presenceReady", isPresenceReady())
            put("presenceSupported", state.presenceSupported)
            put("presenceStatus", state.presenceStatus.name.lowercase(Locale.ROOT))
            put("waveSupported", state.waveSupported)
            put("waveStatus", state.waveStatus.name.lowercase(Locale.ROOT))
            put("wavePattern", state.wavePattern?.name?.lowercase(Locale.ROOT) ?: JSONObject.NULL)
            put("cue", state.cue.name.lowercase(Locale.ROOT))
            put("cueRemainingMs", state.cueRemainingMs)
            put("cueDurationMs", state.cueDurationMs)
            put("canSave", !closed && !saving && state.canSave)
            put("canCalibrate", !closed && !state.active && !saving)
            put("canTeach", !closed && !state.active && !saving)
            put("canTest", false)
            put("near", state.near ?: JSONObject.NULL)
            put("level", state.level ?: JSONObject.NULL)
            put("raw", if (raw.isFinite()) raw else JSONObject.NULL)
            put("age_s", ageSeconds ?: JSONObject.NULL)
            put("calibrationSource", when {
                adopted != null && adopted == state.calibration && adopted != wizard && adopted != baseline -> "touch"
                userOverride -> "user"
                baseline != null -> "profile"
                else -> "none"
            })
            put("profileDefaultAvailable", baseline != null)
            put("calibrationReadFailed", readFailed)
            put("calibrationDriftUnsafe", driftUnsafe)
            put("presenceMode", appliedMode.wire)
            put("touchLearning", learner.model().let { model ->
                JSONObject().put("ready", model != null)
                    .put("seenTouches", model?.seenTouches ?: 0)
                    .put("unseenTouches", model?.unseenTouches ?: 0)
            })
        }.toString()
    }

    /** Callers capture receipt time before taking this monitor. A queued callback must not make a
     * later local action look like a clock rollback; sensor-event freshness is checked separately. */
    private fun processingTime(receivedAt: Long): Long = maxOf(receivedAt, elapsed(), processedAt).also { processedAt = it }

    private fun expireOwner(now: Long) {
        if (!view.active) return
        val reason = when {
            now < startedAt || now - startedAt >= SESSION_TIMEOUT_MS -> "Setup timed out. Your previous calibration is unchanged."
            now < browserAt || now - browserAt > BROWSER_LEASE_MS -> "The setup browser disconnected. Your previous calibration is unchanged."
            visibleAt == 0L && now - startedAt > LAUNCH_TIMEOUT_MS -> "Could not show setup on the panel. Your previous calibration is unchanged."
            visibleAt > 0L && now - visibleAt > LOCAL_VISIBILITY_MS -> "The panel setup screen closed. Your previous calibration is unchanged."
            view.stage == ProximityCalibrationEngine.Stage.INTRO && !(sourceProven && view.available) &&
                now - startedAt >= NO_READING_TIMEOUT_MS -> NO_READING_MESSAGE
            else -> null
        }
        if (reason != null) {
            view = engine.action("cancel", now)
            failureMessage = reason
        }
    }

    private fun decision(now: Long, sparseReporting: Boolean): Decision {
        val state = view
        val near = state.near.takeIf { sourceProven && state.available && state.presenceSupported && !state.active }
        val level = state.level.takeIf { near != null }?.let { if (near == false) 0 else it }
        val gesture = state.gesture && sourceProven && !saving
        if (gesture) gestureToken++
        val approach = state.presenceApproach && sourceProven && !saving && !state.active
        return Decision(reportGate.project(near, level, now, sparseReporting), near, level, gesture, approach)
    }

    private fun readCalibration(): ProximityCalibrationEngine.Calibration? {
        val value = runCatching {
            stored(fingerprint) ?: legacyFingerprint?.let(::stored)?.also { (row, _) ->
                // Carry it to the current key once, so a later revision cannot hide it again. Failing that
                // write costs nothing: the old row stays and is read the same way next start.
                runCatching { store.insertProximityModelIfAbsent(row.copy(fingerprint = fingerprint)) }
            }
        }.getOrElse { failure ->
            Log.w(TAG, "saved proximity calibration could not be read", failure)
            readFailed = true
            return baseline
        }?.second ?: return baseline
        // Admission is in memory only. Quiet statistics never alter the saved calibration row.
        val margined = admitStored(value)
        if (margined == null) {
            driftUnsafe = true
            return baseline
        }
        userOverride = true
        wizard = margined
        return margined
    }

    /** Presence keeps measured anchors for polarity and learns its own local band on every ranged route.
     * Wave admission retains the previous drift margin and the route's existing retained-wave policy. */
    private fun admitStored(value: ProximityCalibrationEngine.Calibration): ProximityCalibrationEngine.Calibration? {
        val previous = ProximityCalibrationEngine.driftMargined(value, driftMargin, driftDeclaredRange)
        if (previous != null) return previous
        if (keepStoredInsideDriftBand) return value
        if (value.mode != ProximityCalibrationEngine.Mode.RANGED || !value.presenceSupported) return null
        // The previous admission refused this whole calibration, including any explicit or legacy
        // wave. Accepting its presence anchors must not enable a previously unavailable wave wake.
        return value.copy(version = 2, wave = null)
    }

    private fun stored(key: String): Pair<EntityCatalogStore.ProximityModelRow, ProximityCalibrationEngine.Calibration>? {
        val row = store.readProximityModel(key) ?: return null
        if (row.algorithmVersion !in setOf(LEGACY_STORAGE_VERSION, STORAGE_VERSION) || row.fingerprint != key || !row.ready) return null
        val value = decode(row.snapshotJson) ?: return null
        if (row.algorithmVersion == LEGACY_STORAGE_VERSION && value.version != 1) return null
        return row to value
    }

    @Synchronized
    override fun close() {
        if (closed) return
        view = engine.action("cancel", processingTime(elapsed()))
        closed = true
        store.close()
    }

    fun closeAsync(): CompletableFuture<Unit> = runCatching { close(); CompletableFuture.completedFuture(Unit) }
        .getOrElse { CompletableFuture<Unit>().also { future -> future.completeExceptionally(it) } }

    companion object {
        const val STORAGE_VERSION = 1002
        /** A learned anchor moves this fraction of the presence span before the running calibration follows. */
        private const val ADOPT_FRACTION = .1f
        const val LEGACY_STORAGE_VERSION = 1001
        const val SESSION_TIMEOUT_MS = 300_000L
        const val BROWSER_LEASE_MS = 30_000L
        const val LOCAL_VISIBILITY_MS = 5_000L
        const val LAUNCH_TIMEOUT_MS = 15_000L
        /** The intro waits this long for a live reading before saying why it cannot start. */
        const val NO_READING_TIMEOUT_MS = 30_000L
        const val NO_READING_MESSAGE = "The proximity sensor sent no reading in 30 seconds. Check that nothing covers it, " +
            "such as tape, film or a case over the edge of the screen, then start setup again. " +
            "If it still shows no reading, restart the panel. Your previous calibration is unchanged."

        private const val TAG = "ha-paneld/proximity"
        const val STORAGE_UNREADABLE_MESSAGE = "The saved proximity calibration could not be read. It has not been changed."

        fun fingerprint(identity: String): String = digest("explicit-proximity-v1|$identity")

        /** The store key for a calibration: [profileId] and [sourceIdentity], never the profile's revision. */
        fun calibrationKey(profileId: String, sourceIdentity: String): String =
            digest("explicit-proximity-v2|$profileId|$sourceIdentity")

        private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) }

        fun profileWavePattern(profile: ProfileProximityCalibration?): ProximityCalibrationEngine.WavePattern =
            profile?.wave?.pattern?.let { ProximityCalibrationEngine.WavePattern.valueOf(it.uppercase(Locale.ROOT)) }
                ?: ProximityCalibrationEngine.WavePattern.SINGLE

        fun fromProfile(value: ProfileProximityCalibration) = ProximityCalibrationEngine.Calibration(
            version = value.formatVersion,
            mode = ProximityCalibrationEngine.Mode.valueOf(value.mode.uppercase(Locale.ROOT)),
            clearRaw = value.clearRaw, nearRaw = value.nearRaw,
            nearEnter = value.nearEnter, clearExit = value.clearExit,
            debounceMs = value.debounceMs.toLong(), clearArmMs = value.clearArmMs.toLong(),
            minimumNearMs = value.minimumNearMs.toLong(), maximumNearMs = value.maximumNearMs.toLong(),
            cooldownMs = value.cooldownMs.toLong(),
            presenceSupported = value.presenceSupported,
            wave = value.wave?.let {
                ProximityCalibrationEngine.WaveCalibration(
                    pattern = ProximityCalibrationEngine.WavePattern.valueOf(it.pattern.uppercase(Locale.ROOT)),
                    clearRaw = it.clearRaw, nearRaw = it.nearRaw, nearEnter = it.nearEnter, clearExit = it.clearExit,
                    debounceMs = it.debounceMs.toLong(), clearArmMs = it.clearArmMs.toLong(),
                    minimumNearMs = it.minimumNearMs.toLong(), maximumNearMs = it.maximumNearMs.toLong(),
                    cooldownMs = it.cooldownMs.toLong(), maxInterWaveGapMs = it.maxInterWaveGapMs.toLong(),
                )
            },
        )

        fun encode(value: ProximityCalibrationEngine.Calibration): String = JSONObject().apply {
            put("version", value.version); put("mode", value.mode.name)
            put("clearRaw", value.clearRaw); put("nearRaw", value.nearRaw)
            put("nearEnter", value.nearEnter); put("clearExit", value.clearExit)
            put("debounceMs", value.debounceMs); put("clearArmMs", value.clearArmMs)
            put("minimumNearMs", value.minimumNearMs); put("maximumNearMs", value.maximumNearMs)
            put("cooldownMs", value.cooldownMs)
            if (value.version == 2) {
                put("presenceSupported", value.presenceSupported)
                put("wave", value.wave?.let { wave -> JSONObject().apply {
                    put("pattern", wave.pattern.name)
                    put("clearRaw", wave.clearRaw); put("nearRaw", wave.nearRaw)
                    put("nearEnter", wave.nearEnter); put("clearExit", wave.clearExit)
                    put("debounceMs", wave.debounceMs); put("clearArmMs", wave.clearArmMs)
                    put("minimumNearMs", wave.minimumNearMs); put("maximumNearMs", wave.maximumNearMs)
                    put("cooldownMs", wave.cooldownMs); put("maxInterWaveGapMs", wave.maxInterWaveGapMs)
                } } ?: JSONObject.NULL)
            }
        }.toString()

        fun decode(raw: String): ProximityCalibrationEngine.Calibration? = runCatching {
            require(raw.length <= 4096)
            val value = JSONObject(raw)
            val version = integer(value, "version").toInt()
            require(version in 1..2)
            val mode = ProximityCalibrationEngine.Mode.valueOf(value.getString("mode"))
            val wave = if (version == 2 && !value.isNull("wave")) {
                val nested = value.getJSONObject("wave")
                ProximityCalibrationEngine.WaveCalibration(
                    pattern = ProximityCalibrationEngine.WavePattern.valueOf(nested.getString("pattern")),
                    clearRaw = number(nested, "clearRaw"), nearRaw = number(nested, "nearRaw"),
                    nearEnter = number(nested, "nearEnter"), clearExit = number(nested, "clearExit"),
                    debounceMs = integer(nested, "debounceMs"), clearArmMs = integer(nested, "clearArmMs"),
                    minimumNearMs = integer(nested, "minimumNearMs"), maximumNearMs = integer(nested, "maximumNearMs"),
                    cooldownMs = integer(nested, "cooldownMs"), maxInterWaveGapMs = integer(nested, "maxInterWaveGapMs"),
                ).also {
                    require(mode != ProximityCalibrationEngine.Mode.BINARY || setOf(it.clearRaw, it.nearRaw) == setOf(0f, 1f))
                    boundedTiming(it.debounceMs, it.clearArmMs, it.minimumNearMs, it.maximumNearMs, it.cooldownMs)
                }
            } else null
            if (version == 1) require(!value.has("wave") && !value.has("presenceSupported"))
            ProximityCalibrationEngine.Calibration(
                version = version, mode = mode,
                clearRaw = number(value, "clearRaw"), nearRaw = number(value, "nearRaw"),
                nearEnter = number(value, "nearEnter"), clearExit = number(value, "clearExit"),
                debounceMs = integer(value, "debounceMs"), clearArmMs = integer(value, "clearArmMs"),
                minimumNearMs = integer(value, "minimumNearMs"), maximumNearMs = integer(value, "maximumNearMs"),
                cooldownMs = integer(value, "cooldownMs"),
                presenceSupported = if (version == 1) true else value.get("presenceSupported").let { require(it is Boolean); it },
                wave = wave,
            ).also { boundedTiming(it.debounceMs, it.clearArmMs, it.minimumNearMs, it.maximumNearMs, it.cooldownMs) }
        }.getOrNull()

        private fun number(value: JSONObject, key: String): Float {
            val raw = value.get(key)
            require(raw is Number && raw.toDouble().isFinite())
            return raw.toFloat().also { require(it.isFinite()) }
        }

        private fun integer(value: JSONObject, key: String): Long {
            val raw = value.get(key)
            require(raw is Number && raw.toDouble().isFinite() && raw.toDouble() % 1.0 == 0.0)
            return raw.toLong().also { require(it in 0..60_000) }
        }

        private fun boundedTiming(debounce: Long, clearArm: Long, minimumNear: Long, maximumNear: Long, cooldown: Long) {
            require(debounce in 0..5_000 && clearArm in 0..30_000 && minimumNear in 0..10_000)
            require(maximumNear in 0..30_000 && cooldown in 0..60_000)
        }
    }
}
