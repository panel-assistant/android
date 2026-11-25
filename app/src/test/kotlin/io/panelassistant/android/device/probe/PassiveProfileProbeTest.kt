package io.panelassistant.android.device.probe

import io.panelassistant.android.device.profile.DeviceFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassiveProfileProbeTest {
    @Test
    fun `report contains only bounded passive observations`() {
        val report = PassiveProfileReportFactory.create(
            PassiveProbeSnapshot(
                generatedAtEpochMs = 123L,
                facts = DeviceFacts("Panel X", "board_x", "fw_1.2.3"),
                androidSdk = 30,
                abis = listOf("arm64-v8a"),
                board = "rk-board",
                hardware = "rockchip",
                widthPx = 1280,
                heightPx = 800,
                densityDpi = 240,
                sensors = setOf(PassiveSensor.LIGHT, PassiveSensor.PROXIMITY),
                ledJniReadable = false,
                ws2812PwmReadable = true,
                sysfsRgbReadable = false,
                cpuGovernors = listOf("schedutil", "performance"),
            ),
        )

        assertEquals(123L, report.generatedAtEpochMs)
        assertEquals("Panel X", report.facts.model)
        assertEquals("true", report.observations.single { it.path == "sensors.light_technology" }.value)
        assertEquals("false", report.observations.single { it.path == "evidence.sensors.temperature" }.value)
        val ledEvidence = report.observations.filter { it.path.startsWith("evidence.led.") }.associate { it.path to it.value }
        assertEquals(
            mapOf(
                "evidence.led.dev_ledjni_readable" to "false",
                "evidence.led.dev_ws2812_pwm_readable" to "true",
                "evidence.led.sysfs_rgb_readable" to "false",
            ),
            ledEvidence,
        )
        assertEquals(
            "schedutil,performance",
            report.observations.single { it.path == "evidence.cpu.available_governors" }.value,
        )
        assertTrue(report.observations.all { it.source.isNotBlank() })
    }
}
