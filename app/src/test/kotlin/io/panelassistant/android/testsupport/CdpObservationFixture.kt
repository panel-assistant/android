package io.panelassistant.android.testsupport

import io.panelassistant.android.control.CdpRelay
import io.panelassistant.android.control.RelayExposureState
import io.panelassistant.android.control.RelayProcessState
import io.panelassistant.android.control.RemoteDebugSecurityTransitionGate

/** Replace the existing acquisition closures, retaining the real lifecycle and caller wiring. */
internal fun <T> withCdpObservation(
    strict: () -> RelayExposureState,
    display: () -> RelayExposureState = { RelayExposureState.ABSENT },
    body: () -> T,
): T = RemoteDebugSecurityTransitionGate.withLock {
    synchronized(CdpRelay) {
        val processField = CdpRelay::class.java.getDeclaredField("process").apply { isAccessible = true }
        val process = processField.get(null) as RelayProcessState
        val probe = RelayProcessState::class.java.getDeclaredField("probe").apply { isAccessible = true }
        val stopProbe = RelayProcessState::class.java.getDeclaredField("stopProbe").apply { isAccessible = true }
        val savedProbe = probe.get(process)
        val savedStopProbe = stopProbe.get(process)
        try {
            probe.set(process, display)
            stopProbe.set(process, strict)
            body()
        } finally {
            probe.set(process, savedProbe)
            stopProbe.set(process, savedStopProbe)
        }
    }
}
