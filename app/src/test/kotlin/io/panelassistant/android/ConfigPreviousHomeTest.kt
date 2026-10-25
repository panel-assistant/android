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
}
