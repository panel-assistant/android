package io.github.maxlyth.hapaneld.migration

import kotlinx.coroutines.CompletableDeferred

/**
 * What the HTTP surface needs from the application-id migration. The service supplies the real
 * implementation for its build identity; [NONE] is a panel that is not migrating, which answers every
 * question the way a build without the migration would.
 */
internal interface IdentityMigrationSurface {
    /** Active bridge with a confirmed dual-uid helper; null means LAN migration upload is unavailable. */
    fun successorUploadCapability(): SuccessorUploadCapability? = null

    /** Continue only from an installed successor; this path must never resolve a release asset. */
    suspend fun offerInstalledOnly(): SuccessorHandoff.Outcome? = null

    /** Successor: true only while the migration is waiting to restore the receipt it pulled. */
    fun restoreOpen(): Boolean = false

    /**
     * Successor: claim the attempt this restore request answers, at admission rather than at the end.
     * The successor opens a fresh wait for every attempt, so a restore that outlives its own wait must
     * still answer the attempt that started it and never the one that replaced it.
     */
    fun claimRestoreAttempt(): RestoreAttempt = RestoreAttempt.NONE

    /** Bridge: install and start the successor now. Null on a build that is not the bridge. */
    suspend fun offer(): SuccessorHandoff.Outcome? = null

    /** Bridge: hand the panel over to the successor that presents [token]. */
    suspend fun release(token: String?, loopback: Boolean): BridgeRelease.Outcome =
        BridgeRelease.Outcome.Refused(BridgeRelease.Refusal.NOT_A_BRIDGE)

    companion object {
        val NONE: IdentityMigrationSurface = object : IdentityMigrationSurface {}
    }
}

internal data class SuccessorUploadCapability(val pkg: String, val version: String, val versionCode: Long, val signer: String)

/**
 * One migration-mode restore attempt's answer. Reporting is idempotent and the first report wins, so
 * the restore job may report its real outcome and the job's completion handler may report a failure
 * behind it without the second overwriting the first.
 */
internal fun interface RestoreAttempt {
    /** The restore ended; [succeeded] only when every part of it is durable. */
    fun finished(succeeded: Boolean)

    companion object {
        /** A build that is not migrating, and every restore that is not this app's own migration. */
        val NONE: RestoreAttempt = RestoreAttempt {}
    }
}

/**
 * The successor's restore attempts, one wait at a time. [begin] opens the wait the state machine will
 * await; [claim] hands the server the attempt that is open when it admits a request, so an attempt that
 * outlives its own wait cannot answer the next one.
 */
internal class RestoreAttempts {
    private val current = java.util.concurrent.atomic.AtomicReference(CompletableDeferred<Boolean>())

    fun begin(): CompletableDeferred<Boolean> = CompletableDeferred<Boolean>().also(current::set)

    fun claim(): RestoreAttempt = current.get().let { attempt -> RestoreAttempt { attempt.complete(it) } }
}

/**
 * Whether this restore request answers the successor's open wait. A dry run is admitted to migration
 * mode and writes nothing, so it must leave the wait for the restore that will actually run.
 */
internal fun claimsRestoreAttempt(migrationRestore: Boolean, dryRun: Boolean): Boolean =
    migrationRestore && !dryRun

/** How a restore request relates to migration mode. */
internal enum class MigrationRestoreAdmission { NOT_REQUESTED, ADMITTED, REFUSED }

/**
 * Migration mode widens what a restore writes back, so it is admitted only to this app's own migration:
 * requested explicitly, from loopback, while the successor's state machine holds the restore step open.
 * Any other restore, on any panel, is exactly the restore it was before.
 */
internal fun migrationRestoreAdmission(
    requested: Boolean,
    loopbackPeer: Boolean,
    restoreOpen: Boolean,
): MigrationRestoreAdmission = when {
    !requested -> MigrationRestoreAdmission.NOT_REQUESTED
    loopbackPeer && restoreOpen -> MigrationRestoreAdmission.ADMITTED
    else -> MigrationRestoreAdmission.REFUSED
}
