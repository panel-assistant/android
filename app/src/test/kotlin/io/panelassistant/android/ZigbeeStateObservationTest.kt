package io.panelassistant.android

import io.panelassistant.android.config.Capabilities
import io.panelassistant.android.control.FakeRootShell
import io.panelassistant.android.control.ZigbeeController
import io.panelassistant.android.control.fakeProfile
import io.panelassistant.android.state.StateConverger.Observation
import io.panelassistant.android.testsupport.PanelRuntimeRig
import io.panelassistant.android.util.ZigbeeReadFixture
import io.panelassistant.android.util.assertNoZigbeeRootReads
import io.panelassistant.android.util.zigbeeStateWire
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

internal class ZigbeeStateObservationTest : PanelRuntimeRig() {
    @Test fun configuredOffIntentDoesNotConcealRunningOrUnknownGatewayState() {
        val rig = rig()
        val wire = ZigbeeReadFixture()
        val root = FakeRootShell()
        val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = "/vendor/bin/siliconlabs_host"), root, wire.client)
        // Install real controllers in the existing runtime fixture before its channels are registered.
        for (owner in listOf(rig.core, rig.commands)) {
            owner.javaClass.getDeclaredField("zigbee").apply { isAccessible = true }.set(owner, controller)
        }
        try {
            wire.replies["ZIGBEESTATE"] = zigbeeStateWire(running = true)
            rig.config.setZigbeeRouterEnabled(false)
            val seen = ConcurrentHashMap<String, Observation>()
            rig.core.addStateSink { key, value -> seen[key] = value }
            rig.start()
            fun observed(): Observation? {
                rig.core.ensureCapabilityChannels(Capabilities(zigbeePresent = controller.present()))
                rig.core.reconcile("zigbee_router")
                drainStatePump()
                return seen["zigbee_router"]
            }
            assertEquals(Observation.Known("ON"), observed())
            wire.missing = true
            assertEquals(Observation.Known("unknown"), observed())
            rig.commands.reconcileZigbeeBoot()
            assertTrue(root.ran.isEmpty(), "boot reconciliation must not act on unknown state")
            wire.missing = false
            wire.replies["ZIGBEESTATE"] = zigbeeStateWire()
            assertEquals(Observation.Known("OFF"), observed())
            assertNoZigbeeRootReads(root)
        } finally { rig.close() }
    }
}
