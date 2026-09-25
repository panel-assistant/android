package io.github.maxlyth.hapaneld

/**
 * Whether MainActivity may show Android's notification-permission dialog.
 *
 * A wall panel's permissions belong to whatever installed or upgraded the app, which grants and verifies
 * them without anyone at the panel. This dialog is only the last resort for a panel that path missed, so
 * it is shown at most once per version code, only over a standing screen that has already been chosen
 * and that no automatic return will cover, and never on a platform where the permission is not a runtime
 * permission. A denial stays the user's answer: the same version never asks again.
 */
internal object NotificationConsentPrompt {
    /** Android 13, where POST_NOTIFICATIONS became a runtime permission. */
    const val RUNTIME_PERMISSION_SDK = 33

    fun shouldAsk(
        sdkInt: Int,
        granted: Boolean,
        standingScreenPresented: Boolean,
        autoReturnPending: Boolean,
        currentVersionCode: Long,
        lastAskedVersionCode: Long?,
    ): Boolean = sdkInt >= RUNTIME_PERMISSION_SDK &&
        !granted &&
        standingScreenPresented &&
        !autoReturnPending &&
        lastAskedVersionCode != currentVersionCode
}
