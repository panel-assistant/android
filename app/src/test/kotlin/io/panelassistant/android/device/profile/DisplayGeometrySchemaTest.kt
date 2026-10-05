package io.panelassistant.android.device.profile

import io.panelassistant.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertNotNull

class DisplayGeometrySchemaTest {
    private val tpa10 get() = BundledProfileFixtures.bundledById.getValue("tpa10")
    private val nspanel get() = BundledProfileFixtures.bundledById.getValue("nspanel-pro")

    private fun withDisplay(display: String): String {
        val raw = tpa10.rawYaml
        val legacy = "display:\n  physical_ppi: 226\n"
        assertTrue("tpa10 fixture no longer carries the legacy display block", legacy in raw)
        return raw.replace(legacy, display)
    }

    private fun parse(raw: String): ProfileDocument {
        val parsed = ProfileYaml.parse(raw)
        assertEquals(emptyList<ProfileIssue>(), parsed.issues)
        return assertNotNull(parsed.document)
    }

    private fun issues(raw: String): List<ProfileIssue> {
        val parsed = ProfileYaml.parse(raw)
        return parsed.issues + (parsed.document?.let { ProfileValidator.validate(it, BuildConfig.VERSION_NAME, bundled = false) } ?: emptyList())
    }

    private val everyField = """
        display:
          geometry:
            - variant: Wide
              product_version_prefixes:
                - TPA10
                - tpa10b
              width_px: 1920
              height_px: 1200
              active_width_mm: 217.5
              active_height_mm: 136.0
              factory_base_dpi: 240
              evidence: measured
              evidence_note: Calipers on the owned unit.
            - width_px: 1280
              height_px: 800
              active_diagonal_in: 10.1
              evidence: module

    """.trimIndent().plus("\n")

    @Test fun everyGeometryFieldRoundTripsThroughTheCanonicalSerializer() {
        val document = parse(withDisplay(everyField))
        val wide = document.display.geometry[0]
        assertEquals("Wide", wide.variant)
        assertEquals(listOf("TPA10", "tpa10b"), wide.productVersionPrefixes)
        assertEquals(1920, wide.widthPx)
        assertEquals(1200, wide.heightPx)
        assertEquals(217.5f, wide.activeWidthMm)
        assertEquals(136.0f, wide.activeHeightMm)
        assertNull(wide.activeDiagonalIn)
        assertEquals(240, wide.factoryBaseDpi)
        assertEquals(DisplayGeometryEvidence.MEASURED, wide.evidence)
        assertEquals("Calipers on the owned unit.", wide.evidenceNote)
        val narrow = document.display.geometry[1]
        assertEquals(10.1f, narrow.activeDiagonalIn)
        assertEquals(DisplayGeometryEvidence.MODULE, narrow.evidence)
        assertNull(narrow.variant)
        assertEquals(emptyList<String>(), narrow.productVersionPrefixes)
        assertNull(document.display.physicalPpi)

        val serialized = ProfileYaml.serialize(document)
        assertEquals(document, parse(serialized))
        assertEquals(serialized, ProfileYaml.serialize(parse(serialized)))
        assertEquals(emptyList<ProfileIssue>(), ProfileValidator.validate(document, BuildConfig.VERSION_NAME, bundled = false))
    }

    @Test fun everyEvidenceKindRoundTrips() {
        DisplayGeometryEvidence.entries.forEach { evidence ->
            val raw = withDisplay(
                "display:\n  geometry:\n    - width_px: 1920\n      height_px: 1200\n" +
                    "      active_diagonal_in: 10.1\n      evidence: ${evidence.yamlName}\n",
            )
            val document = parse(raw)
            assertEquals(evidence, document.display.geometry.single().evidence)
            assertEquals(document, parse(ProfileYaml.serialize(document)))
        }
    }

    @Test fun aProfileCarryingOnlyLegacyPhysicalPpiStillLoadsAndMigratesAsApproximate() {
        val raw = tpa10.rawYaml
        assertTrue(raw.contains("display:\n  physical_ppi: 226\n"))
        val document = parse(raw)
        assertEquals(emptyList<ProfileIssue>(), ProfileValidator.validate(document, BuildConfig.VERSION_NAME, bundled = true))
        assertEquals(226, document.display.physicalPpi)
        assertTrue(document.display.geometry.isEmpty())
        // Content identity survives: the legacy field is serialized back verbatim, not rewritten.
        assertTrue(ProfileYaml.serialize(document).contains("physical_ppi: 226"))
        assertTrue(!ProfileYaml.serialize(document).contains("geometry"))

        val profiled = assertNotNull(tpa10.profile().displayGeometry(1920, 1200))
        val physical = assertNotNull(profiled.physical)
        assertEquals(DisplayGeometryEvidence.APPROXIMATE, physical.evidence)
        assertEquals(226.0, physical.ppi, 0.0)
        assertEquals(10.02, physical.diagonalInches, 0.01)
        assertEquals(215.8, physical.widthMm, 0.1)
        assertEquals(134.9, physical.heightMm, 0.1)
        assertNull("legacy ppi declares no factory-base DPI", profiled.factoryBaseDpi)
    }

    @Test fun nspanelProSelectsItsVariantByProductIdentityAndLivePixels() {
        val compact = assertNotNull(nspanel.profile("NSPanel86P_1.1.7").displayGeometry(480, 480))
        val compactPhysical = assertNotNull(compact.physical)
        assertEquals("86P", compactPhysical.variant)
        assertEquals(DisplayGeometryEvidence.SPECIFICATION, compactPhysical.evidence)
        assertEquals(3.95, compactPhysical.diagonalInches, 1e-6)
        assertEquals(171.8, compactPhysical.ppi, 0.1)
        assertEquals(160, compact.factoryBaseDpi)
        assertNotNull(nspanel.profile("s6_android_2.9.9").displayGeometry(480, 480)?.physical)

        val wide = assertNotNull(nspanel.profile("NSPanel120P_3.5.0").displayGeometry(750, 1334))
        val widePhysical = assertNotNull(wide.physical)
        assertEquals("120P", widePhysical.variant)
        assertEquals(4.7, widePhysical.diagonalInches, 1e-6)
        assertEquals(325.6, widePhysical.ppi, 0.1)
        assertEquals(240, wide.factoryBaseDpi)
        val landscape = assertNotNull(nspanel.profile("NSPanel120P_3.5.0").displayGeometry(1334, 750)?.physical)
        assertEquals(widePhysical.ppi, landscape.ppi, 1e-9)
        assertEquals(widePhysical.widthMm, landscape.heightMm, 1e-9)

        // Identity and pixels must agree; anything else stays unknown rather than borrowing a variant.
        assertNull(nspanel.profile("NSPanel86P_1.1.7").displayGeometry(750, 1334))
        assertNull(nspanel.profile("NSPanel120P_3.5.0").displayGeometry(480, 480))
        assertNull(nspanel.profile("NSPanel86P_1.1.7").displayGeometry(720, 720))
        assertNull(nspanel.profile("unrelated").displayGeometry(480, 480))
    }

    @Test fun validatorRejectsIncompleteAmbiguousAndMixedGeometry() {
        fun codes(display: String) = issues(withDisplay(display)).map { it.path to it.presentation?.code }

        val both = codes(
            "display:\n  physical_ppi: 226\n  geometry:\n    - width_px: 1920\n      height_px: 1200\n" +
                "      active_diagonal_in: 10.1\n      evidence: specification\n",
        )
        assertTrue(both.toString(), ("display.physical_ppi" to "display-geometry-invalid") in both)

        val diagonalAndArea = codes(
            "display:\n  geometry:\n    - width_px: 1920\n      height_px: 1200\n      active_diagonal_in: 10.1\n" +
                "      active_width_mm: 217.5\n      active_height_mm: 136.0\n      evidence: specification\n",
        )
        assertTrue(diagonalAndArea.toString(), ("display.geometry[0]" to "display-geometry-invalid") in diagonalAndArea)

        val noSize = codes("display:\n  geometry:\n    - width_px: 1920\n      height_px: 1200\n      evidence: specification\n")
        assertTrue(noSize.toString(), ("display.geometry[0]" to "display-geometry-invalid") in noSize)

        val halfArea = codes(
            "display:\n  geometry:\n    - width_px: 1920\n      height_px: 1200\n      active_width_mm: 217.5\n      evidence: specification\n",
        )
        assertTrue(halfArea.toString(), ("display.geometry[0]" to "display-geometry-invalid") in halfArea)

        val noEvidence = codes("display:\n  geometry:\n    - width_px: 1920\n      height_px: 1200\n      active_diagonal_in: 10.1\n")
        assertTrue(noEvidence.toString(), ("display.geometry[0].evidence" to "required-string") in noEvidence)

        val duplicate = codes(
            "display:\n  geometry:\n" +
                "    - width_px: 1920\n      height_px: 1200\n      active_diagonal_in: 10.1\n      evidence: specification\n" +
                "    - width_px: 1200\n      height_px: 1920\n      active_diagonal_in: 10.0\n      evidence: specification\n",
        )
        assertTrue(duplicate.toString(), ("display.geometry[1]" to "display-geometry-invalid") in duplicate)

        val implausible = codes("display:\n  geometry:\n    - width_px: 1920\n      height_px: 1200\n      active_diagonal_in: 1.5\n      evidence: specification\n")
        assertTrue(implausible.toString(), ("display.geometry[0]" to "physical-ppi-range") in implausible)

        val badBase = codes(
            "display:\n  geometry:\n    - width_px: 1920\n      height_px: 1200\n      active_diagonal_in: 10.1\n" +
                "      factory_base_dpi: 20\n      evidence: specification\n",
        )
        assertTrue(badBase.toString(), ("display.geometry[0].factory_base_dpi" to "density-range") in badBase)
    }

    @Test fun anAreaDeclaredInOneOrientationFollowsThePanelIntoTheOther() {
        val display = ProfileDisplay(
            geometry = listOf(
                ProfileDisplayGeometry(
                    widthPx = 1920,
                    heightPx = 1200,
                    activeWidthMm = 217.5f,
                    activeHeightMm = 136.0f,
                    evidence = DisplayGeometryEvidence.MEASURED,
                ),
            ),
        )
        val landscape = assertNotNull(DisplayGeometryResolver.resolve(display, "", 1920, 1200)?.physical)
        val portrait = assertNotNull(DisplayGeometryResolver.resolve(display, "", 1200, 1920)?.physical)
        assertEquals(217.5, landscape.widthMm, 1e-9)
        assertEquals(136.0, landscape.heightMm, 1e-9)
        assertEquals(136.0, portrait.widthMm, 1e-9)
        assertEquals(217.5, portrait.heightMm, 1e-9)
        assertEquals(10.10, landscape.diagonalInches, 0.01)
        assertEquals(landscape.ppi, portrait.ppi, 1e-9)
    }

    @Test fun overlappingVariantsLeaveGeometryUnknownRatherThanPickingOne() {
        val display = ProfileDisplay(
            geometry = listOf(
                ProfileDisplayGeometry(widthPx = 480, heightPx = 480, activeDiagonalIn = 3.95f, evidence = DisplayGeometryEvidence.SPECIFICATION),
                ProfileDisplayGeometry(
                    productVersionPrefixes = listOf("NSPanel86P"),
                    widthPx = 480,
                    heightPx = 480,
                    activeDiagonalIn = 4.0f,
                    evidence = DisplayGeometryEvidence.MEASURED,
                ),
            ),
        )
        assertNull(DisplayGeometryResolver.resolve(display, "NSPanel86P_1.1.7", 480, 480))
        assertNotNull(DisplayGeometryResolver.resolve(display, "other", 480, 480))
    }
}
