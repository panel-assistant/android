package io.panelassistant.android.control

import io.panelassistant.android.metrics.PanelMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CpuController's pure logic — governor parsing, tier reverse-mapping, and tier→governor resolution.
 * Governors are read through the shared [PanelMetrics] reader (fed a [FakeMetricSource]); writes are
 * asserted through fake su/helper routes. No device, root command, or helper socket is used.
 */
class CpuControllerTest {

    private data class Harness(
        val cpu: CpuController,
        val root: FakeRootShell,
        val daemon: FakeDaemon,
        val metricSource: FakeMetricSource,
    )

    private fun controller(
        avail: String? = null,
        gov: String? = null,
        cpuGovernors: Map<String, String>? = null,
        appCanSu: Boolean = true,
        suResult: Boolean = true,
        daemonReplies: Map<String, String> = emptyMap(),
    ): Harness {
        val root = FakeRootShell(runResult = suResult)
        val daemon = FakeDaemon(replies = daemonReplies)
        val metricSource = FakeMetricSource(governor = gov, availableGovernors = avail)
        val metrics = PanelMetrics(metricSource)
        return Harness(
            CpuController(fakeProfile(appCanSu = appCanSu, cpuGovernors = cpuGovernors), root, daemon, metrics),
            root,
            daemon,
            metricSource,
        )
    }

    @Test fun governorsParsesTheAvailableList() {
        val h = controller(avail = "powersave performance schedutil\n")
        assertEquals(listOf("powersave", "performance", "schedutil"), h.cpu.governors())
        assertTrue(h.cpu.available())
    }

    @Test fun currentTierReverseMapsGovernors() {
        assertEquals(CpuController.PERFORMANCE, controller(gov = "performance").cpu.currentTier())
        assertEquals(CpuController.EFFICIENCY, controller(gov = "powersave").cpu.currentTier())
        // any dynamic governor reads back as Auto
        assertEquals(CpuController.AUTO, controller(gov = "schedutil").cpu.currentTier())
    }

    @Test fun currentTierNullWhenUnreadable() {
        assertNull(controller().cpu.currentTier())
    }

    @Test fun managementReadsPropagateCapturedRootFallbackPolicy() {
        val h = controller(avail = "performance", gov = "performance")

        assertEquals(CpuController.PERFORMANCE, h.cpu.currentTier(allowRootFallback = false))
        assertTrue(h.cpu.available(allowRootFallback = false))
        assertEquals(listOf(false), h.metricSource.governorRootFallbacks)
        assertEquals(listOf(false), h.metricSource.availableGovernorRootFallbacks)

        assertEquals(CpuController.PERFORMANCE, h.cpu.currentTier())
        assertTrue(h.cpu.available())
        assertEquals(listOf(false, true), h.metricSource.governorRootFallbacks)
        assertEquals(listOf(false, true), h.metricSource.availableGovernorRootFallbacks)
    }

    @Test fun setTierResolvesAutoToADynamicGovernorAndWritesIt() {
        val h = controller(avail = "powersave performance schedutil")
        assertTrue(h.cpu.setTier(CpuController.AUTO))
        assertTrue("expected a schedutil write, got ${h.root.ran}", h.root.ran.any { it.contains("echo schedutil") })
        assertTrue("su success short-circuits helper", h.daemon.sent.isEmpty())
    }

    @Test fun setTierPerformancePicksThePerformanceGovernor() {
        val (cpu, root) = controller(avail = "powersave performance schedutil")
        assertTrue(cpu.setTier(CpuController.PERFORMANCE))
        assertTrue(root.ran.any { it.contains("echo performance") })
    }

    @Test fun profileGovernorOverrideHonouredWhenAvailable() {
        // PX30 maps Auto -> interactive; the profile mapping wins when the SoC offers that governor.
        val (cpu, root) = controller(
            avail = "powersave performance interactive schedutil",
            cpuGovernors = mapOf(CpuController.AUTO to "interactive"),
        )
        assertTrue(cpu.setTier(CpuController.AUTO))
        assertTrue(root.ran.any { it.contains("echo interactive") })
    }

    @Test fun setTierFalseWhenNoGovernorsAvailable() {
        val h = controller()
        assertFalse(h.cpu.setTier(CpuController.AUTO))
    }

    @Test fun suPreferredProfileFallsThroughToHelper() {
        val h = controller(
            avail = "performance",
            appCanSu = true,
            suResult = false,
            daemonReplies = mapOf("GOV performance" to "OK"),
        )
        assertTrue(h.cpu.setTier(CpuController.PERFORMANCE))
        assertTrue("preferred su attempted", h.root.ran.isNotEmpty())
        assertEquals(listOf("GOV performance"), h.daemon.sent)
    }

    @Test fun helperPreferredProfileShortCircuitsSuOnSuccess() {
        val h = controller(
            avail = "performance",
            appCanSu = false,
            daemonReplies = mapOf("GOV performance" to "OK"),
        )
        assertTrue(h.cpu.setTier(CpuController.PERFORMANCE))
        assertEquals(listOf("GOV performance"), h.daemon.sent)
        assertTrue("helper success suppresses su", h.root.ran.isEmpty())
    }

    @Test fun helperPreferredProfileFallsThroughToSu() {
        val h = controller(
            avail = "performance",
            appCanSu = false,
            daemonReplies = mapOf("GOV performance" to "ERR"),
        )
        assertTrue(h.cpu.setTier(CpuController.PERFORMANCE))
        assertEquals(listOf("GOV performance"), h.daemon.sent)
        assertTrue("stale helper falls through to su", h.root.ran.isNotEmpty())
    }

    @Test fun setTierFailsAfterBothRoutesFail() {
        val h = controller(
            avail = "performance",
            appCanSu = false,
            suResult = false,
            daemonReplies = mapOf("GOV performance" to "ERR"),
        )
        assertFalse(h.cpu.setTier(CpuController.PERFORMANCE))
        assertEquals(listOf("GOV performance"), h.daemon.sent)
        assertTrue(h.root.ran.isNotEmpty())
    }

    @Test fun rootWriteRequiresEveryGovernorNode() {
        val h = controller(avail = "performance")
        assertTrue(h.cpu.setTier(CpuController.PERFORMANCE))
        val cmd = h.root.ran.single()
        assertTrue(cmd.contains("failed=0"))
        assertTrue(cmd.contains("|| failed=1"))
        assertTrue(cmd.contains("[ \"\$found\" -eq 1 ]"))
        assertTrue(cmd.contains("[ \"\$failed\" -eq 0 ]"))
    }

    @Test fun invalidRuntimeGovernorNeverCrossesPrivilegeBoundary() {
        val h = controller(avail = "performance;reboot")
        assertFalse(h.cpu.setTier(CpuController.AUTO))
        assertTrue(h.root.ran.isEmpty())
        assertTrue(h.daemon.sent.isEmpty())
    }
}
