package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.sensors.HaNetworkPath
import io.github.maxlyth.hapaneld.sensors.HaNetworkPathPresentation
import io.github.maxlyth.hapaneld.sensors.HaNetworkPathSeverity
import io.github.maxlyth.hapaneld.sensors.HaSocketState
import io.github.maxlyth.hapaneld.sensors.PathProbeCause
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaNetworkChipVisibilityTest {
    @Test fun aPanelCanHideTheWarningWithoutChangingTheIncident() {
        val path = HaNetworkPath()
        path.onSocketState(HaSocketState.LIVE)
        path.onRoundTrip(0L, 12L)
        path.onPathProbeVerdict(HaNetworkPathSeverity.WARNING, PathProbeCause.LATENCY)
        val incident = path.snapshot(0L)
        assertTrue(incident.degraded)

        val visibility = HaNetworkChipVisibility()
        assertTrue(visibility.update(degraded = incident.degraded, enabled = true))
        assertFalse(visibility.update(degraded = incident.degraded, enabled = false))
        assertEquals("warning", JSONObject(HaNetworkPathPresentation.statusJson(incident)).getString("state"))
        assertTrue(HaNetworkPathPresentation.diagnosticLine(incident).contains("state=warning"))
        assertTrue(HaNetworkPathPresentation.healthToken(incident).contains("ha_net=warning"))
        assertTrue(visibility.update(degraded = incident.degraded, enabled = true))
    }

    @Test fun dismissalLastsUntilThePathRecovers() {
        val visibility = HaNetworkChipVisibility()
        assertTrue(visibility.update(degraded = true, enabled = true))
        visibility.dismiss()
        assertFalse(visibility.update(degraded = true, enabled = true))
        assertFalse(visibility.update(degraded = true, enabled = false))
        assertFalse(visibility.update(degraded = false, enabled = true))
        assertTrue(visibility.update(degraded = true, enabled = true))
    }
}
