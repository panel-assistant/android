package io.panelassistant.android.platform

import io.panelassistant.android.AppIdentity
import io.panelassistant.android.Config
import io.panelassistant.android.control.FakeSystemEnv
import io.panelassistant.android.fakePreferences
import io.panelassistant.android.homeTakeoverPending
import io.panelassistant.android.preservePreviousHome
import io.panelassistant.android.setupEverCompleted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same deferred-install caller used by Panel Assistant after setup. */
class CompanionHomeInstallTest {
    @Test fun companionInstallKeepsPreviousLauncherInsteadOfClaimingBuiltin() {
        val vendor = ActivityRef("com.vendor.launcher", "com.vendor.launcher.Home")
        val dashboard = ActivityRef(AppIdentity.OWN, "io.panelassistant.android.DashboardActivity")
        val companion = ActivityRef("io.homeassistant.companion.android.minimal", "Home")
        val config = Config(fakePreferences().instance).apply {
            setupEverCompleted = true
            setDashboardPackage(companion.pkg)
            check(preservePreviousHome(vendor, listOf(vendor), armTakeover = true))
        }
        val env = FakeSystemEnv(ownPackage = AppIdentity.OWN,
            homes = listOf(vendor, dashboard, companion), default = vendor)
        val writes = mutableListOf<String>()
        val reply = pendingHomeOperation(config, env, dashboard.component, true, { true }) { target ->
            writes += target
            env.default = env.homes.single { it.component == target }
            true
        }
        assertEquals("Home must keep the owner's launcher on a Companion panel", vendor, env.defaultHome())
        assertTrue(writes.isEmpty())
        assertFalse(config.homeTakeoverPending)
        assertEquals("panel-assistant-pending-home-retained", reply.data)
    }
}
