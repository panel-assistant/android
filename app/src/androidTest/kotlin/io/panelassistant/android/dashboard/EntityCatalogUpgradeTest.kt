package io.panelassistant.android.dashboard

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.panelassistant.android.CoreInstrumentation
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
    private val context = HistoricalCatalogFixture.isolatedContext(
        ApplicationProvider.getApplicationContext<Context>(), "schema-upgrade",
    )

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
        HistoricalCatalogFixture.create(context).use { db ->
            assertFalse("the fixture must exercise the API-27 non-WAL pre-open path", db.isWriteAheadLoggingEnabled)
            db.execSQL("INSERT INTO dashboard(instance,path,status) VALUES('fixture','home','synced')")
            db.execSQL("UPDATE dashboard SET last_sync=101")
            db.execSQL("INSERT INTO dashboard_issue_ignore VALUES('fixture','home','ignored-warning',102)")
            db.execSQL("INSERT INTO entity(instance,entity_id,first_seen,last_seen) VALUES('fixture','sensor.room',103,104)")
            db.execSQL(
                "INSERT INTO membership(instance,path,entity_id,static_ref,runtime_ref,pinned,excluded," +
                    "first_access,last_access,rate_window_start) VALUES('fixture','home','sensor.room',1,1,1,1,105,106,107)",
            )
            db.execSQL("INSERT INTO minute_rollup(instance,path,entity_id,minute,span_start) VALUES('fixture','home','sensor.room',108,109)")
            db.execSQL("INSERT INTO proximity_model(fingerprint,algorithm_version,snapshot_json,updated_at) VALUES('probe',1,'{}',110)")
            db.execSQL("INSERT INTO proximity_rollup VALUES('probe',111,2,3.0,4.0,7.0,25.0,1,1)")
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
            assertEquals("101", scalar(db, "SELECT last_sync_at FROM dashboard WHERE instance='fixture' AND path='home'"))
            assertEquals("102", scalar(db, "SELECT ignored_at FROM dashboard_ignored_issue WHERE fingerprint='ignored-warning'"))
            assertEquals("103,104", scalar(db, "SELECT first_seen_at||','||last_seen_at FROM entity WHERE entity_id='sensor.room'"))
            assertEquals(
                "1,1,1,1,105,106,107",
                scalar(db, "SELECT referenced_by_config||','||referenced_at_runtime||','||pinned||','||excluded||','||" +
                    "first_access_at||','||last_access_at||','||rate_window_started_at FROM dashboard_entity WHERE entity_id='sensor.room'"),
            )
            assertEquals("109", scalar(db, "SELECT span_started_at FROM dashboard_entity_traffic_minute WHERE minute=108"))
            db.rawQuery("SELECT raw_sum_squares FROM proximity_sample WHERE fingerprint='probe'", emptyArray()).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(25.0, cursor.getDouble(0), 0.0)
            }
            db.rawQuery("PRAGMA foreign_key_check", emptyArray()).use { cursor ->
                assertFalse("the upgraded database must not contain foreign-key violations", cursor.moveToFirst())
            }
        }

        val target = context.getDatabasePath(EntityCatalogStore.DATABASE_NAME)
        val premigration = preMigrationBackupFile(target, 11)
        assertTrue("upgrade must retain the exact pre-migration database", premigration.isFile)
        assertWalHeader(premigration)
        assertStandalone(premigration)
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
     * History survives both the additive payload migration and the declared v14 naming break.
     */
    @Test fun performanceHistorySurvivesTheNamingMigrationAndRetiresTheOldTable() {
        HistoricalCatalogFixture.create(context).use { db ->
            EntityCatalogSchema.plan(11, 12).single().sql.forEach(db::execSQL)
            db.execSQL(
                "INSERT INTO dashboard_performance(instance,path,minute,filter_active,entity_count," +
                    "frames,loaf_max_micros,interaction_max_micros,input_delay_micros) " +
                    "VALUES('home','lovelace',1000,1,42,7,900,500,60)",
            )
            db.version = 12
            // Prove the intermediate additive contract before running the final naming migration.
            val payloadStep = EntityCatalogSchema.plan(12, 13).single()
            assertFalse(payloadStep.breaksCompatibility)
            payloadStep.sql.forEach(db::execSQL)
            payloadStep.transform?.invoke(db)
            assertTrue("schema 13 retains the old table", tableExists(db, "dashboard_performance"))
            assertEquals("0", scalar(db, "SELECT count(*) FROM dashboard_performance"))
            assertEquals("1", scalar(db, "SELECT count(*) FROM dashboard_metric_minute"))
            db.version = 13
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
            assertFalse("v14 retires the migrated table", tableExists(db, "dashboard_performance"))
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

    private fun clean() = HistoricalCatalogFixture.clean(context)
}
