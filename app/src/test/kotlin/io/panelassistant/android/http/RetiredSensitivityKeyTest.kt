package io.panelassistant.android.http

import io.panelassistant.android.config.SettingsRegistry
import io.ktor.http.Parameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `auto_brightness_sensitivity` was retired for a key on a different scale. This admission step is
 * deliberately atomic, so an unknown key does not merely get dropped — it refuses the whole request.
 * A script or automation written against the previous release therefore lost every other setting it
 * sent alongside that one key, silently, on the first upgraded panel it touched.
 */
class RetiredSensitivityKeyTest {

    private fun normalize(vararg pairs: Pair<String, String>) =
        normalizeConfigPostParameters(Parameters.build { pairs.forEach { (k, v) -> append(k, v) } })

    @Test fun `the retired key is accepted and carried onto the new scale`() {
        val result = normalize(SettingsRegistry.LEGACY_SENSITIVITY_KEY to "50")
        assertTrue("expected acceptance, got $result", result is ConfigPostParameters.Ok)
        val ok = result as ConfigPostParameters.Ok
        // The old neutral is full response on the new scale, which is what the live-store migration does.
        assertEquals("100", ok.values[SettingsRegistry.RESPONSE_PERCENT_KEY])
        assertEquals(null, ok.values[SettingsRegistry.LEGACY_SENSITIVITY_KEY])
    }

    @Test fun `the retired key does not take the rest of the request down with it`() {
        val result = normalize(
            SettingsRegistry.LEGACY_SENSITIVITY_KEY to "25",
            "panel_id" to "alpha",
        )
        assertTrue("expected acceptance, got $result", result is ConfigPostParameters.Ok)
        val ok = result as ConfigPostParameters.Ok
        assertEquals("alpha", ok.values["panel_id"])
        assertEquals("50", ok.values[SettingsRegistry.RESPONSE_PERCENT_KEY])
    }

    @Test fun `a genuinely unknown key is still refused`() {
        assertTrue(normalize("not_a_setting" to "1") is ConfigPostParameters.Bad)
    }
}
