package io.github.maxlyth.hapaneld.dashboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import io.github.maxlyth.hapaneld.storage.KNOWN_DATABASE_OPERATIONS
import org.junit.Test

class EntityCatalogStorageHealthContractTest {
    private val source by lazy {
        listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityCatalogStore.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityCatalogStore.kt"),
        ).first(File::isFile).readText()
    }

    @Test fun probeUsesTheOpenedDatabaseFilesystemAllowlistedFilesAndCancellableQuickCheck() {
        val probe = functionBody("readStorageHealthObservation")

        assertTrue("StatFs must measure the database SQLite actually opened", "StatFs(database.path)" in probe)
        assertTrue("quick_check must be bounded", "PRAGMA quick_check(1)" in probe)
        assertTrue("quick_check must receive lifecycle cancellation", "null, cancellationSignal" in probe)
        assertTrue("main database size must be measured", "storageKnownFileBytes(database)" in probe)
        assertTrue("WAL must remain a distinct metric", "database.path + \"-wal\"" in probe)
        assertTrue("SHM must be allowlisted", "database.path + \"-shm\"" in probe)
        assertTrue("rollback journal must be allowlisted", "database.path + \"-journal\"" in probe)
        assertFalse("the health probe must never enumerate arbitrary files", "listFiles" in probe)
        assertTrue("filesystem-only failure must preserve SQLite metrics", "runCatching { StatFs(database.path) }.getOrNull()" in probe)

        val boundary = functionBody("storageHealthObservation")
        assertTrue("SQLite read failure must be latched", "recordDatabaseFailure(\"storage-health-read\"" in boundary)
        assertTrue("a BUSY probe read is the app's own concurrency and must be bounded-retried",
            "retry.admitRetry(failure" in boundary)
        assertTrue("cancellation must also stop retrying, not only the probe cursors",
            "cancellationSignal?.isCanceled == true" in boundary)
        assertTrue("cancellation must propagate unlatched",
            "catch (cancelled: OperationCanceledException)" in boundary)
    }

    @Test fun everyDirectWritableDatabaseOwnerHasAnAuditedBoundary() {
        val owners = source.lines().mapIndexedNotNull { index, line ->
            if ("writableDatabase" !in line) return@mapIndexedNotNull null
            val prefix = source.lines().take(index + 1).joinToString("\n")
            Regex("fun\\s+([A-Za-z0-9_]+)\\s*\\(").findAll(prefix).lastOrNull()?.groupValues?.get(1)
        }.toSet()

        assertEquals(
            setOf(
                "markStatus",
                "commitSync",
                "recordAccess",
                "recordMetrics",
                "recordDashboardPerformance",
                "recordAmbientHistory",
                "seedAmbientHistory",
                "resetAmbientHistory",
                "setOverrides",
                "resetEvidence",
                "migrateRouteKeyedRowsToRoot",
                "setIssueIgnored",
                "maintainSoftLimit",
                "pruneExpiredHistory",
                "truncateWal",
                "vacuumDatabase",
                "writeProximityBatch",
                "clearProximityLearning",
                "openRestoredDatabaseOwner",
            ),
            owners,
        )

        val restoredOpen = functionBody("openRestoredDatabaseOwner")
        assertTrue("the restored helper must open only through the process-wide lease",
            "open = ::openRestoredDatabaseOwner" in source)
        assertTrue("the restored helper must publish its per-open state before opening SQLite",
            "restoredOpenPending = !joiningRetainedGuard" in restoredOpen &&
                "writableDatabase" in restoredOpen)
        assertTrue("every restored-open exit must clear its per-open state",
            "finally" in restoredOpen && "restoredOpenPending = false" in restoredOpen &&
                "retainedGuardJoinPending = false" in restoredOpen)

        val operations = listOf(
            "catalog-status",
            "catalog-sync",
            "catalog-access-history",
            "catalog-metric-history",
            "catalog-maintenance",
            "catalog-retention",
            "dashboard-performance-history",
            "ambient-history",
            "ambient-history-seed",
            "ambient-history-reset",
            "catalog-overrides",
            "catalog-reset",
            "catalog-scope-migration",
            "catalog-issue-override",
            "proximity-history",
            "proximity-history-reset",
        )
        operations.forEach { operation ->
            assertTrue("missing observed boundary for $operation", "observedWrite(\"$operation\"" in source)
        }
        assertTrue("single-row override must use the observed batch owner",
            "setOverrides(instance, path, listOf(entityId), override)" in functionBody("setOverride"))
        assertTrue("proximity writes must still run maintenance after their commit",
            "observedMaintenance(now)" in functionBody("writeProximityBatch"))
        val issueWrite = functionBody("setIssueIgnored")
        assertTrue("a committed issue override must report write recovery",
            "reportsSuccessfulWrite = { it }" in issueWrite)
        assertTrue("benign issue-override exits must pass through the observed boundary",
            "return@observedWrite false" in issueWrite)
    }

    @Test fun expectedInternalBusyIsBoundedRetriedOnlyWhereItIsExpected() {
        // The write boundary retries BUSY within one bounded run, then latches the ORIGINAL failure
        // through the unchanged path. Non-BUSY kinds never retry (DatabaseBusyRetry refuses them).
        val write = functionBody("observedWrite")
        assertTrue("the write boundary must begin one bounded retry run per operation",
            "busyRetry.begin()" in write)
        assertTrue("a refused retry must latch and rethrow the original failure",
            "retry.admitRetry(failure" in write &&
                "recordDatabaseFailure(operation, failure)" in write &&
                "throw failure" in write)
        assertTrue("an in-flight retry must observe the closing owner",
            "::isBusyRetryAbandoned" in write)
        assertTrue("close must abandon in-flight retries before closing the helper",
            "busyRetryAbandoned = true" in functionBody("close"))

        // Schema and pre-open safety nets keep latching immediately: BUSY there is not expected
        // self-contention, and failing closed is their purpose.
        assertFalse("schema writes must not retry", "admitRetry" in functionBody("observedSchemaWrite"))
        assertFalse("pre-open safety-net failures must not retry",
            "admitRetry" in functionBody("retainDatabaseFailure"))

        // Config writes ride a separate pool over the same file and are equal BUSY victims.
        val appState = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/persistence/AppState.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/persistence/AppState.kt"),
        ).first(File::isFile).readText()
        val stateWrite = appState.substring(
            appState.indexOf("private fun writeTransaction"),
            appState.indexOf("private fun insertRevision"),
        )
        assertTrue("app_state writes must bounded-retry BUSY before latching",
            "retry.admitRetry(failure" in stateWrite && "helper::isBusyRetryAbandoned" in stateWrite)
        assertTrue("app_state must still latch the original failure on refusal",
            "recordDatabaseFailure(\"app_state:\$namespace\", failure)" in stateWrite)
        assertTrue("app_state must retry only SQLException failures",
            "failure is SQLException" in stateWrite)
    }

    @Test fun maintenanceIsItsOwnObservedBoundaryOutsideEveryCallerTransaction() {
        // Maintenance is the main BUSY source and a routine BUSY victim. It must not run inside a
        // caller's observed block: its failure would latch under the caller's operation name and
        // rethrow after that caller's transaction committed, telling the telemetry flusher a durable
        // write failed when it succeeded (Issue #91 made it drop an already-written batch).
        assertTrue("maintenance must have its own observed boundary and truthful success reporting",
            "observedWrite(\"catalog-maintenance\", reportsSuccessfulWrite = { it }) { maintainSoftLimit(now) }" in source)
        assertEquals(
            "maintainSoftLimit may be invoked only by its declaration and the observedMaintenance boundary",
            2,
            Regex("maintainSoftLimit\\(").findAll(source).count(),
        )
        assertTrue("maintenance failures must not propagate to callers whose write committed",
            "runCatching {" in functionBody("observedMaintenance"))
        listOf("commitSync", "recordAccess", "recordMetrics").forEach { caller ->
            assertTrue("$caller must run maintenance after its observed write completes",
                ".also { observedMaintenance(now) }" in functionBody(caller))
        }
        // The interval gate is consumed OUTSIDE the retried operation: an admission spent by an
        // attempt that then failed BUSY must not make the retry re-run into a refusing gate, which
        // would end the pass with neither the work retried nor the failure latched.
        val boundary = functionBody("observedMaintenance")
        val gateAt = boundary.indexOf("if (!maintenanceGate.admit(now)) return")
        assertTrue("the gate must be consumed in observedMaintenance", gateAt >= 0)
        assertTrue("the gate must be consumed BEFORE the retried observed write begins",
            gateAt < boundary.indexOf("observedWrite(\"catalog-maintenance\""))
        assertFalse("maintainSoftLimit must not consult the gate inside the retried operation",
            "maintenanceGate" in functionBody("maintainSoftLimit"))
    }

    @Test fun maintenanceHoldsTheWriteLockOnlyInBoundedChunks() {
        // One unbounded DELETE under FULL auto-vacuum held the write lock ≥18 s and latched a false
        // BUSY storage failure (Issue #91). Every purge statement is now a bounded rowid chunk.
        val maintain = functionBody("maintainSoftLimit")
        assertFalse("no unchunked DELETE may remain in maintenance", "execSQL(\"DELETE" in maintain)
        assertFalse("no unchunked UPDATE may remain in maintenance", "execSQL(\n" in maintain)
        assertTrue("purge statements must be bounded rowid chunks",
            "LIMIT \$MAINTENANCE_CHUNK_ROWS" in maintain)
        val chunk = functionBody("chunkedWrite")
        assertTrue("each chunk must be its own statement so the lock releases between chunks",
            "executeUpdateDelete()" in chunk)
        assertTrue("the chunk loop must stop when the candidate set is exhausted",
            "if (changed < MAINTENANCE_CHUNK_ROWS) return any" in chunk)
    }

    @Test fun autoVacuumIsFlippedOnlyFromFullAndReclaimedInBoundedSlices() {
        val ensure = functionBody("ensureIncrementalAutoVacuum")
        assertTrue("FULL must flip to INCREMENTAL (cheap header change)",
            "AUTO_VACUUM_FULL ->" in ensure && "PRAGMA auto_vacuum=INCREMENTAL" in ensure)
        assertTrue("the flip must be verified, not assumed",
            "primaryConnectionMode() != AUTO_VACUUM_INCREMENTAL" in ensure)
        assertTrue("a conversion that did not take must stop the pass and latch, not purge under FULL",
            "throw SQLException(\"auto_vacuum incremental conversion did not persist\")" in ensure)
        assertTrue("the verification read must use the flip's primary connection, not a pooled read snapshot",
            "beginTransaction()" in ensure)
        assertTrue("NONE must be left alone — enabling auto-vacuum needs a full VACUUM",
            "else -> false" in ensure)
        // A full VACUUM must never run implicitly — its space demand can worsen a low-space incident.
        // Its one site is the remediation step, which nothing in the store calls: only the service's
        // remediation ladder reaches it, after fullVacuumAdmission (StorageRemediationTest).
        assertEquals("exactly one full VACUUM site", 1, Regex("execSQL\\(\"VACUUM").findAll(source).count())
        assertTrue("the full VACUUM lives only in the remediation step",
            "execSQL(\"VACUUM\")" in functionBody("vacuumDatabase"))
        assertEquals("nothing in the store invokes the rebuild", 1, Regex("vacuumDatabase\\(").findAll(source).count())
        val vacuum = functionBody("incrementalVacuumStep")
        assertTrue("reclamation must be sliced and capped per pass",
            "MAX_VACUUM_PAGES_PER_PASS" in vacuum && "PRAGMA incremental_vacuum(\$slice)" in vacuum)
        assertTrue("a small freelist must be retained for ordinary reuse",
            "FREELIST_RETAINED_PAGES" in vacuum)
        // incremental_vacuum yields one row per freed page. Through execSQL, Android's
        // executeNonQuery threw on the first SQLITE_ROW, which classified UNKNOWN, took zero retries
        // and latched database_failure on every pass that had anything to reclaim (Issue #91).
        assertFalse("incremental_vacuum must never run through execSQL — it returns one row per page",
            "execSQL(\"PRAGMA incremental_vacuum" in source)
        assertTrue("the pragma must be stepped to exhaustion through rawQuery",
            "rawQuery(\"PRAGMA incremental_vacuum(\$slice)\", null)" in vacuum &&
                "cursor.count.toLong()" in vacuum)
        assertTrue("a short slice means SQLite ran out of reclaimable pages and the loop must stop",
            "if (sliceFreedPages < slice) break" in vacuum)
        assertTrue("the pass must be refused when the filesystem cannot afford its transient cost",
            "reclamationHasHeadroom(" in vacuum)
        val headroom = functionBody("reclamationHasHeadroom")
        assertTrue("the admission decision must be the shared reviewable policy, not a local rule",
            "reclamationAdmitted(" in headroom)
        assertTrue("the policy must be given this pass's own slice budget and margin",
            "MAX_VACUUM_PAGES_PER_PASS" in headroom && "RECLAMATION_HEADROOM_MARGIN_BYTES" in headroom)
        assertTrue("the policy must see live severity, not a cached one",
            "StorageHealthRuntime.snapshot().severity" in headroom)
        // The pragma alone moves page_count but leaves the file at its old length in WAL mode.
        val reclaimed = functionBody("checkpointReclaimedBytes")
        assertTrue("reclaimed pages must be checkpointed out of the WAL or no bytes come back",
            "PRAGMA wal_checkpoint(PASSIVE)" in reclaimed)
        assertTrue("returned bytes must be measured from the file, never inferred from the result row",
            "storageKnownFileBytes(databaseFile)" in reclaimed &&
                "(bytesBefore - bytesAfter).coerceAtLeast(0L)" in reclaimed)
        // PASSIVE reports contention through its busy flag rather than by throwing, so anything it
        // throws is IO or corruption and must reach the maintenance boundary rather than be swallowed.
        assertFalse("a throwing checkpoint must not be silently swallowed",
            "runCatching" in reclaimed)
        // A pool closed mid-pass makes the next query throw IllegalStateException, which no boundary
        // classifies and `observedMaintenance`'s runCatching swallows — a shutdown would read as a
        // clean pass that did nothing. So closure is checked before EVERY later database access.
        assertTrue("closure must be checked before the freelist read that gates the loop",
            "if (abandoned() || freelist() <= FREELIST_RETAINED_PAGES) break" in vacuum)
        assertTrue("closure must be checked before the pass reads anything at all",
            vacuum.indexOf("if (abandoned()) return false") < vacuum.indexOf("PRAGMA page_size"))
        assertTrue("closure must stop the pass before the checkpoint, which writes too",
            "if (abandoned()) return freedPages > 0L" in vacuum)
        assertTrue("a shutdown must not fire the no-progress warning",
            "if (freedPages == 0L && !abandoned())" in vacuum)
        // Stepping via `count` fills the window once; a moveToNext walk could re-execute the pragma
        // on a window refill and free another slice beyond the cap.
        // The whole count, unaltered. A mutation that clamped it to one page per slice survived an
        // earlier battery round: nothing asserted that the pages credited are the pages freed, so
        // reclamation could have crawled at one page a pass exactly as the original defect did.
        assertTrue("the slice must credit the full cursor count, unclamped",
            ".use { cursor -> cursor.count.toLong() }" in vacuum &&
                "while (cursor.moveToNext())" !in vacuum)
        assertTrue("and that count must be what accumulates against the per-pass cap",
            "freedPages += sliceFreedPages" in vacuum)
        // A reader-blocked checkpoint leaves the freelist at its floor, so only the pending flag can
        // bring a later pass back to the checkpoint that returns those bytes.
        assertTrue("a deferred checkpoint must be retried on a later pass",
            "reclamationPassWanted(" in vacuum && "walReclamationStillPending(" in vacuum)
        assertTrue("a full filesystem must be measured, not collapsed into an absent probe",
            "statFs?.let {" in headroom && "?: 0L" !in headroom)
    }

    @Test fun everyProductionOperationLabelIsInTheClosedVocabulary() {
        // `catalog-scope-migration` and `database-downgrade-tripwire` were live labels that were
        // never set members, so they sanitized to the generic `database` — a failure during a scope
        // migration reported "during database", which is precisely the "names nothing anyone can act
        // on" outcome this work exists to remove. Enumerating them by hand is what let that happen,
        // so the enumeration is derived from the source instead.
        val labels = Regex("""observed(?:Write|SchemaWrite)\("([a-z0-9:<>_-]+)"""")
            .findAll(source).map { it.groupValues[1] }
            .plus(Regex("""retainDatabaseFailure\("([a-z0-9:<>_-]+)"""")
                .findAll(source).map { it.groupValues[1] })
            .toSet()
        assertTrue("the scan must actually find the operation labels", labels.size >= 15)
        val missing = labels
            .filterNot { it.startsWith("app_state") }
            .filterNot { it in KNOWN_DATABASE_OPERATIONS }
        assertTrue(
            "operation labels outside the closed vocabulary render as the generic name: $missing",
            missing.isEmpty(),
        )
    }

    @Test fun schemaAndPreopenFailuresCannotDisappear() {
        assertTrue("onCreate must have a schema failure boundary",
            "observedSchemaWrite(\"database-create\")" in functionBody("onCreate"))
        assertTrue("onUpgrade must have a schema failure boundary",
            "observedSchemaWrite(\"database-upgrade\")" in functionBody("onUpgrade"))
        listOf(
            "database-preopen-reconcile",
            "database-version-read",
            "database-vault-read",
            "database-vault-restore",
        ).forEach { operation ->
            assertTrue("missing pre-open failure operation $operation", "retainDatabaseFailure(\"$operation\"" in source)
        }
        val checkpoint = functionBody("checkpointDatabaseFile")
        assertTrue("checkpoint exceptions must reach the shared failure authority",
            "recordDatabaseFailure(\"database-checkpoint\", failure)" in checkpoint)
        assertTrue("a busy checkpoint result must also be truthful",
            "recordDatabaseFailure(" in checkpoint && "\"database-checkpoint\"" in checkpoint)
        assertTrue("the checkpoint's own raw connection is a routine BUSY victim and must bounded-retry",
            "retry.admitRetry(busy)" in checkpoint && "retry.admitRetry(failure)" in checkpoint)
        assertTrue("API-27 checkpoint raw open must stay in WAL mode",
            "SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING" in checkpoint)
    }

    @Test fun sqliteDowngradeCallbackIsAFailClosedMutationFreeTripwire() {
        val downgrade = functionBody("onDowngrade")
        assertTrue(
            "a post-observation downgrade race must cross the schema failure authority",
            "observedSchemaWrite(\"database-downgrade-tripwire\")" in downgrade,
        )
        assertTrue(
            "the callback must refuse rather than let SQLiteOpenHelper version-stamp the newer store",
            "throw DatabaseCompatibilityException(" in downgrade &&
                "DATABASE_CHANGED_AFTER_OBSERVATION" in downgrade,
        )
        assertFalse("the tripwire must not mutate the database", Regex("\\bdb\\.").containsMatchIn(downgrade))
    }

    @Test fun managerForwardsTheCancellationSignalWithoutOwningAnotherProbe() {
        val manager = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityLearningManager.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityLearningManager.kt"),
        ).first(File::isFile).readText()
        val start = manager.indexOf("fun storageHealthObservation")
        val forwarder = manager.substring(start, manager.indexOf('\n', manager.indexOf("store.storageHealthObservation", start)))

        assertTrue("CancellationSignal?" in forwarder)
        assertTrue("store.storageHealthObservation(cancellationSignal)" in forwarder)
        assertFalse("PRAGMA" in forwarder)
    }

    @Test fun remediationDatabaseStepsReportWhatHappenedAndLatchOnlyRealFaults() {
        val truncate = functionBody("truncateWal")
        assertTrue("only TRUNCATE shrinks the WAL file", "PRAGMA wal_checkpoint(TRUNCATE)" in truncate)
        assertTrue("the result row and the measured WAL decide the outcome",
            "interpretWalCheckpoint(" in truncate && "storageKnownFileBytes(wal)" in truncate)
        assertTrue("a BUSY throw is this app's own contention and is deferred",
            "StorageDatabaseFailureKind.BUSY" in truncate && "WalCheckpointResult.DEFERRED_BUSY" in truncate)
        assertTrue("any other checkpoint throw latches under its own operation",
            "recordDatabaseFailure(\"database-wal-truncate\", failure)" in truncate &&
                "WalCheckpointResult.FAILED" in truncate)

        val vacuum = functionBody("vacuumDatabase")
        assertTrue("a closing owner is refused before any rebuild", "isBusyRetryAbandoned()" in vacuum &&
            vacuum.indexOf("isBusyRetryAbandoned()") < vacuum.indexOf("VACUUM\")"))
        assertTrue("NONE converts to INCREMENTAL inside the rebuild, and only then",
            vacuum.indexOf("PRAGMA auto_vacuum=INCREMENTAL") in 0 until vacuum.indexOf("db.execSQL(\"VACUUM\")"))
        assertTrue("a rebuild failure latches under its own operation",
            "recordDatabaseFailure(\"database-vacuum\", failure)" in vacuum)
        assertFalse("the store never deletes or recreates the database file",
            listOf("deleteDatabase", ".delete()", "renameTo").any { it in vacuum || it in truncate })

        val retention = functionBody("pruneExpiredHistory")
        assertTrue("retention runs exactly the shared bounded statements",
            "historyRetentionStatements(now, MAINTENANCE_CHUNK_ROWS)" in retention && "chunkedWrite(" in retention)
        assertTrue("retention runs inside its observed boundary and then bounded reclamation",
            "observedWrite(\"catalog-retention\"" in functionBody("enforceRetention") &&
                "observedMaintenance(now)" in functionBody("enforceRetention"))

        val backup = functionBody("writeVerifiedConfigurationBackup")
        assertTrue("the backup is read back from the file just written",
            "ConfigVault.decode(written.readText())" in backup &&
                "configurationBackupVerified(rows, readBack?.rows)" in backup &&
                "readBack?.profiles == profiles" in backup)
    }

    private fun functionBody(name: String): String {
        val function = Regex("(?m)^([ \\t]*)(?:(?:private|internal|override|protected|inline) )*fun\\s+(?:<[^>]+>\\s+)?$name\\s*\\(")
            .find(source) ?: error("missing function $name")
        val indent = function.groupValues[1]
        val next = Regex("\\n$indent(?:(?:private|internal|override|protected|inline) )*fun\\s+")
            .find(source, function.range.last + 1)?.range?.first ?: source.length
        return source.substring(function.range.first, next)
    }
}
