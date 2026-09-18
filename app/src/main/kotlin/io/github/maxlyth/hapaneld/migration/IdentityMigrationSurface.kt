package io.github.maxlyth.hapaneld.migration

/**
 * What the HTTP surface needs from the application-id migration. The service supplies the real
 * implementation for its build identity; [NONE] is a panel that is not migrating, which answers every
 * question the way a build without the migration would.
 */
internal interface IdentityMigrationSurface {
    /** Successor: true only while the migration is waiting to restore the receipt it pulled. */
    fun restoreOpen(): Boolean = false

    /** Successor: the migration-mode restore is durable. */
    fun onRestoreCommitted() {}

    /** Bridge: install and start the successor now. Null on a build that is not the bridge. */
    suspend fun offer(): SuccessorHandoff.Outcome? = null

    /** Bridge: hand the panel over to the successor that presents [token]. */
    suspend fun release(token: String?, loopback: Boolean): BridgeRelease.Outcome =
        BridgeRelease.Outcome.Refused(BridgeRelease.Refusal.NOT_A_BRIDGE)

    companion object {
        val NONE: IdentityMigrationSurface = object : IdentityMigrationSurface {}
    }
}

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
