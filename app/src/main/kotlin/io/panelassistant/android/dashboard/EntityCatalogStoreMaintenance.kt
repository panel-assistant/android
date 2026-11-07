package io.panelassistant.android.dashboard

import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.StatFs
import android.util.Log
import io.panelassistant.android.dashboard.EntityCatalogStore.Companion.RUNTIME_RETENTION_MS
import io.panelassistant.android.dashboard.EntityCatalogStore.Companion.knownDatabaseFootprint
import io.panelassistant.android.dashboard.EntityCatalogStore.DatabaseUsage
import io.panelassistant.android.metrics.FeatureCostOperation
import io.panelassistant.android.metrics.FeatureCostOutcome
import io.panelassistant.android.metrics.FeatureCosts
import io.panelassistant.android.persistence.ConfigVault
import io.panelassistant.android.storage.FREELIST_RETAINED_PAGES
import io.panelassistant.android.storage.StorageAutoVacuumMode
import io.panelassistant.android.storage.StorageDatabaseFailureKind
import io.panelassistant.android.storage.StorageHealthObservation
import io.panelassistant.android.storage.StorageHealthRuntime
import io.panelassistant.android.storage.StorageQuickCheck
import io.panelassistant.android.storage.VacuumOutcome
import io.panelassistant.android.storage.VacuumRefusal
import io.panelassistant.android.storage.VacuumResult
import io.panelassistant.android.storage.WalCheckpointOutcome
import io.panelassistant.android.storage.WalCheckpointResult
import io.panelassistant.android.storage.classifyDatabaseFailure
import io.panelassistant.android.storage.configurationBackupVerified
import io.panelassistant.android.storage.fullVacuumAdmission
import io.panelassistant.android.storage.interpretWalCheckpoint
import io.panelassistant.android.storage.reclamationAdmitted
import io.panelassistant.android.storage.reclamationPassWanted
import io.panelassistant.android.storage.walReclamationStillPending
import java.io.File

private const val SOFT_LIMIT_BYTES = 128L * 1024 * 1024
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
/**
 * Free space a reclamation pass needs on top of its own transient cost. Reclamation adds WAL
 * frames for the pages it relocates before the checkpoint gives anything back, so a pass run
 * with no margin can deepen the low-space incident it was meant to relieve.
 */
private const val RECLAMATION_HEADROOM_MARGIN_BYTES = 64L * 1024L * 1024L

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
internal fun EntityCatalogStore.observedMaintenance(now: Long) {
    // The interval gate is consumed OUTSIDE the retried operation. An admission spent by an
    // attempt that then failed BUSY would make the retry re-run into a refusing gate, ending the
    // pass with neither the work retried nor the failure latched — the failure mode this ordering prevents.
    if (!maintenanceGate.admit(now)) return
    runCatching {
        observedWrite("catalog-maintenance", reportsSuccessfulWrite = { it }) { maintainSoftLimit(now) }
    }
}

/** @return true only if a durable write (purge chunk or vacuum step) actually executed. */
private fun EntityCatalogStore.maintainSoftLimit(now: Long): Boolean {
    val span = FeatureCosts.registry.span(FeatureCostOperation.ENTITY_DB_MAINTENANCE)
    var observedBytes = 0L
    var appliedTiers = 0
    var wrote = false
    try {
        val db = writableDatabase
        val incrementalVacuum = ensureIncrementalAutoVacuum(db)
        fun refreshUsage(): Long = measureDatabaseUsage(db).also {
            updateDatabaseUsageCache(it)
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
                arrayOf<Any?>(trafficMinuteCutoff(now)),
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
 * — never run it implicitly. Only [vacuumDatabase], behind [fullVacuumAdmission], converts it.
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

private fun EntityCatalogStore.incrementalVacuumStep(db: SQLiteDatabase): Boolean {
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

/**
 * Applies every age-based history limit now, instead of waiting for the write that normally
 * applies it. Each limit is otherwise enforced only on its own write path — entity traffic and
 * tombstones on a dashboard sync, performance minutes on a performance sample, ambient minutes on an
 * ambient sample, proximity rollups on a proximity batch — so a panel on which one of those writers
 * has gone quiet keeps that history indefinitely. The same cutoff functions serve both paths.
 *
 * Only expired history rows are deleted, in bounded chunks. Configuration, the active entity set,
 * pinned and excluded memberships, learned models and anything not past its limit are untouched.
 * Bounded freelist reclamation then runs through the ordinary maintenance gate.
 *
 * @return true when at least one expired row was removed.
 */
fun EntityCatalogStore.enforceRetention(now: Long = System.currentTimeMillis()): Boolean {
    val pruned = observedWrite("catalog-retention", reportsSuccessfulWrite = { it }) {
        pruneExpiredHistory(now)
    }
    observedMaintenance(now)
    return pruned
}

private fun EntityCatalogStore.pruneExpiredHistory(now: Long): Boolean {
    val db = writableDatabase
    return historyRetentionStatements(now, MAINTENANCE_CHUNK_ROWS).fold(false) { wrote, statement ->
        chunkedWrite(db, statement.sql, arrayOf<Any?>(statement.cutoff)) || wrote
    }
}

/**
 * Checkpoints the whole WAL into the main file and truncates it, reporting what happened.
 *
 * `TRUNCATE` is used because it is the only mode that shrinks the WAL file itself. It waits on
 * the busy handler, so a reader or writer holding the log makes it report `busy=1`: that is this
 * app's own concurrency and is returned as deferred, never latched and never reported as success.
 * A BUSY-classified throw is the same thing. Any other throw is IO or corruption, and latches.
 */
fun EntityCatalogStore.truncateWal(): WalCheckpointOutcome {
    // A closing owner must not start a write it cannot finish; the ladder also re-reads
    // lifecycle ownership immediately before calling this.
    if (isBusyRetryAbandoned()) {
        val wal = storageKnownFileBytes(File(databaseTarget.path + "-wal"))
        return WalCheckpointOutcome(WalCheckpointResult.SKIPPED_LIFECYCLE, wal, wal)
    }
    val db = writableDatabase
    val wal = File(db.path + "-wal")
    val before = storageKnownFileBytes(wal)
    return try {
        val row = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
            if (cursor.moveToFirst() && cursor.columnCount == 3) {
                Triple(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
            } else {
                null
            }
        }
        interpretWalCheckpoint(row?.first, row?.second, row?.third, before, storageKnownFileBytes(wal))
            .also { if (it.result == WalCheckpointResult.COMPLETED) StorageHealthRuntime.recordDatabaseWriteSuccess() }
    } catch (failure: SQLException) {
        if (classifyDatabaseFailure(failure) == StorageDatabaseFailureKind.BUSY) {
            WalCheckpointOutcome(WalCheckpointResult.DEFERRED_BUSY, before, storageKnownFileBytes(wal))
        } else {
            StorageHealthRuntime.recordDatabaseFailure("database-wal-truncate", failure)
            WalCheckpointOutcome(WalCheckpointResult.FAILED, before, storageKnownFileBytes(wal))
        }
    }
}

/**
 * Writes a fresh configuration vault generation from the live `app_state` rows and imported
 * profiles, then reads that exact file back and proves it decodes to the same content. This is the
 * backup a full `VACUUM` requires; it is a tiny text file, so it costs no meaningful space even
 * under pressure. Any failure is simply "no verified backup".
 */
fun EntityCatalogStore.writeVerifiedConfigurationBackup(now: Long = System.currentTimeMillis()): Boolean = runCatching {
    val rows = exportAppState()
    val profiles = EntityCatalogStorePreOpen.vaultedProfiles(appContext)
    val written = ConfigVault.write(
        File(appContext.filesDir, ConfigVault.VAULT_DIRECTORY),
        ConfigVault.Export(rows, profiles),
        now,
    ) ?: return false
    val readBack = ConfigVault.decode(written.readText())
    configurationBackupVerified(rows, readBack?.rows) && readBack?.profiles == profiles
}.getOrDefault(false)

/**
 * Rebuilds the database with a full `VACUUM`. Callers must pass [fullVacuumAdmission] first; this
 * performs no admission of its own beyond refusing a closing owner.
 *
 * A database created with `auto_vacuum=NONE` is switched to INCREMENTAL in the same rebuild, the
 * only moment that switch can happen, so its freelist is reachable by bounded reclamation from then
 * on and a rebuild is never needed again. The WAL is then truncated so the new image reaches the
 * main file. BUSY is deferred, not latched; anything else latches.
 */
fun EntityCatalogStore.vacuumDatabase(): VacuumOutcome {
    if (isBusyRetryAbandoned()) return VacuumOutcome(VacuumResult.REFUSED, VacuumRefusal.LIFECYCLE)
    val db = writableDatabase
    return try {
        val mode = db.rawQuery("PRAGMA auto_vacuum", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        }
        if (mode == AUTO_VACUUM_NONE) db.execSQL("PRAGMA auto_vacuum=INCREMENTAL")
        db.execSQL("VACUUM")
        StorageHealthRuntime.recordDatabaseWriteSuccess()
        VacuumOutcome(VacuumResult.COMPLETED, checkpoint = truncateWal().result)
    } catch (failure: SQLException) {
        if (classifyDatabaseFailure(failure) == StorageDatabaseFailureKind.BUSY) {
            VacuumOutcome(VacuumResult.DEFERRED_BUSY)
        } else {
            StorageHealthRuntime.recordDatabaseFailure("database-vacuum", failure)
            VacuumOutcome(VacuumResult.FAILED)
        }
    }
}

/** Rewrite only derived telemetry rows. Each tier is transactionally replace-or-delete so a
 * process death cannot leave a partially compacted window. */
private fun EntityCatalogStore.applyRollupPressureTier(db: SQLiteDatabase, tier: RollupPressureTier, now: Long) {
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

fun EntityCatalogStore.databaseUsage(): DatabaseUsage = databaseUsageCache.get()

/**
 * Bounded, path-free health evidence for the filesystem containing the database that SQLite
 * actually opened. [cancellationSignal] reaches every PRAGMA cursor, including quick_check(1).
 */
fun EntityCatalogStore.storageHealthObservation(
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
            val abandoned = { isBusyRetryAbandoned() || cancellationSignal?.isCanceled == true }
            if (!retry.admitRetry(failure, abandoned)) {
                StorageHealthRuntime.recordDatabaseFailure("storage-health-read", failure)
                throw failure
            }
        }
    }
}

private fun EntityCatalogStore.readStorageHealthObservation(
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
        sidecarBytes = saturatedAdd(
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

/** Publish only after measurement has closed its cursors and released SQLite transactions.
 * Cached.set may wait for a diagnostic measurement, which acquires SQLite resources itself. */
internal fun EntityCatalogStore.updateDatabaseUsageCache(usage: DatabaseUsage) = databaseUsageCache.set(usage)

internal fun EntityCatalogStore.measureDatabaseUsage(db: SQLiteDatabase = readableDatabase): DatabaseUsage {
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

private fun storageKnownFileBytes(file: File): Long = runCatching {
    file.takeIf(File::isFile)?.length()?.coerceAtLeast(0L) ?: 0L
}.getOrDefault(0L)

private fun storageSaturatedMultiply(left: Long, right: Long): Long = when {
    left == 0L || right == 0L -> 0L
    left > Long.MAX_VALUE / right -> Long.MAX_VALUE
    else -> left * right
}

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
