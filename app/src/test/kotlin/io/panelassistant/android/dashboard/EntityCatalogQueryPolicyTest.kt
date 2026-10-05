package io.panelassistant.android.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class EntityCatalogQueryPolicyTest {
    private fun row(
        id: String,
        access: Long = 0,
        rate: Double = 0.0,
        reasons: String = "",
        last: Long = 0,
        override: String = "auto",
    ) = EntitySortProjection(id, access, rate, reasons, last, override)

    @Test fun apiSortPolicyAllowsOnlyDisplayedColumnsAndDirections() {
        val allowed = listOf("entity_id", "access_1h", "rate_1h_bps", "reasons", "last_access_at", "override")
        allowed.forEach { assertEquals(it, EntityCatalogSorting.key(it)) }
        assertEquals("entity_id", EntityCatalogSorting.key("update_bytes DESC; DROP TABLE entity"))
        assertEquals("asc", EntityCatalogSorting.direction("sideways"))
        assertEquals("desc", EntityCatalogSorting.direction("DESC"))
        assertTrue(EntityCatalogSorting.sqlOrder("entity_id", "asc")!!.startsWith("e.entity_id"))
        assertTrue(EntityCatalogSorting.sqlOrder("last_access_at", "desc")!!.contains("DESC"))
        assertTrue(EntityCatalogSorting.sqlOrder("access_1h", "asc")!!.startsWith("recent_access_1h ASC"))
        assertTrue(EntityCatalogSorting.sqlOrder("rate_1h_bps", "desc")!!.startsWith("recent_rate_1h DESC"))
    }

    @Test fun everyDisplayedColumnSortsBothDirectionsWithDeterministicEntityTieBreak() {
        val rows = listOf(
            row("sensor.z", 7, 2.5, "runtime", 100, "pinned"),
            row("sensor.a", 7, 2.5, "runtime", 100, "pinned"),
            row("sensor.m", 2, 9.0, "dashboard", 300, "excluded"),
        )
        for (key in listOf("entity_id", "access_1h", "rate_1h_bps", "reasons", "last_access_at", "override")) {
            for (direction in listOf("asc", "desc")) {
                val sorted = rows.sortedWith(EntityCatalogSorting.comparator(key, direction) { it })
                // Equal primary values are stable across refreshes/pages regardless of primary direction.
                if (key != "entity_id") assertTrue(sorted.indexOfFirst { it.entityId == "sensor.a" } < sorted.indexOfFirst { it.entityId == "sensor.z" })
                assertEquals(rows.toSet(), sorted.toSet())
            }
        }
    }

    @Test fun paginationIsAppliedAfterGlobalSort() {
        val rows = (0 until 250).map { row("sensor.%03d".format(it), access = ((it * 37) % 91).toLong()) }
        val globallySorted = rows.sortedWith(EntityCatalogSorting.comparator("access_1h", "desc") { it })

        val secondPage = globallySorted.drop(100).take(100)

        assertEquals(globallySorted.subList(100, 200), secondPage)
        assertTrue(secondPage.first() !in globallySorted.take(100))
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
