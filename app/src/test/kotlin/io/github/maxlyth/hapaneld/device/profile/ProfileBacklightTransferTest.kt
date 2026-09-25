package io.github.maxlyth.hapaneld.device.profile

import io.github.maxlyth.hapaneld.device.BacklightRoute
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

    private fun withHardware(change: (ProfileHardware) -> ProfileHardware) =
        testProfileDocument().let { it.copy(hardware = change(it.hardware)) }

    private fun withBacklight(backlight: ProfileBacklight?) = withHardware { it.copy(backlight = backlight) }

    private fun withLed(led: (ProfileLed) -> ProfileLed) = withHardware { it.copy(led = led(it.led)) }

    private fun withButtons(curve: ProfileLightCurve?, present: Boolean = true) =
        withHardware { it.copy(hasButtonBacklight = present, buttonBacklight = curve) }.let { document ->
            if (!present) document
            else document.copy(requires = document.requires.copy(drivers = document.requires.drivers + "input.button-backlight"))
        }

    private fun issues(document: ProfileDocument) = ProfileValidator.validate(document, "1.0.0", bundled = false)

    private fun roundTrips(document: ProfileDocument): ProfileDocument {
        val parsed = ProfileYaml.parse(ProfileYaml.serialize(document))
        assertEquals(emptyList<ProfileIssue>(), parsed.issues)
        assertEquals(document, parsed.document)
        assertEquals(emptyList<ProfileIssue>(), issues(parsed.document!!))
        return parsed.document!!
    }

    private fun parseWithHardwareLines(vararg lines: String): ProfileParseResult {
        val yaml = ProfileYaml.serialize(testProfileDocument())
        val anchor = Regex("(?m)^hardware:\\n").find(yaml)!!
        return ProfileYaml.parse(yaml.substring(0, anchor.range.last + 1) + lines.joinToString("") { "$it\n" } + yaml.substring(anchor.range.last + 1))
    }

    @Test fun anUnprofiledPanelKeepsEveryLightLinear() {
        val document = testProfileDocument()
        assertNull(document.hardware.backlight)
        assertNull(document.hardware.buttonBacklight)
        val yaml = ProfileYaml.serialize(document)
        assertFalse(Regex("(?m)^  (backlight|button_backlight):").containsMatchIn(yaml))
        assertFalse(Regex("(?m)^    (gamma|points|floor):").containsMatchIn(yaml))
        val runtime = profile(document)
        assertSame(TransferCurve.Identity, runtime.backlightTransfer)
        assertSame(TransferCurve.Identity, runtime.buttonBacklightTransfer)
        assertSame(LedTransfer.Identity, runtime.ledTransfer)
        for (bundled in BundledProfileFixtures.bundled) {
            val hardware = bundled.document.hardware
            if (hardware.backlight == null) assertSame(bundled.file.name, TransferCurve.Identity, bundled.profile().backlightTransfer)
            if (hardware.buttonBacklight == null) assertSame(bundled.file.name, TransferCurve.Identity, bundled.profile().buttonBacklightTransfer)
        }
    }

    @Test fun eachBacklightShapeRoundTripsAndReachesTheProfile() {
        val cases = listOf(
            ProfileBacklight(ProfileLightCurve("identity")) to TransferCurve.Identity,
            ProfileBacklight(ProfileLightCurve("perceptual", floor = 12), route = "setting") to TransferCurve.Gamma(2.2, 12 / 255.0),
            ProfileBacklight(ProfileLightCurve("gamma", gamma = 1.8), route = "node") to TransferCurve.Gamma(1.8),
            ProfileBacklight(ProfileLightCurve("points", points = listOf(0 to 0, 1 to 4, 64 to 10, 255 to 255)), route = "node") to
                TransferCurve.Points(listOf(0 to 0, 1 to 4, 64 to 10, 255 to 255)),
        )
        for ((backlight, curve) in cases) {
            assertEquals(backlight.toString(), curve, profile(roundTrips(withBacklight(backlight))).backlightTransfer)
        }
    }

    @Test fun theDeclaredRouteReachesTheProfile() {
        val setting = profile(roundTrips(withBacklight(ProfileBacklight(ProfileLightCurve("perceptual"), route = "setting"))))
        assertEquals(BacklightRoute.SETTING, setting.backlightRoute)
        val node = profile(roundTrips(withBacklight(ProfileBacklight(ProfileLightCurve("perceptual"), route = "node"))))
        assertEquals(BacklightRoute.NODE, node.backlightRoute)
        assertEquals(BacklightRoute.NODE, profile(testProfileDocument()).backlightRoute)
    }

    @Test fun aDeclaredCurveMustNameAKnownRoute() {
        val unrouted = issues(withBacklight(ProfileBacklight(ProfileLightCurve("perceptual"))))
        assertTrue(unrouted.any { it.path == "hardware.backlight.route" && it.message.contains("must name its route") })
        val unknown = issues(withBacklight(ProfileBacklight(ProfileLightCurve("perceptual"), route = "framework")))
        assertTrue(unknown.any { it.path == "hardware.backlight.route" && it.message.contains("Unknown backlight route") })
        // The identity curve changes nothing, so it needs no route.
        assertEquals(emptyList<ProfileIssue>(), issues(withBacklight(ProfileBacklight(ProfileLightCurve("identity")))))
    }

    @Test fun invalidCurvesAreRefusedByTheValidator() {
        listOf(
            ProfileLightCurve("sigmoid"),
            ProfileLightCurve("gamma"),
            ProfileLightCurve("gamma", gamma = 9.0),
            ProfileLightCurve("perceptual", floor = 200),
            ProfileLightCurve("identity", floor = 5),
            ProfileLightCurve("points", points = listOf(0 to 0, 128 to 90, 100 to 95, 255 to 255)),
            ProfileLightCurve("points", points = listOf(0 to 0, 255 to 128)),
            ProfileLightCurve("points", points = listOf(0 to 0, 64 to 10, 255 to 255), floor = 4),
        ).forEach { curve ->
            val backlight = withBacklight(ProfileBacklight(curve, route = "node"))
            assertTrue(curve.toString(), issues(backlight).filter { it.path == "hardware.backlight" }.map { it.message }.let { it.size == 1 && it[0].startsWith("Invalid backlight transfer: ") })
            // A refused document never reaches a runtime curve other than the passthrough.
            assertSame(TransferCurve.Identity, profile(backlight).backlightTransfer)

            val buttons = withButtons(curve)
            assertTrue(curve.toString(), issues(buttons).filter { it.path == "hardware.button_backlight" }.map { it.message }.let { it.size == 1 && it[0].startsWith("Invalid key-backlight transfer: ") })
            assertSame(TransferCurve.Identity, profile(buttons).buttonBacklightTransfer)

            if (curve.transfer in LedTransfer.NAMES) {
                val led = withLed { it.copy(transfer = curve.transfer, gamma = curve.gamma, points = curve.points, floor = curve.floor) }
                assertTrue(curve.toString(), issues(led).filter { it.path == "hardware.led" }.map { it.message }.let { it.size == 1 && it[0].startsWith("Invalid LED transfer: ") })
            }
        }
    }

    @Test fun malformedYamlIsRefusedByTheParser() {
        val badPoints = parseWithHardwareLines("  backlight:", "    transfer: points", "    route: node", "    points: [[0, 0], [128], [255, 255]]")
        assertNull(badPoints.document)
        assertTrue(badPoints.issues.any { it.path == "hardware.backlight.points[1]" && it.message == "Expected an integer pair [request, hardware]." })

        val missingTransfer = parseWithHardwareLines("  backlight:", "    floor: 10")
        assertNull(missingTransfer.document)
        assertTrue(missingTransfer.issues.any { it.path == "hardware.backlight.transfer" })

        val unknownKey = parseWithHardwareLines("  backlight:", "    transfer: perceptual", "    knee: 3")
        assertNull(unknownKey.document)
        assertTrue(unknownKey.issues.any { it.path == "hardware.backlight.knee" })

        val good = parseWithHardwareLines("  backlight:", "    transfer: gamma", "    gamma: 2.4", "    floor: 8", "    route: setting")
        assertEquals(emptyList<ProfileIssue>(), good.issues)
        assertEquals(ProfileBacklight(ProfileLightCurve("gamma", gamma = 2.4, floor = 8), route = "setting"), good.document!!.hardware.backlight)

        val buttons = parseWithHardwareLines("  button_backlight:", "    transfer: perceptual", "    floor: 6")
        assertEquals(emptyList<ProfileIssue>(), buttons.issues)
        assertEquals(ProfileLightCurve("perceptual", floor = 6), buttons.document!!.hardware.buttonBacklight)
    }

    @Test fun theLedTakesTheSameCurveDeclaration() {
        val perceptual = profile(roundTrips(withLed { it.copy(transfer = "perceptual") })).ledTransfer
        assertEquals(56, perceptual.red(128))
        assertEquals(0, perceptual.green(0))
        assertEquals(255, perceptual.blue(255))

        val gamma = profile(roundTrips(withLed { it.copy(transfer = "gamma", gamma = 2.0, floor = 3) })).ledTransfer
        assertEquals(TransferCurve.Gamma(2.0, 3 / 255.0).toHardware(128), gamma.red(128))
        assertEquals(3, gamma.green(1))

        val points = profile(roundTrips(withLed { it.copy(transfer = "points", points = listOf(0 to 0, 128 to 32, 255 to 255)) })).ledTransfer
        assertEquals(32, points.blue(128))

        assertSame(LedTransfer.Rk3576FourBit, profile(roundTrips(withLed { it.copy(transfer = "rk3576-four-bit") })).ledTransfer)
        assertSame(LedTransfer.Identity, profile(roundTrips(withLed { it.copy(transfer = "identity") })).ledTransfer)

        val stubWithCurve = issues(withLed { it.copy(transfer = "rk3576-four-bit", floor = 4) })
        assertTrue(stubWithCurve.any { it.path == "hardware.led" && it.message.contains("rk3576-four-bit") })
        val unknown = issues(withLed { it.copy(transfer = "sigmoid") })
        assertTrue(unknown.any { it.path == "hardware.led.transfer" })
    }

    @Test fun theKeyBacklightTakesTheSameCurveDeclaration() {
        val runtime = profile(roundTrips(withButtons(ProfileLightCurve("gamma", gamma = 2.0, floor = 5))))
        assertEquals(TransferCurve.Gamma(2.0, 5 / 255.0), runtime.buttonBacklightTransfer)
        val orphan = issues(withButtons(ProfileLightCurve("perceptual"), present = false))
        assertTrue(orphan.any { it.path == "hardware.button_backlight" && it.message.contains("has_button_backlight") })
    }

    @Test fun metadataDescribesEveryCurveField() {
        val fields = ProfileMetadata.schema.fields.associateBy { it.path }
        val names = listOf("identity", "perceptual", "gamma", "points")
        assertEquals(names, fields.getValue("hardware.backlight.transfer").enumValues)
        assertEquals(names, fields.getValue("hardware.button_backlight.transfer").enumValues)
        assertEquals(listOf("setting", "node"), fields.getValue("hardware.backlight.route").enumValues)
        assertEquals(LedTransfer.NAMES.toList(), fields.getValue("hardware.led.transfer").enumValues)
        for (light in listOf("backlight", "led", "button_backlight")) {
            for (key in listOf("gamma", "points", "floor")) assertFalse("$light.$key", fields.getValue("hardware.$light.$key").required)
        }
        assertFalse(fields.getValue("hardware.backlight.transfer").required)
        assertFalse(fields.getValue("hardware.backlight.route").required)
    }
}
