package io.panelassistant.android

import io.panelassistant.android.control.AppState
import java.util.Collections
import java.util.IdentityHashMap

/** Process-local ownership of deliberately foregrounded administration surfaces. */
internal class KioskAdminUiOwners {
    private val owners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    @Synchronized
    fun setVisible(owner: Any, visible: Boolean) {
        if (visible) owners.add(owner) else owners.remove(owner)
    }

    @Synchronized
    fun isVisible(): Boolean = owners.isNotEmpty()
}

/** Shared by the app Activities and the service-owned kiosk return loop in the same process. */
internal object KioskAdminUi {
    private val owners = KioskAdminUiOwners()

    fun setVisible(owner: Any, visible: Boolean) = owners.setVisible(owner, visible)
    fun isVisible(): Boolean = owners.isVisible()
}

/**
 * Whether the locked kiosk should pull the dashboard back to the front.
 *
 * [foregroundExempt] is evaluated LAST, and Kotlin's `&&` is lazy, so it is asked only once the first
 * two conditions already say "return". That ordering is the whole cost argument: while the dashboard
 * is in front — the overwhelmingly common state — the return loop asks nothing extra, so the steady
 * privileged-probe budget the loop is deliberately bounded to is unchanged. The question is put only
 * at the moment something else genuinely holds the foreground, which is exactly when it matters.
 */
internal fun shouldKioskReturnToDashboard(
    dashboardState: AppState,
    adminUiVisible: Boolean,
    foregroundExempt: () -> Boolean = { false },
): Boolean = dashboardState == AppState.BG && !adminUiVisible && !foregroundExempt()

/**
 * Parse the operator's exempt-companion list.
 *
 * The list is a deliberate allowance, not a discovery: a package earns an exemption because somebody
 * typed it, which is why the default is empty and why nothing here recognises any package by name.
 * Shipping a default that named a specific app would silently change kiosk behaviour for every
 * existing user, and would make this project the arbiter of which third-party apps deserve the
 * foreground on someone else's panel.
 */
internal fun parseKioskCompanionPackages(raw: String): Set<String> =
    raw.splitToSequence(',', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toCollection(LinkedHashSet())

/**
 * Whether [foreground] is one of the operator's exempt packages.
 *
 * A null [foreground] means the probe could not answer — no root, no daemon, or a reply it could not
 * parse. That is deliberately NOT treated as exempt: an unreadable foreground must keep the lock
 * enforcing, because the alternative is that any panel unable to run the probe silently stops being
 * a kiosk the first time a companion package is configured.
 */
internal fun isKioskCompanionForeground(foreground: String?, exempt: Set<String>): Boolean =
    foreground != null && foreground in exempt
