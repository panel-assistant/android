package io.panelassistant.android.device.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileDraftFactoryTest {
    @Test fun `passive draft is valid conservative and exactly matched`() {
        val facts = DeviceFacts("Panel X", "board_x", "fw_1.2.3")
        val report = PassiveProfileReport(
            generatedAtEpochMs = 42,
            facts = facts,
            observations = listOf(
                PassiveProfileObservation("sensors.light", "true", "Android SensorManager", PassiveProfileConfidence.OBSERVED),
                PassiveProfileObservation("sensors.proximity", "true", "Android SensorManager", PassiveProfileConfidence.OBSERVED),
            ),
        )

        val draft = ProfileDraftFactory.fromReport(report)
        val parsed = ProfileYaml.parse(draft.rawYaml).document!!

        assertTrue(ProfileValidator.validate(parsed, "1.0.0", bundled = false).isEmpty())
        assertEquals(ProfileMaturity.DRAFT, parsed.metadata.maturity)
        assertEquals(ShizukuRecommendation.NONE, parsed.provisioning.access.shizuku)
        assertFalse(draft.rawYaml.contains("shizuku", ignoreCase = true))
        assertEquals("none", parsed.hardware.led.mechanism)
        assertFalse(parsed.platform.appCanSu)
        assertEquals(setOf("screen.brightness-zero", "sensor.android"), parsed.requires.drivers)
        assertEquals("Ambient light", parsed.sensors.lightTechnology)
        assertEquals("Android proximity sensor", parsed.sensors.proximityTechnology)
        assertTrue(parsed.matches(facts))
        assertFalse(parsed.matches(facts.copy(device = "different")))
        assertTrue(parsed.metadata.limitations.all { "TODO:" in it })
    }

    @Test fun `non ascii model still produces valid ascii community id`() {
        val draft = ProfileDraftFactory.create(DeviceFacts("Pánel 控制", "", ""))
        val parsed = ProfileYaml.parse(draft.rawYaml).document!!

        assertTrue(parsed.id.matches(Regex("^[a-z0-9.-]+$")))
        assertTrue(ProfileValidator.validate(parsed, "1.0.0", bundled = false).isEmpty())
    }
}
