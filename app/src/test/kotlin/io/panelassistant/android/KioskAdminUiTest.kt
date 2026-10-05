package io.panelassistant.android

import io.panelassistant.android.control.AppState
import io.panelassistant.android.control.SystemController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskAdminUiTest {
    @Test fun overlappingAdminActivitiesKeepTheExemptionUntilTheLastOwnerStops() {
        val owners = KioskAdminUiOwners()
        val launcher = Any()
        val config = Any()

        owners.setVisible(launcher, true)
        owners.setVisible(launcher, true)
        owners.setVisible(config, true)
        owners.setVisible(launcher, false)
        assertTrue(owners.isVisible())

        owners.setVisible(config, false)
        assertFalse(owners.isVisible())
    }

    @Test fun explicitLaunchDecisionAndKioskReclaimShareAdminOwnership() {
        val decision = LaunchScreenPolicy.decide(
            kioskEnabled = true,
            configuredRenderer = SystemController.BUILTIN_DASHBOARD,
            builtInUrlConfigured = true,
            dashboardLaunchAvailable = true,
            dashboardRecoveryBlocked = false,
            currentVersionCode = 336L,
            lastShownVersionCode = 336L,
            explicitAdminEntry = true,
        )
        val owners = KioskAdminUiOwners()
        val intro = Any()

        assertTrue(decision.destination == LaunchDestination.INTRO)
        owners.setVisible(intro, true)
        assertFalse(shouldKioskReturnToDashboard(AppState.BG, owners.isVisible()))

        owners.setVisible(intro, false)
        assertTrue(shouldKioskReturnToDashboard(AppState.BG, owners.isVisible()))
        assertFalse(shouldKioskReturnToDashboard(AppState.FG, adminUiVisible = false))
        assertFalse(shouldKioskReturnToDashboard(AppState.UNKNOWN, adminUiVisible = false))
    }
}
