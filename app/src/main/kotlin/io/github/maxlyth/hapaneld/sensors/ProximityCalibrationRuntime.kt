package io.github.maxlyth.hapaneld.sensors

import android.content.Context
import android.os.SystemClock
import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import io.github.maxlyth.hapaneld.device.profile.ProfileProximityCalibration
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import org.json.JSONObject

/** Fixed calibration, an explicit local wizard, and one atomic durable save. No ambient model writes. */
internal class ProximityCalibrationRuntime(
    sourceIdentity: String,
    profileIdentity: String,
    private val baseline: ProximityCalibrationEngine.Calibration?,
    private val store: ProximityModelStore,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val wall: () -> Long = System::currentTimeMillis,
    requestedWavePattern: ProximityCalibrationEngine.WavePattern = ProximityCalibrationEngine.WavePattern.SINGLE,
    private val observedSourceMode: ProximityCalibrationEngine.Mode? = null,
) : AutoCloseable {
    data class Decision(val reportMask: Int, val near: Boolean?, val normalizedLevel: Int?, val deliberateGesture: Boolean, val presenceApproach: Boolean = false)

    private val fingerprint = fingerprint("$profileIdentity|$sourceIdentity")
    private val reportGate = ProximityReportGate(densePresenceStabilityMs = 0)
    private var userOverride = false
    private var readFailed = false
    private val engine = ProximityCalibrationEngine(readCalibration(), commit = { candidate ->
        val now = wall()
        // This is the only calibration write. The store commits the whole row in one transaction;
        // observation, cancellation, close, and restart have no persistence queue to race it.
        runCatching {
            store.writeProximityBatch(
                EntityCatalogStore.ProximityModelRow(
                    fingerprint, STORAGE_VERSION, "explicit-calibration-v2", encode(candidate), true, now,
                ), emptyList(), emptyList(), now,
            )
        }.isSuccess.also { if (it) { userOverride = true; readFailed = false } }
    }, requestedWavePattern = requestedWavePattern, observedSourceMode = observedSourceMode)
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

    constructor(context: Context, sourceIdentity: String, profileIdentity: String, profile: ProfileProximityCalibration?, observedSourceMode: ProximityCalibrationEngine.Mode? = null) : this(
        sourceIdentity, profileIdentity, profile?.let(::fromProfile),
        SqliteProximityModelStore(EntityCatalogStore(context.applicationContext)),
        requestedWavePattern = profileWavePattern(profile),
        observedSourceMode = observedSourceMode,
    )

    @Synchronized
    fun observe(raw: Float, receivedAt: Long, sparseReporting: Boolean, live: Boolean = true, calibrationLive: Boolean = live): Decision {
        val now = processingTime(receivedAt)
        if (closed) return Decision(ProximityReportGate.NONE, null, null, false)
        expireOwner(now)
        if (!raw.isFinite()) sourceProven = false
        else if (calibrationLive) sourceProven = true
        view = engine.observe(raw, now, live, calibrationLive)
        return decision(now, sparseReporting)
    }

    @Synchronized
    fun tick(receivedAt: Long = elapsed(), sparseReporting: Boolean = false): Decision {
        val now = processingTime(receivedAt)
        if (closed) return Decision(ProximityReportGate.NONE, null, null, false)
        expireOwner(now)
        view = engine.tick(now)
        return decision(now, sparseReporting)
    }

    @Synchronized
    fun sourceUnavailable(receivedAt: Long = elapsed()): Decision {
        val now = processingTime(receivedAt)
        sourceProven = false
        if (!closed) view = engine.sourceUnavailable(now)
        return decision(now, false)
    }

    fun needsTick(): Boolean = !closed && (view.active || engine.needsTick())
    fun isReady(): Boolean = isPresenceReady()
    fun isPresenceReady(): Boolean = !closed && sourceProven && !saving && !view.active && view.presenceReady
    fun isPresenceNear(): Boolean = isPresenceReady() && view.near == true
    fun isWaveReady(): Boolean = !closed && sourceProven && !saving && view.wakeReady
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
    }
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
        if (runCatching { store.clearProximityLearning(fingerprint) }.isFailure) return false
        userOverride = false
        readFailed = false
        sessionId = ""
        failureMessage = ""
        view = engine.reset(baseline, processingTime(elapsed()))
        reportGate.reset()
        return true
    }

    fun summary(): String = when {
        closed || !sourceProven || !view.available -> "Proximity source unavailable"
        view.active -> "Follow proximity setup on the panel"
        view.calibration == null -> "Proximity setup is available on the panel"
        else -> "Calibrated proximity · ${view.mode?.name?.lowercase(Locale.ROOT)}"
    }

    /** Status never waits for a save transaction; the native instruction surface must stay responsive. */
    fun json(raw: Float = Float.NaN, ageSeconds: Long? = null): String {
        val state = view
        val phase = when {
            state.active -> "calibrating"
            !sourceProven || !state.available -> "source_unavailable"
            state.calibration == null -> "calibration_required"
            else -> "ready"
        }
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
            put("calibrationSource", if (userOverride) "user" else if (baseline != null) "profile" else "none")
            put("profileDefaultAvailable", baseline != null)
            put("calibrationReadFailed", readFailed)
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
        val row = runCatching { store.readProximityModel(fingerprint) }.getOrElse { readFailed = true; return baseline }
            ?: return baseline
        if (row.algorithmVersion !in setOf(LEGACY_STORAGE_VERSION, STORAGE_VERSION) || row.fingerprint != fingerprint || !row.ready) return baseline
        val value = decode(row.snapshotJson) ?: return baseline
        if (row.algorithmVersion == LEGACY_STORAGE_VERSION && value.version != 1) return baseline
        userOverride = true
        return value
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

        fun fingerprint(identity: String): String = MessageDigest.getInstance("SHA-256")
            .digest("explicit-proximity-v1|$identity".toByteArray(Charsets.UTF_8))
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
