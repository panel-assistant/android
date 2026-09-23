package io.github.maxlyth.hapaneld.dashboard

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.maxlyth.hapaneld.CoreInstrumentation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves a freshly created database and one upgraded from the supported v0.9.5 floor end up with the
 * same realized schema. [EntityCatalogStore.onCreate] is hand-maintained separately from
 * [EntityCatalogSchema] migration steps, so the two can silently diverge: a column added only to
 * onCreate is missing on every upgraded panel, and a step added only to the plan is missing on every
 * fresh install. No other test reads the realized schema, and asserting on migration SQL strings
 * cannot catch this.
 *
 * The v11 fixture below is deliberately a *literal copy of what the v0.9.5 tag shipped* rather than a
 * reference to today's production constants. Deriving the baseline from current code would inherit any
 * drift and make the comparison vacuous.
 *
 * Comparison is semantic (columns, declared types, nullability, defaults, primary-key position,
 * indexes and foreign keys) rather than raw `sqlite_master` text, because `ALTER TABLE ADD COLUMN`
 * legitimately leaves different DDL text for an identical schema.
 */
@CoreInstrumentation
@RunWith(AndroidJUnit4::class)
class EntityCatalogSchemaParityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun cleanBefore() = clean()
    @After fun cleanAfter() = clean()

    @Test fun freshCreateAndUpgradeFromV095FloorProduceTheSameSchema() {
        val fresh = EntityCatalogStore(context).use { schemaFingerprint(it.writableDatabase) }
        clean()
        v095Database()
        val upgraded = EntityCatalogStore(context).use { store ->
            assertEquals(EntityCatalogSchema.CURRENT_VERSION, store.writableDatabase.version)
            schemaFingerprint(store.writableDatabase)
        }

        if (fresh != upgraded) {
            throw AssertionError(
                "onCreate and onUpgrade-from-v11 disagree.\n" +
                    "Every schema change must be applied to BOTH onCreate and an EntityCatalogSchema step.\n" +
                    firstDifference(fresh, upgraded),
            )
        }
    }

    /**
     * The realized-schema form of the additive rule, and the stronger half: it catches a removal or a
     * retype however the SQL was spelled, which a text lint cannot. Everything public v0.9.5 shipped must
     * still be present with the same declared type, and anything added since must be insertable by a
     * build that has never heard of it.
     */
    @Test fun theCurrentSchemaRemainsAnAdditiveSupersetOfTheV095Baseline() {
        v095Database()
        val baseline = SQLiteDatabase.openDatabase(
            context.getDatabasePath(EntityCatalogStore.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READONLY,
        ).use { columns(it) }
        clean()
        val current = EntityCatalogStore(context).use { columns(it.writableDatabase) }

        val removed = baseline.keys - current.keys
        assertTrue("v0.9.5 columns must not be removed or renamed: $removed", removed.isEmpty())

        val retyped = baseline.filter { (key, column) -> current[key]?.type != column.type }
            .map { (key, column) -> "$key was ${column.type}, now ${current[key]?.type}" }
        assertTrue("v0.9.5 columns must not be retyped: $retyped", retyped.isEmpty())

        // Scoped to tables that already existed: the constraint exists because an older build's INSERT
        // omits a column it does not know about, and it never inserts into a table it has never heard of.
        // A wholly new table may therefore use NOT NULL freely, and flagging it would be a false alarm —
        // a guard that cries wolf is worse than no guard.
        val baselineTables = baseline.values.map { it.table }.toSet()
        val unsafeAdditions = (current.keys - baseline.keys)
            .mapNotNull { current[it] }
            .filter { it.table in baselineTables && it.notNull && it.default == null && !it.primaryKey }
            .map { "${it.table}.${it.name}" }
        assertTrue(
            "columns added to an existing table since v0.9.5 must be nullable or defaulted, else an older " +
                "build cannot insert: $unsafeAdditions",
            unsafeAdditions.isEmpty(),
        )
    }

    private data class Column(
        val table: String, val name: String, val type: String,
        val notNull: Boolean, val default: String?, val primaryKey: Boolean,
    )

    private fun columns(db: SQLiteDatabase): Map<String, Column> {
        val tables = rows(db, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'") {
            it.getString(0)
        }
        return tables.flatMap { table ->
            rows(db, "PRAGMA table_info($table)") { cursor ->
                Column(
                    table = table,
                    name = cursor.getString(1),
                    type = cursor.getString(2).uppercase(),
                    notNull = cursor.getInt(3) != 0,
                    default = cursor.getString(4),
                    primaryKey = cursor.getInt(5) != 0,
                )
            }
        }.associateBy { "${it.table}.${it.name}" }
    }

    private fun <T> rows(db: SQLiteDatabase, sql: String, row: (Cursor) -> T): List<T> =
        db.rawQuery(sql, emptyArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(row(cursor)) }
        }

    /** Guards the fixture itself: a v11 baseline that already looks current would prove nothing. */
    @Test fun theV095FixtureIsGenuinelyOlderThanCurrent() {
        v095Database()
        val baseline = SQLiteDatabase.openDatabase(
            context.getDatabasePath(EntityCatalogStore.DATABASE_NAME).path, null, SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            assertEquals(11, db.version)
            schemaFingerprint(db)
        }
        clean()
        val current = EntityCatalogStore(context).use { schemaFingerprint(it.writableDatabase) }
        assertTrue("v11 fixture must differ from current, else the parity test is vacuous", baseline != current)
    }

    /** Exactly the schema the v0.9.5 tag created, at user_version 11. Do not refactor to use production constants. */
    private fun v095Database() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
        context.openOrCreateDatabase(EntityCatalogStore.DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            V095_SCHEMA.forEach(db::execSQL)
            db.version = 11
        }
    }

    private fun schemaFingerprint(db: SQLiteDatabase): String {
        val out = StringBuilder()
        val tables = rows(db, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name") {
            it.getString(0)
        }
        for (table in tables) {
            out.append("TABLE ").append(table).append('\n')
            // table_info: cid|name|type|notnull|dflt_value|pk. Ordinal position is deliberately excluded so a
            // column appended by ALTER TABLE compares equal to the same column declared inline in onCreate.
            rows(db, "PRAGMA table_info($table)") { cursor ->
                "  COL ${cursor.getString(1)} ${cursor.getString(2)} " +
                    "notnull=${cursor.getInt(3)} default=${cursor.getString(4) ?: "-"} pk=${cursor.getInt(5)}"
            }.sorted().forEach { out.append(it).append('\n') }
            // foreign_key_list: id|seq|table|from|to|on_update|on_delete|match
            rows(db, "PRAGMA foreign_key_list($table)") { cursor ->
                "  FK -> ${cursor.getString(2)}(${cursor.getString(4)}) from=${cursor.getString(3)} " +
                    "onDelete=${cursor.getString(6)}"
            }.sorted().forEach { out.append(it).append('\n') }
        }
        val indexes = rows(db, "SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%' ORDER BY name") {
            it.getString(0)
        }
        for (index in indexes) {
            val columns = rows(db, "PRAGMA index_info($index)") { it.getString(2) ?: "<expr>" }
            out.append("INDEX ").append(index).append('(').append(columns.joinToString(",")).append(")\n")
        }
        return out.toString()
    }

    private fun firstDifference(fresh: String, upgraded: String): String {
        val a = fresh.lines()
        val b = upgraded.lines()
        (a - b.toSet()).firstOrNull()?.let { return "only after fresh onCreate: $it" }
        (b - a.toSet()).firstOrNull()?.let { return "only after upgrade from v11: $it" }
        return "fresh:\n$fresh\nupgraded:\n$upgraded"
    }

    private fun clean() {
        context.deleteDatabase(EntityCatalogStore.DATABASE_NAME)
        // Upgrading from the v11 fixture leaves a .v11.premigrate snapshot; sweep it so a later run
        // cannot silently restore it instead of exercising the upgrade.
        val target = context.getDatabasePath(EntityCatalogStore.DATABASE_NAME)
        target.parentFile?.listFiles()
            ?.filter { it.name.startsWith("${target.name}.v") }
            ?.forEach { it.delete() }
    }

    private companion object {
        val V095_SCHEMA = listOf(
            """CREATE TABLE entity(
                instance TEXT NOT NULL, entity_id TEXT NOT NULL, state TEXT NOT NULL DEFAULT '',
                attributes_json TEXT NOT NULL DEFAULT '{}', metadata_json TEXT NOT NULL DEFAULT '{}',
                first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, missing_streak INTEGER NOT NULL DEFAULT 0,
                tombstone_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(instance,entity_id))""",
            """CREATE TABLE dashboard(
                instance TEXT NOT NULL, path TEXT NOT NULL, config_hash TEXT NOT NULL DEFAULT '',
                config_json TEXT NOT NULL DEFAULT '{}', status TEXT NOT NULL DEFAULT 'disabled',
                last_sync INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '',
                unresolved_json TEXT NOT NULL DEFAULT '[]', sync_generation INTEGER NOT NULL DEFAULT 0,
                issues_json TEXT NOT NULL DEFAULT '[]',
                PRIMARY KEY(instance,path))""",
            """CREATE TABLE membership(
                instance TEXT NOT NULL, path TEXT NOT NULL, entity_id TEXT NOT NULL,
                static_ref INTEGER NOT NULL DEFAULT 0, runtime_ref INTEGER NOT NULL DEFAULT 0,
                pinned INTEGER NOT NULL DEFAULT 0, excluded INTEGER NOT NULL DEFAULT 0,
                reasons TEXT NOT NULL DEFAULT '', first_access INTEGER NOT NULL DEFAULT 0,
                last_access INTEGER NOT NULL DEFAULT 0, access_count INTEGER NOT NULL DEFAULT 0,
                update_count INTEGER NOT NULL DEFAULT 0, update_bytes INTEGER NOT NULL DEFAULT 0,
                rate_window_start INTEGER NOT NULL DEFAULT 0, rate_update_bytes INTEGER NOT NULL DEFAULT 0,
                last_update_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(instance,path,entity_id))""",
            """CREATE TABLE minute_rollup(
                instance TEXT NOT NULL, path TEXT NOT NULL, entity_id TEXT NOT NULL, minute INTEGER NOT NULL,
                access_count INTEGER NOT NULL DEFAULT 0, update_count INTEGER NOT NULL DEFAULT 0,
                update_bytes INTEGER NOT NULL DEFAULT 0, span_start INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(instance,path,entity_id,minute))""",
            "CREATE INDEX entity_missing ON entity(instance,missing_streak)",
            "CREATE INDEX membership_load ON membership(instance,path,update_bytes DESC)",
            "CREATE INDEX minute_rollup_age ON minute_rollup(instance,path,minute)",
            """CREATE TABLE dashboard_issue_ignore(
                instance TEXT NOT NULL, path TEXT NOT NULL, fingerprint TEXT NOT NULL, ignored_at INTEGER NOT NULL,
                PRIMARY KEY(instance,path,fingerprint),
                FOREIGN KEY(instance,path) REFERENCES dashboard(instance,path) ON DELETE CASCADE)""",
            """CREATE TABLE dashboard_performance(
                instance TEXT NOT NULL,path TEXT NOT NULL,minute INTEGER NOT NULL,
                filter_active INTEGER NOT NULL DEFAULT 0,entity_count INTEGER NOT NULL DEFAULT 0,
                sample_ms INTEGER NOT NULL DEFAULT 0,frames INTEGER NOT NULL DEFAULT 0,
                payload_bytes INTEGER NOT NULL DEFAULT 0,updates INTEGER NOT NULL DEFAULT 0,
                hydration_updates INTEGER NOT NULL DEFAULT 0,observer_micros INTEGER NOT NULL DEFAULT 0,
                dropped_frames INTEGER NOT NULL DEFAULT 0,state_task_micros INTEGER NOT NULL DEFAULT 0,
                state_task_max_micros INTEGER NOT NULL DEFAULT 0,interaction_count INTEGER NOT NULL DEFAULT 0,
                interaction_max_micros INTEGER NOT NULL DEFAULT 0,input_delay_micros INTEGER NOT NULL DEFAULT 0,
                interaction_processing_micros INTEGER NOT NULL DEFAULT 0,presentation_micros INTEGER NOT NULL DEFAULT 0,
                loaf_count INTEGER NOT NULL DEFAULT 0,blocking_micros INTEGER NOT NULL DEFAULT 0,
                loaf_max_micros INTEGER NOT NULL DEFAULT 0,script_micros INTEGER NOT NULL DEFAULT 0,
                render_micros INTEGER NOT NULL DEFAULT 0,long_task_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(instance,path,minute))""",
            "CREATE INDEX dashboard_performance_age ON dashboard_performance(minute)",
            """CREATE TABLE app_state_revision(
                revision INTEGER PRIMARY KEY AUTOINCREMENT,
                committed_at INTEGER NOT NULL,
                namespace TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT 'app')""",
            """CREATE TABLE app_state_namespace(
                namespace TEXT PRIMARY KEY,
                imported_at INTEGER NOT NULL,
                legacy_name TEXT NOT NULL DEFAULT '')""",
            """CREATE TABLE app_state(
                namespace TEXT NOT NULL,
                state_key TEXT NOT NULL,
                value_type TEXT NOT NULL,
                value_text TEXT,
                updated_at INTEGER NOT NULL,
                revision INTEGER NOT NULL,
                PRIMARY KEY(namespace,state_key),
                FOREIGN KEY(revision) REFERENCES app_state_revision(revision))""",
            "CREATE INDEX app_state_updated ON app_state(namespace,updated_at)",
            """CREATE TABLE proximity_model(
                fingerprint TEXT PRIMARY KEY,
                algorithm_version INTEGER NOT NULL,
                behavior_signature TEXT NOT NULL DEFAULT '',
                snapshot_json TEXT NOT NULL,
                ready INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL)""",
            """CREATE TABLE proximity_rollup(
                fingerprint TEXT NOT NULL,
                bucket INTEGER NOT NULL,
                sample_count INTEGER NOT NULL,
                raw_min REAL NOT NULL,
                raw_max REAL NOT NULL,
                raw_sum REAL NOT NULL,
                raw_square_sum REAL NOT NULL,
                excursion_count INTEGER NOT NULL DEFAULT 0,
                gesture_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(fingerprint,bucket),
                FOREIGN KEY(fingerprint) REFERENCES proximity_model(fingerprint) ON DELETE CASCADE)""",
            "CREATE INDEX proximity_rollup_age ON proximity_rollup(bucket)",
            """CREATE TABLE proximity_episode(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                fingerprint TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                peak_level INTEGER NOT NULL,
                completed INTEGER NOT NULL,
                guided INTEGER NOT NULL,
                FOREIGN KEY(fingerprint) REFERENCES proximity_model(fingerprint) ON DELETE CASCADE)""",
            "CREATE INDEX proximity_episode_age ON proximity_episode(started_at)",
            """CREATE TABLE ambient_lux_minute(
                context_id TEXT NOT NULL,source_id TEXT NOT NULL,minute INTEGER NOT NULL,
                lux_integral REAL NOT NULL DEFAULT 0,coverage_ms INTEGER NOT NULL DEFAULT 0,
                min_lux REAL NOT NULL DEFAULT 0,max_lux REAL NOT NULL DEFAULT 0,last_lux REAL NOT NULL DEFAULT 0,
                sample_count INTEGER NOT NULL DEFAULT 0,baseline_log_integral REAL NOT NULL DEFAULT 0,
                baseline_coverage_ms INTEGER NOT NULL DEFAULT 0,
                 PRIMARY KEY(context_id,source_id,minute))""",
            "CREATE INDEX ambient_lux_minute_age ON ambient_lux_minute(minute)",
        )
    }
}
