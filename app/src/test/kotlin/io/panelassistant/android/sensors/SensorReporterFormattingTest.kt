package io.panelassistant.android.sensors

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorReporterFormattingTest {
    @Test
    fun `sensor JSON numbers remain valid under a comma-decimal locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)

            assertEquals("12.5", formatSensorValue(12.5f))
            assertEquals("12", formatSensorValue(12f))
        } finally {
            Locale.setDefault(original)
        }
    }
}
