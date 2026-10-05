package io.panelassistant.android.sensors

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Fixed presence interpretation and independently validated deliberate gestures. Only Save persists. */
internal class ProximityCalibrationEngine(
    initial: Calibration? = null,
    private val requestedWavePattern: WavePattern = WavePattern.SINGLE,
    private val observedSourceMode: Mode? = null,
    private val commit: (Calibration) -> Boolean = { true },
) {
    enum class Mode { BINARY, RANGED }
    enum class WavePattern { SINGLE, DOUBLE }
    enum class CapabilityStatus { NOT_TESTED, VALIDATED, NOT_DETECTED, NOT_DISTINGUISHABLE, UNAVAILABLE }
    enum class Cue { PREPARE, APPROACH, HOLD, MOVE_AWAY, WAIT_CLEAR, WAVE }
    enum class Stage { INTRO, CLEAR, NEAR, RETURN_CLEAR, WAVE_BASELINE, WAVE_CAPTURE, WAVES, REVIEW, SAVED, CANCELLED, TIMED_OUT, FAILED }

    data class WaveCalibration(
        val pattern: WavePattern = WavePattern.SINGLE,
        val clearRaw: Float,
        val nearRaw: Float,
        val nearEnter: Float = .65f,
        val clearExit: Float = .30f,
        val debounceMs: Long = 150,
        val clearArmMs: Long = 700,
        val minimumNearMs: Long = 200,
        val maximumNearMs: Long = 4_000,
        val cooldownMs: Long = 1_000,
        val maxInterWaveGapMs: Long = 1_800,
    ) {
        init {
            require(maxInterWaveGapMs in 300..10_000)
            require(clearRaw.isFinite() && nearRaw.isFinite() && clearRaw != nearRaw && (nearRaw - clearRaw).isFinite())
            require(clearExit >= 0f && nearEnter <= 1f && clearExit < nearEnter)
            require(debounceMs >= 0 && clearArmMs >= debounceMs && minimumNearMs >= debounceMs)
            require(maximumNearMs >= minimumNearMs && cooldownMs >= 0)
        }
        fun model(sourceMode: Mode = Mode.RANGED) = Calibration(
            mode = sourceMode,
            clearRaw = clearRaw, nearRaw = nearRaw, nearEnter = nearEnter, clearExit = clearExit,
            debounceMs = debounceMs, clearArmMs = clearArmMs, minimumNearMs = minimumNearMs,
            maximumNearMs = maximumNearMs, cooldownMs = 0,
        )
    }

    data class Calibration(
        val version: Int = 2,
        val mode: Mode,
        val clearRaw: Float,
        val nearRaw: Float,
        val nearEnter: Float = .65f,
        val clearExit: Float = .30f,
        val debounceMs: Long = 150,
        val clearArmMs: Long = 700,
        val minimumNearMs: Long = 200,
        val maximumNearMs: Long = 4_000,
        val cooldownMs: Long = 1_000,
        val presenceSupported: Boolean = true,
        val wave: WaveCalibration? = null,
    ) {
        init {
            require(version in 1..2)
            require(clearRaw.isFinite() && nearRaw.isFinite() && clearRaw != nearRaw && (nearRaw - clearRaw).isFinite())
            require(clearExit >= 0f && nearEnter <= 1f && clearExit < nearEnter)
            require(debounceMs >= 0 && clearArmMs >= debounceMs && minimumNearMs >= debounceMs)
            require(maximumNearMs >= minimumNearMs && cooldownMs >= 0)
            require(mode != Mode.BINARY || isBinaryPair(clearRaw, nearRaw))
            require(mode != Mode.BINARY || wave == null || isBinaryPair(wave.clearRaw, wave.nearRaw))
            require(presenceSupported || wave != null)
            require(wave == null || (wave.nearRaw > wave.clearRaw) == (nearRaw > clearRaw))
        }
        fun level(raw: Float): Int = (((raw.toDouble() - clearRaw) / (nearRaw.toDouble() - clearRaw)) * 100.0)
            .coerceIn(0.0, 100.0).roundToInt()
        fun legacyWave() = WaveCalibration(
            clearRaw = clearRaw, nearRaw = nearRaw, nearEnter = nearEnter, clearExit = clearExit,
            debounceMs = debounceMs, clearArmMs = clearArmMs, minimumNearMs = minimumNearMs,
            maximumNearMs = maximumNearMs, cooldownMs = cooldownMs,
        )
        fun effectiveWave(): WaveCalibration? = wave ?: if (version == 1) legacyWave() else null
    }

    data class Result(
        val stage: Stage?, val message: String, val accepted: Int, val near: Boolean?, val level: Int?,
        val mode: Mode?, val available: Boolean, val wakeReady: Boolean, val active: Boolean,
        val gesture: Boolean, val generation: Long, val calibration: Calibration?, val needsTick: Boolean,
        val presenceApproach: Boolean = false,
        val presenceReady: Boolean = false,
        val presenceSupported: Boolean = false,
        val presenceStatus: CapabilityStatus = CapabilityStatus.NOT_TESTED,
        val waveSupported: Boolean = false,
        val waveStatus: CapabilityStatus = CapabilityStatus.NOT_TESTED,
        val cue: Cue = Cue.PREPARE,
        val cueRemainingMs: Long = 0,
        val cueDurationMs: Long = 0,
        val canSave: Boolean = false,
        val wavePattern: WavePattern? = null,
    )

    private var calibration = initial
    private var detector = initial?.takeIf { it.presenceSupported }?.let(::Detector)
    private var waveDetector = initial?.let(::createWaveDetector)
    private var pendingOperationalPulse: Long? = null
    private var operationalCooldownUntil = 0L
    private var candidate: Calibration? = null
    private var candidateWave: WaveCalibration? = null
    private var candidateWaveMode: Mode? = null
    private var candidateDetector: Detector? = null
    private var pendingCandidatePulse: Long? = null
    private var stage: Stage? = null
    private var message = ""
    private var accepted = 0
    private var generation = 0L
    private var lastTime: Long? = null
    private var lastRaw: Float? = null
    private var lastRawCapture = false
    private var available = false
    private var sessionStarted = 0L
    private var stageStarted = 0L
    private var captureStarted: Long? = null
    private var returnClearSince: Long? = null
    private var wavePeak: Float? = null
    private var waveStarted: Long? = null
    private var observedNonBinary = observedSourceMode == Mode.RANGED
    private var presenceStatus = CapabilityStatus.NOT_TESTED
    private var waveStatus = CapabilityStatus.NOT_TESTED
    private val clearSamples = Samples()
    private val nearSamples = Samples()
    private val waveBaselineSamples = Samples()

    @Synchronized fun current(): Result = result()
    @Synchronized fun needsTick(): Boolean = active() || detector?.needsTick() == true || waveDetector?.needsTick() == true || pendingOperationalPulse != null
    @Synchronized fun releaseGestureCooldown() { operationalCooldownUntil = 0L; waveDetector?.releaseCooldown() }

    @Synchronized fun start(now: Long): Result {
        if (!admit(now) || active()) return result()
        generation++
        resetOperational()
        discardCandidate()
        accepted = 0
        sessionStarted = now
        available = false
        lastRaw = null
        lastRawCapture = false
        transition(Stage.INTRO, now, "Set up normal approach detection, then test deliberate hand waves.")
        return result()
    }

    @Synchronized fun action(action: String, now: Long): Result {
        if (!admit(now)) return result()
        expire(now)
        when (action.lowercase()) {
            "begin", "advance" -> if (stage == Stage.INTRO) transition(Stage.CLEAR, now, "Move away from the panel. The empty area will be measured after the countdown.")
            "retry" -> if (!active()) return start(now)
            "cancel" -> if (active()) finish(Stage.CANCELLED, now, "Setup cancelled. Your previous calibration is unchanged.")
            "save" -> if (stage == Stage.REVIEW) {
                val proposed = candidate
                if (proposed == null || !available) {
                    message = "No verified capability can be saved. Your previous calibration is unchanged."
                } else if (runCatching { commit(proposed) }.getOrDefault(false)) {
                    calibration = proposed
                    detector = proposed.takeIf { it.presenceSupported }?.let(::Detector)
                    waveDetector = createWaveDetector(proposed)
                    finish(Stage.SAVED, now, "Calibration saved. Presence and wave features use their verified capabilities when enabled.")
                } else finish(Stage.FAILED, now, "Could not save calibration. Your previous calibration is unchanged.")
            }
        }
        return result()
    }

    @Synchronized fun observe(raw: Float, now: Long, live: Boolean = true, calibrationLive: Boolean = live): Result {
        if (!admit(now)) return result()
        expire(now)
        if (!raw.isFinite()) return loseSource(now, "Invalid sensor reading. Your previous calibration is unchanged.")
        if (!active() && calibration?.mode == Mode.BINARY && raw != 0f && raw != 1f) return loseSource(now, "The sensor representation changed. Start setup again.")
        available = if (stage == Stage.INTRO) calibrationLive else true
        lastRaw = raw
        lastRawCapture = calibrationLive
        if (active()) {
            if ((candidate?.mode == Mode.BINARY || candidateWaveMode == Mode.BINARY) && raw != 0f && raw != 1f) return loseSource(now, "The sensor representation changed. Your previous calibration is unchanged.")
            if (calibrationLive && raw != 0f && raw != 1f) observedNonBinary = true
            detector?.observe(raw, now, false)
            waveDetector?.observe(raw, now, false)
            if (calibrationLive) advanceWizard(raw, now, true) else {
                candidateDetector?.observe(raw, now, false)
                pendingCandidatePulse = null
                waveStarted = null
                wavePeak = null
                captureStarted = null
                returnClearSince = null
                if (stage == Stage.CLEAR) clearSamples.clear()
                if (stage == Stage.NEAR) nearSamples.clear()
                if (stage == Stage.WAVE_BASELINE) waveBaselineSamples.clear()
            }
            return result()
        }
        if (!live) { generation++; pendingOperationalPulse = null }
        detector?.observe(raw, now, live)
        val pulse = waveDetector?.observe(raw, now, live) == true
        return result(operationalGesture(pulse, now), detector?.presenceApproach == true)
    }

    @Synchronized fun tick(now: Long): Result {
        if (!admit(now)) return result()
        expire(now)
        detector?.tick(now)
        val pulse = waveDetector?.tick(now) == true
        if (active() && available && lastRawCapture) lastRaw?.let { advanceWizard(it, now, false) }
        return if (active()) result() else result(operationalGesture(pulse, now), detector?.presenceApproach == true)
    }

    @Synchronized fun sourceUnavailable(now: Long): Result {
        if (!admit(now)) return result()
        expire(now)
        if (stage == Stage.INTRO) {
            available = false; lastRaw = null; lastRawCapture = false
            generation++; resetOperational(); discardCandidate()
            message = "Waiting for a fresh proximity reading. Move your hand toward the panel, then move clear."
            return result()
        }
        return loseSource(now, "Sensor connection lost. Your previous calibration is unchanged.")
    }

    @Synchronized fun reset(calibration: Calibration?, now: Long): Result {
        if (!admit(now)) return result()
        this.calibration = calibration
        detector = calibration?.takeIf { it.presenceSupported }?.let(::Detector)
        waveDetector = calibration?.let(::createWaveDetector)
        available = false; lastRaw = null; lastRawCapture = false
        generation++; resetOperational(); discardCandidate()
        stage = null; message = ""; accepted = 0
        return result()
    }

    private fun advanceWizard(raw: Float, now: Long, live: Boolean) {
        val stageAge = now - stageStarted
        when (stage) {
            Stage.CLEAR -> {
                if (stageAge < COUNTDOWN_MS) return
                if (capture(clearSamples, raw, now, live)) transition(Stage.NEAR, now, "Approach normally and stand where you use the panel. Keep your hands down.")
            }
            Stage.NEAR -> {
                if (stageAge >= APPROACH_TIMEOUT_MS) {
                    presenceStatus = CapabilityStatus.NOT_DETECTED
                    transition(Stage.WAVE_BASELINE, now, "Your normal approach was not detected. Stay at your normal position while hand-wave support is tested.")
                    return
                }
                if (stageAge < COUNTDOWN_MS) return
                val clear = clearSamples.median() ?: return
                if (abs(raw - clear) <= max(separationFloor(raw, clear), clearSamples.spread() * 1.5f)) {
                    nearSamples.clear(); captureStarted = null; return
                }
                if (capture(nearSamples, raw, now, live)) {
                    val near = nearSamples.median() ?: return
                    val span = abs(near - clear)
                    if (!separated(clearSamples, nearSamples, span)) {
                        nearSamples.clear(); captureStarted = null
                        return
                    }
                    candidate = Calibration(mode = mode(clear, near), clearRaw = clear, nearRaw = near)
                    transition(Stage.RETURN_CLEAR, now, "Move away again. The panel will confirm that your presence clears.")
                }
            }
            Stage.RETURN_CLEAR -> {
                if (stageAge >= APPROACH_TIMEOUT_MS) {
                    presenceStatus = CapabilityStatus.NOT_DETECTED
                    candidate = null
                    transition(Stage.WAVE_BASELINE, now, "Your presence did not clear reliably. Hand-wave support will be tested separately.")
                    return
                }
                if (stageAge < COUNTDOWN_MS) return
                val model = candidate ?: return
                if (model.level(raw) <= model.clearExit * 100f) {
                    if (returnClearSince == null) returnClearSince = now
                    if (now - checkNotNull(returnClearSince) >= CAPTURE_HOLD_MS) {
                        presenceStatus = CapabilityStatus.VALIDATED
                        transition(Stage.WAVE_BASELINE, now, if (requestedWavePattern == WavePattern.DOUBLE)
                            "Stay clear. Prepare to wave twice in succession without walking toward the panel."
                        else "Return to your normal position and stand still. Next, only your hand will move.")
                    }
                } else returnClearSince = null
            }
            Stage.WAVE_BASELINE -> {
                if (stageAge >= WAVE_PHASE_TIMEOUT_MS) { reviewWithoutWave(now); return }
                if (stageAge < COUNTDOWN_MS) return
                val presence = candidate?.takeIf { presenceStatus == CapabilityStatus.VALIDATED }
                val atBaseline = when {
                    presence == null -> abs(raw - checkNotNull(clearSamples.median())) <= max(clearSamples.spread() * 1.5f, separationFloor(raw, checkNotNull(clearSamples.median())))
                    requestedWavePattern == WavePattern.DOUBLE -> presence.level(raw) <= presence.clearExit * 100f
                    else -> presence.level(raw) >= presence.nearEnter * 100f
                }
                if (!atBaseline) { waveBaselineSamples.clear(); captureStarted = null; return }
                if (capture(waveBaselineSamples, raw, now, live)) transition(Stage.WAVE_CAPTURE, now,
                    if (requestedWavePattern == WavePattern.DOUBLE) "Wave your hand toward the sensor and withdraw it. Keep your body still."
                    else "Bring one hand toward the screen as if about to tap it. A touch afterwards is fine. Move your hand away to finish this measurement.")
            }
            Stage.WAVE_CAPTURE -> {
                if (stageAge >= WAVE_PHASE_TIMEOUT_MS) { reviewWithoutWave(now); return }
                if (stageAge < COUNTDOWN_MS || !live) return
                val baseline = waveBaselineSamples.median() ?: return
                val delta = raw - baseline
                if (!delta.isFinite()) { loseSource(now, "Invalid sensor range. Your previous calibration is unchanged."); return }
                val departure = max(separationFloor(raw, baseline), max(waveBaselineSamples.spread(), nearSamples.spread()) * 1.5f)
                val expected = candidate?.let { it.nearRaw - it.clearRaw }
                val beyond = abs(delta) > departure && (expected == null || (delta > 0f) == (expected > 0f))
                if (beyond) {
                    if (waveStarted == null) { waveStarted = now; wavePeak = raw }
                    if (abs(delta) > abs(checkNotNull(wavePeak) - baseline)) wavePeak = raw
                    if (now - checkNotNull(waveStarted) > MAXIMUM_NEAR_MS) { waveStarted = null; wavePeak = null }
                } else if (abs(delta) <= departure && waveStarted != null) {
                    val duration = now - checkNotNull(waveStarted)
                    val peak = checkNotNull(wavePeak)
                    waveStarted = null; wavePeak = null
                    if (duration !in MINIMUM_NEAR_MS..MAXIMUM_NEAR_MS) return
                    val wave = WaveCalibration(pattern = requestedWavePattern, clearRaw = baseline, nearRaw = peak)
                    candidateWave = wave
                    candidateWaveMode = mode(wave.clearRaw, wave.nearRaw)
                    candidateDetector = Detector(wave.model(checkNotNull(candidateWaveMode))).also { it.observe(raw, now, false) }
                    pendingCandidatePulse = null
                    transition(Stage.WAVES, now, if (wave.pattern == WavePattern.DOUBLE)
                        "Make three deliberate double waves. Pause between each pair."
                    else "Bring your hand toward the screen once for each check. A touch afterwards is fine. Move away between checks.")
                }
            }
            Stage.WAVES -> {
                if (stageAge >= WAVE_PHASE_TIMEOUT_MS) { reviewWithoutWave(now); return }
                if (stageAge < COUNTDOWN_MS) {
                    candidateDetector?.observe(raw, now, false)
                    return
                }
                val wave = candidateWave ?: return
                val pulse = if (live) candidateDetector?.observe(raw, now, true) == true else candidateDetector?.tick(now) == true
                if (pendingCandidatePulse?.let { now - it > wave.maxInterWaveGapMs } == true) pendingCandidatePulse = null
                val complete = if (wave.pattern == WavePattern.SINGLE) pulse else if (pulse) {
                    if (pendingCandidatePulse == null) { pendingCandidatePulse = now; false }
                    else { pendingCandidatePulse = null; true }
                } else false
                if (complete) {
                    accepted++
                    if (accepted >= REQUIRED_WAVES) {
                        val presence = candidate?.takeIf { presenceStatus == CapabilityStatus.VALIDATED }
                        candidate = presence?.copy(wave = wave) ?: Calibration(
                            mode = checkNotNull(candidateWaveMode), clearRaw = wave.clearRaw, nearRaw = wave.nearRaw,
                            presenceSupported = false, wave = wave,
                        )
                        waveStatus = CapabilityStatus.VALIDATED
                        transition(Stage.REVIEW, now, "Measurements complete. Review presence and wave support, then Save on the panel.")
                    }
                }
            }
            else -> Unit
        }
    }

    private fun capture(samples: Samples, raw: Float, now: Long, live: Boolean): Boolean {
        if (captureStarted == null) captureStarted = now
        if (live || samples.size == 0) samples.add(raw)
        return now - checkNotNull(captureStarted) >= CAPTURE_HOLD_MS
    }
    private fun separated(clear: Samples, near: Samples, span: Float): Boolean = span.isFinite() && span > separationFloor(clear.median()!!, near.median()!!) &&
        clear.spread() <= span * .5f && near.spread() <= span * .5f && clear.spread() + near.spread() <= span * .75f
    private fun mode(clear: Float, near: Float) = if (!observedNonBinary && isBinaryPair(clear, near)) Mode.BINARY else Mode.RANGED
    private fun reviewWithoutWave(now: Long) {
        candidateWave = null; candidateWaveMode = null; candidateDetector = null; pendingCandidatePulse = null
        waveStatus = CapabilityStatus.NOT_DISTINGUISHABLE
        candidate = candidate?.takeIf { presenceStatus == CapabilityStatus.VALIDATED }?.copy(wave = null)
        transition(Stage.REVIEW, now, if (candidate != null) "Presence was verified. A separate deliberate hand wave was not verified; only presence can be saved."
            else "Neither normal approach nor deliberate hand waves were verified. Your previous calibration is unchanged.")
    }
    private fun operationalGesture(pulse: Boolean, now: Long): Boolean {
        val wave = calibration?.effectiveWave() ?: return false
        if (pendingOperationalPulse?.let { now - it > wave.maxInterWaveGapMs } == true) pendingOperationalPulse = null
        if (!pulse || now < operationalCooldownUntil) return false
        val complete = if (wave.pattern == WavePattern.SINGLE) true else if (pendingOperationalPulse == null) {
            pendingOperationalPulse = now; false
        } else { pendingOperationalPulse = null; true }
        if (complete) operationalCooldownUntil = now + wave.cooldownMs
        return complete
    }
    private fun transition(next: Stage, now: Long, text: String) {
        stage = next; stageStarted = now; captureStarted = null; returnClearSince = null; message = text
    }
    private fun resetOperational() {
        detector?.reset(); waveDetector?.reset(); pendingOperationalPulse = null; operationalCooldownUntil = 0
    }
    private fun finish(next: Stage, now: Long, text: String) {
        generation++; resetOperational(); discardCandidate(); transition(next, now, text)
    }
    private fun discardCandidate() {
        candidate = null; candidateWave = null; candidateWaveMode = null; candidateDetector = null; pendingCandidatePulse = null
        clearSamples.clear(); nearSamples.clear(); waveBaselineSamples.clear()
        captureStarted = null; returnClearSince = null; waveStarted = null; wavePeak = null
        observedNonBinary = observedSourceMode == Mode.RANGED; presenceStatus = CapabilityStatus.NOT_TESTED; waveStatus = CapabilityStatus.NOT_TESTED
    }
    private fun expire(now: Long) {
        if (active() && now - sessionStarted >= SESSION_TIMEOUT_MS) finish(Stage.TIMED_OUT, now, "Setup timed out. Your previous calibration is unchanged.")
    }
    private fun loseSource(now: Long, text: String): Result {
        available = false; lastRaw = null; lastRawCapture = false
        if (active()) finish(Stage.FAILED, now, text) else { generation++; resetOperational(); message = text }
        return result()
    }
    private fun admit(now: Long): Boolean {
        if (now < 0 || lastTime?.let { now < it } == true) {
            loseSource(lastTime ?: 0L, "Sensor timing was invalid. Your previous calibration is unchanged."); return false
        }
        lastTime = now; return true
    }
    private fun active() = when (stage) {
        Stage.INTRO, Stage.CLEAR, Stage.NEAR, Stage.RETURN_CLEAR, Stage.WAVE_BASELINE, Stage.WAVE_CAPTURE, Stage.WAVES, Stage.REVIEW -> true
        else -> false
    }
    private fun result(gesture: Boolean = false, approach: Boolean = false): Result {
        val active = active()
        val model = if (active) candidate else calibration
        val presenceSupported = if (active) presenceStatus == CapabilityStatus.VALIDATED else model?.presenceSupported == true
        val wave = if (active) model?.wave?.takeIf { waveStatus == CapabilityStatus.VALIDATED } else model?.effectiveWave()
        val near = if (available && !active && presenceSupported) detector?.near else null
        val timing = cueTiming()
        return Result(stage, message, accepted, near,
            if (near != null) lastRaw?.let { model?.level(it) } else null, model?.mode, available,
            available && !active && wave != null && waveDetector?.hasClearEvidence == true,
            active, gesture && !active, generation, calibration, needsTick(), approach && !active,
            available && presenceSupported && (active || near != null), presenceSupported,
            if (active) presenceStatus else if (presenceSupported) CapabilityStatus.VALIDATED else CapabilityStatus.NOT_DETECTED,
            wave != null, if (active) waveStatus else if (wave != null) CapabilityStatus.VALIDATED else CapabilityStatus.NOT_DISTINGUISHABLE,
            cue(), timing.first, timing.second, stage == Stage.REVIEW && candidate != null && available,
            wave?.pattern ?: candidateWave?.pattern ?: requestedWavePattern.takeIf { active },
        )
    }
    private fun cue(): Cue {
        val preparation = (lastTime ?: stageStarted) - stageStarted < COUNTDOWN_MS
        return when (stage) {
            Stage.CLEAR -> if (preparation) Cue.MOVE_AWAY else Cue.HOLD
            Stage.NEAR -> if (captureStarted == null) Cue.APPROACH else Cue.HOLD
            Stage.RETURN_CLEAR -> if (preparation) Cue.MOVE_AWAY else Cue.WAIT_CLEAR
            Stage.WAVE_BASELINE -> if (captureStarted != null) Cue.HOLD else if (requestedWavePattern == WavePattern.DOUBLE) Cue.MOVE_AWAY else Cue.APPROACH
            Stage.WAVE_CAPTURE -> when {
                preparation -> Cue.PREPARE
                waveStarted?.let { (lastTime ?: stageStarted) - it >= MINIMUM_NEAR_MS } == true -> Cue.MOVE_AWAY
                else -> Cue.WAVE
            }
            Stage.WAVES -> when {
                preparation -> Cue.PREPARE
                candidateDetector?.near == true -> Cue.MOVE_AWAY
                else -> Cue.WAVE
            }
            else -> Cue.PREPARE
        }
    }
    private fun cueTiming(): Pair<Long, Long> {
        if (!active() || stage == Stage.INTRO || stage == Stage.REVIEW) return 0L to 0L
        val now = lastTime ?: stageStarted
        if (now - stageStarted < COUNTDOWN_MS) return (COUNTDOWN_MS - (now - stageStarted)) to COUNTDOWN_MS
        if (captureStarted != null && stage in listOf(Stage.CLEAR, Stage.NEAR, Stage.WAVE_BASELINE)) {
            return (CAPTURE_HOLD_MS - (now - checkNotNull(captureStarted))).coerceAtLeast(0) to CAPTURE_HOLD_MS
        }
        if (returnClearSince != null) return (CAPTURE_HOLD_MS - (now - checkNotNull(returnClearSince))).coerceAtLeast(0) to CAPTURE_HOLD_MS
        return 0L to 0L
    }
    private fun createWaveDetector(value: Calibration): Detector? = value.effectiveWave()?.let {
        Detector(it.model(value.mode), triggerOnApproach = it.pattern == WavePattern.SINGLE)
    }

    /** Hysteretic reporting; single hand approaches fire on entry, explicit double waves on release. */
    private class Detector(private val calibration: Calibration, private val triggerOnApproach: Boolean = false) {
        var presenceApproach = false
            private set
        private var pendingApproach = false
        var near: Boolean? = null
            private set
        var hasClearEvidence = false
            private set
        private var rawNear: Boolean? = null
        private var pendingSince = 0L
        private var clearSince: Long? = null
        private var nearSince: Long? = null
        private var eligible = false
        private var cooldownUntil = 0L
        private var pendingGesture = false

        fun reset() {
            near = null
            presenceApproach = false
            pendingApproach = false
            rawNear = null
            hasClearEvidence = false
            clearSince = null
            nearSince = null
            eligible = false
            cooldownUntil = 0L
            pendingGesture = false
        }

        fun observe(raw: Float, now: Long, live: Boolean): Boolean {
            presenceApproach = false
            val level = calibration.level(raw)
            val next = when {
                level >= calibration.nearEnter * 100f -> true
                level <= calibration.clearExit * 100f -> false
                else -> rawNear
            }
            if (next == null) return false
            if (!live) {
                eligible = false
                nearSince = null
                pendingGesture = false
                pendingApproach = false
            }
            if (next != rawNear) {
                pendingSince = now
                pendingGesture = false
                if (next) {
                    pendingApproach = live && rawNear == false && clearSince?.let { now - it >= calibration.debounceMs } == true
                    eligible = live && clearSince?.let { now - it >= calibration.clearArmMs } == true && now >= cooldownUntil
                    nearSince = if (live) now else null
                    clearSince = null
                } else {
                    pendingApproach = false
                    val duration = nearSince?.let { now - it }
                    pendingGesture = !triggerOnApproach && live && eligible && duration != null &&
                        duration >= max(calibration.minimumNearMs, calibration.debounceMs) && duration <= calibration.maximumNearMs
                    eligible = false
                    nearSince = null
                    clearSince = now
                }
                rawNear = next
            }
            return tick(now)
        }

        fun releaseCooldown() { cooldownUntil = 0L }

        fun needsTick(): Boolean = pendingGesture || rawNear != near || nearSince != null

        fun tick(now: Long): Boolean {
            var gesture = false
            presenceApproach = false
            if (rawNear != null && now - pendingSince >= calibration.debounceMs) {
                if (rawNear == true && pendingApproach) {
                    presenceApproach = near == false
                    pendingApproach = false
                }
                near = rawNear
                if (triggerOnApproach && near == true && eligible &&
                    nearSince?.let { now - it in max(calibration.minimumNearMs, calibration.debounceMs)..calibration.maximumNearMs } == true) {
                    // The inward movement is the gesture. Holding or subsequently touching does not
                    // revoke it; only a fresh clear interval can arm another entry.
                    eligible = false
                    nearSince = null
                    cooldownUntil = now + calibration.cooldownMs
                    gesture = true
                }
                if (near == false) {
                    hasClearEvidence = true
                    if (pendingGesture) {
                        pendingGesture = false
                        cooldownUntil = now + calibration.cooldownMs
                        gesture = true
                    }
                }
            }
            if (nearSince?.let { now - it > calibration.maximumNearMs } == true) {
                nearSince = null
                eligible = false
            }
            return gesture
        }
    }

    private class Samples {
        private val values = FloatArray(32)
        var size = 0
            private set
        private var next = 0
        fun add(raw: Float) { values[next] = raw; next = (next + 1) % values.size; size = (size + 1).coerceAtMost(values.size) }
        fun clear() { size = 0; next = 0 }
        fun median(): Float? = if (size == 0) null else values.copyOf(size).apply { sort() }[size / 2]
        fun spread(): Float {
            if (size == 0) return 0f
            val sorted = values.copyOf(size).apply { sort() }
            return if (size < 10) sorted[size - 1] - sorted[0]
            else sorted[(size - 1) * 9 / 10] - sorted[(size - 1) / 10]
        }
    }
    companion object {
        const val SESSION_TIMEOUT_MS = 300_000L
        // Leave enough time to read or hear each instruction before observation begins.
        const val COUNTDOWN_MS = 8_000L
        const val CAPTURE_HOLD_MS = 5_000L
        const val APPROACH_TIMEOUT_MS = 30_000L
        const val WAVE_PHASE_TIMEOUT_MS = 45_000L
        const val REQUIRED_WAVES = 3
        private const val MINIMUM_NEAR_MS = 200L
        private const val MAXIMUM_NEAR_MS = 4_000L
        private fun isBinaryPair(clear: Float, near: Float) = (clear == 0f && near == 1f) || (clear == 1f && near == 0f)
        private fun separationFloor(a: Float, b: Float) = max(Math.ulp(a), Math.ulp(b)) * 4f
    }
}
