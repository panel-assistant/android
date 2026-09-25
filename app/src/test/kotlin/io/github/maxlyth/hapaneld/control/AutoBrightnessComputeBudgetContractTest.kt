package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.adaptiveHaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoBrightnessComputeBudgetContractTest {
    @Test fun `disabled auto brightness does not select a Home Assistant source`() {
        assertNull(adaptiveHaSource(enabled = false, configuredEntity = "sensor.room_lux"))
        assertNull(adaptiveHaSource(enabled = true, configuredEntity = "  "))
        assertEquals(
            "sensor.room_lux",
            adaptiveHaSource(enabled = true, configuredEntity = " sensor.room_lux "),
        )
    }


}
