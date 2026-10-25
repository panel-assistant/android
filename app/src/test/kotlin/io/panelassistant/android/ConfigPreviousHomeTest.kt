package io.panelassistant.android

import io.panelassistant.android.platform.ActivityRef
import org.junit.Assert.*
import org.junit.Test

class ConfigPreviousHomeTest {
    private val original = ActivityRef("com.vendor.launcher", "com.vendor.launcher.Home")

    @Test fun `first real home survives fresh config and repeated takeover`() {
        val prefs = fakePreferences()
        assertTrue(Config(prefs.instance).preservePreviousHome(original, listOf(original)))
        val updated = Config(prefs.instance)
        val other = ActivityRef("com.other.launcher", "com.other.launcher.Home")
        assertTrue(updated.preservePreviousHome(other, listOf(other)))
        assertEquals(original.component, updated.previousHomeComponent)
    }

    @Test fun `failed durable capture prevents takeover and can be retried`() {
        val prefs = fakePreferences(commitSucceeds = false)
        val config = Config(prefs.instance)
        assertFalse(config.preservePreviousHome(original, listOf(original)))
        assertNull(config.previousHomeComponent)
        prefs.commitsSucceed.set(true)
        assertTrue(config.preservePreviousHome(original, listOf(original)))
        assertEquals(original.component, config.previousHomeComponent)
    }

    @Test fun `fallback resolver app and non home activities are never recorded`() {
        val config = Config(fakePreferences().instance)
        for (candidate in listOf(ActivityRef("com.android.settings", "com.android.settings.FallbackHome"),
            ActivityRef("android", "android.ResolverActivity"),
            ActivityRef(AppIdentity.OWN, "${AppIdentity.OWN}.DashboardActivity"))) {
            assertTrue(config.preservePreviousHome(candidate, listOf(candidate)))
            assertNull(config.previousHomeComponent)
        }
        assertFalse(config.preservePreviousHome(original, emptyList()))
        assertNull(config.previousHomeComponent)
    }
    @Test fun `installer arms deferred ownership and only durable acknowledgement clears it`() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        assertTrue(config.preservePreviousHome(original, listOf(original), armTakeover = true))
        assertTrue(Config(prefs.instance).homeTakeoverPending)
        config.setupEverCompleted = true
        assertTrue(config.homeTakeoverPending)
        prefs.commitsSucceed.set(false)
        assertFalse(config.completeHomeTakeover())
        assertTrue(config.homeTakeoverPending)
        prefs.commitsSucceed.set(true)
        assertTrue(config.completeHomeTakeover())
        assertFalse(config.homeTakeoverPending)
    }

    @Test fun `explicit handback cancels deferred claim atomically with desired state`() {
        val prefs = fakePreferences()
        val config = Config(prefs.instance)
        assertTrue(config.preservePreviousHome(original, listOf(original), armTakeover = true))
        config.setTameVendorPackages("com.vendor.launcher")
        config.setLauncherPackage(AppIdentity.OWN)
        prefs.commitsSucceed.set(false)
        assertFalse(config.clearHandBackDesiredState())
        assertTrue(config.homeTakeoverPending)
        assertEquals(AppIdentity.OWN, config.launcherPackage)
        prefs.commitsSucceed.set(true)
        assertTrue(config.clearHandBackDesiredState())
        assertFalse(config.homeTakeoverPending)
        assertTrue(config.tameVendorPackages.isEmpty())
        assertEquals("", config.launcherPackage)
        assertEquals(original.component, config.previousHomeComponent)
    }

    @Test fun `pending ownership needs completed setup and exact observed home component`() {
        val config = Config(fakePreferences().instance)
        assertTrue(config.preservePreviousHome(original, listOf(original), armTakeover = true))
        val target = ActivityRef(AppIdentity.OWN, "${AppIdentity.OWN}.DashboardActivity")
        assertFalse(config.confirmHomeTakeover(target, target))
        assertTrue(config.homeTakeoverPending)
        config.setupEverCompleted = true
        assertFalse(config.confirmHomeTakeover(target, target.copy(cls = "${AppIdentity.OWN}.AdminLauncherActivity")))
        assertFalse(config.confirmHomeTakeover(target, null))
        assertTrue(config.homeTakeoverPending)
        assertTrue(config.confirmHomeTakeover(target, target))
        assertFalse(config.homeTakeoverPending)
    }

}
