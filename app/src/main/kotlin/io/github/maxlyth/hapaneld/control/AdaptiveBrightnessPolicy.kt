package io.github.maxlyth.hapaneld.control

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class AdaptiveModelMode { COLD_START, PROVISIONAL, LEARNING, CONFIDENT }

internal data class SolarLocation(val latitude: Double, val longitude: Double)
internal data class SolarFeatures(val elevationDegrees: Double, val rising: Boolean)

internal object SolarPosition {
    /** NOAA-style solar position, accurate enough for matching nearby seven-day observations. */
    fun at(epochMs: Long, location: SolarLocation): SolarFeatures {
        val days = epochMs / 86_400_000.0 - 10_957.5 // days from J2000 noon
        val meanLongitude = radians((280.46 + 0.9856474 * days) % 360.0)
        val anomaly = radians((357.528 + 0.9856003 * days) % 360.0)
        val eclipticLongitude = meanLongitude + radians(1.915) * sin(anomaly) + radians(0.020) * sin(2 * anomaly)
        val obliquity = radians(23.439 - 0.0000004 * days)
        val declination = asin(sin(obliquity) * sin(eclipticLongitude))
        val rightAscension = kotlin.math.atan2(cos(obliquity) * sin(eclipticLongitude), cos(eclipticLongitude))
        val gmst = radians((280.46061837 + 360.98564736629 * (epochMs / 86_400_000.0 - 10_957.5)) % 360.0)
        val hourAngle = normalizeRadians(gmst + radians(location.longitude) - rightAscension)
        val latitude = radians(location.latitude.coerceIn(-90.0, 90.0))
        val elevation = asin(sin(latitude) * sin(declination) + cos(latitude) * cos(declination) * cos(hourAngle))
        return SolarFeatures(Math.toDegrees(elevation), sin(hourAngle) < 0.0)
    }

    private fun radians(degrees: Double) = degrees * PI / 180.0
    private fun normalizeRadians(value: Double): Double {
        var normalized = value % (2 * PI)
        if (normalized > PI) normalized -= 2 * PI
        if (normalized < -PI) normalized += 2 * PI
        return normalized
    }
}

internal data class BaselineEstimate(
    val expectedLogLux: Double,
    val madLogLux: Double,
    val coveredDays: Int,
    val matchedMinutes: Int,
    val brightnessRange: AdaptiveBrightnessRange = AdaptiveBrightnessRange.FIXED,
)

internal data class AdaptiveRangeEvidence(
    val logLux: Double,
    val coverageMs: Long,
    val dayKey: Int,
)

/** Robust room-scale anchors compiled with the retained baseline evidence. */
internal data class AdaptiveBrightnessRange(
    val lowLogLux: Double,
    val highLogLux: Double,
    val learnedWeight: Double,
) {
    val lowLux: Double get() = exp(lowLogLux) - 1.0
    val highLux: Double get() = exp(highLogLux) - 1.0

    companion object {
        val FIXED = AdaptiveBrightnessRange(0.0, 0.0, 0.0)

        fun estimate(evidence: List<AdaptiveRangeEvidence>): AdaptiveBrightnessRange {
            if (evidence.isEmpty()) return FIXED
            val sorted = evidence.asSequence()
                .filter { it.logLux.isFinite() && it.logLux >= 0.0 && it.coverageMs > 0L }
                .sortedBy(AdaptiveRangeEvidence::logLux)
                .toList()
            val totalCoverageMs = sorted.sumOf(AdaptiveRangeEvidence::coverageMs)
            if (totalCoverageMs <= 0L) return FIXED
            val low = weightedQuantile(sorted, LOW_QUANTILE, totalCoverageMs)
            val high = weightedQuantile(sorted, HIGH_QUANTILE, totalCoverageMs)
            if (!low.isFinite() || !high.isFinite() || high - low < MIN_LOG_SPAN) return FIXED

            val coveredDays = sorted.groupBy(AdaptiveRangeEvidence::dayKey)
                .count { (_, day) -> day.sumOf(AdaptiveRangeEvidence::coverageMs) >= MIN_COVERED_DAY_MS }
            val coverageWeight = ((totalCoverageMs - PARTIAL_COVERAGE_MS).toDouble() /
                (FULL_COVERAGE_MS - PARTIAL_COVERAGE_MS)).coerceIn(0.0, 1.0)
            val dayWeight = ((coveredDays - 1) / 2.0).coerceIn(0.0, 1.0)
            val learnedWeight = coverageWeight * dayWeight
            return if (learnedWeight <= 0.0) FIXED else AdaptiveBrightnessRange(low, high, learnedWeight)
        }

        private fun weightedQuantile(
            sorted: List<AdaptiveRangeEvidence>,
            quantile: Double,
            totalCoverageMs: Long,
        ): Double {
            val target = totalCoverageMs * quantile
            var cumulative = 0L
            sorted.forEach { sample ->
                cumulative += sample.coverageMs
                if (cumulative >= target) return sample.logLux
            }
            return sorted.last().logLux
        }

        private const val LOW_QUANTILE = 0.05
        private const val HIGH_QUANTILE = 0.95
        private const val MIN_LOG_SPAN = 1.0
        private const val MIN_COVERED_DAY_MS = 30L * 60_000L
        private const val PARTIAL_COVERAGE_MS = 2L * 60L * 60_000L
        private const val FULL_COVERAGE_MS = 18L * 60L * 60_000L
    }
}

/** Keeps the full seven-day fit off the real-time path. A rebuild is an intentional short burst. */
internal class AdaptiveBaselineCache(
    private val refreshMs: Long = 5L * 60_000L,
    private val estimator: (
        Long,
        List<AmbientHistoryMinute>,
        TimeZone,
        SolarLocation?,
        Double,
    ) -> BaselineEstimate = AdaptiveAmbientModel::estimate,
) {
    private var rowsIdentity: List<AmbientHistoryMinute>? = null
    private var zoneId = ""
    private var location: SolarLocation? = null
    private var computedAtMs = Long.MIN_VALUE
    private var cached: BaselineEstimate? = null

    fun estimate(
        nowMs: Long,
        rows: List<AmbientHistoryMinute>,
        zone: TimeZone,
        location: SolarLocation?,
        fallbackLogLux: Double,
    ): BaselineEstimate = current(nowMs, rows, zone, location)
        ?: compute(nowMs, rows, zone, location, fallbackLogLux).also { store(nowMs, rows, zone, location, it) }

    /** The cached fit when it still describes these inputs, or null when a rebuild is due. */
    fun current(nowMs: Long, rows: List<AmbientHistoryMinute>, zone: TimeZone, location: SolarLocation?): BaselineEstimate? {
        val stale = computedAtMs == Long.MIN_VALUE || nowMs < computedAtMs || nowMs - computedAtMs >= refreshMs
        return cached.takeUnless { rows !== rowsIdentity || zone.id != zoneId || location != this.location || stale }
    }

    /** The rebuild itself. Touches no cache state, so a caller may run it outside its own lock. */
    fun compute(
        nowMs: Long,
        rows: List<AmbientHistoryMinute>,
        zone: TimeZone,
        location: SolarLocation?,
        fallbackLogLux: Double,
    ): BaselineEstimate = estimator(nowMs, rows, zone, location, fallbackLogLux)

    fun store(nowMs: Long, rows: List<AmbientHistoryMinute>, zone: TimeZone, location: SolarLocation?, estimate: BaselineEstimate) {
        cached = estimate
        rowsIdentity = rows
        zoneId = zone.id
        this.location = location
        computedAtMs = nowMs
    }

    fun invalidate() {
        cached = null
        rowsIdentity = null
        computedAtMs = Long.MIN_VALUE
    }
}

internal object AdaptiveAmbientModel {
    fun estimate(
        nowMs: Long,
        rows: List<AmbientHistoryMinute>,
        zone: TimeZone,
        location: SolarLocation?,
        fallbackLogLux: Double,
    ): BaselineEstimate {
        val currentSolar = location?.let { SolarPosition.at(nowMs, it) }
        val currentMinuteOfDay = localMinuteOfDay(nowMs, zone)
        val eligible = rows.asSequence()
            .filter { it.key.minute * AMBIENT_MINUTE_MS < nowMs - AMBIENT_MINUTE_MS }
            .filter { it.baselineCoverageMs >= MIN_BASELINE_COVERAGE_MS }
            .mapNotNull { row -> row.baselineMeanLogLux?.let { row to it } }
            .toList()
        val brightnessRange = AdaptiveBrightnessRange.estimate(eligible.map { (row, logLux) ->
            AdaptiveRangeEvidence(
                logLux = logLux,
                coverageMs = row.baselineCoverageMs,
                dayKey = localDayKey(row.key.minute * AMBIENT_MINUTE_MS, zone),
            )
        })
        val timeCandidates = eligible.filter { (row, _) ->
            val rowMs = row.key.minute * AMBIENT_MINUTE_MS
            circularMinuteDistance(localMinuteOfDay(rowMs, zone), currentMinuteOfDay) <= NIGHT_BIN_MINUTES
        }
        val solarCandidates = if (currentSolar == null) emptyList() else eligible.filter { (row, _) ->
                val rowMs = row.key.minute * AMBIENT_MINUTE_MS
                val historical = SolarPosition.at(rowMs, location!!)
                historical.rising == currentSolar.rising &&
                    abs(historical.elevationDegrees - currentSolar.elevationDegrees) <= SOLAR_BIN_DEGREES
        }
        val solarWeight = currentSolar?.let {
            ((it.elevationDegrees - TWILIGHT_ELEVATION) / -TWILIGHT_ELEVATION).coerceIn(0.0, 1.0)
        } ?: 0.0
        val timeStats = robustStats(timeCandidates.map { it.second }, fallbackLogLux)
        val solarStats = robustStats(solarCandidates.map { it.second }, timeStats.first)
        val expected = timeStats.first + solarWeight * (solarStats.first - timeStats.first)
        val mad = timeStats.second + solarWeight * (solarStats.second - timeStats.second)
        val effectiveSolarCandidates = solarCandidates.ifEmpty { timeCandidates }
        val candidates = when {
            solarWeight <= 0.0 -> timeCandidates
            solarWeight >= 1.0 -> effectiveSolarCandidates
            else -> (timeCandidates + effectiveSolarCandidates).distinctBy { it.first.key }
        }
        val days = candidates.groupBy { localDay(it.first.key.minute * AMBIENT_MINUTE_MS, zone) }
            .count { (_, dayRows) -> dayRows.sumOf { it.first.baselineCoverageMs } >= MIN_COVERED_DAY_MS }
        return BaselineEstimate(expected, mad.coerceAtLeast(MIN_MAD), days, candidates.size, brightnessRange)
    }

    fun mode(sessionCoverageMs: Long, coveredDays: Int): AdaptiveModelMode = when {
        sessionCoverageMs < 30L * 60_000L -> AdaptiveModelMode.COLD_START
        coveredDays < 2 -> AdaptiveModelMode.PROVISIONAL
        coveredDays < 4 -> AdaptiveModelMode.LEARNING
        else -> AdaptiveModelMode.CONFIDENT
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    private fun robustStats(values: List<Double>, fallback: Double): Pair<Double, Double> {
        val center = median(values).takeIf { it.isFinite() } ?: fallback
        val mad = median(values.map { abs(it - center) }).takeIf { it.isFinite() } ?: DEFAULT_MAD
        return center to mad.coerceAtLeast(MIN_MAD)
    }

    private fun localMinuteOfDay(epochMs: Long, zone: TimeZone): Int = Calendar.getInstance(zone).run {
        timeInMillis = epochMs
        get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
    }

    private fun localDay(epochMs: Long, zone: TimeZone): String = Calendar.getInstance(zone).run {
        timeInMillis = epochMs
        "${get(Calendar.YEAR)}-${get(Calendar.DAY_OF_YEAR)}"
    }

    private fun localDayKey(epochMs: Long, zone: TimeZone): Int = Calendar.getInstance(zone).run {
        timeInMillis = epochMs
        get(Calendar.YEAR) * 400 + get(Calendar.DAY_OF_YEAR)
    }

    private fun circularMinuteDistance(a: Int, b: Int): Int = abs(a - b).let { minOf(it, 1440 - it) }

    private const val MIN_BASELINE_COVERAGE_MS = 15_000L
    private const val MIN_COVERED_DAY_MS = 60_000L
    private const val TWILIGHT_ELEVATION = -6.0
    private const val SOLAR_BIN_DEGREES = 3.0
    private const val NIGHT_BIN_MINUTES = 30
    private const val DEFAULT_MAD = 0.15
    private const val MIN_MAD = 0.05
}

/** Demand-built lookup used by the seven-day chart. It compiles history once instead of fitting the
 * full model independently for every five-minute pixel. */
internal class AdaptiveChartBaselineLookup private constructor(
    private val timeBins: Array<BaselineEstimate?>,
    private val solarRisingBins: Array<BaselineEstimate?>?,
    private val solarSettingBins: Array<BaselineEstimate?>?,
    private val zone: TimeZone,
    private val location: SolarLocation?,
    private val fallbackLogLux: Double,
    val brightnessRange: AdaptiveBrightnessRange,
) {
    fun expectedLogLux(epochMs: Long): Double {
        val time = timeBins[localSlot(epochMs, zone)]
            ?: BaselineEstimate(fallbackLogLux, DEFAULT_MAD, 0, 0)
        val place = location ?: return time.expectedLogLux
        val solar = SolarPosition.at(epochMs, place)
        val index = (solar.elevationDegrees.roundToInt() + 90).coerceIn(0, 180)
        val solarEstimate = (if (solar.rising) solarRisingBins else solarSettingBins)?.get(index) ?: time
        val weight = ((solar.elevationDegrees - TWILIGHT_ELEVATION) / -TWILIGHT_ELEVATION).coerceIn(0.0, 1.0)
        return time.expectedLogLux + weight * (solarEstimate.expectedLogLux - time.expectedLogLux)
    }

    companion object {
        fun compile(
            rows: List<AmbientHistoryMinute>,
            zone: TimeZone,
            location: SolarLocation?,
            fallbackLogLux: Double,
        ): AdaptiveChartBaselineLookup {
            val timeEvidence = arrayOfNulls<MutableList<Evidence>>(SLOTS_PER_DAY)
            val risingEvidence = if (location == null) null else arrayOfNulls<MutableList<Evidence>>(181)
            val settingEvidence = if (location == null) null else arrayOfNulls<MutableList<Evidence>>(181)
            val rangeEvidence = mutableListOf<AdaptiveRangeEvidence>()
            rows.forEach { row ->
                val logLux = row.baselineMeanLogLux ?: return@forEach
                if (row.baselineCoverageMs < MIN_BASELINE_COVERAGE_MS) return@forEach
                val epochMs = row.key.minute * AMBIENT_MINUTE_MS
                val evidence = Evidence(logLux, localDayKey(epochMs, zone), row.baselineCoverageMs)
                rangeEvidence += AdaptiveRangeEvidence(logLux, row.baselineCoverageMs, evidence.dayKey)
                val slot = localSlot(epochMs, zone)
                (timeEvidence[slot] ?: mutableListOf<Evidence>().also { timeEvidence[slot] = it }).add(evidence)
                if (location != null) {
                    val solar = SolarPosition.at(epochMs, location)
                    val index = (solar.elevationDegrees.roundToInt() + 90).coerceIn(0, 180)
                    val target = if (solar.rising) risingEvidence!! else settingEvidence!!
                    (target[index] ?: mutableListOf<Evidence>().also { target[index] = it }).add(evidence)
                }
            }
            val compiledTime = Array<BaselineEstimate?>(SLOTS_PER_DAY) { slot ->
                compileWindow(timeEvidence, slot, TIME_WINDOW_SLOTS, circular = true, fallbackLogLux)
            }
            fun compileSolar(source: Array<MutableList<Evidence>?>?): Array<BaselineEstimate?>? =
                source?.let { evidence -> Array(181) { index ->
                    compileWindow(evidence, index, SOLAR_WINDOW_DEGREES, circular = false, fallbackLogLux)
                } }
            return AdaptiveChartBaselineLookup(
                compiledTime,
                compileSolar(risingEvidence),
                compileSolar(settingEvidence),
                zone,
                location,
                fallbackLogLux,
                AdaptiveBrightnessRange.estimate(rangeEvidence),
            )
        }

        private fun compileWindow(
            bins: Array<MutableList<Evidence>?>,
            center: Int,
            radius: Int,
            circular: Boolean,
            fallback: Double,
        ): BaselineEstimate? {
            val evidence = mutableListOf<Evidence>()
            for (offset in -radius..radius) {
                val raw = center + offset
                val index = if (circular) ((raw % bins.size) + bins.size) % bins.size else raw
                if (index in bins.indices) bins[index]?.let(evidence::addAll)
            }
            if (evidence.isEmpty()) return null
            val values = evidence.map(Evidence::logLux).sorted()
            val centerValue = medianSorted(values)
            val deviations = values.map { abs(it - centerValue) }.sorted()
            val days = evidence.groupBy(Evidence::dayKey)
                .count { (_, samples) -> samples.sumOf(Evidence::coverageMs) >= MIN_COVERED_DAY_MS }
            return BaselineEstimate(
                expectedLogLux = centerValue,
                madLogLux = medianSorted(deviations).coerceAtLeast(MIN_MAD),
                coveredDays = days,
                matchedMinutes = evidence.size,
            )
        }

        private fun medianSorted(values: List<Double>): Double {
            val middle = values.size / 2
            return if (values.size % 2 == 1) values[middle] else (values[middle - 1] + values[middle]) / 2.0
        }

        private fun localSlot(epochMs: Long, zone: TimeZone): Int = Calendar.getInstance(zone).run {
            timeInMillis = epochMs
            (get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)) / 5
        }

        private fun localDayKey(epochMs: Long, zone: TimeZone): Int = Calendar.getInstance(zone).run {
            timeInMillis = epochMs
            get(Calendar.YEAR) * 400 + get(Calendar.DAY_OF_YEAR)
        }

        private data class Evidence(val logLux: Double, val dayKey: Int, val coverageMs: Long)

        private const val SLOTS_PER_DAY = 288
        private const val TIME_WINDOW_SLOTS = 6
        private const val SOLAR_WINDOW_DEGREES = 3
        private const val MIN_BASELINE_COVERAGE_MS = 15_000L
        private const val MIN_COVERED_DAY_MS = 60_000L
        private const val TWILIGHT_ELEVATION = -6.0
        private const val DEFAULT_MAD = 0.15
        private const val MIN_MAD = 0.05
    }
}

internal object AdaptiveLuxCurve {
    const val VERSION = 3
    private val points = listOf(
        0.0 to 0.04,
        1.0 to 0.06,
        10.0 to 0.12,
        100.0 to 0.30,
        1_000.0 to 0.65,
        10_000.0 to 1.0,
    )

    fun rawBrightness(
        lux: Double,
        range: AdaptiveBrightnessRange = AdaptiveBrightnessRange.FIXED,
        minimumBrightness: Int = BrightnessController.MIN_VISIBLE,
    ): Int {
        val minimum = minimumBrightness.coerceIn(BrightnessController.MIN_VISIBLE, 255)
        val fixed = (minimum + fixedFraction(lux) * (255 - minimum)).roundToInt()
        if (range.learnedWeight <= 0.0) return fixed
        val learned = (minimum + learnedFraction(lux, range) * (255 - minimum)).roundToInt()
        return (fixed + range.learnedWeight * (learned - fixed)).roundToInt()
            .coerceIn(minimum, 255)
    }

    /**
     * How bright [lux] is for this room, 0 (its dark end) to 1 (its bright end): the same fixed and
     * learned fractions [rawBrightness] maps onto the backlight, blended by the same learned weight,
     * but before the configured automatic minimum rescales them. That floor is a backlight preference,
     * so it must not move anything else that reads the room's light, such as the Ambient theme.
     */
    fun normalizedLevel(lux: Double, range: AdaptiveBrightnessRange = AdaptiveBrightnessRange.FIXED): Double {
        val fixed = fixedFraction(lux)
        if (range.learnedWeight <= 0.0) return fixed
        return (fixed + range.learnedWeight * (learnedFraction(lux, range) - fixed)).coerceIn(0.0, 1.0)
    }

    private fun fixedFraction(lux: Double): Double =
        (fixedBrightness(lux) - BrightnessController.MIN_VISIBLE).toDouble() / (255 - BrightnessController.MIN_VISIBLE)

    private fun learnedFraction(lux: Double, range: AdaptiveBrightnessRange): Double =
        ((ln1p(lux.coerceAtLeast(0.0)) - range.lowLogLux) / (range.highLogLux - range.lowLogLux)).coerceIn(0.0, 1.0)

    fun percentToBrightness(percent: Int): Int = (percent.coerceIn(0, 100) * 255 / 100.0)
        .roundToInt()
        .coerceIn(BrightnessController.MIN_VISIBLE, 255)

    private fun fixedBrightness(lux: Double): Int {
        val safe = lux.coerceIn(0.0, points.last().first)
        val upperIndex = points.indexOfFirst { safe <= it.first }.let { if (it < 0) points.lastIndex else it }
        val fraction = when (upperIndex) {
            0 -> points[0].second
            else -> {
                val lower = points[upperIndex - 1]
                val upper = points[upperIndex]
                val span = ln1p(upper.first) - ln1p(lower.first)
                val t = if (span == 0.0) 0.0 else (ln1p(safe) - ln1p(lower.first)) / span
                lower.second + t.coerceIn(0.0, 1.0) * (upper.second - lower.second)
            }
        }
        return (fraction * 255.0).roundToInt().coerceIn(BrightnessController.MIN_VISIBLE, 255)
    }
}

internal data class AdaptiveBrightnessResult(
    val brightness: Int,
    val effectiveLux: Double,
    val expectedLux: Double,
    val brighterThanExpected: Boolean,
    val mode: AdaptiveModelMode,
    val baselineEligible: Boolean,
    val estimate: BaselineEstimate,
)

/** One-second, elapsed-time policy. Event admission and release are independent of callback rate. */
internal class AdaptiveBrightnessPolicy {
    private var sessionCoverageMs = 0L
    private var positiveResidualMs = 0L
    private var clearingMs = 0L
    private var releaseMs = 0L
    private var boost = false
    private var fastAdmissionCandidate = false
    private var smoothedDirectLog = Double.NaN
    private var previousLog = Double.NaN
    private var lastQualifies = false
    private var cachedSensitivity = Int.MIN_VALUE
    private var cachedGain = 1.0

    fun evaluate(
        nowMs: Long,
        elapsedMs: Long,
        lux: Double,
        history: List<AmbientHistoryMinute>,
        sensitivity: Int,
        zone: TimeZone = TimeZone.getDefault(),
        location: SolarLocation? = null,
        minimumBrightness: Int = BrightnessController.MIN_VISIBLE,
    ): AdaptiveBrightnessResult? {
        if (nowMs < 0L || elapsedMs <= 0L || !lux.isFinite() || lux < 0.0) return null
        val fallback = if (smoothedDirectLog.isFinite()) smoothedDirectLog else ln1p(lux.coerceAtLeast(0.0))
        val estimate = AdaptiveAmbientModel.estimate(nowMs, history, zone, location, fallback)
        return evaluate(nowMs, elapsedMs, lux, estimate, sensitivity, minimumBrightness = minimumBrightness)
    }

    /** O(1) real-time path using a baseline built by [AdaptiveBaselineCache]. */
    fun evaluate(
        nowMs: Long,
        elapsedMs: Long,
        lux: Double,
        baseline: BaselineEstimate,
        sensitivity: Int,
        conditionElapsedMs: Long = elapsedMs,
        minimumBrightness: Int = BrightnessController.MIN_VISIBLE,
    ): AdaptiveBrightnessResult? {
        if (nowMs < 0L || elapsedMs <= 0L || !lux.isFinite() || lux < 0.0) return null
        val elapsed = elapsedMs.coerceAtMost(MAX_EVALUATION_GAP_MS)
        val conditionElapsed = conditionElapsedMs.coerceIn(0L, elapsed)
        sessionCoverageMs += elapsed
        val observedLog = ln1p(lux)
        smoothedDirectLog = if (!smoothedDirectLog.isFinite()) observedLog else {
            val alpha = 1.0 - exp(-elapsed / DIRECT_SMOOTHING_MS)
            smoothedDirectLog + alpha * (observedLog - smoothedDirectLog)
        }
        val estimate = if (baseline.matchedMinutes == 0) {
            baseline.copy(expectedLogLux = smoothedDirectLog, matchedMinutes = 0)
        } else baseline
        val mode = AdaptiveAmbientModel.mode(sessionCoverageMs, estimate.coveredDays)
        val expectedLog = when (mode) {
            AdaptiveModelMode.COLD_START -> smoothedDirectLog
            AdaptiveModelMode.PROVISIONAL -> 0.75 * smoothedDirectLog + 0.25 * estimate.expectedLogLux
            AdaptiveModelMode.LEARNING -> 0.35 * smoothedDirectLog + 0.65 * estimate.expectedLogLux
            AdaptiveModelMode.CONFIDENT -> estimate.expectedLogLux
        }
        val residual = observedLog - expectedLog
        val threshold = max(ln(1.6), 3.0 * estimate.madLogLux)
        val qualifies = residual >= threshold && lux - expm1Safe(expectedLog) >= 5.0
        lastQualifies = qualifies
        val fastRise = previousLog.isFinite() && observedLog - previousLog >= ln(1.35)
        if (!boost) {
            if (qualifies) {
                positiveResidualMs += conditionElapsed
                fastAdmissionCandidate = fastAdmissionCandidate || fastRise
            } else {
                positiveResidualMs = 0L
                fastAdmissionCandidate = false
            }
            if (qualifies && ((fastAdmissionCandidate && positiveResidualMs >= FAST_ADMISSION_MS) ||
                    positiveResidualMs >= SUSTAINED_ADMISSION_MS)) {
                boost = true
                clearingMs = 0L
                releaseMs = 0L
            }
        } else if (qualifies) {
            clearingMs = 0L
            releaseMs = 0L
        } else {
            clearingMs += conditionElapsed
            if (clearingMs >= EXIT_CONFIRM_MS) releaseMs += conditionElapsed
            if (releaseMs >= RELEASE_DECAY_MS) {
                boost = false
                positiveResidualMs = 0L
                fastAdmissionCandidate = false
                clearingMs = 0L
                releaseMs = 0L
            }
        }
        previousLog = observedLog
        val positiveWeight = when {
            residual <= 0.0 -> 1.0
            boost && releaseMs > 0L -> (1.0 - releaseMs.toDouble() / RELEASE_DECAY_MS).coerceIn(0.0, 1.0)
            boost -> 1.0
            else -> 0.0
        }
        val conditionedResidual = if (residual > 0.0) residual * positiveWeight else residual
        val normalizedSensitivity = sensitivity.coerceIn(0, 100)
        if (normalizedSensitivity != cachedSensitivity) {
            cachedSensitivity = normalizedSensitivity
            cachedGain = adaptiveSensitivityGain(normalizedSensitivity)
        }
        val gain = cachedGain
        val effectiveLog = if (mode == AdaptiveModelMode.COLD_START) smoothedDirectLog else {
            (expectedLog + gain * conditionedResidual).coerceAtLeast(0.0)
        }
        val effectiveLux = expm1Safe(effectiveLog)
        return AdaptiveBrightnessResult(
            brightness = AdaptiveLuxCurve.rawBrightness(effectiveLux, estimate.brightnessRange, minimumBrightness),
            effectiveLux = effectiveLux,
            expectedLux = expm1Safe(expectedLog),
            brighterThanExpected = boost,
            mode = mode,
            baselineEligible = !qualifies && !boost,
            estimate = estimate,
        )
    }

    fun needsFastFollowUp(): Boolean = lastQualifies != boost || clearingMs > 0L || releaseMs > 0L

    private fun expm1Safe(value: Double) = (exp(value.coerceAtMost(ln(1_000_001.0))) - 1.0).coerceAtLeast(0.0)

    companion object {
        private const val DIRECT_SMOOTHING_MS = 60_000.0
        private const val MAX_EVALUATION_GAP_MS = 60_000L
        private const val FAST_ADMISSION_MS = 2_000L
        private const val SUSTAINED_ADMISSION_MS = 30_000L
        private const val EXIT_CONFIRM_MS = 10_000L
        private const val RELEASE_DECAY_MS = 20_000L
    }
}

internal data class AdaptiveChartPoint(
    val epochMinute: Long,
    val observedMeanLux: Double,
    val minLux: Double,
    val maxLux: Double,
    val expectedLux: Double,
    val proposedBrightness: Int,
)

/**
 * Fraction of the CONDITIONED deviation from the learned pattern that reaches the screen.
 *
 * The engine blends rather than amplifies — `expectedLog + gain * conditionedResidual` — so 0 uses the
 * learned pattern alone. There is deliberately no value above 100: a gain over 1.0 would drive the
 * screen past the light the sensor actually reported.
 *
 * 100 is NOT "follows the measured light exactly", and the difference is not pedantic. A positive
 * excursion is weighted by boost admission and release, so an unadmitted brightening reaches the screen
 * at weight zero however high this value is, and in [AdaptiveModelMode.COLD_START] the effective level
 * is the smoothed measurement with this value never consulted. What this scales is the residual the
 * engine has already decided to act on.
 */
internal fun adaptiveSensitivityGain(sensitivity: Int): Double = sensitivity.coerceIn(0, 100) / 100.0

internal object AdaptiveChartProjection {
    /** At most 2,016 five-minute points for a seven-day window. */
    fun fiveMinute(
        rows: List<AmbientHistoryMinute>,
        // Deliberately not defaulted: a defaulted sensitivity silently reinterprets whenever the scale
        // changes, and this projection must show exactly what the caller is previewing.
        sensitivity: Int,
        brightnessRange: AdaptiveBrightnessRange = AdaptiveBrightnessRange.FIXED,
        minimumBrightness: Int = BrightnessController.MIN_VISIBLE,
        expectedLogLux: (Long) -> Double,
    ): List<AdaptiveChartPoint> =
        rows.groupBy { floor(it.key.minute / 5.0).toLong() * 5L }
            .toSortedMap()
            .entries.toList().takeLast(2_016)
            .map { (minute, bucket) ->
                val coverage = bucket.sumOf { it.coverageMs }.coerceAtLeast(1L)
                val observed = bucket.sumOf { it.luxIntegral } / coverage
                val expectedLog = expectedLogLux(minute * AMBIENT_MINUTE_MS)
                val expected = exp(expectedLog) - 1.0
                val gain = adaptiveSensitivityGain(sensitivity)
                val projectedLux = exp((expectedLog + gain * (ln1p(observed) - expectedLog)).coerceAtLeast(0.0)) - 1.0
                AdaptiveChartPoint(
                    epochMinute = minute,
                    observedMeanLux = observed,
                    minLux = bucket.minOf { it.minLux },
                    maxLux = bucket.maxOf { it.maxLux },
                    expectedLux = expected.coerceAtLeast(0.0),
                    proposedBrightness = AdaptiveLuxCurve.rawBrightness(
                        projectedLux,
                        brightnessRange,
                        minimumBrightness,
                    ),
                )
            }
}
