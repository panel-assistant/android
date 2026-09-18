package io.github.maxlyth.hapaneld

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

    @Test fun legacyComponentsKeepTheShorthandEveryShippedCommandUses() {
        assertEquals(
            "io.github.maxlyth.hapaneld/.DashboardActivity",
            AppIdentity.component(AppIdentity.LEGACY, ".DashboardActivity"),
        )
    }

    @Test fun successorComponentsNameTheClassInTheUnmovedCodePackage() {
        assertEquals(
            "io.panelassistant.android/io.github.maxlyth.hapaneld.input.PanelAccessibilityService",
            AppIdentity.component(AppIdentity.SUCCESSOR, ".input.PanelAccessibilityService"),
        )
        assertEquals(
            "io.github.maxlyth.hapaneld.AdminLauncherActivity",
            AppIdentity.className(AppIdentity.SUCCESSOR, ".AdminLauncherActivity"),
        )
    }

    @Test fun aClassNameThatIsNotRelativeIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            AppIdentity.component(AppIdentity.SUCCESSOR, "DashboardActivity")
        }
    }
}
