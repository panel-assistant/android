package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone
import kotlin.math.ln1p
import kotlin.math.roundToInt

class AdaptiveBrightnessPolicyTest {
    @Test fun baselineCacheBuildsOnlyOnTtlEvidenceOrContextChange() {
        var builds = 0
        val cache = AdaptiveBaselineCache(refreshMs = 300_000L) { _, rows, _, _, fallback ->
            builds++
            BaselineEstimate(fallback, 0.1, 0, rows.size)
        }
        val rows = listOf(historyRow(NOW / AMBIENT_MINUTE_MS - 1, 10.0))
        repeat(300) { cache.estimate(NOW + it * 1_000L, rows, UTC, null, ln1p(10.0)) }
        assertEquals(1, builds)
        cache.estimate(NOW + 300_000L, rows, UTC, null, ln1p(10.0))
        assertEquals(2, builds)
        cache.estimate(NOW + 300_001L, rows.toList(), UTC, null, ln1p(10.0))
        assertEquals(3, builds)
        cache.estimate(NOW + 300_002L, rows, UTC, SolarLocation(1.0, 2.0), ln1p(10.0))
        assertEquals(4, builds)
    }

    @Test fun cachedPolicyDoesNotCountCalmTimeBeforeAHighObservation() {
        val policy = AdaptiveBrightnessPolicy()
        val baseline = BaselineEstimate(ln1p(10.0), 0.05, 4, 20)
        repeat(30) { policy.evaluate(NOW + it * 60_000L, 60_000L, 10.0, baseline, 50) }

        val first = policy.evaluate(NOW + 1_800_000L, 60_000L, 100.0, baseline, 50, conditionElapsedMs = 0L)!!
        assertFalse(first.brighterThanExpected)
        assertTrue(policy.needsFastFollowUp())
        assertFalse(policy.evaluate(NOW + 1_801_000L, 1_000L, 100.0, baseline, 50)!!.brighterThanExpected)
        assertTrue(policy.evaluate(NOW + 1_802_000L, 1_000L, 100.0, baseline, 50)!!.brighterThanExpected)
        assertFalse("a steady admitted boost returns to calm cadence", policy.needsFastFollowUp())
        policy.evaluate(NOW + 1_803_000L, 1_000L, 10.0, baseline, 50, conditionElapsedMs = 0L)
        assertTrue("a clearing transition enters bounded fast cadence", policy.needsFastFollowUp())
    }

    @Test fun versionedCurveUsesSpecifiedAnchorsAndSafetyBounds() {
        assertEquals(10, AdaptiveLuxCurve.rawBrightness(0.0))
        assertEquals((0.06 * 255).toInt(), AdaptiveLuxCurve.rawBrightness(1.0))
        assertEquals(77, AdaptiveLuxCurve.rawBrightness(100.0))
        assertEquals(255, AdaptiveLuxCurve.rawBrightness(10_000.0))
        assertEquals(3, AdaptiveLuxCurve.VERSION)
    }

    @Test fun minimumLevelAffinelyRescalesTheWholeAutomaticRange() {
        val minimum = AdaptiveLuxCurve.percentToBrightness(25)

        assertEquals(64, minimum)
        assertEquals(minimum, AdaptiveLuxCurve.rawBrightness(0.0, minimumBrightness = minimum))
        assertEquals(255, AdaptiveLuxCurve.rawBrightness(10_000.0, minimumBrightness = minimum))
        val native = AdaptiveLuxCurve.rawBrightness(100.0)
        val expected = (minimum + (native - BrightnessController.MIN_VISIBLE).toDouble() /
            (255 - BrightnessController.MIN_VISIBLE) * (255 - minimum)).toInt()
        assertTrue(kotlin.math.abs(expected - AdaptiveLuxCurve.rawBrightness(100.0, minimumBrightness = minimum)) <= 1)
    }

    @Test fun configuredMinimumPreservesLearnedEndpointsAndDoesNotChangeSensitivityMath() {
        val range = estimateRange(learnedRangeHistory())
        val lowMinimum = AdaptiveLuxCurve.percentToBrightness(4)
        val highMinimum = AdaptiveLuxCurve.percentToBrightness(40)

        assertEquals(lowMinimum, AdaptiveLuxCurve.rawBrightness(range.lowLux, range, lowMinimum))
        assertEquals(highMinimum, AdaptiveLuxCurve.rawBrightness(range.lowLux, range, highMinimum))
        assertEquals(255, AdaptiveLuxCurve.rawBrightness(range.highLux, range, lowMinimum))
        assertEquals(255, AdaptiveLuxCurve.rawBrightness(range.highLux, range, highMinimum))

        fun result(minimum: Int) = AdaptiveBrightnessPolicy().evaluate(
            NOW,
            60_000L,
            25.0,
            BaselineEstimate(ln1p(100.0), 0.05, 4, 100, range),
            80,
            minimumBrightness = minimum,
        )!!
        val low = result(lowMinimum)
        val high = result(highMinimum)
        assertEquals(low.effectiveLux, high.effectiveLux, 0.0)
        assertEquals(low.brighterThanExpected, high.brighterThanExpected)
        assertTrue(high.brightness >= low.brightness)
    }

    @Test fun sensitivityScalesOnlyDeviationFromExpectation() {
        val rows = repeatedHistory(expectedLux = 100.0, days = 4)
        fun evaluate(sensitivity: Int): AdaptiveBrightnessResult {
            val policy = AdaptiveBrightnessPolicy()
            repeat(360) { tick ->
                policy.evaluate(NOW + tick * 5_000L, 5_000, 100.0, rows, sensitivity, UTC)
            }
            return policy.evaluate(NOW + 1_800_000L, 1_000, 25.0, rows, sensitivity, UTC)!!
        }
        val steady = evaluate(0)
        val normal = evaluate(50)
        val responsive = evaluate(100)
        assertTrue(steady.effectiveLux > normal.effectiveLux)
        assertTrue(normal.effectiveLux > responsive.effectiveLux)
        assertFalse(normal.brighterThanExpected)
    }

    @Test fun sensitivityIsTheFractionOfMeasuredDeviationApplied() {
        assertEquals(0.0, adaptiveSensitivityGain(0), 0.0)
        assertEquals(0.25, adaptiveSensitivityGain(25), 0.0)
        assertEquals(0.5, adaptiveSensitivityGain(50), 0.0)
        // The top of the scale follows the measurement exactly. Nothing amplifies past it, because a
        // gain above 1.0 would drive the screen beyond the light the sensor actually reported.
        assertEquals(1.0, adaptiveSensitivityGain(100), 0.0)
        assertEquals(0.0, adaptiveSensitivityGain(-1), 0.0)
        assertEquals(1.0, adaptiveSensitivityGain(101), 0.0)

        val row = historyRow(NOW / AMBIENT_MINUTE_MS, 25.0)
        fun projected(sensitivity: Int) = AdaptiveChartProjection.fiveMinute(
            rows = listOf(row),
            sensitivity = sensitivity,
            expectedLogLux = { ln1p(100.0) },
        ).single().proposedBrightness

        assertTrue(projected(0) > projected(50))
        assertTrue(projected(50) > projected(100))
    }

    @Test fun qualifyingFastRiseAdmitsAfterTwoSecondsThenReleases() {
        val history = repeatedHistory(expectedLux = 10.0, days = 4)
        val policy = AdaptiveBrightnessPolicy()
        repeat(360) { policy.evaluate(NOW + it * 5_000L, 5_000, 10.0, history, 50, UTC) }
        val first = policy.evaluate(NOW + 1_800_000, 1_000, 100.0, history, 50, UTC)!!
        val second = policy.evaluate(NOW + 1_801_000, 1_000, 100.0, history, 50, UTC)!!
        assertFalse(first.brighterThanExpected)
        assertTrue(second.brighterThanExpected)
        repeat(29) { policy.evaluate(NOW + 1_802_000 + it * 1_000L, 1_000, 10.0, history, 50, UTC) }
        val released = policy.evaluate(NOW + 1_831_000, 1_000, 10.0, history, 50, UTC)!!
        assertFalse(released.brighterThanExpected)
    }

    @Test fun maturityRequiresElapsedCoverageAndDistinctHistoricalDays() {
        assertEquals(AdaptiveModelMode.COLD_START, AdaptiveAmbientModel.mode(29 * 60_000, 7))
        assertEquals(AdaptiveModelMode.PROVISIONAL, AdaptiveAmbientModel.mode(30 * 60_000, 1))
        assertEquals(AdaptiveModelMode.LEARNING, AdaptiveAmbientModel.mode(30 * 60_000, 2))
        assertEquals(AdaptiveModelMode.CONFIDENT, AdaptiveAmbientModel.mode(30 * 60_000, 4))
    }

    @Test fun elapsedTimeSmoothingIsInvariantToEvaluationRate() {
        val frequent = AdaptiveBrightnessPolicy()
        val sparse = AdaptiveBrightnessPolicy()
        frequent.evaluate(NOW, 1_000, 10.0, emptyList(), 50, UTC)
        sparse.evaluate(NOW, 1_000, 10.0, emptyList(), 50, UTC)
        var frequentResult: AdaptiveBrightnessResult? = null
        var sparseResult: AdaptiveBrightnessResult? = null
        repeat(60) { tick ->
            frequentResult = frequent.evaluate(NOW + (tick + 1) * 1_000L, 1_000, 100.0, emptyList(), 50, UTC)
        }
        repeat(12) { tick ->
            sparseResult = sparse.evaluate(NOW + (tick + 1) * 5_000L, 5_000, 100.0, emptyList(), 50, UTC)
        }
        assertTrue(kotlin.math.abs(frequentResult!!.brightness - sparseResult!!.brightness) <= 1)
        assertEquals(frequentResult!!.effectiveLux, sparseResult!!.effectiveLux, 0.001)
    }

    @Test fun invalidObservationsDoNotAdvancePolicy() {
        val policy = AdaptiveBrightnessPolicy()
        assertEquals(null, policy.evaluate(NOW, 1_000, Double.NaN, emptyList(), 50, UTC))
        assertEquals(null, policy.evaluate(NOW, 1_000, -1.0, emptyList(), 50, UTC))
        assertEquals(null, policy.evaluate(NOW, 0, 1.0, emptyList(), 50, UTC))
        assertEquals(AdaptiveModelMode.COLD_START,
            policy.evaluate(NOW, 1_000, 1.0, emptyList(), 50, UTC)!!.mode)
    }

    @Test fun localTimeFallbackUsesRobustMedianAndRejectsOutlier() {
        val baseMinute = NOW / AMBIENT_MINUTE_MS
        val rows = listOf(10.0, 11.0, 12.0, 10_000.0).mapIndexed { index, lux ->
            historyRow(baseMinute - (index + 1) * 1440L, lux)
        }
        val estimate = AdaptiveAmbientModel.estimate(NOW, rows, UTC, null, ln1p(1.0))
        assertTrue(estimate.expectedLogLux in ln1p(10.0)..ln1p(12.0))
        assertEquals(4, estimate.coveredDays)
    }

    @Test fun chartProjectionIsFiveMinuteAndPartitionAgnostic() {
        val rows = (0 until 12).map { historyRow(NOW / AMBIENT_MINUTE_MS + it, (it + 1).toDouble()) }
        // 100 is the schema-6 spelling of the gain this test used when the parameter was defaulted.
        val points = AdaptiveChartProjection.fiveMinute(rows, sensitivity = 100, expectedLogLux = { ln1p(20.0) })
        assertEquals(3, points.size)
        assertEquals(20.0, points.first().expectedLux, 0.0001)
        assertTrue(points.first().maxLux >= points.first().minLux)
    }

    @Test fun compiledChartBaselineReusesOneHistoryIndexForEveryPoint() {
        val rows = repeatedHistory(expectedLux = 20.0, days = 7)
        val lookup = AdaptiveChartBaselineLookup.compile(rows, UTC, null, ln1p(1.0))
        repeat(2_016) {
            assertEquals(20.0, kotlin.math.exp(lookup.expectedLogLux(NOW)) - 1.0, 0.0001)
        }
    }

    @Test fun wellCoveredRoomRangeMapsRobustAnchorsToFullPanelRange() {
        val rows = learnedRangeHistory()
        val range = estimateRange(rows)

        assertEquals(1.0, range.learnedWeight, 0.0)
        assertEquals(1.0, range.lowLux, 0.0001)
        assertEquals(100.0, range.highLux, 0.0001)
        assertEquals(BrightnessController.MIN_VISIBLE, AdaptiveLuxCurve.rawBrightness(range.lowLux, range))
        assertEquals(255, AdaptiveLuxCurve.rawBrightness(range.highLux, range))
    }

    @Test fun learnedRangeRejectsSingleSpikesAndNonBaselineValues() {
        val normal = learnedRangeHistory()
        val withOutliers = normal + listOf(
            historyRow(NOW / AMBIENT_MINUTE_MS - 10, 100_000.0),
            historyRow(NOW / AMBIENT_MINUTE_MS - 11, 100_000.0, baselineEligible = false),
        )

        val range = estimateRange(withOutliers)
        assertEquals(100.0, range.highLux, 0.0001)
        assertEquals(255, AdaptiveLuxCurve.rawBrightness(100.0, range))
    }

    @Test fun insufficientOrNarrowHistoryFallsBackToFixedCurve() {
        val insufficient = (0 until 360).map { offset ->
            historyRow(NOW / AMBIENT_MINUTE_MS - offset - 2, if (offset % 2 == 0) 1.0 else 100.0)
        }
        val narrow = learnedRangeHistory(lowLux = 20.0, middleLux = 21.0, highLux = 22.0)

        listOf(estimateRange(insufficient), estimateRange(narrow)).forEach { range ->
            assertEquals(0.0, range.learnedWeight, 0.0)
            assertEquals(AdaptiveLuxCurve.rawBrightness(50.0), AdaptiveLuxCurve.rawBrightness(50.0, range))
        }
    }

    @Test fun provisionalHistoryBlendsWithRatherThanReplacesFixedCurve() {
        val rows = learnedRangeHistory(days = 2, minutesPerDay = 540)
        val range = estimateRange(rows)
        val fixed = AdaptiveLuxCurve.rawBrightness(range.highLux)
        val blended = AdaptiveLuxCurve.rawBrightness(range.highLux, range)

        assertTrue(range.learnedWeight in 0.0..1.0)
        assertTrue(range.learnedWeight > 0.0)
        assertTrue(blended > fixed)
        assertTrue(blended < 255)
    }

    @Test fun learnedRangeUsesOnlyAmbientEvidenceNotStartupBrightness() {
        val rows = learnedRangeHistory()
        val first = estimateRange(rows)
        val second = estimateRange(rows.map { row ->
            row.copy(minLux = 0.0, maxLux = 100_000.0, lastLux = 100_000.0)
        })

        assertEquals(first, second)
    }

    @Test fun runtimeAndChartUseTheSameLearnedRangeAndMinimumMapping() {
        val rows = learnedRangeHistory()
        val range = estimateRange(rows)
        val baseline = BaselineEstimate(ln1p(1.0), 0.05, 4, 100, range)
        val minimum = AdaptiveLuxCurve.percentToBrightness(30)
        val policy = AdaptiveBrightnessPolicy()
        var runtime: AdaptiveBrightnessResult? = null
        repeat(31) { tick ->
            runtime = policy.evaluate(
                NOW + tick * 60_000L,
                60_000L,
                1.0,
                baseline,
                50,
                minimumBrightness = minimum,
            )
        }
        val chart = AdaptiveChartProjection.fiveMinute(
            rows = listOf(historyRow(NOW / AMBIENT_MINUTE_MS, 1.0)),
            sensitivity = 50,
            brightnessRange = range,
            minimumBrightness = minimum,
            expectedLogLux = { ln1p(1.0) },
        ).single()

        assertEquals(runtime!!.brightness, chart.proposedBrightness)
        assertEquals(minimum, chart.proposedBrightness)
    }

    @Test fun maximumRescalesFixedAndLearnedRangesRatherThanClipping() {
        val minimum = AdaptiveLuxCurve.percentToBrightness(20)
        val maximum = AdaptiveLuxCurve.percentToBrightness(60)
        val learned = estimateRange(learnedRangeHistory())
        listOf(AdaptiveBrightnessRange.FIXED, learned).forEach { range ->
            val dark = if (range.learnedWeight == 0.0) 0.0 else range.lowLux
            val bright = if (range.learnedWeight == 0.0) 10_000.0 else range.highLux
            assertEquals(minimum, AdaptiveLuxCurve.rawBrightness(dark, range, minimum, maximum))
            assertEquals(maximum, AdaptiveLuxCurve.rawBrightness(bright, range, minimum, maximum))
            val middle = AdaptiveLuxCurve.rawBrightness(25.0, range, minimum, maximum)
            assertTrue(middle > minimum && middle < maximum)
            assertTrue(middle < AdaptiveLuxCurve.rawBrightness(25.0, range, minimum))
        }
    }

    @Test fun fullMaximumPreservesEveryPreviousIntegerCurve() {
        val anchors = listOf(0.0 to 0.04, 1.0 to 0.06, 10.0 to 0.12,
            100.0 to 0.30, 1_000.0 to 0.65, 10_000.0 to 1.0)
        val learned = estimateRange(learnedRangeHistory())
        // Independent copy of the shipped numerical definition, including both integer roundings.
        fun previous(lux: Double, range: AdaptiveBrightnessRange, minimum: Int): Int {
            val safe = lux.coerceIn(0.0, 10_000.0)
            val index = anchors.indexOfFirst { safe <= it.first }
            val fraction = if (index == 0) anchors[0].second else {
                val (lowLux, low) = anchors[index - 1]
                val (highLux, high) = anchors[index]
                low + (ln1p(safe) - ln1p(lowLux)) / (ln1p(highLux) - ln1p(lowLux)) * (high - low)
            }
            val native = (fraction * 255).roundToInt().coerceIn(10, 255)
            val fixed = (minimum + (native - 10).toDouble() / 245 * (255 - minimum)).roundToInt()
            if (range.learnedWeight <= 0.0) return fixed
            val roomFraction = ((ln1p(lux.coerceAtLeast(0.0)) - range.lowLogLux) /
                (range.highLogLux - range.lowLogLux)).coerceIn(0.0, 1.0)
            val room = (minimum + roomFraction * (255 - minimum)).roundToInt()
            return (fixed + range.learnedWeight * (room - fixed)).roundToInt().coerceIn(minimum, 255)
        }
        listOf(AdaptiveBrightnessRange.FIXED, learned, learned.copy(learnedWeight = 0.5)).forEach { range ->
            listOf(10, 64, 128, 252).forEach { minimum ->
                (0..10_000).forEach { lux ->
                    assertEquals(previous(lux.toDouble(), range, minimum),
                        AdaptiveLuxCurve.rawBrightness(lux.toDouble(), range, minimum, 255))
                }
            }
        }
    }

    @Test fun runtimeAndChartRespectMaximumForColdAndLearnedModels() {
        val minimum = AdaptiveLuxCurve.percentToBrightness(10)
        val maximum = AdaptiveLuxCurve.percentToBrightness(40)
        listOf(AdaptiveBrightnessRange.FIXED, estimateRange(learnedRangeHistory())).forEach { range ->
            val lux = 10_000.0
            val baseline = BaselineEstimate(ln1p(lux), 0.05, 4, 100, range)
            val runtime = AdaptiveBrightnessPolicy().evaluate(NOW, 60_000L, lux, baseline, 100,
                minimumBrightness = minimum, maximumBrightness = maximum)!!
            val chart = AdaptiveChartProjection.fiveMinute(
                rows = listOf(historyRow(NOW / AMBIENT_MINUTE_MS, lux)), sensitivity = 100,
                brightnessRange = range, minimumBrightness = minimum, maximumBrightness = maximum,
                expectedLogLux = { ln1p(lux) },
            ).single()
            assertEquals(maximum, runtime.brightness)
            assertEquals(runtime.brightness, chart.proposedBrightness)
        }
    }

    private fun repeatedHistory(expectedLux: Double, days: Int): List<AmbientHistoryMinute> =
        (1..days).flatMap { day -> (-2..2).map { offset ->
            historyRow(NOW / AMBIENT_MINUTE_MS - day * 1440L + offset, expectedLux)
        } }

    private fun learnedRangeHistory(
        days: Int = 3,
        minutesPerDay: Int = 400,
        lowLux: Double = 1.0,
        middleLux: Double = 25.0,
        highLux: Double = 100.0,
    ): List<AmbientHistoryMinute> = (1..days).flatMap { day ->
        (0 until minutesPerDay).map { offset ->
            val lux = when {
                offset < minutesPerDay / 10 -> lowLux
                offset >= minutesPerDay * 9 / 10 -> highLux
                else -> middleLux
            }
            historyRow(NOW / AMBIENT_MINUTE_MS - day * 1440L + offset, lux)
        }
    }

    private fun estimateRange(rows: List<AmbientHistoryMinute>): AdaptiveBrightnessRange =
        AdaptiveAmbientModel.estimate(NOW, rows, UTC, null, 0.0).brightnessRange

    private fun historyRow(
        minute: Long,
        lux: Double,
        baselineEligible: Boolean = true,
    ): AmbientHistoryMinute = AmbientHistoryMinute(
        AmbientHistoryKey("location", "source", minute), lux * 60_000, 60_000,
        lux, lux, lux, 60,
        if (baselineEligible) ln1p(lux) * 60_000 else 0.0,
        if (baselineEligible) 60_000 else 0L,
    )

    companion object {
        private val UTC = TimeZone.getTimeZone("UTC")
        private const val NOW = 1_800_000_000_000L
    }
}
