package io.github.maxlyth.hapaneld.platform

import android.os.Build

/**
 * The service's notification permission, which a panel holds whatever a person once answered.
 *
 * The service notification is part of keeping the panel working, and a denial may be a mis-tap or a
 * prompt nobody saw, so every installer grants POST_NOTIFICATIONS before the first start and no dialog
 * ever asks for it. This is the repair for a panel an installer missed: at service start, the root
 * helper's fixed `GRANT` verb claims it, where a helper is present.
 */
internal object NotificationPermissionRepair {
    enum class Outcome { HELD, CLAIMED, NO_HELPER, REFUSED }

    /** Below Android 13 the permission is granted at install, so it is never missing there. */
    fun held(sdkInt: Int, granted: () -> Boolean): Boolean =
        sdkInt < Build.VERSION_CODES.TIRAMISU || granted()

    fun repair(sdkInt: Int, granted: () -> Boolean, helper: Daemon, packageName: String): Outcome {
        if (held(sdkInt, granted)) return Outcome.HELD
        val reply = helper.sendLong("GRANT $packageName NOTIFICATIONS", TIMEOUT_MS)
        if (reply == DaemonLongResult.NotSubmitted) return Outcome.NO_HELPER
        // Only what Android kept counts, whatever the helper replied.
        return if (granted()) Outcome.CLAIMED else Outcome.REFUSED
    }

    private const val TIMEOUT_MS = 30_000L
}
