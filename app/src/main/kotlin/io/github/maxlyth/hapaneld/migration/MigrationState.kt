package io.github.maxlyth.hapaneld.migration

import android.content.Context
import io.github.maxlyth.hapaneld.migration.SuccessorMigration.Step
import java.io.File

/** The successor's durable per-step records and receipt, all under `noBackupFilesDir`. */
internal class MigrationState(noBackupFilesDir: File) : SuccessorMigration.Markers {
    private val directory = noBackupFilesDir.resolve(DIRECTORY)

    /** The backup pulled from the legacy app, kept until the migration is complete. */
    val receipt: File = directory.resolve("receipt.zip")

    private fun marker(name: String) = DurableTextFile(directory.resolve("$name.v1"), maxChars = 256)
    private fun marker(step: Step) = marker("step-${step.name.lowercase().replace('_', '-')}")

    override fun done(step: Step): Boolean = marker(step).exists()
    override fun value(step: Step): String? = marker(step).read()
    override fun record(step: Step, value: String): Boolean = marker(step).write(value)
    override fun complete(): Boolean = marker(COMPLETE).exists()
    override fun recordComplete(): Boolean = marker(COMPLETE).write("done")

    /** Any step has been recorded: a migration is in progress even if the legacy package has gone. */
    fun started(): Boolean = Step.entries.any(::done)

    companion object {
        private const val DIRECTORY = "identity-migration"
        private const val COMPLETE = "complete"

        fun of(context: Context): MigrationState = MigrationState(context.noBackupFilesDir)
    }
}

/**
 * What a start of the ordinary service must become on this build, decided from durable facts only so
 * that every route into the app (boot, launcher, sticky restart, an explicit intent) agrees.
 */
internal enum class StartDisposition {
    /** Not migrating: exactly the app it always was. */
    NORMAL,

    /** A bridge that has handed the panel over. It stays idle until its package is removed. */
    RETIRED_BRIDGE,

    /** A successor that must not bind, advertise, connect or claim anything yet. */
    PASSIVE_SUCCESSOR,

    /** A successor starting its ordinary service to restore, with MQTT and mDNS held. */
    HELD_SUCCESSOR,
}

internal fun startDisposition(
    isBridge: Boolean,
    bridgeRetired: Boolean,
    legacyInstalled: Boolean,
    migrationStarted: Boolean,
    migrationComplete: Boolean,
    released: Boolean,
    restored: Boolean,
): StartDisposition = when {
    isBridge -> if (bridgeRetired) StartDisposition.RETIRED_BRIDGE else StartDisposition.NORMAL
    migrationComplete -> StartDisposition.NORMAL
    !migrationStarted && !legacyInstalled -> StartDisposition.NORMAL
    restored -> StartDisposition.NORMAL
    released -> StartDisposition.HELD_SUCCESSOR
    else -> StartDisposition.PASSIVE_SUCCESSOR
}
