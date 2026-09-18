package io.github.maxlyth.hapaneld.migration

import android.content.Context
import io.github.maxlyth.hapaneld.AppIdentity
import io.github.maxlyth.hapaneld.migration.SuccessorMigration.Step
import io.github.maxlyth.hapaneld.util.AppInstaller
import java.util.concurrent.atomic.AtomicBoolean

/** Reads the durable facts behind [startDisposition]. Cheap, and safe before any app state exists. */
internal object IdentityMigrationGate {
    /**
     * True for the life of a process that started as a [StartDisposition.HELD_SUCCESSOR]. MQTT and mDNS
     * consult it at their own start, because several owners can start them and the point of the hold is
     * that none of them connects or advertises under the identity this app generated before it has
     * restored the panel's real one. The process restarts once the restore is durable, so it never clears.
     */
    private val held = AtomicBoolean(false)

    fun holdsNetworkIdentity(): Boolean = held.get()

    /** The migration is complete or was never needed; every activity callback asks, so it is cached. */
    private val settledNormal = AtomicBoolean(false)

    fun disposition(context: Context): StartDisposition {
        if (AppIdentity.IS_BRIDGE) {
            return startDisposition(
                isBridge = true,
                bridgeRetired = BridgeRetirement.isRetired(context),
                legacyInstalled = true,
                migrationStarted = false,
                migrationComplete = false,
                released = false,
                restored = false,
            )
        }
        if (settledNormal.get()) return StartDisposition.NORMAL
        val state = MigrationState.of(context)
        if (state.complete()) {
            settledNormal.set(true)
            return StartDisposition.NORMAL
        }
        // A successor that starts with no legacy package beside it never migrates, now or later. Latch
        // that durably: a legacy package installed afterwards (an old deploy script, a sideload) must
        // not turn a configured panel passive and then restore a stranger's defaults over it.
        if (!state.started() && !legacyInstalled(context)) {
            if (state.recordComplete()) settledNormal.set(true)
            return StartDisposition.NORMAL
        }
        return startDisposition(
            isBridge = false,
            bridgeRetired = false,
            legacyInstalled = legacyInstalled(context),
            migrationStarted = state.started(),
            migrationComplete = state.complete(),
            released = state.done(Step.RELEASE),
            restored = state.done(Step.RESTORE),
        ).also { if (it == StartDisposition.HELD_SUCCESSOR) held.set(true) }
    }

    fun legacyInstalled(context: Context): Boolean =
        AppInstaller.installedSigners(context, AppIdentity.LEGACY) != null

    /** A successor whose migration is neither unnecessary nor finished. */
    fun migrationPending(context: Context): Boolean {
        if (AppIdentity.IS_BRIDGE) return false
        val state = MigrationState.of(context)
        return !state.complete() && (state.started() || legacyInstalled(context))
    }
}
