package io.panelassistant.android.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HA Companion app installer / updater. The panels have no Play Store, so the **minimal**
 * Companion never auto-updates — ha-paneld is the only update path. Self-heals a missing or
 * out-of-date Companion via the shared [AppInstaller] (pinned signer + package allowlist). Leaves a
 * Play-managed *full* Companion alone. Network + su — call OFF the main / MQTT thread.
 */
object CompanionInstaller {
    const val FULL_PKG = "io.homeassistant.companion.android"
    const val MINIMAL_PKG = "io.homeassistant.companion.android.minimal"
    val SUPPORTED_PACKAGES = listOf(FULL_PKG, MINIMAL_PKG)

    internal data class RendererChoice(val packageName: String, val label: String)

    private const val TAG = "ha-paneld/companion"
    private const val REPO = "home-assistant/android"
    private val APK_MATCH: (String) -> Boolean = { it == "app-minimal-release.apk" }

    /** The installed Companion package (full or minimal), or null if neither is present. */
    fun installedPkg(context: Context): String? =
        SUPPORTED_PACKAGES.firstOrNull {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }

    fun installedPackages(context: Context): Set<String> = installedPackages { packageName ->
        context.packageManager.getPackageInfo(packageName, 0)
    }

    /** Probe every supported variant independently so one installed Companion cannot mask the other. */
    internal fun installedPackages(requireInstalled: (String) -> Unit): Set<String> =
        SUPPORTED_PACKAGES.filterTo(linkedSetOf()) { packageName ->
            runCatching { requireInstalled(packageName) }.isSuccess
        }

    /** Stable Configure choices for the installed supported Companion variants. The authoritative
     *  package set and labels stay on the server; arbitrary launchable apps are not renderers. */
    internal fun rendererChoices(installedPackages: Set<String>): List<RendererChoice> =
        SUPPORTED_PACKAGES.filter { it in installedPackages }.map { packageName ->
            RendererChoice(
                packageName = packageName,
                label = when (packageName) {
                    FULL_PKG -> "Home Assistant Companion (full)"
                    MINIMAL_PKG -> "Home Assistant Companion (minimal)"
                    else -> error("unsupported Companion package")
                },
            )
        }

    /** Up to [limit] recent versions on [channel] for the Install-tab picker. Releases above this
     *  device's safety cap remain visible for their notes but are not installable. */
    fun versions(
        channel: String,
        limit: Int = 10,
        maxVersion: String?,
    ): List<ReleaseCatalog.Version> {
        val versions = ReleaseCatalog.list(REPO, channel, limit, APK_MATCH, maxVersion = maxVersion) {
            UpdateChecker.stripVariant(it.removePrefix("v"))
        }
        // The known-good ceiling may age beyond the bounded recent pages. Reuse the installer's
        // exact-cap lookup so the picker still offers that release when it is published.
        if (maxVersion == null || versions.any { it.installable }) return versions
        val target = chooseTargetWithExact(versions, maxVersion) { tag -> ReleaseCatalog.apkUrl(REPO, tag, APK_MATCH) }
            ?: return versions
        return versions + ReleaseCatalog.Version(target.version, target.tag, target.releaseUrl, true, target.apkUrl)
    }

    /** A release chosen for installation. [newestVersion] remains the channel head even when [version]
     *  is an older, profile-capped target, so callers can explain why the pin applied. */
    internal data class Target(
        val version: String,
        val apkUrl: String,
        val releaseUrl: String,
        val newestVersion: String,
        val capped: Boolean,
        /** Exact source tag of [version]'s release, retained so an install can name it. */
        val tag: String,
    )

    private fun catalog(channel: String, limit: Int): List<ReleaseCatalog.Version> =
        ReleaseCatalog.list(REPO, channel, limit, APK_MATCH) { UpdateChecker.stripVariant(it.removePrefix("v")) }

    /** Pick one coherent release/asset target. If the channel head is within the cap but lacks its APK,
     *  fail rather than silently falling back. When the head exceeds the cap, select the newest complete
     *  release at or below it. */
    internal fun chooseTarget(versions: List<ReleaseCatalog.Version>, maxVersion: String?): Target? {
        val newest = versions.firstOrNull() ?: return null
        val capped = maxVersion != null && !withinCap(newest.version, maxVersion)
        val selected = if (!capped) {
            newest.takeIf { it.apkUrl != null }
        } else {
            versions.firstOrNull { withinCap(it.version, maxVersion) && it.apkUrl != null }
        } ?: return null
        return Target(
            version = selected.version,
            apkUrl = selected.apkUrl!!,
            releaseUrl = selected.notesUrl,
            newestVersion = newest.version,
            capped = capped,
            tag = selected.tag,
        )
    }

    /** Resolve the channel's installable target under [maxVersion]. One structured catalog object owns
     *  each tag, notes URL and APK URL, so selection cannot cross release boundaries. */
    internal fun chooseTargetWithExact(
        versions: List<ReleaseCatalog.Version>,
        maxVersion: String?,
        exactApkUrl: (String) -> String?,
    ): Target? {
        chooseTarget(versions, maxVersion)?.let { return it }
        val newest = versions.firstOrNull() ?: return null
        if (maxVersion == null || withinCap(newest.version, maxVersion)) return null

        // A safety ceiling is a durable known-good release, not a moving-window preference. Once the
        // pinned version ages out of the recent catalog, resolve its exact release rather than making a
        // missing or unsafe Companion permanently unrepairable.
        val tags = listOf(maxVersion, "v$maxVersion")
        val resolved = tags.firstNotNullOfOrNull { tag ->
            exactApkUrl(tag)?.let { tag to it }
        } ?: return null
        return Target(
            version = maxVersion,
            apkUrl = resolved.second,
            releaseUrl = "https://github.com/$REPO/releases/tag/${resolved.first}",
            newestVersion = newest.version,
            capped = true,
            tag = resolved.first,
        )
    }

    internal fun target(channel: String, maxVersion: String?): Target? = chooseTargetWithExact(
        versions = catalog(channel, 40),
        maxVersion = maxVersion,
        exactApkUrl = { tag -> ReleaseCatalog.apkUrl(REPO, tag, APK_MATCH) },
    )

    /** Install a specific Companion release by its [tag]. The exact-version picker obeys the same device
     *  cap as automatic and newest-version installs; the cap is a safety boundary, not a preference. */
    internal suspend fun installVersionResult(
        context: Context,
        tag: String,
        maxVersion: String?,
    ): InstallOperationResult = withContext(Dispatchers.IO) {
        if (AppInstaller.installedVersion(context, FULL_PKG).isNotBlank()) return@withContext managed(
            "skipped: full Companion present (Play-managed)",
            "managed-play-managed",
        )
        val installed = AppInstaller.installedVersion(context, MINIMAL_PKG)
        val version = UpdateChecker.stripVariant(tag.removePrefix("v"))
        exactVersionRefusal(tag, maxVersion)?.let { refusal ->
            return@withContext managed(
                refusal,
                "managed-safety-cap-refused",
                "version" to version,
                "cap" to requireNotNull(maxVersion),
            )
        }
        val url = ReleaseCatalog.apkUrl(REPO, tag, APK_MATCH) ?: return@withContext managed(
            "no minimal APK for $tag",
            "managed-apk-missing",
            "version" to version,
        )
        Log.i(TAG, "install Companion tag $tag")
        when (val outcome = AppInstaller.install(context, url, AppInstaller.COMPANION_MINIMAL)) {
            is InstallOutcome.Failure -> return@withContext InstallOperationResult(
                outcome.message,
                outcome.presentation,
            )
            InstallOutcome.Succeeded -> Unit
        }
        managed(
            "installing HA Companion $version",
            committedCode(installed, version),
            "version" to version,
        )
    }

    /** True when [latestVersion] cannot prove it is at or below [maxVersion]. Null cap = no ceiling. */
    internal fun exceedsCap(latestVersion: String, maxVersion: String?): Boolean =
        maxVersion != null && !withinCap(latestVersion, maxVersion)

    /** A malformed release version cannot prove itself below a safety cap, so capped panels fail closed. */
    internal fun withinCap(version: String, maxVersion: String?): Boolean =
        maxVersion == null || UpdateChecker.compareVersions(UpdateChecker.stripVariant(version), maxVersion)
            ?.let { it <= 0 } == true

    /** Pure exact-picker cap gate used before any release lookup or download. */
    internal fun exactVersionRefusal(tag: String, maxVersion: String?): String? {
        val version = UpdateChecker.stripVariant(tag.removePrefix("v"))
        return if (withinCap(version, maxVersion)) null
        else "refused: HA Companion $version exceeds this panel's $maxVersion safety cap"
    }

    /** Explicit install decision. Downgrades require [force]; the device safety cap still applies. */
    internal fun shouldInstallTarget(installed: String, targetVersion: String, force: Boolean, maxVersion: String?): Boolean =
        installed.isBlank() ||
            ComponentUpdater.isUpdate(targetVersion, installed, force, UpdateChecker::stripVariant)

    /** Install the minimal Companion if missing or update it when newer. An already-installed build above
     *  the device cap is downgraded only through explicit [force]; [force] never bypasses [maxVersion]. */
    internal suspend fun installOrUpdateResult(
        context: Context,
        force: Boolean = false,
        channel: String = "stable",
        maxVersion: String?,
    ): InstallOperationResult = withContext(Dispatchers.IO) {
        if (AppInstaller.installedVersion(context, FULL_PKG).isNotBlank()) return@withContext managed(
            "skipped: full Companion present (Play-managed)",
            "managed-play-managed",
        )

        val installed = AppInstaller.installedVersion(context, MINIMAL_PKG)
        val missing = installed.isBlank()
        val target = target(channel, maxVersion) ?: return@withContext managed(
            "no installable release found ($channel)",
            "managed-release-unresolved",
            "channel" to channel,
        )
        val installedAboveCap = !missing && exceedsCap(installed, maxVersion)
        val committedCode = committedCode(installed, target.version)

        if (installedAboveCap && !force) {
            return@withContext managed(
                "refused: installed HA Companion $installed exceeds this panel's $maxVersion safety cap; use the manual reinstall action to approve a downgrade",
                "managed-manual-downgrade-required",
                "current" to installed,
                "cap" to requireNotNull(maxVersion),
            )
        }

        if (!shouldInstallTarget(installed, target.version, force, maxVersion)) {
            return@withContext if (target.capped) managed(
                "pinned at $installed (latest ${target.newestVersion} exceeds this panel's $maxVersion cap)",
                "managed-pinned",
                "current" to installed,
                "latest" to target.newestVersion,
                "cap" to requireNotNull(maxVersion),
            ) else managed(
                "up to date ($installed)",
                "managed-up-to-date",
                "current" to installed,
            )
        }

        when (val outcome = AppInstaller.install(context, target.apkUrl, AppInstaller.COMPANION_MINIMAL)) {
            is InstallOutcome.Failure -> return@withContext InstallOperationResult(
                outcome.message,
                outcome.presentation,
            )
            InstallOutcome.Succeeded -> Unit
        }
        val now = AppInstaller.installedVersion(context, MINIMAL_PKG).ifBlank { target.version }
        val verb = when {
            missing -> "installed"
            installedAboveCap -> "downgraded"
            else -> "updated"
        }
        val capApplied = target.capped || installedAboveCap
        Log.i(TAG, "Companion $verb -> $now${if (capApplied) " (capped at $maxVersion)" else ""}")
        managed(
            "$verb HA Companion app ($now)${if (capApplied) " — pinned to the $maxVersion cap for this panel" else ""}",
            committedCode,
            "version" to target.version,
        )
    }

    /** Stable committed-operation semantics selected before installation, independent of compatibility prose. */
    internal fun committedCode(installedVersion: String, targetVersion: String): String {
        if (installedVersion.isBlank()) return "managed-install-committed"
        val comparison = UpdateChecker.compareVersions(
            UpdateChecker.stripVariant(targetVersion),
            UpdateChecker.stripVariant(installedVersion),
        )
        return if (comparison != null && comparison < 0) {
            "managed-downgrade-committed"
        } else {
            "managed-update-committed"
        }
    }

    private fun presentation(code: String, vararg params: Pair<String, String>): InstallPresentation? =
        InstallPresentation.create(code, mapOf("component" to "companion", *params))

    private fun managed(
        message: String,
        code: String,
        vararg params: Pair<String, String>,
    ): InstallOperationResult = InstallOperationResult(message, presentation(code, *params))
}
