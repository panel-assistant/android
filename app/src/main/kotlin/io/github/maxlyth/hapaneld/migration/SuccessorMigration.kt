package io.github.maxlyth.hapaneld.migration

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
    enum class Step { PULL, VERIFY, RELEASE, AWAIT_PORT, RESTORE, CLAIM, CONFIRM, UNINSTALL }

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

        /** SHA-256 of the stored receipt archive, or null when there is none. */
        fun receiptSha256(): String?

        /** Pull `POST /api/v1/backup` from the legacy app into the receipt; the new SHA-256 or null. */
        suspend fun pullReceipt(): String?

        /** Why the stored receipt is not a complete backup of this device, or null when it is. */
        fun receiptRefusal(): String?

        /** Delete a receipt that failed verification. */
        fun discardReceipt()

        /** True when the legacy app reports its durable retired marker. */
        suspend fun legacyRetired(): Boolean

        /** Ask the legacy app to release the panel; null when admitted, otherwise the refusal. */
        suspend fun requestRelease(): String?

        /** True when nothing is listening on the panel's HTTP port. */
        fun portFree(): Boolean

        /** Restore the receipt in migration mode and wait for it to become durable. */
        suspend fun restoreReceipt(): Boolean

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
        if (markers.complete()) return Result.Complete
        val started = Step.entries.any(markers::done)
        if (!started && !ports.legacyInstalled()) return Result.NotNeeded

        pull()?.let { return it }
        verify()?.let { return it }
        release()?.let { return it }
        if (!markers.done(Step.RESTORE)) {
            if (ports.environment() == Environment.PASSIVE) {
                // Asked only while passive: once the held service runs, this app owns the port itself.
                if (!ports.portFree()) return Result.Waiting(Step.AWAIT_PORT, "legacy app still holds the HTTP port")
                return Result.NeedsHeldService
            }
            if (!ports.restoreReceipt()) return Result.Waiting(Step.RESTORE, "restore did not complete")
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
        // While the legacy app still runs, every pass pulls again: a release that was refused for days
        // must not end with a restore of the state the panel had when the successor was first started.
        val sha = ports.pullReceipt() ?: return Result.Waiting(Step.PULL, "backup could not be pulled")
        if (!markers.record(Step.PULL, sha)) return Result.Waiting(Step.PULL, "marker not durable")
        return null
    }

    private fun verify(): Result? {
        val pulled = markers.value(Step.PULL)
        if (markers.value(Step.VERIFY) == pulled) return null
        ports.receiptRefusal()?.let { refusal ->
            // A receipt that does not verify is worthless as a receipt. While the legacy app still
            // serves backups, drop it so the next pass pulls a fresh one instead of failing forever.
            if (!markers.done(Step.RELEASE)) ports.discardReceipt()
            return Result.Waiting(Step.VERIFY, refusal)
        }
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

    private fun claim(): Result? {
        if (markers.done(Step.CLAIM)) return null
        val failed = ports.missingGrants().filterNot(ports::claimGrant)
        if (failed.isNotEmpty()) return Result.Waiting(Step.CLAIM, "grants not claimed: ${failed.sorted().joinToString(",")}")
        if (ports.missingGrants().isNotEmpty()) return Result.Waiting(Step.CLAIM, "grants did not read back")
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
            if (ports.receiptSha256() != markers.value(Step.VERIFY)) {
                return Result.Waiting(Step.UNINSTALL, "verified receipt is missing")
            }
            if (!ports.uninstallLegacy() || ports.legacyInstalled()) {
                return Result.Waiting(Step.UNINSTALL, "legacy package was not removed")
            }
        }
        if (!markers.record(Step.UNINSTALL) || !markers.recordComplete()) {
            return Result.Waiting(Step.UNINSTALL, "marker not durable")
        }
        return Result.Complete
    }
}
