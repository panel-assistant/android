package io.github.maxlyth.hapaneld.dashboard

import io.github.maxlyth.hapaneld.control.AMBIENT_RETENTION_MINUTES
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The history pruning class, run as the store runs it (each bounded statement repeated until a chunk
 * comes back short) against real SQLite and the store's real `CREATE TABLE` statements — one history
 * table is `WITHOUT ROWID`, which a hand-written fixture would hide. Only rows past their own age limit
 * go; configuration, live entities, pinned or excluded memberships and anything within its window stay.
 */
class HistoryRetentionTest {
    private lateinit var directory: File
    private lateinit var db: Connection

    private val minute = 60_000L
    private val day = 24L * 60L * minute
    private val now = 1_800_000_000_000L
    private val nowMinute = now / minute

    @Before fun createDatabase() {
        directory = Files.createTempDirectory("history-retention").toFile()
        db = DriverManager.getConnection("jdbc:sqlite:${File(directory, "catalog.db").path}")
        listOf(
            "CREATE TABLE app_state(namespace TEXT, state_key TEXT, value_text TEXT, updated_at INTEGER)",
            EntityCatalogStore.ENTITY_TABLE_SQL,
            EntityCatalogStore.DASHBOARD_TABLE_SQL,
            EntityCatalogStore.DASHBOARD_ENTITY_TABLE_SQL,
            EntityCatalogStore.DASHBOARD_ENTITY_TRAFFIC_TABLE_SQL,
            EntityCatalogStore.DASHBOARD_METRIC_TABLE_SQL,
            EntityCatalogStore.AMBIENT_HISTORY_TABLE_SQL,
            EntityCatalogStore.PROXIMITY_MODEL_TABLE_SQL,
            EntityCatalogStore.PROXIMITY_SAMPLE_TABLE_SQL,
        ).forEach(::exec)
    }

    @After fun closeDatabase() {
        runCatching { db.close() }
        directory.deleteRecursively()
    }

    private fun exec(sql: String, vararg args: Any?): Int = db.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }

    private fun ids(table: String, column: String): Set<String> =
        db.createStatement().use { statement ->
            statement.executeQuery("SELECT $column FROM $table").use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            }
        }

    private fun traffic(id: String, minute: Long) =
        exec("INSERT INTO dashboard_entity_traffic_minute(instance,path,entity_id,minute) VALUES('ha','lovelace',?,?)", id, minute)
    private fun entity(id: String, tombstoneAt: Long) =
        exec("INSERT INTO entity(instance,entity_id,first_seen_at,last_seen_at,tombstone_at) VALUES('ha',?,0,0,?)", id, tombstoneAt)
    private fun membership(id: String, pinned: Int) =
        exec("INSERT INTO dashboard_entity(instance,path,entity_id,pinned,excluded) VALUES('ha','lovelace',?,?,0)", id, pinned)
    private fun metric(path: String, minute: Long) =
        exec("INSERT INTO dashboard_metric_minute(instance,path,minute,payload) VALUES('ha',?,?,x'00')", path, minute)
    private fun ambient(source: String, minute: Long) =
        exec("INSERT INTO ambient_lux_minute(context_id,source_id,minute) VALUES('ctx',?,?)", source, minute)
    private fun proximity(fingerprint: String, bucket: Long) = exec(
        "INSERT INTO proximity_sample(fingerprint,bucket,sample_count,raw_min,raw_max,raw_sum,raw_sum_squares) " +
            "VALUES(?,?,1,0,0,0,0)",
        fingerprint, bucket,
    )

    /** Exactly the store's `chunkedWrite` loop. */
    private fun enforce(chunkRows: Int = 2) {
        historyRetentionStatements(now, chunkRows).forEach { statement ->
            while (exec(statement.sql, statement.cutoff) >= chunkRows) Unit
        }
    }

    @Test fun expiredTrafficMinutesGoAndTheLastDayStays() {
        (1..5).forEach { traffic("old-$it", nowMinute - 2 * 24 * 60 - it) }
        traffic("recent", nowMinute - 60)
        traffic("edge", trafficMinuteCutoff(now))

        enforce()

        assertEquals(setOf("recent", "edge"), ids("dashboard_entity_traffic_minute", "entity_id"))
    }

    @Test fun onlyTombstonedEntitiesPastRetentionAndTheirUnpinnedMembershipsGo() {
        val expired = now - TOMBSTONE_RETENTION_MS - day
        val recent = now - day
        entity("sensor.gone", expired)
        entity("sensor.recently_gone", recent)
        entity("sensor.live", 0L)
        entity("sensor.pinned_gone", expired)
        membership("sensor.gone", pinned = 0)
        membership("sensor.recently_gone", pinned = 0)
        membership("sensor.live", pinned = 0)
        membership("sensor.pinned_gone", pinned = 1)

        enforce()

        assertEquals(setOf("sensor.recently_gone", "sensor.live"), ids("entity", "entity_id"))
        assertEquals(
            "a pinned choice is the operator's configuration, never history",
            setOf("sensor.recently_gone", "sensor.live", "sensor.pinned_gone"),
            ids("dashboard_entity", "entity_id"),
        )
    }

    @Test fun performanceAmbientAndProximityHistoryKeepTheirOwnWindows() {
        (1..5).forEach { metric("old-$it", performanceCutoffMinute(nowMinute) - it) }
        metric("kept", performanceCutoffMinute(nowMinute))
        ambient("old", nowMinute - AMBIENT_RETENTION_MINUTES - 1)
        ambient("kept", nowMinute - 60)
        proximity("old", proximityCutoffBucket(now) - 1)
        proximity("kept", proximityCutoffBucket(now))

        enforce()

        assertEquals(setOf("kept"), ids("dashboard_metric_minute", "path"))
        assertEquals(setOf("kept"), ids("ambient_lux_minute", "source_id"))
        assertEquals(setOf("kept"), ids("proximity_sample", "fingerprint"))
    }

    @Test fun configurationAndLearnedModelsAreNeverTouched() {
        exec("INSERT INTO app_state VALUES('config','home_dashboard','lovelace',0)")
        exec("INSERT INTO proximity_model(fingerprint,algorithm_version,snapshot_json,updated_at) VALUES('model',1,'{}',0)")

        enforce()

        assertEquals(setOf("home_dashboard"), ids("app_state", "state_key"))
        assertEquals(setOf("model"), ids("proximity_model", "fingerprint"))
        historyRetentionStatements(now, 1_000).forEach { statement ->
            assertTrue("${statement.table} is not a history table", statement.table in HISTORY_RETENTION_TABLES)
            assertTrue(
                "every statement deletes only from its own history table",
                statement.sql.startsWith("DELETE FROM ${statement.table} "),
            )
        }
    }

    @Test fun aSecondRunFindsNothingLeftToPrune() {
        (1..7).forEach { traffic("old-$it", 0L) }
        (1..7).forEach { metric("old-$it", 0L) }
        enforce()
        val second = historyRetentionStatements(now, 2).sumOf { exec(it.sql, it.cutoff) }
        assertEquals("retention converges", 0, second)
    }
}
