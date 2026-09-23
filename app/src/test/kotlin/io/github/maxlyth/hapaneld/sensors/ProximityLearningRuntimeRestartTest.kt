package io.github.maxlyth.hapaneld.sensors

import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityLearningRuntimeRestartTest {
    @Test fun denseGradedRestartKeepsPersistedModelUntilFullContradictoryHold() {
        val backing = StoreBacking(startupRow())
        val journal = MemoryJournal()
        val runtime = runtime(backing, journal, sparse = false)

        for (now in 0L..300L step 100L) assertAvailable(runtime.observe(STARTUP_FAR, now), runtime)
        assertEquals(0L, runtime.wakeEvidenceGenerationForTest())
        for (now in 350L until 30_350L step 50L) {
            assertAvailable(runtime.observe(STARTUP_BOOT_TAIL, now), runtime)
        }
        assertTrue(backing.row?.ready == true)
        assertTrue(backing.row?.snapshotJson?.contains("\"farRaw\":37.8244") == true)
        assertEquals(0L, runtime.wakeEvidenceGenerationForTest())

        val shifted = runtime.observe(STARTUP_BOOT_TAIL, 30_350L)
        assertNull(shifted.near)
        assertNull(shifted.normalizedLevel)
        assertEquals(1L, runtime.wakeEvidenceGenerationForTest())
        assertTrue(runtime.summary().startsWith("Adapting safely"))
        assertEquals(1, journal.marks)
        close(runtime)
    }

    @Test fun sparseHeldContradictionUsesTheSameLongBoundary() {
        val backing = StoreBacking(startupRow())
        val journal = MemoryJournal()
        val runtime = runtime(backing, journal, sparse = true)

        assertAvailable(runtime.observe(STARTUP_BOOT_TAIL, 0L, sparseReporting = true), runtime)
        assertAvailable(runtime.tick(29_999L, sparseReporting = true), runtime)
        assertEquals(0L, runtime.wakeEvidenceGenerationForTest())
        val shifted = runtime.tick(30_000L, sparseReporting = true)

        assertNull(shifted.near)
        assertNull(shifted.normalizedLevel)
        assertEquals(1L, runtime.wakeEvidenceGenerationForTest())
        assertEquals(1, journal.marks)
        close(runtime)
    }

    @Test fun contradictoryFirstDenseStartupCannotUseTheSeedMismatchShortcut() {
        val backing = StoreBacking(startupRow())
        val journal = MemoryJournal()
        val runtime = runtime(backing, journal, sparse = false)

        for (now in 0L..2_000L step 50L) {
            assertAvailable(runtime.observe(STARTUP_BOOT_TAIL, now), runtime)
        }
        assertEquals(0, journal.marks)
        for (now in 2_050L until 30_000L step 50L) runtime.observe(STARTUP_BOOT_TAIL, now)
        val shifted = runtime.observe(STARTUP_BOOT_TAIL, 30_000L)
        assertNull(shifted.near)
        assertNull(shifted.normalizedLevel)
        assertEquals(1, journal.marks)
        close(runtime)
    }

    @Test fun staleStreamRecoversTheTrustedModelWithoutAdvancingGeneration() {
        val backing = StoreBacking(startupRow())
        val journal = MemoryJournal()
        val runtime = runtime(backing, journal, sparse = false)

        assertAvailable(runtime.observe(STARTUP_FAR, 0L), runtime)
        assertEquals(0L, runtime.wakeEvidenceGenerationForTest())
        val stale = runtime.tick(60_001L)
        assertNull(stale.near)
        assertNull(stale.normalizedLevel)
        assertEquals(0L, runtime.wakeEvidenceGenerationForTest())
        assertAvailable(runtime.observe(STARTUP_FAR, 60_002L), runtime)
        assertEquals(0L, runtime.wakeEvidenceGenerationForTest())
        assertEquals(0, journal.marks)
        close(runtime)
    }

    @Test fun persistedStoreReopensWithAFreshValidationGeneration() {
        val backing = StoreBacking(startupRow())
        val journal = MemoryJournal()
        val first = runtime(backing, journal, sparse = false)
        for (now in 0L..30_000L step 100L) first.observe(STARTUP_FAR, now)
        assertTrue(first.isReady())
        close(first)

        val second = runtime(backing, journal, sparse = false)
        assertEquals(0L, second.wakeEvidenceGenerationForTest())
        assertFalse(second.isReady())
        assertAvailable(second.observe(STARTUP_BOOT_TAIL, 0L), second)
        assertEquals(0L, second.wakeEvidenceGenerationForTest())
        assertEquals(2, backing.opens)
        assertTrue(backing.writes >= 2)
        assertEquals(0, journal.marks)
        close(second)
        assertEquals(2, backing.closes)
    }

    @Test fun productionStoreAdapterReopensARealTemporarySqliteDatabase() {
        val database = File.createTempFile("proximity-runtime-", ".db").apply { delete() }
        val journal = MemoryJournal()
        try {
            SqliteProximityModelStore(CliSqliteStore(database)).use { first ->
                first.writeProximityBatch(startupRow(), emptyList(), emptyList(), 1L)
            }
            assertTrue(database.isFile)
            assertTrue(database.length() > 0L)

            val reopened = SqliteProximityModelStore(CliSqliteStore(database))
            assertEquals(startupRow(), reopened.readProximityModel(ProximityLearningRuntime.fingerprint(SOURCE)))
            val firstRuntime = runtime(reopened, journal, sparse = false)
            assertEquals(0L, firstRuntime.wakeEvidenceGenerationForTest())
            assertAvailable(firstRuntime.observe(STARTUP_FAR, 0L), firstRuntime)
            assertEquals(0L, firstRuntime.wakeEvidenceGenerationForTest())
            close(firstRuntime)

            val reopenedAgain = SqliteProximityModelStore(CliSqliteStore(database))
            val persisted = reopenedAgain.readProximityModel(ProximityLearningRuntime.fingerprint(SOURCE))!!
            assertTrue(persisted.ready)
            val secondRuntime = runtime(reopenedAgain, journal, sparse = false)
            assertEquals(0L, secondRuntime.wakeEvidenceGenerationForTest())
            assertAvailable(secondRuntime.observe(STARTUP_BOOT_TAIL, 0L), secondRuntime)
            assertEquals(0L, secondRuntime.wakeEvidenceGenerationForTest())
            close(secondRuntime)
        } finally {
            database.delete()
            File("${database.path}-wal").delete()
            File("${database.path}-shm").delete()
        }
    }

    private fun runtime(backing: StoreBacking, journal: MemoryJournal, sparse: Boolean) =
        runtime(backing.open(), journal, sparse)

    private fun runtime(store: ProximityModelStore, journal: MemoryJournal, sparse: Boolean) =
        ProximityLearningRuntime(
            context = null,
            config = null,
            sourceIdentity = SOURCE,
            sparseLearningSource = sparse,
            legacySeedEligible = false,
            invalidationJournalForTest = journal,
            modelStoreForTest = store,
        )

    private fun assertAvailable(decision: ProximityLearningRuntime.Decision, runtime: ProximityLearningRuntime) {
        assertEquals(false, decision.near)
        assertTrue(decision.normalizedLevel != null)
        assertFalse(runtime.isReady())
        assertTrue(runtime.summary().startsWith("Checking the previous"))
    }

    private fun close(runtime: ProximityLearningRuntime) {
        runtime.closeAsync().get(5, TimeUnit.SECONDS)
    }

    private fun startupRow(): EntityCatalogStore.ProximityModelRow {
        val fingerprint = ProximityLearningRuntime.fingerprint(SOURCE)
        val snapshot = ProximityLearningEngine.Snapshot(
            farRaw = STARTUP_FAR,
            nearRaw = 69.0891f,
            noise = 1.392f,
            mode = ProximityLearningEngine.Mode.GRADED,
            polarity = ProximityLearningEngine.Polarity.NEAR_IS_HIGHER,
            completedExcursions = 119,
        )
        return EntityCatalogStore.ProximityModelRow(
            fingerprint = fingerprint,
            algorithmVersion = ProximityLearningRuntime.ALGORITHM_VERSION,
            behaviorSignature = "GRADED:NEAR_IS_HIGHER",
            snapshotJson = ProximityLearningRuntime.persistedModelJson(snapshot, guidedReady = true),
            ready = true,
            updatedAt = 1L,
        )
    }

    private class StoreBacking(initial: EntityCatalogStore.ProximityModelRow) {
        var row: EntityCatalogStore.ProximityModelRow? = initial
        var opens = 0
        var closes = 0
        var writes = 0

        fun open(): ProximityModelStore {
            opens++
            return object : ProximityModelStore {
                override fun readProximityModel(fingerprint: String) = row?.takeIf { it.fingerprint == fingerprint }

                override fun writeProximityBatch(
                    model: EntityCatalogStore.ProximityModelRow,
                    rollups: List<EntityCatalogStore.ProximityRollupRow>,
                    episodes: List<EntityCatalogStore.ProximityEpisodeRow>,
                    now: Long,
                ) {
                    row = model
                    writes++
                }

                override fun clearProximityLearning(fingerprint: String) {
                    if (row?.fingerprint == fingerprint) row = null
                }

                override fun close() {
                    closes++
                }
            }
        }
    }

    private class MemoryJournal : ProximityWakeInvalidationAuthority {
        private var token: String? = null
        var marks = 0

        override fun read(fingerprint: String) = token
        override fun mark(fingerprint: String, token: String): Boolean {
            this.token = token
            marks++
            return true
        }
        override fun clear(fingerprint: String, token: String): Boolean {
            if (this.token != token) return false
            this.token = null
            return true
        }
    }

    private class CliSqliteStore(private val database: File) : ProximityModelStore {
        init {
            execute(
                """CREATE TABLE IF NOT EXISTS proximity_model(
                    fingerprint TEXT PRIMARY KEY,
                    algorithm_version INTEGER NOT NULL,
                    behavior_signature TEXT NOT NULL,
                    snapshot_json TEXT NOT NULL,
                    ready INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )""".trimIndent(),
            )
        }

        override fun readProximityModel(fingerprint: String): EntityCatalogStore.ProximityModelRow? {
            val output = execute(
                "SELECT algorithm_version,behavior_signature,snapshot_json,ready,updated_at " +
                    "FROM proximity_model WHERE fingerprint=${quoted(fingerprint)}",
                tabs = true,
            ).trimEnd()
            if (output.isEmpty()) return null
            val columns = output.split('\t', limit = 5)
            return EntityCatalogStore.ProximityModelRow(
                fingerprint = fingerprint,
                algorithmVersion = columns[0].toInt(),
                behaviorSignature = columns[1],
                snapshotJson = columns[2],
                ready = columns[3] == "1",
                updatedAt = columns[4].toLong(),
            )
        }

        override fun writeProximityBatch(
            model: EntityCatalogStore.ProximityModelRow,
            rollups: List<EntityCatalogStore.ProximityRollupRow>,
            episodes: List<EntityCatalogStore.ProximityEpisodeRow>,
            now: Long,
        ) {
            execute(
                "INSERT OR REPLACE INTO proximity_model(" +
                    "fingerprint,algorithm_version,behavior_signature,snapshot_json,ready,updated_at" +
                    ") VALUES(" +
                    "${quoted(model.fingerprint)},${model.algorithmVersion},${quoted(model.behaviorSignature)}," +
                    "${quoted(model.snapshotJson)},${if (model.ready) 1 else 0},${model.updatedAt})",
            )
        }

        override fun clearProximityLearning(fingerprint: String) {
            execute("DELETE FROM proximity_model WHERE fingerprint=${quoted(fingerprint)}")
        }

        override fun close() = Unit

        private fun execute(sql: String, tabs: Boolean = false): String {
            val command = mutableListOf("sqlite3")
            if (tabs) command += listOf("-tabs", "-noheader")
            command += listOf(database.path, sql)
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { output }
            return output
        }

        private fun quoted(value: String): String = "'${value.replace("'", "''")}'"
    }

    companion object {
        private const val SOURCE = "hal:8:graded-sensor"
        private const val STARTUP_FAR = 37.8244f
        private const val STARTUP_BOOT_TAIL = 25.875f
    }
}
