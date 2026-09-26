package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.control.ZigbeeObservation
import io.github.maxlyth.hapaneld.control.ZigbeeGatewayLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagementProjectionPolicyTest {
    @Test fun confirmedAbsenceOverridesADeclaredCandidatePath() {
        assertFalse(zigbeeCapabilityPresent(declaredGateway = true, observation = observation(
            probeSucceeded = true,
            present = false,
        )))
    }

    @Test fun unreadableProbePreservesTheDeclaredCapabilityWithoutInventingRuntimeStatus() {
        val unknown = observation(probeSucceeded = false, present = false)

        assertTrue(zigbeeCapabilityPresent(declaredGateway = true, observation = unknown))
        assertFalse(unknown.present)
        assertTrue(unknown.status.contains("unavailable"))
    }

    @Test fun noDeclarationCannotAcquireTheCapabilityFromAnUnexpectedRuntimeMarker() {
        assertFalse(zigbeeCapabilityPresent(declaredGateway = false, observation = observation(
            probeSucceeded = true,
            present = true,
        )))
    }

    private fun observation(probeSucceeded: Boolean, present: Boolean) = ZigbeeObservation(
        probeSucceeded = probeSucceeded,
        present = present,
        layout = ZigbeeGatewayLayout.VENDOR_NATIVE,
        running = present,
        driver = if (present) "vendor-native" else null,
        role = null,
    )
}
