package io.panelassistant.android.sensors

import io.panelassistant.android.config.SettingsRegistry
import io.panelassistant.android.state.StateConverger.Observation
import io.panelassistant.android.testsupport.PanelRuntimeRig
import org.junit.Assert.assertEquals
import org.junit.Test

internal class AdaptiveProximitySurfaceContractTest : PanelRuntimeRig() {
    @Test fun homeAssistantReceivesOnlyFleetNormalizedState() {
        val rig = rig()
        try {
            val seen = mutableListOf<Pair<String, Observation.Reportable>>()
            rig.core.addStateSink { channel, observation ->
                if (channel == "proximity_level") seen += channel to observation
            }

            rig.core.publishProximity(false, -20)
            rig.core.publishProximity(true, 130)

            assertEquals(
                listOf("proximity_level" to Observation.Known("0"), "proximity_level" to Observation.Known("100")),
                seen,
            )
            assertEquals("%", SettingsRegistry.spec("proximity_level")?.ha?.facts?.unit)
        } finally {
            rig.close()
        }
    }

    @Test fun independentReportMaskIsNotCollapsedByTheStateOwner() {
        val rig = rig()
        try {
            val seen = mutableListOf<Pair<String, Observation.Reportable>>()
            rig.core.addStateSink { channel, observation ->
                if (channel == "proximity" || channel == "proximity_level") seen += channel to observation
            }
            rig.core.publishProximity(false, 10)
            assertEquals(
                listOf("proximity" to Observation.Known("OFF"), "proximity_level" to Observation.Known("10")),
                seen,
            )
            seen.clear()

            rig.core.publishProximity(true, 80, ProximityReportGate.PRESENCE)
            assertEquals(listOf("proximity" to Observation.Known("ON")), seen)
            seen.clear()
            // A reconnect/audit must read the admitted level, rather than the suppressed sample.
            rig.core.reconcile("proximity_level")
            assertEquals(listOf("proximity_level" to Observation.Known("10")), seen)
            seen.clear()

            rig.core.publishProximity(false, 90, ProximityReportGate.LEVEL)
            assertEquals(listOf("proximity_level" to Observation.Known("90")), seen)
            seen.clear()
            rig.core.reconcile("proximity")
            assertEquals(listOf("proximity" to Observation.Known("ON")), seen)
        } finally {
            rig.close()
        }
    }
}
