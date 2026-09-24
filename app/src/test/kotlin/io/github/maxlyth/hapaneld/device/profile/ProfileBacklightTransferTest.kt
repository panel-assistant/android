package io.github.maxlyth.hapaneld.device.profile

import io.github.maxlyth.hapaneld.hardware.LedTransfer
import io.github.maxlyth.hapaneld.hardware.TransferCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBacklightTransferTest {

    private fun profile(document: ProfileDocument) =
        DataDeviceProfile(document = document, productVersion = "", revision = "test", trustedBundledContent = true)

    private fun withBacklight(backlight: ProfileBacklight?) =
        testProfileDocument().let { it.copy(hardware = it.hardware.copy(backlight = backlight)) }

    private fun parseWithHardwareLines(vararg lines: String): ProfileParseResult {
        val yaml = ProfileYaml.serialize(testProfileDocument())
        val anchor = Regex("(?m)^hardware:\\n").find(yaml)!!
        return ProfileYaml.parse(yaml.substring(0, anchor.range.last + 1) + lines.joinToString("") { "$it\n" } + yaml.substring(anchor.range.last + 1))
    }

    @Test fun anUnprofiledPanelKeepsTheLinearBacklight() {
        val document = testProfileDocument()
        assertNull(document.hardware.backlight)
        assertFalse(Regex("(?m)^  backlight:").containsMatchIn(ProfileYaml.serialize(document)))
        assertSame(TransferCurve.Identity, profile(document).backlightTransfer)
        for (bundled in BundledProfileFixtures.bundled) {
            if (bundled.document.hardware.backlight == null) {
                assertSame(bundled.file.name, TransferCurve.Identity, bundled.profile().backlightTransfer)
            }
        }
    }

    @Test fun eachCurveShapeRoundTripsAndReachesTheProfile() {
        val cases = listOf(
            ProfileBacklight(transfer = "identity") to TransferCurve.Identity,
            ProfileBacklight(transfer = "perceptual", floor = 12) to TransferCurve.Gamma(2.2, 12 / 255.0),
            ProfileBacklight(transfer = "gamma", gamma = 1.8) to TransferCurve.Gamma(1.8),
            ProfileBacklight(transfer = "points", points = listOf(0 to 0, 64 to 10, 255 to 255), floor = 4) to
                TransferCurve.Points(listOf(0 to 0, 64 to 10, 255 to 255), 4 / 255.0),
        )
        for ((backlight, curve) in cases) {
            val document = withBacklight(backlight)
            val parsed = ProfileYaml.parse(ProfileYaml.serialize(document))
            assertEquals(backlight.toString(), emptyList<ProfileIssue>(), parsed.issues)
            assertEquals(document, parsed.document)
            assertTrue(ProfileValidator.validate(parsed.document!!, "1.0.0", bundled = false).isEmpty())
            assertEquals(curve, profile(parsed.document!!).backlightTransfer)
        }
    }

    @Test fun invalidCurvesAreRefusedByTheValidator() {
        listOf(
            ProfileBacklight(transfer = "sigmoid"),
            ProfileBacklight(transfer = "gamma"),
            ProfileBacklight(transfer = "gamma", gamma = 9.0),
            ProfileBacklight(transfer = "perceptual", floor = 200),
            ProfileBacklight(transfer = "identity", floor = 5),
            ProfileBacklight(transfer = "points", points = listOf(0 to 0, 128 to 90, 100 to 95, 255 to 255)),
            ProfileBacklight(transfer = "points", points = listOf(0 to 0, 255 to 128)),
        ).forEach { backlight ->
            val issues = ProfileValidator.validate(withBacklight(backlight), "1.0.0", bundled = false)
            val issue = issues.singleOrNull { it.path == "hardware.backlight" }
            assertTrue(backlight.toString(), issue?.message.orEmpty().startsWith("Invalid backlight transfer: "))
            // A refused document never reaches a runtime curve other than the passthrough.
            assertSame(TransferCurve.Identity, profile(withBacklight(backlight)).backlightTransfer)
        }
    }

    @Test fun malformedYamlIsRefusedByTheParser() {
        val badPoints = parseWithHardwareLines("  backlight:", "    transfer: points", "    points: [[0, 0], [128], [255, 255]]")
        assertNull(badPoints.document)
        assertTrue(badPoints.issues.any { it.path == "hardware.backlight.points[1]" && it.message == "Expected an integer pair [request, hardware]." })

        val missingTransfer = parseWithHardwareLines("  backlight:", "    floor: 10")
        assertNull(missingTransfer.document)
        assertTrue(missingTransfer.issues.any { it.path == "hardware.backlight.transfer" })

        val unknownKey = parseWithHardwareLines("  backlight:", "    transfer: perceptual", "    knee: 3")
        assertNull(unknownKey.document)
        assertTrue(unknownKey.issues.any { it.path == "hardware.backlight.knee" })

        val good = parseWithHardwareLines("  backlight:", "    transfer: gamma", "    gamma: 2.4", "    floor: 8")
        assertEquals(emptyList<ProfileIssue>(), good.issues)
        assertEquals(ProfileBacklight(transfer = "gamma", gamma = 2.4, floor = 8), good.document!!.hardware.backlight)
    }

    @Test fun ledTransferAcceptsThePerceptualCurve() {
        val document = testProfileDocument().let { it.copy(hardware = it.hardware.copy(led = it.hardware.led.copy(transfer = "perceptual"))) }
        assertTrue(ProfileValidator.validate(document, "1.0.0", bundled = false).isEmpty())
        val led = profile(document).ledTransfer
        assertEquals(56, led.red(128))
        assertEquals(0, led.green(0))
        assertEquals(255, led.blue(255))

        val unknown = testProfileDocument().let { it.copy(hardware = it.hardware.copy(led = it.hardware.led.copy(transfer = "sigmoid"))) }
        assertTrue(ProfileValidator.validate(unknown, "1.0.0", bundled = false).any { it.path == "hardware.led.transfer" })
        assertSame(LedTransfer.Rk3576FourBit, LedTransfer.named("rk3576-four-bit"))
        assertSame(LedTransfer.Identity, LedTransfer.named("identity"))
    }

    @Test fun metadataDescribesEveryBacklightField() {
        val fields = ProfileMetadata.schema.fields.associateBy { it.path }
        assertEquals(listOf("identity", "perceptual", "gamma", "points"), fields.getValue("hardware.backlight.transfer").enumValues)
        assertEquals(LedTransfer.NAMES.toList(), fields.getValue("hardware.led.transfer").enumValues)
        for (path in listOf("hardware.backlight.gamma", "hardware.backlight.points", "hardware.backlight.floor")) {
            assertFalse(path, fields.getValue(path).required)
        }
    }
}
