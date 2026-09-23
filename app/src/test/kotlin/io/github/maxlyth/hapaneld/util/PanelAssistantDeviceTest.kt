package io.github.maxlyth.hapaneld.util

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
        androidRelease: String? = "14",
        buildDisplay: String? = "TQ3A.230805.001",
        area: String? = "Test Area",
    ) = JSONObject(
        PanelAssistantDevice.json(
            friendlyName, manufacturer, model, androidRelease, buildDisplay, area,
        ),
    )

    @Test fun completeHardwareFactsProjectEveryCardField() {
        val device = project()
        assertEquals("Test Panel", device.getString("name"))
        assertEquals("Electron", device.getString("manufacturer"))
        assertEquals("WF1589T", device.getString("model"))
        assertEquals("Android 14 · TQ3A.230805.001", device.getString("hw_version"))
        assertEquals("Test Area", device.getString("area"))
        assertEquals(5, device.length())
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

    @Test fun oneMissingHardwareFactNeverInventsTheOther() {
        assertEquals("Android 14", project(buildDisplay = null).getString("hw_version"))
        assertFalse(project(androidRelease = null).has("hw_version"))
        assertFalse(project(androidRelease = "", buildDisplay = "TQ3A").has("hw_version"))
    }

    @Test fun anUnusablePanelStillProducesAValidEmptyObject() {
        val device = project(
            friendlyName = null,
            manufacturer = null,
            model = null,
            androidRelease = null,
            buildDisplay = null,
            area = null,
        )
        assertEquals(0, device.length())
    }

    @Test fun theProjectionNeverCarriesAHardwareIdentifier() {
        val raw = PanelAssistantDevice.json(
            "Test Panel", "Electron", "WF1589T (ha-paneld)", "14", "TQ3A.230805.001", "Test Area",
        )
        for (forbidden in listOf("serial", "android_id", "androidId", "mac", "did")) {
            assertFalse(forbidden, raw.contains(forbidden, ignoreCase = true))
        }
    }
}
