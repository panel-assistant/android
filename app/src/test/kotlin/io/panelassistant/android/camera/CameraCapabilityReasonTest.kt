package io.panelassistant.android.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole input space of the capability rule, one case per row, plus the two properties that keep the
 * rule honest: the capability the panel acts on is the reason's own `capable`, and every reason has a
 * distinct wire token so a consumer can tell the cases apart without parsing prose.
 *
 * `CameraCapabilityPolicyTest` covers the same space through `cameraCapabilityPresent` and proves the two
 * agree; this file is about the reason a user is shown.
 */
class CameraCapabilityReasonTest {

    @Test fun aProfileThatSuppressesTheCameraSaysSoWhateverAndroidEnumerated() {
        listOf(true, false, null).forEach { observed ->
            assertEquals(
                "observed=$observed",
                CameraCapabilityReason.SUPPRESSED_BY_PROFILE,
                cameraCapabilityReason(declared = false, observed = observed),
            )
        }
    }

    @Test fun aSilentProfileTakesTheEnumerationsAnswer() {
        assertEquals(CameraCapabilityReason.PRESENT, cameraCapabilityReason(declared = null, observed = true))
        assertEquals(CameraCapabilityReason.NOT_ENUMERATED, cameraCapabilityReason(declared = null, observed = false))
    }

    @Test fun aProbeThatHasNotAnsweredIsUndeterminedRatherThanAbsent() {
        // The camera service can be unavailable early in boot. Reporting that as "no camera on this
        // panel" states a fact about the hardware that nothing has established.
        assertEquals(CameraCapabilityReason.UNDETERMINED, cameraCapabilityReason(declared = null, observed = null))
    }

    @Test fun aDeclarationTheBoardCannotHonourIsMisdeclaredRatherThanPresent() {
        // The capability still holds — a declaration outranks an empty enumeration — but every open will
        // fail with no_camera_id, and the panel can say why before anybody goes looking for a hardware
        // fault that is not there.
        assertEquals(CameraCapabilityReason.MISDECLARED, cameraCapabilityReason(declared = true, observed = false))
        assertTrue(cameraCapabilityReason(declared = true, observed = false).capable)
    }

    @Test fun aDeclarationSurvivesAProbeThatNeverAnsweredOrAgreed() {
        assertEquals(CameraCapabilityReason.PRESENT, cameraCapabilityReason(declared = true, observed = null))
        assertEquals(CameraCapabilityReason.PRESENT, cameraCapabilityReason(declared = true, observed = true))
    }

    @Test fun exactlyTheTwoCapableReasonsOfferTheCamera() {
        val capable = CameraCapabilityReason.entries.filter { it.capable }.toSet()
        assertEquals(setOf(CameraCapabilityReason.PRESENT, CameraCapabilityReason.MISDECLARED), capable)
    }

    @Test fun everyReasonCarriesADistinctWireToken() {
        val wires = CameraCapabilityReason.entries.map { it.wire }
        assertEquals("a consumer tells the cases apart by this token", wires.size, wires.toSet().size)
        assertTrue("tokens are sanitized identifiers", wires.all { it.matches(Regex("[a-z_]+")) })
    }

    @Test fun everyAbsentReasonProducesAnActionAndOnlyCapableOnesAreLeftOut() {
        val absent = CameraCapabilityReason.entries.filterNot { it.capable }
        assertEquals(3, absent.size)
        absent.forEach { reason ->
            val p = CameraPresentation.absent(reason)
            assertEquals(CameraState.ABSENT, p.state)
            assertEquals("the reason travels in fault_detail", reason.wire, p.faultDetail)
            assertFalse("${reason.name} must offer something to do next", p.action == "none")
            assertTrue("${reason.name} must have a summary", p.summary.isNotBlank())
        }
    }

    @Test fun theThreeAbsentCasesAreToldApartByWhatTheySayAndNotOnlyByTheirToken() {
        val suppressed = CameraPresentation.absent(CameraCapabilityReason.SUPPRESSED_BY_PROFILE)
        val none = CameraPresentation.absent(CameraCapabilityReason.NOT_ENUMERATED)
        val pending = CameraPresentation.absent(CameraCapabilityReason.UNDETERMINED)
        val distinct = setOf(suppressed.action, none.action, pending.action)
        assertEquals("each case needs its own next action", 3, distinct.size)
        assertEquals("each case needs its own summary", 3, setOf(suppressed.summary, none.summary, pending.summary).size)
        // The undetermined case must not assert anything about the hardware, in either field.
        assertFalse(pending.summary.contains("no camera"))
        assertFalse(pending.action.contains("no camera"))
    }
}
