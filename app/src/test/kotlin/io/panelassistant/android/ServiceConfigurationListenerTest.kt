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

    @Test fun aRefreshedAccessTokenReachesNoOwnerRefreshButARouteMoveRefreshesAutoSleep() {
        val prefs = fakePreferences(initial = mapOf(
            "ha_url" to "https://ha.local",
            "ha_token" to "old-access",
            "ha_refresh_token" to "refresh",
            "ha_token_expiry" to 1234L,
            "ha_client_id" to "client",
        ))
        val config = Config(prefs.instance)
        val before = prefs.values.toMap()
        assertTrue(config.setHaRefreshedTokenIfOwned(config.haAuthSnapshot(), "new-access", 5678L))
        val written = prefs.values.keys.filter { prefs.values[it] != before[it] }.toSet()
        assertEquals(setOf("ha_token", "ha_token_expiry"), written)

        val changes = mutableListOf<Set<String>>()
        val listener = serviceConfigurationListener(changes::add) { error("unexpected voice restart") }
        written.forEach { listener.onSharedPreferenceChanged(prefs.instance, it) }
        assertEquals("an access-token refresh must not refresh auto-sleep", emptyList<Set<String>>(), changes)

        listener.onSharedPreferenceChanged(prefs.instance, HaConnectionRoutes.EPOCH_KEY)
        assertTrue(configOwnerRefreshPlan(changes.single()).autoSleep)
    }

    @Test fun voiceChangesRetainTheirOwnRestartAndDoNotClaimHome() {
        var restarts = 0
        val listener = serviceConfigurationListener({ error("unexpected HOME policy change") }) { restarts++ }
        listener.onSharedPreferenceChanged(fakePreferences().instance, "voice_enabled")
        assertEquals(1, restarts)
    }
}
