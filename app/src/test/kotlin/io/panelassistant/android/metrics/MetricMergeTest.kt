package io.panelassistant.android.metrics

import io.panelassistant.android.metrics.DashboardMetrics.ENTITY_COUNT
import io.panelassistant.android.metrics.DashboardMetrics.FRAMES
import io.panelassistant.android.metrics.DashboardMetrics.INPUT_DELAY_MICROS
import io.panelassistant.android.metrics.DashboardMetrics.INTERACTION_MAX_MICROS
import io.panelassistant.android.metrics.DashboardMetrics.INTERACTION_PROCESSING_MICROS
import io.panelassistant.android.metrics.DashboardMetrics.LOAF_MAX_MICROS
import io.panelassistant.android.metrics.DashboardMetrics.PRESENTATION_MICROS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricMergeTest {
    private fun merge(bucket: Map<Int, Long>, sample: Map<Int, Long>) = DashboardMetrics.merge(bucket, sample)

    @Test fun countersAccumulate() {
        assertEquals(7L, merge(mapOf(FRAMES to 3L), mapOf(FRAMES to 4L))[FRAMES])
    }

    @Test fun bucketPropertiesTakeTheLatestValue() {
        assertEquals(12L, merge(mapOf(ENTITY_COUNT to 40L), mapOf(ENTITY_COUNT to 12L))[ENTITY_COUNT])
    }

    @Test fun worstCaseMetricsKeepTheirMaximum() {
        assertEquals(90L, merge(mapOf(LOAF_MAX_MICROS to 90L), mapOf(LOAF_MAX_MICROS to 10L))[LOAF_MAX_MICROS])
        assertEquals(90L, merge(mapOf(LOAF_MAX_MICROS to 10L), mapOf(LOAF_MAX_MICROS to 90L))[LOAF_MAX_MICROS])
    }

    /**
     * The subtle one the SQL encoded as `CASE WHEN ?>interaction_max_micros`. These three are the
     * breakdown of the slowest interaction; taking each one's own maximum would report an interaction
     * that never happened, assembled from different samples.
     */
    @Test fun theSlowestInteractionKeepsItsOwnBreakdown() {
        val slow = mapOf(
            INTERACTION_MAX_MICROS to 900L,
            INPUT_DELAY_MICROS to 100L, INTERACTION_PROCESSING_MICROS to 700L, PRESENTATION_MICROS to 100L,
        )
        // A faster interaction whose individual parts are each larger must not contribute any of them.
        val faster = mapOf(
            INTERACTION_MAX_MICROS to 500L,
            INPUT_DELAY_MICROS to 400L, INTERACTION_PROCESSING_MICROS to 50L, PRESENTATION_MICROS to 400L,
        )

        val kept = merge(slow, faster)
        assertEquals(900L, kept[INTERACTION_MAX_MICROS])
        assertEquals("breakdown must stay that of the 900us interaction", 100L, kept[INPUT_DELAY_MICROS])
        assertEquals(700L, kept[INTERACTION_PROCESSING_MICROS])
        assertEquals(100L, kept[PRESENTATION_MICROS])

        val replaced = merge(faster, slow)
        assertEquals(900L, replaced[INTERACTION_MAX_MICROS])
        assertEquals("a slower interaction must bring its whole breakdown", 100L, replaced[INPUT_DELAY_MICROS])
        assertEquals(700L, replaced[INTERACTION_PROCESSING_MICROS])
        assertEquals(100L, replaced[PRESENTATION_MICROS])
    }

    @Test fun theBreakdownIsInternallyConsistentAfterManyInteractions() {
        val samples = listOf(300L to 10L, 900L to 70L, 500L to 40L, 200L to 5L)
        val merged = samples.fold(emptyMap<Int, Long>()) { bucket, (total, delay) ->
            merge(bucket, mapOf(INTERACTION_MAX_MICROS to total, INPUT_DELAY_MICROS to delay))
        }
        assertEquals(900L, merged[INTERACTION_MAX_MICROS])
        assertEquals("the delay must belong to the slowest interaction", 70L, merged[INPUT_DELAY_MICROS])
    }

    /** A build that has never heard of a metric must not erase a newer build's history. */
    @Test fun unknownMetricsArePreservedNotDropped() {
        val merged = merge(mapOf(FRAMES to 1L), mapOf(FRAMES to 1L, 9_999 to 42L))
        assertEquals(42L, merged[9_999])
        assertEquals(2L, merged[FRAMES])
    }

    @Test fun anAbsentMeasurementLeavesTheBucketValueAlone() {
        val merged = merge(mapOf(FRAMES to 5L, LOAF_MAX_MICROS to 80L), mapOf(FRAMES to 1L))
        assertEquals(6L, merged[FRAMES])
        assertEquals(80L, merged[LOAF_MAX_MICROS])
    }

    /** Months of uptime must not wrap a counter into a negative number. */
    @Test fun countersSaturateRatherThanOverflow() {
        val merged = merge(mapOf(FRAMES to Long.MAX_VALUE - 1), mapOf(FRAMES to 100L))
        assertEquals(Long.MAX_VALUE, merged[FRAMES])
    }

    @Test fun everyMetricHasAUniqueIdAndCorrelatesOnlyWithARealLeader() {
        val ids = DashboardMetrics.METRICS.map { it.id }
        assertEquals("ids must be unique; retire, never recycle", ids.size, ids.toSet().size)
        assertEquals("names must be unique", ids.size, DashboardMetrics.METRICS.map { it.name }.toSet().size)
        DashboardMetrics.METRICS.mapNotNull { it.correlatedWith }.forEach { leader ->
            assertTrue("a correlated metric must follow a real leader", leader in ids.toSet())
            assertTrue(
                "a leader must not itself be correlated",
                DashboardMetrics.METRICS.first { it.id == leader }.correlatedWith == null,
            )
        }
    }

    /** The merge must round-trip through the payload it will be stored in. */
    @Test fun aMergedBucketSurvivesEncoding() {
        val merged = merge(mapOf(FRAMES to 3L), mapOf(FRAMES to 4L, LOAF_MAX_MICROS to 12L))
        assertEquals(merged, MetricPayload.decode(MetricPayload.encode(merged)))
    }
}
