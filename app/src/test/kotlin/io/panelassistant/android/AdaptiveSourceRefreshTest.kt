package io.panelassistant.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveSourceRefreshTest {
    @Test fun `same-source live refresh preserves availability and subscription`() {
        var unavailableMarks = 0
        val sourceWrites = mutableListOf<String?>()

        refreshAmbientSourceBinding(
            restartSource = false,
            selectedSource = "sensor.office_illuminance",
            markUnavailable = { unavailableMarks += 1 },
            setSource = sourceWrites::add,
        )

        assertEquals(0, unavailableMarks)
        assertEquals(listOf("sensor.office_illuminance"), sourceWrites)
    }

    @Test fun `real source restart clears availability before rebinding`() {
        var unavailableMarks = 0
        val sourceWrites = mutableListOf<String?>()

        refreshAmbientSourceBinding(
            restartSource = true,
            selectedSource = "sensor.office_illuminance",
            markUnavailable = { unavailableMarks += 1 },
            setSource = sourceWrites::add,
        )

        assertEquals(1, unavailableMarks)
        assertEquals(listOf(null, "sensor.office_illuminance"), sourceWrites)
    }
}
