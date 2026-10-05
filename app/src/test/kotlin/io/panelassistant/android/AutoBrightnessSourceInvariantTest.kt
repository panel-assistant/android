package io.panelassistant.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBrightnessSourceInvariantTest {
    @Test fun `enabled state is cleared when no ambient source can exist`() {
        assertTrue(
            shouldDisableAutoBrightnessForMissingSource(
                enabled = true,
                haEntity = "",
                hasLocalSensor = false,
            ),
        )
    }

    @Test fun `valid configuration routes are preserved`() {
        assertFalse(shouldDisableAutoBrightnessForMissingSource(true, "", hasLocalSensor = true))
        assertFalse(
            shouldDisableAutoBrightnessForMissingSource(
                enabled = true,
                haEntity = "sensor.office_illuminance",
                hasLocalSensor = false,
            ),
        )
        assertFalse(shouldDisableAutoBrightnessForMissingSource(false, "", hasLocalSensor = false))
    }
}
