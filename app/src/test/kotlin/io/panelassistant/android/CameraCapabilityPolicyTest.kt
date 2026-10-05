package io.panelassistant.android

import io.panelassistant.android.camera.cameraCapabilityReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether this panel offers a camera at all.
 *
 * Until 2026-09-04 the answer was the profile's declaration alone, and only two bundled profiles carried
 * it, so a panel with camera hardware and no hand-written profile showed no Camera group in Configure, no
 * Home Assistant entities, and nothing to suggest a flag would produce them. The rule below is what makes
 * a camera Android can already enumerate reachable without authoring a profile first.
 *
 * The direction deliberately differs from [zigbeeCapabilityPresent], whose cases sit in
 * `ManagementProjectionPolicyTest`: there an observation may only veto, because a gateway path cannot be
 * discovered; here it may grant, because `CameraManager` enumeration is Android's authoritative answer.
 */
class CameraCapabilityPolicyTest {

    @Test fun anUndeclaredBoardOffersTheCameraAndroidCanSee() {
        assertTrue(cameraCapabilityPresent(declared = null, observed = true))
    }

    @Test fun anUndeclaredBoardWithNothingToEnumerateStaysClosed() {
        assertFalse(cameraCapabilityPresent(declared = null, observed = false))
    }

    @Test fun anUnansweredProbeGrantsNothingOnItsOwn() {
        // The camera service can be unavailable early in boot. That is not evidence of a camera, and it
        // is not evidence against one either: it simply cannot grant the capability by itself.
        assertFalse(cameraCapabilityPresent(declared = null, observed = null))
    }

    @Test fun aDeclarationSurvivesAProbeThatNeverAnswered() {
        // The two boards that declare a camera must not lose it because enumeration threw once.
        assertTrue(cameraCapabilityPresent(declared = true, observed = null))
    }

    @Test fun aDeclarationOutranksAnEmptyEnumeration() {
        assertTrue(cameraCapabilityPresent(declared = true, observed = false))
    }

    @Test fun anExplicitRefusalSuppressesACameraAndroidCanSee() {
        // The reason the field is three states rather than two: a board whose enumerated device is not a
        // usable room camera needs a way to say so, and `false` had no effect while absence meant false.
        assertFalse(cameraCapabilityPresent(declared = false, observed = true))
    }

    @Test fun anExplicitRefusalAlsoHoldsWithNothingEnumerated() {
        assertFalse(cameraCapabilityPresent(declared = false, observed = null))
    }

    /**
     * The capability is now derived from [cameraCapabilityReason] so the panel can explain the answer as
     * well as act on it. This asserts over the whole input space that deriving it changed nothing: a
     * reason that disagreed with the capability would offer settings the gates refuse, or refuse a camera
     * the panel says it has.
     */
    @Test fun theStatedReasonAgreesWithTheCapabilityOnEveryInput() {
        val values = listOf(true, false, null)
        values.forEach { declared ->
            values.forEach { observed ->
                assertEquals(
                    "declared=$declared observed=$observed",
                    cameraCapabilityPresent(declared, observed),
                    cameraCapabilityReason(declared, observed).capable,
                )
            }
        }
    }
}
