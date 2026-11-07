package io.panelassistant.android.dashboard

import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.persistence.ConfigVault
import io.panelassistant.android.persistence.DashboardIgnoredIssue
import io.panelassistant.android.persistence.DashboardIssueBackup
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.storage.DatabaseBusyRetry
import io.panelassistant.android.storage.StorageHealthRuntime
import io.panelassistant.android.util.Cached
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Writer

/** Bounded, derived entity/catalog evidence. Credentials are never stored here. */
class EntityCatalogStore internal constructor(
    context: Context,
    databaseUsageNowMs: () -> Long,
    measureUsage: (EntityCatalogStore) -> DatabaseUsage,
) : SQLiteOpenHelper(context, DATABASE_NAME, null, VERSION) {
    constructor(context: Context) : this(context, Cached.MONOTONIC_MS, { it.measureDatabaseUsage() })

    /** Held for the config vault: a freshly created database may need configuration restored into it. */
    internal val appContext: Context = context.applicationContext ?: context
    internal val databaseTarget: File = context.getDatabasePath(DATABASE_NAME)
    internal val maintenanceGate = MaintenanceIntervalGate(MAINTENANCE_INTERVAL_MS)
    internal val busyRetry = DatabaseBusyRetry()

    /** Closing races a retry backoff; the flag lets an in-flight run give up instead of sleeping on. */
    @Volatile
    private var busyRetryAbandoned = false
    private var restoredOpenPending = false
    private var retainedGuardJoinPending = false
    private var openedRetainedGuard: DatabaseRestoreRecord? = null

    internal fun isBusyRetryAbandoned(): Boolean = busyRetryAbandoned

    override fun close() {
        busyRetryAbandoned = true
        super.close()
    }
    internal val databaseUsageCache = Cached(10_000L, databaseUsageNowMs) { measureUsage(this) }

    /**
     * Report a completed catalog/history mutation, or latch its original SQLite failure and rethrow.
     *
     * A failure classified BUSY is expected contention between this app's own connection pools, not
     * storage-fault evidence, so it is re-attempted within one bounded [DatabaseBusyRetry] budget
     * before the unchanged latch path decides. Every block is self-contained — its transaction rolls
     * back with its own throw — so a re-run cannot double-apply. Non-BUSY failures never retry.
     */
    internal inline fun <T> observedWrite(
        operation: String,
        reportsSuccessfulWrite: (T) -> Boolean = { true },
        write: () -> T,
    ): T {
        val retry = busyRetry.begin()
        while (true) {
            try {
                return write().also { if (reportsSuccessfulWrite(it)) StorageHealthRuntime.recordDatabaseWriteSuccess() }
            } catch (failure: SQLException) {
                if (!retry.admitRetry(failure, ::isBusyRetryAbandoned)) {
                    StorageHealthRuntime.recordDatabaseFailure(operation, failure)
                    throw failure
                }
            }
        }
    }

    /** Schema setup can contain a best-effort vaulted restore; never clear a failure it retained. */
    private inline fun observedSchemaWrite(operation: String, write: () -> Unit) = try {
        write()
    } catch (failure: SQLException) {
        StorageHealthRuntime.recordDatabaseFailure(operation, failure)
        throw failure
    }

    init {
        // Use the helper-level API so WAL is enabled before the database is opened. Calling
        // SQLiteDatabase.enableWriteAheadLogging() from onConfigure is not supported consistently on
        // the Android 8-era SQLite builds used by several target panels.
        setWriteAheadLoggingEnabled(true)
        EntityCatalogStorePreOpen.DATABASE_RESTORE_OPEN_LEASE.reconcileAndOpen(
            establishedGuardReceipt = { record ->
                DatabaseRestoreTransaction(databaseTarget).establishedGuardReceipt(record)
            },
            reconcile = { EntityCatalogStorePreOpen.reconcileSchema(context).action == SchemaReconcileAction.RESTORED },
            open = ::openRestoredDatabaseOwner,
        )
    }

    /** Called only while the process-wide restore lease excludes every competing helper open. */
    private fun openRestoredDatabaseOwner(joiningRetainedGuard: Boolean): DatabaseRestoreRecord? {
        retainedGuardJoinPending = joiningRetainedGuard
        restoredOpenPending = !joiningRetainedGuard
        openedRetainedGuard = null
        return try {
            writableDatabase
            openedRetainedGuard
        } finally {
            restoredOpenPending = false
            retainedGuardJoinPending = false
        }
    }

    data class StateRow(val entityId: String, val state: String, val friendlyName: String = "")
    data class ProximityModelRow(
        val fingerprint: String,
        val algorithmVersion: Int,
        val behaviorSignature: String,
        val snapshotJson: String,
        val ready: Boolean,
        val updatedAt: Long,
    )
    data class ProximityRollupRow(
        val fingerprint: String,
        val bucket: Long,
        val sampleCount: Int,
        val rawMin: Double,
        val rawMax: Double,
        val rawSum: Double,
        val rawSquareSum: Double,
        val excursionCount: Int,
        val gestureCount: Int,
    )
    data class ProximityEpisodeRow(
        val fingerprint: String,
        val startedAt: Long,
        val durationMs: Long,
        val peakLevel: Int,
        val completed: Boolean,
        val guided: Boolean,
    )
    data class Snapshot(
        val state: String,
        val lastSyncAt: Long,
        val analyzerPolicyVersion: Int,
        val catalogCount: Int,
        val activeCount: Int,
        val unresolvedCount: Int,
        val issueCount: Int,
        val blockingIssueCount: Int,
        val ignoredIssueCount: Int,
        val error: String,
        val dbBytes: Long,
    )

    /** Bounded storage evidence for the one app-owned SQLite database. Paths never leave this owner. */
    data class DatabaseUsage(
        val usedBytes: Long,
        val diskBytes: Long,
        val schemaVersion: Int,
    )

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) = observedSchemaWrite("database-create") {
        EntityCatalogSchema.createTables(db)
        // A database is created fresh either on first install (nothing to recover) or because the
        // previous one was set aside as out-of-contract or too new. The second case is the crisis this
        // vault exists for: without a restore the owner silently loses their dashboard, so recover here,
        // where configuration is provably empty because these tables were just created.
        EntityCatalogStorePreOpen.restoreConfigurationInto(db, appContext)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        observedSchemaWrite("database-upgrade") {
        for (step in EntityCatalogSchema.plan(oldVersion, newVersion)) {
            for (sql in step.sql) execMigration(db, sql)
            step.transform?.invoke(db)
        }
    }

    /**
     * Runs one migration statement, tolerating "already exists" / "duplicate column" so a re-upgrade
     * over a physically-newer database — e.g. after a downgrade left user_version stamped below objects
     * that are already physically present — cannot throw and take config down. Any other error still
     * propagates. Inert on a normal first-time upgrade, where no object yet exists.
     */
    private fun execMigration(db: SQLiteDatabase, sql: String) {
        try {
            db.execSQL(sql)
        } catch (e: SQLException) {
            val message = e.message?.lowercase()
            val normalized = sql.trimStart().uppercase()
            val duplicateTolerated = normalized.startsWith("CREATE TABLE") ||
                normalized.startsWith("CREATE INDEX") || normalized.startsWith("ALTER TABLE")
            if (!duplicateTolerated || message == null ||
                !(message.contains("already exists") || message.contains("duplicate column"))) {
                throw e
            }
            Log.w("EntityCatalogStore", "migration skipped, object already exists: ${sql.trim().take(80)}")
        }
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        observedSchemaWrite("database-downgrade-tripwire") {
            // DB_COMPAT_MUTATION_ANCHOR: RUNTIME_DOWNGRADE_TRIPWIRE
            // Pre-open admission must have restored a candidate-readable snapshot. Reaching this callback
            // means the actual SQLite state changed afterward; throw before SQLiteOpenHelper can stamp the
            // newer database down to this build's version.
            throw DatabaseCompatibilityException(
                DatabaseCompatibilityRefusal.DATABASE_CHANGED_AFTER_OBSERVATION,
            )
        }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (retainedGuardJoinPending || !restoredOpenPending) return
        when (val receipt = DatabaseRestoreTransaction(databaseTarget).settleRestoredAfterOpen()) {
            DatabaseRestoreOpenedReceipt.Absent,
            DatabaseRestoreOpenedReceipt.OrdinaryConsumed -> Unit
            is DatabaseRestoreOpenedReceipt.GuardRetained -> openedRetainedGuard = receipt.record
            DatabaseRestoreOpenedReceipt.Hold -> {
                throw DatabaseRestoreHoldException("database restore receipt could not be consumed")
            }
        }
    }

    fun markStatus(instance: String, path: String, status: String, error: String = ""): Unit =
        observedWrite("catalog-status") {
        writableDatabase.execSQL("INSERT OR IGNORE INTO dashboard(instance,path) VALUES(?,?)", arrayOf(instance, path))
        writableDatabase.execSQL("UPDATE dashboard SET status=?,error=? WHERE instance=? AND path=?", arrayOf(status, error.take(500), instance, path))
    }

    /** Return the dashboard to active only while its stored error is still [ifError]: one statement, so a
     *  newer failure written by a sync or probe since the caller's decision is kept. */
    fun recoverStatus(instance: String, path: String, ifError: String): Unit = observedWrite("catalog-status") {
        writableDatabase.execSQL(RECOVER_STATUS_SQL, arrayOf(instance, path, ifError.take(500)))
    }

    /** Commit only a complete successful HA snapshot; failures never age current rows. */
    fun commitSync(
        instance: String,
        path: String,
        states: List<StateRow>,
        metadata: Map<String, String>,
        configJson: String,
        configHash: String,
        derived: Set<String>,
        unresolved: List<String>,
        status: String,
        now: Long,
        issues: List<JSONObject> = emptyList(),
        defaultIgnoredFingerprints: Set<String> = emptySet(),
    ): Unit = observedWrite("catalog-sync") {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE entity SET missing_streak=missing_streak+1 WHERE instance=?", arrayOf(instance))
            // The catalogue UI and promotion policy never read state attributes. Persisting them copied
            // the full /api/states payload into SQLite and made attribute-heavy installations spend
            // minutes allocating JSON strings and writing megabytes. A compiled upsert keeps the useful
            // state/registry projection and preserves first_seen_at in one statement per entity.
            val upsert = db.compileStatement(
                """INSERT OR REPLACE INTO entity(instance,entity_id,state,attributes_json,metadata_json,
                   first_seen_at,last_seen_at,missing_streak,tombstone_at)
                   VALUES(?,?,?,'{}',?,coalesce((SELECT first_seen_at FROM entity WHERE instance=? AND entity_id=?),?),?,0,0)""",
            )
            try {
                for (row in states) {
                    upsert.clearBindings()
                    upsert.bindString(1, instance)
                    upsert.bindString(2, row.entityId)
                    upsert.bindString(3, row.state)
                    upsert.bindString(4, metadata[row.entityId] ?: "{}")
                    upsert.bindString(5, instance)
                    upsert.bindString(6, row.entityId)
                    upsert.bindLong(7, now)
                    upsert.bindLong(8, now)
                    upsert.executeInsert()
                }
            } finally { upsert.close() }
            db.execSQL("UPDATE entity SET tombstone_at=? WHERE instance=? AND missing_streak>=3 AND tombstone_at=0", arrayOf<Any?>(now, instance))
            db.execSQL("UPDATE dashboard_entity SET referenced_by_config=0 WHERE instance=? AND path=?", arrayOf(instance, path))
            for (id in derived) {
                db.execSQL("INSERT OR IGNORE INTO dashboard_entity(instance,path,entity_id) VALUES(?,?,?)", arrayOf(instance, path, id))
                db.execSQL(
                    "UPDATE dashboard_entity SET referenced_by_config=1,reasons=CASE WHEN instr(reasons,'dashboard')=0 THEN trim(reasons||',dashboard',',') ELSE reasons END WHERE instance=? AND path=? AND entity_id=?",
                    arrayOf(instance, path, id),
                )
            }
            db.execSQL("INSERT OR IGNORE INTO dashboard(instance,path) VALUES(?,?)", arrayOf(instance, path))
            db.execSQL(
                """UPDATE dashboard SET config_hash=?,config_json=?,status=?,last_sync_at=?,error='',
                   unresolved_json=?,issues_json=?,analyzer_policy_version=?,sync_generation=sync_generation+1
                   WHERE instance=? AND path=?""",
                arrayOf<Any?>(
                    configHash,
                    configJson,
                    status,
                    now,
                    JSONArray(unresolved).toString(),
                    EntityCatalogIssuePersistence.boundedJson(issues),
                    DashboardConfigurationLint.ANALYZER_POLICY_VERSION,
                    instance,
                    path,
                ),
            )
            val currentFingerprints = issues.mapNotNull { issue ->
                issue.optString("fingerprint").takeIf(FINGERPRINT::matches)
            }.toSet()
            defaultIgnoredFingerprints.intersect(currentFingerprints).forEach { fingerprint ->
                db.execSQL(
                    "INSERT OR IGNORE INTO dashboard_ignored_issue(instance,path,fingerprint,ignored_at) VALUES(?,?,?,?)",
                    arrayOf<Any?>(instance, path, fingerprint, now),
                )
            }
            val staleIgnores = db.rawQuery(
                "SELECT fingerprint FROM dashboard_ignored_issue WHERE instance=? AND path=?",
                arrayOf(instance, path),
            ).use { cursor -> buildList {
                while (cursor.moveToNext()) cursor.getString(0).takeIf { it !in currentFingerprints }?.let(::add)
            } }
            staleIgnores.forEach { fingerprint ->
                db.delete(
                    "dashboard_ignored_issue", "instance=? AND path=? AND fingerprint=?",
                    arrayOf(instance, path, fingerprint),
                )
            }
            val cutoff = tombstoneCutoff(now)
            db.execSQL(
                "$DELETE_MEMBERSHIP_FOR_PURGED_ENTITIES AND e.instance=? AND e.tombstone_at>0 AND e.tombstone_at<?)",
                arrayOf<Any?>(instance, cutoff),
            )
            db.execSQL("DELETE FROM entity WHERE instance=? AND tombstone_at>0 AND tombstone_at<?", arrayOf<Any?>(instance, cutoff))
            db.execSQL("DELETE FROM dashboard_entity_traffic_minute WHERE minute<?", arrayOf(trafficMinuteCutoff(now)))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }.also { observedMaintenance(now) }

    fun recordAccess(instance: String, path: String, counts: Map<String, Long>, now: Long): Unit =
        observedWrite("catalog-access-history") {
        if (counts.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, rawCount) in counts) {
                val count = rawCount.coerceIn(1, 1_000_000)
                db.execSQL("INSERT OR IGNORE INTO dashboard_entity(instance,path,entity_id) VALUES(?,?,?)", arrayOf(instance, path, id))
                db.execSQL(
                    """UPDATE dashboard_entity SET referenced_at_runtime=1,last_access_at=?,access_count=access_count+?,
                       first_access_at=CASE WHEN first_access_at=0 THEN ? ELSE first_access_at END,
                       reasons=CASE WHEN instr(reasons,'runtime')=0 THEN trim(reasons||',runtime',',') ELSE reasons END
                       WHERE instance=? AND path=? AND entity_id=?""",
                    arrayOf<Any?>(now, count, now, instance, path, id),
                )
                db.execSQL("INSERT OR IGNORE INTO dashboard_entity_traffic_minute(instance,path,entity_id,minute) VALUES(?,?,?,?)", arrayOf<Any?>(instance, path, id, now / MINUTE_MS))
                db.execSQL(
                    "UPDATE dashboard_entity_traffic_minute SET access_count=access_count+? WHERE instance=? AND path=? AND entity_id=? AND minute=?",
                    arrayOf<Any?>(count, instance, path, id, now / MINUTE_MS),
                )
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }.also { observedMaintenance(now) }

    fun recordMetrics(instance: String, path: String, metrics: Map<String, Pair<Long, Long>>, now: Long): Unit =
        observedWrite("catalog-metric-history") {
        if (metrics.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, metric) in metrics) {
                // Load evidence covers the complete working stream, not only entities already promoted
                // by static/runtime dependency evidence.
                db.execSQL("INSERT OR IGNORE INTO dashboard_entity(instance,path,entity_id) VALUES(?,?,?)", arrayOf(instance, path, id))
                db.execSQL(
                    """UPDATE dashboard_entity SET update_count=update_count+?,update_bytes=update_bytes+?,
                       rate_update_bytes=CASE WHEN rate_window_started_at=0 OR ?-rate_window_started_at>? THEN ? ELSE rate_update_bytes+? END,
                       rate_window_started_at=CASE WHEN rate_window_started_at=0 OR ?-rate_window_started_at>? THEN ? ELSE rate_window_started_at END,
                       last_update_at=? WHERE instance=? AND path=? AND entity_id=?""",
                    arrayOf<Any?>(
                        metric.first, metric.second,
                        now, RATE_WINDOW_MS, metric.second, metric.second,
                        now, RATE_WINDOW_MS, now - METRIC_BATCH_MS, now,
                        instance, path, id,
                    ),
                )
                db.execSQL("INSERT OR IGNORE INTO dashboard_entity_traffic_minute(instance,path,entity_id,minute) VALUES(?,?,?,?)", arrayOf<Any?>(instance, path, id, now / MINUTE_MS))
                db.execSQL(
                    "UPDATE dashboard_entity_traffic_minute SET update_count=update_count+?,update_bytes=update_bytes+? WHERE instance=? AND path=? AND entity_id=? AND minute=?",
                    arrayOf<Any?>(metric.first, metric.second, instance, path, id, now / MINUTE_MS),
                )
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }.also { observedMaintenance(now) }

    fun setOverride(instance: String, path: String, entityId: String, override: String) {
        setOverrides(instance, path, listOf(entityId), override)
    }

    fun setOverrides(instance: String, path: String, entityIds: Collection<String>, override: String): Unit =
        observedWrite("catalog-overrides") {
        val pinned = if (override == "pinned") 1 else 0
        val excluded = if (override == "forced_exclude") 1 else 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (entityId in entityIds) {
                db.execSQL("INSERT OR IGNORE INTO dashboard_entity(instance,path,entity_id) VALUES(?,?,?)", arrayOf(instance, path, entityId))
                db.execSQL("UPDATE dashboard_entity SET pinned=?,excluded=?,reasons=CASE WHEN ?=0 AND ?=0 THEN replace(replace(reasons,'manual',''),',,',',') WHEN instr(reasons,'manual')=0 THEN trim(reasons||',manual',',') ELSE reasons END WHERE instance=? AND path=? AND entity_id=?",
                    arrayOf<Any?>(pinned, excluded, pinned, excluded, instance, path, entityId))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Clear rebuildable evidence for one dashboard while retaining the instance-wide HA catalog. */
    fun resetEvidence(instance: String, path: String): Unit = observedWrite("catalog-reset") {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("dashboard_entity_traffic_minute", "instance=? AND path=?", arrayOf(instance, path))
            db.delete("dashboard_entity", "instance=? AND path=?", arrayOf(instance, path))
            db.delete("dashboard", "instance=? AND path=?", arrayOf(instance, path))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        recentCache.invalidate(instance to path)
    }

    /**
     * Collapse route-keyed catalogue rows onto their dashboard root, once.
     *
     * An explicit `/lovelace/kiosk` and an `Auto` that resolves to the same dashboard used to occupy two
     * different namespaces here, so switching modes orphaned rows and a later switch back revived them.
     * A rescan cannot recreate what is lost: runtime observations record what the panel actually saw,
     * and ignored issues record what a person decided.
     *
     * Foreign keys are enforced and cannot be turned off inside a transaction, so the parent is created
     * before children move and removed only once nothing references it. Where both namespaces already
     * exist the root wins and the route rows are dropped after contributing anything the root lacks —
     * `INSERT OR IGNORE` keeps the root's own decision rather than letting the older namespace overwrite
     * it. Returns the number of route paths collapsed.
     */
    internal fun migrateRouteKeyedRowsToRoot(rootOf: (String) -> String): Int =
        observedWrite("catalog-scope-migration") {
        val db = writableDatabase
        val children = listOf(
            "dashboard_entity", "dashboard_entity_traffic_minute",
            "dashboard_ignored_issue", "dashboard_metric_minute",
        )
        var collapsed = 0
        db.beginTransaction()
        try {
            val rows = mutableListOf<Pair<String, String>>()
            db.rawQuery("SELECT instance,path FROM dashboard", null).use { cursor ->
                while (cursor.moveToNext()) rows += cursor.getString(0) to cursor.getString(1)
            }
            for (step in planRouteKeyCollapse(rows, rootOf)) {
                if (!step.mergesIntoExisting) copyRow(db, "dashboard", step.instance, step.from, step.to)
                for (table in children) copyRow(db, table, step.instance, step.from, step.to)
                for (table in children) {
                    db.delete(table, "instance=? AND path=?", arrayOf(step.instance, step.from))
                }
                db.delete("dashboard", "instance=? AND path=?", arrayOf(step.instance, step.from))
                recentCache.invalidate(step.instance to step.from)
                recentCache.invalidate(step.instance to step.to)
                collapsed++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        collapsed
    }

    /** Re-insert every row of [table] for one target under [toPath], keeping any row already there. */
    private fun copyRow(db: SQLiteDatabase, table: String, instance: String, fromPath: String, toPath: String) {
        val columns = mutableListOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            while (cursor.moveToNext()) columns += cursor.getString(1)
        }
        if (columns.isEmpty()) return
        val selected = columns.joinToString(",") { if (it == "path") "?" else it }
        db.execSQL(
            "INSERT OR IGNORE INTO $table(${columns.joinToString(",")}) " +
                "SELECT $selected FROM $table WHERE instance=? AND path=?",
            arrayOf(toPath, instance, fromPath),
        )
    }

    fun activeIds(
        instance: String,
        path: String,
        now: Long,
        includeStatic: Boolean = true,
        includeRuntime: Boolean = true,
    ): List<String> {
        val cutoff = now - RUNTIME_RETENTION_MS
        val evidence = buildList {
            add("m.pinned=1")
            if (includeStatic) add("m.referenced_by_config=1")
            if (includeRuntime) add("m.last_access_at>=?")
        }.joinToString(" OR ")
        val args = mutableListOf(instance, path)
        if (includeRuntime) args += cutoff.toString()
        return readableDatabase.rawQuery(
            """SELECT m.entity_id FROM dashboard_entity m LEFT JOIN entity e ON e.instance=m.instance AND e.entity_id=m.entity_id
               WHERE m.instance=? AND m.path=? AND m.excluded=0 AND ($evidence)
               AND (e.entity_id IS NULL OR e.missing_streak<3) ORDER BY m.entity_id""",
            args.toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }

    /** Unpinned dashboard evidence, independent of exclusion and automatic-promotion policy. */
    fun suggestedIds(instance: String, path: String): List<String> = readableDatabase.rawQuery(
        """SELECT m.entity_id FROM dashboard_entity m LEFT JOIN entity e ON e.instance=m.instance AND e.entity_id=m.entity_id
           WHERE m.instance=? AND m.path=? AND m.pinned=0
           AND (m.referenced_by_config=1 OR m.referenced_at_runtime=1)
           AND (e.entity_id IS NULL OR e.missing_streak<3) ORDER BY m.entity_id""",
        arrayOf(instance, path),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    /** Resolve renderer evidence against the current catalog in bounded SQLite-parameter chunks. */
    fun existingEntityIds(instance: String, entityIds: Collection<String>): Set<String> {
        if (entityIds.isEmpty()) return emptySet()
        val result = mutableSetOf<String>()
        entityIds.distinct().chunked(MAX_SQL_ID_FILTER).forEach { chunk ->
            val placeholders = List(chunk.size) { "?" }.joinToString(",")
            readableDatabase.rawQuery(
                "SELECT entity_id FROM entity WHERE instance=? AND missing_streak<3 AND entity_id IN ($placeholders)",
                (listOf(instance) + chunk).toTypedArray(),
            ).use { cursor -> while (cursor.moveToNext()) result += cursor.getString(0) }
        }
        return result
    }

    fun snapshot(instance: String, path: String): Snapshot {
        val dashboard = readableDatabase.rawQuery(
            "SELECT status,last_sync_at,analyzer_policy_version,error,unresolved_json,issues_json FROM dashboard WHERE instance=? AND path=?",
            arrayOf(instance, path),
        ).use { c ->
            if (c.moveToFirst()) {
                DashboardRow(c.getString(0), c.getLong(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5))
            } else null
        }
        fun count(sql: String) = readableDatabase.rawQuery(sql, arrayOf(instance, path)).use { c -> c.moveToFirst(); c.getInt(0) }
        val issueCounts = EntityCatalogIssuePersistence.counts(dashboard?.issuesJson ?: "[]")
        return Snapshot(
            dashboard?.status ?: "disabled", dashboard?.lastSync ?: 0L,
            dashboard?.analyzerPolicyVersion ?: 0,
            readableDatabase.rawQuery("SELECT count(*) FROM entity WHERE instance=? AND missing_streak<3", arrayOf(instance)).use { c -> c.moveToFirst(); c.getInt(0) },
            count("SELECT count(*) FROM dashboard_entity WHERE instance=? AND path=? AND excluded=0 AND (pinned=1 OR referenced_by_config=1 OR referenced_at_runtime=1)"),
            dashboard?.unresolvedJson?.let { runCatching { JSONArray(it).length() }.getOrDefault(0) } ?: 0,
            issueCounts.first,
            issueCounts.second,
            EntityCatalogIssuePersistence.ignoredCount(dashboard?.issuesJson ?: "[]"),
            dashboard?.error ?: "",
            databaseUsage().usedBytes,
        )
    }

    /** Structured, bounded diagnostics only. The raw dashboard configuration is never returned. */
    fun issuesJson(instance: String, path: String): String {
        val stored = readableDatabase.rawQuery(
            "SELECT issues_json FROM dashboard WHERE instance=? AND path=?",
            arrayOf(instance, path),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else "[]" }
        return EntityCatalogIssuePersistence.boundExistingJson(stored)
    }

    fun ignoredIssueFingerprints(instance: String, path: String): Set<String> = readableDatabase.rawQuery(
        "SELECT fingerprint FROM dashboard_ignored_issue WHERE instance=? AND path=? ORDER BY fingerprint",
        arrayOf(instance, path),
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    /** All explicit decisions, including dashboards not currently selected, within the archive budget. */
    internal fun exportIgnoredDashboardIssues(): List<DashboardIgnoredIssue> = readableDatabase.rawQuery(
        "SELECT instance,path,fingerprint,ignored_at FROM dashboard_ignored_issue " +
            "ORDER BY instance,path,fingerprint LIMIT ${DashboardIssueBackup.MAX_ROWS + 1}",
        emptyArray(),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(
                DashboardIgnoredIssue(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)),
            )
        }.also(DashboardIssueBackup::validate)
    }

    /** Recreate only the FK parent on a cleared catalog; normal scanning still owns all derived data. */
    internal fun restoreIgnoredDashboardIssues(rows: List<DashboardIgnoredIssue>): Int {
        DashboardIssueBackup.validate(rows)
        if (rows.isEmpty()) return 0
        return observedWrite("catalog-issue-restore") {
            val db = writableDatabase
            db.beginTransaction()
            try {
                rows.forEach { row ->
                    db.execSQL("INSERT OR IGNORE INTO dashboard(instance,path) VALUES(?,?)", arrayOf(row.instance, row.path))
                    db.execSQL(
                        "INSERT OR REPLACE INTO dashboard_ignored_issue(instance,path,fingerprint,ignored_at) VALUES(?,?,?,?)",
                        arrayOf<Any?>(row.instance, row.path, row.fingerprint, row.ignoredAt),
                    )
                }
                rows.map { it.instance to it.path }.distinct().forEach { (instance, path) ->
                    val issues = db.rawQuery(
                        "SELECT issues_json FROM dashboard WHERE instance=? AND path=?", arrayOf(instance, path),
                    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "[]" }
                    val ignored = db.rawQuery(
                        "SELECT fingerprint FROM dashboard_ignored_issue WHERE instance=? AND path=?", arrayOf(instance, path),
                    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                    db.execSQL(
                        "UPDATE dashboard SET issues_json=? WHERE instance=? AND path=?",
                        arrayOf(EntityCatalogIssuePersistence.applyIgnores(JSONArray(issues), ignored), instance, path),
                    )
                }
                val present = rows.count { row ->
                    db.rawQuery(
                        "SELECT ignored_at FROM dashboard_ignored_issue WHERE instance=? AND path=? AND fingerprint=?",
                        arrayOf(row.instance, row.path, row.fingerprint),
                    ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) == row.ignoredAt }
                }
                db.setTransactionSuccessful()
                present
            } finally {
                db.endTransaction()
            }
        }
    }

    /** Persist a dashboard-scoped diagnostic override only for an issue visible in the current scan. */
    fun setIssueIgnored(instance: String, path: String, fingerprint: String, ignored: Boolean, now: Long): Boolean =
        observedWrite("catalog-issue-override", reportsSuccessfulWrite = { it }) {
        if (!FINGERPRINT.matches(fingerprint)) return@observedWrite false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stored = db.rawQuery(
                "SELECT issues_json FROM dashboard WHERE instance=? AND path=?",
                arrayOf(instance, path),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: return@observedWrite false
            val issues = JSONArray(EntityCatalogIssuePersistence.boundExistingJson(stored))
            val issue = (0 until issues.length()).asSequence().mapNotNull(issues::optJSONObject)
                .firstOrNull { it.optString("fingerprint") == fingerprint }
                ?: return@observedWrite false
            if (!EntityCatalogIssuePersistence.canIgnore(issue)) return@observedWrite false
            if (ignored) {
                db.execSQL(
                    "INSERT OR REPLACE INTO dashboard_ignored_issue(instance,path,fingerprint,ignored_at) VALUES(?,?,?,?)",
                    arrayOf<Any?>(instance, path, fingerprint, now),
                )
            } else {
                db.delete(
                    "dashboard_ignored_issue", "instance=? AND path=? AND fingerprint=?",
                    arrayOf(instance, path, fingerprint),
                )
            }
            val ignoredSet = db.rawQuery(
                "SELECT fingerprint FROM dashboard_ignored_issue WHERE instance=? AND path=?",
                arrayOf(instance, path),
            ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            db.execSQL(
                "UPDATE dashboard SET issues_json=? WHERE instance=? AND path=?",
                arrayOf(EntityCatalogIssuePersistence.applyIgnores(issues, ignoredSet), instance, path),
            )
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }

    /** The revision the last committed scan recorded for this dashboard, or blank before any scan. */
    fun dashboardConfigHash(instance: String, path: String): String = readableDatabase.rawQuery(
        "SELECT config_hash FROM dashboard WHERE instance=? AND path=?",
        arrayOf(instance, path),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else "" }

    /** Internal-only source for rebuilding bounded derived diagnostics after process restart. */
    fun dashboardConfigJson(instance: String, path: String): String = readableDatabase.rawQuery(
        "SELECT config_json FROM dashboard WHERE instance=? AND path=?",
        arrayOf(instance, path),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "{}" }

    fun entitiesJson(
        instance: String,
        path: String,
        query: String,
        filter: String,
        limit: Int,
        offset: Int,
        sortKey: String = "entity_id",
        sortDirection: String = "asc",
        maxLimit: Int = 500,
        includeIds: Set<String>? = null,
        excludeIds: Set<String> = emptySet(),
    ): String {
        val span = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_LIST)
        var outputBytes = 0L
        var outputRows = 0L
        try {
        val where = mutableListOf("e.instance=?")
        val args = mutableListOf(instance)
        if (query.isNotBlank()) {
            val raw = query.trim().take(100)
            val slug = raw.lowercase().replace('-', '_').replace(' ', '_')
            if (slug == raw) {
                where += "e.entity_id LIKE ?"; args += "%$raw%"
            } else {
                where += "(e.entity_id LIKE ? OR e.entity_id LIKE ?)"
                args += "%$raw%"; args += "%$slug%"
            }
        }
        when (filter) {
            "active" -> where += "m.excluded=0 AND (m.pinned=1 OR m.referenced_by_config=1 OR m.referenced_at_runtime=1)"
            "excluded" -> where += "m.excluded=1"
            "missing" -> where += "e.missing_streak>0"
            // A pin is never removed automatically, so the only way an operator learns that one no
            // longer earns its place is to be shown it. Third branch: pinned, present in Home
            // Assistant, but neither referenced by this dashboard's configuration nor accessed at
            // runtime — a subscription being paid for with nothing asking for it. It is reported for
            // manual unpinning, never acted on.
            "review" -> where += "(e.missing_streak>0 OR (coalesce(m.update_count,0)>0 AND coalesce(m.last_access_at,0)=0 AND coalesce(m.referenced_by_config,0)=0 AND coalesce(m.pinned,0)=0) OR (coalesce(m.pinned,0)=1 AND coalesce(m.referenced_by_config,0)=0 AND coalesce(m.referenced_at_runtime,0)=0))"
            "candidate" -> where += "coalesce(m.pinned,0)=0 AND (m.referenced_by_config=1 OR m.referenced_at_runtime=1)"
            "unpinned" -> where += "coalesce(m.pinned,0)=0"
            else -> Unit
        }
        // Every displayed ordering is executed by SQLite with LIMIT/OFFSET. In particular, recent
        // access/rate ordering must never materialize and sort the complete HA catalog in the app.
        val sqlIdFiltersFit = (includeIds?.size ?: 0) + excludeIds.size <= MAX_SQL_ID_FILTER
        val boundedIdFallback = !sqlIdFiltersFit
        if (includeIds != null) {
            if (includeIds.isEmpty()) {
                where += "0"
            } else if (sqlIdFiltersFit) {
                where += "e.entity_id IN (${includeIds.joinToString(",") { "?" }})"
                args += includeIds.sorted()
            }
        }
        if (excludeIds.isNotEmpty()) {
            if (sqlIdFiltersFit) {
                where += "e.entity_id NOT IN (${excludeIds.joinToString(",") { "?" }})"
                args += excludeIds.sorted()
            }
        }
        val join = "LEFT JOIN dashboard_entity m ON m.instance=e.instance AND m.entity_id=e.entity_id AND m.path=?"
        val now = System.currentTimeMillis()
        val normalizedSort = EntityCatalogSorting.key(sortKey)
        val requiresRecentOrdering = normalizedSort == "access_1h" || normalizedSort == "rate_1h_bps"
        val recentJoin = if (requiresRecentOrdering) {
            "LEFT JOIN (${recentAggregateSql(now)}) r ON r.entity_id=e.entity_id"
        } else ""
        val observedMs = "max($METRIC_BATCH_MS,min($HOUR_MS,$now-max(coalesce(r.first_minute,0)*$MINUTE_MS,${now - HOUR_MS})))"
        val recentProjection = if (requiresRecentOrdering) {
            """coalesce(r.access_1m,0),coalesce(r.access_1h,0) AS recent_access_1h,coalesce(r.access_1d,0),
               coalesce(r.bytes_1m,0),coalesce(r.bytes_1h,0),coalesce(r.bytes_1d,0),coalesce(r.first_minute,0),
               (coalesce(r.bytes_1h,0)*1000.0/$observedMs) AS recent_rate_1h"""
        } else "0,0 AS recent_access_1h,0,0,0,0,0,0.0 AS recent_rate_1h"
        val baseSql = """SELECT e.entity_id,e.state,e.metadata_json,e.first_seen_at,e.last_seen_at,e.missing_streak,
            coalesce(m.referenced_by_config,0),coalesce(m.referenced_at_runtime,0),coalesce(m.pinned,0),coalesce(m.excluded,0),
            coalesce(m.reasons,''),coalesce(m.last_access_at,0),coalesce(m.access_count,0),coalesce(m.update_count,0),coalesce(m.update_bytes,0),
            coalesce(m.rate_window_started_at,0),coalesce(m.rate_update_bytes,0),coalesce(m.last_update_at,0),
            $recentProjection
            FROM entity e $join $recentJoin WHERE ${where.joinToString(" AND ")}"""
        val effectiveLimit = limit.coerceIn(1, maxLimit.coerceIn(1, MAX_SQL_ID_FILTER))
        val effectiveOffset = offset.coerceAtLeast(0)
        val recentSnapshot = recentSnapshot(instance, path, now)
        val ranks = recentSnapshot.ranks
        val pageArgs = if (requiresRecentOrdering) listOf(path, instance, path) + args else listOf(path) + args
        val orderBy = requireNotNull(EntityCatalogSorting.sqlOrder(sortKey, sortDirection))
        val total: Int
        val selected: List<CatalogRow>
        if (boundedIdFallback) {
            // SQLite cannot accept an arbitrary 50k-id IN/NOT IN predicate. Stream only globally
            // ordered IDs, apply the existing bounded sets in-process, and retain the requested page.
            val collector = BoundedEntityIdPageCollector(effectiveLimit, effectiveOffset, includeIds, excludeIds)
            val idProjection = if (requiresRecentOrdering) {
                """e.entity_id,coalesce(r.access_1h,0) AS recent_access_1h,
                   (coalesce(r.bytes_1h,0)*1000.0/$observedMs) AS recent_rate_1h"""
            } else "e.entity_id"
            val idSql = """SELECT $idProjection FROM entity e $join $recentJoin
                WHERE ${where.joinToString(" AND ")} ORDER BY $orderBy"""
            readableDatabase.rawQuery(idSql, pageArgs.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) collector.offer(cursor.getString(0))
            }
            total = collector.total
            val selectedIds = collector.pageIds
            if (selectedIds.isEmpty()) {
                selected = emptyList()
            } else {
                val placeholders = List(selectedIds.size) { "?" }.joinToString(",")
                val fetchSql = "$baseSql AND e.entity_id IN ($placeholders)"
                val fetched = HashMap<String, CatalogRow>(selectedIds.size)
                readableDatabase.rawQuery(fetchSql, (pageArgs + selectedIds).toTypedArray()).use { cursor ->
                    while (cursor.moveToNext()) {
                        cursor.catalogRow(recentSnapshot)?.let { fetched[it.entityId] = it }
                    }
                }
                selected = selectedIds.mapNotNull(fetched::get)
            }
        } else {
            total = readableDatabase.rawQuery(
                "SELECT count(*) FROM entity e $join WHERE ${where.joinToString(" AND ")}",
                (listOf(path) + args).toTypedArray(),
            ).use { c -> c.moveToFirst(); c.getInt(0) }
            val matched = ArrayList<CatalogRow>(effectiveLimit)
            val sql = "$baseSql ORDER BY $orderBy LIMIT $effectiveLimit OFFSET $effectiveOffset"
            readableDatabase.rawQuery(sql, pageArgs.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) cursor.catalogRow(recentSnapshot)?.let(matched::add)
            }
            selected = matched
        }
        val rows = JSONArray()
        val selectedRecent = if (requiresRecentOrdering) emptyMap() else recentStatsForIds(
            instance, path, now, selected.map { it.entityId },
        )
        selected.forEach { rawRow ->
            outputRows++
            val r = rawRow.recent ?: selectedRecent[rawRow.entityId]
            val row = if (r == null || rawRow.recent != null) rawRow else rawRow.copy(
                recent = r,
                rate1h = r.bytes1h / observedSeconds(recentSnapshot.generatedAt, r.firstMinute, HOUR_MS),
            )
            rows.put(catalogRowJson(row, now, recentSnapshot, ranks))
        }
        return JSONObject().put("items", rows).put("limit", effectiveLimit).put("offset", effectiveOffset)
            .put("total", total).put("sort", EntityCatalogSorting.key(sortKey))
            .put("direction", EntityCatalogSorting.direction(sortDirection)).toString().also {
                outputBytes = it.toByteArray(Charsets.UTF_8).size.toLong()
            }
        } catch (error: Exception) {
            span.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            span.work(units = outputRows, bytes = outputBytes).close()
        }
    }

    /** Small diagnostics projection for the performance card; SQLite ranks before the fixed LIMIT. */
    fun performanceSummaryJson(instance: String, path: String, now: Long = System.currentTimeMillis()): String {
        val oneHour = (now - HOUR_MS) / MINUTE_MS
        val rows = JSONArray()
        val sql = """SELECT entity_id,sum(update_count),sum(update_bytes)
            FROM dashboard_entity_traffic_minute WHERE instance=? AND path=? AND minute>=?
            GROUP BY entity_id HAVING sum(update_count)>0
            ORDER BY sum(update_bytes) DESC,entity_id COLLATE NOCASE LIMIT 3"""
        readableDatabase.rawQuery(sql, arrayOf(instance, path, oneHour.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                rows.put(JSONObject()
                    .put("entityId", cursor.getString(0))
                    .put("updates1h", cursor.getLong(1))
                    .put("payloadBytes1h", cursor.getLong(2)))
            }
        }
        return rows.toString()
    }

    private fun catalogRowJson(
        row: CatalogRow,
        now: Long,
        recentSnapshot: RecentSnapshot,
        ranks: RecentRanks = recentSnapshot.ranks,
    ): JSONObject = JSONObject().apply {
        put("entity_id", row.entityId); put("state", row.state); put("metadata", JSONObject(row.metadataJson))
        put("first_seen_at", row.firstSeen); put("last_seen_at", row.lastSeen); put("missing_streak", row.missingStreak)
        put("static", row.staticRef); put("runtime", row.runtimeRef); put("pinned", row.pinned)
        put("excluded", row.excluded); put("reasons", row.reasons); put("last_access_at", row.lastAccess)
        put("access_count", row.accessCount); put("update_count", row.updateCount); put("update_bytes", row.updateBytes)
        val rate = if (row.rateWindowStart == 0L || row.lastUpdateAt == 0L || now - row.lastUpdateAt > RATE_STALE_MS) 0.0
            else row.rateUpdateBytes * 1000.0 / (now - row.rateWindowStart).coerceAtLeast(1000L)
        put("data_rate_bps", rate)
        val r = row.recent
        if (r != null) {
            put("access_1m", r.access1m); put("access_1h", r.access1h); put("access_1d", r.access1d)
            put("rate_1m_bps", r.bytes1m / observedSeconds(recentSnapshot.generatedAt, r.firstMinute, MINUTE_MS))
            put("rate_1h_bps", row.rate1h); put("rate_1d_bps", r.bytes1d / observedSeconds(recentSnapshot.generatedAt, r.firstMinute, 24L * HOUR_MS))
            put("access_1m_rank", ranks.rank(r.access1m, ranks.access1m)); put("access_1h_rank", ranks.rank(r.access1h, ranks.access1h))
            put("access_1d_rank", ranks.rank(r.access1d, ranks.access1d)); put("rate_1m_rank", ranks.rank(r.bytes1m, ranks.bytes1m))
            put("rate_1h_rank", ranks.rank(r.bytes1h, ranks.bytes1h)); put("rate_1d_rank", ranks.rank(r.bytes1d, ranks.bytes1d))
        } else {
            put("access_1m", 0); put("access_1h", 0); put("access_1d", 0)
            put("rate_1m_bps", 0); put("rate_1h_bps", 0); put("rate_1d_bps", 0)
            put("access_1m_rank", 0); put("access_1h_rank", 0); put("access_1d_rank", 0)
            put("rate_1m_rank", 0); put("rate_1h_rank", 0); put("rate_1d_rank", 0)
        }
    }

    /** Stream a valid, self-describing export under hard row, UTF-8 byte, and elapsed-time limits. */
    internal fun writeExportJson(
        instance: String,
        path: String,
        writer: Writer,
        policy: EntityExportPolicy = EntityExportPolicy(),
        now: Long = System.currentTimeMillis(),
        monotonicNanos: () -> Long = System::nanoTime,
    ): EntityExportResult {
        val span = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_EXPORT)
        val startedAt = monotonicNanos()
        var bytes = 0L
        var rows = 0
        var reason: String? = null
        try {
            val header = entityExportHeader(EntityLearningProtocol.hash(instance), path, now)
            writer.write(header)
            bytes += header.utf8Size()
            val recentSnapshot = recentSnapshot(instance, path, now)
            val recentJoin = "LEFT JOIN (${recentAggregateSql(now)}) r ON r.entity_id=e.entity_id"
            val sql = """SELECT e.entity_id,e.state,e.metadata_json,e.first_seen_at,e.last_seen_at,e.missing_streak,
                coalesce(m.referenced_by_config,0),coalesce(m.referenced_at_runtime,0),coalesce(m.pinned,0),coalesce(m.excluded,0),
                coalesce(m.reasons,''),coalesce(m.last_access_at,0),coalesce(m.access_count,0),coalesce(m.update_count,0),coalesce(m.update_bytes,0),
                coalesce(m.rate_window_started_at,0),coalesce(m.rate_update_bytes,0),coalesce(m.last_update_at,0),
                coalesce(r.access_1m,0),coalesce(r.access_1h,0),coalesce(r.access_1d,0),
                coalesce(r.bytes_1m,0),coalesce(r.bytes_1h,0),coalesce(r.bytes_1d,0),coalesce(r.first_minute,0)
                FROM entity e LEFT JOIN dashboard_entity m ON m.instance=e.instance AND m.entity_id=e.entity_id AND m.path=?
                $recentJoin WHERE e.instance=? ORDER BY e.entity_id COLLATE NOCASE,e.entity_id LIMIT ${policy.maxRows + 1}"""
            readableDatabase.rawQuery(sql, arrayOf(path, instance, path, instance)).use { cursor ->
                while (cursor.moveToNext()) {
                    val admissionReason = exportTruncationReason(
                        policy, rows, bytes, nextRowBytes = 0L,
                        elapsedNanos = forwardElapsedNanos(monotonicNanos(), startedAt),
                    )
                    if (admissionReason != null) { reason = admissionReason; break }
                    val recent = cursor.recentAt(18).takeIf { it.firstMinute > 0L }
                    val row = CatalogRow(
                        cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3),
                        cursor.getLong(4), cursor.getInt(5), cursor.getInt(6) != 0, cursor.getInt(7) != 0,
                        cursor.getInt(8) != 0, cursor.getInt(9) != 0, cursor.getString(10), cursor.getLong(11),
                        cursor.getLong(12), cursor.getLong(13), cursor.getLong(14), cursor.getLong(15),
                        cursor.getLong(16), cursor.getLong(17), recent,
                        recent?.let { it.bytes1h / observedSeconds(recentSnapshot.generatedAt, it.firstMinute, HOUR_MS) } ?: 0.0,
                    )
                    val encoded = catalogRowJson(row, now, recentSnapshot).toString()
                    val separatorBytes = if (rows == 0) 0L else 1L
                    val sizeReason = exportTruncationReason(
                        policy, rows, bytes, separatorBytes + encoded.utf8Size(), elapsedNanos = 0L,
                    )
                    if (sizeReason != null) { reason = sizeReason; break }
                    if (rows > 0) { writer.write(','.code); bytes++ }
                    writer.write(encoded)
                    bytes += encoded.utf8Size()
                    rows++
                }
            }
            val footer = entityExportFooter(rows, reason)
            writer.write(footer)
            bytes += footer.utf8Size()
            if (reason != null) FeatureCosts.registry.recordDropped(FeatureCostOperation.ENTITY_EXPORT)
            return EntityExportResult(rows, bytes, reason)
        } catch (error: Exception) {
            span.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            span.work(units = rows.toLong(), bytes = bytes).close()
        }
    }

    /**
     * Set when a pass freed pages but the checkpoint returned no bytes, which means a reader whose
     * snapshot predates the drain pinned the backfill. Without it those bytes are stranded: the
     * freelist is already back at its floor, so the next pass would return before ever reaching a
     * checkpoint again, and the space would only come back if the freelist happened to rebuild.
     */
    internal val walReclamationPending = java.util.concurrent.atomic.AtomicBoolean(false)

    private data class Recent(
        val access1m: Long,
        val access1h: Long,
        val access1d: Long,
        val bytes1m: Long,
        val bytes1h: Long,
        val bytes1d: Long,
        val firstMinute: Long,
    )

    private data class RecentSnapshot(
        val generatedAt: Long,
        val ranks: RecentRanks,
    )

    private data class CatalogRow(
        val entityId: String,
        val state: String,
        val metadataJson: String,
        val firstSeen: Long,
        val lastSeen: Long,
        val missingStreak: Int,
        val staticRef: Boolean,
        val runtimeRef: Boolean,
        val pinned: Boolean,
        val excluded: Boolean,
        val reasons: String,
        val lastAccess: Long,
        val accessCount: Long,
        val updateCount: Long,
        val updateBytes: Long,
        val rateWindowStart: Long,
        val rateUpdateBytes: Long,
        val lastUpdateAt: Long,
        val recent: Recent?,
        val rate1h: Double,
    )

    private data class DashboardRow(
        val status: String,
        val lastSync: Long,
        val analyzerPolicyVersion: Int,
        val error: String,
        val unresolvedJson: String,
        val issuesJson: String,
    )

    /** Constant-space approximate percentile ranks. Exact values do not justify six boxed/sorted
     * full-catalog arrays on memory-constrained panels; four buckets per power-of-two range preserve
     * useful heat-map ordering while bounding the snapshot to a few hundred counters. */
    private class RecentRanks {
        val access1m = LogRankHistogram()
        val access1h = LogRankHistogram()
        val access1d = LogRankHistogram()
        val bytes1m = LogRankHistogram()
        val bytes1h = LogRankHistogram()
        val bytes1d = LogRankHistogram()

        fun add(row: Recent) {
            access1m.add(row.access1m); access1h.add(row.access1h); access1d.add(row.access1d)
            bytes1m.add(row.bytes1m); bytes1h.add(row.bytes1h); bytes1d.add(row.bytes1d)
        }

        fun rank(value: Long, histogram: LogRankHistogram): Double = histogram.rank(value)
    }

    private fun recentAggregateSql(now: Long, entityIdCount: Int = 0): String {
        val oneMinute = (now - MINUTE_MS) / MINUTE_MS
        val oneHour = (now - HOUR_MS) / MINUTE_MS
        val oneDay = (now - 24L * HOUR_MS) / MINUTE_MS
        // All interpolated values originate from the local clock; request data remains bound separately.
        val entityFilter = if (entityIdCount > 0) " AND entity_id IN (${List(entityIdCount) { "?" }.joinToString(",")})" else ""
        return """SELECT entity_id,
               sum(CASE WHEN minute>=$oneMinute THEN access_count ELSE 0 END) AS access_1m,
               sum(CASE WHEN minute>=$oneHour THEN access_count ELSE 0 END) AS access_1h,
               sum(access_count) AS access_1d,
               sum(CASE WHEN minute>=$oneMinute THEN update_bytes ELSE 0 END) AS bytes_1m,
               sum(CASE WHEN minute>=$oneHour THEN update_bytes ELSE 0 END) AS bytes_1h,
               sum(update_bytes) AS bytes_1d,min(CASE WHEN span_started_at=0 THEN minute ELSE span_started_at END) AS first_minute
               FROM dashboard_entity_traffic_minute WHERE instance=? AND path=? AND minute>=$oneDay$entityFilter GROUP BY entity_id"""
    }

    private fun recentStatsForIds(
        instance: String,
        path: String,
        now: Long,
        entityIds: List<String>,
    ): Map<String, Recent> {
        if (entityIds.isEmpty()) return emptyMap()
        return readableDatabase.rawQuery(
            recentAggregateSql(now, entityIds.size),
            (listOf(instance, path) + entityIds).toTypedArray(),
        ).use { cursor -> buildMap {
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.recentAt(1))
        } }
    }

    private fun Cursor.recentAt(index: Int): Recent = Recent(
        getLong(index), getLong(index + 1), getLong(index + 2), getLong(index + 3),
        getLong(index + 4), getLong(index + 5), getLong(index + 6),
    )

    private fun Cursor.catalogRow(recentSnapshot: RecentSnapshot): CatalogRow {
        val stats = recentAt(18).takeIf { it.firstMinute > 0L }
        return CatalogRow(
            getString(0), getString(1), getString(2), getLong(3), getLong(4), getInt(5),
            getInt(6) != 0, getInt(7) != 0, getInt(8) != 0, getInt(9) != 0,
            getString(10), getLong(11), getLong(12), getLong(13), getLong(14),
            getLong(15), getLong(16), getLong(17), stats,
            stats?.let { it.bytes1h / observedSeconds(recentSnapshot.generatedAt, it.firstMinute, HOUR_MS) } ?: 0.0,
        )
    }

    private val recentCache = BoundedSnapshotCache<Pair<String, String>, RecentSnapshot>(
        windowMs = ENTITY_RANKING_REFRESH_MS,
        maxEntries = MAX_RANKING_CACHE_ENTRIES,
    )

    /** One immutable rollup/ranking snapshot serves all entity tables across the diagnostic refresh interval. */
    private fun recentSnapshot(instance: String, path: String, now: Long): RecentSnapshot =
        recentCache.get(instance to path, now) {
            val ranks = RecentRanks()
            readableDatabase.rawQuery(recentAggregateSql(now), arrayOf(instance, path)).use { cursor ->
                while (cursor.moveToNext()) ranks.add(cursor.recentAt(1))
            }
            RecentSnapshot(now, ranks)
        }

    private fun observedSeconds(now: Long, firstMinute: Long, windowMs: Long): Double {
        val start = maxOf(firstMinute * MINUTE_MS, now - windowMs)
        return (now - start).coerceIn(METRIC_BATCH_MS, windowMs) / 1000.0
    }

    /**
     * Every durable `app_state` row, for the portable backup archive.
     *
     * Reads the whole table rather than a list of declared settings, so a namespace introduced later is
     * captured without anyone remembering to add it. What a *restore* may write back is a separate
     * decision held by [io.panelassistant.android.persistence.StateBackupPolicy].
     */
    internal fun exportAppState(): List<ConfigVault.StateRow> =
        EntityCatalogStorePreOpen.readAppStateRows(readableDatabase)

    companion object {
        internal const val DATABASE_NAME = "ha-paneld.db"
        internal val RECOVER_STATUS_SQL =
            "UPDATE dashboard SET status='active',error='' WHERE instance=? AND path=? AND error=?"
        private const val VERSION = EntityCatalogSchema.CURRENT_VERSION

        private const val HOUR_MS = 3_600_000L
        internal const val MINUTE_MS = 60_000L
        internal const val RUNTIME_RETENTION_MS = 30L * 24 * HOUR_MS
        private const val RATE_WINDOW_MS = 5L * 60_000
        private const val RATE_STALE_MS = 90_000L
        private const val METRIC_BATCH_MS = 5_000L
        private const val DAY_MS = 24L * HOUR_MS
        private const val MAX_RANKING_CACHE_ENTRIES = 4
        private const val MAINTENANCE_INTERVAL_MS = 10L * 60_000
        private const val MAX_SQL_ID_FILTER = 800
        internal const val PERFORMANCE_RETENTION_DAYS = 7
        /**
         * Deletes the dashboard_entity rows an entity purge is about to strand. `dashboard_entity` is logically a child
         * of `entity` but declares no foreign key, so nothing cascades, and unlike `dashboard_entity_traffic_minute` it has no
         * age-based prune — so without this, rows for permanently removed entities are retained forever, and
         * the strandings are produced by the very routines that reclaim space.
         *
         * Deliberately scoped two ways. It matches only entities the caller's predicate is purging in this
         * same transaction, never "any dashboard_entity lacking an entity row": all four dashboard_entity inserts use
         * `INSERT OR IGNORE` without inserting an entity, so a dashboard_entity row legitimately exists before its
         * entity appears (a dashboard can reference an entity Home Assistant has not reported yet). And it
         * retains rows carrying explicit user intent — a manual pin or exclusion from [setOverrides] — which
         * stays meaningful while the entity is absent and must not be treated as garbage.
         *
         * Callers append the entity predicate and a closing parenthesis, qualifying columns with `e.`.
         */
        internal const val DELETE_MEMBERSHIP_FOR_PURGED_ENTITIES =
            "DELETE FROM dashboard_entity WHERE pinned=0 AND excluded=0 AND EXISTS(" +
                "SELECT 1 FROM entity e WHERE e.instance=dashboard_entity.instance AND e.entity_id=dashboard_entity.entity_id"

        private val FINGERPRINT = Regex("^[a-f0-9]{16}$")

        /** Main database plus SQLite's allowlisted sidecars; no directory enumeration or path output. */
        internal fun knownDatabaseFootprint(database: File): Long =
            listOf("", "-wal", "-shm", "-journal").fold(0L) { total, suffix ->
                val bytes = runCatching { File(database.path + suffix).takeIf(File::isFile)?.length() ?: 0L }
                    .getOrDefault(0L)
                if (Long.MAX_VALUE - total < bytes) Long.MAX_VALUE else total + bytes
            }

    }
}

// ---- DB-schema downgrade safety net (Tier-2) ----------------------------------------------------
//
// Config and derived catalog data share one SQLite file (ha-paneld.db). A downgrade to a build with
// an older schema must never take the whole store — config included — down. Strategy, all pre-open:
//   * Upgrade imminent (on-disk version < this build): snapshot the pre-migration file so a later
//     downgrade can roll back. Additive schema growth is the common case and needs nothing more.
//   * Downgrade (on-disk version > this build): restore the exact newest validated premigration snapshot
//     inside this build's finite range. If none exists, refuse before touching the live file.
// Missing files are fresh only at runtime startup; unreadable or retained-state-only stores refuse.

/** Heat-map percentiles are diagnostic context, not control state. Recompute them at most once per
 * five minutes while the Entities tab is open; exact per-page values remain current. */
internal const val ENTITY_RANKING_REFRESH_MS = 5L * 60_000

internal data class EntityExportPolicy(
    val maxRows: Int = 50_000,
    val maxBytes: Long = 16L * 1024 * 1024,
    val maxDurationNanos: Long = 30_000_000_000L,
    val footerReserveBytes: Long = 256L,
) {
    init {
        require(maxRows in 1..50_000)
        require(maxBytes in 1_024L..64L * 1024 * 1024)
        require(maxDurationNanos > 0L)
        require(footerReserveBytes in 64L until maxBytes)
    }
}

internal data class EntityExportResult(val rows: Int, val bytes: Long, val truncationReason: String?)

/** Retains only one requested page while scanning a globally ordered ID cursor. The caller-owned
 * include/exclude sets are referenced rather than copied, so heap growth is O(existing sets + page). */
internal class BoundedEntityIdPageCollector(
    private val limit: Int,
    private val offset: Int,
    private val includeIds: Set<String>?,
    private val excludeIds: Set<String>,
) {
    init { require(limit > 0); require(offset >= 0) }
    private val retained = ArrayList<String>(limit)
    var total: Int = 0
        private set

    val pageIds: List<String> get() = retained.toList()
    internal fun retainedCountForTest(): Int = retained.size

    fun offer(entityId: String) {
        if ((includeIds != null && entityId !in includeIds) || entityId in excludeIds) return
        if (total >= offset && retained.size < limit) retained += entityId
        total++
    }
}

internal fun entityExportHeader(instanceHash: String, path: String, now: Long): String =
    """{"kind":"ha-paneld-entity-catalog","exported_at":$now,"instance_hash":${JSONObject.quote(instanceHash)},"dashboard":${JSONObject.quote(path)},"entities":["""

internal fun entityExportFooter(rows: Int, truncationReason: String?): String =
    """],"exported_count":$rows,"truncated":${truncationReason != null},"truncation_reason":${truncationReason?.let(JSONObject::quote) ?: "null"}}"""

internal fun exportTruncationReason(
    policy: EntityExportPolicy,
    rows: Int,
    bytesWritten: Long,
    nextRowBytes: Long,
    elapsedNanos: Long,
): String? = when {
    rows >= policy.maxRows -> "row_limit"
    elapsedNanos > policy.maxDurationNanos -> "time_limit"
    bytesWritten + nextRowBytes + policy.footerReserveBytes > policy.maxBytes -> "byte_limit"
    else -> null
}

private fun String.utf8Size(): Long = toByteArray(Charsets.UTF_8).size.toLong()

private fun forwardElapsedNanos(now: Long, started: Long): Long =
    if (now >= started) now - started else Long.MAX_VALUE

/** A bounded cumulative distribution over positive Long values. */
internal class LogRankHistogram {
    private val buckets = LongArray(Long.SIZE_BITS * BUCKETS_PER_OCTAVE)
    private var total = 0L

    fun add(value: Long) {
        if (value <= 0L) return
        buckets[bucket(value)]++
        total++
    }

    fun rank(value: Long): Double {
        if (value <= 0L || total == 0L) return 0.0
        var cumulative = 0L
        for (index in 0..bucket(value)) cumulative += buckets[index]
        return cumulative.toDouble() / total
    }

    internal fun sampleCountForTest(): Long = total
    internal fun storageSlotsForTest(): Int = buckets.size

    private fun bucket(value: Long): Int {
        val exponent = Long.SIZE_BITS - 1 - java.lang.Long.numberOfLeadingZeros(value)
        val base = 1L shl exponent
        val step = (base / BUCKETS_PER_OCTAVE).coerceAtLeast(1L)
        val subBucket = ((value - base) / step).coerceAtMost((BUCKETS_PER_OCTAVE - 1).toLong()).toInt()
        return exponent * BUCKETS_PER_OCTAVE + subBucket
    }

    private companion object { const val BUCKETS_PER_OCTAVE = 4 }
}

internal data class EntitySortProjection(
    val entityId: String,
    val access1h: Long,
    val rate1h: Double,
    val reasons: String,
    val lastAccess: Long,
    val override: String,
)

/** Public API sort policy: unknown keys/directions never become SQL or reflection input. */
internal object EntityCatalogSorting {
    private val keys = setOf("entity_id", "access_1h", "rate_1h_bps", "reasons", "last_access_at", "override")
    fun key(raw: String): String = raw.takeIf(keys::contains) ?: "entity_id"
    fun direction(raw: String): String = raw.lowercase().takeIf { it == "asc" || it == "desc" } ?: "asc"

    /** Validated SQL order. Recent aliases are produced by the bounded aggregate join in the list
     * query, so every ordering is globally ranked before LIMIT/OFFSET. */
    fun sqlOrder(rawKey: String, rawDirection: String): String? {
        val normalizedKey = key(rawKey)
        val dir = direction(rawDirection).uppercase()
        val expression = when (normalizedKey) {
            "entity_id" -> return "e.entity_id COLLATE NOCASE $dir, e.entity_id $dir"
            "reasons" -> "coalesce(m.reasons,'') COLLATE NOCASE"
            "last_access_at" -> "coalesce(m.last_access_at,0)"
            "override" -> "CASE WHEN coalesce(m.excluded,0)=1 THEN 'excluded' WHEN coalesce(m.pinned,0)=1 THEN 'pinned' ELSE 'auto' END COLLATE NOCASE"
            "access_1h" -> "recent_access_1h"
            "rate_1h_bps" -> "recent_rate_1h"
            else -> return null
        }
        return "$expression $dir, e.entity_id COLLATE NOCASE ASC, e.entity_id ASC"
    }

    fun <T> comparator(rawKey: String, rawDirection: String, projection: (T) -> EntitySortProjection): Comparator<T> {
        val key = key(rawKey)
        val sign = if (direction(rawDirection) == "desc") -1 else 1
        return Comparator { left, right ->
            val a = projection(left); val b = projection(right)
            val primary = when (key) {
                "access_1h" -> a.access1h.compareTo(b.access1h)
                "rate_1h_bps" -> a.rate1h.compareTo(b.rate1h)
                "reasons" -> a.reasons.compareTo(b.reasons, ignoreCase = true)
                "last_access_at" -> a.lastAccess.compareTo(b.lastAccess)
                "override" -> a.override.compareTo(b.override, ignoreCase = true)
                else -> compareEntityIds(a.entityId, b.entityId)
            } * sign
            if (primary != 0) primary else compareEntityIds(a.entityId, b.entityId)
        }
    }

    private fun compareEntityIds(a: String, b: String): Int =
        a.compareTo(b, ignoreCase = true).takeIf { it != 0 } ?: a.compareTo(b)
}

/** Thread-safe fixed-window cache. Loader executes once per key/window and old keys are strictly bounded. */
internal class BoundedSnapshotCache<K, V>(private val windowMs: Long, private val maxEntries: Int) {
    init { require(windowMs > 0); require(maxEntries > 0) }
    private data class Entry<V>(val createdAt: Long, val value: V)
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)

    @Synchronized fun get(key: K, now: Long, loader: () -> V): V {
        // A dashboard switch must not retain every other dashboard's full-catalog rollup/rank arrays
        // after their 10-second window. Clock rollback also expires rather than pinning stale entries.
        entries.entries.removeAll { (_, entry) ->
            now < entry.createdAt || now - entry.createdAt >= windowMs
        }
        entries[key]?.takeIf { now >= it.createdAt && now - it.createdAt < windowMs }?.let { return it.value }
        val value = loader()
        entries[key] = Entry(now, value)
        while (entries.size > maxEntries) entries.remove(entries.entries.first().key)
        return value
    }

    @Synchronized fun invalidate(key: K) { entries.remove(key) }
    @Synchronized internal fun sizeForTest(): Int = entries.size
}

/** One route-keyed catalogue target and the root it collapses onto. */
internal data class ScopeCollapse(
    val instance: String,
    val from: String,
    val to: String,
    /** True when the root namespace already holds rows, so the root's own values win on conflict. */
    val mergesIntoExisting: Boolean,
)

/**
 * Which catalogue targets are keyed by a route rather than by their dashboard root, and where each
 * one lands.
 *
 * Separated from the SQL so the policy is executable. The defect it answers was invisible to evidence
 * that only read source text, and the interesting cases are all decisions rather than statements: a
 * root-keyed target is already correct and must not be touched; two routes under one dashboard both
 * collapse onto it, and the second must see the first's arrival so it merges rather than trying to
 * create the parent twice.
 */
internal fun planRouteKeyCollapse(
    rows: List<Pair<String, String>>,
    rootOf: (String) -> String,
): List<ScopeCollapse> {
    val present = rows.toMutableSet()
    val plan = mutableListOf<ScopeCollapse>()
    for ((instance, path) in rows) {
        val root = rootOf(path)
        if (root == path) continue
        val merges = instance to root in present
        plan += ScopeCollapse(instance, path, root, merges)
        present += instance to root
        present -= instance to path
    }
    return plan
}
