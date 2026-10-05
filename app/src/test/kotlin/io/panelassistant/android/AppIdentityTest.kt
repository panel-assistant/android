package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIdentityTest {
    @Test fun theTwoIdentitiesAreFixed() {
        assertEquals("io.github.maxlyth.hapaneld", AppIdentity.LEGACY)
        assertEquals("io.panelassistant.android", AppIdentity.SUCCESSOR)
        assertEquals(listOf(AppIdentity.LEGACY, AppIdentity.SUCCESSOR), AppIdentity.ALL.toList())
    }

    @Test fun theBridgeIsTheBuildThatKeepsTheLegacyId() {
        assertEquals(AppIdentity.OWN == AppIdentity.LEGACY, AppIdentity.IS_BRIDGE)
        assertEquals(AppIdentity.counterpartOf(AppIdentity.OWN), AppIdentity.COUNTERPART)
    }

    @Test fun eachIdentityNamesTheOtherAsItsCounterpart() {
        assertEquals(AppIdentity.SUCCESSOR, AppIdentity.counterpartOf(AppIdentity.LEGACY))
        assertEquals(AppIdentity.LEGACY, AppIdentity.counterpartOf(AppIdentity.SUCCESSOR))
        assertThrows(IllegalStateException::class.java) { AppIdentity.counterpartOf("com.example.other") }
    }

    @Test fun onlyTheTwoIdsArePanelApps() {
        assertTrue(AppIdentity.isPanelApp(AppIdentity.LEGACY))
        assertTrue(AppIdentity.isPanelApp(AppIdentity.SUCCESSOR))
        assertFalse(AppIdentity.isPanelApp("io.panelassistant.android.debug"))
        assertFalse(AppIdentity.isPanelApp(null))
    }

    @Test fun successorComponentsUseTheShorthandBecauseTheIdIsTheKotlinPackage() {
        assertEquals(
            "io.panelassistant.android/.input.PanelAccessibilityService",
            AppIdentity.component(AppIdentity.SUCCESSOR, ".input.PanelAccessibilityService"),
        )
    }

    @Test fun legacyComponentsNameTheClassInTheKotlinPackage() {
        assertEquals(
            "io.github.maxlyth.hapaneld/io.panelassistant.android.DashboardActivity",
            AppIdentity.component(AppIdentity.LEGACY, ".DashboardActivity"),
        )
    }

    @Test fun aClassNameThatIsNotRelativeIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            AppIdentity.component(AppIdentity.SUCCESSOR, "DashboardActivity")
        }
    }
}
