package io.panelassistant.android.device.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLedCountTest {
    @Test fun aMeasuredStripLengthSurvivesYamlRoundTripAndReachesTheDeviceProfile() {
        for (mechanism in listOf("rk3576-ioctl", "autodetect")) {
            for (count in listOf(null, 1, 234, 1024)) {
                val yaml = yaml(mechanism, count)
                val parsed = ProfileYaml.parse(yaml)
                assertTrue(parsed.issues.toString(), parsed.issues.isEmpty())
                val document = requireNotNull(parsed.document)
                val issues = ProfileValidator.validate(document, "1.0.5", bundled = false)
                assertTrue(issues.toString(), issues.isEmpty())
                val profile = DataDeviceProfile(document, "", ProfileYaml.sha256(yaml), false)
                assertEquals(count, profile.ledCount)
            }
        }
    }

    @Test fun invalidStripLengthsAndMechanismsWithoutAStripRouteAreRefused() {
        val invalid = listOf(
            yaml("autodetect", 0),
            yaml("autodetect", 1025),
            yaml("autodetect", 234).replace("count: 234", "count: 23.4"),
            yaml("autodetect", 234).replace("count: 234", "count: '234'"),
            yaml("rk3576-ioctl-daemon", 234),
            yaml("sysfs-daemon", 234),
            yaml("none", 234),
        )
        for (raw in invalid) {
            val parsed = ProfileYaml.parse(raw)
            val issues = parsed.issues +
                parsed.document?.let { ProfileValidator.validate(it, "1.0.5", bundled = false) }.orEmpty()
            assertTrue(issues.toString(), issues.any {
                it.path == "hardware.led.count" && it.severity == ProfileIssueSeverity.ERROR
            })
        }
    }

    private fun yaml(mechanism: String, count: Int?): String {
        val baseline = testProfileDocument()
        return ProfileYaml.serialize(baseline.copy(
            hardware = baseline.hardware.copy(led = ProfileLed(mechanism, count = count)),
            requires = baseline.requires.copy(drivers = baseline.requires.drivers +
                setOf("led.$mechanism").filterNot { mechanism == "none" }),
        ))
    }
}
