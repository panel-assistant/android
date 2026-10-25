package io.panelassistant.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigSetupCompletionTest {
    @Test fun aConnectedFrontendCannotCompleteSetupBeforeItsQuestionsAreAnswered() {
        val config = Config(fakePreferences(mapOf("panel_id" to "setup-panel")).instance)
        config.setHaConnection("http://ha.local:8123", "fixture-token")
        config.recordBuiltinSetupCompletion()
        assertFalse(config.setupEverCompleted)
        config.setupIdentityConfirmed = true
        config.recordBuiltinSetupCompletion()
        assertFalse(config.setupEverCompleted)
        config.setupHomeDashboardChosen = true
        config.recordBuiltinSetupCompletion()
        assertFalse(config.setupEverCompleted)
        config.setupEntityFilterAnswered = true
        config.recordBuiltinSetupCompletion()
        assertTrue(config.setupEverCompleted)
    }

    @Test fun aConnectionRepairNeverClearsTheDurableCompletionProof() {
        val config = Config(fakePreferences(mapOf("panel_id" to "setup-panel")).instance)
        config.setupEverCompleted = true
        config.recordBuiltinSetupCompletion()
        assertTrue(config.setupEverCompleted)
    }
}
