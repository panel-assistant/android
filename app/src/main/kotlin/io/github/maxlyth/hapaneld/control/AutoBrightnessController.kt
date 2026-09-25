package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.stableOwner
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.sensors.SensorTrace
import io.github.maxlyth.hapaneld.sensors.HaAmbientHistorySeed
import java.security.MessageDigest
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ln1p

internal enum class AmbientLuxSourceKind { PANEL, HOME_ASSISTANT }

internal data class AutoBrightnessRuntimeStatus(
    val enabled: Boolean,
    val sourceKind: AmbientLuxSourceKind,
    val sourceId: String,
    val sourceAvailable: Boolean,
    val latestLux: Double?,
    val expectedLux: Double?,
    val automaticTarget: Int?,
    val appliedTarget: Int?,
    val mode: AdaptiveModelMode?,
    val brighterThanExpected: Boolean,
    val manualPreference: ManualBrightnessPreferenceSnapshot,
    val sourceRevision: String,
    val ambientTheme: AmbientThemeSnapshot,
)

internal data class AutoBrightnessChartSnapshot(
    val sourceRevision: String,
    val points: List<AdaptiveChartPoint>,
)

/** Service-owned adaptive brightness loop. Sensor and HA callbacks only replace the latest level. */
internal class AutoBrightnessController(
    context: Context,
    private val brightness: BrightnessController,
    private val config: Config,
    private val actuationGate: ((() -> Unit) -> Boolean) = { action -> action(); true },
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val history: AmbientHistoryRuntime = AmbientHistoryRuntime(context),
    private val preference: ManualBrightnessAuthority = ManualBrightnessAuthority(
        AndroidManualBrightnessPreferenceStore(context),
        wallClockMs = wallClockMs,
        elapsedRealtimeMs = elapsedRealtimeMs,
        bootCount = { AndroidManualBrightnessPreferenceStore.bootCount(context) },
    ),
    /**
     * Called, off this controller's lock, after the Ambient theme verdict changes and has been
     * persisted. The receiver decides whether the renderer has to follow it; this controller only
     * owns the verdict.
     */
    private val onAmbientThemeChanged: () -> Unit = {},
) : AutoCloseable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ha-paneld-auto-brightness").apply { isDaemon = true }
    }
    private var persistenceFuture: ScheduledFuture<*>? = null
    private var evaluationFuture: ScheduledFuture<*>? = null
    private var evaluationDeadlineElapsed = Long.MAX_VALUE
    private var forceNextEvaluation = false
    private var policy = AdaptiveBrightnessPolicy()
    private val baselineCache = AdaptiveBaselineCache()
    private var latestPanelLux = Double.NaN
    private var latestHaLux = Double.NaN
    private var latestPanelChangeElapsed = Long.MIN_VALUE
    private var latestHaChangeElapsed = Long.MIN_VALUE
    private var haAvailable = false
    private var enabledLastTick = false
    private var lastTickElapsed = Long.MIN_VALUE
    private var lastWallMs = Long.MIN_VALUE
    private var lastAutomaticTarget = -1
    private var lastAppliedTarget = -1
    private var lastResult: AdaptiveBrightnessResult? = null
    private var lastEvaluatedLux = Double.NaN
    private var location: SolarLocation? = null
    private var zone: TimeZone = TimeZone.getDefault()
    private var locationContext = contextKey(null, zone.id)
    private var activeSourceKind = AmbientLuxSourceKind.PANEL
    private var activeSourceKey = "panel"
    private var cachedHaEntity = ""
    private var cachedHaUrl = ""
    private var chartRowsIdentity: List<AmbientHistoryMinute>? = null
    private var chartZoneId = ""
    private var chartLocation: SolarLocation? = null
    private var chartLookup: AdaptiveChartBaselineLookup? = null
    // Seeded from the persisted verdict, so a restart keeps the room's last answer instead of
    // falling back to Home Assistant for a dwell and then rebuilding the dashboard to get it back.
    private val ambientTheme = AmbientThemeDecider(config.dashboardAmbientDark)
    private var lastAmbientLevel = Double.NaN
    @Volatile private var closed = false

    /** Activate only after the service-generation lease has admitted active owners. */
    @Synchronized fun activate() {
        if (closed || !config.autoBrightness) return
        refreshSourceIdentity()
        configureHistorySource()
        requestEvaluationLocked(0L)
    }

    /** Local SensorManager/helper input. Ignored while an HA entity is explicitly selected. */
    @Synchronized fun submitLux(lux: Float) = submitPanelLux(lux.toDouble())

    @Synchronized fun submitPanelLux(lux: Double) {
        if (lux.isFinite() && lux in 0.0..MAX_LUX) {
            latestPanelLux = lux
            refreshSourceIdentity()
            if (config.autoBrightness && activeSourceKind == AmbientLuxSourceKind.PANEL && meaningfulChange(lux)) {
                latestPanelChangeElapsed = elapsedRealtimeMs()
                requestEvaluationLocked(0L)
            }
        }
    }

    /**
     * Native selected-entity lux input. The subscriber is the single availability authority and always
     * reports the source LIVE before delivering a sample, so this only records the value; availability
     * is owned by [setHaSourceAvailable].
     */
    @Synchronized fun submitHaLux(lux: Double) {
        if (!config.autoBrightness) return
        if (lux.isFinite() && lux in 0.0..MAX_LUX) {
            latestHaLux = lux
            refreshSourceIdentity()
            if (config.autoBrightness && activeSourceKind == AmbientLuxSourceKind.HOME_ASSISTANT && meaningfulChange(lux)) {
                latestHaChangeElapsed = elapsedRealtimeMs()
                requestEvaluationLocked(0L)
            }
        }
    }

    @Synchronized fun setHaSourceAvailable(available: Boolean) {
        haAvailable = available
        if (available && config.autoBrightness) requestEvaluationLocked(0L)
    }

    /** Rounded HA site metadata; a material context change starts a separate learned partition. */
    @Synchronized
    fun updateSite(latitude: Double?, longitude: Double?, timeZoneId: String?) {
        if (closed) return
        val nextZone = timeZoneId?.takeIf { it in TimeZone.getAvailableIDs().toSet() }
            ?.let(TimeZone::getTimeZone) ?: TimeZone.getDefault()
        val nextLocation = if (latitude != null && longitude != null &&
            latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
        ) SolarLocation(roundCoordinate(latitude), roundCoordinate(longitude)) else null
        val nextContext = contextKey(nextLocation, nextZone.id)
        zone = nextZone
        location = nextLocation
        if (nextContext != locationContext) {
            locationContext = nextContext
            resetTransientPolicy(clearPreference = true)
            baselineCache.invalidate()
            if (config.autoBrightness) {
                configureHistorySource()
                requestEvaluationLocked(0L, force = true)
            }
        }
    }

    /** Capture explicit local/HA/system intent before a wake callback can reapply automatic brightness. */
    @Synchronized
    internal fun noteExternalBrightness(
        level: Int,
        origin: BrightnessPreferenceOrigin,
        priorAppliedLevel: Int? = null,
    ): Boolean {
        if (!config.autoBrightness) return false
        val automatic = lastAutomaticTarget.takeIf { it >= BrightnessController.MIN_VISIBLE }
            ?: brightness.getCommanded().takeIf { it >= BrightnessController.MIN_VISIBLE }
            ?: level.coerceIn(BrightnessController.MIN_VISIBLE, 255)
        val applied = priorAppliedBrightness(
            origin = origin,
            commandedLevel = brightness.getCommanded().takeIf { it >= BrightnessController.MIN_VISIBLE },
            lastAutomaticApplied = priorAppliedLevel?.takeIf { it >= BrightnessController.MIN_VISIBLE }
                ?: lastAppliedTarget.takeIf { it >= BrightnessController.MIN_VISIBLE },
            automaticTarget = automatic,
        )
        val captured = preference.capture(
            requestedLevel = level,
            automaticTarget = automatic,
            currentApplied = applied,
            origin = origin,
            modelContextKey = locationContext,
            ambientSourceKey = activeSourceId(),
            persist = false,
        )
        if (captured) {
            persistenceFuture?.cancel(false)
            persistenceFuture = scheduler.schedule(preference::persistCurrent, COMMAND_SETTLE_MS, TimeUnit.MILLISECONDS)
            lastAppliedTarget = level.coerceIn(BrightnessController.MIN_VISIBLE, 255)
            Log.i(TAG, "temporary brightness preference started (${origin.name.lowercase()})")
        }
        return captured
    }

    @Synchronized fun resumeFullAuto() {
        preference.clear()
        requestEvaluationLocked(0L, force = true)
    }

    @Synchronized fun resetHistory() {
        history.reset()
        baselineCache.invalidate()
        resetTransientPolicy(clearPreference = true)
        requestEvaluationLocked(0L, force = true)
    }

    /** Admit only a seed fetched for the still-current source and credential owner. */
    @Synchronized internal fun seedHaHistory(seed: HaAmbientHistorySeed): Boolean {
        if (closed || !config.autoBrightness || seed.minutes.isEmpty()) return false
        val entity = config.autoBrightnessHaEntity.trim()
        val url = config.haUrl.trim().trimEnd('/')
        if (entity != seed.entityId || url != seed.baseUrl || config.haAuthSnapshot().stableOwner() != seed.authOwner) return false
        refreshSourceIdentity()
        if (activeSourceKind != AmbientLuxSourceKind.HOME_ASSISTANT) return false
        configureHistorySource()
        val contextSnapshot = locationContext
        val sourceSnapshot = activeSourceId()
        val rows = seed.minutes.map { minute ->
            AmbientMinuteAggregate(
                key = AmbientHistoryKey(contextSnapshot, sourceSnapshot, minute.minute),
                luxIntegral = minute.luxIntegral,
                coverageMs = minute.coverageMs,
                minLux = minute.minLux,
                maxLux = minute.maxLux,
                lastLux = minute.lastLux,
                sampleCount = minute.sampleCount,
                baselineLogIntegral = minute.baselineLogIntegral,
                baselineCoverageMs = minute.baselineCoverageMs,
            )
        }
        history.seed(rows, contextSnapshot, sourceSnapshot) {
            synchronized(this) {
                if (!closed && locationContext == contextSnapshot && activeSourceId() == sourceSnapshot) {
                    baselineCache.invalidate()
                    chartLookup = null
                    chartRowsIdentity = null
                    requestEvaluationLocked(0L, force = true)
                }
            }
        }
        return true
    }

    /** Reconcile through current preference authority after physical wake or a policy change. */
    @Synchronized fun reapplyLatest() {
        // Rebind the retained partition even while automatic actuation is disabled. This is a
        // one-shot source/config transition, not background collection, and prevents a Configure
        // read from pairing the newly selected source with the previous source's cached rows.
        reconcileHistorySource()
        if (!config.autoBrightness) {
            evaluationFuture?.cancel(false)
            evaluationFuture = null
            evaluationDeadlineElapsed = Long.MAX_VALUE
            forceNextEvaluation = false
            if (enabledLastTick) resetTransientPolicy(clearPreference = true)
            enabledLastTick = false
            return
        }
        requestEvaluationLocked(0L, force = true)
    }

    @Synchronized fun status(): AutoBrightnessRuntimeStatus {
        reconcileHistorySource()
        val automatic = lastAutomaticTarget.takeIf { it >= 0 }
        val manual = preference.snapshot(
            automaticTarget = automatic ?: BrightnessController.MIN_VISIBLE,
            modelContextKey = locationContext,
            ambientSourceKey = activeSourceKey,
        )
        return AutoBrightnessRuntimeStatus(
            enabled = config.autoBrightness,
            sourceKind = activeSourceKind,
            sourceId = activeSourceKey,
            sourceAvailable = activeSourceAvailable(),
            latestLux = activeLux().takeIf(Double::isFinite),
            expectedLux = lastResult?.expectedLux,
            automaticTarget = automatic,
            appliedTarget = lastAppliedTarget.takeIf { it >= 0 },
            mode = lastResult?.mode,
            brighterThanExpected = lastResult?.brighterThanExpected == true,
            manualPreference = manual,
            sourceRevision = activeSourceRevision(),
            ambientTheme = AmbientThemeSnapshot(
                verdictDark = ambientTheme.dark,
                level = lastAmbientLevel.takeIf(Double::isFinite),
                pendingDark = ambientTheme.pendingDark(),
                sourceAvailable = activeSourceAvailable(),
            ),
        )
    }

    internal fun historyRows(): List<AmbientHistoryMinute> = history.history()
    @Synchronized internal fun chartPoints(
        sensitivity: Int = config.autoBrightnessResponsePercent,
        minimumPercent: Int = config.autoBrightnessMinimumPercent,
    ): List<AdaptiveChartPoint> = chartSnapshot(sensitivity, minimumPercent).points

    @Synchronized internal fun chartSnapshot(
        sensitivity: Int = config.autoBrightnessResponsePercent,
        minimumPercent: Int = config.autoBrightnessMinimumPercent,
    ): AutoBrightnessChartSnapshot {
        reconcileHistorySource()
        val rows = history.history()
        val fallback = rows.lastOrNull()?.meanLux?.let(::ln1p) ?: 0.0
        if (chartLookup == null || rows !== chartRowsIdentity || chartZoneId != zone.id || chartLocation != location) {
            chartLookup = AdaptiveChartBaselineLookup.compile(rows, zone, location, fallback)
            chartRowsIdentity = rows
            chartZoneId = zone.id
            chartLocation = location
        }
        val lookup = checkNotNull(chartLookup)
        return AutoBrightnessChartSnapshot(
            sourceRevision = activeSourceRevision(),
            points = AdaptiveChartProjection.fiveMinute(
                rows = rows,
                sensitivity = sensitivity,
                brightnessRange = lookup.brightnessRange,
                minimumBrightness = AdaptiveLuxCurve.percentToBrightness(minimumPercent),
                expectedLogLux = lookup::expectedLogLux,
            ),
        )
    }
    internal fun solarLocation(): SolarLocation? = location
    internal fun timeZone(): TimeZone = zone

    private fun tickSafely() {
        val started = FeatureCosts.registry.beginSynchronous(FeatureCostOperation.AUTO_BRIGHTNESS_APPLY)
        var outcome = FeatureCostOutcome.SUCCESS
        try {
            synchronized(this) {
                evaluationFuture = null
                evaluationDeadlineElapsed = Long.MAX_VALUE
                val force = forceNextEvaluation
                forceNextEvaluation = false
                tick(force)?.let(::requestEvaluationLocked)
            }
        } catch (error: Throwable) {
            outcome = FeatureCostOutcome.FAILURE
            Log.w(TAG, "adaptive brightness tick failed", error)
            synchronized(this) {
                if (!closed) requestEvaluationLocked(CALM_EVALUATION_MS)
            }
        } finally {
            FeatureCosts.registry.finishSynchronous(
                FeatureCostOperation.AUTO_BRIGHTNESS_APPLY,
                started,
                outcome = outcome,
                workUnits = 1,
            )
        }
    }

    private fun tick(force: Boolean): Long? {
        if (closed) return null
        val enabled = config.autoBrightness
        if (!enabled) {
            if (enabledLastTick) resetTransientPolicy(clearPreference = true)
            enabledLastTick = false
            return null
        }
        if (!enabledLastTick) {
            enabledLastTick = true
            lastTickElapsed = Long.MIN_VALUE
            configureHistorySource()
        }
        val nowElapsed = elapsedRealtimeMs()
        val nowWall = wallClockMs()
        val elapsed = when {
            lastTickElapsed == Long.MIN_VALUE -> FIRST_EVALUATION_MS
            nowElapsed < lastTickElapsed -> FIRST_EVALUATION_MS
            else -> (nowElapsed - lastTickElapsed).coerceIn(1L, MAX_TICK_GAP_MS)
        }
        if (!force && lastTickElapsed != Long.MIN_VALUE && nowElapsed - lastTickElapsed < MIN_EVALUATION_INTERVAL_MS) {
            return MIN_EVALUATION_INTERVAL_MS - (nowElapsed - lastTickElapsed)
        }
        lastTickElapsed = nowElapsed
        reconcileHistorySource()
        val available = activeSourceAvailable()
        val lux = activeLux()
        if (!available || !lux.isFinite()) return null
        val rows = history.history()
        val estimate = baselineCache.estimate(nowWall, rows, zone, location, ln1p(lux))
        val changedAt = if (activeSourceKind == AmbientLuxSourceKind.HOME_ASSISTANT) {
            latestHaChangeElapsed
        } else latestPanelChangeElapsed
        val conditionElapsed = if (changedAt == Long.MIN_VALUE || nowElapsed < changedAt) elapsed
            else minOf(elapsed, nowElapsed - changedAt)
        val result = policy.evaluate(
            nowMs = nowWall,
            elapsedMs = elapsed,
            lux = lux,
            baseline = estimate,
            sensitivity = config.autoBrightnessResponsePercent,
            conditionElapsedMs = conditionElapsed,
            minimumBrightness = AdaptiveLuxCurve.percentToBrightness(config.autoBrightnessMinimumPercent),
        ) ?: return CALM_EVALUATION_MS
        lastResult = result
        lastEvaluatedLux = lux
        lastAutomaticTarget = result.brightness
        // The theme reads the model's own output on the room's own scale: the effective lux the
        // backlight follows, through the same learned range, before the backlight's minimum floor.
        lastAmbientLevel = AdaptiveLuxCurve.normalizedLevel(result.effectiveLux, result.estimate.brightnessRange)
        if (ambientTheme.observe(nowElapsed, lastAmbientLevel)) publishAmbientVerdict()
        val manual = preference.evaluate(result.brightness, locationContext, activeSourceKey)
        val finalTarget = manual.finalTarget ?: result.brightness
        val lastBacklightWriteElapsed = brightness.lastSuccessfulWriteElapsed()
        val baselineEligible = result.baselineEligible &&
            (lastBacklightWriteElapsed == Long.MIN_VALUE || nowElapsed - lastBacklightWriteElapsed >= BACKLIGHT_QUARANTINE_MS)
        if (lastWallMs == Long.MIN_VALUE || nowWall >= lastWallMs) {
            history.record(
                epochMs = (nowWall - elapsed).coerceAtLeast(0L),
                lux = lux,
                durationMs = elapsed,
                baselineEligible = baselineEligible,
            )
        }
        lastWallMs = nowWall
        val shouldWrite = force || lastAppliedTarget < 0 || abs(finalTarget - lastAppliedTarget) >= MOVEMENT_DEADBAND
        var applied: Int? = null
        if (shouldWrite && brightness.canWrite()) {
            val admitted = actuationGate {
                brightness.setBrightness(finalTarget)
                applied = finalTarget
            }
            if (admitted && applied != null) lastAppliedTarget = finalTarget
        }
        SensorTrace.recordLux(
            lux.toFloat(),
            result.effectiveLux.toFloat(),
            result.brightness,
            lastAppliedTarget.takeIf { it >= 0 },
        )
        val next = if (policy.needsFastFollowUp()) FAST_EVALUATION_MS else CALM_EVALUATION_MS
        // A pending theme change is judged when its dwell ends, not at the next calm tick: a room that
        // went dark and then stayed perfectly still would otherwise wait up to a further minute.
        val dwellRemaining = ambientTheme.pendingDeadlineMs()?.let { (it - nowElapsed).coerceAtLeast(0L) }
        return if (dwellRemaining != null) minOf(next, dwellRemaining) else next
    }

    private fun publishAmbientVerdict() {
        val dark = ambientTheme.dark ?: return
        config.setDashboardAmbientDark(dark)
        Log.i(TAG, "ambient theme verdict: ${if (dark) "dark" else "light"} (level ${String.format(java.util.Locale.ROOT, "%.2f", lastAmbientLevel)})")
        scheduler.execute { if (!closed) onAmbientThemeChanged() }
    }

    private fun resetTransientPolicy(clearPreference: Boolean) {
        policy = AdaptiveBrightnessPolicy()
        // A new evaluation session restarts any wait; the verdict itself stands until the room changes.
        ambientTheme.restartDwell()
        lastAmbientLevel = Double.NaN
        lastResult = null
        lastAutomaticTarget = -1
        lastTickElapsed = Long.MIN_VALUE
        lastWallMs = Long.MIN_VALUE
        lastEvaluatedLux = Double.NaN
        chartLookup = null
        chartRowsIdentity = null
        if (clearPreference) preference.clear()
    }

    private fun configureHistorySource() = history.configure(locationContext, activeSourceId())

    /** Replace the cache identity on the caller's synchronized path, before any status/history read
     * or selection response can expose the new source alongside the previous partition's rows. */
    private fun reconcileHistorySource(): Boolean {
        val changed = refreshSourceIdentity() || activeSourceKey != history.currentSourceId() ||
            locationContext != history.currentContextId()
        if (changed) {
            resetTransientPolicy(clearPreference = true)
            baselineCache.invalidate()
            configureHistorySource()
        }
        return changed
    }

    /** Re-hash only when the configured HA identity changes, never on the evaluation path. */
    private fun refreshSourceIdentity(): Boolean {
        val entity = config.autoBrightnessHaEntity.trim()
        val url = if (entity.isBlank()) "" else config.haUrl.trim()
        if (entity == cachedHaEntity && url == cachedHaUrl) return false
        cachedHaEntity = entity
        cachedHaUrl = url
        if (entity.isBlank()) {
            activeSourceKind = AmbientLuxSourceKind.PANEL
            activeSourceKey = "panel"
        } else {
            activeSourceKind = AmbientLuxSourceKind.HOME_ASSISTANT
            activeSourceKey = "ha:${hash("$url|$entity")}"
        }
        return true
    }

    private fun activeSourceId(): String = activeSourceKey
    private fun activeSourceRevision(): String = hash("$locationContext|$activeSourceKey")
    private fun activeLux(): Double =
        if (activeSourceKind == AmbientLuxSourceKind.HOME_ASSISTANT) latestHaLux else latestPanelLux
    private fun activeSourceAvailable(): Boolean =
        if (activeSourceKind == AmbientLuxSourceKind.HOME_ASSISTANT) haAvailable else latestPanelLux.isFinite()

    private fun meaningfulChange(lux: Double): Boolean = !lastEvaluatedLux.isFinite() ||
        abs(ln1p(lux) - ln1p(lastEvaluatedLux)) >= MEANINGFUL_LOG_DELTA

    private fun requestEvaluationLocked(delayMs: Long, force: Boolean = false) {
        if (closed || !config.autoBrightness) return
        forceNextEvaluation = forceNextEvaluation || force
        val now = elapsedRealtimeMs()
        val earliest = if (lastTickElapsed == Long.MIN_VALUE) now else lastTickElapsed + MIN_EVALUATION_INTERVAL_MS
        val deadline = maxOf(now + delayMs.coerceAtLeast(0L), earliest)
        if (evaluationFuture?.isDone == false && evaluationDeadlineElapsed <= deadline) return
        evaluationFuture?.cancel(false)
        evaluationDeadlineElapsed = deadline
        evaluationFuture = scheduler.schedule(::tickSafely, (deadline - now).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    fun closeAndJoin(timeoutMs: Long = CLOSE_TIMEOUT_MS): Boolean {
        synchronized(this) {
            if (closed) return scheduler.isTerminated
            closed = true
            persistenceFuture?.cancel(false)
            evaluationFuture?.cancel(false)
            preference.persistCurrent()
            scheduler.shutdownNow()
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        val schedulerDrained = runCatching {
            scheduler.awaitTermination(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        val remainingMs = TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0L))
        return schedulerDrained && history.closeAndJoin(remainingMs)
    }

    override fun close() { closeAndJoin() }

    companion object {
        private const val TAG = "ha-paneld/autobright"
        private const val FIRST_EVALUATION_MS = 1_000L
        private const val MIN_EVALUATION_INTERVAL_MS = 250L
        private const val FAST_EVALUATION_MS = 1_000L
        private const val CALM_EVALUATION_MS = 60_000L
        private const val MAX_TICK_GAP_MS = CALM_EVALUATION_MS
        private const val BACKLIGHT_QUARANTINE_MS = 5_000L
        private const val COMMAND_SETTLE_MS = 2_000L
        private const val MOVEMENT_DEADBAND = 4
        private const val MAX_LUX = 100_000.0
        private const val CLOSE_TIMEOUT_MS = 3_000L
        private const val MEANINGFUL_LOG_DELTA = 0.05

        private fun roundCoordinate(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0
        private fun contextKey(location: SolarLocation?, zoneId: String): String = hash(
            location?.let { "solar:${it.latitude},${it.longitude};time:$zoneId" } ?: "time:$zoneId",
        )
        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
    }
}
