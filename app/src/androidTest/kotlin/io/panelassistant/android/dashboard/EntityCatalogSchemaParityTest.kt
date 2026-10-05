package io.panelassistant.android.dashboard

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.panelassistant.android.CoreInstrumentation
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
 * The shared v11 fixture is deliberately a *literal copy of what the v0.9.5 tag shipped* rather than a
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
    private val context = HistoricalCatalogFixture.isolatedContext(
        ApplicationProvider.getApplicationContext<Context>(), "schema-parity",
    )

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

    /** Realized schemas must preserve the additive contract at each step that declares it. */
    @Test fun additiveStepsPreserveExistingColumnsAndAllowOlderInserts() {
        HistoricalCatalogFixture.create(context).use { db ->
            val steps = EntityCatalogSchema.plan(11, 13)
            assertEquals(listOf(12, 13), steps.map { it.to })
            steps.forEach { step ->
                assertTrue("step ${step.from}->${step.to} must remain additive", !step.breaksCompatibility)
                val baseline = columns(db)
                step.sql.forEach(db::execSQL)
                step.transform?.invoke(db)
                assertAdditive(baseline, columns(db))
            }
        }
    }

    @Test fun schema14ChangesOnlyTheDeclaredNamesAndRetiredPerformanceTable() {
        HistoricalCatalogFixture.create(context).use { db ->
            EntityCatalogSchema.plan(11, 13).forEach { step ->
                step.sql.forEach(db::execSQL)
                step.transform?.invoke(db)
            }
            val baseline = columns(db)
            val step = EntityCatalogSchema.plan(13, 14).single()
            assertTrue("the naming migration must declare its compatibility break", step.breaksCompatibility)
            step.sql.forEach(db::execSQL)
            step.transform?.invoke(db)
            val tables = mapOf(
                "membership" to "dashboard_entity", "minute_rollup" to "dashboard_entity_traffic_minute",
                "dashboard_issue_ignore" to "dashboard_ignored_issue", "proximity_rollup" to "proximity_sample",
            )
            val names = mapOf(
                "entity.first_seen" to "first_seen_at", "entity.last_seen" to "last_seen_at",
                "dashboard.last_sync" to "last_sync_at", "membership.static_ref" to "referenced_by_config",
                "membership.runtime_ref" to "referenced_at_runtime", "membership.first_access" to "first_access_at",
                "membership.last_access" to "last_access_at", "membership.rate_window_start" to "rate_window_started_at",
                "minute_rollup.span_start" to "span_started_at", "proximity_rollup.raw_square_sum" to "raw_sum_squares",
            )
            val expected = baseline.filterValues { it.table != "dashboard_performance" }.map { (key, column) ->
                column.copy(table = tables[column.table] ?: column.table, name = names[key] ?: column.name)
            }.associateBy { "${it.table}.${it.name}" }
            assertEquals("v14 must preserve every other column and its constraints", expected, columns(db))
        }
    }

    private fun assertAdditive(baseline: Map<String, Column>, current: Map<String, Column>) {
        val removed = baseline.keys - current.keys
        assertTrue("additive steps must not remove or rename columns: $removed", removed.isEmpty())

        val retyped = baseline.filter { (key, column) -> current[key]?.type != column.type }
            .map { (key, column) -> "$key was ${column.type}, now ${current[key]?.type}" }
        assertTrue("additive steps must not retype columns: $retyped", retyped.isEmpty())

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
        HistoricalCatalogFixture.create(context).close()
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

    private fun clean() = HistoricalCatalogFixture.clean(context)

}
