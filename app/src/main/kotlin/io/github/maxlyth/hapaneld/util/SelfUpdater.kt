package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.AppIdentity
import io.github.maxlyth.hapaneld.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Explicit ha-paneld installation — uses the same
 * pinned-signer install as the Companion installer, via [AppInstaller.HA_PANELD]. A request-local channel selects which
 * releases to follow: `stable` (GitHub releases/latest — non-prerelease) or `prerelease` (the newest
 * published release, incl. rc builds). Installing a newer build restarts the service (the package's
 * MY_PACKAGE_REPLACED receiver relaunches it); a channel switch may move down a version — allowed by
 * the installer's `-d`. Network + su — call OFF the main / MQTT thread.
 */
object SelfUpdater {
    const val STABLE = "stable"
    const val PRERELEASE = "prerelease"
    private const val REPO = "panel-assistant/android"
    private const val TAG = "ha-paneld/selfupdate"
    private const val RELEASES_URL = "https://github.com/panel-assistant/android/releases"

    /**
     * A release from the identity migration onwards carries two APKs: the bridge under the legacy id,
     * named as every earlier release named its only APK, and the successor as `panel-assistant-*.apk`.
     * Each build follows its own asset by name. Shipped updaters take the first `.apk`, so the release
     * workflow orders the bridge first; this build does not depend on that order.
     */
    internal fun isSuccessorAsset(name: String): Boolean =
        name.startsWith("panel-assistant-", ignoreCase = true) && name.endsWith(".apk", ignoreCase = true)

    internal fun isBridgeAsset(name: String): Boolean =
        name.endsWith(".apk", ignoreCase = true) && !isSuccessorAsset(name)

    internal fun ownAssetMatch(bridge: Boolean): (String) -> Boolean =
        if (bridge) ::isBridgeAsset else ::isSuccessorAsset

    private val APK_MATCH: (String) -> Boolean = ownAssetMatch(AppIdentity.IS_BRIDGE)

    /** The successor asset of the release that published [version], or null when it carries none. */
    fun successorAssetUrl(version: String): String? =
        ReleaseCatalog.apkUrl(REPO, "v${version.removePrefix("v")}", ::isSuccessorAsset)

    /** The release-notes page for an exact [tag]; the tag grammar keeps the value inside the URL path. */
    fun releaseNotesUrl(tag: String): String? = if (ReleaseCatalog.validTag(tag)) "$RELEASES_URL/tag/$tag" else null

    /** Up to [limit] recent versions on [channel] for the Install-tab picker (version + release-notes URL). */
    fun versions(channel: String, limit: Int = 10): List<ReleaseCatalog.Version> {
        val policy = AppInstaller.panelUpdatePolicy() ?: return emptyList()
        // PA chooses the effective channel; the request's old channel field has no update authority.
        val effectiveChannel = if (policy.prerelease) "prerelease" else "stable"
        val deadline = System.nanoTime() + 30_000_000_000L
        val raw = ReleaseCatalog.list(REPO, effectiveChannel, 50, APK_MATCH,
            olderAppMatch = if (AppIdentity.IS_BRIDGE) null else { name ->
                name.startsWith("ha-paneld-", ignoreCase = true) && name.endsWith(".apk", ignoreCase = true)
            }) { it.removePrefix("v") }
        return admittedVersions(raw, limit, AppInstaller::panelUpdatePolicy) { version ->
            PanelReleaseMetadata.read(version.tag, version.version, requireNotNull(version.apkUrl)) {
                (deadline - System.nanoTime()) / 1_000_000L
            }
        }
    }

    /** Filter before taking the picker limit; an incompatible newest release cannot hide a valid one. */
    internal fun admittedVersions(
        candidates: List<ReleaseCatalog.Version>,
        limit: Int,
        policy: () -> io.github.maxlyth.hapaneld.panelassistant.PanelAssistantUpdatePolicy?,
        metadata: (ReleaseCatalog.Version) -> PanelReleaseMetadata.Candidate?,
    ): List<ReleaseCatalog.Version> {
        if (limit <= 0 || policy() == null) return emptyList()
        val admitted = candidates.sortedWith { left, right ->
            AppInstaller.comparePanelVersions(right.version, left.version) ?: 0
        }.asSequence().filter { version ->
            version.installable && version.apkUrl != null &&
                AppInstaller.comparePanelVersions(version.version, BuildConfig.VERSION_NAME)?.let { it >= 0 } == true
        }.mapNotNull { version ->
            val proof = metadata(version) ?: return@mapNotNull null
            if (AppInstaller.panelUpdateRefusal(policy(), proof.range, version.version,
                    BuildConfig.VERSION_NAME, proof.versionCode, BuildConfig.VERSION_CODE.toLong()) != null) null
            else version.copy(protocolRange = proof.range, authenticatedVersionCode = proof.versionCode)
        }.take(limit).toList()
        // Metadata I/O can span a disconnect or a changed PA policy. Never publish remembered admission.
        return admitted.filter { version ->
            AppInstaller.panelUpdateRefusal(policy(), version.protocolRange, version.version,
                BuildConfig.VERSION_NAME, version.authenticatedVersionCode ?: 0L,
                BuildConfig.VERSION_CODE.toLong()) == null
        }
    }

    internal sealed interface ChannelPreparation {
        val message: String
        val presentation: InstallPresentation?

        data class Unresolved(
            override val message: String,
            override val presentation: InstallPresentation? = null,
        ) : ChannelPreparation
        data class UpToDate(
            override val message: String,
            override val presentation: InstallPresentation? = null,
        ) : ChannelPreparation
        data class Refused(
            override val message: String,
            override val presentation: InstallPresentation? = null,
        ) : ChannelPreparation
        data class Ready(
            val prepared: AppInstaller.PreparedSelfInstall,
            override val message: String,
            override val presentation: InstallPresentation? = null,
        ) : ChannelPreparation
    }

    /** Install a specific ha-paneld release by its [tag]. The tag is validated and resolved back through
     *  the fixed repository before the package/signer-pinned installer sees its asset. */
    suspend fun installVersion(context: Context, tag: String): String = installVersionResult(context, tag).message

    internal suspend fun installVersionResult(context: Context, tag: String): InstallOperationResult =
        withContext(Dispatchers.IO) {
            val version = tag.removePrefix("v")
            if (AppInstaller.panelUpdatePolicy() == null) return@withContext InstallOperationResult(
                "Panel Assistant must be online with update policy",
            )
            val url = ReleaseCatalog.apkUrl(REPO, tag, APK_MATCH) ?: return@withContext managed(
                "no APK asset for $tag",
                "managed-apk-missing",
                "version" to version,
            )
            Log.i(TAG, "self-install ha-paneld tag $tag")
            when (val preparation = AppInstaller.prepareSelfInstall(context, url)) {
                is AppInstaller.SelfInstallPreparation.Failed -> preparation.outcome.asOperationResult()
                is AppInstaller.SelfInstallPreparation.Ready -> preparation.prepared.use { prepared ->
                    when (val outcome = AppInstaller.installPrepared(context, prepared)) {
                        InstallOutcome.Succeeded -> managed(
                            "installing ha-paneld $tag",
                            "managed-update-committed",
                            "version" to version,
                        )
                        is InstallOutcome.Failure -> outcome.asOperationResult()
                    }
                }
            }
        }

    /** The newest release for [channel] as one coherent target (version + APK URL + release-notes URL), or
     *  null. Feeds the shared [ComponentUpdater] resolve -> compare -> decide pipeline. */
    fun resolveTarget(channel: String): ComponentUpdater.Target? =
        versions(channel, 1).firstOrNull()?.let { target ->
            ComponentUpdater.Target(target.version, requireNotNull(target.apkUrl), RELEASES_URL,
                target.tag, target.version.contains('-'))
        }

    /**
     * Resolve, download, authenticate and database-admit one exact channel candidate without mutating
     * configuration or installing it. A [ChannelPreparation.Ready] owns the admitted bytes until its
     * prepared capability is installed or closed by the caller.
     */
    internal suspend fun prepareChannelUpdate(
        context: Context,
        channel: String,
        force: Boolean = false,
    ): ChannelPreparation = withContext(Dispatchers.IO) {
        val current = BuildConfig.VERSION_NAME
        if (AppInstaller.panelUpdatePolicy() == null) return@withContext ChannelPreparation.Refused(
            "Panel Assistant must be online with update policy",
        )
        when (val outcome = ComponentUpdater.resolveUpdate(current, force) { resolveTarget(channel) }) {
            ComponentUpdater.Outcome.Unresolved -> ChannelPreparation.Unresolved(
                "no release found ($channel)",
                presentation(
                    "managed-release-unresolved",
                    "channel" to channel,
                ),
            )
            ComponentUpdater.Outcome.UpToDate -> ChannelPreparation.UpToDate(
                "up to date ($current, $channel)",
                presentation("managed-up-to-date", "current" to current),
            )
            is ComponentUpdater.Outcome.Update -> {
                val target = outcome.target
                when (val preparation = AppInstaller.prepareSelfInstall(context, target.apkUrl)) {
                    is AppInstaller.SelfInstallPreparation.Failed ->
                        ChannelPreparation.Refused(
                            preparation.outcome.message,
                            preparation.outcome.presentation,
                        )
                    is AppInstaller.SelfInstallPreparation.Ready -> ChannelPreparation.Ready(
                        preparation.prepared,
                        "updating ha-paneld -> ${target.version}",
                    )
                }
            }
        }
    }

    internal suspend fun installPreparedOutcome(
        context: Context,
        prepared: AppInstaller.PreparedSelfInstall,
    ): InstallOperationResult = when (val outcome = AppInstaller.installPrepared(context, prepared)) {
        InstallOutcome.Succeeded -> InstallOperationResult(
            "updating ha-paneld -> ${prepared.version}",
            presentation = presentation(
                "managed-update-committed",
                "version" to prepared.version,
            ),
        )
        is InstallOutcome.Failure -> InstallOperationResult(
            outcome.message,
            presentation = outcome.presentation,
        )
    }

    internal suspend fun checkAndUpdateResult(
        context: Context,
        channel: String,
        force: Boolean = false,
    ): InstallOperationResult =
        withContext(Dispatchers.IO) {
            when (val preparation = prepareChannelUpdate(context, channel, force)) {
                is ChannelPreparation.Unresolved -> InstallOperationResult(
                    preparation.message,
                    preparation.presentation,
                )
                is ChannelPreparation.UpToDate -> InstallOperationResult(
                    preparation.message,
                    preparation.presentation,
                )
                is ChannelPreparation.Refused -> InstallOperationResult(
                    preparation.message,
                    preparation.presentation,
                )
                is ChannelPreparation.Ready -> preparation.prepared.use { prepared ->
                    Log.i(TAG, "self-update ${BuildConfig.VERSION_NAME} -> ${prepared.version} ($channel)")
                    installPreparedOutcome(context, prepared)
                }
            }
        }

    private fun presentation(code: String, vararg params: Pair<String, String>): InstallPresentation? =
        InstallPresentation.create(code, mapOf("component" to "paneld", *params))

    private fun managed(
        message: String,
        code: String,
        vararg params: Pair<String, String>,
    ): InstallOperationResult = InstallOperationResult(message, presentation(code, *params))

    private fun InstallOutcome.Failure.asOperationResult(): InstallOperationResult =
        InstallOperationResult(message, presentation)
}
