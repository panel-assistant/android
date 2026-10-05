package io.panelassistant.android.device

import io.panelassistant.android.device.profile.BundledProfileFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the NSPanel Pro **recommended tame set** — the `defaultTame` compatibility projection badged in
 * the explicit picker. Membership is a deliberate policy call (only genuinely-intrusive firmware
 * clutter, never something a user might actually want), so it's asserted here so it can't silently drift.
 */
class RecommendedTameTest {
    private val panel = BundledProfileFixtures.profile("nspanel-pro")
    private val fallback = BundledProfileFixtures.fallback()
    private val recommended = panel.tameVendorCandidates.filter { it.defaultTame }.map { it.pkg }.toSet()

    @Test fun recommendedSetIsExactlyTheIntrusiveClutter() {
        assertEquals(
            setOf(
                "com.eWeLinkControlPanel",   // draws a widget over the dashboard
                "com.android.rk",            // Rockchip factory/test app
                "com.cghs.stresstest",       // burn-in tool
                "com.smatek.test",           // CoolKit factory test tool
                "com.rockchip.devicetest",   // Rockchip device-test suite
                "com.DeviceTest",            // factory device-test app
            ),
            recommended,
        )
    }

    @Test fun eWeLinkKioskIsRecommended() {
        // The whole point of the item: explicitly taming this package hides the over-dashboard panel.
        assertTrue("com.eWeLinkControlPanel" in recommended)
    }

    @Test fun demoVideoPlayerIsNotRecommended() {
        // A demo the user might actually use — a deliberate per-package choice, not stripped by default.
        assertFalse("android.rk.RockVideoPlayer" in recommended)
        // …but it's still a listed candidate (offered in the picker), just not defaultTame.
        assertTrue(panel.tameVendorCandidates.any { it.pkg == "android.rk.RockVideoPlayer" })
    }

    @Test fun defaultTameOffForUnmarkedProfiles() {
        // defaultTame is a legacy name for recommendation dashboard_entity; it never implies execution consent.
        // The packaged fallback has no candidates at all.
        assertTrue(fallback.tameVendorCandidates.none { it.defaultTame })
    }
}
