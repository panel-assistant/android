package io.panelassistant.android.migration

/**
 * Successor side of the application-id migration: one re-entrant pass over a fixed sequence of steps.
 *
 * Every step is idempotent and records a durable marker only after its effect has been observed, so a
 * process death at any instant is followed by a pass that skips what is proven done and repeats what
 * is not. Nothing the legacy package holds is taken before it has released it, and the legacy package
 * is removed last, only after this app has been confirmed healthy, connected and resolving as HOME by
 * a fresh query made in the same pass as the removal.
 *
 * The pass never loops or sleeps on its own. A step that cannot complete yet returns [Result.Waiting]
 * and the caller decides when to run another pass; a refused release in particular leaves this app
 * passive, with nothing bound, advertised or claimed.
 */
/**
 * Whether the platform's default HOME, [homePackage], is safe to keep once [legacy] is removed.
 *
 * HOME must name a real package that will still exist. On a kiosk panel that is this app, [own], which
 * the legacy app hands over and this app otherwise claims. A panel whose owner chose another launcher
 * keeps it: the migration moves what the legacy app held and nothing else. The platform's resolver
 * (`android`) and an unanswered query are not a HOME, and the legacy package is the one answer that
 * removal would turn into a panel with nothing to show.
 */
internal fun homeSettled(homePackage: String?, own: String, legacy: String): Boolean =
    homePackage == own || (homePackage != null && homePackage != legacy && homePackage != "android")

internal class SuccessorMigration(private val ports: Ports, private val markers: Markers) {
    enum class Step { PULL, VERIFY, RELEASE, GRANT, AWAIT_PORT, RESTORE, CLAIM, CONFIRM, UNINSTALL }

    /** Where this pass is running, which bounds how far it may go. */
    enum class Environment {
        /** Before the legacy app has released the panel: no server, no MQTT, no mDNS, no kiosk. */
        PASSIVE,

        /** The ordinary service, started with MQTT and mDNS held until the restore is durable. */
        HELD_SERVICE,

        /** The ordinary service running from the restored configuration. */
        SERVICE,
    }

    /** Durable per-step records; presence means the step's effect was observed. */
    interface Markers {
        fun done(step: Step): Boolean
        fun value(step: Step): String?
        fun record(step: Step, value: String = "done"): Boolean

        /** The migration finished, or was never needed; nothing runs again. */
        fun complete(): Boolean
        fun recordComplete(): Boolean
    }

    interface Ports {
        fun environment(): Environment
        fun legacyInstalled(): Boolean

        /** The legacy app has delivered the token its release endpoint will ask for. */
        fun releaseTokenHeld(): Boolean

        /** SHA-256 of the stored receipt archive, or null when there is none. */
        fun receiptSha256(): String?

        /**
         * Pull `POST /api/v1/backup` from the legacy app and, only if the pulled archive verifies,
         * make it the receipt; the new SHA-256, or null when the stored receipt was left as it was. A
         * pull taken while the legacy app is shutting down can be incomplete, and it must never replace
         * a good receipt that may turn out to be the last one obtainable.
         */
        suspend fun pullReceipt(): String?

        /** Why the stored receipt is not a complete backup of this device, or null when it is. */
        fun receiptRefusal(): String?

        /** True when the legacy app reports its durable retired marker. */
        suspend fun legacyRetired(): Boolean

        /** Ask the legacy app to release the panel; null when admitted, otherwise the refusal. */
        suspend fun requestRelease(): String?

        /** True when nothing is listening on the panel's HTTP port. */
        fun portFree(): Boolean

        /**
         * Restore the receipt in migration mode and wait for it to become durable; null when it did,
         * otherwise why not, carrying the restore's own refusal rather than a bare failure.
         */
        suspend fun restoreReceipt(): String?

        /** Grants the legacy app holds that this app does not hold yet. */
        fun missingGrants(): Set<String>

        /** Claim one grant through the helper. */
        fun claimGrant(grant: String): Boolean

        /** Assert this app as HOME through the helper. */
        fun claimHome(): Boolean

        /** A fresh HOME query is [homeSettled]: removing the legacy package cannot strand the launcher. */
        fun homeSettled(): Boolean
        fun healthy(): Boolean
        fun mqttConverged(): Boolean

        /** Remove the legacy package through the helper. */
        fun uninstallLegacy(): Boolean

        /** Delete the receipt, the release token and the legacy port once the migration is complete. */
        fun forgetSecrets()
    }

    sealed interface Result {
        /** There is no legacy package and no migration in progress. */
        data object NotNeeded : Result
        data object Complete : Result

        /** [step] cannot complete yet; run another pass later. */
        data class Waiting(val step: Step, val reason: String) : Result

        /** The panel has been released; the ordinary service must start, held, to restore. */
        data object NeedsHeldService : Result

        /** The restore is durable; the process must restart to run from it. */
        data object NeedsRestart : Result
    }

    suspend fun pass(): Result {
        if (markers.complete()) {
            // Idempotent, and repeated here for a process that died between completing and forgetting.
            ports.forgetSecrets()
            return Result.Complete
        }
        val started = Step.entries.any(markers::done)
        if (!started && !ports.legacyInstalled()) return Result.NotNeeded

        pull()?.let { return it }
        verify()?.let { return it }
        release()?.let { return it }
        grant()?.let { return it }
        if (!markers.done(Step.RESTORE)) {
            if (ports.environment() == Environment.PASSIVE) {
                // Asked only while passive: once the held service runs, this app owns the port itself.
                if (!ports.portFree()) return Result.Waiting(Step.AWAIT_PORT, "legacy app still holds the HTTP port")
                return Result.NeedsHeldService
            }
            ports.restoreReceipt()?.let { return Result.Waiting(Step.RESTORE, it) }
            if (!markers.record(Step.RESTORE)) return Result.Waiting(Step.RESTORE, "marker not durable")
        }
        if (ports.environment() != Environment.SERVICE) return Result.NeedsRestart
        claim()?.let { return it }
        confirm()?.let { return it }
        return uninstall()
    }

    private suspend fun pull(): Result? {
        val recorded = markers.value(Step.PULL)
        val kept = recorded != null && recorded == ports.receiptSha256()
        // Once the legacy app has retired its server is gone, so a receipt cannot be pulled again.
        // Losing it then is unrecoverable by this machine, and the legacy package is never removed.
        if (markers.done(Step.RELEASE) || !ports.legacyInstalled() || ports.legacyRetired()) {
            return if (kept) null else Result.Waiting(Step.PULL, "no receipt was kept and none can be pulled")
        }
        // A backup occupies the legacy app's destructive-operation lane and shows there as an operation.
        // Without the token no release can follow, so there is nothing to take one for yet.
        if (!ports.releaseTokenHeld()) return Result.Waiting(Step.PULL, "no authenticated installation identity and release token; update the legacy app before handover")
        // While the legacy app still runs, every pass pulls again: a release that was refused for days
        // must not end with a restore of the state the panel had when the successor was first started.
        val sha = ports.pullReceipt() ?: return Result.Waiting(Step.PULL, "backup could not be pulled")
        if (!markers.record(Step.PULL, sha)) return Result.Waiting(Step.PULL, "marker not durable")
        return null
    }

    private fun verify(): Result? {
        val pulled = markers.value(Step.PULL)
        if (markers.value(Step.VERIFY) == pulled) return null
        // Nothing is deleted on a refusal. While the legacy app still runs the next pass pulls again
        // anyway, and once it has retired a receipt that cannot be replaced must never be discarded.
        ports.receiptRefusal()?.let { return Result.Waiting(Step.VERIFY, it) }
        if (!markers.record(Step.VERIFY, requireNotNull(pulled))) return Result.Waiting(Step.VERIFY, "marker not durable")
        return null
    }

    private suspend fun release(): Result? {
        if (markers.done(Step.RELEASE)) return null
        if (ports.legacyInstalled() && !ports.legacyRetired()) {
            ports.requestRelease()?.let { return Result.Waiting(Step.RELEASE, it) }
            // Admission is not retirement: the legacy app retires after its service has torn down.
            if (!ports.legacyRetired()) return Result.Waiting(Step.RELEASE, "legacy app has not retired yet")
        }
        if (!markers.record(Step.RELEASE)) return Result.Waiting(Step.RELEASE, "marker not durable")
        return null
    }

    /**
     * Grants come before the restore, not after it. The restore applies settings live, and a setting
     * such as the overlay navigation bar is refused without the grant behind it, which fails the whole
     * restore. They come after the release so that a refused successor has still changed nothing.
     */
    private fun grant(): Result? {
        if (markers.done(Step.GRANT)) return null
        val failed = ports.missingGrants().filterNot(ports::claimGrant)
        if (failed.isNotEmpty()) return Result.Waiting(Step.GRANT, "grants not claimed: ${failed.sorted().joinToString(",")}")
        if (ports.missingGrants().isNotEmpty()) return Result.Waiting(Step.GRANT, "grants did not read back")
        if (!markers.record(Step.GRANT)) return Result.Waiting(Step.GRANT, "marker not durable")
        return null
    }

    private fun claim(): Result? {
        if (markers.done(Step.CLAIM)) return null
        if (!ports.homeSettled() && !(ports.claimHome() && ports.homeSettled())) {
            return Result.Waiting(Step.CLAIM, "HOME was not claimed")
        }
        if (!markers.record(Step.CLAIM)) return Result.Waiting(Step.CLAIM, "marker not durable")
        return null
    }

    private fun confirm(): Result? {
        if (markers.done(Step.CONFIRM)) return null
        if (!ports.healthy()) return Result.Waiting(Step.CONFIRM, "health check failed")
        if (!ports.mqttConverged()) return Result.Waiting(Step.CONFIRM, "MQTT has not converged")
        if (!ports.homeSettled()) return Result.Waiting(Step.CONFIRM, "HOME does not resolve to this app")
        if (!markers.record(Step.CONFIRM)) return Result.Waiting(Step.CONFIRM, "marker not durable")
        return null
    }

    private fun uninstall(): Result {
        if (ports.legacyInstalled()) {
            // Asked again here, not read from the CONFIRM marker: removing the legacy package while HOME
            // still names it would strand the launcher, whatever an earlier pass observed.
            if (!ports.homeSettled()) return Result.Waiting(Step.UNINSTALL, "HOME does not resolve to this app")
            if (!ports.uninstallLegacy() || ports.legacyInstalled()) {
                return Result.Waiting(Step.UNINSTALL, "legacy package was not removed")
            }
        }
        if (!markers.record(Step.UNINSTALL) || !markers.recordComplete()) {
            return Result.Waiting(Step.UNINSTALL, "marker not durable")
        }
        // The receipt is a plaintext backup holding the broker password and the Home Assistant tokens.
        // It was kept as long as it could be the only copy; it is that no longer.
        ports.forgetSecrets()
        return Result.Complete
    }
}
