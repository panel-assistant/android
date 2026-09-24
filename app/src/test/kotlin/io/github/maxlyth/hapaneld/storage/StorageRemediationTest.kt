package io.github.maxlyth.hapaneld.storage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remediation policy: the full-`VACUUM` gate, the truthful WAL checkpoint reading and the ladder
 * that orders the steps and escalates when nothing safe remains.
 */
class StorageRemediationTest {
    private val mib = 1024L * 1024L
    private val pageSize = 4_096L

    /** A NONE-mode database with a large freelist that only a full rebuild can return. */
    private fun candidate(
        pressure: StorageHealthSeverity = StorageHealthSeverity.WARNING,
        severity: StorageHealthSeverity = pressure,
        usableBytes: Long? = 400L * mib,
        pageCount: Long = 5_000L,
        freelistCount: Long = 3_000L,
        autoVacuum: StorageAutoVacuumMode = StorageAutoVacuumMode.NONE,
    ) = VacuumCandidate(
        severity = severity,
        pressureSeverity = pressure,
        usableBytes = usableBytes,
        mainDatabaseBytes = pageCount * pageSize,
        walBytes = mib,
        pageSizeBytes = pageSize,
        pageCount = pageCount,
        freelistCount = freelistCount,
        autoVacuumMode = autoVacuum,
    )

    /** 2 × (main + WAL) + margin for [candidate]'s defaults. */
    private val requiredHeadroom = 2L * (5_000L * pageSize + mib) + 64L * mib

    // ---- full VACUUM admission ----

    @Test fun vacuumIsAdmittedWithHeadroomAWorthwhileFreelistABackupAndOwnership() {
        assertNull(fullVacuumAdmission(candidate(usableBytes = requiredHeadroom), lifecycleOwned = true, backupVerified = true))
    }

    @Test fun vacuumIsRefusedWithoutHeadroom() {
        assertEquals(
            VacuumRefusal.INSUFFICIENT_HEADROOM,
            fullVacuumAdmission(candidate(usableBytes = requiredHeadroom - 1L), lifecycleOwned = true, backupVerified = true),
        )
    }

    @Test fun vacuumIsRefusedWhenCapacityIsUnknown() {
        // Unlike a bounded reclamation slice, a whole-database rewrite must not fail open.
        assertEquals(
            VacuumRefusal.CAPACITY_UNKNOWN,
            fullVacuumAdmission(candidate(usableBytes = null), lifecycleOwned = true, backupVerified = true),
        )
    }

    @Test fun vacuumIsNeverAdmittedAtCriticalPressureWhateverTheHeadroom() {
        assertEquals(
            VacuumRefusal.CRITICAL_PRESSURE,
            fullVacuumAdmission(
                candidate(pressure = StorageHealthSeverity.CRITICAL, usableBytes = Long.MAX_VALUE),
                lifecycleOwned = true,
                backupVerified = true,
            ),
        )
    }

    @Test fun vacuumIsRefusedUnderALatchedDatabaseFailure() {
        assertEquals(
            VacuumRefusal.DATABASE_FAILURE,
            fullVacuumAdmission(
                candidate(severity = StorageHealthSeverity.DATABASE_FAILURE, usableBytes = Long.MAX_VALUE),
                lifecycleOwned = true,
                backupVerified = true,
            ),
        )
    }

    @Test fun vacuumIsRefusedWithoutAVerifiedBackup() {
        assertEquals(
            VacuumRefusal.NO_VERIFIED_BACKUP,
            fullVacuumAdmission(candidate(usableBytes = Long.MAX_VALUE), lifecycleOwned = true, backupVerified = false),
        )
    }

    @Test fun vacuumIsRefusedWithoutLifecycleOwnership() {
        assertEquals(
            VacuumRefusal.LIFECYCLE,
            fullVacuumAdmission(candidate(usableBytes = Long.MAX_VALUE), lifecycleOwned = false, backupVerified = true),
        )
    }

    @Test fun vacuumIsNotNeededWithoutPressure() {
        assertEquals(
            VacuumRefusal.NOT_NEEDED,
            fullVacuumAdmission(
                candidate(pressure = StorageHealthSeverity.HEALTHY, usableBytes = Long.MAX_VALUE),
                lifecycleOwned = true,
                backupVerified = true,
            ),
        )
    }

    @Test fun anIncrementalDatabaseReclaimsThroughTheBoundedPathInstead() {
        listOf(StorageAutoVacuumMode.INCREMENTAL, StorageAutoVacuumMode.FULL).forEach { mode ->
            assertEquals(
                VacuumRefusal.INCREMENTAL_AVAILABLE,
                fullVacuumAdmission(candidate(autoVacuum = mode, usableBytes = Long.MAX_VALUE), true, true),
            )
        }
        assertEquals(
            VacuumRefusal.AUTO_VACUUM_UNKNOWN,
            fullVacuumAdmission(candidate(autoVacuum = StorageAutoVacuumMode.UNKNOWN, usableBytes = Long.MAX_VALUE), true, true),
        )
    }

    @Test fun aSmallFreelistIsNotWorthARebuild() {
        // 24% free, though ~19 MiB of it: the fraction alone refuses.
        assertEquals(
            VacuumRefusal.NOT_WORTHWHILE,
            fullVacuumAdmission(
                candidate(pageCount = 20_000L, freelistCount = 4_800L, usableBytes = Long.MAX_VALUE),
                lifecycleOwned = true,
                backupVerified = true,
                policy = FullVacuumPolicy(maximumLiveBytes = Long.MAX_VALUE),
            ),
        )
        // Half free, but only 4 MiB of it: below the absolute floor.
        assertEquals(
            VacuumRefusal.NOT_WORTHWHILE,
            fullVacuumAdmission(candidate(pageCount = 2_048L, freelistCount = 1_024L, usableBytes = Long.MAX_VALUE), true, true),
        )
    }

    @Test fun aRebuildTooLargeForTheLockBudgetIsRefused() {
        // 30,000 live pages is ~117 MiB of copying under the write lock.
        assertEquals(
            VacuumRefusal.TOO_LARGE,
            fullVacuumAdmission(candidate(pageCount = 60_000L, freelistCount = 30_000L, usableBytes = Long.MAX_VALUE), true, true),
        )
    }

    // ---- WAL checkpoint truth ----

    @Test fun aCompleteTruncatingCheckpointIsReportedCompleted() {
        val outcome = interpretWalCheckpoint(0L, 0L, 0L, walBytesBefore = 4L * mib, walBytesAfter = 0L)
        assertEquals(WalCheckpointResult.COMPLETED, outcome.result)
        assertEquals(4L * mib, outcome.walBytesBefore)
        assertEquals(0L, outcome.walBytesAfter)
    }

    @Test fun aBusyCheckpointIsDeferredNotSuccess() {
        assertEquals(WalCheckpointResult.DEFERRED_BUSY, interpretWalCheckpoint(1L, 10L, 4L, 4L * mib, 4L * mib).result)
        assertEquals(
            "SQLite's own busy report is believed even when the file happens to read empty",
            WalCheckpointResult.DEFERRED_BUSY,
            interpretWalCheckpoint(1L, 0L, 0L, 4L * mib, 0L).result,
        )
        assertEquals(
            "busy clear but a WAL still holding frames is not complete",
            WalCheckpointResult.DEFERRED_BUSY,
            interpretWalCheckpoint(0L, 10L, 4L, 4L * mib, 4L * mib).result,
        )
    }

    @Test fun aSuccessRowOverAWalThatIsNotEmptyIsNeverReportedCompleted() {
        // Another connection wrote between the checkpoint and the measurement: contention, not success.
        assertEquals(WalCheckpointResult.DEFERRED_BUSY, interpretWalCheckpoint(0L, 0L, 0L, 4L * mib, 4_120L).result)
    }

    @Test fun aMissingOrMalformedResultRowIsAFailure() {
        assertEquals(WalCheckpointResult.FAILED, interpretWalCheckpoint(null, null, null, mib, mib).result)
    }

    @Test fun aTruncatingCheckpointNeedsRoomForTheBackfill() {
        assertTrue(walCheckpointAdmitted(usableBytes = 20L * mib, walBytes = 4L * mib, marginBytes = 16L * mib))
        assertFalse(walCheckpointAdmitted(usableBytes = 20L * mib - 1L, walBytes = 4L * mib, marginBytes = 16L * mib))
        assertFalse("unknown capacity refuses", walCheckpointAdmitted(null, 4L * mib, 16L * mib))
    }

    // ---- the ladder ----

    private fun snapshot(
        pressure: StorageHealthSeverity,
        severity: StorageHealthSeverity = pressure,
        usableBytes: Long = 300L * mib,
        mainBytes: Long = 20L * mib,
        walBytes: Long = 2L * mib,
        pageCount: Long = 5_120L,
        freelist: Long = 100L,
        autoVacuum: StorageAutoVacuumMode = StorageAutoVacuumMode.INCREMENTAL,
    ) = StorageHealthSnapshot.UNCHECKED.copy(
        severity = severity,
        pressureSeverity = pressure,
        checkedAtMillis = 1L,
        usableBytes = usableBytes,
        totalBytes = 8L * 1024L * mib,
        mainDatabaseBytes = mainBytes,
        walBytes = walBytes,
        pageSizeBytes = pageSize,
        pageCount = pageCount,
        freelistCount = freelist,
        quickCheck = StorageQuickCheck.OK,
        autoVacuumMode = autoVacuum,
    )

    private class Recorder(
        var observations: ArrayDeque<StorageHealthSnapshot?>,
        var sweep: List<DisposableSweepResult> = emptyList(),
        var retention: RetentionResult = RetentionResult.CONVERGED,
        var checkpoint: WalCheckpointOutcome = WalCheckpointOutcome(WalCheckpointResult.COMPLETED, 2L, 0L),
        var backup: Boolean = true,
        var vacuumOutcome: VacuumOutcome = VacuumOutcome(VacuumResult.COMPLETED),
        var owned: Boolean = true,
        var duringRetention: Recorder.() -> Unit = {},
    ) : StorageRemediationOperations {
        val calls = mutableListOf<String>()
        override fun sweepDisposableFiles() = sweep.also { calls += "sweep" }
        override fun enforceRetention() = retention.also { calls += "retention"; duringRetention() }
        override fun checkpointWal() = checkpoint.also { calls += "checkpoint" }
        override fun writeVerifiedBackup() = backup.also { calls += "backup" }
        override fun vacuum() = vacuumOutcome.also { calls += "vacuum" }
        override fun lifecycleOwned() = owned
        /** The last queued observation repeats, so a test names only the states that change. */
        override suspend fun observe(): StorageHealthSnapshot? =
            (if (observations.size > 1) observations.removeFirst() else observations.firstOrNull()).also { calls += "observe" }
    }

    private fun run(operations: Recorder, initial: StorageHealthSnapshot) =
        runBlocking { StorageRemediationLadder(operations, nowMillis = { 42L }).run(initial) }

    @Test fun healthyStorageRunsNoRemediation() {
        val operations = Recorder(ArrayDeque())
        assertNull(run(operations, snapshot(StorageHealthSeverity.HEALTHY)))
        assertEquals(emptyList<String>(), operations.calls)
    }

    @Test fun warningRunsEverySafeStepInCostOrderAndReportsRelief() {
        val warning = snapshot(StorageHealthSeverity.WARNING)
        val relieved = snapshot(StorageHealthSeverity.HEALTHY, mainBytes = 15L * mib, walBytes = 0L)
        val operations = Recorder(
            ArrayDeque(listOf(warning, relieved)),
            sweep = listOf(DisposableSweepResult(DisposableDataClass.DOWNLOADS, 2, 3L * mib, 0, 0)),
            retention = RetentionResult.PRUNED,
        )

        val summary = run(operations, warning)!!

        assertEquals(listOf("sweep", "retention", "observe", "checkpoint", "observe"), operations.calls)
        assertEquals(StorageRemediationVerdict.RELIEVED, summary.verdict)
        assertEquals(3L * mib, summary.fileBytesFreed)
        assertEquals("measured from the observations, not inferred", 7L * mib, summary.databaseBytesFreed)
        assertEquals(10L * mib, summary.bytesFreed)
        assertEquals(RetentionResult.PRUNED, summary.retention)
        assertEquals(VacuumRefusal.NOT_NEEDED, summary.vacuum.refusal)
        assertFalse(summary.escalated)
    }

    @Test fun escalatesWhenNothingSafeRemains() {
        val warning = snapshot(StorageHealthSeverity.WARNING)
        val operations = Recorder(ArrayDeque(listOf(warning)))

        val summary = run(operations, warning)!!

        assertEquals(StorageRemediationVerdict.EXHAUSTED, summary.verdict)
        assertTrue("the operator must be told", summary.escalated)
        assertEquals(VacuumRefusal.INCREMENTAL_AVAILABLE, summary.vacuum.refusal)
        assertEquals(StorageHealthSeverity.WARNING, summary.finalPressure)
    }

    @Test fun criticalPressureDeletesOnlyFilesRunsNoRebuildAndEscalates() {
        val critical = snapshot(StorageHealthSeverity.CRITICAL, usableBytes = 100L * mib, autoVacuum = StorageAutoVacuumMode.NONE,
            pageCount = 5_000L, freelist = 3_000L)
        val operations = Recorder(ArrayDeque(listOf(critical)))

        val summary = run(operations, critical)!!

        assertFalse("row deletes cost WAL space before any returns", "retention" in operations.calls)
        assertFalse("never a rebuild at critical", "vacuum" in operations.calls)
        assertFalse("no backup is written for a rebuild that cannot run", "backup" in operations.calls)
        assertEquals(RetentionResult.SKIPPED_CRITICAL_PRESSURE, summary.retention)
        assertEquals(VacuumRefusal.CRITICAL_PRESSURE, summary.vacuum.refusal)
        assertEquals(StorageRemediationVerdict.EXHAUSTED, summary.verdict)
        assertTrue(summary.escalated)
    }

    @Test fun prunedRowsStillAwaitingBoundedReclamationDeferRatherThanEscalate() {
        // Deleted rows reach the filesystem only through incremental reclamation, which is gated and
        // capped per pass; a freelist above its retained floor will still come back.
        val warning = snapshot(StorageHealthSeverity.WARNING)
        val pending = snapshot(StorageHealthSeverity.WARNING, freelist = FREELIST_RETAINED_PAGES + 1L)
        val operations = Recorder(ArrayDeque(listOf(pending)), retention = RetentionResult.PRUNED)

        val summary = run(operations, warning)!!

        assertEquals(StorageRemediationVerdict.DEFERRED, summary.verdict)
        assertFalse(summary.escalated)

        val atFloor = snapshot(StorageHealthSeverity.WARNING, freelist = FREELIST_RETAINED_PAGES)
        assertEquals(
            "at the retained floor nothing more will come back",
            StorageRemediationVerdict.EXHAUSTED,
            run(Recorder(ArrayDeque(listOf(atFloor)), retention = RetentionResult.PRUNED), warning)!!.verdict,
        )
    }

    @Test fun aCheckpointFailureIsReportedAsAFailure() {
        val warning = snapshot(StorageHealthSeverity.WARNING)
        val operations = Recorder(
            ArrayDeque(listOf(warning)),
            checkpoint = WalCheckpointOutcome(WalCheckpointResult.FAILED, 2L * mib, 2L * mib),
        )

        val summary = run(operations, warning)!!

        assertEquals(WalCheckpointResult.FAILED, summary.walCheckpoint)
        assertEquals(StorageRemediationVerdict.EXHAUSTED, summary.verdict)
    }

    @Test fun aBusyCheckpointDefersRatherThanEscalates() {
        val warning = snapshot(StorageHealthSeverity.WARNING)
        val operations = Recorder(
            ArrayDeque(listOf(warning)),
            checkpoint = WalCheckpointOutcome(WalCheckpointResult.DEFERRED_BUSY, 2L * mib, 2L * mib),
        )

        val summary = run(operations, warning)!!

        assertEquals(StorageRemediationVerdict.DEFERRED, summary.verdict)
        assertFalse(summary.escalated)
    }

    @Test fun anOwnerHoldingTheDataDirectoryDefersEveryStepRatherThanEscalating() {
        val warning = snapshot(StorageHealthSeverity.WARNING)
        val operations = Recorder(ArrayDeque(listOf(warning)), owned = false)

        val summary = run(operations, warning)!!

        assertFalse("files are not swept while another owner holds the directory", "sweep" in operations.calls)
        assertFalse("retention" in operations.calls)
        assertEquals(RetentionResult.SKIPPED_LIFECYCLE, summary.retention)
        assertEquals(StorageRemediationVerdict.DEFERRED, summary.verdict)
        assertFalse("a later run can still do more", summary.escalated)
    }

    @Test fun anOwnerHoldingTheDataDirectoryDefersEvenAtCriticalPressure() {
        // At critical the retention and rebuild steps report their critical refusals, not the owner, so
        // only the withheld sweep itself can say that a later run may still free something.
        val critical = snapshot(StorageHealthSeverity.CRITICAL, usableBytes = 10L * mib)
        val operations = Recorder(ArrayDeque(listOf(critical)), owned = false)

        val summary = run(operations, critical)!!

        assertFalse("sweep" in operations.calls)
        assertEquals(RetentionResult.SKIPPED_CRITICAL_PRESSURE, summary.retention)
        assertEquals(VacuumRefusal.CRITICAL_PRESSURE, summary.vacuum.refusal)
        assertEquals(StorageRemediationVerdict.DEFERRED, summary.verdict)
    }

    @Test fun aRebuildWhoseImageIsStillInABusyWalDefersRatherThanEscalating() {
        val none = snapshot(StorageHealthSeverity.WARNING, usableBytes = 400L * mib, mainBytes = 5_000L * pageSize,
            walBytes = 0L, pageCount = 5_000L, freelist = 3_000L, autoVacuum = StorageAutoVacuumMode.NONE)
        val stillPressured = none.copy(walBytes = 2_000L * (pageSize + 24L), freelistCount = 0L,
            autoVacuumMode = StorageAutoVacuumMode.INCREMENTAL)
        val operations = Recorder(
            ArrayDeque(listOf(none, none, stillPressured)),
            vacuumOutcome = VacuumOutcome(VacuumResult.COMPLETED, checkpoint = WalCheckpointResult.DEFERRED_BUSY),
        )

        val summary = run(operations, none)!!

        assertEquals(VacuumResult.COMPLETED, summary.vacuum.result)
        assertEquals(StorageRemediationVerdict.DEFERRED, summary.verdict)
    }

    @Test fun aCheckpointIsNotStartedWhenOwnershipIsLostDuringRetention() {
        val warning = snapshot(StorageHealthSeverity.WARNING)
        val operations = Recorder(ArrayDeque(listOf(warning)), duringRetention = { owned = false })

        val summary = run(operations, warning)!!

        assertFalse("shutdown or a data backup began during retention", "checkpoint" in operations.calls)
        assertEquals(WalCheckpointResult.SKIPPED_LIFECYCLE, summary.walCheckpoint)
        assertEquals(StorageRemediationVerdict.DEFERRED, summary.verdict)
    }

    @Test fun aCheckpointIsAdmittedOnHeadroomObservedAfterRetentionNotBefore() {
        val ample = snapshot(StorageHealthSeverity.WARNING, usableBytes = 400L * mib, walBytes = 60L * mib)
        val shrunk = snapshot(StorageHealthSeverity.WARNING, usableBytes = 70L * mib, walBytes = 60L * mib)
        val operations = Recorder(ArrayDeque(listOf(shrunk)))

        val summary = run(operations, ample)!!

        assertEquals(listOf("sweep", "retention", "observe", "observe"), operations.calls)
        assertEquals(WalCheckpointResult.REFUSED_HEADROOM, summary.walCheckpoint)
    }

    @Test fun aDatabaseFailureLatchedDuringRetentionStopsTheCheckpoint() {
        val warning = snapshot(StorageHealthSeverity.WARNING)
        val latched = snapshot(StorageHealthSeverity.WARNING, severity = StorageHealthSeverity.DATABASE_FAILURE)
        val operations = Recorder(ArrayDeque(listOf(latched)), retention = RetentionResult.FAILED)

        val summary = run(operations, warning)!!

        assertFalse("checkpoint" in operations.calls)
        assertEquals(WalCheckpointResult.SKIPPED_DATABASE_FAILURE, summary.walCheckpoint)
    }

    @Test fun aCheckpointWithNoObservationAfterRetentionIsNotGuessed() {
        val warning = snapshot(StorageHealthSeverity.WARNING)
        val operations = Recorder(ArrayDeque(listOf(null)))

        val summary = run(operations, warning)!!

        assertFalse("checkpoint" in operations.calls)
        assertEquals(WalCheckpointResult.SKIPPED_UNOBSERVED, summary.walCheckpoint)
    }

    @Test fun aCheckpointWithoutRoomForTheBackfillIsRefusedNotRun() {
        val tight = snapshot(StorageHealthSeverity.CRITICAL, usableBytes = 10L * mib, walBytes = 2L * mib)
        val operations = Recorder(ArrayDeque(listOf(tight)))

        val summary = run(operations, tight)!!

        assertFalse("checkpoint" in operations.calls)
        assertEquals(WalCheckpointResult.REFUSED_HEADROOM, summary.walCheckpoint)
    }

    @Test fun aLatchedDatabaseFailureStopsEveryDatabaseStepButStillSweepsFiles() {
        val failed = snapshot(StorageHealthSeverity.WARNING, severity = StorageHealthSeverity.DATABASE_FAILURE)
        val operations = Recorder(ArrayDeque(listOf(failed)))

        val summary = run(operations, failed)!!

        assertEquals(listOf("sweep", "observe"), operations.calls)
        assertEquals(RetentionResult.SKIPPED_DATABASE_FAILURE, summary.retention)
        assertEquals(WalCheckpointResult.SKIPPED_DATABASE_FAILURE, summary.walCheckpoint)
        assertEquals(VacuumRefusal.DATABASE_FAILURE, summary.vacuum.refusal)
    }

    @Test fun anAdmittedRebuildWritesItsBackupFirstRunsOnceAndIsJudgedByAFreshObservation() {
        val none = snapshot(StorageHealthSeverity.WARNING, usableBytes = 400L * mib, mainBytes = 5_000L * pageSize,
            walBytes = 0L, pageCount = 5_000L, freelist = 3_000L, autoVacuum = StorageAutoVacuumMode.NONE)
        val rebuilt = snapshot(StorageHealthSeverity.HEALTHY, mainBytes = 2_000L * pageSize, walBytes = 0L,
            pageCount = 2_000L, freelist = 0L)
        val operations = Recorder(ArrayDeque(listOf(none, none, rebuilt)))

        val summary = run(operations, none)!!

        assertEquals(listOf("sweep", "retention", "observe", "observe", "backup", "vacuum", "observe"), operations.calls)
        assertEquals(VacuumResult.COMPLETED, summary.vacuum.result)
        assertEquals(StorageRemediationVerdict.RELIEVED, summary.verdict)
        assertEquals(3_000L * pageSize, summary.databaseBytesFreed)
    }

    @Test fun aRebuildWhoseBackupCannotBeVerifiedNeverRuns() {
        val none = snapshot(StorageHealthSeverity.WARNING, usableBytes = 400L * mib, mainBytes = 5_000L * pageSize,
            walBytes = 0L, pageCount = 5_000L, freelist = 3_000L, autoVacuum = StorageAutoVacuumMode.NONE)
        val operations = Recorder(ArrayDeque(listOf(none)), backup = false)

        val summary = run(operations, none)!!

        assertFalse("vacuum" in operations.calls)
        assertEquals(VacuumRefusal.NO_VERIFIED_BACKUP, summary.vacuum.refusal)
        assertEquals(StorageRemediationVerdict.EXHAUSTED, summary.verdict)
    }

    @Test fun aRebuildWithoutHeadroomNeverRunsAndWritesNoBackup() {
        val none = snapshot(StorageHealthSeverity.WARNING, usableBytes = 40L * mib, mainBytes = 5_000L * pageSize,
            walBytes = 0L, pageCount = 5_000L, freelist = 3_000L, autoVacuum = StorageAutoVacuumMode.NONE)
        val operations = Recorder(ArrayDeque(listOf(none)))

        val summary = run(operations, none)!!

        assertFalse("vacuum" in operations.calls)
        assertFalse("backup" in operations.calls)
        assertEquals(VacuumRefusal.INSUFFICIENT_HEADROOM, summary.vacuum.refusal)
    }

    @Test fun anUnobservableOutcomeIsNeverReportedAsRelief() {
        val warning = snapshot(StorageHealthSeverity.WARNING)
        val operations = Recorder(ArrayDeque(listOf(null)))

        val summary = run(operations, warning)!!

        assertEquals(StorageRemediationVerdict.UNVERIFIED, summary.verdict)
        assertEquals(VacuumResult.NOT_RUN, summary.vacuum.result)
        assertEquals("no observation, no claimed database bytes", 0L, summary.databaseBytesFreed)
    }

    @Test fun maintenancePlanFollowsPressure() {
        assertEquals(StorageMaintenancePlan.NONE, storageMaintenancePlan(StorageHealthSnapshot.UNCHECKED))
        assertEquals(StorageMaintenancePlan.RETENTION, storageMaintenancePlan(snapshot(StorageHealthSeverity.HEALTHY)))
        assertEquals(
            StorageMaintenancePlan.NONE,
            storageMaintenancePlan(snapshot(StorageHealthSeverity.HEALTHY, severity = StorageHealthSeverity.DATABASE_FAILURE)),
        )
        assertEquals(StorageMaintenancePlan.REMEDIATE, storageMaintenancePlan(snapshot(StorageHealthSeverity.WARNING)))
        assertEquals(StorageMaintenancePlan.REMEDIATE, storageMaintenancePlan(snapshot(StorageHealthSeverity.CRITICAL)))
    }

    @Test fun aBackupIsVerifiedOnlyWhenItReadsBackExactly() {
        val rows = listOf("a" to 1, "b" to 2)
        assertTrue(configurationBackupVerified(rows, rows.reversed()))
        assertFalse(configurationBackupVerified(rows, null))
        assertFalse(configurationBackupVerified(rows, rows.take(1)))
        assertFalse(configurationBackupVerified(rows, listOf("a" to 1, "b" to 3)))
        assertFalse("an empty backup protects nothing", configurationBackupVerified(emptyList<Int>(), emptyList()))
    }

    @Test fun remediationIsRetainedAcrossLaterObservationsWithoutChangingSeverity() {
        val state = StorageHealthState(StorageHealthPolicy())
        val summary = StorageRemediationSummary(
            1L, StorageRemediationVerdict.EXHAUSTED, 0, 0L, 0L, RetentionResult.CONVERGED,
            WalCheckpointResult.COMPLETED, VacuumOutcome(VacuumResult.REFUSED, VacuumRefusal.INCREMENTAL_AVAILABLE),
            StorageHealthSeverity.WARNING,
        )
        val before = state.snapshot().severity
        assertEquals(before, state.recordRemediation(summary).severity)
        val observation = StorageHealthObservation(
            checkedAtMillis = 2L, usableBytes = 300L * mib, totalBytes = 8L * 1024L * mib,
            mainDatabaseBytes = mib, walBytes = 0L, sidecarBytes = 0L, pageSizeBytes = pageSize,
            pageCount = 256L, freelistCount = 0L, schemaVersion = 11, quickCheck = StorageQuickCheck.OK,
        )
        assertEquals(summary, state.refresh(observation, state.beginObservation()).remediation)
    }
}
