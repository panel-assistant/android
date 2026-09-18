package io.github.maxlyth.hapaneld.migration

/**
 * Bridge side of the application-id migration: install the successor beside this app and start it.
 *
 * The bridge never gives anything up here. It installs a second package and launches it; HOME, the
 * kiosk loop and port 8888 stay with the bridge until the successor has pulled and verified a backup
 * and asks for them through the release endpoint. Every refusal therefore leaves the panel running
 * the bridge exactly as before, and the next pass simply tries again.
 *
 * Two preconditions are applied and then read back before the launch, because each one is what stops
 * a launched successor from harming a working panel: the running root helper must authenticate both
 * application ids (the successor has no other root channel where `su` authorises a single uid), and
 * the successor must be a kiosk companion the return loop honours, or a locked panel snaps it away.
 *
 * An installed successor is trusted only under the pinned signer. A package that merely carries the
 * successor's id is never launched, never handed the release token and never replaced silently.
 */
internal class SuccessorHandoff(private val ports: Ports) {
    data class InstalledSuccessor(val version: String, val signers: Set<String>)

    interface Ports {
        /** True once the release endpoint has retired this bridge; nothing is ever offered again. */
        fun retired(): Boolean

        /** Why the running helper cannot carry a handover, or null when it is confirmed dual-uid. */
        fun helperRefusal(): String?

        /** The installed successor package, or null when it is absent. */
        fun installedSuccessor(): InstalledSuccessor?

        /** The one signer an installed successor must have, exactly and alone. */
        fun trustedSigner(): String

        /** This bridge's versionName: the successor version it installs. */
        fun ownVersion(): String

        /** Negative when [left] is older than [right]; null when the two cannot be ordered. */
        fun compareVersions(left: String, right: String): Int?

        /** The successor asset of this bridge's own release, or null when that release carries none. */
        fun successorAssetUrl(): String?

        /** Pin-verified install of the successor package; null on success, otherwise the failure. */
        suspend fun installSuccessor(url: String): String?

        fun companionPackages(): Set<String>
        fun addCompanionPackage(pkg: String)

        /** Start the successor's launcher activity through a privileged route. */
        fun launchSuccessor(): Boolean

        /** Hand the successor the one-time proof it must present to the release endpoint. */
        fun deliverReleaseToken(): Boolean
    }

    sealed interface Outcome {
        /** Log-safe reason; never shown as a user-facing failure. */
        val detail: String

        data object Retired : Outcome { override val detail = "bridge is retired" }
        data class HelperNotConfirmed(override val detail: String) : Outcome
        data object UntrustedSuccessor : Outcome {
            override val detail = "installed successor is not signed by the pinned signer"
        }
        data object NoSuccessorAsset : Outcome { override val detail = "this release carries no successor" }
        data class InstallFailed(override val detail: String) : Outcome
        data object CompanionNotHonoured : Outcome {
            override val detail = "successor was not accepted as a kiosk companion"
        }
        data object LaunchFailed : Outcome { override val detail = "successor could not be started" }
        data object TokenNotDelivered : Outcome {
            override val detail = "successor started; release token was not delivered"
        }
        data object Launched : Outcome { override val detail = "successor started" }
    }

    suspend fun offer(successorPackage: String): Outcome {
        if (ports.retired()) return Outcome.Retired
        // Checked before anything is installed: a panel whose helper cannot serve the successor keeps
        // exactly one package, not a second one that could never take over.
        ports.helperRefusal()?.let { return Outcome.HelperNotConfirmed(it) }

        val present = ports.installedSuccessor()
        if (present != null && !trusted(present)) return Outcome.UntrustedSuccessor
        // Absent or older only. A successor newer than this bridge is kept: the installer allows
        // downgrades, and reinstalling would also kill a migration that is already running.
        if (present == null || ports.compareVersions(present.version, ports.ownVersion())?.let { it < 0 } == true) {
            val url = ports.successorAssetUrl() ?: return Outcome.NoSuccessorAsset
            ports.installSuccessor(url)?.let { return Outcome.InstallFailed(it) }
            val installed = ports.installedSuccessor()
            if (installed == null || !trusted(installed) || installed.version != ports.ownVersion()) {
                return Outcome.InstallFailed("installed successor was not confirmed")
            }
        }

        // The helper may have been replaced while the APK downloaded; the launch gate is the state now.
        ports.helperRefusal()?.let { return Outcome.HelperNotConfirmed(it) }
        if (successorPackage !in ports.companionPackages()) ports.addCompanionPackage(successorPackage)
        if (successorPackage !in ports.companionPackages()) return Outcome.CompanionNotHonoured

        if (!ports.launchSuccessor()) return Outcome.LaunchFailed
        // After the start: a package that has never run is in the stopped state and receives nothing.
        return if (ports.deliverReleaseToken()) Outcome.Launched else Outcome.TokenNotDelivered
    }

    private fun trusted(successor: InstalledSuccessor): Boolean =
        successor.signers.size == 1 && successor.signers.single().equals(ports.trustedSigner(), ignoreCase = true)
}
