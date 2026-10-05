package io.panelassistant.android.storage

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic JVM reproduction of the Issue #91 *reclamation* residual against real SQLite
 * (org.xerial JDBC), pinning the facts the corrected reclamation depends on.
 *
 * The reporter ran the bounded BUSY fix for 2.75 days with no recurring latch, then reported two
 * things the earlier lane had not accounted for: roughly 115 MB of freelist pages that never
 * returned to the filesystem, and `database_failure` events that named no operation. Both follow
 * from one mistake — the belief that `PRAGMA incremental_vacuum` returns no rows — plus one
 * omission, that the pragma alone returns no bytes in WAL mode.
 *
 * `incremental_vacuum` yields one **zero-column** result row per freed page, so an executor that
 * does not iterate the statement stops after the first step. That is what these tests measure
 * directly: [aNonIteratingExecutorFreesOnlyOnePagePerCall] frees exactly one page per call no matter
 * how large a slice was asked for, which is the whole reason 115 MB stayed put.
 *
 * What transfers to the panel and what does not: SQLite is the same library either side, so the
 * one-page-per-step behavior, the WAL non-truncation and the reader interaction hold on hardware
 * unchanged. Android's framework layer differs in one way that makes the defect louder rather than
 * quieter — `SQLiteDatabase.execSQL` routes to `executeNonQuery`, which *throws*
 * `"Queries can be performed using SQLiteDatabase query or rawQuery methods only."` on the first
 * `SQLITE_ROW` instead of stopping quietly, and that message classifies `UNKNOWN`, takes zero
 * retries and latches. `rawQuery` is the row-tolerant path and handles a zero-column statement
 * correctly, stepping and counting every row. Both framework behaviors are pinned at the source
 * level by `EntityCatalogStorageHealthContractTest`.
 *
 * The JDBC driver has no iterating path for a zero-column statement at all — `executeQuery` rejects
 * it outright — which is why the previous lane's repro, written on `execute()`, watched a drain
 * advance one page at a time and read that as success.
 */
class DatabaseReclamationReproTest {
    private lateinit var databaseFile: File
    private lateinit var owner: Connection

    @Before
    fun createWalDatabase() {
        val directory = Files.createTempDirectory("reclaim-repro").toFile().apply { deleteOnExit() }
        databaseFile = File(directory, "ha-paneld-repro.db")
        owner = open()
    }

    @After
    fun closeConnection() {
        runCatching { owner.close() }
    }

    private fun open(): Connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.path}")

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.pragma(name: String): Long = createStatement().use { statement ->
        statement.executeQuery("PRAGMA $name").use { results ->
            assertTrue(results.next())
            results.getLong(1)
        }
    }

    /** One non-iterating call, the JDBC analogue of Android's `execSQL`. */
    private fun Connection.reclaimWithoutIterating(pages: Long) =
        createStatement().use { it.execute("PRAGMA incremental_vacuum($pages)") }

    /** Drains by repeated non-iterating calls, since JDBC cannot iterate a zero-column statement. */
    private fun drainFreelist(): Long {
        var freed = 0L
        while (owner.pragma("freelist_count") > 0L) {
            val before = owner.pragma("freelist_count")
            owner.reclaimWithoutIterating(256L)
            val after = owner.pragma("freelist_count")
            if (after >= before) break
            freed += before - after
        }
        return freed
    }

    private fun seed(autoVacuum: String, rows: Int = 600) {
        owner.exec("PRAGMA auto_vacuum=$autoVacuum")
        owner.exec("PRAGMA journal_mode=WAL")
        owner.exec("CREATE TABLE telemetry(id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
        owner.autoCommit = false
        owner.prepareStatement("INSERT INTO telemetry(payload) VALUES(?)").use { statement ->
            repeat(rows) {
                statement.setString(1, "x".repeat(2_000))
                statement.addBatch()
            }
            statement.executeBatch()
        }
        owner.commit()
        owner.autoCommit = true
        owner.exec("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    private fun emptyTheTable() {
        owner.exec("DELETE FROM telemetry")
        owner.exec("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    @Test fun aNonIteratingExecutorFreesOnlyOnePagePerCall() {
        seed("INCREMENTAL")
        emptyTheTable()
        val freelistBefore = owner.pragma("freelist_count")
        assertTrue("the fixture must build a freelist far larger than one slice", freelistBefore > 256L)

        owner.reclaimWithoutIterating(256L)

        // This is the defect in one assertion. A 256-page slice frees ONE page, because the pragma
        // yields a row per page and an executor that does not iterate stops at the first step. On
        // Android the same first step throws instead, so the pass latched rather than crawling.
        assertEquals(
            "a non-iterating executor must be seen to free exactly one page per call",
            1L,
            freelistBefore - owner.pragma("freelist_count"),
        )

        // Not a slice-size problem: asking for more does not free more.
        val beforeLargeSlice = owner.pragma("freelist_count")
        owner.reclaimWithoutIterating(4_096L)
        assertEquals(
            "a larger slice must not change the outcome — iteration is what was missing",
            1L,
            beforeLargeSlice - owner.pragma("freelist_count"),
        )
    }

    @Test fun aZeroColumnStatementHasNoIteratingJdbcPath() {
        seed("INCREMENTAL")
        emptyTheTable()
        // Pins why the previous lane's repro could not have caught this, so nobody re-derives the
        // same false comfort from a green JDBC drain.
        try {
            owner.createStatement().use { statement ->
                statement.executeQuery("PRAGMA incremental_vacuum(64)").use { it.next() }
            }
            throw AssertionError("expected JDBC to reject a zero-column statement")
        } catch (rejected: SQLException) {
            assertTrue(
                "JDBC must reject it for having no result set, not for some other reason: ${rejected.message}",
                rejected.message.orEmpty().contains("does not return ResultSet"),
            )
        }
    }

    @Test fun drainingTheFreelistReturnsNoBytesUntilTheWalIsCheckpointed() {
        seed("INCREMENTAL")
        emptyTheTable()
        val bytesBeforeDrain = databaseFile.length()
        val pagesBeforeDrain = owner.pragma("page_count")

        assertTrue("the drain must free pages", drainFreelist() > 0L)
        assertTrue(
            "the drain must shrink the logical page count",
            owner.pragma("page_count") < pagesBeforeDrain,
        )

        // The pragma moved page_count but left the file alone. Reporting success on the strength of
        // page_count would claim bytes the filesystem never gave back.
        assertEquals(
            "incremental_vacuum alone must not be credited with returning filesystem bytes",
            bytesBeforeDrain,
            databaseFile.length(),
        )

        owner.exec("PRAGMA wal_checkpoint(PASSIVE)")
        assertTrue(
            "the checkpoint is what returns the space",
            databaseFile.length() < bytesBeforeDrain,
        )
    }

    @Test fun aReaderPinnedBeforeTheDrainDefersTheBytesAndTheNextPassRecoversThem() {
        seed("INCREMENTAL")
        emptyTheTable()
        val reader = open()
        val bytesBeforeCheckpoint: Long
        try {
            // A snapshot taken BEFORE the reclamation pins the backfill. One taken after does not,
            // so it is the reader's timing rather than its presence that defers the bytes.
            reader.autoCommit = false
            reader.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM telemetry").use { assertTrue(it.next()) }
            }

            drainFreelist()
            bytesBeforeCheckpoint = databaseFile.length()

            val busy = owner.createStatement().use { statement ->
                statement.executeQuery("PRAGMA wal_checkpoint(PASSIVE)").use { results ->
                    assertTrue(results.next())
                    results.getLong(1)
                }
            }
            // The busy flag stays CLEAR while nothing is backfilled, so the checkpoint's own result
            // row cannot tell a deferred pass from a successful one. Only the file length can, which
            // is why reclamation measures the file rather than trusting the pragma.
            assertEquals("a pinned backfill is not reported as busy", 0L, busy)
            assertEquals(
                "a pinned backfill must return no bytes",
                bytesBeforeCheckpoint,
                databaseFile.length(),
            )

            reader.commit()
        } finally {
            runCatching { reader.close() }
        }

        owner.exec("PRAGMA wal_checkpoint(PASSIVE)")
        assertTrue(
            "once the reader moves on, an ordinary later pass returns the space — no recovery path needed",
            databaseFile.length() < bytesBeforeCheckpoint,
        )
    }

    @Test fun autoVacuumNoneReclaimsNothingAndMustNotBeConvertedImplicitly() {
        seed("NONE")
        emptyTheTable()
        assertEquals("the fixture must really be in NONE", 0L, owner.pragma("auto_vacuum"))
        val freelistBefore = owner.pragma("freelist_count")
        val bytesBefore = databaseFile.length()
        assertTrue("the fixture must build a freelist", freelistBefore > 0L)

        // Silent, and correctly so: converting NONE needs a full VACUUM, whose temporary-space
        // demand can worsen the low-space incident reclamation exists to relieve. The gap this lane
        // closes is that nothing told an operator reclamation was unavailable — hence the
        // auto_vacuum mode now carried on every storage surface.
        owner.reclaimWithoutIterating(256L)
        assertEquals("NONE must reclaim nothing", freelistBefore, owner.pragma("freelist_count"))
        assertEquals("NONE must not shrink the file", bytesBefore, databaseFile.length())
        assertEquals("NONE must not be converted as a side effect", 0L, owner.pragma("auto_vacuum"))
    }

    @Test fun aDatabaseWithNoReclaimablePagesChangesNothing() {
        seed("INCREMENTAL", rows = 32)
        // No DELETE, so nothing is on the freelist. A pass here must be a clean no-op rather than a
        // loop with nothing to do — the production loop treats a short slice as "SQLite ran out".
        assertEquals(0L, owner.pragma("freelist_count"))
        val bytesBefore = databaseFile.length()
        val pagesBefore = owner.pragma("page_count")

        owner.reclaimWithoutIterating(256L)

        assertEquals(0L, owner.pragma("freelist_count"))
        assertEquals(pagesBefore, owner.pragma("page_count"))
        assertEquals(bytesBefore, databaseFile.length())
    }

    @Test fun theFullToIncrementalFlipSurvivesReconnectionAndThenReclaims() {
        seed("FULL")
        assertEquals("Android creates the database in FULL", 1L, owner.pragma("auto_vacuum"))
        owner.exec("PRAGMA auto_vacuum=INCREMENTAL")
        owner.close()

        owner = open()
        assertEquals("the flip is a header change and must persist", 2L, owner.pragma("auto_vacuum"))
        emptyTheTable()
        val freelistBefore = owner.pragma("freelist_count")
        assertTrue("a flipped database accumulates a freelist instead of truncating eagerly",
            freelistBefore > 0L)

        owner.reclaimWithoutIterating(64L)
        assertNotEquals(
            "a flipped database must then actually reclaim",
            freelistBefore,
            owner.pragma("freelist_count"),
        )
    }
}
