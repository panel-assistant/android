package io.panelassistant.android.device.profile

import io.panelassistant.android.BuildConfig
import io.panelassistant.android.testsupport.scratchDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Uses the existing startup API so the same contract executes on the pre-XL core. */
class ShellyXlSelectionContractTest {
    @Test fun blakeStartsWithTheDedicatedRootlessXlButtonProfile() {
        val directory = scratchDirectory("xl-selection-contract").toFile()
        val preferences = object : ProfilePreferences {
            override fun getString(key: String, default: String) = default
            override fun getLong(key: String, default: Long) = default
            override fun put(vararg values: Pair<String, Any?>) = true
        }
        try {
            val profile = RuntimeProfileRegistry(directory, preferences, BundledProfileFixtures::rawByName,
                DeviceFacts("blake", "", ""), BuildConfig.VERSION_NAME, { 1_000L }).resolveForStartup().profile
            assertEquals("shelly-wall-display-xl", profile.id)
            assertFalse(profile.appCanSu)
            assertEquals(listOf("KEYCODE_F1", "KEYCODE_F2", "KEYCODE_F3", "KEYCODE_F4", "KEYCODE_F10"),
                profile.evdevButtons.map { it.eventType })
        } finally { directory.deleteRecursively() }
    }
}
