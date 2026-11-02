package io.panelassistant.android.dashboard

import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class EntityCatalogQueryPolicyTest {
    @Test fun apiSortPolicyAllowsOnlyDisplayedColumnsAndDirections() {
        val allowed = listOf("entity_id", "access_1h", "rate_1h_bps", "reasons", "last_access_at", "override")
        allowed.forEach { assertEquals(it, EntityCatalogSorting.key(it)) }
        assertEquals("entity_id", EntityCatalogSorting.key("update_bytes DESC; DROP TABLE entity"))
        assertEquals("asc", EntityCatalogSorting.direction("sideways"))
        assertEquals("desc", EntityCatalogSorting.direction("DESC"))
    }

    @Test fun productionSqlOrdersEveryDisplayedColumnBeforePaginationWithDeterministicEntityTieBreak() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { statement ->
                statement.execute(EntityCatalogSchema.ENTITY_TABLE_SQL)
                statement.execute(EntityCatalogSchema.DASHBOARD_ENTITY_TABLE_SQL)
                statement.execute("""INSERT INTO entity(instance,entity_id,first_seen_at,last_seen_at) VALUES
                    ('ha','sensor.z',0,0),('ha','sensor.a',0,0),('ha','sensor.m',0,0)""")
                statement.execute("""INSERT INTO dashboard_entity(
                    instance,path,entity_id,access_count,update_bytes,reasons,last_access_at,pinned,excluded) VALUES
                    ('ha','dash','sensor.z',7,25,'runtime',100,1,0),
                    ('ha','dash','sensor.a',7,25,'runtime',100,1,0),
                    ('ha','dash','sensor.m',2,90,'dashboard',300,0,1)""")
            }
            // entitiesJson uses this exact production ORDER BY fragment. Recent aggregate values
            // are fixture inputs here; aggregation itself is outside this ordering contract.
            fun ordered(key: String, direction: String, page: String = ""): List<String> {
                val orderBy = requireNotNull(EntityCatalogSorting.sqlOrder(key, direction))
                return db.createStatement().use { statement ->
                    statement.executeQuery("""SELECT e.entity_id,
                        m.access_count AS recent_access_1h,m.update_bytes/10.0 AS recent_rate_1h
                        FROM entity e LEFT JOIN dashboard_entity m
                        ON m.instance=e.instance AND m.entity_id=e.entity_id AND m.path='dash'
                        ORDER BY $orderBy $page""").use { rows ->
                        buildList { while (rows.next()) add(rows.getString(1)) }
                    }
                }
            }
            val expected = mapOf(
                "entity_id" to (listOf("sensor.a", "sensor.m", "sensor.z") to listOf("sensor.z", "sensor.m", "sensor.a")),
                "access_1h" to (listOf("sensor.m", "sensor.a", "sensor.z") to listOf("sensor.a", "sensor.z", "sensor.m")),
                "rate_1h_bps" to (listOf("sensor.a", "sensor.z", "sensor.m") to listOf("sensor.m", "sensor.a", "sensor.z")),
                "reasons" to (listOf("sensor.m", "sensor.a", "sensor.z") to listOf("sensor.a", "sensor.z", "sensor.m")),
                "last_access_at" to (listOf("sensor.a", "sensor.z", "sensor.m") to listOf("sensor.m", "sensor.a", "sensor.z")),
                "override" to (listOf("sensor.m", "sensor.a", "sensor.z") to listOf("sensor.a", "sensor.z", "sensor.m")),
            )
            for ((key, orders) in expected) {
                for ((direction, order) in listOf("asc" to orders.first, "desc" to orders.second)) {
                    assertEquals("$key $direction including the ascending entity tie break", order, ordered(key, direction))
                    assertEquals("$key $direction page follows global order", order.drop(1), ordered(key, direction, "LIMIT 2 OFFSET 1"))
                }
            }
        }
    }

    @Test fun percentileTrackingRemainsConstantSpaceAtCatalogScale() {
        val histogram = LogRankHistogram()
        repeat(100_000) { histogram.add((it + 1).toLong()) }

        assertEquals(100_000L, histogram.sampleCountForTest())
        assertEquals(256, histogram.storageSlotsForTest())
        assertTrue(histogram.rank(1) > 0.0)
        assertTrue(histogram.rank(100_000) == 1.0)
        assertTrue(histogram.rank(10) < histogram.rank(10_000))
    }

    @Test fun oversizedIncludeAndExcludeSetsRetainOnlyTheExactRequestedPage() {
        val ordered = (0 until 100_000).map { "sensor.%05d".format(it) }
        val include = ordered.filterIndexed { index, _ -> index % 2 == 0 }.toSet()
        val exclude = ordered.take(50_000).toSet()
        val expected = ordered.filter { it in include && it !in exclude }
        val collector = BoundedEntityIdPageCollector(
            limit = 100,
            offset = 12_345,
            includeIds = include,
            excludeIds = exclude,
        )

        ordered.forEach(collector::offer)

        assertEquals(25_000, collector.total)
        assertEquals(expected.drop(12_345).take(100), collector.pageIds)
        assertEquals(100, collector.retainedCountForTest())
    }

    @Test fun oversizedIdFallbackPreservesIncomingGlobalSortDirection() {
        val orderedDescending = (0 until 50_000).asSequence().map { "sensor.%05d".format(49_999 - it) }
        val exclude = (0 until 50_000 step 3).mapTo(mutableSetOf()) { "sensor.%05d".format(it) }
        val collector = BoundedEntityIdPageCollector(
            limit = 50,
            offset = 777,
            includeIds = null,
            excludeIds = exclude,
        )
        val expected = orderedDescending.filter { it !in exclude }.toList()

        orderedDescending.forEach(collector::offer)

        assertEquals(expected.size, collector.total)
        assertEquals(expected.drop(777).take(50), collector.pageIds)
        assertEquals(50, collector.retainedCountForTest())
    }

    @Test fun exportAdmissionEnforcesRowsBytesAndTimeWithoutOvershootingFooterReserve() {
        val policy = EntityExportPolicy(maxRows = 3, maxBytes = 1_024, maxDurationNanos = 100, footerReserveBytes = 100)
        assertEquals(null, exportTruncationReason(policy, rows = 2, bytesWritten = 500, nextRowBytes = 400, elapsedNanos = 50))
        assertEquals("row_limit", exportTruncationReason(policy, rows = 3, bytesWritten = 0, nextRowBytes = 0, elapsedNanos = 0))
        assertEquals("time_limit", exportTruncationReason(policy, rows = 0, bytesWritten = 0, nextRowBytes = 0, elapsedNanos = 101))
        assertEquals("byte_limit", exportTruncationReason(policy, rows = 0, bytesWritten = 500, nextRowBytes = 425, elapsedNanos = 0))
    }

    @Test fun boundedExportEnvelopeRemainsValidJsonWhenTruncatedBeforeAnyRows() {
        val document = entityExportHeader("instance-hash", "/dashboard/\"quoted\"", 123) +
            entityExportFooter(0, "byte_limit")
        val json = JSONObject(document)

        assertEquals(0, json.getJSONArray("entities").length())
        assertTrue(json.getBoolean("truncated"))
        assertEquals("byte_limit", json.getString("truncation_reason"))
        assertEquals("/dashboard/\"quoted\"", json.getString("dashboard"))
    }

    @Test fun rollupPressureConvergesInPrecisionFirstOrderAndStopsAtMeasuredCap() {
        val observedAfterTier = listOf(180L, 150L, 120L)
        var observed = 220L
        var applied = 0
        val tiers = mutableListOf<RollupPressureTier>()
        while (true) {
            val tier = RollupRetentionPolicy.nextTier(observed, softLimitBytes = 128L, applied) ?: break
            tiers += tier
            observed = observedAfterTier[applied++]
        }

        assertEquals(
            listOf(RollupPressureTier.HOURLY_DETAIL, RollupPressureTier.DAY_SUMMARY, RollupPressureTier.HOUR_SUMMARY),
            tiers,
        )
        assertEquals(120L, observed)
        assertEquals(null, RollupRetentionPolicy.nextTier(observed, 128L, applied))
    }

    @Test fun rollupPressureWindowsPreserveDayThenHourThenMinuteEvidence() {
        val now = 2L * 24 * 60 * 60_000
        val hourly = RollupRetentionPolicy.window(RollupPressureTier.HOURLY_DETAIL, now)
        val day = RollupRetentionPolicy.window(RollupPressureTier.DAY_SUMMARY, now)
        val hour = RollupRetentionPolicy.window(RollupPressureTier.HOUR_SUMMARY, now)
        val dropDay = RollupRetentionPolicy.window(RollupPressureTier.DROP_DAY_HISTORY, now)
        val dropHour = RollupRetentionPolicy.window(RollupPressureTier.DROP_HOUR_HISTORY, now)

        assertEquals("minute<?", hourly.whereSql)
        assertEquals(listOf("2820"), hourly.whereArgs.toList())
        assertEquals(2819L, day.targetMinute)
        assertEquals("minute>=? AND minute<?", hour.whereSql)
        assertEquals(listOf("2820", "2879"), hour.whereArgs.toList())
        assertEquals(2878L, hour.targetMinute)
        assertTrue(dropDay.drop)
        assertEquals(listOf("2820"), dropDay.whereArgs.toList())
        assertTrue(dropHour.drop)
        assertEquals(listOf("2879"), dropHour.whereArgs.toList())
    }

    @Test fun oneSnapshotLoadServesAllTablesInsideRefreshWindow() {
        val cache = BoundedSnapshotCache<String, List<Int>>(windowMs = ENTITY_RANKING_REFRESH_MS, maxEntries = 4)
        var aggregateQueries = 0
        fun load(now: Long): List<Int> = cache.get("instance/dashboard", now) { aggregateQueries++; listOf(aggregateQueries) }

        val subscribed = load(20_001)
        val suggested = load(120_000)
        val review = load(320_000)

        assertSame(subscribed, suggested)
        assertSame(subscribed, review)
        assertEquals(1, aggregateQueries)
        assertEquals(listOf(2), load(320_001))
        assertEquals(2, aggregateQueries)
    }

    @Test fun explicitInvalidationDropsADeletedEvidenceSnapshot() {
        val cache = BoundedSnapshotCache<String, String>(windowMs = 10_000, maxEntries = 4)
        var loads = 0
        fun load() = cache.get("target", 1_000) { "snapshot-${++loads}" }
        assertEquals("snapshot-1", load())
        cache.invalidate("target")
        assertEquals("snapshot-2", load())
    }

    @Test fun snapshotCacheIsStrictlyBoundedAcrossInstancesAndDashboards() {
        val cache = BoundedSnapshotCache<String, String>(windowMs = 10_000, maxEntries = 3)
        repeat(20) { cache.get("target-$it", 1_000) { "snapshot-$it" } }
        assertEquals(3, cache.sizeForTest())
    }

    @Test fun accessingOneKeyPurgesEveryExpiredSnapshotAndClockRollback() {
        val cache = BoundedSnapshotCache<String, String>(windowMs = 10_000, maxEntries = 20)
        repeat(16) { cache.get("target-$it", 1_000) { "snapshot-$it" } }
        assertEquals(16, cache.sizeForTest())
        cache.get("current", 11_000) { "current" }
        assertEquals(1, cache.sizeForTest())

        cache.get("future", 50_000) { "future" }
        cache.get("rollback", 5_000) { "rollback" }
        assertEquals(1, cache.sizeForTest())
    }
}
