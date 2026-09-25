package io.github.maxlyth.hapaneld.dashboard

import io.github.maxlyth.hapaneld.control.AMBIENT_RETENTION_MINUTES

// One definition per history retention limit. The write paths that normally apply each limit and the
// daily convergence pass (`EntityCatalogStore.enforceRetention`) both read these, so they cannot drift.

private const val RETENTION_MINUTE_MS = 60_000L
private const val RETENTION_DAY_MS = 24L * 60L * RETENTION_MINUTE_MS

/** A tombstoned entity is kept for 30 days after it disappeared from Home Assistant. */
internal const val TOMBSTONE_RETENTION_MS = 30L * RETENTION_DAY_MS
internal const val PERFORMANCE_RETENTION_MINUTES = EntityCatalogStore.PERFORMANCE_RETENTION_DAYS * 24L * 60L
internal const val PROXIMITY_BUCKET_MS = 5L * RETENTION_MINUTE_MS
/** Seven days of five-minute proximity buckets. */
internal const val PROXIMITY_RETENTION_BUCKETS = 7L * 24L * 12L

/** Entity traffic minutes older than one day are rolled away. */
internal fun trafficMinuteCutoff(now: Long): Long = (now - RETENTION_DAY_MS) / RETENTION_MINUTE_MS

internal fun tombstoneCutoff(now: Long): Long = now - TOMBSTONE_RETENTION_MS

/** Dashboard performance minutes are kept for [EntityCatalogStore.PERFORMANCE_RETENTION_DAYS]. */
internal fun performanceCutoffMinute(minute: Long): Long = minute - PERFORMANCE_RETENTION_MINUTES

/** Ambient light minutes are kept for the ambient model's window. */
internal fun ambientOldestMinute(nowMinute: Long): Long = nowMinute - AMBIENT_RETENTION_MINUTES

internal fun proximityCutoffBucket(now: Long): Long = (now / PROXIMITY_BUCKET_MS) - PROXIMITY_RETENTION_BUCKETS

/** One bounded delete of expired history rows: every row strictly older than [cutoff] in [table]. */
internal data class HistoryRetentionStatement(val table: String, val sql: String, val cutoff: Long)

/**
 * The tables whose rows are history the app derived and may expire. Configuration (`app_state*`),
 * learned models, overrides, dashboard definitions and live entities are not in it.
 */
internal val HISTORY_RETENTION_TABLES = setOf(
    "dashboard_entity_traffic_minute",
    "dashboard_entity",
    "entity",
    "dashboard_metric_minute",
    "ambient_lux_minute",
    "proximity_sample",
)

/**
 * Every age limit as a bounded statement, each deleting at most [chunkRows] rows per execution so the
 * write lock is released between chunks (Issue #91). An entity is removed only once it is tombstoned
 * and past its retention, and its dashboard membership only when that membership is neither pinned nor
 * excluded — the same rule a dashboard sync applies per instance.
 */
internal fun historyRetentionStatements(now: Long, chunkRows: Int): List<HistoryRetentionStatement> {
    require(chunkRows > 0)
    val nowMinute = now / RETENTION_MINUTE_MS
    val tombstones = tombstoneCutoff(now)
    // A WITHOUT ROWID table has no rowid, so it is chunked by its primary key instead.
    fun expired(table: String, column: String, cutoff: Long, key: List<String> = listOf("rowid")): HistoryRetentionStatement {
        val keys = key.joinToString(",")
        val selector = if (key.size == 1) keys else "($keys)"
        return HistoryRetentionStatement(
            table,
            "DELETE FROM $table WHERE $selector IN (SELECT $keys FROM $table WHERE $column<? LIMIT $chunkRows)",
            cutoff,
        )
    }
    return listOf(
        expired("dashboard_entity_traffic_minute", "minute", trafficMinuteCutoff(now)),
        // Membership first: deleting the entity row first would strand its memberships.
        HistoryRetentionStatement(
            "dashboard_entity",
            "${EntityCatalogStore.DELETE_MEMBERSHIP_FOR_PURGED_ENTITIES} AND e.tombstone_at>0 AND e.tombstone_at<?)",
            tombstones,
        ),
        HistoryRetentionStatement(
            "entity",
            "DELETE FROM entity WHERE rowid IN (" +
                "SELECT rowid FROM entity WHERE tombstone_at>0 AND tombstone_at<? LIMIT $chunkRows)",
            tombstones,
        ),
        expired(
            "dashboard_metric_minute",
            "minute",
            performanceCutoffMinute(nowMinute),
            key = listOf("instance", "path", "minute"),
        ),
        expired("ambient_lux_minute", "minute", ambientOldestMinute(nowMinute)),
        expired("proximity_sample", "bucket", proximityCutoffBucket(now)),
    )
}
