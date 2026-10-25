package io.panelassistant.android

import io.panelassistant.android.config.TamePackagePolicy
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.util.AndroidInput

/** Local installation custody; kept in the canonical store across updates, never a user setting. */
internal val Config.previousHomeComponent: String?
    get() = prefs.getString("device_local_previous_home_component", null)

/** Initial installation requested ownership, but setup must finish before it can be acquired. */
internal val Config.homeTakeoverPending: Boolean
    get() = prefs.getBoolean("device_local_home_takeover_pending", false)

internal fun Config.completeHomeTakeover(): Boolean = synchronizedTransaction {
    if (!homeTakeoverPending) true
    else durableCommit { putBoolean("device_local_home_takeover_pending", false) }
}

/** HOME ownership is acknowledged only against fresh exact readback after first setup. */
internal fun Config.confirmHomeTakeover(expected: ActivityRef, observed: ActivityRef?): Boolean =
    setupEverCompleted && expected == observed && completeHomeTakeover()

/** Write before taking HOME or disabling its launcher. Retries must never replace the first record. */
internal fun Config.preservePreviousHome(
    home: ActivityRef?,
    candidates: List<ActivityRef>,
    armTakeover: Boolean = false,
): Boolean = synchronizedTransaction {
    val original = previousHomeComponent
    if (original != null && (!armTakeover || homeTakeoverPending)) return@synchronizedTransaction true
    if (home == null) return@synchronizedTransaction false
    val realHome = !AppIdentity.isPanelApp(home.pkg) && !TamePackagePolicy.isCritical(home.pkg) && home.pkg != "android"
    if (realHome && (home !in candidates || !AndroidInput.isComponent(home.component))) return@synchronizedTransaction false
    if (original == null && !realHome && !armTakeover) return@synchronizedTransaction true
    durableCommit {
        if (original == null && realHome) putString("device_local_previous_home_component", home.component)
        if (armTakeover) putBoolean("device_local_home_takeover_pending", true)
    }
}
