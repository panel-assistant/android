package io.panelassistant.android.storage

import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Deterministic JVM reproduction of the Issue #91 collision against real SQLite (org.xerial JDBC).
 *
 * ha-paneld runs several connection pools over one WAL database. The reporter's panel showed a
 * maintenance purge holding the write lock ≥18 s under Android's silently-applied FULL auto-vacuum
 * while a telemetry write exhausted its busy timeout and latched a false storage failure. These
 * tests reproduce the lock semantics with two real connections — the victim at `busy_timeout=0` so
 * BUSY is immediate and nothing here races wall-clock time — and prove the two mechanisms the fix
 * relies on: chunked autocommit statements release the write lock between chunks, and FULL
 * auto-vacuum flips to INCREMENTAL as a plain header change whose freelist then drains in bounded
 * `incremental_vacuum` slices. The reporter's ≥18 s timing itself is size- and hardware-dependent
 * and stays local/on-device evidence, never a CI assertion.
 */
class DatabaseBusyContentionReproTest {
    private lateinit var databasePath: String
    private lateinit var writer: Connection
    private lateinit var victim: Connection

    @Before
    fun openTwoConnectionsOnOneWalDatabase() {
        val directory = Files.createTempDirectory("busy-repro").toFile().apply { deleteOnExit() }
        databasePath = "${directory.absolutePath}/ha-paneld-repro.db"
        writer = connect()
        writer.createStatement().use { statement ->
            // Android's platform SQLite is compiled with SQLITE_DEFAULT_AUTOVACUUM=1; apply the same
            // mode explicitly before the first table so the file matches a deployed panel's database.
            statement.execute("PRAGMA auto_vacuum=FULL")
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("CREATE TABLE telemetry(id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
        }
        victim = connect()
        victim.createStatement().use { it.execute("PRAGMA busy_timeout=0") }
    }

    @After
    fun closeConnections() {
        runCatching { writer.close() }
        runCatching { victim.close() }
    }

    private fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:$databasePath")

    private fun pragmaLong(connection: Connection, name: String): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA $name").use { results ->
                assertTrue(results.next())
                results.getLong(1)
            }
        }

    private fun insertVictimRow(): Unit = victim.createStatement().use {
        it.execute("INSERT INTO telemetry(payload) VALUES('victim')")
    }

    private fun seedRows(count: Int) {
        writer.autoCommit = false
        writer.prepareStatement("INSERT INTO telemetry(payload) VALUES(?)").use { statement ->
            repeat(count) { index ->
                statement.setString(1, "row-$index-" + "x".repeat(200))
                statement.addBatch()
            }
            statement.executeBatch()
        }
        writer.commit()
        writer.autoCommit = true
    }

    @Test
    fun aWriterHoldingTheLockMakesAConcurrentWriteBusyAndTheClassifierCallsItBusy() {
        seedRows(2_000)
        writer.autoCommit = false
        writer.createStatement().use { it.execute("DELETE FROM telemetry") }
        val failure = try {
            insertVictimRow()
            fail("the victim write must not succeed while the purge transaction holds the write lock")
            return
        } catch (busy: SQLException) {
            busy
        }
        // The same classification the production latch consults: expected contention, kind BUSY.
        assertEquals(StorageDatabaseFailureKind.BUSY, classifyDatabaseFailure(failure))

        // The moment the purge commits, the identical write succeeds — transient by construction.
        writer.commit()
        writer.autoCommit = true
        insertVictimRow()
    }

    @Test
    fun chunkedAutocommitDeletesReleaseTheWriteLockBetweenChunks() {
        seedRows(2_000)
        var chunks = 0
        while (true) {
            val deleted = writer.createStatement().use { statement ->
                statement.executeUpdate(
                    "DELETE FROM telemetry WHERE rowid IN (SELECT rowid FROM telemetry WHERE payload LIKE 'row-%' LIMIT 500)",
                )
            }
            if (deleted == 0) break
            chunks++
            // Between chunks the lock is free: the zero-timeout victim writes without one retry.
            insertVictimRow()
        }
        assertTrue("the purge must actually have run in multiple chunks", chunks >= 4)
    }

    @Test
    fun fullAutoVacuumFlipsToIncrementalAsAHeaderChangeAndReclaimsInBoundedSlices() {
        assertEquals("the database must start in a deployed panel's FULL mode", 1L, pragmaLong(writer, "auto_vacuum"))

        // The flip is a plain pragma — no VACUUM — and survives a fresh connection (header change).
        writer.createStatement().use { it.execute("PRAGMA auto_vacuum=INCREMENTAL") }
        assertEquals(2L, pragmaLong(writer, "auto_vacuum"))
        val reopened = connect()
        try {
            assertEquals("the mode must persist in the database header", 2L, pragmaLong(reopened, "auto_vacuum"))
        } finally {
            reopened.close()
        }

        // Under INCREMENTAL a purge leaves pages on the freelist instead of relocating them inside
        // its own commit; bounded incremental_vacuum slices then drain the freelist monotonically.
        seedRows(5_000)
        writer.createStatement().use { it.executeUpdate("DELETE FROM telemetry WHERE payload LIKE 'row-%'") }
        writer.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        val freedPages = pragmaLong(writer, "freelist_count")
        assertTrue("an INCREMENTAL-mode purge must accumulate freelist pages, got $freedPages", freedPages > 64L)

        var remaining = freedPages
        var slices = 0
        while (remaining > 0L && slices < 1_000) {
            writer.createStatement().use { it.execute("PRAGMA incremental_vacuum(64)") }
            val now = pragmaLong(writer, "freelist_count")
            assertTrue("each bounded slice must reclaim, never grow, the freelist", now <= remaining)
            if (now == remaining) break
            remaining = now
            slices++
        }
        assertEquals("bounded slices must be able to drain the whole freelist", 0L, remaining)
        assertTrue("reclamation must have taken multiple bounded slices, got $slices", slices >= 2)
    }
}
