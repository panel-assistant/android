package io.panelassistant.android.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProximityStatusBannerTest {
    private fun title(phase: String, health: String = "healthy", present: Boolean = true,
                      active: Boolean = false, ready: Boolean = false, enabled: Boolean = true) =
        ProximityStatusBanner.titleKey(enabled, present, phase, health, active, ready)

    @Test fun unavailableSourceNeverClaimsLearningEvenWithSavedReadiness() {
        assertEquals("configure.proximity.setup.unavailable", title("source_unavailable"))
        assertEquals("configure.proximity.setup.unavailable", title("ready", health = "source_unavailable", ready = true))
        assertEquals("configure.proximity.setup.unavailable", title("unavailable", present = false))
    }

    @Test fun onlyAnExplicitSessionReportsSetupRunning() {
        assertEquals("configure.proximity.setup.calibrating", title("calibrating", health = "source_unavailable", active = true))
        assertEquals("configure.proximity.setup.required", title("calibration_required"))
    }

    @Test fun completedPresenceOnlySetupDoesNotClaimWaveLearning() {
        assertEquals("dashboard.banner.proximity_learning.title", title("ready"))
    }

    @Test fun readyWaveAndDisabledFeatureNeedNoWarning() {
        assertNull(title("ready", ready = true))
        assertNull(title("source_unavailable", enabled = false))
    }
}
