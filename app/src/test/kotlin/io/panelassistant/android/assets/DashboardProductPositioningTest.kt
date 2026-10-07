package io.panelassistant.android.assets

import io.panelassistant.android.testsupport.TestSources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class DashboardProductPositioningTest {
    // Source-text reason: loads the shipped English i18n catalogue as input data.
    private val english = JSONObject(
        TestSources.appFile("src/main/assets/i18n/en.json").readText(),
    ).getJSONObject("strings")
    private fun english(key: String): String = english.getJSONObject(key).getString("text")

    @Test fun missingRendererGuidanceCatalogueContractLeadsWithSupportedChoices() {
        val title = english("configure.setup.renderer.title")
        val body = english("configure.setup.renderer.body")
        assertTrue(title.startsWith("MQTT is configured. Next:"))
        assertTrue(body.contains("ha-paneld's built-in renderer"))
        assertTrue(body.contains("another dashboard package"))
        assertFalse(body.contains("Fully Kiosk", ignoreCase = true))
    }

    @Test fun builtinRendererSignInGuidanceCatalogueContractDoesNotLookLikeTheMqttStepFailed() {
        val title = english("dashboard.banner.ha_sign_in.title")
        val explanation = english("dashboard.banner.ha_sign_in.explanation")
        assertTrue(title.startsWith("MQTT is configured. Next:"))
        assertTrue(explanation.contains("ha-paneld's built-in renderer is selected"))
        assertFalse(title.contains("failed", ignoreCase = true))
        assertFalse(explanation.contains("failed", ignoreCase = true))
    }
}
