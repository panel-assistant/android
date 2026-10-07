package io.panelassistant.android.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlAvailabilityTest {
    @Test fun accessibilityEnablesBackAndRecents() {
        val result = ControlAvailability.navigation(
            accessibilityReady = true,
            hasRecents = true,
        )

        assertTrue(result.backEnabled)
        assertTrue(result.recentsEnabled)
        assertTrue(result.rootlessNote.contains("Back, Recents still work"))
    }

    @Test fun missingInputRouteDisablesNavigationWithoutPromotingAnOptionalProvider() {
        val result = ControlAvailability.navigation(
            accessibilityReady = false,
            hasRecents = true,
        )

        assertFalse(result.backEnabled)
        assertFalse(result.recentsEnabled)
        assertTrue(result.recentsRequirement.contains("Accessibility"))
        assertTrue(result.rootlessNote.contains("Back and Recents need Accessibility or privileged input access"))
    }

    @Test fun firmwareWithoutOverviewKeepsRecentsDisabledWhenInputIsReady() {
        val result = ControlAvailability.navigation(
            accessibilityReady = true,
            hasRecents = false,
        )

        assertTrue(result.backEnabled)
        assertFalse(result.recentsEnabled)
        assertTrue(result.recentsRequirement.contains("absent on this panel"))
        assertFalse(result.rootlessNote.contains("Recents"))
        assertTrue(result.rootlessNote.contains("Back still works"))
    }

    @Test fun missingInputOnFirmwareWithoutOverviewUsesSingularBackGuidance() {
        val result = ControlAvailability.navigation(
            accessibilityReady = false,
            hasRecents = false,
        )

        assertTrue(result.rootlessNote.contains("Back needs Accessibility or privileged input access"))
    }
}
