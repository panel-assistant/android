package io.panelassistant.android.migration

import android.content.Context
import android.os.Build
import io.panelassistant.android.AppIdentity
import io.panelassistant.android.BuildConfig
import io.panelassistant.android.Config
import io.panelassistant.android.control.SystemController
import io.panelassistant.android.util.AppInstaller
import io.panelassistant.android.util.HelperClient
import io.panelassistant.android.util.InstallOutcome
import io.panelassistant.android.util.SelfUpdater
import io.panelassistant.android.util.UpdateChecker
import io.panelassistant.android.util.dualUidHelperRefusal

/** The bridge's real [SuccessorHandoff.Ports]: release catalog, pinned installer, helper and config. */
internal class AndroidSuccessorHandoffPorts(
    private val context: Context,
    private val config: Config,
    private val system: SystemController,
) : SuccessorHandoff.Ports {
    override fun retired(): Boolean = BridgeRetirement.isRetired(context)

    override fun helperRefusal(): String? =
        dualUidHelperRefusal(HelperClient.helperStatus(), BuildConfig.HELPER_BUILD_ID, context.packageName)

    override fun installedSuccessor(): SuccessorHandoff.InstalledSuccessor? {
        val signers = AppInstaller.installedSigners(context, AppIdentity.SUCCESSOR) ?: return null
        @Suppress("DEPRECATION")
        val code = runCatching {
            val info = context.packageManager.getPackageInfo(AppIdentity.SUCCESSOR, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        }.getOrDefault(-1L)
        return SuccessorHandoff.InstalledSuccessor(
            AppInstaller.installedVersion(context, AppIdentity.SUCCESSOR),
            signers,
            code,
        )
    }

    override fun trustedSigner(): String = AppInstaller.MIGRATION_SIGNER

    override fun ownVersion(): String = BuildConfig.VERSION_NAME

    override fun ownVersionCode(): Long = BuildConfig.VERSION_CODE.toLong()

    override fun compareVersions(left: String, right: String): Int? = UpdateChecker.compareVersions(left, right)

    override fun successorAssetUrl(): String? = SelfUpdater.successorAssetUrl(BuildConfig.VERSION_NAME)

    override suspend fun installSuccessor(url: String): String? =
        when (val outcome = AppInstaller.install(context, url, AppInstaller.migrationPin(AppIdentity.SUCCESSOR))) {
            InstallOutcome.Succeeded -> null
            is InstallOutcome.Failure -> outcome.message
        }

    // The kiosk return loop reads this same getter on every poll, so reading the package back here is
    // the confirmation that the loop will leave the successor in the foreground.
    override fun companionPackages(): Set<String> = config.kioskCompanionPackages

    override fun addCompanionPackage(pkg: String) {
        config.setKioskCompanionPackages((config.kioskCompanionPackages + pkg).joinToString(","))
    }

    override fun launchSuccessor(): Boolean = system.launchPanelApp(AppIdentity.SUCCESSOR)

    override fun deliverReleaseToken(): Boolean {
        val token = ReleaseToken.of(context).ensure() ?: return false
        return MigrationTokenReceiver.deliver(context, token, config.httpPort, config.deviceUid)
    }
}
