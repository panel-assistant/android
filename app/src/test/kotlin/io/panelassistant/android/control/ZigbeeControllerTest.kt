package io.panelassistant.android.control

import io.panelassistant.android.platform.Daemon
import io.panelassistant.android.platform.HelperZigbeeState
import io.panelassistant.android.util.assertNoZigbeeRootReads
import io.panelassistant.android.util.managedZigbeeFiles
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Read preconditions use the helper; only explicitly requested mutations may use root. */
class ZigbeeControllerTest {
    private val dir = "/vendor/bin/siliconlabs_host"
    private val nativeFiles = setOf("guard_process.sh", "run.sh", "zgateway", "mosquitto")
    private val fourXFiles = setOf("run.sh", "zgateway", "mosquitto")

    private class GatewayDaemon(
        var state: HelperZigbeeState? = null,
        var role: String? = null,
        val effects: FakeDaemon = FakeDaemon(mapOf("ZIGBEECONTAIN" to "OK")),
    ) : Daemon by effects {
        var roleReads = 0
        override fun zigbeeState() = state
        override fun zigbeeRole(): String? { roleReads++; return role }
    }

    private fun state(files: Set<String> = managedZigbeeFiles, running: Boolean = false, guard: Boolean = false, version: String? = null) =
        HelperZigbeeState(files, running, guard, version)

    @Test fun capturedLayoutsKeepTheirStatusAndDriverLabels() {
        for ((files, layout, label) in listOf(
            Triple(managedZigbeeFiles, ZigbeeGatewayLayout.MANAGED, "sonoff 3.5.0"),
            Triple(nativeFiles, ZigbeeGatewayLayout.VENDOR_NATIVE, "vendor-native"),
            Triple(fourXFiles, ZigbeeGatewayLayout.VENDOR_4X, "vendor-native 4.x"),
        )) {
            val daemon = GatewayDaemon(state(files, running = true, version = "sonoff-v3.5.4:sonoff-3.5.0"), "Repeater")
            val root = FakeRootShell()
            val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root, daemon)
            val observed = controller.observe(includeRole = true)
            assertEquals(layout, observed.layout)
            assertEquals("$label · running · Repeater", observed.status)
            assertEquals(1, daemon.roleReads)
            assertNoZigbeeRootReads(root)
        }
    }

    @Test fun stoppedAndAbsentMetadataSkipRole() {
        val daemon = GatewayDaemon(state(nativeFiles), "Coordinator")
        val root = FakeRootShell()
        val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root, daemon)
        assertEquals("vendor-native · stopped", controller.observe(includeRole = true).status)
        daemon.state = state(emptySet())
        assertEquals("none", controller.observe(includeRole = true).status)
        assertEquals(0, daemon.roleReads)
        assertNoZigbeeRootReads(root)
    }

    @Test fun unknownFootprintNamesTheSeenFilesButRefusesActions() {
        val daemon = GatewayDaemon(state(setOf("zgateway")))
        val root = FakeRootShell()
        val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root, daemon)
        assertEquals("gateway · unknown layout: zgateway", controller.observe(includeRole = false).status)
        assertFalse(controller.enable())
        assertFalse(controller.disable())
        assertFalse(controller.reconcile(true))
        assertTrue(root.ran.isEmpty())
        assertTrue(daemon.effects.sent.isEmpty())
        assertNoZigbeeRootReads(root)
    }

    @Test fun unsupportedProfileDirectoryIsUnknownWithoutSubmittingAPath() {
        val daemon = GatewayDaemon(state(running = true))
        val root = FakeRootShell()
        val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = "/vendor/other"), root, daemon)
        assertEquals("gateway · status unavailable", controller.observe(includeRole = true).status)
        assertNull(controller.running())
        assertNull(controller.role())
        assertFalse(controller.enable())
        assertFalse(controller.disable())
        assertEquals(0, daemon.roleReads)
        assertTrue(root.ran.isEmpty())
        assertNoZigbeeRootReads(root)
    }

    @Test fun capturedLayoutsDispatchExplicitStartAndStopThroughTheirOwnMechanisms() {
        for (files in listOf(managedZigbeeFiles, nativeFiles, fourXFiles)) {
            val daemon = GatewayDaemon(state(files), "Repeater")
            val root = FakeRootShell()
            val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root, daemon)
            assertTrue(controller.enable())
            assertTrue(controller.disable())
            when (files) {
                managedZigbeeFiles -> {
                    assertTrue("sh $dir/run_guard_process.sh" in root.ran)
                    assertTrue("sh $dir/run_guard_process.sh stop" in root.ran)
                }
                nativeFiles -> {
                    assertTrue(root.ran.any { "nohup sh $dir/guard_process.sh" in it })
                    assertTrue(root.ran.any { "killall -9 zgateway" in it })
                }
                else -> {
                    assertTrue(root.ran.any { "sh $dir/run.sh" in it })
                    assertEquals(listOf("ZIGBEECONTAIN"), daemon.effects.sent)
                }
            }
            assertNoZigbeeRootReads(root)
        }
    }

    @Test fun runningGatewayReassertsKnownCoordinatorRoleWithoutSpawningAnotherGuard() {
        val daemon = GatewayDaemon(state(nativeFiles, running = true), "Coordinator")
        val root = FakeRootShell()
        val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root, daemon)
        assertTrue(controller.reconcile(true))
        assertTrue(root.ran.single().contains("network-role/switch"))
        assertNoZigbeeRootReads(root)
    }

    @Test fun guardStartupInFlightNeverSpawnsASecondSupervisor() {
        val daemon = GatewayDaemon(state(nativeFiles, guard = true))
        val root = FakeRootShell()
        assertTrue(ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root, daemon).reconcile(true))
        assertTrue(root.ran.isEmpty())
        assertNoZigbeeRootReads(root)
    }

    @Test fun explicitOnRetainsBestEffortRepeaterNudgeWhenStartupBrokerIsUnavailable() {
        val daemon = GatewayDaemon(state(nativeFiles, running = true))
        val root = FakeRootShell()
        assertTrue(ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root, daemon).reconcile(true))
        assertTrue(root.ran.single().contains("network-role/switch"))
        assertNoZigbeeRootReads(root)
    }

    @Test fun unknownReadBackCannotProveManagedContainmentComplete() {
        val daemon = GatewayDaemon(state())
        val root = FakeRootShell(onRun = { daemon.state = null })
        val controller = ZigbeeController(fakeProfile(zigbeeGatewayDir = dir), root, daemon)
        val source = AndroidZigbeeGatewayHealthSource(dir, controller, root, daemon) { null }
        assertEquals(ZigbeeContainmentResult.FAILED, source.contain(ZigbeeGatewayLayout.MANAGED))
        assertNoZigbeeRootReads(root)
    }
}
