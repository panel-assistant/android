package io.panelassistant.android.control

internal enum class NavbarModeApplyStatus { APPLIED, FAILED, SUPERSEDED, CLOSED }

internal data class NavbarModeApplyOutcome(
    val mode: String,
    val status: NavbarModeApplyStatus,
)

internal data class NavbarModeTransactionResult(
    val applied: Boolean,
    val rollbackSucceeded: Boolean = true,
)

/**
 * Apply one complete physical navbar mode. Non-Always modes clear persistent overscan before changing
 * overlays, so a failed clear leaves the previously applied bar intact. Always applies overscan first;
 * if overlay creation then fails, the previous complete physical mode is restored before failure is
 * acknowledged. The caller supplies platform operations so ordering and compensation stay JVM-testable.
 */
internal fun transactNavbarMode(
    previousMode: String,
    targetMode: String,
    permissionReady: Boolean,
    applyOverscanFor: (String) -> Boolean,
    applyOverlayFor: (String) -> Boolean,
): NavbarModeTransactionResult {
    if (!permissionReady) return NavbarModeTransactionResult(applied = false)

    // The controller maps Always to positive overscan and every other mode to a clear. In either case
    // platform state is confirmed before the overlay which visually corresponds to it is replaced.
    fun applyPhysical(mode: String): Boolean =
        applyOverscanFor(mode) && applyOverlayFor(mode)

    if (applyPhysical(targetMode)) return NavbarModeTransactionResult(applied = true)
    return NavbarModeTransactionResult(
        applied = false,
        rollbackSucceeded = applyPhysical(previousMode),
    )
}
