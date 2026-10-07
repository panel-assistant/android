package io.panelassistant.android.input

import io.panelassistant.android.http.englishCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonCaptureHealthTest {
    private fun snapshot(
        state: EvdevButtonClient.State,
        mode: EvdevStreamSession.Mode? = null,
        error: String? = null,
    ) = EvdevButtonClient.Snapshot(state, mode, error)

    @Test fun verifiedHelperAndAccessibilityAreFullyHealthy() {
        listOf(EvdevStreamSession.Mode.VERIFIED, EvdevStreamSession.Mode.RECONFIGURABLE).forEach { mode ->
            val result = ButtonCaptureHealth.evaluate(true, 1, snapshot(EvdevButtonClient.State.ACTIVE, mode), "pkg")
            assertEquals("ok", result.status)
            assertTrue(result.note.render(englishCatalogue).contains("verified helper stream"))
        }
    }

    @Test fun legacyOrRetryingHelperCannotClaimProfiledButtonsAreVerified() {
        val legacy = ButtonCaptureHealth.evaluate(false, 1, snapshot(EvdevButtonClient.State.ACTIVE, EvdevStreamSession.Mode.LEGACY), "pkg")
        assertEquals("degraded", legacy.status)
        assertTrue(legacy.note.render(englishCatalogue).contains("legacy helper"))

        val retrying = ButtonCaptureHealth.evaluate(true, 1, snapshot(EvdevButtonClient.State.RETRYING, error = "helper rejected WATCH"), "pkg")
        assertEquals("degraded", retrying.status)
        assertTrue(retrying.note.render(englishCatalogue).contains("helper rejected WATCH"))
    }

    @Test fun accessibilityOnlyProfilesRetainTheirExistingTruth() {
        assertEquals("ok", ButtonCaptureHealth.evaluate(true, 0, snapshot(EvdevButtonClient.State.STOPPED), "pkg").status)
        assertEquals("none", ButtonCaptureHealth.evaluate(false, 0, snapshot(EvdevButtonClient.State.STOPPED), "pkg").status)
    }
}
