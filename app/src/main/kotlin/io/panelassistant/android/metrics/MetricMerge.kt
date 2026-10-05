package io.panelassistant.android.metrics

/**
 * How each measurement in a bucket combines with the next sample.
 *
 * Today this lives in a 25-column accumulating `UPDATE` where the rule is implicit in each assignment:
 * `frames=frames+?` sums, `loaf_max_micros=max(...,?)` takes a maximum, `entity_count=?` replaces. Moving
 * the metric set into a payload turns those assignments into data, so the rules have to become data too.
 * Stating them once, in one table, is also the point: adding a probe should mean adding a row here, not
 * editing SQL in a second place and hoping the two agree.
 *
 * Ids are permanent. Reusing one would make an older build misread a newer panel's history as a metric
 * it thinks it understands, which is worse than not reading it at all — so retire, never recycle.
 */
enum class MetricMerge {
    /** Latest wins: a property of the bucket, not an accumulation over it (entity count, filter state). */
    REPLACE,

    /** Accumulates over the bucket (frames, bytes, elapsed micros). */
    SUM,

    /** Worst seen in the bucket (longest task, slowest interaction). */
    MAX,
}

/**
 * One measurement's identity and merge rule.
 *
 * [correlatedWith] models the case SQL expressed as
 * `CASE WHEN ?>interaction_max_micros THEN ? ELSE ... END`: the input-delay, processing and presentation
 * figures are the *breakdown of the slowest interaction*, not independent maxima. Taking each one's own
 * maximum would report a plausible interaction that never happened — the delay from one sample and the
 * presentation from another. They therefore follow whichever sample won [correlatedWith], and are only
 * meaningful as a set.
 */
data class BucketMetric(
    val id: Int,
    val name: String,
    val merge: MetricMerge,
    val correlatedWith: Int? = null,
)

/** The dashboard-performance metric set: the 22 measurements the fixed columns used to hold. */
object DashboardMetrics {
    const val FILTER_ACTIVE = 1
    const val ENTITY_COUNT = 2
    const val SAMPLE_MS = 3
    const val FRAMES = 4
    const val PAYLOAD_BYTES = 5
    const val UPDATES = 6
    const val HYDRATION_UPDATES = 7
    const val OBSERVER_MICROS = 8
    const val DROPPED_FRAMES = 9
    const val STATE_TASK_MICROS = 10
    const val STATE_TASK_MAX_MICROS = 11
    const val INTERACTION_COUNT = 12
    const val INTERACTION_MAX_MICROS = 13
    const val INPUT_DELAY_MICROS = 14
    const val INTERACTION_PROCESSING_MICROS = 15
    const val PRESENTATION_MICROS = 16
    const val LOAF_COUNT = 17
    const val BLOCKING_MICROS = 18
    const val LOAF_MAX_MICROS = 19
    const val SCRIPT_MICROS = 20
    const val RENDER_MICROS = 21
    const val LONG_TASK_COUNT = 22

    val METRICS: List<BucketMetric> = listOf(
        BucketMetric(FILTER_ACTIVE, "filter_active", MetricMerge.REPLACE),
        BucketMetric(ENTITY_COUNT, "entity_count", MetricMerge.REPLACE),
        BucketMetric(SAMPLE_MS, "sample_ms", MetricMerge.SUM),
        BucketMetric(FRAMES, "frames", MetricMerge.SUM),
        BucketMetric(PAYLOAD_BYTES, "payload_bytes", MetricMerge.SUM),
        BucketMetric(UPDATES, "updates", MetricMerge.SUM),
        BucketMetric(HYDRATION_UPDATES, "hydration_updates", MetricMerge.SUM),
        BucketMetric(OBSERVER_MICROS, "observer_micros", MetricMerge.SUM),
        BucketMetric(DROPPED_FRAMES, "dropped_frames", MetricMerge.SUM),
        BucketMetric(STATE_TASK_MICROS, "state_task_micros", MetricMerge.SUM),
        BucketMetric(STATE_TASK_MAX_MICROS, "state_task_max_micros", MetricMerge.MAX),
        BucketMetric(INTERACTION_COUNT, "interaction_count", MetricMerge.SUM),
        BucketMetric(INTERACTION_MAX_MICROS, "interaction_max_micros", MetricMerge.MAX),
        BucketMetric(INPUT_DELAY_MICROS, "input_delay_micros", MetricMerge.MAX, correlatedWith = INTERACTION_MAX_MICROS),
        BucketMetric(
            INTERACTION_PROCESSING_MICROS, "interaction_processing_micros",
            MetricMerge.MAX, correlatedWith = INTERACTION_MAX_MICROS,
        ),
        BucketMetric(PRESENTATION_MICROS, "presentation_micros", MetricMerge.MAX, correlatedWith = INTERACTION_MAX_MICROS),
        BucketMetric(LOAF_COUNT, "loaf_count", MetricMerge.SUM),
        BucketMetric(BLOCKING_MICROS, "blocking_micros", MetricMerge.SUM),
        BucketMetric(LOAF_MAX_MICROS, "loaf_max_micros", MetricMerge.MAX),
        BucketMetric(SCRIPT_MICROS, "script_micros", MetricMerge.SUM),
        BucketMetric(RENDER_MICROS, "render_micros", MetricMerge.SUM),
        BucketMetric(LONG_TASK_COUNT, "long_task_count", MetricMerge.SUM),
    )

    private val BY_ID: Map<Int, BucketMetric> = METRICS.associateBy { it.id }

    /**
     * Folds [sample] into [bucket] under each metric's rule.
     *
     * Ids absent from [METRICS] are carried through untouched rather than dropped: a build that has never
     * heard of a metric must not silently erase a newer build's history when it happens to write the same
     * bucket. That is the whole reason the metric set is data.
     */
    fun merge(bucket: Map<Int, Long>, sample: Map<Int, Long>): Map<Int, Long> {
        val out = LinkedHashMap(bucket)
        // Correlated groups follow their leader, so the leader's outcome must be decided first.
        val leaderWins = METRICS.filter { it.correlatedWith == null }
            .associate { spec -> spec.id to wins(spec, bucket[spec.id], sample[spec.id]) }

        sample.forEach { (id, value) ->
            val spec = BY_ID[id]
            if (spec == null) {
                out[id] = value // unknown to this build; preserve rather than interpret
                return@forEach
            }
            val existing = bucket[id]
            out[id] = when {
                spec.correlatedWith != null ->
                    if (leaderWins[spec.correlatedWith] == true) value else (existing ?: value)
                spec.merge == MetricMerge.REPLACE -> value
                spec.merge == MetricMerge.SUM -> saturatingAdd(existing ?: 0L, value)
                else -> maxOf(existing ?: value, value)
            }
        }
        return out
    }

    /** Whether [sample] displaces [existing] for a leader metric, deciding its correlated followers. */
    private fun wins(spec: BucketMetric, existing: Long?, sample: Long?): Boolean = when {
        sample == null -> false
        existing == null -> true
        spec.merge == MetricMerge.MAX -> sample > existing
        else -> true
    }

    /** Months of uptime must not wrap a counter into nonsense. */
    private fun saturatingAdd(a: Long, b: Long): Long {
        val sum = a + b
        return if (((a xor sum) and (b xor sum)) < 0) Long.MAX_VALUE else sum
    }
}
