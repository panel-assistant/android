package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceConfigurationListenerTest {
    @Test fun durableFirstCompletionReappliesTheExistingHomePolicy() {
        val changes = mutableListOf<Set<String>>()
        val listener = serviceConfigurationListener(changes::add) { error("unexpected voice restart") }

        listener.onSharedPreferenceChanged(fakePreferences().instance, SETUP_EVER_COMPLETED_PREF)

        assertEquals(listOf(setOf(SETUP_EVER_COMPLETED_PREF)), changes)
        assertTrue(configOwnerRefreshPlan(changes.single()).launcherHome)
    }

    @Test fun voiceChangesRetainTheirOwnRestartAndDoNotClaimHome() {
        var restarts = 0
        val listener = serviceConfigurationListener({ error("unexpected HOME policy change") }) { restarts++ }
        listener.onSharedPreferenceChanged(fakePreferences().instance, "voice_enabled")
        assertEquals(1, restarts)
    }
}
