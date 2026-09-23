package io.github.maxlyth.hapaneld.sensors

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxlyth.hapaneld.dashboard.EntityCatalogStore
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProximitySqliteRestartInstrumentedTest {
    @Test fun productionSqliteStorePersistsAndReopensTrustedGradedStartup() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "proximity-sqlite-${System.nanoTime()}").apply { mkdirs() }
        val context = IsolatedDatabaseContext(base, directory)
        val fingerprint = ProximityLearningRuntime.fingerprint(SOURCE)
        val expected = startupRow(fingerprint)

        SqliteProximityModelStore(EntityCatalogStore(context)).use { first ->
            first.writeProximityBatch(expected, emptyList(), emptyList(), 1L)
        }
        val database = context.getDatabasePath(EntityCatalogStore.DATABASE_NAME)
        assertTrue(database.isFile)
        assertTrue(database.length() > 0L)

        val reopened = SqliteProximityModelStore(EntityCatalogStore(context))
        assertEquals(expected, reopened.readProximityModel(fingerprint))
        val firstRuntime = runtime(reopened)
        assertEquals(0L, firstRuntime.wakeEvidenceGenerationForTest())
        val anchor = firstRuntime.observe(STARTUP_FAR, 0L)
        assertEquals(false, anchor.near)
        assertTrue(anchor.normalizedLevel != null)
        assertFalse(firstRuntime.isReady())
        assertEquals(0L, firstRuntime.wakeEvidenceGenerationForTest())
        firstRuntime.closeAsync().get(5, TimeUnit.SECONDS)

        val reopenedAgain = SqliteProximityModelStore(EntityCatalogStore(context))
        val persisted = reopenedAgain.readProximityModel(fingerprint)!!
        assertTrue(persisted.ready)
        val decoded = ProximityLearningRuntime.persistedModel(persisted.snapshotJson)!!
        assertEquals(STARTUP_FAR, decoded.snapshot.farRaw, 0f)
        assertEquals(69.0891f, decoded.snapshot.nearRaw, 0f)
        assertEquals(ProximityLearningEngine.Polarity.NEAR_IS_HIGHER, decoded.snapshot.polarity)

        val secondRuntime = runtime(reopenedAgain)
        assertEquals(0L, secondRuntime.wakeEvidenceGenerationForTest())
        val bootTail = secondRuntime.observe(STARTUP_BOOT_TAIL, 0L)
        assertEquals(false, bootTail.near)
        assertTrue(bootTail.normalizedLevel != null)
        assertFalse(secondRuntime.isReady())
        assertEquals(0L, secondRuntime.wakeEvidenceGenerationForTest())
        secondRuntime.closeAsync().get(5, TimeUnit.SECONDS)
    }

    private fun runtime(store: ProximityModelStore) = ProximityLearningRuntime(
        context = null,
        config = null,
        sourceIdentity = SOURCE,
        sparseLearningSource = false,
        legacySeedEligible = false,
        invalidationJournalForTest = MemoryJournal(),
        modelStoreForTest = store,
    )

    private fun startupRow(fingerprint: String): EntityCatalogStore.ProximityModelRow {
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

    private class MemoryJournal : ProximityWakeInvalidationAuthority {
        private var token: String? = null
        override fun read(fingerprint: String) = token
        override fun mark(fingerprint: String, token: String): Boolean {
            this.token = token
            return true
        }
        override fun clear(fingerprint: String, token: String): Boolean {
            if (this.token != token) return false
            this.token = null
            return true
        }
    }

    private class IsolatedDatabaseContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getDatabasePath(name: String): File = File(directory, name)

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)

        override fun openOrCreateDatabase(
            name: String,
            mode: Int,
            factory: SQLiteDatabase.CursorFactory?,
            errorHandler: DatabaseErrorHandler?,
        ): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)

        override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
    }

    companion object {
        private const val SOURCE = "hal:8:graded-sensor-sqlite"
        private const val STARTUP_FAR = 37.8244f
        private const val STARTUP_BOOT_TAIL = 25.875f
    }
}
