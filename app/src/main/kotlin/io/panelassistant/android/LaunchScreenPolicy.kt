package io.panelassistant.android

import io.panelassistant.android.control.SystemController

internal enum class LaunchDestination {
    INTRO,
    DASHBOARD,
}

internal data class LaunchScreenDecision(
    val destination: LaunchDestination,
    val rememberVersionShown: Boolean = false,
)

internal data class SavedLaunchIntroState(
    val explicitAdminEntry: Boolean,
    val pendingVersionCode: Long?,
    val autoReturnRemainingMs: Long?,
    val autoReturnNextAttemptRemainingMs: Long?,
)

internal data class RestoredLaunchIntroPlan(
    val explicitAdminEntry: Boolean,
    val pendingVersionCode: Long?,
    val autoReturnRemainingMs: Long?,
    val autoReturnDelayMs: Long?,
)

/** A return window measured from the first rendered intro frame, not from Activity startup. */
internal data class PreparedVisibleAutoReturn(
    val remainingMs: Long,
    val firstDelayMs: Long,
)

internal data class ArmedVisibleAutoReturn(
    val deadlineMs: Long,
    val firstAttemptMs: Long,
)

internal fun prepareVisibleAutoReturn(
    restoredRemainingMs: Long?,
    restoredDelayMs: Long?,
    defaultWindowMs: Long,
    defaultFirstDelayMs: Long,
): PreparedVisibleAutoReturn? {
    val remaining = restoredRemainingMs?.coerceIn(0L, defaultWindowMs) ?: defaultWindowMs
    val firstDelay = restoredDelayMs ?: defaultFirstDelayMs
    if (remaining <= 0L || firstDelay < 0L || firstDelay > remaining) return null
    return PreparedVisibleAutoReturn(remaining, firstDelay)
}

internal fun armVisibleAutoReturn(
    prepared: PreparedVisibleAutoReturn,
    firstDrawAtMs: Long,
): ArmedVisibleAutoReturn = ArmedVisibleAutoReturn(
    deadlineMs = firstDrawAtMs + prepared.remainingMs,
    firstAttemptMs = firstDrawAtMs + prepared.firstDelayMs,
)

/** Restore a visible intro without re-running destination policy or resetting its return window. */
internal fun restoreLaunchIntro(
    saved: SavedLaunchIntroState,
    currentVersionCode: Long,
    lastShownVersionCode: Long?,
    minimumReturnDelayMs: Long,
    maximumReturnWindowMs: Long,
): RestoredLaunchIntroPlan {
    val pendingVersion = saved.pendingVersionCode
        ?.takeIf { it == currentVersionCode && lastShownVersionCode != currentVersionCode }
    if (saved.explicitAdminEntry) {
        return RestoredLaunchIntroPlan(true, pendingVersion, null, null)
    }
    val remaining = saved.autoReturnRemainingMs
        ?.coerceIn(0L, maximumReturnWindowMs)
        ?.takeIf { it > 0L }
    val desiredDelay = saved.autoReturnNextAttemptRemainingMs
        ?.coerceIn(0L, remaining ?: 0L)
        ?: minimumReturnDelayMs
    val delay = remaining
        ?.let { maxOf(desiredDelay, minimumReturnDelayMs) }
        ?.takeIf { it <= remaining }
    return RestoredLaunchIntroPlan(
        explicitAdminEntry = false,
        pendingVersionCode = pendingVersion,
        autoReturnRemainingMs = remaining?.takeIf { delay != null },
        autoReturnDelayMs = delay,
    )
}

/** Pure policy for ha-paneld's launcher/QR screen. Recovery always wins over kiosk convenience. */
internal object LaunchScreenPolicy {
    fun decide(
        kioskEnabled: Boolean,
        configuredRenderer: String,
        builtInUrlConfigured: Boolean,
        dashboardLaunchAvailable: Boolean,
        dashboardRecoveryBlocked: Boolean,
        currentVersionCode: Long,
        lastShownVersionCode: Long?,
        explicitAdminEntry: Boolean,
    ): LaunchScreenDecision {
        if (explicitAdminEntry || !kioskEnabled) return LaunchScreenDecision(LaunchDestination.INTRO)
        if (!PostUpdateReturnPolicy.dashboardReady(
                configuredRenderer = configuredRenderer,
                builtInUrlConfigured = builtInUrlConfigured,
                dashboardLaunchAvailable = dashboardLaunchAvailable,
                dashboardRecoveryBlocked = dashboardRecoveryBlocked,
            )
        ) {
            return LaunchScreenDecision(LaunchDestination.INTRO)
        }
        if (configuredRenderer == SystemController.BUILTIN_DASHBOARD &&
            lastShownVersionCode != currentVersionCode
        ) {
            return LaunchScreenDecision(
                destination = LaunchDestination.INTRO,
                rememberVersionShown = true,
            )
        }
        return LaunchScreenDecision(LaunchDestination.DASHBOARD)
    }
}

/** Pure admission gate for the asynchronous first-draw acknowledgement. */
internal fun mayAcknowledgePresentedIntro(
    generationMatches: Boolean,
    presentedViewMatches: Boolean,
    viewAttached: Boolean,
    activityFinishing: Boolean,
    activityDestroyed: Boolean,
    startupChosen: Boolean,
): Boolean = generationMatches &&
    presentedViewMatches &&
    viewAttached &&
    !activityFinishing &&
    !activityDestroyed &&
    startupChosen
