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
    fun versions(channel: String, limit: Int = 10): List<ReleaseCatalog.Version> =
        ReleaseCatalog.list(REPO, channel, limit, APK_MATCH,
            olderAppMatch = if (AppIdentity.IS_BRIDGE) null else { name ->
                name.startsWith("ha-paneld-", ignoreCase = true) && name.endsWith(".apk", ignoreCase = true)
            }) { it.removePrefix("v") }

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
                            committedCode(version),
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
        ReleaseCatalog.newestApkTarget(REPO, channel, APK_MATCH) { it.removePrefix("v") }
            ?.let { target ->
                ComponentUpdater.Target(
                    target.version,
                    target.apkUrl,
                    RELEASES_URL,
                    target.tag,
                    target.prerelease,
                )
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
                committedCode(prepared.version),
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

    private fun committedCode(version: String): String =
        if (UpdateChecker.compareVersions(version, BuildConfig.VERSION_NAME)?.let { it < 0 } == true)
            "managed-downgrade-committed"
        else "managed-update-committed"

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
