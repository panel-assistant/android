package io.github.maxlyth.hapaneld.config

import io.github.maxlyth.hapaneld.device.profile.BundledProfileFixtures
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presentation contracts for registry settings: the Camera card's shape and badge, and numeric step
 * grids that the browser enforces as constraint validation. Persistence of every direct-postable setting
 * is proven behaviourally by `SettingsBehaviorContractTest` and `ConfigPostProductionRouteTest`.
 */
class CameraAndStepGridContractTest {
    @Test fun theCameraSettingsAreTheirOwnConfigureCardAndSayTheyAreExperimental() {
        val camera = SettingsRegistry.SPECS.filter { it.key.startsWith("camera_") }
        assertEquals("every camera setting belongs to the Camera card", setOf("Camera"), camera.map { it.group }.toSet())
        assertEquals(
            "the camera card holds the switch, the three stream defaults and the exposure bias",
            listOf("camera_enabled", "camera_resolution", "camera_fps", "camera_kbps", "camera_exposure"),
            camera.map { it.key },
        )

        val configureJs = File(BundledProfileFixtures.mainKotlinDirectory, "../assets/configure.js")
        assertTrue("configure.js must be readable", configureJs.isFile)
        // Read the Camera entry itself, not the whole map. Asserting that the map merely *contains*
        // "exp" is satisfied by the Display card's own badge, so swapping Camera's style to something
        // else survived the mutation battery — the assertion could not fail for the reason it claimed.
        val badges = configureJs.readText().substringAfter("var CARD_BADGES =").substringBefore(";")
        assertTrue("the Camera card must carry a badge: $badges", badges.contains("\"Camera\""))
        val cameraBadge = badges.substringAfter("\"Camera\":").substringAfter("[").substringBefore("]")
        assertEquals(
            "the Camera badge must be the experimental pill in the existing style",
            listOf("\"experimental\"", "\"exp\""),
            cameraBadge.split(",").map { it.trim() },
        )
    }

    /**
     * Every numeric setting's own default must sit on the grid its `min` and `step` describe.
     *
     * `configure.js` assigns `inp.step` straight onto the input, so the browser enforces that grid as
     * constraint validation: a default off the grid is rejected on the very first save, with a message
     * about "the two nearest valid values" and no clue that the registry is at fault. That shipped once —
     * `camera_exposure` declared min -2 with a third-of-a-stop step, which does not contain 0 — and the
     * class is invisible to every behavioural test, because the value is never wrong, only unenterable.
     */
    @Test fun everyNumericSettingsDefaultSitsOnItsOwnStepGrid() {
        val offGrid = SettingsRegistry.SPECS.mapNotNull { spec ->
            val min = spec.min ?: return@mapNotNull null
            val step = spec.step?.takeIf { it > 0.0 } ?: return@mapNotNull null
            val default = spec.default.toDoubleOrNull() ?: return@mapNotNull null
            val steps = (default - min) / step
            if (Math.abs(steps - Math.round(steps)) < 1e-9) null else spec.key
        }
        assertEquals("a default the browser will refuse to save is not a default", emptyList<String>(), offGrid)
    }

    @Test fun theExposureGridOffersTheValuesItsHelpDescribes() {
        val spec = SettingsRegistry.spec("camera_exposure")
        assertNotNull(spec)
        // Whole and half stops within the advertised range must all be enterable, since the help text
        // talks in stops, and a whole stop either way is the first thing anyone will reach for.
        listOf(-2.0, -1.5, -1.0, -0.5, 0.0, 0.5, 1.0, 1.5, 2.0).forEach { value ->
            val steps = (value - spec!!.min!!) / spec.step!!
            assertTrue("$value must be on the grid", Math.abs(steps - Math.round(steps)) < 1e-9)
            assertTrue("$value must be within range", value >= spec.min!! && value <= spec.max!!)
        }
    }
}
