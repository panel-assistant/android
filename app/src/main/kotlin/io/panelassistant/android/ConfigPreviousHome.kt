package io.panelassistant.android

import io.panelassistant.android.config.TamePackagePolicy
import io.panelassistant.android.platform.ActivityRef
import io.panelassistant.android.util.AndroidInput

/** Local installation custody; kept in the canonical store across updates, never a user setting. */
internal val Config.previousHomeComponent: String?
    get() = prefs.getString("device_local_previous_home_component", null)

/** Write before taking HOME or disabling its launcher. Retries must never replace the first record. */
internal fun Config.preservePreviousHome(home: ActivityRef?, candidates: List<ActivityRef>): Boolean =
    synchronizedTransaction {
        if (previousHomeComponent != null) return@synchronizedTransaction true
        if (home == null) return@synchronizedTransaction false
        if (AppIdentity.isPanelApp(home.pkg) || TamePackagePolicy.isCritical(home.pkg) ||
            home.pkg == "android"
        ) return@synchronizedTransaction true // A resolver or historical app HOME has no original to record.
        if (home !in candidates || !AndroidInput.isComponent(home.component)) return@synchronizedTransaction false
        durableCommit { putString("device_local_previous_home_component", home.component) }
    }
