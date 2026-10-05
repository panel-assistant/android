package io.panelassistant.android.storage

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

/**
 * Deterministic JVM measurement, against real SQLite (org.xerial JDBC), of the facts a gated full
 * `VACUUM` admission policy depends on: how much disk a full VACUUM really takes in WAL mode, what
 * converts an `auto_vacuum=NONE` database, and how a pinned reader shows up to the checkpoint and to
 * the VACUUM itself.
 *
 * Every fixture runs with `PRAGMA temp_store=MEMORY`, because Android's platform SQLite is built with
 * `SQLITE_TEMP_STORE=3` — temporary storage is always in memory there, whatever a pragma asks for.
 * That matters here more than anywhere: VACUUM builds its compacted copy in a temporary database, so
 * on the panel that copy costs RAM, never disk. What costs disk is the second half of the operation,
 * where the compacted image is written back through the pager — and in WAL mode that means appended
 * to the `-wal` file while the main file stays exactly as it was until a checkpoint.
 *
 * The measured shape, which the assertions pin exactly rather than approximately:
 *
 * - The WAL grows by one frame per page of the COMPACTED database — `page_count_after * (page_size +
 *   24)`, plus the 32-byte WAL header when the log starts empty. That is bounded by the live pages
 *   before the VACUUM, `(page_count - freelist_count) * page_size`, scaled by the frame overhead, and
 *   is often far below it, because VACUUM also repacks half-empty pages that never reached the
 *   freelist.
 * - The main file does not move during the VACUUM. It shrinks only when a checkpoint backfills it,
 *   which is also when the WAL can be truncated.
 * - So the peak on-disk footprint is `main + wal + growth`. In the worst case — a dense database with
 *   nothing to reclaim — growth slightly EXCEEDS the main file, by the 24-byte frame header per page.
 *   "growth never exceeds main + wal" is therefore false as a premise, but a required headroom of
 *   `2 * (main + wal)` still covers it with a margin of roughly the whole database.
 *
 * What transfers to the panel: the library is the same either side, so the WAL arithmetic and the
 * locking outcomes hold unchanged. Only the exception type differs — JDBC surfaces `SQLITE_BUSY` as
 * [SQLiteException] with [SQLiteErrorCode.SQLITE_BUSY], where Android's framework maps the same
 * result code to its own locked-database exception.
 */
class FullVacuumSpaceReproTest {
    private lateinit var directory: File
    private lateinit var databaseFile: File
    private lateinit var walFile: File
    private lateinit var owner: Connection

    @Before
    fun createDatabase() {
        directory = Files.createTempDirectory("vacuum-space-repro").toFile().apply { deleteOnExit() }
        databaseFile = File(directory, "ha-paneld-repro.db")
        walFile = File(directory, "ha-paneld-repro.db-wal")
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

    private fun Connection.count(): Long = createStatement().use { statement ->
        statement.executeQuery("SELECT count(*) FROM telemetry").use { results ->
            assertTrue(results.next())
            results.getLong(1)
        }
    }

    private data class Checkpoint(val busy: Long, val log: Long, val checkpointed: Long)

    private fun Connection.truncate(): Checkpoint = createStatement().use { statement ->
        statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { results ->
            assertTrue(results.next())
            Checkpoint(results.getLong(1), results.getLong(2), results.getLong(3))
        }
    }

    /**
     * An `auto_vacuum=NONE` WAL database with 2,000-byte rows (two to a 4 KiB page), optionally
     * thinned by [delete] and left with its WAL either truncated or still carrying the delete.
     */
    private fun seed(rows: Int, delete: String? = null, checkpointAfterDelete: Boolean = true) {
        owner.exec("PRAGMA auto_vacuum=NONE")
        owner.exec("PRAGMA journal_mode=WAL")
        owner.exec("PRAGMA temp_store=MEMORY")
        // Isolates the VACUUM's own write from the commit-time auto-checkpoint, which would otherwise
        // backfill and shrink the main file before it could be measured.
        owner.exec("PRAGMA wal_autocheckpoint=0")
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
        if (delete != null) {
            owner.exec("DELETE FROM telemetry WHERE $delete")
            if (checkpointAfterDelete) owner.exec("PRAGMA wal_checkpoint(TRUNCATE)")
        }
        assertEquals("the fixture must really be in NONE", 0L, owner.pragma("auto_vacuum"))
        assertEquals("the fixture must hold temp storage in memory", 2L, owner.pragma("temp_store"))
    }

    private class Footprint(val main: Long, val wal: Long) {
        val total get() = main + wal
    }

    private fun footprint() = Footprint(databaseFile.length(), walFile.length())

    /** Runs VACUUM while sampling `main + wal`, so a transient peak above the end state would show. */
    private fun vacuumSamplingPeak(): Long {
        val peak = AtomicLong(0L)
        val sampling = AtomicBoolean(true)
        val sampler = Thread {
            while (sampling.get()) peak.accumulateAndGet(databaseFile.length() + walFile.length(), ::maxOf)
        }
        sampler.start()
        try {
            owner.exec("VACUUM")
        } finally {
            sampling.set(false)
            sampler.join()
        }
        return maxOf(peak.get(), footprint().total)
    }

    @Test fun aFullVacuumAppendsTheCompactedImageToTheWalAndLeavesTheMainFileUntilCheckpoint() {
        // Every other row deleted from every page, plus the upper half dropped outright: a large
        // freelist AND a large body of half-empty pages that only VACUUM can repack.
        seed(rows = 6_000, delete = "id % 4 != 0")
        val pageSize = owner.pragma("page_size")
        val livePagesBefore = owner.pragma("page_count") - owner.pragma("freelist_count")
        assertTrue("the fixture must carry a large freelist", owner.pragma("freelist_count") > 1_000L)
        val before = footprint()
        assertEquals("the fixture must start with an empty WAL", 0L, before.wal)

        val peak = vacuumSamplingPeak()

        val after = footprint()
        val pagesAfter = owner.pragma("page_count")
        assertEquals("VACUUM empties the freelist", 0L, owner.pragma("freelist_count"))
        assertEquals("in WAL mode the main file does not move during VACUUM", before.main, after.main)
        // The exact growth: one frame (page + 24-byte frame header) per page of the COMPACTED
        // database, plus the 32-byte WAL header.
        assertEquals(
            "the WAL grows by exactly the compacted image",
            pagesAfter * (pageSize + 24) + 32,
            after.wal,
        )
        assertTrue(
            "the compacted image is bounded by the live pages before the VACUUM",
            pagesAfter <= livePagesBefore,
        )
        assertTrue("repacking beats the freelist alone here", pagesAfter < livePagesBefore)
        assertEquals("no transient peak exceeds the end state", after.total, peak)
        assertTrue(
            "with a freelist the growth stays well under the main file",
            after.wal - before.wal < before.main,
        )

        val checkpoint = owner.truncate()
        assertEquals("an uncontended TRUNCATE completes", Checkpoint(0L, 0L, 0L), checkpoint)
        assertEquals("the checkpoint is what shrinks the main file", pagesAfter * pageSize, databaseFile.length())
        assertEquals("and TRUNCATE empties the WAL", 0L, walFile.length())
    }

    @Test fun aDenseDatabaseIsTheWorstCaseAndItsWalGrowthExceedsTheMainFile() {
        seed(rows = 3_000)
        val pageSize = owner.pragma("page_size")
        val pages = owner.pragma("page_count")
        assertEquals("the fixture must have nothing to reclaim", 0L, owner.pragma("freelist_count"))
        val before = footprint()

        val peak = vacuumSamplingPeak()

        val growth = walFile.length() - before.wal
        assertEquals("a dense database compacts to itself", pages, owner.pragma("page_count"))
        assertEquals(pages * (pageSize + 24) + 32, growth)
        // This is why "peak growth <= main + wal" cannot be taken as a premise: the frame headers
        // alone push the growth past the main file when nothing is reclaimable.
        assertTrue(
            "the worst-case growth exceeds main + wal",
            growth > before.total,
        )
        assertEquals("the peak is the original files plus the whole compacted image", before.total + growth, peak)
        assertTrue(
            "a 2 * (main + wal) headroom still covers the worst case",
            growth <= 2 * before.total,
        )
    }

    @Test fun anUncheckpointedWalIsAppendedToNotReused() {
        // The delete is left in the WAL, as it would be on a panel whose last checkpoint was blocked.
        seed(rows = 6_000, delete = "id % 4 != 0", checkpointAfterDelete = false)
        val pageSize = owner.pragma("page_size")
        val before = footprint()
        assertTrue("the fixture must start with a non-empty WAL", before.wal > 0L)

        val peak = vacuumSamplingPeak()

        val pagesAfter = owner.pragma("page_count")
        assertEquals(
            "the compacted image is appended after the pending frames, not written over them",
            before.wal + pagesAfter * (pageSize + 24),
            walFile.length(),
        )
        assertEquals("the peak therefore counts the pending WAL too", before.total + pagesAfter * (pageSize + 24), peak)

        assertEquals(Checkpoint(0L, 0L, 0L), owner.truncate())
        assertEquals(pagesAfter * pageSize, databaseFile.length())
        assertEquals(0L, walFile.length())
    }

    @Test fun incrementalIssuedImmediatelyBeforeVacuumConvertsANoneDatabase() {
        seed(rows = 2_000, delete = "id % 4 != 0")
        assertTrue("the fixture must build a freelist", owner.pragma("freelist_count") > 0L)

        owner.exec("PRAGMA auto_vacuum=INCREMENTAL")
        assertEquals(
            "on a NONE database the pragma alone changes nothing — only the VACUUM applies it",
            0L,
            owner.pragma("auto_vacuum"),
        )

        owner.exec("VACUUM")
        assertEquals("the VACUUM converts the database to INCREMENTAL", 2L, owner.pragma("auto_vacuum"))
        assertEquals("and empties the freelist", 0L, owner.pragma("freelist_count"))

        owner.close()
        owner = open()
        assertEquals("the conversion is in the header and survives reconnection", 2L, owner.pragma("auto_vacuum"))
    }

    @Test fun truncateReportsAPinnedReaderAsBusyAndLeavesTheWal() {
        seed(rows = 2_000, delete = "id % 4 != 0")
        owner.exec("PRAGMA busy_timeout=100")
        val reader = open()
        try {
            // A snapshot taken BEFORE the writes: nothing after it can be backfilled.
            reader.autoCommit = false
            reader.count()
            owner.exec("INSERT INTO telemetry(payload) SELECT payload FROM telemetry LIMIT 50")
            val walBefore = walFile.length()
            assertTrue(walBefore > 0L)

            val pinned = owner.truncate()
            // Unlike PASSIVE, TRUNCATE does raise the busy flag when a reader blocks it — and it
            // returns a row rather than throwing, after waiting out the busy timeout.
            assertEquals("TRUNCATE flags the pinned reader as busy", 1L, pinned.busy)
            assertTrue("the log frames are reported", pinned.log > 0L)
            assertEquals("nothing past the old snapshot is backfilled", 0L, pinned.checkpointed)
            assertEquals("the WAL is not shrunk", walBefore, walFile.length())

            // A reader whose snapshot is CURRENT still blocks the reset, though not the backfill.
            reader.commit()
            reader.count()
            val current = owner.truncate()
            assertEquals("a current reader still blocks the WAL reset", 1L, current.busy)
            assertEquals("but every frame is backfilled", current.log, current.checkpointed)
            assertEquals("and the WAL is still not shrunk", walBefore, walFile.length())
            reader.commit()
        } finally {
            runCatching { reader.close() }
        }

        assertEquals("uncontended, TRUNCATE completes", Checkpoint(0L, 0L, 0L), owner.truncate())
        assertEquals("and leaves an empty WAL file behind", 0L, walFile.length())
        assertTrue("TRUNCATE empties the file rather than deleting it", walFile.exists())
    }

    @Test fun vacuumSucceedsPastAPinnedReaderButItsSpaceWaitsForTheReader() {
        seed(rows = 2_000, delete = "id % 4 != 0")
        owner.exec("PRAGMA busy_timeout=100")
        val pageSize = owner.pragma("page_size")
        val reader = open()
        try {
            reader.autoCommit = false
            val countBefore = reader.count()
            val mainBefore = databaseFile.length()

            // No exception: in WAL mode a reader does not block a writer, and VACUUM is a writer.
            owner.exec("VACUUM")
            val pagesAfter = owner.pragma("page_count")
            assertEquals("the reader keeps its pre-VACUUM snapshot", countBefore, reader.count())
            assertEquals("the main file is untouched", mainBefore, databaseFile.length())

            val pinned = owner.truncate()
            assertEquals("the pinned reader turns the follow-up TRUNCATE busy", 1L, pinned.busy)
            assertEquals("none of the compacted image can be backfilled", 0L, pinned.checkpointed)
            assertEquals(pagesAfter * (pageSize + 24) + 32, walFile.length())
            reader.commit()
        } finally {
            runCatching { reader.close() }
        }

        assertEquals(Checkpoint(0L, 0L, 0L), owner.truncate())
        assertTrue(
            "once the reader goes, the next TRUNCATE returns the space",
            databaseFile.length() == owner.pragma("page_count") * pageSize && walFile.length() == 0L,
        )
    }

    @Test fun vacuumAgainstAnOpenWriterThrowsBusyAndInsideATransactionThrowsError() {
        seed(rows = 2_000, delete = "id % 4 != 0")
        owner.exec("PRAGMA busy_timeout=100")
        val writer = open()
        try {
            writer.autoCommit = false
            writer.exec("INSERT INTO telemetry(payload) VALUES('y')")
            val busy = runCatching { owner.exec("VACUUM") }.exceptionOrNull()
            assertTrue("a held write lock must make VACUUM throw: $busy", busy is SQLiteException)
            busy as SQLiteException
            assertEquals(SQLiteErrorCode.SQLITE_BUSY, busy.resultCode)
            assertEquals(5, busy.errorCode)
            writer.rollback()
        } finally {
            runCatching { writer.close() }
        }

        // A different failure a policy must not retry as contention: the caller's own transaction.
        owner.autoCommit = false
        owner.count()
        val nested = runCatching { owner.exec("VACUUM") }.exceptionOrNull()
        owner.rollback()
        owner.autoCommit = true
        assertTrue("VACUUM inside a transaction must throw: $nested", nested is SQLiteException)
        nested as SQLiteException
        assertEquals(SQLiteErrorCode.SQLITE_ERROR, nested.resultCode)
        assertTrue(nested.message.orEmpty().contains("cannot VACUUM from within a transaction"))
    }
}
