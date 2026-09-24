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
 * comes back short) against real SQLite. Only rows past their own age limit go; configuration, live
 * entities, pinned or excluded memberships and anything within its window stay.
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
            "CREATE TABLE entity(instance TEXT, entity_id TEXT, tombstone_at INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE dashboard_entity(instance TEXT, entity_id TEXT, pinned INTEGER, excluded INTEGER)",
            "CREATE TABLE dashboard_entity_traffic_minute(entity_id TEXT, minute INTEGER)",
            "CREATE TABLE dashboard_metric_minute(path TEXT, minute INTEGER)",
            "CREATE TABLE ambient_lux_minute(source_id TEXT, minute INTEGER)",
            "CREATE TABLE proximity_sample(fingerprint TEXT, bucket INTEGER)",
            "CREATE TABLE proximity_model(fingerprint TEXT, updated_at INTEGER)",
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

    /** Exactly the store's `chunkedWrite` loop. */
    private fun enforce(chunkRows: Int = 2) {
        historyRetentionStatements(now, chunkRows).forEach { statement ->
            while (exec(statement.sql, statement.cutoff) >= chunkRows) Unit
        }
    }

    @Test fun expiredTrafficMinutesGoAndTheLastDayStays() {
        (1..5).forEach { exec("INSERT INTO dashboard_entity_traffic_minute VALUES(?,?)", "old-$it", nowMinute - 2 * 24 * 60 - it) }
        exec("INSERT INTO dashboard_entity_traffic_minute VALUES(?,?)", "recent", nowMinute - 60)
        exec("INSERT INTO dashboard_entity_traffic_minute VALUES(?,?)", "edge", trafficMinuteCutoff(now))

        enforce()

        assertEquals(setOf("recent", "edge"), ids("dashboard_entity_traffic_minute", "entity_id"))
    }

    @Test fun onlyTombstonedEntitiesPastRetentionAndTheirUnpinnedMembershipsGo() {
        val expired = now - TOMBSTONE_RETENTION_MS - day
        val recent = now - day
        exec("INSERT INTO entity VALUES('ha','sensor.gone',?)", expired)
        exec("INSERT INTO entity VALUES('ha','sensor.recently_gone',?)", recent)
        exec("INSERT INTO entity VALUES('ha','sensor.live',0)")
        exec("INSERT INTO entity VALUES('ha','sensor.pinned_gone',?)", expired)
        exec("INSERT INTO dashboard_entity VALUES('ha','sensor.gone',0,0)")
        exec("INSERT INTO dashboard_entity VALUES('ha','sensor.recently_gone',0,0)")
        exec("INSERT INTO dashboard_entity VALUES('ha','sensor.live',0,0)")
        exec("INSERT INTO dashboard_entity VALUES('ha','sensor.pinned_gone',1,0)")

        enforce()

        assertEquals(setOf("sensor.recently_gone", "sensor.live"), ids("entity", "entity_id"))
        assertEquals(
            "a pinned choice is the operator's configuration, never history",
            setOf("sensor.recently_gone", "sensor.live", "sensor.pinned_gone"),
            ids("dashboard_entity", "entity_id"),
        )
    }

    @Test fun performanceAmbientAndProximityHistoryKeepTheirOwnWindows() {
        exec("INSERT INTO dashboard_metric_minute VALUES('old',?)", performanceCutoffMinute(nowMinute) - 1)
        exec("INSERT INTO dashboard_metric_minute VALUES('kept',?)", performanceCutoffMinute(nowMinute))
        exec("INSERT INTO ambient_lux_minute VALUES('old',?)", nowMinute - AMBIENT_RETENTION_MINUTES - 1)
        exec("INSERT INTO ambient_lux_minute VALUES('kept',?)", nowMinute - 60)
        exec("INSERT INTO proximity_sample VALUES('old',?)", proximityCutoffBucket(now) - 1)
        exec("INSERT INTO proximity_sample VALUES('kept',?)", proximityCutoffBucket(now))

        enforce()

        assertEquals(setOf("kept"), ids("dashboard_metric_minute", "path"))
        assertEquals(setOf("kept"), ids("ambient_lux_minute", "source_id"))
        assertEquals(setOf("kept"), ids("proximity_sample", "fingerprint"))
    }

    @Test fun configurationAndLearnedModelsAreNeverTouched() {
        exec("INSERT INTO app_state VALUES('config','home_dashboard','lovelace',0)")
        exec("INSERT INTO proximity_model VALUES('model',0)")

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
        (1..7).forEach { exec("INSERT INTO dashboard_entity_traffic_minute VALUES(?,?)", "old-$it", 0L) }
        enforce()
        val second = historyRetentionStatements(now, 2).sumOf { exec(it.sql, it.cutoff) }
        assertEquals("retention converges", 0, second)
    }
}
