package io.github.maxlyth.hapaneld.dashboard

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.StatFs
import android.util.Log
import io.github.maxlyth.hapaneld.control.AMBIENT_RETENTION_MINUTES
import io.github.maxlyth.hapaneld.control.AmbientHistoryMinute
import io.github.maxlyth.hapaneld.control.AmbientMinuteAggregate
import io.github.maxlyth.hapaneld.metrics.DashboardMetrics
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.MetricPayload
import io.github.maxlyth.hapaneld.persistence.ConfigVault
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.storage.DatabaseBusyRetry
import io.github.maxlyth.hapaneld.storage.StorageAutoVacuumMode
import io.github.maxlyth.hapaneld.storage.StorageHealthObservation
import io.github.maxlyth.hapaneld.storage.StorageHealthRuntime
import io.github.maxlyth.hapaneld.storage.reclamationAdmitted
import io.github.maxlyth.hapaneld.storage.reclamationPassWanted
import io.github.maxlyth.hapaneld.storage.walReclamationStillPending
import io.github.maxlyth.hapaneld.storage.StorageQuickCheck
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Writer

/** Bounded, derived entity/catalog evidence. Credentials are never stored here. */
class EntityCatalogStore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, VERSION) {
    /** Held for the config vault: a freshly created database may need configuration restored into it. */
    private val appContext: Context = context.applicationContext ?: context
    private val databaseTarget: File = context.getDatabasePath(DATABASE_NAME)
    private val maintenanceGate = MaintenanceIntervalGate(MAINTENANCE_INTERVAL_MS)
    private val performanceMaintenanceGate = MaintenanceIntervalGate(PERFORMANCE_MAINTENANCE_INTERVAL_MS)
    private val busyRetry = DatabaseBusyRetry()

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
    private val databaseBytesCacheLock = Any()
    private var databaseBytesCachedAt = Long.MIN_VALUE
    private var databaseUsageCachedValue = DatabaseUsage(0L, 0L, 0)

    /**
     * Report a completed catalog/history mutation, or latch its original SQLite failure and rethrow.
     *
     * A failure classified BUSY is expected contention between this app's own connection pools, not
     * storage-fault evidence, so it is re-attempted within one bounded [DatabaseBusyRetry] budget
     * before the unchanged latch path decides. Every block is self-contained — its transaction rolls
     * back with its own throw — so a re-run cannot double-apply. Non-BUSY failures never retry.
     */
    private inline fun <T> observedWrite(
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
        DATABASE_RESTORE_OPEN_LEASE.reconcileAndOpen(
            establishedGuardReceipt = { record ->
                DatabaseRestoreTransaction(databaseTarget).establishedGuardReceipt(record)
            },
            reconcile = { reconcileSchema(context).action == SchemaReconcileAction.RESTORED },
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
        db.execSQL(ENTITY_TABLE_SQL)
        db.execSQL(DASHBOARD_TABLE_SQL)
        db.execSQL(DASHBOARD_ENTITY_TABLE_SQL)
        db.execSQL(DASHBOARD_ENTITY_TRAFFIC_TABLE_SQL)
        db.execSQL("CREATE INDEX ix_entity_missing ON entity(instance,missing_streak)")
        db.execSQL("CREATE INDEX ix_dashboard_entity_load ON dashboard_entity(instance,path,update_bytes DESC)")
        db.execSQL("CREATE INDEX ix_dashboard_entity_traffic_minute_age ON dashboard_entity_traffic_minute(instance,path,minute)")
        db.execSQL(DASHBOARD_IGNORED_ISSUE_TABLE_SQL)
        db.execSQL(DASHBOARD_METRIC_TABLE_SQL)
        db.execSQL("CREATE INDEX ix_dashboard_metric_minute_age ON dashboard_metric_minute(minute)")
        db.execSQL(APP_STATE_REVISION_TABLE_SQL)
        db.execSQL(APP_STATE_NAMESPACE_TABLE_SQL)
        db.execSQL(APP_STATE_TABLE_SQL)
        db.execSQL("CREATE INDEX ix_app_state_updated ON app_state(namespace,updated_at)")
        db.execSQL(PROXIMITY_MODEL_TABLE_SQL)
        db.execSQL(PROXIMITY_SAMPLE_TABLE_SQL)
        db.execSQL("CREATE INDEX ix_proximity_sample_age ON proximity_sample(bucket)")
        db.execSQL(PROXIMITY_EPISODE_TABLE_SQL)
        db.execSQL("CREATE INDEX ix_proximity_episode_age ON proximity_episode(started_at)")
        db.execSQL(AMBIENT_HISTORY_TABLE_SQL)
        db.execSQL("CREATE INDEX ix_ambient_lux_minute_age ON ambient_lux_minute(minute)")
        // A database is created fresh either on first install (nothing to recover) or because the
        // previous one was set aside as out-of-contract or too new. The second case is the crisis this
        // vault exists for: without a restore the owner silently loses their dashboard, so recover here,
        // where configuration is provably empty because these tables were just created.
        restoreConfigurationInto(db, appContext)
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
            val cutoff = now - TOMBSTONE_RETENTION_MS
            db.execSQL(
                "$DELETE_MEMBERSHIP_FOR_PURGED_ENTITIES AND e.instance=? AND e.tombstone_at>0 AND e.tombstone_at<?)",
                arrayOf<Any?>(instance, cutoff),
            )
            db.execSQL("DELETE FROM entity WHERE instance=? AND tombstone_at>0 AND tombstone_at<?", arrayOf<Any?>(instance, cutoff))
            db.execSQL("DELETE FROM dashboard_entity_traffic_minute WHERE minute<?", arrayOf((now - DAY_MS) / MINUTE_MS))
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

    internal fun recordDashboardPerformance(samples: List<DashboardPerformanceAggregate>): Unit =
        observedWrite("dashboard-performance-history") {
        if (samples.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            samples.forEach { sample ->
                val key = sample.key
                // The accumulation the 25-column UPDATE expressed in SQL now happens against the decoded
                // bucket, under each metric's declared rule. Same transaction, so the read-merge-write is
                // as atomic as the upsert it replaces.
                val existing = db.rawQuery(
                    "SELECT payload FROM dashboard_metric_minute WHERE instance=? AND path=? AND minute=?",
                    arrayOf(key.instance, key.path, key.minute.toString()),
                ).use { cursor ->
                    if (cursor.moveToFirst()) MetricPayload.decode(cursor.getBlob(0)) else null
                } ?: emptyMap() // a corrupt bucket is rebuilt rather than propagated

                val merged = DashboardMetrics.merge(
                    existing,
                    DashboardMetricCodec.values(sample.filterActive, sample.entityCount, sample.totals),
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO dashboard_metric_minute(instance,path,minute,payload) VALUES(?,?,?,?)",
                    arrayOf<Any?>(key.instance, key.path, key.minute, MetricPayload.encode(merged)),
                )
            }
            val latestMinute = samples.maxOf { it.key.minute }
            if (performanceMaintenanceGate.admit(latestMinute * MINUTE_MS)) {
                db.execSQL(
                    "DELETE FROM dashboard_metric_minute WHERE minute<?",
                    arrayOf(latestMinute - PERFORMANCE_RETENTION_MINUTES),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Batched IO-side sink for ambient evidence; sensor callbacks only touch the RAM accumulator. */
    internal fun recordAmbientHistory(
        samples: List<AmbientMinuteAggregate>,
        nowMs: Long,
        activeContextId: String = samples.lastOrNull()?.key?.contextId.orEmpty(),
        activeSourceId: String = samples.lastOrNull()?.key?.sourceId.orEmpty(),
    ): Unit = observedWrite("ambient-history") {
        if (samples.isEmpty() || nowMs < 0L) return
        val nowMinute = nowMs / MINUTE_MS
        val oldestMinute = nowMinute - AMBIENT_RETENTION_MINUTES
        val db = writableDatabase
        db.beginTransaction()
        try {
            val statement = db.compileStatement(
                """UPDATE ambient_lux_minute SET lux_integral=lux_integral+?,
                   coverage_ms=coverage_ms+?,min_lux=min(min_lux,?),max_lux=max(max_lux,?),
                   last_lux=?,sample_count=sample_count+?,
                   baseline_log_integral=baseline_log_integral+?,
                   baseline_coverage_ms=baseline_coverage_ms+?
                   WHERE context_id=? AND source_id=? AND minute=?""",
            )
            try {
                samples.asSequence()
                    .filter { it.key.contextId.length in 1..128 && it.key.sourceId.length in 1..255 }
                    .filter { it.key.minute in oldestMinute..nowMinute }
                    .filter { it.coverageMs in 1..MINUTE_MS && it.sampleCount > 0 }
                    .filter { it.luxIntegral.isFinite() && it.minLux.isFinite() && it.maxLux.isFinite() && it.lastLux.isFinite() }
                    .forEach { sample ->
                        db.execSQL(
                            """INSERT OR IGNORE INTO ambient_lux_minute(
                               context_id,source_id,minute,min_lux,max_lux,last_lux)
                               VALUES(?,?,?,?,?,?)""",
                            arrayOf<Any?>(sample.key.contextId, sample.key.sourceId, sample.key.minute,
                                sample.minLux, sample.maxLux, sample.lastLux),
                        )
                        statement.clearBindings()
                        statement.bindDouble(1, sample.luxIntegral)
                        statement.bindLong(2, sample.coverageMs)
                        statement.bindDouble(3, sample.minLux)
                        statement.bindDouble(4, sample.maxLux)
                        statement.bindDouble(5, sample.lastLux)
                        statement.bindLong(6, sample.sampleCount)
                        statement.bindDouble(7, sample.baselineLogIntegral)
                        statement.bindLong(8, sample.baselineCoverageMs)
                        statement.bindString(9, sample.key.contextId)
                        statement.bindString(10, sample.key.sourceId)
                        statement.bindLong(11, sample.key.minute)
                        statement.executeUpdateDelete()
                    }
            } finally { statement.close() }
            pruneAmbientHistory(db, oldestMinute, nowMinute, activeContextId, activeSourceId)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Idempotent bootstrap sink. Existing live rows win wholesale over imported HA history. */
    internal fun seedAmbientHistory(samples: List<AmbientMinuteAggregate>, nowMs: Long): Unit =
        observedWrite("ambient-history-seed") {
        if (samples.isEmpty() || nowMs < 0L) return
        val nowMinute = nowMs / MINUTE_MS
        val oldestMinute = nowMinute - AMBIENT_RETENTION_MINUTES
        val db = writableDatabase
        db.beginTransaction()
        try {
            val statement = db.compileStatement(
                """INSERT OR IGNORE INTO ambient_lux_minute(
                   context_id,source_id,minute,lux_integral,coverage_ms,min_lux,max_lux,last_lux,
                   sample_count,baseline_log_integral,baseline_coverage_ms)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
            )
            try {
                samples.asSequence()
                    .filter { it.key.contextId.length in 1..128 && it.key.sourceId.length in 1..255 }
                    .filter { it.key.minute in oldestMinute..nowMinute }
                    .filter { it.coverageMs in 1..MINUTE_MS && it.sampleCount > 0 }
                    .filter { it.baselineCoverageMs in 0..it.coverageMs }
                    .filter {
                        it.luxIntegral.isFinite() && it.luxIntegral >= 0.0 &&
                            it.minLux.isFinite() && it.minLux >= 0.0 &&
                            it.maxLux.isFinite() && it.maxLux >= it.minLux &&
                            it.lastLux.isFinite() && it.lastLux >= 0.0 &&
                            it.baselineLogIntegral.isFinite() && it.baselineLogIntegral >= 0.0
                    }
                    .forEach { sample ->
                        statement.clearBindings()
                        statement.bindString(1, sample.key.contextId)
                        statement.bindString(2, sample.key.sourceId)
                        statement.bindLong(3, sample.key.minute)
                        statement.bindDouble(4, sample.luxIntegral)
                        statement.bindLong(5, sample.coverageMs)
                        statement.bindDouble(6, sample.minLux)
                        statement.bindDouble(7, sample.maxLux)
                        statement.bindDouble(8, sample.lastLux)
                        statement.bindLong(9, sample.sampleCount)
                        statement.bindDouble(10, sample.baselineLogIntegral)
                        statement.bindLong(11, sample.baselineCoverageMs)
                        statement.executeInsert()
                    }
            } finally { statement.close() }
            val active = samples.last().key
            pruneAmbientHistory(db, oldestMinute, nowMinute, active.contextId, active.sourceId)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    internal fun ambientHistory(
        contextId: String,
        sourceId: String,
        sinceMinute: Long,
        untilMinute: Long = Long.MAX_VALUE,
    ): List<AmbientHistoryMinute> = readableDatabase.rawQuery(
        """SELECT minute,lux_integral,coverage_ms,min_lux,max_lux,last_lux,sample_count,
           baseline_log_integral,baseline_coverage_ms FROM ambient_lux_minute
           WHERE context_id=? AND source_id=? AND minute>=? AND minute<=? ORDER BY minute""",
        arrayOf(contextId, sourceId, sinceMinute.toString(), untilMinute.toString()),
    ).use { cursor -> buildList {
        while (cursor.moveToNext()) add(AmbientHistoryMinute(
            key = io.github.maxlyth.hapaneld.control.AmbientHistoryKey(contextId, sourceId, cursor.getLong(0)),
            luxIntegral = cursor.getDouble(1), coverageMs = cursor.getLong(2),
            minLux = cursor.getDouble(3), maxLux = cursor.getDouble(4), lastLux = cursor.getDouble(5),
            sampleCount = cursor.getLong(6), baselineLogIntegral = cursor.getDouble(7),
            baselineCoverageMs = cursor.getLong(8),
        ))
    } }

    /** Retain the complete active seven-day partition first and every irreplaceable panel-sensor
     * minute second, then spend the fixed remainder on recoverable inactive HA evidence. */
    private fun pruneAmbientHistory(
        db: SQLiteDatabase,
        oldestMinute: Long,
        nowMinute: Long,
        activeContextId: String,
        activeSourceId: String,
    ) {
        db.execSQL("DELETE FROM ambient_lux_minute WHERE minute<? OR minute>?", arrayOf(oldestMinute, nowMinute))
        db.execSQL(
            """DELETE FROM ambient_lux_minute WHERE rowid IN (
               SELECT rowid FROM ambient_lux_minute
               ORDER BY CASE
                          WHEN context_id=? AND source_id=? THEN 0
                          WHEN source_id=? THEN 1
                          ELSE 2
                        END,
                        minute DESC,context_id,source_id
               LIMIT -1 OFFSET $AMBIENT_GLOBAL_ROW_LIMIT)""",
            arrayOf(activeContextId, activeSourceId, PANEL_AMBIENT_SOURCE_ID),
        )
    }

    internal fun resetAmbientHistory(contextId: String? = null, sourceId: String? = null): Int =
        observedWrite("ambient-history-reset") {
            when {
                contextId == null && sourceId == null -> writableDatabase.delete("ambient_lux_minute", null, null)
                contextId != null && sourceId == null -> writableDatabase.delete("ambient_lux_minute", "context_id=?", arrayOf(contextId))
                contextId != null -> writableDatabase.delete("ambient_lux_minute", "context_id=? AND source_id=?", arrayOf(contextId, sourceId!!))
                else -> 0
            }
        }

    internal fun dashboardPerformanceHistory(
        instance: String,
        path: String,
        sinceMinute: Long,
    ): List<DashboardPerformanceMinute> = readableDatabase.rawQuery(
        """SELECT minute,payload FROM dashboard_metric_minute
           WHERE instance=? AND path=? AND minute>=? ORDER BY minute""",
        arrayOf(instance, path, sinceMinute.toString()),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                // A bucket that fails to decode is skipped rather than reported as zeroes: an absent
                // point in a diagnostic chart is honest, a fabricated one is not.
                MetricPayload.decode(cursor.getBlob(1))?.let { values ->
                    add(DashboardMetricCodec.minute(cursor.getLong(0), values))
                }
            }
        }
    }

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
     * Retention is evidence-first: old rollups and tombstones go before inactive entity detail. The
     * active set and all manual overrides are never removed by size maintenance. SQLite may keep freed
     * pages for reuse, so status reports live pages rather than the physical high-water mark.
     */
    /**
     * File-size maintenance boundary for callers whose own write already committed. Maintenance is
     * both the main BUSY *source* (the purge) and a routine BUSY *victim*, so it gets the same
     * bounded retry and its own operation name; its failure still latches, but never propagates —
     * rethrowing after the caller's transaction committed would tell that caller a durable write
     * failed when it succeeded, and once made the telemetry flusher drop an already-written batch.
     * Success is reported only when maintenance actually wrote, so a gate-skipped pass can never
     * masquerade as durable-write recovery evidence.
     */
    private fun observedMaintenance(now: Long) {
        // The interval gate is consumed OUTSIDE the retried operation. An admission spent by an
        // attempt that then failed BUSY would make the retry re-run into a refusing gate, ending the
        // pass with neither the work retried nor the failure latched — the failure mode this ordering prevents.
        if (!maintenanceGate.admit(now)) return
        runCatching {
            observedWrite("catalog-maintenance", reportsSuccessfulWrite = { it }) { maintainSoftLimit(now) }
        }
    }

    /** @return true only if a durable write (purge chunk or vacuum step) actually executed. */
    private fun maintainSoftLimit(now: Long): Boolean {
        val span = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_DB_MAINTENANCE)
        var observedBytes = 0L
        var appliedTiers = 0
        var wrote = false
        try {
            val db = writableDatabase
            val incrementalVacuum = ensureIncrementalAutoVacuum(db)
            fun refreshUsage(): Long = measureDatabaseUsage(db).also {
                updateDatabaseUsageCache(now, it)
            }.usedBytes
            observedBytes = refreshUsage()
            if (observedBytes > SOFT_LIMIT_BYTES) {
                // Each statement runs in bounded rowid chunks, one autocommit transaction per chunk,
                // so the write lock is released between chunks and a concurrent writer's own busy
                // timeout can win in the gaps. One unbounded DELETE here held the lock ≥18 s under
                // FULL auto-vacuum and latched a false BUSY storage failure (Issue #91).
                wrote = chunkedWrite(
                    db,
                    """DELETE FROM dashboard_entity_traffic_minute WHERE rowid IN (
                       SELECT rowid FROM dashboard_entity_traffic_minute WHERE minute<?
                       LIMIT $MAINTENANCE_CHUNK_ROWS)""",
                    arrayOf<Any?>((now - DAY_MS) / MINUTE_MS),
                ) || wrote
                wrote = chunkedWrite(
                    db,
                    """DELETE FROM dashboard_entity WHERE rowid IN (
                       SELECT rowid FROM dashboard_entity WHERE pinned=0 AND excluded=0 AND EXISTS(
                       SELECT 1 FROM entity e WHERE e.instance=dashboard_entity.instance
                       AND e.entity_id=dashboard_entity.entity_id AND e.tombstone_at>0)
                       LIMIT $MAINTENANCE_CHUNK_ROWS)""",
                    emptyArray(),
                ) || wrote
                wrote = chunkedWrite(
                    db,
                    """DELETE FROM entity WHERE rowid IN (
                       SELECT rowid FROM entity WHERE tombstone_at>0 LIMIT $MAINTENANCE_CHUNK_ROWS)""",
                    emptyArray(),
                ) || wrote
                // The chunk predicate excludes rows the previous chunk already rewrote, so the loop
                // strictly shrinks its candidate set and terminates.
                wrote = chunkedWrite(
                    db,
                    """UPDATE entity SET attributes_json='{}',metadata_json='{}' WHERE rowid IN (
                       SELECT rowid FROM entity WHERE (attributes_json!='{}' OR metadata_json!='{}')
                       AND (instance,entity_id) NOT IN
                       (SELECT instance,entity_id FROM dashboard_entity WHERE pinned=1 OR referenced_by_config=1 OR referenced_at_runtime=1)
                       LIMIT $MAINTENANCE_CHUNK_ROWS)""",
                    emptyArray(),
                ) || wrote
                observedBytes = refreshUsage()
                if (observedBytes > SOFT_LIMIT_BYTES) {
                    wrote = chunkedWrite(
                        db,
                        """UPDATE entity SET attributes_json='{}' WHERE rowid IN (
                           SELECT rowid FROM entity WHERE attributes_json!='{}'
                           AND (instance,entity_id) NOT IN
                           (SELECT instance,entity_id FROM dashboard_entity WHERE pinned=1 OR referenced_by_config=1 OR last_access_at>=?)
                           LIMIT $MAINTENANCE_CHUNK_ROWS)""",
                        arrayOf<Any?>(now - RUNTIME_RETENTION_MS),
                    ) || wrote
                    observedBytes = refreshUsage()
                }
                while (true) {
                    val tier = RollupRetentionPolicy.nextTier(observedBytes, SOFT_LIMIT_BYTES, appliedTiers) ?: break
                    applyRollupPressureTier(db, tier, now)
                    wrote = true
                    appliedTiers++
                    observedBytes = refreshUsage()
                }
            }
            if (incrementalVacuum) {
                wrote = incrementalVacuumStep(db) || wrote
                observedBytes = refreshUsage()
            }
            return wrote
        } catch (error: Exception) {
            span.outcome(FeatureCostOutcome.FAILURE)
            throw error
        } finally {
            span.work(units = appliedTiers.toLong(), bytes = observedBytes).close()
        }
    }

    /**
     * Runs one bounded statement repeatedly until its candidate set is exhausted, one autocommit
     * transaction per chunk. Returns whether any row changed.
     */
    private fun chunkedWrite(db: SQLiteDatabase, sql: String, args: Array<Any?>): Boolean {
        var any = false
        while (true) {
            val statement = db.compileStatement(sql)
            val changed = try {
                args.forEachIndexed { index, argument ->
                    when (argument) {
                        is Long -> statement.bindLong(index + 1, argument)
                        is String -> statement.bindString(index + 1, argument)
                        else -> throw IllegalArgumentException("unsupported maintenance bind type")
                    }
                }
                statement.executeUpdateDelete()
            } finally {
                statement.close()
            }
            if (changed > 0) any = true
            if (changed < MAINTENANCE_CHUNK_ROWS) return any
        }
    }

    /**
     * Android's platform SQLite is compiled with `SQLITE_DEFAULT_AUTOVACUUM=1`, so this database was
     * silently created with FULL auto-vacuum: every large purge relocates and truncates pages inside
     * its own commit, which held the write lock ≥18 s on panel eMMC (Issue #91). FULL↔INCREMENTAL is
     * a plain header change that is legal at any time, so flip once and reclaim pages in bounded
     * [incrementalVacuumStep] slices instead. NONE is left alone: enabling auto-vacuum on such a
     * database requires a full `VACUUM`, whose temporary-space demand may worsen a low-space incident
     * — never run it implicitly (storage-health remediation policy).
     *
     * @return true when the database is in INCREMENTAL mode and bounded reclamation may run.
     */
    private fun ensureIncrementalAutoVacuum(db: SQLiteDatabase): Boolean {
        fun mode(): Long = db.rawQuery("PRAGMA auto_vacuum", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else AUTO_VACUUM_NONE
        }
        // The verification read runs inside a transaction so it uses the same primary connection the
        // flip wrote through; a pooled read connection could hold a pre-flip snapshot and misreport a
        // good conversion as failed — the false-failure class this lane exists to remove.
        fun primaryConnectionMode(): Long {
            db.beginTransaction()
            try {
                return mode().also { db.setTransactionSuccessful() }
            } finally {
                db.endTransaction()
            }
        }
        return when (mode()) {
            AUTO_VACUUM_INCREMENTAL -> true
            AUTO_VACUUM_FULL -> {
                db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
                if (primaryConnectionMode() != AUTO_VACUUM_INCREMENTAL) {
                    // A conversion that did not take is an anomaly, distinct from the deliberate NONE
                    // branch below: stop the pass before any purge runs under FULL and latch it
                    // visibly through the maintenance boundary instead of continuing silently.
                    throw SQLException("auto_vacuum incremental conversion did not persist")
                }
                true
            }
            // NONE stays NONE: enabling auto-vacuum needs a full VACUUM, never run implicitly.
            else -> false
        }
    }

    /**
     * Reclaims freelist pages in bounded slices, one short write transaction per pragma call, capped
     * per maintenance pass so no single pass monopolizes the writer. Retains a small freelist for
     * ordinary page reuse. Returns whether any slice ran.
     *
     * Two measured SQLite facts shape this, and getting either wrong makes reclamation silently
     * useless (Issue #91 residual):
     *
     *  * **`incremental_vacuum` is a row-returning statement** — it yields one empty result row per
     *    freed page. Android's [SQLiteDatabase.execSQL] routes to `executeNonQuery`, which throws
     *    `"Queries can be performed using SQLiteDatabase query or rawQuery methods only."` the moment
     *    `sqlite3_step` first returns `SQLITE_ROW`. That message matches no full/corrupt/busy/io
     *    keyword, so it classified `UNKNOWN`, took zero retries and latched `database_failure` on
     *    every pass that had anything to reclaim. It must be run through [SQLiteDatabase.rawQuery]
     *    and stepped to exhaustion; the row count is also the only direct count of pages freed.
     *  * **The pragma alone returns no filesystem bytes in WAL mode.** A full drain moves
     *    `page_count` but leaves the main file at its old length; the space comes back only when a
     *    checkpoint backfills to the end of the WAL and truncates. See [checkpointReclaimedBytes].
     */
    /**
     * Set when a pass freed pages but the checkpoint returned no bytes, which means a reader whose
     * snapshot predates the drain pinned the backfill. Without it those bytes are stranded: the
     * freelist is already back at its floor, so the next pass would return before ever reaching a
     * checkpoint again, and the space would only come back if the freelist happened to rebuild.
     */
    private val walReclamationPending = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun incrementalVacuumStep(db: SQLiteDatabase): Boolean {
        // Owner closure is checked before EVERY later database read on this path. A pool closed
        // mid-pass makes the next query throw IllegalStateException, which is not an SQLException,
        // so no boundary classifies it and `observedMaintenance`'s runCatching swallows it — a
        // shutdown would look exactly like a clean pass that did nothing.
        fun abandoned(): Boolean = isBusyRetryAbandoned()
        fun freelist(): Long = db.rawQuery("PRAGMA freelist_count", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        if (abandoned()) return false
        val pageSize = db.rawQuery("PRAGMA page_size", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(0L) else 0L
        }
        val databaseFile = File(db.path)
        if (abandoned()) return false
        val startingFreelist = freelist()
        val reclaimable = startingFreelist > FREELIST_RETAINED_PAGES
        // A pass with nothing to reclaim still runs when a previous checkpoint was reader-blocked:
        // that pass's bytes are still in the WAL, and this is the only route that returns them.
        if (!reclamationPassWanted(startingFreelist, FREELIST_RETAINED_PAGES, walReclamationPending.get())) {
            return false
        }

        var freedPages = 0L
        var bytesBefore = storageKnownFileBytes(databaseFile)
        if (reclaimable) {
            if (!reclamationHasHeadroom(databaseFile, pageSize)) {
                // Reclamation writes one WAL frame per relocated page before anything shrinks, so it
                // costs space before it returns any. Refusing here is the difference between
                // deferring reclamation and deepening the incident it exists to relieve.
                Log.i(
                    "EntityCatalogStore",
                    "storage reclamation deferred: insufficient headroom, freelist=$startingFreelist",
                )
                return false
            }
            while (freedPages < MAX_VACUUM_PAGES_PER_PASS) {
                if (abandoned() || freelist() <= FREELIST_RETAINED_PAGES) break
                val slice = minOf(VACUUM_CHUNK_PAGES, MAX_VACUUM_PAGES_PER_PASS - freedPages)
                // One row per freed page. `count` steps the statement to exhaustion in one window
                // fill, so unlike a moveToNext walk it cannot re-execute the pragma on a refill and
                // silently free another slice beyond the cap.
                val sliceFreedPages = db.rawQuery("PRAGMA incremental_vacuum($slice)", null)
                    .use { cursor -> cursor.count.toLong() }
                freedPages += sliceFreedPages
                // A short slice means SQLite ran out of reclaimable pages; stop rather than spin.
                if (sliceFreedPages < slice) break
            }
            if (freedPages == 0L && !abandoned()) {
                // A freelist above the retention floor that yields nothing is the exact shape of the
                // defect this replaced: reclamation that runs, reports nothing and is never noticed.
                // It is not a database fault and must not latch, but it must never again be silent.
                // Abandonment is excluded because a shutdown racing maintenance is an ordinary stop,
                // and letting it fire here would drown the signal this warning exists to carry.
                Log.w(
                    "EntityCatalogStore",
                    "storage reclamation made no progress with freelist=$startingFreelist",
                )
            }
        }

        // The checkpoint writes to the database too, so a closing owner must stop before it rather
        // than have the loop's own break undone two statements later.
        if (abandoned()) return freedPages > 0L
        val bytesReturned = checkpointReclaimedBytes(db, databaseFile, bytesBefore)
        // Pending only ever means "the WAL still holds bytes a checkpoint could not hand back".
        walReclamationPending.set(
            walReclamationStillPending(freedPages, bytesReturned, walReclamationPending.get()),
        )
        Log.i(
            "EntityCatalogStore",
            "storage reclamation freed $freedPages page(s), returned $bytesReturned byte(s) to the " +
                "filesystem, deferred=${walReclamationPending.get()}",
        )
        return freedPages > 0L || bytesReturned > 0L
    }

    /**
     * Refuses a reclamation pass that the filesystem cannot afford. The transient cost is bounded by
     * one pass's slice budget; the margin on top covers the checkpoint that follows. A capacity read
     * that fails admits the pass, because losing the probe is not evidence of a full disk and the
     * pass is already bounded — but latched or critical storage pressure refuses it outright.
     */
    private fun reclamationHasHeadroom(databaseFile: File, pageSize: Long): Boolean {
        val statFs = runCatching { StatFs(databaseFile.path) }.getOrNull()
        // A full filesystem reports zero available blocks, which is a real measurement and must
        // refuse the pass. Only the absence of the probe itself is unknown, so the two are carried
        // as different values rather than both collapsing to zero.
        val usableBytes = statFs?.let {
            storageSaturatedMultiply(
                it.availableBlocksLong.coerceAtLeast(0L),
                it.blockSizeLong.coerceAtLeast(0L),
            )
        }
        return reclamationAdmitted(
            severity = StorageHealthRuntime.snapshot().severity,
            usableBytes = usableBytes,
            pageSizeBytes = pageSize,
            maxPagesPerPass = MAX_VACUUM_PAGES_PER_PASS,
            marginBytes = RECLAMATION_HEADROOM_MARGIN_BYTES,
        )
    }

    /**
     * Flushes the reclaimed pages out of the WAL so the main file actually shrinks, and reports the
     * bytes the filesystem really gave back.
     *
     * The returned count is measured from the file, never inferred from the checkpoint's result row.
     * A reader whose snapshot predates the reclamation pins the backfill, and SQLite reports that as
     * `(busy=0, log=N, checkpointed=0)` — the busy flag stays clear, so the result triple cannot
     * distinguish "returned nothing" from "returned everything". Reporting a measured zero is
     * correct and is not a fault, but it is not self-healing either: by then the freelist is back at
     * its floor, so a later pass would return before reaching a checkpoint at all. That is what
     * [walReclamationPending] is for — it brings the next pass here anyway until the bytes land.
     */
    private fun checkpointReclaimedBytes(db: SQLiteDatabase, databaseFile: File, bytesBefore: Long): Long {
        // PASSIVE never blocks a concurrent writer. TRUNCATE additionally shrinks the WAL file but
        // waits on the busy handler, and the main database file — the space actually at stake here —
        // comes back under PASSIVE just the same.
        //
        // Deliberately not wrapped: PASSIVE reports contention through its busy flag rather than by
        // throwing, so anything it does throw is IO or corruption. That must reach the enclosing
        // `catalog-maintenance` boundary, classify and latch — and now says which operation it was.
        db.rawQuery("PRAGMA wal_checkpoint(PASSIVE)", null).use { cursor -> cursor.moveToFirst() }
        val bytesAfter = storageKnownFileBytes(databaseFile)
        return (bytesBefore - bytesAfter).coerceAtLeast(0L)
    }

    /** Rewrite only derived telemetry rows. Each tier is transactionally replace-or-delete so a
     * process death cannot leave a partially compacted window. */
    private fun applyRollupPressureTier(db: SQLiteDatabase, tier: RollupPressureTier, now: Long) {
        val window = RollupRetentionPolicy.window(tier, now)
        db.beginTransaction()
        try {
            if (window.drop) {
                db.delete("dashboard_entity_traffic_minute", window.whereSql, window.whereArgs)
            } else {
                db.execSQL("DROP TABLE IF EXISTS temp.entity_rollup_compact")
                db.execSQL(
                    """CREATE TEMP TABLE entity_rollup_compact(
                       instance TEXT NOT NULL,path TEXT NOT NULL,entity_id TEXT NOT NULL,minute INTEGER NOT NULL,
                       access_count INTEGER NOT NULL,update_count INTEGER NOT NULL,update_bytes INTEGER NOT NULL,
                       span_started_at INTEGER NOT NULL,
                       PRIMARY KEY(instance,path,entity_id,minute))""",
                )
                val minuteExpression = window.targetMinute?.toString() ?: "(minute/60)*60"
                db.execSQL(
                    """INSERT INTO entity_rollup_compact
                       SELECT instance,path,entity_id,$minuteExpression,sum(access_count),sum(update_count),sum(update_bytes),
                              min(CASE WHEN span_started_at=0 THEN minute ELSE span_started_at END)
                       FROM dashboard_entity_traffic_minute WHERE ${window.whereSql}
                       GROUP BY instance,path,entity_id,$minuteExpression""",
                    window.whereArgs,
                )
                db.delete("dashboard_entity_traffic_minute", window.whereSql, window.whereArgs)
                db.execSQL(
                    """INSERT OR REPLACE INTO dashboard_entity_traffic_minute(instance,path,entity_id,minute,access_count,update_count,update_bytes,span_started_at)
                       SELECT instance,path,entity_id,minute,access_count,update_count,update_bytes,span_started_at
                       FROM entity_rollup_compact""",
                )
                db.execSQL("DROP TABLE entity_rollup_compact")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            runCatching { db.execSQL("DROP TABLE IF EXISTS temp.entity_rollup_compact") }
        }
    }

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

    fun readProximityModel(fingerprint: String): ProximityModelRow? {
        if (!PROXIMITY_FINGERPRINT.matches(fingerprint)) return null
        return readableDatabase.rawQuery(
            "SELECT algorithm_version,behavior_signature,snapshot_json,ready,updated_at FROM proximity_model WHERE fingerprint=?",
            arrayOf(fingerprint),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else ProximityModelRow(
                fingerprint = fingerprint,
                algorithmVersion = cursor.getInt(0),
                behaviorSignature = cursor.getString(1),
                snapshotJson = cursor.getString(2),
                ready = cursor.getInt(3) != 0,
                updatedAt = cursor.getLong(4),
            )
        }
    }

    /** One bounded transaction owns model promotion and its coarse evidence. Raw events never enter SQLite. */
    fun writeProximityBatch(
        model: ProximityModelRow,
        rollups: List<ProximityRollupRow>,
        episodes: List<ProximityEpisodeRow>,
        now: Long,
    ) {
        require(PROXIMITY_FINGERPRINT.matches(model.fingerprint))
        require(model.algorithmVersion in 1..10_000)
        require(model.behaviorSignature.length <= 500)
        require(model.snapshotJson.toByteArray(Charsets.UTF_8).size <= MAX_PROXIMITY_SNAPSHOT_BYTES)
        observedWrite("proximity-history") {
            val db = writableDatabase
            db.beginTransaction()
            try {
            // SQLite REPLACE deletes the parent before inserting it. With foreign keys enabled that
            // would cascade-delete all rollups and episodes at every model checkpoint, so use a
            // portable update-then-insert upsert instead of REPLACE/modern ON CONFLICT syntax.
            val modelValues = ContentValues(6).apply {
                put("algorithm_version", model.algorithmVersion)
                put("behavior_signature", model.behaviorSignature)
                put("snapshot_json", model.snapshotJson)
                put("ready", if (model.ready) 1 else 0)
                put("updated_at", model.updatedAt)
            }
            val updated = db.update(
                "proximity_model",
                modelValues,
                "fingerprint=?",
                arrayOf(model.fingerprint),
            )
            if (updated == 0) {
                modelValues.put("fingerprint", model.fingerprint)
                db.insertOrThrow("proximity_model", null, modelValues)
            }
            val mergeRollup = db.compileStatement(
                """UPDATE proximity_sample SET
                   sample_count=MIN(1000000,sample_count+?),raw_min=MIN(raw_min,?),
                   raw_max=MAX(raw_max,?),raw_sum=raw_sum+?,raw_sum_squares=raw_sum_squares+?,
                   excursion_count=excursion_count+?,gesture_count=gesture_count+?
                   WHERE fingerprint=? AND bucket=?""",
            )
            try {
                for (row in rollups) {
                    if (row.fingerprint != model.fingerprint || row.sampleCount <= 0) continue
                    mergeRollup.clearBindings()
                    mergeRollup.bindLong(1, row.sampleCount.coerceAtMost(1_000_000).toLong())
                    mergeRollup.bindDouble(2, row.rawMin)
                    mergeRollup.bindDouble(3, row.rawMax)
                    mergeRollup.bindDouble(4, row.rawSum)
                    mergeRollup.bindDouble(5, row.rawSquareSum)
                    mergeRollup.bindLong(6, row.excursionCount.coerceAtLeast(0).toLong())
                    mergeRollup.bindLong(7, row.gestureCount.coerceAtLeast(0).toLong())
                    mergeRollup.bindString(8, row.fingerprint)
                    mergeRollup.bindLong(9, row.bucket)
                    if (mergeRollup.executeUpdateDelete() == 0) {
                        db.execSQL(
                            """INSERT INTO proximity_sample(
                               fingerprint,bucket,sample_count,raw_min,raw_max,raw_sum,raw_sum_squares,
                               excursion_count,gesture_count) VALUES(?,?,?,?,?,?,?,?,?)""",
                            arrayOf<Any?>(
                                row.fingerprint, row.bucket, row.sampleCount.coerceAtMost(1_000_000),
                                row.rawMin, row.rawMax, row.rawSum, row.rawSquareSum,
                                row.excursionCount.coerceAtLeast(0), row.gestureCount.coerceAtLeast(0),
                            ),
                        )
                    }
                }
            } finally {
                mergeRollup.close()
            }
            for (row in episodes) {
                if (row.fingerprint != model.fingerprint) continue
                db.execSQL(
                    """INSERT INTO proximity_episode(
                       fingerprint,started_at,duration_ms,peak_level,completed,guided)
                       VALUES(?,?,?,?,?,?)""",
                    arrayOf<Any?>(
                        row.fingerprint, row.startedAt, row.durationMs.coerceIn(0, 60_000),
                        row.peakLevel.coerceIn(0, 100), if (row.completed) 1 else 0,
                        if (row.guided) 1 else 0,
                    ),
                )
            }
            val cutoffBucket = (now / PROXIMITY_BUCKET_MS) - PROXIMITY_RETENTION_BUCKETS
            db.execSQL("DELETE FROM proximity_sample WHERE bucket<?", arrayOf(cutoffBucket))
            db.execSQL(
                """DELETE FROM proximity_sample WHERE rowid IN (
                   SELECT rowid FROM proximity_sample ORDER BY bucket DESC LIMIT -1 OFFSET $MAX_PROXIMITY_ROLLUPS)""",
            )
            db.execSQL(
                """DELETE FROM proximity_episode WHERE fingerprint=? AND rowid NOT IN (
                   SELECT rowid FROM proximity_episode WHERE fingerprint=? ORDER BY started_at DESC LIMIT $MAX_PROXIMITY_EPISODES)""",
                arrayOf(model.fingerprint, model.fingerprint),
            )
            val retainedFingerprints = db.rawQuery(
                "SELECT fingerprint FROM proximity_model ORDER BY updated_at DESC LIMIT $MAX_PROXIMITY_MODELS",
                null,
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            if (retainedFingerprints.isNotEmpty()) {
                val placeholders = retainedFingerprints.joinToString(",") { "?" }
                val args = retainedFingerprints.toTypedArray()
                db.delete("proximity_model", "fingerprint NOT IN ($placeholders)", args)
                db.delete("proximity_sample", "fingerprint NOT IN ($placeholders)", args)
                db.delete("proximity_episode", "fingerprint NOT IN ($placeholders)", args)
            }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        observedMaintenance(now)
    }

    fun clearProximityLearning(fingerprint: String): Unit = observedWrite("proximity-history-reset") {
        if (!PROXIMITY_FINGERPRINT.matches(fingerprint)) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("proximity_episode", "fingerprint=?", arrayOf(fingerprint))
            db.delete("proximity_sample", "fingerprint=?", arrayOf(fingerprint))
            db.delete("proximity_model", "fingerprint=?", arrayOf(fingerprint))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

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

    fun databaseUsage(now: Long = System.currentTimeMillis()): DatabaseUsage {
        synchronized(databaseBytesCacheLock) {
            if (databaseBytesCachedAt != Long.MIN_VALUE && now >= databaseBytesCachedAt &&
                now - databaseBytesCachedAt < DATABASE_BYTES_CACHE_MS) {
                return databaseUsageCachedValue
            }
        }
        return measureDatabaseUsage().also { updateDatabaseUsageCache(now, it) }
    }

    /**
     * Bounded, path-free health evidence for the filesystem containing the database that SQLite
     * actually opened. [cancellationSignal] reaches every PRAGMA cursor, including quick_check(1).
     */
    fun storageHealthObservation(
        cancellationSignal: CancellationSignal? = null,
        checkedAtMillis: Long = System.currentTimeMillis(),
    ): StorageHealthObservation {
        // A BUSY read (a checkpoint racing this probe) is the app's own concurrency; latching it
        // would recreate the exact false failure this probe exists to verify away, so it gets the
        // same bounded retry as writes. Cancellation propagates unlatched and also stops retrying.
        val retry = busyRetry.begin()
        while (true) {
            try {
                return readStorageHealthObservation(cancellationSignal, checkedAtMillis)
            } catch (cancelled: OperationCanceledException) {
                throw cancelled
            } catch (failure: SQLException) {
                val abandoned = { busyRetryAbandoned || cancellationSignal?.isCanceled == true }
                if (!retry.admitRetry(failure, abandoned)) {
                    StorageHealthRuntime.recordDatabaseFailure("storage-health-read", failure)
                    throw failure
                }
            }
        }
    }

    private fun readStorageHealthObservation(
        cancellationSignal: CancellationSignal?,
        checkedAtMillis: Long,
    ): StorageHealthObservation {
        val db = readableDatabase
        val database = File(db.path)
        // A filesystem probe failure makes capacity unknown; it must not discard valid SQLite evidence
        // or masquerade as a database failure.
        val statFs = runCatching { StatFs(database.path) }.getOrNull()

        fun pragmaLong(name: String): Long = db.rawQuery("PRAGMA $name", null, cancellationSignal).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(0L) else 0L
        }

        val quickCheck = db.rawQuery("PRAGMA quick_check(1)", null, cancellationSignal).use { cursor ->
            if (cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)) {
                StorageQuickCheck.OK
            } else {
                StorageQuickCheck.FAILED
            }
        }
        val blockSize = statFs?.blockSizeLong?.coerceAtLeast(0L) ?: 0L
        return StorageHealthObservation(
            checkedAtMillis = checkedAtMillis,
            usableBytes = storageSaturatedMultiply(statFs?.availableBlocksLong?.coerceAtLeast(0L) ?: 0L, blockSize),
            totalBytes = storageSaturatedMultiply(statFs?.blockCountLong?.coerceAtLeast(0L) ?: 0L, blockSize),
            mainDatabaseBytes = storageKnownFileBytes(database),
            walBytes = storageKnownFileBytes(File(database.path + "-wal")),
            sidecarBytes = storageSaturatedAdd(
                storageKnownFileBytes(File(database.path + "-shm")),
                storageKnownFileBytes(File(database.path + "-journal")),
            ),
            pageSizeBytes = pragmaLong("page_size"),
            pageCount = pragmaLong("page_count"),
            freelistCount = pragmaLong("freelist_count"),
            schemaVersion = db.version.coerceAtLeast(0),
            quickCheck = quickCheck,
            // Without this, a large freelist_count is ambiguous: reclamation lagging, or a database
            // on which bounded reclamation can never run at all.
            // Read separately from pragmaLong, which cannot distinguish an empty cursor from a
            // real zero. NONE is an operator-actionable claim — "these pages will never come back" —
            // and must not be asserted from a read that did not happen.
            autoVacuumMode = db.rawQuery("PRAGMA auto_vacuum", null, cancellationSignal).use { cursor ->
                when (if (cursor.moveToFirst()) cursor.getLong(0) else -1L) {
                    AUTO_VACUUM_NONE -> StorageAutoVacuumMode.NONE
                    AUTO_VACUUM_FULL -> StorageAutoVacuumMode.FULL
                    AUTO_VACUUM_INCREMENTAL -> StorageAutoVacuumMode.INCREMENTAL
                    else -> StorageAutoVacuumMode.UNKNOWN
                }
            },
        )
    }

    private fun updateDatabaseUsageCache(now: Long, usage: DatabaseUsage) = synchronized(databaseBytesCacheLock) {
        databaseBytesCachedAt = now
        databaseUsageCachedValue = usage
    }

    private fun measureDatabaseUsage(db: SQLiteDatabase = readableDatabase): DatabaseUsage {
        fun pragma(name: String): Long = db.rawQuery("PRAGMA $name", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
        val usedPages = (pragma("page_count") - pragma("freelist_count")).coerceAtLeast(0)
        val pageSize = pragma("page_size").coerceAtLeast(0)
        val usedBytes = if (usedPages == 0L || pageSize <= Long.MAX_VALUE / usedPages) {
            usedPages * pageSize
        } else {
            Long.MAX_VALUE
        }
        return DatabaseUsage(
            usedBytes = usedBytes,
            diskBytes = knownDatabaseFootprint(File(db.path)),
            schemaVersion = db.version,
        )
    }

    /**
     * Every durable `app_state` row, for the portable backup archive.
     *
     * Reads the whole table rather than a list of declared settings, so a namespace introduced later is
     * captured without anyone remembering to add it. What a *restore* may write back is a separate
     * decision held by [io.github.maxlyth.hapaneld.persistence.StateBackupPolicy].
     */
    internal fun exportAppState(): List<ConfigVault.StateRow> =
        readableDatabase.rawQuery(
            "SELECT namespace,state_key,value_type,value_text,updated_at FROM app_state",
            emptyArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ConfigVault.StateRow(
                            namespace = cursor.getString(0),
                            key = cursor.getString(1),
                            type = cursor.getString(2),
                            valueText = if (cursor.isNull(3)) null else cursor.getString(3),
                            updatedAt = cursor.getLong(4),
                        ),
                    )
                }
            }
        }

    companion object {
        internal const val DATABASE_NAME = "ha-paneld.db"
        private const val VERSION = EntityCatalogSchema.CURRENT_VERSION

        /** Mirrors RuntimeProfileRegistry's imported catalog; these live outside the database. */
        private const val IMPORTED_PROFILE_DIRECTORY = "device-profiles/imported"

        /** A profile is a small YAML document; anything larger is not one and is not worth vaulting. */
        private const val MAX_VAULTED_PROFILE_BYTES = 256L * 1024
        private const val HOUR_MS = 3_600_000L
        private const val MINUTE_MS = 60_000L
        private const val RUNTIME_RETENTION_MS = 30L * 24 * HOUR_MS
        private const val TOMBSTONE_RETENTION_MS = 30L * 24 * HOUR_MS
        private const val SOFT_LIMIT_BYTES = 128L * 1024 * 1024
        private const val RATE_WINDOW_MS = 5L * 60_000
        private const val RATE_STALE_MS = 90_000L
        private const val METRIC_BATCH_MS = 5_000L
        private const val DAY_MS = 24L * HOUR_MS
        private const val MAX_RANKING_CACHE_ENTRIES = 4
        private const val DATABASE_BYTES_CACHE_MS = 10_000L
        private const val MAINTENANCE_INTERVAL_MS = 10L * 60_000
        /** Rows per maintenance chunk: small enough that one chunk's lock hold stays well under a
         *  concurrent writer's busy timeout, large enough that a full purge stays a few dozen chunks. */
        private const val MAINTENANCE_CHUNK_ROWS = 1_000
        private const val AUTO_VACUUM_NONE = 0L
        private const val AUTO_VACUUM_FULL = 1L
        private const val AUTO_VACUUM_INCREMENTAL = 2L
        /** ~1 MiB of 4 KiB pages per vacuum slice; each slice is its own short write transaction. */
        private const val VACUUM_CHUNK_PAGES = 256L
        /** Cap one maintenance pass's total reclamation (~20 MiB) so it never monopolizes the writer. */
        private const val MAX_VACUUM_PAGES_PER_PASS = 5_120L
        /** Small freelist retained for ordinary page reuse; below this, reclamation is not worth a lock. */
        private const val FREELIST_RETAINED_PAGES = 512L
        /**
         * Free space a reclamation pass needs on top of its own transient cost. Reclamation adds WAL
         * frames for the pages it relocates before the checkpoint gives anything back, so a pass run
         * with no margin can deepen the low-space incident it was meant to relieve.
         */
        private const val RECLAMATION_HEADROOM_MARGIN_BYTES = 64L * 1024L * 1024L
        private const val MAX_SQL_ID_FILTER = 800
        internal const val PERFORMANCE_RETENTION_DAYS = 7
        private const val PERFORMANCE_RETENTION_MINUTES = PERFORMANCE_RETENTION_DAYS * 24L * 60L
        private const val PERFORMANCE_MAINTENANCE_INTERVAL_MS = HOUR_MS
        /**
         * Per-minute dashboard measurements, with the metric *set* held as data in [payload].
         *
         * Replaces the 25 fixed columns of `dashboard_performance`, where the metric list was part of the
         * schema and each new probe therefore cost a migration, a version bump and a fleet release —
         * which in practice meant the instrumentation was never added when it was wanted.
         *
         * Storage is a wash, not a saving. On a real panel the payloads are 46 bytes per bucket (207,891
         * total), but the `(instance, path)` key costs ~59 more, so the table lands at 475,136 against
         * 450,560 for the columns it replaces — about 5% more on one table, 0.2% of the database. The
         * prototype that measured 0.66x keyed rows on an interned integer scope id; interning here would
         * need a second table and a non-additive key change, which is a poor trade for bytes that do not
         * matter. The flexibility is the point; the storage merely has to not regress meaningfully.
         */
        internal val ENTITY_TABLE_SQL = """CREATE TABLE entity(
            instance TEXT NOT NULL, entity_id TEXT NOT NULL, state TEXT NOT NULL DEFAULT '',
            attributes_json TEXT NOT NULL DEFAULT '{}', metadata_json TEXT NOT NULL DEFAULT '{}',
            first_seen_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL, missing_streak INTEGER NOT NULL DEFAULT 0,
            tombstone_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(instance,entity_id))"""
        internal val DASHBOARD_TABLE_SQL = """CREATE TABLE dashboard(
            instance TEXT NOT NULL, path TEXT NOT NULL, config_hash TEXT NOT NULL DEFAULT '',
            config_json TEXT NOT NULL DEFAULT '{}', status TEXT NOT NULL DEFAULT 'disabled',
            last_sync_at INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '',
            unresolved_json TEXT NOT NULL DEFAULT '[]', sync_generation INTEGER NOT NULL DEFAULT 0,
            issues_json TEXT NOT NULL DEFAULT '[]', analyzer_policy_version INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(instance,path))"""
        internal val DASHBOARD_ENTITY_TABLE_SQL = """CREATE TABLE dashboard_entity(
            instance TEXT NOT NULL, path TEXT NOT NULL, entity_id TEXT NOT NULL,
            referenced_by_config INTEGER NOT NULL DEFAULT 0, referenced_at_runtime INTEGER NOT NULL DEFAULT 0,
            pinned INTEGER NOT NULL DEFAULT 0, excluded INTEGER NOT NULL DEFAULT 0,
            reasons TEXT NOT NULL DEFAULT '', first_access_at INTEGER NOT NULL DEFAULT 0,
            last_access_at INTEGER NOT NULL DEFAULT 0, access_count INTEGER NOT NULL DEFAULT 0,
            update_count INTEGER NOT NULL DEFAULT 0, update_bytes INTEGER NOT NULL DEFAULT 0,
            rate_window_started_at INTEGER NOT NULL DEFAULT 0, rate_update_bytes INTEGER NOT NULL DEFAULT 0,
            last_update_at INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(instance,path,entity_id))"""
        internal val DASHBOARD_ENTITY_TRAFFIC_TABLE_SQL = """CREATE TABLE dashboard_entity_traffic_minute(
            instance TEXT NOT NULL, path TEXT NOT NULL, entity_id TEXT NOT NULL, minute INTEGER NOT NULL,
            access_count INTEGER NOT NULL DEFAULT 0, update_count INTEGER NOT NULL DEFAULT 0,
            update_bytes INTEGER NOT NULL DEFAULT 0, span_started_at INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(instance,path,entity_id,minute))"""
        internal val DASHBOARD_IGNORED_ISSUE_TABLE_SQL = """CREATE TABLE dashboard_ignored_issue(
            instance TEXT NOT NULL, path TEXT NOT NULL, fingerprint TEXT NOT NULL, ignored_at INTEGER NOT NULL,
            PRIMARY KEY(instance,path,fingerprint),
            FOREIGN KEY(instance,path) REFERENCES dashboard(instance,path) ON DELETE CASCADE)"""
        internal val DASHBOARD_METRIC_TABLE_SQL = """CREATE TABLE dashboard_metric_minute(
            instance TEXT NOT NULL, path TEXT NOT NULL, minute INTEGER NOT NULL,
            payload BLOB NOT NULL,
            PRIMARY KEY(instance,path,minute)) WITHOUT ROWID"""

        /**
         * Moves existing history into payloads. Runs inside the migration transaction, so a failure
         * leaves the database as it was rather than half-converted.
         *
         * The source rows are deleted here; the table itself is dropped by the following step, which
         * declares the compatibility break that its renames require anyway.
         */
        internal fun migrateDashboardPerformanceToPayloads(db: SQLiteDatabase) {
            val columns = DashboardMetrics.METRICS.joinToString(",") { it.name }
            db.rawQuery(
                "SELECT instance,path,minute,$columns FROM dashboard_performance", emptyArray(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val values = DashboardMetrics.METRICS.withIndex().associate { (index, metric) ->
                        metric.id to cursor.getLong(3 + index)
                    }
                    db.execSQL(
                        "INSERT OR REPLACE INTO dashboard_metric_minute(instance,path,minute,payload) VALUES(?,?,?,?)",
                        arrayOf<Any?>(
                            cursor.getString(0), cursor.getString(1), cursor.getLong(2),
                            MetricPayload.encode(values),
                        ),
                    )
                }
            }
            db.execSQL("DELETE FROM dashboard_performance")
        }

        internal val PERFORMANCE_HISTORY_TABLE_SQL = """CREATE TABLE dashboard_performance(
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
            PRIMARY KEY(instance,path,minute))"""
        internal val APP_STATE_REVISION_TABLE_SQL = """CREATE TABLE app_state_revision(
            revision INTEGER PRIMARY KEY AUTOINCREMENT,
            committed_at INTEGER NOT NULL,
            namespace TEXT NOT NULL,
            source TEXT NOT NULL DEFAULT 'app')"""
        internal val APP_STATE_NAMESPACE_TABLE_SQL = """CREATE TABLE app_state_namespace(
            namespace TEXT PRIMARY KEY,
            imported_at INTEGER NOT NULL,
            legacy_name TEXT NOT NULL DEFAULT '')"""
        internal val APP_STATE_TABLE_SQL = """CREATE TABLE app_state(
            namespace TEXT NOT NULL,
            state_key TEXT NOT NULL,
            value_type TEXT NOT NULL,
            value_text TEXT,
            updated_at INTEGER NOT NULL,
            revision INTEGER NOT NULL,
            PRIMARY KEY(namespace,state_key),
            FOREIGN KEY(revision) REFERENCES app_state_revision(revision))"""
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

        internal val PROXIMITY_MODEL_TABLE_SQL = """CREATE TABLE proximity_model(
            fingerprint TEXT PRIMARY KEY,
            algorithm_version INTEGER NOT NULL,
            behavior_signature TEXT NOT NULL DEFAULT '',
            snapshot_json TEXT NOT NULL,
            ready INTEGER NOT NULL DEFAULT 0,
            updated_at INTEGER NOT NULL)"""
        internal val PROXIMITY_SAMPLE_TABLE_SQL = """CREATE TABLE proximity_sample(
            fingerprint TEXT NOT NULL,
            bucket INTEGER NOT NULL,
            sample_count INTEGER NOT NULL,
            raw_min REAL NOT NULL,
            raw_max REAL NOT NULL,
            raw_sum REAL NOT NULL,
            raw_sum_squares REAL NOT NULL,
            excursion_count INTEGER NOT NULL DEFAULT 0,
            gesture_count INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(fingerprint,bucket),
            FOREIGN KEY(fingerprint) REFERENCES proximity_model(fingerprint) ON DELETE CASCADE)"""
        internal val PROXIMITY_EPISODE_TABLE_SQL = """CREATE TABLE proximity_episode(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            fingerprint TEXT NOT NULL,
            started_at INTEGER NOT NULL,
            duration_ms INTEGER NOT NULL,
            peak_level INTEGER NOT NULL,
            completed INTEGER NOT NULL,
            guided INTEGER NOT NULL,
            FOREIGN KEY(fingerprint) REFERENCES proximity_model(fingerprint) ON DELETE CASCADE)"""
        private val PROXIMITY_FINGERPRINT = Regex("^[a-f0-9]{32,64}$")
        private const val MAX_PROXIMITY_SNAPSHOT_BYTES = 64 * 1024
        private const val MAX_PROXIMITY_MODELS = 2
        private const val MAX_PROXIMITY_ROLLUPS = 5_000
        private const val MAX_PROXIMITY_EPISODES = 512
        private const val PROXIMITY_BUCKET_MS = 5L * 60_000L
        private const val PROXIMITY_RETENTION_BUCKETS = 7L * 24L * 12L
        internal val AMBIENT_HISTORY_TABLE_SQL = """CREATE TABLE ambient_lux_minute(
            context_id TEXT NOT NULL,source_id TEXT NOT NULL,minute INTEGER NOT NULL,
            lux_integral REAL NOT NULL DEFAULT 0,coverage_ms INTEGER NOT NULL DEFAULT 0,
            min_lux REAL NOT NULL DEFAULT 0,max_lux REAL NOT NULL DEFAULT 0,last_lux REAL NOT NULL DEFAULT 0,
            sample_count INTEGER NOT NULL DEFAULT 0,baseline_log_integral REAL NOT NULL DEFAULT 0,
            baseline_coverage_ms INTEGER NOT NULL DEFAULT 0,
             PRIMARY KEY(context_id,source_id,minute))"""
        // Two inclusive seven-day minute streams (20,162 rows) plus 3,838 rows of headroom.
        private const val AMBIENT_GLOBAL_ROW_LIMIT = 24_000
        private const val PANEL_AMBIENT_SOURCE_ID = "panel"
        private val FINGERPRINT = Regex("^[a-f0-9]{16}$")

        /** Main database plus SQLite's allowlisted sidecars; no directory enumeration or path output. */
        internal fun knownDatabaseFootprint(database: File): Long =
            listOf("", "-wal", "-shm", "-journal").fold(0L) { total, suffix ->
                val bytes = runCatching { File(database.path + suffix).takeIf(File::isFile)?.length() ?: 0L }
                    .getOrDefault(0L)
                if (Long.MAX_VALUE - total < bytes) Long.MAX_VALUE else total + bytes
            }

        // ---- Schema downgrade safety net (Tier-2). See reconcilePreOpen() below. ----
        private val DATABASE_RESTORE_OPEN_LEASE = DatabaseRestoreOpenLease()

        private fun retainDatabaseFailure(operation: String, failure: Throwable) {
            if (failure is SQLException) StorageHealthRuntime.recordDatabaseFailure(operation, failure)
        }

        /** Outcome of the most recent pre-open schema reconciliation, for health reporting. */
        @Volatile
        internal var lastSchemaReconcile: SchemaReconcile? = null

        /**
         * Set when configuration was recovered from the vault into a freshly created database. Read by
         * health so the schema warning can say what actually happened: "settings may have reset" is
         * untrue once they have been restored, and telling an owner to re-enter working configuration is
         * its own kind of damage.
         */
        internal var lastConfigRestore: ConfigRestore? = null
            private set

        /** Runs the authoritative pre-open decision; refusal must prevent SQLiteOpenHelper from opening. */
        private fun reconcileSchema(context: Context): SchemaReconcile {
            return try {
                val target = context.getDatabasePath(DATABASE_NAME)
                val boundary = EntityCatalogSchema.DATABASE_COMPATIBILITY
                val restore = DatabaseRestoreTransaction(target)
                val resumed = restore.reconcile()
                if (resumed is DatabaseRestoreResult.Hold) {
                    throw DatabaseRestoreHoldException(resumed.reason)
                }
                val observation = DatabaseCompatibility.observe(context, boundary)
                (observation.primary as? PrimaryDatabaseObservation.Unreadable)?.let { unreadable ->
                    val failure = SQLException(unreadable.detail ?: "database observation unreadable")
                    retainDatabaseFailure("database-version-read", failure)
                }
                val outcome = reconcilePreOpen(
                    target,
                    boundary,
                    observation,
                    checkpoint = ::checkpointDatabaseFile,
                    vaultConfig = { database -> vaultConfiguration(context, database) },
                    revalidateObservation = { DatabaseCompatibility.observe(context, boundary) },
                    stageRecovery = { recovery -> stageValidatedRecovery(recovery, context.cacheDir) },
                    restoreTransaction = restore,
                )
                lastSchemaReconcile = retainFirstSchemaReconcile(lastSchemaReconcile, outcome)
                outcome
            } catch (failure: Throwable) {
                retainDatabaseFailure("database-preopen-reconcile", failure)
                throw failure
            }
        }

        /**
         * Copies configuration and imported device profiles into the vault before the structure changes.
         *
         * Reads the database directly rather than going through [AppState] so it does not depend on the
         * app's object model or on the schema version, and opens read-only so a failure here can never
         * damage the database it is protecting. Best-effort by design: an unreadable database yields no
         * rows, and [ConfigVault.write] refuses an empty export rather than overwriting good generations.
         */
        private fun vaultConfiguration(context: Context, database: File) {
            if (!database.isFile) return
            val rows = runCatching {
                SQLiteDatabase.openDatabase(
                    database.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ).use { db ->
                    db.rawQuery(
                        "SELECT namespace,state_key,value_type,value_text,updated_at FROM app_state",
                        emptyArray(),
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) {
                                add(
                                    ConfigVault.StateRow(
                                        namespace = cursor.getString(0),
                                        key = cursor.getString(1),
                                        type = cursor.getString(2),
                                        valueText = if (cursor.isNull(3)) null else cursor.getString(3),
                                        updatedAt = cursor.getLong(4),
                                    ),
                                )
                            }
                        }
                    }
                }
            }.onFailure { retainDatabaseFailure("database-vault-read", it) }.getOrDefault(emptyList())

            val profiles = runCatching {
                File(context.filesDir, IMPORTED_PROFILE_DIRECTORY).listFiles()
                    ?.filter { it.isFile && it.length() <= MAX_VAULTED_PROFILE_BYTES }
                    ?.associate { it.name to it.readText() }
                    .orEmpty()
            }.getOrDefault(emptyMap())

            ConfigVault.write(
                File(context.filesDir, ConfigVault.VAULT_DIRECTORY),
                ConfigVault.Export(rows, profiles),
                System.currentTimeMillis(),
            )
        }

        /**
         * Restores vaulted configuration and imported profiles into a freshly created database.
         *
         * Only ever called from [onCreate], so `app_state` is empty by construction; the emptiness check
         * is kept anyway because restoring *over* live configuration is the one genuinely dangerous
         * direction and must be impossible rather than merely unreachable. Only a generation whose digest
         * verifies is considered, and a failure leaves the fresh database untouched rather than
         * half-populated — losing a recovery is recoverable, corrupting one is not.
         *
         * On first install the vault is absent and this is a no-op. Clearing app data removes the vault
         * along with the database, so a deliberate reset stays a reset.
         */
        private fun restoreConfigurationInto(db: SQLiteDatabase, context: Context) {
            runCatching {
                val vaultDir = File(context.filesDir, ConfigVault.VAULT_DIRECTORY)
                val export = ConfigVault.newestValid(vaultDir) ?: return
                if (countRows(db, "app_state") != 0) return

                db.beginTransaction()
                try {
                    db.execSQL(
                        "INSERT INTO app_state_revision(committed_at,namespace,source) VALUES(?,?,?)",
                        arrayOf<Any?>(System.currentTimeMillis(), "config", "config-vault"),
                    )
                    val revision = countRows(db, "app_state_revision").toLong()
                    export.rows.map { it.namespace }.distinct().forEach { namespace ->
                        db.execSQL(
                            "INSERT OR IGNORE INTO app_state_namespace(namespace,imported_at,legacy_name) VALUES(?,?,'')",
                            arrayOf<Any?>(namespace, System.currentTimeMillis()),
                        )
                    }
                    export.rows.forEach { row ->
                        db.execSQL(
                            "INSERT OR REPLACE INTO app_state(namespace,state_key,value_type,value_text,updated_at,revision) " +
                                "VALUES(?,?,?,?,?,?)",
                            arrayOf<Any?>(row.namespace, row.key, row.type, row.valueText, row.updatedAt, revision),
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }

                // Profiles are files, so they are restored only where nothing occupies the name; an
                // existing profile is always more current than a vaulted copy.
                val importedDir = File(context.filesDir, IMPORTED_PROFILE_DIRECTORY)
                export.profiles.forEach { (name, content) ->
                    val destination = File(importedDir, name)
                    if (!destination.exists() && destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
                        runCatching { destination.writeText(content) }
                    }
                }
                lastConfigRestore = ConfigRestore(export.rows.size, export.profiles.size)
            }.onFailure { retainDatabaseFailure("database-vault-restore", it) }
        }

        private fun countRows(db: SQLiteDatabase, table: String): Int =
            db.rawQuery("SELECT count(*) FROM $table", emptyArray()).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }

        /**
         * Rebuilds every table whose name or columns changed, copying rows across.
         *
         * A rebuild rather than `ALTER TABLE ... RENAME COLUMN` because that needs SQLite 3.25, which
         * arrived in API 30, and `minSdk` is 26 — an API 27 panel cannot execute it. Running once at
         * upgrade, on a few thousand rows, so the copy costs nothing worth optimising.
         *
         * Order is the delicate part. `dashboard` is a foreign-key parent and `dashboard_issue_ignore`
         * cascades from it, so dropping `dashboard` while that child exists would silently delete the
         * user's ignored issues. `PRAGMA foreign_keys` is a no-op inside a transaction and `onUpgrade`
         * already holds one, so the constraint cannot simply be switched off: instead the child is read
         * out and dropped first, the parent is rebuilt, and the child is recreated and refilled.
         */
        internal fun rebuildTablesWithFinalNames(db: SQLiteDatabase) {
            // 1. Take the cascading child out of the way, keeping its rows.
            val ignoredIssues = db.rawQuery(
                "SELECT instance,path,fingerprint,ignored_at FROM dashboard_issue_ignore", emptyArray(),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(arrayOf<Any?>(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
                    }
                }
            }
            db.execSQL("DROP TABLE dashboard_issue_ignore")

            // 2. Rebuild the parent, then the remaining tables. Each is create-copy-drop-rename.
            rebuild(
                db, "dashboard", DASHBOARD_TABLE_SQL,
                "instance,path,config_hash,config_json,status,last_sync_at,error,unresolved_json," +
                    "sync_generation,issues_json,analyzer_policy_version",
                "instance,path,config_hash,config_json,status,last_sync,error,unresolved_json," +
                    "sync_generation,issues_json,analyzer_policy_version",
            )
            rebuild(
                db, "entity", ENTITY_TABLE_SQL,
                "instance,entity_id,state,attributes_json,metadata_json,first_seen_at,last_seen_at," +
                    "missing_streak,tombstone_at",
                "instance,entity_id,state,attributes_json,metadata_json,first_seen,last_seen," +
                    "missing_streak,tombstone_at",
            )
            rebuild(
                db, "membership", DASHBOARD_ENTITY_TABLE_SQL,
                "instance,path,entity_id,referenced_by_config,referenced_at_runtime,pinned,excluded,reasons," +
                    "first_access_at,last_access_at,access_count,update_count,update_bytes," +
                    "rate_window_started_at,rate_update_bytes,last_update_at",
                "instance,path,entity_id,static_ref,runtime_ref,pinned,excluded,reasons," +
                    "first_access,last_access,access_count,update_count,update_bytes," +
                    "rate_window_start,rate_update_bytes,last_update_at",
                target = "dashboard_entity",
            )
            rebuild(
                db, "minute_rollup", DASHBOARD_ENTITY_TRAFFIC_TABLE_SQL,
                "instance,path,entity_id,minute,access_count,update_count,update_bytes,span_started_at",
                "instance,path,entity_id,minute,access_count,update_count,update_bytes,span_start",
                target = "dashboard_entity_traffic_minute",
            )
            rebuild(
                db, "proximity_rollup", PROXIMITY_SAMPLE_TABLE_SQL,
                "fingerprint,bucket,sample_count,raw_min,raw_max,raw_sum,raw_sum_squares,excursion_count,gesture_count",
                "fingerprint,bucket,sample_count,raw_min,raw_max,raw_sum,raw_square_sum,excursion_count,gesture_count",
                target = "proximity_sample",
            )

            // 3. Recreate the child against the rebuilt parent and put its rows back.
            db.execSQL(DASHBOARD_IGNORED_ISSUE_TABLE_SQL)
            ignoredIssues.forEach { row ->
                db.execSQL(
                    "INSERT OR IGNORE INTO dashboard_ignored_issue(instance,path,fingerprint,ignored_at) " +
                        "VALUES(?,?,?,?)",
                    row,
                )
            }

            listOf(
                "CREATE INDEX ix_entity_missing ON entity(instance,missing_streak)",
                "CREATE INDEX ix_dashboard_entity_load ON dashboard_entity(instance,path,update_bytes DESC)",
                "CREATE INDEX ix_dashboard_entity_traffic_minute_age ON dashboard_entity_traffic_minute(instance,path,minute)",
                "CREATE INDEX ix_proximity_sample_age ON proximity_sample(bucket)",
                "CREATE INDEX ix_app_state_updated ON app_state(namespace,updated_at)",
                "CREATE INDEX ix_proximity_episode_age ON proximity_episode(started_at)",
                "CREATE INDEX ix_ambient_lux_minute_age ON ambient_lux_minute(minute)",
            ).forEach(db::execSQL)
        }

        /**
         * Creates [createSql]'s table, copies [source] columns into [destination] columns positionally,
         * then drops [from]. [target] defaults to [from] for a rebuild that keeps its name.
         */
        private fun rebuild(
            db: SQLiteDatabase,
            from: String,
            createSql: String,
            destination: String,
            source: String,
            target: String = from,
        ) {
            val staging = "${target}_rebuild"
            db.execSQL(createSql.replaceFirst("CREATE TABLE $target(", "CREATE TABLE $staging("))
            db.execSQL("INSERT INTO $staging($destination) SELECT $source FROM $from")
            db.execSQL("DROP TABLE $from")
            db.execSQL("ALTER TABLE $staging RENAME TO $target")
        }

        /**
         * Flushes WAL into the main file so a single-file pre-migration copy is complete.
         *
         * The checkpoint opens its own raw connection, so a concurrent instance's routine write makes
         * it report busy — the app's own concurrency, not a storage fault (Issue #91). A busy result
         * or a BUSY-classified throw is re-attempted within one bounded budget before latching.
         */
        private fun checkpointDatabaseFile(database: File): Boolean {
            val retry = DatabaseBusyRetry().begin()
            while (true) {
                try {
                    val completed = SQLiteDatabase.openDatabase(
                        database.path,
                        null,
                        SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or
                            SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
                    ).use { opened ->
                        opened.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                            cursor.moveToFirst() && cursor.columnCount == 3 &&
                                cursor.getLong(0) == 0L && cursor.getLong(1) == 0L &&
                                cursor.getLong(2) == 0L && !cursor.moveToNext()
                        }
                    }
                    if (completed) {
                        StorageHealthRuntime.recordDatabaseWriteSuccess()
                        return true
                    }
                    val busy = IllegalStateException("database is busy")
                    if (!retry.admitRetry(busy)) {
                        StorageHealthRuntime.recordDatabaseFailure("database-checkpoint", busy)
                        return false
                    }
                } catch (failure: Throwable) {
                    if (failure is SQLException && retry.admitRetry(failure)) continue
                    StorageHealthRuntime.recordDatabaseFailure("database-checkpoint", failure)
                    return false
                }
            }
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

internal enum class SchemaReconcileAction { NONE, BACKED_UP, RESTORED, PRESERVED_FRESH }

/** What the config vault put back into a freshly created database. */
internal data class ConfigRestore(val settings: Int, val profiles: Int)

/** Result of [reconcilePreOpen]; surfaced via EntityCatalogStore.lastSchemaReconcile for health. */
internal data class SchemaReconcile(
    val action: SchemaReconcileAction,
    val fromVersion: Int,
    val toVersion: Int,
    val restoredVersion: Int = 0,
)

internal class DatabaseCompatibilityException(
    val refusal: DatabaseCompatibilityRefusal,
) : SQLException("database compatibility refused: $refusal")

/** Keep a meaningful downgrade outcome visible for the lifetime of the process. */
internal fun retainFirstSchemaReconcile(
    previous: SchemaReconcile?,
    current: SchemaReconcile,
): SchemaReconcile? = when {
    previous == null || previous.action == SchemaReconcileAction.NONE -> current
    current.action == SchemaReconcileAction.NONE -> previous
    else -> previous
}

private const val PREMIGRATE_SUFFIX = "premigrate"
private const val SUPERSEDED_SUFFIX = "superseded"
private const val MAX_PREMIGRATE_BACKUPS = 2
private const val MAX_PREMIGRATE_BYTES = 64L * 1024 * 1024
private const val SPACE_MARGIN_BYTES = 8L * 1024 * 1024
private fun storageKnownFileBytes(file: File): Long = runCatching {
    file.takeIf(File::isFile)?.length()?.coerceAtLeast(0L) ?: 0L
}.getOrDefault(0L)

private fun storageSaturatedAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private fun storageSaturatedMultiply(left: Long, right: Long): Long = when {
    left == 0L || right == 0L -> 0L
    left > Long.MAX_VALUE / right -> Long.MAX_VALUE
    else -> left * right
}

/** Path of the pre-migration snapshot taken at [version] for [target] (a restore source). */
internal fun preMigrationBackupFile(target: File, version: Int): File =
    File(target.parentFile, "${target.name}.v$version.$PREMIGRATE_SUFFIX")

/** Path where a too-new database is preserved for manual recovery (never a restore source). */
internal fun supersededFile(target: File, version: Int): File =
    File(target.parentFile, "${target.name}.v$version.$SUPERSEDED_SUFFIX")

/**
 * Reconcile the on-disk schema version against this build before the database is opened. Pure file
 * operations; [checkpoint] flushes WAL for the real store and is stubbed in tests. A refusal throws
 * before vault, checkpoint, or file mutations so SQLiteOpenHelper cannot bypass the decision.
 */
internal fun reconcilePreOpen(
    target: File,
    boundary: DatabaseCompatibilityBoundary,
    observation: DatabaseCompatibilityObservation,
    keepBackups: Int = MAX_PREMIGRATE_BACKUPS,
    maxBackupBytes: Long = MAX_PREMIGRATE_BYTES,
    freeSpace: (File) -> Long = { it.usableSpace },
    checkpoint: (File) -> Boolean = { true },
    vaultConfig: (File) -> Unit = {},
    revalidateObservation: () -> DatabaseCompatibilityObservation = { observation },
    stageRecovery: (RecoveryDatabaseObservation) -> File? = { it.file },
    restoreTransaction: DatabaseRestoreTransaction = DatabaseRestoreTransaction(target),
): SchemaReconcile {
    val currentVersion = boundary.maximumSchema
    when (val durable = restoreTransaction.reconcile()) {
        DatabaseRestoreResult.Absent -> Unit
        is DatabaseRestoreResult.Hold -> throw DatabaseRestoreHoldException(durable.reason)
        is DatabaseRestoreResult.Restored -> {
            if (durable.reconcile.toVersion != currentVersion ||
                durable.reconcile.restoredVersion != currentVersion
            ) throw DatabaseRestoreHoldException("database restore receipt is for another schema")
            return durable.reconcile
        }
    }
    val decision = DatabaseCompatibility.decide(
        boundary,
        observation,
        DatabaseOwnerState.RUNTIME_STARTUP,
    )
    if (decision is DatabaseCompatibilityDecision.Refuse) {
        throw DatabaseCompatibilityException(decision.reason)
    }
    // DB_COMPAT_MUTATION_ANCHOR: OBSERVATION_REVALIDATION
    // DB_COMPAT_MUTATION_ANCHOR: RUNTIME_FRESH_REVALIDATION
    // Fresh is only an observation, not a durable state. Reobserve it at the same last pre-open gate as
    // every existing database so a file, recovery, retained sidecar, or unreadable path that appears
    // after the initial observation cannot be opened, created over, or otherwise mutated by the helper.
    val revalidatedObservation = revalidateObservation()
    if (revalidatedObservation != observation ||
        DatabaseCompatibility.decide(
            boundary,
            revalidatedObservation,
            DatabaseOwnerState.RUNTIME_STARTUP,
        ) != decision
    ) {
        throw DatabaseCompatibilityException(
            DatabaseCompatibilityRefusal.DATABASE_CHANGED_AFTER_OBSERVATION,
        )
    }
    if (decision is DatabaseCompatibilityDecision.Fresh) {
        return SchemaReconcile(SchemaReconcileAction.NONE, 0, currentVersion)
    }
    val onDiskVersion = (observation.primary as? PrimaryDatabaseObservation.Readable)?.schema
        ?: throw IllegalStateException("non-fresh decision requires readable primary database")
    if (decision is DatabaseCompatibilityDecision.Direct && onDiskVersion == currentVersion) {
        return SchemaReconcile(SchemaReconcileAction.NONE, onDiskVersion, currentVersion)
    }
    val restore = (decision as? DatabaseCompatibilityDecision.Recover)?.recovery
    val stagedRestore = restore?.let(stageRecovery)
    if (restore != null && stagedRestore == null) {
        throw DatabaseCompatibilityException(
            DatabaseCompatibilityRefusal.DATABASE_CHANGED_AFTER_OBSERVATION,
        )
    }
    // The structure is about to change, the file is about to be replaced, or an older build is about to
    // operate on a newer one. Copy configuration out first, unconditionally: it is a fraction of a
    // percent of the bytes and effectively all of the value, whereas the whole-database snapshot below is
    // deliberately skipped when the file is large or space is short — exactly the conditions under which
    // loss is most likely. It dumps raw rows, so it captures keys this build does not itself understand.
    runCatching { vaultConfig(target) }
    if (decision is DatabaseCompatibilityDecision.Direct && onDiskVersion < currentVersion) {
        // Upgrade imminent: snapshot the pre-migration structure so a later downgrade can roll back.
        // Space-aware and best-effort — an upgrade must never fail for lack of room to archive a copy.
        val backedUp = writePreMigrationBackup(
            target,
            onDiskVersion,
            keepBackups,
            maxBackupBytes,
            freeSpace,
            checkpoint,
        )
        return SchemaReconcile(
            if (backedUp) SchemaReconcileAction.BACKED_UP else SchemaReconcileAction.NONE,
            onDiskVersion,
            currentVersion,
        )
    }
    try {
        restore ?: throw IllegalStateException("non-direct decision requires recovery")
        when (val restored = restoreTransaction.restore(
            staged = checkNotNull(stagedRestore),
            sourceSchema = onDiskVersion,
            stagedSchema = checkNotNull(restore.namedSchema),
            checkpoint = checkpoint,
        )) {
            DatabaseRestoreResult.Absent -> throw IllegalStateException("database restore did not start")
            is DatabaseRestoreResult.Hold -> throw DatabaseRestoreHoldException(restored.reason)
            is DatabaseRestoreResult.Restored -> return restored.reconcile
        }
    } finally {
        if (stagedRestore != null && stagedRestore != restore?.file) runCatching { stagedRestore.delete() }
    }
}

private data class VersionedFile(val file: File, val version: Int)

private fun listVersioned(target: File, suffix: String): List<VersionedFile> {
    val dir = target.parentFile ?: return emptyList()
    val pattern = Regex("^" + Regex.escape(target.name) + "\\.v(\\d+)\\." + suffix + "$")
    return (dir.listFiles() ?: emptyArray()).mapNotNull { file ->
        pattern.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()?.let { VersionedFile(file, it) }
    }
}

private fun pruneVersioned(target: File, suffix: String, keep: Int) {
    listVersioned(target, suffix).sortedByDescending { it.version }.drop(keep.coerceAtLeast(0))
        .forEach { runCatching { it.file.delete() } }
}

private fun writePreMigrationBackup(
    target: File,
    version: Int,
    keepBackups: Int,
    maxBytes: Long,
    freeSpace: (File) -> Long,
    checkpoint: (File) -> Boolean,
): Boolean {
    return runCatching {
        if (!target.isFile || !checkpoint(target)) return false
        val size = target.length()
        if (size > maxBytes) return false
        val dir = target.parentFile ?: return false
        // Retain at most keepBackups-1 existing snapshots so the new copy stays within budget, then drop
        // further snapshots while disk space is tight. An upgrade must never fail for lack of room to
        // archive a rollback copy: a reduced or absent backup is acceptable, a failed upgrade is not.
        var keepExisting = (keepBackups - 1).coerceAtLeast(0)
        pruneVersioned(target, PREMIGRATE_SUFFIX, keepExisting)
        while (freeSpace(dir) < size + SPACE_MARGIN_BYTES && keepExisting > 0) {
            keepExisting--
            pruneVersioned(target, PREMIGRATE_SUFFIX, keepExisting)
        }
        if (freeSpace(dir) < size + SPACE_MARGIN_BYTES) return false // no room even after pruning — skip
        val dest = preMigrationBackupFile(target, version)
        val tmp = File(dest.path + ".tmp")
        target.copyTo(tmp, overwrite = true)
        val installed = tmp.length() == size && tmp.renameTo(dest) && dest.isFile && dest.length() == size
        if (!installed) tmp.delete()
        installed
    }.getOrElse {
        runCatching { File(preMigrationBackupFile(target, version).path + ".tmp").delete() }
        false
    }
}

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

internal enum class RollupPressureTier {
    HOURLY_DETAIL,
    DAY_SUMMARY,
    HOUR_SUMMARY,
    DROP_DAY_HISTORY,
    DROP_HOUR_HISTORY,
}

internal data class RollupRewriteWindow(
    val whereSql: String,
    val whereArgs: Array<String>,
    val targetMinute: Long? = null,
    val drop: Boolean = false,
)

/** Deterministic degradation order for derived telemetry. Recent precision is surrendered before a
 * longer window is discarded, and 1-minute evidence is the last tier retained. */
internal object RollupRetentionPolicy {
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60L * MINUTE_MS

    fun nextTier(observedBytes: Long, softLimitBytes: Long, appliedTiers: Int): RollupPressureTier? =
        if (observedBytes > softLimitBytes) RollupPressureTier.entries.getOrNull(appliedTiers) else null

    fun window(tier: RollupPressureTier, now: Long): RollupRewriteWindow {
        val oneMinute = ((now - MINUTE_MS) / MINUTE_MS).coerceAtLeast(0L)
        val oneHour = ((now - HOUR_MS) / MINUTE_MS).coerceAtLeast(0L)
        return when (tier) {
            RollupPressureTier.HOURLY_DETAIL -> RollupRewriteWindow("minute<?", arrayOf(oneHour.toString()))
            RollupPressureTier.DAY_SUMMARY -> RollupRewriteWindow(
                "minute<?", arrayOf(oneHour.toString()), targetMinute = (oneHour - 1L).coerceAtLeast(0L),
            )
            RollupPressureTier.HOUR_SUMMARY -> RollupRewriteWindow(
                "minute>=? AND minute<?", arrayOf(oneHour.toString(), oneMinute.toString()),
                targetMinute = (oneMinute - 1L).coerceAtLeast(0L),
            )
            RollupPressureTier.DROP_DAY_HISTORY -> RollupRewriteWindow(
                "minute<?", arrayOf(oneHour.toString()), drop = true,
            )
            RollupPressureTier.DROP_HOUR_HISTORY -> RollupRewriteWindow(
                "minute<?", arrayOf(oneMinute.toString()), drop = true,
            )
        }
    }
}

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

/** Wall-clock maintenance admission. Clock rollback is treated as due rather than suppressing work. */
internal class MaintenanceIntervalGate(private val intervalMs: Long) {
    init { require(intervalMs > 0L) }

    private var lastAdmittedAt = Long.MIN_VALUE

    @Synchronized fun admit(now: Long): Boolean {
        if (lastAdmittedAt != Long.MIN_VALUE && now >= lastAdmittedAt && now - lastAdmittedAt < intervalMs) {
            return false
        }
        lastAdmittedAt = now
        return true
    }
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

/**
 * The additive-only rule, made executable.
 *
 * Every schema change creates a future downgrade event, and a downgrade that an older build cannot
 * tolerate costs the owner their configuration. Additive changes are tolerable: an older build simply
 * ignores a table or column it has never heard of. Two shapes are not, and both are silent — nothing
 * fails at the moment the change is written, only years later on somebody's panel:
 *
 * - **Removing or retyping** anything an older build still reads.
 * - **Adding a `NOT NULL` column without a default**, which leaves an older build's inserts — written
 *   against the columns it knows — failing against a constraint it cannot satisfy.
 *
 * Keeping this as a comment in the reconcile path was not enough; it is a rule about SQL, so it is
 * checked against the SQL. Pure and unit-tested, so it runs on every build rather than only on a panel.
 */
internal object SchemaAdditivePolicy {
    private val FORBIDDEN = listOf(
        Regex("""\bDROP\s+TABLE\b""") to "drops a table",
        Regex("""\bDROP\s+COLUMN\b""") to "drops a column",
        Regex("""\bRENAME\s+TO\b""") to "renames a table",
        Regex("""\bRENAME\s+COLUMN\b""") to "renames a column",
        Regex("""\bALTER\s+COLUMN\b""") to "retypes a column",
    )

    /** Human-readable reasons [sql] is not additive; empty when it is safe for an older build. */
    fun violations(sql: String): List<String> {
        val normalized = sql.uppercase().replace(Regex("""\s+"""), " ")
        return buildList {
            FORBIDDEN.forEach { (pattern, reason) -> if (pattern.containsMatchIn(normalized)) add(reason) }
            if (normalized.contains("ADD COLUMN") &&
                normalized.contains("NOT NULL") &&
                !normalized.contains("DEFAULT")
            ) {
                add("adds a NOT NULL column with no default, so an older build's inserts would fail")
            }
        }
    }

    fun violations(statements: List<String>): List<String> = statements.flatMap { statement ->
        violations(statement).map { "$it: ${statement.trim().take(80)}" }
    }
}

/** Sequential forward-only schema contract. Never add an ad-hoc conditional to [onUpgrade]. */
object EntityCatalogSchema {
    const val CURRENT_VERSION = 14

    /**
     * Oldest on-disk structure this build can carry forward — the schema public v0.9.5 shipped.
     *
     * Steps below this are deliberately deleted rather than kept forever: each one is dead weight that
     * still has to be read, tested and reasoned about, and none can run because no supported release
     * produces such a database. Anything older is refused before the database is opened; it must never
     * reach [plan], because a throw inside `onUpgrade` aborts the open and takes configuration down.
     *
     * Raising this floor is a compatibility decision, not a cleanup: it makes upgrading from an older
     * release impossible, so it belongs with a release that states the supported upgrade range.
     */
    const val MINIMUM_SUPPORTED_VERSION = 11

    /** Candidate metadata and runtime admission share this one finite compatibility boundary. */
    internal val DATABASE_COMPATIBILITY = DatabaseCompatibilityBoundary(
        formatVersion = DatabaseCompatibilityBoundary.FORMAT_VERSION,
        databaseName = DatabaseCompatibilityBoundary.DATABASE_NAME,
        minimumSchema = MINIMUM_SUPPORTED_VERSION,
        maximumSchema = CURRENT_VERSION,
    )

    /**
     * [transform] carries data that SQL alone cannot express — an encoding, for instance. It runs after
     * [sql], inside the same transaction, so a failure leaves the step as if it had never run. Prefer
     * plain [sql]; this exists so such a migration stays a declared step rather than becoming the ad-hoc
     * conditional in `onUpgrade` that this contract forbids.
     */
    data class Step(
        val from: Int,
        val to: Int,
        val sql: List<String>,
        val transform: ((SQLiteDatabase) -> Unit)? = null,
        /**
         * Declares that this step drops, renames or retypes something an older build reads.
         *
         * Such a change is allowed — the forward migration chain handles it on upgrade, which is the
         * common direction — but it cannot be *silent*, because the one thing the chain cannot do is run
         * backwards. The finite compatibility boundary ensures an older candidate never assumes that
         * a structure written by a future build is additive.
         */
        val breaksCompatibility: Boolean = false,
    )

    private val steps = mapOf(
        11 to Step(
            11, 12,
            listOf("ALTER TABLE dashboard ADD COLUMN analyzer_policy_version INTEGER NOT NULL DEFAULT 0"),
        ),
        12 to Step(
            12, 13,
            listOf(
                EntityCatalogStore.DASHBOARD_METRIC_TABLE_SQL,
                "CREATE INDEX ix_dashboard_metric_minute_age ON dashboard_metric_minute(minute)",
            ),
            // Carries existing history into payloads. Additive: dashboard_performance is left in place so
            // an older build still opens this database; only its rows move, costing such a build recent
            // diagnostic history rather than anything it cannot rebuild.
            transform = EntityCatalogStore::migrateDashboardPerformanceToPayloads,
        ),
        13 to Step(
            13, 14,
            listOf(
                // Superseded by dashboard_metric_minute and already emptied by the previous step.
                "DROP TABLE IF EXISTS dashboard_performance",
            ),
            // Renaming a column needs SQLite 3.25 (API 30) and minSdk is 26, so every table whose
            // columns change is rebuilt and copied instead. Ordered so `dashboard` -- a foreign-key
            // parent with ON DELETE CASCADE -- is never dropped while a child still references it:
            // DROP TABLE fires that cascade, and PRAGMA foreign_keys cannot be turned off inside the
            // transaction onUpgrade already holds.
            transform = EntityCatalogStore::rebuildTablesWithFinalNames,
            breaksCompatibility = true,
        ),
    )

    fun plan(oldVersion: Int, newVersion: Int): List<Step> {
        require(oldVersion >= MINIMUM_SUPPORTED_VERSION) { "schema version below the supported floor" }
        require(newVersion >= oldVersion) { "schema downgrade is unsupported" }
        val out = mutableListOf<Step>()
        var current = oldVersion
        while (current < newVersion) {
            val step = steps[current] ?: error("missing entity catalog migration $current -> ${current + 1}")
            require(step.to == current + 1) { "non-sequential entity catalog migration" }
            out += step; current = step.to
        }
        return out
    }
}

/** Keeps derived issue diagnostics small even when a dashboard or parser produces hostile input. */
internal object EntityCatalogIssuePersistence {
    const val MAX_ISSUE_GROUPS = 64
    const val MAX_SOURCES_PER_GROUP = 8
    /**
     * The prior 64 KiB bound plus room for one additive presentation code and typed synthetic-title
     * markers on every retained issue. Empty presentation parameters are deliberately absent and
     * require a fresh budget decision.
     */
    const val MAX_PAYLOAD_BYTES = 72 * 1024
    const val MAX_PRESENTATION_CODE_BYTES = 32
    const val MAX_VIEW_TITLE_INDEX = 10_000
    private val PRESENTATION_CODE = Regex("[a-z0-9][a-z0-9_-]{0,31}")
    private const val MAX_ARRAY_ITEMS = 16
    private const val MAX_OBJECT_KEYS = 24
    private const val MAX_KEY_CHARS = 100
    private const val MAX_STRING_CHARS = 500
    private const val MAX_DEPTH = 4

    fun boundedJson(issues: List<JSONObject>): String {
        val out = JSONArray()
        val buckets = Array(4) { ArrayList<JSONObject>(MAX_ISSUE_GROUPS) }
        for (issue in issues) {
            val bucket = buckets[issuePriority(issue)]
            if (bucket.size < MAX_ISSUE_GROUPS) bucket += issue
        }
        for (bucket in buckets) {
            for (issue in bucket) {
                if (out.length() >= MAX_ISSUE_GROUPS) return out.toString()
                val sanitized = sanitizeObject(issue, 0)
                out.put(sanitized)
                if (out.toString().toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
                    out.remove(out.length() - 1)
                }
            }
        }
        return out.toString()
    }

    fun boundExistingJson(raw: String): String = runCatching {
        val array = JSONArray(raw)
        boundedJson(buildList {
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add)
        })
    }.getOrDefault("[]")

    fun counts(raw: String): Pair<Int, Int> = runCatching {
        val array = JSONArray(boundExistingJson(raw))
        var blocking = 0
        for (index in 0 until array.length()) {
            if (array.optJSONObject(index)?.optBoolean("blocking", false) == true) blocking++
        }
        array.length() to blocking
    }.getOrDefault(0 to 0)

    fun ignoredCount(raw: String): Int = runCatching {
        val array = JSONArray(boundExistingJson(raw))
        (0 until array.length()).count { array.optJSONObject(it)?.optBoolean("ignored", false) == true }
    }.getOrDefault(0)

    fun canIgnore(issue: JSONObject): Boolean {
        val wouldBlock = issue.optBoolean("would_block", issue.optBoolean("blocking", false))
        return wouldBlock && issue.optBoolean("ignorable", true)
    }

    fun applyIgnores(issues: JSONArray, ignoredFingerprints: Set<String>): String {
        val effective = buildList {
            for (index in 0 until issues.length()) issues.optJSONObject(index)?.let { source ->
                val issue = JSONObject(source.toString())
                val wouldBlock = issue.optBoolean("would_block", issue.optBoolean("blocking", false))
                val ignored = canIgnore(issue) && issue.optString("fingerprint") in ignoredFingerprints
                issue.put("ignored", ignored)
                issue.put("would_block", wouldBlock)
                issue.put("blocking", wouldBlock && !ignored)
                if (wouldBlock) issue.put("severity", if (ignored) "warning" else "error")
                add(issue)
            }
        }
        return boundedJson(effective)
    }

    private fun issuePriority(issue: JSONObject): Int = when {
        issue.optString("type") == "diagnostic_limit" -> 0
        issue.optBoolean("blocking", false) -> 1
        issue.optBoolean("would_block", false) -> 2
        else -> 3
    }

    private fun sanitizeObject(input: JSONObject, depth: Int): JSONObject = JSONObject().apply {
        if (depth >= MAX_DEPTH) return@apply
        input.keys().asSequence().toList().sorted()
            .filterNot { it == "presentation_params" }
            .take(MAX_OBJECT_KEYS)
            .forEach { rawKey ->
            val key = rawKey.take(MAX_KEY_CHARS)
            sanitizeValue(input.opt(rawKey), depth + 1, key)?.let { put(key, it) }
        }
    }

    private fun sanitizeValue(value: Any?, depth: Int, key: String): Any? = when {
        value == null || value === JSONObject.NULL -> JSONObject.NULL
        value is String && key == "presentation_code" -> value.takeIf(::isValidPresentationCode)
        key == "presentation_code" -> null
        key == "view_title_index" -> (value as? Number)?.toDouble()?.takeIf {
            it.isFinite() && it % 1.0 == 0.0 && it in 1.0..MAX_VIEW_TITLE_INDEX.toDouble()
        }?.toInt()
        key == "card_title_hacs_kiosk" -> true.takeIf { value == true }
        value is String -> value.take(MAX_STRING_CHARS)
        value is Boolean || value is Number -> value
        depth >= MAX_DEPTH -> null
        value is JSONObject -> sanitizeObject(value, depth)
        value is JSONArray -> JSONArray().apply {
            val limit = if (key == "sources" || key == "source_locations") MAX_SOURCES_PER_GROUP else MAX_ARRAY_ITEMS
            for (index in 0 until minOf(value.length(), limit)) {
                sanitizeValue(value.opt(index), depth + 1, key)?.let { put(it) }
            }
        }
        else -> value.toString().take(MAX_STRING_CHARS)
    }

    internal fun isValidPresentationCode(value: String): Boolean = PRESENTATION_CODE.matches(value)
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
