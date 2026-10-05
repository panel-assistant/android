package io.panelassistant.android.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.dashboard.EntityCatalogStore
import io.panelassistant.android.dashboard.DatabaseRestoreGuardBinding
import io.panelassistant.android.dashboard.DatabaseRestoreGuardContext
import io.panelassistant.android.dashboard.DatabaseRestoreTransaction
import io.panelassistant.android.dashboard.SchemaReconcileAction
import io.panelassistant.android.persistence.readAppStateSemanticProof
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class GuardDbStartupProof(
    val apkSha256: String,
    val versionCode: Long,
    val schema: Int,
    val quickCheckOk: Boolean,
    val appStateCount: Long,
    val orderedAppStateSha256: String,
    val settingsSemanticSha256: String,
    val probe: GuardDbMaintenanceProtocol.Probe,
    val recoveryProof: GuardDbMaintenanceProtocol.RecoveryProof,
)

internal fun guardDbStartupHealthCommand(
    status: GuardDbMaintenanceProtocol.Status,
    proof: GuardDbStartupProof,
): String? {
    val role = status.role ?: return null
    val expectedProbe = if (role == GuardDbMaintenanceProtocol.Role.B) {
        GuardDbMaintenanceProtocol.Probe.PRESENT
    } else {
        GuardDbMaintenanceProtocol.Probe.ABSENT
    }
    val eligible = when (role) {
        GuardDbMaintenanceProtocol.Role.B -> status.phase == GuardDbMaintenanceProtocol.Phase.WAIT_B_HEALTH
        GuardDbMaintenanceProtocol.Role.A -> status.phase in setOf(
            GuardDbMaintenanceProtocol.Phase.WAIT_A_HEALTH,
            GuardDbMaintenanceProtocol.Phase.ROLLBACK_DB_RESTORED,
        )
    }
    if (!eligible) return null
    val healthy = proof.quickCheckOk && proof.apkSha256 == status.apkSha256 &&
        proof.versionCode == status.versionCode && proof.schema == status.schema &&
        proof.appStateCount == status.baselineAppStateCount && proof.probe == expectedProbe &&
        proof.recoveryProof == expectedRecoveryProof(status)
    return GuardDbMaintenanceProtocol.health(
        status = status,
        role = role,
        apkSha256 = proof.apkSha256,
        versionCode = proof.versionCode,
        schema = proof.schema,
        healthy = healthy,
        appStateCount = proof.appStateCount,
        orderedAppStateSha256 = proof.orderedAppStateSha256,
        settingsSemanticSha256 = proof.settingsSemanticSha256,
        probe = proof.probe,
        recoveryProof = proof.recoveryProof,
    )
}

internal enum class GuardDbFreshProcessRoute {
    ORDINARY_PANELD,
    WRITER_FREE_MAINTENANCE,
}

internal fun guardDbFreshProcessRoute(
    sentinelState: GuardDbSentinelState,
    helperPhase: GuardDbMaintenanceProtocol.Phase,
): GuardDbFreshProcessRoute =
    if (sentinelState == GuardDbSentinelState.INTENT &&
        helperPhase == GuardDbMaintenanceProtocol.Phase.EMPTY
    ) {
        GuardDbFreshProcessRoute.ORDINARY_PANELD
    } else {
        GuardDbFreshProcessRoute.WRITER_FREE_MAINTENANCE
    }

/** The maintenance listener may start only after foreground promotion and the startup proof closes. */
internal class GuardDbMaintenanceStartupGate(private val scope: CoroutineScope) {
    @Volatile
    private var foreground = false

    fun promoted() {
        foreground = true
    }

    fun launch(
        acknowledge: () -> Boolean,
        startMaintenance: suspend () -> Unit,
        resumeOrdinary: suspend () -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Job {
        check(foreground) { "Guard DB startup requires foreground promotion" }
        return scope.launch(Dispatchers.IO) {
            try {
                if (acknowledge()) resumeOrdinary() else startMaintenance()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                onFailure(failure)
            }
        }
    }
}

internal object GuardDbStartupAcknowledger {
    private const val TAG = "ha-paneld/guard-db-health"
    private const val PROBE_TABLE = "db_compatibility_canary_v15"
    private val attempts = GuardDbHealthAttemptGate()

    /** Called by the promoted maintenance service before its HTTP listener or an ordinary owner. */
    fun reconcileBeforeMaintenanceServer(context: Context): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Guard DB startup inspection must not run on the main thread"
        }
        val load = GuardDbProcessAdmission.current()
        if (load is GuardDbSentinelLoad.Absent) return true
        val sentinel = (load as? GuardDbSentinelLoad.Valid)?.sentinel ?: return false
        if (guardDbBootNonce() != sentinel.bootNonce) return false
        val probe = reacquireStatus()
        val status = (probe as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status ?: return false
        when (guardDbFreshProcessRoute(sentinel.state, status.phase)) {
            GuardDbFreshProcessRoute.ORDINARY_PANELD -> {
                val preparedCleared = guardDbPreparedArmStore(context).clear(sentinel.session)
                if (!preparedCleared) return false
                val cleared = guardDbSentinelStore(context).clear(sentinel.session)
                // Application skipped ordinary initialization in this process. Keep its in-memory
                // admission fence until the maintenance service starts an ordinary fresh process.
                return cleared
            }
            GuardDbFreshProcessRoute.WRITER_FREE_MAINTENANCE -> Unit
        }
        if (status.phase == GuardDbMaintenanceProtocol.Phase.EMPTY) {
            if (sentinel.state != GuardDbSentinelState.BASELINE_READY) return false
            when (val prepared = guardDbPreparedArmStore(context).load()) {
                GuardDbPreparedArmLoad.Absent -> return false
                GuardDbPreparedArmLoad.Corrupt -> return false
                is GuardDbPreparedArmLoad.Valid -> {
                    // Clean proof exists and writers are closed, but helper custody has not started.
                    // Stay in the narrow successor until a second exact physical approval commits it.
                    if (!prepared.prepared.matches(sentinel)) return false
                    return false
                }
            }
            return false
        }
        if (status.session != sentinel.session || status.bootNonce != sentinel.bootNonce) return false
        if (status.phase == GuardDbMaintenanceProtocol.Phase.FINALIZED) {
            if (!exactGuardDbFinalStatus(context.applicationContext, status, sentinel)) return false
            if (!DatabaseRestoreTransaction(
                    context.getDatabasePath(EntityCatalogStore.DATABASE_NAME),
                ).clearRestored(sentinel.session)
            ) return false
            if (!guardDbPreparedArmStore(context).clear(sentinel.session)) return false
            val cleared = guardDbSentinelStore(context).clear(sentinel.session)
            // The durable absence is for the next process; this one remains maintenance-only.
            return cleared
        }
        val sentinelStore = guardDbSentinelStore(context)
        if (!sentinelStore.promoteArmed(sentinel.session)) return false
        GuardDbProcessAdmission.update(sentinelStore.load())

        // Exact-A refusal is a read-only candidate-admission check performed by installed B in the
        // writer-free maintenance server. It never installs or starts A before premigrate is restored.
        if (status.phase == GuardDbMaintenanceProtocol.Phase.WAIT_A_REFUSAL) return false
        if (!healthPending(status)) return false
        val store = runCatching {
            DatabaseRestoreGuardContext.withBinding(
                DatabaseRestoreGuardBinding(requireNotNull(status.session), status.generation),
            ) { EntityCatalogStore(context.applicationContext) }
        }
            .onFailure { Log.e(TAG, "could not construct startup database owner", it) }
            .getOrNull() ?: return false
        val proof = collectClosedCanonicalGuardDbProof(
            collect = { startupProof(context.applicationContext, store.writableDatabase, status) },
            checkpoint = { checkpointGuardDbTruncate(store.writableDatabase) },
            close = { store.close() },
            stable = { stableGuardDbCanonicalMain(context.applicationContext) },
        )
        if (proof == null) {
            Log.e(TAG, "startup database did not reach closed canonical health proof")
            return false
        }
        val outcome = submitGuardDbStartupHealth(GuardDbMaintenance.client, status, attempts) { proof }
        Log.i(TAG, "startup health generation=${status.generation} outcome=$outcome")
        return false
    }

    private fun startupProof(
        context: Context,
        database: SQLiteDatabase,
        status: GuardDbMaintenanceProtocol.Status,
    ): GuardDbStartupProof {
        val quickCheck = database.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()
        }
        val appState = requireNotNull(readAppStateSemanticProof(database))
        val probe = database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(PROBE_TABLE),
        ).use { cursor ->
            if (cursor.moveToFirst()) GuardDbMaintenanceProtocol.Probe.PRESENT
            else GuardDbMaintenanceProtocol.Probe.ABSENT
        }
        val source = File(context.applicationInfo.sourceDir)
        check(source.isFile)
        val recoveryProof = when {
            status.role == GuardDbMaintenanceProtocol.Role.B -> GuardDbMaintenanceProtocol.RecoveryProof.NA
            status.phase == GuardDbMaintenanceProtocol.Phase.ROLLBACK_DB_RESTORED ->
                GuardDbMaintenanceProtocol.RecoveryProof.BASELINE
            EntityCatalogStore.lastSchemaReconcile?.let { reconcile ->
                reconcile.action == SchemaReconcileAction.RESTORED &&
                    reconcile.fromVersion == status.schema?.plus(1) &&
                    reconcile.toVersion == status.schema && reconcile.restoredVersion == status.schema
            } == true -> GuardDbMaintenanceProtocol.RecoveryProof.RESTORED
            else -> GuardDbMaintenanceProtocol.RecoveryProof.NA
        }
        return GuardDbStartupProof(
            apkSha256 = AppInstaller.sha256(source),
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            schema = database.version,
            quickCheckOk = quickCheck,
            appStateCount = appState.count,
            orderedAppStateSha256 = appState.orderedSha256,
            settingsSemanticSha256 = appState.settingsSha256,
            probe = probe,
            recoveryProof = recoveryProof,
        )
    }

    private fun healthPending(status: GuardDbMaintenanceProtocol.Status): Boolean = status.phase in setOf(
        GuardDbMaintenanceProtocol.Phase.WAIT_A_HEALTH,
        GuardDbMaintenanceProtocol.Phase.WAIT_B_HEALTH,
        GuardDbMaintenanceProtocol.Phase.ROLLBACK_DB_RESTORED,
    )

    private fun reacquireStatus(): GuardDbMaintenanceClient.StatusProbe {
        return try {
            reacquireGuardDbStartupStatus(
                statusProbe = GuardDbMaintenance.client::statusProbe,
                resumeRetainedCurrent = BundledHelperInstaller::resumeRetainedCurrent,
                pause = { Thread.sleep(250L) },
            )
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            GuardDbMaintenanceClient.StatusProbe.Unreachable
        }
    }

}

/**
 * Reacquires Guard status without opening an ordinary helper migration path. An active sentinel may
 * outlive the ephemeral current-build supervisor across an app or supervisor restart, so an exact
 * retained record gets one recovery attempt before startup remains writer-free. A successful resume
 * receives a fresh bounded window so the app independently reacquires status through its normal
 * helper transport.
 */
internal fun reacquireGuardDbStartupStatus(
    statusProbe: () -> GuardDbMaintenanceClient.StatusProbe,
    resumeRetainedCurrent: () -> Boolean,
    pause: () -> Unit,
): GuardDbMaintenanceClient.StatusProbe {
    fun boundedStatusWindow(initial: GuardDbMaintenanceClient.StatusProbe):
        GuardDbMaintenanceClient.StatusProbe {
        var probe = initial
        repeat(7) {
            if (probe !is GuardDbMaintenanceClient.StatusProbe.Unreachable) return probe
            pause()
            probe = statusProbe()
        }
        return probe
    }

    val original = boundedStatusWindow(statusProbe())
    if (original != GuardDbMaintenanceClient.StatusProbe.Unsupported &&
        original != GuardDbMaintenanceClient.StatusProbe.Unreachable
    ) {
        return original
    }
    if (!resumeRetainedCurrent()) return original
    return boundedStatusWindow(statusProbe())
}

/**
 * The helper may only inspect a database after every app SQLite owner is closed.  This seam makes the
 * ordering executable in local tests: collect while open, checkpoint, close even on failure, prove the
 * canonical main file/sidecars are stable, and only then return a proof which may be submitted.
 */
internal fun <T> collectClosedCanonicalGuardDbProof(
    collect: () -> T?,
    checkpoint: () -> Boolean,
    close: () -> Unit,
    stable: () -> Boolean,
): T? {
    var proof: T? = null
    var checkpointed = false
    try {
        proof = collect()
        if (proof != null) checkpointed = checkpoint()
    } catch (_: Throwable) {
        // The caller logs the single held outcome. Closing below is still mandatory.
    }
    val closed = runCatching { close() }.isSuccess
    if (proof == null || !checkpointed || !closed) return null
    return proof.takeIf { runCatching { stable() }.getOrDefault(false) }
}

internal fun exactGuardDbTruncateCheckpoint(values: List<Long>?, hasAdditionalRow: Boolean): Boolean =
    values == listOf(0L, 0L, 0L) && !hasAdditionalRow

internal fun checkpointGuardDbTruncate(database: SQLiteDatabase): Boolean = runCatching {
    database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
        if (!cursor.moveToFirst() || cursor.columnCount != 3) return@use false
        val values = listOf(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
        exactGuardDbTruncateCheckpoint(values, cursor.moveToNext())
    }
}.getOrDefault(false)

internal data class GuardDbCanonicalFileIdentity(
    val device: Long,
    val inode: Long,
    val mode: Int,
    val bytes: Long,
    val sha256: String,
)

internal data class GuardDbCanonicalMainSnapshot(
    val directoryDevice: Long,
    val directoryInode: Long,
    val directoryMode: Int,
    val main: GuardDbCanonicalFileIdentity,
    val wal: GuardDbCanonicalFileIdentity?,
    val shm: GuardDbCanonicalFileIdentity?,
)

internal fun stableGuardDbCanonicalMain(
    context: Context,
    snapshot: (File) -> GuardDbCanonicalMainSnapshot? = ::guardDbCanonicalMainSnapshot,
): Boolean {
    val database = context.getDatabasePath(EntityCatalogStore.DATABASE_NAME)
    val first = snapshot(database) ?: return false
    val second = snapshot(database) ?: return false
    return first == second
}

private fun guardDbCanonicalMainSnapshot(database: File): GuardDbCanonicalMainSnapshot? = runCatching {
    val directory = requireNotNull(database.parentFile)
    val directoryStat = Os.lstat(directory.absolutePath)
    if ((directoryStat.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFDIR ||
        directoryStat.st_uid != Process.myUid() || directoryStat.st_gid != Process.myUid()
    ) return null
    val main = ownedGuardDbFileIdentity(database, allowEmpty = false) ?: return null
    val walFile = File(database.path + "-wal")
    val wal = if (guardDbEntryExists(walFile)) {
        ownedGuardDbFileIdentity(walFile, allowEmpty = true)?.takeIf { it.bytes == 0L } ?: return null
    } else null
    val shmFile = File(database.path + "-shm")
    val shm = if (guardDbEntryExists(shmFile)) {
        ownedGuardDbFileIdentity(shmFile, allowEmpty = true) ?: return null
    } else null
    // Any object at the rollback-journal name is unsafe, including a directory or symlink.
    if (guardDbEntryExists(File(database.path + "-journal"))) return null
    GuardDbCanonicalMainSnapshot(
        directoryDevice = directoryStat.st_dev,
        directoryInode = directoryStat.st_ino,
        directoryMode = directoryStat.st_mode,
        main = main,
        wal = wal,
        shm = shm,
    )
}.getOrNull()

private fun guardDbEntryExists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

private fun ownedGuardDbFileIdentity(file: File, allowEmpty: Boolean): GuardDbCanonicalFileIdentity? = runCatching {
    val stat = Os.lstat(file.absolutePath)
    if ((stat.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFREG || stat.st_nlink != 1L ||
        stat.st_uid != Process.myUid() || stat.st_gid != Process.myUid() ||
        (!allowEmpty && stat.st_size <= 0L)
    ) return null
    GuardDbCanonicalFileIdentity(
        device = stat.st_dev,
        inode = stat.st_ino,
        mode = stat.st_mode,
        bytes = stat.st_size,
        sha256 = AppInstaller.sha256(file),
    )
}.getOrNull()

private const val GUARD_DB_REFUSAL_CHECKPOINT_OPEN_FLAGS =
    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS or
        SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING

/** Exact raw-open seam: tests execute the same flags, checkpoint, close and stability path as production. */
internal fun <T : Any> runGuardDbRefusalCheckpoint(
    open: (Int) -> T,
    checkpoint: (T) -> Boolean,
    close: (T) -> Unit,
    stable: () -> Boolean,
): Boolean {
    var database: T? = null
    return collectClosedCanonicalGuardDbProof(
        collect = {
            database = open(GUARD_DB_REFUSAL_CHECKPOINT_OPEN_FLAGS)
            Unit
        },
        checkpoint = { checkpoint(requireNotNull(database)) },
        close = { close(requireNotNull(database)) },
        stable = stable,
    ) != null
}

/** Refusal has no SQLite owner of its own, but still re-canonicalizes B's main before inventory proof. */
internal fun canonicalizeGuardDbMainForRefusal(context: Context): Boolean {
    val databaseFile = context.getDatabasePath(EntityCatalogStore.DATABASE_NAME)
    return runGuardDbRefusalCheckpoint(
        open = { flags ->
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                flags,
            )
        },
        checkpoint = ::checkpointGuardDbTruncate,
        close = SQLiteDatabase::close,
        stable = { stableGuardDbCanonicalMain(context.applicationContext) },
    )
}

internal fun exactGuardDbFinalStatus(
    context: Context,
    status: GuardDbMaintenanceProtocol.Status,
    sentinel: GuardDbStartupSentinel,
): Boolean {
    val source = File(context.applicationInfo.sourceDir)
    val running = source.takeIf { it.isFile }?.let {
        runCatching {
            GuardDbRunningAppIdentity(
                apkSha256 = AppInstaller.sha256(it),
                versionCode = BuildConfig.VERSION_CODE.toLong(),
            )
        }.getOrNull()
    } ?: return false
    return exactGuardDbFinalStatus(status, sentinel, running)
}

internal data class GuardDbRunningAppIdentity(
    val apkSha256: String,
    val versionCode: Long,
)

internal fun exactGuardDbFinalStatus(
    status: GuardDbMaintenanceProtocol.Status,
    sentinel: GuardDbStartupSentinel,
    running: GuardDbRunningAppIdentity,
): Boolean {
    val terminalOutcome = status.outcome?.let {
        it == GuardDbMaintenanceProtocol.Outcome.CANARY_PASSED || it.name.startsWith("ROLLED_BACK_")
    } == true
    if (status.phase != GuardDbMaintenanceProtocol.Phase.FINALIZED ||
        status.session != sentinel.session || status.bootNonce != sentinel.bootNonce ||
        status.role != GuardDbMaintenanceProtocol.Role.A || status.apkSha256 != sentinel.aSha256 ||
        status.versionCode != sentinel.aVersionCode || status.schema != sentinel.aSchema || !terminalOutcome
    ) return false
    return running.versionCode == sentinel.aVersionCode && running.apkSha256 == sentinel.aSha256
}

internal class GuardDbHealthAttemptGate {
    private var inFlight: GuardDbHealthAttemptKey? = null
    private val completed = linkedSetOf<GuardDbHealthAttemptKey>()

    @Synchronized fun claim(key: GuardDbHealthAttemptKey): Boolean {
        if (key == inFlight || key in completed) return false
        inFlight = key
        return true
    }

    @Synchronized fun retry(key: GuardDbHealthAttemptKey) {
        if (inFlight == key) inFlight = null
    }

    @Synchronized fun complete(key: GuardDbHealthAttemptKey) {
        if (inFlight == key) inFlight = null
        completed += key
        while (completed.size > MAX_COMPLETED_ATTEMPTS) completed.remove(completed.first())
    }

    private companion object { const val MAX_COMPLETED_ATTEMPTS = 16 }
}

internal data class GuardDbHealthAttemptKey(
    val session: String,
    val bootNonce: String,
    val generation: Long,
    val role: GuardDbMaintenanceProtocol.Role,
    val phase: GuardDbMaintenanceProtocol.Phase,
)

internal enum class GuardDbHealthAttemptOutcome { NOT_ELIGIBLE, IN_FLIGHT, RETRYABLE, DURABLE }

internal fun submitGuardDbStartupHealth(
    client: GuardDbMaintenanceClient,
    status: GuardDbMaintenanceProtocol.Status,
    attempts: GuardDbHealthAttemptGate,
    proof: () -> GuardDbStartupProof?,
): GuardDbHealthAttemptOutcome {
    val key = GuardDbHealthAttemptKey(
        status.session ?: return GuardDbHealthAttemptOutcome.NOT_ELIGIBLE,
        status.bootNonce ?: return GuardDbHealthAttemptOutcome.NOT_ELIGIBLE,
        status.generation,
        status.role ?: return GuardDbHealthAttemptOutcome.NOT_ELIGIBLE,
        status.phase,
    )
    if (!attempts.claim(key)) return GuardDbHealthAttemptOutcome.IN_FLIGHT
    val exact = proof() ?: return GuardDbHealthAttemptOutcome.RETRYABLE.also { attempts.retry(key) }
    if (guardDbStartupHealthCommand(status, exact) == null) {
        attempts.retry(key)
        return GuardDbHealthAttemptOutcome.NOT_ELIGIBLE
    }
    val expectedProbe = if (status.role == GuardDbMaintenanceProtocol.Role.B) {
        GuardDbMaintenanceProtocol.Probe.PRESENT
    } else {
        GuardDbMaintenanceProtocol.Probe.ABSENT
    }
    val healthy = exact.quickCheckOk && exact.apkSha256 == status.apkSha256 &&
        exact.versionCode == status.versionCode && exact.schema == status.schema &&
        exact.appStateCount == status.baselineAppStateCount && exact.probe == expectedProbe &&
        exact.recoveryProof == expectedRecoveryProof(status)
    val result = client.health(
        status, requireNotNull(status.role), exact.apkSha256, exact.versionCode, exact.schema,
        healthy, exact.appStateCount, exact.orderedAppStateSha256, exact.settingsSemanticSha256, exact.probe,
        exact.recoveryProof,
    )
    val durable = when (result) {
        is GuardDbMaintenanceProtocol.Result.Accepted ->
            result.generation > status.generation && result.phase !in HEALTH_PENDING_PHASES
        GuardDbMaintenanceProtocol.Result.Indeterminate,
        GuardDbMaintenanceProtocol.Result.Malformed -> {
            val next = (client.statusProbe() as? GuardDbMaintenanceClient.StatusProbe.Valid)?.status
            next != null && next.session == status.session && next.bootNonce == status.bootNonce &&
                next.generation > status.generation && next.phase !in HEALTH_PENDING_PHASES
        }
        else -> false
    }
    return if (durable) {
        attempts.complete(key)
        GuardDbHealthAttemptOutcome.DURABLE
    } else {
        attempts.retry(key)
        GuardDbHealthAttemptOutcome.RETRYABLE
    }
}

private val HEALTH_PENDING_PHASES = setOf(
    GuardDbMaintenanceProtocol.Phase.WAIT_A_HEALTH,
    GuardDbMaintenanceProtocol.Phase.WAIT_B_HEALTH,
    GuardDbMaintenanceProtocol.Phase.ROLLBACK_DB_RESTORED,
)

private fun expectedRecoveryProof(status: GuardDbMaintenanceProtocol.Status): GuardDbMaintenanceProtocol.RecoveryProof =
    when {
        status.role == GuardDbMaintenanceProtocol.Role.B -> GuardDbMaintenanceProtocol.RecoveryProof.NA
        status.phase == GuardDbMaintenanceProtocol.Phase.ROLLBACK_DB_RESTORED ->
            GuardDbMaintenanceProtocol.RecoveryProof.BASELINE
        else -> GuardDbMaintenanceProtocol.RecoveryProof.RESTORED
    }
