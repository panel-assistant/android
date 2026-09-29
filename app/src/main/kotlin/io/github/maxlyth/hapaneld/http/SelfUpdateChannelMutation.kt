package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.config.SettingValue
import io.github.maxlyth.hapaneld.util.InstallPresentation

/** An exact channel candidate prepared without changing configuration, helper state, or packages. */
internal sealed interface SelfUpdateChannelPreflight {
    val message: String
    val presentation: InstallPresentation?

    data class Unresolved(
        override val message: String,
        override val presentation: InstallPresentation? = null,
    ) : SelfUpdateChannelPreflight
    data class UpToDate(
        override val message: String,
        override val presentation: InstallPresentation? = null,
    ) : SelfUpdateChannelPreflight
    data class Refused(
        override val message: String,
        override val presentation: InstallPresentation? = null,
    ) : SelfUpdateChannelPreflight
    class Ready(
        override val message: String,
        val requiresRecovery: Boolean,
        val revalidateForConfigCommit: () -> String?,
        val install: suspend () -> SelfUpdateChannelInstallResult,
        private val discardPrepared: () -> Unit,
        override val presentation: InstallPresentation? = null,
    ) : SelfUpdateChannelPreflight, AutoCloseable {
        override fun close() = discardPrepared()
    }
}

internal data class SelfUpdateChannelInstallResult(
    val message: String,
    val installed: Boolean,
    val presentation: InstallPresentation? = null,
)

internal data class SelfUpdateChannelMutation(
    val requested: String,
    val force: Boolean,
)

/** Only an enabled updater changing channels creates an immediate APK candidate. */
internal fun selfUpdateChannelMutation(
    currentChannel: String,
    currentSelfUpdate: Boolean,
    requestedValues: Map<String, String>,
): SelfUpdateChannelMutation? {
    val requested = requestedValues["update_channel"] ?: return null
    if (requested == currentChannel) return null
    val enabled = requestedValues["self_update"]?.let(SettingValue::parseBool) ?: currentSelfUpdate
    if (!enabled) return null
    return SelfUpdateChannelMutation(
        requested = requested,
        force = currentChannel == "prerelease" && requested == "stable",
    )
}

/** A restore already owns a destructive ticket and cannot hand it off to an asynchronous self-install.
 * Reject every actual channel change, including a bundle that simultaneously disables self-update; this
 * also guarantees a later rollback never needs to resolve/install a candidate under the restore owner. */
internal fun restoreChangesUpdateChannel(
    currentChannel: String,
    accepted: Map<String, String>,
): Boolean = accepted["update_channel"]?.let { it != currentChannel } == true
