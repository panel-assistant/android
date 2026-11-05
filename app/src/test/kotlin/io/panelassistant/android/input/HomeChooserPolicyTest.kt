package io.panelassistant.android.input

import io.panelassistant.android.platform.ActivityRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a Meta Portal showed after an in-place update: HOME unowned, Always preselected. */
class HomeChooserPolicyTest {
    private val resolver = ActivityRef("android", "com.android.internal.app.ResolverActivity")
    private val dashboard = ActivityRef("io.panelassistant.android", "io.panelassistant.android.DashboardActivity")

    private fun step(
        home: ActivityRef? = resolver,
        window: String? = "android",
        title: String? = "Use Panel Assistant as Home",
        enabled: Boolean? = true,
        shown: Boolean = false,
        setup: Boolean? = true,
    ) = HomeChooserPolicy.next(home, window, title, enabled, "Panel Assistant", shown, setup)

    @Test fun tapsAlwaysWhenAndroidsChooserOffersThisApp() {
        assertEquals(HomeChooserPolicy.Step.TAP_ALWAYS, step())
        assertEquals(HomeChooserPolicy.Step.TAP_ALWAYS, step(title = "Panel Assistant als Startbildschirm verwenden"))
        assertEquals(HomeChooserPolicy.Step.TAP_ALWAYS, step(shown = true))
    }

    @Test fun leavesHomeAloneOnceItIsOwned() {
        assertEquals(HomeChooserPolicy.Step.NONE, step(home = dashboard))
        assertEquals(HomeChooserPolicy.Step.NONE, step(home = dashboard, window = "io.panelassistant.android"))
        assertEquals(HomeChooserPolicy.Step.NONE, step(home = ActivityRef("com.facebook.alohaapps.launcher", "HomeActivity")))
    }

    @Test fun showsTheChooserOnceWhenTheDashboardCoversIt() {
        // After an update the app's start-up brings the dashboard over the chooser before it is answered.
        assertEquals(HomeChooserPolicy.Step.SHOW_CHOOSER, step(window = "io.panelassistant.android"))
        assertEquals(HomeChooserPolicy.Step.SHOW_CHOOSER, step(window = null))
        assertEquals(HomeChooserPolicy.Step.NONE, step(window = "io.panelassistant.android", shown = true))
    }

    @Test fun leavesAnUnownedHomeToTheInstallerBeforeFirstSetup() {
        assertEquals(HomeChooserPolicy.Step.LEAVE_TO_INSTALLER, step(window = "io.panelassistant.android", setup = false))
        assertEquals(HomeChooserPolicy.Step.LEAVE_TO_INSTALLER, step(window = "cloud.shelly.stargate", setup = null))
        assertEquals(HomeChooserPolicy.Step.NONE, step(window = "io.panelassistant.android", shown = true, setup = false))
        // A chooser already in front is answered as before: Always for this app is what the installer wants too.
        assertEquals(HomeChooserPolicy.Step.TAP_ALWAYS, step(setup = false))
        assertEquals(HomeChooserPolicy.Step.NONE, step(title = "Use Shelly Stargate as Home", setup = false))
    }

    @Test fun dismissesAChooserItShowedThatIsStillInFrontOnceHomeIsOwned() {
        val vendor = "Use Shelly Stargate as Home"
        assertEquals(HomeChooserPolicy.Step.DISMISS, step(home = dashboard, title = vendor, shown = true))
        assertEquals(HomeChooserPolicy.Step.DISMISS, step(home = dashboard, shown = true))
        assertEquals(HomeChooserPolicy.Step.NONE, step(home = dashboard, title = vendor))
        assertEquals(HomeChooserPolicy.Step.NONE, step(home = dashboard, window = "io.panelassistant.android", shown = true))
        assertEquals(HomeChooserPolicy.Step.NONE, step(home = dashboard, title = null, enabled = null, shown = true))
    }

    @Test fun neverTapsForAnotherAppAndDismissesOnlyAChooserItShowed() {
        assertEquals(HomeChooserPolicy.Step.NONE, step(title = "Use Portal Launcher as Home"))
        assertEquals(HomeChooserPolicy.Step.DISMISS, step(title = "Use Portal Launcher as Home", shown = true))
        assertEquals(HomeChooserPolicy.Step.NONE, step(title = null))
        assertEquals(HomeChooserPolicy.Step.NONE, step(enabled = false))
    }

    @Test fun looksAgainAtAChooserItShowedThatIsNotAnswerableYet() {
        // Its list is still loading: Always disabled, or no title yet.
        assertEquals(HomeChooserPolicy.Step.LOOK_AGAIN, step(enabled = false, shown = true))
        assertEquals(HomeChooserPolicy.Step.LOOK_AGAIN, step(title = null, enabled = false, shown = true))
        assertEquals(HomeChooserPolicy.Step.LOOK_AGAIN, step(title = null, shown = true))
        assertEquals(HomeChooserPolicy.Step.LOOK_AGAIN, step(title = "", shown = true))
        assertEquals(HomeChooserPolicy.Step.NONE, step(enabled = false, shown = false))
    }

    @Test fun anotherSystemWindowIsNotTheChooser() {
        // An Android dialog with no Always button, such as "app isn't responding".
        assertEquals(HomeChooserPolicy.Step.NONE, step(title = null, enabled = null))
        assertEquals(HomeChooserPolicy.Step.NONE, step(title = null, enabled = null, shown = true))
    }

    private val pkg = "io.panelassistant.android"
    private val service = "$pkg.input.PanelAccessibilityService"
    private val portal = "$pkg/.input.PanelAccessibilityService:" +
        "com.facebook.aloha.system.device/.accessibility.KeyEventAccessibilityService"

    @Test fun aConnectionWithoutWindowContentReEnablesOnceKeepingOtherServices() {
        // A Meta Portal after the update that added window content: capabilities 40, not 41.
        assertEquals(
            "com.facebook.aloha.system.device/.accessibility.KeyEventAccessibilityService",
            HomeChooserPolicy.rebindEntries(portal, pkg, service, 40),
        )
        assertEquals("", HomeChooserPolicy.rebindEntries("$pkg/$service", pkg, service, 40))
        // The old app id's leftover entry is another package's service and stays.
        val legacy = "io.github.maxlyth.hapaneld/.input.PanelAccessibilityService"
        assertEquals(legacy, HomeChooserPolicy.rebindEntries("$legacy:$pkg/$service", pkg, service, 40))
    }

    @Test fun noRebindWhenTheCapabilityIsAlreadyThereOrTheServiceIsNotListed() {
        assertNull(HomeChooserPolicy.rebindEntries(portal, pkg, service, 41))
        assertNull(HomeChooserPolicy.rebindEntries("com.other/.Service", pkg, service, 40))
        assertNull(HomeChooserPolicy.rebindEntries(null, pkg, service, 40))
    }
}
