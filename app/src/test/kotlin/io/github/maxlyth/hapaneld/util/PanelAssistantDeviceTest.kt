package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.device.profile.BundledProfileFixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device projection is presentation text, so every unusable field must vanish, not leak. */
class PanelAssistantDeviceTest {
    private fun project(
        friendlyName: String? = "Test Panel",
        manufacturer: String? = "Electron",
        model: String? = "WF1589T (ha-paneld)",
        area: String? = "Test Area",
    ) = JSONObject(PanelAssistantDevice.json(friendlyName, manufacturer, model, area))

    @Test fun completeHardwareFactsProjectEveryCardField() {
        val device = project()
        assertEquals("Test Panel", device.getString("name"))
        assertEquals("Electron", device.getString("manufacturer"))
        assertEquals("WF1589T", device.getString("model"))
        assertEquals("Test Area", device.getString("area"))
        assertEquals(4, device.length())
    }

    /** The Android release and build string was the card's Hardware line and told a user nothing. */
    @Test fun theCardCarriesNoAndroidReleaseAsItsHardwareLine() {
        val device = project()
        assertFalse(device.has("hw_version"))
        assertFalse(device.toString().contains("Android"))
    }

    /**
     * The X2i reports the Android codename `Jenna` as its model, device and product, so the card
     * names the product only because the bundled profile does. This is the value `Config.model`
     * takes, application marker and all, when nobody has overridden it.
     */
    @Test fun theBundledX2iProfileNamesTheProductRatherThanTheAndroidCodename() {
        val x2i = BundledProfileFixtures.profile("shelly-wall-display-x2i", "shelly_x2_1.0.0")
        val device = project(
            manufacturer = x2i.manufacturer,
            model = x2i.model + PanelAssistantDevice.APP_MODEL_SUFFIX,
        )
        assertEquals("Shelly", device.optString("manufacturer", null))
        assertEquals("Wall Display X2i", device.optString("model", null))
    }

    @Test fun theApplicationMarkerNeverReachesTheHardwareModel() {
        assertEquals("WF1589T", project(model = "WF1589T (ha-paneld)").getString("model"))
        assertEquals("WF1589T", project(model = "WF1589T (ha-paneld) ").getString("model"))
        assertEquals("NSPanel 86", project(model = "NSPanel 86").getString("model"))
        assertFalse(project(model = " (ha-paneld)").has("model"))
    }

    @Test fun aFieldThePanelCannotStateSafelyIsAbsentRatherThanBlank() {
        assertFalse(project(friendlyName = "").has("name"))
        assertFalse(project(friendlyName = "   ").has("name"))
        assertFalse(project(friendlyName = null).has("name"))
        assertFalse(project(manufacturer = "a".repeat(129)).has("manufacturer"))
        assertFalse(project(area = "Two\nLines").has("area"))
        assertFalse(project(friendlyName = "Bell\u0007").has("name"))
        assertFalse(project(area = "Two\u2028Lines").has("area"))
        assertFalse(project(area = "Two\u2029Lines").has("area"))
        assertFalse(project(friendlyName = "Family \uD83D\uDC68\u200D\uD83D\uDC69").has("name"))
        assertFalse(project(friendlyName = "Private \uE000").has("name"))
        assertFalse(project(friendlyName = "Unassigned \u0378").has("name"))
        assertFalse(project(friendlyName = "Surrogate \uD800").has("name"))
        assertTrue(project(manufacturer = "a".repeat(128)).has("manufacturer"))
        assertEquals("Rocket \uD83D\uDE80", project(friendlyName = "Rocket \uD83D\uDE80").getString("name"))
    }

    @Test fun anUnusablePanelStillProducesAValidEmptyObject() {
        val device = project(
            friendlyName = null,
            manufacturer = null,
            model = null,
            area = null,
        )
        assertEquals(0, device.length())
    }

    @Test fun theProjectionNeverCarriesAHardwareIdentifier() {
        val raw = PanelAssistantDevice.json(
            "Test Panel", "Electron", "WF1589T (ha-paneld)", "Test Area",
        )
        for (forbidden in listOf("serial", "android_id", "androidId", "mac", "did")) {
            assertFalse(forbidden, raw.contains(forbidden, ignoreCase = true))
        }
    }
}
