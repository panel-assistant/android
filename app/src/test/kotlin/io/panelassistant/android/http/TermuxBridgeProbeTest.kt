package io.panelassistant.android.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TermuxBridgeProbeTest {
    @Test fun runningCubePayloadIsDetectedThroughHelperProcessSnapshot() {
        val dump = "@STAT\ncpu 1 2 3 4\n@PROC\n12\t0\trunsv\t10\n34\t0\t/data/data/com.termux/files/usr/bin/node\t12000\n@BRIDGE 1\n@REND\n@END\n"
        assertEquals(TermuxBridgeProbe.State.RUNNING, TermuxBridgeProbe.observe(dump))
        assertEquals("[panel-zigbee-bridge] state=running",
            TermuxBridgeProbe.diagnosticLine(TermuxBridgeProbe.observe(dump)))
    }

    @Test fun stoppedSupervisorAndOrdinaryTermuxAreAbsent() {
        val dump = "@STAT\ncpu 1 2 3 4\n@PROC\n12\t0\trunsv\t10\n35\t0\t/data/data/com.termux/files/usr/bin/bash\t100\n@BRIDGE 0\n@REND\n@END\n"
        assertEquals(TermuxBridgeProbe.State.ABSENT, TermuxBridgeProbe.observe(dump))
        assertEquals("[panel-zigbee-bridge] state=absent",
            TermuxBridgeProbe.diagnosticLine(TermuxBridgeProbe.observe(dump)))
        assertNull(TermuxBridgeProbe.diagnosticLine(TermuxBridgeProbe.State.NOT_APPLICABLE))
    }

    @Test fun anOldHelperOrNoDumpIsUnknown() {
        assertEquals(TermuxBridgeProbe.State.UNKNOWN, TermuxBridgeProbe.observe(null))
        assertEquals("[panel-zigbee-bridge] state=unknown",
            TermuxBridgeProbe.diagnosticLine(TermuxBridgeProbe.observe(null)))
        assertEquals(TermuxBridgeProbe.State.UNKNOWN,
            TermuxBridgeProbe.observe("@PROC\n34\t0\tnode\t12000\n@REND\n@END\n"))
    }

    @Test fun everyReadWithAReadyHelperClassifiesThroughTheHelper() {
        var helperCalls = 0
        repeat(3) {
            assertEquals(TermuxBridgeProbe.State.RUNNING,
                TermuxBridgeProbe.collect(Result.success(10001), { true }) { helperCalls++; "@PROC\n@BRIDGE 1\n@END\n" })
        }
        assertEquals(3, helperCalls)
    }

    @Test fun withoutAReadyHelperTheBridgeIsUnknown() {
        assertEquals(TermuxBridgeProbe.State.UNKNOWN,
            TermuxBridgeProbe.collect(Result.success(10001), { false }) { error("helper is not ready") })
    }

    @Test fun noTermuxOrAnUnreadablePackageManagerMakesNoCalls() {
        assertEquals(TermuxBridgeProbe.State.NOT_APPLICABLE,
            TermuxBridgeProbe.collect(Result.success(null), { error("routes must not be probed") },
                { error("helper must not be called") }))
        assertEquals(TermuxBridgeProbe.State.UNKNOWN,
            TermuxBridgeProbe.collect(Result.failure(IllegalStateException("package manager unavailable")),
                { error("routes must not be probed") }, { error("helper must not be called") }))
    }
}
