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

/** Whether the locked kiosk should pull the dashboard back to the front. */
internal fun shouldKioskReturnToDashboard(
    dashboardState: AppState,
    adminUiVisible: Boolean,
): Boolean = dashboardState == AppState.BG && !adminUiVisible
