package io.github.maxlyth.hapaneld.dashboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.maxlyth.hapaneld.CoreInstrumentation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android SQLite's actual upgrade path; schema-plan string tests cannot prove this. */
@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class EntityCatalogUpgradeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun cleanBefore() = clean()
    @After fun cleanAfter() = clean()

    /**
     * Structures older than public v0.9.5 are out of contract: their migration steps were deleted, so
     * onUpgrade cannot carry them forward. The compatibility boundary must refuse the owned open and
     * leave the exact database untouched; silently replacing it with a fresh store would discard state.
     */
    @Test fun aDatabaseBelowTheSupportedFloorIsRefusedWithoutReplacement() {
        legacyDatabase(8).use { db ->
            db.execSQL("CREATE TABLE entity(instance TEXT NOT NULL, entity_id TEXT NOT NULL, state TEXT NOT NULL, PRIMARY KEY(instance,entity_id))")
            db.execSQL("INSERT INTO entity(instance,entity_id,state) VALUES('home','sensor.room','21.5')")
            db.version = 8
        }

        val refusal = try {
            EntityCatalogStore(context).use { it.writableDatabase }
            throw AssertionError("below-minimum database was opened")
        } catch (expected: DatabaseCompatibilityException) {
            expected
        }
        assertEquals(DatabaseCompatibilityRefusal.PRIMARY_BELOW_MINIMUM, refusal.refusal)

        val target = context.getDatabasePath(EntityCatalogStore.DATABASE_NAME)
        SQLiteDatabase.openDatabase(
            target.path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { preserved ->
            assertEquals(8, preserved.version)
            assertEquals("21.5", scalar(preserved, "SELECT state FROM entity WHERE entity_id='sensor.room'"))
        }
        assertFalse(java.io.File(target.parentFile, "${target.name}.v8.superseded").exists())
    }

    @Test fun publicV095DatabaseUpgradePreservesConfigInOneStep() {
        legacyDatabase(11).use { db ->
            assertFalse("the fixture must exercise the API-27 non-WAL pre-open path", db.isWriteAheadLoggingEnabled)
            db.execSQL(
                "CREATE TABLE dashboard(" +
                    "instance TEXT NOT NULL, path TEXT NOT NULL, config_hash TEXT NOT NULL DEFAULT ''," +
                    "config_json TEXT NOT NULL DEFAULT '{}', status TEXT NOT NULL DEFAULT 'disabled'," +
                    "last_sync_at INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT ''," +
                    "unresolved_json TEXT NOT NULL DEFAULT '[]', sync_generation INTEGER NOT NULL DEFAULT 0," +
                    "issues_json TEXT NOT NULL DEFAULT '[]'," +
                    "PRIMARY KEY(instance,path))",
            )
            db.execSQL("INSERT INTO dashboard(instance,path,status) VALUES('fixture','home','synced')")
            db.execSQL(EntityCatalogStore.APP_STATE_REVISION_TABLE_SQL)
            db.execSQL(EntityCatalogStore.APP_STATE_NAMESPACE_TABLE_SQL)
            db.execSQL(EntityCatalogStore.APP_STATE_TABLE_SQL)
            db.execSQL("CREATE INDEX ix_app_state_updated ON app_state(namespace,updated_at)")
            db.execSQL("INSERT INTO app_state_revision(committed_at,namespace,source) VALUES(1700000000000,'config','fixture')")
            db.execSQL("INSERT INTO app_state_namespace(namespace,imported_at,legacy_name) VALUES('config',1700000000000,'')")
            db.execSQL(
                "INSERT INTO app_state(namespace,state_key,value_type,value_text,updated_at,revision) " +
                    "VALUES('config','ha_url','string','https://ha.example.test',1700000000000,1)",
            )
            db.execSQL(
                "INSERT INTO app_state(namespace,state_key,value_type,value_text,updated_at,revision) " +
                    "VALUES('config','config_schema','int','2',1700000000000,1)",
            )
            db.version = 11
        }

        EntityCatalogStore(context).use { store ->
            val db = store.writableDatabase
            assertEquals(EntityCatalogSchema.CURRENT_VERSION, db.version)
            assertEquals("https://ha.example.test", scalar(db, "SELECT value_text FROM app_state WHERE namespace='config' AND state_key='ha_url'"))
            assertEquals("2", scalar(db, "SELECT value_text FROM app_state WHERE namespace='config' AND state_key='config_schema'"))
            assertEquals("0", scalar(db, "SELECT analyzer_policy_version FROM dashboard"))
        }

        val target = context.getDatabasePath(EntityCatalogStore.DATABASE_NAME)
        val premigration = preMigrationBackupFile(target, 11)
        assertTrue("upgrade must retain the exact pre-migration database", premigration.isFile)
        assertWalHeader(premigration)
        SQLiteDatabase.openDatabase(
            premigration.path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { snapshot ->
            assertEquals(11, snapshot.version)
            assertEquals("ok", scalar(snapshot, "PRAGMA quick_check(1)"))
            assertEquals(
                "https://ha.example.test",
                scalar(snapshot, "SELECT value_text FROM app_state WHERE namespace='config' AND state_key='ha_url'"),
            )
        }
        assertEquals(
            EntityCatalogSchema.CURRENT_VERSION,
            SQLiteDatabase.openDatabase(
                target.path,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { it.version },
        )
        listOf("-wal", "-shm", "-journal", ".tmp").forEach { suffix ->
            assertFalse("pre-migration snapshot must be standalone: $suffix", java.io.File(premigration.path + suffix).exists())
        }
    }

    @Test fun newerDatabaseRestoresStandaloneCurrentSnapshotWithoutCanonicalSidecars() {
        val target = context.getDatabasePath(EntityCatalogStore.DATABASE_NAME)
        EntityCatalogStore(context).use { store ->
            store.writableDatabase.execSQL("CREATE TABLE guard_restore_marker(value TEXT NOT NULL)")
            store.writableDatabase.execSQL("INSERT INTO guard_restore_marker(value) VALUES('baseline')")
        }
        assertWalHeader(target)
        assertStandalone(target)

        val premigration = preMigrationBackupFile(target, EntityCatalogSchema.CURRENT_VERSION)
        target.copyTo(premigration, overwrite = true)
        assertWalHeader(premigration)
        assertStandalone(premigration)

        SQLiteDatabase.openDatabase(
            target.path,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or
                SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
        ).use { future ->
            future.execSQL("CREATE TABLE db_compatibility_canary_v15(value TEXT NOT NULL)")
            future.execSQL("INSERT INTO db_compatibility_canary_v15(value) VALUES('future')")
            future.version = EntityCatalogSchema.CURRENT_VERSION + 1
        }
        assertWalHeader(target)

        EntityCatalogStore(context).use { store ->
            val restored = store.writableDatabase
            assertTrue(restored.isWriteAheadLoggingEnabled)
            val wal = java.io.File(target.path + "-wal")
            assertTrue("the owned open must retain its WAL sidecar", wal.isFile)
            assertEquals(0L, wal.length())
            assertTrue(java.io.File(target.path + "-shm").isFile)
            assertEquals(EntityCatalogSchema.CURRENT_VERSION, restored.version)
            assertEquals("baseline", scalar(restored, "SELECT value FROM guard_restore_marker"))
            assertFalse(tableExists(restored, "db_compatibility_canary_v15"))
            assertFalse(
                "ordinary restore receipt must be consumed during the successful owned open",
                java.io.File(target.parentFile, ".${target.name}.restore.v1").exists(),
            )
        }

        assertWalHeader(target)
        assertStandalone(target)
        assertTrue(
            "the newer primary must remain recoverable",
            supersededFile(target, EntityCatalogSchema.CURRENT_VERSION + 1).isFile,
        )
    }

    /**
     * The metric payload migration moves Tier-2 history, so it must arrive intact. `dashboard_performance`
     * is deliberately left in place: dropping it would be non-additive, and keeping it lets an older build
     * still open this database.
     */
    @Test fun performanceHistoryIsCarriedIntoPayloadsAndTheOldTableIsRetainedEmpty() {
        legacyDatabase(12).use { db ->
            db.execSQL(EntityCatalogStore.PERFORMANCE_HISTORY_TABLE_SQL)
            db.execSQL(
                "INSERT INTO dashboard_performance(instance,path,minute,filter_active,entity_count," +
                    "frames,loaf_max_micros,interaction_max_micros,input_delay_micros) " +
                    "VALUES('home','lovelace',1000,1,42,7,900,500,60)",
            )
            db.version = 12
        }

        EntityCatalogStore(context).use { store ->
            val db = store.writableDatabase
            assertEquals(EntityCatalogSchema.CURRENT_VERSION, db.version)
            val history = store.dashboardPerformanceHistory("home", "lovelace", 0)
            assertEquals(1, history.size)
            val minute = history.single()
            assertEquals(1000L, minute.minute)
            assertTrue(minute.filterActive)
            assertEquals(42, minute.entityCount)
            assertEquals(7L, minute.totals.frames)
            assertEquals(900L, minute.totals.loafMaxMicros)
            assertEquals("the slowest interaction keeps its breakdown", 60L, minute.totals.inputDelayMicros)
            assertEquals("0", scalar(db, "SELECT count(*) FROM dashboard_performance"))
            assertTrue("the old table must remain for an older build", tableExists(db, "dashboard_performance"))
        }
    }

    private fun legacyDatabase(version: Int): SQLiteDatabase {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
        return context.openOrCreateDatabase(EntityCatalogStore.DATABASE_NAME, Context.MODE_PRIVATE, null).also { it.version = version }
    }

    private fun scalar(db: SQLiteDatabase, sql: String): String? =
        db.rawQuery(sql, emptyArray()).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        scalar(db, "SELECT name FROM sqlite_master WHERE type='table' AND name='$table'") == table

    private fun assertWalHeader(database: java.io.File) {
        val header = database.inputStream().use { input -> ByteArray(20).also { assertEquals(20, input.read(it)) } }
        assertEquals("SQLite read version must remain WAL", 2, header[18].toInt())
        assertEquals("SQLite write version must remain WAL", 2, header[19].toInt())
    }

    private fun assertStandalone(database: java.io.File) {
        listOf("-wal", "-shm", "-journal", ".tmp").forEach { suffix ->
            assertFalse("database must be standalone: $suffix", java.io.File(database.path + suffix).exists())
        }
    }

    private fun indexExists(db: SQLiteDatabase, index: String): Boolean =
        scalar(db, "SELECT name FROM sqlite_master WHERE type='index' AND name='$index'") == index

    private fun clean() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
        // The reconcile paths leave .vN.premigrate / .vN.superseded copies beside the database; without
        // sweeping them a preserved database from one test becomes a restore source in the next.
        val target = context.getDatabasePath(EntityCatalogStore.DATABASE_NAME)
        target.parentFile?.listFiles()
            ?.filter { it.name.startsWith("${target.name}.v") }
            ?.forEach { it.delete() }
    }
}
