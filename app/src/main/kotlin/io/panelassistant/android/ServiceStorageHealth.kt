package io.panelassistant.android

import android.content.Context
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Log
import io.panelassistant.android.storage.DisposableFileSweeper
import io.panelassistant.android.storage.DisposableSweepResult
import io.panelassistant.android.storage.ProcessStartWallClock
import io.panelassistant.android.storage.RetentionResult
import io.panelassistant.android.storage.StorageHealthObservation
import io.panelassistant.android.storage.StorageHealthObservationQueue
import io.panelassistant.android.storage.StorageHealthRecoveryLifecycle
import io.panelassistant.android.storage.StorageHealthRuntime
import io.panelassistant.android.storage.StorageHealthSnapshot
import io.panelassistant.android.storage.StorageMaintenancePlan
import io.panelassistant.android.storage.StorageQuickCheck
import io.panelassistant.android.storage.StorageRemediationLadder
import io.panelassistant.android.storage.runHealthyStorageMaintenance
import io.panelassistant.android.storage.StorageRemediationOperations
import io.panelassistant.android.storage.VacuumOutcome
import io.panelassistant.android.storage.VacuumResult
import io.panelassistant.android.storage.WalCheckpointOutcome
import io.panelassistant.android.storage.WalCheckpointResult
import io.panelassistant.android.storage.appOwnedDisposableDirectoryRules
import io.panelassistant.android.storage.appOwnedDisposableFileRules
import io.panelassistant.android.storage.storageMaintenancePlan
import io.panelassistant.android.util.guardDbAppStaging
import io.panelassistant.android.util.periodic
import io.panelassistant.android.util.validGuardDbAppFile
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * The service's storage-health owner: the daily observation loop with its bounded retries, the daily
 * maintenance and remediation ladder, prompt recovery after a recorded database failure, the HTTP
 * status refresh, and the subscription that forwards each new snapshot. Every SQLite read and
 * maintenance step goes through the one observation queue, so none of them overlaps another.
 *
 * [isStopping], [companionPending] and every [Database] call are read when used, never captured:
 * the service creates this unit before the learner that owns the database exists.
 */
internal class ServiceStorageHealth(
    private val context: Context,
    private val scope: CoroutineScope,
    private val isStopping: () -> Boolean,
    private val database: Database,
    private val companionPending: () -> Boolean,
) {
    /** The app database operations this owner observes and maintains through. */
    interface Database {
        fun storageHealthObservation(cancellationSignal: CancellationSignal): StorageHealthObservation
        fun enforceHistoryRetention(): Boolean
        fun truncateDatabaseWal(): WalCheckpointOutcome
        fun writeVerifiedConfigurationBackup(): Boolean
        fun vacuumDatabase(): VacuumOutcome
    }

    private var storageHealthSubscription: AutoCloseable? = null
    private val storageHealthLifecycleLock = Any()
    private var storageHealthRecoveryLifecycle: StorageHealthRecoveryLifecycle? = null
    private val storageHealthObservationQueue = StorageHealthObservationQueue(
        create = ::CancellationSignal,
        cancel = CancellationSignal::cancel,
    )

    fun subscribe(onSnapshot: (StorageHealthSnapshot) -> Unit) {
        storageHealthSubscription = StorageHealthRuntime.subscribe(onSnapshot)
    }

    fun close() {
        storageHealthSubscription?.close()
        storageHealthSubscription = null
        val recoveryLifecycle = synchronized(storageHealthLifecycleLock) {
            storageHealthRecoveryLifecycle.also { storageHealthRecoveryLifecycle = null }
        }
        recoveryLifecycle?.close()
        storageHealthObservationQueue.close()
    }

    fun startStorageHealthChecks() {
        val recoveryLifecycle = StorageHealthRecoveryLifecycle(
            scope = scope,
            delaysMs = STORAGE_HEALTH_RECOVERY_DELAYS_MS,
            subscribeFailures = StorageHealthRuntime::subscribeDatabaseFailures,
            verify = {
                storageHealthRecoveryAttemptComplete(runQueuedStorageHealthObservation())
            },
            onError = { error ->
                Log.w(TAG, "storage health recovery failed (${error.javaClass.simpleName})")
            },
        )
        val admitted = synchronized(storageHealthLifecycleLock) {
            if (isStopping()) {
                false
            } else {
                storageHealthRecoveryLifecycle = recoveryLifecycle
                true
            }
        }
        if (!admitted) {
            recoveryLifecycle.close()
            return
        }
        scope.periodic(
            intervalMs = STORAGE_HEALTH_CHECK_MS,
            initialDelayMs = 0L,
            tag = TAG,
            name = "storage-health",
            // The shared health authority already retains a controlled failure category. Do not copy
            // SQLite messages or app-private paths into log shipping/support output.
            onError = { error -> Log.w(TAG, "storage health check failed (${error.javaClass.simpleName})") },
        ) {
            repeat(STORAGE_HEALTH_CHECK_ATTEMPTS) { index ->
                if (isStopping()) return@periodic
                val attempt = index + 1
                when (runQueuedStorageHealthObservation()) {
                    StorageHealthObservationAttempt.Complete -> {
                        runStorageMaintenance()
                        return@periodic
                    }
                    StorageHealthObservationAttempt.Stopped -> return@periodic
                    StorageHealthObservationAttempt.Retry -> Unit
                }
                if (attempt == STORAGE_HEALTH_CHECK_ATTEMPTS) {
                    Log.w(TAG, "storage health check remained incomplete after $attempt attempts")
                    return@periodic
                }
                Log.w(TAG, "storage health check retrying ($attempt/$STORAGE_HEALTH_CHECK_ATTEMPTS)")
                kotlinx.coroutines.delay(STORAGE_HEALTH_RETRY_MS)
            }
        }
    }

    /**
     * Daily storage maintenance after a completed observation, serialized with every observation so
     * no SQLite work overlaps a health read. A healthy panel converges its existing retention limits;
     * elevated pressure runs the fail-safe remediation ladder, whose measured result is published on
     * every storage-health surface. Prompt recovery and HTTP refreshes never start remediation.
     */
    private suspend fun runStorageMaintenance() {
        storageHealthObservationQueue.run { signal ->
            if (isStopping()) return@run Unit
            val snapshot = StorageHealthRuntime.snapshot()
            when (storageMaintenancePlan(snapshot)) {
                StorageMaintenancePlan.NONE -> Unit
                StorageMaintenancePlan.RETENTION ->
                    if (runHealthyStorageMaintenance(storageRemediationOperations(signal)) == RetentionResult.FAILED) {
                        Log.w(TAG, "history retention failed")
                    }
                StorageMaintenancePlan.REMEDIATE ->
                    StorageRemediationLadder(storageRemediationOperations(signal)).run(snapshot)?.let { summary ->
                        StorageHealthRuntime.recordRemediation(summary)
                        Log.w(
                            TAG,
                            "storage remediation ${summary.verdict.name.lowercase(Locale.ROOT)}: " +
                                "freed ${summary.bytesFreed} bytes (${summary.filesDeleted} files), " +
                                "retention=${summary.retention.name.lowercase(Locale.ROOT)} " +
                                "wal=${summary.walCheckpoint.name.lowercase(Locale.ROOT)} " +
                                "vacuum=${summary.vacuum.result.name.lowercase(Locale.ROOT)}" +
                                (summary.vacuum.refusal?.let { "/${it.name.lowercase(Locale.ROOT)}" } ?: ""),
                        )
                    }
            }
            Unit
        }
    }

    private fun storageRemediationOperations(signal: CancellationSignal) = object : StorageRemediationOperations {
        override fun sweepDisposableFiles(): List<DisposableSweepResult> {
            // Only files written before this process existed can be orphans, judged against its start
            // in the oldest clock epoch it has run in. With no captured start nothing is provably
            // orphaned, so nothing is swept.
            val processStart = ProcessStartWallClock.orphanBoundary(
                System.currentTimeMillis(),
                android.os.SystemClock.elapsedRealtime(),
            ) ?: return emptyList()
            val sweeper = DisposableFileSweeper(processStart)
            val pairUnarmable = { guardDbAppStaging(context).unarmable(File(context.applicationInfo.sourceDir)) }
            return appOwnedDisposableFileRules(context.cacheDir, context.filesDir, pairUnarmable, ::validGuardDbAppFile).map(sweeper::sweep) +
                appOwnedDisposableDirectoryRules(context.cacheDir).map(sweeper::sweep)
        }

        override fun enforceRetention(): RetentionResult = runCatching {
            if (database.enforceHistoryRetention()) RetentionResult.PRUNED else RetentionResult.CONVERGED
        }.getOrElse { RetentionResult.FAILED }

        override fun checkpointWal(): WalCheckpointOutcome = runCatching { database.truncateDatabaseWal() }
            .getOrElse { WalCheckpointOutcome(WalCheckpointResult.FAILED, 0L, 0L) }

        override fun writeVerifiedBackup(): Boolean = database.writeVerifiedConfigurationBackup()

        override fun vacuum(): VacuumOutcome = runCatching { database.vacuumDatabase() }
            .getOrElse { VacuumOutcome(VacuumResult.FAILED) }

        // A companion data backup or restore owns the data directory until its marker clears.
        override fun lifecycleOwned(): Boolean = !isStopping() && !signal.isCanceled &&
            !companionPending()

        override suspend fun observe(): StorageHealthSnapshot? =
            when (runStorageHealthObservation(signal)) {
                StorageHealthObservationAttempt.Complete -> StorageHealthRuntime.snapshot()
                StorageHealthObservationAttempt.Retry,
                StorageHealthObservationAttempt.Stopped -> null
            }
    }

    /** One bounded observation attempt shared by prompt recovery and the independent daily loop. */
    private suspend fun runQueuedStorageHealthObservation(): StorageHealthObservationAttempt =
        storageHealthObservationQueue.run(::runStorageHealthObservation)
            ?: StorageHealthObservationAttempt.Stopped

    /** Same-request HTTP proof joins the daily/recovery queue; it never creates a second SQLite reader
     * authority. Only a complete clean observation earns a nonce-bound status proof. */
    suspend fun refreshStorageHealthForStatus(): StorageHealthSnapshot? =
        when (runQueuedStorageHealthObservation()) {
            StorageHealthObservationAttempt.Complete -> StorageHealthRuntime.snapshot().takeIf {
                it.schemaVersion > 0 && it.quickCheck == StorageQuickCheck.OK && it.checkedAtMillis > 0L
            }
            StorageHealthObservationAttempt.Retry,
            StorageHealthObservationAttempt.Stopped -> null
        }

    private suspend fun runStorageHealthObservation(
        cancellationSignal: CancellationSignal,
    ): StorageHealthObservationAttempt {
        if (isStopping()) return StorageHealthObservationAttempt.Stopped
        return try {
            // Capture immediately before the read. A database failure recorded while this observation
            // is in flight must remain newer truth when the result returns.
            val observationToken = StorageHealthRuntime.beginObservation()
            val observation = database.storageHealthObservation(cancellationSignal)
            if (isStopping()) {
                StorageHealthObservationAttempt.Stopped
            } else {
                val quality = storageHealthObservationQuality(observation)
                // Refresh never clears the failure latch. A completed clean observation only arms the
                // next ordinary durable write; a completed integrity failure remains authoritative.
                StorageHealthRuntime.refresh(observation, observationToken)
                if (storageHealthObservationNeedsRetry(observation)) {
                    Log.w(TAG, "storage health observation ${quality.name.lowercase(Locale.ROOT)}")
                    StorageHealthObservationAttempt.Retry
                } else {
                    StorageHealthObservationAttempt.Complete
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OperationCanceledException) {
            if (isStopping()) StorageHealthObservationAttempt.Stopped
            else StorageHealthObservationAttempt.Retry
        } catch (failure: Exception) {
            if (!isStopping()) {
                Log.w(TAG, "storage health observation failed (${failure.javaClass.simpleName})")
            }
            if (isStopping()) StorageHealthObservationAttempt.Stopped
            else StorageHealthObservationAttempt.Retry
        }
    }

    private companion object {
        const val TAG = PaneldService.TAG
        // Daily rather than wall-clock scheduled: the service owns one immediate check, then delays
        // from completion. A bounded short retry absorbs transient StatFs/SQLite observation failures.
        const val STORAGE_HEALTH_CHECK_MS = 24L * 3_600L * 1_000L
        const val STORAGE_HEALTH_CHECK_ATTEMPTS = 3
        const val STORAGE_HEALTH_RETRY_MS = 5_000L
        val STORAGE_HEALTH_RECOVERY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 30_000L)
    }
}
